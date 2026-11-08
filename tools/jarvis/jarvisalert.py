"""Jarvis F3: Jarvis speaks up by himself. Alerts for the app, modes, "nehlas" rules, reminders, waking Jarvis.

Shared by `jarvis_d.py` (AlertWorker: the only writer of the alerts file and the only waker), `cr_rules.py`,
`cr_jarvis.py`, `cr_task.py` (reminders) and `cr_digest.py` (briefing). Design: `JARVIS-navrh.md` §3 (binding),
`f3/00-brief.md` (the contract between the server and the app).

The contract, `~/.cache/claude-remote/jarvis-alerts` (append, one line per alert, read by the app's `tail -F`):
  claude-remote-jarvis <id> <block|done|info|incident> <ms> <b64(text)> <b64(tmux name) | ->
  claude-remote-jarvis hb <ms>                                     every ~60 s while jarvis-d runs (the app's lease)
`text` is one Czech sentence (a batch: "N věcí: …"), no paths, no texts written by a session. The last field is the
one session the line is about, or a literal `-` (a batch of several, an incident, a reminder). Only jarvis-d writes
it, under `<state>/alerts.lock`, each line with ONE os.write on an O_APPEND descriptor, and only jarvis-d rotates it:
over 2 MB the last 2000 lines go to a tmp file that is renamed over it (a new inode, which `tail -F` follows).

What makes an alert (§3 point 1) and nothing else:
- block     perm / ask / registry waiting / a Stop whose reply ends with a question, once it has waited its grace:
            3 min in a session working on a task from Jarvis, 15 min elsewhere, 1 min while Lukáš is present
            (some tmux client had input in the last 2 min). One episode = one alert; the same session blocked again
            on the same thing within 10 min is the same item (dedup); a block that ended before its grace, or one
            older than 6 h when first seen, is never told (expiry).
- done      what Lukáš asked to hear (HNED, §3 point 1): results of tasks given through Jarvis (a reply to a task's
            mail, told with the task's state; a session whose Stop had background tasks still runs, so it waits; a
            task's evidence `proof`; a task closed or failed by Jarvis; a task's mail that could not be delivered;
            only these wake Jarvis), a watch fired ("dej vědět, až…"), a reminder he set.
- info      news that is no reason to disturb him (none emitted yet; kept in the contract).
- incident  more than INCIDENT_N session starts/ends within a minute: one line at once, outside the batching.
Batching (§3 point 4): at most one line per 5 minutes, all due items in it. Modes (§3 point 7, `<state>/mode`):
normal; blocking = blocks, task results, reminders and incidents (watches wait); dnd[:<until ms>] = incidents only.
What a mode holds back stays pending until it expires. Rules "nehlas" (`<state>/rules`, cr-rules) drop matching
items, never perm/ask/waiting, incidents or reminders.

Waking Jarvis (§3 point 3): a task result that went out is also pasted into Jarvis's pane as ONE line
`[jarvis wake] <sentences>` through the mail's own checks (I1 live claude, I2 idle + settled + empty box + no
selector, I3 Enter only over exactly our line, I5 no client input in 20 s / copy-mode / hold), never while a mail
proposal waits for Lukáš's "ano" (since rev-f4 L7 a `[jarvis` prompt no longer drops it, but a wake between his question
and his answer would still bury the question), never with mail off or in dnd; at most once
per 3 min and 30 times a day. The line is fixed: `[jarvis wake] Výsledek úkolu t-… · cr-task show t-…` (no title). Lukáš writing to Jarvis in the meantime makes it moot (the delta told it).
"""
import base64
import datetime
import fcntl
import json
import os
import re
import secrets
import time

import jarvislib as J

PREFIX = "claude-remote-jarvis"
KINDS = ("block", "done", "info", "incident")
SEVERITY = {"incident": 3, "block": 2, "done": 1, "info": 0}
ALERT_RE = re.compile(r"a-\d{8}T\d{6}-[0-9a-f]{4}")
RULE_RE = re.compile(r"n-\d{8}T\d{6}-[0-9a-f]{4}")
REMIND_RE = re.compile(r"r-\d{8}T\d{6}-[0-9a-f]{4}")

ROTATE_BYTES = 2 * 1024 * 1024
ROTATE_KEEP = 2000
HB_EVERY_MS = 60 * 1000

GRACE_TASK_MS = 3 * 60 * 1000
GRACE_OTHER_MS = 15 * 60 * 1000
GRACE_PRESENT_MS = 60 * 1000
PRESENT_MS = 2 * 60 * 1000
PRESENCE_EVERY_MS = 15 * 1000
BATCH_GAP_MS = 5 * 60 * 1000
BLOCK_STALE_MS = 6 * 3600 * 1000
REPEAT_MS = 10 * 60 * 1000
EXPIRE_MS = {"task": 2 * 3600 * 1000, "watch": 3600 * 1000, "reminder": 2 * 3600 * 1000, "incident": 10 * 60 * 1000,
             "closed": 2 * 3600 * 1000}
