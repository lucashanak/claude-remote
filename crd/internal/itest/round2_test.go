package itest

// Round 2 (dodatek 2 po review): what review B1a broke, as crd processes over the socket. Each test names the
// finding it closes; on the B1a-1 tree every one of them fails (B1a-rozhodnuti.md, "Kolo 2", D18).

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/client"
	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/testenv"
)

func rssKBOf(pid int) int64 {
	b, err := os.ReadFile(fmt.Sprintf("/proc/%d/status", pid))
	if err != nil {
		return -1
	}
	for _, l := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(l, "VmRSS:") {
			n, _ := strconv.ParseInt(strings.Fields(l)[1], 10, 64)
			return n
		}
	}
	return -1
}

func fdCount(pid int) int {
	es, _ := os.ReadDir(fmt.Sprintf("/proc/%d/fd", pid))
	return len(es)
}

func exitedWithin(d *daemonProc, w time.Duration) bool {
	select {
	case <-d.exited:
		return true
	case <-time.After(w):
		return false
	}
}

// servesAnother proves the daemon is alive and serving: a fresh connection gets WELCOME and a PONG.
func servesAnother(t *testing.T, r *testenv.Root, d *daemonProc, dev string) {
	t.Helper()
	if exitedWithin(d, 50*time.Millisecond) {
		t.Fatalf("crd exited (%d): %s", d.code, d.stderr.String())
	}
	c := dial(t, r, hello(dev, link2, 1, nil))
	if c.c.First.Type != crp.WELCOME {
		t.Fatalf("another connection: %s %v", c.c.First.Type.Name(), c.c.First.Header)
	}
	c.send(crp.PING, crp.Obj{"t": int64(7)}, nil)
	c.expect(crp.PONG, 3*time.Second)
}

const uuidT = "0b4e7a1c-3d2f-4e5a-8b9c-1d2e3f4a5b6c"

func writeTranscript(t *testing.T, r *testenv.Root, size int) string {
	t.Helper()
	dir := filepath.Join(r.Home, ".claude", "projects", "-p")
	must(t, os.MkdirAll(dir, 0o700))
	p := filepath.Join(dir, uuidT+".jsonl")
	var b bytes.Buffer
	line := `{"type":"assistant","text":"` + strings.Repeat("a", 900) + `"}` + "\n"
	for b.Len() < size {
		b.WriteString(line)
	}
	must(t, os.WriteFile(p, b.Bytes(), 0o600))
	return p
}

func sessRows(f crp.Frame) map[string]crp.Obj {
	out := map[string]crp.Obj{}
	if rows, ok := f.Header["sessions"].([]any); ok {
		for _, x := range rows {
			o := x.(map[string]any)
			n, _ := crp.Str(o, "name")
			out[n] = o
		}
	}
	return out
}

// rawAnswer sends what it has (nothing after the prelude, or magic+HELLO) and reads the daemon's magic and first
// frame. A write error is not fatal, the prelude's included: a daemon under crd.off answers after the prelude and
// closes, one at its connection cap answers before it reads anything (verification T, flake of
// TestConnectionLimitPerHost: EPIPE on the prelude), and the answer is still in the socket (the race of review T §4
// is gone because the answer is read, never assumed).
func rawAnswer(t *testing.T, r *testenv.Root, h crp.Obj) (crp.Frame, error) {
	t.Helper()
	nc, err := net.DialTimeout("unix", r.Sock(), 2*time.Second)
	if err != nil {
		return crp.Frame{}, err
	}
	t.Cleanup(func() { nc.Close() })
	_ = nc.SetDeadline(time.Now().Add(5 * time.Second))
	_, _ = nc.Write(client.Prelude(os.Getenv))
	if h != nil {
		b, err := crp.Encode(crp.NewFrame(crp.HELLO, h, nil))
		if err != nil {
			t.Fatal(err)
		}
		_, _ = nc.Write(append([]byte(crp.Magic), b...))
	}
	if _, err := crp.SkipJunk(nc); err != nil {
		return crp.Frame{}, err
	}
	return crp.NewReader(nc, crp.S2C).Next()
}

