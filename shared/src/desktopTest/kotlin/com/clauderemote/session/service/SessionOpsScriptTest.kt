package com.clauderemote.session.service

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.session.ClaudeConfig
import com.clauderemote.testsupport.safeDeleteTree
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `cr-session` and the bodies it shares with the app (Jarvis F0, §15.9): parsed by bash and dash, linted by shellcheck
 * where it is installed, and checked to hold the app's own bodies byte for byte — the script must be the app's steps,
 * not a copy of them. Behaviour against a real tmux and drift lives in `SessionOpsIntegrationTest`.
 */
class SessionOpsScriptTest {

    private val name = "claude-server-my proj's tab"

    private fun run(vararg argv: String, home: File? = null, stdin: String = ""): Pair<Int, String> {
        val dir = java.nio.file.Files.createTempDirectory("crsession").toFile()
        try {
            val pb = ProcessBuilder(*argv).directory(dir).redirectErrorStream(true)
            if (home != null) pb.environment()["HOME"] = home.absolutePath
            val p = pb.start()
            p.outputStream.use { it.write(stdin.toByteArray()) }
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); fail("timeout: ${argv.toList()}") }
            return p.exitValue() to out
        } finally {
            safeDeleteTree(dir)
        }
    }

    private fun file(text: String): File = File.createTempFile("crsession", ".sh").apply { writeText(text); deleteOnExit() }

    private fun tempHome(): File = java.nio.file.Files.createTempDirectory("crsession-home").toFile()

    private val bodies: List<Pair<String, String>> by lazy {
        val rec = ClosedRecord(1, "2026-11-06T17:20:03Z", name, "11111111-2222-3333-4444-555555555555", "aaaaaaaa-2222-3333-4444-555555555555",
            null, "/w/my proj's", "/w", "acc", "OPUS", "YOLO", listOf("--dangerously-skip-permissions"), null, null, "t", 1, "jarvis", true, null)
        listOf(
            "script" to SessionOpsScript.SCRIPT,
            "install" to SessionOpsScript.INSTALL_COMMAND,
            "check" to SessionOpsScript.CHECK_COMMAND,
            "tombstoneAndRecord" to ManifestCommands.tombstoneAndRecord(name, durable = true, by = "app:x"),
            "close" to ManifestCommands.closeCommand(name, durable = true, by = "app:x"),
            "blocks" to CloseFactsCommands.BLOCKS_SH,
            "running" to "D=\"\$HOME/.claude-remote\"\n" + ManifestCommands.JSON_SH + "\n" + SessionRestore.RUNNING_SH,
            "closeRecord" to ManifestCommands.closeRecord(name, "prune", durable = false, reason = "it's"),
            "readClosed" to ManifestCommands.readClosed(50),
            "openedMarker" to "N=x; BY=jarvis; F=/w; A=; S=; TASK=\n" + ManifestCommands.openedMarkerBody(),
            "closeFacts" to CloseFactsCommands.closeFactsCommand(name, "android-1-x"),
            "restore" to SessionRestore.restoreCommand(rec),
            "restore fresh" to SessionRestore.restoreCommand(rec, tmuxName = "$name-2", freshSessionId = "bbbbbbbb-2222-3333-4444-555555555555"),
        )
    }

    @Test fun everyBodyParsesInBashAndDash() {
        for ((label, text) in bodies) for (shell in listOf("bash", "sh")) {
            val (code, out) = run(shell, "-n", file(text).absolutePath)
            assertEquals(0, code, "$shell -n $label:\n$out")
        }
    }

    @Test fun shellcheckFindsNoErrors() {
        if (run("bash", "-c", "command -v shellcheck").first != 0) {
            println("shellcheck not on PATH — skipping shellcheckFindsNoErrors")
            return
        }
        for ((label, text) in bodies) {
            val (code, out) = run("shellcheck", "-s", "bash", "-S", "error", file(text).absolutePath)
            assertEquals(0, code, "shellcheck -S error $label:\n$out")
        }
    }

    /** §15.9: the script contains the app's bodies byte for byte (one source of truth by construction). */
    @Test fun scriptHoldsTheAppsOwnBodies() {
        val s = SessionOpsScript.SCRIPT
        for ((label, body) in listOf(
            "tombstoneBody(durable)" to ManifestCommands.tombstoneBody(marker = true),
            "clearTombstoneBody" to ManifestCommands.clearTombstoneBody(),
            "closeRecordBody" to ManifestCommands.closeRecordBody(),
            "openedMarkerBody" to ManifestCommands.openedMarkerBody(),
            "closeFactsBody" to CloseFactsCommands.closeFactsBody(),
            "restoreBody" to SessionRestore.restoreBody(),
            "detachedCreateBody" to ClaudeConfig.detachedCreateBody(),
            "socketGuardHead" to ClaudeConfig.socketGuardHead(),
            "optionsPreludeBody" to ClaudeConfig.optionsPreludeBody(),
            "createBody" to ClaudeConfig.createBody(),
        )) assertContains(s, body, message = "cr-session lacks $label")
        // open and restore never kill: the builder's kill piece is not in the script at all
        assertFalse(s.contains(ClaudeConfig.killExistingBody()), "cr-session must never kill-session the name it creates")
        assertFalse(SessionRestore.restoreBody().contains("kill-session"), "a restore never kills")
    }

    /** The app's inline forms are the bodies with the name assigned first (§15.2). */
    @Test fun inlineFormsAreAssignmentPlusBody() {
        val q = ManifestCommands.sq(name)
        assertEquals("N=$q; " + ManifestCommands.tombstoneBody(marker = true), ManifestCommands.tombstone(name, durable = true))
        assertEquals("N=$q; " + ManifestCommands.tombstoneBody(marker = false), ManifestCommands.tombstone(name, durable = false))
        assertEquals("N='a/b'; " + ManifestCommands.tombstoneBody(marker = false), ManifestCommands.tombstone("a/b", durable = true))
        assertEquals("N=$q; " + ManifestCommands.clearTombstoneBody(), ManifestCommands.clearTombstone(name))
        assertTrue(ManifestCommands.tombstoneAndRecord(name, true, "app:x").startsWith(ManifestCommands.tombstone(name, true) + "; "))
    }

    /** R3 = 3c: the prelude sets only idempotent server options, never `window-size` (the tmux crash poison). */
    @Test fun noWindowSizeAnywhereTheScriptCreates() {
        assertFalse(ClaudeConfig.optionsPreludeBody().contains("window-size"))
        assertFalse(ClaudeConfig.createBody().contains("window-size"))
        assertFalse(SessionOpsScript.SCRIPT.contains("window-size"))
        assertTrue(ClaudeConfig.optionsPreludeBody().contains("set-option -g exit-empty off"))
    }

    /** `cr_cmd` builds the launch command [ClaudeConfig.buildLaunchCommand] builds, for every mode, model and account shape. */
    @Test fun scriptLaunchCommandMatchesTheAppsBuilder() {
        val sid = "11111111-2222-3333-4444-555555555555"
        val cases = buildList {
            for (mode in ClaudeMode.entries) for (model in ClaudeModel.entries) add(listOf("/home/u/p", mode.name, model.name, sid, "1", "acc"))
            add(listOf("/home/u/my proj's", "YOLO", "OPUS", sid, "0", ""))
            add(listOf("~", "NORMAL", "DEFAULT", "", "0", "default"))
            add(listOf("~/x y", "AUTO", "SONNET", sid, "1", "lukas@kontexta.cz"))
            add(listOf("~/", "YOLO", "LOCAL", sid, "0", "a'b"))
        }
        val script = buildString {
            append(SessionOpsScript.CLAUDE_CMD_SH).append('\n')
            for (c in cases) append("cr_cmd ").append(c.joinToString(" ") { ManifestCommands.sq(it) }).append("; echo\n")
        }
        val (code, out) = run("bash", file(script).absolutePath)
        assertEquals(0, code, out)
        val got = out.lines().dropLastWhile { it.isEmpty() }
        assertEquals(cases.size, got.size, out)
        for ((i, c) in cases.withIndex()) {
            val want = ClaudeConfig.buildLaunchCommand(
                c[0], ClaudeMode.valueOf(c[1]), ClaudeModel.valueOf(c[2]), c[3].ifEmpty { null }, c[4] == "1", c[5].ifEmpty { null },
            )
            assertEquals(want, got[i], "cr_cmd ${c.joinToString(" ")}")
        }
    }

    @Test fun versionAndUsage() {
        val f = file(SessionOpsScript.SCRIPT)
        val home = tempHome()
        try {
            assertEquals(0 to "cr-session v${SessionOpsScript.VERSION}\n", run("bash", f.absolutePath, "version", home = home))
            val (code, out) = run("bash", f.absolutePath, home = home)
            assertEquals(2, code)
            assertContains(out, "usage: cr-session")
            // input checks that never reach tmux
            assertEquals("INVALID name", run("bash", f.absolutePath, "open", "--name", "other-x", "--folder", "/", home = home).second.trim())
            assertEquals("INVALID name", run("bash", f.absolutePath, "open", "--name", "claude-server-a.b", "--folder", "/", home = home).second.trim())
            assertEquals("INVALID missing value for --folder", run("bash", f.absolutePath, "open", "--folder", home = home).second.trim())
            assertEquals("REFUSED_NAME", run("bash", f.absolutePath, "close", "other-x", "--token", "1.x", home = home).second.trim())
            // a name drift's manifest holds untombstoned is about to be self-healed: open must not race it (§15.15)
            File(home, ".claude-remote").mkdirs()
            File(home, ".claude-remote/sessions.restore.json").writeText("""[{"tmuxSessionName": "claude-server-heal"}]""")
            val heal = arrayOf("bash", f.absolutePath, "open", "--name", "claude-server-heal", "--folder", home.absolutePath)
            assertEquals(1 to "PENDING_SELF_HEAL\n", run(*heal, home = home))
            File(home, ".claude-remote/sessions.restore.json").writeText("[]")
            File(home, ".claude-remote/jarvis").mkdirs()
            File(home, ".claude-remote/jarvis/self").writeText("claude-server-jarvis\n")
            assertEquals("REFUSED_SELF", run("bash", f.absolutePath, "close", "claude-server-jarvis", "--token", "1.x", home = home).second.trim())
            File(home, ".claude-remote/jarvis/off").writeText("")
            assertEquals("REFUSED_OFF", run("bash", f.absolutePath, "close", "claude-server-x", "--token", "1.x", home = home).second.trim())
        } finally {
            safeDeleteTree(home)
        }
    }

    /**
     * A `tmux` stand-in for [home] (a script first on PATH, not tmux: nothing reaches a server, and `list-sessions`
     * answers 0 so the prelude never reaches for systemd-run). Every call goes to `~/tmux.log` as
     * `<1 if $STUB_NAME is tombstoned right now, else 0> <args>`; `has-session` says "no such session".
     */
    private fun tmuxRecorder(home: File): File {
        val bin = File(home, "stubbin").apply { mkdirs() }
        File(bin, "tmux").apply {
            writeText(
                "#!/usr/bin/env bash\n" +
                    "D=\"\$HOME/.claude-remote\"; t=0\n" +
                    "grep -qxF \"\$STUB_NAME\" \"\$D/forgotten\" 2>/dev/null && t=1\n" +
                    "[ -e \"\$D/forgotten.d/\$STUB_NAME\" ] && t=1\n" +
                    "echo \"\$t \$*\" >> \"\$HOME/tmux.log\"\n" +
                    "case \"\$*\" in *has-session*) exit 1 ;; esac\n" +
                    "exit 0\n",
            )
            setExecutable(true)
        }
        return bin
    }

    private fun runStubbed(home: File, name: String, vararg argv: String): Pair<Int, String> {
        val pb = ProcessBuilder(*argv).directory(home).redirectErrorStream(true)
        pb.environment()["HOME"] = home.absolutePath
        pb.environment()["STUB_NAME"] = name
        pb.environment()["PATH"] = tmuxRecorder(home).absolutePath + ":" + pb.environment()["PATH"]
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); fail("timeout: ${argv.toList()}") }
        return p.exitValue() to out
    }

    private fun tombstone(home: File, name: String) {
        val d = File(home, ".claude-remote").apply { mkdirs() }
        File(d, "forgotten.d").mkdirs(); File(d, "forgotten.d/$name").writeText("")
        File(d, "forgotten").writeText("$name\n")
    }

    private fun calls(home: File): List<String> = File(home, "tmux.log").readLines().also { File(home, "tmux.log").delete() }

    /** The pane's `new-session` calls (the prelude's anchor would be systemd-run's, never reached here). */
    private fun creates(calls: List<String>) = calls.filter { "new-session" in it && "__anchor__" !in it }

    /** `cr-session open` of a tombstoned name, every tmux call recorded. */
    private fun openOverATombstone(home: File): List<String> {
        val o = "claude-server-o"
        tombstone(home, o)
        val (rc, out) = runStubbed(home, o, "bash", file(SessionOpsScript.SCRIPT).absolutePath, "open", "--name", o, "--folder", home.absolutePath, "--wait", "1")
        assertEquals(0, rc, out)
        return calls(home).also { assertTrue(creates(it).isNotEmpty(), "open created nothing: $it") }
    }

    /** The app's inline restore of a tombstoned name, every tmux call recorded. */
    private fun restoreOverATombstone(home: File): List<String> {
        val r = "claude-server-r"
        tombstone(home, r)
        val sid = "77777777-2222-3333-4444-555555555555"
        File(home, ".claude/projects/x").mkdirs(); File(home, ".claude/projects/x/$sid.jsonl").writeText("{}\n")
        File(home, ".claude/.credentials.json").writeText("{}")
        val rec = ClosedRecord(1, "2026-11-06T17:20:03Z", r, sid, null, null, home.absolutePath, home.absolutePath, null, "SONNET", "AUTO",
            emptyList(), null, null, null, null, "app:x", true, null)
        val (rc, out) = runStubbed(home, r, "bash", "-c", SessionRestore.restoreCommand(rec, waitS = 1))
        assertEquals(0, rc, out)
        assertEquals(RestoreResult.Restored(null, null, registry = false, resumedId = sid), SessionRestore.parse(out), out)
        return calls(home).also { assertTrue(creates(it).isNotEmpty(), "restore created nothing: $it") }
    }

    /** rev-tests 26 (§15.15): open never runs `kill-session` — a taken name is EXISTS, never replaced. */
    @Test fun openNeverKills() {
        val home = tempHome()
        try {
            val calls = openOverATombstone(home)
            assertTrue(calls.none { "kill-session" in it }, "open killed: $calls")
        } finally {
            safeDeleteTree(home)
        }
    }

    /**
     * rev-tests 27 (rozpor 15): open lifts the tombstone BEFORE the pane exists — else drift subtracts the new name
     * and peers forget its tab.
     */
    @Test fun openClearsTombstoneBeforeSpawn() {
        val home = tempHome()
        try {
            val c = creates(openOverATombstone(home))
            assertTrue(c.all { it.startsWith("0 ") }, "open created the pane while the name was still tombstoned: $c")
        } finally {
            safeDeleteTree(home)
        }
    }

    /** rev-tests 28: the same for a restore (the app's inline body, which `cr-session restore` holds too), and no kill. */
    @Test fun restoreClearsTombstoneBeforeSpawn() {
        val home = tempHome()
        try {
            val calls = restoreOverATombstone(home)
            assertTrue(creates(calls).all { it.startsWith("0 ") }, "restore created the pane while the name was still tombstoned: $calls")
            assertTrue(calls.none { "kill-session" in it }, "restore killed: $calls")
        } finally {
            safeDeleteTree(home)
        }
    }

    /** `~/.claude-remote/jarvis/off` stops open and restore as it stops close, before anything else happens. */
    @Test fun theOffSwitchStopsOpenAndRestore() {
        val home = tempHome()
        try {
            File(home, ".claude-remote/jarvis").mkdirs()
            File(home, ".claude-remote/jarvis/off").writeText("")
            val f = file(SessionOpsScript.SCRIPT)
            val n = "claude-server-off"
            assertEquals(1 to "REFUSED_OFF\n", runStubbed(home, n, "bash", f.absolutePath, "open", "--name", n, "--folder", home.absolutePath, "--wait", "1"))
            assertEquals(1 to "REFUSED_OFF\n", runStubbed(home, n, "bash", f.absolutePath, "restore", n))
            assertFalse(File(home, "tmux.log").exists(), "tmux was asked: ${File(home, "tmux.log").takeIf { it.exists() }?.readText()}")
        } finally {
            safeDeleteTree(home)
        }
    }

    /**
     * `has-session` that times out (rc 124: a tmux server that hangs) says nothing about the name: open must answer
     * FAILED and leave the name's tombstone where it is, not take "no session" and lift it. The tmux here is a stand-in
     * script first on PATH (not tmux), so nothing reaches a server.
     */
    @Test fun openTreatsATimedOutHasSessionAsAFailureNotAsAFreeName() {
        val home = tempHome()
        try {
            val bin = File(home, "stubbin").apply { mkdirs() }
            File(bin, "tmux").apply { writeText("#!/usr/bin/env bash\necho \"\$*\" >> \"\$HOME/tmux.log\"\nexit 124\n"); setExecutable(true) }
            val d = File(home, ".claude-remote").apply { mkdirs() }
            File(d, "forgotten.d").mkdirs(); File(d, "forgotten.d/claude-server-x").writeText("")
            File(d, "forgotten").writeText("claude-server-x\n")
            val f = file(SessionOpsScript.SCRIPT)
            val pb = ProcessBuilder("bash", f.absolutePath, "open", "--name", "claude-server-x", "--folder", home.absolutePath)
                .directory(home).redirectErrorStream(true)
            pb.environment()["HOME"] = home.absolutePath
            pb.environment()["PATH"] = bin.absolutePath + ":" + pb.environment()["PATH"]
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue(p.waitFor(60, TimeUnit.SECONDS))
            assertEquals(1 to "FAILED tmux-timeout\n", p.exitValue() to out)
            assertTrue(File(d, "forgotten.d/claude-server-x").isFile, "the tombstone stays")
            assertTrue(File(d, "forgotten").readLines().contains("claude-server-x"))
            assertEquals(1, File(home, "tmux.log").readLines().size, "nothing after the has-session: ${File(home, "tmux.log").readText()}")
        } finally {
            safeDeleteTree(home)
        }
    }

    /** `jarvis-ops.log` is cut to its last [SessionOpsScript.OPS_LOG_MAX_LINES] lines on every write, as closed.jsonl is. */
    @Test fun theOpsLogIsCutToItsLastLines() {
        val home = tempHome()
        try {
            val logFn = SessionOpsScript.SCRIPT.lines().let { l ->
                val from = l.indexOfFirst { it.startsWith("log() {") }
                l.subList(from, l.subList(from, l.size).indexOfFirst { it == "}" } + from + 1).joinToString("\n")
            }
            val d = File(home, ".claude-remote").apply { mkdirs() }
            val max = SessionOpsScript.OPS_LOG_MAX_LINES
            File(d, "jarvis-ops.log").writeText((1..max).joinToString("") { "old $it\n" })
            val (rc, out) = run("bash", "-c", "D=\"\$HOME/.claude-remote\"\n$logFn\nlog newest; log newer", home = home)
            assertEquals(0, rc, out)
            val lines = File(d, "jarvis-ops.log").readLines()
            assertEquals(max, lines.size)
            assertEquals("old 3", lines.first())
            assertTrue(lines.last().endsWith("] newer"), lines.last())
            assertTrue(d.listFiles().orEmpty().none { it.name.startsWith("jarvis-ops.log.tmp") }, "no temp file left")
        } finally {
            safeDeleteTree(home)
        }
    }

    private fun versionsFile(): File {
        val rel = "src/desktopTest/resources/golden/cr-session-versions.txt"
        return listOf(File(rel), File("shared/$rel")).firstOrNull { it.parentFile.parentFile.isDirectory } ?: File(rel)
    }

    /**
     * Every content of the script has its own VERSION: a server keeps an intact file of the same version, so two
     * builds with one VERSION and different scripts would rewrite each other's copy on every bootstrap (and a newer
     * script with an unchanged number would never reach a server that has the old one). The file lists every
     * released `<VERSION> <SHA-256>`; a changed script fails here until VERSION is raised and its line added.
     */
    @Test fun everyScriptContentHasItsOwnVersion() {
        val f = versionsFile()
        val pairs = f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map {
            val t = it.split(Regex("\\s+")); t[0].toInt() to t[1]
        }
        assertEquals(pairs.size, pairs.map { it.first }.toSet().size, "one line per version: ${f.absolutePath}")
        val mine = pairs.firstOrNull { it.first == SessionOpsScript.VERSION }
        if (mine == null) fail("add the line `${SessionOpsScript.VERSION} ${SessionOpsScript.SCRIPT_SHA256}` to ${f.absolutePath}")
        assertEquals(
            mine.second, SessionOpsScript.SCRIPT_SHA256,
            "cr-session changed but VERSION is still ${SessionOpsScript.VERSION}: raise SessionOpsScript.VERSION and add its line to ${f.absolutePath}",
        )
        assertEquals(pairs.maxOf { it.first }, SessionOpsScript.VERSION, "VERSION never goes back")
    }

    /** The installer: writes, then leaves an intact copy alone, rewrites a damaged one, never downgrades a newer one. */
    @Test fun installerWritesKeepsRepairsAndNeverDowngrades() {
        val home = tempHome()
        try {
            val f = File(home, ".claude-remote/bin/cr-session")
            fun install() = run("bash", "-c", SessionOpsScript.INSTALL_COMMAND, home = home)
            assertEquals(0 to "SESSIONOPS_INSTALLED\n", install())
            assertEquals(SessionOpsScript.SCRIPT + "\n", f.readText())
            assertTrue(f.canExecute())
            assertEquals(0 to "SESSIONOPS_PRESENT\n", install())
            f.appendText("# damaged\n")
            assertEquals(0 to "SESSIONOPS_INSTALLED\n", install())
            assertEquals(SessionOpsScript.SCRIPT + "\n", f.readText())
            val newer = "#!/usr/bin/env bash\n# ${SessionOpsScript.MARKER}${SessionOpsScript.VERSION + 1}\n"
            f.writeText(newer)
            assertEquals(0 to "SESSIONOPS_PRESENT\n", install())
            assertEquals(newer, f.readText())
        } finally {
            safeDeleteTree(home)
        }
    }

    /**
     * The bootstrap carries only the check, not the 25 KB script (that goes in its own exec when the check says so),
     * and reports the check's line apart from the streamd one.
     */
    @Test fun bootstrapCarriesTheCheckNotTheScript() {
        val b = BootstrapScript.build(installV2 = false)
        assertContains(b, SessionOpsScript.CHECK_COMMAND)
        assertFalse(b.contains("CRSO_SH"), "no installer heredoc in the bootstrap")
        assertFalse(b.contains(SessionOpsScript.SCRIPT.lines()[1]), "no script in the bootstrap")
        assertTrue(SessionOpsScript.CHECK_COMMAND.length < 1_000, "the check is small: ${SessionOpsScript.CHECK_COMMAND.length} B")
        assertTrue(SessionOpsScript.INSTALL_COMMAND.length > 10_000, "what stays out of it is not")
        val r = BootstrapScript.parse("===CRB flags===\n\n===CRB hooks===\nHOOK_EXISTS\n===CRB hooksv2===\nHOOKS_V2_STATE=ABSENT\n" +
            "===CRB streamd===\nSTREAMD_PRESENT\n===CRB sessionops===\nSESSIONOPS_NEEDED\n===CRB end===\n")
        assertEquals("STREAMD_PRESENT", r.streamd)
        assertEquals(SessionOpsScript.CHECK_NEEDED, r.sessionOps)
        assertTrue(r.ok, "a missing cr-session is no failed bootstrap")
    }

    /** The check and the installer judge the file alike in every state: NEEDED exactly when the installer would write. */
    @Test fun theCheckAgreesWithTheInstaller() {
        val home = tempHome()
        try {
            val f = File(home, ".claude-remote/bin/cr-session")
            fun check() = run("bash", "-c", SessionOpsScript.CHECK_COMMAND, home = home)
            fun install() = run("bash", "-c", SessionOpsScript.INSTALL_COMMAND, home = home)
            val needed = 0 to "${SessionOpsScript.CHECK_NEEDED}\n"
            val present = 0 to "${SessionOpsScript.CHECK_PRESENT}\n"

            assertEquals(needed, check(), "missing")
            assertFalse(f.exists(), "the check writes no file")
            assertEquals(0 to "SESSIONOPS_INSTALLED\n", install())
            assertEquals(present, check(), "intact")
            f.appendText("# damaged\n")
            assertEquals(needed, check(), "damaged behind a valid header")
            install()
            f.writeText("#!/usr/bin/env bash\n# ${SessionOpsScript.MARKER}0\n")
            assertEquals(needed, check(), "older")
            install()
            val newer = "#!/usr/bin/env bash\n# ${SessionOpsScript.MARKER}${SessionOpsScript.VERSION + 1}\n"
            f.writeText(newer)
            assertEquals(present, check(), "newer: never downgraded")
            assertEquals(0 to "SESSIONOPS_PRESENT\n", install())
            assertEquals(newer, f.readText())
        } finally {
            safeDeleteTree(home)
        }
    }
}
