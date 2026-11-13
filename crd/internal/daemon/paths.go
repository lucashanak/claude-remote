// Package daemon is crd's core (spec §1.4, §2, §7): modes and paths, the unix socket, the handshake, the
// connection table, the priority writer, liveness, crd.off, graceful stop, sd_notify, logs and stats. Durable
// streams (ev, sess, tx) plug in through Register (stream.go); without them the daemon offers no stream feature.
package daemon

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// Paths are every location the daemon touches, derived from one home (D-H4). In test mode all of them lie under
// CRD_TEST_ROOT, including the legacy marker (zadání: $CRD_TEST_ROOT/tmp/claude-notify).
type Paths struct {
	Test     bool
	Root     string // CRD_TEST_ROOT after EvalSymlinks (test mode only)
	Home     string
	CR       string // ~/.claude-remote
	CRDir    string // ~/.claude-remote/crd
	State    string
	Run      string
	Lock     string
	Sock     string
	Log      string
	Off      string // ~/.claude-remote/crd.off
	Cache    string // ~/.cache/claude-remote
	Events   string
	Notify   string
	Rotated  string // events.rotated
	RotLock  string // rotate.lock
	Legacy   string // /tmp/claude-notify
	Forget   string // ~/.claude-remote/forget.log
	Jarvis   string // ~/.cache/claude-remote/jarvis-alerts
	Projects string // ~/.claude/projects
	TmuxSock string // guarded tmux socket (spec §6.3)
}

// MaxSockPath keeps sun_path (108) safe in deep temp dirs (spec §7.1).
const MaxSockPath = 100

var cgroupUnitRe = regexp.MustCompile(`^claude-remote-crd[^/]*\.service$`)

// ErrRefuse is a refusal to start; the caller prints it to stderr and exits 1 before touching the file system.
type ErrRefuse struct{ Why string }

func (e *ErrRefuse) Error() string { return "crd: refusing to start: " + e.Why }

// ResolveServe decides the mode of `crd serve` (D-H1a/b) and derives its paths. CRD_TEST_ROOT set ⇒ test mode,
// whatever else is set; a failed validation is a refusal, never a fall-through to production. Production needs
// the unit's own markers: NOTIFY_SOCKET ∧ INVOCATION_ID (spec §7.1) ∧ SYSTEMD_EXEC_PID = own pid ∧ the last
// cgroup component claude-remote-crd*.service ∧ no TMUX. INVOCATION_ID alone is inherited by every live tmux
// session on the box (dodatek, H1).
func ResolveServe(getenv func(string) string) (Paths, error) {
	if getenv("CRD_TEST_ROOT") != "" {
		return resolveTest(getenv)
	}
	switch {
	case getenv("NOTIFY_SOCKET") == "" || getenv("INVOCATION_ID") == "":
		return Paths{}, &ErrRefuse{"not under systemd (NOTIFY_SOCKET and INVOCATION_ID) and not in test mode (CRD_TEST_ROOT)"}
	case getenv("TMUX") != "":
		return Paths{}, &ErrRefuse{"TMUX is set: this is a tmux session, not the unit"}
	case getenv("SYSTEMD_EXEC_PID") != strconv.Itoa(os.Getpid()):
		return Paths{}, &ErrRefuse{"SYSTEMD_EXEC_PID is not this process"}
	}
	unit, err := ownCgroupUnit()
	if err != nil || !cgroupUnitRe.MatchString(unit) {
		return Paths{}, &ErrRefuse{fmt.Sprintf("cgroup %q is not a claude-remote-crd service", unit)}
	}
	return resolveProd(getenv)
}

// ResolveClient is for the modes that never start the daemon (--stdio, probe, selftest): the same test-mode
// validation, else HOME must be the passwd home. They never create anything under it (D-H21).
func ResolveClient(getenv func(string) string) (Paths, error) {
	if getenv("CRD_TEST_ROOT") != "" {
		return resolveTest(getenv)
	}
	return resolveProd(getenv)
}

