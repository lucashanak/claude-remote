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

    init {
        File(bin, "tmux").apply {
            writeText("#!/bin/bash\nprintf '%s\\n' \"\$*\" >> '${sent.absolutePath}'\n")
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

    private fun run(expect: ServerExpectation, text: String = "ahoj"): String {
        val pb = ProcessBuilder("bash", "-c", ReplyGuard.serverCommand(tmux, expect, text))
        pb.environment()["HOME"] = home.absolutePath
        pb.environment()["PATH"] = bin.absolutePath + ":" + System.getenv("PATH")
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }

    private fun keys() = if (sent.exists()) sent.readText() else ""

    @Test fun aDialogAnswerGoesOutOnlyWhileThatPermIsThePanesNewestEvent() {
        events(ev("work", 1_000), ev("perm", 2_000), ev("work", 2_500, name = "claude-server-other"))
        assertEquals("CR_REPLY SENT", run(ServerExpectation.Dialog("perm", 2_000), "y"))
        assertEquals("send-keys -t =$tmux: -l -- y\nsend-keys -t =$tmux: Enter\n", keys(), "another pane's newer line does not count")
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
        assertTrue(keys().startsWith("send-keys -t =$tmux: -l -- $nasty\n"), keys())
    }
}
