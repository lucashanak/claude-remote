package stream

// Round 3 (dodatek 3 po ověření): test mode checks every ev path at its use, re-subscribing multiplies nothing, the
// rotation reads only blocks, sess names are never cut and a forged pane record changes no other session's row; plus
// the P1.6 paths of round 2 that had no test through a link (N18c/d/e, N19) and the 60 KiB boundary (N14b).

import (
	"bytes"
	"fmt"
	"math/rand"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/daemon"
	"github.com/lucashanak/claude-remote/crd/internal/testenv"
)

func blobsOf(fs []crp.Frame) string {
	var b strings.Builder
	for _, f := range fs {
		b.Write(f.Blob)
		b.WriteByte('|')
	}
	return b.String()
}

// N-1: after the start ~/.cache/claude-remote becomes a link to a directory outside the root: no line of the files
// there is read, nothing there is written (events stays, no events.rotated, rotate.lock or temp file), and the
// followers go on from their offsets once the directory is back.
func TestEvDirLinkedOutOfTheRootIsNeitherReadNorWritten(t *testing.T) {
	root := testenv.NewRoot(t)
	r2 := testenv.NewRoot(t)
	env := evEnv(t, root)
	p := env.Paths
	writeLines(t, p.Events, 1, 10, os.O_TRUNC)
	s, _ := newEv(env)
	e := s.(*evProvider)
	c := newConn("c")
	if _, err := e.Sub(c, crp.Obj{"st": "ev"}); err != nil {
		t.Fatal(err)
	}
	c.take()
	vdir := filepath.Join(r2.Dir, "vc")
	if err := os.Mkdir(vdir, 0o700); err != nil {
		t.Fatal(err)
	}
	var vb bytes.Buffer
	for i := 0; i < 1500; i++ {
		fmt.Fprintf(&vb, "VICTIM-%d\n", i)
	}
	for _, n := range []string{"events", "notify"} {
		if err := os.WriteFile(filepath.Join(vdir, n), vb.Bytes(), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Rename(p.Cache, p.Cache+".orig"); err != nil {
		t.Fatal(err)
	}
	testenv.SafeSymlink(t, vdir, p.Cache)
	for i := 0; i < 3; i++ {
		e.round(true)
	}
	if got := blobsOf(c.take()); strings.Contains(got, "VICTIM") {
		t.Fatalf("lines from outside the root: %.200s", got)
	}
	for _, n := range []string{"events", "notify"} {
		if b, _ := os.ReadFile(filepath.Join(vdir, n)); !bytes.Equal(b, vb.Bytes()) {
			t.Fatalf("%s outside the root was rewritten (%d lines)", n, bytes.Count(b, []byte{'\n'}))
		}
	}
	if es, _ := os.ReadDir(vdir); len(es) != 2 {
		t.Fatalf("files created outside the root: %v", es)
	}
	if b, _ := os.ReadFile(filepath.Join(p.CRDir, "crd.log")); !strings.Contains(string(b), "ev=ev-outside-root src=events") {
		t.Fatalf("log %q", b)
	}
	// back under the root: the follower goes on from its offset
	if err := testenv.SafeRemoveAll(p.Cache); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(p.Cache+".orig", p.Cache); err != nil {
		t.Fatal(err)
	}
	writeLines(t, p.Events, 11, 11, os.O_APPEND)
	e.round(false)
	if got := blobsOf(c.take()); got != "L11|" {
		t.Fatalf("after the directory came back: %q", got)
	}
}

// N18d (P1.6): in test mode an ev source that is a link (here to a file inside the root, so only O_NOFOLLOW stops it)
// is never read, neither by the seeding nor by the follower.
func TestEvSourceThroughALinkIsNotRead(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	p := env.Paths
	target := filepath.Join(root.Dir, "elsewhere")
	writeLines(t, target, 1, 5, os.O_TRUNC)
	testenv.SafeSymlink(t, target, p.Events)
	s, _ := newEv(env)
	e := s.(*evProvider)
	c := newConn("c")
	if _, err := e.Sub(c, crp.Obj{"st": "ev"}); err != nil {
		t.Fatal(err)
	}
	writeLines(t, target, 6, 6, os.O_APPEND)
	e.round(false)
	if got := blobsOf(c.take()); strings.Contains(got, "L") {
		t.Fatalf("lines read through a link: %q", got)
	}
}

// N18c (P1.6): events.rotated is never created through a link (a dangling one here, to a path inside the root).
func TestRotatedStampThroughALinkIsNotCreated(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	p := env.Paths
	target := filepath.Join(p.Cache, "stamp-target")
	testenv.SafeSymlink(t, target, p.Rotated)
	writeLines(t, p.Events, 1, MarkerMaxLines+1, os.O_TRUNC)
	s, _ := newEv(env)
	e := s.(*evProvider)
	e.rotate(e.followers[0])
	if n := countLines(p.Events); n != MarkerKeepLines {
		t.Fatalf("events not rotated: %d lines", n)
	}
	if _, err := os.Lstat(target); !os.IsNotExist(err) {
		t.Fatalf("events.rotated was created through the link: %v", err)
	}
}

// N18e and N19 (P1.6): a transcript that is a link (to a file inside the root: O_NOFOLLOW) or lies below a directory
// link out of the root (UnderRoot) is never opened in test mode.
func TestTranscriptThroughALinkIsNotRead(t *testing.T) {
	root := testenv.NewRoot(t)
	r2 := testenv.NewRoot(t)
	env := evEnv(t, root)
	s, _ := newTx(env)
	tx := s.(*txProvider)
	uuid := "0f8e2c1a-1111-4222-8333-944455556666"
	real := transcript(t, env, uuid, []byte("{\"a\":1}\n"))
	if f, err := tx.open(real); err != nil {
		t.Fatalf("plain transcript: %v", err)
	} else {
		f.Close()
	}
	link := filepath.Join(filepath.Dir(real), "1f8e2c1a-1111-4222-8333-944455556666.jsonl")
	testenv.SafeSymlink(t, real, link)
	if f, err := tx.open(link); err == nil {
		f.Close()
		t.Fatal("transcript opened through a final link")
	}
	out := filepath.Join(r2.Dir, "proj")
	if err := os.Mkdir(out, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(out, uuid+".jsonl"), []byte("{\"b\":2}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	testenv.SafeSymlink(t, out, filepath.Join(env.Paths.Projects, "-out"))
	if f, err := tx.open(filepath.Join(env.Paths.Projects, "-out", uuid+".jsonl")); err == nil {
		f.Close()
		t.Fatal("transcript below a directory link out of the root was opened")
	}
}

// N14b: the boundary is 60 KiB exactly: a line of 61 440 bytes is kept, one of 61 441 is dropped.
func TestEvLineMaxIs60KiB(t *testing.T) {
	if _, over := isOversize(lineOf(bytes.Repeat([]byte("x"), 61440))); over {
		t.Fatal("a line of 61440 bytes was dropped")
	}
	if n, over := isOversize(lineOf(bytes.Repeat([]byte("x"), 61441))); !over || n != 61441 {
		t.Fatal("a line of 61441 bytes was kept")
	}
}

// N-5: countLines and tailOf read in blocks and give exactly what one read of the whole file gives (wc -l, tail -n).
func TestCountLinesAndTailOfMatchTheWholeFile(t *testing.T) {
	root := testenv.NewRoot(t)
	p := filepath.Join(root.Dir, "f")
	rng := rand.New(rand.NewSource(7))
	shapes := [][]byte{nil, []byte("\n"), []byte("a"), []byte("a\n"), []byte("\n\n\n"), bytes.Repeat([]byte("x"), 3*countBlock),
		append(bytes.Repeat([]byte("y"), countBlock-1), '\n'), append(bytes.Repeat([]byte("z"), countBlock), '\n')}
	for i := 0; i < 40; i++ {
		var b bytes.Buffer
		for j := rng.Intn(500); j > 0; j-- {
			n := rng.Intn(300)
			if rng.Intn(50) == 0 {
				n = rng.Intn(3 * countBlock)
			}
			b.Write(bytes.Repeat([]byte{byte('a' + j%26)}, n))
			b.WriteByte('\n')
		}
		if rng.Intn(2) == 0 {
			b.WriteString("partial")
		}
		shapes = append(shapes, b.Bytes())
	}
	for i, data := range shapes {
		if err := os.WriteFile(p, data, 0o600); err != nil {
			t.Fatal(err)
		}
		if got, want := countLines(p), bytes.Count(data, []byte{'\n'}); got != want {
			t.Fatalf("shape %d: countLines %d, want %d", i, got, want)
		}
		for _, n := range []int{1, 2, 3, MarkerKeepLines} {
			got, err := tailOf(p, n)
			if err != nil || !bytes.Equal(got, tailKeep(data, n)) {
				t.Fatalf("shape %d n %d: tailOf %d bytes, tailKeep %d bytes (%v)", i, n, len(got), len(tailKeep(data, n)), err)
			}
		}
	}
}

// N-2 (c): a SUB of a stream the connection already follows sends no second SNAP or backlog and keeps the window;
// after UNSUB a SUB is a new subscription again.
func TestReSubscribeSendsNoSecondSnapshot(t *testing.T) {
	r := NewRing("e", EvRingItems, BacklogPer)
	st := newSeqStream("ev", r)
	st.itemFrame = func(it Item) crp.Frame { return EvFrame("e", it) }
	st.snapFrames = func(reason string, back []Item) []crp.Frame { return EvSnap(r, reason, back) }
	for i := 0; i < 30; i++ {
		st.appendItem("events", []byte(fmt.Sprintf("l%d", i)), nil)
	}
	c := newConn("c")
	st.subscribe(c, nil)
	first := len(c.take())
	for i := 0; i < 50; i++ {
		st.subscribe(c, nil)
		st.subscribe(c, &Pos{"e", 3})
	}
	if again := c.take(); first < 2 || len(again) != 0 {
		t.Fatalf("first SUB %d frames, repeated SUBs %d frames", first, len(again))
	}
	st.appendItem("events", []byte("live"), nil)
	if fs := c.take(); len(fs) != 1 || string(fs[0].Blob) != "live" {
		t.Fatalf("the subscription after the repeats: %v", fs)
	}
	st.unsubscribe(c)
	st.subscribe(c, nil)
	if fs := c.take(); len(fs) == 0 || fs[0].Type != crp.SNAP {
		t.Fatalf("SUB after UNSUB: %v", fs)
	}
}

// N-2 (c): a SUB tx of a wid the connection already watches takes over the old watch's window: re-subscribing in a
// loop never sends more than one window before the client acknowledges.
func TestTxReSubKeepsTheWindow(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	s, _ := newTx(env)
	tx := s.(*txProvider)
	uuid := "0f8e2c1a-1111-4222-8333-944455556666"
	line := append(bytes.Repeat([]byte("b"), 9999), '\n')
	transcript(t, env, uuid, bytes.Repeat(line, 2000))
	c := newConn("c")
	sent := 0
	var last int64
	for i := 0; i < 10; i++ {
		if _, err := tx.Sub(c, crp.Obj{"st": "tx", "wid": "w", "uuid": uuid, "off": int64(1)}); err != nil {
			t.Fatal(err)
		}
		tx.pollAll()
		for _, f := range c.take() {
			sent += len(f.Blob)
			last, _ = crp.Int(f.Header, "o")
		}
	}
	if sent == 0 || sent > daemon.MaxUnackedTxBytes {
		t.Fatalf("10 SUBs of one wid sent %d bytes", sent)
	}
	if err := tx.Ack(c, crp.Obj{"w": last}); err != nil {
		t.Fatal(err)
	}
	if more := c.take(); len(more) == 0 {
		t.Fatal("nothing flowed after the ACK of the window")
	}
}

// fakeTmuxListing puts a tmux on PATH that answers -V and prints dir/ls (list-sessions) and dir/lp (list-panes) with
// NONCE replaced by the nonce of the call's format, and listens on the guarded socket so the guard lets the call
// through. Nothing here talks to a tmux server.
func fakeTmuxListing(t *testing.T, root *testenv.Root, env *daemon.Env) string {
	t.Helper()
	dir := filepath.Join(root.Dir, "fake")
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	script := "#!/bin/sh\n[ \"$1\" = -V ] && { echo 'tmux 3.5a'; exit 0; }\n" +
		"v=; p=; f=\nfor a in \"$@\"; do case \"$a\" in list-sessions) v=ls;; list-panes) v=lp;; esac; [ \"$p\" = -F ] && f=\"$a\"; p=\"$a\"; done\n" +
		"sed \"s/NONCE/${f%%:*}/g\" '" + dir + "'/$v\n"
	if err := os.WriteFile(filepath.Join(dir, "tmux"), []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", dir+":"+os.Getenv("PATH"))
	if err := os.MkdirAll(filepath.Dir(env.Paths.TmuxSock), 0o700); err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("unix", env.Paths.TmuxSock)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	return dir
}

func setListing(t *testing.T, dir, name string, lines ...string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, name), []byte(strings.Join(lines, "\n")+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
}

// N-7: a session name is never cut: one over 256 bytes is left out of the table and logged, so three sessions with
// a common 256-byte prefix never become rows with the same name.
func TestSessNameIsNeverCut(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	dir := fakeTmuxListing(t, root, env)
	s, _ := newSess(env)
	sp := s.(*sessProvider)
	p256 := "claude-server-" + strings.Repeat("P", 256-len("claude-server-"))
	setListing(t, dir, "ls", "NONCE:100:1:0:claude-server-base", "NONCE:100:1:0:"+p256, "NONCE:100:1:0:"+p256+"AAAAAAAAAA",
		"NONCE:100:1:0:"+p256+"BBBBBBBBBB")
	setListing(t, dir, "lp", "NONCE:1:1:%0:200:0:claude-server-base:sleep", "NONCE:1:1:%1:201:0:"+p256+":exact",
		"NONCE:1:1:%2:202:0:"+p256+"AAAAAAAAAA:longer-A", "NONCE:1:1:%3:203:0:"+p256+"BBBBBBBBBB:longer-B")
	c := newConn("c")
	if _, err := sp.Sub(c, crp.Obj{"st": "sess"}); err != nil {
		t.Fatal(err)
	}
	sp.poll()
	fs := c.take()
	if len(fs) != 1 || fs[0].Type != crp.SNAP {
		t.Fatalf("frames %v", fs)
	}
	names := map[string]string{}
	for _, x := range fs[0].Header["sessions"].([]any) {
		o := x.(map[string]any)
		n, _ := crp.Str(o, "name")
		cmd, _ := crp.Str(o, "cmd")
		if _, dup := names[n]; dup || len(n) > MaxRowField {
			t.Fatalf("row name of %d bytes (duplicate %v)", len(n), dup)
		}
		names[n] = cmd
	}
	if len(names) != 2 || names[p256] != "exact" || names["claude-server-base"] != "sleep" {
		t.Fatalf("rows %v", names)
	}
	if b, _ := os.ReadFile(filepath.Join(env.Paths.CRDir, "crd.log")); !strings.Contains(string(b), "ev=sess-name-too-long sessions=2") {
		t.Fatalf("log %q", b)
	}
	if n, _ := crp.Str(Row{Name: strings.Repeat("n", 300)}.obj(), "name"); len(n) != 300 {
		t.Fatalf("obj cut a name to %d bytes", len(n))
	}
}

// N-8: a pane process that learns the nonce can print a whole record. A pane_id seen twice in one listing drops every
// record with it, and a session with more than one active record takes none: the victim's row never becomes the
// forged one, and a real change of the victim still arrives when the forgery only duplicates another pane's id.
func TestSessForgedPaneRecordChangesNoOtherRow(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	dir := fakeTmuxListing(t, root, env)
	s, _ := newSess(env)
	sp := s.(*sessProvider)
	setListing(t, dir, "ls", "NONCE:100:1:0:claude-server-base", "NONCE:100:2:0:claude-server-victim", "NONCE:100:3:0:claude-server-zzz")
	base := "NONCE:1:1:%0:200:0:claude-server-base:sleep"
	victim := func(cmd string) string { return "NONCE:1:1:%1:300:0:claude-server-victim:" + cmd }
	evil := "NONCE:1:1:%2:400:0:claude-server-zzz:evil"
	row := func() Row {
		sp.mu.Lock()
		defer sp.mu.Unlock()
		return sp.table["claude-server-victim"]
	}
	steps := []struct {
		name string
		lp   []string
		cmd  string
		pid  int64
	}{
		{"clean", []string{base, victim("sleep"), evil}, "sleep", 300},
		{"forged with the attacker's own pane id", []string{base, victim("vi"), evil, "NONCE:1:1:%2:4242:1:claude-server-victim:forged"}, "vi", 300},
		{"forged with the victim's pane id", []string{base, victim("top"), evil, "NONCE:1:1:%1:4242:1:claude-server-victim:forged"}, "vi", 300},
		{"forged with a fresh pane id", []string{base, victim("top"), evil, "NONCE:1:1:%99:4242:1:claude-server-victim:forged"}, "vi", 300},
		{"clean again", []string{base, victim("top"), evil}, "top", 300},
	}
	for _, st := range steps {
		setListing(t, dir, "lp", st.lp...)
		sp.poll()
		if r := row(); r.Cmd != st.cmd || r.PanePid != st.pid || r.Dead {
			t.Fatalf("%s: victim row %+v", st.name, r)
		}
		sp.mu.Lock()
		z, ok := sp.table["claude-server-zzz"]
		sp.mu.Unlock()
		if !ok || z.Cmd != "evil" || z.PanePid != 400 {
			t.Fatalf("%s: attacker row %+v %v", st.name, z, ok)
		}
	}
}

// A-F3 (found in round 3: TestEvSourcesAreNotReadUnderOff failed once under load): the creation of crd.off also kicks
// the ev loop, so a round may have seen crd.off absent just before. SetOff(true) returns only after that round ended,
// and the daemon answers E_OFF only after SetOff: no line is read after a client was told the daemon is off.
func TestSetOffWaitsForTheRoundInFlight(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	writeLines(t, env.Paths.Events, 1, 3, os.O_TRUNC)
	s, _ := newEv(env)
	e := s.(*evProvider)
	inEmit, release := make(chan struct{}), make(chan struct{})
	e.followers[0].emit = func(string, []byte) {
		close(inEmit)
		<-release
	}
	writeLines(t, env.Paths.Events, 4, 4, os.O_APPEND)
	go e.round(false)
	<-inEmit
	offDone := make(chan struct{})
	go func() { e.SetOff(true); close(offDone) }()
	select {
	case <-offDone:
		t.Fatal("SetOff returned while a round was still reading")
	case <-time.After(300 * time.Millisecond):
	}
	close(release)
	select {
	case <-offDone:
	case <-time.After(3 * time.Second):
		t.Fatal("SetOff did not return after the round")
	}
}
