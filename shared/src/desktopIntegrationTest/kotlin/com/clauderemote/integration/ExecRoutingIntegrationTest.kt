package com.clauderemote.integration

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.session.TabManager
import com.clauderemote.session.service.CloseVerdict
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ConnectionRegistry
import com.clauderemote.session.service.ExecServerReconciler
import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.ExecUnavailableReason
import com.clauderemote.session.service.ManifestCommands
import com.clauderemote.session.service.PooledExecTransportManager
import com.clauderemote.session.service.PooledRemoteExec
import com.clauderemote.session.service.ReconcileParse
import com.clauderemote.session.service.UngatedExecChannels
import com.clauderemote.session.transcript.TranscriptStream
import com.clauderemote.storage.ServerStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exec routing against a real sshd + a private tmux server (everything under the fixture's
 * sandbox HOME). One shared exec session plays the server's exec transport; the tabs have NO
 * shell of their own (parked).
 */
class ExecRoutingIntegrationTest {
    private lateinit var fixture: SshdFixture
    private lateinit var session: com.jcraft.jsch.Session
    private lateinit var registry: ConnectionRegistry
    private val tabs = TabManager()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Before fun boot() {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        CommFlags.resetForTest()
        fixture = SshdFixture.start()
        session = fixture.openRawSession()
        registry = ConnectionRegistry(ServerStorage(InMemoryKeyValueStore()), tabs)
        registry.execSessions = PooledExecTransportManager { session }
        registry.execChannels = UngatedExecChannels { session }
        registry.remoteExec = PooledRemoteExec(UngatedExecChannels { session })
        // The sandbox sees the whole process table; keep `ps` to processes of this sandbox, so the reconcile script
        // never reads the config dirs of the LIVE claude processes of this box.
        val shim = File(fixture.shims, "ps")
        shim.writeText(
            "#!/bin/sh\n/bin/ps \"\$@\" | while read -r pid rest; do\n" +
                "  if tr '\\0' '\\n' < /proc/\$pid/environ 2>/dev/null | grep -qxF \"HOME=\$HOME\"; then echo \"\$pid \$rest\"; fi\n" +
                "done\n",
        )
        shim.setExecutable(true)
    }

    @After fun shutdown() {
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.liveSessionProvider = null
        CommFlags.resetForTest()
        if (::session.isInitialized) session.disconnect()
        if (::fixture.isInitialized) fixture.close()
    }

    private val serverId get() = fixture.server.id

    private fun newTmux(name: String, command: String = "sleep 300") {
        fixture.startTmuxServer()
        val r = fixture.tmux("new-session", "-d", "-s", name, command)
        waitUntil(5_000, "tmux session $name (new-session exit=${r.exit} said: ${r.out.trim()}; command: $command)") { name in fixture.tmuxSessions() }
    }

    // ---- allowFresh = false: no new TCP ------------------------------------------------------

