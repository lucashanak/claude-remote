package com.clauderemote.integration

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionStatus
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.session.service.CommFlags
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C4 against a real sshd: a shell that dies during `reconnectSession`'s connect (here the server's startup command
 * ends it right after the prompt; on the phone a route that died 439 ms after its connect) must not leave the tab
 * ACTIVE. Its loss report is skipped while the reconnect holds the tab, so only the check at the end of `connectSsh`
 * can see it: the reconnect ends in its error path (ERROR + the background retry).
 */
class ReconnectLivenessIntegrationTest {
    private var fixture: SshdFixture? = null
    private val storage = ServerStorage(InMemoryKeyValueStore())
    private val tabs = TabManager()
    private lateinit var orchestrator: SessionOrchestrator
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedPlatformLog = FileLogger.platformLog
    private val savedExecChannels = SshSessionHelper.execChannelsProvider
    private val savedLiveSession = SshSessionHelper.liveSessionProvider

    @get:org.junit.Rule(order = 0) val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(180)

    @get:org.junit.Rule(order = 1) val dumpOnFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable?, description: org.junit.runner.Description?) {
            println("---- log lines of ${description?.methodName} ----\n" + logLines.takeLast(200).joinToString("\n"))
        }
    }

    @Before fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, t -> logLines += "$tag: $message" + (t?.let { " — ${it.message}" } ?: "") }
    }

    @After fun tearDown() {
        if (::orchestrator.isInitialized) {
            orchestrator.shutdownForTest()
            orchestrator.connectionRegistry.teardownAllTransports()
            runCatching { orchestrator.execManager.close(fixture!!.server.id) }
        }
        FileLogger.platformLog = savedPlatformLog
        SshSessionHelper.execChannelsProvider = savedExecChannels
        SshSessionHelper.liveSessionProvider = savedLiveSession
        CommFlags.resetForTest()
        fixture?.close()
    }

    @Test fun aShellThatDiesDuringReconnectSessionDoesNotEndActive() {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        val f = SshdFixture.start().also { fixture = it }
        val server = f.server.copy(startupCommand = "exit")
        storage.addServer(server)
        orchestrator = SessionOrchestrator(storage, tabs)
        val tab = ClaudeSession(
            id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = "claude-server-t1", connectionType = ConnectionType.SSH,
        )
        tabs.addTab(tab)
        runBlocking { withTimeout(120_000) { orchestrator.reconnectSession("t1") } }
        assertTrue(logLines.any { "Connection lost" in it }, "the shell did die: ${logLines.takeLast(30)}")
        assertEquals(SessionStatus.ERROR, tabs.getTab("t1")!!.status, "not ACTIVE over a dead shell")
        assertTrue(logLines.any { "Reconnect failed" in it && "died during the connect" in it }, "the connect said why: ${logLines.takeLast(10)}")
        assertTrue(orchestrator.connectionRegistry.ssh("t1")?.isConnected != true)
    }
}
