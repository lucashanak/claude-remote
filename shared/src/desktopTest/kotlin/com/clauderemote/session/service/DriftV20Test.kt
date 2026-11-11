package com.clauderemote.session.service

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.TmuxNameParser
import com.clauderemote.session.ClaudeConfig
import com.clauderemote.testsupport.safeDeleteTree
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drift v20 (dávka E, dodatek po kritice) in pieces, run in bash without a tmux server: the parse of claude's
 * arguments (E2, E2b) against v19's over real processes, the folder function restore.sh and drift share (E4b), the
 * order in which the installer publishes the three scripts (H10), the tmux time limit with `drift-heal.off` (H6),
 * and the exact tmux targets (E1). End to end over sshd: `DriftV20IntegrationTest`, `DriftV20IdentityIntegrationTest`.
 */
class DriftV20Test {
    private val root = Files.createTempDirectory("drift-v20").toFile()
    private val started = mutableListOf<Process>()

    @AfterTest fun tearDown() {
        started.forEach { it.destroyForcibly().waitFor(5, TimeUnit.SECONDS) }
        safeDeleteTree(root)
    }

    private val v20 get() = SessionPersistenceService.INSTALL_RESTORE_COMMAND
    private val v19 get() = DriftPrefixesTest.golden19()

    private fun heredoc(script: String, eof: String): String {
        val start = script.indexOf('\n', script.indexOf("<<'$eof'")) + 1
        return script.substring(start, script.indexOf("\n$eof\n", start) + 1)
    }

    private fun linesBetween(text: String, from: String, to: String): List<String> {
        val lines = text.lines()
        val a = lines.indexOf(from)
        assertTrue(a >= 0, "not found: $from")
        val b = (a until lines.size).firstOrNull { lines[it] == to } ?: error("not found after $from: $to")
        return lines.subList(a, b + 1)
    }

    private class Ran(val exit: Int, val out: String, val err: String)

