package com.clauderemote.integration

import com.clauderemote.connection.SshManager
import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.PooledRemoteExec
import com.clauderemote.session.service.SessionPersistenceService
import com.clauderemote.session.service.UngatedExecChannels
import com.clauderemote.storage.ServerStorage
import com.clauderemote.testsupport.safeDeleteTree
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.Session
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * C0 (dávka C, K-1) against a real sshd: the production install — exec transport path and legacy tab path — sends a
 * short exec request with the installer on stdin, and leaves on the server the same files, the same output and the
 * same install.log lines as the exec request of 18faa58 (the whole installer as the command).
 */
class RestoreInstallStdinIntegrationTest {
    private lateinit var fixture: SshdFixture
    private lateinit var manager: SshManager
    private lateinit var session: Session
    private lateinit var orchestrator: SessionOrchestrator
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedPlatformLog = FileLogger.platformLog
    private val savedExecChannels = SshSessionHelper.execChannelsProvider
    private val savedLiveSession = SshSessionHelper.liveSessionProvider

    @Before fun boot() {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
        fixture = SshdFixture.start()
        manager = SshManager(ServerStorage(InMemoryKeyValueStore()))
        runBlocking { manager.connect(fixture.server, onOutput = {}, onConnectionLost = {}) }
        session = assertNotNull(manager.getSession())
        val storage = ServerStorage(InMemoryKeyValueStore()).also { it.addServer(fixture.server) }
        orchestrator = SessionOrchestrator(storage, TabManager())
    }

    @After fun shutdown() {
        if (::orchestrator.isInitialized) orchestrator.shutdownForTest()
        if (::manager.isInitialized) manager.shutdownForTest()
        FileLogger.platformLog = savedPlatformLog
        SshSessionHelper.execChannelsProvider = savedExecChannels
        SshSessionHelper.liveSessionProvider = savedLiveSession
        CommFlags.resetForTest()
        if (::fixture.isInitialized) fixture.close()
    }

    private class Installed(val out: String, val files: Map<String, String>, val installLog: List<String>)

    /** Every file the install wrote (content and mode), and install.log without its time stamps. */
    private fun installed(out: String): Installed {
        val files = sortedMapOf<String, String>()
        for (dir in listOf(".claude-remote", ".config/systemd/user")) {
            File(fixture.home, dir).walkTopDown().filter { it.isFile && it.name != "install.log" }.forEach {
                files[it.relativeTo(fixture.home).path] = (if (it.canExecute()) "+x " else "-x ") + it.readText()
            }
        }
        val log = File(fixture.home, ".claude-remote/install.log").readLines().map { it.replace(Regex("""^\[[^]]*] """), "") }
        return Installed(out.trim(), files, log)
    }

    private fun wipe() {
        safeDeleteTree(File(fixture.home, ".claude-remote"))
        safeDeleteTree(File(fixture.home, ".config"))
    }

    private val commandLog get() = File(fixture.root, "remote-commands.log")

    /** What sshd was asked to run while [body] ran (the fixture logs every request as `<time> <command>`). */
    private fun requestsDuring(body: () -> Unit): String {
        val before = commandLog.takeIf { it.isFile }?.readText()?.length ?: 0
        body()
        return commandLog.readText().substring(before)
    }

    private fun loggedInstallOutput(): String {
        val prefix = "SessionOrchestrator: Restore service install: "
        val line = logLines.lastOrNull { it.startsWith(prefix) }
        assertNotNull(line, "no install line logged: $logLines")
        return line.removePrefix(prefix)
    }

    @Test fun theProductionInstallSendsAShortRequestAndLeavesWhatTheOldRequestLeft() {
        // 18faa58: the installer itself was the exec request
        lateinit var old: ExecResult
        val oldRequest = requestsDuring { old = session.execCapture(SessionPersistenceService.INSTALL_RESTORE_COMMAND, timeoutMs = 60_000) }
        assertEquals(0, old.exit, "old install failed:\n${old.out}")
        val expected = installed(old.out)
        assertTrue(expected.files.keys.any { it.endsWith("restore.sh") } && expected.files.keys.any { it.endsWith("drift.sh") }, "${expected.files.keys}")
        assertTrue(oldRequest.length > 60_000, "precondition: the old request was the whole installer (${oldRequest.length} B)")
        val launcherRequest = Regex("""^\S+ ${Regex.escape(SessionPersistenceService.INSTALL_RESTORE_EXEC)}\n$""")

        // the exec transport path
        wipe(); logLines.clear()
        orchestrator.connectionRegistry.remoteExec = PooledRemoteExec(UngatedExecChannels { session })
        val viaExecRequest = requestsDuring { assertTrue(runBlocking { orchestrator.persistence.ensureRestoreService(fixture.server.id) }) }
        assertTrue(launcherRequest.matches(viaExecRequest), "exec path: one request, the short launcher:\n${viaExecRequest.take(300)}")
        val viaExec = installed(loggedInstallOutput())
        assertEquals(expected.out, viaExec.out, "exec path: output")
        assertEquals(expected.files, viaExec.files, "exec path: files")
        assertEquals(expected.installLog, viaExec.installLog, "exec path: install.log")

        // the legacy path (exec routing off: the tab's own session)
        wipe(); logLines.clear()
        val viaTabRequest = requestsDuring { runBlocking { orchestrator.persistence.ensureRestoreService(manager) } }
        assertTrue(launcherRequest.matches(viaTabRequest), "legacy path: one request, the short launcher:\n${viaTabRequest.take(300)}")
        val viaTab = installed(loggedInstallOutput())
        assertEquals(expected.out, viaTab.out, "legacy path: output")
        assertEquals(expected.files, viaTab.files, "legacy path: files")
        assertEquals(expected.installLog, viaTab.installLog, "legacy path: install.log")
    }

    @Test fun anInstallerCutShortOverSshRunsNothing() {
        val whole = SessionPersistenceService.installRestoreStdin()
        val r = session.execCapture(SessionPersistenceService.INSTALL_RESTORE_EXEC, timeoutMs = 60_000, stdin = whole.copyOf(whole.size / 2))
        assertEquals(97, r.exit, r.out)
        assertEquals(SessionPersistenceService.INSTALL_RESTORE_TRUNCATED, r.out.trim())
        assertFalse(File(fixture.home, ".claude-remote/install.log").exists(), "the installer's first line must not have run")
        assertFalse(File(fixture.home, ".claude-remote/restore.sh").exists())
    }
}
