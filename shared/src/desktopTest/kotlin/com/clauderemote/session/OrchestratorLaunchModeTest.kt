package com.clauderemote.session

import com.clauderemote.connection.SshManager
import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecResult
import com.clauderemote.session.service.Pace
import com.clauderemote.session.service.RemoteExec
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.JSchException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * V14 wiring: which anchor mode (`ClaudeConfig.OnExisting`) each launch path of the orchestrator sends. A fresh launch
 * refuses a taken name (never kills, never adopts); every relaunch of a session that should exist attaches it with
 * this device's single-client attach (`buildAttachCommand`, S2) and never kills it (N1).
 *
 * The SSH paths are checked on the command the tab's shell actually receives: the private `sendTmuxCommand` runs
 * against a tab manager whose output stream is captured, with the probes answered by a scripted exec transport (no
 * tmux, no network). The decision is the real `TmuxLaunchDecider`'s.
 *
 * The mosh and ET paths build their command inline and hand it to a client process (`mosh`, the bundled `et`), so
 * they are checked in the source, like `theMoshAndEtConnectPathsAdoptTheirClientAndNothingElsePutsOne`. So is the
 * third Rebuild branch (`resume == false`): the orchestrator's transcript probe answers only YES or unknown, so the
 * decider never hands it `resume = false` and no sent command can reach it.
 */
