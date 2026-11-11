package goldentest

import (
	"encoding/json"
	"testing"

	"github.com/lucashanak/claude-remote/crd/internal/client"
	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/daemon"
)

// Celek 2: negotiation, connection table, liveness, the probe's client stream; frames the daemon builds.

type negRef struct {
	ServerProto    []int64  `json:"serverProto"`
	ServerFeats    []string `json:"serverFeats"`
	ServerMaxFrame int64    `json:"serverMaxFrame"`
}

func refOf(t *testing.T, v *Vector) negRef {
	t.Helper()
	var r negRef
	if err := json.Unmarshal(v.ModelInput["negotiate"], &r); err != nil || len(r.ServerProto) != 2 {
		t.Fatalf("%s: modelInput.negotiate: %v", v.ID, err)
	}
	return r
}

func negotiateRef(t *testing.T, v *Vector, hello crp.Obj) daemon.Negotiation {
	r := refOf(t, v)
	return daemon.Negotiate(hello, r.ServerProto[0], r.ServerProto[1], r.ServerFeats, r.ServerMaxFrame)
}

// negJSON renders a Negotiation as gen.py's negotiate() result.
func negJSON(n daemon.Negotiation) crp.Obj {
	if !n.OK {
		o := crp.Obj{"result": "BYE", "code": n.Code}
		if n.Code == crp.EVersion {
			o["min"], o["max"] = n.Min, n.Max
		} else {
			o["msg"] = n.Msg
		}
		return o
	}
	fs := make([]any, len(n.Feats))
	for i, f := range n.Feats {
		fs[i] = f
	}
	return crp.Obj{"result": "WELCOME", "proto": n.Proto, "feats": fs, "maxFrame": n.MaxFrame}
}

func firstHello(t *testing.T, v *Vector, conn string) crp.Obj {
	t.Helper()
	hs := framesOf(v, conn, "c2s", "HELLO")
	if len(hs) == 0 {
		t.Fatalf("%s: no HELLO on %q", v.ID, conn)
	}
	return header(t, hs[0])
}

// welcomeFrom rebuilds a recorded WELCOME with crd's builder: negotiated fields from crd, environment fields
// (conn, srv, now, host, streams, inGen, resumed) taken from the record.
func welcomeFrom(t *testing.T, n daemon.Negotiation, rec crp.Obj, streams crp.Obj) crp.Frame {
	t.Helper()
	host, _ := crp.Sub(rec, "host")
	h := daemon.Host{}
	h.ID, _ = crp.Str(host, "id")
	h.Boot, _ = crp.Str(host, "boot")
	h.TmuxPid, h.HasTmuxP = crp.Int(host, "tmuxPid")
	conn, _ := crp.Str(rec, "conn")
	srv, _ := crp.Str(rec, "srv")
	now, _ := crp.Int(rec, "now")
	inGen, _ := crp.Str(rec, "inGen")
	if streams == nil {
		streams, _ = crp.Sub(rec, "streams")
	}
	resumed, _ := crp.Sub(rec, "resumed")
	return crp.NewFrame(crp.WELCOME, daemon.WelcomeHeader(n, conn, srv, now, h, streams, inGen, resumed), nil)
}

