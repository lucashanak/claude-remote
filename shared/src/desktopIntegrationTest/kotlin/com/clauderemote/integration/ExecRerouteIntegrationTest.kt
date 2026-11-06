package com.clauderemote.integration

import com.clauderemote.connection.ServerTransportPool
import com.clauderemote.connection.SshManager
import com.clauderemote.connection.TransportRole
import com.clauderemote.model.PortForward
import com.clauderemote.model.ServerTransport
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.LinkState
import com.clauderemote.session.service.PoolExecTransportManager
import com.clauderemote.session.service.bindForwardOnJsch
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Route and stability of the exec transport against a REAL sshd (dávka B, celek B2):
 *  - F1: an exec transport that came up on the fallback endpoint (Cloudflare on the phone; here the base host
 *    `localhost`) goes back to the direct endpoint (`tailscaleHost`, here `127.0.0.1`) once the resolver offers it
 *    twice in a row, make-before-break, with hysteresis, never in the background, and not again for 5 minutes;
 *  - F2: the `up ... in Xms` line measures monotonic time, so a frozen process (Doze) no longer reads as a 400 s connect;
 *  - F4: a dead tab says WHY (`cause=`): our own teardown, or the transport's end as jsch saw it;
 *  - NN5/N-C: a throttled lease waits for ANY handshake of its key (not the oldest), and the shell cap is re-read on
 *    every pass, so an exec transport that comes up during a reconnect wave packs the rest of the wave by 8.
 *
 * Time in the F1 tests: the manager's clock runs [SPEED] times faster than real time (fake clock), the 5 s
 * watch poll is [POLL_MS] real ms, so the policy's real constants (60 s dwell, 60 s per probe, 5 min cooldown)
 * pass in about a second. The resolver is injected: it returns the direct endpoint while [lan] says so and
 * records every call (one call = one probe).
 */
class ExecRerouteIntegrationTest {
    private var fixture: SshdFixture? = null
    private val storage = ServerStorage(InMemoryKeyValueStore())
    private val pools = CopyOnWriteArrayList<ServerTransportPool>()
    private val managers = CopyOnWriteArrayList<SshManager>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedPlatformLog = FileLogger.platformLog

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

    private fun fx(extraSshdConfig: String = ""): SshdFixture {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        return SshdFixture.start(extraSshdConfig).also { fixture = it }
    }

    private fun newPool(execWired: () -> Boolean = { true }) =
        ServerTransportPool(storage, execWired = { execWired() }).also { pools += it }

    // ------------------------------------------------------------------ F1 harness

    /** A clock [SPEED]× faster than real time, which a test can also jump (a frozen process waking up). */
    private class FastClock {
        private val start = System.nanoTime()
        val jumped = AtomicLong(0)
        fun now(): Long = 1_000_000L + (System.nanoTime() - start) / 1_000_000 * SPEED + jumped.get()
    }

    /** The fallback (base) endpoint is `localhost`, the direct one `127.0.0.1`: the same sshd, two transport keys. */
    private fun rerouteServer(f: SshdFixture) =
        f.server.copy(host = "localhost", tailscaleHost = "127.0.0.1", transport = ServerTransport.AUTO)

    private class Probe(private val clock: FastClock) {
        val lan = AtomicBoolean(false)
        /** Fake-clock time of every resolve call (= every probe). */
        val calls = CopyOnWriteArrayList<Long>()
        val resolve: suspend (SshServer) -> SshServer = { s ->
            calls += clock.now()
            if (lan.get()) s.forTransport(ServerTransport.TAILSCALE) else s
        }
    }

    private fun manager(
        server: SshServer,
        pool: ServerTransportPool,
        clock: FastClock,
        resolve: suspend (SshServer) -> SshServer,
        strikes: MutableList<Long> = CopyOnWriteArrayList(),
        rerouteDrainMs: Long = 5_000,
        bindForward: (com.jcraft.jsch.Session, PortForward) -> Unit = ::bindForwardOnJsch,
    ) = PoolExecTransportManager(
        scope, { pool }, { server.takeIf { s -> s.id == it } }, resolve,
        onEarlyDeath = { _, _, life -> strikes += life },
        execEnabled = { true }, pollMs = POLL_MS, clock = clock::now, rerouteDrainMs = rerouteDrainMs,
        bindForward = bindForward,
    )

    private fun upOn(m: PoolExecTransportManager, id: String, host: String) {
        waitUntil(30_000, "the exec transport to be up on $host") {
            m.state(id).value is LinkState.Up && m.session(id)?.host == host
        }
    }

    // ------------------------------------------------------------------ F1

    /**
     * The phone's case: the exec transport came up over the fallback while the LAN was not usable yet and stayed there for
     * up to 23 minutes (1 381 200 ms "down after ... via CF"), because nothing ever re-decided its route. On c2915d1 it
     * stays on the fallback for good. Now it moves after two good probes, make-before-break: the services never see
     * "no session", the generation moves by exactly one, the old transport is let go, and nothing counts as a strike.
     */
    @Test fun anExecTransportOnTheFallbackMovesToTheDirectEndpointMakeBeforeBreak() {
        val f = fx()
        val server = rerouteServer(f)
        val pool = newPool()
        val clock = FastClock()
        val probe = Probe(clock)
        val strikes = CopyOnWriteArrayList<Long>()
        val m = manager(server, pool, clock, probe.resolve, strikes)
        m.ensure(server.id)
        upOn(m, server.id, "localhost")
        val gen0 = m.generation(server.id)
        // the LAN comes back: from now on every probe succeeds
        probe.lan.set(true)
        val gaps = AtomicInteger()
        val sampling = AtomicBoolean(true)
        val sampler = Thread {
            while (sampling.get()) { if (m.session(server.id) == null) gaps.incrementAndGet(); Thread.sleep(2) }
        }.apply { isDaemon = true; start() }
        try {
            upOn(m, server.id, "127.0.0.1")
        } finally { sampling.set(false); sampler.join(1_000) }
        assertEquals(0, gaps.get(), "make-before-break: the exec session was never missing during the move")
        assertEquals(gen0 + 1, m.generation(server.id), "one move = one generation (permits of the old transport are void)")
        assertTrue(strikes.isEmpty(), "a move is no strike: $strikes")
        assertTrue(logLines.any { Regex("""Exec transport integration rerouted DIRECT -> TS in \d+ms route=lan""").containsMatchIn(it) }, logLines.filter { it.contains("Exec transport") }.toString())
        waitUntil(30_000, "the old transport to be released") { pool.liveCount(TransportRole.EXEC) == 1 }
        assertEquals("127.0.0.1", m.session(server.id)?.host)
    }

