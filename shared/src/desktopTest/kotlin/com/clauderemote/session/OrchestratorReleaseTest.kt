package com.clauderemote.session

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionStatus
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.LinkState
import com.clauderemote.session.service.ServerBootstrap
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.JSchException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NN3: an orchestrator that a new one replaced (a recreated Android activity) is shut down for good: nothing of it
 * starts, revives or reconnects anything, its pollers stop and its shells are detached; the network events of the
 * process reach only the current orchestrator, through ONE platform callback.
 * NN4: the exec transport's ensure and every close decide under one per-server lock.
 */
class OrchestratorReleaseTest {
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedLog = FileLogger.platformLog
    private val tabs = TabManager()
    private val storage = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private val orchestrators = CopyOnWriteArrayList<SessionOrchestrator>()
    private val opens = AtomicInteger()
    private val fake = FakeSession { _ -> opens.incrementAndGet(); throw JSchException("channel is not opened.") }

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        NetworkCallbackHub.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
        storage.addServer(server)
    }

    @AfterTest fun tearDown() {
        orchestrators.forEach { it.execEnsureHook = null; it.execCloseHook = null; it.shutdownForTest() }
        FileLogger.platformLog = savedLog
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.liveSessionProvider = null
        SshSessionHelper.fallbackRunner = null
        NetworkCallbackHub.resetForTest()
        CommFlags.resetForTest()
    }

    private class FakeBootstrap : ServerBootstrap {
        val ensured = CopyOnWriteArrayList<String>()
        override suspend fun ensure(serverId: String) { ensured += serverId }
        override fun invalidate(serverId: String) {}
        override suspend fun refreshFlags(serverId: String) {}
    }

    private fun orchestrator() = SessionOrchestrator(storage, tabs).also {
        it.connectionRegistry.bootstrap = FakeBootstrap()
        orchestrators += it
    }

    private fun tab(id: String, status: SessionStatus = SessionStatus.ACTIVE) = ClaudeSession(
        id = id, server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-server-$id", connectionType = ConnectionType.SSH, status = status,
    )

    /** A tab whose shell counts as connected (what makes a bootstrap kick and a poller go). */
    private fun connectedTab(o: SessionOrchestrator, id: String) {
        tabs.addTab(tab(id))
        o.connectionRegistry.putSsh(id, BudgetFixture.connectedManager(storage, fake))
    }

    private fun eventually(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(20) }
        assertTrue(cond(), "timed out waiting for $what")
    }

    // ---- NN3: the released guard on every path ------------------------------------------------------------------------

    @Test fun aReleasedOrchestratorStartsNoExecTransportAndKicksNoBootstrap() {
        val o = orchestrator()
        connectedTab(o, "t1")
        val reached = AtomicInteger()
        o.execEnsureHook = { reached.incrementAndGet() }
        o.shutdown()
        o.ensureExecTransports()
        o.ensureExecTransport(server.id)
        Thread.sleep(300)
        assertEquals(0, reached.get(), "no exec transport is started by a replaced orchestrator")
        assertEquals(emptyList(), (o.connectionRegistry.bootstrap as FakeBootstrap).ensured.toList(), "and no bootstrap kicked")
    }

    @Test fun aReleasedOrchestratorReconnectsNoTab() = runBlocking {
        val o = orchestrator()
        tabs.addTab(tab("t1", SessionStatus.DISCONNECTED))
        o.shutdown()
        withTimeout(10_000) { o.reconnectSession("t1") }
        withTimeout(10_000) { o.autoReconnect(tabs.getTab("t1")!!, {}) }
        o.reconnectDeadTabs("test")
        o.onNetworkAvailable()
        o.onNetworkLost()
        Thread.sleep(300)
        assertTrue(logLines.none { it.contains("Reconnecting session t1") }, "reconnectSession: ${logLines.filter { "t1" in it }}")
        assertTrue(logLines.none { it.contains("Auto-reconnect attempt") }, "autoReconnect: ${logLines.filter { "Auto" in it }}")
        assertTrue(logLines.none { it.contains("Reconnect sweep") }, "the sweep")
        assertTrue(logLines.none { it.contains("Network lost") }, "the network events")
    }

    @Test fun shutdownStopsThePollersAndDetachesTheShellsButKeepsTheTabs() {
        val o = orchestrator()
        connectedTab(o, "t1")
        val manager = o.connectionRegistry.ssh("t1")!!
        CommFlags.set(CommFlags.Raw(exec = false))      // the status poller then uses the tab's own session
        o.remoteStatusFlow("t1")
        eventually("the status poller polls") { opens.get() > 0 }
        o.shutdown()
        eventually("the shell detached") { !manager.isConnected }
        // The NEW orchestrator's wiring of the process-wide helper: a poller of the replaced one that went on ticking
        // would now run its polls through it (the new orchestrator's pool).
        val viaNewWiring = AtomicInteger()
        SshSessionHelper.fallbackRunner = object : com.clauderemote.connection.FallbackRunner {
            override suspend fun <T> run(server: SshServer, block: suspend (com.jcraft.jsch.Session) -> T): com.clauderemote.connection.Fallback<T>? {
                viaNewWiring.incrementAndGet()
                return null
            }
        }
        val after = opens.get()
        Thread.sleep(5_600)                               // more than one poll interval (5 s)
        assertEquals(after, opens.get(), "a replaced orchestrator's poller must not poll on")
        assertEquals(0, viaNewWiring.get(), "nor poll through the process-wide helper, now wired to the new orchestrator")
        assertTrue(tabs.getTab("t1") != null, "the tab list (and with it the tmux session) is untouched")
        assertTrue(logLines.any { it.contains("Orchestrator replaced") })
        o.shutdown()
        assertEquals(1, logLines.count { it.contains("Orchestrator replaced") }, "idempotent")
    }

    // ---- NN3: one platform callback per process, delivered to the current orchestrator ---------------------------------

    private class RecordingTarget : NetworkCallbackHub.Target {
        val events = CopyOnWriteArrayList<String>()
        override fun onNetworkAvailable() { events += "available" }
        override fun onNetworkLost() { events += "lost" }
        override fun reconnectDeadTabs(reason: String) { events += "sweep:$reason" }
    }

    @Test fun aRecreatedActivityRegistersNoSecondCallbackAndEventsReachOnlyTheCurrentOrchestrator() {
        val first = RecordingTarget()
        val second = RecordingTarget()
        var current: NetworkCallbackHub.Target = first
        val registrations = CopyOnWriteArrayList<NetworkCallbackHub.Listener>()
        NetworkCallbackHub.register({ current }) { registrations += it }       // activity 1
        current = second
        NetworkCallbackHub.register({ current }) { registrations += it }       // activity 2 (recreated)
        assertEquals(1, registrations.size, "one platform callback per process")
        registrations[0].onLost()
        registrations[0].onAvailable()
        assertEquals(emptyList(), first.events.toList(), "the replaced orchestrator gets nothing")
        assertEquals(listOf("lost", "available", "sweep:onAvailable"), second.events.toList())
        assertEquals(1, logLines.count { it.contains("Default network lost") }, "one line per event")
    }

    @Test fun aRegistrationThatFailedIsTriedAgainByTheNextActivity() {
        var calls = 0
        NetworkCallbackHub.register({ null }) { calls++; throw SecurityException("no permission") }
        NetworkCallbackHub.register({ null }) { calls++ }
        NetworkCallbackHub.register({ null }) { calls++ }
        assertEquals(2, calls)
    }

    @Test fun theOrchestratorSweepsItsDeadAndFailedTabsOnce() {
        val o = orchestrator()
        tabs.addTab(tab("t1", SessionStatus.DISCONNECTED))
        tabs.addTab(tab("t2", SessionStatus.ERROR))
        connectedTab(o, "t3")   // C4: ACTIVE over a live shell (an ACTIVE tab without one is swept too, ReconnectLivenessTest)
        o.reconnectDeadTabs("onResume")
        eventually("the sweep reconnects both") {
            logLines.any { it.contains("Reconnecting session t1") } && logLines.any { it.contains("Reconnecting session t2") }
        }
        assertTrue(logLines.any { it.contains("Reconnect sweep (onResume): 2 dead tab(s)") }, "$logLines")
        assertTrue(logLines.none { it.contains("Reconnecting session t3") })
    }

    // ---- NN4: ensure and close under one per-server lock ----------------------------------------------------------------

    @Test fun aTabOpenedWhileTheLastOneClosesKeepsItsExecTransport() = runBlocking {
        val o = orchestrator()
        tabs.addTab(tab("t1"))
        o.ensureExecTransports()
        eventually("the exec transport runs") { o.execManager.state(server.id).value !is LinkState.Closed }
        var opener: Thread? = null
        o.execCloseHook = {
            if (opener == null) {
                // between the "no tab left" check and the close: a new tab opens and asks for the exec transport
                opener = Thread { tabs.addTab(tab("t2")); o.ensureExecTransport(server.id) }.also { it.start() }
                Thread.sleep(300)
            }
        }
        o.disconnectSession("t1")
        opener!!.join(5_000)
        o.execCloseHook = null
        eventually("the new tab's exec transport") { o.execManager.state(server.id).value !is LinkState.Closed }
        Thread.sleep(300)
        assertFalse(o.execManager.state(server.id).value is LinkState.Closed, "closed behind the back of the tab that opened meanwhile")
    }

    @Test fun theExecFlagSwitchedOffWhileATabEnsuresItsTransportLeavesItClosed() {
        val o = orchestrator()
        tabs.addTab(tab("t1"))
        o.ensureExecTransport(server.id)                  // the flag watcher of the server starts here
        eventually("the exec transport runs") { o.execManager.state(server.id).value !is LinkState.Closed }
        var flipped = false
        var closedWhileEnsuring: Boolean? = null
        o.execEnsureHook = {
            if (!flipped) {
                flipped = true
                // between the checks of ensure (flag on) and the start: the kill switch. Its close must wait for this
                // ensure to finish (the manager re-checks the flag only before its own lock, so a close slipping in
                // between would be undone by the start that follows).
                Thread { CommFlags.set(CommFlags.Raw(exec = false)) }.also { it.start() }.join()
                Thread.sleep(400)
                closedWhileEnsuring = o.execManager.state(server.id).value is LinkState.Closed
            }
        }
        o.ensureExecTransport(server.id)                  // e.g. a reconnecting tab
        o.execEnsureHook = null
        assertEquals(false, closedWhileEnsuring, "the kill switch's close ran in the middle of an ensure")
        eventually("closed by the kill switch") { o.execManager.state(server.id).value is LinkState.Closed }
        Thread.sleep(400)
        assertTrue(o.execManager.state(server.id).value is LinkState.Closed, "an exec transport started after the kill switch closed it")
    }

    /**
     * K4-4 (round 5): the kill switch flipped right after the first ensure of a server, before the flag watcher's
     * coroutine got to run. Deterministic: every thread of Dispatchers.IO (the orchestrator's scope) is held busy while
     * the ensure and the flip happen, so the watcher runs only after the flip. A watcher that reads the flag itself
     * then takes the flip for the old value and never closes the transport.
     */
    @Test fun aKillSwitchRightAfterTheFirstEnsureIsSeenEvenWhenTheWatcherStartsLate() {
        val o = orchestrator()
        tabs.addTab(tab("t1"))
        val par = System.getProperty("kotlinx.coroutines.io.parallelism")?.toIntOrNull() ?: maxOf(64, Runtime.getRuntime().availableProcessors())
        val started = java.util.concurrent.CountDownLatch(par)
        val release = java.util.concurrent.CountDownLatch(1)
        val blockers = (1..par).map {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { started.countDown(); release.await() }
        }
        try {
            // all IO threads busy (a blocker still queued means the same: no IO thread is free)
            started.await(3, java.util.concurrent.TimeUnit.SECONDS)
            o.ensureExecTransport(server.id)                 // starts the flag watcher (queued) and the transport
            CommFlags.set(CommFlags.Raw(exec = false))       // the kill switch, before the watcher ran
        } finally {
            release.countDown()
        }
        runBlocking { blockers.forEach { it.join() } }
        eventually("the kill switch closes the exec transport") { o.execManager.state(server.id).value is LinkState.Closed }
        Thread.sleep(400)
        assertTrue(o.execManager.state(server.id).value is LinkState.Closed, "closed for good")
    }

    @Test fun releasingTheExecLayerWhileATabEnsuresItsTransportLeavesItClosed() {
        val o = orchestrator()
        tabs.addTab(tab("t1"))
        o.ensureExecTransport(server.id)
        eventually("the exec transport runs") { o.execManager.state(server.id).value !is LinkState.Closed }
        var releaser: Thread? = null
        o.execEnsureHook = {
            if (releaser == null) {
                releaser = Thread { o.releaseExecLayer() }.also { it.start() }
                Thread.sleep(300)
            }
        }
        o.ensureExecTransport(server.id)
        o.execEnsureHook = null
        releaser!!.join(5_000)
        Thread.sleep(200)
        assertTrue(o.execManager.state(server.id).value is LinkState.Closed, "releaseExecLayer must not be overtaken by an ensure in flight")
    }

    // ---- review round 1 (low): the FIRST ensure of a server is not yet among the ensured servers when release looks ----

    @Test fun releasingTheExecLayerWhileTheFirstEnsureOfAServerIsPastItsChecksLeavesItClosed() {
        val o = orchestrator()
        tabs.addTab(tab("t1"))
        var releaser: Thread? = null
        o.execEnsureHook = {
            if (releaser == null) {
                // past the checks of the server's very first ensure, before it registers the server: the release looks now
                releaser = Thread { o.releaseExecLayer() }.also { it.start() }
                Thread.sleep(300)
            }
        }
        o.ensureExecTransport(server.id)
        o.execEnsureHook = null
        releaser!!.join(5_000)
        Thread.sleep(200)
        // review B-3 testy-spojeni F1: the cause seen under load was the supervisor writing its state over Closed (fixed in
        // ExecTransportManager, deterministic in ExecTransportCloseRaceTest), not a transport started after the release
        val state = o.execManager.state(server.id).value
        assertTrue(state is LinkState.Closed, "state after close is not Closed: $state")
    }

    // ---- review round 1 (low): shutdown() also ends the native mosh / ET clients of the replaced orchestrator ----

    private fun setProcess(target: Any, p: Process) =
        target.javaClass.getDeclaredField("process").also { it.isAccessible = true }.set(target, p)

    @Test fun shutdownAlsoStopsTheMoshAndEtClientsOfItsTabs() {
        val o = orchestrator()
        tabs.addTab(tab("t1"))
        tabs.addTab(tab("t2"))
        val moshClient = ProcessBuilder("sleep", "60").start()
        val etClient = ProcessBuilder("sleep", "60").start()
        try {
            o.connectionRegistry.putMosh("t1", com.clauderemote.connection.MoshManager().also { setProcess(it, moshClient) })
            o.connectionRegistry.putEt("t2", com.clauderemote.connection.EtManager().also { setProcess(it, etClient) })
            o.shutdown()
            eventually("the mosh and ET clients ended") { !moshClient.isAlive && !etClient.isAlive }
            assertTrue(o.connectionRegistry.mosh("t1") == null && o.connectionRegistry.et("t2") == null, "and are out of the registry")
            assertTrue(logLines.any { it.contains("2 mosh/ET client(s)") }, "$logLines")
        } finally {
            moshClient.destroyForcibly()
            etClient.destroyForcibly()
        }
    }

    // ---- round 3 (kod-spojeni L3): a mosh / ET connect that completes after shutdown() stops its client itself ----

    @Test fun aMoshAndEtClientWhoseConnectEndsAfterShutdownAreStoppedNotAdopted() {
        val o = orchestrator()
        tabs.addTab(tab("t1"))
        tabs.addTab(tab("t2"))
        val moshClient = ProcessBuilder("sleep", "60").start()
        val etClient = ProcessBuilder("sleep", "60").start()
        try {
            o.shutdown()   // the snapshot of shutdown() is taken; both connects are still running
            val mosh = com.clauderemote.connection.MoshManager().also { setProcess(it, moshClient) }
            val et = com.clauderemote.connection.EtManager().also { setProcess(it, etClient) }
            val adopted = runBlocking { listOf(o.adoptMosh("t1", mosh), o.adoptEt("t2", et)) }
            eventually("the late mosh and ET clients ended") { !moshClient.isAlive && !etClient.isAlive }
            assertTrue(adopted.none { it }, "a released orchestrator adopts no client: $adopted")
            assertTrue(o.connectionRegistry.mosh("t1") == null && o.connectionRegistry.et("t2") == null, "and keeps none in its registry")
        } finally {
            moshClient.destroyForcibly()
            etClient.destroyForcibly()
        }
    }

    @Test fun beforeShutdownAConnectedMoshClientIsAdopted() {
        val o = orchestrator()
        tabs.addTab(tab("t1"))
        val moshClient = ProcessBuilder("sleep", "60").start()
        try {
            val mosh = com.clauderemote.connection.MoshManager().also { setProcess(it, moshClient) }
            assertTrue(runBlocking { o.adoptMosh("t1", mosh) })
            assertTrue(o.connectionRegistry.mosh("t1") === mosh && moshClient.isAlive)
        } finally {
            moshClient.destroyForcibly()
        }
    }

    // ---- round 4 (kod L8): the mosh and ET connect paths hand their client over through adoptMosh / adoptEt ----
    // (connectMosh / connectEt start real clients, so this is read off the source: a connect path that puts its client
    // straight into the registry again would keep a client alive past shutdown(); the tests above cover adopt* itself)

    @Test fun theMoshAndEtConnectPathsAdoptTheirClientAndNothingElsePutsOne() {
        val src = java.io.File("src/commonMain/kotlin/com/clauderemote/session/SessionOrchestrator.kt").readText()
        val decl = Regex("""\n    (?:(?:private|internal|public|override|protected) )*(?:suspend )?fun """)
        fun body(name: String): String {
            val start = src.indexOf("fun $name(").also { assertTrue(it >= 0, "fun $name not found") }
            val end = decl.find(src, start + 1)?.range?.first ?: src.length
            return src.substring(start, end)
        }
        assertTrue("adoptMosh(session.id, moshManager)" in body("connectMosh"), "connectMosh hands its client to adoptMosh")
        assertTrue("adoptEt(session.id, etManager)" in body("connectEt"), "connectEt hands its client to adoptEt")
        for ((put, adopt) in listOf("putMosh(" to "adoptMosh", "putEt(" to "adoptEt")) {
            val sites = Regex(Regex.escape(put)).findAll(src).count()
            assertEquals(1, sites, "$put is called only from $adopt (found $sites call sites)")
            assertTrue(put in body(adopt), "and that one call is in $adopt")
        }
    }
}
