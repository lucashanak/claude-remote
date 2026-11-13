package daemon

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
)

// Handshake timeouts on the daemon side (dodatek A3): prelude within 5 s of accept, HELLO within 10 s of it.
const (
	PreludeTimeout = 5 * time.Second
	HelloTimeout   = 10 * time.Second
	preludeMax     = 16 << 10
	healthyWithin  = 10 * time.Second // watchdog: every heartbeat younger than this (spec §7.2)
)

// Server is one `crd serve`.
type Server struct {
	P          Paths
	Log        *Logger
	minIdle    time.Duration
	statsEvery time.Duration
	writeTO    time.Duration

	ln      *net.UnixListener
	lockF   *os.File
	table   *ConnTable
	streams []Stream
	byName  map[string]Stream
	feats   []string
	ep      string
	inGen   string
	host    Host
	started time.Time
	nconn   atomic.Int64
	hbAcc   atomic.Int64
	qTotal  atomic.Int64 // bytes in all writer queues (review N-2)
	shedMu  sync.Mutex

	mu       sync.Mutex
	conns    map[string]*conn
	hs       map[*net.UnixConn]bool // connections still in the handshake (shutdown answers them too)
	hsWG     sync.WaitGroup
	off      bool
	stopping bool
	stop     chan struct{}
	stopOnce sync.Once
}

// Serve runs the daemon until SIGTERM/SIGINT. It returns the exit code.
func Serve(p Paths, stderr io.Writer) int {
	// SIGTERM during seeding is a graceful stop too, not the default exit (review K-F12).
	sig := make(chan os.Signal, 2)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	syscall.Umask(0o077)
	// spec §6.3: TMUX/TMUX_PANE would override the socket choice of every tmux child.
	os.Unsetenv("TMUX")
	os.Unsetenv("TMUX_PANE")
	// ~/.claude-remote is the app's live directory: created if missing, its mode left alone; 0700 only on crd's own
	// subtree (spec §7.4, review K-F7).
	if err := os.MkdirAll(p.CR, 0o700); err != nil {
		fmt.Fprintln(stderr, "crd:", err)
		return 1
	}
	for _, d := range []string{p.CRDir, p.State, p.Run} {
		if err := mkdirPrivate(d); err != nil {
			fmt.Fprintln(stderr, "crd:", err)
			return 1
		}
	}
	lg, err := OpenLog(p.Log)
	if err != nil {
		fmt.Fprintln(stderr, "crd: log:", err)
		return 1
	}
	defer lg.Close()
	if p.Test {
		lg.under = p.UnderRoot
	}
	lockF, err := flockNB(p.Lock)
	if err != nil {
		lg.Log("second-instance", "lock", p.Lock)
		return 0
	}
	defer lockF.Close()
	s := &Server{P: p, Log: lg, lockF: lockF, table: NewConnTable(), conns: map[string]*conn{}, hs: map[*net.UnixConn]bool{},
		byName: map[string]Stream{}, stop: make(chan struct{}), started: time.Now(), minIdle: IdleFloor,
		statsEvery: 60 * time.Second, writeTO: WriteTimeout}
	if p.Test {
		if ms, err := strconv.Atoi(os.Getenv("CRD_TEST_MIN_IDLE_MS")); err == nil && ms > 0 {
			s.minIdle = time.Duration(ms) * time.Millisecond
		}
		if ms, err := strconv.Atoi(os.Getenv("CRD_TEST_STATS_MS")); err == nil && ms > 0 {
			s.statsEvery = time.Duration(ms) * time.Millisecond
		}
		if ms, err := strconv.Atoi(os.Getenv("CRD_TEST_WRITE_MS")); err == nil && ms > 0 {
			s.writeTO = time.Duration(ms) * time.Millisecond
		}
	} else {
		for _, kv := range os.Environ() {
			if strings.HasPrefix(kv, "CRD_") {
				lg.Log("ignored-env", "name", strings.SplitN(kv, "=", 2)[0])
			}
		}
	}
	s.ep, s.inGen = randHex(8), randHex(8)
	s.host.ID, s.host.Boot = hostFacts()
	env := &Env{Paths: p, Log: lg, Now: time.Now, Ep: s.ep}
	for _, f := range factories {
		st, err := f(env)
		if err != nil {
			lg.Log("stream-init-failed", "err", err)
			return 1
		}
		s.streams = append(s.streams, st)
		s.byName[st.Name()] = st
	}
	s.feats = offered(s.streams)
	if s.ln, err = listen(p.Sock); err != nil {
		lg.Log("listen-failed", "err", err)
		return 1
	}
	s.hbAcc.Store(time.Now().UnixNano())
	// crd.off present at start: the providers start stopped (no rotation, no polling) before their first round
	if _, err := os.Stat(p.Off); err == nil {
		s.setOff(true)
	}
	for _, st := range s.streams {
		go st.Start(s.stop)
	}
	go s.watchOff()
	go s.acceptLoop()
	go s.statsLoop()
	go s.watchdog()
	lg.Log("start", "ep", s.ep, "feats", strings.Join(s.feats, ","), "test", p.Test, "pid", os.Getpid())
	_ = sdNotify("READY=1")
	<-sig
	s.shutdown()
	return 0
}

