package com.clauderemote.session.service

import com.clauderemote.testsupport.safeDeleteTree
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ReplyGuard.serverCommand] run by a real bash against a fake HOME: the pane's newest
 * `claude-remote-ev` line decides, and the keys go out only when it matches. `tmux` is a stub
 * on PATH that only records its arguments — no tmux server is ever touched.
 */
class ReplyServerCommandTest {
    private val root = Files.createTempDirectory("reply-cmd").toFile()
    private val home = File(root, "home").apply { mkdirs() }
    private val bin = File(root, "bin").apply { mkdirs() }
    private val sent = File(root, "sent")
    private val tmux = "claude-server-x"

    // What capture-pane prints (missing = nothing on the screen).
    private val screen = File(root, "screen")

    // Runs on the first `send-keys` when present: a dialog opening while the text is typed.
    private val onType = File(root, "on-type")

    init {
        // list-panes answers the main pane first and a split second; everything else is recorded.
        File(bin, "tmux").apply {
            writeText(
                "#!/bin/bash\n" +
                    "[ \"\$1\" = -u ] && shift\n" +
                    "if [ \"\$1\" = list-panes ]; then printf '%%3\\n%%5\\n'; exit 0; fi\n" +
                    // the screen of the pane (Jarvis' conversation mode reads his input box); not recorded as a key
                    "if [ \"\$1\" = capture-pane ]; then cat '${screen.absolutePath}' 2>/dev/null; exit 0; fi\n" +
                    "printf '%s\\n' \"\$*\" >> '${sent.absolutePath}'\n" +
                    "if [ -f '${onType.absolutePath}' ]; then bash '${onType.absolutePath}'; rm -f '${onType.absolutePath}'; fi\n",
            )
            setExecutable(true)
        }
    }

    @AfterTest fun cleanup() = safeDeleteTree(root)

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun events(vararg lines: String) {
        val f = File(home, ".cache/claude-remote/events")
        f.parentFile.mkdirs()
        f.writeText(lines.joinToString("") { "$it\n" })
    }

    private fun ev(ev: String, at: Long, name: String = tmux) = "claude-remote-ev $ev ${b64(name)} $at 4242 ${b64("x")}"

    private fun legacy(vararg lines: String) {
        val f = File(home, ".cache/claude-remote/notify")
        f.parentFile.mkdirs()
        f.writeText(lines.joinToString("") { "$it\n" })
    }

