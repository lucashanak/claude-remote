package cli

import (
	"fmt"
	"io"
	"os"

	"github.com/lucashanak/claude-remote/crd/internal/daemon"
)

func init() {
	Register("serve", runServe)
	daemon.SrvString = Srv()
}

// runServe refuses before any file-system write (dodatek R5): the refusal goes to stderr only.
func runServe(_ []string, _, stderr io.Writer) int {
	p, err := daemon.ResolveServe(os.Getenv)
	if err != nil {
		fmt.Fprintln(stderr, err)
		return 1
	}
	return daemon.Serve(p, stderr)
}
