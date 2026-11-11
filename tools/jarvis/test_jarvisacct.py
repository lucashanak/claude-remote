"""Jarvis F5d: the default login's usage, the autopick of Jarvis's account (decide on the server, switch in the app
through switch-request.json / switch-result.json) and the folder-trust dialog of a claude without a registry record.

Fake box of jarvis_fixtures + the in-memory tmux of test_jarvismail. Nothing here calls a real tmux, reads a real
credentials file or deletes outside its own TemporaryDirectory. MutationTest switches each gate off.
"""
import io
import json
import os
import sys
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import cr_digest  # noqa: E402
import jarvis_d  # noqa: E402
import jarvisacct as JC  # noqa: E402
import jarvisalert as JA  # noqa: E402
import jarvislib as J  # noqa: E402
import jarvismail as M  # noqa: E402
import test_jarvismail as TM  # noqa: E402
from jarvis_fixtures import T0  # noqa: E402

JARVIS, TARGET = TM.JARVIS, TM.TARGET
MIN = 60 * 1000
A, B, C = "a@alfa.cz", "b@beta.cz", "c@gama.cz"

# what `capture-pane -p` showed on 2026-11-11 on `claude-server-~-yolo` (8 sessions like it)
TRUST_SCREEN = """────────────────────────────────────────────────────────────────────────────────
 Accessing workspace:
 /home/lucas
 Quick safety check: Is this a project you created or one you trust? (Like your
 own code, a well-known open source project, or work from your team). If not,
 take a moment to review what's in this folder first.
 Claude Code'll be able to read, edit, and execute files here.
 Security guide
 \x1b[36m❯\x1b[39m No, exit
   Yes, I trust this folder
 Enter to confirm · Esc to cancel"""


