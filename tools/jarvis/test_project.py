"""Jarvis's project template (tools/jarvis/project) and install-project.sh.

settings.json holds every trap f1-spike found on Claude Code 2.1.319, one test each; the gate blocks the usual
attacks and fails closed; the hooks never fail and stay under the 10 000-character hook limit; the installer is
idempotent and never overwrites a file Lukáš changed. Nothing here calls the real tmux or claude: the hooks get a
fake tmux first on PATH and a stub cr-digest.
"""
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "project")
GATE = os.path.join(SRC, ".claude", "hooks", "gate.py")
INSTALL = os.path.join(HERE, "install-project.sh")
PLACEHOLDER = re.compile(r"@(HOME|JARVIS|BIN|PY)@")


def load_template():
    with open(os.path.join(SRC, ".claude", "settings.json"), encoding="utf-8") as f:
        return json.load(f)


def clean_env(**kw):
    env = {k: v for k, v in os.environ.items() if k not in ("TMUX", "TMUX_PANE", "CLAUDE_CONFIG_DIR", "JARVIS_STATE_DIR")}
    env.update(kw)
    return env


def gate(payload, raw=None):
    data = raw if raw is not None else json.dumps(payload)
    return subprocess.run([sys.executable, "-I", GATE], input=data, capture_output=True, text=True, timeout=30)


def bash(cmd):
    return gate({"tool_name": "Bash", "tool_input": {"command": cmd}})


