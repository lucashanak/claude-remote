package com.clauderemote.session.service

import com.clauderemote.session.PromptType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [NotificationPolicy.shouldNotify] against every (kind, appForeground,
 * isActiveTab, notificationsEnabled, notifyOnTaskComplete) combination — it is
 * the one place both platforms' rule is stated, so a regression here silently
 * breaks both Android and desktop.
 */
class NotificationPolicyTest {
    private fun notify(
        kind: NotifyKind = NotifyKind.PERMISSION,
        appForeground: Boolean,
        isActiveTab: Boolean,
        notificationsEnabled: Boolean = true,
        notifyOnTaskComplete: Boolean = true,
    ) = NotificationPolicy.shouldNotify(kind, appForeground, isActiveTab, notificationsEnabled, notifyOnTaskComplete)

    @Test
    fun notifiesWhenBackgroundedRegardlessOfActiveTab() {
        // App not in the foreground: notify whether or not the completing
        // session happens to be the "active" tab underneath.
        assertTrue(notify(appForeground = false, isActiveTab = true))
        assertTrue(notify(appForeground = false, isActiveTab = false))
    }

    @Test
    fun notifiesWhenForegroundButOnADifferentTab() {
        assertTrue(notify(appForeground = true, isActiveTab = false))
    }

    @Test
    fun suppressesWhenForegroundOnTheActiveTab() {
        // The exact case this policy exists to fix: the user is looking
        // straight at the session that just completed.
        for (kind in NotifyKind.entries) {
            assertFalse(notify(kind, appForeground = true, isActiveTab = true), "$kind")
        }
    }

    @Test
    fun neverNotifiesWhenNotificationsAreDisabled() {
        // notificationsEnabled=false must win over every other combination.
        for (kind in NotifyKind.entries) for (fg in listOf(false, true)) for (active in listOf(false, true)) {
            for (taskComplete in listOf(false, true)) {
                assertFalse(
                    notify(kind, fg, active, notificationsEnabled = false, notifyOnTaskComplete = taskComplete),
                    "$kind fg=$fg active=$active taskComplete=$taskComplete",
                )
            }
        }
    }

    // The flood: every turn end of ~50 sessions alerted. A completion alerts only on request.
    @Test
    fun aCompletionAlertsOnlyWhenTaskCompleteNotificationsAreOn() {
        for (fg in listOf(false, true)) for (active in listOf(false, true)) {
            if (fg && active) continue // the foreground rule, covered above
            assertFalse(notify(NotifyKind.COMPLETION, fg, active, notifyOnTaskComplete = false), "fg=$fg active=$active")
            assertTrue(notify(NotifyKind.COMPLETION, fg, active, notifyOnTaskComplete = true), "fg=$fg active=$active")
        }
    }

    // What blocks Claude must never be lost to the completion setting.
    @Test
    fun aPermissionOrAQuestionAlertsWhateverTheCompletionSetting() {
        for (kind in listOf(NotifyKind.PERMISSION, NotifyKind.QUESTION)) {
            for (taskComplete in listOf(false, true)) {
                assertTrue(notify(kind, appForeground = false, isActiveTab = true, notifyOnTaskComplete = taskComplete), "$kind")
                assertTrue(notify(kind, appForeground = true, isActiveTab = false, notifyOnTaskComplete = taskComplete), "$kind")
            }
        }
    }

    @Test
    fun theHookKindMapsAskToAQuestionAndPermToAPermission() {
        assertEquals(NotifyKind.QUESTION, NotifyKind.ofHook("ask"))
        assertEquals(NotifyKind.PERMISSION, NotifyKind.ofHook("perm"))
    }

    @Test
    fun aScreenSelectorIsAPermissionAndThePlainInputBoxIsACompletion() {
        assertEquals(NotifyKind.PERMISSION, NotifyKind.ofPrompt(PromptType.APPROVAL_NEEDED))
        assertEquals(NotifyKind.PERMISSION, NotifyKind.ofPrompt(PromptType.PERMISSION_PROMPT))
        // Shares its hint with the hook `ask` — which is why the kind is typed, not read off the hint.
        assertEquals(NotifyKind.COMPLETION, NotifyKind.ofPrompt(PromptType.INPUT_PROMPT))
    }
}
