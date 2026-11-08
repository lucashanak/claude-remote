#!/usr/bin/env python3
"""cr-task: Jarvis's task ledger (F2a). The ONLY writer of `<state>/tasks.jsonl`.

    cr-task add "<title>" [--to <name>] [--ac "<acceptance criteria>"]   -> t-<id>
    cr-task list [--all] [--json]                                         open tasks (--all: also done/failed)
    cr-task show <t-id> [--json]                                          one task + its mail
    cr-task done <t-id> ["<note>"] [--force]                              --force: Lukáš only, without the evidence
    cr-task fail <t-id> ["<note>"]
    cr-task watch stop|waiting <name> [--task t-…]                        "dej vědět, až X doběhne / bude čekat na tebe"
    cr-task watch ci <name> [--ref <branch|sha>] [--task t-…]             "… až doběhne CI" (repo = the session's cwd)
    cr-task watch usage <account> <percent> [--week]                      "… až limit účtu klesne pod X %"
    cr-task watches [--all] [--json] [--say]                              open watches (--all: also fired/expired)
    cr-task unwatch <w-id>
    cr-task log [<t-id>] [--since 24h] [--json] [--say]                   the audit: tasks, mail, watches
    cr-task remind "<text>" --at HH:MM | --in 2h                          F3: a reminder, told by jarvis-d (app alert)
    cr-task reminders [--all] [--json]                                    open reminders (--all: also fired/cancelled)
    cr-task unremind <r-id>

F2b: a task delegated through cr-mail with --ac is not "hotovo" when the session merely replies. It is "ke kontrole"
and `done` needs the evidence (jarviswatch.evidence): a new commit in the target's repo since the mail, pushed, and
green CI when the repo has CI. Without it `done` is refused with what is missing; only Lukáš, from his own shell, may
override with --force. A session whose last Stop had background tasks has not finished (C7).

`tasks.jsonl` holds EVENTS, appended under flock (`tasks.lock`); a task's state is the fold of its events, so two
writers can never overwrite each other and every change stays auditable (navrh §6 rozpor 5). Writes are allowed to
Jarvis (its own pane) and to Lukáš (a shell outside Claude); a working session must not mark its own task done.
Exit codes: 0, 2 refused (reason on stderr), 1 internal error.
"""
import argparse
import fcntl
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
import jarvislib as J  # noqa: E402
import jarvismail as M  # noqa: E402
import jarviswatch as JW  # noqa: E402
import jarvisalert as JA  # noqa: E402

TITLE_MAX = 300
AC_MAX = 1000
NOTE_MAX = 500
STATE_CZ = {"open": "otevřený", "done": "hotovo", "failed": "selhalo"}
LOG_DEFAULT_MS = 24 * 3600 * 1000


class Refused(Exception):
    pass


def path(env):
    return env.path("tasks.jsonl")


def read_events(env):
    out = []
    try:
        with open(path(env), encoding="utf-8") as f:
            for line in f:
                try:
                    d = json.loads(line)
                except ValueError:
                    continue  # a torn or foreign line never breaks the fold
                if isinstance(d, dict) and M.TASK_RE.fullmatch(str(d.get("id"))):
                    out.append(d)
    except OSError:
        pass
    return out


def fold(events):
    tasks = {}
    for e in events:
        tid, ev = e["id"], e.get("ev")
        if ev == "add" and tid not in tasks:
            tasks[tid] = {"id": tid, "title": e.get("title"), "to": e.get("to"), "ac": e.get("ac"), "state": "open",
                          "created": e.get("at"), "by": e.get("by"), "closedAt": None, "note": None}
        elif ev in ("done", "fail") and tid in tasks and tasks[tid]["state"] == "open":
            tasks[tid].update(state="done" if ev == "done" else "failed", closedAt=e.get("at"), note=e.get("note"))
    return tasks


