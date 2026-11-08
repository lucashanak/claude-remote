package com.clauderemote.session

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
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `opened.d` adoption and "Skrýt z tohoto zařízení" through the real orchestrator and reconciler (§15.4): the
 * reconcile exec is a fake that answers canned `reconcileAll` output and records every command, so the test sees
 * exactly what reaches the server. No network.
 */
class OpenedAdoptionWiringTest {
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedLog = FileLogger.platformLog
    private val savedExecChannels = SshSessionHelper.execChannelsProvider
    private val savedLiveSession = SshSessionHelper.liveSessionProvider
    private val tabs = TabManager()
    private val servers = ServerStorage(FakeKeyValueStore())
    private val sessions = SessionStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private val sid = "11111111-1111-1111-1111-111111111111"
    private val nowS get() = System.currentTimeMillis() / 1000
    private var orchestrator: SessionOrchestrator? = null

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
        servers.addServer(server)
    }

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        FileLogger.platformLog = savedLog
        SshSessionHelper.execChannelsProvider = savedExecChannels
        SshSessionHelper.liveSessionProvider = savedLiveSession
        CommFlags.resetForTest()
    }

    private class FakeExec : RemoteExec {
        val commands = CopyOnWriteArrayList<String>()
        @Volatile var reconcileOut: String = ""
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult {
            commands += cmd
            return ExecResult(if ("__END_snap__" in cmd) reconcileOut else "", 0)
        }
    }

    private class MemStore : HiddenAdoptionsStore {
        @Volatile var value = ""
        override fun load() = value
        override fun save(value: String) { this.value = value }
    }

    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    /** `reconcileAll` output: the manifest holds [manifestNames] (YOLO, OPUS, account acc), tmux runs [live]. */
    private fun reconcile(live: List<String>, manifestNames: List<String>, markers: List<Pair<String, Long>>, tomb: List<String> = emptyList()): String {
        val manifest = manifestNames.joinToString(",", "[", "]") {
            """{"id":"remote-$it","serverId":"srv-1","folder":"/home/u/w","mode":"YOLO","model":"OPUS","tmuxSessionName":"$it","claudeSessionId":"$sid","accountSlug":"acc"}"""
        }
        return buildString {
            append((tomb + "---" + manifest).joinToString("\n")).append("\n__END_snap__ rc=0\n")
            append(live.joinToString("\n")).append("\n__END_tmux__ rc=0\n")
            append("\n__END_panes__ rc=0\n\n__END_ps__ rc=0\n\n__END_claude__ rc=0\n")
            markers.forEach { (n, m) -> append("OPENED $m ${b64(n)} {\"by\":\"jarvis\",\"folder\":\"/w\"}\n") }
            append("__END_opened__ rc=0\n")
        }
    }

    private fun setUpOrchestrator(): Triple<SessionOrchestrator, FakeExec, MemStore> {
        val o = SessionOrchestrator(servers, tabs, sessions).also { orchestrator = it }
        val exec = FakeExec()
        o.connectionRegistry.remoteExec = exec
        val store = MemStore()
        o.hiddenAdoptionsStore = store
        val own = ClaudeSession(
            id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = "claude-box-mine", connectionType = ConnectionType.SSH,
        )
        tabs.addTab(own)
        return Triple(o, exec, store)
    }

    private var lastRound = 0L

    /**
     * One reconcile round of the server (what the 15 s loop does), driven through the uuid check. That check reuses
     * a round younger than 2 s, so a following round waits that out: every call here is a NEW exec and a new answer.
     */
    private fun round(o: SessionOrchestrator, exec: FakeExec) {
        val wait = lastRound + 2_100 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        val before = exec.commands.size
        runBlocking { o.connectionRegistry.reconciler!!.checkUuid("t1") }
        lastRound = System.currentTimeMillis()
        assertTrue(exec.commands.size > before, "the round ran no exec")
    }

    private fun eventually(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(20) }
        assertTrue(cond(), "timed out waiting for $what")
    }

    @Test fun aMarkerBecomesAParkedTabFromTheManifestWithoutTakingTheScreenOrPushing() {
        val (o, exec, _) = setUpOrchestrator()
        exec.reconcileOut = reconcile(
            live = listOf("claude-box-mine", "claude-box-j", "claude-box-new"),
            manifestNames = listOf("claude-box-j"),
            // claude-box-new: drift has not put it into the manifest yet → not now
            markers = listOf("claude-box-j" to nowS - 30, "claude-box-new" to nowS - 5, "claude-box-mine" to nowS - 5),
        )
        round(o, exec)
        val adopted = tabs.tabs.value.filter { it.tmuxSessionName == "claude-box-j" }
        assertEquals(1, adopted.size, "tabs: ${tabs.tabs.value.map { it.tmuxSessionName }}")
        val t = adopted.single()
        assertEquals(SessionStatus.DISCONNECTED, t.status)
        assertEquals(ClaudeMode.YOLO, t.mode)
        assertEquals(ClaudeModel.OPUS, t.model)
        assertEquals("acc", t.accountSlug)
        assertEquals(sid, t.claudeSessionId)
        assertEquals("t1", tabs.activeTabId.value, "adoption never takes the screen")
        assertEquals("jarvis", o.parkedTabs.value[t.id])
        assertEquals(listOf("claude-box-j"), sessions.load().map { it.tmuxSessionName })
        assertTrue(tabs.tabs.value.none { it.tmuxSessionName == "claude-box-new" })
        assertEquals(1, tabs.tabs.value.count { it.tmuxSessionName == "claude-box-mine" }, "a name that is a tab is never adopted")
        assertTrue(exec.commands.all { "__END_snap__" in it }, "adoption sends nothing to the server: ${exec.commands}")

        // the next round neither duplicates it nor pushes
        round(o, exec)
        assertEquals(1, tabs.tabs.value.count { it.tmuxSessionName == "claude-box-j" })
        assertTrue(exec.commands.all { "__END_snap__" in it })

        // the reconnect sweeps (resume, network back) leave a parked tab alone: no shell until the user opens it
        logLines.clear()
        o.reconnectDeadTabs("test")
        assertTrue(logLines.none { "Reconnect sweep" in it }, "the parked tab was swept: $logLines")
    }

    /**
     * A restore of this device writes the name's marker itself and gets its tab from the restore: a reconcile round in
     * the meantime must not adopt the same name as a second, parked tab. Once the restore is over, the marker is fair game.
     */
    @Test fun aNameBeingRestoredHereIsNeverAdopted() {
        val (o, exec, _) = setUpOrchestrator()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        // the restore's exec: held open until the test lets it fail (no server here)
        SshSessionHelper.execChannelsProvider = {
            object : com.clauderemote.session.service.ExecChannels {
                override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T =
                    throw UnsupportedOperationException()
                override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Session) -> T): T {
                    entered.complete(Unit)
                    gate.await()
                    throw IllegalStateException("test: no server")
                }
            }
        }
        val rec = com.clauderemote.session.service.ClosedRecord(
            1, "2026-11-06T17:20:03Z", "claude-box-r", sid, null, null, "/home/u/w", "/home/u/w", null, null, null,
            emptyList(), null, null, null, null, "app:x", true, null,
        )
        val restore = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { o.restoreClosed(server, rec) }
        runBlocking { kotlinx.coroutines.withTimeout(5_000) { entered.await() } }

        exec.reconcileOut = reconcile(listOf("claude-box-mine", "claude-box-r"), listOf("claude-box-r"), listOf("claude-box-r" to nowS))
        round(o, exec)
        assertTrue(tabs.tabs.value.none { it.tmuxSessionName == "claude-box-r" }, "adopted while its restore ran here")

        gate.complete(Unit)
        runBlocking { kotlinx.coroutines.withTimeout(5_000) { restore.await() } }
        // the control: with the restore over, the same round adopts it
        round(o, exec)
        assertEquals(1, tabs.tabs.value.count { it.tmuxSessionName == "claude-box-r" })
    }

    @Test fun nothingIsAdoptedWhileTheHideListIsNotWired() {
        val (o, exec, _) = setUpOrchestrator()
        o.hiddenAdoptionsStore = null
        exec.reconcileOut = reconcile(listOf("claude-box-mine", "claude-box-j"), listOf("claude-box-j"), listOf("claude-box-j" to nowS))
        round(o, exec)
        assertEquals(listOf("claude-box-mine"), tabs.tabs.value.map { it.tmuxSessionName })
    }

    @Test fun hidingDropsTheTabHereOnlyAndKeepsItFromComingBackUntilANewMarker() {
        val (o, exec, store) = setUpOrchestrator()
        val marker = nowS - 30
        exec.reconcileOut = reconcile(listOf("claude-box-mine", "claude-box-j"), listOf("claude-box-j"), listOf("claude-box-j" to marker))
        round(o, exec)
        val id = assertNotNull(tabs.tabs.value.firstOrNull { it.tmuxSessionName == "claude-box-j" }).id
        val before = exec.commands.size

        o.hideFromThisDevice(id)
        eventually("the hidden tab to go") { tabs.getTab(id) == null }
        assertNull(sessions.load().firstOrNull { it.tmuxSessionName == "claude-box-j" })
        assertNull(o.parkedTabs.value[id])
        // no tombstone, no kill, no manifest push: the session keeps running for every other device
        Thread.sleep(200)
        assertEquals(before, exec.commands.size, "hide sent: ${exec.commands.drop(before)}")
        assertTrue("srv-1\tclaude-box-j\t" in store.value, store.value)

        round(o, exec)
        assertTrue(tabs.tabs.value.none { it.tmuxSessionName == "claude-box-j" }, "the hidden marker was adopted again")

        // a later open or restore of the name rewrites the marker: a new event, adopted again
        exec.reconcileOut = reconcile(listOf("claude-box-mine", "claude-box-j"), listOf("claude-box-j"), listOf("claude-box-j" to nowS + 3600))
        round(o, exec)
        assertEquals(1, tabs.tabs.value.count { it.tmuxSessionName == "claude-box-j" })
    }

    @Test fun aTombstonedOrUnknownSnapshotAdoptsNothing() {
        val (o, exec, _) = setUpOrchestrator()
        exec.reconcileOut = reconcile(listOf("claude-box-mine", "claude-box-j"), listOf("claude-box-j"), listOf("claude-box-j" to nowS), tomb = listOf("claude-box-j"))
        round(o, exec)
        // cut off inside the opened section: unknown
        exec.reconcileOut = reconcile(listOf("claude-box-mine", "claude-box-j"), listOf("claude-box-j"), listOf("claude-box-j" to nowS))
            .substringBefore("__END_opened__")
        round(o, exec)
        assertEquals(listOf("claude-box-mine"), tabs.tabs.value.map { it.tmuxSessionName })
    }
}
