package com.clauderemote.session.service

/**
 * Pure builders for the remote shell one-liners that maintain the shared
 * server-side session state under `~/.claude-remote/`:
 *
 *  - `sessions.json` — the restore manifest (see [SessionPersistenceService]),
 *  - `forgotten` — transient tombstones the drift daemon consumes (one tmux
 *    name per line; drift self-cleans a name once it is absent from both live
 *    tmux and the manifest, i.e. usually within one 60 s tick),
 *  - `forgotten.d/<tmux name>` — DURABLE tombstone markers, mtime = close time.
 *    Only a genuine user close writes these, and only the app reads them, so a
 *    device that was asleep when another device closed a session still learns
 *    about the close long after drift dropped the transient tombstone.
 *
 * Everything lives here rather than inline at the call sites so
 * [ManifestCommandSyntaxTest] can `bash -n` (and shellcheck) each command —
 * the same regression guard [SessionPersistenceService.INSTALL_RESTORE_COMMAND]
 * gets from RestoreScriptSyntaxTest. A shell typo in any of these ships
 * straight to production servers and fails silently (exec output discarded).
 */
internal object ManifestCommands {

    /** How long a durable tombstone stays authoritative for the client probe. */
    const val TOMBSTONE_DAYS = 14

    /** Markers older than this are swept on the next close. */
    private const val TOMBSTONE_PRUNE_DAYS = 30

    /** Separator between the tombstone verdict and the manifest JSON in [closeState]. */
    const val CLOSE_STATE_SEPARATOR = "---"

    /** Prefix of [closeState]'s first output line. */
    const val TOMBSTONED_PREFIX = "TOMBSTONED:"

