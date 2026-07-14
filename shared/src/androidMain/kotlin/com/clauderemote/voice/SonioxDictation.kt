package com.clauderemote.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.clauderemote.util.FileLogger
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real-time streaming STT via Soniox's WebSocket API
 * (`wss://stt-rt.soniox.com/transcribe-websocket`). Unlike [ServerDictation]
 * (batch: energy-VAD → record utterance → POST a WAV, no live partials),
 * this captures 16-kHz PCM from the mic, encodes it on-device to AAC-LC
 * (ADTS framing) and streams that — the compressed uplink is ~8-10x smaller
 * than raw PCM, which cuts mobile-data use. Gets word-by-word tokens back
 * with sub-200ms latency, so the transcript visibly grows as the user speaks.
 *
 * Each server message carries a `tokens` array; every token has `text` and
 * `is_final`. Final tokens are appended to [finalText] and never change;
 * non-final tokens are the provisional tail that keeps getting rewritten.
 * [onPartial] fires with `finalText + provisional tail` on every message
 * (that's the live-growing text), and [onFinal] fires with the settled
 * transcript when the utterance ends.
 *
 * Endpoint detection (`enable_endpoint_detection`) makes Soniox emit an
 * `<end>` control token when the speaker pauses. In single-shot mode that
 * ends the dictation (fires [onFinal], closes) so it stops on its own like
 * the other engines; in [continuous] mode it just flushes the current
 * utterance and keeps listening.
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
    // PCM→AAC-LC encoder; null means it couldn't be created and we fall back
    // to streaming raw PCM so dictation still works (see [startEncoder]).
    @Volatile private var aacEncoder: AacEncoder? = null

    // Guarded by `this` — mutated from the WS listener thread, read when
    // building each partial/final string.
    private val finalText = StringBuilder()

    // Total bytes actually pushed to the socket, logged with the final (parity
    // with the watch): a small value confirms the compressed AAC/ADTS path,
    // a large one means the raw-PCM fallback kicked in.
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
        // With an encoder, DON'T interrupt or send end-of-audio here: the
        // capture loop exits on `stopped`, and its finally block signals the
        // encoder's end-of-stream so the AAC tail drains and the encoder's
        // onEnd sends the Soniox end-of-audio marker only AFTER the last frames
        // have gone out — otherwise the end of speech gets clipped. In the PCM
        // fallback there's no tail, so send it now (the old behaviour).
        if (aacEncoder == null) {
            captureThread?.interrupt()
            captureThread = null
            // Empty string = end-of-audio; server finalizes remaining tokens
            // and replies with finished:true, which fires onFinal + closes it.
            runCatching { ws?.send("") }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            FileLogger.log(TAG, "WS open (continuous=$continuous)")
            val config = JSONObject().apply {
                put("api_key", apiKey)
                put("model", MODEL)
                // "auto": Soniox reads container/codec (and, for ADTS, sample
                // rate + channels) from the stream headers, so we send neither
                // sample_rate nor num_channels — the ADTS header carries them.
                put("audio_format", "auto")
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
            if (tokens != null) {
                for (i in 0 until tokens.length()) {
                    val tok = tokens.optJSONObject(i) ?: continue
                    val t = tok.optString("text")
                    // Control tokens ("<end>", "<fin>") aren't transcript text.
                    if (t.startsWith("<") && t.endsWith(">")) {
                        if (tok.optBoolean("is_final")) endpoint = true
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

            if (endpoint) {
                if (continuous) {
                    flushUtterance()
                } else {
                    fireFinalOnce(webSocket)
                }
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
        captureThread?.interrupt()
        captureThread = null
        // Server-side endpoint/finished: it already has all the audio, so just
        // tear the encoder down (no tail to drain, unlike the user-stop path).
        aacEncoder?.release()
        aacEncoder = null
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

    /**
     * Create + start the AAC-LC encoder whose ADTS frames get streamed to
     * [webSocket]. Returns null (→ raw-PCM fallback in [startCapture]) if the
     * device can't give us an AAC encoder — unlikely, but we'd rather degrade
     * to the old uncompressed path than kill dictation outright.
     */
    private fun startEncoder(webSocket: WebSocket): AacEncoder? = runCatching {
        AacEncoder(
            // Don't gate on `stopped`: the drain after stop() emits the tail
            // frames while stopped==true, and they must still reach the socket
            // before onEnd sends end-of-audio.
            onFrame = { frame ->
                if (runCatching { webSocket.send(frame) }.getOrDefault(false)) bytesSent += frame.size
            },
            // End-of-stream reached: the tail frames have already been emitted
            // above, so it's safe to tell Soniox the audio is finished.
            onEnd = { runCatching { ws?.send("") } },
        ).also { it.start() }
    }.getOrElse {
        FileLogger.warn(TAG, "AAC encoder nedostupný, fallback na PCM: ${it.message}")
        null
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(webSocket: WebSocket) {
        // One encoder for the whole recording — in continuous mode it must
        // outlive individual utterances: an endpoint only flushes text, it
        // doesn't end the stream, so the encoder keeps running until stop().
        aacEncoder = startEncoder(webSocket)
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
                    val enc = aacEncoder
                    if (enc != null) {
                        // Copy the valid slice out of the reused buffer; the
                        // encoder's output thread produces ADTS → webSocket.
                        enc.encode(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
                    } else {
                        // Fallback: stream raw PCM exactly as before.
                        val chunk: ByteString =
                            if (n == buf.size) buf.toByteString() else buf.toByteString(0, n)
                        if (!webSocket.send(chunk)) break
                        bytesSent += chunk.size
                    }
                }
            } catch (_: Throwable) {
                // fall through to cleanup
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                // User-stop path (not a server endpoint): flush the encoder tail
                // from THIS thread — the sole codec-input thread, so no
                // concurrent MediaCodec input access. signalEnd() drains the
                // last audio and its onEnd sends the Soniox end-of-audio marker.
                if (!finalFired) aacEncoder?.signalEnd()
            }
        }
        thread.isDaemon = true
        captureThread = thread
        thread.start()
    }

    /**
     * PCM → AAC-LC encoder. Input is fed on the capture thread via [encode];
     * a dedicated daemon thread pulls encoded frames, prepends the 7-byte ADTS
     * header MediaCodec omits, and hands each whole ADTS frame to [onFrame].
     * All MediaCodec calls are runCatching-guarded — teardown races between the
     * input and output threads surface as IllegalStateException, not crashes.
     */
    private inner class AacEncoder(
        private val onFrame: (ByteString) -> Unit,
        private val onEnd: () -> Unit,
    ) {
        private val codec = MediaCodec.createEncoderByType(MIME_AAC)
        // Stop accepting input once end-of-stream is signalled or we're torn
        // down, so a late [encode] from the capture thread is a no-op.
        @Volatile private var accepting = true
        // Requests the output thread to exit + tear the codec down. The codec is
        // only ever stopped/released ON the output thread (in [cleanup]) to
        // avoid freeing it underneath a blocked dequeue call.
        @Volatile private var stopRequested = false
        private val cleanedUp = AtomicBoolean(false)
        // PCM bytes fed so far → presentation timestamps (µs). 16-bit mono.
        private var fedBytes = 0L
        private val outputThread = Thread { drainLoop() }.apply { isDaemon = true }

        fun start() {
            val fmt = MediaFormat.createAudioFormat(MIME_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES * 2)
            }
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            outputThread.start()
        }

        /** Feed one PCM chunk; batches across input buffers if it doesn't fit. */
        fun encode(pcm: ByteArray) {
            var offset = 0
            while (offset < pcm.size && accepting) {
                val idx = runCatching { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrElse { -1 }
                if (idx < 0) continue
                val ib = runCatching { codec.getInputBuffer(idx) }.getOrNull()
                if (ib == null) {
                    runCatching { codec.queueInputBuffer(idx, 0, 0, ptsUs(), 0) }
                    continue
                }
                ib.clear()
                val n = minOf(ib.remaining(), pcm.size - offset)
                ib.put(pcm, offset, n)
                val pts = ptsUs()
                fedBytes += n
                runCatching { codec.queueInputBuffer(idx, 0, n, pts, 0) }
                offset += n
            }
        }

        /**
         * Queue an empty end-of-stream input buffer so the encoder flushes its
         * tail; the output loop then emits the last frames and fires [onEnd].
         * Runs on the capture thread (its finally block), i.e. the same thread
         * as [encode].
         */
        fun signalEnd() {
            if (!accepting) return
            accepting = false
            var tries = 0
            while (tries < EOS_QUEUE_TRIES) {
                val idx = runCatching { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrElse { -1 }
                if (idx >= 0) {
                    runCatching {
                        codec.queueInputBuffer(
                            idx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                    }
                    return
                }
                tries++
            }
        }

        /** Ask the output thread to stop + release the codec (no tail drain). */
        fun release() {
            accepting = false
            stopRequested = true
        }

        private fun drainLoop() {
            val info = MediaCodec.BufferInfo()
            while (!stopRequested) {
                // try/catch (not runCatching{}.getOrElse{break}) — `break` from
                // an inline lambda needs Kotlin 2.2; this project is on 2.1.
                val idx = try {
                    codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
                } catch (_: Throwable) {
                    break
                }
                if (idx >= 0) {
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    // Skip the AAC CSD/config buffer: ADTS carries codec params
                    // in every frame header, so we never send it as audio.
                    if (!isConfig && info.size > 0) {
                        val ob = runCatching { codec.getOutputBuffer(idx) }.getOrNull()
                        if (ob != null) {
                            val frame = ByteArray(info.size + ADTS_HEADER_LEN)
                            System.arraycopy(adtsHeader(info.size), 0, frame, 0, ADTS_HEADER_LEN)
                            ob.position(info.offset)
                            ob.get(frame, ADTS_HEADER_LEN, info.size)
                            onFrame(frame.toByteString())
                        }
                    }
                    runCatching { codec.releaseOutputBuffer(idx, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        onEnd()
                        break
                    }
                }
                // INFO_OUTPUT_FORMAT_CHANGED / INFO_TRY_AGAIN_LATER: ADTS is
                // self-describing, so no CSD to stash — just loop.
            }
            cleanup()
        }

        private fun cleanup() {
            if (!cleanedUp.compareAndSet(false, true)) return
            accepting = false
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        private fun ptsUs(): Long = fedBytes * 1_000_000L / (SAMPLE_RATE.toLong() * 2L)
    }

    companion object {
        private const val TAG = "SonioxStt"
        private const val WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket"
        private const val MODEL = "stt-rt-v5"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_BYTES = 3200 // 100 ms @ 16 kHz mono 16-bit

        private const val MIME_AAC = "audio/mp4a-latm"
        private const val AAC_BIT_RATE = 24000
        private const val ADTS_HEADER_LEN = 7
        // ADTS sampling_frequency_index for 16000 Hz.
        private const val ADTS_FREQ_IDX_16K = 8
        private const val INPUT_TIMEOUT_US = 10_000L
        private const val OUTPUT_TIMEOUT_US = 10_000L
        // ~1 s of tries to hand off the end-of-stream input buffer.
        private const val EOS_QUEUE_TRIES = 100

        /**
         * Build the 7-byte ADTS header (no CRC) that MediaCodec's bare AAC-LC
         * frames lack — Soniox's `audio_format:"auto"` reads sample rate +
         * channels from it, so every frame must carry one. Bit layout per
         * wiki.multimedia.cx/index.php/ADTS. [aacFrameLen] is the raw payload
         * size; the header's 13-bit frame_length field must include these 7
         * bytes and is spread across bytes 3-5.
         */
        private fun adtsHeader(aacFrameLen: Int): ByteArray {
            val fullLen = aacFrameLen + ADTS_HEADER_LEN
            val profile = 1 // AAC-LC: object type 2, ADTS profile field = objectType - 1
            val freqIdx = ADTS_FREQ_IDX_16K
            val chanCfg = 1 // mono
            return byteArrayOf(
                0xFF.toByte(), // syncword 11111111
                0xF1.toByte(), // syncword 1111, MPEG-4 (0), layer 00, protection_absent 1
                ((profile shl 6) or (freqIdx shl 2) or (chanCfg shr 2)).toByte(),
                (((chanCfg and 3) shl 6) or (fullLen shr 11)).toByte(),
                ((fullLen shr 3) and 0xFF).toByte(),
                (((fullLen and 7) shl 5) or 0x1F).toByte(), // 3 frame_length bits + buffer_fullness top 5
                0xFC.toByte(), // buffer_fullness bottom 6 bits (all 1) + num_raw_data_blocks 00
            )
        }
    }
}
