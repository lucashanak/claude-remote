package goldentest

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
)

// goldenDir is the source of truth since B1a (dodatek §2): protocol/crp/golden/ in the repo.
var goldenDir = filepath.Join("..", "..", "..", "protocol", "crp", "golden")

// Vector is one golden file.
type Vector struct {
	ID            string                     `json:"id"`
	Frames        []VFrame                   `json:"frames"`
	Streams       []VStream                  `json:"streams"`
	Expect        map[string]json.RawMessage `json:"expect"`
	ModelInput    map[string]json.RawMessage `json:"modelInput"`
	StreamHex     string                     `json:"streamHex"`
	DecodeOptions *struct {
		Direction string `json:"direction"`
		Excerpt   bool   `json:"excerpt"`
		SkipJunk  bool   `json:"skip_junk"`
	} `json:"decodeOptions"`
}

// VFrame is one recorded frame.
type VFrame struct {
	Conn     string          `json:"conn"`
	Dir      string          `json:"dir"`
	Hex      string          `json:"hex"`
	TypeName string          `json:"typeName"`
	Header   json.RawMessage `json:"header"`
	BlobHex  *string         `json:"blobHex"`
}

// VStream is one (conn, dir) byte stream.
type VStream struct {
	Conn     string `json:"conn"`
	Dir      string `json:"dir"`
	Excerpt  bool   `json:"excerpt"`
	MaxFrame int    `json:"maxFrame"`
	Hex      string `json:"hex"`
	JunkHex  string `json:"junkHex"`
}

