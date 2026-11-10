package com.clauderemote.connection

import com.clauderemote.session.BudgetFixture
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.Session
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dávka E, H1: sshd frees the session behind a channel (what `MaxSessions` counts) only once it has reaped the command,
 * not when our CLOSE arrives. So [ChannelOpen.disconnect] leaves an exec whose output ended to sshd's own CLOSE, and a
 * counted slot of the transport comes back ([ChannelOpen.afterSettled], the pool's `releaseChannel`) only after the
 * channels lingering there settled and the grace passed — never waited for by the caller. On 1bdcbd5 the slot came back
 * at once (`releaseChannel` right after our `disconnect`), and `ChannelBudgetIntegrationTest.n3r` was refused 9 channels.
 */
class ChannelSettleTest {
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    @BeforeTest fun setUp() { ChannelOpen.resetForTest() }
    @AfterTest fun tearDown() { ChannelOpen.resetForTest() }

    /** An exec whose output [eof] ended or not; the "server" closes it when [serverClose] is called. */
    private class Exec(private val sess: Session, @Volatile var eof: Boolean) : ChannelExec() {
        @Volatile private var open = true
        @Volatile private var closed = false
        @Volatile var disconnects = 0
        override fun getSession(): Session = sess
        override fun isConnected(): Boolean = open
        override fun isClosed(): Boolean = closed
        override fun isEOF(): Boolean = eof
        /** What JSch does on sshd's CLOSE: answers it and forgets the channel (out of its static channel table, too). */
        fun serverClose() { closed = true; open = false; super.disconnect() }
        // super: never connected for real, so it writes nothing and only drops the channel from JSch's static table,
        // as a real disconnect does (a fake that stayed there would keep its session alive for the leak test)
        override fun disconnect() { disconnects++; closed = true; open = false; super.disconnect() }
    }

    private fun now() = System.nanoTime() / 1_000_000

    private companion object {
        /**
         * The grace the slot must at least wait after the channel settled: sshd frees the session in its next loop, and
         * over Cloudflare our CLOSE and the next OPEN can arrive in one read. (The default is 200 ms; a scheduler's
         * jitter of a few ms is allowed for.)
         */
        const val MIN_GRACE_MS = 150L
    }

    @Test fun anExecWhoseOutputEndedIsLeftToSshdsCloseAndItsSlotComesBackOnlyAfterItAndTheGrace() = runBlocking {
        val s = FakeSession()
        val ch = Exec(s, eof = true)
        ChannelOpen.disconnect(ch)
        assertEquals(0, ch.disconnects, "no CLOSE of ours while sshd has not reaped the command (and no write on the caller's thread)")
        assertEquals(1, ChannelOpen.lingeringCount(s))
        val releasedAt = AtomicLong(0)
        val t0 = now()
        ChannelOpen.afterSettled(s) { releasedAt.set(now()) }
        assertTrue(now() - t0 < 100, "the caller does not wait for the release")
        Thread.sleep(400)
        assertEquals(0L, releasedAt.get(), "not given back while sshd still holds the session")
        val closedAt = now()
        ch.serverClose()
        waitFor(3_000) { releasedAt.get() != 0L }
        val after = releasedAt.get() - closedAt
        assertTrue(after >= MIN_GRACE_MS, "given back the grace after sshd's CLOSE, not at once: ${after}ms")
        assertTrue(after < ChannelOpen.tuning.settleGraceMs + 500, "and not much later: ${after}ms")
        assertEquals(1, ch.disconnects, "the closer let JSch forget it after sshd's CLOSE")
    }

    @Test fun aChannelClosedWithoutWaitingStillLingersForTheGrace() = runBlocking {
        val s = FakeSession()
        val ch = Exec(s, eof = false)   // the command still runs: we close it (a watchdog, a reader that stops early)
        ChannelOpen.disconnect(ch)
        assertEquals(1, ch.disconnects, "closed at once: nothing to wait for")
        val releasedAt = AtomicLong(0)
        val t0 = now()
        ChannelOpen.afterSettled(s) { releasedAt.set(now()) }
        waitFor(3_000) { releasedAt.get() != 0L }
        val took = releasedAt.get() - t0
        assertTrue(took >= MIN_GRACE_MS, "the grace: sshd frees the session in its next loop at the earliest: ${took}ms")
    }

    @Test fun nothingLingeringGivesTheSlotBackAtOnceAndAfterTheGraceNothingLingers() = runBlocking {
        val s = FakeSession()
        var released = false
        ChannelOpen.afterSettled(s) { released = true }
        assertTrue(released, "no channel of the transport lingers: back at once, synchronously")
        ChannelOpen.disconnect(Exec(s, eof = false))
        Thread.sleep(ChannelOpen.tuning.settleGraceMs + 50)
        assertEquals(0, ChannelOpen.lingeringCount(s), "settled and the grace passed")
        assertEquals(0, ChannelOpen.lingeringSessions(), "and the transport's entry left the map with it")
        released = false
        ChannelOpen.afterSettled(s) { released = true }
        assertTrue(released)
    }

    @Test fun sshdThatNeverClosesIsClosedByUsAfterTheWaitAndTheSlotStillComesBack() = runBlocking {
        ChannelOpen.tuning = ChannelOpen.Tuning(closeWaitMs = 300)
        val s = FakeSession()
        val ch = Exec(s, eof = true)
        ChannelOpen.disconnect(ch)
        val releasedAt = AtomicLong(0)
        val t0 = now()
        ChannelOpen.afterSettled(s) { releasedAt.set(now()) }
        waitFor(3_000) { releasedAt.get() != 0L }
        assertEquals(1, ch.disconnects, "closed by us after the wait")
        val took = releasedAt.get() - t0
        assertTrue(took >= 300 + MIN_GRACE_MS && took < 2_000, "the wait, then the grace: ${took}ms")
    }

    @Test fun aSecondDisconnectOfALingeringChannelChangesNothing() {
        val s = FakeSession()
        val ch = Exec(s, eof = true)
        ChannelOpen.disconnect(ch)
        ChannelOpen.disconnect(ch)   // the watchdog and the finally of the same exec
        assertEquals(0, ch.disconnects)
        assertEquals(1, ChannelOpen.lingeringCount(s), "one channel, one entry")
        ch.serverClose()
    }

    @Test fun thePoolGivesACountedSlotBackOnlyOnceTheChannelOfItSettled() = runBlocking {
        val pool = ServerTransportPool(ServerStorage(FakeKeyValueStore()))
        val s = FakeSession()
        BudgetFixture.injectTransport(pool, s, 8)
        assertTrue(pool.reserveFallbackChannel(s, short = true), "8 shells: one short channel")
        val ch = Exec(s, eof = true)
        ChannelOpen.disconnect(ch)     // the poll read its output to EOF and is done
        pool.releaseChannel(s)          // returns at once
        assertFalse(pool.fallbackHeadroom(s, short = true), "sshd still holds the session: no room for the next poll")
        assertFalse(pool.reserveFallbackChannel(s, short = true), "and the claim agrees")
        ch.serverClose()
        waitFor(3_000) { pool.fallbackHeadroom(s, short = true) }
        assertTrue(pool.reserveFallbackChannel(s, short = true), "room again once sshd closed it and the grace passed")
    }

    // ---- kolo 2 (review E-K) ---------------------------------------------------------------------------------------

    /** V5: two counted slots, one given back the deferred way — exactly one comes back, never both. */
    @Test fun aDeferredReleaseGivesBackExactlyOneSlotOfTwo() = runBlocking {
        val pool = ServerTransportPool(ServerStorage(FakeKeyValueStore()))
        val s = FakeSession()
        BudgetFixture.injectTransport(pool, s, 6)
        assertTrue(pool.reserveFallbackChannel(s), "6 shells: a first long channel")
        assertTrue(pool.reserveFallbackChannel(s), "and a second (6 + 1 + 1 + 2 = 10)")
        assertFalse(pool.fallbackHeadroom(s), "full")
        val ch = Exec(s, eof = true)
        ChannelOpen.disconnect(ch)
        pool.releaseChannel(s)
        ch.serverClose()
        waitFor(3_000) { pool.fallbackHeadroom(s) }
        Thread.sleep(300)
        assertTrue(pool.reserveFallbackChannel(s), "the one slot given back")
        assertFalse(pool.reserveFallbackChannel(s), "only that one: the other reservation still holds its slot")
    }

    /** V8: a release waits for EVERY channel lingering on the transport at that moment, its siblings too. */
    @Test fun aDeferredReleaseWaitsForTheSiblingsToo() = runBlocking {
        val s = FakeSession()
        val a = Exec(s, eof = true)
        val b = Exec(s, eof = true)
        ChannelOpen.disconnect(a)
        ChannelOpen.disconnect(b)
        val releasedAt = AtomicLong(0)
        ChannelOpen.afterSettled(s) { releasedAt.set(now()) }
        a.serverClose()
        Thread.sleep(ChannelOpen.tuning.settleGraceMs + 300)
        assertEquals(0L, releasedAt.get(), "the sibling still lingers: sshd counts it against the same MaxSessions")
        val closedAt = now()
        b.serverClose()
        waitFor(3_000) { releasedAt.get() != 0L }
        assertTrue(releasedAt.get() - closedAt >= MIN_GRACE_MS, "after the last of them and the grace")
    }

    /** V11: the closer writes to JSch (its CLOSE) on an IO worker, never on the caller's thread. */
    @Test fun theCloserWritesOnAnIoWorkerNeverOnTheCallersThread() {
        val s = FakeSession()
        val where = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val ch = object : ChannelExec() {
            @Volatile var open = true
            override fun getSession(): Session = s
            override fun isConnected(): Boolean = open
            override fun isClosed(): Boolean = false
            override fun isEOF(): Boolean = true
            override fun disconnect() { where.compareAndSet(null, Thread.currentThread().name); open = false }
        }
        ChannelOpen.tuning = ChannelOpen.Tuning(closeWaitMs = 100)   // sshd never closes it: the closer writes our CLOSE
        val caller = Thread.currentThread().name
        ChannelOpen.disconnect(ch)
        assertEquals(null, where.get(), "nothing written by disconnect itself")
        waitFor(3_000) { where.get() != null }
        val w = where.get()!!
        assertTrue(w != caller && w.startsWith("DefaultDispatcher-worker"), "written on $w (caller $caller)")
    }

    /** A-EK1: a transport that had lingering channels is not kept alive by the linger map once they settled. */
    @Test fun aSessionWithLingeringChannelsIsCollectedOnceTheyAllSettled() {
        assertEquals(null, gcAndGet(untouched()), "control: an untouched session is collected")
        val abrupt = lingeredAbruptly()
        val waited = lingeredUntilSshdClosed()
        Thread.sleep(ChannelOpen.tuning.settleGraceMs + 400)
        assertEquals(null, gcAndGet(abrupt), "a channel closed at once: the session is not retained")
        assertEquals(null, gcAndGet(waited), "a channel left to sshd's CLOSE: the session is not retained")
    }

    /** A-EK6: a closer whose channel throws still settles, and the slot still comes back (soon, not at the cap). */
    @Test fun aChannelThatThrowsInTheCloserStillSettlesAndItsSlotComesBack() = runBlocking {
        val s = FakeSession()
        val ch = object : ChannelExec() {
            @Volatile var calls = 0
            override fun getSession(): Session = s
            // the first call (disconnect's own look) says "output ended", every later one (the closer) throws
            override fun isConnected(): Boolean = if (calls++ == 0) true else throw IllegalStateException("boom")
            override fun isClosed(): Boolean = false
            override fun isEOF(): Boolean = true
            override fun disconnect() {}
        }
        ChannelOpen.disconnect(ch)
        val releasedAt = AtomicLong(0)
        val t0 = now()
        ChannelOpen.afterSettled(s) { releasedAt.set(now()) }
        waitFor(3_000) { releasedAt.get() != 0L }
        assertTrue(releasedAt.get() != 0L && releasedAt.get() - t0 < 1_500, "given back after the grace, not at the cap: ${releasedAt.get() - t0}ms")
    }

    /** A-EK6: a release that throws is logged, it ends nothing else (no uncaught exception on a thread). */
    @Test fun aReleaseThatThrowsIsLoggedAndTheNextOneStillRuns() = runBlocking {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val saved = com.clauderemote.util.FileLogger.platformLog
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val savedHandler = Thread.getDefaultUncaughtExceptionHandler()
        com.clauderemote.util.FileLogger.platformLog = { _, tag, message, _ -> lines += "$tag: $message" }
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val s = FakeSession()
            ChannelOpen.disconnect(Exec(s, eof = false))
            ChannelOpen.afterSettled(s) { throw IllegalStateException("release boom") }
            waitFor(3_000) { lines.any { "closer failed" in it } }
            assertTrue(lines.any { "closer failed" in it && "release boom" in it }, "logged: $lines")
            assertTrue(uncaught.isEmpty(), "no uncaught exception: $uncaught")
            ChannelOpen.disconnect(Exec(s, eof = false))
            var ran = false
            ChannelOpen.afterSettled(s) { ran = true }
            waitFor(3_000) { ran }
            assertTrue(ran, "the closer still works")
        } finally {
            com.clauderemote.util.FileLogger.platformLog = saved
            Thread.setDefaultUncaughtExceptionHandler(savedHandler)
        }
    }

    /** A-EK5: a linger whose closer never ends (its CLOSE stuck in a full socket) counts as settled after the wait. */
    @Test fun aLingerThatNeverSettlesCountsAsSettledAfterTheWait() = runBlocking {
        ChannelOpen.tuning = ChannelOpen.Tuning(closeWaitMs = 300)
        val stuck = java.util.concurrent.CountDownLatch(1)
        val s = FakeSession()
        val ch = object : ChannelExec() {
            override fun getSession(): Session = s
            override fun isConnected(): Boolean = true
            override fun isClosed(): Boolean = false
            override fun isEOF(): Boolean = true
            override fun disconnect() { stuck.await(20, java.util.concurrent.TimeUnit.SECONDS) }
        }
        try {
            ChannelOpen.disconnect(ch)
            val releasedAt = AtomicLong(0)
            val t0 = now()
            ChannelOpen.afterSettled(s) { releasedAt.set(now()) }
            waitFor(3_000) { releasedAt.get() != 0L }
            val took = releasedAt.get() - t0
            assertTrue(releasedAt.get() != 0L && took < 1_200, "the wait and the grace, not the whole stuck write: ${took}ms")
            Thread.sleep(ChannelOpen.tuning.settleGraceMs + 100)
            var at = false
            ChannelOpen.afterSettled(s) { at = true }
            assertTrue(at, "and it slows no later release of the transport down")
        } finally {
            stuck.countDown()
        }
    }

    /**
     * A-EK4 + N2-4: the late confirmation of an open its caller gave up on (it runs the no-op) is left to sshd's own CLOSE
     * like an exec whose output ended, and the gate's slot for it comes back only after that CLOSE and the grace.
     */
    @Test fun theSlotHeldForAnAbandonedOpenComesBackOnlyAfterSshdClosedItsLateConfirmation() {
        ChannelOpen.tuning = ChannelOpen.Tuning(minOpenLanMs = 10)
        val s = FakeSession()
        val held = java.util.concurrent.atomic.AtomicInteger()
        ChannelOpen.slots(s, object : ChannelOpen.Slots {
            override fun hold(): () -> Unit { held.incrementAndGet(); return { held.decrementAndGet() } }
            override fun headroom(): Boolean = true
        })
        val confirmedAt = AtomicLong(0)
        val ch = object : ChannelExec() {
            @Volatile var open = false
            @Volatile var closed = false
            @Volatile var ourCloses = 0
            override fun getSession(): Session = s
            override fun isConnected(): Boolean = open
            override fun isClosed(): Boolean = closed
            override fun connect(connectTimeout: Int) { Thread.sleep(300); open = true; confirmedAt.set(now()) }
            fun serverClose() { closed = true; open = false; super.disconnect() }
            override fun disconnect() { if (open) ourCloses++; open = false; super.disconnect() }
        }
        kotlin.runCatching { ChannelOpen.connect(ch, 100) }   // the caller gives up after 100 ms; the open goes on
        assertEquals(1, held.get(), "the slot is held for the open the caller gave up on")
        waitFor(3_000) { confirmedAt.get() != 0L }
        Thread.sleep(ChannelOpen.tuning.settleGraceMs + 100L)
        assertEquals(1, held.get(), "confirmed late, its no-op not reaped by sshd yet: not back on the grace alone")
        assertEquals(0, ch.ourCloses, "no CLOSE of ours: sshd closes it itself once it reaped the no-op")
        val closedAt = now()
        ch.serverClose()
        waitFor(3_000) { held.get() == 0 }
        assertEquals(0, held.get(), "back after sshd's CLOSE")
        assertTrue(now() - closedAt >= MIN_GRACE_MS, "and the grace")
    }

    /**
     * N2-4: the open ends in the very instant the caller gives up — between the gate's `hold()` and the hand-over of the
     * slot to the open (`Open.abandon`, the "finished" branch). That slot too comes back only once the late confirmation
     * settled, never directly. The test's `hold()` waits until the open has resolved, so the branch is taken every time.
     */
    @Test fun theSlotOfAnOpenThatEndedWhileTheCallerGaveUpComesBackSettledToo() {
        ChannelOpen.tuning = ChannelOpen.Tuning(minOpenLanMs = 10)
        val s = FakeSession()
        val held = java.util.concurrent.atomic.AtomicInteger()
        lateinit var ch: ChannelExec
        ChannelOpen.slots(s, object : ChannelOpen.Slots {
            override fun hold(): () -> Unit {
                held.incrementAndGet()
                // the open resolves meanwhile (out of `pending`), and its run() marks itself finished right after
                val end = System.nanoTime() + 3_000_000_000L
                while (ChannelOpen.isPending(ch) && System.nanoTime() < end) Thread.sleep(5)
                Thread.sleep(100)
                return { held.decrementAndGet() }
            }
            override fun headroom(): Boolean = true
        })
        val late = object : ChannelExec() {
            @Volatile var open = false
            @Volatile var closed = false
            override fun getSession(): Session = s
            override fun isConnected(): Boolean = open
            override fun isClosed(): Boolean = closed
            override fun connect(connectTimeout: Int) { Thread.sleep(150); open = true }
            fun serverClose() { closed = true; open = false; super.disconnect() }
            override fun disconnect() { open = false; super.disconnect() }
        }
        ch = late
        kotlin.runCatching { ChannelOpen.connect(late, 100) }   // gives up at 100 ms; the open confirms at 150 ms
        Thread.sleep(ChannelOpen.tuning.settleGraceMs + 200L)
        assertEquals(1, held.get(), "not given back directly: the late confirmation still waits for sshd's CLOSE")
        late.serverClose()
        waitFor(3_000) { held.get() == 0 }
        assertEquals(0, held.get(), "back once it settled")
    }

    // own (non-inline) functions: a local of the test method would keep the session alive in its frame
    private fun untouched(): java.lang.ref.WeakReference<out Session> = java.lang.ref.WeakReference(FakeSession())

    private fun lingeredAbruptly(): java.lang.ref.WeakReference<out Session> {
        val s = FakeSession()
        ChannelOpen.disconnect(Exec(s, eof = false))
        return java.lang.ref.WeakReference(s)
    }

    private fun lingeredUntilSshdClosed(): java.lang.ref.WeakReference<out Session> {
        val s = FakeSession()
        val ch = Exec(s, eof = true)
        ChannelOpen.disconnect(ch)
        ch.serverClose()
        return java.lang.ref.WeakReference(s)
    }

    private fun gcAndGet(r: java.lang.ref.WeakReference<out Session>): Session? {
        repeat(30) { System.gc(); Thread.sleep(50); if (r.get() == null) return null }
        return r.get()
    }

    private fun waitFor(ms: Long, cond: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!cond() && System.nanoTime() < end) Thread.sleep(5)
    }
}
