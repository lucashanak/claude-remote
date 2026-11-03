package com.clauderemote.integration

import com.clauderemote.connection.ServerTransportPool
import com.clauderemote.connection.SshManager
import com.clauderemote.connection.TransportRole
import com.clauderemote.model.PortForward
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.ExecUnavailableReason
import com.clauderemote.session.service.GatedExecChannels
import com.clauderemote.session.service.LinkState
import com.clauderemote.session.service.PooledRemoteExec
import com.clauderemote.session.service.PoolExecTransportManager
import com.clauderemote.session.service.UngatedExecChannels
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pool v2 against a REAL sshd: single-flight leases, the shell cap, spill on MaxSessions,
 * the exec transport (alive with zero shells, carrying port forwards, reconnecting, healing
 * after a network loss), the channel budget, and the exec error contract on real channels.
 */
class PoolV2IntegrationTest {
    private var fixture: SshdFixture? = null
    private val storage = ServerStorage(InMemoryKeyValueStore())
    private val managers = CopyOnWriteArrayList<SshManager>()
    private val pools = CopyOnWriteArrayList<ServerTransportPool>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedPlatformLog = FileLogger.platformLog

    private fun fx(extraSshdConfig: String = ""): SshdFixture {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        return SshdFixture.start(extraSshdConfig).also { fixture = it }
    }

