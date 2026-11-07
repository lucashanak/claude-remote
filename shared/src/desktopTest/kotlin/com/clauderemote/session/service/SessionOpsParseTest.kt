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

    /** The record is written by bash and read back by the parser; the tombstone still happens when the record cannot. */
    @Test fun theRecordBodyWritesAParsableLineTrimsTheFileAndNeverFailsTheTombstone() {
        val h = home()
        try {
            val name = "claude-server-my proj's tab"
            val (rc, out) = bash(ManifestCommands.tombstoneAndRecord(name, durable = true, by = "app:android-x"), h)
            assertEquals(0, rc, out)
            val d = File(h, ".claude-remote")
            val rec = assertNotNull(ClosedRecords.parseLine(File(d, "closed.jsonl").readLines().single()), File(d, "closed.jsonl").readText())
            assertEquals(name, rec.tmuxName); assertEquals("app:android-x", rec.by); assertEquals(true, rec.durable)
            assertNull(rec.pid, "no pane: no claude"); assertNotNull(rec.at)
            assertTrue(File(d, "forgotten.d/$name").isFile, "the durable tombstone")

            // a prune: transient, by prune, with a reason; then the file is cut to its last 500 lines
            File(d, "closed.jsonl").writeText((1..ManifestCommands.CLOSED_MAX_LINES).joinToString("") { """{"tmuxName":"old-$it"}""" + "\n" })
            assertEquals(0, bash(ManifestCommands.closeRecord("claude-server-p", "prune", durable = false, reason = "gone"), h).first)
            val lines = File(d, "closed.jsonl").readLines()
            assertEquals(ManifestCommands.CLOSED_MAX_LINES, lines.size)
            assertEquals("old-2", ClosedRecords.parseLine(lines.first())!!.tmuxName)
            val last = ClosedRecords.parseLine(lines.last())!!
            assertEquals("prune", last.by); assertEquals(false, last.durable); assertEquals("gone", last.reason)

            // closed.jsonl cannot be written (a directory): the tombstone still lands and the exit status is its own
            File(d, "closed.jsonl").delete(); File(d, "closed.jsonl").mkdirs()
            val (rc2, out2) = bash(ManifestCommands.tombstoneAndRecord("claude-server-z", durable = true, by = "app:x"), h)
            assertEquals(0, rc2, out2)
            assertTrue(File(d, "forgotten.d/claude-server-z").isFile)
            assertTrue(File(d, "forgotten").readLines().contains("claude-server-z"))
        } finally {
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
        assertEquals("běží úlohy na pozadí", only(*with("event", "FACT event stop 4000 1")).blockText)
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
        assertEquals(RestoreResult.Restored(null, null, false), p("RESTORED_NO_REGISTRY"))
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
            assertEquals(RestoreResult.RunningElsewhere("claude-server-other"), restore())
            // a recycled pid (another procStart) is not that process
            File(h, ".claude/sessions/$pid.json").writeText("""{"pid":$pid,"sessionId":"$sid2","procStart":"1","tmux":"claude-server-other:@1.%1"}""")
            assertEquals(RestoreResult.LoggedOut("default"), restore())

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
            val loop = ManifestCommands.reconcileAll(emptyList()).lines().first { it.startsWith("for f in \"\$D\"/opened.d/") }
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
