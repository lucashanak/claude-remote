package com.clauderemote.voice

import com.clauderemote.util.FileLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Single-shot streaming dictation on desktop: `javax.sound.sampled` captures
 * 16-kHz mono PCM, each frame is converted inline to G.711 μ-law and pushed to
 * Soniox's realtime WebSocket, which streams tokens back.
 *
 * This is the desktop twin of SonioxDictation.kt on Android and deliberately
 * keeps its protocol decisions — μ-law because it is a table lookup rather
 * than a codec (no encoder thread, no first-word latency: an earlier AAC path
 * on Android cost ~3.6 s) at half the uplink of raw PCM, and a client-side
 * silence timer rather than the server's `<end>` as the thing that ends
 * dictation. Continuous (voice-mode) capture is Android-only, so this class is
 * single-shot only.
 *
 * Soniox is the only backend wired up here because it is the one that needs
 * nothing but an API key: no local model, no self-hosted server reachable from
 * wherever the desktop app happens to run.
 *
 * Callbacks arrive on OkHttp's and the timer's threads — the caller does its
 * own hop to the UI thread.
 */
internal class DesktopDictation(
    private val apiKey: String,
    private val silenceMs: Int,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // No read timeout — the socket is idle-ish between the user's words.
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var finalFired = false
    @Volatile private var line: TargetDataLine? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var bytesSent = 0L

    // Guarded by `this` — appended from the WS thread, read when building each
    // partial and the final.
    private val finalText = StringBuilder()

    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cr-dictation-silence").apply { isDaemon = true }
    }
    @Volatile private var silenceTask: ScheduledFuture<*>? = null

    fun start() {
        if (apiKey.isBlank()) {
            onError("Missing Soniox API key.")
            return
        }
        ws = http.newWebSocket(Request.Builder().url(WS_URL).build(), Listener())
    }

    /** Stops capture and asks the server to finalize what it already has. */
    fun stop() {
        if (stopped) return
        stopped = true
        cancelSilence()
        closeLine()
        // Empty frame = end of audio: the server settles the remaining tokens
        // and answers `finished`, which fires onFinal and closes the socket.
        runCatching { ws?.send("") }
        // ...but only if it answers. A server that never sends `finished`
        // would leave this socket open forever — the 20s ping keeps it alive,
        // Soniox bills streaming time, and desktop builds one OkHttpClient per
        // session so the threads never go away either. Give the settle a
        // bounded window, then close regardless.
        timer.schedule({ shutdownTransport() }, FINISH_GRACE_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Closes the socket and releases the per-session OkHttp client. Idempotent:
     * both the settle timeout and a normal `finished` reply land here.
     */
    private fun shutdownTransport() {
        runCatching { ws?.close(1000, null) }
        ws = null
        runCatching {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
        // The scheduler is per-session too. shutdown() (not shutdownNow) so a
        // teardown running ON this executor finishes the task it is inside.
        runCatching { timer.shutdown() }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // The connect takes a few hundred milliseconds, which is long
            // enough for the user to tap stop or navigate away first. Without
            // this check the microphone would still be opened afterwards — the
            // capture loop exits on its own, but the OS mic indicator lights
            // for an instant AFTER the user asked us to stop listening.
            if (stopped) {
                runCatching { webSocket.close(1000, null) }
                return
            }
            FileLogger.log(TAG, "WS open")
            webSocket.send(sonioxDictationConfig(apiKey))
            startCapture(webSocket)
            // Armed immediately with the long guard so a session that never
            // produces a token — a muted mic, a false start — still ends
            // instead of hanging; every real word re-arms it short below.
            armSilence(webSocket, maxOf(NO_SPEECH_GUARD_MS, silenceMs.toLong() + 2000L))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
            val err = obj.optString("error_code").takeIf { it.isNotBlank() && it != "null" }
            if (err != null) {
                val msg = obj.optString("error_message").ifBlank { err }
                FileLogger.log(TAG, "server error: $err — $msg")
                onError("Soniox: $msg")
                return
            }
            val tokens = obj.optJSONArray("tokens")
            val provisional = StringBuilder()
            var sawRealToken = false
            if (tokens != null) {
                for (i in 0 until tokens.length()) {
                    val tok = tokens.optJSONObject(i) ?: continue
                    val t = tok.optString("text")
                    // Control tokens ("<end>", "<fin>") aren't transcript text.
                    if (t.startsWith("<") && t.endsWith(">")) continue
                    sawRealToken = true
                    if (tok.optBoolean("is_final")) {
                        synchronized(this@DesktopDictation) { finalText.append(t) }
                    } else {
                        provisional.append(t)
                    }
                }
            }
            val running = synchronized(this@DesktopDictation) { finalText.toString() } + provisional
            if (running.isNotBlank()) onPartial(running.trim())
            // The client-side timer is authoritative, so restart the countdown
            // from the last word: a mid-sentence pause must not cut the
            // speaker off.
            if (sawRealToken) armSilence(webSocket, silenceMs.toLong())
            if (obj.optBoolean("finished")) fireFinalOnce(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stopped) return
            FileLogger.log(TAG, "WS failure: ${t.message}")
            onError("Soniox: ${t.message ?: "connection failed"}")
        }
    }

    /** (Re)arms the silence timer for [durationMs] from now, cancelling any pending fire. */
    private fun armSilence(webSocket: WebSocket, durationMs: Long) {
        silenceTask?.cancel(false)
        silenceTask = runCatching {
            timer.schedule({ fireFinalOnce(webSocket) }, durationMs, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun cancelSilence() {
        silenceTask?.cancel(false)
        silenceTask = null
        runCatching { timer.shutdownNow() }
    }

    /** Terminal path: emit the settled transcript exactly once and shut everything down. */
    private fun fireFinalOnce(webSocket: WebSocket) {
        if (finalFired) return
        finalFired = true
        stopped = true
        cancelSilence()
        closeLine()
        val settled = synchronized(this) { finalText.toString() }.trim()
        FileLogger.log(TAG, "final (${settled.length} chars, $bytesSent sent bytes)")
        onFinal(settled)
        shutdownTransport()
    }

    /**
     * Closing the line is also how the capture loop is unblocked: a
     * TargetDataLine.read in progress is not interruptible, so an interrupt
     * alone would leave the thread parked until the next frame arrives.
     */
    private fun closeLine() {
        val l = line
        line = null
        runCatching { l?.stop() }
        runCatching { l?.close() }
        captureThread = null
    }

    private fun startCapture(webSocket: WebSocket) {
        val thread = Thread {
            val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            if (!AudioSystem.isLineSupported(info)) {
                onError("No microphone available for 16 kHz mono capture.")
                return@Thread
            }
            val mic = runCatching {
                (AudioSystem.getLine(info) as TargetDataLine).also {
                    it.open(format, FRAME_BYTES * 8)
                    it.start()
                }
            }.getOrElse {
                onError("Could not open the microphone: ${it.message ?: "unknown error"}")
                return@Thread
            }
            line = mic
            val buf = ByteArray(FRAME_BYTES)
            try {
                while (!stopped) {
                    val n = mic.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val mulaw = pcm16leToMulaw(buf, n)
                    if (!webSocket.send(mulaw.toByteString())) break
                    bytesSent += mulaw.size
                }
            } catch (_: Throwable) {
                // Closed from stop() mid-read — fall through to cleanup.
            } finally {
                runCatching { mic.stop() }
                runCatching { mic.close() }
            }
        }
        thread.isDaemon = true
        captureThread = thread
        thread.start()
    }

    companion object {
        private const val TAG = "DesktopStt"
        private const val WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_BYTES = 3200 // 100 ms @ 16 kHz mono 16-bit
        private const val NO_SPEECH_GUARD_MS = 12000L
        /** How long the server gets to answer `finished` before we close anyway. */
        private const val FINISH_GRACE_MS = 5000L
    }
}

/**
 * The session config Soniox expects as the first WS frame. μ-law is
 * headerless, so unlike a self-describing container the rate and channel count
 * have to be stated; the Czech+English hints are what let a sentence mix Czech
 * with English identifiers in one pass.
 */
internal fun sonioxDictationConfig(apiKey: String): String = JSONObject().apply {
    put("api_key", apiKey)
    put("model", "stt-rt-v5")
    put("audio_format", "mulaw")
    put("sample_rate", 16000)
    put("num_channels", 1)
    put("language_hints", JSONArray(listOf("cs", "en")))
    put("language_hints_strict", true)
    // Endpoint detection stays on because it is what finalizes tokens as the
    // speaker pauses, but tuned to wait: the client-side silence timer, not
    // the server's <end>, is what ends dictation.
    put("enable_endpoint_detection", true)
    put("endpoint_sensitivity", -0.3)
    put("max_endpoint_delay_ms", 3000)
}.toString()

/**
 * Converts `len` bytes of 16-bit little-endian PCM into G.711 μ-law, one byte
 * per sample.
 */
internal fun pcm16leToMulaw(pcm: ByteArray, len: Int): ByteArray {
    val out = ByteArray(len / 2)
    var j = 0
    var i = 0
    while (i + 1 < len) {
        val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
        out[j++] = linearToMulaw(sample.toShort().toInt())
        i += 2
    }
    return out
}

/**
 * Standard G.711 μ-law compression of one 16-bit linear sample. The exponent
 * is the classic 256-entry compress table, computed here as the high-bit
 * position of the top 8 bits (identical to the table). Reference vectors:
 * 0 → 0xFF, +full → 0x80, −full → 0x00.
 */
internal fun linearToMulaw(pcmVal: Int): Byte {
    val bias = 0x84
    val clip = 32635
    var sample = pcmVal
    val sign = (sample shr 8) and 0x80
    if (sign != 0) sample = -sample
    if (sample > clip) sample = clip
    sample += bias
    val idx = (sample shr 7) and 0xFF
    var exponent = 0
    var v = idx
    while (v > 1) { v = v shr 1; exponent++ }
    val mantissa = (sample shr (exponent + 3)) and 0x0F
    return ((sign or (exponent shl 4) or mantissa).inv() and 0xFF).toByte()
}