func init() {
	celekPresent["celek2"] = true
	for _, id := range []string{"02-handshake-fresh", "03-version-mismatch", "04-version-downlevel", "25-tx-line-pieces", "45-hello-maxframe-too-small"} {
		register("celek2", id, "negotiation", func(t *testing.T, v *Vector) {
			n := negotiateRef(t, v, firstHello(t, v, "A"))
			sameJSON(t, "negotiation", negJSON(n), expectJSON(t, v, "negotiation"))
		})
	}
	for _, id := range []string{"24-ping-pong-superseded", "31-hello-two-links", "32-hello-stale-att"} {
		register("celek2", id, "table", func(t *testing.T, v *Vector) {
			tb := daemon.NewConnTable()
			var got []any
			for _, f := range framesOf(v, "", "c2s", "HELLO") {
				h := header(t, f)
				dev, _ := crp.Str(h, "dev")
				link, _ := crp.Str(h, "link")
				att, _ := crp.Int(h, "att")
				r := tb.Hello(f.Conn, dev, link, att)
				switch {
				case r.Accept && r.Superseded != "":
					got = append(got, crp.Obj{"accept": f.Conn, "superseded": r.Superseded})
				case r.Accept:
					got = append(got, crp.Obj{"accept": f.Conn})
				default:
					got = append(got, crp.Obj{"reject": f.Conn, "code": crp.Stale, "current": r.Current})
				}
			}
			sameJSON(t, "table", got, expectJSON(t, v, "table"))
		})
	}
	register("celek2", "43-state-fg", "daemonCloseAfterMs", func(t *testing.T, v *Vector) {
		pm, _ := crp.Int(header(t, framesOf(v, "A", "c2s", "STATE")[0]), "pingMs")
		sameJSON(t, "daemonCloseAfterMs", daemon.IdleLimit(pm, daemon.IdleFloor).Milliseconds(), expectJSON(t, v, "daemonCloseAfterMs"))
	})
	// 05: the probe's client stream, at position 41, processes EV 42 and ends at 42.
	register("celek2", "05-ev-delta-ack", "clientPosition", func(t *testing.T, v *Vector) {
		h := header(t, framesOf(v, "A", "s2c", "EV")[0])
		ep, _ := crp.Str(h, "ep")
		seq, _ := crp.Int(h, "seq")
		s := client.NewStream(ep, seq-1)
		if s.OnItem(ep, seq) != client.Processed {
			t.Fatal("not processed")
		}
		sameJSON(t, "clientPosition", crp.Obj{"ev": crp.Obj{"ep": s.Ep, "seq": s.Pos}}, expectJSON(t, v, "clientPosition"))
	})

	// Frames produced by crd's own builders.
	byeOfNeg := func(t *testing.T, v *Vector) {
		n := negotiateRef(t, v, firstHello(t, v, "A"))
		sameFrame(t, "BYE", n.ByeFrame(), framesOf(v, "A", "s2c", "BYE")[0])
	}
	frameCheckers["03-version-mismatch"] = append(frameCheckers["03-version-mismatch"], byeOfNeg)
	frameCheckers["45-hello-maxframe-too-small"] = append(frameCheckers["45-hello-maxframe-too-small"], byeOfNeg)
	welcomes := func(t *testing.T, v *Vector) {
		for _, w := range framesOf(v, "", "s2c", "WELCOME") {
			n := negotiateRef(t, v, firstHello(t, v, w.Conn))
			sameFrame(t, "WELCOME "+w.Conn, welcomeFrom(t, n, header(t, w), nil), w)
		}
	}
	for _, id := range []string{"04-version-downlevel", "24-ping-pong-superseded", "31-hello-two-links", "32-hello-stale-att"} {
		frameCheckers[id] = append(frameCheckers[id], welcomes)
	}
	pongs := func(t *testing.T, v *Vector) {
		pings := framesOf(v, "", "c2s", "PING")
		for i, p := range framesOf(v, "", "s2c", "PONG") {
			pt, _ := crp.Int(header(t, pings[i]), "t")
			now, _ := crp.Int(header(t, p), "now")
			sameFrame(t, "PONG", daemon.Pong(pt, now), p)
		}
	}
	frameCheckers["24-ping-pong-superseded"] = append(frameCheckers["24-ping-pong-superseded"], pongs, func(t *testing.T, v *Vector) {
		sameFrame(t, "BYE SUPERSEDED", daemon.Bye(crp.Superseded, daemon.MsgSuperseded), framesOf(v, "A", "s2c", "BYE")[0])
	})
	frameCheckers["43-state-fg"] = append(frameCheckers["43-state-fg"], pongs)
	frameCheckers["32-hello-stale-att"] = append(frameCheckers["32-hello-stale-att"], func(t *testing.T, v *Vector) {
		sameFrame(t, "BYE STALE", daemon.Bye(crp.Stale, daemon.MsgStale), framesOf(v, "B", "s2c", "BYE")[0])
	})
	frameCheckers["44-bye-off-shutdown"] = append(frameCheckers["44-bye-off-shutdown"], func(t *testing.T, v *Vector) {
		sameFrame(t, "BYE E_OFF", daemon.Bye(crp.EOff, daemon.MsgOff), framesOf(v, "A", "s2c", "BYE")[0])
		sameFrame(t, "BYE E_SHUTDOWN", daemon.ShutdownBye(), framesOf(v, "B", "s2c", "BYE")[0])
	})
}
