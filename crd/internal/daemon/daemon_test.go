package daemon

import (
	"bytes"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/testenv"
)

func TestMain(m *testing.M) { testenv.Main(m, testenv.Options{}) }

func helloAll() crp.Obj {
	return crp.Obj{"proto": []any{int64(1)}, "maxFrame": int64(1 << 20),
		"feats": []any{"ev", "sess", "tx", "term", "in", "rpc.exec", "gzip"}}
}

// dodatek B2: the product set is [ev sess tx gzip] in daemon order, whatever the client asks for (mutant 22).
func TestProductFeatsNeverOfferTermInRpc(t *testing.T) {
	n := Negotiate(helloAll(), ProtoMin, ProtoMax, ProductFeats, crp.DefaultMaxFrame)
	if !n.OK || strings.Join(n.Feats, " ") != "ev sess tx gzip" {
		t.Fatalf("feats %v", n.Feats)
	}
	if got := offered(nil); len(got) != 0 {
		t.Fatalf("no provider registered, offered %v", got)
	}
}

func TestNegotiateRefusals(t *testing.T) {
	h := helloAll()
	h["proto"] = []any{int64(2)}
	if n := Negotiate(h, 1, 1, ProductFeats, crp.DefaultMaxFrame); n.OK || n.Code != crp.EVersion {
		t.Fatalf("%+v", n)
	}
	h = helloAll()
	h["maxFrame"] = int64(65535)
	if n := Negotiate(h, 1, 1, ProductFeats, crp.DefaultMaxFrame); n.OK || n.Code != crp.EProto {
		t.Fatalf("%+v", n)
	}
	h = helloAll()
	h["maxFrame"] = int64(64 << 20)
	if n := Negotiate(h, 1, 1, ProductFeats, 64<<20); n.MaxFrame != crp.HardMaxFrame {
		t.Fatalf("maxFrame %d", n.MaxFrame)
	}
}

// spec §2.5: higher att supersedes; EQUAL or lower is STALE (mutant 8); another link is another key (vector 31).
func TestConnTable(t *testing.T) {
	tb := NewConnTable()
	if r := tb.Hello("A", "d", "l1", 5); !r.Accept {
		t.Fatal("first refused")
	}
	if r := tb.Hello("B", "d", "l1", 5); r.Accept {
		t.Fatal("equal att accepted")
	}
	if r := tb.Hello("C", "d", "l1", 4); r.Accept || r.Current != "A" {
		t.Fatal("lower att accepted")
	}
	if r := tb.Hello("D", "d", "l2", 1); !r.Accept || r.Superseded != "" {
		t.Fatal("other link superseded")
	}
	if r := tb.Hello("E", "d", "l1", 6); !r.Accept || r.Superseded != "A" {
		t.Fatalf("higher att: %+v", r)
	}
	tb.Gone("A", "d", "l1") // the loser leaving must not drop the winner
	if r := tb.Hello("F", "d", "l1", 6); r.Accept {
		t.Fatal("winner's entry lost when the superseded connection left")
	}
	if tb.Devs() != 1 {
		t.Fatal("devs")
	}
}

func TestIdleLimit(t *testing.T) {
	if IdleLimit(15000, IdleFloor) != 45*time.Second || IdleLimit(60000, IdleFloor) != 180*time.Second ||
		IdleLimit(5000, IdleFloor) != 45*time.Second {
		t.Fatal("max(45 s, 3 x pingMs)")
	}
}

type recW struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (r *recW) Write(p []byte) (int, error) { r.mu.Lock(); defer r.mu.Unlock(); return r.b.Write(p) }

