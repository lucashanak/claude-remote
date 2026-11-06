package com.clauderemote.integration

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.session.service.CommFlags
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * V16 (dávka B, round 6) end to end: a real launch through the orchestrator against a real sshd and a private tmux
 * server. A new session is created on the server (detached, carrying the launch's nonce) before its tab exists, then
 * the tab attaches it. A name another session holds is left exactly as it was (same pane, same creation time, not in
 * the manifest, no tombstone) and the tab gets the next free name. The creation costs no handshake of its own: on a
 * cold server it rides the pooled transport the tab's shell then leases, later ones the exec transport.
 */
class FreshLaunchIntegrationTest {
    private var fixture: SshdFixture? = null
    private val storage = ServerStorage(InMemoryKeyValueStore())
    private val sessions = SessionStorage(InMemoryKeyValueStore())
    private val tabs = TabManager()
    private lateinit var orchestrator: SessionOrchestrator
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedPlatformLog = FileLogger.platformLog

    @get:org.junit.Rule(order = 0) val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(240)

    @get:org.junit.Rule(order = 1) val dumpOnFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable?, description: org.junit.runner.Description?) {
            println("---- log lines of ${description?.methodName} ----\n" + logLines.takeLast(200).joinToString("\n"))
        }
    }

    @Before fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
    }

    @After fun tearDown() {
        if (::orchestrator.isInitialized) {
            orchestrator.shutdownForTest()
            orchestrator.connectionRegistry.teardownAllTransports()
            runCatching { orchestrator.execManager.close(fixture!!.server.id) }
        }
        FileLogger.platformLog = savedPlatformLog
        CommFlags.resetForTest()
        fixture?.close()
    }

    private fun boot(): SshdFixture {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        val f = SshdFixture.start().also { fixture = it }
        storage.addServer(f.server)
        orchestrator = SessionOrchestrator(storage, tabs, sessions)
        f.startTmuxServer()
        return f
    }

    private fun manifest(f: SshdFixture): String = File(f.home, ".claude-remote/sessions.json").let { if (it.exists()) it.readText() else "" }

    private fun launch(f: SshdFixture, name: String): ClaudeSession = runBlocking {
        withTimeout(90_000) {
            orchestrator.launchSession(f.server, f.home.absolutePath, ClaudeMode.NORMAL, ClaudeModel.DEFAULT, ConnectionType.SSH, name, isNewTmuxSession = true)
        }
    }

    private fun panePid(f: SshdFixture, name: String): String = f.tmux("list-panes", "-t", "=$name", "-F", "#{pane_pid}").out.trim()

    private fun owner(f: SshdFixture, name: String): String = f.tmux("show-environment", "-t", "=$name", "CR_LAUNCH").out.trim()

    @Test fun aFreshLaunchOfANameAnotherSessionHoldsLeavesItAloneAndTakesTheNextFreeName() {
        val f = boot()
        val name = "claude-server-taken"
        f.tmux("new-session", "-d", "-s", name, "sleep", "86400")
        waitUntil(10_000, "the other session $name") { name in f.tmuxSessions() }
        val created = assertNotNull(f.sessionCreatedAt(name))
        val pid = panePid(f, name)

        val s = launch(f, name)
        assertEquals("$name-2", s.tmuxSessionName, "the tab is the session the launch created, under the next free name")
        waitUntil(60_000, "$name-2 in the server's manifest") { "$name-2" in manifest(f) }
        assertTrue(owner(f, "$name-2").startsWith("CR_LAUNCH="), "the created session carries the launch's nonce: ${owner(f, "$name-2")}")
        assertEquals(listOf("$name-2"), tabs.tabs.value.map { it.tmuxSessionName }, "one tab, on the created session")
        assertTrue(sessions.load().map { it.tmuxSessionName } == listOf("$name-2"), "only the created session is persisted: ${sessions.load()}")
        Thread.sleep(2_000)   // anything that would still go to the server has had its time

        assertEquals(created, f.sessionCreatedAt(name), "the other session is left alone: not killed, not created again")
        assertEquals(pid, panePid(f, name), "same pane, same process")
        assertTrue(!owner(f, name).startsWith("CR_LAUNCH="), "and nobody's launch claimed it: ${owner(f, name)}")
        assertTrue(Regex("\"tmuxSessionName\"\\s*:\\s*\"$name\"").find(manifest(f)) == null, "the other session's name is not in the manifest: ${manifest(f)}")
        assertTrue(File(f.home, ".claude-remote/forgotten").let { !it.exists() || name !in it.readLines().map { l -> l.trim() } }, "no tombstone for that name")
    }

    @Test fun theCreationPaysNoHandshakeOfItsOwnColdOrWarm() {
        val f = boot()
        // cold: no transport of the server yet — the creation leases the pooled transport the tab's shell then uses
        val first = launch(f, "claude-server-first")
        waitUntil(60_000, "first in the manifest") { "claude-server-first" in manifest(f) }
        val coldHandshakes = f.acceptedHandshakes()
        assertTrue(coldHandshakes <= 2, "cold launch: the tab's shell transport and the exec transport, nothing more ($coldHandshakes)")
        assertTrue(logLines.any { "Created tmux session 'claude-server-first'" in it })
        // warm: a second launch on a taken name creates over a channel that exists (exec transport or a counted one)
        f.tmux("new-session", "-d", "-s", "claude-server-taken", "sleep", "86400")
        waitUntil(10_000, "the other session") { "claude-server-taken" in f.tmuxSessions() }
        val second = launch(f, "claude-server-taken")
        assertEquals("claude-server-taken-2", second.tmuxSessionName)
        waitUntil(60_000, "second in the manifest") { "claude-server-taken-2" in manifest(f) }
        // the second tab's own shell may need a transport of its own only when the first is full (cap 3 / 8): not here
        assertEquals(coldHandshakes, f.acceptedHandshakes(), "the warm launch (creation + shell) opened no new connection")
        assertEquals(setOf(first.tmuxSessionName, second.tmuxSessionName), tabs.tabs.value.map { it.tmuxSessionName }.toSet())
    }
}