    /** Hysteresis: a probe that succeeds only every other time never moves the transport (2 good probes IN A ROW are needed). */
    @Test fun aProbeThatSucceedsEveryOtherTimeNeverMovesTheExecTransport() {
        val f = fx()
        val server = rerouteServer(f)
        val pool = newPool()
        val clock = FastClock()
        val calls = AtomicInteger()
        val resolve: suspend (SshServer) -> SshServer = { s ->
            // the connect (call 1) and then alternating: fail, ok, fail, ok, ...
            if (calls.incrementAndGet() % 2 == 1) s else s.forTransport(ServerTransport.TAILSCALE)
        }
        val m = manager(server, pool, clock, resolve)
        m.ensure(server.id)
        upOn(m, server.id, "localhost")
        val t0 = clock.now()
        waitUntil(30_000, "five probes") { calls.get() >= 6 }
        assertEquals("localhost", m.session(server.id)?.host, "never two good probes in a row: no move")
        assertEquals(1, pool.liveCount(TransportRole.EXEC), "and no transport was even opened for one")
        // pace: one probe per 60 s at most (the first one after a 60 s dwell)
        val spanS = (clock.now() - t0) / 1000
        assertTrue(calls.get() - 1 <= spanS / 60 + 1, "${calls.get() - 1} probes in ${spanS}s (fake)")
    }

    /** Never in the background: no probe, no move. Back in the foreground the move happens. */
    @Test fun nothingIsProbedOrMovedInTheBackground() {
        val f = fx()
        val server = rerouteServer(f)
        val pool = newPool()
        val clock = FastClock()
        val probe = Probe(clock)
        val m = manager(server, pool, clock, probe.resolve)
        m.background = true
        m.ensure(server.id)
        upOn(m, server.id, "localhost")
        probe.lan.set(true)
        val t0 = clock.now()
        while (clock.now() - t0 < 300_000) Thread.sleep(50)   // 5 fake minutes
        assertEquals(1, probe.calls.size, "only the connect resolved; no probe in the background: ${probe.calls}")
        assertEquals("localhost", m.session(server.id)?.host)
        m.background = false
        upOn(m, server.id, "127.0.0.1")
    }

    /**
     * A process frozen by Doze wakes with every timer "expired" (the phone log has 281-1 320 s gaps). The first probe
     * must still wait a full period after the wake: no new work joins the reconnect wave that the wake starts.
     */
    @Test fun aWakeFromALongFreezeDoesNotProbeAtOnce() {
        val f = fx()
        val server = rerouteServer(f)
        val pool = newPool()
        val clock = FastClock()
        val probe = Probe(clock)
        val m = manager(server, pool, clock, probe.resolve)
        m.ensure(server.id)
        upOn(m, server.id, "localhost")
        // freeze before the dwell is over, then wake 400 s later
        clock.jumped.addAndGet(400_000)
        val wokeAt = clock.now()
        probe.lan.set(true)
        upOn(m, server.id, "127.0.0.1")
        val probes = probe.calls.drop(1)
        assertTrue(probes.isNotEmpty())
        assertTrue(probes.first() - wokeAt >= 50_000, "the first probe ran ${probes.first() - wokeAt} ms (fake) after the wake")
    }

    /** After a move the route is not touched again for 5 minutes, even if the transport died and came back on the fallback. */
    @Test fun aMoveIsFollowedByAFiveMinuteCooldown() {
        val f = fx()
        val server = rerouteServer(f)
        val pool = newPool()
        val clock = FastClock()
        val probe = Probe(clock)
        // a short drain: the default 5 s real are 250 s on this clock and would hide a missing cooldown (M-cooldown survived it)
        val m = manager(server, pool, clock, probe.resolve, rerouteDrainMs = 50)
        m.ensure(server.id)
        upOn(m, server.id, "localhost")
        probe.lan.set(true)
        upOn(m, server.id, "127.0.0.1")
        val movedAt = clock.now()
        waitUntil(30_000, "the old transport to be released") { pool.liveCount(TransportRole.EXEC) == 1 }
        // the direct path breaks: the exec transport dies and comes back on the fallback
        probe.lan.set(false)
        pool.teardownAll()
        upOn(m, server.id, "localhost")
        probe.lan.set(true)
        upOn(m, server.id, "127.0.0.1")
        val secondMoveAt = clock.now()
        assertTrue(secondMoveAt - movedAt >= 300_000, "second move ${(secondMoveAt - movedAt) / 1000}s (fake) after the first")
    }

    /** The direct endpoint answers the probe but its handshake fails: no strike, the fallback stays, and no retry for 5 min. */
    @Test fun aFailedMoveKeepsTheFallbackAndIsNoStrike() {
        val f = fx()
        val dead = ServerSocket(0).use { it.localPort }
        val server = rerouteServer(f)
        val pool = newPool()
        val clock = FastClock()
        val probe = Probe(clock)
        val strikes = CopyOnWriteArrayList<Long>()
        // the probe says "direct is fine", but the direct endpoint refuses connections
        val resolve: suspend (SshServer) -> SshServer = { s -> probe.resolve(s).let { e -> if (e.host == "127.0.0.1") e.copy(port = dead) else e } }
        val m = manager(server, pool, clock, resolve, strikes)
        m.ensure(server.id)
        upOn(m, server.id, "localhost")
        val gen0 = m.generation(server.id)
        probe.lan.set(true)
        waitUntil(30_000, "the move to be tried") { logLines.any { it.contains("reroute") && it.contains("failed") } }
        val triedAt = clock.now()
        while (clock.now() - triedAt < 240_000) Thread.sleep(50)   // 4 fake minutes
        assertEquals(1, logLines.count { it.contains("reroute") && it.contains("failed") }, "no second try within the cooldown: " + logLines.filter { it.contains("reroute") })
        assertEquals("localhost", m.session(server.id)?.host, "the fallback transport stays")
        assertEquals(gen0, m.generation(server.id), "nothing was published")
        assertTrue(strikes.isEmpty(), "a failed move is no strike: $strikes")
    }

    /** The L forward moves with the transport: it answers through the new one (rebinding a port the old one still held would fail). */
    @Test fun anLForwardKeepsWorkingAfterTheMove() {
        val f = fx()
        val remote = ServerSocket(0)
        val local = ServerSocket(0).use { it.localPort }
        val acceptor = Thread {
            while (!remote.isClosed) runCatching { remote.accept().use { c -> c.getOutputStream().write("PONG\n".toByteArray()) } }
        }.apply { isDaemon = true; start() }
        try {
            val server = rerouteServer(f).copy(portForwards = listOf(PortForward("L", local, "127.0.0.1", remote.localPort)))
            val pool = newPool()
            val clock = FastClock()
            val probe = Probe(clock)
            val m = manager(server, pool, clock, probe.resolve)
            m.ensure(server.id)
            upOn(m, server.id, "localhost")
            fun ask() = Socket("127.0.0.1", local).use { it.soTimeout = 5_000; it.getInputStream().bufferedReader().readLine() }
            assertEquals("PONG", ask())
            probe.lan.set(true)
            upOn(m, server.id, "127.0.0.1")
            waitUntil(30_000, "the old transport to be released") { pool.liveCount(TransportRole.EXEC) == 1 }
            waitUntil(10_000, "the forward to answer through the new transport") { runCatching { ask() == "PONG" }.getOrDefault(false) }
            assertTrue(logLines.none { it.contains("port forward L $local") && (it.contains("failed") || it.contains("not bound")) },
                logLines.filter { it.contains("port forward") }.toString())
        } finally {
            remote.close(); acceptor.interrupt()
        }
    }

