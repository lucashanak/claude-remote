#!/usr/bin/env python3
"""A stand-in for Claude Code in a tmux pane, for the mail tests on a PRIVATE tmux socket (test_jarvismail.py).

    jarvis_fake_claude.py --home H --name N [--mode idle|selector|noecho|deafenter] [--turn S]

Draws what the 2.1.319 spike showed (rule, `❯`+NBSP input row with a dim placeholder, rule, footer; a selector with
its key hints; "✶ …… (Ns" while a turn runs, "✻ … done" after it), reads keys in raw mode, and on Enter with text
plays a turn: transcript `user` line, registry busy + `work` event, then an assistant line, registry idle + `stop`.
Every byte it receives is logged to <H>/fake-<pid>.keys, so a test can prove that no Enter arrived.
Writes only under --home (a temp dir). Started by the test inside its own tmux server, never the default one.
"""
import argparse
import datetime
import json
import os
import select
import sys
import termios
import time
import tty

sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
import jarvislib as J  # noqa: E402

W = 100
ESC = "\x1b"


def now_ms():
    return int(time.time() * 1000)


def iso(ms):
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


class Fake:
    def __init__(self, a):
        self.a = a
        self.pid = os.getpid()
        self.pane = os.environ.get("TMUX_PANE", "%0")
        self.sid = f"{self.pid:08x}-0000-4000-8000-000000000000"
        self.cwd = "/home/u/fake"
        self.buf = ""
        self.history = []
        self.status_line = None
        self.mode = a.mode
        self.deaf = a.mode == "deafenter"
        self.reg_path = os.path.join(a.home, ".claude", "sessions", f"{self.pid}.json")
        slug = "".join(c if c.isalnum() else "-" for c in self.cwd)
        self.tr_path = os.path.join(a.home, ".claude", "projects", slug, self.sid + ".jsonl")
        self.ev_path = os.path.join(a.home, ".cache", "claude-remote", "events")
        self.keys = os.path.join(a.home, f"fake-{self.pid}.keys")
        for p in (self.reg_path, self.tr_path, self.ev_path):
            os.makedirs(os.path.dirname(p), exist_ok=True)
        self.registry("idle", now_ms() - 10000)

    def registry(self, status, at=None):
        d = {"pid": self.pid, "sessionId": self.sid, "cwd": self.cwd, "startedAt": now_ms() - 60000,
             "procStart": J.read_proc_start(self.pid), "version": "2.1.319", "kind": "interactive",
             "tmux": f"{self.a.name}:@0.{self.pane}", "status": status, "statusUpdatedAt": at or now_ms()}
        J.atomic_write(self.reg_path, json.dumps(d))

    def event(self, ev):
        with open(self.ev_path, "a") as f:
            f.write(f"claude-remote-ev {ev} {J.base64.b64encode(self.a.name.encode()).decode()} {now_ms()} {self.pid} \n")

    def transcript(self, d):
        with open(self.tr_path, "a") as f:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")

    def draw(self):
        rule = f"{ESC}[38;5;244m" + "─" * W + f"{ESC}[39m"
        rows = []
        for h in self.history[-6:]:
            rows.append(h)
        if self.mode == "selector":
            rows += [" ☐ Volba", "Zvolte variantu: A, nebo B?", rule, "❯ 1. A", "  2. B", rule,
                     "Enter to select · ↑/↓ to navigate · Esc to cancel"]
        else:
            if self.status_line:
                rows.append(self.status_line)
            rows.append(rule)
            shown = "" if self.mode == "noecho" else self.buf
            if shown:
                chunks = [shown[i:i + W - 2] for i in range(0, len(shown), W - 2)]
                rows.append(f"{ESC}[39m❯ " + chunks[0])
                rows += ["  " + c for c in chunks[1:]]
            else:
                rows.append(f"{ESC}[39m❯ {ESC}[2mTry \"write a test for <filepath>\"{ESC}[0m")
            rows.append(rule)
            rows.append(f"  {ESC}[38;5;246mprofile:fake{ESC}[39m")
        out = f"{ESC}[H{ESC}[2J" + "\r\n".join(rows)
        os.write(1, out.encode())

    def submit(self):
        text = self.buf
        self.buf = ""
        self.history.append("❯ " + text[:80])
        t = now_ms()
        self.transcript({"type": "user", "message": {"role": "user", "content": text}, "timestamp": iso(t)})
        self.registry("busy")
        self.event("work")
        self.status_line = f"{ESC}[38;5;174m✶{ESC}[39m {ESC}[38;5;216mPracuju…{ESC}[38;5;246m (1s){ESC}[39m"
        self.draw()
        time.sleep(self.a.turn)
        reply = "Hotovo: " + text[:40]
        self.transcript({"type": "assistant", "message": {"role": "assistant",
                                                          "content": [{"type": "text", "text": reply}]},
                         "timestamp": iso(now_ms())})
        self.history.append("● " + reply)
        self.status_line = f"{ESC}[38;5;246m✻{ESC}[39m {ESC}[38;5;246mBaked for 1s · done{ESC}[39m"
        self.registry("idle")
        self.event("stop")
        self.draw()

    def run(self):
        old = termios.tcgetattr(0)
        tty.setraw(0)
        try:
            self.draw()
            pending = b""
            while True:
                r, _, _ = select.select([0], [], [], 0.2)
                if not r:
                    continue
                data = os.read(0, 4096)
                if not data:
                    return
                with open(self.keys, "ab") as f:
                    f.write(data)
                pending += data
                try:
                    text = pending.decode("utf-8")
                    pending = b""
                except UnicodeDecodeError:
                    continue
                i = 0
                while i < len(text):
                    c = text[i]
                    if c == "\r":
                        if self.deaf:
                            self.deaf = False
                        elif self.buf and self.mode != "selector":
                            self.submit()
                    elif c == "\x7f":
                        self.buf = self.buf[:-1]
                    elif c == ESC:
                        i += 1
                        while i < len(text) and not text[i].isalpha():
                            i += 1
                    elif c == "\x03":
                        return
                    elif c >= " ":
                        self.buf += c
                    i += 1
                self.draw()
        finally:
            termios.tcsetattr(0, termios.TCSADRAIN, old)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--home", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--mode", default="idle", choices=["idle", "selector", "noecho", "deafenter"])
    ap.add_argument("--turn", type=float, default=0.4)
    Fake(ap.parse_args()).run()


if __name__ == "__main__":
    main()
