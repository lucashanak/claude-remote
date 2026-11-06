package com.clauderemote.connection

import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.TabChannel
import com.clauderemote.session.service.TabChannels
import com.clauderemote.session.status.SessionStatusPoller
import com.clauderemote.session.transcript.TranscriptStream
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The legacy branches of the tab pollers (exec routing off: the kill switch, a legacy server). With shells still
 * packed on a transport their channel on the tab's own session must be counted and skipped without room (NN2), and
 * with `comm_pool` on a tab without a live session must not open a fresh SSH connection per poll (V6): 50 tabs x one
 * poll would be a handshake storm outside the pool's limit of 3 handshakes per server.
 */
class LegacyPollerBudgetTest {
    private val accepted = AtomicInteger()
    private val listener = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    private val acceptor = Thread {
        try { while (true) { listener.accept().also { accepted.incrementAndGet(); it.close() } } } catch (_: Exception) {}
    }.also { it.isDaemon = true; it.start() }
    private val server = SshServer(id = "s1", name = "n", host = "127.0.0.1", port = listener.localPort, username = "u")
    private val opened = AtomicInteger()
    private val reserved = AtomicInteger()
    private val released = AtomicInteger()
    private val session = FakeSession { _ -> opened.incrementAndGet(); throw JSchException("channel is not opened.") }
    private val skipped = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun budget(room: Boolean) = object : TabChannels {
        override suspend fun reserve(session: Session): TabChannel? {
            reserved.incrementAndGet()
            return if (room) TabChannel(session) { released.incrementAndGet() } else null
        }
        override fun skipped(source: String) { skipped += source }
    }

    @BeforeTest fun setUp() = CommFlags.resetForTest()

    @AfterTest fun tearDown() {
        CommFlags.resetForTest()
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.liveSessionProvider = null
        listener.close()
    }

    private fun settle() = Thread.sleep(700)

    @Test fun aLegacyTranscriptPollWithoutRoomOnItsTransportOpensNoChannel() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val s = TranscriptStream(server, "/work", scope, liveSession = { session }, viaExec = { false }, tabChannels = budget(room = false))
            s.start("11111111-1111-1111-1111-111111111111")
            s.pollNow()
            settle()
            s.stop()
            assertTrue(reserved.get() >= 1, "the legacy poll asked for a counted channel")
            assertEquals(0, opened.get(), "and without room it opened none")
            assertTrue("transcript" in skipped, "the skipped tick is reported: $skipped")
        } finally { scope.cancel() }
    }

    @Test fun aLegacyTranscriptPollWithRoomGivesItsChannelBackEvenWhenItFails() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val s = TranscriptStream(server, "/work", scope, liveSession = { session }, viaExec = { false }, tabChannels = budget(room = true))
            s.start("11111111-1111-1111-1111-111111111111")
            s.pollNow()
            settle()
            s.stop()
            assertTrue(opened.get() >= 1, "control: with room the legacy poll uses the tab's session")
            assertEquals(reserved.get(), released.get())
        } finally { scope.cancel() }
    }

    @Test fun aLegacyStatusPollWithoutRoomOpensNoChannelAndWithRoomGivesItBack() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val refused = SessionStatusPoller(server, "/work", { null }, scope, liveSession = { session }, viaExec = { false }, tabChannels = budget(room = false))
            refused.start(); settle(); refused.stop()
            assertTrue(reserved.get() >= 1)
            assertEquals(0, opened.get())
            assertTrue("status" in skipped, "the skipped tick is reported: $skipped")
            reserved.set(0)
            val allowed = SessionStatusPoller(server, "/work", { null }, scope, liveSession = { session }, viaExec = { false }, tabChannels = budget(room = true))
            allowed.start(); settle(); allowed.stop()
            assertTrue(opened.get() >= 1, "control: with room the poller uses the tab's session")
            assertEquals(reserved.get(), released.get())
        } finally { scope.cancel() }
    }

    @Test fun aLegacyTranscriptPollWithoutALiveSessionOpensNoFreshConnectionWhileThePoolIsOn() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val s = TranscriptStream(server, "/work", scope, liveSession = { null }, viaExec = { false })
            s.start("11111111-1111-1111-1111-111111111111")
            s.pollNow()
            settle(); settle()
            s.stop()
            assertEquals(0, accepted.get(), "comm_pool on: no handshake of its own outside the pool")
            CommFlags.set(CommFlags.Raw(pool = false, exec = false))
            val legacy = TranscriptStream(server, "/work", scope, liveSession = { null }, viaExec = { false })
            legacy.start("22222222-2222-2222-2222-222222222222")
            settle(); settle()
            legacy.stop()
            assertTrue(accepted.get() >= 1, "control: with the pool off the poll still opens its own connection, as before")
        } finally { scope.cancel() }
    }

    @Test fun aLegacyStatusPollWithoutALiveSessionOpensNoFreshConnectionWhileThePoolIsOn() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val p = SessionStatusPoller(server, "/work", { null }, scope, liveSession = { null }, viaExec = { false })
            p.start(); settle(); settle(); p.stop()
            assertEquals(0, accepted.get(), "comm_pool on: no handshake of its own outside the pool")
            CommFlags.set(CommFlags.Raw(pool = false, exec = false))
            val legacy = SessionStatusPoller(server, "/work", { null }, scope, liveSession = { null }, viaExec = { false })
            legacy.start(); settle(); settle(); legacy.stop()
            assertTrue(accepted.get() >= 1, "control: with the pool off the poller still opens its own connection")
        } finally { scope.cancel() }
    }
}