    private fun run(expect: ServerExpectation, text: String = "ahoj", approve: Boolean = false, emptyInput: Boolean = false): String {
        val pb = ProcessBuilder("bash", "-c", ReplyGuard.serverCommand(tmux, expect, text, approve, emptyInput))
        pb.environment()["HOME"] = home.absolutePath
        pb.environment()["PATH"] = bin.absolutePath + ":" + System.getenv("PATH")
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        // SENDING precedes the outcome whenever keys go out; the outcome is what the tests name.
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() && it != "CR_REPLY SENDING" }.joinToString("\n")
    }

    private fun keys() = if (sent.exists()) sent.readText() else ""

    @Test fun aDialogAnswerGoesOutOnlyWhileThatPermIsThePanesNewestEvent() {
        events(ev("work", 1_000), ev("perm", 2_000), ev("work", 2_500, name = "claude-server-other"))
        screen.writeText(PERM_SCREEN)
        assertEquals("CR_REPLY SENT", run(ServerExpectation.Dialog("perm", 2_000), "y", approve = true))
        // Enter only, with the focus on "1. Yes"; to the main pane, not `=name:`
        assertEquals("send-keys -t %3 Enter\n", keys(), "another pane's newer line does not count")
    }

    // Claude 2.1.322's permission dialog as captured live (2026-11-11)
    private val PERM_SCREEN = """
         Do you want to proceed?
         ❯ 1. Yes
           2. Yes, and always allow access to /tmp/x from this project
           3. Yes, and switch to auto mode · auto mode handles these prompts for you
           4. No
         Esc to cancel · Tab to amend
    """.trimIndent() + "\n"

    @Test fun neDeniesWithEscapeNeverWithAnEnterThatWouldPickTheFocusedYes() {
        // live 2026-11-11: `n` + Enter ran the command — Claude has no `n` binding, the Enter picked "1. Yes"
        events(ev("perm", 2_000))
        screen.writeText(PERM_SCREEN)
        assertEquals("CR_REPLY SENT", run(ServerExpectation.Dialog("perm", 2_000), "n", approve = true))
        assertEquals("send-keys -t %3 Escape\n", keys())
        assertTrue("Enter" !in keys() && "-l" !in keys(), "no Enter, no typed letter")
    }

    @Test fun neDoesNotEscapeARunningTurnWhenTheDialogIsGoneFromTheScreen() {
        // answered on a terminal: no event, the newest line is still that perm, but Escape would interrupt the turn
        events(ev("perm", 2_000))
        screen.writeText("● Running the tests…\n")
        assertEquals("CR_REPLY STALE", run(ServerExpectation.Dialog("perm", 2_000), "n", approve = true))
        assertEquals("", keys())
    }

    @Test fun anoOnAQuestionGoesOutOnlyWithTheFocusOnItsFirstOption() {
        events(ev("ask", 2_000))
        screen.writeText(" Jak dál?\n ❯ 1. Commitnout lokálně (Recommended)\n   2. Jeden commit\n")
        assertEquals("CR_REPLY SENT", run(ServerExpectation.Dialog("ask", 2_000), "y", approve = true))
        assertEquals("send-keys -t %3 Enter\n", keys())
        sent.delete()
        screen.writeText(" Jak dál?\n   1. Commitnout lokálně (Recommended)\n ❯ 2. Jeden commit\n")
        assertEquals("CR_REPLY STALE", run(ServerExpectation.Dialog("ask", 2_000), "y", approve = true))
        assertEquals("", keys())
    }

    @Test fun anoGoesOutOnlyWhileTheFocusIsOnTheOneTimeYes() {
        events(ev("perm", 2_000))
        screen.writeText(PERM_SCREEN.replace("❯ 1. Yes", "  1. Yes").replace("  2. Yes, and always", "❯ 2. Yes, and always"))
        assertEquals("CR_REPLY STALE", run(ServerExpectation.Dialog("perm", 2_000), "y", approve = true))
        assertEquals("", keys(), "focus on 'always allow': nothing pressed")
        screen.delete()
        assertEquals("CR_REPLY STALE", run(ServerExpectation.Dialog("perm", 2_000), "y", approve = true))
        assertEquals("", keys(), "no dialog on the screen: nothing pressed")
    }

    @Test fun anyOtherDialogAnswerTextIsRefused() {
        events(ev("perm", 2_000))
        screen.writeText(PERM_SCREEN)
        assertEquals("CR_REPLY FAILED", run(ServerExpectation.Dialog("perm", 2_000), "2", approve = true))
        assertEquals("", keys())
    }

    @Test fun aNewerDialogOrAnyLaterEventMakesItStale() {
        events(ev("perm", 2_000), ev("perm", 3_000))
        assertEquals("CR_REPLY STALE", run(ServerExpectation.Dialog("perm", 2_000), "y"))
        events(ev("perm", 2_000), ev("work", 3_000))
        assertEquals("CR_REPLY STALE", run(ServerExpectation.Dialog("perm", 2_000), "y"))
        assertEquals("", keys(), "nothing typed")
    }

    @Test fun withoutHooksADialogAnswerFailsClosed() {
        assertEquals("CR_REPLY NOHOOKS", run(ServerExpectation.Dialog("perm", 2_000), "y"), "no events file")
        events(ev("perm", 2_000))
        File(home, ".claude-remote").mkdirs()
        File(home, ".claude-remote/hookev.off").writeText("")
        assertEquals("CR_REPLY NOHOOKS", run(ServerExpectation.Dialog("perm", 2_000), "y"), "hooks switched off")
        assertEquals("", keys())
    }

    @Test fun aFullEventsFileIsFrozenAndCountsAsNoHooks() {
        events(ev("perm", 2_000))
        val f = File(home, ".cache/claude-remote/events")
        java.io.RandomAccessFile(f, "rw").use { it.setLength(HookCommands.EVENTS_MAX_BYTES.toLong()) }
        assertEquals("CR_REPLY NOHOOKS", run(ServerExpectation.Dialog("perm", 2_000), "y"))
    }

    @Test fun readyTextNeedsAStopAsTheNewestEventOrALegacyMarker() {
        events(ev("perm", 1_000), ev("stop", 2_000))
        assertEquals("CR_REPLY SENT", run(ServerExpectation.Ready(1)))
        events(ev("stop", 2_000), ev("ask", 3_000))
        assertEquals("CR_REPLY STALE", run(ServerExpectation.Ready(1)), "a question opened after the turn ended")
        // hookless server: the newest legacy marker of the pane must be at least the notified one
        safeDeleteTree(File(home, ".cache"))
        legacy("claude-remote-notify $tmux 100", "claude-remote-notify claude-server-other 300")
        assertEquals("CR_REPLY SENT", run(ServerExpectation.Ready(100)))
        assertEquals("CR_REPLY STALE", run(ServerExpectation.Ready(200)), "no marker that recent for this pane")
    }

    @Test fun anInstructionToAWorkingSessionIsRefusedWhileADialogIsTheNewestEvent() {
        events(ev("work", 1_000))
        assertEquals("CR_REPLY SENT", run(ServerExpectation.Working))
        events(ev("work", 1_000), ev("ask", 2_000))
        assertEquals("CR_REPLY STALE", run(ServerExpectation.Working))
        safeDeleteTree(File(home, ".cache"))
        assertEquals("CR_REPLY NOHOOKS", run(ServerExpectation.Working), "without hooks a working session cannot be vouched for")
    }

    @Test fun anIdleInstructionNeedsAStopAndNoHooksFailsClosed() {
        events(ev("stop", 1_000))
        assertEquals("CR_REPLY SENT", run(ServerExpectation.Idle))
        events(ev("stop", 1_000), ev("work", 2_000))
        assertEquals("CR_REPLY STALE", run(ServerExpectation.Idle))
        safeDeleteTree(File(home, ".cache"))
        legacy("claude-remote-notify $tmux 100")
        assertEquals("CR_REPLY NOHOOKS", run(ServerExpectation.Idle), "a legacy marker does not vouch for an instruction")
    }

    @Test fun theTextIsTypedLiterallyWhateverItContains() {
        events(ev("stop", 1_000))
        val nasty = "it's \$(rm -rf ~) `x` \"q\" ; Enter"
        assertEquals("CR_REPLY SENT", run(ServerExpectation.Ready(0), nasty))
        assertTrue(keys().startsWith("send-keys -t %3 -l -- $nasty\n"), keys())
    }

    // ------------------------------------------------------------ verification review (fix2)

    @Test fun theKeysGoToTheMainPaneNeverToTheActivePaneOfTheWindow() {
        events(ev("stop", 1_000))
        assertEquals("CR_REPLY SENT", run(ServerExpectation.Ready(0)))
        assertEquals("send-keys -t %3 -l -- ahoj\nsend-keys -t %3 Enter\n", keys())
    }

    @Test fun noMainPaneMeansNothingIsSent() {
        File(bin, "tmux").writeText("#!/bin/bash\nexit 1\n")
        events(ev("stop", 1_000))
        assertEquals("CR_REPLY NOPANE", run(ServerExpectation.Ready(0)))
    }

    @Test fun aHungTmuxIsCutOffBeforeAnyKeyAndCountsAsNotSent() {
        File(bin, "tmux").writeText("#!/bin/bash\nexec sleep 30\n")
        events(ev("stop", 1_000))
        val t0 = System.currentTimeMillis()
        val out = run(ServerExpectation.Ready(0))
        assertEquals("CR_REPLY NOPANE", out, "list-panes timed out (124): no pane, nothing sent")
        assertTrue(System.currentTimeMillis() - t0 < 6_000, "capped at 2 s (+1 s kill), not the exec's 8 s")
        assertEquals(RefuseReason.NOT_VERIFIABLE, ReplyGuard.serverResult(out))
    }

    @Test fun aDialogThatOpensWhileTheTextIsTypedGetsNoEnter() {
        events(ev("work", 1_000))
        onType.writeText("printf '%s\\n' '${ev("perm", 2_000)}' >> '${File(home, ".cache/claude-remote/events").absolutePath}'\n")
        assertEquals("CR_REPLY STALE_TYPED", run(ServerExpectation.Working, "a pak tohle"))
        assertEquals("send-keys -t %3 -l -- a pak tohle\n", keys(), "typed, but Enter would have picked the dialog's default")
        assertEquals(RefuseReason.TYPED_NOT_SENT, ReplyGuard.serverResult("CR_REPLY SENDING\nCR_REPLY STALE_TYPED"))
    }

    @Test fun anEndedSessionHoldsAShellAndTakesNoInstruction() {
        events(ev("work", 1_000), ev("end", 2_000))
        assertEquals("CR_REPLY STALE", run(ServerExpectation.Working))
        assertEquals("", keys())
    }

    @Test fun aFreshlyStartedSessionIsIdle() {
        events(ev("end", 1_000), ev("start", 2_000))
        assertEquals("CR_REPLY SENT", run(ServerExpectation.Idle))
    }

    @Test fun aTrailingSemicolonStaysLiteral() {
        events(ev("stop", 1_000))
        assertEquals("CR_REPLY SENT", run(ServerExpectation.Ready(0), "přidej test; pak commitni;"))
        assertTrue(keys().startsWith("send-keys -t %3 -l -- přidej test; pak commitni\\;\n"), keys())
        assertEquals("a\\;", ReplyGuard.tmuxLiteral("a;"))
        assertEquals("a\\\\;", ReplyGuard.tmuxLiteral("a\\;"))
        assertEquals("a;b", ReplyGuard.tmuxLiteral("a;b"))
    }

    @Test fun anExecThatStopsAfterSendingMaySentTheKeys() {
        assertEquals(RefuseReason.MAYBE_SENT, ReplyGuard.serverResult("CR_REPLY SENDING\n"))
        assertEquals(RefuseReason.NO_HOOKS, ReplyGuard.serverResult("CR_REPLY NOHOOKS"))
        assertEquals(RefuseReason.NOT_VERIFIABLE, ReplyGuard.serverResult("CR_REPLY NOPANE"))
    }

    /**
     * The real tmux, on a PRIVATE socket: its own TMUX_TMPDIR inside this test's temp root AND
     * `-L cr-reply-test` on every call, the ones [ReplyGuard.serverCommand] makes included — a
     * `tmux` wrapper first on PATH adds it and execs the Gradle test shim, which refuses the live
     * socket anyway. Czech text ending in `;` arrives literally in the main pane, Enter included.
     */
    @Test fun realTmuxReceivesCzechTextWithATrailingSemicolonLiterally() {
        // Reported as skipped, never a silent pass.
        org.junit.Assume.assumeTrue(
            "SKIPPED: no tmux in /usr/bin or /usr/local/bin",
            listOf("/usr/bin/tmux", "/usr/local/bin/tmux").any { File(it).canExecute() },
        )
        val tmuxDir = File(root, "t").apply { mkdirs() }
        org.junit.Assume.assumeTrue(
            "SKIPPED: socket directory ${tmuxDir.absolutePath} is over 70 characters (socket path limit 108 bytes)",
            tmuxDir.absolutePath.length <= 70,
        )
        val shim = System.getProperty("clauderemote.test.shimDir")?.let { File(it, "tmux") }?.takeIf { it.canExecute() }
        org.junit.Assume.assumeTrue("SKIPPED: the Gradle test tmux shim is not available", shim != null)
        shim!!
        // Replaces the recording stub: every tmux call of this test goes to the private named socket.
        // The shim runs with the PATH Gradle gave it (without this wrapper's directory), or its
        // "next tmux on PATH" would be this wrapper again — an endless exec loop.
        val gradlePath = System.getenv("PATH") ?: "/usr/bin:/bin"
        File(bin, "tmux").writeText(
            "#!/bin/bash\nPATH='$gradlePath' exec '${shim.absolutePath}' -L cr-reply-test \"\$@\"\n",
        )
        val out = File(root, "pane-out")
        fun tmuxEnv(pb: ProcessBuilder) = pb.apply {
            environment()["PATH"] = bin.absolutePath + ":" + System.getenv("PATH")
            environment()["HOME"] = home.absolutePath
            environment()["TMUX_TMPDIR"] = tmuxDir.absolutePath
            environment().remove("TMUX")
            environment().remove("TMUX_PANE")
            redirectErrorStream(true)
        }
        val start = tmuxEnv(ProcessBuilder("tmux", "-u", "new-session", "-d", "-s", tmux, "-x", "80", "-y", "10", "cat > '${out.absolutePath}'"))
            .start()
        val started = start.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
        if (!started) start.destroyForcibly()
        assertTrue(started && start.exitValue() == 0, "tmux new-session on the private socket failed")
        try {
            events(ev("stop", 1_000))
            val pb = tmuxEnv(ProcessBuilder("bash", "-c", ReplyGuard.serverCommand(tmux, ServerExpectation.Ready(0), "přidej test; pak commitni;")))
            val p = pb.start()
            val res = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "the reply command hung")
            assertTrue("CR_REPLY SENT" in res, res)
            val end = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < end && !(out.exists() && out.readText().endsWith("\n"))) Thread.sleep(50)
            assertEquals("přidej test; pak commitni;\n", out.readText(Charsets.UTF_8))
        } finally {
            // the private named socket only (wrapper adds -L cr-reply-test, TMUX_TMPDIR is ours)
            tmuxEnv(ProcessBuilder("tmux", "kill-server")).start().waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    // ---- Jarvis' conversation mode (F5a): the input box must be empty right before the keys ----

    private val esc = "\u001b"
    private val rule = "$esc[38;5;244m" + "─".repeat(40) + "$esc[39m"

    /** A Claude Code 2.1 screen: the box row is `❯`+NBSP right below a rule ([box] typed, else the dim placeholder). */
    private fun drawScreen(box: String?, cursor: Boolean = true) {
        val row = when {
            box != null -> "$esc[39m❯\u00a0$box"
            cursor -> "$esc[39m❯\u00a0$esc[7mT$esc[0m$esc[2mry \"write a test for <filepath>\"$esc[0m"
            else -> "$esc[39m❯\u00a0$esc[2mTry \"fix lint errors\"$esc[0m"
        }
        screen.writeText(listOf("● PONG", "$esc[38;5;246m✻$esc[39m Baked for 2s", rule, row, rule, "  ⏵⏵ bypass permissions on").joinToString("\n") + "\n")
    }

    @Test fun aSpokenPromptGoesInOnlyOverAnEmptyInputBox() {
        events(ev("stop", 1_000))
        drawScreen(box = null)
        val before = System.currentTimeMillis()
        val out = run(ServerExpectation.Idle, "[hlas] co je nového", emptyInput = true)
        // SENT carries the server's ms right before Enter: the app reads only a later Stop as the answer
        assertTrue(Regex("CR_REPLY SENT \\d{13}").matches(out), out)
        assertEquals(null, ReplyGuard.serverResult(out))
        val at = ReplyGuard.sentAtServerMs(out)!!
        assertTrue(at in (before - 1_000)..(System.currentTimeMillis() + 1_000), "$at")
        assertTrue(keys().contains("send-keys -t %3 -l -- [hlas] co je nového"), keys())
        sent.delete()
        drawScreen(box = null, cursor = false)
        assertTrue(run(ServerExpectation.Idle, emptyInput = true).startsWith("CR_REPLY SENT "), "the placeholder without the cursor is empty too")
    }

    @Test fun aDraftInTheBoxStopsASpokenPrompt() {
        events(ev("stop", 1_000))
        drawScreen(box = "rozepsan")
        assertEquals("CR_REPLY DRAFT", run(ServerExpectation.Idle, "[hlas] ano", emptyInput = true))
        assertEquals("", keys(), "nothing typed after the draft")
    }

    @Test fun noBoxOnTheScreenStopsASpokenPrompt() {
        events(ev("stop", 1_000))
        assertEquals("CR_REPLY DRAFT", run(ServerExpectation.Idle, emptyInput = true), "no capture at all")
        screen.writeText("Resume a conversation\n❯ 1. yesterday\n")
        assertEquals("CR_REPLY DRAFT", run(ServerExpectation.Idle, emptyInput = true), "another screen")
        assertEquals("", keys())
    }

    @Test fun theDraftCheckRunsOnlyAfterTheEventCheck() {
        events(ev("work", 1_000))
        drawScreen(box = null)
        assertEquals("CR_REPLY STALE", run(ServerExpectation.Idle, emptyInput = true))
    }

    @Test fun withoutTheFlagTheBoxIsNotRead() {
        events(ev("stop", 1_000))
        drawScreen(box = "rozepsan")
        assertEquals("CR_REPLY SENT", run(ServerExpectation.Idle), "the watch's reply path is unchanged (no time either)")
    }

    // ---- F5d: the idle check before Jarvis' account switch types nothing ----

    private fun runIdleCheck(): String {
        val pb = ProcessBuilder("bash", "-c", ReplyGuard.idleCheckCommand(tmux))
        pb.environment()["HOME"] = home.absolutePath
        pb.environment()["PATH"] = bin.absolutePath + ":" + System.getenv("PATH")
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }

    @Test fun theIdleCheckSaysIdleOnlyForAnIdleEmptyPaneAndNeverTypes() {
        events(ev("stop", 1_000))
        drawScreen(box = null)
        assertEquals("CR_REPLY IDLE", runIdleCheck())
        assertEquals(null, ReplyGuard.idleCheckResult(runIdleCheck()))
        drawScreen(box = "rozepsan")
        assertEquals(RefuseReason.DRAFT_IN_INPUT, ReplyGuard.idleCheckResult(runIdleCheck()))
        events(ev("work", 1_000))
        drawScreen(box = null)
        assertEquals(RefuseReason.PROMPT_CHANGED, ReplyGuard.idleCheckResult(runIdleCheck()))
        events(ev("perm", 1_000))
        assertEquals(RefuseReason.PROMPT_CHANGED, ReplyGuard.idleCheckResult(runIdleCheck()))
        assertEquals("", keys(), "no key, no Enter, ever")
        assertEquals(RefuseReason.NOT_VERIFIABLE, ReplyGuard.idleCheckResult(""))
        assertEquals(RefuseReason.NOT_VERIFIABLE, ReplyGuard.idleCheckResult("CR_REPLY SENT"), "a send is no idle answer")
    }
}
