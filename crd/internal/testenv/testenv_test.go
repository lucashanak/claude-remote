package testenv

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func TestMain(m *testing.M) { Main(m, Options{}) }

func TestEnvironmentIsPrivate(t *testing.T) {
	for _, k := range []string{"TMUX", "TMUX_PANE", "NOTIFY_SOCKET", "INVOCATION_ID", "DBUS_SESSION_BUS_ADDRESS", "XDG_CACHE_HOME"} {
		if _, ok := os.LookupEnv(k); ok {
			t.Errorf("%s still set", k)
		}
	}
	for _, kv := range os.Environ() {
		if strings.HasPrefix(kv, "CLAUDE") {
			t.Errorf("%s still set", strings.SplitN(kv, "=", 2)[0])
		}
	}
	if !Under(os.Getenv("HOME"), PkgRoot()) || !Under(os.Getenv("TMUX_TMPDIR"), PkgRoot()) || !Under(os.Getenv("XDG_RUNTIME_DIR"), PkgRoot()) {
		t.Fatal("HOME/TMUX_TMPDIR/XDG_RUNTIME_DIR not under the test root")
	}
	if os.Getenv("CRD_TEST_TAG") != Tag() || len(Tag()) != 16 {
		t.Fatal("run tag missing")
	}
}

// D-H2: tmux resolves to the repo shim, and the shim refuses the live socket without connecting (exit 97).
func TestTmuxIsTheShimAndRefusesTheLiveSocket(t *testing.T) {
	lp, err := exec.LookPath("tmux")
	if err != nil || lp != filepath.Join(RepoRoot(), "scripts", "test-shims", "tmux") {
		t.Fatalf("tmux on PATH = %q, %v", lp, err)
	}
	live := filepath.Join("/tmp", "tmux-"+strconv.Itoa(os.Getuid()), "default")
	cmd := Command(t, nil, "tmux", "-S", live, "list-sessions")
	err = cmd.Run()
	ee, ok := err.(*exec.ExitError)
	if !ok || ee.ExitCode() != 97 {
		t.Fatalf("shim with the live socket: %v (want exit 97)", err)
	}
}

func TestUnderIsStrictAndResolvesLinks(t *testing.T) {
	r := NewRoot(t)
	if Under(r.Dir, r.Dir) {
		t.Error("a root is not under itself")
	}
	if !Under(filepath.Join(r.Home, "x", "y"), r.Dir) {
		t.Error("non-existing child not under root")
	}
	if Under(r.Dir+"x", r.Dir) || Under("/", r.Dir) || Under(r.Dir+"/../..", r.Dir) {
		t.Error("prefix or parent accepted")
	}
	SafeSymlink(t, r.Home, filepath.Join(r.Tmp, "home-link"))
	if !Under(filepath.Join(r.Tmp, "home-link", "f"), r.Dir) {
		t.Error("link inside the root not resolved")
	}
}

func TestSafeRemoveAllRefusesLinkedAncestorsAndOutsidePaths(t *testing.T) {
	r := NewRoot(t)
	inner := filepath.Join(r.Home, "a", "b")
	if err := os.MkdirAll(inner, 0o700); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(r.Tmp, "via")
	SafeSymlink(t, filepath.Join(r.Home, "a"), link)
	if err := SafeRemoveAll(filepath.Join(link, "b")); err == nil {
		t.Fatal("removal through a linked ancestor allowed")
	}
	if _, err := os.Stat(inner); err != nil {
		t.Fatal("target behind the link was removed")
	}
	if err := SafeRemoveAll(filepath.Dir(PkgRoot())); err == nil {
		t.Fatal("removal outside the test root allowed")
	}
	if err := SafeRemoveAll(filepath.Join(r.Home, "a")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(inner); !os.IsNotExist(err) {
		t.Fatal("not removed")
	}
	// Go's RemoveAll does not follow a link inside the tree (the link is unlinked, its target stays).
	keep := filepath.Join(r.Tmp, "keep")
	if err := os.Mkdir(keep, 0o700); err != nil {
		t.Fatal(err)
	}
	tree := filepath.Join(r.Home, "tree")
	_ = os.Mkdir(tree, 0o700)
	SafeSymlink(t, keep, filepath.Join(tree, "l"))
	if err := SafeRemoveAll(tree); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(keep); err != nil {
		t.Fatal("RemoveAll followed a link inside the tree")
	}
}

func TestWalkLinksReportsALinkLeavingItsRoot(t *testing.T) {
	r := NewRoot(t)
	sub := filepath.Join(r.Home, "sub")
	_ = os.Mkdir(sub, 0o700)
	SafeSymlink(t, r.Tmp, filepath.Join(sub, "out"))
	SafeSymlink(t, "inside", filepath.Join(sub, "in"))
	bad := walkLinks(sub)
	if len(bad) != 1 || !strings.Contains(bad[0], "out") {
		t.Fatalf("walkLinks(%s) = %v", sub, bad)
	}
}

func TestCommandPanicsOnAChildOutsideTheRoot(t *testing.T) {
	defer func() {
		if recover() == nil {
			t.Fatal("no panic for HOME outside the root")
		}
	}()
	Command(t, []string{"HOME=/"}, "true")
}

func TestSignalRefusesAForeignPid(t *testing.T) {
	if ours(1) || ours(os.Getppid()) {
		t.Fatal("pid 1 or the parent counted as ours")
	}
	cmd := Command(t, nil, "sleep", "30")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	Signal(t, cmd.Process.Pid, 9)
	_ = cmd.Wait()
}

func TestRootPathsFitSunPath(t *testing.T) {
	r := NewRoot(t)
	if len(r.Sock()) > 100 || len(r.GuardedSocket()) > 100 {
		t.Fatalf("socket paths too long: %d %d", len(r.Sock()), len(r.GuardedSocket()))
	}
}

// A-F15: a test base (GOTMPDIR) inside the live tmux directory or the app's live trees is refused, also through a
// link; a private directory elsewhere is fine.
func TestCheckBaseRefusesLiveDirectories(t *testing.T) {
	r := NewRoot(t)
	home := filepath.Join(r.Dir, "home")
	for _, d := range []string{".claude-remote/crd", ".cache/claude-remote", ".claude"} {
		if err := os.MkdirAll(filepath.Join(home, d), 0o700); err != nil {
			t.Fatal(err)
		}
	}
	SafeSymlink(t, filepath.Join(home, ".claude-remote"), filepath.Join(r.Dir, "lnk"))
	uid := os.Getuid()
	for _, b := range []string{fmt.Sprintf("/tmp/tmux-%d", uid), fmt.Sprintf("/tmp/tmux-%d/x", uid),
		filepath.Join(home, ".claude-remote"), filepath.Join(home, ".claude-remote", "crd", "t"),
		filepath.Join(home, ".cache", "claude-remote", "t"), filepath.Join(home, ".claude", "x"), filepath.Join(r.Dir, "lnk", "t")} {
		if err := CheckBase(b, home, uid); err == nil {
			t.Errorf("base %s accepted", b)
		}
	}
	for _, b := range []string{filepath.Join(r.Dir, "ok"), filepath.Join(home, ".claude-remote-other")} {
		if err := CheckBase(b, home, uid); err != nil {
			t.Errorf("base %s refused: %v", b, err)
		}
	}
	if err := SafeRemoveAll(filepath.Join(r.Dir, "lnk")); err != nil {
		t.Fatal(err)
	}
}
