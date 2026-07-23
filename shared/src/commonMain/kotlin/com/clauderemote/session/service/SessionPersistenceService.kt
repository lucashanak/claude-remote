package com.clauderemote.session.service

import com.clauderemote.connection.SshManager
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.SessionActivity
import com.clauderemote.model.SessionStatus
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
    private val onActivityUpdate: (sessionId: String, activity: SessionActivity) -> Unit,
    private val disconnectSession: suspend (sessionId: String) -> Unit,
    private val onForgotten: (serverId: String, tmuxSessionName: String) -> Unit,
) {
    // sessions.json is one file per SERVER; with N sessions each running the
    // 15 s reconcile loop, N−1 of the flock+cat fetches were redundant. Cache
    // the parsed snapshot per server with a TTL just under the loop period so
    // only the first session to tick pays the exec.
    private class SessionsSnapshot(val at: Long, val list: List<PersistedSession>)
    private val sessionsJsonCache = java.util.concurrent.ConcurrentHashMap<String, SessionsSnapshot>()
    private val sessionsJsonMutex = Mutex()

    // Per-session pollers that read ~/.claude/sessions/<pid>.json on the
    // server to capture the *real* claude session_id — which can drift from
    // the UUID we passed via --session-id when the user invokes /resume,
    // /clear, /compact etc. Without this we'd push a stale UUID to
    // sessions.json and the next reboot's restore.sh would --resume the
    // wrong (or non-existent) conversation.
    private val sessionIdRefreshJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    private val fetchJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

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
    private val INSTALL_RESTORE_COMMAND = """
        set -e
        mkdir -p "${'$'}HOME/.claude-remote" "${'$'}HOME/.config/systemd/user"
        SCRIPT="${'$'}HOME/.claude-remote/restore.sh"
        DRIFT="${'$'}HOME/.claude-remote/drift.sh"
        UNIT="${'$'}HOME/.config/systemd/user/claude-remote-restore.service"
        DUNIT="${'$'}HOME/.config/systemd/user/claude-remote-drift.service"
        DTIMER="${'$'}HOME/.config/systemd/user/claude-remote-drift.timer"
        LOCK="${'$'}HOME/.claude-remote/sessions.lock"
        ANCHOR="${'$'}HOME/.config/systemd/user/claude-tmux.service"
        MARKER="claude-remote-restore-v9"
        touch "${'$'}LOCK"
        echo "[${'$'}(date -u +%FT%TZ)] install: invoked by client" >> "${'$'}HOME/.claude-remote/install.log"
        if ! grep -q "${'$'}MARKER" "${'$'}SCRIPT" 2>/dev/null; then
            echo "[${'$'}(date -u +%FT%TZ)] install: restore.sh missing ${'$'}MARKER — rewriting; prior marker line: ${'$'}(grep -m1 'claude-remote-restore-v' "${'$'}SCRIPT" 2>/dev/null || echo '<none/new file>')" >> "${'$'}HOME/.claude-remote/install.log"
            cat > "${'$'}SCRIPT" <<'RESTORE_EOF'
#!/usr/bin/env bash
# marker-compat (do NOT remove): claude-remote-restore-v6 claude-remote-restore-v7 claude-remote-restore-v8 claude-remote-restore-v9
# Makes any installed client's `grep -q ${'$'}MARKER` find its marker so it takes the
# PRESENT no-op path and does NOT rewrite this file (reverting the fixes) nor run
# the daemon-reload/enable reinstall that correlates with the tmux-server death.
# claude-remote-restore-v9 — recreates tmux+claude sessions from sessions.json (snapshot under flock)
set -u
LOG="${'$'}HOME/.claude-remote/restore.log"
exec >> "${'$'}LOG" 2>&1
echo "----- ${'$'}(date -u +%FT%TZ) restore.sh start (pid=${'$'}${'$'}) -----"
# Anchor the tmux server under the user slice: if none is running yet, create
# it (with a keepalive __anchor__ session) via systemd-run --scope so the
# forked server lives in user-1000.slice and survives SSH-session teardown
# (the mid-life mass-death root cause). Sessions recreated below then attach to
# that anchored server. If a server already exists this is a no-op.
export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/run/user/${'$'}(id -u)}"
tmux list-sessions >/dev/null 2>&1 || systemd-run --user --scope --quiet tmux new-session -d -s __anchor__ sleep infinity >/dev/null 2>&1 || true
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
    for f in "${'$'}RESTORE_SRC" "${'$'}SESSIONS_FILE" "${'$'}SESSIONS_FILE.bak"; do
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
if [ "${'$'}HAVE_JQ" = "1" ]; then
    COUNT=${'$'}(echo "${'$'}SNAP" | jq 'length')
    for i in ${'$'}(seq 0 ${'$'}((COUNT-1))); do
        TMUX_NAME=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].tmuxSessionName")
        FOLDER=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].folder")
        MODE=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].mode")
        MODEL=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].model")
        UUID=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].claudeSessionId // empty")
        tmux has-session -t "${'$'}TMUX_NAME" 2>/dev/null && continue
        FOLDER_EXP="${'$'}{FOLDER/#\~/${'$'}HOME}"
        case "${'$'}FOLDER_EXP" in /*) ;; *) FOLDER_EXP="${'$'}HOME/${'$'}FOLDER_EXP";; esac
        [ -d "${'$'}FOLDER_EXP" ] || { echo "skip ${'$'}TMUX_NAME — folder ${'$'}FOLDER_EXP missing"; continue; }
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
            ENC=${'$'}(echo "${'$'}FOLDER_EXP" | sed 's|/|-|g')
            JSONL="${'$'}HOME/.claude/projects/${'$'}ENC/${'$'}UUID.jsonl"
            if [ -f "${'$'}JSONL" ]; then
                ARGS+=(--resume "${'$'}UUID")
            else
                echo "no transcript at ${'$'}JSONL — launching fresh with --session-id ${'$'}UUID"
                ARGS+=(--session-id "${'$'}UUID")
            fi
        fi
        CMD="${'$'}{ARGS[*]}"
        # `exec bash -l` keepalive: when claude exits (crash, usage limit, OOM,
        # /exit, network blip) the pane drops to a login shell instead of the
        # whole tmux session vanishing — matches app-created sessions, which
        # run claude via `send-keys` into a shell. Without this, a restored
        # session is fragile: it disappears the moment claude stops.
        if tmux new-session -d -s "${'$'}TMUX_NAME" -c "${'$'}FOLDER_EXP" \
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
            *\}*)
                if [ -n "${'$'}{TMUX_NAME:-}" ] && [ -n "${'$'}{FOLDER:-}" ]; then
                    if ! tmux has-session -t "${'$'}TMUX_NAME" 2>/dev/null; then
                        FOLDER_EXP="${'$'}{FOLDER/#\~/${'$'}HOME}"
                        [ -d "${'$'}FOLDER_EXP" ] && {
                            CMD="claude --allow-dangerously-skip-permissions"
                            [ -n "${'$'}{UUID:-}" ] && CMD="${'$'}CMD --resume ${'$'}UUID"
                            tmux new-session -d -s "${'$'}TMUX_NAME" -c "${'$'}FOLDER_EXP" \
                                "tmux set-option -g mouse on; tmux set-option -g history-limit 100000; ${'$'}CMD; exec bash -l"
                            echo "Restored ${'$'}TMUX_NAME"
                        }
                    fi
                fi
                TMUX_NAME=""; FOLDER=""; MODE=""; MODEL=""; UUID=""
                ;;
        esac
    done < "${'$'}SESSIONS_FILE"
fi
RESTORE_EOF
            chmod +x "${'$'}SCRIPT"
            echo "RESTORE_SCRIPT_INSTALLED"
        else
            echo "RESTORE_SCRIPT_PRESENT"
        fi
        if ! grep -q "${'$'}MARKER" "${'$'}DRIFT" 2>/dev/null; then
            echo "[${'$'}(date -u +%FT%TZ)] install: drift.sh missing ${'$'}MARKER — rewriting; prior marker line: ${'$'}(grep -m1 'claude-remote-restore-v' "${'$'}DRIFT" 2>/dev/null || echo '<none/new file>')" >> "${'$'}HOME/.claude-remote/install.log"
            cat > "${'$'}DRIFT" <<'DRIFT_EOF'
#!/usr/bin/env bash
# marker-compat (do NOT remove): claude-remote-restore-v6 claude-remote-restore-v7 claude-remote-restore-v8 claude-remote-restore-v9
# Makes any installed client's `grep -q ${'$'}MARKER` find its marker so it takes the
# PRESENT no-op path and does NOT rewrite this file (reverting the fixes) nor run
# the daemon-reload/enable reinstall that correlates with the tmux-server death.
# claude-remote-restore-v9 — drift daemon: reconciles sessions.json to mirror
# the LIVE claude-server-* tmux sessions every minute. Self-healing: re-adds
# live sessions a misbehaving/old client truncated away, refreshes
# claudeSessionId from claude's per-pid state files, preserves client-set
# metadata (serverId/alias) for known sessions, and drops entries whose tmux
# session is gone. A single buggy `cat > sessions.json` overwrite from any
# client is non-fatal — within 60s the snapshot is rebuilt from ground truth,
# so the next reboot's restore service still rebuilds every live session.
set -u
LOG="${'$'}HOME/.claude-remote/drift.log"
exec >> "${'$'}LOG" 2>&1
echo "----- ${'$'}(date -u +%FT%TZ) drift start -----"
SF="${'$'}HOME/.claude-remote/sessions.restore.json"   # server-owned source of truth (client never writes it)
SF_APP="${'$'}HOME/.claude-remote/sessions.json"        # mirror the app/client reads (drift keeps it in sync)
LOCK="${'$'}HOME/.claude-remote/sessions.lock"
FORGOTTEN="${'$'}HOME/.claude-remote/forgotten"          # tombstones: tmux names a client explicitly closed
command -v tmux >/dev/null 2>&1 || { echo "no tmux"; exit 0; }
command -v jq >/dev/null 2>&1 || { echo "no jq"; exit 0; }
touch "${'$'}LOCK"

# SHUTDOWN GUARD: during a reboot/shutdown systemd tears tmux down before the
# machine halts, so a drift tick here would see LIVE=[] and blank sessions.json
# — the exact file the next boot's restore.sh reads. Skip reconcile entirely
# while the system is going down so the restore manifest survives the reboot.
STATE=${'$'}(systemctl is-system-running 2>/dev/null || true)
case "${'$'}STATE" in
    stopping|offline) echo "system ${'$'}STATE — skip reconcile (preserve restore manifest)"; exit 0;;
esac

# Walk the tmux pane's process tree to find the claude process — pane_pid is
# often bash (claude launched via a shell command / keepalive), so claude is a
# descendant. Recursive descent finds the right pid.
find_claude_descendant() {
    local p=${'$'}1
    if [ "${'$'}(ps -o comm= -p "${'$'}p" 2>/dev/null)" = "claude" ]; then echo "${'$'}p"; return 0; fi
    local c r
    for c in ${'$'}(pgrep -P "${'$'}p" 2>/dev/null); do
        r=${'$'}(find_claude_descendant "${'$'}c"); [ -n "${'$'}r" ] && { echo "${'$'}r"; return 0; }
    done
}

# Ground-truth entry list from the live tmux sessions.
LIVE="[]"
for s in ${'$'}(tmux list-sessions -F '#{session_name}' 2>/dev/null); do
    case "${'$'}s" in claude-server-*) ;; *) continue;; esac
    pane_pid=${'$'}(tmux list-panes -t "${'$'}s" -F '#{pane_pid}' 2>/dev/null | head -1)
    [ -n "${'$'}pane_pid" ] || continue
    folder=${'$'}(tmux display-message -p -t "${'$'}s" '#{pane_current_path}' 2>/dev/null)
    pid=${'$'}(find_claude_descendant "${'$'}pane_pid")
    sid=""; model="DEFAULT"; mode="YOLO"
    if [ -n "${'$'}pid" ]; then
        args=${'$'}(tr '\0' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null)
        case "${'$'}args" in
            *"--model opus"*)   model=OPUS;;
            *"--model sonnet"*) model=SONNET;;
            *"--model haiku"*)  model=HAIKU;;
            *"--model fable"*)  model=FABLE;;
        esac
        case "${'$'}args" in
            *"--permission-mode auto"*) mode=AUTO;;
            *"--permission-mode acceptEdits"*) mode=AUTO_ACCEPT;;
            *"--allow-dangerously-skip-permissions"*) mode=DEFAULT;;
        esac
        sid=${'$'}(echo "${'$'}args" | sed -n 's/.*--\(resume\|session-id\) \([0-9a-f-]*\).*/\2/p' | head -1)
        psf="${'$'}HOME/.claude/sessions/${'$'}pid.json"
        if [ -f "${'$'}psf" ]; then
            v=${'$'}(jq -r .sessionId "${'$'}psf" 2>/dev/null)
            [ -n "${'$'}v" ] && [ "${'$'}v" != "null" ] && sid="${'$'}v"
        fi
    fi
    case "${'$'}s" in *--*) alias="${'$'}{s##*--}";; *) alias="";; esac
    LIVE=${'$'}(echo "${'$'}LIVE" | jq \
        --arg n "${'$'}s" --arg f "${'$'}folder" --arg m "${'$'}mode" --arg md "${'$'}model" --arg a "${'$'}alias" --arg sid "${'$'}sid" \
        '. + [{id:${'$'}n, serverId:"", folder:${'$'}f, mode:${'$'}m, model:${'$'}md, tmuxSessionName:${'$'}n, connectionType:"SSH", alias:${'$'}a, claudeSessionId:(if ${'$'}sid=="" then null else ${'$'}sid end), createdAt:0}]')
done
echo "LIVE=${'$'}(echo "${'$'}LIVE" | jq -c 'map(.tmuxSessionName)')"
(
    flock -x 9
    OLD="[]"; [ -f "${'$'}SF" ] && OLD=${'$'}(cat "${'$'}SF")
    # Tombstones: one tmux name per line a client explicitly closed. Subtract
    # them from the reconcile so a real close is honoured even if its
    # sessions.json push failed, and (below) from the self-heal list so a
    # forgotten session is never relaunched.
    # TOMBSTONE DISABLED (2026-07-23): the client fires forgetSession on a mere
    # server-death ("closed on another device, forgetting locally"), so honoring
    # ~/.claude-remote/forgotten let a whole-server outage wipe the manifest.
    # Ignore tombstones until the CLIENT only forgets a genuine user-close — so
    # never-blank/union is unconditional and a death can never shrink the SoT.
    FORGET="[]"
    FGLEN=0
    # Keep client metadata for sessions already in OLD (refresh only the live
    # claudeSessionId); add live sessions missing from OLD.
    # UNION reconcile — never DROP a manifest entry just because its tmux isn't
    # live right now (it may have crashed; self-heal below relaunches it).
    # Dropping it would let a client reading the shrunken manifest wrongly
    # "forget" the session. Keep every OLD entry (refresh its live sid), append
    # live sessions not yet in the manifest, THEN subtract tombstoned names so
    # an explicit close leaves the manifest for good.
    NEW=${'$'}(jq -n --argjson old "${'$'}OLD" --argjson live "${'$'}LIVE" --argjson forget "${'$'}FORGET" '
        ((${'$'}live | map({key:.tmuxSessionName, value:.}) | from_entries) as ${'$'}lm
        | (${'$'}old | map(.tmuxSessionName)) as ${'$'}on
        | (${'$'}old | map(. as ${'$'}o | (${'$'}lm[${'$'}o.tmuxSessionName]) as ${'$'}l
             | if ${'$'}l and (${'$'}l.claudeSessionId != null) then ${'$'}o + {claudeSessionId:${'$'}l.claudeSessionId} else ${'$'}o end))
          + (${'$'}live | map(select(.tmuxSessionName as ${'$'}n | (${'$'}on | index(${'$'}n)) | not))))
        | map(select(.tmuxSessionName as ${'$'}n | (${'$'}forget | index(${'$'}n)) | not))' 2>/dev/null)
    NEWLEN=${'$'}(echo "${'$'}NEW" | jq 'length' 2>/dev/null || echo 0)
    OLDLEN=${'$'}(echo "${'$'}OLD" | jq 'length // 0' 2>/dev/null || echo 0)
    if [ -n "${'$'}NEW" ] && [ "${'$'}NEW" != "${'$'}OLD" ]; then
        # NEVER replace a non-empty manifest with an empty one — a live tmux
        # server that momentarily lists no claude-server-* sessions (crash,
        # race, shutdown the STATE probe missed) must not destroy the restore
        # manifest. Exception: an empty result driven by tombstones (FGLEN>0)
        # is a real "closed everything" and is allowed through, else a closed
        # session would be resurrected on the next reboot.
        if [ "${'$'}NEWLEN" -eq 0 ] && [ "${'$'}OLDLEN" -gt 0 ] && [ "${'$'}FGLEN" -eq 0 ]; then
            echo "[${'$'}(date -u +%FT%TZ)] drift: refusing to blank non-empty manifest (${'$'}OLDLEN entries) — likely shutdown/tmux restart"
        else
            # Roll a backup of the prior manifest MINUS tombstones, so neither a
            # bak-fallback in restore.sh nor a later tick can resurrect a session
            # the user explicitly closed.
            if [ "${'$'}OLDLEN" -gt 0 ]; then
                echo "${'$'}OLD" | jq --argjson forget "${'$'}FORGET" 'map(select(.tmuxSessionName as ${'$'}n | (${'$'}forget | index(${'$'}n)) | not))' > "${'$'}SF.bak.tmp.${'$'}${'$'}" 2>/dev/null \
                    && mv "${'$'}SF.bak.tmp.${'$'}${'$'}" "${'$'}SF.bak" || cp -f "${'$'}SF" "${'$'}SF.bak" 2>/dev/null || true
            fi
            echo "${'$'}NEW" > "${'$'}SF.tmp.${'$'}${'$'}" && mv "${'$'}SF.tmp.${'$'}${'$'}" "${'$'}SF"
            echo "[${'$'}(date -u +%FT%TZ)] drift: reconciled ${'$'}NEWLEN live (was ${'$'}OLDLEN)"
        fi
    fi
    # Tombstone self-clean: a forgotten name now absent from BOTH live tmux AND
    # the written manifest has done its job — drop it so a future session
    # reusing the same tmux name isn't blocked. Race-safe under this flock.
    if [ -f "${'$'}FORGOTTEN" ] && [ -s "${'$'}FORGOTTEN" ]; then
        MANIFEST="[]"; [ -f "${'$'}SF" ] && MANIFEST=${'$'}(cat "${'$'}SF")
        KEEP=${'$'}(while IFS= read -r fn; do
            [ -n "${'$'}fn" ] || continue
            if tmux has-session -t "${'$'}fn" 2>/dev/null; then echo "${'$'}fn"; continue; fi
            if echo "${'$'}MANIFEST" | jq -e --arg n "${'$'}fn" '[.[].tmuxSessionName] | index(${'$'}n)' >/dev/null 2>&1; then echo "${'$'}fn"; fi
        done < "${'$'}FORGOTTEN")
        if [ -n "${'$'}KEEP" ]; then printf '%s\n' "${'$'}KEEP" > "${'$'}FORGOTTEN.tmp.${'$'}${'$'}" && mv "${'$'}FORGOTTEN.tmp.${'$'}${'$'}" "${'$'}FORGOTTEN"; else : > "${'$'}FORGOTTEN"; fi
    fi
) 9<>"${'$'}LOCK"

# Mirror the server-owned source of truth to the app-facing sessions.json (the
# client may have blanked/shrunk it on reconnect); keeps the app view correct
# within 60s and re-expands a client that wrongly forgot sessions.
if [ -f "${'$'}SF" ] && ! cmp -s "${'$'}SF" "${'$'}SF_APP" 2>/dev/null; then
    cp -f "${'$'}SF" "${'$'}SF_APP" 2>/dev/null || true
fi

# SELF-HEAL: relaunch any manifest session whose tmux is gone (tmux-server
# crash, OOM, stray kill) so it returns within 60s instead of staying dead
# until a reboot. Critically this keeps tmux from ever sitting empty — an empty
# server is exactly the signal that makes clients wrongly "forget" every
# session and shrink the manifest. restore.sh is idempotent: skips live
# sessions and missing folders. Tombstoned names are skipped so an explicit
# close is never relaunched.
if [ -f "${'$'}SF" ]; then
    MISSING=${'$'}(jq -r '.[].tmuxSessionName' "${'$'}SF" 2>/dev/null | while IFS= read -r n; do
        [ -n "${'$'}n" ] || continue
        ! tmux has-session -t "${'$'}n" 2>/dev/null && echo "${'$'}n"; done)
    if [ -n "${'$'}MISSING" ]; then
        echo "[${'$'}(date -u +%FT%TZ)] drift: self-heal — relaunching missing: ${'$'}(echo ${'$'}MISSING | tr '\n' ' ')"
        bash "${'$'}HOME/.claude-remote/restore.sh" >/dev/null 2>&1 || true
    fi
fi
DRIFT_EOF
            chmod +x "${'$'}DRIFT"
            echo "DRIFT_SCRIPT_INSTALLED"
        fi
        if ! grep -q "__anchor__" "${'$'}ANCHOR" 2>/dev/null; then
            cat > "${'$'}ANCHOR" <<'ANCHOR_EOF'
[Unit]
# claude-remote-restore-v8 — persistent tmux server anchored to the lingering
# user manager (user-1000.slice), NOT an ephemeral SSH login scope. Without
# this the tmux server inherits the cgroup of whatever SSH session first ran
# `tmux new-session`, and dies when that session's scope is torn down (the
# root cause of the mid-life mass session death). Starting the server from
# this user service pins it under user@.service for its whole life; every
# later `tmux new-session` (restore.sh, client) attaches to it, so all panes
# live under the user slice too.
Description=Claude Remote — persistent tmux server (anchored to user slice)
Before=claude-remote-restore.service

[Service]
Type=oneshot
RemainAfterExit=yes
# `exec sleep infinity` keepalive session so the server never exits on its own
# when the last real session closes.
ExecStart=/usr/bin/tmux new-session -d -s __anchor__ sleep infinity
ExecStop=/usr/bin/tmux kill-session -t __anchor__

[Install]
WantedBy=default.target
ANCHOR_EOF
            systemctl --user daemon-reload 2>/dev/null || true
            systemctl --user enable claude-tmux.service 2>/dev/null || true
            echo "ANCHOR_UNIT_INSTALLED"
        else
            echo "ANCHOR_UNIT_PRESENT"
        fi
        if ! grep -q "claude-remote-restore" "${'$'}UNIT" 2>/dev/null; then
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
     * Track which servers we've already attempted install on this app session
     * to avoid pinging on every persist. Best-effort — if the install fails
     * (no systemd, e.g. macOS, BSD), the in-app reconnect path still works.
     */
    private val installedRestoreServers = mutableSetOf<String>()

    /**
     * Pull the authoritative `~/.claude-remote/sessions.json` from the
     * server and reconcile this tab's claudeSessionId with whatever the
     * server-side drift daemon has recorded. Replaces the older per-pid
     * probe — the server now owns the truth, the client just mirrors it.
     */
    fun startSessionIdRefresh(sessionId: String, tmuxName: String, sshManager: SshManager) {
        if (sessionStorage == null) return
        sessionIdRefreshJobs[sessionId]?.cancel()
        sessionIdRefreshJobs[sessionId] = scope.launch {
            kotlinx.coroutines.delay(1000)
            while (isActive) {
                // Skip in background: this is 2 SSH execs every 15s per session
                // (~104 radio wakeups/min at 13 sessions) and only matters for
                // UUID/restore correctness, which onResume refreshes anyway.
                if (isBackground()) { kotlinx.coroutines.delay(15_000); continue }
                try {
                    val remote = fetchSessionsCached(registry.serverIdOf(sessionId), sshManager)
                    val entry = remote?.firstOrNull { it.tmuxSessionName == tmuxName }
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
                    val tab = tabManager.getTab(sessionId)
                    if (tab != null && !realUuid.isNullOrBlank() && tab.claudeSessionId != realUuid) {
                        // Refuse to adopt a UUID already owned by another
                        // tab — two transcript streams pointing at the
                        // same jsonl would mirror identical content
                        // across both sessions, which is exactly the
                        // "občas se chat historie zobrazuje stejně" bug.
                        // Happens when the user picks the same /resume
                        // conversation in two tabs, or when a tmux name
                        // collision lets the drift daemon attribute one
                        // claude pid to two tab rows.
                        val claimedByOther = tabManager.tabs.value.any {
                            it.id != sessionId && it.claudeSessionId == realUuid
                        }
                        if (claimedByOther) {
                            FileLogger.log(TAG, "Skip UUID drift $realUuid for $sessionId — already owned by another tab")
                        } else {
                            val source = if (entry?.claudeSessionId == realUuid) "server" else "pid-probe"
                            FileLogger.log(TAG, "Session $sessionId UUID synced from $source: ${tab.claudeSessionId} -> $realUuid")
                            tabManager.updateClaudeSessionId(sessionId, realUuid)
                            sessionStorage.upsert(SessionStorage.fromClaudeSession(tab.copy(claudeSessionId = realUuid)))
                            transcript.notifyClaudeSessionIdChanged(sessionId, realUuid)
                            transcript.setConfirmedUuid(sessionId, realUuid)
                        }
                    }
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    /**
     * Read the server's authoritative sessions.json under a shared file
     * lock (so we never read mid-write from the drift daemon or restore.sh).
     * Returns null on transport failure, empty list on missing file or
     * parse error.
     */
    private suspend fun fetchSessionsFromServer(sshManager: SshManager): List<PersistedSession>? {
        return withContext(Dispatchers.IO) {
            try {
                val sshSession = sshManager.getSession() ?: return@withContext null
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(
                    "touch \"\$HOME/.claude-remote/sessions.lock\"; " +
                    "flock -s \"\$HOME/.claude-remote/sessions.lock\" " +
                    "cat \"\$HOME/.claude-remote/sessions.json\" 2>/dev/null"
                )
                ch.inputStream = null
                val input = ch.inputStream
                val out = try {
                    ch.connect(3000)
                    input.bufferedReader().readText().trim()
                } finally {
                    try { ch.disconnect() } catch (_: Exception) {}
                }
                if (out.isEmpty()) emptyList()
                else fetchJson.decodeFromString<List<PersistedSession>>(out)
            } catch (e: Exception) {
                FileLogger.error(TAG, "fetchSessionsFromServer failed: ${e.message}", e)
                null
            }
        }
    }

    /**
     * True unless the server's authoritative sessions.json was fetched
     * successfully AND no longer lists this tmux name — i.e. some device's
     * forgetSession() removed it. Fail-open (true) on any fetch problem so a
     * network blip can't be mistaken for "closed elsewhere" and wrongly drop
     * a tab that's still legitimately tracked.
     */
    suspend fun stillTrackedOnServer(sshManager: SshManager, tmuxSessionName: String): Boolean {
        val remote = fetchSessionsFromServer(sshManager) ?: return true
        return remote.any { it.tmuxSessionName == tmuxSessionName }
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
    ): List<PersistedSession>? {
        if (serverId == null) return fetchSessionsFromServer(sshManager)
        val ttl = 12_000L // just under the 15 s loop period
        sessionsJsonCache[serverId]?.let {
            if (System.currentTimeMillis() - it.at < ttl) return it.list
        }
        return sessionsJsonMutex.withLock {
            // Re-check under the lock — another session's tick may have just
            // refreshed it while we waited.
            sessionsJsonCache[serverId]?.let {
                if (System.currentTimeMillis() - it.at < ttl) return@withLock it.list
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
        if (serverId != null) {
            synchronized(installedRestoreServers) {
                if (!installedRestoreServers.add(serverId)) return
            }
        }
        try {
            val out = withContext(Dispatchers.IO) {
                val sshSession = sshManager.getSession() ?: return@withContext "NO_SESSION"
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(INSTALL_RESTORE_COMMAND)
                ch.inputStream = null
                val input = ch.inputStream
                ch.connect(15_000)
                val text = input.bufferedReader().readText().trim()
                ch.disconnect()
                text
            }
            FileLogger.log(TAG, "Restore service install: $out")
            if (out.contains("LINGER=no") || out.contains("LINGER=unknown")) {
                FileLogger.log(TAG,
                    "WARNING: linger is not enabled on the server — the restore service " +
                    "will only fire after a manual login. Run `sudo loginctl enable-linger \$USER` " +
                    "on the server to make session persistence work after reboot."
                )
            }
        } catch (e: Exception) {
            FileLogger.error(TAG, "Restore service install failed: ${e.message}", e)
        }
    }

    /**
     * Push the per-server `sessions.json` snapshot to the remote server via
     * `cat > tmp && mv tmp final` (atomic rename). The systemd restore unit
     * reads this file at boot.
     */
    suspend fun pushSessionsToServer(sshManager: SshManager, serverId: String) {
        val storage = sessionStorage ?: return
        try {
            val payload = storage.serializeForServer(serverId)
            withContext(Dispatchers.IO) {
                val sshSession = sshManager.getSession() ?: return@withContext
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                // MERGE, not overwrite. The previous `cat > tmp && mv` clobbered
                // the shared sessions.json with only THIS client's sessions, so
                // whichever client (Android vs desktop) synced last silently
                // dropped the others' sessions — and the next reboot's restore
                // service then only rebuilt that truncated subset.
                //
                // New semantics (under the same flock the drift daemon + restore
                // use): keep the incoming list (this client wins for its own
                // sessions) PLUS any existing entry whose tmux session is still
                // LIVE on the server and isn't already in the incoming list.
                // Killed/forgotten sessions (kill-session runs before this push)
                // are no longer live and aren't in incoming, so they correctly
                // drop out — no resurrection on reboot. Falls back to a plain
                // overwrite when jq is unavailable (matches old behaviour).
                //
                // The incoming + scratch temp files are suffixed with the remote
                // shell's PID ($$) so a burst of near-simultaneous pushes (the
                // app fires several on a multi-tab reconnect) can't race on a
                // shared path — without per-PID names an earlier push would
                // `rm` the incoming file out from under a later one, which then
                // merged against an empty incoming and collapsed sessions.json.
                val safeServerId = serverId.replace("\"", "")
                ch.setCommand(
                    "set -u; D=\"\$HOME/.claude-remote\"; mkdir -p \"\$D\"; " +
                    "LOCK=\"\$D/sessions.lock\"; touch \"\$LOCK\"; " +
                    "SF=\"\$D/sessions.json\"; INC=\"\$D/.sessions.incoming.\$\$\"; " +
                    "cat > \"\$INC\"; " +
                    "if command -v jq >/dev/null 2>&1; then " +
                      "LIVE=\$(tmux list-sessions -F '#{session_name}' 2>/dev/null | jq -R . | jq -s . 2>/dev/null); " +
                      "[ -n \"\$LIVE\" ] || LIVE='[]'; " +
                      "( flock -x 9; " +
                        "OLD='[]'; [ -f \"\$SF\" ] && OLD=\$(cat \"\$SF\"); " +
                        "MERGED=\$(jq -n --slurpfile inc \"\$INC\" --argjson old \"\$OLD\" --argjson live \"\$LIVE\" '" +
                          "(\$inc[0] // []) as \$incoming " +
                          "| (\$incoming | map(.tmuxSessionName)) as \$names " +
                          "| \$incoming + (\$old | map(. as \$e | select(((\$names | index(\$e.tmuxSessionName)) | not) and (\$live | index(\$e.tmuxSessionName)))))" +
                        "' 2>/dev/null); " +
                        "if [ -n \"\$MERGED\" ]; then printf '%s' \"\$MERGED\" > \"\$SF.tmp.\$\$\" && mv \"\$SF.tmp.\$\$\" \"\$SF\"; else cp \"\$INC\" \"\$SF\"; fi " +
                      ") 9<>\"\$LOCK\"; " +
                    "else cp \"\$INC\" \"\$SF\"; fi; " +
                    "rm -f \"\$INC\"; " +
                    "echo \"[\$(date -u +%FT%TZ)] push(merge): ${payload.length} bytes for $safeServerId\" >> \"\$D/push.log\""
                )
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
     * Forget a session permanently (used when user explicitly closes a tab,
     * not just disconnects). Removes the persisted record so it won't be
     * resurrected on next app start, and re-syncs the server-side
     * sessions.json so systemd doesn't try to restore it after reboot.
     */
    suspend fun forgetSession(sessionId: String) {
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
        if (session != null) {
            scope.launch {
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
                        try {
                            val killed = com.clauderemote.connection.TmuxManager.killSession(
                                cleanupConn.getSession() ?: error("no ssh"),
                                session.tmuxSessionName
                            )
                            if (!killed) {
                                FileLogger.error(TAG, "Tmux kill returned failure for $sessionId (${session.tmuxSessionName}) — pane may still be alive", null)
                            }
                        } catch (e: Exception) {
                            FileLogger.error(TAG, "Tmux kill failed for $sessionId: ${e.message}", e)
                        }
                        try {
                            pushSessionsToServer(cleanupConn, session.server.id)
                        } catch (e: Exception) {
                            FileLogger.error(TAG, "sessions.json push failed for ${session.server.id}: ${e.message}", e)
                        }
                        // Durable tombstone: also record the close in the server-side
                        // `forgotten` file over this same cleanup connection. If the
                        // sessions.json push above lost the merge race (or failed), the
                        // drift daemon still drops this session from the restore manifest
                        // and won't relaunch it — a close stays a close across reboot.
                        // Written under the shared flock so it can't race the drift
                        // daemon's tombstone self-clean.
                        try {
                            val ssh = cleanupConn.getSession()
                            if (ssh != null) {
                                val safeName = session.tmuxSessionName.replace("'", "")
                                val ch = ssh.openChannel("exec") as com.jcraft.jsch.ChannelExec
                                ch.setCommand(
                                    "mkdir -p \"\$HOME/.claude-remote\"; " +
                                    "L=\"\$HOME/.claude-remote/sessions.lock\"; touch \"\$L\"; " +
                                    "flock -x \"\$L\" bash -c 'printf \"%s\\n\" \"\$0\" >> \"\$1\"' " +
                                    "'$safeName' \"\$HOME/.claude-remote/forgotten\""
                                )
                                ch.inputStream = null
                                ch.connect(5000)
                                val deadline = System.currentTimeMillis() + 5000
                                while (!ch.isClosed && System.currentTimeMillis() < deadline) {
                                    kotlinx.coroutines.delay(50)
                                }
                                ch.disconnect()
                            }
                        } catch (e: Exception) {
                            FileLogger.error(TAG, "forgotten tombstone write failed for $sessionId: ${e.message}", e)
                        }
                    }
                } finally {
                    try { cleanupConn?.disconnect() } catch (_: Exception) {}
                }
            }
        }
    }

    suspend fun renameTmuxSession(sessionId: String, oldName: String, newName: String) {
        withContext(Dispatchers.IO) {
            try {
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
    fun stopIdRefresh(sessionId: String) {
        sessionIdRefreshJobs.remove(sessionId)?.cancel()
    }
}
