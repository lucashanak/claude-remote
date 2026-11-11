package daemon

import (
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
)

// Flow control of durable streams (spec §2.6).
const (
	MaxUnackedItems   = 1024    // per (connection, stream)
	MaxUnackedBytes   = 1 << 20 // per (connection, stream)
	MaxUnackedTxBytes = 2 << 20 // per tx watch (always at least one piece)
)

// Conn is what a stream sees of one connection.
type Conn interface {
	ID() string
	// Send queues a frame at the priority of its type (writer.go); it never blocks.
	Send(f crp.Frame)
	// Fail sends BYE{code,msg} and closes the connection.
	Fail(code, msg string)
	// Feat reports a negotiated feature.
	Feat(name string) bool
	// Resume is HELLO.resume[st].
	Resume(st string) (ep string, seq int64, ok bool)
	// Done is closed when the connection ends.
	Done() <-chan struct{}
}

// Stream is one durable stream provider (celek 3).
type Stream interface {
	Name() string
	// Feats are the features this provider makes the daemon offer (ev; sess; tx and gzip).
	Feats() []string
	// Info is the WELCOME.streams entry {ep, first, last}; ok=false for streams without one (tx).
	Info() (crp.Obj, bool)
	// Sub handles SUB, and HELLO.resume as a SUB with that position. It returns WELCOME.resumed's value
	// ("replay" | "snapshot", "" for tx and new subscriptions); an error is a protocol violation (BYE E_PROTO).
	Sub(c Conn, h crp.Obj) (string, error)
	Unsub(c Conn, h crp.Obj)
	// Ack gets acks[st] (an {ep, seq} object) for ev/sess, the ACK's "tx" map for tx.
	Ack(c Conn, a crp.Obj) error
	Gone(c Conn)
	// SetOff stops (true) or resumes (false) polling, watches and rotation (spec §1.4).
	SetOff(off bool)
	// Stats adds key=value pairs for the `crd stats` line.
	Stats(add func(k, v string))
	// Heartbeat is the last time the provider's loop ran (watchdog, spec §7.2).
	Heartbeat() time.Time
	// Start runs the provider's loops until stop is closed.
	Start(stop <-chan struct{})
}

// TmuxPider is implemented by the provider that runs the socket guard (WELCOME.host.tmuxPid, stats tmux_pid).
type TmuxPider interface {
	TmuxPid() (int64, bool)
}

// Env is what a provider gets from the daemon.
type Env struct {
	Paths Paths
	Log   *Logger
	Now   func() time.Time
	Ep    string // epoch of this daemon start (spec §4.2)
}

// Factory builds a provider at start.
type Factory func(env *Env) (Stream, error)

var factories []Factory

// Register adds a provider factory (from init of the stream package).
func Register(f Factory) { factories = append(factories, f) }

// offered is ProductFeats restricted to the registered providers, in daemon order.
func offered(ss []Stream) []string {
	have := map[string]bool{}
	for _, s := range ss {
		for _, f := range s.Feats() {
			have[f] = true
		}
	}
	var out []string
	for _, f := range ProductFeats {
		if have[f] {
			out = append(out, f)
		}
	}
	return out
}
