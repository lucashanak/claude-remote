package stream

import (
	"bytes"
	"compress/gzip"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/daemon"
	"github.com/lucashanak/claude-remote/crd/internal/testenv"
)

func TestMain(m *testing.M) { testenv.Main(m, testenv.Options{}) }

// fakeConn records frames a stream sends.
type fakeConn struct {
	id     string
	mu     sync.Mutex
	frames []crp.Frame
	feats  map[string]bool
	done   chan struct{}
}

func newConn(id string) *fakeConn {
	return &fakeConn{id: id, feats: map[string]bool{"ev": true, "sess": true, "tx": true, "gzip": true}, done: make(chan struct{})}
}
func (c *fakeConn) ID() string                          { return c.id }
func (c *fakeConn) Send(f crp.Frame)                    { c.mu.Lock(); c.frames = append(c.frames, f); c.mu.Unlock() }
func (c *fakeConn) Fail(code, msg string)               {}
func (c *fakeConn) Feat(n string) bool                  { return c.feats[n] }
func (c *fakeConn) Resume(string) (string, int64, bool) { return "", 0, false }
func (c *fakeConn) Done() <-chan struct{}               { return c.done }
func (c *fakeConn) take() []crp.Frame {
	c.mu.Lock()
	defer c.mu.Unlock()
	f := c.frames
	c.frames = nil
	return f
}

func seqs(fs []crp.Frame) []int64 {
	var out []int64
	for _, f := range fs {
		if f.Type == crp.EV {
			n, _ := crp.Int(f.Header, "seq")
			out = append(out, n)
		}
	}
	return out
}

func lines(fs []crp.Frame) []string {
	var out []string
	for _, f := range fs {
		if f.Type == crp.EV {
			out = append(out, string(f.Blob))
		}
	}
	return out
}

// ---- constants shared with the Kotlin watcher (dodatek Q2) ----

// The rotation numbers and the seeding depth are read from NotificationService.kt; a drift fails here.
func TestEvConstantsMatchKotlin(t *testing.T) {
	src := readKotlin(t)
	want := map[string]int{"MARKER_MAX_LINES": MarkerMaxLines, "MARKER_KEEP_LINES": MarkerKeepLines,
		"EVENTS_ROTATE_MIN_INTERVAL_MIN": EventsRotateMinutes}
	for name, v := range want {
		m := regexp.MustCompile(`const val ` + name + ` = (\d+)`).FindStringSubmatch(src)
		if m == nil {
			t.Fatalf("%s not found in NotificationService.kt", name)
		}
		if n, _ := strconv.Atoi(m[1]); n != v {
			t.Fatalf("%s = %d in Kotlin, %d in crd", name, n, v)
		}
	}
	if !strings.Contains(src, "tail -q -n "+strconv.Itoa(SeedLines)+" -F") {
		t.Fatal("seeding depth `tail -q -n 25 -F` not found")
	}
	if !strings.Contains(src, kotlinRotation) {
		t.Fatalf("rotation fragment %q not found", kotlinRotation)
	}
}

// kotlinRotation is the events rotation of buildNotifyWatcherCommand, as it stands in the Kotlin source.
const kotlinRotation = `tail -n $MARKER_KEEP_LINES $ev > $ev.rot && mv $ev.rot $ev && chmod 600 $ev && touch $ev.rotated`

func readKotlin(t *testing.T) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(testenv.RepoRoot(), "shared", "src", "commonMain", "kotlin", "com", "clauderemote",
		"session", "service", "NotificationService.kt"))
	if err != nil {
		t.Fatalf("NotificationService.kt: %v", err)
	}
	return string(b)
}

// ---- ring ----

