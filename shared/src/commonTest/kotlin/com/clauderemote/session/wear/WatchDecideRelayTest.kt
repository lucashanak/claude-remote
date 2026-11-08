package com.clauderemote.session.wear

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-11-08 review: the wrist's decision of a Jarvis proposal must not block the Data Layer listener (an approved
 * open or restore takes up to 35 s); it runs on its own scope and answers by message, like /jarvis-undo-result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchDecideRelayTest {
    private val req = WatchDecideRequest("x-1", approve = true, textHash = 7)

    @Test fun submitReturnsAtOnceAndTheAnswerComesLater() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val answers = mutableListOf<WatchDecideResult>()
        val relay = WatchDecideRelay(scope) { r -> gate.await(); WatchDecideResult(r.id, true, "Provedeno") }
        // the listener thread gets control back before the decision even started
        assertTrue(relay.submit(req) { answers += it })
        scope.runCurrent()
        assertTrue(answers.isEmpty(), "still deciding: nothing answered, nothing blocked")
        gate.complete(Unit)
        scope.runCurrent()
        assertEquals(listOf(WatchDecideResult("x-1", true, "Provedeno")), answers)
    }

    @Test fun aSecondTapWhileTheFirstRunsIsDroppedAndTheIdIsFreeAfterwards() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val answers = mutableListOf<WatchDecideResult>()
        val relay = WatchDecideRelay(scope) { r -> calls++; gate.await(); WatchDecideResult(r.id, true, "ok") }
        assertTrue(relay.submit(req) { answers += it })
        assertFalse(relay.submit(req) { answers += it }, "the first one answers it")
        assertTrue(relay.submit(req.copy(id = "m-2")) { answers += it }, "another proposal runs alongside")
        gate.complete(Unit)
        scope.runCurrent()
        assertEquals(2, calls)
        assertEquals(setOf("x-1", "m-2"), answers.map { it.id }.toSet())
        assertTrue(relay.submit(req) { answers += it }, "decided: the id is free again")
        scope.runCurrent()
        assertEquals(3, calls)
    }

    @Test fun aDecisionThatHitsTheLimitSaysItMayHaveHappened() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val answers = mutableListOf<WatchDecideResult>()
        val relay = WatchDecideRelay(scope, limitMs = 1_000) { awaitCancellation() }
        relay.submit(req) { answers += it }
        scope.advanceTimeBy(1_001)
        scope.runCurrent()
        assertEquals(listOf(WatchDecideResult("x-1", false, WatchDecideRelay.TIMEOUT_TEXT)), answers)
        assertTrue(relay.submit(req) { answers += it }, "and frees the id")
    }

    @Test fun aFailureIsAnsweredNotSwallowed() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val answers = mutableListOf<WatchDecideResult>()
        val relay = WatchDecideRelay(scope) { throw IllegalStateException("boom") }
        relay.submit(req) { answers += it }
        scope.runCurrent()
        assertEquals(1, answers.size)
        assertFalse(answers[0].ok)
        assertTrue("IllegalStateException" in answers[0].text)
        assertTrue(relay.submit(req) { answers += it }, "the id is free after a failure")
    }
}
