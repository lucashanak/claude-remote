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
     * The per-server snapshot the 15 s reconcile loop reads: every tombstoned
     * tmux name, then a `---` line, then the raw manifest.
     *
     * Both halves in ONE exec because that loop already runs per server and its
     * cost is measured in radio wakeups on a phone. The tombstone half is what
     * lets a device notice a close made on ANOTHER device without waiting for
     * its own connection to drop — until this existed, a peer whose `tmux
     * attach` merely exited (its SSH shell stays alive, so nothing fires
     * onConnectionLost) kept showing a closed session for hours.
     */
    fun serverSnapshot(): String =
        "set -u; D=\"\$HOME/.claude-remote\"; mkdir -p \"\$D\"; " +
            "LOCK=\"\$D/sessions.lock\"; touch \"\$LOCK\"; " +
            "( flock -s 9; " +
            "cat \"\$D/forgotten\" 2>/dev/null; " +
            "find \"\$D/forgotten.d\" -maxdepth 1 -type f -mtime -$TOMBSTONE_DAYS -printf '%f\\n' 2>/dev/null; " +
            "echo $CLOSE_STATE_SEPARATOR; " +
            "cat \"\$D/sessions.json\" 2>/dev/null; " +
            ") 9<>\"\$LOCK\""

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
     * Rename tmux session [oldName] to [newName]. `=` forces an EXACT target match: plain
     * `-t name` prefix-matches, so renaming `x` while only `x-2` existed renamed the
     * WRONG session (measured on tmux 3.5a).
     */
    fun renameSession(oldName: String, newName: String): String =
        "tmux rename-session -t '=${oldName.replace("'", "'\\''")}' ${sq(newName)}"

    /**
     * Everything one reconcile round needs, in ONE exec per server. The output is a
     * sequence of sections, each closed by `__END_<section>__ rc=<status>` — a section
     * with a missing END line (output cut off) or a non-zero rc is UNKNOWN to the
     * parser ([ReconcileParse]), and unknown never leads to a forget:
     *  - `snap`: [serverSnapshot] (tombstones, `---`, manifest) under the shared lock (the
     *    manifest is written without a trailing newline, so one is added before the END line);
     *  - `tmux`: `tmux -u list-sessions` (one name per line);
     *  - `panes`: ONE `tmux -u list-panes -a` (`<session> <pane_pid>`) restricted to
     *    [names] by exact, whole-line match (a name that is a prefix of another must not
     *    pull in the longer one);
     *  - `ps`: ONE `ps -eo pid=,ppid=,comm=` (the process table, which the `claude` section
     *    reuses instead of running a second `ps`);
     *  - `claude`: per process whose comm is `claude` or a bare version number:
     *    `EXE <pid> <exe>`, `CAPF <pid> <marker 1|0|-> <disableAllHooks 1|0|-> <off 1|0>`
     *    (the facts of [HookCapContract]; `-` = unreadable; `off` = the file
     *    `~/.claude-remote/hookev.off` exists, which silences every hook) and, when the process has a
     *    `sessions/<pid>.json` under its own config dir, `SID <pid> <uuid|->` (`-` when
     *    the transcript does not exist yet — claude forks on --resume and reports the new
     *    id before writing its jsonl).
     */
    fun reconcileAll(names: Collection<String>): String =
        RECONCILE_ALL.replace("@@TD@@", TOMBSTONE_DAYS.toString())
            .replace("@@NAMES@@", if (names.isEmpty()) "''" else names.joinToString(" ") { sq(it) })

    private val RECONCILE_ALL = """set +e
D="${'$'}HOME/.claude-remote"; mkdir -p "${'$'}D"; LOCK="${'$'}D/sessions.lock"; touch "${'$'}LOCK"
( flock -s 9
cat "${'$'}D/forgotten" 2>/dev/null
find "${'$'}D/forgotten.d" -maxdepth 1 -type f -mtime -@@TD@@ -printf '%f\n' 2>/dev/null
echo ---
cat "${'$'}D/sessions.json" 2>/dev/null || :
echo
) 9<>"${'$'}LOCK"; echo "__END_snap__ rc=${'$'}?"
tmux -u list-sessions -F '#{session_name}' 2>/dev/null; echo "__END_tmux__ rc=${'$'}?"
NAMES=${'$'}(printf '%s\n' @@NAMES@@)
PANES=${'$'}(tmux -u list-panes -a -F '#{session_name} #{pane_pid}' 2>/dev/null); R=${'$'}?
printf '%s\n' "${'$'}PANES" | while IFS= read -r l; do n="${'$'}{l% *}"; printf '%s\n' "${'$'}NAMES" | grep -qxF -- "${'$'}n" && printf '%s\n' "${'$'}l"; done
echo "__END_panes__ rc=${'$'}R"
PS=${'$'}(ps -eo pid=,ppid=,comm= 2>/dev/null); R=${'$'}?; printf '%s\n' "${'$'}PS"; echo "__END_ps__ rc=${'$'}R"
dis() { f="${'$'}1"; if [ -r "${'$'}f" ]; then if grep -Eq '"disableAllHooks"[[:space:]]*:[[:space:]]*true' "${'$'}f" 2>/dev/null; then echo 1; else echo 0; fi; elif [ -e "${'$'}f" ]; then echo -; else echo 0; fi; }
if [ -e "${'$'}D/hookev.off" ]; then OFF=1; else OFF=0; fi
for p in ${'$'}(printf '%s\n' "${'$'}PS" | awk '${'$'}3=="claude" || ${'$'}3 ~ /^[0-9]+(\.[0-9]+)+${'$'}/ {print ${'$'}1}'); do
E=${'$'}(readlink /proc/${'$'}p/exe 2>/dev/null); echo "EXE ${'$'}p ${'$'}{E:--}"
CFG=${'$'}(tr '\0' '\n' < /proc/${'$'}p/environ 2>/dev/null | sed -n 's|^CLAUDE_CONFIG_DIR=||p' | head -1); C="${'$'}{CFG:-${'$'}HOME/.claude}"
if [ -r "${'$'}C/settings.json" ]; then if grep -q 'claude-remote-ev v' "${'$'}C/settings.json" 2>/dev/null; then M=1; else M=0; fi; elif [ -e "${'$'}C/settings.json" ]; then M=-; else M=0; fi
D1=${'$'}(dis "${'$'}C/settings.json"); D2=${'$'}(dis "${'$'}C/settings.local.json")
if [ "${'$'}D1" = 1 ] || [ "${'$'}D2" = 1 ]; then X=1; elif [ "${'$'}D1" = - ] || [ "${'$'}D2" = - ]; then X=-; else X=0; fi
echo "CAPF ${'$'}p ${'$'}M ${'$'}X ${'$'}OFF"
f="${'$'}C/sessions/${'$'}p.json"
if [ -f "${'$'}f" ]; then v=${'$'}(jq -r '.sessionId // empty' "${'$'}f" 2>/dev/null); ok=-; [ -n "${'$'}v" ] && { ls "${'$'}HOME"/.claude/projects/*/"${'$'}v".jsonl >/dev/null 2>&1 || ls "${'$'}C"/projects/*/"${'$'}v".jsonl >/dev/null 2>&1; } && ok=${'$'}v; echo "SID ${'$'}p ${'$'}ok"; fi
done; true; echo "__END_claude__ rc=${'$'}?""""

    /**
     * The pid-pinned UUID probe of one tmux session (what `readRealSessionId` runs): the
     * first process of the pane's subtree, shallowest first, that has a
     * `sessions/<pid>.json` under its own config dir, and only if the transcript of that
     * id exists. Prints the uuid, or nothing.
     */
    fun uuidProbe(tmuxName: String): String =
        UUID_PROBE.replace("@@N@@", tmuxName.replace("'", "'\\''"))

    private val UUID_PROBE = """PID=${'$'}(tmux list-panes -t '=@@N@@' -F '#{pane_pid}' 2>/dev/null | head -1); [ -n "${'$'}PID" ] || exit 1; PIDS=${'$'}(ps -eo pid=,ppid= 2>/dev/null | awk -v root="${'$'}PID" '{ kids[${'$'}2]=kids[${'$'}2]" "${'$'}1 } END { head=0;tail=0;q[tail++]=root; while(head<tail){p=q[head++];print p;m=split(kids[p],a," ");for(i=1;i<=m;i++)if(a[i]!="")q[tail++]=a[i]} }'); for p in ${'$'}PIDS; do CFG=${'$'}(tr '\0' '\n' < /proc/${'$'}p/environ 2>/dev/null | sed -n 's|^CLAUDE_CONFIG_DIR=||p' | head -1); f="${'$'}{CFG:-${'$'}HOME/.claude}/sessions/${'$'}p.json"; [ -f "${'$'}f" ] || continue; v=${'$'}(jq -r '.sessionId // empty' "${'$'}f" 2>/dev/null); [ -n "${'$'}v" ] && { ls "${'$'}HOME"/.claude/projects/*/"${'$'}v".jsonl >/dev/null 2>&1 || ls "${'$'}{CFG:-${'$'}HOME/.claude}"/projects/*/"${'$'}v".jsonl >/dev/null 2>&1; } && echo "${'$'}v"; break; done"""

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
              "elif [ \"\$(tmux -u list-sessions -F '#{session_name}' 2>/dev/null | grep -c '^claude-server-')\" -eq 0 ]; then " +
                "echo \"[\$(date -u +%FT%TZ)] push(merge): no live claude-server-* — skip (preserve shared manifest)\" >> \"\$D/push.log\"; " +
              "else " +
                // `-u` is load-bearing here: this LIVE set decides which peer
                // entries survive the merge. Read without it, a session whose
                // alias has diacritics comes back '_'-mangled, fails to match its
                // own manifest entry, and is pruned as dead — the reboot restore
                // then never rebuilds it. See SessionOrchestrator.serverHasOtherLiveSession.
                "LIVE=\$(tmux -u list-sessions -F '#{session_name}' 2>/dev/null | jq -R . | jq -s . 2>/dev/null); " +
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

