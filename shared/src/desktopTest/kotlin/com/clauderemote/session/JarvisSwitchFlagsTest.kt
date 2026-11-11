package com.clauderemote.session

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecChannels
import com.clauderemote.session.service.ExecKind
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F5d (b): Jarvis' account switch relaunches him in mode NORMAL — never `--dangerously-skip-permissions` — whatever
 * mode his tab held; (a): a move to `default` is accountSlug null in the model and in what the manifest push sends.
 * Through the real orchestrator; no live connection, so the restart itself does not go out (and says so).
 */
class JarvisSwitchFlagsTest {
    private val savedExecChannels = SshSessionHelper.execChannelsProvider
    private val servers = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private var orchestrator: SessionOrchestrator? = null

    private object NoExec : ExecChannels {
        override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T =
            throw UnsupportedOperationException()
        override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Session) -> T): T =
            throw UnsupportedOperationException()
    }

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        servers.addServer(server)
    }

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        SshSessionHelper.execChannelsProvider = savedExecChannels
        CommFlags.resetForTest()
    }

    // ── the switch's one shell run, in real bash: temp HOME, a tmux stub that records (no tmux server touched) ──

    private val root = java.nio.file.Files.createTempDirectory("jarvis-switch-run").toFile()
    private val home = java.io.File(root, "home").apply { mkdirs() }
    private val bin = java.io.File(root, "bin").apply { mkdirs() }
    private val calls = java.io.File(root, "calls")
    private val screen = java.io.File(root, "screen")

    init {
        java.io.File(bin, "tmux").apply {
            writeText(
                "#!/bin/bash\n[ \"\$1\" = -u ] && shift\n" +
                    "if [ \"\$1\" = list-panes ]; then printf '%%7\\n%%9\\n'; exit 0; fi\n" +
                    "if [ \"\$1\" = capture-pane ]; then cat '${screen.absolutePath}' 2>/dev/null; exit 0; fi\n" +
                    "printf '%s\\n' \"\$*\" >> '${calls.absolutePath}'\n",
            )
            setExecutable(true)
        }
    }

    @AfterTest fun cleanRoot() = com.clauderemote.testsupport.safeDeleteTree(root)

    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    private fun lastEvent(ev: String) {
        java.io.File(home, ".cache/claude-remote").mkdirs()
        java.io.File(home, ".cache/claude-remote/events").writeText("claude-remote-ev $ev ${b64("claude-server-jarvis")} 1000 42 ${b64("x")}\n")
    }

    private fun emptyBox() {
        val esc = "\u001b"
        val rule = "$esc[38;5;244m" + "─".repeat(40) + "$esc[39m"
        screen.writeText(listOf("● hotovo", rule, "$esc[39m❯\u00a0$esc[2mTry \"fix lint\"$esc[0m", rule).joinToString("\n") + "\n")
    }

    private fun accounts(vararg slots: String) {
        java.io.File(home, ".claude-remote/jarvis").mkdirs()
        java.io.File(home, ".claude-remote/jarvis/accounts").writeText(slots.joinToString("") { "$it\n" })
    }

    private fun login(slot: String) {
        val f = if (slot == "default") java.io.File(home, ".claude.json") else java.io.File(home, ".claude-remote/accounts/$slot/.claude.json")
        f.parentFile.mkdirs()
        f.writeText("""{"oauthAccount":{"emailAddress":"$slot@x.cz"}}""")
    }

    /** The real launcher with the script on stdin, as the exec transport runs it. */
    private fun runOnServer(body: String): String {
        val p = ProcessBuilder("bash", "-c", com.clauderemote.session.service.StdinScript.EXEC).apply {
            environment()["HOME"] = home.absolutePath
            environment()["PATH"] = bin.absolutePath + ":" + System.getenv("PATH")
            environment().remove("TMUX"); environment().remove("TMUX_PANE")
            redirectErrorStream(true)
        }.start()
        p.outputStream.use { it.write(com.clauderemote.session.service.StdinScript.stdin(body)) }
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    private fun setUpJarvis(): Triple<SessionOrchestrator, TabManager, SessionStorage> {
        val tabs = TabManager()
        val storage = SessionStorage(FakeKeyValueStore())
        val o = SessionOrchestrator(servers, tabs, storage).also { orchestrator = it }
        SshSessionHelper.execChannelsProvider = { NoExec }
        o.jarvisSwitchExecOverride = { body -> runOnServer(body) }
        val jarvis = ClaudeSession(
            id = "tab-j", server = server, folder = "~/jarvis", mode = ClaudeMode.YOLO, model = ClaudeModel.SONNET,
            tmuxSessionName = "claude-server-jarvis", connectionType = ConnectionType.SSH, claudeSessionId = "c-1",
            accountSlug = "hanakl@nekrachni.cz",
        )
        tabs.addTab(jarvis)
        tabs.addTab(jarvis.copy(id = "tab-x", tmuxSessionName = "claude-server-x"))
        storage.upsert(SessionStorage.fromClaudeSession(jarvis))
        return Triple(o, tabs, storage)
    }

    private fun unchanged(tabs: TabManager) {
        val t = tabs.getTab("tab-j")!!
        assertEquals("hanakl@nekrachni.cz", t.accountSlug, "rev HIGH-2: no model change without the switch")
        assertEquals(ClaudeMode.YOLO, t.mode)
        assertFalse(calls.exists() && "respawn-pane" in calls.readText(), "nothing respawned")
    }

    @Test fun anIdleJarvisIsRespawnedInTheSameRunAndOnlyThenTheModelFollows() = runBlocking {
        val (o, tabs, storage) = setUpJarvis()
        accounts("hanakl@nekrachni.cz", "default"); login("default"); lastEvent("stop"); emptyBox()
        assertEquals(com.clauderemote.session.service.JarvisSwitch.SwitchRun.Ok, o.switchJarvisAccountNow("tab-j", "default"))
        val c = calls.readText()
        assertTrue("respawn-pane -k -t %7 bash -l" in c, c)
        val typed = c.lines().single { it.startsWith("send-keys -t %7 ") }
        assertTrue("unset CLAUDE_CONFIG_DIR" in typed, "rev LOW1: default with the variable unset")
        assertTrue("--model opus" in typed, "rev LOW4: Opus")
        assertTrue("--resume c-1" in typed || "--resume" in typed && "c-1" in typed, typed)
        assertFalse(Regex("""(^|[\s'])--dangerously-skip-permissions""").containsMatchIn(typed), "never the bypass")
        val t = tabs.getTab("tab-j")!!
        assertEquals(ClaudeMode.NORMAL, t.mode)
        assertEquals(ClaudeModel.OPUS, t.model)
        assertNull(t.accountSlug, "default = null (contract a)")
        assertEquals("tab-x", tabs.activeTabId.value, "the user's view is not yanked to Jarvis")
        val saved = storage.load().single { it.id == "tab-j" }
        assertNull(saved.accountSlug)
        assertTrue("\"accountSlug\": null" in storage.serializeForServer(server.id))
        // another client's alignment touches only the account
        o.alignJarvisAccountModel("tab-x", "hanakl@nekrachni.cz")
        assertEquals("hanakl@nekrachni.cz", tabs.getTab("tab-x")!!.accountSlug)
    }

    @Test fun busyOrADraftTouchesNothing() = runBlocking {
        val (o, tabs, _) = setUpJarvis()
        accounts("hanakl@nekrachni.cz", "default"); login("default")
        lastEvent("work"); emptyBox()
        assertEquals(com.clauderemote.session.service.JarvisSwitch.SwitchRun.Busy("PROMPT_CHANGED"), o.switchJarvisAccountNow("tab-j", "default"))
        unchanged(tabs)
        lastEvent("stop")
        screen.writeText("─".repeat(10) + "\n❯\u00a0rozepsan\n")
        assertEquals(com.clauderemote.session.service.JarvisSwitch.SwitchRun.Busy("DRAFT_IN_INPUT"), o.switchJarvisAccountNow("tab-j", "default"))
        unchanged(tabs)
    }

    @Test fun med2OnlyAChosenSlotWithALogin() = runBlocking {
        val (o, tabs, _) = setUpJarvis()
        lastEvent("stop"); emptyBox()
        accounts("hanakl@nekrachni.cz", "default")
        assertEquals(com.clauderemote.session.service.JarvisSwitch.SwitchRun.Failed("notlisted"), o.switchJarvisAccountNow("tab-j", "lukas@kontexta.cz"))
        assertEquals(com.clauderemote.session.service.JarvisSwitch.SwitchRun.Failed("nologin"), o.switchJarvisAccountNow("tab-j", "default"))
        accounts("hanakl@nekrachni.cz", "lukas@kontexta.cz")
        java.io.File(home, ".claude-remote/accounts/lukas@kontexta.cz").mkdirs() // provisioned, never logged in
        assertEquals(com.clauderemote.session.service.JarvisSwitch.SwitchRun.Failed("nologin"), o.switchJarvisAccountNow("tab-j", "lukas@kontexta.cz"))
        unchanged(tabs)
        assertFalse(java.io.File(home, ".claude-remote/accounts/default").exists(), "no provisioning, ever")
    }

    @Test fun aCutOffRunIsAFailureAndChangesNothing() = runBlocking {
        val (o, tabs, _) = setUpJarvis()
        o.jarvisSwitchExecOverride = { "CR_REPLY IDLE\nCR_SWITCH RESPAWNED\n" } // no end marker: the run's end was not seen
        assertEquals(com.clauderemote.session.service.JarvisSwitch.SwitchRun.Unknown, o.switchJarvisAccountNow("tab-j", "default"))
        unchanged(tabs)
        o.jarvisSwitchExecOverride = { null } // the exec itself broke
        assertEquals(com.clauderemote.session.service.JarvisSwitch.SwitchRun.Unknown, o.switchJarvisAccountNow("tab-j", "default"))
        unchanged(tabs)
    }

    @Test fun theRelaunchLineOfNormalHasNoBypassFlag() {
        val cmd = ClaudeConfig.buildRestartCommand(
            "claude-server-jarvis", "~/jarvis", ClaudeMode.NORMAL, ClaudeModel.OPUS, "c-1", accountSlug = null, resume = null,
        )
        val tokens = Regex("""[^\s'\\;]+""").findAll(cmd).map { it.value }.toList()
        assertFalse("--dangerously-skip-permissions" in tokens, cmd)
        assertTrue("--resume" in tokens, "the same conversation")
        assertFalse("CLAUDE_CONFIG_DIR" in cmd, "default runs with no config dir")
        val other = ClaudeConfig.buildRestartCommand(
            "claude-server-jarvis", "~/jarvis", ClaudeMode.NORMAL, ClaudeModel.OPUS, "c-1", accountSlug = "hanakl@nekrachni.cz", resume = null,
        )
        assertTrue("CLAUDE_CONFIG_DIR=" in other)
        // control: YOLO would have it
        val yolo = ClaudeConfig.buildRestartCommand("claude-server-jarvis", "~/jarvis", ClaudeMode.YOLO, ClaudeModel.OPUS, "c-1", resume = null)
        assertTrue("--dangerously-skip-permissions" in Regex("""[^\s'\\;]+""").findAll(yolo).map { it.value }.toList())
    }

    @Test fun theSwitchRestartsUnderTheAnswersLockAndPollsWithAPollPermit() {
        val src = java.io.File("src/commonMain/kotlin/com/clauderemote/session/SessionOrchestrator.kt").readText()
        assertTrue("switchNow = { id, slot -> jarvisAnswerLock.withLock { switchJarvisAccountNow(id, slot) } }" in src)
        // the spoken prompt and the notification answers take the same lock (HIGH-1: nothing types while it switches)
        assertTrue("jarvisAnswerLock.withLock { sendJarvisChecked(prompt, stillWanted" in src)
        assertTrue("jarvisAnswerLock.withLock { sendJarvisAnswerLocked(text) }" in src)
        // the model changes only after Ok (HIGH-2)
        val sw = src.substring(src.indexOf("internal suspend fun switchJarvisAccountNow("), src.indexOf("internal var jarvisSwitchExecOverride"))
        assertTrue(sw.indexOf("if (run != com.clauderemote.session.service.JarvisSwitch.SwitchRun.Ok) return run") < sw.indexOf("setTabModel("))
        assertTrue(sw.indexOf("setTabModel(") < sw.indexOf("pushSessionsToServer"))
        assertFalse("provisionAccount" in sw, "MED-2: no provisioning")
        assertTrue("jarvisRun(it.server, cmd, periodic = periodic)" in src)
        val agent = java.io.File("src/commonMain/kotlin/com/clauderemote/session/service/JarvisSwitch.kt").readText()
        assertTrue("run(JarvisSwitch.POLL, true)" in agent, "the poll is periodic (POLL permit, silently skipped when busy)")
    }

    /** send-keys fails the first [fails] times (a fresh pane not taking keys yet), everything else is recorded. */
    private fun stubFailingTyping(fails: Int) {
        val n = java.io.File(root, "typefails").apply { writeText("$fails") }
        java.io.File(bin, "tmux").writeText(
            "#!/bin/bash\n[ \"\$1\" = -u ] && shift\n" +
                "if [ \"\$1\" = list-panes ]; then printf '%%7\\n%%9\\n'; exit 0; fi\n" +
                "if [ \"\$1\" = capture-pane ]; then cat '${screen.absolutePath}' 2>/dev/null; exit 0; fi\n" +
                "printf '%s\\n' \"\$*\" >> '${calls.absolutePath}'\n" +
                "if [ \"\$1\" = send-keys ]; then k=\$(cat '${n.absolutePath}'); if [ \"\$k\" -gt 0 ]; then echo \$((k-1)) > '${n.absolutePath}'; exit 1; fi; fi\n",
        )
    }

    @Test fun theTypingIsRetriedOnceThenItIsTypefail() = runBlocking {
        val (o, tabs, _) = setUpJarvis()
        accounts("hanakl@nekrachni.cz", "default"); login("default"); lastEvent("stop"); emptyBox()
        stubFailingTyping(1)
        assertEquals(com.clauderemote.session.service.JarvisSwitch.SwitchRun.Ok, o.switchJarvisAccountNow("tab-j", "default"))
        assertEquals(2, calls.readText().lines().count { it.startsWith("send-keys -t %7 ") }, "typed again after a failure")
        assertNull(tabs.getTab("tab-j")!!.accountSlug)
    }

    @Test fun twoFailedTypingsAreTypefailAndTheModelStays() = runBlocking {
        val (o, tabs, _) = setUpJarvis()
        accounts("hanakl@nekrachni.cz", "default"); login("default"); lastEvent("stop"); emptyBox()
        stubFailingTyping(2)
        assertEquals(com.clauderemote.session.service.JarvisSwitch.SwitchRun.Failed("typefail"), o.switchJarvisAccountNow("tab-j", "default"))
        assertEquals(2, calls.readText().lines().count { it.startsWith("send-keys -t %7 ") }, "exactly one retry")
        assertEquals("hanakl@nekrachni.cz", tabs.getTab("tab-j")!!.accountSlug)
    }
}
