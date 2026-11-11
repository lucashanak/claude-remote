        set -e
        mkdir -p "$HOME/.claude-remote" "$HOME/.config/systemd/user"
        SCRIPT="$HOME/.claude-remote/restore.sh"
        DRIFT="$HOME/.claude-remote/drift.sh"
        SOCKHEAL="$HOME/.claude-remote/tmux-sockheal.sh"
        UNIT="$HOME/.config/systemd/user/claude-remote-restore.service"
        DUNIT="$HOME/.config/systemd/user/claude-remote-drift.service"
        DTIMER="$HOME/.config/systemd/user/claude-remote-drift.timer"
        LOCK="$HOME/.claude-remote/sessions.lock"
        MARKER="claude-remote-restore-v19"
        # An install cut short (set -e, a dropped SSH session) must not leave a temp script behind.
        trap 'rm -f "$SCRIPT.tmp.$$" "$DRIFT.tmp.$$" "$SOCKHEAL.tmp.$$" "$SCRIPT.v18.tmp.$$" "$DRIFT.v18.tmp.$$"' EXIT
        touch "$LOCK"
        echo "[$(date -u +%FT%TZ)] install: invoked by client" >> "$HOME/.claude-remote/install.log"
        if ! grep -q "$MARKER" "$SCRIPT" 2>/dev/null; then
            echo "[$(date -u +%FT%TZ)] install: restore.sh missing $MARKER — rewriting; prior marker line: $(grep -m1 'claude-remote-restore-v' "$SCRIPT" 2>/dev/null || echo '<none/new file>')" >> "$HOME/.claude-remote/install.log"
            # Atomic: a fresh inode renamed into place. `cat >` truncated the file a running bash may be reading
            # block by block, which could execute a mix of the old and the new script.
            cat > "$SCRIPT.tmp.$$" <<'RESTORE_EOF'
#!/usr/bin/env bash
# marker-compat (do NOT remove): claude-remote-restore-v6 claude-remote-restore-v7 claude-remote-restore-v8 claude-remote-restore-v9 claude-remote-restore-v10 claude-remote-restore-v11 claude-remote-restore-v12 claude-remote-restore-v13 claude-remote-restore-v14 claude-remote-restore-v15 claude-remote-restore-v16 claude-remote-restore-v17 claude-remote-restore-v18 claude-remote-restore-v19
# Makes any installed client's `grep -q $MARKER` find its marker so it takes the
# PRESENT no-op path and does NOT rewrite this file (reverting the fixes) nor run
# the daemon-reload/enable reinstall that correlates with the tmux-server death.
# claude-remote-restore-v16 — recreates tmux+claude sessions from sessions.json (snapshot under flock)
set -u
# Any tmux server started from here (incl. auto-start by `tmux new-session`,
# which does NOT inherit the anchor scope's LimitCORE) must be able to dump a
# core: the deaths are tmux SIGSEGV and a backtrace is the only way past theory.
# core_pattern is read-only in this LXC, so the core lands as ./core in cwd.
ulimit -c unlimited 2>/dev/null || true
cd "$HOME/.claude-remote/cores" 2>/dev/null || true
LOG="$HOME/.claude-remote/restore.log"
exec >> "$LOG" 2>&1
echo "----- $(date -u +%FT%TZ) restore.sh start (pid=$$) -----"
# Anchor the tmux server under the user slice: if none is running yet, create
# it (with a keepalive __anchor__ session) via systemd-run --scope so the
# forked server lives in user-1000.slice and survives SSH-session teardown
# (the mid-life mass-death root cause). Sessions recreated below then attach to
# that anchored server. If a server already exists this is a no-op.
# Best-effort (`|| true`): an existing server or a failed systemd-run must never
# break the launch.
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"

# ---- tmux socket guard (identical in restore.sh, drift.sh and tmux-sockheal.sh) ----
# Before anything relaunches sessions or creates a tmux server, count the servers LISTENING on the exact path
# ${TMUX_TMPDIR:-/tmp}/tmux-<uid>/default (a deleted socket file keeps its bound name in the kernel):
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
# ~/.claude-remote/drift-heal.off switches all of this off: the only rollback, since an older client never
# rewrites a v18 script.
SG_DIR="$HOME/.claude-remote"
SG_UID=$(id -u)
# tmux binds realpath(TMUX_TMPDIR)/tmux-<uid>/default: a trailing / or a symlink in TMUX_TMPDIR must not hide it.
SG_BASE="${TMUX_TMPDIR:-/tmp}"
SG_BASE=$(realpath -m -- "$SG_BASE" 2>/dev/null || printf '%s' "$SG_BASE")
SG_SOCK_DIR="$SG_BASE/tmux-$SG_UID"
SG_SOCK="$SG_SOCK_DIR/default"
SG_LOCK="$SG_DIR/tmux-server.lock"
SG_OFF="$SG_DIR/drift-heal.off"
SG_WHO=${SG_WHO:-script}
# A lasting fault logs one line per kind per 15 minutes, not one block per tick (1 440 a day). Per caller: drift.log,
# restore.log and sockheal.log each get the line (one caller's stamp silenced the fault in the others' logs).
# A line the stamp holds back still goes out with SG_QUIET in front when that is set (only tmux-sockheal.sh sets it:
# the log gets no copy, the refused tab gets its cause on stderr every time).
sg_log() {
    local st="$SG_DIR/.drift.sockguard.$SG_WHO.$1" now last
    now=$(date +%s); last=$(stat -c %Y "$st" 2>/dev/null || echo 0)
    if [ $((now - last)) -lt 900 ]; then
        [ -n "${SG_QUIET:-}" ] && echo "${SG_QUIET}[$(date -u +%FT%TZ)] sockguard($SG_WHO): $2"
        return 0
    fi
    touch "$st" 2>/dev/null || true
    echo "[$(date -u +%FT%TZ)] sockguard($SG_WHO): $2"
}
# One line per server listening on exactly $SG_SOCK: its pid, or "?" when it cannot be seen.
# Primary `ss -xlpH`; fallback /proc/net/unix, where only Flags=00010000 St=01 is the listening socket (the other
# rows with that path are accepted client connections).
sg_listeners() {
    local out ino pid
    if out=$(timeout 5 ss -xlpH 2>/dev/null); then
        printf '%s\n' "$out" | awk -v p="$SG_SOCK" '$2 == "LISTEN" && $5 == p {
            if (match($0, /pid=[0-9]+/)) print substr($0, RSTART + 4, RLENGTH - 4); else print "?" }'
        return 0
    fi
    awk -v p="$SG_SOCK" '$4 == "00010000" && $6 == "01" && $8 == p { print $7 }' /proc/net/unix 2>/dev/null \
        | while read -r ino; do
            pid=$(timeout 10 find /proc/[0-9]*/fd -maxdepth 1 -lname "socket:\[$ino\]" 2>/dev/null | head -1 | cut -d/ -f3)
            echo "${pid:-?}"
        done
}
sg_count() { printf '%s\n' "$1" | awk 'NF { n++ } END { print n + 0 }'; }
# Answers through its path AND for a plain client: only a plain client checks the directory (tmux refuses one others can
# enter, "unsafe permissions"), and drift, restore.sh and the app are plain clients.
sg_answers() {
    [ -S "$SG_SOCK" ] && timeout 5 tmux -S "$SG_SOCK" list-sessions >/dev/null 2>&1 \
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
    if o=$(timeout -k 1 5 tmux -S "$SG_SOCK" list-sessions 2>&1 >/dev/null </dev/null); then SG_PROBE=ok; return 0; fi
    case "$o" in *"no server running"*|*"onnection refused"*|*"No such file or directory"*) SG_PROBE=stale;; *) SG_PROBE=hung;; esac
}
sg_stale() { [ -S "$SG_SOCK" ] && sg_probe && [ "$SG_PROBE" = stale ]; }
# 0 when the socket directory is a real directory of ours with mode 700 (made so when it was looser); else 1.
sg_dir_private() {
    local m
    { [ ! -L "$SG_SOCK_DIR" ] && [ -d "$SG_SOCK_DIR" ] && [ "$(stat -c %u "$SG_SOCK_DIR" 2>/dev/null)" = "$SG_UID" ]; } || return 1
    m=$(stat -c %a "$SG_SOCK_DIR" 2>/dev/null)
    [ "$m" = 700 ] && return 0
    { chmod 700 "$SG_SOCK_DIR" 2>/dev/null && [ "$(stat -c %a "$SG_SOCK_DIR" 2>/dev/null)" = 700 ]; } || return 1
    echo "[$(date -u +%FT%TZ)] sockguard($SG_WHO): $SG_SOCK_DIR had mode $m — set to 700 (tmux clients refuse a socket directory others can enter)"
}
# Heal under the shared lock (drift, restore.sh's create branch and the app's tmux-sockheal.sh).
sg_heal() {
    local pid=$1
    (
        flock -w 2 8 || { sg_log lock "could not take $SG_LOCK within 2 s (another heal or create runs) — not relaunching now"; exit 1; }
        # healed by someone else meanwhile: usable again, as far as the caller is concerned
        sg_answers && exit 2
        case "$pid" in ''|*[!0-9]*) sg_log nopid "a tmux server listens on $SG_SOCK without its socket file and its pid is unknown — NOT relaunching"; exit 1;; esac
        if [ "$(cat "/proc/$pid/comm" 2>/dev/null)" != "tmux: server" ] || [ "$(stat -c %u "/proc/$pid" 2>/dev/null)" != "$SG_UID" ]; then
            sg_log notours "listener $pid on $SG_SOCK is not our tmux server — not signalling, NOT relaunching"; exit 1
        fi
        # SIGUSR1 cannot bind into a missing directory (the 2026-11-02 shape).
        [ -d "$SG_SOCK_DIR" ] || mkdir -p -m 700 "$SG_SOCK_DIR" 2>/dev/null
        if ! sg_dir_private; then
            sg_log baddir "$SG_SOCK_DIR is missing, a symlink, not ours or cannot be made private — not signalling, NOT relaunching"; exit 1
        fi
        # Re-checked right before the signal: still exactly this one server on the path (never signal one of two), and
        # the path is empty or a stale file (tmux unlinks it and binds anew). A socket that answers was healed meanwhile.
        [ "$(sg_listeners)" = "$pid" ] || { sg_log moved "the listeners on $SG_SOCK changed while healing — not signalling, NOT relaunching"; exit 1; }
        if [ ! -S "$SG_SOCK" ] || sg_stale; then
            kill -USR1 "$pid" 2>/dev/null || { sg_log kill "SIGUSR1 to $pid failed — NOT relaunching"; exit 1; }
        fi
        # At most 2 s in all: a deadline, not a number of tries (each try may itself wait).
        local end left
        end=$(( $(date +%s%N) / 1000000 + 2000 ))
        while left=$(( end - $(date +%s%N) / 1000000 )); [ "$left" -gt 0 ]; do
            [ -S "$SG_SOCK" ] && timeout "$((left / 1000)).$(printf %03d $((left % 1000)))" tmux -S "$SG_SOCK" list-sessions >/dev/null 2>&1 && exit 2
            sleep 0.1
        done
        sg_log healfail "tmux server $pid alive without its socket $SG_SOCK, SIGUSR1 did not bring it back — NOT relaunching"
        exit 1
    ) 8>>"$SG_LOCK"
}
sg_guard() {
    SG_WHY=
    [ -e "$SG_OFF" ] && return 0
    command -v tmux >/dev/null 2>&1 || return 0
    local pids n rc
    pids=$(sg_listeners)
    n=$(sg_count "$pids")
    [ "$n" -eq 0 ] && return 0
    if [ "$n" -ge 2 ]; then
        SG_WHY=split
        sg_log split "SPLIT BRAIN: $n tmux servers listen on $SG_SOCK (pids $(printf '%s' "$pids" | tr '\n' ' ')) — no signal, no relaunch, no new server; needs a human"
        return 1
    fi
    if [ -S "$SG_SOCK" ]; then
        # One wait for the answer (5 s), not sg_answers' and then sg_probe's: a hung server was refused after 10 s.
        sg_probe
        case "$SG_PROBE" in
            ok)
                timeout 5 env -u TMUX tmux list-sessions >/dev/null 2>&1 && return 0
                # it answers through its path but plain clients refuse the directory
                sg_dir_private && sg_answers && return 2
                SG_WHY=unsafe
                sg_log unsafe "$SG_SOCK answers only through -S: $SG_SOCK_DIR is not a private directory of ours and cannot be made one — NOT relaunching"
                return 1;;
            hung)
                SG_WHY=hung
                sg_log hung "tmux server $pids listens on $SG_SOCK but does not answer — NOT relaunching"
                return 1;;
        esac
    fi
    # the socket file is gone, or a stale file nobody accepts on took its place
    sg_heal "$pids"; rc=$?
    [ "$rc" -eq 2 ] && echo "[$(date -u +%FT%TZ)] sockguard($SG_WHO): tmux server $pids was alive without its socket — $SG_SOCK recreated (SIGUSR1), nothing relaunched"
    [ "$rc" -eq 1 ] && SG_WHY=heal
    return "$rc"
}
# ---- end of tmux socket guard ----
SG_WHO=restore
mkdir -p "$SG_DIR" 2>/dev/null
# A server that is alive on the default socket path but unusable (socket gone and not healable, hung, two
# servers) must never get a second server next to it: that is the 2026-11-02 duplicate-51-sessions incident.
if [ ! -e "$SG_OFF" ]; then
    SG_RC=0; sg_guard || SG_RC=$?
    if [ "$SG_RC" -eq 1 ]; then
        echo "a tmux server is alive on $SG_SOCK but unusable — NOT creating a second server, skipping restore (drift retries next tick)"
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
    if [ -e "$SG_OFF" ]; then
        systemd-run --user --scope --quiet \
            tmux new-session -d -x 200 -y 50 -s __anchor__ sleep infinity >/dev/null 2>&1 || true
    else
        # Under the shared lock, and only while still nothing listens on the path (a concurrent heal or
        # creator wins). `8>&-`: the daemonizing server must not inherit the lock fd and hold it forever.
        (
            flock -w 2 8 || { echo "could not take $SG_LOCK within 2 s — not creating a tmux server now"; exit 0; }
            [ "$(sg_count "$(sg_listeners)")" -eq 0 ] || { echo "a tmux server appeared on $SG_SOCK meanwhile — not creating another"; exit 0; }
            systemd-run --user --scope --quiet \
                tmux new-session -d -x 200 -y 50 -s __anchor__ sleep infinity >/dev/null 2>&1 8>&- || true
        ) 8>>"$SG_LOCK"
    fi
    for _ in $(seq 1 25); do tmux list-sessions >/dev/null 2>&1 && break; sleep 0.2; done
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
LOCK="$HOME/.claude-remote/sessions.lock"
# Source of truth = server-owned sessions.restore.json. The client can (and
# buggily does) blank sessions.json on reconnect; it never touches
# sessions.restore.json, so restore/self-heal survive that. Prefer it, then
# sessions.json, then the rolling backup — first non-empty wins.
RESTORE_SRC="$HOME/.claude-remote/sessions.restore.json"
SESSIONS_FILE="$HOME/.claude-remote/sessions.json"
touch "$LOCK"
SRC=""
if command -v jq >/dev/null 2>&1; then
    # Precedence: server-owned SoT, then ITS OWN rolling backup (drift maintains
    # $RESTORE_SRC.bak — the old list read $SESSIONS_FILE.bak, which NOTHING
    # writes, so the maintained backup was unreachable), then the app-facing
    # mirror, then its stale backup, then the high-water snapshot as a last
    # resort (largest manifest ever seen — turns a wipe into a 5s recovery).
    for f in "$RESTORE_SRC" "$RESTORE_SRC.bak" "$SESSIONS_FILE" "$SESSIONS_FILE.bak" "$HOME/.claude-remote/manifests/highwater.json"; do
        [ -f "$f" ] || continue
        n=$(flock -s "$LOCK" cat "$f" | jq 'length' 2>/dev/null || echo 0)
        [ "${n:-0}" -gt 0 ] && { SRC="$f"; break; }
    done
