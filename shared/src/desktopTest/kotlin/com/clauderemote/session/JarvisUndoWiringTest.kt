package com.clauderemote.session

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecChannels
import com.clauderemote.session.service.ExecKind
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * rev-f4-app MED-1 and MED-2 through the real orchestrator: the watch's undo shares the in-flight set the App holds,
 * and Jarvis' CLI exec (`jarvisRun`, the closed-records read under "Vrátit") holds one SHORT permit of the server's
 * exec gate for its whole run. The gate is a fake that records the permits; its session is never connected, so
 * nothing reaches a network.
 */
class JarvisUndoWiringTest {
    private val savedExecChannels = SshSessionHelper.execChannelsProvider
    private val savedFallback = SshSessionHelper.fallbackRunner
    private val servers = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private val tmux = "claude-server-kontexta-yolo--soniox"
    private var orchestrator: SessionOrchestrator? = null

    private class RecordingGate : ExecChannels {
        val kinds = CopyOnWriteArrayList<ExecKind>()
        @Volatile var inside = false
        @Volatile var blockRanInside = false
        private val sess: Session = JSch().getSession("u", "127.0.0.1", 1)
        override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T =
            throw UnsupportedOperationException()
        override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (Session) -> T): T {
            kinds += kind
            inside = true
            try { blockRanInside = true; return block(sess) } finally { inside = false }
        }
    }

    private val gate = RecordingGate()

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        servers.addServer(server)
    }

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        SshSessionHelper.execChannelsProvider = savedExecChannels
        SshSessionHelper.fallbackRunner = savedFallback
        CommFlags.resetForTest()
    }

    private fun orch(): SessionOrchestrator {
        val o = SessionOrchestrator(servers, TabManager(), SessionStorage(FakeKeyValueStore())).also { orchestrator = it }
        SshSessionHelper.execChannelsProvider = { gate }
        return o
    }

    @Test fun theWatchsUndoWaitsForAnUndoTheAppHolds() = runBlocking {
        val o = orch()
        // the App's "Vrátit" (phone notification / Velín) holds the shared set over its read and restore
        assertTrue(o.jarvisUndoInFlight.tryEnter(server.id, tmux))
        assertEquals(JarvisUndo.busyText(tmux), o.undoJarvisClose(server.id, tmux))
        assertTrue(gate.kinds.isEmpty(), "a second undo touched the server: nothing may be read or restored")
        // control: released, the same call does reach the server (and says the list could not be read)
        o.jarvisUndoInFlight.leave(server.id, tmux)
        val text = o.undoJarvisClose(server.id, tmux)
        assertTrue("nejde načíst" in text, text)
        assertEquals(listOf(ExecKind.SHORT), gate.kinds.toList())
        assertTrue(o.jarvisUndoInFlight.state.value.isEmpty(), "the watch's undo let go of its hold")
    }

    @Test fun jarvisCliExecHoldsOneShortPermitForTheWholeRun() = runBlocking {
        val o = orch()
        // the session is not connected: the exec fails inside the permit and jarvisRun answers null ("nejde načíst")
        assertNull(o.jarvisRun(server, "~/.local/bin/cr-jarvis proposals --json", totalMs = 35_000))
        assertEquals(listOf(ExecKind.SHORT), gate.kinds.toList(), "one SHORT permit of the exec gate, not a POLL, not none")
        assertTrue(gate.blockRanInside)
        assertTrue(!gate.inside, "given back afterwards")
    }
}
