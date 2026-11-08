"""Jarvis F2b: watches, verified "done", `stop bg`, the checkout-conflict warning and the audit.

The fake box of test_jarvismail (Fx + FakeTmux) plus REAL git repositories in the test's own temp dir (a bare
"remote" and a clone; commits with fixed dates, global and system git config switched off for the helper), and a fake
`gh` handed to jarviswatch.Repo. Nothing here calls tmux, the real gh, creates a link or deletes outside its own
TemporaryDirectory (docs/TESTING.md). SourceMutationTest proves each new check: it rewrites one line of the module in
memory and the test that guards it must fail.
"""
import contextlib
import json
import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import cr_digest  # noqa: E402
import cr_mail  # noqa: E402
import cr_task  # noqa: E402
import jarvis_d  # noqa: E402
import jarvislib as J  # noqa: E402
import jarvismail as M  # noqa: E402
import jarviswatch as JW  # noqa: E402
import test_jarvismail as TM  # noqa: E402  (Base has no tests of its own, so nothing runs twice)
from jarvis_fixtures import ev_line  # noqa: E402

TARGET, OTHER, JARVIS = TM.TARGET, TM.OTHER, TM.JARVIS
GIT_ENV = {"GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1", "GIT_TERMINAL_PROMPT": "0"}


class Out:
    def __init__(self):
        self.s = ""

    def write(self, x):
        self.s += x


class FakeGh:
    """`gh run list --commit <sha>` answered from self.runs[sha]; every call is logged."""

    def __init__(self):
        self.runs = {}
        self.calls = []

    def __call__(self, cwd, args):
        self.calls.append(args)
        sha = args[args.index("--commit") + 1]
        return json.dumps([dict(r, headSha=sha) for r in self.runs.get(sha, [])])

    def set(self, sha, *runs):
        self.runs[sha] = [{"databaseId": i, "status": s, "conclusion": c, "workflowName": n}
                          for i, (s, c, n) in enumerate(runs)]


class Base(TM.Base):
    def setUp(self):
        super().setUp()
        self.gh = FakeGh()
        self.remote = os.path.join(self.fx.root, "remote.git")
        self.work = os.path.join(self.fx.root, "work")
        self.git("init", "-q", "--bare", "-b", "main", self.remote, cwd=self.fx.root)
        self.git("clone", "-q", self.remote, self.work, cwd=self.fx.root)
        self.git("checkout", "-q", "-b", "main")
        os.makedirs(os.path.join(self.work, ".github", "workflows"))
        with open(os.path.join(self.work, ".github", "workflows", "tests.yml"), "w") as f:
            f.write("on: push\n")
        self.base_sha = self.commit("start", self.fx.clock.ms - 3600 * 1000)
        self.git("push", "-q", "-u", "origin", "main")
        self.fx.registry(TARGET, 200, "%2", cwd=self.work, status_at=self.fx.clock.ms - 60000)
        self.sessions = {}
        self.status()

    # -- helpers
    def git(self, *args, cwd=None):
        r = subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", *args],
                           cwd=cwd or self.work, env=dict(os.environ, **GIT_ENV, **getattr(self, "_date", {})),
                           capture_output=True, text=True, timeout=30)
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.strip()

    def commit(self, msg, at_ms):
        with open(os.path.join(self.work, "f.txt"), "a") as f:
            f.write(msg + "\n")
        self.git("add", "-A")
        self._date = {"GIT_AUTHOR_DATE": f"@{at_ms // 1000} +0000", "GIT_COMMITTER_DATE": f"@{at_ms // 1000} +0000"}
        try:
            self.git("commit", "-q", "-m", msg)
        finally:
            del self._date
        return self.git("rev-parse", "HEAD")

    def status(self, **per_name):
        """status.json as jarvis-d would write it (fresh), with session dicts the test controls."""
        for name, kw in per_name.items():
            self.sessions.setdefault(name, {"name": name, "short": J.short_name(name), "state": "idle",
                                            "attention": None, "cwd": self.work})
            self.sessions[name].update(kw)
        for name in (TARGET, OTHER):
            self.sessions.setdefault(name, {"name": name, "short": J.short_name(name), "state": "idle",
                                            "attention": None, "cwd": self.work if name == TARGET else "/x"})
        J.atomic_write(os.path.join(self.fx.state, "status.json"), json.dumps(
            {"v": 1, "gen": 1, "at": self.fx.clock.ms, "d_heartbeat": self.fx.clock.ms, "sessions": self.sessions}))

    def task(self, *argv, env=None):
        out, err = Out(), Out()
        rc = cr_task.main(list(argv), env=env or self.env, out=out, err=err, mail=self.mail(env or self.env))
        return rc, out.s, err.s

    def lukas_env(self):
        return self.fx.env(environ_extra={}, my_pid=12)

    def worker(self):
        return JW.WatchWorker(self.env, repo=JW.Repo(self.env, gh_allowed=True, gh=self.gh))

    def tick(self, w, events=(), usage=None, time_left=lambda: 60.0):
        self.status()  # keep status.json fresh as the clock moves
        return w.tick(list(events), self.sessions, J.fold_events_file(self.env, ignore=JARVIS), usage or {},
                      self.fx.clock.ms, time_left)

    def watch(self, *argv):
        rc, out, err = self.task("watch", *argv)
        self.assertEqual(rc, 0, err)
        return out.split()[0]

    def state_of(self, wid):
        return JW.Watches(self.env).all()[wid]["state"]

    def delegated(self, ac="testy zelené, push na main"):
        """A task with criteria, mailed to TARGET, delivered and replied to by TARGET."""
        rc, out, _ = self.task("add", "Opravit timeout", "--to", TARGET, "--ac", ac)
        tid = out.split()[0]
        rec, why = self.send("Oprav timeout.", task=tid)
        self.assertIsNone(why)
        self.assertEqual(self.deliver(rec), "delivered")
        self.fx.ppids[201] = 200
        self.assertIsNone(self.mail(self.make_env(pane="%2", my_pid=201)).reply(rec["id"], "Hotovo."))
        return tid, rec


