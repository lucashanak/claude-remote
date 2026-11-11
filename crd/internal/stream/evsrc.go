package stream

import (
	"bytes"
	"errors"
	"io"
	"os"
	"strconv"
	"syscall"
)

// Constants shared with the Stage A watcher (NotificationService.kt; EvConstantsMatchKotlin keeps them equal).
const (
	MarkerMaxLines            = 1000 // MARKER_MAX_LINES
	MarkerKeepLines           = 200  // MARKER_KEEP_LINES
	EventsRotateMinutes       = 1440 // EVENTS_ROTATE_MIN_INTERVAL_MIN
	SeedLines                 = 25   // tail -q -n 25 (D-B0-13)
	skipWindow                = MarkerKeepLines + 64
	maxReadPerCheck     int64 = 8 << 20
	// EvLineMax: a source line longer than this never enters the ring or the backlog (dodatek 2 P1.4): its EV would
	// not fit the smallest maxFrame (64 KiB) a client may negotiate, and a frame over it is a poison item that every
	// resume would send again. Dropped lines are logged (ev-oversize, length only) and counted (ev_dropped).
	EvLineMax = 60 << 10
	// SeedTailBytes: seeding reads only this much of each source's end (review A-F10).
	SeedTailBytes int64 = 2 << 20
)

// oversize is the stand-in of a dropped line in the follower's window: it contains a newline, so it never equals a
// real line, and two dropped lines of the same length compare equal (the kept-tail skip still lines up).
func oversize(n int) []byte { return []byte("\ncrd-oversize " + strconv.Itoa(n)) }

func isOversize(l []byte) (int, bool) {
	if len(l) == 0 || l[0] != '\n' {
		return 0, false
	}
	n, _ := strconv.Atoi(string(l[len("\ncrd-oversize "):]))
	return n, true
}

// lineOf maps a source line to what the follower keeps of it.
func lineOf(l []byte) []byte {
	if len(l) > EvLineMax {
		return oversize(len(l))
	}
	return append([]byte{}, l...)
}

// EvSources is the seeding order (spec §4.4) with the jarvis erratum (dodatek R2: after forget, never rotated).
var EvSources = []string{"events", "notify", "legacy", "forget", "jarvis"}

// follower follows one ev source file by offset (no tail process, spec §4.7). off is always just after the last
// complete line read: a line is emitted only once its newline is there (dodatek R11).
type follower struct {
	src, path string
	f         *os.File
	dev, ino  uint64
	off       int64
	window    [][]byte          // the last skipWindow lines of the current inode (emitted or skipped as kept tail)
	pending   [][]byte          // the window of the inode that went away while no new file is there yet (review A-F9)
	skipping  bool              // inside a line longer than one read: everything up to its newline is dropped
	under     func(string) bool // test mode: Paths.UnderRoot, asked again at every use (review N-1); nil in production
	outside   bool              // the last check found the path outside the test root (logged once per streak)
	irregular bool              // the last open found no regular file of this uid there (logged once per streak)
	emit      func(src string, line []byte)
	drop      func(src string, n int)
	refuse    func(src string) // logs `ev source outside test root`
	odd       func(src string) // logs `ev source not a regular file`
}

var (
	errOutside    = errors.New("ev source outside test root")
	errNotRegular = errors.New("not a regular file of this user")
)

// srcUID is the owner every source and transcript must have: the daemon's own uid (a test sets another one to reach
// the branch of a file of another owner, which a one-user test cannot create).
var srcUID = uint32(os.Getuid())

// openRegular opens p for reading without ever blocking (review R-1): O_NONBLOCK makes the open of a FIFO or a device
// return at once, O_NOFOLLOW (unless follow) refuses a final link, and fstat keeps only a regular file owned by
// srcUID; anything else is closed unread (errNotRegular). Reads of a regular file ignore O_NONBLOCK.
func openRegular(p string, follow bool) (*os.File, error) {
	flags := os.O_RDONLY | syscall.O_NONBLOCK
	if !follow {
		flags |= syscall.O_NOFOLLOW
	}
	f, err := os.OpenFile(p, flags, 0)
	if err != nil {
		return nil, err
	}
	st, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, err
	}
	if !st.Mode().IsRegular() || st.Sys().(*syscall.Stat_t).Uid != srcUID {
		f.Close()
		return nil, errNotRegular
	}
	return f, nil
}

