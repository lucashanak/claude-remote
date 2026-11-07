package com.clauderemote.integration

import com.clauderemote.connection.SshManager
import com.clauderemote.session.service.CloseFactsCommands
import com.clauderemote.session.service.CloseLevel
import com.clauderemote.session.service.ClosedRecords
import com.clauderemote.session.service.ManifestCommands
import com.clauderemote.session.service.ReconcileParse
import com.clauderemote.session.service.RestoreResult
import com.clauderemote.session.service.SessionOpsScript
import com.clauderemote.session.service.SessionPersistenceService
import com.clauderemote.session.service.SessionRestore
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.Session
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `cr-session` and the app's close record / restore against a real sshd, a real tmux on the fixture's PRIVATE socket
 * and the real drift.sh / restore.sh installed by the app's own installer (§15.9). The claude stand-in is a copy of
 * bash named `claude` (any coreutils: see 9b84c00) that first writes its registry entry `~/.claude/sessions/<pid>.json`
 * like claude does, keeping its argv for drift and the record to read.
 */
class SessionOpsIntegrationTest {

    private lateinit var fixture: SshdFixture
    private lateinit var manager: SshManager
    private lateinit var session: Session
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Before
    fun boot() {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        fixture = SshdFixture.start()
        manager = SshManager(ServerStorage(InMemoryKeyValueStore()))
        runBlocking { manager.connect(fixture.server, onOutput = {}, onConnectionLost = {}) }
        session = assertNotNull(manager.getSession())
        val inst = session.execCapture("bash -s", timeoutMs = 60_000, stdin = SessionPersistenceService.INSTALL_RESTORE_COMMAND.toByteArray())
        assertEquals(0, inst.exit, "drift/restore install: ${inst.out}")
        assertContains(sh(SessionOpsScript.INSTALL_COMMAND).out, "SESSIONOPS_INSTALLED")
        installClaudeStandIn()
        File(fixture.home, ".claude").mkdirs()
        // the restore checks only that a login is there (`test -s`); the content is never read
        File(fixture.home, ".claude/.credentials.json").writeText("{}")
        fixture.startTmuxServer()
    }

    @After
    fun shutdown() {
        if (::manager.isInitialized) manager.shutdownForTest()
        if (::fixture.isInitialized) fixture.close()
    }

    private fun installClaudeStandIn() {
        val bin = File(fixture.root, "realbin").apply { mkdirs() }
        val bashBin = File(ProcessBuilder("bash", "-c", "command -v bash").start().inputStream.bufferedReader().readText().trim())
        val copy = File(bin, "claude")
        bashBin.copyTo(copy, overwrite = true); copy.setExecutable(true)
        val body = File(fixture.root, "standin.sh")
        body.writeText("sleep 3600 & trap 'kill \$! 2>/dev/null; exit 0' HUP TERM INT; wait; :\n")
        File(fixture.shims, "claude").writeText(
            """
            #!/usr/bin/env bash
            # claude stand-in: the registry entry claude writes, then a process named claude that keeps this argv
            cfg="${'$'}{CLAUDE_CONFIG_DIR:-${'$'}HOME/.claude}"
            case "${'$'}cfg" in "${'$'}HOME"/*) ;; *) echo "stand-in: config dir outside HOME" >&2; exit 9 ;; esac
            sid=""; prev=""; for a in "${'$'}@"; do case "${'$'}prev" in --session-id|--resume) sid=${'$'}a ;; esac; prev=${'$'}a; done
            # STANDIN_NO_REGISTRY: a claude that has not written its registry yet (only its argv names the conversation)
            if [ -z "${'$'}{STANDIN_NO_REGISTRY:-}" ]; then
              mkdir -p "${'$'}cfg/sessions"
              printf '{"pid":%s,"sessionId":"%s","cwd":"%s","version":"9.9.9","status":"idle"}\n' "${'$'}${'$'}" "${'$'}sid" "${'$'}PWD" > "${'$'}cfg/sessions/${'$'}${'$'}.json"
            fi
            exec '${copy.absolutePath}' '${body.absolutePath}' "${'$'}@"

            """.trimIndent(),
        )
        File(fixture.shims, "claude").setExecutable(true)
        // a pane runs a login shell, whose /etc/profile drops the fixture's PATH: give it the stand-in back
        File(fixture.home, ".bash_profile").writeText("export PATH='${fixture.shims.absolutePath}':\"\$PATH\"\n")
    }

