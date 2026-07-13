package com.clauderemote.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.clauderemote.util.FileLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Streaming text-to-speech via Soniox's WebSocket API
 * (`wss://tts-rt.soniox.com/tts-websocket`). The old REST path POSTed the
 * whole text and waited for the ENTIRE mp3 to generate + download before a
 * single sound — fine for the short settings-test sentence, but a ~30s wait
 * for a long chat reply.
 *
 * Here the text is sent once (`text_end: true`) and the server streams back
 * base64 PCM chunks (`{"audio": "..."}`) as it generates; each chunk is
 * decoded and written straight to an [AudioTrack] in MODE_STREAM, so
 * playback starts on the first chunk (~hundreds of ms) instead of after the
 * whole utterance. AudioTrack's blocking `write` provides natural
 * backpressure, so we never buffer the whole reply in memory.
 *
 * Not file/MediaPlayer based (that's [MediaTtsCore], used by the other
 * engines) — a growing PCM stream needs AudioTrack, not a temp file.
 */
internal object SonioxTts {
    private const val TAG = "SonioxTts"
    private const val WS_URL = "wss://tts-rt.soniox.com/tts-websocket"
    private const val MODEL = "tts-rt-v1"
    private const val SAMPLE_RATE = 24000
    private const val LEAD_IN_BYTES = 4800   // 100 ms silence @ 24kHz mono 16-bit
    private const val DRAIN_TICK_MS = 20L
    private const val DRAIN_MAX_TICKS = 400   // 8s cap

    private val http = OkHttpClient.Builder().build()

    // Monotonic token: each speak()/stop() bumps it so a superseded stream's
    // late audio chunks can't play over a newer utterance.
    private val generation = AtomicInteger(0)
    private val ws = AtomicReference<WebSocket?>(null)
    private val track = AtomicReference<AudioTrack?>(null)
    private val completion = AtomicReference<(() -> Unit)?>(null)

    fun speak(
        context: Context,
        apiKey: String,
        voice: String,
        text: String,
        rate: Float,
        onFinish: () -> Unit,
        onError: ((String) -> Unit)?,
    ) {
        stop() // supersede anything in flight
        val gen = generation.incrementAndGet()
        completion.set(onFinish)
        if (apiKey.isBlank()) {
            onError?.let { postOnMain { it("Chybí Soniox API klíč") } }
            fireCompletion()
            return
        }
        val listener = Listener(gen, voice, apiKey, text, rate, onError)
        ws.set(http.newWebSocket(Request.Builder().url(WS_URL).build(), listener))
    }

    fun stop() {
        generation.incrementAndGet() // supersede
        ws.getAndSet(null)?.let { runCatching { it.close(1000, null) } }
        track.getAndSet(null)?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            runCatching { it.release() }
        }
        fireCompletion()
    }

    private fun fireCompletion() {
        completion.getAndSet(null)?.let { postOnMain(it) }
    }

    private class Listener(
        private val gen: Int,
        private val voice: String,
        private val apiKey: String,
        private val text: String,
        private val rate: Float,
        private val onError: ((String) -> Unit)?,
    ) : WebSocketListener() {

        private val streamId = "cr-$gen"
        // Frames written to the track so far (mono 16-bit → 2 bytes/frame).
        // Used to drain the buffer fully before release so the last word
        // isn't cut off.
        @Volatile private var writtenFrames = 0

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val config = JSONObject().apply {
                put("api_key", apiKey)
                put("stream_id", streamId) // required — server rejects config without it
                put("model", MODEL)
                put("language", "cs")
                put("voice", voice.ifBlank { "Adrian" })
                put("audio_format", "pcm_s16le")
                put("sample_rate", SAMPLE_RATE)
                // Speed is applied client-side via AudioTrack.playbackParams
                // (pitch-preserving time-stretch), NOT via Soniox's `speed`
                // field — that field hard-rejects anything outside 0.7–1.3
                // with a 400 (confirmed against the live API), which would
                // break TTS entirely at a slider setting like 1.5.
            }
            webSocket.send(config.toString())
            // Whole reply in one shot; server streams audio as it generates.
            webSocket.send(
                JSONObject().put("stream_id", streamId).put("text", text).put("text_end", true).toString(),
            )
        }

        override fun onMessage(webSocket: WebSocket, textMsg: String) {
            if (gen != generation.get()) return
            val obj = runCatching { JSONObject(textMsg) }.getOrNull() ?: return
            val err = obj.optString("error_code").takeIf { it.isNotBlank() && it != "null" }
            if (err != null) {
                val msg = obj.optString("error_message").ifBlank { err }
                FileLogger.log(TAG, "server error: $err — $msg")
                onError?.let { postOnMain { it("Soniox TTS: $msg") } }
                stop()
                return
            }
            val b64 = obj.optString("audio")
            if (b64.isNotBlank()) {
                val pcm = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                if (pcm != null && pcm.isNotEmpty()) writePcm(pcm)
            }
            if (obj.optBoolean("audio_end") || obj.optBoolean("terminated")) {
                finishPlayback(webSocket)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation.get()) return
            FileLogger.log(TAG, "WS failure: ${t.message}")
            onError?.let { postOnMain { it("Soniox TTS: ${t.message ?: "chyba spojení"}") } }
            stop()
        }

        /** Lazily create + start the AudioTrack on the first chunk, then write. */
        private fun writePcm(pcm: ByteArray) {
            if (gen != generation.get()) return
            var t = track.get()
            if (t == null) {
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE)) // ~0.5s @ 24kHz mono 16-bit
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.set(t)
                // Reading speed, pitch-preserved. Clamped to a device-safe
                // range; if the device rejects the value, playback just stays
                // at 1.0 rather than failing.
                if (rate != 1.0f) runCatching {
                    t.playbackParams = t.playbackParams.setSpeed(rate.coerceIn(0.5f, 2.5f))
                }
                runCatching { t.play() }
                // Lead-in silence: MODE_STREAM warms up on the first buffer and
                // clips the very start otherwise — a short pad means the real
                // first word starts after the track is already running.
                val silence = ByteArray(LEAD_IN_BYTES)
                runCatching { t.write(silence, 0, silence.size) }
                writtenFrames += LEAD_IN_BYTES / 2
                FileLogger.log(TAG, "playback started (speed=$rate)")
            }
            // Blocking write = natural backpressure; the OkHttp reader thread
            // paces to playback speed instead of buffering the whole reply.
            runCatching { t.write(pcm, 0, pcm.size) }
            writtenFrames += pcm.size / 2
        }

        private fun finishPlayback(webSocket: WebSocket) {
            if (gen != generation.get()) return
            track.getAndSet(null)?.let { t ->
                // Wait for the play head to reach the last written frame before
                // stopping — releasing right after the final write() cut the
                // last word off (the buffered tail hadn't played yet). Capped
                // so a stuck head can't hang the thread.
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
            fireCompletion()
        }
    }
}
