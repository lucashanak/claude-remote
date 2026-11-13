package stream

import (
	"bytes"
	"compress/gzip"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"github.com/lucashanak/claude-remote/crd/internal/crp"
)

// TxTail is streamd v4's backlog window for off == 0 (streamd.py TAIL).
const TxTail = 200000

// TxWatch is one transcript watch with streamd v4 semantics (streamd.py:80-117, gen.py TxWatch) plus CRP's reset,
// held pending until the next emitted chunk.
type TxWatch struct {
	Off          int64
	pendingReset bool
}

// TxDelta is one emitted chunk.
type TxDelta struct {
	F, O  int64
	Reset bool
	Chunk []byte
}

// TxRange is one emitted chunk as file offsets [F, O): the daemon reads its bytes piece by piece when it sends them,
// so a watch never holds more than the flow-control window (review A-F7).
type TxRange struct {
	F, O  int64
	Reset bool
}

// txScanBlock is the block size of the newline scans: memory per poll is one block, whatever the file size.
const txScanBlock = 64 << 10

// Poll reads the file of size sz through read(off, n). nil = nothing to send. It is PollRange plus one read of the
// chunk (the golden models use it); the daemon uses PollRange.
func (w *TxWatch) Poll(sz int64, read func(off, n int64) ([]byte, error)) (*TxDelta, error) {
	saved := *w
	r, err := w.PollRange(sz, readerAt(read))
	if err != nil || r == nil {
		return nil, err
	}
	chunk, err := read(r.F, r.O-r.F)
	if err == nil && int64(len(chunk)) != r.O-r.F {
		err = io.ErrUnexpectedEOF
	}
	if err != nil {
		*w = saved
		return nil, err
	}
	return &TxDelta{F: r.F, O: r.O, Reset: r.Reset, Chunk: chunk}, nil
}

// PollRange is streamd v4's poll (streamd.py:80-117) on a file of size sz without reading the remainder at once: the
// last newline is found by scanning backwards in blocks, the first one (tail window) forwards.
func (w *TxWatch) PollRange(sz int64, ra io.ReaderAt) (*TxRange, error) {
	off := w.Off
	if off < 0 { // "from EOF": the client loads its own backlog
		w.Off = sz
		return nil, nil
	}
	adjusted := false
	if sz < off || (off == 0 && sz > TxTail) {
		off = sz - TxTail
		if off < 0 {
			off = 0
		}
		adjusted = true
	}
	if sz <= off {
		return nil, nil
	}
	nl, err := scanNL(ra, off, sz, true)
	if err != nil {
		return nil, err
	}
	if nl < 0 {
		return nil, nil // no complete line yet; streamd leaves its offset unchanged
	}
	newOff, start := nl+1, off
	if adjusted && off > 0 {
		first, err := scanNL(ra, off, newOff, false)
		if err != nil {
			return nil, err
		}
		start = first + 1
	}
	w.Off = newOff
	if adjusted {
		w.pendingReset = true
	}
	if newOff == start {
		return nil, nil
	}
	reset := w.pendingReset
	w.pendingReset = false
	return &TxRange{F: start, O: newOff, Reset: reset}, nil
}

// scanNL returns the offset of the last (backwards) or first newline in [from, to), -1 when there is none.
func scanNL(ra io.ReaderAt, from, to int64, backwards bool) (int64, error) {
	buf := make([]byte, txScanBlock)
	for lo, hi := from, to; hi > lo; {
		start, end := lo, hi
		if end-start > txScanBlock {
			if backwards {
				start = end - txScanBlock
			} else {
				end = start + txScanBlock
			}
		}
		b := buf[:end-start]
		if err := readFull(ra, b, start); err != nil {
			return -1, err
		}
		if backwards {
			if i := bytes.LastIndexByte(b, '\n'); i >= 0 {
				return start + int64(i), nil
			}
			hi = start
		} else {
			if i := bytes.IndexByte(b, '\n'); i >= 0 {
				return start + int64(i), nil
			}
			lo = end
		}
	}
	return -1, nil
}

