package com.clauderemote.session.service

import com.clauderemote.connection.SshManager
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.SessionActivity
import com.clauderemote.model.SessionStatus
import com.clauderemote.model.TmuxNameParser
import com.clauderemote.session.TabManager
import com.clauderemote.storage.PersistedSession
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Preserve the exact log tag the moved bodies used while they lived in
// SessionOrchestrator, so device-log lines are byte-identical.
private const val TAG = "SessionOrchestrator"

/**
 * What the server says about one tmux name at reconnect time.
 *
 * @param tracked the name is still in the shared restore manifest.
 * @param tombstoned some device recorded an explicit close of this name — the
 *   authoritative answer, since it is written before the pane is killed and
 *   therefore cannot lose the race against a peer's auto-reconnect.
 */
internal data class RemoteCloseState(val tracked: Boolean, val tombstoned: Boolean)

/**
 * One read of a server's shared state: the restore manifest plus every tmux
 * name currently tombstoned (closed). Fetched together because the 15 s
 * reconcile loop pays for the round-trip either way.
 */
internal data class ServerSnapshot(
    val sessions: List<PersistedSession>,
    val tombstoned: Set<String>,
)

/**
 * Owns the SHARED server-side `sessions.json` (fetch/cache/push), the systemd
 * restore-service installer + restore/drift scripts, the per-session real
 * claude-session-id refresh pollers, and the forget/rename lifecycle edits.
 * Extracted verbatim from SessionOrchestrator: state, timing, ordering, the
 * [sessionsJsonMutex] double-checked cache and the [installedRestoreServers]
 * once-guard are unchanged — a pure move so runtime behavior stays identical.
 *
 * [readRealSessionId] stays in the orchestrator (injected as a lambda);
 * [disconnectSession] and [onActivityUpdate] bridge back to orchestrator/
 * statusService state that forgetSession/restorePersistedTabs still touch.
 */
