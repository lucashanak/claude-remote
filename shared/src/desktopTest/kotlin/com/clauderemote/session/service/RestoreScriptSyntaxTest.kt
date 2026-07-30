package com.clauderemote.session.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression guard for [SessionPersistenceService.INSTALL_RESTORE_COMMAND]:
 * the ~600-line bash installer embedded as a Kotlin raw string, which in turn
 * heredocs two more scripts (restore.sh, drift.sh) onto the user's server.
 * This is the most crash-prone code in the project (14 revisions, one of
 * which SIGSEGV'd the user's entire tmux server) and nothing previously
 * validated it — a shell typo shipped straight to production servers.
 *
 * Lives in `desktopTest` (not `commonTest`) because it shells out to `bash`
 * and writes temp files — an Android unit test has no usable bash.
 *
 * IMPORTANT: `bash -n` on the OUTER script does NOT validate heredoc BODIES
 * (they're just quoted string literals to the outer parser), so the two
 * inner scripts must be extracted and checked separately.
 */
class RestoreScriptSyntaxTest {

    private val script = SessionPersistenceService.INSTALL_RESTORE_COMMAND

    // --- bash/shellcheck availability probes (portability guards only) ---

    private fun bashAvailable(): Boolean = try {
        ProcessBuilder("bash", "-c", "exit 0").start().waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun shellcheckAvailable(): Boolean = try {
        ProcessBuilder("shellcheck", "--version").start().waitFor() == 0
    } catch (_: Exception) {
        false
    }

    // --- heredoc extraction ---

    /**
     * Extracts the body of a `cat > "$X" <<'DELIMITER' ... DELIMITER` heredoc
     * from [script]. The terminator must be alone at the start of a line
     * (true for both RESTORE_EOF and DRIFT_EOF) — this mirrors how bash
     * itself recognizes the end of a heredoc.
     */
    private fun extractHeredoc(delimiter: String): String {
        val startMarker = "<<'$delimiter'"
        val startIdx = script.indexOf(startMarker)
        if (startIdx < 0) fail("heredoc start marker $startMarker not found in installer script — delimiter may have been renamed")
        val bodyStart = script.indexOf('\n', startIdx) + 1
        if (bodyStart <= 0) fail("no newline found after heredoc start marker $startMarker")
        val endMarker = "\n$delimiter\n"
        val endIdx = script.indexOf(endMarker, bodyStart)
        if (endIdx < 0) fail("heredoc terminator '$delimiter' not found on its own line after offset $bodyStart — delimiter may have been renamed")
        // endIdx points at the '\n' that precedes the terminator line; +1
        // keeps that trailing newline as part of the body, excludes the
        // terminator itself.
        return script.substring(bodyStart, endIdx + 1)
    }

    // --- bash -n syntax checking ---

    private fun contextAround(body: String, line: Int, radius: Int = 3): String {
        val lines = body.lines()
        val from = (line - 1 - radius).coerceAtLeast(0)
        val to = (line - 1 + radius).coerceAtMost(lines.lastIndex)
        return (from..to).joinToString("\n") { i -> "${i + 1}: ${lines[i]}" }
    }

    private fun assertBashSyntaxValid(body: String, label: String) {
        val tmp = File.createTempFile("restore-script-syntax", ".sh")
        tmp.deleteOnExit()
        try {
            tmp.writeText(body)
            val proc = ProcessBuilder("bash", "-n", tmp.absolutePath).start()
            val stderr = proc.errorStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0) {
                val lineNum = Regex("""line (\d+)""").find(stderr)?.groupValues?.get(1)?.toIntOrNull()
                val context = lineNum?.let { "\nContext around line $it:\n${contextAround(body, it)}" } ?: ""
                fail("`bash -n` failed for $label (exit=$exit):\n$stderr$context")
            }
        } finally {
            tmp.delete()
        }
    }

    // --- tests ---

    /**
     * Regression: the outer installer (~600 lines of bash building two more
     * scripts via heredoc) must itself be syntactically valid bash. This
     * catches a broken `if`/`case`/quote in the OUTER shell — the part that
     * runs every time the app connects, before either inner script is even
     * written.
     */
    @Test
    fun outerInstallerScript_passesBashSyntaxCheck() {
        if (!bashAvailable()) {
            println("bash not on PATH — skipping outerInstallerScript_passesBashSyntaxCheck")
            return
        }
        assertBashSyntaxValid(script, "outer installer (INSTALL_RESTORE_COMMAND)")
    }

    /**
     * Regression: `bash -n` on the outer script does NOT reach into the
     * RESTORE_EOF heredoc body (it's just a string literal to the outer
     * parser) — so restore.sh, which recreates every tmux+claude session on
     * boot, must be extracted and checked on its own. This is the exact class
     * of bug that shipped a broken restore.sh straight to production before.
     *
     * The size guard runs FIRST and is load-bearing: `bash -n ""` exits 0, so
     * without it a future rename of the RESTORE_EOF delimiter would make the
     * extraction regex silently match nothing and this test would keep
     * "passing" while checking an empty string.
     */
    @Test
    fun restoreDotSh_extractsNonTrivialBody_andPassesBashSyntaxCheck() {
        val body = extractHeredoc("RESTORE_EOF")
        val nonBlankLines = body.lines().count { it.isNotBlank() }
        assertTrue(
            nonBlankLines > 50,
            "restore.sh heredoc body has only $nonBlankLines non-blank lines — RESTORE_EOF delimiter " +
                "may have been renamed, making extraction silently match nothing"
        )
        if (!bashAvailable()) {
            println("bash not on PATH — skipping restore.sh syntax check")
            return
        }
        assertBashSyntaxValid(body, "restore.sh (RESTORE_EOF heredoc body)")
    }

    /**
     * Same as above for drift.sh, the per-minute self-heal/reconcile daemon.
     * Size guard first for the same reason: prevents a silently-empty
     * extraction from vacuously passing `bash -n`.
     */
    @Test
    fun driftDotSh_extractsNonTrivialBody_andPassesBashSyntaxCheck() {
        val body = extractHeredoc("DRIFT_EOF")
        val nonBlankLines = body.lines().count { it.isNotBlank() }
        assertTrue(
            nonBlankLines > 50,
            "drift.sh heredoc body has only $nonBlankLines non-blank lines — DRIFT_EOF delimiter " +
                "may have been renamed, making extraction silently match nothing"
        )
        if (!bashAvailable()) {
            println("bash not on PATH — skipping drift.sh syntax check")
            return
        }
        assertBashSyntaxValid(body, "drift.sh (DRIFT_EOF heredoc body)")
    }

    /**
     * Regression: both restore.sh and drift.sh gate a full rewrite behind
     * `grep -q "$MARKER" <existing file>`, where MARKER is
     * "claude-remote-restore-vN". Each script's own header lists a
     * marker-compat comment (`# marker-compat (do NOT remove): ...v6 v7 ...`)
     * so an OLDER already-installed client's grep for ITS OWN vN marker still
     * matches the NEWLY installed file and takes the no-op path, instead of
     * re-running the daemon-reload/enable reinstall that has correlated with
     * the tmux-server death.
     *
     * If MARKER is bumped to vN+1 without adding vN+1 to the compat list,
     * a client running vN+1 would never find its own marker in a file
     * written by an older peer, endlessly re-triggering reinstall. This test
     * derives the expected range from the script text (6..current MARKER)
     * rather than hardcoding "v14", so it keeps catching that mistake across
     * future version bumps. 6 is hardcoded as the lower bound because that's
     * where the marker-compat scheme itself began (v6..v14 all present today).
     */
    @Test
    fun markerVersion_isPresentAndCompatListIsMonotonicComplete() {
        val marker = Regex("""MARKER="claude-remote-restore-v(\d+)"""")
            .find(script)?.groupValues?.get(1)?.toIntOrNull()
            ?: fail("could not find MARKER=\"claude-remote-restore-vN\" in the installer script")

        val compatLines = Regex("""marker-compat \(do NOT remove\):\s*(.+)""")
            .findAll(script)
            .map { it.groupValues[1].trim() }
            .toList()
        assertTrue(
            compatLines.isNotEmpty(),
            "no '# marker-compat (do NOT remove): ...' line found — cannot verify restore.sh/drift.sh " +
                "self-update compatibility"
        )
        // Both restore.sh and drift.sh carry their own compat comment.
        assertEquals(2, compatLines.size, "expected exactly 2 marker-compat lines (restore.sh + drift.sh), found ${compatLines.size}: $compatLines")

        val expected = (6..marker).toList()
        compatLines.forEachIndexed { idx, line ->
            val versions = Regex("""claude-remote-restore-v(\d+)""")
                .findAll(line)
                .map { it.groupValues[1].toInt() }
                .toList()
            assertEquals(
                expected, versions,
                "marker-compat list #$idx is not the monotonic/complete range v6..v$marker " +
                    "(current MARKER=v$marker): $line"
            )
        }
    }

    /**
     * Regression: this repo has a documented bug where tmux's default
     * PREFIX-matching `-t NAME` target syntax attached a client to the WRONG
     * session whenever one tmux session name was a prefix of another. Every
     * `has-session` call in the installer must use tmux's EXACT-match target
     * syntax (`-t="name"`, i.e. `=name`) instead.
     */
    @Test
    fun hasSessionCalls_useExactMatchTargetSyntax() {
        val exactCount = Regex("""tmux has-session -t="""").findAll(script).count()
        assertTrue(
            exactCount > 0,
            "no `tmux has-session -t=\"...\"` (exact-match) calls found in the installer — " +
                "extraction pattern may be stale"
        )
        val nonExact = Regex("""tmux has-session -t(?!=)\S*""").findAll(script).map { it.value }.toList()
        assertTrue(
            nonExact.isEmpty(),
            "found tmux has-session call(s) NOT using exact-match target syntax (-t=\"name\"): $nonExact — " +
                "prefix-matching -t can attach a client to the WRONG session (documented regression)"
        )
    }

    /**
     * Optional extra lint pass, genuinely skip-if-absent: shellcheck is not
     * installed in the dev sandbox, so this must never fail locally when
     * it's simply missing — only report real findings when it IS present
     * (e.g. on a future CI image that installs it).
     */
    @Test
    fun shellcheckPassesOnAllScripts_ifAvailable() {
        if (!shellcheckAvailable()) {
            println("shellcheck not on PATH — skipping optional shellcheck pass")
            return
        }
        val scripts = listOf(
            "outer installer" to script,
            "restore.sh" to extractHeredoc("RESTORE_EOF"),
            "drift.sh" to extractHeredoc("DRIFT_EOF"),
        )
        for ((label, body) in scripts) {
            val tmp = File.createTempFile("shellcheck", ".sh")
            tmp.deleteOnExit()
            try {
                tmp.writeText(body)
                // `-s bash` is required, not cosmetic: the OUTER installer is a
                // command STRING we exec over SSH, so it has no shebang and
                // shellcheck fails it with SC2148 ("target shell unknown") —
                // verified against shellcheck 0.10.0. Naming the shell is the
                // correct fix rather than suppressing the check: both inner
                // scripts declare `#!/usr/bin/env bash`, so bash is genuinely
                // the target for all three.
                val proc = ProcessBuilder("shellcheck", "-s", "bash", "-S", "error", tmp.absolutePath).start()
                val stdout = proc.inputStream.bufferedReader().readText()
                val stderr = proc.errorStream.bufferedReader().readText()
                val exit = proc.waitFor()
                assertEquals(0, exit, "shellcheck -S error failed for $label:\n$stdout\n$stderr")
            } finally {
                tmp.delete()
            }
        }
    }
}
