"""Jarvis F5d: Jarvis's own account, picked automatically among the slots Lukáš chose (at least two).

Lukáš 2026-11-11: "Neuděláme autopick účtu, co má hodně usage? Z nastavených v nastavení? Alespoň 2."

`<state>/accounts` (written by the app only): one slot per line, a slug of `~/.claude-remote/accounts/<slug>` or
`default` (the `~/.claude` login). Fewer than two valid lines = autopick off, everything as before.

jarvis-d (AccountWorker, once per tick after the alerts) decides; the APP switches (contract in
`f5d/00-brief.md`, "Kontrakt přepnutí"): the app already owns the one safe way (`switchSessionAccount`: its tab and
storage first, `respawn-pane -k` + `claude --resume` under the new CLAUDE_CONFIG_DIR, then its manifest push), and
the server cannot switch by itself without racing the app's own `sessions.json` push (the client wins for its own
sessions) or sending keys other than Enter into Jarvis's pane.

- When: Jarvis's slot has fresh usage (≤ 10 min) at ≥ 80 % of 5 h or ≥ 90 % of 7 d (before the saver's 85/95).
- Where to: a listed slot with fresh usage, 5 h < 50 % and 7 d < 85 %; the best has the lowest max(5 h, 7 d).
  None = the saver goes on as before, and the delta says it once (`noCandidate`).
- Only when: no switch in the last 2 h, no request in the last 30 min, mail on, no conversation (Lukáš's prompt or a
  client's input on Jarvis < 2 min), no open wake, no proposal, and the mail's probe of Jarvis passes (a live claude,
  idle and settled, an empty input box, nobody typing).
- Then `<state>/switch-request.json` (tmp + rename); the app claims it by renaming it to
  `switch-request.<id>.taken`, switches, and writes `<state>/switch-result.json`. A request nobody took in 10 min
  is removed. After `ok` jarvis-d checks that Jarvis runs again on the new slot (its registry record lives under the
  new config dir, keyed through its pane) and only then writes `lastSwitch` and the `jsay` line for Lukáš.
"""
import datetime
import json
import os
import re
import secrets

import jarvislib as J

SWITCH_5H, SWITCH_7D = 80, 90
CAND_5H, CAND_7D = 50, 85
FRESH_S = 10 * 60
SWITCH_GAP_MS = 2 * 3600 * 1000
REQUEST_GAP_MS = 30 * 60 * 1000
REQUEST_TTL_MS = 10 * 60 * 1000
VERIFY_MS = 3 * 60 * 1000
# after a result (or a claim that never answered) Jarvis must run again within this long, else Lukáš is told
DOWN_GRACE_MS = 60 * 1000
DOWN_TEXT = "Jarvis po přepnutí účtu neběží, spusť ho v aplikaci."
CONVERSATION_MS = 2 * 60 * 1000
SLOT_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._@+-]{0,119}")
REQ_ID_RE = re.compile(r"s-\d{8}T\d{6}-[0-9a-f]{4}")
REQUEST = "switch-request.json"
RESULT = "switch-result.json"


