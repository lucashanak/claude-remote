package com.clauderemote.integration

import com.clauderemote.model.AuthMethod
import com.clauderemote.model.SshServer
import com.clauderemote.storage.KeyValueStore
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Real-infrastructure harness for the integration lane: an actual OpenSSH sshd
 * on loopback, an actual (private) tmux server, and a sandboxed `$HOME` — so the
 * production connection/session/restore code runs against real infrastructure
 * instead of mocks.
 *
 * ## The sandbox boundary: sshd `ForceCommand`
 *
 * The production code under test builds its own shell commands and opens its own
 * exec channels; it has no seam for "use this HOME" or "use this tmux socket",
 * and we are not allowed to add one. Instead the harness puts the sandbox in the
 * *server*: sshd_config carries `ForceCommand <root>/wrap.sh`, and wrap.sh
 * rewrites the environment of EVERY command this sshd ever runs:
 *
 *   - `HOME`         -> [home] (so `$HOME/.claude-remote`, `$HOME/.claude` etc.
 *                      can never touch the developer's real ones)
 *   - `TMUX_TMPDIR`  -> [tmuxTmpDir] (private tmux server)
 *   - `TMUX`/`TMUX_PANE` -> UNSET (see the safety note below)
 *   - `PATH`         -> [shims] first (see [writeShims])
 *
 * Because it is enforced server-side, unmodified production code is sandboxed by
 * construction. The cost: the sftp subsystem is also force-commanded and so
 * unusable — harmless here, because [com.clauderemote.connection.SshManager.uploadFile]
 * deliberately uses an `exec` channel + `cat` (not SFTP) to survive the
 * Cloudflare WebSocket tunnel.
 *
 * ## HARD SAFETY INVARIANT (read before touching anything tmux)
 *
 * The dev box this was written on runs ~23 LIVE Claude tmux sessions on the
 * DEFAULT socket, and the test JVM itself is started from inside one of them, so
 * it inherits `TMUX`. A set `TMUX` env var **overrides** `TMUX_TMPDIR`, so a
 * careless `tmux kill-server` would destroy real, unrecoverable work. Therefore:
 *
 *   - every child process spawned here has `TMUX`/`TMUX_PANE` REMOVED, and
 *   - every tmux invocation additionally passes an explicit `-S <private socket>`,
 *   - `kill-server` is only ever issued through [tmuxKillServer], which asserts
 *     the socket path lives under this fixture's temp root first,
 *   - `detach-client -a` is never used anywhere.
 *
 * ## Why /tmp and not the scratchpad
 *
 * Unix socket paths are capped at ~104 bytes; the session scratchpad path alone
 * blows that and tmux fails with "File name too long". Hence `mktemp -d
 * /tmp/crit.XXXXXX` (short by design), removed in [close].
 */
internal class SshdFixture private constructor(
    /** Short-by-necessity temp root; everything the fixture owns lives under it. */
    val root: File,
    val port: Int,
    val username: String,
    val privateKey: String,
    private val wrongPrivateKey: String,
) : AutoCloseable {

    /** Sandbox `$HOME` for every remote command (enforced by wrap.sh). */
    val home: File = File(root, "home")

    /** `TMUX_TMPDIR` for the private tmux server. */
    val tmuxTmpDir: File = File(root, "sock")

    /** The private tmux socket. NEVER the user's default socket. */
    val tmuxSocket: File = File(File(tmuxTmpDir, "tmux-${posixUid()}"), "default")

    /** PATH-prepended stand-ins for `claude`, `systemctl`, `loginctl`, `systemd-run`. */
    val shims: File = File(root, "shims")

    /** Where sshd/tmux/restore diagnostics are copied for CI upload. */
    val logDir: File = File(
        System.getProperty("clauderemote.integration.logDir")
            ?: File("build/integration-logs").absolutePath,
        root.name,
    )

    /** The production model pointing at this sshd, authenticating with a real key. */
    val server: SshServer = SshServer(
        id = "integration-server",
        name = "integration",
        host = "127.0.0.1",
        port = port,
        username = username,
        authMethod = AuthMethod.KEY,
        privateKey = privateKey,
    )

    /** Same server, but with a key that is NOT in authorized_keys. */
    val serverWithWrongKey: SshServer = server.copy(privateKey = wrongPrivateKey)

    @Volatile private var closed = false
    private val shutdownHook = Thread { closeQuietly() }

    /**
     * PIDs this fixture is responsible for reaping — sshd, plus any daemon a test
     * started through it (etserver, mosh-server) via [trackPid].
     *
     * Deliberately a PID list and NOT a `pkill -f <pattern>` sweep. `pkill -f`
     * matches the whole command line of every process on the box as an EXTENDED
     * REGEX, so a pattern like the fixture root (whose `.` matches any character)
     * or a shared shim name will happily kill a concurrent test run, a developer's
     * shell, or an editor that merely has the path open. Nothing here kills a
     * process it did not start.
     */
    private val trackedPids = java.util.Collections.synchronizedList(mutableListOf<Long>())

    init {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    /** Register a daemon PID (etserver, mosh-server, …) for teardown. */
    fun trackPid(pid: Long) {
        trackedPids.add(pid)
    }

    // ---------------------------------------------------------------- processes

    /**
     * Run a local command with `TMUX`/`TMUX_PANE` stripped (see the safety note
     * on the class) and the sandbox `HOME`/`TMUX_TMPDIR` exported.
     */
    fun run(vararg command: String, timeoutMs: Long = 30_000): ProcResult {
        // Output goes to a FILE, never a pipe. `tmux new-session -d` daemonizes a
        // server that inherits stdout, so a pipe never reaches EOF and reading it
        // to completion hangs forever even though the client already exited.
        // Same trap for any other daemonizing child (etserver, mosh-server).
        val sink = File.createTempFile("run", ".out", root)
        try {
            // Resolve bare binary names ourselves (PATH + known bin dirs) rather
            // than relying on the JVM's inherited PATH, which the Gradle daemon may
            // have started with far leaner than an interactive shell's.
            val argv = command.toMutableList()
            if (!argv[0].contains('/')) argv[0] = onPath(argv[0])?.absolutePath ?: argv[0]
            val pb = ProcessBuilder(argv)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(sink))
            pb.environment().apply {
                remove("TMUX")
                remove("TMUX_PANE")
                put("HOME", home.absolutePath)
                put("TMUX_TMPDIR", tmuxTmpDir.absolutePath)
            }
            val p = pb.start()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return ProcResult(-1, sink.readTextOrEmpty())
            }
            return ProcResult(p.exitValue(), sink.readTextOrEmpty())
        } finally {
            sink.delete()
        }
    }

    /**
     * Local tmux against the PRIVATE socket only. The explicit `-S` is redundant
     * with `TMUX_TMPDIR` on purpose — two independent guards against ever
     * addressing the user's real server.
     */
    fun tmux(vararg args: String, timeoutMs: Long = 30_000): ProcResult =
        run("tmux", "-S", tmuxSocket.absolutePath, *args, timeoutMs = timeoutMs)

    /** Session names currently live on the private tmux server (empty if no server). */
    fun tmuxSessions(): List<String> {
        val r = tmux("list-sessions", "-F", "#{session_name}")
        if (r.exit != 0) return emptyList()
        return r.out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Pre-start the private tmux server with a keepalive session.
     *
     * restore.sh's FIRST branch is: if no tmux server is reachable, try to create
     * one via `systemd-run --user --scope`; if that fails it logs "no tmux server
     * and could not create one in a transient scope — skipping restore" and exits
     * 0 having done NOTHING. A restore test that skips this pre-start can pass
     * vacuously, so the tests also assert that message is absent from the log.
     */
    fun startTmuxServer() {
        if (tmuxSessions().isNotEmpty()) return
        // tmux's exit code is NOT trustworthy here (it exits 0 even after
        // "error creating <socket>"), so the poll is the real check and its output
        // is what tells you why.
        val r = tmux("new-session", "-d", "-s", KEEPALIVE_SESSION, "sleep", "86400")
        waitUntil(10_000, "private tmux server on $tmuxSocket (tmux said: ${r.out.trim()})") {
            tmuxSessions().contains(KEEPALIVE_SESSION)
        }
    }

    /**
     * `session_created` for [name], or null when absent. Used to prove a session
     * was left alone rather than killed and recreated.
     *
     * Via `list-sessions`, not `display-message -t`: the latter takes a
     * target-PANE, for which the `=name` exact-session syntax does not parse.
     */
    fun sessionCreatedAt(name: String): String? {
        val r = tmux("list-sessions", "-F", "#{session_name}\t#{session_created}")
        if (r.exit != 0) return null
        return r.out.lineSequence()
            .map { it.trim().split("\t") }
            .firstOrNull { it.size == 2 && it[0] == name }
            ?.get(1)
    }

    // ------------------------------------------------------------------ ssh/jsch

    /** Open a real jsch session to this sshd (key auth), independent of SshManager. */
    fun openRawSession(timeoutMs: Int = 15_000): Session {
        val jsch = com.jcraft.jsch.JSch()
        jsch.addIdentity("integration", privateKey.toByteArray(), null, null)
        val s = jsch.getSession(username, "127.0.0.1", port)
        s.setConfig("StrictHostKeyChecking", "no")
        s.timeout = timeoutMs
        s.connect(timeoutMs)
        return s
    }

    // --------------------------------------------------------------- diagnostics

    /** Copy sshd/restore/drift logs into [logDir] so CI can upload them. */
    fun collectLogs() {
        runCatching {
            logDir.mkdirs()
            val wanted = listOf(
                File(root, "sshd.log"),
                File(root, "remote-commands.log"),
                File(root, "etserver.log"),
                File(home, ".claude-remote/restore.log"),
                File(home, ".claude-remote/drift.log"),
                File(home, ".claude-remote/install.log"),
            )
            for (f in wanted) if (f.isFile) f.copyTo(File(logDir, f.name), overwrite = true)
        }
    }

    /** restore.sh `exec >> restore.log`s everything — it has NO stdout at all. */
    fun restoreLog(): String = File(home, ".claude-remote/restore.log").let {
        if (it.isFile) it.readText() else "<no restore.log at ${it.absolutePath}>"
    }

    fun driftLog(): String = File(home, ".claude-remote/drift.log").let {
        if (it.isFile) it.readText() else "<no drift.log at ${it.absolutePath}>"
    }

    // -------------------------------------------------------------------- teardown

    /**
     * Kill ONLY the private tmux server, after proving the socket is ours.
     *
     * Pane PIDs are collected FIRST so the panes remain reapable by PID if the
     * server is already gone. `kill-server` itself takes the whole pane process
     * tree down with it — verified: two sessions each running the `claude` shim,
     * 2 shim processes alive before, 0 after — so the sweep below is a backstop,
     * not the mechanism.
     */
    private fun tmuxKillServer() {
        // The invariant, enforced rather than commented: refuse to issue
        // kill-server unless the socket demonstrably belongs to this fixture.
        val sock = tmuxSocket.absolutePath
        require(sock.startsWith(root.absolutePath + File.separator)) {
            "REFUSING tmux kill-server: $sock is not inside ${root.absolutePath}"
        }
        if (!tmuxSocket.exists()) return
        val panes = tmux("list-panes", "-a", "-F", "#{pane_pid}", timeoutMs = 10_000)
        if (panes.exit == 0) {
            panes.out.lineSequence()
                .mapNotNull { it.trim().toLongOrNull() }
                .forEach { trackPid(it) }
        }
        tmux("kill-server", timeoutMs = 10_000)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        collectLogs()
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { tmuxKillServer() }
        runCatching {
            File(root, "sshd.pid").readTextOrEmpty().trim().toLongOrNull()?.let { trackPid(it) }
        }
        // Every survivor is killed by PID, and only if it is safe to kill (see
        // [killTracked]). No pattern matching, so nothing this fixture did not
        // start can be caught in the blast radius.
        runCatching { killTracked() }
        runCatching { root.deleteRecursively() }
    }

    /**
     * SIGTERM each tracked PID that is still alive, skipping anything unsafe:
     * this JVM, any of its ancestors, and PIDs already gone. Descendants of a
     * dead pane may have been reparented to init, so descendancy is NOT required
     * — only that we started it (it is in [trackedPids]) and that killing it
     * cannot take us or our parents down.
     */
    private fun killTracked() {
        val self = ProcessHandle.current().pid()
        val ancestors = buildSet {
            var h: ProcessHandle? = ProcessHandle.current().parent().orElse(null)
            while (h != null) {
                add(h.pid())
                h = h.parent().orElse(null)
            }
        }
        val snapshot = synchronized(trackedPids) { trackedPids.toList() }
        for (pid in snapshot.distinct()) {
            if (pid == self || pid in ancestors || pid <= 1L) continue
            val handle = ProcessHandle.of(pid).orElse(null) ?: continue
            if (!handle.isAlive) continue
            runCatching { handle.destroy() }
        }
    }

    // -------------------------------------------------------------------- startup

    companion object {
        const val KEEPALIVE_SESSION = "__intprobe__"

        /**
         * argv[0] the `claude` stand-in re-execs itself under, so a pane's shim is
         * identifiable in `ps` while diagnosing. Teardown kills by PID, never by
         * matching this string.
         */
        const val CLAUDE_SHIM_ARGV0 = "claude-remote-integration-shim"

        /** Directories always searched for the harness's binaries, on top of PATH. */
        private val FALLBACK_BIN_DIRS =
            listOf("/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin")

        /** Everything the harness cannot run without. */
        private val REQUIRED_BINS = listOf("tmux", "jq", "ssh-keygen", "flock", "bash", "mktemp")

        /**
         * Null when the box has everything; otherwise a message naming exactly what
         * is missing. Never let a missing binary turn into a silent skip — see the
         * `isSupported` note in the test classes.
         */
        val unsupportedReason: String? by lazy {
            val missing = buildList {
                if (!File("/usr/sbin/sshd").canExecute()) add("/usr/sbin/sshd (openssh-server)")
                REQUIRED_BINS.filter { onPath(it) == null }.forEach { add(it) }
            }
            if (missing.isEmpty()) null
            else "missing: ${missing.joinToString(", ")} (PATH=${System.getenv("PATH")})"
        }

        val isSupported: Boolean get() = unsupportedReason == null

        /**
         * Resolve [bin] against PATH, then against [FALLBACK_BIN_DIRS]. The fallback
         * matters: the Gradle daemon can be started with a leaner PATH than an
         * interactive shell (tmux lives in /usr/local/bin on this box), and a
         * PATH-only lookup would then declare a perfectly capable machine
         * unsupported.
         */
        fun onPath(bin: String): File? {
            val fromPath = (System.getenv("PATH") ?: "").split(File.pathSeparator)
            return (fromPath + FALLBACK_BIN_DIRS)
                .asSequence()
                .filter { it.isNotBlank() }
                .map { File(it, bin) }
                .firstOrNull { it.canExecute() }
        }

        fun posixUid(): String =
            runCatching {
                val p = ProcessBuilder("id", "-u").start()
                p.inputStream.bufferedReader().readText().trim()
            }.getOrDefault("1000")

        /**
         * Remove fixture roots left behind by a KILLED JVM. The shutdown hook only
         * runs on an orderly exit, so a SIGKILL/SIGTERM'd test worker (a cancelled
         * build, an OOM) leaks its `/tmp/crit.*` root and possibly a tmux server.
         *
         * Conservative by construction: a root is touched only if its path really
         * matches `/tmp/crit.` + 6 alphanumerics, its recorded sshd is NOT alive,
         * and it has not been modified in the last hour — so a concurrent run's
         * fixture is never disturbed.
         */
        fun sweepStaleRoots(maxAgeMs: Long = 60 * 60 * 1000L) {
            val pattern = Regex("""^/tmp/crit\.[A-Za-z0-9]{6}$""")
            val now = System.currentTimeMillis()
            val candidates = File("/tmp").listFiles { f: File ->
                f.isDirectory && pattern.matches(f.absolutePath)
            } ?: return
            for (dir in candidates) {
                // Re-check the path immediately before any recursive delete.
                if (!pattern.matches(dir.absolutePath)) continue
                val sshdAlive = File(dir, "sshd.pid").readTextOrEmpty().trim().toLongOrNull()
                    ?.let { ProcessHandle.of(it).map { h -> h.isAlive }.orElse(false) } ?: false
                if (sshdAlive) continue
                val newest = dir.walkTopDown().maxOfOrNull { it.lastModified() } ?: dir.lastModified()
                if (now - newest < maxAgeMs) continue
                // A leaked root may still own a live tmux server on its private
                // socket. Kill it through that socket only, never the default one.
                val sock = File(File(dir, "sock/tmux-${posixUid()}"), "default")
                if (sock.exists() && sock.absolutePath.startsWith(dir.absolutePath + File.separator)) {
                    runCatching {
                        ProcessBuilder("tmux", "-S", sock.absolutePath, "kill-server")
                            .also { pb -> pb.environment().remove("TMUX"); pb.environment().remove("TMUX_PANE") }
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start().waitFor(10, TimeUnit.SECONDS)
                    }
                }
                runCatching { dir.deleteRecursively() }
                println("SshdFixture: swept stale fixture root ${dir.absolutePath}")
            }
        }

        /**
         * Start a fresh sandboxed sshd. Retries port selection: `ServerSocket(0)`
         * has an unavoidable bind race, so losing it once must not fail the suite.
         */
        fun start(): SshdFixture {
            check(isSupported) { "SshdFixture.start() on an unsupported box — $unsupportedReason" }
            runCatching { sweepStaleRoots() }
            var lastError: Throwable? = null
            repeat(3) {
                val root = mktempShortDir()
                try {
                    return build(root)
                } catch (e: Throwable) {
                    lastError = e
                    runCatching {
                        File(root, "sshd.pid").takeIf { it.isFile }?.readText()?.trim()
                            ?.toIntOrNull()?.let { pid ->
                                ProcessBuilder("kill", pid.toString()).start().waitFor()
                            }
                    }
                    runCatching { root.deleteRecursively() }
                }
            }
            throw IllegalStateException("could not start sandboxed sshd", lastError)
        }

        private fun mktempShortDir(): File {
            val p = ProcessBuilder("mktemp", "-d", "/tmp/crit.XXXXXX")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            check(p.waitFor() == 0 && out.startsWith("/tmp/crit.")) { "mktemp failed: $out" }
            return File(out).also { it.setReadable(true, true); it.setExecutable(true, true) }
        }

        private fun build(root: File): SshdFixture {
            val user = System.getProperty("user.name")
            root.chmod("700")

            val hostKey = File(root, "hostkey")
            val userKey = File(root, "id")
            keygen(hostKey)
            keygen(userKey)
            val authorized = File(root, "authorized_keys")
            File("${userKey.absolutePath}.pub").copyTo(authorized, overwrite = true)
            authorized.chmod("600")
            hostKey.chmod("600")

            val home = File(root, "home").apply { mkdirs() }
            val sock = File(root, "sock").apply { mkdirs() }
            // `tmux -S <path>` does NOT create intermediate directories — and it
            // reports the failure on stderr while still exiting 0, so the only
            // symptom is a server that silently never appears. TMUX_TMPDIR would
            // have created `tmux-<uid>/` itself; the explicit -S (kept as a second
            // guard against ever addressing the user's real socket) does not.
            File(sock, "tmux-${posixUid()}").apply { mkdirs() }.chmod("700")
            val shims = File(root, "shims").apply { mkdirs() }
            writeShims(shims)

            // wrap.sh IS the sandbox — see the class doc. Every command through
            // this sshd, including ones production code builds itself, runs here.
            val wrap = File(root, "wrap.sh")
            wrap.writeText(
                """
                #!/usr/bin/env bash
                export HOME='${home.absolutePath}'
                export TMUX_TMPDIR='${sock.absolutePath}'
                # See SshdFixture's safety note: a set TMUX overrides TMUX_TMPDIR and
                # would point tmux at the user's REAL server full of live work.
                unset TMUX TMUX_PANE
                export PATH='${shims.absolutePath}':/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
                # mosh-server refuses to start unless the locale is UTF-8, and an
                # sshd child inherits no LANG. C.UTF-8 keeps C collation/sorting
                # (so sed/sort/jq behave exactly as before) and only supplies the
                # charset mosh checks for.
                export LANG=C.UTF-8
                cd "${'$'}HOME" || exit 1
                printf '%s %s\n' "${'$'}(date -u +%FT%TZ)" "${'$'}{SSH_ORIGINAL_COMMAND:-<shell>}" \
                    >> '${root.absolutePath}/remote-commands.log' 2>/dev/null
                if [ -n "${'$'}{SSH_ORIGINAL_COMMAND:-}" ]; then
                    exec bash -c "${'$'}SSH_ORIGINAL_COMMAND"
                fi
                exec bash --noprofile --norc -i

                """.trimIndent(),
            )
            wrap.chmod("755")

            val port = freePort()
            val config = File(root, "sshd_config")
            config.writeText(
                """
                Port $port
                ListenAddress 127.0.0.1
                HostKey ${hostKey.absolutePath}
                AuthorizedKeysFile ${authorized.absolutePath}
                PidFile ${root.absolutePath}/sshd.pid
                StrictModes no
                UsePAM no
                PasswordAuthentication no
                PubkeyAuthentication yes
                ForceCommand ${wrap.absolutePath}
                Subsystem sftp internal-sftp
                LogLevel VERBOSE

                """.trimIndent(),
            )

            val sshdLog = File(root, "sshd.log")
            // sshd inherits this JVM's env, which on the dev box includes TMUX —
            // and every session it spawns would inherit it too. Strip it at the
            // daemon, not just in wrap.sh (defence in depth).
            val startupSink = File(root, "sshd.startup")
            val pb = ProcessBuilder(
                "env", "-u", "TMUX", "-u", "TMUX_PANE",
                "/usr/sbin/sshd", "-f", config.absolutePath, "-E", sshdLog.absolutePath,
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.to(startupSink))
            val proc = pb.start()
            check(proc.waitFor(20, TimeUnit.SECONDS)) { "sshd did not daemonize" }
            val startupOut = startupSink.readTextOrEmpty()
            check(proc.exitValue() == 0) {
                "sshd failed to start (exit ${proc.exitValue()}): $startupOut\n" +
                    (if (sshdLog.isFile) sshdLog.readText() else "")
            }

            waitUntil(10_000, "sshd to listen on 127.0.0.1:$port") {
                runCatching {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500); true }
                }.getOrDefault(false)
            }

            val wrongKey = File(root, "wrong")
            keygen(wrongKey)

            return SshdFixture(
                root = root,
                port = port,
                username = user,
                privateKey = userKey.readText(),
                wrongPrivateKey = wrongKey.readText(),
            )
        }

        /**
         * PATH stand-ins. Each one exists so the test exercises OUR logic instead
         * of an external dependency we must not touch:
         *
         *  - `claude`: must NEVER really run — it needs OAuth and would burn the
         *    user's capped subscription credits. The stand-in just parks forever
         *    so restore.sh's `new-session ... claude ...` leaves a live pane.
         *  - `systemctl` / `loginctl`: the install command runs `systemctl --user
         *    enable`, `daemon-reload` and `loginctl enable-linger $USER` — real
         *    calls would mutate the developer's actual systemd user instance and
         *    linger state. `is-system-running` still answers "running" because
         *    drift.sh's shutdown guard reads it (an empty answer also passes the
         *    guard; the shim just makes it deterministic across boxes).
         *  - `systemd-run`: drift.sh's self-heal runs restore.sh via
         *    `systemd-run --user --scope`, swallowing failure with `|| true`. On a
         *    CI runner (no user session bus) that silently no-ops and self-heal
         *    would never be observed. The shim drops the systemd flags and execs
         *    the payload directly, which is exactly the fallback branch drift.sh
         *    itself uses when systemd-run is absent.
         */
        private fun writeShims(shims: File) {
            File(shims, "claude").writeText(
                """
                #!/usr/bin/env bash
                # Stand-in for the Claude Code CLI. Runs OAuth-free and free of charge.
                exec -a $CLAUDE_SHIM_ARGV0 sleep 86400

                """.trimIndent(),
            )
            File(shims, "systemctl").writeText(
                """
                #!/usr/bin/env bash
                case "${'$'}*" in *is-system-running*) echo running;; esac
                exit 0

                """.trimIndent(),
            )
            File(shims, "loginctl").writeText(
                """
                #!/usr/bin/env bash
                case "${'$'}*" in *Linger*) echo no;; esac
                exit 0

                """.trimIndent(),
            )
            File(shims, "systemd-run").writeText(
                """
                #!/usr/bin/env bash
                while [ ${'$'}# -gt 0 ]; do case "${'$'}1" in --*) shift;; *) break;; esac; done
                exec "${'$'}@"

                """.trimIndent(),
            )
            shims.listFiles()?.forEach { it.chmod("755") }
        }

        private fun keygen(target: File) {
            val p = ProcessBuilder(
                "ssh-keygen", "-q", "-t", "ed25519", "-f", target.absolutePath, "-N", "",
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "ssh-keygen failed: $out" }
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        private fun File.chmod(mode: String) {
            ProcessBuilder("chmod", mode, absolutePath).start().waitFor()
        }
    }
}

