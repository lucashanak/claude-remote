package com.clauderemote.session.wear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Batch B, unit B4.3: the watch got "done" 91 s late because the push that carried the
 * WORKING -> WAITING flip was a routine (non-urgent) DataItem (14:41:07.950 -> 14:42:38).
 *
 * Round 2 (R9): urgent only where the watch will actually announce something. It drops a flip
 * into WAITING whose `lastMessageAt` did not move as a "skip stale flip"
 * (WearDataListenerService), so such a flip (a silence drop, an empty Enter) must not wake the
 * radio; entering APPROVAL always counts. One urgent push per 10 s in all outside pushNow(), and
 * an urgent edge survives a build whose write never happened (collectLatest cancelled it).
 */
class WatchPushUrgencyTest {
    private val working = "WORKING"
    private val waiting = "WAITING_FOR_INPUT"
    private val approval = "APPROVAL_NEEDED"

    @Test fun anEdgeIntoApprovalIsAlwaysUrgent() {
        assertTrue(WatchPushUrgency.isUrgentEdge(working, approval, messageAt = 5, lastAttentionMessageAt = 5))
        assertTrue(WatchPushUrgency.isUrgentEdge(waiting, approval, messageAt = 5, lastAttentionMessageAt = 5))
        assertFalse(WatchPushUrgency.isUrgentEdge(approval, approval, 9, 5), "no transition")
        assertFalse(WatchPushUrgency.isUrgentEdge(null, approval, 9, null), "first sighting (app start, new tab) is no transition")
        assertFalse(WatchPushUrgency.isUrgentEdge(waiting, working, 9, 5), "starting work is routine")
        assertFalse(WatchPushUrgency.isUrgentEdge(waiting, "IDLE", 9, 5))
    }

    // 2026-11-08 (Jarvis-only watch): a finished turn never alerts the wrist, so it never wakes the radio either.
    @Test fun aFinishedTurnIsNeverUrgent() {
        assertFalse(WatchPushUrgency.isUrgentEdge(working, waiting, messageAt = 9, lastAttentionMessageAt = 5), "a new answer")
        assertFalse(WatchPushUrgency.isUrgentEdge(working, waiting, messageAt = 9, lastAttentionMessageAt = null))
        assertFalse(WatchPushUrgency.isUrgentEdge(approval, waiting, 5, 5), "the dialog was answered, nothing new to say")
        assertFalse(WatchPushUrgency.isAttentionState(waiting))
        assertFalse(WatchPushUrgency.needsInputUrgent(com.clauderemote.session.service.NotifyKind.COMPLETION))
        val t = WatchUrgencyTracker()
        t.note(listOf(WatchUrgencyRow("s", working, waiting, 100)), 0)
        assertEquals(emptySet(), t.claim(setOf("s"), 0, urgentAnyway = false))
        assertNull(t.deferredUntilMs())
    }

    // ...and no perm/ask is dropped by that: every way a dialog reaches the phone still makes an urgent push.
    @Test fun everyDialogStaysUrgent() {
        // entering a dialog from anything (working, a finished turn, idle, a reconnect) is an edge
        for (from in listOf(working, waiting, "IDLE", "DISCONNECTED")) {
            assertTrue(WatchPushUrgency.isUrgentEdge(from, approval, 1, null), from)
        }
        assertTrue(WatchPushUrgency.isAttentionState(approval))
        // the second dialog of one turn has no edge (APPROVAL -> APPROVAL): its alert's pushNow is urgent
        assertTrue(WatchPushUrgency.needsInputUrgent(com.clauderemote.session.service.NotifyKind.PERMISSION))
        assertTrue(WatchPushUrgency.needsInputUrgent(com.clauderemote.session.service.NotifyKind.QUESTION), "ask")
        // a tracker that also saw finished turns still delivers the dialog edge
        val t = WatchUrgencyTracker()
        t.note(listOf(WatchUrgencyRow("a", working, waiting, 1), WatchUrgencyRow("b", working, approval, 1)), 0)
        assertEquals(setOf("b"), t.claim(setOf("a", "b"), 0, urgentAnyway = false))
    }

