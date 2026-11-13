// Package client is the CRP client side used by `crd probe` and the tests: the shim prelude, the handshake with
// junk skipping, framed I/O and the durable-stream state machine of spec §4.2 (gen.py ClientStream).
package client

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"sync"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
)

// PreludeEnv lists the variables the shim forwards (spec §2.2); TMUX and TMUX_PANE never.
var PreludeEnv = []string{"PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "XDG_RUNTIME_DIR", "SSH_CONNECTION", "SSH_CLIENT"}

// Prelude is the shim's first line on the unix socket: `crd-shim 1 <json>\n`.
func Prelude(getenv func(string) string) []byte {
	env := map[string]string{}
	for _, k := range PreludeEnv {
		if v := getenv(k); v != "" {
			env[k] = v
		}
	}
	b, _ := json.Marshal(struct {
		UID int               `json:"uid"`
		Env map[string]string `json:"env"`
	}{os.Getuid(), env})
	return append(append([]byte("crd-shim 1 "), b...), '\n')
}

// DialSocket connects to the daemon socket and sends the prelude (what the shim does before relaying).
func DialSocket(sock string) (net.Conn, error) {
	c, err := net.DialTimeout("unix", sock, 2*time.Second)
	if err != nil {
		return nil, err
	}
	if _, err := c.Write(Prelude(os.Getenv)); err != nil {
		c.Close()
		return nil, err
	}
	return c, nil
}

// Client is one CRP connection seen from the client.
type Client struct {
	rw    io.ReadWriter
	br    *bufio.Reader
	rd    *crp.Reader
	wmu   sync.Mutex
	Junk  int
	First crp.Frame // WELCOME or BYE
}

// Handshake writes the magic and HELLO, skips junk before the daemon's magic and reads its first frame.
func Handshake(rw io.ReadWriter, hello crp.Obj) (*Client, error) {
	c := &Client{rw: rw, br: bufio.NewReaderSize(rw, 64<<10)}
	hb, err := crp.Encode(crp.NewFrame(crp.HELLO, hello, nil))
	if err != nil {
		return nil, err
	}
	if _, err := rw.Write(append([]byte(crp.Magic), hb...)); err != nil {
		return nil, err
	}
	n, err := crp.SkipJunk(c.br)
	if err != nil {
		return nil, err
	}
	c.Junk = n
	c.rd = crp.NewReader(c.br, crp.S2C)
	f, err := c.rd.Next()
	if err != nil {
		return nil, err
	}
	c.First = f
	if f.Type == crp.WELCOME {
		if mf, ok := crp.Int(f.Header, "maxFrame"); ok {
			c.rd.MaxFrame = int(mf)
		}
	}
	return c, nil
}

// Send writes one frame.
func (c *Client) Send(f crp.Frame) error {
	b, err := crp.Encode(f)
	if err != nil {
		return err
	}
	c.wmu.Lock()
	defer c.wmu.Unlock()
	_, err = c.rw.Write(b)
	return err
}

// Next reads one frame.
func (c *Client) Next() (crp.Frame, error) { return c.rd.Next() }

// DefaultPingMs is the foreground pingMs of spec §2.4 (no term subscription).
const DefaultPingMs = 15000

// Hello builds a HELLO with the usual fields.
func Hello(dev, link string, att int64, feats []string, resume crp.Obj) crp.Obj {
	fs := make([]any, len(feats))
	for i, f := range feats {
		fs[i] = f
	}
	h := crp.Obj{"proto": []any{int64(1)}, "dev": dev, "link": link, "att": att, "ie": "0000000000000000",
		"app": "crd-probe", "feats": fs, "maxFrame": int64(crp.DefaultMaxFrame), "fg": true, "pingMs": int64(DefaultPingMs)}
	if len(resume) > 0 {
		h["resume"] = resume
	}
	return h
}

// Stream is the client state machine of one durable stream (spec §4.2, gen.py ClientStream).
type Stream struct {
	Ep    string
	Pos   int64
	mode  string
	snapS int64
}

// Step results (gen.py strings).
const (
	Processed = "process"
	Dup       = "drop:dup"
	Gap       = "close-resume:gap"
	EpGap     = "close-resume:ep"
	Snapshot  = "snapshot"
)

// NewStream starts at a known position (ep "" = none).
func NewStream(ep string, pos int64) *Stream { return &Stream{Ep: ep, Pos: pos, mode: "normal"} }

// OnSnap handles SNAP{ep, seq:S, from:F}.
func (s *Stream) OnSnap(ep string, S, F int64) string {
	s.Ep, s.snapS = ep, S
	if F > S {
		s.Pos, s.mode = S, "normal"
	} else {
		s.mode = "backlog"
	}
	return Snapshot
}

// OnItem handles an item (ep, seq).
func (s *Stream) OnItem(ep string, seq int64) string {
	if ep != s.Ep {
		return EpGap
	}
	if s.mode == "backlog" {
		if seq == s.snapS {
			s.Pos, s.mode = seq, "normal"
		}
		return Processed
	}
	if seq <= s.Pos {
		return Dup
	}
	if seq != s.Pos+1 {
		return Gap
	}
	s.Pos = seq
	return Processed
}

// InBacklog reports the backlog mode (the client ACKs S only after it).
func (s *Stream) InBacklog() bool { return s.mode == "backlog" }

// ErrBye is returned when the daemon sent BYE.
type ErrBye struct{ Code, Msg string }

func (e *ErrBye) Error() string { return fmt.Sprintf("BYE %s %s", e.Code, e.Msg) }

// ByeOf converts a BYE frame.
func ByeOf(f crp.Frame) error {
	if f.Type != crp.BYE {
		return nil
	}
	code, _ := crp.Str(f.Header, "code")
	msg, _ := crp.Str(f.Header, "msg")
	return &ErrBye{code, msg}
}