fi
[ -n "$SRC" ] || SRC="$SESSIONS_FILE"
if [ ! -f "$SRC" ]; then
    echo "no restore manifest yet — nothing to restore"
    exit 0
fi
echo "restore source: $SRC"
SNAP=$(flock -s "$LOCK" cat "$SRC")
command -v tmux >/dev/null 2>&1 || { echo "tmux not in PATH"; exit 1; }
command -v claude >/dev/null 2>&1 || { echo "claude not in PATH"; exit 1; }
HAVE_JQ=0
command -v jq >/dev/null 2>&1 && HAVE_JQ=1
parse_field() {
    local key="$1" line="$2"
    echo "$line" | sed -n "s/.*\"$key\":[[:space:]]*\"\([^\"]*\)\".*/\1/p"
}
# A tmux name the user CLOSED must never be restored, whichever source file
# $SRC came from. This is load-bearing for the fallbacks above: .bak and
# manifests/highwater.json (the largest manifest ever seen) both predate the
# close, so without this check a reboot resurrects every session ever closed —
# highwater in particular never shrinks and so never forgets.
# Two stores, same meaning: `forgotten` holds transient tombstones the drift
# daemon consumes within a tick, `forgotten.d/<name>` durable markers a real
# user close leaves behind (mtime = close time, honoured for TOMBSTONE_WINDOW).
FORGOTTEN="$HOME/.claude-remote/forgotten"
FORGOTTEN_D="$HOME/.claude-remote/forgotten.d"
TOMBSTONE_WINDOW=14
is_tombstoned() {
    [ -f "$FORGOTTEN" ] && grep -qxF "$1" "$FORGOTTEN" && return 0
    [ -f "$FORGOTTEN_D/$1" ] \
        && [ -n "$(find "$FORGOTTEN_D/$1" -maxdepth 0 -mtime -$TOMBSTONE_WINDOW 2>/dev/null)" ] \
        && return 0
    return 1
}
if [ "$HAVE_JQ" = "1" ]; then
    COUNT=$(echo "$SNAP" | jq 'length')
    for i in $(seq 0 $((COUNT-1))); do
        TMUX_NAME=$(echo "$SNAP" | jq -r ".[$i].tmuxSessionName")
        FOLDER=$(echo "$SNAP" | jq -r ".[$i].folder")
        MODE=$(echo "$SNAP" | jq -r ".[$i].mode")
        MODEL=$(echo "$SNAP" | jq -r ".[$i].model")
        UUID=$(echo "$SNAP" | jq -r ".[$i].claudeSessionId // empty")
        # Which Claude LOGIN this session belongs to. Empty (or "default") means
        # the pre-existing ~/.claude login, which MUST run with CLAUDE_CONFIG_DIR
        # unset — see the ENVPFX note below.
        ACCT=$(echo "$SNAP" | jq -r ".[$i].accountSlug // empty")
        is_tombstoned "$TMUX_NAME" && { echo "skip $TMUX_NAME — closed by the user (tombstoned)"; continue; }
        tmux has-session -t="$TMUX_NAME" 2>/dev/null && continue
        FOLDER_EXP="${FOLDER/#\~/$HOME}"
        case "$FOLDER_EXP" in /*) ;; *) FOLDER_EXP="$HOME/$FOLDER_EXP";; esac
        [ -d "$FOLDER_EXP" ] || { echo "skip $TMUX_NAME — folder $FOLDER_EXP missing"; continue; }
        case "$MODEL" in
            LOCAL|LOCAL_ORNITH|LOCAL_QWEN) ARGS=("claude-local");;
            *) ARGS=("claude");;
        esac
        case "$MODEL" in
            OPUS) ARGS+=(--model opus);;
            FABLE) ARGS+=(--model fable);;
            SONNET) ARGS+=(--model sonnet);;
            HAIKU) ARGS+=(--model haiku);;
        esac
        case "$MODE" in
            YOLO) ARGS+=(--dangerously-skip-permissions);;
            AUTO) ARGS+=(--permission-mode auto --allow-dangerously-skip-permissions);;
            AUTO_ACCEPT) ARGS+=(--permission-mode acceptEdits --allow-dangerously-skip-permissions);;
            *) ARGS+=(--allow-dangerously-skip-permissions);;
        esac
        # Resume only if a transcript actually exists for this UUID — claude
        # creates the jsonl lazily (first user/assistant turn), so a session
        # that was launched but never used has nothing to resume. Falling back
        # to fresh `--session-id` keeps the UUID stable for next time.
        if [ -n "$UUID" ]; then
            # Claude Code encodes '/', '_' AND '.' as '-', so the old '/'-only
            # sed missed every folder containing _ or . (e.g. ~/vscode_android):
            # the transcript existed but restore logged "no transcript" and
            # launched FRESH, orphaning the conversation on every reboot.
            ENC=$(echo "$FOLDER_EXP" | sed 's|[/_.]|-|g')
            JSONL="$HOME/.claude/projects/$ENC/$UUID.jsonl"
            # UUIDs are globally unique: if the encoding guess still misses (it
            # has changed before), find the transcript by UUID anywhere.
            [ -f "$JSONL" ] || JSONL=$(ls "$HOME"/.claude/projects/*/"$UUID".jsonl 2>/dev/null | head -1)
            # An account whose projects/ is a real directory, not the usual
            # symlink to the shared one, keeps its transcripts there.
            if [ ! -f "$JSONL" ] && [ -n "${ACCT:-}" ] && [ "${ACCT:-}" != "default" ]; then
                JSONL=$(ls "$HOME/.claude-remote/accounts/$ACCT/projects/"*/"$UUID".jsonl 2>/dev/null | head -1)
            fi
            if [ -n "$JSONL" ] && [ -f "$JSONL" ]; then
                ARGS+=(--resume "$UUID")
            else
                echo "no transcript at $JSONL — launching fresh with --session-id $UUID"
                ARGS+=(--session-id "$UUID")
            fi
        fi
        CMD="${ARGS[*]}"
        # ACCOUNT. This script builds the claude command a SECOND time, entirely
        # independently of the app's ClaudeConfig — so the account has to be
        # honoured HERE too or a restored/self-healed session silently reverts to
        # the default login (drift runs server-side with no app attached, so
        # nothing would surface the error).
        # An EMPTY or "default" slug must produce a BARE `claude` with NO env var:
        # CLAUDE_CONFIG_DIR=$HOME/.claude is NOT the same as leaving it unset —
        # set, claude reads ~/.claude/.claude.json, which doesn't exist, gets
        # created empty, and the session loses its project trust map + MCP config.
        if [ -n "${ACCT:-}" ] && [ "${ACCT:-}" != "default" ]; then
            CMD="CLAUDE_CONFIG_DIR=\"$HOME/.claude-remote/accounts/$ACCT\" $CMD"
        fi
        # `exec bash -l` keepalive: when claude exits (crash, usage limit, OOM,
        # /exit, network blip) the pane drops to a login shell instead of the
        # whole tmux session vanishing — matches app-created sessions, which
        # run claude via `send-keys` into a shell. Without this, a restored
        # session is fragile: it disappears the moment claude stops.
        if tmux new-session -d -x 200 -y 50 -s "$TMUX_NAME" -c "$FOLDER_EXP" \
            "tmux set-option -g mouse on; tmux set-option -g history-limit 100000; $CMD; exec bash -l"; then
            echo "Restored $TMUX_NAME ($FOLDER_EXP) [uuid=$UUID]"
        else
            echo "FAILED to restore $TMUX_NAME ($FOLDER_EXP) — tmux exit $?"
        fi
    done
