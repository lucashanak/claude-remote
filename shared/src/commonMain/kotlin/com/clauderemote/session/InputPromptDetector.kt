package com.clauderemote.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Detects when Claude Code CLI transitions to "waiting for user input".
 *
 * Strategy: wait for output QUIESCENCE (no new bytes for [QUIESCENCE_MS]),
 * then ask the platform for a snapshot of the rendered terminal grid and let
 * [ScreenStateClassifier] decide working vs. idle by the color of the
 * "working indicator" slot above the input box.
 *
 * Rationale: the old regex-on-raw-stream approach matched `❯` and fired
 * whenever the prompt box was redrawn — but the box is visible even while
 * Claude is streaming, so it produced false positives on nearly every turn.
 * The rendered cell grid with per-cell colors is unambiguous: Claude's
 * working indicator is always dark red, idle content never is.
 *
 * Also parses context window / usage stats from the raw stream — unrelated
 * to prompt detection, kept here for historical reasons.
 */
class InputPromptDetector(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) {

    private val sessionStates = mutableMapOf<String, SessionState>()
    /** Rolling stripped-ANSI output buffer per session — used by context/usage parsers. */
    private val recentOutput = mutableMapOf<String, StringBuilder>()

    @Volatile private var suppressUntil = 0L

    /**
     * Platform-provided screen snapshot reader. The implementation MUST marshal
     * onto whatever thread is safe to access the emulator (main looper on
     * Android, EDT on Swing) — this class invokes it from [scope]'s dispatcher.
     */
    var screenReader: (suspend (sessionId: String) -> ScreenStateSnapshot?)? = null

    /** Fired once per transition into "waiting for input". */
    var onDetection: ((PromptDetection) -> Unit)? = null

    /** Fired on every quiescence check (WORKING / IDLE / UNKNOWN) so UI can update activity dots. */
    var onStateChange: ((sessionId: String, state: ClaudeState) -> Unit)? = null

    /** Suppress detections for [millis] — use during tmux redraws / reconnects. */
    fun suppressFor(millis: Long) {
        suppressUntil = currentTimeMillis() + millis
    }

    /**
     * Feed a chunk of terminal output. Always updates the recent-output buffer
     * (for context/usage parsing) and, unless suppressed, resets the
     * per-session quiescence timer. Returns immediately; detection is async.
     */
    fun onOutput(sessionId: String, text: String) {
        feedRecentOutput(sessionId, text)

        if (currentTimeMillis() < suppressUntil) return

        val state = sessionStates.getOrPut(sessionId) { SessionState() }
        state.pendingCheck?.cancel()
        state.pendingCheck = scope.launch {
            delay(QUIESCENCE_MS)
            runIdleCheck(sessionId)
        }
    }

    private suspend fun runIdleCheck(sessionId: String) {
        if (currentTimeMillis() < suppressUntil) return

        val state = sessionStates[sessionId] ?: return
        val snapshot = screenReader?.invoke(sessionId) ?: return
        val classified = ScreenStateClassifier.classify(snapshot)

        onStateChange?.invoke(sessionId, classified)

        if (classified != ClaudeState.IDLE) return
        // Don't notify on startup prompt — only after user has interacted at least once.
        if (!state.userHasInteracted) return
        // Already notified; wait for user input to reset.
        if (state.waitingForInput) return
        // Cooldown against flapping.
        val now = currentTimeMillis()
        if (now - state.lastDetectionTime < COOLDOWN_MS) return

        state.waitingForInput = true
        state.lastDetectionTime = now
        onDetection?.invoke(PromptDetection(sessionId, PromptType.INPUT_PROMPT))
    }

    /** Reset the latch so the next idle transition will fire a detection. */
    fun onUserInput(sessionId: String) {
        val state = sessionStates.getOrPut(sessionId) { SessionState() }
        state.waitingForInput = false
        state.userHasInteracted = true
    }

    fun removeSession(sessionId: String) {
        sessionStates.remove(sessionId)?.pendingCheck?.cancel()
        recentOutput.remove(sessionId)
    }

