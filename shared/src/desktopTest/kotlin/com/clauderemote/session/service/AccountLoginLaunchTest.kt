package com.clauderemote.session.service

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.session.BudgetFixture
import com.clauderemote.session.TabManager
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.testsupport.safeDeleteTree
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.LocalExecChannel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [AccountService.startLogin] runs the real login launch command ([ClaudeConfig.buildTmuxLoginCommand]) in a local
 * `sh` with a scratch HOME, a socket guard (`~/.claude-remote/tmux-sockheal.sh`) that exits with a chosen status and
 * stand-ins for `tmux` and `systemd-run` that only record their arguments: no tmux server is ever touched.
 *
 * Round 3 (testy-stav F4, kod-stav L2): an exec read returns stdout only, so a refusal of the guard (exit ≠ 0, its
 * reason on stderr) used to come back as "launched". It must be `false`, with the guard's reason in the log.
 */
class AccountLoginLaunchTest {
    private val root = File(System.getProperty("java.io.tmpdir")).resolve("acct-login-${System.nanoTime()}").also { it.mkdirs() }
    private val home = File(root, "home")
    private val bin = File(root, "bin")
    private val tmuxLog = File(root, "tmux.log")
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedLog = FileLogger.platformLog
    private val storage = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { level, _, message, _ -> logLines += "$level $message" }
        storage.addServer(server)
        File(home, ".claude-remote").mkdirs()
        bin.mkdirs()
        script(File(bin, "tmux"), "echo \"tmux \$*\" >> '${tmuxLog.absolutePath}'\ncase \"\$1\" in new-session) exit \${FAKE_NEW_SESSION_RC:-0};; list-sessions) exit 0;; esac\nexit 0")
        script(File(bin, "systemd-run"), "exit 0")
    }

    @AfterTest fun tearDown() {
        FileLogger.platformLog = savedLog
        CommFlags.resetForTest()
        safeDeleteTree(root)
    }

    private fun script(f: File, body: String) {
        f.writeText("#!/bin/sh\n$body\n")
        f.setExecutable(true)
    }

    /** The socket guard exits [rc] and says why on stderr, as the real one does. */
    private fun guard(rc: Int) = script(File(home, ".claude-remote/tmux-sockheal.sh"), "echo 'sockheal: split brain' >&2\nexit $rc")

    private fun startLogin(newSessionRc: Int = 0): Boolean {
        val env = mapOf(
            "HOME" to home.absolutePath,
            "PATH" to bin.absolutePath + ":/usr/bin:/bin",
            "XDG_RUNTIME_DIR" to root.absolutePath,
            "FAKE_NEW_SESSION_RC" to newSessionRc.toString(),
        )
        val fake = FakeSession { _ ->
            LocalExecChannel(env) { cmd -> if (cmd.contains("claude auth login")) cmd else "echo PROVISIONED" }
        }
        val tabs = TabManager().also {
            it.addTab(ClaudeSession(id = "t1", server = server, folder = "/w", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
                tmuxSessionName = "claude-server-a", connectionType = ConnectionType.SSH))
        }
        val reg = ConnectionRegistry(storage, tabs)
        reg.putSsh("t1", BudgetFixture.connectedManager(storage, fake))
        return runBlocking { AccountService(reg, storage).startLogin(server.id, "work") }
    }

    private fun tmuxCalls() = if (tmuxLog.exists()) tmuxLog.readLines() else emptyList()

    @Test fun aRefusingSocketGuardIsNotReportedAsLaunched() {
        guard(1)
        assertFalse(startLogin(), "the guard refused: no login pane")
        assertTrue(tmuxCalls().none { it.startsWith("tmux new-session") }, "nothing below the guard ran: ${tmuxCalls()}")
        assertTrue(logLines.none { it.contains("launched") && !it.contains("NOT launched") }, "no 'launched': $logLines")
        val line = logLines.single { it.contains("NOT launched") }
        assertTrue(line.startsWith("E ") && line.contains("exit 1") && line.contains("socket guard exit 1"), "the guard's reason is logged: $line")
    }

    @Test fun aHealedSocketStillLaunchesTheLoginPane() {
        guard(3)
        assertTrue(startLogin(), "exit 3 = healed: the detached login pane keeps the old path")
        assertTrue(tmuxCalls().any { it.startsWith("tmux new-session -d") }, "${tmuxCalls()}")
    }

    @Test fun aQuietGuardLaunchesTheLoginPane() {
        guard(0)
        assertTrue(startLogin())
        assertTrue(logLines.any { it.contains("tmux claude-login-work launched") }, "$logLines")
    }

    @Test fun aNewSessionThatFailsIsNotReportedAsLaunched() {
        guard(0)
        assertFalse(startLogin(newSessionRc = 1), "tmux new-session failed: no login pane")
        assertEquals(1, logLines.count { it.contains("NOT launched (exit 1)") }, "$logLines")
    }
}
