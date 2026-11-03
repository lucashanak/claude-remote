package com.clauderemote.session.service

import com.clauderemote.testsupport.safeDeleteTree
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The server-side text of hooks v2 and of the bootstrap, parsed by the real shells and
 * python. A syntax error in any of it is invisible to the Kotlin compiler and lands in
 * every Claude session on the box (settings reload while running), so it is parsed here.
 * Nothing in this file executes the scripts' logic; see `HookMergeTest` and
 * `HookScriptIntegrationTest` for that. Runs only `bash -n`, `sh -n`, `py_compile`.
 */
class HookCommandsSyntaxTest {

    private fun tool(vararg probe: String): Boolean = try {
        ProcessBuilder(*probe).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    /** Output (stdout+stderr) and exit code of [argv] fed [stdin]; cwd is a fresh temp dir (never the source tree). */
    private fun run(vararg argv: String, stdin: String = ""): Pair<Int, String> {
        val dir = java.nio.file.Files.createTempDirectory("hooksyntax").toFile()
        try {
            val p = ProcessBuilder(*argv).directory(dir).redirectErrorStream(true).start()
            p.outputStream.use { it.write(stdin.toByteArray()) }
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); fail("timeout: ${argv.toList()}") }
            return p.exitValue() to out
        } finally {
            safeDeleteTree(dir)
        }
    }

    private fun scriptFile(text: String, suffix: String): File =
        File.createTempFile("hooksyntax", suffix).apply { writeText(text); deleteOnExit() }

    @Test fun hookEvScriptParsesInBashAndInDash() {
        val nonBlank = HookCommands.HOOK_EV_SCRIPT.lines().count { it.isNotBlank() }
        assertTrue(nonBlank > 60, "hook-ev.sh has only $nonBlank non-blank lines: is it still the script?")
        assertTrue(tool("bash", "-c", "true"), "bash must exist")
        val f = scriptFile(HookCommands.HOOK_EV_SCRIPT, ".sh")
        for (shell in listOf("bash", "sh")) {
            val (code, out) = run(shell, "-n", f.absolutePath)
            assertEquals(0, code, "$shell -n hook-ev.sh:\n$out")
        }
    }

    @Test fun hookEvScriptIsPosixShNotBash() {
        // It runs from a `sh -c` hook command; the shebang must say sh and a bashism would break under dash.
        assertTrue(HookCommands.HOOK_EV_SCRIPT.startsWith("#!/bin/sh\n"))
        for (bashism in listOf("[[ ", "<<<", "declare ", "local ", "function ")) {
            assertFalse(HookCommands.HOOK_EV_SCRIPT.contains(bashism), "bashism `$bashism` in hook-ev.sh")
        }
    }

    @Test fun hookEvScriptHeaderCarriesTheMarkerAndCompatLineWithinTheFirst300Bytes() {
        val head = HookCommands.HOOK_EV_SCRIPT.toByteArray().copyOf(300).toString(Charsets.UTF_8)
        assertTrue(head.contains("claude-remote-hookev-v${HookCommands.SCRIPT_VERSION}"), head)
        assertTrue(head.contains("marker-compat"), head)
    }

    @Test fun mergePythonCompilesInEveryMode() {
        if (!tool("python3", "-c", "pass")) { println("python3 not on PATH: skipping"); return }
        for (mode in listOf("install", "remove", "status")) {
            val f = scriptFile(HookCommands.hooksV2Py(mode), ".py")
            val (code, out) = run("python3", "-I", "-c", "import sys; compile(open(sys.argv[1], encoding='utf-8').read(), 'merge', 'exec')", f.absolutePath)
            assertEquals(0, code, "merge python ($mode) does not compile:\n$out")
        }
    }

    @Test fun composedCommandsParseInBash() {
        val commands = mapOf(
            "ENSURE_HOOKS_V2" to HookCommands.ENSURE_HOOKS_V2_COMMAND,
            "REMOVE_HOOKS_V2" to HookCommands.REMOVE_HOOKS_V2_COMMAND,
            "STATUS_HOOKS_V2" to HookCommands.STATUS_HOOKS_V2_COMMAND,
            "bootstrap(read)" to BootstrapScript.build(installV2 = false),
            "bootstrap(install)" to BootstrapScript.build(installV2 = true),
            "flags only" to BootstrapScript.FLAGS_ONLY_COMMAND,
            "watcher(legacy)" to buildNotifyWatcherCommand(withHooks = false),
            "watcher(hooks)" to buildNotifyWatcherCommand(withHooks = true),
            "legacy Stop ensure" to LEGACY_STOP_HOOK_COMMAND,
        )
        for ((name, cmd) in commands) {
            val f = scriptFile(cmd, ".sh")
            val (code, out) = run("bash", "-n", f.absolutePath)
            assertEquals(0, code, "bash -n $name:\n$out")
        }
    }

    @Test fun embeddedHeredocsAreClosedOnTheirOwnLine() {
        val install = HookCommands.ENSURE_HOOKS_V2_COMMAND.lines()
        for (delim in listOf("CRHV2_SH", "CRHV2_PY")) {
            assertEquals(1, install.count { it == delim }, "$delim terminator on its own line")
            assertTrue(install.any { it.contains("<<'$delim'") })
        }
        val boot = BootstrapScript.build(true).lines()
        assertEquals(1, boot.count { it == "CRB_PY" })
        assertEquals(1, boot.count { it == "CRSD_EOF" })
        // the hook-ev.sh heredoc nested inside the installer's heredoc
        assertTrue(HookCommands.HOOK_EV_SCRIPT.lines().count { it == "CRPY" } == 1)
    }

    @Test fun settingsCommandNeverLetsStdoutThroughAndAlwaysExitsZeroAndHonoursTheOffSwitch() {
        for ((_, _, ev) in HookCommands.WANTED) {
            val c = HookCommands.entryCommand(ev)
            assertTrue(c.contains(">/dev/null 2>&1"), "stdout of UserPromptSubmit / SessionStart goes into the model's context: $c")
            assertTrue(c.trimEnd().endsWith("exit 0"), "exit 2 would block the prompt or the tool: $c")
            assertTrue(c.contains("hookev.off"), "the off switch must act without a settings rewrite: $c")
            assertTrue(c.contains("${HookCommands.ENTRY_MARKER} v${HookCommands.ENTRY_VERSION}"), c)
            assertTrue(c.startsWith(": ${HookCapContract.HOOKEV_MARKER}"), "CAP reads this marker: $c")
            assertTrue(!c.contains("claude-remote-notify"), "must not look like the legacy Stop entry: $c")
            val (code, out) = run("sh", "-n", "-c", c)
            assertEquals(0, code, "sh -n for $ev:\n$out")
        }
        assertEquals(
            listOf("work", "ask", "askdone", "perm", "stop", "start", "end"),
            HookCommands.WANTED.map { it.third },
        )
    }

    @Test fun legacyStopHookCommandIsByteForByteWhatOldClientsExpect() {
        // Old app builds rewrite the legacy Stop entry on ANY difference and the legacy ensure runs on
        // every connect, so changing even a space would make a mixed fleet flap forever. The hash is that
        // of the string at af72fe1 (computed from the source text there).
        val sha = MessageDigest.getInstance("SHA-256").digest(LEGACY_STOP_HOOK_COMMAND.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("b19b30707501fd7f0eb836added4f62bbf9c94228c7aa70adb4f30170de73129", sha)
    }

    @Test fun watcherCommandWithoutHooksIsTheLegacyCommandAndWithHooksAddsTheNewFiles() {
        val legacy = buildNotifyWatcherCommand(false)
        assertEquals(
            "echo claude-remote-clock \$(date +%s); mkdir -p ~/'.cache/claude-remote' && touch ~/'.cache/claude-remote/notify' && " +
                "chmod 600 ~/'.cache/claude-remote/notify'; " +
                "if [ \"\$(wc -l < ~/'.cache/claude-remote/notify')\" -gt 1000 ]; then " +
                "tail -n 200 ~/'.cache/claude-remote/notify' > ~/'.cache/claude-remote/notify'.rot && " +
                "mv ~/'.cache/claude-remote/notify'.rot ~/'.cache/claude-remote/notify' && chmod 600 ~/'.cache/claude-remote/notify'; fi; " +
                "tail -q -n 25 -F ~/'.cache/claude-remote/notify' '/tmp/claude-notify'",
            legacy,
        )
        val v2 = buildNotifyWatcherCommand(true)
        assertTrue(v2.startsWith(legacy.substringBefore("tail -q -n 25")), "the notify part and its rotation are unchanged")
        assertTrue(v2.endsWith("-F ~/'.cache/claude-remote/notify' '/tmp/claude-notify' ~/'.cache/claude-remote/events' ~/'.claude-remote/forget.log'"), v2)
        assertTrue(v2.contains("-mmin -1440"), "events are rotated at most once per 24 h")
    }

    @Test fun legacyNotifyPathStaysTailedForClaudeLocal() {
        assertTrue(buildNotifyWatcherCommand(false).contains("/tmp/claude-notify"))
        assertTrue(buildNotifyWatcherCommand(true).contains("/tmp/claude-notify"))
    }

    @Test fun v2ResultsAreParsedFromTheScriptTokens() {
        val r = HookCommands.parseV2("HOOKS_V2_FIXED\nACCOUNTS_MERGED=2\nHOOKS_V2_STATE=INSTALLED\nHOOKEV_OFF=1\n")
        assertEquals(HookCommands.V2Result.FIXED, r.result)
        assertEquals(HookCommands.V2State.INSTALLED, r.state)
        assertTrue(r.offSwitch)
        assertEquals(2, r.accountsMerged)
        assertEquals(HookCommands.V2Result.NEWER, HookCommands.parseV2("HOOKS_V2_NEWER\n").result)
        assertEquals(HookCommands.V2State.NEWER, HookCommands.parseV2("HOOKS_V2_NEWER\n").state)
        assertEquals(HookCommands.V2Result.SKIPPED_OFF, HookCommands.parseV2("HOOKS_V2_SKIPPED_OFF\nHOOKS_V2_STATE=ABSENT").result)
        assertEquals(HookCommands.V2State.UNKNOWN, HookCommands.parseV2("").state)
        assertEquals(HookCommands.V2Result.REMOVED, HookCommands.parseV2("HOOKS_V2_REMOVE_FIXED").result)
    }
}
