package daemon

// Round 4 (dodatek 4 po ověření 2): crd.off never waits on a provider for longer than offWait (review R-1), the
// connection over the host's cap is refused with a code that is not a strike (R-3), and the gaps the second
// verification found in the tests of the host's queue budget (V2c-V2f) and of the write-timeout cause (V6a).

import (
	"errors"
	"net"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
)

// stuckStream is a provider whose SetOff does not return until released (a round stuck in a blocking open).
type stuckStream struct {
	panicStream
	release chan struct{}
}

func (s *stuckStream) SetOff(bool) { <-s.release }

// R-1: a provider that hangs in SetOff delays crd.off by offWait at most: the connection still gets BYE E_OFF, the
// switch back still happens when crd.off goes away, and the wait is logged.
func TestSetOffNeverWaitsLongerThanOffWait(t *testing.T) {
	st := &stuckStream{release: make(chan struct{})}
	defer close(st.release)
	s := testServer(t, st)
	old := offWait
	offWait = 300 * time.Millisecond
	defer func() { offWait = old }()
	out := &recW{}
	wdone := make(chan struct{})
	c := &conn{s: s, id: "c-1", done: make(chan struct{})}
	c.w = newWriter(out, crp.DefaultMaxFrame, func() { close(wdone) })
	s.conns[c.id] = c
	go c.w.run()
	for _, off := range []bool{true, false} {
		returned := make(chan struct{})
		go func() { s.setOff(off); close(returned) }()
		select {
		case <-returned:
		case <-time.After(3 * time.Second):
			t.Fatalf("setOff(%v) waited for a provider that hangs", off)
		}
		if s.Off() != off {
			t.Fatalf("off = %v after setOff(%v)", s.Off(), off)
		}
	}
	select {
	case <-wdone:
	case <-time.After(3 * time.Second):
		t.Fatal("no BYE written")
	}
	_, fs, err := crp.DecodeStream(append([]byte(crp.Magic), out.b.Bytes()...), crp.S2C, crp.DefaultMaxFrame, false, false)
	if err != nil || len(fs) != 1 || fs[0].Type != crp.BYE || fs[0].Header["code"] != crp.EOff {
		t.Fatalf("frames %v err %v", fs, err)
	}
	if l, _ := os.ReadFile(s.Log.path); strings.Count(string(l), "ev=crd-off-slow") != 2 {
		t.Fatalf("log %q", l)
	}
}

// R-3: the refusal over the connection cap is E_SHUTDOWN with retryMs (spec §1.4 cause `shutdown`: retry after
// retryMs, no strike), never E_PROTO or E_FRAME (cause `proto`, a strike).
func TestBusyByeIsNotAStrike(t *testing.T) {
	f := BusyBye()
	ms, ok := crp.Int(f.Header, "retryMs")
	if f.Type != crp.BYE || f.Header["code"] != crp.EShutdown || !ok || ms != BusyRetryMs || f.Header["msg"] != MsgConnLimit {
		t.Fatalf("%s %v", f.Type.Name(), f.Header)
	}
	if b, err := crp.Encode(f); err != nil || len(b) > 200 {
		t.Fatalf("encode %d %v", len(b), err)
	}
}

// shedRig is a server with connections whose writers do not run (their queues only grow) and count their kills.
type shedRig struct {
	s     *Server
	frame crp.Frame
	fs    int // encoded size of frame
}

func newShedRig(t *testing.T) *shedRig {
	frame := crp.NewFrame(crp.EV, crp.Obj{"st": "ev", "ep": "e", "seq": int64(1)}, make([]byte, 60<<10))
	b, err := crp.Encode(frame)
	if err != nil {
		t.Fatal(err)
	}
	return &shedRig{s: testServer(t, nil), frame: frame, fs: len(b)}
}

func (r *shedRig) conn(id string, frames int) (*conn, *int) {
	killed := 0
	c := &conn{s: r.s, id: id, done: make(chan struct{})}
	c.w = newWriter(&recW{}, crp.DefaultMaxFrame, func() {})
	c.w.host, c.w.kill = &r.s.qTotal, func() { killed++ }
	r.s.conns[id] = c
	for i := 0; i < frames; i++ {
		c.Send(r.frame)
	}
	return c, &killed
}

// V2c: among eight connections with queues of 1..8 frames, the shed closes the one with 8, whatever the order of the
// map: 32 rounds, each with a new map; picking any other one ends the round with the wrong connection closed (a pick
// in map order would survive all rounds with a chance of 8^-32).
func TestHostShedPicksTheLargestOfMany(t *testing.T) {
	r := newShedRig(t)
	old := hostQueueBudget
	defer func() { hostQueueBudget = old }()
	for round := 0; round < 32; round++ {
		hostQueueBudget = old
		r.s.conns = map[string]*conn{}
		r.s.qTotal.Store(0)
		cs := make([]*conn, 9)
		ks := make([]*int, 9)
		for k := 1; k <= 8; k++ {
			cs[k], ks[k] = r.conn("c-"+strconv.Itoa(round)+"-"+strconv.Itoa(k), k)
		}
		hostQueueBudget = int64(36*r.fs + r.fs/2) // 1+..+8 = 36 frames fit
		cs[1].Send(r.frame)                       // 37: one over, sent by the smallest queue
		for k := 1; k <= 8; k++ {
			if want := k == 8; (*ks[k] == 1) != want {
				t.Fatalf("round %d: queue of %d frames killed %d times", round, k, *ks[k])
			}
		}
		if r.s.qTotal.Load() != int64(29*r.fs) {
			t.Fatalf("round %d: sum %d, want %d", round, r.s.qTotal.Load(), 29*r.fs)
		}
	}
}

