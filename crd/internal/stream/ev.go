package stream

import (
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/daemon"
)

// evProvider is the ev stream (spec §4.1, §4.4, §4.7): five sources followed by offset, seeded at start.
type evProvider struct {
	p             daemon.Paths
	log           *daemon.Logger
	seq           *seqStream
	followers     []*follower
	offMu         sync.Mutex
	off           bool
	roundMu       sync.Mutex // held by a round; SetOff(true) waits for the round in flight
	lastNotifyRot time.Time
	hb            atomic.Int64
	rotateEvery   time.Duration
	kick          chan struct{}
	dropped       atomic.Int64     // ev_dropped: oversize lines (EvLineMax)
	beforeRename  func(src string) // test seam: between the read of the file and the rename of a rotation
}

func (e *evProvider) isOff() bool { e.offMu.Lock(); defer e.offMu.Unlock(); return e.off }

func EvFrame(ep string, it Item) crp.Frame {
	return crp.NewFrame(crp.EV, crp.Obj{"st": "ev", "ep": ep, "seq": it.Seq, "src": it.Src, "at": it.At.UnixMilli()}, it.Line)
}

func EvSnap(r *Ring, reason string, back []Item) []crp.Frame {
	fs := []crp.Frame{crp.NewFrame(crp.SNAP, crp.Obj{"st": "ev", "ep": r.Ep, "seq": r.Last(), "from": r.SnapFrom(back), "reason": reason}, nil)}
	for _, it := range back {
		fs = append(fs, EvFrame(r.Ep, it))
	}
	return fs
}

func newEv(env *daemon.Env) (daemon.Stream, error) {
	r := NewRing(env.Ep, EvRingItems, BacklogPer)
	r.MaxBytes, r.MaxAge, r.Now = EvRingBytes, EvRingAge, env.Now
	e := &evProvider{p: env.Paths, log: env.Log, seq: newSeqStream("ev", r), rotateEvery: 10 * time.Minute, kick: make(chan struct{}, 1)}
	if env.Paths.Test {
		if ms, err := strconv.Atoi(os.Getenv("CRD_TEST_ROTATE_MS")); err == nil && ms > 0 {
			e.rotateEvery = time.Duration(ms) * time.Millisecond
		}
	}
	e.seq.itemFrame = func(it Item) crp.Frame { return EvFrame(r.Ep, it) }
	e.seq.snapFrames = func(reason string, back []Item) []crp.Frame { return EvSnap(r, reason, back) }
	paths := map[string]string{"events": e.p.Events, "notify": e.p.Notify, "legacy": e.p.Legacy, "forget": e.p.Forget, "jarvis": e.p.Jarvis}
	emit := func(src string, line []byte) { e.seq.appendItem(src, line, nil) }
	drop := func(src string, n int) {
		e.dropped.Add(1)
		e.log.Log("ev-oversize", "src", src, "len", n)
	}
	// Seeding at start: the last 25 lines of each source in order, seq 1..k, then follow from there (spec §4.4).
	refuse := func(src string) { e.log.Log("ev-outside-root", "src", src, "why", errOutside.Error()) }
	odd := func(src string) { e.log.Log("ev-not-regular", "src", src, "why", "ev source not a regular file") }
	for _, src := range EvSources {
		fl := &follower{src: src, path: paths[src], emit: emit, drop: drop, refuse: refuse, odd: odd}
		if env.Paths.Test {
			fl.under = env.Paths.UnderRoot
		}
		for _, l := range fl.seed() {
			fl.deliver(l)
		}
		e.followers = append(e.followers, fl)
	}
	e.hb.Store(time.Now().UnixNano())
	return e, nil
}

func (e *evProvider) Name() string    { return "ev" }
func (e *evProvider) Feats() []string { return []string{"ev"} }
func (e *evProvider) Info() (crp.Obj, bool) {
	return e.seq.info(), true
}
func (e *evProvider) Sub(c daemon.Conn, h crp.Obj) (string, error) {
	return e.seq.subscribe(c, posOf(c, "ev", h)), nil
}
func (e *evProvider) Unsub(c daemon.Conn, _ crp.Obj)     { e.seq.unsubscribe(c) }
func (e *evProvider) Ack(c daemon.Conn, a crp.Obj) error { return e.seq.ack(c, a) }
func (e *evProvider) Gone(c daemon.Conn)                 { e.seq.unsubscribe(c) }
func (e *evProvider) SetOff(off bool) {
	e.offMu.Lock()
	e.off = off
	e.offMu.Unlock()
	// A round that saw crd.off absent just before (the creation of crd.off kicks the loop too) ends before the
	// daemon answers E_OFF: nothing is read after a client was told the daemon is off.
	e.roundMu.Lock()
	e.roundMu.Unlock() // empty on purpose: waits for the round in flight
}
func (e *evProvider) Heartbeat() time.Time { return time.Unix(0, e.hb.Load()) }
func (e *evProvider) Stats(add func(k, v string)) {
	e.seq.mu.Lock()
	defer e.seq.mu.Unlock()
	add("ev_seq", strconv.FormatInt(e.seq.ring.Last(), 10))
	add("ring_bytes", strconv.FormatInt(e.seq.ring.Bytes(), 10))
	add("ev_dropped", strconv.FormatInt(e.dropped.Load(), 10))
}

// round is one pass of the loop: under crd.off the sources are not read at all (review A-F3); when crd.off goes
// away each follower continues from its saved offset, an inode change in between is a rotation (spec §4.7).
func (e *evProvider) round(rotate bool) {
	e.roundMu.Lock()
	defer e.roundMu.Unlock()
	if !e.isOff() {
		for _, fl := range e.followers {
			fl.check()
		}
		if rotate {
			for _, fl := range e.followers {
				e.rotate(fl)
			}
		}
	}
	e.seq.mu.Lock()
	e.seq.ring.Trim()
	e.seq.mu.Unlock()
}

func (e *evProvider) Start(stop <-chan struct{}) {
	in, err := daemon.NewInotify()
	dirs := []string{e.p.Cache, e.p.CR}
	if err == nil {
		defer in.Close()
		go in.Run(func(_, _ string) {
			select {
			case e.kick <- struct{}{}:
			default:
			}
		})
	}
	e.cleanRotTemps()
	e.roundMu.Lock()
	for _, fl := range e.followers {
		e.rotate(fl)
	}
	e.roundMu.Unlock()
	t := time.NewTicker(time.Second)
	defer t.Stop()
	lastRot := time.Now()
	for {
		if in != nil {
			for _, d := range dirs {
				if !in.Watching(d) {
					_ = in.AddDir(d)
				}
			}
		}
		rot := time.Since(lastRot) >= e.rotateEvery
		if rot {
			lastRot = time.Now()
		}
		e.round(rot)
		e.hb.Store(time.Now().UnixNano())
		select {
		case <-stop:
			return
		case <-e.kick:
		case <-t.C:
		}
	}
}
