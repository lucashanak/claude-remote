package tmuxgate

import (
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/testenv"
)

func TestMain(m *testing.M) { testenv.Main(m, testenv.Options{}) }

const S = "/x/tmux-1/default"

func argv(a ...string) []string { return append([]string{"-N", "-S", S}, a...) }

// spec §6.5 allowed shapes pass; everything else is refused (mutant 18).
func TestCheckAllowsOnlySpecShapes(t *testing.T) {
	ok := [][]string{
		argv("list-sessions", "-F", "#{session_name}"),
		argv("list-panes", "-a", "-F", "#{pane_pid}"),
		argv("list-clients", "-t", "=s"),
		argv("has-session", "-t", "=s"),
		argv("display-message", "-p", "-t", "=s:", "#{pane_pid}"),
		argv("show-environment", "-t", "=s", "PATH"),
		argv("capture-pane", "-p", "-t", "=s:"),
		argv("attach-session", "-t", "=s", "-f", "!ignore-size"),
		argv("refresh-client", "-t", "/dev/pts/3", "-f", "ignore-size"),
		argv("send-keys", "-t", "=s:", "-l", "hello"),
		argv("load-buffer", "-b", "crp-in-1", "-"),
		argv("paste-buffer", "-b", "crp-in-1", "-t", "=s:", "-p"),
		argv("delete-buffer", "-b", "crp-in-1"),
	}
	for _, a := range ok {
		if err := Check(S, a); err != nil {
			t.Errorf("%v refused: %v", a, err)
		}
	}
	bad := map[string][]string{
		"no -N":               {"-S", S, "list-sessions"},
		"other socket":        {"-N", "-S", "/tmp/tmux-1000/default", "list-sessions"},
		"-L":                  {"-N", "-L", "default", "list-sessions"},
		"extra global":        {"-N", "-S", S, "-f", "/dev/null", "list-sessions"},
		"new-session":         argv("new-session", "-d", "-s", "x"),
		"alias new":           argv("new", "-A", "-s", "x"),
		"kill-server":         argv("kill-server"),
		"kill-session":        argv("kill-session", "-t", "=x"),
		"set-option -g":       argv("set-option", "-g", "status", "off"),
		"show-environment -g": argv("show-environment", "-g"),
		"list-sessions -g":    argv("list-sessions", "-g"),
		"attach -A":           argv("attach-session", "-A", "-t", "=s"),
		"attach no -f":        argv("attach-session", "-t", "=s"),
		"attach other -f":     argv("attach-session", "-t", "=s", "-f", "read-only"),
		"target without =":    argv("has-session", "-t", "s"),
		"display no -p":       argv("display-message", "-t", "=s:", "x"),
		"display #(":          argv("display-message", "-p", "-t", "=s:", "#(id)"),
		"format #(":           argv("list-sessions", "-F", "#(rm -rf /)"),
		"send-keys no colon":  argv("send-keys", "-t", "=s", "x"),
		"load-buffer file":    argv("load-buffer", "-b", "crp-in-1", "/etc/passwd"),
		"other buffer":        argv("delete-buffer", "-b", "mine"),
		"refresh other":       argv("refresh-client", "-t", "=s"),
		"resize-window":       argv("resize-window", "-x", "10"),
		"run-shell":           argv("run-shell", "id"),
		"chained":             argv("list-sessions", ";", "kill-server"),
		"-g as a later word":  argv("send-keys", "-t", "=s:", "x", "-g"),
	}
	for name, a := range bad {
		if err := Check(S, a); err == nil {
			t.Errorf("%s accepted: %v", name, a)
		}
	}
}

func TestRunRefusesAPathOutsideTheRoot(t *testing.T) {
	g := &Gate{Sock: S, Allowed: func(string) bool { return false }}
	if _, err := g.Run("list-sessions"); err == nil || g.Calls.Load() != 0 {
		t.Fatalf("ran outside the root: %v", err)
	}
	if _, err := g.Run("kill-server"); err == nil || g.Calls.Load() != 0 {
		t.Fatal("ran a denied verb")
	}
}

