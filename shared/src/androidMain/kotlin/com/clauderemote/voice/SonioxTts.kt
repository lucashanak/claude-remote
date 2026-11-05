package com.clauderemote.voice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.util.Base64
import androidx.core.content.ContextCompat
import com.clauderemote.util.FileLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** WebSocket upgrade request carrying the Soniox key the current way (header). */
internal fun sonioxWsRequest(url: String, apiKey: String): Request =
    Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build()

/**
 * Read-aloud through Soniox's real-time TTS, as a small media player.
 *
 * Why it is shaped like this (each one is a failure from the car, 2026-11-05):
 *  - One request per reply failed on long replies: Soniox caps a stream at
 *    5000 bytes of text (400) and 2 minutes of audio (413). The text is now cut
 *    into paragraph-sized segments ([ttsSegments]), each its own stream on one
 *    shared WebSocket (`stream_id`, up to 2 in flight — the account allows 3).
 *  - A dropped signal ended the reading and the rest was lost. Audio is now
 *    downloaded into memory ahead of playback (μ-law, 8 kB/s, so a 10-minute
 *    reply is ~5 MB) and played from there: a dropout plays on from what is
 *    already downloaded while the socket reconnects with backoff, and a
 *    segment cut mid-download is fetched again and joined ~3 s before the
 *    point the old copy ran out. Giving up (after [GIVE_UP_MS]) leaves the
 *    player paused at that segment, so ▶ continues instead of starting over.
 *  - No way to pause or skip. Pause/resume, previous/next paragraph and −10 s
 *    work from the in-app bar ([TtsPlayback]), the media notification, the lock
 *    screen and the car's / headset's media buttons ([MediaSession]). Audio
 *    focus is transient: music pauses while Claude reads; a phone call pauses
 *    the reading and it resumes afterwards.
 *
 * Audio is G.711 μ-law at 8000 Hz (the only rate Soniox allows for μ-law),
 * expanded to 16-bit PCM by table lookup — no MediaCodec, which used to stall
 * the stream. Reading speed is applied by AudioTrack's pitch-preserving
 * playbackParams, because Soniox's own `speed` rejects anything outside
 * 0.7–1.3.
 */
internal object SonioxTts {
    private const val TAG = "SonioxTts"

    private val http = OkHttpClient.Builder()
        // Pings let OkHttp notice a dead socket (car tunnel) in seconds rather
        // than waiting for TCP to time out.
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val current = java.util.concurrent.atomic.AtomicReference<Session?>(null)

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
        if (apiKey.isBlank()) {
            onError?.let { postOnMain { it("Chybí Soniox API klíč") } }
            postOnMain(onFinish)
            return
        }
        val segments = ttsSegments(text)
        if (segments.isEmpty()) {
            postOnMain(onFinish)
            return
        }
        FileLogger.log(TAG, "speak: ${text.length} chars → ${segments.size} segments " +
            segments.joinToString(",", "[", "]") { it.length.toString() })
        val s = Session(context.applicationContext, apiKey, voice, rate, segments, onFinish, onError)
        current.set(s)
        s.start()
    }

    fun stop() {
        current.getAndSet(null)?.stop()
    }

    internal fun ended(s: Session) {
        current.compareAndSet(s, null)
    }

    internal fun http() = http

    // ── constants shared with Session ──────────────────────────────────────
    internal const val WS_URL = "wss://tts-rt.soniox.com/tts-websocket"
    internal const val MODEL = "tts-rt-v2"
    internal const val SAMPLE_RATE = 8000          // μ-law is only allowed at 8000 Hz
    internal const val CHUNK_SAMPLES = 1600        // 200 ms per AudioTrack write
    internal const val MAX_IN_FLIGHT = 2           // concurrent streams (account cap 3)
    internal const val REJOIN_OVERLAP_SAMPLES = 3 * SAMPLE_RATE
    internal const val GIVE_UP_MS = 5 * 60_000L
    internal const val MAX_STREAM_ATTEMPTS = 5
    internal const val IDLE_CLOSE_MS = 25_000L     // server drops idle sockets at ~40 s
}

