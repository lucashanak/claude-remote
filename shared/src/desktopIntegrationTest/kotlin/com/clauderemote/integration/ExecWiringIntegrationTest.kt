package com.clauderemote.integration

import com.clauderemote.connection.SshManager
import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.connection.TransportRole
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.PortForward
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.ExecUnavailableReason
import com.clauderemote.session.service.LinkState
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real orchestrator wiring against a real sshd (MaxSessions 10, the default): one exec transport per
 * server however many tabs ask for it, exec work with zero shells, the fallback with channel headroom when the
 * exec transport is down (the tabs sit 8 to a transport), the handshake gate, and the log shipper over exec.
 */
class ExecWiringIntegrationTest {
    private var fixture: SshdFixture? = null
    private val storage = ServerStorage(InMemoryKeyValueStore())
    private val tabs = TabManager()
    private lateinit var orchestrator: SessionOrchestrator
    private val managers = CopyOnWriteArrayList<SshManager>()
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedPlatformLog = FileLogger.platformLog
    private val savedSink = FileLogger.remoteSink
    private val savedExecChannels = SshSessionHelper.execChannelsProvider
    private val savedLiveSession = SshSessionHelper.liveSessionProvider

    // every wait in here is bounded by its own timeout; this is the last resort that fails a test whose blocking
    // call never returns instead of hanging the suite
    @get:org.junit.Rule(order = 0) val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(300)

