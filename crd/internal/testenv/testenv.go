// Package testenv is the Go test harness for crd (spec §8.4.5, CLAUDE.md Traps). Every test package that starts
// a process calls Main from its TestMain (crdlint fails CI otherwise). Main prepares the environment of the test
// process itself before anything runs: a private root, HOME/TMUX_TMPDIR/XDG_RUNTIME_DIR under it, the repo's tmux
// shim first on PATH, and no TMUX, TMUX_PANE, CLAUDE*, systemd or D-Bus variables. Children inherit exactly that.
// After the tests it sweeps processes carrying this run's tag, walks the root for links that leave it, removes the
// root without following links, and compares a read-only canary of the live box (never a connect, never tmux).
//
// Only _test.go files may import this package (crdlint): the tmux and signal helpers below never reach the crd
// binary.
package testenv

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
)

// Options select the optional parts of Main.
type Options struct {
	// TmuxLock takes ~/.cache/crit-integration.lock (path from the real home, computed before HOME is redirected)
	// in-process with flock(2), like the Kotlin integration tests. A flock(1) wrapper would be killed by the old ET
	// bootstrap after 30 s (EtServerInstallScriptTest.kt).
	TmuxLock bool
}

var (
	pkgRoot  string // private root of this test binary
	realHome string // the user's home from /etc/passwd, for the canary and the lock only
	tag      string // CRD_TEST_TAG of this run
	shimDir  string
	repoRoot string
)

// stripped are removed from the test process before anything runs (D-H2).
var stripped = []string{"TMUX", "TMUX_PANE", "NOTIFY_SOCKET", "INVOCATION_ID", "SYSTEMD_EXEC_PID", "WATCHDOG_USEC",
	"WATCHDOG_PID", "LISTEN_FDS", "LISTEN_PID", "LISTEN_FDNAMES", "JOURNAL_STREAM", "DBUS_SESSION_BUS_ADDRESS",
	"XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME", "CRD_TEST_ROOT", "TMUX_TMPDIR"}

// Main runs the tests of one package inside the harness and exits.
func Main(m *testing.M, o Options) {
	os.Exit(run(m, o))
}

func run(m *testing.M, o Options) int {
	var err error
	if realHome, err = passwdHome(); err != nil {
		fmt.Fprintln(os.Stderr, "testenv:", err)
		return 2
	}
	if repoRoot, err = findRepoRoot(); err != nil {
		fmt.Fprintln(os.Stderr, "testenv:", err)
		return 2
	}
	shimDir = filepath.Join(repoRoot, "scripts", "test-shims")
	canaryBefore := takeCanary()
	if err := prepare(); err != nil {
		fmt.Fprintln(os.Stderr, "testenv: refusing to run:", err)
		return 2
	}
	if o.TmuxLock {
		f, err := os.OpenFile(filepath.Join(realHome, ".cache", "crit-integration.lock"), os.O_RDWR|os.O_CREATE, 0o644)
		if err != nil {
			fmt.Fprintln(os.Stderr, "testenv: lock:", err)
			return 2
		}
		defer f.Close()
		if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX); err != nil {
			fmt.Fprintln(os.Stderr, "testenv: flock:", err)
			return 2
		}
	}
	code := m.Run()
	if orphans := sweep(); len(orphans) > 0 {
		fmt.Fprintf(os.Stderr, "testenv: FAIL: %d orphan process(es) of this run killed: %s\n", len(orphans), strings.Join(orphans, "; "))
		code = 1
	}
	if bad := walkLinks(pkgRoot); len(bad) > 0 {
		fmt.Fprintln(os.Stderr, "testenv: FAIL: links leaving the test root:", strings.Join(bad, ", "))
		code = 1
	}
	if err := SafeRemoveAll(pkgRoot); err != nil {
		fmt.Fprintln(os.Stderr, "testenv: removing the test root:", err)
		code = 1
	}
	if diffs := canaryBefore.compare(takeCanary()); len(diffs) > 0 {
		fmt.Fprintln(os.Stderr, "testenv: CANARY FAIL:\n  "+strings.Join(diffs, "\n  "))
		code = 1
	}
	return code
}

