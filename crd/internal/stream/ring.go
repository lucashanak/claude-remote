// Package stream holds crd's durable streams (spec §4): the resume ring and ack guard, the ev sources with
// seeding and rotation, the sess table over the tmux gate and the tx transcript watches. It registers its
// providers with the daemon from init (register.go).
package stream

import (
	"sort"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
)

// Ring limits (spec §4.4).
const (
	EvRingItems  = 4096
	EvRingBytes  = 2 << 20
	EvRingAge    = 24 * time.Hour
	BacklogPer   = 25 // newest items per source, no age limit (= Stage A's tail -n 25)
	SessRingDiff = 256
	itemOverhead = 64 // bytes counted per item on top of its payload (dodatek A7)
)

// Item is one durable item.
type Item struct {
	Seq  int64
	Src  string
	Line []byte // ev: the line without its newline
	Ops  any    // sess: the diff ops
	At   time.Time
	size int64
}

// Ring is the per-host resume ring of one stream (gen.py Ring). last is the stream counter, never derived from
// what the ring still holds; first is the oldest seq in the ring or last+1 when it is empty.
type Ring struct {
	Ep       string
	Cap      int
	MaxBytes int64         // 0 = no byte limit
	MaxAge   time.Duration // 0 = no age limit
	PerSrc   int           // 0 = no backlog store
	Now      func() time.Time

	items []Item
	bytes int64
	last  int64
	store map[string][]Item
	// clearedAt/clearReason: the counter value Clear left; any position below it predates the clear and resumes
	// with SNAP{clearReason} (sess: a client that was away while the tmux server changed, review R01).
	clearedAt   int64
	clearReason string
}

// NewRing makes an empty ring.
func NewRing(ep string, capacity int, perSrc int) *Ring {
	return &Ring{Ep: ep, Cap: capacity, PerSrc: perSrc, Now: time.Now, store: map[string][]Item{}}
}

// Last is the stream counter.
func (r *Ring) Last() int64 { return r.last }

// First is the oldest seq in the ring, last+1 when empty.
func (r *Ring) First() int64 {
	if len(r.items) == 0 {
		return r.last + 1
	}
	return r.items[0].Seq
}

// Bytes is the accounted size of the ring.
func (r *Ring) Bytes() int64 { return r.bytes }

// Append assigns the next seq.
func (r *Ring) Append(src string, line []byte, ops any) Item {
	r.last++
	it := Item{Seq: r.last, Src: src, Line: line, Ops: ops, At: r.Now()}
	it.size = int64(len(line)) + itemOverhead
	if ops != nil {
		if b, err := crp.Canon(ops); err == nil {
			it.size += int64(len(b))
		}
	}
	r.items = append(r.items, it)
	r.bytes += it.size
	r.Trim()
	if r.PerSrc > 0 {
		l := append(r.store[src], it)
		if len(l) > r.PerSrc {
			l = l[len(l)-r.PerSrc:]
		}
		r.store[src] = l
	}
	return it
}

// Trim applies the count, byte and age limits from the front (the backlog store keeps its items).
func (r *Ring) Trim() {
	cut := 0
	now := r.Now()
	for cut < len(r.items) {
		it := r.items[cut]
		over := len(r.items)-cut > r.Cap || (r.MaxBytes > 0 && r.bytes > r.MaxBytes) ||
			(r.MaxAge > 0 && now.Sub(it.At) > r.MaxAge)
		if !over {
			break
		}
		r.bytes -= it.size
		cut++
	}
	if cut > 0 {
		r.items = append([]Item(nil), r.items[cut:]...)
	}
}

// TrimBefore drops ring items with seq < seq (gen.py trim_before; the daemon's age/byte retention).
func (r *Ring) TrimBefore(seq int64) {
	cut := 0
	for cut < len(r.items) && r.items[cut].Seq < seq {
		r.bytes -= r.items[cut].size
		cut++
	}
	r.items = append([]Item(nil), r.items[cut:]...)
}

// Clear empties the ring and bumps the counter once (sess after a tmux server change): every old position is then
// below clearedAt and resumes with SNAP{reason}, never with an empty replay.
func (r *Ring) Clear(reason string) {
	r.last++
	r.items, r.bytes = nil, 0
	r.clearedAt, r.clearReason = r.last, reason
}

// Backlog is the newest PerSrc items of every source in seq order.
func (r *Ring) Backlog() []Item {
	var out []Item
	for _, l := range r.store {
		out = append(out, l...)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Seq < out[j].Seq })
	return out
}

// SnapFrom is SNAP.from: the first backlog seq, or last+1 for an empty backlog.
func (r *Ring) SnapFrom(back []Item) int64 {
	if len(back) == 0 {
		return r.last + 1
	}
	return back[0].Seq
}

// Info is the WELCOME.streams entry.
func (r *Ring) Info() crp.Obj {
	return crp.Obj{"ep": r.Ep, "first": r.First(), "last": r.last}
}

// Pos is a client position.
type Pos struct {
	Ep  string
	Seq int64
}

// Decision of Resume (spec §4.3).
type Decision struct {
	Kind   string // "replay" | "snapshot"
	Reason string // new, epoch, ahead, overflow
	Items  []Item // replay items or the backlog
}

// Resume decides at subscription (gen.py Ring.resume).
func (r *Ring) Resume(p *Pos) Decision {
	switch {
	case p == nil:
		return Decision{"snapshot", "new", r.Backlog()}
	case p.Ep != r.Ep:
		return Decision{"snapshot", "epoch", r.Backlog()}
	case p.Seq > r.last:
		return Decision{"snapshot", "ahead", r.Backlog()}
	case p.Seq < r.clearedAt:
		return Decision{"snapshot", r.clearReason, r.Backlog()}
	case p.Seq+1 < r.First():
		return Decision{"snapshot", "overflow", r.Backlog()}
	}
	var out []Item
	for _, it := range r.items {
		if it.Seq > p.Seq {
			out = append(out, it)
		}
	}
	return Decision{Kind: "replay", Items: out}
}

// NextFor is what a connection that sent up to sent gets next (spec §4.3 mid-connection overflow).
func (r *Ring) NextFor(sent int64) (kind string, it Item, back []Item) {
	nxt := sent + 1
	if nxt > r.last {
		return "idle", Item{}, nil
	}
	if nxt < r.First() {
		return "snapshot", Item{}, r.Backlog()
	}
	return "item", r.items[nxt-r.First()], nil
}

// AckGuard is the daemon's ACK rule (spec §4.2, gen.py AckGuard): ceiling = max(resume position, highest seq sent,
// S of the last SNAP); above it E_PROTO, another ep ignored, below the last ack ignored.
type AckGuard struct {
	Ep      string
	ceiling int64
	acked   int64
}

// NewAckGuard starts at the resume position.
func NewAckGuard(ep string, resume int64) *AckGuard { return &AckGuard{Ep: ep, ceiling: resume} }

// Sent raises the ceiling.
func (g *AckGuard) Sent(seq int64) {
	if seq > g.ceiling {
		g.ceiling = seq
	}
}

// Ack returns "ok", "ignored:ep", "ignored:old" or "E_PROTO".
func (g *AckGuard) Ack(ep string, seq int64) string {
	if ep != g.Ep {
		return "ignored:ep"
	}
	if seq > g.ceiling {
		return "E_PROTO"
	}
	if seq < g.acked {
		return "ignored:old"
	}
	g.acked = seq
	return "ok"
}

// Acked is the last accepted ack.
func (g *AckGuard) Acked() int64 { return g.acked }
