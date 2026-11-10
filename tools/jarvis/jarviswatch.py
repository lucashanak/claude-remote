"""Jarvis F2b: "dej vědět, až…" (watches), verified "done", the checkout-conflict warning and the audit.

Shared by `cr_task.py` (watch/unwatch/watches/log, the evidence behind `done`), `cr_mail.py` (the conflict warning and
the baseline commit at `send`, `audit`), `jarvis_d.py` (WatchWorker: the only caller of `gh`) and `cr_digest.py`
("Splněno: …" on top of the delta and the brief). Design: `JARVIS-navrh.md` §2 C and G, `10-funkce.md` C1/C4/C7/E1/F1.

Files (under <state> = ~/.claude-remote/jarvis, mode 600):
  watches.jsonl     events, appended under watches.lock: add / cancelled (cr-task), fired / expired / proof (jarvis-d).
                    A watch's state is the fold of its events, as in tasks.jsonl. `proof` = a task got its evidence.
  watch-state.json  jarvis-d only (tmp + rename): when each watch was last checked (`gh` at most once per 2 min per
                    watch), the CI cache {repo@sha: result} that cr-task reads instead of calling `gh` itself, and the
                    last evidence per task. status.json carries a summary of it for F3.

Nothing here writes into a session, into git or into GitHub: git runs with GIT_OPTIONAL_LOCKS=0 and only reads
(rev-parse, symbolic-ref, log, status), `gh run list` only lists. Both under `timeout`, output to a file, never a pipe.
"""
import fcntl
import json
import os
import re
import subprocess
import tempfile
import time

import jarvislib as J

WATCH_RE = re.compile(r"w-\d{8}T\d{6}-[0-9a-f]{4}")
KINDS = ("stop", "waiting", "ci", "usage")
# A ref goes to git and gh as an ARGUMENT: it may never start with '-' (an option) and holds no shell or revision
# syntax (`..`, `@{`, `^`, `~`, `:`), so it names a branch or a commit and nothing else.
REF_RE = re.compile(r"(?!.*\.\.)[A-Za-z0-9][A-Za-z0-9._/-]{0,99}")
SHA_RE = re.compile(r"[0-9a-f]{7,40}")
FULL_SHA_RE = re.compile(r"[0-9a-f]{40}")

WATCH_EXPIRE_MS = 24 * 3600 * 1000
CI_EVERY_MS = 2 * 60 * 1000      # the brief: `gh` at most once per 2 min per watch (and per task's commit)
GIT_EVERY_MS = 2 * 60 * 1000     # the daemon re-reads a task's repo at most this often
GH_TIMEOUT_S = 15
GIT_TIMEOUT_S = 5
GH_PER_TICK = 3                  # a tick never spends more than ~45 s on GitHub
MIN_TIME_LEFT_S = 25             # same rule as the mail: never start what the cron run cannot finish
USAGE_STALE_S = 900
MAX_OPEN = 30
FIRED_SHOW_MS = 3600 * 1000      # status.json / brief: watches that fired in the last hour
GREEN = ("success", "skipped", "neutral")
WAITING_KINDS = ("perm", "ask", "waiting")
WAIT_CZ = {"perm": "chce povolení", "ask": "se ptá", "waiting": "čeká na dialog"}


# ---------------------------------------------------------------------------------------------------------- processes

def run(argv, timeout_s, cwd=None, env_extra=None):
    """(rc, stdout) of one read-only command under `timeout -k 1`, stdout to a FILE (a killed child can keep a pipe
    open and the reader would hang past the timeout; the same rule as run_tmux)."""
    env = dict(os.environ, GIT_OPTIONAL_LOCKS="0", GIT_TERMINAL_PROMPT="0", GH_PROMPT_DISABLED="1",
               GH_NO_UPDATE_NOTIFIER="1", NO_COLOR="1", **(env_extra or {}))
    try:
        with tempfile.TemporaryFile() as out:
            rc = subprocess.run(["timeout", "-k", "1", str(timeout_s), *argv], cwd=cwd, env=env,
                                stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.DEVNULL,
                                timeout=timeout_s + 3).returncode
            out.seek(0)
            return rc, out.read(1024 * 1024).decode("utf-8", "replace")
    except (OSError, subprocess.SubprocessError):
        return 125, ""