def writer(env, m):
    """'jarvis' or 'lukas', else Refused."""
    me = J.read_self(env)
    name, why = m.caller_session()
    if not why and me and name == me:
        return "jarvis"
    if not env.environ.get("CLAUDECODE"):
        return "lukas"
    raise Refused("úkolník smí měnit jen Jarvis nebo Lukáš, ne pracovní session")


def append(env, ev):
    os.makedirs(env.state_dir, mode=0o700, exist_ok=True)
    with open(env.path("tasks.lock"), "a+") as lk:
        fcntl.flock(lk.fileno(), fcntl.LOCK_EX)
        try:
            tasks = fold(read_events(env))
            if ev["ev"] != "add":
                t = tasks.get(ev["id"])
                if not t:
                    raise Refused(f"úkol {ev['id']} neexistuje")
                if t["state"] != "open":
                    raise Refused(f"úkol {ev['id']} už je {STATE_CZ[t['state']]}")
            fd = os.open(path(env), os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
            try:
                os.write(fd, (json.dumps(ev, ensure_ascii=False, separators=(",", ":")) + "\n").encode())
                os.fsync(fd)
            finally:
                os.close(fd)
        finally:
            fcntl.flock(lk.fileno(), fcntl.LOCK_UN)


def _text(v, max_len, what, required=True):
    if v is None and not required:
        return None
    t, why = M.validate_text(v, max_len, what)
    if why:
        raise Refused(why)
    return t


def _line(t, now, mails=(), status=None):
    to = f" → {J.short_name(t['to'])}" if t.get("to") else ""
    label, detail = status or (STATE_CZ[t["state"]], None)
    st = label + (f" ({detail})" if detail else "")
    return (f"{t['id']} · {st}{to} · {J.ago_short(now - J._int(t.get('created')))} · "
            f"«{M.data_text(t.get('title'), 120)}»" + (f" · pošta: {', '.join(mails)}" if mails else ""))


def repo_for(env):
    """Read-only git; CI only from jarvis-d's cache (cr-task never calls gh)."""
    st = J.read_json(env.path("watch-state.json"), {}) or {}
    return JW.Repo(env, gh_allowed=False, ci_cache=st.get("ci") if isinstance(st.get("ci"), dict) else {})


def check_done(env, t, recs, sessions, repo, force, by):
    """None, or why `done` may not be written yet (F1 ověřené hotovo, C7 bg)."""
    if not t.get("ac") or not any(r.get("task") == t["id"] and r["state"] not in ("dropped", "cancelled")
                                  for r in recs):
        return None  # not delegated with criteria: Jarvis's or Lukáš's word is enough
    if force:
        if by != "lukas":
            raise Refused("--force smí jen Lukáš ze svého shellu")
        return None
    last = JW.proof_base(t, recs)
    if not last or last["state"] != "replied":
        label, detail = JW.task_status(t, recs, sessions, repo)
        return f"úkol {t['id']} ještě nemá odpověď ze session ({label}" + (f", {detail}" if detail else "") + ")"
    s = sessions.get(last["to"]) or {}
    if s.get("state") == "busy" or s.get("stopBg"):
        return f"{J.short_name(last['to'])} ještě nedoběhla" + (" (na pozadí běží úloha)" if s.get("stopBg") else "")
    ev = JW.evidence(last.get("base"), repo)
    if ev["missing"]:
        return f"úkol {t['id']} je ke kontrole a čeká na důkaz: chybí " + ", ".join(map(str, ev["missing"]))
    return None


def cmd_watch(env, m, a, now, by):
    if a.what not in JW.KINDS:
        raise Refused("hlídat jde: " + ", ".join(JW.KINDS))
    store = JW.Watches(env)
    if a.task:
        if not M.TASK_RE.fullmatch(a.task):
            raise Refused("--task musí být t-…")
        t = fold(read_events(env)).get(a.task)
        if not t or t["state"] != "open":
            raise Refused(f"úkol {a.task} neexistuje nebo už je uzavřený")
    wid = M.now_id("w", now)
    rec = {"v": 1, "at": now, "ev": "add", "id": wid, "kind": a.what, "task": a.task, "by": by}
    if a.what == "usage":
        rec.update(_usage_watch(env, a, now))
    else:
        sessions = JW.sessions_now(env, now)
        name, why = m.resolve_name(a.target, sessions)
        if why:
            raise Refused(why)
        if name == J.read_self(env):
            raise Refused("Jarvis nehlídá sám sebe")
        s = sessions.get(name) or {}
        rec["target"] = name
        short = J.short_name(name)
        if s.get("state") in ("gone", "dead", "missing"):
            raise Refused(f"{short} neběží ({s['state']})")
        if a.what == "stop" and s.get("state") == "idle" and not s.get("stopBg"):
            raise Refused(f"{short} už je nečinná (doběhla), hlídka by čekala až na její další tah; řekni to Lukášovi")
        if a.what == "waiting" and ((s.get("attention") or {}).get("kind") in JW.WAITING_KINDS):
            raise Refused(f"{short} už teď čeká na Lukáše; řekni mu to rovnou")
        if a.what == "ci":
            rec.update(_ci_watch(env, s.get("cwd") or JW.target_cwd(env, name), a.ref, short))

    def guard(ws):
        open_ = [w for w in ws.values() if w["state"] == "open"]
        for w in open_:
            if all(w.get(k) == rec.get(k) for k in ("kind", "target", "repo", "ref", "acct", "window", "below")):
                return "dup:" + w["id"]
        if len(open_) >= JW.MAX_OPEN:
            return f"už je {len(open_)} aktivních hlídek (nejvýš {JW.MAX_OPEN}); nějakou zruš: cr-task unwatch w-…"
        return None
    why = store.append(rec, guard)
    if why and why.startswith("dup:"):
        return why[4:], f"tohle už hlídám jako {why[4:]}: {JW.describe(rec)}"
    if why:
        raise Refused(why)
    return wid, None


def _ci_watch(env, cwd, ref, short):
    repo = JW.Repo(env)
    top = repo.top(cwd) if cwd else None
    if not top:
        raise Refused(f"{short} nepracuje v gitu, CI nemám kde hlídat")
    if not repo.has_ci(top):
        raise Refused(f"repo {os.path.basename(top)} nemá CI (.github/workflows), není co hlídat")
    ref = ref or repo.git(top, "symbolic-ref", "-q", "--short", "HEAD")
    if not ref or not JW.REF_RE.fullmatch(ref):
        raise Refused("--ref je jméno větve nebo commit (písmena, číslice, . _ / -), nezačíná pomlčkou")
    kind = "branch"
    if JW.SHA_RE.fullmatch(ref) and not repo.git(top, "rev-parse", "--verify", "-q", "refs/heads/" + ref):
        full = repo.git(top, "rev-parse", "--verify", "-q", ref + "^{commit}")
        if not full:
            raise Refused(f"commit {ref} v {os.path.basename(top)} neznám")
        ref, kind = full, "sha"
    elif not repo.resolve(top, ref, "branch"):
        raise Refused(f"větev {ref} v {os.path.basename(top)} neexistuje")
    return {"repo": top, "ref": ref, "refKind": kind}


def _usage_watch(env, a, now):
    try:
        below = int(a.value)
    except (TypeError, ValueError):
        raise Refused("cr-task watch usage <účet> <procenta>, např. usage nekrachni 50")
    if not 1 <= below <= 100:
        raise Refused("procenta jsou 1 až 100")
    usage = J.read_usage(env, now)
    q = a.target
    hits = sorted(k for k, u in usage.items() if q in (k, J.account_short(u.get("account")), k.split("@")[0]))
    if len(hits) != 1:
        names = ", ".join(sorted({J.account_short(u.get("account")) for u in usage.values()})) or "žádné"
        raise Refused(f"účet „{J.safe_name(q)}“ {'je nejednoznačný' if hits else 'neznám'} (účty: {names})")
    window = "sevenDay" if a.week else "fiveHour"
    u = usage[hits[0]]
    v = u.get(window)
    if isinstance(v, (int, float)) and (u.get("ageS") or 0) <= JW.USAGE_STALE_S and v < below:
        raise Refused(f"limit už teď je {v:.0f} %, pod {below} %; řekni to Lukášovi rovnou")
    return {"acct": hits[0], "acctName": J.account_short(u.get("account")), "window": window, "below": below}


def cmd_remind(env, a, now, by, m=None):
    """rev-f3 LOW3: a reminder is a vibrating alert with free text, so from Jarvis it needs a fresh prompt of Lukáš's
    (≤ 10 min) and at most 3 per prompt; Lukáš himself is not limited that way."""
    text = _text(a.text, JA.REMIND_TEXT_MAX, "připomínka")
    lp = None
    if by == "jarvis":
        lp = (m.last_prompt() if m else None) or {}
        at = J._int(lp.get("at"))
        if not lp.get("id") or not at or not (-60000 <= now - at <= M.PROMPT_FRESH_MS):
            raise Refused("připomínku zakládáš jen na čerstvý Lukášův pokyn (do 10 minut)")
    if bool(a.at) == bool(getattr(a, "in_")):
        raise Refused("řekni jedno: --at HH:MM, nebo --in 2h")
    try:
        due = JA.parse_at(a.at, now) if a.at else now + JA.parse_in(a.in_)
    except ValueError as e:
        raise Refused(str(e))
    if due - now > JA.REMIND_MAX_MS:
        raise Refused("připomínka nejvýš 7 dní dopředu")
    store = JA.Reminders(env)

    def guard(rs):
        if sum(1 for r in rs.values() if r["state"] == "open") >= 50:
            return "už je 50 čekajících připomínek; nějakou zruš: cr-task unremind r-…"
        if lp and sum(1 for e in store.lines() if e.get("ev") == "add" and e.get("prompt") == lp.get("id")) >= 3:
            return "na jeden Lukášův pokyn nejvýš 3 připomínky"
        return None
    rid = JA.new_id("r", now)
    ev = {"v": 1, "at": now, "ev": "add", "id": rid, "text": text, "due": due, "by": by}
    if lp:
        ev["prompt"] = lp.get("id")
    why = store.append(ev, guard)
    if why:
        raise Refused(why)
    return rid, due


def _reminder_line(r, now):
    st = {"open": "čeká", "fired": "řečena", "cancelled": "zrušena"}[r["state"]]
    return f"{r['id']} · {st} · {JA.when_cz(r['due'], now)} · «{M.data_text(r.get('text'), 160)}»"


def main(argv=None, env=None, out=None, err=None, mail=None):
    out, err = out or sys.stdout, err or sys.stderr
    ap = argparse.ArgumentParser(prog="cr-task", description="Jarvis task ledger (F2a).")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("add")
    p.add_argument("title")
    p.add_argument("--to")
    p.add_argument("--ac")
    p = sub.add_parser("list")
    p.add_argument("--all", action="store_true")
    p.add_argument("--json", action="store_true")
    p = sub.add_parser("show")
    p.add_argument("id")
    p.add_argument("--json", action="store_true")
    for c in ("done", "fail"):
        p = sub.add_parser(c)
        p.add_argument("id")
        p.add_argument("note", nargs="?")
        if c == "done":
            p.add_argument("--force", action="store_true")
    p = sub.add_parser("watch")
    p.add_argument("what")
    p.add_argument("target")
    p.add_argument("value", nargs="?")
    p.add_argument("--ref")
    p.add_argument("--week", action="store_true")
    p.add_argument("--task")
    p = sub.add_parser("watches")
    p.add_argument("--all", action="store_true")
    p.add_argument("--json", action="store_true")
    p.add_argument("--say", action="store_true")
    p = sub.add_parser("unwatch")
    p.add_argument("id")
    p = sub.add_parser("log")
    p.add_argument("id", nargs="?")
    p.add_argument("--since")
    p.add_argument("--json", action="store_true")
    p.add_argument("--say", action="store_true")
    p = sub.add_parser("remind")
    p.add_argument("text")
    p.add_argument("--at")
    p.add_argument("--in", dest="in_")
    p = sub.add_parser("reminders")
    p.add_argument("--all", action="store_true")
    p.add_argument("--json", action="store_true")
    p = sub.add_parser("unremind")
    p.add_argument("id")
    a = ap.parse_args(argv)
    env = env or J.Env()
    m = mail or M.Mail(env)
    now = env.now_ms()
    old = os.umask(0o077)
    try:
        if a.cmd == "add":
            by = writer(env, m)
            title = _text(a.title, TITLE_MAX, "název")
            ac = _text(a.ac, AC_MAX, "kritéria", required=False)
            to = None
            if a.to:
                to, why = m.resolve_name(a.to)
                if why:
                    raise Refused(why)
            tid = M.now_id("t", now)
            append(env, {"v": 1, "at": now, "ev": "add", "id": tid, "title": title, "to": to, "ac": ac, "by": by})
            out.write(f"{tid} založen\n")
        elif a.cmd in ("done", "fail"):
            by = writer(env, m)
            if not M.TASK_RE.fullmatch(a.id):
                raise Refused("id úkolu je t-…")
            note = _text(a.note, NOTE_MAX, "poznámka", required=False)
            t = fold(read_events(env)).get(a.id)
            if a.cmd == "done" and t and t["state"] == "open":
                why = check_done(env, t, m.all(), JW.sessions_now(env, now), repo_for(env), a.force, by)
                if why:
                    raise Refused(why)
            append(env, {"v": 1, "at": now, "ev": a.cmd, "id": a.id, "note": note, "by": by})
            out.write(f"{a.id} {STATE_CZ['done' if a.cmd == 'done' else 'failed']}\n")
        elif a.cmd == "watch":
            by = writer(env, m)
            wid, note = cmd_watch(env, m, a, now, by)
            w = JW.Watches(env).all().get(wid) or {}
            out.write(note + "\n" if note else f"{wid} hlídám: {JW.describe(w)}. Splnění ukáže delta nahoře "
                                                f"(„✓ Splněno: …“), do 24 h, pak hlídka vyprší.\n")
        elif a.cmd == "unwatch":
            by = writer(env, m)
            if not JW.WATCH_RE.fullmatch(a.id):
                raise Refused("id hlídky je w-…")
            why = JW.Watches(env).close(a.id, "cancelled", now, by=by)
            if why:
                raise Refused(why)
            out.write(f"{a.id} zrušena\n")
        elif a.cmd == "remind":
            by = writer(env, m)
            rid, due = cmd_remind(env, a, now, by, m)
            out.write(f"{rid}: připomenu {JA.when_cz(due, now)} (upozornění v aplikaci). Zrušit: cr-task unremind {rid}\n")
        elif a.cmd == "unremind":
            by = writer(env, m)
            if not JA.REMIND_RE.fullmatch(a.id):
                raise Refused("id připomínky je r-…")

            def guard(rs):
                r = rs.get(a.id)
                if not r:
                    return f"připomínka {a.id} neexistuje"
                return None if r["state"] == "open" else f"připomínka {a.id} už je {r['state']}"
            why = JA.Reminders(env).append({"v": 1, "at": now, "ev": "cancelled", "id": a.id, "by": by}, guard)
            if why:
                raise Refused(why)
            out.write(f"{a.id} zrušena\n")
        elif a.cmd == "reminders":
            rs = sorted(JA.Reminders(env).fold().values(), key=lambda r: r["due"])
            sel = [r for r in rs if a.all or r["state"] == "open"]
            if a.json:
                out.write(json.dumps(sel, ensure_ascii=False, indent=1) + "\n")
            else:
                out.write(f"[cr-task: {M.DATA_NOTE}]\n" + ("\n".join(_reminder_line(r, now) for r in sel)
                                                          or "žádné čekající připomínky") + "\n")
        elif a.cmd == "watches":
            ws = sorted(JW.Watches(env).all().values(), key=lambda w: -w["created"])
            sel = [w for w in ws if a.all or w["state"] == "open"]
            if a.json:
                out.write(json.dumps(sel, ensure_ascii=False, indent=1) + "\n")
            elif a.say:
                n = len([w for w in sel if w["state"] == "open"])
                out.write((f"Hlídám {J.num_f(n)} {J.plural(n, 'věc', 'věci', 'věcí')}: "
                           + ", ".join(JW.describe(w, True) for w in sel if w["state"] == "open")[:400] + "."
                           if n else "Nic nehlídám.") + "\n")
            else:
                rows = [f"{w['id']} · {JW.STATE_CZ[w['state']]} · {JW.describe(w)}"
                        + (f" · {JW.fired_text(w)}" if w["state"] == "fired" else "")
                        + (f" · {w['task']}" if w.get("task") else "")
                        + f" · {J.ago_short(now - w['created'])}" for w in sel]
                out.write("\n".join(rows or ["žádné aktivní hlídky"]) + "\n")
        elif a.cmd == "log":
            if a.id and not M.TASK_RE.fullmatch(a.id):
                raise Refused("id úkolu je t-…")
            try:
                span = JW.parse_since(a.since, LOG_DEFAULT_MS if not a.id else 30 * 86400000)
            except ValueError as e:
                raise Refused(str(e))
            rows = JW.audit(env, now - span, task=a.id)
            JW.write_audit(out, rows, a, span)
        else:
            tasks = fold(read_events(env))
            recs = m.all()
            mails = {}
            for r in recs:
                if r.get("task"):
                    mails.setdefault(r["task"], []).append(f"{r['id']} {r['state']}")
            sessions = JW.sessions_now(env, now)
            repo = repo_for(env)
            if a.cmd == "list":
                sel = [t for t in tasks.values() if a.all or t["state"] == "open"]
                sel.sort(key=lambda t: -J._int(t.get("created")))
                if a.json:
                    for t in sel:
                        t["status"] = list(JW.task_status(t, recs, sessions, repo))
                    out.write(json.dumps(sel, ensure_ascii=False, indent=1) + "\n")
                else:
                    out.write(f"[cr-task: {M.DATA_NOTE}]\n" + ("\n".join(
                        _line(t, now, mails.get(t["id"], ()), JW.task_status(t, recs, sessions, repo))
                        for t in sel) or "žádné otevřené úkoly") + "\n")
            else:
                t = tasks.get(a.id)
                if not t:
                    raise Refused(f"úkol {a.id} neexistuje")
                status = JW.task_status(t, recs, sessions, repo)
                if a.json:
                    out.write(json.dumps(dict(t, mail=mails.get(a.id, []), status=list(status)), ensure_ascii=False,
                                         indent=1) + "\n")
                else:
                    out.write(f"[cr-task: {M.DATA_NOTE}]\n{_line(t, now, mails.get(a.id, ()), status)}\n")
                    if t.get("ac"):
                        out.write(f"hotovo když: «{M.data_text(t['ac'], 600)}»\n")
                    if t.get("note"):
                        out.write(f"poznámka: «{M.data_text(t['note'], 400)}»\n")
    except Refused as e:
        err.write(f"cr-task: {e}\n")
        return 2
    finally:
        os.umask(old)
    return 0


if __name__ == "__main__":
    sys.exit(main())