else
    echo "jq not installed — falling back to line parser"
    while IFS= read -r line; do
        case "$line" in
            *tmuxSessionName*) TMUX_NAME=$(parse_field tmuxSessionName "$line");;
            *\"folder\"*)      FOLDER=$(parse_field folder "$line");;
            *\"mode\"*)        MODE=$(parse_field mode "$line");;
            *\"model\"*)       MODEL=$(parse_field model "$line");;
            *claudeSessionId*) UUID=$(parse_field claudeSessionId "$line");;
            *accountSlug*)     ACCT=$(parse_field accountSlug "$line");;
            *\}*)
                if [ -n "${TMUX_NAME:-}" ] && [ -n "${FOLDER:-}" ] \
                   && ! is_tombstoned "$TMUX_NAME"; then
                    if ! tmux has-session -t="$TMUX_NAME" 2>/dev/null; then
                        FOLDER_EXP="${FOLDER/#\~/$HOME}"
                        [ -d "$FOLDER_EXP" ] && {
                            CMD="claude --allow-dangerously-skip-permissions"
                            # Resume only a conversation that exists: --resume on a
                            # session nobody spoke to exits "No conversation found".
                            if [ -n "${UUID:-}" ]; then
                                if ls "$HOME"/.claude/projects/*/"$UUID".jsonl >/dev/null 2>&1 \
                                   || { [ -n "${ACCT:-}" ] && ls "$HOME/.claude-remote/accounts/$ACCT/projects/"*/"$UUID".jsonl >/dev/null 2>&1; }; then
                                    CMD="$CMD --resume $UUID"
                                else
                                    CMD="$CMD --session-id $UUID"
                                fi
                            fi
                            # Same account rule as the jq branch above — this
                            # SECOND command builder must honour it too, or a
                            # jq-less box quietly restores every session onto the
                            # default login. Empty/"default" ⇒ NO env var at all.
                            if [ -n "${ACCT:-}" ] && [ "${ACCT:-}" != "default" ]; then
                                CMD="CLAUDE_CONFIG_DIR=\"$HOME/.claude-remote/accounts/$ACCT\" $CMD"
                            fi
                            tmux new-session -d -x 200 -y 50 -s "$TMUX_NAME" -c "$FOLDER_EXP" \
                                "tmux set-option -g mouse on; tmux set-option -g history-limit 100000; $CMD; exec bash -l"
                            echo "Restored $TMUX_NAME"
                        }
                    fi
                fi
                TMUX_NAME=""; FOLDER=""; MODE=""; MODEL=""; UUID=""; ACCT=""
                ;;
        esac
    done < "$SESSIONS_FILE"
fi
RESTORE_EOF
            chmod +x "$SCRIPT.tmp.$$"
            # ---- rollback copy of restore.sh (D6-S) ----
            # Kept once when the script replaced here is v18 (kritika D2 N17): moving it back is the way back to v18, by
            # hand and only with Lukáš's consent that day (any v19 client's next install rewrites it to v19 again).
            # v19's compat line names v18 too, so the copy itself must not be v19 (a second installer may have replaced
            # the script in between), and `ln` publishes it only where no copy is yet (K2): never one over another.
            if grep -qE 'claude-remote-restore-v18( |$)' "$SCRIPT" 2>/dev/null && [ ! -e "$SCRIPT.v18" ]; then
                { cp -p "$SCRIPT" "$SCRIPT.v18.tmp.$$" && ! grep -q 'claude-remote-restore-v19' "$SCRIPT.v18.tmp.$$" \
                    && ln "$SCRIPT.v18.tmp.$$" "$SCRIPT.v18" \
                    && echo "[$(date -u +%FT%TZ)] install: the v18 restore.sh kept as restore.sh.v18 (rollback)" >> "$HOME/.claude-remote/install.log"; } 2>/dev/null || :
                rm -f "$SCRIPT.v18.tmp.$$"
            fi
            # ---- end of the rollback copy of restore.sh ----
            mv -f "$SCRIPT.tmp.$$" "$SCRIPT"
            echo "RESTORE_SCRIPT_INSTALLED"
        else
            echo "RESTORE_SCRIPT_PRESENT"
        fi
        if ! grep -q "$MARKER" "$DRIFT" 2>/dev/null; then
            echo "[$(date -u +%FT%TZ)] install: drift.sh missing $MARKER — rewriting; prior marker line: $(grep -m1 'claude-remote-restore-v' "$DRIFT" 2>/dev/null || echo '<none/new file>')" >> "$HOME/.claude-remote/install.log"
            cat > "$DRIFT.tmp.$$" <<'DRIFT_EOF'
#!/usr/bin/env bash
# marker-compat (do NOT remove): claude-remote-restore-v6 claude-remote-restore-v7 claude-remote-restore-v8 claude-remote-restore-v9 claude-remote-restore-v10 claude-remote-restore-v11 claude-remote-restore-v12 claude-remote-restore-v13 claude-remote-restore-v14 claude-remote-restore-v15 claude-remote-restore-v16 claude-remote-restore-v17 claude-remote-restore-v18 claude-remote-restore-v19
# Makes any installed client's `grep -q $MARKER` find its marker so it takes the
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
# Any tmux server started from here (incl. auto-start by `tmux new-session`,
# which does NOT inherit the anchor scope's LimitCORE) must be able to dump a
# core: the deaths are tmux SIGSEGV and a backtrace is the only way past theory.
# core_pattern is read-only in this LXC, so the core lands as ./core in cwd.
ulimit -c unlimited 2>/dev/null || true
cd "$HOME/.claude-remote/cores" 2>/dev/null || true
LOG="$HOME/.claude-remote/drift.log"
# Buffer this tick's output; flush to the real log ONLY if something actually
# happened (a reconcile/refuse/self-heal line, an early-exit notice, or a
# stderr error made the buffer non-empty). The common no-op tick writes
# NOTHING — this kills the ~60MB/day of per-tick "drift start"/"LIVE=" spam.
# The header (start banner + live-session names) is held in HDR and prepended
# only when the buffer is flushed, so a change tick still logs full context.
TICKLOG=$(mktemp "$HOME/.claude-remote/.drift.tick.XXXXXX" 2>/dev/null || mktemp 2>/dev/null || echo "$LOG")
flush_tick() {
    if [ "$TICKLOG" != "$LOG" ]; then
        [ -s "$TICKLOG" ] && { printf '%s\n' "$HDR"; cat "$TICKLOG"; } >> "$LOG"
        rm -f "$TICKLOG"
    fi
}
trap flush_tick EXIT
exec >> "$TICKLOG" 2>&1
HDR="----- $(date -u +%FT%TZ) drift start -----"
SF="$HOME/.claude-remote/sessions.restore.json"   # server-owned source of truth (client never writes it)
SF_APP="$HOME/.claude-remote/sessions.json"        # mirror the app/client reads (drift keeps it in sync)
LOCK="$HOME/.claude-remote/sessions.lock"
FORGOTTEN="$HOME/.claude-remote/forgotten"          # tombstones: tmux names a client explicitly closed
FORGOTTEN_D="$HOME/.claude-remote/forgotten.d"      # DURABLE tombstones: one file per USER close (mtime = close time)
TOMBSTONE_WINDOW=14                                 # days a durable marker stays authoritative
command -v tmux >/dev/null 2>&1 || { echo "no tmux"; exit 0; }
command -v jq >/dev/null 2>&1 || { echo "no jq"; exit 0; }
touch "$LOCK"


