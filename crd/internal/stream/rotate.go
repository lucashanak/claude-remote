package stream

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/daemon"
)

// tailKeep is `tail -n n`: the last n lines, a final line without newline counting as one.
func tailKeep(data []byte, n int) []byte {
	i := len(data)
	if i > 0 && data[i-1] == '\n' {
		i--
	}
	for c := 0; i > 0; i-- {
		if data[i-1] == '\n' {
			c++
			if c == n {
				break
			}
		}
	}
	return data[i:]
}

// rotate applies the Stage A rule shared with older watchers (spec §4.7, NotificationService.kt
// buildNotifyWatcherCommand): over MarkerMaxLines lines keep MarkerKeepLines; events only when events.rotated is
// older than 24 h (then touched), notify at most once per 24 h by an in-memory timer. Under flock -n rotate.lock
// (skip the round when held), through a unique temp file <file>.rot.crd.<pid> (0600) and rename. Never legacy,
// forget or jarvis; never under crd.off. In test mode every path is checked against the root again right before it
// is read, created, renamed or locked (review N-1); a temp file left behind by a refused rename is cleaned at the
// next start.
func (e *evProvider) rotate(fl *follower) {
	if e.isOff() || (fl.src != "events" && fl.src != "notify") {
		return
	}
	now := time.Now()
	if fl.src == "notify" && !e.lastNotifyRot.IsZero() && now.Sub(e.lastNotifyRot) < 24*time.Hour {
		return
	}
	if !e.under(fl.src, fl.path) {
		return
	}
	if n := countLines(fl.path); n <= MarkerMaxLines {
		return // a round that rotates nothing does not start the notify timer (review A-F16)
	}
	if fl.src == "events" {
		if st, err := os.Stat(e.p.Rotated); err == nil && now.Sub(st.ModTime()) < EventsRotateMinutes*time.Minute {
			return
		}
	}
	if !e.under(fl.src, e.p.RotLock) {
		return
	}
	lk, err := daemon.FlockNB(e.p.RotLock)
	if err != nil {
		e.log.Log("rotate-skip", "src", fl.src, "why", "lock")
		return
	}
	defer lk.Close()
	fl.check() // everything up to now is emitted and in the window
	tmp := fl.path + ".rot.crd." + strconv.Itoa(os.Getpid())
	if !e.under(fl.src, fl.path, tmp) {
		return
	}
	kept, err := tailOf(fl.path, MarkerKeepLines)
	if err != nil {
		return
	}
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return
	}
	_, werr := f.Write(kept)
	cerr := f.Close()
	if werr != nil || cerr != nil {
		_ = os.Remove(tmp)
		return
	}
	if e.beforeRename != nil {
		e.beforeRename(fl.src)
	}
	if !e.under(fl.src, fl.path, tmp) {
		return
	}
	if err := os.Rename(tmp, fl.path); err != nil {
		_ = os.Remove(tmp)
		return
	}
	if fl.src == "notify" {
		e.lastNotifyRot = now
	}
	if !e.under(fl.src, fl.path, e.p.Rotated) {
		return
	}
	_ = os.Chmod(fl.path, 0o600)
	if fl.src == "events" {
		// O_NONBLOCK: a FIFO there without a reader fails at once instead of stopping the round (review R-1)
		if tf, err := os.OpenFile(e.p.Rotated, os.O_WRONLY|os.O_CREATE|syscall.O_NOFOLLOW|syscall.O_NONBLOCK, 0o600); err == nil {
			tf.Close()
		}
		_ = os.Chtimes(e.p.Rotated, now, now)
	}
	// Lines appended to the old inode between the read above and the rename exist only there.
	if fl.f != nil {
		lines, _, _ := readLines(fl.f, fl.off, fl.skipping)
		for _, l := range lines {
			fl.deliver(l)
		}
		fl.f.Close()
		fl.f = nil
		fl.skipping = false
	}
	// Continue at the size of the kept tail (its last complete line), so a line appended after the rename is read.
	if nf, err := fl.open(); err == nil {
		if st, err := nf.Stat(); err == nil {
			fl.f = nf
			fl.dev, fl.ino = statIno(st)
			fl.window, fl.off = lastLines(kept, skipWindow)
		} else {
			nf.Close()
		}
	}
	e.log.Log("rotate", "src", fl.src, "lines", MarkerKeepLines)
}

// under is Paths.UnderRoot for each of ps, logged when one is outside (always true in production).
func (e *evProvider) under(src string, ps ...string) bool {
	for _, p := range ps {
		if !e.p.UnderRoot(p) {
			e.log.Log("ev-outside-root", "src", src, "why", errOutside.Error())
			return false
		}
	}
	return true
}

// cleanRotTemps removes this daemon's leftover temp files of an interrupted rotation (SIGKILL between create and
// rename): `<events|notify>.rot.crd.<pid>`, regular files only. A leftover with the current pid would otherwise make
// every rotation fail its O_EXCL create (review A-F16). Only one crd runs per home (the flock), so none is live.
func (e *evProvider) cleanRotTemps() {
	for _, p := range []string{e.p.Events, e.p.Notify} {
		ms, _ := filepath.Glob(p + ".rot.crd.*")
		for _, m := range ms {
			if _, err := strconv.Atoi(strings.TrimPrefix(m, p+".rot.crd.")); err != nil {
				continue
			}
			if st, err := os.Lstat(m); err == nil && st.Mode().IsRegular() && e.p.UnderRoot(m) {
				_ = os.Remove(m)
				e.log.Log("rotate-temp-removed", "src", filepath.Base(p))
			}
		}
	}
}
