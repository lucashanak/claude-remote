package com.clauderemote.session

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.ClaudeHistorySession
import com.clauderemote.model.SshServer
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Discovers past Claude Code conversations on a server by scanning the
 * on-disk transcripts under `~/.claude/projects/<encoded-cwd>/<uuid>.jsonl`.
 *
 * Unlike tmux discovery (TmuxManager), this finds "orphaned" sessions whose
 * pane no longer exists — they are still resumable via `claude --resume <uuid>`.
 *
 * The `<encoded-cwd>` directory name is a LOSSY slash→dash encoding, so we do
 * NOT decode it; instead we read the real `cwd` out of the JSONL itself.
 */
object ClaudeHistoryScanner {

    /** Cap: only the most-recently-modified N transcripts are scanned. */
    private const val MAX_TRANSCRIPTS = 50

    /**
     * Strict UUID regex — `[0-9a-fA-F-]{36}`. Rejects any uuid value that
     * could be used for shell injection. Files whose basename doesn't match are
     * silently skipped.
     */
    private val UUID_REGEX = Regex("^[0-9a-fA-F\\-]{36}$")

    /**
     * Result of a scan: the capped list plus the server's total transcript count
     * (before the cap) so the UI can show "Showing N of M".
     */
    data class ScanResult(
        val sessions: List<ClaudeHistorySession>,
        /** Total number of transcripts found before the [MAX_TRANSCRIPTS] cap. */
        val totalCount: Int,
    )

    /**
     * Scan [server] for recent Claude transcripts. Returns sessions newest-first.
     * Unreachable servers / scan failures yield an empty [ScanResult] (never throws).
     */
    suspend fun scan(server: SshServer): ScanResult = withContext(Dispatchers.IO) {
        try {
            val raw = withTimeoutOrNull(10_000L) {
                SshSessionHelper.withSession(server, timeout = 8000) { sess ->
                    exec(sess, buildCommand())
                }
            } ?: return@withContext ScanResult(emptyList(), 0)
            parse(server, raw)
        } catch (_: Exception) {
            ScanResult(emptyList(), 0)
        }
    }

    private fun exec(session: Session, command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.inputStream = null
        val input = channel.inputStream
        channel.connect(8000)
        val output = input.bufferedReader().readText()
        channel.disconnect()
        return output
    }

