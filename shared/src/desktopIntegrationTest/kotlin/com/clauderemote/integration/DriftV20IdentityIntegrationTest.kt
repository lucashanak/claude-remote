package com.clauderemote.integration

import com.clauderemote.integration.DriftWorld.Companion.world
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.TmuxNameParser
import com.clauderemote.session.ClaudeConfig
import com.clauderemote.session.service.SessionPersistenceService
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hard rule of dávka E (dodatek po kritice, "Test identity", kritika H7/H8/§4): on a box like `server` the v20
 * scripts do what v19 does, byte for byte, apart from an explicit list of intended differences. Two worlds per case,
 * v19 (`golden/restore-installer-v19.sh`, the installer 1bdcbd5 generated, installed whole by its own stdin) and v20
 * (the installer of this tree), each in its own sshd fixture over the same fixture of sessions; three ticks as the drift
 * unit runs them (no locale, the unit's PATH); everything a later version reads compared after normalizing only times,
 * HOME and pids. Variants: the socket guard on (the box's path) and `drift-heal.off`; N = no `prefixes` (the box
 * `server` today) and Q = a `prefixes` file and a manifest already there (`server` if a client ever wrote a line).
 *
 * The fixture: sessions with claude in the shapes the live box runs (built by `ClaudeConfig`, the app's builder, and
 * by restore.sh itself through self-heal: `--resume`, `--session-id`, `--model`, `--permission-mode`, an account in
 * claude's environ, a prompt as the last argument, flags with long values, a bare `claude`), sessions without claude,
 * dead entries (self-heal), a durable and a transient tombstone, `claude-server-lan-*`, a non-ASCII name and folder,
 * `__anchor__` and a foreign session.
 */
class DriftV20IdentityIntegrationTest {

    private val uA = "aaaaaaaa-1111-2222-3333-444444444401"
    private val uB = "aaaaaaaa-1111-2222-3333-444444444402"
    private val uC = "aaaaaaaa-1111-2222-3333-444444444403"
    private val uD = "aaaaaaaa-1111-2222-3333-444444444404"
    private val uE = "aaaaaaaa-1111-2222-3333-444444444405"
    private val uF = "aaaaaaaa-1111-2222-3333-444444444406"

    /** A synthetic prompt of [n] bytes in the position of the live box's prompts, with no option text in it. */
    private fun prompt(n: Int) = buildString { while (length < n) append("please look at the build and fix what fails ") }.take(n)

    private fun app(model: ClaudeModel, mode: ClaudeMode, vararg tail: String) =
        ClaudeConfig.modelArgs(model) + ClaudeConfig.modeArgs(mode) + tail.toList()

    private fun DriftWorld.entry(name: String, mode: String = "YOLO", model: String = "DEFAULT", sid: String? = null, acct: String? = null): String {
        val folder = File(home, "w/$name").absolutePath
        fun q(s: String?) = if (s == null) "null" else "\"$s\""
        return """{"id":"id-$name","serverId":"srv-1","folder":"$folder","mode":"$mode","model":"$model","tmuxSessionName":"$name","connectionType":"SSH","alias":"","claudeSessionId":${q(sid)},"createdAt":1700000000000,"accountSlug":${q(acct)}}"""
    }

    private class Outcome(val state: Map<String, String>, val panes: List<String>, val logGrew: List<Long>) {
        override fun equals(other: Any?) = other is Outcome && state == other.state
        override fun hashCode() = state.hashCode()
        override fun toString() = state.entries.joinToString("\n") { "== ${it.key}\n${it.value}" }
    }

    /**
     * One world: install [version] (19 = the golden, 20 = this tree), the fixture, three ticks. [q]: the box has a
     * `prefixes` line (`claude-box-`) and the manifest exists. [extra]: the cases v20 changes on purpose (E1, E2b, E4b).
     * [mix]: after the install, the other version's drift.sh (`"drift"`) or restore.sh (`"restore"`) is put in place.
     */
    private fun run(version: Int, q: Boolean, healOff: Boolean, extra: Boolean = false, mix: String? = null): Outcome = world {
        val prefix = if (q) TmuxNameParser.prefixFor("box") else TmuxNameParser.DEFAULT_PREFIX
        if (version == 19) installV19(prefix) else installV20(prefix)
        if (mix != null) {
            val other = if (version == 19) String(SessionPersistenceService.installRestoreStdin(prefix)) else Goldens.v19
            val (file, eof) = if (mix == "drift") "drift.sh" to "DRIFT_EOF" else "restore.sh" to "RESTORE_EOF"
            File(cr, file).writeText(Goldens.heredoc(other, eof))
        }
        if (healOff) File(cr, "drift-heal.off").writeText("")
        // live sessions, claude in the shapes of the live box
        live("claude-server-app", claude = app(ClaudeModel.OPUS, ClaudeMode.YOLO, "--resume", uA))
        live("claude-server-acct", claude = app(ClaudeModel.HAIKU, ClaudeMode.AUTO_ACCEPT, "--session-id", uB),
            env = mapOf("CLAUDE_CONFIG_DIR" to File(home, ".claude-remote/accounts/acct1").absolutePath))
        live("claude-server-prompt", claude = listOf("--dangerously-skip-permissions", "--model", "opus", "--session-id", uC, prompt(600)))
        live("claude-server-flags", claude = listOf("--append-system-prompt", prompt(2821), "--add-dir", "/x", "--effort", "high",
            "--setting-sources", "user", "--dangerously-skip-permissions", "--resume", uD))
        live("claude-server-bare", claude = emptyList())
        live("claude-server-shell")
        live("claude-server-lan-1")
        live("claude-server-kůň", folder = folder("žluťoučký"))
        live("claude-box-b", claude = listOf("--allow-dangerously-skip-permissions"))
        live("claude-box-stray")
        live("claude-box-app")
        live("__anchor__")
        live("other")
        // transcripts: dead-yolo resumes, dead-auto starts fresh with its id
        File(home, ".claude/projects/-w").mkdirs()
        File(home, ".claude/projects/-w/$uE.jsonl").writeText("{}\n")
        for (n in listOf("claude-server-dead-yolo", "claude-server-dead-auto", "claude-server-dead-acct", "claude-server-closed", "claude-server-old")) folder(n)
        File(cr, "accounts/someone@example.cz").mkdirs()
        val entries = mutableListOf(
            entry("claude-server-app", model = "OPUS", sid = uA),
            entry("claude-server-acct", mode = "AUTO_ACCEPT", model = "HAIKU", sid = uB),
            entry("claude-server-shell"),
            entry("claude-server-dead-yolo", model = "FABLE", sid = uE),
            entry("claude-server-dead-auto", mode = "AUTO", sid = uF),
            entry("claude-server-dead-acct", mode = "NORMAL", model = "SONNET", acct = "someone@example.cz"),
            entry("claude-server-closed"),
            entry("claude-server-old"),
        )
        if (q) entries += entry("claude-box-b", mode = "DEFAULT")
        if (extra) {
            live("claude-server-My Project", claude = listOf("--dangerously-skip-permissions", "--resume", uA))
            entries += entry("claude-server-My Project", sid = uA)
            File(cr, "cores").mkdirs(); File(cr, "cores/claude-server-aZZb").writeText("")
            live("claude-server-a*b")
            entries += """{"id":"id-gone","serverId":"srv-1","folder":"${File(home, "w/not-there").absolutePath}","mode":"YOLO","model":"DEFAULT","tmuxSessionName":"claude-server-gone","connectionType":"SSH","alias":"","claudeSessionId":null,"createdAt":1,"accountSlug":null}"""
            live("claude-server-sid", claude = listOf("--dangerously-skip-permissions", "--session-id", uC, "please run --session-id deadbeef now"))
            File(home, ".claude/sessions").mkdirs()
            File(home, ".claude/sessions/${claudePid("claude-server-sid")}.json").writeText("""{"sessionId":"bbbbbbbb-0000-0000-0000-000000000000"}""")
            entries += entry("claude-server-sid")
        }
        write("sessions.restore.json", "[" + entries.joinToString(",") + "]")
        if (q) write("sessions.json", "[" + entry("claude-box-app", mode = "DEFAULT", model = "HAIKU") + "]")
        File(cr, "forgotten.d").mkdirs(); File(cr, "forgotten.d/claude-server-closed").writeText("")
        write("forgotten", "claude-server-old\n")

        tick()
        waitUntil(20_000, "self-heal's three relaunches: ${launches()}\n${read("drift.log")}") {
            launches().size >= 3 && listOf("claude-server-dead-yolo", "claude-server-dead-auto", "claude-server-dead-acct").all { claudePid(it) != null }
        }
        val panes1 = panes()
        val log = File(cr, "drift.log")
        val sizes = mutableListOf<Long>()
        repeat(2) { val before = log.length(); tick(); sizes += log.length() - before }
        assertEquals(panes1, panes(), "a session was restarted between tick 1 and tick 3 (pane_pid or session_created changed)")
        assertTrue(cr.list()!!.none { it.startsWith(".drift.") || ".tmp." in it }, "a tick left files: ${cr.list()!!.toList()}")
        Outcome(state(), panes1.map { it.substringBefore('\t') }, sizes)
    }

    private fun assertIdentity(q: Boolean, healOff: Boolean) {
        val v19 = run(19, q, healOff)
        val v20 = run(20, q, healOff)
        assertEquals(v19.toString(), v20.toString(), "v20 = v19 (q=$q, drift-heal.off=$healOff)")
        assertEquals(v19.panes, v20.panes)
        assertEquals(listOf(0L, 0L), v19.logGrew, "v19: drift.log grew in a tick without a change")
        assertEquals(listOf(0L, 0L), v20.logGrew, "drift.log grew in a tick without a change")
        // the fixture did what it is for: the three dead entries relaunched by restore.sh's builder, the rest adopted
        assertEquals(3, v20.state.getValue("launches").lines().size, v20.state.getValue("launches"))
        for (n in listOf("claude-server-prompt", "claude-server-flags", "claude-server-bare", "claude-server-lan-1", "claude-server-kůň")) {
            assertTrue("\"$n\"" in v20.state.getValue("sessions.restore.json"), "$n adopted")
        }
    }

    @Test fun onServerV20IsV19WithTheGuard() = assertIdentity(q = false, healOff = false)

    @Test fun onServerV20IsV19WithDriftHealOff() = assertIdentity(q = false, healOff = true)

    @Test fun withAPrefixesFileAndAManifestV20IsV19WithTheGuard() = assertIdentity(q = true, healOff = false)

    @Test fun withAPrefixesFileAndAManifestV20IsV19WithDriftHealOff() = assertIdentity(q = true, healOff = true)

    /**
     * The intended differences, and nothing else: the same world plus `claude-server-My Project` (E1, a space),
     * `claude-server-a*b` with `cores/claude-server-aZZb` (E1, a glob), an entry whose folder is missing (E4b) and a
     * prompt naming `--session-id deadbeef` behind the real one (E2b). Every line only one version wrote is listed
     * (a multiset, sorted). Run on the box `server` with the guard, with `drift-heal.off`, and with a `prefixes` file
     * and a manifest (review E-D T-ED1).
     */
    private fun intendedDiff(q: Boolean, healOff: Boolean): Map<String, List<String>> {
        val v19 = run(19, q = q, healOff = healOff, extra = true)
        val v20 = run(20, q = q, healOff = healOff, extra = true)
        val diff = v19.state.keys.associateWith { k ->
            val a = v19.state.getValue(k).lines().groupingBy { it }.eachCount()
            val b = v20.state.getValue(k).lines().groupingBy { it }.eachCount()
            (a.keys + b.keys).flatMap { l ->
                val n = (a[l] ?: 0) - (b[l] ?: 0)
                if (n > 0) List(n) { "-$l" } else List(-n) { "+$l" }
            }.sorted()
        }.filterValues { it.isNotEmpty() }
        println("intended differences v19 → v20 (q=$q, drift-heal.off=$healOff):\n" +
            diff.entries.joinToString("\n") { "${it.key}:\n" + it.value.joinToString("\n") { l -> "  $l" } } + "\n<end>")
        return diff
    }

    @Test fun theOnlyDifferencesAreTheIntendedOnes() = assertEquals(INTENDED, intendedDiff(q = false, healOff = false))

    @Test fun theOnlyDifferencesAreTheIntendedOnesWithDriftHealOff() = assertEquals(INTENDED_HEAL_OFF, intendedDiff(q = false, healOff = true))

    @Test fun theOnlyDifferencesAreTheIntendedOnesWithAPrefixesFile() = assertEquals(INTENDED_Q, intendedDiff(q = true, healOff = false))

    /**
     * H10: an install cut short (or two installers at once) can leave drift.sh of one version next to restore.sh of
     * the other until the next launch: both mixes do what v19 does over the same world (no extra relaunch, no entry lost).
     */
    @Test fun aMixOfV19AndV20ScriptsDoesWhatV19Does() {
        val v19 = run(19, q = false, healOff = false)
        assertEquals(v19.toString(), run(20, q = false, healOff = false, mix = "drift").toString(), "drift v19 + restore v20")
        assertEquals(v19.toString(), run(19, q = false, healOff = false, mix = "drift").toString(), "drift v20 + restore v19")
    }

    private companion object {
        /**
         * Every line only one version wrote (`-` v19 only, `+` v20 only; a multiset, sorted), each reviewed
         * (`E-rozhodnuti-D.md`, "zamýšlené rozdíly"). The manifest, its mirror and highwater: v19 had a row
         * `claude-server-My` (the folder and session id of `claude-server-My Project`) and `claude-server-sid` with
         * `deadbeef`; v20 has `claude-server-a*b` (v19 expanded it to the file in `cores/`) and the real id. drift.log:
         * v19 relaunched `claude-server-gone` (folder missing) and the duplicate `claude-server-My`, then the missing
         * folder again every tick (three flushed ticks); v20 logs the missing folder once and relaunches only the three
         * dead entries. restore.log: v19 ran restore.sh three times (the duplicate in tick 1). tmux and launches: the
         * duplicate claude v19 started.
         */
        /** With `drift-heal.off` the same lines as with the guard. */
        val INTENDED_HEAL_OFF: Map<String, List<String>> get() = INTENDED

        /** With a `prefixes` line and a manifest: the same, but drift.log's LIVE lines name the box's sessions too. */
        val INTENDED_Q: Map<String, List<String>> = mapOf(
            "sessions.restore.json" to listOf(
                "+    \"claudeSessionId\": \"aaaaaaaa-1111-2222-3333-444444444403\",",
                "+    \"claudeSessionId\": null,",
                "+    \"folder\": \"<HOME>/w/claude-server-a*b\",",
                "+    \"id\": \"claude-server-a*b\",",
                "+    \"tmuxSessionName\": \"claude-server-a*b\",",
                "-    \"claudeSessionId\": \"aaaaaaaa-1111-2222-3333-444444444401\",",
                "-    \"claudeSessionId\": \"deadbeef\",",
                "-    \"folder\": \"<HOME>/w/claude-server-My Project\",",
                "-    \"id\": \"claude-server-My\",",
                "-    \"tmuxSessionName\": \"claude-server-My\",",
            ),
            "sessions.json" to listOf(
                "+    \"claudeSessionId\": \"aaaaaaaa-1111-2222-3333-444444444403\",",
                "+    \"claudeSessionId\": null,",
                "+    \"folder\": \"<HOME>/w/claude-server-a*b\",",
                "+    \"id\": \"claude-server-a*b\",",
                "+    \"tmuxSessionName\": \"claude-server-a*b\",",
                "-    \"claudeSessionId\": \"aaaaaaaa-1111-2222-3333-444444444401\",",
                "-    \"claudeSessionId\": \"deadbeef\",",
                "-    \"folder\": \"<HOME>/w/claude-server-My Project\",",
                "-    \"id\": \"claude-server-My\",",
                "-    \"tmuxSessionName\": \"claude-server-My\",",
            ),
            "manifests/highwater.json" to listOf(
                "+    \"claudeSessionId\": \"aaaaaaaa-1111-2222-3333-444444444403\",",
                "+    \"claudeSessionId\": null,",
                "+    \"folder\": \"<HOME>/w/claude-server-a*b\",",
                "+    \"id\": \"claude-server-a*b\",",
                "+    \"tmuxSessionName\": \"claude-server-a*b\",",
                "-    \"claudeSessionId\": \"aaaaaaaa-1111-2222-3333-444444444401\",",
                "-    \"claudeSessionId\": \"deadbeef\",",
                "-    \"folder\": \"<HOME>/w/claude-server-My Project\",",
                "-    \"id\": \"claude-server-My\",",
                "-    \"tmuxSessionName\": \"claude-server-My\",",
            ),
            "drift.log" to listOf(
                "+LIVE=[\"claude-box-app\",\"claude-box-b\",\"claude-server-My Project\",\"claude-server-a*b\",\"claude-server-acct\",\"claude-server-app\",\"claude-server-bare\",\"claude-server-flags\",\"claude-server-kůň\",\"claude-server-lan-1\",\"claude-server-prompt\",\"claude-server-shell\",\"claude-server-sid\"]",
                "+[<T>] drift: not relaunching, folder missing (kept in the manifest): claude-server-gone ",
                "+[<T>] drift: self-heal — relaunching missing: claude-server-dead-yolo claude-server-dead-auto claude-server-dead-acct ",
                "------ <T> drift start -----",
                "------ <T> drift start -----",
                "-LIVE=[\"claude-box-app\",\"claude-box-b\",\"claude-server-My\",\"claude-server-My\",\"claude-server-acct\",\"claude-server-app\",\"claude-server-bare\",\"claude-server-dead-acct\",\"claude-server-dead-auto\",\"claude-server-dead-yolo\",\"claude-server-flags\",\"claude-server-kůň\",\"claude-server-lan-1\",\"claude-server-prompt\",\"claude-server-shell\",\"claude-server-sid\"]",
                "-LIVE=[\"claude-box-app\",\"claude-box-b\",\"claude-server-My\",\"claude-server-My\",\"claude-server-acct\",\"claude-server-app\",\"claude-server-bare\",\"claude-server-dead-acct\",\"claude-server-dead-auto\",\"claude-server-dead-yolo\",\"claude-server-flags\",\"claude-server-kůň\",\"claude-server-lan-1\",\"claude-server-prompt\",\"claude-server-shell\",\"claude-server-sid\"]",
                "-LIVE=[\"claude-box-app\",\"claude-box-b\",\"claude-server-My\",\"claude-server-acct\",\"claude-server-app\",\"claude-server-bare\",\"claude-server-flags\",\"claude-server-kůň\",\"claude-server-lan-1\",\"claude-server-prompt\",\"claude-server-shell\",\"claude-server-sid\"]",
                "-[<T>] drift: self-heal — relaunching missing: claude-server-dead-yolo claude-server-dead-auto claude-server-dead-acct claude-server-gone claude-server-My ",
                "-[<T>] drift: self-heal — relaunching missing: claude-server-gone ",
                "-[<T>] drift: self-heal — relaunching missing: claude-server-gone ",
            ),
            "restore.log" to listOf(
                "------ <T> restore.sh start (pid=<P>) -----",
                "------ <T> restore.sh start (pid=<P>) -----",
                "-Restored claude-server-My (<HOME>/w/claude-server-My Project) [uuid=aaaaaaaa-1111-2222-3333-444444444401]",
                "-no transcript at  — launching fresh with --session-id aaaaaaaa-1111-2222-3333-444444444401",
                "-restore source: <HOME>/.claude-remote/sessions.restore.json",
                "-restore source: <HOME>/.claude-remote/sessions.restore.json",
                "-skip claude-server-gone — folder <HOME>/w/not-there missing",
                "-skip claude-server-gone — folder <HOME>/w/not-there missing",
            ),
            "tmux" to listOf(
                "-claude-server-My",
            ),
            "launches" to listOf(
                "-<HOME>/w/claude-server-My Project||--dangerously-skip-permissions --session-id aaaaaaaa-1111-2222-3333-444444444401",
            ),
        )

        val INTENDED: Map<String, List<String>> = mapOf(
            "sessions.restore.json" to listOf(
                "+    \"claudeSessionId\": \"aaaaaaaa-1111-2222-3333-444444444403\",",
                "+    \"claudeSessionId\": null,",
                "+    \"folder\": \"<HOME>/w/claude-server-a*b\",",
                "+    \"id\": \"claude-server-a*b\",",
                "+    \"tmuxSessionName\": \"claude-server-a*b\",",
                "-    \"claudeSessionId\": \"aaaaaaaa-1111-2222-3333-444444444401\",",
                "-    \"claudeSessionId\": \"deadbeef\",",
                "-    \"folder\": \"<HOME>/w/claude-server-My Project\",",
                "-    \"id\": \"claude-server-My\",",
                "-    \"tmuxSessionName\": \"claude-server-My\",",
            ),
            "sessions.json" to listOf(
                "+    \"claudeSessionId\": \"aaaaaaaa-1111-2222-3333-444444444403\",",
                "+    \"claudeSessionId\": null,",
                "+    \"folder\": \"<HOME>/w/claude-server-a*b\",",
                "+    \"id\": \"claude-server-a*b\",",
                "+    \"tmuxSessionName\": \"claude-server-a*b\",",
                "-    \"claudeSessionId\": \"aaaaaaaa-1111-2222-3333-444444444401\",",
                "-    \"claudeSessionId\": \"deadbeef\",",
                "-    \"folder\": \"<HOME>/w/claude-server-My Project\",",
                "-    \"id\": \"claude-server-My\",",
                "-    \"tmuxSessionName\": \"claude-server-My\",",
            ),
            "manifests/highwater.json" to listOf(
                "+    \"claudeSessionId\": \"aaaaaaaa-1111-2222-3333-444444444403\",",
                "+    \"claudeSessionId\": null,",
                "+    \"folder\": \"<HOME>/w/claude-server-a*b\",",
                "+    \"id\": \"claude-server-a*b\",",
                "+    \"tmuxSessionName\": \"claude-server-a*b\",",
                "-    \"claudeSessionId\": \"aaaaaaaa-1111-2222-3333-444444444401\",",
                "-    \"claudeSessionId\": \"deadbeef\",",
                "-    \"folder\": \"<HOME>/w/claude-server-My Project\",",
                "-    \"id\": \"claude-server-My\",",
                "-    \"tmuxSessionName\": \"claude-server-My\",",
            ),
            "drift.log" to listOf(
                "+LIVE=[\"claude-server-My Project\",\"claude-server-a*b\",\"claude-server-acct\",\"claude-server-app\",\"claude-server-bare\",\"claude-server-flags\",\"claude-server-kůň\",\"claude-server-lan-1\",\"claude-server-prompt\",\"claude-server-shell\",\"claude-server-sid\"]",
                "+[<T>] drift: not relaunching, folder missing (kept in the manifest): claude-server-gone ",
                "+[<T>] drift: self-heal — relaunching missing: claude-server-dead-yolo claude-server-dead-auto claude-server-dead-acct ",
                "------ <T> drift start -----",
                "------ <T> drift start -----",
                "-LIVE=[\"claude-server-My\",\"claude-server-My\",\"claude-server-acct\",\"claude-server-app\",\"claude-server-bare\",\"claude-server-dead-acct\",\"claude-server-dead-auto\",\"claude-server-dead-yolo\",\"claude-server-flags\",\"claude-server-kůň\",\"claude-server-lan-1\",\"claude-server-prompt\",\"claude-server-shell\",\"claude-server-sid\"]",
                "-LIVE=[\"claude-server-My\",\"claude-server-My\",\"claude-server-acct\",\"claude-server-app\",\"claude-server-bare\",\"claude-server-dead-acct\",\"claude-server-dead-auto\",\"claude-server-dead-yolo\",\"claude-server-flags\",\"claude-server-kůň\",\"claude-server-lan-1\",\"claude-server-prompt\",\"claude-server-shell\",\"claude-server-sid\"]",
                "-LIVE=[\"claude-server-My\",\"claude-server-acct\",\"claude-server-app\",\"claude-server-bare\",\"claude-server-flags\",\"claude-server-kůň\",\"claude-server-lan-1\",\"claude-server-prompt\",\"claude-server-shell\",\"claude-server-sid\"]",
                "-[<T>] drift: self-heal — relaunching missing: claude-server-dead-yolo claude-server-dead-auto claude-server-dead-acct claude-server-gone claude-server-My ",
                "-[<T>] drift: self-heal — relaunching missing: claude-server-gone ",
                "-[<T>] drift: self-heal — relaunching missing: claude-server-gone ",
            ),
            "restore.log" to listOf(
                "------ <T> restore.sh start (pid=<P>) -----",
                "------ <T> restore.sh start (pid=<P>) -----",
                "-Restored claude-server-My (<HOME>/w/claude-server-My Project) [uuid=aaaaaaaa-1111-2222-3333-444444444401]",
                "-no transcript at  — launching fresh with --session-id aaaaaaaa-1111-2222-3333-444444444401",
                "-restore source: <HOME>/.claude-remote/sessions.restore.json",
                "-restore source: <HOME>/.claude-remote/sessions.restore.json",
                "-skip claude-server-gone — folder <HOME>/w/not-there missing",
                "-skip claude-server-gone — folder <HOME>/w/not-there missing",
            ),
            "tmux" to listOf(
                "-claude-server-My",
            ),
            "launches" to listOf(
                "-<HOME>/w/claude-server-My Project||--dangerously-skip-permissions --session-id aaaaaaaa-1111-2222-3333-444444444401",
            ),
        )
    }
}
