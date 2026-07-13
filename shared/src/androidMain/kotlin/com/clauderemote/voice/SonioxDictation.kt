package com.clauderemote.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
 * Real-time streaming STT via Soniox's WebSocket API
 * (`wss://stt-rt.soniox.com/transcribe-websocket`). Unlike [ServerDictation]
 * (batch: energy-VAD → record utterance → POST a WAV, no live partials),
 * this streams raw 16-kHz PCM straight from the mic and gets word-by-word
 * tokens back with sub-200ms latency, so the transcript visibly grows as
 * the user speaks.
 *
 * Each server message carries a `tokens` array; every token has `text` and
 * `is_final`. Final tokens are appended to [finalText] and never change;
 * non-final tokens are the provisional tail that keeps getting rewritten.
 * [onPartial] fires with `finalText + provisional tail` on every message
 * (that's the live-growing text), and [onFinal] fires with the settled
 * transcript once the stream flushes.
 *
 * Tokens include spaces and punctuation as their own tokens, so raw
 * concatenation of `text` reconstructs spacing — we never insert spaces
 * ourselves. `language_hints: ["cs","en"]` is what makes mixed Czech +
 * English (dictating code with English identifiers) transcribe in one pass.
 *
 * No client-side VAD/WAV/hallucination-filter: Soniox does endpointing
 * server-side, and a streaming token model doesn't invent "Titulky
 * vytvořil…" on silence the way batch Whisper does.
 */
internal class SonioxDictation(
    private val context: Context,
    private val apiKey: String,
    private val continuous: Boolean,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListening: (() -> Unit)? = null,
    private val onPartial: ((String) -> Unit)? = null,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // No read timeout — the socket is idle-ish between the user's words.
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var captureThread: Thread? = null

    // Guarded by `this` — mutated from the WS listener thread, read when
    // building each partial/final string.
    private val finalText = StringBuilder()

    fun start() {
        if (apiKey.isBlank()) {
            postOnMain { onError("Chybí Soniox API klíč (Nastavení → Voice).") }
            return
        }
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            postOnMain { onError("Chybí oprávnění mikrofonu.") }
            return
        }
        val request = Request.Builder().url(WS_URL).build()
        ws = http.newWebSocket(request, Listener())
    }

    /** Stop capture and flush whatever's buffered as a final transcript. */
    fun stop() {
        if (stopped) return
        stopped = true
        captureThread?.interrupt()
        captureThread = null
        // Empty string = end-of-audio; server finalizes remaining tokens and
        // replies with finished:true, which fires onFinal + closes the socket.
        runCatching { ws?.send("") }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val config = JSONObject().apply {
                put("api_key", apiKey)
                put("model", MODEL)
                put("audio_format", "pcm_s16le")
                put("sample_rate", SAMPLE_RATE)
                put("num_channels", 1)
                put("language_hints", JSONArray(listOf("cs", "en")))
                put("enable_endpoint_detection", true)
                // NOTE: Soniox also has a `context` field for vocabulary
                // biasing (our terms: Claude, commit, ralph…), but its exact
                // format isn't confirmed from the docs — left out until the
                // confirmed-fields config is verified working on-device, so a
                // rejected field can't break the whole connection.
            }
            webSocket.send(config.toString())
            startCapture(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
            obj.optString("error_code").takeIf { it.isNotBlank() && it != "null" }?.let {
                postOnMain { onError("Soniox: ${obj.optString("error_message").ifBlank { it }}") }
                return
            }
            val tokens = obj.optJSONArray("tokens")
            val provisional = StringBuilder()
            if (tokens != null) {
                for (i in 0 until tokens.length()) {
                    val tok = tokens.optJSONObject(i) ?: continue
                    val t = tok.optString("text")
                    // Endpoint / control tokens ("<end>", "<fin>") aren't
                    // transcript text — skip them; in continuous mode an
                    // endpoint flushes the current utterance as final.
                    if (t.startsWith("<") && t.endsWith(">")) {
                        if (continuous && tok.optBoolean("is_final")) flushUtterance()
                        continue
                    }
                    if (tok.optBoolean("is_final")) {
                        synchronized(this@SonioxDictation) { finalText.append(t) }
                    } else {
                        provisional.append(t)
                    }
                }
            }
            val running = synchronized(this@SonioxDictation) { finalText.toString() } + provisional
            if (running.isNotBlank()) postOnMain { onPartial?.invoke(running.trim()) }

            if (obj.optBoolean("finished")) {
                val settled = synchronized(this@SonioxDictation) { finalText.toString() }.trim()
                postOnMain { onFinal(settled) }
                runCatching { webSocket.close(1000, null) }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stopped) return
            postOnMain { onError("Soniox: ${t.message ?: "chyba spojení"}") }
        }
    }

    /** Continuous mode only: emit the utterance-so-far and reset for the next. */
    private fun flushUtterance() {
        val settled = synchronized(this) {
            val s = finalText.toString().trim()
            finalText.setLength(0)
            s
        }
        if (settled.isNotBlank()) postOnMain { onFinal(settled) }
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(webSocket: WebSocket) {
        val thread = Thread {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                postOnMain { onError("AudioRecord není dostupný.") }
                return@Thread
            }
            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, FRAME_BYTES * 8),
                )
            }.getOrElse {
                postOnMain { onError("Mikrofon nelze otevřít: ${it.message ?: "neznámá chyba"}") }
                return@Thread
            }
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                postOnMain { onError("AudioRecord se neinicializoval.") }
                return@Thread
            }
            val buf = ByteArray(FRAME_BYTES)
            try {
                recorder.startRecording()
                postOnMain { if (!stopped) onListening?.invoke() }
                while (!stopped && !Thread.currentThread().isInterrupted) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val chunk: ByteString =
                        if (n == buf.size) buf.toByteString() else buf.toByteString(0, n)
                    if (!webSocket.send(chunk)) break
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
        private const val WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket"
        private const val MODEL = "stt-rt-v5"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_BYTES = 3200 // 100 ms @ 16 kHz mono 16-bit
    }
}
