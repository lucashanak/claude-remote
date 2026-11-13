package client

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"encoding/base64"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
)

// TxSpec is one transcript watch of the probe.
type TxSpec struct {
	Wid, UUID, Cwd string
	Off            int64
	Gzip           bool
}

// ProbeOpts configure `crd probe` (B1a scope: handshake, SUB ev/sess/tx, ACK, output lines; the rpc mix is B1b).
type ProbeOpts struct {
	Socket    string // "" = run "<this binary> --stdio" and talk over its stdin/stdout, like the app's exec channel
	Subs      []string
	Tx        []TxSpec
	TxOut     string
	Resume    crp.Obj
	Dev, Link string
	Att       int64
	For       time.Duration
	Items     int
	PingMs    int64 // HELLO.pingMs and the PING interval (spec §2.4); 0 = DefaultPingMs
}

// Exit codes of the probe.
const (
	ProbeOK    = 0
	ProbeError = 1
	ProbeGap   = 3 // durable gap: the client would close and resume (spec §4.2)
	ProbeBye   = 4 // the daemon sent BYE
)

type rwc struct {
	io.Reader
	io.Writer
	close func() error
}

func (r rwc) Close() error { return r.close() }

// startShim runs `<self> --stdio` as the exec channel would.
func startShim() (io.ReadWriteCloser, error) {
	self, err := os.Executable()
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(self, "--stdio")
	cmd.Stderr = os.Stderr
	in, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	out, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	var once sync.Once
	var werr error
	return rwc{out, in, func() error {
		once.Do(func() { in.Close(); werr = cmd.Wait() })
		return werr
	}}, nil
}

// Probe runs the client and prints one line per received item:
//
//	welcome <canonical header>
//	ev <ep> <seq> <src> <base64 line>
//	sess <ep> <seq> <canonical ops>
//	snap <st> <ep> <seq> <from> <reason>      (sess also: sesstable <canonical sessions>)
//	tx <wid> <f> <o> reset=<bool> more=<bool> (bytes appended to <TxOut>/<wid> at f - base of the last reset)
//	gap <st> <ep> <seq> <step>
//	bye <code> <msg>
func Probe(o ProbeOpts, stdout io.Writer) int {
	out := &lineWriter{w: bufio.NewWriter(stdout)}
	defer out.flush()
	var conn io.ReadWriteCloser
	var err error
	if o.Socket != "" {
		conn, err = DialSocket(o.Socket)
	} else {
		conn, err = startShim()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "crd probe:", err)
		return ProbeError
	}
	defer conn.Close()
	feats := []string{"ev", "sess", "tx", "gzip"}
	if o.PingMs <= 0 {
		o.PingMs = DefaultPingMs
	}
	hello := Hello(o.Dev, o.Link, o.Att, feats, o.Resume)
	hello["pingMs"] = o.PingMs
	c, err := Handshake(conn, hello)
	if err != nil {
		fmt.Fprintln(os.Stderr, "crd probe: handshake:", err)
		return ProbeError
	}
	if c.First.Type == crp.BYE {
		return out.bye(c.First)
	}
	out.line("welcome " + canon(c.First.Header))
	streams := map[string]*Stream{}
	for st, v := range o.Resume {
		p, _ := v.(map[string]any)
		ep, _ := crp.Str(p, "ep")
		seq, _ := crp.Int(p, "seq")
		streams[st] = NewStream(ep, seq)
	}
	for _, st := range o.Subs {
		if _, ok := streams[st]; ok {
			continue // HELLO.resume already subscribed it
		}
		streams[st] = NewStream("", 0)
		if err := c.Send(crp.NewFrame(crp.SUB, crp.Obj{"st": st}, nil)); err != nil {
			return ProbeError
		}
	}
	txf := map[string]*txFile{}
	for _, t := range o.Tx {
		enc := "none"
		if t.Gzip {
			enc = "gzip"
		}
		h := crp.Obj{"st": "tx", "wid": t.Wid, "uuid": t.UUID, "cwd": t.Cwd, "off": t.Off, "enc": enc}
		if err := c.Send(crp.NewFrame(crp.SUB, h, nil)); err != nil {
			return ProbeError
		}
		txf[t.Wid] = &txFile{dir: o.TxOut, wid: t.Wid}
	}
	acks := &acker{c: c, pos: map[string][2]any{}}
	stop := make(chan struct{})
	defer close(stop)
	go acks.loop(stop)
	// spec §2.4: the client pings when nothing has been received for pingMs; the probe pings every pingMs, which is
	// at least that often, so the daemon's idle limit max(45 s, 3 × pingMs) never closes a quiet probe (review K-F3).
	go pinger(c, time.Duration(o.PingMs)*time.Millisecond, stop)
	if o.For > 0 {
		if d, ok := conn.(interface{ SetReadDeadline(time.Time) error }); ok {
			_ = d.SetReadDeadline(time.Now().Add(o.For))
		} else {
			timer := time.AfterFunc(o.For, func() { conn.Close() })
			defer timer.Stop()
		}
	}
	items := 0
	for o.Items == 0 || items < o.Items {
		f, err := c.Next()
		if err != nil {
			break
		}
		switch f.Type {
		case crp.BYE:
			acks.flush()
			return out.bye(f)
		case crp.SNAP:
			st, _ := crp.Str(f.Header, "st")
			ep, _ := crp.Str(f.Header, "ep")
			S, _ := crp.Int(f.Header, "seq")
			F, _ := crp.Int(f.Header, "from")
			reason, _ := crp.Str(f.Header, "reason")
			out.line(fmt.Sprintf("snap %s %s %d %d %s", st, ep, S, F, reason))
			if st == "sess" {
				out.line("sesstable " + canon(f.Header["sessions"]))
			}
			s := streams[st]
			if s == nil {
				s = NewStream("", 0)
				streams[st] = s
			}
			s.OnSnap(ep, S, F)
			if !s.InBacklog() {
				acks.set(st, ep, s.Pos)
			}
		case crp.EV:
			st, _ := crp.Str(f.Header, "st")
			ep, _ := crp.Str(f.Header, "ep")
			seq, _ := crp.Int(f.Header, "seq")
			s := streams[st]
			if s == nil {
				s = NewStream("", 0)
				streams[st] = s
			}
			step := s.OnItem(ep, seq)
			switch step {
			case Processed:
				items++
				if st == "ev" {
					src, _ := crp.Str(f.Header, "src")
					out.line(fmt.Sprintf("ev %s %d %s %s", ep, seq, src, base64.StdEncoding.EncodeToString(f.Blob)))
				} else {
					out.line(fmt.Sprintf("sess %s %d %s", ep, seq, canon(f.Header["ops"])))
				}
				if !s.InBacklog() {
					acks.set(st, ep, s.Pos)
				}
			case Dup:
			default:
				out.line(fmt.Sprintf("gap %s %s %d %s", st, ep, seq, step))
				acks.flush()
				return ProbeGap
			}
		case crp.TX:
			wid, _ := crp.Str(f.Header, "wid")
			fo, _ := crp.Int(f.Header, "f")
			oo, _ := crp.Int(f.Header, "o")
			enc, _ := crp.Str(f.Header, "enc")
			reset, _ := crp.Bool(f.Header, "reset")
			more, _ := crp.Bool(f.Header, "more")
			blob := f.Blob
			if enc == "gzip" {
				zr, err := gzip.NewReader(bytes.NewReader(blob))
				if err != nil {
					return ProbeError
				}
				if blob, err = io.ReadAll(zr); err != nil {
					return ProbeError
				}
			}
			if tf := txf[wid]; tf != nil && o.TxOut != "" {
				if err := tf.write(fo, blob, reset); err != nil {
					fmt.Fprintln(os.Stderr, "crd probe: tx out:", err)
					return ProbeError
				}
			}
			out.line(fmt.Sprintf("tx %s %d %d reset=%v more=%v", wid, fo, oo, reset, more))
			_ = c.Send(crp.NewFrame(crp.ACK, crp.Obj{"acks": crp.Obj{}, "tx": crp.Obj{wid: oo}}, nil))
			if !more {
				items++
			}
		}
	}
	acks.flush()
	return ProbeOK
}