/** What the `claude` section of [ManifestCommands.reconcileAll] says about one process. */
internal data class ClaudeFact(
    val exe: String? = null,
    /** settings.json of the process's config dir carries the hook marker; null = unreadable. */
    val markerInSettings: Boolean? = null,
    /** `disableAllHooks` in settings.json / settings.local.json; null = unreadable. */
    val disableAllHooks: Boolean? = null,
    /** `~/.claude-remote/hookev.off` exists: the hook script writes nothing. Null = not reported. */
    val hooksOff: Boolean? = false,
    /** A `sessions/<pid>.json` exists for this process. */
    val hasSessionFile: Boolean = false,
    /** The session id in it, only when its transcript exists. */
    val uuid: String? = null,
)

/** The verdict of the forget rule for one tmux name. */
internal enum class CloseVerdict { FORGET, KEEP, UNKNOWN }

/**
 * Parsed result of one [ManifestCommands.reconcileAll] round. A field is null when its
 * section was missing, cut off or failed — i.e. UNKNOWN. Nothing here guesses: every
 * decision helper answers `UNKNOWN`/null for unknown input, and unknown never forgets.
 */
internal class ReconcileSnapshot(
    val tombstoned: Set<String>?,
    val manifest: List<com.clauderemote.storage.PersistedSession>?,
    val tmuxNames: Set<String>?,
    private val tmuxListing: String?,
    /** tmux session name → pid of its first pane. */
    val panePids: Map<String, Int>?,
    val procs: List<HookCapContract.Proc>?,
    val claude: Map<Int, ClaudeFact>?,
) {
    /**
     * Forget iff tombstoned ∧ absent from `list-sessions` ∧ at least one OTHER live
     * `claude-server-*` session exists (a dead tmux server or an empty listing never
     * counts as proof). Anything unknown is [CloseVerdict.UNKNOWN].
     */
    fun closeVerdict(tmuxName: String): CloseVerdict {
        val tomb = tombstoned ?: return CloseVerdict.UNKNOWN
        val live = tmuxNames ?: return CloseVerdict.UNKNOWN
        if (tmuxName !in tomb || tmuxName in live) return CloseVerdict.KEEP
        return if (com.clauderemote.session.TmuxPeerLiveness.hasOtherLivePeer(tmuxListing.orEmpty(), tmuxName)) {
            CloseVerdict.FORGET
        } else {
            CloseVerdict.KEEP
        }
    }

    /** Pids of the pane's subtree, breadth first (the pane's own process first). */
    private fun subtree(panePid: Int, all: List<HookCapContract.Proc>): List<Int> {
        val children = all.groupBy { it.ppid }
        val order = mutableListOf(panePid)
        val seen = mutableSetOf(panePid)
        var i = 0
        while (i < order.size) { for (c in children[order[i]].orEmpty()) if (seen.add(c.pid)) order += c.pid; i++ }
        return order
    }

    /**
     * The REAL claude session id of [tmuxName]: the shallowest process of the pane's
     * subtree that has a `sessions/<pid>.json` decides (a launcher without one is
     * skipped); its id counts only when the transcript exists. Null = unknown / none.
     */
    fun liveUuid(tmuxName: String): String? {
        val pane = panePids?.get(tmuxName) ?: return null
        val all = procs ?: return null
        val facts = claude ?: return null
        val pid = subtree(pane, all).firstOrNull { facts[it]?.hasSessionFile == true } ?: return null
        return facts.getValue(pid).uuid
    }

    /** The manifest's record of the claude session id of [tmuxName] (a weaker source than [liveUuid]). */
    fun manifestUuid(tmuxName: String): String? =
        manifest?.firstOrNull { it.tmuxSessionName == tmuxName }?.claudeSessionId?.takeUnless { it.isBlank() }

    /** CAP verdict of [tmuxName] per [HookCapContract]; null = unknown. */
    fun capability(tmuxName: String): Boolean? {
        val pane = panePids?.get(tmuxName) ?: return null
        val all = procs ?: return null
        val facts = claude ?: return null
        val c = HookCapContract.shallowestClaude(pane, all) ?: return null
        val f = facts[c] ?: return null
        return HookCapContract.verdict(f.markerInSettings, f.disableAllHooks, f.hooksOff)
    }
}

