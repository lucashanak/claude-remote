package com.clauderemote.wear

/**
 * The two pure decisions of the Jarvis-only watch (2026-11-08), unit-tested without a watch:
 * what the Čeká page shows ([WaitingRules]) and what a /sessions push does to a session's notification
 * ([WatchNotifyDecision]). Lukáš: "50 sessions na hodinkách je přehlcený" — the wrist shows only what waits for an
 * Ano/Ne (perm/ask of a session) and Jarvis' own proposals; a finished turn never reaches it.
 */
internal object WaitingRules {
    enum class DialogKind { PERMISSION, QUESTION }

    /** Shown on a card and a notification the wrist cannot answer (review H1). */
    const val UNNAMED_TEXT = "Čeká schválení – odpověz na telefonu"

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

    fun isApproval(s: WearSessionInfo): Boolean = s.activity == "APPROVAL_NEEDED"

    /**
     * Waits for Lukáš and can be answered from the wrist: an open dialog the phone can name. Answering is the existing
     * /approve and /reply path with this identity, which the phone checks with ReplyGuard before typing anything.
     */
    fun isActionable(s: WearSessionInfo): Boolean = isApproval(s) && dialogKind(s.prompt) != null

    /**
     * An approval the phone cannot name (screen-only: `hookev.off`, a server without hooks, a READY not yet replaced).
     * Ano/Ne from the wrist would be refused, but it still waits for Lukáš: it is shown, without buttons (review H1 —
     * before the redesign every approval reached the wrist).
     */
    fun isUnnamedApproval(s: WearSessionInfo): Boolean = isApproval(s) && dialogKind(s.prompt) == null

    sealed interface Item {
        val key: String

        /**
         * A session's open dialog: Ano/Ne; a [DialogKind.QUESTION] also takes a typed or dictated answer. [kind] null =
         * an unnamed approval: the card only says it waits ([UNNAMED_TEXT]), no buttons.
         */
        data class Ask(val session: WearSessionInfo, val kind: DialogKind?, val body: String) : Item {
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
        val asks = sessions.filter { isApproval(it) }
            .sortedByDescending { it.lastMessageAt }
            .map { Item.Ask(it, dialogKind(it.prompt), askBody(it)) }
        return asks + proposals.map { Item.Proposal(it) }
    }

    /**
     * What a dialog card shows: the whole text the phone resolved for it (what is being approved must be readable in
     * full), the summary only when there is nothing else.
     */
    fun askBody(s: WearSessionInfo): String =
        s.notifyBody?.takeIf { it.isNotBlank() } ?: s.lastMessage?.takeIf { it.isNotBlank() }
            ?: s.summary?.takeIf { it.isNotBlank() } ?: "Čeká na schválení"

    /** How many approvals wait, answerable from the wrist or not (the tile's "▲ N ke schválení"). */
    fun askCount(sessions: List<WearSessionInfo>): Int = sessions.count { isApproval(it) }

    /**
     * The "dorolováno" mark of a proposal: its id AND its text (review L6). A proposal whose text changed under the same
     * id must be scrolled through again before it may be approved.
     */
    fun endKey(p: WearJarvisProposal): String = "end:${p.id}:${p.text.hashCode()}"

    /** "Poslat"/"Provést" is enabled: full text, scrolled through as it is now, not lapsed, no decision running. */
    fun canApprove(p: WearJarvisProposal, seenEnds: Collection<String>, expired: Boolean, busy: Boolean): Boolean =
        p.fullText && endKey(p) in seenEnds && !expired && !busy
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
     * Baseline activity of a session showing an approval the phone could not name (review M3). Kept apart from
     * APPROVAL_NEEDED so the identity of the named dialog shown before survives it: when that dialog is named again,
     * it comes back quietly instead of being taken for "already on the wrist".
     */
    const val UNNAMED = "APPROVAL_UNNAMED"

    private const val APPROVAL = "APPROVAL_NEEDED"