// shutdown is the graceful stop of spec §7.2 (without the IN part, B3): stop accepting, BYE E_SHUTDOWN on every
// connection, flush, exit. A connection still in its handshake is woken (read deadline now) and answers
// E_SHUTDOWN itself (review A-F13).
func (s *Server) shutdown() {
	s.mu.Lock()
	s.stopping = true
	conns := make([]*conn, 0, len(s.conns))
	for _, c := range s.conns {
		conns = append(conns, c)
	}
	for nc := range s.hs {
		_ = nc.SetReadDeadline(time.Now())
	}
	s.mu.Unlock()
	_ = sdNotify("STOPPING=1")
	s.ln.Close()
	bye := ShutdownBye()
	for _, c := range conns {
		c.terminate(&bye, "shutdown")
	}
	deadline := time.After(2 * time.Second)
	hsDone := make(chan struct{})
	go func() { s.hsWG.Wait(); close(hsDone) }()
	for _, c := range conns {
		select {
		case <-c.wdone:
		case <-deadline:
		}
	}
	select {
	case <-hsDone:
	case <-deadline:
	}
	s.stopOnce.Do(func() { close(s.stop) })
	s.Log.Log("stop", "conns", len(conns))
}

func (s *Server) acceptLoop() {
	for {
		s.hbAcc.Store(time.Now().UnixNano())
		_ = s.ln.SetDeadline(time.Now().Add(time.Second))
		c, err := s.ln.AcceptUnix()
		if err != nil {
			var ne net.Error
			if errors.As(err, &ne) && ne.Timeout() {
				continue
			}
			s.mu.Lock()
			stopping := s.stopping
			s.mu.Unlock()
			if stopping {
				return
			}
			s.Log.Log("accept-error", "err", err)
			time.Sleep(100 * time.Millisecond)
			continue
		}
		go s.handle(c)
	}
}

// Healthy is the watchdog condition: the accept loop and every provider heartbeat are younger than 10 s.
func (s *Server) Healthy() bool {
	now := time.Now()
	if now.Sub(time.Unix(0, s.hbAcc.Load())) > healthyWithin {
		return false
	}
	for _, st := range s.streams {
		if now.Sub(st.Heartbeat()) > healthyWithin {
			return false
		}
	}
	return true
}

func (s *Server) watchdog() {
	iv := watchdogInterval()
	if iv <= 0 {
		return
	}
	t := time.NewTicker(iv)
	defer t.Stop()
	for {
		select {
		case <-s.stop:
			return
		case <-t.C:
			if s.Healthy() {
				_ = sdNotify("WATCHDOG=1")
			}
		}
	}
}

