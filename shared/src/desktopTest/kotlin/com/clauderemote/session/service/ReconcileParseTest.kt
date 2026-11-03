package com.clauderemote.session.service

import com.clauderemote.testsupport.safeDeleteTree
import com.clauderemote.testsupport.LiveFileCanary
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ReconcileParse] and the forget / uuid / CAP decisions on top of it: pure parsing, and the
 * real `reconcileAll` shell run against fake `tmux`/`ps` and real spawned `claude`-named
 * processes under a temp HOME, guarded by [LiveFileCanary]. `ps` is faked on purpose: the real
 * one would list the LIVE claude processes of this box and the script would read their config
 * dirs.
 */
class ReconcileParseTest {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun parse(raw: String) = ReconcileParse.parse(raw, json)

    private fun full(tmux: String = "claude-server-b\n", tomb: String = "claude-server-a\n") =
        "${tomb}---\n[]\n__END_snap__ rc=0\n${tmux}__END_tmux__ rc=0\n__END_panes__ rc=0\n__END_ps__ rc=0\n__END_claude__ rc=0\n"

    // ---- sections -------------------------------------------------------------------------

    @Test fun onlyCompleteSectionsWithRcZeroAreKnown() {
        val raw = "x\n__END_snap__ rc=0\nl1\nl2\n__END_tmux__ rc=1\nz\n__END_ps__ rc=0\nlost\nno end"
        val s = ReconcileParse.sections(raw)
        assertEquals(setOf("snap", "ps"), s.keys)
        assertEquals(listOf("z"), s["ps"])
        // the cut-off tail belongs to a section that never closed
        assertTrue("claude" !in s)
    }

    @Test fun aManifestWithoutATrailingNewlineDoesNotSwallowTheEndLine() {
        // the real script emits a newline after the manifest; sections() itself only accepts END lines on a line of their own
        val s = ReconcileParse.sections("a\n---\n[]\n__END_snap__ rc=0\n")
        assertEquals(listOf("a", "---", "[]"), s["snap"])
        assertTrue("snap" !in ReconcileParse.sections("a\n---\n[]__END_snap__ rc=0\n"))
    }

    @Test fun aCutOffOrFailedSectionIsUnknownNotEmpty() {
        val cut = parse("claude-server-a\n---\n[]\n__END_snap__ rc=0\nclaude-server-b\nclaude-ser")
        assertEquals(setOf("claude-server-a"), cut.tombstoned)
        assertNull(cut.tmuxNames)
        assertNull(cut.panePids); assertNull(cut.procs); assertNull(cut.claude)
        val failed = parse(full().replace("__END_tmux__ rc=0", "__END_tmux__ rc=1"))
        assertNull(failed.tmuxNames)
        // a snapshot without its `---` separator is not a snapshot
        assertNull(parse("claude-server-a\n__END_snap__ rc=0\n").tombstoned)
    }

    // ---- the forget matrix ----------------------------------------------------------------

    private fun verdict(tomb: String?, tmux: String?, name: String = "claude-server-a"): CloseVerdict {
        val raw = buildString {
            if (tomb != null) append(tomb).append("---\n[]\n__END_snap__ rc=0\n")
            if (tmux != null) append(tmux).append("__END_tmux__ rc=0\n")
        }
        return parse(raw).closeVerdict(name)
    }

    @Test fun forgetNeedsTombstonedAndAbsentAndAnotherLivePeer() {
        assertEquals(CloseVerdict.FORGET, verdict("claude-server-a\n", "claude-server-b\n"))
        // present in tmux
        assertEquals(CloseVerdict.KEEP, verdict("claude-server-a\n", "claude-server-a\nclaude-server-b\n"))
        // not tombstoned
        assertEquals(CloseVerdict.KEEP, verdict("", "claude-server-b\n"))
        // no OTHER live peer: an empty listing (tmux server gone) or only the anchor / foreign sessions
        assertEquals(CloseVerdict.KEEP, verdict("claude-server-a\n", ""))
        assertEquals(CloseVerdict.KEEP, verdict("claude-server-a\n", "__anchor__\nscratch\n"))
        // the peer must be a DIFFERENT claude-server session
        assertEquals(CloseVerdict.KEEP, verdict("claude-server-a\n", "claude-server-a\n"))
    }