class Repo:
    """Read-only git, and `gh` only when `gh_allowed` (jarvis-d). cr-task and cr-mail run inside Jarvis's turn and
    read the CI result jarvis-d cached instead (the brief: only jarvis-d calls gh, Jarvis's gate never lets it)."""

    def __init__(self, env, gh_allowed=False, gh=None, ci_cache=None):
        self.env = env
        self.git_bin = env.environ.get("JARVIS_GIT", "git")
        self.gh_bin = env.environ.get("JARVIS_GH", "gh")
        self.gh_allowed = gh_allowed
        self._gh = gh
        self.ci_cache = ci_cache if ci_cache is not None else {}

    def git(self, cwd, *args):
        if not cwd or not os.path.isdir(cwd):
            return None
        rc, out = run([self.git_bin, "--no-optional-locks", "-C", cwd, *args], GIT_TIMEOUT_S)
        return out.strip() if rc == 0 else None

    def gh(self, cwd, *args):
        if not self.gh_allowed:
            raise PermissionError("gh is jarvis-d's only")
        if self._gh is not None:
            return self._gh(cwd, list(args))
        rc, out = run([self.gh_bin, *args], GH_TIMEOUT_S, cwd=cwd)
        return out if rc == 0 else None

    def top(self, cwd):
        return self.git(cwd, "rev-parse", "--show-toplevel")

    def state(self, cwd):
        """{cwd, top, head, branch, upstream} or {cwd, top: None} outside git."""
        top = self.top(cwd)
        if not top:
            return {"cwd": cwd, "top": None}
        return {"cwd": cwd, "top": top, "head": self.git(top, "rev-parse", "--verify", "-q", "HEAD"),
                "branch": self.git(top, "symbolic-ref", "-q", "--short", "HEAD"),
                "upstream": self.git(top, "rev-parse", "--verify", "-q", "@{upstream}")}

    @staticmethod
    def has_ci(top):
        d = os.path.join(top or "", ".github", "workflows")
        try:
            return any(n.endswith((".yml", ".yaml")) for n in os.listdir(d))
        except OSError:
            return False

    def resolve(self, top, ref, kind):
        """The full sha CI is judged on: a commit as given, a branch as it was last pushed (its upstream, else
        refs/remotes/origin/<ref>, else the local branch)."""
        if kind == "sha":
            return self.git(top, "rev-parse", "--verify", "-q", ref + "^{commit}")
        for r in (f"refs/heads/{ref}@{{upstream}}", f"refs/remotes/origin/{ref}", f"refs/heads/{ref}"):
            sha = self.git(top, "rev-parse", "--verify", "-q", r)
            if sha:
                return sha
        return None

    def ci_status(self, top, sha):
        """('green'|'red'|'pending'|'none'|'unknown', failed workflow names) for every run of that commit."""
        if not sha or not FULL_SHA_RE.fullmatch(sha):
            return "unknown", []
        out = self.gh(top, "run", "list", "--commit", sha, "--limit", "30",
                      "--json", "databaseId,status,conclusion,workflowName,headSha")
        try:
            runs = json.loads(out) if out else None
        except ValueError:
            runs = None
        if not isinstance(runs, list):
            return "unknown", []
        runs = [r for r in runs if isinstance(r, dict) and r.get("headSha") in (None, sha)]
        if not runs:
            return "none", []
        if any(r.get("status") != "completed" for r in runs):
            return "pending", []
        bad = sorted({str(r.get("workflowName") or "?") for r in runs if r.get("conclusion") not in GREEN})
        return ("red", bad) if bad else ("green", [])

    def ci_cached(self, top, sha):
        c = self.ci_cache.get(f"{top}@{sha}") or {}
        return c.get("res") or "unknown", c.get("failed") or []


# ---------------------------------------------------------------------------------------------------------- sessions

def sessions_now(env, now):
    """jarvis-d's picture when it is fresh (≤ 120 s), else a direct snapshot. Never moves any cursor."""
    st = J.read_json(env.path("status.json"))
    if isinstance(st, dict) and isinstance(st.get("sessions"), dict) and now - J._int(st.get("d_heartbeat") or 0) <= 120000:
        return st["sessions"]
    return J.snapshot(env, now_ms=now, ancestor_self=False)["sessions"]


def target_cwd(env, name):
    """The cwd of `name`'s live claude (registry), the manifest folder when none runs."""
    for r in sorted(J.read_registry(env), key=lambda r: (r.started_at or 0, r.pid)):
        if r.alive and r.tmux == name and r.cwd:
            return r.cwd
    m = J.read_manifest(env).get(name) or {}
    return m.get("folder") if isinstance(m.get("folder"), str) else None


# ---------------------------------------------------------------------------------------------------------- conflicts

def conflicts(env, target, repo, me=None):
    """E1: other live sessions in the SAME checkout as the target (one checkout = one branch; git never lets two
    worktrees hold the same branch), when one of them is busy or the checkout has uncommitted changes. Information
    for Lukáš's "Ano?", never a block. None when there is nothing to say."""
    cwd = target_cwd(env, target)
    top = repo.top(cwd) if cwd else None
    if not top:
        return None
    others, tops = {}, {}
    for r in J.read_registry(env):
        if not (r.alive and r.tmux and r.cwd) or r.tmux in (target, me):
            continue
        if not (r.cwd == top or r.cwd.startswith(top.rstrip("/") + "/")):
            continue
        if r.cwd not in tops:
            tops[r.cwd] = repo.top(r.cwd)  # a worktree nested inside the checkout is another checkout
        if tops[r.cwd] != top:
            continue
        busy = r.status == "busy" or others.get(r.tmux) == "busy"
        others[r.tmux] = "busy" if busy else (r.status or "unknown")
    if not others:
        return None
    dirty = bool(repo.git(top, "status", "--porcelain", "--untracked-files=no"))
    busy = sorted(n for n, s in others.items() if s == "busy")
    if not busy and not dirty:
        return None
    return {"top": top, "folder": os.path.basename(top.rstrip("/")) or top,
            "branch": repo.git(top, "symbolic-ref", "-q", "--short", "HEAD") or "odpojený HEAD",
            "busy": busy, "idle": sorted(n for n in others if n not in busy), "dirty": dirty}