    @Test fun aJarvisAlertIsUrgentOnlyWhenItVibrates() {
        for (kind in com.clauderemote.session.service.JarvisAlertKind.entries) {
            assertFalse(WatchPushUrgency.jarvisAlertUrgent(kind, buzz = false), "$kind held back by the 5 min batcher")
        }
        assertTrue(WatchPushUrgency.jarvisAlertUrgent(com.clauderemote.session.service.JarvisAlertKind.BLOCK, true))
        assertTrue(WatchPushUrgency.jarvisAlertUrgent(com.clauderemote.session.service.JarvisAlertKind.DONE, true))
        assertTrue(WatchPushUrgency.jarvisAlertUrgent(com.clauderemote.session.service.JarvisAlertKind.INCIDENT, true))
        assertFalse(WatchPushUrgency.jarvisAlertUrgent(com.clauderemote.session.service.JarvisAlertKind.INFO, true), "info never")
        // F5c: of Jarvis' own sentences only a jblock vibrates the wrist; jsay and jask only update it
        assertTrue(WatchPushUrgency.jarvisAlertUrgent(com.clauderemote.session.service.JarvisAlertKind.JBLOCK, true))
        assertFalse(WatchPushUrgency.jarvisAlertUrgent(com.clauderemote.session.service.JarvisAlertKind.JSAY, true), "jsay")
        assertFalse(WatchPushUrgency.jarvisAlertUrgent(com.clauderemote.session.service.JarvisAlertKind.JASK, true), "jask")
        assertFalse(WatchPushUrgency.jarvisAlertUrgent(com.clauderemote.session.service.JarvisAlertKind.JBLOCK, false))
        assertTrue(WatchPushUrgency.jarvisAlertUrgent(com.clauderemote.session.service.JarvisAlertKind.JBLOCKASK, true), "jblockask like jblock")
    }

    @Test fun aDialogAgainIsUrgentStampOrNot() {
        val t = WatchUrgencyTracker()
        t.note(listOf(WatchUrgencyRow("s", null, approval, 100)), 0)
        t.note(listOf(WatchUrgencyRow("s", approval, working, 100)), 1_000)
        t.note(listOf(WatchUrgencyRow("s", working, approval, 100)), 2_000)
        assertEquals(setOf("s"), t.claim(setOf("s"), 2_000, urgentAnyway = false))
    }

    @Test fun theBudgetIsOneUrgentPushPer10sInAll() {
        val t = WatchUrgencyTracker()
        t.note(listOf(WatchUrgencyRow("a", working, approval, 1)), 0)
        assertEquals(setOf("a"), t.claim(setOf("a"), 0, false))
        t.note(listOf(WatchUrgencyRow("b", working, approval, 1)), 2_000)
        assertEquals(emptySet(), t.claim(setOf("a", "b"), 2_000, false), "another session inside 10 s: routine")
        assertEquals(emptySet(), t.claim(setOf("a", "b"), 9_999, false))
        assertEquals(setOf("b"), t.claim(setOf("a", "b"), 10_000, false), "the deferred edge rides the next push past the budget")
    }

    @Test fun pushNowCarriesPendingEdgesWithoutSpendingTheBudget() {
        val t = WatchUrgencyTracker()
        t.note(listOf(WatchUrgencyRow("a", working, approval, 1)), 0)
        assertEquals(setOf("a"), t.claim(setOf("a"), 0, urgentAnyway = true), "pushNow is urgent anyway and delivers the edge")
        assertEquals(emptySet(), t.claim(setOf("a"), 1, false), "delivered once")
        t.note(listOf(WatchUrgencyRow("b", working, approval, 1)), 500)
        assertEquals(setOf("b"), t.claim(setOf("b"), 500, false), "the budget was not spent by pushNow")
    }

    @Test fun anUrgentEdgeSurvivesABuildThatWasNeverWritten() {
        val t = WatchUrgencyTracker()
        assertNull(t.observe("s", working))
        assertEquals(working, t.observe("s", approval))
        t.note(listOf(WatchUrgencyRow("s", working, approval, 1)), 0)
        // collectLatest cancelled that push before its write; the next build sees no edge any more
        assertEquals(approval, t.observe("s", approval))
        t.note(listOf(WatchUrgencyRow("s", approval, approval, 1)), 300)
        assertEquals(setOf("s"), t.claim(setOf("s"), 300, false), "the edge is claimed by the write that delivers it")
    }

    @Test fun anEdgeIsDroppedOnceTheSessionNoLongerWaits() {
        val t = WatchUrgencyTracker()
        t.note(listOf(WatchUrgencyRow("s", working, approval, 1)), 0)
        t.note(listOf(WatchUrgencyRow("s", approval, working, 1)), 200)
        assertEquals(emptySet(), t.claim(setOf("s"), 200, false), "answered before anything was written")
        t.note(listOf(WatchUrgencyRow("u", working, approval, 1)), 300)
        assertEquals(emptySet(), t.claim(emptySet(), 300, false), "only for a snapshot that still shows it waiting")
        assertEquals(emptySet(), t.claim(setOf("u"), 300 + WatchPushUrgency.PENDING_MAX_MS, false), "a stale edge expires")
    }

