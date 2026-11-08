"""jarvis-d: generations and the delta's changedGen, I8 (own events ignored), rotation across ticks, gone sessions,
the singleton lock, atomic writes."""
import fcntl
import json
import os
import unittest

import jarvis_d as D
from jarvis_fixtures import Fx, T0, ev_line

NAME = "claude-server-p-yolo--alpha"
SELF = "claude-server-~-yolo--jarvis"
P_DEAD = "claude-server-p-yolo--shell"


class DaemonTest(unittest.TestCase):
    def setUp(self):
        self.fx = Fx()
        self.addCleanup(self.fx.close)
        self.fx.session(NAME, 100, "%1", status="busy", status_at=T0 - 60000)

    def status(self):
        with open(os.path.join(self.fx.state, "status.json")) as f:
            return json.load(f)

    def test_gen_moves_only_on_significant_change(self):
        fx = self.fx
        d = D.Daemon(fx.env())
        st = d.tick()
        self.assertEqual(st["gen"], 1)
        self.assertEqual(st["sessions"][NAME]["changedGen"], 1)
        self.assertFalse(st["sessions"][NAME]["new"])  # the first tick does not call everything new
        fx.clock.advance(5)
        self.assertEqual(d.tick()["gen"], 1)  # nothing changed
        fx.registry(NAME, 100, "%1", status="idle", status_at=fx.clock.ms)
        fx.append_events(ev_line("stop", NAME, fx.clock.ms, 100, "bg"))
        fx.clock.advance(5)
        st = d.tick()
        s = st["sessions"][NAME]
        self.assertEqual((st["gen"], s["changedGen"], s["prevState"], s["state"], s["stopBg"]),
                         (2, 2, "busy", "bg", True))  # a stop with background tasks is not idle (2026-11-08)
        self.assertEqual(st["d_heartbeat"], fx.clock.ms)

    def test_state_survives_a_new_cron_run(self):
        fx = self.fx
        D.Daemon(fx.env()).tick()
        fx.append_events(ev_line("work", NAME, T0 + 1, 100))
        d2 = D.Daemon(fx.env())  # the next minute's process
        self.assertEqual(d2.gen, 1)
        fx.session("claude-server-p-yolo--beta", 200, "%2", status="idle")
        st = d2.tick()
        self.assertEqual(st["gen"], 2)
        self.assertTrue(st["sessions"]["claude-server-p-yolo--beta"]["new"])
        self.assertEqual(st["sessions"][NAME]["changedGen"], 1)

    def test_own_session_events_are_ignored(self):
        fx = self.fx
        fx.set_self(SELF)
        fx.session(SELF, 300, "%3", status="idle")
        fx.append_events(ev_line("perm", SELF, T0, 300, "Bash: cr-sessions list"))
        st = D.Daemon(fx.env()).tick()
        self.assertTrue(st["sessions"][SELF]["self"])
        self.assertIsNone(st["sessions"][SELF]["lastEvent"])
        self.assertEqual(st["inbox"], [])
        with open(os.path.join(fx.state, "d-state.json")) as f:
            self.assertNotIn(SELF, json.load(f)["fold"])

    def test_inbox(self):
        fx = self.fx
        fx.registry(NAME, 100, "%1", status="waiting", waiting_for="input needed", status_at=T0 - 2000)
        fx.append_events(ev_line("perm", NAME, T0 - 1000, 100, "Bash: npm test"))
        st = D.Daemon(fx.env()).tick()
        self.assertEqual([(i["name"], i["kind"], i["text"]) for i in st["inbox"]], [(NAME, "perm", "Bash: npm test")])

    def test_rotation_between_ticks(self):
        fx = self.fx
        lines = [ev_line("work", NAME, T0 - 100 + i, 100) for i in range(5)]
        fx.append_events(*lines)
        d = D.Daemon(fx.env())
        d.tick()
        rot = fx.events + ".rot"
        with open(rot, "w") as f:
            f.write("".join(lines[-2:]) + ev_line("stop", NAME, T0, 100))
        os.replace(rot, fx.events)
        fx.clock.advance(5)
        st = d.tick()
        self.assertEqual(st["eventsRotations"], 1)
        self.assertEqual(st["sessions"][NAME]["lastEvent"], {"ev": "stop", "ms": T0})

    def test_gone_session_is_reported_once_then_dropped(self):
        fx = self.fx
        d = D.Daemon(fx.env())
        d.tick()
        fx.panes.clear()
        fx.procs.clear()
        fx.clock.advance(5)
        st = d.tick()
        self.assertEqual((st["gen"], st["sessions"][NAME]["state"]), (2, "gone"))
        fx.clock.advance(5)
        self.assertEqual(d.tick()["gen"], 2)
        fx.clock.advance(3601)
        self.assertNotIn(NAME, d.tick()["sessions"])

    def test_writes_only_into_state_dir_and_atomically(self):
        fx = self.fx
        before = sorted(os.listdir(fx.home))
        D.main(["--once"], env=fx.env())
        self.assertEqual(sorted(os.listdir(fx.home)), before)
        names = sorted(os.listdir(fx.state))
        self.assertEqual(names, ["alerts.lock", "d-state.json", "jarvis-d.lock", "status.json"])
        self.assertEqual(os.stat(os.path.join(fx.state, "status.json")).st_mode & 0o777, 0o600)
        # F3: outside the state dir only the alerts file next to `events`, and on the first tick only its heartbeat
        alerts = os.path.join(fx.home, ".cache", "claude-remote", "jarvis-alerts")
        with open(alerts) as f:
            self.assertEqual(f.read(), f"claude-remote-jarvis hb {T0}\n")
        self.assertEqual(os.stat(alerts).st_mode & 0o777, 0o600)
        self.assertEqual(sorted(os.listdir(os.path.dirname(alerts))), ["events", "jarvis-alerts"])

    def test_singleton(self):
        fx = self.fx
        os.makedirs(fx.state, exist_ok=True)
        with open(os.path.join(fx.state, "jarvis-d.lock"), "a+") as held:
            fcntl.flock(held.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertEqual(D.main(["--once"], env=fx.env()), 0)
            self.assertFalse(os.path.exists(os.path.join(fx.state, "status.json")))
        D.main(["--once"], env=fx.env())
        self.assertTrue(os.path.exists(os.path.join(fx.state, "status.json")))

    def test_loop_ticks_until_the_deadline(self):
        fx = self.fx
        t = [0.0]
        ticks = []

        def mono():
            return t[0]

        def sleep(s):
            ticks.append(s)
            t[0] += s
        D.main(["--duration", "55", "--tick", "5"], env=fx.env(), sleep=sleep, monotonic=mono)
        self.assertEqual(len(ticks), 10)  # ticks at 0..50 s: 11 ticks, 10 sleeps, then stop before 55 s

    def test_a_failing_tick_is_logged_and_the_loop_survives(self):
        fx = self.fx
        env = fx.env()
        env.tmux_panes = lambda: 1 / 0
        self.assertEqual(D.main(["--once"], env=env), 0)
        with open(os.path.join(fx.state, "jarvis-d.log")) as f:
            self.assertIn("ZeroDivisionError", f.read())

    # rev M1: one failed list-panes must not turn sessions into "zmizela"
    def test_tmux_outage_does_not_make_sessions_vanish(self):
        fx = self.fx
        fx.pane("%9", 900, P_DEAD)  # a session without claude exists only through tmux
        d = D.Daemon(fx.env())
        self.assertEqual(d.tick()["sessions"][P_DEAD]["state"], "dead")
        good = list(fx.panes)
        fx.panes = None
        fx.clock.advance(20)  # list-panes is due again (every 15 s) and fails
        st = d.tick()
        self.assertEqual((st["gen"], st["tmuxOk"], st["sessions"][P_DEAD]["state"]), (1, False, "dead"))
        self.assertTrue(st["sessions"][NAME]["verified"])  # the last good answer stands in (60 s)
        fx.clock.advance(120)  # past the stale window: still not "gone", just carried
        st = d.tick()
        self.assertEqual((st["gen"], st["sessions"][P_DEAD]["state"]), (1, "dead"))
        self.assertFalse(st["sessions"][NAME]["verified"])
        fx.panes = good
        fx.clock.advance(5)  # a failed answer is retried on the very next tick
        st = d.tick()
        self.assertEqual((st["gen"], st["tmuxOk"]), (1, True))

    # rev M3: without a self file the session with alias `jarvis` is Jarvis (cron has no claude above it)
    def test_alias_self_without_file(self):
        fx = self.fx
        fx.session(SELF, 300, "%3", status="idle")
        fx.append_events(ev_line("perm", SELF, T0, 300, "Bash: cr-sessions list"))
        st = D.Daemon(fx.env()).tick()
        self.assertEqual(st["self"], SELF)
        self.assertEqual(st["inbox"], [])
        self.assertIsNone(st["sessions"][SELF]["lastEvent"])

    # rev L5: the log and the lock are private like the rest of the state
    def test_log_and_lock_are_private(self):
        fx = self.fx
        env = fx.env()
        env.tmux_panes = lambda: 1 / 0
        old = os.umask(0o022)
        self.addCleanup(os.umask, old)
        D.main(["--once"], env=env)
        for f in ("jarvis-d.log", "jarvis-d.lock", "status.json"):
            p = os.path.join(fx.state, f)
            if os.path.exists(p):
                self.assertEqual(os.stat(p).st_mode & 0o777, 0o600, f)
        self.assertTrue(os.path.exists(os.path.join(fx.state, "jarvis-d.log")))
        self.assertEqual(os.umask(0o022), 0o022)  # main() gave the caller its umask back

    # rev M3: the daemon never takes the claude above it for Jarvis (run by hand from a work session)
    def test_daemon_ignores_its_own_ancestry(self):
        fx = self.fx
        fx.ppids.update({7000: 100})  # started from inside NAME's claude
        fx.append_events(ev_line("perm", NAME, T0, 100, "Bash: x"))
        st = D.Daemon(fx.env(my_pid=7000)).tick()
        self.assertIsNone(st["self"])
        self.assertEqual(st["sessions"][NAME]["lastEvent"]["ev"], "perm")


if __name__ == "__main__":
    unittest.main()
