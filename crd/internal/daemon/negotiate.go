package daemon

import (
	"fmt"
	"regexp"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
)

// ProtoMin/ProtoMax is the CRP range this daemon speaks (spec §2.3).
const (
	ProtoMin = 1
	ProtoMax = 1
)

// ProductFeats is what B1a may offer, in daemon order (zadání celek 2: ev sess tx gzip; term, in, rpc.exec not).
// The daemon offers the subset whose stream is registered (stream.go).
var ProductFeats = []string{"ev", "sess", "tx", "gzip"}

// Negotiation is the result of negotiate (gen.py negotiate).
type Negotiation struct {
	OK       bool
	Proto    int64
	Feats    []string
	MaxFrame int64
	Code     string // BYE code when !OK
	Min, Max int64  // E_VERSION
	Msg      string
}

// Byte-exact BYE messages (the golden vectors carry them).
const (
	MsgVersion    = "no common protocol version"
	MsgSuperseded = "newer connection of this device and link"
	MsgStale      = "older attempt than the live connection"
	MsgAckAbove   = "ACK above sent"
	MsgOff        = "crd.off present"
	MsgConnLimit  = "connection limit reached"
)

// Negotiate is a pure function of the HELLO and the server's parameters (dodatek B2): golden calls it with the
// reference set of gen.py, the daemon with its own.
func Negotiate(hello crp.Obj, protoMin, protoMax int64, serverFeats []string, serverMaxFrame int64) Negotiation {
	var common []int64
	if ps, ok := hello["proto"].([]any); ok {
		for _, p := range ps {
			if n, ok := p.(int64); ok && n >= protoMin && n <= protoMax {
				common = append(common, n)
			}
		}
	}
	if len(common) == 0 {
		return Negotiation{Code: crp.EVersion, Min: protoMin, Max: protoMax, Msg: MsgVersion}
	}
	mf, _ := crp.Int(hello, "maxFrame")
	if mf < crp.MinMaxFrame {
		return Negotiation{Code: crp.EProto, Msg: fmt.Sprintf("maxFrame below %d", crp.MinMaxFrame)}
	}
	want := map[string]bool{}
	if fs, ok := hello["feats"].([]any); ok {
		for _, f := range fs {
			if s, ok := f.(string); ok {
				want[s] = true
			}
		}
	}
	feats := []string{}
	for _, f := range serverFeats {
		if want[f] {
			feats = append(feats, f)
		}
	}
	best := common[0]
	for _, p := range common {
		if p > best {
			best = p
		}
	}
	m := mf
	if serverMaxFrame < m {
		m = serverMaxFrame
	}
	if crp.HardMaxFrame < m {
		m = crp.HardMaxFrame
	}
	return Negotiation{OK: true, Proto: best, Feats: feats, MaxFrame: m}
}

// ByeFrame is the refusal for a failed negotiation.
func (n Negotiation) ByeFrame() crp.Frame {
	h := crp.Obj{"code": n.Code, "msg": n.Msg}
	if n.Code == crp.EVersion {
		h["min"], h["max"] = n.Min, n.Max
	}
	return crp.NewFrame(crp.BYE, h, nil)
}

// MaxByeMsg bounds BYE.msg: messages may quote client input (a stream name, a decoder error), and a BYE must always
// encode (no panic on the send path, dodatek 2 P1.1).
const MaxByeMsg = 256

// Bye builds BYE{code, msg}; an empty msg is left out, a long one is cut at a UTF-8 boundary.
func Bye(code, msg string) crp.Frame {
	h := crp.Obj{"code": code}
	if msg != "" {
		h["msg"] = TruncUTF8(msg, MaxByeMsg)
	}
	return crp.NewFrame(crp.BYE, h, nil)
}

// ShutdownBye is BYE{E_SHUTDOWN, retryMs} of a graceful stop (spec §7.2, vector 44).
func ShutdownBye() crp.Frame {
	return crp.NewFrame(crp.BYE, crp.Obj{"code": crp.EShutdown, "retryMs": int64(3000)}, nil)
}

// BusyBye refuses a connection over MaxConnsHost (review R-3). Not E_PROTO: that is a `proto` strike (spec §1.4) and
// three of them switch CRP off for the server until the app restarts, so a short flood would end CRP on the device.
// CRP/1 has no connection-level E_BUSY (§3.5); E_SHUTDOWN{retryMs} is the connection code the client takes as
// transient (§1.4 cause `shutdown`: retry after retryMs, no backoff, no strike). Erratum: E_BUSY for B2.
func BusyBye() crp.Frame {
	return crp.NewFrame(crp.BYE, crp.Obj{"code": crp.EShutdown, "retryMs": int64(BusyRetryMs), "msg": MsgConnLimit}, nil)
}

