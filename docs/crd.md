# crd — the claude-remote relay daemon (Stage B)

`crd` is one Go daemon per host under `systemd --user`. The app talks to it through **one counted exec channel**
running `crd --stdio` (the shim), which relays CRP/1 frames to the daemon's unix socket. It replaces the notify
watcher (`tail -F`) and streamd with durable, resumable streams; later stages add `rpc exec` (B1b) and the terminal
and input (B3). The normative spec is B0 revision 2 (`.omc/research/relay-design/stage-b/B0-spec.md`, kept outside
the repo); the golden vectors in `protocol/crp/golden/` are the source of truth for the wire format since B1a.

**Nothing of this runs on the dev box.** B1a is code, CI and private fixtures only (owner decision D-B0-1): no unit
is installed, no `crd serve` runs against the live tmux socket, `~/.claude-remote`, `~/.cache/claude-remote` or
`/tmp/claude-notify`. Installation comes after the §9 gate, by the owner's explicit action (B1b ships the unit
template and the installer).

## Layout

| Path | What |
|---|---|
| `crd/cmd/crd` | `main` |
| `crd/internal/crp` | CRP/1 codec: frame layout, canonical header writer, receiver rules, types, error codes |
| `crd/internal/daemon` | modes and paths, socket, handshake, connection table, priority writer, liveness, `crd.off`, graceful stop, sd_notify, log and stats |
| `crd/internal/stream` | durable streams: resume ring and ack guard, `ev` sources with seeding and rotation, `sess`, `tx` |
| `crd/internal/tmuxgate` | the only tmux caller (argv gate, `-N -S <guarded>`) and the socket guard |
| `crd/internal/client` | client side: shim prelude, handshake, client stream state machine, `crd probe` |
| `crd/internal/testenv` | the Go test harness (imported only by `_test.go` files) |
| `crd/tools/crdlint` | CI gate over the Go syntax tree |
| `protocol/crp/golden` | 55 golden vectors, `index.json`, `gen.py` (reference models) |

## Modes

| Mode | What it does | Creates files? |
|---|---|---|
| `crd serve` | the daemon; refuses (exit 1, stderr only) unless it is the systemd unit or in test mode | yes, under `~/.claude-remote/crd/` |
| `crd --stdio` | the shim: answers `E_OFF` / `E_DAEMON_DOWN` itself, else relays bytes both ways | no |
| `crd version` | `crd <semver> app <version>` | no |
| `crd selftest` | environment, path lengths, `/proc/net/unix`, inotify, a bind in a fresh temp dir | only that temp dir |
| `crd probe` | CRP client: handshake, `SUB ev/sess/tx`, ACKs, one output line per item, transcripts rebuilt into `-tx-out` | only `-tx-out` |

`crd serve` counts as "the unit" only with `NOTIFY_SOCKET` ∧ `INVOCATION_ID` ∧ no `TMUX` ∧ `SYSTEMD_EXEC_PID` = its
own pid ∧ the last component of `/proc/self/cgroup` = `claude-remote-crd*.service` ∧ `HOME` = the passwd home.
`INVOCATION_ID` alone is inherited by every live tmux session on the box and proves nothing.

Probe output lines (tested in `itest/round2_test.go`, the acceptance parses them): `welcome <header>`,
`ev <ep> <seq> <src> <base64 line>`, `sess <ep> <seq> <ops>`, `snap <st> <ep> <seq> <from> <reason>` (plus
`sesstable <sessions>` for `sess`), `tx <wid> <f> <o> reset=… more=…`, `gap …` (exit 3), `bye <code> <msg>` (exit 4).
The probe sends `PING` every `-ping-ms` (default 15000, also its `HELLO.pingMs`), so a quiet probe is never closed as
idle. Example: `crd probe -sub ev,sess -tx w1:<uuid>:0 -tx-out /tmp/x -for 1m`.

## Paths on the host (production)