/** μ-law byte → 16-bit linear sample (G.711). */
internal fun mulawToLinear(mu: Int): Int {
    val u = mu.inv() and 0xFF
    val sign = u and 0x80
    val exponent = (u shr 4) and 0x07
    val mantissa = u and 0x0F
    var sample = ((mantissa shl 3) + 0x84) shl exponent
    sample -= 0x84
    return if (sign != 0) -sample else sample
}

private val MULAW_TABLE = ShortArray(256) { mulawToLinear(it).toShort() }

private fun mulawToPcm16(src: ByteArray, len: Int): ByteArray {
    val out = ByteArray(len * 2)
    var j = 0
    for (i in 0 until len) {
        val s = MULAW_TABLE[src[i].toInt() and 0xFF].toInt()
        out[j++] = (s and 0xFF).toByte()
        out[j++] = ((s shr 8) and 0xFF).toByte()
    }
    return out
}

/** Growable μ-law buffer for one segment. Guarded by the session lock. */
private class SegBuf {
    var data = ByteArray(0)
    var size = 0
    var done = false
    /** A re-download running while the player still reads the old copy. */
    var pending: SegBuf? = null
    /** Server errors in a row for this segment (connection drops don't count). */
    var serverErrors = 0
    /** 429s in a row (account stream cap) — a separate, larger budget (~1 min). */
    var rateLimited = 0
    /** Not before this time (ms) — backoff after a server error. */
    var retryAt = 0L

    fun append(b: ByteArray) {
        if (size + b.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + b.size, 16_000))
        System.arraycopy(b, 0, data, size, b.size)
        size += b.size
    }
}

