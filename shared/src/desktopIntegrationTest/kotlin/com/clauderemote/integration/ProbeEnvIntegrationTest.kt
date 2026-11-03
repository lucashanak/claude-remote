package com.clauderemote.integration

import com.jcraft.jsch.ChannelExec
import org.junit.After
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The INTEGRATION test JVM (the hand-registered `integrationTest` task, not `desktopTest`) must run
 * under the same isolation as every other Test task: this lane starts real tmux servers and shell
 * scripts on the box that hosts the developer's live sessions. The isolation is configured once in
 * the root build.gradle.kts for all Test tasks; if it were scoped to one task by mistake, this is
 * the test that goes red (DesktopTestEnvTest only covers desktopTest).
 */
class ProbeEnvIntegrationTest {
    @Test fun integrationJvmHasNoTmuxEnvAndAPrivateTmuxTmpdir() {
        assertNull(System.getenv("TMUX"), "TMUX leaked into the integration JVM")
        assertNull(System.getenv("TMUX_PANE"))
        val dir = System.getenv("TMUX_TMPDIR")
        assertNotNull(dir, "TMUX_TMPDIR unset: tmux would use the LIVE default socket")
        assertEquals(System.getProperty("clauderemote.test.tmuxTmpDir"), dir)
        assertTrue(!File(dir).canonicalPath.startsWith("/tmp/tmux-"), "TMUX_TMPDIR=$dir is the live socket directory")
    }

    @Test fun integrationJvmHasASandboxHomeAndTheTmuxShimFirstOnPath() {
        val real = System.getProperty("clauderemote.live.home")
        assertNotNull(real, "clauderemote.live.home not set: the isolation is not configured for this task")
        assertNotEquals(File(real).canonicalPath, File(System.getProperty("user.home")).canonicalPath, "user.home is the REAL home")
        assertEquals(File(System.getProperty("user.home")).canonicalPath, File(System.getenv("HOME")).canonicalPath)
        val first = System.getenv("PATH").split(File.pathSeparator).first()
        assertEquals(File(System.getProperty("clauderemote.test.shimDir")).canonicalPath, File(first).canonicalPath)
    }

    private var fixture: SshdFixture? = null

    @After fun closeFixture() { fixture?.close() }

    /** Commands that production code sends through the sandboxed sshd must find the tmux shim, not the real tmux, first. */
    @Test fun aCommandThroughTheSandboxedSshdResolvesTmuxToTheShim() {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        val f = SshdFixture.start().also { fixture = it }
        val session = f.openRawSession()
        try {
            val ch = session.openChannel("exec") as ChannelExec
            ch.setCommand("command -v tmux")
            val out = java.io.ByteArrayOutputStream()
            ch.outputStream = out
            ch.connect(5000)
            val deadline = System.currentTimeMillis() + 10_000
            while (!ch.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(50)
            ch.disconnect()
            val shimTmux = File(System.getProperty("clauderemote.test.shimDir"), "tmux").canonicalPath
            assertEquals(shimTmux, File(out.toString().trim()).canonicalPath, "tmux inside the sandboxed sshd is not the test shim")
        } finally {
            session.disconnect()
        }
    }
}
