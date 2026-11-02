package com.clauderemote.session.service

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Error contract of [RemoteExec] against a scripted channel (no sshd): which jsch/IO
 * failure becomes which [ExecUnavailableReason]. The real-sshd side (timeout, refusal by
 * MaxSessions, cancellation) is in the integration tests.
 */
class RemoteExecErrorContractTest {
    /** A channel whose connect/streams behave as scripted. */
    private class Scripted(
        private val onConnect: () -> Unit = {},
        private val stdin: OutputStream = object : OutputStream() { override fun write(b: Int) {} },
        private val stdout: InputStream = ByteArrayInputStream(ByteArray(0)),
    ) : ChannelExec() {
        override fun connect(connectTimeout: Int) { onConnect() }
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun setInputStream(`in`: InputStream?) {}
        override fun disconnect() {}
    }

    private class Fake(private val make: () -> Channel) : ExecChannels {
        override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (Channel) -> T): T =
            block(make())
        override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (Session) -> T): T =
            error("not used")
    }

    private fun run(totalMs: Long = 5_000, stdin: ByteArray? = null, make: () -> Channel): ExecResult =
        runBlocking { PooledRemoteExec(Fake(make)).run("srv", "cmd", stdin = stdin, totalMs = totalMs) }

    private fun reasonOf(totalMs: Long = 5_000, stdin: ByteArray? = null, make: () -> Channel): ExecUnavailableReason =
        assertFailsWith<ExecUnavailable> { run(totalMs, stdin, make) }.reason

    @Test fun refusedChannelIsRefused() {
        assertEquals(ExecUnavailableReason.REFUSED, reasonOf { Scripted(onConnect = { throw JSchException("channel is not opened.") }) })
    }

    @Test fun otherConnectFailuresAreIo() {
        assertEquals(ExecUnavailableReason.IO, reasonOf { Scripted(onConnect = { throw JSchException("session is down") }) })
        assertEquals(ExecUnavailableReason.IO, reasonOf { Scripted(onConnect = { throw IOException("boom") }) })
    }

    @Test fun stdinWriteFailureIsIo() {
        val broken = object : OutputStream() { override fun write(b: Int) { throw IOException("channel is broken") } }
        assertEquals(ExecUnavailableReason.IO, reasonOf(stdin = ByteArray(10)) { Scripted(stdin = broken) })
    }

    @Test fun readFailureIsIo() {
        val broken = object : InputStream() { override fun read(): Int = throw IOException("Pipe closed") }
        assertEquals(ExecUnavailableReason.IO, reasonOf { Scripted(stdout = broken) })
    }

    @Test fun finishingAfterTheDeadlineIsATimeoutNeverAResult() {
        // connect() "hangs" for longer than the bound and then returns normally: the answer must
        // still be TIMEOUT, not an ExecResult
        val e = assertFailsWith<ExecUnavailable> { run(totalMs = 200) { Scripted(onConnect = { Thread.sleep(1_000) }) } }
        assertEquals(ExecUnavailableReason.TIMEOUT, e.reason)
        // and a failure that happens because the watchdog tore the channel down is a timeout, not IO
        val e2 = assertFailsWith<ExecUnavailable> {
            run(totalMs = 200) { Scripted(onConnect = { Thread.sleep(1_000); throw JSchException("channel is not opened.") }) }
        }
        assertEquals(ExecUnavailableReason.TIMEOUT, e2.reason)
    }

    @Test fun aCompleteRunIsAResult() {
        val r = run(stdin = ByteArray(5)) { Scripted(stdout = ByteArrayInputStream("hi".toByteArray())) }
        assertEquals("hi", r.stdout)
        assertTrue(r.exitStatus <= 0)
    }

    @Test fun runIsExecOnly() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { PooledRemoteExec(Fake { Scripted() }).run("srv", "x", kind = ExecKind.SFTP) }
        }
    }
}
