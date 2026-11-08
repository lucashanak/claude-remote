package com.clauderemote.session.service

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The channel budget: permits per kind, void with the transport generation, NOT_UP and the LONG fallback. */
class GatedExecChannelsTest {
    private class Manager(var up: Session?) : ExecTransportManager {
        val gen = AtomicLong(1)
        /** What the manager reports as its state: a transport being connected unless a test says otherwise. */
        val link = MutableStateFlow<LinkState>(LinkState.Connecting)
        /** Every read of the generation moves it on (a transport that keeps being replaced). */
        @Volatile var generationMovesOnEveryRead = false
        override fun ensure(serverId: String) {}
        override fun session(serverId: String): Session? = up?.takeIf { it.isConnected }
        override suspend fun await(serverId: String, ms: Long): Session? {
            val end = System.nanoTime() + ms * 1_000_000
            while (true) {
                session(serverId)?.let { return it }
                if (System.nanoTime() >= end) return null
                delay(10)
            }
        }
        override fun state(serverId: String): StateFlow<LinkState> = link
        override fun close(serverId: String) {}
        override fun onNetworkLost() {}
        override fun onNetworkAvailable() {}
        override fun generation(serverId: String): Long = if (generationMovesOnEveryRead) gen.incrementAndGet() else gen.get()
        override fun setKeepAlive(ms: Int) {}
    }

    private val openFailures = AtomicInteger()

    private fun sessionWith(open: (String) -> Channel = { type -> if (type == "sftp") object : ChannelSftp() { override fun disconnect() {} } else object : ChannelExec() { override fun disconnect() {} } }) =
        FakeSession(channels = open)

    private fun channels(
        m: Manager,
        limits: GatedExecChannels.Limits = GatedExecChannels.Limits(short = 2, long = 1, fallbackLong = 1),
        fallback: Session? = null,
        upWaitMs: Long = 100,
        longFallbackAfterMs: Long = 100,
        reserved: MutableList<String> = mutableListOf(),
    ) = GatedExecChannels(
        m, { fallback }, upWaitMs, longFallbackAfterMs, limits,
        onTransportTrouble = { openFailures.incrementAndGet() },
        reserveFallback = { _, _ -> synchronized(reserved) { reserved += "reserve" }; true },
        releaseFallback = { _, _ -> synchronized(reserved) { reserved += "release" } },
    )

    private fun bounded(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) { runBlocking { withTimeout(20_000) { block() } } }

    @Test fun shortPermitsAreLimitedAndAWaiterGetsOneWhenAHolderLeaves() = bounded {
        val g = channels(Manager(sessionWith()))
        val release = CompletableDeferred<Unit>()
        val holders = (1..2).map { async { g.use("s", ExecKind.SHORT) { release.await() } } }
        while (g.held("s").first < 2) delay(5)
        val third = async { g.use("s", ExecKind.SHORT, acquireTimeoutMs = 5_000) { "got it" } }
        delay(150)
        assertTrue(!third.isCompleted, "the third SHORT waits: limit is 2")
        release.complete(Unit)
        assertEquals("got it", third.await())
        holders.awaitAll()
        assertEquals(Triple(0, 0, 0), g.held("s"), "every permit came back")
    }

    @Test fun waitingLongerThanTheAcquireTimeoutIsAGateTimeout() = bounded {
        val g = channels(Manager(sessionWith()))
        val release = CompletableDeferred<Unit>()
        val holders = (1..2).map { async { g.use("s", ExecKind.SHORT) { release.await() } } }
        while (g.held("s").first < 2) delay(5)
        val e = assertFailsWith<ExecUnavailable> { g.use("s", ExecKind.SHORT, acquireTimeoutMs = 200) { 1 } }
        assertEquals(ExecUnavailableReason.GATE_TIMEOUT, e.reason)
        release.complete(Unit); holders.awaitAll()
    }

    @Test fun sftpSharesTheShortPermits() = bounded {
        val g = channels(Manager(sessionWith()))
        val release = CompletableDeferred<Unit>()
        val a = async { g.use("s", ExecKind.SFTP) { release.await() } }
        val b = async { g.use("s", ExecKind.SHORT) { release.await() } }
        while (g.held("s").first < 2) delay(5)
        val e = assertFailsWith<ExecUnavailable> { g.use("s", ExecKind.SFTP, acquireTimeoutMs = 150) { 1 } }
        assertEquals(ExecUnavailableReason.GATE_TIMEOUT, e.reason, "1 SFTP + 1 SHORT used both short permits")
        release.complete(Unit); a.await(); b.await()
    }

