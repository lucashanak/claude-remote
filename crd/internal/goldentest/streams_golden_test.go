package goldentest

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/client"
	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/daemon"
	"github.com/lucashanak/claude-remote/crd/internal/stream"
)

// Celek 3: Ring (resume, SNAP, seeding, mid-connection overflow), AckGuard, ClientStream steps, TxWatch + pieces.
// Inputs come from modelInput (dodatek §2 (a)); frames are rebuilt by crd's own builders (§2 (b)).

type ringIn struct {
	Ep       string `json:"ep"`
	Capacity int    `json:"capacity"`
	PerSrc   int    `json:"perSrc"`
	Ops      []struct {
		Op       string              `json:"op"`
		Src      string              `json:"src"`
		Line     string              `json:"line"`
		From     int64               `json:"from"`
		To       int64               `json:"to"`
		SrcCycle []string            `json:"srcCycle"`
		Seq      int64               `json:"seq"`
		Files    map[string][]string `json:"files"`
		Client   *struct {
			Ep  string `json:"ep"`
			Seq int64  `json:"seq"`
		} `json:"client"`
		Sent int64 `json:"sent"`
	} `json:"ops"`
}

type ringResult struct {
	op   string
	dec  stream.Decision
	snap crp.Frame // the SNAP this decision would send, built at the time of the query
	info crp.Obj
	kind string // nextFor
	back []stream.Item
}

type ringRun struct {
	r          *stream.Ring
	results    []ringResult
	all        map[int64]stream.Item
	lastAppend int64
}

// nowOf is the `at` of the vector's EV frames (the daemon's read time is environment, not model).
func nowOf(t *testing.T, v *Vector) int64 {
	for _, f := range framesOf(v, "", "s2c", "EV") {
		if at, ok := crp.Int(header(t, f), "at"); ok {
			return at
		}
	}
	return 0
}

func runRing(t *testing.T, v *Vector) *ringRun {
	t.Helper()
	var in ringIn
	if err := json.Unmarshal(v.ModelInput["ring"], &in); err != nil {
		t.Fatalf("%s: modelInput.ring: %v", v.ID, err)
	}
	now := time.UnixMilli(nowOf(t, v))
	r := stream.NewRing(in.Ep, in.Capacity, in.PerSrc)
	r.Now = func() time.Time { return now }
	run := &ringRun{r: r, all: map[int64]stream.Item{}}
	add := func(src, line string) {
		it := r.Append(src, []byte(line), nil)
		run.all[it.Seq] = it
		run.lastAppend = it.Seq
	}
	for _, op := range in.Ops {
		switch op.Op {
		case "append":
			add(op.Src, op.Line)
		case "appendLines":
			for s := op.From; s <= op.To; s++ {
				add(op.SrcCycle[int(s)%len(op.SrcCycle)], fmt.Sprintf("line-%d", s))
			}
		case "trimBefore":
			r.TrimBefore(op.Seq)
		case "seed":
			// crd's own seeding order (EvSources; jarvis has no file in the vectors)
			for _, src := range stream.EvSources {
				ls := op.Files[src]
				if len(ls) > stream.SeedLines {
					ls = ls[len(ls)-stream.SeedLines:]
				}
				for _, l := range ls {
					add(src, l)
				}
			}
		case "resume":
			var p *stream.Pos
			if op.Client != nil {
				p = &stream.Pos{Ep: op.Client.Ep, Seq: op.Client.Seq}
			}
			d := r.Resume(p)
			res := ringResult{op: "resume", dec: d, info: r.Info()}
			if d.Kind == "snapshot" {
				res.snap = stream.EvSnap(r, d.Reason, d.Items)[0]
			}
			run.results = append(run.results, res)
		case "nextFor":
			kind, _, back := r.NextFor(op.Sent)
			run.results = append(run.results, ringResult{op: "nextFor", kind: kind, back: back, info: r.Info()})
		default:
			t.Fatalf("%s: unknown ring op %q", v.ID, op.Op)
		}
	}
	return run
}

func seqList(items []stream.Item) []any {
	out := []any{}
	for _, it := range items {
		out = append(out, it.Seq)
	}
	return out
}

func evFramesOn(t *testing.T, v *Vector, conn string, maxSeq int64) int64 {
	var n int64
	for _, f := range framesOf(v, conn, "s2c", "EV") {
		if s, _ := crp.Int(header(t, f), "seq"); maxSeq == 0 || s <= maxSeq {
			n++
		}
	}
	return n
}

type txIn struct {
	Off   int64 `json:"off"`
	Polls [][]struct {
		Text  string `json:"text"`
		Times int    `json:"times"`
	} `json:"polls"`
}