# ------------------------------------------------------------------------------------------------- watches

class StopWatchTest(Base):
    def test_fires_on_a_stop_without_bg_only(self):
        self.status(**{TARGET: {"state": "busy"}})
        wid = self.watch("stop", TARGET)
        w = self.worker()
        t = self.fx.clock.ms
        bg = ev_line("stop", TARGET, t + 1000, 200, "bg")
        self.fx.append_events(bg)
        self.fx.clock.advance(2)
        self.tick(w, [J.parse_event(bg)])
        self.assertEqual(self.state_of(wid), "open")  # C7: stop bg is not "doběhla"
        done = ev_line("stop", TARGET, t + 3000, 200)
        self.fx.append_events(done)
        self.fx.clock.advance(2)
        self.tick(w, [J.parse_event(done)])
        self.assertEqual(self.state_of(wid), "fired")

    def test_a_stop_before_the_watch_does_not_count(self):
        old = ev_line("stop", TARGET, self.fx.clock.ms - 5000, 200)
        self.fx.append_events(old)
        self.status(**{TARGET: {"state": "busy"}})
        wid = self.watch("stop", TARGET)
        self.tick(self.worker(), [J.parse_event(old)])
        self.assertEqual(self.state_of(wid), "open")

    def test_refused_when_already_done_but_allowed_with_bg(self):
        rc, _, err = self.task("watch", "stop", TARGET)
        self.assertEqual(rc, 2)
        self.assertIn("už je nečinná", err)
        self.status(**{TARGET: {"stopBg": True}})
        self.assertEqual(self.task("watch", "stop", TARGET)[0], 0)

    def test_a_vanished_session_fires_as_gone(self):
        self.status(**{TARGET: {"state": "busy"}})
        wid = self.watch("stop", TARGET)
        self.sessions[TARGET]["state"] = "gone"
        w = self.worker()
        w.tick([], self.sessions, None, {}, self.fx.clock.ms)
        self.assertEqual(JW.Watches(self.env).all()[wid]["result"], "gone")

    def test_jarvis_does_not_watch_itself_and_names_are_exact(self):
        self.status(**{JARVIS: {"state": "busy"}})
        self.assertIn("sám sebe", self.task("watch", "stop", JARVIS)[2])
        self.assertIn("žádná session přesně", self.task("watch", "stop", "jarvis-te")[2])


