package com.clauderemote.voice

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
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/*
 * Platform-neutral core of the Soniox read-aloud player, shared by Android
 * (AudioTrack + MediaSession) and desktop (javax.sound SourceDataLine).
 *
 * What lives here: the WebSocket protocol (one socket, `stream_id` per
 * segment, at most [SonioxTtsTuning.maxInFlight] streams), the per-segment
 * μ-law buffers with re-download and rejoin, reconnect backoff and giving up,
 * and the player state machine (play/pause/previous/next/−10 s/stop) behind
 * [TtsPlayerController]. What a platform supplies is small:
 *  - [TtsAudioSink]: a blocking PCM16 output (AudioTrack, SourceDataLine);
 *  - [SonioxTtsHost]: audio focus, media controls, network watching, the UI
 *    thread — every method has a no-op default, desktop overrides none but post;
 *  - [TtsSocketFactory]: the socket, OkHttp in production ([sonioxOkHttpSockets]),
 *    a fake in tests.
 *
 * The behaviour is the Android player's from 2026-11-05 (commit 9a8af49) moved
 * here unchanged; each rule's reason is in SonioxTts.kt's header and below.
 */

/** WebSocket upgrade request carrying the Soniox key the current way (header). */
internal fun sonioxWsRequest(url: String, apiKey: String): Request =
    Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build()

const val SONIOX_TTS_WS_URL = "wss://tts-rt.soniox.com/tts-websocket"
const val SONIOX_TTS_MODEL = "tts-rt-v2"
/** μ-law is only allowed at 8000 Hz. */
const val SONIOX_TTS_SAMPLE_RATE = 8000

/**
 * Soniox's own `speed` field hard-rejects anything outside 0.7–1.3 with a 400
 * (confirmed live, commit 3f13512). Android stretches on the device instead;
 * a platform that sends `speed` must clamp into this range.
 */
fun sonioxStreamSpeed(rate: Float): Double? {
    val r = rate.coerceIn(0.7f, 1.3f)
    return if (r == 1.0f) null else Math.round(r * 100) / 100.0
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

/** μ-law bytes → little-endian PCM16, by table lookup (no codec — MediaCodec used to stall the stream). */
internal fun mulawToPcm16(src: ByteArray, len: Int = src.size): ByteArray {
    val out = ByteArray(len * 2)
    var j = 0
    for (i in 0 until len) {
        val s = MULAW_TABLE[src[i].toInt() and 0xFF].toInt()
        out[j++] = (s and 0xFF).toByte()
        out[j++] = ((s shr 8) and 0xFF).toByte()
    }
    return out
}

/**
 * Blocking PCM16 mono little-endian output.
 *
 * Threads: [open], [write] and [release] run on the session's player thread
 * only. [play], [pause] and [flush] run on the player thread AND on control
 * threads (bar, media buttons, focus changes) — while a [write] may be
 * blocked — which is how AudioTrack and SourceDataLine are meant to be driven.
 * [halt] runs on whichever thread stops the session.
 */
interface TtsAudioSink {
    /** Opens the output at [sampleRate]; returns its buffer size in bytes (the drain tail). */
    fun open(sampleRate: Int): Int
    /**
     * Writes up to [len] bytes, blocking. Must return a SHORT count (possibly 0)
     * when the output is paused or flushed meanwhile — the session waits for
     * resume and writes the rest. Negative = the output failed.
     */
    fun write(pcm: ByteArray, off: Int, len: Int): Int
    fun play()
    fun pause()
    /** Drops what is queued but not yet heard (a seek). */
    fun flush()
    /** Silence now, from any thread (stop pressed). */
    fun halt()
    /** Release; the player thread calls it once as it exits. */
    fun release()
    /** For the log: what plays and at what speed (Android stretches on the device). */
    fun describe(): String = javaClass.simpleName
}

/** Platform glue around a session. Every hook has a harmless default. */
interface SonioxTtsHost {
    /** Asks for the audio output; false (a phone call is on) starts the session paused. */
    fun requestFocus(): Boolean = true
    /** Once, right after the session attached to [TtsPlayback]: media controls, network watch. */
    fun onStart(session: SonioxTtsSession) {}
    /** Each new state. Called with the session lock held — post, do not block. */
    fun onState(state: TtsPlayerState) {}
    /**
     * The session ended and detached. Release platform resources, then run
     * [finish] (the caller's onFinish) on the UI thread.
     */
    fun onEnd(finish: () -> Unit) { post(finish) }
    /** Runs [action] on the thread callers expect callbacks on. */
    fun post(action: () -> Unit) { action() }
}

/** The parts of a WebSocket the session uses — the seam for the fake in tests. */
interface TtsSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String?)
}

