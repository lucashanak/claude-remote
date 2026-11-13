package daemon

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"os"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

// Inotify is a small wrapper over inotify(7) (stdlib syscall; no x/sys needed in B1a).
type Inotify struct {
	f  *os.File
	mu sync.Mutex
	wd map[int32]string
}

// Watch masks used by crd.
const (
	InDirChanges = syscall.IN_CREATE | syscall.IN_DELETE | syscall.IN_MOVED_TO | syscall.IN_MOVED_FROM |
		syscall.IN_CLOSE_WRITE | syscall.IN_MODIFY | syscall.IN_ATTRIB | syscall.IN_DELETE_SELF | syscall.IN_MOVE_SELF
)

// NewInotify opens a non-blocking inotify instance served by the runtime poller.
func NewInotify() (*Inotify, error) {
	fd, err := syscall.InotifyInit1(syscall.IN_CLOEXEC | syscall.IN_NONBLOCK)
	if err != nil {
		return nil, err
	}
	return &Inotify{f: os.NewFile(uintptr(fd), "inotify"), wd: map[int32]string{}}, nil
}

// AddDir watches a directory; a missing directory is an error the caller retries later.
func (n *Inotify) AddDir(dir string) error {
	w, err := syscall.InotifyAddWatch(int(n.f.Fd()), dir, InDirChanges)
	if err != nil {
		return err
	}
	n.mu.Lock()
	n.wd[int32(w)] = dir
	n.mu.Unlock()
	return nil
}

// Watching reports whether dir has a watch.
func (n *Inotify) Watching(dir string) bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	for _, d := range n.wd {
		if d == dir {
			return true
		}
	}
	return false
}

// Run calls fn(dir, name) for every event until the instance is closed.
func (n *Inotify) Run(fn func(dir, name string)) {
	buf := make([]byte, 64*1024)
	for {
		k, err := n.f.Read(buf)
		if err != nil {
			return
		}
		for off := 0; off+syscall.SizeofInotifyEvent <= k; {
			ev := (*syscall.InotifyEvent)(unsafe.Pointer(&buf[off]))
			name := ""
			if ev.Len > 0 {
				raw := buf[off+syscall.SizeofInotifyEvent : off+syscall.SizeofInotifyEvent+int(ev.Len)]
				name = string(bytes.TrimRight(raw, "\x00"))
			}
			n.mu.Lock()
			dir := n.wd[ev.Wd]
			if ev.Mask&syscall.IN_IGNORED != 0 {
				delete(n.wd, ev.Wd)
			}
			n.mu.Unlock()
			fn(dir, name)
			off += syscall.SizeofInotifyEvent + int(ev.Len)
		}
	}
}

// Close stops Run.
func (n *Inotify) Close() error { return n.f.Close() }

// sdNotify sends one datagram to NOTIFY_SOCKET (spec §7.1: written by hand). Abstract sockets start with '@'.
func sdNotify(state string) error {
	addr := os.Getenv("NOTIFY_SOCKET")
	if addr == "" {
		return nil
	}
	if strings.HasPrefix(addr, "@") {
		addr = "\x00" + addr[1:]
	}
	c, err := net.DialUnix("unixgram", nil, &net.UnixAddr{Name: addr, Net: "unixgram"})
	if err != nil {
		return err
	}
	defer c.Close()
	_, err = c.Write([]byte(state))
	return err
}

// watchdogInterval is half of WATCHDOG_USEC when it is meant for this process, else 0.
func watchdogInterval() time.Duration {
	us := os.Getenv("WATCHDOG_USEC")
	if us == "" {
		return 0
	}
	if p := os.Getenv("WATCHDOG_PID"); p != "" && p != fmt.Sprint(os.Getpid()) {
		return 0
	}
	var n int64
	if _, err := fmt.Sscan(us, &n); err != nil || n <= 0 {
		return 0
	}
	return time.Duration(n) * time.Microsecond / 2
}

// peerUID reads SO_PEERCRED of a unix connection.
func peerUID(c *net.UnixConn) (int, error) {
	rc, err := c.SyscallConn()
	if err != nil {
		return -1, err
	}
	uid := -1
	var gerr error
	err = rc.Control(func(fd uintptr) {
		var cred *syscall.Ucred
		cred, gerr = syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
		if gerr == nil {
			uid = int(cred.Uid)
		}
	})
	if err != nil {
		return -1, err
	}
	return uid, gerr
}

// flockNB takes an exclusive non-blocking flock on path (created 0600).
func flockNB(path string) (*os.File, error) {
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE|syscall.O_NOFOLLOW, 0o600)
	if err != nil {
		return nil, err
	}
	if err := syscall.Flock(int(f.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		f.Close()
		return nil, err
	}
	return f, nil
}

// FlockNB is flockNB for the stream package (rotate.lock).
func FlockNB(path string) (*os.File, error) { return flockNB(path) }

func randHex(n int) string {
	b := make([]byte, (n+1)/2)
	if _, err := rand.Read(b); err != nil {
		binary.BigEndian.PutUint64(b, uint64(time.Now().UnixNano()))
	}
	return hex.EncodeToString(b)[:n]
}

// hostFacts: host.id = first 8 hex of sha256(/etc/machine-id), host.boot = first 8 hex of the boot id
// (spec §7.4; formats decided here, vector values are examples: dodatek A9).
func hostFacts() (id, boot string) {
	if b, err := os.ReadFile("/etc/machine-id"); err == nil {
		s := sha256.Sum256(bytes.TrimSpace(b))
		id = hex.EncodeToString(s[:4])
	}
	if b, err := os.ReadFile("/proc/sys/kernel/random/boot_id"); err == nil {
		boot = strings.ReplaceAll(strings.TrimSpace(string(b)), "-", "")
		if len(boot) > 8 {
			boot = boot[:8]
		}
	}
	return
}

func rssKB() int64 {
	b, err := os.ReadFile("/proc/self/status")
	if err != nil {
		return -1
	}
	for _, l := range strings.Split(string(b), "\n") {
		if strings.HasPrefix(l, "VmRSS:") {
			var n int64
			fmt.Sscan(strings.TrimSpace(strings.TrimSuffix(strings.TrimPrefix(l, "VmRSS:"), "kB")), &n)
			return n
		}
	}
	return -1
}

// mkdirPrivate makes dir (and parents) and forces 0700 on dir itself.
func mkdirPrivate(dir string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	st, err := os.Lstat(dir)
	if err != nil {
		return err
	}
	if !st.IsDir() {
		return errors.New(dir + " is not a directory")
	}
	return os.Chmod(dir, 0o700)
}