// Owner of one (vector, expect key): the celek whose test verifies it, or "out: <reason>" (dodatek R1/B4).
// A key in a vector that is not in this table, a table row whose key is gone, or a vector file without a row is a
// FAIL. Rows owned by a celek whose files are absent from the tree are reported as "pending: celek N".
var owners = map[string]map[string]string{
	"01-preamble-junk":                 {"junkSkipped": "celek1"},
	"02-handshake-fresh":               {"negotiation": "celek2", "resume": "celek3", "reason": "celek3", "backlogSeqs": "celek3"},
	"03-version-mismatch":              {"negotiation": "celek2", "client": "out: client fallback (Kotlin B2)"},
	"04-version-downlevel":             {"negotiation": "celek2"},
	"05-ev-delta-ack":                  {"clientPosition": "celek2", "daemonAck": "celek3"},
	"06-resume-replay":                 {"resume": "celek3", "replaySeqs": "celek3"},
	"07-resume-overflow":               {"resume": "celek3", "reason": "celek3", "ringFirst": "celek3", "backlogCount": "celek3", "backlogFirstSeq": "celek3", "backlogLastSeq": "celek3", "framesShown": "celek3"},
	"08-resume-epoch-change":           {"resume": "celek3", "reason": "celek3", "backlogSeqs": "celek3"},
	"09-sess-snapshot-diff":            {"tableAfter": "out: the client applies the diff (Kotlin B2)"},
	"10-tx-sub-delta":                  {"model": "celek3", "watchOff": "celek3"},
	"11-tx-truncated":                  {"model": "celek3"},
	"12-term-sub-out":                  {"daemonTmux": "out: term attach (B3)", "ptySize": "out: term attach (B3)"},
	"13-term-gap-redraw":               {"daemonTmux": "out: term redraw (B3)", "gap": "out: term redraw (B3)"},
	"14-term-resize-owner":             {"model": "out: size_owner (B3)"},
	"15-in-applied-dup":                {"statuses": "out: InDedup (B3)", "plan": "out: pacing_plan (B3)", "payloadHash": "out: InDedup (B3)"},
	"16-in-unknown-and-gap":            {"statuses": "out: InDedup (B3)", "planEscape": "out: pacing_plan (B3)"},
	"17-rpc-exec-allowed":              {"denyScan": "out: deny_scan (B1b)"},
	"18-rpc-exec-parts":                {"denyScan": "out: deny_scan (B1b)", "stdoutBytes": "out: rpc exec (B1b)"},
	"19-rpc-denied-kill-server":        {"denyScan": "out: deny_scan (B1b)", "kotlin": "out: Kotlin mapping (B2)"},
	"20-rpc-denied-unknown-tag":        {"denyScan": "out: deny_scan (B1b)", "kotlin": "out: Kotlin mapping (B2)"},
	"21-rpc-denied-new-alias":          {"denyScan": "out: deny_scan (B1b)", "kotlin": "out: Kotlin mapping (B2)"},
	"22-rpc-denied-fresh-create":       {"denyScan": "out: deny_scan (B1b)", "kotlin": "out: Kotlin mapping (B2)"},
	"23-rpc-denied-set-g":              {"denyScan": "out: deny_scan (B1b)", "kotlin": "out: Kotlin mapping (B2)"},
	"24-ping-pong-superseded":          {"table": "celek2", "clientOnSuperseded": "out: client prose (Kotlin B2)"},
	"25-tx-line-pieces":                {"negotiation": "celek2", "pieces": "celek3", "delta": "celek3"},
	"26-tx-reset-pending":              {"poll1": "celek3", "poll2": "celek3"},
	"27-ev-empty-ring":                 {"A": "celek3", "B": "celek3", "C-ahead (model only)": "celek3", "nextLiveSeq": "celek3"},
	"28-ev-restart-seed":               {"seeded": "celek3", "last": "celek3", "framesShown": "celek3"},
	"29-ev-midstream-overflow":         {"next": "celek3", "backlogCount": "celek3", "clientSteps": "celek3", "clientPosition": "celek3"},
	"30-ack-rules":                     {"daemon": "celek3"},
	"31-hello-two-links":               {"table": "celek2"},
	"32-hello-stale-att":               {"table": "celek2"},
	"33-in-refused-midpipeline":        {"statuses": "out: InDedup (B3)"},
	"34-in-conflict":                   {"statuses": "out: InDedup (B3)", "client": "out: client prose (B3)"},
	"35-in-skip":                       {"statuses": "out: InDedup (B3)", "resendOf30": "out: InDedup (B3)"},
	"36-in-state-lost":                 {"inGen-changed": "out: client_on_welcome (B3)", "inHi-below-acked": "out: client_on_welcome (B3)", "normal": "out: client_on_welcome (B3)"},
	"37-in-paste-plan":                 {"plans": "out: pacing_plan (B3)"},
	"38-rpc-deny-table":                {"cases": "out: deny_scan (B1b)"},
	"39-rpc-allow-table":               {"cases": "out: deny_scan (B1b)"},
	"40-rpc-errors":                    {"kotlin": "out: Kotlin mapping (B2)"},
	"41-header-nonascii":               {"headerHex": "celek1"},
	"42-tmux-socket-and-server-change": {"client": "out: client prose (Kotlin B2)"},
	"43-state-fg":                      {"daemonCloseAfterMs": "celek2", "clientDeadAfterMs": "out: client liveness (Kotlin B2)"},
	"44-bye-off-shutdown":              {"client": "out: client prose (Kotlin B2)"},
	"45-hello-maxframe-too-small":      {"negotiation": "celek2"},
	"90-neg-frame-too-large":           {"error": "celek1", "decoded": "celek1"},
	"91-neg-reserved-flags":            {"error": "celek1", "decoded": "celek1"},
	"92-neg-unknown-core-type":         {"error": "celek1", "decoded": "celek1"},
	"93-ext-type-ignored":              {"error": "celek1", "decoded": "celek1"},
	"94-neg-no-magic":                  {"error": "celek1", "decoded": "celek1"},
	"95-neg-missing-field":             {"error": "celek1", "decoded": "celek1"},
	"96-neg-wrong-direction":           {"error": "celek1", "decoded": "celek1"},
	"97-neg-blob-on-blobless-type":     {"error": "celek1", "decoded": "celek1"},
	"98-neg-field-type":                {"error": "celek1", "decoded": "celek1"},
	"99-neg-first-not-hello":           {"error": "celek1", "decoded": "celek1"},
}

