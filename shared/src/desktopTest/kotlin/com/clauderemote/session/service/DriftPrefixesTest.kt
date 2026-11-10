package com.clauderemote.session.service

import com.clauderemote.model.TmuxNameParser
import com.clauderemote.testsupport.safeDeleteTree
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D6-S (dodatek 3, kritika D2 N14–N16, N20): `~/.claude-remote/prefixes` and marker v19, in the generator and in shell.
 * The drift runs end to end over sshd in `DriftPrefixesIntegrationTest` (hradla B, C, D).
 */
class DriftPrefixesTest {
    private val root = Files.createTempDirectory("drift-prefixes").toFile()

    @AfterTest fun tearDown() { safeDeleteTree(root) }

    // ---- the golden v18 ---------------------------------------------------------------------------

    /**
     * `golden/restore-installer-v18.sh` is the installer cedfd70 generated (drift.sh, restore.sh and tmux-sockheal.sh
     * in it are byte for byte what the live box runs since 2026-11-08, checked when the golden was made). The extraction
     * below reads the installer out of the Kotlin source the way the compiler builds it; that it gives today's constant
     * from today's source is what makes its reading of cedfd70 trustworthy.
     */
    @Test fun theGoldenV18IsTheInstallerCedfd70Generated() {
        val golden = golden()
        assertEquals(GOLDEN_SHA256, sha256(golden.toByteArray(Charsets.UTF_8)), "the golden changed")
        assertEquals(SessionPersistenceService.INSTALL_RESTORE_COMMAND, installerOf(File(SPS).readText()), "the extraction is not the compiler's")
        val base = gitShow("$BASE:shared/$SPS")
        if (base == null) {
            println("$BASE is not in this clone (a shallow checkout): the golden is checked by its SHA-256 only")
            return
        }
        assertEquals(golden, installerOf(base), "the golden is not what cedfd70 generated")
        assertTrue("MARKER=\"claude-remote-restore-v18\"" in golden)
    }

    /**
     * Hradlo A: what the app sends for the box `server` differs from cedfd70's only in the marker (MARKER, the three
     * compat lines), drift's prefix reader (one block), drift's two comparisons that knew only `claude-server-` (and
     * the header comment that said so), the prefix write before the end line, and (D6S-zaloha) the rollback copies of
     * a v18 restore.sh and drift.sh with their temp files in the installer's trap.
     */
    @Test fun forServerTheInstallerChangedOnlyTheMarkerThePrefixesAndTheTwoComparisons() {
        val old = (golden() + "\n" + SessionPersistenceService.INSTALL_RESTORE_END + "\n").lines()
        val new = String(SessionPersistenceService.installRestoreStdin(TmuxNameParser.DEFAULT_PREFIX), Charsets.UTF_8).lines()
        val (removed, added) = lineDiff(old, new)
        val compat18 = "# marker-compat (do NOT remove): " + (6..18).joinToString(" ") { "claude-remote-restore-v$it" }
        val compat19 = "$compat18 claude-remote-restore-v19"
        val trap18 = "        trap 'rm -f \"\$SCRIPT.tmp.\$\$\" \"\$DRIFT.tmp.\$\$\" \"\$SOCKHEAL.tmp.\$\$\"' EXIT"
        val trap19 = "        trap 'rm -f \"\$SCRIPT.tmp.\$\$\" \"\$DRIFT.tmp.\$\$\" \"\$SOCKHEAL.tmp.\$\$\" \"\$SCRIPT.v18.tmp.\$\$\" \"\$DRIFT.v18.tmp.\$\$\"' EXIT"
        assertEquals(
            listOf(
                "        MARKER=\"claude-remote-restore-v18\"",
                trap18,
                compat18,
                compat18,
                "# the LIVE claude-server-* tmux sessions every minute. Self-healing: re-adds",
                "if [ \"\$(tmux -u list-sessions -F '#{session_name}' 2>/dev/null | grep -c '^claude-server-')\" -gt 0 ]; then",
                "    case \"\$s\" in claude-server-*) ;; *) continue;; esac",
                compat18,
            ),
            removed,
            "removed lines",
        )
        val reader = block(SessionPersistenceService.INSTALL_RESTORE_COMMAND, "# ---- prefixes of this box (D6-S) ----", "# ---- end of prefixes ----")
        val write = SessionPersistenceService.prefixWrite(TmuxNameParser.DEFAULT_PREFIX).lines()
        // the rollback copies of the v18 restore.sh and drift.sh (D6S-zaloha) and their temp files in the trap
        fun rollback(name: String) = block(SessionPersistenceService.INSTALL_RESTORE_COMMAND,
            "            # ---- rollback copy of $name (D6-S) ----", "            # ---- end of the rollback copy of $name ----")
        assertEquals(
            listOf("        MARKER=\"claude-remote-restore-v19\"", trap19,
                compat19) + rollback("restore.sh") + listOf(compat19,
                "# the LIVE tmux sessions of this box (claude-server-* and the prefixes file) every minute. Self-healing: re-adds") +
                reader +
                listOf(
                    "if tmux -u list-sessions -F '#{session_name}' 2>/dev/null | { while IFS= read -r s; do is_ours \"\$s\" && exit 0; done; exit 1; }; then",
                    "    is_ours \"\$s\" || continue",
                ) + rollback("drift.sh") + listOf(compat19) + write,
            added,
            "added lines",
        )
        assertTrue(reader.size in 20..40 && write.size in 20..45, "the reader (${reader.size}) and the write (${write.size}) are small blocks")
        assertEquals(9, rollback("drift.sh").size)
    }