    /**
     * Takes 127.0.0.1:[port] (waiting up to 5 s while its previous owner still has it: the old transport's listener, or the
     * old sshd process that has not yet seen the unconfirmed `cancel-tcpip-forward`) and frees it [holdMs] later.
     */
    private fun holdPort(port: Int, holdMs: Long) {
        val deadline = System.nanoTime() + 5_000_000_000L
        var held: ServerSocket? = null
        while (held == null) {
            held = runCatching { ServerSocket().apply { bind(java.net.InetSocketAddress("127.0.0.1", port)) } }.getOrNull()
            if (held == null) { check(System.nanoTime() < deadline) { "port $port was never freed" }; Thread.sleep(20) }
        }
        val h = held
        Thread { Thread.sleep(holdMs); h.close() }.apply { isDaemon = true; start() }
    }

    /**
     * R4 (review B-1): a move binds the L forward on the new transport while its port is still taken for a moment (here
     * deterministically: 500 ms by the test, in the field the old listener under load). On dbd5dc6 the one failed bind
     * was only logged and the forward stayed dead for the transport's whole life. Now the bind is retried (5 x 200 ms)
     * within the move: the forward answers through the new transport and is never given up.
     */
    @Test fun anLForwardWhosePortIsBrieflyTakenIsBoundOnARetryWithinTheMove() {
        val f = fx()
        val remote = ServerSocket(0)
        val local = ServerSocket(0).use { it.localPort }
        val acceptor = Thread {
            while (!remote.isClosed) runCatching { remote.accept().use { c -> c.getOutputStream().write("PONG\n".toByteArray()) } }
        }.apply { isDaemon = true; start() }
        try {
            val server = rerouteServer(f).copy(portForwards = listOf(PortForward("L", local, "127.0.0.1", remote.localPort)))
            val pool = newPool()
            val clock = FastClock()
            val probe = Probe(clock)
            val taken = AtomicBoolean(false)
            val m = manager(server, pool, clock, probe.resolve, rerouteDrainMs = 50, bindForward = { s, pf ->
                if (s.host == "127.0.0.1" && !taken.getAndSet(true)) holdPort(pf.localPort, 500)
                bindForwardOnJsch(s, pf)
            })
            m.ensure(server.id)
            upOn(m, server.id, "localhost")
            fun ask() = Socket("127.0.0.1", local).use { it.soTimeout = 2_000; it.getInputStream().bufferedReader().readLine() }
            assertEquals("PONG", ask())
            probe.lan.set(true)
            upOn(m, server.id, "127.0.0.1")
            waitUntil(30_000, "the move's L bind") { logLines.any { it.contains("rerouted") } }
            assertTrue(taken.get(), "the port was taken while the move bound it")
            waitUntil(10_000, "the forward to answer through the new transport") { runCatching { ask() == "PONG" }.getOrDefault(false) }
            val fwd = logLines.filter { it.contains("port forward") }
            assertTrue(fwd.any { Regex("""port forward L $local <-> 127\.0\.0\.1:\d+ \(try [2-5]/5\)""").containsMatchIn(it) }, "bound on a retry: $fwd")
            assertTrue(fwd.none { it.contains("not bound") }, "never given up within the move: $fwd")
        } finally {
            remote.close(); acceptor.interrupt()
        }
    }

    /**
     * R4 (review B-1): jsch cancels an R forward with want-reply=0, so the OLD sshd process may still hold the remote port
     * when the new transport binds it (2 of 40 rounds in the adversarial probe). Here the port stays taken for 2.5 s,
     * longer than the move's 5 retries: the move gives the forward up for now, and the watch re-binds it once the port is
     * free. On dbd5dc6 the R forward stayed dead until the transport died.
     */
    @Test fun anRForwardLostInTheMoveIsBoundAgainOnceTheOldTransportLetsThePortGo() {
        val f = fx("AddressFamily inet")   // remote forwards on 127.0.0.1 only, the address the test holds
        val back = ServerSocket(0)
        val remotePort = ServerSocket(0).use { it.localPort }
        val acceptor = Thread {
            while (!back.isClosed) runCatching { back.accept().use { c -> c.getOutputStream().write("PONG\n".toByteArray()) } }
        }.apply { isDaemon = true; start() }
        try {
            val server = rerouteServer(f).copy(portForwards = listOf(PortForward("R", back.localPort, "127.0.0.1", remotePort)))
            val pool = newPool()
            val clock = FastClock()
            val probe = Probe(clock)
            val taken = AtomicBoolean(false)
            val m = manager(server, pool, clock, probe.resolve, rerouteDrainMs = 50, bindForward = { s, pf ->
                if (s.host == "127.0.0.1" && !taken.getAndSet(true)) holdPort(pf.remotePort, 2_500)
                bindForwardOnJsch(s, pf)
            })
            m.ensure(server.id)
            upOn(m, server.id, "localhost")
            fun ask() = Socket("127.0.0.1", remotePort).use { it.soTimeout = 1_000; it.getInputStream().bufferedReader().readLine() }
            waitUntil(10_000, "the R forward on the fallback transport") { runCatching { ask() == "PONG" }.getOrDefault(false) }
            probe.lan.set(true)
            upOn(m, server.id, "127.0.0.1")
            waitUntil(30_000, "the move") { logLines.any { it.contains("rerouted") } }
            assertTrue(taken.get(), "the port was taken while the move bound it")
            waitUntil(15_000, "the R forward to answer through the new transport") { runCatching { ask() == "PONG" }.getOrDefault(false) }
            val fwd = logLines.filter { it.contains("port forward") }
            assertTrue(fwd.any { it.contains("port forward R ${back.localPort} ") && it.contains("not bound after 5 tries") }, "the move gave it up for now: $fwd")
            assertTrue(fwd.any { it.contains("port forward R ${back.localPort} ") && it.contains("bound again") }, "the watch bound it again: $fwd")
            assertEquals("127.0.0.1", m.session(server.id)?.host, "still on the new transport")
        } finally {
            back.close(); acceptor.interrupt()
        }
    }

    /**
     * Review B-2 T2: the R forward of a move with NOTHING holding its port and the production drain (5 s). The old transport
     * frees the port (jsch: `cancel-tcpip-forward`, no reply awaited), the new one binds it within the move's retries: no
     * "not bound" line, and the forward answers through the new transport while the old one is still draining. A move that
     * did not free the old R forward would find the port taken by the old sshd for the whole drain and give it up.
     */
    @Test fun anRForwardMovesWithinTheMoveWhileTheOldTransportStillDrains() {
        val f = fx()
        val back = ServerSocket(0)
        val remotePort = ServerSocket(0).use { it.localPort }
        val acceptor = Thread {
            while (!back.isClosed) runCatching { back.accept().use { c -> c.getOutputStream().write("PONG\n".toByteArray()) } }
        }.apply { isDaemon = true; start() }
        try {
            val server = rerouteServer(f).copy(portForwards = listOf(PortForward("R", back.localPort, "127.0.0.1", remotePort)))
            val pool = newPool()
            val clock = FastClock()
            val probe = Probe(clock)
            val m = manager(server, pool, clock, probe.resolve)   // rerouteDrainMs = 5 000, as in production
            m.ensure(server.id)
            upOn(m, server.id, "localhost")
            fun ask() = Socket("127.0.0.1", remotePort).use { it.soTimeout = 1_000; it.getInputStream().bufferedReader().readLine() }
            waitUntil(10_000, "the R forward on the fallback transport") { runCatching { ask() == "PONG" }.getOrDefault(false) }
            probe.lan.set(true)
            waitUntil(30_000, "the move") { logLines.any { it.contains("rerouted") } }
            waitUntil(4_000, "the R forward to answer through the new transport within the drain") { runCatching { ask() == "PONG" }.getOrDefault(false) }
            assertEquals(2, pool.liveCount(TransportRole.EXEC), "the old transport is still draining when the forward answers")
            val fwd = logLines.filter { it.contains("port forward R ${back.localPort} ") }
            assertTrue(fwd.none { it.contains("not bound") || it.contains("failed") }, "bound within the move: $fwd")
            assertEquals("127.0.0.1", m.session(server.id)?.host)
        } finally {
            back.close(); acceptor.interrupt()
        }
    }

