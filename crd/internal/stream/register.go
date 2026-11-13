package stream

import "github.com/lucashanak/claude-remote/crd/internal/daemon"

func init() {
	daemon.Register(newEv)
	daemon.Register(newSess)
	daemon.Register(newTx)
}
