#!/usr/bin/env python3
"""cr-rules: the "nehlas" rules (F3). The only writer of `<state>/rules`; jarvis-d reads it.

    cr-rules add [--session <jméno>] [--kind question|task|watch|all] [--days N] ["<poznámka>"]
    cr-rules list [--json] [--say]
    cr-rules rm <n-id>

A rule drops matching alerts (jarvis-d) for at most 7 days. It NEVER silences a permission, a question of a dialog
(ask), a registry `waiting`, an incident or a reminder: those are refused here and ignored by jarvis-d even when
written by hand. `--session` is a whole tmux name, short name, alias or folder (no globs: a rule says exactly what it
covers, and it must match a session that exists now). `--kind`: question (a reply ending with a question), task
(results of tasks), watch (fired watches), all (all three).

Who (navrh §3 point 8, "Jarvis smí pravidlo jen navrhnout, nikdy ho sám nezavede"):
- Lukáš from his own shell or the app (no CLAUDECODE): always.
- Jarvis from his own pane: only on a FRESH prompt of Lukáš's (≤ 10 min) that itself asks for silence ("nehlas",
  "nehlásit", "nechci", "přestaň hlásit", …), at most 3 rules per prompt. The UserPromptSubmit hook records only
  that flag, never the text, and never for a `[jarvis …]` prompt. So when Jarvis offers "Mám tohle přestat
  hlásit?", a bare "ano" is not enough: Lukáš says "nehlas to" and the rule is his words, read back by the CLI.
  Chosen over a second proposal machine (like mail's): one step, works by voice, and the worst a mistaken rule does
  is hold back non-blocking news for ≤ 7 days, visible in `cr-rules list` and in the readback.
- any other Claude session: never.
`rm` makes Jarvis louder, so Jarvis may do it without the word. Exit codes: 0, 2 refused, 1 internal error.
"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
import jarvislib as J  # noqa: E402
import jarvismail as M  # noqa: E402
import jarviswatch as JW  # noqa: E402
import jarvisalert as JA  # noqa: E402

PAT_RE = re.compile(r"[\w.@+-]{1,80}")
KIND_ALIASES = {"all": "*", "vše": "*", "vse": "*", "*": "*", "question": "question", "otázka": "question",
                "task": "task", "úkol": "task", "watch": "watch", "hlídka": "watch"}
PER_PROMPT = 3
NOTE_MAX = 200


class Refused(Exception):
    pass


def caller(env, m):
    me = J.read_self(env)
    name, why = m.caller_session()
    if not why and me and name == me:
        return "jarvis"
    if not env.environ.get("CLAUDECODE"):
        return "lukas"
    raise Refused("pravidla „nehlas“ mění jen Lukáš, nebo Jarvis na jeho pokyn; ne pracovní session")


def describe(rule, now):
    mt = rule.get("match") or {}
    what = JA.RULE_KIND_CZ.get(mt.get("kind") or "*", "?")
    where = f"u {J.safe_name(mt['session'], 80)}" if mt.get("session") else "ze všech sessions"
    return f"nehlásím {what} {where} do {JA.when_cz(rule['until_ms'], now)}"


def cmd_add(env, m, a, now, by, out):
    kind = KIND_ALIASES.get((a.kind or "all").strip().lower())
    if (a.kind or "").strip().lower() in JA.NEVER_SILENCED + ("perm", "ask", "waiting", "povolení", "dotaz", "incident",
                                                               "reminder", "připomínka"):
        raise Refused("povolení, dotazy, dialogy, incidenty a připomínky se hlásí vždy, na ně pravidlo nejde")
    if not kind:
        raise Refused("--kind je question, task, watch nebo all")
    pat = (a.session or "").strip() or None
    if pat is None and kind == "*" and not a.everything:
        raise Refused("pravidlo bez --session a --kind by umlčelo všechno kromě povolení a dotazů; to je režim "
                      "„jen blokující“ (cr-jarvis mode blocking). Když to Lukáš opravdu chce: --everything")
    if pat is not None:
        if not PAT_RE.fullmatch(pat):
            raise Refused("--session je celé jméno, krátké jméno, alias nebo složka (písmena, číslice, . _ @ + -)")
        sessions = JW.sessions_now(env, now)
        hits = sorted(n for n, s in sessions.items() if pat in JA.session_keys(n, s) and not s.get("self"))
        if not hits:
            near = sorted(J.short_name(n) for n in sessions if pat.lower() in n.lower())[:6]
            raise Refused(f"žádná session se nejmenuje „{J.safe_name(pat)}“"
                          + (f" (myslíš: {', '.join(near)}?)" if near else ""))
    try:
        days = int(a.days) if a.days is not None else 7
    except ValueError:
        raise Refused("--days je 1 až 7")
    if not 1 <= days <= 7:
        raise Refused("pravidlo platí nejvýš 7 dní (--days 1 až 7)")
    note = None
    if a.note is not None:
        note, why = M.validate_text(a.note, NOTE_MAX, "poznámka")
        if why:
            raise Refused(why)
    lp = None
    if by == "jarvis":
        lp = m.last_prompt() or {}
        at = J._int(lp.get("at"))
        if not lp.get("id") or not at or not (-60000 <= now - at <= M.PROMPT_FRESH_MS):
            raise Refused("chybí čerstvý Lukášův pokyn (do 10 minut); pravidlo jen navrhni")
        if not lp.get("silence"):
            raise Refused("Lukášova poslední zpráva o umlčení neříká („nehlas“, „nechci“, „přestaň hlásit“); "
                          "pravidlo jen navrhni a zapíše se, až to řekne")
    store = JA.Rules(env)
    rule = {"id": JA.new_id("n", now), "match": {k: v for k, v in (("session", pat), ("kind", kind)) if v},
            "until_ms": now + days * 86400 * 1000, "created": now, "by": by}
    if note:
        rule["note"] = note
    if lp:
        rule["prompt"] = lp.get("id")

    def go():
        rules = store.all(now)
        if lp and sum(1 for r in rules if r.get("prompt") == lp.get("id")) >= PER_PROMPT:
            return None, f"na jeden Lukášův pokyn nejvýš {PER_PROMPT} pravidla"
        for r in rules:
            if r.get("match") == rule["match"]:
                r["until_ms"] = max(r["until_ms"], rule["until_ms"])  # the same rule again: it lasts longer
                store.rewrite_raw(rules)
                return r, None
        if len(rules) >= JA.RULES_MAX:
            return None, f"už je {len(rules)} pravidel (nejvýš {JA.RULES_MAX}); nějaké zruš: cr-rules rm n-…"
        store.rewrite_raw(rules + [rule])  # expired ones are dropped on every write
        return rule, None
    got, why = store.locked(go)
    if why:
        raise Refused(why)
    with m.lock():
        m.log("rule-add", id=got["id"], by=by, text=describe(got, now))
    out.write(f"{got['id']}: {describe(got, now)}. Povolení, dotazy a dialogy hlásím dál. "
              f"Přečti to Lukášovi. Odvolat: cr-rules rm {got['id']}\n")


def cmd_rm(env, m, a, now, by, out):
    if not JA.RULE_RE.fullmatch(a.id):
        raise Refused("id pravidla je n-…")
    store = JA.Rules(env)

    def go():
        rules = store.all(now)
        left = [r for r in rules if r["id"] != a.id]
        if len(left) == len(rules):
            return f"pravidlo {a.id} neexistuje nebo už vypršelo"
        store.rewrite_raw(left)
        return None
    why = store.locked(go)
    if why:
        raise Refused(why)
    with m.lock():
        m.log("rule-rm", id=a.id, by=by)
    out.write(f"{a.id} zrušeno, hlásím zase normálně\n")


def cmd_list(env, a, now, out):
    rules = sorted(JA.Rules(env).all(now), key=lambda r: r["until_ms"])
    if a.json:
        out.write(json.dumps(rules, ensure_ascii=False, indent=1) + "\n")
    elif a.say:
        n = len(rules)
        if not n:
            out.write("Žádné pravidlo neplatí, hlásím všechno.\n")
        else:
            out.write(f"Platí {J.num_f(n)} {J.plural(n, 'pravidlo', 'pravidla', 'pravidel')}: "
                      + "; ".join(describe(r, now) for r in rules[:5]) + ".\n")
    else:
        out.write("\n".join(f"{r['id']} · {describe(r, now)}" + (f" · «{M.data_text(r.get('note'), 120)}»"
                                                                 if r.get("note") else "") for r in rules)
                  or "žádná pravidla „nehlas“")
        out.write("\n")


def main(argv=None, env=None, out=None, err=None, mail=None):
    out, err = out or sys.stdout, err or sys.stderr
    ap = argparse.ArgumentParser(prog="cr-rules", description="Jarvis \"nehlas\" rules (F3).")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("add")
    p.add_argument("note", nargs="?")
    p.add_argument("--session")
    p.add_argument("--kind")
    p.add_argument("--days")
    p.add_argument("--everything", action="store_true")
    p = sub.add_parser("list")
    p.add_argument("--json", action="store_true")
    p.add_argument("--say", action="store_true")
    p = sub.add_parser("rm")
    p.add_argument("id")
    a = ap.parse_args(argv)
    env = env or J.Env()
    m = mail or M.Mail(env)
    now = env.now_ms()
    old = os.umask(0o077)
    try:
        if a.cmd == "list":
            cmd_list(env, a, now, out)
        else:
            by = caller(env, m)
            (cmd_add if a.cmd == "add" else cmd_rm)(env, m, a, now, by, out)
    except Refused as e:
        err.write(f"cr-rules: {e}\n")
        return 2
    finally:
        os.umask(old)
    return 0


if __name__ == "__main__":
    sys.exit(main())
