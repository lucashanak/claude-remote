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
    /**
     * [viaJarvis] = [jarvisCarries]: Jarvis' batches stand in for this device's completion alerts. Only completions
     * step aside; a permission or a question always alerts by itself, whether or not Jarvis is alive (navrh §3 bod 11:
     * the deterministic layer of the app escalates what blocks, even while Jarvis sleeps).
     */
    fun shouldNotify(
        kind: NotifyKind,
        appForeground: Boolean,
        isActiveTab: Boolean,
        notificationsEnabled: Boolean,
        notifyOnTaskComplete: Boolean,
        viaJarvis: Boolean = false,
    ): Boolean = notificationsEnabled &&
        (kind != NotifyKind.COMPLETION || (notifyOnTaskComplete && !viaJarvis)) &&
        (!appForeground || !isActiveTab)

    /**
     * Whether Jarvis carries this device's completion alerts: routed through him AND his heartbeat lease is live.
     * Without the lease it is per session again at once (R11 fail-open), with nothing to switch back by hand.
     */
    fun jarvisCarries(routing: AlertRouting, jarvisAlive: Boolean): Boolean =
        routing == AlertRouting.VIA_JARVIS && jarvisAlive

    /**
     * This alert was held back ONLY because Jarvis carries it: without him it would have alerted. Such a completion
     * is remembered ([JarvisMutedCompletions]) and alerted after all if Jarvis' lease lapses before he said it.
     */
    fun mutedByJarvis(
        kind: NotifyKind,
        appForeground: Boolean,
        isActiveTab: Boolean,
        notificationsEnabled: Boolean,
        notifyOnTaskComplete: Boolean,
        viaJarvis: Boolean,
    ): Boolean = viaJarvis &&
        !shouldNotify(kind, appForeground, isActiveTab, notificationsEnabled, notifyOnTaskComplete, viaJarvis = true) &&
        shouldNotify(kind, appForeground, isActiveTab, notificationsEnabled, notifyOnTaskComplete, viaJarvis = false)

    /** "Jarvis neodpovídá": routed through Jarvis, but his lease lapsed (the Velín's strip, the one quiet notice). */
    fun jarvisFailedOpen(routing: AlertRouting, jarvisAlive: Boolean): Boolean =
        routing == AlertRouting.VIA_JARVIS && !jarvisAlive
}

/**
 * Takes down a permission/question alert once its dialog was answered elsewhere. With
 * completion alerts off, nothing else replaces it after the turn ended.
 *
 * With live hooks only the hook reducer decides ([answered]: the pane's next work/askdone/
 * stop/end after the perm/ask); an activity edge is not proof — a tab switch downgrades an
 * approval to WAITING, the statusline flaps. Without hooks only APPROVAL_NEEDED -> WORKING
 * counts; the WAITING edge is ignored for the same reasons. Either way only while the alert on
 * screen is a dialog one: a completion alert posted for the turn that ended the dialog (the Stop
 * path alerts BEFORE it flips the activity) must stay.
 */
class DialogAlertTracker {
    private val lock = Any()
    private val postedKinds = HashMap<String, NotifyKind>()
    private val previous = HashMap<String, com.clauderemote.model.SessionActivity>()

    /** An alert of [kind] is now showing for [sessionId]. */
    fun posted(sessionId: String, kind: NotifyKind) { synchronized(lock) { postedKinds[sessionId] = kind } }

    /** The hooks report the dialog of [sessionId] answered: true when its dialog alert is to go. */
    fun answered(sessionId: String): Boolean = synchronized(lock) { takeDialog(sessionId) }

    /** The hookless sessions whose dialog alert is stale under [activities] (each reported once). */
    fun staleAfter(
        activities: Map<String, com.clauderemote.model.SessionActivity>,
        hooksLive: (String) -> Boolean,
    ): List<String> = synchronized(lock) {
        val stale = ArrayList<String>()
        for ((id, now) in activities) {
            val before = previous.put(id, now)
            val left = before == com.clauderemote.model.SessionActivity.APPROVAL_NEEDED &&
                now == com.clauderemote.model.SessionActivity.WORKING
            if (left && !hooksLive(id) && takeDialog(id)) stale += id
        }
        stale
    }

    private fun takeDialog(id: String): Boolean {
        val kind = postedKinds[id]
        if (kind != NotifyKind.PERMISSION && kind != NotifyKind.QUESTION) return false
        postedKinds.remove(id)
        return true
    }

    fun retain(liveIds: Set<String>) {
        synchronized(lock) {
            postedKinds.keys.retainAll(liveIds)
            previous.keys.retainAll(liveIds)
        }
    }
}
