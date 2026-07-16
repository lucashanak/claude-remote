package com.clauderemote.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Base64
import com.clauderemote.util.FileLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Streaming text-to-speech via Soniox's WebSocket API
 * (`wss://tts-rt.soniox.com/tts-websocket`). The old REST path POSTed the
 * whole text and waited for the ENTIRE mp3 to generate + download before a
 * single sound — fine for the short settings-test sentence, but a ~30s wait
 * for a long chat reply.
 *
 * Here the text is sent once (`text_end: true`) and the server streams audio
 * back as it generates; each chunk is decoded and written to an [AudioTrack]
 * in MODE_STREAM, so playback starts on the first chunk (~hundreds of ms)
 * instead of after the whole utterance. AudioTrack's blocking `write` provides
 * natural backpressure.
 *
 * We ask Soniox for **MP3** (`audio_format: "mp3"` @ 48 kbps) instead of raw
 * PCM to cut the bytes on the wire — 24 kHz mono 16-bit PCM is ~384 kbps,
 * MP3 @ 48 kbps is ~1/8th the data. The compressed base64 chunks are streamed
 * through a [Mp3Decoder] (MediaCodec) and the decoded PCM goes to the
 * AudioTrack. Chunks stay small on the wire; only the tiny compressed backlog
 * lives in memory. If the device has no MP3 decoder the error is surfaced via
 * `onError` (the caller falls back to another engine) rather than crashing.
 *
 * Not file/MediaPlayer based (that's [MediaTtsCore], used by the other
 * engines) — a growing PCM stream needs AudioTrack, not a temp file.
 */
internal object SonioxTts {
    private const val TAG = "SonioxTts"
    private const val WS_URL = "wss://tts-rt.soniox.com/tts-websocket"
    private const val MODEL = "tts-rt-v1"
    private const val SAMPLE_RATE = 24000
    // Soniox MP3 only allows [32000,64000,96000,128000,192000,256000,320000];
    // 48000 was rejected with HTTP 400 → no audio at all. 64k is the lowest
    // valid speech-grade rate.
    private const val BITRATE = 64000
    // MediaCodec dequeue timeouts (µs). MediaCodec calls are blocking, so these
    // bound how long the decoder threads park between polls.
    private const val INPUT_TIMEOUT_US = 10_000L
    private const val OUTPUT_TIMEOUT_US = 10_000L
    private const val DRAIN_TICK_MS = 20L
    private const val DRAIN_MAX_TICKS = 400   // 8s cap

    private val http = OkHttpClient.Builder().build()

    // Monotonic token: each speak()/stop() bumps it so a superseded stream's
    // late audio chunks can't play over a newer utterance.
    private val generation = AtomicInteger(0)
    private val ws = AtomicReference<WebSocket?>(null)
    private val track = AtomicReference<AudioTrack?>(null)
    private val decoder = AtomicReference<Listener.Mp3Decoder?>(null)
    private val completion = AtomicReference<(() -> Unit)?>(null)

    fun speak(
        context: Context,
        apiKey: String,
        voice: String,
        text: String,
        rate: Float,
        onFinish: () -> Unit,
        onError: ((String) -> Unit)?,
    ) {
        stop() // supersede anything in flight
        val gen = generation.incrementAndGet()
        completion.set(onFinish)
        if (apiKey.isBlank()) {
            onError?.let { postOnMain { it("Chybí Soniox API klíč") } }
            fireCompletion()
            return
        }
        FileLogger.log(TAG, "speak: ${text.length} chars, gen=$gen, voice=$voice")
        val listener = Listener(gen, voice, apiKey, text, rate, onError)
        ws.set(http.newWebSocket(Request.Builder().url(WS_URL).build(), listener))
    }

