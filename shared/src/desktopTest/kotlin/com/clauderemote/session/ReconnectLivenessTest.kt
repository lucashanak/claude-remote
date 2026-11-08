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

    @Test fun aKickRemembersTheSizeForTheTabsNextReconnect() {
        val o = orchestrator()
        tab("t1", SessionStatus.ACTIVE)
        o.connectionRegistry.putSsh("t1", BudgetFixture.connectedManager(storage, FakeSession()))
        o.kickRedraw("t1", 96, 50)
        assertEquals(96 to 50, terminalIO(o).effectiveSize("t1"))
    }
}
