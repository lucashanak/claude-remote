"""Jarvis F3: Jarvis speaks up by himself. Alerts for the app, modes, "nehlas" rules, reminders, waking Jarvis.

Shared by `jarvis_d.py` (AlertWorker: the only writer of the alerts file and the only waker), `cr_rules.py`,
`cr_jarvis.py`, `cr_task.py` (reminders) and `cr_digest.py` (briefing). Design: `JARVIS-navrh.md` §3 (binding),
`f3/00-brief.md` (the contract between the server and the app).

The contract, `~/.cache/claude-remote/jarvis-alerts` (append, one line per alert, read by the app's `tail -F`):
  claude-remote-jarvis <id> <block|done|info|incident> <ms> <b64(text)> <b64(tmux name) | ->
  claude-remote-jarvis <id> <jsay|jblock|jask|jblockask> <ms> <b64(Jarvis's own text, ≤ 400)> <b64(tmux name) | ->
      F5c: jsay = news; jblock = the batch blocks Lukáš (high priority); jask = exactly one proposal waits (action
      Ano); jblockask = both (high priority AND Ano). Old apps ignore these kinds.
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
            task's evidence `proof`; a task closed or failed by Jarvis; a task's mail that could not be delivered),
            a watch fired ("dej vědět, až…"), a reminder he set.
- info      news that is no reason to disturb him: F5c's stuck / crashed / usage, only as the fallback of a wake.
- incident  more than INCIDENT_N session starts/ends within a minute: one line at once, outside the batching.
Batching (§3 point 4): at most one line per 5 minutes, all due items in it. Modes (§3 point 7, `<state>/mode`):
normal; blocking = blocks, task results, reminders and incidents (watches wait); dnd[:<until ms>] = incidents only.
What a mode holds back stays pending until it expires. Rules "nehlas" (`<state>/rules`, cr-rules) drop matching
items, never perm/ask/waiting, incidents or reminders.

Waking Jarvis (F5c, Lukáš 2026-11-10 "je hrozně pasivní"): the batch above first wakes Jarvis instead of going out as
a template, when the wake route is open (Jarvis known, mail on, not the saver, under 60 wakes a day, no `wake-off`
switch) and the mode lets it (`wake_allowed`: dnd = incidents and perm/ask; blocking = blocks, his tasks, incidents).
The sources: blocks after their grace (perm/ask/waiting/question/limit/stuck mail), task results, fired watches and
reminders, incidents, and three new ones: a session busy without any sign of life for 30 min (`stuck`), a session
that vanished or lost its claude in the middle of work or with Jarvis's task (`crashed`), an account crossing 80/90 %
of its 5 h or 7 d window (`usage`); plus a round every 30 min when something Jarvis has not seen changed (`round`).
One wake at a time, ≤ 1 per 5 min, ≤ 60 a day; never while Lukáš talks to Jarvis (his prompt or a client's input on
Jarvis < 2 min, or Jarvis not idle), never while a proposal waits, never with mail off. The line is FIXED:
`[jarvis wake w-…] N událostí · cr-digest wake w-…` (the batch is `<state>/wakes/<w-id>.json`), pasted through the
mail's own checks (I1 live claude, I2 idle + settled + empty box + no selector, I3 Enter only over exactly our line,
I5 no client input in 20 s / copy-mode / hold). His FINAL text of that turn (stop_reason end_turn, read 1.5 s after
his Stop) becomes one j-line; an answer starting `[ticho]` says nothing (perm/ask/waiting and his own steps are told
anyway); a lost Enter gets two more over exactly our line (a deliberate deviation from the mail's I4 "≤ 2 Enters per
message": a wake may get up to 4 Enters, each only while the box holds exactly our line, see `_recover_enter`); no
paste within 3 min or no answer within 3 min of the Enter that took
of the paste = the batch's template lines. Jarvis's own account at ≥ 95 % 7 d or ≥ 85 % 5 h = the saver: no wakes,
templates only, said once in the delta.
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
# F5c: Jarvis's own words (jsay|jblock|jask) next to the templates; an old app ignores the kinds it does not know
# rev-f5c L1: `jblockask` = a blocking batch AND exactly one proposal: high priority like jblock, the Ano action like
# jask (one kind, so the six fields of the contract stay as they are)
J_KINDS = ("jsay", "jblock", "jask", "jblockask")
KINDS = ("block", "done", "info", "incident") + J_KINDS
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
# F5c (Lukáš 2026-11-10: "events + a round"): one wake per 5 minutes at most, 60 a day
WAKE_GAP_MS = 5 * 60 * 1000
WAKE_DAY_MAX = 60
TEXT_MAX = 400
WAKE_PREFIX = "[jarvis wake "
MIN_TIME_LEFT_S = 25
# the wake must be in Jarvis's box this long after its batch formed, and his answer must follow this long after the
# paste; otherwise the batch goes out as today's template lines (the fallback)
PASTE_WAIT_MS = 3 * 60 * 1000
REPLY_WAIT_MS = 3 * 60 * 1000
# "never during Lukáš's conversation": his prompt to Jarvis, or input of a client attached to Jarvis, this fresh
CONVERSATION_MS = 2 * 60 * 1000
ROUND_MS = 30 * 60 * 1000
STUCK_MS = 30 * 60 * 1000
CRASH_FRESH_MS = 10 * 60 * 1000
CRASH_CLOSED_MS = 15 * 60 * 1000
USAGE_LEVELS = (80, 90)
USAGE_FRESH_S = 900
USAGE_EVERY_MS = 30 * 1000
# the saver: Jarvis's own account this spent stops the wakes (templates go on); said once in the delta
SAVER_7D, SAVER_5H = 95, 85
WAKES_KEEP_MS = 2 * 86400 * 1000
WAKES_KEEP_N = 200
# Lukáš's (and the lead's) instant switch back to the F3 behaviour: no wake at all, templates only
WAKE_OFF = "wake-off"
# rev-f5c L2: an answer that STARTS with [ticho] is silence; whatever follows it is dropped
SILENT_RE = re.compile(r"\s*\[ticho\]", re.IGNORECASE)
# rev-f5c H1: Claude writes the transcript a moment after the Stop hook: the final text is read this long after it
REPLY_SETTLE_MS = 1500
# rev-f5c L6: a wake line left in Jarvis's box by a lost Enter gets this many more Enters (only over exactly it)
RECOVER_ENTERS = 2
_ID_WORD = re.compile(r"\b[amnrtwx]-\d{8}T\d{6}-[0-9a-f]{4}\b")
_MD = re.compile(r"[*`#>|]+|(?<!\w)_+|_+(?!\w)")  # markdown marks; an underscore inside a name stays

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


# F5c sources that are news, not a reason to break a quiet mode (and none of them had a template before F5c)
INFO_SRC = ("stuck", "crashed", "usage", "round")


def allowed_by_mode(item, mode):
    """May a TEMPLATE line carry it now (F3, unchanged; the F5c sources only in the normal mode)."""
    if mode == "dnd":
        return item["src"] == "incident"
    if mode == "blocking":
        return item["src"] not in ("watch",) + INFO_SRC
    return True


def wake_allowed(item, mode):
    """May it wake Jarvis now (F5c): dnd = an incident or a permission / question of a session; blocking = blocks
    (perm/ask/waiting/question/limit/stuck mail), his own tasks' results, incidents; normal = everything."""
    if mode == "dnd":
        return item["src"] == "incident" or always_told(item)
    if mode == "blocking":
        return item["src"] in ("block", "task", "incident")
    return True


