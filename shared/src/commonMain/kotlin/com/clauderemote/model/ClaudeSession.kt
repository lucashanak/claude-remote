package com.clauderemote.model

data class ClaudeSession(
    val id: String,
    val server: SshServer,
    val folder: String,
    val mode: ClaudeMode,
    val model: ClaudeModel,
    val tmuxSessionName: String,
    val connectionType: ConnectionType,
    val status: SessionStatus = SessionStatus.CONNECTING,
    val connectedAt: Long = System.currentTimeMillis()
) {
    val tabTitle: String get() {
        val name = folder.trimEnd('/').substringAfterLast('/').ifBlank { folder }
        return "${server.name}:$name"
    }

    val durationText: String get() {
        val elapsed = (System.currentTimeMillis() - connectedAt) / 1000
        return when {
            elapsed < 60 -> "${elapsed}s"
            elapsed < 3600 -> "${elapsed / 60}m"
            else -> "${elapsed / 3600}h${(elapsed % 3600) / 60}m"
        }
    }
}

data class TmuxSession(
    val name: String,
    val windows: Int,
    val attached: Boolean,
    val created: String
)

/**
 * A tmux session discovered on a remote server (not yet connected in-app).
 */
data class RemoteSession(
    val server: SshServer,
    val tmuxSession: TmuxSession
)