    /**
     * Single-quote [value] for the remote shell. tmux session names are
     * user-supplied (ConnectScreen lets the user type one), so nothing may be
     * interpolated raw.
     */
    fun sq(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * True when [tmuxName] is usable as a tombstone FILE name. A name with a
     * slash or a newline would escape `forgotten.d/` (or corrupt the
     * line-per-name `forgotten` file), so those fall back to the transient
     * tombstone only. tmux itself rejects `.` and `:` in session names, and
     * everything else is safe inside the quoted path.
     */
    fun isMarkerSafe(tmuxName: String): Boolean =
        tmuxName.isNotBlank() && !tmuxName.contains('/') && !tmuxName.contains('\n')

    /**
     * Record a close of [tmuxName]: append the transient tombstone the drift
     * daemon reads and — when [durable], i.e. the user really closed the
     * session rather than a heuristic prune deciding it was gone — drop the
     * `forgotten.d` marker that outlives drift's self-clean.
     *
     * Written under the SAME `sessions.lock` the drift daemon, restore.sh and
     * the manifest push take, so it can never race drift's tombstone
     * self-clean. Uses the `( flock -x 9; … ) 9<>LOCK` idiom rather than
     * `flock -x LOCK bash -c '…'`: no nested quoting, so the body stays
     * readable (and checkable) as plain bash.
     */
    fun tombstone(tmuxName: String, durable: Boolean): String {
        val n = sq(tmuxName)
        val marker = durable && isMarkerSafe(tmuxName)
        return buildString {
            append("set -u; D=\"\$HOME/.claude-remote\"; mkdir -p \"\$D\"; ")
            if (marker) append("mkdir -p \"\$D/forgotten.d\"; ")
            append("LOCK=\"\$D/sessions.lock\"; touch \"\$LOCK\"; ")
            append("N=$n; ")
            append("( flock -x 9; ")
            append("grep -qxF \"\$N\" \"\$D/forgotten\" 2>/dev/null || printf '%s\\n' \"\$N\" >> \"\$D/forgotten\"; ")
            if (marker) {
                append("touch \"\$D/forgotten.d/\$N\"; ")
                // Sweep long-expired markers while we hold the lock — they are
                // no longer honoured by closeState() and would otherwise
                // accumulate one file per session ever closed.
                append("find \"\$D/forgotten.d\" -maxdepth 1 -type f -mtime +$TOMBSTONE_PRUNE_DAYS -delete 2>/dev/null || true; ")
            }
            append(") 9<>\"\$LOCK\"; ")
            append("echo \"[\$(date -u +%FT%TZ)] forget: \$N (durable=${if (marker) 1 else 0})\" >> \"\$D/forget.log\"")
        }
    }

    /**
     * Ask the server, in ONE round-trip, both questions the reconnect decision
     * needs about [tmuxName]: is it tombstoned (some device closed it), and is
     * it still in the restore manifest?
     *
     * Output is `TOMBSTONED:0|1`, then a `---` line, then the raw
     * `sessions.json` (possibly empty). Read under the SHARED lock so we never
     * see a half-written manifest.
     */
    fun closeState(tmuxName: String): String {
        val n = sq(tmuxName)
        return "set -u; D=\"\$HOME/.claude-remote\"; mkdir -p \"\$D\"; " +
            "LOCK=\"\$D/sessions.lock\"; touch \"\$LOCK\"; " +
            "N=$n; " +
            "( flock -s 9; " +
            "T=0; " +
            "if grep -qxF \"\$N\" \"\$D/forgotten\" 2>/dev/null; then T=1; fi; " +
            // A marker older than TOMBSTONE_DAYS is ignored: past that the tmux
            // name may legitimately have been reused, and a stale marker would
            // force-forget the NEW session on every reconnect.
            "if [ \"\$T\" = 0 ] && [ -f \"\$D/forgotten.d/\$N\" ] && " +
            "[ -n \"\$(find \"\$D/forgotten.d/\$N\" -maxdepth 0 -mtime -$TOMBSTONE_DAYS 2>/dev/null)\" ]; then T=1; fi; " +
            "echo \"$TOMBSTONED_PREFIX\$T\"; " +
            "echo $CLOSE_STATE_SEPARATOR; " +
            "cat \"\$D/sessions.json\" 2>/dev/null; " +
            ") 9<>\"\$LOCK\""
    }

    /**
     * Drop every tombstone for [tmuxName] — transient and durable. Called when
     * the user deliberately launches/attaches this tmux name: that is positive
     * intent that the name is alive again, and without it a reused name would
     * stay poisoned (closeState would keep reporting "closed elsewhere", and
     * the manifest push below would keep filtering the session out).
     */
    fun clearTombstone(tmuxName: String): String {
        val n = sq(tmuxName)
        return "set -u; D=\"\$HOME/.claude-remote\"; [ -d \"\$D\" ] || exit 0; " +
            "LOCK=\"\$D/sessions.lock\"; touch \"\$LOCK\"; " +
            "N=$n; " +
            "( flock -x 9; " +
            "rm -f \"\$D/forgotten.d/\$N\" 2>/dev/null || true; " +
            "if [ -s \"\$D/forgotten\" ]; then " +
            "grep -vxF \"\$N\" \"\$D/forgotten\" > \"\$D/.forgotten.tmp.\$\$\" 2>/dev/null || true; " +
            "mv \"\$D/.forgotten.tmp.\$\$\" \"\$D/forgotten\" 2>/dev/null || rm -f \"\$D/.forgotten.tmp.\$\$\"; " +
            "fi; " +
            ") 9<>\"\$LOCK\""
    }

    /**
     * MERGE, not overwrite. The previous `cat > tmp && mv` clobbered
     * the shared sessions.json with only THIS client's sessions, so
     * whichever client (Android vs desktop) synced last silently
     * dropped the others' sessions — and the next reboot's restore
     * service then only rebuilt that truncated subset.
     *
     * New semantics (under the same flock the drift daemon + restore
     * use): keep the incoming list (this client wins for its own
     * sessions) PLUS any existing entry whose tmux session is still
     * LIVE on the server and isn't already in the incoming list.
     * Killed/forgotten sessions (kill-session runs before this push)
     * are no longer live and aren't in incoming, so they correctly
     * drop out — no resurrection on reboot. Falls back to a plain
     * overwrite when jq is unavailable (matches old behaviour).
     *
     * The incoming + scratch temp files are suffixed with the remote
     * shell's PID ($$) so a burst of near-simultaneous pushes (the
     * app fires several on a multi-tab reconnect) can't race on a
     * shared path — without per-PID names an earlier push would
     * `rm` the incoming file out from under a later one, which then
     * merged against an empty incoming and collapsed sessions.json.
     *
     * Reads [payloadLength] only for the push.log line; the payload itself is
     * streamed to the command's stdin by the caller.
     */
    fun pushMerge(serverId: String, payloadLength: Int): String {
        // Interpolated inside a double-quoted shell string, so a quote in the
        // id would break out of it.
        val safeServerId = serverId.replace("\"", "")
        return (
            "set -u; D=\"\$HOME/.claude-remote\"; mkdir -p \"\$D\"; " +
            "LOCK=\"\$D/sessions.lock\"; touch \"\$LOCK\"; " +
            "SF=\"\$D/sessions.json\"; INC=\"\$D/.sessions.incoming.\$\$\"; " +
            "cat > \"\$INC\"; " +
            "if command -v jq >/dev/null 2>&1; then " +
              // WHOLE-SERVER-DEATH GUARD: the merge below prunes any OLD peer
              // entry whose tmux name isn't in the LIVE set. If tmux itself
              // errors (server gone) or no claude-server-* sessions are live,
              // LIVE is effectively empty and the merge would drop EVERY other
              // client's sessions from the shared manifest. Skip the push
              // entirely so a transient server death can't shrink the source of
              // truth — the incoming client's sessions (also dead right now)
              // get re-pushed once the server is actually back up.
              "if ! tmux list-sessions >/dev/null 2>&1; then " +
                "echo \"[\$(date -u +%FT%TZ)] push(merge): tmux down — skip (preserve shared manifest)\" >> \"\$D/push.log\"; " +
              "elif [ \"\$(tmux list-sessions -F '#{session_name}' 2>/dev/null | grep -c '^claude-server-')\" -eq 0 ]; then " +
                "echo \"[\$(date -u +%FT%TZ)] push(merge): no live claude-server-* — skip (preserve shared manifest)\" >> \"\$D/push.log\"; " +
              "else " +
                "LIVE=\$(tmux list-sessions -F '#{session_name}' 2>/dev/null | jq -R . | jq -s . 2>/dev/null); " +
                "[ -n \"\$LIVE\" ] || LIVE='[]'; " +
                "( flock -x 9; " +
                  "OLD='[]'; [ -f \"\$SF\" ] && OLD=\$(cat \"\$SF\"); " +
                  // Tombstoned names are subtracted from the RESULT, incoming
                  // included. Without this, a peer that still holds a session
                  // closed on another device re-adds it here the next time it
                  // pushes (its incoming list always wins for its own
                  // sessions) — and the drift daemon's self-heal then
                  // relaunches the pane from that manifest entry, resurrecting
                  // a session the user closed. Both tombstone stores are read:
                  // the transient `forgotten` file and the durable
                  // `forgotten.d` markers still inside their window.
                  "FG=\$( { cat \"\$D/forgotten\" 2>/dev/null; " +
                    "find \"\$D/forgotten.d\" -maxdepth 1 -type f -mtime -$TOMBSTONE_DAYS -printf '%f\\n' 2>/dev/null; " +
                    "} | jq -R . | jq -s 'map(select(length>0))' 2>/dev/null); " +
                  "[ -n \"\$FG\" ] || FG='[]'; " +
                  "MERGED=\$(jq -n --slurpfile inc \"\$INC\" --argjson old \"\$OLD\" --argjson live \"\$LIVE\" --argjson fg \"\$FG\" '" +
                    "(\$inc[0] // []) as \$incoming " +
                    "| (\$incoming | map(.tmuxSessionName)) as \$names " +
                    "| \$incoming + (\$old | map(. as \$e | select(((\$names | index(\$e.tmuxSessionName)) | not) and (\$live | index(\$e.tmuxSessionName)))))" +
                    "| map(select(.tmuxSessionName as \$n | (\$fg | index(\$n)) | not))" +
                  "' 2>/dev/null); " +
                  "if [ -n \"\$MERGED\" ]; then printf '%s' \"\$MERGED\" > \"\$SF.tmp.\$\$\" && mv \"\$SF.tmp.\$\$\" \"\$SF\"; else cp \"\$INC\" \"\$SF\"; fi " +
                ") 9<>\"\$LOCK\"; " +
              "fi; " +
            "else cp \"\$INC\" \"\$SF\"; fi; " +
            "rm -f \"\$INC\"; " +
            "echo \"[\$(date -u +%FT%TZ)] push(merge): $payloadLength bytes for $safeServerId\" >> \"\$D/push.log\""
        )
    }
}