// allowed is the test mode's check of the source path at the moment of use (review N-1): a directory on the way that
// became a link out of the root after the start makes the source unreadable; nothing is read there and nothing written.
func (fl *follower) allowed() bool {
	if fl.under == nil || fl.under(fl.path) {
		fl.outside = false
		return true
	}
	if !fl.outside && fl.refuse != nil {
		fl.refuse(fl.src)
	}
	fl.outside = true
	return false
}

// open opens the source: never through a final link (every source path is fixed, review A-F17), never blocking, and
// only a regular file of this uid (review R-1: a FIFO at /tmp/claude-notify stopped the round, crd.off and the start).
// Another kind of file is logged once until the path holds something else, and tried again every round.
func (fl *follower) open() (*os.File, error) {
	if !fl.allowed() {
		return nil, errOutside
	}
	f, err := openRegular(fl.path, false)
	if errors.Is(err, errNotRegular) {
		if !fl.irregular && fl.odd != nil {
			fl.odd(fl.src)
		}
		fl.irregular = true
		return nil, err
	}
	fl.irregular = false
	return f, err
}

// deliver emits a line, or reports a dropped one.
func (fl *follower) deliver(l []byte) {
	if n, ok := isOversize(l); ok {
		if fl.drop != nil {
			fl.drop(fl.src, n)
		}
		return
	}
	fl.emit(fl.src, l)
}

func statIno(st os.FileInfo) (uint64, uint64) {
	s := st.Sys().(*syscall.Stat_t)
	return uint64(s.Dev), s.Ino
}

func (fl *follower) remember(line []byte) {
	fl.window = append(fl.window, line)
	if len(fl.window) > skipWindow {
		fl.window = fl.window[len(fl.window)-skipWindow:]
	}
}

// readLines reads complete lines of f from off up to EOF (at most maxReadPerCheck per call); returns them and the new
// offset. A read without any newline that fills maxReadPerCheck belongs to an oversize line: its bytes are passed
// over (skipping) and the line ends up as one oversize stand-in, so a line without newline never stalls the source.
func readLines(f *os.File, off int64, skipping bool) ([][]byte, int64, bool) {
	st, err := f.Stat()
	if err != nil || st.Size() <= off {
		return nil, off, skipping
	}
	n := st.Size() - off
	if n > maxReadPerCheck {
		n = maxReadPerCheck
	}
	buf := make([]byte, n)
	k, err := f.ReadAt(buf, off)
	if err != nil && err != io.EOF {
		return nil, off, skipping
	}
	buf = buf[:k]
	end := bytes.LastIndexByte(buf, '\n')
	if end < 0 {
		if int64(k) == maxReadPerCheck {
			return nil, off + int64(k), true
		}
		return nil, off, skipping
	}
	var lines [][]byte
	for i, l := range bytes.Split(buf[:end], []byte{'\n'}) {
		if i == 0 && skipping {
			lines = append(lines, oversize(-1))
			continue
		}
		lines = append(lines, lineOf(l))
	}
	return lines, off + int64(end) + 1, false
}

// lastLines returns the last n complete lines of data and the offset after the last complete line.
func lastLines(data []byte, n int) ([][]byte, int64) {
	end := bytes.LastIndexByte(data, '\n')
	if end < 0 {
		return nil, 0
	}
	all := bytes.Split(data[:end], []byte{'\n'})
	if len(all) > n {
		all = all[len(all)-n:]
	}
	out := make([][]byte, len(all))
	for i, l := range all {
		out[i] = lineOf(l)
	}
	return out, int64(end) + 1
}

// SkipKept is the length of the longest prefix of fresh that equals a contiguous run of window (dodatek Q1): the
// rotator's kept tail, wherever it sits in the window (lines appended between its tail and mv follow it).
func SkipKept(window, fresh [][]byte) int {
	best := 0
	for j := range window {
		k := 0
		for k < len(fresh) && j+k < len(window) && bytes.Equal(fresh[k], window[j+k]) {
			k++
		}
		if k > best {
			best = k
		}
	}
	return best
}

