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

    // ── Jarvis F3: routing with fail-open ──

    @Test
    fun viaJarvisMutesOnlyCompletionsAndNeverAPermissionOrAQuestion() {
        for (fg in listOf(false, true)) for (active in listOf(false, true)) {
            val expectBlocking = !fg || !active
            for (k in listOf(NotifyKind.PERMISSION, NotifyKind.QUESTION)) {
                assertEquals(
                    expectBlocking,
                    NotificationPolicy.shouldNotify(k, fg, active, notificationsEnabled = true, notifyOnTaskComplete = true, viaJarvis = true),
                    "$k fg=$fg active=$active",
                )
            }
            assertFalse(NotificationPolicy.shouldNotify(NotifyKind.COMPLETION, fg, active, true, true, viaJarvis = true))
            assertEquals(expectBlocking, NotificationPolicy.shouldNotify(NotifyKind.COMPLETION, fg, active, true, true, viaJarvis = false))
        }
    }

    @Test
    fun jarvisCarriesOnlyWithItsRoutingAndALiveLeaseElseFailsOpen() {
        assertTrue(NotificationPolicy.jarvisCarries(AlertRouting.VIA_JARVIS, jarvisAlive = true))
        assertFalse(NotificationPolicy.jarvisCarries(AlertRouting.VIA_JARVIS, jarvisAlive = false))
        assertFalse(NotificationPolicy.jarvisCarries(AlertRouting.PER_SESSION, jarvisAlive = true))
        assertFalse(NotificationPolicy.jarvisCarries(AlertRouting.PER_SESSION, jarvisAlive = false))
        assertTrue(NotificationPolicy.jarvisFailedOpen(AlertRouting.VIA_JARVIS, jarvisAlive = false))
        assertFalse(NotificationPolicy.jarvisFailedOpen(AlertRouting.VIA_JARVIS, jarvisAlive = true))
        assertFalse(NotificationPolicy.jarvisFailedOpen(AlertRouting.PER_SESSION, jarvisAlive = false))
        // the lease lapsed: the completion the user asked for alerts per session again, with nothing switched
        val carried = NotificationPolicy.jarvisCarries(AlertRouting.VIA_JARVIS, jarvisAlive = false)
        assertTrue(NotificationPolicy.shouldNotify(NotifyKind.COMPLETION, false, false, true, true, viaJarvis = carried))
    }

    @Test
    fun mutedByJarvisIsOnlyWhatJarvisAloneHeldBack() {
        // a background completion the user wants, carried by Jarvis: muted, to be said if he lapses (MED1)
        assertTrue(NotificationPolicy.mutedByJarvis(NotifyKind.COMPLETION, false, false, true, true, viaJarvis = true))
        // not carried: it alerted itself, nothing to remember
        assertFalse(NotificationPolicy.mutedByJarvis(NotifyKind.COMPLETION, false, false, true, true, viaJarvis = false))
        // would not have alerted anyway: completion alerts off, notifications off, the user looks at it
        assertFalse(NotificationPolicy.mutedByJarvis(NotifyKind.COMPLETION, false, false, true, false, viaJarvis = true))
        assertFalse(NotificationPolicy.mutedByJarvis(NotifyKind.COMPLETION, false, false, false, true, viaJarvis = true))
        assertFalse(NotificationPolicy.mutedByJarvis(NotifyKind.COMPLETION, true, true, true, true, viaJarvis = true))
        // a permission is never muted
        assertFalse(NotificationPolicy.mutedByJarvis(NotifyKind.PERMISSION, false, false, true, true, viaJarvis = true))
    }

    @Test
    fun routingDefaultsToPerSessionAndRoundTrips() {
        assertEquals(AlertRouting.PER_SESSION, AlertRouting.of(null))
        assertEquals(AlertRouting.PER_SESSION, AlertRouting.of("garbage"))
        AlertRouting.entries.forEach { assertEquals(it, AlertRouting.of(it.wire)) }
    }
}
