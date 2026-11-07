package com.clauderemote.session.service

import com.clauderemote.model.SessionActivity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A permission/question alert goes once its dialog was answered elsewhere: the hooks' word when
 * they are live, the APPROVAL -> WORKING edge without them. A completion alert stays.
 */
class DialogAlertTrackerTest {
    private val a = SessionActivity.APPROVAL_NEEDED
    private val w = SessionActivity.WORKING
    private val r = SessionActivity.WAITING_FOR_INPUT
    private val hookless: (String) -> Boolean = { false }
    private val hooked: (String) -> Boolean = { true }

    @Test fun withHooksOnlyTheReducerTakesTheAlertDown() {
        val t = DialogAlertTracker()
        t.staleAfter(mapOf("s" to a), hooked)
        t.posted("s", NotifyKind.PERMISSION)
        assertEquals(emptyList(), t.staleAfter(mapOf("s" to w), hooked), "an activity edge is no proof with hooks")
        assertEquals(true, t.answered("s"))
        assertEquals(false, t.answered("s"), "reported once")
    }

    @Test fun withoutHooksTheEdgeIntoWorkCounts() {
        val t = DialogAlertTracker()
        t.staleAfter(mapOf("s" to a), hookless)
        t.posted("s", NotifyKind.QUESTION)
        assertEquals(listOf("s"), t.staleAfter(mapOf("s" to w), hookless))
        assertEquals(emptyList(), t.staleAfter(mapOf("s" to a), hookless))
    }

    @Test fun theWaitingEdgeIsIgnoredItIsATabSwitchDowngradeOrAFlap() {
        val t = DialogAlertTracker()
        t.staleAfter(mapOf("s" to a), hookless)
        t.posted("s", NotifyKind.PERMISSION)
        assertEquals(emptyList(), t.staleAfter(mapOf("s" to r), hookless))
        assertEquals(true, t.answered("s"), "the alert is still there for the hooks to take down")
    }

    @Test fun theCompletionAlertOfTheTurnThatEndedTheDialogStays() {
        val t = DialogAlertTracker()
        t.posted("s", NotifyKind.PERMISSION)
        // the Stop path alerts before it flips the activity
        t.posted("s", NotifyKind.COMPLETION)
        assertEquals(false, t.answered("s"))
    }

    @Test fun pruningForgetsClosedTabs() {
        val t = DialogAlertTracker()
        t.staleAfter(mapOf("o" to a), hookless)
        t.posted("o", NotifyKind.PERMISSION)
        t.retain(setOf("s"))
        assertEquals(emptyList(), t.staleAfter(mapOf("o" to w), hookless))
    }
}