func prepare() error {
	uid := os.Getuid()
	base := os.Getenv("GOTMPDIR")
	if base == "" || len(base) > 40 {
		base = fmt.Sprintf("/tmp/crdt-%d", uid)
	}
	if err := CheckBase(base, realHome, uid); err != nil {
		return err
	}
	if err := os.Mkdir(base, 0o700); err != nil && !os.IsExist(err) {
		return err
	}
	st, err := os.Lstat(base)
	if err != nil {
		return err
	}
	sys := st.Sys().(*syscall.Stat_t)
	if st.Mode()&fs.ModeSymlink != 0 || !st.IsDir() || int(sys.Uid) != uid || st.Mode().Perm() != 0o700 {
		return fmt.Errorf("test base %s is not a private directory of uid %d", base, uid)
	}
	if pkgRoot, err = os.MkdirTemp(base, "c"); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(pkgRoot, ".crd-test-root"), nil, 0o600); err != nil {
		return err
	}
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return err
	}
	tag = hex.EncodeToString(b)
	for _, k := range stripped {
		os.Unsetenv(k)
	}
	for _, kv := range os.Environ() {
		if k, _, _ := strings.Cut(kv, "="); strings.HasPrefix(k, "CLAUDE") {
			os.Unsetenv(k)
		}
	}
	home := filepath.Join(pkgRoot, "h")
	tmuxTmp := filepath.Join(pkgRoot, "t")
	xdg := filepath.Join(pkgRoot, "x")
	for _, d := range []string{home, tmuxTmp, xdg} {
		if err := os.Mkdir(d, 0o700); err != nil {
			return err
		}
	}
	set := map[string]string{"HOME": home, "TMUX_TMPDIR": tmuxTmp, "XDG_RUNTIME_DIR": xdg, "LC_ALL": "C.UTF-8",
		"PATH": shimDir + ":" + os.Getenv("PATH"), "CRD_TEST_TAG": tag}
	for k, v := range set {
		os.Setenv(k, v)
	}
	// Only now the checks of spec §8.4.5.
	tt := os.Getenv("TMUX_TMPDIR")
	rt, err := filepath.EvalSymlinks(tt)
	if tt == "" || err != nil || rt == "/tmp" || rt == filepath.Clean(realTmp()) {
		return fmt.Errorf("TMUX_TMPDIR %q is unset or /tmp", tt)
	}
	if !Under(os.Getenv("HOME"), pkgRoot) || !Under(rt, pkgRoot) {
		return fmt.Errorf("HOME or TMUX_TMPDIR not under the test root %s", pkgRoot)
	}
	if lp, err := exec.LookPath("tmux"); err == nil && filepath.Dir(lp) != shimDir {
		return fmt.Errorf("tmux on PATH is %s, not the shim", lp)
	}
	return nil
}

// CheckBase refuses a test base (GOTMPDIR) inside the live tmux directory or the app's live trees (review A-F15):
// test roots and private tmux sockets never sit next to the live ones, whatever GOTMPDIR says.
func CheckBase(base, realHome string, uid int) error {
	live := []string{fmt.Sprintf("/tmp/tmux-%d", uid), filepath.Join(realTmp(), fmt.Sprintf("tmux-%d", uid)),
		filepath.Join(realHome, ".claude-remote"), filepath.Join(realHome, ".cache", "claude-remote"),
		filepath.Join(realHome, ".claude")}
	rb, err := evalExisting(base)
	if err != nil {
		return fmt.Errorf("test base %s: %v", base, err)
	}
	for _, l := range live {
		rl, err := evalExisting(l)
		if err != nil {
			rl = filepath.Clean(l)
		}
		if rel, err := filepath.Rel(rl, rb); err == nil && (rel == "." || (rel != ".." && !strings.HasPrefix(rel, "../"))) {
			return fmt.Errorf("test base %s lies in the live directory %s", base, l)
		}
	}
	return nil
}

func realTmp() string {
	if r, err := filepath.EvalSymlinks("/tmp"); err == nil {
		return r
	}
	return "/tmp"
}

