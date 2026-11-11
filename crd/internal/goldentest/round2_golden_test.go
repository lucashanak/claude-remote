package goldentest

import (
	"encoding/hex"
	"testing"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/stream"
)

// Dodatek 1 §2(b), review T: the EV of vector 05 rebuilt by crd's ev frame code, byte for byte. Vectors 09 (SNAP
// sess + EV sess ops) and 42 (SNAP tmux-restart) need the sess provider's own poll, diff and snapshot code, which is
// unexported: their byte checks are in the stream package (stream/golden_sess_test.go, TestGoldenSessFrameBytes).
func init() {
	frameCheckers["05-ev-delta-ack"] = append(frameCheckers["05-ev-delta-ack"], func(t *testing.T, v *Vector) {
		for _, f := range framesOf(v, "A", "s2c", "EV") {
			h := header(t, f)
			ep, _ := crp.Str(h, "ep")
			seq, _ := crp.Int(h, "seq")
			src, _ := crp.Str(h, "src")
			at, _ := crp.Int(h, "at")
			blob, err := hex.DecodeString(*f.BlobHex)
			if err != nil {
				t.Fatal(err)
			}
			it := stream.Item{Seq: seq, Src: src, Line: blob, At: time.UnixMilli(at)}
			sameFrame(t, "EV 05", stream.EvFrame(ep, it), f)
		}
	})
}