func TestEmptyRingFirstIsLastPlusOne(t *testing.T) {
	r := NewRing("e", 4096, 25)
	if r.First() != 1 || r.Last() != 0 {
		t.Fatalf("empty start: first %d last %d", r.First(), r.Last())
	}
	for i := 0; i < 5; i++ {
		r.Append("events", []byte("x"), nil)
	}
	r.TrimBefore(6)
	if r.First() != 6 || r.Last() != 5 {
		t.Fatalf("trimmed: first %d last %d", r.First(), r.Last())
	}
	// age retention empties the ring the same way and keeps the counter
	now := time.Now()
	r2 := NewRing("e", 4096, 25)
	r2.MaxAge = time.Hour
	r2.Now = func() time.Time { return now }
	r2.Append("events", []byte("old"), nil)
	now = now.Add(2 * time.Hour)
	r2.Trim()
	if r2.First() != 2 || r2.Last() != 1 || len(r2.Backlog()) != 1 {
		t.Fatalf("age trim: first %d last %d backlog %d", r2.First(), r2.Last(), len(r2.Backlog()))
	}
}

func TestRingByteLimitAndResumeEdge(t *testing.T) {
	r := NewRing("e", 4096, 25)
	r.MaxBytes = 10 * (itemOverhead + 10)
	for i := 0; i < 30; i++ {
		r.Append("events", bytes.Repeat([]byte("a"), 10), nil)
	}
	if r.First() != 21 || r.Last() != 30 {
		t.Fatalf("byte limit: first %d", r.First())
	}
	// position first-1 is still inside: replay of everything (seq+1 == first)
	if d := r.Resume(&Pos{"e", 20}); d.Kind != "replay" || len(d.Items) != 10 {
		t.Fatalf("edge: %+v", d.Kind)
	}
	if d := r.Resume(&Pos{"e", 19}); d.Kind != "snapshot" || d.Reason != "overflow" {
		t.Fatalf("below edge: %+v", d.Kind)
	}
}

// SNAP over an empty backlog has from = S+1 (spec §4.3), e.g. a daemon that started without any source line.
func TestSnapOverEmptyBacklog(t *testing.T) {
	r := NewRing("e", 4096, 25)
	fs := EvSnap(r, "new", r.Backlog())
	if len(fs) != 1 {
		t.Fatal("backlog frames on an empty ring")
	}
	S, _ := crp.Int(fs[0].Header, "seq")
	F, _ := crp.Int(fs[0].Header, "from")
	if S != 0 || F != 1 {
		t.Fatalf("SNAP seq %d from %d, want 0 1", S, F)
	}
}

// ---- seqStream: flow control and mid-connection overflow (spec §2.6, §4.3, vector 29) ----

func TestPausedStreamOverflowsIntoSnap(t *testing.T) {
	r := NewRing("e", 1000, 25)
	st := newSeqStream("ev", r)
	st.itemFrame = func(it Item) crp.Frame { return EvFrame(r.Ep, it) }
	st.snapFrames = func(reason string, back []Item) []crp.Frame { return EvSnap(r, reason, back) }
	c := newConn("c1")
	if got := st.subscribe(c, nil); got != "" {
		t.Fatalf("new subscription resumed=%q", got)
	}
	c.take()
	for i := 1; i <= 3000; i++ {
		st.appendItem("events", []byte(fmt.Sprintf("l-%d", i)), nil)
	}
	got := seqs(c.take())
	if len(got) != daemon.MaxUnackedItems || got[len(got)-1] != daemon.MaxUnackedItems {
		t.Fatalf("sent %d items before the pause, want %d", len(got), daemon.MaxUnackedItems)
	}
	// ring now holds 2001..3000; item 1025 is gone: the ack resumes the stream with SNAP(overflow)
	if err := st.ack(c, crp.Obj{"ep": "e", "seq": int64(1024)}); err != nil {
		t.Fatal(err)
	}
	fs := c.take()
	if len(fs) == 0 || fs[0].Type != crp.SNAP || fs[0].Header["reason"] != "overflow" {
		t.Fatalf("no SNAP(overflow) after resume (%d frames)", len(fs))
	}
	if s, _ := crp.Int(fs[0].Header, "seq"); s != 3000 {
		t.Fatalf("SNAP seq %d", s)
	}
	// live items continue at S+1
	st.appendItem("events", []byte("next"), nil)
	if g := seqs(c.take()); len(g) != 1 || g[0] != 3001 {
		t.Fatalf("live after snap %v", g)
	}
	if err := st.ack(c, crp.Obj{"ep": "e", "seq": int64(3002)}); err == nil {
		t.Fatal("ACK above sent accepted")
	}
}