// watchOff follows crd.off (spec §1.4): inotify on ~/.claude-remote plus a 1 s stat backstop.
func (s *Server) watchOff() {
	kick := make(chan struct{}, 1)
	if in, err := NewInotify(); err == nil {
		defer in.Close()
		_ = in.AddDir(s.P.CR)
		go in.Run(func(_, name string) {
			if name == "crd.off" || name == "" {
				select {
				case kick <- struct{}{}:
				default:
				}
			}
		})
	}
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for {
		_, err := os.Stat(s.P.Off)
		s.setOff(err == nil)
		select {
		case <-s.stop:
			return
		case <-kick:
		case <-t.C:
		}
	}
}

func (s *Server) setOff(off bool) {
	s.mu.Lock()
	if s.off == off {
		s.mu.Unlock()
		return
	}
	s.off = off
	conns := make([]*conn, 0, len(s.conns))
	for _, c := range s.conns {
		conns = append(conns, c)
	}
	s.mu.Unlock()
	s.Log.Log("crd-off", "on", off, "conns", len(conns))
	// The providers first, so a round already reading ends before a client hears E_OFF (review A-F3); but never more
	// than offWait: a provider that hangs must not keep the BYE, or the way back when crd.off goes away, from
	// happening (review R-1). Each provider in its own goroutine, so one that hangs does not hold up the others.
	var wg sync.WaitGroup
	for _, st := range s.streams {
		wg.Add(1)
		go func() { defer wg.Done(); st.SetOff(off) }()
	}
	all := make(chan struct{})
	go func() { wg.Wait(); close(all) }()
	select {
	case <-all:
	case <-time.After(offWait):
		s.Log.Log("crd-off-slow", "on", off, "waited_ms", offWait.Milliseconds())
	}
	if off {
		bye := Bye(crp.EOff, MsgOff)
		for _, c := range conns {
			c.terminate(&bye, "off")
		}
	}
}

// Off reports the crd.off state (stats, tests).
func (s *Server) Off() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.off
}

func (s *Server) statsLoop() {
	t := time.NewTicker(s.statsEvery)
	defer t.Stop()
	for {
		select {
		case <-s.stop:
			return
		case <-t.C:
			s.Log.Line(s.StatsLine())
		}
	}
}

// StatsLine is `crd stats …` with the fields B1a has, in spec order (§7.6); rpc/in/attach fields are left out.
func (s *Server) StatsLine() string {
	s.mu.Lock()
	n := len(s.conns)
	s.mu.Unlock()
	vals := map[string]string{}
	for _, st := range s.streams {
		st.Stats(func(k, v string) { vals[k] = v })
	}
	var b strings.Builder
	fmt.Fprintf(&b, "crd stats conns=%d devs=%d", n, s.table.Devs())
	for _, k := range []string{"ev_seq", "ring_bytes", "ev_dropped", "tx_watches", "tmux_pid"} {
		if v, ok := vals[k]; ok {
			fmt.Fprintf(&b, " %s=%s", k, v)
		}
	}
	fmt.Fprintf(&b, " rss_kb=%d up_s=%d", rssKB(), int64(time.Since(s.started).Seconds()))
	return b.String()
}

// encodeBye encodes a handshake-time frame the daemon builds; nil when it cannot (never a panic).
func encodeBye(f crp.Frame) []byte {
	b, err := crp.Encode(f)
	if err != nil {
		return nil
	}
	return b
}

// writeRaw answers a connection that never got a writer: magic + one frame, then close.
func writeRaw(c net.Conn, f crp.Frame) {
	_ = c.SetWriteDeadline(time.Now().Add(2 * time.Second))
	_, _ = c.Write(append([]byte(crp.Magic), encodeBye(f)...))
	c.Close()
}

func (s *Server) stoppingNow() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.stopping
}

// endHandshake removes nc from the handshake set once (registration or refusal).
func (s *Server) endHandshake(nc *net.UnixConn, once *bool) {
	if *once {
		return
	}
	*once = true
	s.mu.Lock()
	delete(s.hs, nc)
	s.mu.Unlock()
	s.hsWG.Done()
}