class WaitingAndUsageWatchTest(Base):
    def test_waiting_fires_on_perm_and_refuses_when_already_waiting(self):
        self.status(**{TARGET: {"state": "busy"}})
        wid = self.watch("waiting", TARGET)
        w = self.worker()
        self.tick(w)
        self.assertEqual(self.state_of(wid), "open")
        self.status(**{TARGET: {"state": "waiting", "attention": {"kind": "perm", "text": "Bash"}}})
        self.tick(w)
        self.assertEqual(JW.Watches(self.env).all()[wid]["result"], "perm")
        self.assertIn("už teď čeká", self.task("watch", "waiting", TARGET)[2])

    def test_usage_fires_below_and_never_on_stale_data(self):
        self.fx.usage("hanakl@nekrachni.cz", 80, 50)
        wid = self.watch("usage", "nekrachni", "50")
        w = self.worker()
        acct = "hanakl@nekrachni.cz"
        self.tick(w, usage={acct: {"fiveHour": 40.0, "ageS": 3600}})
        self.assertEqual(self.state_of(wid), "open")  # an hour-old number is not "klesl"
        self.tick(w, usage={acct: {"fiveHour": 55.0, "ageS": 10}})
        self.assertEqual(self.state_of(wid), "open")
        self.tick(w, usage={acct: {"fiveHour": 42.0, "ageS": 10}})
        self.assertEqual(self.state_of(wid), "fired")

    def test_usage_refusals(self):
        self.fx.usage("hanakl@nekrachni.cz", 20, 50)
        self.fx.usage("lukas-kontexta-cz", 20, 50)
        self.assertIn("už teď je", self.task("watch", "usage", "nekrachni", "50")[2])
        self.assertIn("neznám", self.task("watch", "usage", "gmail", "50")[2])
        self.assertIn("1 až 100", self.task("watch", "usage", "nekrachni", "500")[2])
        self.assertEqual(self.task("watch", "usage", "nekrachni", "50", "--week")[0], 0)  # 7d is at 50: not below
        self.assertIn("už teď je", self.task("watch", "usage", "nekrachni", "60", "--week")[2])

    def test_expiry_dedupe_unwatch(self):
        self.status(**{TARGET: {"state": "busy"}})
        wid = self.watch("stop", TARGET)
        rc, out, _ = self.task("watch", "stop", TARGET)
        self.assertIn("už hlídám jako " + wid, out)
        w2 = self.watch("waiting", TARGET)
        self.assertEqual(self.task("unwatch", w2)[0], 0)
        self.assertIn("zrušená", self.task("unwatch", w2)[2])
        self.fx.clock.advance(24 * 3600 + 1)
        self.tick(self.worker())
        self.assertEqual(self.state_of(wid), "expired")
        self.assertIn("vypršelá", self.task("unwatch", wid)[2])
        self.fx.ppids[301] = 300
        self.assertIn("jen Jarvis nebo Lukáš", self.task("watch", "stop", TARGET,
                                                         env=self.make_env(pane="%3", my_pid=301))[2])