/** Socket events. [httpCode] is the failed upgrade's status (401/403 = bad key). */
interface TtsSocketListener {
    fun onOpen(socket: TtsSocket)
    fun onMessage(socket: TtsSocket, text: String)
    /** Server-initiated close; [onClosed] only follows our own close. */
    fun onClosing(socket: TtsSocket, code: Int, reason: String)
    fun onClosed(socket: TtsSocket, code: Int, reason: String)
    fun onFailure(socket: TtsSocket, error: Throwable, httpCode: Int?)
}

fun interface TtsSocketFactory {
    fun connect(listener: TtsSocketListener): TtsSocket
}

/** Pings let OkHttp notice a dead socket (car tunnel) in seconds rather than waiting for TCP. */
internal val sonioxTtsHttp: OkHttpClient by lazy {
    OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
}

/** Production sockets: OkHttp to Soniox with the key in the upgrade header. */
fun sonioxOkHttpSockets(apiKey: String, http: OkHttpClient = sonioxTtsHttp, url: String = SONIOX_TTS_WS_URL) =
    TtsSocketFactory { l ->
        val adapter = OkHttpTtsSocket()
        val ws = http.newWebSocket(
            sonioxWsRequest(url, apiKey),
            object : WebSocketListener() {
                // Every callback re-binds first: OkHttp may call back before
                // newWebSocket has returned the handle.
                private fun bind(w: WebSocket) = adapter.also { it.ws = w }
                override fun onOpen(webSocket: WebSocket, response: Response) = l.onOpen(bind(webSocket))
                override fun onMessage(webSocket: WebSocket, text: String) = l.onMessage(bind(webSocket), text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) =
                    l.onClosing(bind(webSocket), code, reason)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    l.onClosed(bind(webSocket), code, reason)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    l.onFailure(bind(webSocket), t, response?.code)
            },
        )
        adapter.ws = ws
        adapter
    }

private class OkHttpTtsSocket : TtsSocket {
    @Volatile var ws: WebSocket? = null
    override fun send(text: String) = ws?.send(text) ?: false
    override fun close(code: Int, reason: String?) { ws?.close(code, reason) }
}

/** Timing and limits. Defaults are the phone's production values; tests shrink them. */
data class SonioxTtsTuning(
    /** 200 ms per sink write. */
    val chunkSamples: Int = 1600,
    /** Concurrent streams (account cap 3). */
    val maxInFlight: Int = 2,
    /** A re-download is joined this far before the point the old copy ran out. */
    val rejoinOverlapSamples: Int = 3 * SONIOX_TTS_SAMPLE_RATE,
    /** No audio for this long while the connection keeps failing = give up (player pauses there). */
    val giveUpMs: Long = 5 * 60_000L,
    val maxStreamAttempts: Int = 5,
    /** The server drops idle sockets at ~40 s. */
    val idleCloseMs: Long = 25_000L,
    val firstBackoffMs: Long = 1000L,
    val maxBackoffMs: Long = 15_000L,
    val rateLimitRetryMs: Long = 3000L,
    val serverErrorStepMs: Long = 1000L,
    /** Pause after the drain before tearing down, so the last word is heard. */
    val finishSettleMs: Long = 150L,
    /**
     * Streams in flight but not one message on the socket for this long = the
     * streams are stuck (pings still answered, so OkHttp sees no failure).
     * Socket-wide on purpose: should Soniox queue the second stream behind
     * the first, the first keeps the socket busy, so only a real stall goes
     * quiet. 30 s is far above Soniox's pacing — it sends audio chunks well
     * under a second apart and synthesises faster than real time.
     */
    val streamStallMs: Long = 30_000L,
    val stallCheckMs: Long = 5_000L,
)

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

/**
 * One read-aloud: [segments] (from [ttsSegments]) downloaded ahead into memory
 * and played through [sink]. Thread model: one player thread writes to the
 * sink, one scheduler thread runs timers, socket callbacks arrive on the
 * socket's thread; all state is under one lock.
 *
 * [onFallback], when set, replaces giving up: a refused key, a fatal server
 * error, no audio at all within [fallbackAfterMs], or a reading that ran out
 * because downloads gave up — each reports the reason and the first segment
 * not heard, then the session stops. Desktop uses it to finish with the OS
 * synthesiser; Android passes null and keeps its pause-at-the-gap behaviour.
 */
