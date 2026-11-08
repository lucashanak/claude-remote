package com.clauderemote.session

import com.clauderemote.connection.SshManager
import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionStatus
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecResult
import com.clauderemote.session.service.Pace
import com.clauderemote.session.service.RemoteExec
import com.clauderemote.session.service.TerminalIOService
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.FakeSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * C4 (zamrzlý tab): a reconnect must never end ACTIVE over a connection that died during it. Measured on the phone
 * (2026-11-07 13:32): the new shell died 439 ms after its connect, its loss report was dropped by the "already
 * reconnecting" guard, the attach went into the dead shell, and the tab ended ACTIVE + "Reconnected!" with nothing
 * behind it — no sweep, button or retry reached it, only an app restart did.
 *
 * The shell is stood in for by [SessionOrchestrator.reconnectShellSeam] (a manager over a [FakeSession] whose
 * `connected` the test flips) and the attach by [SessionOrchestrator.tmuxCommandSeam]; a loss report is what the
 * shell's `onConnectionLost` does: `autoReconnect(session, emit)`.
 */
class ReconnectLivenessTest {
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val savedLog = FileLogger.platformLog
    private val logLines = CopyOnWriteArrayList<String>()
    private val tabs = TabManager()
    private val storage = ServerStorage(FakeKeyValueStore())
    private val sessions = SessionStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private var orchestrator: SessionOrchestrator? = null

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
    }

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        FileLogger.platformLog = savedLog
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.liveSessionProvider = null
        SshSessionHelper.fallbackRunner = null
        CommFlags.resetForTest()
    }

    /** Every exec answers empty: the tmux probes and pollers of an attached tab find nothing and go on. */
    private object QuietExec : RemoteExec {
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace) =
            ExecResult("", 0)
    }

    /** The shells the reconnects built, in order. */
    private val shells = CopyOnWriteArrayList<Pair<FakeSession, SshManager>>()
    /** What the plain connect paths (reconnectSession) were asked for: (session id, its effective size then, ms since start). */
    private val connects = CopyOnWriteArrayList<Triple<String, Pair<Int, Int>, Long>>()
    private val started = System.currentTimeMillis()

    private fun orchestrator(): SessionOrchestrator {
        storage.addServer(server)
        val o = SessionOrchestrator(storage, tabs, sessions).also { orchestrator = it }
        o.connectionRegistry.remoteExec = QuietExec
        o.reconnectShellSeam = {
            val fake = FakeSession()
            BudgetFixture.connectedManager(storage, fake).also { shells += fake to it }
        }
        o.connectSeam = { _, s, _, _ -> connects += Triple(s.id, terminalIO(o).effectiveSize(s.id), System.currentTimeMillis() - started); true }
        o.tmuxCommandSeam = { _, _, _ -> true }     // the attach went out
        return o
    }

    private fun tab(id: String = "t1", status: SessionStatus = SessionStatus.DISCONNECTED) = ClaudeSession(
        id = id, server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-box-$id", connectionType = ConnectionType.SSH, status = status,
        claudeSessionId = "0f6c2a4e-1b2c-4d5e-8f90-123456789abc",
    ).also { tabs.addTab(it); tabs.updateTabStatus(it.id, status) }

    private fun terminalIO(o: SessionOrchestrator): TerminalIOService = field(o, "terminalIO")

    private fun <T> field(o: Any, name: String): T {
        val f = o.javaClass.getDeclaredField(name).also { it.isAccessible = true }
        @Suppress("UNCHECKED_CAST") return f.get(o) as T
    }

    private fun eventually(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(20) }
        assertTrue(cond(), "timed out waiting for $what; log: ${logLines.filter { "t1" in it || "Reconnect" in it }.takeLast(15)}")
    }

    private fun liveShell(o: SessionOrchestrator, id: String = "t1") = o.connectionRegistry.ssh(id)?.isConnected == true

    // ---- body 1: the check before ACTIVE ---------------------------------------------------------------------------

    @Test fun aShellThatDiesDuringTheReconnectIsNotLeftActiveTheNextAttemptReplacesIt() {
        val o = orchestrator()
        val t = tab()
        // the shell dies while the attach is sent (13:32:26: 439 ms after its connect); in production its loss report
        // is the skipped one, here there is none at all: only the check can see it
        o.tmuxCommandSeam = { _, _, _ -> if (shells.size == 1) shells[0].first.connected = false; true }
        runBlocking { withTimeout(30_000) { o.autoReconnect(t, {}) } }
        assertEquals(2, shells.size, "a second attempt built a new shell; log: ${logLines.filter { "Auto-reconnect" in it }}")
        assertTrue(logLines.any { "Auto-reconnect attempt 2/3" in it })
        assertSame(shells[1].second, o.connectionRegistry.ssh("t1"), "the tab's shell is the new one")
        assertTrue(liveShell(o), "and it is alive")
        assertEquals(SessionStatus.ACTIVE, tabs.getTab("t1")!!.status)
    }

    @Test fun aShellThatKeepsDyingEndsInTheBackgroundRetryAfterThreeAttemptsNotInALoop() {
        val o = orchestrator()
        val t = tab()
        // every shell dies during its attach AND reports its loss while the reconnect holds the tab
        o.tmuxCommandSeam = { s, _, _ ->
            shells.last().first.connected = false
            runBlocking { o.autoReconnect(s, {}) }
            true
        }
        val printed = CopyOnWriteArrayList<String>()
        runBlocking { withTimeout(30_000) { o.autoReconnect(t, { text -> printed += text }) } }
        assertTrue(printed.any { "Reconnect failed after 3 attempts" in it }, "the three attempts end: $printed")
        Thread.sleep(1_500)    // the background retry's first round is 2 s away: anything before it is a loop
        assertEquals(3, shells.size, "exactly the three attempts built shells; log: ${logLines.filter { "t1" in it }.takeLast(10)}")
        assertEquals(SessionStatus.DISCONNECTED, tabs.getTab("t1")!!.status)
        assertEquals(-1, o.reconnectStatus.value["t1"]?.maxAttempts, "the unbounded background retry has the tab")
    }

    // ---- body 2: a loss reported during the reconnect is not lost ---------------------------------------------------

    @Test fun aLossReportedAfterTheCheckRunsTheReconnectAgain() {
        val o = orchestrator()
        val t = tab()
        val reported = AtomicInteger()
        // the shell dies after the check, while "Reconnected!" is printed, and reports it: the reconnect still holds the tab
        lateinit var emit: (String) -> Unit
        emit = { text ->
            if ("Reconnected!" in text && reported.getAndIncrement() == 0) {
                shells[0].first.connected = false
                runBlocking { o.autoReconnect(t, emit) }
            }
        }
        runBlocking { withTimeout(30_000) { o.autoReconnect(t, emit) } }
        eventually("a second reconnect builds a live shell") { shells.size == 2 && liveShell(o) }
        eventually("and ends ACTIVE") { tabs.getTab("t1")!!.status == SessionStatus.ACTIVE && logLines.count { "Auto-reconnect succeeded for t1" in it } == 2 }
        assertTrue(logLines.any { "lost during its reconnect — reconnecting again" in it })
    }

    @Test fun aLossReportedDuringAReconnectSessionRunsTheReconnectAgain() {
        val o = orchestrator()
        tab()
        // reconnectSession's connect (seam) leaves a shell that dies before it ends, and the loss is reported meanwhile
        o.connectSeam = { _, s, _, _ ->
            val fake = FakeSession()
            o.connectionRegistry.putSsh(s.id, BudgetFixture.connectedManager(storage, fake))
            fake.connected = false
            runBlocking { o.autoReconnect(s, {}) }
            true
        }
        runBlocking { withTimeout(30_000) { o.reconnectSession("t1") } }
        eventually("the reconnect runs again over a live shell") { shells.size == 1 && liveShell(o) }
        eventually("ACTIVE") { tabs.getTab("t1")!!.status == SessionStatus.ACTIVE && logLines.any { "Auto-reconnect succeeded for t1" in it } }
    }

    @Test fun aLossReportedDuringASuccessfulReconnectOverALiveShellStartsNothing() {
        val o = orchestrator()
        val t = tab()
        val reported = AtomicInteger()
        lateinit var emit: (String) -> Unit
        // a stale report (an earlier shell's) arrives during the reconnect; the shell it ends with is alive
        emit = { text -> if ("Reconnected!" in text && reported.getAndIncrement() == 0) runBlocking { o.autoReconnect(t, emit) } }
        runBlocking { withTimeout(30_000) { o.autoReconnect(t, emit) } }
        Thread.sleep(500)
        assertEquals(1, shells.size, "no second reconnect over a live shell")
        assertTrue(logLines.none { "reconnecting again" in it })
    }

    // ---- body 3: the safety net -------------------------------------------------------------------------------------

    @Test fun theSweepTakesAnActiveTabWithoutALiveConnectionAndLeavesALiveOne() {
        val o = orchestrator()
        terminalIO(o).resize("view", 96, 50)          // the view size is known: no cold-start wait
        tab("t1", SessionStatus.ACTIVE)
        o.connectionRegistry.putSsh("t1", BudgetFixture.connectedManager(storage, FakeSession(connected = false)))
        tab("t2", SessionStatus.ACTIVE)
        o.connectionRegistry.putSsh("t2", BudgetFixture.connectedManager(storage, FakeSession()))
        o.reconnectDeadTabs("test")
        eventually("the dead ACTIVE tab is reconnected") { connects.any { it.first == "t1" } }
        assertTrue(logLines.any { "Reconnect sweep (test): 1 dead tab(s)" in it }, "only t1 is dead: ${logLines.filter { "sweep" in it }}")
        Thread.sleep(300)
        assertTrue(connects.none { it.first == "t2" }, "the live tab is left alone")
    }

    @Test fun aKickOverADeadConnectionReconnectsTheTabAndOverALiveOneDoesNot() {
        val o = orchestrator()
        tab("t1", SessionStatus.ACTIVE)
        o.connectionRegistry.putSsh("t1", BudgetFixture.connectedManager(storage, FakeSession()))
        o.kickRedraw("t1", 96, 50)
        Thread.sleep(500)
        assertTrue(connects.isEmpty(), "a kick over a live shell only redraws")
        (o.connectionRegistry.ssh("t1")!!.getSession() as FakeSession).connected = false
        o.kickRedraw("t1", 96, 50)
        eventually("the frozen tab reconnects when it is shown") { connects.any { it.first == "t1" } }
        assertTrue(logLines.any { "kickRedraw: t1 is ACTIVE without a live connection" in it })
    }

    @Test fun aKickOverADisconnectedTabLeavesItToItsOwnReconnect() {
        val o = orchestrator()
        tab("t1", SessionStatus.DISCONNECTED)
        o.kickRedraw("t1", 96, 50)
        Thread.sleep(500)
        assertTrue(connects.isEmpty(), "a DISCONNECTED tab has its reconnect already (autoReconnect or the retry)")
    }

    // ---- body 4: firstByte ------------------------------------------------------------------------------------------

    @Test fun theAppsOwnLinesDoNotCloseTheAttachMeasurement() {
        val o = orchestrator()
        val t = tab()
        val traces: java.util.concurrent.ConcurrentHashMap<String, AttachTrace> = field(o, "attachTraces")
        val closedBy = CopyOnWriteArrayList<String>()
        // what connectSsh's emit does with the trace
        val emit: (String) -> Unit = { text -> traces["t1"]?.firstByte(text)?.let { traces.remove("t1"); closedBy += text } }
        o.tmuxCommandSeam = { _, _, _ -> traces["t1"]!!.attachSent("tmux attach-session -t claude-box-t1"); true }
        runBlocking { withTimeout(30_000) { o.autoReconnect(t, emit) } }
        assertEquals(emptyList(), closedBy.toList(), "\"Reconnected!\" is the app's, not tmux's first output")
        emit("\u001B[?1049h tmux")
        assertEquals(listOf("\u001B[?1049h tmux"), closedBy.toList(), "tmux's first output closes it")
    }

    @Test fun anOwnLineIsPassedOverOnceAndTmuxOutputStillCloses() {
        val trace = AttachTrace()
        trace.attachSent("tmux attach")
        trace.ownOutput("\r\nReconnected!\r\n")
        assertNull(trace.firstByte("\r\nReconnected!\r\n"))
        assertNotNull(trace.firstByte("\r\nReconnected!\r\n"), "only the announced line, once: the same text from tmux counts")
    }

    // ---- body 5: the size at a cold start ---------------------------------------------------------------------------

    @Test fun theColdStartSweepWaitsForTheFirstViewSize() {
        val o = orchestrator()
        tab("t1", SessionStatus.DISCONNECTED)
        o.reconnectDeadTabs("onResume")
        Thread.sleep(400)
        assertTrue(connects.isEmpty(), "no connect before the screen's size is known")
        terminalIO(o).resize("view", 96, 50)
        eventually("the tab connects") { connects.isNotEmpty() }
        assertEquals(96 to 50, connects[0].second, "at the screen's size, not 80x24")
    }

    @Test fun theColdStartWaitEndsAtItsLimitOnceAndNotAgain() {
        val o = orchestrator()
        tab("t1", SessionStatus.DISCONNECTED)
        val t0 = System.currentTimeMillis() - started
        o.reconnectDeadTabs("onResume")
        eventually("the tab connects at the limit") { connects.isNotEmpty() }
        val waited = connects[0].third - t0
        assertTrue(waited in 1_800..4_000, "waited $waited ms for a size that never came")
        assertEquals(80 to 24, connects[0].second, "then the default, as before")
        eventually("the first sweep is over") { field<kotlinx.coroutines.Job?>(o, "reconnectSweepJob")?.isActive != true }
        tabs.updateTabStatus("t1", SessionStatus.DISCONNECTED)
        val t1 = System.currentTimeMillis() - started
        o.reconnectDeadTabs("onAvailable")
        eventually("the next sweep") { connects.size == 2 }
        assertTrue(connects[1].third - t1 < 1_000, "the limit is spent once: ${connects[1].third - t1} ms")
    }

    @Test fun theRestoreWaveWaitsForTheFirstViewSize() {
        // exec off: the wave's wait for the exec transport (up to 3 s) would hide a wave that does not wait for the size
        CommFlags.set(CommFlags.Raw(exec = false))
        val o = orchestrator()
        sessions.save(listOf(SessionStorage.fromClaudeSession(ClaudeSession(
            id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = "claude-box-t1", connectionType = ConnectionType.SSH,
        ))))
        o.restoreAndReconnect()
        Thread.sleep(400)
        assertTrue(connects.isEmpty(), "the wave waits for the screen's size")
        terminalIO(o).resize("view", 96, 50)
        eventually("the restored tab connects") { connects.isNotEmpty() }
        assertEquals(96 to 50, connects[0].second)
    }

    // ---- kolo 3 ----------------------------------------------------------------------------------------------------

    /** Kolo 3 (review LOW): the rerun after a loss during the reconnect starts from DISCONNECTED, like any loss — not ACTIVE. */
    @Test fun theRerunAfterALossDuringTheReconnectStartsFromDisconnected() {
        val o = orchestrator()
        val t = tab()
        val reported = AtomicInteger()
        val statusAtRerun = CopyOnWriteArrayList<SessionStatus>()
        val activityAtRerun = CopyOnWriteArrayList<com.clauderemote.model.SessionActivity?>()
        val statusService: com.clauderemote.session.service.StatusService = field(o, "statusService")
        lateinit var emit: (String) -> Unit
        emit = { text ->
            if ("Reconnecting (1/3)" in text && reported.get() > 0) {
                statusAtRerun += tabs.getTab("t1")!!.status
                activityAtRerun += statusService.currentActivity("t1")
            }
            if ("Reconnected!" in text && reported.getAndIncrement() == 0) {
                shells[0].first.connected = false
                runBlocking { o.autoReconnect(t, emit) }
            }
        }
        runBlocking { withTimeout(30_000) { o.autoReconnect(t, emit) } }
        eventually("the rerun") { statusAtRerun.isNotEmpty() }
        assertEquals(SessionStatus.DISCONNECTED, statusAtRerun.first(), "not ACTIVE over the dead shell while it runs again")
        // kolo 4 (D2): the activity too — the watch reads a reconnect only through DISCONNECTED
        assertEquals(com.clauderemote.model.SessionActivity.DISCONNECTED, activityAtRerun.first(), "the activity says offline too")
        eventually("and it ends ACTIVE over a live shell") { tabs.getTab("t1")!!.status == SessionStatus.ACTIVE && liveShell(o) && shells.size == 2 }
    }

    /** Kolo 3 (review LOW): the sweep's list is up to 2 s old (the size wait): a tab that healed meanwhile keeps its shell. */
    @Test fun theSweepLeavesATabThatHealedWhileItWaitedForTheScreenSize() {
        val o = orchestrator()
        tab("t1", SessionStatus.ACTIVE)
        val fake = FakeSession(connected = false)
        o.connectionRegistry.putSsh("t1", BudgetFixture.connectedManager(storage, fake))
        o.reconnectDeadTabs("onResume")                      // cold start: waits for the size
        Thread.sleep(300)
        fake.connected = true                                // healed meanwhile (another reconnect of the tab)
        terminalIO(o).resize("view", 96, 50)
        eventually("the sweep is over") { field<kotlinx.coroutines.Job?>(o, "reconnectSweepJob")?.isActive != true }
        assertTrue(connects.none { it.first == "t1" }, "a live tab is not reconnected: $connects")
    }

    /** Kolo 3 (adv N5): an ET tab whose client died is not live because its carrier shell is — the kick reconnects it. */
    @Test fun aKickOverAnEtTabWhoseClientDiedReconnectsItDespiteALiveCarrier() {
        val o = orchestrator()
        tab("t1", SessionStatus.ACTIVE)
        o.connectionRegistry.putSsh("t1", BudgetFixture.connectedManager(storage, FakeSession()))   // the carrier
        o.connectionRegistry.putEt("t1", com.clauderemote.connection.EtManager())                  // its ET client: not running
        o.kickRedraw("t1", 96, 50)
        eventually("the tab reconnects") { connects.any { it.first == "t1" } }
    }

    // ---- kolo 5: no input queue ---------------------------------------------------------------------------------

    /** A route the test switches on (as a reconnect would), and what went through it. */
    private class Wire {
        val up = java.util.concurrent.atomic.AtomicBoolean(false)
        val delivered = CopyOnWriteArrayList<String>()
    }

    private fun wire(o: SessionOrchestrator): Wire {
        terminalIO(o).initBuffer("t1")
        val w = Wire()
        val control: com.clauderemote.session.service.ClaudeControlService = field(o, "claudeControl")
        control.routeOverride = {
            if (w.up.get()) com.clauderemote.session.service.ClaudeControlService.InputRoute({ w.delivered += it }, { w.delivered += String(it) }) else null
        }
        o.connectSeam = { _, _, _, _ -> w.up.set(true); true }
        return w
    }

    private fun buffer(o: SessionOrchestrator, id: String = "t1") = terminalIO(o).getBuffer(id)

    private fun typeEverythingWithoutARoute(o: SessionOrchestrator) {
        o.sendInput("t1", "1")                                  // the raw keyboard
        o.sendBytes("t1", "\r".toByteArray())
        o.sendClaudeCommand("t1", "y\r")                         // the input bar / the palette
        assertEquals(3, Regex("Not connected — not sent").findAll(buffer(o)).count(), "each send says it was not sent: ${buffer(o)}")
    }

    /** Kolo 5 (decision of the lead): input without a route is never sent later — not after a reconnectSession. */
    @Test fun inputWithoutARouteIsNeverSentAfterAReconnectSession() {
        val o = orchestrator()
        tab("t1", SessionStatus.ACTIVE)
        val w = wire(o)
        typeEverythingWithoutARoute(o)
        runBlocking { withTimeout(30_000) { o.reconnectSession("t1") } }
        assertEquals(SessionStatus.ACTIVE, tabs.getTab("t1")!!.status)
        Thread.sleep(700)
        assertEquals(emptyList(), w.delivered.toList(), "nothing goes out after the reconnect")
    }

    /** Kolo 5: nor after an autoReconnect. */
    @Test fun inputWithoutARouteIsNeverSentAfterAnAutoReconnect() {
        val o = orchestrator()
        val t = tab("t1", SessionStatus.ACTIVE)
        val w = wire(o)
        typeEverythingWithoutARoute(o)
        w.up.set(true)
        tabs.updateTabStatus("t1", SessionStatus.DISCONNECTED)
        runBlocking { withTimeout(30_000) { o.autoReconnect(t, {}) } }
        assertEquals(SessionStatus.ACTIVE, tabs.getTab("t1")!!.status)
        Thread.sleep(700)
        assertEquals(emptyList(), w.delivered.toList(), "nothing goes out after the reconnect")
    }

    /** Kolo 5 (adv N3, probe X2): a tab that is not ACTIVE (reconnecting, before its attach) takes no input at all. */
    @Test fun aTabThatIsNotActiveTakesNoInputEvenOverALiveShell() {
        val o = orchestrator()
        tab("t1", SessionStatus.DISCONNECTED)
        val w = wire(o)
        w.up.set(true)                                          // the new shell is up, the attach not sent yet
        o.sendClaudeCommand("t1", "rm -rf build\r")
        o.sendInput("t1", "x")
        assertEquals(emptyList(), w.delivered.toList(), "nothing into the bare login shell")
        assertTrue("Not connected — not sent" in buffer(o), buffer(o))
        tabs.updateTabStatus("t1", SessionStatus.ACTIVE)
        o.sendInput("t1", "x")
        assertEquals(listOf("x"), w.delivered.toList(), "an ACTIVE tab does take it")
    }

    /**
     * Kolo 5 (adv N4, probe X4): a carrier rebuild that hands the tab to a full autoReconnect stops the old ET client
     * (here a running stand-in process) and takes it out: the rebuilt SSH terminal gets the input, no zombie `et`.
     */
    @Test fun aHandOffToAutoReconnectStopsTheOldEtClient() {
        val o = orchestrator()
        val t = tab("t1", SessionStatus.ACTIVE)
        val et = com.clauderemote.connection.EtManager()
        val proc = ProcessBuilder("sleep", "60").start()
        com.clauderemote.connection.EtManager::class.java.getDeclaredField("process").also { it.isAccessible = true }.set(et, proc)
        o.connectionRegistry.putEt("t1", et)
        assertTrue(et.isConnected, "precondition: the old ET client runs")
        try {
            runBlocking { withTimeout(30_000) { o.reconnectEtCarrier(t, {}) } }   // no local port: hands off at once
            eventually("the full reconnect ends ACTIVE over a new shell") { tabs.getTab("t1")!!.status == SessionStatus.ACTIVE && liveShell(o) }
            assertEquals(null, o.connectionRegistry.et("t1"), "the old ET client is out of the registry")
            eventually("and stopped") { !proc.isAlive }
        } finally {
            proc.destroyForcibly()
        }
    }

    // ---- kolo 6 -----------------------------------------------------------------------------------------------------

    /**
     * Kolo 6 (verifier probe P-DA, regression of kolo 5): tmux asks the terminal DA1/DA2 at every attach; the emulator's
     * answers come one round trip later — inside autoReconnect's settle, before ACTIVE. They reach tmux, and no
     * "not sent" is printed.
     */
    @Test fun theEmulatorsAnswersAfterTheAttachOfAnAutoReconnectReachTmux() {
        val o = orchestrator()
        val t = tab("t1", SessionStatus.DISCONNECTED)
        terminalIO(o).initBuffer("t1")
        val delivered = CopyOnWriteArrayList<String>()
        val control: com.clauderemote.session.service.ClaudeControlService = field(o, "claudeControl")
        control.routeOverride = { com.clauderemote.session.service.ClaudeControlService.InputRoute({ delivered += it }, { delivered += String(it) }) }
        o.tmuxCommandSeam = { _, _, _ ->
            Thread {
                Thread.sleep(30)
                o.sendTerminalReply("t1", "\u001b[?64;1;2;6;9;15;18;21;22c".toByteArray())
                o.sendTerminalReply("t1", "\u001b[>41;320;0c".toByteArray())
            }.start()
            true
        }
        runBlocking { withTimeout(30_000) { o.autoReconnect(t, {}) } }
        Thread.sleep(300)
        assertEquals(2, delivered.size, "the emulator's answers reach tmux")
        assertEquals(0, Regex("Not connected — not sent").findAll(terminalIO(o).getBuffer("t1")).count(), "no 'not sent' in a tab that reconnects fine")
    }

    /** Kolo 6 (W06): CONNECTING — the user's input does not go, the emulator's answers do. */
    @Test fun aConnectingTabTakesTheEmulatorsAnswersButNotTheUsersInput() {
        val o = orchestrator()
        tab("t1", SessionStatus.CONNECTING)
        val w = wire(o)
        w.up.set(true)
        o.sendInput("t1", "x")
        o.sendClaudeCommand("t1", "y\r")
        o.sendBytes("t1", "z".toByteArray())
        assertEquals(emptyList(), w.delivered.toList(), "no user input before ACTIVE")
        o.sendTerminalReply("t1", "\u001b[>41;320;0c".toByteArray())
        assertEquals(listOf("\u001b[>41;320;0c"), w.delivered.toList(), "the emulator's answer goes")
        assertEquals(3, Regex("Not connected — not sent").findAll(buffer(o)).count(), "only the user's sends say so")
    }

    /** Kolo 6 (adv M3, H2): reconnectSession first stops and removes the old ET and mosh clients (stand-in processes). */
    @Test fun aReconnectSessionStopsTheOldEtAndMoshClients() {
        val o = orchestrator()
        tab("t1", SessionStatus.ACTIVE)
        val et = com.clauderemote.connection.EtManager()
        val mosh = com.clauderemote.connection.MoshManager()
        val etProc = ProcessBuilder("sleep", "60").start()
        val moshProc = ProcessBuilder("sleep", "60").start()
        try {
            com.clauderemote.connection.EtManager::class.java.getDeclaredField("process").also { it.isAccessible = true }.set(et, etProc)
            com.clauderemote.connection.MoshManager::class.java.getDeclaredField("process").also { it.isAccessible = true }.set(mosh, moshProc)
            o.connectionRegistry.putEt("t1", et)
            o.connectionRegistry.putMosh("t1", mosh)
            runBlocking { withTimeout(30_000) { o.reconnectSession("t1") } }
            assertEquals(null, o.connectionRegistry.et("t1"), "the old ET client is out")
            assertEquals(null, o.connectionRegistry.mosh("t1"), "the old mosh client is out")
            eventually("both stopped") { !etProc.isAlive && !moshProc.isAlive }
        } finally {
            etProc.destroyForcibly(); moshProc.destroyForcibly()
        }
    }

    /** Kolo 6 (adv M3): so does an autoReconnect (it builds a plain SSH terminal). */
    @Test fun anAutoReconnectStopsTheOldEtClient() {
        val o = orchestrator()
        val t = tab("t1", SessionStatus.DISCONNECTED)
        val et = com.clauderemote.connection.EtManager()
        val etProc = ProcessBuilder("sleep", "60").start()
        try {
            com.clauderemote.connection.EtManager::class.java.getDeclaredField("process").also { it.isAccessible = true }.set(et, etProc)
            o.connectionRegistry.putEt("t1", et)
            runBlocking { withTimeout(30_000) { o.autoReconnect(t, {}) } }
            assertEquals(null, o.connectionRegistry.et("t1"), "the old ET client is out")
            eventually("and stopped") { !etProc.isAlive }
        } finally {
            etProc.destroyForcibly()
        }
    }

    /** Kolo 6 (adv M2): a message whose text part is not sent never sends its Enter alone (the tab came back meanwhile). */
    @Test fun aMessageWhoseTextWasNotSentNeverSendsItsEnter() {
        val o = orchestrator()
        tab("t1", SessionStatus.DISCONNECTED)
        val w = wire(o)
        w.up.set(true)
        o.sendMessage("t1", "fix it")
        val end = System.currentTimeMillis() + 5_000
        while ("Not connected" !in buffer(o) && System.currentTimeMillis() < end) Thread.sleep(1)
        tabs.updateTabStatus("t1", SessionStatus.ACTIVE)          // the text was refused; the tab is back before the Enter's 40 ms
        Thread.sleep(400)
        assertEquals(emptyList(), w.delivered.toList(), "no Enter alone")
        assertEquals(1, Regex("Not connected — not sent").findAll(buffer(o)).count())
        o.sendMessage("t1", "fix it")
        eventually("an ACTIVE tab takes the whole message") { w.delivered.toList() == listOf("fix it", "\r") }
    }

    /** Kolo 6 (F5): a slash command without a route: one "not sent" for the whole command, no key of it goes. */
    @Test fun aSlashCommandWithoutARouteSaysSoOnceAndSendsNothing() {
        val o = orchestrator()
        tab("t1", SessionStatus.DISCONNECTED)
        val w = wire(o)
        w.up.set(true)
        o.sendMessage("t1", "/compact")
        Thread.sleep(600)
        assertEquals(emptyList(), w.delivered.toList())
        assertEquals(1, Regex("Not connected — not sent").findAll(buffer(o)).count(), "one line, not one per key: ${buffer(o)}")
    }

    /** Kolo 6 (adv M2): the input bar's message is bound to the tab read once at the send (App wiring). */
    @Test fun theInputBarsMessageIsBoundToTheTabOfTheSend() {
        val app = java.io.File("src/commonMain/kotlin/com/clauderemote/ui/App.kt").readText()
        assertTrue("onSendMessage = { text -> activeTabId?.let { sessionOrchestrator.sendMessage(it, text) } }," in app, "read once, passed as an id")
        val screen = java.io.File("src/commonMain/kotlin/com/clauderemote/ui/TerminalScreen.kt").readText()
        assertTrue(Regex("""onSendCommand\((ch\.toString\(\)|text)\)""").find(screen) == null, "no part-by-part send that re-reads the tab")
        assertEquals(2, Regex("""onSendMessage\(text\)""").findAll(screen).count(), "both input surfaces send one message")
    }

    // ---- kolo 7 -----------------------------------------------------------------------------------------------------

    private fun managerWritingTo(out: java.io.OutputStream): SshManager =
        BudgetFixture.connectedManager(storage, FakeSession()).also { m ->
            SshManager::class.java.getDeclaredField("outputStream").also { it.isAccessible = true }.set(m, out)
        }

    /** Kolo 7 (V07): the emulator's answer never goes into an ET tab's carrier shell; into a plain SSH terminal it does. */
    @Test fun theEmulatorsAnswerNeverGoesIntoAnEtCarrier() {
        val o = orchestrator()
        tab("t1", SessionStatus.CONNECTING)
        val out = java.io.ByteArrayOutputStream()
        val m = managerWritingTo(out)
        o.connectionRegistry.putSsh("t1", m, etCarrier = true)
        o.sendTerminalReply("t1", "\u001b[>41;320;0c".toByteArray())
        Thread.sleep(300)
        assertEquals(0, out.size(), "nothing into the hidden carrier shell")
        o.connectionRegistry.putSsh("t1", m)                    // control: the same shell as the tab's terminal
        o.sendTerminalReply("t1", "\u001b[>41;320;0c".toByteArray())
        eventually("into the terminal it goes") { out.size() > 0 }
    }

    /** Kolo 7 (V08): the emulator's answer is no typing — the activity stays; a key does set WORKING (control). */
    @Test fun theEmulatorsAnswerIsNotCountedAsTyping() {
        val o = orchestrator()
        tab("t1", SessionStatus.ACTIVE)
        val w = wire(o)
        w.up.set(true)
        val status: com.clauderemote.session.service.StatusService = field(o, "statusService")
        status.updateActivity("t1", com.clauderemote.model.SessionActivity.WAITING_FOR_INPUT)
        o.sendTerminalReply("t1", "\u001b[?64;1;2;6;9;15;18;21;22c".toByteArray())
        assertEquals(1, w.delivered.size)
        assertEquals(com.clauderemote.model.SessionActivity.WAITING_FOR_INPUT, status.currentActivity("t1"), "no WORKING from an answer")
        o.sendInput("t1", "x")
        assertEquals(com.clauderemote.model.SessionActivity.WORKING, status.currentActivity("t1"), "control: a key is typing")
    }

    /** Kolo 7 (V20): an answer without a transport says nothing (there is nobody to answer). */
    @Test fun anAnswerWithoutATransportPrintsNothing() {
        val o = orchestrator()
        tab("t1", SessionStatus.ACTIVE)
        val w = wire(o)
        o.sendTerminalReply("t1", "\u001b[>41;320;0c".toByteArray())
        assertEquals(emptyList(), w.delivered.toList())
        assertTrue("Not connected" !in buffer(o), buffer(o))
    }

    /** Kolo 7 (V15, behaviour): the Enter of a message goes to the tab of the send, also when another tab is active. */
    @Test fun theEnterOfAMessageGoesToTheTabOfTheSend() {
        val o = orchestrator()
        tab("t1", SessionStatus.ACTIVE)
        tab("t2", SessionStatus.ACTIVE)
        terminalIO(o).initBuffer("t1")
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        val control: com.clauderemote.session.service.ClaudeControlService = field(o, "claudeControl")
        control.routeOverride = { id -> com.clauderemote.session.service.ClaudeControlService.InputRoute({ sent += id to it }, {}) }
        tabs.switchTab("t2")                                     // the user is on another tab meanwhile
        o.sendMessage("t1", "fix it")
        eventually("the message went") { sent.size == 2 }
        assertEquals(listOf("t1" to "fix it", "t1" to "\r"), sent.toList(), "text and Enter to the tab of the send")
    }

    /** Kolo 7 (V17): the login code that was not sent is never followed by its Enter (the tab came back meanwhile). */
    @Test fun theLoginCodesEnterNeverGoesAloneAfterAnUnsentCode() {
        val o = orchestrator()
        tab("t1", SessionStatus.DISCONNECTED)
        val w = wire(o)
        w.up.set(true)
        o.submitLoginCode("t1", "abc123")
        val end = System.currentTimeMillis() + 5_000
        while ("Not connected" !in buffer(o) && System.currentTimeMillis() < end) Thread.sleep(1)
        tabs.updateTabStatus("t1", SessionStatus.ACTIVE)          // back before the Enter's 120 ms
        Thread.sleep(500)
        assertEquals(emptyList(), w.delivered.toList(), "no Enter alone")
    }

    /** Kolo 7 (adv N6, V14): a mosh client that ended leaves the registry; one already replaced writes nothing. */
    @Test fun anEndedMoshClientLeavesTheRegistryAndAReplacedOneWritesNothing() {
        val o = orchestrator()
        tab("t1", SessionStatus.ACTIVE)
        val old = com.clauderemote.connection.MoshManager()
        val current = com.clauderemote.connection.MoshManager()
        o.connectionRegistry.putMosh("t1", current)
        o.onMoshClientEnded("t1", old)                           // stopped by a reconnect, ends late
        assertSame(current, o.connectionRegistry.mosh("t1"), "the replaced one leaves the current client alone")
        assertEquals(SessionStatus.ACTIVE, tabs.getTab("t1")!!.status, "and does not write the tab's state")
        o.onMoshClientEnded("t1", current)
        assertEquals(null, o.connectionRegistry.mosh("t1"), "the tab's own client leaves the registry")
        assertEquals(SessionStatus.DISCONNECTED, tabs.getTab("t1")!!.status)
    }

    /** Kolo 7 (adv N3): the desktop terminal never turns the wheel into arrow keys (they would pass as the emulator's). */
    @Test fun theDesktopWheelIsNeverSentAsArrowKeys() {
        val main = java.io.File("../desktopApp/src/main/kotlin/com/clauderemote/Main.kt").readText()
        assertTrue("override fun simulateMouseScrollWithArrowKeysInAlternativeScreen(): Boolean = false" in main)
    }

    /** Kolo 5: no input queue is left in the app's code (production sources; tests are not scanned). */
    @Test fun noInputQueueIsLeftInTheAppsCode() {
        val banned = Regex("""queueInput|QueuedInput|flushPendingInputs|pendingInputs|PendingInput|pendingCounts|clearPendingInputs|will send on reconnect|sent on reconnect""")
        val hits = listOf("shared/src", "androidApp/src", "wearApp/src", "desktopApp/src").map { java.io.File("../$it") }.filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" && "Test" !in it.path.substringAfter("/src/").substringBefore("/") } }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (banned.containsMatchIn(l)) "${f.path}:${i + 1}: ${l.trim()}" else null } }
        assertEquals(emptyList(), hits, "input is never kept for a later send")
    }

    /** Kolo 5 (verifier probe P-L2): the legacy bookkeeping of a launch whose shell is being rebuilt waits for it. */
    @Test fun theLegacyBookkeepingOfALaunchWaitsForItsRebuiltShell() {
        val o = orchestrator()
        o.connectSeam = { _, s, _, _ -> tabs.updateTabStatus(s.id, SessionStatus.DISCONNECTED); true }
        o.connectionRegistry.remoteExec = null                  // the legacy path (no exec transport)
        assertTrue(!o.connectionRegistry.execActive(server.id), "the legacy path runs")
        runBlocking {
            withTimeout(30_000) {
                o.launchSession(server, "/work", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, ConnectionType.SSH, "claude-box-l2", isNewTmuxSession = false)
            }
        }
        val id = tabs.tabs.value.single { it.tmuxSessionName == "claude-box-l2" }.id
        Thread.sleep(1_500)
        assertTrue(logLines.none { "skipped: no live shell" in it }, "not given up at once: ${logLines.takeLast(10)}")
        val opened = CopyOnWriteArrayList<String>()
        o.connectionRegistry.putSsh(id, BudgetFixture.connectedManager(storage, FakeSession(channels = { opened += it; error("refused") })))
        eventually("the bookkeeping runs on the rebuilt shell", 5_000) { opened.isNotEmpty() }
    }

    /** Kolo 4 (adv N2, probe P2b): the ET client was taken out (its onDisconnect); the carrier is no terminal. */
    @Test fun anEtTabWhoseClientIsGoneIsNotLiveAndItsInputNeverGoesToTheCarrier() {
        val o = orchestrator()
        tab("t1", SessionStatus.ACTIVE)
        o.connectionRegistry.putSsh("t1", BudgetFixture.connectedManager(storage, FakeSession()), etCarrier = true)
        val control: com.clauderemote.session.service.ClaudeControlService = field(o, "claudeControl")
        assertTrue(!control.canDeliverNow("t1"), "no route into the carrier's hidden shell")
        o.kickRedraw("t1", 96, 50)
        eventually("the tab reconnects") { connects.any { it.first == "t1" } }
    }

    /** Kolo 4 (S2): the sweep's check skips only an ACTIVE tab that is live — a DISCONNECTED one is reconnected. */
    @Test fun theSweepStillReconnectsADisconnectedTabWhoseShellLooksLive() {
        val o = orchestrator()
        terminalIO(o).resize("view", 96, 50)
        tab("t1", SessionStatus.DISCONNECTED)
        o.connectionRegistry.putSsh("t1", BudgetFixture.connectedManager(storage, FakeSession()))
        o.reconnectDeadTabs("test")
        eventually("the DISCONNECTED tab is reconnected") { connects.any { it.first == "t1" } }
    }

    /** Kolo 4 (adv N4, probe P3): a rotation during the 200 ms settle after the attach wins over the size read before it. */
    @Test fun aRotationDuringTheSettleAfterAnAutoReconnectWins() {
        val o = orchestrator()
        val t = tab()
        terminalIO(o).resize("t1", 80, 40)
        val ch = com.jcraft.jsch.RecordingShellChannel()
        o.reconnectShellSeam = {
            com.clauderemote.connection.SshManager(storage).also { m ->
                com.clauderemote.connection.SshManager::class.java.getDeclaredField("session").also { it.isAccessible = true }.set(m, FakeSession())
                com.clauderemote.connection.SshManager::class.java.getDeclaredField("channel").also { it.isAccessible = true }.set(m, ch)
            }
        }
        o.tmuxCommandSeam = { _, _, _ ->
            Thread { Thread.sleep(80); terminalIO(o).resize("t1", 160, 30) }.start()   // the phone turns
            true
        }
        runBlocking { withTimeout(30_000) { o.autoReconnect(t, {}) } }
        Thread.sleep(400)
        assertEquals(160 to 30, ch.ptys.last().let { it.cols to it.rows }, "the last size sent is the new one: ${ch.ptys}")
    }

    /** Kolo 4 (adv N9): a launch whose shell was lost (its autoReconnect gave up: DISCONNECTED) does not end ACTIVE. */
    @Test fun aLaunchDoesNotSetActiveOverAShellItsReconnectGaveUpOn() {
        val o = orchestrator()
        o.connectSeam = { _, s, _, _ -> tabs.updateTabStatus(s.id, SessionStatus.DISCONNECTED); true }
        runBlocking {
            withTimeout(30_000) {
                o.launchSession(server, "/work", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, ConnectionType.SSH, "claude-box-l1", isNewTmuxSession = false)
            }
        }
        val tab = tabs.tabs.value.single { it.tmuxSessionName == "claude-box-l1" }
        assertEquals(SessionStatus.DISCONNECTED, tab.status, "the background retry has it, not ACTIVE over nothing")
        assertEquals(listOf("claude-box-l1"), sessions.load().map { it.tmuxSessionName }, "and it is persisted")
        assertTrue(logLines.any { "lost its shell during the launch" in it })
    }

    @Test fun aKickRemembersTheSizeForTheTabsNextReconnect() {
        val o = orchestrator()
        tab("t1", SessionStatus.ACTIVE)
        o.connectionRegistry.putSsh("t1", BudgetFixture.connectedManager(storage, FakeSession()))
        o.kickRedraw("t1", 96, 50)
        assertEquals(96 to 50, terminalIO(o).effectiveSize("t1"))
    }
}