class SettingsTrapsTest(unittest.TestCase):
    """One test per trap from f1-spike.md (numbers refer to its table)."""

    def setUp(self):
        self.s = load_template()
        self.p = self.s["permissions"]

    def test_template_is_valid_json_with_only_known_placeholders(self):
        with open(os.path.join(SRC, ".claude", "settings.json"), encoding="utf-8") as f:
            raw = f.read()
        json.loads(raw)
        self.assertEqual(set(re.findall(r"@([A-Z]+)@", raw)), {"HOME", "JARVIS", "PY"})

    def test_4a_dont_ask_mode_from_the_project(self):
        self.assertEqual(self.p["defaultMode"], "dontAsk")

    def test_4d_bypass_is_disabled_inside_permissions_not_top_level(self):
        # top-level disableBypassPermissionsMode is ignored on 2.1.319: Shift+Tab still offered bypass
        self.assertEqual(self.p["disableBypassPermissionsMode"], "disable")
        self.assertNotIn("disableBypassPermissionsMode", self.s)

    def test_4d_auto_mode_is_disabled(self):
        # auto is the default mode without a flag on 2.1.319, and its classifier approves things off the allowlist
        self.assertEqual(self.p["disableAutoMode"], "disable")
        self.assertNotIn("disableAutoMode", self.s)

    def test_allow_is_exactly_the_two_read_clis(self):
        self.assertEqual(sorted(self.p["allow"]), ["Bash(cr-digest:*)", "Bash(cr-sessions:*)"])

    def test_4i_tools_dont_ask_lets_through_are_denied(self):
        # dontAsk let Agent, Skill, Artifact, SendMessage (writes into live sessions) and ListAgents through
        for t in ("SendMessage", "ListAgents", "Agent", "Skill", "Artifact", "Workflow", "WebFetch", "WebSearch",
                  "Edit", "Write", "NotebookEdit", "RemoteTrigger", "PushNotification", "PublishPlugin", "CronCreate",
                  "CronDelete", "EnterWorktree", "ScheduleWakeup", "TaskStop", "Monitor"):
            self.assertIn(t, self.p["deny"], t)

    def test_4e_dangerous_bash_has_its_own_deny(self):
        for r in ("Bash(ssh:*)", "Bash(tmux:*)", "Bash(rm:*)", "Bash(kill:*)"):
            self.assertIn(r, self.p["deny"], r)

    def test_4h_read_of_credentials_and_account_dirs_is_denied(self):
        for r in ("Read(~/.claude/**)", "Read(~/.claude-remote/accounts/**)", "Read(~/.claude.json)",
                  "Read(~/claude-local/cc-config/**)"):
            self.assertIn(r, self.p["deny"], r)
        self.assertFalse([a for a in self.p["allow"] if a.startswith("Read")], "no Read beyond the project dir")
        self.assertNotIn("additionalDirectories", self.p)

    def test_every_rule_is_well_formed(self):
        # 2.1.319 stops at a "Settings Warning" dialog on a malformed rule (lower-case "advisor" from the spike did
        # that) and Jarvis would hang on it; a rule naming no tool only warns, so "advisor" is left out altogether
        shape = re.compile(r"(?:mcp__\w+|[A-Z][A-Za-z]*)(?:\([^()]+\))?")
        for r in self.p["allow"] + self.p["deny"]:
            self.assertTrue(shape.fullmatch(r), r)
        self.assertNotIn("advisor", [r.lower() for r in self.p["deny"]])

    def test_no_allow_rule_is_also_denied_or_broad(self):
        for a in self.p["allow"]:
            self.assertNotIn(a, self.p["deny"])
            self.assertNotIn(a, ("Bash", "Bash(*)", "Bash(*:*)"))

    def test_5_plugins_off_including_omc_and_the_synced_org_plugin(self):
        ep = self.s["enabledPlugins"]
        self.assertIs(ep["oh-my-claudecode@omc"], False)
        self.assertIs(ep["claude-for-nonprofits@synced"], False)
        self.assertTrue(all(v is False for v in ep.values()))

    def test_5_global_claude_md_excluded(self):
        ex = self.s["claudeMdExcludes"]
        self.assertIn("@HOME@/.claude/CLAUDE.md", ex)
        self.assertIn("@HOME@/.claude-remote/accounts/*/CLAUDE.md", ex)
        self.assertFalse([e for e in ex if "jarvis" in e.lower()], "Jarvis's own CLAUDE.md must load")

    def test_4g_claude_ai_connectors_off(self):
        self.assertIs(self.s["disableClaudeAiConnectors"], True)

    def test_3a_effort_medium_from_the_project_both_keys(self):
        self.assertEqual(self.s["maxEffortLevel"], "medium")
        self.assertEqual(self.s["effortLevel"], "medium")

    def test_hooks_not_disabled(self):
        # disableAllHooks would also kill the brief, the delta and the gate
        self.assertNotIn("disableAllHooks", self.s)

    def test_session_start_runs_for_every_source_including_compact(self):
        # no matcher = startup, resume, clear and compact; R9 relies on the compact re-brief
        ss = self.s["hooks"]["SessionStart"]
        self.assertEqual(len(ss), 1)
        self.assertNotIn("matcher", ss[0])
        self.assertTrue(ss[0]["hooks"][0]["command"].endswith('/.claude/hooks/session-start.sh"'))

    def test_user_prompt_submit_runs_the_delta(self):
        ups = self.s["hooks"]["UserPromptSubmit"]
        self.assertTrue(ups[0]["hooks"][0]["command"].endswith('/.claude/hooks/prompt-delta.sh"'))

    def test_4f_gate_is_called_through_an_absolute_interpreter(self):
        # a missing interpreter (127) lets the call through; python3 <missing file> exits 2, which blocks
        pre = self.s["hooks"]["PreToolUse"]
        self.assertEqual(pre[0]["matcher"], "Bash")
        self.assertEqual(pre[0]["hooks"][0]["command"], '"@PY@" -I "@JARVIS@/.claude/hooks/gate.py"')

    def test_hook_timeouts_are_short(self):
        for ev in self.s["hooks"].values():
            for m in ev:
                for h in m["hooks"]:
                    self.assertLessEqual(h["timeout"], 20)

    def test_no_model_instruction_writes_effort(self):
        with open(os.path.join(SRC, "CLAUDE.md"), encoding="utf-8") as f:
            md = f.read()
        self.assertIn("Nikdy nepiš `/effort` ani `/config`", md)
        self.assertLessEqual(len(md.splitlines()), 150)


