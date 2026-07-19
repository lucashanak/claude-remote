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
 * Endpoint detection (`enable_endpoint_detection`) makes Soniox emit an
 * `<end>` control token when the speaker pauses; we keep it on for the
 * running token finalization it drives. In single-shot mode an `<end>` no
 * longer ends the dictation — a client-side 5 s silence timer does, rearmed
 * on every new word — so a short mid-sentence pause doesn't cut the speaker
 * off (Soniox's own endpoint delay is capped at 3 s server-side). In
 * [continuous] mode an `<end>` just flushes the current utterance and keeps
 * listening (no silence timer there).
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

    // Single-shot silence timer. `enable_endpoint_detection` fires an `<end>`
    // the moment the speaker pauses, which cuts a short mid-sentence breath
    // off; instead we wait SILENCE_TIMEOUT_MS after the *last real word* and
    // only then finalize. Rearmed on every non-control token (see onMessage),
    // cancelled on manual stop / server `finished` / fire. Lives client-side
    // because Soniox's own endpoint delay is capped at 3 s server-side.
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var silenceStop: Runnable? = null

    // Guarded by `this` — mutated from the WS listener thread, read when
    // building each partial/final string.
    private val finalText = StringBuilder()

    // Total μ-law bytes actually pushed to the socket, logged with the final
    // (parity with the watch): confirms the uplink stayed at half of raw PCM.
    @Volatile private var bytesSent = 0L

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
        cancelSilenceTimer()
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
                // Keep endpoint detection on (drives running token
                // finalization), but make it *less* trigger-happy: negative
                // sensitivity waits longer, and we lift the server delay to
                // its 3 s ceiling. The full ~5 s pause tolerance is enforced
                // client-side (silenceStop) since the server maxes at 3 s.
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
                        put(JSONObject().put("key", "language").put("value", "Czech and English mixed"))
                        put(JSONObject().put("key", "topic").put("value", "Software development, terminal work with the Claude Code CLI"))
                        put(JSONObject().put("key", "instructions").put("value", "Speaker mixes Czech sentences with English technical and code terms and identifiers."))
                    })
                    put("terms", JSONArray(listOf(
                        "Claude", "Claude Code", "commit", "git", "ralph", "session", "tmux", "pull request",
                        "merge", "rebase", "branch", "checkout", "push", "deploy", "build", "gradle", "diff", "PR", "refactor",
                    )))
                })
            }
            webSocket.send(config.toString())
            startCapture(webSocket)
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
                    // A real word arrived — the speaker isn't silent, so the
                    // single-shot silence timer (below) gets rearmed.
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
                // Voice mode is unchanged: an endpoint flushes the utterance
                // and we keep listening. No client silence timer here.
                if (endpoint) flushUtterance()
            } else {
                // Single-shot: an endpoint no longer ends the dictation — the
                // 5 s silence timer does. Rearm it on every real word so a
                // short pause doesn't finalize early; the timer fires only
                // after 5 s with no new word.
                if (sawRealToken) resetSilenceTimer(webSocket)
            }
            if (obj.optBoolean("finished")) fireFinalOnce(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stopped) return
            FileLogger.log(TAG, "WS failure: ${t.message}")
            postOnMain { onError("Soniox: ${t.message ?: "chyba spojení"}") }
        }
    }

    /** Single-shot terminal: fire onFinal once, stop capture, close socket. */
    private fun fireFinalOnce(webSocket: WebSocket) {
        if (finalFired) return
        finalFired = true
        stopped = true
        cancelSilenceTimer()
        captureThread?.interrupt()
        captureThread = null
        val settled = synchronized(this) { finalText.toString() }.trim()
        FileLogger.log(TAG, "final (${settled.length} chars, $bytesSent sent bytes)")
        postOnMain { onFinal(settled) }
        runCatching { webSocket.close(1000, null) }
    }

    /**
     * Single-shot: (re)arm the 5 s silence timer, counted from the last real
     * word. Each new word cancels the pending fire and schedules a fresh one,
     * so the dictation only ends after 5 s of actual silence.
     */
    private fun resetSilenceTimer(webSocket: WebSocket) {
        silenceStop?.let { main.removeCallbacks(it) }
        val runnable = Runnable { fireFinalOnce(webSocket) }
        silenceStop = runnable
        main.postDelayed(runnable, SILENCE_TIMEOUT_MS)
    }

    /** Cancel any pending silence fire (manual stop, finished, or after fire). */
    private fun cancelSilenceTimer() {
        silenceStop?.let { main.removeCallbacks(it) }
        silenceStop = null
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
        // Client-side silence tolerance (single-shot only): end the dictation
        // 5 s after the last word. Larger than the server's 3 s hard cap on
        // max_endpoint_delay_ms, which is why the wait lives here.
        private const val SILENCE_TIMEOUT_MS = 5000L
    }
}
