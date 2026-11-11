package crp

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
)

// Frame is one decoded or to-be-encoded frame. HasBlob mirrors flags bit 0; Ignored marks a skipped extension frame.
type Frame struct {
	Type    Type
	Header  Obj
	Blob    []byte
	HasBlob bool
	Ignored bool
}

// NewFrame builds a frame; a non-nil blob sets HasBlob.
func NewFrame(t Type, h Obj, blob []byte) Frame {
	return Frame{Type: t, Header: h, Blob: blob, HasBlob: blob != nil}
}

// Encode writes one frame (spec §3.1). An empty header is written with hlen 0, like gen.py.
func Encode(f Frame) ([]byte, error) {
	var h []byte
	if len(f.Header) > 0 {
		var err error
		if h, err = Canon(f.Header); err != nil {
			return nil, err
		}
	}
	if len(h) > 0xFFFF {
		return nil, perr(EFrame, "header over 65535 bytes")
	}
	var flags byte
	if f.HasBlob {
		flags = FlagBlob
	}
	body := 4 + len(h) + len(f.Blob)
	out := make([]byte, 4+body)
	binary.BigEndian.PutUint32(out, uint32(body))
	out[4] = byte(f.Type)
	out[5] = flags
	binary.BigEndian.PutUint16(out[6:], uint16(len(h)))
	copy(out[8:], h)
	copy(out[8+len(h):], f.Blob)
	return out, nil
}

// MustEncode is Encode for frames the daemon builds itself.
func MustEncode(f Frame) []byte {
	b, err := Encode(f)
	if err != nil {
		panic(err)
	}
	return b
}

// parseBody applies the receiver rules (§3.2) to one frame body (everything after the length field).
func parseBody(body []byte, dir Dir) (Frame, error) {
	ln := len(body)
	t := Type(body[0])
	flags := body[1]
	hlen := int(binary.BigEndian.Uint16(body[2:4]))
	if flags&^FlagBlob != 0 {
		return Frame{}, perr(EProto, fmt.Sprintf("reserved flag bits 0x%02x", flags))
	}
	if 4+hlen > ln {
		return Frame{}, perr(EFrame, "header longer than frame")
	}
	hraw := body[4 : 4+hlen]
	blob := body[4+hlen:]
	hasBlob := flags&FlagBlob != 0
	if !hasBlob && len(blob) > 0 {
		return Frame{}, perr(EProto, "bytes after header without the blob flag")
	}
	ti, known := types[t]
	if !known {
		if t >= 0x80 {
			return Frame{Type: t, Ignored: true}, nil
		}
		return Frame{}, perr(EProto, fmt.Sprintf("unknown core type 0x%02x", byte(t)))
	}
	if (dir == C2S && !ti.c2s) || (dir == S2C && !ti.s2c) {
		return Frame{}, perr(EProto, fmt.Sprintf("%s in direction %s", ti.name, dir))
	}
	h := Obj{}
	if hlen > 0 {
		var err error
		if h, err = ParseObject(hraw); err != nil {
			return Frame{}, perr(EProto, "header is not a UTF-8 JSON object: "+err.Error())
		}
	}
	if err := Validate(t, h, hasBlob); err != nil {
		return Frame{}, err
	}
	var b []byte
	if hasBlob {
		b = append([]byte{}, blob...)
	}
	return Frame{Type: t, Header: h, Blob: b, HasBlob: hasBlob}, nil
}

// Validate checks required keys, typed top-level keys and the blob rules of §3.2/§3.3.
func Validate(t Type, h Obj, hasBlob bool) error {
	ti := types[t]
	for _, k := range ti.required {
		if _, ok := h[k]; !ok {
			return perr(EProto, fmt.Sprintf("%s without '%s'", ti.name, k))
		}
	}
	for k, v := range h {
		switch {
		case intFields[k]:
			n, ok := v.(int64)
			if !ok || (k != "t" && n < 0) {
				return perr(EProto, fmt.Sprintf("%s.%s is not a valid integer", ti.name, k))
			}
		case strFields[k]:
			if _, ok := v.(string); !ok {
				return perr(EProto, fmt.Sprintf("%s.%s is not a string", ti.name, k))
			}
		case boolFields[k]:
			if _, ok := v.(bool); !ok {
				return perr(EProto, fmt.Sprintf("%s.%s is not a boolean", ti.name, k))
			}
		}
	}
	if hasBlob && !ti.blob {
		return perr(EProto, ti.name+" carries a blob")
	}
	if (t == TX || t == TOUT || t == IN) && !hasBlob {
		return perr(EProto, ti.name+" without blob")
	}
	if t == EV {
		st, _ := h["st"].(string)
		switch st {
		case "ev":
			if !hasBlob {
				return perr(EProto, "EV(ev) without blob")
			}
		case "sess":
			if _, ok := h["ops"]; !ok || hasBlob {
				return perr(EProto, "EV(sess) needs ops in the header and no blob")
			}
		default:
			return perr(EProto, fmt.Sprintf("EV of unknown stream %q", st))
		}
	}
	return nil
}

