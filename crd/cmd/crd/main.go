// Command crd is the claude-remote relay daemon (spec B0 rev. 2, docs/crd.md). Modes: see internal/cli.
package main

import (
	"os"

	"github.com/lucashanak/claude-remote/crd/internal/cli"
)

func main() { os.Exit(cli.Main(os.Args[1:])) }