    private fun run(argv: List<String>, home: File, path: String? = null, stdin: ByteArray? = null): Ran {
        val errFile = File(root, "err-${System.nanoTime()}")
        val pb = ProcessBuilder(argv).redirectError(errFile).directory(home)
        pb.environment()["HOME"] = home.absolutePath
        if (path != null) pb.environment()["PATH"] = path
        val p = pb.start()
        p.outputStream.use { if (stdin != null) it.write(stdin) }
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "hung: $argv")
        return Ran(p.exitValue(), out, errFile.readText())
    }

    private fun freshHome(): File = File(root, "home-${System.nanoTime()}").also { File(it, ".claude-remote").mkdirs() }

    // ---- E2 and E2b: claude's arguments ---------------------------------------------------------------------------

    /** A process named `claude` (a script of that name) with [args] in its command line and [env] in its environ. */
    private fun claude(args: List<String>, env: Map<String, String> = emptyMap()): Long {
        val bin = File(root, "cbin").apply { mkdirs() }
        val script = File(bin, "claude").apply { if (!isFile) { writeText("#!/bin/sh\nsleep 60\n"); setExecutable(true) } }
        val pb = ProcessBuilder(listOf(script.absolutePath) + args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.environment().remove("CLAUDE_CONFIG_DIR")
        pb.environment().putAll(env)
        val p = pb.start().also { started += it }
        val pid = p.pid()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && runCatching { File("/proc/$pid/cmdline").readText() }.getOrDefault("").split('\u0000').size < args.size + 2) Thread.sleep(10)
        return pid
    }

    /**
     * drift's loop over the live sessions of [version] (19 = the golden of 1bdcbd5, 20 = this tree) for one session
     * [name] whose pane runs the process [pid], with tmux and the process tree stubbed: the row it writes to the live
     * table and the B1 tokens (`c:`/`a:`) it adds to OWN_META.
     */
    private fun parse(version: Int, name: String, pid: Long, home: File = freshHome()): Pair<String, List<String>> {
        val drift = heredoc(if (version == 19) v19 else v20, "DRIFT_EOF")
        val loop = if (version == 19) linesBetween(drift, "for s in \$(tmux -u list-sessions -F '#{session_name}' 2>/dev/null); do", "done")
            else linesBetween(drift, "while IFS= read -r s; do", "done < <(tmux -u list-sessions -F '#{session_name}' 2>/dev/null)")
        val script = buildString {
            append("set -u\n")
            append("tmux() { case \"\$*\" in *list-sessions*) printf '%s\\n' \"\$CR_NAME\";; *list-panes*) echo 4242;; *display-message*) echo /w/x;; esac; }\n")
            append("find_claude_descendant() { echo \"\$CR_PID\"; }\n")
            append("is_ours() { return 0; }\napp_has() { return 1; }\n")
            append("LIVE_TBL=\"\$HOME/live.tbl\"; : > \"\$LIVE_TBL\"; OWN_META=\"\"\n")
            append(loop.joinToString("\n")).append("\n")
            append("cat \"\$LIVE_TBL\" 2>/dev/null; printf '%s' \"\$OWN_META\" | tr ' \\n' '\\n\\n' | sed -n 's/^\\([ac]:\\)/META \\1/p'\n")
        }
        val pb = ProcessBuilder("bash", "-c", script).directory(home).redirectErrorStream(true)
        pb.environment().apply { put("HOME", home.absolutePath); put("CR_NAME", name); put("CR_PID", pid.toString()); remove("LANG"); remove("LC_ALL"); remove("LC_CTYPE") }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue(), out)
        val lines = out.lines().filter { it.isNotEmpty() }
        return lines.filterNot { it.startsWith("META ") }.joinToString("\n") to lines.filter { it.startsWith("META ") }.map { it.removePrefix("META ") }
    }

    private val u = "aaaaaaaa-1111-2222-3333-444444444444"
    private fun app(model: ClaudeModel, mode: ClaudeMode, vararg tail: String) = ClaudeConfig.modelArgs(model) + ClaudeConfig.modeArgs(mode) + tail.toList()
    private fun prompt(n: Int) = buildString { while (length < n) append("please look at the build and fix what fails ") }.take(n)

    /** The shapes of claude's command line the live box runs (kritika E 3.1), built by the app's and restore.sh's builders. */
    private val liveShapes: List<Pair<List<String>, Map<String, String>>> by lazy {
        val acct = mapOf("CLAUDE_CONFIG_DIR" to "/home/u/.claude-remote/accounts/lukas-x")
        listOf(
            app(ClaudeModel.DEFAULT, ClaudeMode.YOLO, "--resume", u) to emptyMap(),
            app(ClaudeModel.FABLE, ClaudeMode.YOLO, "--resume", u) to emptyMap(),
            app(ClaudeModel.DEFAULT, ClaudeMode.YOLO, "--session-id", u) to emptyMap(),
            listOf("--dangerously-skip-permissions", "--model", "opus", "--session-id", u, prompt(2821)) to emptyMap(),
            app(ClaudeModel.HAIKU, ClaudeMode.YOLO, "--resume", u) to emptyMap(),
            app(ClaudeModel.OPUS, ClaudeMode.NORMAL, "--resume", u) to acct,
            listOf("--append-system-prompt", prompt(2821), "--add-dir", "/x", "--effort", "high", "--setting-sources", "user",
                "--dangerously-skip-permissions", "--resume", u) to emptyMap(),
            emptyList<String>() to emptyMap(),
            app(ClaudeModel.SONNET, ClaudeMode.AUTO, "--session-id", u) to acct,
            app(ClaudeModel.DEFAULT, ClaudeMode.AUTO_ACCEPT, "--resume", u) to emptyMap(),
            app(ClaudeModel.OPUS, ClaudeMode.PLAN) to emptyMap(),
        )
    }

    /** E2b on the live box's shapes: v20 parses each byte for byte as v19 (the row, and the B1 token on a box). */
    @Test fun onTheLiveShapesV20ParsesClaudesArgumentsAsV19() {
        for ((args, env) in liveShapes) {
            val pid = claude(args, env)
            for (name in listOf("claude-server-x", "claude-box-x")) {
                val old = parse(19, name, pid)
                val new = parse(20, name, pid)
                assertEquals(old, new, "$name, claude ${args.joinToString(" ").take(120)}")
            }
            assertTrue(parse(20, "claude-server-x", pid).first.isNotEmpty())
        }
        // and the rows say what they should (a few, spelled out)
        assertEquals("claude-server-x\t/w/x\tYOLO\tFABLE\t\t$u\t", parse(20, "claude-server-x", claude(app(ClaudeModel.FABLE, ClaudeMode.YOLO, "--resume", u))).first)
        assertEquals("claude-server-x\t/w/x\tAUTO\tSONNET\t\t$u\tlukas-x", parse(20, "claude-server-x", claude(liveShapes[8].first, liveShapes[8].second)).first)
        assertEquals("claude-server-x\t/w/x\tYOLO\tDEFAULT\t\t\t", parse(20, "claude-server-x", claude(emptyList())).first)
    }

    /**
     * E2 (B1) and E2b where v20 differs on purpose: the flag's text in a prompt or after `--` is no signature; model,
     * mode and session id are the first argument after their option, not text of a prompt; a value after `--resume`
     * that is no id (a search term) gives no id. Each case: what v19 read and what v20 reads.
     */
    @Test fun claudesArgumentsAreWholeArgumentsBeforeDashDash() {
        data class Case(val args: List<String>, val name: String, val v19: Pair<String, List<String>>, val v20: Pair<String, List<String>>)
        val box = "claude-box-x"
        val srv = "claude-server-x"
        fun row(mode: String, model: String, sid: String, n: String = srv) = "$n\t/w/x\t$mode\t$model\t\t$sid\t"
        val cases = listOf(
            Case(listOf("-p", "please use --dangerously-skip-permissions here"), box, row("YOLO", "DEFAULT", "", box) to listOf("c:$box"), "" to emptyList()),
            Case(listOf("-p", "--", "--dangerously-skip-permissions"), box, row("YOLO", "DEFAULT", "", box) to listOf("c:$box"), "" to emptyList()),
            Case(listOf("--add-dir", "/a --allow-dangerously-skip-permissions b"), box, row("DEFAULT", "DEFAULT", "", box) to listOf("c:$box"), "" to emptyList()),
            Case(listOf("-p", "--dangerously-skip-permissions-and-more", "--allow-dangerously-skip-permissionsX"), box, "" to emptyList(), "" to emptyList()),
            Case(listOf("--resume", u, "--dangerously-skip-permissions"), box, row("YOLO", "DEFAULT", u, box) to listOf("c:$box"), row("YOLO", "DEFAULT", u, box) to listOf("c:$box")),
            Case(listOf("--dangerously-skip-permissions", "--session-id", u, "please run --session-id deadbeef now"), srv,
                row("YOLO", "DEFAULT", "deadbeef") to emptyList(), row("YOLO", "DEFAULT", u) to emptyList()),
            Case(listOf("--dangerously-skip-permissions", "--resume", u, "fix the --resume flag"), srv,
                row("YOLO", "DEFAULT", "f") to emptyList(), row("YOLO", "DEFAULT", u) to emptyList()),
            Case(listOf("--model", "haiku", "--dangerously-skip-permissions", "--session-id", u, "use --model opus and --permission-mode auto"), srv,
                row("AUTO", "OPUS", u) to emptyList(), row("YOLO", "HAIKU", u) to emptyList()),
            Case(listOf("--model", "opusplan", "--allow-dangerously-skip-permissions"), srv, row("DEFAULT", "OPUS", "") to emptyList(), row("DEFAULT", "DEFAULT", "") to emptyList()),
            Case(listOf("--resume", "fix", "--dangerously-skip-permissions"), srv, row("YOLO", "DEFAULT", "f") to emptyList(), row("YOLO", "DEFAULT", "") to emptyList()),
            Case(listOf("--model", "sonnet", "--model", "opus", "--allow-dangerously-skip-permissions"), srv, row("DEFAULT", "OPUS", "") to emptyList(), row("DEFAULT", "SONNET", "") to emptyList()),
            Case(listOf("--dangerously-skip-permissions", "--resume", u, "--resume", "deadbeef"), srv, row("YOLO", "DEFAULT", "deadbeef") to emptyList(), row("YOLO", "DEFAULT", u) to emptyList()),
        )
        for (c in cases) {
            val home = freshHome()
            val pid = claude(c.args)
            // a per-pid state file whose id has no transcript: it does not override (kritika KK9)
            File(home, ".claude/sessions").mkdirs()
            File(home, ".claude/sessions/$pid.json").writeText("""{"sessionId":"bbbbbbbb-0000-0000-0000-000000000000"}""")
            assertEquals(c.v19, parse(19, c.name, pid, home), "v19: ${c.args}")
            assertEquals(c.v20, parse(20, c.name, pid, home), "v20: ${c.args}")
        }
    }


    // ---- A-ED2: restore.sh's command, word by word ------------------------------------------------------------

    /**
     * restore.sh builds the pane's command from `cr_q` words: for every value the app writes (the live box's shapes,
     * slugs with `@` and `.`) the command is v19's byte for byte (`${ARGS[*]}`, `CLAUDE_CONFIG_DIR="…/<slug>"`); any
     * other value is one word that reads back as itself, and nothing in it runs.
     */
    @Test fun restoreBuildsV19sCommandForTheAppsValuesAndKeepsAnyOtherAsAValue() {
        val words = linesBetween(heredoc(v20, "RESTORE_EOF"), "# ---- one shell word (v20, A-ED2) ----", "# ---- end of one shell word ----").joinToString("\n")
        val home = freshHome()
        fun sh(script: String, vararg args: String) = run(listOf("bash", "-c", "$words\n$script", "x") + args, home)
        for ((args, _) in liveShapes) {
            val argv = listOf("claude") + args
            val r = sh("CMD=\"\"; for a in \"\$@\"; do CMD=\"\$CMD\${CMD:+ }\$(cr_q \"\$a\")\"; done; printf '%s' \"\$CMD\"", *argv.toTypedArray())
            val v19 = argv.joinToString(" ")
            if (argv.all { a -> a.isNotEmpty() && a.all { it.isLetterOrDigit() && it.code < 128 || it in "@%+=:,./_-" } }) assertEquals(v19, r.out, "v19's command")
            else assertEquals(argv, sh("eval \"set -- \$1\"; printf '%s\\n' \"\$@\"", r.out).out.trimEnd('\n').split('\n'), "the words read back")
        }
        for (slug in listOf("lukas@kontexta.cz", "lukas-kontexta-cz", "acct1", "a.b_c-d")) {
            assertEquals("CLAUDE_CONFIG_DIR=\"${home.absolutePath}/.claude-remote/accounts/$slug\"", sh("cr_acct \"\$1\"", slug).out, slug)
        }
        val odd = listOf("a\" ; echo PWNED >> \$HOME/pwned; : \"", "x; echo PWNED >> \$HOME/pwned", "\$(touch \$HOME/pwned)", "`touch \$HOME/pwned`",
            "it's", "a b", "", "~", "--", "ž")
        for (v in odd) {
            val word = sh("cr_q \"\$1\"", v).out
            assertEquals(v, sh("eval \"set -- \$1\"; printf '%s' \"\$1\"", word).out, "cr_q '$v' reads back")
            val acct = sh("cr_acct \"\$1\"", v).out
            assertEquals("${home.absolutePath}/.claude-remote/accounts/$v", sh("eval \"env \$1 printenv CLAUDE_CONFIG_DIR\"", acct).out.trimEnd('\n'), "cr_acct '$v'")
        }
        assertFalse(File(home, "pwned").exists(), "a value ran as code")
    }


    /**
     * T-ED13: restore.sh, with jq and without (the line parser over sessions.json), never puts a slug that is a path
     * (`../../x`, `a/b`, `..`) into CLAUDE_CONFIG_DIR: the entry starts without an account and restore.log says so; a
     * slug of the app (`someone@example.cz`) keeps v19's `CLAUDE_CONFIG_DIR="…"`. Run with a tmux that records what it
     * is told (no server at all) and a PATH of wrappers, so the branch without jq really has no jq.
     */
    @Test fun restoreNeverTakesASlugThatIsAPathInEitherBranch() {
        for (withJq in listOf(true, false)) {
            val home = freshHome()
            val cr = File(home, ".claude-remote")
            File(cr, "drift-heal.off").writeText("")
            val bin = File(home, "bin").apply { mkdirs() }
            File(bin, "tmux").apply {
                writeText("#!/bin/sh\ncase \"\$1\" in new-session) for a; do last=\$a; done; printf '%s\\n' \"\$last\" >> \"\$HOME/tmux.log\";; has-session) exit 1;; esac\nexit 0\n")
                setExecutable(true)
            }
            File(bin, "claude").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
            val tools = listOf("mkdir", "date", "id", "realpath", "cat", "sed", "ls", "head", "find", "grep", "seq", "sleep", "flock", "tr",
                "stat", "awk", "cut", "env", "sort", "wc", "touch", "rm", "mv", "cp", "chmod", "dirname", "basename", "timeout") + (if (withJq) listOf("jq") else emptyList())
            for (t in tools) {
                val real = listOf("/usr/bin/$t", "/bin/$t").firstOrNull { File(it).canExecute() } ?: continue
                File(bin, t).apply { writeText("#!/bin/sh\nexec $real \"\$@\"\n"); setExecutable(true) }
            }
            val names = listOf("claude-server-up" to "../../x", "claude-server-sl" to "a/b", "claude-server-dd" to "..", "claude-server-ok" to "someone@example.cz")
            val text = "[\n" + names.joinToString(",\n") { (n, a) ->
                File(home, "w/$n").mkdirs()
                "  {\n    \"tmuxSessionName\": \"$n\",\n    \"folder\": \"${File(home, "w/$n").absolutePath}\",\n    \"mode\": \"YOLO\",\n    \"model\": \"DEFAULT\",\n    \"accountSlug\": \"$a\"\n  }"
            } + "\n]\n"
            File(cr, "sessions.json").writeText(text)
            File(cr, "sessions.restore.json").writeText(text)
            File(cr, "restore.sh").writeText(heredoc(v20, "RESTORE_EOF"))
            val pb = ProcessBuilder("/bin/bash", File(cr, "restore.sh").absolutePath).directory(home).redirectErrorStream(true)
            pb.environment().apply { clear(); put("HOME", home.absolutePath); put("PATH", bin.absolutePath) }
            val p = pb.start()
            p.inputStream.readAllBytes()
            assertTrue(p.waitFor(60, TimeUnit.SECONDS))
            val what = if (withJq) "jq" else "without jq"
            val log = File(cr, "restore.log").readText()
            assertEquals(!withJq, "jq not installed" in log, "$what: the branch\n$log")
            val started = File(home, "tmux.log").readText().lines().filter { it.isNotEmpty() }
            assertEquals(4, started.size, "$what: $started\n$log")
            for ((n, a) in names.take(3)) assertTrue("account $a of $n is a path — starting it without an account" in log, "$what: $n not logged\n$log")
            assertTrue(started.any { "CLAUDE_CONFIG_DIR=\"${cr.absolutePath}/accounts/someone@example.cz\" claude" in it }, "$what: the app's slug: $started")
            assertEquals(1, started.count { "CLAUDE_CONFIG_DIR" in it }, "$what: a path became CLAUDE_CONFIG_DIR: $started")
        }
    }


    /**
     * T-ED19: restore.sh alone (a boot, no drift before it) skips a name of the manifest with a control character —
     * tmux would escape it and start a session of another name — and starts the healthy entry beside it.
     */
    @Test fun restoreAloneSkipsANameWithAControlCharacter() {
        val home = freshHome()
        val cr = File(home, ".claude-remote")
        File(cr, "drift-heal.off").writeText("")
        val bin = File(home, "bin").apply { mkdirs() }
        File(bin, "tmux").apply {
            writeText("#!/bin/sh\ncase \"\$1\" in new-session) n=; while [ \$# -gt 0 ]; do [ \"\$1\" = -s ] && n=\$2; shift; done; printf '%s\\n' \"\$n\" >> \"\$HOME/tmux.log\";; has-session) exit 1;; esac\nexit 0\n")
            setExecutable(true)
        }
        File(bin, "claude").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        for (t in listOf("mkdir", "date", "id", "realpath", "cat", "sed", "ls", "head", "find", "grep", "seq", "sleep", "flock", "tr", "jq")) {
            val real = listOf("/usr/bin/$t", "/bin/$t").firstOrNull { File(it).canExecute() } ?: continue
            File(bin, t).apply { writeText("#!/bin/sh\nexec $real \"\$@\"\n"); setExecutable(true) }
        }
        File(home, "w/c").mkdirs(); File(home, "w/ok").mkdirs()
        File(cr, "sessions.restore.json").writeText("""[{"tmuxSessionName":"claude-server-c\u0001x","folder":"${File(home, "w/c").absolutePath}","mode":"YOLO","model":"DEFAULT"},""" +
            """{"tmuxSessionName":"claude-server-ok","folder":"${File(home, "w/ok").absolutePath}","mode":"YOLO","model":"DEFAULT"}]""")
        File(cr, "restore.sh").writeText(heredoc(v20, "RESTORE_EOF"))
        val pb = ProcessBuilder("/bin/bash", File(cr, "restore.sh").absolutePath).directory(home).redirectErrorStream(true)
        pb.environment().apply { clear(); put("HOME", home.absolutePath); put("PATH", bin.absolutePath) }
        val p = pb.start()
        p.inputStream.readAllBytes()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        val log = File(cr, "restore.log").readText()
        assertEquals(listOf("claude-server-ok"), File(home, "tmux.log").readText().lines().filter { it.isNotEmpty() }, log)
        assertTrue("tmux cannot hold this name as written" in log, log)
    }

    // ---- E4b: the folder restore.sh and drift share -------------------------------------------------------------

    /** `cr_folder`, pasted into both scripts: `~` and a relative path under HOME, `""` and null no folder. */
    @Test fun theFolderOfAnEntryIsReadOneWayInBothScripts() {
        fun func(script: String) = linesBetween(heredoc(v20, script), "# ---- the folder of a manifest entry (v20, E4b) ----", "# ---- end of the folder of a manifest entry ----")
        assertEquals(func("RESTORE_EOF"), func("DRIFT_EOF"), "the same function in restore.sh and drift.sh")
        val home = freshHome()
        val h = home.absolutePath
        val cases = listOf("~/x" to "$h/x", "~" to h, "x/y" to "$h/x/y", "/abs/p" to "/abs/p", "a b" to "$h/a b", "" to null, "null" to null)
        // v19's expansion in restore.sh, for the same values (where it had one)
        val old = "f=\"\${1/#\\~/\$HOME}\"; case \"\$f\" in /*) ;; *) f=\"\$HOME/\$f\";; esac; printf '%s' \"\$f\""
        for ((value, want) in cases) {
            val r = run(listOf("bash", "-c", func("RESTORE_EOF").joinToString("\n") + "\ncr_folder \"\$1\"", "x", value), home)
            assertEquals(want ?: "", r.out, "cr_folder '$value'")
            assertEquals(if (want == null) 1 else 0, r.exit, "cr_folder '$value' rc")
            if (want != null) assertEquals(want, run(listOf("bash", "-c", old, "x", value), home).out, "v19 read '$value' the same")
        }
        assertEquals("$h/", run(listOf("bash", "-c", old, "x", ""), home).out, "v19 started an entry without a folder in HOME")
    }

    // ---- H10: the installer publishes the three scripts together ------------------------------------------------

    private fun shims(home: File, failChmodOf: String? = null): String {
        val dir = File(home, "shims-${System.nanoTime()}").apply { mkdirs() }
        for (tool in listOf("systemctl", "loginctl")) File(dir, tool).apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        File(dir, "tmux").apply { writeText("#!/bin/sh\nexit 1\n"); setExecutable(true) }
        if (failChmodOf != null) File(dir, "chmod").apply {
            writeText("#!/bin/sh\ncase \"\$*\" in *$failChmodOf*) exit 1;; esac\nexec /bin/chmod \"\$@\"\n"); setExecutable(true)
        }
        return dir.absolutePath + File.pathSeparator + System.getenv("PATH")
    }

    private fun installer(home: File, stdin: ByteArray, path: String) = run(listOf("bash", "-c", SessionPersistenceService.INSTALL_RESTORE_EXEC), home, path, stdin)

    /**
     * An install cut short after the first two scripts were written (here: the chmod of tmux-sockheal.sh's temp file
     * fails, `set -e`) replaces none of the three: all three temp files are written first, then renamed back to back.
     * v19 had renamed restore.sh and drift.sh already, leaving the box on two versions until the next launch.
     */
    @Test fun anInstallCutShortReplacesNoScript() {
        val home = freshHome()
        val v18stdin = (DriftPrefixesTest.golden() + "\n" + SessionPersistenceService.INSTALL_RESTORE_END + "\n").toByteArray()
        installer(home, v18stdin, shims(home)).let { assertEquals(0, it.exit, "v18 install: ${it.out}\n${it.err}") }
        val names = listOf("restore.sh", "drift.sh", "tmux-sockheal.sh")
        val before = names.map { File(home, ".claude-remote/$it").readText() }
        val cut = installer(home, SessionPersistenceService.installRestoreStdin(TmuxNameParser.DEFAULT_PREFIX), shims(home, failChmodOf = "tmux-sockheal.sh.tmp."))
        assertTrue(cut.exit != 0, "the install went on: ${cut.out}")
        assertEquals(before, names.map { File(home, ".claude-remote/$it").readText() }, "a script was replaced by an install cut short:\n${cut.out}")
        assertTrue(cut.out.lines().none { it.endsWith("_INSTALLED") }, cut.out)
        assertTrue(File(home, ".claude-remote").list()!!.none { ".tmp." in it }, "temp files left: ${File(home, ".claude-remote").list()!!.toList()}")
        val whole = installer(home, SessionPersistenceService.installRestoreStdin(TmuxNameParser.DEFAULT_PREFIX), shims(home))
        assertEquals(0, whole.exit, whole.out)
        assertEquals(listOf("RESTORE_SCRIPT_INSTALLED", "DRIFT_SCRIPT_INSTALLED", "SOCKHEAL_SCRIPT_INSTALLED"),
            whole.out.lines().filter { it.endsWith("_INSTALLED") && "SCRIPT" in it }, whole.out)
        assertEquals(listOf("RESTORE_EOF", "DRIFT_EOF", "SOCKHEAL_EOF").map { heredoc(v20, it) }, names.map { File(home, ".claude-remote/$it").readText() })
        val again = installer(home, SessionPersistenceService.installRestoreStdin(TmuxNameParser.DEFAULT_PREFIX), shims(home))
        assertTrue("RESTORE_SCRIPT_PRESENT" in again.out.lines() && again.out.lines().none { it.endsWith("_SCRIPT_INSTALLED") }, again.out)
    }


    /**
     * A-ED5: an install killed outright (no trap) left its temp files; the next install removes ours older than an hour
     * and nothing else (a fresh one may be another install's, running now; another name is not ours).
     */
    @Test fun anInstallRemovesItsOwnOldTempFilesOnly() {
        val home = freshHome()
        val cr = File(home, ".claude-remote")
        val old = listOf("restore.sh.tmp.123", "drift.sh.tmp.456", "tmux-sockheal.sh.tmp.789", "restore.sh.v18.tmp.11", "drift.sh.v19.tmp.12")
        val keep = listOf("drift.sh.tmp.999", "sessions.json.tmp.5", "drift.sh.tmp.x", "notes.tmp.1")
        for (n in old + keep) File(cr, n).writeText("x")
        val hourAgo = System.currentTimeMillis() - 2 * 3_600_000L
        (old + keep.drop(1)).forEach { File(cr, it).setLastModified(hourAgo) }
        val r = installer(home, SessionPersistenceService.installRestoreStdin(TmuxNameParser.DEFAULT_PREFIX), shims(home))
        assertEquals(0, r.exit, r.out)
        for (n in old) assertFalse(File(cr, n).exists(), "$n left")
        for (n in keep) assertTrue(File(cr, n).exists(), "$n removed")
    }

    // ---- H6: every tmux call of a tick has a time limit, drift-heal.off or not ------------------------------------

    /**
     * With `drift-heal.off` (the socket guard off) a tick's tmux calls still go through `timeout` (v19 defined the
     * wrapper only with the guard on, so a hung server wedged the timer unit). Run with a stub tmux that answers
     * nothing and a `timeout` that records its calls; no tmux server exists.
     */
    @Test fun tmuxHasATimeLimitWithDriftHealOff() {
        val home = freshHome()
        File(home, ".claude-remote/drift-heal.off").writeText("")
        val dir = File(home, "stubs").apply { mkdirs() }
        File(dir, "tmux").apply { writeText("#!/bin/sh\nprintf 'tmux %s\\n' \"\$*\" >> \"\$HOME/calls\"\nexit 1\n"); setExecutable(true) }
        File(dir, "timeout").apply {
            writeText("#!/bin/sh\nprintf 'timeout %s\\n' \"\$*\" >> \"\$HOME/calls\"\nwhile [ \$# -gt 0 ]; do case \"\$1\" in -k) shift 2;; -*) shift;; *) break;; esac; done\nshift\nexec \"\$@\"\n")
            setExecutable(true)
        }
        File(dir, "systemctl").apply { writeText("#!/bin/sh\necho running\n"); setExecutable(true) }
        File(home, ".claude-remote/drift.sh").writeText(heredoc(v20, "DRIFT_EOF"))
        val r = run(listOf("bash", File(home, ".claude-remote/drift.sh").absolutePath), home, dir.absolutePath + File.pathSeparator + System.getenv("PATH"))
        assertEquals(0, r.exit, r.err)
        val calls = File(home, "calls").readText().lines().filter { it.isNotEmpty() }
        val viaTimeout = calls.count { it.startsWith("timeout -k 2 10 tmux ") }
        val tmuxCalls = calls.count { it.startsWith("tmux ") }
        assertTrue(tmuxCalls > 0, calls.toString())
        assertEquals(tmuxCalls, viaTimeout, "a tmux call without the time limit:\n${calls.joinToString("\n")}")
    }

    // ---- E1: exact tmux targets ---------------------------------------------------------------------------------

    /**
     * E1 (kritika KK1, KK4): every tmux target in drift.sh is exact: a session `=name` (`-t "=$s"`, `-t="$n"`,
     * `=__anchor__`), a pane `=name:` (`-t "=$s:"`, display-message with `=name` alone prints nothing). A bare name
     * matches by prefix or pattern. And no `for … in $(…)` over names is left.
     */
    @Test fun everyTmuxTargetOfDriftIsExact() {
        val drift = heredoc(v20, "DRIFT_EOF")
        val targets = Regex("""-t(=| )("[^"]*"|\S+)""").findAll(drift.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n"))
            .map { it.value }.toSortedSet()
        assertEquals(sortedSetOf("-t \"=\$s:\"", "-t =__anchor__", "-t=\"\$fn\"", "-t=\"\$n\""), targets)
        assertFalse(Regex("""for \w+ in \$\(tmux""").containsMatchIn(drift), "a for loop over tmux names")
    }
}
