package com.clauderemote.session.service

import com.clauderemote.model.TmuxNameParser
import com.clauderemote.testsupport.safeDeleteTree
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    // ---- the goldens v18 and v19 -----------------------------------------------------------------

    /**
     * `golden/restore-installer-v18.sh` is the installer cedfd70 generated (drift.sh, restore.sh and tmux-sockheal.sh
     * in it are byte for byte what the live box ran from 2026-11-08 to 2026-11-10, checked when the golden was made). The
     * extraction below reads the installer out of the Kotlin source the way the compiler builds it; that it gives today's
     * constant from today's source is what makes its reading of cedfd70 trustworthy.
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
     * `golden/restore-installer-v19.sh` is the installer 1bdcbd5 generated: the scripts the live box runs since
     * 2026-11-10T10:17Z (v19). The base of hradlo A below and of the v19 worlds in `DriftV20IdentityIntegrationTest`.
     */
    @Test fun theGoldenV19IsTheInstaller1bdcbd5Generated() {
        val golden = golden19()
        assertEquals(GOLDEN_V19_SHA256, sha256(golden.toByteArray(Charsets.UTF_8)), "the golden changed")
        assertTrue("MARKER=\"claude-remote-restore-v19\"" in golden)
        val base = gitShow("$BASE_V19:shared/$SPS")
        if (base == null) {
            println("$BASE_V19 is not in this clone (a shallow checkout): the golden is checked by its SHA-256 only")
            return
        }
        assertEquals(golden, installerOf(base), "the golden is not what 1bdcbd5 generated")
        // the prefix write is outside INSTALL_RESTORE_COMMAND: v20 sends v19's unchanged (the v19 worlds use today's)
        assertEquals(prefixWriteOf(base), prefixWriteOf(File(SPS).readText()), "the prefix write changed since v19")
    }

    /**
     * Hradlo A, rewritten for v20 over the golden v19 (it was v18 → v19 over the golden v18; dodatek E "Marker v20"):
     * what the app sends for the box `server` differs from 1bdcbd5's in exactly the lines v19 had and v20 has not
     * ([REMOVED_V19], each reviewed), and every line v20 adds lies in a block named for v20 (`# ---- … v20 … ----` to
     * the next `# ---- end of … ----`) or is one of [ADDED_V20_OUTSIDE_BLOCKS]. The behaviour of the blocks is tested in
     * `DriftV20Test` and `DriftV20IntegrationTest`; that the box `server` sees none of it in `DriftV20IdentityIntegrationTest`.
     */
    @Test fun forServerV20ChangedOnlyItsNamedBlocksAndTheseLines() {
        val old = (golden19() + "\n" + SessionPersistenceService.prefixWrite(TmuxNameParser.DEFAULT_PREFIX) + "\n" +
            SessionPersistenceService.INSTALL_RESTORE_END + "\n").lines()
        val new = String(SessionPersistenceService.installRestoreStdin(TmuxNameParser.DEFAULT_PREFIX), Charsets.UTF_8).lines()
        val (removed, addedAt) = lineDiffAt(old, new)
        assertEquals(REMOVED_V19, removed, "removed lines")
        val start = Regex("""^\s*# ---- .*\bv20\b.* ----$""")
        val end = Regex("""^\s*# ---- end of .* ----$""")
        // a block runs to the next end line (a block inside another ends the outer one there)
        val inBlock = mutableSetOf<Int>()
        var i = 0
        while (i < new.size) {
            if (start.matches(new[i])) {
                val j = (i until new.size).first { end.matches(new[it]) }
                (i..j).forEach { inBlock += it }
                i = j + 1
            } else i++
        }
        val names = new.filter { start.matches(it) }.map { it.trim() }
        assertEquals(ADDED_V20_OUTSIDE_BLOCKS, addedAt.filter { it !in inBlock }.map { new[it] }, "added lines outside the named v20 blocks")
        assertEquals(V20_BLOCKS.sorted(), names.distinct().sorted(), "the named v20 blocks")
        assertTrue(names.size <= 20, "v20 is ${names.size} named blocks")
    }

    /**
     * Review D2 kód K2 / adv N4, dodatek E "záloha": a script's compat line names every older marker, so "the replaced
     * script is v<ver>" also holds for a newer one (a second installer may have put it there in between). The copy
     * must then carry no newer marker: no copy from it and no temp file left. A script of that very marker is copied
     * byte for byte, published by `ln` (which never replaces a copy). Each of the installer's four rollback blocks
     * (restore.sh and drift.sh, as of v18 and v19), run alone in bash, over scripts of v17 … v20.
     */
    @Test fun theRollbackCopyIsOnlyOfItsOwnMarkerAndOnlyOnce() {
        fun compat(upTo: Int) = "# marker-compat (do NOT remove): " + (6..upTo).joinToString(" ") { "claude-remote-restore-v$it" }
        for ((name, variable) in listOf("restore.sh" to "SCRIPT", "drift.sh" to "DRIFT")) for (ver in listOf(18, 19)) {
            val rollback = block(SessionPersistenceService.INSTALL_RESTORE_COMMAND,
                "            # ---- rollback copy of $name as of v$ver (D6-S, v20) ----", "            # ---- end of the rollback copy of $name as of v$ver ----")
            assertTrue(rollback.any { "ln \"\$$variable.v$ver.tmp.\$\$\" \"\$$variable.v$ver\"" in it } && rollback.none { "mv -f" in it },
                "$name v$ver: published by ln, never mv -f")
            for (scriptVer in 17..20) {
                val script = "#!/usr/bin/env bash\n${compat(scriptVer)}\nexit 0\n"
                val copied = scriptVer == ver
                for (existing in listOf(false, true)) {
                    val home = freshHome()
                    val target = File(home, ".claude-remote/$name").apply { writeText(script) }
                    val copy = File(home, ".claude-remote/$name.v$ver")
                    if (existing) copy.writeText("an earlier copy\n")
                    val r = run(listOf("bash", "-c", "set -e\n$variable=\"\$HOME/.claude-remote/$name\"\n" + rollback.joinToString("\n") + "\necho DONE\n"), home, dir = home)
                    val what = "$name as of v$ver, a v$scriptVer script${if (existing) ", a copy there" else ""}"
                    assertEquals(0, r.exit, "$what: ${r.err}")
                    assertEquals("DONE", r.out.trim(), what)
                    when {
                        existing -> assertEquals("an earlier copy\n", copy.readText(), "$what: a copy was overwritten")
                        copied -> assertEquals(script, copy.readText(), "$what: byte for byte")
                        else -> assertFalse(copy.exists(), "$what: a copy of a script of another marker")
                    }
                    val made = copied && !existing
                    assertEquals(made, File(home, ".claude-remote/install.log").readTextOrNull()?.contains("kept as $name.v$ver") == true, what)
                    assertEquals((listOf(name) + (if (made) listOf("install.log") else emptyList()) + (if (made || existing) listOf("$name.v$ver") else emptyList())).sorted(),
                        File(home, ".claude-remote").list()!!.sorted(), "$what: no temp file left")
                    assertEquals(script, target.readText(), "$what: the script itself untouched")
                }
            }
        }
    }

    /**
     * Dodatek E: the compat line carries v6 … v20 in all three scripts (a v18 or v19 client then finds its marker in a
     * v20 script and rewrites nothing), and tmux-sockheal.sh, which gets no rollback copy, is v19's but for that line
     * and the corrected comment on `drift-heal.off` (it is no brake on drift's relaunch: kritika E H6, KK22).
     */
    @Test fun theCompatLineNamesEveryMarkerAndSockhealIsV19ButForItsMarkerAndOneComment() {
        val cmd = SessionPersistenceService.INSTALL_RESTORE_COMMAND
        // one source for the marker (review E-D K-ED5): MARKER is made of MARKER_VERSION, the compat lines are held to it
        assertEquals(20, SessionPersistenceService.MARKER_VERSION)
        assertTrue("        MARKER=\"claude-remote-restore-v${SessionPersistenceService.MARKER_VERSION}\"" in cmd.lines())
        val compat = "# marker-compat (do NOT remove): " + (6..SessionPersistenceService.MARKER_VERSION).joinToString(" ") { "claude-remote-restore-v$it" }
        for (eof in listOf("RESTORE_EOF", "DRIFT_EOF", "SOCKHEAL_EOF")) assertEquals(compat, heredoc(cmd, eof).lines()[1], eof)
        val (removed, added) = lineDiff(heredoc(golden19(), "SOCKHEAL_EOF").lines(), heredoc(cmd, "SOCKHEAL_EOF").lines())
        assertEquals(listOf(compat.removeSuffix(" claude-remote-restore-v20"),
            "# ~/.claude-remote/drift-heal.off switches all of this off: the only rollback, since an older client never",
            "# rewrites a v18 script."), removed)
        assertEquals(listOf(compat,
            "# ~/.claude-remote/drift-heal.off switches this guard off, and only the guard: it is no brake on drift's relaunch",
            "# (self-heal then runs without it). The brakes for drift are ~/.claude-remote/drift.off and stopping its timer."), added)
    }

    /**
     * Review D3 adv N10: drift's overlay of the app's entries (B1), run alone in bash. The entries reach jq from a file,
     * so an app's sessions.json over 128 KB (one execve argument's limit) still adopts; and if that jq fails anyway only
     * the names of the block drop out of the tick — `claude-server-*` stays — and `__anchor__` is not dropped for them.
     */
    @Test fun whenTheOverlayOfTheAppsEntriesFailsOnlyItsOwnNamesDropOut() {
        val overlayBlock = block(SessionPersistenceService.INSTALL_RESTORE_COMMAND,
            "# ---- the app's entry of a session only a line of prefixes names (B1) ----", "# ---- end of the app's entry ----")
        fun row(n: String) = """{"id":"$n","serverId":"","folder":"/w/$n","mode":"YOLO","model":"DEFAULT","tmuxSessionName":"$n","connectionType":"SSH","alias":"","claudeSessionId":null,"createdAt":0,"accountSlug":null}"""
        val entryB = """{"id":"tab-1","serverId":"srv-box","folder":"/w/b","mode":"DEFAULT","model":"OPUS","tmuxSessionName":"claude-box-b","connectionType":"MOSH","alias":"","claudeSessionId":null,"createdAt":1762700000000,"accountSlug":null}"""
        val filler = (0 until 1_500).joinToString(",") { """{"tmuxSessionName":"claude-box-old-$it--a-long-alias","folder":"/home/someone/projects/project-with-a-long-name-$it","serverId":"srv-box-0123456789abcdef"}""" }
        val big = "[$entryB,{\"tmuxSessionName\":\"claude-box-c\",\"serverId\":\"srv-box-c\",\"mode\":\"DEFAULT\"},$filler]"
        assertTrue(big.length > 140_000, "the app's entries are over one argument's 128 KB: ${big.length}")
        fun overlay(failingJq: Boolean): Pair<String, String> {
            val home = freshHome()
            val app = File(home, "app.json").apply { writeText(big) }
            val anchor = File(home, "anchor.log")
            val script = buildString {
                append("tmux() { printf '%s\\n' \"\$*\" >> \"\$HOME/anchor.log\"; }\n")
                if (failingJq) append("jq() { case \" \$* \" in *\" --slurpfile \"*) return 5;; esac; command jq \"\$@\"; }\n")
                append("APP_JSON=\$(cat \"\$HOME/app.json\")\n")
                append("LIVE='[${row("claude-server-a")},${row("claude-box-b")},${row("claude-box-c")}]'\n")
                append("OWN_META=\$'\\na:claude-box-b\\nc:claude-box-c'\n")
                append(overlayBlock.joinToString("\n")).append("\nprintf '%s' \"\$LIVE\"\n")
            }
            val r = run(listOf("bash", "-c", script), home, dir = home)
            assertEquals(0, r.exit, r.err)
            assertTrue(app.isFile)
            return r.out.trim() to (if (anchor.isFile) anchor.readText() else "")
        }
        fun names(live: String) = Json.parseToJsonElement(live).jsonArray.map { it.jsonObject["tmuxSessionName"]!!.jsonPrimitive.content }
        val (whole, killed) = overlay(failingJq = false)
        assertEquals(listOf("claude-server-a", "claude-box-b", "claude-box-c"), names(whole))
        assertEquals(Json.parseToJsonElement(entryB), Json.parseToJsonElement(whole).jsonArray[1], "a: the app's entry as it wrote it, from an app file over 128 KB")
        assertEquals(Json.parseToJsonElement(row("claude-box-c").replace("\"serverId\":\"\"", "\"serverId\":\"srv-box-c\"")), Json.parseToJsonElement(whole).jsonArray[2],
            "c: its own row with the serverId of the app's entry")
        assertEquals("kill-session -t =__anchor__\n", killed, "an adopted session drops the anchor (exactly that name)")
        val (dropped, notKilled) = overlay(failingJq = true)
        assertEquals(listOf("claude-server-a"), names(dropped), "the overlay failed: only its names drop out")
        assertEquals("", notKilled, "nothing adopted: the anchor stays")
    }

    private fun File.readTextOrNull(): String? = if (isFile) readText() else null

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
            // review D3 N11: a prefix already in the file is found without the lock — never NOT_WRITTEN, no wait
            prefixesOf(home).writeText("claude-box-\n")
            val t1 = System.nanoTime()
            val again = install(home, TmuxNameParser.prefixFor("box"))
            val ms1 = (System.nanoTime() - t1) / 1_000_000
            assertEquals(0, again.exit, again.out)
            assertTrue(again.out.lines().none { it.startsWith("PREFIX_") }, "already there: nothing to say\n${again.out}")
            assertTrue(ms1 < 4_500, "waited $ms1 ms for a lock it does not need")
            assertEquals("claude-box-\n", prefixesOf(home).readText())
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
    private fun lineDiff(old: List<String>, new: List<String>): Pair<List<String>, List<String>> =
        lineDiffAt(old, new).let { (removed, at) -> removed to at.map { new[it] } }

    /** Lines only in [old], and the indices in [new] of the lines only in [new] (longest common subsequence). */
    private fun lineDiffAt(old: List<String>, new: List<String>): Pair<List<String>, List<Int>> {
        val n = old.size
        val m = new.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (old[i] == new[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val removed = mutableListOf<String>()
        val added = mutableListOf<Int>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                old[i] == new[j] -> { i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> removed += old[i++]
                else -> added += j++
            }
        }
        while (i < n) removed += old[i++]
        while (j < m) added += j++
        return removed to added
    }

    /** The body of the heredoc `<<'[eof]'` in [script], its last newline included. */
    private fun heredoc(script: String, eof: String): String {
        val start = script.indexOf('\n', script.indexOf("<<'$eof'")) + 1
        return script.substring(start, script.indexOf("\n$eof\n", start) + 1)
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

        /** Hradlo A: the lines of 1bdcbd5's installer (for `server`) that v20 no longer has, in order. */
        val REMOVED_V19 = listOf(
            "        MARKER=\"claude-remote-restore-v19\"",
            "        trap 'rm -f \"\$SCRIPT.tmp.\$\$\" \"\$DRIFT.tmp.\$\$\" \"\$SOCKHEAL.tmp.\$\$\" \"\$SCRIPT.v18.tmp.\$\$\" \"\$DRIFT.v18.tmp.\$\$\"' EXIT",
            "# marker-compat (do NOT remove): claude-remote-restore-v6 claude-remote-restore-v7 claude-remote-restore-v8 claude-remote-restore-v9 claude-remote-restore-v10 claude-remote-restore-v11 claude-remote-restore-v12 claude-remote-restore-v13 claude-remote-restore-v14 claude-remote-restore-v15 claude-remote-restore-v16 claude-remote-restore-v17 claude-remote-restore-v18 claude-remote-restore-v19",
            "# ~/.claude-remote/drift-heal.off switches all of this off: the only rollback, since an older client never",
            "# rewrites a v18 script.",
            "        FOLDER_EXP=\"\${FOLDER/#\\~/\$HOME}\"",
            "        case \"\$FOLDER_EXP\" in /*) ;; *) FOLDER_EXP=\"\$HOME/\$FOLDER_EXP\";; esac",
            "        [ -d \"\$FOLDER_EXP\" ] || { echo \"skip \$TMUX_NAME — folder \$FOLDER_EXP missing\"; continue; }",
            "        CMD=\"\${ARGS[*]}\"",
            "            CMD=\"CLAUDE_CONFIG_DIR=\\\"\$HOME/.claude-remote/accounts/\$ACCT\\\" \$CMD\"",
            "                if [ -n \"\${TMUX_NAME:-}\" ] && [ -n \"\${FOLDER:-}\" ] \\",
            "                        FOLDER_EXP=\"\${FOLDER/#\\~/\$HOME}\"",
            "                        [ -d \"\$FOLDER_EXP\" ] && {",
            "                                    CMD=\"\$CMD --resume \$UUID\"",
            "                                    CMD=\"\$CMD --session-id \$UUID\"",
            "                                CMD=\"CLAUDE_CONFIG_DIR=\\\"\$HOME/.claude-remote/accounts/\$ACCT\\\" \$CMD\"",
            "            # ---- rollback copy of restore.sh (D6-S) ----",
            "            # Kept once when the script replaced here is v18 (kritika D2 N17): moving it back is the way back to v18, by",
            "            # hand and only with Lukáš's consent that day (any v19 client's next install rewrites it to v19 again).",
            "            # v19's compat line names v18 too, so the copy itself must not be v19 (a second installer may have replaced",
            "            # the script in between), and `ln` publishes it only where no copy is yet (K2): never one over another.",
            "                { cp -p \"\$SCRIPT\" \"\$SCRIPT.v18.tmp.\$\$\" && ! grep -q 'claude-remote-restore-v19' \"\$SCRIPT.v18.tmp.\$\$\" \\",
            "            # ---- end of the rollback copy of restore.sh ----",
            "            mv -f \"\$SCRIPT.tmp.\$\$\" \"\$SCRIPT\"",
            "            echo \"RESTORE_SCRIPT_INSTALLED\"",
            "        else",
            "            echo \"RESTORE_SCRIPT_PRESENT\"",
            "# marker-compat (do NOT remove): claude-remote-restore-v6 claude-remote-restore-v7 claude-remote-restore-v8 claude-remote-restore-v9 claude-remote-restore-v10 claude-remote-restore-v11 claude-remote-restore-v12 claude-remote-restore-v13 claude-remote-restore-v14 claude-remote-restore-v15 claude-remote-restore-v16 claude-remote-restore-v17 claude-remote-restore-v18 claude-remote-restore-v19",
            "# ~/.claude-remote/drift-heal.off switches all of this off: the only rollback, since an older client never",
            "# rewrites a v18 script.",
            "    # Every tmux call of a tick gets a time limit: a hung server must not wedge the timer unit.",
            "    command -v timeout >/dev/null 2>&1 && tmux() { timeout -k 2 10 tmux \"\$@\"; }",
            "    tmux kill-session -t __anchor__ 2>/dev/null || true",
            "APP_JSON=\"[]\"; OWN_META=\"\"",
            "for s in \$(tmux -u list-sessions -F '#{session_name}' 2>/dev/null); do",
            "    pane_pid=\$(tmux -u list-panes -t \"\$s\" -F '#{pane_pid}' 2>/dev/null | head -1)",
            "    folder=\$(tmux -u display-message -p -t \"\$s\" '#{pane_current_path}' 2>/dev/null)",
            "        *)  sig=\"\"",
            "            [ -n \"\$pid\" ] && case \" \$(tr '\\0' ' ' < \"/proc/\$pid/cmdline\" 2>/dev/null) \" in",
            "                *\" --dangerously-skip-permissions \"*|*\" --allow-dangerously-skip-permissions \"*) sig=1;;",
            "            esac",
            "            if [ -n \"\$sig\" ]; then OWN_META=\"\$OWN_META c:\$s\"",
            "            elif app_has \"\$s\"; then OWN_META=\"\$OWN_META a:\$s\"",
            "        args=\$(tr '\\0' ' ' < \"/proc/\$pid/cmdline\" 2>/dev/null)",
            "        case \"\$args\" in",
            "            *\"--model opus\"*)   model=OPUS;;",
            "            *\"--model sonnet\"*) model=SONNET;;",
            "            *\"--model haiku\"*)  model=HAIKU;;",
            "            *\"--model fable\"*)  model=FABLE;;",
            "        case \"\$args\" in",
            "            *\"--permission-mode auto\"*) mode=AUTO;;",
            "            *\"--permission-mode acceptEdits\"*) mode=AUTO_ACCEPT;;",
            "            *\"--allow-dangerously-skip-permissions\"*) mode=DEFAULT;;",
            "        sid=\$(echo \"\$args\" | sed -n 's/.*--\\(resume\\|session-id\\) \\([0-9a-f-]*\\).*/\\2/p' | head -1)",
            "done",
            "        | (\$own | split(\" \") | map(select(length > 0) | {key: .[2:], value: .[0:1]}) | from_entries) as \$om",
            "            | if \$om[\$n] == \"a\" then \$am[\$n]",
            "        tmux kill-session -t __anchor__ 2>/dev/null || true",
            "        LIVE=\$(printf '%s' \"\$LIVE\" | jq -c --arg own \"\$OWN_META\" '(\$own | split(\" \") | map(.[2:])) as \$o",
            "    NEW=\$(jq -n --argjson old \"\$OLD\" --argjson live \"\$LIVE\" --argjson forget \"\$FORGET\" '",
            "        ((\$live | map({key:.tmuxSessionName, value:.}) | from_entries) as \$lm",
            "    if [ -n \"\$NEW\" ] && { [ \"\$NEW\" != \"\$OLD\" ] || [ \"\${CORRUPT:-0}\" = \"1\" ]; }; then",
            "        DROP_EXPLAINED=\$(jq -n --argjson old \"\$OLD\" --argjson forget \"\$FORGET\" \\",
            "            '((\$old | map(.tmuxSessionName)) - \$forget | length) == 0' 2>/dev/null)",
            "    if [ -f \"\$FORGOTTEN\" ] && [ -s \"\$FORGOTTEN\" ]; then",
            "# Mirror the server-owned source of truth to the app-facing sessions.json (the",
            "# client may have blanked/shrunk it on reconnect); keeps the app view correct",
            "# within 60s and re-expands a client that wrongly forgot sessions.",
            "# Mirror ONLY a valid, non-empty manifest: an unguarded cp would copy a",
            "# truncated/empty \$SF over the app-facing file every tick and blank the app view.",
            "if [ -f \"\$SF\" ] && [ -s \"\$SF\" ] && jq -e 'length>0' \"\$SF\" >/dev/null 2>&1 \\",
            "   && ! cmp -s \"\$SF\" \"\$SF_APP\" 2>/dev/null; then",
            "    cp -f \"\$SF\" \"\$SF_APP\" 2>/dev/null || true",
            "fi",
            "    MISSING=\$(jq -r '.[].tmuxSessionName' \"\$SF\" 2>/dev/null | while IFS= read -r n; do",
            "        ! tmux has-session -t=\"\$n\" 2>/dev/null && echo \"\$n\"; done)",
            "        echo \"[\$(date -u +%FT%TZ)] drift: self-heal — relaunching missing: \$(echo \$MISSING | tr '\\n' ' ')\"",
            "            # ---- rollback copy of drift.sh (D6-S) ----",
            "            # Kept once when the script replaced here is v18 (kritika D2 N17): moving it back is the way back to v18, by",
            "            # hand and only with Lukáš's consent that day (any v19 client's next install rewrites it to v19 again).",
            "            # v19's compat line names v18 too, so the copy itself must not be v19 (a second installer may have replaced",
            "            # the script in between), and `ln` publishes it only where no copy is yet (K2): never one over another.",
            "                { cp -p \"\$DRIFT\" \"\$DRIFT.v18.tmp.\$\$\" && ! grep -q 'claude-remote-restore-v19' \"\$DRIFT.v18.tmp.\$\$\" \\",
            "            # ---- end of the rollback copy of drift.sh ----",
            "            mv -f \"\$DRIFT.tmp.\$\$\" \"\$DRIFT\"",
            "            echo \"DRIFT_SCRIPT_INSTALLED\"",
            "# marker-compat (do NOT remove): claude-remote-restore-v6 claude-remote-restore-v7 claude-remote-restore-v8 claude-remote-restore-v9 claude-remote-restore-v10 claude-remote-restore-v11 claude-remote-restore-v12 claude-remote-restore-v13 claude-remote-restore-v14 claude-remote-restore-v15 claude-remote-restore-v16 claude-remote-restore-v17 claude-remote-restore-v18 claude-remote-restore-v19",
            "# ~/.claude-remote/drift-heal.off switches all of this off: the only rollback, since an older client never",
            "# rewrites a v18 script.",
            "            mv -f \"\$SOCKHEAL.tmp.\$\$\" \"\$SOCKHEAL\"",
            "            echo \"SOCKHEAL_SCRIPT_INSTALLED\"",
        )

        /** Hradlo A: the lines v20 adds outside its named blocks, in order. */
        val ADDED_V20_OUTSIDE_BLOCKS = listOf(
            "        MARKER=\"claude-remote-restore-v20\"",
            "        trap 'rm -f \"\$SCRIPT.tmp.\$\$\" \"\$DRIFT.tmp.\$\$\" \"\$SOCKHEAL.tmp.\$\$\" \"\$SCRIPT.v18.tmp.\$\$\" \"\$DRIFT.v18.tmp.\$\$\" \"\$SCRIPT.v19.tmp.\$\$\" \"\$DRIFT.v19.tmp.\$\$\"' EXIT",
            "        # v20 (H10): each script missing the marker is written to its temp file first; the three renames follow back to",
            "        # back once all three are written, and only then the output: an install cut short while writing replaces no",
            "        # script, and a mix of versions is left only by one killed within the window of the three renames.",
            "        # An install killed outright (SIGKILL: no trap) leaves its temp files: ours, older than an hour, go here.",
            "        find \"\$HOME/.claude-remote\" -maxdepth 1 -type f \\( -name 'restore.sh.tmp.[0-9]*' -o -name 'drift.sh.tmp.[0-9]*' \\",
            "            -o -name 'tmux-sockheal.sh.tmp.[0-9]*' -o -name 'restore.sh.v1[89].tmp.[0-9]*' -o -name 'drift.sh.v1[89].tmp.[0-9]*' \\) \\",
            "            -mmin +60 -delete 2>/dev/null || true",
            "        R_NEW=0; D_NEW=0; S_NEW=0",
            "# marker-compat (do NOT remove): claude-remote-restore-v6 claude-remote-restore-v7 claude-remote-restore-v8 claude-remote-restore-v9 claude-remote-restore-v10 claude-remote-restore-v11 claude-remote-restore-v12 claude-remote-restore-v13 claude-remote-restore-v14 claude-remote-restore-v15 claude-remote-restore-v16 claude-remote-restore-v17 claude-remote-restore-v18 claude-remote-restore-v19 claude-remote-restore-v20",
            "# ~/.claude-remote/drift-heal.off switches this guard off, and only the guard: it is no brake on drift's relaunch",
            "# (self-heal then runs without it). The brakes for drift are ~/.claude-remote/drift.off and stopping its timer.",
            "",
            "        cr_acct_ok \"\$ACCT\" || { echo \"account \$(cr_q \"\$ACCT\") of \$TMUX_NAME is a path — starting it without an account\"; ACCT=\"\"; }",
            "        cr_name_ok \"\$TMUX_NAME\" || { echo \"skip \$TMUX_NAME — tmux cannot hold this name as written\"; continue; }",
            "            CMD=\"\$(cr_acct \"\$ACCT\") \$CMD\"",
            "                cr_acct_ok \"\${ACCT:-}\" || { echo \"account \$(cr_q \"\$ACCT\") of \${TMUX_NAME:-} is a path — starting it without an account\"; ACCT=\"\"; }",
            "                if [ -n \"\${TMUX_NAME:-}\" ] && [ -n \"\${FOLDER:-}\" ] && cr_name_ok \"\$TMUX_NAME\" \\",
            "                        FOLDER_EXP=\$(cr_folder \"\$FOLDER\") && [ -d \"\$FOLDER_EXP\" ] && {",
            "                                    CMD=\"\$CMD --resume \$(cr_q \"\$UUID\")\"",
            "                                    CMD=\"\$CMD --session-id \$(cr_q \"\$UUID\")\"",
            "                                CMD=\"\$(cr_acct \"\$ACCT\") \$CMD\"",
            "            R_NEW=1",
            "# marker-compat (do NOT remove): claude-remote-restore-v6 claude-remote-restore-v7 claude-remote-restore-v8 claude-remote-restore-v9 claude-remote-restore-v10 claude-remote-restore-v11 claude-remote-restore-v12 claude-remote-restore-v13 claude-remote-restore-v14 claude-remote-restore-v15 claude-remote-restore-v16 claude-remote-restore-v17 claude-remote-restore-v18 claude-remote-restore-v19 claude-remote-restore-v20",
            "# ~/.claude-remote/drift-heal.off switches this guard off, and only the guard: it is no brake on drift's relaunch",
            "# (self-heal then runs without it). The brakes for drift are ~/.claude-remote/drift.off and stopping its timer.",
            "# Every tmux call of a tick gets a time limit, with the guard or without it (v20: it used to go with drift-heal.off).",
            "# It ends the client; a pipe or \$(…) reading from it still waits while a stopped server holds the client's stdout, so",
            "# only the guard (on by default) keeps a tick short over a hung server.",
            "command -v timeout >/dev/null 2>&1 && tmux() { timeout -k 2 10 tmux \"\$@\"; }",
            "    tmux kill-session -t =__anchor__ 2>/dev/null || true",
            "APP_JSON=\"[]\"; OWN_META=\"\"; B1_DROPPED=0",
            "        *)  if [ -n \"\$a_sig\" ]; then OWN_META=\"\$OWN_META\"\$'\\n'\"c:\$s\"",
            "            elif app_has \"\$s\"; then OWN_META=\"\$OWN_META\"\$'\\n'\"a:\$s\"",
            "        case \"\$a_model\" in",
            "            opus)   model=OPUS;;",
            "            sonnet) model=SONNET;;",
            "            haiku)  model=HAIKU;;",
            "            fable)  model=FABLE;;",
            "        case \"\$a_pmode\" in",
            "            auto) mode=AUTO;;",
            "            acceptEdits) mode=AUTO_ACCEPT;;",
            "            *) [ -z \"\$a_allow\" ] || mode=DEFAULT;;",
            "        # a session id is hex and dashes (a uuid): anything else after --resume (a search term, a flag) is none",
            "        case \"\$a_sid\" in ''|-*|*[!0-9a-f-]*) ;; *) sid=\$a_sid;; esac",
            "done < <(tmux -u list-sessions -F '#{session_name}' 2>/dev/null)",
            "# ---- end of the live sessions ----",
            "        def crsafe: (if has(\"claudeSessionId\") then .claudeSessionId |= (if type == \"string\" and test(\"\\\\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\\\z\") then . else null end) else . end)",
            "            | (if has(\"accountSlug\") then .accountSlug |= (if type == \"string\" and test(\"\\\\A[\\\\p{L}\\\\p{N}@._-]{1,64}\\\\z\") and . != \".\" and . != \"..\" then . else null end) else . end);",
            "        | (\$own | split(\"\\n\") | map(select(length > 0) | {key: .[2:], value: .[0:1]}) | from_entries) as \$om",
            "            | if \$om[\$n] == \"a\" then (\$am[\$n] | crsafe)",
            "        tmux kill-session -t =__anchor__ 2>/dev/null || true",
            "        LIVE=\$(printf '%s' \"\$LIVE\" | jq -c --arg own \"\$OWN_META\" '(\$own | split(\"\\n\") | map(select(length > 0) | .[2:])) as \$o",
            "        # v20 (E3): this tick's manifest misses these names, so the mirror below must not copy it over the app's",
            "        # sessions.json, which still has the app's entries of them",
            "        B1_DROPPED=1",
            "        fi",
            "    fi",
            "    # ---- end of the first manifest ----",
            "    NEW=\$(jq -n --slurpfile old0 <(printf '%s' \"\$OLD\") --slurpfile live0 <(printf '%s' \"\$LIVE\") --argjson forget \"\$FORGET\" '",
            "        \$old0[0] as \$old | \$live0[0] as \$live",
            "        | ((\$live | map({key:.tmuxSessionName, value:.}) | from_entries) as \$lm",
            "    if [ \"\$SEED_HOLD\" = 0 ] && [ -n \"\$NEW\" ] && { [ \"\$NEW\" != \"\$OLD\" ] || [ \"\${CORRUPT:-0}\" = \"1\" ] || [ \"\$SEEDED\" = \"1\" ]; }; then",
            "        DROP_EXPLAINED=\$(jq -n --slurpfile old0 <(printf '%s' \"\$OLD\") --argjson forget \"\$FORGET\" \\",
            "            '\$old0[0] as \$old | ((\$old | map(.tmuxSessionName)) - \$forget | length) == 0' 2>/dev/null)",
            "            # the first manifest is logged once it is written (A-ED3: not when the write could not happen)",
            "            [ \"\$SEEDED\" = 1 ] && [ -f \"\$SF\" ] && echo \"[\$(date -u +%FT%TZ)] drift: first manifest of this box — \$SEEDLEN entries from the app's sessions.json\"",
            "            [ -f \"\$HOLD_ST\" ] && [ -f \"\$SF\" ] && rm -f \"\$HOLD_ST\"",
            "    if [ \"\$SEED_HOLD\" = 0 ] && [ -f \"\$FORGOTTEN\" ] && [ -s \"\$FORGOTTEN\" ]; then",
            "    # A name tmux cannot hold as written (`.`, `:`, a control character: tmux makes it `_` or escapes it) is never",
            "    # \"missing\": its relaunch would start a session of another name, every tick (v20, A-ED1). On the box `server` the",
            "    # manifest holds only names of live sessions, so no such name is ever there.",
            "    MISSING=\$(jq -r '.[].tmuxSessionName | select((type == \"string\" and test(\"[.:]|[[:cntrl:]]\")) | not)' \"\$SF\" 2>/dev/null | while IFS= read -r n; do",
            "        tmux has-session -t=\"\$n\" 2>/dev/null && continue",
            "    done)",
            "    SKIPPED=\$(printf '%s\\n' \"\$MISSING\" | sed -n 's/^s://p')",
            "    MISSING=\$(printf '%s\\n' \"\$MISSING\" | sed -n 's/^m://p')",
            "        echo \"[\$(date -u +%FT%TZ)] drift: self-heal — relaunching missing: \$(printf '%s\\n' \"\$MISSING\" | tr '\\n' ' ')\"",
            "            D_NEW=1",
            "# marker-compat (do NOT remove): claude-remote-restore-v6 claude-remote-restore-v7 claude-remote-restore-v8 claude-remote-restore-v9 claude-remote-restore-v10 claude-remote-restore-v11 claude-remote-restore-v12 claude-remote-restore-v13 claude-remote-restore-v14 claude-remote-restore-v15 claude-remote-restore-v16 claude-remote-restore-v17 claude-remote-restore-v18 claude-remote-restore-v19 claude-remote-restore-v20",
            "# ~/.claude-remote/drift-heal.off switches this guard off, and only the guard: it is no brake on drift's relaunch",
            "# (self-heal then runs without it). The brakes for drift are ~/.claude-remote/drift.off and stopping its timer.",
            "            S_NEW=1",
        )

        /** Hradlo A: the blocks v20 adds or rewrites, by their opening line. */
        val V20_BLOCKS = listOf(
            "# ---- a name tmux holds as written (v20, A-ED1) ----",
            "# ---- an account slug that is no path (v20, T-ED13) ----",
            "# ---- an entry whose folder is missing (v20, E4b) ----",
            "# ---- claude's arguments, one by one (v20, E2 and E2b) ----",
            "# ---- one shell word (v20, A-ED2) ----",
            "# ---- publish the scripts (v20, H10) ----",
            "# ---- rollback copy of drift.sh as of v18 (D6-S, v20) ----",
            "# ---- rollback copy of drift.sh as of v19 (D6-S, v20) ----",
            "# ---- rollback copy of restore.sh as of v18 (D6-S, v20) ----",
            "# ---- rollback copy of restore.sh as of v19 (D6-S, v20) ----",
            "# ---- the brake (v20, H6) ----",
            "# ---- the command, word by word (v20, A-ED2) ----",
            "# ---- the entries left out for their folder, logged once per change (v20, E4b) ----",
            "# ---- the first manifest of a box with another name (v20, E3) ----",
            "# ---- the first manifest postponed (v20, A-ED9) ----",
            "# ---- the folder (v20, E4b) ----",
            "# ---- the folder of a manifest entry (v20, E4b) ----",
            "# ---- the live sessions, one name per line (v20, E1) ----",
            "# ---- the mirror (v20: under the manifest lock, by rename) ----",
        )

        const val GOLDEN_V19_SHA256 = "e620cc7c7f5e974b1e3817923ae96f8fc1a56b66a5f45d40ea938045f60745b8"
        const val BASE_V19 = "1bdcbd5c0031b14cf3c5e7fbc86e27cb148b15a3"

        fun golden(): String = goldenFile("restore-installer-v18.sh")

        fun golden19(): String = goldenFile("restore-installer-v19.sh")

        private fun goldenFile(name: String): String {
            val rel = "src/desktopTest/resources/golden/$name"
            return (listOf(File(rel), File("shared/$rel")).firstOrNull { it.isFile } ?: File(rel)).readText()
        }

        /** The raw template of `prefixWrite` in a source text of `SessionPersistenceService.kt`. */
        fun prefixWriteOf(source: String): String {
            val start = "internal fun prefixWrite(prefix: String): String = \"\"\""
            val a = source.indexOf(start)
            require(a >= 0) { "no prefixWrite in the source" }
            return source.substring(a, source.indexOf("\"\"\"", a + start.length))
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
            Regex("""MARKER_VERSION = (\d+)""").find(source)?.let { env["MARKER_VERSION"] = it.groupValues[1] }
            if ("private val FOLDER_EXPAND_FUNC = $q" in source) env["FOLDER_EXPAND_FUNC"] = kt(raw("private val FOLDER_EXPAND_FUNC = $q", q), emptyMap())
            // v20: the rollback copies come from one function; its template is read from the same source
            if ("internal fun rollbackCopy(" in source) {
                val markerVersion = Regex("""MARKER_VERSION = (\d+)""").find(source)!!.groupValues[1].toInt()
                val template = raw("internal fun rollbackCopy(", ".removePrefix(").let { it.substring(it.indexOf("return $q") + "return $q".length).removeSuffix(q) }
                for (m in Regex("""\$\{rollbackCopy\("(\w+)", "([^"]+)", (\d+)\)\}""").findAll(source)) {
                    val (variable, name, ver) = m.destructured
                    val newer = (ver.toInt() + 1..markerVersion).joinToString("|")
                    env[m.value.removePrefix("\${").removeSuffix("}")] = template.replace("\${d}", "\$").replace("\$d", "\$")
                        .replace("\$variable", variable).replace("\$name", name).replace("\$ver", ver).replace("\$newer", newer).removePrefix("\n")
                }
            }
            return kt(raw("internal val INSTALL_RESTORE_COMMAND = $q", "$q.trimIndent()"), env).trimIndent()
        }
    }
}