class CiWatchTest(Base):
    def test_branch_watch_tells_the_next_result_not_an_old_one(self):
        self.gh.set(self.base_sha, ("completed", "success", "Tests"))
        wid = self.watch("ci", TARGET)  # ref = the checked-out branch, main
        w = self.worker()
        self.tick(w)
        self.assertEqual(self.state_of(wid), "open")  # CI of the commit already pushed is old news
        self.assertEqual(len(self.gh.calls), 1)
        sha = self.commit("fix", self.fx.clock.ms)
        self.git("push", "-q")
        self.gh.set(sha, ("in_progress", None, "Tests"))
        self.fx.clock.advance(60)
        self.tick(w)
        self.assertEqual(len(self.gh.calls), 1)  # at most once per 2 min per watch
        self.fx.clock.advance(61)
        self.tick(w)
        self.assertEqual((self.state_of(wid), len(self.gh.calls)), ("open", 2))
        self.gh.set(sha, ("completed", "failure", "Tests"), ("completed", "success", "Build"))
        self.fx.clock.advance(121)
        self.tick(w)
        ws = JW.Watches(self.env).all()[wid]
        self.assertEqual((ws["state"], ws["result"], ws["detail"]["failed"]), ("fired", "red", ["Tests"]))

    def test_sha_watch_fires_on_the_first_finished_check(self):
        self.gh.set(self.base_sha, ("completed", "success", "Tests"))
        wid = self.watch("ci", TARGET, "--ref", self.base_sha[:10])
        self.assertEqual(JW.Watches(self.env).all()[wid]["ref"], self.base_sha)
        self.tick(self.worker())
        self.assertEqual(JW.Watches(self.env).all()[wid]["result"], "green")

    def test_no_gh_call_when_the_run_is_ending(self):
        self.watch("ci", TARGET)
        self.tick(self.worker(), time_left=lambda: 10.0)
        self.assertEqual(self.gh.calls, [])

    def test_refusals(self):
        self.assertIn("neexistuje", self.task("watch", "ci", TARGET, "--ref", "nope")[2])
        self.assertIn("nezačíná pomlčkou", self.task("watch", "ci", TARGET, "--ref=-x")[2])
        os.unlink(os.path.join(self.work, ".github", "workflows", "tests.yml"))
        self.assertIn("nemá CI", self.task("watch", "ci", TARGET)[2])

    def test_only_jarvis_d_may_call_gh(self):
        with self.assertRaises(PermissionError):
            JW.Repo(self.env).gh(self.work, "run", "list")


# ------------------------------------------------------------------------------------------------- verified done