class Base(TM.Base):
    def setUp(self):
        super().setUp()
        self.prompt(age_s=900)  # Lukáš's last prompt long ago: no conversation
        for slug, mail in ((A, A), (B, B), (C, C)):
            d = self.fx.account_dir(slug)
            with open(os.path.join(d, ".claude.json"), "w") as f:
                json.dump({"oauthAccount": {"emailAddress": mail}, "projects": {}}, f)
        with open(os.path.join(self.fx.home, ".claude.json"), "w") as f:
            json.dump({"oauthAccount": {"emailAddress": "lukas@default.cz"}}, f)
        self.w = JC.AccountWorker(self.env, mail=self.m)
        self.cur = A

    def slots(self, *lines):
        with open(os.path.join(self.fx.state, "accounts"), "w") as f:
            f.write("".join(x + "\n" for x in lines))

    def jarvis(self, **kw):
        return dict({"name": JARVIS, "self": True, "account": None if self.cur == "default" else self.cur,
                     "sessionId": "0000000b-0000-4000-8000-000000000000", "state": "idle", "pid": 11,
                     "verified": True}, **kw)

    def tick(self, wake_open=False, **kw):
        return self.w.tick({JARVIS: self.jarvis(**kw)}, JARVIS, self.fx.clock.ms, self.fold(), None, wake_open)

    def usage(self, slug, five, seven, age_s=0):
        self.fx.usage(".claude" if slug == "default" else slug, five, seven,
                      fetched_s=self.fx.clock.ms // 1000 - age_s)

    def request(self):
        return J.read_json(self.env.path(JC.REQUEST))

    def setup_switch(self):
        self.slots(A, B, "default")
        self.usage(A, 82, 50)
        self.usage(B, 10, 40)
        self.usage("default", 30, 70)


class FlowBase(Base):
    def requested(self):
        self.setup_switch()
        self.tick()
        req = self.request()
        self.assertIsNotNone(req)
        self.assertRegex(req["id"], JC.REQ_ID_RE.pattern)
        return req

    def app_answers(self, req, ok=True):
        os.rename(self.env.path(JC.REQUEST), self.env.path(f"switch-request.{req['id']}.taken"))
        J.atomic_write(self.env.path(JC.RESULT), json.dumps({"id": req["id"], "by": "app:test", "at": self.fx.clock.ms,
                                                             "ok": ok, "error": None if ok else "busy"}))

    def jsay(self):
        try:
            with open(self.env.alerts_path) as f:
                return [a for a in map(JA.parse_alert, f) if a and a.get("kind") == "jsay"]
        except FileNotFoundError:
            return []


class UsageTest(Base):
    def test_the_default_login_is_read_with_its_email(self):
        self.usage("default", 12, 34)
        u = J.read_usage(self.env, self.fx.clock.ms).get("default") or {}
        self.assertEqual((u.get("fiveHour"), u.get("email")), (12, "lukas@default.cz"))
        self.usage(A, 1, 2)
        self.assertEqual(J.read_usage(self.env, self.fx.clock.ms)[A]["email"], A)
        out = io.StringIO()
        cr_digest.main(["usage"], env=self.env, out=out)
        self.assertIn("výchozí (lukas@default.cz) 5h 12 %", out.getvalue())

    def test_the_email_never_comes_from_credentials(self):
        d = self.fx.account_dir(B)
        with open(os.path.join(d, ".credentials.json"), "w") as f:
            f.write('{"oauthAccount": {"emailAddress": "secret@x.cz"}}')
        with mock.patch("builtins.open", wraps=open) as op:
            J._EMAIL_CACHE.clear()
            J.account_email(self.env, B)
        self.assertFalse(any(".credentials" in str(c.args[0]) for c in op.call_args_list))


class SlotsTest(Base):
    def test_fewer_than_two_valid_slots_is_off(self):
        self.usage(A, 95, 95)
        self.slots(A, "../etc", "/abs/path", "nobody@x.cz", A)
        self.tick()
        self.assertEqual(self.w.summary["autopick"], "off: méně než 2 účty")
        self.assertIsNone(self.request())
        self.assertEqual(JC.read_slots(self.env), [A])
        self.slots(A, "default")
        self.assertEqual(JC.read_slots(self.env), [A, "default"])


class DecideTest(Base):
    def test_threshold_80_5h_or_90_7d(self):
        self.slots(A, B)
        self.usage(B, 10, 10)
        for five, seven, want in ((79, 89, False), (80, 10, True), (10, 90, True)):
            self.w = JC.AccountWorker(self.env, mail=self.m)
            self.usage(A, five, seven)
            self.tick()
            self.assertEqual(self.request() is not None, want, (five, seven))
            if self.request():
                os.unlink(self.env.path(JC.REQUEST))

    def test_the_best_candidate_fresh_under_50_and_85(self):
        self.slots(A, B, C, "default")
        self.usage(A, 90, 90)
        self.usage(B, 40, 60)               # max 60
        self.usage(C, 20, 30, age_s=700)    # the best, but its numbers are 11 min old
        self.usage("default", 55, 10)       # 5 h too high
        self.tick()
        self.assertIsNotNone(self.request())
        self.assertEqual(self.request()["to"], B)
        self.assertEqual(self.request()["from"], A)
        self.assertEqual(self.request()["claudeSessionId"], "0000000b-0000-4000-8000-000000000000")
        self.assertEqual(self.request()["tmux"], JARVIS)

    def test_stale_numbers_of_his_own_slot_never_move_him(self):
        self.slots(A, B)
        self.usage(A, 99, 99, age_s=700)
        self.usage(B, 1, 1)
        self.tick()
        self.assertIsNone(self.request())

    def test_nowhere_to_go_the_saver_goes_on_and_the_delta_says_it_once(self):
        self.slots(A, B)
        self.usage(A, 90, 90)
        self.usage(B, 60, 10)
        self.tick()
        self.assertIsNone(self.request())
        self.assertEqual(self.w.summary["noCandidate"], self.fx.clock.ms)
        st = J.snapshot(self.env, ancestor_self=False)
        st.update(d_heartbeat=self.fx.clock.ms, gen=1, jarvisAccount=self.w.summary)
        J.atomic_write(self.env.path("status.json"), json.dumps(st))
        for want in (True, False):
            out = io.StringIO()
            cr_digest.main(["delta"], env=self.env, out=out)
            self.assertEqual("žádný jiný z účtů pro Jarvise" in out.getvalue(), want)


class HoldTest(Base):
    def held(self, why, **kw):
        self.tick(**kw)
        self.assertIsNone(self.request(), why)
        self.assertIn("čeká", self.w.summary["autopick"])

    def test_at_most_one_switch_in_2_h_and_one_request_in_30_min(self):
        self.setup_switch()
        self.w.s["lastSwitch"] = {"at": self.fx.clock.ms - 119 * MIN, "from": B, "to": A}
        self.held("2 h")
        self.w.s["lastSwitch"] = None
        self.w.s["lastRequestAt"] = self.fx.clock.ms - 29 * MIN
        self.held("30 min")

    def test_never_during_a_conversation(self):
        self.setup_switch()
        self.prompt(age_s=60)
        self.held("prompt")
        self.prompt(age_s=900)
        self.tm.panes["%1"].activity.append(self.fx.clock.ms // 1000 - 30)
        self.held("keyboard")

    def test_never_with_a_proposal_an_open_wake_mail_off_or_input(self):
        self.setup_switch()
        self.held("wake", wake_open=True)
        self.prompt()
        self.send(approve=False)
        self.prompt(age_s=900)
        self.held("proposal")
        for r in self.m.all():
            with self.m.lock():
                self.m.transition(r, "dropped")
        self.tm.panes["%1"].box = "rozepsané"
        self.held("draft")
        self.tm.panes["%1"].box = ""
        open(self.env.path("off"), "w").close()
        self.held("off")
        os.unlink(self.env.path("off"))
        self.tick()
        self.assertIsNotNone(self.request())


class SwitchFlowTest(FlowBase):
    def test_ok_then_verified_on_the_new_slot_then_lastswitch_and_jsay(self):
        req = self.requested()
        self.assertEqual(self.w.summary["request"]["id"], req["id"])
        self.app_answers(req)
        self.fx.clock.advance(5)
        self.tick()  # the result is read; Jarvis still shows the old slot
        self.assertIsNone(self.w.s["lastSwitch"])
        self.assertEqual(self.jsay(), [])
        self.cur = B
        self.fx.clock.advance(20)
        self.tick()
        self.assertEqual(self.w.s["lastSwitch"]["to"], B)
        self.assertEqual([a["text"] for a in self.jsay()], [f"Přešel jsem na účet {B} (40 % týdne), {A} došel."])
        self.assertFalse(os.path.exists(self.env.path(JC.RESULT)))

    def test_ok_but_jarvis_never_runs_on_the_new_slot_is_no_switch(self):
        req = self.requested()
        self.app_answers(req)
        self.tick()
        self.fx.clock.advance(4 * 60)
        self.tick()
        self.assertIsNone(self.w.s["lastSwitch"])
        self.assertIsNone(self.w.s["verify"])
        self.assertEqual(self.jsay(), [])

    def test_a_failed_switch_is_no_switch(self):
        req = self.requested()
        self.app_answers(req, ok=False)
        self.tick()
        self.assertIsNone(self.w.s["verify"])
        self.assertIsNone(self.w.s["lastSwitch"])

    def test_a_request_nobody_took_goes_after_10_min(self):
        self.requested()
        self.fx.clock.advance(11 * 60)
        self.setup_switch()  # fresh numbers again: the need is still there
        self.tick()
        self.assertIsNone(self.request())
        self.assertIsNone(self.w.s["request"])
        self.assertIn("30 min", self.w.summary["autopick"])  # and the next one waits its 30 minutes

    def test_status_json_carries_jarvis_account(self):
        self.setup_switch()
        fx = self.fx
        os.unlink(os.path.join(fx.home, ".claude", "sessions", "11.json"))  # Jarvis runs on slot A, not the default
        fx.registry(JARVIS, 11, "%1", account=A, status_at=T0 - 60000)
        d = jarvis_d.Daemon(self.make_env())  # a fresh env sees the account dirs made in setUp
        d.mail.mail.tmux = self.tm
        d.tick()
        st = d.tick()
        ja = st["jarvisAccount"]
        self.assertEqual((ja["slot"], ja["email"]), (A, A))
        self.assertEqual([c["slot"] for c in ja["candidates"]], [A, B, "default"])


class TrustTest(Base):
    def noreg(self, screen):
        fx = self.fx
        fx.pane("%9", 70, "claude-server-trust")
        fx.ptable = {70: (1, "bash"), 71: (70, "claude")}
        fx.screens["%9"] = screen
        return J.snapshot(fx.env())["sessions"]["claude-server-trust"]

    def test_the_trust_dialog_is_waiting_for_lukas(self):
        s = self.noreg(TRUST_SCREEN)
        self.assertEqual((s["state"], s["waitingFor"]), ("waiting", J.TRUST_CZ))
        self.assertEqual((s["attention"]["kind"], s["attention"]["text"]), ("waiting", J.TRUST_CZ))

    def test_similar_text_is_not_the_dialog(self):
        quoted = TM.screen(extra_rows=[" claude ukázal: Yes, I trust this folder a No, exit; Accessing workspace:",
                                      " Enter to confirm"])
        for screen in (quoted, TRUST_SCREEN.replace("Enter to confirm", "Esc"),
                       TRUST_SCREEN.replace("Accessing workspace:", "").replace("Quick safety check", "Quick"),
                       TM.screen(selector=True)):
            s = self.noreg(screen)
            self.assertNotEqual(s["state"], "waiting", screen[-80:])
        self.assertEqual(self.noreg(TM.screen(selector=True))["state"], "unknown")

    def test_a_plain_prompt_is_idle_nothing_readable_is_unknown(self):
        self.assertEqual(self.noreg(TM.screen())["state"], "idle")
        self.assertEqual(self.noreg(None)["state"], "unknown")

    def test_the_screen_is_read_at_most_every_30_s(self):
        fx = self.fx
        fx.pane("%9", 70, "claude-server-trust")
        fx.ptable = {70: (1, "bash"), 71: (70, "claude")}
        calls = []
        env = fx.env(capture_pane=lambda p: calls.append(p) or TRUST_SCREEN)
        cache = {}
        for _ in range(3):
            J.snapshot(env, tr_cache=cache)
        fx.clock.advance(31)
        J.snapshot(env, tr_cache=cache)
        self.assertEqual(calls, ["%9", "%9"])

    def test_it_is_told_as_a_block_after_its_grace(self):
        s = self.noreg(TRUST_SCREEN)
        w = JA.AlertWorker(self.env, mail=self.m, presence=lambda: None)
        open(os.path.join(self.fx.state, JA.WAKE_OFF), "w").close()
        s["attention"]["since"] = self.fx.clock.ms - 20 * MIN
        w.tick([], {"claude-server-trust": s}, self.fold(), self.fx.clock.ms, JARVIS)
        with open(self.env.alerts_path) as f:
            texts = [a["text"] for a in map(JA.parse_alert, f) if a and "id" in a]
        self.assertEqual(texts, ["trust čeká na tebe."])


class Review2Test(FlowBase):
    """rev-f5d-srv: MED1 (verify by the config dir, every ok holds 2 h), MED2 (a limit never blocks the switch),
    LOW (a claimed request waits for its answer, a fresh dialog is fresh, no wake while a switch waits, hysteresis)."""

    def test_med1_the_slot_comes_from_the_config_dir_not_the_manifest(self):
        default_cfg = os.path.join(self.fx.home, ".claude")
        s = self.jarvis(account=A, cfgDir=default_cfg)  # the manifest still names A
        self.assertEqual(JC.slot_of(s, self.env), "default")
        self.assertEqual(JC.slot_of(self.jarvis(cfgDir=self.fx.account_dir(B)), self.env), B)

    def test_med1_a_switch_to_default_verifies(self):
        self.slots(A, "default")
        self.usage(A, 82, 50)
        self.usage("default", 10, 20)
        self.tick()
        req = self.request()
        self.assertEqual(req["to"], "default")
        self.app_answers(req)
        self.tick()
        self.w.tick({JARVIS: self.jarvis(account=A, cfgDir=os.path.join(self.fx.home, ".claude"))}, JARVIS,
                    self.fx.clock.ms, self.fold(), None, False)
        self.assertEqual((self.w.s["lastSwitch"] or {}).get("to"), "default")

    def test_med1_an_unverified_ok_still_holds_2_h(self):
        req = self.requested()
        self.app_answers(req)
        self.tick()
        self.fx.clock.advance(31 * 60)  # verify gave up long ago; the request gap is over too
        self.setup_switch()
        self.tick()
        self.assertIsNone(self.request())
        self.assertIn("2 h", self.w.summary["autopick"])

    def test_med2_his_own_limit_never_blocks_the_switch(self):
        st = {"v": 1, "gen": 1, "at": self.fx.clock.ms, "d_heartbeat": self.fx.clock.ms,
              "sessions": {JARVIS: {"limit": {"ms": self.fx.clock.ms - MIN, "text": "limit"}}}}
        J.atomic_write(self.env.path("status.json"), json.dumps(st))
        self.assertEqual(self.m.probe(JARVIS, self.fold(), self.fx.clock.ms), "limit")
        self.requested()

    def test_low_a_claimed_request_waits_for_its_answer(self):
        req = self.requested()
        self.fx.clock.advance(9 * 60)
        os.rename(self.env.path(JC.REQUEST), self.env.path(f"switch-request.{req['id']}.taken"))
        self.tick()
        self.fx.clock.advance(2 * 60)  # 11 min: past the original expiry
        self.tick()
        self.assertIsNotNone(self.w.s["request"])
        J.atomic_write(self.env.path(JC.RESULT), json.dumps({"id": req["id"], "ok": True, "by": "app:x"}))
        self.tick()
        self.assertIsNotNone(self.w.s["verify"])
        self.assertFalse(os.path.exists(self.env.path(f"switch-request.{req['id']}.taken")))

    def test_low_a_fresh_trust_dialog_is_fresh(self):
        fx = self.fx
        fx.pane("%9", 70, "claude-server-trust")
        fx.ptable = {70: (1, "bash"), 71: (70, "claude")}
        fx.screens["%9"] = TRUST_SCREEN
        fx.append_events(TM.ev_line("stop", "claude-server-trust", 1000, 50))  # its previous life, long ago
        with mock.patch.object(J, "_proc_start_ms", return_value=fx.clock.ms - 30000):
            s = J.snapshot(fx.env())["sessions"]["claude-server-trust"]
        self.assertEqual(s["state"], "waiting")
        self.assertEqual(s["stateSince"], fx.clock.ms - 30000)

    def test_low_no_wake_while_a_switch_waits(self):
        w = JA.AlertWorker(self.env, mail=self.m, presence=lambda: None)
        self.assertIsNone(w.wake_hold(self.fx.clock.ms, JARVIS))
        self.requested()
        self.assertEqual(w.wake_hold(self.fx.clock.ms, JARVIS), "switch")

    def test_low_one_odd_screen_never_flips_the_state(self):
        env = self.fx.env(capture_pane=lambda p: frames.pop(0))
        frames = [TM.screen(), TM.screen(selector=True), TM.screen(), TM.screen(selector=True), TM.screen(selector=True)]
        cache, got = {}, []
        for _ in range(5):
            got.append(J.screen_state(env, "%9", cache, self.fx.clock.ms))
            self.fx.clock.advance(31)
        self.assertEqual(got, ["idle", "idle", "idle", "idle", None])


class DownTest(FlowBase):
    """Lead 2026-11-11: a typefail, or Jarvis's pane without claude after ANY answer (ok, false, null, or a claim
    that never answered), is told to Lukáš as `jblock` (he is offline: never a wake). `ok: null` = unknown: the
    process decides whether a switch happened."""

    def blocks(self):
        try:
            with open(self.env.alerts_path) as f:
                return [a for a in map(JA.parse_alert, f) if a and a.get("kind") == "jblock"]
        except FileNotFoundError:
            return []

    def answer(self, req, **res):
        os.rename(self.env.path(JC.REQUEST), self.env.path(f"switch-request.{req['id']}.taken"))
        J.atomic_write(self.env.path(JC.RESULT), json.dumps(dict({"id": req["id"], "by": "app:test"}, **res)))

    def test_typefail_is_told_at_once(self):
        req = self.requested()
        self.answer(req, ok=False, error="typefail")
        self.tick(state="dead", pid=None)
        self.assertEqual([(a["text"], a["name"]) for a in self.blocks()], [(JC.DOWN_TEXT, JARVIS)])
        self.fx.clock.advance(120)
        self.tick(state="dead", pid=None)
        self.assertEqual(len(self.blocks()), 1)  # once

    def test_down_after_any_answer_is_told_after_a_minute(self):
        for res in ({"ok": True}, {"ok": None}, {"ok": False, "error": "busy"}):
            self.setUp()
            req = self.requested()
            self.answer(req, **res)
            self.tick(state="dead", pid=None)
            self.assertEqual(self.blocks(), [], res)
            self.fx.clock.advance(61)
            self.tick(state="dead", pid=None)
            self.assertEqual(len(self.blocks()), 1, res)
            self.assertIsNone(self.w.s["lastSwitch"])

    def test_a_claim_without_an_answer_and_a_dead_pane_is_told(self):
        req = self.requested()
        os.rename(self.env.path(JC.REQUEST), self.env.path(f"switch-request.{req['id']}.taken"))
        self.tick()
        self.fx.clock.advance(11 * 60)
        self.tick(state="dead", pid=None)
        self.fx.clock.advance(61)
        self.tick(state="dead", pid=None)
        self.assertEqual(len(self.blocks()), 1)

    def test_ok_null_decides_by_the_process(self):
        req = self.requested()
        self.answer(req, ok=None)
        self.cur = B
        self.tick()
        self.assertEqual((self.w.s["lastSwitch"] or {}).get("to"), B)
        self.setUp()
        req = self.requested()
        self.answer(req, ok=None)
        self.tick()  # still on A
        self.fx.clock.advance(4 * 60)
        self.tick()
        self.assertIsNone(self.w.s["lastSwitch"])
        self.assertEqual(self.blocks(), [])


class MutationTest(unittest.TestCase):
    MUTANTS = {
        "dotfile-usage": (lambda: _mut(J, "read_usage", "if os.path.isfile(dot):", "if False:"), UsageTest),
        "fewer-than-2": (lambda: _mut(JC, "AccountWorker", "if len(slots) < 2:", "if len(slots) < 1:"), SlotsTest),
        "threshold": (lambda: _both(mock.patch.object(JC, "SWITCH_5H", 101), mock.patch.object(JC, "SWITCH_7D", 101)),
                      DecideTest),
        "stale-candidate": (lambda: mock.patch.object(JC, "FRESH_S", 10 ** 9), DecideTest),
        "candidate-limits": (lambda: mock.patch.object(JC, "CAND_5H", 101), DecideTest),
        "gap-2h": (lambda: mock.patch.object(JC, "SWITCH_GAP_MS", 0), HoldTest),
        "gap-30min": (lambda: mock.patch.object(JC, "REQUEST_GAP_MS", 0), HoldTest),
        "conversation": (lambda: mock.patch.object(JC, "CONVERSATION_MS", 0), HoldTest),
        "holds": (lambda: mock.patch.object(JC.AccountWorker, "hold", lambda self, *a: None), HoldTest),
        "verify": (lambda: _mut(JC, "AccountWorker", 'if slot_of(me, self.env) == ver["to"] and',
                                'if True or slot_of(me, self.env) == ver["to"] and'), SwitchFlowTest),
        # rev-f5d-srv round 2
        "MED1-cfgdir": (lambda: mock.patch.object(JC, "slot_of", lambda s, env=None: (s or {}).get("account") or
                                                  "default"), Review2Test),
        "MED1-ok-holds": (lambda: _mut(JC, "AccountWorker", " or now - self.s[\"lastOkAt\"] < SWITCH_GAP_MS", ""),
                          Review2Test),
        "MED2-limit": (lambda: _mut(JC, "AccountWorker", "ignore_limit=True", "ignore_limit=False"), Review2Test),
        "LOW-taken": (lambda: _mut(JC, "AccountWorker", "if taken and not req.get(\"taken\"):", "if False:"),
                      Review2Test),
        "LOW-trust-born": (lambda: _mut(J, "build_sessions", "if born and born > (s.get(\"stateSince\") or 0):",
                                        "if False:"), Review2Test),
        "LOW-wake-switch": (lambda: mock.patch.object(JC, "switch_pending", lambda env, now, window_ms=0: False),
                            Review2Test),
        "down-alert": (lambda: mock.patch.object(JC.AccountWorker, "_down", lambda self, *a: None), DownTest),
        "down-typefail": (lambda: _mut(JC, "AccountWorker", 'if ok is False and res.get("error") == "typefail":',
                                       "if False:"), DownTest),
        "down-after-any": (lambda: _mut(JC, "AccountWorker", "elif not running and now - _i(ver.get(\"since\")) >= "
                                        "DOWN_GRACE_MS:", "elif False:"), DownTest),
        "down-no-result": (lambda: _mut(JC, "AccountWorker", "if req.get(\"taken\"):\n", "if False:\n"), DownTest),
        "LOW-hysteresis": (lambda: _mut(J, "screen_state", "    reported = res\n    if hit and hit[1] != res:",
                                        "    reported = res\n    if False:"), Review2Test),
        "expiry": (lambda: mock.patch.object(JC, "REQUEST_TTL_MS", 10 ** 12), SwitchFlowTest),
        "trust-marks": (lambda: mock.patch.object(J, "TRUST_MARKS", J.TRUST_MARKS[:2]), TrustTest),
        "blind-idle": (lambda: _mut(J, "build_sessions", 'elif seen == "idle":', "else:"), TrustTest),
        "screen-ttl": (lambda: mock.patch.object(J, "SCREEN_TTL_MS", 0), TrustTest),
    }

    def test_every_gate_fails_without_it(self):
        for name, (patcher, cls) in self.MUTANTS.items():
            suite = unittest.defaultTestLoader.loadTestsFromTestCase(cls)
            with patcher():
                res = unittest.TestResult()
                suite.run(res)
            self.assertGreater(len(res.failures) + len(res.errors), 0, f"{name}: {cls.__name__} passed the mutant")


def _mut(mod, attr, old, new):
    import types
    with open(mod.__file__, encoding="utf-8") as f:
        src = f.read()
    assert src.count(old) == 1, (mod.__name__, old)
    m = types.ModuleType(mod.__name__ + "_mutant")
    m.__file__ = mod.__file__
    exec(compile(src.replace(old, new), mod.__file__, "exec"), m.__dict__)
    return mock.patch.object(mod, attr, getattr(m, attr))


class _both:
    def __init__(self, *ps):
        self.ps = ps

    def __enter__(self):
        for p in self.ps:
            p.__enter__()

    def __exit__(self, *a):
        for p in reversed(self.ps):
            p.__exit__(*a)


if __name__ == "__main__":
    unittest.main()