    /**
     * Remote discovery command. Bounded to the [MAX_TRANSCRIPTS] most recent
     * transcripts by mtime. Output format:
     *
     *   TOTAL:<n>            ← total transcript count before cap
     *   <uuid>\t<mtime>\t<cwd>\t<preview>   ← one line per file
     *
     * Performance: cwd is read cheaply via `grep -m1` on the first line
     * (Claude always writes cwd on the first entry). Preview uses `tail -n 40`
     * to avoid slurping large transcripts, then a single combined `jq` pass
     * for both fields. Files whose basename is not a valid UUID are skipped
     * (defence-in-depth against unexpected directory contents).
     *
     * Shell escaping: all user-influenceable paths that reach a shell are
     * wrapped with single-quote escaping (the `'path'` → `'\''` idiom used
     * throughout the codebase).
     */
    private fun buildCommand(): String = buildString {
        append("D=\"\$HOME/.claude/projects\"; ")
        append("[ -d \"\$D\" ] || exit 0; ")

        // Emit total count before applying the cap so the UI can show
        // "Showing 50 of N" when older transcripts are hidden.
        append("ALL=\$(find \"\$D\" -type f -name '*.jsonl' 2>/dev/null); ")
        append("TOTAL=\$(printf '%s\\n' \$ALL | grep -c . 2>/dev/null || echo 0); ")
        append("printf 'TOTAL:%s\\n' \"\$TOTAL\"; ")

        // Sort by mtime descending, take cap. GNU find -printf first; stat fallback.
        append("FILES=\$(find \"\$D\" -type f -name '*.jsonl' -printf '%T@\\t%p\\n' 2>/dev/null ")
        append("| sort -rn | head -n $MAX_TRANSCRIPTS | cut -f2-); ")
        append("if [ -z \"\$FILES\" ]; then ")
        append("FILES=\$(find \"\$D\" -type f -name '*.jsonl' 2>/dev/null ")
        append("| while read -r f; do ")
        append("echo \"\$(stat -c '%Y' \"\$f\" 2>/dev/null || stat -f '%m' \"\$f\" 2>/dev/null || echo 0)\\t\$f\"; ")
        append("done | sort -rn | head -n $MAX_TRANSCRIPTS | cut -f2-); ")
        append("fi; ")

        append("IFS=\$(printf '\\n'); ")
        append("for f in \$FILES; do ")
        append("[ -f \"\$f\" ] || continue; ")
        append("U=\$(basename \"\$f\" .jsonl); ")
        // Skip files whose name is not a valid UUID — guards against injection
        // via attacker-controlled filenames reaching later shell expansions.
        append("echo \"\$U\" | grep -qE '^[0-9a-fA-F-]{36}\$' || continue; ")
        append("MT=\$(stat -c '%Y' \"\$f\" 2>/dev/null || stat -f '%m' \"\$f\" 2>/dev/null || echo 0); ")
        // cwd: read from first line of file only (Claude writes cwd on every
        // entry but the first is sufficient and avoids a full-file read).
        // Single-quote-escape the path before passing to sh — jq receives
        // the literal filename via a hard-quoted shell word.
        append("CWD=\$(head -n1 \"\$f\" 2>/dev/null | jq -r '.cwd // \"\"' 2>/dev/null); ")
        // preview: tail to avoid reading multi-MB files. One combined jq pass
        // extracts the last non-empty user/assistant text. Content can be a
        // plain string or an array of {type,text} blocks.
        append("PV=\$(tail -n 40 \"\$f\" 2>/dev/null | jq -rs '")
        append("[.[] | (.message.content) | select(.!=null) ")
        append("| if type==\"string\" then . ")
        append("else ([.[]? | .text? // \"\"] | join(\" \")) end ")
        append("| select(.!=\"\")] | last // \"\"' 2>/dev/null ")
        append("| tr '\\n\\t\\r' '   ' | cut -c1-80); ")
        append("printf '%s\\t%s\\t%s\\t%s\\n' \"\$U\" \"\$MT\" \"\$CWD\" \"\$PV\"; ")
        append("done")
    }

    private fun parse(server: SshServer, raw: String): ScanResult {
        var totalCount = 0
        val sessions = mutableListOf<ClaudeHistorySession>()
        // Deduplicate by (server.id, uuid, cwd) keeping highest mtime.
        val seen = HashMap<Triple<String, String, String>, Long>()

        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            if (line.startsWith("TOTAL:")) {
                totalCount = line.removePrefix("TOTAL:").trim().toIntOrNull() ?: 0
                continue
            }
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val uuid = parts[0].trim()
            val mtime = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
            val cwd = parts.getOrNull(2)?.trim().orEmpty()
            val preview = parts.getOrNull(3)?.trim().orEmpty()

            // FIX 1: validate UUID strictly — drop anything that doesn't match.
            if (!UUID_REGEX.matches(uuid)) continue
            if (cwd.isBlank()) continue

            val key = Triple(server.id, uuid, cwd)
            val existing = seen[key]
            if (existing != null && existing >= mtime) continue
            seen[key] = mtime

            sessions.removeAll { it.uuid == uuid && it.cwd == cwd }
            sessions.add(
                ClaudeHistorySession(
                    server = server,
                    uuid = uuid,
                    cwd = cwd,
                    lastModifiedEpoch = mtime,
                    preview = preview,
                )
            )
        }

        val sorted = sessions.sortedByDescending { it.lastModifiedEpoch }
        return ScanResult(sessions = sorted, totalCount = totalCount)
    }
}