    private fun showsApproval(activity: String?) = activity == APPROVAL || activity == UNNAMED

    /**
     * One session of a /sessions push. [prev] = the stored baseline (null = never seen), [row] = the session in this
     * push (null = gone from it), [bodyHash] = hash of the body the notification would show. [seeding] = this is the
     * very first load of an empty store (a fresh install): a session never seen is then recorded as announced, so the
     * install does not buzz for every dialog open for hours. Otherwise a session never seen (a new tab whose first push
     * is already a dialog, a session back in the snapshot) is announced like any other (review M4).
     *
     * Keyed on the prompt identity, not on activity flips and message stamps: a hook `perm`/`ask` key is unique per
     * dialog, so the same dialog never vibrates twice (a reconnect, a re-delivered DataItem, a restarted process all
     * re-report it) and every new one does, even inside one turn. An approval nobody can name is shown too, without
     * buttons (review H1): it vibrates when the session enters it.
     */
    fun decide(prev: NotifyBaseline?, row: WearSessionInfo?, bodyHash: Int, seeding: Boolean = false): Result {
        if (row == null) {
            return Result(if (showsApproval(prev?.activity)) Action.Cancel else Action.Skip, null)
        }
        // A phone that lost its link marks every session DISCONNECTED: not a change of the dialog. Keep the notification
        // and the baseline as they are (the phone's check refuses an answer that no longer fits) and decide on reconnect.
        if (row.activity == "DISCONNECTED") {
            return Result(Action.Skip, prev ?: NotifyBaseline(activity = row.activity))
        }
        if (prev == null && seeding) {
            val act = if (WaitingRules.isUnnamedApproval(row)) UNNAMED else row.activity
            return Result(
                Action.Skip,
                NotifyBaseline(
                    activity = act, notifiedPrompt = row.prompt.takeIf { WaitingRules.isActionable(row) },
                    notifiedBodyHash = bodyHash, unnamedAlerted = act == UNNAMED,
                ),
            )
        }
        val before = prev ?: NotifyBaseline()
        if (WaitingRules.isUnnamedApproval(row)) {
            val keep = before.copy(activity = UNNAMED, notifiedBodyHash = bodyHash)
            return when {
                // entering it: a new request (a named dialog shown just before turns into it quietly, M3)
                !showsApproval(before.activity) -> Result(Action.Post(alert = true), keep.copy(unnamedAlerted = true))
                // from a named dialog: quietly, and this episode did not vibrate (a dialog named next must, see below)
                before.activity == APPROVAL -> Result(Action.Post(alert = false), keep.copy(unnamedAlerted = false))
                bodyHash != before.notifiedBodyHash -> Result(Action.Repair, keep)
                else -> Result(Action.Skip, keep)
            }
        }
        if (!WaitingRules.isActionable(row)) {
            return Result(
                if (showsApproval(before.activity)) Action.Cancel else Action.Skip,
                before.copy(activity = row.activity),
            )
        }
        val announced = NotifyBaseline(
            activity = row.activity, notifiedPrompt = row.prompt, notifiedBodyHash = bodyHash, spokenHash = before.spokenHash,
        )
        return when {
            // The phone named the dialog it showed as unnamed a moment ago: quietly, but only when that unnamed episode
            // vibrated. Entered quietly from a named A (A's answer and a new dialog B merged into one push), B is new.
            before.activity == UNNAMED && before.unnamedAlerted && row.prompt != before.notifiedPrompt ->
                Result(Action.Post(alert = false), announced)
            row.prompt != before.notifiedPrompt -> Result(Action.Post(alert = true), announced)
            // The same dialog back after the phone briefly called the session something else: shown again, quietly.
            before.activity != APPROVAL -> Result(Action.Post(alert = false), announced)
            bodyHash != before.notifiedBodyHash -> Result(Action.Repair, announced)
            else -> Result(Action.Skip, announced)
        }
    }
}
