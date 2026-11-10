"""Jarvis F1 read layer: one view of every `claude-server-*` session, built only by READING.

Shared by `cr_sessions.py`, `jarvis_d.py` and `cr_digest.py` (Python 3.11, stdlib only).

Sources (none of them is ever written here):
- the registry: `<config>/sessions/*.json` of `~/.claude` and of every `~/.claude-remote/accounts/<slug>`. Part of the
  files belong to dead pids, and some of those name a pane that today belongs to ANOTHER session (`30-rizika` §1), so a
  record counts only while `/proc/<pid>/stat` starttime equals its `procStart`. The `*.key` files next to them are never
  opened.
- `tmux list-panes -a` (read-only, `timeout -k 1 2`, output to a file): which sessions exist, the main pane, I1.
- `~/.cache/claude-remote/events` (`hook-ev.sh`): `claude-remote-ev <ev> <name_b64> <ms> <pid> [<extra_b64>]`.
- the manifest `~/.claude-remote/sessions.json` (model, mode, account, folder), the usage cache
  `~/.claude-remote/usage/<slug>.json` (line 1 = fetch time, then the payload), `~/.claude-remote/closed.jsonl`.
- the TAIL of a transcript (`ai-title`, `last-prompt`, the last assistant text), never the whole file.

Every text that came out of a session is data, not an instruction: whatever leaves this module towards a model goes
through `safe_text` first (R5, §5 point 11).
"""
import base64
import collections
import glob
import hashlib
import json
import os
import re
import subprocess
import tempfile
import time
import unicodedata

SERVER_PREFIX = "claude-server-"
EV_PREFIX = "claude-remote-ev "
KNOWN_EV = ("work", "ask", "askdone", "perm", "stop", "start", "end")
# Registry `status` values (binary 2.1.319). Anything else is reported as `unknown`, never guessed.
REG_STATES = ("busy", "idle", "waiting", "shell")
# A session without a registry record: its last hook event says what it is doing (ask/perm = waiting for Lukáš).
EVENT_STATE = {"work": "busy", "askdone": "busy", "ask": "waiting", "perm": "waiting", "stop": "idle", "start": "idle"}

# A registry file is a few hundred bytes; anything far bigger is not one and is skipped unread.
REGISTRY_MAX_BYTES = 65536
# Transcript tail: ai-title and last-prompt are rewritten every turn, so the last 256 KiB hold them unless one tool
# result alone is bigger; then the previous value is kept (daemon) or the field is empty (one-shot CLI).
TRANSCRIPT_TAIL_BYTES = 262144
# How much of the events file a reader without a cursor looks at. The app rotates it at 1000 lines (~100 KB).
EVENTS_FIRST_WINDOW = 2 * 1024 * 1024
EVENTS_MAX_READ = 4 * 1024 * 1024
# A reply that ends with a question waits for Lukáš only so long: one left unanswered for days is no longer news, and
# the inbox ("co na mě čeká") would otherwise fill with every session parked on a question.
QUESTION_MAX_AGE_MS = 12 * 3600 * 1000
# An open ask/perm whose session state cannot be read (`unknown`) stops counting after the same time: without a
# registry nothing would ever close it, and the inbox would keep it forever.
OPEN_MAX_AGE_MS = 12 * 3600 * 1000
# Fallback self (I8) when `<state>/self` is missing: a session whose alias is this.
SELF_ALIAS = "jarvis"
# Hashes of the last lines read. The app's rotation keeps the last 200 lines under a NEW inode, so after a rotation
# those lines come round again and must not be applied twice.
EVENTS_RING = 2000


# --------------------------------------------------------------------------------------------------------------------
# Environment and seams

def read_proc_start(pid):
    """starttime (field 22 of /proc/<pid>/stat, clock ticks) as the registry's `procStart` string, or None."""
    try:
        with open(f"/proc/{int(pid)}/stat", "rb") as f:
            raw = f.read().decode("ascii", "replace")
    except (OSError, ValueError):
        return None
    # comm may contain spaces and ')' itself: the fields start after the LAST ')'
    rest = raw[raw.rfind(")") + 2:].split()
    return rest[19] if len(rest) > 19 else None


def read_ppid(pid):
    try:
        with open(f"/proc/{int(pid)}/stat", "rb") as f:
            raw = f.read().decode("ascii", "replace")
    except (OSError, ValueError):
        return None
    rest = raw[raw.rfind(")") + 2:].split()
    try:
        return int(rest[1])
    except (IndexError, ValueError):
        return None


def read_proc_table():
    """{pid: (ppid, comm)} of every process (one pass over /proc; used only when a pane has no live registry)."""
    out = {}
    for d in os.listdir("/proc"):
        if not d.isdigit():
            continue
        try:
            with open(f"/proc/{d}/stat", "rb") as f:
                raw = f.read().decode("utf-8", "replace")
        except OSError:
            continue
        lp, rp = raw.find("("), raw.rfind(")")
        rest = raw[rp + 2:].split()
        try:
            out[int(d)] = (int(rest[1]), raw[lp + 1:rp])
        except (IndexError, ValueError):
            continue
    return out


def is_claude_comm(comm):
    # the native build runs as `claude`, or under its version number (`2.1.316`) when started by path (hook-ev.sh)
    return comm == "claude" or bool(re.fullmatch(r"[0-9]+(\.[0-9]+)+", comm or ""))


def own_pid_ns():
    try:
        return os.readlink("/proc/self/ns/pid")
    except OSError:
        return None


def tmux_list_panes(tmux_bin):
    """[(pane_id, pane_pid, session, dead)] in tmux's order (session, window, pane), or None when tmux cannot say.

    Read-only. `timeout -k 1 2` and stdout to a FILE (§5 point 16): a client killed by timeout can leave a pipe's
    other end open and a reader would then hang far beyond the timeout (the same reason as in hook-ev.sh).
    """
    if not tmux_bin:
        return None
    fmt = "#{pane_id}\t#{pane_pid}\t#{session_name}\t#{pane_dead}"
    try:
        with tempfile.TemporaryFile() as out:
            rc = subprocess.run(["timeout", "-k", "1", "2", tmux_bin, "-u", "list-panes", "-a", "-F", fmt],
                                stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.DEVNULL, timeout=5).returncode
            if rc != 0:
                return None
            out.seek(0)
            text = out.read(4 * 1024 * 1024).decode("utf-8", "replace")
    except (OSError, subprocess.SubprocessError):
        return None
    panes = []
    for line in text.splitlines():
        f = line.split("\t")
        if len(f) != 4 or not f[0].startswith("%"):
            continue
        try:
            panes.append((f[0], int(f[1]), f[2], f[3] == "1"))
        except ValueError:
            continue
    return panes


