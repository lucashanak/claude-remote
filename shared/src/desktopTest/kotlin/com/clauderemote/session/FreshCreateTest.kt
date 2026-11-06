package com.clauderemote.session

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V16 (dávka B, round 6): a NEW session is created on the server over an exec channel BEFORE its tab exists, and the
 * tab then attaches it like any session of this device. A name another session holds is left exactly as it is and the
 * next free name is taken; a lost answer is retried and the session it created is recognised by the launch's nonce
 * (no orphan, no second name); a creation that cannot happen ends the launch with a readable error and no tab.
 *
 * The server is a scripted tmux behind the exec seam ([FakeTmux]: what `new-session -d -s <name> -e CR_LAUNCH=<nonce>`
 * and the owner check answer); the connect paths are stood in for by the orchestrator's connect seam, which records
 * what each was asked to do.
 */
class FreshCreateTest {
    @get:org.junit.Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val savedLog = FileLogger.platformLog
    private val logLines = CopyOnWriteArrayList<String>()
    private val tabs = TabManager()
    private val storage = ServerStorage(FakeKeyValueStore())
    private val sessions = SessionStorage(FakeKeyValueStore())
    private var server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private val base = "claude-box-work"
    private var orchestrator: SessionOrchestrator? = null

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
    }

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        FileLogger.platformLog = savedLog
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.liveSessionProvider = null
        SshSessionHelper.fallbackRunner = null
        CommFlags.resetForTest()
    }

    /** One tmux session of the scripted server: who created it (the launch nonce in its environment, null = none). */
    private data class Tmux(val owner: String?, val created: Long, val panePid: Int)

    /** A tmux server behind the exec seam; answers the creation command the way the real one does (see the IT). */
    private inner class FakeTmux : RemoteExec {
        val sessions = ConcurrentHashMap<String, Tmux>()
        val ran = CopyOnWriteArrayList<String>()
        /** The creations seen: (name, nonce). */
        val creates = CopyOnWriteArrayList<Pair<String, String>>()
        /** The next N creations run on the server but their answer never comes back. */
        @Volatile var loseAnswers = 0
        /** Holds every creation until completed. */
        @Volatile var gate: CompletableDeferred<Unit>? = null
        /** The socket guard refuses: nothing is created, the reason is on stderr. */
        @Volatile var guardRefusal: String? = null
        /** Not UP until [up] (the exec transport is down): NOT_UP, as the gated exec reports it. */
        @Volatile var up = true
        /** The cleanups seen (R6-2/R6-3): (name, nonce). */
        val cleanups = CopyOnWriteArrayList<Pair<String, String>>()
        // the run of the creation is quoted once more inside `timeout … sh -c '…'` (K6-1): `'\''name'\''`
        private val createRe = Regex("""tmux new-session -d -x 200 -y 50 -s '(?:\\'')?([^'\\]+)'(?:\\'')? -e CR_LAUNCH=([A-Za-z0-9]+) """)
        private val cleanupRe = Regex("""show-environment -t '=([^']+)' CR_LAUNCH 2>/dev/null\)" = 'CR_LAUNCH=([A-Za-z0-9]+)' ] && timeout \d+ tmux kill-session""")

        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult {
            if (!up) throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
            ran += cmd
            cleanupRe.find(cmd)?.let { c ->
                // compare-and-kill, as the server runs it: only a session carrying the nonce goes
                val (name, nonce) = c.groupValues[1] to c.groupValues[2]
                cleanups += name to nonce
                val killed = sessions[name]?.owner == nonce && sessions.remove(name) != null
                return ExecResult(if (killed) "__CR_CLEANED__\n" else "", 0)
            }
            val m = createRe.find(cmd) ?: return ExecResult("", 0)
            gate?.await()
            val (name, nonce) = m.groupValues[1] to m.groupValues[2]
            creates += name to nonce
            guardRefusal?.let { return ExecResult("$it\n__CR_CREATE_RC=1\n__CR_CREATE_ABSENT__\n", 0) }
            val rc = if (sessions.putIfAbsent(name, Tmux(nonce, System.nanoTime(), 4000 + sessions.size)) == null) 0 else 1
            val owner = sessions.getValue(name).owner?.let { "CR_LAUNCH=$it" }.orEmpty()
            val out = (if (rc == 1) "duplicate session: $name\n" else "") + "__CR_CREATE_RC=$rc\n__CR_CREATE_OWNER=$owner\n"
            if (loseAnswers > 0) { loseAnswers--; throw ExecUnavailable(ExecUnavailableReason.TIMEOUT, "answer lost") }
            return ExecResult(out, 0)
        }

        /** Commands that touched [name] in any way other than this launch's creation tries. */
        fun touching(name: String): List<String> = ran.filter { "'=$name'" in it || "'=$name:'" in it || "'$name'" in it }
            .filterNot { createRe.find(it)?.groupValues?.get(1) == name }
    }

    /** What a connect path was asked to do. */
    private data class Connect(val path: String, val tmuxName: String, val isNew: Boolean, val checkClosedElsewhere: Boolean, val tabExisted: Boolean)

    private fun orchestrator(exec: FakeTmux?): Pair<SessionOrchestrator, CopyOnWriteArrayList<Connect>> {
        storage.addServer(server)
        val o = SessionOrchestrator(storage, tabs, sessions).also { orchestrator = it }
        if (exec != null) o.connectionRegistry.remoteExec = exec
        val connects = CopyOnWriteArrayList<Connect>()
        o.connectSeam = { path, s, isNew, closed -> connects += Connect(path, s.tmuxSessionName, isNew, closed, tabs.getTab(s.id) != null); true }
        return o to connects
    }

    private fun launch(o: SessionOrchestrator, name: String = base, type: ConnectionType = ConnectionType.SSH): ClaudeSession = runBlocking {
        withTimeout(40_000) { o.launchSession(server, "/work", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, type, name, isNewTmuxSession = true) }
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(20)
        return cond()
    }

    @Test fun aNameAnotherSessionHoldsIsLeftAloneAndTheTabGetsTheNextFreeName() {
        val exec = FakeTmux()
        val foreign = Tmux(owner = null, created = 1L, panePid = 777)
        exec.sessions[base] = foreign
        val (o, connects) = orchestrator(exec)
        val s = launch(o)
        assertEquals("$base-2", s.tmuxSessionName, "the tab is the session it created, under the next free name")
        assertEquals(foreign, exec.sessions[base], "the other session: same pane, same creation time, no owner given to it")
        assertEquals(listOf(base, "$base-2"), exec.creates.map { it.first }, "one try of the taken name, then the next free one")
        assertTrue(exec.touching(base).isEmpty(), "nothing else addressed the other session (kill, attach, respawn, rename): ${exec.touching(base)}")
        assertEquals(listOf("$base-2"), tabs.tabs.value.map { it.tmuxSessionName }, "one tab, the free name")
        assertEquals(listOf(Connect("ssh", "$base-2", isNew = false, checkClosedElsewhere = false, tabExisted = true)), connects.toList(),
            "the tab attaches the created session (single-client attach; gone again = the usual rebuild), never launches in the tab")
        assertTrue(waitFor(5_000) { sessions.load().any { it.tmuxSessionName == "$base-2" } }, "the tab is persisted as this device's session")
        assertTrue(sessions.load().none { it.tmuxSessionName == base }, "the other session's name is never persisted: ${sessions.load()}")
    }

    @Test fun aTakenNameMovesOnPastTheRequestedOneNeverBackToANameThePickerSkipped() {
        val exec = FakeTmux()
        exec.sessions["$base-3"] = Tmux(null, 1L, 1)
        val (o, _) = orchestrator(exec)
        // the picker skipped base and base-2 (taken by its listing, the manifest or the last scan): never go back to them
        val s = launch(o, name = "$base-3")
        assertEquals("$base-4", s.tmuxSessionName)
        assertEquals(listOf("$base-3", "$base-4"), exec.creates.map { it.first })
    }

    @Test fun aLostAnswerIsRetriedAndTheSessionThatTryCreatedIsRecognisedAsOurs() {
        val exec = FakeTmux().apply { loseAnswers = 1 }
        val (o, connects) = orchestrator(exec)
        val s = launch(o)
        assertEquals(base, s.tmuxSessionName, "the retry ran into the session the lost try created: its nonce is this launch's")
        assertEquals(setOf(base), exec.sessions.keys, "one session on the server, no second name, nothing orphaned")
        assertEquals(2, exec.creates.size, "the lost try and the retry: ${exec.creates}")
        assertEquals(1, exec.creates.map { it.second }.toSet().size, "both tries carry the same nonce")
        assertEquals(listOf(base), connects.map { it.tmuxName })
    }

    @Test fun noTabExistsUntilTheSessionIsCreated() {
        val exec = FakeTmux().apply { gate = CompletableDeferred() }
        exec.sessions[base] = Tmux(null, 1L, 777)
        val (o, connects) = orchestrator(exec)
        runBlocking {
            val launching = async(Dispatchers.IO) {
                withTimeout(40_000) { o.launchSession(server, "/work", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, ConnectionType.SSH, base, isNewTmuxSession = true) }
            }
            assertTrue(waitFor(10_000) { exec.ran.any { "new-session -d" in it } }, "the creation is under way")
            Thread.sleep(300)
            // while the server decides whose the name is, there is no tab: nothing can probe its account, adopt a uuid,
            // rename it, restart it or close it, and nothing of it is stored
            assertTrue(tabs.tabs.value.isEmpty(), "no tab before the session is created: ${tabs.tabs.value}")
            assertTrue(sessions.load().isEmpty(), "nothing persisted: ${sessions.load()}")
            assertEquals(0, o.restartClaudeForAllSessions(), "restart all finds no tab to respawn")
            assertTrue(connects.isEmpty(), "no connect")
            exec.gate!!.complete(Unit)
            val s = launching.await()
            assertEquals("$base-2", s.tmuxSessionName)
        }
        assertTrue(exec.ran.none { "respawn-pane" in it || "rename-session" in it || "kill-session -t '=$base'" in it }, "nothing hit the other session: ${exec.touching(base)}")
        assertTrue(connects.single().tabExisted && !connects.single().isNew)
    }

    @Test fun aCreationTheServerRefusesEndsTheLaunchWithAReadableErrorAndNoTab() {
        val exec = FakeTmux().apply { guardRefusal = "claude-remote: not starting a tmux server (socket guard exit 4, see ~/.claude-remote/sockheal.log)" }
        val (o, connects) = orchestrator(exec)
        val e = assertFailsWith<java.io.IOException> { launch(o) }
        assertTrue("Could not start a new session on box" in (e.message ?: "") && "socket guard exit 4" in (e.message ?: ""), "readable, with the server's reason: ${e.message}")
        assertTrue(tabs.tabs.value.isEmpty() && connects.isEmpty() && sessions.load().isEmpty(), "no tab, no connect, nothing stored")
        assertEquals(1, exec.creates.size, "a refusal is not retried under other names")
    }

    @Test fun noChannelToTheServerAtAllEndsTheLaunchWithAReadableErrorAndNoTab() {
        // exec routing off, no tab of the server: the pooled transport the tab would lease cannot be reached either
        val (o, connects) = orchestrator(exec = null)
        val e = assertFailsWith<java.io.IOException> { launch(o) }
        assertTrue("Could not start a new session on box" in (e.message ?: ""), "readable: ${e.message}")
        assertTrue(tabs.tabs.value.isEmpty() && connects.isEmpty() && sessions.load().isEmpty(), "no tab on a name nobody checked")
    }

    @Test fun everyTransportCreatesFirstAndThenAttachesTheCreatedSession() {
        for ((type, eternal, path) in listOf(Triple(ConnectionType.SSH, false, "ssh"), Triple(ConnectionType.MOSH, false, "mosh"), Triple(ConnectionType.SSH, true, "et"))) {
            tabs.tabs.value.forEach { tabs.removeTab(it.id) }
            orchestrator?.shutdownForTest()
            server = server.copy(preferEternal = eternal)
            val exec = FakeTmux()
            exec.sessions[base] = Tmux(null, 1L, 777)
            val (o, connects) = orchestrator(exec)
            val s = launch(o, type = type)
            assertEquals("$base-2", s.tmuxSessionName, path)
            assertEquals(listOf(Connect(path, "$base-2", isNew = false, checkClosedElsewhere = false, tabExisted = true)), connects.toList(),
                "$path: the created session is attached, the taken one is not launched over")
            assertTrue(exec.creates.isNotEmpty() && exec.touching(base).isEmpty(), "$path: ${exec.touching(base)}")
        }
    }

    @Test fun aMoshLaunchWithTheExecTransportDownCreatesOverTheExecTransportItStartsAndLetsItGoWhenItFails() {
        val exec = FakeTmux().apply { up = false }
        val (o, connects) = orchestrator(exec)
        val ensured = CopyOnWriteArrayList<String>()
        val closed = CopyOnWriteArrayList<String>()
        o.execEnsureHook = { ensured += it; exec.up = true }   // the exec transport comes up once it is started
        o.execCloseHook = { closed += it }
        val s = launch(o, type = ConnectionType.MOSH)
        assertEquals(base, s.tmuxSessionName, "created over the exec transport (no pooled transport: that one cannot be reached here)")
        assertTrue(ensured.isNotEmpty() && connects.single().tabExisted)
        assertTrue(closed.isEmpty(), "the tab exists: its exec transport stays")

        // a mosh launch that fails while no tab of the server exists: the exec transport it started goes again
        tabs.tabs.value.forEach { runBlocking { o.disconnectSession(it.id) } }
        closed.clear()
        exec.up = false
        exec.guardRefusal = "claude-remote: not starting a tmux server (socket guard exit 4)"
        assertFailsWith<java.io.IOException> { launch(o, type = ConnectionType.MOSH) }
        assertTrue(tabs.tabs.value.isEmpty())
        assertEquals(listOf(server.id), closed.toList(), "nothing keeps the exec transport once the launch is through without a tab")
    }

    @Test fun aReconnectAttachesTheTabsSessionAndMayReadItAsClosedElsewhere() {
        val (o, connects) = orchestrator(FakeTmux())
        val tab = ClaudeSession(id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = base, connectionType = ConnectionType.SSH, claudeSessionId = "0f6c2a4e-1b2c-4d5e-8f90-123456789abc")
        tabs.addTab(tab)
        runBlocking { o.reconnectSession(tab.id) }
        assertEquals(listOf(Connect("ssh", base, isNew = false, checkClosedElsewhere = true, tabExisted = true)), connects.toList())
    }

    // ---- round 7 ----

    @Test fun aMoshTabReconnectsByAttachingItsSession() {   // T6-1
        val (o, connects) = orchestrator(FakeTmux())
        val tab = ClaudeSession(id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = base, connectionType = ConnectionType.MOSH, claudeSessionId = "0f6c2a4e-1b2c-4d5e-8f90-123456789abc")
        tabs.addTab(tab)
        runBlocking { o.reconnectSession(tab.id) }
        assertEquals(listOf(Connect("mosh", base, isNew = false, checkClosedElsewhere = false, tabExisted = true)), connects.toList(),
            "the mosh tab's own session is attached (`attach || relaunch`), never launched over with the anchor's refusal")
    }

    @Test fun anAutoReconnectAttachesTheTabsSessionAndMayReadItAsClosedElsewhere() {   // T6-1
        val (o, _) = orchestrator(FakeTmux())
        val tab = ClaudeSession(id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = base, connectionType = ConnectionType.SSH, claudeSessionId = "0f6c2a4e-1b2c-4d5e-8f90-123456789abc")
        tabs.addTab(tab)
        val asked = CopyOnWriteArrayList<Triple<String, Boolean, Boolean>>()
        o.reconnectShellSeam = { BudgetFixture.connectedManager(storage, com.jcraft.jsch.FakeSession()) }
        o.tmuxCommandSeam = { s, isNew, closed -> asked += Triple(s.tmuxSessionName, isNew, closed); true }
        runBlocking { withTimeout(30_000) { o.autoReconnect(tab, {}) } }
        assertEquals(listOf(Triple(base, false, true)), asked.toList(),
            "after a dropped connection the tab's own session is attached (a missing one may read as closed elsewhere), never launched fresh")
    }

    @Test fun aSecondLaunchOfTheNameWhileTheFirstCreatesItJoinsItOneSessionOneTab() {   // R6-1
        val exec = FakeTmux().apply { gate = CompletableDeferred() }
        val (o, connects) = orchestrator(exec)
        runBlocking {
            val first = async(Dispatchers.IO) {
                withTimeout(40_000) { o.launchSession(server, "/work", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, ConnectionType.SSH, base, isNewTmuxSession = true) }
            }
            assertTrue(waitFor(10_000) { exec.ran.any { "new-session -d" in it } }, "the first launch is creating its session")
            val second = async(Dispatchers.IO) {
                withTimeout(40_000) { o.launchSession(server, "/work", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, ConnectionType.SSH, base, isNewTmuxSession = true) }
            }
            Thread.sleep(500)
            exec.gate!!.complete(Unit)
            val a = first.await()
            val b = second.await()
            assertEquals(a.id, b.id, "the second launch is the first one's tab")
        }
        assertEquals(setOf(base), exec.sessions.keys, "one session on the server")
        assertEquals(1, exec.creates.size, "one creation: ${exec.creates}")
        assertEquals(listOf(base), tabs.tabs.value.map { it.tmuxSessionName }, "one tab")
        assertEquals(1, connects.size, "one connect: $connects")
    }

    @Test fun aSecondQuickConnectDuringACreationAsksForTheNameBeingCreated() {   // R6-1
        val exec = FakeTmux().apply { gate = CompletableDeferred() }
        val (o, _) = orchestrator(exec)
        runBlocking {
            val first = async(Dispatchers.IO) { runCatching { launch(o) } }
            assertTrue(waitFor(10_000) { exec.ran.any { "new-session -d" in it } }, "the first launch is creating its session")
            // the listing already shows the session being created; this device's own names include it
            val quickName = com.clauderemote.ui.pickQuickConnectName(server.copy(defaultFolder = "/work"), o.ownTmuxNames(server.id), emptyList()) {
                com.clauderemote.connection.TmuxManager.FreshNameListing(listOf(com.clauderemote.model.TmuxSession(base, 1, false, "0")), emptySet())
            }
            exec.gate!!.complete(Unit)
            first.await()
            assertEquals(base, quickName, "the second quick connect asks for the name the first is creating (and joins it), not the next one")
        }
    }

    @Test fun aSecondLaunchJoiningOneThatFailsEndsWithoutASessionOfItsOwn() {   // R6-1
        val exec = FakeTmux().apply {
            gate = CompletableDeferred()
            guardRefusal = "claude-remote: not starting a tmux server (socket guard exit 4)"
        }
        val (o, _) = orchestrator(exec)
        runBlocking {
            val first = async(Dispatchers.IO) { runCatching { launch(o) } }
            assertTrue(waitFor(10_000) { exec.ran.any { "new-session -d" in it } })
            val second = async(Dispatchers.IO) { runCatching { launch(o) } }
            Thread.sleep(500)
            exec.gate!!.complete(Unit)
            assertTrue(first.await().isFailure && second.await().isFailure, "both end with the first one's error")
        }
        assertEquals(1, exec.creates.size, "the second launch created nothing of its own: ${exec.creates}")
        assertTrue(tabs.tabs.value.isEmpty())
    }

    @Test fun everyAnswerLostRemovesTheSessionTheLaunchCreated() {   // R6-2
        val exec = FakeTmux().apply { loseAnswers = FRESH_LOST }
        val (o, _) = orchestrator(exec)
        val e = assertFailsWith<java.io.IOException> { launch(o) }
        assertTrue("no answer" in (e.message ?: ""), "${e.message}")
        assertTrue(exec.sessions.isEmpty(), "the session the first try created is gone again: ${exec.sessions.keys}")
        assertEquals(listOf(base to exec.creates.first().second), exec.cleanups.toList(), "removed by its nonce")
        assertTrue(tabs.tabs.value.isEmpty() && sessions.load().isEmpty())
    }

    @Test fun theCleanupNeverRemovesASessionOfAnotherLaunch() {   // R6-2: only by the nonce
        val exec = FakeTmux().apply { loseAnswers = FRESH_LOST }
        val foreign = Tmux(owner = "SomeoneElse0", created = 1L, panePid = 777)
        exec.sessions[base] = foreign
        val (o, _) = orchestrator(exec)
        assertFailsWith<java.io.IOException> { launch(o) }   // every answer (duplicate, another owner) lost
        assertEquals(foreign, exec.sessions[base], "the other launch's session is untouched")
        assertTrue(exec.cleanups.all { it.second != "SomeoneElse0" } && exec.cleanups.isNotEmpty(), "${exec.cleanups}")
    }

    @Test fun aLaunchCancelledAfterTheCreationRemovesTheSessionItCreated() {   // R6-3
        val exec = FakeTmux()
        val (o, _) = orchestrator(exec)
        val created = CompletableDeferred<Unit>()
        val slow = object : RemoteExec by exec {
            override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult {
                val r = exec.run(serverId, cmd, stdin, totalMs, kind, paceStdin)
                if ("new-session -d" in cmd) { created.complete(Unit); kotlinx.coroutines.delay(5_000) }   // the answer is on its way
                return r
            }
        }
        o.connectionRegistry.remoteExec = slow
        runBlocking {
            val j = async(Dispatchers.IO) { o.launchSession(server, "/work", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, ConnectionType.SSH, base, isNewTmuxSession = true) }
            withTimeout(10_000) { created.await() }
            assertEquals(setOf(base), exec.sessions.keys, "the server created it")
            j.cancel()
            runCatching { withTimeout(15_000) { j.await() } }
        }
        assertTrue(exec.sessions.isEmpty(), "the cancelled launch's session is gone again: ${exec.sessions.keys}")
        assertTrue(tabs.tabs.value.isEmpty() && sessions.load().isEmpty())
    }

    private companion object {
        const val FRESH_LOST = 3   // SessionOrchestrator.FRESH_CREATE_LOST_TRIES
    }
}
