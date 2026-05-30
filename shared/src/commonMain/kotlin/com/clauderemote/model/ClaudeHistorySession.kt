package com.clauderemote.model

/**
 * A past Claude Code conversation discovered on a remote server from its
 * on-disk transcript at `~/.claude/projects/<encoded-cwd>/<uuid>.jsonl`.
 *
 * Discovered independently of tmux — includes "orphaned" sessions whose
 * pane no longer exists. Resumed via `claude --resume <uuid>` (see
 * SessionOrchestrator.launchSession's resumeClaudeSessionId param).
 */
data class ClaudeHistorySession(
    val server: SshServer,
    /** Conversation UUID — the transcript's basename without `.jsonl`. */
    val uuid: String,
    /** Real working directory, read from the transcript's `cwd` field. */
    val cwd: String,
    /** Transcript file mtime (epoch seconds). */
    val lastModifiedEpoch: Long,
    /** Short last-message preview, newlines stripped, truncated (~80 chars). */
    val preview: String,
) {
    /** Last path segment of [cwd], like other models' display helpers. */
    val displayFolder: String
        get() = cwd.trimEnd('/').substringAfterLast('/').ifBlank { cwd }
}