INCIDENT_N = 8
INCIDENT_WINDOW_MS = 60 * 1000
INCIDENT_COOLDOWN_MS = 10 * 60 * 1000
WAKE_GAP_MS = 3 * 60 * 1000
WAKE_DAY_MAX = 30
WAKE_EXPIRE_MS = 30 * 60 * 1000
TEXT_MAX = 400
WAKE_PREFIX = "[jarvis wake] "
MIN_TIME_LEFT_S = 25

MODES = ("normal", "blocking", "dnd")
MODE_CZ = {"normal": "normální", "blocking": "jen blokující", "dnd": "nerušit"}
# never silenced by a rule: what blocks Lukáš outright
NEVER_SILENCED = ("perm", "ask", "waiting")
RULE_KINDS = ("question", "task", "watch", "*")
RULE_KIND_CZ = {"question": "odpovědi končící otázkou", "task": "výsledky úkolů", "watch": "splněné hlídky",
                "*": "všechno kromě povolení, dotazů a dialogů"}
RULE_TTL_MS = 7 * 86400 * 1000
RULES_MAX = 50
REMIND_MAX_MS = 7 * 86400 * 1000
REMIND_TEXT_MAX = 200
# a prompt of Lukáš's that asks for silence; cr-rules from Jarvis's pane needs one (see cr_rules.py). Imperatives
# only (rev-f3 MED2): "Proč jsi mi to nehlásil?" or "nechci teď nic měnit" are not a request to be silent.
SILENCE_WORDS = re.compile(r"\bnehl[aá]s(?:te)?\b|\bnehlásit\b|přesta[nň]\w*\s+(?:to\s+|mi\s+)?hl[aá]sit"
                           r"|\bneozývej|\bneotravuj|\bnechci\s+(?:to\s+|už\s+)?(?:slyšet|hlásit|to\s+vědět)",
                           re.IGNORECASE)
# a prompt that asks for a quiet mode; cr-jarvis mode dnd|blocking from Jarvis's pane needs one (rev-f3 MED1)
QUIET_WORDS = re.compile(r"\bneruš|\bnerušit\b|\bdnd\b|jen\s+blokující|jen\s+důležit", re.IGNORECASE)
PROMPT_FRESH_MS = 10 * 60 * 1000
PER_PROMPT = 3


def _i(v):
    r = J._int(v)
    return 0 if r is None else r


def b64(s):
    return base64.b64encode(s.encode("utf-8")).decode("ascii")


def new_id(prefix, ms):
    t = datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).strftime("%Y%m%dT%H%M%S")
    return f"{prefix}-{t}-{secrets.token_hex(2)}"


def hm(ms):
    return time.strftime("%H:%M", time.localtime(ms / 1000))


def when_cz(ms, now):
    """'15:30', 'zítra 7:00', '14. 11. 9:00' (local time)."""
    d, n = datetime.datetime.fromtimestamp(ms / 1000), datetime.datetime.fromtimestamp(now / 1000)
    t = f"{d.hour}:{d.minute:02d}"
    if d.date() == n.date():
        return t
    if d.date() == (n + datetime.timedelta(days=1)).date():
        return "zítra " + t
    return f"{d.day}. {d.month}. {t}"


# ---------------------------------------------------------------------------------------------------------- time

AT_RE = re.compile(r"([01]?\d|2[0-3])[:.]([0-5]\d)")
IN_RE = re.compile(r"(?:(\d{1,3})d)?(?:(\d{1,3})h)?(?:(\d{1,4})m)?")


def parse_at(s, now):
    """HH:MM -> ms of its next occurrence in local time (today, or tomorrow when it has passed). ValueError."""
    m = AT_RE.fullmatch((s or "").strip())
    if not m:
        raise ValueError("čas je HH:MM, např. 15:30")
    n = datetime.datetime.fromtimestamp(now / 1000)
    t = n.replace(hour=int(m.group(1)), minute=int(m.group(2)), second=0, microsecond=0)
    if t.timestamp() * 1000 <= now:
        t += datetime.timedelta(days=1)
    return int(t.timestamp() * 1000)


def parse_in(s):
    """'90m', '2h', '1h30m', '1d' -> ms. ValueError."""
    m = IN_RE.fullmatch((s or "").strip())
    if not m or not any(m.groups()):
        raise ValueError("doba je např. 30m, 2h, 1h30m nebo 1d")
    d, h, mi = (int(x or 0) for x in m.groups())
    ms = ((d * 24 + h) * 60 + mi) * 60000
    if ms <= 0:
        raise ValueError("doba musí být kladná")
    return ms


# ---------------------------------------------------------------------------------------------------------- mode

def read_mode(env, now):
    """(mode, until_ms or None). Anything unreadable or expired is `normal`: a broken file must make Jarvis louder,
    never silent."""
    try:
        with open(env.path("mode"), encoding="utf-8") as f:
            raw = f.read(200).strip()
    except OSError:
        return "normal", None
    mode, _, until = raw.partition(":")
    if mode not in MODES:
        return "normal", None
    if mode != "dnd" or not until:
        return mode, None
    try:
        u = int(until)
    except ValueError:
        return "normal", None
    return ("dnd", u) if u > now else ("normal", None)


