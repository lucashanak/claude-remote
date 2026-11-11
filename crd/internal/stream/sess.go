package stream

import (
	"crypto/rand"
	"encoding/hex"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/daemon"
	"github.com/lucashanak/claude-remote/crd/internal/tmuxgate"
)

// SessPoll is the sess poll interval while somebody subscribes (spec §4.5).
const SessPoll = 2 * time.Second

// MaxRowField bounds name and cmd of a sess row (dodatek 2 P1.1). cmd is argv[0] of the pane's foreground process,
// unbounded and written by anything that runs in a session: it is cut at a UTF-8 boundary (erratum: no `trunc` flag).
// The name is the client's key of the table and is never cut (review N-7: two long names with a common prefix would
// become one row): a session whose name is longer is left out of the table and logged (`sess name too long`).
const MaxRowField = 256

// Row is one sess row {name, created, clients, panePid, cmd, dead}. B1a holds no tmux clients of its own, so
// clients is session_attached as tmux reports it.
type Row struct {
	Name    string
	Created int64
	Clients int64
	PanePid int64
	Cmd     string
	Dead    bool
}

func (r Row) obj() crp.Obj {
	return crp.Obj{"name": validName(r.Name), "created": r.Created, "clients": r.Clients,
		"panePid": r.PanePid, "cmd": daemon.TruncUTF8(r.Cmd, MaxRowField), "dead": r.Dead}
}

// validName is the name as it goes out: valid UTF-8 (tmux escapes control characters and invalid bytes itself; this
// is the backstop), never cut.
func validName(n string) string { return strings.ToValidUTF8(n, "\uFFFD") }

// identity of the tmux server behind the guarded socket: listener inode, server pid and its start time.
type identity struct {
	inode, start string
	pid          int64
}

// sessProvider is the sess stream (spec §4.5). It never starts, kills or changes anything in tmux: it reads
// list-sessions and list-panes through the gate, only while a connection subscribes and crd.off is absent.
type sessProvider struct {
	p       daemon.Paths
	log     *daemon.Logger
	seq     *seqStream
	gate    *tmuxgate.Gate
	every   time.Duration
	mu      sync.Mutex
	table   map[string]Row
	known   bool // a table has been read
	held    map[string]bool
	id      identity
	pidOK   bool
	off     atomic.Bool
	waiting map[string]waitSub
	hb      atomic.Int64
	kick    chan struct{}
	failing atomic.Bool
	long    int                                     // sessions left out for a name over MaxRowField in the last read
	readFn  func() (map[string]Row, identity, bool) // test seam; default read
}

type waitSub struct {
	c daemon.Conn
	p *Pos
}

func newSess(env *daemon.Env) (daemon.Stream, error) {
	r := NewRing(env.Ep, SessRingDiff, 0)
	r.Now = env.Now
	s := &sessProvider{p: env.Paths, log: env.Log, seq: newSeqStream("sess", r), every: SessPoll, table: map[string]Row{},
		waiting: map[string]waitSub{}, kick: make(chan struct{}, 1)}
	s.readFn = s.read
	s.gate = &tmuxgate.Gate{Sock: env.Paths.TmuxSock, Allowed: env.Paths.UnderRoot}
	if env.Paths.Test {
		if ms, err := strconv.Atoi(os.Getenv("CRD_TEST_SESS_MS")); err == nil && ms > 0 {
			s.every = time.Duration(ms) * time.Millisecond
		}
	}
	s.seq.itemFrame = func(it Item) crp.Frame {
		return crp.NewFrame(crp.EV, crp.Obj{"st": "sess", "ep": r.Ep, "seq": it.Seq, "ops": it.Ops}, nil)
	}
	s.seq.snapFrames = func(reason string, _ []Item) []crp.Frame {
		return []crp.Frame{crp.NewFrame(crp.SNAP, crp.Obj{"st": "sess", "ep": r.Ep, "seq": r.Last(), "from": r.Last() + 1,
			"reason": reason, "sessions": s.tableObj()}, nil)}
	}
	s.hb.Store(time.Now().UnixNano())
	return s, nil
}

// tableObj renders the table sorted by name. Lock order everywhere: seq.mu, then s.mu.
func (s *sessProvider) tableObj() []any {
	s.mu.Lock()
	defer s.mu.Unlock()
	names := make([]string, 0, len(s.table))
	for n := range s.table {
		names = append(names, n)
	}
	sort.Strings(names)
	out := make([]any, 0, len(names))
	for _, n := range names {
		out = append(out, s.table[n].obj())
	}
	return out
}

func (s *sessProvider) Name() string    { return "sess" }
func (s *sessProvider) Feats() []string { return []string{"sess"} }
func (s *sessProvider) Info() (crp.Obj, bool) {
	return s.seq.info(), true
}