func TestReplayInsideRingIsExact(t *testing.T) {
	r := NewRing("e", 4096, 25)
	st := newSeqStream("ev", r)
	st.itemFrame = func(it Item) crp.Frame { return EvFrame(r.Ep, it) }
	st.snapFrames = func(reason string, back []Item) []crp.Frame { return EvSnap(r, reason, back) }
	for i := 1; i <= 45; i++ {
		st.appendItem("events", []byte("x"), nil)
	}
	c := newConn("c")
	if got := st.subscribe(c, &Pos{"e", 43}); got != "replay" {
		t.Fatal(got)
	}
	if g := seqs(c.take()); len(g) != 2 || g[0] != 44 || g[1] != 45 {
		t.Fatalf("replay %v", g)
	}
}

// ---- ev sources: seeding, partial lines, rotation (spec §4.4, §4.7; dodatek Q1, R2, R11) ----

type rec struct {
	mu  sync.Mutex
	got []string
}

func (r *rec) emit(src string, line []byte) {
	r.mu.Lock()
	r.got = append(r.got, src+":"+string(line))
	r.mu.Unlock()
}

func (r *rec) all() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.got...)
}

func writeLines(t *testing.T, p string, from, to int, mode int) {
	t.Helper()
	f, err := os.OpenFile(p, os.O_WRONLY|os.O_CREATE|mode, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	for i := from; i <= to; i++ {
		fmt.Fprintf(f, "L%d\n", i)
	}
	f.Close()
}

func appendRaw(t *testing.T, p, s string) {
	t.Helper()
	f, err := os.OpenFile(p, os.O_WRONLY|os.O_APPEND|os.O_CREATE, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	f.WriteString(s)
	f.Close()
}

// expectOnce: every line exactly once, in order.
func expectOnce(t *testing.T, got []string, want []string) {
	t.Helper()
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("emitted\n %v\nwant\n %v", got, want)
	}
}

func span(src string, from, to int) []string {
	var out []string
	for i := from; i <= to; i++ {
		out = append(out, fmt.Sprintf("%s:L%d", src, i))
	}
	return out
}

func newFollower(t *testing.T, p string, r *rec) *follower {
	fl := &follower{src: "events", path: p, emit: r.emit}
	fl.seed()
	return fl
}

// foreignRotator runs the Kotlin fragment itself, split at its mv, with absolute paths in the test root (D-H8).
func foreignRotator(t *testing.T, root *testenv.Root, ev string, between func()) {
	t.Helper()
	src := readKotlin(t)
	if !strings.Contains(src, kotlinRotation) {
		t.Fatal("rotation fragment not in the Kotlin source")
	}
	q := "'" + ev + "'"
	cmd := strings.NewReplacer("$MARKER_KEEP_LINES", strconv.Itoa(MarkerKeepLines), "$ev", q).Replace(kotlinRotation)
	head, tail, ok := strings.Cut(cmd, " && mv ")
	if !ok {
		t.Fatal("fragment has no mv")
	}
	tail = "mv " + tail
	for _, c := range []string{head, tail} {
		if strings.Contains(c, "~") || strings.Contains(c, "$HOME") || strings.Contains(c, "/tmp/claude-notify") || !strings.Contains(c, root.Dir) {
			t.Fatalf("rotator command leaves the root: %q", c)
		}
	}
	run := func(c string) {
		cmd := testenv.Command(t, nil, "env", "-i", "HOME="+root.Home, "PATH=/usr/bin:/bin", "sh", "-c", c)
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("%s: %v %s", c, err, out)
		}
	}
	run(head)
	between()
	run(tail)
}

