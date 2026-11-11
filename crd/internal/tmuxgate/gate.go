// Package tmuxgate is the only way crd runs tmux (spec §6.5) and the socket guard of §6.3. Every call is an argv
// (never a shell string), starts with exactly `-N -S <guarded path>`, names an allowed verb with allowed flags,
// and has a hard 2 s timeout. crdlint fails CI on any tmux literal or creating/killing verb outside this file.
package tmuxgate

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"time"
)

// CallTimeout is the hard bound of one call (attach-session is a long-lived child, not a call; B3).
const CallTimeout = 2 * time.Second

// denied are never accepted in any argv position (the deny list the CI gate exempts).
var denied = []string{"new-session", "start-server", "kill-server", "kill-session", "set-option", "resize-window",
	"window-size", "-A", "-g"}

var (
	bufRe    = regexp.MustCompile(`^crp-in-[0-9]+$`)
	ttyRe    = regexp.MustCompile(`^/dev/pts/[0-9]+$`)
	sizeFlag = map[string]bool{"ignore-size": true, "!ignore-size": true}
)

// Check validates a full argv after the binary name (spec §6.5).
func Check(sock string, argv []string) error {
	if len(argv) < 4 || argv[0] != "-N" || argv[1] != "-S" || argv[2] != sock || sock == "" {
		return errors.New("global flags must be exactly -N -S <guarded path>")
	}
	for _, a := range argv {
		for _, d := range denied {
			if a == d {
				return fmt.Errorf("denied word %q", a)
			}
		}
	}
	verb, args := argv[3], argv[4:]
	for _, a := range args {
		if strings.Contains(a, "#(") {
			return errors.New("format with #( (shell command)")
		}
	}
	flags, rest, err := parseFlags(args, map[string]bool{"-F": true, "-t": true, "-b": true, "-S": true, "-E": true, "-f": true})
	if err != nil {
		return err
	}
	allow := func(ok ...string) error {
		set := map[string]bool{}
		for _, o := range ok {
			set[o] = true
		}
		for k := range flags {
			if !set[k] {
				return fmt.Errorf("%s: flag %s not allowed", verb, k)
			}
		}
		return nil
	}
	target := func(need bool, suffixColon bool) error {
		t, ok := flags["-t"]
		if !ok {
			if need {
				return fmt.Errorf("%s needs -t", verb)
			}
			return nil
		}
		if !strings.HasPrefix(t, "=") || len(t) < 2 || (suffixColon && !strings.HasSuffix(t, ":")) {
			return fmt.Errorf("%s: target %q must be =<name>%s", verb, t, map[bool]string{true: ":", false: ""}[suffixColon])
		}
		return nil
	}
	noRest := func() error {
		if len(rest) > 0 {
			return fmt.Errorf("%s: unexpected argument %q", verb, rest[0])
		}
		return nil
	}
	switch verb {
	case "list-sessions":
		return first(allow("-F"), noRest())
	case "list-panes":
		return first(allow("-a", "-s", "-F", "-t"), target(false, false), noRest())
	case "list-clients":
		return first(allow("-F", "-t"), target(false, false), noRest())
	case "has-session":
		return first(allow("-t"), target(true, false), noRest())
	case "display-message":
		if _, p := flags["-p"]; !p || len(rest) != 1 {
			return errors.New("display-message only as -p -t =<name>: <format>")
		}
		return first(allow("-p", "-t"), target(true, true))
	case "show-environment":
		if len(rest) > 1 {
			return errors.New("show-environment: one variable at most")
		}
		return first(allow("-t", "-s"), target(true, false))
	case "capture-pane":
		return first(allow("-p", "-e", "-J", "-t", "-S", "-E"), target(true, true), noRest())
	case "attach-session":
		if !sizeFlag[flags["-f"]] {
			return errors.New("attach-session only as -t =<name> -f <ignore-size|!ignore-size>")
		}
		return first(allow("-t", "-f"), target(true, false), noRest())
	case "refresh-client":
		if !ttyRe.MatchString(flags["-t"]) {
			return errors.New("refresh-client only -t <own client tty>")
		}
		if f, ok := flags["-f"]; ok && !sizeFlag[f] {
			return errors.New("refresh-client -f only ignore-size|!ignore-size")
		}
		return first(allow("-t", "-f"), noRest())
	case "send-keys":
		return first(allow("-t", "-l"), target(true, true))
	case "load-buffer":
		if !bufRe.MatchString(flags["-b"]) || len(rest) != 1 || rest[0] != "-" {
			return errors.New("load-buffer only -b crp-in-<n> -")
		}
		return allow("-b")
	case "paste-buffer":
		if !bufRe.MatchString(flags["-b"]) {
			return errors.New("paste-buffer only with -b crp-in-<n>")
		}
		return first(allow("-b", "-t", "-p", "-d"), target(true, true), noRest())
	case "delete-buffer":
		if !bufRe.MatchString(flags["-b"]) {
			return errors.New("delete-buffer only with -b crp-in-<n>")
		}
		return first(allow("-b"), noRest())
	}
	return fmt.Errorf("verb %q not allowed", verb)
}

