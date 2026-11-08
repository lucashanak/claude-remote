package com.clauderemote.wear

/**
 * The two pure decisions of the Jarvis-only watch (2026-11-08), unit-tested without a watch:
 * what the Čeká page shows ([WaitingRules]) and what a /sessions push does to a session's notification
 * ([WatchNotifyDecision]). Lukáš: "50 sessions na hodinkách je přehlcený" — the wrist shows only what waits for an
 * Ano/Ne (perm/ask of a session) and Jarvis' own proposals; a finished turn never reaches it.
 */
internal object WaitingRules {
    enum class DialogKind { PERMISSION, QUESTION }

    /**
     * The kind of dialog a row's prompt identity names (PromptFingerprint.encode on the phone: `KIND:key`), null for
     * anything else: READY / INSTRUCTION, none, or an approval only the screen saw and nobody can vouch for.
     */
    fun dialogKind(prompt: String?): DialogKind? = when {
        prompt == null -> null
        prompt.startsWith("PERMISSION:") && prompt.length > "PERMISSION:".length -> DialogKind.PERMISSION
        prompt.startsWith("QUESTION:") && prompt.length > "QUESTION:".length -> DialogKind.QUESTION
        else -> null
    }

    /**
     * Waits for Lukáš and can be answered from the wrist: an open dialog the phone can name. Answering is the existing
     * /approve and /reply path with this identity, which the phone checks with ReplyGuard before typing anything.
     */
    fun isActionable(s: WearSessionInfo): Boolean = s.activity == "APPROVAL_NEEDED" && dialogKind(s.prompt) != null

    sealed interface Item {
        val key: String

        /** A session's open dialog: Ano/Ne; a [DialogKind.QUESTION] also takes a typed or dictated answer. */
        data class Ask(val session: WearSessionInfo, val kind: DialogKind, val body: String) : Item {
            override val key: String get() = "ask:${session.id}:${session.prompt}"
        }

        /** One of Jarvis' proposals (mail / open / close / restore). */
        data class Proposal(val proposal: WearJarvisProposal) : Item {
            override val key: String get() = "prop:${proposal.id}"
        }
    }

    /**
     * The Čeká page: open dialogs first (they hold a session back), newest first, then Jarvis' proposals in the CLI's
     * order (oldest first). Empty = "Klid, pane."
     */
    fun items(sessions: List<WearSessionInfo>, proposals: List<WearJarvisProposal>): List<Item> {
        val asks = sessions.filter { isActionable(it) }
            .sortedByDescending { it.lastMessageAt }
            .map { Item.Ask(it, dialogKind(it.prompt)!!, askBody(it)) }
        return asks + proposals.map { Item.Proposal(it) }
    }

    /**
     * What a dialog card shows: the whole text the phone resolved for it (what is being approved must be readable in
     * full), the summary only when there is nothing else.
     */
    fun askBody(s: WearSessionInfo): String =
        s.notifyBody?.takeIf { it.isNotBlank() } ?: s.lastMessage?.takeIf { it.isNotBlank() }
            ?: s.summary?.takeIf { it.isNotBlank() } ?: "Čeká na schválení"

    /** How many dialogs wait (the tile's "▲ N ke schválení"). */
    fun askCount(sessions: List<WearSessionInfo>): Int = sessions.count { isActionable(it) }
}

internal object WatchNotifyDecision {
    sealed interface Action {
        data object Skip : Action
        /** Take the session's notification down: the dialog is gone (answered, moved on, the tab closed). */
        data object Cancel : Action
        /** Show the dialog; [alert] = vibrate (a new dialog), else quietly (the same dialog shown again). */
        data class Post(val alert: Boolean) : Action
        /** The same dialog with a corrected text: rewrite it silently, only while it is still on the wrist. */
        data object Repair : Action
    }

    /** [baseline] = what to store for the session afterwards (null = forget it: it left the snapshot). */
    data class Result(val action: Action, val baseline: NotifyBaseline?)

    /**
     * One session of a /sessions push. [prev] = the stored baseline (null = never seen), [row] = the session in this
     * push (null = gone from it), [bodyHash] = hash of the body the notification would show.
     *
     * Keyed on the prompt identity, not on activity flips and message stamps: a hook `perm`/`ask` key is unique per
     * dialog, so the same dialog never vibrates twice (a reconnect, a re-delivered DataItem, a restarted process all
     * re-report it) and every new one does, even inside one turn. A session never seen before is recorded as already
     * announced: a fresh install must not buzz for every dialog that has been open for hours.
     */
    fun decide(prev: NotifyBaseline?, row: WearSessionInfo?, bodyHash: Int): Result {
        if (row == null) {
            return Result(if (prev?.activity == APPROVAL) Action.Cancel else Action.Skip, null)
        }
        // A phone that lost its link marks every session DISCONNECTED: not a change of the dialog. Keep the
        // notification (the phone's check refuses an answer that no longer fits) and decide again on reconnect.
        if (row.activity == "DISCONNECTED") {
            return Result(Action.Skip, (prev ?: NotifyBaseline()).copy(activity = row.activity))
        }
        if (!WaitingRules.isActionable(row)) {
            val showing = prev?.activity == APPROVAL || prev?.activity == "DISCONNECTED" && prev.notifiedPrompt != null
            return Result(
                if (showing) Action.Cancel else Action.Skip,
                (prev ?: NotifyBaseline()).copy(activity = row.activity),
            )
        }
        val announced = NotifyBaseline(activity = row.activity, notifiedPrompt = row.prompt, notifiedBodyHash = bodyHash)
        if (prev == null) return Result(Action.Skip, announced)
        if (row.prompt != prev.notifiedPrompt) {
            return Result(Action.Post(alert = true), announced.copy(spokenHash = prev.spokenHash))
        }
        val keep = announced.copy(spokenHash = prev.spokenHash)
        // The same dialog back after the phone briefly called the session something else: shown again, quietly.
        if (prev.activity != APPROVAL) return Result(Action.Post(alert = false), keep)
        if (bodyHash != prev.notifiedBodyHash) return Result(Action.Repair, keep)
        return Result(Action.Skip, keep)
    }

    private const val APPROVAL = "APPROVAL_NEEDED"
}