    @Test fun noFreshConnectionIsOpenedWhenRoutingForbidsIt() = runBlocking {
        val before = fixture.acceptedHandshakes()
        val e = assertFailsWith<ExecUnavailable> { SshSessionHelper.withSession(fixture.server, allowFresh = false) { 1 } }
        assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
        // a poller with exec routing: the tick is skipped, no handshake
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val stream = TranscriptStream(fixture.server, "/work", scope, liveSession = { null }, viaExec = { true })
            stream.start("11111111-1111-1111-1111-111111111111")
            stream.pollNow()
            Thread.sleep(500)
            stream.stop()
        } finally { scope.cancel() }
        assertEquals(before, fixture.acceptedHandshakes(), "no TCP connection may be opened")
        // control: with a fresh connection allowed, the very same call costs exactly one handshake
        assertEquals(7, SshSessionHelper.withSession(fixture.server) { 7 })
        assertEquals(before + 1, fixture.acceptedHandshakes())
    }

    // ---- rename over the exec transport (no shell) -------------------------------------------

    @Test fun renameGoesToTheExactNameOnly() = runBlocking {
        newTmux("claude-server-old"); newTmux("claude-server-y-2")
        val ok = registry.execRun(serverId, ManifestCommands.renameSession("claude-server-old", "claude-server-new"))!!
        assertEquals(0, ok.exitStatus)
        assertTrue("claude-server-new" in fixture.tmuxSessions() && "claude-server-old" !in fixture.tmuxSessions())
        // renaming a name that does not exist must not hit its longer namesake (tmux's prefix matching)
        val miss = registry.execRun(serverId, ManifestCommands.renameSession("claude-server-y", "claude-server-z"))!!
        assertTrue(miss.exitStatus != 0)
        assertTrue("claude-server-y-2" in fixture.tmuxSessions() && "claude-server-z" !in fixture.tmuxSessions())
    }

    // ---- reconcile with 3 sessions, one killed and tombstoned --------------------------------

    private fun tab(id: String, name: String) = ClaudeSession(
        id = id, server = fixture.server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = name, connectionType = ConnectionType.SSH,
    )

    @Test fun reconcileAllSeesThreeSessionsAndOnlyTheKilledTombstonedOneIsForgotten() = runBlocking {
        val names = listOf("claude-server-a", "claude-server-b", "claude-server-c")
        names.forEach { newTmux(it) }
        // the close another device would have done: tombstone first, then kill
        assertEquals(0, registry.execRun(serverId, ManifestCommands.tombstone("claude-server-c", durable = true))!!.exitStatus)
        fixture.tmux("kill-session", "-t", "=claude-server-c")
        waitUntil(5_000, "c gone") { "claude-server-c" !in fixture.tmuxSessions() }

        val snap = ReconcileParse.parse(registry.execRead(serverId, ManifestCommands.reconcileAll(names))!!, json)
        assertEquals(setOf("claude-server-c"), snap.tombstoned)
        assertTrue(snap.tmuxNames!!.containsAll(listOf("claude-server-a", "claude-server-b")) && "claude-server-c" !in snap.tmuxNames!!)
        assertEquals(setOf("claude-server-a", "claude-server-b"), snap.panePids!!.keys) // c has no pane any more
        assertEquals(CloseVerdict.FORGET, snap.closeVerdict("claude-server-c"))
        assertEquals(CloseVerdict.KEEP, snap.closeVerdict("claude-server-a"))

        val forgotten = mutableListOf<String>()
        val tabNames = mapOf("a" to names[0], "b" to names[1], "c" to names[2])
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val rec = ExecServerReconciler(
                scope = scope,
                run = { sid, cmd, ms -> registry.execRead(sid, cmd, ms) },
                serverIdOf = { if (it in tabNames) serverId else null },
                tmuxNameOf = { tabNames[it] }, tabUuidOf = { null },
                adoptUuid = { _, _, _ -> }, forget = { forgotten += it }, isBackground = { false },
                periodMs = 3_600_000,
            )
            tabNames.keys.forEach { rec.register(it) }
            for (id in tabNames.keys) rec.checkClosed(id, 0)
            assertEquals(listOf("c"), forgotten)
        } finally { scope.cancel() }
    }

    // ---- CAP of a real pane, following settings.json live -------------------------------------

    @Test fun capVerdictFollowsSettingsJsonOfARunningClaudeWithoutRestartingIt() = runBlocking {
        val bin = File(fixture.home, "bin").apply { mkdirs() }
        val claude = File(bin, "claude")
        // A copy of bash sleeping in a child (the trailing `:` stops bash exec-ing it): comm and exe are "claude" whatever
        // coreutils is installed. A copied `sleep` is no stand-in: uutils (ubuntu-26.04) is one multicall binary that picks the
        // tool from its own file name and exits for the unknown name "claude", so the pane died and the session never showed.
        val bashBin = File(ProcessBuilder("bash", "-c", "command -v bash").start().inputStream.bufferedReader().readText().trim())
        bashBin.copyTo(claude, overwrite = true); claude.setExecutable(true)
        newTmux("claude-server-cap", "'${claude.absolutePath}' -c 'sleep 300; :'")
        newTmux("claude-server-peer")
        var lastOut = ""
        fun reconcileOut(names: List<String>): String = runBlocking { registry.execRead(serverId, ManifestCommands.reconcileAll(names))!! }.also { lastOut = it }
        try {
            waitUntil(5_000, "claude-named process") {
                ReconcileParse.parse(reconcileOut(listOf("claude-server-cap")), json).claude?.isNotEmpty() == true
            }
        } catch (e: AssertionError) { throw AssertionError("${e.message}; last reconcile output: $lastOut; tmux: ${fixture.tmuxSessions()}") }
        fun cap(): Boolean? = ReconcileParse.parse(reconcileOut(listOf("claude-server-cap")), json).capability("claude-server-cap")

        val cfg = File(fixture.home, ".claude").apply { mkdirs() }
        assertEquals(false, cap(), "no settings.json: hooks are not installed: $lastOut")
        File(cfg, "settings.json").writeText("""{"hooks":{"Stop":[{"hooks":[{"command":"claude-remote-ev v2 stop"}]}]}}""")
        assertEquals(true, cap(), "installed after the claude started: capable at once (hot reload): $lastOut")
        File(cfg, "settings.local.json").writeText("""{"disableAllHooks": true}""")
        assertEquals(false, cap(), lastOut)
        File(cfg, "settings.local.json").delete()
        assertEquals(true, cap(), lastOut)
        File(cfg, "settings.json").writeText("{}")
        assertEquals(false, cap(), lastOut)
        assertNull(ReconcileParse.parse(reconcileOut(listOf("claude-server-peer")), json).capability("claude-server-peer"),
            "a pane without claude is unknown, not a no: $lastOut")
    }

    // ---- SFTP through the exec channels for a parked tab --------------------------------------

    @Test fun downloadAndStatOfAParkedTabGoOverSftpOnTheExecTransport() = runBlocking {
        tabs.addTab(tab("t1", "claude-server-a"))
        assertNull(registry.ssh("t1"))
        File(fixture.home, "hello.txt").writeText("hello over sftp")
        val ops = com.clauderemote.session.service.RemoteOpsService(CoroutineScope(Job() + Dispatchers.IO), registry, tabs)
        assertEquals("hello over sftp", String(ops.downloadFile("t1", "~/hello.txt")!!))
        assertEquals(setOf("~/hello.txt"), ops.statFiles("t1", listOf("~/hello.txt", "~/nope.txt")))
        // with exec routing off for the server a parked tab has no way to do it (as before)
        CommFlags.applyServerOff(serverId, setOf("exec"))
        assertNull(ops.downloadFile("t1", "~/hello.txt"))
    }

    // ---- exec not UP: the tab session of the server is the fallback ----------------------------

    /** `SshManager.disconnect` joins its blocking read loop; closing the raw session first lets that read return. */
    private fun closeTab(mgr: com.clauderemote.connection.SshManager) {
        try { mgr.getSession()?.disconnect() } catch (_: Exception) {}
        runBlocking { kotlinx.coroutines.withTimeout(10_000) { mgr.disconnect() } }
    }

    private fun connectedTab(id: String, name: String): com.clauderemote.connection.SshManager {
        tabs.addTab(tab(id, name))
        val mgr = com.clauderemote.connection.SshManager(ServerStorage(InMemoryKeyValueStore()))
        runBlocking { mgr.connect(fixture.server, onOutput = {}, onConnectionLost = {}) }
        registry.putSsh(id, mgr)
        return mgr
    }

    @Test fun whenTheExecTransportIsNotUpALiveTabSessionOfTheServerCarriesTheCommand() = runBlocking {
        registry.execSessions = PooledExecTransportManager { null }
        registry.remoteExec = PooledRemoteExec(UngatedExecChannels { null }) // NOT_UP
        assertNull(registry.execRun(serverId, "echo hi")) // nothing at all: null, not an exception
        val mgr = connectedTab("t2", "claude-server-b")
        try {
            assertEquals("hi\n", registry.execRead(serverId, "echo hi"))
            assertEquals(3, registry.execRun(serverId, "exit 3")!!.exitStatus)
        } finally { closeTab(mgr) }
    }

    @Test fun aChannelBlockThatThrowsNotUpItselfIsNotRunAgainOnTheTabSession() = runBlocking {
        val mgr = connectedTab("t2", "claude-server-b")
        try {
            var runs = 0
            val thrown = runCatching {
                kotlinx.coroutines.withTimeout(20_000) {
                    registry.useChannel<Unit>(serverId, com.clauderemote.session.service.ExecKind.SHORT) {
                        runs++
                        throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
                    }
                }
            }.exceptionOrNull()
            assertTrue(thrown is ExecUnavailable, "was: $thrown")
            assertEquals(1, runs)
        } finally { closeTab(mgr) }
    }

    @Test fun theAccountsScreenUsesALiveTabSessionInsteadOfAHandshakeWhileTheExecTransportIsNotUp() = runBlocking {
        registry.execSessions = PooledExecTransportManager { null } // exec routing active, transport not UP
        registry.remoteExec = PooledRemoteExec(UngatedExecChannels { null })
        val mgr = connectedTab("t2", "claude-server-b")
        try {
            val storage = ServerStorage(InMemoryKeyValueStore()).also { it.addServer(fixture.server) }
            val before = fixture.acceptedHandshakes()
            com.clauderemote.session.service.AccountService(registry, storage).listAccounts(serverId)
            assertEquals(before, fixture.acceptedHandshakes(), "the accounts poll must ride the live tab session, not open a TCP connection")
        } finally { closeTab(mgr) }
    }

    @Test fun gitStatusOfAParkedTabUsesAnotherTabsSessionOnlyUnderExecRouting() = runBlocking {
        if (fixture.run("git", "--version").exit != 0) { println("git missing — skipping"); return@runBlocking }
        val repo = File(fixture.home, "repo").apply { mkdirs() }
        fixture.run("git", "-C", repo.path, "init", "-q")
        fixture.run("git", "-C", repo.path, "-c", "user.email=a@b.c", "-c", "user.name=n", "commit", "-q", "--allow-empty", "-m", "x")
        tabs.addTab(tab("t1", "claude-server-a").copy(folder = "~/repo")) // parked: no shell of its own
        val mgr = connectedTab("t2", "claude-server-b")
        try {
            registry.execSessions = PooledExecTransportManager { null } // exec routing active but the transport is not UP
            registry.remoteExec = PooledRemoteExec(UngatedExecChannels { null })
            val git = com.clauderemote.session.service.GitStatusService(CoroutineScope(Job() + Dispatchers.IO), registry, tabs) { false }
            git.probeGitStatusOnce("t1")
            assertTrue(git.gitStatuses.value["t1"] != null, "served through the live session of the other tab")
            // routing off for the server: today's rule, a tab without a shell of its own probes nothing
            git.clearSession("t1")
            CommFlags.applyServerOff(serverId, setOf("exec"))
            git.probeGitStatusOnce("t1")
            assertNull(git.gitStatuses.value["t1"])
        } finally { closeTab(mgr) }
    }

    // ---- streamd EOF without hello invalidates the bootstrap memo -----------------------------

    @Test fun aStreamDaemonThatEndsWithoutHelloInvalidatesTheBootstrapMemo() = runBlocking {
        tabs.addTab(tab("t1", "claude-server-a"))
        val invalidated = java.util.concurrent.CopyOnWriteArrayList<String>()
        registry.bootstrap = object : com.clauderemote.session.service.ServerBootstrap {
            override suspend fun ensure(serverId: String) {}
            override fun invalidate(serverId: String) { invalidated += serverId }
            override suspend fun refreshFlags(serverId: String) {}
        }
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            // the sandbox HOME has no ~/.claude-remote/streamd.py: the daemon command exits at once, no `hello` ever arrives
            val svc = com.clauderemote.session.service.TranscriptService(scope, registry, tabs, { false }, { _, _ -> }, null, { _, _ -> null })
            svc.registerStreamWatch("t1")
            waitUntil(15_000, "bootstrap invalidated") { invalidated.isNotEmpty() }
            assertEquals(serverId, invalidated.first())
        } finally { scope.cancel() }
    }
}
