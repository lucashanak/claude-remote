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

    def install(self, *extra):
        env = {k: v for k, v in os.environ.items() if k not in ("TMUX", "TMUX_PANE", "CLAUDE_CONFIG_DIR")}
        env["HOME"] = self.home
        return subprocess.run(["bash", os.path.join(HERE, "install.sh"), "--prefix", self.prefix, "--no-test", *extra],
                              env=env, capture_output=True, text=True, timeout=60)

    def test_layout(self):
        r = self.install()
        self.assertEqual(r.returncode, 0, r.stderr)
        lib = os.path.join(self.prefix, "lib", "jarvis")
        cur = os.path.realpath(os.path.join(lib, "current"))
        self.assertTrue(cur.startswith(os.path.realpath(lib) + os.sep))
        self.assertEqual(sorted(os.listdir(cur)), ["cr_digest.py", "cr_sessions.py", "jarvis_d.py", "jarvislib.py"])
        for b, f in (("cr-sessions", "cr_sessions.py"), ("cr-digest", "cr_digest.py"), ("jarvis-d", "jarvis_d.py")):
            p = os.path.join(self.prefix, "bin", b)
            self.assertTrue(os.path.islink(p))
            self.assertEqual(os.path.realpath(p), os.path.join(cur, f))
            self.assertTrue(os.access(p, os.X_OK))
        self.assertIn("* * * * * " + os.path.join(self.prefix, "bin", "jarvis-d"), r.stdout)
        self.assertEqual(os.listdir(self.home), [])  # nothing outside the prefix

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
        self.assertIn("left alone", r.stderr)
        with open(mine) as f:
            self.assertEqual(f.read(), "not ours")

    def test_dry_run_writes_nothing(self):
        r = self.install("--dry-run")
        self.assertEqual(r.returncode, 0)
        self.assertFalse(os.path.exists(self.prefix))


if __name__ == "__main__":
    unittest.main()