func resolveProd(getenv func(string) string) (Paths, error) {
	home := getenv("HOME")
	if home == "" {
		return Paths{}, &ErrRefuse{"HOME is not set"}
	}
	ph, err := passwdHome()
	if err != nil {
		return Paths{}, &ErrRefuse{err.Error()}
	}
	rh, err1 := filepath.EvalSymlinks(home)
	rp, err2 := filepath.EvalSymlinks(ph)
	if err1 != nil || err2 != nil || rh != rp {
		return Paths{}, &ErrRefuse{fmt.Sprintf("HOME %q is not the passwd home %q", home, ph)}
	}
	tt := getenv("TMUX_TMPDIR")
	if tt == "" {
		tt = "/tmp"
	}
	rt, err := filepath.EvalSymlinks(tt)
	if err != nil {
		rt = filepath.Clean(tt)
	}
	p := derive(rh, "/tmp/claude-notify", filepath.Join(rt, "tmux-"+strconv.Itoa(os.Getuid()), "default"))
	return p, nil
}

func resolveTest(getenv func(string) string) (Paths, error) {
	root := getenv("CRD_TEST_ROOT")
	rr, err := filepath.EvalSymlinks(root)
	if err != nil {
		return Paths{}, &ErrRefuse{"CRD_TEST_ROOT: " + err.Error()}
	}
	if st, err := os.Stat(rr); err != nil || !st.IsDir() || rr == "/" {
		return Paths{}, &ErrRefuse{"CRD_TEST_ROOT is not a directory below /"}
	}
	if st, err := os.Lstat(filepath.Join(rr, ".crd-test-root")); err != nil || !st.Mode().IsRegular() {
		return Paths{}, &ErrRefuse{"CRD_TEST_ROOT has no .crd-test-root marker"}
	}
	rh, err := filepath.EvalSymlinks(getenv("HOME"))
	if err != nil || !strictlyUnder(rh, rr) {
		return Paths{}, &ErrRefuse{"test mode: HOME is not strictly under CRD_TEST_ROOT"}
	}
	// fail-closed: without the passwd home the "HOME is not the real home" check cannot be made (review K-F8)
	ph, err := passwdHome()
	if err != nil {
		return Paths{}, &ErrRefuse{"test mode: " + err.Error()}
	}
	if rp, err := filepath.EvalSymlinks(ph); err != nil || rp == rh {
		return Paths{}, &ErrRefuse{"test mode: HOME is the real home (or the passwd home does not resolve)"}
	}
	tt := getenv("TMUX_TMPDIR")
	rt, err := filepath.EvalSymlinks(tt)
	if tt == "" || err != nil || !strictlyUnder(rt, rr) {
		return Paths{}, &ErrRefuse{"test mode: TMUX_TMPDIR is not strictly under CRD_TEST_ROOT"}
	}
	uid := strconv.Itoa(os.Getuid())
	guarded := filepath.Join(rt, "tmux-"+uid, "default")
	liveTmp, err := filepath.EvalSymlinks("/tmp")
	if err != nil {
		liveTmp = "/tmp"
	}
	if guarded == filepath.Join(liveTmp, "tmux-"+uid, "default") || guarded == filepath.Join("/tmp", "tmux-"+uid, "default") {
		return Paths{}, &ErrRefuse{"test mode: the guarded socket is the live one"}
	}
	p := derive(rh, filepath.Join(rr, "tmp", "claude-notify"), guarded)
	p.Test, p.Root = true, rr
	if err := p.allUnderRoot(); err != nil {
		return Paths{}, err
	}
	if len(p.Sock) > MaxSockPath || len(p.TmuxSock) > MaxSockPath {
		return Paths{}, &ErrRefuse{fmt.Sprintf("test mode: socket path over %d bytes", MaxSockPath)}
	}
	return p, nil
}