    @Test fun unknownNeverForgets() {
        assertEquals(CloseVerdict.UNKNOWN, verdict(null, "claude-server-b\n"))
        assertEquals(CloseVerdict.UNKNOWN, verdict("claude-server-a\n", null))
        assertEquals(CloseVerdict.UNKNOWN, verdict(null, null))
    }

    // ---- uuid and CAP from facts ----------------------------------------------------------

    private fun facts(panes: String, ps: String, claude: String) = parse(
        "---\n[]\n__END_snap__ rc=0\n${panes}__END_panes__ rc=0\n${ps}__END_ps__ rc=0\n${claude}__END_claude__ rc=0\n",
    )

    @Test fun uuidIsTheShallowestSubtreeProcessWithASessionFile() {
        val s = facts(
            "claude-server-a 100\n", "100 1 bash\n101 100 claude\n102 101 claude\n300 1 claude\n",
            "EXE 101 -\nCAPF 101 1 0\nEXE 102 -\nCAPF 102 1 0\nSID 102 $uuid\nSID 300 bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee\n",
        )
        assertEquals(uuid, s.liveUuid("claude-server-a")) // 300 is another pane's claude
        assertNull(s.liveUuid("claude-server-nope"))
    }

    @Test fun aSessionFileWhoseTranscriptDoesNotExistYetAnswersNoUuid() {
        val s = facts("claude-server-a 100\n", "100 1 claude\n", "EXE 100 -\nCAPF 100 1 0\nSID 100 -\n")
        assertNull(s.liveUuid("claude-server-a"))
    }

    @Test fun capabilityFollowsMarkerAndDisableAllHooksOfTheShallowestClaude() {
        fun cap(f: String) = facts("claude-server-a 100\n", "100 1 bash\n101 100 claude\n102 101 claude\n", f).capability("claude-server-a")
        assertEquals(true, cap("CAPF 101 1 0\nCAPF 102 0 0\n")) // the deeper claude is irrelevant
        assertEquals(false, cap("CAPF 101 0 0\n"))
        assertEquals(false, cap("CAPF 101 1 1\n"))
        assertNull(cap("CAPF 101 - 0\n"))
        assertNull(cap("CAPF 101 1 -\n"))
        assertNull(cap("")) // no facts for C
        // no claude in the pane, or two at the same depth: unknown, never a "no"
        assertNull(facts("claude-server-a 100\n", "100 1 bash\n", "").capability("claude-server-a"))
        assertNull(facts("claude-server-a 100\n", "100 1 bash\n101 100 claude\n102 100 claude\n", "CAPF 101 1 0\nCAPF 102 1 0\n").capability("claude-server-a"))
    }

    @Test fun theHookSwitchFileMakesCapabilityNoAndAnUnreadableOneUnknown() {
        fun cap(f: String) = facts("claude-server-a 100\n", "100 1 claude\n", f).capability("claude-server-a")
        assertEquals(true, cap("CAPF 100 1 0 0\n"))
        assertEquals(false, cap("CAPF 100 1 0 1\n"))
        assertEquals(false, cap("CAPF 100 0 0 1\n"))
        assertNull(cap("CAPF 100 1 0 -\n"))
        assertEquals(true, cap("CAPF 100 1 0\n"), "a script that does not report the switch means it is not set")
    }

    @Test fun aSessionWithTwoPanesIsJudgedByItsFirstPane() {
        val s = facts(
            "claude-server-a 100\nclaude-server-a 200\n", "100 1 bash\n101 100 claude\n200 1 bash\n201 200 claude\n",
            "EXE 101 -\nCAPF 101 1 0\nEXE 201 -\nCAPF 201 0 0\nSID 101 $uuid\nSID 201 bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee\n",
        )
        assertEquals(100, s.panePids!!["claude-server-a"])
        assertEquals(true, s.capability("claude-server-a"))
        assertEquals(uuid, s.liveUuid("claude-server-a"))
    }