    @get:org.junit.Rule val dumpOnFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable?, description: org.junit.runner.Description?) {
            println("---- log lines of ${description?.methodName} ----\n" + logLines.joinToString("\n"))
        }
    }

    @Before fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
    }

    @After fun tearDown() {
        FileLogger.platformLog = savedPlatformLog
        scope.cancel()
        pools.forEach { it.teardownAll() }
        managers.forEach { it.shutdownForTest() }
        CommFlags.resetForTest()
        fixture?.close()
    }

    /** A pool whose servers' exec transports count as running (shell cap 8), unless [execWired] says otherwise. */
    private fun newPool(execWired: Boolean = true) =
        ServerTransportPool(storage, execWired = { execWired }).also { pools += it }

    private fun connectAll(pool: ServerTransportPool, server: SshServer, n: Int): List<SshManager> = runBlocking {
        val ms = (1..n).map { SshManager(storage, transportPool = pool).also { m -> managers += m } }
        withTimeout(90_000) { ms.map { m -> async(Dispatchers.IO) { m.connect(server, {}, {}) } }.awaitAll() }
        ms
    }

    private fun accepted(f: SshdFixture) = f.acceptedHandshakes()

    /** TCP connections to this fixture's sshd that are ESTABLISHED right now (loopback, so both ends are counted). */
    private fun establishedConnections(f: SshdFixture): Int =
        f.run("ss", "-Htn", "state", "established", "( sport = :${f.port} )").out.lines().count { it.isNotBlank() }

    // ------------------------------------------------------------------ cap + single flight

    /** 20 tabs at cap 8 need 3 transports; one transport per 3 tabs would need 7 (and 7 handshakes). */
    @Test fun twentyTabsNeedAtMostCeilTwentyOverCapTransports() {
        val f = fx()
        val pool = newPool()
        val ms = connectAll(pool, f.server, 20)
        val cap = CommFlags.effectiveFor(f.server.id).shellCap
        assertEquals(8, cap)
        val transports = ms.map { it.getSession() }.distinct().size
        assertEquals(3, transports, "20 tabs at cap 8 ride ceil(20/8)=3 transports")
        assertEquals(3, pool.liveCount(), "and the pool holds exactly those")
        assertEquals(3, accepted(f), "3 handshakes in total, not one per transport per tab")
        assertTrue(ms.groupBy { it.getSession() }.values.all { it.size <= cap }, "no transport above the cap")
    }

    /** Until the server's exec transport really runs, its transient execs still ride the tab transports: 3 shells each. */
    @Test fun theShellCapIsThreeUntilTheExecTransportIsWiredAndEightAfter() {
        val f = fx()
        val unwired = ServerTransportPool(storage).also { pools += it }   // the default: nothing wired
        val a = connectAll(unwired, f.server, 10)
        assertEquals(4, a.map { it.getSession() }.distinct().size, "ceil(10/3) transports")
        assertTrue(a.groupBy { it.getSession() }.values.all { it.size <= 3 })
        val wired = newPool(execWired = true)
        val b = connectAll(wired, f.server, 10)
        assertEquals(2, b.map { it.getSession() }.distinct().size, "ceil(10/8) transports")
    }

    /** A transport the server refused a channel on is not offered again, by the legacy lease either. */
    @Test fun aFullTransportIsNotLeasedAgainEvenByTheLegacyLease() {
        CommFlags.set(CommFlags.Raw(pool = false))
        val f = fx()
        val pool = newPool()
        runBlocking {
            val first = pool.lease(f.server, 15_000, Any())
            pool.reportFull(first)
            val second = pool.lease(f.server, 15_000, Any())
            assertNotSame(first, second, "a transport marked full is skipped")
        }
    }

    /** A long stream borrowing a shell transport counts against its capacity; only shell transports are borrowed. */
    @Test fun aReservedChannelTakesASlotAndOnlyShellTransportsAreBorrowed() {
        val f = fx()
        val pool = newPool(execWired = false)   // cap 3
        runBlocking {
            val a = pool.lease(f.server, 15_000, Any())
            val b = pool.lease(f.server, 15_000, Any())
            assertSame(a, b)
            assertTrue(pool.reserveChannel(a))
            // 2 shells + 1 reserved channel = cap 3: the next shell must go to a new transport
            val c = pool.lease(f.server, 15_000, Any())
            assertNotSame(a, c, "the reserved channel filled the third slot")
            pool.releaseChannel(a)
            val d = pool.lease(f.server, 15_000, Any())
            assertSame(a, d, "the slot is free again after the stream ended")
            // the exec transport is never offered as a shell transport, nor borrowed
            val e = pool.leaseExec(f.server, 15_000, Any())
            assertTrue(!pool.reserveChannel(e))
        }
    }

    @Test fun theExecTransportIsNotAnyUsableSession() {
        val f = fx()
        val pool = newPool()
        runBlocking { pool.leaseExec(f.server, 15_000, Any()) }
        assertNull(pool.anyUsableSession(), "its channels are metered by the exec permits, not handed out as a tab session")
        connectAll(pool, f.server, 1)
        assertNotNull(pool.anyUsableSession())
    }

    /** `comm_pool` OFF is the legacy lease, literally: cap 3 and no single-flight (the handshake is under the mutex). */
    @Test fun poolOffKeepsTheLegacyLeaseWithCapThreeAndNoFlights() {
        CommFlags.set(CommFlags.Raw(pool = false))
        val f = fx()
        val pool = newPool()
        val ms = connectAll(pool, f.server, 20)
        assertEquals(7, ms.map { it.getSession() }.distinct().size, "ceil(20/3)")
        assertEquals(7, accepted(f))
        runBlocking {
            assertEquals(0, pool.startedConnects(), "legacy path never starts a flight")
            assertEquals(0, pool.peakInflightConnects())
        }
    }

    @Test fun singleFlightStartsConnectsInParallelAndNeverMoreThanNeeded() {
        val f = fx()
        val pool = newPool()
        connectAll(pool, f.server, 20)
        runBlocking {
            assertEquals(3, pool.startedConnects(), "ceil(20/8) handshakes were started")
            assertTrue(pool.peakInflightConnects() >= 2, "...concurrently, not one after another (peak ${pool.peakInflightConnects()})")
            assertEquals(0, pool.inflightConnects(), "nothing left in flight")
        }
    }

    @Test fun aCancelledInitiatorLeaksNoSession() {
        val f = fx()
        val pool = newPool()
        runBlocking {
            val job = async(start = CoroutineStart.UNDISPATCHED) { pool.lease(f.server, 15_000, Any()) }
            // UNDISPATCHED: the claim is made and the coroutine is parked on the handshake
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            // the handshake still completes (it belongs to the pool, not to the initiator)...
            waitUntil(15_000, "the abandoned handshake to complete") { accepted(f) == 1 }
            // ...and its session is thrown away rather than leaked
            waitUntil(15_000, "the abandoned transport to be disposed") { logLines.any { it.contains("Disposed transport") } }
            waitUntil(15_000, "its TCP connection to the sshd to be closed") { establishedConnections(f) == 0 }
            assertEquals(0, pool.liveCount(), "nothing is published for a lease nobody holds")
        }
    }

    @Test fun aWaiterWhoseInitiatorGaveUpStillGetsTheTransport() {
        val f = fx()
        val pool = newPool()
        runBlocking {
            val first = async(start = CoroutineStart.UNDISPATCHED) { pool.lease(f.server, 15_000, Any()) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { pool.lease(f.server, 15_000, Any()) }
            first.cancelAndJoin()
            val s = withTimeout(20_000) { second.await() }
            assertTrue(s.isConnected)
            assertEquals(1, accepted(f), "one handshake served the survivor")
            assertEquals(1, pool.liveCount())
        }
    }

    // ------------------------------------------------------------------ spill

    @Test fun sixConcurrentTabsOnAMaxSessionsFourServerSpillWithoutASurfacedError() {
        val f = fx("MaxSessions 4")
        val pool = newPool()
        val ms = connectAll(pool, f.server, 6)   // would throw if the refusal surfaced
        assertTrue(f.refusedChannelOpens() >= 1, "the server really refused a channel (${f.refusedChannelOpens()})")
        assertEquals(6, ms.count { it.isConnected })
        assertEquals(2, ms.map { it.getSession() }.distinct().size, "the refused tabs moved to a second transport")
        assertTrue(logLines.any { it.contains("channel is not opened.") && it.contains("spilling") }, "the spill is logged with the vocabulary the extractor counts")
    }

    // ------------------------------------------------------------------ exec transport

    private fun manager(f: SshdFixture, pool: ServerTransportPool, server: SshServer = f.server,
                        resolve: suspend (SshServer) -> SshServer = { it },
                        onEarlyDeath: (SshServer, SshServer, Long) -> Unit = { _, _, _ -> },
                        onStable: (SshServer, SshServer) -> Unit = { _, _ -> },
                        clock: () -> Long = System::currentTimeMillis) =
        PoolExecTransportManager(
            scope, { pool }, { server.takeIf { s -> s.id == it } }, resolve, onEarlyDeath, onStable,
            execEnabled = { true }, pollMs = 200, stableAfterMs = 700, clock = clock,
        )

    private fun upFor(m: PoolExecTransportManager, id: String) = runBlocking {
        assertNotNull(withTimeout(30_000) { m.await(id, 25_000) }, "exec transport did not come up")
    }

    /** The exec transport is alive with ZERO shells (it is a transport of its own, not a view of a shell's). */
    @Test fun theExecTransportWorksWithZeroShells() {
        val f = fx()
        val pool = newPool()
        val m = manager(f, pool)
        m.ensure(f.server.id)
        val s = upFor(m, f.server.id)
        val exec = PooledRemoteExec(GatedExecChannels(m))
        val r = runBlocking { exec.run(f.server.id, "echo no-shells") }
        assertEquals("no-shells\n", r.stdout)
        assertEquals(0, pool.liveCount(TransportRole.SHELL))
        assertEquals(1, pool.liveCount(TransportRole.EXEC))
        assertEquals("lan", pool.routeOf(s), "loopback is the LAN, not a tunnel")
        assertTrue(m.state(f.server.id).value is LinkState.Up)
    }

    @Test fun theConnectLogLinesCarryRoleAndRoute() {
        val f = fx()
        val pool = newPool()
        val ms = connectAll(pool, f.server, 1)
        assertTrue(logLines.any { Regex("""ServerTransportPool: New transport to 127\.0\.0\.1:${f.port} role=shell route=lan \(\d+ in pool\)""").containsMatchIn(it) }, logLines.filter { it.contains("transport") }.toString())
        val m = manager(f, pool); m.ensure(f.server.id); upFor(m, f.server.id)
        assertTrue(logLines.any { it.contains("role=exec route=lan") }, "the exec handshake is counted as role=exec")
        assertTrue(logLines.any { Regex("""ExecTransport: Exec transport integration up via DIRECT in \d+ms route=lan""").containsMatchIn(it) }, logLines.filter { it.contains("Exec transport") }.toString())
        assertEquals(1, ms.size)
    }

    @Test fun anLForwardOnTheExecTransportIsReachableWithZeroShellsAndSurvivesAReconnect() {
        val f = fx()
        val remote = ServerSocket(0)
        val local = ServerSocket(0).use { it.localPort }
        val acceptor = Thread {
            while (!remote.isClosed) runCatching { remote.accept().use { c -> c.getOutputStream().write("PONG\n".toByteArray()) } }
        }.apply { isDaemon = true; start() }
        try {
            val server = f.server.copy(portForwards = listOf(PortForward("L", local, "127.0.0.1", remote.localPort)))
            val pool = newPool()
            val m = manager(f, pool, server)
            m.ensure(server.id)
            upFor(m, server.id)
            fun ask() = Socket("127.0.0.1", local).use { it.soTimeout = 5_000; it.getInputStream().bufferedReader().readLine() }
            assertEquals("PONG", ask(), "forward works with 0 shells")
            assertEquals(0, pool.liveCount(TransportRole.SHELL))
            // the transport dies (as on a network blip): the forward is registered again on the new one
            val gen = m.generation(server.id)
            pool.teardownAll()
            waitUntil(30_000, "the exec transport to come back") { m.generation(server.id) > gen && m.state(server.id).value is LinkState.Up }
            waitUntil(10_000, "the forward to answer again") { runCatching { ask() == "PONG" }.getOrDefault(false) }
        } finally {
            remote.close(); acceptor.interrupt()
        }
    }

    @Test fun shellSessionsDoNotRebindForwardsTheExecTransportOwns() {
        val f = fx()
        val remote = ServerSocket(0)
        val local = ServerSocket(0).use { it.localPort }
        try {
            val server = f.server.copy(portForwards = listOf(PortForward("L", local, "127.0.0.1", remote.localPort)))
            val pool = newPool()
            val m = manager(f, pool, server); m.ensure(server.id); upFor(m, server.id)
            val out = StringBuilder()
            val sm = SshManager(storage, transportPool = pool).also { managers += it }
            runBlocking { sm.connect(server, { out.append(it) }, {}) }
            assertTrue(!out.contains("Port forward"), "the shell session must not announce or bind the forward: $out")
            assertTrue(logLines.none { it.contains("Port forward on shared transport") }, "no failed rebind either")
        } finally { remote.close() }
    }

    @Test fun theExecTransportReconnectsAfterItsDeathAndBumpsTheGeneration() {
        val f = fx()
        val pool = newPool()
        val m = manager(f, pool)
        m.ensure(f.server.id)
        val first = upFor(m, f.server.id)
        val gen = m.generation(f.server.id)
        pool.teardownAll()
        waitUntil(30_000, "a new exec transport") { m.state(f.server.id).value is LinkState.Up && m.session(f.server.id).let { it != null && it !== first } }
        assertTrue(m.generation(f.server.id) > gen)
        assertTrue(logLines.any { Regex("""Exec transport integration down after \d+ms via DIRECT""").containsMatchIn(it) })
    }

    @Test fun aYoungTailscaleDeathStrikesButADirectOneDoesNot() {
        val f = fx()
        val strikes = CopyOnWriteArrayList<Long>()
        // TS endpoint = the same loopback sshd, resolved the way the tab resolver would
        val ts = f.server.copy(tailscaleHost = "127.0.0.1")
        val pool = newPool()
        val m = manager(f, pool, ts, resolve = { it.copy(host = it.tailscaleHost) }, onEarlyDeath = { _, _, life -> strikes += life })
        m.ensure(ts.id)
        upFor(m, ts.id)
        pool.teardownAll()
        waitUntil(30_000, "the TS death to be counted") { strikes.size == 1 }
        assertTrue(strikes.single() < 30_000)
        assertTrue(logLines.any { Regex("""Exec transport integration down after \d+ms via TS""").containsMatchIn(it) }, "the line the extractor's TS_DEATH_RE reads")

        val direct = newPool()
        val d = manager(f, direct, onEarlyDeath = { _, _, life -> strikes += life })
        d.ensure(f.server.id); upFor(d, f.server.id)
        val before = strikes.size
        val gen = d.generation(f.server.id)
        direct.teardownAll()
        waitUntil(30_000, "the direct transport to die and return") { d.generation(f.server.id) > gen && d.state(f.server.id).value is LinkState.Up }
        assertEquals(before, strikes.size, "a non-Tailscale death is no strike")
    }

    @Test fun aNetworkLossParksInDownNetworkAndHealsWithoutAnyoneTellingIt() {
        val f = fx()
        val pool = newPool()
        val m = manager(f, pool)
        m.ensure(f.server.id)
        upFor(m, f.server.id)
        val gen = m.generation(f.server.id)
        m.onNetworkLost()
        assertEquals(LinkState.DownNetwork, m.state(f.server.id).value)
        assertTrue(m.generation(f.server.id) > gen)
        waitUntil(10_000, "the dead transport to be released") { pool.liveCount(TransportRole.EXEC) == 0 }
        // nobody calls onNetworkAvailable: the first caller that wants the transport heals it
        Thread.sleep(2_300)
        val t0 = System.nanoTime()
        val s = runBlocking { withTimeout(30_000) { m.await(f.server.id, 25_000) } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertNotNull(s, "DOWN_NETWORK must heal on demand")
        assertTrue(m.state(f.server.id).value is LinkState.Up)
        assertTrue(ms < 6_000, "the waiter itself triggers the connect (took $ms ms); the 10 s retry timer is only the fallback")
    }

    @Test fun onNetworkAvailableReconnectsAtOnce() {
        val f = fx()
        val pool = newPool()
        val m = manager(f, pool)
        m.ensure(f.server.id); upFor(m, f.server.id)
        m.onNetworkLost()
        waitUntil(10_000, "DownNetwork") { m.state(f.server.id).value == LinkState.DownNetwork && pool.liveCount(TransportRole.EXEC) == 0 }
        m.onNetworkAvailable()
        waitUntil(15_000, "Up again") { m.state(f.server.id).value is LinkState.Up }
    }

    @Test fun aClosedTransportStaysClosedUntilAnExplicitEnsure() {
        val f = fx()
        val pool = newPool()
        val m = manager(f, pool)
        m.ensure(f.server.id); upFor(m, f.server.id)
        m.close(f.server.id)
        assertEquals(LinkState.Closed, m.state(f.server.id).value)
        assertNull(runBlocking { m.await(f.server.id, 300) }, "await does not revive a closed transport")
        m.onNetworkAvailable()
        Thread.sleep(600)
        assertEquals(LinkState.Closed, m.state(f.server.id).value, "neither does the network callback")
        waitUntil(10_000, "the lease to be released") { pool.liveCount(TransportRole.EXEC) == 0 }
        m.ensure(f.server.id)
        upFor(m, f.server.id)
    }

    @Test fun closingTheTransportDropsItsSessionAndTheForwardOwnership() {
        val f = fx()
        val pool = newPool()
        val m = manager(f, pool)
        m.ensure(f.server.id); upFor(m, f.server.id)
        assertTrue(pool.execForwardsOwned(f.server.id))
        m.close(f.server.id)
        assertNull(m.session(f.server.id), "no session is served after close")
        assertTrue(!pool.execForwardsOwned(f.server.id), "the tabs bind their own forwards again")
    }

    @Test fun closeBumpsTheGenerationAndAWaiterForAPermitIsTurnedAwayAtOnce() {
        val f = fx()
        val pool = newPool()
        val m = manager(f, pool); m.ensure(f.server.id); upFor(m, f.server.id)
        val gate = GatedExecChannels(m, limits = GatedExecChannels.Limits(short = 1, long = 1))
        val gen = m.generation(f.server.id)
        runBlocking {
            val holder = async(Dispatchers.IO) { runCatching { PooledRemoteExec(gate).run(f.server.id, "sleep 3", totalMs = 30_000) } }
            waitUntil(10_000, "the permit to be taken") { gate.held(f.server.id).first == 1 }
            val waiter = async(Dispatchers.IO) { runCatching { gate.use(f.server.id, ExecKind.SHORT, acquireTimeoutMs = 20_000) { 1 } } }
            delay(300)
            val t0 = System.nanoTime()
            m.close(f.server.id)
            assertTrue(m.generation(f.server.id) > gen, "close is a generation change")
            val r = withTimeout(10_000) { waiter.await() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val e = r.exceptionOrNull() as? ExecUnavailable
            assertNotNull(e, "the waiter must fail, not get a permit on a closed transport")
            assertEquals(ExecUnavailableReason.NOT_UP, e.reason, e.message)
            assertTrue(ms < 4_000, "told at once, not after its 20 s acquire timeout (took $ms ms)")
            holder.cancel()
        }
    }

    /** close() and ensure() from two threads: whatever the interleaving, the last ensure leaves a working, owned transport. */
    @Test fun aCloseRacingAnEnsureNeverLeavesAnUpTransportWithoutASession() {
        val f = fx()
        val pool = newPool()
        val m = manager(f, pool)
        m.ensure(f.server.id); upFor(m, f.server.id)
        repeat(25) {
            val closer = Thread { m.close(f.server.id) }
            val ensurer = Thread { Thread.sleep((it % 3).toLong()); m.ensure(f.server.id) }
            closer.start(); ensurer.start(); closer.join(); ensurer.join()
        }
        m.ensure(f.server.id)   // the final word
        val s = upFor(m, f.server.id)
        assertTrue(m.state(f.server.id).value is LinkState.Up)
        assertTrue(pool.execForwardsOwned(f.server.id), "ownership of the forwards followed the last ensure")
        waitUntil(15_000, "exactly one exec transport to remain") { pool.liveCount(TransportRole.EXEC) == 1 }
        assertSame(s, m.session(f.server.id), "the session served is the live one")
    }

    @Test fun aConnectFailureWhileTheNetworkIsGoneIsNoTailscaleStrikeButOneOtherwiseIs() {
        val f = fx()
        val strikes = CopyOnWriteArrayList<Long>()
        val deadPort = ServerSocket(0).use { it.localPort }   // nothing listens there
        val broken = java.util.concurrent.atomic.AtomicBoolean(false)
        val ts = f.server.copy(tailscaleHost = "127.0.0.1")
        val resolve: suspend (SshServer) -> SshServer = { if (broken.get()) it.copy(host = it.tailscaleHost, port = deadPort) else it.copy(host = it.tailscaleHost) }
        val pool = newPool()
        val m = manager(f, pool, ts, resolve = resolve, onEarlyDeath = { _, _, life -> strikes += life })
        m.ensure(ts.id); upFor(m, ts.id)
        broken.set(true)
        m.onNetworkLost()
        fun failedAttempts() = logLines.count { it.contains("connect failed") }
        // every caller that wants the transport nudges one attempt (at most every 2 s); each one fails
        val t0 = System.nanoTime()
        while (failedAttempts() < 2 && (System.nanoTime() - t0) / 1_000_000 < 20_000) { m.session(ts.id); Thread.sleep(200) }
        assertTrue(failedAttempts() >= 2, "two failed attempts while the network is gone")
        assertEquals(emptyList(), strikes.toList(), "attempts without a network say nothing about Tailscale")

        // control: the network is there and the TS endpoint refuses => a strike, like for a tab
        val other = newPool()
        val c = manager(f, other, ts, resolve = { it.copy(host = it.tailscaleHost, port = deadPort) }, onEarlyDeath = { _, _, life -> strikes += life })
        c.ensure(ts.id)
        waitUntil(20_000, "a connect failure with the network up to strike") { strikes.isNotEmpty() }
    }

    @Test fun aTailscaleTransportThatStaysUpIsReportedStableOnceAndADirectOneNever() {
        val f = fx()
        val stable = CopyOnWriteArrayList<String>()
        val ts = f.server.copy(tailscaleHost = "127.0.0.1")
        val m = manager(f, newPool(), ts, resolve = { it.copy(host = it.tailscaleHost) }, onStable = { _, eff -> stable += eff.host })
        m.ensure(ts.id); upFor(m, ts.id)
        waitUntil(10_000, "the stable report") { stable.isNotEmpty() }
        Thread.sleep(800)
        assertEquals(1, stable.size, "once per transport")
        val directStable = CopyOnWriteArrayList<String>()
        val d = manager(f, newPool(), onStable = { _, eff -> directStable += eff.host })
        d.ensure(f.server.id); upFor(d, f.server.id)
        Thread.sleep(1_800)
        assertEquals(emptyList(), directStable.toList(), "only the Tailscale path needs proving")
    }

    @Test fun aTransportThatDiesBeforeTheStableWindowIsNeverReportedStable() {
        val f = fx()
        val stable = CopyOnWriteArrayList<String>()
        val ts = f.server.copy(tailscaleHost = "127.0.0.1")
        val pool = newPool()
        val m = manager(f, pool, ts, resolve = { it.copy(host = it.tailscaleHost) }, onStable = { _, eff -> stable += eff.host })
        m.ensure(ts.id); upFor(m, ts.id)
        pool.teardownAll()   // dies within ~200 ms, well before the 700 ms window
        Thread.sleep(500)
        assertEquals(emptyList(), stable.toList())
    }

    @Test fun inTheBackgroundANudgeWaitsHalfTheRetryPeriodNotTwoSeconds() {
        val f = fx()
        val now = java.util.concurrent.atomic.AtomicLong(0)
        val pool = newPool()
        val m = manager(f, pool, clock = { now.get() })
        m.ensure(f.server.id); upFor(m, f.server.id)
        m.background = true
        m.onNetworkLost()
        waitUntil(10_000, "DownNetwork with the transport released") { m.state(f.server.id).value == LinkState.DownNetwork && pool.liveCount(TransportRole.EXEC) == 0 }
        now.set(3_000)   // 3 s since the last attempt: a foreground nudge would connect, a background one (15 s) must not
        repeat(10) { m.session(f.server.id); Thread.sleep(100) }
        Thread.sleep(1_000)
        assertEquals(LinkState.DownNetwork, m.state(f.server.id).value, "no connect attempt in the background after 3 s")
        now.set(16_000)
        m.session(f.server.id)
        waitUntil(15_000, "the nudge after half the background retry period") { m.state(f.server.id).value is LinkState.Up }
    }

    // ------------------------------------------------------------------ channel budget

    private fun hammer(exec: PooledRemoteExec, id: String, n: Int): List<Result<*>> = runBlocking {
        withTimeout(90_000) {
            (1..n).map { i -> async(Dispatchers.IO) { runCatching { exec.run(id, "sleep 0.4; echo $i", totalMs = 30_000) } } }.awaitAll()
        }
    }

    @Test fun execChannelsNeverExceedTheirPermitsUnderTwentyConcurrentRuns() {
        val f = fx("MaxSessions 4")
        val pool = newPool()
        val m = manager(f, pool); m.ensure(f.server.id); upFor(m, f.server.id)
        val gate = GatedExecChannels(m, limits = GatedExecChannels.Limits(short = 3, long = 1))
        val results = hammer(PooledRemoteExec(gate), f.server.id, 20)
        assertEquals(0, results.count { it.isFailure }, "gated: every run succeeds: ${results.mapNotNull { it.exceptionOrNull()?.message }}")
        assertEquals(0, f.refusedChannelOpens(), "the gate kept the server from ever refusing a channel")
        assertEquals(Triple(0, 0, 0), gate.held(f.server.id))
    }

    /** Control: the same load on the ungated channels is refused by sshd (this is what the gate prevents). */
    @Test fun withoutTheGateTheSameLoadIsRefusedByTheServer() {
        val f = fx("MaxSessions 4")
        val s = f.openRawSession()
        try {
            val results = hammer(PooledRemoteExec(UngatedExecChannels { s }, refusedRetryDelayMs = 1), f.server.id, 20)
            assertTrue(f.refusedChannelOpens() > 0, "ungated: sshd refused ${f.refusedChannelOpens()} channels")
            assertTrue(results.any { it.isFailure })
        } finally { s.disconnect() }
    }

    @Test fun theExecGateTypesAMissingTransportAsNotUp() {
        val f = fx()
        val pool = newPool()
        val m = manager(f, pool)   // ensure() never called
        val e = assertFailsWith<ExecUnavailable> { runBlocking { PooledRemoteExec(GatedExecChannels(m, upWaitMs = 100)).run(f.server.id, "true") } }
        assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
    }

    // ------------------------------------------------------------------ exec error contract on real channels

    /** The transport dies under a running command => IO, never the partial output. */
    @Test fun aTransportDeathMidCommandIsIoNeverAPartialResult() {
        val f = fx()
        val s = f.openRawSession()
        val exec = PooledRemoteExec(UngatedExecChannels { s })
        Thread { Thread.sleep(700); s.disconnect() }.apply { isDaemon = true; start() }
        val e = assertFailsWith<ExecUnavailable> { runBlocking { exec.run("srv", "echo part; sleep 20", totalMs = 15_000) } }
        assertEquals(ExecUnavailableReason.IO, e.reason, e.message)
    }

    /** Stdin stops being fed once the command has exited without reading it all. */
    @Test fun pacedStdinStopsWhenTheRemoteCommandHasExited() {
        val f = fx()
        val s = f.openRawSession()
        try {
            val exec = PooledRemoteExec(UngatedExecChannels { s })
            val t0 = System.nanoTime()
            val r = runBlocking { exec.run("srv", "head -c 10 > /dev/null; exit 0", stdin = ByteArray(3_000_000), paceStdin = com.clauderemote.session.service.Pace(4096, 5)) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(0, r.exitStatus)
            assertTrue(ms < 3_000, "3 MB paced at 4 KB / 5 ms would take ~3.6 s if the writer ignored the exit (took $ms ms)")
        } finally { s.disconnect() }
    }

    /** Spill: a MaxSessions refusal that clears a moment later is retried once, not surfaced. */
    @Test fun aRefusalThatClearsWithinTheRetryDelayIsAbsorbed() {
        val f = fx("MaxSessions 4")
        val s = f.openRawSession()
        // `cat` ends when its channel closes (a `sleep` would keep the slot occupied after the client disconnect)
        val held = (1..4).map { (s.openChannel("exec") as ChannelExec).also { c -> c.setCommand("cat"); c.connect(5000) } }
        try {
            Thread { Thread.sleep(80); held[0].disconnect() }.apply { isDaemon = true; start() }
            val r = runBlocking { PooledRemoteExec(UngatedExecChannels { s }).run("srv", "echo after-spill") }
            assertEquals("after-spill\n", r.stdout)
            assertTrue(f.refusedChannelOpens() >= 1, "the first attempt really was refused")
        } finally { held.forEach { it.disconnect() }; s.disconnect() }
    }

    // ------------------------------------------------------------------ keepalive

    @Test fun theKeepaliveIntervalFollowsTheTransportsTheyPoolOpensLater() {
        val f = fx()
        val pool = newPool()
        val first = connectAll(pool, f.server, 1)[0]
        first.setKeepAliveInterval(60_000)
        assertEquals(60_000, first.getSession()!!.serverAliveInterval)
        // 8 more tabs overflow the first transport (cap 8): the NEW transport must start with 60 s, not 10 s
        val more = connectAll(pool, f.server, 8)
        val sessions = (listOf(first) + more).mapNotNull { it.getSession() }.distinct()
        assertEquals(2, sessions.size, "a second transport was connected")
        assertTrue(sessions.all { it.serverAliveInterval == 60_000 }, "intervals: ${sessions.map { it.serverAliveInterval }}")
    }

    @Test fun aStandaloneManagerConnectsWithTheIntervalItWasGiven() {
        val f = fx()
        val m = SshManager(storage).also { managers += it }
        m.setKeepAliveInterval(45_000)
        val s = runBlocking { m.connect(f.server, {}, {}) }
        assertEquals(45_000, s.serverAliveInterval)
    }

    @Test fun poolAndExecManagerApplyKeepaliveToEveryTransport() {
        val f = fx()
        val pool = newPool()
        val m = manager(f, pool); m.ensure(f.server.id)
        val exec = upFor(m, f.server.id)
        val shell = connectAll(pool, f.server, 1)[0].getSession()!!
        m.setKeepAlive(30_000)
        assertEquals(30_000, exec.serverAliveInterval)
        assertEquals(30_000, shell.serverAliveInterval, "the pool-wide rule reaches the shell transport too")
        assertNotSame(exec, shell)
    }
}
