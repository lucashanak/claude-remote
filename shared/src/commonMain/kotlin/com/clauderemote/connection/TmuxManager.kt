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
        return "tmux new-session -A -s '${sessionName.replace("'", "\\'")}' \\; set-option -g mouse on"
    }

    /**
     * Kill a tmux session.
     */
    suspend fun killSession(session: Session, sessionName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            execCommand(session, "tmux kill-session -t '${sessionName.replace("'", "\\'")}'")
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
            val result = execCommand(session, "mkdir -p ${path.replace("'", "\\'")} && echo OK")
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