def always_told(item):
    """rev-f5c M1: what no rule silences (perm, ask, waiting) is told in every mode and after [ticho] too."""
    return item.get("src") == "block" and item.get("sub") in NEVER_SILENCED


def blocking_item(item):
    """jblock: the batch holds something that blocks Lukáš (perm/ask/waiting, a limit, an incident …)."""
    return item["src"] in ("block", "incident")


def jarvis_text(raw):
    """Jarvis's answer as one notification / TTS text: no markdown, no ids, no paths, one line, ≤ 400 characters
    (cut at a sentence end when it can be). It is still DATA for the app (shown and spoken, never executed)."""
    t = J.safe_text(_MD.sub("", raw or ""), TEXT_MAX * 3) or ""
    if t == J.HIDDEN:
        return ""
    t = _ID_WORD.sub("", t)
    t = " ".join(w for w in t.split() if "/" not in w and not w.startswith("~") and w not in ("-", "•"))
    if len(t) > TEXT_MAX:
        cut = t[:TEXT_MAX]
        end = max(cut.rfind(". "), cut.rfind("? "), cut.rfind("! "))
        t = cut[:end + 1] if end > TEXT_MAX // 2 else cut[:TEXT_MAX - 1].rstrip() + "…"
    return t.strip()


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


BLOCK_CZ = {"perm": "chce povolení", "ask": "se ptá", "waiting": "čeká na tebe", "question": "se v odpovědi ptá",
            "limit": "narazila na limit účtu a stojí", "mail": "zatím nedostala zprávu od Jarvise"}
TEMPLATE_KIND = {"task": "done", "watch": "done", "reminder": "done", "incident": "incident", "closed": "done",
                 "stuck": "info", "crashed": "info", "usage": "info", "round": "info", "auto": "done"}