// spec §2.6: BYE PONG ERR TSTATE INACK > TOUT > RPCRES RPCPART > EV SNAP > TX, FIFO within a class.
func TestWriterPriority(t *testing.T) {
	var order []crp.Type
	var mu sync.Mutex
	done := make(chan struct{})
	w := newWriter(&recW{}, 1<<20, func() { close(done) })
	w.sent = func(tp crp.Type) { mu.Lock(); order = append(order, tp); mu.Unlock() }
	tx := crp.NewFrame(crp.TX, crp.Obj{"wid": "w", "u": "u", "f": int64(0), "o": int64(1), "enc": "none"}, []byte("x"))
	ev := func(seq int64) crp.Frame {
		return crp.NewFrame(crp.EV, crp.Obj{"st": "ev", "ep": "e", "seq": seq}, []byte("l"))
	}
	w.send(tx)
	w.send(ev(1))
	w.send(crp.NewFrame(crp.RPCPART, crp.Obj{"id": int64(1), "part": int64(0)}, []byte("o")))
	w.send(crp.NewFrame(crp.TOUT, crp.Obj{"sid": "s", "tseq": int64(1)}, []byte("t")))
	w.send(ev(2))
	w.send(Pong(1, 2))
	go w.run()
	deadline := time.Now().Add(2 * time.Second)
	for w.pending() > 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	w.terminate(nil)
	<-done
	want := []crp.Type{crp.PONG, crp.TOUT, crp.RPCPART, crp.EV, crp.EV, crp.TX}
	mu.Lock()
	defer mu.Unlock()
	if len(order) != len(want) {
		t.Fatalf("order %v", order)
	}
	for i := range want {
		if order[i] != want[i] {
			t.Fatalf("order %v, want %v", order, want)
		}
	}
}

func TestWriterTerminalByeDropsQueue(t *testing.T) {
	out := &recW{}
	done := make(chan struct{})
	w := newWriter(out, 1<<20, func() { close(done) })
	w.send(crp.NewFrame(crp.EV, crp.Obj{"st": "ev", "ep": "e", "seq": int64(1)}, []byte("l")))
	bye := Bye(crp.Superseded, MsgSuperseded)
	w.terminate(&bye)
	w.send(Pong(1, 1)) // after terminate: dropped
	go w.run()
	<-done
	if !bytes.Equal(out.b.Bytes(), crp.MustEncode(bye)) {
		t.Fatalf("wrote %x", out.b.Bytes())
	}
}

func TestCutLogOnLineBoundary(t *testing.T) {
	b := []byte("aaaa\nbbbb\ncccc\n")
	if got := string(CutLog(b, 7)); got != "cccc\n" {
		t.Fatalf("%q", got)
	}
	if got := string(CutLog(b, 100)); got != string(b) {
		t.Fatal("short log changed")
	}
}

func TestLoggerCutsAt20MBTo16MB(t *testing.T) {
	r := testenv.NewRoot(t)
	oldMax, oldKeep := LogMax, LogKeep
	LogMax, LogKeep = 2000, 1600
	defer func() { LogMax, LogKeep = oldMax, oldKeep }()
	l, err := OpenLog(filepath.Join(r.Dir, "crd.log"))
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	for i := 0; i < 200; i++ {
		l.Log("x", "i", i, "pad", strings.Repeat("p", 20))
	}
	b, _ := os.ReadFile(filepath.Join(r.Dir, "crd.log"))
	if len(b) > 2000 || len(b) == 0 || !strings.HasPrefix(string(b), "ts=") || !strings.HasSuffix(string(b), "\n") {
		t.Fatalf("log size %d, starts %q", len(b), string(b[:10]))
	}
}

