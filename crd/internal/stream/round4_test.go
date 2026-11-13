package stream

// Round 4 (dodatek 4 po ověření 2): a FIFO or a file of another owner at the place of an ev source, of the rotation
// stamp or of a transcript is never opened for reading in a way that blocks, never read, logged once, and tried again
// (review R-1); the session name boundary 256/257 bytes (V7b).

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/daemon"
	"github.com/lucashanak/claude-remote/crd/internal/testenv"
)

// mkfifo puts a FIFO at p (inside the test root); the cleanup wakes anything still blocked opening it, so a failing
// run of this test against blocking code ends instead of hanging the package.
func mkfifo(t *testing.T, p string) {
	t.Helper()
	if err := syscall.Mkfifo(p, 0o600); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { unblockFifo(p) })
}

func unblockFifo(p string) {
	for _, fl := range []int{os.O_WRONLY, os.O_RDONLY} {
		if f, err := os.OpenFile(p, fl|syscall.O_NONBLOCK, 0); err == nil {
			f.Close()
		}
	}
}

// within runs f and fails the test when it has not returned after d (a blocking open of a FIFO never returns).
func within(t *testing.T, d time.Duration, what string, f func()) {
	t.Helper()
	done := make(chan struct{})
	go func() { f(); close(done) }()
	select {
	case <-done:
	case <-time.After(d):
		t.Fatalf("%s did not return within %v", what, d)
	}
}

func logOf(t *testing.T, root string) string {
	t.Helper()
	b, _ := os.ReadFile(filepath.Join(root, "crd.log"))
	return string(b)
}

// R-1: FIFOs at legacy and jarvis when the provider starts (seeding), at events and notify while it runs (follow and
// rotation: countLines and tailOf): nothing blocks, the regular sources are seeded and followed, each FIFO is logged
// once however many rounds see it, and a regular file that comes back in its place is read again.
func TestEvFifoSourcesNeverBlock(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	p := env.Paths
	writeLines(t, p.Events, 1, 3, os.O_TRUNC)
	writeLines(t, p.Notify, 1, 2, os.O_TRUNC)
	writeLines(t, p.Forget, 1, 1, os.O_TRUNC)
	mkfifo(t, p.Legacy)
	mkfifo(t, p.Jarvis)
	var e *evProvider
	within(t, 3*time.Second, "seeding with FIFO sources", func() {
		s, _ := newEv(env)
		e = s.(*evProvider)
	})
	c := newConn("c")
	if _, err := e.Sub(c, crp.Obj{"st": "ev"}); err != nil {
		t.Fatal(err)
	}
	if got := blobsOf(c.take()); !strings.Contains(got, "L3|") || !strings.Contains(got, "L2|") {
		t.Fatalf("seed of the regular sources: %q", got)
	}
	if err := os.Rename(p.Events, p.Events+".old"); err != nil {
		t.Fatal(err)
	}
	mkfifo(t, p.Events)
	if err := os.Rename(p.Notify, p.Notify+".old"); err != nil {
		t.Fatal(err)
	}
	mkfifo(t, p.Notify)
	writeLines(t, p.Forget, 2, 2, os.O_APPEND)
	for i := 0; i < 3; i++ {
		within(t, 3*time.Second, "a round with FIFO sources", func() { e.round(true) })
	}
	if got := blobsOf(c.take()); !strings.Contains(got, "L2|") {
		t.Fatalf("forget not followed next to the FIFOs: %q", got)
	}
	lg := logOf(t, p.CRDir)
	for _, src := range []string{"legacy", "jarvis", "events", "notify"} {
		if n := strings.Count(lg, "ev=ev-not-regular src="+src+" "); n != 1 {
			t.Fatalf("ev-not-regular for %s logged %d times: %q", src, n, lg)
		}
	}
	if !strings.Contains(lg, `why="ev source not a regular file"`) {
		t.Fatalf("log %q", lg)
	}
	if err := os.Rename(p.Legacy, p.Legacy+".fifo"); err != nil {
		t.Fatal(err)
	}
	writeLines(t, p.Legacy, 7, 7, os.O_TRUNC)
	within(t, 3*time.Second, "a round after the FIFO went away", func() { e.round(false) })
	if got := blobsOf(c.take()); !strings.Contains(got, "L7|") {
		t.Fatalf("legacy not read once it is a regular file again: %q", got)
	}
}

// R-1: a regular file of another owner is not read (one user cannot create one, so the owner the daemon expects is
// changed instead: the branch is the same fstat comparison).
func TestEvSourceOfAnotherOwnerIsNotRead(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	p := env.Paths
	writeLines(t, p.Events, 1, 3, os.O_TRUNC)
	old := srcUID
	srcUID = uint32(os.Getuid()) + 1
	defer func() { srcUID = old }()
	r := &rec{}
	var logged []string
	fl := &follower{src: "events", path: p.Events, emit: r.emit, odd: func(src string) { logged = append(logged, src) }}
	if got := fl.seed(); len(got) != 0 || fl.f != nil {
		t.Fatalf("seeded %d lines of a file of another owner", len(got))
	}
	fl.check()
	fl.check()
	if len(r.all()) != 0 || len(logged) != 1 {
		t.Fatalf("emitted %v, logged %v", r.all(), logged)
	}
	if n := countLines(p.Events); n != 0 {
		t.Fatalf("countLines read a file of another owner: %d", n)
	}
	if _, err := tailOf(p.Events, 1); err == nil {
		t.Fatal("tailOf read a file of another owner")
	}
	srcUID = old
	fl.check()
	expectOnce(t, r.all(), span("events", 1, 3))
}

