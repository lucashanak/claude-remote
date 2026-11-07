package com.clauderemote.session.service

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.session.TabManager
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.jcraft.jsch.JSch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Operations on a PARKED tab: the tab exists, no shell (SshManager) is attached for it, and
 * the server's exec transport carries the work. A fake [RemoteExec] stands in for the
 * transport; nothing here touches a network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParkedTabOpsTest {
    private val server = SshServer(id = "s1", name = "one", host = "unreachable.invalid", username = "u")
    private val tab = ClaudeSession(
        id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-server-old", connectionType = ConnectionType.SSH,
        claudeSessionId = "11111111-1111-1111-1111-111111111111",
    )

    @BeforeTest fun reset() = CommFlags.resetForTest()
    @AfterTest fun cleanup() = CommFlags.resetForTest()

    private class Call(val cmd: String, val stdin: String?, val kind: ExecKind)

    private class FakeExec(var exit: Int = 0, var out: String = "") : RemoteExec {
        val calls = mutableListOf<Call>()
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult {
            calls += Call(cmd, stdin?.toString(Charsets.UTF_8), kind)
            return ExecResult(out, exit)
        }
    }

    private class Wiring(val registry: ConnectionRegistry, val tabs: TabManager, val storage: SessionStorage, val scope: CoroutineScope)

    private fun TestScope.wire(exec: RemoteExec?): Wiring {
        val tabs = TabManager().also { it.addTab(tab) }
        val registry = ConnectionRegistry(ServerStorage(FakeKeyValueStore()), tabs)
        if (exec != null) {
            registry.remoteExec = exec
            registry.execSessions = object : ExecSessionProvider {
                override fun session(serverId: String) = JSch().getSession("u", "localhost", 22)
                override suspend fun await(serverId: String, ms: Long) = session(serverId)
            }
        }
        val storage = SessionStorage(FakeKeyValueStore())
        storage.upsert(SessionStorage.fromClaudeSession(tab))
        return Wiring(registry, tabs, storage, backgroundScope)
    }

    private fun persistence(w: Wiring, transcript: TranscriptService) = SessionPersistenceService(
        scope = w.scope, registry = w.registry, tabManager = w.tabs, serverStorage = ServerStorage(FakeKeyValueStore()),
        sessionStorage = w.storage, transcript = transcript, terminalIO = TerminalIOService(w.registry),
        isBackground = { false }, readRealSessionId = { _, _ -> null }, probeTmuxAlive = { _, _ -> true },
        onActivityUpdate = { _, _ -> }, disconnectSession = {}, onForgotten = { _, _ -> },
    )

    private fun transcript(w: Wiring) = TranscriptService(
        scope = w.scope, registry = w.registry, tabManager = w.tabs, isBackground = { false },
        onContextUpdate = { _, _ -> }, sessionStorage = w.storage, readRealSessionId = { _, _ -> null },
    )

    @Test fun renameOfAParkedTabRunsOverTheExecTransportAndPushesTheManifest() = runTest {
        val exec = FakeExec()
        val w = wire(exec)
        assertEquals(null, w.registry.ssh("t1")) // parked: no shell of its own
        persistence(w, transcript(w)).renameTmuxSession("t1", "claude-server-old", "claude-server-new")
        assertEquals("tmux rename-session -t '=claude-server-old' 'claude-server-new'", exec.calls[0].cmd)
        val push = exec.calls[1]
        assertTrue("sessions.json" in push.cmd || "push(merge)" in push.cmd, push.cmd)
        assertTrue("claude-server-new" in push.stdin.orEmpty(), "the pushed manifest carries the new name")
        assertEquals("claude-server-new", w.storage.load().single().tmuxSessionName)
    }

    @Test fun aFailedRenameChangesNothingLocally() = runTest {
        val exec = FakeExec(exit = 1)
        val w = wire(exec)
        persistence(w, transcript(w)).renameTmuxSession("t1", "claude-server-old", "claude-server-new")
        assertEquals(1, exec.calls.size) // no manifest push after a failed rename
        assertEquals("claude-server-old", w.storage.load().single().tmuxSessionName)
    }

    @Test fun theStreamDaemonWaitsQuietlyForATabSessionWhileRoutingIsOff() = runTest {
        val w = wire(null) // routing off, no tab has a shell: nothing to open a channel on yet
        val logged = mutableListOf<String>()
        val before = com.clauderemote.util.FileLogger.platformLog
        com.clauderemote.util.FileLogger.platformLog = { _, _, m, _ -> logged += m }
        try {
            transcript(w).registerStreamWatch("t1")
            advanceTimeBy(30_000)
        } finally { com.clauderemote.util.FileLogger.platformLog = before }
        assertTrue(logged.none { "streamd failed" in it }, "a mid-reconnect wait is not a failure: $logged")
    }

    @Test fun theStreamDaemonWaitsQuietlyWhileTheExecTransportIsNotUpAndNoTabCanStandIn() = runTest {
        val w = wire(FakeExec()) // routing on, but the exec transport is down and no tab has a shell
        w.registry.execChannels = object : ExecChannels {
            override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T =
                throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
            override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Session) -> T): T =
                throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
        }
        val logged = mutableListOf<String>()
        val before = com.clauderemote.util.FileLogger.platformLog
        com.clauderemote.util.FileLogger.platformLog = { _, _, m, _ -> logged += m }
        try {
            transcript(w).registerStreamWatch("t1")
            advanceTimeBy(30_000)
        } finally { com.clauderemote.util.FileLogger.platformLog = before }
        assertTrue(logged.none { "streamd failed" in it }, "a down exec transport is a quiet wait, not a failure: $logged")
    }

    @Test fun withExecRoutingOffAParkedTabRenameStaysAnInertNoOp() = runTest {
        val w = wire(null)
        persistence(w, transcript(w)).renameTmuxSession("t1", "claude-server-old", "claude-server-new")
        assertEquals("claude-server-old", w.storage.load().single().tmuxSessionName)
    }

    @Test fun kickSyncOfAParkedTabAsksTheReconcilerForTheUuid() = runTest {
        val w = wire(FakeExec())
        val asked = mutableListOf<String>()
        w.registry.reconciler = object : ServerReconciler {
            override fun register(sessionId: String) {}
            override fun unregister(sessionId: String) {}
            override suspend fun checkClosed(sessionId: String, delayMs: Long) {}
            override suspend fun checkUuid(sessionId: String) { asked += sessionId }
            override suspend fun capability(serverId: String, tmux: String): Boolean? = null
        }
        val service = transcript(w)
        service.transcriptFlow("t1")
        testScheduler.runCurrent()
        assertEquals(listOf("t1"), asked)
        // a confirmed uuid is not asked again
        service.setConfirmedUuid("t1", tab.claudeSessionId!!)
        asked.clear()
        service.transcriptFlow("t1")
        testScheduler.runCurrent()
        assertTrue(asked.isEmpty())
    }

    @Test fun closeRecordsALocalCloseThatTheReducerCanQuery() = runTest {
        val exec = FakeExec()
        val w = wire(exec)
        val p = persistence(w, transcript(w))
        assertEquals(false, p.recentlyClosedLocally("s1", "claude-server-old", 60_000))
        p.forgetSession("t1", userInitiated = true)
        testScheduler.runCurrent()
        assertEquals(true, p.recentlyClosedLocally("s1", "claude-server-old", 60_000))
        assertEquals(false, p.recentlyClosedLocally("s2", "claude-server-old", 60_000)) // names are only unique per server
        assertEquals(false, p.recentlyClosedLocally("s1", "claude-server-other", 60_000))
        assertEquals(false, p.recentlyClosedLocally("s1", "claude-server-old", -1))
        // server side went over the exec transport: tombstone FIRST, then the kill, then the manifest
        val cmds = exec.calls.map { it.cmd }
        assertTrue("forgotten" in cmds[0], cmds[0])
        // the close record rides in the tombstone's exec, after it and before the kill (Jarvis F0b)
        assertEquals(ManifestCommands.tombstoneAndRecord("claude-server-old", durable = true, by = "app"), cmds[0])
        assertEquals("tmux kill-session -t '=claude-server-old'", cmds[1])
        assertTrue(cmds.size >= 3)
    }

    @Test fun aPruneIsRecordedAsPruneAndTransient() = runTest {
        val exec = FakeExec()
        val w = wire(exec)
        val p = persistence(w, transcript(w))
        p.forgetSession("t1", userInitiated = false)
        testScheduler.runCurrent()
        assertEquals(ManifestCommands.tombstoneAndRecord("claude-server-old", durable = false, by = "prune"), exec.calls.first().cmd)
    }

    @Test fun gitStatusOfAParkedTabIsProbedOverTheExecTransport() = runTest {
        val exec = FakeExec(out = "feature/x\n M file\n2\t3\n")
        val w = wire(exec)
        val git = GitStatusService(w.scope, w.registry, w.tabs) { false }
        git.probeGitStatusOnce("t1")
        assertTrue("git rev-parse" in exec.calls.single().cmd)
        val st = git.gitStatuses.value["t1"]!!
        assertEquals("feature/x", st.branch); assertTrue(st.dirty); assertEquals(3, st.ahead); assertEquals(2, st.behind)
    }

    @Test fun gitStatusStaysInertForAParkedTabWithoutExecRouting() = runTest {
        val w = wire(null)
        val git = GitStatusService(w.scope, w.registry, w.tabs) { false }
        git.probeGitStatusOnce("t1")
        assertTrue(git.gitStatuses.value.isEmpty())
    }

    @Test fun uploadFromAParkedTabStreamsTheBytesThroughExec() = runTest {
        val exec = FakeExec()
        val w = wire(exec)
        val remote = RemoteOpsService(w.scope, w.registry, w.tabs).uploadFile("t1", byteArrayOf(1, 2, 3), "shot.png")
        assertEquals("/tmp/claude-uploads/shot.png", remote)
        val c = exec.calls.single()
        assertEquals("mkdir -p '/tmp/claude-uploads' && cat > '/tmp/claude-uploads/shot.png'", c.cmd)
        assertEquals(3, c.stdin!!.length)
        assertEquals(ExecKind.SHORT, c.kind, "an upload ends: it must not hold one of the two long-stream permits")
    }

    @Test fun oneShotUsageFetchUsesTheExecTransportAndReportsNoTransportAsFalse() = runTest {
        val exec = FakeExec(out = "{}")
        val w = wire(exec)
        val usage = UsageService(w.scope, w.registry, w.tabs, { false }, { _, _ -> })
        assertEquals(true, usage.fetchRateLimitsOnce("s1", null))
        assertEquals(1, exec.calls.size)
        assertEquals(false, UsageService(w.scope, wire(null).registry, w.tabs, { false }, { _, _ -> }).fetchRateLimitsOnce("s1", null))
    }

    @Test fun theCcusagePollOfAServerIsAShortExecNotALongStream() = runTest {
        val exec = FakeExec(out = "{}")
        val w = wire(exec)
        val usage = UsageService(w.scope, w.registry, w.tabs, { false }, { _, _ -> })
        usage.startServerUsagePolling("s1")
        advanceTimeBy(5_001)
        val c = exec.calls.single()
        assertTrue("ccusage" in c.cmd)
        assertEquals(ExecKind.SHORT, c.kind, "the long permits belong to the notify watcher and the stream daemon")
    }

    @Test fun aServerWithOnlyParkedTabsReadsOnlineWhileItsExecTransportIsUp() = runTest {
        val w = wire(FakeExec())
        val health = ServerHealthService(w.scope, w.registry, w.tabs) { false }
        health.probeServers(listOf(server), force = true)
        testScheduler.runCurrent()
        assertEquals(com.clauderemote.model.ServerHealth.ONLINE, health.serverHealth.value["s1"])
    }

    @Test fun tmuxProbesByServerWorkWithoutAnAttachedShellAndFailOpen() = runTest {
        val exec = FakeExec(out = "NO\n")
        val w = wire(exec)
        val probes = TmuxProbes(w.scope, w.registry, w.tabs, TerminalIOService(w.registry))
        assertEquals(false, probes.probeTmuxSession("s1", "claude-server-old"))
        assertEquals("tmux has-session -t '=claude-server-old' 2>/dev/null && echo YES || echo NO", exec.calls.last().cmd)
        exec.out = "YES\n"
        assertEquals(true, probes.probeTmuxSession("s1", "claude-server-old"))
        exec.out = "" // a channel that closed before answering is not "gone"
        assertEquals(true, probes.probeTmuxSession("s1", "claude-server-old"))
        assertEquals(true, TmuxProbes(w.scope, wire(null).registry, w.tabs, TerminalIOService(w.registry)).probeTmuxSession("s1", "x")) // no transport: fail-open
        exec.out = "YES\n"
        assertEquals(true, probes.probeTranscriptExists("s1", "/work", "11111111-1111-1111-1111-111111111111"))
        exec.out = "NO\n"
        assertEquals(false, probes.probeTranscriptExists("s1", "/work", "11111111-1111-1111-1111-111111111111"))
        exec.out = "garbage"
        assertEquals(null, probes.probeTranscriptExists("s1", "/work", "11111111-1111-1111-1111-111111111111"))
    }
}