class Env:
    """Paths and seams. Tests pass their own `environ` (temp HOME) and fake liveness/tmux/clock."""

    def __init__(self, environ=None, proc_start=None, ppid=None, tmux_panes=None, now_ms=None, pid_ns=None,
                 proc_table=None, my_pid=None):
        e = os.environ if environ is None else environ
        # kept for the F2 mail provenance (TMUX_PANE, CLAUDECODE): tests pass their own, never the real one
        self.environ = e
        self.home = e.get("HOME") or os.path.expanduser("~")
        self.cr = os.path.join(self.home, ".claude-remote")
        # JARVIS_STATE_DIR: everything this layer WRITES (status.json, cursor, the daemon's state) lives here and
        # nowhere else, so a smoke run against live data can point it at a temp dir.
        self.state_dir = e.get("JARVIS_STATE_DIR") or os.path.join(self.cr, "jarvis")
        self.events_path = e.get("JARVIS_EVENTS") or os.path.join(self.home, ".cache", "claude-remote", "events")
        # F3: what jarvis-d tells the app; next to `events` because the app already tails that directory
        self.alerts_path = e.get("JARVIS_ALERTS") or os.path.join(self.home, ".cache", "claude-remote", "jarvis-alerts")
        dirs = e.get("JARVIS_CONFIG_DIRS")
        if dirs:
            self.config_dirs = [d for d in dirs.split(":") if d]
        else:
            # the default login + one per account; claude-local's own dir too when it exists (its sessions run
            # with CLAUDE_CONFIG_DIR there, so their registry is there and nowhere else)
            self.config_dirs = [os.path.join(self.home, ".claude")] + sorted(
                d.rstrip("/") for d in glob.glob(os.path.join(self.cr, "accounts", "*", "")))
            local = os.path.join(self.home, "claude-local", "cc-config")
            if os.path.isdir(os.path.join(local, "sessions")):
                self.config_dirs.append(local)
        self.tmux_bin = e.get("JARVIS_TMUX", "tmux")
        self.proc_start = proc_start or read_proc_start
        self.ppid = ppid or read_ppid
        self.proc_table = proc_table or read_proc_table
        self.tmux_panes = tmux_panes or (lambda: tmux_list_panes(self.tmux_bin))
        self.now_ms = now_ms or (lambda: int(time.time() * 1000))
        self.pid_ns = own_pid_ns() if pid_ns is None else pid_ns
        self.my_pid = os.getpid() if my_pid is None else my_pid

    def path(self, *parts):
        return os.path.join(self.state_dir, *parts)


def atomic_write(path, data):
    """tmp + rename in the same directory, mode 600: a reader sees the old file or the new one, never half."""
    d = os.path.dirname(path)
    os.makedirs(d, mode=0o700, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".tmp.", dir=d)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(data)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def read_json(path, default=None, max_bytes=8 * 1024 * 1024):
    try:
        with open(path, "rb") as f:
            raw = f.read(max_bytes + 1)
        if len(raw) > max_bytes:
            return default
        return json.loads(raw.decode("utf-8"))
    except (OSError, ValueError):
        return default


def read_tail(path, max_bytes):
    """Last `max_bytes` of a file as complete lines (a cut first line is dropped)."""
    try:
        with open(path, "rb") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            start = max(0, size - max_bytes)
            f.seek(start)
            raw = f.read(max_bytes)
    except OSError:
        return []
    if start > 0:
        nl = raw.find(b"\n")
        raw = raw[nl + 1:] if nl >= 0 else b""
    return raw.decode("utf-8", "replace").splitlines()


# --------------------------------------------------------------------------------------------------------------------
# Untrusted text

_OMC_WORDS = re.compile(r"\b(ralph|autopilot|ulw|ultrawork|cancelomc|ralplan|ultrathink)\b", re.IGNORECASE)
# Sentences that address a model or ask for an action. A title or a question that looks like one is not shown at all:
# a summary that reaches Jarvis's hook context is trusted more than tool output (`30-rizika` R5).
_INJECTION = [re.compile(p, re.IGNORECASE) for p in (
    r"\bignor\w*\b.{0,30}\b(previous|prior|above|earlier|all|instructions?|p[řr]edchoz\w*|pokyn\w*|v[šs]e)\b",
    r"\bdisregard\b",
    r"\b(system|developer)\s*(prompt|message|instructions?)\b",
    r"^\s*(system|assistant|user|human)\s*:",
    r"\byou are now\b|\bnew instructions?\b|\bnov[ée] pokyny\b",
    r"\bjarvis\w*\s*[,:;!]",
    # paraphrases rev-f1-proj found: greeting or dative/vocative address, "Jarvis tell/say…", "forget the previous…",
    # a message for Lukáš. Still a blacklist; the real defence is that text never changes the items (CLAUDE.md).
    r"\b(hey|hi|hello|ok|okay|hej|ahoj|čau|nazdar)\s*,?\s+jarvis\b",
    r"\bjarvis(i|ovi)\b",
    r"\bjarvis\w*\s+(tell|say|please|ask|remind|řekni|vyřiď|pověz|oznam|připomeň|vzkaz\w*)\b",
    r"\b(zapome[ňn]\w*|forget)\b.{0,30}\b(p[řr]edchoz\w*|zadání|pokyn\w*|instrukc\w*|v[šs]e|v[šs]echno|previous|prior|"
    r"above|earlier|instructions?|everything)\b",
    r"\b(tell|inform)\s+lukas\w*\b|\b(řekni|vyřiď|pověz|oznam|napiš)\s+luk[aá][šs]\w*\b",
    r"\b(cr-mail|cr-session|cr-task|send-keys|paste-buffer|kill-session)\b",
    r"\b(po[šs]li|odešli|send|spus[tť]|run|execute|vykonej|zavři|close)\b.{0,40}\b(session\w*|relac\w*|p[řr]íkaz\w*|command|tmux)\b",
    r"<\s*/?\s*(system|assistant|user|human|instructions?|system-reminder|tool)\b",
)]
HIDDEN = "(skryto: text vypadá jako pokyn)"
_REPL = str.maketrans({"<": "‹", ">": "›", "`": "'", "\\": "/"})
_BRACKET = {"Ps": "(", "Pe": ")", "Pi": '"', "Pf": '"'}


