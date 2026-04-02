package com.clauderemote.session

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel

/**
 * Claude Code CLI command builder and runtime control sequences.
 *
 * Runtime mode switching:
 * - Shift+Tab (\x1b[Z) toggles between normal/plan/auto-accept modes
 * - /model opens interactive model picker
 * - /plan [desc] enters plan mode
 * - /clear clears context
 * - /compact compacts context
 * - /rewind undoes changes
 * - /config opens settings
 * - YOLO mode (--dangerously-skip-permissions) cannot be toggled at runtime
 */
object ClaudeConfig {

    fun buildLaunchCommand(
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel
    ): String {
        val parts = mutableListOf<String>()
        parts.add("cd ${shellEscape(folder)}")

        val claudeArgs = mutableListOf("claude")

        if (model.cliValue != null) {
            claudeArgs.add("--model")
            claudeArgs.add(model.cliValue)
        }

        when (mode) {
            ClaudeMode.AUTO_ACCEPT -> claudeArgs.add("--auto-accept")
            ClaudeMode.YOLO -> claudeArgs.add("--dangerously-skip-permissions")
            ClaudeMode.PLAN, ClaudeMode.NORMAL -> {}
        }

        parts.add(claudeArgs.joinToString(" "))
        return parts.joinToString(" && ")
    }

    fun buildTmuxLaunchCommand(
        tmuxSessionName: String,
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel
    ): String {
        val claudeCmd = buildLaunchCommand(folder, mode, model)
        return "tmux new-session -A -s '${tmuxSessionName.replace("'", "\\'")}' " +
                "\\; set-option -g mouse on " +
                "\\; send-keys '${claudeCmd.replace("'", "\\'")}' Enter"
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

    // Model switch: /model opens picker, then type number or name
    fun modelSwitchCommand(model: ClaudeModel): String = "/model\n"

    fun escapeSequence(): String = ESCAPE

    private fun shellEscape(path: String): String {
        return if (path.contains(' ') || path.contains('$') || path.contains('(')) {
            "'$path'"
        } else {
            path
        }
    }
}