    @get:org.junit.Rule(order = 1) val dumpOnFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable?, description: org.junit.runner.Description?) {
            println("---- log lines of ${description?.methodName} ----\n" + logLines.takeLast(200).joinToString("\n"))
        }
    }

    @Before fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
    }

    @After fun tearDown() {
        // The orchestrator's loops (notify watcher, reconcile, bootstrap retry, reconnects, stats) run on its own
        // scope and outlive the test unless it is ended: they logged into the next test's log lines and reconnected
        // against its server (a second "Server bootstrap integration:" line, a 16-session watcher from another test).
        if (::orchestrator.isInitialized) {
            orchestrator.shutdownForTest()
            orchestrator.connectionRegistry.teardownAllTransports()
            runCatching { orchestrator.execManager.close(serverId) }
        }
        managers.forEach { it.shutdownForTest() }
        FileLogger.platformLog = savedPlatformLog
        FileLogger.remoteSink = savedSink
        // process-global providers (every orchestrator overwrites them): put back what was there before the test
        SshSessionHelper.execChannelsProvider = savedExecChannels
        SshSessionHelper.liveSessionProvider = savedLiveSession
        CommFlags.resetForTest()
        fixture?.close()
    }

    private val serverId get() = fixture!!.server.id
    private val registry get() = orchestrator.connectionRegistry
    private val pool get() = registry.transportPool(serverId)

    private fun boot(extraSshdConfig: String = ""): SshdFixture {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        val f = SshdFixture.start(extraSshdConfig).also { fixture = it }
        storage.addServer(f.server)
        orchestrator = SessionOrchestrator(storage, tabs)
        return f
    }

    private fun tab(f: SshdFixture, id: String) = ClaudeSession(
        id = id, server = f.server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-server-$id", connectionType = ConnectionType.SSH,
    )

    private fun addTabs(f: SshdFixture, n: Int) = (1..n).map { i -> tab(f, "t$i").also { tabs.addTab(it) } }

    private fun execUp() = runBlocking {
        assertNotNull(withTimeout(30_000) { orchestrator.execManager.await(serverId, 25_000) }, "exec transport did not come up")
    }

    private fun established(f: SshdFixture): Int =
        f.run("ss", "-Htn", "state", "established", "( sport = :${f.port} )").out.lines().count { it.isNotBlank() }

    /** A tab shell on the pool of the server, registered the way the orchestrator does it. */
    private fun connectShells(f: SshdFixture, ids: List<String>) = runBlocking {
        val ms = ids.map { id -> SshManager(storage, transportPool = pool).also { m -> managers += m; registry.putSsh(id, m) } }
        withTimeout(90_000) { ms.map { m -> async(Dispatchers.IO) { m.connect(f.server, {}, {}) } }.awaitAll() }
        ms
    }

    // ---- one ensure for N tabs, zero shells -----------------------------------------------------

    @Test fun twentyTabsAskingForTheExecTransportCostOneHandshakeAndItWorksWithZeroShells() {
        val f = boot()
        addTabs(f, 20)
        runBlocking {
            // every connect path of every tab calls ensure; so does the foreground sweep
            (1..20).map { async(Dispatchers.Default) { orchestrator.ensureExecTransport(serverId) } }.awaitAll()
            orchestrator.ensureExecTransports()
        }
        execUp()
        assertEquals(1, f.acceptedHandshakes(), "one handshake for 20 tabs")
        assertEquals(1, pool.liveCount())
        assertEquals(1, pool.liveCount(TransportRole.EXEC))
        assertEquals(0, pool.liveCount(TransportRole.SHELL), "no shell was needed")

        // the services' work rides that one transport
        runBlocking {
            assertEquals("hi", registry.execRead(serverId, "echo hi")!!.trim())
            assertEquals(0, registry.execRun(serverId, "true")!!.exitStatus)
            registry.useChannel(serverId, ExecKind.LONG) { ch -> assertNotNull(ch) }
            assertEquals("seen", SshSessionHelper.withSession(f.server, allowFresh = false) { sess ->
                sess.execCapture("echo seen").out.trim()
            })
        }
        assertEquals(1, f.acceptedHandshakes(), "still one: nothing opened a connection of its own")
        assertEquals(1, established(f), "one TCP connection to the box")
        assertEquals(0, f.refusedChannelOpens())
    }

    @Test fun theShellCapBecomesEightWhenTheExecTransportIsUp() {
        val f = boot()
        addTabs(f, 10)
        // before: nothing is up, the legacy cap of 3 (4 transports for 10 shells)
        val before = connectShells(f, (1..10).map { "pre$it" })
        assertEquals(4, before.map { it.getSession() }.distinct().size)
        pool.teardownAll(); managers.forEach { it.shutdownForTest() }; managers.clear()

        orchestrator.ensureExecTransport(serverId)
        execUp()
        val after = connectShells(f, (1..10).map { "post$it" })
        assertEquals(2, after.map { it.getSession() }.distinct().size, "ceil(10/8) shell transports once exec is up")
    }

    // ---- the fallback needs channel headroom ------------------------------------------------------

    /** Hold `n` extra shell-less channels on the shell transport the tabs ride, i.e. what an unguarded fallback would do. */
    private fun rawChannels(session: com.jcraft.jsch.Session, n: Int, hold: CompletableDeferred<Unit>): List<Result<Unit>> = runBlocking {
        (1..n).map {
            async(Dispatchers.IO) {
                runCatching {
                    val ch = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
                    ch.setCommand("sleep 30")
                    ch.connect(5_000)
                    hold.await()
                    ch.disconnect()
                }
            }
        }.awaitAll()
    }

    @Test fun withEightShellsOnATransportAndNoExecTransportTheFallbacksAreRefusedNotOverflowing() {
        val f = boot()
        addTabs(f, 8)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        connectShells(f, (1..8).map { "t$it" })
        assertEquals(1, pool.liveCount(TransportRole.SHELL), "8 shells ride ONE transport (cap 8)")
        orchestrator.execManager.close(serverId)   // the exec transport is gone for good

        runBlocking {
            assertNull(registry.execRun(serverId, "echo x"), "a short exec has nowhere to go: no transport has room")
            assertNull(registry.execRead(serverId, "echo x"))
            val e = assertFailsWith<ExecUnavailable> { registry.useChannel(serverId, ExecKind.LONG) { } }
            assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
            assertNull(registry.liveServerSession(serverId), "the watcher's fallback lookup finds nothing either")
            assertNull(registry.fallbackPooledSession(serverId))
            assertNull(SshSessionHelper.liveSessionProvider!!.invoke(f.server), "withSession's pooled step gets no transport without room")
            assertNull(registry.execOnTab(serverId, "echo x", 5_000), "nor does the bootstrap's fallback")
        }
        assertEquals(0, f.refusedChannelOpens(), "sshd was never asked for a channel it would refuse")

        // control: the same three transient channels on that transport (watcher, streamd, one short exec) overflow it
        val legacy = assertNotNull(registry.pooledSession(serverId), "the unguarded lookup would hand this transport out")
        val hold = CompletableDeferred<Unit>()
        val results = runBlocking {
            val r = async(Dispatchers.IO) { rawChannels(legacy, 3, hold) }
            Thread.sleep(2_000)
            hold.complete(Unit)
            r.await()
        }
        assertTrue(results.any { it.isFailure }, "8 shells + 3 channels is more than MaxSessions 10: $results")
        assertTrue(f.refusedChannelOpens() >= 1)
    }

    @Test fun withSevenShellsExactlyOneFallbackChannelRunsAtATimeAndTheCountComesBack() {
        val f = boot()
        addTabs(f, 7)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        connectShells(f, (1..7).map { "t$it" })
        orchestrator.execManager.close(serverId)

        runBlocking {
            val first = try { registry.execRead(serverId, "echo a") } catch (e: Exception) {
                throw AssertionError("first fallback failed: $e; sshd started ${f.sessionChannelOpens()} sessions, refused ${f.refusedChannelOpens()}; sshd.log tail:\n" +
                    File(f.root, "sshd.log").readLines().takeLast(30).joinToString("\n"), e)
            }
            assertEquals("a", first!!.trim(), "7 + 1 + the reserve of 2 = 10: one fits")
            val inside = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val stream = launch(Dispatchers.IO) {
                registry.useChannel(serverId, ExecKind.LONG) { _ -> inside.complete(Unit); release.await() }
            }
            withTimeout(10_000) { inside.await() }
            assertNull(registry.execRead(serverId, "echo b"), "the stream holds the one slot: a second fallback is refused locally")
            release.complete(Unit)
            stream.join()
            assertEquals("c", registry.execRead(serverId, "echo c")!!.trim(), "the slot came back")
        }
        assertEquals(0, f.refusedChannelOpens())
    }

    @Test fun aRunningShortFallbackHoldsTheOneSlotOfASevenShellTransport() {
        val f = boot()
        addTabs(f, 7)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        connectShells(f, (1..7).map { "t$it" })
        orchestrator.execManager.close(serverId)
        runBlocking {
            val slow = async(Dispatchers.IO) { registry.execRun(serverId, "sleep 2; echo slow", totalMs = 10_000) }
            Thread.sleep(700)
            assertNull(registry.execRead(serverId, "echo second"), "the running short exec holds the slot")
            assertEquals("slow", slow.await()!!.stdout.trim())
            assertEquals("third", registry.execRead(serverId, "echo third")!!.trim(), "and gives it back")
        }
        assertEquals(0, f.refusedChannelOpens())
    }

    @Test fun aFlagOffServerKeepsTheLegacyFallbackAndItsLookup() {
        val f = boot()
        CommFlags.set(CommFlags.Raw(exec = false))
        addTabs(f, 3)
        connectShells(f, (1..3).map { "t$it" })
        runBlocking {
            assertEquals("legacy", registry.execRead(serverId, "echo legacy")!!.trim())
            assertNotNull(registry.liveServerSession(serverId))
            assertNotNull(registry.fallbackPooledSession(serverId))
        }
    }

    // ---- a real launch through the orchestrator --------------------------------------------------

    @Test fun launchingTwoSessionsRunsOneBootstrapStartsTheExecTransportAndLogsTheAttachPhases() {
        val f = boot()
        val folder = f.home.absolutePath
        runBlocking {
            withTimeout(90_000) {
                listOf("claude-server-it1", "claude-server-it2").map { name ->
                    async(Dispatchers.IO) {
                        orchestrator.launchSession(f.server, folder, ClaudeMode.NORMAL, ClaudeModel.DEFAULT, ConnectionType.SSH, name)
                    }
                }.awaitAll()
            }
        }
        val attach = Regex("Attach \\S+ kind=cold ms=\\d+ phase=shell=\\d+,prompt=\\d+,decide=\\d+,attach=\\d+,firstByte=\\d+")
        waitUntil(30_000, "both Attach lines") { logLines.count { attach.containsMatchIn(it) } >= 2 }
        assertEquals(1, logLines.count { it.contains("Server bootstrap integration:") }, "20 tabs or 2: one bootstrap exec per server")
        assertEquals(1, pool.liveCount(TransportRole.EXEC), "the exec transport exists")
        assertEquals(2, f.acceptedHandshakes(), "one exec transport plus one shell transport carrying both tabs")
        assertTrue(logLines.any { it.contains("Exec transport integration up via") })
        assertEquals(0, f.refusedChannelOpens())
        // the per-tab session-id loop is replaced by the per-server reconcile: both tabs are registered with it and
        // its first round (one exec for the whole server) runs over the exec transport
        waitUntil(15_000, "the first reconcile round") { registry.reconcileExecCount() >= 1 }
        assertEquals(2, f.acceptedHandshakes(), "the reconcile opened no connection either")
    }

    // ---- the handshake gate with pool v2 --------------------------------------------------------

    private data class Measured(val wallMs: Long, val peakInflight: Int, val handshakes: Int)

    private fun twentyTabsConnectBehind(gate: () -> Semaphore): Measured {
        val f = boot()
        addTabs(f, 20)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        val base = f.acceptedHandshakes()
        val t0 = System.currentTimeMillis()
        val ms = (1..20).map { SshManager(storage, transportPool = pool).also { m -> managers += m } }
        runBlocking {
            withTimeout(120_000) {
                ms.map { m -> async(Dispatchers.IO) { gate().withPermit { m.connect(f.server, {}, {}) } } }.awaitAll()
            }
        }
        val wall = System.currentTimeMillis() - t0
        val peak = runBlocking { pool.peakInflightConnects() }
        return Measured(wall, peak, f.acceptedHandshakes() - base)
    }

    @Test fun aGateOfThreeInFrontOfTheSingleFlightSerializesTheHandshakesOfAWave() {
        val gate = Semaphore(3)
        val m = twentyTabsConnectBehind { gate }
        println("MEASURED gate=3 pool=on tabs=20: $m")
        assertEquals(3, m.handshakes, "ceil(20/8) transports either way")
        assertEquals(1, m.peakInflight, "but one handshake at a time: a tab holds its permit while it waits for the flight it joined")
    }

    @Test fun thePooledGateLetsTheHandshakesOfAWaveRunInParallel() {
        val m = twentyTabsConnectBehind { registry.connectGate(serverId) }
        println("MEASURED gate=registry(pool on) tabs=20: $m")
        assertEquals(3, m.handshakes)
        assertEquals(3, m.peakInflight, "all ceil(20/8) handshakes start together")
    }

    @Test fun withPoolOffTheGateOfThreeStays() {
        boot()
        CommFlags.set(CommFlags.Raw(pool = false, exec = false, hooks = false))
        assertEquals(3, registry.connectGate(serverId).availablePermits)
        CommFlags.set(CommFlags.Raw())
        assertTrue(registry.connectGate(serverId).availablePermits > 20)
    }

    // ---- log shipping over exec -----------------------------------------------------------------

    @Test fun theLogShipperFlushesOverTheExecTransportOfTheServerWithTheMostTabs() {
        val f = boot()
        addTabs(f, 3)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        val before = f.acceptedHandshakes()
        // the logger fans lines out to the shipper only once it has a log file (inside the fixture's own root,
        // which the fixture removes)
        FileLogger.init(File(f.root, "applog").also { it.mkdirs() })
        orchestrator.startLogShipping("e2e-app")
        FileLogger.log("ExecWiringIT", "a line that must reach the server through the exec layer")
        val file = File(f.home, ".claude-remote/logs/e2e-app.log")
        waitUntil(45_000, "the first flush (30 s period)", stepMs = 500) { file.isFile && file.readText().contains("must reach the server") }
        assertEquals(before, f.acceptedHandshakes(), "the flush opened no connection of its own")
        assertEquals(0, pool.liveCount(TransportRole.SHELL))
    }

    // ---- a reconnect wave never storms the server ---------------------------------------------------

    private fun sshdLog(f: SshdFixture): String = File(f.root, "sshd.log").takeIf { it.isFile }?.readText().orEmpty()

    @Test fun aWaveOfFortyNineLeasesWithoutAnExecTransportRunsAtMostThreeHandshakesAtOnceAndSshdDropsNone() {
        val f = boot("MaxStartups 10:30:100")
        val ms = (1..49).map { SshManager(storage, transportPool = pool).also { m -> managers += m } }
        runBlocking { withTimeout(150_000) { ms.map { m -> async(Dispatchers.IO) { m.connect(f.server, {}, {}) } }.awaitAll() } }
        val peak = runBlocking { pool.peakInflightConnects() }
        println("MEASURED wave 49 leases, exec down, MaxStartups 10:30:100: peak=$peak handshakes=${f.acceptedHandshakes()} transports=${pool.liveCount(TransportRole.SHELL)}")
        assertTrue(peak <= 3, "at most 3 handshakes at once, saw $peak")
        assertEquals(17, f.acceptedHandshakes(), "ceil(49/3) transports at the cap of 3, none lost to a dropped connection")
        assertTrue(!sshdLog(f).contains("MaxStartups"), "sshd threw nothing away: " + sshdLog(f).lines().filter { it.contains("MaxStartups") })
    }

    @Test fun theFirstReconnectsOfAWaveWaitForTheExecTransportSoTheyGetTheShellCapOfEight() {
        val f = boot("MaxStartups 10:30:100")
        val ts = addTabs(f, 16)
        orchestrator.onNetworkAvailable()   // opens the wave and starts the exec transport: Connecting for the next moments
        runBlocking { withTimeout(240_000) { ts.map { t -> async(Dispatchers.IO) { orchestrator.reconnectSession(t.id) } }.awaitAll() } }
        execUp()
        val shellTransports = pool.liveCount(TransportRole.SHELL)
        val peak = runBlocking { pool.peakInflightConnects() }
        println("MEASURED wave 16 reconnects: shell transports=$shellTransports peak=$peak handshakes=${f.acceptedHandshakes()}")
        assertTrue(shellTransports <= 3, "ceil(16/8) transports (+1 slack), not ceil(16/3)=6: $shellTransports")
        assertTrue(peak <= 3)
        // (refused channels are not asserted here: a reconnect's close-state and peer probes still ride the tab's own
        // shell transport, a limit of the wiring that is written down in docs/connection.md)
        assertTrue(!sshdLog(f).contains("MaxStartups"))
    }

    // ---- the exec kill switch at run time ---------------------------------------------------------

    private fun execClosed() = orchestrator.execManager.state(serverId).value is LinkState.Closed

    @Test fun theKillSwitchWithEightShellsOnATransportRunsTheBackgroundReadsOnlyWithRoomAndClosesTheExecTransport() {
        val f = boot()
        addTabs(f, 8)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        connectShells(f, (1..8).map { "t$it" })
        assertEquals(1, pool.liveCount(TransportRole.SHELL))
        CommFlags.set(CommFlags.Raw(exec = false))
        waitUntil(20_000, "the exec transport to be closed by the kill switch") { execClosed() }
        waitUntil(20_000, "its TCP connection to be gone") { established(f) == 1 }
        val results = runBlocking { (1..4).map { async(Dispatchers.IO) { registry.execRead(serverId, "echo x") } }.awaitAll() }
        assertTrue(results.all { it == null }, "8 shells + 1 + the reserve of 2 do not fit in 10: nothing runs, nothing is refused by sshd: $results")
        assertEquals(0, f.refusedChannelOpens(), "sshd was never asked for a channel it would refuse")
    }

    @Test fun theKillSwitchWithSixShellsLetsTheReadsThroughOneOrTwoAtATime() {
        val f = boot()
        addTabs(f, 6)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        connectShells(f, (1..6).map { "t$it" })
        CommFlags.set(CommFlags.Raw(exec = false))
        waitUntil(20_000, "the exec transport to be closed") { execClosed() }
        waitUntil(20_000, "its TCP connection to be gone") { established(f) == 1 }
        val results = runBlocking { (1..4).map { async(Dispatchers.IO) { registry.execRead(serverId, "echo x") } }.awaitAll() }
        assertTrue(results.any { it != null }, "6 shells + 1 + 2 = 9 fit: $results")
        assertEquals(0, f.refusedChannelOpens())
    }

    @Test fun theKillSwitchOnAServerWithMaxSessionsFourLeavesNoRefusedChannel() {
        val f = boot("MaxSessions 4")
        addTabs(f, 2)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        connectShells(f, listOf("t1", "t2"))
        CommFlags.set(CommFlags.Raw(exec = false))
        waitUntil(20_000, "the exec transport to be closed") { execClosed() }
        waitUntil(20_000, "its TCP connection to be gone") { established(f) == 1 }
        runBlocking { repeat(3) { assertEquals("x", registry.execRead(serverId, "echo x")!!.trim()) } }
        assertEquals(0, f.refusedChannelOpens())
    }

    @Test fun theKillSwitchGivesThePortForwardsOfTheExecTransportBackToAShellTransportAndTakesThemBack() {
        val f = boot()
        val remote = java.net.ServerSocket(0)
        val local = java.net.ServerSocket(0).use { it.localPort }
        val acceptor = Thread {
            while (!remote.isClosed) runCatching { remote.accept().use { c -> c.getOutputStream().write("PONG\n".toByteArray()) } }
        }.apply { isDaemon = true; start() }
        try {
            storage.updateServer(f.server.copy(portForwards = listOf(PortForward("L", local, "127.0.0.1", remote.localPort))))
            addTabs(f, 2)
            orchestrator.ensureExecTransport(serverId)
            execUp()
            fun ask() = java.net.Socket("127.0.0.1", local).use { it.soTimeout = 5_000; it.getInputStream().bufferedReader().readLine() }
            waitUntil(10_000, "the forward on the exec transport") { runCatching { ask() == "PONG" }.getOrDefault(false) }
            connectShells(f, listOf("t1", "t2"))   // the shells leave the forward to the exec transport

            CommFlags.set(CommFlags.Raw(exec = false))
            waitUntil(20_000, "the exec transport to be closed") { execClosed() }
            waitUntil(30_000, "the forward to answer on a shell transport") { runCatching { ask() == "PONG" }.getOrDefault(false) }
            assertEquals(1, established(f), "the exec connection is gone, the forward rides the shell transport")

            CommFlags.set(CommFlags.Raw())
            waitUntil(30_000, "the exec transport again") { orchestrator.execManager.state(serverId).value is LinkState.Up }
            waitUntil(30_000, "the forward to answer on the exec transport again") { runCatching { ask() == "PONG" }.getOrDefault(false) }
            assertEquals(0, f.refusedChannelOpens())
        } finally {
            remote.close(); acceptor.interrupt()
        }
    }

    @Test fun deletingTheServersForceOffFileLiftsItAtTheNextFlagsRead() {
        val f = boot()
        addTabs(f, 1)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        connectShells(f, listOf("t1"))
        val flagsFile = File(f.home, ".claude-remote/client-flags.json").also { it.parentFile.mkdirs() }
        flagsFile.writeText("""{"off":["exec"]}""")
        runBlocking { orchestrator.refreshServerFlags() }
        waitUntil(20_000, "exec forced off by the server") { !CommFlags.effectiveFor(serverId).exec }
        waitUntil(20_000, "the exec transport closed") { execClosed() }

        assertTrue(flagsFile.delete())
        runBlocking { orchestrator.refreshServerFlags() }   // the server forces exec off: still asked, over a tab transport
        waitUntil(30_000, "exec back on") { CommFlags.effectiveFor(serverId).exec }
        waitUntil(30_000, "the exec transport running again") { orchestrator.execManager.state(serverId).value is LinkState.Up }
    }

    // ---- a closing shell, the polls of a tab, the network loss --------------------------------------

    @Test fun disconnectingAShellThatSaysNothingReturnsAtOnce() {
        val f = boot()
        val m = connectShells(f, listOf("quiet")).single()
        Thread.sleep(1_500)   // the shell's prompt is long out; from here on it is silent
        val t0 = System.currentTimeMillis()
        runBlocking { withTimeout(2_000) { m.disconnect() } }
        println("MEASURED disconnect of a quiet shell: ${System.currentTimeMillis() - t0} ms")
    }

    @Test fun withEightShellsAndNoExecTransportThePollsOfTheTabsAreSkippedNotRefused() {
        val f = boot()
        val ts = (1..8).map { i -> tab(f, "t$i").copy(claudeSessionId = "00000000-0000-4000-8000-00000000000$i").also { tabs.addTab(it) } }
        orchestrator.ensureExecTransport(serverId)
        execUp()
        connectShells(f, ts.map { it.id })
        orchestrator.execManager.close(serverId)
        ts.forEach { orchestrator.transcriptFlow(it.id) }     // eight streams poll at once, each on its own session
        Thread.sleep(8_000)
        assertEquals(0, f.refusedChannelOpens(), "no poll opened a channel on a transport with no room")
    }

    @Test fun withEightShellsAndNoExecTransportTheRedrawKicksOpenNoChannelOfTheirOwn() {
        val f = boot()
        val ts = addTabs(f, 8)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        connectShells(f, ts.map { it.id })
        orchestrator.execManager.close(serverId)
        ts.forEach { orchestrator.kickRedraw(it.id, 100, 30) }   // eight refresh-client execs at once; no room for any of them
        Thread.sleep(3_000)
        assertEquals(0, f.refusedChannelOpens(), "a kick without room uses the geometry fallback, not a channel of the attached shell's session")
    }

    @Test fun theNetworkLossReachesTheExecManagerBeforeTheTransportsAreTornDown() {
        val f = boot()
        val ts = addTabs(f, 24)
        orchestrator.ensureExecTransport(serverId)
        execUp()
        connectShells(f, ts.map { it.id })   // 3 shell transports of 8: a teardown that takes a while
        val execSession = registry.execSessions!!.session(serverId)!!
        val sawTornDownWhileStillUp = java.util.concurrent.atomic.AtomicBoolean(false)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val sampler = Thread {
            while (!stop.get()) {
                // the exec transport is unusable (disconnected) while its manager still says Up: the teardown ran first
                if (!execSession.isConnected && orchestrator.execManager.state(serverId).value is LinkState.Up) sawTornDownWhileStillUp.set(true)
            }
        }.apply { isDaemon = true; start() }
        try {
            orchestrator.onNetworkLost()
            Thread.sleep(300)
        } finally { stop.set(true); sampler.join() }
        assertTrue(!sawTornDownWhileStillUp.get(), "the manager must be told (DownNetwork, no strike, no backoff) before its transport dies")
        assertTrue(orchestrator.execManager.state(serverId).value is LinkState.DownNetwork || orchestrator.execManager.state(serverId).value is LinkState.Connecting)
    }
}
