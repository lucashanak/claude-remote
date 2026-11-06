package com.clauderemote.session.service

import com.clauderemote.connection.ServerTransportPool
import com.clauderemote.model.SshServer
import com.clauderemote.session.BudgetFixture
import com.clauderemote.session.TabManager
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The counted channel a tab's poller takes on its own transport ([ConnectionRegistry.reserveTabChannel]) is a token:
 * releasing it gives back exactly what that reservation took, once, whatever else happens on the same session at the
 * same time. A per-session counter (the old shape) gave back a slot another reservation still held, and a release
 * racing a reserve could drop the count of the other one for good (a permanent leak of a slot).
 */
class TabChannelTokenTest {
    private val storage = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(id = "s1", name = "n", host = "127.0.0.1", port = 1, username = "u")
    private val reg = ConnectionRegistry(storage, TabManager())
    private val fake = FakeSession()
    private lateinit var pool: ServerTransportPool

    private object NoExec : ExecSessionProvider {
        override fun session(serverId: String): Session? = null
        override suspend fun await(serverId: String, ms: Long): Session? = null
    }
    private object NoRemote : RemoteExec {
        override suspend fun run(serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace): ExecResult =
            throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
    }

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        pool = reg.transportPool(server.id)
        BudgetFixture.injectTransport(pool, fake, 1)
        routing(true)
    }

    @AfterTest fun tearDown() = CommFlags.resetForTest()

    /** Exec routing on = every tab channel of the server must be counted; off (1 shell, not packed) = nothing is. */
    private fun routing(on: Boolean) {
        reg.execSessions = if (on) NoExec else null
        reg.remoteExec = if (on) NoRemote else null
    }

    /** Counted channels the transport can still take (measured by taking them all and giving them back). */
    private suspend fun freeSlots(): Int {
        var n = 0
        while (pool.reserveFallbackChannel(fake)) n++
        repeat(n) { pool.releaseChannel(fake) }
        return n
    }

    @Test fun releasingOneTokenTwiceGivesBackOnlyItsOwnSlot() = runBlocking {
        val free = freeSlots()
        val a = assertNotNull(reg.reserveTabChannel(server.id, fake))
        val b = assertNotNull(reg.reserveTabChannel(server.id, fake))
        assertEquals(free - 2, freeSlots())
        a.release()
        a.release()
        assertEquals(free - 1, freeSlots(), "b is still running: the second release of a must not give back b's slot")
        b.release()
        assertEquals(free, freeSlots())
    }

    @Test fun aReservationTakenWithoutCountingGivesNothingBackWhenTheRoomCheckTurnedOnMeanwhile() = runBlocking {
        val free = freeSlots()
        val counted = assertNotNull(reg.reserveTabChannel(server.id, fake))     // routing on: counted
        routing(false)
        val uncounted = assertNotNull(reg.reserveTabChannel(server.id, fake))   // routing off: nothing counted
        uncounted.release()
        assertEquals(free - 1, freeSlots(), "the counted one is still running: its slot must stay taken")
        counted.release()
        assertEquals(free, freeSlots())
        routing(true)
        val c2 = assertNotNull(reg.reserveTabChannel(server.id, fake))
        routing(false)
        c2.release()
        assertEquals(free, freeSlots(), "a counted token gives its slot back even after the room check went off")
    }

    @Test fun twoPollersReservingAndReleasingConcurrentlyLeaveTheBalanceAtZero() = runBlocking {
        val free = freeSlots()
        (1..2).map {
            async(Dispatchers.Default) {
                repeat(10_000) { reg.reserveTabChannel(server.id, fake)?.release() }
            }
        }.awaitAll()
        assertEquals(free, freeSlots(), "every slot taken was given back exactly once")
    }

    @Test fun withoutRoomThereIsNoToken() = runBlocking {
        val held = generateSequence { runBlocking { reg.reserveTabChannel(server.id, fake) } }.take(100).toList()
        val n = held.size
        assertEquals(null, reg.reserveTabChannel(server.id, fake), "after $n reservations the transport is full")
        held.forEach { it.release() }
    }
}
