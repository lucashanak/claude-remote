package com.clauderemote.session.service

import com.clauderemote.session.BudgetFixture
import com.clauderemote.session.TabManager
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.FakeSession
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `poll skipped (no room)` ([ConnectionRegistry.noteSkipped]): the first skip of a source on a server at once, then at
 * most one line a minute per source and server with the count, and the skips counted after the last line are written
 * when the server grants a channel again after the minute (the end of a streak), so the sum of `n` is every skipped
 * tick (review round 1: the surplus was never written when the skipping stopped).
 */
class SkipLogTest {
    private val lines = CopyOnWriteArrayList<String>()
    private val savedLog = FileLogger.platformLog
    private val reg = ConnectionRegistry(ServerStorage(FakeKeyValueStore()), TabManager())
    private var now = 1_000_000L

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, _, message, _ -> if (message.startsWith("poll skipped")) lines += message }
        reg.skipClock = { now }
    }

    @AfterTest fun tearDown() {
        FileLogger.platformLog = savedLog
        CommFlags.resetForTest()
    }

    private fun n(line: String) = Regex("n=(\\d+)").find(line)!!.groupValues[1].toInt()

    @Test fun aHundredSkipsInAMinuteAreOneLineAndTheNextMinuteCarriesTheCount() {
        repeat(100) { reg.noteSkipped("transcript", "s1"); now += 500 }       // 50 s
        assertEquals(1, lines.size, "one line in the first minute: $lines")
        assertEquals(1, n(lines[0]), "the first skip is written at once")
        now += 11_000                                                        // past the minute
        reg.noteSkipped("transcript", "s1")
        assertEquals(2, lines.size)
        assertEquals(100, n(lines[1]), "the 99 counted since the first line plus this one")
    }

    @Test fun sourcesAndServersAreLimitedSeparately() {
        reg.noteSkipped("transcript", "s1")
        reg.noteSkipped("status", "s1")
        reg.noteSkipped("transcript", "s2")
        reg.noteSkipped("transcript", "s1")
        assertEquals(3, lines.size, "$lines")
    }

    @Test fun theSkipsOfAStreakThatEndedAreWrittenWhenTheServerGrantsAChannelAfterTheMinute() = runBlocking {
        val fake = FakeSession()
        BudgetFixture.injectTransport(reg.transportPool("s1"), fake, 1)
        repeat(5) { reg.noteSkipped("git", "s1") }
        assertEquals(listOf(1), lines.map(::n))
        assertNotNull(reg.reserveTabChannel("s1", fake)).release()
        assertEquals(1, lines.size, "within the minute the surplus waits (still at most one line a minute)")
        now += 61_000
        assertNotNull(reg.reserveTabChannel("s1", fake)).release()
        assertEquals(listOf(1, 4), lines.map(::n), "the 4 skips after the first line are written once the streak ended")
        assertNotNull(reg.reserveTabChannel("s1", fake)).release()
        assertEquals(2, lines.size, "nothing left to write")
        assertEquals(5, lines.sumOf(::n), "every skipped tick is in the log")
    }

    /** Round 3 (kod-spojeni): the surplus is written right after the claim; a logger that throws there must not leak the slot. */
    @Test fun aLoggerThatThrowsWhileTheSurplusIsWrittenLeaksNoSlot() = runBlocking {
        CommFlags.set(CommFlags.Raw(exec = false))                     // the kill switch with 8 packed shells: counted
        val fake = FakeSession()
        val pool = reg.transportPool("s1")
        BudgetFixture.injectTransport(pool, fake, 8)
        repeat(3) { reg.noteSkipped("git", "s1") }
        now += 61_000
        FileLogger.platformLog = { _, _, message, _ -> if (message.startsWith("poll skipped")) error("platform logger down") }
        val token = runCatching { reg.reserveTabChannel("s1", fake) }
        assertTrue(token.isSuccess, "the reservation does not fail on the logger: ${token.exceptionOrNull()}")
        token.getOrNull()?.release()
        assertTrue(pool.reserveFallbackChannel(fake, short = true), "the slot of the reserve came back (no leak)")
    }
}
