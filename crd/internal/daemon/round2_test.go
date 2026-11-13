package daemon

// Round 2 (dodatek 2 po review): the send path never panics, the queue is bounded, BYE and pingMs are bounded, the
// production gate is decided by the unit's own markers, and test mode checks every path.

import (
	"bufio"
	"errors"
	"net"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/testenv"
)

// P1.1: a frame whose header is over 65535 bytes, or whose body is over the negotiated maxFrame, is refused with an
// error, never a panic, and nothing is queued.
func TestWriterRefusesOversizeWithoutPanic(t *testing.T) {
	w := newWriter(&recW{}, crp.MinMaxFrame, func() {})
	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("send panicked: %v", r)
		}
	}()
	big := crp.NewFrame(crp.TX, crp.Obj{"wid": strings.Repeat("w", 70000), "u": "u", "f": int64(0), "o": int64(1), "enc": "none"}, []byte("x"))
	var pe *crp.ProtoError
	if err := w.send(big); !errors.As(err, &pe) || pe.Code != crp.EFrame {
		t.Fatalf("header over 65535: %v", err)
	}
	blob := crp.NewFrame(crp.EV, crp.Obj{"st": "ev", "ep": "e", "seq": int64(1)}, make([]byte, crp.MinMaxFrame))
	if err := w.send(blob); !errors.Is(err, errOverMaxFrame) {
		t.Fatalf("body over maxFrame: %v", err)
	}
	if w.pending() != 0 {
		t.Fatal("a refused frame was queued")
	}
	bye := crp.NewFrame(crp.BYE, crp.Obj{"code": "E_X", "msg": strings.Repeat("m", 70000)}, nil)
	w.terminate(&bye) // does not encode: close without BYE, no panic
	done := make(chan struct{})
	w.onDone = func() { close(done) }
	go w.run()
	<-done
}

// P1.3: the queue holds at most MaxQueueFrames frames and MaxQueueBytes bytes; going over closes the connection at
// once (kill) and reports errOverflow exactly once.
func TestWriterQueueIsBounded(t *testing.T) {
	for _, tc := range []struct {
		name  string
		frame crp.Frame
		n     int
	}{
		{"frames", Pong(1, 2), MaxQueueFrames},
		{"bytes", crp.NewFrame(crp.EV, crp.Obj{"st": "ev", "ep": "e", "seq": int64(1)}, make([]byte, 60<<10)), 0},
	} {
		if tc.n == 0 {
			b, _ := crp.Encode(tc.frame)
			tc.n = MaxQueueBytes / len(b)
		}
		killed := 0
		w := newWriter(&recW{}, 1<<20, func() {})
		w.kill = func() { killed++ }
		for i := 0; i < tc.n; i++ {
			if err := w.send(tc.frame); err != nil {
				t.Fatalf("%s: send %d: %v", tc.name, i, err)
			}
		}
		if w.queued() > MaxQueueBytes || w.pending() > MaxQueueFrames {
			t.Fatalf("%s: queue %d bytes %d frames", tc.name, w.queued(), w.pending())
		}
		var over int
		for i := 0; i < 1000; i++ {
			if err := w.send(tc.frame); errors.Is(err, errOverflow) {
				over++
			}
		}
		if over != 1 || killed != 1 || w.pending() != 0 {
			t.Fatalf("%s: overflow reported %d times, kill %d, %d frames kept", tc.name, over, killed, w.pending())
		}
	}
}

// K-F9: after the client's EOF the queued frames still go out, then the writer closes; a close without BYE does
// not cut the drain short.
func TestWriterFinishDrainsTheQueue(t *testing.T) {
	out := &recW{}
	done := make(chan struct{})
	w := newWriter(out, 1<<20, func() { close(done) })
	w.send(Pong(1, 2))
	w.send(Pong(3, 4))
	w.finish()
	w.terminate(nil)
	go w.run()
	<-done
	a, _ := crp.Encode(Pong(1, 2))
	b, _ := crp.Encode(Pong(3, 4))
	if string(out.b.Bytes()) != string(a)+string(b) {
		t.Fatalf("drained %d bytes", out.b.Len())
	}
}

// P1.1: BYE.msg may quote client input; it is cut to MaxByeMsg at a UTF-8 boundary and always encodes.
func TestByeMessageIsBounded(t *testing.T) {
	f := Bye(crp.EProto, strings.Repeat("ž", 100000)+"\xff")
	b, err := crp.Encode(f)
	if err != nil {
		t.Fatalf("BYE does not encode: %v", err)
	}
	msg, _ := crp.Str(f.Header, "msg")
	if len(msg) > MaxByeMsg || !utf8.ValidString(msg) || len(b) > 512 {
		t.Fatalf("msg %d bytes, valid %v", len(msg), utf8.ValidString(msg))
	}
	if got := TruncUTF8("ab\xffcd", 10); got != "ab�cd" {
		t.Fatalf("%q", got)
	}
	if got := TruncUTF8("aaž", 3); got != "aa" {
		t.Fatalf("%q", got)
	}
}

