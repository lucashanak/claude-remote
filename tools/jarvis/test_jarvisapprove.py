"""Jarvis F5e: approvals through Jarvis. Reading a session's dialog (fixtures captured from live sessions on
2026-11-11, words neutralised, layout and ANSI kept: `fixtures/perm-bash.ansi`, `fixtures/ask-single.ansi`; the
Edit permission, several questions and multiSelect are drawn the same way from Claude 2.1.322's own strings), the
fingerprint, the proposal, Lukáš's yes and the exact keys.

The fake box of jarvis_fixtures and the in-memory tmux of test_jarvismail, with a dialog drawn by DialogTmux: Down / Up
move its pointer, Enter answers the focused option, Escape denies. Nothing here calls a real tmux.
"""
import io
import json
import os
import re
import sys
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import cr_approve  # noqa: E402
import jarvisalert as JA  # noqa: E402
import jarvisapprove as JAP  # noqa: E402
import jarvislib as J  # noqa: E402
import jarvismail as M  # noqa: E402
import jarvisops as JO  # noqa: E402
import test_jarvismail as TM  # noqa: E402
from jarvis_fixtures import assistant, ev_line, user  # noqa: E402

JARVIS, TARGET = TM.JARVIS, TM.TARGET
MIN = 60 * 1000
FIX = os.path.join(HERE, "fixtures")
RULE = "─" * 80


def fixture(name):
    with open(os.path.join(FIX, name), encoding="utf-8") as f:
        return f.read()


def perm_edit(pointer=1):
    opts = ["Yes", "Yes, allow all edits during this session (shift+tab)", "No"]
    return "\n".join(["● Upravím soubor.", RULE, " Edit file", " src/app.py", "╌" * 80, " │ 12 -    x = 1",
                      " │ 12 +    x = 2", "╌" * 80, " Do you want to make this edit to app.py?"]
                     + [f" {'❯' if i == pointer else ' '} {i}. {o}" for i, o in enumerate(opts, 1)]
                     + [" Esc to cancel · Tab to amend"])


def ask_tabs():
    return "\n".join([RULE, "←  ☐ Databáze  ☐ Fronta  ✔ Submit  →", "", "Kterou databázi?",
                      "❯ 1. Postgres (Recommended)", "  2. SQLite", "  3. Type something.", RULE,
                      "  4. Chat about this", "Enter to select · Tab/Arrow keys to navigate · Esc to cancel"])


def ask_multi():
    return "\n".join([RULE, " ☐ Funkce", "", "Které funkce zapnout?", "❯ 1. [ ] Export", "  2. [ ] Import",
                      "  3. [ ] Type something", "", "  4. Submit", "Enter to select · ↑/↓ to navigate · Esc to cancel"])


class DialogTmux:
    """TM.FakeTmux with one dialog drawn in a pane: `screen(pointer)` draws it, Down/Up move the pointer, Enter answers
    the focused option (the dialog goes, a `work` event follows), Escape denies (the dialog goes)."""

    def __init__(self, base, fx, pane, name, pid, screen, n_options):
        self.base, self.fx, self.pane, self.name, self.pid = base, fx, pane, name, pid
        self.screen, self.n, self.pointer = screen, n_options, 1
        self.open = True
        self.answered = None
        self.calls = base.calls

    def __getattr__(self, k):
        return getattr(self.base, k)

    def __call__(self, args):
        if args[0] == "capture-pane" and args[args.index("-t") + 1] == self.pane and self.open:
            self.base.calls.append(list(args))
            return 0, self.screen(self.pointer)
        if args[0] == "send-keys" and args[args.index("-t") + 1] == self.pane and self.open:
            self.base.calls.append(list(args))
            k = args[-1]
            if k == "Down":
                self.pointer = min(self.n, self.pointer + 1)
            elif k == "Up":
                self.pointer = max(1, self.pointer - 1)
            elif k == "Enter":
                self.open, self.answered = False, self.pointer
                self.fx.append_events(ev_line("work", self.name, self.fx.clock.ms, self.pid))
            elif k == "Escape":
                self.open, self.answered = False, "deny"
            return 0, ""
        return self.base(args)


