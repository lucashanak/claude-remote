#!/usr/bin/env python3
"""cr-task: Jarvis's task ledger (F2a). The ONLY writer of `<state>/tasks.jsonl`.

    cr-task add "<title>" [--to <name>] [--ac "<acceptance criteria>"]   -> t-<id>
    cr-task list [--all] [--json]                                         open tasks (--all: also done/failed)
    cr-task show <t-id> [--json]                                          one task + its mail
    cr-task done <t-id> ["<note>"]
    cr-task fail <t-id> ["<note>"]

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

TITLE_MAX = 300
AC_MAX = 1000
NOTE_MAX = 500
STATE_CZ = {"open": "otevřený", "done": "hotovo", "failed": "selhalo"}


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
    if why and "OMC" in why:
        t, why = " ".join(v.split()), None  # a task title is never pasted anywhere; mode words are harmless here
    if why:
        raise Refused(why)
    return t


def _line(t, now, mails=()):
    to = f" → {J.short_name(t['to'])}" if t.get("to") else ""
    return (f"{t['id']} · {STATE_CZ[t['state']]}{to} · {J.ago_short(now - J._int(t.get('created')))} · "
            f"«{M.data_text(t.get('title'), 120)}»" + (f" · pošta: {', '.join(mails)}" if mails else ""))


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
            append(env, {"v": 1, "at": now, "ev": a.cmd, "id": a.id, "note": note, "by": by})
            out.write(f"{a.id} {STATE_CZ['done' if a.cmd == 'done' else 'failed']}\n")
        else:
            tasks = fold(read_events(env))
            mails = {}
            for r in m.all():
                if r.get("task"):
                    mails.setdefault(r["task"], []).append(f"{r['id']} {r['state']}")
            if a.cmd == "list":
                sel = [t for t in tasks.values() if a.all or t["state"] == "open"]
                sel.sort(key=lambda t: -J._int(t.get("created")))
                if a.json:
                    out.write(json.dumps(sel, ensure_ascii=False, indent=1) + "\n")
                else:
                    out.write(f"[cr-task: {M.DATA_NOTE}]\n" + ("\n".join(_line(t, now, mails.get(t["id"], ()))
                                                                  for t in sel) or "žádné otevřené úkoly") + "\n")
            else:
                t = tasks.get(a.id)
                if not t:
                    raise Refused(f"úkol {a.id} neexistuje")
                if a.json:
                    out.write(json.dumps(dict(t, mail=mails.get(a.id, [])), ensure_ascii=False, indent=1) + "\n")
                else:
                    out.write(f"[cr-task: {M.DATA_NOTE}]\n{_line(t, now, mails.get(a.id, ()))}\n")
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
