package stream

import (
	"errors"
	"os"
	"regexp"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/daemon"
)

// TxPoll is streamd's poll interval; crd keeps it (it MAY use inotify but MUST NOT change what is sent, §4.6).
const TxPoll = time.Second

// Watch limits (dodatek 2 P1.3): a SUB tx of a wid the connection already watches replaces that watch (its window
// carries over, review N-2); a new wid beyond these is a protocol error (BYE E_PROTO). The wid goes into every TX
// header, so it is short and plain.
const (
	MaxTxWatchesPerConn = 64
	MaxTxWatchesHost    = 256
	MaxWidLen           = 128
)

var widRe = regexp.MustCompile(`^[A-Za-z0-9._-]{1,128}$`)

type txWatch struct {
	c     daemon.Conn
	wid   string
	uuid  string
	path  string
	gz    bool
	model TxWatch
	queue []TxPiece
	sentO []sentPiece
	sentB int64
	odd   bool // the last poll found no regular file of this uid at path (logged once per streak; pollAll only)
}

type sentPiece struct {
	o, n int64
}

// txProvider is the tx stream (spec §4.6).
type txProvider struct {
	p       daemon.Paths
	log     *daemon.Logger
	mu      sync.Mutex
	watches map[string]map[string]*txWatch // conn -> wid -> watch
	off     atomic.Bool
	hb      atomic.Int64
	kick    chan struct{}
	// afterSnapshot is a test seam between the poll's snapshot of the watches and their reads (nil in production).
	afterSnapshot func()
}

// current reports whether w is still the watch of its wid (t.mu held): a re-SUB or UNSUB after the poll's snapshot
// replaced or removed it, and a replaced watch that still sent would open a second window for the wid (review N-2).
func (t *txProvider) current(w *txWatch) bool { return t.watches[w.c.ID()][w.wid] == w }

func newTx(env *daemon.Env) (daemon.Stream, error) {
	t := &txProvider{p: env.Paths, log: env.Log, watches: map[string]map[string]*txWatch{}, kick: make(chan struct{}, 1)}
	t.hb.Store(time.Now().UnixNano())
	return t, nil
}

func (t *txProvider) Name() string          { return "tx" }
func (t *txProvider) Feats() []string       { return []string{"tx", "gzip"} }
func (t *txProvider) Info() (crp.Obj, bool) { return nil, false }
func (t *txProvider) SetOff(off bool)       { t.off.Store(off) }
func (t *txProvider) Heartbeat() time.Time  { return time.Unix(0, t.hb.Load()) }
func (t *txProvider) Gone(c daemon.Conn)    { t.mu.Lock(); delete(t.watches, c.ID()); t.mu.Unlock() }
func (t *txProvider) Stats(add func(k, v string)) {
	t.mu.Lock()
	n := 0
	for _, m := range t.watches {
		n += len(m)
	}
	t.mu.Unlock()
	add("tx_watches", strconv.Itoa(n))
}

// Sub is streamd's watch: the path is resolved once, here (streamd.py:55-58).
func (t *txProvider) Sub(c daemon.Conn, h crp.Obj) (string, error) {
	wid, ok1 := crp.Str(h, "wid")
	uuid, ok2 := crp.Str(h, "uuid")
	off, ok3 := crp.Int(h, "off")
	if v, ok := h["off"].(int64); ok && v < 0 {
		off, ok3 = v, true
	}
	if !ok1 || !ok2 || !ok3 || wid == "" {
		return "", errors.New("SUB tx needs wid, uuid and off")
	}
	if !widRe.MatchString(wid) {
		return "", errors.New("SUB tx: wid is not 1-128 of [A-Za-z0-9._-]")
	}
	if !ValidUUID(uuid) {
		return "", errors.New("SUB tx: uuid is not a uuid")
	}
	enc, _ := crp.Str(h, "enc")
	switch enc {
	case "", "none":
	case "gzip":
		if !c.Feat("gzip") {
			return "", errors.New("SUB tx enc gzip without the gzip feature")
		}
	default:
		return "", errors.New("SUB tx: unknown enc")
	}
	cwd, _ := crp.Str(h, "cwd")
	w := &txWatch{c: c, wid: wid, uuid: uuid, gz: enc == "gzip", model: TxWatch{Off: off},
		path: ResolveTranscript(t.p.Home, t.p.Projects, cwd, uuid)}
	t.mu.Lock()
	m := t.watches[c.ID()]
	if _, again := m[wid]; !again {
		total := 0
		for _, x := range t.watches {
			total += len(x)
		}
		if len(m) >= MaxTxWatchesPerConn || total >= MaxTxWatchesHost {
			t.mu.Unlock()
			return "", errors.New("SUB tx: watch limit reached")
		}
	}
	if m == nil {
		m = map[string]*txWatch{}
		t.watches[c.ID()] = m
	}
	if old := m[wid]; old != nil {
		// the 2 MiB window belongs to the wid, not to one SUB: what the old watch sent and the client has not
		// acknowledged still counts, so a re-SUB in a loop never opens a new window (review N-2)
		w.sentO, w.sentB = old.sentO, old.sentB
	}
	m[wid] = w
	t.mu.Unlock()
	select {
	case t.kick <- struct{}{}:
	default:
	}
	return "", nil
}