// Under reports whether p lies strictly below dir after resolving symlinks of both, compared by path component.
func Under(p, dir string) bool {
	rp, err1 := evalExisting(p)
	rd, err2 := filepath.EvalSymlinks(dir)
	if err1 != nil || err2 != nil {
		return false
	}
	rel, err := filepath.Rel(rd, rp)
	return err == nil && rel != "." && rel != ".." && !strings.HasPrefix(rel, "../") && !filepath.IsAbs(rel)
}

// evalExisting resolves the longest existing prefix of p and appends the rest.
func evalExisting(p string) (string, error) {
	p = filepath.Clean(p)
	rest := ""
	for {
		r, err := filepath.EvalSymlinks(p)
		if err == nil {
			return filepath.Join(r, rest), nil
		}
		parent := filepath.Dir(p)
		if parent == p {
			return "", err
		}
		rest = filepath.Join(filepath.Base(p), rest)
		p = parent
	}
}

func passwdHome() (string, error) {
	f, err := os.Open("/etc/passwd")
	if err != nil {
		return "", err
	}
	defer f.Close()
	uid := strconv.Itoa(os.Getuid())
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		p := strings.Split(sc.Text(), ":")
		if len(p) >= 6 && p[2] == uid {
			return p[5], nil
		}
	}
	return "", fmt.Errorf("uid %s not in /etc/passwd", uid)
}

func findRepoRoot() (string, error) {
	d, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		if st, err := os.Stat(filepath.Join(d, "scripts", "test-shims", "tmux")); err == nil && st.Mode()&0o111 != 0 {
			return d, nil
		}
		parent := filepath.Dir(d)
		if parent == d {
			return "", fmt.Errorf("repo root with scripts/test-shims/tmux not found above the package")
		}
		d = parent
	}
}

// PkgRoot is the private root of this test binary.
func PkgRoot() string { return pkgRoot }

// RepoRoot is the checkout root (for reading Kotlin sources and the golden vectors).
func RepoRoot() string { return repoRoot }

// Tag is CRD_TEST_TAG of this run.
func Tag() string { return tag }

// Root is a per-test private root: its own marker, HOME, TMUX_TMPDIR and legacy tmp dir, short enough for sun_path.
type Root struct {
	Dir, Home, TmuxTmp, Tmp string
}

