package com.clauderemote.session

import com.clauderemote.connection.SshManager
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecChannels
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecResult
import com.clauderemote.session.service.Pace
import com.clauderemote.session.service.PooledRemoteExec
import com.clauderemote.session.service.RemoteExec
import com.clauderemote.session.service.ServerBootstrap
import com.clauderemote.session.service.SessionPersistenceService
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.testsupport.safeDeleteTree
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.Session
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * C0 (dávka C, K-1): the ~80 KB restore installer goes on the channel's stdin, paced 4 KB / 20 ms, behind a short
 * exec request, and runs on the server only when it arrived whole.
 */
class RestoreInstallSendTest {
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedLog = FileLogger.platformLog
    private val tabs = TabManager()
    private val storage = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
        storage.addServer(server)
    }

    @AfterTest fun tearDown() {
        FileLogger.platformLog = savedLog
        CommFlags.resetForTest()
    }

    private fun orchestrator() = SessionOrchestrator(storage, tabs, null).also { it.connectionRegistry.bootstrap = NoBootstrap() }

    private fun bounded(body: suspend () -> Unit) = runBlocking { withTimeout(30_000) { body() } }

    private class NoBootstrap : ServerBootstrap {
        override suspend fun ensure(serverId: String) {}
        override fun invalidate(serverId: String) {}
        override suspend fun refreshFlags(serverId: String) {}
    }

    private class Captured(val cmd: String, val stdin: ByteArray?, val pace: Pace)

    private class CapturingExec(private val stdout: String = "RESTORE_SCRIPT_PRESENT\nLINGER=yes", private val exit: Int = 0) : RemoteExec {
        val calls = CopyOnWriteArrayList<Captured>()
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult {
            calls += Captured(cmd, stdin, paceStdin)
            return ExecResult(stdout, exit)
        }
    }

    /** An exec channel that records the request and every stdin write with its time. */
    private class RecordingChannel(
        private val stdout: String = "RESTORE_SCRIPT_PRESENT\nLINGER=yes\n",
        private val exit: Int = 0,
    ) : ChannelExec() {
        @Volatile var sentCommand: String? = null
        val commands = java.util.concurrent.atomic.AtomicInteger()
        val writes = CopyOnWriteArrayList<Pair<Long, ByteArray>>()
        override fun setCommand(command: String) { sentCommand = command; commands.incrementAndGet() }
        override fun connect(connectTimeout: Int) {}
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
            override fun write(b: ByteArray, off: Int, len: Int) { writes += System.nanoTime() to b.copyOfRange(off, off + len) }
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(stdout.toByteArray())
        override fun setInputStream(`in`: InputStream?) {}
        override fun getExitStatus(): Int = exit
        override fun isClosed(): Boolean = false
        override fun isEOF(): Boolean = false
        override fun disconnect() {}
    }

    private class OneChannel(val ch: Channel) : ExecChannels {
        override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (Channel) -> T): T = block(ch)
        override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (Session) -> T): T = error("unused")
    }

    // ---- what goes out ----------------------------------------------------------------------------

    @Test fun theInstallerGoesOnStdinBehindAnExecRequestOfAFewHundredBytes() = bounded {
        val o = orchestrator()
        val exec = CapturingExec().also { o.connectionRegistry.remoteExec = it }
        assertTrue(o.persistence.ensureRestoreService(server.id))
        val call = exec.calls.single()
        assertEquals(SessionPersistenceService.INSTALL_RESTORE_EXEC, call.cmd, "the exec request is the short launcher")
        assertTrue(call.cmd.toByteArray().size < 512, "exec request of ${call.cmd.toByteArray().size} bytes")
        assertContentEquals(SessionPersistenceService.installRestoreStdin("claude-box-"), call.stdin, "the installer goes on stdin")
        assertEquals(
            SessionPersistenceService.INSTALL_RESTORE_COMMAND + "\n" + SessionPersistenceService.prefixWrite("claude-box-") + "\n" +
                SessionPersistenceService.INSTALL_RESTORE_END + "\n",
            String(call.stdin!!, Charsets.UTF_8),
            "stdin = the installer unchanged, the write of the box's own session prefix (D6-S), then the end line",
        )
        assertEquals(Pace(chunkBytes = 4096, delayMs = 20), call.pace, "paced like every upload through the tunnel")
        assertTrue(logLines.any { it.contains("Restore service install: RESTORE_SCRIPT_PRESENT") }, "$logLines")
    }

    @Test fun theStdinIsWrittenInChunksOfAtMost4KBWithAPauseBetweenThem() = bounded {
        val o = orchestrator()
        val ch = RecordingChannel()
        o.connectionRegistry.remoteExec = PooledRemoteExec(OneChannel(ch))
        assertTrue(o.persistence.ensureRestoreService(server.id))
        assertPacedInstall(ch, SessionPersistenceService.installRestoreStdin("claude-box-"))
    }

    /** The legacy path (exec routing off): the same request and the same paced stdin on the tab's own session. */
    @Test fun theLegacyPathSendsTheSameRequestAndPacesTheSameWay() = bounded {
        val o = orchestrator()
        val ch = RecordingChannel()
        val mgr = SshManager(storage)
        SshManager::class.java.getDeclaredField("session").also { it.isAccessible = true }.set(mgr, FakeSession(channels = { ch }))
        o.persistence.ensureRestoreService(mgr)
        // a manager of no tab: the box is unknown, so the default prefix (no line is written)
        assertPacedInstall(ch, SessionPersistenceService.installRestoreStdin())
        assertTrue(logLines.any { it.contains("Restore service install: RESTORE_SCRIPT_PRESENT") }, "$logLines")
    }

    private fun assertPacedInstall(ch: RecordingChannel, stdin: ByteArray) {
        assertEquals(SessionPersistenceService.INSTALL_RESTORE_EXEC, ch.sentCommand)
        assertTrue(stdin.size > 60_000, "precondition: the installer is big (${stdin.size} B)")
        val sizes = ch.writes.map { it.second.size }
        assertTrue(sizes.all { it <= 4096 }, "every write at most 4096 B: ${sizes.filter { it > 4096 }}")
        assertEquals((stdin.size + 4095) / 4096, sizes.size, "one write per 4 KB chunk")
        assertContentEquals(stdin, ch.writes.map { it.second }.reduce { a, b -> a + b }, "the installer arrives whole and in order")
        val gapsMs = ch.writes.zipWithNext { a, b -> (b.first - a.first) / 1_000_000 }
        assertTrue(gapsMs.all { it >= 15 }, "a pause of ~20 ms between chunks, measured: $gapsMs")
    }

    @Test fun anInstallThatDidNotArriveWholeIsNotRememberedAsDone() = bounded {
        val o = orchestrator()
        val exec = CapturingExec(stdout = SessionPersistenceService.INSTALL_RESTORE_TRUNCATED).also { o.connectionRegistry.remoteExec = it }
        assertFalse(o.persistence.ensureRestoreService(server.id), "nothing was installed: the caller may ask again")
        assertFalse(o.persistence.ensureRestoreService(server.id))
        assertEquals(2, exec.calls.size, "asked again: nothing ran the first time")
    }

    // ---- kolo 2: the verdict is the exit status (adv 4) ----

    private fun assertNotRemembered(stdout: String, exit: Int, returns: Boolean) = bounded {
        val o = orchestrator()
        val exec = CapturingExec(stdout, exit).also { o.connectionRegistry.remoteExec = it }
        assertEquals(returns, o.persistence.ensureRestoreService(server.id), "rc $exit, out '$stdout'")
        o.persistence.ensureRestoreService(server.id)
        assertEquals(2, exec.calls.size, "rc $exit, out '$stdout': not remembered, the next launch installs again")
    }

    @Test fun anInstallThatExitedNonZeroIsNotRememberedAsDone() {
        assertNotRemembered("", 127, returns = true)   // no bash on the server: it ran and failed, asking at once fails the same way
        assertNotRemembered("RESTORE_SCRIPT_INSTALLED", 1, returns = true)   // set -e cut it short
        assertNotRemembered("Welcome to box\n${SessionPersistenceService.INSTALL_RESTORE_TRUNCATED}", 97, returns = false)   // a banner before it
        assertTrue(logLines.any { it.contains("Restore service install exited with 127") }, "$logLines")
    }

    @Test fun anInstallThatExitedZeroIsRemembered() = bounded {
        val o = orchestrator()
        val exec = CapturingExec("RESTORE_SCRIPT_PRESENT\nLINGER=yes", 0).also { o.connectionRegistry.remoteExec = it }
        assertTrue(o.persistence.ensureRestoreService(server.id))
        assertTrue(o.persistence.ensureRestoreService(server.id))
        assertEquals(1, exec.calls.size)
    }

    /** N1: the legacy path judges the same way; its memo works through the tab the manager belongs to. */
    @Test fun theLegacyPathRemembersOnlyAnInstallThatRanToItsEnd() = bounded {
        for ((stdout, exit, runs) in listOf(
            Triple(SessionPersistenceService.INSTALL_RESTORE_TRUNCATED + "\n", 97, 2),
            Triple("RESTORE_SCRIPT_INSTALLED\n", 1, 2),
            Triple("RESTORE_SCRIPT_PRESENT\nLINGER=yes\n", 0, 1),
        )) {
            val o = orchestrator()
            val ch = RecordingChannel(stdout, exit)
            val mgr = SshManager(storage)
            SshManager::class.java.getDeclaredField("session").also { it.isAccessible = true }.set(mgr, FakeSession(channels = { ch }))
            val id = "t-$exit"
            tabs.addTab(ClaudeSession(id = id, server = server, folder = "/w", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
                tmuxSessionName = "claude-box-$exit", connectionType = ConnectionType.SSH))
            o.connectionRegistry.putSsh(id, mgr)
            o.persistence.ensureRestoreService(mgr)
            o.persistence.ensureRestoreService(mgr)
            assertEquals(runs, ch.commands.get(), "rc $exit, out '$stdout'")
            tabs.removeTab(id)
        }
    }

    // ---- kolo 2: the restore unit is written once (adv 3, HIGH) ----

    /** Shims that only log their arguments (nothing reaches the live user manager); tmux refuses. */
    private fun loggingShims(root: File): File {
        val log = File(root, "shim.log")
        val shims = File(root, "shims").apply { mkdirs() }
        File(shims, "systemctl").apply { writeText("#!/usr/bin/env bash\necho \"systemctl \$*\" >> '${log.absolutePath}'\nexit 0\n") }.setExecutable(true)
        File(shims, "loginctl").apply {
            writeText("#!/usr/bin/env bash\necho \"loginctl \$*\" >> '${log.absolutePath}'\ncase \"\$*\" in *show-user*) echo yes;; esac\nexit 0\n")
        }.setExecutable(true)
        File(shims, "tmux").apply { writeText("#!/usr/bin/env bash\nexit 1\n") }.setExecutable(true)
        return log
    }

    private fun snapshot(home: File): Map<String, String> =
        listOf(".claude-remote", ".config/systemd/user").flatMap { d ->
            File(home, d).walkTopDown().filter { it.isFile && it.name != "install.log" }.map { it.relativeTo(home).path to it.readText() }.toList()
        }.toMap().toSortedMap()

    @Test fun aSecondInstallFindsTheUnitAndReloadsNothing() = withRoot { root ->
        val log = loggingShims(root)
        val first = runLauncher(root, SessionPersistenceService.installRestoreStdin(), shimsReady = true)
        assertEquals(0, first.exit, first.out)
        assertEquals(
            listOf("RESTORE_SCRIPT_INSTALLED", "DRIFT_SCRIPT_INSTALLED", "SOCKHEAL_SCRIPT_INSTALLED", "RESTORE_UNIT_INSTALLED", "DRIFT_TIMER_INSTALLED", "LINGER=yes"),
            first.out.lines().filter { it.isNotBlank() }, "the first install is unchanged",
        )
        assertTrue(log.readLines().any { it == "systemctl --user daemon-reload" }, "the first install reloads: ${log.readLines()}")
        val home = File(root, "home")
        val unit = File(home, ".config/systemd/user/claude-remote-restore.service").readText()
        assertTrue("ExecStart=/usr/bin/env bash %h/.claude-remote/restore.sh" in unit, unit)
        val before = snapshot(home)
        val linesBefore = log.readLines().size

        val second = runLauncher(root, SessionPersistenceService.installRestoreStdin(), shimsReady = true)
        assertEquals(0, second.exit, second.out)
        assertEquals(listOf("RESTORE_SCRIPT_PRESENT", "RESTORE_UNIT_PRESENT", "LINGER=yes"), second.out.lines().filter { it.isNotBlank() })
        val added = log.readLines().drop(linesBefore)
        assertTrue(added.isNotEmpty() && added.all { it.startsWith("loginctl show-user") },
            "the second install only reads linger; no systemctl at all (no daemon-reload, no enable): $added")
        assertEquals(before, snapshot(home), "and rewrites nothing")
    }

    @Test fun aLeftoverOwnedUnitIsRemovedOnceNotOnEveryInstall() = withRoot { root ->
        val log = loggingShims(root)
        File(root, "home/.config/systemd/user").mkdirs()
        File(root, "home/.config/systemd/user/claude-tmux.service").writeText("[Service]\n")
        val first = runLauncher(root, SessionPersistenceService.installRestoreStdin(), shimsReady = true)
        assertTrue("OWNED_UNIT_REMOVED" in first.out, first.out)
        val linesBefore = log.readLines().size
        val second = runLauncher(root, SessionPersistenceService.installRestoreStdin(), shimsReady = true)
        assertFalse("OWNED_UNIT_REMOVED" in second.out, second.out)
        assertTrue(log.readLines().drop(linesBefore).none { it.startsWith("systemctl") }, "${log.readLines().drop(linesBefore)}")
    }

    // ---- what the server does with it ---------------------------------------------------------------

    private class Ran(val exit: Int, val out: String)

    /** Runs the launcher in a private HOME; systemctl, loginctl and tmux are shims that refuse (nothing reaches the live box). */
    private fun runLauncher(root: File, stdin: ByteArray, shimsReady: Boolean = false): Ran {
        val home = File(root, "home").apply { mkdirs() }
        val shims = File(root, "shims").apply { mkdirs() }
        if (!shimsReady) for (tool in listOf("systemctl", "loginctl", "tmux")) {
            File(shims, tool).apply { writeText("#!/usr/bin/env bash\nexit 1\n") }.setExecutable(true)
        }
        val pb = ProcessBuilder("bash", "-c", SessionPersistenceService.INSTALL_RESTORE_EXEC).redirectErrorStream(true)
        pb.environment()["HOME"] = home.absolutePath
        pb.environment()["PATH"] = shims.absolutePath + File.pathSeparator + System.getenv("PATH")
        val p = pb.start()
        p.outputStream.use { it.write(stdin) }
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "launcher hung")
        return Ran(p.exitValue(), out)
    }

    private fun withRoot(body: (File) -> Unit) {
        val root = Files.createTempDirectory("c0-install").toFile()
        try { body(root) } finally { safeDeleteTree(root) }
    }

    @Test fun aStdinCutShortRunsNothingOfTheInstaller() {
        val whole = SessionPersistenceService.installRestoreStdin()
        val script = SessionPersistenceService.INSTALL_RESTORE_COMMAND.toByteArray(Charsets.UTF_8)
        val cuts = mapOf(
            "empty" to 0,
            "first chunk" to 4096,
            "half" to whole.size / 2,
            "the whole installer without its end line" to script.size + 1,
            "the end line cut by one byte" to whole.size - 2,
        )
        for ((what, n) in cuts) withRoot { root ->
            val r = runLauncher(root, whole.copyOf(n))
            assertEquals(97, r.exit, "$what: exit. out:\n${r.out}")
            assertEquals(SessionPersistenceService.INSTALL_RESTORE_TRUNCATED, r.out.trim(), "$what: output")
            assertFalse(File(root, "home/.claude-remote").exists(), "$what: the installer must not have run (it creates ~/.claude-remote)")
        }
    }

    @Test fun aWholeStdinRunsAsAScriptWithItsOutputAndExitStatus() = withRoot { root ->
        val end = SessionPersistenceService.INSTALL_RESTORE_END
        val ok = runLauncher(root, "set -e\necho \"one \$HOME\"\ncat <<'X'\nheredoc 'kept' \$HOME\nX\necho two\n$end\n".toByteArray())
        assertEquals(0, ok.exit, ok.out)
        assertEquals("one ${File(root, "home").absolutePath}\nheredoc 'kept' \$HOME\ntwo\n", ok.out)
        val failing = runLauncher(root, "set -e\necho before\nfalse\necho after\n$end\n".toByteArray())
        assertEquals(1, failing.exit, "set -e ends the script with the failing command's status: ${failing.out}")
        assertEquals("before\n", failing.out)
    }

    @Test fun theEndLineAppearsOnlyAtTheEndOfTheStdin() {
        val end = SessionPersistenceService.INSTALL_RESTORE_END
        assertFalse(SessionPersistenceService.INSTALL_RESTORE_COMMAND.contains(end), "the installer must not contain the end line")
        val stdin = String(SessionPersistenceService.installRestoreStdin(), Charsets.UTF_8)
        assertEquals(stdin.length - end.length - 1, stdin.indexOf(end), "exactly once, as the last line")
    }
}
