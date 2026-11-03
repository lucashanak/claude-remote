package com.clauderemote.session.service

/**
 * Server-side half of the background-state hooks ("hooks v2"): the `hook-ev.sh`
 * script that Claude Code runs on a handful of events, the settings.json merge
 * that wires it up (and removes it again), and the read-only status probe.
 *
 * Everything here is text that runs on the SERVER, so a mistake is invisible to
 * the Kotlin compiler and lands in every Claude session of every account on the
 * box at once (Claude reloads settings.json while running). The scripts are
 * therefore syntax-checked and executed by tests (`HookCommandsSyntaxTest`,
 * `HookMergeTest`, `HookScriptIntegrationTest`), and the rules below are the
 * contract those tests pin.
 *
 * In the scripts below `§` stands for `$` (a Kotlin raw string cannot hold `$`
 * without noise; [sh] swaps it in).
 *
 * ## What gets installed (only on the user's explicit request, per server)
 *
 * One settings.json entry per event, all carrying the marker `claude-remote-ev v<N>`
 * ([ENTRY_VERSION]):
 *
 * | Hook              | Matcher           | `hook-ev.sh` argument |
 * |-------------------|-------------------|-----------------------|
 * | UserPromptSubmit  | -                 | `work`                |
 * | PreToolUse        | AskUserQuestion   | `ask`                 |
 * | PostToolUse       | AskUserQuestion   | `askdone`             |
 * | PermissionRequest | -                 | `perm`                |
 * | Stop              | -                 | `stop`                |
 * | SessionStart      | -                 | `start`               |
 * | SessionEnd        | -                 | `end`                 |
 *
 * The legacy `Stop` entry (marker `claude-remote-notify`, [NotificationService]'s
 * `LEGACY_STOP_HOOK_COMMAND`) is never touched: old app builds rewrite it on any
 * difference. The v2 `Stop` entry is a separate item with a different marker, so
 * the legacy ensure (which only looks for its own marker) leaves it alone and a
 * mixed fleet stays byte-stable.
 *
 * ## The settings.json command
 *
 * `: claude-remote-ev v1; H=…/hook-ev.sh; [ -f …/hookev.off ] || { [ -x "§H" ] && "§H" <EV> >/dev/null 2>&1; }; exit 0`
 *
 *  - `>/dev/null 2>&1`: stdout of UserPromptSubmit / SessionStart hooks is added to
 *    the model's context, so nothing may ever reach it;
 *  - `exit 0` always: exit 2 blocks the prompt / tool, exit 1 shows an error;
 *  - `~/.claude-remote/hookev.off` is an immediate off switch with no settings.json
 *    rewrite (a rewrite would reload every live session again).
 *
 * ## Merge rules
 *
 *  - target = `realpath` of each settings.json (`~/.claude` and every
 *    `~/.claude-remote/accounts/<a>/`; account files are symlinks to the main one
 *    today): `os.replace` goes onto the real file, never onto a symlink;
 *  - one flock, the same file as the legacy ensure (`~/.claude/settings.json.claude-remote.lock`);
 *  - unparseable / non-object JSON, or a `hooks` / event list of an unexpected
 *    shape: `FAILED`, nothing written;
 *  - an entry of a NEWER version ([ENTRY_VERSION] < N): `NEWER`, nothing written;
 *  - per event list: drop every non-canonical entry carrying the marker, append the
 *    canonical one, never touch any other entry;
 *  - before a write: backup `settings.json.claude-remote.bak.<epoch>` (newest 5 kept);
 *    compare-and-swap (`st_ino`, `st_mtime_ns` must be unchanged right before
 *    `os.replace`, else re-read, at most 3 rounds; Claude and OMC do not take our lock);
 *  - after the write: re-read; every top-level key except `hooks` and every entry
 *    without the marker must be unchanged (as multisets), else the backup is put
 *    back and the result is `FAILED`;
 *  - the per-config-dir marker file `<config dir>/claude-remote-hookev.installed`
 *    (`<epoch_s>\n`, see [HookCapContract]) is written only AFTER `os.replace`, and
 *    also on `EXISTS` when it is missing.
 *
 * Output (one token per line): `HOOKS_V2_FIXED|EXISTS|NEWER|FAILED`,
 * `ACCOUNTS_MERGED=<n>`, and `HOOKS_V2_DETAIL <path> <result>` for anything that
 * is not plain success.
 */