// P1.3: pingMs above 300 000 is capped, so the idle limit is at most 15 minutes.
func TestPingMsIsCapped(t *testing.T) {
	if ClampPingMs(1<<53-1) != MaxPingMs || ClampPingMs(15000) != 15000 {
		t.Fatal("clamp")
	}
	if IdleLimit(ClampPingMs(1<<53-1), IdleFloor) != 15*time.Minute {
		t.Fatal("idle limit over 15 min")
	}
}

// T-R07 (D-H1b): each production marker decides on its own. HOME is the passwd home, so the home check never masks
// a missing marker; every refusal names the marker that refused it. Nothing is created under the real home.
func TestProductionMarkersDecideEachRefusal(t *testing.T) {
	realHome, err := passwdHome()
	if err != nil {
		t.Fatal(err)
	}
	crdDir := filepath.Join(realHome, ".claude-remote", "crd")
	before, beforeErr := os.Lstat(crdDir)
	unit := func() map[string]string {
		return map[string]string{"HOME": realHome, "INVOCATION_ID": "x", "NOTIFY_SOCKET": "/run/x",
			"SYSTEMD_EXEC_PID": strconv.Itoa(os.Getpid())}
	}
	cases := []struct {
		name string
		mod  func(m map[string]string)
		why  string
	}{
		{"no NOTIFY_SOCKET", func(m map[string]string) { delete(m, "NOTIFY_SOCKET") }, "not under systemd"},
		{"no INVOCATION_ID", func(m map[string]string) { delete(m, "INVOCATION_ID") }, "not under systemd"},
		{"TMUX", func(m map[string]string) { m["TMUX"] = "/tmp/x,1,0" }, "TMUX is set"},
		{"SYSTEMD_EXEC_PID other", func(m map[string]string) { m["SYSTEMD_EXEC_PID"] = "1" }, "SYSTEMD_EXEC_PID"},
		{"SYSTEMD_EXEC_PID unset", func(m map[string]string) { delete(m, "SYSTEMD_EXEC_PID") }, "SYSTEMD_EXEC_PID"},
		// every marker but the cgroup is the unit's: this test's cgroup is not claude-remote-crd*.service
		{"cgroup", func(map[string]string) {}, "is not a claude-remote-crd service"},
	}
	for _, tc := range cases {
		m := unit()
		tc.mod(m)
		_, err := ResolveServe(env(m))
		var re *ErrRefuse
		if !errors.As(err, &re) || !strings.Contains(re.Why, tc.why) {
			t.Errorf("%s: %v, want a refusal naming %q", tc.name, err, tc.why)
		}
	}
	after, afterErr := os.Lstat(crdDir)
	if (beforeErr == nil) != (afterErr == nil) || (beforeErr == nil && !after.ModTime().Equal(before.ModTime())) {
		t.Fatalf("%s changed during the test", crdDir)
	}
}