    @Test fun sftpGetsAnSftpChannelAndExecGetsAnExecChannel() = bounded {
        val g = channels(Manager(sessionWith()))
        assertTrue(g.use("s", ExecKind.SFTP) { it is ChannelSftp })
        assertTrue(g.use("s", ExecKind.SHORT) { it is ChannelExec })
    }

    @Test fun longHasItsOwnLimitAndIsNotStarvedByShorts() = bounded {
        val g = channels(Manager(sessionWith()))
        val release = CompletableDeferred<Unit>()
        val shorts = (1..2).map { async { g.use("s", ExecKind.SHORT) { release.await() } } }
        while (g.held("s").first < 2) delay(5)
        val long1 = async { g.use("s", ExecKind.LONG) { release.await() } }
        while (g.held("s").second < 1) delay(5)
        val e = assertFailsWith<ExecUnavailable> { g.use("s", ExecKind.LONG, acquireTimeoutMs = 150) { 1 } }
        assertEquals(ExecUnavailableReason.GATE_TIMEOUT, e.reason, "second LONG waits: limit is 1")
        release.complete(Unit); shorts.awaitAll(); long1.await()
    }

    @Test fun neverMoreThanTheBudgetIsOpenAtOnceUnderLoad() = bounded {
        val limits = GatedExecChannels.Limits(short = 6, long = 2)
        val g = channels(Manager(sessionWith()), limits)
        val open = AtomicInteger(); val peak = AtomicInteger()
        (1..60).map { i ->
            async {
                g.use("s", if (i % 5 == 0) ExecKind.LONG else ExecKind.SHORT, acquireTimeoutMs = 15_000) {
                    val n = open.incrementAndGet()
                    peak.updateAndGet { maxOf(it, n) }
                    delay(15)
                    open.decrementAndGet()
                }
            }
        }.awaitAll()
        assertTrue(peak.get() <= 8, "peak ${peak.get()} must stay within 6 SHORT + 2 LONG")
        assertTrue(peak.get() >= 6, "and the gate actually lets them run in parallel (peak ${peak.get()})")
        assertEquals(Triple(0, 0, 0), g.held("s"))
    }

    @Test fun aNewTransportGenerationVoidsTheOldPermits() = bounded {
        val old = sessionWith()
        val m = Manager(old)
        val g = channels(m)
        val release = CompletableDeferred<Unit>()
        val holders = (1..2).map { async { g.use("s", ExecKind.SHORT) { release.await() } } }
        while (g.held("s").first < 2) delay(5)
        // the transport dies and is replaced: both old permits are void at once
        old.connected = false
        val fresh = sessionWith()
        m.up = fresh
        m.gen.incrementAndGet()
        val got = g.withPermit("s", ExecKind.SHORT, acquireTimeoutMs = 2_000) { it }
        assertSame(fresh, got, "a new caller is served on the new transport immediately, not after the dead holders")
        // the old holders leaving must not free (or double-free) the NEW generation's permits
        release.complete(Unit); holders.awaitAll()
        assertEquals(Triple(0, 0, 0), g.held("s"))
    }

    @Test fun waitersOfADeadGenerationMoveToTheNewTransport() = bounded {
        val old = sessionWith()
        val m = Manager(old)
        val g = channels(m)
        val release = CompletableDeferred<Unit>()
        val holders = (1..2).map { async { g.use("s", ExecKind.SHORT) { release.await() } } }
        while (g.held("s").first < 2) delay(5)
        val waiter = async { g.withPermit("s", ExecKind.SHORT, acquireTimeoutMs = 10_000) { it } }
        delay(100)
        old.connected = false
        val fresh = sessionWith(); m.up = fresh; m.gen.incrementAndGet()
        assertSame(fresh, waiter.await(), "the waiter parked on the old generation is re-served on the new transport")
        release.complete(Unit); holders.awaitAll()
    }

    @Test fun noExecTransportIsNotUpForShortAndSftp() = bounded {
        val g = channels(Manager(null))
        assertEquals(ExecUnavailableReason.NOT_UP, assertFailsWith<ExecUnavailable> { g.use("s", ExecKind.SHORT) { 1 } }.reason)
        assertEquals(ExecUnavailableReason.NOT_UP, assertFailsWith<ExecUnavailable> { g.use("s", ExecKind.SFTP) { 1 } }.reason)
        assertEquals(ExecUnavailableReason.NOT_UP, assertFailsWith<ExecUnavailable> { g.withPermit("s", ExecKind.SHORT) { 1 } }.reason)
    }

