// Package cli dispatches the crd modes (spec §7.1). Celek 1 has `version`; the daemon modes register themselves
// from their own files, so the skeleton builds and runs without them.
package cli

import (
	"fmt"
	"io"
	"os"
	"sort"
)

// Version is the crd semver; AppVersion is set at release build time (-ldflags -X …/cli.AppVersion=…).
var (
	Version    = "0.1.0"
	AppVersion = "dev"
)

// Srv is WELCOME.srv (spec §2.3: app version + crd semver; B1a sends the crd part only, the app part is in `version`).
func Srv() string { return "crd " + Version }

// Mode is one entry point. It gets the arguments after the mode word and returns the exit code.
type Mode func(args []string, stdout, stderr io.Writer) int

var modes = map[string]Mode{
	"version": func(_ []string, stdout, _ io.Writer) int {
		fmt.Fprintf(stdout, "crd %s app %s\n", Version, AppVersion)
		return 0
	},
}

// Register adds a mode; called from init of the mode's file.
func Register(name string, m Mode) { modes[name] = m }

// Main runs crd with os.Args[1:].
func Main(args []string) int {
	if len(args) == 0 {
		return usage(os.Stderr)
	}
	m, ok := modes[args[0]]
	if !ok {
		return usage(os.Stderr)
	}
	return m(args[1:], os.Stdout, os.Stderr)
}

func usage(w io.Writer) int {
	names := make([]string, 0, len(modes))
	for n := range modes {
		names = append(names, n)
	}
	sort.Strings(names)
	fmt.Fprintf(w, "usage: crd <%v>\n", names)
	return 2
}
