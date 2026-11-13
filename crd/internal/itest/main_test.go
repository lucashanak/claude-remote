// Package itest runs crd as a real process (the test binary re-executes itself as crd) against private roots,
// private tmux servers and fixtures. Nothing here talks to the live box: testenv.Main sets the environment of
// this process before any test, children inherit it, and the canary compares the live box before and after.
package itest

import (
	"bytes"
	"errors"
	"net"
	"os"
	"os/exec"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/cli"
	"github.com/lucashanak/claude-remote/crd/internal/client"
	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/testenv"
)

func TestMain(m *testing.M) {
	if os.Getenv("CRD_TEST_EXEC") == "1" {
		os.Exit(cli.Main(os.Args[1:]))
	}
	testenv.Main(m, testenv.Options{TmuxLock: needTmuxLock})
}

// needTmuxLock is set by the files whose tests start a private tmux (celek 3): only they take
// ~/.cache/crit-integration.lock, like the Kotlin integration tests.
var needTmuxLock bool

func self(t *testing.T) string {
	t.Helper()
	p, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	return p
}

type safeBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *safeBuf) Write(p []byte) (int, error) { s.mu.Lock(); defer s.mu.Unlock(); return s.b.Write(p) }
func (s *safeBuf) String() string              { s.mu.Lock(); defer s.mu.Unlock(); return s.b.String() }

// crdCmd builds `crd <args>` on root (test mode) with extra environment.
func crdCmd(t *testing.T, root *testenv.Root, env []string, args ...string) *exec.Cmd {
	t.Helper()
	e := append(append(root.Env(), "CRD_TEST_EXEC=1"), env...)
	return testenv.Command(t, e, self(t), args...)
}

type daemonProc struct {
	t      *testing.T
	root   *testenv.Root
	cmd    *exec.Cmd
	stderr *safeBuf
	exited chan struct{}
	code   int
}

// startDaemon runs `crd serve` in test mode on root and waits until its socket answers.
func startDaemon(t *testing.T, root *testenv.Root, env ...string) *daemonProc {
	t.Helper()
	d := &daemonProc{t: t, root: root, stderr: &safeBuf{}, exited: make(chan struct{})}
	d.cmd = crdCmd(t, root, env, "serve")
	d.cmd.Stderr = d.stderr
	if err := d.cmd.Start(); err != nil {
		t.Fatal(err)
	}
	go func() {
		err := d.cmd.Wait()
		var ee *exec.ExitError
		if errors.As(err, &ee) {
			d.code = ee.ExitCode()
		}
		close(d.exited)
	}()
	t.Cleanup(d.stop)
	deadline := time.Now().Add(10 * time.Second)
	for {
		if c, err := net.DialTimeout("unix", root.Sock(), 200*time.Millisecond); err == nil {
			c.Close()
			return d
		}
		select {
		case <-d.exited:
			t.Fatalf("crd serve exited early (%d): %s", d.code, d.stderr.String())
		default:
		}
		if time.Now().After(deadline) {
			t.Fatalf("crd serve did not listen: %s", d.stderr.String())
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func (d *daemonProc) pid() int { return d.cmd.Process.Pid }

func (d *daemonProc) signal(sig syscall.Signal) { testenv.Signal(d.t, d.pid(), sig) }

// wait returns the exit code, failing after the timeout.
func (d *daemonProc) wait(timeout time.Duration) int {
	d.t.Helper()
	select {
	case <-d.exited:
		return d.code
	case <-time.After(timeout):
		d.t.Fatalf("crd did not exit within %v", timeout)
	}
	return -1
}

func (d *daemonProc) stop() {
	select {
	case <-d.exited:
		return
	default:
	}
	testenv.Signal(d.t, d.pid(), syscall.SIGTERM)
	select {
	case <-d.exited:
	case <-time.After(5 * time.Second):
		testenv.Signal(d.t, d.pid(), syscall.SIGKILL)
		<-d.exited
	}
}

func (d *daemonProc) log() string {
	b, _ := os.ReadFile(d.root.Home + "/.claude-remote/crd/crd.log")
	return string(b)
}

// tconn is a test client on the daemon socket.
type tconn struct {
	t  *testing.T
	nc net.Conn
	c  *client.Client
}

func dialRaw(t *testing.T, root *testenv.Root) net.Conn {
	t.Helper()
	nc, err := client.DialSocket(root.Sock())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { nc.Close() })
	return nc
}

// dial connects and handshakes; the first frame is in c.c.First.
func dial(t *testing.T, root *testenv.Root, hello crp.Obj) *tconn {
	t.Helper()
	nc := dialRaw(t, root)
	_ = nc.SetDeadline(time.Now().Add(5 * time.Second))
	c, err := client.Handshake(nc, hello)
	if err != nil {
		t.Fatalf("handshake: %v", err)
	}
	return &tconn{t: t, nc: nc, c: c}
}

func hello(dev, link string, att int64, resume crp.Obj) crp.Obj {
	return client.Hello(dev, link, att, []string{"ev", "sess", "tx", "term", "in", "rpc.exec", "gzip"}, resume)
}

const link1, link2 = "5b1e2f0c9a7d3e41", "c03d81aa47e2b915"

func (c *tconn) send(typ crp.Type, h crp.Obj, blob []byte) {
	c.t.Helper()
	if err := c.c.Send(crp.NewFrame(typ, h, blob)); err != nil {
		c.t.Fatalf("send %s: %v", typ.Name(), err)
	}
}

// next reads one frame within d (error on timeout/EOF).
func (c *tconn) next(d time.Duration) (crp.Frame, error) {
	_ = c.nc.SetReadDeadline(time.Now().Add(d))
	return c.c.Next()
}

// expect reads frames until one of type typ arrives (other types are returned in skipped).
func (c *tconn) expect(typ crp.Type, d time.Duration) crp.Frame {
	c.t.Helper()
	deadline := time.Now().Add(d)
	for {
		f, err := c.next(time.Until(deadline))
		if err != nil {
			c.t.Fatalf("waiting for %s: %v", typ.Name(), err)
		}
		if f.Type == typ {
			return f
		}
	}
}

func (c *tconn) expectBye(code string, d time.Duration) crp.Frame {
	c.t.Helper()
	f := c.expect(crp.BYE, d)
	if got, _ := crp.Str(f.Header, "code"); got != code {
		c.t.Fatalf("BYE %s, want %s (%v)", got, code, f.Header)
	}
	return f
}

func featsOf(w crp.Frame) []string {
	var out []string
	if fs, ok := w.Header["feats"].([]any); ok {
		for _, f := range fs {
			s, _ := f.(string)
			out = append(out, s)
		}
	}
	return out
}

func waitFor(t *testing.T, d time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(d)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out: %s", what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

var _ = strings.Contains
