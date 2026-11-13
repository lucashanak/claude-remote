package daemon

import (
	"errors"
	"net"
	"os"
	"time"
)

// listen binds the daemon socket (spec §7.4): directory 0700, socket 0600; a stale socket (no answer to connect)
// is unlinked first. The flock on state/crd.lock is already held, so an answering socket is not ours to take.
func listen(sock string) (*net.UnixListener, error) {
	if st, err := os.Lstat(sock); err == nil {
		if st.Mode()&os.ModeSocket == 0 {
			return nil, errors.New(sock + " exists and is not a socket")
		}
		if c, err := net.DialTimeout("unix", sock, 500*time.Millisecond); err == nil {
			c.Close()
			return nil, errors.New(sock + " answers: another daemon holds it")
		}
		if err := os.Remove(sock); err != nil {
			return nil, err
		}
	}
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: sock, Net: "unix"})
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(sock, 0o600); err != nil {
		ln.Close()
		return nil, err
	}
	return ln, nil
}
