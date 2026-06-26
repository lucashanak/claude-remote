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

    /**
     * Sessions whose idle detection is handled by the Claude Code `Stop` hook
     * watcher. The hook fires a shell command the moment Claude finishes a
     * turn — faster and more reliable than polling the rendered terminal — so
     * for these sessions the screen-state check runs in APPROVAL-ONLY mode:
     * WORKING/IDLE classifications are dropped (the hook owns them) and only
     * an on-screen selector (APPROVAL) is acted on, because a mid-turn
     * AskUserQuestion / permission prompt never triggers the Stop hook.
     */
    private val hookActiveSessions = mutableSetOf<String>()

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

    /** Mark a session as using the Claude Code `Stop` hook for idle detection. */
    fun markHookActive(sessionId: String) { hookActiveSessions.add(sessionId) }

    /** Remove hook-active flag (session disconnected or hook watcher failed). */
    fun markHookInactive(sessionId: String) { hookActiveSessions.remove(sessionId) }

    /**
     * Feed a chunk of terminal output. Always updates the recent-output buffer
     * (for context/usage parsing) and, unless the session uses hook-based
     * detection or is suppressed, resets the per-session quiescence timer.
     * Returns immediately; detection is async.
     */
    fun onOutput(sessionId: String, text: String) {
        feedRecentOutput(sessionId, text)

        // Hook-active sessions still run the quiescence screen check, but in
        // APPROVAL-ONLY mode (see runIdleCheck). The Stop hook is authoritative
        // for end-of-turn (idle), but it CANNOT see a pending AskUserQuestion /
        // permission selector — those block MID-turn, the hook never fires, and
        // the old unconditional skip here meant APPROVAL_NEEDED was never
        // emitted for hook-active sessions: the chat stayed "Claude is
        // working…" while Claude sat waiting for an answer.
        if (currentTimeMillis() < suppressUntil) return

        val state = sessionStates.getOrPut(sessionId) { SessionState() }
        state.recheckCount = 0 // fresh output → fresh retry budget
        state.pendingCheck?.cancel()
        state.pendingCheck = scope.launch {
            delay(QUIESCENCE_MS)
            runIdleCheck(sessionId)
        }
    }

    private suspend fun runIdleCheck(sessionId: String) {
        val state = sessionStates[sessionId] ?: return
        // The quiescence check is OUTPUT-driven: while a question/selector sits
        // on screen there is NO further output, so a check eaten by the
        // suppress window, a transiently-null screenReader, or an UNKNOWN
        // classification used to be a PERMANENT miss ("I only find out Claude
        // asked something when I get suspicious and switch to Raw").
        // scheduleRecheck re-arms a bounded retry so a single bad sample
        // self-heals; an APPROVAL result also re-checks (unbounded, 3s cadence)
        // so the activity stays asserted against statusline WORKING clobbers
        // for as long as the dialog is actually on screen.
        if (currentTimeMillis() < suppressUntil) {
            scheduleRecheck(state, sessionId)
            return
        }
        val snapshot = screenReader?.invoke(sessionId)
        if (snapshot == null) {
            scheduleRecheck(state, sessionId)
            return
        }
        val classified = ScreenStateClassifier.classify(snapshot)
        when (classified) {
            ClaudeState.UNKNOWN -> scheduleRecheck(state, sessionId)
            ClaudeState.APPROVAL -> {
                state.recheckCount = 0
                scheduleRecheck(state, sessionId)
            }
            else -> {}
        }

        // Claude is visibly WORKING again → a new turn started. Re-arm the
        // notify latch so the NEXT idle/approval fires even when the user
        // never typed in this app (dismissed the notification, answered from
        // another client, …). Without this the latch stuck forever and the
        // session notified exactly once ("fires once, then never again").
        if (classified == ClaudeState.WORKING) state.waitingForInput = false

        // Approval-only mode for hook-active sessions: the hook + statusline own
        // WORKING/IDLE (screen-based idle is the flappy signal the hook replaced),
        // so the screen check contributes ONLY the one state they can't see — a
        // selector awaiting the user. APPROVAL never gets stuck from here: a
        // working statusline overrides it (orchestrator FIX 3 allows WORKING
        // over APPROVAL) and the Stop hook forces WAITING at end of turn.
        if (sessionId in hookActiveSessions && classified != ClaudeState.APPROVAL) return

        onStateChange?.invoke(sessionId, classified)

        // Screen-state IDLE no longer raises a "ready for input" notification.
        // The rendered screen routinely LOOKS idle mid-turn — between a message
        // and the next tool call the input box is visible and the dark-red
        // working indicator hasn't repainted — which spammed false "Claude is
        // ready" alerts WHILE Claude was still working. The Claude Code Stop
        // hook (startNotifyWatcher) is now the SOLE authority for end-of-turn
        // "ready": it fires only when the turn genuinely ends. Screen-state
        // still drives the activity dot (onStateChange above) and still raises
        // APPROVAL — a selector genuinely needs the user, and the Stop hook
        // can't see a mid-turn AskUserQuestion / permission prompt.
        val promptType = when (classified) {
            ClaudeState.APPROVAL -> PromptType.APPROVAL_NEEDED
            else -> return
        }
        // Don't notify on startup prompt — only after user has interacted at least once.
        if (!state.userHasInteracted) return
        // Already notified; wait for user input to reset.
        if (state.waitingForInput) return
        // Cooldown against flapping.
        val now = currentTimeMillis()
        if (now - state.lastDetectionTime < COOLDOWN_MS) return

        state.waitingForInput = true
        state.lastDetectionTime = now
        onDetection?.invoke(PromptDetection(sessionId, promptType))
    }

    /**
     * Re-arm a delayed screen re-check. Bounded by [MAX_RECHECKS] consecutive
     * tries (reset whenever fresh output arrives or APPROVAL is confirmed) so a
     * disconnected/scrolled session doesn't poll the snapshot forever.
     */
    private fun scheduleRecheck(state: SessionState, sessionId: String) {
        if (state.recheckCount >= MAX_RECHECKS) return
        state.recheckCount++
        state.pendingCheck = scope.launch {
            delay(RECHECK_MS)
            runIdleCheck(sessionId)
        }
    }

    /** Reset the latch so the next idle transition will fire a detection. */
    fun onUserInput(sessionId: String) {
        val state = sessionStates.getOrPut(sessionId) { SessionState() }
        state.waitingForInput = false
        state.userHasInteracted = true
    }

    /**
     * The statusline says Claude is actively working — re-arm the notify
     * latch (same rationale as the WORKING screen classification, but this
     * source flows through terminal output for EVERY session, including
     * background tabs whose screenReader returns null).
     */
    fun onClaudeWorking(sessionId: String) {
        sessionStates[sessionId]?.waitingForInput = false
    }

    /**
     * Treat the session as already-interacted. Called on session RESTORE /
     * reconnect: Claude may already be sitting idle waiting for input, and
     * the `userHasInteracted` startup guard (meant to silence the shell's
     * own prompt on a brand-new session) would otherwise swallow the first —
     * and only — notification until the user happened to type something.
     */
    fun markInteracted(sessionId: String) {
        sessionStates.getOrPut(sessionId) { SessionState() }.userHasInteracted = true
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
            return (match.groupValues.getOrNull(1)?.toIntOrNull()
                ?: match.groupValues.getOrNull(2)?.toIntOrNull()
                ?: match.groupValues.getOrNull(3)?.toIntOrNull())
                ?.coerceIn(0, 100)
        }
        TOKENS_REMAINING_REGEX.find(stripped)?.let { match ->
            val remaining = parseTokenCount(match.groupValues[1])
            // Infer context window size from remaining tokens
            val total = when {
                remaining <= 200_000 -> 200_000.0
                remaining <= 1_000_000 -> 1_000_000.0
                else -> remaining * 1.5 // unknown window, estimate conservatively
            }
            if (remaining in 0.0..total) {
                return ((1.0 - remaining / total) * 100).toInt().coerceIn(0, 100)
            }
        }
        return null
    }

    fun parseUsage(sessionId: String, text: String): Map<String, Int>? {
        val stripped = recentOutput[sessionId]?.toString() ?: stripAnsi(text)
        val result = mutableMapOf<String, Int>()
        SESSION_USAGE_REGEX.find(stripped)?.let { m ->
            // Either capture group 1 (legacy '/usage' output) or 2 (OMC '5h:NN%').
            (m.groupValues.getOrNull(1)?.toIntOrNull()
                ?: m.groupValues.getOrNull(2)?.toIntOrNull())
                ?.let { pct -> result["session"] = pct }
        }
        WEEK_USAGE_REGEX.find(stripped)?.let { m ->
            (m.groupValues.getOrNull(1)?.toIntOrNull()
                ?: m.groupValues.getOrNull(2)?.toIntOrNull())
                ?.let { pct -> result["week"] = pct }
        }
        // Reset times in total minutes — only captured when paired with the
        // OMC short-form, since /usage doesn't print them in this layout.
        SESSION_RESET_REGEX.find(stripped)?.let { m ->
            resetToMinutes(m)?.let { result["session_reset_min"] = it }
        }
        WEEK_RESET_REGEX.find(stripped)?.let { m ->
            resetToMinutes(m)?.let { result["week_reset_min"] = it }
        }
        return result.ifEmpty { null }
    }

    /** Total minutes from a reset match with optional (d, h, m) groups, or null
     *  if the parenthesised duration was empty. */
    private fun resetToMinutes(m: MatchResult): Int? {
        val d = m.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
        val h = m.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        val mins = m.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
        val total = d * 1440 + h * 60 + mins
        return if (total > 0) total else null
    }

    /**
     * Working/idle from the OMC statusline, which flows through terminal output
     * in EVERY view (unlike the Stop hook, which can silently fail). The
     * rendered-screen classifier now also works in Chat for the active
     * single-pane session on Android (#75: emulator is kept composed under the
     * Chat overlay), but the statusline remains the ground truth for all other
     * cases (background tabs, desktop, grid panes).
     *
     * The statusline carries a state segment between the weekly-usage block and
     * `session:` only while Claude is active:
     *   working: `... wk:14%(10h52m) | thinking | session:6464m | ctx:42% ...`
     *   idle:    `... wk:14%(6d10h) | session:6m | ctx:6% ...`
     * Any non-empty segment there (thinking / a skill / compacting / …) means
     * working; going straight to `session:` means idle. Returns null when no
     * statusline is in the recent buffer (can't tell — leave activity as is).
     */
    fun parseClaudeWorking(sessionId: String): Boolean? {
        val s = recentOutput[sessionId]?.toString() ?: return null
        // recentOutput is a rolling buffer holding MANY concatenated statusline
        // renders. Take the LAST (current/bottom) one, not the first — otherwise
        // on a working→idle transition an older "thinking" render still in the
        // window would re-assert WORKING after the session went idle.
        val m = OMC_STATE_REGEX.findAll(s).lastOrNull() ?: return null
        return m.groupValues[1].replace("|", " ").trim().isNotEmpty()
    }

    private fun parseTokenCount(s: String): Double {
        val clean = s.replace(",", "").trim()
        return when {
            clean.endsWith("k", ignoreCase = true) -> clean.dropLast(1).toDoubleOrNull()?.times(1_000.0) ?: 0.0
            clean.endsWith("m", ignoreCase = true) -> clean.dropLast(1).toDoubleOrNull()?.times(1_000_000.0) ?: 0.0
            else -> clean.toDoubleOrNull() ?: 0.0
        }
    }

    private class SessionState {
        var waitingForInput = false
        var lastDetectionTime = 0L
        var userHasInteracted = false
        var pendingCheck: Job? = null
        /** Consecutive scheduled re-checks since the last fresh output/APPROVAL. */
        var recheckCount = 0
    }

    companion object {
        /** How long to wait after the last output chunk before running an idle check. */
        private const val QUIESCENCE_MS = 1000L
        /** Minimum time between two detections on the same session. */
        private const val COOLDOWN_MS = 3000L
        /** Delay between screen re-checks when a check was eaten / confirmed APPROVAL. */
        private const val RECHECK_MS = 3000L
        /** Max consecutive re-checks without fresh output (≈15s of self-healing). */
        private const val MAX_RECHECKS = 5

        private val ANSI_REGEX = Regex("\u001B(?:\\[\\??[0-9;]*[a-zA-Z]|\\][^\u0007]*\u0007)")
        private val CONTEXT_RATIO_REGEX = Regex("([\\d,.]+[km]?)\\s*/\\s*([\\d,.]+[km]?)\\s*tokens", RegexOption.IGNORE_CASE)
        // Match Claude Code /usage output ("33% context", "context: 33%")
        // OR the OMC statusline at the bottom of every turn ("ctx:20%").
        private val CONTEXT_PERCENT_REGEX = Regex(
            "(\\d{1,3})%\\s*context|context[:\\s]+(\\d{1,3})%|ctx[:\\s]+(\\d{1,3})%",
            RegexOption.IGNORE_CASE
        )
        private val TOKENS_REMAINING_REGEX = Regex("([\\d,.]+[km]?)\\s*tokens?\\s*remaining", RegexOption.IGNORE_CASE)
        // Match both `/usage` command output ("Current session ... 33% used")
        // and the OMC statusline ("5h:33%(2h59m)"). OMC is what actually paints
        // on every turn — without this fallback the chip stays at '—' forever
        // unless the user explicitly runs /usage.
        private val SESSION_USAGE_REGEX = Regex(
            "Current session[\\s\\S]{0,50}?(\\d{1,3})%\\s*used|5h[:\\s]+(\\d{1,3})%",
            RegexOption.IGNORE_CASE
        )
        private val WEEK_USAGE_REGEX = Regex(
            "Current week[\\s\\S]{0,60}?(\\d{1,3})%\\s*used|wk[:\\s]+(\\d{1,3})%",
            RegexOption.IGNORE_CASE
        )
        // OMC also prints time-to-reset right after the percentage as
        // `(XhYm)` — e.g. `5h:33%(2h59m)`, `wk:73%(15h9m)`. We anchor each
        // reset capture to its own prefix so 5h's window doesn't get
        // confused with wk's.
        // Reset is rendered as (XdYhZm) with any field optional and absent
        // fields dropped — e.g. session "(2h27m)", week "(5d10h)" or "(45m)".
        // The old pattern required a trailing `m`, so "5d10h" never matched and
        // the week-reset chip stayed blank.
        private val SESSION_RESET_REGEX = Regex(
            "5h[:\\s]+\\d{1,3}%\\s*\\((?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?\\)",
            RegexOption.IGNORE_CASE
        )
        private val WEEK_RESET_REGEX = Regex(
            "wk[:\\s]+\\d{1,3}%\\s*\\((?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?\\)",
            RegexOption.IGNORE_CASE
        )
        // Captures the OMC statusline segment between the weekly-usage block and
        // `session:`. Non-empty → Claude is active. Single-line (no
        // DOT_MATCHES_ALL) so a wrapped statusline simply yields no match.
        private val OMC_STATE_REGEX = Regex(
            "wk:\\d{1,3}%\\([^)]*\\)\\s*\\|(.*?)session:",
            RegexOption.IGNORE_CASE
        )

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
