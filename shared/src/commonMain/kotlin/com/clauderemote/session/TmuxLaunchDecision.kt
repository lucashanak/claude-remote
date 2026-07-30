package com.clauderemote.session

/**
 * What to do with a session's tmux when (re)connecting: attach to the live
 * session, recreate it (resuming the conversation or not), launch it fresh, or
 * forget it because another device closed it.
 *
 * Extracted from SessionOrchestrator.sendTmuxCommand so the decision — the one
 * that determines whether the user lands back in their own conversation, in an
 * empty one, or has the session deleted — is pure and exhaustively testable.
 * All I/O (probes, logging, sending the command) stays with the caller.
 */
internal sealed interface TmuxLaunchDecision {

    /** Brand-new session: create the tmux session and launch claude fresh. */
    data object FreshLaunch : TmuxLaunchDecision

    /** tmux is alive — plain attach. */
    data object Attach : TmuxLaunchDecision

    /**
     * The session was closed on ANOTHER device (tmux gone, pushed out of the
     * shared manifest, and the tmux server is provably up with live peers).
     * The caller must remove it from storage, disconnect, and throw.
     */
    data object ForgetClosedElsewhere : TmuxLaunchDecision

    /**
     * tmux is gone — recreate it and re-launch claude.
     *
     * @param resume pass `--resume` (a transcript exists) instead of only `--session-id`.
     * @param withSessionId pass the session's `claudeSessionId` at all; false only
     *   when the session has none (then the launch command omits both arguments).
     * @param afterSuspectedServerOutage this rebuild is the fall-through from the
     *   closed-elsewhere check finding no live peers, i.e. a suspected WHOLE-server
     *   tmux death. Carried on the decision purely so the caller can emit the same
     *   log line it always has; it does not change the command that gets built.
     */
    data class Rebuild(
        val resume: Boolean,
        val withSessionId: Boolean,
        val afterSuspectedServerOutage: Boolean = false,
    ) : TmuxLaunchDecision
}

/**
 * Parsing half of the liveness gate that feeds `hasLivePeers` in [TmuxLaunchDecider].
 *
 * Extracted from `SessionOrchestrator.serverHasOtherLiveSession` so the predicate
 * is testable without an SSH session: the orchestrator keeps the exec round-trip
 * and its fail-closed error handling, this decides what the output MEANS.
 *
 * Getting this wrong is expensive in one direction: a false "peers are alive"
 * lets the closed-elsewhere branch delete a session during a whole-server tmux
 * outage. Hence every unknown answer must read as "no peers".
 */
internal object TmuxPeerLiveness {

    /** Only our own sessions count as peers; a user's unrelated tmux session does not. */
    const val SESSION_PREFIX = "claude-server-"

    /** Keepalive session the restore scripts park on the server; never a peer. */
    const val ANCHOR = "__anchor__"

    /**
     * True iff [listSessionsOutput] (`tmux list-sessions -F '#{session_name}'`)
     * names at least one live session that is ours, is not [excludeTmuxName],
     * and is not the [ANCHOR] keepalive.
     *
     * Empty output — a dead tmux server, or a failed exec whose stderr was
     * swallowed — yields false, which is what makes a server-wide outage fall
     * through to rebuild instead of forgetting every session.
     */
    fun hasOtherLivePeer(listSessionsOutput: String, excludeTmuxName: String): Boolean =
        listSessionsOutput.lineSequence()
            .map { it.trim() }
            .any { it.startsWith(SESSION_PREFIX) && it != excludeTmuxName && it != ANCHOR }
}

/**
 * Pure decision function for [TmuxLaunchDecision].
 *
 * Every probe is an SSH round-trip, sometimes over a cellular link, so they are
 * passed as suspend suppliers and invoked **at most once** and **only** on the
 * paths that genuinely need them (see [decide]).
 */
internal object TmuxLaunchDecider {

    /**
     * Cheap inputs by value, expensive probes as lazy suppliers.
     *
     * @param isNew the session is being created now — decided with NO probes.
     * @param checkClosedElsewhere trust the shared manifest to mean "the user
     *   closed this on another device". Only the reconnect-to-an-already-tracked-tab
     *   path passes true; launchSession's attach/history-resume callers pass false
     *   since their target may legitimately be new to sessions.json.
     * @param hasClaudeSessionId the session carries a claude session UUID.
     * @param tmuxExists probe: is the named tmux session alive?
     * @param stillTracked probe: is the tmux name still in the server's shared manifest?
     * @param hasLivePeers probe: is the tmux server provably up with ≥1 OTHER live session?
     * @param hasTranscript probe: did claude write a jsonl transcript for the UUID?
     */
    suspend fun decide(
        isNew: Boolean,
        checkClosedElsewhere: Boolean,
        hasClaudeSessionId: Boolean,
        tmuxExists: suspend () -> Boolean,
        stillTracked: suspend () -> Boolean,
        hasLivePeers: suspend () -> Boolean,
        hasTranscript: suspend () -> Boolean,
    ): TmuxLaunchDecision {
        if (isNew) return TmuxLaunchDecision.FreshLaunch

        // Probe tmux first. If the named session is gone (server reboot, someone
        // killed it), recreate it and re-launch claude with --resume so the
        // conversation continues. Otherwise plain attach.
        val alive = tmuxExists()

        var afterSuspectedServerOutage = false
        if (!alive && checkClosedElsewhere && !stillTracked()) {
            // Another device's forgetSession() already pushed this tmux name out
            // of the shared sessions.json — respect that instead of resurrecting
            // a session the user consciously closed elsewhere.
            //
            // BUT "!tmuxExists && !stillTracked" is AMBIGUOUS: a WHOLE-server
            // tmux death makes probeTmuxSession=false for EVERY session AND
            // (with a wiped/empty manifest) stillTrackedOnServer=false too, so
            // this branch used to forget every session on a transient
            // server-wide outage (the repeated whole-server session loss).
            // Gate on positive liveness: only treat it as closed-elsewhere when
            // the tmux server is PROVABLY up with ≥1 OTHER live claude-server-*
            // session. No live peers ⇒ whole-server outage ⇒ fall through to the
            // rebuild/--resume path instead of forgetting.
            if (hasLivePeers()) return TmuxLaunchDecision.ForgetClosedElsewhere
            afterSuspectedServerOutage = true
        }

        if (alive) return TmuxLaunchDecision.Attach

        if (!hasClaudeSessionId) {
            return TmuxLaunchDecision.Rebuild(
                resume = false,
                withSessionId = false,
                afterSuspectedServerOutage = afterSuspectedServerOutage,
            )
        }

        // Resume only works if claude actually wrote a transcript file for this
        // UUID. The transcript appears lazily — first user/assistant turn — so a
        // session that was launched but never interacted with has no jsonl, and
        // `--resume` would print "No conversation found". In that case we
        // re-launch fresh with the same `--session-id` so future restarts can
        // resume.
        return TmuxLaunchDecision.Rebuild(
            resume = hasTranscript(),
            withSessionId = true,
            afterSuspectedServerOutage = afterSuspectedServerOutage,
        )
    }
}