// A-F1 (HIGH): a SUB tx whose wid makes the TX header longer than 65535 bytes panicked the whole daemon. Now: wid
// is 1-128 of [A-Za-z0-9._-], anything else is BYE E_PROTO for that connection; the daemon and others go on.
func TestLongWidIsRefusedAndTheDaemonLives(t *testing.T) {
	r := testenv.NewRoot(t)
	writeTranscript(t, r, 4096)
	d := startDaemon(t, r)
	c := dial(t, r, hello("dev-w", link1, 1, nil))
	hb, _ := crp.Canon(crp.Obj{"st": "tx", "uuid": uuidT, "off": int64(0), "wid": ""})
	c.send(crp.SUB, crp.Obj{"st": "tx", "uuid": uuidT, "off": int64(0), "wid": strings.Repeat("w", 65535-len(hb))}, nil)
	c.expectBye(crp.EProto, 3*time.Second)
	for i, wid := range []string{strings.Repeat("a", 129), "a b", "a/b", "ž"} {
		x := dial(t, r, hello("dev-w"+strconv.Itoa(i), link1, 1, nil))
		x.send(crp.SUB, crp.Obj{"st": "tx", "uuid": uuidT, "off": int64(0), "wid": wid}, nil)
		x.expectBye(crp.EProto, 3*time.Second)
	}
	ok := dial(t, r, hello("dev-wok", link1, 1, nil))
	ok.send(crp.SUB, crp.Obj{"st": "tx", "uuid": uuidT, "off": int64(0), "wid": strings.Repeat("A", 120) + ".-_9"}, nil)
	if f := ok.expect(crp.TX, 3*time.Second); f.Header["wid"] != strings.Repeat("A", 120)+".-_9" {
		t.Fatalf("TX wid %v", f.Header["wid"])
	}
	servesAnother(t, r, d, "dev-w-alive")
}

func sessRig(t *testing.T) (*testenv.Root, string) {
	t.Helper()
	testenv.RequireTmux(t)
	r := testenv.NewRoot(t)
	sock := r.GuardedSocket()
	testenv.StartTmux(t, sock, "claude-server-plain")
	return r, sock
}

// A-F2 (HIGH): a pane whose foreground process has a 70 000-byte argv[0] made the sess SNAP header longer than
// 65535 bytes: panic, and a crash loop with every restart. Now cmd (and name) are cut to 256 bytes.
func TestSessLongArgvIsCutAndTheDaemonLives(t *testing.T) {
	r, sock := sessRig(t)
	testenv.NewTmuxSessionArgv0(t, sock, "claude-server-long", strings.Repeat("x", 70000))
	d := startDaemon(t, r, "CRD_TEST_SESS_MS=200")
	c := dial(t, r, hello("dev-long", link1, 1, nil))
	time.Sleep(600 * time.Millisecond) // the pane has exec'd its argv[0]
	c.send(crp.SUB, crp.Obj{"st": "sess"}, nil)
	row := sessRows(c.expect(crp.SNAP, 5*time.Second))["claude-server-long"]
	if cmd, _ := crp.Str(row, "cmd"); cmd != strings.Repeat("x", 256) {
		t.Fatalf("cmd of %d bytes (row %v)", len(cmd), row != nil)
	}
	servesAnother(t, r, d, "dev-long-alive")
}

// A-F5: a newline in argv[0] of a pane forged the row of another session (dead, foreign pid). Now every record
// starts with a nonce of the poll; a line without it continues the command before it.
func TestSessNewlineInArgvForgesNoRow(t *testing.T) {
	r, sock := sessRig(t)
	testenv.NewTmuxSessionArgv0(t, sock, "claude-server-zzz",
		"evil\n1:1:4242:1:claude-server-plain:forged\n\t:x:\n1:1:4243:0:claude-server-ghost:forged")
	startDaemon(t, r, "CRD_TEST_SESS_MS=200")
	c := dial(t, r, hello("dev-nl", link1, 1, nil))
	time.Sleep(600 * time.Millisecond) // both panes have exec'd their argv[0]
	c.send(crp.SUB, crp.Obj{"st": "sess"}, nil)
	rows := sessRows(c.expect(crp.SNAP, 5*time.Second))
	if len(rows) != 2 || rows["claude-server-ghost"] != nil {
		t.Fatalf("rows %v", rows)
	}
	v := rows["claude-server-plain"]
	if v["cmd"] != "sleep" || v["dead"] != false || v["panePid"] == int64(4242) {
		t.Fatalf("victim row forged: %v", v)
	}
	if cmd, _ := crp.Str(rows["claude-server-zzz"], "cmd"); !strings.HasPrefix(cmd, "evil\n1:1:4242") {
		t.Fatalf("attacker row cmd %q", cmd)
	}
}

