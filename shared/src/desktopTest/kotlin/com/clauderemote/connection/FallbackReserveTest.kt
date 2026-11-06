package com.clauderemote.connection

import com.clauderemote.session.BudgetFixture
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.FakeSession
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V9 (dávka B, round 2): of the [ServerTransportPool.FALLBACK_RESERVE] channels a shell transport keeps free of
 * sshd's `MaxSessions` 10, a SHORT counted channel may take one, one at a time; a LONG one (the watcher) none. So with
 * 8 shells packed on a transport the polls still run one after another (8 + 1), never two at once (8 + 2 would leave
 * nothing for the channels that are still uncounted: the connect-time probes, the UI's one-offs, install and push), and
 * the cap of 10 holds. A transfer (V12) asks like a LONG channel (`short = false`) and so never takes the reserve.
 */
class FallbackReserveTest {
    private fun pool(shells: Int): Pair<ServerTransportPool, FakeSession> {
        val pool = ServerTransportPool(ServerStorage(FakeKeyValueStore()))
        val fake = FakeSession()
        BudgetFixture.injectTransport(pool, fake, shells)
        return pool to fake
    }

    @Test fun eightShellsLendOneSlotOfTheReserveToOneShortChannelAtATime() = runBlocking {
        val (pool, s) = pool(8)
        assertTrue(pool.reserveFallbackChannel(s, short = true), "one short channel runs with 8 shells")
        assertFalse(pool.reserveFallbackChannel(s, short = true), "a second one at the same time does not: 8 + 2 would empty the reserve")
        pool.releaseChannel(s)
        assertTrue(pool.reserveFallbackChannel(s, short = true), "the next one runs once the first is done")
    }

    @Test fun aLongChannelNeverTakesTheReserve() = runBlocking {
        assertFalse(pool(8).let { (p, s) -> p.reserveFallbackChannel(s, short = false) }, "8 shells: no room for a long stream")
        val (pool, s) = pool(7)
        assertTrue(pool.reserveFallbackChannel(s, short = false), "7 shells: room for one long stream (7 + 1 + 2 = 10)")
        assertFalse(pool.reserveFallbackChannel(s, short = false), "but not for a second one")
        assertTrue(pool.reserveFallbackChannel(s, short = true), "a short one still gets the slot of the reserve")
    }

    @Test fun aShortChannelInTheReserveKeepsTheWatcherOut() = runBlocking {
        val (pool, s) = pool(6)
        assertTrue(pool.reserveFallbackChannel(s, short = true))
        assertTrue(pool.reserveFallbackChannel(s, short = true))
        assertTrue(pool.reserveFallbackChannel(s, short = true), "6 + 3: the third short channel sits in the reserve")
        assertFalse(pool.reserveFallbackChannel(s, short = true), "never two of them")
        assertFalse(pool.reserveFallbackChannel(s, short = false), "and a long stream does not squeeze in")
        assertFalse(pool.fallbackHeadroom(s, short = true))
        assertFalse(pool.fallbackSessionWithHeadroom(short = true) != null, "the look agrees with the claim")
    }

    @Test fun theLookUsesTheSameRuleAsTheClaim() {
        val (pool, s) = pool(8)
        assertTrue(pool.fallbackHeadroom(s, short = true))
        assertFalse(pool.fallbackHeadroom(s, short = false))
        assertTrue(pool.fallbackSessionWithHeadroom(short = true) === s)
        assertTrue(pool.fallbackSessionWithHeadroom(short = false) == null)
    }
}
