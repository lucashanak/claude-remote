package testenv

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"syscall"
)

// canary is a read-only fingerprint of the live box taken before and after a test binary (D-H7): never a
// connect(), never a tmux command, only stat/readlink/read of /proc. Paths come from the real home in /etc/passwd,
// not from the redirected HOME.
type canary struct {
	ci          bool
	sock, dir   fileID
	serverPid   int
	serverStart string
	children    map[int]string
	absent      map[string]bool // paths that must not exist (crd never touched the live tree)
	rotTemps    []string
	legacy      fileID
	events      fileID
	notify      fileID
	rotated     fileID
	eventsLines int
	notifyLines int
}

type fileID struct {
	ok    bool
	ino   uint64
	size  int64
	mtime int64
}

func statID(p string) fileID {
	var st syscall.Stat_t
	if err := syscall.Lstat(p, &st); err != nil {
		return fileID{}
	}
	return fileID{ok: true, ino: st.Ino, size: st.Size, mtime: st.Mtim.Sec*1e9 + st.Mtim.Nsec}
}

func liveDir() string { return filepath.Join(realTmp(), "tmux-"+strconv.Itoa(os.Getuid())) }

func takeCanary() *canary {
	c := &canary{ci: os.Getenv("CI") == "true", children: map[int]string{}, absent: map[string]bool{}}
	c.dir = statID(liveDir())
	c.sock = statID(filepath.Join(liveDir(), "default"))
	if c.sock.ok {
		c.serverPid = listenerPid(c.sock.ino, filepath.Join(liveDir(), "default"))
		if c.serverPid > 0 {
			c.serverStart = startTime(c.serverPid)
			c.children = childrenOf(c.serverPid)
		}
	}
	cr := filepath.Join(realHome, ".claude-remote")
	cache := filepath.Join(realHome, ".cache", "claude-remote")
	for _, p := range []string{filepath.Join(cr, "crd"), filepath.Join(cache, "rotate.lock"), filepath.Join(cr, "crd.off")} {
		_, err := os.Lstat(p)
		c.absent[p] = os.IsNotExist(err)
	}
	for _, d := range []string{cr, cache} {
		ents, _ := os.ReadDir(d)
		for _, e := range ents {
			if strings.Contains(e.Name(), ".rot.crd.") {
				c.rotTemps = append(c.rotTemps, filepath.Join(d, e.Name()))
			}
		}
	}
	c.legacy = statID("/tmp/claude-notify")
	c.events = statID(filepath.Join(cache, "events"))
	c.notify = statID(filepath.Join(cache, "notify"))
	c.rotated = statID(filepath.Join(cache, "events.rotated"))
	c.eventsLines = countLines(filepath.Join(cache, "events"))
	c.notifyLines = countLines(filepath.Join(cache, "notify"))
	return c
}

func (b *canary) compare(a *canary) []string {
	var d []string
	if !b.sock.ok {
		if !b.ci {
			d = append(d, "live tmux socket "+filepath.Join(liveDir(), "default")+" missing at start: cannot guard (outside CI this is a failure)")
		}
	} else {
		if !a.sock.ok || a.sock.ino != b.sock.ino {
			d = append(d, "live tmux socket inode changed or vanished (check the sockheal log; rerun once)")
		}
		if !a.dir.ok || a.dir.ino != b.dir.ino {
			d = append(d, "live socket directory "+liveDir()+" inode changed or vanished")
		}
		if b.serverPid > 0 && (a.serverPid != b.serverPid || a.serverStart != b.serverStart) {
			d = append(d, fmt.Sprintf("live tmux server identity changed: pid %d->%d", b.serverPid, a.serverPid))
		}
		var gone []string
		for pid, st := range b.children {
			if a.children[pid] != st {
				gone = append(gone, strconv.Itoa(pid))
			}
		}
		if len(gone) > 0 {
			sort.Strings(gone)
			d = append(d, "pane processes of the live server gone: "+strings.Join(gone, ",")+" (the user may have closed a session; check before blaming the test)")
		}
	}
	for p, was := range b.absent {
		if !a.absent[p] && was {
			d = append(d, p+" appeared (crd touched the live tree)")
		}
		if !was {
			d = append(d, p+" existed at start (cannot guard it)")
		}
	}
	if len(a.rotTemps) > len(b.rotTemps) {
		d = append(d, "crd rotation temp files appeared: "+strings.Join(a.rotTemps, ","))
	}
	if b.legacy != a.legacy {
		d = append(d, "/tmp/claude-notify changed")
	}
	if b.events.ok && (a.events.ino != b.events.ino) && !(a.rotated.mtime != b.rotated.mtime && a.eventsLines >= 200) {
		d = append(d, "live events file replaced without an app rotation stamp")
	}
	if b.notify.ok && a.notify.ino != b.notify.ino && a.notifyLines < 200 {
		d = append(d, "live notify file replaced and shorter than 200 lines")
	}
	return d
}

func countLines(p string) int {
	b, err := os.ReadFile(p)
	if err != nil {
		return 0
	}
	return bytes.Count(b, []byte{'\n'})
}

// listenerPid maps the live socket's listener to its pid: inode from /proc/net/unix, then the process whose fd
// table holds socket:[inode] (read only).
func listenerPid(_ uint64, path string) int {
	f, err := os.Open("/proc/net/unix")
	if err != nil {
		return 0
	}
	defer f.Close()
	var inode string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		fl := strings.Fields(sc.Text())
		if len(fl) >= 8 && fl[7] == path {
			flags, _ := strconv.ParseUint(fl[3], 16, 32)
			if flags&0x10000 != 0 {
				inode = fl[6]
			}
		}
	}
	if inode == "" {
		return 0
	}
	want := "socket:[" + inode + "]"
	ents, _ := os.ReadDir("/proc")
	for _, e := range ents {
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		fds, err := os.ReadDir(fmt.Sprintf("/proc/%d/fd", pid))
		if err != nil {
			continue
		}
		for _, fd := range fds {
			if l, err := os.Readlink(fmt.Sprintf("/proc/%d/fd/%s", pid, fd.Name())); err == nil && l == want {
				return pid
			}
		}
	}
	return 0
}

func startTime(pid int) string {
	st, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return ""
	}
	i := bytes.LastIndexByte(st, ')')
	if i < 0 {
		return ""
	}
	f := strings.Fields(string(st[i+1:]))
	if len(f) < 20 {
		return ""
	}
	return f[19] // field 22 of stat(5): starttime
}

func childrenOf(ppid int) map[int]string {
	out := map[int]string{}
	ents, _ := os.ReadDir("/proc")
	for _, e := range ents {
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		st, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
		if err != nil {
			continue
		}
		i := bytes.LastIndexByte(st, ')')
		if i < 0 {
			continue
		}
		f := strings.Fields(string(st[i+1:]))
		if len(f) > 19 && f[1] == strconv.Itoa(ppid) {
			out[pid] = f[19]
		}
	}
	return out
}