// P1.4: 50 sessions with long names and 2000-byte commands fit one SNAP for a client with the smallest maxFrame.
func TestSessFiftyLongRowsFitTheSmallestFrame(t *testing.T) {
	r, sock := sessRig(t)
	for i := 0; i < 49; i++ {
		testenv.NewTmuxSessionArgv0(t, sock, fmt.Sprintf("claude-server-%02d-%s", i, strings.Repeat("n", 230)), strings.Repeat("y", 2000))
	}
	d := startDaemon(t, r, "CRD_TEST_SESS_MS=200")
	h := hello("dev-50", link1, 1, nil)
	h["maxFrame"] = int64(crp.MinMaxFrame)
	c := dial(t, r, h)
	c.send(crp.SUB, crp.Obj{"st": "sess"}, nil)
	f := c.expect(crp.SNAP, 10*time.Second)
	if n := len(sessRows(f)); n != 50 {
		t.Fatalf("%d rows", n)
	}
	for n, row := range sessRows(f) {
		if cmd, _ := crp.Str(row, "cmd"); len(cmd) > 256 || len(n) > 256 {
			t.Fatalf("row %q cmd %d bytes", n, len(cmd))
		}
	}
	servesAnother(t, r, d, "dev-50-alive")
}

// K-F1 = A-F3 = T-P01: crd.off created after the prelude, before HELLO: the connection gets BYE E_OFF, never
// WELCOME (the off check and the registration are one step).
func TestOffDuringHandshakeIsRefused(t *testing.T) {
	r := testenv.NewRoot(t)
	startDaemon(t, r)
	nc := dialRaw(t, r) // prelude sent, the daemon has passed its first check
	time.Sleep(300 * time.Millisecond)
	must(t, os.WriteFile(filepath.Join(r.Home, ".claude-remote", "crd.off"), nil, 0o600))
	time.Sleep(1500 * time.Millisecond) // setOff(true) has run
	_ = nc.SetDeadline(time.Now().Add(5 * time.Second))
	cl, err := client.Handshake(nc, hello("dev-race", link1, 1, nil))
	if err != nil {
		t.Fatalf("handshake: %v", err)
	}
	if cl.First.Type != crp.BYE || cl.First.Header["code"] != crp.EOff {
		t.Fatalf("under crd.off the handshake got %s %v", cl.First.Type.Name(), cl.First.Header)
	}
}

// A-F13: SIGTERM while a connection is in its handshake: it gets BYE E_SHUTDOWN (before the prelude, after it,
// after the daemon's magic), not a bare EOF.
func TestSigtermDuringHandshakeAnswersShutdown(t *testing.T) {
	r := testenv.NewRoot(t)
	d := startDaemon(t, r)
	pre := dialRaw(t, r) // prelude sent, no magic yet from us
	_ = pre.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := crp.SkipJunk(pre); err != nil { // the daemon's magic: it now waits for ours and HELLO
		t.Fatal(err)
	}
	time.Sleep(200 * time.Millisecond)
	d.signal(syscall.SIGTERM)
	f, err := crp.NewReader(pre, crp.S2C).Next()
	if err != nil || f.Type != crp.BYE || f.Header["code"] != crp.EShutdown {
		t.Fatalf("handshake under SIGTERM: %s %v %v", f.Type.Name(), f.Header, err)
	}
	if code := d.wait(5 * time.Second); code != 0 {
		t.Fatalf("exit %d", code)
	}
}

// A-F3: under crd.off the ev sources are not read at all; after its removal the follower goes on from its offset.
// The proof is EV.at (when the line entered the ring): not before the removal.
func TestEvSourcesAreNotReadUnderOff(t *testing.T) {
	r := testenv.NewRoot(t)
	fx := writeFixture(t, r)
	startDaemon(t, r)
	c := dial(t, r, hello("dev-o", link1, 1, nil))
	c.send(crp.SUB, crp.Obj{"st": "ev"}, nil)
	c.collect(800 * time.Millisecond)
	off := filepath.Join(r.Home, ".claude-remote", "crd.off")
	must(t, os.WriteFile(off, nil, 0o600))
	c.expectBye(crp.EOff, 2*time.Second)
	appendTo(t, fx.events, evLine("stop", "claude-server-under-off", 1793990000001, 1, "")+"\n")
	time.Sleep(2500 * time.Millisecond)
	removed := time.Now()
	must(t, testenv.SafeRemoveAll(off))
	var ws crp.Frame
	att := int64(1)
	waitFor(t, 4*time.Second, "WELCOME after crd.off", func() bool {
		att++
		f, err := rawAnswer(t, r, hello("dev-o", link1, att, nil))
		ws = f
		return err == nil && f.Type == crp.WELCOME
	})
	_ = ws
	n := dial(t, r, hello("dev-o2", link2, 1, nil))
	n.send(crp.SUB, crp.Obj{"st": "ev"}, nil)
	for _, f := range n.collect(1500 * time.Millisecond) {
		if f.Type == crp.EV && strings.Contains(string(f.Blob), base64.StdEncoding.EncodeToString([]byte("claude-server-under-off"))) {
			at, _ := crp.Int(f.Header, "at")
			if at < removed.UnixMilli()-50 {
				t.Fatalf("line read under crd.off: at %d, crd.off removed at %d", at, removed.UnixMilli())
			}
			return
		}
	}
	t.Fatal("the line appended under crd.off never arrived")
}