// NewRoot makes a fresh root under the package root (D-H11); nothing is shared between tests.
func NewRoot(t testing.TB) *Root {
	t.Helper()
	d, err := os.MkdirTemp(pkgRoot, "r")
	if err != nil {
		t.Fatal(err)
	}
	r := &Root{Dir: d, Home: filepath.Join(d, "h"), TmuxTmp: filepath.Join(d, "t"), Tmp: filepath.Join(d, "tmp")}
	for _, p := range []string{r.Home, r.TmuxTmp, r.Tmp} {
		if err := os.Mkdir(p, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(d, ".crd-test-root"), nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if len(r.Sock()) > 100 || len(r.GuardedSocket()) > 100 {
		t.Fatalf("test root %s too deep for sun_path", d)
	}
	return r
}

// Env is the environment that puts a crd child in test mode on this root.
func (r *Root) Env() []string {
	return []string{"CRD_TEST_ROOT=" + r.Dir, "HOME=" + r.Home, "TMUX_TMPDIR=" + r.TmuxTmp}
}

// Sock is crd's socket under this root's HOME.
func (r *Root) Sock() string {
	return filepath.Join(r.Home, ".claude-remote", "crd", "run", "crd.sock")
}

// GuardedSocket is the path the daemon's socket guard watches on this root.
func (r *Root) GuardedSocket() string {
	rt, err := filepath.EvalSymlinks(r.TmuxTmp)
	if err != nil {
		rt = r.TmuxTmp
	}
	return filepath.Join(rt, "tmux-"+strconv.Itoa(os.Getuid()), "default")
}

// Command is the only way tests start a child: inherited environment plus overrides, a process group of its own,
// SIGKILL when the test process dies. It panics when the child's HOME, TMUX_TMPDIR or TMUX would leave the root.
func Command(t testing.TB, env []string, name string, args ...string) *exec.Cmd {
	t.Helper()
	cmd := exec.Command(name, args...)
	cmd.Env = append(os.Environ(), env...)
	final := map[string]string{}
	for _, kv := range cmd.Env {
		k, v, _ := strings.Cut(kv, "=")
		final[k] = v
	}
	if !Under(final["HOME"], pkgRoot) || !Under(final["TMUX_TMPDIR"], pkgRoot) {
		panic(fmt.Sprintf("testenv: child HOME %q / TMUX_TMPDIR %q outside the test root", final["HOME"], final["TMUX_TMPDIR"]))
	}
	if v, ok := final["TMUX"]; ok {
		p, _, _ := strings.Cut(v, ",")
		if !Under(p, pkgRoot) {
			panic(fmt.Sprintf("testenv: child TMUX %q outside the test root", v))
		}
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true, Pdeathsig: syscall.SIGKILL}
	return cmd
}

// HasTmux reports a real tmux binary behind the shim. Without one a test skips with the reason, except under
// CRD_REQUIRE_TMUX=1 (CI), where a skip is a failure.
func RequireTmux(t testing.TB) {
	t.Helper()
	for _, d := range filepath.SplitList(os.Getenv("PATH")) {
		if d == shimDir || d == "" {
			continue
		}
		if st, err := os.Stat(filepath.Join(d, "tmux")); err == nil && !st.IsDir() && st.Mode()&0o111 != 0 {
			return
		}
	}
	if os.Getenv("CRD_REQUIRE_TMUX") == "1" {
		t.Fatal("no tmux binary on PATH and CRD_REQUIRE_TMUX=1")
	}
	t.Skip("no tmux binary on PATH (set CRD_REQUIRE_TMUX=1 to make this a failure)")
}

// Tmux runs `tmux -f /dev/null -S <sock> args...` for a socket under the test root (D-H24).
func Tmux(t testing.TB, sock string, args ...string) (string, error) {
	t.Helper()
	if !Under(sock, pkgRoot) {
		t.Fatalf("testenv: tmux socket %s outside the test root", sock)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cmd := Command(t, nil, "tmux", append([]string{"-f", "/dev/null", "-S", sock}, args...)...)
	var out bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &out
	if err := cmd.Start(); err != nil {
		return "", err
	}
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		return out.String(), err
	case <-ctx.Done():
		Signal(t, cmd.Process.Pid, syscall.SIGKILL)
		<-done
		return out.String(), ctx.Err()
	}
}

// StartTmux starts a private tmux server on sock with one session per name, each running `sleep 3600` (never a
// shell), and registers its kill-server as a cleanup.
func StartTmux(t testing.TB, sock string, names ...string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(sock), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { KillTmux(t, sock) })
	for _, n := range names {
		if out, err := Tmux(t, sock, "new-session", "-d", "-s", n, "-x", "200", "-y", "50", "sleep 3600"); err != nil {
			t.Fatalf("private tmux new-session %s: %v %s", n, err, out)
		}
	}
}

// NewTmuxSession adds a session to a running private server.
func NewTmuxSession(t testing.TB, sock, name string) {
	t.Helper()
	if out, err := Tmux(t, sock, "new-session", "-d", "-s", name, "sleep 3600"); err != nil {
		t.Fatalf("private tmux new-session %s: %v %s", name, err, out)
	}
}

// NewTmuxSessionArgv0 adds a session whose pane runs `sleep 3600` under the given argv[0] (a hostile process name:
// long, with newlines, tabs or separators). The name reaches bash through a file under the test root, never as a
// shell word; the pane is still sleep, never an interactive shell (D-H24).
func NewTmuxSessionArgv0(t testing.TB, sock, name, argv0 string) {
	t.Helper()
	f, err := os.CreateTemp(filepath.Dir(sock), "argv0-")
	if err != nil {
		t.Fatal(err)
	}
	_, werr := f.WriteString(argv0)
	if cerr := f.Close(); werr != nil || cerr != nil {
		t.Fatal("argv0 file")
	}
	cmd := `exec bash -c 'exec -a "$(cat ` + f.Name() + `)" sleep 3600'`
	if out, err := Tmux(t, sock, "new-session", "-d", "-s", name, cmd); err != nil {
		t.Fatalf("private tmux new-session %s: %v %s", name, err, out)
	}
}

// KillTmuxSession removes a session of a private server.
func KillTmuxSession(t testing.TB, sock, name string) {
	t.Helper()
	if out, err := Tmux(t, sock, "kill-session", "-t", "="+name); err != nil {
		t.Fatalf("private tmux kill-session %s: %v %s", name, err, out)
	}
}

// KillTmux stops a private server (by its socket, never by pid or name).
func KillTmux(t testing.TB, sock string) {
	t.Helper()
	_, _ = Tmux(t, sock, "kill-server")
}

// TmuxPid is the pid of the private server, asked through its own socket (D-H5), 0 when none answers.
func TmuxPid(t testing.TB, sock string) int {
	t.Helper()
	out, err := Tmux(t, sock, "display-message", "-p", "#{pid}")
	if err != nil {
		return 0
	}
	n, _ := strconv.Atoi(strings.TrimSpace(out))
	return n
}

// Signal sends sig to pid only when the process carries this run's tag or the test root in its command line (D-H5).
func Signal(t testing.TB, pid int, sig syscall.Signal) {
	t.Helper()
	// A child that has not exec'd yet still shows the parent's start-time environment: wait for it briefly.
	for i := 0; !ours(pid); i++ {
		if i == 50 {
			t.Fatalf("testenv: pid %d does not carry this run's tag; no signal sent", pid)
		}
		time.Sleep(20 * time.Millisecond)
	}
	_ = syscall.Kill(pid, sig)
}

func ours(pid int) bool {
	if pid <= 1 || pid == os.Getpid() {
		return false
	}
	env, err := os.ReadFile(fmt.Sprintf("/proc/%d/environ", pid))
	if err == nil && tag != "" && bytes.Contains(append([]byte{0}, env...), []byte("\x00CRD_TEST_TAG="+tag+"\x00")) {
		return true
	}
	cmdline, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
	return err == nil && pkgRoot != "" && bytes.Contains(cmdline, []byte(pkgRoot))
}

// sweep kills every process of this uid that carries this run's tag and reports them (D-H6).
func sweep() []string {
	var found []string
	ents, _ := os.ReadDir("/proc")
	for _, e := range ents {
		pid, err := strconv.Atoi(e.Name())
		if err != nil || pid == os.Getpid() {
			continue
		}
		env, err := os.ReadFile(fmt.Sprintf("/proc/%d/environ", pid))
		if err != nil || !bytes.Contains(append([]byte{0}, env...), []byte("\x00CRD_TEST_TAG="+tag+"\x00")) {
			continue
		}
		cmdline, _ := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
		found = append(found, fmt.Sprintf("%d %s", pid, strings.ReplaceAll(string(cmdline), "\x00", " ")))
		_ = syscall.Kill(pid, syscall.SIGKILL)
	}
	return found
}

// Orphans lists live processes with this run's tag except the ones in keep (for tests that check "no crd child").
func Orphans(keep ...int) []int {
	var out []int
	ents, _ := os.ReadDir("/proc")
next:
	for _, e := range ents {
		pid, err := strconv.Atoi(e.Name())
		if err != nil || pid == os.Getpid() {
			continue
		}
		for _, k := range keep {
			if k == pid {
				continue next
			}
		}
		env, err := os.ReadFile(fmt.Sprintf("/proc/%d/environ", pid))
		if err == nil && bytes.Contains(append([]byte{0}, env...), []byte("\x00CRD_TEST_TAG="+tag+"\x00")) {
			if st, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid)); err == nil && procState(st) != 'Z' {
				out = append(out, pid)
			}
		}
	}
	return out
}