def _i(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return 0


def read_slots(env):
    """The valid slots of `<state>/accounts`, in their order, without duplicates: `default`, or a slug whose
    account dir exists. Anything else (a path, `..`, a missing account) is skipped."""
    try:
        with open(env.path("accounts"), encoding="utf-8") as f:
            raw = f.read(16384)
    except OSError:
        return []
    out = []
    for line in raw.splitlines():
        s = line.strip()
        if not s or s.startswith("#") or s in out:
            continue
        if s == "default" or (SLOT_RE.fullmatch(s) and ".." not in s
                              and os.path.isdir(os.path.join(env.cr, "accounts", s))):
            out.append(s)
    return out


def slot_of(session, env=None):
    """The slot Jarvis RUNS on. rev-f5d MED1: from the config dir of his live registry record (`cfgDir`), never from
    `account`, which falls back to the manifest's accountSlug: after a switch to `default` that still named the old
    slot, the switch never verified, and a new request came every 30 min (a restart loop)."""
    s = session or {}
    cfg = s.get("cfgDir")
    if isinstance(cfg, str) and cfg:
        cfg = os.path.realpath(cfg.rstrip("/"))
        if env is not None and cfg == os.path.realpath(J.slot_dir(env, "default")):
            return "default"
        if os.path.basename(os.path.dirname(cfg)) == "accounts":
            return os.path.basename(cfg)
        if env is not None:
            return "?" + cfg  # a config dir that is no slot (claude-local): never equal to one
    return s.get("account") or "default"


def switch_pending(env, now, window_ms=15 * 60 * 1000):
    """A switch request waits (or an app took one and is switching): jarvis-d must not wake Jarvis meanwhile, the
    restart would cut the wake's turn (rev-f5d LOW)."""
    if os.path.exists(env.path(REQUEST)):
        return True
    try:
        names = os.listdir(env.state_dir)
    except OSError:
        return False
    for n in names:
        if n.startswith("switch-request.") and n.endswith(".taken") and REQ_ID_RE.fullmatch(n[15:-6]):
            try:
                if now - os.stat(os.path.join(env.state_dir, n)).st_mtime * 1000 < window_ms \
                        and not os.path.exists(env.path(RESULT)):
                    return True
            except OSError:
                pass
    return False


def slot_name(env, slot, usage=None):
    """How Lukáš knows the slot: its e-mail, else the short name."""
    u = (usage or {}).get(slot) or {}
    return u.get("email") or J.account_email(env, None if slot == "default" else slot) or J.account_short(
        None if slot == "default" else slot)


def candidate(slot, usage):
    u = (usage or {}).get(slot) or {}
    return {"slot": slot, "email": u.get("email"), "fiveHour": u.get("fiveHour"), "sevenDay": u.get("sevenDay"),
            "ageS": u.get("ageS")}


def fresh(c):
    return c.get("ageS") is not None and c["ageS"] <= FRESH_S and isinstance(c.get("fiveHour"), (int, float)) \
        and isinstance(c.get("sevenDay"), (int, float))


def needs_switch(c):
    """Jarvis's slot is running out (fresh data only: stale numbers never move him)."""
    return fresh(c) and (c["fiveHour"] >= SWITCH_5H or c["sevenDay"] >= SWITCH_7D)


def best_candidate(cands, current):
    ok = [c for c in cands if c["slot"] != current and fresh(c) and c["fiveHour"] < CAND_5H
          and c["sevenDay"] < CAND_7D]
    return min(ok, key=lambda c: (max(c["fiveHour"], c["sevenDay"]), c["slot"])) if ok else None


def new_request_id(ms):
    t = datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).strftime("%Y%m%dT%H%M%S")
    return f"s-{t}-{secrets.token_hex(2)}"


