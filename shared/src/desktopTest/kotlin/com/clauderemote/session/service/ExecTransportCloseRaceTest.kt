package com.clauderemote.session.service

import com.clauderemote.connection.ServerTransportPool
import com.clauderemote.model.ServerTransport
import com.clauderemote.model.SshServer
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review B-3 testy-spojeni F1 (dávka B, kolo 4): a `close()` that lands while the supervisor of the exec transport is past
 * its `!closed` check must leave the state `Closed`. The supervisor wrote its state outside the entry lock and without
 * looking at `closed`, so it turned `Closed` back into `Connecting` (close just before the connect starts), `Down` or
 * `DownNetwork` (close during a connect that then fails), or `Down` (close while the server is gone), and a connect
 * failure after the close still counted as a Tailscale strike. `OrchestratorReleaseTest` saw it as a flake under load.
 *
 * Deterministic: the seams the supervisor calls at those points ([serverOf] at the top of a pass, the pool lookup of the
 * lease right after [resolve]) call `close()` themselves, on the supervisor's own thread, then the test waits for the
 * cancelled supervisor to end. No real connect: the lease "fails" in the seam.
 */
class ExecTransportCloseRaceTest {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val pool = ServerTransportPool(ServerStorage(FakeKeyValueStore()))
    private val server = SshServer(
        id = "s", name = "box", host = "ssh.example.org", username = "u",
        useCloudflareProxy = true, tailscaleHost = "127.0.0.1", transport = ServerTransport.AUTO,
    )
    /** Tailscale strikes fed by the manager (lifetime ms); a connect failure counts as lifetime 0. */
    private val strikes = CopyOnWriteArrayList<Long>()
    /** Armed by the resolve: the next pool lookup is the supervisor's lease (close() and ensure() look the pool up too). */
    private val leaseNext = AtomicBoolean(false)

    @AfterTest fun tearDown() {
        scope.cancel()
    }

    private fun manager(serverOf: (String) -> SshServer?, onLease: () -> Unit) =
        PoolExecTransportManager(
            scope,
            poolOf = {
                if (leaseNext.compareAndSet(true, false)) {
                    onLease()
                    throw java.io.IOException("connect refused (test)")
                }
                pool
            },
            serverOf = serverOf,
            resolve = { s -> leaseNext.set(true); s.forTransport(ServerTransport.TAILSCALE) },
            onEarlyDeath = { _, _, lifetime -> strikes += lifetime },
            execEnabled = { true },
        )

    /** Waits for the supervisor (and the release close() launched) to end: after that nothing writes the state any more. */
    private fun settle() = runBlocking { withTimeout(10_000) { job.children.toList().forEach { it.join() } } }

    private fun assertClosed(m: PoolExecTransportManager) {
        settle()
        val state = m.state(server.id).value
        assertTrue(state is LinkState.Closed, "state after close is not Closed: $state")
        assertEquals(emptyList(), strikes.toList(), "a connect that failed after the close is no Tailscale strike")
    }

    @Test fun aCloseJustBeforeTheSupervisorSaysConnectingLeavesItClosed() {
        lateinit var m: PoolExecTransportManager
        val calls = AtomicInteger()
        m = manager(serverOf = { id -> if (calls.incrementAndGet() == 1) m.close(id); server }, onLease = {})
        m.ensure(server.id)
        assertClosed(m)
    }

    @Test fun aCloseDuringAConnectThatThenFailsLeavesItClosedAndStrikesNothing() {
        lateinit var m: PoolExecTransportManager
        m = manager(serverOf = { server }, onLease = { m.close(server.id) })
        m.ensure(server.id)
        assertClosed(m)
    }

    @Test fun aCloseDuringAConnectWhileTheNetworkIsLostLeavesItClosed() {
        lateinit var m: PoolExecTransportManager
        val calls = AtomicInteger()
        m = manager(serverOf = { if (calls.incrementAndGet() == 1) m.onNetworkLost(); server }, onLease = { m.close(server.id) })
        m.ensure(server.id)
        assertClosed(m)
    }

    @Test fun aCloseWhileTheServerIsGoneLeavesItClosed() {
        lateinit var m: PoolExecTransportManager
        m = manager(serverOf = { id -> m.close(id); null }, onLease = {})
        m.ensure(server.id)
        assertClosed(m)
    }

    /**
     * Review B-4 adv-spojeni K4-5 (kolo 5): `close()` and right away `ensure()` (the flag off and on, or the last tab closed
     * and a new one opened) while the old supervisor's connect is failing. `ensure()` clears `closed`, so the old run's
     * `!closed` check passed and its failed connect counted as a Tailscale strike. Only the entry's current run may strike.
     * The second supervisor sees no server, so it never connects (and never strikes) itself.
     */
    @Test fun aConnectThatFailsAfterACloseAndAnEnsureAgainStrikesNothing() {
        lateinit var m: PoolExecTransportManager
        val calls = AtomicInteger()
        var oldRun: List<Job> = emptyList()
        val reEnsured = CountDownLatch(1)
        m = manager(serverOf = { if (calls.incrementAndGet() == 1) server else null }, onLease = {
            oldRun = job.children.toList()   // only the first supervisor runs now
            m.close(server.id)
            m.ensure(server.id)
            reEnsured.countDown()
        })
        m.ensure(server.id)
        assertTrue(reEnsured.await(10, TimeUnit.SECONDS), "the first supervisor never leased")
        assertEquals(1, oldRun.size)
        runBlocking { withTimeout(10_000) { oldRun.forEach { it.join() } } }   // past its catch: a strike would be in by now
        assertEquals(emptyList(), strikes.toList(), "the failed connect of a run that close() ended is no Tailscale strike")
        assertTrue(m.state(server.id).value !is LinkState.Closed, "the new run (after ensure) is not closed")
    }

    /**
     * Review B-4 kod L2 (kolo 5): `onNetworkLost()` checked `closed` and then wrote `DownNetwork` outside the entry lock, so a
     * `close()` in between left a closed entry `DownNetwork`. The window is put right there: the entry's state flow, when
     * `onNetworkLost()` writes `DownNetwork` on the test thread, first runs `close()` on another thread and waits for it.
     * Under the entry lock the close has to wait for the network-loss write and then wins.
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    @Test fun aCloseBetweenTheNetworkLossChecksAndItsWriteLeavesItClosed() {
        val m = manager(serverOf = { null }, onLease = {})   // no server: the supervisor never connects
        m.ensure(server.id)
        val entries = PoolExecTransportManager::class.java.getDeclaredField("entries").apply { isAccessible = true }
            .get(m) as Map<*, *>
        val entry = entries[server.id]!!
        val stateField = entry.javaClass.getDeclaredField("state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val real = stateField.get(entry) as MutableStateFlow<LinkState>
        val testThread = Thread.currentThread()
        val closer = AtomicBoolean(false)
        var c: Thread? = null
        val hooked = object : MutableStateFlow<LinkState> by real {
            override var value: LinkState
                get() = real.value
                set(v) {
                    if (v is LinkState.DownNetwork && Thread.currentThread() === testThread && closer.compareAndSet(false, true)) {
                        c = Thread { m.close(server.id) }.also { it.start(); it.join(2_000) }   // ends at once without the lock; with it, waits for this write
                    }
                    real.value = v
                }
        }
        stateField.set(entry, hooked)
        m.onNetworkLost()
        assertTrue(closer.get(), "onNetworkLost() did not write DownNetwork")
        c!!.join(10_000)
        settle()
        val state = real.value
        assertTrue(state is LinkState.Closed, "state after a close during onNetworkLost() is not Closed: $state")
    }
}