internal object HookCommands {
    /** Version of the settings.json entries (`claude-remote-ev v<N>`). A higher number wins. */
    const val ENTRY_VERSION = 1

    /** Marker text inside every v2 command; [HookCapContract.HOOKEV_MARKER] is its prefix. */
    const val ENTRY_MARKER = "claude-remote-ev"

    /** Version tag of the `hook-ev.sh` file (also listed in its `marker-compat` line). */
    const val SCRIPT_VERSION = 1

    const val SCRIPT_PATH = "~/.claude-remote/hook-ev.sh"
    const val EVENTS_PATH = "~/.cache/claude-remote/events"
    const val OFF_SWITCH_PATH = "~/.claude-remote/hookev.off"

    /** Events file size above which `hook-ev.sh` stops appending (nobody is reading it). */
    const val EVENTS_MAX_BYTES = 20 * 1024 * 1024

    private fun sh(s: String) = s.replace('§', '$')

    /** (event, matcher, hook-ev.sh argument) in settings.json order. */
    val WANTED: List<Triple<String, String, String>> = listOf(
        Triple("UserPromptSubmit", "", "work"),
        Triple("PreToolUse", "AskUserQuestion", "ask"),
        Triple("PostToolUse", "AskUserQuestion", "askdone"),
        Triple("PermissionRequest", "", "perm"),
        Triple("Stop", "", "stop"),
        Triple("SessionStart", "", "start"),
        Triple("SessionEnd", "", "end"),
    )

    /** The exact command text of the settings.json entry for [ev]. */
    fun entryCommand(ev: String): String = sh(
        ": $ENTRY_MARKER v$ENTRY_VERSION; H=\"§HOME/.claude-remote/hook-ev.sh\"; " +
            "[ -f \"§HOME/.claude-remote/hookev.off\" ] || { [ -x \"§H\" ] && \"§H\" $ev >/dev/null 2>&1; }; exit 0",
    )

    // ------------------------------------------------------------------ hook-ev.sh

