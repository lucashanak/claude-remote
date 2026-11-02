package com.clauderemote.integration

import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.ExecUnavailableReason
import com.clauderemote.session.service.Pace
import com.clauderemote.session.service.PooledExecTransportManager
import com.clauderemote.session.service.PooledRemoteExec
import com.clauderemote.session.service.UngatedExecChannels
import com.clauderemote.session.service.LinkState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Production stubs of RemoteExec / ExecChannels / ExecTransportManager against a real sshd. */
class ExecStubsIntegrationTest {
    private lateinit var fixture: SshdFixture
    private lateinit var session: com.jcraft.jsch.Session

    @Before fun boot() {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        fixture = SshdFixture.start()
        session = fixture.openRawSession()
    }

    @After fun shutdown() {
        if (::session.isInitialized) session.disconnect()
        if (::fixture.isInitialized) fixture.close()
    }

    @Test fun runReturnsStdoutAndExitStatusAndPacesStdin() = runBlocking {
        val exec = PooledRemoteExec(UngatedExecChannels { session })
        val r = exec.run("srv", "echo out; exit 3")
        assertEquals(ExecResultView("out\n", 3), ExecResultView(r.stdout, r.exitStatus))
        val payload = ByteArray(10_000) { ('a'.code + it % 26).toByte() }
        val t0 = System.nanoTime()
        val echoed = exec.run("srv", "cat; exit 0", stdin = payload, paceStdin = Pace(chunkBytes = 2048, delayMs = 20))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(String(payload), echoed.stdout)
        assertEquals(0, echoed.exitStatus)
        assertTrue(ms >= 80, "10000 B in 2048 B chunks = 4 gaps x 20 ms, took $ms ms") // pacing is real
    }

    @Test fun channelIsDisconnectedWhenTheBlockExits() = runBlocking {
        val channels = UngatedExecChannels { session }
        var seen: com.jcraft.jsch.ChannelExec? = null
        channels.use("srv", ExecKind.SHORT) { ch ->
            val exec = ch as com.jcraft.jsch.ChannelExec
            exec.setCommand("sleep 30")
            exec.connect(5000)
            assertTrue(exec.isConnected, "the block really connected the channel")
            seen = exec
        }
        assertTrue(!seen!!.isConnected, "leaving the block must disconnect the channel")
        assertTrue(seen!!.isClosed)
    }

    @Test fun withPermitHandsTheLiveSessionAndNoChannelIsOpened() = runBlocking {
        val channels = UngatedExecChannels { session }
        val got = channels.withPermit("srv", ExecKind.SHORT) { s -> s }
        assertTrue(got === session)
        assertEquals(7, channels.withPermit("srv", ExecKind.LONG) { 7 })
        val e = assertFailsWith<ExecUnavailable> { runBlocking { UngatedExecChannels { null }.withPermit("srv", ExecKind.SHORT) { 1 } } }
        assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
    }

    @Test fun timeoutIsTypedNeverAPartialResult() = runBlocking {
        val exec = PooledRemoteExec(UngatedExecChannels { session })
        val t0 = System.nanoTime()
        val e = assertFailsWith<ExecUnavailable> { exec.run("srv", "echo part; sleep 30", totalMs = 1_000) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(ExecUnavailableReason.TIMEOUT, e.reason)
        assertTrue(ms in 900..3_500, "bounded by totalMs, not by the 2 s exit-status wait or the 30 s sleep: $ms ms")
        // the session is still usable afterwards
        assertEquals("ok\n", exec.run("srv", "echo ok").stdout)
    }

    @Test fun callerCancellationDoesNotLiftTheWallClockBound() = runBlocking {
        val exec = PooledRemoteExec(UngatedExecChannels { session })
        val t0 = System.nanoTime()
        val job = async(start = CoroutineStart.UNDISPATCHED) { runCatching { exec.run("srv", "sleep 8", totalMs = 2_000) } }
        delay(300)
        job.cancelAndJoin()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 4_500, "cancelled caller must be released by the watchdog at ~2 s, not after the 8 s sleep: $ms ms")
    }

    @Test fun sftpKindIsRejectedByRun() {
        val exec = PooledRemoteExec(UngatedExecChannels { session })
        assertFailsWith<IllegalArgumentException> { runBlocking { exec.run("srv", "true", kind = ExecKind.SFTP) } }
    }

    @Test fun noSessionIsTypedNotUpNeverNull() {
        val exec = PooledRemoteExec(UngatedExecChannels { null })
        val e = assertFailsWith<ExecUnavailable> { runBlocking { exec.run("srv", "true") } }
        assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
    }

    @Test fun transportManagerStubDelegatesAndBumpsGeneration() = runBlocking {
        val m = PooledExecTransportManager { session }
        assertTrue(m.session("srv") === session)
        assertTrue(m.await("srv", 100) === session)
        assertTrue(m.state("srv").value is LinkState.Up)
        val g = m.generation("srv")
        m.close("srv")
        assertEquals(g + 1, m.generation("srv"))
        m.setKeepAlive(30_000)
        assertEquals(30_000, m.keepAliveMs)
        val none = PooledExecTransportManager { null }
        assertNull(none.await("srv", 150))
        assertEquals(LinkState.Down, none.state("srv").value)
    }

    private data class ExecResultView(val out: String, val rc: Int)
}
