package com.clauderemote.voice

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
import com.clauderemote.util.FileLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Real-time streaming STT via Soniox's WebSocket API
 * (`wss://stt-rt.soniox.com/transcribe-websocket`). Unlike [ServerDictation]
 * (batch: energy-VAD → record utterance → POST a WAV, no live partials),
 * this captures 16-kHz PCM from the mic, converts each frame inline to G.711
 * μ-law (`mulaw`) and streams that. μ-law is a raw 1-byte-per-sample codec:
 * no MediaCodec, no encoder thread — the conversion is a fast table lookup on
 * the capture thread. It halves the uplink vs raw PCM (128 kbps at 16 kHz)
 * while Soniox still decodes it in realtime (~0.9 s to first word, like PCM),
 * and unlike telephony 8 kHz μ-law we keep the full 16 kHz bandwidth. (An
 * earlier AAC-LC/ADTS path compressed harder but added ~3.6 s of first-word
 * latency, so it was dropped.)
 *
 * Each server message carries a `tokens` array; every token has `text` and
 * `is_final`. Final tokens are appended to [finalText] and never change;
 * non-final tokens are the provisional tail that keeps getting rewritten.
 * [onPartial] fires with `finalText + provisional tail` on every message
 * (that's the live-growing text), and [onFinal] fires with the settled
 * transcript when the utterance ends.
 *
 * Endpoint detection (`enable_endpoint_detection`) stays ON — Soniox needs it
 * to finalize tokens as the speaker pauses — but its `<end>` no longer ends
 * the single-shot dictation. Instead a client-side silence timer on the main
 * Handler is authoritative: [armTimer] is called ONCE at capture start with a
 * generous [NO_SPEECH_GUARD_MS] guard (so a session with zero tokens — noise,
 * a silent mic, a false start — still terminates and never hangs), then
 * re-armed to [silenceMs] on every real token, so dictation ends [silenceMs]
 * after the last word. Sensitivity is tuned down server-side
 * (`endpoint_sensitivity` −0.3, `max_endpoint_delay_ms` 3000 ≈ 3 s) so a short
 * mid-sentence pause doesn't cut the speaker off before our timer would. In
 * [continuous] mode there is NO timer: an `<end>` just flushes the current
 * utterance and we keep listening.
 *
 * Tokens include spaces and punctuation as their own tokens, so raw
 * concatenation of `text` reconstructs spacing — we never insert spaces
 * ourselves. `language_hints: ["cs","en"]` is what makes mixed Czech +
 * English (dictating code with English identifiers) transcribe in one pass.
 */
internal class SonioxDictation(
    private val context: Context,
    private val apiKey: String,
    private val continuous: Boolean,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListening: (() -> Unit)? = null,
    private val onPartial: ((String) -> Unit)? = null,
    // Silence tolerance (ms) that ends single-shot dictation — user-set in
    // Voice settings. Default 4 s; ignored in continuous (voice) mode.
    private val silenceMs: Int = 4000,
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
    // Single-shot: guarantees onFinal fires exactly once (endpoint OR manual
    // stop OR server `finished`, whichever lands first) so a trailing message
    // can't re-inject text after the caller has moved on.
    @Volatile private var finalFired = false

    // Guarded by `this` — mutated from the WS listener thread, read when
    // building each partial/final string.
    private val finalText = StringBuilder()

    // Total μ-law bytes actually pushed to the socket, logged with the final
    // (parity with the watch): confirms the uplink stayed at half of raw PCM.
    @Volatile private var bytesSent = 0L

    // Single-shot silence timer (main thread). This is what ends dictation —
    // NOT Soniox's own <end> endpoint. Armed ONCE at capture start with a
    // generous NO_SPEECH_GUARD_MS (so a session that never produces a token —
    // noise, silence, a mic that heard nothing — always terminates instead of
    // hanging, which is exactly how the previous timer broke), then re-armed to
    // the user-set silenceMs on EVERY real word. So the countdown restarts from
    // the last word and a short mid-sentence pause doesn't cut you off. Never
    // depends on a token arriving to schedule the first fire.
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var silenceStop: Runnable? = null

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

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            FileLogger.log(TAG, "WS open (continuous=$continuous)")
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
                // Keep endpoint detection on (it ends the single-shot
                // dictation), but make it *less* trigger-happy: negative
                // sensitivity waits longer, and we lift the server delay to
                // its 3 s ceiling so a short mid-sentence pause doesn't cut
                // the speaker off.
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
            startCapture(webSocket)
            // Arm the silence timer immediately (single-shot only) with the
            // long no-speech guard. Re-armed short on each real word below. This
            // up-front arm is what guarantees no hang even if zero tokens ever
            // arrive. The guard must never be shorter than the (user-set)
            // tolerance — otherwise a 10 s tolerance would be cut by the guard.
            if (!continuous) postOnMain {
                armSilence(webSocket, maxOf(NO_SPEECH_GUARD_MS, silenceMs.toLong() + 2000L))
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
            val err = obj.optString("error_code").takeIf { it.isNotBlank() && it != "null" }
            if (err != null) {
                val msg = obj.optString("error_message").ifBlank { err }
                FileLogger.log(TAG, "server error: $err — $msg")
                postOnMain { onError("Soniox: $msg") }
                return
            }
            val tokens = obj.optJSONArray("tokens")
            val provisional = StringBuilder()
            var endpoint = false
            var sawRealToken = false
            if (tokens != null) {
                for (i in 0 until tokens.length()) {
                    val tok = tokens.optJSONObject(i) ?: continue
                    val t = tok.optString("text")
                    // Control tokens ("<end>", "<fin>") aren't transcript text.
                    if (t.startsWith("<") && t.endsWith(">")) {
                        if (tok.optBoolean("is_final")) endpoint = true
                        continue
                    }
                    sawRealToken = true
                    if (tok.optBoolean("is_final")) {
                        synchronized(this@SonioxDictation) { finalText.append(t) }
                    } else {
                        provisional.append(t)
                    }
                }
            }
            val running = synchronized(this@SonioxDictation) { finalText.toString() } + provisional
            if (running.isNotBlank()) postOnMain { onPartial?.invoke(running.trim()) }

            if (continuous) {
                // Voice mode: an endpoint flushes the utterance and we keep
                // listening. No silence timer here.
                if (endpoint) flushUtterance()
            } else {
                // Single-shot: <end> no longer ends dictation — the silence
                // timer does. Re-arm it (short) on every real word so a pause
                // between words doesn't finalize early. The up-front arm in
                // onOpen already guarantees termination if no word ever comes.
                if (sawRealToken) postOnMain { armSilence(webSocket, silenceMs.toLong()) }
            }
            if (obj.optBoolean("finished")) fireFinalOnce(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stopped) return
            FileLogger.log(TAG, "WS failure: ${t.message}")
            postOnMain { onError("Soniox: ${t.message ?: "chyba spojení"}") }
        }
    }

    /**
     * (Re)arm the single-shot silence timer for [durationMs] from now. Cancels
     * any pending fire first, so each call restarts the countdown. Main thread.
     */
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

    /** Single-shot terminal: fire onFinal once, stop capture, close socket. */
    private fun fireFinalOnce(webSocket: WebSocket) {
        if (finalFired) return
        finalFired = true
        stopped = true
        cancelSilence()
        captureThread?.interrupt()
        captureThread = null
        val settled = synchronized(this) { finalText.toString() }.trim()
        FileLogger.log(TAG, "final (${settled.length} chars, $bytesSent sent bytes)")
        postOnMain { onFinal(settled) }
        runCatching { webSocket.close(1000, null) }
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
                    // s16le PCM → μ-law inline (n bytes in → n/2 bytes out),
                    // then straight to the socket. No codec, no extra thread.
                    val mulaw = pcm16leToMulaw(buf, n)
                    if (!webSocket.send(mulaw.toByteString())) break
                    bytesSent += mulaw.size
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
        private const val TAG = "SonioxStt"
        private const val WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket"
        private const val MODEL = "stt-rt-v5"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_BYTES = 3200 // 100 ms @ 16 kHz mono 16-bit
        // Silence timer: the pause-between-words tolerance is now the user-set
        // `silenceMs` (re-armed on each word); this is only the long up-front
        // guard so a token-less session still ends.
        private const val NO_SPEECH_GUARD_MS = 12000L
    }
}
