package stream

import (
	"errors"
	"sync"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/daemon"
)

// seqStream is a durable (ep, seq) stream with per-connection subscribers, flow control (spec §2.6) and the
// resume table of §4.3. The replay set (or backlog) and the registration as a live receiver happen in one
// critical section, so no item falls between them.
type seqStream struct {
	name string
	mu   sync.Mutex
	ring *Ring
	subs map[string]*seqSub
	// itemFrame renders one item; snapFrames renders SNAP (and its backlog EVs) for reason, under mu.
	itemFrame  func(it Item) crp.Frame
	snapFrames func(reason string, back []Item) []crp.Frame
}

type seqSub struct {
	c       daemon.Conn
	sent    int64
	guard   *AckGuard
	unacked []sentItem
	bytes   int64
	paused  bool
}

type sentItem struct {
	seq  int64
	size int64
}

func newSeqStream(name string, ring *Ring) *seqStream {
	return &seqStream{name: name, ring: ring, subs: map[string]*seqSub{}}
}

func (s *seqSub) full() bool {
	return len(s.unacked) >= daemon.MaxUnackedItems || s.bytes >= daemon.MaxUnackedBytes
}

func (s *seqSub) push(f crp.Frame, seq int64) {
	s.c.Send(f)
	sz := int64(len(f.Blob)) + 64
	s.unacked = append(s.unacked, sentItem{seq, sz})
	s.bytes += sz
}

// posOf reads {ep, seq} from a SUB header, else from HELLO.resume.
func posOf(c daemon.Conn, st string, h crp.Obj) *Pos {
	ep, ok1 := crp.Str(h, "ep")
	seq, ok2 := crp.Int(h, "seq")
	if ok1 && ok2 {
		return &Pos{ep, seq}
	}
	if ep, seq, ok := c.Resume(st); ok {
		return &Pos{ep, seq}
	}
	return nil
}

// subscribe registers c and returns WELCOME.resumed's value. A SUB of a stream c already follows changes nothing (no
// second SNAP or backlog, the flow-control window stays): a client re-subscribing in a loop never multiplies what the
// daemon queues (review N-2); a client that lost its place reconnects with resume (spec §4.2).
func (st *seqStream) subscribe(c daemon.Conn, p *Pos) string {
	st.mu.Lock()
	defer st.mu.Unlock()
	// A connection that already ended is never registered: its Gone ran or runs after this (daemon closes Done
	// before Gone), so a registration now would stay forever (review K-F4/A-F12).
	select {
	case <-c.Done():
		return ""
	default:
	}
	if _, again := st.subs[c.ID()]; again {
		return ""
	}
	d := st.ring.Resume(p)
	sub := &seqSub{c: c}
	resume := int64(0)
	if p != nil && p.Ep == st.ring.Ep {
		resume = p.Seq
	}
	sub.guard = NewAckGuard(st.ring.Ep, resume)
	st.subs[c.ID()] = sub
	if d.Kind == "replay" {
		sub.sent = p.Seq
		for _, it := range d.Items {
			if sub.full() {
				sub.paused = true
				break
			}
			sub.push(st.itemFrame(it), it.Seq)
			sub.sent = it.Seq
			sub.guard.Sent(it.Seq)
		}
		return "replay"
	}
	st.snapshot(sub, d.Reason, d.Items)
	if p == nil {
		return ""
	}
	return "snapshot"
}

// snapshot sends SNAP + backlog; the position becomes S = last.
func (st *seqStream) snapshot(sub *seqSub, reason string, back []Item) {
	fs := st.snapFrames(reason, back)
	sub.c.Send(fs[0])
	for i, f := range fs[1:] {
		sub.push(f, back[i].Seq)
	}
	sub.sent = st.ring.Last()
	sub.guard.Sent(sub.sent)
	sub.paused = false
}

// appendItem adds an item and hands it to every live subscriber that is caught up and has room.
func (st *seqStream) appendItem(src string, line []byte, ops any) Item {
	st.mu.Lock()
	defer st.mu.Unlock()
	return st.appendLocked(src, line, ops)
}

func (st *seqStream) appendLocked(src string, line []byte, ops any) Item {
	it := st.ring.Append(src, line, ops)
	for _, sub := range st.subs {
		if sub.paused || sub.sent != it.Seq-1 {
			sub.paused = true
			continue
		}
		if sub.full() {
			sub.paused = true
			continue
		}
		sub.push(st.itemFrame(it), it.Seq)
		sub.sent = it.Seq
		sub.guard.Sent(it.Seq)
	}
	return it
}

var errAckAbove = errors.New(daemon.MsgAckAbove)

// ack applies the ack rules and resumes a paused subscriber (mid-connection overflow ⇒ SNAP, vector 29).
func (st *seqStream) ack(c daemon.Conn, a crp.Obj) error {
	ep, _ := crp.Str(a, "ep")
	seq, ok := crp.Int(a, "seq")
	if !ok {
		return errors.New("ACK without seq")
	}
	st.mu.Lock()
	defer st.mu.Unlock()
	sub := st.subs[c.ID()]
	if sub == nil {
		return nil // ACK of a stream it does not follow (e.g. right after an UNSUB): harmless
	}
	switch sub.guard.Ack(ep, seq) {
	case "E_PROTO":
		return errAckAbove
	case "ok":
		i := 0
		for i < len(sub.unacked) && sub.unacked[i].seq <= seq {
			sub.bytes -= sub.unacked[i].size
			i++
		}
		sub.unacked = sub.unacked[i:]
	}
	st.drain(sub)
	return nil
}

func (st *seqStream) drain(sub *seqSub) {
	for sub.paused && !sub.full() {
		kind, it, back := st.ring.NextFor(sub.sent)
		switch kind {
		case "idle":
			sub.paused = false
		case "snapshot":
			st.snapshot(sub, "overflow", back)
		default:
			sub.push(st.itemFrame(it), it.Seq)
			sub.sent = it.Seq
			sub.guard.Sent(it.Seq)
		}
	}
}

func (st *seqStream) unsubscribe(c daemon.Conn) {
	st.mu.Lock()
	delete(st.subs, c.ID())
	st.mu.Unlock()
}

func (st *seqStream) count() int {
	st.mu.Lock()
	defer st.mu.Unlock()
	return len(st.subs)
}

func (st *seqStream) info() crp.Obj {
	st.mu.Lock()
	defer st.mu.Unlock()
	return st.ring.Info()
}
