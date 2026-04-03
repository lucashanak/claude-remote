package com.clauderemote.session

/**
 * Detects when Claude Code CLI is waiting for user input by analyzing terminal output.
 * Handles ANSI escape sequence stripping, debouncing, and per-session state tracking.
 */
class InputPromptDetector {

    private val sessionStates = mutableMapOf<String, SessionState>()

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
    }

    private fun detectPromptType(stripped: String): PromptType? {
        // Check approval patterns first (more specific)
        if (stripped.contains("[Y/n]") || stripped.contains("[y/N]")) {
            return PromptType.APPROVAL_NEEDED
        }
        if (stripped.contains("Do you want to") || stripped.contains("permission")) {
            return PromptType.PERMISSION_PROMPT
        }

        // Check for Claude's input prompt character near the end of output
        val trimmed = stripped.trimEnd()
        if (trimmed.length <= 30) {
            if (trimmed.contains('\u276F')) { // ❯
                return PromptType.INPUT_PROMPT
            }
        } else {
            val tail = trimmed.substring(trimmed.length - 30)
            if (tail.contains('\u276F')) {
                return PromptType.INPUT_PROMPT
            }
        }

        return null
    }

    private class SessionState {
        var waitingForInput = false
        var lastNotificationTime = 0L
        var userHasInteracted = false // Only notify after user has sent at least one input
    }

    companion object {
        private val ANSI_REGEX = Regex("\u001B(?:\\[[0-9;]*[a-zA-Z]|\\][^\u0007]*\u0007|\\[[0-9;]*m)")

        fun stripAnsi(text: String): String = ANSI_REGEX.replace(text, "")

        // Expect/actual not needed — System.currentTimeMillis() works on JVM targets
        private fun currentTimeMillis(): Long = System.currentTimeMillis()
    }
}

enum class PromptType(val displayHint: String) {
    INPUT_PROMPT("Claude is ready for input"),
    APPROVAL_NEEDED("Approval needed"),
    PERMISSION_PROMPT("Permission requested")
}

data class PromptDetection(val sessionId: String, val type: PromptType)