    /**
     * Review B-2 adv-spojeni N1: a bind that does not get a quick answer (a transport that stalls without a RST: jsch waits
     * 10 x 1 s for the reply of each `tcpip-forward`) must not be retried within the move, or the move, and the watch it
     * runs in, stands still for 5 x 10 s (51 s measured on a stalled TCP proxy). Here every bind on the new transport takes
     * 1.2 s and fails, five times; then the port is free. The move tries it once and leaves it to the watch: it ends in
     * well under 3 s (on 851eb34: 5 x 1.2 s + 4 x 0.2 s = 6.8 s) and the watch binds the forward later.
     */
    @Test fun aSlowFailingBindIsNotRetriedWithinTheMoveTheWatchTakesIt() {
        val f = fx()
        val remote = ServerSocket(0)
        val local = ServerSocket(0).use { it.localPort }
        val acceptor = Thread {
            while (!remote.isClosed) runCatching { remote.accept().use { c -> c.getOutputStream().write("PONG\n".toByteArray()) } }
        }.apply { isDaemon = true; start() }
        try {
            val server = rerouteServer(f).copy(portForwards = listOf(PortForward("L", local, "127.0.0.1", remote.localPort)))
            val pool = newPool()
            val clock = FastClock()
            val probe = Probe(clock)
            val slow = AtomicInteger()
            val m = manager(server, pool, clock, probe.resolve, rerouteDrainMs = 50, bindForward = { s, pf ->
                if (s.host == "127.0.0.1" && slow.incrementAndGet() <= 5) {
                    Thread.sleep(1_200)
                    throw com.jcraft.jsch.JSchException("no reply to the forward request (test)")
                }
                bindForwardOnJsch(s, pf)
            })
            m.ensure(server.id)
            upOn(m, server.id, "localhost")
            probe.lan.set(true)
            waitUntil(30_000, "the move") { logLines.any { it.contains("rerouted") } }
            val moveMs = logLines.firstNotNullOf { Regex("""rerouted \S+ -> \S+ in (\d+)ms""").find(it) }.groupValues[1].toLong()
            assertTrue(moveMs < 3_000, "the move did not retry a slow bind: it took ${moveMs}ms; " + logLines.filter { it.contains("port forward") })
            fun ask() = Socket("127.0.0.1", local).use { it.soTimeout = 2_000; it.getInputStream().bufferedReader().readLine() }
            waitUntil(20_000, "the watch to bind the forward") { runCatching { ask() == "PONG" }.getOrDefault(false) }
            assertTrue(logLines.any { it.contains("port forward L $local") && it.contains("bound again") }, logLines.filter { it.contains("port forward") }.toString())
        } finally {
            remote.close(); acceptor.interrupt()
        }
    }

    /**
     * Review B-2 adv-spojeni N1, second half: once the new transport is down, every further bind fails, so the move does not
     * try again (on 851eb34 it tried all five times on the dead session). The watch then sees the dead transport and
     * reconnects; the forward is bound on the next transport.
     */
    @Test fun aBindOnATransportThatWentDownIsNotRetried() {
        val f = fx()
        val remote = ServerSocket(0)
        val local = ServerSocket(0).use { it.localPort }
        val acceptor = Thread {
            while (!remote.isClosed) runCatching { remote.accept().use { c -> c.getOutputStream().write("PONG\n".toByteArray()) } }
        }.apply { isDaemon = true; start() }
        try {
            val server = rerouteServer(f).copy(portForwards = listOf(PortForward("L", local, "127.0.0.1", remote.localPort)))
            val pool = newPool()
            val clock = FastClock()
            val probe = Probe(clock)
            val dead = java.util.concurrent.atomic.AtomicReference<com.jcraft.jsch.Session?>()
            val onDead = AtomicInteger()
            val m = manager(server, pool, clock, probe.resolve, rerouteDrainMs = 50, bindForward = { s, pf ->
                if (s.host == "127.0.0.1" && dead.compareAndSet(null, s)) s.disconnect()
                if (s === dead.get()) {
                    onDead.incrementAndGet()
                    // what jsch says for a request on a closed session (its L bind would not even notice, see PortWatcher)
                    throw com.jcraft.jsch.JSchException("session is down")
                }
                bindForwardOnJsch(s, pf)
            })
            m.ensure(server.id)
            upOn(m, server.id, "localhost")
            probe.lan.set(true)
            waitUntil(30_000, "the move") { logLines.any { it.contains("rerouted") } }
            assertEquals(1, onDead.get(), "binds tried on the transport that went down: " + logLines.filter { it.contains("port forward") })
            fun ask() = Socket("127.0.0.1", local).use { it.soTimeout = 2_000; it.getInputStream().bufferedReader().readLine() }
            waitUntil(30_000, "the forward on the next transport") { runCatching { ask() == "PONG" }.getOrDefault(false) }
        } finally {
            remote.close(); acceptor.interrupt()
        }
    }

    // ------------------------------------------------------------------ dávka B kolo 4 (review B-3 R3-2, kod OQ1, F1)

    /** The `<-> host:port` part of a forward's success line ("... bound again" when the watch bound it). */
    private fun boundLines(target: String) = logLines.filter { it.contains("port forward") && it.contains("<-> $target") }

