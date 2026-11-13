package itest

// Round 4 (dodatek 4 po ověření 2), as crd processes over the socket: a FIFO at the place of an ev source (at the
// start and while it runs) or of a transcript neither stops the daemon nor crd.off (review R-1), and the wiring of
// the log's path check in Serve, with a log prefilled to just under its cut (M1f).

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

// fifoAt moves the file at p aside and puts a FIFO there (inside the test root). The cleanup wakes a daemon of an
// older tree still blocked opening it, so its stop does not wait for SIGKILL.
func fifoAt(t *testing.T, p string) {
	t.Helper()
	if _, err := os.Lstat(p); err == nil {
		must(t, os.Rename(p, p+".reg"))
	}
	must(t, syscall.Mkfifo(p, 0o600))
	t.Cleanup(func() {
		for _, fl := range []int{os.O_WRONLY, os.O_RDONLY} {
			if f, err := os.OpenFile(p, fl|syscall.O_NONBLOCK, 0); err == nil {
				f.Close()
			}
		}
	})
}

// waitEv reads frames until an EV whose line contains want arrives (false after d).
func waitEv(c *tconn, want string, d time.Duration) bool {
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		f, err := c.next(time.Until(deadline))
		if err != nil {
			return false
		}
		if f.Type == crp.EV && bytes.Contains(f.Blob, []byte(want)) {
			return true
		}
	}
	return false
}

func logCount(d *daemonProc, s string) int { return strings.Count(d.log(), s) }

// R-1 (verification A, g5): FIFOs at legacy (production: /tmp/claude-notify), forget and jarvis before the start.
// The daemon listens, events and notify are seeded and served, each FIFO is logged once, and legacy is read again once
// a regular file is back in its place.
func TestFifoSourcesAtStartLetTheDaemonServe(t *testing.T) {
	r := testenv.NewRoot(t)
	fx := writeFixture(t, r)
	for _, p := range []string{fx.legacy, fx.forget, fx.jarvis} {
		fifoAt(t, p)
	}
	d := startDaemon(t, r)
	c := dial(t, r, hello("dev-fifo-start", link1, 1, nil))
	c.send(crp.SUB, crp.Obj{"st": "ev"}, nil)
	srcs := map[string]int{}
	for _, f := range c.collect(1500 * time.Millisecond) {
		if f.Type == crp.EV {
			s, _ := crp.Str(f.Header, "src")
			srcs[s]++
		}
	}
	if len(srcs) != 2 || srcs["events"] != 25 || srcs["notify"] != 2 {
		t.Fatalf("seeded EV by source %v", srcs)
	}
	for _, src := range []string{"legacy", "forget", "jarvis"} {
		if n := logCount(d, "ev=ev-not-regular src="+src+" "); n != 1 {
			t.Fatalf("ev-not-regular for %s logged %d times", src, n)
		}
	}
	must(t, os.Rename(fx.legacy, fx.legacy+".fifo"))
	must(t, os.WriteFile(fx.legacy, []byte("claude-server-legacy-back\n"), 0o600))
	if !waitEv(c, "claude-server-legacy-back", 4*time.Second) {
		t.Fatal("legacy not read once it is a regular file again")
	}
}