class VerifiedDoneTest(Base):
    def test_a_reply_is_ke_kontrole_and_done_needs_commit_push_and_green_ci(self):
        tid, rec = self.delegated()
        self.assertEqual(self.m.get(rec["id"])["base"]["head"], self.base_sha)
        rc, out, _ = self.task("show", tid)
        self.assertIn("ke kontrole (čeká na důkaz: chybí nový commit, zelené CI)", out)
        rc, _, err = self.task("done", tid)
        self.assertEqual(rc, 2)
        self.assertIn("chybí nový commit", err)
        self.fx.clock.advance(60)
        sha = self.commit("fix", self.fx.clock.ms)
        self.assertIn("chybí push", self.task("done", tid)[2])
        self.git("push", "-q")
        self.assertIn("výsledek CI (zatím neznámý)", self.task("done", tid)[2])
        # jarvis-d asks GitHub (once per 2 min), caches the result, appends the proof once
        self.gh.set(sha, ("in_progress", None, "Tests"))
        w = self.worker()
        self.tick(w)
        self.assertIn("CI ještě běží", self.task("done", tid)[2])
        self.gh.set(sha, ("completed", "success", "Tests"))
        self.fx.clock.advance(121)
        self.tick(w)
        self.fx.clock.advance(121)
        self.tick(w)
        proofs = [e for e in JW.Watches(self.env).events() if e.get("ev") == "proof"]
        self.assertEqual([(p["task"], p["head"], p["ci"]) for p in proofs], [(tid, sha, "green")])
        self.assertIn("důkaz je: commit " + sha[:8] + " na main, push, CI zelené", self.task("show", tid)[1])
        self.assertEqual(self.task("done", tid, "ověřeno")[0], 0)

    def test_without_ci_commit_and_push_are_enough(self):
        os.unlink(os.path.join(self.work, ".github", "workflows", "tests.yml"))
        tid, _ = self.delegated()
        self.fx.clock.advance(60)
        self.commit("fix", self.fx.clock.ms)
        self.git("push", "-q")
        self.assertEqual(self.task("done", tid)[0], 0)
        self.assertEqual(self.gh.calls, [])

    def test_an_older_commit_is_not_new_work(self):
        tid, _ = self.delegated()
        self.commit("old", self.fx.clock.ms - 7200 * 1000)
        self.git("push", "-q")
        self.assertIn("chybí nový commit", self.task("done", tid)[2])

    def test_stop_bg_keeps_the_task_running_and_done_refused(self):
        os.unlink(os.path.join(self.work, ".github", "workflows", "tests.yml"))
        tid, _ = self.delegated()
        self.fx.clock.advance(60)
        self.commit("fix", self.fx.clock.ms)
        self.git("push", "-q")
        self.status(**{TARGET: {"stopBg": True}})
        self.assertIn("běží (na pozadí ještě běží úloha)", self.task("show", tid)[1])
        self.assertIn("ještě nedoběhla (na pozadí běží úloha)", self.task("done", tid)[2])
        w = self.worker()
        self.tick(w)
        self.assertEqual([e for e in JW.Watches(self.env).events() if e.get("ev") == "proof"], [])
        self.status(**{TARGET: {"stopBg": False}})
        self.assertEqual(self.task("done", tid)[0], 0)

    def test_states_along_the_way(self):
        rc, out, _ = self.task("add", "Úkol", "--to", TARGET, "--ac", "push")
        tid = out.split()[0]
        self.assertIn("· otevřený →", self.task("show", tid)[1].replace("zadáno", "otevřený"))
        rec, _ = self.send("x", task=tid, approve=False)
        self.assertIn("zadáno (čeká na Lukášovo ano)", self.task("show", tid)[1])
        self.lukas_says("ano")
        self.assertIn("zadáno (ve frontě)", self.task("show", tid)[1])
        self.deliver(self.m.get(rec["id"]))
        self.assertIn("· běží", self.task("show", tid)[1])
        self.status(**{TARGET: {"state": "waiting", "attention": {"kind": "ask"}}})
        self.assertIn("čeká na tebe (jarvis-test se ptá)", self.task("show", tid)[1])

    def test_force_is_lukas_only_and_a_task_without_mail_needs_no_proof(self):
        tid, _ = self.delegated()
        self.assertIn("smí jen Lukáš", self.task("done", tid, "--force")[2])
        self.assertEqual(self.task("done", tid, "--force", env=self.lukas_env())[0], 0)
        rc, out, _ = self.task("add", "Jen poznámka", "--ac", "něco")
        self.assertEqual(self.task("done", out.split()[0])[0], 0)

    def test_the_delta_tells_the_task_state_with_the_reply_and_the_proof_once(self):
        os.unlink(os.path.join(self.work, ".github", "workflows", "tests.yml"))
        tid, rec = self.delegated()
        out = Out()
        cr_digest.main(["delta", "--max-chars", "8000"], env=self.env, out=out)
        self.assertIn(f"odpověděla na {rec['id']}", out.s)
        self.assertIn(f"úkol {tid}: ke kontrole, čeká na důkaz: chybí nový commit", out.s)
        out = Out()
        cr_digest.main(["delta", "--say"], env=self.env, out=out)
        self.fx.clock.advance(60)
        self.commit("fix", self.fx.clock.ms)
        self.git("push", "-q")
        self.tick(self.worker())
        out = Out()
        cr_digest.main(["delta", "--max-chars", "8000"], env=self.env, out=out)
        body = out.s.splitlines()
        self.assertTrue(body[1].startswith(f"✓ Úkol {tid}"), out.s)  # on top
        out = Out()
        cr_digest.main(["delta", "--max-chars", "8000"], env=self.env, out=out)
        self.assertNotIn("✓ Úkol", out.s)


# ------------------------------------------------------------------------------------------------- digest, daemon

