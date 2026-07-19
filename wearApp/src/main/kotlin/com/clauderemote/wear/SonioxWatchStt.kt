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
 * on `shared`). Captures 16-kHz PCM from the mic, converts each frame inline to
 * G.711 μ-law (`mulaw`) and streams that. μ-law is a raw 1-byte-per-sample
 * codec: no MediaCodec, no encoder thread — the conversion is a fast table
 * lookup on the capture thread. It halves the uplink vs raw PCM (128 kbps at
 * 16 kHz) while Soniox decodes it in realtime (~0.8 s to first word, like PCM),
 * and unlike telephony 8 kHz μ-law we keep the full 16 kHz bandwidth. (An
 * earlier AMR-WB path compressed ~10x harder but added ~1.3 s of first-word
 * latency — Soniox has to decode it — so it was dropped for lower latency.)
 * Gets word-by-word tokens back;
 * [onPartial] fires with the growing transcript, [onFinal] with the settled
 * text. It ends on [stop], on the server's `finished`, or on Soniox's `<end>`
 * endpoint. Sensitivity is tuned down server-side (`endpoint_sensitivity`
 * −0.3, `max_endpoint_delay_ms` 3000 ≈ 3 s) so a short mid-sentence pause
 * doesn't cut the speaker off. Requires a Soniox key synced from the phone
 * ([SonioxKeyStore]) and RECORD_AUDIO.
 *
 * Unlike the phone port, mic capture starts the instant [start] is called (NOT
 * in onOpen): on a watch with no Wi-Fi the handshake can take seconds — so
 * whatever the user says before onOpen would be lost ("cuts off the start").
 * μ-law frames produced before the socket is ready are buffered and flushed
 * the moment it opens.
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
    // Set once the socket is open + configured — the send path reads this to
    // decide whether to stream live or keep buffering into [backlog].
    @Volatile private var liveSocket: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var captureThread: Thread? = null
    @Volatile private var finalFired = false
    private val finalText = StringBuilder()

    // Single-shot silence timer — ends dictation, NOT Soniox's <end>. Armed
    // once at onOpen with NO_SPEECH_GUARD_MS (so a token-less session can't
    // hang), re-armed to DICTATION_SILENCE_MS on every real word.
    @Volatile private var silenceStop: Runnable? = null

    // Outbound μ-law frames produced before the socket opened, flushed on
    // onOpen. Guarded by itself. Capped so a socket that never opens can't
    // grow it unbounded.
    private val backlog = ArrayDeque<ByteString>()

    // Diagnostics logged with the final, so a bad transcript is explainable
    // without a device in hand:
    //  - connect latency: how long onOpen took (≈ how much lead the pre-buffer
    //    had to cover; large = the old code would have clipped that much).
    //  - sent bytes: total μ-law bytes pushed to the socket (128 kbit/s @ 16
    //    kHz, half of raw PCM); ≈0 means the mic captured nothing
    //    (permission/screen-off), lots + 0 chars means audio flowed but Soniox
    //    heard silence/garbage.
    //  - peak WS queue: bytes stuck in OkHttp's send buffer — grows when the
    //    uplink (BT tunnel) can't keep up; μ-law is 128 kbit/s (vs ~24 for the
    //    old AMR-WB), so watch this to see if the BT tunnel holds up.
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
        // Capture BEFORE the socket handshake — see class kdoc. μ-law frames
        // buffer into [backlog] until onOpen flips [liveSocket] on.
        startCapture()
        ws = http.newWebSocket(Request.Builder().url(WS_URL).build(), Listener(apiKey))
    }

    fun stop() {
        if (stopped) return
        stopped = true
        cancelSilence()
        // μ-law goes out inline on the capture loop, so the last chunk has
        // already been sent by the time this runs — no encoder tail to drain.
        // Just stop the loop and send the end-of-audio marker.
        captureThread?.interrupt()
        captureThread = null
        // Empty string = end-of-audio; server finalizes remaining tokens and
        // replies with finished:true, which fires onFinal + closes it.
        runCatching { ws?.send("") }
    }

    private inner class Listener(private val apiKey: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connectMs = (System.nanoTime() - startNanos) / 1_000_000
            WearLog.i(context, TAG, "WS open (connect $connectMs ms)")
            val config = JSONObject().apply {
                put("api_key", apiKey)
                put("model", MODEL)
                // "mulaw" is a raw (headerless) codec, so unlike a self-
                // describing container we must state the sample rate + channel
                // count explicitly — Soniox can't infer them from the stream.
                put("audio_format", "mulaw")
                put("sample_rate", SAMPLE_RATE)
                put("num_channels", 1)
                put("language_hints", JSONArray(listOf("cs", "en")))
                // Restrict output to ONLY Czech + English — suppresses accidental
                // transcription in neighbouring Slavic languages (sk/pl/ru), which
                // is a likely cause of the "Czech is inaccurate" reports. English
                // stays allowed, so context.terms English terms are unaffected.
                put("language_hints_strict", true)
                // Keep endpoint detection on (it ends the dictation), but
                // make it *less* trigger-happy: negative sensitivity waits
                // longer, and we lift the server delay to its 3 s ceiling so
                // a short mid-sentence pause doesn't cut the speaker off.
                // Docs recommend -0.3 for dictation; do NOT also send
                // endpoint_latency_adjustment_level (it fights negative
                // sensitivity).
                put("enable_endpoint_detection", true)
                put("endpoint_sensitivity", -0.3)
                put("max_endpoint_delay_ms", 3000)
                // Vocabulary biasing: `general` describes the speaking style,
                // `terms` is a flat list of literals we expect. Must be a
                // nested JSONObject/JSONArray (a flat string 400s).
                put("context", JSONObject().apply {
                    put("general", JSONArray().apply {
                        put(JSONObject().put("key", "language").put("value", "Czech (primary)"))
                        put(JSONObject().put("key", "topic").put("value", "Software development, terminal work with the Claude Code CLI"))
                        put(JSONObject().put("key", "instructions").put("value", "Speaker is a native Czech speaker; conversation is primarily in Czech, with occasional English software and technical terms embedded mid-sentence."))
                    })
                    put("terms", JSONArray(listOf(
                        "Claude", "Claude Code", "commit", "git", "ralph", "session", "tmux", "pull request",
                        "merge", "rebase", "branch", "checkout", "push", "deploy", "build", "gradle", "diff", "PR", "refactor",
                    )))
                })
            }
            webSocket.send(config.toString())
            // Send path now drains [backlog] then streams live.
            liveSocket = webSocket
            // Arm the silence timer with the long no-speech guard the moment
            // the socket is ready — re-armed short on each real word below.
            // This guarantees the dictation always ends even if no token comes.
            main.post { armSilence(webSocket, NO_SPEECH_GUARD_MS) }
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
            var sawRealToken = false
            if (tokens != null) {
                for (i in 0 until tokens.length()) {
                    val tok = tokens.optJSONObject(i) ?: continue
                    val t = tok.optString("text")
                    // Control tokens ("<end>", "<fin>") aren't transcript text.
                    if (t.startsWith("<") && t.endsWith(">")) {
                        continue
                    }
                    sawRealToken = true
                    if (tok.optBoolean("is_final")) {
                        synchronized(this@SonioxWatchStt) { finalText.append(t) }
                    } else {
                        provisional.append(t)
                    }
                }
            }
            val running = synchronized(this@SonioxWatchStt) { finalText.toString() } + provisional
            if (running.isNotBlank()) main.post { onPartial?.invoke(running.trim()) }
            // <end> no longer finalizes — the silence timer does. Re-arm it
            // (short) on every real word; the up-front arm in onOpen guarantees
            // termination even with zero tokens.
            if (sawRealToken) main.post { armSilence(webSocket, DICTATION_SILENCE_MS) }
            // Server-side end-of-stream still finalizes immediately.
            if (obj.optBoolean("finished")) fireFinalOnce(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stopped) return
            WearLog.w(context, TAG, "WS failure: ${t.message}")
            main.post { onError("Soniox: ${t.message ?: "chyba spojení"}") }
        }
    }

    /** (Re)arm the silence timer for [durationMs] from now (main thread). */
    private fun armSilence(webSocket: WebSocket, durationMs: Long) {
        silenceStop?.let { main.removeCallbacks(it) }
        val r = Runnable { fireFinalOnce(webSocket) }
        silenceStop = r
        main.postDelayed(r, durationMs)
    }

    private fun cancelSilence() {
        silenceStop?.let { main.removeCallbacks(it) }
        silenceStop = null
    }

    private fun fireFinalOnce(webSocket: WebSocket) {
        if (finalFired) return
        finalFired = true
        stopped = true
        cancelSilence()
        captureThread?.interrupt()
        captureThread = null
        val settled = synchronized(this) { finalText.toString() }.trim()
        WearLog.i(
            context, TAG,
            "final (${settled.length} chars, $bytesSent sent bytes, connect $connectMs ms, peak WS queue $peakQueueBytes B)",
        )
        main.post { onFinal(settled) }
        runCatching { webSocket.close(1000, null) }
    }

    /**
     * Route one outbound μ-law frame to the socket, or buffer it until [onOpen]
     * flips [liveSocket] on — the pre-buffer-before-open mechanism that stops
     * the start of speech being clipped. [bytesSent]/[peakQueueBytes] track the
     * μ-law bytes actually pushed.
     */
    private fun sendFrame(frame: ByteString) {
        val socket = liveSocket
        if (socket != null) {
            drainBacklog(socket)
            if (!socket.send(frame)) return
            bytesSent += frame.size
            val q = socket.queueSize()
            if (q > peakQueueBytes) peakQueueBytes = q
        } else {
            // Socket not open yet — buffer, but bound the backlog so a
            // never-opening socket can't grow it without limit. Eviction drops
            // the oldest audio.
            synchronized(backlog) {
                backlog.addLast(frame)
                while (backlog.size > MAX_BACKLOG_FRAMES) {
                    backlog.removeFirst()
                }
            }
        }
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
                    // s16le PCM → μ-law inline (n bytes in → n/2 bytes out),
                    // then through the live/backlog path. No codec, no thread.
                    val mulaw = pcm16leToMulaw(buf, n)
                    sendFrame(mulaw.toByteString())
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

    /**
     * Convert `len` bytes of 16-bit little-endian PCM (AudioRecord's native
     * ENCODING_PCM_16BIT on ARM) into G.711 μ-law, one byte per sample.
     */
    private fun pcm16leToMulaw(pcm: ByteArray, len: Int): ByteArray {
        val out = ByteArray(len / 2)
        var j = 0
        var i = 0
        while (i + 1 < len) {
            // s16 LE: low byte first, high byte sign-extended into the sample.
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            out[j++] = linearToMulaw(sample.toShort().toInt())
            i += 2
        }
        return out
    }

    /**
     * Standard G.711 μ-law compression of one 16-bit linear sample. The
     * exponent is the classic 256-entry compress-table lookup, computed here
     * as the high-bit position of the top 8 bits of `sample` (identical to the
     * table). Verified against the reference vectors: 0→0xFF, +full→0x80,
     * −full→0x00.
     */
    private fun linearToMulaw(pcmVal: Int): Byte {
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

    companion object {
        private const val TAG = "SonioxWatchStt"
        private const val WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket"
        private const val MODEL = "stt-rt-v5"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_BYTES = 3200 // 100 ms @ 16 kHz mono 16-bit
        // Silence timer: pause-between-words tolerance (re-armed per word) and
        // the long up-front guard that prevents a token-less hang.
        private const val DICTATION_SILENCE_MS = 4000L
        private const val NO_SPEECH_GUARD_MS = 12000L
        // ~10 s of μ-law frames — a generous ceiling on pre-open buffering.
        private const val MAX_BACKLOG_FRAMES = 150
    }
}
