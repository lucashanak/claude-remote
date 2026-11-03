package com.clauderemote.session

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionActivity
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.CommLogFormat
import com.clauderemote.session.service.LinkState
import com.clauderemote.session.service.PooledExecTransportManager
import com.clauderemote.session.service.ServerReconciler
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the orchestrator does with the exec layer: the registry slots are filled in one step, the
 * lifecycle and hook-sink wiring, the FIX 4 rule for hook approvals, the attach trace. No network:
 * the one connect these tests provoke goes to a closed loopback port.
 */
class SessionOrchestratorWiringTest {
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedLog = FileLogger.platformLog
    private val tabs = TabManager()
    private val storage = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(
        id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p",
    )

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

    private fun orchestrator() = SessionOrchestrator(storage, tabs)

    /** For tests that make the orchestrator ask for a bootstrap: the real one subscribes to process-wide request flows for the rest of the JVM. */
    private fun orchestratorWithFakeBootstrap() = orchestrator().also { it.connectionRegistry.bootstrap = FakeBootstrap() }

    private fun tab(id: String, tmux: String) = ClaudeSession(
        id = id, server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = tmux, connectionType = ConnectionType.SSH,
    )

    private fun eventually(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(20) }
        assertTrue(cond(), "timed out waiting for $what")
    }

    // ---- the slots are filled in one step -------------------------------------------------------

    @Test fun everySeamSlotIsFilledByTheConstructor() {
        val o = orchestrator()
        val reg = o.connectionRegistry
        assertSame(o.execManager, reg.execSessions)
        assertNotNull(reg.execChannels)
        assertNotNull(reg.remoteExec)
        assertNotNull(reg.reconciler)
        assertNotNull(reg.bootstrap)
        // the helper that wraps one-off ops gets its permit source; with routing on it is the gate
        assertNotNull(SshSessionHelper.execChannelsProvider)
        assertNotNull(SshSessionHelper.execChannelsProvider!!.invoke(server.id))
        // and nothing at all when the server is switched to the legacy path
        CommFlags.set(CommFlags.Raw(exec = false))
        assertNull(SshSessionHelper.execChannelsProvider!!.invoke(server.id))
    }

    @Test fun theNotificationServiceGetsTheSinkAndTheClosures() {
        val o = orchestrator()
        assertTrue(o.notificationService.closures === o.persistence, "recentlyClosedLocally must be the persistence service")
        assertFalse(o.notificationService.hookSink === com.clauderemote.session.service.HookEventSink.NoOp)
    }

    @Test fun theHookSinkAsksTheReconcilerForTheTabOfThatServerAndTmuxName() {
        val o = orchestrator()
        tabs.addTab(tab("t1", "claude-server-a"))
        tabs.addTab(tab("t2", "claude-server-b"))
        val calls = CopyOnWriteArrayList<String>()
        o.connectionRegistry.reconciler = object : ServerReconciler {
            override fun register(sessionId: String) {}
            override fun unregister(sessionId: String) {}
            override suspend fun checkClosed(sessionId: String, delayMs: Long) { calls += "closed:$sessionId:$delayMs" }
            override suspend fun checkUuid(sessionId: String) { calls += "uuid:$sessionId" }
            override suspend fun capability(serverId: String, tmux: String): Boolean? = null
        }
        val sink = o.notificationService.hookSink
        sink.onCheckUuid(server.id, "claude-server-b")
        sink.onCheckClosed(server.id, "claude-server-a", 3_000)
        eventually("both sink calls") { calls.size == 2 }
        assertEquals(setOf("uuid:t2", "closed:t1:3000"), calls.toSet())
        // a name no tab has, and another server's tab of the same name: nothing
        calls.clear()
        sink.onCheckUuid(server.id, "claude-server-zzz")
        sink.onCheckUuid("other-server", "claude-server-a")
        Thread.sleep(150)
        assertEquals(emptyList(), calls.toList())
    }

    // ---- the pool's shell cap follows the exec transport ---------------------------------------

    @Test fun theShellCapPredicateIsTheExecTransportBeingUp() {
        val reg = orchestrator().connectionRegistry
        assertFalse(reg.execUp(server.id), "nothing running yet")
        reg.execSessions = PooledExecTransportManager { null }
        assertFalse(reg.execUp(server.id), "a manager whose transport is down")
        reg.execSessions = null
        assertFalse(reg.execUp(server.id), "no manager wired at all")
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    @Test fun ensureStartsTheExecTransportOnlyWhileTheExecFlagIsOn() {
        val o = orchestrator()
        tabs.addTab(tab("t1", "claude-server-a"))   // the exec transport lives while the server has a tab
        CommFlags.set(CommFlags.Raw(exec = false))
        o.ensureExecTransport(server.id)
        Thread.sleep(300)
        assertTrue(logLines.none { it.contains("Exec transport") }, "exec off: nothing is started: $logLines")

        CommFlags.set(CommFlags.Raw())
        o.ensureExecTransport(server.id)
        eventually("the connect attempt to the closed port") { logLines.any { it.contains("Exec transport box connect failed") } }
        o.execManager.close(server.id)
    }

    @Test fun ensureExecTransportsCoversEveryServerThatHasATab() {
        val o = orchestratorWithFakeBootstrap()
        tabs.addTab(tab("t1", "claude-server-a"))
        o.ensureExecTransports()
        eventually("the exec transport of the tab's server") { logLines.any { it.contains("Exec transport box connect failed") } }
        o.execManager.close(server.id)
        assertTrue(o.execManager.state(server.id).value is LinkState.Closed)
    }

    @Test fun theNetworkComingBackEnsuresTheExecTransportOfEveryServerThatHasATab() {
        val o = orchestratorWithFakeBootstrap()
        tabs.addTab(tab("t1", "claude-server-a"))
        o.onNetworkAvailable()
        eventually("the exec transport started by the network callback") { logLines.any { it.contains("Exec transport box connect failed") } }
        o.execManager.close(server.id)
    }

    @Test fun closingTheLastTabOfAServerClosesItsExecTransportButNotWhileAnotherTabRemains() = kotlinx.coroutines.runBlocking {
        val o = orchestratorWithFakeBootstrap()
        tabs.addTab(tab("t1", "claude-server-a"))
        tabs.addTab(tab("t2", "claude-server-b"))
        o.ensureExecTransports()
        eventually("the exec transport runs") { logLines.any { it.contains("Exec transport box connect failed") } }
        o.disconnectSession("t1")
        assertFalse(o.execManager.state(server.id).value is LinkState.Closed, "t2 is still on the server")
        o.disconnectSession("t2")
        assertTrue(o.execManager.state(server.id).value is LinkState.Closed, "state=${o.execManager.state(server.id).value}")
    }

    @Test fun theNetworkLossReachesTheExecManagerBeforeTheTeardown() {
        val o = orchestratorWithFakeBootstrap()
        tabs.addTab(tab("t1", "claude-server-a"))
        o.ensureExecTransports()
        eventually("the manager runs") { logLines.any { it.contains("Exec transport box connect failed") } }
        o.onNetworkLost()
        assertTrue(o.execManager.state(server.id).value is LinkState.DownNetwork, "state=${o.execManager.state(server.id).value}")
        o.execManager.close(server.id)
    }

    // ---- the bootstrap at connect, and the flags refresh ----------------------------------------

    private class FakeBootstrap(val onEnsure: suspend (String) -> Unit = {}) : com.clauderemote.session.service.ServerBootstrap {
        val ensured = CopyOnWriteArrayList<String>()
        val refreshed = CopyOnWriteArrayList<String>()
        override suspend fun ensure(serverId: String) { ensured += serverId; onEnsure(serverId) }
        override fun invalidate(serverId: String) {}
        override suspend fun refreshFlags(serverId: String) { refreshed += serverId }
    }

    @Test fun aRoutedConnectRunsTheBootstrapInsteadOfThePerTabEnsures() = kotlinx.coroutines.runBlocking {
        val o = orchestrator()
        val fake = FakeBootstrap().also { o.connectionRegistry.bootstrap = it }
        o.ensureServerSetup(server.id, com.clauderemote.connection.SshManager(storage))
        assertEquals(listOf(server.id), fake.ensured)
        assertTrue(logLines.none { it.contains("Stop hook setup") || it.contains("streamd setup") }, "no per-tab ensure: $logLines")
    }

    @Test fun aLegacyConnectKeepsThePerTabEnsuresAndNeverCallsTheBootstrap() = kotlinx.coroutines.runBlocking {
        val o = orchestrator()
        val fake = FakeBootstrap().also { o.connectionRegistry.bootstrap = it }
        CommFlags.set(CommFlags.Raw(exec = false))
        o.ensureServerSetup(server.id, com.clauderemote.connection.SshManager(storage))
        assertEquals(emptyList(), fake.ensured)
        assertTrue(logLines.any { it.contains("Stop hook setup") }, "the legacy per-tab ensure ran: $logLines")
    }

    @Test fun aConnectWaitsForTheBootstrapAtMostThreeSecondsAndTheBootstrapGoesOn() = kotlinx.coroutines.runBlocking {
        val o = orchestrator()
        val finished = kotlinx.coroutines.CompletableDeferred<Unit>()
        val fake = FakeBootstrap { kotlinx.coroutines.awaitCancellation() }.also { o.connectionRegistry.bootstrap = it }
        val t0 = System.currentTimeMillis()
        // bounded: a connect that waits for a bootstrap with no limit must fail this test, not hang the suite
        kotlinx.coroutines.withTimeout(20_000) { o.ensureServerSetup(server.id, com.clauderemote.connection.SshManager(storage)) }
        val waited = System.currentTimeMillis() - t0
        finished.complete(Unit)
        assertEquals(SessionOrchestrator.BOOTSTRAP_WAIT_MS, 3_000L)
        assertTrue(waited in 2_900..4_500, "waited ${waited}ms for a bootstrap that never answers")
        assertEquals(1, fake.ensured.size)
    }

    @Test fun theFlagsRefreshAsksOnlyServersThatHaveATabAndRunExecRouting() = kotlinx.coroutines.runBlocking {
        val o = orchestrator()
        val fake = FakeBootstrap().also { o.connectionRegistry.bootstrap = it }
        o.refreshServerFlags()
        assertEquals(emptyList(), fake.refreshed, "no tab, no server")
        tabs.addTab(tab("t1", "claude-server-a"))
        tabs.addTab(tab("t2", "claude-server-b"))
        o.refreshServerFlags()
        assertEquals(listOf(server.id), fake.refreshed, "once per server, not per tab")
        CommFlags.set(CommFlags.Raw(exec = false))
        o.refreshServerFlags()
        assertEquals(1, fake.refreshed.size, "a legacy server is not asked")
        assertEquals(10L * 60 * 1000, SessionOrchestrator.FLAGS_REFRESH_MS)
    }

    // ---- FIX 4: a hook approval survives leaving the tab --------------------------------------

    private fun approvalSetup(sinceMs: Long?): Pair<SessionOrchestrator, String> {
        val o = orchestrator()
        tabs.addTab(tab("a", "claude-server-a"))
        tabs.addTab(tab("b", "claude-server-b"))
        tabs.switchTab("a")
        o.approvalSinceOf = { _, tmux -> if (tmux == "claude-server-a") sinceMs else null }
        o.statusService.updateActivity("a", SessionActivity.APPROVAL_NEEDED)
        return o to "a"
    }

    @Test fun aRecentHookApprovalStaysAfterLeavingTheTab() {
        val (o, id) = approvalSetup(System.currentTimeMillis() - 60_000)
        o.switchTab("b")
        assertEquals(SessionActivity.APPROVAL_NEEDED, o.sessionActivities.value[id])
    }

    @Test fun anOldHookApprovalIsResetLikeAScreenGuess() {
        val (o, id) = approvalSetup(System.currentTimeMillis() - 11 * 60_000)
        o.switchTab("b")
        assertEquals(SessionActivity.WAITING_FOR_INPUT, o.sessionActivities.value[id])
    }

    @Test fun anApprovalNoHookReportedIsReset() {
        val (o, id) = approvalSetup(null)
        o.switchTab("b")
        assertEquals(SessionActivity.WAITING_FOR_INPUT, o.sessionActivities.value[id])
    }

    @Test fun aHookApprovalOfAServerWithHooksOffIsReset() {
        CommFlags.set(CommFlags.Raw(hooks = false))
        val (o, id) = approvalSetup(System.currentTimeMillis() - 1_000)
        o.switchTab("b")
        assertEquals(SessionActivity.WAITING_FOR_INPUT, o.sessionActivities.value[id])
    }

    @Test fun theApprovalPolicyJudgesTheAbsoluteAgeAndTheFlag() {
        val now = 1_000_000_000L
        assertTrue(HookApprovalPolicy.survivesSwitch(now - 599_999, now, hooksOn = true))
        assertFalse(HookApprovalPolicy.survivesSwitch(now - 600_000, now, hooksOn = true), "10 min is already too old")
        assertTrue(HookApprovalPolicy.survivesSwitch(now + 20_000, now, hooksOn = true), "a server clock a few seconds ahead")
        assertFalse(HookApprovalPolicy.survivesSwitch(now + 3_600_000, now, hooksOn = true), "a clock hours off proves nothing")
        assertFalse(HookApprovalPolicy.survivesSwitch(now - 1_000, now, hooksOn = false))
        assertFalse(HookApprovalPolicy.survivesSwitch(null, now, hooksOn = true))
    }

    // ---- the Attach line -----------------------------------------------------------------------

    @Test fun theAttachTraceCountsEachPhaseFromThePreviousMarkAndClosesOnTheFirstOutputAfterTheAttach() {
        var t = 1_000L
        val trace = AttachTrace { t }
        assertNull(trace.firstByte("shell prompt"), "output before the attach command closes nothing")
        t += 300; trace.phase("shell")
        t += 100; trace.phase("prompt")
        t += 40; trace.phase("decide")
        assertNull(trace.firstByte("more output"), "still before the attach command was sent")
        t += 5; trace.attachSent("tmux attach")
        t += 500
        val done = trace.firstByte("\u001B[?1049h")!!
        assertEquals(mapOf("shell" to 300L, "prompt" to 100L, "decide" to 40L, "attach" to 5L, "firstByte" to 500L), done.phasesMs)
        assertEquals(945L, done.totalMs)
        assertNull(trace.firstByte("\u001B[?1049h"), "the line is written once")
        assertEquals(
            "Attach s1 kind=cold ms=945 phase=shell=300,prompt=100,decide=40,attach=5,firstByte=500",
            CommLogFormat.attach("s1", cold = true, done.totalMs, done.phasesMs),
        )
    }
}
