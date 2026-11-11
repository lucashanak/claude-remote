package stream

// Round 2 (dodatek 2 po review): sess resume after a server change, a waiting subscriber that left, the nonce
// records, oversize ev lines, crd.off, rotation seams, tx limits and windows, resume windows and atomicity.

import (
	"bytes"
	"fmt"
	"math/rand"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/daemon"
	"github.com/lucashanak/claude-remote/crd/internal/testenv"
)

// T-R01 (probe P02): a client that saw the table up to S, was away while the tmux server was replaced, and resumes
// with {ep, S} gets SNAP{tmux-restart} with the new table, never an empty replay.
func TestSessResumeAfterServerChangeIsTmuxRestart(t *testing.T) {
	sp, c1, reads := sessWith(t)
	other := func() (map[string]Row, identity, bool) {
		m, _, _ := table("z")()
		return m, identity{inode: "2", pid: 200, start: "9"}, true
	}
	*reads = append(*reads, table("a", "b"), other, other)
	sp.Sub(c1, crp.Obj{"st": "sess"})
	sp.poll()
	fs := c1.take()
	S, _ := crp.Int(fs[0].Header, "seq")
	ep, _ := crp.Str(fs[0].Header, "ep")
	sp.Gone(c1)
	sp.poll() // the server is replaced while nobody listens
	c2 := newConn("c2")
	got, _ := sp.Sub(c2, crp.Obj{"st": "sess", "ep": ep, "seq": S})
	out := c2.take()
	if got != "snapshot" || len(out) != 1 || out[0].Type != crp.SNAP || out[0].Header["reason"] != "tmux-restart" {
		t.Fatalf("resume after a server change: resumed=%q frames=%v", got, out)
	}
	if names := sessionRowNames(out[0]); names != "z" {
		t.Fatalf("table %s", names)
	}
	// a client already on the new table resumes with a replay
	S2, _ := crp.Int(out[0].Header, "seq")
	c3 := newConn("c3")
	if got, _ := sp.Sub(c3, crp.Obj{"st": "sess", "ep": ep, "seq": S2}); got != "replay" {
		t.Fatalf("resume on the new table: %q", got)
	}
}

func sessionRowNames(f crp.Frame) string {
	var out []string
	for _, r := range f.Header["sessions"].([]any) {
		out = append(out, r.(map[string]any)["name"].(string))
	}
	return strings.Join(out, ",")
}

// K-F4 = A-F12: a subscriber that waits for the first table, whose connection ends while the poll holds its copy
// of the waiting list (Gone ran and found nothing to remove), is never registered; the poll then stops (no reads
// without subscribers). The copy is modelled by putting the entry back after Gone.
func TestSessWaitingSubscriberThatLeftIsNeverRegistered(t *testing.T) {
	sp, c, _ := sessWith(t)
	var n int
	var mu sync.Mutex
	sp.readFn = func() (map[string]Row, identity, bool) {
		mu.Lock()
		n++
		mu.Unlock()
		return table("a")()
	}
	sp.Sub(c, crp.Obj{"st": "sess"}) // no table yet: waits
	close(c.done)                    // the daemon closes Done before Gone
	sp.Gone(c)
	sp.mu.Lock()
	sp.waiting[c.ID()] = waitSub{c: c} // the poll's copy of the list still has it
	sp.mu.Unlock()
	sp.poll()
	if k := sp.seq.count(); k != 0 || sp.wanted() {
		t.Fatalf("%d subscriber(s) registered after the connection left", k)
	}
	stop, done := make(chan struct{}), make(chan struct{})
	sp.every = 10 * time.Millisecond
	go func() { sp.Start(stop); close(done) }()
	time.Sleep(200 * time.Millisecond)
	close(stop)
	<-done
	mu.Lock()
	defer mu.Unlock()
	if n != 1 {
		t.Fatalf("%d reads after the last subscriber left", n-1)
	}
}

// A-F2/K-F12: cmd is cut to 256 bytes at a UTF-8 boundary, the name never (review N-7), invalid bytes replaced.
func TestRowFieldsAreCut(t *testing.T) {
	o := Row{Name: strings.Repeat("n", 300), Cmd: strings.Repeat("ž", 200) + "\xff"}.obj()
	name, _ := crp.Str(o, "name")
	cmd, _ := crp.Str(o, "cmd")
	if len(name) != 300 || len(cmd) > MaxRowField || len(cmd) < MaxRowField-1 || !utf8.ValidString(cmd) {
		t.Fatalf("name %d cmd %d valid %v", len(name), len(cmd), utf8.ValidString(cmd))
	}
	if _, err := crp.Canon(o); err != nil {
		t.Fatal(err)
	}
}