    /**
     * The script. Contract:
     *  1. ALWAYS exits 0 and writes NOTHING to stdout/stderr, whatever happens
     *     (`exec >/dev/null 2>&1` first, `trap 'exit 0' EXIT`);
     *  2. Linux only; off switch `~/.claude-remote/hookev.off`;
     *  3. stdin: the first N bytes are read (N depends on the event), the rest is
     *     drained so Claude's writer never sees a broken pipe;
     *  4. attribution: `C` = `§CLAUDE_PID` (fallback: nearest claude ancestor of
     *     `§PPID`); `P` = `pane_pid` of `§TMUX_PANE` (one `timeout 1 tmux -u
     *     display-message`). Emit only when `C == P` (`exec claude`) or `C` is a
     *     descendant of `P` with NO other claude in between and `P` itself is not a
     *     claude (then `C` would be nested). Intermediate shells are fine
     *     (`bash -l -> bash -lc -> claude`, `sh -c "...; exec bash -l"`);
     *     `session_id` on stdin must equal `§CLAUDE_CODE_SESSION_ID` when both are known;
     *  5. no python on the critical path of `work`, `askdone`, `stop`, `start`,
     *     `end`; python is used only to shorten the question (`ask`) or the tool
     *     (`perm`), and only if it exists;
     *  6. the line `claude-remote-ev <EV> <name_b64> <epoch_ms> <C> <extra_b64>`
     *     (under 1 KB) is appended to `~/.cache/claude-remote/events` (mode 600);
     *     above 20 MB nothing is appended;
     *  7. teammates/subagents run in-process (same pid, pane, session_id): their
     *     `ask` / `perm` are emitted for the lead's tab; a `PermissionRequest` for
     *     `AskUserQuestion` is dropped (the `ask` event already covers it).
     *
     * `extra` is UTF-8 text capped at 600 bytes: `ask` = the questions (+ option
     * labels), `perm` = `<tool>: <description|command|path>`, `stop` = `bg` when the
     * Stop carries background tasks, `start` = `source=<s> exe=<claude version>`.
     * Limit: `background_tasks` is looked for in the first 256 KB of a Stop payload.
     */
    val HOOK_EV_SCRIPT: String = sh(
        """
        #!/bin/sh
        # claude-remote-hookev-v$SCRIPT_VERSION
        # marker-compat (keep inside `head -c 300`): claude-remote-hookev-v$SCRIPT_VERSION
        # Background-state event emitter. Always exits 0, never writes to stdout/stderr.
        # usage: hook-ev.sh <work|ask|askdone|perm|stop|start|end>   (hook JSON on stdin)
        exec >/dev/null 2>&1
        set +e
        TF=""
        trap 'rm -f "§TF"; exit 0' EXIT
        [ "§(uname -s)" = Linux ] || exit 0
        umask 077
        EV="§1"
        case "§EV" in work|ask|askdone|perm|stop|start|end) ;; *) exit 0 ;; esac
        case "§EV" in stop) L=262144 ;; ask) L=16384 ;; perm) L=8192 ;; *) L=4096 ;; esac
        D=§(head -c §L)
        cat >/dev/null
        [ -n "§HOME" ] || exit 0
        [ -e "§HOME/.claude-remote/hookev.off" ] && exit 0
        [ -n "§{TMUX_PANE:-}" ] || exit 0

        comm_of() { cat "/proc/§1/comm" 2>/dev/null; }
        ppid_of() { sed 's/^.*) //' "/proc/§1/stat" 2>/dev/null | cut -d' ' -f2; }
        is_claude() {
          c=§(comm_of "§1")
          [ "§c" = claude ] && return 0
          printf '%s' "§c" | grep -Eq '^[0-9]+(\.[0-9]+)+§' || return 1
          readlink "/proc/§1/exe" 2>/dev/null | grep -q '/claude/versions/'
        }

        # session_id on stdin must belong to the claude whose env we run in (nested claude: drop)
        SID=§(printf '%s' "§D" | grep -o '"session_id"[[:space:]]*:[[:space:]]*"[^"]*"' | head -n1 | cut -d'"' -f4)
        if [ -n "§{CLAUDE_CODE_SESSION_ID:-}" ] && [ -n "§SID" ] && [ "§SID" != "§CLAUDE_CODE_SESSION_ID" ]; then exit 0; fi

        # a PermissionRequest for AskUserQuestion is covered by the `ask` event
        if [ "§EV" = perm ] && printf '%s' "§D" | grep -q '"tool_name"[[:space:]]*:[[:space:]]*"AskUserQuestion"'; then exit 0; fi

        C="§{CLAUDE_PID:-}"
        if [ -z "§C" ] || [ ! -d "/proc/§C" ]; then
          C=""; q="§PPID"; n=0
          while [ -n "§q" ] && [ "§q" -gt 1 ] 2>/dev/null && [ §n -lt 64 ]; do
            if is_claude "§q"; then C="§q"; break; fi
            q=§(ppid_of "§q"); n=§((n+1))
          done
        fi
        [ -n "§C" ] || exit 0

        # Every pane of this pane's session, first window first (`-s`): the FIRST line is the main
        # pane, the one the app attaches to. The answer goes to a FILE, not a pipe: a client killed
        # by `timeout` can leave the pipe's other end open (stuck tmux server), and `$(...)` would
        # then wait for it far beyond the one second.
        TF=§(mktemp 2>/dev/null) || exit 0
        timeout -k 1 1 tmux -u list-panes -s -t "§TMUX_PANE" -F '#{pane_id} #{pane_pid} #S' >"§TF" 2>/dev/null
        P=""; N=""; MAINPANE=""
        while IFS=' ' read -r pid_ pp_ nm_; do
          [ -n "§pid_" ] || continue
          [ -n "§MAINPANE" ] || MAINPANE="§pid_"
          if [ "§pid_" = "§TMUX_PANE" ]; then P="§pp_"; N="§nm_"; fi
        done < "§TF"
        rm -f "§TF"; TF=""
        [ -n "§P" ] && [ -n "§N" ] || exit 0
        # a teammate (agent teams in tmux mode) runs claude in a split pane of the SAME session
        [ "§MAINPANE" = "§TMUX_PANE" ] || exit 0

        ok=0
        if [ "§C" = "§P" ]; then
          ok=1
        else
          q=§(ppid_of "§C"); n=0
          while [ -n "§q" ] && [ "§q" -gt 1 ] 2>/dev/null && [ §n -lt 64 ]; do
            if [ "§q" = "§P" ]; then is_claude "§P" || ok=1; break; fi
            is_claude "§q" && break
            q=§(ppid_of "§q"); n=§((n+1))
          done
        fi
        [ "§ok" = 1 ] || exit 0

        EXTRA=""
        case "§EV" in
          ask|perm)
            if command -v python3 >/dev/null 2>&1; then
              EXTRA=§(CRD="§D" python3 -I - "§EV" <<'CRPY'
        import os, sys, json, re
        ev = sys.argv[1]
        raw = os.environ.get("CRD", "")
        out = ""
        try:
            d = json.loads(raw)
            ti = d.get("tool_input") or {}
            if ev == "ask":
                parts = []
                for q in ti.get("questions") or []:
                    t = str(q.get("question", ""))
                    opts = ", ".join(str(o.get("label", "")) for o in (q.get("options") or []) if isinstance(o, dict))
                    parts.append(t + (" [" + opts + "]" if opts else ""))
                out = " | ".join(parts)
            else:
                tool = str(d.get("tool_name", ""))
                detail = ti.get("description") or ti.get("command") or ti.get("file_path") or ti.get("path") or ""
                out = tool + (": " + str(detail) if detail else "")
        except Exception:
            if ev == "ask":
                out = " | ".join(re.findall(r'"question"\s*:\s*"((?:[^"\\]|\\.)*)"', raw))
            else:
                m = re.search(r'"tool_name"\s*:\s*"([^"]*)"', raw)
                out = m.group(1) if m else ""
        sys.stdout.write(out.encode("utf-8")[:600].decode("utf-8", "ignore"))
        CRPY
              )
            fi ;;
          stop)
            if printf '%s' "§D" | grep -q '"background_tasks"[[:space:]]*:[[:space:]]*\[[[:space:]]*[^][:space:]]'; then EXTRA="bg"; fi ;;
          start)
            SRC=§(printf '%s' "§D" | grep -o '"source"[[:space:]]*:[[:space:]]*"[a-z]*"' | head -n1 | cut -d'"' -f4)
            EXE=§(readlink "/proc/§C/exe" 2>/dev/null | sed 's|.*/||')
            EXTRA="source=§SRC exe=§EXE" ;;
        esac

        MS=§(date +%s%3N)
        NB=§(printf '%s' "§N" | base64 -w0)
        EB=""
        [ -n "§EXTRA" ] && EB=§(printf '%s' "§EXTRA" | head -c 600 | base64 -w0)
        F="§HOME/.cache/claude-remote/events"
        mkdir -p "§HOME/.cache/claude-remote"
        if [ -f "§F" ]; then
          SZ=§(stat -c %s "§F" 2>/dev/null)
          [ "§{SZ:-0}" -gt $EVENTS_MAX_BYTES ] && exit 0
        fi
        printf 'claude-remote-ev %s %s %s %s %s\n' "§EV" "§NB" "§MS" "§C" "§EB" >> "§F"
        exit 0
        """.trimIndent(),
    )

