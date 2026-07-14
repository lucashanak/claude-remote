package com.clauderemote.wear

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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-watch streaming STT via Soniox's WebSocket API — dictate a reply on the
 * watch in Soniox's Czech (+English code-switching) instead of the system
 * voice input. Port of the phone's SonioxDictation (wearApp has no dependency
 * on `shared`). Captures 16-kHz PCM from the mic, encodes it on-device to
 * AAC-LC (ADTS framing) and streams that — the compressed uplink is ~8-10x
 * smaller than raw PCM, which matters on a watch whose Soniox socket is
 * tunnelled through the phone's Bluetooth link. Gets word-by-word tokens back;
 * [onPartial] fires with the growing transcript, [onFinal] with the settled
 * text once an endpoint (silence) is detected or [stop] is called. Requires a
 * Soniox key synced from the phone ([SonioxKeyStore]) and RECORD_AUDIO.
 *
 * Unlike the phone port, mic capture (and the encoder) start the instant
 * [start] is called (NOT in onOpen): on a watch with no Wi-Fi the handshake
 * can take seconds — so whatever the user says before onOpen would be lost
 * ("cuts off the start"). Frames produced before the socket is ready are
 * buffered and flushed the moment it opens.
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
    // AAC encoder; null means it couldn't be created and we fall back to raw
    // PCM so dictation still works (see [startEncoder]).
    @Volatile private var aacEncoder: AacEncoder? = null
    private val finalText = StringBuilder()

    // Outbound frames (ADTS AAC, or raw PCM in the fallback path) produced
    // before the socket opened, flushed on onOpen. Guarded by itself. Capped so
    // a socket that never opens can't grow it unbounded.
    private val backlog = ArrayDeque<ByteString>()

    // Diagnostics logged with the final, so a bad transcript is explainable
    // without a device in hand:
    //  - connect latency: how long onOpen took (≈ how much lead the pre-buffer
    //    had to cover; large = the old code would have clipped that much).
    //  - sent bytes: total compressed ADTS AAC bytes pushed to the socket
    //    (~24 kbit/s, ~8-10x smaller than the old raw PCM); ≈0 means the mic
    //    captured nothing (permission/screen-off), lots + 0 chars means audio
    //    flowed but Soniox heard silence/garbage.
    //  - peak WS queue: bytes stuck in OkHttp's send buffer — grows when the
    //    uplink (BT tunnel) can't keep up; AAC's small frames keep this near
    //    zero where raw PCM would back it up.
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
        // Encoder + capture BEFORE the socket handshake — see class kdoc. Frames
        // buffer into [backlog] until onOpen flips [liveSocket] on.
        aacEncoder = startEncoder()
        startCapture()
        ws = http.newWebSocket(Request.Builder().url(WS_URL).build(), Listener(apiKey))
    }

    fun stop() {
        if (stopped) return
        stopped = true
        // Don't force-release the encoder here: the capture thread's finally
        // block signals end-of-stream so the AAC tail drains and the encoder's
        // onEnd sends the Soniox end-of-audio marker only AFTER the last frames
        // have gone out. In the PCM fallback there's no encoder, so send it now.
        if (aacEncoder == null) runCatching { ws?.send("") }
    }

    private inner class Listener(private val apiKey: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connectMs = (System.nanoTime() - startNanos) / 1_000_000
            WearLog.i(context, TAG, "WS open (connect $connectMs ms)")
            val config = JSONObject().apply {
                put("api_key", apiKey)
                put("model", MODEL)
                // "auto": Soniox reads container/codec (and, for ADTS, sample
                // rate + channels) from the stream headers, so we send neither
                // sample_rate nor num_channels — the ADTS header carries them.
                put("audio_format", "auto")
                put("language_hints", JSONArray(listOf("cs", "en")))
                put("enable_endpoint_detection", true)
            }
            webSocket.send(config.toString())
            // Send path now drains [backlog] then streams live.
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
        // Server-side endpoint: it already has all the audio, so just tear the
        // encoder down (no tail to drain, unlike the user-stop path).
        aacEncoder?.release()
        aacEncoder = null
        val settled = synchronized(this) { finalText.toString() }.trim()
        WearLog.i(
            context, TAG,
            "final (${settled.length} chars, $bytesSent sent bytes, connect $connectMs ms, peak WS queue $peakQueueBytes B)",
        )
        main.post { onFinal(settled) }
        runCatching { webSocket.close(1000, null) }
    }

    /**
     * Route one outbound frame to the socket, or buffer it until [onOpen] flips
     * [liveSocket] on — the pre-buffer-before-open mechanism that stops the
     * start of speech being clipped. [bytesSent]/[peakQueueBytes] track the
     * compressed ADTS bytes actually pushed.
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
            // never-opening socket can't grow it without limit.
            synchronized(backlog) {
                backlog.addLast(frame)
                while (backlog.size > MAX_BACKLOG_FRAMES) backlog.removeFirst()
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

    /**
     * Create + start the AAC-LC encoder that turns captured PCM into ADTS
     * frames on [sendFrame]. Returns null (→ raw-PCM fallback in [startCapture])
     * if the device can't give us an AAC encoder — unlikely, but we'd rather
     * degrade to the old uncompressed path than kill dictation outright.
     */
    private fun startEncoder(): AacEncoder? = runCatching {
        AacEncoder(
            onFrame = { frame -> sendFrame(frame) },
            // End-of-stream reached: the tail frames have already been emitted
            // above, so it's safe to tell Soniox the audio is finished.
            onEnd = { runCatching { ws?.send("") } },
        ).also { it.start() }
    }.getOrElse {
        WearLog.w(context, TAG, "AAC encoder nedostupný, fallback na PCM: ${it.message}")
        null
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
                    val enc = aacEncoder
                    if (enc != null) {
                        // Copy the valid slice out of the reused buffer; the
                        // encoder's output thread produces ADTS → sendFrame.
                        enc.encode(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
                    } else {
                        // Fallback: stream raw PCM exactly as before.
                        val chunk: ByteString =
                            if (n == buf.size) buf.toByteString() else buf.toByteString(0, n)
                        sendFrame(chunk)
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
        private const val TAG = "SonioxWatchStt"
        private const val WS_URL = "wss://stt-rt.soniox.com/transcribe-websocket"
        private const val MODEL = "stt-rt-v5"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_BYTES = 3200
        // ~10 s of ~64 ms AAC frames — a generous ceiling on pre-open buffering.
        private const val MAX_BACKLOG_FRAMES = 150

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
