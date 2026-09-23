package com.clauderemote.session

import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.SessionStatus

/**
 * Which sessions a "restart Claude everywhere" pass should actually touch.
 *
 * Split out of [SessionOrchestrator] because the rule is the whole risk of the
 * feature — the restart itself needs a live SSH session and cannot be unit
 * tested, while getting the rule wrong silently kills conversations.
 */
internal object RestartTargets {

    /**
     * Sessions that can be respawned without losing anything:
     *
     *  - ACTIVE only. A disconnected or errored tab has no live connection, so
     *    a restart there is a no-op that still costs an SSH round trip — and on
     *    a fleet-wide pass those add up to a stall.
     *  - a `claudeSessionId` is required: the respawn resumes that uuid, and
     *    without one the restart cannot bring the conversation back (the
     *    orchestrator refuses it for exactly this reason).
     *
     * Order follows the tab list so the user can predict which pane blanks next.
     */
    fun eligible(tabs: List<ClaudeSession>): List<String> =
        tabs.filter { it.status == SessionStatus.ACTIVE && !it.claudeSessionId.isNullOrBlank() }
            .map { it.id }
}