// Sub answers at once when a table is known; otherwise the subscriber waits for the first successful read
// (a failed read sends nothing, spec §4.5).
func (s *sessProvider) Sub(c daemon.Conn, h crp.Obj) (string, error) {
	p := posOf(c, "sess", h)
	s.mu.Lock()
	known := s.known
	if !known {
		s.waiting[c.ID()] = waitSub{c, p}
	}
	s.mu.Unlock()
	select {
	case s.kick <- struct{}{}:
	default:
	}
	if !known {
		// the first successful read answers with SNAP (poll), so a resume position gets "snapshot" now (review K-F11)
		if p != nil {
			return "snapshot", nil
		}
		return "", nil
	}
	return s.seq.subscribe(c, p), nil
}

func (s *sessProvider) drop(c daemon.Conn) {
	s.mu.Lock()
	delete(s.waiting, c.ID())
	s.mu.Unlock()
	s.seq.unsubscribe(c)
}

func (s *sessProvider) Unsub(c daemon.Conn, _ crp.Obj)     { s.drop(c) }
func (s *sessProvider) Gone(c daemon.Conn)                 { s.drop(c) }
func (s *sessProvider) Ack(c daemon.Conn, a crp.Obj) error { return s.seq.ack(c, a) }
func (s *sessProvider) SetOff(off bool)                    { s.off.Store(off) }
func (s *sessProvider) Heartbeat() time.Time               { return time.Unix(0, s.hb.Load()) }
func (s *sessProvider) Stats(add func(k, v string)) {
	if pid, ok := s.TmuxPid(); ok {
		add("tmux_pid", strconv.FormatInt(pid, 10))
	} else {
		add("tmux_pid", "none")
	}
}

// TmuxPid is WELCOME.host.tmuxPid: the server pid of the last good poll.
func (s *sessProvider) TmuxPid() (int64, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.id.pid, s.pidOK
}

func (s *sessProvider) wanted() bool {
	s.mu.Lock()
	w := len(s.waiting) > 0
	s.mu.Unlock()
	return w || s.seq.count() > 0
}

func (s *sessProvider) Start(stop <-chan struct{}) {
	t := time.NewTicker(s.every)
	defer t.Stop()
	for {
		s.hb.Store(time.Now().UnixNano())
		if !s.off.Load() && s.wanted() {
			s.poll()
		}
		select {
		case <-stop:
			return
		case <-t.C:
		case <-s.kick:
		}
	}
}

// read takes the table through the guard and the gate; any failure returns ok=false and nothing is sent.
func (s *sessProvider) read() (map[string]Row, identity, bool) {
	inode, err := tmuxgate.Guard(s.p.TmuxSock)
	if err != nil {
		return nil, identity{}, false
	}
	// ':' separates the fields: tmux never allows it in a session name (it turns it into '_') and escapes control
	// characters there, and the pane command, which may contain anything but NUL, is the last field. Every record
	// starts with a nonce of this poll; a line without it continues the command of the record before (a newline in
	// argv[0]), it never becomes a record of its own (review A-F5).
	nonce := newNonce()
	ls, err := s.gate.Run("list-sessions", "-F", nonce+":#{pid}:#{session_created}:#{session_attached}:#{session_name}")
	if err != nil {
		s.logFail("list-sessions", err)
		return nil, identity{}, false
	}
	lp, err := s.gate.Run("list-panes", "-a", "-F", nonce+":#{window_active}:#{pane_active}:#{pane_id}:#{pane_pid}:#{pane_dead}:#{session_name}:#{pane_current_command}")
	if err != nil {
		s.logFail("list-panes", err)
		return nil, identity{}, false
	}
	rows := map[string]Row{}
	var pid int64
	long := 0
	for _, l := range records(ls, nonce) {
		f := strings.SplitN(l, ":", 4)
		if len(f) != 4 {
			continue
		}
		pid, _ = strconv.ParseInt(f[0], 10, 64)
		if len(validName(f[3])) > MaxRowField {
			long++ // never cut: left out (review N-7)
			continue
		}
		cr, _ := strconv.ParseInt(f[1], 10, 64)
		at, _ := strconv.ParseInt(f[2], 10, 64)
		rows[f[3]] = Row{Name: f[3], Created: cr, Clients: at}
	}
	// The active pane of the active window stands for the session (dodatek A6). The nonce is readable in /proc while
	// the call runs, so a pane process can still forge a whole record (review N-8): a pane_id that appears twice in one
	// listing drops every record with it, and a session with more than one active record in one listing takes none.
	// A session without exactly one record left (forged, or created or killed between the two calls) keeps its
	// previous row, or waits for the next poll.
	var panes [][]string
	ids, active := map[string]int{}, map[string]int{}
	for _, l := range records(lp, nonce) {
		if f := strings.SplitN(l, ":", 7); len(f) == 7 {
			panes = append(panes, f)
			ids[f[2]]++
		}
	}
	for _, f := range panes {
		if ids[f[2]] == 1 && f[0] == "1" && f[1] == "1" {
			active[f[5]]++
		}
	}
	seen := map[string]bool{}
	for _, f := range panes {
		if ids[f[2]] != 1 || f[0] != "1" || f[1] != "1" || active[f[5]] != 1 {
			continue
		}
		r, ok := rows[f[5]]
		if !ok {
			continue
		}
		r.PanePid, _ = strconv.ParseInt(f[3], 10, 64)
		r.Dead, r.Cmd = f[4] == "1", f[6]
		rows[f[5]] = r
		seen[f[5]] = true
	}
	if long != s.long {
		s.log.Log("sess-name-too-long", "sessions", long, "max", MaxRowField, "why", "sess name too long")
		s.long = long
	}
	s.mu.Lock()
	for n := range rows {
		if !seen[n] {
			if old, ok := s.table[n]; ok {
				rows[n] = old
			} else {
				delete(rows, n)
			}
		}
	}
	s.mu.Unlock()
	return rows, identity{inode: inode, pid: pid, start: tmuxgate.StartTime(pid)}, true
}