// verifiers: "vector/key" -> check. Celek 2 and 3 test files add theirs in init() and mark their celek present.
var (
	verifiers    = map[string]func(t *testing.T, v *Vector){}
	celekPresent = map[string]bool{"celek1": true}
	// frameCheckers compare bytes produced by crd's own code with the recorded hex (dodatek §2 (b)); they run per
	// vector after the codec checks.
	frameCheckers = map[string][]func(t *testing.T, v *Vector){}
)

func register(owner, id, key string, f func(t *testing.T, v *Vector)) {
	if owners[id][key] != owner {
		panic("register: " + id + "/" + key + " is not owned by " + owner)
	}
	verifiers[id+"/"+key] = f
}

func loadAll(t *testing.T) []*Vector {
	t.Helper()
	ents, err := os.ReadDir(goldenDir)
	if err != nil {
		t.Fatal(err)
	}
	var vs []*Vector
	for _, e := range ents {
		n := e.Name()
		if !strings.HasSuffix(n, ".json") || n == "index.json" {
			continue
		}
		b, err := os.ReadFile(filepath.Join(goldenDir, n))
		if err != nil {
			t.Fatal(err)
		}
		v := &Vector{}
		if err := json.Unmarshal(b, v); err != nil {
			t.Fatalf("%s: %v", n, err)
		}
		if v.ID+".json" != n {
			t.Fatalf("%s: id %q", n, v.ID)
		}
		vs = append(vs, v)
	}
	return vs
}