def safe_text(s, max_len=120):
    """One escaped line of untrusted text, at most `max_len` characters, safe to show a model as DATA.

    - control, format (bidi overrides, zero-width), private-use, surrogate and line/paragraph separators -> space;
    - `<>[]«»` and backticks are replaced, so the text can neither open a tag, nor fake a `[jarvis …]` prefix, nor
      close the «» quotes the digest puts round it;
    - OMC keywords get a middle dot, so a quote never starts ralph/autopilot in whoever reads it later (R6);
    - a text that looks like an instruction is replaced as a whole by HIDDEN.
    """
    if not isinstance(s, str) or not s:
        return ""
    # NFKC, not NFC: fullwidth and other compatibility forms (`［jarvis］`, `＜system＞`, `ｉｇｎｏｒｅ`) fold to ASCII
    # before the patterns look at them
    s = unicodedata.normalize("NFKC", s)
    s = "".join(" " if unicodedata.category(c) in ("Cc", "Cf", "Cs", "Co", "Zl", "Zp") else c for c in s)
    s = " ".join(s.split())
    if any(p.search(s) for p in _INJECTION):
        return HIDDEN
    # every opening/closing bracket and quotation mark of any script (〈》「」‹›«» …) becomes ( ) or ", so none of
    # them can pose as our «» or as a `[jarvis …]` prefix; then the ASCII ones that are not brackets
    s = "".join(_BRACKET.get(unicodedata.category(c), c) if c not in "<>" else c for c in s)
    s = s.translate(_REPL)
    s = _OMC_WORDS.sub(lambda m: m.group(0)[:2] + "·" + m.group(0)[2:], s)
    if len(s) > max_len:
        s = s[:max(1, max_len - 1)].rstrip() + "…"
    return s


# --------------------------------------------------------------------------------------------------------------------
# Names

def parse_name(name):
    """(folder, alias, yolo) of a `claude-server-<folder>[-yolo][--<alias>]` name (TmuxNameParser.parse)."""
    rest = name[len(SERVER_PREFIX):] if name.startswith(SERVER_PREFIX) else name
    alias = ""
    if "--" in rest:
        rest, alias = rest.split("--", 1)
    m = re.search(r"-yolo\d*$", rest)
    yolo = bool(m)
    if m:
        rest = rest[:m.start()]
    return (rest or "~"), alias, yolo


def safe_name(name, max_len=40):
    """A session name as it may be shown: tmux names are typed by people, so anything but letters, digits and
    `.~-` becomes `_` (no spaces, quotes, brackets or control characters reach a model or TTS)."""
    s = re.sub(r"[^\w.~-]", "_", unicodedata.normalize("NFKC", name or ""))
    return s[:max_len] or "_"


def short_name(name):
    folder, alias, _ = parse_name(name)
    return safe_name(alias or folder)


def short_names(names):
    """name -> short form; a short form two sessions share falls back to the name without `claude-server-`."""
    first = {n: short_name(n) for n in names}
    count = collections.Counter(first.values())
    return {n: (s if count[s] == 1 else safe_name(n[len(SERVER_PREFIX):] if n.startswith(SERVER_PREFIX) else n))
            for n, s in first.items()}


def spoken_name(short):
    s = re.sub(r"[-_.~/]+", " ", short)
    s = re.sub(r"[^\w ]+", "", s)
    s = " ".join(s.split())
    return s or "session"


def account_short(slug):
    if not slug:
        return "výchozí"
    if "@" in slug:
        return slug.split("@", 1)[1].rsplit(".", 1)[0]
    # a slug made from an address (`lukas-kontexta-cz`): drop the TLD part
    return re.sub(r"-[a-z]{2,3}$", "", slug)


# --------------------------------------------------------------------------------------------------------------------
# Registry

class Reg:
    __slots__ = ("file", "cfg", "account", "pid", "proc_start", "session_id", "cwd", "status", "waiting_for",
                 "status_at", "started_at", "tmux", "pane", "alive", "why", "version", "pid_domain", "kind")


def read_registry(env):
    """Every registry record of every config dir, each with `alive` decided by pid + procStart (+ pid namespace)."""
    regs = []
    for cfg in env.config_dirs:
        parent = os.path.basename(os.path.dirname(cfg.rstrip("/")))
        account = os.path.basename(cfg.rstrip("/")) if parent == "accounts" else None
        for f in sorted(glob.glob(os.path.join(cfg, "sessions", "*.json"))):
            try:
                if os.path.getsize(f) > REGISTRY_MAX_BYTES:
                    continue
            except OSError:
                continue
            d = read_json(f)
            if not isinstance(d, dict):
                continue
            r = Reg()
            r.file, r.cfg, r.account = f, cfg, account
            try:
                r.pid = int(d.get("pid"))
            except (TypeError, ValueError):
                continue
            r.proc_start = str(d.get("procStart") or "")
            r.session_id = d.get("sessionId") if isinstance(d.get("sessionId"), str) else None
            r.cwd = d.get("cwd") if isinstance(d.get("cwd"), str) else None
            r.status = d.get("status") if d.get("status") in REG_STATES else ("unknown" if d.get("status") else None)
            r.waiting_for = d.get("waitingFor") if isinstance(d.get("waitingFor"), str) else None
            r.status_at = _int(d.get("statusUpdatedAt")) or _int(d.get("updatedAt"))
            r.started_at = _int(d.get("startedAt"))
            r.version = d.get("version") if isinstance(d.get("version"), str) else None
            r.pid_domain = d.get("pidDomain") if isinstance(d.get("pidDomain"), str) else None
            t = d.get("tmux") if isinstance(d.get("tmux"), str) else ""
            # "<session>:@<window>.%<pane>"; the app never mints a session name with ':' (TmuxNameParser.sanitize)
            if ":" in t:
                sess, loc = t.rsplit(":", 1)
                r.tmux = sess
                r.pane = loc[loc.find("%"):] if "%" in loc else None
            else:
                r.tmux, r.pane = None, None
            r.kind = d.get("kind") if isinstance(d.get("kind"), str) else None
            r.alive, r.why = _alive(env, r)
            regs.append(r)
    return regs


def _alive(env, r):
    # only an interactive claude is a session; anything else that writes a registry record (other entrypoints) is not
    if r.kind not in (None, "interactive"):
        return False, "not-interactive"
    if r.pid_domain and env.pid_ns and "pid:[" in r.pid_domain and not r.pid_domain.endswith(env.pid_ns):
        return False, "foreign-pid-namespace"
    if not r.proc_start:
        return False, "no-procStart"
    ps = env.proc_start(r.pid)
    if ps is None:
        return False, "pid-dead"
    if ps != r.proc_start:
        return False, "pid-reused"
    return True, "ok"