    // ---- the reader (drift.sh) --------------------------------------------------------------------

    /** The N14 fixture: every one of these lines must be skipped. */
    private val brokenLines = listOf(
        "", "   ", "claude-box-\r", "claude-", "claude--", "*", "__anchor__", "[x]", "a b", " claude-sp-", "claude-sp- ",
        "claude-a.b-", "claude-a:b-", "claude-é-", "claude-*-", "claude-[ab]-", "claude-?-", "-claude-x-", "claude--x-",
        "claude-x", "xclaude-y-", "claude-\$HOME-", "claude-x-\tclaude-y-",
    )
    private val names = listOf(
        "claude-server-a", "claude-box-b", "claude-test", "__anchor__", "other", "claude-a.b-1", "claude-sp-1", "claude-x-1",
        "claude--x-1", "claude-y-1", "claude-ok-1", "claude-x-y-z", "claude-tail-1", "claude-a_b-1", "claude-server-lan-1",
    )

    /** Runs drift's reader block in [shell] with a private HOME holding [prefixes] (null = no file); the names it counts. */
    private fun ours(shell: String, prefixes: String?, extra: String = "", names: List<String> = this.names, home: File = freshHome()): Pair<List<String>, String> {
        if (prefixes != null) File(home, ".claude-remote/prefixes").writeText(prefixes)
        val reader = block(SessionPersistenceService.INSTALL_RESTORE_COMMAND, "# ---- prefixes of this box (D6-S) ----", "# ---- end of prefixes ----")
        val script = reader.joinToString("\n") + "\n$extra\nfor n in \"\$@\"; do is_ours \"\$n\" && echo \"\$n\"; done\nexit 0\n"
        val r = run(listOf(shell, "-c", script, shell) + names, home, dir = home)
        assertEquals(0, r.exit, "$shell: ${r.err}")
        return r.out.lines().filter { it.isNotEmpty() } to r.err
    }

    private fun shells(): List<String> = listOf("bash", "dash").filter { sh ->
        val ok = runCatching { ProcessBuilder(sh, "-c", "exit 0").start().waitFor() == 0 }.getOrDefault(false)
        if (!ok) println("$sh not installed: the reader is not checked in it")
        ok
    }

    @Test fun aBrokenPrefixesFileAdoptsNothingButTheDefault() {
        for (sh in shells()) {
            val (got, err) = ours(sh, brokenLines.joinToString("\n") + "\n")
            assertEquals(listOf("claude-server-a", "claude-server-lan-1"), got, "$sh: only claude-server- counts")
            assertEquals("", err, "$sh: silent")
        }
    }