// B5: the vectors are enumerated from the directory; index.json must agree (55, same ids).
func TestGoldenDirectoryAndIndex(t *testing.T) {
	vs := loadAll(t)
	if len(vs) != 55 {
		t.Fatalf("%d vectors, want 55", len(vs))
	}
	var idx struct {
		Count   int `json:"count"`
		Vectors []struct {
			ID string `json:"id"`
		} `json:"vectors"`
	}
	b, err := os.ReadFile(filepath.Join(goldenDir, "index.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(b, &idx); err != nil {
		t.Fatal(err)
	}
	var a, c []string
	for _, v := range vs {
		a = append(a, v.ID)
	}
	for _, v := range idx.Vectors {
		c = append(c, v.ID)
	}
	sort.Strings(a)
	sort.Strings(c)
	if idx.Count != 55 || strings.Join(a, ",") != strings.Join(c, ",") {
		t.Fatalf("index.json (%d) does not match the directory", idx.Count)
	}
}

// canonOf parses a recorded header with crd's parser and writes it with crd's canonical writer.
func canonOf(t *testing.T, raw json.RawMessage) []byte {
	t.Helper()
	o, err := crp.ParseObject(raw)
	if err != nil {
		t.Fatalf("recorded header %s: %v", raw, err)
	}
	return crp.MustCanon(o)
}

// TestGoldenCodec: every stream decodes to exactly its recorded frames, every frame re-encodes to its hex, every
// negative stream fails with the recorded code (spec §10).
func TestGoldenCodec(t *testing.T) {
	for _, v := range loadAll(t) {
		v := v
		t.Run(v.ID, func(t *testing.T) {
			if v.DecodeOptions != nil {
				checkNegative(t, v)
				return
			}
			for _, s := range v.Streams {
				raw, _ := hex.DecodeString(s.Hex)
				dir, ok := crp.ParseDir(s.Dir)
				if !ok {
					t.Fatalf("dir %q", s.Dir)
				}
				junk, frames, err := crp.DecodeStream(raw, dir, s.MaxFrame, s.Excerpt, s.JunkHex != "")
				if err != nil {
					t.Fatalf("%s/%s: %v", s.Conn, s.Dir, err)
				}
				if junk != len(s.JunkHex)/2 {
					t.Fatalf("junk %d, want %d", junk, len(s.JunkHex)/2)
				}
				var mine []VFrame
				for _, f := range v.Frames {
					if f.Conn == s.Conn && f.Dir == s.Dir {
						mine = append(mine, f)
					}
				}
				if len(frames) != len(mine) {
					t.Fatalf("%s/%s: %d frames decoded, %d recorded", s.Conn, s.Dir, len(frames), len(mine))
				}
				for i, g := range frames {
					r := mine[i]
					if g.Type.Name() != r.TypeName {
						t.Fatalf("frame %d: type %s, want %s", i, g.Type.Name(), r.TypeName)
					}
					if !bytes.Equal(crp.MustCanon(g.Header), canonOf(t, r.Header)) {
						t.Fatalf("frame %d header differs", i)
					}
					wantBlob := ""
					if r.BlobHex != nil {
						wantBlob = *r.BlobHex
					}
					if hex.EncodeToString(g.Blob) != wantBlob || g.HasBlob != (r.BlobHex != nil) {
						t.Fatalf("frame %d blob differs", i)
					}
					// re-encode the RECORDED frame with crd's encoder
					ro, _ := crp.ParseObject(r.Header)
					var blob []byte
					if r.BlobHex != nil {
						blob, _ = hex.DecodeString(*r.BlobHex)
						if blob == nil {
							blob = []byte{}
						}
					}
					typ, _ := crp.TypeByName(r.TypeName)
					enc, err := crp.Encode(crp.NewFrame(typ, ro, blob))
					if err != nil || hex.EncodeToString(enc) != r.Hex {
						t.Fatalf("frame %d re-encode differs (%v)", i, err)
					}
					if len(enc)-4 > s.MaxFrame {
						t.Fatalf("frame %d above maxFrame", i)
					}
				}
			}
		})
	}
}

func checkNegative(t *testing.T, v *Vector) {
	raw, _ := hex.DecodeString(v.StreamHex)
	dir, _ := crp.ParseDir(v.DecodeOptions.Direction)
	_, frames, err := crp.DecodeStream(raw, dir, crp.DefaultMaxFrame, v.DecodeOptions.Excerpt, v.DecodeOptions.SkipJunk)
	var want *string
	_ = json.Unmarshal(v.Expect["error"], &want)
	if want == nil {
		if err != nil {
			t.Fatalf("want success, got %v", err)
		}
		var dec []struct {
			TypeName *string        `json:"typeName"`
			Header   map[string]any `json:"header"`
			Ignored  bool           `json:"ignored"`
		}
		_ = json.Unmarshal(v.Expect["decoded"], &dec)
		if len(dec) != len(frames) {
			t.Fatalf("decoded %d frames, want %d", len(frames), len(dec))
		}
		for i, f := range frames {
			if f.Ignored != dec[i].Ignored || (dec[i].TypeName != nil) == f.Ignored {
				t.Fatalf("frame %d ignored=%v", i, f.Ignored)
			}
			if dec[i].TypeName != nil && *dec[i].TypeName != f.Type.Name() {
				t.Fatalf("frame %d type %s", i, f.Type.Name())
			}
		}
		return
	}
	var pe *crp.ProtoError
	if !errors.As(err, &pe) || pe.Code != *want {
		t.Fatalf("want %s, got %v", *want, err)
	}
	if string(v.Expect["decoded"]) != "null" {
		t.Fatalf("decoded must be null for an error vector")
	}
}

// TestGoldenExpectOwnership walks every (vector, expect key) through the owner table and runs its verifier.
func TestGoldenExpectOwnership(t *testing.T) {
	vs := loadAll(t)
	seen := map[string]bool{}
	for _, v := range vs {
		seen[v.ID] = true
		row, ok := owners[v.ID]
		if !ok {
			t.Errorf("%s: vector without a row in the owner table", v.ID)
			continue
		}
		for k := range v.Expect {
			if _, ok := row[k]; !ok {
				t.Errorf("%s: expect key %q not in the owner table", v.ID, k)
			}
		}
		for k, owner := range row {
			if _, ok := v.Expect[k]; !ok {
				t.Errorf("%s: owner table names %q, the vector has no such key", v.ID, k)
				continue
			}
			v, k, owner := v, k, owner
			t.Run(v.ID+"/"+k, func(t *testing.T) {
				switch {
				case strings.HasPrefix(owner, "out: "):
					t.Logf("not in B1a: %s", strings.TrimPrefix(owner, "out: "))
				case !celekPresent[owner]:
					t.Logf("pending: %s (its files are not in this tree)", owner)
				default:
					f := verifiers[v.ID+"/"+k]
					if f == nil {
						t.Fatalf("owned by %s, which is present, but no verifier is registered", owner)
					}
					f(t, v)
				}
			})
		}
	}
	for id := range owners {
		if !seen[id] {
			t.Errorf("owner table row %s without a vector file", id)
		}
	}
}

// TestGoldenFrameBytes runs the registered producers: bytes made by crd's code == recorded hex.
func TestGoldenFrameBytes(t *testing.T) {
	n := 0
	for _, v := range loadAll(t) {
		for _, f := range frameCheckers[v.ID] {
			n++
			v, f := v, f
			t.Run(v.ID, func(t *testing.T) { f(t, v) })
		}
	}
	t.Logf("%d frame producers checked", n)
}

func init() {
	register("celek1", "01-preamble-junk", "junkSkipped", func(t *testing.T, v *Vector) {
		var want int
		_ = json.Unmarshal(v.Expect["junkSkipped"], &want)
		raw, _ := hex.DecodeString(v.Streams[0].Hex)
		// the client side reader (SkipJunk) and the buffer decoder agree on the count
		n, err := crp.SkipJunk(bytes.NewReader(raw))
		if err != nil || n != want {
			t.Fatalf("SkipJunk = %d, %v; want %d", n, err, want)
		}
	})
	register("celek1", "41-header-nonascii", "headerHex", func(t *testing.T, v *Vector) {
		var want string
		_ = json.Unmarshal(v.Expect["headerHex"], &want)
		if got := hex.EncodeToString(canonOf(t, v.Frames[0].Header)); got != want {
			t.Fatalf("canonical header %s, want %s", got, want)
		}
	})
	for id, row := range owners {
		for k, o := range row {
			if o == "celek1" && (k == "error" || k == "decoded") {
				register("celek1", id, k, func(t *testing.T, v *Vector) { checkNegative(t, v) })
			}
		}
	}
}

// Helpers shared with the celek 2/3 files.

func expectJSON(t *testing.T, v *Vector, key string) any {
	t.Helper()
	x, err := crp.ParseValue(v.Expect[key])
	if err != nil {
		t.Fatalf("%s/%s: %v", v.ID, key, err)
	}
	return x
}

// sameJSON compares a value built by crd with a recorded JSON value through the canonical writer.
func sameJSON(t *testing.T, what string, got any, want any) {
	t.Helper()
	g, err := crp.Canon(got)
	if err != nil {
		t.Fatalf("%s: %v", what, err)
	}
	w := crp.MustCanon(want)
	if !bytes.Equal(g, w) {
		t.Fatalf("%s:\n got %s\nwant %s", what, g, w)
	}
}

func framesOf(v *Vector, conn, dir, typ string) []VFrame {
	var out []VFrame
	for _, f := range v.Frames {
		if (conn == "" || f.Conn == conn) && f.Dir == dir && (typ == "" || f.TypeName == typ) {
			out = append(out, f)
		}
	}
	return out
}

func header(t *testing.T, f VFrame) crp.Obj {
	t.Helper()
	o, err := crp.ParseObject(f.Header)
	if err != nil {
		t.Fatal(err)
	}
	return o
}

func sameFrame(t *testing.T, what string, got crp.Frame, want VFrame) {
	t.Helper()
	b, err := crp.Encode(got)
	if err != nil {
		t.Fatalf("%s: %v", what, err)
	}
	if hex.EncodeToString(b) != want.Hex {
		t.Fatalf("%s: crd built\n %x\nrecorded\n %s", what, b, want.Hex)
	}
}
