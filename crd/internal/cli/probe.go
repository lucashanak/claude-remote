package cli

import (
	"flag"
	"fmt"
	"io"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/lucashanak/claude-remote/crd/internal/client"
	"github.com/lucashanak/claude-remote/crd/internal/crp"
)

func init() { Register("probe", runProbe) }

type multi []string

func (m *multi) String() string     { return strings.Join(*m, ",") }
func (m *multi) Set(s string) error { *m = append(*m, s); return nil }

// runProbe: crd probe [-socket P] [-sub ev,sess] [-tx wid:uuid:off[:gzip][:cwd]]... [-tx-out DIR]
// [-resume st=ep:seq]... [-dev D] [-link L] [-att N] [-for 10s] [-items N]
func runProbe(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("probe", flag.ContinueOnError)
	fs.SetOutput(stderr)
	o := client.ProbeOpts{}
	var subs string
	var txs, resumes multi
	fs.StringVar(&o.Socket, "socket", "", "talk to this daemon socket instead of running --stdio")
	fs.StringVar(&subs, "sub", "ev", "comma-separated durable streams")
	fs.Var(&txs, "tx", "transcript watch wid:uuid:off[:gzip][:cwd]")
	fs.StringVar(&o.TxOut, "tx-out", "", "directory for the reassembled transcripts")
	fs.Var(&resumes, "resume", "position st=ep:seq")
	fs.StringVar(&o.Dev, "dev", "crd-probe", "device key")
	fs.StringVar(&o.Link, "link", "0000000000000000", "link (16 hex)")
	att := fs.Int64("att", 1, "attempt counter")
	fs.DurationVar(&o.For, "for", 0, "stop after this long (0 = until EOF)")
	fs.IntVar(&o.Items, "items", 0, "stop after this many items (0 = no limit)")
	fs.Int64Var(&o.PingMs, "ping-ms", client.DefaultPingMs, "HELLO.pingMs and the PING interval")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	o.Att = *att
	for _, s := range strings.Split(subs, ",") {
		if s != "" {
			o.Subs = append(o.Subs, s)
		}
	}
	for _, t := range txs {
		p := strings.SplitN(t, ":", 5)
		if len(p) < 3 {
			fmt.Fprintln(stderr, "crd probe: -tx wid:uuid:off[:gzip][:cwd]")
			return 2
		}
		off, err := strconv.ParseInt(p[2], 10, 64)
		if err != nil {
			return 2
		}
		ts := client.TxSpec{Wid: p[0], UUID: p[1], Off: off, Cwd: "~"}
		if len(p) > 3 {
			ts.Gzip = p[3] == "gzip"
		}
		if len(p) > 4 {
			ts.Cwd = p[4]
		}
		o.Tx = append(o.Tx, ts)
	}
	if len(resumes) > 0 {
		o.Resume = crp.Obj{}
		for _, r := range resumes {
			st, pos, ok := strings.Cut(r, "=")
			ep, seq, ok2 := strings.Cut(pos, ":")
			n, err := strconv.ParseInt(seq, 10, 64)
			if !ok || !ok2 || err != nil {
				return 2
			}
			o.Resume[st] = crp.Obj{"ep": ep, "seq": n}
		}
	}
	if o.For == 0 && o.Items == 0 {
		o.For = 24 * 365 * time.Hour
	}
	_ = os.Stdout
	return client.Probe(o, stdout)
}