    /**
     * Review B-3 adv-spojeni R3-2: on a transport that stalls without a RST, jsch waits 10 x 1 s for the reply of every R
     * request. Round 3 stopped retrying ONE such forward, but the move went on to the next forward on the same stalled
     * transport and waited again (adv14: 2 R forwards, moveMs 20 035). Now a bind that fails slowly (or on a transport
     * that is down) leaves the REST of the forwards to the watch. Here the first bind of an R forward on the new transport
     * takes 3 s and fails: the move waits once (on 90aefca twice, ~6 s), and the watch binds all three forwards later.
     */
    @Test fun aMoveWhoseFirstBindFailsSlowlyLeavesTheOtherForwardsToTheWatch() {
        val f = fx()
        val back = ServerSocket(0)
        val r1 = ServerSocket(0).use { it.localPort }
        val r2 = ServerSocket(0).use { it.localPort }
        val local = ServerSocket(0).use { it.localPort }
        try {
            val server = rerouteServer(f).copy(portForwards = listOf(
                PortForward("R", back.localPort, "127.0.0.1", r1),
                PortForward("R", back.localPort, "127.0.0.1", r2),
                PortForward("L", local, "127.0.0.1", back.localPort),
            ))
            val pool = newPool()
            val clock = FastClock()
            val probe = Probe(clock)
            val stalled = AtomicInteger()
            val m = manager(server, pool, clock, probe.resolve, rerouteDrainMs = 50, bindForward = { s, pf ->
                // the new transport stalls for the move's binds: no reply in time, then jsch's exception
                if (s.host == "127.0.0.1" && pf.type == "R" && logLines.none { it.contains("rerouted") }) {
                    stalled.incrementAndGet()
                    Thread.sleep(3_000)
                    throw com.jcraft.jsch.JSchException("remote port forwarding failed for listen port ${pf.remotePort} (test: no reply in time)")
                }
                bindForwardOnJsch(s, pf)
            })
            m.ensure(server.id)
            upOn(m, server.id, "localhost")
            probe.lan.set(true)
            waitUntil(60_000, "the move") { logLines.any { it.contains("rerouted") } }
            val moveMs = logLines.firstNotNullOf { Regex("""rerouted \S+ -> \S+ in (\d+)ms""").find(it) }.groupValues[1].toLong()
            val fwd = { logLines.filter { it.contains("port forward") || it.contains("forward(s)") } }
            assertEquals(1, stalled.get(), "slow binds the move waited for (it took ${moveMs}ms): ${fwd()}")
            assertTrue(moveMs < 4_500, "the move waited once, not once per R forward: it took ${moveMs}ms; ${fwd()}")
            waitUntil(20_000, "the watch to bind all three forwards: ${fwd()}") {
                listOf("127.0.0.1:$r1", "127.0.0.1:$r2", "127.0.0.1:${back.localPort}").all { t -> boundLines(t).any { it.contains("bound again") } }
            }
        } finally {
            back.close()
        }
    }

    /**
     * Review B-3 kod OQ1: the same for the connect and every pass of the watch (one try per forward there). A connect whose
     * first R bind fails slowly does not wait for the second one too: the `up` line's `forwards=` phase is one wait (on
     * 90aefca two), and the watch binds both forwards afterwards.
     */
    @Test fun aConnectWhoseFirstBindFailsSlowlyLeavesTheOtherForwardsToTheWatch() {
        val f = fx()
        val back = ServerSocket(0)
        val r1 = ServerSocket(0).use { it.localPort }
        val r2 = ServerSocket(0).use { it.localPort }
        try {
            val server = f.server.copy(portForwards = listOf(
                PortForward("R", back.localPort, "127.0.0.1", r1),
                PortForward("R", back.localPort, "127.0.0.1", r2),
            ))
            val pool = newPool()
            val stalled = AtomicInteger()
            val m = manager(server, pool, FastClock(), { it }, bindForward = { s, pf ->
                // the transport stalls for the connect's binds (the `up` line is written after them)
                if (logLines.none { it.contains(" up via ") }) {
                    stalled.incrementAndGet()
                    Thread.sleep(2_000)
                    throw com.jcraft.jsch.JSchException("remote port forwarding failed for listen port ${pf.remotePort} (test: no reply in time)")
                }
                bindForwardOnJsch(s, pf)
            })
            m.ensure(server.id)
            waitUntil(30_000, "the exec transport to be up") { logLines.any { it.contains(" up via ") } }
            val forwardsMs = Regex("""forwards=(\d+)ms""").find(logLines.first { it.contains(" up via ") })!!.groupValues[1].toLong()
            val fwd = { logLines.filter { it.contains("port forward") || it.contains("forward(s)") } }
            assertEquals(1, stalled.get(), "slow binds the connect waited for (forwards=${forwardsMs}ms): ${fwd()}")
            assertTrue(forwardsMs < 3_000, "the connect waited once, not once per R forward: forwards=${forwardsMs}ms; ${fwd()}")
            waitUntil(20_000, "the watch to bind both forwards: ${fwd()}") {
                listOf("127.0.0.1:$r1", "127.0.0.1:$r2").all { t -> boundLines(t).any { it.contains("bound again") } }
            }
        } finally {
            back.close()
        }
    }

    /**
     * Guard for the two tests above: the forwards a slow failure left untried go FIRST on the watch's next pass. Without
     * that, a forward that always fails slowly (its own fault, the transport is fine) would be tried first on every pass
     * and keep the watch from ever reaching the others.
     */
    @Test fun aForwardThatAlwaysFailsSlowlyDoesNotKeepTheWatchFromTheOthers() {
        val f = fx()
        val back = ServerSocket(0)
        val r1 = ServerSocket(0).use { it.localPort }
        val r2 = ServerSocket(0).use { it.localPort }
        try {
            val server = f.server.copy(portForwards = listOf(
                PortForward("R", back.localPort, "127.0.0.1", r1),
                PortForward("R", back.localPort, "127.0.0.1", r2),
            ))
            val pool = newPool()
            val m = manager(server, pool, FastClock(), { it }, bindForward = { s, pf ->
                if (pf.remotePort == r1) {
                    Thread.sleep(1_500)
                    throw com.jcraft.jsch.JSchException("remote port forwarding failed for listen port $r1 (test: always slow)")
                }
                bindForwardOnJsch(s, pf)
            })
            m.ensure(server.id)
            waitUntil(15_000, "the second forward bound although the first always fails slowly: " +
                logLines.filter { it.contains("port forward") || it.contains("forward(s)") }) { boundLines("127.0.0.1:$r2").isNotEmpty() }
            assertTrue(boundLines("127.0.0.1:$r1").isEmpty(), "the first one never binds")
        } finally {
            back.close()
        }
    }