All from one `HOME` (the unit's, checked against `/etc/passwd`); `os.UserCacheDir`, `XDG_*_HOME` and `os/user`
are never used. `~/.claude-remote` is created if missing but its mode is left alone; 0700 is forced only on
`crd/`, `crd/state/`, `crd/run/`. The log and the lock are opened with `O_NOFOLLOW`.

| Path | Use |
|---|---|
| `~/.claude-remote/crd/state/crd.lock` | singleton `flock` |
| `~/.claude-remote/crd/run/crd.sock` | daemon socket (dir 0700, socket 0600, own uid only via `SO_PEERCRED`) |
| `~/.claude-remote/crd/crd.log` | `key=value` log, cut to 16 MB at 20 MB, never content bytes; `crd stats …` every 60 s |
| `~/.claude-remote/crd.off` | instant off switch (inotify + 1 s stat): `BYE E_OFF` to every connection within 1 s, and instead of WELCOME to one that was still in its handshake; no `ev` source is read, no polling, watches or rotation; afterwards every follower goes on from its offset. The providers are told first (a round in flight ends before a client hears `E_OFF`), but for at most 1 s: a provider that hangs never holds up the `BYE` or the way back (log `crd-off-slow`) |
| `~/.cache/claude-remote/{events,notify,jarvis-alerts}`, `/tmp/claude-notify`, `~/.claude-remote/forget.log` | `ev` sources (seeding order events, notify, legacy, forget, jarvis). Opened with `O_NONBLOCK|O_NOFOLLOW` in every mode, then `fstat`: only a regular file owned by the daemon's uid is read; a FIFO, device, link or another owner's file (e.g. a FIFO someone put at `/tmp/claude-notify`) is closed unread, logged once per streak (`ev-not-regular … why="ev source not a regular file"`) and tried again every round |
| `~/.cache/claude-remote/rotate.lock`, `events.rotated` | rotation lock and stamp shared with the Stage A watchers (the stamp is opened with `O_NONBLOCK`: a FIFO there fails instead of stopping the round) |
| `~/.claude/projects/*/<uuid>.jsonl` | `tx` transcripts (streamd v4 semantics); opened with `O_NONBLOCK`, read only when `fstat` shows a regular file of the daemon's uid (else log `tx-not-regular wid=…` once, the watch stays) |
| `realpath(${TMUX_TMPDIR:-/tmp})/tmux-<uid>/default` | the guarded tmux socket: exactly one listener, accepts `connect()`, else no tmux call at all |

## Test mode

`CRD_TEST_ROOT` set ⇒ test mode, always, whatever systemd variables are set; a root that fails validation is a
refusal, never production. The root must contain `.crd-test-root`; `HOME` and `TMUX_TMPDIR` must lie strictly under
it (after `EvalSymlinks`, by path component), `HOME` must not be the real home, the guarded socket must not be the
live one, and the socket paths must be ≤ 100 bytes. Every one of the 19 derived paths (log, lock, socket, sources,
`events.rotated`, `rotate.lock`, `~/.claude/projects`, …) must lie strictly under the root with its existing part
resolved, and an unreadable `/etc/passwd` is a refusal. The check is repeated at every use, not only at the start: each
`ev` source before it is opened or followed, the rotation before it counts, reads the tail, locks `rotate.lock`, creates
its temp file, renames and touches `events.rotated`, the log's cut before it reads, creates, renames and reopens, and
every transcript before it is opened. A path that leads out of the root by then (a directory replaced by a link after
the start) is neither read nor written; the source logs `ev=ev-outside-root … why="ev source outside test root"` once
and goes on from its offset when the path is back. Sources, transcripts, the log, the lock and `events.rotated` are
opened with `O_NOFOLLOW`, new files with `O_EXCL`. What is left is the window between a check and the `open` that
follows it (a directory swapped in those microseconds): `openat2(RESOLVE_BENEATH)` would close it, but needs
`golang.org/x/sys` or a raw syscall, which crdlint forbids. The legacy marker moves to
`$CRD_TEST_ROOT/tmp/claude-notify`. Only in test mode the knobs `CRD_TEST_MIN_IDLE_MS`, `CRD_TEST_STATS_MS`,
`CRD_TEST_ROTATE_MS`, `CRD_TEST_SESS_MS`, `CRD_TEST_WRITE_MS` shorten timers; outside it every `CRD_*` variable is
ignored and logged. The gate and the socket guard check again that the guarded path is under the root.

## Limits (what a client or a session can make the daemon hold)

| Limit | Value | When exceeded |
|---|---|---|
| frame the daemon sends | negotiated `maxFrame`, header ≤ 65535 B | that connection only: `BYE E_FRAME` + log `frame-refused`; never a panic |
| connections per host | 64, registered or still in their handshake | one more gets `BYE E_SHUTDOWN{retryMs:5000} "connection limit reached"` before its handshake (log `conn-limit`): the client retries after `retryMs` without a strike (§1.4 cause `shutdown`) |
| writer queue per connection | 32 MiB or 16384 frames (any type, PONG and SNAP included) | connection closed without BYE (`conn-overflow`); the client sees `hung`/EOF and resumes |
| writer queues of the host together | 128 MiB | the connection with the largest queue is closed without BYE (`conn-overflow … scope=host`), the others go on |
| one socket write | 10 s (`WriteTimeout`) for a whole frame | connection closed, log `cause=write-timeout`: a client that stops reading never pins a goroutine, fd or queue. A link slower than ~6 KB/s never gets a 60 KiB frame through (the deadline is per frame, not per piece) and loops through close and resume |
| `SUB` of `ev`/`sess` the connection already follows | — | ignored: no second SNAP or backlog, the flow-control window stays (a client that lost its place reconnects with `resume`) |
| `pingMs` (HELLO, STATE) | ≤ 300 000 | capped: the idle limit is at most 15 min |
| `ev` source line | ≤ 60 KiB | the line never enters the ring or the backlog; log `ev-oversize src=… len=…`, stats `ev_dropped` |
| `ev` seeding | last 2 MiB of each source | older bytes are not read at start |
| `ev` rotation | lines counted and the kept tail read in 64 KiB blocks | memory is one block plus the kept 200 lines, whatever the size of the source |
| `sess` row | `cmd` cut to 256 B at a UTF-8 boundary; `name` never cut | a session whose name is over 256 B is left out of the table (log `sess-name-too-long … why="sess name too long"`); ≈ 100 sessions of maximal rows fill a 64 KiB header: then `BYE E_FRAME` |
| `sess` records | `list-panes` carries `#{pane_id}` | a `pane_id` seen twice in one listing drops every record with it, a session with more than one active record takes none: such a session keeps its previous row (a pane process that reads the nonce from `/proc` can print a whole record) |
| `tx` `wid` | 1–128 of `[A-Za-z0-9._-]` | `BYE E_PROTO` |
| `tx` watches | 64 per connection, 256 per host (a SUB of a known `wid` replaces it and takes over its unacknowledged window; a replaced or removed watch never sends again, also when the poll had already picked it up) | `BYE E_PROTO` |
| `tx` reads | pieces of 48 KiB read when they are sent, within the 2 MiB window | — |
| `BYE.msg` | 256 B | cut |

A panic in a connection's goroutine (reader or writer) closes that connection and logs `CRITICAL`; the daemon goes on.

## Running the tests

```
cd crd
export GOTOOLCHAIN=local GOFLAGS=-mod=readonly PATH=$HOME/.local/go/bin:$PATH
go vet ./... && go run ./tools/crdlint .
nice -n 10 env GOMAXPROCS=4 CGO_ENABLED=1 go test -race -count=1 -p 4 -parallel 4 -timeout 10m ./...
nice -n 10 env GOMAXPROCS=4 CGO_ENABLED=0 go test -count=1 -p 4 -timeout 10m ./...
CGO_ENABLED=0 go build -trimpath -o /tmp/crd ./cmd/crd && file /tmp/crd
(cd ../protocol/crp/golden && python3 -I gen.py --check)     # "checked 55 vectors, 0 mismatches"
```

- Every test package that starts a process calls `testenv.Main` from `TestMain` (crdlint fails otherwise). It
  sets up a private root under `$GOTMPDIR` or `/tmp/crdt-<uid>` before any test, points `HOME`, `TMUX_TMPDIR` and
  `XDG_RUNTIME_DIR` into it, puts `scripts/test-shims/tmux` first on `PATH`, removes `TMUX`, `TMUX_PANE`, `CLAUDE*`,
  systemd and D-Bus variables, tags every child with `CRD_TEST_TAG`, sweeps tagged orphans afterwards, removes the
  root without following links and compares a read-only canary of the live box (tmux socket and directory inode,
  server identity and pane processes, `~/.claude-remote/crd` and `rotate.lock` absent, `/tmp/claude-notify`
  unchanged). Never `pgrep`/`pkill`; a signal goes only to a pid that carries the run's tag.
- Tests with a real tmux start a private server (`tmux -f /dev/null -S <root>/… new-session … 'sleep 3600'`) and
  take `~/.cache/crit-integration.lock` in-process, like the Kotlin integration tests, so they wait while another
  session runs those. Without a tmux binary they skip; with `CRD_REQUIRE_TMUX=1` (CI) a skip is a failure. The
  wait counts against `-timeout`: while a long Kotlin integration run holds the lock (`grep <inode> /proc/locks`),
  `internal/itest` fails without having run a test — run it again later, never around the lock.
- `GOTMPDIR` (≤ 40 bytes, 0700, own uid) is the base of the test roots; the harness refuses one inside
  `/tmp/tmux-<uid>`, `~/.claude-remote`, `~/.cache/claude-remote` or `~/.claude`.
- The integration tests run the test binary itself as `crd` (`CRD_TEST_EXEC=1`), so `-race` covers the daemon too.
- CI: job `crd` in `.github/workflows/tests.yml` (vet, crdlint, `-race`, `CGO_ENABLED=0` tests, golden check,
  static build, `scripts/ci/test-tests-gate.sh`). A failed `crd` job fails the Tests run the release gate reads.

## What B1a does and does not

Does: the codec and every golden vector (decode, re-encode, and the models it implements: negotiation, connection
table, ring with resume/SNAP/seeding, ack guard, client stream, tx watch and pieces); the daemon core of spec §2 and
§7 (handshake, supersede/STALE, ping, priority writer, flow control, `crd.off`, SIGTERM, sd_notify, log, stats);
`ev` with five sources, seeding, rotation and its tail skip; `sess` over the gate with the guard, mass-drop hold and
`SNAP{tmux-restart}`; `tx` with streamd v4 semantics, 48 KiB pieces, pending reset and gzip; `crd probe`.

Does not (later stages): `rpc exec`, the allowlist and deny scan, children and their reaping, the unit template and
installer, the release asset (B1b); `term`, `IN` and the daemon-held attaches (B3); the Kotlin client (B2). B1a never
offers `term`, `in` or `rpc.exec`; a frame that needs them is `BYE E_PROTO`.

Known limits: two identical consecutive lines can make the tail skip after a foreign rotation swallow a line (the
reducer's content dedup is the backstop); a trailing partial line of an inode that is rotated away is not emitted
from the old inode, and a kept tail that ends in a partial line is glued to the next appended line (Stage A's `tail`
does the same); the watchdog heartbeat covers the accept loop and the stream loops, not the writers (a writer blocked
on a slow client ends at the 10 s write deadline); crdlint sees call names built from literals, not from named
constants or at run time; the rotation holds the kept 200 lines in memory (a tail of oversize lines is that big);
tmux session groups (a pane listed under two sessions) would lose their rows to the `pane_id` rule (the app creates
none); a session killed between `list-sessions` and `list-panes` can still take a forged record for that one poll. A
round of `ev` that takes longer than 1 s when `crd.off` appears (a rotation counting a very large source) is no longer
waited for: its last reads can come after the `BYE E_OFF` (logged `crd-off-slow`).

Left for later stages (verification of round 2, LOW): the host limit of 256 `tx` watches can refuse a SUB of another
device, and the refusal ends that device's whole connection (B1b/B2: a limit per `dev` and an `ERR` for one SUB, which
CRP/1 does not have); a sparse transcript or a giant last line keeps `tx` scanning under its lock in O(file size)
(B1b: a scan budget per poll and lazy pieces); a `sess` table over the 64 KiB header ends the connection and its
resume (B2: SNAP/EV `sess` in pages); `crd.off` is followed by inotify with a 1 s stat behind it, and no test tells
the two apart.

Left for B1b (verification of round 3; required input of B1b): R-2 (a process in any session that reads the nonce can
hide or freeze another session's row through the one-active-record rule: read `cmd` outside the record format;
MEDIUM before B3), R-3 rest (handshake slots apart with a shorter timeout, a limit per `dev`: 64 slow or registered
connections of one uid still keep a legitimate client out), R-4 (`os.Root` instead of the check before each use in
test mode: a link switched in and out as fast as possible still wins the window, see above), R-5 (the host shed picks
the largest queue in bytes: a slow legitimate client can be the one), R-6 (128 MiB bound the queues, not the RSS), and
the TOCTOU checks inside the rotation and the log cut that no test can tell apart without a seam. A file of another
owner at a source is refused by the same `fstat` comparison the tests reach by changing the expected uid (one user
cannot create such a file).

**Before B3** (the terminal and input decide by `name`, `panePid` and `dead`): N-7 (names cut to one row) and N-8
(forged pane records) are fixed since round 3 — names are never cut, and records are dropped by `pane_id` and by the
one-active-record rule above.

## Spec errata found in B1a

- §1.3/§4.1/§4.4/§4.7: the Stage A watcher tails **five** files since 2026-11-08 (Jarvis alerts); crd follows
  `~/.cache/claude-remote/jarvis-alerts` as source `jarvis`, seeded after `forget`, never rotated by crd.
- §2.3/§5.4: `inGen` is per daemon start in B1a (no input state yet); B3 has to keep it with `input.log`.
- §7.8/§8.2: systemd 252 ends a start-limit trip with `Result=exit-code`, not `start-limit-hit` (B1b).
- §4.7 tail skip "the last lines emitted": lines appended between the rotator's `tail` and `mv` make the kept tail a
  contiguous run inside the emitted window, not its suffix; crd skips the longest prefix of the new file that equals
  such a run.
- §1.1/§4.7 cite `NotificationService.kt:82-97`; the function is `:85-102` today.
- §4.7/§2.6 (round 2): an `ev` line over 60 KiB is dropped, not sent (Stage A's `tail` would pass it on). The writers
  stay far below: hook lines carry a base64 name and at most 600 B of extra text (≈ 1.3 KiB, `HookCommands.kt`
  `hook-ev.sh`), forget lines are `[ts] forget: <name> (durable=…)`, notify/legacy lines are session names, Jarvis
  lines carry at most 600 + 200 characters the app reads (`JarvisAlerts.kt`).
- §4.5 (round 2, changed in round 3): `sess` rows cut `cmd` to 256 B without a `trunc` flag and never cut `name` (the
  client's key): a session with a name over 256 B is left out of the table; the order of `SNAP.sessions` is by
  name (vector 09 lists its two rows in another order; the client keys the table by name). A resume position older than
  a tmux server change gets `SNAP{reason:"tmux-restart"}`; a `resume.sess` before the first table is announced as
  `resumed.sess = "snapshot"`.
- §2.4 (round 2): `pingMs` is capped at 300 000 (HELLO and STATE).
- §2.6/§3.5 (round 3, changed in round 4): the host takes at most 64 connections. CRP/1 has no connection-level
  `E_BUSY` (§3.5 lists it for RPC and IN only); `E_PROTO` would be a `proto` strike, and three of them in 10 min switch
  CRP off for the server until the app restarts (§1.4), so a short flood of connections would end CRP on the device.
  One more connection therefore gets `BYE E_SHUTDOWN{retryMs:5000, msg:"connection limit reached"}`, the only
  connection code the client takes as transient without a strike (§1.4 cause `shutdown`: retry after `retryMs`, no
  backoff; §3.5 lists `E_SHUTDOWN` as transient for IN too). Proposal for B2: a connection-level `E_BUSY{retryMs}`
  in §3.5 and a row `busy` in the §1.4 table (backoff, no strike), so the client can tell a full host from a stopping
  daemon in its log.
- §2.3/§4.3 (round 3): a durable `SUB` of a stream the connection already follows is ignored (the spec does not say);
  §2.6 "unacked TX bytes per watch" is per `wid`: a SUB of a known `wid` keeps what is unacknowledged.
- Vector 01 (round 2): its login junk names `192.0.2.1` (RFC 5737) instead of a LAN address.