func (s *Server) handle(nc *net.UnixConn) {
	var c *conn
	// The general backstop (dodatek 2 P1.1): a panic in this connection's goroutine closes this connection only.
	defer func() {
		if r := recover(); r != nil {
			s.Log.Log("CRITICAL", "panic", TruncUTF8(fmt.Sprint(r), 200), "conn", connID(c))
			if c != nil {
				s.closeConn(c)
			}
			nc.Close()
		}
	}()
	uid, err := peerUID(nc)
	if err != nil || uid != os.Getuid() {
		s.Log.Log("peer-refused", "uid", uid)
		nc.Close()
		return
	}
	s.mu.Lock()
	if s.stopping {
		s.mu.Unlock()
		writeRaw(nc, ShutdownBye())
		return
	}
	if n := len(s.conns) + len(s.hs); n >= MaxConnsHost {
		s.mu.Unlock()
		s.Log.Log("conn-limit", "conns", n)
		writeRaw(nc, BusyBye())
		return
	}
	s.hs[nc] = true
	s.hsWG.Add(1)
	s.mu.Unlock()
	hsDone := false
	defer s.endHandshake(nc, &hsDone)
	_ = nc.SetDeadline(time.Now().Add(PreludeTimeout))
	br := bufio.NewReaderSize(nc, 64<<10)
	if err := readPrelude(br); err != nil {
		if s.stoppingNow() {
			writeRaw(nc, ShutdownBye())
			return
		}
		s.Log.Log("prelude-refused", "err", err)
		nc.Close()
		return
	}
	s.mu.Lock()
	off, stopping := s.off, s.stopping
	s.mu.Unlock()
	if off {
		writeRaw(nc, Bye(crp.EOff, MsgOff)) // R8: accept, answer E_OFF, close
		return
	}
	if stopping {
		writeRaw(nc, ShutdownBye())
		return
	}
	if _, err := nc.Write([]byte(crp.Magic)); err != nil {
		nc.Close()
		return
	}
	_ = nc.SetDeadline(time.Now().Add(HelloTimeout))
	fail := func(code, msg string) {
		_, _ = nc.Write(encodeBye(Bye(code, msg)))
		nc.Close()
	}
	failRead := func(err error) {
		var pe *crp.ProtoError
		switch {
		case s.stoppingNow():
			_, _ = nc.Write(encodeBye(ShutdownBye()))
			nc.Close()
		case errors.As(err, &pe):
			fail(pe.Code, pe.Msg)
		default:
			nc.Close()
		}
	}
	if err := crp.ReadMagic(br); err != nil {
		failRead(err)
		return
	}
	rd := crp.NewReader(br, crp.C2S)
	hf, err := rd.Next()
	if err != nil {
		failRead(err)
		return
	}
	neg := Negotiate(hf.Header, ProtoMin, ProtoMax, s.feats, crp.DefaultMaxFrame)
	if !neg.OK {
		_, _ = nc.Write(encodeBye(neg.ByeFrame()))
		nc.Close()
		return
	}
	dev, _ := crp.Str(hf.Header, "dev")
	link, _ := crp.Str(hf.Header, "link")
	att, _ := crp.Int(hf.Header, "att")
	if !ValidKey(dev, link) {
		fail(crp.EProto, "bad dev or link")
		return
	}
	rd.MaxFrame = int(neg.MaxFrame)
	id := fmt.Sprintf("c-%04d", s.nconn.Add(1))
	c = &conn{s: s, nc: nc, id: id, dev: dev, link: link, att: att, feats: map[string]bool{}, done: make(chan struct{}),
		wdone: make(chan struct{}), resume: map[string]crp.Obj{}}
	for _, f := range neg.Feats {
		c.feats[f] = true
	}
	if pm, ok := crp.Int(hf.Header, "pingMs"); ok && pm > 0 {
		c.pingMs.Store(ClampPingMs(pm))
	}
	if rs, ok := crp.Sub(hf.Header, "resume"); ok {
		for st, v := range rs {
			if o, ok := v.(map[string]any); ok {
				c.resume[st] = o
			}
		}
	}
	c.w = newWriter(nc, int(neg.MaxFrame), func() { nc.Close(); close(c.wdone) })
	c.w.deadline, c.w.kill, c.w.timeout = nc.SetWriteDeadline, func() { nc.Close() }, s.writeTO
	c.w.host = &s.qTotal
	c.w.failed = func(err error) {
		var ne net.Error
		if errors.As(err, &ne) && ne.Timeout() {
			c.setCause("write-timeout") // the client stopped reading (review N-6), not its EOF
		}
	}
	s.mu.Lock()
	// crd.off or SIGTERM may have come after the check behind the prelude: registration and the check are one step
	// under s.mu, and setOff/shutdown take their snapshot of s.conns under the same lock (review K-F1/A-F3/A-F13).
	if s.off || s.stopping {
		off := s.off
		s.mu.Unlock()
		if off {
			fail(crp.EOff, MsgOff)
		} else {
			_, _ = nc.Write(encodeBye(ShutdownBye()))
			nc.Close()
		}
		return
	}
	res := s.table.Hello(id, dev, link, att)
	if !res.Accept {
		s.mu.Unlock()
		s.Log.Log("conn-stale", "dev", dev, "link", link, "att", att, "live", res.Current)
		fail(crp.Stale, MsgStale)
		return
	}
	old := s.conns[res.Superseded]
	s.conns[id] = c
	s.mu.Unlock()
	s.endHandshake(nc, &hsDone)
	if old != nil {
		old.supersede()
	}
	// HELLO.resume subscribes the named durable streams at once (vectors 06, 07, 08, 27); their replay or SNAP is
	// queued behind WELCOME, which the writer sends first (it has the highest class and the writer starts after it).
	streams, resumed := crp.Obj{}, crp.Obj{}
	for _, st := range s.streams {
		if !c.feats[st.Name()] {
			continue
		}
		if info, ok := st.Info(); ok {
			streams[st.Name()] = info
		}
	}
	names := make([]string, 0, len(c.resume))
	for st := range c.resume {
		names = append(names, st)
	}
	sort.Strings(names)
	for _, name := range names {
		st := s.byName[name]
		if st == nil || !c.feats[name] {
			continue
		}
		pos := c.resume[name]
		h := crp.Obj{"st": name, "ep": pos["ep"], "seq": pos["seq"]}
		r, err := st.Sub(c, h)
		if err != nil {
			s.closeConn(c)
			fail(crp.EProto, err.Error())
			c.w.abort() // the writer never ran: what HELLO.resume queued leaves the host's sum
			return
		}
		if r != "" {
			resumed[name] = r
		}
		c.subs.Store(name, true)
	}
	host := s.host
	for _, st := range s.streams {
		if tp, ok := st.(TmuxPider); ok {
			host.TmuxPid, host.HasTmuxP = tp.TmuxPid()
		}
	}
	wh := WelcomeHeader(neg, id, cliSrv(), time.Now().UnixMilli(), host, streams, s.inGen, resumed)
	welcome := crp.NewFrame(crp.WELCOME, wh, nil)
	if b, err := crp.Encode(welcome); err == nil && len(b)-4 > crp.MaxWelcome {
		s.Log.Log("welcome-too-big", "conn", id)
	}
	c.Send(welcome)
	go c.w.runGuarded(s.Log, id)
	s.Log.Log("conn-open", "conn", id, "dev", dev, "link", link, "att", att, "superseded", res.Superseded)
	c.readLoop(rd)
	s.closeConn(c)
}