    /** Feed a chunk to the rolling buffer used by context/usage parsers. */
    fun feedRecentOutput(sessionId: String, text: String) {
        val buf = recentOutput.getOrPut(sessionId) { StringBuilder() }
        buf.append(stripAnsi(text))
        if (buf.length > 2048) buf.delete(0, buf.length - 2048)
    }

    // ---- Context / usage parsing (independent of prompt detection) ----

    fun parseContextPercent(sessionId: String, text: String): Int? {
        val stripped = recentOutput[sessionId]?.toString() ?: stripAnsi(text)

        CONTEXT_RATIO_REGEX.find(stripped)?.let { match ->
            val used = parseTokenCount(match.groupValues[1])
            val total = parseTokenCount(match.groupValues[2])
            if (total > 0) return ((used / total) * 100).toInt().coerceIn(0, 100)
        }
        CONTEXT_PERCENT_REGEX.find(stripped)?.let { match ->
            return match.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
        }
        TOKENS_REMAINING_REGEX.find(stripped)?.let { match ->
            val remaining = parseTokenCount(match.groupValues[1])
            val total = 200.0
            if (remaining in 0.0..total) {
                return ((1.0 - remaining / total) * 100).toInt().coerceIn(0, 100)
            }
        }
        return null
    }

    fun parseUsage(sessionId: String, text: String): Map<String, Int>? {
        val stripped = recentOutput[sessionId]?.toString() ?: stripAnsi(text)
        val result = mutableMapOf<String, Int>()
        SESSION_USAGE_REGEX.find(stripped)?.let {
            it.groupValues[1].toIntOrNull()?.let { pct -> result["session"] = pct }
        }
        WEEK_USAGE_REGEX.find(stripped)?.let {
            it.groupValues[1].toIntOrNull()?.let { pct -> result["week"] = pct }
        }
        return result.ifEmpty { null }
    }

    private fun parseTokenCount(s: String): Double {
        val clean = s.replace(",", "").trim()
        return when {
            clean.endsWith("k", ignoreCase = true) -> clean.dropLast(1).toDoubleOrNull()?.times(1.0) ?: 0.0
            clean.endsWith("m", ignoreCase = true) -> clean.dropLast(1).toDoubleOrNull()?.times(1000.0) ?: 0.0
            else -> clean.toDoubleOrNull()?.div(1000.0) ?: 0.0
        }
    }

    private class SessionState {
        var waitingForInput = false
        var lastDetectionTime = 0L
        var userHasInteracted = false
        var pendingCheck: Job? = null
    }

    companion object {
        /** How long to wait after the last output chunk before running an idle check. */
        private const val QUIESCENCE_MS = 1000L
        /** Minimum time between two detections on the same session. */
        private const val COOLDOWN_MS = 3000L

        private val ANSI_REGEX = Regex("\u001B(?:\\[\\??[0-9;]*[a-zA-Z]|\\][^\u0007]*\u0007)")
        private val CONTEXT_RATIO_REGEX = Regex("([\\d,.]+[km]?)\\s*/\\s*([\\d,.]+[km]?)\\s*tokens", RegexOption.IGNORE_CASE)
        private val CONTEXT_PERCENT_REGEX = Regex("(\\d{1,3})%\\s*context|context[:\\s]+(\\d{1,3})%", RegexOption.IGNORE_CASE)
        private val TOKENS_REMAINING_REGEX = Regex("([\\d,.]+[km]?)\\s*tokens?\\s*remaining", RegexOption.IGNORE_CASE)
        private val SESSION_USAGE_REGEX = Regex("Current session[\\s\\S]{0,50}?(\\d{1,3})%\\s*used", RegexOption.IGNORE_CASE)
        private val WEEK_USAGE_REGEX = Regex("Current week[\\s\\S]{0,60}?(\\d{1,3})%\\s*used", RegexOption.IGNORE_CASE)

        fun stripAnsi(text: String): String = ANSI_REGEX.replace(text, "")

        private fun currentTimeMillis(): Long = System.currentTimeMillis()
    }
}

enum class PromptType(val displayHint: String) {
    INPUT_PROMPT("Claude is ready for input"),
    APPROVAL_NEEDED("Approval needed"),
    PERMISSION_PROMPT("Permission requested"),
}

data class PromptDetection(val sessionId: String, val type: PromptType)
