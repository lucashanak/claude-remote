package com.clauderemote.session

import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.testsupport.safeDeleteTree
import org.junit.Assume
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1: the ET bootstrap's install script (`SessionOrchestrator.ETSERVER_INSTALL`, sent as [SessionOrchestrator.etServerInstallCommand])
 * no longer kills every `flock` process of the user older than 30 s — other sessions' `( flock -x 9; … ) 9<>LOCK`
 * waiters (whose body then ran without the lock) and `flock ~/.cache/crit-integration.lock` died with it.
 *
 * SAFETY (kritika D, Hrozba 1): `pgrep` sees every process of the user, and no private HOME or TMUX_TMPDIR narrows
 * it, so the script (old, new or a mutant) runs ONLY in its own PID namespace: `unshare -Urpf --mount-proc`, where it
 * sees nothing but the harness's processes. `systemd-run`, `curl` and `ps` are shims (no scope in the live user
 * systemd, no network; `ps` answers the ages the test chose), `etserver`/`etterminal` are stubs and `etserver.ver` is
 * there, so nothing is downloaded. The harness also refuses to go on unless it is pid 1. Without `unshare` the
 * namespace tests are SKIPPED with a message — never run without it.
 */
class EtServerInstallScriptTest {
    private val root = File(System.getProperty("java.io.tmpdir")).resolve("et-install-${System.nanoTime()}").also { it.mkdirs() }
    private var orchestrator: SessionOrchestrator? = null

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        safeDeleteTree(root)
    }

    private fun orchestrator() = SessionOrchestrator(ServerStorage(FakeKeyValueStore()), TabManager()).also { orchestrator = it }

    // ---- static: what the script holds ----

    @Test fun theInstallScriptHasNoFlockReaperButKeepsTheEtterminalReaperAndItsBoundedLock() {
        val o = orchestrator()
        val script = o.ETSERVER_INSTALL
        assertTrue("pgrep -x flock" !in script, "no reaper of every flock process: $script")
        assertTrue("for p in \$(pgrep -f \"\$CR/etterminal\" 2>/dev/null); do" in script, "the etterminal reaper stays")
        assertTrue("[ \"\${AGE:-0}\" -gt 30 ] 2>/dev/null && kill \"\$p\" 2>/dev/null" in script, "it kills etterminals older than 30 s")
        assertTrue("exec 9>\"\$CR/etserver.start.lock\"" in script && "flock -w 5 9 || { echo ET_LOCK_TIMEOUT; exit 0; }" in script,
            "the start lock is etserver.start.lock with a bounded wait")
        // nothing else is killed: the etterminal reaper and the etserver restarts only
        val kills = script.lines().filter { Regex("""\b(p?kill)\b""").containsMatchIn(it) && !it.trim().startsWith("#") }.map { it.trim() }
        assertEquals(
            listOf(
                "[ \"\${AGE:-0}\" -gt 30 ] 2>/dev/null && kill \"\$p\" 2>/dev/null",
                "pkill -f \"\$CR/etserver\" 2>/dev/null; rm -f \"\$CR/et.sock\"",
                "pkill -f \"\$CR/etserver\" 2>/dev/null; rm -f \"\$CR/et.sock\"",
            ),
            kills, "the only kills of the script",
        )
        assertEquals("timeout 12 sh -c '" + script.replace("'", "'\\''") + "'", o.etServerInstallCommand, "the command sends this script")
    }

    /** D1 (kritika V-D1): the ET bootstrap's exec opens through ChannelOpen — a bare `ch.connect(t)` that times out leaks an sshd slot. */
    @Test fun execCaptureOpensItsChannelThroughChannelOpen() {
        val src = File("src/commonMain/kotlin/com/clauderemote/session/SessionOrchestrator.kt").readText()
        val start = src.indexOf("private suspend fun execCapture(").also { assertTrue(it >= 0, "execCapture not found") }
        val end = Regex("""\n    (?:(?:private|internal|public|override|protected) )*(?:suspend )?(?:fun|val|var) """).find(src, start + 1)!!.range.first
        // code only: a comment may name the bare call it replaced
        val body = src.substring(start, end).lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        assertTrue(Regex("""(?<!ChannelOpen)\.connect\(""").find(body) == null, "no bare .connect( in execCapture:\n$body")
        assertTrue(Regex("""(?<!ChannelOpen)\.disconnect\(""").find(body) == null, "no bare .disconnect( in execCapture:\n$body")
        assertTrue("com.clauderemote.connection.ChannelOpen.connect(ch, 10_000)" in body, "the open goes through ChannelOpen:\n$body")
        assertTrue("com.clauderemote.connection.ChannelOpen.disconnect(ch)" in body, "and so does the close:\n$body")
    }

    // ---- in a PID namespace: what the script does ----

    private data class Run(val out: String, val alive: Set<String>)

    /**
     * Runs [SessionOrchestrator.etServerInstallCommand] as the login shell would (`bash -c`) inside a PID namespace,
     * after starting the processes named in [setup] (bash lines in the harness; each `NAME=$!` is reported as alive or
     * not afterwards). Ages for `ps -o etimes=` come from `$R/age.<pid>`, 100 s when absent.
     */
    private fun runInNamespace(names: List<String>, setup: String): Run {
        Assume.assumeTrue("ET install script test SKIPPED: x86_64 only (the script bails on other arches)",
            System.getProperty("os.arch") in setOf("amd64", "x86_64"))
        val probe = try {
            ProcessBuilder("unshare", "-Urpf", "--mount-proc", "sh", "-c", "[ \$\$ = 1 ]").redirectErrorStream(true).start()
                .let { p -> if (p.waitFor(10, TimeUnit.SECONDS)) p.exitValue() else { p.destroyForcibly(); -1 } }
        } catch (e: java.io.IOException) {
            -1
        }
        if (probe != 0) println("EtServerInstallScriptTest: unshare -Urpf --mount-proc is not available here — the namespace test is SKIPPED, never run without it")
        Assume.assumeTrue("unshare -Urpf --mount-proc unavailable: the ET install script never runs outside its own PID namespace", probe == 0)

        val r = root.absolutePath
        val shims = File(root, "shims").apply { mkdirs() }
        fun script(f: File, body: String) { f.writeText(body); f.setExecutable(true) }
        script(File(shims, "systemd-run"), "#!/bin/sh\necho \"systemd-run \$*\" >> '$r/calls'\nexit 1\n")
        script(File(shims, "curl"), "#!/bin/sh\necho \"curl \$*\" >> '$r/calls'\nexit 1\n")
        script(File(shims, "ps"), "#!/bin/bash\nfor a; do pid=\$a; done\ncat '$r'/age.\"\$pid\" 2>/dev/null || echo 100\n")
        File(root, "cmd").writeText(orchestrator().etServerInstallCommand)
        val harness = File(root, "harness.sh")
        harness.writeText(
            """
            |#!/bin/bash
            |# never outside a PID namespace: there pgrep sees only what this harness started
            |[ "${'$'}${'$'}" = 1 ] || { echo NOT_IN_PID_NAMESPACE; exit 99; }
            |R='$r'
            |export HOME="${'$'}R/home"; CR="${'$'}HOME/.claude-remote"; mkdir -p "${'$'}CR"
            |printf '#!/bin/sh\nsleep 300\n' > "${'$'}CR/etterminal"
            |printf '%s\n' '#!/bin/sh' 'command -v python3 >/dev/null || exec sleep 300' \
            |  'exec python3 -c "import socket,sys,time; s=socket.socket(socket.AF_UNIX); s.bind(sys.argv[1]); s.listen(1); time.sleep(300)" "${'$'}4"' > "${'$'}CR/etserver"
            |chmod +x "${'$'}CR/etterminal" "${'$'}CR/etserver"
            |echo "claude-remote-etserver v7.0.0-1" > "${'$'}CR/etserver.ver"
            |$setup
            |sleep 0.3
            |out=${'$'}(PATH="${'$'}R/shims:${'$'}PATH" bash -c "${'$'}(cat "${'$'}R/cmd")" 2>&1)
            |echo "OUT<<${'$'}out>>"
            |sleep 0.3
            |for n in ${names.joinToString(" ")}; do
            |  eval "p=\${'$'}${'$'}n"
            |  st=${'$'}(awk '{print ${'$'}3}' "/proc/${'$'}p/stat" 2>/dev/null)
            |  if [ -n "${'$'}st" ] && [ "${'$'}st" != Z ]; then echo "ALIVE ${'$'}n"; else echo "GONE ${'$'}n"; fi
            |done
            """.trimMargin() + "\n",
        )
        // the ONLY way this harness is ever started: as pid 1 of a new PID namespace (with its own /proc)
        val p = ProcessBuilder("unshare", "-Urpf", "--mount-proc", "bash", harness.absolutePath).redirectErrorStream(true).start()
        if (!p.waitFor(40, TimeUnit.SECONDS)) { p.destroyForcibly(); error("the harness did not finish in 40 s") }
        val text = p.inputStream.bufferedReader().readText()
        assertTrue("NOT_IN_PID_NAMESPACE" !in text, text)
        val out = Regex("OUT<<(.*)>>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1) ?: error("no output: $text")
        val calls = File(root, "calls").takeIf { it.exists() }?.readText().orEmpty()
        assertTrue("curl" !in calls, "nothing was downloaded: $calls")
        return Run(out, text.lines().filter { it.startsWith("ALIVE ") }.map { it.removePrefix("ALIVE ") }.toSet())
    }

    @Test fun aForeignFlockWaiterOlderThan30sSurvivesTheEtBootstrap() {
        val run = runInNamespace(
            listOf("HOLDER", "WAITER"),
            // another session's lock: a holder, and a waiter `flock -w 300` behind it — both "older than 30 s" (ps shim: 100)
            "L=\"\$R/foreign.lock\"; flock \"\$L\" sleep 300 & HOLDER=\$!; sleep 0.2; flock -w 300 \"\$L\" true & WAITER=\$!",
        )
        assertTrue("ET_READY port=" in run.out, "the bootstrap still brings etserver up: ${run.out}")
        assertEquals(setOf("HOLDER", "WAITER"), run.alive, "foreign flock processes are left alone: ${run.out}")
    }

    @Test fun theEtterminalReaperKillsOnlyAnEtterminalOlderThan30s() {
        val run = runInNamespace(
            listOf("OLD", "YOUNG"),
            "\"\$CR/etterminal\" & OLD=\$!; echo 100 > \"\$R/age.\$OLD\"; \"\$CR/etterminal\" & YOUNG=\$!; echo 5 > \"\$R/age.\$YOUNG\"",
        )
        assertTrue("ET_READY port=" in run.out, run.out)
        assertEquals(setOf("YOUNG"), run.alive, "a wedged etterminal (> 30 s) is reaped, a young one lives: ${run.out}")
    }
}