def redraw(raw, pointer):
    """The same screen with the pointer ❯ on another option (as Claude redraws it)."""
    out = []
    for l in raw.split("\n"):
        m = re.match(r"^(\s*(?:\x1b\[[0-9;]*m)*)\s*(❯)?(\s*(?:\x1b\[[0-9;]*m)*)\s*(\d+)\.(.*)$", l)
        if m:
            n = int(m.group(4))
            l = f"{m.group(1)}{'❯' if n == pointer else ' '} {m.group(3)}{n}.{m.group(5)}"
        out.append(l)
    return "\n".join(out)


class Base(TM.Base):
    def setUp(self):
        super().setUp()
        self.m.ops_spawn = lambda xid: None  # the runner is run by the test itself (no subprocess)
        self.ms = self.fx.clock.ms - 30000
        # the app's live screen (ReplyServerCommandTest.PERM_SCREEN): a short command (a long one goes to the app)
        self.dialog("perm", lambda p: redraw(fixture("perm-always.txt"), p), 4, "Bash: ls /tmp/x")

    def dialog(self, ev, screen, n, extra=""):
        self.fx.append_events(ev_line(ev, TARGET, self.ms, 200, extra))
        self.dt = DialogTmux(self.tm, self.fx, "%2", TARGET, 200, screen, n)
        self.m.tmux = self.dt

    def ops(self, env=None):
        return JO.Ops(env or self.env, mail=self.m, spawn=lambda xid: None)

    def run_cli(self, *argv, env=None):
        out, err = io.StringIO(), io.StringIO()
        rc = cr_approve.main(list(argv), env=env or self.env, out=out, err=err, mail=self.m, ops=self.ops(env))
        return rc, out.getvalue(), err.getvalue()

    def keys(self):
        return [c[-1] for c in self.tm.calls if c[0] == "send-keys"]

    def pending(self):
        return [r for r in self.ops().all() if r["state"] == "proposed"]

    def say(self, text):
        """Jarvis's last answer, as his transcript holds it (the hook reads it for the verbatim check)."""
        self.jarvis_tr = self.fx.transcript("/home/u/jarvis", f"{11:08x}-0000-4000-8000-000000000000",
                                            [user("ukaž"), assistant(text)])

    def lukas_says(self, text, m=None, said=True):
        """TM.Base.lukas_says with the hook payload's transcript_path; `said`: Jarvis's last answer held the
        sentence of the waiting approval word for word (as CLAUDE.jarvis.md asks)."""
        props = [r for r in self.ops().all() if r["state"] == "proposed" and r["kind"] == "approve"]
        if props and said:
            self.say("Pane, " + props[0]["title"])
        elif not hasattr(self, "jarvis_tr"):
            self.say("Pane, něco jiného.")
        st = self.fx.state
        try:
            os.replace(os.path.join(st, "last-prompt"), os.path.join(st, "last-prompt.prev"))
        except FileNotFoundError:
            if os.path.exists(os.path.join(st, "last-prompt.prev")):
                os.unlink(os.path.join(st, "last-prompt.prev"))
        self.fx.clock.advance(1)
        return (m or self.m).prompt_seen({"hook_event_name": "UserPromptSubmit", "prompt": text,
                                          "transcript_path": self.jarvis_tr}, self.fx.clock.ms)

    def approve_and_run(self, text="ano", said=True):
        self.lukas_says(text, said=said)
        ap = [r for r in self.ops().all() if r["state"] == "approved"]
        for r in ap:
            self.ops().run(r["id"])
        return [self.ops().get(r["id"]) for r in ap]

    def tearDown(self):
        # the only keys ever sent: Enter, Escape and the arrows (never a digit, a letter or a persistent choice)
        for c in self.tm.calls:
            if c[0] == "send-keys":
                self.assertIn(c[-1], ("Enter", "Escape", "Down", "Up"), c)


