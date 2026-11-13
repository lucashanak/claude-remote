package itest

// Round 3 (dodatek 3 po ověření), as crd processes over the socket: a cache directory that becomes a link after the
// start (N-1), what many connections can make the host hold together (N-2), big ev sources at the start (N-5), the
// cause of a write timeout in the log (N-6) and session names over 256 bytes with a real tmux (N-7). On the tree of
// B1a-2 each of them fails (B1a-rozhodnuti.md, "Kolo 3", D18).

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/testenv"
)

func hwmKBOf(pid int) int64 {
	b, err := os.ReadFile(fmt.Sprintf("/proc/%d/status", pid))
	if err != nil {
		return -1
	}
	for _, l := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(l, "VmHWM:") {
			n, _ := strconv.ParseInt(strings.Fields(l)[1], 10, 64)
			return n
		}
	}
	return -1
}

// bigBacklog appends 30 ev lines of lineLen bytes (a SNAP carries 25 of them).
func bigBacklog(t *testing.T, r *testenv.Root, lineLen int) {
	t.Helper()
	fx := writeFixture(t, r)
	var b bytes.Buffer
	for i := 0; i < 30; i++ {
		b.WriteString(evLine("work", "claude-server-amp", 1793990000000+int64(i), i, strings.Repeat("x", lineLen)) + "\n")
	}
	appendTo(t, fx.events, b.String())
}

// N-1 (verification A, probe TestOvToctouCacheDir): after the start ~/.cache/claude-remote becomes a link to a
// directory outside CRD_TEST_ROOT with an events file of 1500 lines and rotation every 700 ms: no line from there is
// delivered, the file there stays byte for byte, and no events.rotated, rotate.lock or temp file appears there.
func TestEvCacheDirLinkedAfterStartStaysInTheRoot(t *testing.T) {
	r := testenv.NewRoot(t)
	r2 := testenv.NewRoot(t)
	fx := writeFixture(t, r)
	vdir := filepath.Join(r2.Dir, "vc")
	must(t, os.MkdirAll(vdir, 0o700))
	var b bytes.Buffer
	for i := 0; i < 1500; i++ {
		fmt.Fprintf(&b, "VICTIM-LINE-%d\n", i)
	}
	must(t, os.WriteFile(filepath.Join(vdir, "events"), b.Bytes(), 0o600))
	d := startDaemon(t, r, "CRD_TEST_ROTATE_MS=700")
	c := dial(t, r, hello("dev-toc", link1, 1, nil))
	c.send(crp.SUB, crp.Obj{"st": "ev"}, nil)
	c.collect(1200 * time.Millisecond)
	cache := filepath.Dir(fx.events)
	must(t, os.Rename(cache, cache+".orig"))
	testenv.SafeSymlink(t, vdir, cache)
	victimEv := 0
	for _, f := range c.collect(3 * time.Second) {
		if f.Type == crp.EV && bytes.HasPrefix(f.Blob, []byte("VICTIM-LINE-")) {
			victimEv++
		}
	}
	d.stop()
	vb, _ := os.ReadFile(filepath.Join(vdir, "events"))
	es, _ := os.ReadDir(vdir)
	var names []string
	for _, e := range es {
		names = append(names, e.Name())
	}
	if victimEv != 0 || !bytes.Equal(vb, b.Bytes()) || len(names) != 1 {
		t.Fatalf("outside the root: %d EV delivered, events %d lines (was 1500), files %v", victimEv, bytes.Count(vb, []byte{'\n'}), names)
	}
	if !strings.Contains(d.log(), "ev=ev-outside-root") {
		t.Fatal("no ev-outside-root in the log")
	}
}

// N-2 (a): at most 64 connections per host: one more is answered before its handshake and closed; when one goes away
// the next one is served. The answer is BYE E_SHUTDOWN{retryMs} (review R-3: E_PROTO is a client strike, spec §1.4).
func TestConnectionLimitPerHost(t *testing.T) {
	r := testenv.NewRoot(t)
	d := startDaemon(t, r)
	var cs []*tconn
	for i := 0; i < 64; i++ {
		cs = append(cs, dial(t, r, hello(fmt.Sprintf("dev-cl-%d", i), link1, 1, nil)))
	}
	f, err := rawAnswer(t, r, hello("dev-cl-over", link1, 1, nil))
	ms, _ := crp.Int(f.Header, "retryMs")
	if err != nil || f.Type != crp.BYE || f.Header["code"] != crp.EShutdown || ms != 5000 || f.Header["msg"] != "connection limit reached" {
		t.Fatalf("connection 65: %s %v %v", f.Type.Name(), f.Header, err)
	}
	if !strings.Contains(d.log(), "ev=conn-limit conns=64") {
		t.Fatal("no conn-limit in the log")
	}
	cs[0].nc.Close()
	waitFor(t, 5*time.Second, "a free slot after one connection closed", func() bool {
		f, err := rawAnswer(t, r, hello("dev-cl-next", link1, time.Now().UnixNano()&0xffff+1, nil))
		return err == nil && f.Type == crp.WELCOME
	})
}