// A-F5: records start with the poll's nonce; a line without it is the continuation of the command before it.
func TestRecordsKeepNewlinesInTheCommand(t *testing.T) {
	out := []byte("N:1:1:10:0:claude-server-a:sleep\nN:1:1:11:0:claude-server-z:evil\n1:1:4242:1:claude-server-a:forged\n" +
		"N2:x\nN:1:1:12:0:claude-server-b:vi\n")
	got := records(out, "N")
	want := []string{"1:1:10:0:claude-server-a:sleep", "1:1:11:0:claude-server-z:evil\n1:1:4242:1:claude-server-a:forged\nN2:x",
		"1:1:12:0:claude-server-b:vi"}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("records %q", got)
	}
	if len(records([]byte("junk\nN:a\n"), "N")) != 1 {
		t.Fatal("a line before the first record became one")
	}
}

// P1.4: lines over EvLineMax never enter the ring or the backlog (seeded or followed); they are counted and logged.
func TestEvOversizeLinesNeverEnterTheRing(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	p := env.Paths
	appendRaw(t, p.Events, "a\n"+strings.Repeat("Q", EvLineMax+1)+"\nb\n")
	appendRaw(t, p.Forget, strings.Repeat("F", 1200<<10)+"\nforget-small\n")
	s, _ := newEv(env)
	e := s.(*evProvider)
	appendRaw(t, p.Events, strings.Repeat("R", EvLineMax+1)+"\nc\n"+strings.Repeat("S", EvLineMax)+"\n")
	e.round(false)
	var got []string
	for _, it := range e.seq.ring.Backlog() {
		if len(it.Line) > EvLineMax {
			t.Fatalf("item of %d bytes in the ring", len(it.Line))
		}
		got = append(got, it.Src+":"+string(it.Line[:min(len(it.Line), 12)]))
	}
	if strings.Join(got, ",") != "events:a,events:b,forget:forget-small,events:c,events:SSSSSSSSSSSS" || e.dropped.Load() != 3 {
		t.Fatalf("ring %v, dropped %d", got, e.dropped.Load())
	}
	if b, _ := os.ReadFile(filepath.Join(p.CRDir, "crd.log")); !strings.Contains(string(b), "ev=ev-oversize src=forget len=1228800") {
		t.Fatalf("log %q", b)
	}
}

// K-F12: a line without newline longer than one read (8 MiB) no longer stalls its source: it is passed over and
// dropped, and the lines after it are read.
func TestFollowerPassesOverALineLongerThanOneRead(t *testing.T) {
	root := testenv.NewRoot(t)
	p := filepath.Join(root.Dir, "events")
	writeLines(t, p, 1, 3, os.O_TRUNC)
	r := &rec{}
	fl := newFollower(t, p, r)
	appendRaw(t, p, strings.Repeat("z", 9<<20))
	fl.check()
	appendRaw(t, p, "zzz\nafter\n")
	fl.check()
	fl.check()
	if got := r.all(); strings.Join(got, ",") != "events:after" {
		t.Fatalf("emitted %d lines: %.80v", len(got), got)
	}
}

// A-F3: under crd.off a round reads nothing; afterwards the follower goes on from its offset.
func TestEvRoundReadsNothingUnderOff(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	writeLines(t, env.Paths.Events, 1, 3, os.O_TRUNC)
	s, _ := newEv(env)
	e := s.(*evProvider)
	last := e.seq.ring.Last()
	e.SetOff(true)
	writeLines(t, env.Paths.Events, 4, 5, os.O_APPEND)
	e.round(true)
	if e.seq.ring.Last() != last {
		t.Fatal("read under crd.off")
	}
	e.SetOff(false)
	e.round(false)
	if e.seq.ring.Last() != last+2 {
		t.Fatalf("after crd.off: last %d, want %d", e.seq.ring.Last(), last+2)
	}
}

