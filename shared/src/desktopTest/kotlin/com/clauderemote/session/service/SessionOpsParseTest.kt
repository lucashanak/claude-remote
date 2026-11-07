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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Jarvis F0b/F0c on the app side: the parsers of `closed.jsonl`, of the close facts, of a restore's result and of the
 * `opened` reconcile section, and the close record and restore bodies run by bash under a temp `$HOME` (tmux here is
 * the test shim on a private socket with no server, so nothing below reaches a session). The steps that need a live
 * pane and drift are in `SessionOpsIntegrationTest`.
 */
class SessionOpsParseTest {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val sid = "11111111-2222-3333-4444-555555555555"
    private val sid2 = "22222222-2222-3333-4444-555555555555"

    private fun home(): File = java.nio.file.Files.createTempDirectory("crops-home").toFile()

    private fun bash(cmd: String, home: File): Pair<Int, String> {
        val p = ProcessBuilder("bash", "-c", cmd).directory(home).redirectErrorStream(true)
            .also { it.environment()["HOME"] = home.absolutePath }.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); fail("timeout") }
        return p.exitValue() to out
    }

    /** [bash] with extra [env]; [stubTmux] puts [tmuxStub] first on PATH (no tmux is ever reached then). */
    private fun bashEnv(cmd: String, home: File, env: Map<String, String> = emptyMap(), stubTmux: Boolean = false): Pair<Int, String> {
        val pb = ProcessBuilder("bash", "-c", cmd).directory(home).redirectErrorStream(true)
        pb.environment()["HOME"] = home.absolutePath
        if (stubTmux) pb.environment()["PATH"] = tmuxStub(home).absolutePath + ":" + pb.environment()["PATH"]
        pb.environment().putAll(env)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); fail("timeout") }
        return p.exitValue() to out
    }

    /**
     * A `tmux` stand-in under [home] (not tmux: a script that answers the record's two questions): one pane whose pid
     * is `$STUB_PANE` (none when empty), its path `$STUB_PATH`; `kill-session` logs whether the tombstone and the
     * record were already there; anything else fails like a missing server.
     */
    private fun tmuxStub(home: File): File {
        val dir = File(home, "stubbin").apply { mkdirs() }
        File(dir, "tmux").apply {
            writeText(
                "#!/usr/bin/env bash\n" +
                    "case \"\$*\" in\n" +
                    "  *list-panes*) [ -n \"\${STUB_PANE:-}\" ] && echo \"\$STUB_PANE\" ;;\n" +
                    "  *display-message*) echo \"\${STUB_PATH:-/w}\" ;;\n" +
                    "  *kill-session*) D=\"\$HOME/.claude-remote\"; t=0; grep -q . \"\$D/forgotten\" 2>/dev/null && t=1; " +
                    "echo \"kill tomb=\$t rec=\$(cat \"\$D/closed.jsonl\" 2>/dev/null | wc -l)\" >> \"\$HOME/stub.log\" ;;\n" +
                    "  *) exit 1 ;;\n" +
                    "esac\n",
            )
            setExecutable(true)
        }
        return dir
    }

    /** A process named `claude` (a copy of bash, see 9b84c00) with [args] after its own `-c 'sleep 300; :' x`. */
    private fun fakeClaude(home: File, vararg args: String, env: Map<String, String> = emptyMap()): Process {
        val bin = File(home, "fakebin").apply { mkdirs() }
        val copy = File(bin, "claude")
        if (!copy.exists()) {
            File(ProcessBuilder("bash", "-c", "command -v bash").start().inputStream.bufferedReader().readText().trim()).copyTo(copy)
            copy.setExecutable(true)
        }
        val pb = ProcessBuilder(listOf(copy.absolutePath, "-c", "sleep 300; :", "x") + args).directory(home)
        pb.environment().putAll(env)
        return pb.start()
    }

    // ---- closed.jsonl -----------------------------------------------------------------------

    @Test fun aRecordLineIsReadToleratingMissingAndUnknownFields() {
        val full = ClosedRecords.parseLine(
            """{"v":1,"at":"2026-11-06T17:20:03Z","tmuxName":"claude-server-k-yolo--pdf","sessionId":"$sid","launchSessionId":"$sid2",""" +
                """"transcript":"/t.jsonl","cwd":"/home/l/k","folder":"/home/l/k","account":"lukas-kontexta-cz","model":"OPUS","mode":"YOLO",""" +
                """"flags":["--dangerously-skip-permissions"],"effort":null,"claudeVersion":"2.1.319","title":"Sloupce \"PDF\"","pid":42,""" +
                """"by":"app:android-1","durable":true,"reason":null,"future":{"x":1}}""",
        )
        assertNotNull(full)
        assertEquals("claude-server-k-yolo--pdf", full.tmuxName)
        assertEquals(sid, full.sessionId); assertEquals(sid2, full.launchSessionId)
        assertEquals(ClaudeModel.OPUS, full.claudeModel); assertEquals(ClaudeMode.YOLO, full.claudeMode)
        assertEquals("Sloupce \"PDF\"", full.title); assertEquals(42, full.pid); assertEquals(true, full.durable)
        assertEquals("/home/l/k", full.restoreFolder)
        assertEquals(1793985603L, full.atEpochSeconds)
        assertFalse(full.closedByPrune)

        val bare = assertNotNull(ClosedRecords.parseLine("""{"tmuxName":"claude-server-x","by":"prune","flags":["--dangerously-skip-permissions"]}"""))
        assertNull(bare.sessionId); assertNull(bare.v); assertNull(bare.atEpochSeconds)
        assertEquals(ClaudeModel.DEFAULT, bare.claudeModel)
        assertEquals(ClaudeMode.YOLO, bare.claudeMode, "no mode: the yolo flag decides")
        assertTrue(bare.closedByPrune)
        assertEquals(ClaudeMode.NORMAL, ClosedRecords.parseLine("""{"tmuxName":"x","mode":"WHAT"}""")!!.claudeMode)

        assertNull(ClosedRecords.parseLine(""))
        assertNull(ClosedRecords.parseLine("""{"v":1,"at":"x"}"""), "no tmuxName: not a record")
        assertNull(ClosedRecords.parseLine("""{"tmuxName":"claude-server-cut""""), "a cut-off line")
        assertNull(ClosedRecords.parseLine("not json"))
    }

    @Test fun readClosedIsNewestFirstAndUnknownWithoutItsEnd() {
        val out = """{"tmuxName":"a"}""" + "\n" + "garbage\n" + """{"tmuxName":"b"}""" + "\n\n" + ManifestCommands.CLOSED_END + "\n"
        assertEquals(listOf("b", "a"), ClosedRecords.parseRead(out)!!.map { it.tmuxName })
        assertEquals(emptyList(), ClosedRecords.parseRead("\n" + ManifestCommands.CLOSED_END))
        assertNull(ClosedRecords.parseRead("""{"tmuxName":"a"}"""), "no end marker: cut off, not empty")
    }

    /**
     * The record is written by bash and read back by the parser; the tombstone still happens when the record cannot.
     * A pane that is gone gets no record at all: an empty line would shadow the real one a restore needs.
     */
    @Test fun theRecordBodyWritesAParsableLineTrimsTheFileAndNeverFailsTheTombstone() {
        val h = home()
        val pane = ProcessBuilder("sleep", "300").start()
        try {
            val name = "claude-server-my proj's tab"
            val d = File(h, ".claude-remote")
            val live = mapOf("STUB_PANE" to pane.pid().toString(), "STUB_PATH" to "/w/my proj")

            // no pane (closed twice, or a prune of a dead one): tombstone yes, record no
            val (rc0, out0) = bashEnv(ManifestCommands.tombstoneAndRecord(name, durable = true, by = "app:android-x"), h, stubTmux = true)
            assertEquals(0, rc0, out0)
            assertTrue(File(d, "forgotten.d/$name").isFile, "the durable tombstone")
            assertFalse(File(d, "closed.jsonl").exists(), "no pane, no record")

            val (rc, out) = bashEnv(ManifestCommands.tombstoneAndRecord(name, durable = true, by = "app:android-x"), h, live, stubTmux = true)
            assertEquals(0, rc, out)
            val rec = assertNotNull(ClosedRecords.parseLine(File(d, "closed.jsonl").readLines().single()), File(d, "closed.jsonl").readText())
            assertEquals(name, rec.tmuxName); assertEquals("app:android-x", rec.by); assertEquals(true, rec.durable)
            assertNull(rec.pid, "a pane without claude"); assertNotNull(rec.at); assertEquals("/w/my proj", rec.restoreFolder)

            // a prune: transient, by prune, with a reason; then the file is cut to its last 500 lines
            File(d, "closed.jsonl").writeText((1..ManifestCommands.CLOSED_MAX_LINES).joinToString("") { """{"tmuxName":"old-$it"}""" + "\n" })
            assertEquals(0, bashEnv(ManifestCommands.closeRecord("claude-server-p", "prune", durable = false, reason = "gone"), h, live, stubTmux = true).first)
            val lines = File(d, "closed.jsonl").readLines()
            assertEquals(ManifestCommands.CLOSED_MAX_LINES, lines.size)
            assertEquals("old-2", ClosedRecords.parseLine(lines.first())!!.tmuxName)
            val last = ClosedRecords.parseLine(lines.last())!!
            assertEquals("prune", last.by); assertEquals(false, last.durable); assertEquals("gone", last.reason)

            // closed.jsonl cannot be written (a directory): the tombstone still lands and the exit status is its own
            File(d, "closed.jsonl").delete(); File(d, "closed.jsonl").mkdirs()
            val (rc2, out2) = bashEnv(ManifestCommands.tombstoneAndRecord("claude-server-z", durable = true, by = "app:x"), h, live, stubTmux = true)
            assertEquals(0, rc2, out2)
            assertTrue(File(d, "forgotten.d/claude-server-z").isFile)
            assertTrue(File(d, "forgotten").readLines().contains("claude-server-z"))
        } finally {
            pane.destroyForcibly()
            safeDeleteTree(h)
        }
    }

    /**
     * The app's close is ONE command: tombstone, record (the pane still lives), the opened.d marker gone, then the
     * kill — in that order, its status printed for the app; the exit status stays the tombstone's.
     */
    @Test fun theAppsCloseIsOneCommandInTheRightOrder() {
        val h = home()
        val pane = ProcessBuilder("sleep", "300").start()
        try {
            val name = "claude-server-p--a"
            val d = File(h, ".claude-remote")
            File(d, "opened.d").mkdirs(); File(d, "opened.d/$name").writeText("{}")
            val (rc, out) = bashEnv(ManifestCommands.closeCommand(name, durable = true, by = "app:x"), h,
                mapOf("STUB_PANE" to pane.pid().toString()), stubTmux = true)
            assertEquals(0, rc, out)
            assertEquals(0, ManifestCommands.closeKillStatus(out), out)
            assertEquals("kill tomb=1 rec=1", File(h, "stub.log").readText().trim(), "the kill came after the tombstone and the record")
            assertFalse(File(d, "opened.d/$name").exists(), "a close from the app drops the marker, as cr-session close does")
            assertNull(ManifestCommands.closeKillStatus("no kill line"))
        } finally {
            pane.destroyForcibly()
            safeDeleteTree(h)
        }
    }

    /**
     * claude-local exports its own config dir and execs plain `claude`: the record says LOCAL, finds the transcript
     * there. A pane whose claude is gone takes account, model, mode and conversation from the manifest.
     */
    @Test fun theRecordKnowsClaudeLocalAndFallsBackToTheManifest() {
        val h = home()
        var claude: Process? = null
        val shell = ProcessBuilder("sleep", "300").start()
        try {
            val lc = File(h, "claude-local/cc-config")
            claude = fakeClaude(h, "--session-id", sid, env = mapOf("CLAUDE_CONFIG_DIR" to lc.absolutePath))
            File(lc, "sessions").mkdirs()
            File(lc, "sessions/${claude.pid()}.json").writeText("""{"pid":${claude.pid()},"sessionId":"$sid","cwd":"/w/l"}""")
            File(lc, "projects/-w-l").mkdirs(); File(lc, "projects/-w-l/$sid.jsonl").writeText("{}\n")
            val d = File(h, ".claude-remote").apply { mkdirs() }
            assertEquals(0, bashEnv(ManifestCommands.closeRecord("claude-server-l", "app:x", durable = true), h,
                mapOf("STUB_PANE" to claude.pid().toString()), stubTmux = true).first)
            val local = assertNotNull(ClosedRecords.parseRead(bash(ManifestCommands.readClosed(), h).second)).first()
            assertEquals("LOCAL", local.model); assertNull(local.account); assertEquals(sid, local.sessionId)
            assertEquals(File(lc, "projects/-w-l/$sid.jsonl").absolutePath, local.transcript)

            File(d, "sessions.restore.json").writeText(
                """[{"tmuxSessionName":"claude-server-m","accountSlug":"acc","claudeSessionId":"$sid2","model":"OPUS","mode":"PLAN"}]""",
            )
            assertEquals(0, bashEnv(ManifestCommands.closeRecord("claude-server-m", "app:x", durable = true), h,
                mapOf("STUB_PANE" to shell.pid().toString()), stubTmux = true).first)
            val m = assertNotNull(ClosedRecords.parseRead(bash(ManifestCommands.readClosed(), h).second)).first()
            assertEquals("claude-server-m", m.tmuxName)
            assertEquals("acc", m.account); assertEquals(sid2, m.launchSessionId); assertEquals("OPUS", m.model); assertEquals("PLAN", m.mode)
        } finally {
            claude?.destroyForcibly(); shell.destroyForcibly()
            safeDeleteTree(h)
        }
    }

    // ---- close facts --------------------------------------------------------------------------

    private fun facts(vararg lines: String, end: Boolean = true) =
        CloseFactsCommands.parse((lines.toList() + listOf("TOKEN 1794040000.abc") + if (end) listOf(CloseFactsCommands.END) else emptyList()).joinToString("\n"))

    private val idle = arrayOf("FACT alive 1", "FACT pid 10", "FACT status idle -", "FACT event stop 4000 0", "FACT draft 0",
        "FACT clients 0 1", "FACT procs 0 -", "FACT git 0 0 0", "FACT transcript 1")

    private fun with(key: String, line: String) = idle.map { if (it.startsWith("FACT $key ")) line else it }.toTypedArray()

    @Test fun factsLevelsAndTexts() {
        val ok = facts(*idle)
        assertEquals(CloseLevel.OK, ok.level); assertEquals("1794040000.abc", ok.token); assertEquals(true, ok.transcript)
        assertEquals(CloseLevel.UNKNOWN, facts(*idle, end = false).level, "cut off: unknown, the close goes ahead")
        assertEquals(CloseLevel.OK, facts("FACT alive 0").level, "no session: nothing to protect")

        fun only(vararg l: String): CloseFacts = facts(*l)
        assertEquals("běží tah", only(*with("status", "FACT status busy -")).blockText)
        val wf = java.util.Base64.getEncoder().encodeToString("dialog open".toByteArray())
        assertEquals("otevřený dialog", only(*with("status", "FACT status waiting $wf")).blockText)
        assertEquals("čeká na schválení", only(*with("status", "FACT status waiting -")).blockText)
        // no registry: a recent `work` without a stop is a running turn, an old one is not trusted
        assertEquals("běží tah", only(*with("status", "FACT status - -").let { a -> a.map { if (it.startsWith("FACT event")) "FACT event work 30 0" else it }.toTypedArray() }).blockText)
        assertEquals(CloseLevel.OK, only(*with("status", "FACT status - -").let { a -> a.map { if (it.startsWith("FACT event")) "FACT event work 99999 0" else it }.toTypedArray() }).level)
        assertEquals("běží úlohy na pozadí", only(*with("event", "FACT event stop 100 1")).blockText)
        assertEquals(CloseLevel.OK, only(*with("event", "FACT event stop 4000 1")).level, "a stop long ago: its tasks are processes by now, or gone")
        assertEquals("rozepsaný vstup", only(*with("draft", "FACT draft 1")).blockText)
        assertEquals(CloseLevel.OK, only(*with("draft", "FACT draft -")).level, "no input box visible: no draft claim")
        assertEquals("dívá se jiné zařízení", only(*with("clients", "FACT clients 1 1")).blockText)
        assertEquals("dívá se jiné zařízení", only(*with("clients", "FACT clients 2 -")).blockText, "cr-session: no own client")
        val maybe = only(*with("clients", "FACT clients 1 0"))
        assertEquals(CloseLevel.WARN, maybe.level); assertEquals("možná se dívá jiné zařízení", maybe.warnText)
        assertEquals("běží node, gradle", only(*with("procs", "FACT procs 3 node,gradle")).blockText)
        val git = only(*with("git", "FACT git 3 1 7"))
        assertEquals(CloseLevel.WARN, git.level)
        assertEquals("3 necommitnuté soubory, 1 nepushnutý commit, 7 uložených stashů", git.warnText)
        assertEquals("aktivní před 2 min", only(*with("event", "FACT event stop 150 0")).warnText)
        assertEquals("aktivní právě teď", only(*with("event", "FACT event stop 10 0")).warnText)
        // a BLOCK and a WARN together: BLOCK wins, the warning is still reported
        val both = only(*with("draft", "FACT draft 1").map { if (it.startsWith("FACT git")) "FACT git 1 - -" else it }.toTypedArray())
        assertEquals(CloseLevel.BLOCK, both.level); assertEquals("1 necommitnutý soubor", both.warnText)
        assertEquals("5 necommitnutých souborů", CloseFactsCommands.czCount(5, "necommitnutý soubor", "necommitnuté soubory", "necommitnutých souborů"))
    }

    /** `cr-session close` refuses with the shell twin of the BLOCK rules: the same codes as the app's parser. */
    @Test fun theShellBlockRulesAreTheParsersRules() {
        val h = home()
        try {
            val wf = java.util.Base64.getEncoder().encodeToString("dialog".toByteArray())
            val cases = listOf(
                idle, with("status", "FACT status busy -"), with("status", "FACT status waiting $wf"),
                with("status", "FACT status - -").map { if (it.startsWith("FACT event")) "FACT event work 30 0" else it }.toTypedArray(),
                with("status", "FACT status - -").map { if (it.startsWith("FACT event")) "FACT event perm 30 0" else it }.toTypedArray(),
                with("status", "FACT status - -").map { if (it.startsWith("FACT event")) "FACT event work 99999 0" else it }.toTypedArray(),
                with("status", "FACT status busy -").map { if (it.startsWith("FACT event")) "FACT event ask 30 0" else it }.toTypedArray(),
                with("event", "FACT event stop 100 1"), with("event", "FACT event stop 4000 1"), with("event", "FACT event - - 0"),
                with("draft", "FACT draft 1"), with("draft", "FACT draft -"),
                with("clients", "FACT clients 1 1"), with("clients", "FACT clients 2 -"), with("clients", "FACT clients 1 0"),
                with("procs", "FACT procs 3 node,gradle"), with("git", "FACT git 3 1 7"),
                arrayOf("FACT alive 0"),
                with("draft", "FACT draft 1").map { if (it.startsWith("FACT procs")) "FACT procs 1 sleep" else it }.toTypedArray(),
            )
            for (c in cases) {
                val out = (c.toList() + listOf("TOKEN 1.x", CloseFactsCommands.END)).joinToString("\n")
                val (rc, sh) = bash(CloseFactsCommands.BLOCKS_SH + "\nprintf '%s\\n' ${ManifestCommands.sq(out)} | cr_blocks", h)
                assertEquals(0, rc, sh)
                val parsed = CloseFactsCommands.parse(out).reasons.filter { it.level == CloseLevel.BLOCK }.map { it.code.name }
                assertEquals(parsed, sh.trim().split(',').filter { it.isNotEmpty() }, "facts: ${c.toList()}")
            }
        } finally {
            safeDeleteTree(h)
        }
    }

    /** The marker name the facts read is exactly the one the attach preamble writes. */
    @Test fun selfMarkerIsTheAttachPreamblesFile() {
        val key = CloseFactsCommands.selfMarkerFor("claude-server-a b", "android-1234")
        assertEquals("android-1234-claude-server-a_b", key)
        val cmd = CloseFactsCommands.closeFactsCommand("claude-server-a b", key)
        assertTrue(cmd.startsWith("N='claude-server-a b'; SELF='android-1234-claude-server-a_b'\n"))
    }

    // ---- restore ------------------------------------------------------------------------------

    @Test fun restoreResultsParse() {
        fun p(s: String) = SessionRestore.parse("noise\n${SessionRestore.RESULT_PREFIX} $s\n")
        assertEquals(RestoreResult.Restored(42, sid, true), p("RESTORED 42 $sid"))
        assertEquals(RestoreResult.Restored(42, sid, true, resumedId = sid2), p("RESTORED 42 $sid $sid2"))
        assertEquals(RestoreResult.Restored(null, null, false), p("RESTORED_NO_REGISTRY"))
        assertEquals(RestoreResult.Restored(null, null, false, resumedId = sid2), p("RESTORED_NO_REGISTRY $sid2"))
        assertEquals(RestoreResult.Failed("tmux-timeout"), p("FAILED tmux-timeout"))
        assertEquals(RestoreResult.Exists("claude-server-a--b-2"), p("EXISTS claude-server-a--b-2"))
        assertEquals(RestoreResult.PendingSelfHeal, p("PENDING_SELF_HEAL"))
        assertEquals(RestoreResult.RunningElsewhere("claude-server-q"), p("RUNNING_ELSEWHERE claude-server-q"))
        assertEquals(RestoreResult.RunningElsewhere(null), p("RUNNING_ELSEWHERE -"))
        assertEquals(RestoreResult.NoTranscript(sid2), p("NO_TRANSCRIPT $sid2"))
        assertEquals(RestoreResult.NoTranscript(null), p("NO_TRANSCRIPT -"))
        assertEquals(RestoreResult.LoggedOut("acc"), p("LOGGED_OUT acc"))
        assertEquals(RestoreResult.LoginRequired, p("OPENED_LOGIN_REQUIRED"))
        assertEquals(RestoreResult.Failed("rc=1"), p("FAILED rc=1"))
        assertTrue(p("REFUSED_GUARD 4") is RestoreResult.Refused)
        assertEquals(RestoreResult.Unknown, SessionRestore.parse("nothing"))
    }

    private fun record(name: String = "claude-server-p--a", folder: String = "/w", account: String? = null, s: String? = sid, l: String? = sid2) =
        ClosedRecord(1, "2026-11-06T17:20:03Z", name, s, l, null, folder, folder, account, "SONNET", "AUTO", emptyList(), null, null, null, null, "app:x", true, null)

    @Test fun restoreCommandResumesWithTheAppsOwnLaunchString() {
        val r = record(account = "acc")
        val cmd = SessionRestore.restoreCommand(r)
        val q = ManifestCommands::sq
        assertContains(cmd, "CMD=${q(ClaudeConfig.buildLaunchCommand("/w", ClaudeMode.AUTO, ClaudeModel.SONNET, sid, true, "acc"))};")
        assertContains(cmd, "CMD2=${q(ClaudeConfig.buildLaunchCommand("/w", ClaudeMode.AUTO, ClaudeModel.SONNET, sid2, true, "acc"))};")
        assertContains(cmd, "FRESH=0; AT_EPOCH=1793985603; WAIT=${SessionRestore.APP_WAIT_S}\n")
        val fresh = SessionRestore.restoreCommand(r, tmuxName = "claude-server-p--a-2", freshSessionId = "33333333-2222-3333-4444-555555555555")
        assertContains(fresh, "N='claude-server-p--a-2';")
        assertContains(fresh, "--session-id 33333333-2222-3333-4444-555555555555")
        assertContains(fresh, "CMD2='';"); assertContains(fresh, "FRESH=1;")
        // the same id twice is no fallback
        assertContains(SessionRestore.restoreCommand(record(l = sid)), "S2='';")
        assertFalse(SessionRestore.restoreBody().contains("kill-session"))
    }

    /** The refusals that come before anything is created, run by bash under a temp HOME. */
    @Test fun restoreRefusesBeforeCreatingAnything() {
        val h = home()
        val sleeper = ProcessBuilder("sleep", "300").start()
        try {
            val d = File(h, ".claude-remote").apply { mkdirs() }
            fun restore(r: ClosedRecord = record(folder = h.absolutePath), name: String = r.tmuxName) =
                SessionRestore.parse(bash(SessionRestore.restoreCommand(r, tmuxName = name, waitS = 1), h).second)

            assertEquals(RestoreResult.Refused("invalid name"), restore(name = "claude-server-a.b"))

            // no transcript of either id; the folder's newest transcript around the close is offered, not used
            val proj = File(h, ".claude/projects/" + h.absolutePath.replace(Regex("[^A-Za-z0-9]"), "-")).apply { mkdirs() }
            val cand = File(proj, "44444444-2222-3333-4444-555555555555.jsonl").apply { writeText("{}\n") }
            cand.setLastModified(1793985603L * 1000 + 60_000)
            assertEquals(RestoreResult.NoTranscript("44444444-2222-3333-4444-555555555555"), restore())
            cand.setLastModified(1793985603L * 1000 + 3_600_000)
            assertEquals(RestoreResult.NoTranscript(null), restore(), "an hour off is no candidate")

            // the launch id has the transcript (claude forked on --resume and never wrote the new one): logged out next
            File(proj, "$sid2.jsonl").writeText("{}\n")
            assertEquals(RestoreResult.LoggedOut("default"), restore())
            assertEquals(RestoreResult.LoggedOut("acc"), restore(record(folder = h.absolutePath, account = "acc")))

            // the conversation runs in a live process (its registry entry, same procStart)
            File(h, ".claude/sessions").mkdirs()
            val pid = sleeper.pid()
            val start = File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19]
            File(h, ".claude/sessions/$pid.json").writeText("""{"pid":$pid,"sessionId":"$sid2","procStart":"$start","tmux":"claude-server-other:@1.%1"}""")
            // where: only a pane of THIS socket counts (the registry's own `tmux` names no socket), and there is none
            assertEquals(RestoreResult.RunningElsewhere(null), restore())
            // a recycled pid (another procStart) is not that process
            File(h, ".claude/sessions/$pid.json").writeText("""{"pid":$pid,"sessionId":"$sid2","procStart":"1","tmux":"claude-server-other:@1.%1"}""")
            assertEquals(RestoreResult.LoggedOut("default"), restore())

            // no registry yet (claude writes it once it is up): its argv `--resume <id>` is enough. The restore's own
            // bash carries the id in its argv too, and is no claude.
            val c = fakeClaude(h, "--dangerously-skip-permissions", "--resume", sid2)
            try {
                assertEquals(RestoreResult.RunningElsewhere(null), restore())
                assertEquals(RestoreResult.RunningElsewhere(null), restore(record(folder = h.absolutePath, s = sid2, l = null)))
            } finally {
                c.destroyForcibly(); c.waitFor()
            }
            assertEquals(RestoreResult.LoggedOut("default"), restore(), "gone with its process")
            // the candidate is searched in the record's account too
            File(proj, "$sid2.jsonl").delete()
            val accProj = File(d, "accounts/acc/projects/" + h.absolutePath.replace(Regex("[^A-Za-z0-9]"), "-")).apply { mkdirs() }
            cand.delete()
            File(accProj, "55555555-2222-3333-4444-555555555555.jsonl").apply { writeText("{}\n"); setLastModified(1793985603L * 1000 + 30_000) }
            assertEquals(RestoreResult.NoTranscript("55555555-2222-3333-4444-555555555555"), restore(record(folder = h.absolutePath, account = "acc")))
            File(proj, "$sid2.jsonl").writeText("{}\n")

            // a manifest entry of the name that is not tombstoned waits for drift's self-heal
            File(d, "sessions.restore.json").writeText("""[{"tmuxSessionName":"claude-server-p--a"}]""")
            assertEquals(RestoreResult.PendingSelfHeal, restore())
            File(d, "forgotten.d").mkdirs(); File(d, "forgotten.d/claude-server-p--a").writeText("")
            assertEquals(RestoreResult.LoggedOut("default"), restore(), "tombstoned: drift will drop it, not heal it")
        } finally {
            sleeper.destroyForcibly()
            safeDeleteTree(h)
        }
    }

    // ---- opened markers in reconcileAll ------------------------------------------------------

    @Test fun theOpenedSectionIsParsedAndAnOlderParserIgnoresIt() {
        val base = "claude-server-a\n---\n[]\n__END_snap__ rc=0\nclaude-server-b\nclaude-server-c\n__END_tmux__ rc=0\n" +
            "claude-server-b 100\n__END_panes__ rc=0\n100 1 bash\n101 100 claude\n__END_ps__ rc=0\n" +
            "SID 101 $sid\n__END_claude__ rc=0\n"
        fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())
        val opened = "OPENED 1794040000 ${b64("claude-server-c")} {\"by\":\"jarvis\",\"at\":\"2026-11-07T08:00:00Z\",\"folder\":\"/w\",\"account\":null,\"sessionId\":\"$sid\",\"task\":\"t-1\"}\n" +
            "OPENED 1794040001 ${b64("claude-server-d e")} not-json\n" +
            "OPENED x\n" +
            "__END_opened__ rc=0\n"
        val old = ReconcileParse.parse(base, json)
        val new = ReconcileParse.parse(base + opened, json)
        // everything the parser read before is read the same with the new section present
        assertEquals(old.tombstoned, new.tombstoned); assertEquals(old.manifest, new.manifest); assertEquals(old.tmuxNames, new.tmuxNames)
        assertEquals(old.panePids, new.panePids); assertEquals(old.procs, new.procs); assertEquals(old.claude, new.claude)
        for (n in listOf("claude-server-a", "claude-server-b", "claude-server-c")) {
            assertEquals(old.closeVerdict(n), new.closeVerdict(n)); assertEquals(old.liveUuid(n), new.liveUuid(n))
        }
        assertNull(old.opened, "no section: unknown")
        val m = assertNotNull(new.opened)
        assertEquals(listOf("claude-server-c", "claude-server-d e"), m.map { it.tmuxName })
        assertEquals(OpenedMarker("claude-server-c", 1794040000, "jarvis", "2026-11-07T08:00:00Z", "/w", null, sid, "t-1"), m[0])
        assertEquals(OpenedMarker("claude-server-d e", 1794040001, null, null, null, null, null, null), m[1])
        assertNull(ReconcileParse.parseOpened(base + "OPENED 1 ${b64("x")} {}\n"), "a section without its END line is unknown")
    }

    /** The marker body writes what the reconcile section's loop (cut out of reconcileAll) prints and the parser reads. */
    @Test fun aMarkerWrittenByTheBodyIsReadBackThroughTheReconcileLoop() {
        val h = home()
        try {
            val write = "N='claude-server-x y'; BY=jarvis; F='/w/it'\"'\"'s'; A=acc; S=$sid; TASK=''\n" + ManifestCommands.openedMarkerBody()
            assertEquals(0, bash(write, h).first)
            // a marker older than a day is never adopted: the section does not send it
            File(h, ".claude-remote/opened.d/claude-server-old").apply { writeText("{}\n"); setLastModified(System.currentTimeMillis() - 25 * 3600_000L) }
            val loop = ManifestCommands.reconcileAll(emptyList()).lines().first { it.contains("\"/opened.d/*") }
            val (rc, out) = bash("D=\"\$HOME/.claude-remote\"\n$loop", h)
            assertEquals(0, rc, out)
            val m = assertNotNull(ReconcileParse.parseOpened(out)).single()
            assertEquals("claude-server-x y", m.tmuxName); assertEquals("jarvis", m.by); assertEquals("/w/it's", m.folder)
            assertEquals("acc", m.account); assertEquals(sid, m.sessionId); assertNull(m.task); assertTrue(m.mtimeEpochS > 0)
        } finally {
            safeDeleteTree(h)
        }
    }
}