// V2d: when closing the largest queue is not enough, the shed goes on with the next largest until the sum is under
// the budget (a lowered budget stands in for queues that grew while the budget was full).
func TestHostShedClosesAsManyAsNeeded(t *testing.T) {
	r := newShedRig(t)
	old := hostQueueBudget
	defer func() { hostQueueBudget = old }()
	_, ka := r.conn("c-a", 4)
	_, kb := r.conn("c-b", 3)
	c, kc := r.conn("c-c", 1)
	hostQueueBudget = int64(2*r.fs + r.fs/2)
	c.Send(r.frame) // 4+3+2 = 9 frames: a (4) and b (3) must go, c (2) stays
	if *ka != 1 || *kb != 1 || *kc != 0 || r.s.qTotal.Load() != int64(2*r.fs) {
		t.Fatalf("killed a %d b %d c %d, sum %d", *ka, *kb, *kc, r.s.qTotal.Load())
	}
}

type errW struct{}

func (errW) Write([]byte) (int, error) { return 0, errors.New("peer gone") }

// V2e: a writer whose socket write fails leaves the host's sum with everything it still had queued.
func TestWriteFailureLeavesTheHostSum(t *testing.T) {
	r := newShedRig(t)
	done := make(chan struct{})
	w := newWriter(errW{}, crp.DefaultMaxFrame, func() { close(done) })
	w.host = &r.s.qTotal
	for i := 0; i < 5; i++ {
		if err := w.send(r.frame); err != nil {
			t.Fatal(err)
		}
	}
	if r.s.qTotal.Load() != int64(5*r.fs) {
		t.Fatalf("sum %d before the write", r.s.qTotal.Load())
	}
	go w.run()
	<-done
	if r.s.qTotal.Load() != 0 {
		t.Fatalf("sum %d after the failed write (%d frames still counted)", r.s.qTotal.Load(), r.s.qTotal.Load()/int64(r.fs))
	}
}

// resumeFailStream queues an item for HELLO.resume, then refuses the position (a protocol error).
type resumeFailStream struct{ panicStream }

func (resumeFailStream) Sub(c Conn, _ crp.Obj) (string, error) {
	c.Send(crp.NewFrame(crp.EV, crp.Obj{"st": "ev", "ep": "e", "seq": int64(1)}, make([]byte, 1000)))
	return "", errors.New("bad resume position")
}

// V2f: a HELLO.resume that fails after something was queued (the writer never ran) answers BYE E_PROTO and leaves
// nothing in the host's sum.
func TestFailedResumeLeavesTheHostSum(t *testing.T) {
	s := testServer(t, resumeFailStream{})
	srv, cl := socketPair(t)
	done := make(chan struct{})
	go func() { s.handle(srv); close(done) }()
	h := crp.Obj{"proto": []any{int64(1)}, "dev": "d", "link": "5b1e2f0c9a7d3e41", "att": int64(1), "ie": "0000000000000000",
		"app": "t", "feats": []any{"ev"}, "maxFrame": int64(crp.MinMaxFrame),
		"resume": map[string]any{"ev": map[string]any{"ep": "e", "seq": int64(1)}}}
	b, err := crp.Encode(crp.NewFrame(crp.HELLO, h, nil))
	if err != nil {
		t.Fatal(err)
	}
	pre := "crd-shim 1 {\"uid\":" + strconv.Itoa(os.Getuid()) + ",\"env\":{}}\n"
	if _, err := cl.Write(append(append([]byte(pre), crp.Magic...), b...)); err != nil {
		t.Fatal(err)
	}
	_ = cl.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := crp.SkipJunk(cl); err != nil {
		t.Fatal(err)
	}
	if f, err := crp.NewReader(cl, crp.S2C).Next(); err != nil || f.Type != crp.BYE || f.Header["code"] != crp.EProto {
		t.Fatalf("answer %s %v %v", f.Type.Name(), f.Header, err)
	}
	<-done
	if n := s.qTotal.Load(); n != 0 {
		t.Fatalf("host sum %d after the failed resume", n)
	}
}

// V6a: cause=write-timeout only for an expired write deadline: a write that fails because the client shut down its
// reading side (EPIPE, a net.Error that is not a timeout) is not named write-timeout.
func TestPeerErrorIsNotAWriteTimeout(t *testing.T) {
	s := testServer(t, nil)
	srv, cl := socketPair(t)
	done := make(chan struct{})
	go func() { s.handle(srv); close(done) }()
	clientHello(t, cl)
	if w, err := crp.NewReader(cl, crp.S2C).Next(); err != nil || w.Type != crp.WELCOME {
		t.Fatalf("first frame %v %v", w.Type.Name(), err)
	}
	if err := cl.(*net.UnixConn).CloseRead(); err != nil {
		t.Fatal(err)
	}
	b, _ := crp.Encode(crp.NewFrame(crp.PING, crp.Obj{"t": int64(1)}, nil))
	if _, err := cl.Write(b); err != nil { // the PONG to it is the write that fails
		t.Fatal(err)
	}
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("the connection did not end")
	}
	l, _ := os.ReadFile(s.Log.path)
	if !strings.Contains(string(l), "ev=conn-close conn=c-0001") || strings.Contains(string(l), "write-timeout") {
		t.Fatalf("log %q", l)
	}
}