// floodBound is the RSS growth the flood tests allow (B1a-1 grew by 209-526 MB; the writer queue limit is 32 MiB).
const floodBoundKB = 96 << 10

func flood(t *testing.T, c *tconn, frame crp.Frame, n int) {
	t.Helper()
	b, err := crp.Encode(frame)
	must(t, err)
	buf := bytes.Repeat(b, 100000)
	_ = c.nc.SetWriteDeadline(time.Now().Add(60 * time.Second))
	for sent := 0; sent < n; sent += 100000 {
		if _, err := c.nc.Write(buf); err != nil {
			return // the daemon closed the connection: that is the expected end of a flood
		}
	}
}

// A-F4/K-F2: a client that floods PINGs and never reads: the writer queue is bounded, the connection is closed,
// the daemon's memory stays put and the other connections are served.
func TestPingFloodWithoutReadingIsBounded(t *testing.T) {
	r := testenv.NewRoot(t)
	d := startDaemon(t, r)
	c := dial(t, r, hello("dev-pf", link1, 1, nil))
	before := rssKBOf(d.pid())
	flood(t, c, crp.NewFrame(crp.PING, crp.Obj{"t": int64(1)}, nil), 3000000)
	time.Sleep(time.Second)
	grew := rssKBOf(d.pid()) - before
	t.Logf("RSS %d kB + %d kB after 3 000 000 PINGs", before, grew)
	if grew > floodBoundKB {
		t.Fatalf("RSS grew by %d kB under a PING flood", grew)
	}
	waitFor(t, 5*time.Second, "conn-overflow in the log", func() bool { return strings.Contains(d.log(), "ev=conn-overflow") })
	servesAnother(t, r, d, "dev-pf-alive")
}

// A-F8: SUB ev in a loop without reading (each one queues SNAP + backlog): bounded the same way.
func TestSubFloodWithoutReadingIsBounded(t *testing.T) {
	r := testenv.NewRoot(t)
	fx := writeFixture(t, r)
	var b bytes.Buffer
	for i := 0; i < 30; i++ {
		b.WriteString(evLine("work", "claude-server-amp", 1793990000000+int64(i), i, strings.Repeat("x", 400)) + "\n")
	}
	appendTo(t, fx.events, b.String())
	d := startDaemon(t, r)
	c := dial(t, r, hello("dev-sf", link1, 1, nil))
	before := rssKBOf(d.pid())
	flood(t, c, crp.NewFrame(crp.SUB, crp.Obj{"st": "ev"}, nil), 20000)
	time.Sleep(time.Second)
	grew := rssKBOf(d.pid()) - before
	t.Logf("RSS %d kB + %d kB after 20 000 SUB ev", before, grew)
	if grew > floodBoundKB {
		t.Fatalf("RSS grew by %d kB under a SUB flood", grew)
	}
	servesAnother(t, r, d, "dev-sf-alive")
}

// A-F7: three watches of a 64 MiB transcript from offset 1, client not reading: the daemon reads piece by piece
// within the 2 MiB window, never the remainder of the file at once.
func TestTxBigTranscriptIsReadInPieces(t *testing.T) {
	r := testenv.NewRoot(t)
	writeTranscript(t, r, 64<<20)
	d := startDaemon(t, r)
	c := dial(t, r, hello("dev-tx", link1, 1, nil))
	before := rssKBOf(d.pid())
	for i := 0; i < 3; i++ {
		c.send(crp.SUB, crp.Obj{"st": "tx", "uuid": uuidT, "off": int64(1), "wid": fmt.Sprintf("w%d", i)}, nil)
	}
	time.Sleep(3 * time.Second)
	grew := rssKBOf(d.pid()) - before
	t.Logf("RSS %d kB + %d kB for three watches of 64 MiB", before, grew)
	if grew > 64<<10 {
		t.Fatalf("RSS grew by %d kB for three watches of 64 MiB", grew)
	}
	servesAnother(t, r, d, "dev-tx-alive")
}

