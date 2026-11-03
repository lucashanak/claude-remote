package com.clauderemote.integration

import com.clauderemote.connection.ServerTransportPool
import com.clauderemote.connection.SshManager
import com.clauderemote.session.service.CommFlags
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Facts the lazy-attach work will lean on, measured against a real sshd and a real (private)
 * tmux server, so the design rests on numbers and not on assumptions:
 *
 *  1. closing the shell channel removes the tmux client within 2 s;
 *  2. with `window-size latest`, detaching the newest client resizes the window to the survivor;
 *  3. 50 attach/detach cycles in 60 s leave the tmux server alive (same pid);
 *  4. cold-attach phase timings with the shell transport DRAINED (a handshake first) and UP.
 *     They go through the pool and [SshManager] (the real connect path of a tab), not through
 *     the orchestrator: the phase instrumentation of `connectSsh` belongs to the integration step.
 *     The numbers are printed (`S05 ...`) and only sanity-bounded; they are the measured basis for
 *     the attach-latency targets.
 */
class LazyAttachIntegrationTest {
    private lateinit var f: SshdFixture
    private val storage = ServerStorage(InMemoryKeyValueStore())
    private val raw = CopyOnWriteArrayList<Session>()
    private val managers = CopyOnWriteArrayList<SshManager>()
    private val pools = CopyOnWriteArrayList<ServerTransportPool>()

    @Before fun setUp() {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        CommFlags.resetForTest()
        f = SshdFixture.start()
        f.startTmuxServer()
        f.tmux("set-option", "-g", "window-size", "latest").also { assertEquals(0, it.exit, it.out) }
        // no status line, so a window is as tall as its client's terminal
        f.tmux("set-option", "-g", "status", "off").also { assertEquals(0, it.exit, it.out) }
        f.tmux("new-session", "-d", "-s", SESSION, "-x", "120", "-y", "40", "sh", "-c", "echo $READY; exec cat").also { assertEquals(0, it.exit, it.out) }
        waitUntil(10_000, "session $SESSION") { f.tmuxSessions().contains(SESSION) }
    }

    @After fun tearDown() {
        pools.forEach { it.teardownAll() }
        raw.forEach { runCatching { it.disconnect() } }
        managers.forEach { it.shutdownForTest() }
        CommFlags.resetForTest()
        if (::f.isInitialized) f.close()
    }

    /** A raw pty shell on its own transport that attaches to the test session at the given size. */
    private fun attachClient(cols: Int, rows: Int): Pair<Session, ChannelShell> {
        val s = f.openRawSession().also { raw += it }
        val ch = s.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color")
        ch.setPtySize(cols, rows, cols * 8, rows * 16)
        val out = ch.inputStream
        val os = ch.outputStream
        ch.connect(10_000)
        Thread { runCatching { val b = ByteArray(8192); while (out.read(b) >= 0) { /* drain */ } } }.apply { isDaemon = true; start() }
        os.write("tmux attach-session -t '=$SESSION'\n".toByteArray()); os.flush()
        return s to ch
    }

    private fun clients(): List<String> =
        f.tmux("list-clients", "-t", SESSION, "-F", "#{client_pid}").out.lines().map { it.trim() }.filter { it.matches(Regex("\\d+")) }

    private fun windowSize(): String =
        f.tmux("list-windows", "-t", SESSION, "-F", "#{window_width}x#{window_height}").out.lines().first().trim()

    private fun serverPid(): String = f.tmux("display-message", "-p", "#{pid}").out.trim()

    @Test fun closingTheShellChannelRemovesTheTmuxClientWithinTwoSeconds() {
        val (_, ch) = attachClient(100, 30)
        waitUntil(10_000, "the client to attach") { clients().size == 1 }
        val t0 = System.nanoTime()
        ch.disconnect()
        waitUntil(2_000, "the client to disappear after the channel closed", stepMs = 20) { clients().isEmpty() }
        println("S05 client-gone-after-channel-close ms=${(System.nanoTime() - t0) / 1_000_000}")
    }