def write_mode(env, mode, until=None):
    os.makedirs(env.state_dir, mode=0o700, exist_ok=True)
    J.atomic_write(env.path("mode"), (f"{mode}:{until}" if until else mode) + "\n")


def mode_say(mode, until, now):
    if mode == "dnd":
        return "Nerušit" + (f" do {when_cz(until, now)}" if until else " do odvolání") + ", ozvu se jen při incidentu."
    if mode == "blocking":
        return "Jen blokující: hlásím povolení, dotazy, výsledky úkolů, připomínky a incidenty."
    return "Normální režim."


def allowed_by_mode(item, mode):
    if mode == "dnd":
        return item["src"] == "incident"
    if mode == "blocking":
        return item["src"] != "watch"
    return True


# ---------------------------------------------------------------------------------------------------------- stores

class Store:
    """A small JSON-lines file under its own flock: append (reminders, events) or rewrite (rules)."""

    def __init__(self, env, name):
        self.env = env
        self.path = env.path(name)
        self.lock_path = env.path(name + ".lock")

    def lines(self):
        out = []
        for line in J.read_tail(self.path, 1024 * 1024):
            try:
                d = json.loads(line)
            except ValueError:
                continue  # a torn or foreign line never breaks the fold
            if isinstance(d, dict):
                out.append(d)
        return out

    def locked(self, fn):
        os.makedirs(self.env.state_dir, mode=0o700, exist_ok=True)
        with open(self.lock_path, "a+") as lk:
            fcntl.flock(lk.fileno(), fcntl.LOCK_EX)
            try:
                return fn()
            finally:
                fcntl.flock(lk.fileno(), fcntl.LOCK_UN)

    def append_raw(self, d):
        fd = os.open(self.path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        try:
            os.write(fd, (json.dumps(d, ensure_ascii=False, separators=(",", ":")) + "\n").encode())
        finally:
            os.close(fd)

    def rewrite_raw(self, rows):
        J.atomic_write(self.path, "".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n"
                                          for r in rows))


class Rules(Store):
    """`<state>/rules`: one rule per line `{id, match:{session?, kind?}, until_ms, created, by, note?, prompt?}`.
    Rewritten (tmp + rename) by cr-rules only; jarvis-d reads it. Expired rules are dropped on the next write."""

    def __init__(self, env):
        super().__init__(env, "rules")

    def all(self, now=None):
        out = []
        for d in self.lines():
            m = d.get("match")
            if not (RULE_RE.fullmatch(str(d.get("id"))) and isinstance(m, dict) and isinstance(d.get("until_ms"), int)
                    and isinstance(d.get("created"), int)):
                continue
            # rev-f3 LOW8: a hand-written rule cannot outlive the 7 days nor date itself into the future
            if d["until_ms"] > d["created"] + RULE_TTL_MS or (now is not None and d["created"] > now + 60000):
                continue
            if m.get("kind") in NEVER_SILENCED:
                continue  # hand-edited: such a rule never counts
            if now is None or d["until_ms"] > now:
                out.append(d)
        return out

    @staticmethod
    def matches(rule, item, sessions=None):
        """Whole-name match against the session's tmux name, short name, alias or folder: no globs, so a rule says
        exactly which sessions it covers (and the gate needs no `*` in an argument)."""
        # F4 "Zavřel jsem X." is the receipt of an act with its undo ("Vrátit"), not news: no rule silences it
        if item.get("sub") in NEVER_SILENCED or item["src"] in ("incident", "reminder", "closed"):
            return False
        m = rule.get("match") or {}
        kind = m.get("kind") or "*"
        src_kind = "question" if item["src"] == "block" else item["src"]
        if kind != "*" and kind != src_kind:
            return False
        pat = m.get("session")
        if not pat:
            return True
        name = item.get("name")
        if not name:
            return False
        return pat in session_keys(name, (sessions or {}).get(name))

    def silenced(self, item, now, sessions=None, rules=None):
        for r in (self.all(now) if rules is None else rules):
            if self.matches(r, item, sessions):
                return r
        return None


def session_keys(name, s=None):
    folder, alias, _ = J.parse_name(name)
    keys = {name, J.short_name(name), folder, alias}
    if isinstance(s, dict):
        keys |= {s.get("short"), s.get("alias"), s.get("folder")}
    return {k for k in keys if k}


class Reminders(Store):
    """`<state>/reminders.jsonl`: events add (cr-task remind), cancelled (cr-task unremind), fired (jarvis-d).
    A reminder's state is the fold of its events, as in tasks.jsonl."""

    def __init__(self, env):
        super().__init__(env, "reminders.jsonl")

    def fold(self, events=None):
        rs = {}
        for e in self.lines() if events is None else events:
            rid, ev = e.get("id"), e.get("ev")
            if not REMIND_RE.fullmatch(str(rid)):
                continue
            if ev == "add" and rid not in rs and isinstance(e.get("due"), int):
                rs[rid] = {"id": rid, "text": e.get("text"), "due": e["due"], "created": e.get("at"),
                           "by": e.get("by"), "state": "open", "closedAt": None}
            elif ev in ("fired", "cancelled") and rid in rs and rs[rid]["state"] == "open":
                rs[rid].update(state=ev, closedAt=e.get("at"))
        return rs

    def append(self, ev, guard=None):
        def go():
            if guard:
                why = guard(self.fold())
                if why:
                    return why
            self.append_raw(ev)
            return None
        return self.locked(go)


