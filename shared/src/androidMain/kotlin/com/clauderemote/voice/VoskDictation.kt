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
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Offline Czech speech-to-text on top of Vosk, used when the device's
 * built-in `SpeechRecognizer` doesn't support cs-CZ (HyperOS / non-GMS
 * devices, …).
 *
 * The model — the same `vosk-model-small-cs-0.4-rhasspy` we already use
 * for wake-word — must be downloaded via [VoskModelManager] beforehand.
 *
 * Lifecycle: [start] opens the mic + spins up a worker that feeds 16-kHz
 * mono PCM into a free-form Vosk recognizer; partials and finals are
 * pushed back through callbacks on the main thread. [stop] flushes the
 * recognizer and releases resources synchronously enough for a follow-up
 * [SpeechRecognizer] start not to race the mic.
 */
internal class VoskDictation(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stopped = false

    fun start() {
        if (!VoskModelManager.isModelReady(context)) {
            postOnMain {
                onError(
                    "Český model Vosk není stažený. " +
                        "Otevřete Nastavení → Voice a stáhněte český model."
                )
            }
            return
        }
        if (!hasMicPermission()) {
            postOnMain { onError("Chybí oprávnění mikrofonu.") }
            return
        }
        if (job != null) return
        stopped = false
        job = scope.launch { runLoop() }
    }

    fun stop() {
        stopped = true
        job?.cancel()
        job = null
    }

    @SuppressLint("MissingPermission")
    private fun runLoop() {
        val modelDir = VoskModelManager.modelDir(context).absolutePath
        val model = runCatching { Model(modelDir) }.getOrElse {
            postOnMain { onError("Nepodařilo se otevřít Vosk model: ${it.message ?: "neznámá chyba"}") }
            return
        }
        val recognizer = runCatching { Recognizer(model, SAMPLE_RATE_HZ.toFloat()) }.getOrElse {
            runCatching { model.close() }
            postOnMain { onError("Nepodařilo se vytvořit Vosk rozpoznávač: ${it.message ?: "neznámá chyba"}") }
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            runCatching { recognizer.close() }
            runCatching { model.close() }
            postOnMain { onError("AudioRecord není dostupný.") }
            return
        }
        val bufferSize = maxOf(minBuf, FRAME_SAMPLES * 2 * 4)
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }.getOrElse {
            runCatching { recognizer.close() }
            runCatching { model.close() }
            postOnMain { onError("Mikrofon nelze otevřít: ${it.message ?: "neznámá chyba"}") }
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            runCatching { recognizer.close() }
            runCatching { model.close() }
            postOnMain { onError("AudioRecord se neinicializoval.") }
            return
        }

        val buf = ByteArray(FRAME_SAMPLES * 2)
        try {
            recorder.startRecording()
            while (!stopped && scope.isActive) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) continue
                val isFinal = recognizer.acceptWaveForm(buf, n)
                if (isFinal) {
                    val text = parseField(recognizer.result, "text")
                    if (text.isNotBlank()) postOnMain { onFinal(text) }
                } else {
                    val partial = parseField(recognizer.partialResult, "partial")
                    if (partial.isNotBlank()) postOnMain { onPartial(partial) }
                }
            }
            // Drain any speech still buffered at stop time.
            val flush = parseField(recognizer.finalResult, "text")
            if (flush.isNotBlank()) postOnMain { onFinal(flush) }
        } catch (_: Throwable) {
            // Fall through to cleanup.
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { recognizer.close() }
            runCatching { model.close() }
        }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun parseField(json: String?, key: String): String =
        runCatching { JSONObject(json ?: return@runCatching "").optString(key) }
            .getOrNull()
            .orEmpty()
            .trim()

    companion object {
        private const val SAMPLE_RATE_HZ = 16000
        // ~250 ms chunks @ 16 kHz mono = 4000 16-bit samples.
        private const val FRAME_SAMPLES = 4000
    }
}
