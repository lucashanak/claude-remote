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
    // Fired (on main) the instant the mic is actually capturing, so the UI
    // shows "listening" only when speech will really be recorded — not while
    // AudioRecord is still warming up (which clipped the user's first word).
    private val onListening: (() -> Unit)? = null,
    // Fired (on main) with a rolling interim transcript while the user is
    // still speaking, so the UI can show the words progressively instead of
    // only after end-of-utterance. Whisper is batch, so this re-transcribes
    // the audio-so-far on a cadence — a few extra calls per utterance.
    private val onPartial: ((String) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stopped = false
    private val interimInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
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
        val preRoll = ArrayDeque<ByteArray>()
        val preRollFrames = (PREROLL_MS / FRAME_MS).coerceAtLeast(1)
        var inSpeech = false
        var silenceFrames = 0
        var speechFrames = 0
        var framesSinceInterim = 0
        val buf = ShortArray(FRAME)
        val silenceFrameLimit = (SILENCE_MS / FRAME_MS)
        val minSpeechFrames = (MIN_SPEECH_MS / FRAME_MS)
        val interimEveryFrames = (INTERIM_MS / FRAME_MS).coerceAtLeast(1)
        try {
            recorder.startRecording()
            postOnMain { if (!stopped) onListening?.invoke() }
            while (!stopped && scope.isActive) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) continue
                val frame = frameBytes(buf, n)
                val rms = rms(buf, n)
                val voiced = rms > RMS_THRESHOLD
                if (voiced) {
                    if (!inSpeech) {
                        // Entering speech — prepend the pre-roll so the soft
                        // onset of the first word (often below threshold)
                        // isn't clipped.
                        inSpeech = true
                        framesSinceInterim = 0
                        while (preRoll.isNotEmpty()) captured.write(preRoll.removeFirst())
                    }
                    silenceFrames = 0
                    speechFrames++
                } else if (inSpeech) {
                    silenceFrames++
                }
                if (inSpeech) {
                    captured.write(frame)
                    framesSinceInterim++
                } else {
                    // Keep a rolling window of the most recent quiet frames.
                    preRoll.addLast(frame)
                    while (preRoll.size > preRollFrames) preRoll.removeFirst()
                }

                // Progressive transcription: while still speaking, re-transcribe
                // the audio-so-far on a cadence and surface it as a partial.
                if (onPartial != null && inSpeech && speechFrames >= minSpeechFrames &&
                    framesSinceInterim >= interimEveryFrames
                ) {
                    framesSinceInterim = 0
                    maybeInterim(captured.toByteArray())
                }

                val endOfUtterance = inSpeech &&
                    silenceFrames >= silenceFrameLimit &&
                    speechFrames >= minSpeechFrames
                if (endOfUtterance) {
                    val pcm = captured.toByteArray()
                    captured.reset()
                    inSpeech = false; silenceFrames = 0; speechFrames = 0; framesSinceInterim = 0
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

    /**
     * Fire-and-forget interim transcription of the audio captured so far.
     * Runs off the capture loop and skips if one is already in flight, so a
     * slow round-trip can't stall recording or pile up requests. Errors are
     * swallowed — interim is best-effort; the final flush is authoritative.
     */
    private fun maybeInterim(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (!interimInFlight.compareAndSet(false, true)) return
        scope.launch {
            val text = runCatching { transcribe(pcm) }.getOrNull()
            interimInFlight.set(false)
            if (!stopped && !text.isNullOrBlank()) postOnMain { onPartial?.invoke(text) }
        }
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

    /** Little-endian 16-bit PCM bytes for one captured frame. */
    private fun frameBytes(buf: ShortArray, n: Int): ByteArray {
        val out = ByteArray(n * 2)
        var j = 0
        for (i in 0 until n) {
            val v = buf[i].toInt()
            out[j++] = (v and 0xFF).toByte()
            out[j++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
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
        private const val FRAME = 480           // 30 ms @ 16 kHz — fine onset/endpoint granularity
        private const val FRAME_MS = 30
        private const val PREROLL_MS = 300      // audio kept before speech onset, prepended so the first word isn't clipped
        private const val SILENCE_MS = 600      // end-of-utterance silence
        // Tuned permissively — RMS 1200 / min 500 ms dropped the user's
        // own voice; rely on the Whisper `prompt` + hallucination filter
        // to handle quiet-audio attractors instead of the VAD gating.
        private const val MIN_SPEECH_MS = 300
        private const val INTERIM_MS = 900      // cadence of progressive re-transcription while speaking
        private const val RMS_THRESHOLD = 600.0

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
