package com.clauderemote.session.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [NotificationPolicy.shouldNotify] against every (appForeground,
 * isActiveTab, notificationsEnabled) combination — it is the one place both
 * platforms' "don't notify about the session you're already looking at" rule
 * is stated, so a regression here silently breaks both Android and desktop.
 */
class NotificationPolicyTest {

    @Test
    fun notifiesWhenBackgroundedRegardlessOfActiveTab() {
        // App not in the foreground: notify whether or not the completing
        // session happens to be the "active" tab underneath.
        assertTrue(NotificationPolicy.shouldNotify(appForeground = false, isActiveTab = true, notificationsEnabled = true))
        assertTrue(NotificationPolicy.shouldNotify(appForeground = false, isActiveTab = false, notificationsEnabled = true))
    }

    @Test
    fun notifiesWhenForegroundButOnADifferentTab() {
        assertTrue(NotificationPolicy.shouldNotify(appForeground = true, isActiveTab = false, notificationsEnabled = true))
    }

    @Test
    fun suppressesWhenForegroundOnTheActiveTab() {
        // The exact case this policy exists to fix: the user is looking
        // straight at the session that just completed.
        assertFalse(NotificationPolicy.shouldNotify(appForeground = true, isActiveTab = true, notificationsEnabled = true))
    }

    @Test
    fun neverNotifiesWhenNotificationsAreDisabled() {
        // notificationsEnabled=false must win over every other combination.
        assertFalse(NotificationPolicy.shouldNotify(appForeground = false, isActiveTab = false, notificationsEnabled = false))
        assertFalse(NotificationPolicy.shouldNotify(appForeground = false, isActiveTab = true, notificationsEnabled = false))
        assertFalse(NotificationPolicy.shouldNotify(appForeground = true, isActiveTab = false, notificationsEnabled = false))
        assertFalse(NotificationPolicy.shouldNotify(appForeground = true, isActiveTab = true, notificationsEnabled = false))
    }
}