// seed reads the last SeedLines complete lines (spec §4.4) from the last SeedTailBytes of the file and positions
// the follower at the end of them. The lines it returns include oversize stand-ins (deliver drops them).
func (fl *follower) seed() [][]byte {
	f, err := fl.open()
	if err != nil {
		return nil
	}
	st, err := f.Stat()
	if err != nil {
		f.Close()
		return nil
	}
	base := st.Size() - SeedTailBytes
	if base < 0 {
		base = 0
	}
	data := make([]byte, st.Size()-base)
	k, err := f.ReadAt(data, base)
	if err != nil && err != io.EOF {
		f.Close()
		return nil
	}
	data = data[:k]
	fl.f = f
	fl.dev, fl.ino = statIno(st)
	if base > 0 { // the tail starts inside a line: that line is older than the seed and not ours to read
		i := bytes.IndexByte(data, '\n')
		if i < 0 {
			fl.off, fl.skipping = base+int64(k), true
			return nil
		}
		data, base = data[i+1:], base+int64(i)+1
	}
	win, off := lastLines(data, skipWindow)
	fl.off = base + off
	fl.window = win
	if len(win) > SeedLines {
		return win[len(win)-SeedLines:]
	}
	return win
}

// check reads what is new. On an inode change or a truncation it first reads the old descriptor to EOF, then
// reads the new file from its start and skips the kept tail (spec §4.7).
func (fl *follower) check() {
	if !fl.allowed() {
		return // the open descriptor stays as it is; the follower goes on once the path is back under the root
	}
	st, err := os.Stat(fl.path)
	var skip [][]byte
	if fl.f != nil {
		rotated := err != nil
		if err == nil {
			dev, ino := statIno(st)
			rotated = dev != fl.dev || ino != fl.ino
		}
		truncated := !rotated && st.Size() < fl.off
		if rotated || truncated {
			if rotated {
				lines, _, _ := readLines(fl.f, fl.off, fl.skipping)
				for _, l := range lines {
					fl.deliver(l)
					fl.remember(l)
				}
			}
			fl.f.Close()
			fl.f = nil
			fl.skipping = false
			skip = fl.window
			fl.window = nil
		}
	}
	if fl.f == nil {
		if skip == nil {
			skip = fl.pending
		}
		if err != nil {
			fl.pending = skip // a rotator with a gap (mv away, new file later): the kept tail is still skipped
			return
		}
		f, err := fl.open()
		if err != nil {
			fl.pending = skip
			return
		}
		fst, err := f.Stat()
		if err != nil {
			f.Close()
			fl.pending = skip
			return
		}
		fl.f, fl.pending = f, nil
		fl.dev, fl.ino = statIno(fst)
		fl.off = 0
		lines, off, sk := readLines(f, 0, false)
		k := SkipKept(skip, lines)
		for i, l := range lines {
			if i >= k {
				fl.deliver(l)
			}
			fl.remember(l)
		}
		fl.off, fl.skipping = off, sk
		return
	}
	lines, off, sk := readLines(fl.f, fl.off, fl.skipping)
	for _, l := range lines {
		fl.deliver(l)
		fl.remember(l)
	}
	fl.off, fl.skipping = off, sk
}

// countBlock is the read size of countLines and tailOf: their memory is one block, whatever the file size (review N-5).
const countBlock = 64 << 10

// countLines is `wc -l`: the number of newlines, read block by block.
func countLines(p string) int {
	f, err := openRegular(p, false)
	if err != nil {
		return 0
	}
	defer f.Close()
	buf := make([]byte, countBlock)
	n := 0
	for {
		k, err := f.Read(buf)
		n += bytes.Count(buf[:k], []byte{'\n'})
		if err != nil {
			return n
		}
	}
}

// tailOf is tailKeep(whole file, n) reading the file backwards from its end in blocks until the tail is complete:
// only the kept tail is held, never the whole file (review N-5).
func tailOf(p string, n int) ([]byte, error) {
	f, err := openRegular(p, false)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return nil, err
	}
	var blocks [][]byte // from the end backwards
	nl, total := 0, 0
	for pos := st.Size(); pos > 0; {
		k := int64(countBlock)
		if k > pos {
			k = pos
		}
		pos -= k
		b := make([]byte, k)
		if err := readFull(f, b, pos); err != nil {
			return nil, err
		}
		if len(blocks) == 0 && b[len(b)-1] == '\n' {
			nl-- // the newline that ends the last line does not start a line (tailKeep)
		}
		blocks = append(blocks, b)
		total += len(b)
		if nl += bytes.Count(b, []byte{'\n'}); nl >= n {
			break // the newline in front of the n-th last line is read
		}
	}
	data := make([]byte, 0, total)
	for i := len(blocks) - 1; i >= 0; i-- {
		data = append(data, blocks[i]...)
	}
	return tailKeep(data, n), nil
}