// SrvString is WELCOME.srv; set by the cli package (crd version string).
var SrvString = "crd"

func cliSrv() string { return SrvString }

func readPrelude(br *bufio.Reader) error {
	line, err := br.ReadSlice('\n')
	if err != nil {
		return err
	}
	if len(line) > preludeMax {
		return errors.New("prelude too long")
	}
	rest, ok := strings.CutPrefix(string(line), "crd-shim 1 ")
	if !ok {
		return errors.New("not a crd-shim 1 prelude")
	}
	var p struct {
		UID int               `json:"uid"`
		Env map[string]string `json:"env"`
	}
	if err := json.Unmarshal([]byte(rest), &p); err != nil {
		return err
	}
	if p.UID != os.Getuid() {
		return errors.New("prelude uid differs")
	}
	return nil
}

func (s *Server) closeConn(c *conn) {
	c.closeOnce.Do(func() {
		s.mu.Lock()
		if s.conns[c.id] == c {
			delete(s.conns, c.id)
		}
		s.table.Gone(c.id, c.dev, c.link)
		s.mu.Unlock()
		// Done first: a provider that registers c after its Gone sees it closed (sess waiting, review K-F4/A-F12).
		close(c.done)
		for _, st := range s.streams {
			st.Gone(c)
		}
		c.w.terminate(nil)
		s.Log.Log("conn-close", "conn", c.id, "dev", c.dev, "link", c.link, "att", c.att, "cause", c.cause())
	})
}

