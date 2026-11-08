package com.clauderemote.integration

import com.clauderemote.connection.ChannelOpen
import com.clauderemote.connection.ServerTransportPool
import com.clauderemote.connection.SshManager
import com.clauderemote.model.SshServer
import com.clauderemote.session.ClaudeConfig
import com.clauderemote.session.FreshCreate
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.session.TabManager
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ConnectionRegistry
import com.clauderemote.session.service.ExecTransportManager
import com.clauderemote.session.service.GatedExecChannels
import com.clauderemote.session.service.LinkState
import com.clauderemote.session.service.PoolExecTransportManager
import com.clauderemote.session.service.PooledRemoteExec
import com.clauderemote.session.service.execReadWithWatchdog
import com.clauderemote.storage.ServerStorage
import com.clauderemote.testsupport.LiveFileCanary
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Dávka C, celek C1, against a REAL sshd:
 *  - 1a (K-2): JSch's keepalive works over a proxy WITHOUT a socket (Cloudflare) once its input is a [KeepAliveInput] —
 *    probe P3 as a test: the data stop, the transport is down within ~3 keepalive intervals;
 *  - 1b (K-4, probes P1/P2): opens given up after their budget leave no orphans in `MaxSessions`;
 *  - 1b (K-6, H8, H9): an exec transport whose channels stop opening (black hole, socket open) is replaced — a new
 *    transport, the old one disconnected — and not again within the spacing; the time from the hole to `down` is measured;
 *  - H7: while the exec transport is being replaced the shells keep the cap of 8;
 *  - N7-1 (H19): a STOPPED tmux server (SIGSTOP of the private one only) no longer keeps a creation or a cleanup — and
 *    with it sshd's session — waiting for an EOF.
 */
class ChannelHealthIntegrationTest {
    private var fixture: SshdFixture? = null
    private val storage = ServerStorage(InMemoryKeyValueStore())
    private val pools = CopyOnWriteArrayList<ServerTransportPool>()
    private val managers = CopyOnWriteArrayList<SshManager>()
    private val relays = CopyOnWriteArrayList<TcpRelay>()
    private val sessions = CopyOnWriteArrayList<Session>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedPlatformLog = FileLogger.platformLog

