package com.clauderemote.session.service

import com.clauderemote.session.PromptType

/**
 * What a "Claude needs input" event asks of the user. Carried as a type from
 * the source that knows it, never re-derived from the hint text: the hook
 * `ask` path and the screen INPUT_PROMPT share one hint string, so a hint
 * cannot tell a question from a finished turn.
 */
enum class NotifyKind {
    /** The turn ended (Stop hook). Nothing is blocked on the user. */
    COMPLETION,
    /** A tool permission dialog or another selector Claude cannot get past alone. */
    PERMISSION,
    /** Claude asked the user a question (AskUserQuestion). */
    QUESTION;

    companion object {
        /** Kind of a hook-event notification ([HookEventReducer.Notify.kind]: `perm` or `ask`). */
        fun ofHook(kind: String): NotifyKind = if (kind == "ask") QUESTION else PERMISSION

        /**
         * Kind of a screen-detector prompt. The detector only reports APPROVAL — a selector on
         * screen, whether a tool permission or an AskUserQuestion menu, which the screen does not
         * tell apart — so that is PERMISSION. INPUT_PROMPT is the plain idle input box, i.e. a
         * finished turn; screen idle no longer notifies (see InputPromptDetector), but should it
         * ever again it must not slip past the completion setting.
         */
        fun ofPrompt(type: PromptType): NotifyKind = when (type) {
            PromptType.INPUT_PROMPT -> COMPLETION
            PromptType.APPROVAL_NEEDED, PromptType.PERMISSION_PROMPT -> PERMISSION
        }
    }
}

/**
 * Single source of truth for "should this 'Claude needs input' event raise a
 * platform notification" — extracted so both platforms state the rule exactly
 * once instead of re-deriving it inline.
 *
 * Both platforms call this — Android from MainActivity.onClaudeNeedsInput,
 * desktop from Main.kt — so the rule cannot drift between them: notify only when
 * the user could plausibly have missed the event — the app isn't in the
 * foreground, or a different tab than the one that raised it is showing — and
 * never when notifications are turned off.
 *
 * A finished turn alerts only when [notifyOnTaskComplete] is on. With ~50
 * sessions every turn end buzzing was a flood (255 alerts in 6.5 h), while
 * the events that actually block Claude — a permission dialog, a question —
 * are what the user must not miss, so those always alert.
 */
object NotificationPolicy {
    fun shouldNotify(
        kind: NotifyKind,
        appForeground: Boolean,
        isActiveTab: Boolean,
        notificationsEnabled: Boolean,
        notifyOnTaskComplete: Boolean,
    ): Boolean = notificationsEnabled &&
        (kind != NotifyKind.COMPLETION || notifyOnTaskComplete) &&
        (!appForeground || !isActiveTab)
}
