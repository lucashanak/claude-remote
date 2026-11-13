package daemon

import (
	"bytes"
	"fmt"
	"os"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Log size limits (spec §7.6): cut to the last 16 MB at 20 MB, on a line boundary.
var (
	LogMax  int64 = 20 << 20
	LogKeep int64 = 16 << 20
)

// Logger writes key=value lines to crd.log. It never logs content bytes (terminal, input, rpc stdout, ev lines,
// transcript bytes): callers pass names, counts and codes only.
type Logger struct {
	mu   sync.Mutex
	path string
	f    *os.File
	size int64
	// under is Paths.UnderRoot in test mode (nil in production): the cut checks its paths again right before it reads,
	// creates, renames and reopens them, so a log directory that became a link out of the root is never written
	// (review N-1); the log then goes on into the open file.
	under       func(string) bool
	afterRename func() // test seam: between the rename of a cut and the reopen
}

// OpenLog opens (creating 0600) the log at path.
func OpenLog(path string) (*Logger, error) {
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_APPEND|os.O_CREATE|syscall.O_NOFOLLOW, 0o600)
	if err != nil {
		return nil, err
	}
	st, _ := f.Stat()
	return &Logger{path: path, f: f, size: st.Size()}, nil
}

// Log writes `ts=… ev=<ev> k=v …`.
func (l *Logger) Log(ev string, kv ...any) {
	if l == nil {
		return
	}
	var b strings.Builder
	b.WriteString("ts=")
	b.WriteString(time.Now().UTC().Format("2006-01-02T15:04:05.000Z"))
	b.WriteString(" ev=")
	b.WriteString(ev)
	for i := 0; i+1 < len(kv); i += 2 {
		b.WriteByte(' ')
		b.WriteString(fmt.Sprint(kv[i]))
		b.WriteByte('=')
		b.WriteString(logValue(fmt.Sprint(kv[i+1])))
	}
	b.WriteByte('\n')
	l.mu.Lock()
	defer l.mu.Unlock()
	n, _ := l.f.WriteString(b.String())
	l.size += int64(n)
	if l.size > LogMax {
		l.cut()
	}
}

// Line writes a preformatted line (the `crd stats …` line).
func (l *Logger) Line(s string) {
	if l == nil {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	n, _ := l.f.WriteString(s + "\n")
	l.size += int64(n)
	if l.size > LogMax {
		l.cut()
	}
}

func logValue(s string) string {
	if s == "" || strings.ContainsAny(s, " =\"\n\t") {
		return fmt.Sprintf("%q", s)
	}
	return s
}

func (l *Logger) allowed(ps ...string) bool {
	for _, p := range ps {
		if l.under != nil && !l.under(p) {
			return false
		}
	}
	return true
}

// cut keeps the last LogKeep bytes starting after a newline, via temp + rename.
func (l *Logger) cut() {
	tmp := l.path + ".cut"
	if !l.allowed(l.path, tmp) {
		return
	}
	b, err := os.ReadFile(l.path)
	if err != nil {
		return
	}
	kept := CutLog(b, LogKeep)
	if !l.allowed(l.path, tmp) {
		return
	}
	_ = os.Remove(tmp)
	tf, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return
	}
	_, werr := tf.Write(kept)
	if cerr := tf.Close(); werr != nil || cerr != nil {
		_ = os.Remove(tmp)
		return
	}
	if !l.allowed(l.path, tmp) {
		return // the temp file stays where it is: removing it by path could hit the link's target
	}
	if err := os.Rename(tmp, l.path); err != nil {
		_ = os.Remove(tmp)
		return
	}
	if l.afterRename != nil {
		l.afterRename()
	}
	if !l.allowed(l.path) {
		return
	}
	// open the new file before closing the old one: a failed reopen keeps logging to the old inode instead of
	// going silent until the next start (review K-F12)
	if f, err := os.OpenFile(l.path, os.O_WRONLY|os.O_APPEND|syscall.O_NOFOLLOW, 0o600); err == nil {
		l.f.Close()
		l.f = f
		l.size = int64(len(kept))
	}
}

// CutLog returns the tail of b of at most keep bytes that starts at a line boundary.
func CutLog(b []byte, keep int64) []byte {
	if int64(len(b)) <= keep {
		return b
	}
	t := b[int64(len(b))-keep:]
	if i := bytes.IndexByte(t, '\n'); i >= 0 {
		return t[i+1:]
	}
	return nil
}

// Close closes the log.
func (l *Logger) Close() {
	if l != nil {
		l.f.Close()
	}
}