    fun stop() {
        generation.incrementAndGet() // supersede
        ws.getAndSet(null)?.let { runCatching { it.close(1000, null) } }
        // Release the MP3 decoder (its thread self-observes the bumped
        // generation and tears down the codec) before the track.
        decoder.getAndSet(null)?.release()
        track.getAndSet(null)?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            runCatching { it.release() }
        }
        fireCompletion()
    }

    private fun fireCompletion() {
        completion.getAndSet(null)?.let { postOnMain(it) }
    }

    private class Listener(
        private val gen: Int,
        private val voice: String,
        private val apiKey: String,
        private val text: String,
        private val rate: Float,
        private val onError: ((String) -> Unit)?,
    ) : WebSocketListener() {

        private val streamId = "cr-$gen"
        // Frames written to the track so far. Used to drain the buffer fully
        // before release so the last word isn't cut off.
        @Volatile private var writtenFrames = 0
        // Actual PCM format reported by the decoder (INFO_OUTPUT_FORMAT_CHANGED).
        // MP3 @ 24000 mono normally decodes 1:1, but read the real values for
        // robustness and size the AudioTrack + lead-in from them.
        @Volatile private var outSampleRate = SAMPLE_RATE
        @Volatile private var outChannels = 1
        // Streaming MP3 decoder, created lazily on the first audio chunk (like
        // the AudioTrack was). Touched only on the WS listener thread.
        private var mp3: Mp3Decoder? = null

        // Byte budgets derived from the real output format (bytes/s = rate * ch * 2).
        private val bytesPerSec get() = outSampleRate * outChannels * 2
        private val leadInBytes get() = bytesPerSec / 10 // ~0.1 s
        private fun framesOf(bytes: Int) = bytes / (2 * outChannels)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            FileLogger.log(TAG, "WS open (gen=$gen)")
            val config = JSONObject().apply {
                put("api_key", apiKey)
                put("stream_id", streamId) // required — server rejects config without it
                put("model", MODEL)
                put("language", "cs")
                put("voice", voice.ifBlank { "Adrian" })
                // MP3 instead of PCM — see class KDoc (bytes-on-the-wire budget).
                put("audio_format", "mp3")
                put("bitrate", BITRATE)
                put("sample_rate", SAMPLE_RATE)
                // Speed is applied client-side via AudioTrack.playbackParams
                // (pitch-preserving time-stretch), NOT via Soniox's `speed`
                // field — that field hard-rejects anything outside 0.7–1.3
                // with a 400 (confirmed against the live API), which would
                // break TTS entirely at a slider setting like 1.5.
            }
            webSocket.send(config.toString())
            // Whole reply in one shot; server streams audio as it generates.
            webSocket.send(
                JSONObject().put("stream_id", streamId).put("text", text).put("text_end", true).toString(),
            )
        }

        override fun onMessage(webSocket: WebSocket, textMsg: String) {
            val cur = generation.get()
            val obj = runCatching { JSONObject(textMsg) }.getOrNull()
            // Diagnostic: every frame, BEFORE the supersede check — so a
            // silently-dropped stream (gen != cur) or an unexpected payload is
            // visible instead of vanishing.
            FileLogger.log(
                TAG,
                "msg gen=$gen cur=$cur audioB64=${obj?.optString("audio")?.length ?: -1} " +
                    "err=${obj?.optString("error_code")} end=${obj?.optBoolean("audio_end")}",
            )
            if (gen != cur) return
            if (obj == null) return
            val err = obj.optString("error_code").takeIf { it.isNotBlank() && it != "null" }
            if (err != null) {
                val msg = obj.optString("error_message").ifBlank { err }
                FileLogger.log(TAG, "server error: $err — $msg")
                onError?.let { postOnMain { it("Soniox TTS: $msg") } }
                stop()
                return
            }
            val b64 = obj.optString("audio")
            if (b64.isNotBlank()) {
                val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    // base64 now carries MP3, not PCM — hand it to the decoder,
                    // which emits PCM into writePcm.
                    ensureDecoder(webSocket)?.feed(bytes)
                }
            }
            if (obj.optBoolean("audio_end") || obj.optBoolean("terminated")) {
                // EOS must pass *through* the decoder first so the tail of the
                // MP3 stream flushes; the decoder then drains AudioTrack and
                // calls finishPlayback. If no audio ever arrived, finish here.
                val d = mp3
                if (d != null) d.signalEnd() else finishPlayback(webSocket)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Log ALWAYS (even superseded) — a stale-gen silent return hid the
            // real reason read-aloud died with no trace after speakRouted.
            FileLogger.log(TAG, "WS failure (gen=$gen cur=${generation.get()}) code=${response?.code}: ${t.message}")
            if (gen != generation.get()) return
            onError?.let { postOnMain { it("Soniox TTS: ${t.message ?: "chyba spojení"}") } }
            stop()
        }

        /**
         * Lazily create the MP3 decoder. If this device has no "audio/mpeg"
         * decoder, log it and surface the error so the caller falls back to
         * another engine — no crash.
         */
        private fun ensureDecoder(webSocket: WebSocket): Mp3Decoder? {
            mp3?.let { return it }
            val d = runCatching { Mp3Decoder(webSocket) }.getOrNull()
            if (d == null) {
                FileLogger.log(TAG, "no MP3 decoder — surfacing error for fallback")
                onError?.let { postOnMain { it("Soniox TTS: MP3 dekodér není dostupný") } }
                stop()
                return null
            }
            mp3 = d
            decoder.set(d)
            return d
        }

        /** Lazily create + start the AudioTrack on the first chunk, then write. */
        private fun writePcm(pcm: ByteArray) {
            if (gen != generation.get()) return
            var t = track.get()
            if (t == null) {
                val channelMask =
                    if (outChannels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val minBuf = AudioTrack.getMinBufferSize(
                    outSampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT,
                )
                t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(outSampleRate)
                            .setChannelMask(channelMask)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, bytesPerSec / 2)) // ~0.5s
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track.set(t)
                // Reading speed, pitch-preserved. Clamped to a device-safe
                // range; if the device rejects the value, playback just stays
                // at 1.0 rather than failing.
                if (rate != 1.0f) runCatching {
                    t.playbackParams = t.playbackParams.setSpeed(rate.coerceIn(0.5f, 2.5f))
                }
                runCatching { t.play() }
                // Lead-in silence: MODE_STREAM warms up on the first buffer and
                // clips the very start otherwise — a short pad means the real
                // first word starts after the track is already running.
                val silence = ByteArray(leadInBytes)
                runCatching { t.write(silence, 0, silence.size) }
                writtenFrames += framesOf(silence.size)
                FileLogger.log(TAG, "playback started (speed=$rate, ${outSampleRate}Hz/${outChannels}ch)")
            }
            // Blocking write = natural backpressure; the decoder output thread
            // paces to playback speed instead of buffering the whole reply.
            runCatching { t.write(pcm, 0, pcm.size) }
            writtenFrames += framesOf(pcm.size)
        }

        private fun finishPlayback(webSocket: WebSocket) {
            if (gen != generation.get()) return
            track.getAndSet(null)?.let { t ->
                // Wait for the play head to reach the last written frame before
                // stopping — releasing right after the final write() cut the
                // last word off (the buffered tail hadn't played yet). Capped
                // so a stuck head can't hang the thread.
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
            // Codec already self-releases when its output loop returns after EOS.
            decoder.compareAndSet(mp3, null)
            ws.compareAndSet(webSocket, null)
            runCatching { webSocket.close(1000, null) }
            fireCompletion()
        }

        /**
         * Streaming MP3 → PCM decoder over MediaCodec. Input (compressed MP3
         * chunks off the WS) is fed on the WS listener thread; output (decoded
         * PCM) is pulled on a dedicated daemon thread — MediaCodec permits
         * input and output from separate threads. Decoded PCM is handed to
         * [writePcm] (→ AudioTrack). MP3 frames are self-syncing, so WS chunk
         * boundaries don't have to align with frame boundaries.
         */
        inner class Mp3Decoder(private val webSocket: WebSocket) {
            private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
            // MP3 chunks awaiting a free input buffer + offset into the head
            // chunk. WS-listener-thread-only (feed/signalEnd/pumpInput).
            private val pending = ArrayDeque<ByteArray>()
            private var chunkOffset = 0
            private var endRequested = false
            private var endQueued = false
            @Volatile private var released = false
            private val thread: Thread

            init {
                // No CSD for MP3; sample rate/channels here are hints — the real
                // values arrive via INFO_OUTPUT_FORMAT_CHANGED.
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, SAMPLE_RATE, 1)
                codec.configure(fmt, null, null, 0)
                codec.start()
                thread = Thread({ outputLoop() }, "SonioxMp3Dec-$gen").apply {
                    isDaemon = true
                    start()
                }
            }

            /** Queue an MP3 chunk and push as much as the codec will take now. */
            fun feed(mp3Bytes: ByteArray) {
                if (released || gen != generation.get()) return
                pending.addLast(mp3Bytes)
                pumpInput()
            }

            /** Signal end-of-input; the codec flushes its tail and emits EOS. */
            fun signalEnd() {
                if (released || gen != generation.get()) return
                endRequested = true
                pumpInput()
            }

            // Fill free input buffers from `pending`; once drained, queue the
            // EOS marker if end was requested. Blocks only in short dequeue
            // timeouts; the generation guard is the escape hatch.
            private fun pumpInput() {
                while (!released && gen == generation.get()) {
                    if (pending.isEmpty() && (!endRequested || endQueued)) return
                    val idx = runCatching { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(-1)
                    if (idx < 0) continue // no free input buffer yet; the output loop is recycling them
                    if (pending.isEmpty()) {
                        // Only reachable with end requested: mark end-of-stream.
                        runCatching {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                        endQueued = true
                        return
                    }
                    val buf = runCatching { codec.getInputBuffer(idx) }.getOrNull() ?: return
                    val chunk = pending.first()
                    val n = minOf(buf.remaining(), chunk.size - chunkOffset)
                    buf.put(chunk, chunkOffset, n)
                    runCatching { codec.queueInputBuffer(idx, 0, n, 0, 0) }
                    chunkOffset += n
                    if (chunkOffset >= chunk.size) {
                        pending.removeFirst()
                        chunkOffset = 0
                    }
                }
            }

            private fun outputLoop() {
                val info = MediaCodec.BufferInfo()
                try {
                    while (!released && gen == generation.get()) {
                        val idx = runCatching { codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US) }
                            .getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                        when {
                            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val f = codec.outputFormat
                                outSampleRate = f.optInt(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE)
                                outChannels = f.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1)
                            }
                            idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit // keep polling
                            idx >= 0 -> {
                                if (info.size > 0) {
                                    val out = runCatching { codec.getOutputBuffer(idx) }.getOrNull()
                                    if (out != null) {
                                        // Output is 16-bit LE PCM — directly
                                        // compatible with ENCODING_PCM_16BIT.
                                        out.position(info.offset)
                                        out.limit(info.offset + info.size)
                                        val pcm = ByteArray(info.size)
                                        out.get(pcm)
                                        writePcm(pcm)
                                    }
                                }
                                runCatching { codec.releaseOutputBuffer(idx, false) }
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    // Tail decoded — drain the track then finish.
                                    finishPlayback(webSocket)
                                    return
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Codec torn down mid-loop (release/stop) — fall through.
                } finally {
                    // Own the codec lifecycle on this thread so it's never torn
                    // down mid-dequeue from another thread.
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
            }

            /** Ask the output loop to exit; it self-releases the codec. */
            fun release() {
                released = true
                runCatching { thread.join(200) }
            }
        }
    }
}

/** MediaFormat.getInteger throws if the key is absent; default instead. */
private fun MediaFormat.optInt(key: String, def: Int): Int =
    if (containsKey(key)) getInteger(key) else def