// T-R15 (zadání §2): own rotation with lines appended to the old inode between its read and the rename: every line
// is emitted exactly once, none of the kept tail again.
func TestOwnRotationKeepsLinesAppendedBeforeRename(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	p := env.Paths
	writeLines(t, p.Events, 1, 1200, os.O_TRUNC)
	s, _ := newEv(env)
	e := s.(*evProvider)
	c := newConn("c")
	e.seq.subscribe(c, nil)
	c.take()
	e.beforeRename = func(src string) {
		if src == "events" {
			writeLines(t, p.Events, 1201, 1203, os.O_APPEND) // the old inode, after the read
		}
	}
	for _, fl := range e.followers {
		e.rotate(fl)
	}
	if n := countLines(p.Events); n != MarkerKeepLines {
		t.Fatalf("%d lines after rotation", n)
	}
	writeLines(t, p.Events, 1204, 1204, os.O_APPEND)
	e.round(false)
	if got := lines(c.take()); strings.Join(got, ",") != "L1201,L1202,L1203,L1204" {
		t.Fatalf("emitted %v", got)
	}
}

// T-R16 + A-F16: notify rotates once it is over 1000 lines; a round that found it small does not start the 24 h
// timer; a rotation does.
func TestNotifyRotatesOnceItIsOver1000(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	p := env.Paths
	writeLines(t, p.Notify, 1, 10, os.O_TRUNC)
	s, _ := newEv(env)
	e := s.(*evProvider)
	var nf *follower
	for _, fl := range e.followers {
		if fl.src == "notify" {
			nf = fl
		}
	}
	e.rotate(nf) // small: nothing, and no timer
	writeLines(t, p.Notify, 11, 1200, os.O_APPEND)
	e.rotate(nf)
	if n := countLines(p.Notify); n != MarkerKeepLines {
		t.Fatalf("notify has %d lines", n)
	}
	writeLines(t, p.Notify, 1201, 2400, os.O_APPEND)
	e.rotate(nf)
	if n := countLines(p.Notify); n <= MarkerMaxLines {
		t.Fatal("notify rotated twice within 24 h")
	}
}

// A-F16: a temp file left by an interrupted rotation with this pid is removed at start, so the rotation runs.
func TestRotationTempLeftoverIsCleaned(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	p := env.Paths
	writeLines(t, p.Events, 1, 1200, os.O_TRUNC)
	left := p.Events + ".rot.crd." + strconv.Itoa(os.Getpid())
	appendRaw(t, left, "half\n")
	s, _ := newEv(env)
	stop := make(chan struct{})
	done := make(chan struct{})
	go func() { s.Start(stop); close(done) }()
	deadline := time.Now().Add(5 * time.Second)
	for {
		if n := countLines(p.Events); n == MarkerKeepLines {
			break
		}
		if time.Now().After(deadline) {
			close(stop)
			<-done
			t.Fatal("no rotation with a leftover temp file")
		}
		time.Sleep(20 * time.Millisecond)
	}
	close(stop)
	<-done
	if _, err := os.Lstat(left); !os.IsNotExist(err) {
		t.Fatal("leftover temp file still there")
	}
}

// A-F9: a rotator with a gap (the file is missing for a while, then comes back with the kept tail): nothing again.
func TestFollowerSkipsTheKeptTailAcrossAGap(t *testing.T) {
	root := testenv.NewRoot(t)
	p := filepath.Join(root.Dir, "events")
	writeLines(t, p, 1, 300, os.O_TRUNC)
	r := &rec{}
	fl := newFollower(t, p, r)
	if err := os.Rename(p, p+".1"); err != nil {
		t.Fatal(err)
	}
	fl.check()
	fl.check()
	writeLines(t, p+".tmp", 101, 300, os.O_TRUNC)
	writeLines(t, p+".tmp", 301, 301, os.O_APPEND)
	if err := os.Rename(p+".tmp", p); err != nil {
		t.Fatal(err)
	}
	fl.check()
	expectOnce(t, r.all(), span("events", 301, 301))
}