internal class Session(
    private val ctx: Context,
    private val apiKey: String,
    private val voice: String,
    private val rate: Float,
    private val segments: List<String>,
    private val onFinish: () -> Unit,
    private val onError: ((String) -> Unit)?,
) : TtsPlayerController {
    private val tag = "SonioxTts"
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val bufs = Array(segments.size) { SegBuf() }
    private val sched = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "soniox-tts-ctl").apply { isDaemon = true }
    }

    // ── all below guarded by [lock] ────────────────────────────────────────
    private var stopped = false
    private var paused = false
    private var resumeOnFocusGain = false
    /** Set by the player when it ran out of audio while downloads are halted. */
    private var interrupted: String? = null
    /** Downloads gave up (no signal for [GIVE_UP_MS], or a segment kept failing). */
    private var haltNote: String? = null
    private var cur = 0
    private var pos = 0
    private var seekEpoch = 0
    private var ws: WebSocket? = null
    private var wsOpen = false
    private var streamSeq = 0
    private val streams = HashMap<String, Int>()           // stream_id → segment
    private var failingSince = 0L                           // 0 = connection healthy
    private var backoffMs = 0L
    private var reconnectTask: ScheduledFuture<*>? = null
    private var idleTask: ScheduledFuture<*>? = null
    private var retryTask: ScheduledFuture<*>? = null
    private var lastPublished: TtsPlayerState? = null

    @Volatile private var track: AudioTrack? = null
    private var player: Thread? = null
    private var media: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var receiver: BroadcastReceiver? = null
    private val audioManager = ctx.getSystemService(AudioManager::class.java)

    fun start() {
        lock.withLock { if (stopped) return }
        // Focus refused (a phone call is on): start paused instead of talking over it.
        val granted = requestFocus()
        lock.withLock {
            if (stopped) return
            if (!granted) paused = true
            TtsPlayback.attach(this, stateLocked())
        }
        postOnMain { setUpMediaControls() }
        watchNetwork()
        lock.withLock { pumpLocked() }
        player = Thread({ playLoop() }, "soniox-tts-play").apply { start() }
    }

    // ── controller (UI bar, notification, media buttons) ───────────────────

    override fun play() {
        if (lock.withLock { stopped }) return
        // Re-request every time: a permanent loss (another player started)
        // took it away, and asking again while holding it is harmless.
        requestFocus()
        lock.withLock { resumeLocked() }
    }

    private fun resumeLocked() {
        if (stopped) return
        resumeOnFocusGain = false
        if (interrupted != null || haltNote != null) {
            // ▶ after giving up: start downloading again from a clean slate.
            interrupted = null
            haltNote = null
            failingSince = 0L
            backoffMs = 0L
            for (b in bufs) { b.serverErrors = 0; b.rateLimited = 0; b.retryAt = 0L }
        }
        paused = false
        track?.let { runCatching { it.play() } }
        pumpLocked()
        changed.signalAll()
        publishLocked()
    }

    override fun pause() = lock.withLock {
        if (stopped) return@withLock
        resumeOnFocusGain = false // the user's pause wins over a call ending
        paused = true
        track?.let { runCatching { it.pause() } }
        changed.signalAll()
        publishLocked()
    }

    override fun previous() = lock.withLock {
        if (stopped) return@withLock
        // Like a music player: a few seconds in, ⏮ restarts this paragraph.
        if (pos < 3 * SonioxTts.SAMPLE_RATE && cur > 0) cur--
        pos = 0
        seekLocked()
    }

    override fun next() = lock.withLock {
        if (stopped) return@withLock
        if (cur + 1 >= bufs.size) return@withLock
        cur++
        pos = 0
        seekLocked()
    }

    override fun rewind(seconds: Int) = lock.withLock {
        if (stopped) return@withLock
        var p = pos - seconds * SonioxTts.SAMPLE_RATE
        if (p < 0 && cur > 0) {
            val prev = bufs[cur - 1]
            prev.pending?.takeIf { it.done }?.let { bufs[cur - 1] = it } // finished re-download
            if (bufs[cur - 1].done) {
                cur--
                p += bufs[cur].size
            }
        }
        pos = p.coerceAtLeast(0)
        seekLocked()
    }

    override fun stop() {
        val t: Thread?
        lock.withLock {
            if (stopped) return
            stopped = true
            changed.signalAll()
            t = player
        }
        teardown()
        t?.interrupt()
    }

    private fun seekLocked() {
        seekEpoch++
        track?.let {
            runCatching { it.pause(); it.flush(); if (!paused) it.play() }
        }
        pumpLocked()
        changed.signalAll()
        publishLocked()
    }

    // ── playback thread ────────────────────────────────────────────────────

    private fun playLoop() {
        try {
            val t = buildTrack()
            track = t
            lock.withLock { if (!paused) runCatching { t.play() } }
            // MODE_STREAM clips the very first buffer while it warms up.
            val lead = ByteArray(SonioxTts.SAMPLE_RATE / 5)
            t.write(lead, 0, lead.size)
            FileLogger.log(tag, "playback started (speed=$rate, ${segments.size} segments)")
            val chunk = ByteArray(SonioxTts.CHUNK_SAMPLES)
            val tail = ByteArray(maxOf(t.bufferSizeInFrames * 2, SonioxTts.SAMPLE_RATE))
            while (true) {
                var n = 0
                var epoch: Int
                var finished = false
                lock.withLock {
                    while (true) {
                        if (stopped) return
                        if (paused || interrupted != null) { publishLocked(); changed.await(); continue }
                        val b = bufs[cur]
                        if (pos >= b.size) {
                            val pend = b.pending
                            if (pend != null) {
                                // Old copy ran out: join the re-download a little
                                // before this point (same text, near-same timing).
                                bufs[cur] = pend
                                pos = (pos - SonioxTts.REJOIN_OVERLAP_SAMPLES).coerceAtLeast(0)
                                continue
                            }
                            if (b.done) {
                                if (cur + 1 >= bufs.size) { finished = true; break }
                                cur++
                                pos = 0
                                pumpLocked()
                                publishLocked()
                                continue
                            }
                            // Starved. If downloads gave up, stop here so ▶ resumes
                            // from this point; otherwise wait for the download.
                            haltNote?.let { note ->
                                interrupted = note
                                FileLogger.log(tag, "interrupted at segment ${cur + 1}/${bufs.size}: $note")
                            }
                            publishLocked()
                            if (interrupted == null) changed.await(250, TimeUnit.MILLISECONDS)
                            continue
                        }
                        n = minOf(chunk.size, b.size - pos)
                        System.arraycopy(b.data, pos, chunk, 0, n)
                        pos += n
                        publishLocked()
                        break
                    }
                    epoch = seekEpoch
                }
                if (finished) {
                    // Drain: push a buffer's worth of silence so every real sample
                    // has played before stop — releasing right after the last
                    // write cut the final word off. A seek (⏮, −10 s) during the
                    // drain goes back to playing instead of ending.
                    if (!writeFully(t, tail, epoch)) return
                    if (lock.withLock { epoch == seekEpoch }) break
                    continue
                }
                if (!writeFully(t, mulawToPcm16(chunk, n), epoch)) return
                lock.withLock {
                    // A seek during the blocking write let up to one stale chunk in.
                    if (epoch != seekEpoch && !stopped) {
                        runCatching { t.pause(); t.flush(); if (!paused) t.play() }
                    }
                }
            }
            Thread.sleep(150)
            FileLogger.log(tag, "finished all ${segments.size} segments")
            stop()
        } catch (_: InterruptedException) {
            // stop() interrupts us; teardown already ran.
        } catch (e: Throwable) {
            FileLogger.log(tag, "player crashed: ${e.message}")
            onError?.let { cb -> postOnMain { cb("Soniox TTS: ${e.message}") } }
            stop()
        } finally {
            // Only this thread writes to the track, so only it releases it —
            // releasing from teardown could race a write still in progress.
            track?.let { t ->
                runCatching { t.stop() }
                runCatching { t.release() }
            }
            track = null
        }
    }

    /**
     * Blocking write of the whole buffer. A pause makes AudioTrack return a
     * short count; the remainder is written after resume instead of being
     * dropped (that clipped a syllable on every pause). A seek discards it.
     * Returns false once the session is stopped.
     */
    private fun writeFully(t: AudioTrack, pcm: ByteArray, epoch: Int): Boolean {
        var off = 0
        while (off < pcm.size) {
            val w = t.write(pcm, off, pcm.size - off)
            if (w < 0) throw IllegalStateException("AudioTrack.write failed ($w)")
            off += w
            if (off >= pcm.size) break
            lock.withLock {
                while (!stopped && paused && epoch == seekEpoch) changed.await()
                if (stopped) return false
                if (epoch != seekEpoch) return true
            }
            if (w == 0) Thread.sleep(10)
        }
        return !lock.withLock { stopped }
    }

    private fun buildTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            SonioxTts.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(ttsAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SonioxTts.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, SonioxTts.SAMPLE_RATE)) // ~0.5 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (rate != 1.0f) runCatching {
            t.playbackParams = t.playbackParams.setSpeed(rate.coerceIn(0.5f, 2.5f))
        }
        return t
    }

    // ── download side ──────────────────────────────────────────────────────

    /** Start streams for the next segments that still need audio. */
    private fun pumpLocked() {
        if (stopped || haltNote != null) return
        val now = System.currentTimeMillis()
        val missing = (cur until bufs.size).filter { i ->
            val b = bufs[i]
            !b.done && !(b.pending?.done ?: false) && i !in streams.values
        }
        if (missing.isEmpty()) {
            if (streams.isEmpty()) {
                // Nothing left to download: an old connection failure must not
                // count toward giving up on a later one.
                failingSince = 0L
                backoffMs = 0L
                scheduleIdleCloseLocked()
            }
            return
        }
        val want = missing.filter { bufs[it].retryAt <= now }
        if (want.isEmpty()) {
            // All waiting out a server-error backoff: come back when the first is due.
            if (retryTask?.isDone != false) {
                val due = missing.minOf { bufs[it].retryAt } - now
                retryTask = sched.schedule({ lock.withLock { pumpLocked() } }, due.coerceAtLeast(50), TimeUnit.MILLISECONDS)
            }
            return
        }
        idleTask?.cancel(false); idleTask = null
        val sock = ws
        if (sock == null) {
            if (reconnectTask == null) connectLocked()
            return
        }
        if (!wsOpen) return // onOpen pumps again
        for (i in want) {
            if (streams.size >= SonioxTts.MAX_IN_FLIGHT) break
            startStreamLocked(sock, i)
        }
    }

    private fun startStreamLocked(sock: WebSocket, i: Int) {
        val b = bufs[i]
        // Partial audio left by a broken stream: if the player is inside this
        // segment, keep it playing and collect the new copy on the side; else
        // just start over.
        if (!b.done) {
            if (i == cur && pos > 0 && b.size > 0) {
                b.pending = SegBuf()
            } else {
                // Also drop a half-filled earlier re-download, or the new copy
                // would be appended after it and the paragraph start repeat.
                b.data = ByteArray(0); b.size = 0; b.pending = null
            }
        }
        val id = "s$i-${++streamSeq}"
        streams[id] = i
        val cfg = JSONObject().apply {
            put("stream_id", id)
            put("model", SonioxTts.MODEL)
            put("language", "cs")
            put("voice", voice.ifBlank { "Adrian" })
            put("audio_format", "pcm_mulaw")
            put("sample_rate", SonioxTts.SAMPLE_RATE)
        }
        sock.send(cfg.toString())
        sock.send(JSONObject().put("stream_id", id).put("text", segments[i]).put("text_end", true).toString())
    }

    private fun connectLocked() {
        wsOpen = false
        ws = SonioxTts.http().newWebSocket(sonioxWsRequest(SonioxTts.WS_URL, apiKey), Listener())
    }

    private fun scheduleIdleCloseLocked() {
        if (idleTask != null || ws == null) return
        idleTask = sched.schedule({
            lock.withLock {
                idleTask = null
                if (streams.isEmpty()) {
                    ws?.close(1000, null)
                    ws = null
                    wsOpen = false
                }
            }
        }, SonioxTts.IDLE_CLOSE_MS, TimeUnit.MILLISECONDS)
    }

    /** The socket died: requeue its streams and retry with backoff. */
    private fun connectionLostLocked(why: String) {
        ws = null
        wsOpen = false
        streams.clear()
        if (stopped) return
        val now = System.currentTimeMillis()
        if (failingSince == 0L) failingSince = now
        if (now - failingSince > SonioxTts.GIVE_UP_MS) {
            haltLocked("přerušeno (bez signálu)")
            return
        }
        backoffMs = if (backoffMs == 0L) 1000L else minOf(backoffMs * 2, 15_000L)
        FileLogger.log(tag, "connection lost ($why) — retry in ${backoffMs} ms")
        scheduleReconnectLocked(backoffMs)
        publishLocked()
    }

    private fun scheduleReconnectLocked(delayMs: Long) {
        reconnectTask?.cancel(false)
        reconnectTask = sched.schedule({
            lock.withLock {
                reconnectTask = null
                if (!stopped && ws == null) pumpLocked()
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Stop downloading. Playback goes on through what is already in memory;
     * the player marks itself interrupted only when it actually runs out.
     */
    private fun haltLocked(note: String) {
        FileLogger.log(tag, "downloads halted at segment ${cur + 1}/${bufs.size}: $note")
        haltNote = note
        reconnectTask?.cancel(false); reconnectTask = null
        ws?.close(1000, null); ws = null; wsOpen = false
        streams.clear()
        changed.signalAll()
        publishLocked()
    }

    private inner class Listener : WebSocketListener() {
        private fun mine(webSocket: WebSocket) = ws === webSocket && !stopped

        override fun onOpen(webSocket: WebSocket, response: Response) = lock.withLock {
            if (!mine(webSocket)) return@withLock
            wsOpen = true
            // failingSince/backoff reset only on real audio: a socket that opens
            // and then errors must not loop at a 1 s cadence forever.
            pumpLocked()
            publishLocked()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
            // Decode outside the lock — base64 of a big chunk is the heavy part.
            val b64 = obj.optString("audio")
            val audio = if (b64.isNotBlank()) runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() else null
            lock.withLock {
                if (!mine(webSocket)) return@withLock
                val id = obj.optString("stream_id")
                val seg = streams[id]
                val code = obj.opt("error_code")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                if (code != null) {
                    handleErrorLocked(id, seg, code, obj)
                    return@withLock
                }
                if (seg == null) return@withLock // a stream we already gave up on
                val b = bufs[seg]
                val target = b.pending ?: b
                if (audio != null && audio.isNotEmpty()) {
                    target.append(audio)
                    failingSince = 0L
                    backoffMs = 0L
                }
                if (obj.optBoolean("audio_end") || obj.optBoolean("terminated")) {
                    target.done = true
                    b.serverErrors = 0
                    b.rateLimited = 0
                    streams.remove(id)
                    pumpLocked()
                }
                changed.signalAll()
            }
        }

        // Server-initiated close arrives here first; onClosed only follows our
        // own close, so without this the session sat on a dead socket until
        // the next ping failed.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(1000, null) }
            closedLocked(webSocket, "closing $code $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            closedLocked(webSocket, "closed $code $reason")

        private fun closedLocked(webSocket: WebSocket, why: String) = lock.withLock {
            if (!mine(webSocket)) return@withLock
            if (wsOpen && streams.isEmpty()) {
                // Idle socket closed: not a failure, reconnect on demand.
                ws = null
                wsOpen = false
                pumpLocked()
            } else {
                connectionLostLocked(why)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lock.withLock {
            if (!mine(webSocket)) return@withLock
            when {
                response?.code == 401 || response?.code == 403 ->
                    fatalLocked("Soniox odmítl API klíč (${response.code})")
                // Streams start only in onOpen, so a socket still handshaking always
                // has none — that case must back off, or it reconnects in a hot loop.
                wsOpen && streams.isEmpty() -> {
                    // Idle open socket died (network switch): nothing was lost.
                    ws = null
                    wsOpen = false
                    pumpLocked()
                }
                else -> connectionLostLocked(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun handleErrorLocked(id: String, seg: Int?, code: String, obj: JSONObject) {
        val type = obj.optString("error_type")
        val msg = obj.optString("error_message").ifBlank { type.ifBlank { code } }
        FileLogger.log(tag, "server error $code/$type on '$id' (segment ${seg?.plus(1)}): $msg")
        if (seg == null) {
            // Connection-level (no / unknown stream_id).
            when {
                code == "401" || code == "403" -> fatalLocked("Soniox odmítl API klíč ($code)")
                code == "400" || code == "402" -> fatalLocked("Soniox TTS: $msg")
                id.isEmpty() -> {
                    // Counts toward giving up like a dropped socket: onOpen no
                    // longer resets the failure clock, only real audio does.
                    val sock = ws
                    connectionLostLocked("server $code")
                    sock?.close(1000, null)
                }
            }
            return
        }
        streams.remove(id)
        val b = bufs[seg]
        val target = b.pending ?: b
        when {
            code == "413" || type == "max_audio_duration_reached" -> {
                // Segment longer than 2 min of speech (should not happen with
                // ttsSegments' limits): keep what came, move on.
                target.done = true
            }
            code == "400" || code == "401" || code == "402" || code == "403" ->
                fatalLocked("Soniox TTS: $msg")
            else -> {
                // 408 / 429 / 5xx: retry this segment after a growing pause.
                // 429 is often the account's stream cap still counting a dead
                // socket's streams, so it does not use up the attempts.
                if (code == "429") b.rateLimited++ else b.serverErrors++
                if (b.serverErrors >= SonioxTts.MAX_STREAM_ATTEMPTS || b.rateLimited >= 20) {
                    haltLocked("chyba serveru ($code)")
                } else {
                    val delay = if (code == "429") 3000L else 1000L * b.serverErrors
                    b.retryAt = System.currentTimeMillis() + delay
                    pumpLocked() // schedules itself for the retry time
                }
            }
        }
        changed.signalAll()
    }

    private fun fatalLocked(msg: String) {
        onError?.let { cb -> postOnMain { cb(msg) } }
        // Leave the lock before tearing down (teardown posts to main and joins nothing).
        sched.execute { stop() }
    }

    // ── state publishing (bar + media session) ─────────────────────────────

    private fun stateLocked(): TtsPlayerState {
        val b = bufs[cur]
        val phase = when {
            interrupted != null -> TtsPhase.INTERRUPTED
            paused -> TtsPhase.PAUSED
            pos < b.size || b.done -> TtsPhase.PLAYING
            failingSince != 0L || (ws == null && reconnectTask != null) -> TtsPhase.WAITING_NETWORK
            else -> TtsPhase.BUFFERING
        }
        val preview = segments[cur].replace('\n', ' ').take(120)
        return TtsPlayerState(phase, cur, segments.size, preview, interrupted)
    }

    private fun publishLocked() {
        if (stopped) return
        val s = stateLocked()
        if (s == lastPublished) return
        lastPublished = s
        TtsPlayback.publish(this, s)
        postOnMain { updateMediaControls(s) }
    }

    // ── platform glue: focus, network, media session, notification ────────

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            // A call or a navigation prompt: pause, and come back when it ends.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> lock.withLock {
                if (!stopped && !paused) {
                    paused = true
                    resumeOnFocusGain = true
                    track?.let { runCatching { it.pause() } }
                    changed.signalAll()
                    publishLocked()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> lock.withLock { if (resumeOnFocusGain) resumeLocked() }
        }
    }

    /** @return false when the system refused focus (e.g. during a call). */
    private fun requestFocus(): Boolean {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ttsAttributes)
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = req
        val res = runCatching { audioManager?.requestAudioFocus(req) }.getOrNull()
        if (res == AudioManager.AUDIOFOCUS_REQUEST_FAILED) FileLogger.log(tag, "audio focus refused")
        return res != AudioManager.AUDIOFOCUS_REQUEST_FAILED
    }

    private fun watchNetwork() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Signal is back: skip the rest of the backoff.
                lock.withLock {
                    if (!stopped && interrupted == null && ws == null && reconnectTask != null) {
                        scheduleReconnectLocked(300)
                    }
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { netCallback = cb }
    }

    private fun setUpMediaControls() {
        if (lock.withLock { stopped }) return
        val ms = MediaSession(ctx, "ClaudeRemoteReadAloud")
        ms.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = play()
            override fun onPause() = pause()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = previous()
            override fun onRewind() = rewind(10)
            override fun onFastForward() = next()
            override fun onStop() = stop()
            override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                when (action) {
                    CUSTOM_REWIND -> rewind(10)
                    CUSTOM_STOP -> stop()
                }
            }
        })
        ms.isActive = true
        media = ms

        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    ACTION_PREV -> previous()
                    ACTION_REWIND -> rewind(10)
                    ACTION_TOGGLE -> if (lock.withLock { paused || interrupted != null }) play() else pause()
                    ACTION_NEXT -> next()
                    ACTION_STOP -> stop()
                }
            }
        }
        val filter = IntentFilter().apply {
            listOf(ACTION_PREV, ACTION_REWIND, ACTION_TOGGLE, ACTION_NEXT, ACTION_STOP).forEach { addAction(it) }
        }
        ContextCompat.registerReceiver(ctx, rcv, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = rcv
        lock.withLock { lastPublished = null; publishLocked() }
    }

    private fun updateMediaControls(s: TtsPlayerState) {
        val ms = media ?: return
        if (lock.withLock { stopped }) return
        val pbState = when (s.phase) {
            TtsPhase.PLAYING -> PlaybackState.STATE_PLAYING
            TtsPhase.BUFFERING, TtsPhase.WAITING_NETWORK -> PlaybackState.STATE_BUFFERING
            TtsPhase.PAUSED -> PlaybackState.STATE_PAUSED
            TtsPhase.INTERRUPTED -> PlaybackState.STATE_PAUSED
        }
        ms.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_REWIND or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_STOP,
                )
                .setState(pbState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, rate)
                // Android 13+ builds its media controls from these, not from
                // the notification's buttons — so −10 s and Konec go here too.
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(CUSTOM_REWIND, "Zpět o 10 s", android.R.drawable.ic_media_rew).build(),
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(CUSTOM_STOP, "Konec", android.R.drawable.ic_menu_close_clear_cancel).build(),
                )
                .build(),
        )
        val title = "Claude · odstavec ${s.segment + 1}/${s.segmentCount}"
        ms.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s.preview)
                .build(),
        )
        postNotification(ms, s, title)
    }

    @SuppressLint("MissingPermission") // POST_NOTIFICATIONS is requested by the app; without it notify() is a no-op
    private fun postNotification(ms: MediaSession, s: TtsPlayerState, title: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Předčítání", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ovládání předčítání zpráv (pauza, odstavce)"
                    setShowBadge(false)
                },
            )
        }
        fun pi(action: String, req: Int) = PendingIntent.getBroadcast(
            ctx, req, Intent(action).setPackage(ctx.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val playing = s.phase == TtsPhase.PLAYING || s.phase == TtsPhase.BUFFERING ||
            s.phase == TtsPhase.WAITING_NETWORK
        val status = when (s.phase) {
            TtsPhase.WAITING_NETWORK -> "Čekám na signál… · "
            TtsPhase.INTERRUPTED -> "${s.note ?: "Přerušeno"} · "
            else -> ""
        }
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 7310, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        @Suppress("DEPRECATION")
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(title)
            .setContentText(status + s.preview)
            .setContentIntent(open)
            .setDeleteIntent(pi(ACTION_STOP, 7315))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Předchozí", pi(ACTION_PREV, 7311)).build())
            .addAction(
                Notification.Action.Builder(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pauza" else "Přehrát",
                    pi(ACTION_TOGGLE, 7312),
                ).build(),
            )
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Další", pi(ACTION_NEXT, 7313)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Konec", pi(ACTION_STOP, 7314)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(ms.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }

    private fun teardown() {
        reconnectTask?.cancel(false)
        idleTask?.cancel(false)
        lock.withLock {
            ws?.close(1000, null)
            ws = null
            streams.clear()
        }
        sched.shutdownNow()
        // Silence now; the player thread releases the track as it exits.
        track?.let { t -> runCatching { t.pause(); t.flush(); t.stop() } }
        focusRequest?.let { req -> runCatching { audioManager?.abandonAudioFocusRequest(req) } }
        focusRequest = null
        netCallback?.let { cb ->
            runCatching { ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        netCallback = null
        TtsPlayback.detach(this)
        SonioxTts.ended(this)
        postOnMain {
            receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
            receiver = null
            media?.let { runCatching { it.isActive = false; it.release() } }
            media = null
            // A newer session may already own the notification id; it attaches
            // before posting, so checking here on main is race-free.
            if (TtsPlayback.controller == null) runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
            onFinish()
        }
    }

    private companion object {
        const val CHANNEL = "tts_player"
        const val NOTIFICATION_ID = 7300
        const val ACTION_PREV = "com.clauderemote.tts.PREV"
        const val ACTION_REWIND = "com.clauderemote.tts.REWIND"
        const val ACTION_TOGGLE = "com.clauderemote.tts.TOGGLE"
        const val ACTION_NEXT = "com.clauderemote.tts.NEXT"
        const val ACTION_STOP = "com.clauderemote.tts.STOP"
        const val CUSTOM_REWIND = "rewind10"
        const val CUSTOM_STOP = "stop"

        val ttsAttributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
