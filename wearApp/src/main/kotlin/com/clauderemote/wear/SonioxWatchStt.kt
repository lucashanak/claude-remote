package com.clauderemote.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * On-watch streaming STT via Soniox's WebSocket API — dictate a reply on the
 * watch in Soniox's Czech (+English code-switching) instead of the system
 * voice input. Port of the phone's SonioxDictation (wearApp has no dependency
 * on `shared`). Streams raw 16-kHz PCM from the mic, gets word-by-word tokens
 * back; [onPartial] fires with the growing transcript, [onFinal] with the
 * settled text once an endpoint (silence) is detected or [stop] is called.
 * Requires a Soniox key synced from the phone ([SonioxKeyStore]) and the
 * RECORD_AUDIO permission.
 *
 * Unlike the phone port, mic capture starts the instant [start] is called
 * (NOT in onOpen): on a watch with no Wi-Fi the Soniox socket is tunnelled
 * through the phone's Bluetooth link and the handshake can take seconds — so
 * whatever the user says before onOpen would be lost ("cuts off the start").
 * Frames captured before the socket is ready are buffered and flushed the
 * moment it opens.
 */
internal class SonioxWatchStt(
    private val context: Context,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListening: (() -> Unit)? = null,
    private val onPartial: ((String) -> Unit)? = null,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ws: WebSocket? = null
    // Set once the socket is open + configured — the capture thread reads this
    // to decide whether to stream live or keep buffering into [backlog].
    @Volatile private var liveSocket: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var captureThread: Thread? = null
    @Volatile private var finalFired = false
    private val finalText = StringBuilder()

    // PCM frames captured before the socket opened, flushed on onOpen. Guarded
    // by itself. Capped so a socket that never opens can't grow it unbounded.
    private val backlog = ArrayDeque<ByteString>()

    // Diagnostics logged with the final, so a bad transcript is explainable
    // without a device in hand:
    //  - connect latency: how long onOpen took (≈ how much lead the pre-buffer
    //    had to cover; large = the old code would have clipped that much).
    //  - mic bytes: ≈0 means the mic captured nothing (permission/screen-off),
    //    lots + 0 chars means audio flowed but Soniox heard silence/garbage.
    //  - peak WS queue: bytes stuck in OkHttp's send buffer — grows when the
    //    uplink (BT tunnel) can't keep up with 256 kbit/s raw PCM, i.e. the
    //    bandwidth-limited case that hurts accuracy.
    private var startNanos = 0L
    @Volatile private var connectMs = -1L
    @Volatile private var bytesSent = 0L
    @Volatile private var peakQueueBytes = 0L

    fun start() {
        val apiKey = SonioxKeyStore.apiKey
        if (apiKey.isBlank()) {
            main.post { onError("Chybí Soniox klíč (nastav ho na telefonu).") }
            return
        }
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            main.post { onError("Chybí oprávnění mikrofonu.") }
            return
        }
        startNanos = System.nanoTime()
        // Capture BEFORE the socket handshake — see class kdoc. Frames buffer
        // into [backlog] until onOpen flips [liveSocket] on.
        startCapture()
        ws = http.newWebSocket(Request.Builder().url(WS_URL).build(), Listener(apiKey))
    }

    fun stop() {
        if (stopped) return
        stopped = true
        captureThread?.interrupt()
        captureThread = null
        runCatching { ws?.send("") }
    }

    private inner class Listener(private val apiKey: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connectMs = (System.nanoTime() - startNanos) / 1_000_000
            WearLog.i(context, TAG, "WS open (connect $connectMs ms)")
            val config = JSONObject().apply {
                put("api_key", apiKey)
                put("model", MODEL)
                put("audio_format", "pcm_s16le")
                put("sample_rate", SAMPLE_RATE)
                put("num_channels", 1)
                put("language_hints", JSONArray(listOf("cs", "en")))
                put("enable_endpoint_detection", true)
            }
            webSocket.send(config.toString())
            // Capture thread now drains [backlog] then streams live.
            liveSocket = webSocket
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
            val err = obj.optString("error_code").takeIf { it.isNotBlank() && it != "null" }
            if (err != null) {
                val msg = obj.optString("error_message").ifBlank { err }
                WearLog.w(context, TAG, "server error: $err — $msg")
                main.post { onError("Soniox: $msg") }
                return
            }
            val tokens = obj.optJSONArray("tokens")
            val provisional = StringBuilder()
            var endpoint = false
            if (tokens != null) {
                for (i in 0 until tokens.length()) {
                    val tok = tokens.optJSONObject(i) ?: continue
                    val t = tok.optString("text")
                    if (t.startsWith("<") && t.endsWith(">")) {
                        if (tok.optBoolean("is_final")) endpoint = true
                        continue
                    }
                    if (tok.optBoolean("is_final")) {
                        synchronized(this@SonioxWatchStt) { finalText.append(t) }
                    } else {
                        provisional.append(t)
                    }
                }
            }
            val running = synchronized(this@SonioxWatchStt) { finalText.toString() } + provisional
            if (running.isNotBlank()) main.post { onPartial?.invoke(running.trim()) }
            if (endpoint || obj.optBoolean("finished")) fireFinalOnce(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stopped) return
            WearLog.w(context, TAG, "WS failure: ${t.message}")
            main.post { onError("Soniox: ${t.message ?: "chyba spojení"}") }
        }
    }

    private fun fireFinalOnce(webSocket: WebSocket) {
        if (finalFired) return
        finalFired = true
        stopped = true
        captureThread?.interrupt()
        captureThread = null
        val settled = synchronized(this) { finalText.toString() }.trim()
        WearLog.i(
            context, TAG,
            "final (${settled.length} chars, $bytesSent mic bytes, connect $connectMs ms, peak WS queue $peakQueueBytes B)",
        )
        main.post { onFinal(settled) }
        runCatching { webSocket.close(1000, null) }
    }

    /** Flush frames captured before the socket opened, in order. */
    private fun drainBacklog(socket: WebSocket) {
        synchronized(backlog) {
            while (backlog.isNotEmpty()) {
                val frame = backlog.peekFirst()
                if (!socket.send(frame)) return
                backlog.removeFirst()
                bytesSent += frame.size
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCapture() {
        val thread = Thread {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                main.post { onError("AudioRecord není dostupný.") }
                return@Thread
            }
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, FRAME_BYTES * 8),
                )
            }.getOrElse {
                main.post { onError("Mikrofon nelze otevřít: ${it.message ?: "neznámá chyba"}") }
                return@Thread
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                main.post { onError("AudioRecord se neinicializoval.") }
                return@Thread
            }
            val buf = ByteArray(FRAME_BYTES)
            try {
                recorder.startRecording()
                main.post { if (!stopped) onListening?.invoke() }
                while (!stopped && !Thread.currentThread().isInterrupted) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    // Copy out of the reused buffer before queuing/sending.
                    val chunk: ByteString =
                        if (n == buf.size) buf.toByteString() else buf.toByteString(0, n)
                    val socket = liveSocket
                    if (socket != null) {
                        drainBacklog(socket)
                        if (!socket.send(chunk)) break
                        bytesSent += n
                        val q = socket.queueSize()
                        if (q > peakQueueBytes) peakQueueBytes = q
                    } else {
                        // Socket not open yet — buffer, but bound the backlog so
                        // a never-opening socket can't grow it without limit.
                        synchronized(backlog) {
                            backlog.addLast(chunk)
                            while (backlog.size > MAX_BACKLOG_FRAMES) backlog.removeFirst()
                        }
                    }
                }
            } catch (_: Throwable) {
                // fall through to cleanup
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
        }
        thread.isDaemon = true
        captureThread = thread
        thread.start()
    }

    companion object {
        private const val TAG = "SonioxWatchStt"
        private const val WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket"
        private const val MODEL = "stt-rt-v5"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_BYTES = 3200
        // ~15 s of 100 ms frames — a generous ceiling on pre-open buffering.
        private const val MAX_BACKLOG_FRAMES = 150
    }
}