    @get:org.junit.Rule val dumpOnFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable?, description: org.junit.runner.Description?) {
            println("---- log lines of ${description?.methodName} ----\n" + logLines.joinToString("\n"))
        }
    }

    @Before fun setUp() {
        CommFlags.resetForTest()
        ChannelOpen.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "${System.currentTimeMillis()} $tag: $message" }
    }

    @After fun tearDown() {
        FileLogger.platformLog = savedPlatformLog
        scope.cancel()
        sessions.forEach { runCatching { it.disconnect() } }
        pools.forEach { it.teardownAll() }
        managers.forEach { it.shutdownForTest() }
        relays.forEach { it.close() }
        ChannelOpen.resetForTest()
        CommFlags.resetForTest()
        com.clauderemote.connection.CloudflareProxy.tunnelForTest = null
        tunnels.forEach { it.close() }
        fixture?.close()
    }

    private fun fx(extraSshdConfig: String = ""): SshdFixture {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        return SshdFixture.start(extraSshdConfig).also { fixture = it }
    }

    /**
     * A TCP relay to [targetPort] on loopback: [delayMs] on every chunk in both directions (order kept), and a black hole
     * ([blackHoleAll]) that drops every byte of the connections open now while their sockets stay open; later
     * connections pass.
     */
    internal class TcpRelay(private val targetPort: Int) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        @Volatile var delayMs = 0L
        private class Conn(val a: Socket, val b: Socket) { @Volatile var hole = false }
        private val conns = CopyOnWriteArrayList<Conn>()

        init {
            daemon {
                try {
                    while (true) {
                        val a = server.accept()
                        val b = Socket(InetAddress.getLoopbackAddress(), targetPort)
                        val c = Conn(a, b).also { conns += it }
                        pump(c, a, b); pump(c, b, a)
                    }
                } catch (_: Exception) {}
            }
        }

        fun blackHoleAll() = conns.forEach { it.hole = true }

        private fun pump(c: Conn, from: Socket, to: Socket) {
            val q = LinkedBlockingQueue<Pair<Long, ByteArray?>>()
            daemon {
                val buf = ByteArray(32 * 1024)
                try {
                    val input = from.getInputStream()
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (!c.hole) q.put(System.nanoTime() + delayMs * 1_000_000 to buf.copyOf(n))
                    }
                } catch (_: Exception) {}
                q.put(0L to null)
            }
            daemon {
                try {
                    val out = to.getOutputStream()
                    while (true) {
                        val (due, data) = q.take()
                        if (data == null) { runCatching { to.shutdownOutput() }; break }
                        val wait = (due - System.nanoTime()) / 1_000_000
                        if (wait > 0) Thread.sleep(wait)
                        if (!c.hole) { out.write(data); out.flush() }
                    }
                } catch (_: Exception) {}
            }
        }

        override fun close() {
            runCatching { server.close() }
            conns.forEach { runCatching { it.a.close() }; runCatching { it.b.close() } }
        }

        private fun daemon(body: () -> Unit) = Thread(body).apply { isDaemon = true; start() }
    }

    private fun relay(f: SshdFixture) = TcpRelay(f.port).also { relays += it }

    // ------------------------------------------------------------------ 1a: keepalive over the Cloudflare proxy

    /**
     * The WebSocket leg of the PRODUCTION [com.clauderemote.connection.CloudflareProxy] replaced through its seam by a
     * socket to this sshd. [hole] drops every byte both ways while the socket stays open (a tunnel gone quiet beyond the
     * WebSocket leg); [splitMs] > 0 delivers the next chunk in two parts with that pause between them (inside an SSH
     * packet); [silent] never delivers the server's bytes (a handshake that hangs).
     */
    private class SeamTunnel(port: Int, private val onBytes: (ByteArray) -> Unit, private val onEnd: () -> Unit) :
        com.clauderemote.connection.CloudflareProxy.TestTunnel {
        private val sock = Socket(InetAddress.getLoopbackAddress(), port)
        @Volatile var hole = false
        @Volatile var splitMs = 0L
        @Volatile var silent = false
        init {
            Thread {
                val buf = ByteArray(16 * 1024)
                try {
                    val i = sock.getInputStream()
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        if (hole || silent) continue
                        val pause = splitMs
                        if (pause > 0 && n > 40) {
                            splitMs = 0
                            onBytes(buf.copyOfRange(0, 7)); Thread.sleep(pause); onBytes(buf.copyOfRange(7, n))
                        } else onBytes(buf.copyOf(n))
                    }
                } catch (_: Exception) {}
                onEnd()
            }.apply { isDaemon = true; start() }
        }
        override fun send(bytes: ByteArray): Boolean { if (!hole) sock.getOutputStream().write(bytes); return true }
        override fun close() { runCatching { sock.close() } }
    }

    private val tunnels = CopyOnWriteArrayList<SeamTunnel>()

    /** Every Cloudflare proxy of this test rides a [SeamTunnel] to [port]. */
    private fun cloudflareVia(port: Int) {
        com.clauderemote.connection.CloudflareProxy.tunnelForTest = { onBytes, onEnd -> SeamTunnel(port, onBytes, onEnd).also { tunnels += it } }
    }

    /** A Cloudflare server whose transports the PRODUCTION pool connects (and arms) over the seam; keepalive [intervalMs] x 2. */
    private fun cloudflarePool(f: SshdFixture, intervalMs: Int): Pair<SshServer, ServerTransportPool> {
        cloudflareVia(f.port)
        val server = f.server.copy(id = "cf-server", useCloudflareProxy = true)
        storage.addServer(server)
        val pool = ServerTransportPool(storage, execWired = { true }).also { pools += it }
        pool.setKeepAlive(intervalMs)
        return server to pool
    }

    private fun downWithin(s: Session, limitMs: Long): Long? {
        val t0 = System.nanoTime()
        while (s.isConnected && (System.nanoTime() - t0) / 1_000_000 < limitMs) Thread.sleep(20)
        return if (s.isConnected) null else (System.nanoTime() - t0) / 1_000_000
    }

    /** R1/R2/R4: the pool's own Cloudflare transport is armed after its handshake; a black hole takes it down in time. */
    @Test fun aBlackHoledCloudflareTransportOfThePoolGoesDownByItsKeepalive() {
        val f = fx()
        val (server, pool) = cloudflarePool(f, intervalMs = 1_000)
        val s = runBlocking { pool.leaseExec(server, 15_000, Any()) }
        assertTrue(com.clauderemote.connection.ChannelOpen.isCloudflare(s), "the pool armed it (and marked the route)")
        Thread.sleep(500)
        tunnels.last().hole = true
        val took = downWithin(s, 10_000)
        println("C1 cf black hole -> down: $took ms (keepalive 1000 ms x 2)")
        assertTrue(took != null && took <= 3_000 + 600, "down after interval x (count + 1) plus the 250 ms tick: $took ms")
    }

    /** R4: a quiet but healthy Cloudflare transport is kept alive — the keepalive's answers count — well past 3 intervals. */
    @Test fun anIdleHealthyCloudflareTransportSurvivesManyIntervals() {
        val f = fx()
        val (server, pool) = cloudflarePool(f, intervalMs = 1_000)
        val s = runBlocking { pool.leaseExec(server, 15_000, Any()) }
        Thread.sleep(6_000)
        assertTrue(s.isConnected, "idle 6 s at a 1 s keepalive: still up")
        val out = runBlocking { execReadWithWatchdog(s, "echo alive", totalMs = 5_000) }
        assertEquals("alive", out.trim())
    }

    /** Adv 5: a pause inside an SSH packet shorter than the dead limit no longer breaks the stream (no exception in the read). */
    @Test fun aPauseInsideAnSshPacketDoesNotKillTheCloudflareTransport() {
        val f = fx()
        val (server, pool) = cloudflarePool(f, intervalMs = 1_000)
        val s = runBlocking { pool.leaseExec(server, 15_000, Any()) }
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand("for i in 1 2 3 4 5 6; do head -c 30000 /dev/zero | tr '\\0' a; echo; sleep 0.5; done")
        ch.inputStream = null
        val input = ch.inputStream
        ch.connect(5_000)
        Thread.sleep(800)
        tunnels.last().splitMs = 2_500   // > 2 intervals of 1 s, < interval x 3
        val bytes = runCatching { input.readBytes().size }.getOrElse { -1 }
        Thread.sleep(300)
        assertTrue(s.isConnected, "the transport lives")
        assertEquals(6 * 30_001, bytes, "every byte arrived")
        assertEquals(0, ch.exitStatus)
    }

    /** Review C1-1 (MEDIUM): a handshake that hangs over the proxy (no socket, so no read timeout of JSch) ends in time. */
    @Test fun aHangingHandshakeOverTheCloudflareProxyEndsWithinItsLimit() {
        val f = fx()
        cloudflareVia(f.port)
        com.clauderemote.connection.CloudflareProxy.tunnelForTest = { onBytes, onEnd ->
            SeamTunnel(f.port, onBytes, onEnd).also { it.silent = true; tunnels += it }
        }
        val server = f.server.copy(id = "cf-hang", useCloudflareProxy = true)
        storage.addServer(server)
        val pool = ServerTransportPool(storage, execWired = { true }).also { pools += it }
        val t0 = System.nanoTime()
        val r = runCatching { runBlocking { withTimeout(40_000) { pool.leaseExec(server, 10_000, Any()) } } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(r.isFailure, "no handshake: no transport")
        assertTrue(ms <= 10_000 + 1_000, "ended by the proxy's handshake limit (connect timeout 10 s): $ms ms")
    }

    // ------------------------------------------------------------------ 1b: no orphans (probes P1 / P2)

    /**
     * Probe P1/P2: on a link with 400 ms each way, 4 opens are given up after 300 ms. jsch alone would never CLOSE the
     * channels sshd confirms late, and with `MaxSessions 3` the next opens were refused (exitStatus 2). Through
     * [ChannelOpen] the late ones are closed: the next opens succeed.
     */
    @Test fun opensGivenUpOnASlowLinkLeaveNoOrphans() {
        val f = fx("MaxSessions 3")
        val r = relay(f).apply { delayMs = 400 }
        val jsch = com.jcraft.jsch.JSch().apply { addIdentity("k", f.privateKey.toByteArray(), null, null) }
        val s = jsch.getSession(f.username, "127.0.0.1", r.port).also { sessions += it }
        s.setConfig("StrictHostKeyChecking", "no")
        s.connect(20_000)
        val given = (1..4).map {
            val ch = s.openChannel("exec") as ChannelExec
            ch.setCommand("sleep 0")
            val e = runCatching { ChannelOpen.connect(ch, 300) }.exceptionOrNull()
            assertTrue(e is JSchException && "channel is not opened" in e.message.orEmpty(), "given up after 300 ms: $e")
            ChannelOpen.disconnect(ch)
            ch
        }
        waitUntil(15_000, "the given-up opens to resolve") { given.none { ChannelOpen.isPending(it) } }
        // (4 opens in flight at once against MaxSessions 3 may see one honest refusal; what matters is what comes after)
        val refusedBefore = f.refusedChannelOpens()
        repeat(3) { i ->
            val out = runBlocking { execReadWithWatchdog(s, "echo ok", connectMs = 5_000, totalMs = 10_000) }
            assertEquals("ok", out.trim(), "open $i after the given-up ones: no orphan holds a slot")
        }
        assertEquals(refusedBefore, f.refusedChannelOpens(), "sshd refused none of the opens after them")
    }

    // ------------------------------------------------------------------ 1b: the half-dead exec transport

    private fun execManager(server: SshServer, pool: ServerTransportPool, spacingMs: Long) = PoolExecTransportManager(
        scope, { pool }, { server.takeIf { s -> s.id == it } }, resolve = { it },
        execEnabled = { true }, pollMs = 200, healthSwapSpacingMs = spacingMs,
    )

    private fun upLines() = logLines.count { "Exec transport" in it && " up via " in it }

    /** Pollers on the exec transport, like the transcript ticks: one open after another, each with a full budget. */
    private fun startPolls(m: PoolExecTransportManager, id: String, n: Int) {
        val exec = PooledRemoteExec(GatedExecChannels(m, onTransportTrouble = { m.noteChannelTrouble(it) }))
        repeat(n) {
            scope.launch {
                while (isActive) {
                    runCatching { exec.run(id, "echo ok", totalMs = 10_000) }
                    delay(200)
                }
            }
        }
    }

    @Test fun aHalfDeadExecTransportIsReplacedSoonAndNotAgainWithinTheSpacing() {
        val f = fx()
        val r = relay(f)
        val server = f.server.copy(port = r.port)
        storage.addServer(server)
        val pool = ServerTransportPool(storage, execWired = { true }).also { pools += it }
        val m = execManager(server, pool, spacingMs = 120_000)
        m.ensure(server.id)
        waitUntil(30_000, "the exec transport up") { m.state(server.id).value is LinkState.Up }
        val first = m.session(server.id)!!
        startPolls(m, server.id, 4)
        Thread.sleep(1_500)
        r.blackHoleAll()   // the data stop, the socket stays open (keepalive 10 s x 2 would need ~30 s)
        val t0 = System.nanoTime()
        waitUntil(25_000, "the half-dead exec transport to be reported down") { logLines.any { "Exec transport" in it && "down after" in it } }
        val downMs = (System.nanoTime() - t0) / 1_000_000
        println("C1 black hole -> down: $downMs ms")
        assertTrue(logLines.any { "replacing it" in it }, "replaced for channel health, not by the keepalive")
        // kolo 2: 3 counted opens must be in a row, so a LAN black hole is now caught by the open's dead limit (10 s)
        assertTrue(downMs < 12_500, "the open's dead limit of 10 s + the watch: $downMs ms")
        // R10: the REAL manager keeps the shell cap of 8 while it replaces the transport
        assertTrue(m.keepsShellCap(server.id), "shell cap held during the replacement")
        waitUntil(30_000, "a new exec transport up") { m.session(server.id)?.let { it !== first } == true }
        val second = m.session(server.id)!!
        assertNotSame(first, second, "a new transport (H8), never the same one again")
        waitUntil(5_000, "the old transport disconnected") { !first.isConnected }
        // the spacing (H9): the new one goes half-dead at once — not replaced again within it
        val swapsBefore = logLines.count { "replacing it" in it }
        r.blackHoleAll()
        waitUntil(25_000, "the refusal of a second replacement") { logLines.any { "not replaced, the last replacement was" in it } }
        assertEquals(swapsBefore, logLines.count { "replacing it" in it }, "no second replacement within the spacing")
        assertTrue(m.session(server.id) === second, "the transport stays (the keepalive ends it later)")
        m.close(server.id)
    }

    /** Doplněk 1, bod 3: with no channel traffic at all only the keepalive notices the black hole: interval x (count + 1). */
    @Test fun withoutTrafficTheKeepaliveTakesABlackHoledExecTransportDown() {
        val f = fx()
        val r = relay(f)
        val server = f.server.copy(port = r.port)
        storage.addServer(server)
        val pool = ServerTransportPool(storage, execWired = { true }).also { pools += it }
        pool.setKeepAlive(2_000)
        val m = execManager(server, pool, spacingMs = 120_000)
        m.ensure(server.id)
        waitUntil(30_000, "the exec transport up") { m.state(server.id).value is LinkState.Up }
        r.blackHoleAll()
        val t0 = System.nanoTime()
        waitUntil(20_000, "down by the keepalive") { logLines.any { "Exec transport" in it && "down after" in it } }
        val downMs = (System.nanoTime() - t0) / 1_000_000
        println("C1 black hole without traffic, keepalive 2 s x 2 -> down: $downMs ms")
        assertTrue(downMs <= 3 * 2_000 + 1_500, "interval x 3 + the 200 ms watch: $downMs ms")
        m.close(server.id)
    }

    /** Kolo 2 (adv 3, R9): a replacement after which no open was ever confirmed doubles the spacing before the next one. */
    @Test fun aReplacementThatBringsNoWorkingOpenDoublesTheSpacing() {
        val f = fx()
        val r = relay(f)
        val server = f.server.copy(port = r.port)
        storage.addServer(server)
        val pool = ServerTransportPool(storage, execWired = { true }).also { pools += it }
        val m = execManager(server, pool, spacingMs = 2_000)
        m.ensure(server.id)
        waitUntil(30_000, "the exec transport up") { m.state(server.id).value is LinkState.Up }
        val first = m.session(server.id)!!
        val exec = PooledRemoteExec(GatedExecChannels(m, onTransportTrouble = { m.noteChannelTrouble(it) }))
        runBlocking { exec.run(server.id, "echo ok", totalMs = 5_000) }   // a confirmed open on the first transport
        r.blackHoleAll()
        val one = scope.launch { runCatching { exec.run(server.id, "echo ok", totalMs = 10_000) } }
        waitUntil(20_000, "the first replacement") { logLines.any { "replacing it" in it } }
        assertTrue(logLines.single { "replacing it" in it }.contains("not within 2000ms"), "it had worked: the base spacing")
        one.cancel()
        waitUntil(30_000, "the second transport up") { m.session(server.id)?.let { it !== first } == true }
        r.blackHoleAll()   // the new one never answers a single open
        scope.launch { runCatching { exec.run(server.id, "echo ok", totalMs = 10_000) } }
        waitUntil(20_000, "the second replacement") { logLines.count { "replacing it" in it } == 2 }
        assertTrue(logLines.filter { "replacing it" in it }[1].contains("not within 4000ms"),
            "no open confirmed since the last replacement: the spacing doubles (${logLines.filter { "replacing it" in it }})")
        m.close(server.id)
    }

    /** Adv STALL: ONE pause of 6 s that 4 pollers wait out together (nothing lost) is no reason to replace a healthy transport. */
    @Test fun onePauseOfSixSecondsDoesNotReplaceAHealthyExecTransport() {
        val f = fx()
        val r = relay(f)
        val server = f.server.copy(port = r.port)
        storage.addServer(server)
        val pool = ServerTransportPool(storage, execWired = { true }).also { pools += it }
        val m = execManager(server, pool, spacingMs = 120_000)
        m.ensure(server.id)
        waitUntil(30_000, "the exec transport up") { m.state(server.id).value is LinkState.Up }
        val first = m.session(server.id)!!
        startPolls(m, server.id, 4)
        Thread.sleep(2_000)
        r.delayMs = 6_000   // every chunk of the next 300 ms is held 6 s, in order, nothing dropped
        Thread.sleep(300)
        r.delayMs = 0
        Thread.sleep(6_000 + 8_000)
        assertEquals(0, logLines.count { "replacing it" in it }, "no replacement: ${logLines.filter { "ChannelOpen" in it }}")
        assertTrue(m.session(server.id) === first && first.isConnected, "the same, healthy transport")
        m.close(server.id)
    }

    // ------------------------------------------------------------------ H7: the shell cap during a replacement

    private class FakeExecManager : ExecTransportManager {
        val link = MutableStateFlow<LinkState>(LinkState.Up("test"))
        @Volatile var holdCap = false
        override fun ensure(serverId: String) {}
        override fun session(serverId: String): Session? = null
        override suspend fun await(serverId: String, ms: Long): Session? = null
        override fun state(serverId: String): StateFlow<LinkState> = link
        override fun close(serverId: String) {}
        override fun onNetworkLost() {}
        override fun onNetworkAvailable() {}
        override fun generation(serverId: String): Long = 1
        override fun setKeepAlive(ms: Int) {}
        override fun keepsShellCap(serverId: String): Boolean = holdCap
    }

    /** H7: a wave of 16 tabs with the exec transport being replaced in the middle rides at most ceil(16/8)+1 transports. */
    @Test fun aWaveDuringAnExecReplacementKeepsTheShellCapOfEight() {
        val f = fx()
        storage.addServer(f.server)
        val registry = ConnectionRegistry(storage, TabManager())
        val fake = FakeExecManager()
        registry.execSessions = fake
        val pool = registry.transportPool(f.server.id).also { pools += it }
        fun wave(n: Int) = runBlocking {
            val ms = (1..n).map { SshManager(storage, transportPool = pool).also { m -> managers += m } }
            withTimeout(90_000) { ms.map { m -> async(Dispatchers.IO) { m.connect(f.server, {}, {}) } }.awaitAll() }
            ms
        }
        val a = wave(8)
        fake.link.value = LinkState.Down   // the half-dead exec transport was just marked unusable
        fake.holdCap = true                 // ExecTransportManager.keepsShellCap: replaced for channel health
        val b = wave(8)
        val transports = (a + b).map { it.getSession() }.distinct().size
        assertTrue(transports <= 3, "16 tabs, cap 8 kept through the replacement: at most ceil(16/8)+1 transports, got $transports")
    }

    // ------------------------------------------------------------------ LOW3 on a real tmux (review C1-1, HIGH)

    @Test fun theCleanupOnARealTmuxKillsOurSessionWithoutClientsAndSparesAnAttachedOne() {
        val f = fx()
        val canary = LiveFileCanary()
        val token = LiveFileCanary.newToken()
        canary.guard(token, f.home, f.root) {
            f.startTmuxServer()
            val nonce = "L3" + token.filter { it.isLetterOrDigit() }.take(12)
            val s = f.openRawSession().also { sessions += it }
            fun cleanup(name: String) = runBlocking { execReadWithWatchdog(s, ClaudeConfig.buildOwnSessionCleanupCommand(name, nonce), totalMs = 15_000) }
            // ours, nobody attached: removed
            f.tmux("new-session", "-d", "-s", "claude-x-free", "-e", "CR_LAUNCH=$nonce", "sleep", "3600")
            waitUntil(5_000, "the session") { "claude-x-free" in f.tmuxSessions() }
            val out1 = cleanup("claude-x-free")
            assertTrue(ClaudeConfig.FRESH_CLEANED in out1, "nobody attached: cleaned ($out1)")
            assertTrue("claude-x-free" !in f.tmuxSessions(), "and gone")
            // ours, a client attached (another device took it over): it stays
            f.tmux("new-session", "-d", "-s", "claude-x-taken", "-e", "CR_LAUNCH=$nonce", "sleep", "3600")
            waitUntil(5_000, "the session") { "claude-x-taken" in f.tmuxSessions() }
            val pb = ProcessBuilder("tmux", "-S", f.tmuxSocket.absolutePath, "-C", "attach-session", "-t", "=claude-x-taken")
            pb.environment().apply { remove("TMUX"); remove("TMUX_PANE"); put("TMUX_TMPDIR", f.tmuxTmpDir.absolutePath); put("HOME", f.home.absolutePath) }
            val client = pb.redirectErrorStream(true).redirectOutput(java.io.File(f.root, "ctl.out")).start()
            try {
                waitUntil(5_000, "the client attached") {
                    f.tmux("display-message", "-p", "-t", "=claude-x-taken:", "#{session_attached}").out.trim() == "1"
                }
                val out2 = cleanup("claude-x-taken")
                assertTrue(ClaudeConfig.FRESH_CLEANUP_ATTACHED in out2 && ClaudeConfig.FRESH_CLEANED !in out2, "attached: it stays ($out2)")
                assertTrue("claude-x-taken" in f.tmuxSessions())
            } finally {
                client.destroy()
                client.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            null
        }
    }

    // ------------------------------------------------------------------ adv STOP: a stopped tmux behind the tmux probes

    /**
     * Adv STOP (H19 rules): the PRIVATE tmux server stopped 70 s; 4 pollers run the production has-session probe as poll
     * execs, an interactive `echo` every 5 s. Round 1: 3 replacements, sshd refusing channels, 10 of 14 echoes failed.
     */
    @Test fun aStoppedTmuxBehindTheProbesNeitherChurnsTheTransportNorStarvesInteractiveWork() {
        val f = fx()
        val canary = LiveFileCanary()
        val token = LiveFileCanary.newToken()
        canary.guard(token, f.home, f.root) {
            f.startTmuxServer()
            val pid = f.tmux("display-message", "-p", "#{pid}").out.trim()
            assertTrue(pid.matches(Regex("\\d+")), "pid: '$pid'")
            val cmdline = java.io.File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
            assertTrue(f.tmuxSocket.absolutePath in cmdline, "the stopped server must be the fixture's private one: $cmdline")
            storage.addServer(f.server)
            val pool = ServerTransportPool(storage, execWired = { true }).also { pools += it }
            val m = PoolExecTransportManager(scope, { pool }, { f.server.takeIf { s -> s.id == it } }, resolve = { it },
                execEnabled = { true }, pollMs = 200)
            m.ensure(f.server.id)
            waitUntil(30_000, "the exec transport up") { m.state(f.server.id).value is LinkState.Up }
            val exec = PooledRemoteExec(GatedExecChannels(m, onTransportTrouble = { m.noteChannelTrouble(it) }))
            val echoes = CopyOnWriteArrayList<String>()
            val probe = com.clauderemote.session.service.TmuxProbes.hasSessionCmd("claude-x-$token")
            try {
                assertEquals(0, ProcessBuilder("kill", "-STOP", pid).start().waitFor())
                val jobs = (1..4).map { scope.launch { while (isActive) {
                    runCatching { exec.run(f.server.id, probe, totalMs = 2_000, kind = com.clauderemote.session.service.ExecKind.POLL) }
                    delay(500)
                } } }
                val ij = scope.launch { while (isActive) {
                    delay(5_000)
                    echoes += runCatching { exec.run(f.server.id, "echo hi", totalMs = 8_000).stdout.trim() }.getOrElse { "FAIL ${it.message}" }
                } }
                Thread.sleep(70_000)
                jobs.forEach { it.cancel() }; ij.cancel()
            } finally {
                ProcessBuilder("kill", "-CONT", pid).start().waitFor()
            }
            val swaps = logLines.count { "replacing it" in it }
            println("C1 STOP: swaps=$swaps echoes=$echoes refusedBySshd=${f.refusedChannelOpens()}")
            assertTrue(swaps <= 2, "at most 2 replacements in 70 s: $swaps")
            assertTrue(echoes.count { it == "hi" } >= 12, "interactive work gets through: $echoes")
            waitUntil(10_000, "the private tmux server answers again") { f.tmux("list-sessions").exit == 0 }
            m.close(f.server.id)
            null
        }
    }

    // ------------------------------------------------------------------ N7-1: a stopped tmux server (H19)

    @Test fun aStoppedTmuxServerKeepsNeitherACleanupNorACreationWaiting() {
        val f = fx()
        val canary = LiveFileCanary()
        val token = LiveFileCanary.newToken()
        canary.guard(token, f.home, f.root) {
            f.startTmuxServer()
            // the PID of THIS fixture's server only: asked over its own socket, and checked against its command line
            val pid = f.tmux("display-message", "-p", "#{pid}").out.trim()
            assertTrue(pid.matches(Regex("\\d+")), "pid: '$pid'")
            val cmdline = java.io.File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
            assertTrue(f.tmuxSocket.absolutePath in cmdline, "the stopped server must be the fixture's private one: $cmdline")
            val nonce = "N71x" + token.filter { it.isLetterOrDigit() }.take(12)
            val s = f.openRawSession().also { sessions += it }
            try {
                assertEquals(0, ProcessBuilder("kill", "-STOP", pid).start().waitFor())
                val t0 = System.nanoTime()
                val out = runBlocking { execReadWithWatchdog(s, ClaudeConfig.buildOwnSessionCleanupCommand("claude-x-$nonce", nonce), totalMs = 15_000) }
                val cleanupMs = (System.nanoTime() - t0) / 1_000_000
                assertTrue(cleanupMs < (ClaudeConfig.FRESH_CHECK_LIMIT_S + 3) * 1000L,
                    "the cleanup's channel ends with its own limit, not when tmux wakes up: $cleanupMs ms ($out)")
                assertTrue(ClaudeConfig.FRESH_CLEANUP_UNKNOWN in out, "and says it does not know: $out")
                val t1 = System.nanoTime()
                val created = runBlocking {
                    execReadWithWatchdog(s, ClaudeConfig.buildFreshCreateCommand("claude-x-$nonce", "/tmp", ClaudeMode.NORMAL, ClaudeModel.DEFAULT,
                        "0f6c2a4e-1b2c-4d5e-8f90-123456789abc", null, nonce), totalMs = 25_000)
                }
                val createMs = (System.nanoTime() - t1) / 1_000_000
                assertTrue(createMs < (ClaudeConfig.FRESH_CREATE_SERVER_LIMIT_S + ClaudeConfig.FRESH_CHECK_LIMIT_S + 3) * 1000L,
                    "the creation's channel ends with the server's limits: $createMs ms ($created)")
                assertEquals(FreshCreate.Result.Lost, FreshCreate.parse(created, nonce), created)
            } finally {
                ProcessBuilder("kill", "-CONT", pid).start().waitFor()
            }
            // tmux recovered: whatever the stopped server still had queued for the killed clients, nothing is left waiting
            waitUntil(10_000, "the private tmux server answers again") { f.tmux("list-sessions").exit == 0 }
            null
        }
    }
}