// R-1 (same class): events.rotated is opened for writing; a FIFO there without a reader made that open wait forever
// inside the round. The rotation ends, events is rotated, and the FIFO is left as it is.
func TestRotatedStampFifoNeverBlocks(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	p := env.Paths
	mkfifo(t, p.Rotated)
	old := time.Now().Add(-48 * time.Hour)
	if err := os.Chtimes(p.Rotated, old, old); err != nil {
		t.Fatal(err)
	}
	writeLines(t, p.Events, 1, MarkerMaxLines+1, os.O_TRUNC)
	s, _ := newEv(env)
	e := s.(*evProvider)
	within(t, 3*time.Second, "a rotation with a FIFO at events.rotated", func() { e.rotate(e.followers[0]) })
	if n := countLines(p.Events); n != MarkerKeepLines {
		t.Fatalf("events not rotated: %d lines", n)
	}
	if st, err := os.Lstat(p.Rotated); err != nil || st.Mode()&os.ModeNamedPipe == 0 {
		t.Fatalf("events.rotated: %v %v", st, err)
	}
}

// R-1: a transcript that is a FIFO is never opened in a way that blocks; the poll goes on to the other watches (the
// other device gets its TX), and the FIFO is logged once.
func TestTxFifoTranscriptNeverBlocks(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	s, _ := newTx(env)
	tx := s.(*txProvider)
	bad, good := "0f8e2c1a-1111-4222-8333-944455556666", "1f8e2c1a-1111-4222-8333-944455556666"
	fifo := transcript(t, env, bad, nil)
	if err := os.Rename(fifo, fifo+".gone"); err != nil {
		t.Fatal(err)
	}
	mkfifo(t, fifo)
	transcript(t, env, good, []byte("{\"a\":1}\n"))
	c1, c2 := newConn("c1"), newConn("c2")
	if _, err := tx.Sub(c1, crp.Obj{"st": "tx", "wid": "fifo", "uuid": bad, "off": int64(0)}); err != nil {
		t.Fatal(err)
	}
	if _, err := tx.Sub(c2, crp.Obj{"st": "tx", "wid": "ok", "uuid": good, "off": int64(0)}); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		within(t, 3*time.Second, "a tx poll with a FIFO transcript", tx.pollAll)
	}
	if fs := c2.take(); len(fs) != 1 || fs[0].Type != crp.TX {
		t.Fatalf("the other watch got %v", fs)
	}
	if fs := c1.take(); len(fs) != 0 {
		t.Fatalf("the FIFO watch got %v", fs)
	}
	if n := strings.Count(logOf(t, env.Paths.CRDir), "ev=tx-not-regular wid=fifo "); n != 1 {
		t.Fatalf("tx-not-regular logged %d times", n)
	}
}

// V7b: the name boundary is exactly 256 bytes: a session whose name has 257 bytes is left out (and logged), one of
// 256 is a row.
func TestSessName257IsLeftOut(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	dir := fakeTmuxListing(t, root, env)
	s, _ := newSess(env)
	sp := s.(*sessProvider)
	p256 := "claude-server-" + strings.Repeat("P", 256-len("claude-server-"))
	p257 := "claude-server-" + strings.Repeat("Q", 257-len("claude-server-"))
	setListing(t, dir, "ls", "NONCE:100:1:0:"+p256, "NONCE:100:1:0:"+p257)
	setListing(t, dir, "lp", "NONCE:1:1:%1:201:0:"+p256+":a", "NONCE:1:1:%2:202:0:"+p257+":b")
	c := newConn("c")
	if _, err := sp.Sub(c, crp.Obj{"st": "sess"}); err != nil {
		t.Fatal(err)
	}
	sp.poll()
	fs := c.take()
	if len(fs) != 1 || fs[0].Type != crp.SNAP {
		t.Fatalf("frames %v", fs)
	}
	rows := fs[0].Header["sessions"].([]any)
	if n, _ := crp.Str(rows[0].(map[string]any), "name"); len(rows) != 1 || n != p256 {
		t.Fatalf("%d rows, first %d bytes", len(rows), len(n))
	}
	if !strings.Contains(logOf(t, env.Paths.CRDir), "ev=sess-name-too-long sessions=1") {
		t.Fatal("no sess-name-too-long in the log")
	}
}

// N-2 (c), race found in round 4 (mutant run B1, itest TestTxResubscribeLoopStaysBounded: 10 MB before any ACK): a
// re-SUB of a wid between the poll's snapshot and its reads replaced the watch, the poll still flushed the old one
// and the new one opened a second window. The seam puts the re-SUB exactly there: the wid gets one window in total.
func TestTxReSubDuringPollOpensNoSecondWindow(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	s, _ := newTx(env)
	tx := s.(*txProvider)
	uuid := "0f8e2c1a-1111-4222-8333-944455556666"
	line := append(bytes.Repeat([]byte("b"), 9999), '\n')
	transcript(t, env, uuid, bytes.Repeat(line, 800)) // 8 MB: four windows
	c := newConn("c")
	sub := crp.Obj{"st": "tx", "wid": "w", "uuid": uuid, "off": int64(1)}
	if _, err := tx.Sub(c, sub); err != nil {
		t.Fatal(err)
	}
	resubs := 0
	tx.afterSnapshot = func() {
		if resubs < 3 {
			resubs++
			if _, err := tx.Sub(c, sub); err != nil {
				t.Error(err)
			}
		}
	}
	for i := 0; i < 5; i++ {
		tx.pollAll()
	}
	sent := 0
	for _, f := range c.take() {
		sent += len(f.Blob)
	}
	if resubs != 3 || sent == 0 || sent > daemon.MaxUnackedTxBytes {
		t.Fatalf("%d re-SUBs between snapshot and read: %d TX bytes before any ACK (window %d)", resubs, sent, daemon.MaxUnackedTxBytes)
	}
}
