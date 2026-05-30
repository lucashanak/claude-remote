package com.clauderemote.model

/**
 * Git status snapshot of a session's working directory, polled periodically
 * off the UI thread. Null (absent from the map) means "not a git repo" — the
 * UI shows no chip in that case.
 */
data class GitStatus(
    val branch: String,
    val dirty: Boolean,
    val ahead: Int = 0,
    val behind: Int = 0
)

data class ClaudeSession(
    val id: String,
    val server: SshServer,
    val folder: String,
    val mode: ClaudeMode,
    val model: ClaudeModel,
    val tmuxSessionName: String,
    val connectionType: ConnectionType,
    val status: SessionStatus = SessionStatus.CONNECTING,
    val connectedAt: Long = System.currentTimeMillis(),
    val alias: String = "",
    /**
     * UUID passed to `claude --session-id <uuid>` at launch. Used to deterministically
     * resume the same conversation via `claude --resume <uuid>` after a server reboot
     * or app restart. Null only for sessions launched before this field existed.
     */
    val claudeSessionId: String? = null,
    /** Per-session cost history samples (normalised 0–1) for sparkline display. */
    val history: List<Float> = emptyList()
) {
    val tabTitle: String get() {
        if (alias.isNotBlank()) return alias
        val name = folder.trimEnd('/').substringAfterLast('/').ifBlank { folder }
        return "${server.name}:$name"
    }

    val displayLabel: String get() {
        if (alias.isNotBlank()) return alias
        return folder.trimEnd('/').substringAfterLast('/').ifBlank { folder }
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

/**
 * Tmux session name convention: claude-{server}-{folder}[-yolo][--{alias}]
 */
object TmuxNameParser {
    fun build(serverName: String, folder: String, isYolo: Boolean, alias: String = ""): String {
        val folderPart = folder.trimEnd('/').substringAfterLast('/').ifBlank { folder }
        val yolo = if (isYolo) "-yolo" else ""
        val aliasPart = if (alias.isNotBlank()) "--${alias.replace(" ", "-")}" else ""
        return "claude-${serverName}-${folderPart}${yolo}${aliasPart}".take(64)
    }

    data class Parsed(val folder: String, val isYolo: Boolean, val alias: String)

    fun parse(tmuxName: String, serverName: String): Parsed {
        val prefix = "claude-${serverName}-"
        var remainder = if (tmuxName.startsWith(prefix)) tmuxName.removePrefix(prefix) else tmuxName
        // Extract alias (after --)
        val alias = if (remainder.contains("--")) {
            val parts = remainder.split("--", limit = 2)
            remainder = parts[0]
            parts[1].replace("-", " ")
        } else ""
        // Extract yolo
        val isYolo = remainder.endsWith("-yolo") || remainder.contains("-yolo")
        remainder = remainder.replace(Regex("-yolo\\d*$"), "")
        val folder = remainder.ifBlank { "~" }
        return Parsed(folder, isYolo, alias)
    }
}
