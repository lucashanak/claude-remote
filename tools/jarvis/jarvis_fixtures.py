"""Test fixtures for the Jarvis read layer: a fake HOME in a temp dir, fake liveness, fake tmux, a settable clock.

Nothing here starts a process, calls tmux, creates a link or deletes outside its own temp root (docs/TESTING.md,
incident 2026-11-02): the temp dir is removed by TemporaryDirectory itself.
"""
import base64
import json
import os
import tempfile

import jarvislib as J

T0 = 1794000000000  # a fixed "now" in ms


def b64(s):
    return base64.b64encode(s.encode()).decode()


def ev_line(ev, name, ms, pid=100, extra=None):
    return f"claude-remote-ev {ev} {b64(name)} {ms} {pid} {b64(extra) if extra else ''}\n"


class Clock:
    def __init__(self, ms=T0):
        self.ms = ms

    def __call__(self):
        return self.ms

    def advance(self, s):
        self.ms += int(s * 1000)


class Fx:
    """A fake box. `procs` = {pid: procStart} of the living, `ppids` = {pid: ppid}, `panes` = tmux list-panes rows."""

    def __init__(self):
        self._td = tempfile.TemporaryDirectory(prefix="jarvis-test-")
        self.root = self._td.name
        self.home = os.path.join(self.root, "home")
        self.cr = os.path.join(self.home, ".claude-remote")
        self.state = os.path.join(self.root, "state")
        self.events = os.path.join(self.home, ".cache", "claude-remote", "events")
        for d in (os.path.join(self.home, ".claude", "sessions"), os.path.join(self.home, ".claude", "projects"),
                  os.path.join(self.cr, "usage"), os.path.dirname(self.events)):
            os.makedirs(d, exist_ok=True)
        open(self.events, "w").close()
        self.procs = {}
        self.ppids = {}
        self.panes = []
        self.ptable = {}
        self.clock = Clock()
        self.manifest = []

    def close(self):
        self._td.cleanup()

    def env(self, **kw):
        environ = {"HOME": self.home, "JARVIS_STATE_DIR": self.state, "JARVIS_TMUX": ""}
        return J.Env(environ=environ, proc_start=lambda pid: self.procs.get(pid), ppid=lambda pid: self.ppids.get(pid),
                     tmux_panes=lambda: (list(self.panes) if self.panes is not None else None), now_ms=self.clock,
                     pid_ns="pid:[1]", proc_table=lambda: dict(self.ptable), **kw)

    def account_dir(self, slug):
        d = os.path.join(self.cr, "accounts", slug)
        os.makedirs(os.path.join(d, "sessions"), exist_ok=True)
        return d

    def registry(self, name, pid, pane, status="idle", account=None, alive=True, proc_start="777", sid=None,
                 cwd="/home/u/proj", status_at=None, started_at=None, waiting_for=None, window="@1", **extra):
        cfg = self.account_dir(account) if account else os.path.join(self.home, ".claude")
        d = {"pid": pid, "sessionId": sid or f"{pid:08x}-0000-4000-8000-000000000000", "cwd": cwd,
             "startedAt": started_at or T0 - 3600000, "procStart": proc_start, "version": "2.1.319",
             "kind": "interactive", "pidDomain": "linux:abc:pid:[1]", "tmux": f"{name}:{window}.{pane}",
             "status": status, "statusUpdatedAt": status_at or T0 - 60000, "updatedAt": status_at or T0 - 60000}
        if waiting_for:
            d["waitingFor"] = waiting_for
        d.update(extra)
        with open(os.path.join(cfg, "sessions", f"{pid}.json"), "w") as f:
            json.dump(d, f)
        # a .key file sits next to every record on the live box; it must never be read
        with open(os.path.join(cfg, "sessions", f"{pid}.deadbeef.key"), "w") as f:
            f.write("SECRET")
        if alive:
            self.procs[pid] = proc_start
        return d

    def pane(self, pane, pane_pid, session, dead=False):
        self.panes.append((pane, pane_pid, session, dead))

    def session(self, name, pid, pane, pane_pid=None, **kw):
        """A live claude in its own pane, pid a child of the pane's shell."""
        pane_pid = pane_pid or pid - 1
        self.pane(pane, pane_pid, name)
        self.ppids[pid] = pane_pid
        self.ppids[pane_pid] = 1
        return self.registry(name, pid, pane, **kw)

    def append_events(self, *lines):
        with open(self.events, "a") as f:
            f.write("".join(lines))

    def write_manifest(self, entries):
        with open(os.path.join(self.cr, "sessions.json"), "w") as f:
            json.dump(entries, f)

    def transcript(self, cwd, sid, entries):
        slug = "".join(c if c.isalnum() else "-" for c in cwd)
        d = os.path.join(self.home, ".claude", "projects", slug)
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, sid + ".jsonl")
        with open(p, "w") as f:
            for e in entries:
                f.write(json.dumps(e) + "\n")
        return p

    def usage(self, slug, five, seven, fetched_s=None, resets="2026-11-07T22:39:59+00:00"):
        body = {"five_hour": {"utilization": five, "resets_at": resets},
                "seven_day": {"utilization": seven, "resets_at": "2026-11-12T19:00:00+00:00"}}
        with open(os.path.join(self.cr, "usage", slug + ".json"), "w") as f:
            f.write(f"{fetched_s or self.clock.ms // 1000}\n{json.dumps(body)}\nHTTP:200")

    def closed(self, *records):
        with open(os.path.join(self.cr, "closed.jsonl"), "a") as f:
            for r in records:
                f.write(json.dumps(r) + "\n")

    def set_self(self, name):
        os.makedirs(self.state, exist_ok=True)
        with open(os.path.join(self.state, "self"), "w") as f:
            f.write(name + "\n")


def user(text, ts="2026-11-06T21:19:00Z", **kw):
    return dict({"type": "user", "timestamp": ts, "message": {"role": "user", "content": text}}, **kw)


def tool_result(ts="2026-11-06T21:19:10Z"):
    return {"type": "user", "timestamp": ts,
            "message": {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "t", "content": "ok"}]}}


def assistant(text, ts="2026-11-06T21:19:30Z", **kw):
    return dict({"type": "assistant", "timestamp": ts,
                 "message": {"role": "assistant", "content": [{"type": "text", "text": text}]}}, **kw)
