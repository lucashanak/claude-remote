package crp

import (
	"bytes"
	"encoding/hex"
	"errors"
	"strings"
	"testing"
)

func TestCanonEscapesOnlyQuoteBackslashAndC0(t *testing.T) {
	got := string(MustCanon(Obj{"b": "<a&b>  \x7f", "a": "q\"\\\t\n\r\b\f\x01\x1f", "c": int64(-5)}))
	want := `{"a":"q\"\\\t\n\r\b\f\u0001\u001f","b":"<a&b>` + "  \x7f" + `","c":-5}`
	if got != want {
		t.Fatalf("canon\n got %q\nwant %q", got, want)
	}
}

func TestCanonSortsByCodePoint(t *testing.T) {
	got := string(MustCanon(Obj{"é": 1, "z": 2, "Z": 3, "a": Obj{"y": true, "x": nil}}))
	if got != `{"Z":3,"a":{"x":null,"y":true},"z":2,"é":1}` {
		t.Fatalf("got %s", got)
	}
}

func TestCanonRefusesOutOfRangeAndFloats(t *testing.T) {
	if _, err := Canon(Obj{"n": int64(1<<53 + 1)}); err == nil {
		t.Fatal("2^53+1 accepted")
	}
	if _, err := Canon(Obj{"n": 1.5}); err == nil {
		t.Fatal("float accepted")
	}
}

// B6: integers only as -?[0-9]+, duplicate keys refused, non-negative fields checked.
func TestDecoderNumberAndKeyRules(t *testing.T) {
	cases := map[string]string{
		"fraction":      `{"t":1.0}`,
		"exponent":      `{"t":1e0}`,
		"negative-seq":  `{"st":"ev","ep":"a","seq":-1}`,
		"duplicate-key": `{"t":1,"t":2}`,
		"nan":           `{"t":NaN}`,
		"big":           `{"t":9007199254740993}`,
		"not-object":    `[1]`,
		"trailing":      `{"t":1} x`,
		"bad-utf8":      "{\"m\":\"\xff\"}",
	}
	for name, h := range cases {
		typ := PING
		if strings.Contains(name, "seq") {
			typ = EV
		}
		body := frameBytes(byte(typ), 0, []byte(h), nil)
		_, err := parseBody(body[4:], S2C)
		var pe *ProtoError
		if !errors.As(err, &pe) || pe.Code != EProto {
			t.Errorf("%s: want E_PROTO, got %v", name, err)
		}
	}
	// t may be negative (it is the only signed field)
	if _, err := parseBody(frameBytes(byte(PING), 0, []byte(`{"t":-1}`), nil)[4:], C2S); err != nil {
		t.Errorf("negative t refused: %v", err)
	}
}

func frameBytes(typ, flags byte, h, blob []byte) []byte {
	var b bytes.Buffer
	n := 4 + len(h) + len(blob)
	b.Write([]byte{byte(n >> 24), byte(n >> 16), byte(n >> 8), byte(n), typ, flags, byte(len(h) >> 8), byte(len(h))})
	b.Write(h)
	b.Write(blob)
	return b.Bytes()
}

func TestRoundTripAndEmptyHeader(t *testing.T) {
	b := MustEncode(Frame{Type: SUB, Header: Obj{}})
	if hex.EncodeToString(b) != "0000000410000000" {
		t.Fatalf("empty header frame %x", b)
	}
	f := NewFrame(EV, Obj{"st": "ev", "ep": "a1b2c3d4", "seq": int64(3), "src": "events", "at": int64(1)}, []byte("line"))
	raw := MustEncode(f)
	g, pos, ok, err := DecodeOne(raw, 0, DefaultMaxFrame, S2C)
	if err != nil || !ok || pos != len(raw) || string(g.Blob) != "line" {
		t.Fatalf("round trip: %v %v %d", err, ok, pos)
	}
	if !bytes.Equal(MustEncode(g), raw) {
		t.Fatal("re-encode differs")
	}
}

func TestReaderRefusesOversizeBeforeReadingBody(t *testing.T) {
	// a length of 2 MiB with no body behind it: the reader must refuse on the length alone
	r := NewReader(bytes.NewReader([]byte{0, 0x20, 0, 0}), C2S)
	r.first = false
	_, err := r.Next()
	var pe *ProtoError
	if !errors.As(err, &pe) || pe.Code != EFrame {
		t.Fatalf("want E_FRAME, got %v", err)
	}
}

func TestReaderFirstFrameMustBeHandshake(t *testing.T) {
	r := NewReader(bytes.NewReader(MustEncode(Frame{Type: PING, Header: Obj{"t": int64(1)}})), C2S)
	if _, err := r.Next(); err == nil {
		t.Fatal("PING accepted as first client frame")
	}
}

func TestSkipJunk(t *testing.T) {
	n, err := SkipJunk(bytes.NewReader([]byte("motd\n" + Magic + "x")))
	if err != nil || n != 5 {
		t.Fatalf("n=%d err=%v", n, err)
	}
	_, err = SkipJunk(bytes.NewReader(bytes.Repeat([]byte("j"), JunkLimit+10)))
	if err == nil {
		t.Fatal("junk over the limit accepted")
	}
}

func TestExtensionTypeSkipped(t *testing.T) {
	f, err := parseBody(frameBytes(0x90, 0, []byte("{}"), nil)[4:], C2S)
	if err != nil || !f.Ignored {
		t.Fatalf("extension frame: %v %+v", err, f)
	}
}

// T-R18/R19/R20: each rule refuses on its own. The frames are valid except for the one thing named, and the valid
// twin of each passes (so the refusal is not some other rule's: review T, the old negative-seq case had no blob).
func TestDecoderTypedFieldRulesOnValidFrames(t *testing.T) {
	cases := []struct {
		name     string
		typ      Type
		bad, ok  string
		blob     []byte
		dir      Dir
		contains string
	}{
		{"negative-seq", EV, `{"at":1,"ep":"a","seq":-1,"src":"events","st":"ev"}`, `{"at":1,"ep":"a","seq":1,"src":"events","st":"ev"}`,
			[]byte("line"), S2C, "EV.seq is not a valid integer"},
		{"bool-type", TX, `{"enc":"none","f":0,"more":1,"o":1,"u":"u","wid":"w"}`, `{"enc":"none","f":0,"more":true,"o":1,"u":"u","wid":"w"}`,
			[]byte("x"), S2C, "TX.more is not a boolean"},
		{"sess-ev-with-blob", EV, `{"ep":"a","ops":[],"seq":1,"st":"sess"}`, `{"ep":"a","ops":[],"seq":1,"st":"sess"}`,
			[]byte("x"), S2C, "blob"},
	}
	for _, tc := range cases {
		flags := byte(0)
		if tc.blob != nil {
			flags = FlagBlob
		}
		okBlob, okFlags := tc.blob, flags
		if tc.name == "sess-ev-with-blob" {
			okBlob, okFlags = nil, 0
		}
		if _, err := parseBody(frameBytes(byte(tc.typ), okFlags, []byte(tc.ok), okBlob)[4:], tc.dir); err != nil {
			t.Fatalf("%s: the valid twin is refused: %v", tc.name, err)
		}
		_, err := parseBody(frameBytes(byte(tc.typ), flags, []byte(tc.bad), tc.blob)[4:], tc.dir)
		var pe *ProtoError
		if !errors.As(err, &pe) || pe.Code != EProto || !strings.Contains(pe.Msg, tc.contains) {
			t.Errorf("%s: want E_PROTO %q, got %v", tc.name, tc.contains, err)
		}
	}
}