func first(errs ...error) error {
	for _, e := range errs {
		if e != nil {
			return e
		}
	}
	return nil
}

// parseFlags splits leading flags (with values for the given ones) from the remaining arguments.
func parseFlags(args []string, withValue map[string]bool) (map[string]string, []string, error) {
	flags := map[string]string{}
	i := 0
	for i < len(args) && strings.HasPrefix(args[i], "-") && len(args[i]) == 2 {
		f := args[i]
		if _, dup := flags[f]; dup {
			return nil, nil, fmt.Errorf("flag %s twice", f)
		}
		if withValue[f] {
			if i+1 >= len(args) {
				return nil, nil, fmt.Errorf("flag %s without value", f)
			}
			flags[f] = args[i+1]
			i += 2
			continue
		}
		flags[f] = ""
		i++
	}
	return flags, args[i:], nil
}

// Gate runs tmux for one guarded socket. Allowed is the second, independent path check of D-H3 (test mode: the
// socket must lie under the test root).
type Gate struct {
	Sock      string
	Allowed   func(path string) bool
	Calls     atomic.Int64
	versionOK atomic.Bool
}

// MinTmux is the oldest tmux the gate runs: -N (no server start) came with tmux 3.2.
var MinTmux = [2]int{3, 2}

var versionRe = regexp.MustCompile(`^tmux (?:next-)?([0-9]+)\.([0-9]+)`)

// checkVersion runs `tmux -V` (prints the version, never connects to a server) until one answer is new enough; an
// older tmux is a clear error instead of a failure of every call (review A-F16).
func (g *Gate) checkVersion(bin string) error {
	if g.versionOK.Load() {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), CallTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, bin, "-V")
	cmd.Env = childEnv()
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
	out, err := cmd.Output()
	if err != nil {
		return fmt.Errorf("tmux -V: %w", err)
	}
	m := versionRe.FindStringSubmatch(strings.TrimSpace(string(out)))
	if m == nil {
		return fmt.Errorf("tmux -V: unknown version %q", strings.TrimSpace(string(out)))
	}
	major, _ := strconv.Atoi(m[1])
	minor, _ := strconv.Atoi(m[2])
	if major < MinTmux[0] || (major == MinTmux[0] && minor < MinTmux[1]) {
		return fmt.Errorf("tmux %d.%d is older than %d.%d (crd needs -N)", major, minor, MinTmux[0], MinTmux[1])
	}
	g.versionOK.Store(true)
	return nil
}

// binary resolves tmux through the daemon's PATH (the unit's PATH in production, dodatek T4/R12).
func binary() (string, error) { return exec.LookPath("tmux") }

// Run executes one checked call.
func (g *Gate) Run(args ...string) ([]byte, error) {
	argv := append([]string{"-N", "-S", g.Sock}, args...)
	if err := Check(g.Sock, argv); err != nil {
		return nil, err
	}
	if g.Allowed != nil && !g.Allowed(g.Sock) {
		return nil, errors.New("guarded socket outside the allowed root")
	}
	bin, err := binary()
	if err != nil {
		return nil, err
	}
	if err := g.checkVersion(bin); err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), CallTimeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, bin, argv...)
	cmd.Env = childEnv()
	// a SIGKILLed crd leaves no tmux client behind (spec §8.4.4)
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGKILL}
	g.Calls.Add(1)
	return cmd.Output()
}

// childEnv is the daemon's environment without TMUX/TMUX_PANE (spec §6.3; crd removed them at start already).
func childEnv() []string {
	var out []string
	for _, kv := range os.Environ() {
		if strings.HasPrefix(kv, "TMUX=") || strings.HasPrefix(kv, "TMUX_PANE=") {
			continue
		}
		out = append(out, kv)
	}
	return out
}
