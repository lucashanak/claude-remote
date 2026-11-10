package com.clauderemote.session.service

import com.clauderemote.model.ClaudeMode
import com.clauderemote.testsupport.safeDeleteTree
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D3: [StdinScript] in a real shell — the four scripts that went on stdin (close, close facts, restore, the
 * `cr-session` install), each sent the way the app sends it: [StdinScript.EXEC] as the command of a login shell (bash
 * and dash; zsh where installed, else skipped with a message — the box has none), the script on stdin.
 *
 * Fail-closed (kritika D2 N4): a stdin cut at every byte of its last 256 B and at every 64th byte before runs NOTHING
 * — a private HOME stays exactly as it was (no tombstone, no `closed.jsonl`, no `cr-session`, no restore) — and the
 * answer is [StdinScript.TRUNCATED] with exit 97, which [StdinScript.parse] reads as incomplete. A whole stdin runs the
 * body; its status comes back both in the marker and as the exec's exit status (N8).
 *
 * Safety: HOME is a private directory; tmux is the repo's test shim on a private `TMUX_TMPDIR` (the Test task's); a
 * shim directory first on PATH answers `systemd-run`, `systemctl`, `loginctl` and `claude` with a logged refusal, so
 * even a launcher that ran a cut body (a mutant) could not reach the live user manager or start a claude. The restore
 * body is never run whole here (it would start a session); its equivalence is the byte-for-byte check in
 * `ExecArgumentSizeTest`.
 */
class StdinScriptShellTest {
    private val root = File(System.getProperty("java.io.tmpdir")).resolve("stdin-script-${System.nanoTime()}").also { it.mkdirs() }
    private val shims = File(root, "shims").also { d ->
        d.mkdirs()
        for (name in listOf("systemd-run", "systemctl", "loginctl", "claude")) {
            File(d, name).apply { writeText("#!/bin/sh\necho \"$name \$*\" >> '${root.absolutePath}/shim.log'\nexit 1\n"); setExecutable(true) }
        }
    }

    @AfterTest fun tearDown() { safeDeleteTree(root) }

    private class Ran(val out: String, val exit: Int)

    private fun home(tag: String) = File(root, "home-$tag").also { File(it, ".claude-remote").mkdirs() }

