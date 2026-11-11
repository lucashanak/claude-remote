package com.clauderemote.integration

import com.clauderemote.integration.DriftWorld.Companion.rows
import com.clauderemote.integration.DriftWorld.Companion.str
import com.clauderemote.integration.DriftWorld.Companion.world
import com.clauderemote.model.TmuxNameParser
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Drift v20 (dávka E, dodatek po kritice: E1, E2 + E2b, E3, E4b, brakes) end to end: the installed scripts over a
 * private tmux server, ticks as the drift unit runs them (no locale, the unit's PATH: [DriftWorld.tick]). Every world
 * mixes the fault with healthy neighbours (D24), and every test fails on 1bdcbd5 (v19) for the reason it names (D18).
 */
class DriftV20IntegrationTest {

    private val uuid1 = "11111111-2222-3333-4444-555555555551"
    private val uuid2 = "11111111-2222-3333-4444-555555555552"
    private val uuid3 = "11111111-2222-3333-4444-555555555553"

    private fun DriftWorld.entry(name: String, folder: String? = File(home, "w/$name").absolutePath, mode: String = "YOLO",
                                 model: String = "DEFAULT", sid: String? = null, serverId: String = "srv", extra: String = ""): String {
        val f = if (folder == null) "null" else "\"$folder\""
        val s = if (sid == null) "null" else "\"$sid\""
        return """{"id":"id-$name","serverId":"$serverId","folder":$f,"mode":"$mode","model":"$model","tmuxSessionName":"$name","connectionType":"SSH","alias":"","claudeSessionId":$s,"createdAt":1,"accountSlug":null$extra}"""
    }

    private fun DriftWorld.manifest(vararg entries: String) = write("sessions.restore.json", "[" + entries.joinToString(",") + "]")

    private fun DriftWorld.restoreRows(): List<JsonObject> = rows(read("sessions.restore.json"))

    /** Each live session of ours is in the manifest exactly once, with its own folder. */
    private fun DriftWorld.assertEachLiveOnceWithItsFolder(names: List<String>) {
        val rows = restoreRows()
        for (n in names) {
            val mine = rows.filter { it.str("tmuxSessionName") == n }
            assertEquals(1, mine.size, "$n is in the manifest ${mine.size}× : ${read("sessions.restore.json")}")
            val folder = mine.single().str("folder")!!
            assertEquals(File(home, "w/$n").absolutePath, folder, "$n's folder, as written: ${read("sessions.restore.json")}")
        }
    }

    // ---- E1: a name with a space or a glob character -------------------------------------------------------------

    /**
     * E1 (a), kritika KK2: no real `claude-server-My`; the manifest knows `claude-server-My Project` (claude runs in it).
     * v19 split the name, `claude-server-My` matched `claude-server-My Project` by prefix, and self-heal started a
     * duplicate claude in its folder. Beside it a healthy session, and one with a non-ASCII name the manifest does not
     * know yet (adopted from a tick without locale: kills a loop without `-u`).
     */
    @Test fun aNameWithASpaceIsOneNameAndNothingIsRelaunched() = world {
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        val names = listOf("claude-server-My Project", "claude-server-ok", "claude-server-čaj")
        live("claude-server-My Project", claude = listOf("--dangerously-skip-permissions", "--resume", uuid1))
        live("claude-server-ok")
        live("claude-server-čaj")
        manifest(entry("claude-server-My Project", sid = uuid1), entry("claude-server-ok"))
        repeat(3) { tick() }
        assertEquals(emptyList(), launches(), "self-heal started something:\n${read("drift.log")}")
        assertEachLiveOnceWithItsFolder(names)
        assertEquals(3, restoreRows().size, read("sessions.restore.json"))
        assertFalse(read("drift.log").contains("self-heal"), read("drift.log"))
        assertEquals(names.sorted(), sessions())
    }

    /**
     * E1 (b): a real `claude-server-My` next to `claude-server-My Project`, a manifest without either. v19 wrote
     * `claude-server-My` twice and `claude-server-My Project` not at all (a reboot never brings it back).
     * E1 (c), KK1: both are adoptions the manifest does not know, so each must get its own folder, never `""`.
     */
    @Test fun aSessionWhoseNameIsAPrefixOfAnotherIsAdoptedOnceEachWithItsFolder() = world {
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        live("claude-server-My")
        live("claude-server-My Project", claude = listOf("--allow-dangerously-skip-permissions", "--session-id", uuid2))
        live("claude-server-ok")
        manifest(entry("claude-server-ok"))
        repeat(2) { tick() }
        assertEachLiveOnceWithItsFolder(listOf("claude-server-My", "claude-server-My Project", "claude-server-ok"))
        val project = restoreRows().single { it.str("tmuxSessionName") == "claude-server-My Project" }
        assertEquals("DEFAULT" to uuid2, project.str("mode") to project.str("claudeSessionId"), "its own claude, not the prefix twin's")
        assertEquals(emptyList(), launches())
        assertEquals(read("sessions.restore.json"), read("sessions.json"), "the mirror follows")
    }

    /**
     * E1 (d), KK3: drift runs in `~/.claude-remote/cores`, where a file `claude-server-aZZb` lies; v19's unquoted
     * list expanded the session `claude-server-a*b` to that file name and never adopted the session.
     */
    @Test fun aGlobCharacterInANameIsNeverExpandedToAFileName() = world {
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        File(cr, "cores").mkdirs()
        File(cr, "cores/claude-server-aZZb").writeText("")
        live("claude-server-a*b")
        live("claude-server-ok")
        manifest(entry("claude-server-ok"))
        repeat(2) { tick() }
        assertEachLiveOnceWithItsFolder(listOf("claude-server-a*b", "claude-server-ok"))
        assertFalse(read("sessions.restore.json").contains("claude-server-aZZb"))
        assertEquals(emptyList(), launches())
    }

    /**
     * E1 on a box with `prefixes` (`c:`/`a:` with a space, OWN_META): `claude-box-My Project` runs claude with the
     * app's flag, `claude-box-Our App` has the app's entry; both are adopted once each with their own metadata; v19
     * split both names into words and lost them.
     */
    @Test fun onABoxWithPrefixesNamesWithASpaceAreAdoptedWhole() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-My Project", claude = listOf("--model", "sonnet", "--allow-dangerously-skip-permissions", "--resume", uuid1))
        live("claude-box-Our App")
        live("claude-box-plain", claude = listOf("--dangerously-skip-permissions"))
        write("sessions.json", "[" + entry("claude-box-Our App", mode = "DEFAULT", model = "HAIKU", serverId = "srv-box") + "]")
        // a manifest is there: the app's entry comes through the overlay (OWN_META `a:`), not the first-manifest seed
        manifest()
        repeat(2) { tick() }
        assertEachLiveOnceWithItsFolder(listOf("claude-box-My Project", "claude-box-Our App", "claude-box-plain"))
        val rows = restoreRows().associateBy { it.str("tmuxSessionName") }
        assertEquals("SONNET", rows.getValue("claude-box-My Project").str("model"))
        assertEquals("srv-box" to "HAIKU", rows.getValue("claude-box-Our App").let { it.str("serverId") to it.str("model") }, "the app's entry as it wrote it")
        assertEquals(emptyList(), launches())
    }

    // ---- E2 and E2b: claude's arguments one by one ---------------------------------------------------------------

    /**
     * E2 (B1, kritika KK8, N-T11): on the box `box` the app's signature is an argument equal to one of its flags.
     * The flag's text inside a prompt, inside a path, or after `--` is no signature: not adopted, not relaunched;
     * the flag as its own argument first, in the middle or last is (live claude never has it last: kritika 3.1).
     */
    @Test fun theAppsSignatureIsAWholeArgumentBeforeDashDash() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-prompt", claude = listOf("-p", "please run with --dangerously-skip-permissions here"))
        live("claude-box-dashdash", claude = listOf("-p", "--", "--dangerously-skip-permissions"))
        live("claude-box-path", claude = listOf("--add-dir", "/tmp/a --allow-dangerously-skip-permissions b", "-p", "x"))
        live("claude-box-first", claude = listOf("--dangerously-skip-permissions", "--resume", uuid1))
        live("claude-box-middle", claude = listOf("--model", "opus", "--allow-dangerously-skip-permissions", "--resume", uuid2))
        live("claude-box-last", claude = listOf("--resume", uuid3, "--dangerously-skip-permissions"))
        repeat(2) { tick() }
        val rows = restoreRows().associateBy { it.str("tmuxSessionName") }
        assertEquals(setOf("claude-box-first", "claude-box-middle", "claude-box-last"), rows.keys, read("sessions.restore.json"))
        assertEquals("YOLO" to uuid1, rows.getValue("claude-box-first").let { it.str("mode") to it.str("claudeSessionId") })
        assertEquals(Triple("OPUS", "DEFAULT", uuid2), rows.getValue("claude-box-middle").let { Triple(it.str("model"), it.str("mode"), it.str("claudeSessionId")) })
        assertEquals("YOLO" to uuid3, rows.getValue("claude-box-last").let { it.str("mode") to it.str("claudeSessionId") })
        for (n in listOf("claude-box-prompt", "claude-box-dashdash", "claude-box-path")) fixture.tmux("kill-session", "-t", "=$n")
        repeat(2) { tick() }
        assertEquals(emptyList(), launches(), "a stranger was relaunched")
        assertFalse(read("drift.log").contains("self-heal"), read("drift.log"))
    }

    /**
     * E2b (kritika KK9): model, mode and session id come from claude's arguments, the first of each before `--`. A
     * prompt that names `--session-id deadbeef` behind the real `--session-id <uuid>` (claude's per-pid state file has
     * an id without a transcript, so it does not override) left v19 with `deadbeef` in the manifest; one that says
     * `--resume flag` with `f`; a prompt saying `--model opus` gave a new adoption OPUS.
     */
    @Test fun modelModeAndSessionIdAreTheArgumentsNotPiecesOfAPrompt() = world {
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        live("claude-server-sid", claude = listOf("--dangerously-skip-permissions", "--session-id", uuid1, "please run --session-id deadbeef now"))
        live("claude-server-word", claude = listOf("--dangerously-skip-permissions", "--resume", uuid2, "fix the --resume flag"))
        live("claude-server-model", claude = listOf("--model", "haiku", "--dangerously-skip-permissions", "--session-id", uuid3, "use --model opus and --permission-mode auto"))
        live("claude-server-ok", claude = listOf("--model", "fable", "--dangerously-skip-permissions", "--resume", uuid2))
        val pid = claudePid("claude-server-sid")!!
        File(home, ".claude/sessions").mkdirs()
        File(home, ".claude/sessions/$pid.json").writeText("""{"sessionId":"99999999-0000-0000-0000-000000000000"}""")
        manifest(entry("claude-server-sid"), entry("claude-server-word"), entry("claude-server-ok"))
        repeat(2) { tick() }
        val rows = restoreRows().associateBy { it.str("tmuxSessionName") }
        assertEquals(uuid1, rows.getValue("claude-server-sid").str("claudeSessionId"), read("sessions.restore.json"))
        assertEquals(uuid2, rows.getValue("claude-server-word").str("claudeSessionId"))
        assertEquals(Triple("HAIKU", "YOLO", uuid3), rows.getValue("claude-server-model").let { Triple(it.str("model"), it.str("mode"), it.str("claudeSessionId")) })
        assertEquals(uuid2, rows.getValue("claude-server-ok").str("claudeSessionId"))
    }

    // ---- E3: the first manifest of a box with another name --------------------------------------------------------

    /**
     * E3 (kritika KK10–KK12): on the box `box` without a manifest yet, drift's first manifest starts from the app's
     * entries of the box's own prefix. The app's entry whose session is not alive (folder there) is kept and self-heal
     * relaunches it (1 start, as on `server`); a tombstoned entry, an entry of another prefix, a `claude-server-*`
     * entry and one that is no object of a name are not taken; an entry whose folder is missing is kept but not
     * started (E4b). Three ticks leave the manifest and the mirror byte for byte the same. v19 wrote the live sessions
     * alone and its mirror dropped the rest from `sessions.json`.
     */
    @Test fun theFirstManifestOfABoxStartsFromTheAppsEntriesOfItsPrefix() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-live", claude = listOf("--dangerously-skip-permissions", "--resume", uuid1))
        folder("claude-box-dead")
        File(cr, "forgotten.d").mkdirs(); File(cr, "forgotten.d/claude-box-tomb").writeText("")
        write("forgotten", "claude-box-tomb2\n")   // a transient tombstone (review E-D T-ED5)
        folder("claude-box-tomb2")
        val app = listOf(
            entry("claude-box-tomb2", serverId = "srv-box"),
            entry("claude-box-live", serverId = "srv-box", sid = uuid1),
            entry("claude-box-dead", serverId = "srv-box", mode = "DEFAULT", model = "SONNET"),
            entry("claude-box-tomb", serverId = "srv-box"),
            entry("claude-other-x", serverId = "srv-other"),
            entry("claude-server-a", serverId = "srv"),
            entry("claude-box-nofolder", folder = File(home, "w/not-there").absolutePath, serverId = "srv-box"),
            entry("claude-box-dead", serverId = "srv-box-dup"),
            """{"folder":"/x"}""", "42",
        )
        write("sessions.json", "[" + app.joinToString(",") + "]")
        tick()
        waitUntil(15_000, "the relaunch of claude-box-dead") { "claude-box-dead" in sessions() && launches().isNotEmpty() }
        val names = restoreRows().map { it.str("tmuxSessionName") }
        assertEquals(listOf("claude-box-live", "claude-box-dead", "claude-box-nofolder"), names, read("sessions.restore.json"))
        val dead = restoreRows().single { it.str("tmuxSessionName") == "claude-box-dead" }
        assertEquals(Triple("srv-box", "DEFAULT", "SONNET"), Triple(dead.str("serverId"), dead.str("mode"), dead.str("model")), "the app's entry as it wrote it")
        assertEquals(1, launches().size, "one start: claude-box-dead\n${launches()}")
        assertContains(launches().single(), "--model sonnet --allow-dangerously-skip-permissions")
        assertContains(read("drift.log"), "drift: first manifest of this box — 3 entries from the app's sessions.json")
        assertFalse("claude-box-nofolder" in sessions())
        val md5 = md5("sessions.restore.json") to md5("sessions.json")
        repeat(2) { tick(); assertEquals(md5, md5("sessions.restore.json") to md5("sessions.json"), "the manifest or the mirror flapped") }
        assertEquals(read("sessions.restore.json"), read("sessions.json"))
        assertEquals(1, launches().size)
        assertEquals(1, read("drift.log").lines().count { "first manifest" in it }, "the seed is a migration, once")
    }

    /** E3: with a manifest there already (the box `server` always has one) the app's entries are never taken. */
    @Test fun aManifestThatIsThereIsNeverSeededAgain() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-live", claude = listOf("--dangerously-skip-permissions"))
        folder("claude-box-dead")
        manifest(entry("claude-box-live"))
        write("sessions.json", "[" + entry("claude-box-live") + "," + entry("claude-box-dead") + "]")
        // first prove the seed exists at all (D18: fails on v19), in a world of its own
        val seeded = world {
            installV20(TmuxNameParser.prefixFor("box"))
            folder("claude-box-dead")
            live("claude-box-live", claude = listOf("--dangerously-skip-permissions"))
            write("sessions.json", "[" + entry("claude-box-live") + "," + entry("claude-box-dead") + "]")
            tick()
            restoreRows().map { it.str("tmuxSessionName") }
        }
        assertEquals(listOf("claude-box-live", "claude-box-dead"), seeded)
        repeat(2) { tick() }
        assertEquals(listOf("claude-box-live"), restoreRows().map { it.str("tmuxSessionName") }, "a manifest that was there got the app's entries")
        assertEquals(emptyList(), launches())
        assertFalse(read("drift.log").contains("first manifest"))
    }

    /**
     * E3 (D4-adv #20): when the overlay of the app's entries fails (its jq), the tick's manifest misses those names
     * and the mirror must leave the app's `sessions.json` as it is; v19 copied the manifest over it and the app's
     * entry of the live `claude-box-new` was gone. The jq of the overlay fails through a wrapper first on the unit's PATH.
     */
    @Test fun theMirrorSkipsATickWhoseOverlayOfTheAppsEntriesFailed() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-a", claude = listOf("--dangerously-skip-permissions"))
        live("claude-box-new")
        manifest(entry("claude-box-a"))
        val app = "[" + entry("claude-box-a") + "," + entry("claude-box-new", serverId = "srv-box", model = "OPUS") + "]"
        write("sessions.json", app)
        File(home, ".local/bin/jq").apply {
            writeText("#!/bin/sh\ncase \" \$* \" in *\" --slurpfile \"*) exit 5;; esac\nexec /usr/bin/jq \"\$@\"\n"); setExecutable(true)
        }
        tick()
        assertEquals(app, read("sessions.json"), "the mirror overwrote the app's entries in a tick whose overlay failed")
        assertEquals(listOf("claude-box-a"), restoreRows().map { it.str("tmuxSessionName") })
        File(home, ".local/bin/jq").delete()
        tick()
        assertEquals(listOf("claude-box-a", "claude-box-new"), restoreRows().map { it.str("tmuxSessionName") })
        assertEquals(read("sessions.restore.json"), read("sessions.json"), "the next tick mirrors")
    }

    /** KK21/H13: the mirror is a new file renamed into place (never `cp` over the file the app reads), no temp left. */
    @Test fun theMirrorIsRenamedIntoPlace() = world {
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        live("claude-server-a")
        manifest(entry("claude-server-a"))
        write("sessions.json", "[]")
        val before = inode(File(cr, "sessions.json"))
        tick()
        assertEquals(read("sessions.restore.json"), read("sessions.json"))
        assertNotEquals(before, inode(File(cr, "sessions.json")), "the mirror wrote into the file the app reads")
        assertTrue(cr.list()!!.none { ".tmp." in it }, cr.list()!!.toList().toString())
    }

    // ---- E4b: an entry whose folder is missing --------------------------------------------------------------------

    /**
     * E4b (kritika KK16–KK18): drift reads an entry's folder as restore.sh does (one shared function): `~/x` and a
     * relative path lie under HOME and are relaunched; `""`, null and a missing folder are not, the entries stay in
     * the manifest, and the log says it once, not every tick (v19: "self-heal" every minute, and `""` started in
     * HOME). Beside them a healthy dead entry is relaunched. When the missing folder appears (a mount), it is relaunched.
     */
    @Test fun anEntryWhoseFolderIsMissingIsLeftOutOnceAndRelaunchedWhenItIsBack() = world {
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        // drift's cwd as on the box (it cds into ~/.claude-remote/cores): a relative folder is read under HOME, not there
        File(cr, "cores").mkdirs()
        live("claude-server-ok")
        folder("tilde"); folder("rel"); folder("claude-server-dead")
        manifest(
            entry("claude-server-ok"),
            entry("claude-server-tilde", folder = "~/w/tilde"),
            entry("claude-server-rel", folder = "w/rel"),
            entry("claude-server-empty", folder = ""),
            entry("claude-server-null", folder = null),
            entry("claude-server-gone", folder = "~/w/gone"),
            entry("claude-server-dead"),
        )
        tick()
        waitUntil(15_000, "three relaunches") { launches().size >= 3 }
        val starts = launches().map { it.substringBefore('|') }.sorted()
        assertEquals(listOf("w/claude-server-dead", "w/rel", "w/tilde").map { File(home, it).absolutePath }.sorted(), starts, launches().toString())
        repeat(2) { tick() }
        assertEquals(3, launches().size, "started again: ${launches()}")
        val log = read("drift.log").lines()
        assertEquals(1, log.count { "self-heal" in it }, read("drift.log"))
        assertEquals(1, log.count { "not relaunching, folder missing" in it }, read("drift.log"))
        assertContains(read("drift.log"), "not relaunching, folder missing (kept in the manifest): claude-server-empty claude-server-null claude-server-gone ")
        assertEquals(7, restoreRows().size, "kept in the manifest")
        // the folder comes back (a mount): relaunched; the rest is logged once more (the set changed)
        folder("gone")
        tick()
        waitUntil(15_000, "the relaunch of claude-server-gone") { launches().size == 4 }
        assertEquals(File(home, "w/gone").absolutePath, launches().last().substringBefore('|'))
        // a garbled state file only costs one more log line, never a relaunch decision
        File(cr, "drift.skipped").writeText("garbage\n")
        val lines = read("drift.log").lines().size
        tick(); tick()
        assertEquals(4, launches().size)
        assertTrue(read("drift.log").lines().size > lines, "a garbled state logs once more")
        val after = read("drift.log").lines().size
        tick()
        assertEquals(after, read("drift.log").lines().size, "and then nothing")
    }

    // ---- the brakes ---------------------------------------------------------------------------------------------

    /**
     * H6: `~/.claude-remote/drift.off` stops drift before anything: a tick changes no tmux session (the anchor
     * stays), no manifest, no mirror, writes no log and relaunches nothing; without it the same world is reconciled.
     * `drift-heal.off` is no brake: with it self-heal still relaunches (it only turns the socket guard off).
     */
    @Test fun driftOffStopsEverythingAndDriftHealOffIsNoBrake() = world {
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        live("claude-server-a"); live("__anchor__")
        folder("claude-server-dead")
        manifest(entry("claude-server-a"), entry("claude-server-dead"))
        write("sessions.json", "[]")
        File(cr, "drift.off").writeText("")
        val before = state() to panes()
        repeat(2) { tick() }
        assertEquals(before, state() to panes(), "a tick with drift.off changed something")
        assertFalse(File(cr, "drift.log").exists())
        File(cr, "drift.off").delete()
        File(cr, "drift-heal.off").writeText("")
        tick()
        waitUntil(15_000, "self-heal with drift-heal.off") { "claude-server-dead" in sessions() && launches().size == 1 }
        assertFalse("__anchor__" in sessions())
        assertEquals(read("sessions.restore.json"), read("sessions.json"))
    }


    // ---- marker v20: the step, the copies, the way back, two installers at once ---------------------------------

    private val scripts = listOf("restore.sh" to "RESTORE_EOF", "drift.sh" to "DRIFT_EOF", "tmux-sockheal.sh" to "SOCKHEAL_EOF")
    private val v20Installer get() = String(com.clauderemote.session.service.SessionPersistenceService.installRestoreStdin(TmuxNameParser.DEFAULT_PREFIX))
    private fun DriftWorld.script(name: String) = read(name)

    /**
     * The step on the live box, v19 → v20 (dodatek E "Marker v20"): the three scripts rewritten (install.log names
     * v19's compat line three times), the units stay, the replaced v19 restore.sh and drift.sh kept as `.v19` byte for
     * byte (no `.v18`: the box came straight from v19; no copy of tmux-sockheal.sh), and a v19 or v18 client after it
     * finds its marker in the compat line and rewrites nothing.
     */
    @Test fun theStepFromV19KeepsTheV19ScriptsAndOlderClientsLeaveV20Alone() = world {
        installV19(TmuxNameParser.DEFAULT_PREFIX)
        val logBefore = read("install.log").length
        val out = installV20(TmuxNameParser.DEFAULT_PREFIX)
        for (tag in listOf("RESTORE_SCRIPT_INSTALLED", "DRIFT_SCRIPT_INSTALLED", "SOCKHEAL_SCRIPT_INSTALLED", "RESTORE_UNIT_PRESENT")) assertTrue(tag in out.lines(), "$tag:\n$out")
        assertFalse("DRIFT_TIMER_INSTALLED" in out || "RESTORE_UNIT_INSTALLED" in out, "the units stay: no daemon-reload\n$out")
        val log = read("install.log").substring(logBefore)
        val compat19 = "# marker-compat (do NOT remove): " + (6..19).joinToString(" ") { "claude-remote-restore-v$it" }
        for ((name, _) in scripts) assertContains(log, "$name missing claude-remote-restore-v20 — rewriting; prior marker line: $compat19\n")
        for ((name, eof) in scripts.take(2)) {
            assertEquals(Goldens.heredoc(Goldens.v19, eof), read("$name.v19"), "$name.v19 is the v19 script")
            assertContains(log, "install: the v19 $name kept as $name.v19 (rollback)")
            assertFalse(File(cr, "$name.v18").exists(), "a .v18 of a v19 script")
        }
        assertFalse(File(cr, "tmux-sockheal.sh.v19").exists())
        val now = scripts.map { script(it.first) }
        assertEquals(scripts.map { Goldens.heredoc(v20Installer, it.second) }, now)
        for (old in listOf({ installV19(TmuxNameParser.DEFAULT_PREFIX) }, { installV18() }, { installV20(TmuxNameParser.DEFAULT_PREFIX) })) {
            val o = old()
            assertTrue("RESTORE_SCRIPT_PRESENT" in o.lines() && o.lines().none { it.endsWith("_SCRIPT_INSTALLED") }, "an install rewrote v20:\n$o")
        }
        assertEquals(now, scripts.map { script(it.first) }, "nothing rewritten after v20")
        assertEquals(scripts.take(2).map { Goldens.heredoc(Goldens.v19, it.second) }, scripts.take(2).map { read("${it.first}.v19") }, "the copies stay")
        assertTrue(cr.list()!!.none { ".tmp." in it }, "temp files left")
    }

    /** A box that skips v19 (still v18) gets the `.v18` copies, and no `.v19`. */
    @Test fun aBoxStraightFromV18KeepsTheV18Scripts() = world {
        installV18()
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        for ((name, eof) in scripts.take(2)) {
            assertEquals(Goldens.heredoc(Goldens.v18, eof), read("$name.v18"), "$name.v18")
            assertFalse(File(cr, "$name.v19").exists(), "$name.v19 of a v18 script")
        }
        assertEquals(scripts.map { Goldens.heredoc(v20Installer, it.second) }, scripts.map { script(it.first) })
    }

    /**
     * The way back and forth (kritika H §3.4, dodatek "Rollback"): v19 → v20 → the `.v19` copies moved back (the
     * rollback) → v19 ticks over the state v20 left (its `drift.skipped`, a manifest v20 wrote) → v20 again. Moving
     * back lasts only to the next v20 install, which writes a new `.v19`, byte for byte the first one. v19 reads what
     * v20 left without a word: its manifest is v20's (nothing rewritten, no session started that v20 had not), and
     * v20 after it changes nothing.
     */
    @Test fun theWayBackToV19AndForthAgainKeepsTheCopyAndTheState() = world {
        installV19(TmuxNameParser.DEFAULT_PREFIX)
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        val copies = scripts.take(2).map { read("${it.first}.v19") }
        live("claude-server-a", claude = listOf("--dangerously-skip-permissions", "--resume", uuid1))
        live("claude-server-b")
        manifest(entry("claude-server-a", sid = uuid1), entry("claude-server-b"), entry("claude-server-gone", folder = "~/w/not-there"))
        repeat(2) { tick() }
        assertEquals("claude-server-gone\n", read("drift.skipped"), "v20 left its state file")
        val sf = read("sessions.restore.json")
        val mirror = read("sessions.json")
        assertEquals(sf, mirror)
        // the rollback, as the lead would do it on a box (consent that day): the copies moved back
        for ((name, _) in scripts.take(2)) assertTrue(File(cr, "$name.v19").renameTo(File(cr, name)))
        assertEquals(scripts.take(2).map { Goldens.heredoc(Goldens.v19, it.second) }, scripts.take(2).map { script(it.first) })
        val panes = panes()
        repeat(2) { tick() }
        assertEquals(sf, read("sessions.restore.json"), "v19 rewrote the manifest v20 left")
        assertEquals(mirror, read("sessions.json"))
        assertEquals(panes, panes(), "v19 restarted a session")
        assertEquals(emptyList(), launches())
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        assertEquals(copies, scripts.take(2).map { read("${it.first}.v19") }, "the new .v19 is the first one byte for byte")
        tick()
        assertEquals(sf, read("sessions.restore.json"))
        assertEquals(panes, panes())
    }

    /**
     * Kritika H10: a v19 and a v20 installer at once over a v18 box — the end state only: each script ends whole, v19's
     * or v20's (never a mix inside a file); `.v18` is made once and is v18's; a `.v19` (if the v20 installer replaced
     * v19's work) is v19's; no temp file; the next v20 install leaves all three v20. A guard of properties v19 had too
     * (it passes on 1bdcbd5): the order of v20's renames is held by `DriftV20Test.anInstallCutShortReplacesNoScript`.
     * Three rounds, the v18 scripts put back each time.
     */
    @Test fun twoInstallersAtOnceLeaveEachScriptWhole() = world {
        installV18()
        val v18 = scripts.map { Goldens.heredoc(Goldens.v18, it.second) }
        val v19 = scripts.map { Goldens.heredoc(Goldens.v19, it.second) }
        val v20 = scripts.map { Goldens.heredoc(v20Installer, it.second) }
        repeat(3) { round ->
            scripts.forEachIndexed { i, (name, _) -> File(cr, name).writeText(v18[i]); File(cr, "$name.v18").delete(); File(cr, "$name.v19").delete() }
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val threads = listOf({ installV19(TmuxNameParser.DEFAULT_PREFIX) }, { installV20(TmuxNameParser.DEFAULT_PREFIX) }).map { f ->
                Thread { runCatching { f() }.onFailure { errors += it } }.apply { start() }
            }
            threads.forEach { it.join(120_000) }
            assertEquals(emptyList(), errors.toList(), "round $round")
            scripts.forEachIndexed { i, (name, _) ->
                val now = script(name)
                assertTrue(now == v19[i] || now == v20[i], "round $round: $name is neither v19's nor v20's")
                if (i < 2) {
                    assertEquals(v18[i], read("$name.v18"), "round $round: $name.v18")
                    if (File(cr, "$name.v19").exists()) assertEquals(v19[i], read("$name.v19"), "round $round: $name.v19")
                }
            }
            assertTrue(cr.list()!!.none { ".tmp." in it }, "round $round: temp files left: ${cr.list()!!.toList()}")
        }
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        assertEquals(v20, scripts.map { script(it.first) })
    }


    // ---- kolo 2 (dodatek po review E-D) --------------------------------------------------------------------------

    private val injAcct = "a\" ; echo PWNED >> \$HOME/pwned; : \""
    private val injSid = "x; echo PWNED-SID >> \$HOME/pwned"

    private fun jstr(v: String) = kotlinx.serialization.json.JsonPrimitive(v).toString()

    /**
     * A-ED1 (review E-D adv, pokus 4): the app's file names `claude-box-nekrachni.plus` (an old client's name), one with
     * `:` and one with a newline; tmux holds none of them as written (it makes `.`/`:` into `_`). The first manifest
     * takes none of them: one start (the healthy dead entry), and after `claude-box-nekrachni_plus` (if a version made it)
     * is closed — tombstones, kill, gone from sessions.json — three ticks start nothing, restore.log stays as it is and
     * the mirror never brings `.plus` back. E-D-1 seeded `.plus`, started it as `_plus` and restarted it after its close.
     */
    @Test fun aNameTmuxDoesNotHoldAsWrittenIsNeverSeeded() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-ok", claude = listOf("--dangerously-skip-permissions"))
        for (n in listOf("nekrachni.plus", "a:b", "claude-box-dead")) folder(n)
        val odd = listOf("claude-box-nekrachni.plus", "claude-box-a:b", "claude-box-x\nl")
        write("sessions.json", "[" + (odd.map { n ->
            """{"id":"x","serverId":"srv-box","folder":${jstr(File(home, "w/" + n.removePrefix("claude-box-")).absolutePath)},"mode":"YOLO","model":"DEFAULT","tmuxSessionName":${jstr(n)},"connectionType":"SSH","alias":"","claudeSessionId":null,"createdAt":1,"accountSlug":null}"""
        } + entry("claude-box-ok") + entry("claude-box-dead")).joinToString(",") + "]")
        tick()
        waitUntil(15_000, "the relaunch of claude-box-dead") { launches().size >= 1 && "claude-box-dead" in sessions() }
        val names = restoreRows().map { it.str("tmuxSessionName") }
        assertEquals(listOf("claude-box-ok", "claude-box-dead"), names, read("sessions.restore.json"))
        assertEquals(1, launches().size, launches().toString())
        // the user closes the session a version may have made of `.plus`
        if ("claude-box-nekrachni_plus" in sessions()) {
            File(cr, "forgotten.d").mkdirs(); File(cr, "forgotten.d/claude-box-nekrachni_plus").writeText("")
            write("forgotten", "claude-box-nekrachni_plus\n")
            fixture.tmux("kill-session", "-t", "=claude-box-nekrachni_plus")
        }
        val before = launches().size to read("restore.log")
        repeat(3) { tick() }
        assertEquals(before, launches().size to read("restore.log"), "a closed or impossible name was relaunched")
        for (n in odd) assertFalse(read("sessions.json").contains(jstr(n)), "the mirror brought back $n")
    }

    /**
     * A-ED1, the general guard: a name tmux cannot hold as written in the manifest (put there by an older drift's
     * seed, or by hand) is never "missing", so restore.sh does not run for it every tick; a healthy dead entry beside
     * it is relaunched once. On the box `server` such a name never reaches the manifest (only live names do).
     */
    @Test fun aNameTmuxCannotHoldInTheManifestIsNeverMissing() = world {
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        live("claude-server-ok")
        folder("claude-server-a.b"); folder("claude-server-dead")
        manifest(entry("claude-server-ok"), entry("claude-server-a.b"), entry("claude-server-dead"))
        tick()
        waitUntil(15_000, "the relaunch of claude-server-dead") { launches().size == 1 }
        val log = read("restore.log")
        repeat(3) { tick() }
        assertEquals(1, launches().size, launches().toString())
        assertEquals(log, read("restore.log"), "restore.sh ran again for a name tmux cannot hold")
        assertFalse("claude-server-a_b" in sessions())
        assertEquals(1, read("drift.log").lines().count { "self-heal" in it }, read("drift.log"))
    }

    /**
     * A-ED2 (pokus 3): an entry of the app's file with an account slug and a session id that are shell code. The first
     * manifest keeps neither (null), restore.sh starts the session without an account and without an id, and no
     * `pwned` file appears. A slug as the app makes it (`someone@example.cz`) stays.
     */
    @Test fun aSeedNeverCarriesASlugOrAnIdThatIsCode() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-ok", claude = listOf("--dangerously-skip-permissions"))
        folder("claude-box-inj"); folder("claude-box-acct"); folder("claude-box-dots")
        File(cr, "accounts/someone@example.cz").mkdirs()
        val inj = """{"id":"i","serverId":"srv-box","folder":"${File(home, "w/claude-box-inj").absolutePath}","mode":"YOLO","model":"DEFAULT","tmuxSessionName":"claude-box-inj","connectionType":"SSH","alias":"","claudeSessionId":${jstr(injSid)},"createdAt":1,"accountSlug":${jstr(injAcct)}}"""
        val good = """{"id":"g","serverId":"srv-box","folder":"${File(home, "w/claude-box-acct").absolutePath}","mode":"YOLO","model":"DEFAULT","tmuxSessionName":"claude-box-acct","connectionType":"SSH","alias":"","claudeSessionId":"$uuid1","createdAt":1,"accountSlug":"someone@example.cz"}"""
        val dots = entry("claude-box-dots").replace("\"accountSlug\":null", "\"accountSlug\":\"..\"")
        write("sessions.json", "[" + entry("claude-box-ok") + "," + inj + "," + good + "," + dots + "]")
        tick()
        waitUntil(15_000, "three relaunches") { launches().size >= 3 }
        assertEquals(null, restoreRows().single { it.str("tmuxSessionName") == "claude-box-dots" }.str("accountSlug"), "`..` is no slug")
        val rows = restoreRows().associateBy { it.str("tmuxSessionName") }
        assertEquals(null to null, rows.getValue("claude-box-inj").let { it.str("accountSlug") to it.str("claudeSessionId") })
        assertEquals("someone@example.cz" to uuid1, rows.getValue("claude-box-acct").let { it.str("accountSlug") to it.str("claudeSessionId") })
        val starts = launches().associateBy { it.substringBefore('|') }
        assertEquals("||--dangerously-skip-permissions", starts.getValue(File(home, "w/claude-box-inj").absolutePath).removePrefix(File(home, "w/claude-box-inj").absolutePath))
        assertEquals("|" + File(cr, "accounts/someone@example.cz").absolutePath + "|--dangerously-skip-permissions --session-id $uuid1",
            starts.getValue(File(home, "w/claude-box-acct").absolutePath).removePrefix(File(home, "w/claude-box-acct").absolutePath))
        assertEquals("||--dangerously-skip-permissions", starts.getValue(File(home, "w/claude-box-dots").absolutePath).removePrefix(File(home, "w/claude-box-dots").absolutePath))
        assertFalse(File(home, "pwned").exists(), File(home, "pwned").readTextOrEmpty())
    }

    /**
     * A-ED2 (pokus 19): the app's entry the B1 overlay takes (`a:`, a live session without claude) with a slug and an
     * id that are code: nulled; after the session dies, self-heal relaunches it without either and nothing runs.
     */
    @Test fun theOverlayOfTheAppsEntryNeverCarriesASlugOrAnIdThatIsCode() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        manifest()
        live("claude-box-ov")
        live("claude-box-dd"); live("claude-box-d")
        // review E-D, kolo 4: the overlay's own checks by behaviour, one case each
        val ovOdd = linkedMapOf(
            "claude-box-sid1" to ("deadbeef" to null), "claude-box-sid2" to ("aaaaaaaa-1111-2222-3333-" + "4".repeat(20) to null),
            "claude-box-s65" to (null to "a".repeat(65)), "claude-box-s0" to (null to ""), "claude-box-schar" to (null to "a;b"))
        for (n in ovOdd.keys) live(n)
        fun ov(n: String, sid: String?, acct: String?) = """{"id":"$n","serverId":"srv-box","folder":"${File(home, "w/$n").absolutePath}","mode":"YOLO","model":"DEFAULT","tmuxSessionName":"$n","connectionType":"SSH","alias":"","claudeSessionId":${sid?.let { jstr(it) } ?: "null"},"createdAt":1,"accountSlug":${acct?.let { jstr(it) } ?: "null"}}"""
        write("sessions.json", "[" + ovOdd.entries.joinToString("") { (n, v) -> ov(n, v.first, v.second) + "," } + """{"id":"o","serverId":"srv-box","folder":"${File(home, "w/claude-box-ov").absolutePath}","mode":"YOLO","model":"DEFAULT","tmuxSessionName":"claude-box-ov","connectionType":"SSH","alias":"","claudeSessionId":${jstr(injSid)},"createdAt":1,"accountSlug":${jstr(injAcct)}}""" +
            "," + entry("claude-box-dd").replace("\"accountSlug\":null", "\"accountSlug\":\"..\"") +
            "," + entry("claude-box-d").replace("\"accountSlug\":null", "\"accountSlug\":\".\"") + "]")
        tick()
        val row = restoreRows().single { it.str("tmuxSessionName") == "claude-box-ov" }
        assertEquals(null to null, row.str("accountSlug") to row.str("claudeSessionId"))
        // review E-D T-ED14: `..` and `.` are no slug, in the overlay as in the first manifest
        for (n in listOf("claude-box-dd", "claude-box-d")) assertEquals(null, restoreRows().single { it.str("tmuxSessionName") == n }.str("accountSlug"), n)
        for (n in ovOdd.keys) {
            val r = restoreRows().single { it.str("tmuxSessionName") == n }
            assertEquals(null to null, r.str("claudeSessionId") to r.str("accountSlug"), n)
        }
        fixture.tmux("kill-session", "-t", "=claude-box-ov")
        tick()
        waitUntil(15_000, "the relaunch of claude-box-ov") { launches().size == 1 }
        assertEquals(File(home, "w/claude-box-ov").absolutePath + "||--dangerously-skip-permissions", launches().single())
        assertFalse(File(home, "pwned").exists(), File(home, "pwned").readTextOrEmpty())
    }

    /**
     * A-ED2, restore.sh's own guard for an entry already in the manifest (v19 put it there, or anyone): restore.sh
     * builds the pane's command from words no shell reads, so a slug or id that is code stays a value: the session
     * starts (claude gets the odd values as written) and nothing in them runs. A slug that is also a path (it holds
     * `/`) starts without an account instead (T-ED13, logged). On the box `server`.
     */
    @Test fun restoreTakesASlugOrAnIdOfTheManifestAsAValueNeverAsCode() = world {
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        live("claude-server-ok")
        folder("claude-server-inj"); folder("claude-server-inj2")
        val codeSlug = "a\" ; touch pwned-acct ; : \""   // code, but no path
        fun e(n: String, acct: String) = """{"id":"$n","serverId":"srv","folder":"${File(home, "w/$n").absolutePath}","mode":"YOLO","model":"DEFAULT","tmuxSessionName":"$n","connectionType":"SSH","alias":"","claudeSessionId":${jstr(injSid)},"createdAt":1,"accountSlug":${jstr(acct)}}"""
        manifest(entry("claude-server-ok"), e("claude-server-inj", codeSlug), e("claude-server-inj2", injAcct))
        tick()
        waitUntil(15_000, "the relaunches of claude-server-inj and -inj2") { launches().size == 2 }
        val starts = launches().associateBy { it.substringBefore('|') }
        assertEquals("|" + File(cr, "accounts").absolutePath + "/" + codeSlug + "|--dangerously-skip-permissions --session-id " + injSid,
            starts.getValue(File(home, "w/claude-server-inj").absolutePath).removePrefix(File(home, "w/claude-server-inj").absolutePath))
        assertEquals("||--dangerously-skip-permissions --session-id " + injSid,
            starts.getValue(File(home, "w/claude-server-inj2").absolutePath).removePrefix(File(home, "w/claude-server-inj2").absolutePath))
        assertContains(read("restore.log"), "of claude-server-inj2 is a path — starting it without an account")
        assertFalse(File(home, "pwned").exists(), File(home, "pwned").readTextOrEmpty())
        assertFalse(File(home, "w/claude-server-inj/pwned-acct").exists(), "the slug ran")
    }

    /**
     * T-ED4 / A-ED6: a garbled, empty or directory `drift.skipped` there before a tick never holds back self-heal:
     * the healthy dead entry is relaunched each time; a directory in its place gets no line in drift.log and no file.
     */
    @Test fun aBrokenStateFileNeverHoldsBackSelfHeal() = world {
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        live("claude-server-ok")
        folder("claude-server-dead")
        manifest(entry("claude-server-ok"), entry("claude-server-dead"), entry("claude-server-gone", folder = File(home, "w/not-there").absolutePath))
        val st = File(cr, "drift.skipped")
        for ((round, make) in listOf<Pair<String, () -> Unit>>(
            "garbled" to { st.writeText("garbage\nclaude-server-dead\n") },
            "empty" to { st.writeText("") },
            "directory" to { st.delete(); st.mkdirs() },
        ).withIndex().map { it.value }) {
            make()
            val logBefore = read("drift.log").lines().count { "folder missing" in it }
            tick()
            waitUntil(15_000, "$round: the relaunch of claude-server-dead (launches ${launches()})") { "claude-server-dead" in sessions() && launches().isNotEmpty() }
            if (round == "directory") {
                repeat(2) { tick() }
                assertEquals(logBefore, read("drift.log").lines().count { "folder missing" in it }, "$round: a line every tick")
                assertEquals(emptyList(), st.list()!!.toList(), "$round: something written into the directory")
            }
            fixture.tmux("kill-session", "-t", "=claude-server-dead")
            File(home, "launches.log").delete()
            if (round != "directory") assertTrue(st.isFile, "$round: the state is written again")
        }
        assertTrue(cr.list()!!.none { ".tmp." in it }, cr.list()!!.toList().toString())
    }

    /**
     * K-ED1: the first manifest reads the app's file under the lock; a file cut in the middle (a write in progress)
     * postpones it: no manifest, no mirror, nothing started. Once the file is whole the next tick seeds from it. A file
     * broken for longer than two minutes is no write in progress: the manifest goes on without the app's entries.
     */
    @Test fun aHalfWrittenAppFilePostponesTheFirstManifest() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-live", claude = listOf("--dangerously-skip-permissions"))
        folder("claude-box-dead")
        val whole = "[" + entry("claude-box-live") + "," + entry("claude-box-dead") + "]"
        write("sessions.json", whole.substring(0, whole.length / 2))
        repeat(2) { tick() }
        assertFalse(File(cr, "sessions.restore.json").exists(), "a manifest without the app's entries: ${read("sessions.restore.json")}")
        assertEquals(whole.substring(0, whole.length / 2), read("sessions.json"), "mirrored")
        assertEquals(emptyList(), launches())
        write("sessions.json", whole)
        tick()
        waitUntil(15_000, "the relaunch of claude-box-dead") { launches().size == 1 }
        assertEquals(listOf("claude-box-live", "claude-box-dead"), restoreRows().map { it.str("tmuxSessionName") })
        // a broken file of long ago holds nothing back
        val stale = world {
            installV20(TmuxNameParser.prefixFor("box"))
            live("claude-box-live", claude = listOf("--dangerously-skip-permissions"))
            write("sessions.json", "[{\"broken")
            File(cr, "sessions.json").setLastModified(System.currentTimeMillis() - 10 * 60_000)
            tick()
            restoreRows().map { it.str("tmuxSessionName") }
        }
        assertEquals(listOf("claude-box-live"), stale)
    }

    /**
     * A-ED3: an app's file over 128 KB (one execve argument's limit) seeds all the same: the union reads its inputs from
     * files. 150 entries of ~1 KB whose folder is missing (never started) and one dead entry with its folder (started once);
     * "first manifest" logged once, when the manifest is written.
     */
    @Test fun aFirstManifestOver128KBIsWritten() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-live", claude = listOf("--dangerously-skip-permissions"))
        folder("claude-box-dead")
        // few entries, each long: restore.sh reads every entry with jq, so 700 of them made one tick slower than 90 s
        val pad = ",\"note\":\"" + "x".repeat(900) + "\""
        val filler = (0 until 150).map { entry("claude-box-old-$it", folder = "/nowhere/p-$it", serverId = "srv-box", extra = pad) }
        val text = "[" + (listOf(entry("claude-box-live"), entry("claude-box-dead")) + filler).joinToString(",") + "]"
        assertTrue(text.length > 140_000, "${text.length}")
        write("sessions.json", text)
        tick()
        waitUntil(15_000, "the relaunch of claude-box-dead") { launches().size == 1 }
        assertEquals(152, restoreRows().size)
        tick()
        assertEquals(1, read("drift.log").lines().count { "first manifest of this box — 152 entries" in it }, read("drift.log").take(2000))
        assertEquals(1, launches().size)
    }

    /**
     * T-ED7: a name ending in a space is one name with its space (`IFS=`), and a session of two panes is read from its
     * first pane (claude's id there), never from both.
     */
    @Test fun aNameEndingInASpaceAndASessionOfTwoPanes() = world {
        installV20(TmuxNameParser.DEFAULT_PREFIX)
        // tmux keeps the space; the fixture's listing trims names, so this one is checked by has-session
        fixture.tmux("new-session", "-d", "-s", "claude-server-tail ", "-c", folder("claude-server-tail ").absolutePath, "sleep", "86400")
        waitUntil(10_000, "the session with a space at its end") { fixture.tmux("has-session", "-t", "=claude-server-tail ").exit == 0 }
        live("claude-server-two", claude = listOf("--dangerously-skip-permissions", "--resume", uuid1))
        fixture.tmux("split-window", "-d", "-t", "=claude-server-two:", "sleep", "86400")
        waitUntil(10_000, "the second pane") { fixture.tmux("list-panes", "-t", "=claude-server-two:").out.lines().count { it.isNotBlank() } == 2 }
        manifest()
        repeat(2) { tick() }
        assertEachLiveOnceWithItsFolder(listOf("claude-server-tail ", "claude-server-two"))
        assertEquals(uuid1, restoreRows().single { it.str("tmuxSessionName") == "claude-server-two" }.str("claudeSessionId"))
        assertEquals(emptyList(), launches())
    }


    // ---- kolo 3 (dodatek po ověření E-D) ------------------------------------------------------------------------

    /**
     * A-ED8 / T-ED12: the first manifest keeps a session id only as a uuid and a slug only as 1 to 64 characters, each
     * matched on the whole string. A 40 KB hex id (E-D-2 kept it: tmux refused the command, "FAILED to restore" and
     * restore.sh every tick), an id and a slug ending in a newline (`$` let them through), a 100-character slug, a slug
     * with `;` or a space (T-ED17) and a uuid's prefix with a 40 KB tail (T-ED18) are null: each session starts once,
     * without them, and three more ticks run restore.sh no more.
     */
    @Test fun aSeedKeepsOnlyAUuidAndASlugOfAtMost64Characters() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-ok", claude = listOf("--dangerously-skip-permissions"))
        val odd = listOf("claude-box-long", "claude-box-nl", "claude-box-wide", "claude-box-semi", "claude-box-space", "claude-box-tail")
        for (n in odd) folder(n)
        File(cr, "accounts/someone@example.cz").mkdirs()
        fun e(n: String, sid: String?, acct: String?) = """{"id":"$n","serverId":"srv-box","folder":"${File(home, "w/$n").absolutePath}","mode":"YOLO","model":"DEFAULT","tmuxSessionName":"$n","connectionType":"SSH","alias":"","claudeSessionId":${sid?.let { jstr(it) } ?: "null"},"createdAt":1,"accountSlug":${acct?.let { jstr(it) } ?: "null"}}"""
        write("sessions.json", "[" + listOf(entry("claude-box-ok"), e("claude-box-long", "a".repeat(40_000), null),
            e("claude-box-nl", "$uuid1\n", "someone@example.cz\n"), e("claude-box-wide", null, "a".repeat(100) + "@example.cz"),
            // review E-D T-ED17: a slug outside the charset; T-ED18: a uuid's prefix with a 40 KB hex tail
            e("claude-box-semi", null, "a;b"), e("claude-box-space", null, "a b"),
            e("claude-box-tail", "aaaaaaaa-1111-2222-3333-" + "4".repeat(40_000), null)).joinToString(",") + "]")
        tick()
        waitUntil(20_000, "six relaunches: ${launches()}") { launches().size >= odd.size }
        val rows = restoreRows().associateBy { it.str("tmuxSessionName") }
        for (n in odd) {
            assertEquals(null to null, rows.getValue(n).let { it.str("claudeSessionId") to it.str("accountSlug") }, n)
            assertEquals(File(home, "w/$n").absolutePath + "||--dangerously-skip-permissions", launches().single { it.startsWith(File(home, "w/$n").absolutePath + "|") }, n)
        }
        repeat(3) { tick() }
        assertEquals(odd.size, launches().size, launches().toString())
        assertFalse("FAILED to restore" in read("restore.log"), read("restore.log").take(3000))
        assertEquals(1, read("restore.log").lines().count { "restore.sh start" in it }, "restore.sh ran again")
    }

    private fun DriftWorld.brokenApp(text: String = "[{\"broken") = write("sessions.json", text)

    /**
     * A-ED9 (review E-D adv R13): a broken sessions.json whose mtime is a year in the future is no write in progress: the
     * first tick writes the manifest from the live sessions and says so once (E-D-2 postponed it for a year, silently).
     */
    @Test fun anAppFileFromTheFutureDoesNotPostponeTheFirstManifest() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-live", claude = listOf("--dangerously-skip-permissions"))
        brokenApp()
        File(cr, "sessions.json").setLastModified(System.currentTimeMillis() + 365L * 24 * 3600_000)
        tick()
        assertEquals(listOf("claude-box-live"), restoreRows().map { it.str("tmuxSessionName") }, read("drift.log"))
        assertEquals(1, read("drift.log").lines().count { "first manifest from the live sessions" in it }, read("drift.log"))
    }

    /**
     * A-ED9 (R15): a file the app rewrites broken every tick postpones the first manifest at most 5 ticks, with one line
     * when the series opens and one when it ends; then the manifest comes from the live sessions. The state file
     * drift.seedhold goes with the manifest.
     */
    @Test fun aPostponementOfTheFirstManifestEndsAfterFiveTicks() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-live", claude = listOf("--dangerously-skip-permissions"))
        repeat(5) {
            brokenApp()
            tick()
            assertFalse(File(cr, "sessions.restore.json").exists(), "tick ${it + 1}: written")
        }
        brokenApp()
        tick()
        assertEquals(listOf("claude-box-live"), restoreRows().map { it.str("tmuxSessionName") })
        val log = read("drift.log").lines()
        assertEquals(1, log.count { "first manifest postponed" in it }, read("drift.log"))
        assertEquals(1, log.count { "first manifest from the live sessions" in it }, read("drift.log"))
        assertFalse(File(cr, "drift.seedhold").exists(), "the state outlived the manifest")
        assertTrue(cr.list()!!.none { ".tmp." in it }, cr.list()!!.toList().toString())
    }

    /**
     * A-ED9: drift.seedhold garbled counts from 0 (at most 5 more ticks), a directory in its place postpones nothing
     * (no state: the manifest comes at once), says nothing per tick and gets nothing written into it.
     */
    @Test fun aBrokenPostponementStateNeverPostponesForEver() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-live", claude = listOf("--dangerously-skip-permissions"))
        File(cr, "drift.seedhold").mkdirs()
        brokenApp()
        tick()
        assertEquals(listOf("claude-box-live"), restoreRows().map { it.str("tmuxSessionName") }, read("drift.log"))
        assertEquals(emptyList(), File(cr, "drift.seedhold").list()!!.toList())
        val garbled = world {
            installV20(TmuxNameParser.prefixFor("box"))
            live("claude-box-live", claude = listOf("--dangerously-skip-permissions"))
            write("drift.seedhold", "garbage\n")
            var ticks = 0
            while (!File(cr, "sessions.restore.json").exists() && ticks < 8) { brokenApp(); tick(); ticks++ }
            ticks
        }
        assertEquals(6, garbled, "garbled state: five postponements, then the manifest")
    }

    /** A-ED9 (R16): a directory in place of sessions.json gets no mirror and no file inside; the manifest is written. */
    @Test fun theMirrorNeverWritesIntoADirectory() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-live", claude = listOf("--dangerously-skip-permissions"))
        File(cr, "sessions.json").mkdirs()
        repeat(2) { tick() }
        assertEquals(listOf("claude-box-live"), restoreRows().map { it.str("tmuxSessionName") })
        assertEquals(emptyList(), File(cr, "sessions.json").list()!!.toList(), "written into the directory")
        assertTrue(cr.list()!!.none { ".tmp." in it }, cr.list()!!.toList().toString())
    }


    /**
     * Kolo 4 (R3-hold-tombstone): a postponed tick consumes no tombstone. A transient tombstone of a session the user
     * closed, while sessions.json is being written, is still there when the next tick seeds from the whole file: the
     * closed entry is not taken and not started. (Consumed in the postponed tick — no manifest, so "absent from it" —
     * the next tick would seed the closed session and relaunch it.)
     */
    @Test fun aPostponedTickConsumesNoTombstone() = world {
        installV20(TmuxNameParser.prefixFor("box"))
        live("claude-box-live", claude = listOf("--dangerously-skip-permissions"))
        folder("claude-box-closed")
        write("forgotten", "claude-box-closed\n")
        brokenApp()
        tick()
        assertFalse(File(cr, "sessions.restore.json").exists(), "postponed")
        assertEquals("claude-box-closed\n", read("forgotten"), "a postponed tick consumed the tombstone")
        write("sessions.json", "[" + entry("claude-box-live") + "," + entry("claude-box-closed") + "]")
        repeat(2) { tick() }
        assertEquals(listOf("claude-box-live"), restoreRows().map { it.str("tmuxSessionName") })
        assertEquals(emptyList(), launches(), "the closed session was started")
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private fun inode(f: File): Any? = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java).fileKey()
}
