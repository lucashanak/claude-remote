package com.clauderemote.session.service

import com.clauderemote.session.ClaudeConfig
import com.clauderemote.testsupport.safeDeleteTree
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kolo 3 (adv N3): the bounded tmux probes of C1 round 2 must answer under any login shell of the server. They kept
 * `timeout 3` in `$CR_T` and ran `$CR_T tmux …`; zsh does not word-split an unquoted variable, so `timeout 3` ran as ONE
 * command name (127) and has-session printed NO for a live session (a ForgetClosedElsewhere of a live session behind it).
 * Kolo 4 (adv N8): has-session prints NO only for tmux's own "no such session / no server"; any other failure (tmux
 * missing, a socket it cannot reach) prints nothing — fail-open.
 *
 * zsh is not on this box. Its rule is reproduced exactly in sh and bash by an empty `IFS` (no field splitting of an
 * unquoted expansion), and zsh itself runs too where it exists. tmux is a stand-in script on a private PATH of the child
 * shell (it never touches a socket); its `has-session -t =<name>` answers like tmux 3.5a for these names: `live` exists;
 * `gone` "can't find session"; `nosrv` "no server running"; `nosock` "error connecting … (No such file or directory)";
 * `toolong` / `eacces` "error connecting … (File name too long / Permission denied)"; `hang` hangs 10 s. `list-clients`
 * prints two clients and `refresh-client` succeeds.
 */
class TmuxProbeShellTest {
    private val dir: File = kotlin.io.path.createTempDirectory("tmuxprobe-shell").toFile()

    @AfterTest fun tearDown() = safeDeleteTree(dir)

    private val fakeBin = File(dir, "bin").apply {
        mkdirs()
        File(this, "tmux").apply {
            writeText(
                "#!/bin/sh\n" +
                    "case \"\$1\" in\n" +
                    "  has-session) case \"\$3\" in\n" +
                    "    =live) exit 0 ;;\n" +
                    "    =nosrv) echo 'no server running on /tmp/tmux-1/default' >&2; exit 1 ;;\n" +
                    "    =nosock) echo 'error connecting to /tmp/tmux-1/default (No such file or directory)' >&2; exit 1 ;;\n" +
                    "    =toolong) echo 'error connecting to /tmp/x (File name too long)' >&2; exit 1 ;;\n" +
                    "    =eacces) echo 'error connecting to /tmp/tmux-1/default (Permission denied)' >&2; exit 1 ;;\n" +
                    "    =hang) sleep 10; echo \"can't find session: hang\" >&2; exit 1 ;;\n" +
                    "    =livesrv) echo \"error connecting to \$LIVE_SOCK (No such file or directory)\" >&2; exit 1 ;;\n" +
                    "    =livestale) echo \"no server running on \$LIVE_SOCK\" >&2; exit 1 ;;\n" +
                    "    *) echo \"can't find session: \${3#=}\" >&2; exit 1 ;;\n" +
                    "  esac ;;\n" +
                    "  list-clients) printf 'c1\\nc2\\n'; exit 0 ;;\n" +
                    "  refresh-client) exit 0 ;;\n" +
                    "esac\nexit 2\n"
            )
            setExecutable(true)
        }
    }

    /** Coreutils WITHOUT `timeout` (copies, never links out of the temp root): the N7-2 path of a server without it. */
    private val noTimeoutBin = File(dir, "core").apply {
        mkdirs()
        for (tool in listOf("mktemp", "grep", "rm", "sleep")) {
            val src = listOf("/usr/bin/$tool", "/bin/$tool").map(::File).first { it.canExecute() }
            src.copyTo(File(this, tool)).setExecutable(true)
        }
    }

    private fun shells(): List<List<String>> {
        val found = mutableListOf(listOf("/bin/sh", "-c"), listOf("/bin/bash", "-c"))
        listOf("/bin/zsh", "/usr/bin/zsh").firstOrNull { File(it).canExecute() }?.let { found += listOf(it, "-c") }
        return found.filter { File(it[0]).canExecute() }
    }