func readFull(ra io.ReaderAt, b []byte, off int64) error {
	n, err := ra.ReadAt(b, off)
	if n == len(b) {
		return nil
	}
	if err == nil {
		err = io.ErrUnexpectedEOF
	}
	return err
}

type readerAt func(off, n int64) ([]byte, error)

func (r readerAt) ReadAt(p []byte, off int64) (int, error) {
	b, err := r(off, int64(len(p)))
	return copy(p, b), err
}

// TxPiece is one TX frame of a delta.
type TxPiece struct {
	F, O        int64
	Reset, More bool
	Bytes       []byte
}

// Pieces cuts a delta at any byte into pieces of <= 48 KiB; every piece but the last has more:true (spec §4.6).
func Pieces(d *TxDelta) []TxPiece {
	out := RangePieces(TxRange{F: d.F, O: d.O, Reset: d.Reset})
	for i := range out {
		out[i].Bytes = d.Chunk[out[i].F-d.F : out[i].O-d.F]
	}
	return out
}

// RangePieces is Pieces on file offsets: the pieces carry no bytes yet (the daemon reads each one when it sends it).
func RangePieces(r TxRange) []TxPiece {
	var out []TxPiece
	for f := r.F; f < r.O; f += crp.Piece {
		o := f + crp.Piece
		if o > r.O {
			o = r.O
		}
		out = append(out, TxPiece{F: f, O: o, Reset: f == r.F && r.Reset, More: o < r.O})
	}
	return out
}

// TxFrame builds the TX frame of a piece; gzip compresses the piece on its own (f/o stay uncompressed offsets).
func TxFrame(wid, uuid string, p TxPiece, gz bool) crp.Frame {
	enc := "none"
	blob := p.Bytes
	if gz {
		enc = "gzip"
		var b bytes.Buffer
		zw := gzip.NewWriter(&b)
		_, _ = zw.Write(p.Bytes)
		_ = zw.Close()
		blob = b.Bytes()
	}
	h := crp.Obj{"wid": wid, "u": uuid, "f": p.F, "o": p.O, "enc": enc}
	if p.Reset {
		h["reset"] = true
	}
	if p.More {
		h["more"] = true
	}
	return crp.NewFrame(crp.TX, h, append([]byte{}, blob...))
}

var (
	uuidRe   = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	nonAlnum = regexp.MustCompile(`[^a-zA-Z0-9]`)
)

// ValidUUID: streamd does not validate the uuid (`../` would pass); crd does, a conscious deviation (Q5).
func ValidUUID(u string) bool { return uuidRe.MatchString(u) }

// ResolveTranscript is streamd's resolve (streamd.py:26-39), done once per watch: the newest
// ~/.claude/projects/*/<uuid>.jsonl, else the encoded-cwd path (realpath of a missing path keeps the rest).
func ResolveTranscript(home, projects, cwd, uuid string) string {
	hits, _ := filepath.Glob(filepath.Join(projects, "*", uuid+".jsonl"))
	if len(hits) > 0 {
		sort.Slice(hits, func(i, j int) bool { return mtime(hits[i]) > mtime(hits[j]) })
		return hits[0]
	}
	p := cwd
	if p == "" {
		p = "~"
	}
	if p == "~" || strings.HasPrefix(p, "~/") {
		p = filepath.Join(home, strings.TrimPrefix(p, "~"))
	}
	if !filepath.IsAbs(p) {
		p = filepath.Join(home, p)
	}
	enc := nonAlnum.ReplaceAllString(realpathLike(p), "-")
	return filepath.Join(projects, enc, uuid+".jsonl")
}

func mtime(p string) int64 {
	st, err := os.Stat(p)
	if err != nil {
		return 0
	}
	return st.ModTime().UnixNano()
}

// realpathLike is Python's os.path.realpath on a possibly missing path: resolve the existing prefix, keep the rest.
func realpathLike(p string) string {
	p = filepath.Clean(p)
	rest := ""
	for {
		if r, err := filepath.EvalSymlinks(p); err == nil {
			return filepath.Join(r, rest)
		}
		parent := filepath.Dir(p)
		if parent == p {
			return filepath.Join(p, rest)
		}
		rest = filepath.Join(filepath.Base(p), rest)
		p = parent
	}
}
