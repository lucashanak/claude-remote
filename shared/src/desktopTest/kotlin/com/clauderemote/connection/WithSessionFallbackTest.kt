package com.clauderemote.connection

import com.clauderemote.model.SshServer
import com.clauderemote.session.BudgetFixture
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.ExecUnavailableReason
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.FakeSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pooled step of [SshSessionHelper.withSession] (every one-off user of the helper: history scan, folder browser,
 * command fetcher, accounts, the pollers' exec path) as the orchestrator wires it. With exec routing on and the exec
 * transport down, the block runs on a shell transport of the pool and must hold one COUNTED channel there for its
 * whole run; without room it must not run there at all. A legacy server (no room check) counts nothing.
 *
 * The block's channel is SHORT: 8 shells on a transport leave room for exactly one such channel (`MaxSessions` 10, a
 * short channel may take one of the 2 kept free, V9), so the transport's short headroom tells whether the block's
 * channel is counted. No room = that one slot is held by another short channel already.
 */
class WithSessionFallbackTest {
    private val accepted = AtomicInteger()
    private val listener = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    private val acceptor = Thread {
        try { while (true) { listener.accept().also { accepted.incrementAndGet(); it.close() } } } catch (_: Exception) {}
    }.also { it.isDaemon = true; it.start() }
    private val server = SshServer(id = "s1", name = "n", host = "127.0.0.1", port = listener.localPort, username = "u", password = "p")
    private val storage = ServerStorage(FakeKeyValueStore())
    private var orchestrator: SessionOrchestrator? = null

