package com.clauderemote.connection

import com.clauderemote.model.TmuxSession
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TmuxManager {

    /**
     * Check if tmux is installed on the remote server.
     */
    suspend fun isInstalled(session: Session): Boolean = withContext(Dispatchers.IO) {
        try {
            val output = execCommand(session, "which tmux")
            output.isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * List existing tmux sessions on the remote server.
     */
    suspend fun listSessions(session: Session): List<TmuxSession> = withContext(Dispatchers.IO) {
        try {
            val output = execCommand(session, "tmux list-sessions -F '#{session_name}|#{session_windows}|#{session_attached}|#{session_created}' 2>/dev/null")
            if (output.isBlank()) return@withContext emptyList()

            output.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size >= 4) {
                    TmuxSession(
                        name = parts[0],
                        windows = parts[1].toIntOrNull() ?: 0,
                        attached = parts[2] == "1",
                        created = parts[3]
                    )
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Build the tmux attach/create command.
     */
    fun buildAttachCommand(sessionName: String): String {
        // set-clipboard on + terminal-features clipboard → tmux sends the selected
        // text back to the terminal via OSC 52 after a mouse-drag selection, which
        // both the desktop app (custom interceptor) and Termux on Android honor.
        val escapedName = sessionName.replace("'", "'\\''")
        return "tmux new-session -A -s '$escapedName' \\; " +
            "set-option -g mouse on \\; " +
            "set-option -g set-clipboard on \\; " +
            "set-option -g history-limit 100000 \\; " +
            "set-option -ga terminal-features ',*:clipboard'"
    }

    /**
     * Rename a tmux session (blocking, call from IO thread).
     */
    fun renameSession(session: Session, oldName: String, newName: String) {
        // `=` forces an EXACT target match. Plain `-t name` prefix-matches, so
        // renaming `x` while only `x-2` existed renamed the WRONG session — and
        // the app then lost track of both. Measured on tmux 3.5a.
        execCommand(session, "tmux rename-session -t '=${oldName.replace("'", "'\\''")}' '${newName.replace("'", "'\\''")}'")
    }

    /**
     * Kill a tmux session.
     *
     * Records a tombstone FIRST, so no caller can kill a pane without the
     * close being visible to the other devices. The pane's death is what every
     * other device reacts to — its attach drops and auto-reconnect fires within
     * a second — and a peer that finds no tombstone rebuilds the pane with
     * `claude --resume`, which is how a killed session came back a moment later.
     */
    suspend fun killSession(session: Session, sessionName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            try {
                execCommand(
                    session,
                    com.clauderemote.session.service.ManifestCommands.tombstone(sessionName, durable = false),
                )
            } catch (e: Exception) {
                // Best-effort: a missing tombstone only costs us the race guard,
                // it must never block the kill the user asked for.
                com.clauderemote.util.FileLogger.error(
                    "TmuxManager", "tombstone before kill failed for $sessionName: ${e.message}", e
                )
            }
            // `-t '=name'`: plain `-t` prefix-matches, so killing "proj--cashy"
            // would also hit a live "proj--cashy-2" (measured on tmux 3.5a).
            // `=` forces an exact-name match for a target-session.
            execCommand(session, "tmux kill-session -t '=${sessionName.replace("'", "'\\''")}'")
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * List folders in a remote directory.
     */
    suspend fun listFolders(session: Session, path: String = "~"): List<String> = withContext(Dispatchers.IO) {
        try {
            val expandedPath = if (path == "~") "\$HOME" else path
            val output = execCommand(session, "ls -1d $expandedPath/*/ 2>/dev/null | head -50")
            output.lines().filter { it.isNotBlank() }.map { it.trimEnd('/') }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Check if a remote folder exists, create if requested.
     */
    suspend fun ensureFolder(session: Session, path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = execCommand(session, "mkdir -p '${path.replace("'", "'\\''")}' && echo OK")
            result.trim() == "OK"
        } catch (e: Exception) {
            false
        }
    }

    private fun execCommand(session: Session, command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.inputStream = null
        val input = channel.inputStream
        channel.connect(5000)

        val output = input.bufferedReader().readText()
        channel.disconnect()
        return output
    }
}
