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

    private val name = "claude-box-a"

    private class Wired(val o: SessionOrchestrator, val exec: FakeExec, val store: MemStore, val tab: ClaudeSession)

    /** The real orchestrator with one tab of [name], its exec faked, the hide list wired. */
    private fun wire(): Wired {
        val o = SessionOrchestrator(servers, tabs, sessions).also { orchestrator = it }
        val exec = FakeExec()
        o.connectionRegistry.remoteExec = exec
        val store = MemStore()
        o.hiddenAdoptionsStore = store
        val tab = ClaudeSession(
            id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = name, connectionType = ConnectionType.SSH,
        )
        tabs.addTab(tab)
        sessions.upsert(SessionStorage.fromClaudeSession(tab))
        return Wired(o, exec, store, tab)
    }

    /** Close [w]'s tab and flush; the close's exec is let through at once, the flush waited for. */
    private fun closeNow(w: Wired) {
        assertTrue(w.o.pendingCloses.close(w.tab))
        w.exec.closeGate.complete(Unit)
        val flush = w.o.pendingCloses.flush()
        runBlocking { withTimeout(10_000) { flush.join() } }
    }

    /** rev-tests 30: an exit waits for the flush, so the flush must wait for the close's one exec (tombstone, record, kill). */
    @Test fun flushWaitsForTheServerSide() {
        val w = wire()
        assertTrue(w.o.pendingCloses.close(w.tab))
        val flush = w.o.pendingCloses.flush()
        eventually("the close's exec") { w.exec.commands.any { ManifestCommands.CLOSE_KILL_PREFIX in it } }
        Thread.sleep(200)
        assertFalse(flush.isCompleted, "the flush ended before the server side")
        w.exec.closeGate.complete(Unit)
        runBlocking { withTimeout(5_000) { flush.join() } }
        assertEquals(emptyList(), sessions.load().map { it.id })
    }

    /** rev-tests 30: closed here = the name's opened.d marker (as it is now) is never adopted back as a parked tab. */
    @Test fun aClosedNameIsNeverAdoptedAgain() {
        val w = wire()
        closeNow(w)
        val hidden = HiddenAdoptions.parse(w.store.value)
        val now = System.currentTimeMillis() / 1000
        assertTrue(hidden.isSuppressed(server.id, name, now - 5), "a marker from before the close: ${w.store.value}")
        assertFalse(hidden.isSuppressed(server.id, name, now + 3600), "a later open or restore is a new event")
    }

    /** rev-tests 31: the facts leave out this device's own client — the marker the attach preamble writes, not none. */
    @Test fun factsCarryTheAttachMarker() {
        val w = wire()
        assertTrue(w.o.pendingCloses.close(w.tab))
        eventually("the facts check") { w.exec.commands.any { CloseFactsCommands.END in it } }
        val marker = w.o.ownClientMarker(name)
        assertContains(w.exec.commands.first { CloseFactsCommands.END in it }, "SELF=${ManifestCommands.sq(marker)}")
        assertContains(w.o.buildAttachCommand(name), "/$marker\"")
    }

    /** rev-tests 31: the record says THIS device closed it (what "zavřeno tady" and Ctrl+Shift+T go by). */
    @Test fun recordByIsOwnCloseBy() {
        val w = wire()
        closeNow(w)
        val close = w.exec.commands.first { ManifestCommands.CLOSE_KILL_PREFIX in it }
        assertTrue(w.o.ownCloseBy.startsWith("app:") && w.o.ownCloseBy.length > "app:".length, w.o.ownCloseBy)
        assertContains(close, "BY=${ManifestCommands.sq(w.o.ownCloseBy)};")
    }
}