// A-F10: seeding reads only the end of a source; a tail that starts inside a line skips that line.
func TestSeedReadsOnlyTheTail(t *testing.T) {
	root := testenv.NewRoot(t)
	p := filepath.Join(root.Dir, "events")
	var b bytes.Buffer
	b.WriteString("head-line-that-is-not-read\n")
	b.WriteString(strings.Repeat("x", int(SeedTailBytes)) + "\n")
	for i := 1; i <= 30; i++ {
		fmt.Fprintf(&b, "L%d\n", i)
	}
	if err := os.WriteFile(p, b.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	r := &rec{}
	fl := &follower{src: "events", path: p, emit: r.emit}
	got := fl.seed()
	if len(got) != SeedLines || string(got[0]) != "L6" || fl.off != int64(b.Len()) {
		t.Fatalf("seed %d lines from %q, off %d of %d", len(got), got[0], fl.off, b.Len())
	}
}

func transcript(t *testing.T, env *daemon.Env, uuid string, content []byte) string {
	t.Helper()
	dir := filepath.Join(env.Paths.Projects, "-x")
	_ = os.MkdirAll(dir, 0o700)
	p := filepath.Join(dir, uuid+".jsonl")
	if err := os.WriteFile(p, content, 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

// P1.1 + P1.3: wid is 1-128 of [A-Za-z0-9._-]; a SUB of a known wid replaces the watch; at most 64 watches per
// connection and 256 per host.
func TestTxWidAndWatchLimits(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	s, _ := newTx(env)
	tx := s.(*txProvider)
	uuid := "0f8e2c1a-1111-4222-8333-944455556666"
	sub := func(c *fakeConn, wid string) error {
		_, err := tx.Sub(c, crp.Obj{"st": "tx", "wid": wid, "uuid": uuid, "off": int64(0)})
		return err
	}
	c := newConn("c")
	for _, wid := range []string{strings.Repeat("w", 129), "a b", "a/b", "a:b", "ž"} {
		if sub(c, wid) == nil {
			t.Fatalf("wid %q accepted", wid)
		}
	}
	for i := 0; i < 1000; i++ {
		if err := sub(c, "same"); err != nil {
			t.Fatal(err)
		}
	}
	for i := 1; i < MaxTxWatchesPerConn; i++ {
		if err := sub(c, "w"+strconv.Itoa(i)); err != nil {
			t.Fatalf("watch %d: %v", i, err)
		}
	}
	if sub(c, "one-more") == nil {
		t.Fatal("65th watch on one connection accepted")
	}
	if err := sub(c, "same"); err != nil {
		t.Fatal("replacing a watch at the limit refused")
	}
	for k := 1; k < MaxTxWatchesHost/MaxTxWatchesPerConn; k++ {
		ck := newConn("c" + strconv.Itoa(k))
		for i := 0; i < MaxTxWatchesPerConn; i++ {
			if err := sub(ck, "w"+strconv.Itoa(i)); err != nil {
				t.Fatalf("conn %d watch %d: %v", k, i, err)
			}
		}
	}
	if sub(newConn("late"), "w") == nil {
		t.Fatal("257th watch on the host accepted")
	}
}

// A-F7: a watch from offset 1 of a 20 MiB transcript holds at most the flow-control window in bytes (queued pieces
// carry no bytes until they are sent).
func TestTxHoldsAtMostTheWindow(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	s, _ := newTx(env)
	tx := s.(*txProvider)
	uuid := "0f8e2c1a-1111-4222-8333-944455556666"
	line := append(bytes.Repeat([]byte("b"), 9999), '\n')
	transcript(t, env, uuid, bytes.Repeat(line, 2000))
	c := newConn("c")
	if _, err := tx.Sub(c, crp.Obj{"st": "tx", "wid": "w", "uuid": uuid, "off": int64(1)}); err != nil {
		t.Fatal(err)
	}
	tx.pollAll()
	var held, sent int
	for _, p := range tx.watches["c"]["w"].queue {
		held += len(p.Bytes)
	}
	for _, f := range c.take() {
		sent += len(f.Blob)
	}
	if held != 0 || sent > daemon.MaxUnackedTxBytes || sent == 0 {
		t.Fatalf("held %d bytes in the queue, sent %d", held, sent)
	}
}

// The daemon's range poll is streamd's poll: on random files and offsets it gives exactly what one read of the
// whole remainder gives (the B1a-1 algorithm, kept here as the reference).
func TestPollRangeMatchesWholeRead(t *testing.T) {
	ref := func(w *TxWatch, content []byte) *TxDelta {
		sz := int64(len(content))
		off := w.Off
		if off < 0 {
			w.Off = sz
			return nil
		}
		adjusted := false
		if sz < off || (off == 0 && sz > TxTail) {
			off = max(0, sz-TxTail)
			adjusted = true
		}
		if sz <= off {
			return nil
		}
		data := content[off:]
		nl := bytes.LastIndexByte(data, '\n')
		if nl < 0 {
			return nil
		}
		chunk, newOff, start := data[:nl+1], off+int64(nl)+1, off
		if adjusted && off > 0 {
			first := bytes.IndexByte(chunk, '\n')
			chunk, start = chunk[first+1:], off+int64(first)+1
		}
		w.Off = newOff
		if adjusted {
			w.pendingReset = true
		}
		if len(chunk) == 0 {
			return nil
		}
		reset := w.pendingReset
		w.pendingReset = false
		return &TxDelta{F: start, O: newOff, Reset: reset, Chunk: chunk}
	}
	rng := rand.New(rand.NewSource(7))
	for i := 0; i < 400; i++ {
		var b bytes.Buffer
		for b.Len() < rng.Intn(400000)+1 {
			b.Write(bytes.Repeat([]byte("x"), rng.Intn(3*txScanBlock)))
			if rng.Intn(5) > 0 {
				b.WriteByte('\n')
			}
		}
		content := b.Bytes()
		offs := []int64{0, -1, 1, int64(len(content)), int64(len(content)) + 5, rng.Int63n(int64(len(content)) + 1)}
		for _, off := range offs {
			a, r := &TxWatch{Off: off, pendingReset: rng.Intn(2) == 0}, (*TxWatch)(nil)
			cp := *a
			r = &cp
			got, err := a.Poll(int64(len(content)), readerOf(content))
			want := ref(r, content)
			if err != nil || (got == nil) != (want == nil) || *a != *r ||
				(got != nil && (got.F != want.F || got.O != want.O || got.Reset != want.Reset || !bytes.Equal(got.Chunk, want.Chunk))) {
				t.Fatalf("case %d off %d size %d: got %+v want %+v", i, off, len(content), got, want)
			}
		}
	}
}

// A piece that cannot be read any more (the transcript shrank) drops the rest and the watch starts over with reset.
func TestTxShrunkTranscriptStartsOverWithReset(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	s, _ := newTx(env)
	tx := s.(*txProvider)
	uuid := "0f8e2c1a-1111-4222-8333-944455556666"
	line := append(bytes.Repeat([]byte("b"), 99999), '\n')
	p := transcript(t, env, uuid, bytes.Repeat(line, 40))
	c := newConn("c")
	if _, err := tx.Sub(c, crp.Obj{"st": "tx", "wid": "w", "uuid": uuid, "off": int64(1)}); err != nil {
		t.Fatal(err)
	}
	tx.pollAll()
	fs := c.take()
	if err := os.WriteFile(p, []byte("{\"new\":1}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	last, _ := crp.Int(fs[len(fs)-1].Header, "o")
	if err := tx.Ack(c, crp.Obj{"w": last}); err != nil {
		t.Fatal(err)
	}
	if more := c.take(); len(more) != 0 {
		t.Fatalf("%d stale pieces sent after the file shrank", len(more))
	}
	tx.pollAll()
	fs = c.take()
	if len(fs) != 1 || fs[0].Header["reset"] != true || string(fs[0].Blob) != "{\"new\":1}\n" {
		t.Fatalf("after the shrink: %v", fs)
	}
}

// T-R12: a resume with more than 1024 items to replay sends 1024 (the window), the rest after the ACK.
func TestResumeReplayStopsAtTheWindow(t *testing.T) {
	r := NewRing("e", EvRingItems, BacklogPer)
	st := newSeqStream("ev", r)
	st.itemFrame = func(it Item) crp.Frame { return EvFrame("e", it) }
	st.snapFrames = func(reason string, back []Item) []crp.Frame { return EvSnap(r, reason, back) }
	for i := 0; i < 3000; i++ {
		st.appendItem("events", []byte("x"), nil)
	}
	c := newConn("c")
	if got := st.subscribe(c, &Pos{"e", 10}); got != "replay" {
		t.Fatal(got)
	}
	first := seqs(c.take())
	if len(first) != daemon.MaxUnackedItems || first[0] != 11 || first[len(first)-1] != 10+daemon.MaxUnackedItems {
		t.Fatalf("first batch %d items", len(first))
	}
	if err := st.ack(c, crp.Obj{"ep": "e", "seq": first[len(first)-1]}); err != nil {
		t.Fatal(err)
	}
	next := seqs(c.take())
	if len(next) != daemon.MaxUnackedItems || next[0] != first[len(first)-1]+1 {
		t.Fatalf("after the ACK %d items from %v", len(next), next[:1])
	}
}

// T-R27: the replay and the registration are one critical section: subscribers that join while items are appended
// see every item after their position, without a gap and without an ACK (all within the window).
func TestReplayAndRegistrationAreAtomic(t *testing.T) {
	for round := 0; round < 20; round++ {
		r := NewRing("e", EvRingItems, BacklogPer)
		st := newSeqStream("ev", r)
		st.itemFrame = func(it Item) crp.Frame { return EvFrame("e", it) }
		st.snapFrames = func(reason string, back []Item) []crp.Frame { return EvSnap(r, reason, back) }
		st.appendItem("events", []byte("x"), nil)
		const total = 600
		done := make(chan struct{})
		go func() {
			for i := 0; i < total; i++ {
				st.appendItem("events", []byte("x"), nil)
				if i%7 == 0 {
					time.Sleep(time.Microsecond)
				}
			}
			close(done)
		}()
		type joined struct {
			c   *fakeConn
			pos int64
		}
		var js []joined
		for i := 0; i < 60; i++ {
			st.mu.Lock()
			pos := r.Last()
			st.mu.Unlock()
			c := newConn("c" + strconv.Itoa(i))
			st.subscribe(c, &Pos{"e", pos})
			js = append(js, joined{c, pos})
		}
		<-done
		for _, j := range js {
			got := seqs(j.c.take())
			if int64(len(got)) != r.Last()-j.pos || (len(got) > 0 && (got[0] != j.pos+1 || got[len(got)-1] != r.Last())) {
				t.Fatalf("round %d: joined at %d, got %d items up to %v, last %d", round, j.pos, len(got), got[len(got)-1:], r.Last())
			}
		}
	}
}

// P1.3: a repeated SUB of one stream on one connection replaces the subscription, never adds one.
func TestRepeatedSubscribeReplaces(t *testing.T) {
	r := NewRing("e", EvRingItems, BacklogPer)
	st := newSeqStream("ev", r)
	st.itemFrame = func(it Item) crp.Frame { return EvFrame("e", it) }
	st.snapFrames = func(reason string, back []Item) []crp.Frame { return EvSnap(r, reason, back) }
	c := newConn("c")
	for i := 0; i < 1000; i++ {
		st.subscribe(c, nil)
	}
	if st.count() != 1 {
		t.Fatalf("%d subscriptions", st.count())
	}
}

// T-R17: the rotation rule is Stage A's `-gt 1000`: exactly 1000 lines stay, 1001 rotate.
func TestOwnRotationBoundaryIsOver1000(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	p := env.Paths
	writeLines(t, p.Events, 1, MarkerMaxLines, os.O_TRUNC)
	s, _ := newEv(env)
	e := s.(*evProvider)
	for _, fl := range e.followers {
		e.rotate(fl)
	}
	if n := countLines(p.Events); n != MarkerMaxLines {
		t.Fatalf("rotated at exactly %d lines", n)
	}
	writeLines(t, p.Events, MarkerMaxLines+1, MarkerMaxLines+1, os.O_APPEND)
	for _, fl := range e.followers {
		e.rotate(fl)
	}
	if n := countLines(p.Events); n != MarkerKeepLines {
		t.Fatalf("%d lines after 1001", n)
	}
}

// T-R02: exactly half of the rows gone is not a mass drop (more than half is): the dels go out at once.
func TestSessHalfGoneIsNotHeld(t *testing.T) {
	sp, c, reads := sessWith(t)
	*reads = append(*reads, table("a", "b", "c", "d"), table("a", "b"))
	sp.Sub(c, crp.Obj{"st": "sess"})
	sp.poll()
	c.take()
	sp.poll()
	fs := c.take()
	if len(fs) != 1 || string(crp.MustCanon(fs[0].Header["ops"])) != `[{"name":"c","op":"del"},{"name":"d","op":"del"}]` {
		t.Fatalf("half gone: %v", fs)
	}
}

// T-R21: SUB tx with enc gzip needs the negotiated gzip feature.
func TestTxGzipNeedsTheFeature(t *testing.T) {
	root := testenv.NewRoot(t)
	s, _ := newTx(evEnv(t, root))
	c := newConn("c")
	delete(c.feats, "gzip")
	if _, err := s.Sub(c, crp.Obj{"st": "tx", "wid": "w", "uuid": "0f8e2c1a-1111-4222-8333-944455556666", "off": int64(0), "enc": "gzip"}); err == nil {
		t.Fatal("gzip without the feature accepted")
	}
	c.feats["gzip"] = true
	if _, err := s.Sub(c, crp.Obj{"st": "tx", "wid": "w", "uuid": "0f8e2c1a-1111-4222-8333-944455556666", "off": int64(0), "enc": "gzip"}); err != nil {
		t.Fatal(err)
	}
}
