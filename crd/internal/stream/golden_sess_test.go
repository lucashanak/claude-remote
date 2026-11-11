package stream

import (
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"testing"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
	"github.com/lucashanak/claude-remote/crd/internal/testenv"
)

type goldenFrame struct {
	Dir      string          `json:"dir"`
	Hex      string          `json:"hex"`
	TypeName string          `json:"typeName"`
	Header   json.RawMessage `json:"header"`
}

func goldenFrames(t *testing.T, id string) []goldenFrame {
	t.Helper()
	ms, _ := filepath.Glob(filepath.Join(testenv.RepoRoot(), "protocol", "crp", "golden", id+"-*.json"))
	if len(ms) != 1 {
		t.Fatalf("vector %s: %v", id, ms)
	}
	b, err := os.ReadFile(ms[0])
	if err != nil {
		t.Fatal(err)
	}
	var v struct {
		Frames []goldenFrame `json:"frames"`
	}
	if err := json.Unmarshal(b, &v); err != nil {
		t.Fatal(err)
	}
	return v.Frames
}

func rowsOf(t *testing.T, sessions any) map[string]Row {
	t.Helper()
	out := map[string]Row{}
	for _, x := range sessions.([]any) {
		o := x.(map[string]any)
		r := Row{}
		r.Name, _ = crp.Str(o, "name")
		r.Created, _ = crp.Int(o, "created")
		r.Clients, _ = crp.Int(o, "clients")
		r.PanePid, _ = crp.Int(o, "panePid")
		r.Cmd, _ = crp.Str(o, "cmd")
		r.Dead, _ = crp.Bool(o, "dead")
		out[r.Name] = r
	}
	return out
}

func sameBytes(t *testing.T, what string, got crp.Frame, want goldenFrame) {
	t.Helper()
	b, err := crp.Encode(got)
	if err != nil {
		t.Fatalf("%s: %v", what, err)
	}
	if hex.EncodeToString(b) != want.Hex {
		t.Fatalf("%s:\n got %x\nwant %s", what, b, want.Hex)
	}
}

// sessAt is a provider on epoch ep whose counter has reached last, with tbl as its table: the counter is driven by
// real polls (each one a diff that toggles the clients of the first row), never set by hand.
func sessAt(t *testing.T, ep string, last int64, tbl map[string]Row, id identity) *sessProvider {
	t.Helper()
	root := testenv.NewRoot(t)
	env := evEnv(t, root)
	env.Ep = ep
	s, _ := newSess(env)
	sp := s.(*sessProvider)
	var first string
	for n := range tbl {
		if first == "" || n < first {
			first = n
		}
	}
	cur := map[string]Row{}
	for n, r := range tbl {
		cur[n] = r
	}
	if last%2 == 1 { // start flipped, so that `last` flips end on tbl
		r := cur[first]
		r.Clients ^= 1
		cur[first] = r
	}
	sp.readFn = func() (map[string]Row, identity, bool) {
		m := map[string]Row{}
		for n, r := range cur {
			m[n] = r
		}
		return m, id, true
	}
	sp.poll() // first table: no item
	for sp.seq.ring.Last() < last {
		r := cur[first]
		r.Clients ^= 1
		cur[first] = r
		sp.poll()
	}
	if cur[first] != tbl[first] || sp.seq.ring.Last() != last {
		t.Fatalf("counter %d, table not back", sp.seq.ring.Last())
	}
	return sp
}

// Vector 09: SNAP sess {seq 17, from 18, new} with the table, then the EV sess diff (put changed, del gone),
// rebuilt by the provider's poll/diff/snapshot code byte for byte.
func TestGoldenSessFrameBytes09(t *testing.T) {
	fs := goldenFrames(t, "09")
	snapH, _ := crp.ParseObject(fs[1].Header)
	evH, _ := crp.ParseObject(fs[2].Header)
	ep, _ := crp.Str(snapH, "ep")
	S, _ := crp.Int(snapH, "seq")
	before := rowsOf(t, snapH["sessions"])
	id := identity{inode: "1", pid: 100, start: "5"}
	sp := sessAt(t, ep, S, before, id)
	c := newConn("A")
	if got, _ := sp.Sub(c, crp.Obj{"st": "sess"}); got != "" {
		t.Fatalf("SUB without position: %q", got)
	}
	out := c.take()
	// The order of SNAP.sessions is not specified (spec §4.5 is a table keyed by name); crd sends it sorted by name,
	// vector 09 has the generator's hand order. Erratum (Kolo 2): the vector's frame is compared with its rows sorted
	// by name, every other byte as recorded.
	sorted := crp.Obj{}
	for k, v := range snapH {
		sorted[k] = v
	}
	rows := append([]any(nil), snapH["sessions"].([]any)...)
	sort.Slice(rows, func(i, j int) bool {
		return rows[i].(map[string]any)["name"].(string) < rows[j].(map[string]any)["name"].(string)
	})
	sorted["sessions"] = rows
	wb, err := crp.Encode(crp.NewFrame(crp.SNAP, sorted, nil))
	if err != nil {
		t.Fatal(err)
	}
	if len(wb) != len(fs[1].Hex)/2 {
		t.Fatal("re-sorting changed the length")
	}
	sameBytes(t, "SNAP 09 (rows by name)", out[0], goldenFrame{Hex: hex.EncodeToString(wb)})
	after := map[string]Row{}
	for _, op := range evH["ops"].([]any) {
		o := op.(map[string]any)
		if o["op"] == "put" {
			for n, r := range rowsOf(t, []any{o["row"]}) {
				after[n] = r
			}
		}
	}
	for n, r := range before {
		if _, put := after[n]; !put && n != "claude-server-claude-remote--crtest-1" {
			after[n] = r
		}
	}
	sp.readFn = func() (map[string]Row, identity, bool) { return after, id, true }
	sp.poll()
	out = c.take()
	if len(out) != 1 {
		t.Fatalf("diff: %d frames", len(out))
	}
	sameBytes(t, "EV sess 09", out[0], fs[2])
}

// Vector 42: a replaced tmux server is SNAP{tmux-restart} at seq 90 with the new table, from the provider's code.
func TestGoldenSessFrameBytes42(t *testing.T) {
	fs := goldenFrames(t, "42")
	var snap goldenFrame
	for _, f := range fs {
		if f.TypeName == "SNAP" {
			snap = f
		}
	}
	h, _ := crp.ParseObject(snap.Header)
	ep, _ := crp.Str(h, "ep")
	S, _ := crp.Int(h, "seq")
	tbl := rowsOf(t, h["sessions"])
	sp := sessAt(t, ep, S-1, tbl, identity{inode: "1", pid: 100, start: "5"})
	c := newConn("A")
	sp.Sub(c, crp.Obj{"st": "sess"})
	c.take()
	sp.readFn = func() (map[string]Row, identity, bool) { return tbl, identity{inode: "2", pid: 200, start: "9"}, true }
	sp.poll()
	out := c.take()
	if len(out) != 1 {
		t.Fatalf("server change: %d frames", len(out))
	}
	sameBytes(t, "SNAP tmux-restart 42", out[0], snap)
}
