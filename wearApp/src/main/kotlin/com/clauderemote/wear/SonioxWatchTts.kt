package com.clauderemote.wear

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * On-watch streaming TTS via Soniox's WebSocket API — the watch reads a
 * session message aloud in Soniox's voice instead of the on-device Android
 * TTS ([WatchTts]). Port of the phone's SonioxTts (wearApp has no dependency
 * on `shared`).
 *
 * Audio comes as G.711 **μ-law @ 8 kHz** (raw, 8-bit companded PCM). A watch
 * has no Wi-Fi, so Soniox audio is tunnelled through the phone's Bluetooth
 * link, and 24 kHz mono 16-bit PCM (~384 kbps) overruns that link and
 * stutters. μ-law @ 8 kHz is ~64 kbps — ~1/6th the bytes — which the tunnel
 * carries comfortably. It's raw, so each byte expands to a 16-bit PCM sample
 * with a trivial lookup (no MediaCodec, which blocks the WS thread and stalls
 * the stream); the PCM feeds a jitter prebuffer → AudioTrack in MODE_STREAM so
 * playback starts on the first chunk. 100 ms silence lead-in +
 * drain-before-release avoid clipping the first/last word. Requires a Soniox
 * key synced from the phone ([SonioxKeyStore]); [WatchTts] remains the
 * fallback when there's no key.
 */
object SonioxWatchTts {
    private const val TAG = "SonioxWatchTts"
    private const val WS_URL = "wss://tts-rt.soniox.com/tts-websocket"
    private const val MODEL = "tts-rt-v1"
    private const val SAMPLE_RATE = 8000 // μ-law is only allowed at 8000 Hz
    private const val DRAIN_TICK_MS = 20L
    private const val DRAIN_MAX_TICKS = 400

    private val http = OkHttpClient.Builder().build()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private val ws = AtomicReference<WebSocket?>(null)
    private val track = AtomicReference<AudioTrack?>(null)
    private val completion = AtomicReference<(() -> Unit)?>(null)

    /** @param onDone fired (on main) when playback finishes on its own or fails. */
    fun speak(context: Context, text: String, onDone: () -> Unit = {}) {
        val apiKey = SonioxKeyStore.apiKey
        if (apiKey.isBlank()) {
            WearLog.w(context, TAG, "no Soniox key — falling back to on-device TTS")
            WatchTts.speak(context, text)
            main.post(onDone)
            return
        }
        stop()
        completion.set(onDone)
        val gen = generation.incrementAndGet()
        val req = Request.Builder().url(WS_URL).build()
        ws.set(http.newWebSocket(req, Listener(
            context.applicationContext, gen, apiKey,
            SonioxKeyStore.ttsVoice, SonioxKeyStore.ttsSpeedPct, text,
        )))
    }