func (t *txProvider) Unsub(c daemon.Conn, h crp.Obj) {
	wid, _ := crp.Str(h, "wid")
	t.mu.Lock()
	delete(t.watches[c.ID()], wid)
	t.mu.Unlock()
}

// Ack gets the ACK's tx map {wid: o}: everything up to o is acknowledged (flow control, spec §2.6).
func (t *txProvider) Ack(c daemon.Conn, a crp.Obj) error {
	t.mu.Lock()
	defer t.mu.Unlock()
	for wid, v := range a {
		o, ok := v.(int64)
		if !ok {
			return errors.New("ACK.tx value is not an offset")
		}
		w := t.watches[c.ID()][wid]
		if w == nil {
			continue
		}
		i := 0
		for i < len(w.sentO) && w.sentO[i].o <= o {
			w.sentB -= w.sentO[i].n
			i++
		}
		w.sentO = w.sentO[i:]
		t.flush(w)
	}
	return nil
}

// open opens a transcript for reading; in test mode only below the test root and never through a final symlink
// (review A-F17); never blocking and only a regular file of this uid (review R-1: a FIFO stopped tx for every device).
func (t *txProvider) open(path string) (*os.File, error) {
	if !t.p.UnderRoot(path) {
		return nil, errors.New("transcript outside the test root")
	}
	return openRegular(path, !t.p.Test)
}

// flush sends queued pieces while the watch has fewer than 2 MiB unacknowledged (always at least one piece). Each
// piece's bytes are read here, when it is sent: a watch holds at most the window, not the delta (review A-F7). A
// piece that cannot be read any more (the file shrank or went away) drops the rest of the delta and the watch starts
// over from offset 0 with reset, like streamd on a truncated file.
func (t *txProvider) flush(w *txWatch) {
	var f *os.File
	defer func() {
		if f != nil {
			f.Close()
		}
	}()
	for len(w.queue) > 0 && (w.sentB == 0 || w.sentB+(w.queue[0].O-w.queue[0].F) <= daemon.MaxUnackedTxBytes) {
		p := w.queue[0]
		if p.Bytes == nil {
			var err error
			if f == nil {
				f, err = t.open(w.path)
			}
			if err == nil {
				p.Bytes = make([]byte, p.O-p.F)
				err = readFull(f, p.Bytes, p.F)
			}
			if err != nil {
				w.queue = nil
				w.model.Off, w.model.pendingReset = 0, true
				return
			}
		}
		w.queue = w.queue[1:]
		w.c.Send(TxFrame(w.wid, w.uuid, p, w.gz))
		w.sentO = append(w.sentO, sentPiece{p.O, p.O - p.F})
		w.sentB += p.O - p.F
	}
}

func (t *txProvider) pollAll() {
	t.mu.Lock()
	var ws []*txWatch
	for _, m := range t.watches {
		for _, w := range m {
			ws = append(ws, w)
		}
	}
	t.mu.Unlock()
	if t.afterSnapshot != nil {
		t.afterSnapshot()
	}
	for _, w := range ws {
		t.mu.Lock()
		busy := len(w.queue) > 0
		t.mu.Unlock()
		if busy {
			continue
		}
		st, err := os.Stat(w.path)
		if err != nil {
			continue // streamd: getsize failure = continue, the watch stays
		}
		f, err := t.open(w.path)
		if err != nil {
			if errors.Is(err, errNotRegular) && !w.odd {
				t.log.Log("tx-not-regular", "wid", w.wid, "why", "transcript not a regular file")
			}
			w.odd = errors.Is(err, errNotRegular)
			continue
		}
		w.odd = false
		t.mu.Lock()
		if !t.current(w) { // replaced or removed after the snapshot: its wid's window belongs to the new watch
			t.mu.Unlock()
			f.Close()
			continue
		}
		r, err := w.model.PollRange(st.Size(), f)
		if err == nil && r != nil {
			w.queue = append(w.queue, RangePieces(*r)...)
			t.flush(w)
		}
		t.mu.Unlock()
		f.Close()
	}
}

func (t *txProvider) Start(stop <-chan struct{}) {
	tk := time.NewTicker(TxPoll)
	defer tk.Stop()
	for {
		t.hb.Store(time.Now().UnixNano())
		if !t.off.Load() {
			t.pollAll()
		}
		select {
		case <-stop:
			return
		case <-tk.C:
		case <-t.kick:
		}
	}
}