// DecodeOne decodes the frame at buf[pos:]. It returns (frame, newPos, nil), or (_, pos, nil) with ok=false when
// the buffer holds an incomplete frame.
func DecodeOne(buf []byte, pos, maxFrame int, dir Dir) (Frame, int, bool, error) {
	if len(buf)-pos < 4 {
		return Frame{}, pos, false, nil
	}
	ln := int(binary.BigEndian.Uint32(buf[pos:]))
	if ln < 4 {
		return Frame{}, pos, false, perr(EFrame, fmt.Sprintf("length %d < 4", ln))
	}
	if ln > maxFrame {
		return Frame{}, pos, false, perr(EFrame, fmt.Sprintf("length %d > maxFrame %d", ln, maxFrame))
	}
	if len(buf)-pos-4 < ln {
		return Frame{}, pos, false, nil
	}
	f, err := parseBody(buf[pos+4:pos+4+ln], dir)
	if err != nil {
		return Frame{}, pos, false, err
	}
	return f, pos + 4 + ln, true, nil
}

// DecodeStream decodes one direction of one connection (gen.py decode_stream). excerpt=false: the first frame is
// the handshake frame of that direction (<= 64 KiB). skipJunk: skip up to JunkLimit bytes before the magic.
func DecodeStream(buf []byte, dir Dir, maxFrame int, excerpt, skipJunk bool) (junk int, frames []Frame, err error) {
	pos := 0
	if skipJunk {
		lim := JunkLimit + len(Magic)
		if lim > len(buf) {
			lim = len(buf)
		}
		idx := bytes.Index(buf[:lim], []byte(Magic))
		if idx < 0 {
			return 0, nil, perr(ENoDaemon, fmt.Sprintf("no CRP magic in the first %d bytes", JunkLimit))
		}
		junk, pos = idx, idx+len(Magic)
	} else {
		if !bytes.HasPrefix(buf, []byte(Magic)) {
			return 0, nil, perr(EProto, "stream does not start with the CRP magic")
		}
		pos = len(Magic)
	}
	first := !excerpt
	for pos < len(buf) {
		limit := maxFrame
		if first {
			limit = PreNegotiationMax
		}
		f, npos, ok, err := DecodeOne(buf, pos, limit, dir)
		if err != nil {
			return junk, nil, err
		}
		if !ok {
			return junk, nil, perr(EFrame, "truncated frame at end of buffer")
		}
		if first && !isHandshake(f, dir) {
			return junk, nil, perr(EProto, fmt.Sprintf("first frame %s is not a handshake frame of %s", f.Type.Name(), dir))
		}
		frames = append(frames, f)
		pos = npos
		first = false
	}
	return junk, frames, nil
}

func isHandshake(f Frame, dir Dir) bool {
	if f.Ignored {
		return false
	}
	if dir == C2S {
		return f.Type == HELLO
	}
	return f.Type == WELCOME || f.Type == BYE
}

// Reader reads frames from a live byte stream. The length is checked against the limit before the body is read,
// so an oversize frame is refused without buffering it (§3.4).
type Reader struct {
	r        io.Reader
	dir      Dir
	MaxFrame int
	first    bool
}

// NewReader expects the magic to be consumed already; the first frame is limited to 64 KiB and must be the
// handshake frame of dir.
func NewReader(r io.Reader, dir Dir) *Reader {
	return &Reader{r: r, dir: dir, MaxFrame: DefaultMaxFrame, first: true}
}

// Next returns the next frame. Extension frames come back with Ignored set. io.EOF only at a frame boundary.
func (fr *Reader) Next() (Frame, error) {
	var lb [4]byte
	if _, err := io.ReadFull(fr.r, lb[:]); err != nil {
		if err == io.ErrUnexpectedEOF {
			return Frame{}, perr(EFrame, "truncated length")
		}
		return Frame{}, err
	}
	ln := int(binary.BigEndian.Uint32(lb[:]))
	limit := fr.MaxFrame
	if fr.first {
		limit = PreNegotiationMax
	}
	if ln < 4 {
		return Frame{}, perr(EFrame, fmt.Sprintf("length %d < 4", ln))
	}
	if ln > limit {
		return Frame{}, perr(EFrame, fmt.Sprintf("length %d > maxFrame %d", ln, limit))
	}
	body := make([]byte, ln)
	if _, err := io.ReadFull(fr.r, body); err != nil {
		return Frame{}, perr(EFrame, "truncated frame")
	}
	f, err := parseBody(body, fr.dir)
	if err != nil {
		return Frame{}, err
	}
	if fr.first && !isHandshake(f, fr.dir) {
		return Frame{}, perr(EProto, fmt.Sprintf("first frame %s is not a handshake frame of %s", f.Type.Name(), fr.dir))
	}
	fr.first = false
	return f, nil
}

// ReadMagic consumes exactly the 8 magic bytes; anything else is E_PROTO (the daemon tolerates no junk, §2.2).
func ReadMagic(r io.Reader) error {
	var m [len(Magic)]byte
	if _, err := io.ReadFull(r, m[:]); err != nil {
		return err
	}
	if string(m[:]) != Magic {
		return perr(EProto, "bytes before the client's magic")
	}
	return nil
}

// SkipJunk reads up to JunkLimit bytes before the magic (client side, §2.2).
func SkipJunk(r io.Reader) (int, error) {
	var win []byte
	one := make([]byte, 1)
	total := 0
	for {
		if _, err := io.ReadFull(r, one); err != nil {
			return 0, perr(ENoDaemon, "stream ended before the CRP magic")
		}
		total++
		win = append(win, one[0])
		if len(win) > len(Magic) {
			win = win[1:]
		}
		if string(win) == Magic {
			return total - len(Magic), nil
		}
		if total-len(Magic) >= JunkLimit {
			return 0, perr(ENoDaemon, fmt.Sprintf("no CRP magic in the first %d bytes", JunkLimit))
		}
	}
}
