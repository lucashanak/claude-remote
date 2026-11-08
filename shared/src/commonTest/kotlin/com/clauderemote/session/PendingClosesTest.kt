package com.clauderemote.session

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The undo window of a tab close (Jarvis F0a): [PendingCloses.forget] — the irreversible half — must run exactly
 * once when a window ends and never when it was undone, blocked or dropped. Virtual time throughout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingClosesTest {
    private val server = SshServer(id = "s1", name = "one", host = "unreachable.invalid", username = "u")

    private fun tab(id: String) = ClaudeSession(
        id = id, server = server, folder = "/work/$id", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-server-$id", connectionType = ConnectionType.SSH,
    )

    private class Harness(val closes: PendingCloses, val forgotten: MutableList<String>)

    private fun TestScope.harness(
        forget: suspend (String) -> Unit = {},
        check: suspend (ClaudeSession) -> CloseCheck? = { null },
    ): Harness {
        val forgotten = mutableListOf<String>()
        val closes = PendingCloses(
            scope = backgroundScope,
            forget = { id -> forgotten += id; forget(id) },
            closeCheck = check,
            now = { testScheduler.currentTime },
        )
        return Harness(closes, forgotten)
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test fun theCloseHappensExactlyOnceWhenTheWindowEnds() = runTest {
        val h = harness()
        assertTrue(h.closes.close(tab("a"), wasActive = true))
        assertEquals(setOf("a"), h.closes.state.value.hiddenIds)
        advance(9_999)
        assertEquals(emptyList(), h.forgotten, "nothing happens inside the window")
        advance(1)
        assertEquals(listOf("a"), h.forgotten)
        assertTrue(h.closes.state.value.isEmpty)
        advance(60_000)
        assertEquals(listOf("a"), h.forgotten, "fires once")
    }

    @Test fun undoMeansTheCloseNeverHappens() = runTest {
        val h = harness()
        h.closes.close(tab("a"), wasActive = true)
        advance(5_000)
        val undone = h.closes.undo("a")
        assertEquals("a", undone?.sessionId)
        assertTrue(undone!!.wasActive, "undo hands back whether the tab was on screen")
        assertTrue(h.closes.state.value.hiddenIds.isEmpty(), "the tab is visible again")
        advance(60_000)
        assertEquals(emptyList(), h.forgotten)
        assertNull(h.closes.undo("a"), "a second undo is a no-op")
    }

    @Test fun aSecondTapNeitherDuplicatesNorRestartsTheWindow() = runTest {
        val h = harness()
        assertTrue(h.closes.close(tab("a")))
        advance(6_000)
        assertFalse(h.closes.close(tab("a")))
        advance(4_000)
        assertEquals(listOf("a"), h.forgotten, "still on the FIRST tap's deadline")
        advance(60_000)
        assertEquals(listOf("a"), h.forgotten)
    }

    @Test fun closingAgainAfterUndoStartsAFreshWindowAndTheOldTimerStaysDead() = runTest {
        val h = harness()
        h.closes.close(tab("a"))
        advance(8_000)
        h.closes.undo("a")
        advance(1_000)
        h.closes.close(tab("a"))
        advance(2_000) // past the first close's deadline (10 s)
        assertEquals(emptyList(), h.forgotten)
        advance(8_000) // 10 s after the second close
        assertEquals(listOf("a"), h.forgotten)
    }

    @Test fun everyCloseHasItsOwnDeadline() = runTest {
        val h = harness()
        h.closes.close(tab("a"))
        advance(3_000)
        h.closes.close(tab("b"))
        assertEquals(2, h.closes.state.value.pending.size)
        advance(7_000)
        assertEquals(listOf("a"), h.forgotten)
        assertEquals(listOf("b"), h.closes.state.value.pending.map { it.sessionId })
        advance(3_000)
        assertEquals(listOf("a", "b"), h.forgotten)
    }

    @Test fun undoAllBringsEveryPendingTabBack() = runTest {
        val h = harness()
        h.closes.close(tab("a"))
        h.closes.close(tab("b"), wasActive = true)
        h.closes.close(tab("c"))
        val undone = h.closes.undoAll()
        assertEquals(listOf("a", "b", "c"), undone.map { it.sessionId })
        assertTrue(h.closes.state.value.isEmpty)
        advance(60_000)
        assertEquals(emptyList(), h.forgotten)
    }

    @Test fun flushClosesEverythingNowAndTheTimersNeverFireAgain() = runTest {
        val serverSide = CompletableDeferred<Unit>()
        val h = harness(forget = { serverSide.await() })
        h.closes.close(tab("a"))
        h.closes.close(tab("b"))
        advance(1_000)
        val flush = h.closes.flush()
        runCurrent()
        assertEquals(setOf("a", "b"), h.forgotten.toSet(), "both run at once, without waiting for each other")
        assertTrue(h.closes.state.value.isEmpty)
        assertFalse(flush.isCompleted, "flush waits for the closes' server side")
        serverSide.complete(Unit)
        runCurrent()
        assertTrue(flush.isCompleted)
        advance(60_000)
        assertEquals(2, h.forgotten.size)
    }

    @Test fun flushAlsoWaitsForACloseWhoseWindowAlreadyEnded() = runTest {
        val serverSide = CompletableDeferred<Unit>()
        val h = harness(forget = { serverSide.await() })
        h.closes.close(tab("a"))
        advance(10_000)
        assertEquals(listOf("a"), h.forgotten) // running, server side not done
        val flush = h.closes.flush()
        runCurrent()
        assertFalse(flush.isCompleted, "an exit must not cut the running close off")
        serverSide.complete(Unit)
        runCurrent()
        assertTrue(flush.isCompleted)
        assertEquals(listOf("a"), h.forgotten)
    }

    /**
     * Leaving the app while a facts check still runs: flush waits for it (up to its limit), so a BLOCK about to arrive
     * still keeps the tab; a check that does not answer in time closes, as at the deadline. Nothing blocks the caller.
     */
    @Test fun flushWaitsBrieflyForARunningCheckAndHonoursItsBlock() = runTest {
        val answers = mutableMapOf<String, CompletableDeferred<CloseCheck?>>()
        val h = harness(check = { s -> answers.getOrPut(s.id) { CompletableDeferred() }.await() })
        h.closes.close(tab("busy"))
        h.closes.close(tab("clear"))
        h.closes.close(tab("silent"))
        runCurrent()
        val flush = h.closes.flush()
        runCurrent()
        assertEquals(emptyList(), h.forgotten, "no close before the checks have had their chance")
        assertEquals(setOf("busy", "clear", "silent"), h.closes.state.value.hiddenIds, "still hidden meanwhile")
        answers.getValue("busy").complete(CloseCheck.Block("běží tah"))
        answers.getValue("clear").complete(CloseCheck.Clear)
        advance(PendingCloses.FLUSH_CHECK_WAIT_MS - 1)
        assertEquals(emptyList(), h.forgotten)
        advance(1)
        assertEquals(setOf("clear", "silent"), h.forgotten.toSet(), "a BLOCK keeps its tab, an unanswered check closes")
        assertEquals(listOf("busy"), h.closes.state.value.blocked.map { it.sessionId })
        assertTrue(flush.isCompleted)
        advance(60_000)
        assertEquals(2, h.forgotten.size, "no timer fires one of them again")
    }

    @Test fun flushDoesNotWaitWhenEveryCheckHasAnswered() = runTest {
        val h = harness(check = { CloseCheck.Warn("1 necommitnutý soubor") })
        h.closes.close(tab("a"))
        runCurrent()
        h.closes.flush()
        runCurrent()
        assertEquals(listOf("a"), h.forgotten)
    }

    @Test fun anUndoDuringTheFlushWaitKeepsTheTab() = runTest {
        val h = harness(check = { awaitCancellation() })
        h.closes.close(tab("a"))
        runCurrent()
        h.closes.flush()
        runCurrent()
        assertEquals("a", h.closes.undo("a")?.sessionId, "the user is back within the wait")
        advance(PendingCloses.FLUSH_CHECK_WAIT_MS)
        assertEquals(emptyList(), h.forgotten)
    }

    @Test fun flushWithNothingPendingIsImmediate() = runTest {
        val h = harness()
        val flush = h.closes.flush()
        runCurrent()
        assertTrue(flush.isCompleted)
        assertEquals(emptyList(), h.forgotten)
    }

    @Test fun aBlockCancelsTheCloseAndBringsTheTabBack() = runTest {
        val h = harness(check = { delay(2_000); CloseCheck.Block("běží tah") })
        h.closes.close(tab("a"))
        advance(2_000)
        val s = h.closes.state.value
        assertTrue(s.hiddenIds.isEmpty(), "the tab is back")
        assertEquals("běží tah", s.blocked.single().reason)
        advance(60_000)
        assertEquals(emptyList(), h.forgotten, "a blocked close never happens on its own")
        assertTrue(h.closes.state.value.isEmpty, "the notice leaves after its own window")
    }

    @Test fun closeAnywayOnABlockedNoticeClosesAtOnce() = runTest {
        val h = harness(check = { CloseCheck.Block("někdo se dívá") })
        h.closes.close(tab("a"))
        runCurrent()
        assertEquals(1, h.closes.state.value.blocked.size)
        h.closes.forceClose("a")
        runCurrent()
        assertEquals(listOf("a"), h.forgotten)
        assertTrue(h.closes.state.value.isEmpty)
        advance(60_000)
        assertEquals(listOf("a"), h.forgotten)
    }

    @Test fun closingABlockedTabAgainIsAFreshRequest() = runTest {
        var verdict: CloseCheck? = CloseCheck.Block("dialog")
        val h = harness(check = { verdict })
        h.closes.close(tab("a"))
        runCurrent()
        verdict = null
        assertTrue(h.closes.close(tab("a")))
        runCurrent()
        assertTrue(h.closes.state.value.blocked.isEmpty())
        advance(10_000)
        assertEquals(listOf("a"), h.forgotten)
    }

    @Test fun aWarningOnlyAddsTextAndTheCloseStillHappens() = runTest {
        val h = harness(check = { CloseCheck.Warn("3 necommitnuté soubory") })
        h.closes.close(tab("a"))
        runCurrent()
        assertEquals("3 necommitnuté soubory", h.closes.state.value.pending.single().warning)
        advance(10_000)
        assertEquals(listOf("a"), h.forgotten)
    }

    @Test fun clearAndUnknownBothClose() = runTest {
        val h = harness(check = { s -> if (s.id == "a") CloseCheck.Clear else null })
        h.closes.close(tab("a"))
        h.closes.close(tab("b"))
        advance(10_000)
        assertEquals(setOf("a", "b"), h.forgotten.toSet())
    }

    @Test fun aCheckThatNeverAnswersDoesNotHoldTheCloseUp() = runTest {
        // exec down / old server: the facts never come
        var cancelled = false
        val h = harness(check = { try { awaitCancellation() } finally { cancelled = true } })
        h.closes.close(tab("a"))
        advance(10_000)
        assertEquals(listOf("a"), h.forgotten)
        assertTrue(cancelled, "the check is called off with the close")
    }

    @Test fun aBlockThatArrivesAfterTheDeadlineIsTooLate() = runTest {
        val h = harness(check = { delay(15_000); CloseCheck.Block("late") })
        h.closes.close(tab("a"))
        advance(20_000)
        assertEquals(listOf("a"), h.forgotten)
        assertTrue(h.closes.state.value.isEmpty)
    }

    @Test fun aFailingCheckCountsAsUnknown() = runTest {
        val h = harness(check = { error("exec unavailable") })
        h.closes.close(tab("a"))
        advance(10_000)
        assertEquals(listOf("a"), h.forgotten)
    }

    @Test fun aCloseOnAnUnreachableServerFailsQuietlyAndOnce() = runTest {
        // the server side throws (no transport, connect refused): logged, not retried, not crashed
        val h = harness(forget = { throw IllegalStateException("connect refused") })
        h.closes.close(tab("a"))
        h.closes.close(tab("b"))
        advance(10_000)
        assertEquals(setOf("a", "b"), h.forgotten.toSet())
        advance(60_000)
        assertEquals(2, h.forgotten.size)
        assertTrue(h.closes.state.value.isEmpty)
    }

    @Test fun hoverHoldsEveryCountdownAndKeepsWhatWasLeft() = runTest {
        val h = harness()
        h.closes.close(tab("a"))
        advance(4_000)
        h.closes.setPaused(true)
        assertEquals(6_000, h.closes.remainingMs("a"))
        advance(30_000)
        assertEquals(emptyList(), h.forgotten)
        assertEquals(6_000, h.closes.remainingMs("a"), "frozen while paused")
        h.closes.close(tab("b")) // a close made while paused waits too
        advance(30_000)
        assertEquals(emptyList(), h.forgotten)
        h.closes.setPaused(false)
        advance(5_999)
        assertEquals(emptyList(), h.forgotten)
        advance(1)
        assertEquals(listOf("a"), h.forgotten)
        advance(4_000)
        assertEquals(listOf("a", "b"), h.forgotten)
    }

    @Test fun aRepeatedHoverEventChangesNothing() = runTest {
        val h = harness()
        h.closes.close(tab("a"))
        advance(4_000)
        h.closes.setPaused(true)
        advance(1_000)
        h.closes.setPaused(true) // Enter twice must not re-read the remaining time
        h.closes.setPaused(false)
        h.closes.setPaused(false)
        advance(6_000)
        assertEquals(listOf("a"), h.forgotten)
    }

    @Test fun flushWhilePausedStillClosesEverything() = runTest {
        val h = harness()
        h.closes.close(tab("a"))
        h.closes.setPaused(true)
        h.closes.flush()
        runCurrent()
        assertEquals(listOf("a"), h.forgotten)
    }

    @Test fun aTabThatVanishedElsewhereIsDroppedNotClosed() = runTest {
        val h = harness()
        h.closes.close(tab("a"))
        h.closes.close(tab("b"))
        h.closes.retain(setOf("b")) // another device closed "a"
        assertEquals(listOf("b"), h.closes.state.value.pending.map { it.sessionId })
        advance(10_000)
        assertEquals(listOf("b"), h.forgotten)
    }
}