/** Parser for the output of [ManifestCommands.reconcileAll]. Pure. */
internal object ReconcileParse {
    private val END = Regex("""__END_([a-z]+)__ rc=(\d+)""")

    /**
     * The COMPLETE sections (END line present, rc 0) and their payload lines. A section
     * that never closed — the output was cut off — and one that failed are simply absent.
     * Payload lines after the last END belong to a section that never closed and are dropped.
     */
    fun sections(raw: String): Map<String, List<String>> {
        val out = HashMap<String, List<String>>()
        var cur = ArrayList<String>()
        for (line in raw.lineSequence()) {
            val m = END.matchEntire(line.trim())
            if (m != null) {
                if (m.groupValues[2] == "0") out[m.groupValues[1]] = cur
                cur = ArrayList()
            } else {
                cur.add(line)
            }
        }
        return out
    }

    fun parse(raw: String, json: kotlinx.serialization.json.Json): ReconcileSnapshot {
        val sec = sections(raw)
        val snap = sec["snap"]?.let { SessionPersistenceService.parseServerSnapshot(it.joinToString("\n"), json) }
        val tmuxLines = sec["tmux"]
        val tmuxNames = tmuxLines?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val panes = sec["panes"]?.let { lines ->
            val m = LinkedHashMap<String, Int>()
            for (l in lines) {
                val i = l.lastIndexOf(' ')
                if (i <= 0) continue
                val pid = l.substring(i + 1).trim().toIntOrNull() ?: continue
                m.putIfAbsent(l.substring(0, i), pid)
            }
            m
        }
        val claude = sec["claude"]?.let { parseClaude(it) }
        val procs = sec["ps"]?.let { lines ->
            lines.mapNotNull { l ->
                val t = l.trim().split(Regex("\\s+"), limit = 3)
                if (t.size < 3) return@mapNotNull null
                val pid = t[0].toIntOrNull() ?: return@mapNotNull null
                val ppid = t[1].toIntOrNull() ?: return@mapNotNull null
                HookCapContract.Proc(pid, ppid, t[2].trim(), claude?.get(pid)?.exe)
            }
        }
        return ReconcileSnapshot(
            tombstoned = snap?.tombstoned,
            manifest = snap?.sessions,
            tmuxNames = tmuxNames,
            tmuxListing = tmuxLines?.joinToString("\n"),
            panePids = panes,
            procs = procs,
            claude = claude,
        )
    }

    private fun parseClaude(lines: List<String>): Map<Int, ClaudeFact> {
        val m = HashMap<Int, ClaudeFact>()
        fun tri(s: String): Boolean? = when (s) { "1" -> true; "0" -> false; else -> null }
        for (l in lines) {
            val t = l.trim().split(Regex("\\s+"), limit = 3)
            if (t.size < 3) continue
            val pid = t[1].toIntOrNull() ?: continue
            val cur = m[pid] ?: ClaudeFact()
            when (t[0]) {
                "EXE" -> m[pid] = cur.copy(exe = t[2].takeUnless { it == "-" })
                "CAPF" -> {
                    val rest = t[2].trim().split(Regex("\\s+"))
                    if (rest.size >= 2) m[pid] = cur.copy(markerInSettings = tri(rest[0]), disableAllHooks = tri(rest[1]), hooksOff = if (rest.size >= 3) tri(rest[2]) else false)
                }
                "SID" -> m[pid] = cur.copy(hasSessionFile = true, uuid = t[2].trim().takeIf { Regex("^[0-9a-f-]{36}$").matches(it) })
            }
        }
        return m
    }
}
