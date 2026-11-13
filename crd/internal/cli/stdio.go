package cli

import (
	"io"
	"net"
	"os"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/client"
	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/daemon"
)

func init() { Register("--stdio", runStdio) }

// runStdio is the shim (spec §2.2): it never starts the daemon or a unit and creates nothing; it answers
// E_OFF/E_DAEMON_DOWN itself and otherwise relays bytes both ways until either side closes.
func runStdio(_ []string, stdout, _ io.Writer) int {
	refuse := func(code, msg string) int {
		b, err := crp.Encode(daemon.Bye(code, msg))
		if err != nil {
			return 1
		}
		_, _ = stdout.Write(append([]byte(crp.Magic), b...))
		return 0
	}
	p, err := daemon.ResolveClient(os.Getenv)
	if err != nil {
		return refuse(crp.EDaemonDown, err.Error())
	}
	if _, err := os.Stat(p.Off); err == nil {
		return refuse(crp.EOff, daemon.MsgOff)
	}
	c, err := net.DialTimeout("unix", p.Sock, 2*time.Second)
	if err != nil {
		return refuse(crp.EDaemonDown, "daemon socket does not answer")
	}
	defer c.Close()
	if _, err := c.Write(client.Prelude(os.Getenv)); err != nil {
		return refuse(crp.EDaemonDown, "daemon socket does not answer")
	}
	// stdin EOF (the exec channel's EOF, or `printf … | crd --stdio`) half-closes the socket and the daemon's answer
	// is still relayed until it closes, at most shimDrain later (review K-F9).
	in, out := make(chan struct{}), make(chan struct{})
	go func() {
		_, _ = io.Copy(c, os.Stdin)
		if uc, ok := c.(*net.UnixConn); ok {
			_ = uc.CloseWrite()
		}
		close(in)
	}()
	go func() { _, _ = io.Copy(stdout, c); close(out) }()
	select {
	case <-out:
	case <-in:
		select {
		case <-out:
		case <-time.After(shimDrain):
		}
	}
	return 0
}

// shimDrain bounds how long the shim relays the daemon's answer after its stdin ended.
const shimDrain = 10 * time.Second