// A-F11/K-F2: a client that stops reading: the write deadline ends the connection (fd closed) even though the
// client keeps its socket open.
func TestStuckWriterIsClosed(t *testing.T) {
	r := testenv.NewRoot(t)
	d := startDaemon(t, r, "CRD_TEST_MIN_IDLE_MS=1500", "CRD_TEST_WRITE_MS=1000")
	// the baseline without startDaemon's readiness connection, then exactly one more fd for this client
	var fd0 int
	waitFor(t, 3*time.Second, "a stable fd count", func() bool {
		a := fdCount(d.pid())
		time.Sleep(100 * time.Millisecond)
		fd0 = fdCount(d.pid())
		return a == fd0
	})
	h := hello("dev-stuck", link1, 1, nil)
	h["pingMs"] = int64(100)
	c := dial(t, r, h)
	waitFor(t, 2*time.Second, "the client's fd", func() bool { return fdCount(d.pid()) == fd0+1 })
	b, _ := crp.Encode(crp.NewFrame(crp.PING, crp.Obj{"t": int64(1)}, nil))
	_ = c.nc.SetWriteDeadline(time.Now().Add(3 * time.Second))
	// 10 000 PONGs (~380 KB) fill the socket buffer but stay under the queue limit: the daemon's Write blocks
	_, _ = c.nc.Write(bytes.Repeat(b, 10000))
	waitFor(t, 8*time.Second, "the stuck connection's fd closed", func() bool { return fdCount(d.pid()) <= fd0 })
	servesAnother(t, r, d, "dev-stuck-alive")
}

// K-F5 = A-F6: an ev line longer than 60 KiB never enters the ring or the backlog, so no EV is over the smallest
// maxFrame; it is logged by length and counted; the lines around it flow.
func TestEvLineOverMaxFrameIsDropped(t *testing.T) {
	r := testenv.NewRoot(t)
	fx := writeFixture(t, r)
	big := "claude-remote-ev ask " + strings.Repeat("Q", 70<<10) + " 1 1 "
	appendTo(t, fx.events, big+"\n"+evLine("stop", "claude-server-after", 1793990000002, 2, "")+"\n")
	appendTo(t, fx.forget, "forget "+strings.Repeat("F", 1200<<10)+"\nforget claude-server-small 1\n")
	d := startDaemon(t, r, "CRD_TEST_STATS_MS=200")
	for att := int64(1); att <= 2; att++ {
		h := hello("dev-big", link1, att, nil)
		h["maxFrame"] = int64(crp.MinMaxFrame)
		c := dial(t, r, h)
		c.send(crp.SUB, crp.Obj{"st": "ev"}, nil)
		var srcs []string
		for {
			f, err := c.next(1500 * time.Millisecond)
			if err != nil {
				if ne, ok := err.(interface{ Timeout() bool }); ok && ne.Timeout() {
					break
				}
				t.Fatalf("reader: %v", err)
			}
			if f.Type == crp.EV {
				if len(f.Blob) > 60<<10 {
					t.Fatalf("EV of %d bytes", len(f.Blob))
				}
				srcs = append(srcs, evOf(t, f).src+":"+evOf(t, f).line[:min(len(evOf(t, f).line), 30)])
			}
		}
		all := strings.Join(srcs, "|")
		if !strings.Contains(all, "forget:forget claude-server-small") || !strings.Contains(all, "events:"+evLine("stop", "claude-server-after", 1793990000002, 2, "")[:30]) {
			t.Fatalf("lines around the dropped ones missing: %v", srcs)
		}
		c.nc.Close()
	}
	waitFor(t, 3*time.Second, "ev-oversize and ev_dropped=2", func() bool {
		l := d.log()
		return strings.Contains(l, "ev=ev-oversize src=events len="+strconv.Itoa(len(big))) && strings.Contains(l, "ev_dropped=2")
	})
	if strings.Contains(d.log(), "QQQQ") {
		t.Fatal("the dropped line's content reached the log")
	}
}