class GateTest(unittest.TestCase):
    def assertAllowed(self, cmd):
        r = bash(cmd)
        self.assertEqual(r.returncode, 0, (cmd, r.stderr))

    def assertBlocked(self, cmd):
        r = bash(cmd)
        self.assertEqual(r.returncode, 2, (cmd, r.stdout, r.stderr))
        self.assertIn("jarvis gate", r.stderr)

    def test_read_commands_pass(self):
        for c in ("cr-sessions list", "cr-sessions list --all", "cr-sessions list --json", "cr-sessions show soniox",
                  "cr-sessions show 'iss242'", "cr-sessions tail Deepseek 10", "cr-sessions show claude-server-a--b",
                  "cr-digest delta --peek", "cr-digest delta --say", "cr-digest brief --max-chars 3000",
                  "cr-digest brief --max-chars=3000", "cr-digest waiting --say", "cr-digest usage --json",
                  "cr-digest --help", "cr-sessions show účetnictví", "  cr-digest brief  "):
            self.assertAllowed(c)

    def test_attacks_are_blocked(self):
        for c in ("cr-sessions; rm -rf ~", "cr-sessions list; rm -rf ~", "cr-sessions list && touch /tmp/x",
                  "cr-sessions list || id", "cr-sessions list & id", "cr-sessions show $(id)", "cr-sessions show `id`",
                  "cr-sessions show ${HOME}", "cr-sessions show $HOME", "cr-sessions list\nrm -rf ~",
                  "cr-sessions list\rid", "cr-digest brief | sh", "cr-digest brief > ~/.bashrc",
                  "cr-digest brief < /etc/passwd", "cr-digest brief >> x", "cr-sessions show (id)",
                  "cr-sessions show \\$(id)", "tmux kill-server", "tmux send-keys -t x 'rm -rf ~' Enter",
                  "ls", "rm -rf /", "ssh host", "bash -c 'cr-sessions list'", "FOO=1 cr-sessions list",
                  "/usr/bin/cr-sessions list", "./cr-sessions list", "cr-sessionsX list", "cr-sessions-evil list",
                  "cr-mail send x hi", "cr-session close x", "cr-digest", "cr-sessions", "cr-sessions rm x",
                  "cr-digest reset", "cr-sessions list *", "cr-sessions show ~root", "cr-sessions show {a,b}",
                  "cr-sessions show 'unbalanced", "cr-sessions\tlist", "cr-sessions show a\x00b", "",
                  "cr-sessions show \u0000", "cr-sessions show x id"):
            self.assertBlocked(c)

    def test_non_bash_tools_are_left_to_the_permission_rules(self):
        self.assertEqual(gate({"tool_name": "Read", "tool_input": {"file_path": "/etc/passwd"}}).returncode, 0)

    def test_fails_closed_on_any_malformed_input(self):
        for raw in ("", "not json", "[]", "null", '{"tool_name": "Bash"}', '{"tool_name": "Bash", "tool_input": []}',
                    '{"tool_name": "Bash", "tool_input": {"command": 5}}',
                    '{"tool_name": "Bash", "tool_input": {"command": null}}'):
            r = gate(None, raw=raw)
            self.assertEqual(r.returncode, 2, (raw, r.stderr))

    def test_a_missing_gate_file_still_blocks(self):
        with tempfile.TemporaryDirectory() as td:
            r = subprocess.run([sys.executable, "-I", os.path.join(td, "gate.py")], input="{}", capture_output=True,
                               text=True, timeout=30)
        self.assertEqual(r.returncode, 2)

    def test_allowlist_is_read_only_in_f1(self):
        self.assertEqual(load_gate().ALLOWED, {"cr-sessions": {"list", "show", "tail"},
                                               "cr-digest": {"delta", "brief", "waiting", "usage"}})

    def test_line_shape_alone_rejects_every_metacharacter(self):
        # the argument check would catch most of these too; the line regex must hold on its own
        line = load_gate().LINE
        self.assertTrue(line.fullmatch("cr-sessions show soniox"))
        for ch in ";&|$`<>()\\\n\r\t\x00\x1b\x7f":
            self.assertIsNone(line.fullmatch(f"cr-sessions show a{ch}b"), repr(ch))


def load_gate():
    import importlib.util
    spec = importlib.util.spec_from_file_location("jarvis_gate", GATE)
    g = importlib.util.module_from_spec(spec)
    old, sys.dont_write_bytecode = sys.dont_write_bytecode, True  # no __pycache__ inside the template
    try:
        spec.loader.exec_module(g)
    finally:
        sys.dont_write_bytecode = old
    return g