# ---------------------------------------------------------------------------------------------------------- the file

def alert_line(aid, kind, ms, text, name=None):
    return f"{PREFIX} {aid} {kind} {ms} {b64(text)} {b64(name) if name else '-'}\n"


def parse_alert(line):
    """{id, kind, ms, text, name} | {hb: ms} | None."""
    f = line.split()
    if len(f) < 3 or f[0] != PREFIX:
        return None
    if f[1] == "hb":
        return {"hb": _i(f[2])} if f[2].isdigit() else None
    if len(f) != 6 or not ALERT_RE.fullmatch(f[1]) or f[2] not in KINDS or not f[3].isdigit():
        return None
    try:
        text = base64.b64decode(f[4], validate=True).decode("utf-8")
        name = None if f[5] == "-" else base64.b64decode(f[5], validate=True).decode("utf-8")
    except (ValueError, UnicodeDecodeError):
        return None
    return {"id": f[1], "kind": f[2], "ms": int(f[3]), "text": text, "name": name}


def read_alerts(env, since_ms=0):
    out = []
    for line in J.read_tail(env.alerts_path, 1024 * 1024):
        a = parse_alert(line)
        if a and "id" in a and a["ms"] > since_ms:
            out.append(a)
    return out


class AlertFile:
    def __init__(self, env):
        self.env = env
        self.path = env.alerts_path
        self.lock_path = env.path("alerts.lock")

    def write(self, line):
        """One line, one write(2) on O_APPEND, under the lock; rotation under the same lock."""
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        os.makedirs(self.env.state_dir, mode=0o700, exist_ok=True)
        with open(self.lock_path, "a+") as lk:
            fcntl.flock(lk.fileno(), fcntl.LOCK_EX)
            try:
                self._rotate()
                fd = os.open(self.path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
                try:
                    os.write(fd, line.encode("utf-8"))
                finally:
                    os.close(fd)
            finally:
                fcntl.flock(lk.fileno(), fcntl.LOCK_UN)

    def _rotate(self):
        try:
            if os.path.getsize(self.path) <= ROTATE_BYTES:
                return
        except OSError:
            return
        keep = J.read_tail(self.path, ROTATE_BYTES)[-ROTATE_KEEP:]
        J.atomic_write(self.path, "".join(l + "\n" for l in keep))


# ---------------------------------------------------------------------------------------------------------- words

def clean(s, n):
    """A short text for an alert: escaped like every session text, and without anything that looks like a path."""
    t = J.safe_text(s, n * 2) or ""
    t = " ".join(w for w in t.split() if "/" not in w and not w.startswith("~"))
    if len(t) > n:
        t = t[:n - 1].rstrip() + "…"
    return t


BLOCK_CZ = {"perm": "chce povolení", "ask": "se ptá", "waiting": "čeká na tebe", "question": "se v odpovědi ptá"}


def block_sentence(name, kind):
    return f"{J.short_name(name)} {BLOCK_CZ.get(kind, 'čeká na tebe')}"


def batch_text(sentences):
    """One sentence as it is; several as "N věcí: a; b; c." and, when that is too long, "…; a 3 další."."""
    if len(sentences) == 1:
        return _cap(sentences[0] + ".")
    n = len(sentences)
    head = f"{n} {J.plural(n, 'věc', 'věci', 'věcí')}: "
    for k in range(n, 0, -1):
        rest = n - k
        more = [f"a {rest} {J.plural(rest, 'další', 'další', 'dalších')}"] if rest else []
        t = head + "; ".join(sentences[:k] + more) + "."
        if len(t) <= TEXT_MAX:
            return t
    return _cap(head + sentences[0] + ".")


def _cap(t):
    return t if len(t) <= TEXT_MAX else t[:TEXT_MAX - 1].rstrip() + "…"


# ---------------------------------------------------------------------------------------------------------- daemon

class AlertWorker:
    """jarvis-d's half. `state` is JSON-able and lives in d-state.json (a file the live-file canary already
    tolerates), so no new file is rewritten every few seconds. `presence()` -> ms of the latest tmux client input or
    None; `mail` is a jarvismail.Mail (its checks and its tmux seam are reused for the wake)."""

    def __init__(self, env, state=None, mail=None, log=None, presence=None, persist=None):
        import jarvismail as M
        self.env = env
        self.M = M
        self.mail = mail or M.Mail(env)
        self.log = log or (lambda msg: None)
        self.file = AlertFile(env)
        self.rules = Rules(env)
        self.reminders = Reminders(env)
        st = state if isinstance(state, dict) else {}
        self.s = {
            "cur": st.get("cur") if isinstance(st.get("cur"), dict) else None,
            "blocks": st.get("blocks") if isinstance(st.get("blocks"), dict) else {},
            "recent": st.get("recent") if isinstance(st.get("recent"), dict) else {},
            "items": [i for i in st.get("items") or [] if isinstance(i, dict) and i.get("src")],
            "lastEmit": _i(st.get("lastEmit")) or 0,
            "inc": [x for x in st.get("inc") or [] if isinstance(x, list) and len(x) == 2],
            "lastIncident": _i(st.get("lastIncident")) or 0,
            "wake": [w for w in st.get("wake") or [] if isinstance(w, dict)],
            "wakes": [x for x in st.get("wakes") or [] if isinstance(x, int)],
            "day": st.get("day") if isinstance(st.get("day"), dict) else {},
        }
        self.presence_fn = presence or self._tmux_presence
        # rev-f3 LOW5: jarvis-d's d-state writer. Called BEFORE an alert line or a wake paste goes out, with the
        # state that already counts it as done: a crash in between loses at most that one line, never repeats it.
        self.persist = persist or (lambda: None)
        self.present_at, self.presence_checked = None, 0
        self.hb_at = 0
        self.summary = {}

    @property
    def state(self):
        return self.s

    # ------------------------------------------------------------------ helpers

    def _tmux_presence(self):
        rc, out = self.M.run_tmux(self.env.tmux_bin, ["list-clients", "-F", "#{client_activity}"])
        if rc != 0:
            return None
        acts = [int(x) * 1000 for x in out.split() if x.isdigit()]
        return max(acts) if acts else None

    def present(self, now):
        if now - self.presence_checked >= PRESENCE_EVERY_MS:
            self.presence_checked = now
            try:
                self.present_at = self.presence_fn()
            except Exception:  # noqa: BLE001
                self.present_at = None
        return self.present_at is not None and now - self.present_at < PRESENT_MS

    def _count(self, now, key):
        day = time.strftime("%Y-%m-%d", time.localtime(now / 1000))
        if self.s["day"].get("d") != day:
            self.s["day"] = {"d": day}
        self.s["day"][key] = _i(self.s["day"].get(key)) + 1

    def task_sessions(self, tasks, mails):
        """Sessions that work on a task given through Jarvis (3 min grace instead of 15)."""
        names = {t["to"] for t in tasks.values() if t["state"] == "open" and t.get("to")}
        for m in mails:
            t = tasks.get(m.get("task") or "")
            if t and t["state"] == "open" and m["state"] in ("queued", "delivering", "delivered", "replied",
                                                              "uncertain"):
                names.add(m["to"])
        return names

    # ------------------------------------------------------------------ the tick

    def tick(self, new_events, sessions, fold, now, self_name=None, time_left=lambda: 60.0):
        """One pass: heartbeat, sources, emit, wake. Returns True when the state changed (d-state.json to write)."""
        before = json.dumps(self.s, sort_keys=True)
        if now - self.hb_at >= HB_EVERY_MS:
            self.hb_at = now
            self._safe("hb", self.file.write, f"{PREFIX} hb {now}\n")
        import cr_task
        tasks = self._safe("tasks", lambda: cr_task.fold(cr_task.read_events(self.env))) or {}
        mails = self._safe("mails", self.mail.all) or []
        mode, until = read_mode(self.env, now)
        self._safe("incident", self._incidents, new_events or (), now, self_name)
        self._safe("blocks", self._blocks, sessions or {}, now, tasks, mails)
        self._safe("sources", self._sources, now, tasks, mails, sessions or {})
        self._safe("emit", self._emit, now, mode, sessions or {}, tasks, mails)
        self._safe("wake", self._wake, now, mode, fold, self_name, time_left)
        self.summary = {"mode": mode, "until": until, "pending": len(self.s["items"]) + sum(
            1 for b in self.s["blocks"].values() if not b.get("told")), "lastEmit": self.s["lastEmit"] or None,
            "today": {k: v for k, v in self.s["day"].items() if k != "d"}, "wakePending": len(self.s["wake"]),
            "present": self.present_at is not None and now - self.present_at < PRESENT_MS}
        return json.dumps(self.s, sort_keys=True) != before

    def _safe(self, what, fn, *a):
        try:
            return fn(*a)
        except Exception as e:  # noqa: BLE001  one bad source must not silence the others
            self.log(f"alerts {what}: {type(e).__name__}: {e}"[:300])
            return None

    # ------------------------------------------------------------------ incidents

    def _incidents(self, events, now, self_name):
        inc = [x for x in self.s["inc"] if now - x[0] < INCIDENT_WINDOW_MS]
        for e in events:
            # only fresh ones: the first read of the events file brings up to 2 MB of history
            if e.ev in ("start", "end") and e.name != self_name and now - e.ms < INCIDENT_WINDOW_MS:
                inc.append([e.ms, 1 if e.ev == "start" else 0])
        self.s["inc"] = inc[-500:]
        if len(inc) > INCIDENT_N and now - self.s["lastIncident"] >= INCIDENT_COOLDOWN_MS:
            starts = sum(x[1] for x in inc)
            ends = len(inc) - starts
            parts = []
            if starts:
                parts.append(f"{starts} {J.plural(starts, 'start', 'starty', 'startů')}")
            if ends:
                parts.append(f"{ends} {J.plural(ends, 'konec', 'konce', 'konců')}")
            self.s["lastIncident"] = now
            self.s["items"].append({"src": "incident", "kind": "incident", "at": now, "name": None,
                                    "text": "Hromadná událost: za minutu " + " a ".join(parts) + " sessions."})

    # ------------------------------------------------------------------ blocks

    def _blocks(self, sessions, now, tasks, mails):
        task_names = None
        seen = set()
        for name, s in sessions.items():
            if not isinstance(s, dict) or s.get("self"):
                continue
            a = s.get("attention") or {}
            kind = a.get("kind")
            if kind not in BLOCK_CZ:
                continue
            seen.add(name)
            since = _i(a.get("since")) or now
            sig = f"{kind}|{a.get('text') or ''}"
            b = self.s["blocks"].get(name)
            if b and b.get("kind") == kind and b.get("since") == since:
                continue
            if b and b.get("kind") == kind and b.get("sig") == sig and not b.get("told"):
                continue  # the same thing, re-stamped (the registry moved, still blocked): one item
            r = self.s["recent"].get(name) or {}
            told = False
            if r.get("sig") == sig and now - _i(r.get("at")) < REPEAT_MS:
                told = True  # blocked again on the same thing right after it was told: the same item
            if now - since > BLOCK_STALE_MS:
                told = True  # old news when first seen (a daemon that was down for hours)
            if task_names is None:
                task_names = self.task_sessions(tasks, mails)
            self.s["blocks"][name] = {"kind": kind, "since": since, "sig": sig, "told": told,
                                      "task": name in task_names}
        for name in [n for n in self.s["blocks"] if n not in seen]:
            del self.s["blocks"][name]  # unblocked before or after its alert: nothing more to say (expiry)
        self.s["recent"] = {k: v for k, v in self.s["recent"].items() if now - _i(v.get("at")) < REPEAT_MS}

    def grace(self, b, now):
        if self.present(now):
            return GRACE_PRESENT_MS
        return GRACE_TASK_MS if b.get("task") else GRACE_OTHER_MS

    # ------------------------------------------------------------------ task results, watches, reminders

    def _sources(self, now, tasks, mails, sessions):
        cur = self.s["cur"]
        if cur is None:
            # first run: nothing from before the alerts existed is news
            self.s["cur"] = {"mail": now, "watch": now, "task": now, "ops": now}
            cur = self.s["cur"]
        by_id = {m["id"]: m for m in mails}
        # mail: replies and final failures of a task's mail
        mx = _i(cur.get("mail"))
        for e in self.mail.ledger(_i(cur.get("mail"))):
            mx = max(mx, e["at"])
            ev = e.get("ev")
            if ev not in ("reply", "give-up", "expired"):
                continue
            rec = by_id.get(e.get("id") or "") or {}
            t = tasks.get(rec.get("task") or "")
            if not t:
                continue
            self._add(now, src="task", sub="reply" if ev == "reply" else "mail-fail", task=t["id"],
                      name=rec.get("to"), at=e["at"], wake=True, mail=rec.get("id"))
        cur["mail"] = mx
        # watches: fired, and the evidence of a task
        import jarviswatch as JW
        W = JW.Watches(self.env)
        evs = W.events()
        ws = None
        wx = _i(cur.get("watch"))
        for e in evs:
            if e["at"] <= _i(cur.get("watch")):
                continue
            wx = max(wx, e["at"])
            if e.get("ev") == "proof":
                t = tasks.get(e.get("task") or "")
                if t:
                    self._add(now, src="task", sub="proof", task=t["id"], name=t.get("to"), at=e["at"], wake=True)
            elif e.get("ev") == "fired":
                ws = ws if ws is not None else W.fold(evs)
                w = ws.get(e.get("id"))
                if w:
                    w = dict(w, result=e.get("result"), detail=e.get("detail"))
                    self._add(now, src="watch", sub=w["kind"], name=w.get("target"), at=e["at"],
                              text="Splněno: " + JW.fired_text(w))
        cur["watch"] = wx
        # tasks closed by Jarvis (Lukáš's own close is not news to him)
        import cr_task
        tx = _i(cur.get("task"))
        for e in cr_task.read_events(self.env):
            at = _i(e.get("at"))
            if at <= _i(cur.get("task")):
                continue
            tx = max(tx, at)
            if e.get("ev") in ("done", "fail") and e.get("by") == "jarvis":
                t = tasks.get(e["id"]) or {}
                self._add(now, src="task", sub=e["ev"], task=e["id"], name=t.get("to"), at=at, wake=False)
        cur["task"] = tx
        # F4: sessions Jarvis closed. One line each, with the tmux name in the last field: the app offers "Vrátit"
        # when closed.jsonl has that name's newest record by "jarvis" younger than 24 h (the contract: no new field)
        if cur.get("ops") is None:
            cur["ops"] = now
        ox = _i(cur.get("ops"))
        try:
            import jarvisops as JO
            evs = JO.Ops(self.env, mail=self.mail).ledger(_i(cur.get("ops")))
        except Exception:  # noqa: BLE001
            evs = []
        for e in evs:
            ox = max(ox, e["at"])
            if e.get("ev") == "closed" and e.get("by") == "jarvis" and isinstance(e.get("name"), str):
                self._add(now, src="closed", sub="closed", name=e["name"], at=e["at"],
                          text=f"Zavřel jsem {J.short_name(e['name'])}.")
        cur["ops"] = ox
        # reminders that are due: fired under the store's lock, so one is never told twice
        for r in self.reminders.fold().values():
            if r["state"] != "open" or r["due"] > now:
                continue

            def guard(rs, rid=r["id"]):
                return None if (rs.get(rid) or {}).get("state") == "open" else "closed"
            if self.reminders.append({"v": 1, "at": now, "ev": "fired", "id": r["id"]}, guard) is None:
                self._add(now, src="reminder", sub="reminder", at=r["due"],
                          text="Připomínka: " + (clean(r.get("text"), REMIND_TEXT_MAX) or "bez textu"))

    def _add(self, now, **it):
        it.setdefault("name", None)
        # §3 point 1: a fired watch and a reminder are things Lukáš asked to hear (HNED), like a task's result; `info`
        # stays for news that is no reason to disturb him (the watch vibrates on block|done|incident)
        it["kind"] = {"task": "done", "watch": "done", "reminder": "done", "incident": "incident",
                      "closed": "done"}[it["src"]]
        key = (it["src"], it.get("sub"), it.get("task"), it.get("name"), it.get("at"))
        if any((i["src"], i.get("sub"), i.get("task"), i.get("name"), i.get("at")) == key for i in self.s["items"]):
            return
        self.s["items"].append(it)

    # ------------------------------------------------------------------ emit

    def _task_text(self, it, tasks, mails, sessions):
        """(sentence, hold): hold=True keeps the item back (the session still runs: `stop bg` is not done)."""
        import cr_task
        import jarviswatch as JW
        t = tasks.get(it.get("task") or "") or {}
        title = clean(t.get("title"), 60) or it.get("task") or "?"
        short = J.short_name(it["name"]) if it.get("name") else "session"
        sub = it.get("sub")
        if sub == "reply":
            label, detail = JW.task_status(t, mails, sessions, cr_task.repo_for(self.env)) if t else ("?", None)
            if label == "běží":
                return None, True
            if label == "čeká na tebe":
                return f"Úkol «{title}»: {short} odpověděla a čeká na tebe", False
            d = clean(detail, 120) if isinstance(detail, str) else ""
            return f"Úkol «{title}»: {short} odpověděla, {label}" + (f" ({d})" if d else ""), False
        if sub == "proof":
            return f"Úkol «{title}» má důkaz (commit, push, CI), stačí ho uzavřít", False
        if sub == "done":
            return f"Úkol «{title}» je hotový", False
        if sub == "fail":
            return f"Úkol «{title}» selhal", False
        return f"Úkol «{title}»: zprávu pro {short} se nepodařilo doručit", False

    def _emit(self, now, mode, sessions, tasks, mails):
        rules = self.rules.all(now)
        keep, incidents = [], []
        for it in self.s["items"]:
            if now - _i(it.get("at")) > EXPIRE_MS.get(it["src"], 3600 * 1000):
                continue  # stale: never told late
            if self.rules.silenced(it, now, sessions, rules):
                continue
            if it["src"] == "incident":
                incidents.append(it)
                continue
            keep.append(it)
        self.s["items"] = keep
        if incidents:
            self._persist()
            for it in incidents:
                self._write(now, "incident", it["text"], None)
        # F4: each close on its own line at once (outside the 5-minute batch, which would replace the name with `-`
        # and so lose the app's "Vrátit"); a mode that holds `done` back holds these too (dnd)
        closed = [it for it in self.s["items"] if it["src"] == "closed" and allowed_by_mode(it, mode)]
        if closed:
            ids = {id(it) for it in closed}
            self.s["items"] = [i for i in self.s["items"] if id(i) not in ids]
            for _ in closed:
                self._count(now, "alerts")
            self._persist()
            for it in closed:
                self._write(now, "done", it["text"], it["name"])
        if now - self.s["lastEmit"] < BATCH_GAP_MS:
            return
        sentences, names, kinds, sent, woke = [], [], [], [], []
        for name, b in sorted(self.s["blocks"].items(), key=lambda kv: kv[1]["since"]):
            if b.get("told") or now - b["since"] < self.grace(b, now):
                continue
            it = {"src": "block", "sub": b["kind"], "name": name}
            if not allowed_by_mode(it, mode) or self.rules.silenced(it, now, sessions, rules):
                continue
            sentences.append(block_sentence(name, b["kind"]))
            names.append(name)
            kinds.append("block")
            sent.append(("block", name))
        for it in self.s["items"]:
            if not allowed_by_mode(it, mode):
                continue
            if it["src"] == "task":
                text, hold = self._task_text(it, tasks, mails, sessions)
                if hold:
                    continue
            else:
                text = it.get("text") or ""
            sentences.append(text)
            names.append(it.get("name"))
            kinds.append(it["kind"])
            sent.append(("item", id(it)))
            if it.get("wake") and mode != "dnd":
                woke.append(it.get("task"))
        if not sentences:
            return
        kind = max(kinds, key=lambda k: SEVERITY[k])
        one = names[0] if len(names) == 1 else None
        self.s["lastEmit"] = now
        done_ids = {x[1] for x in sent if x[0] == "item"}
        self.s["items"] = [i for i in self.s["items"] if id(i) not in done_ids]
        for k, name in sent:
            if k == "block":
                b = self.s["blocks"][name]
                b["told"] = True
                self.s["recent"][name] = {"sig": b["sig"], "at": now}
        for tid in woke:
            if tid and self.M.TASK_RE.fullmatch(tid):
                self.s["wake"].append({"task": tid, "at": now})
        self._count(now, "alerts")
        self._persist()
        self._write(now, kind, batch_text(sentences), one)

    def _write(self, now, kind, text, name):
        if kind == "incident":
            self._count(now, "alerts")
        self.file.write(alert_line(new_id("a", now), kind, now, text, name))

    def _persist(self):
        try:
            self.persist()
        except Exception as e:  # noqa: BLE001  a full disk must not stop the alert itself
            self.log(f"alerts persist: {type(e).__name__}: {e}"[:300])

    # ------------------------------------------------------------------ wake

    def wake_line(self):
        """A FIXED line (rev-f3 LOW6, as mail's I13): task ids and a command, never a title or any other free text,
        so nothing typed by anyone reaches Jarvis's prompt through a wake."""
        ids = []
        for w in self.s["wake"]:
            t = w.get("task")
            if isinstance(t, str) and self.M.TASK_RE.fullmatch(t) and t not in ids:
                ids.append(t)
        ids = ids[:5]
        if not ids:
            return None
        return (WAKE_PREFIX + "Výsledek úkolu " + ", ".join(ids) + " · "
                + " · ".join(f"cr-task show {t}" for t in ids))[:self.M.LINE_MAX]

    def wake_hold(self, now, mode, self_name):
        """None, or why no wake may go now (§3 point 3 and the brief's limits). The screen checks come after."""
        if not self_name:
            return "no-self"
        if mode == "dnd":
            return "dnd"
        if self.s["wakes"] and now - self.s["wakes"][-1] < WAKE_GAP_MS:
            return "gap"
        day = time.strftime("%Y-%m-%d", time.localtime(now / 1000))
        if self.s["day"].get("d") == day and _i(self.s["day"].get("wakes")) >= WAKE_DAY_MAX:
            return "day-cap"
        if any(r["state"] == "proposed" for r in self.mail.all()):
            return "proposal"  # a wake would bury the question Lukáš is about to answer (it no longer drops it: L7)
        return None

    def _wake(self, now, mode, fold, self_name, time_left):
        if not self.s["wake"]:
            return
        lp = max(_i((J.read_json(self.env.path(p)) or {}).get("at")) for p in ("last-prompt", "last-prompt.prev"))
        # expiry: Lukáš wrote to Jarvis after the result (his delta has told it), or it is half an hour old
        self.s["wake"] = [w for w in self.s["wake"] if now - w["at"] < WAKE_EXPIRE_MS and w["at"] > lp]
        self.s["wakes"] = [x for x in self.s["wakes"] if now - x < 86400 * 1000]
        if not self.s["wake"] or self.wake_hold(now, mode, self_name) or time_left() < MIN_TIME_LEFT_S:
            return
        line = self.wake_line()
        if not line or self.M.bad_chars(line) is not None:
            self.s["wake"] = []
            return
        m = self.mail
        t = m.probe(self_name, fold, m.env.now_ms())
        if isinstance(t, str):
            return  # not now (busy, a draft, someone typing, copy-mode, mail off …): the next tick tries again
        self.s["wake"] = []
        self.s["wakes"].append(now)
        self._count(now, "wakes")
        self._persist()  # LOW5: counted before the paste, so a crash inside it never pastes a second wake
        res = self.paste(t, line)
        with m.lock():
            m.log("wake", to=self_name, result=res)

    def paste(self, t, line):
        """The mail's sequence without a record: load-buffer, paste-buffer -d, Enter only over exactly our line
        (ready_for_enter), at most two Enters, confirmation = `work` or registry busy. Returns the outcome."""
        m = self.M
        mail = self.mail
        mail.ensure()
        lp = os.path.join(mail.odir, new_id("wake", self.env.now_ms()) + ".line")
        J.atomic_write(lp, line)
        buf = "crwake-" + os.path.basename(lp)[:-5]
        try:
            rc, _ = mail.tmux(["load-buffer", "-b", buf, lp])
            if rc != 0:
                return "load-buffer"
            since = self.env.now_ms()
            rc, _ = mail.tmux(["paste-buffer", "-d", "-b", buf, "-t", t.pane])
            if rc != 0:
                mail.tmux(["delete-buffer", "-b", buf])
                return "paste-failed"
            mail.sleep(mail.paste_check_s)
            for _ in range(m.MAX_ENTERS):
                why = mail.ready_for_enter(t, line)
                if why:
                    return "uncertain:" + why
                mail.tmux(["send-keys", "-t", t.pane, "Enter"])
                if mail.wait_confirm(t, since):
                    return "delivered"
            return "uncertain:no-confirm"
        finally:
            try:
                os.unlink(lp)
            except OSError:
                pass