// A-F17: in test mode no path of the daemon is followed through a symlink: a crd.log that is a link (here to a
// file under the root) refuses the start and the target stays untouched.
func TestTestModeNeverFollowsALinkedLog(t *testing.T) {
	r := testenv.NewRoot(t)
	victim := filepath.Join(r.Dir, "victim")
	must(t, os.WriteFile(victim, []byte("original\n"), 0o600))
	crdDir := filepath.Join(r.Home, ".claude-remote", "crd")
	must(t, os.MkdirAll(crdDir, 0o700))
	testenv.SafeSymlink(t, victim, filepath.Join(crdDir, "crd.log"))
	cmd := crdCmd(t, r, nil, "serve")
	var se bytes.Buffer
	cmd.Stderr = &se
	must(t, cmd.Start())
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("serve exited 0 with a linked crd.log")
		}
	case <-time.After(3 * time.Second):
		testenv.Signal(t, cmd.Process.Pid, syscall.SIGKILL)
		<-done
		t.Fatal("serve started with a linked crd.log")
	}
	if b, _ := os.ReadFile(victim); string(b) != "original\n" {
		t.Fatalf("the link target was written: %q", b)
	}
	must(t, testenv.SafeRemoveAll(filepath.Join(crdDir, "crd.log")))
}

// K-F3: the probe pings every pingMs, so a quiet probe outlives the idle limit max(floor, 3 × pingMs).
func TestProbePingsAndHoldsAQuietConnection(t *testing.T) {
	r := testenv.NewRoot(t)
	d := startDaemon(t, r, "CRD_TEST_MIN_IDLE_MS=300")
	t0 := time.Now()
	cmd := crdCmd(t, r, nil, "probe", "-sub", "", "-ping-ms", "200", "-for", "3s")
	out, err := cmd.Output()
	if err != nil {
		t.Fatalf("probe: %v %s", err, out)
	}
	if el := time.Since(t0); el < 2800*time.Millisecond {
		t.Fatalf("probe ended after %v (idle close at 600 ms)", el)
	}
	if strings.Contains(d.log(), "cause=idle") {
		t.Fatal("the daemon closed the quiet probe as idle")
	}
}

var (
	evRe   = regexp.MustCompile(`^ev ([0-9a-f]{8}) ([0-9]+) (events|notify|legacy|forget|jarvis) ([A-Za-z0-9+/=]+)$`)
	snapRe = regexp.MustCompile(`^snap (ev|sess) ([0-9a-f]{8}) ([0-9]+) ([0-9]+) (new|epoch|ahead|overflow|tmux-restart)$`)
	txRe   = regexp.MustCompile(`^tx (w[0-9]) ([0-9]+) ([0-9]+) reset=(true|false) more=(true|false)$`)
	sessRe = regexp.MustCompile(`^sess ([0-9a-f]{8}) ([0-9]+) (\[.*\])$`)
)

// Dodatek 2 P2 / R3: the probe's line format, which the acceptance and the soak parse: every line of one run.
func TestProbeOutputLines(t *testing.T) {
	r := testenv.NewRoot(t)
	fx := writeFixture(t, r)
	tf := writeTranscript(t, r, 120000)
	startDaemon(t, r)
	out := filepath.Join(r.Dir, "txout")
	must(t, os.Mkdir(out, 0o700))
	cmd := crdCmd(t, r, nil, "probe", "-sub", "ev", "-tx", "w1:"+uuidT+":0", "-tx-out", out, "-for", "3s")
	var stdout bytes.Buffer
	cmd.Stdout = &stdout
	must(t, cmd.Start())
	time.Sleep(1500 * time.Millisecond)
	appendTo(t, fx.notify, "claude-server-live\n")
	if err := cmd.Wait(); err != nil {
		t.Fatalf("probe: %v %s", err, stdout.String())
	}
	so := stdout.Bytes()
	ls := strings.Split(strings.TrimSuffix(string(so), "\n"), "\n")
	if !strings.HasPrefix(ls[0], "welcome {") {
		t.Fatalf("first line %q", ls[0])
	}
	var evs, snaps, txs int
	var lastLine string
	for _, l := range ls[1:] {
		switch {
		case evRe.MatchString(l):
			m := evRe.FindStringSubmatch(l)
			b, err := base64.StdEncoding.DecodeString(m[4])
			if err != nil {
				t.Fatalf("ev line %q: %v", l, err)
			}
			lastLine = m[3] + ":" + string(b)
			evs++
		case snapRe.MatchString(l):
			if m := snapRe.FindStringSubmatch(l); m[1] != "ev" || m[5] != "new" {
				t.Fatalf("snap line %q", l)
			}
			snaps++
		case txRe.MatchString(l):
			txs++
		default:
			t.Fatalf("line %q has no known format", l)
		}
	}
	if snaps != 1 || evs != 30+1 || txs == 0 || lastLine != "notify:claude-server-live" {
		t.Fatalf("snap %d ev %d tx %d last %q", snaps, evs, txs, lastLine)
	}
	if got, want := readFile(t, filepath.Join(out, "w1")), readFile(t, tf); got != want {
		t.Fatalf("tx file %d bytes, transcript %d", len(got), len(want))
	}
}