// conn is one CRP connection.
type conn struct {
	s             *Server
	nc            *net.UnixConn
	id, dev, link string
	att           int64
	feats         map[string]bool
	resume        map[string]crp.Obj
	w             *writer
	pingMs        atomic.Int64
	done, wdone   chan struct{}
	closeOnce     sync.Once
	subs          sync.Map
	dispatchMu    sync.Mutex
	dead          atomic.Bool
	causeMu       sync.Mutex
	closeCause    string
}

func (c *conn) ID() string            { return c.id }
func (c *conn) Feat(name string) bool { return c.feats[name] }

func connID(c *conn) string {
	if c == nil {
		return "-"
	}
	return c.id
}

// Send queues f (writer.go). A frame this connection cannot carry ends this connection only: BYE E_FRAME when the
// frame is over the negotiated maxFrame or does not encode, a close without BYE when the queue is over its limit.
func (c *conn) Send(f crp.Frame) {
	switch err := c.w.send(f); {
	case err == nil:
		if c.s.qTotal.Load() > hostQueueBudget {
			c.s.shedQueues()
		}
	case errors.Is(err, errOverflow):
		c.s.Log.Log("conn-overflow", "conn", c.id, "type", f.Type.Name())
		c.terminate(nil, "overflow")
	default:
		c.s.Log.Log("frame-refused", "conn", c.id, "type", f.Type.Name(), "err", TruncUTF8(err.Error(), 120))
		c.Fail(crp.EFrame, "frame over maxFrame")
	}
}
func (c *conn) Done() <-chan struct{} { return c.done }

func (c *conn) Resume(st string) (string, int64, bool) {
	o, ok := c.resume[st]
	if !ok {
		return "", 0, false
	}
	ep, ok1 := crp.Str(o, "ep")
	seq, ok2 := crp.Int(o, "seq")
	return ep, seq, ok1 && ok2
}

func (c *conn) Fail(code, msg string) {
	bye := Bye(code, msg)
	c.terminate(&bye, code)
}

func (c *conn) terminate(bye *crp.Frame, cause string) {
	c.setCause(cause)
	c.dead.Store(true)
	c.w.terminate(bye)
}

func (c *conn) setCause(cause string) {
	c.causeMu.Lock()
	if c.closeCause == "" {
		c.closeCause = cause
	}
	c.causeMu.Unlock()
}

func (c *conn) cause() string {
	c.causeMu.Lock()
	defer c.causeMu.Unlock()
	if c.closeCause == "" {
		return "eof"
	}
	return c.closeCause
}

// supersede waits for a dispatch in progress; every frame read afterwards is discarded (spec §2.5, vector 24).
func (c *conn) supersede() {
	c.dispatchMu.Lock()
	c.dead.Store(true)
	c.dispatchMu.Unlock()
	bye := Bye(crp.Superseded, MsgSuperseded)
	c.terminate(&bye, "superseded")
}

func (c *conn) readLoop(rd *crp.Reader) {
	for {
		pm := c.pingMs.Load()
		_ = c.nc.SetReadDeadline(time.Now().Add(IdleLimit(pm, c.s.minIdle)))
		f, err := rd.Next()
		if err != nil {
			var pe *crp.ProtoError
			var ne net.Error
			switch {
			case errors.As(err, &pe):
				c.Fail(pe.Code, pe.Msg)
			case errors.As(err, &ne) && ne.Timeout():
				c.terminate(nil, "idle")
			default:
				// the client closed its side: what is queued still goes out (a half-closed shim, review K-F9)
				c.setCause("eof")
				c.dead.Store(true)
				c.w.finish()
			}
			return
		}
		c.dispatchMu.Lock()
		if c.dead.Load() {
			c.dispatchMu.Unlock()
			return
		}
		err = c.dispatch(f)
		c.dispatchMu.Unlock()
		if err != nil {
			c.Fail(crp.EProto, err.Error())
			return
		}
	}
}

