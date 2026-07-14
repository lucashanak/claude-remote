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
 * on `shared`). Text is sent once, base64 PCM chunks stream back and go
 * straight to an AudioTrack in MODE_STREAM so playback starts on the first
 * chunk. 100 ms silence lead-in + drain-before-release avoid clipping the
 * first/last word. Requires a Soniox key synced from the phone
 * ([SonioxKeyStore]); [WatchTts] remains the fallback when there's no key.
 */
object SonioxWatchTts {
    private const val TAG = "SonioxWatchTts"
    private const val WS_URL = "wss://tts-rt.soniox.com/tts-websocket"
    private const val MODEL = "tts-rt-v1"
    private const val SAMPLE_RATE = 24000
    private const val LEAD_IN_BYTES = 4800
    // Jitter buffer: accumulate ~0.75 s of audio before the first play() so a
    // slow/uneven downlink (Soniox PCM tunnelled through the phone's Bluetooth
    // link on a watch with no Wi-Fi) can't underrun AudioTrack and stutter.
    // 24 kHz mono 16-bit = 48000 B/s, so 0.75 s = SAMPLE_RATE * 3 / 2 bytes.
    private const val PREBUFFER_BYTES = SAMPLE_RATE * 3 / 2
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
        // Pre-play jitter buffer (see PREBUFFER_BYTES). Accessed only from the
        // WS listener thread (onMessage is serialized), so no extra locking.
        private val prebuffer = java.io.ByteArrayOutputStream()
        @Volatile private var playing = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            WearLog.i(ctx, TAG, "WS open")
            val cfg = JSONObject().apply {
                put("api_key", apiKey)
                put("stream_id", streamId)
                put("model", MODEL)
                put("language", "cs")
                put("voice", voice.ifBlank { "Adrian" })
                put("audio_format", "pcm_s16le")
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
                val pcm = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                if (pcm != null && pcm.isNotEmpty()) writePcm(pcm)
            }
            if (obj.optBoolean("audio_end") || obj.optBoolean("terminated")) finishPlayback(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (gen != generation.get()) return
            WearLog.w(ctx, TAG, "WS failure: ${t.message}")
            stop()
        }

        private fun writePcm(pcm: ByteArray) {
            if (gen != generation.get()) return
            if (playing) {
                val t = track.get() ?: return
                runCatching { t.write(pcm, 0, pcm.size) }
                writtenFrames += pcm.size / 2
                return
            }
            // Still filling the jitter buffer — hold audio until we have
            // PREBUFFER_BYTES, then start playback with that head start.
            prebuffer.write(pcm, 0, pcm.size)
            if (prebuffer.size() >= PREBUFFER_BYTES) startPlayback()
        }

        /** Create the track, prime it with lead-in silence + the buffered audio, and play. */
        private fun startPlayback() {
            if (playing || gen != generation.get()) return
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
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
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                // ~1 s hardware buffer (was ~0.5 s) — more slack to ride out
                // downlink jitter without underrunning.
                .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.set(t)
            // Reading speed synced from the phone, pitch-preserved.
            if (speedPct != 100) runCatching {
                t.playbackParams = t.playbackParams.setSpeed((speedPct / 100f).coerceIn(0.5f, 2.5f))
            }
            runCatching { t.play() }
            val silence = ByteArray(LEAD_IN_BYTES)
            runCatching { t.write(silence, 0, silence.size) }
            writtenFrames += LEAD_IN_BYTES / 2
            val buffered = prebuffer.toByteArray()
            prebuffer.reset()
            runCatching { t.write(buffered, 0, buffered.size) }
            writtenFrames += buffered.size / 2
            playing = true
            WearLog.i(ctx, TAG, "playback started (prebuffered ${buffered.size} B)")
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
    }
}