// All is every path of p but Root, in field order (TestAllCoversEveryPath keeps it complete).
func (p Paths) All() []string {
	return []string{p.Home, p.CR, p.CRDir, p.State, p.Run, p.Lock, p.Sock, p.Log, p.Off, p.Cache, p.Events, p.Notify,
		p.Rotated, p.RotLock, p.Legacy, p.Forget, p.Jarvis, p.Projects, p.TmuxSock}
}

// allUnderRoot is D-H3 for every derived path (review A-F17): each one, with the existing part of it resolved,
// lies strictly under the test root, so a symlink inside the test HOME cannot lead a write or a read outside it.
// The files themselves are opened with O_NOFOLLOW (log, lock, sources, events.rotated); new ones are O_EXCL.
func (p Paths) allUnderRoot() error {
	for _, x := range p.All() {
		if !strictlyUnder(x, p.Root) {
			return &ErrRefuse{"test mode: " + x + " is not under CRD_TEST_ROOT"}
		}
	}
	return nil
}

func derive(home, legacy, tmuxSock string) Paths {
	cr := filepath.Join(home, ".claude-remote")
	crd := filepath.Join(cr, "crd")
	cache := filepath.Join(home, ".cache", "claude-remote")
	return Paths{
		Home: home, CR: cr, CRDir: crd,
		State: filepath.Join(crd, "state"), Run: filepath.Join(crd, "run"),
		Lock: filepath.Join(crd, "state", "crd.lock"), Sock: filepath.Join(crd, "run", "crd.sock"),
		Log: filepath.Join(crd, "crd.log"), Off: filepath.Join(cr, "crd.off"),
		Cache: cache, Events: filepath.Join(cache, "events"), Notify: filepath.Join(cache, "notify"),
		Rotated: filepath.Join(cache, "events.rotated"), RotLock: filepath.Join(cache, "rotate.lock"),
		Legacy: legacy, Forget: filepath.Join(cr, "forget.log"), Jarvis: filepath.Join(cache, "jarvis-alerts"),
		Projects: filepath.Join(home, ".claude", "projects"), TmuxSock: tmuxSock,
	}
}

// strictlyUnder compares by path component after the caller resolved dir; p may not exist yet (its existing
// prefix is resolved).
func strictlyUnder(p, dir string) bool {
	rp := evalExisting(p)
	rel, err := filepath.Rel(dir, rp)
	return err == nil && rel != "." && rel != ".." && !strings.HasPrefix(rel, "../") && !filepath.IsAbs(rel)
}

func evalExisting(p string) string {
	p = filepath.Clean(p)
	rest := ""
	for {
		if r, err := filepath.EvalSymlinks(p); err == nil {
			return filepath.Join(r, rest)
		}
		parent := filepath.Dir(p)
		if parent == p {
			return filepath.Join(p, rest)
		}
		rest = filepath.Join(filepath.Base(p), rest)
		p = parent
	}
}

// UnderRoot is the second, independent check of D-H3: in test mode every path the gate or the guard touches
// must lie under the test root.
func (p Paths) UnderRoot(x string) bool {
	return !p.Test || strictlyUnder(x, p.Root)
}

// passwdFile is /etc/passwd (a test points it elsewhere to prove the fail-closed paths).
var passwdFile = "/etc/passwd"

func passwdHome() (string, error) {
	f, err := os.Open(passwdFile)
	if err != nil {
		return "", err
	}
	defer f.Close()
	uid := strconv.Itoa(os.Getuid())
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		x := strings.Split(sc.Text(), ":")
		if len(x) >= 6 && x[2] == uid {
			return x[5], nil
		}
	}
	return "", errors.New("uid not in /etc/passwd")
}

func ownCgroupUnit() (string, error) {
	b, err := os.ReadFile("/proc/self/cgroup")
	if err != nil {
		return "", err
	}
	for _, line := range strings.Split(strings.TrimSpace(string(b)), "\n") {
		if strings.HasPrefix(line, "0::") {
			return filepath.Base(strings.TrimPrefix(line, "0::")), nil
		}
	}
	return "", errors.New("no cgroup v2 line")
}
