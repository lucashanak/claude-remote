package com.clauderemote.session.service

import com.clauderemote.model.SessionActivity
import kotlin.test.Test
import kotlin.test.assertEquals

/** A permission/question alert goes once its session left the dialog; a completion alert stays. */
class DialogAlertTrackerTest {
    private val a = SessionActivity.APPROVAL_NEEDED
    private val w = SessionActivity.WORKING
    private val r = SessionActivity.WAITING_FOR_INPUT

    @Test fun aDialogAlertIsStaleOnceTheSessionLeavesTheDialog() {
        val t = DialogAlertTracker()
        t.staleAfter(mapOf("s" to a))
        t.posted("s", NotifyKind.PERMISSION)
        assertEquals(emptyList(), t.staleAfter(mapOf("s" to a)), "still in the dialog")
        assertEquals(listOf("s"), t.staleAfter(mapOf("s" to w)), "answered elsewhere, back at work")
        assertEquals(emptyList(), t.staleAfter(mapOf("s" to a)), "reported once")
        assertEquals(emptyList(), t.staleAfter(mapOf("s" to r)), "no dialog alert showing any more")
    }

    @Test fun aQuestionAlertGoesWhenTheTurnEndsWithoutACompletionAlert() {
        val t = DialogAlertTracker()
        t.staleAfter(mapOf("s" to a))
        t.posted("s", NotifyKind.QUESTION)
        assertEquals(listOf("s"), t.staleAfter(mapOf("s" to r)))
    }

    @Test fun theCompletionAlertOfTheTurnThatEndedTheDialogStays() {
        val t = DialogAlertTracker()
        t.staleAfter(mapOf("s" to a))
        t.posted("s", NotifyKind.PERMISSION)
        // the Stop path alerts before it flips the activity
        t.posted("s", NotifyKind.COMPLETION)
        assertEquals(emptyList(), t.staleAfter(mapOf("s" to r)))
    }

    @Test fun onlyTheEdgeOutOfTheDialogCountsPerSession() {
        val t = DialogAlertTracker()
        t.staleAfter(mapOf("s" to w, "o" to a))
        t.posted("s", NotifyKind.PERMISSION)
        t.posted("o", NotifyKind.PERMISSION)
        assertEquals(listOf("o"), t.staleAfter(mapOf("s" to r, "o" to w)), "s never was in a dialog on this edge")
        t.retain(setOf("s"))
        assertEquals(emptyList(), t.staleAfter(mapOf("o" to a)))
    }
}
