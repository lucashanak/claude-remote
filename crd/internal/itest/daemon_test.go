package itest

import (
	"bytes"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/testenv"
)

// R5 + D-H1c: outside systemd and without CRD_TEST_ROOT, serve refuses with exit 1 and leaves HOME empty. The
// production start itself is never tested on the host.
func TestServeRefusesOutsideSystemdWithoutTouchingHome(t *testing.T) {
	r := testenv.NewRoot(t)
	for _, extra := range [][]string{nil, {"INVOCATION_ID=0123", "NOTIFY_SOCKET=" + filepath.Join(r.Dir, "nope")}} {
		cmd := testenv.Command(t, append([]string{"HOME=" + r.Home, "TMUX_TMPDIR=" + r.TmuxTmp, "CRD_TEST_EXEC=1"}, extra...), self(t), "serve")
		var stderr bytes.Buffer
		cmd.Stderr = &stderr
		err := cmd.Run()
		if ee, ok := err.(interface{ ExitCode() int }); !ok || ee.ExitCode() != 1 {
			t.Fatalf("exit %v, want 1", err)
		}
		if !strings.Contains(stderr.String(), "refusing to start") {
			t.Fatalf("stderr %q", stderr.String())
		}
		if ents, _ := os.ReadDir(r.Home); len(ents) != 0 {
			t.Fatalf("HOME not empty after a refusal: %v", ents)
		}
	}
}

// The other modes create nothing either (D-H21): the shim with no daemon answers E_DAEMON_DOWN and exits 0.
func TestShimWithoutDaemonAnswersDaemonDown(t *testing.T) {
	r := testenv.NewRoot(t)
	cmd := crdCmd(t, r, nil, "--stdio")
	cmd.Stdin = strings.NewReader("")
	out, err := cmd.Output()
	if err != nil {
		t.Fatalf("shim: %v", err)
	}
	_, frames, derr := crp.DecodeStream(out, crp.S2C, crp.DefaultMaxFrame, false, false)
	if derr != nil || len(frames) != 1 || frames[0].Header["code"] != crp.EDaemonDown {
		t.Fatalf("shim output %x (%v)", out, derr)
	}
	if ents, _ := os.ReadDir(r.Home); len(ents) != 0 {
		t.Fatalf("shim created %v", ents)
	}
	sc := crdCmd(t, r, nil, "selftest")
	if b, err := sc.CombinedOutput(); err != nil || !strings.Contains(string(b), "selftest ok") {
		t.Fatalf("selftest: %v %s", err, b)
	}
	if ents, _ := os.ReadDir(r.Home); len(ents) != 0 {
		t.Fatalf("selftest created %v", ents)
	}
}

func TestHandshakePingAndPermissions(t *testing.T) {
	r := testenv.NewRoot(t)
	d := startDaemon(t, r)
	c := dial(t, r, hello("android-1", link1, 1, nil))
	if c.c.First.Type != crp.WELCOME {
		t.Fatalf("first frame %s", c.c.First.Type.Name())
	}
	for _, f := range featsOf(c.c.First) {
		if f == "term" || f == "in" || f == "rpc.exec" {
			t.Fatalf("offered %s", f)
		}
	}
	if _, ok := c.c.First.Header["inHi"]; ok {
		t.Fatal("inHi sent")
	}
	ig, _ := crp.Str(c.c.First.Header, "inGen")
	ep := c.c.First.Header["conn"]
	if len(ig) != 8 || ep == nil {
		t.Fatalf("WELCOME %v", c.c.First.Header)
	}
	c.send(crp.PING, crp.Obj{"t": int64(-7)}, nil)
	p := c.expect(crp.PONG, 2*time.Second)
	if tt, _ := crp.Int(p.Header, "t"); tt != -7 {
		t.Fatal("PONG t")
	}
	st, _ := os.Stat(filepath.Dir(r.Sock()))
	ss, _ := os.Stat(r.Sock())
	if st.Mode().Perm() != 0o700 || ss.Mode().Perm() != 0o600 {
		t.Fatalf("run dir %v, socket %v", st.Mode(), ss.Mode())
	}
	// §8.4.5: the daemon process has no TMUX in its environment
	env, _ := os.ReadFile("/proc/" + itoa(d.pid()) + "/environ")
	if bytes.Contains(env, []byte("\x00TMUX=")) || bytes.HasPrefix(env, []byte("TMUX=")) {
		t.Fatal("crd runs with TMUX")
	}
}