    @BeforeTest fun setUp() { CommFlags.resetForTest(); storage.addServer(server) }

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.liveSessionProvider = null
        SshSessionHelper.fallbackRunner = null
        CommFlags.resetForTest()
        listener.close()
    }

    /** The orchestrator's own wiring of the helper, minus the exec gate (the exec transport is down); [held] short channels already taken. */
    private fun wired(shells: Int, held: Int = 0): Pair<ServerTransportPool, FakeSession> {
        val o = SessionOrchestrator(storage, TabManager()).also { orchestrator = it }
        SshSessionHelper.execChannelsProvider = null
        val pool = o.connectionRegistry.transportPool(server.id)
        val fake = FakeSession()
        BudgetFixture.injectTransport(pool, fake, shells)
        repeat(held) { assertTrue(runBlocking { pool.reserveFallbackChannel(fake, short = true) }, "fixture: a held channel") }
        return pool to fake
    }

    @Test fun theBlockHoldsACountedChannelOnThePooledTransportAndGivesItBack() = runBlocking {
        val (pool, fake) = wired(8)
        assertTrue(pool.fallbackHeadroom(fake, short = true), "control: one slot is free before")
        val during = SshSessionHelper.withSession(server, allowFresh = false) { s -> pool.fallbackHeadroom(s, short = true) }
        assertFalse(during, "the block's channel must be counted while it runs (it took the last slot)")
        assertTrue(pool.fallbackHeadroom(fake, short = true), "and given back afterwards")
        assertEquals(0, accepted.get())
    }

    @Test fun withoutRoomTheBlockDoesNotRunOnThePoolAndNoFreshConnectionIsOpened() = runBlocking {
        wired(8, held = 1)
        var ran = false
        val e = assertFailsWith<ExecUnavailable> { SshSessionHelper.withSession(server, allowFresh = false) { ran = true } }
        assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
        assertFalse(ran)
        Thread.sleep(300)
        assertEquals(0, accepted.get())
    }

    @Test fun withoutRoomAUiCallerThatMayConnectStillGetsAFreshConnection() = runBlocking {
        wired(8, held = 1)
        var ran = false
        // allowFresh (the default of the UI callers: folder browser, history scan, ...): no room on the pool = a fresh
        // connection as before (the test's listener accepts and closes it, so the handshake fails)
        runCatching { SshSessionHelper.withSession(server, timeout = 1_500) { ran = true } }
        assertFalse(ran, "the block never ran on the full pooled transport")
        assertTrue(accepted.get() >= 1, "a fresh connection was attempted")
    }

    @Test fun aBlockThatReturnsNullRunsExactlyOnceAndIsNotRetriedOnAFreshConnection() = runBlocking {
        val (pool, fake) = wired(8)
        val runs = AtomicInteger()
        val r: String? = SshSessionHelper.withSession(server, timeout = 1_500) { runs.incrementAndGet(); null }
        assertNull(r)
        assertEquals(1, runs.get(), "null is the block's answer, not 'no room'")
        Thread.sleep(300)
        assertEquals(0, accepted.get(), "no fresh connection after a null answer")
        assertTrue(pool.fallbackHeadroom(fake, short = true))
    }

    @Test fun aCancelledBlockGivesItsChannelBackAtOnce() = runBlocking {
        val (pool, fake) = wired(8)
        val entered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            SshSessionHelper.withSession(server, allowFresh = false) { entered.complete(Unit); awaitCancellation() }
        }
        withTimeout(5_000) { entered.await() }
        assertFalse(pool.fallbackHeadroom(fake, short = true))
        job.cancelAndJoin()
        assertTrue(pool.fallbackHeadroom(fake, short = true), "the channel is given back even when the block is cancelled")
    }

    @Test fun aLegacyServerRunsTheBlockOnThePoolWithoutCountingAnything() = runBlocking {
        CommFlags.set(CommFlags.Raw(exec = false))   // no exec routing; 3 shells are not packed: no room check
        val (pool, fake) = wired(3)
        suspend fun freeSlots(): Int {
            var n = 0
            while (pool.reserveFallbackChannel(fake)) n++
            repeat(n) { pool.releaseChannel(fake) }
            return n
        }
        val before = freeSlots()
        val during = SshSessionHelper.withSession(server, allowFresh = false) { freeSlots() }
        assertEquals(before, during, "a legacy lease must not lose a slot while a one-off op runs")
        assertEquals(0, accepted.get())
    }

    // ---- the seam between the look and the claim (review round 1, MK1: FALLBACK_CLAIM_TRIES untested) ----

    @Test fun aSlotTakenBetweenTheLookAndTheClaimIsLookedForAgainOnAnotherTransport() = runBlocking {
        val (pool, a) = wired(8)
        val b = FakeSession()
        BudgetFixture.injectTransport(pool, b, 8)
        val registry = orchestrator!!.connectionRegistry
        val stolen = AtomicInteger()
        registry.claimSeam = { seen ->
            // the first look found a transport with one slot: somebody else takes that slot before our claim
            if (stolen.getAndIncrement() == 0) assertTrue(pool.reserveFallbackChannel(seen, short = true), "fixture: the slot is taken")
        }
        try {
            val ranOn = SshSessionHelper.withSession(server, allowFresh = false) { s -> s }
            assertTrue(stolen.get() >= 2, "the claim failed and the runner looked again (looks: ${stolen.get()})")
            assertTrue(ranOn === b, "the block ran on the transport that still had room")
            assertEquals(0, accepted.get())
            assertTrue(pool.fallbackHeadroom(b, short = true), "its channel given back")
            assertFalse(pool.fallbackHeadroom(a, short = true), "the other transport's slot stays with whoever took it")
        } finally {
            registry.claimSeam = null
        }
    }

    @Test fun twoCallersRacingForTheLastSlotNeverBothGetIt() = runBlocking {
        val (pool, fake) = wired(8)
        val registry = orchestrator!!.connectionRegistry
        val bothLooked = CompletableDeferred<Unit>()
        val looks = AtomicInteger()
        // both callers look before either claims: both see the one free slot
        registry.claimSeam = { if (looks.incrementAndGet() >= 2) bothLooked.complete(Unit) else withTimeout(5_000) { bothLooked.await() } }
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()
        try {
            val results = listOf(
                launch(Dispatchers.IO) { runCatching { SshSessionHelper.withSession(server, allowFresh = false) { val n = inside.incrementAndGet(); maxInside.accumulateAndGet(n, ::maxOf); Thread.sleep(200); inside.decrementAndGet() } } },
                launch(Dispatchers.IO) { runCatching { SshSessionHelper.withSession(server, allowFresh = false) { val n = inside.incrementAndGet(); maxInside.accumulateAndGet(n, ::maxOf); Thread.sleep(200); inside.decrementAndGet() } } },
            )
            withTimeout(10_000) { results.forEach { it.join() } }
            assertEquals(1, maxInside.get(), "only one of two callers may hold the last slot")
            assertTrue(pool.fallbackHeadroom(fake, short = true), "given back")
        } finally {
            registry.claimSeam = null
        }
    }
}