class OrchestratorLaunchModeTest {
    private val savedLog = FileLogger.platformLog
    private val logLines = CopyOnWriteArrayList<String>()
    private val tabs = TabManager()
    private val storage = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private val tmux = "claude-box-work"
    private val uuid = "0f6c2a4e-1b2c-4d5e-8f90-123456789abc"
    private var orchestrator: SessionOrchestrator? = null

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
        storage.addServer(server)
    }

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        FileLogger.platformLog = savedLog
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.liveSessionProvider = null
        SshSessionHelper.fallbackRunner = null
        CommFlags.resetForTest()
    }

    /** The exec transport answers the two probes `sendTmuxCommand` may ask: tmux `has-session` and the transcript. */
    private class ProbeExec(private val tmuxAlive: Boolean, private val transcript: Boolean) : RemoteExec {
        val asked = CopyOnWriteArrayList<String>()
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult {
            asked += cmd
            val yes = if ("has-session" in cmd) tmuxAlive else transcript
            return ExecResult(if (yes) "YES\n" else "NO\n", 0)
        }
    }

    private fun tab(claudeSessionId: String?) = ClaudeSession(
        id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = tmux, connectionType = ConnectionType.SSH, claudeSessionId = claudeSessionId,
        accountSlug = "acct",
    )

    /**
     * Runs `sendTmuxCommand(isNew)` for [session] with tmux alive or not and the transcript there or not, and returns
     * the line the tab's shell received (without its newline).
     */
    private fun sent(session: ClaudeSession, isNew: Boolean, tmuxAlive: Boolean = false, transcript: Boolean = false): Pair<SessionOrchestrator, String> {
        val o = SessionOrchestrator(storage, tabs).also { orchestrator = it }
        val exec = ProbeExec(tmuxAlive, transcript)
        o.connectionRegistry.remoteExec = exec
        assertTrue(o.connectionRegistry.execActive(server.id), "fixture: exec routing on (the probes go to the scripted exec)")
        tabs.addTab(session)
        val fake = FakeSession { _ -> throw JSchException("no channel in this test") }
        val manager = BudgetFixture.connectedManager(storage, fake)
        val out = ByteArrayOutputStream()
        SshManager::class.java.getDeclaredField("outputStream").also { it.isAccessible = true }.set(manager, out)
        o.connectionRegistry.putSsh(session.id, manager)
        val m = SessionOrchestrator::class.java.declaredMethods.single { it.name == "sendTmuxCommand" && it.parameterCount == 6 }
        m.isAccessible = true
        runBlocking {
            withTimeout(5_000) {
                suspendCoroutineUninterceptedOrReturn<Any?> { c ->
                    try {
                        m.invoke(o, manager, session, isNew, false, null, c)
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        throw e.targetException
                    }
                }
            }
        }
        val end = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < end && !synchronized(out) { out.toString(Charsets.UTF_8) }.endsWith("\n")) Thread.sleep(10)
        val text = synchronized(out) { out.toString(Charsets.UTF_8) }
        assertTrue(text.endsWith("\n"), "the tab's shell received one command line: '$text'")
        if (!isNew) assertTrue(exec.asked.any { "has-session" in it }, "fixture: the decider probed tmux over the scripted exec")
        return o to text.removeSuffix("\n")
    }

    private fun launch(session: ClaudeSession, resume: Boolean?, onExisting: ClaudeConfig.OnExisting) =
        ClaudeConfig.buildTmuxLaunchCommand(
            tmuxSessionName = session.tmuxSessionName, folder = session.folder, mode = session.mode, model = session.model,
            claudeSessionId = session.claudeSessionId, resume = resume, accountSlug = session.accountSlug, onExisting = onExisting,
        )

    /** The same launch in the three anchor modes: the exact one sent must be [want], and the three must differ (else the check proves nothing). */
    private fun assertMode(what: String, sent: String, o: SessionOrchestrator, session: ClaudeSession, resume: Boolean?, want: String) {
        val refuse = launch(session, resume, ClaudeConfig.OnExisting.Refuse)
        val attachSingleClient = launch(session, resume, ClaudeConfig.OnExisting.Attach(o.buildAttachCommand(session.tmuxSessionName)))
        val attachPlain = launch(session, resume, ClaudeConfig.OnExisting.Attach())
        assertEquals(3, setOf(refuse, attachSingleClient, attachPlain).size, "fixture: the three modes give three commands")
        val expected = when (want) {
            "refuse" -> refuse
            "attach" -> attachSingleClient
            else -> error(want)
        }
        val got = when (sent) {
            refuse -> "refuse"
            attachSingleClient -> "attach with the single-client preamble"
            attachPlain -> "plain attach (no single-client preamble)"
            else -> "another command"
        }
        assertEquals(expected, sent, "$what must send the anchor in mode '$want', it sent: $got")
    }

    // ---- SSH: sendTmuxCommand, on the sent command ----

    @Test fun aFreshLaunchRefusesATakenNameAndNeverKillsIt() {
        val session = tab(claudeSessionId = uuid)
        val (o, cmd) = sent(session, isNew = true)
        assertMode("FreshLaunch", cmd, o, session, resume = false, want = "refuse")
        assertTrue("already exists" in cmd, "the refusal is readable")
        assertTrue("kill-session" !in cmd, "a fresh launch never kills: $cmd")
        assertTrue("attach-session" !in cmd, "a fresh launch never attaches: $cmd")
    }

    @Test fun aRebuildWithoutASessionIdAttachesAnExistingSessionWithThisDevicesSingleClientAttach() {
        val session = tab(claudeSessionId = null)
        val (o, cmd) = sent(session, isNew = false, tmuxAlive = false)
        assertMode("Rebuild without a session id", cmd, o, session, resume = false, want = "attach")
        assertTrue(o.buildAttachCommand(tmux) in cmd, "the attach branch runs this device's buildAttachCommand")
    }

    @Test fun aRebuildWithResumeAttachesAnExistingSessionWithThisDevicesSingleClientAttach() {
        val session = tab(claudeSessionId = uuid)
        val (o, cmd) = sent(session, isNew = false, tmuxAlive = false, transcript = true)
        assertTrue("--resume" in cmd, "fixture: the resume branch (transcript there)")
        assertMode("Rebuild with --resume", cmd, o, session, resume = true, want = "attach")
    }

    @Test fun aRebuildThatLeavesResumeToTheServerAttachesAnExistingSessionWithThisDevicesSingleClientAttach() {
        val session = tab(claudeSessionId = uuid)
        val (o, cmd) = sent(session, isNew = false, tmuxAlive = false, transcript = false)
        assertMode("Rebuild with the server-side transcript check", cmd, o, session, resume = null, want = "attach")
    }

    @Test fun anAttachOfALiveSessionIsThePlainSingleClientAttach() {
        val session = tab(claudeSessionId = uuid)
        val (o, cmd) = sent(session, isNew = false, tmuxAlive = true)
        assertEquals(o.buildAttachCommand(tmux), cmd, "a live session is attached, no anchor")
        assertNotEquals(0, logLines.count { "Attaching to tmux" in it }, "control: the attach is logged")
    }

    // ---- In the source: mosh, ET, and the Rebuild branch no sent command reaches ----

    private val src by lazy { java.io.File("src/commonMain/kotlin/com/clauderemote/session/SessionOrchestrator.kt").readText() }

    private fun body(name: String): String {
        val decl = Regex("""\n    (?:(?:private|internal|public|override|protected) )*(?:suspend )?fun """)
        val start = src.indexOf("fun $name(").also { assertTrue(it >= 0, "fun $name not found") }
        val end = decl.find(src, start + 1)?.range?.first ?: src.length
        return src.substring(start, end)
    }

    private val refuse = "onExisting = ClaudeConfig.OnExisting.Refuse,"
    private val attach = "onExisting = ClaudeConfig.OnExisting.Attach(buildAttachCommand(session.tmuxSessionName)),"

    /** The `if (isNewTmuxSession) { … } else { … }` that builds [variable] in [fn]: (new branch, relaunch branch). */
    private fun branches(fn: String, variable: String): Pair<String, String> {
        val b = body(fn)
        val start = b.indexOf("val $variable = if (isNewTmuxSession) {").also { assertTrue(it >= 0, "$fn: `val $variable = if (isNewTmuxSession)` not found") }
        val split = b.indexOf("} else {", start).also { assertTrue(it > start, "$fn: no else branch") }
        val end = b.indexOf("\"\${buildAttachCommand(session.tmuxSessionName)} 2>/dev/null || { \$relaunch ; }\"", split)
            .also { assertTrue(it > split, "$fn: the relaunch is `attach || { relaunch ; }`") }
        return b.substring(start, split) to b.substring(split, end)
    }

    private fun assertOnly(what: String, text: String, want: String) {
        val modes = Regex("""onExisting = [^\n]*""").findAll(text).map { it.value.trim() }.toList()
        assertEquals(listOf(want), modes, "$what sends exactly one anchor mode")
    }

    @Test fun moshSendsRefuseForANewSessionAndTheSingleClientAttachForTheRelaunch() {
        val (new, relaunch) = branches("connectMosh", "tmuxCmd")
        assertOnly("connectMosh, isNewTmuxSession", new, refuse)
        assertOnly("connectMosh, attach || relaunch", relaunch, attach)
    }

    @Test fun etSendsRefuseForANewSessionAndTheSingleClientAttachForTheRelaunch() {
        val (new, relaunch) = branches("connectEt", "attachCmd")
        assertOnly("connectEt, isNewTmuxSession", new, refuse)
        assertOnly("connectEt, attach || relaunch", relaunch, attach)
    }

    @Test fun everyRebuildBranchOfSendTmuxCommandAttachesAndOnlyTheFreshLaunchRefuses() {
        val b = body("sendTmuxCommand")
        val fresh = b.substring(b.indexOf("is TmuxLaunchDecision.FreshLaunch ->"), b.indexOf("is TmuxLaunchDecision.Attach ->"))
        assertOnly("sendTmuxCommand, FreshLaunch", fresh, refuse)
        val rebuild = b.substring(b.indexOf("is TmuxLaunchDecision.Rebuild ->"))
        val modes = Regex("""onExisting = [^\n]*""").findAll(rebuild).map { it.value.trim() }.toList()
        assertEquals(listOf(attach, attach, attach), modes, "sendTmuxCommand, Rebuild: each of the three branches attaches with buildAttachCommand")
    }

    /**
     * Quick connect picks its free name with a listing that never pays a handshake of its own: exec transport or a
     * counted channel of a transport the server has (`allowFresh = false`, see
     * `ExecNoFreshConnectionTest.withSessionAllowFreshFalseThrowsNotUpAndConnectsNothing`), else the launcher's last
     * scan. Both quick-connect entries use it.
     */
    @Test fun quickConnectNamesItsSessionWithoutAHandshakeOfItsOwn() {
        val app = java.io.File("src/commonMain/kotlin/com/clauderemote/ui/App.kt").readText()
        val start = app.indexOf("suspend fun quickConnectName(").also { assertTrue(it >= 0, "quickConnectName not found") }
        val fn = app.substring(start, app.indexOf("\n\n", start))
        assertTrue("SshSessionHelper.withSession(server, allowFresh = false)" in fn, "the listing must not open a fresh connection: $fn")
        // round 5 (V15): the last scan is part of the taken names always, not only without a transport — the fallback
        // itself is pickQuickConnectName's (FreshNamePickTest)
        assertTrue("lastScan = remoteSessions.filter { it.server.id == server.id }" in fn,
            "the launcher's last scan of this server names taken sessions: $fn")
        assertEquals(2, Regex("""tmuxSessionName = quickConnectName\((server|srv)\),\s*isNewTmuxSession = true""").findAll(app).count(),
            "both quick-connect entries launch a new session under quickConnectName")
    }
}