# ---- tmux socket guard (identical in restore.sh, drift.sh and tmux-sockheal.sh) ----
# Before anything relaunches sessions or creates a tmux server, count the servers LISTENING on the exact path
# ${TMUX_TMPDIR:-/tmp}/tmux-<uid>/default (a deleted socket file keeps its bound name in the kernel):
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
# ~/.claude-remote/drift-heal.off switches all of this off: the only rollback, since an older client never
# rewrites a v18 script.
SG_DIR="$HOME/.claude-remote"
SG_UID=$(id -u)
# tmux binds realpath(TMUX_TMPDIR)/tmux-<uid>/default: a trailing / or a symlink in TMUX_TMPDIR must not hide it.
SG_BASE="${TMUX_TMPDIR:-/tmp}"
SG_BASE=$(realpath -m -- "$SG_BASE" 2>/dev/null || printf '%s' "$SG_BASE")
SG_SOCK_DIR="$SG_BASE/tmux-$SG_UID"
SG_SOCK="$SG_SOCK_DIR/default"
SG_LOCK="$SG_DIR/tmux-server.lock"
SG_OFF="$SG_DIR/drift-heal.off"
SG_WHO=${SG_WHO:-script}
# A lasting fault logs one line per kind per 15 minutes, not one block per tick (1 440 a day). Per caller: drift.log,
# restore.log and sockheal.log each get the line (one caller's stamp silenced the fault in the others' logs).
# A line the stamp holds back still goes out with SG_QUIET in front when that is set (only tmux-sockheal.sh sets it:
# the log gets no copy, the refused tab gets its cause on stderr every time).
sg_log() {
    local st="$SG_DIR/.drift.sockguard.$SG_WHO.$1" now last
    now=$(date +%s); last=$(stat -c %Y "$st" 2>/dev/null || echo 0)
    if [ $((now - last)) -lt 900 ]; then
        [ -n "${SG_QUIET:-}" ] && echo "${SG_QUIET}[$(date -u +%FT%TZ)] sockguard($SG_WHO): $2"
        return 0
    fi
    touch "$st" 2>/dev/null || true
    echo "[$(date -u +%FT%TZ)] sockguard($SG_WHO): $2"
}
# One line per server listening on exactly $SG_SOCK: its pid, or "?" when it cannot be seen.
# Primary `ss -xlpH`; fallback /proc/net/unix, where only Flags=00010000 St=01 is the listening socket (the other
# rows with that path are accepted client connections).
sg_listeners() {
    local out ino pid
    if out=$(timeout 5 ss -xlpH 2>/dev/null); then
        printf '%s\n' "$out" | awk -v p="$SG_SOCK" '$2 == "LISTEN" && $5 == p {
            if (match($0, /pid=[0-9]+/)) print substr($0, RSTART + 4, RLENGTH - 4); else print "?" }'
        return 0
    fi
    awk -v p="$SG_SOCK" '$4 == "00010000" && $6 == "01" && $8 == p { print $7 }' /proc/net/unix 2>/dev/null \
        | while read -r ino; do
            pid=$(timeout 10 find /proc/[0-9]*/fd -maxdepth 1 -lname "socket:\[$ino\]" 2>/dev/null | head -1 | cut -d/ -f3)
            echo "${pid:-?}"
        done
}
sg_count() { printf '%s\n' "$1" | awk 'NF { n++ } END { print n + 0 }'; }
# Answers through its path AND for a plain client: only a plain client checks the directory (tmux refuses one others can
# enter, "unsafe permissions"), and drift, restore.sh and the app are plain clients.
sg_answers() {
    [ -S "$SG_SOCK" ] && timeout 5 tmux -S "$SG_SOCK" list-sessions >/dev/null 2>&1 \
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
    if o=$(timeout -k 1 5 tmux -S "$SG_SOCK" list-sessions 2>&1 >/dev/null </dev/null); then SG_PROBE=ok; return 0; fi
    case "$o" in *"no server running"*|*"onnection refused"*|*"No such file or directory"*) SG_PROBE=stale;; *) SG_PROBE=hung;; esac
}
sg_stale() { [ -S "$SG_SOCK" ] && sg_probe && [ "$SG_PROBE" = stale ]; }
# 0 when the socket directory is a real directory of ours with mode 700 (made so when it was looser); else 1.
sg_dir_private() {
    local m
    { [ ! -L "$SG_SOCK_DIR" ] && [ -d "$SG_SOCK_DIR" ] && [ "$(stat -c %u "$SG_SOCK_DIR" 2>/dev/null)" = "$SG_UID" ]; } || return 1
    m=$(stat -c %a "$SG_SOCK_DIR" 2>/dev/null)
    [ "$m" = 700 ] && return 0
    { chmod 700 "$SG_SOCK_DIR" 2>/dev/null && [ "$(stat -c %a "$SG_SOCK_DIR" 2>/dev/null)" = 700 ]; } || return 1
    echo "[$(date -u +%FT%TZ)] sockguard($SG_WHO): $SG_SOCK_DIR had mode $m — set to 700 (tmux clients refuse a socket directory others can enter)"
}
# Heal under the shared lock (drift, restore.sh's create branch and the app's tmux-sockheal.sh).
sg_heal() {
    local pid=$1
    (
        flock -w 2 8 || { sg_log lock "could not take $SG_LOCK within 2 s (another heal or create runs) — not relaunching now"; exit 1; }
        # healed by someone else meanwhile: usable again, as far as the caller is concerned
        sg_answers && exit 2
        case "$pid" in ''|*[!0-9]*) sg_log nopid "a tmux server listens on $SG_SOCK without its socket file and its pid is unknown — NOT relaunching"; exit 1;; esac
        if [ "$(cat "/proc/$pid/comm" 2>/dev/null)" != "tmux: server" ] || [ "$(stat -c %u "/proc/$pid" 2>/dev/null)" != "$SG_UID" ]; then
            sg_log notours "listener $pid on $SG_SOCK is not our tmux server — not signalling, NOT relaunching"; exit 1
        fi
        # SIGUSR1 cannot bind into a missing directory (the 2026-11-02 shape).
        [ -d "$SG_SOCK_DIR" ] || mkdir -p -m 700 "$SG_SOCK_DIR" 2>/dev/null
        if ! sg_dir_private; then
            sg_log baddir "$SG_SOCK_DIR is missing, a symlink, not ours or cannot be made private — not signalling, NOT relaunching"; exit 1
        fi
        # Re-checked right before the signal: still exactly this one server on the path (never signal one of two), and
        # the path is empty or a stale file (tmux unlinks it and binds anew). A socket that answers was healed meanwhile.
        [ "$(sg_listeners)" = "$pid" ] || { sg_log moved "the listeners on $SG_SOCK changed while healing — not signalling, NOT relaunching"; exit 1; }
        if [ ! -S "$SG_SOCK" ] || sg_stale; then
            kill -USR1 "$pid" 2>/dev/null || { sg_log kill "SIGUSR1 to $pid failed — NOT relaunching"; exit 1; }
        fi
        # At most 2 s in all: a deadline, not a number of tries (each try may itself wait).
        local end left
        end=$(( $(date +%s%N) / 1000000 + 2000 ))
        while left=$(( end - $(date +%s%N) / 1000000 )); [ "$left" -gt 0 ]; do
            [ -S "$SG_SOCK" ] && timeout "$((left / 1000)).$(printf %03d $((left % 1000)))" tmux -S "$SG_SOCK" list-sessions >/dev/null 2>&1 && exit 2
            sleep 0.1
        done
        sg_log healfail "tmux server $pid alive without its socket $SG_SOCK, SIGUSR1 did not bring it back — NOT relaunching"
        exit 1
    ) 8>>"$SG_LOCK"
}
sg_guard() {
    SG_WHY=
    [ -e "$SG_OFF" ] && return 0
    command -v tmux >/dev/null 2>&1 || return 0
    local pids n rc
    pids=$(sg_listeners)
    n=$(sg_count "$pids")
    [ "$n" -eq 0 ] && return 0
    if [ "$n" -ge 2 ]; then
        SG_WHY=split
        sg_log split "SPLIT BRAIN: $n tmux servers listen on $SG_SOCK (pids $(printf '%s' "$pids" | tr '\n' ' ')) — no signal, no relaunch, no new server; needs a human"
        return 1
    fi
    if [ -S "$SG_SOCK" ]; then
        # One wait for the answer (5 s), not sg_answers' and then sg_probe's: a hung server was refused after 10 s.
        sg_probe
        case "$SG_PROBE" in
            ok)
                timeout 5 env -u TMUX tmux list-sessions >/dev/null 2>&1 && return 0
                # it answers through its path but plain clients refuse the directory
                sg_dir_private && sg_answers && return 2
                SG_WHY=unsafe
                sg_log unsafe "$SG_SOCK answers only through -S: $SG_SOCK_DIR is not a private directory of ours and cannot be made one — NOT relaunching"
                return 1;;
            hung)
                SG_WHY=hung
                sg_log hung "tmux server $pids listens on $SG_SOCK but does not answer — NOT relaunching"
                return 1;;
        esac
    fi
    # the socket file is gone, or a stale file nobody accepts on took its place
    sg_heal "$pids"; rc=$?
    [ "$rc" -eq 2 ] && echo "[$(date -u +%FT%TZ)] sockguard($SG_WHO): tmux server $pids was alive without its socket — $SG_SOCK recreated (SIGUSR1), nothing relaunched"
    [ "$rc" -eq 1 ] && SG_WHY=heal
    return "$rc"
}
# ---- end of tmux socket guard ----
SG_WHO=drift
# SOCKET GUARD, every tick and before anything below can decide to relaunch (not only at LIVE=[]: at 23:38:14 on
# 2026-11-02 LIVE held part of a SECOND server's sessions and drift relaunched 24 more duplicates).
# Healed (rc 2) or refused (rc 1): this tick relaunches nothing and writes no manifest.
if [ ! -e "$SG_OFF" ]; then
    # Every tmux call of a tick gets a time limit: a hung server must not wedge the timer unit.
    command -v timeout >/dev/null 2>&1 && tmux() { timeout -k 2 10 tmux "$@"; }
    SG_RC=0; sg_guard || SG_RC=$?
    [ "$SG_RC" -eq 0 ] || exit 0
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