    @Test fun longFallsBackToTheLiveShellTransportWhenExecIsNotUp() = bounded {
        val shell = sessionWith()
        val g = channels(Manager(null), fallback = shell, longFallbackAfterMs = 80)
        val t0 = System.nanoTime()
        val got = g.withPermit("s", ExecKind.LONG) { it }
        assertSame(shell, got)
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 60, "it waited for the exec transport first")
    }

    @Test fun theLongFallbackHasItsOwnSmallLimit() = bounded {
        val g = channels(Manager(null), fallback = sessionWith(), longFallbackAfterMs = 20)
        val release = CompletableDeferred<Unit>()
        val a = async { g.withPermit("s", ExecKind.LONG) { release.await() } }
        while (g.held("s").third < 1) delay(5)
        val e = assertFailsWith<ExecUnavailable> { g.withPermit("s", ExecKind.LONG, acquireTimeoutMs = 200) { 1 } }
        assertEquals(ExecUnavailableReason.GATE_TIMEOUT, e.reason)
        release.complete(Unit); a.await()
    }

    @Test fun withPermitHoldsOnePermitForTheBlock() = bounded {
        val g = channels(Manager(sessionWith()))
        g.withPermit("s", ExecKind.SHORT) { assertEquals(1, g.held("s").first) }
        assertEquals(0, g.held("s").first)
        // released on an exception too
        runCatching { g.withPermit("s", ExecKind.SHORT) { error("boom") } }
        assertEquals(0, g.held("s").first)
    }

    @Test fun anOpenFailureTellsTheManagerAndIsNotUp() = bounded {
        val bad = FakeSession(channels = { throw JSchException("session is down") })
        val g = channels(Manager(bad))
        val before = openFailures.get()
        val e = assertFailsWith<ExecUnavailable> { g.use("s", ExecKind.SHORT) { 1 } }
        assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
        assertEquals(before + 1, openFailures.get(), "the transport owner is told at once")
        assertEquals(0, g.held("s").first, "the permit came back")
    }
    @Test fun aDownTransportAnswersNotUpAtOnceInsteadOfWaiting() = bounded {
        val m = Manager(null).also { it.link.value = LinkState.Down }
        val g = channels(m, upWaitMs = 3_000, longFallbackAfterMs = 3_000, fallback = sessionWith())
        val t0 = System.nanoTime()
        val e = assertFailsWith<ExecUnavailable> { g.use("s", ExecKind.SHORT) { 1 } }
        assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
        g.withPermit("s", ExecKind.LONG) { }   // the LONG fallback is immediate too
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 1_500, "a transport in a backoff does not come up within the wait; took $ms ms")
    }

    @Test fun aConnectingTransportIsWaitedFor() = bounded {
        val m = Manager(null)   // Connecting
        val g = channels(m, upWaitMs = 600)
        val t0 = System.nanoTime()
        assertFailsWith<ExecUnavailable> { g.use("s", ExecKind.SHORT) { 1 } }
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 500, "it waited for the transport that is being connected")
    }

    @Test fun theLongLimitHoldsWithNoShortPermitsInUse() = bounded {
        val g = channels(Manager(sessionWith()), GatedExecChannels.Limits(short = 6, long = 1))
        val release = CompletableDeferred<Unit>()
        val first = async { g.use("s", ExecKind.LONG) { release.await() } }
        while (g.held("s").second < 1) delay(5)
        val e = assertFailsWith<ExecUnavailable> { g.use("s", ExecKind.LONG, acquireTimeoutMs = 150) { 1 } }
        assertEquals(ExecUnavailableReason.GATE_TIMEOUT, e.reason, "the second LONG waits although the total budget has room")
        release.complete(Unit); first.await()
    }

    @Test fun aFallbackStreamThatLostTheRoomToAnotherClaimantDoesNotStartAndHoldsNoPermit() = bounded {
        val reserved = mutableListOf<String>()
        val fb = sessionWith()
        val g = GatedExecChannels(
            Manager(null), { fb }, 100, 100,
            GatedExecChannels.Limits(short = 2, long = 1, fallbackLong = 1),
            reserveFallback = { _, _ -> synchronized(reserved) { reserved += "reserve" }; false },
            releaseFallback = { _, _ -> synchronized(reserved) { reserved += "release" } },
        )
        val e = assertFailsWith<ExecUnavailable> { g.use("s", ExecKind.LONG) { } }
        assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
        assertEquals(listOf("reserve"), reserved, "nothing was counted, so nothing is given back")
        assertEquals(Triple(0, 0, 0), g.held("s"), "the local permit came back")
    }

    @Test fun aFallbackStreamIsCountedAgainstTheShellTransportAndGivenBack() = bounded {
        val reserved = mutableListOf<String>()
        val g = channels(Manager(null), fallback = sessionWith(), longFallbackAfterMs = 20, reserved = reserved)
        val release = CompletableDeferred<Unit>()
        val stream = async { g.use("s", ExecKind.LONG) { release.await() } }
        while (g.held("s").third < 1) delay(5)
        assertEquals(listOf("reserve"), synchronized(reserved) { reserved.toList() }, "counted while it runs")
        release.complete(Unit); stream.await()
        assertEquals(listOf("reserve", "release"), synchronized(reserved) { reserved.toList() })
        // an exec permit is not a fallback: it is never counted against a shell transport
        val m = Manager(sessionWith()); val g2 = channels(m, reserved = reserved)
        g2.use("s", ExecKind.LONG) { }
        assertEquals(2, synchronized(reserved) { reserved.size })
    }

    @Test fun aFallbackStreamEndsWhenTheExecTransportComesUp() = bounded {
        val m = Manager(null).also { it.link.value = LinkState.Down }
        val ended = CompletableDeferred<Unit>()
        val shell = sessionWith { object : ChannelExec() { override fun disconnect() { ended.complete(Unit) } } }
        val g = channels(m, fallback = shell)
        val stream = async { g.use("s", ExecKind.LONG) { ended.await() } }
        delay(300)
        assertTrue(!ended.isCompleted, "the stream runs as long as the exec transport is down")
        m.link.value = LinkState.Up("test")
        withTimeout(5_000) { ended.await() }
        stream.await()
        assertEquals(Triple(0, 0, 0), g.held("s"))
    }

    @Test fun aPermitIsGivenBackWhenTheTransportKeepsChangingUnderIt() = bounded {
        val m = Manager(sessionWith()).also { it.generationMovesOnEveryRead = true }
        val g = channels(m)
        val e = assertFailsWith<ExecUnavailable> { g.use("s", ExecKind.SHORT) { 1 } }
        assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
        assertEquals(Triple(0, 0, 0), g.held("s"), "the stale permits did not leak")
    }

    // ---- C1: the priority class of periodic pollers, and the slot of an open its caller gave up on ----

    @Test fun aPollerNeverWaitsForAPermitAndLeavesTheInteractiveReserveFree() = bounded {
        val g = channels(Manager(sessionWith()), limits = GatedExecChannels.Limits(short = 6, long = 2, fallbackLong = 1, interactiveReserve = 2))
        val release = CompletableDeferred<Unit>()
        val holders = (1..3).map { async { g.use("s", ExecKind.SHORT) { release.await() } } }
        while (g.held("s").first < 3) delay(5)
        assertEquals("poll", g.use("s", ExecKind.POLL) { "poll" }, "4 of 6 taken by it: a poller still fits")
        val fourth = async { g.use("s", ExecKind.SHORT) { release.await() } }
        while (g.held("s").first < 4) delay(5)
        val t0 = System.nanoTime()
        val e = assertFailsWith<ExecUnavailable> { g.use("s", ExecKind.POLL, acquireTimeoutMs = 5_000) { "poll" } }
        val tookMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(ExecUnavailableReason.GATE_TIMEOUT, e.reason, "4 taken: the last 2 are the interactive reserve")
        assertTrue(tookMs < 500, "a poller does not wait for a permit (it skips the tick): $tookMs ms")
        assertEquals("user", g.use("s", ExecKind.SHORT) { "user" }, "an interactive caller still gets one of the reserve")
        release.complete(Unit)
        holders.awaitAll(); fourth.await()
        assertEquals(Triple(0, 0, 0), g.held("s"))
    }

    /** K-4: an open the caller gave up on keeps its slot until it resolves; then it is free again (no leak). */
    @Test fun anOpenItsCallerGaveUpOnKeepsItsSlotUntilItResolves() = bounded {
        com.clauderemote.connection.ChannelOpen.resetForTest()
        try {
            lateinit var sess: Session
            val opened = CompletableDeferred<Unit>()
            sess = sessionWith { object : ChannelExec() {
                @Volatile var confirmed = false
                override fun getSession(): Session = sess
                override fun connect(connectTimeout: Int) { Thread.sleep(1_200); confirmed = true; opened.complete(Unit) }
                override fun isConnected(): Boolean = confirmed
                override fun disconnect() {}
            } }
            val g = channels(Manager(sess), limits = GatedExecChannels.Limits(short = 2, long = 1, fallbackLong = 1, interactiveReserve = 0))
            val exec = PooledRemoteExec(g)
            val e = assertFailsWith<ExecUnavailable> { exec.run("s", "true", totalMs = 400) }
            assertTrue(e.reason == ExecUnavailableReason.REFUSED || e.reason == ExecUnavailableReason.TIMEOUT, "${e.reason}: ${e.message}")
            assertEquals(0, g.held("s").first, "the caller's own permit is back")
            assertEquals(1, g.pendingOpens("s"), "but the open still runs and keeps its slot")
            withTimeout(5_000) { opened.await() }
            while (g.pendingOpens("s") > 0) delay(10)
            assertEquals(0, g.pendingOpens("s"), "resolved: the slot is free again")
        } finally {
            com.clauderemote.connection.ChannelOpen.resetForTest()
        }
    }

    // ---- kolo 2 (review C1-1) ----

    /** R12: production uses the default limits — 6 SHORT, 2 of them the interactive reserve. */
    @Test fun theDefaultLimitsKeepTwoOfSixShortPermitsForInteractiveWork() {
        assertEquals(GatedExecChannels.Limits(short = 6, long = 2, fallbackLong = 2, interactiveReserve = 2), GatedExecChannels.Limits())
    }

    /** R12b: an open given up and still pending takes its slot from the poll class too. */
    @Test fun aPendingOpenCountsAgainstThePollCap() = bounded {
        com.clauderemote.connection.ChannelOpen.resetForTest()
        try {
            lateinit var sess: Session
            sess = sessionWith { object : ChannelExec() {
                override fun getSession(): Session = sess
                override fun connect(connectTimeout: Int) { Thread.sleep(2_000) }
                override fun disconnect() {}
            } }
            val g = channels(Manager(sess), limits = GatedExecChannels.Limits())
            val release = CompletableDeferred<Unit>()
            val holders = (1..3).map { async { g.use("s", ExecKind.SHORT) { release.await() } } }
            while (g.held("s").first < 3) delay(5)
            assertEquals("poll", g.use("s", ExecKind.POLL) { "poll" }, "3 of 6 in use: a poller fits")
            assertFailsWith<ExecUnavailable> { PooledRemoteExec(g).run("s", "true", totalMs = 400) }   // given up: pending
            assertEquals(1, g.pendingOpens("s"))
            val e = assertFailsWith<ExecUnavailable> { g.use("s", ExecKind.POLL) { "poll" } }
            assertEquals(ExecUnavailableReason.GATE_TIMEOUT, e.reason, "3 held + 1 pending = 4: the reserve stays free")
            release.complete(Unit); holders.awaitAll()
        } finally {
            com.clauderemote.connection.ChannelOpen.resetForTest()
        }
    }

    /** Review C1-1 (LOW): while the exec transport is connecting a poll skips its tick — no wait, no shell transport. */
    @Test fun aPollWhileTheExecTransportConnectsIsSkippedNotSentToAShellTransport() = bounded {
        val m = Manager(null).also { it.link.value = LinkState.Connecting }
        val shell = sessionWith()
        val reserved = mutableListOf<String>()
        val g = channels(m, fallback = shell, reserved = reserved)
        val t0 = System.nanoTime()
        val e = assertFailsWith<ExecUnavailable> { g.use("s", ExecKind.POLL) { "poll" } }
        assertEquals(ExecUnavailableReason.GATE_TIMEOUT, e.reason)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 50, "at once")
        assertTrue(reserved.isEmpty(), "nothing taken on a shell transport")
    }

    /** R8b: the production budget's headroom answers the refusal rule — two refusals with room left replace the transport. */
    @Test fun twoRefusalsWithRoomLeftOnTheExecTransportReportItDead() = bounded {
        com.clauderemote.connection.ChannelOpen.resetForTest()
        try {
            lateinit var sess: Session
            sess = sessionWith { object : ChannelExec() {
                override fun getSession(): Session = sess
                override fun getExitStatus(): Int = 2
                override fun connect(connectTimeout: Int) { throw JSchException("channel is not opened.") }
                override fun disconnect() {}
            } }
            val dead = java.util.concurrent.CopyOnWriteArrayList<String>()
            com.clauderemote.connection.ChannelOpen.watch(sess) { _, why -> dead += why; true }
            val g = channels(Manager(sess), limits = GatedExecChannels.Limits())
            assertFailsWith<ExecUnavailable> { PooledRemoteExec(g, refusedRetryDelayMs = 5).run("s", "true", totalMs = 3_000) }
            assertEquals(1, dead.size, "a refusal and its one retry, our channels far below MaxSessions: orphans ($dead)")
        } finally {
            com.clauderemote.connection.ChannelOpen.resetForTest()
        }
    }
}