def _int(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def _boot_ms():
    try:
        with open("/proc/stat", encoding="ascii", errors="replace") as f:
            for line in f:
                if line.startswith("btime "):
                    return int(line.split()[1]) * 1000
    except (OSError, ValueError):
        pass
    return None


def _proc_start_ms(env, pid):
    """When the process started (epoch ms), from its starttime ticks and the boot time; None when unknown."""
    try:
        ticks = int(env.proc_start(pid)) if pid else None
        boot = _boot_ms()
        if ticks is None or boot is None:
            return None
        return boot + ticks * 1000 // os.sysconf("SC_CLK_TCK")
    except (TypeError, ValueError, OSError, AttributeError):
        return None


def _pane_of(env, pid, pane_info, limit=64):
    """The live pane (its id, e.g. %61) whose process is an ancestor of pid (claude runs under the pane's shell), or
    None. pane_info is pane id -> (pane process pid, session, dead)."""
    by_pid = {info[0]: pane for pane, info in pane_info.items()}
    q = env.ppid(pid)
    for _ in range(limit):
        if q is None or q <= 1:
            return None
        pane = by_pid.get(q)
        if pane is not None:
            return None if pane_info[pane][2] else pane
        q = env.ppid(q)
    return None


def is_descendant(env, pid, ancestor, limit=64):
    q = pid
    for _ in range(limit):
        if q == ancestor:
            return True
        if q is None or q <= 1:
            return False
        q = env.ppid(q)
    return False


# --------------------------------------------------------------------------------------------------------------------
# Events

class Event(collections.namedtuple("Event", "ev name ms pid extra")):
    pass


def parse_event(line):
    """One `hook-ev.sh` line, or None for anything else (HookEventLine.parse)."""
    if not line.startswith(EV_PREFIX):
        return None
    f = line.split()
    if len(f) < 5 or f[1] not in KNOWN_EV:
        return None
    try:
        name = base64.b64decode(f[2], validate=True).decode("utf-8")
        ms = int(f[3])
        extra = base64.b64decode(f[5], validate=True).decode("utf-8", "replace") if len(f) >= 6 else ""
    except (ValueError, UnicodeDecodeError):
        return None
    if not name:
        return None
    return Event(f[1], name, ms, _int(f[4]), extra)


class EventsReader:
    """Incremental reader of the events file: inode + offset, rotation detection, a partial last line is left alone.

    State (JSON-able): {"ino", "off", "ring"}. A changed inode or a file shorter than the offset is a rotation: the
    new file is read from the start and lines already seen (ring of hashes) are skipped, because the app's rotation
    copies the last 200 lines into the new file. A line without its newline is still being written: it is not
    consumed and the offset stays in front of it.
    """

    def __init__(self, state=None):
        st = state or {}
        self.ino = st.get("ino")
        self.off = int(st.get("off") or 0)
        self.ring = collections.deque(st.get("ring") or [], maxlen=EVENTS_RING)
        self.rotations = 0

    def state(self):
        return {"ino": self.ino, "off": self.off, "ring": list(self.ring)}

    def read(self, path):
        try:
            f = open(path, "rb")
        except OSError:
            return []
        with f:
            st = os.fstat(f.fileno())
            first = self.ino is None
            rotated = not first and (st.st_ino != self.ino or st.st_size < self.off)
            prev_nl = True
            if first:
                start = max(0, st.st_size - EVENTS_FIRST_WINDOW)
                if start > 0:
                    f.seek(start - 1)
                    prev_nl = f.read(1) == b"\n"
            elif rotated:
                self.rotations += 1
                start = 0
            else:
                start = self.off
            f.seek(start)
            raw = f.read(EVENTS_MAX_READ)
        if first and start > 0 and not prev_nl:
            # the window starts inside a line: its tail is not an event (it may even look like one)
            nl = raw.find(b"\n")
            skip = nl + 1 if nl >= 0 else len(raw)
            raw, start = raw[skip:], start + skip
        end = raw.rfind(b"\n")
        if end < 0:
            # no complete line: a huge garbage line must not stall the reader forever
            consumed = len(raw) if len(raw) >= EVENTS_MAX_READ else 0
            body = b""
        else:
            consumed = end + 1
            body = raw[:consumed]
        seen = set(self.ring) if (rotated or first) else None
        out = []
        for bline in body.split(b"\n"):
            if not bline:
                continue
            h = hashlib.sha1(bline).hexdigest()[:16]
            if seen is not None and h in seen:
                continue
            self.ring.append(h)
            ev = parse_event(bline.decode("utf-8", "replace"))
            if ev:
                out.append(ev)
        self.ino = st.st_ino
        self.off = start + consumed
        return out


class Fold:
    """Per session: the last event and the open ask/perm (closed by askdone/work/stop/start/end). JSON-able."""

    def __init__(self, data=None):
        self.s = data if isinstance(data, dict) else {}

    def apply(self, e, ignore=None):
        if ignore and e.name == ignore:
            return False
        st = self.s.setdefault(e.name, {})
        last = st.get("last")
        # lines of concurrent writers can land a few ms out of order; an older one never overrides a newer one
        if last and e.ms < last["ms"]:
            return False
        st["last"] = {"ev": e.ev, "ms": e.ms, "pid": e.pid, "extra": e.extra[:600]}
        if e.ev in ("ask", "perm"):
            st["open"] = {"ev": e.ev, "ms": e.ms, "extra": e.extra[:600]}
        else:
            st["open"] = None
        if e.ev == "stop":
            st["stop"] = {"ms": e.ms, "bg": e.extra == "bg"}
        return True

    def prune(self, now_ms, keep_ms=7 * 86400 * 1000):
        for k in [k for k, v in self.s.items() if (v.get("last") or {}).get("ms", 0) < now_ms - keep_ms]:
            del self.s[k]


def fold_events_file(env, ignore=None):
    """One-shot fold of the events tail (CLI without a running daemon)."""
    fold = Fold()
    for e in EventsReader().read(env.events_path):
        fold.apply(e, ignore)
    return fold


# --------------------------------------------------------------------------------------------------------------------
# Transcript tail

def transcript_path(env, cfg, cwd, sid):
    if not sid or not re.fullmatch(r"[0-9a-fA-F-]{8,64}", sid):
        return None
    bases = [os.path.join(env.home, ".claude", "projects")]
    if cfg:
        bases.append(os.path.join(cfg, "projects"))
    if cwd:
        slug = re.sub(r"[^A-Za-z0-9]", "-", cwd)
        for b in bases:
            p = os.path.join(b, slug, sid + ".jsonl")
            if os.path.isfile(p):
                return p
    for b in bases:
        hits = glob.glob(os.path.join(glob.escape(b), "*", sid + ".jsonl"))
        if hits:
            return hits[0]
    return None


def _is_prompt(d):
    """A user entry the human (or a paste) typed, not a tool result or a meta line."""
    if d.get("isMeta") or d.get("isSidechain"):
        return False
    c = (d.get("message") or {}).get("content")
    if isinstance(c, str):
        return bool(c.strip())
    if isinstance(c, list):
        kinds = {b.get("type") for b in c if isinstance(b, dict)}
        return "text" in kinds and "tool_result" not in kinds
    return False


def _assistant_text(d):
    if d.get("isSidechain"):
        return ""
    c = (d.get("message") or {}).get("content")
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        return "\n".join(b.get("text", "") for b in c if isinstance(b, dict) and b.get("type") == "text"
                         and isinstance(b.get("text"), str)).strip()
    return ""


def _ts_ms(s):
    if not isinstance(s, str):
        return None
    try:
        import datetime
        return int(datetime.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() * 1000)
    except ValueError:
        return None


# Claude 2.1.32x continues by itself after a limit: `system` / `informational` "Usage limit reached · continuing
# automatically at 10:30pm · esc to cancel" next to the limit hit, then "Usage limit reset · Continuing automatically"
# and a user "Your claude.ai usage limit has reset. Continue the task…" (live 2026-11-10, davka-E and B1)
AUTO_CONTINUE_RE = re.compile(r"continuing automatically at\s+(\d{1,2})(?::(\d{2}))?\s*([ap]m)", re.IGNORECASE)


def auto_continue_hm(text):
    """'22:30' from "… continuing automatically at 10:30pm …", else None (the box's local time, as Claude prints it)."""
    m = AUTO_CONTINUE_RE.search(text or "")
    if not m:
        return None
    h = int(m.group(1)) % 12 + (12 if m.group(3).lower() == "pm" else 0)
    return f"{h}:{int(m.group(2) or 0):02d}"


AUTO_RESET_RE = re.compile(r"usage limit reset\s*·\s*continuing automatically", re.IGNORECASE)
# the promised time + this, and still no sign that it went on: the auto-continue did not happen (esc, a crash, an
# unknown format), so `cr-mail resume` is allowed again
AUTO_CONTINUE_GRACE_MS = 5 * 60 * 1000
# rev-dbg LOW: a promise whose time we could not compute stands at most this long after the limit hit
AUTO_CONTINUE_UNKNOWN_MS = 6 * 3600 * 1000
# a line of Jarvis's mail delivered into the session (the auto "pokračuj" among them): a limit right after it is a NEW
# episode, the relapse that must be told (rev-dbg2 MED); other prompts (teammates) never start one
JARVIS_MAIL_RE = re.compile(r"\[jarvis m-\d{8}T\d{6}-[0-9a-f]{4}\]")


def _prompt_plain(d):
    c = (d.get("message") or {}).get("content")
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        return "".join(b.get("text", "") for b in c if isinstance(b, dict) and b.get("type") == "text")
    return ""


def auto_continue_ms(hm, notice_ms):
    """ms of the first `hm` (box local time, as Claude prints it) after the notice, or None."""
    import datetime
    if not hm or not notice_ms:
        return None
    try:
        h, m = (int(x) for x in hm.split(":"))
        base = datetime.datetime.fromtimestamp(notice_ms / 1000)
        t = base.replace(hour=h, minute=m, second=0, microsecond=0)
    except ValueError:
        return None
    if t.timestamp() * 1000 < notice_ms - 60000:
        t += datetime.timedelta(days=1)
    return int(t.timestamp() * 1000)


def auto_continue_live(lim, now_ms):
    """Does the limit episode's auto-continue still stand? Until the promised time + 5 min; after that the session is
    still on THIS limit (an auto-continue that happened ends the episode in transcript_info), so it did not happen:
    esc, a crash, an unknown format. A time we cannot compute stands at most 6 h after the limit hit (rev-dbg)."""
    if not isinstance(lim, dict) or not lim.get("autoContinue"):
        return False
    at = _int(lim.get("autoContinueMs"))
    if not at:
        return now_ms <= _int(lim.get("ms")) + AUTO_CONTINUE_UNKNOWN_MS
    return now_ms <= at + AUTO_CONTINUE_GRACE_MS


def transcript_info(path, max_bytes=TRANSCRIPT_TAIL_BYTES, keep_texts=0):
    """{title, lastPrompt, lastAssistantMs, endsWithQuestion, texts?} from the transcript's tail. Raw values: the
    caller escapes them before they go anywhere."""
    info = {"title": None, "lastPrompt": None, "lastPromptMs": None, "lastAssistantMs": None,
            "endsWithQuestion": False, "limitHit": None}
    texts = collections.deque(maxlen=keep_texts or 1)
    custom = None
    last_text = None
    auto_cont = None
    mail_after_hit = False
    for line in read_tail(path, max_bytes):
        if not line.startswith("{"):
            continue
        try:
            d = json.loads(line)
        except ValueError:
            continue
        if not isinstance(d, dict):
            continue
        t = d.get("type")
        if t == "ai-title" and isinstance(d.get("aiTitle"), str):
            info["title"] = d["aiTitle"]
        elif t == "custom-title" and isinstance(d.get("customTitle"), str):
            custom = d["customTitle"]
        elif t == "last-prompt" and isinstance(d.get("lastPrompt"), str):
            info["lastPrompt"] = d["lastPrompt"]
        elif t == "user" and _is_prompt(d):
            info["lastPromptMs"] = _ts_ms(d.get("timestamp"))
            last_text = None
            if info["limitHit"] and JARVIS_MAIL_RE.search(_prompt_plain(d)):
                mail_after_hit = True
            if auto_cont and auto_cont.get("autoContinueMs") and \
                    (info["lastPromptMs"] or 0) >= auto_cont["autoContinueMs"]:
                # "Your claude.ai usage limit has reset. Continue the task…": the auto-continue happened, the session
                # works again; that limit and its promise are over (rev-dbg MED: neither may cover the next limit)
                info["limitHit"], auto_cont = None, None
        elif t == "assistant" and d.get("isApiErrorMessage") and d.get("error") == "rate_limit":
            # "You've hit your weekly limit · resets 4pm (UTC)": the session holds Lukáš's input until the reset
            # (2026-11-09: nobody knew, neither Lukáš nor Jarvis). One episode = from the FIRST hit until a real
            # answer (rev-dbg): messages delivered meanwhile hit it again and must not start a new one.
            hit_ms, hit_text = _ts_ms(d.get("timestamp")), _assistant_text(d) or ""
            if mail_after_hit:
                info["limitHit"], auto_cont, mail_after_hit = None, None, False  # the relapse after our mail
            if not info["limitHit"]:
                info["limitHit"] = {"ms": hit_ms, "text": hit_text}
                if auto_cont:
                    info["limitHit"].update(auto_cont)
            # the episode keeps its first hit; the text (and with it the reset) and lastHitMs follow the last one
            info["limitHit"].update(text=hit_text, lastHitMs=hit_ms)
        elif t == "system" and isinstance(d.get("content"), str) and auto_continue_hm(d["content"]):
            hm = auto_continue_hm(d["content"])
            auto_cont = {"autoContinue": hm, "autoContinueMs": auto_continue_ms(hm, _ts_ms(d.get("timestamp")))}
            if info["limitHit"]:
                info["limitHit"].update(auto_cont)
        elif t == "system" and isinstance(d.get("content"), str) and AUTO_RESET_RE.search(d["content"]):
            info["limitHit"], auto_cont = None, None  # "Usage limit reset · Continuing automatically": it works
            mail_after_hit = False
        elif t == "assistant":
            txt = _assistant_text(d)
            if txt:
                info["limitHit"] = None  # a real answer after it: the session works again
                auto_cont = None
                mail_after_hit = False
                last_text = txt
                info["lastAssistantMs"] = _ts_ms(d.get("timestamp"))
                if keep_texts:
                    texts.append({"ms": info["lastAssistantMs"], "text": txt})
    if custom:
        info["title"] = custom
    if last_text:
        tail = last_text.rstrip().rstrip("*_`)\"' ").rstrip()
        info["endsWithQuestion"] = tail.endswith("?")
    if keep_texts:
        info["texts"] = list(texts)
    return info


# --------------------------------------------------------------------------------------------------------------------
# Manifest, usage, closed

def read_manifest(env):
    d = read_json(os.path.join(env.cr, "sessions.json"), [])
    out = {}
    if isinstance(d, list):
        for x in d:
            if isinstance(x, dict) and isinstance(x.get("tmuxSessionName"), str):
                # only string fields survive: one odd entry must not break every tick (the daemon would go blind)
                out[x["tmuxSessionName"]] = {k: v for k, v in x.items() if isinstance(v, str)}
    return out


def read_usage(env, now_ms):
    """{account: {fiveHour, fiveHourResets, sevenDay, sevenDayResets, ageS}} from the app's cache. Read only; the
    credentials are never touched (the cache exists precisely so nothing else needs them)."""
    out = {}
    for f in sorted(glob.glob(os.path.join(env.cr, "usage", "*.json"))):
        try:
            with open(f, "rb") as fh:
                raw = fh.read(1024 * 1024).decode("utf-8", "replace")
        except OSError:
            continue
        first, _, rest = raw.partition("\n")
        fetched = _int(first.strip())
        body = rest.rsplit("\nHTTP:", 1)[0]
        try:
            d = json.loads(body)
        except ValueError:
            continue
        if not isinstance(d, dict):
            continue
        acct = os.path.basename(f)[:-5]
        acct = None if acct in (".claude", "default") else acct
        fh_, sd = d.get("five_hour"), d.get("seven_day")
        fh_, sd = (fh_ if isinstance(fh_, dict) else {}), (sd if isinstance(sd, dict) else {})
        scoped, scoped_reset = _scoped(d)
        out[acct or "default"] = {
            "account": acct,
            "fiveHour": _num(fh_.get("utilization")), "fiveHourResets": _str(fh_.get("resets_at")),
            "sevenDay": _num(sd.get("utilization")), "sevenDayResets": _str(sd.get("resets_at")),
            "scoped": scoped, "scopedResets": scoped_reset,
            "ageS": (now_ms // 1000 - fetched) if fetched else None,
        }
    return out


def _scoped(d):
    """rev-f5c N1: the highest MODEL-scoped weekly window (seven_day_opus / seven_day_sonnet, and the `limits` list's
    weekly_scoped rows), with its reset: a session on Opus can stand on its model's week while the account's own
    5 h and 7 d windows are far from 100 %. (None, None) when the account has none."""
    best, reset = None, None
    for k in ("seven_day_opus", "seven_day_sonnet"):
        w = d.get(k)
        v = _num(w.get("utilization")) if isinstance(w, dict) else None
        if v is not None and (best is None or v > best):
            best, reset = v, _str(w.get("resets_at"))
    for w in d.get("limits") if isinstance(d.get("limits"), list) else ():
        if isinstance(w, dict) and w.get("kind") == "weekly_scoped":
            v = _num(w.get("percent"))
            if v is not None and (best is None or v > best):
                best, reset = v, _str(w.get("resets_at"))
    return best, reset


def _num(v):
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def _str(v):
    return v if isinstance(v, str) else None


def read_closed(env, limit=20):
    out = []
    for line in read_tail(os.path.join(env.cr, "closed.jsonl"), 256 * 1024)[-limit:]:
        try:
            d = json.loads(line)
        except ValueError:
            continue
        if isinstance(d, dict) and isinstance(d.get("tmuxName"), str):
            out.append({"name": d["tmuxName"], "at": _str(d.get("at")), "atMs": _ts_ms(d.get("at")),
                        "by": _str(d.get("by")), "account": _str(d.get("account")), "title": _str(d.get("title")),
                        "durable": d.get("durable") is True})
    return out


def read_self(env):
    try:
        with open(env.path("self"), encoding="utf-8") as f:
            return f.read(512).strip() or None
    except OSError:
        return None


def resolve_self(env, regs, panes=None, ancestor=True):
    """Jarvis's own tmux name (I8), in this order:
    1. `<state>/self` (written by Jarvis's SessionStart hook);
    2. the session whose live claude is an ancestor of this process (a hook or a Bash call inside Jarvis);
    3. the one session whose alias is `jarvis` (cron's jarvis-d has no claude above it).
    None when nothing matches; then nothing is filtered, which is what F1 did before.
    `ancestor=False` for jarvis-d and plain views: started by hand from a work session, the daemon would otherwise
    take THAT session for Jarvis and drop its events from the persistent fold.
    """
    name = read_self(env)
    if name:
        return name
    by_pid = {r.pid: r for r in regs if r.alive and r.tmux}
    q = env.my_pid if ancestor else None
    for _ in range(64):
        if q is None or q <= 1:
            break
        r = by_pid.get(q)
        if r:
            return _session_of(r, panes) or r.tmux
        q = env.ppid(q)
    names = {r.tmux for r in regs if r.alive and r.tmux} | {p[2] for p in (panes or ())}
    hits = sorted(n for n in names if n.startswith(SERVER_PREFIX) and parse_name(n)[1] == SELF_ALIAS)
    return hits[0] if len(hits) == 1 else None


def _session_of(r, panes):
    for pane_id, _pp, sess, _dead in panes or ():
        if pane_id == r.pane:
            return sess
    return None


# --------------------------------------------------------------------------------------------------------------------
# The snapshot

def build_sessions(env, regs, panes, manifest, fold, self_name=None, tr_cache=None, now_ms=None):
    """name -> session dict. Pure function of its inputs (+ the transcript tail, cached by size/mtime in tr_cache).

    state: busy|idle|waiting|shell (from the live registry record), dead (tmux session without a live claude),
    missing (in the manifest, no tmux session), unknown (no tmux answer and no live record, or an unknown registry
    status).
    """
    now_ms = env.now_ms() if now_ms is None else now_ms
    tr_cache = {} if tr_cache is None else tr_cache
    pane_info = {}
    main_pane = {}
    tmux_sessions = set()
    if panes is not None:
        for pid_, ppid_, sess, dead in panes:
            pane_info[pid_] = (ppid_, sess, dead)
            main_pane.setdefault(sess, pid_)
            tmux_sessions.add(sess)

    live = collections.defaultdict(list)
    stale = collections.Counter()
    on_pane = set()
    for r in regs:
        if r.alive:
            # Keyed by the session that owns the record's pane TODAY, when the claude really runs in that pane: the
            # name in the record is the one at launch, and a session renamed in the app since would otherwise show
            # its old name as alive and the new one as having no claude.
            info = pane_info.get(r.pane) if r.pane else None
            if info and not info[2] and is_descendant(env, r.pid, info[0]):
                live[info[1]].append(r)
                on_pane.add(id(r))
            elif r.tmux:
                live[r.tmux].append(r)
            else:
                # Claude 2.1.319 after a --resume restart can write the record without `tmux` (2026-11-09: 21 of 59
                # sessions, Jarvis included, showed as "?"): find its pane by ancestry instead.
                pane = _pane_of(env, r.pid, pane_info)
                if pane is not None:
                    r.pane = pane
                    live[pane_info[pane][1]].append(r)
                    on_pane.add(id(r))
        elif r.tmux:
            stale[r.tmux] += 1

    names = {n for n in tmux_sessions if n.startswith(SERVER_PREFIX)}
    names |= {n for n in live if n.startswith(SERVER_PREFIX)}
    if panes is not None:
        names |= {n for n in manifest if n.startswith(SERVER_PREFIX)}
    shorts = short_names(names)

    out = {}
    ptab = None
    for name in sorted(names):
        try:
            man = manifest.get(name) or {}
            cands = live.get(name, [])
            primary = None
            if cands:
                mp = main_pane.get(name)
                # a teammate (agent teams) runs its own claude in a split pane of the same session: the main pane wins,
                # and among equals the claude that started first (not the order of the registry files)
                by_start = sorted(cands, key=lambda r: (r.started_at or 0, r.pid))
                on_main = [r for r in by_start if mp and r.pane == mp and id(r) in on_pane]
                primary = (on_main or by_start)[0]
            folder, alias, yolo = parse_name(name)
            s = {
                "name": name, "short": shorts[name], "alias": alias, "folder": folder,
                "cwd": None, "account": None, "model": man.get("model"), "mode": man.get("mode"),
                "pid": None, "pane": None, "sessionId": None, "version": None, "transcript": None,
                "state": None, "waitingFor": None, "stateSince": None, "verified": False,
                "teammates": max(0, len(cands) - 1), "staleRecords": stale.get(name, 0),
                "self": name == self_name,
            }
            cfg = None
            if primary:
                r = primary
                cfg = r.cfg
                s.update(cwd=r.cwd, account=r.account or man.get("accountSlug"), pid=r.pid, pane=r.pane,
                         sessionId=r.session_id, version=r.version, state=r.status or "unknown",
                         waitingFor=safe_text(r.waiting_for, 80) or None, stateSince=r.status_at)
                # I1 without capture-pane: the record was keyed through its pane (live pane of THIS session, pid below
                # the pane's process); a record keyed by its stored name only is never verified
                s["verified"] = id(r) in on_pane
            else:
                s["account"] = man.get("accountSlug")
                s["cwd"] = man.get("folder")
                s["sessionId"] = man.get("claudeSessionId")
                orphan = None
                if name in tmux_sessions:
                    if ptab is None:
                        ptab = _children(env.proc_table())
                    orphan = _claude_under(ptab, pane_info[main_pane[name]][0])
                if orphan:
                    # claude runs in the pane but has no live registry record (seen live: 3 sessions on 2.1.316): its
                    # state cannot be known, and calling it "dead" would be a lie
                    s["state"], s["pid"], s["noRegistry"] = "unknown", orphan, True
                elif name in tmux_sessions:
                    s["state"] = "dead"
                elif panes is not None:
                    s["state"] = "missing"
                else:
                    s["state"] = "unknown"
            fs = fold.s.get(name) or {}
            last = fs.get("last")
            s["lastEvent"] = {"ev": last["ev"], "ms": last["ms"]} if last else None
            if s["stateSince"] is None and last:
                s["stateSince"] = last["ms"]
            if s.get("noRegistry"):
                # no registry record (claude after a --resume restart can lack one): the last hook event is the only
                # signal, so take it, marked as inferred, instead of "?". An event older than the claude process is
                # from its previous life; no event since it started = it sits idle at its prompt (2026-11-09: 8
                # sessions restarted the day before had neither a record nor an event since).
                born = _proc_start_ms(env, s.get("pid"))
                ev = last if last and (born is None or last["ms"] >= born) else None
                if ev and ev["ev"] in EVENT_STATE:
                    s["state"], s["stateInferred"] = EVENT_STATE[ev["ev"]], True
                elif ev is None and s.get("pid"):
                    s["state"], s["stateInferred"] = "idle", True
            stop = fs.get("stop")
            if s["state"] == "idle" and last and last["ev"] == "stop" and stop and stop.get("bg"):
                # the turn ended with background tasks (agents, a watched build) still running: not idle, and it
                # will wake itself; calling it "nečinná" misled Lukáš (2026-11-08)
                s["state"] = "bg"
            # transcript tail, re-read only when the file changed
            tp = transcript_path(env, cfg, s["cwd"], s["sessionId"])
            s["transcript"] = tp
            ti = {}
            if tp:
                try:
                    stt = os.stat(tp)
                    key = f"{stt.st_size}:{stt.st_mtime_ns}"
                except OSError:
                    key = None
                c = tr_cache.get(tp)
                if c and c[0] == key:
                    ti = c[1]
                elif key:
                    ti = transcript_info(tp)
                    ti["_key"] = key
                    tr_cache[tp] = (key, ti)
            s["trKey"] = ti.get("_key")
            s["lastPromptMs"] = ti.get("lastPromptMs")
            s["lastAssistantMs"] = ti.get("lastAssistantMs")
            s["endsWithQuestion"] = bool(ti.get("endsWithQuestion"))
            lh = ti.get("limitHit")
            s["limit"] = {"ms": lh.get("ms"), "text": safe_text(lh.get("text"), 80)} if isinstance(lh, dict) else None
            if s["limit"] and isinstance(lh.get("autoContinue"), str):
                s["limit"].update({k: lh.get(k) for k in ("autoContinue", "autoContinueMs") if lh.get(k) is not None})
            if s["limit"] and lh.get("lastHitMs"):
                s["limit"]["lastHitMs"] = lh["lastHitMs"]
            # Stored and shown only escaped: whatever reads status.json (a model included) gets data, not instructions.
            s["title"] = safe_text(ti.get("title"), 100) or None
            s["lastPrompt"] = safe_text(ti.get("lastPrompt"), 160) or None
            att = _attention(s, fs, ti, now_ms)
            if att:
                att["text"] = safe_text(att.get("text"), 160)
            s["attention"] = att
            out[name] = s
        except Exception as e:  # noqa: BLE001
            # one malformed record or transcript must not take the whole picture down (the daemon would go blind)
            out[name] = {"name": name, "short": shorts[name], "alias": parse_name(name)[1], "state": "unknown",
                         "attention": None, "self": name == self_name, "error": f"{type(e).__name__}: {e}"[:200]}
    return out


def _children(table):
    kids = collections.defaultdict(list)
    for pid, (ppid, comm) in table.items():
        kids[ppid].append((pid, comm))
    return kids


def _claude_under(kids, root, limit=4096):
    """pid of the first claude process below root (breadth first), or None."""
    q, seen = collections.deque([root]), 0
    while q and seen < limit:
        p = q.popleft()
        seen += 1
        for c, comm in kids.get(p, ()):
            if is_claude_comm(comm):
                return c
            q.append(c)
    return None


def _attention(s, fs, ti, now_ms):
    """What the session waits for from Lukáš, or None. Order: open perm/ask, registry waiting, Stop with a question."""
    lim = s.get("limit")
    if lim and s["state"] not in ("dead", "missing"):
        return {"kind": "limit", "text": lim.get("text") or "", "since": lim.get("ms")}
    op = fs.get("open")
    state = s["state"]
    if op:
        # The registry is the fresher signal: a perm answered in the TUI leaves no event behind, but the registry
        # flips to busy after it. An open ask/perm counts while the registry is `waiting` or has not moved since
        # (hook-ev writes its line after the registry flipped to waiting, so "moved" means strictly later).
        reg_moved = s["stateSince"] is not None and s["stateSince"] > op["ms"] and state in ("busy", "idle")
        expired = state == "unknown" and now_ms - op["ms"] > OPEN_MAX_AGE_MS
        if state == "waiting" or (not reg_moved and not expired and state not in ("dead", "missing")):
            return {"kind": op["ev"], "text": op.get("extra") or "", "since": op["ms"]}
    if state == "waiting":
        return {"kind": "waiting", "text": s.get("waitingFor") or "", "since": s["stateSince"]}
    last = fs.get("last") or {}
    if state == "idle" and s["endsWithQuestion"] and last.get("ev") in (None, "stop"):
        pm, am = ti.get("lastPromptMs"), ti.get("lastAssistantMs")
        if am and (pm is None or am >= pm) and now_ms - am <= QUESTION_MAX_AGE_MS:
            return {"kind": "question", "text": "", "since": last.get("ms") or am}
    return None


def inbox(sessions):
    """Sessions waiting for Lukáš, oldest first (the self session excluded)."""
    items = [s for s in sessions.values() if s.get("attention") and not s.get("self")]
    return sorted(items, key=lambda s: (s["attention"].get("since") or 0, s["name"]))


# A change in one of these makes a session part of the next delta. lastPrompt/title churn every turn and are not
# news on their own; the state transition that comes with them is.
def significant(s):
    a = s.get("attention") or {}
    return (s.get("state"), s.get("waitingFor"), a.get("kind"), a.get("text"))


def snapshot(env, fold=None, self_name=None, tr_cache=None, now_ms=None, ancestor_self=True):
    """A complete one-shot snapshot (cr-sessions, and cr-digest when the daemon is not running)."""
    now_ms = env.now_ms() if now_ms is None else now_ms
    regs = read_registry(env)
    panes = env.tmux_panes()
    self_name = resolve_self(env, regs, panes, ancestor_self) if self_name is None else self_name
    fold = fold or fold_events_file(env, ignore=self_name)
    sessions = build_sessions(env, regs, panes, read_manifest(env), fold, self_name, tr_cache, now_ms)
    return {"at": now_ms, "self": self_name, "tmuxOk": panes is not None, "sessions": sessions,
            "registryRecords": len(regs), "registryLive": sum(1 for r in regs if r.alive)}


# --------------------------------------------------------------------------------------------------------------------
# Czech

_NUM_F = {1: "jedna", 2: "dvě", 3: "tři", 4: "čtyři", 5: "pět", 6: "šest", 7: "sedm", 8: "osm", 9: "devět",
          10: "deset"}


def num_f(n):
    return _NUM_F.get(n, str(n))


def plural(n, one, few, many):
    return one if n == 1 else few if 2 <= n <= 4 else many


def minutes_cz(ms):
    """'minutu', '3 minuty', '12 minut', '2 hodiny' (accusative after 'čeká')."""
    m = max(0, int(ms // 60000))
    if m < 1:
        return "chvíli"
    if m < 60:
        return "minutu" if m == 1 else f"{m} {plural(m, 'minutu', 'minuty', 'minut')}"
    h = m // 60
    return "hodinu" if h == 1 else f"{h} {plural(h, 'hodinu', 'hodiny', 'hodin')}"


def ago_short(ms):
    m = max(0, int(ms // 60000))
    if m < 60:
        return f"{m} min"
    if m < 48 * 60:
        return f"{m // 60} h"
    return f"{m // 1440} d"