// env builds a getenv over a map.
func env(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

func testEnv(r *testenv.Root) map[string]string {
	return map[string]string{"CRD_TEST_ROOT": r.Dir, "HOME": r.Home, "TMUX_TMPDIR": r.TmuxTmp}
}

func TestResolveTestModePaths(t *testing.T) {
	r := testenv.NewRoot(t)
	p, err := ResolveServe(env(testEnv(r)))
	if err != nil {
		t.Fatal(err)
	}
	rr, _ := filepath.EvalSymlinks(r.Dir)
	if !p.Test || p.Legacy != filepath.Join(rr, "tmp", "claude-notify") || !strings.HasPrefix(p.Sock, rr+"/") ||
		!strings.HasPrefix(p.TmuxSock, rr+"/") || !strings.HasPrefix(p.RotLock, rr+"/") || !strings.HasPrefix(p.Events, rr+"/") {
		t.Fatalf("paths %+v", p)
	}
}

// D-H1a: CRD_TEST_ROOT wins over systemd markers; a broken test root is a refusal, never production.
func TestTestRootWinsOverSystemd(t *testing.T) {
	r := testenv.NewRoot(t)
	m := testEnv(r)
	m["NOTIFY_SOCKET"], m["INVOCATION_ID"], m["SYSTEMD_EXEC_PID"] = "/run/x", "abc", strconv.Itoa(os.Getpid())
	p, err := ResolveServe(env(m))
	if err != nil || !p.Test {
		t.Fatalf("test mode lost: %v %+v", err, p)
	}
	m["CRD_TEST_ROOT"] = filepath.Join(r.Dir, "missing")
	if _, err := ResolveServe(env(m)); err == nil {
		t.Fatal("broken test root fell through")
	}
}

// superseded by TestProductionMarkersDecideEachRefusal (round 2, T-R07): kept for the plain refusals
func TestProductionNeedsTheUnitsOwnMarkers(t *testing.T) {
	// a live tmux session has INVOCATION_ID inherited; even with NOTIFY_SOCKET it is not the unit
	m := map[string]string{"HOME": os.Getenv("HOME"), "INVOCATION_ID": "x", "NOTIFY_SOCKET": "/run/x"}
	if _, err := ResolveServe(env(m)); err == nil {
		t.Fatal("started in production without SYSTEMD_EXEC_PID/cgroup")
	}
	m["SYSTEMD_EXEC_PID"] = strconv.Itoa(os.Getpid())
	if _, err := ResolveServe(env(m)); err == nil {
		t.Fatal("started in production from a non-unit cgroup")
	}
	m["TMUX"] = "/tmp/x,1,0"
	if _, err := ResolveServe(env(m)); err == nil {
		t.Fatal("started with TMUX set")
	}
	if _, err := ResolveServe(env(map[string]string{})); err == nil {
		t.Fatal("started without anything")
	}
}

func TestResolveTestRefusals(t *testing.T) {
	r := testenv.NewRoot(t)
	cases := map[string]func(m map[string]string){
		"home=root":     func(m map[string]string) { m["HOME"] = r.Dir },
		"home outside":  func(m map[string]string) { m["HOME"] = "/" },
		"tmuxtmp /tmp":  func(m map[string]string) { m["TMUX_TMPDIR"] = "/tmp" },
		"tmuxtmp unset": func(m map[string]string) { delete(m, "TMUX_TMPDIR") },
		"root=/":        func(m map[string]string) { m["CRD_TEST_ROOT"] = "/" },
		"no marker": func(m map[string]string) {
			m["CRD_TEST_ROOT"] = r.Home
			m["HOME"] = r.Home + "/x"
			m["TMUX_TMPDIR"] = r.Home
		},
	}
	for name, mod := range cases {
		m := testEnv(r)
		mod(m)
		if _, err := ResolveServe(env(m)); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
	deep := testenv.NewRoot(t)
	long := filepath.Join(deep.Home, strings.Repeat("d", 60))
	if err := os.MkdirAll(long, 0o700); err != nil {
		t.Fatal(err)
	}
	m := testEnv(deep)
	m["HOME"] = long
	if _, err := ResolveServe(env(m)); err == nil {
		t.Error("socket path over 100 bytes accepted")
	}
}

func TestStatsLineHasOnlyB1aFields(t *testing.T) {
	s := &Server{table: NewConnTable(), conns: map[string]*conn{}, started: time.Now()}
	l := s.StatsLine()
	if !strings.HasPrefix(l, "crd stats conns=0 devs=0 rss_kb=") || strings.Contains(l, "rpc") || strings.Contains(l, "in_") {
		t.Fatalf("%q", l)
	}
}

var _ = io.Discard
