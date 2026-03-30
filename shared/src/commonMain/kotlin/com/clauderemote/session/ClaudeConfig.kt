package com.clauderemote.session

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel

object ClaudeConfig {

    /**
     * Build the shell command to launch claude in a given folder with options.
     * Returns something like: cd ~/project && claude --model opus --auto-accept
     */
    fun buildLaunchCommand(
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel
    ): String {
        val parts = mutableListOf<String>()
        parts.add("cd ${shellEscape(folder)}")

        val claudeArgs = mutableListOf("claude")

        // Model
        claudeArgs.add("--model")
        claudeArgs.add(model.cliValue)

        // Mode flag
        when (mode) {
            ClaudeMode.AUTO_ACCEPT -> claudeArgs.add("--auto-accept")
            ClaudeMode.YOLO -> claudeArgs.add("--dangerously-skip-permissions")
            ClaudeMode.PLAN, ClaudeMode.NORMAL -> { /* no flag, plan is set at runtime */ }
        }

        parts.add(claudeArgs.joinToString(" "))
        return parts.joinToString(" && ")
    }

    /**
     * Build a tmux command that creates or attaches to a session,
     * then runs the claude launch command inside it.
     */
    fun buildTmuxLaunchCommand(
        tmuxSessionName: String,
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel
    ): String {
        val claudeCmd = buildLaunchCommand(folder, mode, model)
        // tmux new-session -A: attach if exists, create if not
        // Send the claude command only on new session creation
        return "tmux new-session -A -s '${tmuxSessionName.replace("'", "\\'")}' " +
                "\\; set-option -g mouse on " +
                "\\; send-keys '${claudeCmd.replace("'", "\\'")}' Enter"
    }

    /**
     * Command to send to terminal to switch claude model at runtime.
     */
    fun modelSwitchCommand(model: ClaudeModel): String = "/model ${model.cliValue}\n"

    /**
     * Command to enter plan mode.
     */
    fun planModeCommand(): String = "/plan\n"

    /**
     * Command to clear context.
     */
    fun clearCommand(): String = "/clear\n"

    /**
     * Escape key sequence.
     */
    fun escapeSequence(): String = "\u001B"

    private fun shellEscape(path: String): String {
        // Simple escape: wrap in single quotes if needed
        return if (path.contains(' ') || path.contains('$') || path.contains('(')) {
            "'$path'"
        } else {
            path
        }
    }
}
