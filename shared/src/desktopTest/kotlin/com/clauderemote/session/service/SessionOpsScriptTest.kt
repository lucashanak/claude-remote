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
            "tombstoneAndRecord" to ManifestCommands.tombstoneAndRecord(name, durable = true, by = "app:x"),
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

    /** The bootstrap carries the install and reports its line apart from the streamd one. */
    @Test fun bootstrapInstallsAndReportsIt() {
        val b = BootstrapScript.build(installV2 = false)
        assertContains(b, SessionOpsScript.INSTALL_COMMAND)
        val r = BootstrapScript.parse("===CRB flags===\n\n===CRB hooks===\nHOOK_EXISTS\n===CRB hooksv2===\nHOOKS_V2_STATE=ABSENT\n" +
            "===CRB streamd===\nSTREAMD_PRESENT\n===CRB sessionops===\nSESSIONOPS_INSTALLED\n===CRB end===\n")
        assertEquals("STREAMD_PRESENT", r.streamd)
        assertEquals("SESSIONOPS_INSTALLED", r.sessionOps)
        assertTrue(r.ok)
    }
}