    /** [cmd] as `<shell> -c cmd` with [stdin], HOME = [home]. */
    private fun run(shell: String, cmd: String, stdin: ByteArray, home: File): Ran {
        val p = ProcessBuilder(shell, "-c", cmd).directory(home).redirectErrorStream(false).also {
            val env = it.environment()
            env["HOME"] = home.absolutePath
            env["PATH"] = shims.absolutePath + File.pathSeparator + env["PATH"].orEmpty()
            it.redirectError(ProcessBuilder.Redirect.DISCARD)
        }.start()
        val writer = Thread { try { p.outputStream.use { o -> o.write(stdin) } } catch (_: java.io.IOException) {} }.also { it.start() }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); error("timeout: $shell") }
        writer.join(5_000)
        return Ran(out, p.exitValue())
    }

    /** Every file below [home] with its content: what a cut script must leave exactly as it was. */
    private fun tree(home: File): Map<String, String> =
        home.walkTopDown().filter { it.isFile }.associate { it.relativeTo(home).path to it.readText() }.toSortedMap()

    private val shells: List<String> by lazy {
        val zsh = listOf("/usr/bin/zsh", "/bin/zsh").firstOrNull { File(it).canExecute() }
        if (zsh == null) println("StdinScriptShellTest: zsh is not installed here (nor on the box) — the zsh login shell is SKIPPED")
        listOfNotNull("bash", "dash", zsh)
    }

    private val name = "claude-server-claude-remote--relay-design-davka-1"

    private val scripts: Map<String, String> by lazy {
        mapOf(
            "close" to ManifestCommands.closeCommand(name, durable = true, by = "app:android-706cbd7b"),
            "close facts" to CloseFactsCommands.closeFactsCommand(name, CloseFactsCommands.selfMarkerFor(name, "android-706cbd7b")),
            "restore" to SessionRestore.restoreCommand(ClosedRecord(
                1, "2026-11-06T17:20:03Z", name, "11111111-2222-3333-4444-555555555555", "aaaaaaaa-2222-3333-4444-555555555555",
                null, "/tmp/nowhere-folder", "/tmp/nowhere-folder", "hanakl-nekrachni-cz", "SONNET", ClaudeMode.NORMAL.name,
                emptyList(), null, null, null, null, "app:android-706cbd7b", true, null,
            )),
            "cr-session install" to SessionOpsScript.INSTALL_COMMAND,
        )
    }

    /**
     * Every byte of the last 256, every 64th before: the cut lengths of a stdin of [size] bytes. Excluded: the whole
     * stdin, and the one cut that loses only its final newline — the end line has arrived whole then, `$(cat)` drops a
     * trailing newline anyway, and the whole body runs ([aStdinThatLostOnlyItsFinalNewlineIsWhole]).
     */
    private fun cuts(size: Int): List<Int> = ((0 until size step 64) + ((size - 256).coerceAtLeast(0) until size - 1)).distinct().sorted()

    @Test fun aStdinCutAnywhereRunsNothingInAnyLoginShell() {
        val counts = mutableListOf<String>()
        for ((what, script) in scripts) {
            val stdin = StdinScript.stdin(script)
            for (shell in shells) {
                val h = home("$what-$shell".replace(' ', '_'))
                val before = tree(h)
                val cut = cuts(stdin.size)
                for (n in cut) {
                    val r = run(shell, StdinScript.EXEC, stdin.copyOf(n), h)
                    assertEquals(StdinScript.TRUNCATED + "\n", r.out, "$what, $shell, cut at $n of ${stdin.size}: nothing ran")
                    assertEquals(97, r.exit, "$what, $shell, cut at $n: exit 97")
                    assertEquals(StdinScript.Outcome.Incomplete, StdinScript.parse(r.out), "$what, $shell, cut at $n: incomplete")
                }
                assertEquals(before, tree(h), "$what, $shell: ${cut.size} cut stdins wrote nothing to HOME")
                counts += "$what/$shell=${cut.size}"
            }
        }
        val shimLog = File(root, "shim.log")
        assertTrue(!shimLog.exists(), "no cut script reached systemd-run, systemctl, loginctl or claude: ${shimLog.takeIf { it.exists() }?.readText()}")
        println("StdinScriptShellTest cuts: $counts")
    }

    @Test fun aStdinThatLostOnlyItsFinalNewlineIsWhole() {
        for (shell in shells) {
            val stdin = StdinScript.stdin("echo whole")
            val r = run(shell, StdinScript.EXEC, stdin.copyOf(stdin.size - 1), home("nl-$shell"))
            assertEquals(StdinScript.Outcome.Completed(0, "whole\n"), StdinScript.parse(r.out), "$shell: ${r.out}")
        }
    }

    /** N8: the body's status reaches the marker AND the exec's exit status; an `exit` inside the body still prints the marker. */
    @Test fun theBodysStatusComesBackInTheMarkerAndAsTheExitStatus() {
        for (shell in shells) {
            val h = home("status-$shell")
            val r = run(shell, StdinScript.EXEC, StdinScript.stdin("N='a b'; printf 'out %s' \"\$N\"; exit 3"), h)
            assertEquals(3, r.exit, "$shell: the exec exits with the body's status")
            assertEquals(StdinScript.Outcome.Completed(3, "out a b"), StdinScript.parse(r.out), "$shell: ${r.out}")
            val ok = run(shell, StdinScript.EXEC, StdinScript.stdin("echo fine"), h)
            assertEquals(0, ok.exit)
            assertEquals(StdinScript.Outcome.Completed(0, "fine\n"), StdinScript.parse(ok.out), "$shell: ${ok.out}")
            // the body cannot read the stream behind it: its stdin is /dev/null
            val read = run(shell, StdinScript.EXEC, StdinScript.stdin("cat; echo after"), h)
            assertEquals(StdinScript.Outcome.Completed(0, "after\n"), StdinScript.parse(read.out), "$shell: ${read.out}")
        }
    }

    /** The launcher as a login shell gets it (`sh`) and its inner script (`bash`): shellcheck, where installed, finds nothing. */
    @Test fun shellcheckFindsNothingInTheLauncher() {
        if (listOf("/usr/bin/shellcheck", "/bin/shellcheck").none { File(it).canExecute() }) {
            println("StdinScriptShellTest: shellcheck not installed — skipping shellcheckFindsNothingInTheLauncher")
            return
        }
        val inner = Regex("^bash -c '(.*)'$").find(StdinScript.EXEC)!!.groupValues[1]
        for ((shell, text) in listOf("sh" to StdinScript.EXEC, "bash" to inner)) {
            val f = File(root, "launcher.$shell").apply { writeText(text + "\n") }
            val p = ProcessBuilder("shellcheck", "-s", shell, f.absolutePath).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(30, TimeUnit.SECONDS))
            assertEquals(0, p.exitValue(), "shellcheck -s $shell:\n$out")
        }
    }

    @Test fun anAnswerWithoutTheMarkerIsIncomplete() {
        assertEquals(StdinScript.Outcome.Incomplete, StdinScript.parse(""))
        assertEquals(StdinScript.Outcome.Incomplete, StdinScript.parse("CR_KILL=0\n"), "a body's output alone")
        assertEquals(StdinScript.Outcome.Incomplete, StdinScript.parse("x\n${StdinScript.MARK}"), "a marker without its status")
        assertEquals(StdinScript.Outcome.Incomplete, StdinScript.parse("x\n${StdinScript.MARK}0\nmore\n"), "something after the marker")
        assertEquals(StdinScript.Outcome.Completed(0, "Welcome\nx"), StdinScript.parse("Welcome\nx\n${StdinScript.MARK}0\n"), "a banner is kept")
    }

    /**
     * Equivalence with the old way (the script as the login shell's command): the close writes the same tombstone and
     * prints the same kill line; the facts answer the same; the install writes the same `cr-session`, whose SHA-256 is
     * [SessionOpsScript.SCRIPT_SHA256] (kritika D2 N11: the bodies and their SHA are unchanged, only the delivery).
     */
    @Test fun aWholeScriptDoesWhatTheOldArgumentDid() {
        fun norm(s: String) = s.replace(Regex("[0-9]{9,}"), "<t>").replace(Regex("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ"), "<iso>")
        for ((what, script) in scripts) {
            if (what == "restore") continue    // would start a session: byte equivalence only (ExecArgumentSizeTest)
            for (shell in shells) {
                val old = home("old-$what-$shell".replace(' ', '_'))
                val new = home("new-$what-$shell".replace(' ', '_'))
                val a = run("bash", script, ByteArray(0), old)
                val b = run(shell, StdinScript.EXEC, StdinScript.stdin(script), new)
                val done = StdinScript.parse(b.out)
                assertTrue(done is StdinScript.Outcome.Completed, "$what, $shell: completed: ${b.out.takeLast(300)}")
                assertEquals(a.exit, done.status, "$what, $shell: the same status")
                assertEquals(a.exit, b.exit, "$what, $shell: and the exec's exit status")
                val outA = a.out.lines().filterNot { it.startsWith("TOKEN ") }.joinToString("\n")
                val outB = done.out.lines().filterNot { it.startsWith("TOKEN ") }.joinToString("\n")
                assertEquals(norm(outA), norm(outB), "$what, $shell: the same output")
                assertEquals(tree(old).mapValues { norm(it.value) }, tree(new).mapValues { norm(it.value) }, "$what, $shell: the same files")
                if (what == "cr-session install") {
                    val f = File(new, ".claude-remote/bin/cr-session")
                    val sha = java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
                    assertEquals(SessionOpsScript.SCRIPT_SHA256, sha, "$shell: cr-session written from stdin is the script byte for byte")
                    assertTrue("SESSIONOPS_INSTALLED" in done.out, done.out)
                }
                if (what == "close") assertTrue(File(new, ".claude-remote/forgotten").exists(), "$shell: the tombstone was written")
            }
        }
        assertTrue(!File(root, "shim.log").exists(), "nothing reached the shims: ${File(root, "shim.log").takeIf { it.exists() }?.readText()}")
    }
}