    @Test fun retainDropsClosedTabs() {
        val t = WatchUrgencyTracker()
        t.observe("s", working)
        t.note(listOf(WatchUrgencyRow("s", working, approval, 1)), 0)
        t.retain(emptySet())
        assertNull(t.observe("s", approval), "a closed tab's previous activity is gone")
        assertEquals(emptySet(), t.claim(setOf("s"), 0, false), "and its pending edge")
    }

    // Round 3 (adv-stav N7, kod-stav L5): an edge the budget held back had no write of its own; with
    // everything quiet the WAITING went out routine. The claim names when a write may deliver it.
    @Test fun anEdgeTheBudgetHeldBackNamesWhenToWriteAgain() {
        val t = WatchUrgencyTracker()
        t.note(listOf(WatchUrgencyRow("a", working, approval, 1)), 0)
        assertEquals(setOf("a"), t.claim(setOf("a"), 0, false))
        assertNull(t.deferredUntilMs(), "nothing held back")
        t.note(listOf(WatchUrgencyRow("b", working, approval, 1)), 2_000)
        assertEquals(emptySet(), t.claim(setOf("a", "b"), 2_000, false))
        assertEquals(WatchPushUrgency.BUDGET_MS, t.deferredUntilMs(), "the write is due when the budget allows one again")
        assertEquals(setOf("b"), t.claim(setOf("a", "b"), WatchPushUrgency.BUDGET_MS, false), "that write delivers it")
        assertNull(t.deferredUntilMs(), "delivered: nothing more to schedule")
    }

    @Test fun nothingIsDeferredWithoutAHeldBackEdge() {
        val t = WatchUrgencyTracker()
        t.note(listOf(WatchUrgencyRow("a", working, approval, 1)), 0)
        assertEquals(setOf("a"), t.claim(setOf("a"), 0, false))
        assertEquals(emptySet(), t.claim(setOf("a"), 1_000, false), "a routine write inside the budget, no edge pending")
        assertNull(t.deferredUntilMs())
        t.note(listOf(WatchUrgencyRow("b", working, approval, 1)), 2_000)
        assertEquals(emptySet(), t.claim(setOf("a", "b"), 2_000, false))
        assertEquals(setOf("b"), t.claim(setOf("b"), 3_000, urgentAnyway = true), "pushNow took it")
        assertNull(t.deferredUntilMs(), "a held back edge that pushNow delivered schedules nothing")
    }

    // Round 4 (adv-stav N7): the routine write the budget held an edge back from put the edge's row
    // already, so the scheduled write carried the same bytes and Data Layer, which drops a DataItem
    // identical to the last one, dropped it with its urgent flag: the edge reached the watch routine.
    @Test fun aWriteTheBudgetHoldsAnEdgeBackFromPutsNothingAndTheScheduledWriteIsUrgent() {
        val t = WatchUrgencyTracker()
        val delivered = mutableListOf<Pair<String, Boolean>>() // what a deduplicating Data Layer delivers: payload, urgent
        var last: String? = null
        fun write(payload: String, attention: Set<String>, nowMs: Long, urgentAnyway: Boolean = false): Long? =
            t.write(attention, nowMs, urgentAnyway) { urgent, _ ->
                if (payload != last) delivered += payload to urgent
                last = payload
            }
        t.note(listOf(WatchUrgencyRow("a", working, approval, 1)), 0)
        assertNull(write("a waits", setOf("a"), 0))
        t.note(listOf(WatchUrgencyRow("b", working, approval, 7)), 2_000)
        assertEquals(WatchPushUrgency.BUDGET_MS, write("a, b wait", setOf("a", "b"), 2_000), "held back until the budget allows one")
        assertEquals(WatchPushUrgency.BUDGET_MS, write("a, b wait", setOf("a", "b"), 5_000), "and so is every write before then")
        assertEquals(listOf("a waits" to true), delivered, "b's row was not written before the scheduled write")
        assertNull(write("a, b wait", setOf("a", "b"), WatchPushUrgency.BUDGET_MS), "the scheduled write")
        assertEquals(listOf("a waits" to true, "a, b wait" to true), delivered, "it carries b's row, urgent")
        assertNull(write("a, b wait, c works", setOf("a", "b"), 11_000), "nothing held back: written")
        assertEquals("a, b wait, c works" to false, delivered.last(), "routine")
        t.note(listOf(WatchUrgencyRow("c", working, approval, 1)), 12_000)
        assertNull(write("c asks", setOf("a", "b", "c"), 12_000, urgentAnyway = true), "pushNow is never held back")
        assertEquals("c asks" to true, delivered.last())
    }
}
