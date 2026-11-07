package com.clauderemote.session

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CloseFactsCommands
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecResult
import com.clauderemote.session.service.ManifestCommands
import com.clauderemote.session.service.Pace
import com.clauderemote.session.service.RemoteExec
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The orchestrator's own [SessionOrchestrator.pendingCloses], not a harness of the class: what its wiring hands the
 * facts check and the forget (Jarvis F0a). The server exec is a fake that records every command; the close's one exec
 * waits on a gate, so the test sees whether a flush really waits for the server side.
 */
class PendingCloseWiringTest {
    private val savedExecChannels = SshSessionHelper.execChannelsProvider
    private val savedLiveSession = SshSessionHelper.liveSessionProvider
    private val tabs = TabManager()
    private val servers = ServerStorage(FakeKeyValueStore())
    private val sessions = SessionStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private var orchestrator: SessionOrchestrator? = null

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        servers.addServer(server)
    }

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        SshSessionHelper.execChannelsProvider = savedExecChannels
        SshSessionHelper.liveSessionProvider = savedLiveSession
        CommFlags.resetForTest()
    }

    private class FakeExec : RemoteExec {
        val commands = CopyOnWriteArrayList<String>()
        val closeGate = CompletableDeferred<Unit>()
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult {
            commands += cmd
            if (CloseFactsCommands.END in cmd) {
                // an idle session: nothing blocks the close
                return ExecResult("FACT alive 1\nFACT status idle -\nTOKEN 1.x\n${CloseFactsCommands.END}\n", 0)
            }
            if (ManifestCommands.CLOSE_KILL_PREFIX in cmd) {
                closeGate.await()
                return ExecResult("${ManifestCommands.CLOSE_KILL_PREFIX}0\n", 0)
            }
            return ExecResult("", 0)
        }
    }

    private class MemStore : HiddenAdoptionsStore {
        @Volatile var value = ""
        override fun load() = value
        override fun save(value: String) { this.value = value }
    }

    private fun eventually(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(20) }
        assertTrue(cond(), "timed out waiting for $what")
    }

    @Test fun aCloseCarriesThisDevicesMarkerAndByAndAFlushWaitsForTheKill() {
        val o = SessionOrchestrator(servers, tabs, sessions).also { orchestrator = it }
        val exec = FakeExec()
        o.connectionRegistry.remoteExec = exec
        val store = MemStore()
        o.hiddenAdoptionsStore = store
        val name = "claude-box-a"
        val tab = ClaudeSession(
            id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = name, connectionType = ConnectionType.SSH,
        )
        tabs.addTab(tab)
        sessions.upsert(SessionStorage.fromClaudeSession(tab))

        assertTrue(o.pendingCloses.close(tab))
        eventually("the facts check") { exec.commands.any { CloseFactsCommands.END in it } }
        // the facts leave out this device's own client: the marker the attach preamble writes, not none
        val marker = o.ownClientMarker(name)
        assertContains(exec.commands.first { CloseFactsCommands.END in it }, "SELF=${ManifestCommands.sq(marker)}")
        assertContains(o.buildAttachCommand(name), "/$marker\"")

        val flush = o.pendingCloses.flush()
        eventually("the close's exec") { exec.commands.any { ManifestCommands.CLOSE_KILL_PREFIX in it } }
        Thread.sleep(200)
        assertFalse(flush.isCompleted, "an exit waiting for the flush must wait for tombstone, record and kill")
        exec.closeGate.complete(Unit)
        runBlocking { withTimeout(5_000) { flush.join() } }

        // the record says this device closed it (what "zavřeno tady" and Ctrl+Shift+T go by)
        val close = exec.commands.first { ManifestCommands.CLOSE_KILL_PREFIX in it }
        assertTrue(o.ownCloseBy.startsWith("app:") && o.ownCloseBy.length > "app:".length, o.ownCloseBy)
        assertContains(close, "BY=${ManifestCommands.sq(o.ownCloseBy)};")
        // closed here: the name's marker is never adopted back as a parked tab
        assertTrue("srv-1\t$name\t" in store.value, store.value)
        assertEquals(emptyList(), sessions.load().map { it.id })
    }
}