    // ------------------------------------------------------------------ settings merge

    /**
     * The python run on the server (`MODE` is prepended: `install`, `remove` or
     * `status`). See the class KDoc for the rules. Standard library only.
     */
    private val HOOKS_V2_PY_BODY: String = sh(
        """
        import json, os, sys, fcntl, tempfile, re, time, glob, copy

        VER = $ENTRY_VERSION
        MARK = '$ENTRY_MARKER'
        HOME = os.path.expanduser('~')
        MAIN_DIR = os.path.join(HOME, '.claude')
        MARKER_FILE = 'claude-remote-hookev.installed'
        WANT = [
            ('UserPromptSubmit', '', 'work'),
            ('PreToolUse', 'AskUserQuestion', 'ask'),
            ('PostToolUse', 'AskUserQuestion', 'askdone'),
            ('PermissionRequest', '', 'perm'),
            ('Stop', '', 'stop'),
            ('SessionStart', '', 'start'),
            ('SessionEnd', '', 'end'),
        ]
        WANT_NAMES = set(w[0] for w in WANT)
        CMD = ': %s v%d; H="§HOME/.claude-remote/hook-ev.sh"; [ -f "§HOME/.claude-remote/hookev.off" ] || { [ -x "§H" ] && "§H" %s >/dev/null 2>&1; }; exit 0'


        def entry(matcher, ev):
            return {'matcher': matcher, 'hooks': [{'type': 'command', 'command': CMD % (MARK, VER, ev), 'timeout': 5}]}


        def cmds(e):
            out = []
            if isinstance(e, dict):
                out.append(str(e.get('command', '')))
                hs = e.get('hooks')
                if isinstance(hs, list):
                    for h in hs:
                        if isinstance(h, dict):
                            out.append(str(h.get('command', '')))
            return out


        def is_ev(e):
            return any(MARK in c for c in cmds(e))


        def is_ev_hook(h):
            return isinstance(h, dict) and MARK in str(h.get('command', ''))


        def strip(e):
            # the entry WITHOUT our hook items: other hooks that share an entry with ours
            # (added by hand or through /hooks) stay. None when nothing foreign is left of
            # an entry that carried ours; entries without ours come back untouched.
            if not isinstance(e, dict):
                return e
            hs = e.get('hooks')
            if isinstance(hs, list):
                rest = [h for h in hs if not is_ev_hook(h)]
                if len(rest) == len(hs):
                    return e
                return dict(e, hooks=rest) if rest else None
            return None if MARK in str(e.get('command', '')) else e


        def strip_list(lst):
            return [x for x in (strip(e) for e in lst) if x is not None]


        def is_canonical(lst, want):
            # exactly one of our items in the list, equal to the wanted one, under the wanted matcher
            found = []
            for e in lst:
                if not isinstance(e, dict):
                    continue
                hs = e.get('hooks')
                if isinstance(hs, list):
                    found += [(e.get('matcher'), h) for h in hs if is_ev_hook(h)]
                elif MARK in str(e.get('command', '')):
                    found.append((None, e))
            return len(found) == 1 and found[0][0] == want['matcher'] and found[0][1] == want['hooks'][0]


        def versions(e):
            out = []
            for c in cmds(e):
                for m in re.finditer(re.escape(MARK) + r' v([0-9]+)', c):
                    out.append(int(m.group(1)))
            return out


        def canon(x):
            return json.dumps(x, sort_keys=True)


        def merge(d, mode):
            # returns FIXED / EXISTS / NEWER / FAILED, mutating d only for FIXED
            hooks = d.get('hooks')
            if hooks is None:
                hooks = {}
            if not isinstance(hooks, dict):
                return 'FAILED'
            for name, lst in hooks.items():
                if not isinstance(lst, list):
                    return 'FAILED'
                for e in lst:
                    if any(v > VER for v in versions(e)):
                        return 'NEWER' if mode == 'install' else mode_remove(d, hooks)
            if mode == 'remove':
                return mode_remove(d, hooks)
            changed = False
            for name, matcher, ev in WANT:
                lst = hooks.get(name)
                if lst is None:
                    lst = []
                want = entry(matcher, ev)
                if is_canonical(lst, want):
                    continue
                others = strip_list(lst)
                hooks[name] = others + [want]
                changed = True
            for name in list(hooks.keys()):
                if name in WANT_NAMES:
                    continue
                lst = hooks[name]
                others = strip_list(lst)
                if others != lst:
                    changed = True
                    if others:
                        hooks[name] = others
                    else:
                        del hooks[name]
            if not changed:
                return 'EXISTS'
            d['hooks'] = hooks
            return 'FIXED'


        def mode_remove(d, hooks):
            changed = False
            for name in list(hooks.keys()):
                lst = hooks[name]
                others = strip_list(lst)
                if others != lst:
                    changed = True
                    if others:
                        hooks[name] = others
                    else:
                        del hooks[name]
            if not changed:
                return 'EXISTS'
            if hooks:
                d['hooks'] = hooks
            elif 'hooks' in d:
                del d['hooks']
            return 'FIXED'


        def same_outside_v2(before, after):
            keys = set(before.keys()) | set(after.keys())
            for k in keys:
                if k == 'hooks':
                    continue
                if canon(before.get(k)) != canon(after.get(k)):
                    return False
            bh = before.get('hooks') or {}
            ah = after.get('hooks') or {}
            if not isinstance(bh, dict) or not isinstance(ah, dict):
                return False
            if not all(isinstance(v, list) for v in list(bh.values()) + list(ah.values())):
                return False
            for name in set(bh.keys()) | set(ah.keys()):
                bo = sorted(canon(e) for e in strip_list(bh.get(name) or []))
                ao = sorted(canon(e) for e in strip_list(ah.get(name) or []))
                if bo != ao:
                    return False
            return True


        def backup(rp, raw):
            bak = rp + '.claude-remote.bak.' + str(int(time.time()))
            fd = os.open(bak, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, 'wb') as f:
                f.write(raw)
            olds = sorted(glob.glob(rp + '.claude-remote.bak.*'))
            for old in olds[:-5]:
                try:
                    os.unlink(old)
                except OSError:
                    pass
            return bak


        def put(rp, data, mode):
            fd, tmp = tempfile.mkstemp(dir=os.path.dirname(rp), prefix='.claude-remote-settings-')
            with os.fdopen(fd, 'wb') as f:
                f.write(data)
            os.chmod(tmp, mode)
            return tmp


        def sig_of(rp):
            try:
                st = os.stat(rp)
                return (st.st_ino, st.st_mtime_ns)
            except OSError:
                return None


        def process(rp, may_create, mode):
            try:
                return process_file(rp, may_create, mode)
            except Exception:
                return 'FAILED'


        def process_file(rp, may_create, mode):
            for attempt in range(3):
                sig = sig_of(rp)
                raw = None
                if sig is None:
                    if not may_create or mode != 'install':
                        return 'SKIP'
                    d = {}
                    fmode = 0o600
                else:
                    try:
                        with open(rp, 'rb') as f:
                            raw = f.read()
                        d = json.loads(raw.decode('utf-8'))
                        fmode = os.stat(rp).st_mode & 0o777
                    except Exception:
                        return 'FAILED'
                    if not isinstance(d, dict):
                        return 'FAILED'
                before = copy.deepcopy(d)
                res = merge(d, mode)
                if res != 'FIXED':
                    return res
                bak = backup(rp, raw) if raw is not None else None
                written = (json.dumps(d, indent=2, ensure_ascii=False) + '\n').encode('utf-8')
                tmp = put(rp, written, fmode)
                if sig_of(rp) != sig:
                    os.unlink(tmp)
                    continue
                os.replace(tmp, rp)
                ok = False
                cur = None
                try:
                    with open(rp, 'rb') as f:
                        cur = f.read()
                    after = json.loads(cur.decode('utf-8'))
                    ok = isinstance(after, dict) and same_outside_v2(before, after)
                except Exception:
                    ok = False
                if not ok:
                    # Put the old content back ONLY when the file is still exactly what we wrote:
                    # anything else is somebody else's newer write (Claude, OMC) that a rollback
                    # would destroy. Then we just report the failure.
                    if cur == written:
                        if raw is not None:
                            back = put(rp, raw, fmode)
                            os.replace(back, rp)
                        else:
                            try:
                                os.unlink(rp)
                            except OSError:
                                pass
                    return 'FAILED'
                return 'FIXED'
            return 'FAILED'


        def write_marker(cdir):
            p = os.path.join(cdir, MARKER_FILE)
            tmp = p + '.tmp'
            with open(tmp, 'w') as f:
                f.write(str(int(time.time())) + '\n')
            os.replace(tmp, p)


        def drop_marker(cdir):
            try:
                os.unlink(os.path.join(cdir, MARKER_FILE))
            except OSError:
                pass


        def main():
            if MODE == 'status':
                p = os.path.realpath(os.path.join(MAIN_DIR, 'settings.json'))
                state = 'ABSENT'
                try:
                    with open(p, 'rb') as f:
                        d = json.loads(f.read().decode('utf-8'))
                    if not isinstance(d, dict):
                        state = 'UNREADABLE'
                    else:
                        hooks = d.get('hooks') or {}
                        evs = [e for lst in (hooks.values() if isinstance(hooks, dict) else []) if isinstance(lst, list) for e in lst if is_ev(e)]
                        if any(v > VER for e in evs for v in versions(e)):
                            state = 'NEWER'
                        elif not evs:
                            state = 'ABSENT'
                        else:
                            probe = copy.deepcopy(d)
                            state = 'INSTALLED' if merge(probe, 'install') == 'EXISTS' else 'PARTIAL'
                except FileNotFoundError:
                    state = 'ABSENT'
                except Exception:
                    state = 'UNREADABLE'
                print('HOOKS_V2_STATE=' + state)
                print('HOOKEV_OFF=' + ('1' if os.path.exists(os.path.join(HOME, '.claude-remote', 'hookev.off')) else '0'))
                return
            os.makedirs(MAIN_DIR, exist_ok=True)
            lock = open(os.path.join(MAIN_DIR, 'settings.json.claude-remote.lock'), 'a+')
            fcntl.flock(lock, fcntl.LOCK_EX)
            try:
                dirs = [MAIN_DIR] + [a for a in sorted(glob.glob(os.path.join(HOME, '.claude-remote', 'accounts', '*'))) if os.path.isdir(a)]
                groups = {}
                for cdir in dirs:
                    rp = os.path.realpath(os.path.join(cdir, 'settings.json'))
                    groups.setdefault(rp, []).append(cdir)
                results = []
                merged = 0
                main_rp = os.path.realpath(os.path.join(MAIN_DIR, 'settings.json'))
                for rp, cdirs in groups.items():
                    res = process(rp, rp == main_rp, MODE)
                    if res == 'SKIP':
                        continue
                    results.append((rp, res))
                    if rp != main_rp and res == 'FIXED':
                        merged += 1
                    for cdir in cdirs:
                        try:
                            if MODE == 'install' and res == 'FIXED':
                                write_marker(cdir)
                            elif MODE == 'install' and res == 'EXISTS' and not os.path.exists(os.path.join(cdir, MARKER_FILE)):
                                write_marker(cdir)
                            elif MODE == 'remove' and res in ('FIXED', 'EXISTS'):
                                drop_marker(cdir)
                        except OSError:
                            pass
                order = ['FAILED', 'NEWER', 'FIXED', 'EXISTS']
                overall = 'EXISTS'
                for want in order:
                    if any(r == want for _, r in results):
                        overall = want
                        break
                print(('HOOKS_V2_' if MODE == 'install' else 'HOOKS_V2_REMOVE_') + overall)
                print('ACCOUNTS_MERGED=%d' % merged)
                for rp, r in results:
                    if r not in ('FIXED', 'EXISTS'):
                        print('HOOKS_V2_DETAIL %s %s' % (rp, r))
            finally:
                fcntl.flock(lock, fcntl.LOCK_UN)
                lock.close()


        main()
        """.trimIndent(),
    )

