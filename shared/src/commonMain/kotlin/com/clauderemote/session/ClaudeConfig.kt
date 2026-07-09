package com.clauderemote.session

import com.clauderemote.model.ClaudeEffort
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel

/**
 * Claude Code CLI command builder and runtime control sequences.
 *
 * Runtime mode switching:
 * - Shift+Tab (\x1b[Z) toggles between normal/plan/auto-accept modes
 * - /model <alias> switches model immediately; /model alone opens interactive picker
 * - /plan [desc] enters plan mode
 * - /clear clears context
 * - /compact compacts context
 * - /rewind undoes changes
 * - /config opens settings
 *
 * Permission handling:
 * - Non-YOLO launches pass --allow-dangerously-skip-permissions, which keeps
 *   the permission gate at startup but lets the user opt into YOLO from the
 *   running session (so mode can be flipped without reconnecting)
 * - YOLO launches pass --dangerously-skip-permissions directly (starts in YOLO)
 */
object ClaudeConfig {

    fun buildLaunchCommand(
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel,
        claudeSessionId: String? = null,
        resume: Boolean = false
    ): String = "cd ${shellEscape(folder)} && ${claudeInvocation(mode, model, claudeSessionId, resume)}"

    /**
     * Just the `claude …` invocation (no `cd`), space-joined. All tokens are
     * plain (flags, model alias, uuid) — no shell metacharacters — so this is
     * safe to single-quote-wrap for `send-keys`/`bash -lc` without escaping.
     */
    private fun claudeInvocation(
        mode: ClaudeMode,
        model: ClaudeModel,
        claudeSessionId: String?,
        resume: Boolean,
    ): String {
        // Local models launch the `claude-local` wrapper (which sets the
        // gateway env server-side); everything else launches plain `claude`.
        val claudeArgs = mutableListOf(if (model.isLocal) "claude-local" else "claude")

        if (model.cliValue != null) {
            claudeArgs.add("--model")
            claudeArgs.add(model.cliValue)
        }

        when (mode) {
            ClaudeMode.AUTO_ACCEPT -> { claudeArgs.add("--permission-mode"); claudeArgs.add("acceptEdits") }
            ClaudeMode.AUTO -> { claudeArgs.add("--permission-mode"); claudeArgs.add("auto") }
            ClaudeMode.PLAN, ClaudeMode.NORMAL -> {}
            ClaudeMode.YOLO -> {}
        }

        claudeArgs.add(
            if (mode == ClaudeMode.YOLO) "--dangerously-skip-permissions"
            else "--allow-dangerously-skip-permissions"
        )

        // Session-id flag: --resume reuses an existing conversation UUID,
        // --session-id forces a NEW session to use a deterministic UUID.
        // These are mutually exclusive — resume wins when both are set.
        if (claudeSessionId != null) {
            if (resume) {
                claudeArgs.add("--resume")
                claudeArgs.add(claudeSessionId)
            } else {
                claudeArgs.add("--session-id")
                claudeArgs.add(claudeSessionId)
            }
        }

        return claudeArgs.joinToString(" ")
    }

    fun buildTmuxLaunchCommand(
        tmuxSessionName: String,
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel,
        claudeSessionId: String? = null,
        resume: Boolean = false
    ): String {
        val claudeCmd = buildLaunchCommand(folder, mode, model, claudeSessionId, resume)
        // POSIX single-quote wrap: a literal ' inside a '...' string must be
        // written as '\'' — NOT \' (you can't backslash-escape a quote inside
        // single quotes). The old `\'` produced an unterminated quote because
        // claudeCmd already contains the single-quoted `cd '<folder>'`, so the
        // launch hung at the bash continuation prompt and Claude never started.
        fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"
        // Kill existing session with same name to avoid -A reattaching
        // and sending keystrokes into a running program
        return "tmux kill-session -t ${sq(tmuxSessionName)} 2>/dev/null; " +
                "tmux set-option -g history-limit 100000 2>/dev/null; " +
                "tmux new-session -s ${sq(tmuxSessionName)} " +
                "\\; set-option -g mouse on " +
                "\\; set-option -g history-limit 100000 " +
                "\\; send-keys ${sq(claudeCmd)} Enter"
    }

