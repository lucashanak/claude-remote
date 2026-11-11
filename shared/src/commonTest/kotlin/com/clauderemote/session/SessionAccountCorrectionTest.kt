package com.clauderemote.session

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionStatus
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecResult
import com.clauderemote.session.service.ExecSessionProvider
import com.clauderemote.session.service.Pace
import com.clauderemote.session.service.RemoteExec
import com.clauderemote.session.service.SessionAccountReading
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The account correction of [SessionOrchestrator.resolveSessionAccount] (2026-11-11, "Not connected — not sent"): the
 * app attached to a session running under another account, the probe suspended, the launch set the tab ACTIVE
 * meanwhile, and the correction wrote back the whole row read before the probe — CONNECTING. Nothing set ACTIVE again,
 * so every key was dropped until an app restart. The correction now writes only the account field of the current row,
 * only while it still holds the value read before the probe, persists the row it wrote and pushes the manifest.
 *
 * The probe is stood in for by [SessionOrchestrator.sessionAccountSeam], held on a gate so the test changes the tab
 * while it is in flight. The exec routing is a recording [RemoteExec], so the manifest push is seen with its payload.
 */
class SessionAccountCorrectionTest {
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedLog = FileLogger.platformLog
    private val tabs = TabManager()
    private val storage = ServerStorage(FakeKeyValueStore())
    private val sessions = SessionStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private var orchestrator: SessionOrchestrator? = null

