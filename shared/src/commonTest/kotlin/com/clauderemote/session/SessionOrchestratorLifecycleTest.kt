package com.clauderemote.session

import com.clauderemote.connection.SshManager
import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecResult
import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.ExecUnavailableReason
import com.clauderemote.session.service.LinkState
import com.clauderemote.session.service.Pace
import com.clauderemote.session.service.RemoteExec
import com.clauderemote.session.service.ServerBootstrap
import com.clauderemote.session.service.ServerReconciler
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Run-time behaviour of the exec wiring that needs no network: nothing the exec paths start may end the process,
 * the exec flag switched at run time, the closing of a server's last tab, one-off steps, the attach trace. The
 * connects these tests provoke go to a closed loopback port.
 */
class SessionOrchestratorLifecycleTest {
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedLog = FileLogger.platformLog
    private val tabs = TabManager()
    private val storage = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
        storage.addServer(server)
    }

    @AfterTest fun tearDown() {
        FileLogger.platformLog = savedLog
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.liveSessionProvider = null
        CommFlags.resetForTest()
    }

    private fun orchestrator(sessionStorage: SessionStorage? = null) =
        SessionOrchestrator(storage, tabs, sessionStorage).also { it.connectionRegistry.bootstrap = FakeBootstrap() }

    private fun tab(id: String, tmux: String) = ClaudeSession(
        id = id, server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = tmux, connectionType = ConnectionType.SSH,
    )

    private fun eventually(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(20) }
        assertTrue(cond(), "timed out waiting for $what")
    }

    /** A test body with a bound: a cooperative wait that never ends fails the test instead of hanging the suite. */
    private fun bounded(ms: Long = 30_000, body: suspend () -> Unit) = runBlocking { withTimeout(ms) { body() } }

    private class FakeBootstrap : ServerBootstrap {
        val ensured = CopyOnWriteArrayList<String>()
        val refreshed = CopyOnWriteArrayList<String>()
        override suspend fun ensure(serverId: String) { ensured += serverId }
        override fun invalidate(serverId: String) {}
        override suspend fun refreshFlags(serverId: String) { refreshed += serverId }
    }

    private class RecordingReconciler(val failWith: Exception? = null) : ServerReconciler {
        val calls = CopyOnWriteArrayList<String>()
        var onUnregister: (String) -> Unit = {}
        override fun register(sessionId: String) { calls += "register:$sessionId" }
        override fun unregister(sessionId: String) { calls += "unregister:$sessionId"; onUnregister(sessionId) }
        override suspend fun checkClosed(sessionId: String, delayMs: Long) { calls += "closed:$sessionId"; failWith?.let { throw it } }
        override suspend fun checkUuid(sessionId: String) { calls += "uuid:$sessionId"; failWith?.let { throw it } }
        override suspend fun capability(serverId: String, tmux: String): Boolean? = null
    }

    // ---- nothing the exec paths start may end the process ---------------------------------------

    @Test fun anExecFailureInTheHookSinkIsLoggedAndNeverReachesTheThreadsHandler() {
        val o = orchestrator()
        tabs.addTab(tab("t1", "claude-server-a"))
        val boom = ExecUnavailable(ExecUnavailableReason.TIMEOUT)
        o.connectionRegistry.reconciler = RecordingReconciler(failWith = boom)
        val caught = CopyOnWriteArrayList<Throwable>()
        val saved = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> caught += e }
        try {
            o.notificationService.hookSink.onCheckClosed(server.id, "claude-server-a", 0)
            o.notificationService.hookSink.onCheckUuid(server.id, "claude-server-a")
            eventually("both failures named in the log") {
                logLines.any { it.contains("checkClosed of claude-server-a failed") } && logLines.any { it.contains("checkUuid of claude-server-a failed") }
            }
            Thread.sleep(200)
            assertEquals(emptyList(), caught.toList(), "the failure must not reach the default handler (it ends an Android process)")
            assertTrue(logLines.none { it.contains("Uncaught exception in a background coroutine") }, "caught at the launch site, not by the safety net: $logLines")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(saved)
        }
    }

    @Test fun aFailureNobodyCaughtInTheBackgroundScopeIsLoggedByTheSafetyNet() {
        val o = orchestrator()
        val caught = CopyOnWriteArrayList<Throwable>()
        val saved = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> caught += e }
        try {
            val scope = SessionOrchestrator::class.java.getDeclaredField("reconnectScope").also { it.isAccessible = true }
                .get(o) as kotlinx.coroutines.CoroutineScope
            scope.launch { error("nobody catches this") }
            eventually("the safety net") { logLines.any { it.contains("Uncaught exception in a background coroutine") } }
            assertEquals(emptyList(), caught.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(saved)
        }
    }

    // ---- one-off steps --------------------------------------------------------------------------

    private class CapturingExec(val exit: Int = 0) : RemoteExec {
        val kinds = CopyOnWriteArrayList<ExecKind>()
        val commands = CopyOnWriteArrayList<String>()
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult {
            kinds += kind; commands += cmd
            return ExecResult("", exit)
        }
    }

    @Test fun theLogShipperFlushesAsAShortExecOnTheServerWithTheMostTabs() = bounded {
        val o = orchestrator()
        val exec = CapturingExec().also { o.connectionRegistry.remoteExec = it }
        assertFalse(o.shipLogChunk("cat >> x", ByteArray(1)), "no tab, no server")
        tabs.addTab(tab("t1", "claude-server-a"))
        assertTrue(o.shipLogChunk("cat >> x", ByteArray(1)))
        assertEquals(listOf(ExecKind.SHORT), exec.kinds.toList(), "one flush is a one-off command: SHORT, never a long channel")
    }

    @Test fun aTombstoneThatCouldNotBeClearedIsLoggedAndNoTransportIsReportedAsFalse() = bounded {
        val o = orchestrator()
        assertFalse(o.persistence.clearTombstone(server.id, "claude-server-a"), "no usable transport: the caller may ask again")
        assertTrue(logLines.any { it.contains("clearTombstone(claude-server-a) skipped") })
        o.connectionRegistry.remoteExec = CapturingExec(exit = 3)
        assertTrue(o.persistence.clearTombstone(server.id, "claude-server-a"))
        assertTrue(logLines.any { it.contains("clearTombstone(claude-server-a) exited with 3") }, "$logLines")
    }

    @Test fun aManifestPushThatFoundNoTransportSaysSoAndOneThatRanDoesNot() = bounded {
        val o = orchestrator(SessionStorage(FakeKeyValueStore()))
        assertFalse(o.persistence.pushSessionsToServer(server.id), "no usable transport: the launch waits for the exec transport and tries again")
        o.connectionRegistry.remoteExec = CapturingExec()
        assertTrue(o.persistence.pushSessionsToServer(server.id))
    }

    @Test fun aRestoreInstallThatFoundNoTransportIsNotRememberedAsDone() = bounded {
        val o = orchestrator()
        assertFalse(o.persistence.ensureRestoreService(server.id))
        assertFalse(o.persistence.ensureRestoreService(server.id), "asked again: nothing was installed the first time")
        assertEquals(2, logLines.count { it.contains("Restore service install: NO_SESSION") }, "both attempts ran: $logLines")
    }

    @Test fun aRestoreInstallThatSucceededIsNotRepeated() = bounded {
        val o = orchestrator()
        val exec = CapturingExec().also { o.connectionRegistry.remoteExec = it }
        assertTrue(o.persistence.ensureRestoreService(server.id))
        assertTrue(o.persistence.ensureRestoreService(server.id))
        assertEquals(1, exec.commands.size, "once per server and app run, once it worked")
    }

    // ---- the exec flag at run time ----------------------------------------------------------------

    @Test fun theExecFlagSwitchedOffAtRunTimeClosesTheTransportAndHandsTheTabBackToItsOwnLoopAndBack() {
        val o = orchestrator(SessionStorage(FakeKeyValueStore()))
        val rec = RecordingReconciler().also { o.connectionRegistry.reconciler = it }
        tabs.addTab(tab("t1", "claude-server-a"))
        o.connectionRegistry.putSsh("t1", SshManager(storage))   // a shell that is not connected: enough for the per-tab loop to start
        o.ensureExecTransports()
        eventually("the exec transport runs") { logLines.any { it.contains("Exec transport box connect failed") } }

        CommFlags.set(CommFlags.Raw(exec = false))
        eventually("the exec transport closed by the kill switch") { o.execManager.state(server.id).value is LinkState.Closed }
        eventually("the tab on its own loop") { o.persistence.idRefreshActive("t1") }
        assertTrue("unregister:t1" in rec.calls, "the reconcile of the server lets go of the tab: ${rec.calls}")

        CommFlags.set(CommFlags.Raw())
        eventually("the exec transport again") { o.execManager.state(server.id).value !is LinkState.Closed }
        eventually("the tab on the reconcile again") { "register:t1" in rec.calls }
        eventually("the per-tab loop stopped") { !o.persistence.idRefreshActive("t1") }
        o.releaseExecLayer()
    }

    @Test fun aServerWhoseExecGroupItsOwnFileSwitchedOffIsStillAskedForItsFlags() = bounded {
        val o = orchestrator()
        val fake = o.connectionRegistry.bootstrap as FakeBootstrap
        tabs.addTab(tab("t1", "claude-server-a"))
        CommFlags.applyServerOff(server.id, setOf("exec"))
        o.refreshServerFlags()
        assertEquals(listOf(server.id), fake.refreshed, "deleting client-flags.json lifts the force-off, and only this read can notice")
        CommFlags.applyServerOff(server.id, emptySet())
        CommFlags.set(CommFlags.Raw(exec = false))
        o.refreshServerFlags()
        assertEquals(1, fake.refreshed.size, "this device's own switch is not the server's business")
    }

    @Test fun theFlagsRefreshOfTheBackgroundIsSkippedAndTheForegroundRunsIt() = bounded {
        val o = orchestrator()
        val fake = o.connectionRegistry.bootstrap as FakeBootstrap
        tabs.addTab(tab("t1", "claude-server-a"))
        o.setBackgroundMode(true)
        o.flagsRefreshTick()
        assertEquals(emptyList(), fake.refreshed, "nobody sees a flag change while the app sleeps")
        o.setBackgroundMode(false)
        o.flagsRefreshTick()
        assertEquals(listOf(server.id), fake.refreshed)
    }

    @Test fun aBootstrapIsNotKickedForAServerWhereNothingCanAnswerItYet() {
        val o = orchestrator()
        val fake = o.connectionRegistry.bootstrap as FakeBootstrap
        tabs.addTab(tab("t1", "claude-server-a"))   // a tab that is not connected, and the exec transport does not exist
        o.ensureExecTransports()
        Thread.sleep(400)
        assertEquals(emptyList(), fake.ensured, "a bootstrap that fails with 'no transport' backs off for 10 s and then suppresses the connects' own")
        o.releaseExecLayer()
    }

    // ---- closing tabs ------------------------------------------------------------------------------

    @Test fun disconnectingATabLetsGoOfItInTheReconcile() = bounded {
        val o = orchestrator()
        val rec = RecordingReconciler().also { o.connectionRegistry.reconciler = it }
        tabs.addTab(tab("t1", "claude-server-a"))
        o.disconnectSession("t1")
        assertTrue("unregister:t1" in rec.calls, "${rec.calls}")
    }

    @Test fun aReconnectOfATabThatIsBeingClosedDoesNotStartTheExecTransportAgain() = bounded {
        val o = orchestrator()
        val rec = RecordingReconciler().also { o.connectionRegistry.reconciler = it }
        tabs.addTab(tab("t1", "claude-server-a"))
        var ownedDuringClose: Boolean? = null
        // the moment inside disconnectSession where the tab is still listed: what a reconnect in flight does there
        rec.onUnregister = {
            o.ensureExecTransport(server.id)
            ownedDuringClose = o.connectionRegistry.transportPool(server.id).execForwardsOwned(server.id)
        }
        o.disconnectSession("t1")
        assertEquals(false, ownedDuringClose, "a tab that is closing is no reason to start the exec transport")
        o.ensureExecTransport(server.id)
        assertFalse(o.connectionRegistry.transportPool(server.id).execForwardsOwned(server.id), "and with no tab left nothing starts it either")
    }

    @Test fun theExecTransportOfAServerIsClosedWithItsLastTabAndNotBeforeTheTeardownBegan() = bounded {
        val o = orchestrator()
        val rec = RecordingReconciler().also { o.connectionRegistry.reconciler = it }
        tabs.addTab(tab("t1", "claude-server-a"))
        o.ensureExecTransports()
        eventually("the exec transport runs") { logLines.any { it.contains("Exec transport box connect failed") } }
        var stateWhenTheTeardownBegan: LinkState? = null
        rec.onUnregister = { stateWhenTheTeardownBegan = o.execManager.state(server.id).value }
        o.disconnectSession("t1")
        assertFalse(stateWhenTheTeardownBegan is LinkState.Closed, "closed before the teardown of the tab even began: $stateWhenTheTeardownBegan")
        assertTrue(o.execManager.state(server.id).value is LinkState.Closed)
    }

    @Test fun disconnectAllClosesTheExecTransports() = bounded {
        val o = orchestrator()
        tabs.addTab(tab("t1", "claude-server-a"))
        o.ensureExecTransports()
        eventually("the exec transport runs") { logLines.any { it.contains("Exec transport box connect failed") } }
        o.disconnectAll()
        assertTrue(o.execManager.state(server.id).value is LinkState.Closed)
    }

    @Test fun releasingTheExecLayerClosesEveryTransportItStartedAndNothingRevivesThem() {
        val o = orchestrator()
        tabs.addTab(tab("t1", "claude-server-a"))
        o.ensureExecTransports()
        eventually("the exec transport runs") { logLines.any { it.contains("Exec transport box connect failed") } }
        o.releaseExecLayer()
        assertTrue(o.execManager.state(server.id).value is LinkState.Closed)
        CommFlags.set(CommFlags.Raw(exec = false)); CommFlags.set(CommFlags.Raw())
        Thread.sleep(300)
        assertTrue(o.execManager.state(server.id).value is LinkState.Closed, "the flag watcher is gone with it")
    }

    // ---- the attach trace skips the echo of the command ----------------------------------------------

    @Test fun theAttachTraceDoesNotCloseOnTheEchoOfTheCommandItSent() {
        var t = 0L
        val trace = AttachTrace { t }
        t += 10; trace.phase("shell")
        t += 5; trace.attachSent("tmux attach-session -t '=claude-server-a'")
        assertEquals(null, trace.firstByte("tmux attach-session "), "the pty echoes the command, in pieces")
        assertEquals(null, trace.firstByte("-t '=claude-server-a'\r\n\u001B[?2004l"), "the rest of it, the line break and bracketed paste off")
        t += 700
        val done = trace.firstByte("\u001B[?1049h\u001B[22;0;0t\u001B[1;1H")!!
        assertEquals(715L, done.totalMs)
        assertEquals(700L, done.phasesMs["firstByte"], "tmux's first output, not the echo one round trip after the send")
    }

    @Test fun theAttachTraceClosesOnTheFirstOutputThatIsNotTheEchoEvenWithoutTheAlternateScreen() {
        val trace = AttachTrace { 0L }
        trace.attachSent("tmux attach-session -t 'x'")
        assertEquals(null, trace.firstByte("tmux attach"))
        assertTrue(trace.firstByte("sessions should be nested with care") != null, "an error text is output, not echo")
        val other = AttachTrace { 0L }
        other.attachSent("tmux attach-session -t 'x'")
        assertTrue(other.firstByte("no sessions\r\n") != null, "a mismatch with the echo is output")
    }
}