class Box:
    """A temp HOME with a fake tmux first on PATH and a stub cr-digest."""

    def __init__(self, tc):
        td = tempfile.TemporaryDirectory(prefix="jarvis-proj-")
        tc.addCleanup(td.cleanup)
        self.root = td.name
        self.home = os.path.join(self.root, "home")
        self.bin = os.path.join(self.root, "bin")
        self.fake = os.path.join(self.root, "fakebin")
        self.state = os.path.join(self.root, "state")
        self.log = os.path.join(self.root, "calls.log")
        for d in (self.home, self.bin, self.fake):
            os.makedirs(d)
        self.target = os.path.join(self.home, "jarvis")
        self.tmux_name("claude-server-jarvis--Jarvis")
        self.digest("echo \"[jarvis $1] args: $*\"")
        self.write(os.path.join(self.bin, "cr-sessions"), "#!/bin/sh\necho sessions\n", 0o755)

    @staticmethod
    def write(path, text, mode=0o644):
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        os.chmod(path, mode)

    def tmux_name(self, out_sh):
        # the fake tmux logs its argv and prints whatever out_sh prints
        self.write(os.path.join(self.fake, "tmux"),
                   f"#!/bin/sh\necho \"$*\" >> '{self.log}'\nprintf '%s\\n' '{out_sh}'\n", 0o755)

    def tmux_script(self, body):
        self.write(os.path.join(self.fake, "tmux"), f"#!/bin/sh\necho \"$*\" >> '{self.log}'\n{body}\n", 0o755)

    def digest(self, body):
        self.write(os.path.join(self.bin, "cr-digest"), f"#!/bin/sh\n{body}\n", 0o755)

    def env(self, **kw):
        return clean_env(HOME=self.home, JARVIS_STATE_DIR=self.state,
                         PATH=self.fake + os.pathsep + "/usr/bin:/bin", **kw)

    def install(self, *extra, check=True):
        r = subprocess.run(["bash", INSTALL, "--bin", self.bin, "--python", sys.executable, *extra], env=self.env(),
                           capture_output=True, text=True, timeout=60)
        if check and r.returncode != 0:
            raise AssertionError(r.stdout + r.stderr)
        return r

    def path(self, rel):
        return os.path.join(self.target, rel)

    def read(self, rel):
        with open(self.path(rel), encoding="utf-8") as f:
            return f.read()

    def hook(self, rel, stdin='{"hook_event_name": "x"}', **env):
        return subprocess.run([self.path(rel)], input=stdin, env=self.env(**env), capture_output=True, text=True,
                              timeout=30, cwd=self.target)

    def calls(self):
        try:
            with open(self.log) as f:
                return f.read().splitlines()
        except FileNotFoundError:
            return []