class DigestTest(Base):
    def test_fired_watch_is_on_top_once_and_say_has_no_ids(self):
        out = Out()
        cr_digest.main(["delta"], env=self.env, out=out)  # the cursor
        self.status(**{TARGET: {"state": "busy"}})
        wid = self.watch("stop", TARGET)
        self.fx.clock.advance(5)
        e = ev_line("stop", TARGET, self.fx.clock.ms, 200)
        self.fx.append_events(e)
        self.tick(self.worker(), [J.parse_event(e)])
        out = Out()
        cr_digest.main(["delta", "--peek", "--say"], env=self.env, out=out)
        self.assertTrue(out.s.startswith("Splněno: jarvis test doběhla."), out.s)
        self.assertNotIn("w-", out.s)
        self.assertNotIn("/", out.s)
        out = Out()
        cr_digest.main(["delta", "--max-chars", "8000"], env=self.env, out=out)
        self.assertEqual(out.s.splitlines()[1], f"✓ Splněno: jarvis-test doběhla ({wid})")
        self.assertGreater(J.read_json(os.path.join(self.fx.state, "cursor"))["watchAt"], 0)  # its own cursor
        out = Out()
        cr_digest.main(["delta", "--max-chars", "8000"], env=self.env, out=out)
        self.assertNotIn("Splněno", out.s)
        out = Out()
        cr_digest.main(["brief", "--max-chars", "8000"], env=self.env, out=out)
        self.assertIn("✓ Splněno: jarvis-test doběhla", out.s)

    def test_daemon_runs_the_watches_and_status_json_carries_them(self):
        self.status(**{TARGET: {"state": "busy"}})
        wid = self.watch("stop", TARGET)
        d = jarvis_d.Daemon(self.env)
        d.mail.mail = self.m
        d.watch.repo = JW.Repo(self.env, gh_allowed=True, gh=self.gh)
        self.fx.clock.advance(3)
        self.fx.append_events(ev_line("stop", TARGET, self.fx.clock.ms, 200))
        d.tick()
        self.assertEqual(self.state_of(wid), "fired")
        d.tick()
        st = J.read_json(os.path.join(self.fx.state, "status.json"))
        self.assertEqual([w["id"] for w in st["watches"]["fired"]], [wid])


# ------------------------------------------------------------------------------------------------- conflicts

class ConflictTest(Base):
    def other_in(self, cwd, status="busy"):
        self.fx.registry(OTHER, 300, "%3", cwd=cwd, status=status)

    def send_out(self):
        self.prompt()
        out, err = Out(), Out()
        rc = cr_mail.main(["send", "--to", TARGET, "Oprav to."], env=self.env, out=out, err=err, mail=self.m)
        self.assertEqual(rc, 0, err.s)
        return out.s

    def test_a_busy_session_in_the_same_checkout_is_named(self):
        self.other_in(os.path.join(self.work, ".github"))
        out = self.send_out()
        self.assertIn("Pozor, konflikt checkoutu", out)
        self.assertIn("pracují v něm RAG", out)
        self.assertIn("větev main", out)
        say = out.split("třeba: „")[1]
        self.assertIn("V work pracuje další jedna session na větvi main. Pošlu i tak?", say)
        self.assertNotIn("/", say)

    def test_idle_and_clean_says_nothing_dirty_says_it(self):
        self.other_in(self.work, status="idle")
        self.assertNotIn("Pozor", self.send_out())
        with open(os.path.join(self.work, "f.txt"), "a") as f:
            f.write("rozdělané\n")
        out = self.send_out()
        self.assertIn("nečinné RAG; má necommitnuté změny", out)

    def test_another_worktree_is_another_checkout(self):
        wt = os.path.join(self.work, "wt")
        self.git("worktree", "add", "-q", "-b", "other", wt)
        self.other_in(wt)
        self.assertNotIn("Pozor", self.send_out())


# ------------------------------------------------------------------------------------------------- audit

class AuditTest(Base):
    def test_audit_tells_proposed_approved_delivered_refused_dropped_with_times(self):
        rc, out, _ = self.task("add", "Úkol A", "--to", TARGET)
        tid = out.split()[0]
        rec, _ = self.send("A", task=tid)
        self.deliver(rec)
        self.m.send(TARGET, "B")
        self.lukas_says("ne, počkej")
        self.allow()
        self.prompt()
        self.assertIsNotNone(self.m.send(TARGET, "C")[1])  # refused: not on the allowlist
        out, err = Out(), Out()
        self.assertEqual(cr_mail.main(["audit", "--since", "1h"], env=self.env, out=out, err=err, mail=self.m), 0)
        txt = out.s
        for what in ("úkol založen (Jarvis) " + tid, "Jarvis navrhl zprávu " + rec["id"],
                     "Lukáš řekl ano " + rec["id"], "doručeno " + rec["id"], "návrh zahozen (bez ano)",
                     "odmítnuto"):
            self.assertIn(what, txt)
        self.assertRegex(txt.splitlines()[1], r"^\d\d\.\d\d\. \d\d:\d\d ")
        # cr-task log <t-id>: only that task's story
        rc, out, _ = self.task("log", tid)
        self.assertIn(rec["id"], out)
        self.assertNotIn("návrh zahozen", out)
        out = Out()
        cr_mail.main(["audit", "--say"], env=self.env, out=out, err=Out(), mail=self.m)
        self.assertTrue(out.s.startswith("Za poslední hodinu: "), out.s)
        self.assertNotIn("m-", out.s)
        self.assertNotIn("/", out.s)

    def test_since_is_validated_and_bounded(self):
        out, err = Out(), Out()
        self.assertEqual(cr_mail.main(["audit", "--since", "1 hour"], env=self.env, out=out, err=err, mail=self.m), 2)
        self.assertEqual(JW.parse_since("90d", 0), 30 * 86400000)
        self.fx.clock.advance(7200)
        out = Out()
        cr_mail.main(["audit"], env=self.env, out=out, err=Out(), mail=self.m)
        self.assertIn("nic", out.s)