internal data class ProcResult(val exit: Int, val out: String)

internal fun File.readTextOrEmpty(): String = if (isFile) runCatching { readText() }.getOrDefault("") else ""

/** In-memory [KeyValueStore] — the real desktop one writes to the user's home. */
internal class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun getString(key: String, default: String): String = map[key] ?: default
    override fun putString(key: String, value: String) { map[key] = value }
    override fun putStringSync(key: String, value: String) { map[key] = value }
    override fun getInt(key: String, default: Int): Int = map[key]?.toIntOrNull() ?: default
    override fun putInt(key: String, value: Int) { map[key] = value.toString() }
    override fun getBoolean(key: String, default: Boolean): Boolean =
        map[key]?.toBooleanStrictOrNull() ?: default
    override fun putBoolean(key: String, value: Boolean) { map[key] = value.toString() }
}

/**
 * Poll [condition] until true or [timeoutMs] elapses, then fail with [what].
 *
 * Real sshd/tmux are the flakiness risk in this lane, so there is not one bare
 * `Thread.sleep` in it: every wait is a bounded poll that fails with a message
 * naming what never happened.
 */
internal fun waitUntil(
    timeoutMs: Long,
    what: String,
    stepMs: Long = 100,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var last: Throwable? = null
    while (System.currentTimeMillis() < deadline) {
        val ok = try {
            condition()
        } catch (e: Throwable) {
            last = e
            false
        }
        if (ok) return
        Thread.sleep(stepMs)
    }
    throw AssertionError(
        "timed out after ${timeoutMs}ms waiting for $what" +
            (last?.let { " (last error: $it)" } ?: ""),
    )
}

