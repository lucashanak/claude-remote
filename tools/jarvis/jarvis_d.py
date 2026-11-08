#!/usr/bin/env python3
"""jarvis-d: Jarvis's read-only state daemon (F1, no LLM).

    jarvis-d [--once] [--duration S] [--tick S]

Started by cron every minute. It takes `<state>/jarvis-d.lock` with flock(LOCK_NB) and leaves at once when another
run holds it, so there is never more than one. One run is a LOOP of ~55 s with a 5 s tick, not a single pass:
- a 60 s pass would make "waiting for Lukáš" up to a minute late, and F3's blocking alerts (R10) and the
  UserPromptSubmit delta both want seconds;
- a tick is cheap: ~200 small registry files, a stat of the events file, and `tmux list-panes` only every 15 s or when
  the set of live claude pids changed (the tmux server is shared by 50+ sessions, so it is asked as little as possible);
- ending at 55 s leaves the lock free before the next cron start, and a crash costs at most one minute.
`--once` is one tick (tests, manual runs).

Reads: the events file (inode + offset, rotation, a partial last line is left for the next tick), the 4 registries,
tmux (read only), the manifest, the usage cache (never `.credentials.json`), `closed.jsonl`, transcript tails.
Writes, only under JARVIS_STATE_DIR (default ~/.claude-remote/jarvis), each with tmp + rename:
- `status.json`  the state of every session, `gen` / `changedGen` for the delta, `inbox`, `d_heartbeat`;
- `d-state.json` the events cursor (inode, offset, ring of recent line hashes) and the per-session event fold;
- `jarvis-d.log` errors only, capped.
Events of Jarvis's own session (name in `<state>/self`) are ignored (I8), so its own Stop never shows up as news.
"""
import argparse
import fcntl
import json
import os
import signal
import sys
import time
import traceback

sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
import jarvislib as J  # noqa: E402

TICK_S = 5
DURATION_S = 55
PANES_EVERY_MS = 15000
USAGE_EVERY_MS = 30000
GONE_KEEP_MS = 3600 * 1000
LOG_MAX = 256 * 1024
# One failed `list-panes` (timeout under load) must not make every session without a live registry record vanish
# and come back as "zmizela" and "nová": the last good answer stands in for this long, and when there is none,
# sessions that drop out are carried over unchanged instead of being called gone.
PANES_STALE_OK_MS = 60000


