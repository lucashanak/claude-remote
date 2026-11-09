"""Jarvis F4: opening, closing and restoring sessions, the account policy, the general proposals and their approval.

cr-session is replaced by an ATRAPA: a small sh script at `<temp HOME>/.claude-remote/bin/cr-session` that logs its
arguments and prints canned answers from `<temp HOME>/cr-resp/`. The wrapper runs it through the real subprocess path;
the real cr-session has its own integration tests in Kotlin (SessionOpsIntegrationTest). Nothing here talks to a real
tmux (TM.Base's FakeTmux answers Jarvis's own display-message), creates a link, or deletes outside its own
TemporaryDirectory (docs/TESTING.md). `git` runs only on directories inside that temp dir.
"""
import base64
import io
import json
import os
import subprocess
import sys
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import cr_digest  # noqa: E402
import cr_jarvis  # noqa: E402
import cr_sessions as S  # noqa: E402
import cr_task  # noqa: E402
import jarvisalert as JA  # noqa: E402
import jarvislib as J  # noqa: E402
import jarvismail as M  # noqa: E402
import jarvisops as JO  # noqa: E402
import test_jarvismail as TM  # noqa: E402
from jarvis_fixtures import T0  # noqa: E402

JARVIS, TARGET, OTHER = TM.JARVIS, TM.TARGET, TM.OTHER
DAY = 86400 * 1000

ATRAPA = """#!/bin/sh
printf '%s\\n' "$*" >> "$HOME/cr-calls.log"
printf 'TMUX=%s PANE=%s CC=%s CFG=%s\\n' "${TMUX:-}" "${TMUX_PANE:-}" "${CLAUDECODE:-}" "${CLAUDE_CONFIG_DIR:-}" >> "$HOME/cr-env.log"
R="$HOME/cr-resp"
case "$1" in
  version) if [ -f "$R/version" ]; then cat "$R/version"; else echo "cr-session v2"; fi ;;
  facts) if [ -f "$R/facts.$2" ]; then cat "$R/facts.$2"; else printf 'FACT alive 0\\nTOKEN 1.none\\n__CR_FACTS_END__\\n'; fi ;;
  close) if [ -f "$R/close.$2" ]; then cat "$R/close.$2"; else echo CLOSED; fi ;;
  open) if [ -f "$R/open" ]; then cat "$R/open"; else echo 'OPENED {"name":"x","pane":"%9","pid":900,"sessionId":"s"}'; fi ;;
  restore) if [ -f "$R/restore" ]; then cat "$R/restore"; else echo "CR_RESTORE RESTORED 901 sid sid"; fi ;;
  *) exit 2 ;;
esac
"""


def facts(alive=1, pid=200, status="idle", event=("stop", 4000, 0), draft=0, clients=(0, "-"), procs=(0, "-"),
          git=(0, "-", 0), transcript=1, token="1794000000.h1"):
    return "".join([f"FACT alive {alive}\n", f"FACT pid {pid}\n", f"FACT status {status} -\n",
                    f"FACT event {event[0]} {event[1]} {event[2]}\n", f"FACT draft {draft}\n",
                    f"FACT clients {clients[0]} {clients[1]}\n", f"FACT procs {procs[0]} {procs[1]}\n",
                    f"FACT git {git[0]} {git[1]} {git[2]}\n", f"FACT transcript {transcript}\n",
                    f"TOKEN {token}\n", "__CR_FACTS_END__\n"])


class Base(TM.Base):
    """TM.Base: Jarvis in %1 (the calling process 12 below his claude), TARGET in %2, OTHER in %3, a fresh prompt of
    Lukáš's, FakeTmux; plus known project folders, the atrapa, accounts and their usage."""

    def setUp(self):
        self.spawned = []
        super().setUp()
        fx = self.fx
        self.proj = {}
        for p in ("kontexta", "rag", "kontexta-deploy", "jarvis"):
            self.proj[p] = os.path.join(fx.home, p)
            os.makedirs(self.proj[p], exist_ok=True)
        fx.write_manifest([{"tmuxSessionName": "claude-server-kontexta-yolo--a", "folder": self.proj["kontexta"]},
                           {"tmuxSessionName": "claude-server-rag-yolo", "folder": self.proj["rag"]},
                           {"tmuxSessionName": "claude-server-kd-yolo", "folder": self.proj["kontexta-deploy"]},
                           {"tmuxSessionName": "claude-server-jarvis--jarvis", "folder": self.proj["jarvis"]}])
        bin_ = os.path.join(fx.cr, "bin")
        os.makedirs(bin_, exist_ok=True)
        with open(os.path.join(bin_, "cr-session"), "w") as f:
            f.write(ATRAPA)
        os.chmod(os.path.join(bin_, "cr-session"), 0o755)
        os.makedirs(os.path.join(fx.home, "cr-resp"), exist_ok=True)
        for a in ("lukas-kontexta-cz", "hanakl@nekrachni.cz"):
            self.login(a)
        self.login("default")
        with open(os.path.join(fx.cr, "folder-policies.json"), "w") as f:
            json.dump({"kontexta": {"defaultAccountSlug": "lukas-kontexta-cz",
                                    "allowedAccountSlugs": ["lukas-kontexta-cz", "hanakl@nekrachni.cz"]}}, f)
        fx.usage("lukas-kontexta-cz", 20, 50)
        fx.usage("hanakl@nekrachni.cz", 10, 30)
        self.allow(TARGET, "claude-server-kontexta-yolo--pdf", "claude-server-kontexta-yolo")
        self.ops = self.make_ops()

    def login(self, slug):
        d = os.path.join(self.fx.home, ".claude") if slug == "default" else self.fx.account_dir(slug)
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, ".credentials.json"), "w") as f:
            f.write("x" * 10)

    def mail(self, env):
        m = super().mail(env)
        m.ops_spawn = self.spawned.append  # never a real runner from a test
        return m

    def make_ops(self, env=None):
        env = env or self.env
        return JO.Ops(env, mail=self.mail(env), spawn=self.spawned.append)

    def resp(self, name, text):
        with open(os.path.join(self.fx.home, "cr-resp", name), "w") as f:
            f.write(text)

    def calls(self):
        try:
            with open(os.path.join(self.fx.home, "cr-calls.log")) as f:
                return [l.split() for l in f.read().splitlines()]
        except FileNotFoundError:
            return []

    def actions(self):
        return [c for c in self.calls() if c and c[0] in ("open", "close", "restore")]

    def named(self, sessions=(), projects=(), closed=(), age_s=5, pid="p-abc", intent=True):
        """last-prompt as the hook writes it: what Lukáš named, and (rev-f4 H1) whether he asked to act on it."""
        with open(os.path.join(self.fx.state, "last-prompt"), "w") as f:
            json.dump({"id": pid, "at": self.fx.clock.ms - int(age_s * 1000), "named": list(sessions),
                       "namedProjects": list(projects), "namedClosed": list(closed), "closeIntent": intent,
                       "openIntent": intent, "reopenIntent": intent, "sendIntent": intent}, f)

    def cli(self, mod, *argv, env=None, ops=None):
        out, err = io.StringIO(), io.StringIO()
        env = env or self.env
        rc = mod.main(list(argv), env=env, out=out, err=err, ops=ops or self.make_ops(env), **(
            {"mail": self.mail(env)} if mod is cr_jarvis else {}))
        return rc, out.getvalue(), err.getvalue()

    def sessions_cli(self, *argv, **kw):
        return self.cli(S, *argv, **kw)

    def app_env(self):
        return self.fx.env(environ_extra={}, my_pid=5000)

    def ledger(self):
        return [e["ev"] for e in self.ops.ledger()]

    def tearDown(self):
        super().tearDown()
        # never --force, never anything but the four commands, from any test
        for c in self.calls():
            self.assertNotIn("--force", c)
            self.assertIn(c[0], ("version", "facts", "open", "close", "restore"), c)


# ------------------------------------------------------------------------------------------------- pure parts

class NamesTest(unittest.TestCase):
    def test_build_matches_the_apps_builder(self):
        self.assertEqual(JO.build_name("/home/u/kontexta", "pdf sloupce"), "claude-server-kontexta-yolo--pdf-sloupce")
        self.assertEqual(JO.build_name("/home/u/kontexta", ""), "claude-server-kontexta-yolo")
        self.assertEqual(JO.build_name("/home/u/nekrachni.plus", "a:b"), "claude-server-nekrachni_plus-yolo--a_b")
        long = JO.build_name("/w/" + "f" * 80, "alias")
        self.assertTrue(long.endswith("-yolo--alias"))
        self.assertEqual(len(long), 64)

    def test_fresh_name_numbers_before_the_tail(self):
        taken = {"claude-server-kontexta-yolo--pdf", "claude-server-kontexta-2-yolo--pdf"}
        self.assertEqual(JO.fresh_name("/h/kontexta", "pdf", taken), "claude-server-kontexta-3-yolo--pdf")
        self.assertEqual(JO.fresh_name("/h/kontexta", "", {"claude-server-kontexta-yolo"}),
                         "claude-server-kontexta-2-yolo")
        self.assertEqual(J.parse_name("claude-server-kontexta-3-yolo--pdf"), ("kontexta-3", "pdf", True))