// N-2 (a): connections still in their handshake count too (each holds a reader buffer and a goroutine).
func TestHandshakesCountAgainstTheConnectionLimit(t *testing.T) {
	r := testenv.NewRoot(t)
	startDaemon(t, r)
	for i := 0; i < 64; i++ {
		nc := dialRaw(t, r) // prelude sent, no HELLO: in the handshake for HelloTimeout
		_ = nc.SetReadDeadline(time.Now().Add(5 * time.Second))
		if _, err := crp.SkipJunk(nc); err != nil { // the daemon's magic: this one is registered as a handshake
			t.Fatalf("handshake %d: %v", i, err)
		}
	}
	f, err := rawAnswer(t, r, hello("dev-hs-over", link1, 1, nil))
	if err != nil || f.Type != crp.BYE || f.Header["code"] != crp.EShutdown {
		t.Fatalf("connection 65 next to 64 handshakes: %s %v %v", f.Type.Name(), f.Header, err)
	}
}

// N-2 (c) with (b) behind it (verification A, probe TestOvMultiConnAmp: 12 × 20 SUB ev grew RSS by 336 MB): twelve
// connections that SUB ev twenty times without reading hold one SNAP each, and a reader of one of them gets one SNAP.
func TestManyConnectionsResubscribingStayBounded(t *testing.T) {
	r := testenv.NewRoot(t)
	bigBacklog(t, r, 40000)
	d := startDaemon(t, r)
	before := rssKBOf(d.pid())
	sub, err := crp.Encode(crp.NewFrame(crp.SUB, crp.Obj{"st": "ev"}, nil))
	must(t, err)
	var cs []*tconn
	for k := 0; k < 12; k++ {
		c := dial(t, r, hello(fmt.Sprintf("dev-amp-%d", k), link1, 1, nil))
		_ = c.nc.SetWriteDeadline(time.Now().Add(20 * time.Second))
		_, _ = c.nc.Write(bytes.Repeat(sub, 20))
		cs = append(cs, c)
	}
	time.Sleep(2 * time.Second)
	grew := rssKBOf(d.pid()) - before
	t.Logf("RSS %d kB + %d kB for 12 connections x 20 SUB ev", before, grew)
	if grew > floodBoundKB {
		t.Fatalf("RSS grew by %d kB", grew)
	}
	snaps := 0
	for _, f := range cs[11].collect(1500 * time.Millisecond) {
		if f.Type == crp.SNAP {
			snaps++
		}
	}
	if snaps != 1 {
		t.Fatalf("a reader of twenty SUBs got %d SNAPs", snaps)
	}
	servesAnother(t, r, d, "dev-amp-alive")
}

// N-2 (c) (verification A, probe TestOvTxReSubAmp: 8 × 15 re-SUB of one wid grew RSS by 423 MB): a re-SUB of the
// same wid takes over the window, so a client that re-subscribes in a loop gets at most one window before it ACKs.
func TestTxResubscribeLoopStaysBounded(t *testing.T) {
	r := testenv.NewRoot(t)
	writeTranscript(t, r, 64<<20)
	d := startDaemon(t, r)
	before := rssKBOf(d.pid())
	var cs []*tconn
	for k := 0; k < 8; k++ {
		c := dial(t, r, hello(fmt.Sprintf("dev-rs-%d", k), link1, 1, nil))
		for i := 0; i < 15; i++ {
			c.send(crp.SUB, crp.Obj{"st": "tx", "uuid": uuidT, "off": int64(1), "wid": "same"}, nil)
			time.Sleep(20 * time.Millisecond)
		}
		cs = append(cs, c)
	}
	time.Sleep(2 * time.Second)
	grew := rssKBOf(d.pid()) - before
	t.Logf("RSS %d kB + %d kB for 8 connections x 15 SUB tx of one wid", before, grew)
	// coarse: under -race the reads of 120 polls leave up to ~96 MB of garbage; the exact check is the byte count below
	if grew > 2*floodBoundKB {
		t.Fatalf("RSS grew by %d kB", grew)
	}
	got := 0
	for _, f := range cs[7].collect(1500 * time.Millisecond) {
		if f.Type == crp.TX {
			got += len(f.Blob)
		}
	}
	if got == 0 || got > 2<<20 {
		t.Fatalf("a reader of fifteen SUBs of one wid got %d TX bytes before any ACK", got)
	}
	servesAnother(t, r, d, "dev-rs-alive")
}