    /** The probe has started (the row it was started against is read). */
    private val probeStarted = CompletableDeferred<Unit>()
    /** Completing it lets the probe answer. */
    private val gate = CompletableDeferred<Unit>()

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
        storage.addServer(server)
    }

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        FileLogger.platformLog = savedLog
        com.clauderemote.connection.SshSessionHelper.execChannelsProvider = null
        com.clauderemote.connection.SshSessionHelper.liveSessionProvider = null
        com.clauderemote.connection.SshSessionHelper.fallbackRunner = null
        CommFlags.resetForTest()
    }

    /** Every exec answers empty and is recorded: (command, stdin). */
    private class RecordingExec : RemoteExec {
        val runs = CopyOnWriteArrayList<Pair<String, String>>()
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult {
            runs += cmd to (stdin?.toString(Charsets.UTF_8) ?: "")
            return ExecResult("", 0)
        }
    }

    /** Exec routing "on": the registry only asks whether a provider is wired; nothing here opens a transport. */
    private object NoTransport : ExecSessionProvider {
        override fun session(serverId: String): com.jcraft.jsch.Session? = null
        override suspend fun await(serverId: String, ms: Long): com.jcraft.jsch.Session? = null
    }

    private val exec = RecordingExec()

    private fun orchestrator(probe: suspend () -> SessionAccountReading = { SessionAccountReading.Running("acct") }): SessionOrchestrator {
        val o = SessionOrchestrator(storage, tabs, sessions).also { orchestrator = it }
        o.connectionRegistry.execSessions = NoTransport
        o.connectionRegistry.remoteExec = exec
        o.sessionAccountSeam = { _, _ ->
            probeStarted.complete(Unit)
            gate.await()
            probe()
        }
        return o
    }

    private fun tab(id: String, accountSlug: String? = null, status: SessionStatus = SessionStatus.CONNECTING) = ClaudeSession(
        id = id, server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-box-$id", connectionType = ConnectionType.SSH, status = status,
        claudeSessionId = "0f6c2a4e-1b2c-4d5e-8f90-12345678${id.padStart(4, '0')}", accountSlug = accountSlug,
    ).also { tabs.addTab(it); sessions.upsert(SessionStorage.fromClaudeSession(it)) }

    private fun bounded(ms: Long = 30_000, body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(ms) { body() } }

    private fun eventually(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(20) }
        assertTrue(cond(), "timed out waiting for $what; log: ${logLines.takeLast(10)}")
    }

    private fun stored(id: String) = sessions.load().singleOrNull { it.id == id }

    private val pushedAcct = Regex(""""accountSlug":\s*"acct"""")

    /** T1 (D18): the tab went ACTIVE and got an alias during the probe — both stay, the account is corrected, persisted and pushed. */
    @Test fun aCorrectionDuringTheLaunchKeepsTheTabActiveAndItsAliasAndPersistsAndPushesTheAccount() {
        val o = orchestrator()
        tab("t1")
        bounded {
            val r = async(Dispatchers.Default) { o.resolveSessionAccount("t1") }
            probeStarted.await()
            tabs.updateTabStatus("t1", SessionStatus.ACTIVE)          // the launch's "Session active"
            tabs.updateAlias("t1", "kept-alias")
            gate.complete(Unit)
            assertEquals("acct", r.await())
        }
        val now = tabs.getTab("t1")!!
        assertEquals(SessionStatus.ACTIVE, now.status, "the live tab is not put back to CONNECTING (input would have no route)")
        assertEquals("acct", now.accountSlug)
        assertEquals("kept-alias", now.alias)
        assertEquals("acct", stored("t1")?.accountSlug, "the record carries the corrected account")
        assertEquals("kept-alias", stored("t1")?.alias, "and is the current row, not the one read before the probe")
        assertTrue(logLines.any { "stored was default — correcting" in it })
        eventually("the manifest is pushed with the corrected account") {
            exec.runs.any { (cmd, stdin) -> "sessions.json" in cmd && pushedAcct.containsMatchIn(stdin) }
        }
    }

    /** T2: the tab was closed during the probe — the correction neither brings it back nor writes its record again. */
    @Test fun aTabClosedDuringTheProbeIsNotBroughtBack() {
        val o = orchestrator()
        tab("t1")
        bounded {
            val r = async(Dispatchers.Default) { o.resolveSessionAccount("t1") }
            probeStarted.await()
            tabs.removeTab("t1")
            sessions.remove("t1")
            gate.complete(Unit)
            r.await()
        }
        assertNull(tabs.getTab("t1"), "the closed tab stays closed")
        assertNull(stored("t1"), "and its record stays removed")
        assertTrue(logLines.none { "— correcting" in it })
    }

    /**
     * T3: another tab is active — it stays active, also when the user switches tabs during the probe, and the corrected
     * tab is never made active on the way (a silent active-tab change desyncs the terminal from the input route).
     */
    @Test fun theCorrectionNeverChangesTheActiveTab() {
        val o = orchestrator()
        tab("t1")
        tab("t2")
        tab("t3")
        tabs.switchTab("t2")
        val seen = CopyOnWriteArrayList<String?>()
        val watcher = CoroutineScope(Dispatchers.Unconfined)
        try {
            watcher.launch { tabs.activeTabId.collect { seen += it } }
            bounded {
                val r = async(Dispatchers.Default) { o.resolveSessionAccount("t1") }
                probeStarted.await()
                tabs.switchTab("t3")                                  // the user moves on meanwhile
                gate.complete(Unit)
                assertEquals("acct", r.await())
            }
        } finally {
            watcher.cancel()
        }
        assertEquals("t3", tabs.activeTabId.value, "the user's tab stays active")
        assertEquals("acct", tabs.getTab("t1")!!.accountSlug)
        assertFalse("t1" in seen, "the corrected background tab was never made active: $seen")
        assertEquals(listOf("t1", "t2", "t3"), tabs.tabs.value.map { it.id }, "nor moved")
    }

    /** T4: the user switched the account during the probe — the probe of the old process does not undo that choice. */
    @Test fun anAccountSwitchDuringTheProbeIsNotUndone() {
        val o = orchestrator()
        tab("t1", status = SessionStatus.ACTIVE)
        bounded {
            val r = async(Dispatchers.Default) { o.resolveSessionAccount("t1") }
            probeStarted.await()
            o.switchSessionAccount("t1", "chosen")
            gate.complete(Unit)
            assertEquals("chosen", r.await(), "the answer is the tab's account now")
        }
        assertEquals("chosen", tabs.getTab("t1")!!.accountSlug, "the user's choice stays")
        assertEquals("chosen", stored("t1")?.accountSlug)
        assertEquals(SessionStatus.ACTIVE, tabs.getTab("t1")!!.status)
        assertTrue(logLines.none { "— correcting" in it }, "nothing was corrected: ${logLines.filter { "account" in it }}")
        assertTrue(logLines.any { "correction skipped" in it && "changed to chosen" in it })
    }

    /** T5: the correction lands during the launch's connect — the launch's own record does not put the old account back. */
    @Test fun theLaunchPersistsTheCorrectedAccountWhenTheCorrectionLandedDuringItsConnect() {
        gate.complete(Unit)
        val o = orchestrator()
        o.connectionRegistry.execSessions = null                     // the legacy path: the launch needs no exec transport
        o.connectionRegistry.remoteExec = null
        o.tmuxCommandSeam = { _, _, _ -> true }
        o.connectSeam = { _, s, _, _ -> runBlocking { o.resolveSessionAccount(s.id) }; true }
        bounded {
            o.launchSession(server, "/work", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, ConnectionType.SSH, "claude-box-l5", isNewTmuxSession = false)
        }
        val t = tabs.tabs.value.single { it.tmuxSessionName == "claude-box-l5" }
        assertEquals("acct", t.accountSlug)
        assertEquals(SessionStatus.ACTIVE, t.status)
        assertEquals("acct", sessions.load().single { it.tmuxSessionName == "claude-box-l5" }.accountSlug,
            "the launch persisted the tab as it is now, not its snapshot from before the connect")
    }

    /** An Unknown probe (timeout, no claude) keeps the stored account and writes nothing. */
    @Test fun anUnknownProbeKeepsTheStoredAccountAndWritesNothing() {
        gate.complete(Unit)
        val o = orchestrator(probe = { SessionAccountReading.Unknown })
        tab("t1", accountSlug = "acct", status = SessionStatus.ACTIVE)
        bounded { assertEquals("acct", o.resolveSessionAccount("t1")) }
        assertEquals("acct", tabs.getTab("t1")!!.accountSlug)
        assertEquals("acct", stored("t1")?.accountSlug)
        assertTrue(logLines.none { "— correcting" in it })
    }

    /** Regression: switching a background session's account changes that field only, keeps the active tab, persists and pushes. */
    @Test fun switchingABackgroundSessionsAccountKeepsTheActiveTabAndTheRow() {
        gate.complete(Unit)
        val o = orchestrator()
        tab("t1", status = SessionStatus.ACTIVE)
        tabs.updateAlias("t1", "kept-alias")
        tab("t2", status = SessionStatus.ACTIVE)
        val seen = CopyOnWriteArrayList<String?>()
        val watcher = CoroutineScope(Dispatchers.Unconfined)
        try {
            watcher.launch { tabs.activeTabId.collect { seen += it } }
            o.switchSessionAccount("t1", "acct")
        } finally {
            watcher.cancel()
        }
        assertEquals("t2", tabs.activeTabId.value)
        assertFalse("t1" in seen, "the background tab was never made active: $seen")
        val t1 = tabs.getTab("t1")!!
        assertEquals("acct", t1.accountSlug)
        assertEquals(SessionStatus.ACTIVE, t1.status)
        assertEquals("kept-alias", t1.alias)
        assertEquals("acct", stored("t1")?.accountSlug)
        assertTrue(logLines.any { "Switching t1 to account acct" in it })
        eventually("the manifest is pushed with the new account") {
            exec.runs.any { (cmd, stdin) -> "sessions.json" in cmd && pushedAcct.containsMatchIn(stdin) }
        }
    }

    /** Regression: switching to the account the tab already has, or "default" on the default one, does nothing. */
    @Test fun switchingToTheSameAccountDoesNothing() {
        val o = orchestrator()
        tab("t1", status = SessionStatus.ACTIVE)
        o.switchSessionAccount("t1", "default")
        o.switchSessionAccount("t1", null)
        o.switchSessionAccount("missing", "acct")
        assertNull(tabs.getTab("t1")!!.accountSlug)
        assertTrue(logLines.none { "Switching" in it })
    }

    // ---- kolo 2 ---------------------------------------------------------------------------------------------------

    private fun pushes() = exec.runs.filter { "sessions.json" in it.first }.map { it.second }

    /**
     * R1.1 (adv A1): the switch has not reached the process yet (no live shell for its respawn), the tab is activated
     * again and the probe — started AFTER the switch — reads the old process. The user's choice stays in memory, in
     * the record and in every manifest pushed.
     */
    @Test fun aProbeOfTheOldProcessAfterASwitchThatHasNotTakenEffectDoesNotUndoIt() {
        gate.complete(Unit)
        val o = orchestrator(probe = { SessionAccountReading.Running(null) })    // the old process: default login
        tab("t1", status = SessionStatus.ACTIVE)
        o.switchSessionAccount("t1", "chosen")
        eventually("the switch pushed its manifest") { pushes().isNotEmpty() }
        bounded { assertEquals("chosen", o.resolveSessionAccount("t1"), "the answer is the user's choice") }
        Thread.sleep(500)                                                       // a push after the probe would be in by now
        assertEquals("chosen", tabs.getTab("t1")!!.accountSlug, "the choice stays in memory")
        assertEquals("chosen", stored("t1")?.accountSlug, "and in the record")
        val chosen = Regex(""""accountSlug":\s*"chosen"""")
        assertTrue(pushes().all { chosen.containsMatchIn(it) }, "no manifest without the choice was pushed: ${pushes()}")
        assertTrue(logLines.any { "switch to chosen pending — not corrected" in it })
        assertTrue(logLines.none { "— correcting" in it })
    }

    /** R1.1: the pending switch ends when a probe reads its target — a later disagreement is corrected again. */
    @Test fun aSwitchThatTookEffectNoLongerBlocksTheCorrection() {
        gate.complete(Unit)
        val reading = java.util.concurrent.atomic.AtomicReference<SessionAccountReading>(SessionAccountReading.Running("chosen"))
        val o = orchestrator(probe = { reading.get() })
        tab("t1", status = SessionStatus.ACTIVE)
        o.switchSessionAccount("t1", "chosen")
        bounded { assertEquals("chosen", o.resolveSessionAccount("t1")) }   // the respawn took effect
        reading.set(SessionAccountReading.Running(null))                    // the process later runs the default login
        bounded { assertEquals(null, o.resolveSessionAccount("t1")) }
        assertNull(tabs.getTab("t1")!!.accountSlug, "corrected: the switch is no longer pending")
        assertNull(stored("t1")?.accountSlug)
    }

    /** R1.1: a second switch replaces the pending one — a probe of the first one's process does not undo the second. */
    @Test fun aSecondSwitchReplacesThePendingOne() {
        gate.complete(Unit)
        val o = orchestrator(probe = { SessionAccountReading.Running("first") })
        tab("t1", status = SessionStatus.ACTIVE)
        o.switchSessionAccount("t1", "first")
        o.switchSessionAccount("t1", "second")
        bounded { assertEquals("second", o.resolveSessionAccount("t1")) }
        assertEquals("second", tabs.getTab("t1")!!.accountSlug)
        assertEquals("second", stored("t1")?.accountSlug)
    }

    /** R1.2: without exec routing the correction pushes nothing — no bare channel on the tab's own transport. */
    @Test fun withoutExecRoutingTheCorrectionOpensNoChannelOnTheTabsTransport() {
        gate.complete(Unit)
        val o = orchestrator()
        o.connectionRegistry.execSessions = null
        o.connectionRegistry.remoteExec = null
        tab("t1", status = SessionStatus.ACTIVE)
        // the tab's shell: a JSch session that is not up — any channel opened on it fails and is logged
        val shell = com.clauderemote.connection.SshManager(storage)
        com.clauderemote.connection.SshManager::class.java.getDeclaredField("session").also { it.isAccessible = true }
            .set(shell, com.jcraft.jsch.JSch().getSession("u", "127.0.0.1", 1))
        o.connectionRegistry.putSsh("t1", shell)
        bounded { assertEquals("acct", o.resolveSessionAccount("t1")) }
        Thread.sleep(800)                                                       // the background push would have run by now
        assertTrue(logLines.none { "sessions.json sync" in it }, "no push over the tab's shell: ${logLines.filter { "sessions.json" in it }}")
        assertEquals("acct", stored("t1")?.accountSlug, "the record has it for the next push")
        assertTrue(logLines.any { "push after the account correction of t1 skipped" in it })
    }

    /** R1.2: a push that finds no transport waits for the exec transport once (whenExecUp), it is not just dropped. */
    @Test fun aCorrectionPushThatFindsNoTransportWaitsForTheExecTransport() {
        gate.complete(Unit)
        val o = orchestrator()
        o.connectionRegistry.remoteExec = object : RemoteExec {
            override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult =
                throw com.clauderemote.session.service.ExecUnavailable(com.clauderemote.session.service.ExecUnavailableReason.NOT_UP)
        }
        tab("t1", status = SessionStatus.ACTIVE)
        bounded { assertEquals("acct", o.resolveSessionAccount("t1")) }
        eventually("the push asked for the exec transport") {
            logLines.any { "sessions.json push after account correction for srv-1 dropped" in it }
        }
    }

    /** R1.3 (adv A4): a launch whose tab was closed during the connect writes no record, clears no tombstone, pushes nothing. */
    @Test fun aLaunchWhoseTabWasClosedDuringTheConnectGetsNoBookkeeping() {
        gate.complete(Unit)
        val o = orchestrator()
        o.tmuxCommandSeam = { _, _, _ -> true }
        o.connectSeam = { _, s, _, _ -> tabs.removeTab(s.id); sessions.remove(s.id); true }
        bounded {
            o.launchSession(server, "/work", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, ConnectionType.SSH, "claude-box-l4", isNewTmuxSession = false)
        }
        Thread.sleep(1_500)                                                     // the background bookkeeping would have run by now
        assertNull(sessions.load().singleOrNull { it.tmuxSessionName == "claude-box-l4" }, "no record written back")
        val clear = com.clauderemote.session.service.ManifestCommands.clearTombstone("claude-box-l4")
        assertTrue(exec.runs.none { it.first == clear }, "the close's tombstone was not cleared")
        assertTrue(pushes().isEmpty(), "and no manifest pushed: ${pushes()}")
        assertTrue(logLines.any { "bookkeeping of claude-box-l4 skipped: its tab was closed" in it })
    }

    /** R1.4 (vt MED-1): a process on the default login corrects a tab that believed in another account to default (null). */
    @Test fun aProcessOnTheDefaultLoginCorrectsTheTabToDefault() {
        gate.complete(Unit)
        val o = orchestrator(probe = { SessionAccountReading.Running(null) })
        tab("t1", accountSlug = "acct", status = SessionStatus.ACTIVE)
        bounded { assertNull(o.resolveSessionAccount("t1")) }
        assertNull(tabs.getTab("t1")!!.accountSlug)
        assertEquals(SessionStatus.ACTIVE, tabs.getTab("t1")!!.status)
        assertNull(stored("t1")?.accountSlug, "the record carries the default account")
        assertTrue(logLines.any { "on account default, stored was acct — correcting" in it })
        eventually("the manifest is pushed with the default account") {
            pushes().any { Regex(""""accountSlug":\s*null""").containsMatchIn(it) }
        }
    }

    // ---- kolo 3 ---------------------------------------------------------------------------------------------------

    /**
     * Kolo 3 (vt LOW-3, M5): a "switch" that changes nothing — to the account the tab already has, or of a tab that
     * does not exist — leaves no pending switch behind: the next probe that disagrees corrects the tab as usual.
     * (Otherwise one tap on the current account would switch the correction of that tab off until an app restart.)
     */
    @Test fun aSwitchThatChangesNothingLeavesNoPendingSwitch() {
        gate.complete(Unit)
        val o = orchestrator(probe = { SessionAccountReading.Running("other") })
        tab("t1", accountSlug = "acct", status = SessionStatus.ACTIVE)
        o.switchSessionAccount("t1", "acct")                                    // the account the tab already has
        bounded { assertEquals("other", o.resolveSessionAccount("t1")) }
        assertEquals("other", tabs.getTab("t1")!!.accountSlug, "corrected: the no-op switch left nothing pending")
        assertEquals("other", stored("t1")?.accountSlug)
        // a tab that does not exist (yet): the only way to see a pending entry left for it is a tab under that id later
        o.switchSessionAccount("t2", "acct")
        tab("t2", accountSlug = "acct", status = SessionStatus.ACTIVE)
        bounded { assertEquals("other", o.resolveSessionAccount("t2")) }
        assertEquals("other", tabs.getTab("t2")!!.accountSlug, "corrected: the switch of a missing tab left nothing pending")
        assertTrue(logLines.none { "pending — not corrected" in it })
    }
}