    /**
     * Restart the Claude Code process IN PLACE while keeping the conversation.
     *
     * `tmux respawn-pane -k` kills whatever is running in the pane (the current
     * `claude`, whether idle or mid-task) and restarts the pane's shell — WITHOUT
     * killing the tmux session, so any attached client (the app's terminal) stays
     * attached and simply sees the pane redraw. We then `send-keys` a fresh
     * `claude --resume <uuid>` into that shell, which reloads the SAME conversation
     * from its transcript (picking up e.g. a newly-installed Claude Code version).
     *
     * This is deliberately NOT `kill-session; new-session` (buildTmuxLaunchCommand):
     * that would detach the client and break the live terminal.
     */
    fun buildRestartCommand(
        tmuxSessionName: String,
        mode: ClaudeMode,
        model: ClaudeModel,
        claudeSessionId: String,
    ): String {
        // Run claude via a LOGIN shell (bash -lc): the shell that respawn-pane
        // spawns does NOT have ~/.local/bin (where claude lives) on PATH, so a
        // bare `claude` was "command not found". A login shell sources ~/.profile
        // which adds it. NO `cd`: respawn-pane keeps the pane's cwd (the project
        // dir where claude was already running), so a relative `cd <folder>`
        // (folder is relative-to-$HOME) would fail from inside that dir — which
        // was the second half of the bug. claudeInvocation has no quotes, so the
        // nesting is a single, clean level of '\'' escaping for send-keys.
        val claudeCmd = claudeInvocation(mode, model, claudeSessionId, resume = true)
        fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val loginRun = "bash -lc ${sq(claudeCmd)}"
        return "tmux respawn-pane -k -t ${sq(tmuxSessionName)} 2>/dev/null; " +
                "sleep 0.4; " +
                "tmux send-keys -t ${sq(tmuxSessionName)} ${sq(loginRun)} Enter"
    }

    // ======================== RUNTIME CONTROLS ========================

    /** Shift+Tab — toggles between modes (normal → plan → auto-accept) */
    const val SHIFT_TAB = "\u001B[Z"

    /** Alt+M — toggle mode (Linux) */
    const val ALT_M = "\u001Bm"

    /** Escape key */
    const val ESCAPE = "\u001B"

    /** /model — opens interactive model picker */
    const val CMD_MODEL = "/model\n"

    /** /plan — enter plan mode */
    const val CMD_PLAN = "/plan\n"

    /** /clear — clear context */
    const val CMD_CLEAR = "/clear\n"

    /** /compact — compact context */
    const val CMD_COMPACT = "/compact\n"

    /** /rewind — undo changes */
    const val CMD_REWIND = "/rewind\n"

    /** /config — open settings */
    const val CMD_CONFIG = "/config\n"

    /** /help — show help */
    const val CMD_HELP = "/help\n"

    /** Ctrl+C */
    const val CTRL_C = "\u0003"

    /** Tab */
    const val TAB = "\t"

    /** Arrow keys */
    const val ARROW_UP = "\u001B[A"
    const val ARROW_DOWN = "\u001B[B"
    const val ARROW_RIGHT = "\u001B[C"
    const val ARROW_LEFT = "\u001B[D"

    /** Enter */
    const val ENTER = "\r"

    // Model switch: `/model <alias>` selects immediately, no interactive picker.
    // No trailing newline — the caller (sendSlashCommand) types this char-by-
    // char and sends \r itself; a whole "text+\n" burst gets pasted as
    // literal text by Claude's TUI instead of executed.
    fun modelSwitchCommand(model: ClaudeModel): String = "/model ${model.cliValue ?: "default"}"

    // Effort switch: `/effort <level>` selects immediately, no interactive picker.
    fun effortSwitchCommand(effort: ClaudeEffort): String = "/effort ${effort.cliValue}"

    fun escapeSequence(): String = ESCAPE

    private fun shellEscape(path: String): String {
        // POSIX-safe single-quoting that also closes the injection vector when
        // `path` is untrusted (e.g. a cwd read from a .jsonl transcript on the
        // server). The old conditional quote left embedded `'` unescaped, so a
        // crafted cwd could break out of the `cd ...` command. Single-quote and
        // escape embedded quotes via the standard `'\''` idiom, but keep a
        // leading `~` / `~/` UNquoted so tilde expansion still works in the
        // pane's shell. Avoids bash-only `${x/#~/$HOME}` so POSIX sh works too.
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        return when {
            path == "~" -> "~"
            path.startsWith("~/") -> "~/" + q(path.substring(2))
            else -> q(path)
        }
    }
}