// T-R08: the marker alone refuses: a root whose HOME and TMUX_TMPDIR are valid but which has no .crd-test-root (or a
// directory/link of that name) is refused for the marker.
func TestTestRootWithoutMarkerIsRefused(t *testing.T) {
	r := testenv.NewRoot(t)
	sub := filepath.Join(r.Dir, "s")
	for _, d := range []string{sub, filepath.Join(sub, "h"), filepath.Join(sub, "t")} {
		if err := os.Mkdir(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	m := map[string]string{"CRD_TEST_ROOT": sub, "HOME": filepath.Join(sub, "h"), "TMUX_TMPDIR": filepath.Join(sub, "t")}
	marker := filepath.Join(sub, ".crd-test-root")
	for _, mk := range []func(){
		func() {},
		func() { _ = os.Mkdir(marker, 0o700) },
		func() {
			_ = testenv.SafeRemoveAll(marker)
			testenv.SafeSymlink(t, filepath.Join(r.Dir, ".crd-test-root"), marker)
		},
	} {
		mk()
		_, err := ResolveServe(env(m))
		var re *ErrRefuse
		if !errors.As(err, &re) || !strings.Contains(re.Why, ".crd-test-root") {
			t.Fatalf("refusal %v, want the marker", err)
		}
	}
	if err := testenv.SafeRemoveAll(marker); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(marker, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := ResolveServe(env(m)); err != nil {
		t.Fatalf("with the marker: %v", err)
	}
}

// P1.6: All lists every path field but Root (a new field cannot be forgotten).
func TestAllCoversEveryPath(t *testing.T) {
	var p Paths
	v := reflect.ValueOf(&p).Elem()
	n := 0
	for i := 0; i < v.NumField(); i++ {
		if f := v.Field(i); f.Kind() == reflect.String && v.Type().Field(i).Name != "Root" {
			f.SetString("/x/" + v.Type().Field(i).Name)
			n++
		}
	}
	got := map[string]bool{}
	for _, x := range p.All() {
		got[x] = true
	}
	if len(got) != n || n != 19 {
		t.Fatalf("All has %d of %d path fields", len(got), n)
	}
}

// P1.6 (A-F17, K-F8): in test mode every one of the 19 paths must lie under the root; one outside refuses.
func TestTestModeRefusesAnyPathOutsideTheRoot(t *testing.T) {
	r := testenv.NewRoot(t)
	p, err := ResolveServe(env(testEnv(r)))
	if err != nil {
		t.Fatal(err)
	}
	v := reflect.ValueOf(&p).Elem()
	for i := 0; i < v.NumField(); i++ {
		name := v.Type().Field(i).Name
		if v.Field(i).Kind() != reflect.String || name == "Root" {
			continue
		}
		q := p
		reflect.ValueOf(&q).Elem().Field(i).SetString(filepath.Join(filepath.Dir(r.Dir), "outside", name))
		if err := q.allUnderRoot(); err == nil {
			t.Errorf("%s outside the root accepted", name)
		}
	}
	if err := p.allUnderRoot(); err != nil {
		t.Fatal(err)
	}
}

// K-F8: without a readable passwd entry the "HOME is not the real home" check cannot be made: refuse.
func TestTestModeWithoutPasswdIsRefused(t *testing.T) {
	r := testenv.NewRoot(t)
	old := passwdFile
	passwdFile = filepath.Join(r.Dir, "missing")
	defer func() { passwdFile = old }()
	if _, err := ResolveServe(env(testEnv(r))); err == nil {
		t.Fatal("test mode without passwd accepted")
	}
}

// R24: dev and link of HELLO (spec §2.3).
func TestValidKey(t *testing.T) {
	for _, tc := range []struct {
		dev, link string
		ok        bool
	}{
		{"pixel-8.a_b", "5b1e2f0c9a7d3e41", true}, {strings.Repeat("d", 64), "0000000000000000", true},
		{strings.Repeat("d", 65), "5b1e2f0c9a7d3e41", false}, {"", "5b1e2f0c9a7d3e41", false},
		{"d d", "5b1e2f0c9a7d3e41", false}, {"d\n", "5b1e2f0c9a7d3e41", false},
		{"d", "5B1E2F0C9A7D3E41", false}, {"d", "5b1e2f0c9a7d3e4", false}, {"d", "5b1e2f0c9a7d3e410", false},
	} {
		if ValidKey(tc.dev, tc.link) != tc.ok {
			t.Errorf("ValidKey(%q, %q) != %v", tc.dev, tc.link, tc.ok)
		}
	}
}

// panicStream is an ev provider whose SUB panics (the backstop of P1.1 must contain it).
type panicStream struct{}

func (panicStream) Name() string    { return "ev" }
func (panicStream) Feats() []string { return []string{"ev"} }
func (panicStream) Info() (crp.Obj, bool) {
	return crp.Obj{"ep": "e", "first": int64(1), "last": int64(0)}, true
}
func (panicStream) Sub(Conn, crp.Obj) (string, error) { panic("provider bug") }
func (panicStream) Unsub(Conn, crp.Obj)               {}
func (panicStream) Ack(Conn, crp.Obj) error           { return nil }
func (panicStream) Gone(Conn)                         {}
func (panicStream) SetOff(bool)                       {}
func (panicStream) Stats(func(k, v string))           {}
func (panicStream) Heartbeat() time.Time              { return time.Now() }
func (panicStream) Start(<-chan struct{})             {}

func socketPair(t *testing.T) (*net.UnixConn, net.Conn) {
	t.Helper()
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_STREAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	conv := func(fd int) net.Conn {
		f := os.NewFile(uintptr(fd), "sp")
		c, err := net.FileConn(f)
		f.Close()
		if err != nil {
			t.Fatal(err)
		}
		return c
	}
	a, b := conv(fds[0]), conv(fds[1])
	t.Cleanup(func() { a.Close(); b.Close() })
	return a.(*net.UnixConn), b
}

func testServer(t *testing.T, st Stream) *Server {
	t.Helper()
	r := testenv.NewRoot(t)
	lg, err := OpenLog(filepath.Join(r.Dir, "crd.log"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(lg.Close)
	s := &Server{Log: lg, table: NewConnTable(), conns: map[string]*conn{}, hs: map[*net.UnixConn]bool{},
		byName: map[string]Stream{}, stop: make(chan struct{}), started: time.Now(), minIdle: IdleFloor, writeTO: WriteTimeout}
	if st != nil {
		s.streams, s.byName[st.Name()], s.feats = []Stream{st}, st, []string{st.Name()}
	}
	return s
}

func clientHello(t *testing.T, c net.Conn) {
	t.Helper()
	h := crp.Obj{"proto": []any{int64(1)}, "dev": "d", "link": "5b1e2f0c9a7d3e41", "att": int64(1), "ie": "0000000000000000",
		"app": "t", "feats": []any{"ev"}, "maxFrame": int64(crp.MinMaxFrame)}
	b, err := crp.Encode(crp.NewFrame(crp.HELLO, h, nil))
	if err != nil {
		t.Fatal(err)
	}
	pre := "crd-shim 1 {\"uid\":" + strconv.Itoa(os.Getuid()) + ",\"env\":{}}\n"
	if _, err := c.Write(append(append([]byte(pre), crp.Magic...), b...)); err != nil {
		t.Fatal(err)
	}
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := crp.SkipJunk(c); err != nil {
		t.Fatal(err)
	}
}

// P1.1 backstop: a panic in a connection's goroutine (here a provider's SUB) closes that connection, logs CRITICAL,
// and the process (this test binary) lives on.
func TestPanicInAConnectionClosesOnlyIt(t *testing.T) {
	s := testServer(t, panicStream{})
	srv, cl := socketPair(t)
	done := make(chan struct{})
	go func() { s.handle(srv); close(done) }()
	clientHello(t, cl)
	rd := crp.NewReader(cl, crp.S2C)
	if w, err := rd.Next(); err != nil || w.Type != crp.WELCOME {
		t.Fatalf("first frame %v %v", w.Type.Name(), err)
	}
	b, _ := crp.Encode(crp.NewFrame(crp.SUB, crp.Obj{"st": "ev"}, nil))
	if _, err := cl.Write(b); err != nil {
		t.Fatal(err)
	}
	<-done
	for {
		if _, err := rd.Next(); err != nil {
			break // closed
		}
	}
	if s.Conns() != 0 {
		t.Fatal("the panicked connection is still registered")
	}
	if b, _ := os.ReadFile(s.Log.path); !strings.Contains(string(b), "ev=CRITICAL panic=\"provider bug\"") {
		t.Fatalf("log %q", b)
	}
}

// P1.1: a frame the connection cannot carry (over its maxFrame) ends that connection with BYE E_FRAME.
func TestSendOverMaxFrameEndsTheConnectionWithEFrame(t *testing.T) {
	s := testServer(t, nil)
	out := &recW{}
	done := make(chan struct{})
	c := &conn{s: s, id: "c-1", done: make(chan struct{})}
	c.w = newWriter(out, crp.MinMaxFrame, func() { close(done) })
	c.Send(crp.NewFrame(crp.EV, crp.Obj{"st": "ev", "ep": "e", "seq": int64(1)}, make([]byte, crp.MinMaxFrame)))
	c.Send(Pong(1, 2)) // after the end: dropped
	c.w.terminate(nil) // no effect after the BYE above; without it the writer would only close
	go c.w.run()
	<-done
	_, fs, err := crp.DecodeStream(append([]byte(crp.Magic), out.b.Bytes()...), crp.S2C, crp.DefaultMaxFrame, false, false)
	if err != nil || len(fs) != 1 || fs[0].Type != crp.BYE || fs[0].Header["code"] != crp.EFrame || c.cause() != crp.EFrame {
		t.Fatalf("frames %v err %v cause %s", fs, err, c.cause())
	}
}

// T-R25: the prelude must name this uid (SO_PEERCRED of another uid is not testable on a one-user box; this is).
func TestPreludeOfAnotherUidIsRefused(t *testing.T) {
	for uid, ok := range map[int]bool{os.Getuid(): true, os.Getuid() + 1: false} {
		line := "crd-shim 1 {\"uid\":" + strconv.Itoa(uid) + ",\"env\":{}}\n"
		err := readPrelude(bufio.NewReader(strings.NewReader(line)))
		if (err == nil) != ok {
			t.Fatalf("uid %d: %v", uid, err)
		}
	}
}