    private fun pyRun(mode: String): String =
        "if command -v python3 >/dev/null 2>&1; then\n" +
            "python3 -I - <<'CRHV2_PY'\n" +
            "MODE = '$mode'\n" +
            HOOKS_V2_PY_BODY + "\n" +
            "CRHV2_PY\n" +
            "else echo HOOKS_V2_FAILED; echo HOOKS_V2_DETAIL python3 missing; fi"

    /** Same python in [mode] (`install`, `remove`, `status`) for tests. */
    fun hooksV2Py(mode: String): String = "MODE = '$mode'\n$HOOKS_V2_PY_BODY\n"

    /** Size and SHA-256 of `hook-ev.sh` as the installer writes it (the script and the heredoc's final newline). */
    private val SCRIPT_BYTES: ByteArray = (HOOK_EV_SCRIPT + "\n").encodeToByteArray()
    private val SCRIPT_SHA256: String = java.security.MessageDigest.getInstance("SHA-256").digest(SCRIPT_BYTES)
        .joinToString("") { "%02x".format(it) }

    /**
     * Writes `hook-ev.sh` (temp + chmod + mv). A file of a NEWER version is left alone; one of this
     * version is left alone only when it is intact (size and, where `sha256sum` exists, SHA-256 match):
     * a damaged file with a valid header is rewritten instead of being trusted forever.
     */
    private val INSTALL_SCRIPT_SH: String = sh(
        "F=\"§HOME/.claude-remote/hook-ev.sh\"; mkdir -p \"§HOME/.claude-remote\"; " +
            "CUR=§(head -c 300 \"§F\" 2>/dev/null | grep -o 'claude-remote-hookev-v[0-9][0-9]*' | sed 's/.*-v//' | sort -n | tail -n1); " +
            "KEEP=0; if [ -n \"§CUR\" ]; then " +
            "if [ \"§CUR\" -gt $SCRIPT_VERSION ]; then KEEP=1; " +
            "elif [ \"§CUR\" -eq $SCRIPT_VERSION ]; then " +
            "SZ=§(wc -c < \"§F\" 2>/dev/null | tr -d ' '); HS=§(sha256sum < \"§F\" 2>/dev/null | cut -d' ' -f1); " +
            "[ \"§SZ\" = ${SCRIPT_BYTES.size} ] && { [ -z \"§HS\" ] || [ \"§HS\" = $SCRIPT_SHA256 ]; } && KEEP=1; fi; fi; " +
            "if [ \"§KEEP\" = 1 ]; then echo HOOKEV_SCRIPT_PRESENT; else " +
            "T=\"§F.tmp.§§\"; cat > \"§T\" <<'CRHV2_SH'\n" + HOOK_EV_SCRIPT + "\nCRHV2_SH\n" +
            "chmod 755 \"§T\" && mv -f \"§T\" \"§F\" && echo HOOKEV_SCRIPT_INSTALLED; fi",
    )