    // ------------------------------------------------------------------ helpers

    private fun sh(cmd: String, timeoutMs: Long = 60_000): ExecResult = session.execCapture(cmd, timeoutMs = timeoutMs)

    private fun cr(vararg args: String, timeoutMs: Long = 60_000): ExecResult =
        sh("\"\$HOME/.claude-remote/bin/cr-session\" " + args.joinToString(" ") { ManifestCommands.sq(it) }, timeoutMs)

    private fun drift(): ExecResult = sh("bash \"\$HOME/.claude-remote/drift.sh\"")

    private fun manifestNames(): List<String> {
        val f = File(fixture.home, ".claude-remote/sessions.restore.json")
        if (!f.isFile) return emptyList()
        return Regex("\"tmuxSessionName\"\\s*:\\s*\"([^\"]*)\"").findAll(f.readText()).map { it.groupValues[1] }.toList()
    }

    private fun folder(name: String): File = File(fixture.home, name).apply { mkdirs() }

    /** The transcript of [sid] under [dir], with an `ai-title`. */
    private fun transcript(dir: File, sid: String, title: String = "Sloupce v PDF") {
        val p = File(fixture.home, ".claude/projects/" + dir.absolutePath.replace(Regex("[^A-Za-z0-9]"), "-")).apply { mkdirs() }
        File(p, "$sid.jsonl").writeText("{\"type\":\"user\"}\n{\"type\":\"ai-title\",\"aiTitle\":\"$title\",\"sessionId\":\"$sid\"}\n")
    }

    private fun open(name: String, dir: File, sid: String): String {
        val r = cr("open", "--name", name, "--folder", dir.absolutePath, "--session-id", sid, "--wait", "10")
        assertTrue(r.out.trim().startsWith("OPENED {"), "open $name: ${r.out}\npane:\n${fixture.tmux("capture-pane", "-p", "-t", "=$name:").out}")
        return r.out
    }

    private fun panePid(name: String): Long? =
        fixture.tmux("list-panes", "-t", "=$name:", "-F", "#{pane_pid}").takeIf { it.exit == 0 }?.out?.trim()?.lines()?.firstOrNull()?.toLongOrNull()

    private fun token(name: String): String =
        assertNotNull(CloseFactsCommands.token(cr("facts", name).out), "facts of $name gave no token")

    private val sidA = "11111111-2222-3333-4444-555555555555"
    private val sidB = "22222222-2222-3333-4444-555555555555"
    private val nameA = "claude-server-projA-yolo--pdf"
    private val nameB = "claude-server-projB-yolo--peer"

    // ------------------------------------------------------------------ tests

    /** open → drift tick → the session is in sessions.restore.json (adopted), with its marker for the other devices. */
    @Test
    fun anOpenedSessionIsAdoptedByDriftAndAnnouncedByItsMarker() {
        val out = open(nameA, folder("projA"), sidA)
        assertContains(out, "\"sessionId\":\"$sidA\"")
        assertEquals(0, drift().exit, fixture.driftLog())
        waitUntil(10_000, "drift to adopt $nameA. log:\n${fixture.driftLog()}") { nameA in manifestNames() }
        assertContains(File(fixture.home, ".claude-remote/sessions.restore.json").readText(), sidA)

        val markers = assertNotNull(ReconcileParse.parseOpened(sh(ManifestCommands.reconcileAll(listOf(nameA))).out), "opened section")
        val m = markers.single { it.tmuxName == nameA }
        assertEquals("jarvis", m.by); assertEquals(sidA, m.sessionId); assertEquals(folder("projA").absolutePath, m.folder)
        assertContains(File(fixture.home, ".claude-remote/jarvis-ops.log").readText(), "open $nameA")
    }