    @Test fun onlyTheValidLinesOfAMixedFileCount() {
        val file = (brokenLines + listOf("claude-box-", "claude-x-y-", "claude-ok-")).joinToString("\n") + "\nclaude-tail-"   // last line without \n
        for (sh in shells()) {
            val (got, err) = ours(sh, file)
            assertEquals(listOf("claude-server-a", "claude-box-b", "claude-ok-1", "claude-x-y-z", "claude-tail-1", "claude-server-lan-1"), got, sh)
            assertEquals("", err, "$sh: silent")
        }
        // CRLF: a whole file written on Windows is refused line by line (drift adopts nothing more; nothing harmful)
        for (sh in shells()) assertEquals(listOf("claude-server-a", "claude-server-lan-1"), ours(sh, "claude-box-\r\nclaude-ok-\r\n").first, sh)
    }

    /** N15: no file (the normal case), a directory, an unreadable file: the default only, and not one byte of output. */
    @Test fun theReaderIsSilentWithAndWithoutTheFile() {
        for (sh in shells()) {
            assertEquals(listOf("claude-server-a", "claude-server-lan-1") to "", ours(sh, null), "$sh: no file")
            val dirHome = freshHome().also { File(it, ".claude-remote/prefixes").mkdirs() }
            assertEquals(listOf("claude-server-a", "claude-server-lan-1") to "", ours(sh, null, home = dirHome), "$sh: a directory")
            if (System.getProperty("user.name") != "root") {
                val home = freshHome()
                File(home, ".claude-remote/prefixes").apply { writeText("claude-box-\n"); setReadable(false, false) }
                assertEquals(listOf("claude-server-a", "claude-server-lan-1") to "", ours(sh, null, home = home), "$sh: unreadable")
            }
        }
    }

    /**
     * The comparison is a fixed string even for a prefix the check would have refused (defence in depth): `.` is no
     * regex dot, `*` and `[x]` no glob. Runs in an empty directory, so the unquoted list does not expand to file names.
     */
    @Test fun theComparisonIsAFixedStringEvenWithoutTheCheck() {
        for (sh in shells()) {
            val (got, _) = ours(sh, null, extra = "PFX='claude-a.b- claude-*- claude-[x]-'",
                names = listOf("claude-a.b-1", "claude-aXb-1", "claude-*-1", "claude-x-1", "claude-[x]-1", "claude-x-2", "claude-server-a"))
            assertEquals(listOf("claude-a.b-1", "claude-*-1", "claude-[x]-1"), got, sh)
        }
    }

    // ---- the writer (the installer) ---------------------------------------------------------------

    private class Ran(val exit: Int, val out: String, val err: String = "")

    private fun freshHome(): File = File(root, "home-${System.nanoTime()}").also { File(it, ".claude-remote").mkdirs() }