    /** Install (or refresh) hooks v2: script first, then the settings entries. Only ever run on the user's request. */
    val ENSURE_HOOKS_V2_COMMAND: String = INSTALL_SCRIPT_SH + "\n" + pyRun("install")

    /** Remove every v2 entry (any version) and the per-config-dir markers. The script file stays (harmless without entries). */
    val REMOVE_HOOKS_V2_COMMAND: String = pyRun("remove")

    /** Read-only: `HOOKS_V2_STATE=INSTALLED|PARTIAL|ABSENT|NEWER|UNREADABLE` and `HOOKEV_OFF=0|1`. */
    val STATUS_HOOKS_V2_COMMAND: String = pyRun("status")

    // ------------------------------------------------------------------ result parsing

    enum class V2State { INSTALLED, PARTIAL, ABSENT, NEWER, UNREADABLE, UNKNOWN }

    /** Outcome of the install/remove step of one bootstrap run. */
    enum class V2Result { EXISTS, FIXED, NEWER, FAILED, SKIPPED_OFF, REMOVED, NOT_RUN }

    data class V2Report(val result: V2Result, val state: V2State, val offSwitch: Boolean, val accountsMerged: Int)

    /** Parse the tokens printed by [ENSURE_HOOKS_V2_COMMAND], [REMOVE_HOOKS_V2_COMMAND] or [STATUS_HOOKS_V2_COMMAND]. */
    fun parseV2(output: String, ran: V2Result = V2Result.NOT_RUN): V2Report {
        var result = ran
        var state = V2State.UNKNOWN
        var off = false
        var merged = 0
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            when {
                line == "HOOKS_V2_FIXED" -> result = V2Result.FIXED
                line == "HOOKS_V2_EXISTS" -> result = V2Result.EXISTS
                line == "HOOKS_V2_NEWER" -> result = V2Result.NEWER
                line == "HOOKS_V2_FAILED" -> result = V2Result.FAILED
                line == "HOOKS_V2_SKIPPED_OFF" -> result = V2Result.SKIPPED_OFF
                line == "HOOKS_V2_REMOVE_FIXED" || line == "HOOKS_V2_REMOVE_EXISTS" -> result = V2Result.REMOVED
                line == "HOOKS_V2_REMOVE_FAILED" -> result = V2Result.FAILED
                line.startsWith("HOOKS_V2_STATE=") ->
                    state = runCatching { V2State.valueOf(line.substringAfter('=')) }.getOrDefault(V2State.UNKNOWN)
                line == "HOOKEV_OFF=1" -> off = true
                line.startsWith("ACCOUNTS_MERGED=") -> merged = line.substringAfter('=').toIntOrNull() ?: 0
            }
        }
        if (state == V2State.UNKNOWN) {
            state = when (result) {
                V2Result.FIXED, V2Result.EXISTS -> V2State.INSTALLED
                V2Result.NEWER -> V2State.NEWER
                V2Result.REMOVED -> V2State.ABSENT
                else -> V2State.UNKNOWN
            }
        }
        return V2Report(result, state, off, merged)
    }
}