/**
 * Tear an [com.clauderemote.connection.SshManager] down without wedging the suite.
 *
 * FINDING, not a workaround for a test bug: `SshManager.disconnect()` does
 * `readJob.cancelAndJoin()` BEFORE `channel.disconnect()`, and that read loop is
 * parked in `inputStream.read()` — a blocking, NON-cancellable call on jsch's
 * piped stream. So on a shell with nothing to say, `disconnect()` never returns;
 * it is unblocked only by bytes arriving or by the transport dying. Real sessions
 * mask this because an attached claude pane emits output constantly (spinner), so
 * a byte is always moments away.
 *
 * Closing the raw jsch session first hands the pipe an EOF, after which the join
 * completes at once. The bound is belt-and-braces so a wedged teardown can never
 * take the rest of the suite with it.
 */
internal fun com.clauderemote.connection.SshManager.shutdownForTest() {
    runCatching { getSession()?.disconnect() }
    kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeoutOrNull(15_000) { runCatching { disconnect() } }
    }
}

internal data class ExecResult(val out: String, val exit: Int)

/**
 * Run [command] on a live jsch session and capture combined output + exit status.
 * Bounded: never blocks past [timeoutMs].
 */
internal fun Session.execCapture(
    command: String,
    timeoutMs: Long = 30_000,
    pty: Boolean = false,
    stdin: ByteArray? = null,
): ExecResult {
    val ch = openChannel("exec") as ChannelExec
    ch.setCommand(command)
    ch.setPty(pty)
    if (stdin == null) ch.inputStream = null
    val out = ch.inputStream
    val err = ch.errStream
    val os = if (stdin != null) ch.outputStream else null
    // Drain both streams concurrently: reading them in sequence deadlocks if the
    // second one fills its window while we are still blocked on the first.
    val stdoutBuf = StringBuilder()
    val stderrBuf = StringBuilder()
    lateinit var readers: List<Thread>
    try {
        ch.connect(10_000)
        readers = listOf(out to stdoutBuf, err to stderrBuf).map { (stream, sink) ->
            Thread { runCatching { sink.append(stream.bufferedReader().readText()) } }
                .apply { isDaemon = true; start() }
        }
        if (stdin != null && os != null) {
            os.write(stdin)
            os.flush()
            os.close()
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        readers.forEach { it.join(maxOf(1L, deadline - System.currentTimeMillis())) }
        while (!ch.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return ExecResult(stdoutBuf.toString() + stderrBuf.toString(), ch.exitStatus)
    } finally {
        runCatching { ch.disconnect() }
    }
}