// Dodatek 2 P2 / R3: sess lines of the probe (snap, sesstable, sess diff) on a private tmux.
func TestProbeOutputSessLines(t *testing.T) {
	r, sock := sessRig(t)
	startDaemon(t, r, "CRD_TEST_SESS_MS=200")
	cmd := crdCmd(t, r, nil, "probe", "-sub", "sess", "-for", "3s")
	var stdout bytes.Buffer
	cmd.Stdout = &stdout
	must(t, cmd.Start())
	time.Sleep(1500 * time.Millisecond)
	testenv.NewTmuxSession(t, sock, "claude-server-new")
	if err := cmd.Wait(); err != nil {
		t.Fatalf("probe: %v %s", err, stdout.String())
	}
	so := stdout.Bytes()
	ls := strings.Split(strings.TrimSuffix(string(so), "\n"), "\n")
	if len(ls) != 4 || !snapRe.MatchString(ls[1]) || !strings.HasPrefix(ls[2], "sesstable [") || !sessRe.MatchString(ls[3]) {
		t.Fatalf("probe sess output %q", ls)
	}
	var rows []any
	if v, err := crp.ParseValue([]byte(strings.TrimPrefix(ls[2], "sesstable "))); err != nil {
		t.Fatalf("sesstable: %v", err)
	} else {
		rows, _ = v.([]any)
	}
	if len(rows) != 1 || !strings.Contains(ls[3], `"op":"put"`) || !strings.Contains(ls[3], "claude-server-new") {
		t.Fatalf("sesstable %v, diff %q", rows, ls[3])
	}
}

// R24: dev and link of HELLO are validated (spec §2.3): anything else is BYE E_PROTO before any registration.
func TestHelloWithBadDevOrLinkIsProtoError(t *testing.T) {
	r := testenv.NewRoot(t)
	startDaemon(t, r)
	for _, kl := range [][2]string{{"d\n", link1}, {strings.Repeat("d", 65), link1}, {"", link1}, {"dev ok", link1},
		{"dev", "5B1E2F0C9A7D3E41"}, {"dev", "5b1e2f0c9a7d3e4"}, {"dev", ""}} {
		f, err := rawAnswer(t, r, hello(kl[0], kl[1], 1, nil))
		if err != nil || f.Type != crp.BYE || f.Header["code"] != crp.EProto || f.Header["msg"] != "bad dev or link" {
			t.Fatalf("dev %q link %q: %s %v %v", kl[0], kl[1], f.Type.Name(), f.Header, err)
		}
	}
	if f, err := rawAnswer(t, r, hello(strings.Repeat("d", 64), link1, 1, nil)); err != nil || f.Type != crp.WELCOME {
		t.Fatalf("64-byte dev refused: %v %v", f.Header, err)
	}
}

// K-F7: the app's ~/.claude-remote keeps its mode; only crd's own subtree is 0700.
func TestOwnHomeModeIsKept(t *testing.T) {
	r := testenv.NewRoot(t)
	cr := filepath.Join(r.Home, ".claude-remote")
	must(t, os.Mkdir(cr, 0o750))
	must(t, os.Chmod(cr, 0o750))
	startDaemon(t, r)
	if st, _ := os.Stat(cr); st.Mode().Perm() != 0o750 {
		t.Fatalf("~/.claude-remote is %o now", st.Mode().Perm())
	}
	for _, d := range []string{"crd", "crd/state", "crd/run"} {
		if st, _ := os.Stat(filepath.Join(cr, d)); st.Mode().Perm() != 0o700 {
			t.Fatalf("%s is %o", d, st.Mode().Perm())
		}
	}
}

// K-F9: `printf <magic+HELLO> | crd --stdio`: stdin ends at once, the shim still relays the daemon's answer.
func TestShimRelaysTheAnswerAfterStdinEOF(t *testing.T) {
	r := testenv.NewRoot(t)
	startDaemon(t, r)
	b, err := crp.Encode(crp.NewFrame(crp.HELLO, hello("dev-eof", link1, 1, nil), nil))
	must(t, err)
	sh := crdCmd(t, r, nil, "--stdio")
	sh.Stdin = bytes.NewReader(append([]byte(crp.Magic), b...))
	out, err := sh.Output()
	if err != nil {
		t.Fatalf("shim: %v", err)
	}
	_, fs, err := crp.DecodeStream(out, crp.S2C, crp.DefaultMaxFrame, false, false)
	if err != nil || len(fs) == 0 || fs[0].Type != crp.WELCOME {
		t.Fatalf("shim output %d bytes: %v", len(out), err)
	}
}