class ParseTest(Base):
    def test_live_permission_bash(self):
        p = JAP.parse_screen(fixture("perm-bash.ansi"))
        self.assertEqual((p["kind"], p["question"], p["pointer"]), ("perm", "Do you want to proceed?", 1))
        self.assertEqual([o["label"] for o in p["options"]], ["Yes", "No"])
        self.assertTrue(p["title"].startswith("Bash command"))
        self.assertIn("rm -f pdf/*", p["content"])
        self.assertNotIn("Run shell command", p["content"])

    def test_live_question_with_recommended_and_the_special_options(self):
        p = JAP.parse_screen(fixture("ask-single.ansi"))
        self.assertEqual(p["kind"], "ask")
        self.assertEqual([o["recommended"] for o in p["options"]], [True, False, False, False, False])
        self.assertEqual([o["label"] for o in p["options"]][3:], ["Type something.", "Chat about this"])
        self.assertFalse(p["tabs"] or p["multi"])
        self.assertTrue(p["options"][0]["desc"])

    def test_edit_permission_tabs_and_multiselect(self):
        p = JAP.parse_screen(perm_edit())
        self.assertEqual((p["kind"], p["title"]), ("perm", "Edit file"))
        self.assertIn("x = 2", p["content"])
        self.assertTrue(JAP.parse_screen(ask_tabs())["tabs"])
        self.assertTrue(JAP.parse_screen(ask_multi())["multi"])

    def test_no_dialog_on_a_prompt_or_the_trust_dialog(self):
        self.assertIsNone(JAP.parse_screen(TM.screen()))
        import test_jarvisacct as TA
        self.assertIsNone(JAP.parse_screen(TA.TRUST_SCREEN))  # no "Esc to cancel"


class FingerprintTest(Base):
    def test_a_redraw_or_the_pointer_never_changes_it_another_dialog_or_event_does(self):
        raw = fixture("perm-bash.ansi")
        a = JAP.fingerprint("perm", 1, JAP.parse_screen(raw))
        self.assertEqual(a, JAP.fingerprint("perm", 1, JAP.parse_screen(redraw(raw, 2))))
        self.assertEqual(a, JAP.fingerprint("perm", 1, JAP.parse_screen(re.sub(r"\x1b\[[0-9;]*m", "", raw))))
        self.assertNotEqual(a, JAP.fingerprint("perm", 1, JAP.parse_screen(raw.replace("rm -f pdf/*", "rm -rf /"))))
        self.assertNotEqual(a, JAP.fingerprint("perm", 2, JAP.parse_screen(raw)))


class ShowTest(Base):
    def test_show_is_data_with_the_options(self):
        rc, out, err = self.run_cli("show", TARGET)
        self.assertEqual(rc, 0, err)
        self.assertIn(M.DATA_NOTE, out)
        self.assertIn("1. «Yes»", out)
        self.assertIn("ls /tmp/x", out)

    def test_never_a_system_dialog_or_prod(self):
        self.fx.append_events(ev_line("work", TARGET, self.fx.clock.ms, 200))  # no open perm/ask any more
        rc, _, err = self.run_cli("show", TARGET)
        self.assertEqual(rc, 2)
        self.assertIn("nečeká", err)
        with mock.patch.object(M, "HIGH_IMPACT", re.compile("jarvis-test")):
            rc, _, err = self.run_cli("show", TARGET)
        self.assertIn("Tohle schval v aplikaci", err)


