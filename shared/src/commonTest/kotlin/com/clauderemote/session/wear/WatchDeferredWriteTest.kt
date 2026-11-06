package com.clauderemote.session.wear

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Batch B, unit B4, round 5 (review-B-4 kod L1): the write WearSync schedules for when the urgent
 * budget lets a held-back edge out. On 799f23f a write held back while that scheduled write was
 * still running (waiting on an LLM summary, or its second write dropped as stale / unchanged) saw an
 * active job that was not itself and scheduled nothing: the change waited for the next push, in a
 * quiet hour indefinitely. Virtual time; `write` stands for `WearSync.push(urgent = false)`.
 */
class WatchDeferredWriteTest {
    private fun TestScope.deferred(write: suspend () -> Unit) =
        WatchDeferredWrite(this, { testScheduler.currentTime }, write)

    @Test fun aWriteHeldBackWhileTheScheduledWriteWaitsOnASummaryIsWrittenOnceItIsDone() = runTest {
        val writes = mutableListOf<Long>()
        val summary = CompletableDeferred<Unit>()
        val d = deferred {
            writes += testScheduler.currentTime
            if (writes.size == 1) summary.await() // the first scheduled push waits on the LLM
        }
        d.heldBack(10_000)
        advanceTimeBy(10_001); runCurrent()
        assertEquals(listOf(10_000L), writes)
        // The collector writes a new edge meanwhile; the budget holds it back until 20 s.
        d.heldBack(20_000)
        advanceTimeBy(5_000); runCurrent()
        summary.complete(Unit) // the scheduled push ends without another write (stale or unchanged)
        runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(10_000L, 20_000L), writes, "the change held back during the scheduled write was never written")
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, writes.size, "nothing more is scheduled once it is written")
    }

    @Test fun theScheduledWriteHeldBackItselfWritesAgain() = runTest {
        val writes = mutableListOf<Long>()
        lateinit var d: WatchDeferredWrite
        d = deferred {
            writes += testScheduler.currentTime
            // Another urgent write spent the budget meanwhile: the scheduled write is held back to 25 s.
            if (writes.size == 1) d.heldBack(25_000)
        }
        d.heldBack(10_000)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(10_000L, 25_000L), writes)
    }

    @Test fun writesHeldBackBeforeTheScheduledOneFoldIntoIt() = runTest {
        val writes = mutableListOf<Long>()
        val d = deferred { writes += testScheduler.currentTime }
        d.heldBack(10_000)
        advanceTimeBy(2_000); d.heldBack(10_000)
        advanceTimeBy(2_000); d.heldBack(10_000)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(10_000L), writes, "one scheduled write carries every change held back before it")
    }

    @Test fun aWriteHeldBackAfterTheScheduledOneFinishedSchedulesANewOne() = runTest {
        val writes = mutableListOf<Long>()
        val d = deferred { writes += testScheduler.currentTime }
        d.heldBack(10_000)
        advanceTimeBy(15_000); runCurrent()
        d.heldBack(30_000)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(10_000L, 30_000L), writes)
    }

    @Test fun aWriteThatThrewDoesNotBlockTheNextSchedule() = runTest {
        val writes = mutableListOf<Long>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, _ -> })
        val d = WatchDeferredWrite(scope, { testScheduler.currentTime }) {
            writes += testScheduler.currentTime
            if (writes.size == 1) error("putDataItem blew up")
        }
        d.heldBack(10_000)
        advanceTimeBy(15_000); runCurrent()
        d.heldBack(20_000)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(10_000L, 20_000L), writes)
        scope.cancel()
    }
}
