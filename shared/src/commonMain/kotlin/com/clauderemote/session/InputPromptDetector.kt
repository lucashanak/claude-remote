package com.clauderemote.session

/**
 * Detects when Claude Code CLI is waiting for user input by analyzing terminal output.
 * Handles ANSI escape sequence stripping, debouncing, and per-session state tracking.
 */
class InputPromptDetector {

    private val sessionStates = mutableMapOf<String, SessionState>()
    // Buffer recent output for multi-chunk pattern matching (usage/context)
    private val recentOutput = mutableMapOf<String, StringBuilder>()

    /** Set true during buffer replays (tab switch, reconnect) to suppress false positives. */
    var suppressDetection = false

    /**
     * Analyze a chunk of terminal output. Returns a [PromptDetection] if Claude
     * transitioned to "waiting for input", or null if no prompt detected / already notified.
     */
    fun onOutput(sessionId: String, text: String): PromptDetection? {
        if (suppressDetection) return null

        val state = sessionStates.getOrPut(sessionId) { SessionState() }
        val stripped = stripAnsi(text)
        if (stripped.isBlank()) return null

        val type = detectPromptType(stripped) ?: return null

        // Don't notify on startup prompts — only after user has interacted at least once
        if (!state.userHasInteracted && type == PromptType.INPUT_PROMPT) return null

        // Debounce: don't fire again if already waiting
        if (state.waitingForInput) return null

        // Time-based debounce: suppress if < 3s since last notification
        val now = currentTimeMillis()
        if (now - state.lastNotificationTime < 3000L) return null

        state.waitingForInput = true
        state.lastNotificationTime = now
        return PromptDetection(sessionId, type)
    }

    /**
     * Call when the user sends input to a session. Resets the "waiting" state
     * so the next prompt will trigger a new notification.
     */
    fun onUserInput(sessionId: String) {
        val state = sessionStates[sessionId] ?: return
        state.waitingForInput = false
        state.userHasInteracted = true
    }

    fun removeSession(sessionId: String) {
        sessionStates.remove(sessionId)
        recentOutput.remove(sessionId)
    }

    private fun detectPromptType(stripped: String): PromptType? {
        // Check approval patterns first (more specific)
        if (stripped.contains("[Y/n]") || stripped.contains("[y/N]")) {
            return PromptType.APPROVAL_NEEDED
        }
        if (stripped.contains("Do you want to") || stripped.contains("permission")) {
            return PromptType.PERMISSION_PROMPT
        }

        // Check for Claude's input prompt: ❯ anywhere in the last 50 chars or last line
        if (stripped.contains('\u276F')) { // ❯
            return PromptType.INPUT_PROMPT
        }

        return null
    }

    private class SessionState {
        var waitingForInput = false
        var lastNotificationTime = 0L
        var userHasInteracted = false // Only notify after user has sent at least one input
    }

    /**
     * Parse context window usage from Claude Code terminal output.
     * Claude Code displays patterns like "132.9k tokens remaining" or "X% context".
     * Returns percentage used (0-100), or null if no context info found.
     */
    /** Feed output to the recent buffer for multi-chunk matching */
    fun feedRecentOutput(sessionId: String, text: String) {
        val buf = recentOutput.getOrPut(sessionId) { StringBuilder() }
        buf.append(stripAnsi(text))
        // Keep last 2KB
        if (buf.length > 2048) buf.delete(0, buf.length - 2048)
    }

    fun parseContextPercent(sessionId: String, text: String): Int? {
        val stripped = recentOutput[sessionId]?.toString() ?: stripAnsi(text)

        // Pattern: "XX.Xk/YYYk tokens" or similar ratio
        CONTEXT_RATIO_REGEX.find(stripped)?.let { match ->
            val used = parseTokenCount(match.groupValues[1])
            val total = parseTokenCount(match.groupValues[2])
            if (total > 0) return ((used / total) * 100).toInt().coerceIn(0, 100)
        }

        // Pattern: "XX% context" or "context: XX%"
        CONTEXT_PERCENT_REGEX.find(stripped)?.let { match ->
            return match.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
        }

        // Pattern: "XXXk tokens remaining" with known total (200k default)
        TOKENS_REMAINING_REGEX.find(stripped)?.let { match ->
            val remaining = parseTokenCount(match.groupValues[1])
            val total = 200.0 // assume 200k default context
            if (remaining in 0.0..total) {
                return ((1.0 - remaining / total) * 100).toInt().coerceIn(0, 100)
            }
        }

        return null
    }

    private fun parseTokenCount(s: String): Double {
        val clean = s.replace(",", "").trim()
        return when {
            clean.endsWith("k", ignoreCase = true) -> clean.dropLast(1).toDoubleOrNull()?.times(1.0) ?: 0.0
            clean.endsWith("m", ignoreCase = true) -> clean.dropLast(1).toDoubleOrNull()?.times(1000.0) ?: 0.0
            else -> clean.toDoubleOrNull()?.div(1000.0) ?: 0.0
        }
    }

    /**
     * Parse usage data from Claude Code /usage command output.
     * Returns map of label→percentage, e.g. {"session" to 5, "week" to 16}
     */
    fun parseUsage(sessionId: String, text: String): Map<String, Int>? {
        val stripped = recentOutput[sessionId]?.toString() ?: stripAnsi(text)
        val result = mutableMapOf<String, Int>()

        // "Current session" → "XX% used"
        SESSION_USAGE_REGEX.find(stripped)?.let {
            it.groupValues[1].toIntOrNull()?.let { pct -> result["session"] = pct }
        }
        // "Current week (all models)" → "XX% used"
        WEEK_USAGE_REGEX.find(stripped)?.let {
            it.groupValues[1].toIntOrNull()?.let { pct -> result["week"] = pct }
        }

        return result.ifEmpty { null }
    }

    companion object {
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
    PERMISSION_PROMPT("Permission requested")
}

data class PromptDetection(val sessionId: String, val type: PromptType)
