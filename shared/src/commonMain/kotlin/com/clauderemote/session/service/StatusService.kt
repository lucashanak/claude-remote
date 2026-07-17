package com.clauderemote.session.service

import com.clauderemote.model.SessionActivity
import com.clauderemote.session.TabManager
import com.clauderemote.session.status.RemoteSessionStatus
import com.clauderemote.session.status.SessionStatusPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update

/**
 * Per-session activity state (health indicator dots) + the per-session OMC
 * remote-status pollers (active skill + subagent count).
 *
 * Extracted verbatim from SessionOrchestrator: the state, timing, ordering,
 * locking and atomic map operations are unchanged — a pure move so the public
 * API and runtime behavior stay identical. [markWorkSeen]/[probeGitOnIdle] are
 * thin bridges back to the transcript/git services the orchestrator owns.
 */
internal class StatusService(
    private val scope: CoroutineScope,
    private val registry: ConnectionRegistry,
    private val tabManager: TabManager,
    private val isBackground: () -> Boolean,
    private val markWorkSeen: (String) -> Unit,
    private val probeGitOnIdle: (String) -> Unit,
) {
    // Per-session OMC state pollers (active skill + subagent count).
    private val statusPollers = mutableMapOf<String, SessionStatusPoller>()
    private val statusLock = Any()

    // Per-session activity state (for health indicator dots)
    private val _sessionActivities = kotlinx.coroutines.flow.MutableStateFlow<Map<String, SessionActivity>>(emptyMap())
    val sessionActivities: kotlinx.coroutines.flow.StateFlow<Map<String, SessionActivity>> = _sessionActivities

    // When APPROVAL_NEEDED was last asserted per session — used to protect a
    // fresh screen-detected approval from being clobbered by a stale statusline
    // WORKING render (see the grace check in emit()). Refreshed every ~3s by
    // the detector's APPROVAL re-check while the dialog is on screen.
    private val lastApprovalAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun updateActivity(sessionId: String, activity: SessionActivity) {
        if (activity == SessionActivity.WORKING) markWorkSeen(sessionId)
        if (activity == SessionActivity.APPROVAL_NEEDED) {
            lastApprovalAt[sessionId] = System.currentTimeMillis()
        }
        val previous = _sessionActivities.value[sessionId]
        _sessionActivities.update { it + (sessionId to activity) }
        // Refresh git status when the session goes idle (e.g. a command just
        // finished and may have changed the branch/dirty state). Debounced
        // against the 90s loop via lastGitProbeAt. Off-thread; never blocks.
        if (activity == SessionActivity.WAITING_FOR_INPUT && previous != SessionActivity.WAITING_FOR_INPUT) {
            probeGitOnIdle(sessionId)
        }
    }

    /** Current activity for [id], or null if none tracked. */
    fun currentActivity(id: String): SessionActivity? = _sessionActivities.value[id]

    /** When APPROVAL_NEEDED was last asserted for [id] (ms), or 0L. */
    fun lastApprovalAtMs(id: String): Long = lastApprovalAt[id] ?: 0L

    fun remoteStatusFlow(sessionId: String): kotlinx.coroutines.flow.StateFlow<RemoteSessionStatus> {
        val tab = tabManager.getTab(sessionId)
            ?: return kotlinx.coroutines.flow.MutableStateFlow(RemoteSessionStatus())
        val poller = synchronized(statusLock) {
            // Only ONE tab's status is rendered at a time (the active Chat
            // view). Stop the others here instead of leaking them: visiting all
            // 21 tabs used to leave 21 pollers alive — each opening SSH execs
            // every 5 s, in the background too. A stopped poller restarts
            // lazily the next time its tab becomes active.
            statusPollers.forEach { (id, p) -> if (id != sessionId) p.stop() }
            statusPollers.getOrPut(sessionId) {
                SessionStatusPoller(
                    server = tab.server,
                    cwd = tab.folder,
                    claudeSessionIdProvider = { tabManager.getTab(sessionId)?.claudeSessionId },
                    scope = scope,
                    liveSession = { registry.ssh(sessionId)?.getSession() },
                    isBackground = { isBackground() },
                )
            }
        }
        poller.start()
        return poller.status
    }

    /** Cancel + drop this session's status poller (disconnect). */
    fun stopPoller(sessionId: String) {
        synchronized(statusLock) {
            statusPollers.remove(sessionId)?.stop()
        }
    }

    /** Drop a disconnected session's activity entry. */
    fun clearActivity(sessionId: String) { _sessionActivities.update { it - sessionId } }
}
