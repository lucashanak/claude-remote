package com.clauderemote.integration

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSchException
import org.junit.After
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Self-test of the fixture additions: `extraSshdConfig`, the VERBOSE
 * channel-open log pattern that the pool/exec tests count, the exact way a full
 * `MaxSessions` surfaces in jsch-0.2.21, and SFTP through the ForceCommand sandbox.
 *
 * MEASURED, not assumed (OpenSSH 9.2, jsch 0.2.21): the refusal is
 * `JSchException("channel is not opened.")` and the channel's `exitStatus` carries
 * the SSH open-failure reason code, which for MaxSessions is **2**
 * (SSH_OPEN_CONNECT_FAILED), NOT 1 (administratively prohibited) as the plan
 * assumed. A spill rule for refused channels must therefore key on the message and accept both
 * 1 and 2 (see [REFUSAL_EXIT_STATUSES]).
 */
class SshdFixtureSelfTest {
    private var fixture: SshdFixture? = null

    @After fun tearDown() { fixture?.close() }

    private fun start(extra: String = ""): SshdFixture {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        return SshdFixture.start(extra).also { fixture = it }
    }

    private fun openSleep(s: com.jcraft.jsch.Session): ChannelExec =
        (s.openChannel("exec") as ChannelExec).also { it.setCommand("sleep 20"); it.connect(5000) }

    @Test fun maxSessions4RefusesTheFifthChannelWithChannelIsNotOpened() {
        val f = start("MaxSessions 4")
        val s = f.openRawSession()
        val open = (1..4).map { openSleep(s) }
        try {
            val fifth = s.openChannel("exec") as ChannelExec
            fifth.setCommand("true")
            val e = assertFailsWith<JSchException> { fifth.connect(5000) }
            assertEquals("channel is not opened.", e.message)
            assertTrue(fifth.exitStatus in REFUSAL_EXIT_STATUSES, "exitStatus=${fifth.exitStatus}")
            assertEquals(2, fifth.exitStatus, "measured on OpenSSH 9.2: MaxSessions ⇒ CONNECT_FAILED(2)")
            // the log tells the same story: 4 started, 1 refused
            waitUntil(5_000, "refusal in sshd.log") { f.refusedChannelOpens() == 1 }
            assertEquals(4, f.sessionChannelOpens())
        } finally {
            open.forEach { it.disconnect() }; s.disconnect()
        }
    }

    @Test fun withoutExtraConfigTheDefaultMaxSessionsAllowsMoreThanFour() {
        val f = start()
        val s = f.openRawSession()
        val open = (1..6).map { openSleep(s) } // default MaxSessions 10: control for the test above
        try {
            assertEquals(6, f.sessionChannelOpens())
            assertEquals(0, f.refusedChannelOpens())
        } finally {
            open.forEach { it.disconnect() }; s.disconnect()
        }
    }

    @Test fun verboseChannelOpenPatternAndHandshakeCounter() {
        val f = start()
        val s = f.openRawSession()
        openSleep(s).disconnect(); openSleep(s).disconnect()
        val starts = File(f.root, "sshd.log").readLines().filter { it.contains("Starting session:") }
        assertEquals(2, starts.size)
        val pattern = Regex("""Starting session: forced-command \(config\) '.*/wrap\.sh' for \S+ from 127\.0\.0\.1 port \d+ id \d+""")
        starts.forEach { assertTrue(pattern.containsMatchIn(it), "unexpected channel-open line: $it") }
        assertEquals(1, f.acceptedHandshakes())
        f.openRawSession().disconnect()
        assertEquals(2, f.acceptedHandshakes())
        s.disconnect()
    }

    @Test fun extraSshdConfigIsAppendedToSshdConfig() {
        val f = start("MaxSessions 4\nMaxAuthTries 3")
        val cfg = File(f.root, "sshd_config").readText()
        assertTrue(cfg.contains("LogLevel VERBOSE\nMaxSessions 4\nMaxAuthTries 3\n"), cfg)
    }

    /**
     * SFTP works through the ForceCommand sandbox because wrap.sh
     * serves `SSH_ORIGINAL_COMMAND=internal-sftp` with the out-of-process
     * sftp-server inside the sandbox HOME.
     */
    @Test fun sftpThroughForceCommandLandsInSandboxHome() {
        assertTrue(SshdFixture.sftpServerPath() != null, "no sftp-server binary in any known location on this box")
        val f = start()
        val s = f.openRawSession()
        try {
            val ch = s.openChannel("sftp") as ChannelSftp
            ch.connect(5000)
            ch.put("hello-sftp".byteInputStream(), "sftp-probe.txt")
            assertTrue(ch.pwd().startsWith(f.home.canonicalPath) || ch.pwd().startsWith(f.home.absolutePath), "pwd=${ch.pwd()}")
            ch.disconnect()
            assertEquals("hello-sftp", File(f.home, "sftp-probe.txt").readText())
        } finally {
            s.disconnect()
        }
    }

    companion object {
        /** SSH open-failure reason codes jsch exposes via exitStatus: 1 = administratively prohibited, 2 = connect failed. */
        val REFUSAL_EXIT_STATUSES = setOf(1, 2)
    }
}
