package com.clauderemote.session.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptDialogDedupTest {
    private val d = PromptDialogDedup(windowMs = 15_000)
    private val hook = PromptDialogDedup.Source.HOOK
    private val screen = PromptDialogDedup.Source.SCREEN

    @Test fun theSecondSourceOfOneDialogIsHeldBackInEitherOrder() {
        assertTrue(d.allow("s", hook, 1_000))
        assertFalse(d.allow("s", screen, 2_000), "the screen detector after the hook event: the same dialog")
        assertTrue(d.allow("t", screen, 1_000))
        assertFalse(d.allow("t", hook, 2_000), "the hook event after the screen detector: the same dialog")
    }

    @Test fun theSameSourceRepeatsPassBecauseTheNextDialogIsANewThing() {
        assertTrue(d.allow("s", hook, 1_000))
        assertTrue(d.allow("s", hook, 3_000), "answering one dialog and getting the next")
    }

    @Test fun theOtherSourceIsAllowedAgainAfterTheWindowAndOtherSessionsAreIndependent() {
        assertTrue(d.allow("s", hook, 1_000))
        assertTrue(d.allow("s", screen, 1_000 + 15_000), "the window is over")
        assertTrue(d.allow("other", screen, 1_500), "another session")
    }

    @Test fun aHeldBackNotificationDoesNotExtendTheWindowOrCountAsOne() {
        assertTrue(d.allow("s", hook, 1_000))
        assertFalse(d.allow("s", screen, 10_000))
        assertFalse(d.allow("s", screen, 15_999), "the window runs from the hook, not from the held-back screen call")
        assertTrue(d.allow("s", screen, 16_000))
    }

    @Test fun forgetDropsTheMemory() {
        assertTrue(d.allow("s", hook, 1_000))
        d.forget("s")
        assertTrue(d.allow("s", screen, 2_000))
    }

    @Test fun keysAreClassifiedBySource() {
        assertTrue(NotifyKeys.isHook(NotifyKeys.hook("perm", "t", 5)))
        assertFalse(NotifyKeys.isHook(NotifyKeys.stop("t", 5)))
        assertFalse(NotifyKeys.isHook(null))
        assertFalse(NotifyKeys.isDetection(NotifyKeys.hook("perm", "t", 5)))
        assertTrue(NotifyKeys.isDetection(NotifyKeys.detection(com.clauderemote.session.PromptType.PERMISSION_PROMPT, 3)))
        assertFalse(NotifyKeys.isHook(NotifyKeys.detection(com.clauderemote.session.PromptType.PERMISSION_PROMPT, 3)))
    }
}
