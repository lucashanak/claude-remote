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

    /**
     * Kolo 3 (adv N2, probe P2): the check is only for the caller that holds the tab's reconnect lock. A launch holds
     * none, so the loss of its first shell is reported and autoReconnect heals the tab; the launch itself must still end
     * as a launch that worked — persisted (SessionStorage, tombstone, sessions.json), no "launch failed". With the check
     * in every connect the launch threw, the tab came back ACTIVE 0.6 s later, and nothing of it was persisted.
     */
    @Test fun aShellThatDiesDuringALaunchStillEndsPersistedAndIsHealed() {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        val f = SshdFixture.start().also { fixture = it }
        f.startTmuxServer()
        f.tmux("new-session", "-d", "-s", "claude-server-l1")
        // the first shell ends right after its prompt; every later one lives
        val server = f.server.copy(startupCommand = "[ -e \"\$HOME/.c4once\" ] || { touch \"\$HOME/.c4once\"; exit; }")
        storage.addServer(server)
        val persisted = com.clauderemote.storage.SessionStorage(InMemoryKeyValueStore())
        orchestrator = SessionOrchestrator(storage, tabs, persisted)
        val r = runCatching {
            runBlocking {
                withTimeout(60_000) {
                    orchestrator.launchSession(server, f.home.absolutePath, ClaudeMode.NORMAL, ClaudeModel.DEFAULT, ConnectionType.SSH,
                        "claude-server-l1", isNewTmuxSession = false)
                }
            }
        }
        assertTrue(r.isSuccess, "the launch does not fail: ${r.exceptionOrNull()}")
        assertTrue(logLines.any { "Connection lost" in it }, "the first shell did die: ${logLines.takeLast(30)}")
        assertEquals(listOf("claude-server-l1"), persisted.load().map { it.tmuxSessionName }, "the launched tab is persisted")
        val tabId = tabs.tabs.value.single().id
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end &&
            !(tabs.getTab(tabId)?.status == SessionStatus.ACTIVE && orchestrator.connectionRegistry.ssh(tabId)?.isConnected == true)) Thread.sleep(50)
        assertEquals(SessionStatus.ACTIVE, tabs.getTab(tabId)?.status, "autoReconnect healed it")
        assertTrue(orchestrator.connectionRegistry.ssh(tabId)?.isConnected == true, "over a live shell")
    }

    // ---- kolo 4 ----------------------------------------------------------------------------------------------------

    /** A Cloudflare tunnel stood in by a plain socket to [port] (the proxy's WebSocket seam). */
    private class Tunnel(port: Int, onBytes: (ByteArray) -> Unit, onEnd: () -> Unit) : com.clauderemote.connection.CloudflareProxy.TestTunnel {
        private val sock = java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port)
        init {
            Thread {
                val buf = ByteArray(16 * 1024)
                try { val i = sock.getInputStream(); while (true) { val n = i.read(buf); if (n < 0) break; onBytes(buf.copyOf(n)) } } catch (_: Exception) {}
                onEnd()
            }.apply { isDaemon = true; start() }
        }
        override fun send(bytes: ByteArray): Boolean { sock.getOutputStream().write(bytes); return true }
        override fun close() { runCatching { sock.close() } }
    }

    private fun tab(server: com.clauderemote.model.SshServer, type: ConnectionType = ConnectionType.SSH) = ClaudeSession(
        id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-server-t1", connectionType = type,
    ).also { tabs.addTab(it) }

    /** Kolo 4 (RA1): a MOSH tab over Cloudflare reconnects through the SSH fallback — with the check at its end. */
    @Test fun aShellThatDiesDuringAMoshReconnectSessionOverCloudflareDoesNotEndActive() {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        val f = SshdFixture.start().also { fixture = it }
        com.clauderemote.connection.CloudflareProxy.tunnelForTest = { a, b -> Tunnel(f.port, a, b) }
        try {
            val server = f.server.copy(startupCommand = "exit", useCloudflareProxy = true)
            storage.addServer(server)
            orchestrator = SessionOrchestrator(storage, tabs)
            tab(server, ConnectionType.MOSH)
            runBlocking { withTimeout(120_000) { orchestrator.reconnectSession("t1") } }
            assertTrue(logLines.any { "Mosh needs direct UDP" in it }, "the SSH fallback ran: ${logLines.takeLast(20)}")
            assertEquals(SessionStatus.ERROR, tabs.getTab("t1")!!.status, "not ACTIVE over a dead shell")
            assertTrue(logLines.any { "Reconnect failed" in it && "died during the connect" in it }, "${logLines.takeLast(10)}")
        } finally {
            com.clauderemote.connection.CloudflareProxy.tunnelForTest = null
        }
    }

    /** Kolo 4 (RA2): an ET tab whose ET path fails reconnects through the SSH fallback — with the check at its end. */
    @Test fun aShellThatDiesDuringTheSshFallbackOfAnEtReconnectDoesNotEndActive() {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        val f = SshdFixture.start().also { fixture = it }
        val server = f.server.copy(startupCommand = "exit", preferEternal = true)
        storage.addServer(server)
        orchestrator = SessionOrchestrator(storage, tabs)
        orchestrator.etServerSeam = { null }                    // no etserver: the ET path fails (its install never runs here)
        tab(server)
        runBlocking { withTimeout(120_000) { orchestrator.reconnectSession("t1") } }
        assertTrue(logLines.any { "Eternal Terminal path failed" in it }, "the ET path fell back to SSH: ${logLines.takeLast(20)}")
        assertEquals(SessionStatus.ERROR, tabs.getTab("t1")!!.status, "not ACTIVE over a dead shell")
        assertTrue(logLines.any { "Reconnect failed" in it && "died during the connect" in it }, "${logLines.takeLast(10)}")
    }

    /** Kolo 5 (verifier probe P-E4): during connectEt's bootstrap its carrier shell is no input route. */
    @Test fun theCarrierOfAFirstEtConnectIsNoRouteDuringItsBootstrap() {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        val f = SshdFixture.start().also { fixture = it }
        val server = f.server.copy(startupCommand = "exit", preferEternal = true)
        storage.addServer(server)
        orchestrator = SessionOrchestrator(storage, tabs)
        val control = SessionOrchestrator::class.java.getDeclaredField("claudeControl").also { it.isAccessible = true }.get(orchestrator)
            as com.clauderemote.session.service.ClaudeControlService
        val seen = java.util.concurrent.atomic.AtomicReference("unset")
        orchestrator.etServerSeam = {
            seen.set("route=${control.canDeliverNow("t1")} carrierConnected=${orchestrator.connectionRegistry.ssh("t1")?.isConnected}")
            null
        }
        tab(server)
        runBlocking { withTimeout(120_000) { orchestrator.reconnectSession("t1") } }
        assertEquals("route=false carrierConnected=true", seen.get(), "no input into the hidden carrier shell during the bootstrap")
    }

    /**
     * Kolo 4 (ET2, adv N2/P2c): the ET client dies (its `onDisconnect`: `removeEt` + autoReconnect) while a carrier rebuild
     * holds the tab; the rebuild ends "rebuilt" over a live carrier only. Its end must look at the recorded loss and run
     * the reconnect again — the carrier is no terminal. No ET binary: the state a bootstrap leaves (the local port, the
     * etserver port, a registered ET client) is put in place; a re-forward needs no etserver.
     */
    @Test fun anEtClientThatDiesDuringItsCarrierRebuildIsReconnectedAfterIt() {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        val f = SshdFixture.start().also { fixture = it }
        val server = f.server
        storage.addServer(server)
        orchestrator = SessionOrchestrator(storage, tabs)
        orchestrator.tmuxCommandSeam = { _, _, _ -> true }
        val t = tab(server)
        tabs.updateTabStatus("t1", SessionStatus.ACTIVE)
        val localPort = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        fun <T> field(name: String): T = SessionOrchestrator::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(orchestrator) as T
        field<MutableMap<String, Int>>("etLocalPorts")["t1"] = localPort
        field<MutableMap<String, Int>>("etServerPorts")[server.id] = f.port
        orchestrator.connectionRegistry.putEt("t1", com.clauderemote.connection.EtManager())
        val died = java.util.concurrent.atomic.AtomicBoolean(false)
        FileLogger.platformLog = { _, tag, message, e ->
            logLines += "$tag: $message" + (e?.let { " — ${it.message}" } ?: "")
            if ("ET carrier rebuilt for t1" in message && died.compareAndSet(false, true)) {
                orchestrator.connectionRegistry.removeEt("t1")        // what the ET client's onDisconnect does …
                runBlocking { orchestrator.autoReconnect(t, {}) }     // … while the rebuild still holds the tab
            }
        }
        runBlocking { withTimeout(60_000) { orchestrator.reconnectEtCarrier(t) {} } }
        assertTrue(died.get(), "the rebuild ran: ${logLines.takeLast(20)}")
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end && logLines.none { "Auto-reconnect succeeded for t1" in it }) Thread.sleep(50)
        assertTrue(logLines.any { "lost during its reconnect — reconnecting again" in it }, "the recorded loss was looked at: ${logLines.takeLast(20)}")
        assertTrue(logLines.any { "Auto-reconnect succeeded for t1" in it }, "and the tab got a live terminal again")
        assertTrue(orchestrator.connectionRegistry.ssh("t1")?.isConnected == true && !orchestrator.connectionRegistry.isEtCarrier("t1"),
            "its shell is the terminal now, not a carrier")
    }
}