# ------------------------------------------------------------------------------------------------- mutations

MUTANTS = [
    # (module, old, new, test)
    (JW, 'and e.extra != "bg"', "", "StopWatchTest.test_fires_on_a_stop_without_bg_only"),
    (JW, 'and not stop.get("bg")', "", "StopWatchTest.test_fires_on_a_stop_without_bg_only"),
    (JW, 'and e.ms > w["created"]', "", "StopWatchTest.test_a_stop_before_the_watch_does_not_count"),
    (JW, 'return self._fire(w, now, s["state"])', "pass", "StopWatchTest.test_a_vanished_session_fires_as_gone"),
    (JW, "if kind in WAITING_KINDS:\n                return self._fire", "if False:\n                return self._fire",
     "WaitingAndUsageWatchTest.test_waiting_fires_on_perm_and_refuses_when_already_waiting"),
    (JW, "and (u.get(\"ageS\") or 0) <= USAGE_STALE_S and v <", "and v <",
     "WaitingAndUsageWatchTest.test_usage_fires_below_and_never_on_stale_data"),
    (JW, 'if now - w["created"] > WATCH_EXPIRE_MS:', "if False:", "WaitingAndUsageWatchTest.test_expiry_dedupe_unwatch"),
    (JW, "and c[\"first\"][\"sha\"] == sha", "and False",
     "CiWatchTest.test_branch_watch_tells_the_next_result_not_an_old_one"),
    (JW, "if now - J._int(c.get(\"at\") or 0) < CI_EVERY_MS or budget", "if budget",
     "CiWatchTest.test_branch_watch_tells_the_next_result_not_an_old_one"),
    (JW, "or time_left() < MIN_TIME_LEFT_S:\n                return None", ":\n                return None",
     "CiWatchTest.test_no_gh_call_when_the_run_is_ending"),
    (JW, 'if not self.gh_allowed:\n            raise', 'if False:\n            raise',
     "CiWatchTest.test_only_jarvis_d_may_call_gh"),
    (JW, 'if not new:\n        out["missing"].append("nový commit")', 'if False:\n        pass',
     "VerifiedDoneTest.test_a_reply_is_ke_kontrole_and_done_needs_commit_push_and_green_ci"),
    (JW, "new = ct is not None and ct * 1000 >= J._int(base[\"at\"]) - 120000", "new = True",
     "VerifiedDoneTest.test_an_older_commit_is_not_new_work"),
    (JW, 'elif not pushed:\n        out["missing"].append("push")', 'elif False:\n        pass',
     "VerifiedDoneTest.test_a_reply_is_ke_kontrole_and_done_needs_commit_push_and_green_ci"),
    (JW, 'if res != "green":', "if False:",
     "VerifiedDoneTest.test_a_reply_is_ke_kontrole_and_done_needs_commit_push_and_green_ci"),
    (JW, 'res, failed = repo.ci_cached(top, head) if not repo.gh_allowed else repo.ci_status(top, head)',
     'res, failed = repo.ci_status(top, head)',
     "VerifiedDoneTest.test_a_reply_is_ke_kontrole_and_done_needs_commit_push_and_green_ci"),
    (JW, 'and (t["id"], ev["head"]) not in proved', "",
     "VerifiedDoneTest.test_a_reply_is_ke_kontrole_and_done_needs_commit_push_and_green_ci"),
    (JW, 'if s.get("state") == "busy" or s.get("stopBg"):\n        return "běží"',
     'if False:\n        return "běží"', "VerifiedDoneTest.test_stop_bg_keeps_the_task_running_and_done_refused"),
    (JW, 'if s.get("state") == "busy" or s.get("stopBg"):\n                continue', 'if False:\n                continue',
     "VerifiedDoneTest.test_stop_bg_keeps_the_task_running_and_done_refused"),
    (cr_task, 'if ev["missing"]:\n        return', 'if False:\n        return',
     "VerifiedDoneTest.test_an_older_commit_is_not_new_work"),
    (cr_task, 'if s.get("state") == "busy" or s.get("stopBg"):\n        return', 'if False:\n        return',
     "VerifiedDoneTest.test_stop_bg_keeps_the_task_running_and_done_refused"),
    (cr_task, 'if by != "lukas":\n            raise', 'if False:\n            raise',
     "VerifiedDoneTest.test_force_is_lukas_only_and_a_task_without_mail_needs_no_proof"),
    (cr_task, "if a.what == \"stop\" and s.get(\"state\") == \"idle\" and not s.get(\"stopBg\"):", "if False:",
     "StopWatchTest.test_refused_when_already_done_but_allowed_with_bg"),
    (cr_digest, "write_cursor(env, gen, _i(st.get(\"at\")), mail_at, watch_at, ops_at)",
     "write_cursor(env, gen, _i(st.get(\"at\")), mail_at, None, ops_at)", "DigestTest.test_fired_watch_is_on_top_once_and_say_has_no_ids"),
    (cr_digest, "body = [JW.notice_line(i) for i in done] + ", "body = ",
     "DigestTest.test_fired_watch_is_on_top_once_and_say_has_no_ids"),
    (cr_digest, "    _task_status(env, mail, sessions)\n", "",
     "VerifiedDoneTest.test_the_delta_tells_the_task_state_with_the_reply_and_the_proof_once"),
    (jarvis_d, "self.watch.tick(new, sessions, self.fold, self.usage, now, self.time_left)", "pass",
     "DigestTest.test_daemon_runs_the_watches_and_status_json_carries_them"),
    (JW, "if not busy and not dirty:\n        return None", "if False:\n        return None",
     "ConflictTest.test_idle_and_clean_says_nothing_dirty_says_it"),
    (JW, "if tops[r.cwd] != top:\n            continue", "if False:\n            continue",
     "ConflictTest.test_another_worktree_is_another_checkout"),
    (M, "base, conflict = self.target_facts(name, now)", "base, conflict = None, None",
     "ConflictTest.test_a_busy_session_in_the_same_checkout_is_named"),
    (JW, "if task and rid not in task_mails:\n            continue", "if False:\n            continue",
     "AuditTest.test_audit_tells_proposed_approved_delivered_refused_dropped_with_times"),
]