// canon is the canonical JSON of a received value (always encodable: it was decoded under the same rules).
func canon(v any) string {
	b, err := crp.Canon(v)
	if err != nil {
		return "null"
	}
	return string(b)
}

func pinger(c *Client, every time.Duration, stop <-chan struct{}) {
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			if c.Send(crp.NewFrame(crp.PING, crp.Obj{"t": time.Now().UnixMilli()}, nil)) != nil {
				return
			}
		}
	}
}

type lineWriter struct {
	mu sync.Mutex
	w  *bufio.Writer
}

func (l *lineWriter) line(s string) {
	l.mu.Lock()
	l.w.WriteString(s + "\n")
	l.w.Flush()
	l.mu.Unlock()
}

func (l *lineWriter) flush() {
	l.mu.Lock()
	l.w.Flush()
	l.mu.Unlock()
}

func (l *lineWriter) bye(f crp.Frame) int {
	code, _ := crp.Str(f.Header, "code")
	msg, _ := crp.Str(f.Header, "msg")
	l.line(fmt.Sprintf("bye %s %s", code, msg))
	return ProbeBye
}

// acker sends the processed positions at most once per second or every 64 items, and before closing (§4.2).
type acker struct {
	mu    sync.Mutex
	c     *Client
	pos   map[string][2]any
	n     int
	dirty bool
}

func (a *acker) set(st, ep string, seq int64) {
	a.mu.Lock()
	a.pos[st] = [2]any{ep, seq}
	a.dirty = true
	a.n++
	n := a.n
	a.mu.Unlock()
	if n >= 64 {
		a.flush()
	}
}

func (a *acker) flush() {
	a.mu.Lock()
	defer a.mu.Unlock()
	if !a.dirty {
		return
	}
	acks := crp.Obj{}
	names := make([]string, 0, len(a.pos))
	for st := range a.pos {
		names = append(names, st)
	}
	sort.Strings(names)
	for _, st := range names {
		acks[st] = crp.Obj{"ep": a.pos[st][0], "seq": a.pos[st][1]}
	}
	_ = a.c.Send(crp.NewFrame(crp.ACK, crp.Obj{"acks": acks}, nil))
	a.dirty, a.n = false, 0
}

func (a *acker) loop(stop <-chan struct{}) {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			a.flush()
		}
	}
}

type txFile struct {
	dir, wid string
	base     int64
	f        *os.File
}

func (t *txFile) write(f int64, b []byte, reset bool) error {
	if t.f == nil {
		var err error
		if t.f, err = os.OpenFile(filepath.Join(t.dir, t.wid), os.O_RDWR|os.O_CREATE, 0o600); err != nil {
			return err
		}
		t.base = f
	}
	if reset {
		if err := t.f.Truncate(0); err != nil {
			return err
		}
		t.base = f
	}
	_, err := t.f.WriteAt(b, f-t.base)
	return err
}
