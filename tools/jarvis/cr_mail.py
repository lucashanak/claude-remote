#!/usr/bin/env python3
"""cr-mail: Jarvis's mail to other sessions (F2a). The invariants live in jarvismail.py, not here.

    cr-mail send --to <alias|name> [--task t-…] "<text>"   Jarvis only, on a fresh prompt of Lukáš's: a PROPOSAL,
                                                             queued only when his next prompt is "ano" or names it
    cr-mail status [<m-id>] [--json]                         the queue, or one message with its reply
    cr-mail inbox [--all] [--json]                           replies and problems of the last 24 h
    cr-mail cancel <m-id>                                    a message that is proposed, queued or uncertain
    cr-mail reply <m-id> "<text>"                            the ADDRESSEE's explicit reply (overrides the implicit one)
    cr-mail off                                              "Jarvisi, stop": no paste until `cr-mail on`
    cr-mail on                                               Lukáš only (refused inside any Claude session)
    cr-mail audit [--since 1h] [--json] [--say]              what Jarvis proposed, Lukáš approved, what was delivered
                                                             or refused, with times (+ tasks and watches)
    cr-mail prompt-seen                                      Jarvis's UserPromptSubmit hook (payload on stdin)
    cr-mail send … --after limit|HH:MM                       F3: approved, it waits until the target account's limit
                                                             resets (limit) or until that time
    cr-mail approve|reject <m-id> --by app:<installId>       F3: Lukáš's yes/no from the app's proposal card; refused
                                                             inside any Claude session (Jarvis's pane above all)

Exit codes: 0 done, 2 refused (the reason, in Czech, on stderr), 1 internal error.
The text goes as ONE argument: Jarvis's gate lets no heredoc, pipe or `$` through. The CLI checks it again (one line,
no control characters, ≤ 2000 characters) and pastes only one FIXED line into the target:
`[jarvis m-<id>] Zpráva od Jarvise · Celé znění: cat <path>`; the text itself is only in the file.
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


def _ja():
    """jarvisalert, imported lazily (rev-f3 LOW10): a broken F3 module must never take `cr-mail off` ("Jarvisi,
    stop"), status or send down with it. None when it does not import."""
    try:
        import jarvisalert
        return jarvisalert
    except Exception:  # noqa: BLE001
        return None


BY_RE = re.compile(r"app:[A-Za-z0-9._-]{1,64}")
# --after limit: the 7-day window counts as spent from this much on, then its reset is the one to wait for
WEEK_SPENT = 95

STATE_CZ = {"proposed": "navrženo, čeká na Lukášovo ano", "dropped": "zahozeno (bez ano)", "queued": "ve frontě", "delivering": "doručuje se", "delivered": "doručeno, čeká na odpověď",
            "uncertain": "NEJISTÉ", "replied": "odpovězeno", "expired": "vypršelo", "cancelled": "zrušeno"}
BLOCK_CZ = {"busy": "pracuje", "waiting": "čeká na Lukáše", "settling": "právě doběhla", "ask": "otevřený dotaz",
            "perm": "čeká na povolení", "bg": "běží úloha na pozadí", "draft": "rozepsaný vstup",
            "client_active": "někdo v ní píše", "mode": "copy-mode", "hold": "pozdrženo aplikací",
            "selector": "otevřený výběr", "busy-screen": "pracuje (obrazovka)", "screen-unknown": "neznámá obrazovka",
            "dead": "neběží", "no-claude": "bez claude", "off": "pošta vypnutá", "rate": "limit vložení",
            "rate-target": "limit na cíl", "locked": "doručuje se", "unknown": "neznámý stav",
            "clients-unknown": "nevím, kdo je připojen"}


class Refused(Exception):
    pass


def _age(now, ms):
    return J.ago_short(now - ms) if ms else "?"


def _row(r, now):
    blk = r.get("lastBlock") if r["state"] == "queued" else None
    extra = f" · blokuje: {BLOCK_CZ.get(blk, blk)}" if blk else ""
    task = f" · {r['task']}" if r.get("task") else ""
    nb = J._int(r.get("notBefore")) or 0
    if nb > now and r["state"] in ("proposed", "queued"):
        JA = _ja()
        extra += " · odloženo do " + (JA.when_cz(nb, now) if JA else J.ago_short(nb - now))
    return (f"{r['id']} → {J.short_name(r['to'])} · {STATE_CZ.get(r['state'], r['state'])}{extra}{task} · "
            f"{_age(now, r.get('created'))} · «{M.data_text(r.get('subject'), 80)}»")


def after_ms(m, to, after, now):
    """(ms, Czech words) for --after: HH:MM, or `limit` = when the target's account gets its limit back (the weekly
    reset when the week is spent, else the 5-hour one). Refused when the usage cache does not know."""
    JA = _ja()
    if JA is None:
        raise Refused("odložené odeslání teď nejde (modul jarvisalert se nenačetl); pošli bez --after")
    if after != "limit":
        try:
            ms = JA.parse_at(after, now)
        except ValueError as e:
            raise Refused(f"--after je limit nebo HH:MM ({e})")
        return ms, f"do {JA.when_cz(ms, now)}"
    name, why = m.resolve_name(to.strip() if isinstance(to, str) else "")
    if why:
        raise Refused(why)
    s = JW.sessions_now(m.env, now).get(name) or {}
    acct = s.get("account") or "default"
    u = J.read_usage(m.env, now).get(acct) or {}
    if not u or (u.get("ageS") or 0) > 3 * 3600:
        raise Refused(f"o limitu účtu {J.account_short(s.get('account'))} nemám čerstvá data, řekni čas: --after HH:MM")
    week = (u.get("sevenDay") or 0) >= WEEK_SPENT
    ms = J._ts_ms(u.get("sevenDayResets") if week else u.get("fiveHourResets"))
    if not ms or ms <= now:
        raise Refused("nevím, kdy se limit obnoví; řekni čas: --after HH:MM")
    return ms, f"do obnovy {'týdenního' if week else 'pětihodinového'} limitu ({JA.when_cz(ms, now)})"


def cmd_send(m, a, out):
    nb, after_cz = (None, None)
    if a.after:
        nb, after_cz = after_ms(m, a.to, a.after, m.env.now_ms())
    rec, why = m.send(a.to, a.text, task=a.task, not_before=nb, after_cz=after_cz)
    if why:
        raise Refused(why)
    later = f" {after_cz}" if after_cz else ""
    out.write(f"{rec['id']} navržena pro {J.short_name(rec['to'])}{(', odložená' + later) if later else ''}. "
              f"Zeptej se Lukáše: „Pošlu to do {J.short_name(rec['to'])}{later}. Ano?“ Odejde jen po jeho ano "
              f"v příští zprávě (do 10 minut), jinak se zahodí.\n")
    c = rec.get("conflict")
    if c:
        # E1: only information; Lukáš decides with it in front of him
        out.write(f"Pozor, konflikt checkoutu: {JW.conflict_text(c)}.\n"
                  f"V otázce to zmiň, třeba: „{JW.conflict_say(c)}“\n")


def cmd_status(m, a, out):
    now = m.env.now_ms()
    if a.id:
        r = m.get(a.id)
        if not r:
            raise Refused(f"zpráva {a.id} neexistuje")
        if a.json:
            d = dict(r, reply=M.data_text(r.get("reply"), M.REPLY_MAX))
            out.write(json.dumps(d, ensure_ascii=False, indent=1) + "\n")
            return
        out.write(f"[cr-mail: {M.DATA_NOTE}]\n{_row(r, now)}\n")
        if r.get("reason"):
            out.write(f"důvod: {r['reason']}\n")
        if r.get("conflict"):
            out.write(f"pozor při odeslání: {JW.conflict_text(r['conflict'])}\n")
        if r.get("reply"):
            how = "výslovná" if r.get("replyBy") == "explicit" else "poslední zpráva tahu"
            out.write(f"odpověď ({how}, {_age(now, r.get('replyAt'))}): «{M.data_text(r['reply'], 3000)}»\n")
        return
    recs = [r for r in m.all() if r["state"] in M.ACTIVE]
    if a.json:
        # F3: the app's proposal card shows the target and the text; texts are Jarvis's, escaped as data anyway
        rows = [dict(r, short=J.short_name(r["to"]), text=M.data_text(m.text_of(r["id"]), M.TEXT_MAX)
                     if r["state"] in ("proposed", "queued") else None) for r in recs]
        out.write(json.dumps({"off": m.off(), "messages": rows}, ensure_ascii=False, indent=1) + "\n")
        return
    head = "[cr-mail" + (" · VYPNUTO (cr-mail on zapne)" if m.off() else "") + f": {M.DATA_NOTE}]"
    out.write(head + "\n" + ("\n".join(_row(r, now) for r in recs) if recs else "žádná rozpracovaná pošta") + "\n")


def cmd_inbox(m, a, out):
    now = m.env.now_ms()
    recs = [r for r in m.all() if r["state"] in ("replied", "uncertain", "expired")
            and (a.all or now - J._int(r.get("updatedAt")) < 24 * 3600 * 1000)]
    recs.sort(key=lambda r: -J._int(r.get("updatedAt")))
    if a.json:
        out.write(json.dumps([dict(r, reply=M.data_text(r.get("reply"), M.REPLY_MAX)) for r in recs],
                             ensure_ascii=False, indent=1) + "\n")
        return
    lines = [f"[cr-mail inbox: {M.DATA_NOTE}]"]
    for r in recs:
        lines.append(_row(r, now))
        if r.get("reply"):
            lines.append(f"  «{M.data_text(r['reply'], 300)}»")
    if len(lines) == 1:
        lines.append("nic")
    out.write("\n".join(lines) + "\n")


def cmd_cancel(m, a, out):
    why = m.cancel(a.id)
    if why:
        raise Refused(why)
    out.write(f"{a.id} zrušena\n")


def cmd_reply(m, a, out):
    why = m.reply(a.id, a.text)
    if why:
        raise Refused(why)
    out.write(f"odpověď na {a.id} uložena\n")


def cmd_off(m, a, out):
    m.ensure()
    J.atomic_write(m.env.path("off"), json.dumps({"at": m.env.now_ms(), "by": "cr-mail off"}) + "\n")
    with m.lock():
        m.log("off")
    out.write("Pošta je vypnutá: nic se nevkládá, fronta čeká. Zapne ji jen Lukáš: cr-mail on\n")


def cmd_on(m, a, out):
    # inside any Claude session (Jarvis included) CLAUDECODE=1: the switch back is Lukáš's own act
    if m.env.environ.get("CLAUDECODE"):
        raise Refused("cr-mail on smí jen Lukáš ze svého shellu, ne Claude session")
    try:
        os.unlink(m.env.path("off"))
    except FileNotFoundError:
        pass
    with m.lock():
        m.log("on")
    out.write("Pošta je zapnutá.\n")


def decide_refusal(m, a):
    """None, or why this caller may not approve/reject. A guard against MISTAKES, not a wall (rev-f3 LOW4, F2a
    decision 5): every session runs under the same uid, so a determined one could unset CLAUDECODE or write the queue
    itself. What it stops: Jarvis (the gate never lets him call it, and his pane is refused here), and any Claude
    session that runs `cr-mail approve` as it is (CLAUDECODE set), i.e. a working session "saying ano" by accident."""
    if m.env.environ.get("CLAUDECODE"):
        return f"cr-mail {a.cmd} je Lukášovo rozhodnutí z aplikace, ne z Claude session"
    me = J.read_self(m.env)
    name, why = m.caller_session()
    if not why and me and name == me:
        return f"cr-mail {a.cmd} nejde z Jarvisova panu"
    if not BY_RE.fullmatch(a.by or ""):
        return "--by je app:<installId> (písmena, číslice, . _ -)"
    return None


def cmd_decide(m, a, out):
    why = decide_refusal(m, a)
    if why:
        raise Refused(why)
    if not M.ID_RE.fullmatch(a.id):
        raise Refused("id zprávy je m-…")
    why = m.decide_by_app(a.id, a.cmd == "approve", a.by)
    if why:
        raise Refused(why)
    out.write(f"{a.id} {'schválena, je ve frontě' if a.cmd == 'approve' else 'zahozena'}\n")


def cmd_audit(m, a, out):
    try:
        span = JW.parse_since(a.since, 3600 * 1000)
    except ValueError as e:
        raise Refused(str(e))
    JW.write_audit(out, JW.audit(m.env, m.env.now_ms() - span), a, span)


def cmd_prompt_seen(m, a, out):
    try:
        payload = json.loads(sys.stdin.read(1024 * 1024) or "null")
    except ValueError:
        payload = None
    why = m.prompt_seen(payload, m.env.now_ms())
    if why:
        raise Refused(why)


def main(argv=None, env=None, out=None, err=None, mail=None):
    out, err = out or sys.stdout, err or sys.stderr
    ap = argparse.ArgumentParser(prog="cr-mail", description="Jarvis mail (F2a).")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("send")
    p.add_argument("--to", required=True)
    p.add_argument("--task")
    p.add_argument("--after")
    p.add_argument("text")
    for c in ("approve", "reject"):
        p = sub.add_parser(c)
        p.add_argument("id")
        p.add_argument("--by", required=True)
    p = sub.add_parser("status")
    p.add_argument("id", nargs="?")
    p.add_argument("--json", action="store_true")
    p = sub.add_parser("inbox")
    p.add_argument("--all", action="store_true")
    p.add_argument("--json", action="store_true")
    p = sub.add_parser("cancel")
    p.add_argument("id")
    p = sub.add_parser("reply")
    p.add_argument("id")
    p.add_argument("text")
    p = sub.add_parser("audit")
    p.add_argument("--since")
    p.add_argument("--json", action="store_true")
    p.add_argument("--say", action="store_true")
    sub.add_parser("off")
    sub.add_parser("on")
    sub.add_parser("prompt-seen")
    a = ap.parse_args(argv)
    env = env or J.Env()
    m = mail or M.Mail(env)
    fn = {"send": cmd_send, "status": cmd_status, "inbox": cmd_inbox, "cancel": cmd_cancel, "reply": cmd_reply,
          "off": cmd_off, "on": cmd_on, "prompt-seen": cmd_prompt_seen, "audit": cmd_audit, "approve": cmd_decide,
          "reject": cmd_decide}[a.cmd]
    old = os.umask(0o077)
    try:
        fn(m, a, out)
    except Refused as e:
        err.write(f"cr-mail: {e}\n")
        return 2
    finally:
        os.umask(old)
    return 0


if __name__ == "__main__":
    sys.exit(main())
