package com.clauderemote.session.service

/**
 * Single source of truth for "should this 'Claude needs input' completion
 * raise a platform notification" — extracted so both platforms state the
 * rule exactly once instead of re-deriving it inline.
 *
 * Both platforms call this — Android from MainActivity.onClaudeNeedsInput,
 * desktop from Main.kt — so the rule cannot drift between them: notify only when
 * the user could plausibly have missed the completion — the app isn't in the
 * foreground, or a different tab than the one that finished is showing — and
 * never when notifications are turned off.
 */
object NotificationPolicy {
    fun shouldNotify(
        appForeground: Boolean,
        isActiveTab: Boolean,
        notificationsEnabled: Boolean,
    ): Boolean = notificationsEnabled && (!appForeground || !isActiveTab)
}