    /**
     * close → tick → gone from the manifest and NOT resurrected by self-heal; the record was written while the pane
     * lived (it names claude's pid and argv) and after the tombstone; the marker is gone.
     */
    @Test
    fun aClosedSessionStaysClosedAndLeavesARecord() {
        transcript(folder("projA"), sidA)
        open(nameA, folder("projA"), sidA)
        open(nameB, folder("projB"), sidB)
        assertEquals(0, drift().exit)
        waitUntil(10_000, "drift to adopt both") { manifestNames().containsAll(listOf(nameA, nameB)) }

        val facts = CloseFactsCommands.parse(cr("facts", nameA).out)
        assertEquals(CloseLevel.OK, facts.level, "an idle stand-in: ${facts.reasons}")
        assertEquals(true, facts.transcript)
        assertEquals("CLOSED", cr("close", nameA, "--token", assertNotNull(facts.token), "--reason", "done").out.trim())
        assertFalse(nameA in fixture.tmuxSessions())

        val rec = assertNotNull(ClosedRecords.parseRead(sh(ManifestCommands.readClosed(10)).out)).first()
        assertEquals(nameA, rec.tmuxName); assertEquals("jarvis", rec.by); assertEquals(true, rec.durable); assertEquals("done", rec.reason)
        assertNotNull(rec.pid, "the record was written while claude lived")
        assertEquals(sidA, rec.sessionId); assertEquals(sidA, rec.launchSessionId)
        assertEquals("YOLO", rec.mode); assertEquals("DEFAULT", rec.model); assertEquals("9.9.9", rec.claudeVersion)
        assertEquals("Sloupce v PDF", rec.title); assertEquals(folder("projA").absolutePath, rec.cwd); assertNull(rec.account)
        assertTrue(File(fixture.home, ".claude-remote/forgotten.d/$nameA").isFile, "durable tombstone")
        assertFalse(File(fixture.home, ".claude-remote/opened.d/$nameA").exists(), "the marker must go with the session")
        // the order in the script: tombstone, record, kill
        val close = SessionOpsScript.SCRIPT.substringAfter("cmd_close() {").substringBefore("cmd_restore() {")
        assertTrue(close.indexOf("cr_tombstone ") < close.indexOf("cr_record ") && close.indexOf("cr_record ") < close.indexOf("kill-session"), close)

        repeat(2) { assertEquals(0, drift().exit, fixture.driftLog()) }
        Thread.sleep(1_500) // let a (wrong) self-heal land before looking
        assertFalse(nameA in manifestNames(), "drift must drop the closed session. log:\n${fixture.driftLog()}")
        assertFalse(nameA in fixture.tmuxSessions(), "self-heal resurrected a closed session. log:\n${fixture.driftLog()}")
        assertTrue(nameB in fixture.tmuxSessions())
    }

    /** Negative control: a bare kill without a tombstone IS resurrected — the danger the tombstone exists for. */
    @Test
    fun aKillWithoutATombstoneIsResurrectedBySelfHeal() {
        open(nameA, folder("projA"), sidA)
        open(nameB, folder("projB"), sidB)
        assertEquals(0, drift().exit)
        waitUntil(10_000, "drift to adopt both") { manifestNames().containsAll(listOf(nameA, nameB)) }
        assertEquals(0, fixture.tmux("kill-session", "-t", "=$nameA").exit)
        assertEquals(0, drift().exit)
        waitUntil(25_000, "self-heal to relaunch $nameA. log:\n${fixture.driftLog()}") { nameA in fixture.tmuxSessions() }
    }

    /** open over a fresh durable tombstone: the tombstone goes first, so the next tick keeps the session in the manifest. */
    @Test
    fun openingANameWithAStaleDurableTombstoneLiftsItFirst() {
        val d = File(fixture.home, ".claude-remote").apply { mkdirs() }
        File(d, "forgotten.d").mkdirs(); File(d, "forgotten.d/$nameA").writeText("")
        File(d, "forgotten").writeText("$nameA\n")
        open(nameA, folder("projA"), sidA)
        assertFalse(File(d, "forgotten.d/$nameA").exists())
        assertFalse(File(d, "forgotten").readText().lines().contains(nameA))
        assertEquals(0, drift().exit)
        waitUntil(10_000, "drift to adopt $nameA. log:\n${fixture.driftLog()}") { nameA in manifestNames() }
    }