func itoa(n int) string { return strconv.Itoa(n) }

// A4: a frame sent right behind HELLO in the same write is processed after WELCOME, not dropped.
func TestPipelinedFrameAfterHello(t *testing.T) {
	r := testenv.NewRoot(t)
	startDaemon(t, r)
	nc := dialRaw(t, r)
	buf := append([]byte(crp.Magic), crp.MustEncode(crp.NewFrame(crp.HELLO, hello("d1", link1, 1, nil), nil))...)
	buf = append(buf, crp.MustEncode(crp.NewFrame(crp.PING, crp.Obj{"t": int64(5)}, nil))...)
	if _, err := nc.Write(buf); err != nil {
		t.Fatal(err)
	}
	_ = nc.SetReadDeadline(time.Now().Add(3 * time.Second))
	raw := make([]byte, 0, 4096)
	tmp := make([]byte, 4096)
	for {
		n, err := nc.Read(tmp)
		raw = append(raw, tmp[:n]...)
		if _, fs, derr := crp.DecodeStream(raw, crp.S2C, crp.DefaultMaxFrame, false, false); derr == nil && len(fs) >= 2 {
			if fs[0].Type != crp.WELCOME || fs[1].Type != crp.PONG {
				t.Fatalf("order %s %s", fs[0].Type.Name(), fs[1].Type.Name())
			}
			return
		}
		if err != nil {
			t.Fatalf("read: %v (%x)", err, raw)
		}
	}
}

// spec §2.5 (vectors 24, 31, 32) on the real daemon: higher att supersedes, equal att is STALE (mutant 8), another
// link coexists, and the superseded connection delivers nothing more (mutant 9).
func TestSupersedeStaleAndTwoLinks(t *testing.T) {
	r := testenv.NewRoot(t)
	startDaemon(t, r)
	a := dial(t, r, hello("dev-p", link1, 5, nil))
	other := dial(t, r, hello("dev-p", link2, 1, nil))
	b := dial(t, r, hello("dev-p", link1, 6, nil))
	if b.c.First.Type != crp.WELCOME {
		t.Fatal("higher att refused")
	}
	a.expectBye(crp.Superseded, 2*time.Second)
	// after the BYE nothing else arrives on A, and A is closed
	if f, err := a.next(500 * time.Millisecond); err == nil {
		t.Fatalf("superseded connection still delivers %s", f.Type.Name())
	}
	eq := dial(t, r, hello("dev-p", link1, 6, nil))
	if eq.c.First.Type != crp.BYE || eq.c.First.Header["code"] != crp.Stale {
		t.Fatalf("equal att: %v", eq.c.First.Header)
	}
	low := dial(t, r, hello("dev-p", link1, 2, nil))
	if low.c.First.Header["code"] != crp.Stale {
		t.Fatal("lower att not stale")
	}
	// the winner and the other link are untouched
	for _, c := range []*tconn{b, other} {
		c.send(crp.PING, crp.Obj{"t": int64(1)}, nil)
		c.expect(crp.PONG, 2*time.Second)
	}
}

// dodatek R4: a frame of a feature B1a does not offer, or SUB of a stream not offered, is BYE E_PROTO + close.
func TestFeatureNotOfferedIsProtoError(t *testing.T) {
	r := testenv.NewRoot(t)
	startDaemon(t, r)
	cases := []struct {
		name string
		typ  crp.Type
		h    crp.Obj
		blob []byte
	}{
		{"IN", crp.IN, crp.Obj{"sid": "s", "iseq": int64(1), "exp": int64(1)}, []byte("x")},
		{"INSKIP", crp.INSKIP, crp.Obj{"sid": "s", "to": int64(1)}, nil},
		{"TSIZE", crp.TSIZE, crp.Obj{"sid": "s", "cols": int64(80), "rows": int64(24)}, nil},
		{"RPC", crp.RPC, crp.Obj{"id": int64(1), "m": "exec", "tag": "health", "cmd": "echo", "ms": int64(100)}, nil},
		{"SUB term", crp.SUB, crp.Obj{"st": "term", "sid": "s"}, nil},
		{"SUB unknown", crp.SUB, crp.Obj{"st": "zz"}, nil},
		{"UNSUB term", crp.UNSUB, crp.Obj{"st": "term", "sid": "s"}, nil},
		{"HELLO again", crp.HELLO, hello("x", link1, 1, nil), nil},
	}
	for i, tc := range cases {
		c := dial(t, r, hello("dev-r4", link1, int64(10+i), nil))
		c.send(tc.typ, tc.h, tc.blob)
		c.expectBye(crp.EProto, 2*time.Second)
		if _, err := c.next(time.Second); err == nil {
			t.Fatalf("%s: connection not closed", tc.name)
		}
	}
}

