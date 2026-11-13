package daemon

import (
	"errors"
	"fmt"
	"io"
	"sync"
	"sync/atomic"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
)

// Writer priority per connection (spec §2.6): BYE PONG ERR TSTATE INACK > TOUT > RPCRES RPCPART > EV SNAP > TX.
// WELCOME is the first frame of a connection and goes with the first class.
const numPrio = 5

// Queue and write limits of one connection. Flow control (spec §2.6: 1024 items / 1 MiB per durable stream, 2 MiB per
// tx watch) bounds what is unacknowledged; these bound what is not even written yet, whatever the type (PONG and SNAP
// are outside flow control). A client that falls this far behind, or whose socket takes a write longer than
// WriteTimeout, is closed without BYE (the client sees `hung`/EOF and resumes from its positions, §4.3).
const (
	MaxQueueBytes  = 32 << 20
	MaxQueueFrames = 16384
	WriteTimeout   = 10 * time.Second
	// MaxHostQueueBytes bounds the sum of all writer queues of the host (review N-2): over it the connection with the
	// largest queue is closed without BYE (log conn-overflow scope=host), so many connections that do not read never
	// hold more than this together.
	MaxHostQueueBytes = 128 << 20
	// MaxConnsHost bounds the connections of the host, registered or still in their handshake (review N-2); one more
	// is answered BusyBye (E_SHUTDOWN with retryMs, never a strike; review R-3) and closed.
	MaxConnsHost = 64
)

var (
	errOverMaxFrame = errors.New("frame over the negotiated maxFrame")
	errOverflow     = errors.New("writer queue over its limit")
)

func prio(t crp.Type) int {
	switch t {
	case crp.WELCOME, crp.BYE, crp.PONG, crp.ERR, crp.TSTATE, crp.INACK:
		return 0
	case crp.TOUT:
		return 1
	case crp.RPCRES, crp.RPCPART:
		return 2
	case crp.EV, crp.SNAP:
		return 3
	}
	return 4 // TX
}

// writer serialises one connection's frames. Within a class order is FIFO; across classes the higher class goes
// first. A terminal BYE drops what is still queued (durable items are resumable, §4.3) and closes after writing;
// finish (the client's EOF) writes what is queued and then closes.
type writer struct {
	mu       sync.Mutex
	cond     *sync.Cond
	q        [numPrio][][]byte
	qBytes   int
	qFrames  int
	bye      []byte
	drain    bool
	closed   bool
	w        io.Writer
	maxFrame int
	deadline func(time.Time) error // write deadline of the socket (nil: plain writer in tests)
	timeout  time.Duration         // WriteTimeout (test mode: CRD_TEST_WRITE_MS)
	kill     func()                // closes the socket at once, so a Write that hangs returns (nil in tests)
	onDone   func()
	sent     func(t crp.Type) // test seam: order of written types
	failed   func(err error)  // a socket write failed (the connection logs the cause, review N-6)
	host     *atomic.Int64    // the host's sum of queued bytes (nil: a writer of its own in tests)
}

// addHost moves the host's sum of queued bytes by n.
func (w *writer) addHost(n int) {
	if w.host != nil {
		w.host.Add(int64(n))
	}
}

// dropLocked empties the queue (w.mu held).
func (w *writer) dropLocked() {
	w.addHost(-w.qBytes)
	w.q, w.qBytes, w.qFrames = [numPrio][][]byte{}, 0, 0
}

// abort drops the queue and closes the socket at once, without BYE (the host's queue budget).
func (w *writer) abort() {
	w.mu.Lock()
	if !w.closed {
		if w.bye == nil {
			w.bye = []byte{}
		}
		w.dropLocked()
		w.cond.Signal()
	}
	w.mu.Unlock()
	if w.kill != nil {
		w.kill()
	}
}

func newWriter(w io.Writer, maxFrame int, onDone func()) *writer {
	wr := &writer{w: w, maxFrame: maxFrame, onDone: onDone, timeout: WriteTimeout}
	wr.cond = sync.NewCond(&wr.mu)
	return wr
}