    /** A token older than 120 s, or one taken before the facts changed, closes nothing. */
    @Test
    fun aStaleTokenClosesNothing() {
        val dir = folder("projA")
        open(nameA, dir, sidA)
        val tok = token(nameA)
        val old = (System.currentTimeMillis() / 1000 - 200).toString() + "." + tok.substringAfter('.')
        assertEquals("REFUSED_STALE", cr("close", nameA, "--token", old).out.trim())
        // the session changes between the question and the "yes": a file appears in its git work tree
        assertEquals(0, ProcessBuilder("git", "init", "-q", dir.absolutePath).start().waitFor())
        val before = token(nameA)
        File(dir, "new.txt").writeText("x")
        assertEquals("REFUSED_STALE", cr("close", nameA, "--token", before).out.trim())
        assertTrue(nameA in fixture.tmuxSessions(), "a refused close must leave the session")
        val git = CloseFactsCommands.parse(cr("facts", nameA).out)
        assertEquals("1 necommitnutý soubor", git.warnText)
        assertEquals("CLOSED", cr("close", nameA, "--token", token(nameA)).out.trim())
    }

    /** open of a taken name: EXISTS, and the session there is the same one (never killed, never recreated). */
    @Test
    fun openingATakenNameLeavesTheSessionThere() {
        open(nameA, folder("projA"), sidA)
        val pid = panePid(nameA)
        val born = fixture.sessionCreatedAt(nameA)
        val r = cr("open", "--name", nameA, "--folder", folder("projB").absolutePath, "--session-id", sidB)
        assertEquals("EXISTS", r.out.trim())
        assertEquals(1, r.exit)
        assertEquals(pid, panePid(nameA)); assertEquals(born, fixture.sessionCreatedAt(nameA))
    }

    /**
     * Restore of a record whose name was taken again: EXISTS with `…-2`, the session there untouched; restoring under
     * `…-2` brings the closed conversation back and drift adopts it.
     */
    @Test
    fun restoreUnderATakenNameOffersTheNextFreeName() {
        val dir = folder("projA")
        transcript(dir, sidA)
        open(nameA, dir, sidA)
        assertEquals("CLOSED", cr("close", nameA, "--token", token(nameA)).out.trim())
        open(nameA, dir, sidB)
        val pid = panePid(nameA)
        val r = cr("restore", nameA, "--wait", "5")
        assertEquals("$nameA-2", (SessionRestore.parse(r.out) as RestoreResult.Exists).suggestedName, r.out)
        assertEquals(pid, panePid(nameA), "the session under the taken name must be untouched")
        val ok = SessionRestore.parse(cr("restore", nameA, "--name", "$nameA-2", "--wait", "10").out)
        assertTrue(ok is RestoreResult.Restored && ok.registry && ok.sessionId == sidA && ok.resumedId == sidA, "restored: $ok")
        // the same record once more, under the next free name: the conversation runs in …-2 now, no second process
        assertEquals(RestoreResult.RunningElsewhere("$nameA-2"), SessionRestore.parse(cr("restore", nameA, "--name", "$nameA-3", "--wait", "5").out))
        assertFalse("$nameA-3" in fixture.tmuxSessions())
        assertEquals(0, drift().exit)
        waitUntil(10_000, "drift to adopt both. log:\n${fixture.driftLog()}") { manifestNames().containsAll(listOf(nameA, "$nameA-2")) }
        assertTrue(File(fixture.home, ".claude-remote/forgotten.d/$nameA").exists().not(), "the open of $nameA lifted its tombstone")
    }