@contextlib.contextmanager
def source_mutant(mod, old, new):
    """Rewrite one spot of a module's source and exec it into the SAME module object (every importer sees it), then
    exec the original back. The spot must occur exactly once, so a refactor that moves it fails loudly here."""
    path = mod.__file__
    with open(path, encoding="utf-8") as f:
        src = f.read()
    assert src.count(old) == 1, f"{os.path.basename(path)}: {old!r} occurs {src.count(old)}×"
    exec(compile(src.replace(old, new), path, "exec"), mod.__dict__)
    try:
        yield
    finally:
        exec(compile(src, path, "exec"), mod.__dict__)


class SourceMutationTest(unittest.TestCase):
    def test_every_new_check_has_a_test_that_fails_without_it(self):
        survived = []
        for mod, old, new, test in MUTANTS:
            cls, name = test.split(".")
            with source_mutant(mod, old, new):
                res = unittest.TestResult()
                globals()[cls](name).run(res)
            if not (res.failures or res.errors):
                survived.append(f"{mod.__name__}: {old[:50]!r} ({test})")
        self.assertEqual(survived, [])

    def test_the_unmutated_tests_pass(self):
        for test in sorted({t for *_, t in MUTANTS}):
            cls, name = test.split(".")
            res = unittest.TestResult()
            globals()[cls](name).run(res)
            self.assertTrue(res.wasSuccessful(), (test, res.failures, res.errors))


if __name__ == "__main__":
    unittest.main()