class InstallProjectTest(unittest.TestCase):
    def setUp(self):
        self.b = Box(self)

    def test_fresh_install_layout_modes_and_paths(self):
        b = self.b
        b.install()
        for rel, mode in ((".claude/hooks/gate.py", 0o755), (".claude/hooks/session-start.sh", 0o755),
                          (".claude/hooks/prompt-delta.sh", 0o755), (".claude/settings.json", 0o644),
                          ("CLAUDE.md", 0o644), ("pravidla.md", 0o644)):
            self.assertEqual(stat.S_IMODE(os.stat(b.path(rel)).st_mode), mode, rel)
        self.assertEqual(os.listdir(b.home), ["jarvis"])  # nothing outside the target
        s = json.loads(b.read(".claude/settings.json"))
        raw = b.read(".claude/settings.json") + b.read(".claude/hooks/session-start.sh") + \
            b.read(".claude/hooks/prompt-delta.sh")
        self.assertIsNone(PLACEHOLDER.search(raw))
        self.assertIn(b.home + "/.claude/CLAUDE.md", s["claudeMdExcludes"])
        # every hook command names an installed, executable file
        for ev in s["hooks"].values():
            for m in ev:
                for h in m["hooks"]:
                    paths = re.findall(r'"([^"]+)"', h["command"])
                    self.assertTrue(paths, h["command"])
                    for p in paths:
                        self.assertTrue(p.startswith("/"), p)
                        self.assertTrue(os.path.isfile(p), p)
                        if p != sys.executable:
                            self.assertTrue(p.startswith(b.target + "/.claude/hooks/"), p)
                    self.assertTrue(os.access(paths[-1], os.X_OK), paths[-1])
        self.assertIn(f'"{b.bin}/cr-digest" brief --max-chars 8000', b.read(".claude/hooks/session-start.sh"))
        self.assertIn(f'"{b.bin}/cr-digest" delta --max-chars 8000', b.read(".claude/hooks/prompt-delta.sh"))

    def test_installed_gate_command_runs_as_configured(self):
        b = self.b
        b.install()
        cmd = json.loads(b.read(".claude/settings.json"))["hooks"]["PreToolUse"][0]["hooks"][0]["command"]
        for c, code in (("cr-sessions list", 0), ("cr-sessions list; id", 2), ("tmux ls", 2)):
            r = subprocess.run(["sh", "-c", cmd], input=json.dumps({"tool_name": "Bash", "tool_input": {"command": c}}),
                               env=b.env(), capture_output=True, text=True, timeout=30, cwd=b.target)
            self.assertEqual(r.returncode, code, (c, r.stderr))

    def test_second_run_changes_nothing(self):
        b = self.b
        b.install()
        before = {r: (b.read(r), os.stat(b.path(r)).st_mtime_ns) for r in ("CLAUDE.md", ".claude/settings.json")}
        r = b.install()
        self.assertNotIn("created", r.stdout)
        self.assertNotIn("KEPT", r.stdout)
        self.assertIn("unchanged", r.stdout)
        for rel, (txt, mt) in before.items():
            self.assertEqual((b.read(rel), os.stat(b.path(rel)).st_mtime_ns), (txt, mt), rel)

    def test_a_file_lukas_changed_is_kept_and_the_diff_printed(self):
        b = self.b
        b.install()
        mine = b.read("CLAUDE.md") + "\nMoje poznámka.\n"
        b.write(b.path("CLAUDE.md"), mine)
        r = b.install()
        self.assertEqual(r.returncode, 0)
        self.assertEqual(b.read("CLAUDE.md"), mine)
        self.assertIn("KEPT", r.stdout)
        self.assertIn("-Moje poznámka.", r.stdout)
        # still "changed" on the next run, not silently adopted as ours
        self.assertIn("KEPT", b.install().stdout)

    def test_an_unchanged_old_version_is_updated(self):
        b = self.b
        b.install()
        old = "# Jarvis\nstará verze šablony\n"
        b.write(b.path("CLAUDE.md"), old)
        rec = b.path(".claude/jarvis-installed")
        with open(rec, encoding="utf-8") as f:
            lines = [ln for ln in f.read().splitlines() if not ln.endswith("  CLAUDE.md")]
        import hashlib
        lines.append(hashlib.sha256(old.encode()).hexdigest() + "  CLAUDE.md")
        b.write(rec, "\n".join(lines) + "\n")
        r = b.install()
        self.assertIn("updated", r.stdout)
        with open(os.path.join(SRC, "CLAUDE.md"), encoding="utf-8") as f:
            self.assertEqual(b.read("CLAUDE.md"), f.read())

    def test_a_file_without_a_record_counts_as_changed(self):
        b = self.b
        os.makedirs(b.path(".claude"))
        b.write(b.path(".claude/settings.json"), "{}\n")
        r = b.install()
        self.assertIn("KEPT", r.stdout)
        self.assertEqual(b.read(".claude/settings.json"), "{}\n")

    def test_force_replaces_and_keeps_a_backup(self):
        b = self.b
        b.install()
        b.write(b.path("CLAUDE.md"), "moje\n")
        r = b.install("--force")
        self.assertIn("replaced", r.stdout)
        baks = [f for f in os.listdir(b.target) if f.startswith("CLAUDE.md.bak.")]
        self.assertEqual(len(baks), 1)
        with open(os.path.join(b.target, baks[0]), encoding="utf-8") as f:
            self.assertEqual(f.read(), "moje\n")
        self.assertNotIn("KEPT", b.install().stdout)

    def test_pravidla_is_lukas_file_from_the_start(self):
        b = self.b
        b.install()
        b.write(b.path("pravidla.md"), "- nehlas session=x druh=done do=2026-11-14\n")
        r = b.install("--force")
        self.assertEqual(b.read("pravidla.md"), "- nehlas session=x druh=done do=2026-11-14\n")
        self.assertNotIn("pravidla.md (template)", r.stdout)

    def test_dry_run_writes_nothing(self):
        b = self.b
        r = b.install("--dry-run")
        self.assertIn("would create", r.stdout)
        self.assertFalse(os.path.exists(b.target))

    def test_refuses_paths_it_cannot_quote(self):
        r = self.b.install("--target", self.b.home + '/ja"rvis', check=False)
        self.assertEqual(r.returncode, 2)
        r = self.b.install("--target", "relative/jarvis", check=False)
        self.assertEqual(r.returncode, 2)