def conflict_text(c):
    parts = []
    if c["busy"]:
        parts.append("pracují v něm " + ", ".join(J.short_name(n) for n in c["busy"]))
    if c["idle"]:
        parts.append("nečinné " + ", ".join(J.short_name(n) for n in c["idle"][:6])
                     + (f" a další {len(c['idle']) - 6}" if len(c["idle"]) > 6 else ""))
    if c["dirty"]:
        parts.append("má necommitnuté změny")
    return f"checkout {c['top']} (větev {J.safe_name(c['branch'], 60)}): " + "; ".join(parts)


def conflict_say(c):
    """For the "Ano?" question and TTS: no path, no id."""
    n = len(c["busy"]) or len(c["idle"])
    who = (f"{'pracují' if 2 <= n <= 4 else 'pracuje'} další {J.num_f(n)} "
           f"{J.plural(n, 'session', 'sessions', 'sessions')}" if c["busy"] else
           f"{'jsou' if 2 <= n <= 4 else 'je'} další {J.num_f(n)} {J.plural(n, 'session', 'sessions', 'sessions')}")
    s = f"V {J.spoken_name(J.safe_name(c['folder']))} {who} na větvi {J.spoken_name(J.safe_name(c['branch'], 60))}"
    if c["dirty"]:
        s += " a jsou tam necommitnuté změny"
    return s + ". Pošlu i tak?"


# ---------------------------------------------------------------------------------------------------------- evidence

def evidence(base, repo):
    """F1 "ověřené hotovo": a new commit in the target's repo since the mail was sent, pushed (@{u} = HEAD), and green
    CI when the repo has CI. {missing: [...], head, branch, ci} — `missing` empty = the evidence is there."""
    if not isinstance(base, dict) or not base.get("top"):
        return {"missing": ["repo cíle (složka není v gitu)"], "head": None, "branch": None, "ci": None, "failed": []}
    top = base["top"]
    st = repo.state(top)
    head, up = st.get("head"), st.get("upstream")
    out = {"missing": [], "head": head, "branch": st.get("branch"), "ci": None, "failed": []}
    if not st.get("top") or not head:
        out["missing"].append("repo cíle nejde přečíst")
        return out
    new = head != base.get("head")
    if new and base.get("at"):
        # a branch switched back to an older commit is not new work: the commit must be younger than the mail
        ct = J._int(repo.git(top, "log", "-1", "--format=%ct", head))
        new = ct is not None and ct * 1000 >= J._int(base["at"]) - 120000
    if not new:
        out["missing"].append("nový commit")
    pushed = bool(up) and up == head
    if not up:
        out["missing"].append("push (větev nemá upstream)")
    elif not pushed:
        out["missing"].append("push")
    if repo.has_ci(top):
        if new and pushed:
            res, failed = repo.ci_cached(top, head) if not repo.gh_allowed else repo.ci_status(top, head)
            out["ci"], out["failed"] = res, failed
            if res != "green":
                out["missing"].append({"pending": "CI ještě běží", "red": "zelené CI (je červené"
                                       + (f": {', '.join(J.safe_name(f, 40) for f in failed[:3])}" if failed else "")
                                       + ")", "none": "CI (zatím žádný běh)"}.get(res, "výsledek CI (zatím neznámý)"))
        else:
            out["missing"].append("zelené CI")
    return out


def task_status(t, mails, sessions, repo):
    """(label, detail) of one task for cr-task and the digest: zadáno → běží → čeká na tebe → ke kontrole (čeká na
    důkaz | důkaz je) → hotovo/selhalo. C7: a session whose last Stop had background tasks is still running."""
    if t["state"] == "done":
        return "hotovo", None
    if t["state"] == "failed":
        return "selhalo", None
    ms = sorted((m for m in mails if m.get("task") == t["id"]), key=lambda m: J._int(m.get("created")) or 0)
    live = [m for m in ms if m["state"] not in ("dropped", "cancelled", "expired")]
    if not live:
        return "zadáno", ("pošta k němu nedošla (" + ms[-1]["state"] + ")") if ms else None
    last = live[-1]
    if last["state"] in ("proposed", "queued", "delivering"):
        return "zadáno", {"proposed": "čeká na Lukášovo ano", "queued": "ve frontě",
                          "delivering": "doručuje se"}[last["state"]]
    if last["state"] == "uncertain":
        return "zadáno", "doručení nejisté"
    s = (sessions or {}).get(last["to"]) or {}
    kind = (s.get("attention") or {}).get("kind")
    if kind in WAITING_KINDS:
        return "čeká na tebe", f"{J.short_name(last['to'])} {WAIT_CZ[kind]}"
    if s.get("state") == "busy" or s.get("stopBg"):
        return "běží", "na pozadí ještě běží úloha" if s.get("stopBg") and s.get("state") != "busy" else None
    if last["state"] == "delivered":
        return "běží", None
    # replied
    if not t.get("ac"):
        return "ke kontrole", "bez kritérií, ověř podle odpovědi"
    ev = evidence(last.get("base"), repo)
    if ev["missing"]:
        return "ke kontrole", "čeká na důkaz: chybí " + ", ".join(ev["missing"])
    return "ke kontrole", f"důkaz je: commit {ev['head'][:8]} na {J.safe_name(ev['branch'] or '?', 60)}, push" + (
        ", CI zelené" if ev["ci"] == "green" else ", repo nemá CI")


