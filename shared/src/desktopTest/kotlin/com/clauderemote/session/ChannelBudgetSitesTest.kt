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
import com.clauderemote.session.service.Pace
import com.clauderemote.session.service.RemoteExec
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.FakeShellChannel
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every place that opens a channel on a TAB transport outside the exec transport must count that channel against the
 * transport (sshd's `MaxSessions`) and skip when there is no room. The situation is the kill switch (`exec` off) with
 * the shells still packed on one transport. A SHORT channel (a poll, a one-off command) may take one slot of the
 * reserve (V9): 8 shells leave room for exactly one, and none once another short channel holds it. A LONG channel (the
 * notify watcher) keeps off the reserve: 7 shells leave room for exactly one, 8 for none.
 *
 * "Counted" is read at the moment the channel is opened: the transport must then have NO headroom of the site's kind
 * left (the channel itself took the last slot). An uncounted channel still sees the slot free.
 */
class ChannelBudgetSitesTest {
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedLog = FileLogger.platformLog
    private val tabs = TabManager()
    private val storage = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private val tmux = "claude-server-a"
    private var orchestrator: SessionOrchestrator? = null

    /** Per opened channel: whether the transport still had headroom at that moment (false = the channel was counted). */
    private val headroomAtOpen = CopyOnWriteArrayList<Boolean>()
    /** The kind of the site under test: its headroom is the one probed. */
    private var short = true
    private lateinit var fake: FakeSession
    private lateinit var manager: SshManager

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
        storage.addServer(server)
    }

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        FileLogger.platformLog = savedLog
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.liveSessionProvider = null
        SshSessionHelper.fallbackRunner = null
        CommFlags.resetForTest()
    }

    private fun tab() = ClaudeSession(
        id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = tmux, connectionType = ConnectionType.SSH,
    )

    /**
     * Kill switch on, one tab whose transport carries [shells] shells, [held] short channels already taken by others;
     * every channel opened there is recorded and fails. [long]: the site opens a LONG channel.
     */
    private fun setup(shells: Int, sessionStorage: SessionStorage? = null, held: Int = 0, long: Boolean = false): SessionOrchestrator {
        short = !long
        CommFlags.set(CommFlags.Raw(exec = false))
        val o = SessionOrchestrator(storage, tabs, sessionStorage).also { orchestrator = it }
        SshSessionHelper.execChannelsProvider = null
        val pool = o.connectionRegistry.transportPool(server.id)
        fake = FakeSession { _ -> headroomAtOpen += pool.fallbackHeadroom(fake, short = short); throw JSchException("channel is not opened.") }
        BudgetFixture.injectTransport(pool, fake, shells)
        repeat(held) { assertTrue(runBlocking { pool.reserveFallbackChannel(fake, short = true) }, "fixture: a held short channel") }
        tabs.addTab(tab())
        manager = BudgetFixture.connectedManager(storage, fake, pool)
        o.connectionRegistry.putSsh("t1", manager)
        // the other shells of the transport are other tabs' managers on the same session (what the registry counts, R3-1)
        repeat(shells - 1) { o.connectionRegistry.putSsh("peer-$it", BudgetFixture.connectedManager(storage, fake, pool)) }
        return o
    }

    private fun <T> field(o: SessionOrchestrator, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return SessionOrchestrator::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(o) as T
    }

    private fun waitFor(ms: Long, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(20)
    }

    private fun assertNoChannel(what: String) =
        assertEquals(emptyList(), headroomAtOpen.toList(), "$what: without room no channel may be opened on the tab transport")

    private fun assertCounted(what: String) {
        assertTrue(headroomAtOpen.isNotEmpty(), "$what: control — with room the site does open a channel")
        assertTrue(headroomAtOpen.none { it }, "$what: each channel must be counted while it is open (headroom at open: $headroomAtOpen)")
        val pool = orchestrator!!.connectionRegistry.transportPool(server.id)
        waitFor(2_000) { pool.fallbackHeadroom(fake, short = short) }
        assertTrue(pool.fallbackHeadroom(fake, short = short), "$what: the counted channel was given back")
    }

    // ---- GitStatusService (legacy: the tab's own session) ----
    private fun git(o: SessionOrchestrator) = runBlocking { field<com.clauderemote.session.service.GitStatusService>(o, "gitStatusService").probeGitStatusOnce("t1") }

    @Test fun gitProbeWithoutRoomOpensNoChannel() { git(setup(8, held = 1)); assertNoChannel("git") }
    @Test fun gitProbeWithRoomCountsItsChannel() { git(setup(8)); assertCounted("git") }

    // ---- AccountService (legacy: a live tab session of the server) ----
    private fun accounts(o: SessionOrchestrator) = runBlocking {
        withTimeoutOrNull(5_000) { runCatching { field<com.clauderemote.session.service.AccountService>(o, "accountService").listAccounts(server.id) } }
    }

    @Test fun accountsWithRoomCountTheirChannel() { accounts(setup(8)); assertCounted("accounts") }

    // ---- RemoteOpsService: tmux scroll and file upload (legacy: the tab's own session) ----
    private fun ops(o: SessionOrchestrator) = field<com.clauderemote.session.service.RemoteOpsService>(o, "remoteOps")

    @Test fun scrollWithoutRoomOpensNoChannel() {
        ops(setup(8, held = 1)).tmuxScroll("t1", true); Thread.sleep(600); assertNoChannel("scroll")
    }

    @Test fun scrollWithRoomCountsItsChannel() {
        ops(setup(8)).tmuxScroll("t1", true); waitFor(2_000) { headroomAtOpen.isNotEmpty() }; assertCounted("scroll")
    }

    private fun upload(o: SessionOrchestrator) = runBlocking { withTimeoutOrNull(2_500) { runCatching { ops(o).uploadFile("t1", ByteArray(4), "f.txt") } } }

    // V12 (round 3): a transfer keeps off the reserve like a LONG channel, so 8 shells leave it no room, 7 one slot
    @Test fun uploadOnEightShellsTakesNoReserveSlot() { upload(setup(8)); assertNoChannel("upload") }
    @Test fun uploadWithRoomCountsItsChannel() { upload(setup(7, long = true)); assertCounted("upload") }

    // ---- RemoteOpsService: SFTP download and stat on the tab's own session (R3, review round 1) ----
    private fun download(o: SessionOrchestrator) = runBlocking { withTimeoutOrNull(3_000) { ops(o).downloadFile("t1", "/tmp/x.png") } }
    private fun stat(o: SessionOrchestrator) = runBlocking { withTimeoutOrNull(3_000) { ops(o).statFiles("t1", listOf("/tmp/x.png")) } }

    @Test fun sftpDownloadOnEightShellsTakesNoReserveSlot() { download(setup(8)); assertNoChannel("sftp download") }
    @Test fun sftpDownloadWithRoomCountsItsChannel() { download(setup(7, long = true)); assertCounted("sftp download") }
    @Test fun sftpStatWithoutRoomOpensNoChannel() { stat(setup(8, held = 1)); assertNoChannel("sftp stat") }
    @Test fun sftpStatWithRoomCountsItsChannel() { stat(setup(8)); assertCounted("sftp stat") }

    @Test fun anUploadWithoutRoomFailsAtOnceAndSaysSo() {
        val o = setup(8)
        val t0 = System.currentTimeMillis()
        val e = runBlocking { withTimeoutOrNull(5_000) { runCatching { ops(o).uploadFile("t1", ByteArray(4), "f.txt") }.exceptionOrNull() } }
        val ms = System.currentTimeMillis() - t0
        assertTrue(e?.message?.contains("No free SSH channel") == true, "the error names the missing room, at once (${ms} ms), not a broken SSH: $e")
        // R3-1 (round 4): the kill switch with 8 shells is the case where no room can come, so at once; and it is no timeout
        assertTrue(ms < 1_500, "the kill switch with 8 shells: no room can come, the upload fails at once, not after waiting (${ms} ms)")
        assertTrue(e?.message?.contains("timeout") == false, "an error that came at once does not say timeout: ${e?.message}")
        assertNoChannel("upload")
    }

    // ---- R3-1 (round 4): a transfer without room waits for it (holding nothing) when room can come ----

    /** Runs [op] in the background, frees the held short channel after [afterMs] (the poll in flight ends), returns the op. */
    private fun <T> releaseHeldAfter(afterMs: Long, op: suspend () -> T): kotlinx.coroutines.Deferred<T> {
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async { op() }
        Thread.sleep(afterMs)
        assertTrue(headroomAtOpen.isEmpty(), "control: while the held channel is in flight there is no room for the transfer")
        runBlocking { orchestrator!!.connectionRegistry.transportPool(server.id).releaseChannel(fake) }
        return job
    }

    @Test fun anUploadOnSevenShellsWaitsForThePollInFlightAndThenGetsItsChannel() {
        val o = setup(7, held = 1, long = true)
        val job = releaseHeldAfter(800) { withTimeoutOrNull(4_000) { runCatching { ops(o).uploadFile("t1", ByteArray(4), "f.txt") } } }
        waitFor(3_000) { headroomAtOpen.isNotEmpty() }
        job.cancel()
        assertCounted("upload after the poll in flight ended")
    }

    @Test fun aDownloadOnSevenShellsWaitsForThePollInFlightAndThenGetsItsChannel() {
        val o = setup(7, held = 1, long = true)
        val job = releaseHeldAfter(800) { withTimeoutOrNull(4_000) { ops(o).downloadFile("t1", "/tmp/x.png") } }
        waitFor(3_000) { headroomAtOpen.isNotEmpty() }
        job.cancel()
        assertCounted("download after the poll in flight ended")
    }

    @Test fun aDownloadOnEightShellsWithTheKillSwitchReturnsAtOnce() {
        val o = setup(8)
        val t0 = System.currentTimeMillis()
        assertEquals(null, download(o))
        assertTrue(System.currentTimeMillis() - t0 < 1_500, "no room can come: no wait")
        assertNoChannel("download")
    }

    // ---- V12 + T1 (round 3): exec routing on, the exec transport not up, 8 shells: the fallbacks of execRun/useChannel ----
    private object NotUpChannels : com.clauderemote.session.service.ExecChannels {
        override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T =
            throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
        override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Session) -> T): T =
            throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
    }

    /** [setup] with exec routing on and the exec transport down: every exec and SFTP channel falls back to the tab transport. */
    private fun setupRouted(shells: Int, long: Boolean = false, held: Int = 0): SessionOrchestrator {
        val o = setup(shells, long = long, held = held)
        CommFlags.resetForTest()
        o.connectionRegistry.remoteExec = NotUpExec()
        o.connectionRegistry.execChannels = NotUpChannels
        assertTrue(o.connectionRegistry.execActive(server.id), "fixture: exec routing on")
        return o
    }

    /** The exec transport of the server closed on purpose: it does not come back by itself (R3-1: no room can come). */
    private fun closeExec(o: SessionOrchestrator) {
        o.execManager.state(server.id)
        o.execManager.close(server.id)
    }

    @Test fun aRoutedUploadWithTheExecTransportClosedAndEightShellsTakesNoReserveSlotAndFailsAtOnce() {
        val o = setupRouted(8)
        closeExec(o)
        val t0 = System.currentTimeMillis()
        val e = runBlocking { withTimeoutOrNull(5_000) { runCatching { ops(o).uploadFile("t1", ByteArray(4), "f.txt") }.exceptionOrNull() } }
        val ms = System.currentTimeMillis() - t0
        assertTrue(e?.message?.contains("No free SSH channel") == true, "the error names the missing room, at once: $e")
        assertTrue(ms < 1_500 && e?.message?.contains("timeout") == false, "at once (${ms} ms) and no 'timeout': ${e?.message}")
        assertNoChannel("routed upload")
    }

    /** An exec transport that answers every command (exit 0): the transport came back. */
    private class OkExec : RemoteExec {
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace) = ExecResult("", 0)
    }

    @Test fun aRoutedUploadWaitsForAnExecTransportThatIsBrieflyDownAndThenSucceeds() {
        // adv11: routing on, 8 shells (no room on the tab transport), the exec transport down (not closed) for a moment
        val o = setupRouted(8)
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
            withTimeoutOrNull(6_000) { runCatching { ops(o).uploadFile("t1", ByteArray(4), "f.txt") } }
        }
        Thread.sleep(1_200)
        assertTrue(job.isActive, "the upload still waits while the exec transport is down")
        o.connectionRegistry.remoteExec = OkExec()
        val r = runBlocking { job.await() }
        assertEquals("/tmp/claude-uploads/f.txt", r?.getOrNull(), "uploaded once the exec transport was back: $r")
        assertNoChannel("routed upload")
    }

    @Test fun aRoutedDownloadOnSevenShellsWaitsForRoomOnTheTabTransport() {
        val o = setupRouted(7, long = true, held = 1)
        val job = releaseHeldAfter(800) { withTimeoutOrNull(4_000) { ops(o).downloadFile("t1", "/tmp/x.png") } }
        waitFor(3_000) { headroomAtOpen.isNotEmpty() }
        job.cancel()
        assertCounted("routed download after the poll in flight ended")
    }

    @Test fun aRoutedDownloadWithTheExecTransportClosedAndEightShellsReturnsAtOnce() {
        val o = setupRouted(8)
        closeExec(o)
        val t0 = System.currentTimeMillis()
        assertEquals(null, download(o))
        assertTrue(System.currentTimeMillis() - t0 < 1_500, "no room can come: no wait")
        assertNoChannel("routed download")
    }

    @Test fun aRoutedUploadWithRoomCountsItsChannel() { upload(setupRouted(7, long = true)); assertCounted("routed upload") }

    @Test fun aRoutedSftpDownloadWithTheExecTransportDownTakesNoReserveSlot() {
        val o = setupRouted(8)
        assertEquals(null, download(o), "no download now")
        assertNoChannel("routed sftp download")
    }

    @Test fun aRoutedSftpStatWithTheExecTransportDownStillTakesTheReserveSlot() {
        stat(setupRouted(8)); assertCounted("routed sftp stat")
    }

    // ---- K4-3 (round 5): a LONG channel held on the tab transport (the notify watcher's tail) never ends between polls ----

    @Test fun anUploadOnSevenShellsAndAWatcherTailFailsAtOnceNoRoomCanCome() {
        val o = setup(7, long = true)
        // the notify watcher's LONG tail on the tab transport: 7 + 1 + reserve = 10, it fits and holds its slot for good
        val tailOpen = CompletableDeferred<Unit>()
        val tail = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
            o.connectionRegistry.onServerTabChannel(server.id, ExecKind.LONG) { tailOpen.complete(Unit); kotlinx.coroutines.awaitCancellation() }
        }
        try {
            runBlocking { withTimeout(3_000) { tailOpen.await() } }
            assertTrue(!o.connectionRegistry.transferMayFindRoom(server.id), "7 shells and a held LONG channel: a transfer never fits (7+1+1+2 > 10)")
            val t0 = System.currentTimeMillis()
            val e = runBlocking { withTimeoutOrNull(5_000) { runCatching { ops(o).uploadFile("t1", ByteArray(4), "f.txt") }.exceptionOrNull() } }
            val ms = System.currentTimeMillis() - t0
            assertTrue(e?.message?.contains("No free SSH channel") == true && ms < 1_500, "no room can come: the upload fails at once (${ms} ms): $e")
            assertNoChannel("upload beside a watcher tail")
        } finally {
            tail.cancel()
        }
        waitFor(2_000) { o.connectionRegistry.transferMayFindRoom(server.id) }
        assertTrue(o.connectionRegistry.transferMayFindRoom(server.id), "control: with the tail gone 7 shells may find room again")
    }

    // ---- TmuxProbes: the redraw kick's refresh-client (legacy: the tab's own session) ----
    private fun kick(o: SessionOrchestrator) {
        field<com.clauderemote.session.service.TmuxProbes>(o, "tmuxProbes").kickRedraw("t1", 80, 24)
        Thread.sleep(800)
    }

    @Test fun redrawKickWithoutRoomOpensNoChannel() { kick(setup(8, held = 1)); assertNoChannel("refresh-client") }
    @Test fun redrawKickWithRoomCountsItsChannel() { kick(setup(8)); assertCounted("refresh-client") }

    // ---- NotificationService: the Stop hook setup and the LONG watcher channel ----
    @Test fun stopHookWithoutRoomOpensNoChannel() {
        val o = setup(8, held = 1); runBlocking { o.notificationService.ensureStopHook(manager) }; assertNoChannel("ensureStopHook")
    }

    @Test fun stopHookWithRoomCountsItsChannel() {
        val o = setup(8); runBlocking { o.notificationService.ensureStopHook(manager) }; assertCounted("ensureStopHook")
    }

    @Test fun notifyWatcherWithoutRoomOpensNoChannel() {
        // LONG: 8 shells leave none, the slot a short poll may borrow from the reserve is not for it (V9)
        val o = setup(8, long = true)
        o.notificationService.startNotifyWatcher("t1", tmux, server.id)
        Thread.sleep(1_000)
        o.notificationService.unregisterNotifyWatcher(server.id, "t1")
        assertNoChannel("notify watcher")
    }

    @Test fun notifyWatcherWithRoomCountsItsLongChannel() {
        val o = setup(7, long = true)
        o.notificationService.startNotifyWatcher("t1", tmux, server.id)
        waitFor(3_000) { headroomAtOpen.isNotEmpty() }
        o.notificationService.unregisterNotifyWatcher(server.id, "t1")
        assertCounted("notify watcher")
    }

    // ---- SessionPersistenceService.startSessionIdRefresh: the per-tab loop of the kill switch ----
    @Test fun idRefreshWithoutRoomOpensNoChannel() {
        val o = setup(8, SessionStorage(FakeKeyValueStore()), held = 1)
        o.persistence.startSessionIdRefresh("t1", tmux, manager)
        Thread.sleep(1_800)
        o.persistence.stopIdRefresh("t1")
        assertNoChannel("session-id refresh")
    }

    @Test fun idRefreshWithRoomCountsItsChannels() {
        val o = setup(8, SessionStorage(FakeKeyValueStore()))
        o.persistence.startSessionIdRefresh("t1", tmux, manager)
        waitFor(4_000) { headroomAtOpen.size >= 2 }
        o.persistence.stopIdRefresh("t1")
        assertCounted("session-id refresh")
    }

    // ---- TranscriptService: the one-shot pid probe of a tab's uuid when its transcript opens (legacy: the tab's own session) ----
    // (Found by the integration probe n1: 8 tabs opening their transcripts at once = 8 uncounted probes on a full transport.)
    @Test fun uuidKickProbeWithoutRoomOpensNoChannel() {
        val o = setup(8, held = 1)
        o.transcriptFlow("t1")
        Thread.sleep(1_000)
        assertNoChannel("uuid kick-probe")
    }

    @Test fun uuidKickProbeWithRoomCountsItsChannel() {
        val o = setup(8)
        o.transcriptFlow("t1")
        waitFor(3_000) { headroomAtOpen.isNotEmpty() }
        Thread.sleep(300)
        assertCounted("uuid kick-probe")
    }

    // ---- K07: a redraw kick with the exec transport not up and no tab with room opens no channel of its own ----
    private class NotUpExec : RemoteExec {
        val runs = AtomicInteger()
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult {
            runs.incrementAndGet()
            throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
        }
    }

    @Test fun aRedrawKickWithTheExecTransportDownAndNoRoomFallsBackToNothingOfItsOwn() {
        // exec routing on (default flags); the exec transport is not up; no tab transport in the pool
        val o = SessionOrchestrator(storage, tabs).also { orchestrator = it }
        val exec = NotUpExec().also { o.connectionRegistry.remoteExec = it }
        tabs.addTab(tab())
        // the tab's manager sits on a jsch session that never connected: a channel opened there fails with "session is down"
        val m = SshManager(storage)
        SshManager::class.java.getDeclaredField("session").also { it.isAccessible = true }.set(m, JSch().getSession("u", "127.0.0.1", 1))
        SshManager::class.java.getDeclaredField("channel").also { it.isAccessible = true }.set(m, FakeShellChannel())
        o.connectionRegistry.putSsh("t1", m)
        field<com.clauderemote.session.service.TmuxProbes>(o, "tmuxProbes").kickRedraw("t1", 80, 24)
        waitFor(3_000) { exec.runs.get() > 0 }
        Thread.sleep(300)
        assertTrue(exec.runs.get() > 0, "the kick asked the exec layer (execRead)")
        assertTrue(logLines.none { it.contains("refresh-client failed") }, "no channel on the tab's own session: $logLines")
    }
}