class FlowTest(Base):
    def test_propose_ano_then_exactly_enter_on_yes(self):
        rc, out, err = self.run_cli("propose", TARGET)
        self.assertEqual(rc, 0, err)
        self.assertIn("jarvis-test chce povolení: Bash", out)
        self.assertIn("Schválit?", out)
        self.assertEqual(self.keys(), [])
        recs = self.approve_and_run()
        self.assertEqual(self.dt.answered, 1)
        self.assertEqual(self.keys(), ["Enter"])
        self.assertEqual(recs[0]["state"], "done")
        self.assertTrue(recs[0]["message"].startswith("Schváleno: jarvis-test může Bash"))

    def test_a_changed_dialog_is_never_answered(self):
        self.run_cli("propose", TARGET)
        self.dt.screen = lambda p: redraw(fixture("perm-always.txt").replace("ls /tmp/x", "rm -rf ~"), p)
        recs = self.approve_and_run()
        self.assertEqual(self.keys(), [])
        self.assertEqual((recs[0]["state"], recs[0]["message"]), ("failed", JAP.CHANGED))

    def test_never_while_lukas_types_into_it(self):
        self.run_cli("propose", TARGET)
        self.tp.activity.append(self.fx.clock.ms // 1000)
        recs = self.approve_and_run()
        self.assertEqual(self.keys(), [])
        self.assertEqual(recs[0]["state"], "failed")

    def test_no_ano_or_ano_after_10_min_does_nothing(self):
        self.run_cli("propose", TARGET)
        self.assertEqual(self.approve_and_run("co dělá kontexta?"), [])
        self.assertEqual(self.pending(), [])
        self.prompt()
        self.run_cli("propose", TARGET)
        self.fx.clock.advance(11 * 60)
        self.assertEqual(self.approve_and_run(), [])
        self.assertEqual(self.keys(), [])

    def test_one_proposal_at_a_time(self):
        self.assertEqual(self.run_cli("propose", TARGET)[0], 0)
        rc, _, err = self.run_cli("propose", TARGET)
        self.assertEqual(rc, 2)
        self.assertIn("už čeká jiný návrh", err)

    def test_named_is_still_a_proposal_that_waits_for_ano(self):
        # rev-f5e HIGH (lead): even "u jarvis-test schval" only proposes; Lukáš hears the code's sentence first
        self.prompt_named()
        rc, out, err = self.run_cli("propose", TARGET)
        self.assertEqual(rc, 0, err)
        self.assertEqual(self.keys(), [])
        self.assertIn("Řekni Lukášovi doslova", out)
        self.approve_and_run()
        self.assertEqual(self.keys(), ["Enter"])

    def test_a_quoted_yes_above_the_dialog_is_no_focus(self):
        self.dialog("perm", lambda p: "❯ 1. Yes\n" + perm_edit(p), 3, "Edit: src/app.py")
        self.dt.pointer = 2
        self.run_cli("propose", TARGET)
        recs = self.approve_and_run()
        self.assertEqual(self.keys(), [])
        self.assertEqual(recs[0]["message"], JAP.CHANGED)

    def prompt_named(self):
        # status.json is what the hook names sessions from; give it the target as a short name
        st = {"v": 1, "gen": 1, "at": self.fx.clock.ms, "d_heartbeat": self.fx.clock.ms,
              "sessions": {TARGET: {"name": TARGET, "short": "jarvis-test", "alias": "jarvis-test"}}}
        J.atomic_write(self.env.path("status.json"), json.dumps(st))
        self.lukas_says("u jarvis-test schval")

    def test_deny_is_escape(self):
        self.run_cli("propose", TARGET, "--deny")
        recs = self.approve_and_run()
        self.assertEqual((self.keys(), self.dt.answered), (["Escape"], "deny"))
        self.assertTrue(recs[0]["message"].startswith("Zamítnuto"))

    def test_never_a_persistent_choice(self):
        self.dialog("perm", perm_edit, 3, "Edit: src/app.py")
        rc, _, err = self.run_cli("propose", TARGET, "--option", "2")
        self.assertEqual(rc, 2)
        self.dt.pointer = 2  # the focus on "Yes, allow all edits": Enter would pick it, so nothing goes (stale)
        self.assertEqual(self.run_cli("propose", TARGET)[0], 0)
        recs = self.approve_and_run()
        self.assertEqual(self.keys(), [])
        self.assertEqual(recs[0]["message"], JAP.CHANGED)

    def test_the_apps_live_screen_always_and_auto_mode_never(self):
        raw = fixture("perm-always.txt")
        self.dialog("perm", lambda p: redraw(raw, p), 4, "Bash: ls /tmp/x")
        d = JAP.parse_screen(raw)
        self.assertEqual([o["label"][:28] for o in d["options"]][1:3],
                         ["Yes, and always allow access", "Yes, and switch to auto mode"])
        for n in ("2", "3"):
            self.assertEqual(self.run_cli("propose", TARGET, "--option", n)[0], 2)
        self.assertEqual(self.run_cli("propose", TARGET)[0], 0)
        self.approve_and_run()
        self.assertEqual((self.keys(), self.dt.answered), (["Enter"], 1))


class Round4Test(Base):
    """Kontrolní review F5e: a long command goes to the app; a bare "ano" counts only after Jarvis said the code's
    sentence word for word."""

    def test_the_live_long_command_joins_its_cut_words_and_goes_to_the_app(self):
        p = JAP.parse_screen(fixture("perm-bash.ansi"))
        self.assertIn("36f14f6a0352/scratchpad", p["content"])        # cut mid-word: no blank
        self.assertIn("tail -1; cd /home/uzivo", p["content"])        # wrapped at a blank: one blank
        self.dialog("perm", lambda q: redraw(fixture("perm-bash.ansi"), q), 2, "Bash: rm")
        for argv in (("show", TARGET), ("propose", TARGET)):
            rc, _, err = self.run_cli(*argv)
            self.assertEqual(rc, 2, argv)
            self.assertIn(JAP.LONG, err)

    def test_ano_after_other_words_is_not_taken_the_proposal_waits(self):
        self.run_cli("propose", TARGET)
        self.say("Pane, jarvis-test chce něco spustit. Ano?")
        recs = self.approve_and_run(said=False)
        self.assertEqual((recs, self.keys()), ([], []))
        p = self.pending()
        self.assertEqual(len(p), 1)
        items, _ = JO.digest_items(self.env, 0, self.fx.clock.ms)
        self.assertIn("Přečti Lukášovi větu návrhu doslova a zeptej se znovu", JO.ops_line(items[-1]))
        recs = self.approve_and_run()  # now he read it
        self.assertEqual(self.keys(), ["Enter"])
        self.assertEqual(recs[0]["state"], "done")

    def test_no_transcript_is_no_approval(self):
        self.run_cli("propose", TARGET)
        st = self.fx.state
        os.replace(os.path.join(st, "last-prompt"), os.path.join(st, "last-prompt.prev"))
        self.fx.clock.advance(1)
        self.m.prompt_seen({"hook_event_name": "UserPromptSubmit", "prompt": "ano"}, self.fx.clock.ms)
        self.assertEqual([r["state"] for r in self.ops().all()], ["proposed"])


class Round5Test(Base):
    """Kolo 5: the whole command in the sentence; a refused "ano" is told to Lukáš once; only the turn before it."""

    CMD = ("cd /home/u/projekt && git stash list && git log --oneline -5 && ls -la build dist && du -sh node_modules "
           "&& find . -name '*.tmp' -delete && echo hotovo && rm -rf ~/projekt")

    def long_perm(self, pointer=1):
        words, rows, row = self.CMD.split(" "), [], ""
        for w in words:
            if len(" │ " + row + " " + w) > 76:
                rows.append(row)
                row = w
            else:
                row = (row + " " + w).strip()
        rows.append(row)
        return "\n".join([RULE, " Bash command", " Run shell command"] + [f" │ {r}" for r in rows]
                         + [" Do you want to proceed?", f" {'❯' if pointer == 1 else ' '} 1. Yes",
                            f" {'❯' if pointer == 2 else ' '} 2. No", " Esc to cancel · Tab to amend"])

    def test_the_sentence_carries_the_whole_command(self):
        self.assertEqual(len(self.CMD), 171)
        self.dialog("perm", self.long_perm, 2, "Bash: …")
        rc, out, err = self.run_cli("propose", TARGET)
        self.assertEqual(rc, 0, err)
        title = self.pending()[0]["title"]
        self.assertTrue(title.endswith("rm -rf ~/projekt. Schválit?"), title)
        self.assertIn(self.CMD, title)

    def alerts(self):
        try:
            with open(self.env.alerts_path) as f:
                return [a for a in map(JA.parse_alert, f) if a and "id" in a]
        except FileNotFoundError:
            return []

    def test_a_refused_ano_is_told_to_lukas_once(self):
        open(os.path.join(self.fx.state, JA.WAKE_OFF), "w").close()
        w = JA.AlertWorker(self.env, mail=self.m, presence=lambda: None)
        w.tick([], {}, self.fold(), self.fx.clock.ms, JARVIS)
        self.run_cli("propose", TARGET)
        for _ in range(2):
            self.say("Pane, jarvis-test chce něco spustit. Ano?")
            self.approve_and_run(said=False)
            self.fx.clock.advance(1)
            w.tick([], {}, self.fold(), self.fx.clock.ms, JARVIS)
        js = [(a["kind"], a["text"], a["name"]) for a in self.alerts()]
        self.assertEqual(js, [("jsay", JA.UNREAD_TEXT, TARGET)])
        self.assertEqual(self.keys(), [])

    def tr(self, *entries):
        self.jarvis_tr = self.fx.transcript("/home/u/jarvis", f"{11:08x}-0000-4000-8000-000000000000", list(entries))

    def ano(self):
        st = self.fx.state
        try:
            os.replace(os.path.join(st, "last-prompt"), os.path.join(st, "last-prompt.prev"))
        except FileNotFoundError:
            pass
        self.fx.clock.advance(1)
        self.m.prompt_seen({"hook_event_name": "UserPromptSubmit", "prompt": "ano",
                            "transcript_path": self.jarvis_tr}, self.fx.clock.ms)
        return [r["state"] for r in self.ops().all()]

    def test_only_the_turn_right_before_the_ano_counts(self):
        self.run_cli("propose", TARGET)
        title = self.pending()[0]["title"]
        # he said it in an earlier turn; the last turn (after "a co dál?") has no text at all
        self.tr(user("ukaž"), assistant(title), user("a co dál?"), {"type": "assistant", "message": {
            "role": "assistant", "content": [{"type": "tool_use", "id": "t", "name": "Bash", "input": {}}]}})
        self.assertEqual(self.ano(), ["proposed"])
        # the "ano" already in the transcript: the turn before it counts
        self.tr(user("ukaž"), assistant("Pane, " + title), user("ano"))
        self.assertEqual(self.ano(), ["approved"])


class QuestionTest(Base):
    def setUp(self):
        super().setUp()
        self.ms += 1000
        self.dialog("ask", lambda p: redraw(fixture("ask-single.ansi"), p), 5, "xxx [a, b, c]")

    def test_the_recommended_option_by_default_and_option_n(self):
        rc, out, err = self.run_cli("propose", TARGET)
        self.assertEqual(rc, 0, err)
        self.assertIn("doporučená možnost 1", out)
        self.ops().decide(self.pending()[0]["id"], False, "app:t")  # Lukáš said no to it in the app
        self.assertEqual(self.run_cli("propose", TARGET, "--option", "3")[0], 0)
        self.approve_and_run()
        self.assertEqual(self.keys(), ["Down", "Down", "Enter"])
        self.assertEqual(self.dt.answered, 3)

    def test_the_number_lukas_said_is_in_the_sentence_when_it_differs(self):
        self.lukas_says("moje práce, zvol dvojku")
        rc, out, err = self.run_cli("propose", TARGET, "--option", "3")
        self.assertEqual(rc, 0, err)
        self.assertIn("možnost 3:", out)
        self.assertIn("(ve tvé zprávě: 2)", out)

    def test_hands_on_the_pane_mid_way_stop_the_arrows(self):
        self.run_cli("propose", TARGET, "--option", "3")
        orig = self.dt.__call__

        def typing(args):
            r = orig(args)
            if args[0] == "send-keys" and args[-1] == "Down":
                self.tp.activity[:] = [self.fx.clock.ms // 1000]  # Lukáš starts typing into it
            return r
        self.m.tmux = typing
        recs = self.approve_and_run()
        self.assertEqual(self.keys(), ["Down"])
        self.assertEqual(recs[0]["state"], "failed")

    def test_never_type_something_or_chat(self):
        for n in ("4", "5"):
            self.assertEqual(self.run_cli("propose", TARGET, "--option", n)[0], 2)

    def test_never_an_option_with_always_or_auto_mode(self):
        labels = ["Postgres (Recommended)", "Always allow migrations", "Switch to auto mode", "Type something."]
        screen = "\n".join([RULE, " ☐ Databáze", "", "Kterou databázi?"]
                           + [f"  {i}. {l}" for i, l in enumerate(labels, 1)] + [RULE, "  5. Chat about this",
                                                                                "Enter to select · ↑/↓ to navigate · Esc to cancel"])
        self.dialog("ask", lambda p: redraw(screen, p), 5)
        for n in ("2", "3"):
            self.assertEqual(self.run_cli("propose", TARGET, "--option", n)[0], 2, n)
        self.assertEqual(self.run_cli("propose", TARGET, "--option", "1")[0], 0)

    def test_tabs_and_multiselect_go_to_the_app(self):
        for screen in (ask_tabs, ask_multi):
            self.dialog("ask", lambda p, s=screen: s(), 4)
            for argv in ((), ("--option", "1")):
                rc, _, err = self.run_cli("propose", TARGET, *argv)
                self.assertEqual(rc, 2, argv)
                self.assertIn("aplikaci", err)

    def test_a_dialog_that_changes_while_the_pointer_moves_gets_no_enter(self):
        self.run_cli("propose", TARGET, "--option", "3")
        base = self.dt.screen
        self.dt.screen = lambda p: base(p) if p == 1 else base(p).replace("Type something.", "Type anything.")
        recs = self.approve_and_run()
        self.assertNotIn("Enter", self.keys())
        self.assertEqual((recs[0]["state"], recs[0]["message"]), ("failed", JAP.CHANGED))


class AlertTest(Base):
    def test_the_outcome_is_a_jsay_in_the_codes_words(self):
        open(os.path.join(self.fx.state, JA.WAKE_OFF), "w").close()
        w = JA.AlertWorker(self.env, mail=self.m, presence=lambda: None)
        w.tick([], {}, self.fold(), self.fx.clock.ms, JARVIS)
        self.fx.clock.advance(1)
        self.run_cli("propose", TARGET)
        self.approve_and_run()
        self.fx.clock.advance(1)
        w.tick([], {}, self.fold(), self.fx.clock.ms, JARVIS)
        with open(self.env.alerts_path) as f:
            js = [a for a in map(JA.parse_alert, f) if a and a.get("kind") == "jsay"]
        self.assertEqual(len(js), 1)
        self.assertTrue(js[0]["text"].startswith("Schváleno: jarvis-test"))
        self.assertEqual(js[0]["name"], TARGET)


class RelayTextTest(Base):
    def test_jask_shows_the_codes_sentence_not_jarvis_words(self):
        import test_jarvis_active as TAC
        t = TAC.RelayTest("test_jask_for_a_quiet_batch_with_one_proposal")
        t.setUp()
        try:
            t.perm()
            t.tick()
            t.lukas_says(t.submitted())
            p = JO.Ops(t.env, mail=t.m).propose("approve", TARGET, "jarvis-test chce povolení: Bash ls. Schválit?", "",
                                                {}, t.m.wake_prompt(t.fx.clock.ms)["id"], t.fx.clock.ms)
            t.answer("Pane, jarvis-test chce smazat úplně všechno. Ano?")
            a = t.alerts()
            self.assertEqual([x["kind"] for x in a], ["jblockask"])
            self.assertEqual(a[0]["text"], p["title"])
        finally:
            t.doCleanups()


class WakeTest(Base):
    def test_after_a_wake_one_proposal_and_it_needs_ano(self):
        os.makedirs(self.env.path("wakes"), exist_ok=True)
        wid = "w-20261111T120000-abcd"
        with open(self.env.path("wakes", wid + ".json"), "w") as f:
            f.write("{}")
        self.lukas_says(f"[jarvis wake {wid}] 1 událost · cr-digest wake {wid}")
        rc, out, err = self.run_cli("propose", TARGET)
        self.assertEqual(rc, 0, err)
        self.assertEqual(self.keys(), [])
        self.approve_and_run()
        self.assertEqual(self.keys(), ["Enter"])


class MutationTest(unittest.TestCase):
    MUTANTS = {
        "fingerprint": (lambda: _mut(JAP, "execute", "or d.fp != args.get(\"fp\")", ""), FlowTest),
        "hands": (lambda: _mut(JAP, "execute", "why = mail.check_hands(d.target, mail.env.now_ms())", "why = None"),
                  FlowTest),
        "prod": (lambda: _mut(JAP, "read_dialog", "if mail.check_high_impact(name):", "if False:"), ShowTest),
        "one-at-a-time": (lambda: _mut(cr_approve, "cmd_propose", "if m._pending():", "if False:"), FlowTest),
        "always-propose": (lambda: mock.patch.object(JO.Ops, "propose", lambda self, kind, target, title, detail, args,
                                                     pid, now, targets=None: self.start_now(
            kind, target, title, detail, args, pid, "named", 1)[0]), FlowTest),
        "said-number": (lambda: _mut(JAP, "sentence", "if said and n not in said else", "if False else"),
                        QuestionTest),
        "hands-per-step": (lambda: _mut(JAP, "_move_to", "if mail.check_hands(d.target, mail.env.now_ms()):",
                                        "if False:"), QuestionTest),
        "region-yes": (lambda: _mut(JAP, "execute", 'not APPROVE_RE.search(p["shown"])',
                                    "not APPROVE_RE.search(plain(raw))"), FlowTest),
        "relay-code-text": (lambda: mock.patch.object(JA.AlertWorker, "_approve_sentence", lambda self: None),
                            RelayTextTest),
        "deny-escape": (lambda: _mut(JAP, "execute", 'mail.tmux(["send-keys", "-t", pane, "Escape"])',
                                     'mail.tmux(["send-keys", "-t", pane, "Enter"])'), FlowTest),
        "special-options": (lambda: mock.patch.object(JAP, "NOT_AN_ANSWER", ()), QuestionTest),
        "tabs-multi": (lambda: _mut(JAP, "Dialog", 'elif not (self.p["tabs"] or self.p["multi"]):', "else:"),
                       QuestionTest),
        "approve-regex": (lambda: _mut(JAP, "execute", ' or not APPROVE_RE.search(p["shown"])', ""), FlowTest),
        "never-always": (lambda: mock.patch.object(JAP, "NEVER_RE", re.compile("(?!)")), QuestionTest),
        "long-command": (lambda: _mut(JAP, "read_dialog", 'if p["kind"] == "perm" and len(p["content"]) > CONTENT_MAX:',
                                      "if False:"), Round4Test),
        "cut-words": (lambda: _mut(JAP, "parse_screen", "cut = len(l.rstrip()) >= width - 1", "cut = False"),
                      Round4Test),
        "verbatim": (lambda: mock.patch.object(JO, "said_verbatim", lambda s, t, c=None: True), Round4Test),
        "whole-command": (lambda: _mut(JAP, "_what", "CONTENT_MAX + 200)", "140)"), Round5Test),
        "unread-told": (lambda: _mut(JA, "AlertWorker", 'elif e.get("ev") == "unread" and e.get("kind") == "approve":',
                                     "elif False:"), Round5Test),
        "unread-once": (lambda: _mut(JO, "Ops", 'ev="unread" if first else "unread-again"', 'ev="unread"'),
                        Round5Test),
        "last-turn": (lambda: mock.patch.object(JO, "last_turn_text", lambda p, c=None, max_bytes=0: " ".join(
            J._assistant_text(d) for d in map(json.loads, J.read_tail(p, 10 ** 6)) if d.get("type") == "assistant")),
            Round5Test),
        "step-check": (lambda: _mut(JAP, "_move_to", "if not p or fingerprint(d.ev, d.ms, p) != d.fp or "
                                    "p[\"pointer\"] is None:", "if not p or p[\"pointer\"] is None:"), QuestionTest),
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
    if hasattr(mod, "Refused"):
        m.Refused = mod.Refused
    return mock.patch.object(mod, attr, getattr(m, attr))


if __name__ == "__main__":
    unittest.main()
