package cli

import (
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"

	"github.com/lucashanak/claude-remote/crd/internal/daemon"
)

func init() { Register("selftest", runSelftest) }

// runSelftest checks what B1a can check without touching the daemon's tree (dodatek R6, D-H21): the environment,
// path lengths, /proc/net/unix, inotify, and a bind in a fresh temp dir. State files arrive in B1b/B3.
func runSelftest(_ []string, stdout, stderr io.Writer) int {
	fail := func(what string, err error) int {
		fmt.Fprintf(stderr, "crd selftest: %s: %v\n", what, err)
		return 1
	}
	p, err := daemon.ResolveClient(os.Getenv)
	if err != nil {
		return fail("paths", err)
	}
	if len(p.Sock) > daemon.MaxSockPath {
		return fail("socket path", fmt.Errorf("%d bytes", len(p.Sock)))
	}
	if _, err := os.ReadFile("/proc/net/unix"); err != nil {
		return fail("/proc/net/unix", err)
	}
	in, err := daemon.NewInotify()
	if err != nil {
		return fail("inotify", err)
	}
	in.Close()
	base := ""
	if p.Test {
		base = filepath.Join(p.Root, "tmp")
	}
	d, err := os.MkdirTemp(base, "crd-selftest-")
	if err != nil {
		return fail("temp dir", err)
	}
	ln, err := net.Listen("unix", filepath.Join(d, "s"))
	if err == nil {
		ln.Close() // unlinks the socket
	}
	_ = os.Remove(d)
	if err != nil {
		return fail("bind", err)
	}
	fmt.Fprintln(stdout, "crd selftest ok")
	return 0
}
