package com.clauderemote.integration

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionStatus
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.session.service.CommFlags
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertTrue

/**
 * Kolo 6 (adv H1, probe A1) against a real tmux 3.5a on the fixture's PRIVATE socket: `reconnectSession` while the tmux
 * server lives without its socket file (the 2026-11-02 shape). Kolo 5 read that as fail-open, attached plainly, the
 * attach failed ("no sessions"), the login shell stayed, the tab went ACTIVE, and `echo BARE-$((6*7))` printed
 * `BARE-42` from bash. Now has-session says NO (Rebuild through sockheal, or the attach line ends the shell): the tab is
 * never ACTIVE over a bare login shell, and what is typed is never run by it. The server is stopped at the end by its own
 * pid, checked first to be the `tmux: server` listening on the fixture's socket path — never another.
 */
class HasSessionNoServerIntegrationTest {
    private var fixture: SshdFixture? = null
    private val storage = ServerStorage(InMemoryKeyValueStore())
    private val tabs = TabManager()
    private lateinit var orchestrator: SessionOrchestrator
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedPlatformLog = FileLogger.platformLog
    private var serverPid: String? = null

    @get:org.junit.Rule val dumpOnFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable?, description: org.junit.runner.Description?) {
            println("---- log lines of ${description?.methodName} ----\n" + logLines.takeLast(80).joinToString("\n"))
        }
    }

    @Before fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, t -> logLines += "$tag: $message" + (t?.let { " — ${it.message}" } ?: "") }
    }

    private fun listens(path: String): Boolean =
        File("/proc/net/unix").readLines().any { l -> l.trim().split(Regex("\\s+")).let { it.size >= 8 && it[3] == "00010000" && it[5] == "01" && it[7] == path } }

    @After fun tearDown() {
        if (::orchestrator.isInitialized) {
            orchestrator.shutdownForTest()
            orchestrator.connectionRegistry.teardownAllTransports()
            runCatching { orchestrator.execManager.close(fixture!!.server.id) }
        }
        FileLogger.platformLog = savedPlatformLog
        CommFlags.resetForTest()
        // stop exactly our private server: same pid, a tmux server, listening on the fixture's private path
        val f = fixture
        val pid = serverPid
        if (f != null && pid != null && File("/proc/$pid/comm").exists() &&
            File("/proc/$pid/comm").readText().trim() == "tmux: server" && listens(f.tmuxSocket.absolutePath)) {
            ProcessBuilder("kill", "-TERM", pid).start().waitFor()
        }
        f?.close()
    }

    /** A private server with the tab's session (`cat` in its pane), the tab DISCONNECTED; returns the terminal buffer. */
    private fun setUpTab(): com.clauderemote.session.service.TerminalIOService {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        val f = SshdFixture.start().also { fixture = it }
        f.startTmuxServer()
        f.tmux("new-session", "-d", "-s", "claude-server-a4", "cat")
        serverPid = f.tmux("display-message", "-p", "#{pid}").out.trim()
        assertTrue(serverPid!!.matches(Regex("\\d+")) && File("/proc/$serverPid/comm").readText().trim() == "tmux: server",
            "the private server's pid: $serverPid")
        storage.addServer(f.server)
        orchestrator = SessionOrchestrator(storage, tabs)
        val tio = SessionOrchestrator::class.java.getDeclaredField("terminalIO").also { it.isAccessible = true }.get(orchestrator)
            as com.clauderemote.session.service.TerminalIOService
        tio.initBuffer("t1")
        tabs.addTab(ClaudeSession(
            id = "t1", server = f.server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = "claude-server-a4", connectionType = ConnectionType.SSH, status = SessionStatus.DISCONNECTED,
        ))
        return tio
    }

    /**
     * After the reconnect: whatever it ended in, what is typed into the tab is never run by a bare login shell. A shell
     * inside tmux (a pane: here a rebuilt session whose `claude` is missing in the fixture) prints `BARE-tmux-42`; the
     * login shell outside tmux would print `BARE--42`.
     */
    private fun typedInputNeverRunsInABareShell(tio: com.clauderemote.session.service.TerminalIOService) {
        Thread.sleep(1_500)
        orchestrator.sendInput("t1", "echo BARE-\${TMUX:+tmux}-\$((6*7))\r")
        val end = System.currentTimeMillis() + 8_000
        while ("BARE--42" !in tio.getBuffer("t1") && System.currentTimeMillis() < end) Thread.sleep(100)
        val buf = tio.getBuffer("t1")
        println("H1 status=${tabs.getTab("t1")!!.status} sshLive=${orchestrator.connectionRegistry.ssh("t1")?.isConnected} inTmux=${"BARE-tmux-42" in buf}")
        assertTrue("BARE--42" !in buf, "typed input never runs in a bare login shell: ${buf.takeLast(400).replace("\u001b", "<ESC>")}")
    }

    /** Probe A1: the server lives, its socket file is gone — has-session says NO (Rebuild heals), never a bare shell. */
    @Test fun aReconnectWhileTheServerLivesWithoutItsSocketFileNeverLeavesABareShellUnderAnActiveTab() {
        val tio = setUpTab()
        val f = fixture!!
        assertTrue(f.tmuxSocket.delete(), "socket file deleted")
        assertTrue(listens(f.tmuxSocket.absolutePath), "the server still listens")
        runBlocking { withTimeout(120_000) { orchestrator.reconnectSession("t1") } }
        // has-session said NO: the Rebuild's launch line (through sockheal where installed), not the plain attach
        // (D2: the Rebuild's line ends with `|| exit 1` too — its launch, not `|| exit 1`, tells it from the plain attach)
        assertTrue(logLines.any { "Attaching to tmux:" in it && "new-session" in it }, "the reconnect rebuilt: ${logLines.filter { "Attaching" in it }}")
        typedInputNeverRunsInABareShell(tio)
    }

    /**
     * Kolo 6: a probe that cannot tell (the socket's directory is unreachable: "Permission denied" — fail-open) leads to
     * a plain attach; that attach fails, and its line (`… || exit 1`) ends the login shell: the reconnect ends in its
     * error path, not ACTIVE over bash.
     */
    @Test fun aFailedAttachEndsTheLoginShellInsteadOfLeavingItUnderAnActiveTab() {
        val tio = setUpTab()
        val dir = fixture!!.tmuxSocket.parentFile
        assertTrue(dir.setExecutable(false, false) && dir.setReadable(false, false), "the private socket directory is closed")
        try {
            runBlocking { withTimeout(120_000) { orchestrator.reconnectSession("t1") } }
            // R3: a Rebuild's line ends with `|| exit 1` too (D2) — no `new-session` tells the plain attach from it
            assertTrue(logLines.any { "Attaching to tmux:" in it && "|| exit 1" in it && "new-session" !in it }, "a plain attach was tried: ${logLines.takeLast(20)}")
            // the attach line's `|| exit 1` ends the login shell a moment after the reconnect sent it: the loss follows
            val end = System.currentTimeMillis() + 10_000
            while (logLines.none { "Connection lost" in it } && System.currentTimeMillis() < end) Thread.sleep(50)
            assertTrue(logLines.any { "Connection lost" in it }, "the shell ended with its failed attach: ${logLines.takeLast(20)}")
            typedInputNeverRunsInABareShell(tio)
        } finally {
            dir.setReadable(true, true); dir.setExecutable(true, true)
        }
    }

    /**
     * D2 (N2 of overeni-int-5-adv): a Rebuild whose socket guard REFUSES — the real `tmux-sockheal.sh` the app installs
     * (cut out of `SessionPersistenceService.INSTALL_RESTORE_COMMAND`), in the fixture's private HOME, with its heal
     * lock `tmux-server.lock` held by someone else for longer than its 2 s wait (and its second look). Its launch line
     * prints the refusal and runs `false`; `{ …; } || exit 1` ends the login shell: the loss goes the reconnect way.
     * Before D2 the login shell lived on under the tab and what was typed ran in it (`BARE--42`). The refusal is logged
     * on the server (`sockheal.log`, kritika Hrozba 5: a lasting refusal must be visible).
     */
    @Test fun aRebuildWhoseSocketGuardRefusesEndsTheLoginShell() {
        val tio = setUpTab()
        val f = fixture!!
        val installer = com.clauderemote.session.service.SessionPersistenceService.INSTALL_RESTORE_COMMAND
        val from = installer.indexOf('\n', installer.indexOf("<<'SOCKHEAL_EOF'")) + 1
        val to = installer.indexOf("\nSOCKHEAL_EOF\n", from)
        assertTrue(from > 0 && to > from, "the installer carries tmux-sockheal.sh")
        File(f.home, ".claude-remote").mkdirs()
        File(f.home, ".claude-remote/tmux-sockheal.sh").apply { writeText(installer.substring(from, to + 1)); setExecutable(true) }
        assertTrue(f.tmuxSocket.delete(), "socket file deleted (the guard's heal case)")
        assertTrue(listens(f.tmuxSocket.absolutePath), "the server still listens")
        val lock = File(f.home, ".claude-remote/tmux-server.lock")
        val release = File(f.root, "release-heal-lock")
        val holder = ProcessBuilder("flock", lock.absolutePath, "sh", "-c", "while [ ! -e \"\$1\" ]; do sleep 0.02; done", "holder", release.absolutePath)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        f.trackPid(holder.pid())
        try {
            val end0 = System.currentTimeMillis() + 10_000
            while (ProcessBuilder("flock", "-n", lock.absolutePath, "true").start().waitFor() == 0 && System.currentTimeMillis() < end0) Thread.sleep(20)
            runBlocking { withTimeout(120_000) { orchestrator.reconnectSession("t1") } }
            assertTrue(logLines.any { "Attaching to tmux:" in it && "new-session" in it }, "the reconnect rebuilt: ${logLines.filter { "Attaching" in it }}")
            // the guard waits 2 s for the lock, looks once more after 0.5 s, waits 2 s again: then `false`, then the exit
            val end = System.currentTimeMillis() + 20_000
            while (logLines.none { "Connection lost" in it } && System.currentTimeMillis() < end) Thread.sleep(50)
            assertTrue(logLines.any { "Connection lost" in it }, "the refused launch line ended the login shell: ${logLines.takeLast(20)}")
            typedInputNeverRunsInABareShell(tio)
            assertTrue(logLines.any { "Attaching to tmux:" in it && "new-session" in it && it.trimEnd().endsWith("|| exit 1") },
                "the Rebuild's launch line ends in || exit 1: ${logLines.filter { "Attaching" in it }}")
            val sockheal = File(f.home, ".claude-remote/sockheal.log").let { if (it.isFile) it.readText() else "" }
            assertTrue("could not take" in sockheal, "the refusal is in sockheal.log: $sockheal")
        } finally {
            release.createNewFile()
            if (!holder.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) holder.destroyForcibly()
        }
    }

    /**
     * Kolo 7 (adv N1, probe P1b): an attach that fails for good (the socket's directory is unreachable) on a slow link
     * (the attach fails 1.2 s after its line: a tmux shim of the fixture's own PATH) — its `|| exit 1` loss comes after the
     * reconnect already said ACTIVE. Counted as an early death: few attempts with growing waits in 40 s, not a "succeeded"
     * every 1.6 s for ever (kolo 6: 22 attempts, 21 "succeeded").
     */
    @Test fun anAttachThatKeepsFailingOnASlowLinkBacksOffInsteadOfLooping() {
        setUpTab()
        val f = fixture!!
        File(f.shims, "tmux").apply {
            writeText("#!/bin/bash\nself=\$(cd \"\$(dirname \"\$0\")\" && pwd -P)\n" +
                "P=\$(printf '%s' \"\$PATH\" | tr ':' '\\n' | awk -v s=\"\$self\" '\$0 != s' | paste -sd:)\n" +
                "case \" \$* \" in *\" attach-session \"*) sleep 1.2;; esac\nPATH=\$P exec tmux \"\$@\"\n")
            setExecutable(true)
        }
        val dir = f.tmuxSocket.parentFile
        assertTrue(dir.setExecutable(false, false) && dir.setReadable(false, false), "the private socket directory is closed")
        try {
            runBlocking { withTimeout(120_000) { orchestrator.reconnectSession("t1") } }
            Thread.sleep(40_000)
            val attempts = logLines.filter { "Auto-reconnect attempt" in it }
            val succeeded = logLines.count { "Auto-reconnect succeeded" in it }
            val early = logLines.filter { "early death" in it }
            println("H1-P1b attempts=${attempts.size} succeeded=$succeeded early=${early.map { it.substringAfter("waiting ") }}")
            assertTrue(attempts.size in 1..5, "a handful of attempts in 40 s, not a loop: ${attempts.size}")
            assertTrue(succeeded <= 5, "not a 'succeeded' every round: $succeeded")
            assertTrue(early.any { "waiting 8000ms" in it }, "the waits grow (4 s, 8 s …): $early")
        } finally {
            dir.setReadable(true, true); dir.setExecutable(true, true)
        }
    }
}