class AccountWorker:
    """jarvis-d's half. `state` rides in d-state.json; `summary` is status.json `jarvisAccount`."""

    def __init__(self, env, state=None, mail=None, log=None):
        import jarvismail as M
        self.env = env
        self.M = M
        self.mail = mail or M.Mail(env)
        self.log = log or (lambda msg: None)
        st = state if isinstance(state, dict) else {}
        self.s = {
            "lastSwitch": st.get("lastSwitch") if isinstance(st.get("lastSwitch"), dict) else None,
            "lastRequestAt": _i(st.get("lastRequestAt")),
            "request": st.get("request") if isinstance(st.get("request"), dict) else None,
            "verify": st.get("verify") if isinstance(st.get("verify"), dict) else None,
            "noCandidate": _i(st.get("noCandidate")),
            "lastOkAt": _i(st.get("lastOkAt")),
        }
        self.summary = {}

    @property
    def state(self):
        return self.s

    # ------------------------------------------------------------------ the tick

    def tick(self, sessions, self_name, now, fold=None, usage=None, wake_open=False):
        """One pass. Returns True when the state changed (d-state.json to write)."""
        before = json.dumps(self.s, sort_keys=True)
        usage = J.read_usage(self.env, now) if usage is None else usage
        slots = read_slots(self.env)
        me = (sessions or {}).get(self_name) or {} if self_name else {}
        cur = slot_of(me, self.env) if me else None
        cands = [candidate(s, usage) for s in slots]
        self._result(now, sessions or {}, self_name, usage)
        why = self._decide(now, slots, cur, cands, usage, sessions or {}, self_name, fold, wake_open)
        cc = candidate(cur, usage) if cur else {}
        self.summary = {"slot": cur, "email": cc.get("email") or (slot_name(self.env, cur, usage) if cur else None),
                        "fiveHour": cc.get("fiveHour"), "sevenDay": cc.get("sevenDay"), "candidates": cands,
                        "autopick": why, "lastSwitch": self.s["lastSwitch"], "request": self.s["request"],
                        "noCandidate": self.s["noCandidate"] or None}
        return json.dumps(self.s, sort_keys=True) != before

    def _decide(self, now, slots, cur, cands, usage, sessions, self_name, fold, wake_open):
        """'on' (nothing to do or a request just went out), 'off: …', or why a needed switch waits."""
        if len(slots) < 2:
            return "off: méně než 2 účty"
        if not cur or not self_name:
            return "off: Jarvise nevidím"
        if not needs_switch(candidate(cur, usage)):
            self.s["noCandidate"] = 0
            return "on"
        best = best_candidate(cands, cur)
        if not best:
            if not self.s["noCandidate"]:
                self.s["noCandidate"] = now
                self.log("účet Jarvise dochází a žádný jiný ze seznamu nemá rezervu: jede úsporný režim")
            return "on: není kam přejít"
        self.s["noCandidate"] = 0
        hold = self.hold(now, sessions, self_name, fold, wake_open)
        if hold:
            return "on: čeká (" + hold + ")"
        self._request(now, sessions, self_name, cur, best, usage)
        return "on: požádáno o přepnutí"

    def hold(self, now, sessions, self_name, fold, wake_open):
        """None, or why no switch may be requested NOW."""
        if self.s["request"] or self.s["verify"]:
            return "probíhá"
        ls = self.s["lastSwitch"] or {}
        if (ls and now - _i(ls.get("at")) < SWITCH_GAP_MS) or now - self.s["lastOkAt"] < SWITCH_GAP_MS:
            # every `ok` holds 2 h, verified or not (rev-f5d MED1: an unverified one must not loop)
            return "přepnuto před méně než 2 h"
        if self.s["lastRequestAt"] and now - self.s["lastRequestAt"] < REQUEST_GAP_MS:
            return "žádost před méně než 30 min"
        if self.mail.off():
            return "pošta vypnutá"
        if wake_open:
            return "otevřené probuzení"
        lp = 0
        for p in ("last-prompt", "last-prompt.prev", "last-prompt.held"):
            d = J.read_json(self.env.path(p))
            lp = max(lp, _i(d.get("at")) if isinstance(d, dict) else 0)
        if lp and now - lp < CONVERSATION_MS:
            return "rozhovor"
        rc, out = self.mail.tmux(["list-clients", "-t", "=" + self_name, "-F", "#{client_activity}"])
        if rc != 0 or any(x.isdigit() and now // 1000 - int(x) < CONVERSATION_MS // 1000 for x in out.split()):
            return "rozhovor"
        if self.mail._pending():
            return "návrh"
        if not (sessions.get(self_name) or {}).get("sessionId"):
            return "neznám jeho konverzaci"
        # rev-f5d MED2: his own account's limit is exactly when he must move; the rest of the probe still holds
        t = self.mail.probe(self_name, fold, now, ignore_limit=True)
        if isinstance(t, str):
            return "vstup " + t  # busy, a draft, someone typing, copy-mode … (the mail's I1-I5)
        return None

    def _request(self, now, sessions, self_name, cur, best, usage):
        me = sessions.get(self_name) or {}
        cu = candidate(cur, usage)
        rid = new_request_id(now)
        why = (f"{slot_name(self.env, cur, usage)} je na {cu['fiveHour']:.0f} % za 5 h a {cu['sevenDay']:.0f} % za "
               f"týden, {slot_name(self.env, best['slot'], usage)} má {best['fiveHour']:.0f} % a "
               f"{best['sevenDay']:.0f} %")
        req = {"v": 1, "id": rid, "at": now, "expires": now + REQUEST_TTL_MS, "tmux": self_name,
               "claudeSessionId": me.get("sessionId"), "from": cur, "to": best["slot"], "why": why}
        self.s["request"], self.s["lastRequestAt"] = req, now
        J.atomic_write(self.env.path(REQUEST), json.dumps(req, ensure_ascii=False))
        with self.mail.lock():
            self.mail.log("acct-request", id=rid, frm=cur, to=best["slot"])

    # ------------------------------------------------------------------ the app's answer

    def _result(self, now, sessions, self_name, usage):
        req, ver = self.s["request"], self.s["verify"]
        if req:
            res = J.read_json(self.env.path(RESULT))
            if isinstance(res, dict) and res.get("id") == req.get("id"):
                self._unlink(RESULT)
                self.s["request"] = None
                with self.mail.lock():
                    self.mail.log("acct-result", id=req["id"], ok=res.get("ok") if res.get("ok") in (True, False) else None,
                                  by=str(res.get("by") or "")[:80], error=str(res.get("error") or "")[:120])
                self._unlink(f"switch-request.{req['id']}.taken")
                ok = res.get("ok")
                if ok is not False:
                    self.s["lastOkAt"] = now  # ok, or unknown (`null`): either may have restarted him; 2 h hold
                # every answer is checked against the PROCESS: ok must show the new slot, null decides by it, and
                # after any of them (a false one too) a pane without claude is told to Lukáš
                self.s["verify"] = {"id": req["id"], "from": req["from"], "to": req["to"], "why": req.get("why"),
                                    "since": now, "ok": ok, "error": str(res.get("error") or "")[:60]}
                if ok is False and res.get("error") == "typefail":
                    self._down(now, self_name, req["id"], "typefail")
                req = None  # answered: the process is checked below, in this same tick
        if req:
            taken = os.path.exists(self.env.path(f"switch-request.{req['id']}.taken"))
            if taken and not req.get("taken"):
                # rev-f5d LOW: an app claimed it (maybe in its 9th minute): its answer is waited for another
                # REQUEST_TTL_MS from the claim, never dropped at the original expiry
                req.update(taken=True, expires=max(_i(req.get("expires")), now + REQUEST_TTL_MS))
                return
            if now > _i(req.get("expires")):
                # nobody took it (no app connected) or the taker never answered: our own file goes, theirs stays
                cur = J.read_json(self.env.path(REQUEST))
                if isinstance(cur, dict) and cur.get("id") == req.get("id"):
                    self._unlink(REQUEST)
                self._unlink(f"switch-request.{req['id']}.taken")
                self.s["request"] = None
                with self.mail.lock():
                    self.mail.log("acct-expired", id=req["id"])
                if req.get("taken"):
                    # an app claimed it and never answered: it may have stopped him half way; the process decides
                    self.s["verify"] = {"id": req["id"], "from": req["from"], "to": req["to"], "why": req.get("why"),
                                        "since": now, "ok": None, "error": "no-result"}
            return
        self._prune_taken(now)
        ver = self.s["verify"]
        if ver:
            me = (sessions or {}).get(self_name) or {}
            running = bool(me.get("pid")) and me.get("state") not in ("dead", "missing", "gone")
            if ver.get("told"):
                self.s["verify"] = None  # Lukáš knows he is down (typefail): nothing more to decide
            elif slot_of(me, self.env) == ver["to"] and me.get("pid") and me.get("verified") and me.get(
                    "state") not in ("dead", "missing", "unknown", "gone"):
                self.s["verify"] = None
                self.s["lastSwitch"] = {"at": now, "from": ver["from"], "to": ver["to"], "why": ver.get("why"),
                                        "id": ver["id"]}
                self._tell(now, ver, usage)
            elif not running and now - _i(ver.get("since")) >= DOWN_GRACE_MS:
                self.s["verify"] = None
                self._down(now, self_name, ver["id"], ver.get("error") or "down")
            elif ver.get("ok") is False and running:
                self.s["verify"] = None  # the app gave up and he runs on: no switch, nothing to tell
            elif now - _i(ver.get("since")) > VERIFY_MS:
                self.s["verify"] = None
                self.log(f"přepnutí {ver['id']} se neověřilo: Jarvis neběží na {ver['to']}")
                with self.mail.lock():
                    self.mail.log("acct-unverified", id=ver["id"], to=ver["to"])

    def _down(self, now, self_name, rid, why):
        """`jblock` template: Jarvis is offline after a switch, so this is never a wake (jarvis-d writes it itself)."""
        import jarvisalert as JA
        if self.s["verify"] and self.s["verify"].get("id") == rid:
            self.s["verify"]["told"] = True
        JA.AlertFile(self.env).write(JA.alert_line(JA.new_id("a", now), "jblock", now, DOWN_TEXT, self_name))
        self.log(f"přepnutí {rid}: Jarvis neběží ({why})")
        with self.mail.lock():
            self.mail.log("acct-down", id=rid, why=str(why)[:60])

    def _tell(self, now, ver, usage):
        """The `jsay` template: "Přešel jsem na účet X (Y % týdne), Z došel." (jarvis-d writes the alerts file)."""
        import jarvisalert as JA
        u = (usage or {}).get(ver["to"]) or {}
        week = u.get("sevenDay")
        text = (f"Přešel jsem na účet {slot_name(self.env, ver['to'], usage)}"
                + (f" ({week:.0f} % týdne)" if isinstance(week, (int, float)) else "")
                + f", {slot_name(self.env, ver['from'], usage)} došel.")
        JA.AlertFile(self.env).write(JA.alert_line(JA.new_id("a", now), "jsay", now, text, None))
        with self.mail.lock():
            self.mail.log("acct-switched", id=ver["id"], frm=ver["from"], to=ver["to"])

    def _prune_taken(self, now, keep_ms=86400 * 1000):
        """Claims (`switch-request.<id>.taken`) left by an app that never answered go after a day."""
        try:
            names = os.listdir(self.env.state_dir)
        except OSError:
            return
        for n in names:
            if n.startswith("switch-request.") and n.endswith(".taken") and REQ_ID_RE.fullmatch(n[15:-6]):
                try:
                    if now - os.stat(os.path.join(self.env.state_dir, n)).st_mtime * 1000 > keep_ms:
                        os.unlink(os.path.join(self.env.state_dir, n))
                except OSError:
                    pass

    def _unlink(self, name):
        try:
            os.unlink(self.env.path(name))
        except OSError:
            pass