    /**
     * The app's path: tombstone + record + kill in ONE exec, restore with the app's inline command; the restored
     * session survives drift ticks and its marker says `restore`. Restoring the same record again — under its name or
     * under the offered `…-2`, as two taps or two devices would — is refused: the conversation already runs.
     */
    @Test
    fun theAppsCloseRecordAndInlineRestoreRoundTrip() {
        val dir = folder("projA")
        transcript(dir, sidA)
        open(nameA, dir, sidA)
        open(nameB, folder("projB"), sidB)
        assertEquals(0, drift().exit)
        File(fixture.home, ".claude-remote/opened.d").mkdirs(); File(fixture.home, ".claude-remote/opened.d/$nameA").writeText("{}")
        val t = sh(ManifestCommands.closeCommand(nameA, durable = true, by = "app:android-test"))
        assertEquals(0, t.exit, t.out)
        assertEquals(0, ManifestCommands.closeKillStatus(t.out), t.out)
        assertFalse(nameA in fixture.tmuxSessions(), "the one exec killed the pane")
        assertFalse(File(fixture.home, ".claude-remote/opened.d/$nameA").exists(), "and dropped its marker")
        // a second close of the gone pane (a retried exec): tombstone again, but no empty record over the real one
        assertEquals(0, sh(ManifestCommands.closeCommand(nameA, durable = true, by = "app:android-test")).exit)
        assertEquals(1, assertNotNull(ClosedRecords.parseRead(sh(ManifestCommands.readClosed()).out)).count { it.tmuxName == nameA })
        val rec = assertNotNull(ClosedRecords.parseRead(sh(ManifestCommands.readClosed()).out)).first()
        assertEquals("app:android-test", rec.by); assertNotNull(rec.pid); assertEquals(sidA, rec.sessionId)
        assertEquals(0, drift().exit)
        assertFalse(nameA in manifestNames())

        val res = SessionRestore.parse(sh(SessionRestore.restoreCommand(rec), timeoutMs = 30_000).out)
        assertTrue(res is RestoreResult.Restored && res.registry, "restore: $res")
        assertFalse(File(fixture.home, ".claude-remote/forgotten.d/$nameA").exists(), "the tombstone is lifted")
        repeat(2) { assertEquals(0, drift().exit) }
        assertTrue(nameA in manifestNames() && nameA in fixture.tmuxSessions(), "restored session in the manifest. log:\n${fixture.driftLog()}")
        val m = assertNotNull(ReconcileParse.parseOpened(sh(ManifestCommands.reconcileAll(listOf(nameA))).out)).single { it.tmuxName == nameA }
        assertEquals("restore", m.by)
        // a second restore of the same record: the conversation runs under its name now
        assertEquals(RestoreResult.RunningElsewhere(nameA), SessionRestore.parse(sh(SessionRestore.restoreCommand(rec)).out))
        assertEquals(RestoreResult.RunningElsewhere(nameA), SessionRestore.parse(sh(SessionRestore.restoreCommand(rec, tmuxName = "$nameA-2")).out))
        assertFalse("$nameA-2" in fixture.tmuxSessions(), "never a second claude over one transcript")
    }

    /**
     * A claude that has not written its registry yet (it is still starting, or `--resume` of a big transcript): only
     * its argv names the conversation. Restore and `cr-session open --resume` see it there and say where it runs.
     */
    @Test
    fun aConversationRunningWithoutARegistryIsFoundByItsArgv() {
        val dir = folder("projA")
        transcript(dir, sidA)
        open(nameA, dir, sidA)
        assertEquals("CLOSED", cr("close", nameA, "--token", token(nameA)).out.trim())
        val claude = File(fixture.shims, "claude").absolutePath
        assertEquals(0, fixture.tmux("new-session", "-d", "-s", nameB, "cd '${dir.absolutePath}' && STANDIN_NO_REGISTRY=1 $claude --resume $sidA").exit)
        waitUntil(10_000, "the registry-less claude") { sh("ps -eo comm=,args= | awk '\$1 == \"claude\" && /--resume $sidA/'").out.isNotBlank() }

        assertEquals(RestoreResult.RunningElsewhere(nameB), SessionRestore.parse(cr("restore", nameA, "--wait", "5").out))
        assertFalse(nameA in fixture.tmuxSessions(), "nothing started")
        val o = cr("open", "--name", "claude-server-projA-yolo--again", "--folder", dir.absolutePath, "--resume", sidA)
        assertEquals("RUNNING_ELSEWHERE $nameB", o.out.trim()); assertEquals(1, o.exit)
        assertFalse("claude-server-projA-yolo--again" in fixture.tmuxSessions())
    }

