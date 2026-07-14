package com.clauderemote.wear

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * On-watch streaming TTS via Soniox's WebSocket API — the watch reads a
 * session message aloud in Soniox's voice instead of the on-device Android
 * TTS ([WatchTts]). Port of the phone's SonioxTts (wearApp has no dependency
 * on `shared`).
 *
 * Unlike the phone (SonioxTts, which asks for raw PCM over Wi-Fi), the watch
 * asks Soniox for **MP3** and decodes it on-device. A watch has no Wi-Fi, so
 * Soniox audio is tunnelled through the phone's Bluetooth link, and 24 kHz
 * mono 16-bit PCM (~384 kbps) overruns that link and stutters. MP3 @ 48 kbps
 * is ~1/8th the bytes, which the tunnel carries comfortably. The compressed
 * chunks are streamed through a [Mp3Decoder] (MediaCodec) and the decoded PCM
 * goes to an AudioTrack in MODE_STREAM so playback starts on the first chunk.
 * 100 ms silence lead-in + drain-before-release avoid clipping the
 * first/last word. Requires a Soniox key synced from the phone
 * ([SonioxKeyStore]); [WatchTts] remains the fallback when there's no key or
 * the device has no MP3 decoder.
 */
object SonioxWatchTts {
    private const val TAG = "SonioxWatchTts"
    private const val WS_URL = "wss://tts-rt.soniox.com/tts-websocket"
    private const val MODEL = "tts-rt-v1"
    private const val SAMPLE_RATE = 24000
    // Soniox MP3 only allows [32000,64000,96000,128000,192000,256000,320000];
    // 48000 was rejected with HTTP 400 → no audio at all. 64k is the lowest
    // valid speech-grade rate (still ~1/6th of 24 kHz raw PCM).
    private const val BITRATE = 64000
    // MediaCodec dequeue timeouts (µs). MediaCodec calls are blocking, so these
    // bound how long the decoder threads park between polls.
    private const val INPUT_TIMEOUT_US = 10_000L
    private const val OUTPUT_TIMEOUT_US = 10_000L
    private const val DRAIN_TICK_MS = 20L
    private const val DRAIN_MAX_TICKS = 400

    private val http = OkHttpClient.Builder().build()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger(0)
    private val ws = AtomicReference<WebSocket?>(null)
    private val track = AtomicReference<AudioTrack?>(null)
    private val decoder = AtomicReference<Listener.Mp3Decoder?>(null)
    private val completion = AtomicReference<(() -> Unit)?>(null)

    /** @param onDone fired (on main) when playback finishes on its own or fails. */
    fun speak(context: Context, text: String, onDone: () -> Unit = {}) {
        val apiKey = SonioxKeyStore.apiKey
        if (apiKey.isBlank()) {
            WearLog.w(context, TAG, "no Soniox key — falling back to on-device TTS")
            WatchTts.speak(context, text)
            main.post(onDone)
            return
        }
        stop()
        completion.set(onDone)
        val gen = generation.incrementAndGet()
        val req = Request.Builder().url(WS_URL).build()
        ws.set(http.newWebSocket(req, Listener(
            context.applicationContext, gen, apiKey,
            SonioxKeyStore.ttsVoice, SonioxKeyStore.ttsSpeedPct, text,
        )))
    }

    /** Stop playback now (the "stop reading" button / a superseding speak). */
    fun stop() {
        generation.incrementAndGet()
        completion.set(null) // caller-initiated stop — don't fire onDone
        ws.getAndSet(null)?.let { runCatching { it.close(1000, null) } }
        // Release the MP3 decoder (its thread self-observes the bumped
        // generation and tears down the codec) before the track.
        decoder.getAndSet(null)?.release()
        track.getAndSet(null)?.let {
            runCatching { it.pause(); it.flush(); it.stop() }
            runCatching { it.release() }
        }
    }

    private fun fireDone() {
        completion.getAndSet(null)?.let { main.post(it) }
    }

