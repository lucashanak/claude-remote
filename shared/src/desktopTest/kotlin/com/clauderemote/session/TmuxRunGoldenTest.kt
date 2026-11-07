package com.clauderemote.session

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The tmux launch builders, byte for byte, against their output captured BEFORE `buildTmuxRunCommand` was cut into
 * pieces (`socketGuardHead`, `optionsPreludeBody`, `killExistingBody`, `createBody`) so `cr-session` could compose the
 * same pieces over shell variables (Jarvis F0, R3 = 3c). The app must keep sending exactly what it sent: a byte that
 * moves here moves on every launch of every session.
 *
 * The golden file was written by this test from the pre-refactor builder (a missing file is written and the test
 * fails, so a fresh capture is never mistaken for a pass). Never regenerate it to make a diff go away.
 */
class TmuxRunGoldenTest {

    private val sid = "11111111-2222-3333-4444-555555555555"

    private fun cases(): List<Pair<String, String>> = buildList {
        val tricky = "claude-server-my proj's-yolo--a'b"
        add("launch attach resume" to ClaudeConfig.buildTmuxLaunchCommand(
            tricky, "/home/u/my proj's", ClaudeMode.YOLO, ClaudeModel.OPUS, sid, resume = true, accountSlug = null,
            onExisting = ClaudeConfig.OnExisting.Attach(),
        ))
        add("launch attach custom" to ClaudeConfig.buildTmuxLaunchCommand(
            "claude-server-p--x", "~/p", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, sid, resume = false, accountSlug = "lukas@kontexta.cz",
            onExisting = ClaudeConfig.OnExisting.Attach("echo 'attach' && tmux attach-session -t '=claude-server-p--x'"),
        ))
        add("launch refuse fresh" to ClaudeConfig.buildTmuxLaunchCommand(
            tricky, "/w", ClaudeMode.AUTO, ClaudeModel.SONNET, sid, resume = false, accountSlug = "acc",
            onExisting = ClaudeConfig.OnExisting.Refuse,
        ))
        add("launch server decided" to ClaudeConfig.buildTmuxLaunchCommand(
            "claude-server-q", "~", ClaudeMode.AUTO_ACCEPT, ClaudeModel.HAIKU, sid, resume = null, accountSlug = "acc'x",
            onExisting = ClaudeConfig.OnExisting.Attach(),
        ))
        add("launch no sid local" to ClaudeConfig.buildTmuxLaunchCommand(
            "claude-server-l", "/l", ClaudeMode.PLAN, ClaudeModel.LOCAL, null, resume = false, accountSlug = null,
            onExisting = ClaudeConfig.OnExisting.Refuse,
        ))
        add("fresh create" to ClaudeConfig.buildFreshCreateCommand(
            tricky, "~/x y", ClaudeMode.YOLO, ClaudeModel.FABLE, sid, accountSlug = "acc", nonce = "abc123",
        ))
        add("fresh create no sid" to ClaudeConfig.buildFreshCreateCommand(
            "claude-server-n", "/n", ClaudeMode.NORMAL, ClaudeModel.DEFAULT, null, accountSlug = null, nonce = "Z9",
        ))
        add("login" to ClaudeConfig.buildTmuxLoginCommand("claude-login-x'y", "acct"))
        add("restart" to ClaudeConfig.buildRestartCommand(tricky, "/home/u/p", ClaudeMode.YOLO, ClaudeModel.OPUS, sid, "acc", resume = null))
        for (mode in ClaudeMode.entries) for (model in ClaudeModel.entries) for (resume in listOf(true, false)) {
            add("launch ${mode.name} ${model.name} $resume" to
                ClaudeConfig.buildLaunchCommand("/p", mode, model, sid, resume, if (resume) "acc" else null))
        }
    }

    private fun goldenFile(): File {
        val rel = "src/desktopTest/resources/golden/tmux-run-commands.txt"
        return listOf(File(rel), File("shared/$rel")).firstOrNull { it.parentFile.parentFile.isDirectory } ?: File(rel)
    }

    /** One record per case: a `### label` line, the command, then an empty line. Commands never contain a newline. */
    private fun render(cases: List<Pair<String, String>>): String =
        cases.joinToString("") { (label, cmd) ->
            require('\n' !in cmd) { "a command with a newline cannot be stored line-wise: $label" }
            "### $label\n$cmd\n\n"
        }

    @Test
    fun everyLaunchBuilderStillSendsThePreRefactorBytes() {
        val f = goldenFile()
        val now = render(cases())
        if (!f.isFile) {
            f.parentFile.mkdirs()
            f.writeText(now)
            fail("golden file was missing and has been written to ${f.absolutePath}; rerun")
        }
        val want = f.readText().split("### ").filter { it.isNotBlank() }.associate {
            val label = it.substringBefore('\n')
            label to it.substringAfter('\n').removeSuffix("\n\n")
        }
        val got = cases().toMap()
        assertEquals(want.keys, got.keys, "the set of golden cases changed")
        for ((label, cmd) in got) assertEquals(want[label], cmd, "builder output changed for case: $label")
    }
}