class Daemon:
    def __init__(self, env):
        self.env = env
        st = J.read_json(env.path("d-state.json"), {}) or {}
        prev = J.read_json(env.path("status.json"), {}) or {}
        self.reader = J.EventsReader(st.get("reader"))
        self.fold = J.Fold(st.get("fold"))
        self.gen = max(_i(st.get("gen")), _i(prev.get("gen")))
        self.prev = prev.get("sessions") if isinstance(prev.get("sessions"), dict) else {}
        self.first = not self.prev
        self.started = env.now_ms()
        self.tr_cache = {}
        # Seed the transcript cache from the last status: a new cron run does not re-read 50 transcript tails.
        for s in self.prev.values():
            if isinstance(s, dict) and s.get("transcript") and s.get("trKey"):
                self.tr_cache[s["transcript"]] = (s["trKey"], {
                    "_key": s["trKey"], "title": s.get("title"), "lastPrompt": s.get("lastPrompt"),
                    "lastPromptMs": s.get("lastPromptMs"), "lastAssistantMs": s.get("lastAssistantMs"),
                    "endsWithQuestion": s.get("endsWithQuestion")})
        self.fetched, self.panes_at, self.live_key = None, 0, None
        self.good_panes, self.good_at = None, 0
        self.usage, self.usage_at = {}, 0
        self.errors = []

    def tick(self):
        env = self.env
        now = env.now_ms()
        regs = J.read_registry(env)
        live_key = sorted((r.pid, r.proc_start) for r in regs if r.alive)
        # a failed answer is retried on the next tick (fetched stays None)
        if self.fetched is None or live_key != self.live_key or now - self.panes_at >= PANES_EVERY_MS:
            self.fetched, self.panes_at, self.live_key = env.tmux_panes(), now, live_key
            if self.fetched is not None:
                self.good_panes, self.good_at = self.fetched, now
        tmux_ok = self.fetched is not None
        panes = self.fetched if tmux_ok else (
            self.good_panes if self.good_panes is not None and now - self.good_at <= PANES_STALE_OK_MS else None)
        self_name = J.resolve_self(env, regs, panes, ancestor=False)
        for e in self.reader.read(env.events_path):
            self.fold.apply(e, ignore=self_name)
        self.fold.prune(now)
        if now - self.usage_at >= USAGE_EVERY_MS:
            self.usage, self.usage_at = J.read_usage(env, now), now
        sessions = J.build_sessions(env, regs, panes, J.read_manifest(env), self.fold, self_name,
                                    self.tr_cache, now)
        changed = self._diff(sessions, now, tmux_ok)
        if changed:
            self.gen += 1
            for name in changed:
                sessions[name]["changedGen"] = self.gen
        self.prev = sessions
        self.first = False
        closed = J.read_closed(env)
        for c in closed:
            c["title"] = J.safe_text(c.get("title"), 100) or None
        status = {
            "v": 1, "gen": self.gen, "at": now, "d_heartbeat": now, "d_pid": os.getpid(), "d_started": self.started,
            "self": self_name, "tmuxOk": tmux_ok,
            "tmuxAgeMs": (now - self.good_at) if self.good_panes is not None else None,
            "registryRecords": len(regs), "registryLive": sum(1 for r in regs if r.alive),
            "eventsRotations": self.reader.rotations,
            "counts": _counts(sessions),
            "inbox": [{"name": s["name"], "short": s["short"], **s["attention"]} for s in J.inbox(sessions)],
            "usage": self.usage, "closed": closed, "errors": self.errors[-5:],
            "sessions": sessions,
        }
        J.atomic_write(env.path("status.json"), json.dumps(status, ensure_ascii=False, separators=(",", ":")))
        J.atomic_write(env.path("d-state.json"), json.dumps(
            {"v": 1, "gen": self.gen, "reader": self.reader.state(), "fold": self.fold.s}, separators=(",", ":")))
        return status

    def _diff(self, sessions, now, tmux_ok=True):
        """Names whose significant fields changed since the last tick; carries prevState/changedGen forward."""
        changed = []
        for name, s in sessions.items():
            p = self.prev.get(name)
            if not isinstance(p, dict):
                s.update(prevState=None, changedAt=now, changedGen=0, new=not self.first)
                changed.append(name)
            elif J.significant(p) != J.significant(s):
                s.update(prevState=p.get("state"), changedAt=now, changedGen=0, new=False,
                         stopBg=self._stop_bg(name))
                changed.append(name)
            else:
                for k in ("prevState", "changedAt", "changedGen", "new", "stopBg"):
                    s[k] = p.get(k)
        # A session that vanished stays one hour as `gone`, so the delta can say so once.
        for name, p in self.prev.items():
            if name in sessions or not isinstance(p, dict):
                continue
            if not tmux_ok and p.get("state") != "gone":
                sessions[name] = p  # tmux did not answer: not seeing a session is not seeing it vanish
            elif p.get("state") != "gone":
                g = dict(p, prevState=p.get("state"), state="gone", attention=None, changedAt=now, new=False)
                sessions[name] = g
                changed.append(name)
            elif now - _i(p.get("changedAt")) < GONE_KEEP_MS:
                sessions[name] = p
        return changed

    def _stop_bg(self, name):
        st = (self.fold.s.get(name) or {}).get("stop") or {}
        return bool(st.get("bg"))

    def log(self, msg):
        self.errors.append({"at": self.env.now_ms(), "msg": msg[-300:]})
        self.errors = self.errors[-20:]
        p = self.env.path("jarvis-d.log")
        try:
            if os.path.exists(p) and os.path.getsize(p) > LOG_MAX:
                os.replace(p, p + ".1")
            with open(p, "a", encoding="utf-8") as f:
                f.write(time.strftime("%Y-%m-%dT%H:%M:%S ") + msg.replace("\n", " | ") + "\n")
        except OSError:
            pass


def _i(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return 0


def _counts(sessions):
    c = {}
    for s in sessions.values():
        if not s.get("self"):
            c[s["state"]] = c.get(s["state"], 0) + 1
    return c


def main(argv=None, env=None, sleep=time.sleep, monotonic=time.monotonic):
    ap = argparse.ArgumentParser(prog="jarvis-d", description="Jarvis read-only state daemon (cron, singleton).")
    ap.add_argument("--once", action="store_true", help="one tick and exit")
    ap.add_argument("--duration", type=float, default=DURATION_S, help="seconds to loop (default 55)")
    ap.add_argument("--tick", type=float, default=TICK_S, help="seconds between ticks (default 5)")
    a = ap.parse_args(argv)
    env = env or J.Env()
    # the log and the lock are created with open(); without this they come out 644 under cron's umask
    old_umask = os.umask(0o077)
    try:
        return _run(a, env, sleep, monotonic)
    finally:
        os.umask(old_umask)


def _run(a, env, sleep, monotonic):
    os.makedirs(env.state_dir, mode=0o700, exist_ok=True)
    lock = open(env.path("jarvis-d.lock"), "a+")
    try:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        lock.close()
        return 0  # another run holds it: that is the singleton working, not an error
    with lock:
        stop = []
        try:
            signal.signal(signal.SIGTERM, lambda *_: stop.append(1))
        except ValueError:
            pass  # not the main thread (tests)
        d = Daemon(env)
        end = monotonic() + (0 if a.once else a.duration)
        while not stop:
            t0 = monotonic()
            try:
                d.tick()
            except Exception:
                d.log(traceback.format_exc(limit=3))
            if a.once or monotonic() + a.tick >= end:
                break
            sleep(max(0.0, a.tick - (monotonic() - t0)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
