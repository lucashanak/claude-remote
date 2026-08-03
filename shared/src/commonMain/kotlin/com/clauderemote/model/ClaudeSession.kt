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
    val history: List<Float> = emptyList(),
    /**
     * Which server-side Claude login this session runs under, as a
     * [ClaudeAccount.slug]. **null (the default) means the default `~/.claude`
     * account**, which must launch with `CLAUDE_CONFIG_DIR` UNSET — see
     * [claudeConfigDirFor]. Any other value selects
     * `~/.claude-remote/accounts/<slug>/` as the session's config dir.
     */
    val accountSlug: String? = null
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

    val durationText: String get() = formatElapsedCompact((System.currentTimeMillis() - connectedAt) / 1000)
}

/**
 * Clamped to >= 0: RemoteSession.durationText derives elapsed time from the
 * SERVER's clock (tmux `session_created`), which can read slightly ahead of
 * the phone's — an un-clamped negative would render a nonsense "-3s" chip.
 */
fun formatElapsedCompact(elapsedSeconds: Long): String {
    val s = elapsedSeconds.coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h${(s % 3600) / 60}m"
        s < 7 * 86400 -> "${s / 86400}d${(s % 86400) / 3600}h"
        else -> "${s / (7 * 86400)}w"
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

val RemoteSession.durationText: String get() {
    val created = tmuxSession.created.toLongOrNull() ?: return ""
    val elapsed = System.currentTimeMillis() / 1000 - created
    return formatElapsedCompact(elapsed)
}

/**
 * Tmux session name convention: claude-{server}-{folder}[-yolo][--{alias}]
 */
object TmuxNameParser {
    /**
     * Apply tmux's own rewrite of session names: `.` and `:` are its target
     * syntax separators (`-t sess:win.pane`), so `new-session -s a.b` silently
     * creates `a_b` and `-t '=a.b'` can then never address it again.
     *
     * Minting a name tmux will rename means the launching tab points at a
     * session that does not exist — every probe, attach and kill for it fails —
     * and the remote scan discovers the renamed session as an unknown one and
     * opens a SECOND tab for it. That is the `nekrachni.plus` /
     * `nekrachni_plus` duplicate. Substitute up front so what the client stores
     * is what tmux will actually hold.
     */
    fun sanitize(tmuxName: String): String = tmuxName.replace('.', '_').replace(':', '_')

    fun build(serverName: String, folder: String, isYolo: Boolean, alias: String = ""): String {
        val folderPart = folder.trimEnd('/').substringAfterLast('/').ifBlank { folder }
        val yolo = if (isYolo) "-yolo" else ""
        val aliasPart = if (alias.isNotBlank()) "--${alias.replace(" ", "-")}" else ""
        val prefix = "claude-${serverName}-"
        val suffix = "${yolo}${aliasPart}"
        // Never truncate inside the -yolo/--alias suffix. A cut alias fragment
        // (e.g. "--mission-critical" clipped to "--mis") is re-parsed as the
        // canonical alias on the next build(), mutating the name so it no longer
        // matches the live tmux session — the client then CREATES a duplicate
        // instead of reattaching. If length-bounding is needed, shorten ONLY the
        // folder segment and keep the suffix intact. tmux tolerates long session
        // names, so a suffix that alone overflows the budget is left uncut rather
        // than corrupted (the old hard 64-cap was arbitrary).
        val budget = 64 - prefix.length - suffix.length
        val boundedFolder = if (budget in 1 until folderPart.length) folderPart.take(budget) else folderPart
        return sanitize("${prefix}${boundedFolder}${suffix}")
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
        // Extract yolo — anchored at the END only. The old `contains("-yolo")`
        // fired on any folder with "-yolo" mid-string yet the strip regex is
        // end-anchored, leaving isYolo=true with an unstripped folder (a
        // round-trip break). Match what build() actually appends: a trailing
        // "-yolo" (with optional legacy dedup digits).
        val isYolo = Regex("-yolo\\d*$").containsMatchIn(remainder)
        remainder = remainder.replace(Regex("-yolo\\d*$"), "")
        val folder = remainder.ifBlank { "~" }
        return Parsed(folder, isYolo, alias)
    }
}