    private class Listener(
        private val ctx: Context,
        private val gen: Int,
        private val apiKey: String,
        private val voice: String,
        private val speedPct: Int,
        private val text: String,
    ) : WebSocketListener() {
        private val streamId = "cr-$gen"
        @Volatile private var writtenFrames = 0
        // Pre-play jitter buffer. Accumulate ~0.75 s of *decoded* audio before
        // the first play() so an uneven downlink can't underrun AudioTrack and
        // stutter. Now fed by the MP3 decoder, so it holds PCM, not WS bytes.
        // Accessed only from the decoder output thread (see writePcm), so no
        // extra locking.
        private val prebuffer = java.io.ByteArrayOutputStream()
        @Volatile private var playing = false
        // Actual PCM format reported by the decoder (INFO_OUTPUT_FORMAT_CHANGED).
        // MP3 @ 24000 mono normally decodes 1:1, but read the real values for
        // robustness and size the AudioTrack + jitter/lead-in from them.
        @Volatile private var outSampleRate = SAMPLE_RATE
        @Volatile private var outChannels = 1
        // Streaming MP3 decoder, created lazily on the first audio chunk (like
        // the AudioTrack was). Touched only on the WS listener thread.
        private var mp3: Mp3Decoder? = null

        // Byte budgets derived from the real output format (bytes/s = rate * ch * 2).
        private val bytesPerSec get() = outSampleRate * outChannels * 2
        private val prebufferBytes get() = bytesPerSec * 3 / 4 // ~0.75 s
        private val leadInBytes get() = bytesPerSec / 10        // ~0.1 s
        private fun framesOf(bytes: Int) = bytes / (2 * outChannels)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            WearLog.i(ctx, TAG, "WS open")
            val cfg = JSONObject().apply {
                put("api_key", apiKey)
                put("stream_id", streamId)
                put("model", MODEL)
                put("language", "cs")
                put("voice", voice.ifBlank { "Adrian" })
                // MP3 instead of PCM — see class KDoc (Bluetooth downlink budget).
                put("audio_format", "mp3")
                put("bitrate", BITRATE)
                put("sample_rate", SAMPLE_RATE)
            }
            webSocket.send(cfg.toString())
            webSocket.send(JSONObject().put("stream_id", streamId).put("text", text).put("text_end", true).toString())
        }

        override fun onMessage(webSocket: WebSocket, textMsg: String) {
            if (gen != generation.get()) return
            val obj = runCatching { JSONObject(textMsg) }.getOrNull() ?: return
            val err = obj.optString("error_code").takeIf { it.isNotBlank() && it != "null" }
            if (err != null) {
                WearLog.w(ctx, TAG, "server error: $err — ${obj.optString("error_message")}")
                fireDone()
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
            if (gen != generation.get()) return
            WearLog.w(ctx, TAG, "WS failure: ${t.message}")
            stop() // releases decoder + track
        }

        /**
         * Lazily create the MP3 decoder. If this device has no "audio/mpeg"
         * decoder (very unlikely, but a watch OEM could ship a stripped codec
         * set), fall back to on-device TTS for this message and return null.
         */
        private fun ensureDecoder(webSocket: WebSocket): Mp3Decoder? {
            mp3?.let { return it }
            val d = runCatching { Mp3Decoder(webSocket) }.getOrNull()
            if (d == null) {
                WearLog.w(ctx, TAG, "no MP3 decoder — falling back to on-device TTS")
                WatchTts.speak(ctx, text)
                fireDone()
                stop()
                return null
            }
            mp3 = d
            decoder.set(d)
            return d
        }

        private fun writePcm(pcm: ByteArray) {
            if (gen != generation.get()) return
            if (playing) {
                val t = track.get() ?: return
                runCatching { t.write(pcm, 0, pcm.size) }
                writtenFrames += framesOf(pcm.size)
                return
            }
            // Still filling the jitter buffer — hold audio until we have
            // prebufferBytes, then start playback with that head start.
            prebuffer.write(pcm, 0, pcm.size)
            if (prebuffer.size() >= prebufferBytes) startPlayback()
        }

        /** Create the track, prime it with lead-in silence + the buffered audio, and play. */
        private fun startPlayback() {
            if (playing || gen != generation.get()) return
            val channelMask =
                if (outChannels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(
                outSampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT,
            )
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
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
                // ~1 s hardware buffer — slack to ride out downlink jitter
                // without underrunning.
                .setBufferSizeInBytes(maxOf(minBuf, bytesPerSec))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.set(t)
            // Reading speed synced from the phone, pitch-preserved.
            if (speedPct != 100) runCatching {
                t.playbackParams = t.playbackParams.setSpeed((speedPct / 100f).coerceIn(0.5f, 2.5f))
            }
            runCatching { t.play() }
            val silence = ByteArray(leadInBytes)
            runCatching { t.write(silence, 0, silence.size) }
            writtenFrames += framesOf(silence.size)
            val buffered = prebuffer.toByteArray()
            prebuffer.reset()
            runCatching { t.write(buffered, 0, buffered.size) }
            writtenFrames += framesOf(buffered.size)
            playing = true
            WearLog.i(ctx, TAG, "playback started (prebuffered ${buffered.size} B @ ${outSampleRate}Hz/${outChannels}ch)")
        }

        private fun finishPlayback(webSocket: WebSocket) {
            if (gen != generation.get()) return
            // Short message whose audio never reached the prebuffer threshold:
            // flush what we have so it still plays.
            if (!playing && prebuffer.size() > 0) startPlayback()
            track.getAndSet(null)?.let { t ->
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
            fireDone()
        }

        /**
         * Streaming MP3 → PCM decoder over MediaCodec. Input (compressed MP3
         * chunks off the WS) is fed on the WS listener thread; output (decoded
         * PCM) is pulled on a dedicated daemon thread — MediaCodec permits
         * input and output from separate threads. Decoded PCM is handed to
         * [writePcm] (jitter buffer → AudioTrack). MP3 frames are self-syncing,
         * so WS chunk boundaries don't have to align with frame boundaries.
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