// (i) no append, (ii) appends between tail and mv (only in the old inode), (iii) appends to the new file before
// crd sees the inode change: every line exactly once.
func TestForeignRotationNoLossNoDuplicate(t *testing.T) {
	for _, tc := range []string{"plain", "between", "new-file-first"} {
		t.Run(tc, func(t *testing.T) {
			root := testenv.NewRoot(t)
			ev := filepath.Join(root.Home, "events")
			writeLines(t, ev, 1, 1001, os.O_TRUNC)
			r := &rec{}
			fl := newFollower(t, ev, r)
			var want []string
			foreignRotator(t, root, ev, func() {
				if tc == "between" {
					writeLines(t, ev, 1002, 1004, os.O_APPEND)
					want = span("events", 1002, 1004)
				}
			})
			if tc == "new-file-first" {
				writeLines(t, ev, 2001, 2003, os.O_APPEND)
				want = append(want, span("events", 2001, 2003)...)
			}
			fl.check()
			writeLines(t, ev, 3001, 3001, os.O_APPEND)
			fl.check()
			want = append(want, "events:L3001")
			expectOnce(t, r.all(), want)
			if _, err := os.Stat(ev + ".rotated"); err != nil {
				t.Fatal("rotator did not stamp")
			}
		})
	}
}

// (iv) a file whose last line has no newline: the partial is emitted once, when complete (R11).
func TestPartialLineWaitsForNewline(t *testing.T) {
	root := testenv.NewRoot(t)
	ev := filepath.Join(root.Home, "events")
	writeLines(t, ev, 1, 3, os.O_TRUNC)
	appendRaw(t, ev, "claude-remote-ev stop Yg== 1 2 ")
	r := &rec{}
	fl := newFollower(t, ev, r)
	fl.check()
	if len(r.all()) != 0 {
		t.Fatalf("partial emitted: %v", r.all())
	}
	appendRaw(t, ev, "\n")
	fl.check()
	// trailing space kept byte for byte
	expectOnce(t, r.all(), []string{"events:claude-remote-ev stop Yg== 1 2 "})
	// truncation (same inode, shorter): read from the start, kept lines skipped
	if err := os.Truncate(ev, 0); err != nil {
		t.Fatal(err)
	}
	writeLines(t, ev, 9, 9, os.O_APPEND)
	fl.check()
	expectOnce(t, r.all(), []string{"events:claude-remote-ev stop Yg== 1 2 ", "events:L9"})
}

func TestSkipKept(t *testing.T) {
	b := func(xs ...string) [][]byte {
		var o [][]byte
		for _, x := range xs {
			o = append(o, []byte(x))
		}
		return o
	}
	if SkipKept(b("a", "b", "c", "d", "x", "y"), b("c", "d", "x", "y", "n")) != 4 {
		t.Fatal("suffix")
	}
	if SkipKept(b("a", "b", "c", "d", "x", "y"), b("b", "c", "d", "n")) != 3 {
		t.Fatal("contiguous run that is not a suffix")
	}
	if SkipKept(nil, b("a")) != 0 || SkipKept(b("a"), b("z")) != 0 {
		t.Fatal("no match")
	}
}

