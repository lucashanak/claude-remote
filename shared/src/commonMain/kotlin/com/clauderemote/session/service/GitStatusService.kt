package com.clauderemote.session.service

import com.clauderemote.model.GitStatus
import com.clauderemote.session.TabManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Per-session git working-dir status polling (branch + dirty/ahead/behind).
 * Extracted verbatim from SessionOrchestrator: the state, timing, ordering,
 * locking and atomic map operations are unchanged — a pure move so the public
 * API and runtime behavior stay identical.
 */
internal class GitStatusService(
    private val scope: CoroutineScope,
    private val registry: ConnectionRegistry,
    private val tabManager: TabManager,
    private val isBackground: () -> Boolean,
) {
    // Per-session git status of the working directory (branch + dirty/ahead/behind).
    // Absence of a sessionId key means "not a git repo" — UI shows no chip.
    private val _gitStatuses = kotlinx.coroutines.flow.MutableStateFlow<Map<String, GitStatus>>(emptyMap())
    val gitStatuses: kotlinx.coroutines.flow.StateFlow<Map<String, GitStatus>> = _gitStatuses

    // Per-session git-status pollers (branch + dirty/ahead/behind of the working dir).
    private val gitStatusJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    // Per-session timestamp (ms) of the last git probe, used to debounce the
    // idle-transition trigger against the 90s polling loop so they don't
    // double-fire within a few seconds.
    private val lastGitProbeAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Periodically probe the git status of the session's working directory
     * (branch + dirty/ahead/behind) and publish it to [gitStatuses]. Mirrors
     * [startServerUsagePolling]: runs the exec off the UI thread, respects
     * [isBackground], and never blocks. A non-git directory (or any failure)
     * clears the entry so the UI shows no chip. Cancelled in [dispose].
     */
    fun startGitStatusPolling(sessionId: String) {
        gitStatusJobs[sessionId]?.cancel()
        gitStatusJobs[sessionId] = scope.launch {
            kotlinx.coroutines.delay(3000) // initial delay — let the session settle
            while (isActive) {
                if (!isBackground()) {
                    probeGitStatusOnce(sessionId)
                }
                kotlinx.coroutines.delay(90_000) // poll every 90s
            }
        }
    }

    /**
     * Run a single git-status probe (one exec + parse + StateFlow update) for
     * [sessionId]. Shared by the 90s polling loop and the activity→idle trigger.
     * Updates [lastGitProbeAt] on entry so the two callers debounce each other.
     * The folder may be the literal "~" or a relative path, so we mirror the
     * expansion idiom used by [probeTranscriptExists]: `${F/#~/$HOME}` plus a
     * relative-path anchor under $HOME — JSch's non-login exec shell does not
     * expand `~` on its own.
     */
    suspend fun probeGitStatusOnce(sessionId: String) {
        lastGitProbeAt[sessionId] = System.currentTimeMillis()
        try {
            val folder = tabManager.getTab(sessionId)?.folder ?: "~"
            val conn = registry.ssh(sessionId) ?: return
            val sshSession = conn.getSession() ?: return
            val escapedFolder = folder.replace("'", "'\\''")
            val cmd = """
                F='$escapedFolder'
                E="${'$'}{F/#~/${'$'}HOME}"
                case "${'$'}E" in /*) ;; *) E="${'$'}HOME/${'$'}E";; esac
                cd "${'$'}E" 2>/dev/null || exit 0
                git rev-parse --abbrev-ref HEAD 2>/dev/null
                git status --porcelain 2>/dev/null | head -1
                git rev-list --left-right --count @{u}...HEAD 2>/dev/null
            """.trimIndent()
            val output = execReadWithWatchdog(sshSession, cmd, totalMs = 20_000)
            parseGitStatus(sessionId, output)
        } catch (_: Exception) {
            _gitStatuses.update { it - sessionId }
        }
    }

    /**
     * Refresh git status when the session goes idle (e.g. a command just
     * finished and may have changed the branch/dirty state). Debounced against
     * the 90s loop via [lastGitProbeAt]. Off-thread; never blocks.
     */
    fun probeOnIdle(sessionId: String) {
        if (isBackground()) return
        val last = lastGitProbeAt[sessionId] ?: 0L
        if (System.currentTimeMillis() - last >= 5_000L) {
            scope.launch { probeGitStatusOnce(sessionId) }
        }
    }

    /**
     * Parse the multi-line output of the git probe. Line 1 is the branch
     * (empty/error → not a git repo → clear the entry). The presence of a
     * porcelain line means dirty. A trailing "behind<TAB>ahead" line gives the
     * ahead/behind counts. Defensive: any malformed output → null.
     */
    private fun parseGitStatus(sessionId: String, raw: String) {
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val branch = lines.firstOrNull()
        if (branch.isNullOrBlank()) {
            // Not a git repo (or unparseable) — show no chip.
            _gitStatuses.update { it - sessionId }
            return
        }
        val resolvedBranch = branch
        // A left-right count line looks like "2\t3" (behind \t ahead).
        val countLine = lines.lastOrNull { it.matches(Regex("""\d+\s+\d+""")) }
        var behind = 0
        var ahead = 0
        if (countLine != null) {
            val parts = countLine.split(Regex("\\s+"))
            behind = parts.getOrNull(0)?.toIntOrNull() ?: 0
            ahead = parts.getOrNull(1)?.toIntOrNull() ?: 0
        }
        // Dirty if there's any porcelain output (a line that isn't the branch
        // and isn't the count line).
        val dirty = lines.any { it != resolvedBranch && it != countLine }
        _gitStatuses.update {
            it + (sessionId to GitStatus(branch = resolvedBranch, dirty = dirty, ahead = ahead, behind = behind))
        }
    }

    /** Cancel + drop this session's git-status polling job (disconnect). */
    fun stopPolling(sessionId: String) { gitStatusJobs.remove(sessionId)?.cancel() }

    /** Drop a disconnected session's git-status entry. */
    fun clearSession(sessionId: String) { _gitStatuses.update { it - sessionId } }
}