    @Test fun detachingTheLatestClientResizesTheWindowToTheSurvivor() {
        val (_, small) = attachClient(100, 30)
        waitUntil(10_000, "first client") { clients().size == 1 }
        waitUntil(5_000, "window follows the first client") { windowSize() == "100x30" }
        val (_, big) = attachClient(160, 50)
        waitUntil(10_000, "second client") { clients().size == 2 }
        waitUntil(5_000, "window follows the newest client") { windowSize() == "160x50" }
        big.disconnect()
        waitUntil(5_000, "window to shrink to the survivor", stepMs = 50) { windowSize() == "100x30" }
        small.disconnect()
    }

    @Test fun fiftyAttachDetachCyclesInSixtySecondsLeaveTheServerAlive() {
        val pidBefore = serverPid()
        assertTrue(pidBefore.matches(Regex("\\d+")), "pid: '$pidBefore'")
        val s = f.openRawSession().also { raw += it }
        val t0 = System.nanoTime()
        repeat(50) { i ->
            val ch = s.openChannel("shell") as ChannelShell
            ch.setPtyType("xterm-256color"); ch.setPtySize(100, 30, 800, 480)
            val out = ch.inputStream; val os = ch.outputStream
            ch.connect(10_000)
            Thread { runCatching { val b = ByteArray(8192); while (out.read(b) >= 0) { } } }.apply { isDaemon = true; start() }
            os.write("tmux attach-session -t '=$SESSION'\n".toByteArray()); os.flush()
            waitUntil(10_000, "cycle $i: attach") { clients().isNotEmpty() }
            ch.disconnect()
            waitUntil(10_000, "cycle $i: detach") { clients().isEmpty() }
        }
        val secs = (System.nanoTime() - t0) / 1_000_000_000.0
        println("S05 50-cycles seconds=$secs")
        assertTrue(secs <= 60.0, "50 cycles took $secs s")
        assertEquals(pidBefore, serverPid(), "the tmux server survived all cycles")
        assertTrue(f.tmuxSessions().contains(SESSION))
    }

    /** One cold attach through the pool + SshManager; returns phase timings in ms. */
    private fun coldAttach(pool: ServerTransportPool): Map<String, Long> {
        val m = SshManager(storage, transportPool = pool).also { managers += it }
        val chunks = CopyOnWriteArrayList<Pair<Long, String>>()
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        runBlocking { withTimeout(60_000) { m.connect(f.server, { chunks += ms() to it }, {}) } }
        val connected = ms()
        waitUntil(15_000, "the shell prompt") { chunks.isNotEmpty() }
        val prompt = chunks.first().first
        val sent = ms()
        m.sendInput("tmux attach-session -t '=$SESSION'\n")
        waitUntil(15_000, "the attached screen") { chunks.any { it.first >= sent && it.second.contains(READY) } }
        val first = chunks.first { it.first >= sent && it.second.contains(READY) }.first
        return linkedMapOf("channelOpen" to connected, "prompt" to prompt, "attach" to first - sent, "total" to first)
    }

    @Test fun coldAttachTimingsWithTheShellTransportDrainedAndUp() {
        val pool = ServerTransportPool(storage).also { pools += it }
        val keeper = SshManager(storage, transportPool = pool).also { managers += it }
        // DRAINED: nothing leased yet => the connect includes the SSH handshake
        val h0 = f.acceptedHandshakes()
        // UP is measured second, with the first tab's transport still alive
        runBlocking { withTimeout(60_000) { keeper.connect(f.server, {}, {}) } }
        val h1 = f.acceptedHandshakes()
        assertEquals(h0 + 1, h1, "the keeper's connect was the handshake")
        val up = coldAttach(pool)
        assertEquals(h1, f.acceptedHandshakes(), "UP: the attach rode the live transport (no new handshake)")
        println("S05 transport=up   " + up.entries.joinToString(" ") { "${it.key}=${it.value}ms" })

        // DRAINED for real: kill the transport, so the next lease prunes it and shakes hands again
        pool.teardownAll()
        val before = f.acceptedHandshakes()
        val drained = coldAttach(pool)
        assertEquals(before + 1, f.acceptedHandshakes(), "DRAINED: the attach paid for one handshake")
        println("S05 transport=drained " + drained.entries.joinToString(" ") { "${it.key}=${it.value}ms" })
        assertTrue(up.getValue("total") < 15_000 && drained.getValue("total") < 15_000, "sanity bound: up=$up drained=$drained")
    }

    private companion object {
        const val SESSION = "s05attach"
        const val READY = "S05READY"
    }
}