def proof_base(t, mails):
    live = [m for m in mails if m.get("task") == t["id"] and m["state"] in ("delivered", "replied")]
    live.sort(key=lambda m: J._int(m.get("created")) or 0)
    return live[-1] if live else None


# ---------------------------------------------------------------------------------------------------------- the store

class Watches:
    def __init__(self, env):
        self.env = env
        self.path = env.path("watches.jsonl")

    def events(self):
        out = []
        for line in J.read_tail(self.path, 4 * 1024 * 1024):
            try:
                d = json.loads(line)
            except ValueError:
                continue
            if isinstance(d, dict) and isinstance(d.get("at"), int) and (
                    WATCH_RE.fullmatch(str(d.get("id"))) or d.get("ev") == "proof"):
                out.append(d)
        return out

    @staticmethod
    def fold(events):
        ws = {}
        for e in events:
            wid, ev = e.get("id"), e.get("ev")
            if ev == "add" and wid not in ws and e.get("kind") in KINDS:
                ws[wid] = dict(e, state="open", created=e["at"], closedAt=None, result=None)
            elif ev in ("fired", "expired", "cancelled") and wid in ws and ws[wid]["state"] == "open":
                ws[wid].update(state=ev, closedAt=e["at"], result=e.get("result"), detail=e.get("detail"))
        return ws

    def all(self):
        return self.fold(self.events())

    def append(self, ev, guard=None):
        """Append one event under watches.lock. `guard(fold)` -> reason or None runs under the lock, so a watch is
        never fired twice nor fired after its cancel."""
        os.makedirs(self.env.state_dir, mode=0o700, exist_ok=True)
        with open(self.env.path("watches.lock"), "a+") as lk:
            fcntl.flock(lk.fileno(), fcntl.LOCK_EX)
            try:
                if guard:
                    why = guard(self.fold(self.events()))
                    if why:
                        return why
                fd = os.open(self.path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
                try:
                    os.write(fd, (json.dumps(ev, ensure_ascii=False, separators=(",", ":")) + "\n").encode())
                    os.fsync(fd)
                finally:
                    os.close(fd)
            finally:
                fcntl.flock(lk.fileno(), fcntl.LOCK_UN)
        return None

    def close(self, wid, ev, at, **kw):
        def guard(ws):
            w = ws.get(wid)
            if not w:
                return f"hlídka {wid} neexistuje"
            if w["state"] != "open":
                return f"hlídka {wid} už je {STATE_CZ.get(w['state'], w['state'])}"
            return None
        return self.append({"v": 1, "at": at, "ev": ev, "id": wid, **kw}, guard)


STATE_CZ = {"open": "aktivní", "fired": "splněná", "expired": "vypršelá", "cancelled": "zrušená"}


# ---------------------------------------------------------------------------------------------------------- words

def describe(w, say=False):
    k = w["kind"]
    if k in ("stop", "waiting"):
        who = J.spoken_name(J.short_name(w["target"])) if say else J.short_name(w["target"])
        return f"{who} doběhne" if k == "stop" else f"{who} bude čekat na Lukáše"
    if k == "ci":
        what = ("commit" if say else f"commit {w['ref'][:8]}") if w.get("refKind") == "sha" else \
            f"větev {J.spoken_name(w['ref']) if say else w['ref']}"
        where = J.spoken_name(J.safe_name(os.path.basename(w.get("repo") or "")))
        return f"CI pro {what} v {where}"
    win = "týden" if w.get("window") == "sevenDay" else "pět hodin"
    acct = J.spoken_name(w.get("acctName") or "?") if say else (w.get("acctName") or "?")
    return f"limit {acct} ({win}) klesne pod {w['below']} {'procent' if say else '%'}"


def fired_text(w, say=False):
    k, res, det = w["kind"], w.get("result"), w.get("detail") or {}
    if k == "stop":
        who = J.spoken_name(J.short_name(w["target"])) if say else J.short_name(w["target"])
        return f"{who} doběhla" if res == "done" else f"{who} skončila bez doběhnutí ({'zmizela' if say else res})"
    if k == "waiting":
        who = J.spoken_name(J.short_name(w["target"])) if say else J.short_name(w["target"])
        return f"{who} {WAIT_CZ.get(res, 'čeká na tebe')}"
    if k == "ci":
        what = describe(w, say)
        if res == "green":
            return f"{what} je zelené"
        failed = det.get("failed") or []
        return f"{what} je červené" + (f" ({', '.join(J.safe_name(f, 40) for f in failed[:3])})" if failed and not say else "")
    v = det.get("value")
    acct = J.spoken_name(w.get("acctName") or "?") if say else (w.get("acctName") or "?")
    return f"limit {acct} klesl pod {w['below']} {'procent' if say else '%'}" + (
        f" (teď {v:.0f} %)" if isinstance(v, (int, float)) and not say else "")


def notices(env, since_ms):
    """Watch news for the digest since `since_ms`: fired, expired, proof. (items, max_at)."""
    W = Watches(env)
    evs = W.events()
    ws = W.fold(evs)
    items, max_at = [], since_ms
    for e in evs:
        if e["at"] <= since_ms or e.get("ev") not in ("fired", "expired", "proof"):
            continue
        max_at = max(max_at, e["at"])
        if e["ev"] == "proof":
            items.append({"kind": "proof", "task": e.get("task"), "title": J.safe_text(e.get("title"), 80),
                          "head": e.get("head"), "branch": e.get("branch"), "ci": e.get("ci"), "at": e["at"]})
            continue
        w = ws.get(e["id"])
        if not w:
            continue
        w = dict(w, result=e.get("result"), detail=e.get("detail"))
        items.append({"kind": "watch-" + e["ev"], "id": w["id"], "task": w.get("task"), "text": fired_text(w)
                      if e["ev"] == "fired" else describe(w), "say": fired_text(w, True) if e["ev"] == "fired"
                      else describe(w, True), "at": e["at"]})
    return items, max_at


def notice_line(it):
    if it["kind"] == "proof":
        return (f"✓ Úkol {it['task']} «{it['title']}» má důkaz: commit {(it.get('head') or '?')[:8]}, push"
                f"{', CI zelené' if it.get('ci') == 'green' else ''}; uzavři ho: cr-task done {it['task']}")
    if it["kind"] == "watch-fired":
        return f"✓ Splněno: {it['text']} ({it['id']})"
    return f"· Hlídka vypršela bez splnění: {it['text']} ({it['id']})"


def notice_say(it):
    if it["kind"] == "proof":
        return f"Úkol {J.spoken_name(it['title'] or 'bez názvu')} má důkaz, můžu ho uzavřít."
    if it["kind"] == "watch-fired":
        return f"Splněno: {it['say']}."
    return f"Hlídka vypršela: {it['say']}."


# ---------------------------------------------------------------------------------------------------------- daemon

class WatchWorker:
    """jarvis-d's half: checks open watches each tick, fires them, expires them after 24 h, and appends `proof` when
    a task delegated with acceptance criteria gets its evidence. The only place that calls `gh`."""

    def __init__(self, env, repo=None, log=None):
        self.env = env
        self.store = Watches(env)
        self.state_path = env.path("watch-state.json")
        self.repo = repo or Repo(env, gh_allowed=True)
        self.log = log or (lambda msg: None)
        self.summary = {"open": [], "fired": [], "evidence": {}}

    def _safe(self, what, fn, *a):
        try:
            return fn(*a)
        except Exception as e:  # noqa: BLE001  one bad watch must not stop the others
            self.log(f"watch {what}: {type(e).__name__}: {e}"[:300])
            return None

    def tick(self, new_events, sessions, fold, usage, now, time_left=lambda: 60.0):
        raw = J.read_json(self.state_path, {}) or {}
        st = raw if isinstance(raw, dict) else {}
        before = json.dumps(st, sort_keys=True)  # the dicts below are mutated in place: compare text, not objects
        st = {"checks": st.get("checks") if isinstance(st.get("checks"), dict) else {},
              "ci": st.get("ci") if isinstance(st.get("ci"), dict) else {},
              "evidence": st.get("evidence") if isinstance(st.get("evidence"), dict) else {}}
        self.repo.ci_cache = st["ci"]
        budget = [GH_PER_TICK]
        ws = self.store.all()
        for w in [w for w in ws.values() if w["state"] == "open"]:
            self._safe(w["id"], self._one, w, new_events, sessions, fold, usage, now, st, time_left, budget)
        self._safe("evidence", self._evidence, sessions, now, st, time_left, budget)
        live = set(ws)
        st["checks"] = {k: v for k, v in st["checks"].items() if k in live}
        st["ci"] = {k: v for k, v in st["ci"].items() if now - J._int(v.get("at") or 0) < 7 * 86400 * 1000}
        if json.dumps(st, sort_keys=True) != before and (raw or any(st.values())):
            # written only when it changed: no churn on a box without watches and tasks
            J.atomic_write(self.state_path, json.dumps(st, ensure_ascii=False, separators=(",", ":")))
        ws = self.store.all()
        self.summary = {
            "open": [{"id": w["id"], "kind": w["kind"], "task": w.get("task"), "text": describe(w),
                      "created": w["created"]} for w in ws.values() if w["state"] == "open"],
            "fired": [{"id": w["id"], "kind": w["kind"], "task": w.get("task"), "text": fired_text(w),
                       "at": w["closedAt"]} for w in ws.values()
                      if w["state"] == "fired" and now - J._int(w["closedAt"]) < FIRED_SHOW_MS],
            "evidence": st["evidence"]}
        return self.summary

    def _fire(self, w, now, result, **detail):
        self.store.close(w["id"], "fired", now, result=result, detail=detail or None)

    def _one(self, w, new_events, sessions, fold, usage, now, st, time_left, budget):
        if now - w["created"] > WATCH_EXPIRE_MS:
            self.store.close(w["id"], "expired", now)
            return
        k, t = w["kind"], w.get("target")
        if k == "stop":
            # C7: a Stop with background tasks is not "doběhla"; only one without `bg`, after the watch was set
            for e in new_events or ():
                if e.ev == "stop" and e.name == t and e.ms > w["created"] and e.extra != "bg":
                    return self._fire(w, now, "done", ms=e.ms)
            stop = ((fold.s.get(t) if fold else None) or {}).get("stop") or {}
            if J._int(stop.get("ms") or 0) > w["created"] and not stop.get("bg"):
                return self._fire(w, now, "done", ms=stop["ms"])
            s = (sessions or {}).get(t)
            if s and s.get("state") in ("gone", "dead", "missing"):
                return self._fire(w, now, s["state"])
        elif k == "waiting":
            kind = (((sessions or {}).get(t) or {}).get("attention") or {}).get("kind")
            if kind in WAITING_KINDS:
                return self._fire(w, now, kind)
        elif k == "usage":
            u = (usage or {}).get(w["acct"]) or {}
            v = u.get(w["window"])
            if isinstance(v, (int, float)) and (u.get("ageS") or 0) <= USAGE_STALE_S and v < w["below"]:
                return self._fire(w, now, "below", value=v)
        elif k == "ci":
            c = st["checks"].setdefault(w["id"], {})
            if now - J._int(c.get("at") or 0) < CI_EVERY_MS or budget[0] <= 0 or time_left() < MIN_TIME_LEFT_S:
                return None
            budget[0] -= 1
            sha = self.repo.resolve(w["repo"], w["ref"], w.get("refKind"))
            res, failed = self.repo.ci_status(w["repo"], sha) if sha else ("unknown", [])
            c.update(at=now, sha=sha, res=res)
            if sha:
                st["ci"][f"{w['repo']}@{sha}"] = {"at": now, "res": res, "failed": failed}
            if "first" not in c:
                c["first"] = {"sha": sha, "done": res in ("green", "red")}
            # a branch watch tells the NEXT result: a run already finished when the watch was set is old news (the
            # watch was set before the push), so it waits for the branch to move
            if res in ("green", "red") and (w.get("refKind") == "sha" or not (c["first"]["done"]
                                                                                and c["first"]["sha"] == sha)):
                return self._fire(w, now, res, sha=sha, failed=failed)
        return None

    def _evidence(self, sessions, now, st, time_left, budget):
        import cr_task
        import jarvismail as M
        tasks = cr_task.fold(cr_task.read_events(self.env))
        mails = M.Mail(self.env).all()
        proved = {(e.get("task"), e.get("head")) for e in self.store.events() if e.get("ev") == "proof"}
        for t in tasks.values():
            if t["state"] != "open" or not t.get("ac"):
                st["evidence"].pop(t["id"], None)
                continue
            last = proof_base(t, mails)
            if not last or last["state"] != "replied":
                continue
            s = (sessions or {}).get(last["to"]) or {}
            if s.get("state") == "busy" or s.get("stopBg"):
                continue  # C7: the reply is not the end while the session still works
            prev = st["evidence"].get(t["id"]) or {}
            if now - J._int(prev.get("at") or 0) < GIT_EVERY_MS:
                continue
            base = last.get("base") or {}
            # CI through the cache unless this tick may still ask GitHub (rate: per commit, like a watch)
            head = self.repo.git(base.get("top"), "rev-parse", "--verify", "-q", "HEAD") if base.get("top") else None
            cached = st["ci"].get(f"{base.get('top')}@{head}") or {}
            ask = (budget[0] > 0 and time_left() >= MIN_TIME_LEFT_S and cached.get("res") not in ("green", "red")
                   and now - J._int(cached.get("at") or 0) >= CI_EVERY_MS)
            self.repo.gh_allowed = ask
            try:
                ev = evidence(base, self.repo)
            finally:
                self.repo.gh_allowed = True
            if ask and ev.get("ci") is not None:
                budget[0] -= 1
                st["ci"][f"{base['top']}@{ev['head']}"] = {"at": now, "res": ev["ci"], "failed": ev["failed"]}
            st["evidence"][t["id"]] = {"at": now, "missing": [str(m) for m in ev["missing"]], "head": ev["head"],
                                       "ci": ev["ci"]}
            if not ev["missing"] and (t["id"], ev["head"]) not in proved:
                self.store.append({"v": 1, "at": now, "ev": "proof", "task": t["id"], "title": t.get("title"),
                                   "head": ev["head"], "branch": ev["branch"], "ci": ev["ci"] or "none"})


# ---------------------------------------------------------------------------------------------------------- audit

SINCE_RE = re.compile(r"(\d{1,4})([smhd])")
_UNIT = {"s": 1000, "m": 60000, "h": 3600000, "d": 86400000}


def parse_since(s, default_ms):
    if s is None:
        return default_ms
    m = SINCE_RE.fullmatch(s.strip())
    if not m:
        raise ValueError("--since je číslo a jednotka s, m, h nebo d (např. 1h, 30m, 2d)")
    return min(int(m.group(1)) * _UNIT[m.group(2)], 30 * 86400000)


AUDIT_MAIL = {
    "proposed": "Jarvis navrhl zprávu", "approved": "Lukáš řekl ano", "dropped": "návrh zahozen (bez ano)",
    "refused": "odmítnuto", "confirmed": "doručeno", "uncertain": "doručení nejisté", "give-up": "doručení vzdáno",
    "cancelled": "zrušeno", "expired": "vypršelo", "reply": "odpověď", "off": "pošta vypnuta", "on": "pošta zapnuta",
    # F3
    "mode": "režim Jarvise", "rule-add": "pravidlo nehlas", "rule-rm": "pravidlo nehlas zrušeno",
    "wake": "Jarvis probuzen",
    # F5c
    "auto": "Jarvis sám (krok bez ano)", "wake-said": "Jarvis ohlásil po probuzení",
    "wake-fallback": "místo Jarvise šablona",
}
AUDIT_TASK = {"add": "úkol založen", "done": "úkol hotovo", "fail": "úkol selhal"}
AUDIT_WATCH = {"add": "hlídka přidána", "fired": "hlídka splněna", "expired": "hlídka vypršela",
               "cancelled": "hlídka zrušena", "proof": "důkaz k úkolu"}
WHO = {"jarvis": "Jarvis", "lukas": "Lukáš", "jarvis-auto": "Jarvis sám"}


def audit(env, since_ms, task=None):
    """One timeline from what is already written (the mail ledger, tasks.jsonl, watches.jsonl): no new writer.
    Rows {at, src, ev, what, id, to, task, who, text}; texts that came from sessions are escaped as data."""
    import cr_task
    import jarvismail as M
    m = M.Mail(env)
    recs = {r["id"]: r for r in m.all()}
    task_mails = {i for i, r in recs.items() if r.get("task") == task} if task else None
    rows = []
    for e in m.ledger(since_ms, max_bytes=4 * 1024 * 1024):
        ev = e.get("ev")
        if ev not in AUDIT_MAIL:
            continue
        rid = e.get("id")
        if task and rid not in task_mails:
            continue
        r = recs.get(rid or "") or {}
        text = ""
        if ev == "reply":
            text = M.data_text(r.get("reply"), 160)
            who = "výslovná" if r.get("replyBy") == "explicit" else "konec tahu"
        elif ev == "refused":
            text = M.data_text(e.get("reason"), 200)
            who = None
        elif ev in ("mode", "rule-add", "rule-rm", "wake", "wake-said", "wake-fallback"):
            text = M.data_text(e.get("text") or e.get("result") or e.get("kind") or e.get("why") or "", 160)
            who = WHO.get(e.get("by"))
        elif ev == "auto":
            text = {"resume": "pokračuj po limitu", "nudge": "popohnání k úkolu"}.get(e.get("kind"), e.get("kind") or "")
            who = WHO.get(e.get("by"))
        else:
            who = None  # "Jarvis navrhl", "Lukáš řekl ano": the actor is in the words already
            if ev in ("uncertain", "give-up") and e.get("reason"):
                text = M.data_text(str(e.get("reason")), 80)
        what = AUDIT_MAIL[ev]
        if ev == "approved" and e.get("decidedBy") == M.AUTO_BY:
            what = "Jarvis sám zařadil (bez ano)"  # F5c: never "Lukáš řekl ano" for a step Lukáš did not approve
        elif ev == "proposed" and e.get("by") == M.AUTO_BY:
            what = "Jarvis sám připravil zprávu"
        rows.append({"at": e["at"], "src": "mail", "ev": ev, "what": what, "id": rid,
                     "to": e.get("to") or r.get("to"), "task": r.get("task"), "who": who, "text": text})
    for e in cr_task.read_events(env):
        if J._int(e.get("at") or 0) <= since_ms or (task and e["id"] != task) or e.get("ev") not in AUDIT_TASK:
            continue
        rows.append({"at": e["at"], "src": "task", "ev": e["ev"], "what": AUDIT_TASK[e["ev"]], "id": e["id"],
                     "to": e.get("to"), "task": e["id"], "who": WHO.get(e.get("by")),
                     "text": M.data_text(e.get("title") if e["ev"] == "add" else e.get("note"), 120)})
    W = Watches(env)
    evs = W.events()
    ws = W.fold(evs)
    for e in evs:
        if e["at"] <= since_ms or e.get("ev") not in AUDIT_WATCH:
            continue
        if e["ev"] == "proof":
            if task and e.get("task") != task:
                continue
            rows.append({"at": e["at"], "src": "watch", "ev": "proof", "what": AUDIT_WATCH["proof"], "id": e.get("task"),
                         "to": None, "task": e.get("task"), "who": None,
                         "text": f"commit {(e.get('head') or '?')[:8]}, push" + (", CI zelené" if e.get("ci") == "green" else "")})
            continue
        w = ws.get(e["id"])
        if not w or (task and w.get("task") != task):
            continue
        txt = fired_text(dict(w, result=e.get("result"), detail=e.get("detail"))) if e["ev"] == "fired" else describe(w)
        rows.append({"at": e["at"], "src": "watch", "ev": e["ev"], "what": AUDIT_WATCH[e["ev"]], "id": e["id"],
                     "to": w.get("target"), "task": w.get("task"), "who": WHO.get(e.get("by")), "text": txt})
    rows.sort(key=lambda r: r["at"])
    return rows


def audit_line(r, hm):
    who = f" ({r['who']})" if r.get("who") else ""
    to = f" → {J.short_name(r['to'])}" if r.get("to") else ""
    task = f" · {r['task']}" if r.get("task") and r["src"] != "task" else ""
    text = f" · «{r['text']}»" if r.get("text") else ""
    return f"{hm(r['at'])} {r['what']}{who} {r['id'] or ''}{to}{task}{text}".replace("  ", " ")


def _num(n, gender):
    """Czech numerals agree in gender: jeden úkol, jedna zpráva, jedno schválení; dva úkoly, dvě zprávy."""
    if n == 1:
        return {"m": "jeden", "f": "jedna", "n": "jedno"}[gender]
    if n == 2:
        return "dva" if gender == "m" else "dvě"
    return J.num_f(n)


def audit_say(rows, span_cz):
    """Counts only (TTS: no ids, no paths, no session texts)."""
    c = {}
    for r in rows:
        c[(r["src"], r["ev"])] = c.get((r["src"], r["ev"]), 0) + 1
    parts = []
    for key, g, forms in ((("mail", "proposed"), "f", ("navržená zpráva", "navržené zprávy", "navržených zpráv")),
                          (("mail", "approved"), "n", ("tvoje schválení", "tvoje schválení", "tvých schválení")),
                          (("mail", "confirmed"), "f", ("doručená zpráva", "doručené zprávy", "doručených zpráv")),
                          (("mail", "refused"), "f", ("odmítnutá zpráva", "odmítnuté zprávy", "odmítnutých zpráv")),
                          (("mail", "reply"), "f", ("odpověď", "odpovědi", "odpovědí")),
                          (("task", "add"), "m", ("nový úkol", "nové úkoly", "nových úkolů")),
                          (("task", "done"), "m", ("hotový úkol", "hotové úkoly", "hotových úkolů")),
                          (("watch", "fired"), "f", ("splněná hlídka", "splněné hlídky", "splněných hlídek"))):
        x = c.get(key, 0)
        if x:
            parts.append(f"{_num(x, g)} {J.plural(x, *forms)}")
    if not parts:
        return f"Za {span_cz} se nic nestalo."
    return f"Za {span_cz}: " + ", ".join(parts) + "."


def write_audit(out, rows, a, span):
    """The output of `cr-task log` and `cr-mail audit` (a.json / a.say / text)."""
    import jarvismail as M
    if a.json:
        out.write(json.dumps(rows, ensure_ascii=False, indent=1) + "\n")
    elif a.say:
        out.write(audit_say(rows, span_cz(span)) + "\n")
    else:
        def hm(ms):
            return time.strftime("%d.%m. %H:%M", time.localtime(ms / 1000))
        out.write(f"[audit · {span_cz(span)}: {M.DATA_NOTE}]\n"
                  + ("\n".join(audit_line(r, hm) for r in rows[-200:]) or "nic") + "\n")


def span_cz(ms):
    """'poslední hodinu', 'poslední 2 hodiny', 'posledních 24 hodin', 'posledních 30 minut' (after 'za')."""
    for unit, forms in ((86400000, ("den", "dny", "dní")), (3600000, ("hodinu", "hodiny", "hodin")),
                        (60000, ("minutu", "minuty", "minut"))):
        if ms >= unit and (ms % unit == 0 or unit == 60000):
            n = ms // unit
            if n == 1:
                return f"poslední {forms[0]}"
            return f"{'poslední' if 2 <= n <= 4 else 'posledních'} {n} {J.plural(n, *forms)}"
    return "poslední chvíli"