// records splits tmux format output into records that start with "<nonce>:" (the prefix removed); a line without the
// prefix is appended, with its newline, to the record before it.
func records(out []byte, nonce string) []string {
	var recs []string
	pre := nonce + ":"
	for _, l := range strings.Split(strings.TrimSuffix(string(out), "\n"), "\n") {
		if rest, ok := strings.CutPrefix(l, pre); ok {
			recs = append(recs, rest)
		} else if len(recs) > 0 {
			recs[len(recs)-1] += "\n" + l
		}
	}
	return recs
}

func newNonce() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// logFail logs a failed read once per streak (verb, exit status; tmux's stderr is not logged: it may name sessions).
func (s *sessProvider) logFail(verb string, err error) {
	if s.failing.Swap(true) {
		return
	}
	s.log.Log("sess-read-failed", "verb", verb, "err", err)
}

func (s *sessProvider) poll() {
	rows, id, ok := s.readFn()
	if !ok {
		s.mu.Lock()
		s.pidOK = false
		s.mu.Unlock()
		return
	}
	s.failing.Store(false)
	// The table change and its seq are one step under seq.mu, so a SNAP never pairs a table with another seq.
	s.seq.mu.Lock()
	s.mu.Lock()
	first := !s.known
	changed := s.known && id != s.id
	s.id, s.pidOK = id, true
	var ops []any
	if first || changed {
		s.table, s.known, s.held = rows, true, nil
	} else {
		ops = s.diff(rows)
	}
	waiting := s.waiting
	s.waiting = map[string]waitSub{}
	s.mu.Unlock()
	switch {
	case changed:
		// spec §4.5: a new server is SNAP{tmux-restart} with the new table, never a del per row
		s.seq.ring.Clear("tmux-restart")
		for _, sub := range s.seq.subs {
			s.seq.snapshot(sub, "tmux-restart", nil)
		}
		s.log.Log("tmux-restart", "pid", id.pid)
	case len(ops) > 0:
		s.seq.appendLocked("sess", nil, ops)
	}
	s.seq.mu.Unlock()
	for _, w := range waiting {
		// a waiting subscriber was told "snapshot" (or nothing): it gets SNAP whatever its position (review K-F11)
		p := w.p
		if p != nil && p.Ep == s.seq.ring.Ep {
			p = nil
		}
		s.seq.subscribe(w.c, p)
	}
}

// diff applies rows to the table and returns the ops. More than half of the rows vanishing at once is held one
// poll and sent only when the next read confirms it (spec §4.5 mass-drop hold).
func (s *sessProvider) diff(rows map[string]Row) []any {
	var gone []string
	for n := range s.table {
		if _, ok := rows[n]; !ok {
			gone = append(gone, n)
		}
	}
	if len(s.table) > 0 && 2*len(gone) > len(s.table) {
		confirmed := len(s.held) > 0
		for n := range s.held {
			if _, back := rows[n]; back {
				confirmed = false
			}
		}
		if confirmed {
			s.held = nil
		} else {
			s.held = map[string]bool{}
			for _, n := range gone {
				s.held[n] = true
				rows[n] = s.table[n] // keep the vanished rows for this poll
			}
			gone = nil
		}
	} else {
		s.held = nil
	}
	var puts []string
	for n, r := range rows {
		if old, ok := s.table[n]; !ok || old != r {
			puts = append(puts, n)
		}
	}
	sort.Strings(puts)
	sort.Strings(gone)
	var ops []any
	for _, n := range puts {
		ops = append(ops, crp.Obj{"op": "put", "row": rows[n].obj()})
	}
	for _, n := range gone {
		ops = append(ops, crp.Obj{"op": "del", "name": validName(n)})
	}
	s.table = rows
	return ops
}