# A prefix line is claude-<name>- where <name> holds only ASCII letters, digits, _ and - and does not start with -:
# never empty, never claude- alone, no space, CR, '.', ':' or glob character. The class is spelled out (no ranges), so
# no locale can widen it. A line that fails is skipped without a word.
cr_prefix_ok() {
    case "$1" in claude-?*-) ;; *) return 1;; esac
    case "${1#claude-}" in
        [!abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_]*) return 1;;
        *[!abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-]*) return 1;;
    esac
    return 0
}
PFX="claude-server-"
if [ -f "$HOME/.claude-remote/prefixes" ]; then
    while IFS= read -r _p || [ -n "$_p" ]; do
        cr_prefix_ok "$_p" && PFX="$PFX $_p"
    done 2>/dev/null < "$HOME/.claude-remote/prefixes"
fi
# 0 when $1 starts with one of PFX, compared as fixed strings. One pass, the first match ends it: overlapping
# prefixes (claude-server- and claude-server-lan-) give one row, not two.
is_ours() {
    local q hit=1
    for q in $PFX; do case "$1" in "$q"*) hit=0; break;; esac; done
    return $hit
}
# ---- end of prefixes ----
# Drop the internal keepalive __anchor__ if it lingers: with exit-empty=off the
# server no longer needs it, and leaving it visible makes the app treat it as a
# real session and churn it (kill/relaunch). Safe — exit-empty=off keeps the
# server alive at 0 sessions.
if tmux -u list-sessions -F '#{session_name}' 2>/dev/null | { while IFS= read -r s; do case "$s" in "claude-server-"*) exit 0;; esac; done; exit 1; }; then
    tmux kill-session -t __anchor__ 2>/dev/null || true
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
    TSRV=$(tmux display -p '#{pid}' 2>/dev/null)
    [ -n "$TSRV" ] && prlimit --pid "$TSRV" --core=unlimited 2>/dev/null || true
fi

# SHUTDOWN GUARD: during a reboot/shutdown systemd tears tmux down before the
# machine halts, so a drift tick here would see LIVE=[] and blank sessions.json
# — the exact file the next boot's restore.sh reads. Skip reconcile entirely
# while the system is going down so the restore manifest survives the reboot.
STATE=$(systemctl is-system-running 2>/dev/null || true)
case "$STATE" in
    stopping|offline) echo "system $STATE — skip reconcile (preserve restore manifest)"; exit 0;;
esac

# ONE process-tree snapshot instead of a recursive pgrep walk per session:
# build a pid->comm map and a ppid->children map, then resolve each pane's
# claude descendant by walking that in-memory tree. pgrep -P forked at every
# tree level for every session (~5s CPU/tick over ~32 sessions); one `ps` plus
# an in-shell walk gives byte-identical results with no per-level forks.
declare -A DRIFT_COMM DRIFT_KIDS
while read -r _p _pp _cm; do
    DRIFT_COMM[$_p]=$_cm
    DRIFT_KIDS[$_pp]="${DRIFT_KIDS[$_pp]:-} $_p"
done < <(ps -eo pid=,ppid=,comm=)

# Walk the tmux pane's process tree to find the claude process — pane_pid is
# often bash (claude launched via a shell command / keepalive), so claude is a
# descendant. Same depth-first, first-match descent as before, over the map.
find_claude_descendant() {
    local p=$1 c r
    if [ "${DRIFT_COMM[$p]:-}" = "claude" ]; then echo "$p"; return 0; fi
    for c in ${DRIFT_KIDS[$p]:-}; do
        r=$(find_claude_descendant "$c"); [ -n "$r" ] && { echo "$r"; return 0; }
    done
}

# ---- sessions only a line of prefixes names (B1) ----
# A session whose name starts with claude-server- is ours by its name alone, as in v18 (and only such a session makes
# the __anchor__ check above). One only a line of ~/.claude-remote/prefixes names may be somebody else's, so drift
# adopts it only with proof: claude runs in its pane with one of the two flags the app and restore.sh always start it
# with (metadata from claude's cmdline and environ, a missing serverId from the app's entry of that name), or the app's
# sessions.json has an entry of that name, kept as the app wrote it (claude without the flag gets no YOLO default).
# Neither: not adopted, not written, not counted as a live session of ours, never relaunched.
APP_JSON="[]"; OWN_META=""
if [ "$PFX" != "claude-server-" ] && [ -f "$SF_APP" ]; then
    APP_JSON=$(jq -c 'if type == "array" then map(select(type == "object" and (.tmuxSessionName | type) == "string")) else [] end' "$SF_APP" 2>/dev/null)
    [ -n "$APP_JSON" ] || APP_JSON="[]"
fi
app_has() { printf '%s' "$APP_JSON" | jq -e --arg n "$1" 'any(.[]; .tmuxSessionName == $n)' >/dev/null 2>&1; }
# ---- end of sessions only a line of prefixes names ----

# Ground-truth entry list from the live tmux sessions. Collect one tab-separated
# row per session into a temp table, then build the whole LIVE JSON array in a
# SINGLE jq pass (was one jq fork per session).
LIVE_TBL=$(mktemp "$HOME/.claude-remote/.drift.live.XXXXXX" 2>/dev/null || mktemp 2>/dev/null)
# `tmux -u` on every read: this daemon runs from a systemd user unit, which has
# NO locale at all, and a non-UTF-8 tmux client gets each non-ASCII character of
# a session name (and of pane_current_path) replaced with '_' by utf8_sanitize().
# Without -u this loop wrote '_'-mangled names into sessions.json as GROUND
# TRUTH: the real session's entry vanished, the mangled one collapsed onto any
# genuinely mangled twin (two identical rows in the manifest), and the next
# client to read it relaunched the "missing" session under the broken name.
for s in $(tmux -u list-sessions -F '#{session_name}' 2>/dev/null); do
    is_ours "$s" || continue
    pane_pid=$(tmux -u list-panes -t "$s" -F '#{pane_pid}' 2>/dev/null | head -1)
    [ -n "$pane_pid" ] || continue
    folder=$(tmux -u display-message -p -t "$s" '#{pane_current_path}' 2>/dev/null)
    pid=$(find_claude_descendant "$pane_pid")
    # ---- a session only a line of prefixes names (B1) ----
    case "$s" in
        "claude-server-"*) ;;
        *)  sig=""
            [ -n "$pid" ] && case " $(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null) " in
                *" --dangerously-skip-permissions "*|*" --allow-dangerously-skip-permissions "*) sig=1;;
            esac
            if [ -n "$sig" ]; then OWN_META="$OWN_META c:$s"
            elif app_has "$s"; then OWN_META="$OWN_META a:$s"
            else continue
            fi;;
    esac
    # ---- end of a session only a line of prefixes names ----
    sid=""; model="DEFAULT"; mode="YOLO"; acct=""
    if [ -n "$pid" ]; then
        args=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)
        # ACCOUNT: the login this pane actually runs under. The command PREFIX
        # (CLAUDE_CONFIG_DIR=... claude ...) is an env assignment, not an argv
        # token, so cmdline can't show it — the process's own environ can.
        # Empty ⇒ the default ~/.claude login (no var), which is exactly the
        # null the manifest wants.
        cfg=""
        if [ -r "/proc/$pid/environ" ]; then
            cfg=$(tr '\0' '\n' < "/proc/$pid/environ" 2>/dev/null \
                | sed -n 's|^CLAUDE_CONFIG_DIR=||p' | head -1)
            acct=$(printf '%s' "$cfg" | sed -n 's|.*/accounts/\([^/]*\)/*$|\1|p')
        fi
        case "$args" in
            *"--model opus"*)   model=OPUS;;
            *"--model sonnet"*) model=SONNET;;
            *"--model haiku"*)  model=HAIKU;;
            *"--model fable"*)  model=FABLE;;
        esac
        case "$args" in
            *"--permission-mode auto"*) mode=AUTO;;
            *"--permission-mode acceptEdits"*) mode=AUTO_ACCEPT;;
            *"--allow-dangerously-skip-permissions"*) mode=DEFAULT;;
        esac
        sid=$(echo "$args" | sed -n 's/.*--\(resume\|session-id\) \([0-9a-f-]*\).*/\2/p' | head -1)
        # Claude writes its per-pid state under the CONFIG DIR, so a pane running
        # under a non-default login keeps it in ~/.claude-remote/accounts/<slug>/
        # sessions/, not ~/.claude/sessions/. Hardcoding the latter made this
        # whole refresh a no-op for account sessions: their claudeSessionId was
        # frozen at the launch/--resume id forever, which is what pinned a
        # client's chat view to the pre-fork transcript after every restore.
        psf="${cfg:-$HOME/.claude}/sessions/$pid.json"
        if [ -f "$psf" ]; then
            v=$(jq -r .sessionId "$psf" 2>/dev/null)
            # claude >= 2.1.220 FORKS on --resume: it reports a BRAND NEW
            # sessionId whose transcript is created lazily, so adopting it
            # ratchets the manifest onto an empty conversation and orphans the
            # real one for good (proven: server--CC 17b4feef -> 667a07bd, then
            # "no transcript -> launching fresh" on every restore since).
            # Only adopt an id that actually HAS a transcript on disk.
            # Accounts normally share ~/.claude/projects via symlink, but a login
            # provisioned out of order has a REAL projects dir of its own — look
            # in both so the guard can't reject a transcript that exists.
            if [ -n "$v" ] && [ "$v" != "null" ] && [ "$v" != "$sid" ]; then
                if ls "$HOME"/.claude/projects/*/"$v".jsonl >/dev/null 2>&1 \
                   || ls "${cfg:-$HOME/.claude}"/projects/*/"$v".jsonl >/dev/null 2>&1; then
                    sid="$v"
                fi
            fi
        fi
    fi
    case "$s" in *--*) alias="${s##*--}";; *) alias="";; esac
    [ -n "$LIVE_TBL" ] && printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$s" "$folder" "$mode" "$model" "$alias" "$sid" "$acct" >> "$LIVE_TBL"