internal class SessionPersistenceService(
    private val scope: CoroutineScope,
    private val registry: ConnectionRegistry,
    private val tabManager: TabManager,
    private val serverStorage: ServerStorage,
    private val sessionStorage: SessionStorage?,
    private val transcript: TranscriptService,
    private val terminalIO: TerminalIOService,
    private val isBackground: () -> Boolean,
    private val readRealSessionId: suspend (sshManager: SshManager, tmuxName: String) -> String?,
    /**
     * `tmux has-session` for one name. Wired to TmuxProbes.probeTmuxSession,
     * which fails OPEN (true = alive) — required here: a probe that couldn't
     * answer must never be read as "the pane is gone", because that deletes a
     * live tab.
     */
    private val probeTmuxAlive: suspend (sshManager: SshManager, tmuxName: String) -> Boolean,
    private val onActivityUpdate: (sessionId: String, activity: SessionActivity) -> Unit,
    private val disconnectSession: suspend (sessionId: String) -> Unit,
    private val onForgotten: (serverId: String, tmuxSessionName: String) -> Unit,
    /** Who a user close is recorded as in `closed.jsonl` (`app:<installId>`); a prune is recorded as `prune`. */
    private val closedBy: () -> String = { "app" },
) : PersistenceClosures {
    // (serverId, tmux name) -> when THIS device closed it (forgetSession). Feeds
    // recentlyClosedLocally; bounded by pruning on every write.
    private val closedLocally = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Long>()

    override fun recentlyClosedLocally(serverId: String, name: String, withinMs: Long): Boolean {
        val at = closedLocally[serverId to name] ?: return false
        return System.currentTimeMillis() - at <= withinMs
    }

    private fun noteClosedLocally(serverId: String, name: String) {
        val now = System.currentTimeMillis()
        closedLocally.entries.removeAll { now - it.value > CLOSED_LOCALLY_RETAIN_MS }
        closedLocally[serverId to name] = now
    }

    // sessions.json is one file per SERVER; with N sessions each running the
    // 15 s reconcile loop, N−1 of the flock+cat fetches were redundant. Cache
    // the parsed snapshot per server with a TTL just under the loop period so
    // only the first session to tick pays the exec.
    private class SessionsSnapshot(val at: Long, val snapshot: ServerSnapshot)
    private val sessionsJsonCache = java.util.concurrent.ConcurrentHashMap<String, SessionsSnapshot>()
    private val sessionsJsonMutex = Mutex()

    // Per-session pollers that read ~/.claude/sessions/<pid>.json on the
    // server to capture the *real* claude session_id — which can drift from
    // the UUID we passed via --session-id when the user invokes /resume,
    // /clear, /compact etc. Without this we'd push a stale UUID to
    // sessions.json and the next reboot's restore.sh would --resume the
    // wrong (or non-existent) conversation.
    private val sessionIdRefreshJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    // coerceInputValues: this parses the SERVER-side sessions.json, written by
    // whatever restore.sh/drift.sh a possibly different-vintage client
    // installed — the exact phone/watch (or upgrade/downgrade) version-skew
    // scenario. An unknown enum value would otherwise make the whole fetch
    // throw; PersistedSession's mode/model/connectionType now carry defaults
    // (see SessionStorage.kt) so this actually takes effect here too. Low
    // blast radius either way — fetchSessionsFromServer already fails open
    // (returns null) on any decode error, so this only upgrades "skip this
    // tick's server sync" into "sync with safe defaults".
    private val fetchJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // Companion (not an instance field) and `internal` rather than `private` so
    // RestoreScriptSyntaxTest in desktopTest can `bash -n` this script — and the
    // RESTORE_EOF/DRIFT_EOF heredocs it writes — without instantiating
    // SessionPersistenceService, whose constructor takes many live collaborators.
    internal companion object {
        /**
         * The limit of the close's one exec ([ManifestCommands.closeCommand]: tombstone, record ≤ ~7 s, kill ≤ 3 s).
         * The desktop exit waits for it ([com.clauderemote.session.PendingCloses.EXIT_WAIT_MS]).
         */
        const val CLOSE_EXEC_MS = 12_000L

        /** How long a local close is remembered (the reducer asks within minutes at most). */
        private const val CLOSED_LOCALLY_RETAIN_MS = 30 * 60_000L

        /**
         * The tmux socket guard, pasted VERBATIM into restore.sh, drift.sh and tmux-sockheal.sh. Must be declared
         * before [INSTALL_RESTORE_COMMAND]: companion properties initialise in textual order.
         *
         * Incident 2026-11-02: the whole `/tmp/tmux-<uid>` directory vanished while the tmux server kept running.
         * drift saw `LIVE=[]`, restore.sh started a SECOND server and relaunched all 51 sessions into it as
         * duplicates; with the second server up, `LIVE` was never empty again and the duplicates continued.
         * SIGUSR1 makes tmux recreate its socket, but only when the directory exists (measured: tmux 3.5a), and two
         * servers on one path must never be signalled (the signalled one unlinks the other's socket).
         */
        private val SOCK_GUARD_FUNCS = """
# ---- tmux socket guard (identical in restore.sh, drift.sh and tmux-sockheal.sh) ----
# Before anything relaunches sessions or creates a tmux server, count the servers LISTENING on the exact path
# ${'$'}{TMUX_TMPDIR:-/tmp}/tmux-<uid>/default (a deleted socket file keeps its bound name in the kernel):
#   0   nothing alive there: the old behaviour (relaunch / create)                         rc 0
#   1   the socket answers                                                                  rc 0
#       socket file gone: mkdir -m 700, SIGUSR1 (tmux recreates it), wait <= 2 s            rc 2 healed, 1 failed
#       a stale socket file there (connect refused): the same heal                          rc 2 healed, 1 failed
#       answers only through -S (directory not private): chmod 700                         rc 2 healed, 1 failed
#       socket there but list-sessions fails (hung server): do nothing                      rc 1
#   2+  split brain: no signal, no relaunch, no new server                                  rc 1
# rc 2 = "it was unusable when we looked and is usable again": the caller relaunches nothing this time (the app's
# anchor attaches a session that is still there). On rc 1 SG_WHY names the cause (split, hung, unsafe, heal).
# Never relaunch while anything listens there. The server is identified by the listening socket's exact path,
# never by cmdline: the live server's cmdline names no socket, and test servers isolate through TMUX_TMPDIR.
# ~/.claude-remote/drift-heal.off switches this guard off, and only the guard: it is no brake on drift's relaunch
# (self-heal then runs without it). The brakes for drift are ~/.claude-remote/drift.off and stopping its timer.
SG_DIR="${'$'}HOME/.claude-remote"
SG_UID=${'$'}(id -u)
# tmux binds realpath(TMUX_TMPDIR)/tmux-<uid>/default: a trailing / or a symlink in TMUX_TMPDIR must not hide it.
SG_BASE="${'$'}{TMUX_TMPDIR:-/tmp}"
SG_BASE=${'$'}(realpath -m -- "${'$'}SG_BASE" 2>/dev/null || printf '%s' "${'$'}SG_BASE")
SG_SOCK_DIR="${'$'}SG_BASE/tmux-${'$'}SG_UID"
SG_SOCK="${'$'}SG_SOCK_DIR/default"
SG_LOCK="${'$'}SG_DIR/tmux-server.lock"
SG_OFF="${'$'}SG_DIR/drift-heal.off"
SG_WHO=${'$'}{SG_WHO:-script}
# A lasting fault logs one line per kind per 15 minutes, not one block per tick (1 440 a day). Per caller: drift.log,
# restore.log and sockheal.log each get the line (one caller's stamp silenced the fault in the others' logs).
# A line the stamp holds back still goes out with SG_QUIET in front when that is set (only tmux-sockheal.sh sets it:
# the log gets no copy, the refused tab gets its cause on stderr every time).
sg_log() {
    local st="${'$'}SG_DIR/.drift.sockguard.${'$'}SG_WHO.${'$'}1" now last
    now=${'$'}(date +%s); last=${'$'}(stat -c %Y "${'$'}st" 2>/dev/null || echo 0)
    if [ ${'$'}((now - last)) -lt 900 ]; then
        [ -n "${'$'}{SG_QUIET:-}" ] && echo "${'$'}{SG_QUIET}[${'$'}(date -u +%FT%TZ)] sockguard(${'$'}SG_WHO): ${'$'}2"
        return 0
    fi
    touch "${'$'}st" 2>/dev/null || true
    echo "[${'$'}(date -u +%FT%TZ)] sockguard(${'$'}SG_WHO): ${'$'}2"
}
# One line per server listening on exactly ${'$'}SG_SOCK: its pid, or "?" when it cannot be seen.
# Primary `ss -xlpH`; fallback /proc/net/unix, where only Flags=00010000 St=01 is the listening socket (the other
# rows with that path are accepted client connections).
sg_listeners() {
    local out ino pid
    if out=${'$'}(timeout 5 ss -xlpH 2>/dev/null); then
        printf '%s\n' "${'$'}out" | awk -v p="${'$'}SG_SOCK" '$2 == "LISTEN" && $5 == p {
            if (match($0, /pid=[0-9]+/)) print substr($0, RSTART + 4, RLENGTH - 4); else print "?" }'
        return 0
    fi
    awk -v p="${'$'}SG_SOCK" '$4 == "00010000" && $6 == "01" && $8 == p { print $7 }' /proc/net/unix 2>/dev/null \
        | while read -r ino; do
            pid=${'$'}(timeout 10 find /proc/[0-9]*/fd -maxdepth 1 -lname "socket:\[${'$'}ino\]" 2>/dev/null | head -1 | cut -d/ -f3)
            echo "${'$'}{pid:-?}"
        done
}
sg_count() { printf '%s\n' "${'$'}1" | awk 'NF { n++ } END { print n + 0 }'; }
# Answers through its path AND for a plain client: only a plain client checks the directory (tmux refuses one others can
# enter, "unsafe permissions"), and drift, restore.sh and the app are plain clients.
sg_answers() {
    [ -S "${'$'}SG_SOCK" ] && timeout 5 tmux -S "${'$'}SG_SOCK" list-sessions >/dev/null 2>&1 \
        && timeout 5 env -u TMUX tmux list-sessions >/dev/null 2>&1
}
# SG_PROBE = what is on the path, through -S: ok (answers), stale (a socket file nobody accepts on: what `tmux attach`
# leaves when it finds the path empty and starts a short-lived server of its own, measured on tmux 3.5a) or hung
# (accepts, then times out). A socket file gone between the caller's [ -S ] and this probe ("No such file or directory")
# is stale too: the heal re-checks everything under the lock.
sg_probe() {
    local o
    # only stderr is captured: the client hands its stdin/stdout to the server (SCM_RIGHTS), and a stopped server
    # would hold a captured stdout open, so the capture never ended (measured with SIGSTOP)
    if o=${'$'}(timeout -k 1 5 tmux -S "${'$'}SG_SOCK" list-sessions 2>&1 >/dev/null </dev/null); then SG_PROBE=ok; return 0; fi
    case "${'$'}o" in *"no server running"*|*"onnection refused"*|*"No such file or directory"*) SG_PROBE=stale;; *) SG_PROBE=hung;; esac
}
sg_stale() { [ -S "${'$'}SG_SOCK" ] && sg_probe && [ "${'$'}SG_PROBE" = stale ]; }
# 0 when the socket directory is a real directory of ours with mode 700 (made so when it was looser); else 1.
sg_dir_private() {
    local m
    { [ ! -L "${'$'}SG_SOCK_DIR" ] && [ -d "${'$'}SG_SOCK_DIR" ] && [ "${'$'}(stat -c %u "${'$'}SG_SOCK_DIR" 2>/dev/null)" = "${'$'}SG_UID" ]; } || return 1
    m=${'$'}(stat -c %a "${'$'}SG_SOCK_DIR" 2>/dev/null)
    [ "${'$'}m" = 700 ] && return 0
    { chmod 700 "${'$'}SG_SOCK_DIR" 2>/dev/null && [ "${'$'}(stat -c %a "${'$'}SG_SOCK_DIR" 2>/dev/null)" = 700 ]; } || return 1
    echo "[${'$'}(date -u +%FT%TZ)] sockguard(${'$'}SG_WHO): ${'$'}SG_SOCK_DIR had mode ${'$'}m — set to 700 (tmux clients refuse a socket directory others can enter)"
}
# Heal under the shared lock (drift, restore.sh's create branch and the app's tmux-sockheal.sh).
sg_heal() {
    local pid=${'$'}1
    (
        flock -w 2 8 || { sg_log lock "could not take ${'$'}SG_LOCK within 2 s (another heal or create runs) — not relaunching now"; exit 1; }
        # healed by someone else meanwhile: usable again, as far as the caller is concerned
        sg_answers && exit 2
        case "${'$'}pid" in ''|*[!0-9]*) sg_log nopid "a tmux server listens on ${'$'}SG_SOCK without its socket file and its pid is unknown — NOT relaunching"; exit 1;; esac
        if [ "${'$'}(cat "/proc/${'$'}pid/comm" 2>/dev/null)" != "tmux: server" ] || [ "${'$'}(stat -c %u "/proc/${'$'}pid" 2>/dev/null)" != "${'$'}SG_UID" ]; then
            sg_log notours "listener ${'$'}pid on ${'$'}SG_SOCK is not our tmux server — not signalling, NOT relaunching"; exit 1
        fi
        # SIGUSR1 cannot bind into a missing directory (the 2026-11-02 shape).
        [ -d "${'$'}SG_SOCK_DIR" ] || mkdir -p -m 700 "${'$'}SG_SOCK_DIR" 2>/dev/null
        if ! sg_dir_private; then
            sg_log baddir "${'$'}SG_SOCK_DIR is missing, a symlink, not ours or cannot be made private — not signalling, NOT relaunching"; exit 1
        fi
        # Re-checked right before the signal: still exactly this one server on the path (never signal one of two), and
        # the path is empty or a stale file (tmux unlinks it and binds anew). A socket that answers was healed meanwhile.
        [ "${'$'}(sg_listeners)" = "${'$'}pid" ] || { sg_log moved "the listeners on ${'$'}SG_SOCK changed while healing — not signalling, NOT relaunching"; exit 1; }
        if [ ! -S "${'$'}SG_SOCK" ] || sg_stale; then
            kill -USR1 "${'$'}pid" 2>/dev/null || { sg_log kill "SIGUSR1 to ${'$'}pid failed — NOT relaunching"; exit 1; }
        fi
        # At most 2 s in all: a deadline, not a number of tries (each try may itself wait).
        local end left
        end=${'$'}(( ${'$'}(date +%s%N) / 1000000 + 2000 ))
        while left=${'$'}(( end - ${'$'}(date +%s%N) / 1000000 )); [ "${'$'}left" -gt 0 ]; do
            [ -S "${'$'}SG_SOCK" ] && timeout "${'$'}((left / 1000)).${'$'}(printf %03d ${'$'}((left % 1000)))" tmux -S "${'$'}SG_SOCK" list-sessions >/dev/null 2>&1 && exit 2
            sleep 0.1
        done
        sg_log healfail "tmux server ${'$'}pid alive without its socket ${'$'}SG_SOCK, SIGUSR1 did not bring it back — NOT relaunching"
        exit 1
    ) 8>>"${'$'}SG_LOCK"
}
sg_guard() {
    SG_WHY=
    [ -e "${'$'}SG_OFF" ] && return 0
    command -v tmux >/dev/null 2>&1 || return 0
    local pids n rc
    pids=${'$'}(sg_listeners)
    n=${'$'}(sg_count "${'$'}pids")
    [ "${'$'}n" -eq 0 ] && return 0
    if [ "${'$'}n" -ge 2 ]; then
        SG_WHY=split
        sg_log split "SPLIT BRAIN: ${'$'}n tmux servers listen on ${'$'}SG_SOCK (pids ${'$'}(printf '%s' "${'$'}pids" | tr '\n' ' ')) — no signal, no relaunch, no new server; needs a human"
        return 1
    fi
    if [ -S "${'$'}SG_SOCK" ]; then
        # One wait for the answer (5 s), not sg_answers' and then sg_probe's: a hung server was refused after 10 s.
        sg_probe
        case "${'$'}SG_PROBE" in
            ok)
                timeout 5 env -u TMUX tmux list-sessions >/dev/null 2>&1 && return 0
                # it answers through its path but plain clients refuse the directory
                sg_dir_private && sg_answers && return 2
                SG_WHY=unsafe
                sg_log unsafe "${'$'}SG_SOCK answers only through -S: ${'$'}SG_SOCK_DIR is not a private directory of ours and cannot be made one — NOT relaunching"
                return 1;;
            hung)
                SG_WHY=hung
                sg_log hung "tmux server ${'$'}pids listens on ${'$'}SG_SOCK but does not answer — NOT relaunching"
                return 1;;
        esac
    fi
    # the socket file is gone, or a stale file nobody accepts on took its place
    sg_heal "${'$'}pids"; rc=${'$'}?
    [ "${'$'}rc" -eq 2 ] && echo "[${'$'}(date -u +%FT%TZ)] sockguard(${'$'}SG_WHO): tmux server ${'$'}pids was alive without its socket — ${'$'}SG_SOCK recreated (SIGUSR1), nothing relaunched"
    [ "${'$'}rc" -eq 1 ] && SG_WHY=heal
    return "${'$'}rc"
}
# ---- end of tmux socket guard ----"""

        /**
         * D6-S (kritika D2 N14): the one check of a line of `~/.claude-remote/prefixes`, pasted VERBATIM into drift.sh
         * (its reader) and into the installer's prefix write ([prefixWrite]). Must be declared before
         * [INSTALL_RESTORE_COMMAND]: companion properties initialise in textual order.
         */
        private val PREFIX_CHECK_FUNC = """
# A prefix line is claude-<name>- where <name> holds only ASCII letters, digits, _ and - and does not start with -:
# never empty, never claude- alone, no space, CR, '.', ':' or glob character. The class is spelled out (no ranges), so
# no locale can widen it. A line that fails is skipped without a word.
cr_prefix_ok() {
    case "${'$'}1" in claude-?*-) ;; *) return 1;; esac
    case "${'$'}{1#claude-}" in
        [!abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_]*) return 1;;
        *[!abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-]*) return 1;;
    esac
    return 0
}"""

        /**
         * E4b (v20): the folder of a manifest entry as restore.sh starts it, pasted VERBATIM into restore.sh (its jq
         * branch) and drift.sh (which leaves an entry whose folder is missing out of self-heal), so both read a folder
         * the same way. Must be declared before [INSTALL_RESTORE_COMMAND]: companion properties initialise in textual
         * order.
         */
        private val FOLDER_EXPAND_FUNC = """
# ---- the folder of a manifest entry (v20, E4b) ----
# As restore.sh starts it: ~ is ${'$'}HOME, a relative path lies under ${'$'}HOME. An empty folder or jq's null (no folder
# at all) is no folder: rc 1, nothing printed (v19's restore.sh started an empty folder in ${'$'}HOME/ and looked for
# a folder "null" as ${'$'}HOME/null).
cr_folder() {
    local f
    case "${'$'}1" in ''|null) return 1;; esac
    f="${'$'}{1/#\~/${'$'}HOME}"
    case "${'$'}f" in /*) ;; *) f="${'$'}HOME/${'$'}f";; esac
    printf '%s' "${'$'}f"
}
# ---- end of the folder of a manifest entry ----"""

        /** The marker version [INSTALL_RESTORE_COMMAND] carries: `MARKER` is made of it; `DriftPrefixesTest` holds the compat lines to it. */
        internal const val MARKER_VERSION = 20

        /**
         * The installer's rollback copy of [name] (in `$`[variable]) as of marker v[ver]: kept once when the script the
         * installer replaces is v[ver], so moving it back is the way back to v[ver] (by hand, only with Lukáš's consent
         * that day; any client of a newer marker rewrites it on its next launch, a lasting way back is a hotfix). A
         * script of a newer marker names v[ver] in its compat line too, so the copy must carry no newer marker (a
         * second installer may have replaced the script in between), and `ln` publishes it only where no copy is yet
         * (K2): never one over another. Its temp file is in the installer's `trap`.
         */
        internal fun rollbackCopy(variable: String, name: String, ver: Int): String {
            val d = "${'$'}"
            val newer = (ver + 1..MARKER_VERSION).joinToString("|")
            return """
            # ---- rollback copy of $name as of v$ver (D6-S, v20) ----
            if grep -qE 'claude-remote-restore-v$ver( |$d)' "$d$variable" 2>/dev/null && [ ! -e "$d$variable.v$ver" ]; then
                { cp -p "$d$variable" "$d$variable.v$ver.tmp.$d$d" && ! grep -qE 'claude-remote-restore-v($newer)( |$d)' "$d$variable.v$ver.tmp.$d$d" \
                    && ln "$d$variable.v$ver.tmp.$d$d" "$d$variable.v$ver" \
                    && echo "[$d(date -u +%FT%TZ)] install: the v$ver $name kept as $name.v$ver (rollback)" >> "${d}HOME/.claude-remote/install.log"; } 2>/dev/null || :
                rm -f "$d$variable.v$ver.tmp.$d$d"
            fi
            # ---- end of the rollback copy of $name as of v$ver ----""".removePrefix("\n")
        }

        /**
         * Idempotent installer for the user-level systemd service that restores
         * tmux + claude sessions after a server reboot. Writes:
         *   ~/.claude-remote/restore.sh
         *   ~/.config/systemd/user/claude-remote-restore.service
         * then enables linger + the unit. Safe to call on every connect — checks
         * for a marker line in the script before rewriting.
         *
         * Requires: bash, jq, tmux, claude on PATH at boot. The service uses an
         * explicit PATH so it works under empty systemd-user env.
         */
        internal val INSTALL_RESTORE_COMMAND = """
        set -e
        mkdir -p "${'$'}HOME/.claude-remote" "${'$'}HOME/.config/systemd/user"
        SCRIPT="${'$'}HOME/.claude-remote/restore.sh"
        DRIFT="${'$'}HOME/.claude-remote/drift.sh"
        SOCKHEAL="${'$'}HOME/.claude-remote/tmux-sockheal.sh"
        UNIT="${'$'}HOME/.config/systemd/user/claude-remote-restore.service"
        DUNIT="${'$'}HOME/.config/systemd/user/claude-remote-drift.service"
        DTIMER="${'$'}HOME/.config/systemd/user/claude-remote-drift.timer"
        LOCK="${'$'}HOME/.claude-remote/sessions.lock"
        MARKER="claude-remote-restore-v$MARKER_VERSION"
        # An install cut short (set -e, a dropped SSH session) must not leave a temp script behind.
        trap 'rm -f "${'$'}SCRIPT.tmp.${'$'}${'$'}" "${'$'}DRIFT.tmp.${'$'}${'$'}" "${'$'}SOCKHEAL.tmp.${'$'}${'$'}" "${'$'}SCRIPT.v18.tmp.${'$'}${'$'}" "${'$'}DRIFT.v18.tmp.${'$'}${'$'}" "${'$'}SCRIPT.v19.tmp.${'$'}${'$'}" "${'$'}DRIFT.v19.tmp.${'$'}${'$'}"' EXIT
        touch "${'$'}LOCK"
        echo "[${'$'}(date -u +%FT%TZ)] install: invoked by client" >> "${'$'}HOME/.claude-remote/install.log"
        # v20 (H10): each script missing the marker is written to its temp file first; the three renames follow back to
        # back once all three are written, and only then the output: an install cut short while writing replaces no
        # script, and a mix of versions is left only by one killed within the window of the three renames.
        # An install killed outright (SIGKILL: no trap) leaves its temp files: ours, older than an hour, go here.
        find "${'$'}HOME/.claude-remote" -maxdepth 1 -type f \( -name 'restore.sh.tmp.[0-9]*' -o -name 'drift.sh.tmp.[0-9]*' \
            -o -name 'tmux-sockheal.sh.tmp.[0-9]*' -o -name 'restore.sh.v1[89].tmp.[0-9]*' -o -name 'drift.sh.v1[89].tmp.[0-9]*' \) \
            -mmin +60 -delete 2>/dev/null || true
        R_NEW=0; D_NEW=0; S_NEW=0
        if ! grep -q "${'$'}MARKER" "${'$'}SCRIPT" 2>/dev/null; then
            echo "[${'$'}(date -u +%FT%TZ)] install: restore.sh missing ${'$'}MARKER — rewriting; prior marker line: ${'$'}(grep -m1 'claude-remote-restore-v' "${'$'}SCRIPT" 2>/dev/null || echo '<none/new file>')" >> "${'$'}HOME/.claude-remote/install.log"
            # Atomic: a fresh inode renamed into place. `cat >` truncated the file a running bash may be reading
            # block by block, which could execute a mix of the old and the new script.
            cat > "${'$'}SCRIPT.tmp.${'$'}${'$'}" <<'RESTORE_EOF'
#!/usr/bin/env bash
# marker-compat (do NOT remove): claude-remote-restore-v6 claude-remote-restore-v7 claude-remote-restore-v8 claude-remote-restore-v9 claude-remote-restore-v10 claude-remote-restore-v11 claude-remote-restore-v12 claude-remote-restore-v13 claude-remote-restore-v14 claude-remote-restore-v15 claude-remote-restore-v16 claude-remote-restore-v17 claude-remote-restore-v18 claude-remote-restore-v19 claude-remote-restore-v20
# Makes any installed client's `grep -q ${'$'}MARKER` find its marker so it takes the
# PRESENT no-op path and does NOT rewrite this file (reverting the fixes) nor run
# the daemon-reload/enable reinstall that correlates with the tmux-server death.
# claude-remote-restore-v16 — recreates tmux+claude sessions from sessions.json (snapshot under flock)
set -u
# Any tmux server started from here (incl. auto-start by `tmux new-session`,
# which does NOT inherit the anchor scope's LimitCORE) must be able to dump a
# core: the deaths are tmux SIGSEGV and a backtrace is the only way past theory.
# core_pattern is read-only in this LXC, so the core lands as ./core in cwd.
ulimit -c unlimited 2>/dev/null || true
cd "${'$'}HOME/.claude-remote/cores" 2>/dev/null || true
LOG="${'$'}HOME/.claude-remote/restore.log"
exec >> "${'$'}LOG" 2>&1
echo "----- ${'$'}(date -u +%FT%TZ) restore.sh start (pid=${'$'}${'$'}) -----"
# Anchor the tmux server under the user slice: if none is running yet, create
# it (with a keepalive __anchor__ session) via systemd-run --scope so the
# forked server lives in user-1000.slice and survives SSH-session teardown
# (the mid-life mass-death root cause). Sessions recreated below then attach to
# that anchored server. If a server already exists this is a no-op.
# Best-effort (`|| true`): an existing server or a failed systemd-run must never
# break the launch.
export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/run/user/${'$'}(id -u)}"
$SOCK_GUARD_FUNCS
SG_WHO=restore
mkdir -p "${'$'}SG_DIR" 2>/dev/null
# A server that is alive on the default socket path but unusable (socket gone and not healable, hung, two
# servers) must never get a second server next to it: that is the 2026-11-02 duplicate-51-sessions incident.
if [ ! -e "${'$'}SG_OFF" ]; then
    SG_RC=0; sg_guard || SG_RC=${'$'}?
    if [ "${'$'}SG_RC" -eq 1 ]; then
        echo "a tmux server is alive on ${'$'}SG_SOCK but unusable — NOT creating a second server, skipping restore (drift retries next tick)"
        exit 0
    fi
fi
if ! tmux list-sessions >/dev/null 2>&1; then
    # Create the server in its OWN transient scope, never in the caller's cgroup.
    # NOTE: no --unit=<fixed name> — a leftover unit of that name makes systemd-run
    # fail silently, and the trailing `|| true` then let the CALLER create the
    # server instead. When the caller was claude-remote-drift.service (oneshot,
    # KillMode=control-group, no RemainAfterExit) the whole fleet died with the
    # tick. Verified: no claude-tmux-server.scope ever existed on this box.
    if [ -e "${'$'}SG_OFF" ]; then
        systemd-run --user --scope --quiet \
            tmux new-session -d -x 200 -y 50 -s __anchor__ sleep infinity >/dev/null 2>&1 || true
    else
        # Under the shared lock, and only while still nothing listens on the path (a concurrent heal or
        # creator wins). `8>&-`: the daemonizing server must not inherit the lock fd and hold it forever.
        (
            flock -w 2 8 || { echo "could not take ${'$'}SG_LOCK within 2 s — not creating a tmux server now"; exit 0; }
            [ "${'$'}(sg_count "${'$'}(sg_listeners)")" -eq 0 ] || { echo "a tmux server appeared on ${'$'}SG_SOCK meanwhile — not creating another"; exit 0; }
            systemd-run --user --scope --quiet \
                tmux new-session -d -x 200 -y 50 -s __anchor__ sleep infinity >/dev/null 2>&1 8>&- || true
        ) 8>>"${'$'}SG_LOCK"
    fi
    for _ in ${'$'}(seq 1 25); do tmux list-sessions >/dev/null 2>&1 && break; sleep 0.2; done
    if ! tmux list-sessions >/dev/null 2>&1; then
        echo "no tmux server and could not create one in a transient scope — skipping restore (drift retries next tick)"
        exit 0
    fi
fi
# THE key fix: tmux default exit-empty=on makes the server exit the instant its
# session count hits 0 — which the app's reconnect/close churn (kill-session +
# recreate) transiently does, killing the whole server ("server exited
# unexpectedly") and every session. Turn it off so the server is immortal;
# sessions are killed/recreated within the SAME persistent server.
tmux set-option -g exit-empty off 2>/dev/null || true
# CRASH FIX (core dump 2026-07-27 12:38, tmux 3.5a):
#   SIGSEGV in clients_calculate_size() <- default_window_size() <- spawn_window()
#   <- cmd_new_session_exec(), i.e. tmux crashed WHILE CREATING A SESSION because
#   it was sizing the new window from the ATTACHED CLIENTS. A server that respawns
#   after a crash starts with the DEFAULT `window-size latest`, so every
#   new-session walked the (partly stale/zombie) client list — and self-heal
#   creating ~22 sessions in a row turned that into a reliable crash loop: create
#   sessions -> server SEGVs -> all sessions die -> next tick tries again.
#   Pinning manual sizing + an explicit default-size makes default_window_size()
#   take its manual branch and never consult clients at all.
tmux set-option -g default-size 200x50 2>/dev/null || true
LOCK="${'$'}HOME/.claude-remote/sessions.lock"
# Source of truth = server-owned sessions.restore.json. The client can (and
# buggily does) blank sessions.json on reconnect; it never touches
# sessions.restore.json, so restore/self-heal survive that. Prefer it, then
# sessions.json, then the rolling backup — first non-empty wins.
RESTORE_SRC="${'$'}HOME/.claude-remote/sessions.restore.json"
SESSIONS_FILE="${'$'}HOME/.claude-remote/sessions.json"
touch "${'$'}LOCK"
SRC=""
if command -v jq >/dev/null 2>&1; then
    # Precedence: server-owned SoT, then ITS OWN rolling backup (drift maintains
    # ${'$'}RESTORE_SRC.bak — the old list read ${'$'}SESSIONS_FILE.bak, which NOTHING
    # writes, so the maintained backup was unreachable), then the app-facing
    # mirror, then its stale backup, then the high-water snapshot as a last
    # resort (largest manifest ever seen — turns a wipe into a 5s recovery).
    for f in "${'$'}RESTORE_SRC" "${'$'}RESTORE_SRC.bak" "${'$'}SESSIONS_FILE" "${'$'}SESSIONS_FILE.bak" "${'$'}HOME/.claude-remote/manifests/highwater.json"; do
        [ -f "${'$'}f" ] || continue
        n=${'$'}(flock -s "${'$'}LOCK" cat "${'$'}f" | jq 'length' 2>/dev/null || echo 0)
        [ "${'$'}{n:-0}" -gt 0 ] && { SRC="${'$'}f"; break; }
    done
fi
[ -n "${'$'}SRC" ] || SRC="${'$'}SESSIONS_FILE"
if [ ! -f "${'$'}SRC" ]; then
    echo "no restore manifest yet — nothing to restore"
    exit 0
fi
echo "restore source: ${'$'}SRC"
SNAP=${'$'}(flock -s "${'$'}LOCK" cat "${'$'}SRC")
command -v tmux >/dev/null 2>&1 || { echo "tmux not in PATH"; exit 1; }
command -v claude >/dev/null 2>&1 || { echo "claude not in PATH"; exit 1; }
HAVE_JQ=0
command -v jq >/dev/null 2>&1 && HAVE_JQ=1
parse_field() {
    local key="${'$'}1" line="${'$'}2"
    echo "${'$'}line" | sed -n "s/.*\"${'$'}key\":[[:space:]]*\"\([^\"]*\)\".*/\1/p"
}
# A tmux name the user CLOSED must never be restored, whichever source file
# ${'$'}SRC came from. This is load-bearing for the fallbacks above: .bak and
# manifests/highwater.json (the largest manifest ever seen) both predate the
# close, so without this check a reboot resurrects every session ever closed —
# highwater in particular never shrinks and so never forgets.
# Two stores, same meaning: `forgotten` holds transient tombstones the drift
# daemon consumes within a tick, `forgotten.d/<name>` durable markers a real
# user close leaves behind (mtime = close time, honoured for TOMBSTONE_WINDOW).
FORGOTTEN="${'$'}HOME/.claude-remote/forgotten"
FORGOTTEN_D="${'$'}HOME/.claude-remote/forgotten.d"
TOMBSTONE_WINDOW=14
is_tombstoned() {
    [ -f "${'$'}FORGOTTEN" ] && grep -qxF "${'$'}1" "${'$'}FORGOTTEN" && return 0
    [ -f "${'$'}FORGOTTEN_D/${'$'}1" ] \
        && [ -n "${'$'}(find "${'$'}FORGOTTEN_D/${'$'}1" -maxdepth 0 -mtime -${'$'}TOMBSTONE_WINDOW 2>/dev/null)" ] \
        && return 0
    return 1
}
$FOLDER_EXPAND_FUNC
# ---- one shell word (v20, A-ED2) ----
# ${'$'}1 as one word of the pane's command: the value itself when it holds only characters no shell reads specially (every
# id, model, flag and account slug the app writes, so the command is v19's byte for byte), else single-quoted. A value
# from a client's file (the manifest's claudeSessionId, accountSlug) never becomes code. The class is spelled out.
cr_q() {
    case "${'$'}1" in
        ''|*[!abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@%+=:,./_-]*) printf "'%s'" "${'$'}{1//"'"/"'\\''"}";;
        *) printf '%s' "${'$'}1";;
    esac
}
# CLAUDE_CONFIG_DIR=… for account ${'$'}1, as v19 wrote it when the slug is one plain word
cr_acct() {
    local q
    q=${'$'}(cr_q "${'$'}1")
    if [ "${'$'}q" = "${'$'}1" ]; then printf 'CLAUDE_CONFIG_DIR="%s"' "${'$'}HOME/.claude-remote/accounts/${'$'}1"
    else printf 'CLAUDE_CONFIG_DIR="%s"%s' "${'$'}HOME/.claude-remote/accounts/" "${'$'}q"
    fi
}
# ---- end of one shell word ----
# ---- a name tmux holds as written (v20, A-ED1) ----
# tmux makes `.` and `:` of a session name into `_` and escapes a control character: such a name of the manifest would
# start a session of another name (a closed one, or a second row for one session), so it is skipped.
cr_name_ok() { case "${'$'}1" in *[.:]*|*[[:cntrl:]]*) return 1;; esac; return 0; }
# ---- end of a name tmux holds as written ----
# ---- an account slug that is no path (v20, T-ED13) ----
# A slug is a directory under accounts/: one with `/`, or `.` / `..`, would put CLAUDE_CONFIG_DIR somewhere else. Such
# an entry starts without an account (logged) rather than not at all: a session that never starts is "missing" every tick.
cr_acct_ok() { case "${'$'}1" in .|..|*/*) return 1;; esac; return 0; }
# ---- end of an account slug that is no path ----
if [ "${'$'}HAVE_JQ" = "1" ]; then
    COUNT=${'$'}(echo "${'$'}SNAP" | jq 'length')
    for i in ${'$'}(seq 0 ${'$'}((COUNT-1))); do
        TMUX_NAME=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].tmuxSessionName")
        FOLDER=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].folder")
        MODE=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].mode")
        MODEL=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].model")
        UUID=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].claudeSessionId // empty")
        # Which Claude LOGIN this session belongs to. Empty (or "default") means
        # the pre-existing ~/.claude login, which MUST run with CLAUDE_CONFIG_DIR
        # unset — see the ENVPFX note below.
        ACCT=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].accountSlug // empty")
        cr_acct_ok "${'$'}ACCT" || { echo "account ${'$'}(cr_q "${'$'}ACCT") of ${'$'}TMUX_NAME is a path — starting it without an account"; ACCT=""; }
        cr_name_ok "${'$'}TMUX_NAME" || { echo "skip ${'$'}TMUX_NAME — tmux cannot hold this name as written"; continue; }
        is_tombstoned "${'$'}TMUX_NAME" && { echo "skip ${'$'}TMUX_NAME — closed by the user (tombstoned)"; continue; }
        tmux has-session -t="${'$'}TMUX_NAME" 2>/dev/null && continue
        # ---- the folder (v20, E4b) ----
        FOLDER_EXP=${'$'}(cr_folder "${'$'}FOLDER") && [ -d "${'$'}FOLDER_EXP" ] \
            || { echo "skip ${'$'}TMUX_NAME — folder ${'$'}{FOLDER_EXP:-<none>} missing"; continue; }
        # ---- end of the folder ----
        case "${'$'}MODEL" in
            LOCAL|LOCAL_ORNITH|LOCAL_QWEN) ARGS=("claude-local");;
            *) ARGS=("claude");;
        esac
        case "${'$'}MODEL" in
            OPUS) ARGS+=(--model opus);;
            FABLE) ARGS+=(--model fable);;
            SONNET) ARGS+=(--model sonnet);;
            HAIKU) ARGS+=(--model haiku);;
        esac
        case "${'$'}MODE" in
            YOLO) ARGS+=(--dangerously-skip-permissions);;
            AUTO) ARGS+=(--permission-mode auto --allow-dangerously-skip-permissions);;
            AUTO_ACCEPT) ARGS+=(--permission-mode acceptEdits --allow-dangerously-skip-permissions);;
            *) ARGS+=(--allow-dangerously-skip-permissions);;
        esac
        # Resume only if a transcript actually exists for this UUID — claude
        # creates the jsonl lazily (first user/assistant turn), so a session
        # that was launched but never used has nothing to resume. Falling back
        # to fresh `--session-id` keeps the UUID stable for next time.
        if [ -n "${'$'}UUID" ]; then
            # Claude Code encodes '/', '_' AND '.' as '-', so the old '/'-only
            # sed missed every folder containing _ or . (e.g. ~/vscode_android):
            # the transcript existed but restore logged "no transcript" and
            # launched FRESH, orphaning the conversation on every reboot.
            ENC=${'$'}(echo "${'$'}FOLDER_EXP" | sed 's|[/_.]|-|g')
            JSONL="${'$'}HOME/.claude/projects/${'$'}ENC/${'$'}UUID.jsonl"
            # UUIDs are globally unique: if the encoding guess still misses (it
            # has changed before), find the transcript by UUID anywhere.
            [ -f "${'$'}JSONL" ] || JSONL=${'$'}(ls "${'$'}HOME"/.claude/projects/*/"${'$'}UUID".jsonl 2>/dev/null | head -1)
            # An account whose projects/ is a real directory, not the usual
            # symlink to the shared one, keeps its transcripts there.
            if [ ! -f "${'$'}JSONL" ] && [ -n "${'$'}{ACCT:-}" ] && [ "${'$'}{ACCT:-}" != "default" ]; then
                JSONL=${'$'}(ls "${'$'}HOME/.claude-remote/accounts/${'$'}ACCT/projects/"*/"${'$'}UUID".jsonl 2>/dev/null | head -1)
            fi
            if [ -n "${'$'}JSONL" ] && [ -f "${'$'}JSONL" ]; then
                ARGS+=(--resume "${'$'}UUID")
            else
                echo "no transcript at ${'$'}JSONL — launching fresh with --session-id ${'$'}UUID"
                ARGS+=(--session-id "${'$'}UUID")
            fi
        fi
        # ---- the command, word by word (v20, A-ED2) ----
        CMD=""
        for a in "${'$'}{ARGS[@]}"; do CMD="${'$'}CMD${'$'}{CMD:+ }${'$'}(cr_q "${'$'}a")"; done
        # ---- end of the command ----
        # ACCOUNT. This script builds the claude command a SECOND time, entirely
        # independently of the app's ClaudeConfig — so the account has to be
        # honoured HERE too or a restored/self-healed session silently reverts to
        # the default login (drift runs server-side with no app attached, so
        # nothing would surface the error).
        # An EMPTY or "default" slug must produce a BARE `claude` with NO env var:
        # CLAUDE_CONFIG_DIR=${'$'}HOME/.claude is NOT the same as leaving it unset —
        # set, claude reads ~/.claude/.claude.json, which doesn't exist, gets
        # created empty, and the session loses its project trust map + MCP config.
        if [ -n "${'$'}{ACCT:-}" ] && [ "${'$'}{ACCT:-}" != "default" ]; then
            CMD="${'$'}(cr_acct "${'$'}ACCT") ${'$'}CMD"
        fi
        # `exec bash -l` keepalive: when claude exits (crash, usage limit, OOM,
        # /exit, network blip) the pane drops to a login shell instead of the
        # whole tmux session vanishing — matches app-created sessions, which
        # run claude via `send-keys` into a shell. Without this, a restored
        # session is fragile: it disappears the moment claude stops.
        if tmux new-session -d -x 200 -y 50 -s "${'$'}TMUX_NAME" -c "${'$'}FOLDER_EXP" \
            "tmux set-option -g mouse on; tmux set-option -g history-limit 100000; ${'$'}CMD; exec bash -l"; then
            echo "Restored ${'$'}TMUX_NAME (${'$'}FOLDER_EXP) [uuid=${'$'}UUID]"
        else
            echo "FAILED to restore ${'$'}TMUX_NAME (${'$'}FOLDER_EXP) — tmux exit ${'$'}?"
        fi
    done
else
    echo "jq not installed — falling back to line parser"
    while IFS= read -r line; do
        case "${'$'}line" in
            *tmuxSessionName*) TMUX_NAME=${'$'}(parse_field tmuxSessionName "${'$'}line");;
            *\"folder\"*)      FOLDER=${'$'}(parse_field folder "${'$'}line");;
            *\"mode\"*)        MODE=${'$'}(parse_field mode "${'$'}line");;
            *\"model\"*)       MODEL=${'$'}(parse_field model "${'$'}line");;
            *claudeSessionId*) UUID=${'$'}(parse_field claudeSessionId "${'$'}line");;
            *accountSlug*)     ACCT=${'$'}(parse_field accountSlug "${'$'}line");;
            *\}*)
                cr_acct_ok "${'$'}{ACCT:-}" || { echo "account ${'$'}(cr_q "${'$'}ACCT") of ${'$'}{TMUX_NAME:-} is a path — starting it without an account"; ACCT=""; }
                if [ -n "${'$'}{TMUX_NAME:-}" ] && [ -n "${'$'}{FOLDER:-}" ] && cr_name_ok "${'$'}TMUX_NAME" \
                   && ! is_tombstoned "${'$'}TMUX_NAME"; then
                    if ! tmux has-session -t="${'$'}TMUX_NAME" 2>/dev/null; then
                        FOLDER_EXP=${'$'}(cr_folder "${'$'}FOLDER") && [ -d "${'$'}FOLDER_EXP" ] && {
                            CMD="claude --allow-dangerously-skip-permissions"
                            # Resume only a conversation that exists: --resume on a
                            # session nobody spoke to exits "No conversation found".
                            if [ -n "${'$'}{UUID:-}" ]; then
                                if ls "${'$'}HOME"/.claude/projects/*/"${'$'}UUID".jsonl >/dev/null 2>&1 \
                                   || { [ -n "${'$'}{ACCT:-}" ] && ls "${'$'}HOME/.claude-remote/accounts/${'$'}ACCT/projects/"*/"${'$'}UUID".jsonl >/dev/null 2>&1; }; then
                                    CMD="${'$'}CMD --resume ${'$'}(cr_q "${'$'}UUID")"
                                else
                                    CMD="${'$'}CMD --session-id ${'$'}(cr_q "${'$'}UUID")"
                                fi
                            fi
                            # Same account rule as the jq branch above — this
                            # SECOND command builder must honour it too, or a
                            # jq-less box quietly restores every session onto the
                            # default login. Empty/"default" ⇒ NO env var at all.
                            if [ -n "${'$'}{ACCT:-}" ] && [ "${'$'}{ACCT:-}" != "default" ]; then
                                CMD="${'$'}(cr_acct "${'$'}ACCT") ${'$'}CMD"
                            fi
                            tmux new-session -d -x 200 -y 50 -s "${'$'}TMUX_NAME" -c "${'$'}FOLDER_EXP" \
                                "tmux set-option -g mouse on; tmux set-option -g history-limit 100000; ${'$'}CMD; exec bash -l"
                            echo "Restored ${'$'}TMUX_NAME"
                        }
                    fi
                fi
                TMUX_NAME=""; FOLDER=""; MODE=""; MODEL=""; UUID=""; ACCT=""
                ;;
        esac
    done < "${'$'}SESSIONS_FILE"
fi
RESTORE_EOF
            chmod +x "${'$'}SCRIPT.tmp.${'$'}${'$'}"
${rollbackCopy("SCRIPT", "restore.sh", 18)}
${rollbackCopy("SCRIPT", "restore.sh", 19)}
            R_NEW=1
        fi
        if ! grep -q "${'$'}MARKER" "${'$'}DRIFT" 2>/dev/null; then
            echo "[${'$'}(date -u +%FT%TZ)] install: drift.sh missing ${'$'}MARKER — rewriting; prior marker line: ${'$'}(grep -m1 'claude-remote-restore-v' "${'$'}DRIFT" 2>/dev/null || echo '<none/new file>')" >> "${'$'}HOME/.claude-remote/install.log"
            cat > "${'$'}DRIFT.tmp.${'$'}${'$'}" <<'DRIFT_EOF'
#!/usr/bin/env bash
# marker-compat (do NOT remove): claude-remote-restore-v6 claude-remote-restore-v7 claude-remote-restore-v8 claude-remote-restore-v9 claude-remote-restore-v10 claude-remote-restore-v11 claude-remote-restore-v12 claude-remote-restore-v13 claude-remote-restore-v14 claude-remote-restore-v15 claude-remote-restore-v16 claude-remote-restore-v17 claude-remote-restore-v18 claude-remote-restore-v19 claude-remote-restore-v20
# Makes any installed client's `grep -q ${'$'}MARKER` find its marker so it takes the
# PRESENT no-op path and does NOT rewrite this file (reverting the fixes) nor run
# the daemon-reload/enable reinstall that correlates with the tmux-server death.
# claude-remote-restore-v16 — drift daemon: reconciles sessions.json to mirror
# the LIVE tmux sessions of this box (claude-server-* and the prefixes file) every minute. Self-healing: re-adds
# live sessions a misbehaving/old client truncated away, refreshes
# claudeSessionId from claude's per-pid state files, preserves client-set
# metadata (serverId/alias) for known sessions, and drops entries whose tmux
# session is gone. A single buggy `cat > sessions.json` overwrite from any
# client is non-fatal — within 60s the snapshot is rebuilt from ground truth,
# so the next reboot's restore service still rebuilds every live session.
set -u
# ---- the brake (v20, H6) ----
# ~/.claude-remote/drift.off stops drift before it does anything at all: no tmux call, no manifest, no mirror, no
# relaunch, no log. Remove the file to let the next tick run. (drift-heal.off is no brake: it only turns the socket
# guard off, and self-heal then relaunches without it.) The other brake: systemctl --user stop claude-remote-drift.timer.
[ -e "${'$'}HOME/.claude-remote/drift.off" ] && exit 0
# ---- end of the brake ----
# Any tmux server started from here (incl. auto-start by `tmux new-session`,
# which does NOT inherit the anchor scope's LimitCORE) must be able to dump a
# core: the deaths are tmux SIGSEGV and a backtrace is the only way past theory.
# core_pattern is read-only in this LXC, so the core lands as ./core in cwd.
ulimit -c unlimited 2>/dev/null || true
cd "${'$'}HOME/.claude-remote/cores" 2>/dev/null || true
LOG="${'$'}HOME/.claude-remote/drift.log"
# Buffer this tick's output; flush to the real log ONLY if something actually
# happened (a reconcile/refuse/self-heal line, an early-exit notice, or a
# stderr error made the buffer non-empty). The common no-op tick writes
# NOTHING — this kills the ~60MB/day of per-tick "drift start"/"LIVE=" spam.
# The header (start banner + live-session names) is held in HDR and prepended
# only when the buffer is flushed, so a change tick still logs full context.
TICKLOG=${'$'}(mktemp "${'$'}HOME/.claude-remote/.drift.tick.XXXXXX" 2>/dev/null || mktemp 2>/dev/null || echo "${'$'}LOG")
flush_tick() {
    if [ "${'$'}TICKLOG" != "${'$'}LOG" ]; then
        [ -s "${'$'}TICKLOG" ] && { printf '%s\n' "${'$'}HDR"; cat "${'$'}TICKLOG"; } >> "${'$'}LOG"
        rm -f "${'$'}TICKLOG"
    fi
}
trap flush_tick EXIT
exec >> "${'$'}TICKLOG" 2>&1
HDR="----- ${'$'}(date -u +%FT%TZ) drift start -----"
SF="${'$'}HOME/.claude-remote/sessions.restore.json"   # server-owned source of truth (client never writes it)
SF_APP="${'$'}HOME/.claude-remote/sessions.json"        # mirror the app/client reads (drift keeps it in sync)
LOCK="${'$'}HOME/.claude-remote/sessions.lock"
FORGOTTEN="${'$'}HOME/.claude-remote/forgotten"          # tombstones: tmux names a client explicitly closed
FORGOTTEN_D="${'$'}HOME/.claude-remote/forgotten.d"      # DURABLE tombstones: one file per USER close (mtime = close time)
TOMBSTONE_WINDOW=14                                 # days a durable marker stays authoritative
command -v tmux >/dev/null 2>&1 || { echo "no tmux"; exit 0; }
command -v jq >/dev/null 2>&1 || { echo "no jq"; exit 0; }
touch "${'$'}LOCK"

$SOCK_GUARD_FUNCS
SG_WHO=drift
# SOCKET GUARD, every tick and before anything below can decide to relaunch (not only at LIVE=[]: at 23:38:14 on
# 2026-11-02 LIVE held part of a SECOND server's sessions and drift relaunched 24 more duplicates).
# Healed (rc 2) or refused (rc 1): this tick relaunches nothing and writes no manifest.
# Every tmux call of a tick gets a time limit, with the guard or without it (v20: it used to go with drift-heal.off).
# It ends the client; a pipe or $(…) reading from it still waits while a stopped server holds the client's stdout, so
# only the guard (on by default) keeps a tick short over a hung server.
command -v timeout >/dev/null 2>&1 && tmux() { timeout -k 2 10 tmux "${'$'}@"; }
if [ ! -e "${'$'}SG_OFF" ]; then
    SG_RC=0; sg_guard || SG_RC=${'$'}?
    [ "${'$'}SG_RC" -eq 0 ] || exit 0
fi

# Enforce exit-empty=off every tick so ANY tmux server (however created) never
# exits when sessions momentarily hit 0 (app churn/close) — server-side, no
# dependency on the client build. This is the root fix for the mass churn
# ("server exited unexpectedly").
# ONLY against an EXISTING server: `tmux set-option -g` on a dead server STARTS
# one — and this script runs inside claude-remote-drift.service, which is
# Type=oneshot, RemainAfterExit=no, KillMode=control-group. So the server (plus
# every session restored into it) was killed the moment the tick finished, and
# the next tick recreated and re-killed it: a self-inflicted fleet flap loop
# (watchdog: server born in .../claude-remote-drift.service, dead 12s later).
if tmux list-sessions >/dev/null 2>&1; then
    tmux set-option -g exit-empty off 2>/dev/null || true
    # THE CRASH FIX, enforced on whatever server is live (core dump 2026-07-27
    # 12:38, tmux 3.5a): SIGSEGV in clients_calculate_size() <-
    # default_window_size() <- spawn_window() <- cmd_new_session_exec(). tmux
    # crashed WHILE CREATING A SESSION because it sized the new window from the
    # ATTACHED CLIENTS and walked a stale one. A server that respawns after a
    # crash starts with the DEFAULT window-size=latest, so EVERY new-session — the
    # app's included, which passes no -x/-y — took that path. Pinning manual
    # sizing + an explicit default-size makes default_window_size() use its manual
    # branch and never consult clients, for every creator.
    tmux set-option -g default-size 200x50 2>/dev/null || true
fi
# ---- prefixes of this box (D6-S) ----
# The sessions drift adopts, counts and relaunches start with claude-server- (always, with or without the file) or
# with a line of ~/.claude-remote/prefixes, which the installer writes for a box with another name. A line that fails
# cr_prefix_ok is skipped: an empty or half-written line would match every session (__anchor__ and foreign ones
# too), and drift would adopt, kill and relaunch them. Read silently: no file is the normal case, and a tick must
# not grow drift.log for it.
$PREFIX_CHECK_FUNC
PFX="${TmuxNameParser.DEFAULT_PREFIX}"
if [ -f "${'$'}HOME/.claude-remote/prefixes" ]; then
    while IFS= read -r _p || [ -n "${'$'}_p" ]; do
        cr_prefix_ok "${'$'}_p" && PFX="${'$'}PFX ${'$'}_p"
    done 2>/dev/null < "${'$'}HOME/.claude-remote/prefixes"
fi
# 0 when ${'$'}1 starts with one of PFX, compared as fixed strings. One pass, the first match ends it: overlapping
# prefixes (claude-server- and claude-server-lan-) give one row, not two.
is_ours() {
    local q hit=1
    for q in ${'$'}PFX; do case "${'$'}1" in "${'$'}q"*) hit=0; break;; esac; done
    return ${'$'}hit
}
# ---- end of prefixes ----
# Drop the internal keepalive __anchor__ if it lingers: with exit-empty=off the
# server no longer needs it, and leaving it visible makes the app treat it as a
# real session and churn it (kill/relaunch). Safe — exit-empty=off keeps the
# server alive at 0 sessions.
if tmux -u list-sessions -F '#{session_name}' 2>/dev/null | { while IFS= read -r s; do case "${'$'}s" in "${TmuxNameParser.DEFAULT_PREFIX}"*) exit 0;; esac; done; exit 1; }; then
    tmux kill-session -t =__anchor__ 2>/dev/null || true
fi

# CORE DUMPS, enforced on whatever server is actually live. Setting `ulimit -c`
# in this script only helps if THIS script cold-starts the server; normally the
# server already exists (created by the app's recovery command or another path)
# and panes fork INSIDE it, inheriting ITS limits from whenever it was born —
# so the live server was measured with `Max core file size 0` and the deaths
# produced no core. prlimit fixes the running process directly, every tick, no
# matter who created it. Cores land as ./core in the server cwd (core_pattern is
# read-only in this LXC). Also cover the claude processes themselves.
if command -v prlimit >/dev/null 2>&1; then
    TSRV=${'$'}(tmux display -p '#{pid}' 2>/dev/null)
    [ -n "${'$'}TSRV" ] && prlimit --pid "${'$'}TSRV" --core=unlimited 2>/dev/null || true
fi

# SHUTDOWN GUARD: during a reboot/shutdown systemd tears tmux down before the
# machine halts, so a drift tick here would see LIVE=[] and blank sessions.json
# — the exact file the next boot's restore.sh reads. Skip reconcile entirely
# while the system is going down so the restore manifest survives the reboot.
STATE=${'$'}(systemctl is-system-running 2>/dev/null || true)
case "${'$'}STATE" in
    stopping|offline) echo "system ${'$'}STATE — skip reconcile (preserve restore manifest)"; exit 0;;
esac

# ONE process-tree snapshot instead of a recursive pgrep walk per session:
# build a pid->comm map and a ppid->children map, then resolve each pane's
# claude descendant by walking that in-memory tree. pgrep -P forked at every
# tree level for every session (~5s CPU/tick over ~32 sessions); one `ps` plus
# an in-shell walk gives byte-identical results with no per-level forks.
declare -A DRIFT_COMM DRIFT_KIDS
while read -r _p _pp _cm; do
    DRIFT_COMM[${'$'}_p]=${'$'}_cm
    DRIFT_KIDS[${'$'}_pp]="${'$'}{DRIFT_KIDS[${'$'}_pp]:-} ${'$'}_p"
done < <(ps -eo pid=,ppid=,comm=)

# Walk the tmux pane's process tree to find the claude process — pane_pid is
# often bash (claude launched via a shell command / keepalive), so claude is a
# descendant. Same depth-first, first-match descent as before, over the map.
find_claude_descendant() {
    local p=${'$'}1 c r
    if [ "${'$'}{DRIFT_COMM[${'$'}p]:-}" = "claude" ]; then echo "${'$'}p"; return 0; fi
    for c in ${'$'}{DRIFT_KIDS[${'$'}p]:-}; do
        r=${'$'}(find_claude_descendant "${'$'}c"); [ -n "${'$'}r" ] && { echo "${'$'}r"; return 0; }
    done
}

# ---- sessions only a line of prefixes names (B1) ----
# A session whose name starts with ${TmuxNameParser.DEFAULT_PREFIX} is ours by its name alone, as in v18 (and only such a session makes
# the __anchor__ check above). One only a line of ~/.claude-remote/prefixes names may be somebody else's, so drift
# adopts it only with proof: claude runs in its pane with one of the two flags the app and restore.sh always start it
# with (metadata from claude's cmdline and environ, a missing serverId from the app's entry of that name), or the app's
# sessions.json has an entry of that name, kept as the app wrote it (claude without the flag gets no YOLO default).
# Neither: not adopted, not written, not counted as a live session of ours, never relaunched.
APP_JSON="[]"; OWN_META=""; B1_DROPPED=0
if [ "${'$'}PFX" != "${TmuxNameParser.DEFAULT_PREFIX}" ] && [ -f "${'$'}SF_APP" ]; then
    APP_JSON=${'$'}(jq -c 'if type == "array" then map(select(type == "object" and (.tmuxSessionName | type) == "string")) else [] end' "${'$'}SF_APP" 2>/dev/null)
    [ -n "${'$'}APP_JSON" ] || APP_JSON="[]"
fi
app_has() { printf '%s' "${'$'}APP_JSON" | jq -e --arg n "${'$'}1" 'any(.[]; .tmuxSessionName == ${'$'}n)' >/dev/null 2>&1; }
# ---- end of sessions only a line of prefixes names ----

# Ground-truth entry list from the live tmux sessions. Collect one tab-separated
# row per session into a temp table, then build the whole LIVE JSON array in a
# SINGLE jq pass (was one jq fork per session).
LIVE_TBL=${'$'}(mktemp "${'$'}HOME/.claude-remote/.drift.live.XXXXXX" 2>/dev/null || mktemp 2>/dev/null)
# `tmux -u` on every read: this daemon runs from a systemd user unit, which has
# NO locale at all, and a non-UTF-8 tmux client gets each non-ASCII character of
# a session name (and of pane_current_path) replaced with '_' by utf8_sanitize().
# Without -u this loop wrote '_'-mangled names into sessions.json as GROUND
# TRUTH: the real session's entry vanished, the mangled one collapsed onto any
# genuinely mangled twin (two identical rows in the manifest), and the next
# client to read it relaunched the "missing" session under the broken name.
# ---- the live sessions, one name per line (v20, E1) ----
# A name is read whole, one per line: a name with a space or a glob character (`claude-server-My Project`, `…a*b`) is
# one name, never words or file names of the cwd (`for s in ${'$'}(…)` split it, and self-heal started a duplicate). Every
# tmux target below is exact: a bare name matches by prefix or pattern, so `claude-server-My` read the pane of
# `claude-server-My Project`. A pane target carries the colon (`=name:`): display-message with `=name` alone prints
# nothing and exits 0 (tmux 3.5a), which would give every new adoption an empty folder.
while IFS= read -r s; do
    is_ours "${'$'}s" || continue
    pane_pid=${'$'}(tmux -u list-panes -t "=${'$'}s:" -F '#{pane_pid}' 2>/dev/null | head -1)
    [ -n "${'$'}pane_pid" ] || continue
    folder=${'$'}(tmux -u display-message -p -t "=${'$'}s:" '#{pane_current_path}' 2>/dev/null)
    pid=${'$'}(find_claude_descendant "${'$'}pane_pid")
    # ---- claude's arguments, one by one (v20, E2 and E2b) ----
    # /proc/<pid>/cmdline read argument by argument (NUL-separated) up to the first `--`: everything after it is
    # positional (a prompt). The app's signature is an argument equal to one of its two flags, never a piece of a
    # prompt; model, mode and session id are the argument after --model, --permission-mode and --resume or
    # --session-id, the first of each (a prompt that names `--session-id <id>` is one argument and never matches). A
    # value that equals a flag's text counts as that flag, as claude's own parser reads it (a limit).
    a_sig=""; a_allow=""; a_model=""; a_pmode=""; a_sid=""; a_gm=""; a_gp=""; a_gs=""
    if [ -n "${'$'}pid" ]; then
        a_prev=""
        while IFS= read -r -d '' a || [ -n "${'$'}a" ]; do
            [ "${'$'}a" = "--" ] && break
            case "${'$'}a_prev" in
                --model) [ -n "${'$'}a_gm" ] || { a_gm=1; a_model=${'$'}a; };;
                --permission-mode) [ -n "${'$'}a_gp" ] || { a_gp=1; a_pmode=${'$'}a; };;
                --resume|--session-id) [ -n "${'$'}a_gs" ] || { a_gs=1; a_sid=${'$'}a; };;
            esac
            case "${'$'}a" in
                --dangerously-skip-permissions) a_sig=1;;
                --allow-dangerously-skip-permissions) a_sig=1; a_allow=1;;
            esac
            a_prev=${'$'}a
        done 2>/dev/null < "/proc/${'$'}pid/cmdline"
    fi
    # ---- end of claude's arguments ----
    # ---- a session only a line of prefixes names (B1) ----
    case "${'$'}s" in
        "${TmuxNameParser.DEFAULT_PREFIX}"*) ;;
        *)  if [ -n "${'$'}a_sig" ]; then OWN_META="${'$'}OWN_META"${'$'}'\n'"c:${'$'}s"
            elif app_has "${'$'}s"; then OWN_META="${'$'}OWN_META"${'$'}'\n'"a:${'$'}s"
            else continue
            fi;;
    esac
    # ---- end of a session only a line of prefixes names ----
    sid=""; model="DEFAULT"; mode="YOLO"; acct=""
    if [ -n "${'$'}pid" ]; then
        # ACCOUNT: the login this pane actually runs under. The command PREFIX
        # (CLAUDE_CONFIG_DIR=... claude ...) is an env assignment, not an argv
        # token, so cmdline can't show it — the process's own environ can.
        # Empty ⇒ the default ~/.claude login (no var), which is exactly the
        # null the manifest wants.
        cfg=""
        if [ -r "/proc/${'$'}pid/environ" ]; then
            cfg=${'$'}(tr '\0' '\n' < "/proc/${'$'}pid/environ" 2>/dev/null \
                | sed -n 's|^CLAUDE_CONFIG_DIR=||p' | head -1)
            acct=${'$'}(printf '%s' "${'$'}cfg" | sed -n 's|.*/accounts/\([^/]*\)/*${'$'}|\1|p')
        fi
        case "${'$'}a_model" in
            opus)   model=OPUS;;
            sonnet) model=SONNET;;
            haiku)  model=HAIKU;;
            fable)  model=FABLE;;
        esac
        case "${'$'}a_pmode" in
            auto) mode=AUTO;;
            acceptEdits) mode=AUTO_ACCEPT;;
            *) [ -z "${'$'}a_allow" ] || mode=DEFAULT;;
        esac
        # a session id is hex and dashes (a uuid): anything else after --resume (a search term, a flag) is none
        case "${'$'}a_sid" in ''|-*|*[!0-9a-f-]*) ;; *) sid=${'$'}a_sid;; esac
        # Claude writes its per-pid state under the CONFIG DIR, so a pane running
        # under a non-default login keeps it in ~/.claude-remote/accounts/<slug>/
        # sessions/, not ~/.claude/sessions/. Hardcoding the latter made this
        # whole refresh a no-op for account sessions: their claudeSessionId was
        # frozen at the launch/--resume id forever, which is what pinned a
        # client's chat view to the pre-fork transcript after every restore.
        psf="${'$'}{cfg:-${'$'}HOME/.claude}/sessions/${'$'}pid.json"
        if [ -f "${'$'}psf" ]; then
            v=${'$'}(jq -r .sessionId "${'$'}psf" 2>/dev/null)
            # claude >= 2.1.220 FORKS on --resume: it reports a BRAND NEW
            # sessionId whose transcript is created lazily, so adopting it
            # ratchets the manifest onto an empty conversation and orphans the
            # real one for good (proven: server--CC 17b4feef -> 667a07bd, then
            # "no transcript -> launching fresh" on every restore since).
            # Only adopt an id that actually HAS a transcript on disk.
            # Accounts normally share ~/.claude/projects via symlink, but a login
            # provisioned out of order has a REAL projects dir of its own — look
            # in both so the guard can't reject a transcript that exists.
            if [ -n "${'$'}v" ] && [ "${'$'}v" != "null" ] && [ "${'$'}v" != "${'$'}sid" ]; then
                if ls "${'$'}HOME"/.claude/projects/*/"${'$'}v".jsonl >/dev/null 2>&1 \
                   || ls "${'$'}{cfg:-${'$'}HOME/.claude}"/projects/*/"${'$'}v".jsonl >/dev/null 2>&1; then
                    sid="${'$'}v"
                fi
            fi
        fi
    fi
    case "${'$'}s" in *--*) alias="${'$'}{s##*--}";; *) alias="";; esac
    [ -n "${'$'}LIVE_TBL" ] && printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "${'$'}s" "${'$'}folder" "${'$'}mode" "${'$'}model" "${'$'}alias" "${'$'}sid" "${'$'}acct" >> "${'$'}LIVE_TBL"
done < <(tmux -u list-sessions -F '#{session_name}' 2>/dev/null)
# ---- end of the live sessions ----
LIVE="[]"
if [ -n "${'$'}LIVE_TBL" ]; then
    LIVE=${'$'}(jq -R -s -c '
        split("\n") | map(select(length>0)) | map(split("\t")) | map({
            id: .[0], serverId: "", folder: .[1], mode: .[2], model: .[3],
            tmuxSessionName: .[0], connectionType: "SSH", alias: .[4],
            claudeSessionId: (if .[5]=="" then null else .[5] end), createdAt: 0,
            accountSlug: (if (.[6] // "")=="" then null else .[6] end)
        })' "${'$'}LIVE_TBL" 2>/dev/null)
    rm -f "${'$'}LIVE_TBL"
    [ -n "${'$'}LIVE" ] || LIVE="[]"
fi
# ---- the app's entry of a session only a line of prefixes names (B1) ----
# a:<name> is the app's entry as it stands, c:<name> (claude with the app's flag) gets the entry's serverId. The entries
# come from a file (an execve argument stops at 128 KB); if this jq fails, only these names miss this tick.
if [ -n "${'$'}OWN_META" ]; then
    if NL=${'$'}(printf '%s' "${'$'}LIVE" | jq -c --slurpfile app0 <(printf '%s' "${'$'}APP_JSON") --arg own "${'$'}OWN_META" '
        def crsafe: (if has("claudeSessionId") then .claudeSessionId |= (if type == "string" and test("\\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\z") then . else null end) else . end)
            | (if has("accountSlug") then .accountSlug |= (if type == "string" and test("\\A[\\p{L}\\p{N}@._-]{1,64}\\z") and . != "." and . != ".." then . else null end) else . end);
        (${'$'}app0[0] | map({key: .tmuxSessionName, value: .}) | from_entries) as ${'$'}am
        | (${'$'}own | split("\n") | map(select(length > 0) | {key: .[2:], value: .[0:1]}) | from_entries) as ${'$'}om
        | map(.tmuxSessionName as ${'$'}n
            | if ${'$'}om[${'$'}n] == "a" then (${'$'}am[${'$'}n] | crsafe)
              elif ${'$'}om[${'$'}n] == "c" then . + {serverId: ((${'$'}am[${'$'}n].serverId // "") | if type == "string" then . else "" end)}
              else . end)
        | map(select(type == "object"))' 2>/dev/null) && [ -n "${'$'}NL" ]; then
        LIVE=${'$'}NL
        # an adopted session of ours lives: the anchor goes, as above for ${TmuxNameParser.DEFAULT_PREFIX}*
        tmux kill-session -t =__anchor__ 2>/dev/null || true
    else
        LIVE=${'$'}(printf '%s' "${'$'}LIVE" | jq -c --arg own "${'$'}OWN_META" '(${'$'}own | split("\n") | map(select(length > 0) | .[2:])) as ${'$'}o
            | map(select(.tmuxSessionName as ${'$'}n | ${'$'}o | any(.[]; . == ${'$'}n) | not))' 2>/dev/null)
        [ -n "${'$'}LIVE" ] || LIVE="[]"
        # v20 (E3): this tick's manifest misses these names, so the mirror below must not copy it over the app's
        # sessions.json, which still has the app's entries of them
        B1_DROPPED=1
    fi
fi
# ---- end of the app's entry ----
HDR="${'$'}HDR
LIVE=${'$'}(echo "${'$'}LIVE" | jq -c 'map(.tmuxSessionName)')"
(
    flock -x 9
    CORRUPT=0
    OLD="[]"; [ -f "${'$'}SF" ] && OLD=${'$'}(cat "${'$'}SF")
    # A truncated/0-byte ${'$'}SF (ENOSPC, unclean shutdown) makes the union jq below
    # fail, NEW empty, and the `[ -n "${'$'}NEW" ]` guard then skips the write — every
    # tick, forever: reconcile, self-heal and never-blank all silently dead. Fall
    # back to the rolling backup instead of running on garbage.
    if ! printf '%s' "${'$'}OLD" | jq -e 'type=="array"' >/dev/null 2>&1; then
        echo "[${'$'}(date -u +%FT%TZ)] drift: manifest unreadable/corrupt — falling back to .bak"
        OLD="[]"
        if [ -f "${'$'}SF.bak" ] && jq -e 'type=="array"' "${'$'}SF.bak" >/dev/null 2>&1; then OLD=${'$'}(cat "${'$'}SF.bak"); fi
        # Force the write even when NEW ends up equal to OLD: the file ON DISK is
        # garbage, so "no change" must not mean "leave the corruption in place".
        CORRUPT=1
    fi
    # Tombstones: one tmux name per line a client explicitly closed. Read them
    # into a jq array. Subtract them from the reconcile so a real close is
    # honoured even if its sessions.json push failed, and (below) from the
    # self-heal list so a forgotten session is never relaunched.
    # RE-ENABLED (v10): paired with the client-side forget-guard shipping in the
    # same build — forgetSession now fires ONLY on a genuine user-close, not on a
    # mere server-death, so honoring ~/.claude-remote/forgotten can no longer let
    # a whole-server outage wipe the manifest. Re-enabling lets user-close work
    # again and stops the manifest growing unbounded with dead entries.
    FORGET="[]"
    FGLEN=0
    if [ -f "${'$'}FORGOTTEN" ] && [ -s "${'$'}FORGOTTEN" ]; then
        FORGET=${'$'}(jq -R . "${'$'}FORGOTTEN" | jq -s 'map(select(length>0))' 2>/dev/null)
        [ -n "${'$'}FORGET" ] || FORGET="[]"
        FGLEN=${'$'}(echo "${'$'}FORGET" | jq 'length' 2>/dev/null || echo 0)
    fi
    # DURABLE tombstones: `forgotten.d/<name>`, one file per close, written ONLY
    # by an explicit user close (the app's prune — which merely GUESSES a pane is
    # gone — writes the transient file above and nothing here). That provenance
    # is the whole point: the mass-tombstone guard below exists because a client
    # recovering from an outage could tombstone its entire fleet at once, and it
    # cannot tell that apart from real closes when all it sees is a list of
    # names. A durable marker carries the distinction, so it is honoured with NO
    # count limit — otherwise closing four sessions in one minute (a completely
    # ordinary thing to do with 45 of them) trips the guard, the transient file
    # is thrown away, and SELF-HEAL relaunches every session the user just
    # closed. That is exactly what happened on 2026-08-31T08:31:49Z.
    DFG="[]"
    DFGLEN=0
    if [ -d "${'$'}FORGOTTEN_D" ]; then
        DFG=${'$'}(find "${'$'}FORGOTTEN_D" -maxdepth 1 -type f -mtime -${'$'}TOMBSTONE_WINDOW -printf '%f\n' 2>/dev/null \
            | jq -R . | jq -s 'map(select(length>0))' 2>/dev/null)
        [ -n "${'$'}DFG" ] || DFG="[]"
        DFGLEN=${'$'}(echo "${'$'}DFG" | jq 'length' 2>/dev/null || echo 0)
    fi
    # MASS-TOMBSTONE GUARD: a human closes sessions ONE AT A TIME. More than 2
    # tombstones in a single tick means the CLIENT mass-forgot after an outage or
    # a PARTIAL recovery — its own forget-guard only blocks the all-dead case,
    # not "8 came back, 15 still missing" — which on 2026-07-27 silently cut the
    # manifest 23 -> 8 and lost those sessions for good. Refuse to honour them
    # and move the file aside as evidence so it cannot keep tripping.
    if [ "${'$'}FGLEN" -gt 2 ]; then
        echo "[${'$'}(date -u +%FT%TZ)] drift: REFUSING ${'$'}FGLEN tombstones at once (mass-forget after outage, not user closes) — moved aside"
        mv -f "${'$'}FORGOTTEN" "${'$'}FORGOTTEN.rejected.${'$'}(date -u +%Y%m%dT%H%M%SZ)" 2>/dev/null || : > "${'$'}FORGOTTEN"
        FORGET="[]"; FGLEN=0
    fi
    # Union AFTER the guard: rejecting a suspicious transient batch must never
    # reject the durable markers, which are proof of intent rather than a list
    # of names that happen to be missing.
    if [ "${'$'}DFGLEN" -gt 0 ]; then
        FORGET=${'$'}(jq -n --argjson t "${'$'}FORGET" --argjson d "${'$'}DFG" '${'$'}t + ${'$'}d | unique' 2>/dev/null)
        [ -n "${'$'}FORGET" ] || FORGET="${'$'}DFG"
        FGLEN=${'$'}(echo "${'$'}FORGET" | jq 'length' 2>/dev/null || echo 0)
    fi
    # ---- the first manifest of a box with another name (v20, E3) ----
    # On a box whose prefixes file names a prefix of its own, the first manifest drift wrote used to hold the live
    # sessions alone, and the mirror then dropped from sessions.json every entry of the app whose session was not alive
    # at that moment. Drift's first manifest there (no sessions.restore.json yet) starts from the app's entries of
    # this box's own prefixes: never claude-server-*, never a tombstoned name, one row per name. A migration, once:
    # from then on the manifest is drift's own, as on server, and no client's push is an authority for self-heal.
    # Self-heal relaunches the entries whose session is not alive, as for any entry whose session died.
    # The app's file is read here, under the manifest lock (the app's push writes it under the same lock): a file jq
    # cannot read (half written: unparsable or empty, and changed within two minutes) postpones the first manifest, and
    # this tick writes no manifest, no mirror and consumes no tombstone (SEED_HOLD), so the next tick seeds from the
    # whole file (review E-D K-ED1). Only a name tmux holds as
    # written: tmux turns `.` and `:` into `_` and escapes control characters, so such a name would be "missing"
    # forever and its relaunch would start a session of another name (A-ED1). Only a session id that is a uuid (what the
    # app and claude write; a 40 KB id made tmux refuse the command every tick) and an account slug of 1 to 64 letters,
    # digits, @ . _ - (what the app's slugs are made of; never `.` or `..`), each matched on the whole string, else null.
    SEEDED=0; SEED_HOLD=0; SEEDLEN=0; HOLD_ST="${'$'}HOME/.claude-remote/drift.seedhold"
    if [ "${'$'}PFX" != "${TmuxNameParser.DEFAULT_PREFIX}" ] && [ ! -f "${'$'}SF" ] && [ -f "${'$'}SF_APP" ]; then
        if SEED=${'$'}(jq -c --arg pfx "${'$'}PFX" --argjson forget "${'$'}FORGET" '
            def crsafe: (if has("claudeSessionId") then .claudeSessionId |= (if type == "string" and test("\\A[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\z") then . else null end) else . end)
            | (if has("accountSlug") then .accountSlug |= (if type == "string" and test("\\A[\\p{L}\\p{N}@._-]{1,64}\\z") and . != "." and . != ".." then . else null end) else . end);
            (if type == "array" then . else [] end) | map(select(type == "object" and (.tmuxSessionName | type) == "string"))
            | (${'$'}pfx | split(" ") | map(select(length > 0 and . != "${TmuxNameParser.DEFAULT_PREFIX}"))) as ${'$'}own
            | map(select(.tmuxSessionName as ${'$'}n
                | (${'$'}n | test("[.:]|[[:cntrl:]]") | not)
                  and (${'$'}n | startswith("${TmuxNameParser.DEFAULT_PREFIX}") | not)
                  and any(${'$'}own[]; . as ${'$'}q | ${'$'}n | startswith(${'$'}q))
                  and ((${'$'}forget | index(${'$'}n)) | not)))
            | map(crsafe)
            | reduce .[] as ${'$'}e ([]; if any(.[]; .tmuxSessionName == ${'$'}e.tmuxSessionName) then . else . + [${'$'}e] end)' "${'$'}SF_APP" 2>/dev/null) \
           && [ -n "${'$'}SEED" ]; then
            [ -f "${'$'}HOLD_ST" ] && rm -f "${'$'}HOLD_ST"
            SEEDLEN=${'$'}(printf '%s' "${'$'}SEED" | jq 'length' 2>/dev/null || echo 0)
            if [ "${'$'}{SEEDLEN:-0}" -gt 0 ]; then OLD=${'$'}SEED; SEEDED=1; fi
        else
            # ---- the first manifest postponed (v20, A-ED9) ----
            # A write in progress has an mtime of now (-2 min … +1 min); one older or in the future is none. A file the app
            # keeps rewriting broken would postpone the first manifest for ever, so a series of postponements has a cap of
            # 5 ticks, counted in drift.seedhold (garbled or empty = 0, so at most 5 more; a directory = no state, so no
            # postponement). Then, or for a file broken for longer, the first manifest goes on from the live sessions
            # without the app's entries. One line opens a series and one ends it; none per tick (99 = ended, logged).
            sh_m=${'$'}(stat -c %Y "${'$'}SF_APP" 2>/dev/null || echo 0); sh_now=${'$'}(date +%s); sh_n=0
            [ -f "${'$'}HOLD_ST" ] && sh_n=${'$'}(head -c 16 "${'$'}HOLD_ST" 2>/dev/null | tr -cd 0123456789)
            sh_n=${'$'}{sh_n:-0}
            if [ "${'$'}sh_m" -gt ${'$'}((sh_now - 120)) ] && [ "${'$'}sh_m" -le ${'$'}((sh_now + 60)) ] && [ ! -d "${'$'}HOLD_ST" ] && [ "${'$'}sh_n" -lt 5 ]; then
                SEED_HOLD=1
                [ "${'$'}sh_n" -eq 0 ] && echo "[${'$'}(date -u +%FT%TZ)] drift: the app's sessions.json is unreadable and being written — first manifest postponed (at most 5 ticks)"
                sh_n=${'$'}((sh_n + 1))
            elif [ ! -d "${'$'}HOLD_ST" ] && [ "${'$'}sh_n" -lt 99 ]; then
                echo "[${'$'}(date -u +%FT%TZ)] drift: the app's sessions.json is unreadable — first manifest from the live sessions, without the app's entries"
                sh_n=99
            fi
            [ -d "${'$'}HOLD_ST" ] || { printf '%s\n' "${'$'}sh_n" > "${'$'}HOLD_ST.tmp.${'$'}${'$'}" && mv -f "${'$'}HOLD_ST.tmp.${'$'}${'$'}" "${'$'}HOLD_ST"; } 2>/dev/null || rm -f "${'$'}HOLD_ST.tmp.${'$'}${'$'}"
            # ---- end of the first manifest postponed ----
        fi
    fi
    # ---- end of the first manifest ----
    # Keep client metadata for sessions already in OLD (refresh only the live
    # claudeSessionId); add live sessions missing from OLD.
    # UNION reconcile — never DROP a manifest entry just because its tmux isn't
    # live right now (it may have crashed; self-heal below relaunches it).
    # Dropping it would let a client reading the shrunken manifest wrongly
    # "forget" the session. Keep every OLD entry (refresh its live sid), append
    # live sessions not yet in the manifest, THEN subtract tombstoned names so
    # an explicit close leaves the manifest for good.
    NEW=${'$'}(jq -n --slurpfile old0 <(printf '%s' "${'$'}OLD") --slurpfile live0 <(printf '%s' "${'$'}LIVE") --argjson forget "${'$'}FORGET" '
        ${'$'}old0[0] as ${'$'}old | ${'$'}live0[0] as ${'$'}live
        | ((${'$'}live | map({key:.tmuxSessionName, value:.}) | from_entries) as ${'$'}lm
        | (${'$'}old | map(.tmuxSessionName)) as ${'$'}on
        | (${'$'}old | map(. as ${'$'}o | (${'$'}lm[${'$'}o.tmuxSessionName]) as ${'$'}l
             | (if ${'$'}l and (${'$'}l.claudeSessionId != null) then ${'$'}o + {claudeSessionId:${'$'}l.claudeSessionId} else ${'$'}o end)
             | (if ${'$'}l and (${'$'}l.accountSlug != null) then . + {accountSlug:${'$'}l.accountSlug} else . end)))
          + (${'$'}live | map(select(.tmuxSessionName as ${'$'}n | (${'$'}on | index(${'$'}n)) | not))))
        | map(select(.tmuxSessionName as ${'$'}n | (${'$'}forget | index(${'$'}n)) | not))' 2>/dev/null)
    NEWLEN=${'$'}(echo "${'$'}NEW" | jq 'length' 2>/dev/null || echo 0)
    OLDLEN=${'$'}(echo "${'$'}OLD" | jq 'length // 0' 2>/dev/null || echo 0)
    if [ "${'$'}SEED_HOLD" = 0 ] && [ -n "${'$'}NEW" ] && { [ "${'$'}NEW" != "${'$'}OLD" ] || [ "${'$'}{CORRUPT:-0}" = "1" ] || [ "${'$'}SEEDED" = "1" ]; }; then
        # NEVER replace a non-empty manifest with an empty one — a live tmux
        # server that momentarily lists no claude-server-* sessions (crash,
        # race, shutdown the STATE probe missed) must not destroy the restore
        # manifest. Exception: an empty result the TOMBSTONES fully explain is a
        # real "closed everything" and is allowed through, else a closed session
        # would be resurrected on the next reboot.
        # The test is "every entry that vanished was tombstoned", not the older
        # "FGLEN>0": durable markers linger for TOMBSTONE_WINDOW days, so a mere
        # non-empty tombstone set would leave this protection switched off for a
        # fortnight after any close — and then a whole-server outage could blank
        # the manifest, which is the incident this guard was added for.
        DROP_EXPLAINED=${'$'}(jq -n --slurpfile old0 <(printf '%s' "${'$'}OLD") --argjson forget "${'$'}FORGET" \
            '${'$'}old0[0] as ${'$'}old | ((${'$'}old | map(.tmuxSessionName)) - ${'$'}forget | length) == 0' 2>/dev/null)
        if [ "${'$'}NEWLEN" -eq 0 ] && [ "${'$'}OLDLEN" -gt 0 ] && [ "${'$'}{DROP_EXPLAINED:-false}" != "true" ]; then
            echo "[${'$'}(date -u +%FT%TZ)] drift: refusing to blank non-empty manifest (${'$'}OLDLEN entries) — likely shutdown/tmux restart"
        else
              # Roll an UNFILTERED backup. This used to strip tombstoned entries
              # "so a .bak fallback can't resurrect a closed session" — which
              # destroyed the only backup in the SAME tick as the damage: on
              # 2026-07-27 the client mass-forgot 15 sessions and .bak was left
              # holding 7, so no copy of the 23 existed to recover from.
              # Resurrecting one closed session in an emergency is vastly cheaper
              # than losing fifteen.
              # NEVER roll a backup FROM a corrupt/empty primary — that would
              # copy garbage over the last good copy (caught in testing: a
              # truncated ${'$'}SF wiped the 25-entry .bak). Only back up a manifest
              # that is itself valid.
              if [ "${'$'}OLDLEN" -gt 0 ] && [ "${'$'}{CORRUPT:-0}" = "0" ] \
                 && [ -s "${'$'}SF" ] && jq -e 'type=="array"' "${'$'}SF" >/dev/null 2>&1; then
                  cp -f "${'$'}SF" "${'$'}SF.bak" 2>/dev/null || true
              fi
              # Durable snapshots: keep every DROP (the event you need to undo)
              # and a high-water copy (the largest manifest ever seen).
              # restore.sh falls back to highwater.json as a last resort.
              MDIR="${'$'}HOME/.claude-remote/manifests"; mkdir -p "${'$'}MDIR" 2>/dev/null
              if [ "${'$'}NEWLEN" -lt "${'$'}OLDLEN" ] && [ "${'$'}OLDLEN" -gt 0 ]; then
                  cp -f "${'$'}SF" "${'$'}MDIR/${'$'}(date -u +%Y%m%dT%H%M%SZ)-drop-${'$'}OLDLEN-to-${'$'}NEWLEN.json" 2>/dev/null || true
                  ls -1t "${'$'}MDIR"/*-drop-*.json 2>/dev/null | tail -n +51 | xargs -r rm -f 2>/dev/null || true
              fi
              HW=0
              if [ -f "${'$'}MDIR/highwater.json" ]; then HW=${'$'}(jq 'length' "${'$'}MDIR/highwater.json" 2>/dev/null || echo 0); fi
              if [ "${'$'}NEWLEN" -gt "${'$'}{HW:-0}" ]; then printf '%s' "${'$'}NEW" > "${'$'}MDIR/highwater.json" 2>/dev/null || true; fi
            echo "${'$'}NEW" > "${'$'}SF.tmp.${'$'}${'$'}" && mv "${'$'}SF.tmp.${'$'}${'$'}" "${'$'}SF"
            # the first manifest is logged once it is written (A-ED3: not when the write could not happen)
            [ "${'$'}SEEDED" = 1 ] && [ -f "${'$'}SF" ] && echo "[${'$'}(date -u +%FT%TZ)] drift: first manifest of this box — ${'$'}SEEDLEN entries from the app's sessions.json"
            [ -f "${'$'}HOLD_ST" ] && [ -f "${'$'}SF" ] && rm -f "${'$'}HOLD_ST"
            echo "[${'$'}(date -u +%FT%TZ)] drift: reconciled ${'$'}NEWLEN live (was ${'$'}OLDLEN)"
        fi
    fi
    # Tombstone self-clean: a forgotten name now absent from BOTH live tmux AND
    # the written manifest has done its job — drop it so a future session
    # reusing the same tmux name isn't blocked. Race-safe under this flock.
    if [ "${'$'}SEED_HOLD" = 0 ] && [ -f "${'$'}FORGOTTEN" ] && [ -s "${'$'}FORGOTTEN" ]; then
        MANIFEST="[]"; [ -f "${'$'}SF" ] && MANIFEST=${'$'}(cat "${'$'}SF")
        KEEP=${'$'}(while IFS= read -r fn; do
            [ -n "${'$'}fn" ] || continue
            if tmux has-session -t="${'$'}fn" 2>/dev/null; then echo "${'$'}fn"; continue; fi
            if echo "${'$'}MANIFEST" | jq -e --arg n "${'$'}fn" '[.[].tmuxSessionName] | index(${'$'}n)' >/dev/null 2>&1; then echo "${'$'}fn"; fi
        done < "${'$'}FORGOTTEN")
        if [ -n "${'$'}KEEP" ]; then printf '%s\n' "${'$'}KEEP" > "${'$'}FORGOTTEN.tmp.${'$'}${'$'}" && mv "${'$'}FORGOTTEN.tmp.${'$'}${'$'}" "${'$'}FORGOTTEN"; else : > "${'$'}FORGOTTEN"; fi
    fi
    # ---- the mirror (v20: under the manifest lock, by rename) ----
    # Mirror the server-owned source of truth to the app-facing sessions.json (the client may have blanked/shrunk it on
    # reconnect); keeps the app view correct within 60s and re-expands a client that wrongly forgot sessions. Mirror ONLY
    # a valid, non-empty manifest (an unguarded copy blanked the app view), written whole: a temp file checked as a JSON
    # array and renamed into place under this lock, so the app's reader never sees half a file. Not in a tick whose
    # manifest misses the names of a failed overlay of the app's entries (B1_DROPPED).
    if [ "${'$'}B1_DROPPED" = 0 ] && [ "${'$'}SEED_HOLD" = 0 ] && [ ! -d "${'$'}SF_APP" ] && [ -f "${'$'}SF" ] && [ -s "${'$'}SF" ] && jq -e 'length>0' "${'$'}SF" >/dev/null 2>&1 \
       && ! cmp -s "${'$'}SF" "${'$'}SF_APP" 2>/dev/null; then
        { cp -f "${'$'}SF" "${'$'}SF_APP.tmp.${'$'}${'$'}" && jq -e 'type=="array"' "${'$'}SF_APP.tmp.${'$'}${'$'}" >/dev/null 2>&1 \
            && mv -f "${'$'}SF_APP.tmp.${'$'}${'$'}" "${'$'}SF_APP"; } 2>/dev/null || rm -f "${'$'}SF_APP.tmp.${'$'}${'$'}"
    fi
    # ---- end of the mirror ----
) 9<>"${'$'}LOCK"
$FOLDER_EXPAND_FUNC

# SELF-HEAL: relaunch any manifest session whose tmux is gone (tmux-server
# crash, OOM, stray kill) so it returns within 60s instead of staying dead
# until a reboot. Critically this keeps tmux from ever sitting empty — an empty
# server is exactly the signal that makes clients wrongly "forget" every
# session and shrink the manifest. restore.sh is idempotent: skips live
# sessions and missing folders. Tombstoned names are skipped so an explicit
# close is never relaunched.
if [ -f "${'$'}SF" ]; then
    # A name tmux cannot hold as written (`.`, `:`, a control character: tmux makes it `_` or escapes it) is never
    # "missing": its relaunch would start a session of another name, every tick (v20, A-ED1). On the box `server` the
    # manifest holds only names of live sessions, so no such name is ever there.
    MISSING=${'$'}(jq -r '.[].tmuxSessionName | select((type == "string" and test("[.:]|[[:cntrl:]]")) | not)' "${'$'}SF" 2>/dev/null | while IFS= read -r n; do
        [ -n "${'$'}n" ] || continue
        [ -f "${'$'}FORGOTTEN" ] && grep -qxF "${'$'}n" "${'$'}FORGOTTEN" && continue
        # Durable marker = the user closed this. Checked separately from the
        # transient file because that one is consumed (and, on a rejected batch,
        # deleted) within a tick, while this relaunch decision has to keep
        # honouring the close for as long as the marker stands.
        [ -f "${'$'}FORGOTTEN_D/${'$'}n" ] \
            && [ -n "${'$'}(find "${'$'}FORGOTTEN_D/${'$'}n" -maxdepth 0 -mtime -${'$'}TOMBSTONE_WINDOW 2>/dev/null)" ] \
            && continue
        tmux has-session -t="${'$'}n" 2>/dev/null && continue
        # ---- an entry whose folder is missing (v20, E4b) ----
        # restore.sh skips it ("folder … missing"), so relaunching it only ran restore.sh and logged the line every
        # minute. It stays in the manifest (a folder on a mount comes back) and is relaunched once its folder is there.
        nf=${'$'}(jq -r --arg n "${'$'}n" 'first(.[] | select(.tmuxSessionName == ${'$'}n) | .folder) // ""' "${'$'}SF" 2>/dev/null)
        if nf=${'$'}(cr_folder "${'$'}nf") && [ -d "${'$'}nf" ]; then echo "m:${'$'}n"; else echo "s:${'$'}n"; fi
        # ---- end of an entry whose folder is missing ----
    done)
    SKIPPED=${'$'}(printf '%s\n' "${'$'}MISSING" | sed -n 's/^s://p')
    MISSING=${'$'}(printf '%s\n' "${'$'}MISSING" | sed -n 's/^m://p')
    # ---- the entries left out for their folder, logged once per change (v20, E4b) ----
    # drift.skipped holds the names this tick left out; it only decides whether to log (missing or garbled: log once
    # more), never what self-heal relaunches.
    SKIP_ST="${'$'}HOME/.claude-remote/drift.skipped"
    if [ -d "${'$'}SKIP_ST" ]; then
        :   # a directory in its place (nobody makes one): no state, so no line, and nothing is written into it
    elif [ -n "${'$'}SKIPPED" ]; then
        if [ "${'$'}(cat "${'$'}SKIP_ST" 2>/dev/null)" != "${'$'}SKIPPED" ]; then
            echo "[${'$'}(date -u +%FT%TZ)] drift: not relaunching, folder missing (kept in the manifest): ${'$'}(printf '%s\n' "${'$'}SKIPPED" | tr '\n' ' ')"
            { printf '%s\n' "${'$'}SKIPPED" > "${'$'}SKIP_ST.tmp.${'$'}${'$'}" && mv -f "${'$'}SKIP_ST.tmp.${'$'}${'$'}" "${'$'}SKIP_ST"; } 2>/dev/null || rm -f "${'$'}SKIP_ST.tmp.${'$'}${'$'}"
        fi
    elif [ -e "${'$'}SKIP_ST" ]; then
        rm -f "${'$'}SKIP_ST"
    fi
    # ---- end of the entries left out ----
    # Same guard again right before relaunching: the socket may have gone (or a second server appeared) during
    # this tick, and then every name above is "missing".
    if [ -n "${'$'}MISSING" ] && [ ! -e "${'$'}SG_OFF" ]; then
        sg_guard || MISSING=""
    fi
    if [ -n "${'$'}MISSING" ]; then
        echo "[${'$'}(date -u +%FT%TZ)] drift: self-heal — relaunching missing: ${'$'}(printf '%s\n' "${'$'}MISSING" | tr '\n' ' ')"
        # Run the heal in its OWN transient scope. Anything restore.sh starts
        # (notably a tmux server, when none is running) then belongs to that
        # scope instead of claude-remote-drift.service, whose cgroup systemd
        # tears down the instant this tick ends — which is what killed the
        # freshly restored fleet, twice, 9-12s after it came back.
        if command -v systemd-run >/dev/null 2>&1; then
            systemd-run --user --scope --quiet \
                bash "${'$'}HOME/.claude-remote/restore.sh" >/dev/null 2>&1 || true
        else
            bash "${'$'}HOME/.claude-remote/restore.sh" >/dev/null 2>&1 || true
        fi
    fi
fi
DRIFT_EOF
            chmod +x "${'$'}DRIFT.tmp.${'$'}${'$'}"
${rollbackCopy("DRIFT", "drift.sh", 18)}
${rollbackCopy("DRIFT", "drift.sh", 19)}
            D_NEW=1
        fi
        # The app's tmux anchor (ClaudeConfig.buildTmuxRunCommand) runs this before it may create a server.
        if ! grep -q "${'$'}MARKER" "${'$'}SOCKHEAL" 2>/dev/null; then
            echo "[${'$'}(date -u +%FT%TZ)] install: tmux-sockheal.sh missing ${'$'}MARKER — rewriting; prior marker line: ${'$'}(grep -m1 'claude-remote-restore-v' "${'$'}SOCKHEAL" 2>/dev/null || echo '<none/new file>')" >> "${'$'}HOME/.claude-remote/install.log"
            cat > "${'$'}SOCKHEAL.tmp.${'$'}${'$'}" <<'SOCKHEAL_EOF'
#!/usr/bin/env bash
# marker-compat (do NOT remove): claude-remote-restore-v6 claude-remote-restore-v7 claude-remote-restore-v8 claude-remote-restore-v9 claude-remote-restore-v10 claude-remote-restore-v11 claude-remote-restore-v12 claude-remote-restore-v13 claude-remote-restore-v14 claude-remote-restore-v15 claude-remote-restore-v16 claude-remote-restore-v17 claude-remote-restore-v18 claude-remote-restore-v19 claude-remote-restore-v20
# claude-remote-restore-v18 — tmux socket guard for the app's own tmux anchor. The app runs it before any command
# that may create a tmux server (a second server next to a live one is how 51 sessions got duplicated on 2026-11-02):
#   exit 0  nothing listens on the default socket path, or the socket answers: the app goes on as before
#   exit 3  it was unusable and is usable again (socket recreated with SIGUSR1, or directory made private): the app
#           attaches a session that is still there instead of killing and restarting it
#   exit 1  refused (split brain, hung, not healable): the app creates nothing; the reason goes to stderr
set -u
$SOCK_GUARD_FUNCS
SG_WHO=app
SG_QUIET='#quiet# '
[ -e "${'$'}SG_OFF" ] && exit 0
mkdir -p "${'$'}SG_DIR" 2>/dev/null
# Only an event is logged: a normal launch must not create or grow any file. The last line carries rc and cause.
# Lines the rate-limit stamp holds back (SG_QUIET in front) stay out of the log; a refused tab gets every line.
SG_ALL=
sg_run() {
    local res tail out logged
    res=${'$'}(sg_guard 2>&1; printf '%s %s' "${'$'}?" "${'$'}{SG_WHY:-}")
    tail=${'$'}{res##*${'$'}'\n'}; out=${'$'}{res%"${'$'}tail"}
    SG_RC=${'$'}{tail%% *}; SG_WHY=${'$'}{tail#* }
    logged=${'$'}(printf '%s' "${'$'}out" | sed "/^${'$'}SG_QUIET/d")
    [ -n "${'$'}logged" ] && printf '%s\n' "${'$'}logged" >> "${'$'}SG_DIR/sockheal.log"
    SG_ALL="${'$'}SG_ALL${'$'}out"
}
sg_run
# A heal that failed (lock not taken, listeners changed meanwhile, socket not back) mostly lost a race with another
# tab's heal or attach: one more look after 0.5 s before the tab is refused (S3).
if [ "${'$'}SG_RC" = 1 ] && [ "${'$'}SG_WHY" = heal ]; then sleep 0.5; sg_run; fi
case "${'$'}SG_RC" in
    0) exit 0;;
    2) exit 3;;
esac
case "${'$'}SG_WHY" in
    split) why="two tmux servers listen on ${'$'}SG_SOCK (split brain) — a human must pick the one that stays";;
    hung) why="the tmux server on ${'$'}SG_SOCK does not answer";;
    unsafe) why="${'$'}SG_SOCK_DIR is not a private directory of ours and cannot be made one";;
    *) why="a tmux server is alive on ${'$'}SG_SOCK without its socket and it could not be recreated now";;
esac
printf '%s' "${'$'}SG_ALL" | sed "s/^${'$'}SG_QUIET//" >&2
echo "claude-remote: ${'$'}why — not starting a second tmux server (details: ${'$'}SG_DIR/sockheal.log)" >&2
exit 1
SOCKHEAL_EOF
            chmod +x "${'$'}SOCKHEAL.tmp.${'$'}${'$'}"
            S_NEW=1
        fi
        # ---- publish the scripts (v20, H10) ----
        if [ "${'$'}R_NEW" = 1 ]; then mv -f "${'$'}SCRIPT.tmp.${'$'}${'$'}" "${'$'}SCRIPT"; fi
        if [ "${'$'}D_NEW" = 1 ]; then mv -f "${'$'}DRIFT.tmp.${'$'}${'$'}" "${'$'}DRIFT"; fi
        if [ "${'$'}S_NEW" = 1 ]; then mv -f "${'$'}SOCKHEAL.tmp.${'$'}${'$'}" "${'$'}SOCKHEAL"; fi
        if [ "${'$'}R_NEW" = 1 ]; then echo "RESTORE_SCRIPT_INSTALLED"; else echo "RESTORE_SCRIPT_PRESENT"; fi
        if [ "${'$'}D_NEW" = 1 ]; then echo "DRIFT_SCRIPT_INSTALLED"; fi
        if [ "${'$'}S_NEW" = 1 ]; then echo "SOCKHEAL_SCRIPT_INSTALLED"; fi
        # ---- end of publishing the scripts ----
        # REMOVED (was: an OWNING claude-tmux.service that "ended a create-race").
        # The create-race theory is refuted — a second `tmux new-session` on the
        # same socket cannot kill a server, it just returns "duplicate session";
        # the real cause of the deaths is a tmux SIGSEGV (code=killed,
        # status=11/SEGV). And the unit was actively harmful: Type=forking on a
        # daemonizing binary lost the main PID (MainPID=0, so Restart=always
        # could never fire), its ExecStart/ExecStartPost failed whenever a server
        # already existed or __anchor__ didn't (NRestarts=96 flap loop), it
        # accumulated unrelated orphans under KillMode=none, and in the one state
        # where it DID own the server a `systemctl restart` — reachable from this
        # very install-on-connect path — would have killed every session.
        #
        # Clean up any previously-installed copy: disable + rm ONLY. Never
        # stop/restart it — in the state where it owns the server a stop kills
        # every session. Disabling keeps it from coming back after a boot; a
        # still-running instance is harmless until then.
        OWNED="${'$'}HOME/.config/systemd/user/claude-tmux.service"
        if [ -e "${'$'}OWNED" ] || [ -d "${'$'}OWNED.d" ]; then
            systemctl --user disable claude-tmux.service 2>/dev/null || true
            rm -f "${'$'}OWNED"
            rm -rf "${'$'}OWNED.d"
            systemctl --user daemon-reload 2>/dev/null || true
            echo "OWNED_UNIT_REMOVED"
        fi
        # Present = the unit runs our restore.sh (the unit's content is unchanged, so a written unit stays).
        if ! grep -qF "claude-remote/restore.sh" "${'$'}UNIT" 2>/dev/null; then
            cat > "${'$'}UNIT" <<UNIT_EOF
[Unit]
Description=Claude Remote — restore tmux+claude sessions on boot
After=default.target

[Service]
Type=oneshot
RemainAfterExit=yes
Environment=PATH=%h/.local/bin:%h/.npm-global/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=/usr/bin/env bash %h/.claude-remote/restore.sh

[Install]
WantedBy=default.target
UNIT_EOF
            systemctl --user daemon-reload 2>/dev/null || true
            systemctl --user enable claude-remote-restore.service 2>/dev/null || true
            loginctl enable-linger "${'$'}USER" 2>/dev/null || true
            echo "RESTORE_UNIT_INSTALLED"
        else
            echo "RESTORE_UNIT_PRESENT"
        fi
        if [ ! -f "${'$'}DUNIT" ] || [ ! -f "${'$'}DTIMER" ]; then
            cat > "${'$'}DUNIT" <<DUNIT_EOF
[Unit]
Description=Claude Remote — sync sessions.json with claude session_ids

[Service]
Type=oneshot
Environment=PATH=%h/.local/bin:%h/.npm-global/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=/usr/bin/env bash %h/.claude-remote/drift.sh
DUNIT_EOF
            cat > "${'$'}DTIMER" <<DTIMER_EOF
[Unit]
Description=Run claude-remote-drift every minute

[Timer]
OnBootSec=2min
OnUnitActiveSec=1min

[Install]
WantedBy=timers.target
DTIMER_EOF
            systemctl --user daemon-reload 2>/dev/null || true
            systemctl --user enable --now claude-remote-drift.timer 2>/dev/null || true
            echo "DRIFT_TIMER_INSTALLED"
        fi
        # Probe whether linger actually stuck — without it the user systemd
        # instance dies on logout and the restore unit never fires after reboot.
        # Most distros require polkit/sudo for `loginctl enable-linger`, so
        # the call above often fails silently. Surface the verdict in the log
        # so the client can warn the user once.
        LINGER=${'$'}(loginctl show-user "${'$'}USER" --property=Linger --value 2>/dev/null || echo unknown)
        echo "LINGER=${'$'}LINGER"
    """.trimIndent()

        /**
         * The last line of the installer's stdin ([installRestoreStdin]); it appears nowhere in
         * [INSTALL_RESTORE_COMMAND], so a stdin that ends with it arrived whole.
         */
        internal const val INSTALL_RESTORE_END = "# claude-remote-install-end"
        /** What [INSTALL_RESTORE_EXEC] prints (exit 97) when the installer did not arrive whole: nothing of it ran. */
        internal const val INSTALL_RESTORE_TRUNCATED = "INSTALL_TRUNCATED"
        /** What [prefixWrite] prints when the box's session prefix could not be written (the install still exits 0). */
        internal const val PREFIX_NOT_WRITTEN = "PREFIX_NOT_WRITTEN"

        /**
         * The exec request of the install; [INSTALL_RESTORE_COMMAND] itself (~80 KB) goes on the channel's stdin, paced
         * 4 KB / 20 ms like every upload. Sent as the request itself it was one burst of ~80 KB (23 KB compressed)
         * through the Cloudflare tunnel, after which the exec transport stopped opening channels until it died
         * (dávka C, K-1). The script is read to its end and run only when [INSTALL_RESTORE_END] arrived: a transport
         * lost midway closes the stdin early, and the first half of an installer must not run. It runs in bash (`eval`,
         * so it is never one argument again), as before on a server whose login shell is bash; the launcher needs bash.
         */
        internal const val INSTALL_RESTORE_EXEC =
            "bash -c 's=\$(cat); case \"\$s\" in *\"$INSTALL_RESTORE_END\") eval \"\$s\";; *) echo $INSTALL_RESTORE_TRUNCATED; exit 97;; esac'"

        /**
         * The stdin of [INSTALL_RESTORE_EXEC]: the installer, the write of this box's session [prefix] for drift.sh
         * ([prefixWrite]), then [INSTALL_RESTORE_END].
         */
        internal fun installRestoreStdin(prefix: String = TmuxNameParser.DEFAULT_PREFIX): ByteArray =
            (INSTALL_RESTORE_COMMAND + "\n" + prefixWrite(prefix) + "\n" + INSTALL_RESTORE_END + "\n").toByteArray(Charsets.UTF_8)

        /**
         * D6-S (kritika D2 N20): adds [prefix] to `~/.claude-remote/prefixes`, which drift.sh reads, on every install
         * (outside the marker, so a box already on the current scripts gets it too). The same check as drift's reader;
         * the default prefix needs no line (drift always has it, so the box `server` keeps no file); a prefix already in
         * the file is found before the lock (review D3 N11), any other is added under the manifest lock; the file is written whole (tmp + mv, valid lines only), because drift reads it without the lock and
         * must never see half a line. Never fails the install (it runs under `set -e`): a prefix not written only means
         * drift does not adopt this box's sessions yet. An unsupported name is logged to install.log.
         */
        internal fun prefixWrite(prefix: String): String = """
# ---- this box's session prefix for drift.sh (D6-S) ----
CR_PREFIX=${ManifestCommands.sq(prefix)}
$PREFIX_CHECK_FUNC
if ! cr_prefix_ok "${'$'}CR_PREFIX"; then
    echo "[${'$'}(date -u +%FT%TZ)] install: session prefix ${'$'}CR_PREFIX is not claude-<letters, digits, _ or ->- — drift does not adopt this box's sessions" >> "${'$'}HOME/.claude-remote/install.log" || true
    echo "PREFIX_UNSUPPORTED"
elif [ "${'$'}CR_PREFIX" != "${TmuxNameParser.DEFAULT_PREFIX}" ]; then
    (
        PF="${'$'}HOME/.claude-remote/prefixes"
        trap 'rm -f "${'$'}PF.tmp.${'$'}${'$'}"' EXIT
        # already there: no lock needed (the file only ever changes by mv), so a held lock never says NOT_WRITTEN
        grep -qxF -- "${'$'}CR_PREFIX" "${'$'}PF" 2>/dev/null && exit 0
        flock -w 5 9 || exit 1
        # mv onto a directory would move the file into it
        [ ! -d "${'$'}PF" ] || exit 1
        grep -qxF -- "${'$'}CR_PREFIX" "${'$'}PF" 2>/dev/null && exit 0
        {
            if [ -f "${'$'}PF" ]; then
                while IFS= read -r l || [ -n "${'$'}l" ]; do cr_prefix_ok "${'$'}l" && printf '%s\n' "${'$'}l"; done < "${'$'}PF"
            fi
            printf '%s\n' "${'$'}CR_PREFIX"
        } > "${'$'}PF.tmp.${'$'}${'$'}" || exit 1
        mv -f "${'$'}PF.tmp.${'$'}${'$'}" "${'$'}PF" || exit 1
        echo "PREFIX_ADDED ${'$'}CR_PREFIX"
    ) 9>>"${'$'}LOCK" || echo "$PREFIX_NOT_WRITTEN"
fi
# ---- end of the session prefix ----"""

        /**
         * Parse [ManifestCommands.closeState] output. Null means "the probe
         * didn't answer" (no verdict line: transport truncation, no shell, a
         * server without `flock`), which callers must treat as unknown and
         * fail open.
         *
         * `tracked` deliberately fails OPEN on a corrupt manifest — a garbled
         * sessions.json must never read as "this session was closed", because
         * that deletes a live tab. A well-formed but empty manifest DOES mean
         * untracked (that is the state right after a close).
         */
        /**
         * Parse [ManifestCommands.serverSnapshot] output: tombstoned tmux names,
         * a `---` line, then the manifest.
         *
         * Null means the read failed (no separator: truncated transport, no
         * shell, no flock) — callers must fail open and change nothing. A
         * corrupt manifest yields an EMPTY session list but keeps the tombstone
         * set: the two halves answer different questions and one being garbage
         * must not suppress the other.
         */
        internal fun parseServerSnapshot(
            raw: String,
            json: kotlinx.serialization.json.Json,
        ): ServerSnapshot? {
            val lines = raw.lines()
            val sepIdx = lines.indexOfFirst { it.trim() == ManifestCommands.CLOSE_STATE_SEPARATOR }
            if (sepIdx < 0) return null
            val tombstoned = lines.take(sepIdx).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val manifest = lines.drop(sepIdx + 1).joinToString("\n").trim()
            val sessions = if (manifest.isEmpty()) {
                emptyList()
            } else {
                try {
                    json.decodeFromString<List<PersistedSession>>(manifest)
                } catch (e: Exception) {
                    FileLogger.error(TAG, "server snapshot: manifest unparseable (${e.message})", null)
                    emptyList()
                }
            }
            return ServerSnapshot(sessions = sessions, tombstoned = tombstoned)
        }

        internal fun parseCloseState(
            raw: String,
            tmuxSessionName: String,
            json: kotlinx.serialization.json.Json,
        ): RemoteCloseState? {
            val lines = raw.lines()
            val verdictIdx = lines.indexOfFirst { it.trimStart().startsWith(ManifestCommands.TOMBSTONED_PREFIX) }
            if (verdictIdx < 0) return null
            val tombstoned = lines[verdictIdx].trim()
                .removePrefix(ManifestCommands.TOMBSTONED_PREFIX).trim() == "1"
            val sepIdx = lines.drop(verdictIdx + 1)
                .indexOfFirst { it.trim() == ManifestCommands.CLOSE_STATE_SEPARATOR }
            val manifest = if (sepIdx < 0) "" else lines.drop(verdictIdx + 1 + sepIdx + 1).joinToString("\n").trim()
            val tracked = if (manifest.isEmpty()) {
                false
            } else {
                try {
                    json.decodeFromString<List<PersistedSession>>(manifest)
                        .any { it.tmuxSessionName == tmuxSessionName }
                } catch (_: Exception) {
                    true
                }
            }
            return RemoteCloseState(tracked = tracked, tombstoned = tombstoned)
        }
    }

    /**
     * Track which servers we've already attempted install on this app session
     * to avoid pinging on every persist. Best-effort — if the install fails
     * (no systemd, e.g. macOS, BSD), the in-app reconnect path still works.
     */
    private val installedRestoreServers = mutableSetOf<String>()
    /** Installs running right now, so 20 tabs launching at once run one; moved to [installedRestoreServers] only on success. */
    private val installingRestoreServers = mutableSetOf<String>()

    private fun beginRestoreInstall(serverId: String): Boolean = synchronized(installedRestoreServers) {
        serverId !in installedRestoreServers && installingRestoreServers.add(serverId)
    }

    /** A failed install (no transport, an exception) is not remembered: the next launch on the server tries again. */
    private fun endRestoreInstall(serverId: String, ok: Boolean) = synchronized(installedRestoreServers) {
        installingRestoreServers.remove(serverId)
        if (ok) installedRestoreServers.add(serverId)
    }

    /**
     * Pull the authoritative `~/.claude-remote/sessions.json` from the
     * server and reconcile this tab's claudeSessionId with whatever the
     * server-side drift daemon has recorded. Replaces the older per-pid
     * probe — the server now owns the truth, the client just mirrors it.
     */
    fun startSessionIdRefresh(sessionId: String, tmuxName: String, sshManager: SshManager) {
        if (sessionStorage == null) return
        // The per-server reconciler (one exec per server per round) replaces this per-tab loop.
        if (registry.reconciler != null && registry.serverIdOf(sessionId)?.let { registry.execActive(it) } == true) return
        sessionIdRefreshJobs[sessionId]?.cancel()
        sessionIdRefreshJobs[sessionId] = scope.launch {
            kotlinx.coroutines.delay(1000)
            while (isActive) {
                // Skip in background: this is 2 SSH execs every 15s per session
                // (~104 radio wakeups/min at 13 sessions) and only matters for
                // UUID/restore correctness, which onResume refreshes anyway.
                if (isBackground()) { kotlinx.coroutines.delay(15_000); continue }
                // The kill switch with shells still packed on the transport: this tick's channels (one at a time) are
                // counted against it, and a tick without room is skipped.
                val serverId = registry.serverIdOf(sessionId)
                val tickSession = sshManager.getSession()
                val tickChannel = if (serverId != null && tickSession != null) registry.reserveTabChannel(serverId, tickSession) else null
                if (serverId != null && tickSession != null && tickChannel == null) {
                    registry.noteSkipped("session-id", serverId)
                    kotlinx.coroutines.delay(15_000)
                    continue
                }
                try {
                    val remote = fetchSessionsCached(serverId, sshManager)
                    // CLOSED ON ANOTHER DEVICE, noticed without a disconnect.
                    // Killing the pane drops this device's `tmux attach`, but
                    // the SSH shell underneath survives — so onConnectionLost
                    // never fires, autoReconnect never runs, and the only code
                    // that checks for a close never got a chance. The tab then
                    // sat there, live-looking, until something else broke the
                    // connection (hours, on a desktop). This loop is already
                    // talking to the server every 15 s, so let it answer the
                    // question too.
                    if (remote != null && remote.tombstoned.contains(tmuxName)) {
                        // Two facts before deleting anything: the server says
                        // closed AND the pane is really gone. probeTmuxAlive
                        // fails OPEN (alive), so a flaky link keeps the tab.
                        if (!probeTmuxAlive(sshManager, tmuxName)) {
                            FileLogger.log(TAG, "Tmux '$tmuxName' tombstoned and gone — closed on another device, forgetting locally")
                            forgetClosedElsewhere(sessionId, tmuxName)
                            return@launch
                        }
                    }
                    val entry = remote?.sessions?.firstOrNull { it.tmuxSessionName == tmuxName }
                    // Prefer the LIVE pane's running Claude pid over the server's
                    // sessions.json record. The server entry is seeded with the
                    // client-generated launch UUID and the drift daemon may not
                    // have caught a rotation (or recorded the wrong pid) — a
                    // stale-but-present server value would mask the real UUID and
                    // actively fight the transcript kick-probe, reverting the
                    // corrected UUID every 15 s. The pid-probe reads what Claude
                    // is writing right now, so it's ground truth for a connected
                    // tab; the server entry is only a fallback when the pane
                    // can't be resolved.
                    val realUuid = readRealSessionId(sshManager, tmuxName)
                        ?: entry?.claudeSessionId.takeUnless { it.isNullOrBlank() }
                    if (!realUuid.isNullOrBlank()) {
                        adoptUuid(sessionId, realUuid, if (entry?.claudeSessionId == realUuid) "server" else "pid-probe")
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) { tickChannel?.release() }
                }
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    /**
     * A session another device closed: drop the local record and the tab, and prune the
     * UI's remote-tmux snapshot. Shared by the legacy per-tab loop and the reconciler.
     */
    suspend fun forgetClosedElsewhere(sessionId: String, tmuxName: String) {
        sessionStorage?.remove(sessionId)
        val serverId = tabManager.getTab(sessionId)?.server?.id
        disconnectSession(sessionId)
        if (serverId != null) onForgotten(serverId, tmuxName)
    }

    /**
     * Adopt [realUuid] as the claude session id of [sessionId] when it differs from the
     * tab's. Refuses a UUID already owned by another tab — two transcript streams
     * pointing at the same jsonl would mirror identical content across both sessions
     * ("občas se chat historie zobrazuje stejně"); that happens when the user picks the
     * same /resume conversation in two tabs, or when a tmux name collision lets the
     * drift daemon attribute one claude pid to two tab rows. Shared by the legacy
     * per-tab loop and the reconciler.
     */
    suspend fun adoptUuid(sessionId: String, realUuid: String, source: String, confirmWhenEqual: Boolean = false) {
        val storage = sessionStorage ?: return
        val tab = tabManager.getTab(sessionId) ?: return
        if (tab.claudeSessionId == realUuid) {
            // Already the tab's id: a live probe agreeing with it confirms it (transcriptFlow's kick-sync then skips).
            if (confirmWhenEqual) transcript.setConfirmedUuid(sessionId, realUuid)
            return
        }
        val claimedByOther = tabManager.tabs.value.any {
            it.id != sessionId && it.claudeSessionId == realUuid
        }
        if (claimedByOther) {
            FileLogger.log(TAG, "Skip UUID drift $realUuid for $sessionId — already owned by another tab")
            return
        }
        FileLogger.log(TAG, "Session $sessionId UUID synced from $source: ${tab.claudeSessionId} -> $realUuid")
        tabManager.updateClaudeSessionId(sessionId, realUuid)
        storage.upsert(SessionStorage.fromClaudeSession(tab.copy(claudeSessionId = realUuid)))
        transcript.notifyClaudeSessionIdChanged(sessionId, realUuid)
        transcript.setConfirmedUuid(sessionId, realUuid)
    }

    /**
     * The exec-routed reconciler, bound to this service and the tab/transcript state.
     * The wiring step installs it into `ConnectionRegistry.reconciler`.
     */
    fun newExecReconciler(
        onRound: (serverId: String, snapshot: ReconcileSnapshot) -> Unit = { _, _ -> },
    ): ServerReconciler = ExecServerReconciler(
        scope = scope,
        run = { serverId, cmd, totalMs -> registry.execRead(serverId, cmd, totalMs) },
        serverIdOf = { registry.serverIdOf(it) },
        tmuxNameOf = { tabManager.getTab(it)?.tmuxSessionName },
        tabUuidOf = { tabManager.getTab(it)?.claudeSessionId },
        adoptUuid = { sid, uuid, source -> adoptUuid(sid, uuid, source, confirmWhenEqual = source == "pid-probe") },
        forget = { sid ->
            val name = tabManager.getTab(sid)?.tmuxSessionName
            if (name != null) forgetClosedElsewhere(sid, name)
        },
        isBackground = isBackground,
        onRound = onRound,
        livePrefixesOf = ::livePrefixes,
    )

    /**
     * Read the server's shared state — the manifest AND the tombstone set —
     * under a shared file lock (so we never read mid-write from the drift
     * daemon or restore.sh). Returns null on transport failure, empty
     * sessions on a missing file or parse error.
     */
    private suspend fun fetchSessionsFromServer(sshManager: SshManager): ServerSnapshot? {
        return withContext(Dispatchers.IO) {
            try {
                val sshSession = sshManager.getSession() ?: return@withContext null
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(ManifestCommands.serverSnapshot())
                ch.inputStream = null
                val input = ch.inputStream
                val out = try {
                    ch.connect(3000)
                    input.bufferedReader().readText()
                } finally {
                    try { ch.disconnect() } catch (_: Exception) {}
                }
                parseServerSnapshot(out, fetchJson)
            } catch (e: Exception) {
                FileLogger.error(TAG, "fetchSessionsFromServer failed: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Both server-side facts the reconnect decision needs about one tmux name,
     * in a SINGLE exec: did some device tombstone it (a real user close), and
     * is it still in the restore manifest.
     *
     * The tombstone half exists because the manifest ALONE cannot answer
     * "closed elsewhere" without a race: the closing device kills the tmux
     * pane, and the peer's attach dies (and its auto-reconnect fires) long
     * before that device's manifest push lands. The peer then read a manifest
     * that still listed the session, concluded "nobody closed it", and
     * rebuilt the pane with `claude --resume` — the session the user just
     * closed reappearing a moment later. [forgetSession] now writes the
     * tombstone BEFORE killing tmux, so whatever the manifest says, the
     * tombstone is already on disk by the time any peer can notice the death.
     *
     * Returns null when the probe itself failed (transport, no shell, no
     * output). Callers MUST fail open on null — "still tracked, not
     * tombstoned" — so a network blip can never be mistaken for a close and
     * drop a tab that is still legitimately tracked.
     */
    suspend fun fetchCloseState(sshManager: SshManager, tmuxSessionName: String): RemoteCloseState? {
        return withContext(Dispatchers.IO) {
            try {
                val sshSession = sshManager.getSession() ?: return@withContext null
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(ManifestCommands.closeState(tmuxSessionName))
                ch.inputStream = null
                val input = ch.inputStream
                val out = try {
                    ch.connect(3000)
                    input.bufferedReader().readText()
                } finally {
                    try { ch.disconnect() } catch (_: Exception) {}
                }
                parseCloseState(out, tmuxSessionName, fetchJson)
            } catch (e: Exception) {
                FileLogger.error(TAG, "fetchCloseState failed: ${e.message}", e)
                null
            }
        }
    }

    /** D6-K: the prefixes whose live session proves [serverId]'s tmux is up: its own (by its name) and the default. */
    private fun livePrefixes(serverId: String): Set<String> =
        com.clauderemote.model.TmuxNameParser.livePrefixes(serverStorage.getServer(serverId)?.name)

    /** D6-S: the session prefix the installer writes for drift.sh: the box's own (by its name); the default for an unknown box. */
    private fun installPrefix(serverId: String?): String =
        serverId?.let { serverStorage.getServer(it)?.name }?.let(TmuxNameParser::prefixFor) ?: TmuxNameParser.DEFAULT_PREFIX

    /** `by` of a close record: a user close is this install's, a prune (not user-initiated) is `prune`. */
    private fun closeRecordBy(userInitiated: Boolean): String = if (userInitiated) closedBy() else "prune"

    /**
     * Drop every tombstone for [tmuxSessionName]. Called when the user
     * deliberately launches or attaches this tmux name — positive intent that
     * the name is alive again, which un-poisons a reused name (see
     * [ManifestCommands.clearTombstone]).
     */
    suspend fun clearTombstone(sshManager: SshManager, tmuxSessionName: String) {
        runShellCommand(
            sshManager,
            ManifestCommands.clearTombstone(tmuxSessionName),
            "clearTombstone($tmuxSessionName)",
        )
    }

    /**
     * Fire-and-wait exec for the small state-file commands above: waits up to [waitMs] for the command to end.
     * Returns its stdout, or null when it could not run (no session, an exception).
     */
    private suspend fun runShellCommand(sshManager: SshManager, command: String, label: String, waitMs: Long = 5_000): String? {
        return withContext(Dispatchers.IO) {
            try {
                val sshSession = sshManager.getSession() ?: return@withContext null
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(command)
                ch.inputStream = null
                val input = ch.inputStream
                ch.connect(5000)
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + waitMs
                while (System.currentTimeMillis() < deadline) {
                    while (input.available() > 0) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    if (ch.isClosed && input.available() <= 0) break
                    kotlinx.coroutines.delay(50)
                }
                val exit = ch.exitStatus
                ch.disconnect()
                if (exit != 0) FileLogger.error(TAG, "$label exited with $exit", null)
                out.toString(Charsets.UTF_8)
            } catch (e: Exception) {
                FileLogger.error(TAG, "$label failed: ${e.message}", e)
                null
            }
        }
    }

    /**
     * [fetchSessionsFromServer] with a per-server TTL cache. All sessions on a
     * server reconcile against the SAME sessions.json every 15 s — only the
     * first one inside the TTL pays the exec, the rest reuse the snapshot.
     * Transport failures (null) are never cached, so a fresh session retries.
     */
    private suspend fun fetchSessionsCached(
        serverId: String?,
        sshManager: SshManager,
    ): ServerSnapshot? {
        if (serverId == null) return fetchSessionsFromServer(sshManager)
        val ttl = 12_000L // just under the 15 s loop period
        sessionsJsonCache[serverId]?.let {
            if (System.currentTimeMillis() - it.at < ttl) return it.snapshot
        }
        return sessionsJsonMutex.withLock {
            // Re-check under the lock — another session's tick may have just
            // refreshed it while we waited.
            sessionsJsonCache[serverId]?.let {
                if (System.currentTimeMillis() - it.at < ttl) return@withLock it.snapshot
            }
            val fresh = fetchSessionsFromServer(sshManager)
            if (fresh != null) {
                sessionsJsonCache[serverId] = SessionsSnapshot(System.currentTimeMillis(), fresh)
            }
            fresh
        }
    }

    suspend fun ensureRestoreService(sshManager: SshManager) {
        val serverId = tabManager.tabs.value.firstOrNull { registry.ssh(it.id) === sshManager }?.server?.id
        if (serverId != null && !beginRestoreInstall(serverId)) return
        var ok = false
        try {
            val sshSession = sshManager.getSession()
            // the installer on stdin, paced (K-1), as on the exec transport
            val res = if (sshSession == null) null else PooledRemoteExec(UngatedExecChannels { sshSession })
                .run(serverId.orEmpty(), INSTALL_RESTORE_EXEC, installRestoreStdin(installPrefix(serverId)), totalMs = 15_000, paceStdin = Pace())
            ok = restoreInstallRan(res) == true
        } catch (e: Exception) {
            FileLogger.error(TAG, "Restore service install failed: ${e.message}", e)
        } finally {
            if (serverId != null) endRestoreInstall(serverId, ok)
        }
    }

    /**
     * [ensureRestoreService] by server id, over the exec transport (no tab shell needed). Once per server per app
     * run, once it succeeded (exit status 0). False = there was no usable transport, or the installer did not arrive
     * whole (nothing was installed, nothing is remembered): the caller may ask again when one is up. A command that ran
     * and failed is not remembered either, but true: asking again at once would fail the same way.
     */
    suspend fun ensureRestoreService(serverId: String): Boolean {
        if (!beginRestoreInstall(serverId)) return true
        var ran: Boolean? = null
        try {
            // the installer on stdin, paced (K-1): never one ~80 KB exec request through the tunnel
            ran = restoreInstallRan(registry.execRun(serverId, INSTALL_RESTORE_EXEC, stdin = installRestoreStdin(installPrefix(serverId)), totalMs = 15_000, paceStdin = Pace()))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.error(TAG, "Restore service install failed: ${e.message}", e)
            return true // a real failure of the command: asking again at once would fail the same way
        } finally {
            endRestoreInstall(serverId, ran == true)
        }
        return ran != null
    }

    /**
     * Logs one install and judges it: true = it ran to its end (exit status 0: remembered), false = it ran and failed
     * (not remembered), null = it never ran (no transport, or the installer did not arrive whole: [INSTALL_RESTORE_TRUNCATED]
     * as the last line, whatever a login banner printed before it). An install that could not write the box's session
     * prefix (`PREFIX_NOT_WRITTEN`, adv N5) still exits 0 but is not remembered either: the next launch writes it again.
     */
    private fun restoreInstallRan(res: ExecResult?): Boolean? {
        val out = res?.stdout?.trim() ?: "NO_SESSION"
        logRestoreInstall(out)
        if (res == null || out.lineSequence().last().trim() == INSTALL_RESTORE_TRUNCATED) return null
        if (res.exitStatus != 0) FileLogger.error(TAG, "Restore service install exited with ${res.exitStatus} — not remembered, the next launch installs again", null)
        else if (out.lineSequence().any { it.trim() == PREFIX_NOT_WRITTEN }) {
            FileLogger.error(TAG, "Restore service install could not write the box's session prefix — not remembered, the next launch writes it again", null)
            return false
        }
        return res.exitStatus == 0
    }

    private fun logRestoreInstall(out: String) {
        FileLogger.log(TAG, "Restore service install: $out")
        if (out.contains("LINGER=no") || out.contains("LINGER=unknown")) {
            FileLogger.log(TAG,
                "WARNING: linger is not enabled on the server — the restore service " +
                "will only fire after a manual login. Run `sudo loginctl enable-linger \$USER` " +
                "on the server to make session persistence work after reboot."
            )
        }
    }

    /**
     * Push the per-server `sessions.json` snapshot to the remote server via
     * `cat > tmp && mv tmp final` (atomic rename). The systemd restore unit
     * reads this file at boot.
     */
    /**
     * F5d (rev LOW2): runs before every manifest push of a server — the orchestrator checks this device's model of
     * Jarvis' account against his status first, so a stale slot is never pushed. Null = nothing to check.
     */
    @Volatile var beforePush: (suspend (serverId: String) -> Unit)? = null

    suspend fun pushSessionsToServer(sshManager: SshManager, serverId: String) {
        val storage = sessionStorage ?: return
        beforePush?.invoke(serverId)
        try {
            val payload = storage.serializeForServer(serverId)
            withContext(Dispatchers.IO) {
                val sshSession = sshManager.getSession() ?: return@withContext
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                // The merge semantics (and why this is a merge at all) live in
                // ManifestCommands.pushMerge, which is a pure builder so the
                // shell can be bash -n'd by ManifestCommandSyntaxTest.
                ch.setCommand(ManifestCommands.pushMerge(serverId, payload.length, livePrefixes(serverId)))
                ch.inputStream = null
                val os = ch.outputStream
                ch.connect(5000)
                os.write(payload.toByteArray(Charsets.UTF_8))
                os.flush()
                os.close()
                val deadline = System.currentTimeMillis() + 5000
                while (!ch.isClosed && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(50)
                }
                val exit = ch.exitStatus
                ch.disconnect()
                if (exit != 0) {
                    FileLogger.error(
                        TAG,
                        "sessions.json sync exec exited with $exit for $serverId — restore service may use stale data",
                        null
                    )
                    return@withContext
                }
            }
            FileLogger.log(TAG, "Synced sessions.json to server $serverId (${payload.length} bytes)")
        } catch (e: Exception) {
            FileLogger.error(TAG, "sessions.json sync failed for $serverId: ${e.message}", e)
        }
    }

    /**
     * [pushSessionsToServer] by server id, over the exec transport (falling back to a live
     * tab session): the merge payload goes to stdin, paced like every upload. A parked tab
     * can therefore still re-sync the manifest (rename, close).
     */
    suspend fun pushSessionsToServer(serverId: String): Boolean {
        val storage = sessionStorage ?: return true
        beforePush?.invoke(serverId)
        try {
            val payload = storage.serializeForServer(serverId)
            val res = registry.execRun(
                serverId,
                ManifestCommands.pushMerge(serverId, payload.length, livePrefixes(serverId)),
                stdin = payload.toByteArray(Charsets.UTF_8),
                totalMs = 15_000,
            )
            when {
                res == null -> {
                    FileLogger.log(TAG, "sessions.json sync skipped for $serverId: no usable transport")
                    return false
                }
                res.exitStatus != 0 -> FileLogger.error(
                    TAG,
                    "sessions.json sync exec exited with ${res.exitStatus} for $serverId — restore service may use stale data",
                    null
                )
                else -> FileLogger.log(TAG, "Synced sessions.json to server $serverId (${payload.length} bytes)")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.error(TAG, "sessions.json sync failed for $serverId: ${e.message}", e)
        }
        return true
    }

    /**
     * Drop every tombstone of [tmuxSessionName] over the exec transport, [clearTombstone] by server id. False =
     * no usable transport (retry later); a command that exited non-zero is logged like the legacy variant does.
     */
    suspend fun clearTombstone(serverId: String, tmuxSessionName: String): Boolean {
        val res = try {
            registry.execRun(serverId, ManifestCommands.clearTombstone(tmuxSessionName), totalMs = 5_000)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.error(TAG, "clearTombstone($tmuxSessionName) failed: ${e.message}", e)
            return true
        }
        if (res == null) {
            FileLogger.log(TAG, "clearTombstone($tmuxSessionName) skipped for $serverId: no usable transport")
            return false
        }
        if (res.exitStatus != 0) FileLogger.error(TAG, "clearTombstone($tmuxSessionName) exited with ${res.exitStatus}", null)
        return true
    }

    /**
     * Server-side half of [forgetSession] over the exec transport: tombstone FIRST, then
     * kill the pane, then re-sync the manifest. False = the exec path is not usable (no
     * transport), the caller then uses a fresh connection. A failure after the tombstone
     * is logged like the legacy path logs it; the tombstone is idempotent, so a fallback
     * that repeats it is harmless.
     */
    private suspend fun cleanupViaExec(session: ClaudeSession, userInitiated: Boolean): Boolean {
        val serverId = session.server.id
        val name = session.tmuxSessionName
        try {
            // Tombstone, the close record (it reads the live pane, §15.12) and the kill in ONE exec, in that order: a
            // second exec for the kill may never start (the desktop exit waits only so long), and a close cut there
            // left the session running under a tombstone, with no tab and no manifest entry.
            // D3: the ~6 KB script on stdin (StdinScript), run only when it arrived whole.
            val res = registry.execRun(
                serverId,
                StdinScript.EXEC,
                stdin = StdinScript.stdin(ManifestCommands.closeCommand(name, durable = userInitiated, by = closeRecordBy(userInitiated))),
                totalMs = CLOSE_EXEC_MS,
                paceStdin = Pace(),
            )
                ?: return false
            val done = StdinScript.parse(res.stdout) as? StdinScript.Outcome.Completed
            if (done == null) {
                // no end marker: cut short (nothing ran) or its end unseen — the fallback runs it again, which is safe:
                // the tombstone and the kill are idempotent and a gone pane writes no second record
                FileLogger.log(TAG, "Exec close of ${session.id} ($name) did not complete (exit ${res.exitStatus}) — using a fresh connection")
                return false
            }
            if (done.status != 0) FileLogger.error(TAG, "tombstone($name) exited with ${done.status}", null)
            if (ManifestCommands.closeKillStatus(done.out) != 0) {
                FileLogger.error(TAG, "Tmux kill returned failure for ${session.id} ($name) — pane may still be alive", null)
            }
            pushSessionsToServer(serverId)
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ExecUnavailable) {
            FileLogger.log(TAG, "Exec cleanup for ${session.id} unavailable (${e.reason}) — using a fresh connection")
            return false
        }
    }

    /**
     * [forgetSession]'s fallback close on [conn], a connection of its own: [ManifestCommands.closeCommand] on stdin
     * ([StdinScript], D3). Its output, or null when it did not complete (no session, cut short, no end marker) — a
     * failure the caller logs. A tombstone that failed is logged here, as [cleanupViaExec] logs it.
     */
    private suspend fun closeOnSession(conn: SshManager, name: String, userInitiated: Boolean): String? {
        val label = "close($name, durable=$userInitiated)"
        return try {
            val sess = conn.getSession() ?: return null
            val res = PooledRemoteExec(UngatedExecChannels { sess }).run(
                "", StdinScript.EXEC,
                StdinScript.stdin(ManifestCommands.closeCommand(name, userInitiated, closeRecordBy(userInitiated))),
                totalMs = CLOSE_EXEC_MS, paceStdin = Pace(),
            )
            when (val done = StdinScript.parse(res.stdout)) {
                is StdinScript.Outcome.Completed -> {
                    if (done.status != 0) FileLogger.error(TAG, "$label exited with ${done.status}", null)
                    done.out
                }
                StdinScript.Outcome.Incomplete -> {
                    FileLogger.error(TAG, "$label did not complete (exit ${res.exitStatus}): ${res.stdout.trim().takeLast(200)}", null)
                    null
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.error(TAG, "$label failed: ${e.message}", e)
            null
        }
    }

    /**
     * Forget a session permanently (used when user explicitly closes a tab,
     * not just disconnects). Removes the persisted record so it won't be
     * resurrected on next app start, and re-syncs the server-side
     * sessions.json so systemd doesn't try to restore it after reboot.
     *
     * Returns the background server-side cleanup (null when there was no tab to clean up), so a caller that is
     * about to end the process — the desktop exit flushing pending closes — can wait for the tombstone and kill.
     */
    suspend fun forgetSession(sessionId: String, userInitiated: Boolean = false): kotlinx.coroutines.Job? {
        val session = tabManager.getTab(sessionId)
        // Capture identity up front so we can prune the UI's stale remote-tmux
        // snapshot even after the tab is torn down below.
        val forgottenServerId = session?.server?.id
        val forgottenTmuxName = session?.tmuxSessionName

        // 1. IMMEDIATE local teardown. The server-side cleanup below can take
        //    tens of seconds on a slow network (cleanup connect timeout + tmux
        //    kill + sessions.json push). The old order ran cleanup FIRST and
        //    removed the tab in a finally — so a "closed" tab stayed visible
        //    (and switchable!) until cleanup finished, then vanished from under
        //    the user mid-use.
        sessionStorage?.remove(sessionId)
        disconnectSession(sessionId)
        // Prune the UI's stale remote-tmux snapshot so the killed pane
        // doesn't reappear as a "detached remote" row.
        if (forgottenServerId != null && !forgottenTmuxName.isNullOrBlank()) {
            onForgotten(forgottenServerId, forgottenTmuxName)
        }

        // 2. Server-side cleanup in the BACKGROUND, best-effort: kill the tmux
        //    pane and re-sync sessions.json so the systemd restore service
        //    doesn't re-materialise the "closed" session after a reboot. Opens
        //    its own short-lived connection — the tab's live connection was
        //    already torn down above, and reusing it would keep its read loop
        //    (and onConnectionLost → autoReconnect) alive for a dead tab.
        var cleanup: kotlinx.coroutines.Job? = null
        if (session != null) {
            noteClosedLocally(session.server.id, session.tmuxSessionName)
            cleanup = scope.launch {
                if (registry.execActive(session.server.id) && cleanupViaExec(session, userInitiated)) return@launch
                val cleanupConn: SshManager? = try {
                    val tmp = SshManager(serverStorage)
                    tmp.connectForCleanup(session.server)
                    tmp
                } catch (e: Exception) {
                    FileLogger.log(TAG, "Cleanup SSH connect failed for $sessionId (${e.message}) — server-side cleanup skipped")
                    null
                }
                try {
                    if (cleanupConn != null) {
                        // TOMBSTONE FIRST — before the kill, not after. Killing
                        // the pane is what every OTHER device notices: its
                        // attach dies instantly and auto-reconnect fires within
                        // a second, while this device still has a tmux kill and
                        // a manifest push to go. With the tombstone written
                        // afterwards, that peer probed a server that still
                        // listed the session as tracked, concluded "nobody
                        // closed it" and rebuilt the pane with `claude
                        // --resume` — the close appeared to work and the
                        // session came back moments later. Writing it first
                        // makes the close visible to peers before the death
                        // they react to.
                        // Tombstone, record and kill in one exec, as on the exec path above — and on stdin the same
                        // way (D3), as the restore installer goes on a session of its own (C0)
                        val out = closeOnSession(cleanupConn, session.tmuxSessionName, userInitiated)
                        if (out == null || ManifestCommands.closeKillStatus(out) != 0) {
                            FileLogger.error(TAG, "Tmux kill returned failure for $sessionId (${session.tmuxSessionName}) — pane may still be alive", null)
                        }
                        try {
                            pushSessionsToServer(cleanupConn, session.server.id)
                        } catch (e: Exception) {
                            FileLogger.error(TAG, "sessions.json push failed for ${session.server.id}: ${e.message}", e)
                        }
                    }
                } finally {
                    try { cleanupConn?.disconnect() } catch (_: Exception) {}
                }
            }
        }
        return cleanup
    }

    suspend fun renameTmuxSession(sessionId: String, oldName: String, newName: String) {
        withContext(Dispatchers.IO) {
            try {
                val serverId = registry.serverIdOf(sessionId)
                if (serverId != null && registry.execActive(serverId)) {
                    // Exec transport: a parked tab (no shell of its own) can be renamed too.
                    val res = registry.execRun(serverId, ManifestCommands.renameSession(oldName, newName), totalMs = 5_000)
                    if (res == null || res.exitStatus != 0) {
                        FileLogger.error(TAG, "Tmux rename $oldName → $newName failed (${res?.exitStatus ?: "no transport"})", null)
                        return@withContext
                    }
                    FileLogger.log(TAG, "Tmux renamed: $oldName → $newName")
                    val tab = tabManager.getTab(sessionId)
                    if (tab != null && sessionStorage != null) {
                        sessionStorage.upsert(SessionStorage.fromClaudeSession(tab.copy(tmuxSessionName = newName)))
                        pushSessionsToServer(serverId)
                    }
                    return@withContext
                }
                val sshSession = registry.ssh(sessionId)?.getSession() ?: return@withContext
                com.clauderemote.connection.TmuxManager.renameSession(sshSession, oldName, newName)
                FileLogger.log(TAG, "Tmux renamed: $oldName → $newName")
                // Persist the new tmux name + re-sync server snapshot so the
                // restore service uses it after a reboot.
                val tab = tabManager.getTab(sessionId)
                if (tab != null && sessionStorage != null) {
                    val updated = tab.copy(tmuxSessionName = newName)
                    sessionStorage.upsert(SessionStorage.fromClaudeSession(updated))
                    registry.ssh(sessionId)?.let { pushSessionsToServer(it, tab.server.id) }
                }
            } catch (e: Exception) {
                FileLogger.error(TAG, "Tmux rename failed", e)
            }
        }
    }

    /**
     * Rehydrate persisted sessions on app start. Returns the list of
     * ClaudeSessions that were restored into [tabManager] (status =
     * CONNECTING). Caller is responsible for triggering reconnectSession()
     * for each, which will probe tmux and either attach or rebuild via
     * `claude --resume <uuid>`.
     */
    @Volatile private var restoreDone = false

    fun restorePersistedTabs(): List<ClaudeSession> {
        val storage = sessionStorage ?: return emptyList()
        // Idempotent — if the host (Activity, app window) calls this twice
        // (e.g. Android configuration change re-runs initApp), we mustn't
        // duplicate tabs or fan out parallel reconnects to the same tmux.
        if (restoreDone) return emptyList()
        restoreDone = true
        val persisted = storage.load()
        if (persisted.isEmpty()) return emptyList()
        val existingIds = tabManager.tabs.value.map { it.id }.toSet()
        val rehydrated = persisted.mapNotNull { p ->
            if (p.id in existingIds) return@mapNotNull null
            val cs = SessionStorage.toClaudeSession(p, serverStorage)
            if (cs == null) {
                FileLogger.log(TAG, "Dropping persisted session ${p.id}: server ${p.serverId} not found")
                storage.remove(p.id)
            }
            cs
        }
        rehydrated.forEach { session ->
            terminalIO.initBuffer(session.id)
            tabManager.addTab(session)
            tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
            onActivityUpdate(session.id, SessionActivity.DISCONNECTED)
        }
        FileLogger.log(TAG, "Restored ${rehydrated.size} persisted sessions to tabs")
        return rehydrated
    }

    /** Cancel this session's sessions.json UUID-refresh poller (called from disconnectSession). */
    /** The per-tab session-id loop of [sessionId] is running (tests: exactly one of it and the per-server reconcile may run). */
    internal fun idRefreshActive(sessionId: String): Boolean = sessionIdRefreshJobs[sessionId]?.isActive == true

    fun stopIdRefresh(sessionId: String) {
        sessionIdRefreshJobs.remove(sessionId)?.cancel()
    }
}