// spec §1.4, vector 44, R8: crd.off mid-connection ⇒ BYE E_OFF within 1 s; while it exists new connections and the
// shim get E_OFF; after removal the daemon serves again.
func TestCrdOffMidConnection(t *testing.T) {
	r := testenv.NewRoot(t)
	startDaemon(t, r)
	c := dial(t, r, hello("dev-off", link1, 1, nil))
	off := filepath.Join(r.Home, ".claude-remote", "crd.off")
	t0 := time.Now()
	if err := os.WriteFile(off, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	c.expectBye(crp.EOff, 1500*time.Millisecond)
	if el := time.Since(t0); el > 1200*time.Millisecond {
		t.Fatalf("BYE E_OFF after %v", el)
	}
	// Under crd.off the daemon answers right after the prelude and closes: the test reads that answer and never
	// depends on writing HELLO first (the EPIPE race of review T §4; rawAnswer ignores the write error).
	if n, err := rawAnswer(t, r, nil); err != nil || n.Type != crp.BYE || n.Header["code"] != crp.EOff {
		t.Fatalf("new connection under crd.off: %v %v", n.Header, err)
	}
	if n, err := rawAnswer(t, r, hello("dev-off", link1, 2, nil)); err != nil || n.Type != crp.BYE || n.Header["code"] != crp.EOff {
		t.Fatalf("new connection with HELLO under crd.off: %v %v", n.Header, err)
	}
	sh := crdCmd(t, r, nil, "--stdio")
	sh.Stdin = strings.NewReader("")
	out, _ := sh.Output()
	if _, fs, err := crp.DecodeStream(out, crp.S2C, crp.DefaultMaxFrame, false, false); err != nil || fs[0].Header["code"] != crp.EOff {
		t.Fatalf("shim under crd.off: %x", out)
	}
	if err := testenv.SafeRemoveAll(off); err != nil {
		t.Fatal(err)
	}
	att := int64(10)
	waitFor(t, 3*time.Second, "WELCOME after crd.off removal", func() bool {
		att++
		f, err := rawAnswer(t, r, hello("dev-off", link1, att, nil))
		return err == nil && f.Type == crp.WELCOME
	})
}

// spec §7.2: SIGTERM ⇒ BYE{E_SHUTDOWN, retryMs:3000} on every connection, then exit 0.
func TestSigtermGracefulStop(t *testing.T) {
	r := testenv.NewRoot(t)
	d := startDaemon(t, r)
	a := dial(t, r, hello("dev-t", link1, 1, nil))
	b := dial(t, r, hello("dev-t", link2, 1, nil))
	d.signal(syscall.SIGTERM)
	for _, c := range []*tconn{a, b} {
		f := c.expectBye(crp.EShutdown, 3*time.Second)
		if ms, _ := crp.Int(f.Header, "retryMs"); ms != 3000 {
			t.Fatalf("retryMs %d", ms)
		}
	}
	if code := d.wait(5 * time.Second); code != 0 {
		t.Fatalf("exit %d", code)
	}
	if _, err := os.Lstat(r.Sock()); !os.IsNotExist(err) {
		t.Fatal("socket left after a graceful stop")
	}
}

// spec §7.4: a second serve on the same home exits 0 and the first keeps serving; a stale socket is unlinked.
func TestSingletonAndStaleSocket(t *testing.T) {
	r := testenv.NewRoot(t)
	if err := os.MkdirAll(filepath.Dir(r.Sock()), 0o700); err != nil {
		t.Fatal(err)
	}
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: r.Sock(), Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	ln.SetUnlinkOnClose(false)
	ln.Close() // a socket file without a listener: stale
	startDaemon(t, r)
	second := crdCmd(t, r, nil, "serve")
	if err := second.Run(); err != nil {
		t.Fatalf("second instance: %v", err)
	}
	c := dial(t, r, hello("dev-s", link1, 1, nil))
	if c.c.First.Type != crp.WELCOME {
		t.Fatal("first instance stopped serving")
	}
}

// spec §2.4: no frame for max(floor, 3 × pingMs) closes the connection (floor shortened in test mode only).
func TestIdleConnectionIsClosed(t *testing.T) {
	r := testenv.NewRoot(t)
	startDaemon(t, r, "CRD_TEST_MIN_IDLE_MS=300")
	c := dial(t, r, hello("dev-i", link1, 1, nil))
	c.send(crp.STATE, crp.Obj{"fg": false, "pingMs": int64(50)}, nil)
	t0 := time.Now()
	_, err := c.next(3 * time.Second)
	if err == nil || time.Since(t0) > 2*time.Second {
		t.Fatalf("idle connection not closed (%v after %v)", err, time.Since(t0))
	}
}

// A connection without the shim prelude is closed without the magic.
func TestPreludeRequired(t *testing.T) {
	r := testenv.NewRoot(t)
	startDaemon(t, r)
	nc, err := net.Dial("unix", r.Sock())
	if err != nil {
		t.Fatal(err)
	}
	defer nc.Close()
	_, _ = nc.Write([]byte(crp.Magic))
	_ = nc.SetReadDeadline(time.Now().Add(7 * time.Second))
	b, _ := io.ReadAll(nc)
	if len(b) != 0 {
		t.Fatalf("got %x without a prelude", b)
	}
}

// spec §7.1 sd_notify by hand, D-H1a: CRD_TEST_ROOT wins over the systemd variables; READY=1 and WATCHDOG=1
// arrive on NOTIFY_SOCKET in the test root.
func TestSdNotifyReadyAndWatchdogInTestMode(t *testing.T) {
	r := testenv.NewRoot(t)
	ns := filepath.Join(r.Dir, "notify")
	pc, err := net.ListenUnixgram("unixgram", &net.UnixAddr{Name: ns, Net: "unixgram"})
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	d := startDaemon(t, r, "NOTIFY_SOCKET="+ns, "INVOCATION_ID=deadbeef", "WATCHDOG_USEC=400000")
	got := map[string]bool{}
	buf := make([]byte, 256)
	_ = pc.SetReadDeadline(time.Now().Add(5 * time.Second))
	for !(got["READY=1"] && got["WATCHDOG=1"]) {
		n, err := pc.Read(buf)
		if err != nil {
			t.Fatalf("notify: %v (got %v)", err, got)
		}
		got[string(buf[:n])] = true
	}
	if !strings.Contains(d.log(), "test=true") {
		t.Fatal("daemon did not run in test mode")
	}
}

func TestStatsLineInLog(t *testing.T) {
	r := testenv.NewRoot(t)
	d := startDaemon(t, r, "CRD_TEST_STATS_MS=100")
	dial(t, r, hello("dev-st", link1, 1, nil))
	waitFor(t, 3*time.Second, "stats line", func() bool { return strings.Contains(d.log(), "crd stats conns=1 devs=1") })
	for _, l := range strings.Split(d.log(), "\n") {
		if strings.HasPrefix(l, "crd stats") && (strings.Contains(l, "rpc") || strings.Contains(l, "in_") || strings.Contains(l, "attaches")) {
			t.Fatalf("field B1a does not have: %q", l)
		}
	}
}

// The probe runs `crd --stdio` as its channel and prints the WELCOME.
func TestProbeThroughShim(t *testing.T) {
	r := testenv.NewRoot(t)
	startDaemon(t, r)
	cmd := crdCmd(t, r, nil, "probe", "-sub", "", "-for", "500ms")
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		t.Fatalf("probe: %v %s %s", err, out, stderr.String())
	}
	if !strings.HasPrefix(string(out), "welcome {") {
		t.Fatalf("probe output %q", out)
	}
}
