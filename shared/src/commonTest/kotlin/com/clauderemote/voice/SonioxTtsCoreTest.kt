package com.clauderemote.voice

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The shared Soniox player without network or audio hardware: [FakeServer]
 * stands in for the WebSocket, [FakeSink] for AudioTrack / SourceDataLine.
 */
class SonioxTtsCoreTest {

    // ── pure functions ─────────────────────────────────────────────────────

    @Test
    fun mulawDecodesG711Anchors() {
        assertEquals(0, mulawToLinear(0xFF))
        assertEquals(0, mulawToLinear(0x7F))
        assertEquals(-32124, mulawToLinear(0x00))
        assertEquals(32124, mulawToLinear(0x80))
        // Monotonic within each sign half: louder codes never decode quieter.
        for (i in 0x80 until 0xFF) assertTrue(mulawToLinear(i) > mulawToLinear(i + 1), "code $i")
    }

    @Test
    fun mulawToPcm16IsLittleEndianTableLookup() {
        val src = byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte(), 0x5A)
        val pcm = mulawToPcm16(src, 3)
        assertEquals(6, pcm.size)
        for (i in 0 until 3) {
            val s = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
            assertEquals(mulawToLinear(src[i].toInt() and 0xFF), s.toShort().toInt(), "sample $i")
        }
    }

    @Test
    fun streamSpeedClampsToWhatSonioxAccepts() {
        assertNull(sonioxStreamSpeed(1.0f))
        assertEquals(1.3, sonioxStreamSpeed(2.0f))
        assertEquals(0.7, sonioxStreamSpeed(0.5f))
        assertEquals(1.15, sonioxStreamSpeed(1.15f))
    }

    @Test
    fun streamConfigCarriesSpeedOnlyWhenSet() {
        val plain = sonioxStreamConfig("s0-1", "", null)
        assertEquals("Adrian", plain.getString("voice"))
        assertEquals("pcm_mulaw", plain.getString("audio_format"))
        assertEquals(8000, plain.getInt("sample_rate"))
        assertTrue(!plain.has("speed"))
        assertEquals(1.2, sonioxStreamConfig("s0-1", "Maya", 1.2).getDouble("speed"))
    }

    // ── session ────────────────────────────────────────────────────────────

    private val tuning = SonioxTtsTuning(
        firstBackoffMs = 30, maxBackoffMs = 60, serverErrorStepMs = 30, rateLimitRetryMs = 30,
        idleCloseMs = 60_000, finishSettleMs = 0,
    )
    private val sessions = CopyOnWriteArrayList<SonioxTtsSession>()

    @AfterTest
    fun tearDown() {
        sessions.forEach { it.stop() }
        SonioxTtsPlayer.stop()
        SonioxTtsPlayer.onMediaButton = null
    }

    private class Harness(
        val session: SonioxTtsSession,
        val server: FakeServer,
        val sink: FakeSink,
        val finished: CountDownLatch,
        val errors: MutableList<String>,
        val fallback: AtomicReference<Pair<String, Int>?>,
    )

    private fun harness(
        segments: List<String>,
        withFallback: Boolean = false,
        fallbackAfterMs: Long = 60_000,
        t: SonioxTtsTuning = tuning,
        speed: Double? = null,
    ): Harness {
        val server = FakeServer()
        val sink = FakeSink()
        val finished = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<String>()
        val fb = AtomicReference<Pair<String, Int>?>(null)
        val s = SonioxTtsSession(
            segments = segments,
            voice = "Adrian",
            sink = sink,
            sockets = server,
            onFinish = { finished.countDown() },
            onError = { errors += it },
            streamSpeed = speed,
            onFallback = if (withFallback) { r, from -> fb.set(r to from) } else null,
            fallbackAfterMs = fallbackAfterMs,
            tuning = t,
        )
        sessions += s
        return Harness(s, server, sink, finished, errors, fb)
    }

    private fun audio(seed: Int, n: Int) = ByteArray(n) { (seed + it % 7).toByte() }

    @Test
    fun readsAllSegmentsInOrderAndFinishes() {
        val h = harness(listOf("první", "druhý", "třetí"))
        h.session.start()
        val sock = h.server.awaitSocket(0)
        sock.open()
        val chunks = listOf(audio(0x11, 3000), audio(0x22, 2500), audio(0x33, 1800))
        for (i in chunks.indices) {
            val id = sock.awaitStream(i)
            assertEquals(listOf("první", "druhý", "třetí")[i], sock.textOf(id))
            sock.audio(id, chunks[i])
            sock.end(id)
        }
        assertTrue(h.finished.await(5, TimeUnit.SECONDS), "onFinish")
        val expected = ByteArrayOutputStream().apply { chunks.forEach { write(mulawToPcm16(it)) } }.toByteArray()
        assertContentEquals(expected, h.sink.nonSilentPcm())
        assertNull(TtsPlayback.state.value, "detached")
        assertTrue(sock.closed, "socket closed at the end")
        assertTrue(h.sink.released)
        assertTrue(h.errors.isEmpty())
    }

    @Test
    fun keepsAtMostTwoStreamsInFlight() {
        val h = harness(listOf("a", "b", "c", "d"))
        h.session.pause()
        h.session.start()
        val sock = h.server.awaitSocket(0)
        sock.open()
        val first = sock.awaitStream(0)
        sock.awaitStream(1)
        Thread.sleep(100)
        assertEquals(2, sock.streamIds().size, "third waits for a free slot")
        sock.audio(first, audio(1, 10)); sock.end(first)
        sock.awaitStream(2)
        assertEquals(3, sock.streamIds().size)
    }

    @Test
    fun speedGoesIntoEveryStreamConfig() {
        val h = harness(listOf("a"), speed = 1.3)
        h.session.start()
        val sock = h.server.awaitSocket(0)
        sock.open()
        val id = sock.awaitStream(0)
        assertEquals(1.3, sock.configOf(id).getDouble("speed"))
    }

    @Test
    fun transportControlsMoveTheStateMachine() {
        val h = harness(listOf("jedna", "dva", "tři"))
        h.session.start()
        val sock = h.server.awaitSocket(0)
        sock.open()
        awaitPhase(h.session) { it.phase == TtsPhase.BUFFERING || it.phase == TtsPhase.PLAYING }
        h.sink.awaitOpen()

        h.session.pause()
        assertEquals(TtsPhase.PAUSED, h.session.state().phase)
        assertTrue(h.sink.calls.contains("pause"))
        assertEquals(TtsPhase.PAUSED, TtsPlayback.state.value?.phase, "bar sees it")

        h.session.next()
        assertEquals(1, h.session.state().segment)
        h.session.next()
        h.session.next() // past the end: stays on the last
        assertEquals(2, h.session.state().segment)
        h.session.previous() // at its start: back one paragraph
        assertEquals(1, h.session.state().segment)
        assertTrue(h.sink.calls.contains("flush"), "a seek drops queued audio")

        h.session.play()
        assertTrue(h.session.state().phase != TtsPhase.PAUSED)
        h.session.toggle()
        assertEquals(TtsPhase.PAUSED, h.session.state().phase)
        h.session.toggle()
        assertTrue(h.session.state().phase != TtsPhase.PAUSED)

        h.session.stop()
        assertTrue(h.finished.await(2, TimeUnit.SECONDS))
        assertNull(TtsPlayback.state.value)
    }

    @Test
    fun rewindCrossesIntoTheFinishedPreviousSegment() {
        val h = harness(listOf("a", "b"))
        h.session.pause()
        h.session.start()
        val sock = h.server.awaitSocket(0)
        sock.open()
        val a = sock.awaitStream(0)
        sock.audio(a, audio(1, 4000)); sock.end(a)
        h.session.next()
        assertEquals(1, h.session.state().segment)
        h.session.rewind(10)
        assertEquals(0, h.session.state().segment)
    }

    @Test
    fun transientFocusLossResumesButUserPauseWins() {
        val h = harness(listOf("a"))
        h.session.start()
        h.session.transientPause()
        assertEquals(TtsPhase.PAUSED, h.session.state().phase)
        h.session.transientResume()
        assertTrue(h.session.state().phase != TtsPhase.PAUSED)

        h.session.transientPause()
        h.session.pause() // the user's pause wins over the call ending
        h.session.transientResume()
        assertEquals(TtsPhase.PAUSED, h.session.state().phase)
    }

    @Test
    fun mediaButtonHookConsumesThePressAndFocusLossNeverCallsIt() {
        val h = harness(listOf("a"))
        h.session.start()
        h.sink.awaitOpen()
        val presses = java.util.concurrent.atomic.AtomicInteger(0)
        var consume = true
        SonioxTtsPlayer.onMediaButton = { presses.incrementAndGet(); consume }

        h.session.mediaButton()
        assertEquals(1, presses.get())
        assertTrue(h.session.state().phase != TtsPhase.PAUSED, "consumed: the reading is left alone")

        h.session.transientPause() // a navigation prompt / a call
        assertEquals(TtsPhase.PAUSED, h.session.state().phase)
        h.session.transientResume()
        h.session.pause(); h.session.play() // bar / notification
        assertEquals(1, presses.get(), "only the button reaches the hook")

        consume = false
        h.session.mediaButton()
        assertEquals(2, presses.get())
        assertEquals(TtsPhase.PAUSED, h.session.state().phase, "not consumed: toggles as before")

        SonioxTtsPlayer.onMediaButton = null
        h.session.mediaButton()
        assertTrue(h.session.state().phase != TtsPhase.PAUSED, "no hook: plain toggle")
    }

    @Test
    fun refusedKeyWithoutFallbackReportsAndStops() {
        val h = harness(listOf("a", "b"))
        h.session.start()
        h.server.awaitSocket(0).fail(401)
        assertTrue(h.finished.await(2, TimeUnit.SECONDS))
        assertEquals(1, h.errors.size)
        assertTrue(h.errors[0].contains("odmítl"), h.errors[0])
    }

    @Test
    fun refusedKeyWithFallbackHandsOverFromTheStart() {
        val h = harness(listOf("a", "b"), withFallback = true)
        h.session.start()
        h.server.awaitSocket(0).fail(403)
        assertTrue(h.finished.await(2, TimeUnit.SECONDS))
        val fb = assertNotNull(h.fallback.get())
        assertEquals(0, fb.second)
        assertTrue(h.errors.isEmpty(), "fallback replaces the error")
    }

    @Test
    fun fatalServerErrorOnAStreamFallsBackAtThatSegment() {
        val h = harness(listOf("a", "b"), withFallback = true)
        h.session.start()
        val sock = h.server.awaitSocket(0)
        sock.open()
        val a = sock.awaitStream(0)
        sock.audio(a, audio(1, 200)); sock.end(a)
        val b = sock.awaitStream(1)
        awaitPhase(h.session) { it.segment == 1 }
        sock.error(b, 402, "no credit")
        assertTrue(h.finished.await(2, TimeUnit.SECONDS))
        assertEquals(1, h.fallback.get()?.second)
    }

    @Test
    fun noAudioInTimeFallsBack() {
        val h = harness(listOf("a"), withFallback = true, fallbackAfterMs = 150)
        h.session.start()
        h.server.awaitSocket(0) // never opens
        assertTrue(h.finished.await(3, TimeUnit.SECONDS))
        assertEquals(0, h.fallback.get()?.second)
    }

    @Test
    fun serverErrorRetriesTheSameSegment() {
        val h = harness(listOf("a"))
        h.session.start()
        val sock = h.server.awaitSocket(0)
        sock.open()
        val first = sock.awaitStream(0)
        sock.error(first, 500, "boom")
        val second = sock.awaitStream(1)
        assertTrue(first != second)
        assertEquals("a", sock.textOf(second))
        sock.audio(second, audio(3, 100)); sock.end(second)
        assertTrue(h.finished.await(3, TimeUnit.SECONDS))
        assertTrue(h.errors.isEmpty())
    }

    @Test
    fun droppedConnectionReconnectsAndRefetches() {
        val h = harness(listOf("a", "b"))
        h.session.start()
        val s1 = h.server.awaitSocket(0)
        s1.open()
        s1.awaitStream(0)
        s1.fail(null) // network drop mid-stream
        awaitPhase(h.session) { it.phase == TtsPhase.WAITING_NETWORK }
        val s2 = h.server.awaitSocket(1)
        s2.open()
        val a = s2.awaitStream(0)
        assertEquals("a", s2.textOf(a))
        s2.audio(a, audio(1, 100)); s2.end(a)
        val b = s2.awaitStream(1)
        s2.audio(b, audio(2, 100)); s2.end(b)
        assertTrue(h.finished.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun givingUpPausesAtTheGapWithoutFallback() {
        val h = harness(listOf("a"), t = tuning.copy(giveUpMs = 0))
        h.session.start()
        val s1 = h.server.awaitSocket(0)
        s1.open()
        s1.awaitStream(0)
        s1.fail(null) // first loss starts the clock …
        val s2 = h.server.awaitSocket(1)
        s2.fail(null) // … the next one is past giveUpMs = 0
        awaitPhase(h.session) { it.phase == TtsPhase.INTERRUPTED }
        assertEquals(1L, h.finished.count, "still alive: ▶ resumes from here")
        h.session.play()
        val s3 = h.server.awaitSocket(2)
        s3.open()
        val id = s3.awaitStream(0)
        s3.audio(id, audio(1, 50)); s3.end(id)
        assertTrue(h.finished.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun givingUpWithFallbackHandsOverTheCurrentSegment() {
        val h = harness(listOf("a", "b"), withFallback = true, t = tuning.copy(giveUpMs = 0))
        h.session.start()
        val s1 = h.server.awaitSocket(0)
        s1.open()
        val a = s1.awaitStream(0)
        s1.audio(a, audio(1, 100)); s1.end(a)
        s1.awaitStream(1)
        s1.fail(null)
        h.server.awaitSocket(1).fail(null)
        assertTrue(h.finished.await(3, TimeUnit.SECONDS))
        assertEquals(1, h.fallback.get()?.second)
    }

    @Test
    fun noAudioOutputFallsBackWithoutBuyingAStream() {
        val h = harness(listOf("a"), withFallback = true)
        h.sink.failOpen = true
        h.session.start()
        assertTrue(h.finished.await(3, TimeUnit.SECONDS))
        assertEquals(0, h.fallback.get()?.second)
        assertEquals(0, h.server.count, "no socket, no stream, no credit spent")
    }

    @Test
    fun socketThatCannotBeBuiltIsFatalNotAThrow() {
        val finished = CountDownLatch(1)
        val errors = CopyOnWriteArrayList<String>()
        val s = SonioxTtsSession(
            segments = listOf("a"), voice = "Adrian", sink = FakeSink(),
            sockets = { throw IllegalArgumentException("Unexpected char 0x200b in header") },
            onFinish = { finished.countDown() }, onError = { errors += it }, tuning = tuning,
        )
        sessions += s
        s.start()
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertEquals(1, errors.size, "reported once: $errors")
        assertTrue(errors[0].contains("klíč"), errors[0])
    }

    @Test
    fun shortWritesAreFinishedNotDropped() {
        val h = harness(listOf("a", "b"))
        h.sink.maxPerWrite = 333 // odd-sized short counts, never the whole chunk
        h.session.start()
        val sock = h.server.awaitSocket(0)
        sock.open()
        val chunks = listOf(audio(0x15, 5000), audio(0x25, 1234))
        for (i in chunks.indices) {
            val id = sock.awaitStream(i)
            sock.audio(id, chunks[i]); sock.end(id)
        }
        assertTrue(h.finished.await(5, TimeUnit.SECONDS))
        val expected = ByteArrayOutputStream().apply { chunks.forEach { write(mulawToPcm16(it)) } }.toByteArray()
        assertContentEquals(expected, h.sink.nonSilentPcm())
    }

    @Test
    fun segmentCutMidDownloadIsRefetchedAndJoinedBeforeTheBreak() {
        val overlap = 100
        val h = harness(listOf("a"), t = tuning.copy(rejoinOverlapSamples = overlap))
        h.session.start()
        val s1 = h.server.awaitSocket(0)
        s1.open()
        val first = s1.awaitStream(0)
        val old = audio(0x31, 1000)
        s1.audio(first, old)
        // Let the player read all of the old copy (lead-in 1600 B + 2000 B of PCM).
        val until = System.currentTimeMillis() + 3000
        while (h.sink.written() < 1600 + 2000 && System.currentTimeMillis() < until) Thread.sleep(5)
        s1.fail(null) // dropped mid-stream, no audio_end
        val s2 = h.server.awaitSocket(1)
        s2.open()
        val again = s2.awaitStream(0)
        assertEquals("a", s2.textOf(again), "the same paragraph again")
        val fresh = audio(0x51, 3000)
        s2.audio(again, fresh); s2.end(again)
        assertTrue(h.finished.await(5, TimeUnit.SECONDS))
        // Old copy in full, then the new copy from `overlap` samples before the break.
        val expected = mulawToPcm16(old) + mulawToPcm16(fresh.copyOfRange(old.size - overlap, fresh.size))
        assertContentEquals(expected, h.sink.nonSilentPcm())
    }

    @Test
    fun rateLimitsDoNotUseUpTheAttempts() {
        val h = harness(listOf("a"))
        h.session.start()
        val sock = h.server.awaitSocket(0)
        sock.open()
        // More 429s than maxStreamAttempts (5): still retried, never halted.
        repeat(7) { n -> sock.error(sock.awaitStream(n), 429, "too many streams") }
        val id = sock.awaitStream(7)
        assertTrue(h.session.state().phase != TtsPhase.INTERRUPTED)
        sock.audio(id, audio(1, 100)); sock.end(id)
        assertTrue(h.finished.await(3, TimeUnit.SECONDS))
        assertTrue(h.errors.isEmpty())
    }

    @Test
    fun serverErrorsDoUseUpTheAttempts() {
        val h = harness(listOf("a"))
        h.session.start()
        val sock = h.server.awaitSocket(0)
        sock.open()
        repeat(5) { n -> sock.error(sock.awaitStream(n), 503, "busy") }
        awaitPhase(h.session) { it.phase == TtsPhase.INTERRUPTED }
        Thread.sleep(100)
        assertEquals(5, sock.streamIds().size, "no sixth attempt")
    }

    @Test
    fun streamStuckWithoutDataIsRetriedOnAFreshSocket() {
        val h = harness(listOf("a"), t = tuning.copy(streamStallMs = 150, stallCheckMs = 30))
        h.session.start()
        val s1 = h.server.awaitSocket(0)
        s1.open()
        s1.awaitStream(0) // …and the server never answers it
        val s2 = h.server.awaitSocket(1)
        assertTrue(s1.closed, "the stuck socket is dropped so its stream slots free up")
        s2.open()
        val id = s2.awaitStream(0)
        assertEquals("a", s2.textOf(id))
        s2.audio(id, audio(1, 100)); s2.end(id)
        assertTrue(h.finished.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun aStallThatKeepsComingBackEndsInAHalt() {
        val h = harness(listOf("a"), t = tuning.copy(streamStallMs = 60, stallCheckMs = 20, maxStreamAttempts = 2))
        h.session.start()
        h.server.awaitSocket(0).also { it.open(); it.awaitStream(0) }
        h.server.awaitSocket(1).also { it.open(); it.awaitStream(0) }
        awaitPhase(h.session) { it.phase == TtsPhase.INTERRUPTED }
        assertEquals("stream bez dat", h.session.state().note)
    }

    @Test
    fun startingAnotherStopsThePrevious() {
        val a = harness(listOf("a"))
        val b = harness(listOf("b"))
        SonioxTtsPlayer.start(a.session)
        assertTrue(SonioxTtsPlayer.isActive)
        SonioxTtsPlayer.start(b.session)
        assertTrue(a.finished.await(2, TimeUnit.SECONDS), "first stopped")
        assertEquals(1L, b.finished.count)
        SonioxTtsPlayer.stop()
        assertTrue(b.finished.await(2, TimeUnit.SECONDS))
        assertTrue(!SonioxTtsPlayer.isActive)
    }

    private fun awaitPhase(s: SonioxTtsSession, cond: (TtsPlayerState) -> Boolean) {
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until) {
            if (cond(s.state())) return
            Thread.sleep(10)
        }
        fail("state never matched; last ${s.state()}")
    }
}

/** Records what the player wrote. Never blocks; returns 0 while paused like a real line. */
internal class FakeSink : TtsAudioSink {
    val out = ByteArrayOutputStream()
    val calls = CopyOnWriteArrayList<String>()
    @Volatile var paused = true
    @Volatile var released = false
    @Volatile var failOpen = false
    @Volatile var opened = false
    /** >0: accept at most this many bytes per write, like a line with little room left. */
    @Volatile var maxPerWrite = 0

    override fun open(sampleRate: Int): Int {
        if (failOpen) throw IllegalStateException("no audio device")
        opened = true
        return 4000
    }

    fun awaitOpen() {
        val until = System.currentTimeMillis() + 3000
        while (!opened && System.currentTimeMillis() < until) Thread.sleep(5)
        // The session flags the sink usable right after open() returns.
        Thread.sleep(20)
        assertTrue(opened, "sink never opened")
    }
    override fun write(pcm: ByteArray, off: Int, len: Int): Int {
        if (paused) { Thread.sleep(2); return 0 }
        val n = if (maxPerWrite > 0) minOf(len, maxPerWrite) else len
        synchronized(out) { out.write(pcm, off, n) }
        return n
    }

    fun written(): Int = synchronized(out) { out.size() }
    override fun play() { calls += "play"; paused = false }
    override fun pause() { calls += "pause"; paused = true }
    override fun flush() { calls += "flush" }
    override fun halt() { calls += "halt"; paused = true }
    override fun release() { calls += "release"; released = true }

    /** Written PCM without the lead-in and drain silence (test audio is never silent). */
    fun nonSilentPcm(): ByteArray {
        val all = synchronized(out) { out.toByteArray() }
        var start = 0
        while (start + 1 < all.size && all[start] == 0.toByte() && all[start + 1] == 0.toByte()) start += 2
        var end = all.size
        while (end - 2 >= start && all[end - 1] == 0.toByte() && all[end - 2] == 0.toByte()) end -= 2
        return all.copyOfRange(start, end)
    }
}

/** A scripted Soniox: hands out [FakeSocket]s and lets the test speak for the server. */
internal class FakeServer : TtsSocketFactory {
    private val sockets = CopyOnWriteArrayList<FakeSocket>()
    val count: Int get() = sockets.size

    override fun connect(listener: TtsSocketListener): TtsSocket = FakeSocket(listener).also { sockets += it }

    fun awaitSocket(index: Int): FakeSocket {
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until) {
            sockets.getOrNull(index)?.let { return it }
            Thread.sleep(5)
        }
        fail("socket #$index never connected (have ${sockets.size})")
    }
}

internal class FakeSocket(private val l: TtsSocketListener) : TtsSocket {
    private val sent = CopyOnWriteArrayList<JSONObject>()
    @Volatile var closed = false

    override fun send(text: String): Boolean { sent += JSONObject(text); return !closed }
    override fun close(code: Int, reason: String?) { closed = true }

    // Events come from another thread, like OkHttp's reader — the session
    // registers the socket under its lock only after connect() returns.
    private fun async(block: () -> Unit) {
        val t = Thread(block); t.start(); t.join()
    }

    fun open() = async { l.onOpen(this) }
    fun fail(httpCode: Int?) = async { l.onFailure(this, RuntimeException("drop"), httpCode) }
    fun audio(id: String, mu: ByteArray) = async {
        l.onMessage(this, JSONObject().put("stream_id", id).put("audio", Base64.getEncoder().encodeToString(mu)).toString())
    }
    fun end(id: String) = async { l.onMessage(this, JSONObject().put("stream_id", id).put("audio_end", true).toString()) }
    fun error(id: String, code: Int, msg: String) = async {
        l.onMessage(this, JSONObject().put("stream_id", id).put("error_code", code).put("error_message", msg).toString())
    }

    fun streamIds(): List<String> = sent.filter { it.has("model") }.map { it.getString("stream_id") }
    fun configOf(id: String): JSONObject = sent.first { it.has("model") && it.getString("stream_id") == id }
    fun textOf(id: String): String = sent.first { it.has("text") && it.getString("stream_id") == id }.getString("text")

    /** The [n]-th stream (0-based) this socket was asked for. */
    fun awaitStream(n: Int): String {
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until) {
            val ids = streamIds()
            if (ids.size > n && sent.any { it.has("text") && it.getString("stream_id") == ids[n] }) return ids[n]
            Thread.sleep(5)
        }
        fail("stream #$n never started (have ${streamIds()})")
    }
}