done
LIVE="[]"
if [ -n "$LIVE_TBL" ]; then
    LIVE=$(jq -R -s -c '
        split("\n") | map(select(length>0)) | map(split("\t")) | map({
            id: .[0], serverId: "", folder: .[1], mode: .[2], model: .[3],
            tmuxSessionName: .[0], connectionType: "SSH", alias: .[4],
            claudeSessionId: (if .[5]=="" then null else .[5] end), createdAt: 0,
            accountSlug: (if (.[6] // "")=="" then null else .[6] end)
        })' "$LIVE_TBL" 2>/dev/null)
    rm -f "$LIVE_TBL"
    [ -n "$LIVE" ] || LIVE="[]"
fi
# ---- the app's entry of a session only a line of prefixes names (B1) ----
# a:<name> is the app's entry as it stands, c:<name> (claude with the app's flag) gets the entry's serverId. The entries
# come from a file (an execve argument stops at 128 KB); if this jq fails, only these names miss this tick.
if [ -n "$OWN_META" ]; then
    if NL=$(printf '%s' "$LIVE" | jq -c --slurpfile app0 <(printf '%s' "$APP_JSON") --arg own "$OWN_META" '
        ($app0[0] | map({key: .tmuxSessionName, value: .}) | from_entries) as $am
        | ($own | split(" ") | map(select(length > 0) | {key: .[2:], value: .[0:1]}) | from_entries) as $om
        | map(.tmuxSessionName as $n
            | if $om[$n] == "a" then $am[$n]
              elif $om[$n] == "c" then . + {serverId: (($am[$n].serverId // "") | if type == "string" then . else "" end)}
              else . end)
        | map(select(type == "object"))' 2>/dev/null) && [ -n "$NL" ]; then
        LIVE=$NL
        # an adopted session of ours lives: the anchor goes, as above for claude-server-*
        tmux kill-session -t __anchor__ 2>/dev/null || true
    else
        LIVE=$(printf '%s' "$LIVE" | jq -c --arg own "$OWN_META" '($own | split(" ") | map(.[2:])) as $o
            | map(select(.tmuxSessionName as $n | $o | any(.[]; . == $n) | not))' 2>/dev/null)
        [ -n "$LIVE" ] || LIVE="[]"
    fi
fi
# ---- end of the app's entry ----
HDR="$HDR
LIVE=$(echo "$LIVE" | jq -c 'map(.tmuxSessionName)')"
(
    flock -x 9
    CORRUPT=0
    OLD="[]"; [ -f "$SF" ] && OLD=$(cat "$SF")
    # A truncated/0-byte $SF (ENOSPC, unclean shutdown) makes the union jq below
    # fail, NEW empty, and the `[ -n "$NEW" ]` guard then skips the write — every
    # tick, forever: reconcile, self-heal and never-blank all silently dead. Fall
    # back to the rolling backup instead of running on garbage.
    if ! printf '%s' "$OLD" | jq -e 'type=="array"' >/dev/null 2>&1; then
        echo "[$(date -u +%FT%TZ)] drift: manifest unreadable/corrupt — falling back to .bak"
        OLD="[]"
        if [ -f "$SF.bak" ] && jq -e 'type=="array"' "$SF.bak" >/dev/null 2>&1; then OLD=$(cat "$SF.bak"); fi
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
    if [ -f "$FORGOTTEN" ] && [ -s "$FORGOTTEN" ]; then
        FORGET=$(jq -R . "$FORGOTTEN" | jq -s 'map(select(length>0))' 2>/dev/null)
        [ -n "$FORGET" ] || FORGET="[]"
        FGLEN=$(echo "$FORGET" | jq 'length' 2>/dev/null || echo 0)
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
    if [ -d "$FORGOTTEN_D" ]; then
        DFG=$(find "$FORGOTTEN_D" -maxdepth 1 -type f -mtime -$TOMBSTONE_WINDOW -printf '%f\n' 2>/dev/null \
            | jq -R . | jq -s 'map(select(length>0))' 2>/dev/null)
        [ -n "$DFG" ] || DFG="[]"
        DFGLEN=$(echo "$DFG" | jq 'length' 2>/dev/null || echo 0)
    fi
    # MASS-TOMBSTONE GUARD: a human closes sessions ONE AT A TIME. More than 2
    # tombstones in a single tick means the CLIENT mass-forgot after an outage or
    # a PARTIAL recovery — its own forget-guard only blocks the all-dead case,
    # not "8 came back, 15 still missing" — which on 2026-07-27 silently cut the
    # manifest 23 -> 8 and lost those sessions for good. Refuse to honour them
    # and move the file aside as evidence so it cannot keep tripping.
    if [ "$FGLEN" -gt 2 ]; then
        echo "[$(date -u +%FT%TZ)] drift: REFUSING $FGLEN tombstones at once (mass-forget after outage, not user closes) — moved aside"
        mv -f "$FORGOTTEN" "$FORGOTTEN.rejected.$(date -u +%Y%m%dT%H%M%SZ)" 2>/dev/null || : > "$FORGOTTEN"
        FORGET="[]"; FGLEN=0
    fi
    # Union AFTER the guard: rejecting a suspicious transient batch must never
    # reject the durable markers, which are proof of intent rather than a list
    # of names that happen to be missing.
    if [ "$DFGLEN" -gt 0 ]; then
        FORGET=$(jq -n --argjson t "$FORGET" --argjson d "$DFG" '$t + $d | unique' 2>/dev/null)
        [ -n "$FORGET" ] || FORGET="$DFG"
        FGLEN=$(echo "$FORGET" | jq 'length' 2>/dev/null || echo 0)
    fi
    # Keep client metadata for sessions already in OLD (refresh only the live
    # claudeSessionId); add live sessions missing from OLD.
    # UNION reconcile — never DROP a manifest entry just because its tmux isn't
    # live right now (it may have crashed; self-heal below relaunches it).
    # Dropping it would let a client reading the shrunken manifest wrongly
    # "forget" the session. Keep every OLD entry (refresh its live sid), append
    # live sessions not yet in the manifest, THEN subtract tombstoned names so
    # an explicit close leaves the manifest for good.
    NEW=$(jq -n --argjson old "$OLD" --argjson live "$LIVE" --argjson forget "$FORGET" '
        (($live | map({key:.tmuxSessionName, value:.}) | from_entries) as $lm
        | ($old | map(.tmuxSessionName)) as $on
        | ($old | map(. as $o | ($lm[$o.tmuxSessionName]) as $l
             | (if $l and ($l.claudeSessionId != null) then $o + {claudeSessionId:$l.claudeSessionId} else $o end)
             | (if $l and ($l.accountSlug != null) then . + {accountSlug:$l.accountSlug} else . end)))
          + ($live | map(select(.tmuxSessionName as $n | ($on | index($n)) | not))))
        | map(select(.tmuxSessionName as $n | ($forget | index($n)) | not))' 2>/dev/null)
    NEWLEN=$(echo "$NEW" | jq 'length' 2>/dev/null || echo 0)
    OLDLEN=$(echo "$OLD" | jq 'length // 0' 2>/dev/null || echo 0)
    if [ -n "$NEW" ] && { [ "$NEW" != "$OLD" ] || [ "${CORRUPT:-0}" = "1" ]; }; then
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
        DROP_EXPLAINED=$(jq -n --argjson old "$OLD" --argjson forget "$FORGET" \
            '(($old | map(.tmuxSessionName)) - $forget | length) == 0' 2>/dev/null)
        if [ "$NEWLEN" -eq 0 ] && [ "$OLDLEN" -gt 0 ] && [ "${DROP_EXPLAINED:-false}" != "true" ]; then
            echo "[$(date -u +%FT%TZ)] drift: refusing to blank non-empty manifest ($OLDLEN entries) — likely shutdown/tmux restart"
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
              # truncated $SF wiped the 25-entry .bak). Only back up a manifest
              # that is itself valid.
              if [ "$OLDLEN" -gt 0 ] && [ "${CORRUPT:-0}" = "0" ] \
                 && [ -s "$SF" ] && jq -e 'type=="array"' "$SF" >/dev/null 2>&1; then
                  cp -f "$SF" "$SF.bak" 2>/dev/null || true
              fi
              # Durable snapshots: keep every DROP (the event you need to undo)
              # and a high-water copy (the largest manifest ever seen).
              # restore.sh falls back to highwater.json as a last resort.
              MDIR="$HOME/.claude-remote/manifests"; mkdir -p "$MDIR" 2>/dev/null
              if [ "$NEWLEN" -lt "$OLDLEN" ] && [ "$OLDLEN" -gt 0 ]; then
                  cp -f "$SF" "$MDIR/$(date -u +%Y%m%dT%H%M%SZ)-drop-$OLDLEN-to-$NEWLEN.json" 2>/dev/null || true
                  ls -1t "$MDIR"/*-drop-*.json 2>/dev/null | tail -n +51 | xargs -r rm -f 2>/dev/null || true
              fi
              HW=0
              if [ -f "$MDIR/highwater.json" ]; then HW=$(jq 'length' "$MDIR/highwater.json" 2>/dev/null || echo 0); fi
              if [ "$NEWLEN" -gt "${HW:-0}" ]; then printf '%s' "$NEW" > "$MDIR/highwater.json" 2>/dev/null || true; fi
            echo "$NEW" > "$SF.tmp.$$" && mv "$SF.tmp.$$" "$SF"
            echo "[$(date -u +%FT%TZ)] drift: reconciled $NEWLEN live (was $OLDLEN)"
        fi
    fi
    # Tombstone self-clean: a forgotten name now absent from BOTH live tmux AND
    # the written manifest has done its job — drop it so a future session
    # reusing the same tmux name isn't blocked. Race-safe under this flock.
    if [ -f "$FORGOTTEN" ] && [ -s "$FORGOTTEN" ]; then
        MANIFEST="[]"; [ -f "$SF" ] && MANIFEST=$(cat "$SF")
        KEEP=$(while IFS= read -r fn; do
            [ -n "$fn" ] || continue
            if tmux has-session -t="$fn" 2>/dev/null; then echo "$fn"; continue; fi
            if echo "$MANIFEST" | jq -e --arg n "$fn" '[.[].tmuxSessionName] | index($n)' >/dev/null 2>&1; then echo "$fn"; fi
        done < "$FORGOTTEN")
        if [ -n "$KEEP" ]; then printf '%s\n' "$KEEP" > "$FORGOTTEN.tmp.$$" && mv "$FORGOTTEN.tmp.$$" "$FORGOTTEN"; else : > "$FORGOTTEN"; fi
    fi
) 9<>"$LOCK"

# Mirror the server-owned source of truth to the app-facing sessions.json (the
# client may have blanked/shrunk it on reconnect); keeps the app view correct
# within 60s and re-expands a client that wrongly forgot sessions.
# Mirror ONLY a valid, non-empty manifest: an unguarded cp would copy a
# truncated/empty $SF over the app-facing file every tick and blank the app view.
if [ -f "$SF" ] && [ -s "$SF" ] && jq -e 'length>0' "$SF" >/dev/null 2>&1 \
   && ! cmp -s "$SF" "$SF_APP" 2>/dev/null; then
    cp -f "$SF" "$SF_APP" 2>/dev/null || true
fi

# SELF-HEAL: relaunch any manifest session whose tmux is gone (tmux-server
# crash, OOM, stray kill) so it returns within 60s instead of staying dead
# until a reboot. Critically this keeps tmux from ever sitting empty — an empty
# server is exactly the signal that makes clients wrongly "forget" every
# session and shrink the manifest. restore.sh is idempotent: skips live
# sessions and missing folders. Tombstoned names are skipped so an explicit
# close is never relaunched.
if [ -f "$SF" ]; then
    MISSING=$(jq -r '.[].tmuxSessionName' "$SF" 2>/dev/null | while IFS= read -r n; do
        [ -n "$n" ] || continue
        [ -f "$FORGOTTEN" ] && grep -qxF "$n" "$FORGOTTEN" && continue
        # Durable marker = the user closed this. Checked separately from the
        # transient file because that one is consumed (and, on a rejected batch,
        # deleted) within a tick, while this relaunch decision has to keep
        # honouring the close for as long as the marker stands.
        [ -f "$FORGOTTEN_D/$n" ] \
            && [ -n "$(find "$FORGOTTEN_D/$n" -maxdepth 0 -mtime -$TOMBSTONE_WINDOW 2>/dev/null)" ] \
            && continue
        ! tmux has-session -t="$n" 2>/dev/null && echo "$n"; done)
    # Same guard again right before relaunching: the socket may have gone (or a second server appeared) during
    # this tick, and then every name above is "missing".
    if [ -n "$MISSING" ] && [ ! -e "$SG_OFF" ]; then
        sg_guard || MISSING=""
    fi
    if [ -n "$MISSING" ]; then
        echo "[$(date -u +%FT%TZ)] drift: self-heal — relaunching missing: $(echo $MISSING | tr '\n' ' ')"
        # Run the heal in its OWN transient scope. Anything restore.sh starts
        # (notably a tmux server, when none is running) then belongs to that
        # scope instead of claude-remote-drift.service, whose cgroup systemd
        # tears down the instant this tick ends — which is what killed the
        # freshly restored fleet, twice, 9-12s after it came back.
        if command -v systemd-run >/dev/null 2>&1; then
            systemd-run --user --scope --quiet \
                bash "$HOME/.claude-remote/restore.sh" >/dev/null 2>&1 || true
        else
            bash "$HOME/.claude-remote/restore.sh" >/dev/null 2>&1 || true
        fi
    fi
fi
DRIFT_EOF
            chmod +x "$DRIFT.tmp.$$"
            # ---- rollback copy of drift.sh (D6-S) ----
            # Kept once when the script replaced here is v18 (kritika D2 N17): moving it back is the way back to v18, by
            # hand and only with Lukáš's consent that day (any v19 client's next install rewrites it to v19 again).
            # v19's compat line names v18 too, so the copy itself must not be v19 (a second installer may have replaced
            # the script in between), and `ln` publishes it only where no copy is yet (K2): never one over another.
            if grep -qE 'claude-remote-restore-v18( |$)' "$DRIFT" 2>/dev/null && [ ! -e "$DRIFT.v18" ]; then
                { cp -p "$DRIFT" "$DRIFT.v18.tmp.$$" && ! grep -q 'claude-remote-restore-v19' "$DRIFT.v18.tmp.$$" \
                    && ln "$DRIFT.v18.tmp.$$" "$DRIFT.v18" \
                    && echo "[$(date -u +%FT%TZ)] install: the v18 drift.sh kept as drift.sh.v18 (rollback)" >> "$HOME/.claude-remote/install.log"; } 2>/dev/null || :
                rm -f "$DRIFT.v18.tmp.$$"
            fi
            # ---- end of the rollback copy of drift.sh ----
            mv -f "$DRIFT.tmp.$$" "$DRIFT"
            echo "DRIFT_SCRIPT_INSTALLED"
        fi
        # The app's tmux anchor (ClaudeConfig.buildTmuxRunCommand) runs this before it may create a server.
        if ! grep -q "$MARKER" "$SOCKHEAL" 2>/dev/null; then
            echo "[$(date -u +%FT%TZ)] install: tmux-sockheal.sh missing $MARKER — rewriting; prior marker line: $(grep -m1 'claude-remote-restore-v' "$SOCKHEAL" 2>/dev/null || echo '<none/new file>')" >> "$HOME/.claude-remote/install.log"
            cat > "$SOCKHEAL.tmp.$$" <<'SOCKHEAL_EOF'
#!/usr/bin/env bash
# marker-compat (do NOT remove): claude-remote-restore-v6 claude-remote-restore-v7 claude-remote-restore-v8 claude-remote-restore-v9 claude-remote-restore-v10 claude-remote-restore-v11 claude-remote-restore-v12 claude-remote-restore-v13 claude-remote-restore-v14 claude-remote-restore-v15 claude-remote-restore-v16 claude-remote-restore-v17 claude-remote-restore-v18 claude-remote-restore-v19
# claude-remote-restore-v18 — tmux socket guard for the app's own tmux anchor. The app runs it before any command
# that may create a tmux server (a second server next to a live one is how 51 sessions got duplicated on 2026-11-02):
#   exit 0  nothing listens on the default socket path, or the socket answers: the app goes on as before
#   exit 3  it was unusable and is usable again (socket recreated with SIGUSR1, or directory made private): the app
#           attaches a session that is still there instead of killing and restarting it
#   exit 1  refused (split brain, hung, not healable): the app creates nothing; the reason goes to stderr
set -u

# ---- tmux socket guard (identical in restore.sh, drift.sh and tmux-sockheal.sh) ----
# Before anything relaunches sessions or creates a tmux server, count the servers LISTENING on the exact path
# ${TMUX_TMPDIR:-/tmp}/tmux-<uid>/default (a deleted socket file keeps its bound name in the kernel):
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
# ~/.claude-remote/drift-heal.off switches all of this off: the only rollback, since an older client never
# rewrites a v18 script.
SG_DIR="$HOME/.claude-remote"
SG_UID=$(id -u)
# tmux binds realpath(TMUX_TMPDIR)/tmux-<uid>/default: a trailing / or a symlink in TMUX_TMPDIR must not hide it.
SG_BASE="${TMUX_TMPDIR:-/tmp}"
SG_BASE=$(realpath -m -- "$SG_BASE" 2>/dev/null || printf '%s' "$SG_BASE")
SG_SOCK_DIR="$SG_BASE/tmux-$SG_UID"
SG_SOCK="$SG_SOCK_DIR/default"
SG_LOCK="$SG_DIR/tmux-server.lock"
SG_OFF="$SG_DIR/drift-heal.off"
SG_WHO=${SG_WHO:-script}
# A lasting fault logs one line per kind per 15 minutes, not one block per tick (1 440 a day). Per caller: drift.log,
# restore.log and sockheal.log each get the line (one caller's stamp silenced the fault in the others' logs).
# A line the stamp holds back still goes out with SG_QUIET in front when that is set (only tmux-sockheal.sh sets it:
# the log gets no copy, the refused tab gets its cause on stderr every time).
sg_log() {
    local st="$SG_DIR/.drift.sockguard.$SG_WHO.$1" now last
    now=$(date +%s); last=$(stat -c %Y "$st" 2>/dev/null || echo 0)
    if [ $((now - last)) -lt 900 ]; then
        [ -n "${SG_QUIET:-}" ] && echo "${SG_QUIET}[$(date -u +%FT%TZ)] sockguard($SG_WHO): $2"
        return 0
    fi
    touch "$st" 2>/dev/null || true
    echo "[$(date -u +%FT%TZ)] sockguard($SG_WHO): $2"
}
# One line per server listening on exactly $SG_SOCK: its pid, or "?" when it cannot be seen.
# Primary `ss -xlpH`; fallback /proc/net/unix, where only Flags=00010000 St=01 is the listening socket (the other
# rows with that path are accepted client connections).
sg_listeners() {
    local out ino pid
    if out=$(timeout 5 ss -xlpH 2>/dev/null); then
        printf '%s\n' "$out" | awk -v p="$SG_SOCK" '$2 == "LISTEN" && $5 == p {
            if (match($0, /pid=[0-9]+/)) print substr($0, RSTART + 4, RLENGTH - 4); else print "?" }'
        return 0
    fi
    awk -v p="$SG_SOCK" '$4 == "00010000" && $6 == "01" && $8 == p { print $7 }' /proc/net/unix 2>/dev/null \
        | while read -r ino; do
            pid=$(timeout 10 find /proc/[0-9]*/fd -maxdepth 1 -lname "socket:\[$ino\]" 2>/dev/null | head -1 | cut -d/ -f3)
            echo "${pid:-?}"
        done
}
sg_count() { printf '%s\n' "$1" | awk 'NF { n++ } END { print n + 0 }'; }
# Answers through its path AND for a plain client: only a plain client checks the directory (tmux refuses one others can
# enter, "unsafe permissions"), and drift, restore.sh and the app are plain clients.
sg_answers() {
    [ -S "$SG_SOCK" ] && timeout 5 tmux -S "$SG_SOCK" list-sessions >/dev/null 2>&1 \
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
    if o=$(timeout -k 1 5 tmux -S "$SG_SOCK" list-sessions 2>&1 >/dev/null </dev/null); then SG_PROBE=ok; return 0; fi
    case "$o" in *"no server running"*|*"onnection refused"*|*"No such file or directory"*) SG_PROBE=stale;; *) SG_PROBE=hung;; esac
}
sg_stale() { [ -S "$SG_SOCK" ] && sg_probe && [ "$SG_PROBE" = stale ]; }
# 0 when the socket directory is a real directory of ours with mode 700 (made so when it was looser); else 1.
sg_dir_private() {
    local m
    { [ ! -L "$SG_SOCK_DIR" ] && [ -d "$SG_SOCK_DIR" ] && [ "$(stat -c %u "$SG_SOCK_DIR" 2>/dev/null)" = "$SG_UID" ]; } || return 1
    m=$(stat -c %a "$SG_SOCK_DIR" 2>/dev/null)
    [ "$m" = 700 ] && return 0
    { chmod 700 "$SG_SOCK_DIR" 2>/dev/null && [ "$(stat -c %a "$SG_SOCK_DIR" 2>/dev/null)" = 700 ]; } || return 1
    echo "[$(date -u +%FT%TZ)] sockguard($SG_WHO): $SG_SOCK_DIR had mode $m — set to 700 (tmux clients refuse a socket directory others can enter)"
}
# Heal under the shared lock (drift, restore.sh's create branch and the app's tmux-sockheal.sh).
sg_heal() {
    local pid=$1
    (
        flock -w 2 8 || { sg_log lock "could not take $SG_LOCK within 2 s (another heal or create runs) — not relaunching now"; exit 1; }
        # healed by someone else meanwhile: usable again, as far as the caller is concerned
        sg_answers && exit 2
        case "$pid" in ''|*[!0-9]*) sg_log nopid "a tmux server listens on $SG_SOCK without its socket file and its pid is unknown — NOT relaunching"; exit 1;; esac
        if [ "$(cat "/proc/$pid/comm" 2>/dev/null)" != "tmux: server" ] || [ "$(stat -c %u "/proc/$pid" 2>/dev/null)" != "$SG_UID" ]; then
            sg_log notours "listener $pid on $SG_SOCK is not our tmux server — not signalling, NOT relaunching"; exit 1
        fi
        # SIGUSR1 cannot bind into a missing directory (the 2026-11-02 shape).
        [ -d "$SG_SOCK_DIR" ] || mkdir -p -m 700 "$SG_SOCK_DIR" 2>/dev/null
        if ! sg_dir_private; then
            sg_log baddir "$SG_SOCK_DIR is missing, a symlink, not ours or cannot be made private — not signalling, NOT relaunching"; exit 1
        fi
        # Re-checked right before the signal: still exactly this one server on the path (never signal one of two), and
        # the path is empty or a stale file (tmux unlinks it and binds anew). A socket that answers was healed meanwhile.
        [ "$(sg_listeners)" = "$pid" ] || { sg_log moved "the listeners on $SG_SOCK changed while healing — not signalling, NOT relaunching"; exit 1; }
        if [ ! -S "$SG_SOCK" ] || sg_stale; then
            kill -USR1 "$pid" 2>/dev/null || { sg_log kill "SIGUSR1 to $pid failed — NOT relaunching"; exit 1; }
        fi
        # At most 2 s in all: a deadline, not a number of tries (each try may itself wait).
        local end left
        end=$(( $(date +%s%N) / 1000000 + 2000 ))
        while left=$(( end - $(date +%s%N) / 1000000 )); [ "$left" -gt 0 ]; do
            [ -S "$SG_SOCK" ] && timeout "$((left / 1000)).$(printf %03d $((left % 1000)))" tmux -S "$SG_SOCK" list-sessions >/dev/null 2>&1 && exit 2
            sleep 0.1
        done
        sg_log healfail "tmux server $pid alive without its socket $SG_SOCK, SIGUSR1 did not bring it back — NOT relaunching"
        exit 1
    ) 8>>"$SG_LOCK"
}
sg_guard() {
    SG_WHY=
    [ -e "$SG_OFF" ] && return 0
    command -v tmux >/dev/null 2>&1 || return 0
    local pids n rc
    pids=$(sg_listeners)
    n=$(sg_count "$pids")
    [ "$n" -eq 0 ] && return 0
    if [ "$n" -ge 2 ]; then
        SG_WHY=split
        sg_log split "SPLIT BRAIN: $n tmux servers listen on $SG_SOCK (pids $(printf '%s' "$pids" | tr '\n' ' ')) — no signal, no relaunch, no new server; needs a human"
        return 1
    fi
    if [ -S "$SG_SOCK" ]; then
        # One wait for the answer (5 s), not sg_answers' and then sg_probe's: a hung server was refused after 10 s.
        sg_probe
        case "$SG_PROBE" in
            ok)
                timeout 5 env -u TMUX tmux list-sessions >/dev/null 2>&1 && return 0
                # it answers through its path but plain clients refuse the directory
                sg_dir_private && sg_answers && return 2
                SG_WHY=unsafe
                sg_log unsafe "$SG_SOCK answers only through -S: $SG_SOCK_DIR is not a private directory of ours and cannot be made one — NOT relaunching"
                return 1;;
            hung)
                SG_WHY=hung
                sg_log hung "tmux server $pids listens on $SG_SOCK but does not answer — NOT relaunching"
                return 1;;
        esac
    fi
    # the socket file is gone, or a stale file nobody accepts on took its place
    sg_heal "$pids"; rc=$?
    [ "$rc" -eq 2 ] && echo "[$(date -u +%FT%TZ)] sockguard($SG_WHO): tmux server $pids was alive without its socket — $SG_SOCK recreated (SIGUSR1), nothing relaunched"
    [ "$rc" -eq 1 ] && SG_WHY=heal
    return "$rc"
}
# ---- end of tmux socket guard ----
SG_WHO=app
SG_QUIET='#quiet# '
[ -e "$SG_OFF" ] && exit 0
mkdir -p "$SG_DIR" 2>/dev/null
# Only an event is logged: a normal launch must not create or grow any file. The last line carries rc and cause.
# Lines the rate-limit stamp holds back (SG_QUIET in front) stay out of the log; a refused tab gets every line.
SG_ALL=
sg_run() {
    local res tail out logged
    res=$(sg_guard 2>&1; printf '%s %s' "$?" "${SG_WHY:-}")
    tail=${res##*$'\n'}; out=${res%"$tail"}
    SG_RC=${tail%% *}; SG_WHY=${tail#* }
    logged=$(printf '%s' "$out" | sed "/^$SG_QUIET/d")
    [ -n "$logged" ] && printf '%s\n' "$logged" >> "$SG_DIR/sockheal.log"
    SG_ALL="$SG_ALL$out"
}
sg_run
# A heal that failed (lock not taken, listeners changed meanwhile, socket not back) mostly lost a race with another
# tab's heal or attach: one more look after 0.5 s before the tab is refused (S3).
if [ "$SG_RC" = 1 ] && [ "$SG_WHY" = heal ]; then sleep 0.5; sg_run; fi
case "$SG_RC" in
    0) exit 0;;
    2) exit 3;;
esac
case "$SG_WHY" in
    split) why="two tmux servers listen on $SG_SOCK (split brain) — a human must pick the one that stays";;
    hung) why="the tmux server on $SG_SOCK does not answer";;
    unsafe) why="$SG_SOCK_DIR is not a private directory of ours and cannot be made one";;
    *) why="a tmux server is alive on $SG_SOCK without its socket and it could not be recreated now";;
