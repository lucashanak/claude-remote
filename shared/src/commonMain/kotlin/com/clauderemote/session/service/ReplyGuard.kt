package com.clauderemote.session.service

import com.clauderemote.model.SessionActivity

/** What an actionable notification asked the user for. */
enum class PromptKind {
    /** Claude finished and sits at its input box: the answer is a new instruction. */
    READY,
    /** A selector is open (tool permission or AskUserQuestion): a key press answers it. */
    DIALOG,
}

/**
 * Identity of the prompt an actionable notification (phone alert, watch notification, watch
 * detail screen) was raised for. [key] is the notify event key ([NotifyKeys]): one hook `perm` /
 * `ask`, one screen detection, one Stop marker. A reply carries it back so the phone can tell
 * whether the session still shows THAT prompt before typing anything into it.
 */
data class PromptFingerprint(val key: String, val kind: PromptKind) {
    /** One string, so it fits an Intent extra and the watch wire models unchanged in shape. */
    fun encode(): String = "${kind.name}:$key"

    companion object {
        /** null for anything that is not an [encode] result: a reply without an identity fails closed. */
        fun decode(s: String?): PromptFingerprint? {
            if (s == null) return null
            val i = s.indexOf(':')
            if (i <= 0 || i == s.length - 1) return null
            val kind = PromptKind.entries.firstOrNull { it.name == s.substring(0, i) } ?: return null
            return PromptFingerprint(s.substring(i + 1), kind)
        }
    }
}

/**
 * Decides whether a reply given from outside the app (the notification's inline reply, the
 * watch) may be typed into the session. Every session runs in yolo mode, so a permission
 * dialog is the last safety gate: an "Ano" written for command A must never approve command B
 * that took A's place while the notification sat on the wrist. Anything unknown fails closed.
 */
object ReplyGuard {
    enum class Reply {
        /** Free text, followed by Enter. */
        TEXT,
        /** The watch's Ano/Ne: a dialog answer. */
        APPROVE,
    }

    sealed interface Verdict {
        data object Send : Verdict
        data class Refuse(val reason: String) : Verdict
    }

    /** Placeholder key of a [PromptKind.READY] prompt no event named (see [promptShown]). */
    const val IDLE_KEY = "idle"

    /**
     * [notified]: the prompt the user answered (from the notification or the watch row).
     * [current]: the newest prompt the session announced, null once it went back to work.
     * [activity]: the session's current activity. [deliverable]: the input reaches the session now
     * rather than being queued for a reconnect, when the state it was checked against is long gone.
     */
    fun check(
        notified: PromptFingerprint?,
        current: PromptFingerprint?,
        activity: SessionActivity?,
        deliverable: Boolean,
        reply: Reply,
    ): Verdict {
        if (notified == null) return Verdict.Refuse("the reply names no prompt")
        if (!deliverable) return Verdict.Refuse("the session is not connected")
        return when (notified.kind) {
            // Only the very dialog the user saw: a new perm/ask or a screen detection replaces
            // the key, and going back to work clears it.
            PromptKind.DIALOG -> when {
                activity != SessionActivity.APPROVAL_NEEDED -> Verdict.Refuse("no dialog is open now ($activity)")
                current != notified -> Verdict.Refuse("another prompt is open now (${current?.key})")
                else -> Verdict.Send
            }
            // A new instruction for an idle session may answer any completion, not only the one
            // notified, as long as no selector is open to catch it.
            PromptKind.READY -> when {
                reply != Reply.TEXT -> Verdict.Refuse("a dialog answer for a session that asked no question")
                activity != SessionActivity.WAITING_FOR_INPUT -> Verdict.Refuse("the session is not at its input prompt ($activity)")
                current?.kind == PromptKind.DIALOG -> Verdict.Refuse("a dialog is open now (${current.key})")
                else -> Verdict.Send
            }
        }
    }

    /**
     * The prompt a view of the session shows right now (the watch row): the announced one when
     * it agrees with [activity]. A ready session nobody announced (a silence drop, a restarted
     * app) still offers a free-text reply, which [check] judges by the state alone; an approval
     * with no announced dialog offers nothing that could pass.
     */
    fun promptShown(current: PromptFingerprint?, activity: SessionActivity?): PromptFingerprint? = when (activity) {
        SessionActivity.WAITING_FOR_INPUT -> current?.takeIf { it.kind == PromptKind.READY }
            ?: PromptFingerprint(IDLE_KEY, PromptKind.READY)
        SessionActivity.APPROVAL_NEEDED -> current?.takeIf { it.kind == PromptKind.DIALOG }
        else -> null
    }
}

/**
 * The newest prompt each session announced. Set by every prompt event (also one that raised no
 * alert: the active tab in the foreground, an old replayed `perm`), cleared as soon as the
 * session leaves the prompt states — after WORKING, an APPROVAL with no new event must not
 * revive the key of the dialog that was answered.
 */
internal class PromptTokens {
    private val lock = Any()
    private val tokens = HashMap<String, PromptFingerprint>()

    fun note(sessionId: String, prompt: PromptFingerprint) = synchronized(lock) { tokens[sessionId] = prompt }

    fun onActivity(sessionId: String, activity: SessionActivity) {
        if (activity == SessionActivity.WAITING_FOR_INPUT || activity == SessionActivity.APPROVAL_NEEDED) return
        synchronized(lock) { tokens.remove(sessionId) }
    }

    fun current(sessionId: String): PromptFingerprint? = synchronized(lock) { tokens[sessionId] }

    fun forget(sessionId: String) = synchronized(lock) { tokens.remove(sessionId) }
}
