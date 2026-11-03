package com.clauderemote.session.service

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `PooledRemoteExec.run` against scripted channels: the wall-clock, partial-result,
 * typed-failure and spill rules that need no sshd. (The same rules against a real sshd are in
 * PoolV2IntegrationTest.)
 */
class RemoteExecRunTest {
    private class Scripted(
        private val stdout: String = "",
        private val onConnect: (Scripted) -> Unit = {},
        private val sessionOf: () -> Session? = { null },
        @Volatile var exit: Int = -1,
        @Volatile var closed: Boolean = false,
        @Volatile var eof: Boolean = false,
        private val onWrite: (Scripted, Int) -> Unit = { _, _ -> },
        /** The output stream blocks (like a parked jsch read) until the channel is disconnected. */
        private val blockRead: Boolean = false,
    ) : ChannelExec() {
        val connectArgs = mutableListOf<Int>()
        val disconnects = AtomicInteger()
        val written = ByteArrayOutputStream()
        val writes = AtomicInteger()

        override fun connect(connectTimeout: Int) { connectArgs += connectTimeout; onConnect(this) }
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                written.write(b, off, len); onWrite(this@Scripted, writes.incrementAndGet())
            }
        }
        override fun getInputStream(): InputStream =
            if (!blockRead) ByteArrayInputStream(stdout.toByteArray())
            else object : InputStream() {
                override fun read(): Int { while (disconnects.get() == 0) Thread.sleep(5); return -1 }
            }
        override fun setInputStream(`in`: InputStream?) {}
        override fun getExitStatus(): Int = exit
        override fun isClosed(): Boolean = closed
        override fun isEOF(): Boolean = eof
        override fun getSession(): Session = sessionOf() ?: throw JSchException("session is not available")
        override fun disconnect() { disconnects.incrementAndGet() }
    }

    /** [permitWaitMs] is how long getting the permit took (the block starts only after it). */
    private class Fake(private val permitWaitMs: Long = 0, private val make: () -> Channel) : ExecChannels {
        val opened = AtomicInteger()
        val acquireTimeouts = mutableListOf<Long>()
        override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (Channel) -> T): T {
            acquireTimeouts += acquireTimeoutMs
            if (permitWaitMs > 0) delay(permitWaitMs)
            opened.incrementAndGet(); return block(make())
        }
        override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (Session) -> T): T = error("unused")
    }

    private fun exec(fake: Fake, retryDelayMs: Long = 5) = PooledRemoteExec(fake, refusedRetryDelayMs = retryDelayMs)

    // ---- the connect never waits longer than what is left of the bound ----

    @Test fun connectWaitsAtMostWhatIsLeftOfTheBound() {
        val tight = Scripted(stdout = "x", exit = 0)
        runBlocking { exec(Fake { tight }).run("s", "c", totalMs = 1_200) }
        assertTrue(tight.connectArgs.single() in 900..1_200, "connect timeout ${tight.connectArgs} must be ~1200, not the fixed 5000")
        val roomy = Scripted(stdout = "x", exit = 0)
        runBlocking { exec(Fake { roomy }).run("s", "c", totalMs = 60_000) }
        assertEquals(listOf(5_000), roomy.connectArgs, "a roomy bound still caps the connect at 5 s")
    }

    // ---- the wall-clock bound covers the wait for a permit too ----

    @Test fun theWaitForAPermitIsBoundedByWhatIsLeftOfTheTotal() {
        val fake = Fake { Scripted(stdout = "x", exit = 0) }
        runBlocking { exec(fake).run("s", "c", totalMs = 1_200) }
        runBlocking { exec(fake).run("s", "c", totalMs = 60_000) }
        assertTrue(fake.acquireTimeouts[0] in 1..1_200, "acquire timeout ${fake.acquireTimeouts[0]} must fit the 1200 ms bound")
        assertEquals(5_000L, fake.acquireTimeouts[1], "a roomy bound still waits at most 5 s for a permit")
    }

    @Test fun timeSpentWaitingForAPermitIsNotGivenToTheCommandAgain() {
        // 600 ms of a 900 ms bound go on the permit; the command then runs against a read that never returns
        val ch = Scripted(blockRead = true, sessionOf = liveSession)
        val t0 = System.nanoTime()
        val e = assertFailsWith<ExecUnavailable> { runBlocking { exec(Fake(permitWaitMs = 600) { ch }).run("s", "c", totalMs = 900) } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(ExecUnavailableReason.TIMEOUT, e.reason)
        assertTrue(ms in 800..1_300, "the whole call fits the 900 ms bound, not 600 + 900 (took $ms ms)")
        assertTrue(ch.connectArgs.single() <= 320, "connect gets what was left, ${ch.connectArgs}")
    }

    @Test fun aPermitThatArrivesAfterTheBoundIsATimeoutWithoutConnecting() {
        val ch = Scripted(stdout = "x", exit = 0)
        val e = assertFailsWith<ExecUnavailable> { runBlocking { exec(Fake(permitWaitMs = 500) { ch }).run("s", "c", totalMs = 300) } }
        assertEquals(ExecUnavailableReason.TIMEOUT, e.reason)
        assertTrue(ch.connectArgs.isEmpty(), "no channel connect after the bound")
    }

    // ---- a partial result is never returned when the transport died under the command ----

    private val deadSession: () -> Session = { FakeSession(connected = false) }
    private val liveSession: () -> Session = { FakeSession(connected = true) }

    @Test fun outputWithoutAStatusOnADeadTransportIsIoNotAResult() {
        val e = assertFailsWith<ExecUnavailable> {
            runBlocking { exec(Fake { Scripted(stdout = "partial output", sessionOf = deadSession, exit = -1) }).run("s", "c", totalMs = 5_000) }
        }
        assertEquals(ExecUnavailableReason.IO, e.reason, e.message)
    }

    @Test fun noStatusOnALiveTransportStillReturnsTheOutputWithMinusOne() {
        val r = runBlocking { exec(Fake { Scripted(stdout = "killed by a signal", sessionOf = liveSession, exit = -1) }).run("s", "c", totalMs = 5_000) }
        assertEquals("killed by a signal", r.stdout)
        assertEquals(-1, r.exitStatus)
    }

    @Test fun aReportedStatusIsAResultEvenIfTheTransportDiedRightAfter() {
        val r = runBlocking { exec(Fake { Scripted(stdout = "done", sessionOf = deadSession, exit = 0) }).run("s", "c", totalMs = 5_000) }
        assertEquals(ExecResult("done", 0), r)
    }

    // ---- a failing lookup or a wrong channel type is a typed failure, not a stray exception ----

    @Test fun aThrowingSessionLookupIsNotUp() {
        val e = assertFailsWith<ExecUnavailable> {
            runBlocking { PooledRemoteExec(UngatedExecChannels { error("registry exploded") }).run("s", "c") }
        }
        assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
    }

    @Test fun aChannelThatIsNotAnExecChannelIsIoNotAClassCastException() {
        val e = assertFailsWith<ExecUnavailable> { runBlocking { exec(Fake { ChannelSftp() }).run("s", "c") } }
        assertEquals(ExecUnavailableReason.IO, e.reason, e.message)
    }

    @Test fun aFailingStreamGetterIsTypedToo() {
        val broken = object : ChannelExec() {
            override fun getOutputStream(): OutputStream = throw java.io.IOException("no stdin pipe")
            override fun disconnect() {}
        }
        val e = assertFailsWith<ExecUnavailable> { runBlocking { exec(Fake { broken }).run("s", "c", stdin = ByteArray(3)) } }
        assertEquals(ExecUnavailableReason.IO, e.reason)
    }

    // ---- paced stdin stops once the remote side is gone ----

    @Test fun stdinIsNotFedToAChannelThatIsAlreadyClosed() {
        val ch = Scripted(stdout = "", exit = 0, closed = true, sessionOf = liveSession)
        val t0 = System.nanoTime()
        runBlocking { exec(Fake { ch }).run("s", "c", stdin = ByteArray(1_000_000), paceStdin = Pace(4096, 20)) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(0, ch.writes.get(), "nothing written to a closed channel")
        assertTrue(ms < 1_500, "must not sit through 245 paced chunks (took $ms ms)")
    }

    @Test fun stdinStopsRightAfterTheRemoteSideClosesMidway() {
        val ch = Scripted(stdout = "", exit = 0, sessionOf = liveSession, onWrite = { c, n -> if (n == 2) c.eof = true })
        runBlocking { exec(Fake { ch }).run("s", "c", stdin = ByteArray(100_000), paceStdin = Pace(1024, 1)) }
        assertEquals(2, ch.writes.get(), "stopped after the write that saw the remote close")
    }

    // ---- the watchdog is cancelled when the command finishes ----

    @Test fun theWatchdogDoesNotFireAfterTheCommandFinished() {
        val ch = Scripted(stdout = "ok", exit = 0, sessionOf = liveSession)
        runBlocking { exec(Fake { ch }).run("s", "c", totalMs = 300) }
        val after = ch.disconnects.get()
        Thread.sleep(700)    // well past the 300 ms bound
        assertEquals(after, ch.disconnects.get(), "a leaked watchdog would disconnect the channel again at 300 ms")
    }

    // ---- spill: one retry of a MaxSessions refusal, nothing else is retried ----

    private fun refusing(times: Int, exitStatus: Int): () -> Scripted {
        val n = AtomicInteger()
        return { Scripted(stdout = "fine", sessionOf = liveSession, exit = exitStatus.takeIf { n.get() < times } ?: 0,
            onConnect = { if (n.incrementAndGet() <= times) throw JSchException("channel is not opened.") }) }
    }

    @Test fun aRefusalWithStatusTwoIsRetriedOnceAndThenSucceeds() {
        val fake = Fake(make = refusing(times = 1, exitStatus = 2))
        val r = runBlocking { exec(fake).run("s", "c", totalMs = 5_000) }
        assertEquals("fine", r.stdout)
        assertEquals(2, fake.opened.get(), "exactly one retry")
    }

    @Test fun aRefusalWithStatusOneIsAlsoSpillable() {
        val fake = Fake(make = refusing(times = 1, exitStatus = 1))
        assertEquals("fine", runBlocking { exec(fake).run("s", "c", totalMs = 5_000) }.stdout)
    }

    @Test fun aSecondRefusalSurfacesAsRefusedAfterExactlyTwoAttempts() {
        val fake = Fake(make = refusing(times = 5, exitStatus = 2))
        val e = assertFailsWith<ExecUnavailable> { runBlocking { exec(fake).run("s", "c", totalMs = 5_000) } }
        assertEquals(ExecUnavailableReason.REFUSED, e.reason)
        assertEquals(2, fake.opened.get())
    }

    @Test fun aRefusalWithStatusThreeIsNotRetried() {
        val fake = Fake(make = refusing(times = 5, exitStatus = 3))
        val e = assertFailsWith<ExecUnavailable> { runBlocking { exec(fake).run("s", "c", totalMs = 5_000) } }
        assertEquals(ExecUnavailableReason.REFUSED, e.reason)
        assertEquals(1, fake.opened.get(), "only the server's own 1/2 statuses mean MaxSessions")
    }

    @Test fun aRefusalWithoutAServerStatusIsNotRetried() {
        // exitStatus -1: the channel never heard from the server (transport trouble), not a MaxSessions refusal
        val fake = Fake(make = refusing(times = 5, exitStatus = -1))
        val e = assertFailsWith<ExecUnavailable> { runBlocking { exec(fake).run("s", "c", totalMs = 5_000) } }
        assertEquals(ExecUnavailableReason.REFUSED, e.reason)
        assertEquals(1, fake.opened.get(), "no spill without exitStatus 1/2")
    }

    @Test fun theRetryStaysInsideTheWallClockBound() {
        val fake = Fake(make = refusing(times = 5, exitStatus = 2))
        val t0 = System.nanoTime()
        val e = assertFailsWith<ExecUnavailable> { runBlocking { exec(fake, retryDelayMs = 400).run("s", "c", totalMs = 300) } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(ExecUnavailableReason.TIMEOUT, e.reason, "no time left for the retry => TIMEOUT, not a second attempt")
        assertTrue(ms < 1_500, "bounded: $ms ms")
    }
}
