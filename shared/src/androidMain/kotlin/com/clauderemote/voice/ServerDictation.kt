package com.clauderemote.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Streams microphone audio to a self-hosted OpenAI-compatible STT server
 * (faster-whisper / Speaches) and returns the transcript.
 *
 * Endpointing is a lightweight energy (RMS) VAD — no model download — so
 * the engine is self-contained: it just needs the server URL. When speech
 * is followed by [SILENCE_MS] of quiet, the captured utterance is encoded
 * as 16-kHz mono WAV and POSTed to `/v1/audio/transcriptions`. Batch, so
 * no live partials. [continuous] loops for voice mode.
 */
internal class ServerDictation(
    private val context: Context,
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
    private val continuous: Boolean,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stopped = false
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun start() {
        if (baseUrl.isBlank()) {
            postOnMain { onError("Není nastavená adresa serveru (Nastavení → Voice).") }
            return
        }
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            postOnMain { onError("Chybí oprávnění mikrofonu.") }
            return
        }
        if (job != null) return
        stopped = false
        job = scope.launch { runLoop() }
    }

    /** Stop listening AND flush whatever is buffered as a final utterance. */
    fun stop() {
        stopped = true
        job?.cancel()
        job = null
    }

    @SuppressLint("MissingPermission")
    private fun runLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            postOnMain { onError("AudioRecord není dostupný.") }
            return
        }
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME * 2 * 8),
            )
        }.getOrElse {
            postOnMain { onError("Mikrofon nelze otevřít: ${it.message ?: "neznámá chyba"}") }
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            postOnMain { onError("AudioRecord se neinicializoval.") }
            return
        }

        val captured = ByteArrayOutputStream()
        var inSpeech = false
        var silenceFrames = 0
        var speechFrames = 0
        val buf = ShortArray(FRAME)
        val silenceFrameLimit = (SILENCE_MS / FRAME_MS)
        val minSpeechFrames = (MIN_SPEECH_MS / FRAME_MS)
        try {
            recorder.startRecording()
            while (!stopped && scope.isActive) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) continue
                val rms = rms(buf, n)
                val voiced = rms > RMS_THRESHOLD
                if (voiced) {
                    inSpeech = true
                    silenceFrames = 0
                    speechFrames++
                } else if (inSpeech) {
                    silenceFrames++
                }
                if (inSpeech) appendPcm(captured, buf, n)

                val endOfUtterance = inSpeech &&
                    silenceFrames >= silenceFrameLimit &&
                    speechFrames >= minSpeechFrames
                if (endOfUtterance) {
                    val pcm = captured.toByteArray()
                    captured.reset()
                    inSpeech = false; silenceFrames = 0; speechFrames = 0
                    flush(pcm)
                    if (!continuous) {
                        stopped = true
                        break
                    }
                }
            }
            // On manual stop with buffered speech, transcribe the tail.
            if (captured.size() > 0 && speechFrames >= minSpeechFrames) {
                flush(captured.toByteArray())
            }
        } catch (_: Throwable) {
            // fall through to cleanup
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }

    private fun flush(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val text = runCatching { transcribe(pcm) }.getOrElse {
            postOnMain { onError("Server: ${it.message ?: "chyba přenosu"}") }
            return
        }
        if (text.isNotBlank()) postOnMain { onFinal(text) }
    }

    private fun transcribe(pcm: ByteArray): String {
        val wav = wrapWav(pcm)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("language", "cs")
            .addFormDataPart("response_format", "json")
            // Context biasing — pulls Whisper away from its movie-subtitle
            // training-data attractors ("Titulky vytvořil JohnyX.",
            // "Děkuji za sledování.", "amara.org", …) when audio is short
            // or noisy.
            .addFormDataPart("prompt", "Krátká česká hlasová zpráva uživatele asistentovi.")
            .build()
        val url = normalizeApiBase(baseUrl) + "/v1/audio/transcriptions"
        val req = Request.Builder().url(url).post(body).apply {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }.build()
        http.newCall(req).execute().use { resp ->
            val payload = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val text = runCatching { JSONObject(payload).optString("text") }
                .getOrDefault("")
                .trim()
            return if (isHallucination(text)) "" else text
        }
    }

    /** Drop the canonical Whisper hallucinations that survive prompt biasing. */
    private fun isHallucination(text: String): Boolean {
        val t = text.lowercase().trim().trim('.', ',', '!', '?', ' ')
        return t.isEmpty() || HALLUCINATIONS.any { t == it || t.contains(it) }
    }

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val s = buf[i].toDouble()
            sum += s * s
        }
        return Math.sqrt(sum / n)
    }

    private fun appendPcm(out: ByteArrayOutputStream, buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            val v = buf[i].toInt()
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
    }

    private fun wrapWav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        val totalDataLen = 36 + pcm.size
        val byteRate = SAMPLE_RATE * 2 // mono * 16-bit
        fun w(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        w("RIFF"); le32(totalDataLen); w("WAVE")
        w("fmt "); le32(16); le16(1); le16(1); le32(SAMPLE_RATE); le32(byteRate); le16(2); le16(16)
        w("data"); le32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME = 1600          // 100 ms @ 16 kHz
        private const val FRAME_MS = 100
        private const val SILENCE_MS = 700      // end-of-utterance silence
        private const val MIN_SPEECH_MS = 500   // ignore blips (was 300 —
                                                // sub-300 ms blobs almost
                                                // always produce Whisper
                                                // hallucinations)
        private const val RMS_THRESHOLD = 1200.0 // bumped from 500 — quieter
                                                 // env noise was passing the
                                                 // VAD and feeding silence
                                                 // to Whisper

        private val HALLUCINATIONS = listOf(
            "titulky vytvořil johnyx",
            "titulky: amara.org",
            "titulky: amara org",
            "amara.org",
            "amara org",
            "děkuji za sledování",
            "děkujeme za sledování",
            "titulky vytvořila",
            "překlad titulky",
            "titulky a překlad",
            "thanks for watching",
            "subtitles by",
        )
    }
}