func procState(stat []byte) byte {
	i := bytes.LastIndexByte(stat, ')')
	if i < 0 || i+2 >= len(stat) {
		return 0
	}
	return stat[i+2]
}

// SafeRemoveAll removes path only when it lies under the package root (or is it) and no ancestor below the base is
// a symlink: Go's RemoveAll does not follow links inside the tree but opens every ancestor of the path through
// them (os/removeall_at.go), so a linked ancestor would make it delete outside (D-H10).
func SafeRemoveAll(path string) error {
	if pkgRoot == "" {
		return fmt.Errorf("no test root")
	}
	base := filepath.Dir(pkgRoot)
	clean := filepath.Clean(path)
	if clean != pkgRoot && !strings.HasPrefix(clean, pkgRoot+"/") {
		return fmt.Errorf("refusing to remove %s outside the test root", path)
	}
	rel, _ := filepath.Rel(base, filepath.Dir(clean))
	cur := base
	for _, c := range strings.Split(rel, "/") {
		if c == "." || c == "" {
			continue
		}
		cur = filepath.Join(cur, c)
		st, err := os.Lstat(cur)
		if err != nil {
			return err
		}
		if st.Mode()&fs.ModeSymlink != 0 {
			return fmt.Errorf("refusing to remove %s: ancestor %s is a link", path, cur)
		}
	}
	return os.RemoveAll(clean)
}