    /**
     * [cmd] under [shell] with PATH = the stand-in tmux first, then [path] ([zshSplitting] = IFS empty). [withTmux] false:
     * PATH = the copied coreutils only — no tmux at all (never the real one: a test must not reach a tmux server).
     */
    private fun run(shell: List<String>, cmd: String, path: String, zshSplitting: Boolean, withTmux: Boolean = true): String {
        val script = (if (zshSplitting && !shell[0].endsWith("zsh")) "IFS=; " else "") + cmd
        val p = ProcessBuilder(shell + script).redirectErrorStream(true).apply {
            environment()["PATH"] = if (withTmux) "${fakeBin.path}:$path" else noTimeoutBin.path
            environment()["TMPDIR"] = dir.path
            environment()["LIVE_SOCK"] = liveSock.path
        }.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "the probe ends")
        return out.trim()
    }

    private val sys = "/usr/bin:/bin"

    /** A path a server listens on (a unix socket of this test), as a tmux server's stays in /proc/net/unix without its file. */
    private val liveSock = File(dir, "live.sock")

    /**
     * Kolo 6 (adv H1; reverts kolo 5's listener check): "no socket file" and "no server running" read NO also while a
     * server still listens on the path (the 2026-11-02 shape) — the Rebuild that follows heals the socket through
     * `tmux-sockheal.sh` and attaches the existing session; fail-open there led to a plain attach that failed and left a
     * bare login shell under an ACTIVE tab.
     */
    @Test fun aSocketPathThatStillHasAListenerReadsNoSoTheRebuildHealsIt() {
        val server = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)
        try {
            server.bind(java.net.UnixDomainSocketAddress.of(liveSock.toPath()))
            assertTrue(liveSock.delete(), "the socket file is gone, the listener stays (the 2026-11-02 shape)")
            for (shell in shells()) for (zsh in listOf(false, true)) {
                val label = "${shell[0]} zsh=$zsh"
                assertEquals("NO", run(shell, TmuxProbes.hasSessionCmd("livesrv"), sys, zsh), "no socket file, live listener: NO, $label")
                assertEquals("NO", run(shell, TmuxProbes.hasSessionCmd("livestale"), sys, zsh), "no server running there: NO, $label")
            }
        } finally {
            server.close()
        }
    }

    @Test fun theHasSessionProbeAnswersUnderEveryShellWithAndWithoutTimeout() {
        for (shell in shells()) for (zsh in listOf(false, true)) for (path in listOf(sys, noTimeoutBin.path)) {
            val label = "${shell[0]}${if (zsh) " (no word splitting, as zsh)" else ""} PATH=$path"
            assertEquals("YES", run(shell, TmuxProbes.hasSessionCmd("live"), path, zsh), "a live session: $label")
            assertEquals("NO", run(shell, TmuxProbes.hasSessionCmd("gone"), path, zsh), "a gone session: $label")
        }
    }

    /** Kolo 4 (adv N8, probe S4): NO only when tmux says the session or its server is not there. */
    @Test fun onlyTmuxsOwnNoSessionOrNoServerIsNoAnyOtherFailureIsFailOpen() {
        for (shell in shells()) for (zsh in listOf(false, true)) {
            val label = "${shell[0]} zsh=$zsh"
            for (name in listOf("nosrv", "nosock")) assertEquals("NO", run(shell, TmuxProbes.hasSessionCmd(name), sys, zsh), "$name: $label")
            for (name in listOf("toolong", "eacces")) assertEquals("", run(shell, TmuxProbes.hasSessionCmd(name), sys, zsh), "$name fails open: $label")
            assertEquals("", run(shell, TmuxProbes.hasSessionCmd("gone"), sys, zsh, withTmux = false), "no tmux at all (127) fails open: $label")
        }
        assertTrue(dir.list()!!.none { it.startsWith("tmp.") }, "the probe's temp file is removed: ${dir.list()!!.toList()}")
    }

    /** Kolo 4 (Z2, by behaviour): a hung tmux is cut at 3 s where `timeout` exists and reads as alive (nothing printed). */
    @Test fun aHungTmuxIsCutShortAndReadsAsAlive() {
        for (zsh in listOf(false, true)) {
            val t0 = System.nanoTime()
            val out = run(listOf("/bin/sh", "-c"), TmuxProbes.hasSessionCmd("hang"), sys, zsh)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals("", out, "cut short (124): fail-open, zsh=$zsh")
            assertTrue(ms in 2_500..6_000, "bounded by timeout 3: $ms ms, zsh=$zsh")
        }
    }

    @Test fun theRefreshKickRefreshesEveryClientUnderEveryShell() {
        for (shell in shells()) for (zsh in listOf(false, true)) {
            assertEquals("OK\nOK", run(shell, TmuxProbes.refreshCmd("live"), sys, zsh), "${shell[0]} zsh=$zsh")
        }
        assertTrue(dir.list()!!.none { it.startsWith("tmp.") }, "the temp file of the kick is removed: ${dir.list()!!.toList()}")
    }

    /**
     * Every command the batch builds for a server shell: no `$VARIABLE` in a command's position (after `;`, `&&`, `||`,
     * `|`, `{`, `(`, `then`, `else`, `do` or at the start) — the exec's login shell may be zsh.
     */
    @Test fun noGeneratedServerCommandRunsAVariableAsACommand() {
        val commands = mapOf(
            "hasSession" to TmuxProbes.hasSessionCmd("x"),
            "refresh" to TmuxProbes.refreshCmd("x"),
            "cleanup" to ClaudeConfig.buildOwnSessionCleanupCommand("claude-x", "N0nce"),
            "create" to ClaudeConfig.buildFreshCreateCommand(
                tmuxSessionName = "claude-x", folder = "/w", mode = com.clauderemote.model.ClaudeMode.NORMAL,
                model = com.clauderemote.model.ClaudeModel.DEFAULT, claudeSessionId = "0f6c2a4e-1b2c-4d5e-8f90-123456789abc",
                nonce = "N0nce",
            ),
        )
        val commandPosition = Regex("""(?:^|[;&|{(]|\bthen\b|\belse\b|\bdo\b)\s*\$\{?[A-Za-z_]""")
        for ((what, cmd) in commands) {
            assertTrue(commandPosition.find(cmd) == null, "$what runs a variable as a command at `${commandPosition.find(cmd)?.value}`: $cmd")
        }
        assertTrue(commandPosition.containsMatchIn("CR_T='timeout 3'; \$CR_T tmux has-session"), "the check sees the old form")
    }
}
