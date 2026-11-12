#!/usr/bin/env python3
"""cr-approve: Lukáš approves through Jarvis (F5e). The keys are the code's, never Jarvis's.

    cr-approve show <name> [--json]                      what the session asks (permission or AskUserQuestion), as
                                                          data, with its options and the fingerprint
    cr-approve propose <name> [--option N] [--deny]      a proposal `x-…` (kind approve) with a sentence the CODE
                                                          composes; Jarvis reads it verbatim and ends with it

A proposal waits for Lukáš's bare "ano" (his next prompt, the Ano of the `jask` notification, the watch, the voice)
like every other: one at a time, 10 minutes, the hook decides. ALWAYS a proposal (rev-f5e HIGH, the lead): not even
"u pdf-prepocet schval" goes at once, so Lukáš hears the code's sentence before any key; an option number his prompt
named ("zvol dvojku") that differs from --option is said in the sentence. A permission
is approved with its one-time "Yes" only; a question gets one of its real options (single question, single choice);
--deny is Escape. Never: I7 sessions ("Tohle schval v aplikaci."), the folder-trust dialog, /login or any other system
dialog. The answer itself runs in the detached runner (`cr-jarvis run`), right after a last check of the fingerprint.
Exit codes: 0, 2 refused (the reason on stderr), 1 internal error.
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
import jarvislib as J  # noqa: E402
import jarvismail as M  # noqa: E402
import jarvisops as JO  # noqa: E402
import jarvisapprove as JAP  # noqa: E402

Refused = JO.Refused


def _dialog(m, q, fold=None):
    name, why = m.resolve_name((q or "").strip())
    if why:
        raise Refused(why)
    d, why = JAP.read_dialog(m, name, fold)
    if why:
        raise Refused(why)
    return d


def _name(m, q):
    name, why = m.resolve_name((q or "").strip())
    if why:
        raise Refused(why)
    return name


def _trust_or_none(m, name):
    """The trust dialog of `name` when it waits on one (no perm/ask hook event), else None; a trust dialog that may
    not be approved here (outside $HOME, PROD) is refused with its reason."""
    tr, why = JAP.read_trust(m, name)
    if why and not isinstance(why, JAP.NotTrust):
        raise Refused(why)
    return tr


def cmd_show(env, a, out, m, ops):
    tr = _trust_or_none(m, _name(m, a.name))
    if tr:
        out.write(f"[cr-approve: {M.DATA_NOTE}]\nsession: {J.short_name(tr.name)} · dialog důvěry ke složce · otisk "
                  f"{tr.fp}\nsložka: «{J.safe_text(tr.path, 200)}»\nNavrhni: cr-approve propose '{tr.name}'"
                  f" (potvrdí důvěru), nebo pro všechny se stejnou složkou: cr-approve propose --trust-all {tr.path}\n")
        return 0
    d = _dialog(m, a.name)
    if a.json:
        out.write(JAP.dump(d) + "\n")
        return 0
    out.write(f"[cr-approve: {M.DATA_NOTE}]\n" + "\n".join(JAP.describe(d)) + "\n")
    acts = d.answers()
    opts = [f"--option {n}" for act, n, _ in acts if act == "option"]
    out.write("Navrhni: cr-approve propose " + J.short_name(d.name)
              + (" (schválí jednorázové Yes)" if any(x[0] == "approve" for x in acts) else "")
              + (f" s {' nebo '.join(opts)}" if opts else "") + ", nebo --deny.\n")
    return 0


def pick(d, a):
    acts = d.answers()
    if a.deny:
        return "deny", None
    if a.option is not None:
        if ("option", a.option) not in {(x[0], x[1]) for x in acts}:
            raise Refused(f"možnost {a.option} přes Jarvise zvolit nejde (není, nebo je to „napiš“, chat či víc "
                          f"otázek); zvol ji v aplikaci")
        return "option", a.option
    if d.p["kind"] == "perm":
        if not any(x[0] == "approve" for x in acts):
            raise Refused("tenhle dialog nemá jednorázové „Yes“; schval ho v aplikaci")
        return "approve", next(x[1] for x in acts if x[0] == "approve")
    rec = [o["n"] for o in d.p["options"] if o["recommended"] and JAP.answerable(o)]
    if d.p["tabs"] or d.p["multi"]:
        raise Refused("víc otázek naráz nebo výběr víc možností přes Jarvise nejde; zvol to v aplikaci")
    if len(rec) == 1:
        return "option", rec[0]
    raise Refused("u otázky řekni, kterou možnost: --option N (zeptej se Lukáše)")


def trusts_for(env, m, path, now):
    """Every session waiting on the trust dialog of the same folder (realpath), at most TRUST_MAX; the others
    (another folder, outside $HOME, PROD …) never join."""
    import jarviswatch as JW
    want = os.path.realpath(path)
    out = []
    for name, s in sorted(JW.sessions_now(env, now).items()):
        if not isinstance(s, dict) or s.get("waitingFor") != J.TRUST_CZ:
            continue
        tr, _ = JAP.read_trust(m, name)
        if tr and os.path.realpath(tr.path) == want:
            out.append(tr)
        if len(out) >= JAP.TRUST_MAX:
            break
    return out


def cmd_propose(env, a, out, m, ops):
    now = env.now_ms()
    by = ops.caller(now)
    if m.off():
        raise Refused("Jarvis je vypnutý (off)")
    lp = (m.last_prompt() or m.wake_prompt(now) or {}) if by == "jarvis" else {}
    pid = lp.get("id")
    trusts, d = None, None
    if a.trust_all:
        trusts = trusts_for(env, m, a.trust_all, now)
        if not trusts:
            raise Refused(f"na důvěru ke složce {a.trust_all} pod tvým domovem teď nic nečeká")
    elif a.name:
        tr = _trust_or_none(m, _name(m, a.name))
        trusts = [tr] if tr else None
    else:
        raise Refused("řekni jméno session, nebo --trust-all <cesta>")
    if not trusts:
        d = _dialog(m, a.name)
        action, n = pick(d, a)
    ops.per_prompt(pid, now)
    if m._pending():
        raise Refused("už čeká jiný návrh; nejdřív ať Lukáš rozhodne o něm")
    # rev-f5e HIGH (lead): never at once, not even "u X schval" or from a shell (a session can unset CLAUDECODE):
    # always a proposal, so Lukáš hears the code's sentence before anything is pressed and his bare "ano" decides
    if trusts:
        say = JAP.trust_sentence(trusts)
        p = ops.propose("approve", trusts[0].name, say, "", {"trust": True, "items": [t.item() for t in trusts]},
                        pid, now, targets=[t.name for t in trusts])
    else:
        say = JAP.sentence(d, action, n, said=lp.get("optionNumbers") or None)
        p = ops.propose("approve", d.name, say, "", JAP.args_json(d, action, n), pid, now)
    out.write(f"Návrh {p['id']}. Řekni Lukášovi doslova (a nic víc o tom): „{say}“ Provede se po jeho ano "
              f"(do 10 minut) nebo po schválení v aplikaci, jinak se zahodí.\n")
    return 0


def main(argv=None, env=None, out=None, err=None, mail=None, ops=None):
    out, err = out or sys.stdout, err or sys.stderr
    ap = argparse.ArgumentParser(prog="cr-approve", description="Approvals through Jarvis (F5e).")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("show")
    p.add_argument("name")
    p.add_argument("--json", action="store_true")
    p = sub.add_parser("propose")
    p.add_argument("name", nargs="?")
    p.add_argument("--trust-all", dest="trust_all")
    p.add_argument("--option", type=int)
    p.add_argument("--deny", action="store_true")
    a = ap.parse_args(argv)
    env = env or J.Env()
    m = mail or M.Mail(env)
    old = os.umask(0o077)
    try:
        ops = ops or JO.Ops(env, mail=m)
        return {"show": cmd_show, "propose": cmd_propose}[a.cmd](env, a, out, m, ops)
    except Refused as e:
        err.write(f"cr-approve: {e}\n")
        return 2
    finally:
        os.umask(old)


if __name__ == "__main__":
    sys.exit(main())