// runTx replays the polls on crd's TxWatch; nil entries are polls that sent nothing.
func runTx(t *testing.T, v *Vector) (*stream.TxWatch, []*stream.TxDelta) {
	t.Helper()
	var in txIn
	if err := json.Unmarshal(v.ModelInput["tx"], &in); err != nil {
		t.Fatalf("%s: modelInput.tx: %v", v.ID, err)
	}
	w := &stream.TxWatch{Off: in.Off}
	var out []*stream.TxDelta
	for _, segs := range in.Polls {
		var b strings.Builder
		for _, s := range segs {
			b.WriteString(strings.Repeat(s.Text, s.Times))
		}
		content := []byte(b.String())
		d, err := w.Poll(int64(len(content)), func(off, n int64) ([]byte, error) { return content[off : off+n], nil })
		if err != nil {
			t.Fatal(err)
		}
		out = append(out, d)
	}
	return w, out
}

func deltaModel(d *stream.TxDelta) any {
	if d == nil {
		return nil
	}
	return crp.Obj{"f": d.F, "o": d.O, "reset": d.Reset}
}

func init() {
	celekPresent["celek3"] = true
	resumeExpect := func(t *testing.T, v *Vector, keys ...string) {
		run := runRing(t, v)
		res := run.results[0]
		got := crp.Obj{"resume": res.dec.Kind}
		if res.dec.Kind == "snapshot" {
			got["reason"] = res.dec.Reason
		}
		for _, k := range keys {
			switch k {
			case "backlogSeqs", "replaySeqs":
				got[k] = seqList(res.dec.Items)
			case "ringFirst":
				got[k] = run.r.First()
			case "backlogCount":
				got[k] = int64(len(res.dec.Items))
			case "backlogFirstSeq":
				got[k] = res.dec.Items[0].Seq
			case "backlogLastSeq":
				got[k] = res.dec.Items[len(res.dec.Items)-1].Seq
			case "framesShown":
				got[k] = evFramesOn(t, v, "", 0)
			}
		}
		for k := range got {
			sameJSON(t, v.ID+"/"+k, got[k], expectJSON(t, v, k))
		}
	}
	reg := func(id string, keys []string, extra ...string) {
		for _, k := range keys {
			register("celek3", id, k, func(t *testing.T, v *Vector) { resumeExpect(t, v, extra...) })
		}
	}
	reg("02-handshake-fresh", []string{"resume", "reason", "backlogSeqs"}, "backlogSeqs")
	reg("06-resume-replay", []string{"resume", "replaySeqs"}, "replaySeqs")
	reg("07-resume-overflow", []string{"resume", "reason", "ringFirst", "backlogCount", "backlogFirstSeq", "backlogLastSeq", "framesShown"},
		"ringFirst", "backlogCount", "backlogFirstSeq", "backlogLastSeq", "framesShown")
	reg("08-resume-epoch-change", []string{"resume", "reason", "backlogSeqs"}, "backlogSeqs")

	// 27: A replays nothing, B gets SNAP(overflow) with the age-free backlog, C is ahead, the next live seq is last+1.
	e27 := func(t *testing.T, v *Vector) crp.Obj {
		run := runRing(t, v)
		a, b, c := run.results[0].dec, run.results[1].dec, run.results[2].dec
		S, _ := crp.Int(header(t, framesOf(v, "B", "s2c", "SNAP")[0]), "seq")
		return crp.Obj{
			"A":                    crp.Obj{"resume": a.Kind, "replaySeqs": seqList(a.Items)},
			"B":                    crp.Obj{"resume": b.Kind, "reason": b.Reason, "backlogCount": int64(len(b.Items)), "backlogLastSeq": b.Items[len(b.Items)-1].Seq, "framesShown": evFramesOn(t, v, "B", S)},
			"C-ahead (model only)": crp.Obj{"resume": c.Kind, "reason": c.Reason, "backlogCount": int64(len(c.Items))},
			"nextLiveSeq":          run.lastAppend,
		}
	}
	for _, k := range []string{"A", "B", "C-ahead (model only)", "nextLiveSeq"} {
		k := k
		register("celek3", "27-ev-empty-ring", k, func(t *testing.T, v *Vector) { sameJSON(t, k, e27(t, v)[k], expectJSON(t, v, k)) })
	}
	// 28: seeding order and counter.
	e28 := func(t *testing.T, v *Vector) crp.Obj {
		run := runRing(t, v)
		seeded := []any{}
		for _, it := range run.r.Backlog() {
			seeded = append(seeded, crp.Obj{"seq": it.Seq, "src": it.Src})
		}
		return crp.Obj{"seeded": seeded, "last": run.r.Last(), "framesShown": evFramesOn(t, v, "", 0)}
	}
	for _, k := range []string{"seeded", "last", "framesShown"} {
		k := k
		register("celek3", "28-ev-restart-seed", k, func(t *testing.T, v *Vector) { sameJSON(t, k, e28(t, v)[k], expectJSON(t, v, k)) })
	}
	// 29: mid-connection overflow, and the probe's client stream on that SNAP + backlog + a later gap.
	e29 := func(t *testing.T, v *Vector) crp.Obj {
		run := runRing(t, v)
		nx := run.results[0]
		ep := run.r.Ep
		cs := client.NewStream(ep, 50)
		steps := []any{cs.OnSnap(ep, run.r.Last(), nx.back[0].Seq)}
		for _, it := range nx.back {
			steps = append(steps, cs.OnItem(ep, it.Seq))
		}
		steps = append(steps, cs.OnItem(ep, run.r.Last()+2))
		return crp.Obj{"next": []any{nx.kind, "overflow"}, "backlogCount": int64(len(nx.back)),
			"clientSteps": append(steps[:1:1], steps[len(steps)-3:]...), "clientPosition": cs.Pos}
	}
	for _, k := range []string{"next", "backlogCount", "clientSteps", "clientPosition"} {
		k := k
		register("celek3", "29-ev-midstream-overflow", k, func(t *testing.T, v *Vector) { sameJSON(t, k, e29(t, v)[k], expectJSON(t, v, k)) })
	}
	// 30: the ack guard on the recorded ACK frames (labels are gen.py's names of the four cases, in order).
	register("celek3", "30-ack-rules", "daemon", func(t *testing.T, v *Vector) {
		h := firstHello(t, v, "A")
		rs, _ := crp.Sub(h, "resume")
		pos, _ := crp.Sub(rs, "ev")
		ep, _ := crp.Str(pos, "ep")
		seq, _ := crp.Int(pos, "seq")
		g := stream.NewAckGuard(ep, seq)
		names := []string{"after-resume", "old-ep", "below", "above-sent"}
		got := crp.Obj{}
		for i, f := range framesOf(v, "A", "c2s", "ACK") {
			a, _ := crp.Sub(header(t, f), "acks")
			p, _ := crp.Sub(a, "ev")
			e, _ := crp.Str(p, "ep")
			s, _ := crp.Int(p, "seq")
			got[names[i]] = g.Ack(e, s)
		}
		sameJSON(t, "daemon", got, expectJSON(t, v, "daemon"))
	})
	register("celek3", "05-ev-delta-ack", "daemonAck", func(t *testing.T, v *Vector) {
		ev := header(t, framesOf(v, "A", "s2c", "EV")[0])
		ep, _ := crp.Str(ev, "ep")
		seq, _ := crp.Int(ev, "seq")
		g := stream.NewAckGuard(ep, 0)
		g.Sent(seq)
		a, _ := crp.Sub(header(t, framesOf(v, "A", "c2s", "ACK")[0]), "acks")
		p, _ := crp.Sub(a, "ev")
		e, _ := crp.Str(p, "ep")
		s, _ := crp.Int(p, "seq")
		sameJSON(t, "daemonAck", g.Ack(e, s), expectJSON(t, v, "daemonAck"))
	})
	// tx
	register("celek3", "10-tx-sub-delta", "model", func(t *testing.T, v *Vector) {
		_, ds := runTx(t, v)
		sameJSON(t, "model", deltaModel(ds[0]), expectJSON(t, v, "model"))
	})
	register("celek3", "10-tx-sub-delta", "watchOff", func(t *testing.T, v *Vector) {
		w, _ := runTx(t, v)
		sameJSON(t, "watchOff", w.Off, expectJSON(t, v, "watchOff"))
	})
	register("celek3", "11-tx-truncated", "model", func(t *testing.T, v *Vector) {
		_, ds := runTx(t, v)
		sameJSON(t, "model", deltaModel(ds[0]), expectJSON(t, v, "model"))
	})
	register("celek3", "25-tx-line-pieces", "pieces", func(t *testing.T, v *Vector) {
		_, ds := runTx(t, v)
		got := []any{}
		for _, p := range stream.Pieces(ds[0]) {
			got = append(got, crp.Obj{"f": p.F, "o": p.O, "more": p.More})
		}
		sameJSON(t, "pieces", got, expectJSON(t, v, "pieces"))
	})
	register("celek3", "25-tx-line-pieces", "delta", func(t *testing.T, v *Vector) {
		_, ds := runTx(t, v)
		sameJSON(t, "delta", crp.Obj{"f": ds[0].F, "o": ds[0].O, "bytes": int64(len(ds[0].Chunk))}, expectJSON(t, v, "delta"))
	})
	register("celek3", "26-tx-reset-pending", "poll1", func(t *testing.T, v *Vector) {
		_, ds := runTx(t, v)
		sameJSON(t, "poll1", deltaModel(ds[0]), expectJSON(t, v, "poll1"))
	})
	register("celek3", "26-tx-reset-pending", "poll2", func(t *testing.T, v *Vector) {
		_, ds := runTx(t, v)
		sameJSON(t, "poll2", deltaModel(ds[1]), expectJSON(t, v, "poll2"))
	})

	// Frames rebuilt by crd: every ev EV from the Go ring, SNAP from the Go decision, WELCOME streams from the
	// Go ring, TX from the Go watch and piece cutter, BYE of the ack guard.
	ringFrames := func(t *testing.T, v *Vector) {
		run := runRing(t, v)
		for _, f := range framesOf(v, "", "s2c", "EV") {
			h := header(t, f)
			if h["st"] != "ev" {
				continue
			}
			seq, _ := crp.Int(h, "seq")
			it, ok := run.all[seq]
			if !ok {
				t.Fatalf("EV seq %d not produced by the Go ring", seq)
			}
			sameFrame(t, fmt.Sprintf("EV %d", seq), stream.EvFrame(run.r.Ep, it), f)
		}
		// a recorded SNAP is matched to the first unused snapshot decision with its reason; without one (28, 29)
		// it is the SNAP of the final ring state
		var fromQueries []ringResult
		for _, r := range run.results {
			if r.op == "resume" && r.dec.Kind == "snapshot" {
				fromQueries = append(fromQueries, r)
			}
		}
		for _, s := range framesOf(v, "", "s2c", "SNAP") {
			reason, _ := crp.Str(header(t, s), "reason")
			var want *crp.Frame
			for i := range fromQueries {
				if fromQueries[i].dec.Reason == reason {
					f := fromQueries[i].snap
					want = &f
					fromQueries = append(fromQueries[:i], fromQueries[i+1:]...)
					break
				}
			}
			if want == nil {
				f := stream.EvSnap(run.r, reason, run.r.Backlog())[0]
				want = &f
			}
			sameFrame(t, "SNAP", *want, s)
		}
		ws := framesOf(v, "", "s2c", "WELCOME")
		for i, w := range ws {
			info := run.r.Info()
			if v.ID == "27-ev-empty-ring" {
				info = run.results[i].info
			}
			n := negotiateRef(t, v, firstHello(t, v, w.Conn))
			sameFrame(t, "WELCOME "+w.Conn, welcomeFrom(t, n, header(t, w), crp.Obj{"ev": info}), w)
		}
	}
	for _, id := range []string{"02-handshake-fresh", "06-resume-replay", "07-resume-overflow", "08-resume-epoch-change",
		"27-ev-empty-ring", "28-ev-restart-seed", "29-ev-midstream-overflow", "30-ack-rules"} {
		frameCheckers[id] = append(frameCheckers[id], ringFrames)
	}
	frameCheckers["30-ack-rules"] = append(frameCheckers["30-ack-rules"], func(t *testing.T, v *Vector) {
		sameFrame(t, "BYE E_PROTO", daemon.Bye(crp.EProto, daemon.MsgAckAbove), framesOf(v, "A", "s2c", "BYE")[0])
	})
	txFrames := func(t *testing.T, v *Vector) {
		_, ds := runTx(t, v)
		sub := header(t, framesOf(v, "A", "c2s", "SUB")[0])
		wid, _ := crp.Str(sub, "wid")
		uuid, _ := crp.Str(sub, "uuid")
		enc, _ := crp.Str(sub, "enc")
		var built []crp.Frame
		for _, d := range ds {
			if d == nil {
				continue
			}
			for _, p := range stream.Pieces(d) {
				built = append(built, stream.TxFrame(wid, uuid, p, enc == "gzip"))
			}
		}
		rec := framesOf(v, "A", "s2c", "TX")
		if len(built) != len(rec) {
			t.Fatalf("%d TX built, %d recorded", len(built), len(rec))
		}
		for i := range rec {
			sameFrame(t, fmt.Sprintf("TX %d", i), built[i], rec[i])
		}
	}
	for _, id := range []string{"10-tx-sub-delta", "11-tx-truncated", "25-tx-line-pieces", "26-tx-reset-pending"} {
		frameCheckers[id] = append(frameCheckers[id], txFrames)
	}
}

// All three celky are in this tree: nothing may stay pending (removing a celek is only for the isolated check).
func TestGoldenAllCelkyPresent(t *testing.T) {
	for _, c := range []string{"celek1", "celek2", "celek3"} {
		if !celekPresent[c] {
			t.Fatalf("%s missing", c)
		}
	}
}