// send queues f. It never panics and never blocks: a frame that cannot be encoded or is over the negotiated maxFrame
// is refused (errOverMaxFrame or the encoder's E_FRAME), a queue over its limit closes the connection (errOverflow,
// returned once). Frames after the end of the connection are dropped.
func (w *writer) send(f crp.Frame) error {
	b, err := crp.Encode(f)
	if err != nil {
		return err
	}
	if len(b)-4 > w.maxFrame {
		return errOverMaxFrame
	}
	w.mu.Lock()
	if w.closed || w.bye != nil || w.drain {
		w.mu.Unlock()
		return nil
	}
	if w.qFrames+1 > MaxQueueFrames || w.qBytes+len(b) > MaxQueueBytes {
		w.bye = []byte{}
		w.dropLocked()
		w.cond.Signal()
		w.mu.Unlock()
		if w.kill != nil {
			w.kill()
		}
		return errOverflow
	}
	w.q[prio(f.Type)] = append(w.q[prio(f.Type)], b)
	w.qBytes += len(b)
	w.qFrames++
	w.addHost(len(b))
	w.cond.Signal()
	w.mu.Unlock()
	return nil
}

// terminate queues the final BYE (nil: close without one). While the writer drains after the client's EOF, a
// close without BYE lets the drain finish.
func (w *writer) terminate(bye *crp.Frame) {
	var b []byte
	if bye != nil {
		if e, err := crp.Encode(*bye); err == nil && len(e)-4 <= w.maxFrame {
			b = e
		}
	}
	w.mu.Lock()
	if !w.closed && w.bye == nil && !(bye == nil && w.drain) {
		if b == nil {
			b = []byte{}
		}
		w.bye = b
		w.cond.Signal()
	}
	w.mu.Unlock()
}

// finish writes what is queued and closes (the client closed its sending side).
func (w *writer) finish() {
	w.mu.Lock()
	w.drain = true
	w.cond.Signal()
	w.mu.Unlock()
}

// pending is the number of queued frames (stats, tests).
func (w *writer) pending() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.qFrames
}

// queued is the number of queued bytes (tests).
func (w *writer) queued() int {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.qBytes
}

func (w *writer) write(b []byte) error {
	if w.deadline != nil {
		_ = w.deadline(time.Now().Add(w.timeout))
	}
	_, err := w.w.Write(b)
	return err
}

func (w *writer) run() {
	defer w.onDone()
	for {
		w.mu.Lock()
		for w.bye == nil && w.qFrames == 0 && !w.drain {
			w.cond.Wait()
		}
		if w.bye != nil || (w.drain && w.qFrames == 0) {
			bye := w.bye
			w.closed = true
			w.dropLocked()
			w.mu.Unlock()
			if len(bye) > 0 {
				_ = w.write(bye)
			}
			return
		}
		var b []byte
		for i := range w.q {
			if len(w.q[i]) > 0 {
				b = w.q[i][0]
				w.q[i] = w.q[i][1:]
				break
			}
		}
		w.qBytes -= len(b)
		w.qFrames--
		w.addHost(-len(b))
		w.mu.Unlock()
		if w.sent != nil {
			w.sent(crp.Type(b[4]))
		}
		if err := w.write(b); err != nil {
			if w.failed != nil {
				w.failed(err)
			}
			w.mu.Lock()
			w.closed = true
			w.dropLocked()
			w.mu.Unlock()
			return
		}
	}
}

// runGuarded is run with the backstop of dodatek 2 P1.1: a panic here closes this connection only.
func (w *writer) runGuarded(lg *Logger, conn string) {
	defer func() {
		if r := recover(); r != nil {
			lg.Log("CRITICAL", "panic", TruncUTF8(fmt.Sprint(r), 200), "conn", conn)
			if w.kill != nil {
				w.kill()
			}
		}
	}()
	w.run()
}
