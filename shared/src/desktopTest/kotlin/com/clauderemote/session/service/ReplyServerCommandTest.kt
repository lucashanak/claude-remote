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

    // Runs on the first `send-keys` when present: a dialog opening while the text is typed.
    private val onType = File(root, "on-type")

    init {
        // list-panes answers the main pane first and a split second; everything else is recorded.
        File(bin, "tmux").apply {
            writeText(
                "#!/bin/bash\n" +
                    "[ \"\$1\" = -u ] && shift\n" +
                    "if [ \"\$1\" = list-panes ]; then printf '%%3\\n%%5\\n'; exit 0; fi\n" +
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

    private fun run(expect: ServerExpectation, text: String = "ahoj", approve: Boolean = false): String {
        val pb = ProcessBuilder("bash", "-c", ReplyGuard.serverCommand(tmux, expect, text, approve))
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
        assertEquals("CR_REPLY SENT", run(ServerExpectation.Dialog("perm", 2_000), "y", approve = true))
        // one tmux call, no gap between the answer and its Enter; to the main pane, not `=name:`
        assertEquals("send-keys -t %3 -l -- y ; send-keys -t %3 Enter\n", keys(), "another pane's newer line does not count")
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
     * The real tmux, on a PRIVATE socket (its own TMUX_TMPDIR inside this test's temp root; `tmux`
     * resolves through the Gradle test shim first on PATH, which refuses the live socket anyway): Czech text ending in `;` arrives
     * literally in the main pane, Enter included.
     */
    @Test fun realTmuxReceivesCzechTextWithATrailingSemicolonLiterally() {
        if (listOf("/usr/bin/tmux", "/usr/local/bin/tmux").none { File(it).canExecute() }) return
        val tmuxDir = File(root, "t").apply { mkdirs() }
        if (tmuxDir.absolutePath.length > 70) return // the socket path must stay under the 108-byte limit
        File(bin, "tmux").delete() // the stub; the gradle shim (first on PATH) execs the real one
        val out = File(root, "pane-out")
        fun tmuxEnv(pb: ProcessBuilder) = pb.apply {
            environment()["HOME"] = home.absolutePath
            environment()["TMUX_TMPDIR"] = tmuxDir.absolutePath
            environment().remove("TMUX")
            environment().remove("TMUX_PANE")
            redirectErrorStream(true)
        }
        val start = tmuxEnv(ProcessBuilder("tmux", "-u", "new-session", "-d", "-s", tmux, "-x", "80", "-y", "10", "cat > '${out.absolutePath}'"))
            .start()
        assertEquals(0, start.waitFor(), start.inputStream.bufferedReader().readText())
        try {
            events(ev("stop", 1_000))
            val pb = tmuxEnv(ProcessBuilder("bash", "-c", ReplyGuard.serverCommand(tmux, ServerExpectation.Ready(0), "přidej test; pak commitni;")))
            val p = pb.start()
            val res = p.inputStream.bufferedReader().readText()
            p.waitFor()
            assertTrue("CR_REPLY SENT" in res, res)
            val end = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < end && !(out.exists() && out.readText().endsWith("\n"))) Thread.sleep(50)
            assertEquals("přidej test; pak commitni;\n", out.readText(Charsets.UTF_8))
        } finally {
            tmuxEnv(ProcessBuilder("tmux", "kill-server")).start().waitFor()
        }
    }
}
