package daemon

// Round 3 (dodatek 3 po ověření): the host bounds connections and queues together, test mode checks the log's paths
// at every cut, and the gaps the verification found in the tests of round 2 (N40, N18b/f, N11b, N06b, N05b).

import (
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/testenv"
)

// N40 (verification T, probe ovn40): shutdown waits for a handshake goroutine that is still running (up to its 2 s).
func TestShutdownWaitsForHandshakes(t *testing.T) {
	s := testServer(t, nil)
	r := testenv.NewRoot(t)
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: filepath.Join(r.Dir, "s.sock"), Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	s.ln = ln
	s.hsWG.Add(1) // a handshake goroutine that has not finished
	done := make(chan struct{})
	go func() { s.shutdown(); close(done) }()
	select {
	case <-done:
		t.Fatal("shutdown returned while a handshake goroutine was still running")
	case <-time.After(400 * time.Millisecond):
	}
	s.hsWG.Done()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("shutdown did not return after the handshake ended")
	}
}

// N18b (P1.6): the lock is never taken through a link: a dangling link creates nothing, a link to a file locks nothing.
func TestFlockNeverFollowsALink(t *testing.T) {
	r := testenv.NewRoot(t)
	target := filepath.Join(r.Dir, "target")
	link := filepath.Join(r.Dir, "crd.lock")
	testenv.SafeSymlink(t, target, link)
	if f, err := flockNB(link); err == nil {
		f.Close()
		t.Fatal("lock taken through a dangling link")
	}
	if _, err := os.Lstat(target); !os.IsNotExist(err) {
		t.Fatalf("the link's target was created: %v", err)
	}
	if err := os.WriteFile(target, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if f, err := flockNB(link); err == nil {
		f.Close()
		t.Fatal("lock taken through a link to a file")
	}
}

// N11b: pingMs in STATE is capped like in HELLO (the idle limit stays at most 15 min).
func TestPingMsInStateIsCapped(t *testing.T) {
	s := testServer(t, nil)
	c := &conn{s: s, id: "c-1", done: make(chan struct{})}
	if err := c.dispatch(crp.NewFrame(crp.STATE, crp.Obj{"fg": true, "pingMs": int64(1) << 40}, nil)); err != nil {
		t.Fatal(err)
	}
	if got := c.pingMs.Load(); got != MaxPingMs {
		t.Fatalf("pingMs %d after STATE", got)
	}
	if err := c.dispatch(crp.NewFrame(crp.STATE, crp.Obj{"fg": true, "pingMs": int64(20000)}, nil)); err != nil || c.pingMs.Load() != 20000 {
		t.Fatalf("pingMs %d after STATE 20000 (%v)", c.pingMs.Load(), err)
	}
}

// N06b: SIGTERM that comes after the check behind the prelude, while the HELLO is on its way, is seen at the
// registration: BYE E_SHUTDOWN, never WELCOME (the read is not woken here, so only the recheck can answer).
func TestStoppingAtRegistrationAnswersShutdown(t *testing.T) {
	s := testServer(t, nil)
	srv, cl := socketPair(t)
	done := make(chan struct{})
	go func() { s.handle(srv); close(done) }()
	_ = cl.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := cl.Write([]byte("crd-shim 1 {\"uid\":" + strconv.Itoa(os.Getuid()) + ",\"env\":{}}\n")); err != nil {
		t.Fatal(err)
	}
	if _, err := crp.SkipJunk(cl); err != nil { // the daemon's magic: the check behind the prelude is behind us
		t.Fatal(err)
	}
	s.mu.Lock()
	s.stopping = true
	s.mu.Unlock()
	h := crp.Obj{"proto": []any{int64(1)}, "dev": "d", "link": "5b1e2f0c9a7d3e41", "att": int64(1), "ie": "0000000000000000",
		"app": "t", "feats": []any{"ev"}, "maxFrame": int64(crp.MinMaxFrame)}
	b, err := crp.Encode(crp.NewFrame(crp.HELLO, h, nil))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := cl.Write(append([]byte(crp.Magic), b...)); err != nil {
		t.Fatal(err)
	}
	f, err := crp.NewReader(cl, crp.S2C).Next()
	if err != nil || f.Type != crp.BYE || f.Header["code"] != crp.EShutdown {
		t.Fatalf("answer %s %v %v", f.Type.Name(), f.Header, err)
	}
	<-done
	if s.Conns() != 0 {
		t.Fatal("a connection was registered while stopping")
	}
}

// panicW is a socket whose write panics: the writer goroutine's backstop must contain it (N05b), without any hook in
// the product.
type panicW struct{}

func (panicW) Write([]byte) (int, error) { panic("writer bug") }

// N05b: a panic in the writer goroutine closes that connection only and logs CRITICAL.
func TestWriterPanicClosesOnlyItsConnection(t *testing.T) {
	r := testenv.NewRoot(t)
	lg, err := OpenLog(filepath.Join(r.Dir, "crd.log"))
	if err != nil {
		t.Fatal(err)
	}
	defer lg.Close()
	killed := make(chan struct{}, 1)
	w := newWriter(panicW{}, crp.MinMaxFrame, func() {})
	w.kill = func() { killed <- struct{}{} }
	if err := w.send(Pong(1, 2)); err != nil {
		t.Fatal(err)
	}
	escaped := make(chan any, 1)
	go func() {
		defer func() { escaped <- recover() }()
		w.runGuarded(lg, "c-0007")
	}()
	if p := <-escaped; p != nil {
		t.Fatalf("the panic left the writer goroutine: %v", p)
	}
	select {
	case <-killed:
	default:
		t.Fatal("the connection's socket was not closed")
	}
	if b, _ := os.ReadFile(lg.path); !strings.Contains(string(b), `ev=CRITICAL panic="writer bug" conn=c-0007`) {
		t.Fatalf("log %q", b)
	}
}

// N-2 (b): the writer queues of all connections together stay under the host's budget: going over closes the
// connection with the largest queue (log conn-overflow scope=host), the others go on, and the sum follows every
// queue and dequeue exactly.
func TestHostQueueBudgetClosesTheLargestQueue(t *testing.T) {
	s := testServer(t, nil)
	frame := crp.NewFrame(crp.EV, crp.Obj{"st": "ev", "ep": "e", "seq": int64(1)}, make([]byte, 60<<10))
	b, err := crp.Encode(frame)
	if err != nil {
		t.Fatal(err)
	}
	fs := len(b)
	old := hostQueueBudget
	hostQueueBudget = int64(17*fs + fs/2)
	defer func() { hostQueueBudget = old }()
	mk := func(id string) (*conn, *int) {
		killed := 0
		c := &conn{s: s, id: id, done: make(chan struct{})}
		c.w = newWriter(&recW{}, crp.DefaultMaxFrame, func() {})
		c.w.host, c.w.kill = &s.qTotal, func() { killed++ }
		s.conns[id] = c
		return c, &killed
	}
	a, ka := mk("c-a")
	bc, kb := mk("c-b")
	for i := 0; i < 10; i++ {
		a.Send(frame)
	}
	for i := 0; i < 7; i++ {
		bc.Send(frame)
	}
	if *ka+*kb != 0 || s.qTotal.Load() != int64(17*fs) {
		t.Fatalf("under the budget: killed %d %d, sum %d", *ka, *kb, s.qTotal.Load())
	}
	bc.Send(frame) // the sum goes over: the largest queue (a, 10 frames) is closed, not the sender
	if *ka != 1 || *kb != 0 || a.w.queued() != 0 || a.cause() != "overflow-host" {
		t.Fatalf("killed a %d b %d, a queued %d cause %s", *ka, *kb, a.w.queued(), a.cause())
	}
	if s.qTotal.Load() != int64(8*fs) || bc.w.queued() != 8*fs {
		t.Fatalf("sum %d, b queued %d", s.qTotal.Load(), bc.w.queued())
	}
	if l, _ := os.ReadFile(s.Log.path); !strings.Contains(string(l), "ev=conn-overflow conn=c-a scope=host") {
		t.Fatalf("log %q", l)
	}
	done := make(chan struct{})
	bc.w.onDone = func() { close(done) }
	bc.w.finish()
	go bc.w.run()
	<-done
	if s.qTotal.Load() != 0 {
		t.Fatalf("sum %d after b's queue was written", s.qTotal.Load())
	}
}

// N-1: in test mode a cut of the log checks its paths again: a log directory that became a link out of the root after
// the start is neither read nor written; the log goes on into the open file.
func TestLogCutNeverWritesOutsideTheRoot(t *testing.T) {
	r := testenv.NewRoot(t)
	r2 := testenv.NewRoot(t)
	oldMax, oldKeep := LogMax, LogKeep
	LogMax, LogKeep = 2000, 1600
	defer func() { LogMax, LogKeep = oldMax, oldKeep }()
	dir := filepath.Join(r.Dir, "crd")
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	l, err := OpenLog(filepath.Join(dir, "crd.log"))
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	root, _ := filepath.EvalSymlinks(r.Dir)
	l.under = Paths{Test: true, Root: root}.UnderRoot
	vdir := filepath.Join(r2.Dir, "crd")
	if err := os.Mkdir(vdir, 0o700); err != nil {
		t.Fatal(err)
	}
	victim := strings.Repeat("victim line\n", 300)
	if err := os.WriteFile(filepath.Join(vdir, "crd.log"), []byte(victim), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(dir, dir+".orig"); err != nil {
		t.Fatal(err)
	}
	testenv.SafeSymlink(t, vdir, dir)
	for i := 0; i < 200; i++ {
		l.Log("x", "i", i, "pad", strings.Repeat("p", 20))
	}
	if b, _ := os.ReadFile(filepath.Join(vdir, "crd.log")); string(b) != victim {
		t.Fatalf("the log outside the root was rewritten (%d bytes)", len(b))
	}
	if es, _ := os.ReadDir(vdir); len(es) != 1 {
		t.Fatalf("files outside the root: %v", es)
	}
	if b, _ := os.ReadFile(filepath.Join(dir+".orig", "crd.log")); !strings.Contains(string(b), "i=199") {
		t.Fatal("the log did not go on into its open file")
	}
}

// N18f (P1.6): the reopen after a cut never follows a link: a link put at the log's path right after the rename is
// not written; the log goes on into the file it has open (the next cut replaces the link by a file of its own).
func TestLogReopenNeverFollowsALink(t *testing.T) {
	r := testenv.NewRoot(t)
	oldMax, oldKeep := LogMax, LogKeep
	LogMax, LogKeep = 2000, 1600
	defer func() { LogMax, LogKeep = oldMax, oldKeep }()
	p := filepath.Join(r.Dir, "crd.log")
	l, err := OpenLog(p)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	victim := filepath.Join(r.Dir, "victim")
	if err := os.WriteFile(victim, []byte("original\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	linked := false
	l.afterRename = func() {
		if linked {
			return
		}
		linked = true
		if err := os.Rename(p, p+".moved"); err != nil {
			t.Fatal(err)
		}
		testenv.SafeSymlink(t, victim, p)
	}
	for i := 0; i < 200; i++ {
		l.Log("x", "i", i, "pad", strings.Repeat("p", 20))
	}
	if !linked {
		t.Fatal("no cut happened")
	}
	if b, _ := os.ReadFile(victim); string(b) != "original\n" {
		t.Fatalf("the link's target was written: %q", b)
	}
	moved, _ := os.ReadFile(p + ".moved")
	now, _ := os.ReadFile(p)
	if !strings.Contains(string(moved)+string(now), "i=199") {
		t.Fatal("log lines were lost")
	}
}

// V1n (verification T, probe TestProbeLogReopenDirLinkAfterRename): the log DIRECTORY becomes a link out of the root
// right after the rename of a cut; the reopen checks the path again and never appends to the crd.log out there.
func TestLogReopenNeverFollowsADirectoryLink(t *testing.T) {
	r := testenv.NewRoot(t)
	r2 := testenv.NewRoot(t)
	oldMax, oldKeep := LogMax, LogKeep
	LogMax, LogKeep = 2000, 1600
	defer func() { LogMax, LogKeep = oldMax, oldKeep }()
	dir := filepath.Join(r.Dir, "crd")
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	l, err := OpenLog(filepath.Join(dir, "crd.log"))
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	root, _ := filepath.EvalSymlinks(r.Dir)
	l.under = Paths{Test: true, Root: root}.UnderRoot
	vdir := filepath.Join(r2.Dir, "crd")
	if err := os.Mkdir(vdir, 0o700); err != nil {
		t.Fatal(err)
	}
	victim := "victim line\n"
	if err := os.WriteFile(filepath.Join(vdir, "crd.log"), []byte(victim), 0o600); err != nil {
		t.Fatal(err)
	}
	swapped := false
	l.afterRename = func() {
		if swapped {
			return
		}
		swapped = true
		if err := os.Rename(dir, dir+".orig"); err != nil {
			t.Fatal(err)
		}
		testenv.SafeSymlink(t, vdir, dir)
	}
	for i := 0; i < 200; i++ {
		l.Log("x", "i", i, "pad", strings.Repeat("p", 20))
	}
	if !swapped {
		t.Fatal("no cut")
	}
	if b, _ := os.ReadFile(filepath.Join(vdir, "crd.log")); string(b) != victim {
		t.Fatalf("victim log written: %d bytes", len(b))
	}
}