esac
printf '%s' "$SG_ALL" | sed "s/^$SG_QUIET//" >&2
echo "claude-remote: $why — not starting a second tmux server (details: $SG_DIR/sockheal.log)" >&2
exit 1
SOCKHEAL_EOF
            chmod +x "$SOCKHEAL.tmp.$$"
            mv -f "$SOCKHEAL.tmp.$$" "$SOCKHEAL"
            echo "SOCKHEAL_SCRIPT_INSTALLED"
        fi
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
        OWNED="$HOME/.config/systemd/user/claude-tmux.service"
        if [ -e "$OWNED" ] || [ -d "$OWNED.d" ]; then
            systemctl --user disable claude-tmux.service 2>/dev/null || true
            rm -f "$OWNED"
            rm -rf "$OWNED.d"
            systemctl --user daemon-reload 2>/dev/null || true
            echo "OWNED_UNIT_REMOVED"
        fi
        # Present = the unit runs our restore.sh (the unit's content is unchanged, so a written unit stays).
        if ! grep -qF "claude-remote/restore.sh" "$UNIT" 2>/dev/null; then
            cat > "$UNIT" <<UNIT_EOF
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
            loginctl enable-linger "$USER" 2>/dev/null || true
            echo "RESTORE_UNIT_INSTALLED"
        else
            echo "RESTORE_UNIT_PRESENT"
        fi
        if [ ! -f "$DUNIT" ] || [ ! -f "$DTIMER" ]; then
            cat > "$DUNIT" <<DUNIT_EOF
[Unit]
Description=Claude Remote — sync sessions.json with claude session_ids

[Service]
Type=oneshot
Environment=PATH=%h/.local/bin:%h/.npm-global/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=/usr/bin/env bash %h/.claude-remote/drift.sh
DUNIT_EOF
            cat > "$DTIMER" <<DTIMER_EOF
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
        LINGER=$(loginctl show-user "$USER" --property=Linger --value 2>/dev/null || echo unknown)
        echo "LINGER=$LINGER"