class SonioxTtsSession(
    private val segments: List<String>,
    private val voice: String,
    private val sink: TtsAudioSink,
    private val sockets: TtsSocketFactory,
    private val host: SonioxTtsHost = object : SonioxTtsHost {},
    private val onFinish: () -> Unit = {},
    private val onError: ((String) -> Unit)? = null,
    /** Soniox `speed` (already clamped, see [sonioxStreamSpeed]); null = do not send. */
    private val streamSpeed: Double? = null,
    private val onFallback: ((reason: String, fromSegment: Int) -> Unit)? = null,
    private val fallbackAfterMs: Long = 8_000L,
    private val tuning: SonioxTtsTuning = SonioxTtsTuning(),
    private val tag: String = "SonioxTts",
    private val clock: () -> Long = System::currentTimeMillis,
) : TtsPlayerController {
    init {
        require(segments.isNotEmpty()) { "nothing to read" }
    }

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
    /** Downloads gave up (no signal for giveUpMs, or a segment kept failing). */
    private var haltNote: String? = null
    private var cur = 0
    private var pos = 0
    private var seekEpoch = 0
    private var ws: TtsSocket? = null
    private var wsOpen = false
    private var streamSeq = 0
    private val streams = HashMap<String, Int>()           // stream_id → segment
    private var failingSince = 0L                           // 0 = connection healthy
    private var backoffMs = 0L
    private var reconnectTask: ScheduledFuture<*>? = null
    private var idleTask: ScheduledFuture<*>? = null
    private var retryTask: ScheduledFuture<*>? = null
    private var lastPublished: TtsPlayerState? = null
    private var everAudio = false
    private var fellBack = false
    /** Last message (or open, or stream start) on the socket — for [streamStallMs]. */
    private var lastActivity = 0L
    /** A fatal error is being reported; nothing more is downloaded. */
    private var fatal = false

    /** Set once the player thread opened the sink; control calls skip it before that. */
    @Volatile private var sinkOpen = false
    private var player: Thread? = null

    fun start() {
        lock.withLock { if (stopped) return }
        // Focus refused (a phone call is on): start paused instead of talking over it.
        val granted = host.requestFocus()
        lock.withLock {
            if (stopped) return
            if (!granted) paused = true
            TtsPlayback.attach(this, stateLocked())
        }
        host.onStart(this)
        if (onFallback != null) {
            sched.schedule({
                lock.withLock {
                    if (!stopped && !everAudio) fallbackLocked("Soniox neposlal zvuk do ${fallbackAfterMs / 1000} s", 0)
                }
            }, fallbackAfterMs, TimeUnit.MILLISECONDS)
        }
        sched.scheduleWithFixedDelay(
            { lock.withLock { checkStallLocked() } },
            tuning.stallCheckMs, tuning.stallCheckMs, TimeUnit.MILLISECONDS,
        )
        // Downloads start only once the player has opened the output (it
        // pumps right after): without an audio device no stream is bought.
        player = Thread({ playLoop() }, "soniox-tts-play").apply { start() }
    }

    // ── controller (UI bar, notification, media buttons) ───────────────────

    override fun play() {
        if (lock.withLock { stopped }) return
        // Re-request every time: a permanent loss (another player started)
        // took it away, and asking again while holding it is harmless.
        host.requestFocus()
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
        if (sinkOpen) runCatching { sink.play() }
        pumpLocked()
        changed.signalAll()
        publishLocked()
    }

    override fun pause() = lock.withLock {
        if (stopped) return@withLock
        resumeOnFocusGain = false // the user's pause wins over a call ending
        paused = true
        if (sinkOpen) runCatching { sink.pause() }
        changed.signalAll()
        publishLocked()
    }

    /** Media-button / notification toggle. */
    fun toggle() {
        if (lock.withLock { paused || interrupted != null }) play() else pause()
    }

    /**
     * A hardware play/pause press (headset, car). Offered to
     * [SonioxTtsPlayer.onMediaButton] first; when that consumes it, the
     * reading is left alone, else it toggles as before. Focus changes
     * ([transientPause]) and the bar/notification never come through here —
     * that is the point: a caller can tell "the button was pressed" from
     * "something else paused the reading".
     */
    fun mediaButton() {
        val hook = SonioxTtsPlayer.onMediaButton
        if (hook != null && runCatching { hook() }.getOrDefault(false)) return
        toggle()
    }

    override fun previous() = lock.withLock {
        if (stopped) return@withLock
        // Like a music player: a few seconds in, ⏮ restarts this paragraph.
        if (pos < 3 * SONIOX_TTS_SAMPLE_RATE && cur > 0) cur--
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
        var p = pos - seconds * SONIOX_TTS_SAMPLE_RATE
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

    /** A call or a navigation prompt took the output: pause, and resume when it ends. */
    fun transientPause() = lock.withLock {
        if (!stopped && !paused) {
            paused = true
            resumeOnFocusGain = true
            if (sinkOpen) runCatching { sink.pause() }
            changed.signalAll()
            publishLocked()
        }
    }

    /** The output came back after [transientPause]. */
    fun transientResume() = lock.withLock { if (resumeOnFocusGain) resumeLocked() }

    /** Signal is back: skip the rest of the reconnect backoff. */
    fun networkAvailable() = lock.withLock {
        if (!stopped && interrupted == null && ws == null && reconnectTask != null) {
            scheduleReconnectLocked(300)
        }
    }

    /** Publishes the current state again, e.g. once media controls exist to show it. */
    fun republish() = lock.withLock { lastPublished = null; publishLocked() }

    fun isPausedOrInterrupted(): Boolean = lock.withLock { paused || interrupted != null }

    private fun seekLocked() {
        seekEpoch++
        if (sinkOpen) runCatching { sink.pause(); sink.flush(); if (!paused) sink.play() }
        pumpLocked()
        changed.signalAll()
        publishLocked()
    }

    // ── playback thread ────────────────────────────────────────────────────

    private fun playLoop() {
        var opened = false
        try {
            val bufferBytes = sink.open(SONIOX_TTS_SAMPLE_RATE)
            opened = true
            sinkOpen = true
            lock.withLock {
                if (!paused) runCatching { sink.play() }
                pumpLocked()
            }
            // MODE_STREAM clips the very first buffer while it warms up.
            // Written whole like any audio: a short count (started paused)
            // must not drop part of it or leave a half frame.
            val lead = ByteArray(SONIOX_TTS_SAMPLE_RATE / 5)
            if (!writeFully(lead, lock.withLock { seekEpoch })) return
            FileLogger.log(tag, "playback started (Soniox speed=${streamSpeed ?: 1.0}, output ${sink.describe()}, ${segments.size} segments)")
            val chunk = ByteArray(tuning.chunkSamples)
            val tail = ByteArray(maxOf(bufferBytes, SONIOX_TTS_SAMPLE_RATE))
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
                                pos = (pos - tuning.rejoinOverlapSamples).coerceAtLeast(0)
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
                                if (onFallback != null) fallbackLocked(note, cur)
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
                    if (!writeFully(tail, epoch)) return
                    if (lock.withLock { epoch == seekEpoch }) break
                    continue
                }
                if (!writeFully(mulawToPcm16(chunk, n), epoch)) return
                lock.withLock {
                    // A seek during the blocking write let up to one stale chunk in.
                    if (epoch != seekEpoch && !stopped) {
                        runCatching { sink.pause(); sink.flush(); if (!paused) sink.play() }
                    }
                }
            }
            Thread.sleep(tuning.finishSettleMs)
            FileLogger.log(tag, "finished all ${segments.size} segments")
            stop()
        } catch (_: InterruptedException) {
            // stop() interrupts us; teardown already ran.
        } catch (e: Throwable) {
            FileLogger.log(tag, "player crashed: ${e.message}")
            if (onFallback != null) {
                lock.withLock { fallbackLocked("přehrávání selhalo: ${e.message}", cur) }
            } else {
                onError?.let { cb -> host.post { cb("Soniox TTS: ${e.message}") } }
            }
            stop()
        } finally {
            // Only this thread writes to the sink, so only it releases it —
            // releasing from teardown could race a write still in progress.
            sinkOpen = false
            if (opened) runCatching { sink.release() }
        }
    }

    /**
     * Blocking write of the whole buffer. A pause makes the sink return a
     * short count; the remainder is written after resume instead of being
     * dropped (that clipped a syllable on every pause). A seek discards it.
     * Returns false once the session is stopped.
     */
    private fun writeFully(pcm: ByteArray, epoch: Int): Boolean {
        var off = 0
        while (off < pcm.size) {
            val w = sink.write(pcm, off, pcm.size - off)
            if (w < 0) throw IllegalStateException("audio write failed ($w)")
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

    // ── download side ──────────────────────────────────────────────────────

    /** Start streams for the next segments that still need audio. */
    private fun pumpLocked() {
        if (stopped || fatal || haltNote != null || !sinkOpen) return
        val now = clock()
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
            if (streams.size >= tuning.maxInFlight) break
            startStreamLocked(sock, i)
        }
    }

    private fun startStreamLocked(sock: TtsSocket, i: Int) {
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
        lastActivity = clock()
        sock.send(sonioxStreamConfig(id, voice, streamSpeed).toString())
        sock.send(JSONObject().put("stream_id", id).put("text", segments[i]).put("text_end", true).toString())
    }

    private fun connectLocked() {
        wsOpen = false
        // Assigned under the lock: every listener callback takes it before
        // checking `mine`, so none can see the socket half-registered.
        ws = try {
            sockets.connect(Listener())
        } catch (e: Exception) {
            // OkHttp refuses a header value with a control or non-ASCII char
            // (a key pasted with a zero-width space) before any I/O. Retrying
            // cannot help; it is a bad key.
            FileLogger.log(tag, "cannot open the socket: ${e.message}")
            null
        }
        if (ws == null) fatalLocked("Soniox API klíč nelze použít (neplatné znaky)")
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
        }, tuning.idleCloseMs, TimeUnit.MILLISECONDS)
    }

    /** The socket died: requeue its streams and retry with backoff. */
    private fun connectionLostLocked(why: String) {
        ws = null
        wsOpen = false
        streams.clear()
        if (stopped) return
        val now = clock()
        if (failingSince == 0L) failingSince = now
        if (now - failingSince > tuning.giveUpMs) {
            haltLocked("přerušeno (bez signálu)")
            return
        }
        backoffMs = if (backoffMs == 0L) tuning.firstBackoffMs else minOf(backoffMs * 2, tuning.maxBackoffMs)
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
     * Streams in flight and a silent socket for [SonioxTtsTuning.streamStallMs]:
     * count it as a server error on each stuck segment (bounded by
     * maxStreamAttempts like a 5xx, so a stall that keeps coming back ends in
     * a halt instead of looping), and drop the socket so the server frees its
     * stream slots. The retry reconnects when the backoff is due.
     */
    private fun checkStallLocked() {
        if (stopped || !wsOpen || streams.isEmpty()) return
        val now = clock()
        if (now - lastActivity < tuning.streamStallMs) return
        val stuck = streams.values.toSet()
        FileLogger.log(tag, "no data for ${now - lastActivity} ms on ${streams.size} stream(s) — retrying segment(s) ${stuck.map { it + 1 }}")
        var exhausted = false
        for (seg in stuck) {
            val b = bufs[seg]
            b.serverErrors++
            b.retryAt = now + tuning.serverErrorStepMs * b.serverErrors
            if (b.serverErrors >= tuning.maxStreamAttempts) exhausted = true
        }
        val sock = ws
        ws = null
        wsOpen = false
        streams.clear()
        sock?.close(1000, null)
        if (exhausted) haltLocked("stream bez dat") else pumpLocked()
        changed.signalAll()
        publishLocked()
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

    private inner class Listener : TtsSocketListener {
        private fun mine(socket: TtsSocket) = ws === socket && !stopped

        override fun onOpen(socket: TtsSocket) = lock.withLock {
            if (!mine(socket)) return@withLock
            wsOpen = true
            lastActivity = clock()
            // failingSince/backoff reset only on real audio: a socket that opens
            // and then errors must not loop at a 1 s cadence forever.
            pumpLocked()
            publishLocked()
        }

        override fun onMessage(socket: TtsSocket, text: String) {
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
            // Decode outside the lock — base64 of a big chunk is the heavy part.
            // The MIME decoder skips line breaks like android.util.Base64.DEFAULT did.
            val b64 = obj.optString("audio")
            val audio = if (b64.isNotBlank()) runCatching { java.util.Base64.getMimeDecoder().decode(b64) }.getOrNull() else null
            lock.withLock {
                if (!mine(socket)) return@withLock
                lastActivity = clock()
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
                    everAudio = true
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
        override fun onClosing(socket: TtsSocket, code: Int, reason: String) {
            runCatching { socket.close(1000, null) }
            closedLocked(socket, "closing $code $reason")
        }

        override fun onClosed(socket: TtsSocket, code: Int, reason: String) =
            closedLocked(socket, "closed $code $reason")

        private fun closedLocked(socket: TtsSocket, why: String) = lock.withLock {
            if (!mine(socket)) return@withLock
            if (wsOpen && streams.isEmpty()) {
                // Idle socket closed: not a failure, reconnect on demand.
                ws = null
                wsOpen = false
                pumpLocked()
            } else {
                connectionLostLocked(why)
            }
        }

        override fun onFailure(socket: TtsSocket, error: Throwable, httpCode: Int?) = lock.withLock {
            if (!mine(socket)) return@withLock
            when {
                httpCode == 401 || httpCode == 403 ->
                    fatalLocked("Soniox odmítl API klíč ($httpCode)")
                // Streams start only in onOpen, so a socket still handshaking always
                // has none — that case must back off, or it reconnects in a hot loop.
                wsOpen && streams.isEmpty() -> {
                    // Idle open socket died (network switch): nothing was lost.
                    ws = null
                    wsOpen = false
                    pumpLocked()
                }
                else -> connectionLostLocked(error.message ?: error.javaClass.simpleName)
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
                if (b.serverErrors >= tuning.maxStreamAttempts || b.rateLimited >= 20) {
                    haltLocked("chyba serveru ($code)")
                } else {
                    val delay = if (code == "429") tuning.rateLimitRetryMs else tuning.serverErrorStepMs * b.serverErrors
                    b.retryAt = clock() + delay
                    pumpLocked() // schedules itself for the retry time
                }
            }
        }
        changed.signalAll()
    }

    private fun fatalLocked(msg: String) {
        if (fatal) return
        fatal = true
        if (onFallback != null) {
            fallbackLocked(msg, cur)
            return
        }
        onError?.let { cb -> host.post { cb(msg) } }
        // Leave the lock before tearing down (teardown posts to main and joins nothing).
        sched.execute { stop() }
    }

    /** Hand the rest to the caller's fallback, once, and end this session. */
    private fun fallbackLocked(reason: String, fromSegment: Int) {
        if (fellBack || stopped) return
        fellBack = true
        FileLogger.log(tag, "falling back at segment ${fromSegment + 1}/${bufs.size}: $reason")
        val cb = onFallback ?: return
        // Reported before stop() so the caller knows, when onFinish runs, that
        // this end was a hand-over and not the reading finishing.
        cb(reason, fromSegment)
        runCatching { sched.execute { stop() } }.onFailure { Thread { stop() }.start() }
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
        host.onState(s)
    }

    /** Current state, for tests and platform glue. */
    fun state(): TtsPlayerState = lock.withLock { stateLocked() }

    private fun teardown() {
        lock.withLock {
            reconnectTask?.cancel(false)
            idleTask?.cancel(false)
            ws?.close(1000, null)
            ws = null
            streams.clear()
        }
        sched.shutdownNow()
        // Silence now; the player thread releases the sink as it exits.
        if (sinkOpen) runCatching { sink.halt() }
        TtsPlayback.detach(this)
        SonioxTtsPlayer.ended(this)
        host.onEnd(onFinish)
    }
}

/** Per-stream config message. */
internal fun sonioxStreamConfig(streamId: String, voice: String, speed: Double?): JSONObject =
    JSONObject().apply {
        put("stream_id", streamId)
        put("model", SONIOX_TTS_MODEL)
        put("language", "cs")
        put("voice", voice.ifBlank { "Adrian" })
        put("audio_format", "pcm_mulaw")
        put("sample_rate", SONIOX_TTS_SAMPLE_RATE)
        if (speed != null) put("speed", speed)
    }

/**
 * The one live Soniox read-aloud in the process (app-wide, as every other
 * engine): starting one stops the previous. Shared by Android and desktop.
 */
object SonioxTtsPlayer {
    private val current = AtomicReference<SonioxTtsSession?>(null)

    /**
     * Optional owner of the hardware play/pause button while a reading runs
     * (Jarvis conversation mode: the press means "interrupt / my turn", not
     * "pause"). Called on the platform's media thread (Android: main) for
     * each press; return true to consume it, false to let the reading toggle.
     * Null (the default) = the platform's normal handling, unchanged. Set it
     * while the mode is on and clear it when the mode ends.
     */
    @kotlin.concurrent.Volatile
    var onMediaButton: (() -> Boolean)? = null

    /** Stops whatever is reading, then starts [session]. */
    fun start(session: SonioxTtsSession) {
        stop()
        current.set(session)
        session.start()
    }

    fun stop() {
        current.getAndSet(null)?.stop()
    }

    val isActive: Boolean get() = current.get() != null

    internal fun ended(s: SonioxTtsSession) {
        current.compareAndSet(s, null)
    }
}
