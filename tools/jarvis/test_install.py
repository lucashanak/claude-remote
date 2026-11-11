"""install.sh into a temp prefix: a copied release, an atomic `current`, command links, the cron line only printed."""
import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


@unittest.skipIf(os.environ.get("JARVIS_INSTALL_RUNNING"), "install.sh runs this suite itself")
class InstallTest(unittest.TestCase):
    def setUp(self):
        td = tempfile.TemporaryDirectory(prefix="jarvis-install-")
        self.addCleanup(td.cleanup)
        self.root = td.name
        self.home = os.path.join(self.root, "home")
        os.makedirs(self.home)
        self.prefix = os.path.join(self.root, "prefix")

    def install(self, *extra, stamp=None):
        env = {k: v for k, v in os.environ.items() if k not in ("TMUX", "TMUX_PANE", "CLAUDE_CONFIG_DIR",
                                                                 "JARVIS_INSTALL_STAMP")}
        env["HOME"] = self.home
        if stamp:
            env["JARVIS_INSTALL_STAMP"] = stamp
        return subprocess.run(["bash", os.path.join(HERE, "install.sh"), "--prefix", self.prefix, "--no-test", *extra],
                              env=env, capture_output=True, text=True, timeout=60)

    def test_layout(self):
        r = self.install()
        self.assertEqual(r.returncode, 0, r.stderr)
        lib = os.path.join(self.prefix, "lib", "jarvis")
        cur = os.path.realpath(os.path.join(lib, "current"))
        self.assertTrue(cur.startswith(os.path.realpath(lib) + os.sep))
        self.assertEqual(sorted(os.listdir(cur)), ["SHA256SUMS", "cr_approve.py", "cr_digest.py", "cr_jarvis.py",
                                                   "cr_mail.py", "cr_rules.py", "cr_sessions.py", "cr_task.py",
                                                   "jarvis_d.py", "jarvisacct.py", "jarvisalert.py", "jarvisapprove.py",
                                                   "jarvislib.py", "jarvismail.py", "jarvisops.py", "jarviswatch.py"])
        for b, f in (("cr-sessions", "cr_sessions.py"), ("cr-digest", "cr_digest.py"), ("jarvis-d", "jarvis_d.py"),
                     ("cr-rules", "cr_rules.py"), ("cr-jarvis", "cr_jarvis.py"), ("cr-approve", "cr_approve.py")):
            p = os.path.join(self.prefix, "bin", b)
            self.assertTrue(os.path.islink(p))
            self.assertEqual(os.path.realpath(p), os.path.join(cur, f))
            self.assertTrue(os.access(p, os.X_OK))
        self.assertIn("* * * * * " + os.path.join(self.prefix, "bin", "jarvis-d"), r.stdout)
        # nothing outside the prefix except the default mail allowlist (only the test session)
        allow = os.path.join(self.home, ".claude-remote", "jarvis", "mail-allow")
        with open(allow) as f:
            self.assertEqual([l for l in f.read().splitlines() if not l.startswith("#")],
                             ["claude-server-jarvis-test--jarvis-test"])
        self.assertEqual(os.listdir(self.home), [".claude-remote"])
        self.assertEqual(os.stat(os.path.dirname(allow)).st_mode & 0o777, 0o700)
        # the release records what it installed, and jarvismail.integrity finds nothing changed
        import sys
        sys.path.insert(0, HERE)
        import jarvismail
        self.assertEqual(jarvismail.integrity(cur), [])
        os.chmod(os.path.join(cur, "cr_mail.py"), 0o644)
        with open(os.path.join(cur, "cr_mail.py"), "a") as f:
            f.write("# changed\n")
        self.assertEqual(jarvismail.integrity(cur), [os.path.basename(cur) + "/cr_mail.py"])

    def test_an_existing_allowlist_is_left_alone(self):
        d = os.path.join(self.home, ".claude-remote", "jarvis")
        os.makedirs(d)
        with open(os.path.join(d, "mail-allow"), "w") as f:
            f.write("claude-server-x--y\n")
        self.assertEqual(self.install().returncode, 0)
        with open(os.path.join(d, "mail-allow")) as f:
            self.assertEqual(f.read(), "claude-server-x--y\n")

    def test_installed_command_runs(self):
        self.install()
        env = {"HOME": self.home, "PATH": os.environ.get("PATH", "/usr/bin:/bin"), "JARVIS_TMUX": "",
               "JARVIS_STATE_DIR": os.path.join(self.root, "state")}
        r = subprocess.run([os.path.join(self.prefix, "bin", "cr-digest"), "usage", "--say"], env=env,
                           capture_output=True, text=True, timeout=30)
        self.assertEqual((r.returncode, r.stdout.strip()), (0, "O limitech nemám data."), r.stderr)

    def test_a_foreign_file_is_left_alone(self):
        os.makedirs(os.path.join(self.prefix, "bin"))
        mine = os.path.join(self.prefix, "bin", "cr-digest")
        with open(mine, "w") as f:
            f.write("not ours")
        r = self.install()
        self.assertIn("is not our link, left alone", r.stderr)
        with open(mine) as f:
            self.assertEqual(f.read(), "not ours")

    def test_dry_run_writes_nothing(self):
        r = self.install("--dry-run")
        self.assertEqual(r.returncode, 0)
        self.assertFalse(os.path.exists(self.prefix))

    # rev L4: a link that points anywhere but into our lib is not ours either
    def test_a_foreign_link_is_left_alone(self):
        os.makedirs(os.path.join(self.prefix, "bin"))
        target = os.path.join(self.root, "elsewhere")
        with open(target, "w") as f:
            f.write("x")
        link = os.path.join(self.prefix, "bin", "cr-sessions")
        subprocess.run(["ln", "-s", target, link], check=True)  # inside the temp root
        r = self.install()
        self.assertIn("is not our link, left alone", r.stderr)
        self.assertEqual(os.readlink(link), target)
        # our own link is replaced on the next install
        r = self.install(stamp="20991231T000000Z-test")
        self.assertTrue(os.path.realpath(os.path.join(self.prefix, "bin", "cr-digest")).endswith(
            "20991231T000000Z-test/cr_digest.py"))

    # rev L4: a release is never rewritten in place
    def test_existing_release_is_refused(self):
        self.assertEqual(self.install(stamp="S1").returncode, 0)
        r = self.install(stamp="S1")
        self.assertEqual(r.returncode, 1)
        self.assertIn("already exists", r.stderr)

    # rev L4: the release name carries `git describe --always --dirty`
    def test_release_name_has_git_describe(self):
        self.install()
        want = subprocess.run(["git", "-C", HERE, "describe", "--always", "--dirty"], capture_output=True,
                              text=True).stdout.strip() or "nogit"
        cur = os.readlink(os.path.join(self.prefix, "lib", "jarvis", "current"))
        self.assertTrue(cur.endswith("-" + want), cur)


if __name__ == "__main__":
    unittest.main()