    @Test fun aVersionNumberCommCountsOnlyWithAClaudeVersionsExe() {
        val ps = "100 1 bash\n101 100 2.1.316\n102 101 2.1.316\n"
        val s = facts("claude-server-a 100\n", ps,
            "EXE 101 /home/u/.local/share/claude/versions/2.1.316\nCAPF 101 1 0\nEXE 102 /home/u/vendor/rg\nCAPF 102 0 0\n")
        assertEquals(true, s.capability("claude-server-a"))
        val rgOnly = facts("claude-server-a 100\n", "100 1 bash\n102 100 2.1.316\n", "EXE 102 /home/u/vendor/rg\nCAPF 102 1 0\n")
        assertNull(rgOnly.capability("claude-server-a"))
    }

    // ---- the real script ------------------------------------------------------------------

    private fun bashAvailable() = try { ProcessBuilder("bash", "-c", "command -v flock jq >/dev/null").start().waitFor() == 0 } catch (_: Exception) { false }

    private fun sh(file: File, text: String, mode: String = "755") = file.apply {
        parentFile.mkdirs(); writeText(text); Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString(if (mode == "755") "rwxr-xr-x" else "rw-------"))
    }

    @Test fun reconcileAllAgainstFakeTmuxAndRealClaudeNamedProcesses() {
        if (!bashAvailable()) { println("bash/flock/jq missing — skipping"); return }
        val root = Files.createTempDirectory("reconcile-test").toFile()
        val procs = mutableListOf<Process>()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val cfg = File(root, "cfg").apply { mkdirs() }
            val bin = File(root, "bin")
            val sleepBin = File(ProcessBuilder("bash", "-c", "command -v sleep").start().inputStream.bufferedReader().readText().trim())
            fun spawn(copyTo: File): Long {
                copyTo.parentFile.mkdirs(); sleepBin.copyTo(copyTo, overwrite = true); copyTo.setExecutable(true)
                // argv[0] stays "sleep": a multicall coreutils (uutils, ubuntu-26.04) copied to another name dispatches on argv[0] and exits
                val pb = ProcessBuilder("bash", "-c", "exec -a sleep \"\$0\" 300", copyTo.path).also { it.environment()["CLAUDE_CONFIG_DIR"] = cfg.path; it.environment()["HOME"] = home.path }
                return pb.start().also { procs += it }.pid()
            }
            val claudePid = spawn(File(root, "real/claude"))
            val versionPid = spawn(File(root, "share/claude/versions/2.1.316")) // comm 2.1.316, exe under claude/versions/
            val rgPid = spawn(File(root, "share/vendor/2.1.317"))              // comm 2.1.317, exe elsewhere
            Thread.sleep(200)

            val token = "tok" + System.nanoTime()
            val names = listOf("claude-server-a$token", "claude-server-my proj's tab", "claude-server-čeština")
            val tricky = names[1]
            File(home, "fake").mkdirs()
            File(home, "fake/sessions").writeText(names.joinToString("\n") + "\nclaude-server-b\n")
            // pane pids are fake except the real claude child: bash(9001) -> claude, and another pane running the version binary directly
            File(home, "fake/panes").writeText(
                "${names[0]} 9001\n$tricky 9002\n${names[2]} 9003\n${names[0]}-longer 9004\nunasked 9005\n",
            )
            // comm of the version process is the bare version number
            File(home, "fake/ps").writeText(
                "9001 1 bash\n$claudePid 9001 claude\n9002 1 bash\n$versionPid 9002 2.1.316\n9003 1 bash\n$rgPid 9003 2.1.317\n",
            )
            sh(File(bin, "tmux"), "#!/bin/sh\ncase \"\$*\" in\n*list-sessions*) cat \"\$HOME/fake/sessions\";;\n*list-panes*\" -a \"*) cat \"\$HOME/fake/panes\";;\n*list-panes*) echo 9001;;\n*) exit 1;;\nesac\n")
            sh(File(bin, "ps"), "#!/bin/sh\necho x >> \"\$HOME/fake/ps.calls\"\ncase \"\$*\" in\n*ppid=*) cat \"\$HOME/fake/ps\";;\n*) awk '{print \$1, \$3}' \"\$HOME/fake/ps\";;\nesac\n")
            File(home, ".claude-remote").mkdirs()
            File(home, ".claude-remote/forgotten").writeText("claude-server-b\n")
            File(home, ".claude-remote/sessions.json").writeText("""[{"id":"i","serverId":"s","folder":"/f","tmuxSessionName":"${names[0]}","claudeSessionId":"$uuid"}]""")
            File(cfg, "settings.json").writeText("""{"hooks":{"Stop":[{"hooks":[{"command":"x claude-remote-ev v2 y"}]}]}}""")
            File(cfg, "sessions").mkdirs(); File(cfg, "sessions/$claudePid.json").writeText("""{"sessionId":"$uuid"}""")
            File(cfg, "projects/p").mkdirs(); File(cfg, "projects/p/$uuid.jsonl").writeText("{}\n")

            var lastOut = ""
            fun run(): ReconcileSnapshot = LiveFileCanary().guard(token, home, root) { env ->
                // via a file: the JVM would mangle non-ASCII characters in an argv under the C locale (JSch sends UTF-8)
                val script = File(root, "reconcile.sh").also { it.writeText(ManifestCommands.reconcileAll(names), Charsets.UTF_8) }
                val pb = ProcessBuilder("bash", script.path).redirectErrorStream(false)
                env.applyTo(pb)
                pb.environment()["PATH"] = "${bin.path}:" + System.getenv("PATH")
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                lastOut = out + "\n[stderr] " + p.errorStream.bufferedReader().readText()
                parse(out)
            }

            val s = run()
            assertEquals(1, File(home, "fake/ps.calls").readLines().size, "ONE ps for the whole round: $lastOut")
            assertEquals(setOf("claude-server-b"), s.tombstoned, lastOut)
            assertEquals(names.toSet() + "claude-server-b", s.tmuxNames)
            // exactly the asked names, whole-name match: the longer name and the unasked pane are not in
            assertEquals(names.toSet(), s.panePids!!.keys)
            assertEquals(9002, s.panePids!![tricky])
            assertEquals(uuid, s.manifestUuid(names[0]))
            // the real claude: exe read, marker found, no disable, session file + transcript
            val f = s.claude!![claudePid.toInt()]!!
            assertEquals(true, f.markerInSettings); assertEquals(false, f.disableAllHooks); assertEquals(uuid, f.uuid)
            assertEquals(uuid, s.liveUuid(names[0]))
            assertEquals(true, s.capability(names[0]))
            // version-number comm: counted only when its exe lies under claude/versions/
            assertEquals(true, s.capability(tricky))
            assertNull(s.capability(names[2]), "an exe outside claude/versions/ is no claude")

            // the standalone uuid probe: the shallowest subtree process with a session file, transcript required
            fun probe(): String = LiveFileCanary().guard(token, home, root) { env ->
                val script = File(root, "uuid.sh").also { it.writeText(ManifestCommands.uuidProbe(names[0]), Charsets.UTF_8) }
                val pb = ProcessBuilder("bash", script.path)
                env.applyTo(pb)
                pb.environment()["PATH"] = "${bin.path}:" + System.getenv("PATH")
                val p = pb.start()
                p.inputStream.bufferedReader().readText().trim().also { p.waitFor() }
            }
            assertEquals(uuid, probe())
            File(cfg, "projects/p/$uuid.jsonl").renameTo(File(cfg, "projects/p/$uuid.jsonl.gone"))
            assertEquals("", probe(), "a session file whose transcript does not exist yet answers nothing")
            File(cfg, "projects/p/$uuid.jsonl.gone").renameTo(File(cfg, "projects/p/$uuid.jsonl"))

            // CAP follows the settings of the config dir at once — no restart of the claude process
            File(cfg, "settings.local.json").writeText("""{"disableAllHooks": true}""")
            assertEquals(false, run().capability(names[0]))
            File(cfg, "settings.local.json").delete()
            // the owner's switch that silences the hook script
            File(home, ".claude-remote/hookev.off").writeText("")
            assertEquals(false, run().capability(names[0]))
            File(home, ".claude-remote/hookev.off").delete()
            assertEquals(true, run().capability(names[0]))
            File(cfg, "settings.json").writeText("""{"hooks":{}}""")
            assertEquals(false, run().capability(names[0]))
            File(cfg, "settings.json").setReadable(false)
            if (!File(cfg, "settings.json").canRead()) assertNull(run().capability(names[0]), "unreadable settings: unknown, not a no")
        } finally {
            procs.forEach { it.destroyForcibly() }
            safeDeleteTree(root)
        }
    }
}