// BusyRetryMs is BusyBye's retryMs: a slot frees when a connection ends, so a few seconds.
const BusyRetryMs = 5000

// Pong answers PING{t} at once (spec §2.4).
func Pong(t, now int64) crp.Frame {
	return crp.NewFrame(crp.PONG, crp.Obj{"t": t, "now": now}, nil)
}

// Host is WELCOME.host (spec §2.3, §7.4).
type Host struct {
	ID       string
	Boot     string
	TmuxPid  int64 // 0 = null
	HasTmuxP bool
}

// WelcomeHeader builds WELCOME (spec §2.3). streams/resumed may be empty; inHi is never sent by B1a.
func WelcomeHeader(n Negotiation, conn, srv string, now int64, host Host, streams crp.Obj, inGen string, resumed crp.Obj) crp.Obj {
	feats := make([]any, len(n.Feats))
	for i, f := range n.Feats {
		feats[i] = f
	}
	hh := crp.Obj{"id": host.ID, "boot": host.Boot, "tmuxPid": nil}
	if host.HasTmuxP {
		hh["tmuxPid"] = host.TmuxPid
	}
	h := crp.Obj{"proto": n.Proto, "conn": conn, "srv": srv, "feats": feats, "maxFrame": n.MaxFrame, "now": now,
		"host": hh, "streams": streams, "inGen": inGen}
	if len(resumed) > 0 {
		h["resumed"] = resumed
	}
	return h
}

// TruncUTF8 cuts s to at most n bytes without splitting a UTF-8 sequence and replaces invalid bytes by U+FFFD
// (the canonical writer needs valid UTF-8, the receiver refuses anything else).
func TruncUTF8(s string, n int) string {
	if !utf8.ValidString(s) {
		s = strings.ToValidUTF8(s, "\uFFFD")
	}
	if len(s) <= n {
		return s
	}
	i := n
	for i > 0 && !utf8.RuneStart(s[i]) {
		i--
	}
	return s[:i]
}

// MaxPingMs caps the client's pingMs (HELLO, STATE): the idle limit max(45 s, 3 × pingMs) is then at most 15 min.
const MaxPingMs = 300000

// ClampPingMs applies MaxPingMs.
func ClampPingMs(pm int64) int64 {
	if pm > MaxPingMs {
		return MaxPingMs
	}
	return pm
}

// IdleLimit is how long the daemon waits for any frame before it closes: max(45 s, 3 × pingMs) (spec §2.4).
func IdleLimit(pingMs int64, floor time.Duration) time.Duration {
	d := 3 * time.Duration(pingMs) * time.Millisecond
	if d < floor {
		return floor
	}
	return d
}

// IdleFloor is the 45 s of spec §2.4.
const IdleFloor = 45 * time.Second

var (
	devRe  = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)
	linkRe = regexp.MustCompile(`^[0-9a-f]{16}$`)
)

// ValidKey checks dev and link of a HELLO (spec §2.3).
func ValidKey(dev, link string) bool { return devRe.MatchString(dev) && linkRe.MatchString(link) }

// ConnTable is the daemon side of spec §2.5: one connection per (dev, link); a higher att supersedes, an equal or
// lower one is STALE (gen.py ConnTable).
type ConnTable struct {
	mu    sync.Mutex
	byKey map[[2]string]tableEntry
}

type tableEntry struct {
	conn string
	att  int64
}

// HelloResult is the table's decision.
type HelloResult struct {
	Accept     bool
	Superseded string // conn id that lost, if any
	Current    string // live conn when rejected
}

// NewConnTable makes an empty table.
func NewConnTable() *ConnTable { return &ConnTable{byKey: map[[2]string]tableEntry{}} }

// Hello registers conn for (dev, link) with att.
func (t *ConnTable) Hello(conn, dev, link string, att int64) HelloResult {
	t.mu.Lock()
	defer t.mu.Unlock()
	k := [2]string{dev, link}
	cur, ok := t.byKey[k]
	if !ok {
		t.byKey[k] = tableEntry{conn, att}
		return HelloResult{Accept: true}
	}
	if att > cur.att {
		t.byKey[k] = tableEntry{conn, att}
		return HelloResult{Accept: true, Superseded: cur.conn}
	}
	return HelloResult{Current: cur.conn}
}

// Gone removes conn if it still holds its key (a superseded connection does not).
func (t *ConnTable) Gone(conn, dev, link string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	k := [2]string{dev, link}
	if t.byKey[k].conn == conn {
		delete(t.byKey, k)
	}
}

// Devs is the number of distinct devices with a live connection.
func (t *ConnTable) Devs() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	d := map[string]bool{}
	for k := range t.byKey {
		d[k[0]] = true
	}
	return len(d)
}
