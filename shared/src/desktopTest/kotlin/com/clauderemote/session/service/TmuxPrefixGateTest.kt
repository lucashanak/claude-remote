package com.clauderemote.session.service

import com.clauderemote.model.TmuxNameParser
import com.clauderemote.testsupport.safeDeleteTree
import org.junit.Assume
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D6-K in shell and in the source: the manifest push's guard ("no live session of ours — skip") counts the box's own
 * prefix and the default one as fixed strings, and no literal `claude-server-` is left outside the one place of truth
 * and its named exceptions.
 */
class TmuxPrefixGateTest {
    private val root = File(System.getProperty("java.io.tmpdir")).resolve("prefix-gate-${System.nanoTime()}").also { it.mkdirs() }

    @AfterTest fun tearDown() { safeDeleteTree(root) }

    /**
     * Runs [ManifestCommands.pushMerge] with [prefixes] in a private HOME, a `tmux` of its own first on PATH that lists
     * [live] (it touches no socket); true when the merge ran (sessions.json written), false when the guard skipped it.
     */
    private fun merged(prefixes: Set<String>, vararg live: String): Boolean {
        Assume.assumeTrue("jq not installed: the push's merge path cannot run here", File("/usr/bin/jq").canExecute() || File("/bin/jq").canExecute())
        val dir = File(root, "run-${System.nanoTime()}").also { it.mkdirs() }
        val home = File(dir, "home").also { it.mkdirs() }
        val bin = File(dir, "bin").also { it.mkdirs() }
        File(bin, "tmux").apply {
            writeText("#!/bin/sh\ncase \"\$*\" in *list-sessions*) printf '%s\\n' ${live.joinToString(" ") { ManifestCommands.sq(it) }} ;; esac\nexit 0\n")
            setExecutable(true)
        }
        val p = ProcessBuilder("bash", "-c", ManifestCommands.pushMerge("srv", 2, prefixes)).directory(home).redirectErrorStream(true).also {
            it.environment()["HOME"] = home.absolutePath
            it.environment()["PATH"] = bin.absolutePath + File.pathSeparator + it.environment()["PATH"].orEmpty()
        }.start()
        p.outputStream.use { it.write("[]".toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "push did not end: $out")
        val log = File(home, ".claude-remote/push.log").readText()
        val skipped = "no live session of ours — skip" in log
        assertEquals(!skipped, File(home, ".claude-remote/sessions.json").exists(), "merge xor skip: $log")
        return !skipped
    }

    @Test fun thePushGuardCountsTheBoxsOwnPrefixAndTheDefaultAsFixedStrings() {
        val box = TmuxNameParser.livePrefixes("box")
        assertTrue(merged(box, "claude-box-a", "other"), "a session of the box: merge")
        assertTrue(merged(box, "claude-server-x"), "a session of the default prefix: merge")
        assertTrue(!merged(box, "__anchor__", "other", "claude-test", "claude-boxer"), "none of ours: skip")
        assertTrue(!merged(TmuxNameParser.livePrefixes(null), "claude-box-a"), "the default alone does not count the box (the old guard)")
        // the box `server`: what the guard always did
        assertTrue(merged(TmuxNameParser.livePrefixes("server"), "claude-server-a"))
        assertTrue(!merged(TmuxNameParser.livePrefixes("server"), "claude-box-a", "xclaude-server-a"))
        // a name is never a pattern: `a.b` -> `claude-a_b-` matches no `claude-aXb-`, `*` matches nothing but itself
        assertTrue(merged(TmuxNameParser.livePrefixes("a.b"), "claude-a_b-1"))
        assertTrue(!merged(setOf(TmuxNameParser.prefixFor("a.b")), "claude-aXb-1"))
        assertTrue(!merged(setOf(TmuxNameParser.prefixFor("*")), "claude-x-1", "claude-anything"))
        assertTrue(merged(setOf(TmuxNameParser.prefixFor("*")), "claude-*-1"))
        assertTrue(!merged(setOf(TmuxNameParser.prefixFor("it's")), "claude-its-1"), "a quote in the name stays inside its string")
        assertTrue(merged(setOf(TmuxNameParser.prefixFor("it's")), "claude-it's-1"))
    }

    /**
     * kritika D2 N2, dodatek 3 D6-K: no literal `claude-server-` in the app's code (Kotlin and generated-shell comments
     * aside) outside the place
     * of truth and these named exceptions — each one a contract, not prefix logic:
     *  - `TmuxNameParser.DEFAULT_PREFIX`: the place of truth;
     *  - `QuickAccess.JARVIS_TMUX_NAME` and `WatchJarvis.TMUX_NAME`: Jarvis's own fixed name (Jarvis lives on `server`);
     *  - the `cr-session` bodies (`SessionOpsScript` `valid_new_name` and `cmd_close`, `SessionRestore.RUNNING_SH`):
     *    their bytes are `cr-session`'s SHA (N18), it knows only `server`;
     *  - the generated drift (`SessionPersistenceService`, D6-S's part): its marker-driven shell.
     * Nothing under `tools/jarvis` (Python, N19) is scanned.
     */
    @Test fun noLiteralPrefixOutsideThePlaceOfTruth() {
        val allowed = listOf(
            "src/commonMain/kotlin/com/clauderemote/model/ClaudeSession.kt" to "const val DEFAULT_PREFIX = \"claude-server-\"",
            "src/commonMain/kotlin/com/clauderemote/session/QuickAccess.kt" to "const val JARVIS_TMUX_NAME = \"claude-server-jarvis--jarvis\"",
            "src/commonMain/kotlin/com/clauderemote/session/wear/WatchJarvis.kt" to "private val TMUX_NAME = Regex(\"claude-server-[A-Za-z0-9_-]{1,120}\")",
            "src/commonMain/kotlin/com/clauderemote/session/service/SessionOpsScript.kt" to "valid_new_name() { case \"§1\" in claude-server-*) ;;",
            "src/commonMain/kotlin/com/clauderemote/session/service/SessionOpsScript.kt" to "case \"§N\" in claude-server-*) ;; *) echo REFUSED_NAME; exit 1 ;; esac",
            "src/commonMain/kotlin/com/clauderemote/session/service/SessionRestore.kt" to "if [ -n \"§s\" ]; then case \"§s\" in claude-server-*) printf '%s' \"§s\"; return 0 ;; esac; return 1; fi",
        ) + DRIFT_LINES.map { "src/commonMain/kotlin/com/clauderemote/session/service/SessionPersistenceService.kt" to it }
        val roots = listOf("src/commonMain", "src/androidMain", "src/desktopMain", "../androidApp/src/main", "../desktopApp/src/main", "../wearApp/src/main")
        val found = mutableListOf<Pair<String, String>>()
        for (r in roots) {
            val dir = File(r)
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val rel = f.path.replace(File.separatorChar, '/')
                f.readLines().forEach { line ->
                    val t = line.trim()
                    if ("claude-server-" in t && !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") && !t.startsWith("#")) found += rel to t
                }
            }
        }
        val unexpected = found.filterNot { (f, t) -> allowed.any { (af, at) -> f == af && at in t } }
        assertTrue(unexpected.isEmpty(), "literal claude-server- outside TmuxNameParser.DEFAULT_PREFIX: ${unexpected.joinToString("\n")}")
        val stale = allowed.filterNot { (af, at) -> found.any { (f, t) -> f == af && at in t } }
        assertTrue(stale.isEmpty(), "named exceptions no longer found (drop them): $stale")
    }

    /**
     * Every liveness site the box's name reaches only in code that has no unit seam asks for the box's prefixes, not the
     * default alone: the prune guard of the remote scan (App), the manifest push (both paths) and the orchestrator's
     * peer check before a forget. The reconciler's wiring is behavioural (`TmuxPrefixTest`).
     */
    @Test fun everyLivenessSiteAsksForTheBoxsPrefixes() {
        fun src(p: String) = File(p).readText()
        val app = src("src/commonMain/kotlin/com/clauderemote/ui/App.kt")
        assertTrue("TmuxNameParser.livePrefixes(servers.firstOrNull { it.id == serverId }?.name)" in app &&
            "TmuxNameParser.hasPrefix(it.tmuxSession.name, ours)" in app, "the prune guard counts the box's prefixes")
        val sps = src("src/commonMain/kotlin/com/clauderemote/session/service/SessionPersistenceService.kt")
        assertEquals(2, Regex("""ManifestCommands\.pushMerge\(serverId, payload\.length, livePrefixes\(serverId\)\)""").findAll(sps).count(),
            "both manifest pushes carry the box's prefixes")
        assertTrue("TmuxNameParser.livePrefixes(serverStorage.getServer(serverId)?.name)" in sps, "by the server's name")
        val orch = src("src/commonMain/kotlin/com/clauderemote/session/SessionOrchestrator.kt")
        assertTrue("serverHasOtherLiveSession(sshManager, session.tmuxSessionName, TmuxNameParser.livePrefixes(session.server.name))" in orch,
            "the peer check before a forget")
    }

    private companion object {
        /** The generated drift.sh's own lines with the prefix (D6-S's part; marker-driven, unchanged by D6-K). */
        val DRIFT_LINES = listOf(
            "if [ \"\${'\$'}(tmux -u list-sessions -F '#{session_name}' 2>/dev/null | grep -c '^claude-server-')\" -gt 0 ]; then",
            "case \"\${'\$'}s\" in claude-server-*) ;; *) continue;; esac",
        )
    }
}
