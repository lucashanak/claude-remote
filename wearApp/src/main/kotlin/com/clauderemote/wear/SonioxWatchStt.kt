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
    @Volatile private var stopped = false
    @Volatile private var captureThread: Thread? = null
    @Volatile private var finalFired = false
    // Total mic bytes actually pushed to Soniox — logged with the final so a
    // "0 chars" result tells apart "mic captured nothing" (≈0 bytes, e.g.
    // screen slept and throttled capture) from "audio sent, Soniox heard
    // silence / wrong format" (many bytes, 0 chars).
    @Volatile private var bytesSent = 0L
    private val finalText = StringBuilder()

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
            WearLog.i(context, TAG, "WS open")
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
            startCapture(webSocket)
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
        WearLog.i(context, TAG, "final (${settled.length} chars, $bytesSent mic bytes sent)")
        main.post { onFinal(settled) }
        runCatching { webSocket.close(1000, null) }
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(webSocket: WebSocket) {
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
                    val chunk: ByteString =
                        if (n == buf.size) buf.toByteString() else buf.toByteString(0, n)
                    if (!webSocket.send(chunk)) break
                    bytesSent += n
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
    }
}
