package com.clauderemote.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Offline Czech STT via sherpa-onnx Whisper (small int8) with Silero VAD
 * for endpointing.
 *
 * Whisper is a batch model — there are no live partial results. The flow is:
 * record → VAD detects end-of-utterance → transcribe the whole segment →
 * emit [onFinal]. For [continuous] mode (voice mode) the loop keeps going;
 * for one-shot dictation the caller stops after the first final.
 *
 * Heavy init (loading ~375 MB of ONNX) happens on the worker thread inside
 * [start]; expect a second or two before the first transcript.
 */
internal class WhisperDictation(
    private val context: Context,
    private val continuous: Boolean,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stopped = false

    fun start() {
        if (!WhisperModelManager.isModelReady(context)) {
            postOnMain { onError("Whisper model není stažený. Otevřete Nastavení → Voice a stáhněte ho.") }
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

    fun stop() {
        stopped = true
        job?.cancel()
        job = null
    }

    @SuppressLint("MissingPermission")
    private fun runLoop() {
        val recognizer = runCatching { buildRecognizer() }.getOrElse {
            postOnMain { onError("Whisper init selhal: ${it.message ?: "neznámá chyba"}") }
            return
        }
        val vad = runCatching { buildVad() }.getOrElse {
            runCatching { recognizer.release() }
            postOnMain { onError("VAD init selhal: ${it.message ?: "neznámá chyba"}") }
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            runCatching { vad.release() }
            runCatching { recognizer.release() }
            postOnMain { onError("AudioRecord není dostupný.") }
            return
        }
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, WINDOW_SIZE * 2 * 8),
            )
        }.getOrElse {
            runCatching { vad.release() }
            runCatching { recognizer.release() }
            postOnMain { onError("Mikrofon nelze otevřít: ${it.message ?: "neznámá chyba"}") }
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            runCatching { vad.release() }
            runCatching { recognizer.release() }
            postOnMain { onError("AudioRecord se neinicializoval.") }
            return
        }

        val shortBuf = ShortArray(WINDOW_SIZE)
        try {
            recorder.startRecording()
            while (!stopped && scope.isActive) {
                val n = recorder.read(shortBuf, 0, shortBuf.size)
                if (n <= 0) continue
                val floats = FloatArray(n) { shortBuf[it] / 32768.0f }
                vad.acceptWaveform(floats)
                while (!vad.empty()) {
                    val segment = vad.front()
                    val text = transcribe(recognizer, segment.samples)
                    vad.pop()
                    if (text.isNotBlank()) {
                        postOnMain { onFinal(text) }
                        if (!continuous) {
                            stopped = true
                            break
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Fall through to cleanup.
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { vad.release() }
            runCatching { recognizer.release() }
        }
    }

    private fun transcribe(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } catch (_: Throwable) {
            ""
        } finally {
            runCatching { stream.release() }
        }
    }

    private fun buildRecognizer(): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = WhisperModelManager.path(context, WhisperModelManager.ENCODER),
                    decoder = WhisperModelManager.path(context, WhisperModelManager.DECODER),
                    language = "cs",
                    task = "transcribe",
                    tailPaddings = 1000,
                ),
                tokens = WhisperModelManager.path(context, WhisperModelManager.TOKENS),
                modelType = "whisper",
                numThreads = 2,
            ),
        )
        return OfflineRecognizer(assetManager = null, config = config)
    }

    private fun buildVad(): Vad {
        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = WhisperModelManager.path(context, WhisperModelManager.VAD),
                threshold = 0.5f,
                minSilenceDuration = 0.4f,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW_SIZE,
                maxSpeechDuration = 15.0f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        )
        return Vad(assetManager = null, config = config)
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        // Silero VAD operates on 512-sample windows at 16 kHz (32 ms).
        private const val WINDOW_SIZE = 512
    }
}