func evEnv(t *testing.T, root *testenv.Root) *daemon.Env {
	t.Helper()
	p, err := daemon.ResolveServe(func(k string) string {
		return map[string]string{"CRD_TEST_ROOT": root.Dir, "HOME": root.Home, "TMUX_TMPDIR": root.TmuxTmp}[k]
	})
	if err != nil {
		t.Fatal(err)
	}
	for _, d := range []string{p.Cache, p.CR, filepath.Dir(p.Legacy), p.CRDir} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	lg, err := daemon.OpenLog(filepath.Join(p.CRDir, "crd.log"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(lg.Close)
	return &daemon.Env{Paths: p, Log: lg, Now: time.Now, Ep: "a1b2c3d4"}
}

// spec §4.4 + erratum R2: seed order events, notify, legacy, forget, jarvis; 25 per source; seq 1..k.
func TestSeedingOrderFiveSources(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	p := env.Paths
	writeLines(t, p.Events, 1, 30, os.O_TRUNC)
	appendRaw(t, p.Notify, "stop-a\nstop-b\n")
	appendRaw(t, p.Legacy, "legacy-1\n")
	appendRaw(t, p.Forget, "forget claude-server-old\n")
	appendRaw(t, p.Jarvis, "jarvis-1\n")
	s, err := newEv(env)
	if err != nil {
		t.Fatal(err)
	}
	e := s.(*evProvider)
	var got []string
	for _, it := range e.seq.ring.Backlog() {
		got = append(got, it.Src)
	}
	want := append(strings.Split(strings.Repeat("events,", 25), ",")[:25], "notify", "notify", "legacy", "forget", "jarvis")
	if strings.Join(got, ",") != strings.Join(want, ",") || e.seq.ring.Last() != 30 {
		t.Fatalf("seed order %v", got)
	}
	if string(e.seq.ring.Backlog()[0].Line) != "L6" {
		t.Fatal("not the last 25 lines")
	}
}

// Own rotation: over 1000 lines with an old stamp ⇒ 200 kept, stamp touched, no temp left, lines appended after
// the rename are read; never for legacy/forget/jarvis, never under crd.off, skipped when rotate.lock is held.
func TestOwnRotation(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	p := env.Paths
	writeLines(t, p.Events, 1, 1200, os.O_TRUNC)
	old := time.Now().Add(-25 * time.Hour)
	appendRaw(t, p.Rotated, "")
	_ = os.Chtimes(p.Rotated, old, old)
	for _, x := range []string{p.Legacy, p.Forget, p.Jarvis} {
		writeLines(t, x, 1, 1200, os.O_TRUNC)
	}
	s, _ := newEv(env)
	e := s.(*evProvider)
	c := newConn("c")
	e.seq.subscribe(c, nil)
	c.take()
	// held lock: skipped
	lk, err := daemon.FlockNB(p.RotLock)
	if err != nil {
		t.Fatal(err)
	}
	for _, fl := range e.followers {
		e.rotate(fl)
	}
	if n := countLines(p.Events); n != 1200 {
		t.Fatal("rotated although rotate.lock was held")
	}
	lk.Close()
	e.SetOff(true)
	for _, fl := range e.followers {
		e.rotate(fl)
	}
	if n := countLines(p.Events); n != 1200 {
		t.Fatal("rotated under crd.off")
	}
	e.SetOff(false)
	for _, fl := range e.followers {
		e.rotate(fl)
	}
	if n := countLines(p.Events); n != MarkerKeepLines {
		t.Fatalf("events has %d lines after rotation", n)
	}
	for _, x := range []string{p.Legacy, p.Forget, p.Jarvis} {
		if n := countLines(x); n != 1200 {
			t.Fatalf("%s rotated", x)
		}
	}
	if st, _ := os.Stat(p.Rotated); time.Since(st.ModTime()) > time.Minute {
		t.Fatal("events.rotated not touched")
	}
	if st, _ := os.Stat(p.Events); st.Mode().Perm() != 0o600 {
		t.Fatal("mode not 600")
	}
	if m, _ := filepath.Glob(p.Events + ".rot.*"); len(m) != 0 {
		t.Fatalf("temp left: %v", m)
	}
	writeLines(t, p.Events, 5000, 5001, os.O_APPEND)
	for _, fl := range e.followers {
		fl.check()
	}
	if got := lines(c.take()); strings.Join(got, ",") != "L5000,L5001" {
		t.Fatalf("after own rotation %v", got)
	}
	// the stamp blocks a second rotation within 24 h
	writeLines(t, p.Events, 6000, 7000, os.O_APPEND)
	for _, fl := range e.followers {
		e.rotate(fl)
	}
	if n := countLines(p.Events); n <= MarkerMaxLines {
		t.Fatal("rotated again within 24 h")
	}
}

// ---- tx (spec §4.6, streamd v4) ----

func readerOf(b []byte) func(off, n int64) ([]byte, error) {
	return func(off, n int64) ([]byte, error) { return b[off : off+n], nil }
}

// A read without a complete line moves nothing; the line arrives whole with f = the original offset.
func TestTxHoldsPartialLine(t *testing.T) {
	w := &TxWatch{Off: 4}
	content := []byte("abc\n{\"partial\":")
	if d, _ := w.Poll(int64(len(content)), readerOf(content)); d != nil || w.Off != 4 {
		t.Fatalf("partial read moved the offset to %d", w.Off)
	}
	content = append(content, []byte("1}\n")...)
	d, _ := w.Poll(int64(len(content)), readerOf(content))
	if d == nil || d.F != 4 || d.O != int64(len(content)) || string(d.Chunk) != "{\"partial\":1}\n" {
		t.Fatalf("delta %+v", d)
	}
}

func TestTxLineOfOneAndAHalfMBInPieces(t *testing.T) {
	line := append(append([]byte(`{"type":"user","data":"`), bytes.Repeat([]byte("A"), 1500000)...), []byte("\"}\n")...)
	w := &TxWatch{Off: 3} // a client that has the first line (off 0 would read only the last 200 000 B)
	content := append([]byte("{}\n"), line...)
	d, _ := w.Poll(int64(len(content)), readerOf(content))
	if d == nil {
		t.Fatal("no delta")
	}
	ps := Pieces(d)
	var joined []byte
	for i, p := range ps {
		if len(p.Bytes) > crp.Piece || p.More != (i < len(ps)-1) || (i > 0 && p.F != ps[i-1].O) {
			t.Fatalf("piece %d: %d bytes more=%v", i, len(p.Bytes), p.More)
		}
		joined = append(joined, p.Bytes...)
		f := TxFrame("w", "u", p, i%2 == 1)
		if len(crp.MustEncode(f))-4 > crp.MinMaxFrame {
			t.Fatal("frame above the minimum maxFrame")
		}
		if i%2 == 1 {
			zr, err := gzip.NewReader(bytes.NewReader(f.Blob))
			if err != nil {
				t.Fatal(err)
			}
			b, _ := io.ReadAll(zr)
			if !bytes.Equal(b, p.Bytes) {
				t.Fatal("gzip piece differs")
			}
		}
	}
	if !bytes.Equal(joined, content[d.F:d.O]) || len(ps) < 30 {
		t.Fatalf("pieces do not join to the delta (%d pieces)", len(ps))
	}
}

// Pending reset survives an empty adjusted read (vector 26 in real sizes).
func TestTxPendingReset(t *testing.T) {
	giant := append(bytes.Repeat([]byte("x"), TxTail+5000), '\n')
	c1 := append([]byte("{\"a\":1}\n"), giant...)
	w := &TxWatch{Off: 0}
	if d, _ := w.Poll(int64(len(c1)), readerOf(c1)); d != nil {
		t.Fatal("first poll sent something")
	}
	c2 := append(c1, []byte("{\"b\":2}\n")...)
	d, _ := w.Poll(int64(len(c2)), readerOf(c2))
	if d == nil || !d.Reset || d.F != int64(len(c1)) {
		t.Fatalf("reset lost: %+v", d)
	}
}

func TestTxFlowControlTwoMiB(t *testing.T) {
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	s, _ := newTx(env)
	tx := s.(*txProvider)
	uuid := "0f8e2c1a-1111-4222-8333-944455556666"
	dir := filepath.Join(env.Paths.Projects, "-x")
	_ = os.MkdirAll(dir, 0o700)
	var content []byte
	for i := 0; i < 40; i++ {
		content = append(content, append(bytes.Repeat([]byte("b"), 100000), '\n')...)
	}
	if err := os.WriteFile(filepath.Join(dir, uuid+".jsonl"), content, 0o600); err != nil {
		t.Fatal(err)
	}
	c := newConn("c")
	if _, err := tx.Sub(c, crp.Obj{"st": "tx", "wid": "w1", "uuid": uuid, "off": int64(1), "enc": "none"}); err != nil {
		t.Fatal(err)
	}
	tx.pollAll()
	fs := c.take()
	var sent int64
	for _, f := range fs {
		sent += int64(len(f.Blob))
	}
	if sent > daemon.MaxUnackedTxBytes || sent < daemon.MaxUnackedTxBytes-crp.Piece {
		t.Fatalf("sent %d unacked bytes", sent)
	}
	last, _ := crp.Int(fs[len(fs)-1].Header, "o")
	if err := tx.Ack(c, crp.Obj{"w1": last}); err != nil {
		t.Fatal(err)
	}
	if more := c.take(); len(more) == 0 {
		t.Fatal("ACK did not release the watch")
	}
	if _, err := tx.Sub(c, crp.Obj{"st": "tx", "wid": "w2", "uuid": "../../etc", "off": int64(0)}); err == nil {
		t.Fatal("uuid with ../ accepted")
	}
}

// ---- sess: diff, read errors, mass drop, server change (spec §4.5) ----

func sessWith(t *testing.T) (*sessProvider, *fakeConn, *[]func() (map[string]Row, identity, bool)) {
	root := testenv.NewRoot(t)
	s, _ := newSess(evEnv(t, root))
	sp := s.(*sessProvider)
	reads := &[]func() (map[string]Row, identity, bool){}
	sp.readFn = func() (map[string]Row, identity, bool) {
		f := (*reads)[0]
		*reads = (*reads)[1:]
		return f()
	}
	return sp, newConn("c"), reads
}

func table(names ...string) func() (map[string]Row, identity, bool) {
	return func() (map[string]Row, identity, bool) {
		m := map[string]Row{}
		for _, n := range names {
			m[n] = Row{Name: n, Created: 1, PanePid: 10, Cmd: "sleep"}
		}
		return m, identity{inode: "1", pid: 100, start: "5"}, true
	}
}

func failing() (map[string]Row, identity, bool) { return nil, identity{}, false }

func TestSessReadErrorSendsNothing(t *testing.T) {
	sp, c, reads := sessWith(t)
	*reads = append(*reads, table("a", "b", "c", "d"), failing, failing)
	sp.Sub(c, crp.Obj{"st": "sess"})
	sp.poll()
	fs := c.take()
	if len(fs) != 1 || fs[0].Type != crp.SNAP || len(fs[0].Header["sessions"].([]any)) != 4 {
		t.Fatalf("first read: %v", fs)
	}
	sp.poll()
	sp.poll()
	if fs := c.take(); len(fs) != 0 {
		t.Fatalf("a failed read sent %v", fs)
	}
	if _, ok := sp.TmuxPid(); ok {
		t.Fatal("tmux pid kept after a failed guard")
	}
}

func TestSessMassDropHeldOnePoll(t *testing.T) {
	sp, c, reads := sessWith(t)
	*reads = append(*reads, table("a", "b", "c"), table("a"), table("a"), table("a", "x"))
	sp.Sub(c, crp.Obj{"st": "sess"})
	sp.poll()
	c.take()
	sp.poll() // 2 of 3 gone: held
	if fs := c.take(); len(fs) != 0 {
		t.Fatalf("mass drop sent at once: %v", fs[0].Header)
	}
	sp.poll() // confirmed
	fs := c.take()
	if len(fs) != 1 || string(crp.MustCanon(fs[0].Header["ops"])) != `[{"name":"b","op":"del"},{"name":"c","op":"del"}]` {
		t.Fatalf("confirmed drop: %v", fs)
	}
	sp.poll()
	if fs := c.take(); len(fs) != 1 || !strings.Contains(string(crp.MustCanon(fs[0].Header["ops"])), `"put"`) {
		t.Fatal("put after drop")
	}
}

func TestSessServerChangeIsSnapNotDel(t *testing.T) {
	sp, c, reads := sessWith(t)
	other := func() (map[string]Row, identity, bool) {
		m, _, _ := table("a")()
		return m, identity{inode: "2", pid: 200, start: "9"}, true
	}
	*reads = append(*reads, table("a", "b"), other)
	sp.Sub(c, crp.Obj{"st": "sess"})
	sp.poll()
	c.take()
	sp.poll()
	fs := c.take()
	if len(fs) != 1 || fs[0].Type != crp.SNAP || fs[0].Header["reason"] != "tmux-restart" {
		t.Fatalf("server change: %v", fs)
	}
	S, _ := crp.Int(fs[0].Header, "seq")
	F, _ := crp.Int(fs[0].Header, "from")
	if F != S+1 {
		t.Fatal("sess SNAP from != seq+1")
	}
}
