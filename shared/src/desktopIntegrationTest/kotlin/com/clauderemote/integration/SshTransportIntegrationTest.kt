package com.clauderemote.integration

import com.clauderemote.connection.SshManager
import com.clauderemote.storage.ServerStorage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [SshManager] against a REAL sshd on loopback with real key auth — the
 * production class, not a stub.
 */
class SshTransportIntegrationTest {

    @Test
    fun connectsWithKeyAuthAndExposesALiveSession() = runBlocking {
        val manager = newManager()
        try {
            val session = manager.connect(
                server = fixture.server,
                onOutput = {},
                onConnectionLost = {},
            )
            assertTrue(session.isConnected, "jsch session should be connected")
            assertNotNull(manager.getSession(), "getSession() must expose the live session")
            assertTrue(manager.isConnected, "SshManager.isConnected should be true after connect")
        } finally {
            manager.shutdownForTest()
        }
    }

    @Test
    fun execChannelRoundTripsCommandOutput() = runBlocking {
        val manager = newManager()
        try {
            manager.connect(fixture.server, onOutput = {}, onConnectionLost = {})
            val session = assertNotNull(manager.getSession())
            val result = session.execCapture("echo ROUNDTRIP-\$((6*7))")
            assertEquals(0, result.exit, "exec should exit 0, got: ${result.out}")
            assertContains(result.out, "ROUNDTRIP-42", message = "remote shell must evaluate and return output")
        } finally {
            manager.shutdownForTest()
        }
    }

    /**
     * `uploadFile` is the path every sessions.json push and script install rides.
     * Note it uses an exec channel + `cat` rather than SFTP (deliberate — SFTP is
     * unreliable through the Cloudflare WebSocket tunnel), so this asserts the
     * bytes really land, whatever the mechanism.
     */
    @Test
    fun uploadFileLandsExactBytesOnDisk() = runBlocking {
        val manager = newManager()
        try {
            manager.connect(fixture.server, onOutput = {}, onConnectionLost = {})
            val dir = File(fixture.home, "uploads")
            // Non-ASCII + newlines + shell metacharacters: a naive quoting bug in
            // the upload path would corrupt exactly this.
            val payload = "line-1\nčeština ěščř\n{\"json\":true}\n\$NOT_EXPANDED `no-subshell`\n"
            val remotePath = manager.uploadFile(
                payload.toByteArray(Charsets.UTF_8),
                dir.absolutePath,
                "payload.txt",
            )
            assertEquals(File(dir, "payload.txt").absolutePath, remotePath)
            val landed = File(dir, "payload.txt")
            waitUntil(10_000, "uploaded file to appear at ${landed.absolutePath}") { landed.isFile }
            assertEquals(payload, landed.readText(Charsets.UTF_8), "uploaded bytes must match exactly")
        } finally {
            manager.shutdownForTest()
        }
    }

    /**
     * `disconnect()` on a session that is ACTIVELY streaming — which is what a real
     * session is (an attached claude pane emits its spinner continuously).
     *
     * That condition is load-bearing, not incidental. `disconnect()` joins its read
     * loop before closing the channel, and the loop sits in a blocking,
     * non-cancellable `inputStream.read()`; it can only notice cancellation when
     * bytes arrive. On a silent shell `disconnect()` therefore does not return at
     * all — see `shutdownForTest` and the report. This test asserts the real path,
     * and deliberately does NOT paper over the idle one.
     */
    @Test
    fun disconnectClosesAStreamingSessionCleanly() = runBlocking {
        val manager = newManager()
        val output = StringBuilder()
        manager.connect(
            fixture.server,
            onOutput = { chunk -> synchronized(output) { output.append(chunk) } },
            onConnectionLost = {},
        )
        val session = assertNotNull(manager.getSession())
        manager.sendInput("while :; do printf 'tick '; sleep 0.05; done\n")
        waitUntil(20_000, "the remote shell to start streaming") {
            synchronized(output) { output.contains("tick") }
        }

        val elapsed = kotlin.system.measureTimeMillis { manager.disconnect() }
        assertTrue(elapsed < 20_000, "disconnect() took ${elapsed}ms on a streaming session")
        assertFalse(manager.isConnected, "isConnected must be false after disconnect")
        assertEquals(null, manager.getSession(), "getSession() must be cleared after disconnect")
        waitUntil(5_000, "underlying jsch session to close") { !session.isConnected }
    }

    /**
     * A key that is not in authorized_keys must FAIL — and fail fast. The
     * `withTimeoutOrNull` is the real assertion: a hang here would look like a
     * passing test in a suite that only checked "did it throw".
     */
    @Test
    fun wrongKeyFailsAuthenticationWithoutHanging() = runBlocking {
        val manager = newManager()
        val outcome = withTimeoutOrNull(30_000) {
            runCatching {
                manager.connect(fixture.serverWithWrongKey, onOutput = {}, onConnectionLost = {})
            }
        }
        assertNotNull(outcome, "connect() with a wrong key HUNG past 30s instead of failing")
        assertTrue(outcome.isFailure, "connect() must fail with a key absent from authorized_keys")
        val message = outcome.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            message.contains("Auth", ignoreCase = true) ||
                message.contains("fail", ignoreCase = true),
            "expected an auth failure, got: $message",
        )
        manager.shutdownForTest()
    }

    private fun newManager() = SshManager(ServerStorage(InMemoryKeyValueStore()))

    companion object {
        private lateinit var fixture: SshdFixture

        @BeforeClass
        @JvmStatic
        fun boot() {
        // NO SILENT SKIP. The Gradle task already refuses to run without
        // sshd/tmux/jq (see :shared:integrationTest's doFirst), so an unsupported
        // box HERE is a wiring problem — and a JUnit "skipped" on these tests is
        // indistinguishable from a pass in CI. Fail loudly, naming what is missing.
        SshdFixture.unsupportedReason?.let {
            error("integration environment incomplete — $it")
        }
            fixture = SshdFixture.start()
        }

        @AfterClass
        @JvmStatic
        fun shutdown() {
            if (::fixture.isInitialized) fixture.close()
        }
    }
}