    /**
     * `cr-session close` takes the `--self` its `facts` was asked with (the token then matches) and refuses what the
     * app's snackbar refuses — a draft in the input box here — unless `--force`.
     */
    @Test
    fun closeRefusesABlockedSessionUnlessForced() {
        val rule = "\\342\\224\\200\\342\\224\\200\\342\\224\\200"
        val prompt = "\\342\\235\\257\\302\\240"
        val script = File(fixture.root, "typed.sh")
        script.writeText("printf 'history\\n$rule\\n${prompt}hello\\n$rule\\n  status\\n'; exec sleep 3600\n")
        val n = "claude-server-typed"
        assertEquals(0, fixture.tmux("new-session", "-d", "-s", n, "bash ${script.absolutePath}").exit)
        waitUntil(5_000, "$n drawn") { fixture.tmux("capture-pane", "-p", "-t", "=$n:").out.contains("status") }
        val tok = assertNotNull(CloseFactsCommands.token(cr("facts", n, "--self", "android-1-x").out))
        val r = cr("close", n, "--token", tok, "--self", "android-1-x")
        // the pane's own `sleep` is a foreign process too
        assertEquals("REFUSED_BLOCKED DRAFT,PROCESSES", r.out.trim()); assertEquals(1, r.exit)
        assertTrue(n in fixture.tmuxSessions(), "a refused close leaves the session")
        assertEquals("CLOSED", cr("close", n, "--token", tok, "--self", "android-1-x", "--force", "--reason", "test").out.trim())
        assertFalse(n in fixture.tmuxSessions())
        assertContains(File(fixture.home, ".claude-remote/jarvis-ops.log").readText(), "close $n by=jarvis kill=0 force=1")
    }

    /** The draft is read from the screen: typed text after `❯ ` counts, a dim suggestion does not; a shell's job is a foreign process. */
    @Test
    fun factsReadTheInputBoxAndTheProcessesOfThePane() {
        val rule = "\\342\\224\\200\\342\\224\\200\\342\\224\\200"
        val prompt = "\\342\\235\\257\\302\\240"
        fun pane(name: String, input: String) {
            val script = File(fixture.root, "$name.sh")
            script.writeText("printf 'history\\n$rule\\n$prompt$input\\n$rule\\n  status\\n'; exec sleep 3600\n")
            assertEquals(0, fixture.tmux("new-session", "-d", "-s", name, "bash ${script.absolutePath}").exit)
            waitUntil(5_000, "$name drawn") { fixture.tmux("capture-pane", "-p", "-t", "=$name:").out.contains("status") }
        }
        pane("claude-server-typed", "hello world")
        pane("claude-server-hint", "\\033[2mTry \"fix it\"\\033[0m")
        pane("claude-server-empty", "\\033[7m \\033[0m")
        assertContains(cr("facts", "claude-server-typed").out, "FACT draft 1")
        assertContains(cr("facts", "claude-server-hint").out, "FACT draft 0")
        assertContains(cr("facts", "claude-server-empty").out, "FACT draft 0")
        assertEquals("rozepsaný vstup", CloseFactsCommands.parse(cr("facts", "claude-server-typed").out).reasons.first { it.code.name == "DRAFT" }.text)

        // a stand-in claude: its sleep is claude's own child (an MCP-like helper), not a foreign process
        open(nameA, folder("projA"), sidA)
        assertContains(cr("facts", nameA).out, "FACT procs 0 -")
        // a job the pane's shell runs next to claude is
        assertEquals(0, fixture.tmux("new-session", "-d", "-s", "claude-server-job", "bash -c 'sleep 3600 & ${File(fixture.shims, "claude").absolutePath} --session-id $sidB; wait'").exit)
        waitUntil(10_000, "the job pane's claude") { cr("facts", "claude-server-job").out.contains(Regex("FACT pid [0-9]+")) }
        // counted once it is older than a statusline run (CloseFactsCommands.PROC_MIN_AGE_S)
        waitUntil(10_000, "the job counted") { cr("facts", "claude-server-job").out.contains("FACT procs 1 sleep") }
    }
}