class PolicyTest(Base):
    def test_canonical_folder(self):
        h = "/home/u"
        for raw, want in (("kontexta", "~/kontexta"), ("~/kontexta/", "~/kontexta"), ("/home/u/kontexta", "~/kontexta"),
                          ("/home/u", "~"), ("/srv//x/", "/srv/x"), (".", "~"), ("./a", "~/a")):
            self.assertEqual(JO.canonical_folder(raw, h), want, raw)

    def test_most_specific_rule_wins_and_siblings_are_not_covered(self):
        with open(os.path.join(self.fx.cr, "folder-policies.json"), "w") as f:
            json.dump({"kontexta": {"defaultAccountSlug": "a", "allowedAccountSlugs": ["a"]},
                       "~/kontexta/sub": {"defaultAccountSlug": "b", "allowedAccountSlugs": []}}, f)
        h = self.fx.home
        self.assertEqual(JO.policy_for(self.env, h + "/kontexta")[1]["defaultAccountSlug"], "a")
        self.assertEqual(JO.policy_for(self.env, h + "/kontexta/x/y")[1]["defaultAccountSlug"], "a")
        self.assertEqual(JO.policy_for(self.env, h + "/kontexta/sub/z")[1]["defaultAccountSlug"], "b")
        self.assertEqual(JO.policy_for(self.env, h + "/kontexta-deploy"), (None, {}))

    def test_pick_default_then_the_least_loaded_allowed(self):
        f = self.proj["kontexta"]
        p = JO.pick_account(self.env, f, self.fx.clock.ms)
        self.assertEqual((p["account"], p["ok"], p["alternative"]), ("lukas-kontexta-cz", True, None))
        self.fx.usage("lukas-kontexta-cz", 20, 100)  # the live example: a spent week
        p = JO.pick_account(self.env, f, self.fx.clock.ms)
        self.assertEqual((p["account"], p["ok"], p["alternative"]), ("lukas-kontexta-cz", False, "hanakl@nekrachni.cz"))
        self.assertIn("týden", p["why"])
        self.fx.usage("hanakl@nekrachni.cz", 90, 10)  # 5 h over 85 %
        p = JO.pick_account(self.env, f, self.fx.clock.ms)
        self.assertIsNone(p["alternative"])  # never outside the policy (default / the other accounts are not allowed)

    def test_logged_out_is_not_suitable_and_old_usage_is_unknown(self):
        os.unlink(os.path.join(self.fx.cr, "accounts", "lukas-kontexta-cz", ".credentials.json"))
        p = JO.pick_account(self.env, self.proj["kontexta"], self.fx.clock.ms)
        self.assertEqual((p["ok"], p["alternative"], p["why"]), (False, "hanakl@nekrachni.cz", "není přihlášený"))
        self.login("lukas-kontexta-cz")
        self.fx.usage("lukas-kontexta-cz", 99, 99, fetched_s=self.fx.clock.ms // 1000 - 3600)
        p = JO.pick_account(self.env, self.proj["kontexta"], self.fx.clock.ms)
        self.assertEqual((p["ok"], p["usage"]["known"]), (True, False))

    def test_no_policy_takes_the_first_account(self):
        p = JO.pick_account(self.env, self.proj["rag"], self.fx.clock.ms)
        self.assertEqual((p["account"], p["policyKey"]), ("default", None))


class ProjectsTest(Base):
    def test_known_projects_come_from_manifest_closed_and_policies(self):
        os.makedirs(os.path.join(self.fx.home, "fromclosed"))
        os.makedirs(os.path.join(self.fx.home, "frompolicy"))
        os.makedirs(os.path.join(self.fx.home, "unknown"))
        self.fx.closed({"tmuxName": "claude-server-fromclosed-yolo", "at": "2026-11-06T17:20:03Z",
                        "cwd": os.path.join(self.fx.home, "fromclosed")},
                       {"tmuxName": "claude-server-gone-yolo", "at": "2026-11-06T17:20:03Z", "cwd": "/nonexistent"},
                       {"tmuxName": "claude-server-etc-yolo", "at": "2026-11-06T17:20:03Z", "cwd": "/etc"})
        with open(os.path.join(self.fx.cr, "folder-policies.json"), "w") as f:
            json.dump({"~/frompolicy": {}, "/home/u/x::kontexta": {}}, f)
        k = set(JO.known_projects(self.env).values())
        self.assertEqual(k, {"kontexta", "rag", "kontexta-deploy", "fromclosed", "frompolicy"})  # no jarvis, /etc, home

    def test_resolve(self):
        self.assertEqual(JO.resolve_project(self.env, "Kontexta"), (self.proj["kontexta"], None))
        self.assertEqual(JO.resolve_project(self.env, self.proj["rag"]), (self.proj["rag"], None))
        self.assertIn("není známý projekt", JO.resolve_project(self.env, "unknown")[1])
        self.assertIn("myslíš: kontexta, kontexta-deploy", JO.resolve_project(self.env, "konte")[1])
        self.assertIsNone(JO.resolve_project(self.env, "/etc")[0])
        self.assertIsNone(JO.resolve_project(self.env, "jarvis")[0])

    def test_home_only_exactly_and_only_where_a_session_ran(self):
        with open(os.path.join(self.fx.cr, "folder-policies.json"), "w") as f:
            json.dump({"~": {}, self.fx.home: {}}, f)  # a policy key alone never makes ~ a project
        self.assertNotIn(os.path.realpath(self.fx.home), JO.known_projects(self.env))
        self.assertIsNone(JO.resolve_project(self.env, "~")[0])
        self.fx.closed({"tmuxName": "claude-server-~-yolo--home", "at": "2026-11-06T17:20:03Z", "cwd": self.fx.home + "/"})
        home = os.path.realpath(self.fx.home)
        self.assertEqual(JO.known_projects(self.env)[home], "~")
        self.assertEqual(JO.resolve_project(self.env, "~"), (home, None))
        self.assertEqual(JO.resolve_project(self.env, self.fx.home), (home, None))
        # nothing else outside strictly-under-$HOME: its parent, / and a sibling stay out
        self.fx.closed({"tmuxName": "claude-server-u-yolo", "at": "2026-11-06T17:20:03Z", "cwd": os.path.dirname(home)},
                       {"tmuxName": "claude-server-r-yolo", "at": "2026-11-06T17:20:03Z", "cwd": "/"})
        self.assertEqual({f for f in JO.known_projects(self.env) if not f.startswith(home + "/")}, {home})
        rc, out, err = self.sessions_cli("open", self.fx.home, "--alias", "research", env=self.app_env())
        self.assertEqual(rc, 0, err)
        self.assertEqual(self.actions()[0][2], "claude-server-~-yolo--research")  # the app's name for ~
        self.assertIn("Otevřel jsem research v ~ na účtu", out)

    def test_named_projects_and_closed_from_a_prompt(self):
        self.fx.closed({"tmuxName": "claude-server-kontexta-yolo--pdf-sloupce", "at": "2026-11-06T17:20:03Z",
                        "cwd": self.proj["kontexta"]})
        self.assertEqual(JO.named_projects(self.env, "Otevři session v Kontexta, ať opraví PDF"), [self.proj["kontexta"]])
        self.assertEqual(JO.named_projects(self.env, "nasaď kontexta-deploy"), [self.proj["kontexta-deploy"]])
        self.assertEqual(JO.named_projects(self.env, "otevři něco"), [])
        self.assertEqual(JO.named_closed(self.env, "vrať pdf-sloupce"), ["claude-server-kontexta-yolo--pdf-sloupce"])


class FactsTest(unittest.TestCase):
    def rep(self, **kw):
        f, tok, end = JO.parse_facts(facts(**kw))
        self.assertTrue(end)
        return JO.facts_report(f)

    def test_blocks_like_cr_session(self):
        self.assertEqual(self.rep()["blocks"], [])
        self.assertEqual(self.rep(status="busy")["blocks"], ["BUSY"])
        self.assertEqual(self.rep(status="-", event=("work", 100, 0))["blocks"], ["BUSY"])
        self.assertEqual(self.rep(status="-", event=("work", 4000, 0))["blocks"], [])  # older than 30 min
        self.assertEqual(self.rep(status="-", event=("perm", 10, 0))["blocks"], ["WAITING"])
        self.assertEqual(self.rep(event=("stop", 10, 1))["blocks"], ["BACKGROUND"])
        self.assertEqual(self.rep(draft=1)["blocks"], ["DRAFT"])
        self.assertEqual(self.rep(draft="-")["blocks"], [])
        self.assertEqual(self.rep(clients=(1, "-"))["blocks"], ["VIEWER"])
        self.assertEqual(self.rep(procs=(2, "node,vite"))["block"], ["běží v ní další procesy (node_vite)"])

    def test_warnings_info_and_the_question(self):
        r = self.rep(git=(3, 1, 0), event=("stop", 120, 0))
        self.assertEqual(r["warn"], ["3 necommitnuté soubory", "1 nepushnutý commit", "naposledy aktivní před 2 minuty"])
        self.assertEqual(r["info"], ["konverzace se neztratí, jde vrátit"])
        self.assertTrue(r["restorable"])
        self.assertEqual(JO.close_question("pdf-sloupce", self.rep(git=(3, "-", 0))),
                         "Zavřít pdf-sloupce? Má 3 necommitnuté soubory, konverzaci si pamatuju.")
        self.assertIn("nepůjde vrátit", " ".join(self.rep(transcript=0)["warn"]))


# ------------------------------------------------------------------------------------------------- open

class OpenTest(Base):
    def test_named_project_on_the_default_account_opens_at_once(self):
        self.named(projects=[self.proj["kontexta"]])
        rc, out, err = self.sessions_cli("open", "kontexta", "--alias", "pdf")
        self.assertEqual(rc, 0, err)
        self.assertIn("Otevřel jsem pdf v kontexta na účtu lukas-kontexta.", out)
        self.assertEqual(self.ledger(), ["approved", "started", "opened", "done"])

    def test_a_task_makes_it_a_proposal_and_yes_queues_the_task(self):
        # rev-f4 H1: a new yolo session with work in it: Lukáš sees the task in the question first
        self.named(projects=[self.proj["kontexta"]])
        name = "claude-server-kontexta-yolo--pdf"
        self.resp("open", 'OPENED {"name":"%s","pane":"%%9","pid":900,"sessionId":"s"}\n' % name)
        rc, out, err = self.sessions_cli("open", "kontexta", "--alias", "pdf", "--task", "Oprav sloupce v PDF exportu.")
        self.assertIn("se zadáním", out)
        self.assertEqual(self.actions(), [])
        p = self.ops.proposals(self.fx.clock.ms)[0]
        self.assertIn("zadání: Oprav sloupce v PDF exportu.", p["detail"])
        rec, out = self.ops.decide(p["id"], True, "app:x")
        self.assertIn("Otevřel jsem pdf v kontexta na účtu lukas-kontexta.", out)
        self.assertIn("čeká ve frontě", out)
        o = self.actions()[0]
        self.assertEqual(o[:11], ["open", "--name", name, "--folder", self.proj["kontexta"], "--account",
                                  "lukas-kontexta-cz", "--model", "DEFAULT", "--mode", "yolo"])
        self.assertIn("--by", o)
        mails = [r for r in self.m.all() if r["to"] == name]
        self.assertEqual([(r["state"], r["opened"]) for r in mails], [("queued", True)])
        self.assertIn("Oprav sloupce v PDF exportu.", self.m.text_of(mails[0]["id"]))

    def test_a_project_jarvis_chose_is_a_proposal_and_yes_runs_it(self):
        rc, out, _ = self.sessions_cli("open", "kontexta", "--alias", "pdf")
        self.assertEqual(rc, 0)
        self.assertIn("Zeptej se Lukáše: „Otevřu pdf v kontexta na účtu lukas-kontexta. Ano?“", out)
        self.assertIn("projekt Lukáš ve zprávě nejmenoval", out)
        self.assertEqual(self.actions(), [])
        p = self.ops.proposals(self.fx.clock.ms)
        self.assertEqual([(x["kind"], x["target"]) for x in p], [("open", "claude-server-kontexta-yolo--pdf")])
        self.assertEqual(set(p[0]), {"id", "kind", "target", "targets", "title", "detail", "created", "expires"})
        self.lukas_says("ano")
        self.assertEqual(self.spawned, [p[0]["id"]])
        self.assertEqual(self.ops.get(p[0]["id"])["state"], "approved")
        rec, msg = self.ops.run(p[0]["id"])
        self.assertEqual(rec["state"], "done")
        self.assertEqual(self.actions()[0][0], "open")
        self.assertIn("--wait", self.actions()[0])
        self.assertEqual(self.actions()[0][self.actions()[0].index("--wait") + 1], str(JO.APPROVED_WAIT_S))

    def test_a_spent_default_account_proposes_the_other_allowed_one(self):
        self.named(projects=[self.proj["kontexta"]])
        self.fx.usage("lukas-kontexta-cz", 20, 100)
        rc, out, _ = self.sessions_cli("open", "kontexta")
        self.assertEqual(rc, 0)
        self.assertIn("Kontexta má výchozí účet lukas-kontexta, ten má vyčerpaný týden (100 %). Otevřu kontexta v "
                      "kontexta na účtu nekrachni. Ano?", out)
        self.assertEqual(self.actions(), [])

    def test_no_allowed_account_left_asks_lukas(self):
        self.named(projects=[self.proj["kontexta"]])
        self.fx.usage("lukas-kontexta-cz", 20, 100)
        self.fx.usage("hanakl@nekrachni.cz", 90, 10)
        rc, _, err = self.sessions_cli("open", "kontexta")
        self.assertEqual(rc, 2)
        self.assertIn("Zeptej se Lukáše, na kterém účtu otevřít", err)

    def test_another_account_is_always_a_proposal(self):
        self.named(projects=[self.proj["kontexta"]])
        rc, out, _ = self.sessions_cli("open", "kontexta", "--account", "hanakl@nekrachni.cz")
        self.assertIn("jiný než výchozí účet", out)
        self.assertEqual(self.actions(), [])

    def test_refusals(self):
        self.named(projects=[self.proj["kontexta"], self.proj["kontexta-deploy"]])
        for argv, want in ((("open", "unknown"), "není známý projekt"), (("open", "/etc"), "není známý projekt"),
                           (("open", "kontexta-deploy", "--task", "nasaď"), "PROD/DB-/deploy"),
                           (("open", "kontexta", "--alias", "jarvis"), "alias jarvis"),
                           (("open", "kontexta", "--account", "nikdo"), "neznám"),
                           (("open", "kontexta", "--task", "a\nb"), "řídicí znak")):
            rc, _, err = self.sessions_cli(*argv)
            self.assertEqual(rc, 2, argv)
            self.assertIn(want, err, argv)
        self.assertEqual(self.actions(), [])

    def test_the_name_avoids_live_manifest_and_just_closed_names(self):
        self.named(projects=[self.proj["kontexta"]])
        self.fx.closed({"tmuxName": "claude-server-kontexta-yolo", "at": "2026-11-06T17:20:03Z",
                        "cwd": self.proj["kontexta"]})
        self.fx.clock.ms = J._ts_ms("2026-11-06T18:00:00Z")
        self.named(projects=[self.proj["kontexta"]])
        self.sessions_cli("open", "kontexta")
        self.assertEqual(self.actions()[0][2], "claude-server-kontexta-2-yolo")

    def test_wrong_or_missing_cr_session_refuses(self):
        self.named(projects=[self.proj["kontexta"]])
        self.resp("version", "cr-session v3\n")
        rc, _, err = self.sessions_cli("open", "kontexta")
        self.assertEqual(rc, 2)
        self.assertIn("jinou verzi", err)
        os.unlink(os.path.join(self.fx.cr, "bin", "cr-session"))
        rc, _, err = self.sessions_cli("open", "kontexta")
        self.assertIn("připoj aplikaci k serveru", err)

    def test_off_and_provenance(self):
        self.named(projects=[self.proj["kontexta"]], age_s=11 * 60)
        rc, _, err = self.sessions_cli("open", "kontexta")
        self.assertEqual((rc, "čerstvý Lukášův pokyn" in err), (2, True))
        self.named(projects=[self.proj["kontexta"]])
        other = self.fx.env(environ_extra={"TMUX_PANE": "%2", "CLAUDECODE": "1"}, my_pid=12)
        rc, _, err = self.sessions_cli("open", "kontexta", env=other)
        self.assertEqual(rc, 2)
        self.assertIn("jen Jarvis na Lukášův pokyn", err)
        with open(os.path.join(self.fx.state, "off"), "w") as f:
            f.write("{}")
        rc, _, err = self.sessions_cli("open", "kontexta")
        self.assertIn("vypnutý", err)
        self.assertEqual(self.actions(), [])

    def test_lukas_from_his_shell_needs_no_proposal(self):
        rc, out, err = self.sessions_cli("open", "rag", env=self.app_env())
        self.assertEqual(rc, 0, err)
        self.assertEqual(self.actions()[0][2], "claude-server-rag-2-yolo")

    def test_three_actions_per_prompt(self):
        for _ in range(3):
            self.assertEqual(self.sessions_cli("open", "rag")[0], 0)
        rc, _, err = self.sessions_cli("open", "rag")
        self.assertEqual(rc, 2)
        self.assertIn("nejvýš 3 akce", err)


# ------------------------------------------------------------------------------------------------- close

class CloseTest(Base):
    def setUp(self):
        super().setUp()
        self.resp("facts." + TARGET, facts(git=(2, "-", 0)))

    def test_named_and_clean_closes_at_once_with_the_token(self):
        self.resp("facts." + TARGET, facts())
        self.named(sessions=[TARGET])
        rc, out, err = self.sessions_cli("close", "jarvis-test")
        self.assertEqual(rc, 0, err)
        self.assertEqual(out.strip(), "Zavřel jsem jarvis-test.")
        c = self.actions()[0]
        self.assertEqual(c[:6], ["close", TARGET, "--token", "1794000000.h1", "--by", "jarvis"])
        ev = [e for e in self.ops.ledger() if e["ev"] == "closed"]
        self.assertEqual([(e["name"], e["by"]) for e in ev], [(TARGET, "jarvis")])

    def test_not_named_is_a_proposal_with_the_question(self):
        rc, out, _ = self.sessions_cli("close", "jarvis-test")
        self.assertIn("Zeptej se Lukáše: „Zavřít jarvis-test? Má 2 necommitnuté soubory, konverzaci si pamatuju. Ano?“",
                      out)
        self.assertEqual(self.actions(), [])
        xid = self.ops.proposals(self.fx.clock.ms)[0]["id"]
        rc, out, err = self.cli(cr_jarvis, "approve", xid, "--by", "app:phone1", env=self.app_env())
        self.assertEqual(rc, 0, err)
        self.assertIn("Zavřel jsem jarvis-test.", out)
        self.assertEqual(self.ops.get(xid)["decidedBy"], "app:phone1")

    def test_a_change_between_question_and_yes_refuses(self):
        self.sessions_cli("close", "jarvis-test")
        xid = self.ops.proposals(self.fx.clock.ms)[0]["id"]
        self.resp("facts." + TARGET, facts(git=(5, "-", 0), token="1794000000.h2"))
        rc, out, _ = self.cli(cr_jarvis, "approve", xid, "--by", "app:x", env=self.app_env())
        self.assertEqual(rc, 1)
        self.assertIn("se od otázky změnila", out)
        self.assertEqual(self.actions(), [])

    def test_blok_is_a_proposal_and_never_forced(self):
        self.named(sessions=[TARGET])
        self.resp("facts." + TARGET, facts(status="busy"))
        rc, out, _ = self.sessions_cli("close", "jarvis-test")
        self.assertIn("BLOK: pracuje (běží tah)", out)
        self.assertIn("--force nikdy", out)
        xid = self.ops.proposals(self.fx.clock.ms)[0]["id"]
        rc, out, _ = self.cli(cr_jarvis, "approve", xid, "--by", "app:x", env=self.app_env())
        self.assertEqual(rc, 1)
        self.assertIn("nezavřu, pracuje (běží tah)", out)
        self.assertEqual(self.actions(), [])
        # it finished meanwhile: still no close on that yes (rev-f4 L2, lead: BLOK after "ano" = a refusal; the
        # facts changed, so Lukáš is asked again with the new ones)
        self.named(sessions=[TARGET])
        self.sessions_cli("close", "jarvis-test")
        xid = self.ops.proposals(self.fx.clock.ms)[0]["id"]
        self.resp("facts." + TARGET, facts(token="1794000000.h9"))
        rc, out, _ = self.cli(cr_jarvis, "approve", xid, "--by", "app:x", env=self.app_env())
        self.assertEqual(rc, 1, out)
        self.assertIn("se od otázky změnila", out)
        self.assertEqual(self.actions(), [])

    def test_our_own_blocks_task_and_mail(self):
        self.named(sessions=[TARGET])
        tid = M.now_id("t", self.fx.clock.ms)
        cr_task.append(self.env, {"v": 1, "at": self.fx.clock.ms, "ev": "add", "id": tid, "title": "x", "to": TARGET,
                                  "by": "jarvis"})
        rc, out, _ = self.sessions_cli("close", "jarvis-test")
        self.assertIn("BLOK: má otevřený úkol od Jarvise", out)
        xid = self.ops.proposals(self.fx.clock.ms)[0]["id"]
        # Lukáš's yes covers the block he was told about ...
        rc, out, _ = self.cli(cr_jarvis, "approve", xid, "--by", "app:x", env=self.app_env())
        self.assertEqual(rc, 0, out)
        self.assertEqual(len(self.actions()), 1)

    def test_a_new_block_after_the_question_refuses(self):
        self.sessions_cli("close", "jarvis-test")
        xid = self.ops.proposals(self.fx.clock.ms)[0]["id"]
        rec, _ = self.m.send(TARGET, "Pusť testy.")  # mail to TARGET, queued from the app (no prompt in between)
        self.m.decide_by_app(rec["id"], True, "app:x")
        rc, out, _ = self.cli(cr_jarvis, "approve", xid, "--by", "app:x", env=self.app_env())
        self.assertEqual(rc, 1)
        self.assertIn("čeká na ni zpráva od Jarvise", out)

    def test_never_prod_never_self(self):
        prod = "claude-server-api-yolo--PROD-api"
        self.fx.session(prod, 400, "%4", pane_pid=399)
        self.resp("facts." + prod, facts())
        self.named(sessions=[prod, JARVIS])
        for q, want in (("PROD-api", "nikdy nezavírám"), ("jarvis", "sám sebe")):
            rc, _, err = self.sessions_cli("close", q)
            self.assertEqual(rc, 2, q)
            self.assertIn(want, err)
        self.assertEqual(self.actions(), [])

    def test_limits_five_per_confirmation_twenty_a_day(self):
        rc, _, err = self.sessions_cli("close", *[f"s{i}" for i in range(6)])
        self.assertIn("nejvýš 5", err)
        with self.ops.lock():
            for i in range(20):
                self.ops.log("closed", id="x", name=f"n{i}", by="jarvis")
        self.named(sessions=[TARGET])
        rc, _, err = self.sessions_cli("close", "jarvis-test")
        self.assertEqual(rc, 2)
        self.assertIn("za den nejvýš 20", err)
        self.assertEqual(self.actions(), [])

    def test_bulk_only_restorable_one_proposal_one_yes(self):
        self.resp("facts." + OTHER, facts(pid=300, transcript=0))
        self.named(sessions=[TARGET, OTHER])
        rc, _, err = self.sessions_cli("close", "jarvis-test", "RAG")
        self.assertEqual(rc, 2)
        self.assertIn("RAG nepůjde", err)
        self.resp("facts." + OTHER, facts(pid=300))
        rc, out, _ = self.sessions_cli("close", "jarvis-test", "RAG")
        self.assertIn("Zavřít jarvis-test, RAG? Všechny jdou vrátit, pozor: jarvis-test 2 necommitnuté soubory. Ano?", out)
        self.assertEqual(self.actions(), [])
        p = self.ops.proposals(self.fx.clock.ms)[0]
        self.assertEqual((p["target"], p["targets"]), (f"{TARGET} {OTHER}", [TARGET, OTHER]))
        self.lukas_says("ano")
        self.ops.run(self.spawned[0])
        self.assertEqual([c[1] for c in self.actions()], [TARGET, OTHER])

    def test_a_bulk_close_approved_in_the_app_runs_detached_and_answers_at_once(self):
        self.resp("facts." + OTHER, facts(pid=300))
        self.sessions_cli("close", "jarvis-test", "RAG")
        xid = self.ops.proposals(self.fx.clock.ms)[0]["id"]
        rc, out, err = self.cli(cr_jarvis, "approve", xid, "--by", "app:x", env=self.app_env())
        self.assertEqual(rc, 0, err)
        self.assertEqual(out, "RUNNING zavírám 2 sessions, výsledek přijde jako upozornění.\n")
        self.assertEqual(self.actions(), [])  # nothing closed inside the app's exec
        self.assertEqual((self.spawned, self.ops.get(xid)["state"], self.ops.get(xid)["decidedBy"]),
                         ([xid], "approved", "app:x"))
        self.ops.run(xid)  # what the runner does
        self.assertEqual([c[1] for c in self.actions()], [TARGET, OTHER])

    def test_a_single_close_approved_in_the_app_stays_synchronous(self):
        self.sessions_cli("close", "jarvis-test")
        xid = self.ops.proposals(self.fx.clock.ms)[0]["id"]
        rc, out, _ = self.cli(cr_jarvis, "approve", xid, "--by", "app:x", env=self.app_env())
        self.assertEqual((rc, out.strip(), self.spawned), (0, "Zavřel jsem jarvis-test.", []))

    def test_check_close_prints_levels_and_the_sentence(self):
        rc, out, _ = self.sessions_cli("check-close", "jarvis-test")
        self.assertEqual(rc, 0)
        self.assertIn("  VAROVÁNÍ: 2 necommitnuté soubory", out)
        self.assertIn("  INFO: konverzace se neztratí, jde vrátit", out)
        self.assertIn("Věta: „Zavřít jarvis-test? Má 2 necommitnuté soubory, konverzaci si pamatuju.“", out)

    def test_refused_blocked_from_cr_session_is_told(self):
        self.resp("facts." + TARGET, facts())
        self.named(sessions=[TARGET])
        self.resp("close." + TARGET, "REFUSED_BLOCKED BUSY,VIEWER\n")
        rc, out, _ = self.sessions_cli("close", "jarvis-test")
        self.assertEqual(rc, 1)
        self.assertIn("nezavřu, pracuje (běží tah) a někdo se na ni právě dívá", out)


# ------------------------------------------------------------------------------------------------- reopen

class ReopenTest(Base):
    def setUp(self):
        super().setUp()
        self.name = "claude-server-kontexta-yolo--pdf-sloupce"
        self.fx.closed({"tmuxName": self.name, "at": "2026-11-06T17:20:03Z", "cwd": self.proj["kontexta"],
                        "sessionId": "abc", "account": "lukas-kontexta-cz", "by": "jarvis", "title": "Sloupce"})

    def test_named_restores_at_once_by_its_record(self):
        self.named(closed=[self.name])
        rc, out, err = self.sessions_cli("reopen", "pdf-sloupce")
        self.assertEqual(rc, 0, err)
        self.assertIn("Vrátil jsem pdf-sloupce.", out)
        self.assertEqual(self.actions()[0][:2], ["restore", "2026-11-06T17:20:03Z"])

    def test_exists_turns_into_a_proposal_under_the_suggested_name(self):
        self.named(closed=[self.name])
        self.resp("restore", f"CR_RESTORE EXISTS {self.name}-2\n")
        rc, out, _ = self.sessions_cli("reopen", "pdf-sloupce")
        self.assertEqual(rc, 1)
        self.assertIn("Vrátit ji jako pdf-sloupce-2? Ano?", out)
        p = [x for x in self.ops.proposals(self.fx.clock.ms) if x["kind"] == "restore"][0]
        self.assertEqual(p["target"], self.name + "-2")
        self.assertIsNone(self.ops.get(p["id"])["prompt"])  # rev-f4 L1: no "ano" approves what Lukáš has not seen
        self.resp("restore", "CR_RESTORE RESTORED 1 a a\n")
        self.ops.decide(p["id"], True, "app:x")
        self.assertEqual(self.actions()[-1][:4], ["restore", "2026-11-06T17:20:03Z", "--name", self.name + "-2"])

    def test_running_elsewhere_and_not_named(self):
        self.named(closed=[self.name])
        self.resp("restore", f"CR_RESTORE RUNNING_ELSEWHERE {OTHER}\n")
        rc, out, _ = self.sessions_cli("reopen", "pdf-sloupce")
        self.assertIn("Ta konverzace už běží v RAG.", out)
        self.named()
        rc, out, _ = self.sessions_cli("reopen", "pdf-sloupce")
        self.assertIn("Vrátím pdf-sloupce. Ano?", out)

    def test_closed_lists_newest_first(self):
        rc, out, _ = self.sessions_cli("closed")
        self.assertIn("pdf-sloupce · zavřel Jarvis · účet lukas-kontexta · «Sloupce» · at=2026-11-06T17:20:03Z", out)


# ------------------------------------------------------------------------------------------------- proposals

class ProposalsTest(Base):
    def test_the_contract_lists_mail_and_actions(self):
        self.m.send(TARGET, "Pusť testy.")
        self.sessions_cli("open", "rag")
        rc, out, _ = self.cli(cr_jarvis, "proposals", "--json")
        d = json.loads(out)
        self.assertEqual([x["kind"] for x in d], ["mail", "open"])
        self.assertEqual(d[0]["detail"], "Pusť testy.")
        self.assertEqual(d[0]["expires"] - d[0]["created"], 10 * 60 * 1000)
        for x in d:
            self.assertTrue({"id", "kind", "target", "title", "detail", "created", "expires"} <= set(x))
        self.fx.clock.advance(601)
        self.assertEqual(json.loads(self.cli(cr_jarvis, "proposals", "--json")[1]), [])

    def test_approve_mail_delegates_and_claude_sessions_are_refused(self):
        rec, _ = self.m.send(TARGET, "Pusť testy.")
        rc, _, err = self.cli(cr_jarvis, "approve", rec["id"], "--by", "app:x")  # Jarvis's own pane
        self.assertEqual(rc, 2)
        self.assertIn("Claude session", err)
        rc, out, _ = self.cli(cr_jarvis, "approve", rec["id"], "--by", "app:x", env=self.app_env())
        self.assertEqual(rc, 0)
        self.assertEqual(self.m.get(rec["id"])["state"], "queued")

    def test_reject_runs_nothing_and_late_approval_expires(self):
        self.sessions_cli("open", "rag")
        xid = self.ops.proposals(self.fx.clock.ms)[0]["id"]
        rc, out, _ = self.cli(cr_jarvis, "reject", xid, "--by", "app:x", env=self.app_env())
        self.assertEqual((rc, self.ops.get(xid)["state"]), (0, "rejected"))
        self.sessions_cli("open", "rag")
        xid = [p for p in self.ops.proposals(self.fx.clock.ms)][0]["id"]
        self.fx.clock.advance(601)
        rc, _, err = self.cli(cr_jarvis, "approve", xid, "--by", "app:x", env=self.app_env())
        self.assertEqual(rc, 2)
        self.assertIn("propadl", err)
        self.assertEqual(self.actions(), [])

    def test_the_next_prompt_decides(self):
        self.sessions_cli("open", "rag")
        self.lukas_says("ne, počkej")
        self.assertEqual([r["state"] for r in self.ops.all()], ["dropped"])
        self.sessions_cli("open", "rag", "--alias", "a")
        self.sessions_cli("open", "rag", "--alias", "b")
        a, b = [r["id"] for r in self.ops.all() if r["state"] == "proposed"]
        self.lukas_says(f"jen {b}")
        self.assertEqual({r["id"]: r["state"] for r in self.ops.all() if r["id"] in (a, b)}, {a: "dropped", b: "approved"})
        self.assertEqual(self.spawned, [b])
        # a proposal from two prompts ago never counts
        self.sessions_cli("open", "rag", "--alias", "c")
        self.lukas_says("co je nového?")
        self.lukas_says("ano")
        self.assertEqual(self.spawned, [b])

    def test_a_jarvis_prompt_drops_and_the_runner_runs_once(self):
        self.sessions_cli("open", "rag")
        self.lukas_says("ano")
        xid = self.spawned[0]
        self.ops.run(xid)
        with self.assertRaises(JO.Refused):
            self.ops.run(xid)
        self.assertEqual(len(self.actions()), 1)
        self.sessions_cli("open", "rag", "--alias", "z")
        self.lukas_says("[jarvis wake] Výsledek úkolu")
        self.assertEqual(self.ops.all()[-1]["state"], "proposed")  # rev-f4 L7: a wake decides nothing

    def test_the_real_detached_runner_executes_the_approved_action(self):
        # the hook's spawn, for real: a detached `cr_jarvis.py run` under the temp HOME / state dir, the atrapa
        # as cr-session; the clock is the real one in the child, so the proposal is made at "now"
        import time
        self.fx.clock.ms = int(time.time() * 1000)
        self.prompt()
        self.sessions_cli("open", "rag")
        xid = self.ops.proposals(self.fx.clock.ms)[0]["id"]
        with self.ops.lock():
            r = self.ops.get(xid)
            self.ops.transition(r, "approved", decidedBy="prompt")
        JO.Ops(self.env, mail=self.m)._spawn(xid)
        for _ in range(100):
            if self.ops.get(xid)["state"] in JO.FINAL:
                break
            time.sleep(0.1)
        self.assertEqual(self.ops.get(xid)["state"], "done", self.ops.get(xid).get("message"))
        self.assertEqual(self.actions()[0][:3], ["open", "--name", "claude-server-rag-2-yolo"])

    def test_sweep_expires_and_spawns_what_the_spawn_missed(self):
        # rev-f4 M2: the daemon never runs an action in its tick, it hands it to the detached runner (once per 30 s)
        self.sessions_cli("open", "rag")
        self.lukas_says("ano")
        self.sessions_cli("open", "rag", "--alias", "q")
        self.spawned.clear()
        self.fx.clock.advance(601)
        self.ops.sweep(self.fx.clock.ms)
        st = {r["args"].get("alias"): r["state"] for r in self.ops.all()}
        self.assertEqual(st, {None: "approved", "q": "expired"})
        self.assertEqual(len(self.spawned), 1)
        self.assertEqual(self.actions(), [])
        self.fx.clock.advance(10)
        self.ops.sweep(self.fx.clock.ms)
        self.assertEqual(len(self.spawned), 1)
        self.fx.clock.advance(31)
        self.ops.sweep(self.fx.clock.ms)
        self.assertEqual(len(self.spawned), 2)

    def test_a_run_whose_process_died_is_failed_by_the_sweep(self):
        self.sessions_cli("open", "rag")
        xid = self.ops.proposals(self.fx.clock.ms)[0]["id"]
        with self.ops.lock():
            self.ops.transition(self.ops.get(xid), "running")
        self.fx.clock.advance(301)
        self.ops.sweep(self.fx.clock.ms)
        self.assertEqual(self.ops.get(xid)["state"], "failed")
        self.assertEqual(self.actions(), [])

    def test_ids_fit_the_apps_pattern(self):
        self.sessions_cli("open", "rag")
        self.m.send(TARGET, "x")
        for p in self.ops.proposals(self.fx.clock.ms):
            self.assertRegex(p["id"], r"^[xm]-[A-Za-z0-9._:-]+$")

    def test_delta_tells_the_results_of_approved_actions(self):
        cr_digest.write_cursor(self.env, 0, 0, None, None, self.fx.clock.ms - 1)
        self.sessions_cli("open", "rag")
        self.lukas_says("ano")
        self.ops.run(self.spawned[0])
        items, _ = JO.digest_items(self.env, self.fx.clock.ms - 60000, self.fx.clock.ms)
        lines = [JO.ops_line(i) for i in items]
        self.assertIn("⚙ Otevřel jsem rag-2 v rag na účtu výchozí.", lines)
        self.assertTrue(any("Lukáš řekl ano" in l for l in lines))


class PromptAndMailTest(Base):
    def test_the_hook_records_named_projects_and_closed_names_never_the_text(self):
        self.fx.closed({"tmuxName": "claude-server-kontexta-yolo--pdf-sloupce", "at": "2026-11-06T17:20:03Z",
                        "cwd": self.proj["kontexta"]})
        self.lukas_says("Otevři session v kontexta a vrať pdf-sloupce, heslo je tajné")
        with open(os.path.join(self.fx.state, "last-prompt")) as f:
            lp = json.load(f)
        self.assertEqual(lp["namedProjects"], [self.proj["kontexta"]])
        self.assertEqual(lp["namedClosed"], ["claude-server-kontexta-yolo--pdf-sloupce"])
        self.assertNotIn("tajné", json.dumps(lp, ensure_ascii=False))

    def test_a_new_sessions_task_waiting_on_the_trust_dialog_is_told_once(self):
        rec, why = self.m.send_approved(TARGET, "Oprav sloupce.", by="op:x-1")
        self.assertIsNone(why)
        self.assertEqual((rec["state"], rec["opened"]), ("queued", True))
        since = self.fx.clock.ms
        self.m._block(rec, "waiting")
        self.fx.clock.advance(3 * 60)
        items, _ = M.digest_items(self.env, since, self.fx.clock.ms)
        self.assertIn("✉ {}: nová session jarvis-test čeká na potvrzení důvěry ke složce (nebo jiný dialog), zadání "
                      "čeká ve frontě".format(rec["id"]), [M.mail_line(i) for i in items if i["kind"] == "mail-opened-wait"])
        later, _ = M.digest_items(self.env, self.fx.clock.ms, self.fx.clock.ms + 60000)
        self.assertEqual([i for i in later if i["kind"] == "mail-opened-wait"], [])

    def test_send_approved_keeps_every_other_check(self):
        self.allow(TARGET, "claude-server-x-yolo--PROD")  # an allowlisted exact name resolves without a snapshot
        for to, want in ((JARVIS, "sám sobě"), ("claude-server-x-yolo--PROD", "vysokým dopadem"), (OTHER, "mail-allow")):
            rec, why = self.m.send_approved(to, "x", by="op:x-1")
            self.assertIsNone(rec)
            self.assertIn(want, why or "")


# ------------------------------------------------------------------------------------------------- alert, stale

class AlertTest(Base):
    def test_a_close_by_jarvis_is_one_done_line_with_the_name(self):
        w = JA.AlertWorker(self.env, None, mail=self.m, presence=lambda: None)
        w.tick([], {}, J.fold_events_file(self.env, ignore=JARVIS), self.fx.clock.ms, JARVIS)
        self.fx.clock.advance(1)
        self.named(sessions=[TARGET])
        self.resp("facts." + TARGET, facts())
        self.sessions_cli("close", "jarvis-test")
        self.fx.clock.advance(1)
        w.tick([], {}, J.fold_events_file(self.env, ignore=JARVIS), self.fx.clock.ms, JARVIS)
        with open(self.env.alerts_path) as f:
            al = [JA.parse_alert(l) for l in f if " hb " not in l]
        self.assertEqual([(a["kind"], a["text"], a["name"]) for a in al], [("done", "Zavřel jsem jarvis-test.", TARGET)])
        with open(self.env.alerts_path) as f:
            line = [l for l in f if " hb " not in l][0].split()
        self.assertEqual(len(line), 6)
        self.assertEqual(base64.b64decode(line[5]).decode(), TARGET)

    def test_dnd_holds_it(self):
        w = JA.AlertWorker(self.env, None, mail=self.m, presence=lambda: None)
        w.tick([], {}, {}, self.fx.clock.ms, JARVIS)
        JA.write_mode(self.env, "dnd")
        with self.ops.lock():
            self.ops.log("closed", id="x", name=TARGET, by="jarvis")
        self.fx.clock.advance(1)
        w.tick([], {}, {}, self.fx.clock.ms, JARVIS)
        with open(self.env.alerts_path) as f:
            self.assertFalse(any(" hb " not in l for l in f))


class StaleTest(Base):
    def git(self, d, *args):
        subprocess.run(["git", "-C", d, *args], check=True, capture_output=True, timeout=30,
                       env={"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": self.fx.home,
                            "GIT_CONFIG_NOSYSTEM": "1"})

    def test_idle_clean_no_task_oldest_first(self):
        now = self.fx.clock.ms
        clean, dirty = os.path.join(self.fx.root, "clean"), os.path.join(self.fx.root, "dirty")
        for d in (clean, dirty):
            os.makedirs(d)
            self.git(d, "init", "-q")
        with open(os.path.join(dirty, "f"), "w") as f:
            f.write("x")
        st = lambda age_d, cwd, state="idle": {"state": state, "stateSince": now - int(age_d * DAY), "cwd": cwd,
                                                "short": "x"}
        sessions = {"claude-server-a-yolo--old": st(20, clean), "claude-server-a-yolo--mid": st(9, clean),
                    "claude-server-a-yolo--young": st(2, clean), "claude-server-a-yolo--dirty": st(30, dirty),
                    "claude-server-a-yolo--busy": st(30, clean, "busy"), "claude-server-PROD-yolo": st(30, clean),
                    "claude-server-a-yolo--tasked": st(30, clean), "claude-server-a-yolo--plain": st(10, self.proj["rag"])}
        tid = M.now_id("t", now)
        cr_task.append(self.env, {"v": 1, "at": now, "ev": "add", "id": tid, "title": "x",
                                  "to": "claude-server-a-yolo--tasked", "by": "jarvis"})
        rows = JO.stale(self.env, now, sessions=sessions)
        self.assertEqual([r["short"] for r in rows], ["old", "plain", "mid"])

    def test_briefing_offers_them_once_a_week(self):
        with mock.patch.object(JO, "stale", lambda env, now, **kw: [{"short": "a"}, {"short": "b"}]):
            a = mock.Mock(which="morning", summary=False, say=True, json=False)
            self.assertIn("Můžu zavřít dvě staré sessions. Chcete?", cr_digest.cmd_briefing(self.env, a, self.fx.clock.ms))
            self.fx.clock.advance(3600)
            self.assertNotIn("Můžu zavřít", cr_digest.cmd_briefing(self.env, a, self.fx.clock.ms))
            self.fx.clock.advance(7 * 86400)
            self.assertIn("Můžu zavřít", cr_digest.cmd_briefing(self.env, a, self.fx.clock.ms))


# ------------------------------------------------------------------------------------------------- review fixes

class ReviewFixTest(Base):
    """rev-f4-srv: H1 (named is not intent), M1 (bare yes), M2, L1-L7. The reviewer's probes are the first tests."""

    def status(self):
        with open(os.path.join(self.fx.state, "status.json"), "w") as f:
            json.dump({"sessions": {TARGET: {"alias": "jarvis-test", "short": "jarvis-test"},
                                    OTHER: {"alias": "RAG", "short": "RAG"}}}, f)

    # ---- H1
    def test_h1_probe_a_question_about_rag_never_closes_it(self):
        self.status()
        self.resp("facts." + OTHER, facts(pid=300, transcript=0, git=(3, "-", 0)))
        self.lukas_says("kolik tokenů žere rag pipeline?")
        lp = self.m.last_prompt()
        self.assertEqual((lp["named"], lp["closeIntent"]), ([OTHER], False))
        rc, out, _ = self.sessions_cli("close", "RAG")
        self.assertEqual(rc, 0)
        self.assertIn("Návrh x-", out)
        self.assertEqual(self.actions(), [])

    def test_h1_close_needs_intent_restorable_and_no_warning(self):
        self.status()
        for f, closes in ((facts(pid=300, git=(3, "-", 0)), False), (facts(pid=300, transcript=0), False),
                          (facts(pid=300, event=("stop", 60, 0)), False), (facts(pid=300), True)):
            self.lukas_says("zavři RAG")
            self.assertTrue(self.m.last_prompt()["closeIntent"])
            self.resp("facts." + OTHER, f)
            self.sessions_cli("close", "RAG")
            self.assertEqual(bool(self.actions()), closes, f)

    def test_h1_project_named_in_a_question_is_not_an_open(self):
        self.lukas_says("co dělá kontexta?")
        self.assertEqual((self.m.last_prompt()["namedProjects"], self.m.last_prompt()["openIntent"]),
                         ([self.proj["kontexta"]], False))
        rc, out, _ = self.sessions_cli("open", "kontexta")
        self.assertIn("Lukáš o otevření neřekl", out)
        self.assertEqual(self.actions(), [])
        self.lukas_says("otevři novou session v kontexta")
        self.sessions_cli("open", "kontexta")
        self.assertEqual(self.actions()[0][0], "open")

    def test_h1_an_open_without_a_named_project_asks(self):
        self.lukas_says("otevři novou session, ať se podívá na ten bug")
        rc, out, _ = self.sessions_cli("open", "kontexta")
        self.assertIn("projekt Lukáš ve zprávě nejmenoval", out)
        self.assertEqual(self.actions(), [])

    def test_h1_reopen_needs_intent(self):
        name = "claude-server-kontexta-yolo--pdf-sloupce"
        self.fx.closed({"tmuxName": name, "at": "2026-11-06T17:20:03Z", "cwd": self.proj["kontexta"],
                        "account": "lukas-kontexta-cz"})
        self.lukas_says("co dělala pdf-sloupce?")
        self.assertIn("Lukáš o vrácení neřekl", self.sessions_cli("reopen", "pdf-sloupce")[1])
        self.lukas_says("vrať pdf-sloupce")
        self.sessions_cli("reopen", "pdf-sloupce")
        self.assertEqual(self.actions()[0][0], "restore")

    def test_h1_intent_words(self):
        for text, key, want in (("Zavři RAG", "closeIntent", True), ("zavřít to můžeš", "closeIntent", True),
                                ("zavřená session", "closeIntent", False), ("kill it", "closeIntent", True),
                                ("otevři kontexta", "openIntent", True), ("založ novou session", "openIntent", True),
                                ("otevřený úkol", "openIntent", False), ("vrať ji", "reopenIntent", True),
                                ("obnovení limitu", "reopenIntent", False)):
            self.assertEqual(JO.intents(text)[key], want, text)
        for text, want in (("pošli to", True), ("ať to pushne", True), ("opravdu?", False), ("Oprav to", True),
                           ("napsal jsi?", False), ("udělej to", True)):
            self.assertEqual(bool(M.SEND_INTENT.search(text)), want, text)

    # ---- M1
    def test_m1_probe_ok_thanks_and_a_question_approves_nothing(self):
        for a in ("a", "b", "c"):
            self.sessions_cli("open", "rag", "--alias", a)
        self.lukas_says("ok díky, a co dělá kontexta?")
        self.assertEqual({r["state"] for r in self.ops.all()}, {"dropped"})
        # and with a lone proposal too: a yes followed by anything is not a bare yes
        self.sessions_cli("open", "rag", "--alias", "d")
        self.lukas_says("ok díky, a co dělá kontexta?")
        self.assertEqual({r["state"] for r in self.ops.all()}, {"dropped"})
        self.assertEqual(self.spawned, [])

    def test_m1_bare_yes_only_for_a_lone_proposal_ids_otherwise(self):
        self.sessions_cli("open", "rag", "--alias", "a")
        self.m.send(TARGET, "Pusť testy.")
        self.lukas_says("ano")
        self.assertEqual(self.spawned, [])
        self.assertEqual([r["state"] for r in self.m.all()], ["dropped"])
        self.sessions_cli("open", "rag", "--alias", "b")
        self.lukas_says("Ano.")
        self.assertEqual(len(self.spawned), 1)

    def test_f5a_a_spoken_yes_runs_the_lone_action_proposal(self):
        # the app's conversation mode: `[hlas] Ano.` decides an action exactly like a typed "Ano."
        self.sessions_cli("open", "rag", "--alias", "b")
        self.lukas_says("[hlas] Ano.")
        self.assertEqual(len(self.spawned), 1)

    def test_m1_close_targets_per_prompt_add_up_to_five(self):
        for i, n in enumerate(("x1", "x2", "x3", "x4")):
            name = f"claude-server-z-yolo--{n}"
            self.fx.session(name, 500 + 10 * i, f"%{9 + i}", pane_pid=499 + 10 * i)
            self.resp("facts." + name, facts(pid=500 + 10 * i))
        self.resp("facts." + TARGET, facts())
        self.resp("facts." + OTHER, facts(pid=300))
        self.assertEqual(self.sessions_cli("close", "jarvis-test", "RAG", "x1")[0], 0)
        rc, _, err = self.sessions_cli("close", "x2", "x3", "x4")
        self.assertEqual(rc, 2)
        self.assertIn("nejvýš 5 zavíraných sessions dohromady", err)

    # ---- L1 (M2: ProposalsTest.test_sweep_expires_and_spawns_what_the_spawn_missed)
    def test_l1_a_restore_proposal_from_a_runner_waits_for_its_id_or_the_card(self):
        name = "claude-server-kontexta-yolo--pdf-sloupce"
        self.fx.closed({"tmuxName": name, "at": "2026-11-06T17:20:03Z", "cwd": self.proj["kontexta"]})
        self.sessions_cli("reopen", "pdf-sloupce")
        self.resp("restore", f"CR_RESTORE EXISTS {name}-2\n")
        self.lukas_says("ano")
        self.ops.run(self.spawned[0])
        new = [r for r in self.ops.all() if r["state"] == "proposed"][0]
        self.lukas_says("ano")
        self.assertEqual(self.ops.get(new["id"])["state"], "dropped")
        self.assertEqual(len(self.spawned), 1)

    # ---- L3
    def test_l3_the_day_slot_is_reserved_under_the_lock_before_cr_session_closes(self):
        self.resp("facts." + TARGET, facts())
        self.named(sessions=[TARGET])
        seen = []
        real = self.ops.cr.call

        def call(*args, **kw):
            if args[0] == "close":
                seen.append([e["ev"] for e in self.ops.ledger() if e.get("name") == TARGET])
            return real(*args, **kw)
        self.ops.cr.call = call
        self.sessions_cli("close", "jarvis-test", ops=self.ops)
        self.assertEqual(seen, [["closing"]])
        self.assertEqual(self.ops.closes_today(self.fx.clock.ms), 1)  # closing + closed = one close

    # ---- L4
    def test_l4_runner_and_cr_session_never_run_as_jarvis(self):
        env = self.fx.env(environ_extra={"TMUX": "/tmp/tmux-1/default,1,0", "TMUX_PANE": "%1", "CLAUDECODE": "1",
                                         "CLAUDE_CONFIG_DIR": "/x"}, my_pid=12)
        JO.CrSession(env).call("version")
        with open(os.path.join(self.fx.home, "cr-env.log")) as f:
            self.assertEqual(f.read().splitlines()[-1], "TMUX= PANE= CC= CFG=")
        got = []
        with mock.patch.object(JO.Ops, "_popen", staticmethod(lambda *a, **kw: got.append(kw["env"]))):
            JO.Ops(env, mail=self.m)._spawn("x-20261108T000000-abcd")
        self.assertFalse(set(JO.SCRUB_ENV) & set(got[0]))
        self.assertEqual(got[0]["HOME"], self.fx.home)

    # ---- L5
    def test_l5_force_is_an_argparse_refusal_the_gate_is_only_the_second_layer(self):
        # the gate lets a quoted "--force" through as text; the CLI has no such option and stops it before any action
        with self.assertRaises(SystemExit) as cm:
            self.sessions_cli("close", "jarvis-test", "--force")
        self.assertEqual(cm.exception.code, 2)
        with self.assertRaises(SystemExit):
            self.sessions_cli("close", "jarvis-test", "--forc")
        self.assertEqual(self.actions(), [])

    # ---- L6
    def test_l6_nothing_under_jarvis_and_outside_the_policy_is_said(self):
        sub = os.path.join(self.proj["jarvis"], "sub")
        os.makedirs(sub)
        self.fx.closed({"tmuxName": "claude-server-sub-yolo", "at": "2026-11-06T17:20:03Z", "cwd": sub})
        self.assertNotIn(os.path.realpath(sub), JO.known_projects(self.env))
        self.named(projects=[self.proj["kontexta"]])
        rc, out, _ = self.sessions_cli("open", "kontexta", "--account", "default")
        self.assertIn("MIMO POLITIKU SLOŽKY", out)
        self.assertIn("MIMO POLITIKU", self.ops.proposals(self.fx.clock.ms)[0]["detail"])

    # ---- L7
    def test_l7_a_wake_keeps_an_action_proposal_for_lukas_next_yes(self):
        self.sessions_cli("open", "rag")
        self.lukas_says("[jarvis wake] Výsledek úkolu t-1 · cr-task show t-1")
        self.assertEqual(self.ops.all()[0]["state"], "proposed")
        self.lukas_says("ano")
        self.assertEqual(self.ops.all()[0]["state"], "approved")


_real_prompt_seen = M.Mail.prompt_seen


def _prompt_seen_wake_drops_all(self, payload, now):
    # the F3/F4 behaviour before L7: a [jarvis prompt dropped every pending proposal, mail and actions
    if isinstance(payload, dict) and str(payload.get("prompt", "")).lstrip().startswith("[jarvis"):
        self.decide_proposals(None, None, now)
        self._decide_ops(None, None, now)
    return _real_prompt_seen(self, payload, now)


def _sweep_runs(self, now):
    late = [r["id"] for r in self.all() if r["state"] == "approved"]
    for xid in late:
        self.run(xid)
    return late


_real_propose = JO.Ops.propose


def _propose_with_prompt(self, kind, target, title, detail, args, prompt_id, now, targets=None):
    return _real_propose(self, kind, target, title, detail, args, prompt_id or (self.mail.last_prompt() or {}).get("id"),
                         now, targets)


_real_log = JO.Ops.log


def _log_without_reservation(self, ev, **kw):
    if ev != "closing":
        _real_log(self, ev, **kw)


_real_pick = JO.pick_account


def _pick_no_allowed(env, folder, now, usage=None):
    return dict(_real_pick(env, folder, now, usage), allowed=[])


# ------------------------------------------------------------------------------------------------- mutations

class MutationTest(unittest.TestCase):
    """Each rule's test must FAIL with its check switched off."""

    MUTANTS = {
        "I7 never closed": (lambda: mock.patch.object(JO, "high_impact", lambda n: False),
                            [("CloseTest", "test_never_prod_never_self")]),
        "named = immediate": (lambda: mock.patch.object(JO.Ops, "caller", lambda self, now: "lukas"),
                              [("CloseTest", "test_not_named_is_a_proposal_with_the_question"),
                               ("OpenTest", "test_a_project_jarvis_chose_is_a_proposal_and_yes_runs_it")]),
        "blocks": (lambda: mock.patch.object(JO, "cr_blocks", lambda f: []),
                   [("CloseTest", "test_blok_is_a_proposal_and_never_forced"), ("FactsTest", "test_blocks_like_cr_session")]),
        "per confirmation": (lambda: mock.patch.object(JO, "CLOSE_PER_CONFIRM", 99),
                             [("CloseTest", "test_limits_five_per_confirmation_twenty_a_day")]),
        "per day": (lambda: mock.patch.object(JO, "CLOSE_PER_DAY", 999),
                    [("CloseTest", "test_limits_five_per_confirmation_twenty_a_day")]),
        "version": (lambda: mock.patch.object(JO, "CR_SESSION_VERSION", "cr-session v3"),
                    [("OpenTest", "test_named_project_on_the_default_account_opens_at_once")]),
        "usage limits": (lambda: mock.patch.object(JO, "SEVEN_DAY_MAX", 101),
                         [("OpenTest", "test_a_spent_default_account_proposes_the_other_allowed_one")]),
        "named project": (lambda: mock.patch.object(JO, "named_projects", lambda env, p: list(JO.known_projects(env))),
                          [("ReviewFixTest", "test_h1_an_open_without_a_named_project_asks")]),
        "proposal age": (lambda: mock.patch.object(JO, "PROPOSAL_MS", 10 ** 12),
                         [("ProposalsTest", "test_reject_runs_nothing_and_late_approval_expires")]),
        "restorable bulk": (lambda: mock.patch.object(JO, "facts_report", _always_restorable),
                            [("CloseTest", "test_bulk_only_restorable_one_proposal_one_yes")]),
        # rev-f4-srv fixes
        "H1 intent": (lambda: mock.patch.dict(JO.INTENT, {k: __import__("re").compile("") for k in JO.INTENT}),
                      [("ReviewFixTest", "test_h1_probe_a_question_about_rag_never_closes_it"),
                       ("ReviewFixTest", "test_h1_project_named_in_a_question_is_not_an_open"),
                       ("ReviewFixTest", "test_h1_reopen_needs_intent")]),
        "H1 no warning, restorable": (lambda: mock.patch.object(JO, "facts_report", _no_warn_restorable),
                                      [("ReviewFixTest", "test_h1_close_needs_intent_restorable_and_no_warning")]),
        "H1 task = proposal": (lambda: mock.patch.object(S, "TASK_NEEDS_YES", False),
                               [("OpenTest", "test_a_task_makes_it_a_proposal_and_yes_queues_the_task")]),
        "M1 bare yes": (lambda: mock.patch.object(M, "APPROVE", __import__("re").compile(r"^\s*(ano|ok)\b", 2)),
                        [("ReviewFixTest", "test_m1_probe_ok_thanks_and_a_question_approves_nothing")]),
        "M1 single": (lambda: mock.patch.object(M.Mail, "_pending", lambda self: 1),
                      [("ReviewFixTest", "test_m1_bare_yes_only_for_a_lone_proposal_ids_otherwise")]),
        "M1 targets": (lambda: mock.patch.object(JO, "CLOSE_TARGETS_PER_PROMPT", 99),
                       [("ReviewFixTest", "test_m1_close_targets_per_prompt_add_up_to_five")]),
        "M2": (lambda: mock.patch.object(JO.Ops, "sweep", _sweep_runs),
               [("ProposalsTest", "test_sweep_expires_and_spawns_what_the_spawn_missed")]),
        "L1": (lambda: mock.patch.object(JO.Ops, "propose", _propose_with_prompt),
               [("ReviewFixTest", "test_l1_a_restore_proposal_from_a_runner_waits_for_its_id_or_the_card")]),
        "L2": (lambda: mock.patch.object(JO.Ops, "same_facts", staticmethod(
                   lambda at, rep: bool(at.get("blocks")) or at.get("hash") == rep["hash"])),
               [("CloseTest", "test_blok_is_a_proposal_and_never_forced")]),
        "L3": (lambda: mock.patch.object(JO.Ops, "log", _log_without_reservation),
               [("ReviewFixTest", "test_l3_the_day_slot_is_reserved_under_the_lock_before_cr_session_closes")]),
        "L4": (lambda: mock.patch.object(JO, "SCRUB_ENV", ()),
               [("ReviewFixTest", "test_l4_runner_and_cr_session_never_run_as_jarvis")]),
        "L6 subtree": (lambda: mock.patch.object(JO, "banned_folder", lambda rp, banned: rp in banned),
                       [("ReviewFixTest", "test_l6_nothing_under_jarvis_and_outside_the_policy_is_said")]),
        "L6 policy": (lambda: mock.patch.object(JO, "pick_account", _pick_no_allowed),
                      [("ReviewFixTest", "test_l6_nothing_under_jarvis_and_outside_the_policy_is_said")]),
        "F5a [hlas]": (lambda: mock.patch.object(M, "strip_voice", lambda p: p),
                       [("ReviewFixTest", "test_f5a_a_spoken_yes_runs_the_lone_action_proposal")]),
        "L7": (lambda: mock.patch.object(M.Mail, "prompt_seen", _prompt_seen_wake_drops_all),
               [("ReviewFixTest", "test_l7_a_wake_keeps_an_action_proposal_for_lukas_next_yes")]),
    }

    def test_every_rule_test_fails_without_its_check(self):
        mod = sys.modules[__name__]
        for what, (patcher, tests) in self.MUTANTS.items():
            for cls, name in tests:
                res = unittest.TestResult()
                getattr(mod, cls)(name).run(res)
                self.assertTrue(res.wasSuccessful(), f"{cls}.{name} fails even unmutated: {res.failures + res.errors}")
                with patcher():
                    res = unittest.TestResult()
                    getattr(mod, cls)(name).run(res)
                self.assertGreater(len(res.failures) + len(res.errors), 0, f"{what}: {cls}.{name} passed a mutant")


_real_report = JO.facts_report


def _no_warn_restorable(f, extra=()):
    r = _real_report(f, extra)
    r.update(warn=[], restorable=True)
    return r


def _always_restorable(f, extra=()):
    r = _real_report(f, extra)
    r["restorable"] = True
    return r


if __name__ == "__main__":
    unittest.main()