    private fun run(argv: List<String>, home: File, stdin: ByteArray? = null, dir: File? = null): Ran {
        val shims = File(root, "shims").apply { mkdirs() }
        for (tool in listOf("systemctl", "loginctl", "tmux")) {
            File(shims, tool).takeUnless { it.exists() }?.apply { writeText("#!/usr/bin/env bash\nexit 1\n") }?.setExecutable(true)
        }
        val errFile = File(root, "err-${System.nanoTime()}")
        val pb = ProcessBuilder(argv).redirectError(errFile)
        if (dir != null) pb.directory(dir)
        pb.environment()["HOME"] = home.absolutePath
        pb.environment()["PATH"] = shims.absolutePath + File.pathSeparator + System.getenv("PATH")
        val p = pb.start()
        p.outputStream.use { if (stdin != null) it.write(stdin) }
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "hung: $argv")
        return Ran(p.exitValue(), out, errFile.readText())
    }

    /** The real launcher with the real stdin, in a private HOME; systemctl, loginctl and tmux are shims that refuse. */
    private fun install(home: File, prefix: String): Ran =
        run(listOf("bash", "-c", SessionPersistenceService.INSTALL_RESTORE_EXEC), home, SessionPersistenceService.installRestoreStdin(prefix))

    private fun prefixesOf(home: File) = File(home, ".claude-remote/prefixes")

    @Test fun theInstallerWritesTheBoxsPrefixOnceAndNoFileForServer() {
        val home = freshHome()
        val first = install(home, TmuxNameParser.prefixFor("box"))
        assertEquals(0, first.exit, first.out)
        assertTrue("PREFIX_ADDED claude-box-" in first.out.lines(), first.out)
        assertEquals("claude-box-\n", prefixesOf(home).readText())
        val second = install(home, TmuxNameParser.prefixFor("box"))
        assertEquals(0, second.exit, second.out)
        assertTrue(second.out.lines().none { it.startsWith("PREFIX_") }, "already there: nothing to say\n${second.out}")
        assertEquals("claude-box-\n", prefixesOf(home).readText())
        assertEquals(0, install(home, TmuxNameParser.prefixFor("lan.2")).exit)
        assertEquals("claude-box-\nclaude-lan_2-\n", prefixesOf(home).readText(), "a second box name adds its line")
        assertTrue(File(home, ".claude-remote").list()!!.none { ".tmp." in it }, "temp files left")

        val server = freshHome()
        val s = install(server, TmuxNameParser.DEFAULT_PREFIX)
        assertEquals(0, s.exit, s.out)
        assertTrue(s.out.lines().none { it.startsWith("PREFIX_") }, s.out)
        assertFalse(prefixesOf(server).exists(), "the box `server` keeps no file (drift always has the default)")
        assertEquals(0, install(server, TmuxNameParser.prefixFor("server")).exit)
        assertFalse(prefixesOf(server).exists())
    }

    /** The writer keeps the valid lines of what it finds and drops the rest (the same check as the reader). */
    @Test fun addingThePrefixRewritesABrokenFileToItsValidLines() {
        val home = freshHome()
        prefixesOf(home).writeText((brokenLines + "claude-ok-").joinToString("\n") + "\nclaude-tail-")
        val r = install(home, TmuxNameParser.prefixFor("box"))
        assertEquals(0, r.exit, r.out)
        assertEquals("claude-ok-\nclaude-tail-\nclaude-box-\n", prefixesOf(home).readText())
    }

    /** N20: a name drift cannot match is logged and costs the install nothing; a quote in it stays inside its string. */
    @Test fun anUnsupportedNameIsLoggedAndTheInstallStillSucceeds() {
        for (prefix in listOf(TmuxNameParser.prefixFor("my box"), TmuxNameParser.prefixFor("ж"), TmuxNameParser.prefixFor("it's"),
            "claude-x-'; touch \"\$HOME/pwned\"; '-", "claude-")) {
            val home = freshHome()
            val r = install(home, prefix)
            assertEquals(0, r.exit, "$prefix: ${r.out}")
            assertTrue("PREFIX_UNSUPPORTED" in r.out.lines(), "$prefix: ${r.out}")
            assertFalse(prefixesOf(home).exists(), prefix)
            assertFalse(File(home, "pwned").exists(), "$prefix: the name ran as code")
            assertTrue(File(home, ".claude-remote/install.log").readText().contains("session prefix $prefix is not claude-<letters"), prefix)
        }
    }

    /** N20: the install runs under `set -e`; a prefix it cannot write (lock held, a directory in the way) never fails it. */
    @Test fun aPrefixThatCannotBeWrittenNeverFailsTheInstall() {
        val home = freshHome()
        val lock = File(home, ".claude-remote/sessions.lock").apply { createNewFile() }
        val ready = File(root, "held-${System.nanoTime()}")
        // one process holds the lock (exec: the sleep itself owns fd 9, so destroying it releases the lock)
        val holderErr = File(root, "holder-err-${System.nanoTime()}")
        val holder = ProcessBuilder("bash", "-c", "exec 9>>\"\$1\" && flock -n 9 && : > \"\$2\" && exec sleep 60", "holder", lock.absolutePath, ready.absolutePath)
            .redirectError(holderErr).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val deadline = System.currentTimeMillis() + 10_000
            while (!ready.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue(ready.exists(), "the lock holder did not start: ${holderErr.readText()}")
            val t0 = System.nanoTime()
            val r = install(home, TmuxNameParser.prefixFor("box"))
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(0, r.exit, r.out)
            assertTrue("PREFIX_NOT_WRITTEN" in r.out.lines(), r.out)
            assertTrue(ms in 4_500..30_000, "waited $ms ms for the lock (flock -w 5)")
            assertFalse(prefixesOf(home).exists())
        } finally {
            holder.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        }
        val dirHome = freshHome().also { prefixesOf(it).mkdirs() }
        val r = install(dirHome, TmuxNameParser.prefixFor("box"))
        assertEquals(0, r.exit, r.out)
        assertTrue("PREFIX_NOT_WRITTEN" in r.out.lines(), r.out)
        assertTrue(prefixesOf(dirHome).list()!!.isEmpty(), "nothing moved into the directory: ${prefixesOf(dirHome).list()!!.toList()}")
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** Lines of [script] from the line [from] to the line [to], both included. */
    private fun block(script: String, from: String, to: String): List<String> {
        val lines = script.lines()
        val a = lines.indexOf(from)
        val b = lines.indexOf(to)
        assertTrue(a >= 0 && b > a, "block $from … $to not found")
        return lines.subList(a, b + 1)
    }

    /** Lines only in [old] and lines only in [new], each in order (longest common subsequence). */
    private fun lineDiff(old: List<String>, new: List<String>): Pair<List<String>, List<String>> {
        val n = old.size
        val m = new.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (old[i] == new[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val removed = mutableListOf<String>()
        val added = mutableListOf<String>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                old[i] == new[j] -> { i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> removed += old[i++]
                else -> added += new[j++]
            }
        }
        while (i < n) removed += old[i++]
        while (j < m) added += new[j++]
        return removed to added
    }

    private fun gitShow(spec: String): String? = runCatching {
        val p = ProcessBuilder("git", "show", spec).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        if (p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) out else null
    }.getOrNull()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    companion object {
        const val GOLDEN_SHA256 = "faf2c0669e581d5fa098e4304319383689ead1c9b95495629111e318df31260d"
        const val BASE = "cedfd70e57906ca8d49858a73b96b25a4c9c3885"
        const val SPS = "src/commonMain/kotlin/com/clauderemote/session/service/SessionPersistenceService.kt"

        fun golden(): String {
            val rel = "src/desktopTest/resources/golden/restore-installer-v18.sh"
            return (listOf(File(rel), File("shared/$rel")).firstOrNull { it.isFile } ?: File(rel)).readText()
        }

        /**
         * [SessionPersistenceService.INSTALL_RESTORE_COMMAND] as the compiler builds it from a source text of
         * `SessionPersistenceService.kt`: the raw string with Kotlin's dollar escape read as `$`, the guard (and, when the
         * source has it, the prefix check) interpolated, then `trimIndent()`.
         */
        fun installerOf(source: String): String {
            fun raw(start: String, end: String): String {
                val a = source.indexOf(start)
                require(a >= 0) { "not in the source: $start" }
                val b = source.indexOf(end, a + start.length)
                return source.substring(a + start.length, b)
            }
            val dollar = "\${'\$'}"
            fun kt(raw: String, env: Map<String, String>): String {
                val sb = StringBuilder()
                var i = 0
                while (i < raw.length) {
                    if (raw.startsWith(dollar, i)) { sb.append('$'); i += dollar.length; continue }
                    val m = Regex("""\$([A-Za-z_][A-Za-z0-9_]*)""").matchAt(raw, i)
                    if (m != null) { sb.append(env[m.groupValues[1]] ?: error("unknown template ${m.value}")); i += m.value.length; continue }
                    if (raw.startsWith("\${", i)) {
                        val end = raw.indexOf('}', i)
                        val key = raw.substring(i + 2, end)
                        sb.append(env[key] ?: error("unknown template \${$key}")); i = end + 1; continue
                    }
                    sb.append(raw[i]); i++
                }
                return sb.toString()
            }
            val q = "\"\"\""
            val env = mutableMapOf(
                "SOCK_GUARD_FUNCS" to kt(raw("private val SOCK_GUARD_FUNCS = $q", q), emptyMap()),
                "TmuxNameParser.DEFAULT_PREFIX" to TmuxNameParser.DEFAULT_PREFIX,
            )
            if ("private val PREFIX_CHECK_FUNC = $q" in source) env["PREFIX_CHECK_FUNC"] = kt(raw("private val PREFIX_CHECK_FUNC = $q", q), emptyMap())
            return kt(raw("internal val INSTALL_RESTORE_COMMAND = $q", "$q.trimIndent()"), env).trimIndent()
        }
    }
}