    /**
     * Review B-3 testy-spojeni F1, the watch's end: the supervisor lets a dead transport go (log, release, back off) and
     * only then wrote `Down`, unguarded, so a `close()` in between (the last tab closed, the kill switch) ended as `Down`
     * instead of `Closed`. Deterministic: the clock read right after the supervisor's last `closed` check closes it.
     */
    @Test fun aCloseWhileTheSupervisorLetsADeadTransportGoLeavesItClosed() {
        val f = fx()
        val pool = newPool()
        val server = f.server
        val own = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val armed = AtomicBoolean(false)
        try {
            lateinit var m: PoolExecTransportManager
            m = PoolExecTransportManager(
                own, { pool }, { server.takeIf { s -> s.id == it } }, { it },
                execEnabled = { true }, pollMs = POLL_MS, stableAfterMs = 0,
                clock = {
                    // the first clock read after the transport died is the lifetime, past the supervisor's `closed` check
                    if (armed.compareAndSet(true, false)) m.close(server.id)
                    System.currentTimeMillis()
                },
            )
            m.ensure(server.id)
            waitUntil(30_000, "the exec transport to be up") { m.state(server.id).value is LinkState.Up }
            val session = assertNotNull(m.session(server.id))
            // past the first watch pass: the stable window (0 ms) is behind it, so the watch reads no clock any more
            Thread.sleep(300)
            armed.set(true)
            session.disconnect()
            waitUntil(10_000, "the close at the lifetime read") { !armed.get() }
            runBlocking { withTimeout(10_000) { own.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.join() } } }
            val state = m.state(server.id).value
            assertTrue(state is LinkState.Closed, "state after close is not Closed: $state")
        } finally {
            own.cancel()
        }
    }

    // ------------------------------------------------------------------ K5-3: a run that close() and a new ensure() ended

    /**
     * Review B-5 adv-spojeni K5-3, the call a seam on the OLD supervisor's thread makes: `close()` and right away `ensure()`
     * from another thread (the flag off and on, the last tab closed and a new one opened), then it waits until the NEW run
     * is up, so the old run goes on with an idle pool (no lock contention that would cancel it first). Returns the old
     * supervisor's job and the generation the new run published.
     */
    private fun closeAndEnsureFromAnotherThread(m: PoolExecTransportManager, own: CoroutineScope, id: String): Pair<kotlinx.coroutines.Job, Long> {
        val oldRun = own.coroutineContext[kotlinx.coroutines.Job]!!.children.single()
        Thread {
            m.close(id)
            m.ensure(id)
            waitUntil(30_000, "the new run to be up") { m.state(id).value is LinkState.Up }
            Thread.sleep(200)   // close()'s own release of the old lessee is long done
        }.also { it.start(); it.join(40_000) }
        return oldRun to m.generation(id)
    }

    private fun tsServer(f: SshdFixture, forwards: List<PortForward> = emptyList()) =
        f.server.copy(host = "localhost", tailscaleHost = "127.0.0.1", transport = ServerTransport.AUTO, portForwards = forwards)

    /**
     * K5-3 (measured by the review as adv24b, strikes=[1070]): the watch pass is blocked in non-suspending code (here the
     * re-bind of an R forward whose first bind failed) while another thread calls `close()` and `ensure()`. The old run
     * leaves the watch because it was cancelled; `closed` is false again, so on 811d7fb it went on as if its transport had
     * died: a "down after" line, a generation bump on the NEW run's transport, and a Tailscale strike.
     */
    @Test fun aWatchPassThatACloseAndAnEnsureEndedStrikesNothingAndLeavesTheNewRunAlone() {
        val f = fx()
        val back = ServerSocket(0)
        val rp = ServerSocket(0).use { it.localPort }
        val own = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val server = tsServer(f, listOf(PortForward("R", back.localPort, "127.0.0.1", rp)))
            val pool = newPool()
            val strikes = CopyOnWriteArrayList<Long>()
            val binds = AtomicInteger()
            val seen = java.util.concurrent.atomic.AtomicReference<Pair<kotlinx.coroutines.Job, Long>>()
            lateinit var m: PoolExecTransportManager
            m = PoolExecTransportManager(
                own, { pool }, { server.takeIf { s -> s.id == it } }, { s -> s.forTransport(ServerTransport.TAILSCALE) },
                onEarlyDeath = { _, _, life -> strikes += life }, execEnabled = { true }, pollMs = POLL_MS,
                bindForward = { s, pf ->
                    when (binds.incrementAndGet()) {
                        1 -> throw com.jcraft.jsch.JSchException("the connect's bind fails (test)")
                        2 -> {
                            seen.set(closeAndEnsureFromAnotherThread(m, own, server.id))
                            throw com.jcraft.jsch.JSchException("the watch's re-bind fails (test)")
                        }
                        else -> bindForwardOnJsch(s, pf)
                    }
                },
            )
            m.ensure(server.id)
            waitUntil(60_000, "the watch's re-bind") { seen.get() != null }
            val (oldRun, generation) = seen.get()
            runBlocking { withTimeout(10_000) { oldRun.join() } }
            assertEquals(emptyList(), strikes.toList(), "the run that close() ended is no Tailscale strike")
            assertEquals(generation, m.generation(server.id), "the old run bumped the new run's generation")
            assertTrue(logLines.none { it.contains("down after") }, logLines.filter { it.contains("Exec transport") }.toString())
            assertTrue(m.state(server.id).value is LinkState.Up, "the new run is up: ${m.state(server.id).value}")
        } finally {
            own.cancel()
            back.close()
        }
    }

    /**
     * K5-3, the window right after that check: the old transport really died and the supervisor is past the check when
     * `close()` and `ensure()` land (the seam is the clock read of the lifetime). The run is over, its death is no strike.
     */
    @Test fun aDeathWhoseRunACloseAndAnEnsureEndedAfterTheCheckStrikesNothing() {
        val f = fx()
        val own = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val server = tsServer(f)
            val pool = newPool()
            val strikes = CopyOnWriteArrayList<Long>()
            val armed = AtomicBoolean(false)
            val seen = java.util.concurrent.atomic.AtomicReference<Pair<kotlinx.coroutines.Job, Long>>()
            lateinit var m: PoolExecTransportManager
            m = PoolExecTransportManager(
                own, { pool }, { server.takeIf { s -> s.id == it } }, { s -> s.forTransport(ServerTransport.TAILSCALE) },
                onEarlyDeath = { _, _, life -> strikes += life }, execEnabled = { true }, pollMs = POLL_MS, stableAfterMs = 0,
                clock = {
                    // the first clock read after the transport died is the lifetime, past the supervisor's check
                    if (armed.compareAndSet(true, false)) seen.set(closeAndEnsureFromAnotherThread(m, own, server.id))
                    System.currentTimeMillis()
                },
            )
            m.ensure(server.id)
            waitUntil(30_000, "the exec transport to be up") { m.state(server.id).value is LinkState.Up }
            val session = assertNotNull(m.session(server.id))
            Thread.sleep(300)   // past the first watch pass: the stable window (0 ms) is behind it, the watch reads no clock any more
            armed.set(true)
            session.disconnect()
            waitUntil(60_000, "the close and ensure at the lifetime read") { seen.get() != null }
            runBlocking { withTimeout(10_000) { seen.get().first.join() } }
            assertEquals(emptyList(), strikes.toList(), "the death of a run that close() ended is no Tailscale strike")
            assertTrue(m.state(server.id).value is LinkState.Up, "the new run is up: ${m.state(server.id).value}")
        } finally {
            own.cancel()
        }
    }

    /**
     * K5-3 for `onStable` (the review's remediation): the stable window of the old run ends while `close()` and `ensure()`
     * land (the seam is the clock read of that window). Telling the resolver "stable" for it would reset the Tailscale
     * strike streak on behalf of a run that is over.
     */
    @Test fun theStableWindowOfARunACloseAndAnEnsureEndedIsNotReported() {
        val f = fx()
        val own = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val server = tsServer(f)
            val pool = newPool()
            val stables = AtomicInteger()
            val armed = AtomicBoolean(false)
            val seen = java.util.concurrent.atomic.AtomicReference<Pair<kotlinx.coroutines.Job, Long>>()
            lateinit var m: PoolExecTransportManager
            m = PoolExecTransportManager(
                own, { pool }, { server.takeIf { s -> s.id == it } }, { s -> s.forTransport(ServerTransport.TAILSCALE) },
                onStable = { _, _ -> stables.incrementAndGet() }, execEnabled = { true }, pollMs = POLL_MS,
                clock = {
                    // armed once up: the next clock read is the watch's stable check; it ends the window (+1 h)
                    if (armed.compareAndSet(true, false)) {
                        seen.set(closeAndEnsureFromAnotherThread(m, own, server.id))
                        System.currentTimeMillis() + 3_600_000
                    } else System.currentTimeMillis()
                },
            )
            m.ensure(server.id)
            waitUntil(30_000, "the exec transport to be up") { m.state(server.id).value is LinkState.Up }
            armed.set(true)
            waitUntil(60_000, "the close and ensure at the stable check") { seen.get() != null }
            runBlocking { withTimeout(10_000) { seen.get().first.join() } }
            assertEquals(0, stables.get(), "the stable window of a run that close() ended was reported")
            assertTrue(m.state(server.id).value is LinkState.Up, "the new run is up: ${m.state(server.id).value}")
        } finally {
            own.cancel()
        }
    }

    /**
     * Review B-1 (test hole MK9): the production call `maybeCountTsEarlyDeath(session)` has no `transport` argument; the
     * transport is looked up in the REAL [com.clauderemote.session.service.ConnectionRegistry] (the tab's SshManager, still
     * set while onConnectionLost runs). Six real tabs on two pool transports (cap 3): the three tabs of one transport are
     * one strike, the second transport's three are the second strike, which arms the Cloudflare cooldown. A lookup that
     * returned null would key every tab to the server and give one strike only.
     */
    @Test fun theProductionStrikePathTellsTwoTransportsApartThroughTheRegistry() {
        val f = fx()
        val pool = newPool(execWired = { false })
        val registry = com.clauderemote.session.service.ConnectionRegistry(storage, com.clauderemote.session.TabManager())
        val resolver = com.clauderemote.session.service.TransportResolver(scope, registry)
        val base = SshServer(id = "s", name = "box", host = "ssh.example.org", username = "u",
            useCloudflareProxy = true, tailscaleHost = "192.168.1.7", transport = ServerTransport.TAILSCALE)
        val ts = base.forTransport(ServerTransport.TAILSCALE)
        val tabs = (1..6).map { i ->
            com.clauderemote.model.ClaudeSession(
                id = "t$i", server = base, folder = "~/x", mode = com.clauderemote.model.ClaudeMode.NORMAL,
                model = com.clauderemote.model.ClaudeModel.DEFAULT, tmuxSessionName = "claude-x--t$i",
                connectionType = com.clauderemote.model.ConnectionType.SSH,
            )
        }
        val sms = tabs.map { t ->
            SshManager(storage, transportPool = pool).also { sm ->
                managers += sm
                runBlocking { withTimeout(30_000) { sm.connect(f.server, {}, {}) } }
                registry.putSsh(t.id, sm)
            }
        }
        val a = sms[0].getSession()
        assertEquals(2, sms.map { System.identityHashCode(it.getSession()) }.distinct().size, "two transports, three tabs each")
        val (onA, onB) = tabs.partition { registry.ssh(it.id)?.getSession() === a }
        assertEquals(3, onA.size)
        tabs.forEach { resolver.recordConnectSuccess(it.id, base, ts) }
        onA.forEach { resolver.maybeCountTsEarlyDeath(it) }
        assertEquals(false, runBlocking { resolver.resolveTransport(base) }.useCloudflareProxy, "one transport's tabs: one strike")
        onB.forEach { resolver.maybeCountTsEarlyDeath(it) }
        assertTrue(runBlocking { resolver.resolveTransport(base) }.useCloudflareProxy, "the other transport's death is the second strike")
    }

    // ------------------------------------------------------------------ F2

    /** A wall clock that jumps 400 s while the transport connects (Doze) must not show up as a 400 s connect. */
    @Test fun theUpLineMeasuresMonotonicTimeAndShowsTheBreakdown() {
        val f = fx()
        val pool = newPool()
        val jump = AtomicLong(0)
        val wall = { System.currentTimeMillis() + jump.get() }
        val m = PoolExecTransportManager(
            scope, { pool }, { f.server.takeIf { s -> s.id == it } },
            resolve = { s -> jump.addAndGet(400_000); s },
            execEnabled = { true }, pollMs = POLL_MS, clock = wall,
        )
        m.ensure(f.server.id)
        waitUntil(30_000, "up") { m.state(f.server.id).value is LinkState.Up }
        val line = logLines.firstOrNull { it.contains("Exec transport integration up via") }
        assertNotNull(line)
        val inMs = Regex("""up via DIRECT in (\d+)ms route=lan""").find(line)?.groupValues?.get(1)?.toLong()
        assertNotNull(inMs, line)
        assertTrue(inMs < 60_000, "the 400 s wall-clock jump is not part of the connect time: $line")
        val parts = Regex("""resolve=(\d+)ms lease=(\d+)ms forwards=(\d+)ms total=(\d+)ms wall=(\d+)ms""").find(line)
        assertNotNull(parts, "the breakdown is logged: $line")
        assertTrue(parts.groupValues[5].toLong() >= 400_000, "the wall time is still there for diagnosis: $line")
    }

    // ------------------------------------------------------------------ F4

    /** Our own teardown (network lost) is `cause=local`, for the tab line and for the transport. */
    @Test fun aTabKilledByOurOwnTeardownSaysCauseLocal() {
        val f = fx()
        val pool = newPool()
        val lost = java.util.concurrent.CountDownLatch(1)
        val sm = SshManager(storage, transportPool = pool).also { managers += it }
        runBlocking { withTimeout(30_000) { sm.connect(f.server, {}, { lost.countDown() }) } }
        pool.teardownAll()
        assertTrue(lost.await(15, java.util.concurrent.TimeUnit.SECONDS), "the tab saw the loss")
        waitUntil(5_000, "the cause lines") { logLines.any { it.contains("Connection lost cause=") } }
        val line = logLines.first { it.contains("Connection lost") }
        assertTrue(Regex("""Connection lost cause=local: network teardown""").containsMatchIn(line), line)
        assertTrue(logLines.any { Regex("""Transport to 127\.0\.0\.1:${f.port} role=shell route=lan ended after \d+ms: cause=local: network teardown""").containsMatchIn(it) }, logLines.filter { it.contains("Transport to") }.toString())
    }

    /** The server end goes away (its sshd process is killed): the cause is what jsch saw, not `local`. */
    @Test fun aTabWhoseServerEndDiedSaysWhatJschSaw() {
        val f = fx()
        val pool = newPool()
        val lost = java.util.concurrent.CountDownLatch(1)
        val sm = SshManager(storage, transportPool = pool).also { managers += it }
        runBlocking { withTimeout(30_000) { sm.connect(f.server, {}, { lost.countDown() }) } }
        // the sshd processes that serve THIS fixture's established connection (sport = the fixture's port)
        val pids = Regex("""pid=(\d+)""").findAll(f.run("ss", "-Htnp", "state", "established", "( sport = :${f.port} )").out)
            .map { it.groupValues[1] }.toSet()
        assertTrue(pids.isNotEmpty(), "found the sshd process of the connection")
        pids.forEach { f.run("kill", "-9", it) }
        assertTrue(lost.await(30, java.util.concurrent.TimeUnit.SECONDS), "the tab saw the loss")
        waitUntil(5_000, "the cause line") { logLines.any { it.contains("Connection lost cause=") } }
        val line = logLines.first { it.contains("Connection lost") }
        assertTrue(!line.contains("cause=local") && Regex("""cause=\S""").containsMatchIn(line), line)
        assertTrue(logLines.any { Regex("""Transport to 127\.0\.0\.1:${f.port} role=shell route=lan ended after \d+ms: cause=(?!local)\S""").containsMatchIn(it) }, logLines.filter { it.contains("Transport to") }.toString())
    }

    // ------------------------------------------------------------------ NN5 + N-C

    /** A pool whose every handshake first waits [latencyMs] (index = the n-th handshake, from 0). */
    private fun slowPool(execWired: () -> Boolean, latencyMs: (Int) -> Long): ServerTransportPool {
        val n = AtomicInteger()
        return ServerTransportPool(storage, execWired = { execWired() }, beforeHandshake = { delay(latencyMs(n.getAndIncrement())) }).also { pools += it }
    }

    /**
     * A throttled lease (3 handshakes of its key already run) waits for the FIRST of them to end. On c2915d1 it waited
     * for the OLDEST: a slow first handshake (5 s) held it although a second one (0.2 s) had long freed the slot.
     * The oldest handshake is made the slow one deterministically: tab 1 starts it alone, the other 9 come once it runs
     * (2 of them join it, 6 start two fast handshakes, the last one is throttled). An earlier version chose the slow
     * handshake by the order of the seam's calls, which the IO threads may swap: mutation M-firstFlight survived it.
     */
    @Test fun aThrottledLeaseGoesOnWhenAnyHandshakeEndsNotTheOldest() {
        val f = fx()
        val first = AtomicBoolean(true)
        val slowStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val pool = ServerTransportPool(storage, execWired = { false }, beforeHandshake = {
            if (first.getAndSet(false)) { slowStarted.complete(Unit); delay(5_000) } else delay(200)
        }).also { pools += it }
        val took = CopyOnWriteArrayList<Long>()
        suspend fun lease() {
            val t0 = System.nanoTime()
            pool.lease(f.server, 15_000, Any())
            took += (System.nanoTime() - t0) / 1_000_000
        }
        runBlocking {
            withTimeout(60_000) {
                val oldest = async(Dispatchers.IO) { lease() }
                slowStarted.await()
                ((2..10).map { async(Dispatchers.IO) { lease() } } + oldest).awaitAll()
            }
            // cap 3: the slow handshake serves tabs 1 and two more, the fast ones 6, the 10th tab is throttled
            assertEquals(7, took.count { it < 2_500 }, "the 6 tabs of the fast handshakes AND the throttled one, before the slow one: ${took.sorted()}")
            assertEquals(3, took.count { it >= 4_000 }, "the slow handshake's 3 tabs: ${took.sorted()}")
            assertTrue(pool.peakInflightConnects() <= ServerTransportPool.MAX_PARALLEL_HANDSHAKES, "never more than 3 handshakes at once")
        }
    }

    /**
     * Scenario A: 49 tabs reconnect, every handshake takes 2 s, the exec transport comes up 3 s into the wave. The
     * cap is re-read on every pass, so the tabs still waiting pack by 8 onto the transports already there:
     * ceil(49/8)+1 = 8 at most. On c2915d1 the cap was read once per lease, at its start (3): 17.
     */
    @Test fun anExecTransportThatComesUpDuringTheWavePacksTheRestByEight() {
        val f = fx()
        val execUp = AtomicBoolean(false)
        val pool = slowPool({ execUp.get() }) { 2_000 }
        val transports = runBlocking {
            scope.launch { delay(3_000); execUp.set(true) }
            withTimeout(120_000) { (1..49).map { async(Dispatchers.IO) { pool.lease(f.server, 15_000, Any()) } }.awaitAll() }
        }
        val n = transports.distinct().size
        assertTrue(n <= 8, "transports for 49 tabs with the exec transport up after 3 s: $n")
        assertTrue(transports.groupBy { it }.values.all { it.size <= 8 }, "no transport above the cap of 8")
        assertTrue(runBlocking { pool.peakInflightConnects() } <= ServerTransportPool.MAX_PARALLEL_HANDSHAKES)
    }

    /**
     * Scenario B: the exec transport comes up 9 s in, after the wave (0.8 s handshakes, 6 rounds of 3) is over: every
     * lease was made at cap 3, so 17 transports is the expected result, not a defect (the phone's exec transport
     * needs 5-9 s through the probe).
     */
    @Test fun anExecTransportThatComesUpAfterTheWaveChangesNothing() {
        val f = fx()
        val execUp = AtomicBoolean(false)
        val pool = slowPool({ execUp.get() }) { 800 }
        val transports = runBlocking {
            scope.launch { delay(9_000); execUp.set(true) }
            withTimeout(120_000) { (1..49).map { async(Dispatchers.IO) { pool.lease(f.server, 15_000, Any()) } }.awaitAll() }
        }
        assertEquals(17, transports.distinct().size, "ceil(49/3)")
        assertTrue(runBlocking { pool.peakInflightConnects() } <= ServerTransportPool.MAX_PARALLEL_HANDSHAKES)
    }

    /**
     * N-D: a blackhole (every handshake hangs 2 s, then fails) and 49 tabs. A throttled lease waits at most its
     * connectTimeout (3 s here) while no handshake of its key succeeds, then gives up like a claimant whose handshake
     * timed out. On c2915d1 it waited round after round (9 tabs per round of 2 s): the last tab gave up after ~12 s.
     */
    @Test fun aThrottledLeaseGivesUpAfterItsConnectTimeoutWhenNoHandshakeSucceeds() {
        val f = fx()
        val pool = ServerTransportPool(storage, execWired = { false },
            beforeHandshake = { delay(2_000); throw java.io.IOException("blackhole") }).also { pools += it }
        val took = CopyOnWriteArrayList<Long>()
        runBlocking {
            withTimeout(120_000) {
                (1..49).map {
                    async(Dispatchers.IO) {
                        val t0 = System.nanoTime()
                        assertTrue(runCatching { pool.lease(f.server, 3_000, Any()) }.isFailure, "nothing connects through a blackhole")
                        took += (System.nanoTime() - t0) / 1_000_000
                    }
                }.awaitAll()
            }
        }
        // throttled <= 3 s, then (at most) one handshake of 2 s as a claimant
        assertTrue(took.max() < 6_500, "the longest lease took ${took.max()} ms: ${took.sorted()}")
        assertTrue(runBlocking { pool.peakInflightConnects() } <= ServerTransportPool.MAX_PARALLEL_HANDSHAKES)
    }

    /**
     * N-D guard: the deadline counts from the last handshake of the key that SUCCEEDED, not from the lease's start. A wave
     * of 49 tabs with 1 s handshakes lasts ~6 s, twice the 3 s connectTimeout, and every tab still gets its transport.
     */
    @Test fun aSlowButSucceedingWaveIsNotCutByTheThrottleDeadline() {
        val f = fx()
        val pool = slowPool({ false }) { 1_000 }
        val transports = runBlocking {
            withTimeout(120_000) { (1..49).map { async(Dispatchers.IO) { pool.lease(f.server, 3_000, Any()) } }.awaitAll() }
        }
        assertEquals(17, transports.distinct().size, "ceil(49/3), every tab connected")
    }

    companion object {
        const val SPEED = 50L
        const val POLL_MS = 50L
    }
}