class HooksTest(unittest.TestCase):
    def setUp(self):
        self.b = Box(self)
        self.b.install()

    def self_file(self):
        p = os.path.join(self.b.state, "self")
        if not os.path.exists(p):
            return None
        self.assertEqual(stat.S_IMODE(os.stat(p).st_mode), 0o600)
        with open(p) as f:
            return f.read()

    def test_session_start_writes_self_from_its_own_pane_then_prints_the_brief(self):
        b = self.b
        r = b.hook(".claude/hooks/session-start.sh", TMUX_PANE="%42")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.strip(), "[jarvis brief] args: brief --max-chars 8000")
        self.assertEqual(self.self_file(), "claude-server-jarvis--Jarvis\n")
        self.assertEqual(b.calls(), ["display-message -p -t %42 #S"])
        self.assertEqual([f for f in os.listdir(b.state) if f != "self"], [])  # no tmp left behind

    def test_session_start_without_a_pane_writes_no_self(self):
        b = self.b
        for pane in (None, "", "42", "%", "%4x", "%1;id"):
            env = {} if pane is None else {"TMUX_PANE": pane}
            r = b.hook(".claude/hooks/session-start.sh", **env)
            self.assertEqual(r.returncode, 0)
            self.assertIn("[jarvis brief]", r.stdout)
        self.assertIsNone(self.self_file())
        self.assertEqual(b.calls(), [])

    def test_session_start_ignores_a_bad_tmux_answer(self):
        b = self.b
        for body in ("exit 1", "printf ''", "printf 'a\\nb\\n'", "python3 -c \"print('x'*300)\"", "sleep 10"):
            b.tmux_script(body)
            r = b.hook(".claude/hooks/session-start.sh", TMUX_PANE="%7")
            self.assertEqual(r.returncode, 0, body)
            self.assertIn("[jarvis brief]", r.stdout, body)
            self.assertIsNone(self.self_file(), body)

    def test_session_start_replaces_an_old_self(self):
        b = self.b
        os.makedirs(b.state)
        Box.write(os.path.join(b.state, "self"), "stary\n", 0o600)
        b.hook(".claude/hooks/session-start.sh", TMUX_PANE="%1")
        self.assertEqual(self.self_file(), "claude-server-jarvis--Jarvis\n")

    def test_session_start_survives_a_broken_digest(self):
        b = self.b
        for body in ("exit 2", "exit 1", "true"):
            b.digest(body)
            r = b.hook(".claude/hooks/session-start.sh", TMUX_PANE="%1")
            self.assertEqual(r.returncode, 0, body)
            self.assertIn("[jarvis brief nedostupný", r.stdout, body)
        os.remove(os.path.join(b.bin, "cr-digest"))
        r = b.hook(".claude/hooks/session-start.sh")
        self.assertEqual((r.returncode, "nedostupný" in r.stdout), (0, True))

    def test_prompt_delta_never_blocks_the_prompt(self):
        # exit 2 from UserPromptSubmit blocks and erases Lukáš's prompt
        b = self.b
        r = b.hook(".claude/hooks/prompt-delta.sh")
        self.assertEqual((r.returncode, r.stdout.strip()), (0, "[jarvis delta] args: delta --max-chars 8000"))
        for body in ("exit 2", "echo half; exit 2", "kill -9 $$"):
            b.digest(body)
            r = b.hook(".claude/hooks/prompt-delta.sh")
            self.assertEqual(r.returncode, 0, body)
            self.assertIn("[jarvis delta nedostupná", r.stdout, body)
        self.assertEqual(b.calls(), [])  # the delta hook never asks tmux

    def test_hooks_with_the_real_digest_stay_under_the_hook_limit(self):
        # the installed hooks against the real cr_digest.py on an empty fake box: they run and say the daemon is down
        b = self.b
        digest = os.path.join(HERE, "cr_digest.py")
        b.digest(f'exec "{sys.executable}" "{digest}" "$@"')
        for hook in (".claude/hooks/session-start.sh", ".claude/hooks/prompt-delta.sh"):
            r = b.hook(hook, JARVIS_TMUX="")
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertIn("jarvis-d neběží", r.stdout, hook)
            self.assertLess(len(r.stdout), 10000)


if __name__ == "__main__":
    unittest.main()
