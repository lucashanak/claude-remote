package itest

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/rand"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/testenv"
)

func init() { needTmuxLock = true }

// Lines in the shape hook-ev.sh writes (HookCommands.kt: printf 'claude-remote-ev %s %s %s %s %s\n'), with
// synthetic session names; an empty extra leaves a trailing space.
func evLine(ev, name string, ms int64, pid int, extra string) string {
	nb := base64.StdEncoding.EncodeToString([]byte(name))
	eb := ""
	if extra != "" {
		eb = base64.StdEncoding.EncodeToString([]byte(extra))
	}
	return fmt.Sprintf("claude-remote-ev %s %s %d %d %s", ev, nb, ms, pid, eb)
}

type fixture struct {
	events, notify, legacy, forget, jarvis string
}

func writeFixture(t *testing.T, r *testenv.Root) fixture {
	t.Helper()
	f := fixture{
		events: filepath.Join(r.Home, ".cache", "claude-remote", "events"),
		notify: filepath.Join(r.Home, ".cache", "claude-remote", "notify"),
		jarvis: filepath.Join(r.Home, ".cache", "claude-remote", "jarvis-alerts"),
		forget: filepath.Join(r.Home, ".claude-remote", "forget.log"),
		legacy: filepath.Join(r.Dir, "tmp", "claude-notify"),
	}
	for _, d := range []string{filepath.Dir(f.events), filepath.Dir(f.forget)} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	var ev bytes.Buffer
	for i := 1; i <= 30; i++ {
		extra := ""
		if i%2 == 0 {
			extra = "source=startup exe=claude"
		}
		ev.WriteString(evLine("work", fmt.Sprintf("claude-server-fixture-%d", i%3), 1793989999000+int64(i), 4000+i, extra) + "\n")
	}
	ev.WriteString(evLine("ask", "claude-server-žluťoučký", 1793989999999, 4100, "Jakou barvu má kůň?") + "\n")
	must(t, os.WriteFile(f.events, ev.Bytes(), 0o600))
	must(t, os.WriteFile(f.notify, []byte("claude-server-fixture-1\nclaude-server-fixture-2\n"), 0o600))
	must(t, os.WriteFile(f.legacy, []byte("claude-server-legacy-1\n"), 0o600))
	must(t, os.WriteFile(f.forget, []byte("forget claude-server-old 1793989990000\n"), 0o600))
	must(t, os.WriteFile(f.jarvis, []byte("{\"alert\":\"synthetic\"}\n"), 0o600))
	return f
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func appendTo(t *testing.T, p, s string) {
	t.Helper()
	f, err := os.OpenFile(p, os.O_WRONLY|os.O_APPEND, 0o600)
	must(t, err)
	_, err = f.WriteString(s)
	must(t, err)
	f.Close()
}

type evItem struct {
	seq  int64
	src  string
	line string
	ep   string
}

func evOf(t *testing.T, f crp.Frame) evItem {
	seq, _ := crp.Int(f.Header, "seq")
	src, _ := crp.Str(f.Header, "src")
	ep, _ := crp.Str(f.Header, "ep")
	return evItem{seq, src, string(f.Blob), ep}
}

// collect reads frames until quiet for idle.
func (c *tconn) collect(idle time.Duration) []crp.Frame {
	var out []crp.Frame
	for {
		f, err := c.next(idle)
		if err != nil {
			return out
		}
		out = append(out, f)
	}
}

// spec §4.4, §4.7, §4.3 (vectors 08, 28): seeding of five sources in order, live lines byte for byte, a partial
// line only once complete, and a restart is a new epoch whose SNAP backlog has the seeded lines.
func TestEvSeedFollowRestart(t *testing.T) {
	r := testenv.NewRoot(t)
	fx := writeFixture(t, r)
	d := startDaemon(t, r)
	c := dial(t, r, hello("dev-ev", link1, 1, nil))
	ep, _ := crp.Sub(c.c.First.Header, "streams")
	evInfo, _ := crp.Sub(ep, "ev")
	if last, _ := crp.Int(evInfo, "last"); last != 30 {
		t.Fatalf("WELCOME ev last %d, want 30 (25+2+1+1+1)", last)
	}
	c.send(crp.SUB, crp.Obj{"st": "ev"}, nil)
	snap := c.expect(crp.SNAP, 3*time.Second)
	if snap.Header["reason"] != "new" {
		t.Fatal(snap.Header)
	}
	var back []evItem
	for len(back) < 30 {
		f := c.expect(crp.EV, 2*time.Second)
		back = append(back, evOf(t, f))
	}
	var srcs []string
	for _, b := range back {
		srcs = append(srcs, b.src)
	}
	want := strings.Repeat("events,", 25) + "notify,notify,legacy,forget,jarvis"
	if strings.Join(srcs, ",") != want {
		t.Fatalf("seed order %s", strings.Join(srcs, ","))
	}
	lines, _ := os.ReadFile(fx.events)
	all := strings.Split(strings.TrimSuffix(string(lines), "\n"), "\n")
	if back[24].line != all[len(all)-1] || back[0].line != all[len(all)-25] {
		t.Fatal("backlog is not the last 25 events lines byte for byte")
	}
	// live: a full line, a line with a trailing space, a partial that waits for its newline
	l1 := evLine("stop", "claude-server-fixture-9", 1793990000001, 4200, "")
	appendTo(t, fx.events, l1+"\n")
	if it := evOf(t, c.expect(crp.EV, 3*time.Second)); it.line != l1 || it.seq != 31 || !strings.HasSuffix(it.line, " ") {
		t.Fatalf("live item %+v", it)
	}
	appendTo(t, fx.events, "claude-remote-ev ask")
	if f, err := c.next(1500 * time.Millisecond); err == nil {
		t.Fatalf("partial line emitted: %q", f.Blob)
	}
	appendTo(t, fx.events, " Yg== 1 2 \n")
	if it := evOf(t, c.expect(crp.EV, 3*time.Second)); it.line != "claude-remote-ev ask Yg== 1 2 " {
		t.Fatalf("completed line %q", it.line)
	}
	appendTo(t, fx.forget, "forget claude-server-x 1\n")
	if it := evOf(t, c.expect(crp.EV, 3*time.Second)); it.src != "forget" {
		t.Fatalf("forget line from %s", it.src)
	}
	oldEp := back[0].ep
	// restart: new epoch, the resume with the old epoch is SNAP(epoch) with the seeded backlog
	d.signal(syscall.SIGTERM)
	c.expectBye(crp.EShutdown, 3*time.Second)
	d.wait(5 * time.Second)
	startDaemon(t, r)
	c2 := dial(t, r, hello("dev-ev", link1, 2, crp.Obj{"ev": crp.Obj{"ep": oldEp, "seq": int64(33)}}))
	if rs, _ := crp.Sub(c2.c.First.Header, "resumed"); rs["ev"] != "snapshot" {
		t.Fatalf("resumed %v", c2.c.First.Header["resumed"])
	}
	s2 := c2.expect(crp.SNAP, 3*time.Second)
	if s2.Header["reason"] != "epoch" || s2.Header["ep"] == oldEp {
		t.Fatalf("after restart %v", s2.Header)
	}
	got := map[string]bool{}
	for _, f := range c2.collect(time.Second) {
		if f.Type == crp.EV {
			got[string(f.Blob)] = true
		}
	}
	if !got[l1] || !got["forget claude-server-x 1"] {
		t.Fatal("restart backlog misses the lines written before the restart")
	}
}

// Rotation stops under crd.off and resumes after it (spec §1.4, §4.7).
func TestOwnRotationNotUnderOff(t *testing.T) {
	r := testenv.NewRoot(t)
	fx := writeFixture(t, r)
	var b bytes.Buffer
	for i := 0; i < 1200; i++ {
		b.WriteString(evLine("work", "claude-server-r", int64(i), 1, "") + "\n")
	}
	must(t, os.WriteFile(fx.events, b.Bytes(), 0o600))
	off := filepath.Join(r.Home, ".claude-remote", "crd.off")
	must(t, os.WriteFile(off, nil, 0o600))
	startDaemon(t, r, "CRD_TEST_ROTATE_MS=200")
	time.Sleep(1500 * time.Millisecond)
	if n := strings.Count(readFile(t, fx.events), "\n"); n != 1200 {
		t.Fatalf("rotated under crd.off (%d lines)", n)
	}
	must(t, testenv.SafeRemoveAll(off))
	waitFor(t, 5*time.Second, "rotation after crd.off", func() bool { return strings.Count(readFile(t, fx.events), "\n") == 200 })
	// the stamp is touched after the rename (like Stage A's mv, then touch): wait for it, never race it
	waitFor(t, 2*time.Second, "the events.rotated stamp", func() bool { _, err := os.Stat(fx.events + ".rotated"); return err == nil })
	if n := strings.Count(readFile(t, fx.notify), "\n"); n != 2 {
		t.Fatal("notify touched")
	}
}

func readFile(t *testing.T, p string) string {
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// tmuxRig is a daemon with a private tmux server behind its guarded socket and a recording tmux first on PATH.
type tmuxRig struct {
	r    *testenv.Root
	d    *daemonProc
	rec  string
	sock string
}

func newTmuxRig(t *testing.T, sessions []string, env ...string) *tmuxRig {
	t.Helper()
	testenv.RequireTmux(t)
	r := testenv.NewRoot(t)
	g := &tmuxRig{r: r, sock: r.GuardedSocket(), rec: filepath.Join(r.Dir, "rec")}
	must(t, os.Mkdir(g.rec, 0o700))
	if len(sessions) > 0 {
		testenv.StartTmux(t, g.sock, sessions...)
	}
	path := testenv.RecordingTmux(t, g.rec)
	g.d = startDaemon(t, r, append([]string{path, "CRD_TEST_SESS_MS=200"}, env...)...)
	return g
}

func (g *tmuxRig) calls() []string { return testenv.TmuxCalls(g.rec) }

func sessionNames(t *testing.T, sessions any) []string {
	var out []string
	rows, _ := sessions.([]any)
	for _, r := range rows {
		m, _ := r.(map[string]any)
		n, _ := m["name"].(string)
		out = append(out, n)
	}
	sort.Strings(out)
	return out
}

// spec §4.5 on a private tmux: SNAP with the table, put/del diffs, every call is `-N -S <guarded>` with an allowed
// verb, and TMUX/TMUX_PANE given to crd on purpose never reach its tmux children (dodatek T2).
func TestSessOverPrivateTmux(t *testing.T) {
	r0 := testenv.NewRoot(t) // only for a fake TMUX path under the root
	g := newTmuxRig(t, []string{"claude-server-a", "claude-server-b", "claude-server-c"},
		"TMUX="+filepath.Join(r0.Dir, "fake")+",1,0", "TMUX_PANE=%9")
	c := dial(t, g.r, hello("dev-s", link1, 1, nil))
	c.send(crp.SUB, crp.Obj{"st": "sess"}, nil)
	snap := c.expect(crp.SNAP, 5*time.Second)
	if names := sessionNames(t, snap.Header["sessions"]); strings.Join(names, ",") != "claude-server-a,claude-server-b,claude-server-c" {
		t.Fatalf("table %v", names)
	}
	rows := snap.Header["sessions"].([]any)
	row := rows[0].(map[string]any)
	if row["cmd"] != "sleep" || row["dead"] != false {
		t.Fatalf("row %v", row)
	}
	if pp, _ := row["panePid"].(int64); pp <= 1 {
		t.Fatal("panePid")
	}
	testenv.NewTmuxSession(t, g.sock, "claude-server-d")
	ev := c.expect(crp.EV, 5*time.Second)
	if !strings.Contains(string(crp.MustCanon(ev.Header["ops"])), `"op":"put"`) || !strings.Contains(string(crp.MustCanon(ev.Header["ops"])), "claude-server-d") {
		t.Fatalf("put %v", ev.Header)
	}
	testenv.KillTmuxSession(t, g.sock, "claude-server-a")
	ev = c.expect(crp.EV, 5*time.Second)
	if string(crp.MustCanon(ev.Header["ops"])) != `[{"name":"claude-server-a","op":"del"}]` {
		t.Fatalf("del %s", crp.MustCanon(ev.Header["ops"]))
	}
	calls := g.calls()
	if len(calls) == 0 {
		t.Fatal("no tmux call recorded")
	}
	for _, l := range calls {
		if l == "TMUX-SET" || l == "TMUX_PANE-SET" {
			t.Fatal("TMUX reached a tmux child of crd")
		}
		if l != "-V" && !strings.HasPrefix(l, "-N -S "+g.sock+" list-") { // -V: the gate's version check, no server
			t.Fatalf("call %q", l)
		}
	}
	if pid, ok := c.c.First.Header["host"].(map[string]any)["tmuxPid"].(int64); ok && pid != int64(testenv.TmuxPid(t, g.sock)) {
		t.Fatal("WELCOME host.tmuxPid is not the private server")
	}
}

// spec §4.5: a replaced tmux server is SNAP{tmux-restart}, never del per row; crd.off mid-subscription ⇒ BYE
// within 1 s and the polling stops (counted in tmux calls, not time).
func TestSessServerChangeThenOff(t *testing.T) {
	g := newTmuxRig(t, []string{"claude-server-a", "claude-server-b"})
	c := dial(t, g.r, hello("dev-c", link1, 1, nil))
	c.send(crp.SUB, crp.Obj{"st": "sess"}, nil)
	c.expect(crp.SNAP, 5*time.Second)
	oldPid := testenv.TmuxPid(t, g.sock)
	testenv.KillTmux(t, g.sock)
	testenv.StartTmux(t, g.sock, "claude-server-z")
	if testenv.TmuxPid(t, g.sock) == oldPid {
		t.Fatal("server not replaced")
	}
	for {
		f := c.expect(crp.SNAP, 6*time.Second)
		if f.Header["reason"] == "tmux-restart" {
			if names := sessionNames(t, f.Header["sessions"]); strings.Join(names, ",") != "claude-server-z" {
				t.Fatalf("table after restart %v", names)
			}
			break
		}
	}
	// no EV(del) was sent before the SNAP: expect() above would have skipped it, so check the order directly
	off := filepath.Join(g.r.Home, ".claude-remote", "crd.off")
	t0 := time.Now()
	must(t, os.WriteFile(off, nil, 0o600))
	c.expectBye(crp.EOff, 1500*time.Millisecond)
	if time.Since(t0) > 1200*time.Millisecond {
		t.Fatal("BYE E_OFF later than 1 s")
	}
	time.Sleep(500 * time.Millisecond) // a poll already running when the file appeared may finish
	n := len(g.calls())
	time.Sleep(1500 * time.Millisecond) // 7 poll intervals of 200 ms
	if m := len(g.calls()); m != n {
		t.Fatalf("sess kept polling under crd.off: %d -> %d calls", n, m)
	}
}

// spec §4.5 order check: the server change never produces an EV with a del.
func TestSessServerChangeSendsNoDel(t *testing.T) {
	g := newTmuxRig(t, []string{"claude-server-a", "claude-server-b"})
	c := dial(t, g.r, hello("dev-c2", link1, 1, nil))
	c.send(crp.SUB, crp.Obj{"st": "sess"}, nil)
	c.expect(crp.SNAP, 5*time.Second)
	testenv.KillTmux(t, g.sock)
	testenv.StartTmux(t, g.sock, "claude-server-y")
	deadline := time.Now().Add(6 * time.Second)
	for time.Now().Before(deadline) {
		f, err := c.next(time.Until(deadline))
		if err != nil {
			t.Fatal("no SNAP(tmux-restart)")
		}
		if f.Type == crp.EV && strings.Contains(string(crp.MustCanon(f.Header["ops"])), `"del"`) {
			t.Fatalf("del sent on a server change: %s", crp.MustCanon(f.Header["ops"]))
		}
		if f.Type == crp.SNAP && f.Header["reason"] == "tmux-restart" {
			return
		}
	}
	t.Fatal("no SNAP(tmux-restart)")
}

// spec §6.3: missing socket, two listeners, a socket without a listener ⇒ no tmux call at all and nothing sent.
func TestSocketGuardRefusesWithoutCallingTmux(t *testing.T) {
	for _, tc := range []string{"missing", "two-listeners", "no-listener"} {
		t.Run(tc, func(t *testing.T) {
			testenv.RequireTmux(t)
			r := testenv.NewRoot(t)
			sock := r.GuardedSocket()
			must(t, os.MkdirAll(filepath.Dir(sock), 0o700))
			switch tc {
			case "two-listeners":
				a := listenUnix(t, sock)
				must(t, testenv.SafeRemoveAll(sock))
				b := listenUnix(t, sock)
				defer a.Close()
				defer b.Close()
			case "no-listener":
				l := listenUnix(t, sock)
				l.Close()
			}
			rec := filepath.Join(r.Dir, "rec")
			must(t, os.Mkdir(rec, 0o700))
			startDaemon(t, r, testenv.RecordingTmux(t, rec), "CRD_TEST_SESS_MS=100")
			c := dial(t, r, hello("dev-g", link1, 1, nil))
			if pid := c.c.First.Header["host"].(map[string]any)["tmuxPid"]; pid != nil {
				t.Fatalf("tmuxPid %v without a usable socket", pid)
			}
			c.send(crp.SUB, crp.Obj{"st": "sess"}, nil)
			if f, err := c.next(1500 * time.Millisecond); err == nil {
				t.Fatalf("sent %s with an unusable socket", f.Type.Name())
			}
			if calls := testenv.TmuxCalls(rec); len(calls) != 0 {
				t.Fatalf("tmux called: %v", calls)
			}
		})
	}
}

// spec §8.4.4 in B1a's scope: SIGKILL of crd in the middle of a poll leaves the private server pid and every pane
// pid unchanged, and no tmux client of crd survives it.
func TestSigkillMidPollLeavesTmuxUntouched(t *testing.T) {
	g := newTmuxRig(t, []string{"claude-server-a", "claude-server-b"})
	server := testenv.TmuxPid(t, g.sock)
	panes, _ := testenv.Tmux(t, g.sock, "list-panes", "-a", "-F", "#{pane_pid}")
	must(t, syscall.Mkfifo(filepath.Join(g.rec, "hold"), 0o600))
	c := dial(t, g.r, hello("dev-k", link1, 1, nil))
	c.send(crp.SUB, crp.Obj{"st": "sess"}, nil)
	waitFor(t, 5*time.Second, "a list-panes call held by the recorder", func() bool {
		for _, l := range g.calls() {
			if strings.Contains(l, "list-panes") {
				return true
			}
		}
		return false
	})
	g.d.signal(syscall.SIGKILL)
	g.d.wait(3 * time.Second)
	keep := []int{server}
	for _, p := range strings.Fields(panes) {
		var n int
		fmt.Sscan(p, &n)
		keep = append(keep, n)
	}
	waitFor(t, 2*time.Second, "no crd tmux client left", func() bool { return len(testenv.Orphans(keep...)) == 0 })
	must(t, testenv.SafeRemoveAll(filepath.Join(g.rec, "hold")))
	if testenv.TmuxPid(t, g.sock) != server {
		t.Fatal("tmux server pid changed")
	}
	after, _ := testenv.Tmux(t, g.sock, "list-panes", "-a", "-F", "#{pane_pid}")
	if after != panes {
		t.Fatalf("pane pids changed: %q -> %q", panes, after)
	}
}

// spec §8.4.1 in B1a's scope: 1000 random SUB/UNSUB/ACK/PING operations change neither the private server pid
// nor its session set.
func TestRandomOpsLeaveTmuxUntouched(t *testing.T) {
	g := newTmuxRig(t, []string{"claude-server-a", "claude-server-b", "claude-server-c"})
	server := testenv.TmuxPid(t, g.sock)
	before, _ := testenv.Tmux(t, g.sock, "list-sessions", "-F", "#{session_name} #{session_created}")
	rnd := rand.New(rand.NewSource(1))
	conns := []*tconn{}
	for i := 0; i < 3; i++ {
		conns = append(conns, dial(t, g.r, hello(fmt.Sprintf("dev-r%d", i), link1, 1, nil)))
	}
	for i := 0; i < 1000; i++ {
		c := conns[rnd.Intn(len(conns))]
		st := []string{"ev", "sess"}[rnd.Intn(2)]
		switch rnd.Intn(4) {
		case 0:
			c.send(crp.SUB, crp.Obj{"st": st}, nil)
		case 1:
			c.send(crp.UNSUB, crp.Obj{"st": st}, nil)
		case 2:
			c.send(crp.ACK, crp.Obj{"acks": crp.Obj{}}, nil)
		case 3:
			c.send(crp.PING, crp.Obj{"t": int64(i)}, nil)
		}
		if i%50 == 0 {
			_ = c.nc.SetReadDeadline(time.Now().Add(10 * time.Millisecond))
		}
	}
	for _, c := range conns {
		c.send(crp.PING, crp.Obj{"t": int64(-1)}, nil)
		for {
			f := c.expect(crp.PONG, 5*time.Second)
			if t1, _ := crp.Int(f.Header, "t"); t1 == -1 {
				break
			}
		}
	}
	after, _ := testenv.Tmux(t, g.sock, "list-sessions", "-F", "#{session_name} #{session_created}")
	if testenv.TmuxPid(t, g.sock) != server || after != before {
		t.Fatalf("tmux changed: %q -> %q", before, after)
	}
	for _, l := range g.calls() {
		if l != "-V" && !strings.HasPrefix(l, "-N -S "+g.sock+" list-") { // -V: the gate's version check, no server
			t.Fatalf("call %q", l)
		}
	}
}

// spec §4.6 end to end: a transcript synthesised from the profile of a real one (dodatek D-H9; lengths only, one
// line over 48 KiB) is rebuilt by the probe through `crd --stdio` byte for byte, also gzip, also after an append.
func TestTxTranscriptThroughProbe(t *testing.T) {
	r := testenv.NewRoot(t)
	content := synthTranscript(t, 190000)
	uuid := "99ec0bf7-0000-4000-8000-000000000001"
	dir := filepath.Join(r.Home, ".claude", "projects", "-home-x")
	must(t, os.MkdirAll(dir, 0o700))
	tf := filepath.Join(dir, uuid+".jsonl")
	must(t, os.WriteFile(tf, content, 0o600))
	startDaemon(t, r)
	out := filepath.Join(r.Dir, "txout")
	must(t, os.Mkdir(out, 0o700))
	go func() {
		time.Sleep(2500 * time.Millisecond)
		appendTo(t, tf, "{\"type\":\"user\",\"n\":\"appended\"}\n")
	}()
	cmd := crdCmd(t, r, nil, "probe", "-sub", "", "-tx", "w1:"+uuid+":0", "-tx", "w2:"+uuid+":0:gzip", "-tx-out", out, "-for", "5s")
	so, err := cmd.Output()
	if err != nil {
		t.Fatalf("probe: %v %s", err, so)
	}
	want := readFile(t, tf)
	for _, w := range []string{"w1", "w2"} {
		if got := readFile(t, filepath.Join(out, w)); got != want {
			t.Fatalf("%s: rebuilt %d bytes, file %d", w, len(got), len(want))
		}
	}
	if !strings.Contains(string(so), "more=true") {
		t.Fatal("no piece with more=true (the profile has a line over 48 KiB)")
	}
}

type profile struct {
	Lines []struct {
		Len  int      `json:"len"`
		Keys []string `json:"keys"`
	} `json:"lines"`
}

// synthTranscript builds lines of the profiled lengths: the key skeleton with filler values (no real content).
func synthTranscript(t *testing.T, max int) []byte {
	b, err := os.ReadFile(filepath.Join(testenv.RepoRoot(), "crd", "testdata", "tx-profile.json"))
	must(t, err)
	var p profile
	must(t, json.Unmarshal(b, &p))
	var out bytes.Buffer
	big := false
	for _, l := range p.Lines {
		if out.Len()+l.Len+1 > max && big {
			break
		}
		if out.Len()+l.Len+1 > max {
			continue
		}
		out.Write(synthLine(l.Len, l.Keys))
		out.WriteByte('\n')
		big = big || l.Len > crp.Piece
	}
	if !big {
		t.Fatal("profile has no line over 48 KiB within the size")
	}
	return out.Bytes()
}

func synthLine(n int, keys []string) []byte {
	var b bytes.Buffer
	b.WriteByte('{')
	for i, k := range keys {
		if b.Len()+len(k)+8 >= n {
			break
		}
		if i > 0 {
			b.WriteByte(',')
		}
		fmt.Fprintf(&b, "%q:\"x\"", k)
	}
	if b.Len() == 1 {
		b.WriteString(`"x":""`)
	}
	// pad the last value to the exact length
	s := b.Bytes()
	pad := n - len(s) - 1
	if pad < 0 {
		return append(bytes.Repeat([]byte(" "), n-2), '{', '}')[:n]
	}
	s = append(s[:len(s)-1], bytes.Repeat([]byte("x"), pad)...)
	s = append(s, '"', '}')
	return s
}

func listenUnix(t *testing.T, p string) interface{ Close() error } {
	t.Helper()
	l, err := net.ListenUnix("unix", &net.UnixAddr{Name: p, Net: "unix"})
	must(t, err)
	l.SetUnlinkOnClose(false)
	return l
}