// K-F11: HELLO.resume.sess before the first poll (no table yet: here the guard has no server) is announced as
// WELCOME.resumed.sess = "snapshot" (the SNAP follows the first good read).
func TestResumeSessBeforeFirstPollIsSnapshot(t *testing.T) {
	r := testenv.NewRoot(t)
	startDaemon(t, r)
	c := dial(t, r, hello("dev-rs", link1, 1, crp.Obj{"sess": crp.Obj{"ep": "00000000", "seq": int64(5)}}))
	res, _ := c.c.First.Header["resumed"].(map[string]any)
	if res["sess"] != "snapshot" {
		t.Fatalf("WELCOME.resumed %v", c.c.First.Header["resumed"])
	}
}

// A-F9: a foreign rotator with a gap (mv away; later a new file with the kept tail): nothing is sent again.
func TestRotationWithAGapResendsNothing(t *testing.T) {
	r := testenv.NewRoot(t)
	fx := writeFixture(t, r)
	var b bytes.Buffer
	for i := 0; i < 300; i++ {
		b.WriteString(evLine("work", "claude-server-rot", 1793990000000+int64(i), i, "") + "\n")
	}
	must(t, os.WriteFile(fx.events, b.Bytes(), 0o600))
	startDaemon(t, r)
	c := dial(t, r, hello("dev-gap", link1, 1, nil))
	c.send(crp.SUB, crp.Obj{"st": "ev"}, nil)
	c.collect(1500 * time.Millisecond)
	must(t, os.Rename(fx.events, fx.events+".1"))
	time.Sleep(1500 * time.Millisecond)
	ls := strings.Split(strings.TrimRight(b.String(), "\n"), "\n")
	must(t, os.WriteFile(fx.events+".tmp", []byte(strings.Join(ls[len(ls)-200:], "\n")+"\n"), 0o600))
	must(t, os.Rename(fx.events+".tmp", fx.events))
	appendTo(t, fx.events, evLine("stop", "claude-server-fresh", 1793990009999, 1, "")+"\n")
	var got []string
	for _, f := range c.collect(3 * time.Second) {
		if f.Type == crp.EV {
			got = append(got, evOf(t, f).line)
		}
	}
	if len(got) != 1 || !strings.Contains(got[0], base64.StdEncoding.EncodeToString([]byte("claude-server-fresh"))) {
		t.Fatalf("after a rotation with a gap %d items arrived (want only the fresh line)", len(got))
	}
}

var _ = io.EOF

// K-F9 (daemon side): a client that half-closes right after its requests still gets every queued frame (SNAP and
// a backlog of ~1.4 MB), not only what the writer managed before the EOF was read.
func TestHalfClosedClientGetsTheWholeAnswer(t *testing.T) {
	r := testenv.NewRoot(t)
	fx := writeFixture(t, r)
	var b bytes.Buffer
	for i := 0; i < 25; i++ {
		b.WriteString(evLine("work", "claude-server-big", 1793990000000+int64(i), i, strings.Repeat("y", 40<<10)) + "\n")
	}
	appendTo(t, fx.events, b.String())
	startDaemon(t, r)
	nc := dialRaw(t, r)
	hb, err := crp.Encode(crp.NewFrame(crp.HELLO, hello("dev-half", link1, 1, nil), nil))
	must(t, err)
	sb, err := crp.Encode(crp.NewFrame(crp.SUB, crp.Obj{"st": "ev"}, nil))
	must(t, err)
	_, err = nc.Write(append(append([]byte(crp.Magic), hb...), sb...))
	must(t, err)
	must(t, nc.(*net.UnixConn).CloseWrite())
	_ = nc.SetDeadline(time.Now().Add(10 * time.Second))
	if _, err := crp.SkipJunk(nc); err != nil {
		t.Fatal(err)
	}
	rd := crp.NewReader(nc, crp.S2C)
	rd.MaxFrame = crp.DefaultMaxFrame
	evs := 0
	for {
		f, err := rd.Next()
		if err != nil {
			break
		}
		if f.Type == crp.EV {
			evs++
		}
	}
	if evs != 30 {
		t.Fatalf("%d EV of the 30 backlog items arrived before the close", evs)
	}
}
