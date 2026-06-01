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
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * On-device wake word via sherpa-onnx [KeywordSpotter] — a streaming zipformer
 * KWS model (gigaspeech, bundled int8 under `assets/kws/`). Fully offline, no
 * account/key. The keyword is supplied as BPE tokens at stream creation, so the
 * user's choice needs no rebuild. Detection fires [onWake] on the main thread.
 */
internal class SherpaKws(
    private val context: Context,
    private val keywordTokens: String,
    private val onWake: () -> Unit,
    private val onError: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stopped = false

    fun start() {
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
        runCatching { SherpaNative.ensureLoaded() }.onFailure {
            postOnMain { onError("Nelze načíst sherpa-onnx: ${it.message ?: "chyba"}") }
            return
        }
        val spotter = runCatching {
            val model = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$ASSET_DIR/encoder.int8.onnx",
                    decoder = "$ASSET_DIR/decoder.int8.onnx",
                    joiner = "$ASSET_DIR/joiner.int8.onnx",
                ),
                tokens = "$ASSET_DIR/tokens.txt",
                numThreads = 1,
                modelType = "zipformer2",
            )
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = model,
                keywordsFile = "$ASSET_DIR/keywords.txt",
            )
            KeywordSpotter(context.assets, config)
        }.getOrElse {
            postOnMain { onError("KWS init selhal: ${it.message ?: "chyba"}") }
            return
        }
        val stream = runCatching { spotter.createStream(keywordTokens) }.getOrElse {
            runCatching { spotter.release() }
            postOnMain { onError("KWS stream selhal: ${it.message ?: "chyba"}") }
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME * 2 * 4),
            )
        }.getOrNull()
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder?.release() }
            runCatching { spotter.release() }
            postOnMain { onError("Mikrofon nelze otevřít.") }
            return
        }
        val buf = ShortArray(FRAME)
        val fbuf = FloatArray(FRAME)
        try {
            recorder.startRecording()
            while (!stopped && scope.isActive) {
                val n = recorder.read(buf, 0, buf.size)
                if (n <= 0) continue
                for (i in 0 until n) fbuf[i] = buf[i] / 32768.0f
                val samples = if (n == fbuf.size) fbuf else fbuf.copyOf(n)
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (spotter.isReady(stream)) spotter.decode(stream)
                if (spotter.getResult(stream).keyword.isNotEmpty()) {
                    spotter.reset(stream)
                    if (!stopped) postOnMain { onWake() }
                }
            }
        } catch (_: Throwable) {
            // fall through to cleanup
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { spotter.release() }
        }
    }

    companion object {
        private const val ASSET_DIR = "kws"
        private const val SAMPLE_RATE = 16000
        private const val FRAME = 1600 // 100 ms @ 16 kHz

        /** Wake word -> BPE tokens for the gigaspeech model (sentencepiece). */
        val KEYWORDS: Map<String, String> = linkedMapOf(
            "HEY CLAUDE" to "▁HE Y ▁C LA U DE",
            "CLAUDE" to "▁C LA U DE",
            "COMPUTER" to "▁COMP U TER",
            "JARVIS" to "▁JA R VI S",
        )
    }
}
