package com.clauderemote.connection

import com.clauderemote.model.SshServer
import com.clauderemote.session.status.SessionStatusPoller
import com.clauderemote.session.transcript.TranscriptStream
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.JSchException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With exec routing on and no exec transport, the polls of a tab fall back to the tab's own session. That channel must be
 * counted against the transport and refused without room (the tabs sit 8 to a transport and `MaxSessions` is 10): a refused
 * reservation skips the tick without opening anything, an accepted one is given back however the poll ends.
 */
class PollerFallbackRoomTest {
    private val server = SshServer(id = "s1", name = "n", host = "127.0.0.1", port = 1, username = "u")
    private val opened = AtomicInteger()
    private val reserved = AtomicInteger()
    private val released = AtomicInteger()
    private val session = FakeSession { _ -> opened.incrementAndGet(); throw JSchException("channel is not opened.") }

    @AfterTest fun reset() {
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.liveSessionProvider = null
    }

    private fun settle() = Thread.sleep(500)

    @Test fun aTranscriptPollWithoutRoomOnTheTabTransportOpensNoChannel() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val s = TranscriptStream(
                server, "/work", scope, liveSession = { session }, viaExec = { true },
                reserveTabChannel = { reserved.incrementAndGet(); false }, releaseTabChannel = { released.incrementAndGet() },
            )
            s.start("11111111-1111-1111-1111-111111111111")
            s.pollNow()
            settle()
            s.stop()
            assertTrue(reserved.get() >= 1, "the poll asked for a counted channel")
            assertEquals(0, opened.get(), "and without room it opened none")
            assertEquals(0, released.get(), "nothing was taken, nothing is given back")
        } finally { scope.cancel() }
    }

    @Test fun aTranscriptPollWithRoomOpensOneChannelAndGivesItBackEvenWhenItFails() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val s = TranscriptStream(
                server, "/work", scope, liveSession = { session }, viaExec = { true },
                reserveTabChannel = { reserved.incrementAndGet(); true }, releaseTabChannel = { released.incrementAndGet() },
            )
            s.start("11111111-1111-1111-1111-111111111111")
            s.pollNow()
            settle()
            s.stop()
            assertTrue(opened.get() >= 1, "control: with room the poll uses the tab's session")
            assertEquals(reserved.get(), released.get(), "every counted channel came back, the failed poll included")
        } finally { scope.cancel() }
    }

    @Test fun aStatusPollWithoutRoomOnTheTabTransportOpensNoChannelAndWithRoomGivesItBack() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.IO)
        try {
            val refused = SessionStatusPoller(
                server, "/work", { null }, scope, liveSession = { session }, viaExec = { true },
                reserveTabChannel = { reserved.incrementAndGet(); false }, releaseTabChannel = { released.incrementAndGet() },
            )
            refused.start()
            settle()
            refused.stop()
            assertTrue(reserved.get() >= 1)
            assertEquals(0, opened.get())
            assertEquals(0, released.get())

            reserved.set(0)
            val allowed = SessionStatusPoller(
                server, "/work", { null }, scope, liveSession = { session }, viaExec = { true },
                reserveTabChannel = { reserved.incrementAndGet(); true }, releaseTabChannel = { released.incrementAndGet() },
            )
            allowed.start()
            settle()
            allowed.stop()
            assertTrue(opened.get() >= 1, "control: with room the poller uses the tab's session")
            assertEquals(reserved.get(), released.get())
        } finally { scope.cancel() }
    }
}
