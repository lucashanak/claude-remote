#!/usr/bin/env python3
"""cr-jarvis: Jarvis's switches (F3).

    cr-jarvis mode [--json] [--say]                                      the current mode
    cr-jarvis mode normal|blocking|dnd [--until HH:MM | --for 2h]        set it (--until / --for only with dnd)

`<state>/mode` = `normal`, `blocking` or `dnd[:<until ms>]` (the contract with the app, which may read it too).
normal: every alert. blocking: blocks, task results, reminders, incidents (fired watches wait). dnd: incidents only;
without --until it lasts until it is switched back. jarvis-d holds back what the mode does not let through and tells
it afterwards if it is still current; an unreadable or expired file counts as normal (louder, never silent).
The app's own per-session notifications for permissions and questions do not depend on this (R11, fail-open).

Who: Lukáš from his shell or the app (no CLAUDECODE); Jarvis from his own pane on a fresh prompt of Lukáš's
(≤ 10 min, the same rule as mail); never another Claude session. Exit codes: 0, 2 refused, 1 internal error.
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
import jarvislib as J  # noqa: E402
import jarvismail as M  # noqa: E402
import jarvisalert as JA  # noqa: E402

FOR_MAX_MS = 24 * 3600 * 1000


class Refused(Exception):
    pass


def caller(env, m, now):
    me = J.read_self(env)
    name, why = m.caller_session()
    if not why and me and name == me:
        lp = m.last_prompt() or {}
        at = J._int(lp.get("at"))
        if not lp.get("id") or not at or not (-60000 <= now - at <= M.PROMPT_FRESH_MS):
            raise Refused("režim přepínáš jen na čerstvý Lukášův pokyn (do 10 minut)")
        return "jarvis"
    if not env.environ.get("CLAUDECODE"):
        return "lukas"
    raise Refused("režim Jarvise mění jen Lukáš, nebo Jarvis na jeho pokyn; ne pracovní session")


def main(argv=None, env=None, out=None, err=None, mail=None):
    out, err = out or sys.stdout, err or sys.stderr
    ap = argparse.ArgumentParser(prog="cr-jarvis", description="Jarvis switches (F3).")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("mode")
    p.add_argument("mode", nargs="?", choices=JA.MODES)
    p.add_argument("--until")
    p.add_argument("--for", dest="for_")
    p.add_argument("--json", action="store_true")
    p.add_argument("--say", action="store_true")
    a = ap.parse_args(argv)
    env = env or J.Env()
    m = mail or M.Mail(env)
    now = env.now_ms()
    old = os.umask(0o077)
    try:
        if a.mode:
            by = caller(env, m, now)
            until = None
            if a.until or a.for_:
                if a.mode != "dnd":
                    raise Refused("--until / --for jen u dnd")
                if a.until and a.for_:
                    raise Refused("buď --until HH:MM, nebo --for 2h")
                try:
                    until = JA.parse_at(a.until, now) if a.until else now + JA.parse_in(a.for_)
                except ValueError as e:
                    raise Refused(str(e))
                if until - now > FOR_MAX_MS:
                    raise Refused("nerušit nejvýš 24 hodin dopředu (bez času platí do odvolání)")
            JA.write_mode(env, a.mode, until)
            with m.lock():
                m.log("mode", by=by, text=a.mode + (f" do {JA.when_cz(until, now)}" if until else ""))
        mode, until = JA.read_mode(env, now)
        if a.json:
            out.write(json.dumps({"mode": mode, "until": until}) + "\n")
        else:
            out.write(JA.mode_say(mode, until, now) + "\n")
    except Refused as e:
        err.write(f"cr-jarvis: {e}\n")
        return 2
    finally:
        os.umask(old)
    return 0


if __name__ == "__main__":
    sys.exit(main())