    /** Stop playback now (the "stop reading" button / a superseding speak). */
    fun stop() {
        generation.incrementAndGet()
        completion.set(null) // caller-initiated stop — don't fire onDone
        ws.getAndSet(null)?.let { runCatching { it.close(1000, null) } }
        track.getAndSet(null)?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            runCatching { it.release() }
        }
    }

    private fun fireDone() {
        completion.getAndSet(null)?.let { main.post(it) }
    }

    private class Listener(
        private val ctx: Context,
        private val gen: Int,
        private val apiKey: String,
        private val voice: String,
        private val speedPct: Int,
        private val text: String,
    ) : WebSocketListener() {
        private val streamId = "cr-$gen"
        @Volatile private var writtenFrames = 0
        // Pre-play jitter buffer. Accumulate ~0.75 s of decoded audio before
        // the first play() so an uneven downlink can't underrun AudioTrack and
        // stutter. Holds expanded PCM, not the μ-law bytes off the WS. Accessed
        // only from the WS listener thread (see writePcm), so no extra locking.
        private val prebuffer = java.io.ByteArrayOutputStream()
        @Volatile private var playing = false

        // Byte budgets for μ-law @ 8 kHz mono → 16-bit PCM (bytes/s = rate * 2).
        private val bytesPerSec get() = SAMPLE_RATE * 2
        private val prebufferBytes get() = bytesPerSec * 3 / 4 // ~0.75 s
        private val leadInBytes get() = bytesPerSec / 10        // ~0.1 s
        private fun framesOf(bytes: Int) = bytes / 2

        override fun onOpen(webSocket: WebSocket, response: Response) {
            WearLog.i(ctx, TAG, "WS open")
            val cfg = JSONObject().apply {
                put("api_key", apiKey)
                put("stream_id", streamId)
                put("model", MODEL)
                put("language", "cs")
                put("voice", voice.ifBlank { "Adrian" })
                // Raw μ-law instead of a compressed codec — see class KDoc
                // (Bluetooth downlink budget). Soniox rejects μ-law at any rate
                // other than 8000 with a 400.
                put("audio_format", "pcm_mulaw")
                put("sample_rate", SAMPLE_RATE)
            }
            webSocket.send(cfg.toString())
            webSocket.send(JSONObject().put("stream_id", streamId).put("text", text).put("text_end", true).toString())
        }

        override fun onMessage(webSocket: WebSocket, textMsg: String) {
            if (gen != generation.get()) return
            val obj = runCatching { JSONObject(textMsg) }.getOrNull() ?: return
            val err = obj.optString("error_code").takeIf { it.isNotBlank() && it != "null" }
            if (err != null) {
                WearLog.w(ctx, TAG, "server error: $err — ${obj.optString("error_message")}")
                fireDone()
                stop()
                return
            }
            val b64 = obj.optString("audio")
            if (b64.isNotBlank()) {
                // base64 carries μ-law bytes — expand to 16-bit PCM and feed
                // the jitter buffer → AudioTrack.
                val mulaw = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                if (mulaw != null && mulaw.isNotEmpty()) writePcm(mulawToPcm16(mulaw))
            }
            if (obj.optBoolean("audio_end") || obj.optBoolean("terminated")) {
                finishPlayback(webSocket)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation.get()) return
            WearLog.w(ctx, TAG, "WS failure: ${t.message}")
            stop() // releases the track
        }

        private fun writePcm(pcm: ByteArray) {
            if (gen != generation.get()) return
            if (playing) {
                val t = track.get() ?: return
                runCatching { t.write(pcm, 0, pcm.size) }
                writtenFrames += framesOf(pcm.size)
                return
            }
            // Still filling the jitter buffer — hold audio until we have
            // prebufferBytes, then start playback with that head start.
            prebuffer.write(pcm, 0, pcm.size)
            if (prebuffer.size() >= prebufferBytes) startPlayback()
        }

        /** Create the track, prime it with lead-in silence + the buffered audio, and play. */
        private fun startPlayback() {
            if (playing || gen != generation.get()) return
            val channelMask = AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT,
            )
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(channelMask)
                        .build(),
                )
                // ~1 s hardware buffer — slack to ride out downlink jitter
                // without underrunning.
                .setBufferSizeInBytes(maxOf(minBuf, bytesPerSec))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.set(t)
            // Reading speed synced from the phone, pitch-preserved.
            if (speedPct != 100) runCatching {
                t.playbackParams = t.playbackParams.setSpeed((speedPct / 100f).coerceIn(0.5f, 2.5f))
            }
            runCatching { t.play() }
            val silence = ByteArray(leadInBytes)
            runCatching { t.write(silence, 0, silence.size) }
            writtenFrames += framesOf(silence.size)
            val buffered = prebuffer.toByteArray()
            prebuffer.reset()
            runCatching { t.write(buffered, 0, buffered.size) }
            writtenFrames += framesOf(buffered.size)
            playing = true
            WearLog.i(ctx, TAG, "playback started (prebuffered ${buffered.size} B @ ${SAMPLE_RATE}Hz mono)")
        }

        private fun finishPlayback(webSocket: WebSocket) {
            if (gen != generation.get()) return
            // Short message whose audio never reached the prebuffer threshold:
            // flush what we have so it still plays.
            if (!playing && prebuffer.size() > 0) startPlayback()
            track.getAndSet(null)?.let { t ->
                runCatching {
                    var guard = 0
                    while (gen == generation.get() &&
                        t.playbackHeadPosition < writtenFrames &&
                        guard++ < DRAIN_MAX_TICKS
                    ) {
                        Thread.sleep(DRAIN_TICK_MS)
                    }
                    t.stop()
                }
                runCatching { t.release() }
            }
            ws.compareAndSet(webSocket, null)
            runCatching { webSocket.close(1000, null) }
            fireDone()
        }

        // G.711 μ-law → 16-bit little-endian PCM. Inverse of the STT uplink
        // encode; a correct expand is required or the audio is noise.
        private fun mulawToPcm16(mulaw: ByteArray): ByteArray {
            val out = ByteArray(mulaw.size * 2)
            var j = 0
            for (b in mulaw) {
                val s = mulawToLinear(b.toInt() and 0xFF)
                out[j++] = (s and 0xFF).toByte()          // little-endian
                out[j++] = ((s shr 8) and 0xFF).toByte()
            }
            return out
        }

        private fun mulawToLinear(mu: Int): Int {
            val u = mu.inv() and 0xFF
            val sign = u and 0x80
            val exponent = (u shr 4) and 0x07
            val mantissa = u and 0x0F
            var sample = ((mantissa shl 3) + 0x84) shl exponent
            sample -= 0x84
            return if (sign != 0) -sample else sample     // 16-bit signed
        }
    }
}