# A message approved but stuck in the queue this long is told (2026-11-09: one waited 16 h, nobody knew).
MAIL_STUCK_MS = 10 * 60 * 1000


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
    None; `mail` is a jarvismail.Mail (its checks and its tmux seam are reused for the wake).

    F5c (active Jarvis): what used to go straight to the alerts file as a template now first WAKES Jarvis when it may
    (`wake_route` is None and the mode lets it, `wake_allowed`): the batch becomes `<state>/wakes/<w-id>.json`, one
    fixed line `[jarvis wake w-…] N událostí · cr-digest wake w-…` goes into his pane through the mail's checks, and
    his answer to that turn becomes ONE `jsay|jblock|jask` line in his own words. The templates of the batch are the
    fallback: no paste within 3 min, no answer within 3 min of the paste, an unreadable answer; and after `[ticho]` the
    permissions and questions (and every step he took by himself) are told anyway."""

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

        def d(k):
            return st.get(k) if isinstance(st.get(k), dict) else {}
        wb = st.get("wb")
        self.s = {
            "cur": st.get("cur") if isinstance(st.get("cur"), dict) else None,
            "blocks": d("blocks"),
            "recent": d("recent"),
            "items": [i for i in st.get("items") or [] if isinstance(i, dict) and i.get("src")],
            "lastEmit": _i(st.get("lastEmit")) or 0,
            "inc": [x for x in st.get("inc") or [] if isinstance(x, list) and len(x) == 2],
            "lastIncident": _i(st.get("lastIncident")) or 0,
            "wakes": [x for x in st.get("wakes") or [] if isinstance(x, int)],
            "day": d("day"),
            # F5c
            "wb": wb if isinstance(wb, dict) and self.M.WAKE_RE.fullmatch(str(wb.get("id"))) else None,
            "roundAt": _i(st.get("roundAt")),
            "stuck": d("stuck"),
            "crashed": d("crashed"),
            "usageLvl": d("usageLvl"),
            "saver": _i(st.get("saver")),
            "autoAt": _i(st.get("autoAt")),
        }
        self.presence_fn = presence or self._tmux_presence
        # rev-f3 LOW5: jarvis-d's d-state writer. Called BEFORE an alert line or a wake paste goes out, with the
        # state that already counts it as done: a crash in between loses at most that one line, never repeats it.
        self.persist = persist or (lambda: None)
        self.present_at, self.presence_checked = None, 0
        self.hb_at = 0
        self.summary = {}
        self.usage, self.usage_at = {}, 0
        # rollout (lead 2026-11-10): the first run of F5c has no `stuck` in its state yet. Sessions busy past the
        # threshold at that moment are the baseline, not news: they are told only after a new sign of life and
        # another 30 min without one. A state from an F5c run (even an empty dict) has no baseline to take.
        self.stuck_baseline = not isinstance(st.get("stuck"), dict)

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

    def _today(self, now, key):
        day = time.strftime("%Y-%m-%d", time.localtime(now / 1000))
        return _i(self.s["day"].get(key)) if self.s["day"].get("d") == day else 0

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
        sessions = sessions or {}
        if now - self.usage_at >= USAGE_EVERY_MS:
            self.usage, self.usage_at = self._safe("usage", J.read_usage, self.env, now) or {}, now
        self._safe("incident", self._incidents, new_events or (), now, self_name)
        self._safe("blocks", self._blocks, sessions, now, tasks, mails)
        self._safe("sources", self._sources, now, tasks, mails, sessions)
        self._safe("sources-f5c", self._sources_f5c, now, tasks, mails, sessions)
        saver = self._safe("saver", self._saver, now, sessions, self_name)
        route = self.wake_route(now, self_name, bool(saver))
        self._safe("emit", self._emit, now, mode, sessions, tasks, mails, route is None)
        self._safe("round", self._round, now, mode, sessions, route is None)
        self._safe("wake", self._wake, now, mode, fold, self_name, time_left, sessions, new_events or (), route)
        self._safe("auto", self._auto_orphans, now)
        self.summary = {"mode": mode, "until": until, "pending": len(self.s["items"]) + sum(
            1 for b in self.s["blocks"].values() if not b.get("told")), "lastEmit": self.s["lastEmit"] or None,
            "today": {k: v for k, v in self.s["day"].items() if k != "d"}, "wakePending": 1 if self.s["wb"] else 0,
            "present": self.present_at is not None and now - self.present_at < PRESENT_MS,
            "wake": route or "on", "saver": self.s["saver"] or None}
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
            if r.get("sig") == sig and now - _i(r.get("at")) < REPEAT_MS and not (
                    kind == "limit" and self.mail.resume_relapse(name, since)):
                # blocked again on the same thing right after it was told: the same item. Never a limit that came
                # right after Jarvis's own "pokračuj" (rev-f5c N1): Lukáš must hear that it stands again
                told = True
            if now - since > BLOCK_STALE_MS:
                told = True  # old news when first seen (a daemon that was down for hours)
            if task_names is None:
                task_names = self.task_sessions(tasks, mails)
            self.s["blocks"][name] = {"kind": kind, "since": since, "sig": sig, "told": told,
                                      "task": name in task_names}
        for m in mails or ():
            name = m.get("to") or ""
            first = _i(m.get("firstBlockedAt"))
            if m.get("state") != "queued" or not first or name in seen or now - first < MAIL_STUCK_MS:
                continue
            seen.add(name)
            b = self.s["blocks"].get(name)
            if b and b.get("kind") == "mail" and b.get("since") == first:
                continue
            self.s["blocks"][name] = {"kind": "mail", "since": first, "sig": f"mail|{m.get('id')}", "told": False,
                                      "task": True}
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
                      name=rec.get("to"), at=e["at"], mail=rec.get("id"))
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
                    self._add(now, src="task", sub="proof", task=t["id"], name=t.get("to"), at=e["at"])
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
                self._add(now, src="task", sub=e["ev"], task=e["id"], name=t.get("to"), at=at)
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
    # ------------------------------------------------------------------ F5c sources: stuck, crashed, usage

    def _sources_f5c(self, now, tasks, mails, sessions):
        task_names = None
        stuck = {}
        for name, s in sessions.items():
            if not isinstance(s, dict) or s.get("self"):
                continue
            st = s.get("state")
            short = J.short_name(name)
            if st == "busy":
                # busy without any sign of life: no hook event, no registry change, no transcript write for 30 min
                since = max(_i((s.get("lastEvent") or {}).get("ms")), _i(s.get("stateSince")), _tr_ms(s.get("trKey")))
                if since and now - since >= STUCK_MS:
                    stuck[name] = since
                    if _i(self.s["stuck"].get(name)) != since and not self.stuck_baseline:
                        # `at` = when it was found (the expiry counts from there; `since` may be hours old)
                        self._add(now, src="stuck", sub="stuck", name=name, at=now, since=since,
                                  text=f"{short} pracuje {J.minutes_cz(now - since)} bez známky života")
            elif st in ("gone", "dead"):
                ch = _i(s.get("changedAt"))
                if not ch or now - ch > CRASH_FRESH_MS or _i(self.s["crashed"].get(name)) == ch:
                    continue
                if task_names is None:
                    task_names = self.task_sessions(tasks, mails)
                work = s.get("prevState") in ("busy", "bg", "waiting")
                if not (work or name in task_names):
                    continue
                self.s["crashed"][name] = ch
                if self._closed_on_purpose(name, ch):
                    continue  # Lukáš, Jarvis or the app closed it: no crash
                self._add(now, src="crashed", sub=st, name=name, at=ch,
                          text=f"{short} {'zmizela' if st == 'gone' else 'už nemá běžící claude'}"
                          + (" uprostřed práce" if work else " a měla rozdělaný úkol od Jarvise"))
        self.s["stuck"] = stuck
        self.stuck_baseline = False
        self.s["crashed"] = {k: v for k, v in self.s["crashed"].items() if now - _i(v) < 2 * 3600 * 1000}
        for acct, u in sorted((self.usage or {}).items()):
            if not isinstance(u, dict) or (u.get("ageS") or 0) > USAGE_FRESH_S:
                continue
            for win, cz in (("fiveHour", "za pět hodin"), ("sevenDay", "za týden")):
                v = u.get(win)
                if not isinstance(v, (int, float)):
                    continue
                lvl = max([x for x in USAGE_LEVELS if v >= x], default=0)
                k = f"{acct}|{win}"
                old = self.s["usageLvl"].get(k)
                self.s["usageLvl"][k] = lvl
                if old is not None and lvl > _i(old):  # the first sight is a baseline, not news
                    self._add(now, src="usage", sub=win, name=None, at=now, acct=acct,
                              text=f"Účet {J.account_short(u.get('account'))} je na {v:.0f} % {cz}")

    def _closed_on_purpose(self, name, ch):
        for c in J.read_closed(self.env, 100):
            if c.get("name") == name and ch - CRASH_CLOSED_MS <= _i(c.get("atMs")) <= ch + 60000:
                return True
        return False

    def _saver(self, now, sessions, self_name):
        """Jarvis's own account at ≥ 95 % a week or ≥ 85 % in 5 h: no wakes (templates only) until it drops."""
        s = (sessions.get(self_name) if self_name else None) or {}
        u = (self.usage or {}).get(s.get("account") or "default") or {}
        on = bool(u) and (u.get("ageS") or 0) <= 3 * 3600 and (
            (u.get("sevenDay") or 0) >= SAVER_7D or (u.get("fiveHour") or 0) >= SAVER_5H)
        if on and not self.s["saver"]:
            self.s["saver"] = now
            self.log("úsporný režim: Jarvisův účet je vyčerpaný, probouzení stojí, jdou šablony")
        elif not on:
            self.s["saver"] = 0
        return on

    def _add(self, now, **it):
        it.setdefault("name", None)
        # §3 point 1: a fired watch and a reminder are things Lukáš asked to hear (HNED), like a task's result; `info`
        # stays for news that is no reason to disturb him (the watch vibrates on block|done|incident)
        it["kind"] = TEMPLATE_KIND[it["src"]]
        key = (it["src"], it.get("sub"), it.get("task"), it.get("name"), it.get("at"), it.get("acct"))
        if any((i["src"], i.get("sub"), i.get("task"), i.get("name"), i.get("at"), i.get("acct")) == key
               for i in self.s["items"]):
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

    def _emit(self, now, mode, sessions, tasks, mails, wake_on=False):
        rules = self.rules.all(now)
        keep = []
        for it in self.s["items"]:
            if now - _i(it.get("at")) > EXPIRE_MS.get(it["src"], 3600 * 1000):
                continue  # stale: never told late
            if self.rules.silenced(it, now, sessions, rules):
                continue
            keep.append(it)
        self.s["items"] = keep
        # F4: each close on its own line at once (outside the 5-minute batch, which would replace the name with `-`
        # and so lose the app's "Vrátit"); a mode that holds `done` back holds these too (dnd). Never a wake: it is
        # Jarvis's own act, he knows it.
        closed = [it for it in self.s["items"] if it["src"] == "closed" and allowed_by_mode(it, mode)]
        if closed:
            ids = {id(it) for it in closed}
            self.s["items"] = [i for i in self.s["items"] if id(i) not in ids]
            for _ in closed:
                self._count(now, "alerts")
            self._persist()
            for it in closed:
                self._write(now, "done", it["text"], it["name"])
        # an incident never waits for the 5-minute batch (F3); the rest only when the batch is due
        gap_ok = now - self.s["lastEmit"] >= BATCH_GAP_MS
        due = []
        if gap_ok:
            for name, b in sorted(self.s["blocks"].items(), key=lambda kv: kv[1]["since"]):
                if b.get("told") or now - b["since"] < self.grace(b, now):
                    continue
                it = {"src": "block", "sub": b["kind"], "name": name, "kind": "block",
                      "text": block_sentence(name, b["kind"]), "since": b["since"],
                      "data": J.safe_text(((sessions.get(name) or {}).get("attention") or {}).get("text"), 120)}
                if self.rules.silenced(it, now, sessions, rules):
                    continue
                due.append((it, ("block", name)))
        for it in self.s["items"]:
            if it["src"] == "closed" or (not gap_ok and it["src"] != "incident"):
                continue
            if it["src"] == "task":
                text, hold = self._task_text(it, tasks, mails, sessions)
                if hold:
                    continue
            else:
                text = it.get("text") or ""
            e = {"src": it["src"], "sub": it.get("sub"), "name": it.get("name"), "kind": it["kind"], "text": text,
                 "orig": dict(it)}  # the item itself, so a fallback the mode holds back can put it back
            if it.get("task"):
                e["task"] = it["task"]
            due.append((e, ("item", id(it))))
        busy = self.s["wb"] is not None  # one wake at a time: what may wake waits for the next one
        to_wake, to_tpl = [], []
        for e, ref in due:
            if wake_on and wake_allowed(e, mode):
                if not busy:
                    to_wake.append((e, ref))
                elif e["src"] == "incident":
                    to_tpl.append((e, ref))  # an incident never waits behind a wake
            elif allowed_by_mode(e, mode):
                to_tpl.append((e, ref))
            # else: held back by the mode; told afterwards while it is still current (F3)
        chosen = to_wake + to_tpl
        if not chosen:
            return
        for e, ref in chosen:
            if ref[0] == "block":
                b = self.s["blocks"][ref[1]]
                b["told"] = True
                self.s["recent"][ref[1]] = {"sig": b["sig"], "at": now}
        done_ids = {ref[1] for e, ref in chosen if ref[0] == "item"}
        self.s["items"] = [i for i in self.s["items"] if id(i) not in done_ids]
        if any(e["src"] != "incident" for e, _ in chosen):
            self.s["lastEmit"] = now
        if to_wake:
            self._new_batch(now, [e for e, _ in to_wake], urgent=any(e["src"] == "incident" for e, _ in to_wake))
        self._template(now, [e for e, _ in to_tpl])

    def _template(self, now, entries):
        """Today's (F3) lines: every incident on its own, the rest in one batch line. Persisted before the write."""
        if not entries:
            return
        incidents = [e for e in entries if e["src"] == "incident"]
        rest = [e for e in entries if e["src"] != "incident"]
        if rest:
            self._count(now, "alerts")
        self._persist()
        for e in incidents:
            self._write(now, "incident", e["text"], None)
        if rest:
            kind = max((e["kind"] for e in rest), key=lambda k: SEVERITY.get(k, 0))
            names = [e.get("name") for e in rest]
            self._write(now, kind, batch_text([e["text"] for e in rest]), names[0] if len(names) == 1 else None)

    def _write(self, now, kind, text, name):
        if kind == "incident":
            self._count(now, "alerts")
        self.file.write(alert_line(new_id("a", now), kind, now, text, name))

    def _persist(self):
        try:
            self.persist()
        except Exception as e:  # noqa: BLE001  a full disk must not stop the alert itself
            self.log(f"alerts persist: {type(e).__name__}: {e}"[:300])

    # ------------------------------------------------------------------ F5c: the wake batch

    def wake_route(self, now, self_name, saver=False):
        """None when news may wake Jarvis at all, else why not; then the templates go out at once, as in F3."""
        if os.path.exists(self.env.path(WAKE_OFF)):
            return "wake-off"
        if not self_name:
            return "no-self"
        if self.mail.off():
            return "mail-off"
        if saver:
            return "saver"
        if self._today(now, "wakes") >= WAKE_DAY_MAX:
            return "day-cap"
        return None

    def _new_batch(self, now, entries, urgent=False):
        wid = new_id("w", now)
        for e in entries:
            if e.get("name"):
                e["short"] = J.short_name(e["name"])
        self.s["wb"] = {"id": wid, "at": now, "phase": "pending", "urgent": bool(urgent),
                        "blocking": any(blocking_item(e) for e in entries), "entries": entries}
        self._write_batch(self.s["wb"])

    def _write_batch(self, wb):
        """`<state>/wakes/<id>.json`, the data `cr-digest wake` shows; the old ones are pruned here."""
        d = self.env.path("wakes")
        os.makedirs(d, mode=0o700, exist_ok=True)
        J.atomic_write(os.path.join(d, wb["id"] + ".json"), json.dumps(
            {"v": 1, "id": wb["id"], "at": wb["at"], "blocking": wb["blocking"],
             "entries": [{k: v for k, v in e.items() if k != "orig"} for e in wb["entries"]]},
            ensure_ascii=False, separators=(",", ":")))
        try:
            listing = os.listdir(d)
        except OSError:
            return
        names = sorted(n for n in listing if n.endswith(".json") and self.M.WAKE_RE.fullmatch(n[:-5]))
        # rev-f5c N2: a wake's proposal claim goes with its batch (or after the same 2 days)
        for n in listing:
            if n.endswith(".claimed") and self.M.WAKE_RE.fullmatch(n[:-8]):
                p = os.path.join(d, n)
                try:
                    if n[:-8] + ".json" not in listing or wb["at"] - os.stat(p).st_mtime * 1000 > WAKES_KEEP_MS:
                        os.unlink(p)
                except OSError:
                    pass
        for i, n in enumerate(names):
            p = os.path.join(d, n)
            try:
                if i < len(names) - WAKES_KEEP_N or wb["at"] - os.stat(p).st_mtime * 1000 > WAKES_KEEP_MS:
                    os.unlink(p)
            except OSError:
                pass

    def wake_line(self, wb):
        """A FIXED line (rev-f3 LOW6, as mail's I13): the batch id, a count and a command, never any text of a
        session or of a task, so nothing typed by anyone reaches Jarvis's prompt through a wake."""
        n = sum(len(e.get("items") or ()) or 1 for e in wb["entries"])
        return (f"{WAKE_PREFIX}{wb['id']}] {n} {J.plural(n, 'událost', 'události', 'událostí')} · "
                f"cr-digest wake {wb['id']}")[:self.M.LINE_MAX]

    def wake_hold(self, now, self_name, sessions=None):
        """None, or why the wake may not go in NOW (it waits; after 3 min the templates go instead). The screen
        checks (idle, empty box, nobody typing …) come after, in the mail's probe."""
        if not self_name:
            return "no-self"
        if self.s["wakes"] and now - self.s["wakes"][-1] < WAKE_GAP_MS:
            return "gap"
        if self._today(now, "wakes") >= WAKE_DAY_MAX:
            return "day-cap"
        # never into Lukáš's conversation with Jarvis: his prompt (typed or spoken) or a client's input on Jarvis
        lp = 0
        for p in ("last-prompt", "last-prompt.prev", "last-prompt.held"):
            d = J.read_json(self.env.path(p))
            lp = max(lp, _i(d.get("at")) if isinstance(d, dict) else 0)
        if lp and now - lp < CONVERSATION_MS:
            return "conversation"
        rc, out = self.mail.tmux(["list-clients", "-t", "=" + self_name, "-F", "#{client_activity}"])
        if rc != 0:
            return "clients-unknown"
        if any(x.isdigit() and now // 1000 - int(x) < CONVERSATION_MS // 1000 for x in out.split()):
            return "conversation"
        if self.mail._pending():
            return "proposal"  # a wake would bury the question Lukáš is about to answer
        # rev-f5c M3: Jarvis's last answer is a question (no proposal behind it): Lukáš's "ano" belongs to it, and a
        # proposal made in a wake in between would take that "ano" for itself
        me = ((sessions or {}).get(self_name) or {})
        if me.get("endsWithQuestion") and now - _i(me.get("lastAssistantMs")) < self.M.PROMPT_FRESH_MS:
            return "question"
        return None

    def _wake(self, now, mode, fold, self_name, time_left, sessions, new_events, route):
        wb = self.s["wb"]
        if not wb:
            return
        if wb["phase"] == "pending":
            if route is not None:
                self._fallback(now, mode, wb, route)  # mail off, saver, the switch: the templates, now
                return
            deadline = wb["at"] + (0 if wb.get("urgent") else PASTE_WAIT_MS)
            why = self.wake_hold(now, self_name, sessions) or (None if time_left() >= MIN_TIME_LEFT_S else "time")
            t = None
            if not why:
                t = self.mail.probe(self_name, fold, self.mail.env.now_ms())
                why = t if isinstance(t, str) else None
            if why:
                if now >= deadline:
                    self._fallback(now, mode, wb, "hold:" + why)
                return
            line = self.wake_line(wb)
            if self.M.bad_chars(line) is not None:
                self._fallback(now, mode, wb, "line")
                return
            wb.update(phase="pasting", pasteAt=now)
            self.s["wakes"] = [x for x in self.s["wakes"] if now - x < 86400 * 1000] + [now]
            self._count(now, "wakes")
            self._persist()  # LOW5: counted before the paste, so a crash inside it never pastes a second wake
            res = self.paste(t, line)
            with self.mail.lock():
                self.mail.log("wake", to=self_name, result=res, wake=wb["id"], n=len(wb["entries"]))
            if res in ("load-buffer", "paste-failed"):
                self._fallback(now, mode, wb, res)
                return
            wb.update(phase="pasted", paste=res, line=line)
            if res == "delivered":
                wb["enterAt"] = self.env.now_ms()  # rev-f5c N4: his 3 minutes run from the Enter that took
            return
        # pasted (or a crash while pasting): his answer to exactly this turn, or the templates after 3 min
        for e in new_events:
            if e.name == self_name and e.ev == "stop" and e.ms >= _i(wb.get("pasteAt")):
                wb["stopMs"] = e.ms
        s = (sessions.get(self_name) if self_name else None) or {}
        if str(wb.get("paste") or "").startswith("uncertain") and time_left() >= MIN_TIME_LEFT_S:
            self._recover_enter(wb, self_name)
        # rev-f5c H1: the turn is over (his Stop, or the registry idle since after the paste) for 1.5 s, and the
        # transcript's last entry after our line is a FINAL text (stop_reason end_turn, nothing after it); a text in
        # the middle of the turn ("Podívám se.") is never his answer
        ended = _i(wb.get("stopMs")) or (_i(s.get("stateSince")) if s.get("state") == "idle"
                                         and _i(s.get("stateSince")) > _i(wb.get("pasteAt")) else 0)
        if ended and now - ended >= REPLY_SETTLE_MS:
            # rev-f5c N3: our line stuck in his box and Lukáš typed after it: that turn answered LUKÁŠ (he reads it
            # in the session), never a j-line; the batch goes out as its templates
            mp = self.M.marker_prompt(s.get("transcript"), "wake " + wb["id"])
            if mp is not None and wb.get("line") and mp.strip() != wb["line"]:
                self._fallback(now, mode, wb, "lukas-took-over")
                return
            reply = self.M.find_final_reply(s.get("transcript"), "wake " + wb["id"])
            if reply is not None:
                self._relay(now, mode, wb, reply)
                return
        if now - (_i(wb.get("enterAt")) or _i(wb.get("pasteAt"))) >= REPLY_WAIT_MS:
            self._fallback(now, mode, wb, "no-reply")

    def _recover_enter(self, wb, self_name):
        """rev-f5c L6: a wake whose Enter got lost leaves its line in Jarvis's box: the next wake would wait on the
        "draft" for ever, and Lukáš's next words would start with `[jarvis`. One Enter per tick, at most
        RECOVER_ENTERS, only while every Enter condition holds and the box holds EXACTLY our line (I3)."""
        if _i(wb.get("recovered")) >= RECOVER_ENTERS or not wb.get("line"):
            return
        t = self.mail.resolve_target(self_name)
        if isinstance(t, str) or self.mail.ready_for_enter(t, wb["line"]) is not None:
            return
        wb["recovered"] = _i(wb.get("recovered")) + 1
        self._persist()  # counted before the key, as every Enter
        since = self.env.now_ms()
        self.mail.tmux(["send-keys", "-t", t.pane, "Enter"])
        ok = self.mail.wait_confirm(t, since)
        if ok:
            wb["paste"] = "delivered:recovered"
            wb["enterAt"] = self.env.now_ms()
        with self.mail.lock():
            self.mail.log("wake-enter", wake=wb["id"], result="delivered" if ok else "no-confirm")

    def _relay(self, now, mode, wb, reply):
        """His last text of the wake turn -> one j-line. `[ticho]` -> nothing, except permissions and questions and
        the steps he took by himself, which are told anyway (as templates)."""
        self.s["wb"] = None
        auto = self.auto_entries(max(_i(wb.get("pasteAt")), self.s["autoAt"]), now)
        if SILENT_RE.match(reply or ""):
            self.s["autoAt"] = now
            must = [e for e in wb["entries"] if always_told(e)] + auto
            with self.mail.lock():
                self.mail.log("wake-said", wake=wb["id"], kind="silent", told=len(must))
            self._template(now, must)
            if not must:
                self._persist()
            return
        text = jarvis_text(reply)
        if not text:
            self.s["wb"] = wb
            self._fallback(now, mode, wb, "unreadable")
            return
        ask, block = self._pending_one(), bool(wb.get("blocking"))
        kind = "jblockask" if ask and block else "jask" if ask else "jblock" if block else "jsay"
        self.s["autoAt"] = now  # his own words cover the steps of his turn
        names = {e.get("name") for e in wb["entries"]}
        one = next(iter(names)) if len(names) == 1 else None
        self._count(now, "alerts")
        self._count(now, "said")
        self._persist()
        self._write(now, kind, text, one)
        with self.mail.lock():
            self.mail.log("wake-said", wake=wb["id"], kind=kind)

    def _pending_one(self):
        try:
            return self.mail._pending() == 1
        except Exception:  # noqa: BLE001
            return False

    def _fallback(self, now, mode, wb, why):
        """Today's template lines of the batch (a round has none), plus every step he took by himself. What the mode
        of THIS moment holds back (Lukáš switched to dnd while the batch waited) is never dropped: it goes back to
        the pending items and blocks and is told later, as F3 holds things in a mode; perm/ask and incidents go out
        in every mode."""
        self.s["wb"] = None
        entries, held = [], 0
        for e in wb["entries"]:
            if e["src"] == "round":
                continue
            if allowed_by_mode(e, mode) or always_told(e):
                entries.append(e)
            else:
                held += self._requeue(e)
        if wb.get("pasteAt"):
            entries += self.auto_entries(max(_i(wb["pasteAt"]), self.s["autoAt"]), now)
            self.s["autoAt"] = now
        self.log(f"wake {wb['id']}: šablona místo Jarvise ({why})")
        with self.mail.lock():
            self.mail.log("wake-fallback", wake=wb["id"], why=str(why)[:60], told=len(entries), held=held)
        self._template(now, entries)
        if not entries:
            self._persist()

    def _requeue(self, e):
        """Put one entry of a batch back to wait (1), or nothing when it is no longer current (0): a block only while
        the session is still blocked on the same thing, an item with its own expiry (`at` kept)."""
        if e["src"] == "block":
            b = self.s["blocks"].get(e.get("name") or "")
            if b and b.get("kind") == e.get("sub") and _i(b.get("since")) == _i(e.get("since")):
                b["told"] = False
                self.s["recent"].pop(e["name"], None)
                return 1
            return 0
        it = e.get("orig")
        if isinstance(it, dict) and it.get("src"):
            self.s["items"].append(dict(it))
            return 1
        return 0

    def _auto_orphans(self, now):
        """rev-f5c M5: a step Jarvis took that no answer of his covered (after a fallback his wake turn may go on)
        goes out as a template. A step in Lukáš's own turn needs nothing: he reads that answer himself. `autoAt` is
        the cursor: everything up to it is told."""
        if self.s["wb"] is not None:
            return
        if not self.s["autoAt"]:
            self.s["autoAt"] = now
            return
        steps = self.auto_entries(self.s["autoAt"], now)
        if not steps:
            return
        lukas = 0
        for p in ("last-prompt", "last-prompt.prev", "last-prompt.held"):
            d = J.read_json(self.env.path(p))
            lukas = max(lukas, _i(d.get("at")) if isinstance(d, dict) else 0)
        last_wake = max(self.s["wakes"]) if self.s["wakes"] else 0
        self.s["autoAt"] = now
        if last_wake > lukas:
            self._template(now, steps)
        else:
            self._persist()

    def auto_entries(self, since, until):
        """The steps Jarvis took by himself in (since, until], as template entries: each is told to Lukáš even when
        Jarvis said nothing about it (Lukáš: "každý takový krok hned oznámí")."""
        out = []
        try:
            for e in self.mail.auto_steps(since, until):
                short = J.short_name(e.get("to") or "") if e.get("to") else "session"
                text = (f"Poslal jsem {short} pokyn pokračovat po obnově limitu" if e.get("kind") == "resume"
                        else f"Popohnal jsem {short} k dokončení úkolu")
                out.append({"src": "auto", "sub": e.get("kind"), "name": e.get("to"), "kind": "done", "text": text})
            import jarviswatch as JW
            ws = JW.Watches(self.env)
            for e in ws.events():
                if e.get("ev") == "add" and e.get("by") == self.M.AUTO_BY and since < e["at"] <= until:
                    out.append({"src": "auto", "sub": "watch", "name": e.get("target"), "kind": "done",
                                "text": "Založil jsem hlídku: " + JW.describe(dict(e, state="open"))})
            for e in self.reminders.lines():
                if e.get("ev") == "add" and e.get("by") == self.M.AUTO_BY and since < _i(e.get("at")) <= until:
                    out.append({"src": "auto", "sub": "reminder", "name": None, "kind": "done",
                                "text": f"Nastavil jsem připomínku na {when_cz(_i(e.get('due')), until)}"})
        except Exception as e:  # noqa: BLE001
            self.log(f"alerts auto: {type(e).__name__}: {e}"[:300])
        return out

    def _round(self, now, mode, sessions, wake_on):
        """F5c: every 30 min a round, only when something Jarvis has not seen yet (since his delta cursor) changed
        since the last round. Normal mode only; never a template (it is not alert-worthy on its own)."""
        if not wake_on or mode != "normal" or self.s["wb"] is not None:
            return
        if not self.s["roundAt"]:
            self.s["roundAt"] = now  # the first run starts the clock
            return
        if now - self.s["roundAt"] < ROUND_MS:
            return
        since, self.s["roundAt"] = self.s["roundAt"], now
        import cr_digest as CD
        gen = _i((CD.read_cursor(self.env) or {}).get("gen"))
        items = []
        for name, s in sorted(sessions.items()):
            if not isinstance(s, dict) or s.get("self"):
                continue
            if _i(s.get("changedGen")) <= gen or _i(s.get("changedAt")) <= since:
                continue
            k = CD.classify(s)
            if k in ("busy", "cleared", "idle"):
                continue
            items.append({"name": name, "short": J.short_name(name), "kind": k})
        if not items:
            return
        n = len(items)
        self._new_batch(now, [{"src": "round", "sub": "round", "name": None, "kind": "info",
                               "text": f"Obchůzka: {n} {J.plural(n, 'změna', 'změny', 'změn')} od minula",
                               "items": items[:20]}])
        self._persist()

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


def _tr_ms(key):
    """The transcript's mtime in ms from jarvislib's cache key `size:mtime_ns` (0 when unknown)."""
    try:
        return int(str(key).split(":", 1)[1]) // 1000000
    except (IndexError, ValueError):
        return 0