// R-1 (verification A, g1-g3 and g6): events, notify and jarvis become FIFOs while the daemon runs and rotates, and a
// transcript is a FIFO. The other sources and the other transcript are still served, crd.off answers the live
// connection with BYE E_OFF within 2 s, and once crd.off is gone the daemon welcomes again.
func TestFifoSourcesWhileRunningLeaveCrdOffWorking(t *testing.T) {
	r := testenv.NewRoot(t)
	fx := writeFixture(t, r)
	tp := writeTranscript(t, r, 4096)
	const uuidFifo = "1b4e7a1c-3d2f-4e5a-8b9c-1d2e3f4a5b6c"
	d := startDaemon(t, r, "CRD_TEST_ROTATE_MS=300")
	live := dial(t, r, hello("dev-fifo-live", link1, 1, nil))
	live.send(crp.SUB, crp.Obj{"st": "ev"}, nil)
	live.collect(800 * time.Millisecond)
	for _, p := range []string{fx.events, fx.notify, fx.jarvis} {
		fifoAt(t, p)
	}
	fifoAt(t, filepath.Join(filepath.Dir(tp), uuidFifo+".jsonl"))
	tc := dial(t, r, hello("dev-fifo-tx", link2, 1, nil))
	tc.send(crp.SUB, crp.Obj{"st": "tx", "uuid": uuidFifo, "off": int64(0), "wid": "fifo"}, nil)
	tc.send(crp.SUB, crp.Obj{"st": "tx", "uuid": uuidT, "off": int64(0), "wid": "ok"}, nil)
	time.Sleep(2500 * time.Millisecond) // rounds and rotations over the FIFOs
	appendTo(t, fx.forget, "forget claude-server-after-fifo 1793990000002\n")
	if !waitEv(live, "claude-server-after-fifo", 4*time.Second) {
		t.Fatal("forget not followed next to the FIFOs")
	}
	wids := map[string]int{}
	for _, f := range tc.collect(1500 * time.Millisecond) {
		if f.Type == crp.TX {
			w, _ := crp.Str(f.Header, "wid")
			wids[w]++
		}
	}
	if wids["ok"] == 0 || wids["fifo"] != 0 {
		t.Fatalf("TX by watch %v", wids)
	}
	for _, src := range []string{"events", "notify", "jarvis"} {
		if n := logCount(d, "ev=ev-not-regular src="+src+" "); n != 1 {
			t.Fatalf("ev-not-regular for %s logged %d times", src, n)
		}
	}
	if n := logCount(d, "ev=tx-not-regular wid=fifo "); n != 1 {
		t.Fatalf("tx-not-regular logged %d times", n)
	}
	off := filepath.Join(r.Home, ".claude-remote", "crd.off")
	must(t, os.WriteFile(off, nil, 0o600))
	live.expectBye(crp.EOff, 2*time.Second)
	must(t, testenv.SafeRemoveAll(off))
	att := int64(1)
	waitFor(t, 4*time.Second, "WELCOME after crd.off is gone", func() bool {
		att++
		f, err := rawAnswer(t, r, hello("dev-fifo-live", link1, att, nil))
		return err == nil && f.Type == crp.WELCOME
	})
}

// M1f (verification T): Serve wires the log's path check in test mode. The log is prefilled to just under its cut;
// after the start the crd directory becomes a link to a directory out of the root with a crd.log of its own. The
// cut that follows is refused (the daemon's own log grows past the limit in the file it has open) and nothing out
// there is read, replaced or written.
func TestPrefilledLogCutStaysInTheRoot(t *testing.T) {
	r := testenv.NewRoot(t)
	r2 := testenv.NewRoot(t)
	crdDir := filepath.Join(r.Home, ".claude-remote", "crd")
	must(t, os.MkdirAll(crdDir, 0o700))
	line := []byte("ts=2026-11-11T00:00:00.000Z ev=prefill " + strings.Repeat("p", 90) + "\n")
	fill := bytes.Repeat(line, int(daemon.LogMax-16<<10)/len(line))
	must(t, os.WriteFile(filepath.Join(crdDir, "crd.log"), fill, 0o600))
	vdir := filepath.Join(r2.Dir, "crd")
	must(t, os.Mkdir(vdir, 0o700))
	victim := []byte("victim crd.log\n")
	must(t, os.WriteFile(filepath.Join(vdir, "crd.log"), victim, 0o600))
	vst, err := os.Stat(filepath.Join(vdir, "crd.log"))
	must(t, err)
	d := startDaemon(t, r, "CRD_TEST_STATS_MS=40")
	must(t, os.Rename(crdDir, crdDir+".orig"))
	testenv.SafeSymlink(t, vdir, crdDir)
	own := filepath.Join(crdDir+".orig", "crd.log")
	waitFor(t, 10*time.Second, "the daemon's log past its limit (a cut was due)", func() bool {
		st, err := os.Stat(own)
		return err == nil && st.Size() > daemon.LogMax+2<<10
	})
	d.stop()
	b, _ := os.ReadFile(filepath.Join(vdir, "crd.log"))
	st, err := os.Stat(filepath.Join(vdir, "crd.log"))
	es, _ := os.ReadDir(vdir)
	if err != nil || !bytes.Equal(b, victim) || !os.SameFile(st, vst) || len(es) != 1 {
		t.Fatalf("out of the root: crd.log %d bytes (same file %v), %d entries", len(b), err == nil && os.SameFile(st, vst), len(es))
	}
}