// featureOf maps the types whose feature B1a never offers (dodatek R4).
var featureOf = map[crp.Type]string{crp.TSIZE: "term", crp.IN: "in", crp.INSKIP: "in", crp.RPC: "rpc.exec"}

func (c *conn) dispatch(f crp.Frame) error {
	if f.Ignored {
		return nil
	}
	switch f.Type {
	case crp.PING:
		t, _ := crp.Int(f.Header, "t")
		c.Send(Pong(t, time.Now().UnixMilli()))
	case crp.PONG:
	case crp.STATE:
		if pm, ok := crp.Int(f.Header, "pingMs"); ok && pm > 0 {
			c.pingMs.Store(ClampPingMs(pm))
		}
	case crp.BYE:
		c.terminate(nil, "client-bye")
	case crp.SUB, crp.UNSUB:
		st, _ := crp.Str(f.Header, "st")
		s := c.s.byName[st]
		if s == nil || !c.feats[st] {
			return fmt.Errorf("%s of a stream not offered: %q", f.Type.Name(), st)
		}
		if f.Type == crp.UNSUB {
			s.Unsub(c, f.Header)
			c.subs.Delete(st)
			return nil
		}
		if _, err := s.Sub(c, f.Header); err != nil {
			return err
		}
		c.subs.Store(st, true)
	case crp.ACK:
		acks, ok := crp.Sub(f.Header, "acks")
		if !ok {
			return errors.New("ACK.acks is not an object")
		}
		for st, v := range acks {
			s := c.s.byName[st]
			a, ok := v.(map[string]any)
			if s == nil || !c.feats[st] || !ok {
				return fmt.Errorf("ACK of a stream not offered: %q", st)
			}
			if err := s.Ack(c, a); err != nil {
				return err
			}
		}
		if tx, ok := f.Header["tx"]; ok {
			m, ok := tx.(map[string]any)
			s := c.s.byName["tx"]
			if !ok || s == nil || !c.feats["tx"] {
				return errors.New("ACK.tx without the tx feature")
			}
			if err := s.Ack(c, m); err != nil {
				return err
			}
		}
	case crp.HELLO:
		return errors.New("HELLO after the handshake")
	default:
		if feat, ok := featureOf[f.Type]; ok {
			return fmt.Errorf("%s without feature %s", f.Type.Name(), feat)
		}
		return fmt.Errorf("%s from a client", f.Type.Name())
	}
	return nil
}

// offWait bounds how long setOff waits for the providers (review R-1).
var offWait = time.Second

// hostQueueBudget is MaxHostQueueBytes (a test lowers it).
var hostQueueBudget int64 = MaxHostQueueBytes

// shedQueues closes the connection with the largest writer queue while the sum of all queues is over the host's
// budget (review N-2): connections that do not read never hold more than the budget together. The closed one sees
// EOF and resumes from its positions like after its own overflow.
func (s *Server) shedQueues() {
	s.shedMu.Lock()
	defer s.shedMu.Unlock()
	for tries := 0; s.qTotal.Load() > hostQueueBudget && tries < MaxConnsHost; tries++ {
		var big *conn
		most := 0
		s.mu.Lock()
		for _, c := range s.conns {
			if q := c.w.queued(); q > most {
				big, most = c, q
			}
		}
		s.mu.Unlock()
		if big == nil {
			return
		}
		s.Log.Log("conn-overflow", "conn", big.id, "scope", "host", "queued", most)
		big.setCause("overflow-host")
		big.dead.Store(true)
		big.w.abort()
	}
}

// Conns is the number of open connections (tests, stats).
func (s *Server) Conns() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.conns)
}