// SafeSymlink creates link -> target only when the target resolves under the package root (D-H10).
func SafeSymlink(t testing.TB, target, link string) {
	t.Helper()
	abs := target
	if !filepath.IsAbs(abs) {
		abs = filepath.Join(filepath.Dir(link), target)
	}
	if !Under(abs, pkgRoot) || !Under(link, pkgRoot) {
		t.Fatalf("testenv: link %s -> %s leaves the test root", link, target)
	}
	if err := os.Symlink(target, link); err != nil {
		t.Fatal(err)
	}
}

func walkLinks(root string) []string {
	var bad []string
	_ = filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.Type()&fs.ModeSymlink == 0 {
			return nil
		}
		tgt, err := os.Readlink(p)
		if err != nil {
			return nil
		}
		if !filepath.IsAbs(tgt) {
			tgt = filepath.Join(filepath.Dir(p), tgt)
		}
		if r, err := evalExisting(tgt); err != nil || !(r == root || strings.HasPrefix(r, root+"/")) {
			bad = append(bad, p+" -> "+tgt)
		}
		return nil
	})
	return bad
}

// RecordingTmux writes a test-only `tmux` into dir that records every call (argv, and whether TMUX/TMUX_PANE
// reached it) in dir/calls, fails while dir/fail exists, blocks list-panes on the FIFO dir/hold while it exists,
// and otherwise execs the repo shim. It returns the PATH entry to put first (dodatek T4).
func RecordingTmux(t testing.TB, dir string) string {
	t.Helper()
	if !Under(dir, pkgRoot) {
		t.Fatalf("testenv: recorder dir %s outside the test root", dir)
	}
	script := "#!/bin/sh\n" +
		"d='" + dir + "'\n" +
		"printf '%s\\n' \"$*\" >> \"$d/calls\"\n" +
		"[ -n \"${TMUX+x}\" ] && echo TMUX-SET >> \"$d/calls\"\n" +
		"[ -n \"${TMUX_PANE+x}\" ] && echo TMUX_PANE-SET >> \"$d/calls\"\n" +
		"[ -e \"$d/fail\" ] && exit 1\n" +
		"case \"$*\" in *list-panes*) [ -p \"$d/hold\" ] && read x < \"$d/hold\" ;; esac\n" +
		// the shim looks for the real binary on PATH: without the recorder's own dir, or it would find this script
		"PATH=${PATH#\"$d\":}; export PATH\n" +
		"exec '" + filepath.Join(shimDir, "tmux") + "' \"$@\"\n"
	if err := os.WriteFile(filepath.Join(dir, "tmux"), []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	return "PATH=" + dir + ":" + os.Getenv("PATH")
}

// TmuxCalls reads the recorder's log.
func TmuxCalls(dir string) []string {
	b, err := os.ReadFile(filepath.Join(dir, "calls"))
	if err != nil {
		return nil
	}
	s := strings.TrimRight(string(b), "\n")
	if s == "" {
		return nil
	}
	return strings.Split(s, "\n")
}