// spec §6.3: missing, not a socket, no listener, two listeners ⇒ refused; exactly one listener that accepts ⇒ ok
// (mutant 17). Two listeners on one path is the 2026-11-02 split brain: bind, unlink the path, bind again.
func TestGuard(t *testing.T) {
	r := testenv.NewRoot(t)
	p := filepath.Join(r.TmuxTmp, "g")
	if _, err := Guard(p); err == nil {
		t.Fatal("missing socket accepted")
	}
	if err := os.WriteFile(p, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := Guard(p); err == nil {
		t.Fatal("regular file accepted")
	}
	if err := testenv.SafeRemoveAll(p); err != nil {
		t.Fatal(err)
	}
	a, err := net.ListenUnix("unix", &net.UnixAddr{Name: p, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	a.SetUnlinkOnClose(false)
	if _, err := Guard(p); err != nil {
		t.Fatalf("one listener refused: %v", err)
	}
	if err := testenv.SafeRemoveAll(p); err != nil {
		t.Fatal(err)
	}
	b, err := net.ListenUnix("unix", &net.UnixAddr{Name: p, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	b.SetUnlinkOnClose(false)
	if n, _, _ := Listeners(p); n != 2 {
		t.Fatalf("listeners %d, want 2", n)
	}
	if _, err := Guard(p); err == nil || !strings.Contains(err.Error(), "2 listeners") {
		t.Fatalf("two listeners accepted: %v", err)
	}
	a.Close()
	b.Close()
	if _, err := Guard(p); err == nil {
		t.Fatal("socket without a listener accepted")
	}
}

// fakeTmux puts a script named tmux first on PATH: `-V` answers version, anything else runs body.
func fakeTmux(t *testing.T, version, body string) {
	t.Helper()
	r := testenv.NewRoot(t)
	dir := filepath.Join(r.Dir, "bin")
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	script := "#!/bin/sh\n[ \"$1\" = -V ] && { echo '" + version + "'; exit 0; }\n" + body + "\n"
	if err := os.WriteFile(filepath.Join(dir, "tmux"), []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", dir+":"+os.Getenv("PATH"))
}

// T-R04: a tmux call that hangs is ended by the 2 s hard timeout (spec §6.5).
func TestRunEndsAHangingCallAfterTheTimeout(t *testing.T) {
	r := testenv.NewRoot(t)
	fakeTmux(t, "tmux 3.5a", "exec sleep 30")
	g := &Gate{Sock: r.GuardedSocket()}
	t0 := time.Now()
	_, err := g.Run("list-sessions", "-F", "#{session_name}")
	if el := time.Since(t0); err == nil || el < CallTimeout-100*time.Millisecond || el > CallTimeout+2*time.Second {
		t.Fatalf("hanging call: %v after %v", err, el)
	}
}

// A-F16: a tmux older than 3.2 (no -N) is a clear error, and no call with the socket is made.
func TestRunRefusesAnOldTmux(t *testing.T) {
	r := testenv.NewRoot(t)
	calls := filepath.Join(r.Dir, "calls")
	fakeTmux(t, "tmux 3.1c", "echo \"$*\" >> '"+calls+"'")
	g := &Gate{Sock: r.GuardedSocket()}
	if _, err := g.Run("list-sessions", "-F", "#{session_name}"); err == nil || !strings.Contains(err.Error(), "older than 3.2") {
		t.Fatalf("old tmux: %v", err)
	}
	if _, err := os.Stat(calls); !os.IsNotExist(err) {
		t.Fatal("a call reached the old tmux")
	}
	fakeTmux(t, "tmux next-3.6", "echo \"$*\" >> '"+calls+"'")
	if _, err := g.Run("list-sessions", "-F", "#{session_name}"); err != nil {
		t.Fatalf("tmux next-3.6: %v", err)
	}
}

// A-F16: a socket path with a space is matched whole (strings.Fields cut it at the space).
func TestListenersMatchAPathWithASpace(t *testing.T) {
	proc := "Num       RefCount Protocol Flags    Type St Inode Path\n" +
		"0000000000000000: 00000002 00000000 00010000 0001 01 4711 /tmp/my dir/tmux-1/default\n" +
		"0000000000000000: 00000003 00000000 00000000 0001 03 4712 /tmp/my dir/tmux-1/default\n" +
		"0000000000000000: 00000002 00000000 00010000 0001 01 4713 /tmp/my\n"
	n, ino, err := listenersIn(strings.NewReader(proc), "/tmp/my dir/tmux-1/default")
	if err != nil || n != 1 || ino != "4711" {
		t.Fatalf("listeners %d inode %s %v", n, ino, err)
	}
	if n, _, _ := listenersIn(strings.NewReader(proc), "/tmp/my"); n != 1 {
		t.Fatalf("/tmp/my: %d", n)
	}
}
