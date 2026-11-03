package com.clauderemote.connection

import com.clauderemote.model.SshServer
import com.clauderemote.session.service.ExecChannels
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.ExecUnavailableReason
import com.clauderemote.session.status.SessionStatusPoller
import com.clauderemote.session.transcript.TranscriptStream
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * With exec routing active nothing may open a TCP connection of its own: the server here is a
 * bare loopback listener that counts accepted connections. Every "0 connections" assertion has
 * a control that proves the same setup DOES count a connection when a fresh one is allowed.
 */
class ExecNoFreshConnectionTest {
    private val accepted = AtomicInteger()
    private val listener = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    private val acceptor = Thread {
        try { while (true) { listener.accept().also { accepted.incrementAndGet(); it.close() } } } catch (_: Exception) {}
    }.also { it.isDaemon = true; it.start() }
    private val server = SshServer(id = "s1", name = "n", host = "127.0.0.1", port = listener.localPort, username = "u")

    @AfterTest fun close() {
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.liveSessionProvider = null
        listener.close()
    }

    private fun settle() = Thread.sleep(700)

    @Test fun withSessionAllowFreshFalseThrowsNotUpAndConnectsNothing() = runBlocking {
        val e = assertFailsWith<ExecUnavailable> { SshSessionHelper.withSession(server, timeout = 2_000, allowFresh = false) { 1 } }
        assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
        settle()
        assertEquals(0, accepted.get())
        // control: allowed to open a fresh connection, it does (and fails the handshake here)
        runCatching { SshSessionHelper.withSession(server, timeout = 1_500) { 1 } }
        settle()
        assertTrue(accepted.get() >= 1, "control: a fresh connection must be visible to the listener")
    }

    @Test fun transcriptPollWithExecRoutingSkipsTheTickInsteadOfConnecting() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val s = TranscriptStream(server, "/work", scope, liveSession = { null }, viaExec = { true })
            s.start("11111111-1111-1111-1111-111111111111")
            s.pollNow()
            settle()
            s.stop()
            assertEquals(0, accepted.get())
            val legacy = TranscriptStream(server, "/work", scope, liveSession = { null }, viaExec = { false })
            legacy.start("22222222-2222-2222-2222-222222222222")
            settle(); settle()
            legacy.stop()
            assertTrue(accepted.get() >= 1, "control: without exec routing the poll opens its own connection")
        } finally { scope.cancel() }
    }

    @Test fun statusPollWithExecRoutingNeverConnects() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val p = SessionStatusPoller(server, "/work", { null }, scope, liveSession = { null }, viaExec = { true })
            p.start()
            settle(); settle()
            p.stop()
            assertEquals(0, accepted.get())
            val legacy = SessionStatusPoller(server, "/work", { null }, scope, liveSession = { null }, viaExec = { false })
            legacy.start()
            settle(); settle()
            legacy.stop()
            assertTrue(accepted.get() >= 1, "control: without exec routing the poller opens its own connection")
        } finally { scope.cancel() }
    }

    private class FakePermits(val session: Session) : ExecChannels {
        var inside = false
        val kinds = mutableListOf<ExecKind>()
        override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T =
            throw UnsupportedOperationException()
        override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (Session) -> T): T {
            kinds += kind
            inside = true
            try { return block(session) } finally { inside = false }
        }
    }

    @Test fun withSessionHoldsOneShortPermitOnTheExecSessionForTheBlock() = runBlocking {
        val sess = JSch().getSession("u", "localhost", 22)
        val permits = FakePermits(sess)
        SshSessionHelper.execChannelsProvider = { permits }
        var sawPermit = false
        val got = SshSessionHelper.withSession(server, allowFresh = false) { s -> sawPermit = permits.inside; s }
        assertSame(sess, got)
        assertTrue(sawPermit, "the block ran while the permit was held")
        assertTrue(!permits.inside, "released afterwards")
        assertEquals(listOf(ExecKind.SHORT), permits.kinds)
        assertEquals(0, accepted.get())
    }

    @Test fun anExecUnavailableRaisedByTheBlockIsNotRetriedOnAnotherTransport() = runBlocking {
        val permits = FakePermits(JSch().getSession("u", "localhost", 22))
        SshSessionHelper.execChannelsProvider = { permits }
        var runs = 0
        assertFailsWith<ExecUnavailable> {
            SshSessionHelper.withSession(server) { runs++; throw ExecUnavailable(ExecUnavailableReason.NOT_UP) }
        }
        assertEquals(1, runs)
        settle()
        assertEquals(0, accepted.get())
    }

    @Test fun aSaturatedGateIsNotWalkedAroundByAnotherTransport() = runBlocking {
        val gate = object : ExecChannels {
            override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T = throw UnsupportedOperationException()
            override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (Session) -> T): T =
                throw ExecUnavailable(ExecUnavailableReason.GATE_TIMEOUT)
        }
        SshSessionHelper.execChannelsProvider = { gate }
        SshSessionHelper.liveSessionProvider = { JSch().getSession("u", "localhost", 22).also { s -> markConnected(s) } }
        var ran = false
        val e = assertFailsWith<ExecUnavailable> { SshSessionHelper.withSession(server) { ran = true; 1 } }
        assertEquals(ExecUnavailableReason.GATE_TIMEOUT, e.reason)
        assertTrue(!ran, "the block must not run on a pooled transport without a permit")
        settle()
        assertEquals(0, accepted.get(), "and no fresh connection either")
    }

    /** A jsch Session that reports connected, without any network (the field is private). */
    private fun markConnected(s: Session) {
        Session::class.java.getDeclaredField("isConnected").also { it.isAccessible = true }.setBoolean(s, true)
    }

    @Test fun whenTheGateHasNoSessionTheLegacyOrderApplies() = runBlocking {
        val gate = object : ExecChannels {
            override suspend fun <T> use(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (com.jcraft.jsch.Channel) -> T): T = throw UnsupportedOperationException()
            override suspend fun <T> withPermit(serverId: String, kind: ExecKind, acquireTimeoutMs: Long, block: suspend (Session) -> T): T =
                throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
        }
        SshSessionHelper.execChannelsProvider = { gate }
        val e = assertFailsWith<ExecUnavailable> { SshSessionHelper.withSession(server, allowFresh = false) { 1 } }
        assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
    }
}
