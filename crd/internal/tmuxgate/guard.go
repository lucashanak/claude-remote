package tmuxgate

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"time"
)

// acceptCon is __SO_ACCEPTCON in /proc/net/unix Flags: a listening socket.
const acceptCon = 0x10000

// Listeners counts the listening sockets bound to exactly path in /proc/net/unix and returns the inode of the
// last one (Stage A's rule: count listeners by path, which is what catches the split brain of 2026-11-02).
func Listeners(path string) (int, string, error) {
	f, err := os.Open("/proc/net/unix")
	if err != nil {
		return 0, "", err
	}
	defer f.Close()
	return listenersIn(f, path)
}

func listenersIn(r io.Reader, path string) (int, string, error) {
	n, inode := 0, ""
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64<<10), 1<<20)
	for sc.Scan() {
		fl, ino, p, ok := unixLine(sc.Text())
		if !ok || p != path {
			continue
		}
		flags, err := strconv.ParseUint(fl, 16, 32)
		if err == nil && flags&acceptCon != 0 {
			n++
			inode = ino
		}
	}
	return n, inode, sc.Err()
}

// unixLine splits a /proc/net/unix line into Flags, Inode and Path: seven fields, then the path as it is (it may
// contain spaces, review A-F16).
func unixLine(l string) (flags, inode, path string, ok bool) {
	var f [7]string
	rest := l
	for i := range f {
		rest = strings.TrimLeft(rest, " ")
		j := strings.IndexByte(rest, ' ')
		if j < 0 {
			return "", "", "", false
		}
		f[i], rest = rest[:j], rest[j+1:]
	}
	return f[3], f[6], rest, true
}

// ErrSocket is the guard's refusal (E_TMUX_SOCKET): nothing may be run.
var ErrSocket = errors.New("E_TMUX_SOCKET")

// Guard is spec §6.3: the path exists, is a socket, has exactly one listener and accepts a connect(). It never
// heals: no signal, chmod, mkdir or sockheal. It returns the listener inode.
func Guard(path string) (string, error) {
	st, err := os.Lstat(path)
	if err != nil || st.Mode()&os.ModeSocket == 0 {
		return "", fmt.Errorf("%w: %s is missing or not a socket", ErrSocket, path)
	}
	n, inode, err := Listeners(path)
	if err != nil || n != 1 {
		return "", fmt.Errorf("%w: %d listeners on %s", ErrSocket, n, path)
	}
	c, err := net.DialTimeout("unix", path, time.Second)
	if err != nil {
		return "", fmt.Errorf("%w: connect: %v", ErrSocket, err)
	}
	c.Close()
	return inode, nil
}

// StartTime is field 22 of /proc/<pid>/stat.
func StartTime(pid int64) string {
	b, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return ""
	}
	i := bytes.LastIndexByte(b, ')')
	if i < 0 {
		return ""
	}
	f := strings.Fields(string(b[i+1:]))
	if len(f) < 20 {
		return ""
	}
	return f[19]
}
