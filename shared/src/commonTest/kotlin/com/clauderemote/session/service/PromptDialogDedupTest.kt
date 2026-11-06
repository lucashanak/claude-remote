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

    @Test fun aHeldBackNotificationIsNotRecordedInItsOwnSourceEither() {
        assertTrue(d.allow("s", hook, 1_000))
        assertFalse(d.allow("s", screen, 10_000), "held back")
        assertTrue(d.allow("s", hook, 20_000), "the held-back screen call must not count as a screen notification: the next dialog is never swallowed")
    }

    // Batch B, B4.2: the phone log had the hook `perm` at 14:26:18 and the screen detector's
    // sighting of the same dialog at 14:26:52 (+35 s). A time window cannot cover a dialog that
    // waits for minutes; the hold lasts while that hook approval is pending.

    @Test fun aScreenSightingOfAnAnnouncedHookApprovalIsHeldBackPast15sUntilTheApprovalEnds() {
        assertTrue(d.allow("s", hook, 1_000, pendingHookApprovalAt = 900))
        assertFalse(d.allow("s", screen, 1_000 + 35_000, pendingHookApprovalAt = 900), "+35 s, the dialog is still pending")
        assertTrue(d.isHeldByHook("s", 900, 1_000 + 35_000))
        assertFalse(d.allow("s", screen, 1_000 + 110_000, pendingHookApprovalAt = 900), "+110 s, still the same dialog")
        assertTrue(d.allow("s", screen, 1_000 + 111_000, pendingHookApprovalAt = null), "the next hook event ended it")
    }

    // Batch B round 2, R6: the hold must never silence a real new request. A permission answered
    // with allow (or denied) emits no hook, so after `hookev.off` or a lost watcher the reducer
    // keeps the old approval pending forever and every later dialog would be held back.

    @Test fun theHoldEndsAfterHoldMaxMs() {
        assertTrue(d.allow("s", hook, 1_000, pendingHookApprovalAt = 900))
        assertFalse(d.allow("s", screen, 1_000 + PromptDialogDedup.HOLD_MAX_MS - 1, pendingHookApprovalAt = 900))
        assertTrue(
            d.allow("s", screen, 1_000 + PromptDialogDedup.HOLD_MAX_MS, pendingHookApprovalAt = 900),
            "the hold has an upper bound: a dialog still on screen 2 min later alerts once more",
        )
    }

    @Test fun aHoldNeedsLiveHooks() {
        assertTrue(d.allow("s", hook, 1_000, pendingHookApprovalAt = 900))
        assertFalse(d.isHeldByHook("s", 900, 36_000, hooksLive = false))
        assertTrue(
            d.allow("s", screen, 36_000, pendingHookApprovalAt = 900, hooksLive = false),
            "hooks off (hookev.off) or the watcher lost: a pending approval proves nothing about the next dialog",
        )
    }

    @Test fun aDialogThatIsGoneEndsTheHold() {
        assertTrue(d.allow("s", hook, 1_000, pendingHookApprovalAt = 900))
        d.dialogGone("s") // answered from this app
        assertTrue(d.allow("s", screen, 36_000, pendingHookApprovalAt = 900), "the next dialog on screen is a new one")
    }

    @Test fun aScreenWithoutTheDialogRightAfterTheAnnouncementIsIgnored() {
        assertTrue(d.allow("s", hook, 1_000, pendingHookApprovalAt = 900))
        d.screenShowsNoDialog("s", lastOutputAtMs = 1_000 + PromptDialogDedup.SCREEN_GONE_GRACE_MS - 1) // the hook beat the drawing
        assertFalse(d.allow("s", screen, 36_000, pendingHookApprovalAt = 900), "still held")
        d.screenShowsNoDialog("s", lastOutputAtMs = 1_000 + PromptDialogDedup.SCREEN_GONE_GRACE_MS)
        assertTrue(d.allow("s", screen, 37_000, pendingHookApprovalAt = 900), "output came after the dialog and shows none")
    }

    // Round 3, adv-stav N5: a tab's stream can lag the hook by more than the grace. Its screen
    // then still shows the spinner from before the dialog; read late, that is no news.

    @Test fun aScreenWhoseOutputIsOlderThanTheDialogNeverEndsTheHold() {
        assertTrue(d.allow("s", hook, 1_000, pendingHookApprovalAt = 900))
        d.screenShowsNoDialog("s", lastOutputAtMs = 800) // read 10 s later, the newest output is from before the hook
        d.screenShowsNoDialog("s", lastOutputAtMs = null)
        assertFalse(d.allow("s", screen, 36_000, pendingHookApprovalAt = 900), "the late sighting of the same dialog stays held")
    }

    @Test fun onceTheScreenShowedTheDialogItsAbsenceEndsTheHold() {
        assertTrue(d.allow("s", hook, 1_000, pendingHookApprovalAt = 900))
        d.dialogSeen("s")
        d.screenShowsNoDialog("s", lastOutputAtMs = 1_500) // answered within the grace, e.g. on the desktop
        assertTrue(d.allow("s", screen, 36_000, pendingHookApprovalAt = 900), "the next dialog on screen is a new one")
    }

    @Test fun aSightingBeforeTheAnnouncementDoesNotCount() {
        d.dialogSeen("s") // nothing announced yet
        assertTrue(d.allow("s", hook, 1_000, pendingHookApprovalAt = 900))
        d.screenShowsNoDialog("s", lastOutputAtMs = 1_500)
        assertFalse(d.allow("s", screen, 36_000, pendingHookApprovalAt = 900), "still held")
        // a new announcement starts over: the sighting of the previous dialog does not carry
        d.dialogSeen("s")
        assertTrue(d.allow("s", hook, 40_000, pendingHookApprovalAt = 39_000))
        d.screenShowsNoDialog("s", lastOutputAtMs = 40_500)
        assertFalse(d.allow("s", screen, 70_000, pendingHookApprovalAt = 39_000), "still held after the second announcement")
    }

    @Test fun aHookAnnouncementHeldBackByTheScreenFirstStillHoldsLaterSightings() {
        assertTrue(d.allow("s", screen, 1_000), "the screen announced the dialog first")
        assertFalse(d.allow("s", hook, 3_000, pendingHookApprovalAt = 2_900), "the same dialog from the hook, inside 15 s")
        assertFalse(
            d.allow("s", screen, 40_000, pendingHookApprovalAt = 2_900),
            "the dialog was announced (by the screen): a re-sighting past 15 s is the same dialog",
        )
    }

    @Test fun aPendingApprovalTheHookNeverAnnouncedHoldsNothing() {
        assertFalse(d.isHeldByHook("s", 900, 36_000))
        assertTrue(d.allow("s", screen, 36_000, pendingHookApprovalAt = 900), "e.g. the foreground tab: the screen is the one alert")
    }

    @Test fun aNewHookApprovalNeedsItsOwnAnnouncement() {
        assertTrue(d.allow("s", hook, 1_000, pendingHookApprovalAt = 900))
        assertTrue(d.allow("s", screen, 40_000, pendingHookApprovalAt = 39_000), "a second request whose hook alert has not gone out")
        assertTrue(d.allow("other", screen, 40_000, pendingHookApprovalAt = 900), "per session")
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