// N-5 (verification A, probe TestOvF10BigSourcesAtStart: HWM 506 MB / 259 MB for 241 MiB): the rotation at the start
// counts the lines and takes the kept tail in blocks; the daemon's high-water mark stays far below the file size.
func TestBigSourcesAtStartAreReadInBlocks(t *testing.T) {
	for _, stamped := range []bool{false, true} {
		r := testenv.NewRoot(t)
		fx := writeFixture(t, r)
		line := evLine("work", "claude-server-big", 1793990000000, 1, strings.Repeat("z", 900)) + "\n"
		f, err := os.OpenFile(fx.events, os.O_WRONLY|os.O_APPEND, 0o600)
		must(t, err)
		chunk := strings.Repeat(line, 1000)
		for i := 0; i < 128; i++ { // ~154 MiB
			_, err = f.WriteString(chunk)
			must(t, err)
		}
		must(t, f.Close())
		if stamped {
			must(t, os.WriteFile(filepath.Join(r.Home, ".cache", "claude-remote", "events.rotated"), nil, 0o600))
		}
		d := startDaemon(t, r)
		time.Sleep(1500 * time.Millisecond)
		hwm := hwmKBOf(d.pid())
		n := strings.Count(d.log(), "ev=rotate ")
		d.stop()
		st, _ := os.Stat(fx.events)
		t.Logf("stamped=%v: HWM %d kB, rotations %d, events now %d B", stamped, hwm, n, st.Size())
		if hwm > 96<<10 {
			t.Fatalf("stamped=%v: HWM %d kB for a 154 MiB source", stamped, hwm)
		}
		if (n == 1) == stamped || (!stamped && st.Size() > 1<<20) {
			t.Fatalf("stamped=%v: %d rotations, events %d B", stamped, n, st.Size())
		}
		must(t, os.Truncate(fx.events, 0)) // the disk space, before the next case
	}
}

// N-6: a connection closed because its socket write timed out says so in the log (cause=write-timeout, not eof).
func TestWriteTimeoutIsLoggedAsSuch(t *testing.T) {
	r := testenv.NewRoot(t)
	d := startDaemon(t, r, "CRD_TEST_WRITE_MS=1000")
	c := dial(t, r, hello("dev-wt", link1, 1, nil))
	b, _ := crp.Encode(crp.NewFrame(crp.PING, crp.Obj{"t": int64(1)}, nil))
	_ = c.nc.SetWriteDeadline(time.Now().Add(3 * time.Second))
	_, _ = c.nc.Write(bytes.Repeat(b, 10000)) // the PONGs fill the socket buffer: the daemon's write blocks
	closed := func() bool {
		for _, l := range strings.Split(d.log(), "\n") {
			if strings.Contains(l, "ev=conn-close") && strings.Contains(l, "dev=dev-wt ") {
				return true
			}
		}
		return false
	}
	waitFor(t, 8*time.Second, "conn-close of dev-wt", closed)
	for _, l := range strings.Split(d.log(), "\n") {
		if strings.Contains(l, "ev=conn-close") && strings.Contains(l, "dev=dev-wt ") && !strings.Contains(l, "cause=write-timeout") {
			t.Fatalf("close line %q", l)
		}
	}
	servesAnother(t, r, d, "dev-wt-alive")
}

// N-7 with a real tmux (verification A, probe TestOvTmuxTrunc): sessions P (256 B), P+10×A and P+10×B are never one
// name: P has its own row, the two longer ones are left out and logged.
func TestSessLongNamesAreNeverCut(t *testing.T) {
	r, sock := sessRig(t)
	pre := "claude-server-"
	p256 := pre + strings.Repeat("P", 256-len(pre))
	testenv.NewTmuxSessionArgv0(t, sock, p256, "exact-256")
	testenv.NewTmuxSessionArgv0(t, sock, p256+"AAAAAAAAAA", "longer-A")
	testenv.NewTmuxSessionArgv0(t, sock, p256+"BBBBBBBBBB", "longer-B")
	d := startDaemon(t, r, "CRD_TEST_SESS_MS=200")
	c := dial(t, r, hello("dev-n7", link1, 1, nil))
	time.Sleep(600 * time.Millisecond) // the panes have exec'd their argv[0]
	c.send(crp.SUB, crp.Obj{"st": "sess"}, nil)
	f := c.expect(crp.SNAP, 5*time.Second)
	rows, ok := f.Header["sessions"].([]any)
	if !ok {
		t.Fatalf("SNAP %v", f.Header)
	}
	names := map[string]bool{}
	for _, x := range rows {
		n, _ := crp.Str(x.(map[string]any), "name")
		if names[n] || len(n) > 256 {
			t.Fatalf("row name of %d bytes (duplicate %v)", len(n), names[n])
		}
		names[n] = true
	}
	if len(names) != 2 || !names[p256] || !names["claude-server-plain"] {
		t.Fatalf("%d rows", len(names))
	}
	if !strings.Contains(d.log(), "ev=sess-name-too-long sessions=2") {
		t.Fatal("no sess-name-too-long in the log")
	}
}
