# Testing

The suite exists to stop regressions in the parts of this app that have
historically broken silently: the shell commands we generate, the third-party
output formats we scrape, and the bash we install on the user's server.

## Running tests

Everything runs on the JVM (desktop) target — no device or emulator needed:

```bash
./gradlew :shared:desktopTest
```

Local runs may need an explicit JDK (the Rider JBR has no `jlink`, which the
Android plugin needs):

```bash
JAVA_HOME=~/jdks/jdk-21.0.5+11 ./gradlew :shared:desktopTest
```

HTML report: `shared/build/reports/tests/desktopTest/index.html`.

A single class or method:

```bash
./gradlew :shared:desktopTest --tests 'com.clauderemote.session.ClaudeConfigTest'
./gradlew :shared:desktopTest --tests '*.ClaudeConfigTest.restartCommandCdsBackToSessionFolder'
```

Gradle's up-to-date check sometimes misses a newly added test file. If a test
you just wrote doesn't appear to run, force it:

```bash
./gradlew :shared:desktopTest --rerun-tasks
```

### Release-variant compile check

The debug pipeline does **not** catch everything — missing icon imports,
cross-module `internal` access, sealed-state type inference and the `R`
namespace only fail in a release build. Before pushing Kotlin changes:

```bash
./gradlew :shared:compileReleaseKotlinAndroid :androidApp:compileReleaseKotlin
```

CI runs this in the same job as the tests.

### Integration lane (real sshd + real tmux)

```bash
./gradlew :shared:integrationTest
```

Needs `openssh-server`, `tmux`, `jq`, `flock` on the box; it fails loudly with a
list of what's missing rather than passing zero tests. It is deliberately NOT
wired into `check`, and `:shared:desktopTest` cannot see it — the separation is
structural (its own compilation), not a filter someone can forget.

## Source-set layout

| Source set | Runs on | Use for |
|---|---|---|
| `shared/src/commonTest` | both targets | Pure logic — parsers, classifiers, command builders, state machines |
| `shared/src/desktopTest` | desktop JVM only | Tests needing JVM/OS facilities (e.g. shelling out to `bash`) |
| `shared/src/desktopIntegrationTest` | desktop JVM only | Real sshd / tmux / restore.sh. Slow (seconds per test), opt-in |

`commonTest` has friend-module access to `commonMain`, so `internal`
declarations are visible to tests without widening their visibility.

Tests must never read or write the developer's real `~/.claude-remote/` or
`~/.claude/`. Storage is reached through the `KeyValueStore` interface and
faked in tests — the desktop `PlatformPreferences` writes to
`~/.claude-remote/settings.properties`, i.e. the developer's live settings.

## CI

Tests live in their own workflow, `.github/workflows/tests.yml`, as a single
`test` job: unit tests, the release-variant compiles, then the integration lane.

It is deliberately a **separate workflow with its own concurrency group** so it
runs alongside `build-and-release` and never delays a release (that workflow
takes ~7 min; this one ~3). The separate group also matters because the release
workflow uses `cancel-in-progress` on one shared group — a burst of pushes
cancelling release runs would otherwise take the test runs down with it.

**One job, not two.** It was originally split into parallel `unit` and
`integration` jobs at ~166s each — but only 1.1s and 8.5s of that was running
tests. The rest was Kotlin compilation and runner setup, paid for TWICE:
`:shared` compiled once per job (~60s each), two Android SDK installs, two
Gradle setups. The build cache cannot fix that, because parallel jobs start
before either has written a cache. One job does: `build/` persists across steps,
so the release-compile and integration steps reuse the first step's
compilation (verified — the integration step reports `compileKotlinDesktop
UP-TO-DATE`). The lanes are serial now, so wall-clock rises slightly; that is
free while the release takes twice as long anyway.

The release-compile and integration steps run under `if: !cancelled()` so one
push surfaces every failure at once, instead of making you fix the unit tests
and push again to discover the integration lane is broken too.

**Trade-off, stated plainly:** nothing here gates the release, so a failing test
does *not* stop a release from shipping. Make this job a required status check
on `main` to get that brake back without slowing anything down.

Reports upload as `test-results` even on failure, and the integration harness's
sshd/tmux/restore logs upload as `integration-diagnostics` when it fails —
without them a CI-only failure in the integration lane is close to
undiagnosable.

### Build caching

`org.gradle.caching=true` reuses task outputs across builds and CI runs;
`org.gradle.configuration-cache=true` only skips Gradle's configuration phase
and does not help with compilation. With the classes deleted, a rebuild pulls
`compileKotlinDesktop`/`compileTestKotlinDesktop` FROM-CACHE and finishes in
~2.5s instead of ~29s.

Two tasks must NOT be reused, and both are pinned so:

- `desktopTest` declares every module's Kotlin sources as inputs, because
  `TmuxTargetSyntaxTest` reads them at runtime and Gradle cannot see that.
  Without the declaration a cached result could be restored while a scanned
  source had changed, and the guard would silently not run.
- `integrationTest` sets `outputs.cacheIf { false }` as well as
  `upToDateWhen { false }`. The latter alone only defeats the up-to-date check —
  an out-of-date task can still be restored FROM-CACHE, which would "pass" the
  lane without ever starting sshd.

## What is covered, and why

Targets were chosen by *churn × fragility*, not coverage percentage.

- **`ClaudeConfig`** — pure builders for the shell commands that launch,
  restart and resume Claude Code inside tmux. Locks in single-quote escaping
  (a path containing `'` must not break out), tmux **exact** target syntax
  `-t '=name'` (plain `-t` prefix-matches and once attached users to the wrong
  session), `--resume` vs `--session-id` selection, and that restart `cd`s back
  into the session folder instead of `$HOME`.

- **`InputPromptDetector`** — the regex scrapers for the oh-my-claudecode
  statusline and `/usage` output. This is the most fragile coupling in the
  project: it parses a third-party format we don't control. When OMC 4.15 added
  a `[###----]` progress bar, every scrape broke silently — usage chips froze
  on stale scrollback and working/idle detection went quiet. Tests pin both the
  bar and no-bar forms, last-match-wins semantics over the rolling buffer (an
  older `thinking` render must not re-assert WORKING after going idle),
  reset-time parsing with any of `(XdYhZm)` omitted, and that "no statusline"
  yields `null` rather than `false`.

- **Embedded restore/drift bash** — `SessionPersistenceService` carries a
  ~615-line bash installer as a Kotlin raw string, which writes `restore.sh`
  and `drift.sh` to the user's server via heredocs. It has been through 14
  revisions and one of them SIGSEGV'd the tmux server, killing every session.
  The test extracts all three layers and syntax-checks them (`bash -n`, plus
  `shellcheck` when available). Note `bash -n` on the outer script does *not*
  validate heredoc bodies, so the inner scripts are extracted and checked
  separately — and the extraction itself is asserted non-empty, so renaming a
  heredoc delimiter can't make the test silently pass on an empty string.

- **`TabManager`** — the session tab/drawer state machine. Chiefly that
  removing the active tab reassigns `activeTabId` to a valid remaining tab (or
  `null`), never a removed id; a dangling id renders a blank terminal. Also that
  `addTab` is idempotent on id — appending a duplicate produced a phantom tab
  that every id-keyed lookup could never reach again.

- **tmux target syntax** (`TmuxTargetSyntaxTest`) — a source-level scan, not a
  normal unit test, because this defect has been found and fixed **five** times
  in this codebase and most of the offending command strings are built inline
  inside functions that need a live SSH session.

  tmux resolves `-t name` as exact → **prefix** → fnmatch. Measured on tmux
  3.5a: `kill-session -t 'proj--cashy'` kills the session named
  `proj--cashy-2`. In this app that means the user's *wrong* Claude session gets
  killed, renamed, scrolled, or reported on. The correct forms — all verified
  against real tmux, not inferred:

  | verb | exact form |
  |---|---|
  | `has-session`, `kill-session`, `rename-session`, `list-panes`, `attach-session` | `-t '=name'` |
  | `send-keys`, `respawn-pane`, `copy-mode`, `display-message` | `-t '=name:'` |

  The trailing colon is required, not cosmetic: `-t '=name'` on a pane target is
  rejected outright with `can't find pane`. tmux's own `-t=name` spelling (used
  by the embedded bash as `-t="$VAR"`) is already exact and is deliberately not
  flagged. The scan carries a short justified allowlist for targets that aren't
  session names (a client tty) and asserts it actually scanned the sources, so
  a broken path can't make it pass vacuously.

- **`TranscriptRenderModel`** — the pure transform behind the Chat view:
  consecutive tool calls collapsing into one group, time-gap insertion at the
  threshold boundary, turn attribution, and above all that `itemKey` values are
  unique and stable. Those keys back a lazy list, so a duplicate corrupts
  rendering and an unstable one throws away scroll position.

- **`UpdateChecker`** — `isNewer` decides whether a user is offered an update at
  all, so it is pinned against the cases a lexicographic compare gets wrong
  (`1.10.0` vs `1.9.0`), equal versions, short/long segment counts and garbage
  input fetched from GitHub. `sha256` is asserted against known-answer vectors
  rather than itself, since it gates self-update integrity.

- **Storage** (`AppSettings`, `SessionStorage`, `ServerStorage`) — round-trips,
  that `installId` is generated once and stays stable (the tmux single-client
  marker is keyed on it), that unknown enum values fall back instead of
  throwing, and that corrupt persisted JSON doesn't crash on launch. Includes
  backward-compat fixtures: a field rename must fail a test rather than
  silently wipe users' saved sessions and servers.

## The integration lane

15 tests in `shared/src/desktopIntegrationTest` drive the production connection,
probe and restore code against real infrastructure.

**How it sandboxes production code without a seam.** The code under test builds
its own shell commands and opens its own exec channels; there is no "use this
HOME" hook and adding one just for tests would be the wrong trade. Instead the
sandbox lives in the *server*: `sshd_config` carries `ForceCommand wrap.sh`, and
that wrapper rewrites `HOME`, `TMUX_TMPDIR`, `PATH` and unsets `TMUX` for every
command the sshd ever runs. Unmodified production code is therefore sandboxed by
construction.

**What it proves that unit tests cannot:**

- **The `sessions.json` producer/consumer contract.** `restore.sh` parses the
  manifest with `jq .[i].tmuxSessionName`; the producer is
  `SessionStorage.serializeForServer`. The test feeds the *app's own serializer
  output* into the *real restore.sh* and asserts the tmux sessions appear. A
  paired negative test renames one manifest field and asserts nothing is
  created — so the contract test is provably able to fail. Nothing else in the
  suite guards this, and a field rename would silently break session restore for
  every user.
- **tmux exact-match, end to end.** The real `TmuxProbes.probeTmuxSession` over
  a real SSH connection must answer `false` for `proj--cashy` while only
  `proj--cashy-2` exists.
- **drift self-heal**: kill one restored session, run the real `drift.sh`,
  assert only that one is relaunched and the healthy sibling is untouched.
- **Eternal Terminal** round-trips a command through a real `etserver`, and
  `mosh-server new` really emits the `MOSH CONNECT <port> <key>` line the app
  parses.

**Claude Code itself is never run.** It needs OAuth and would draw on a capped
subscription credit pool, so a stand-in `claude` on `PATH` parks forever and
leaves a live pane. `systemctl`/`loginctl`/`systemd-run` are likewise shimmed so
the tests can't mutate the developer's real systemd user instance — and so
drift.sh's self-heal branch is observable on a CI runner with no user session
bus. These tests assert *our* logic, not Claude's and not systemd's.

**Safety invariants** (the dev box runs live Claude sessions on the default tmux
socket, and the test JVM is started from inside one of them, so it inherits
`TMUX` — which *overrides* `TMUX_TMPDIR`):

- `TMUX`/`TMUX_PANE` are stripped from the test JVM, from the daemon, and from
  every child process.
- Every tmux call passes an explicit `-S <private socket>`.
- `kill-server` runs only after asserting the socket is inside the fixture root.
- Teardown kills tracked PIDs, never `pkill -f <pattern>` — that matches any
  process whose command line merely *mentions* the string, which will happily
  kill a concurrent run or a developer's shell.
- Startup sweeps stale `/tmp/crit.*` roots, because a killed JVM never runs its
  shutdown hook.

## Extracting decisions out of SessionOrchestrator

`SessionOrchestrator` is ~1950 lines and by far the most-changed file here. It
builds its own collaborators in the constructor, so it cannot be instantiated in
a test, and wrapping all of them in interfaces would be a large, risky change to
exactly the file that changes most often.

The approach taken instead: pull the **decisions** out as pure units and leave
the I/O where it is. Three so far, 44 tests.

- **`TmuxLaunchDecider`** — the decision behind `sendTmuxCommand`, and the most
  consequential one in the app: attach, rebuild-and-resume, rebuild-fresh, or
  *forget the session and delete it*. Cheap inputs are passed by value and each
  expensive probe as a `suspend () -> Boolean`, so the original laziness is
  preserved exactly — every probe is an SSH round-trip, sometimes over cellular.
  The tests assert the full decision table AND that probes are not called when
  they shouldn't be (`isNew` short-circuits before any probe, a live tmux never
  probes for a transcript, and so on).

  The assertion that matters most: `!tmuxExists && !stillTracked && !hasLivePeers`
  must **not** forget. A whole-server tmux death makes `tmuxExists` false for
  every session and an emptied manifest makes `stillTracked` false too — that
  combination once forgot every session on a transient outage.

- **`ReconnectPolicy`** — the two backoff curves, which differ deliberately
  (background re-arm caps at 60s and starts at ~2s; foreground auto-reconnect
  caps at 30s and retries immediately on attempt 1 because the user is
  watching). Both have failed in production in both directions: too tight and
  the retry loop hammered the link hard enough to kill a Tailscale gateway, too
  capped and a session sat DISCONNECTED until an app restart. Tests pin the
  exact millisecond values per attempt rather than re-deriving the formula, and
  check the curves never dip or go negative — `2000L shl n` overflows, so the
  `coerceAtMost(5)` on the shift is load-bearing.

- **`TmuxPeerLiveness`** — the parse half of the liveness gate that feeds the
  forget decision. Deliberately asymmetric: any ambiguous or empty output must
  read as "no peers", because a false "peers are alive" deletes a session while
  a false "no peers" only rebuilds one.

`buildAttachCommand` is now `internal` and tested for its exact output. That
test exists because the command carried a live bug: the window-size un-pin used
`set-option -w -t '=name'`, but a **window** target needs the trailing colon
just like a pane target. tmux rejected it with `no such window`, and the
`2>/dev/null` on the line swallowed the failure — so the un-pin, whose whole
purpose is to heal windows an older build left pinned to `window-size manual`,
had never once run. Verified on tmux 3.5a before and after:

```
$ tmux set-option -w -t '=sess:' window-size manual   # simulate an old build
$ tmux set-option -w -t '=sess'  window-size latest 2>/dev/null; echo $?
1                                    # …and still 'manual'
$ tmux set-option -w -t '=sess:' window-size latest 2>/dev/null; echo $?
0                                    # now 'latest'
```

`attach-session` on the same line correctly stays `-t '=name'` without a colon —
it is a session target. The two forms differ deliberately, and
`TmuxTargetSyntaxTest` now guards both.

## Deliberately not covered

- **`@Composable` rendering.** Screenshot/UI tests here cost far more to
  maintain than they protect. The pure model behind the UI is tested instead
  (transcript render models, diffing, parsing).
- **Live remote servers.** The integration lane covers real sshd/tmux/ET on
  loopback, but genuinely network-dependent behavior (Starlink egress-IP
  rotation, the Cloudflare WebSocket tunnel, ET resume across a real drop) is
  only reproducible against real links and is not simulated.
- **Voice/STT and Wear.** Needs device hardware and a paired watch.

## Known gaps

These are deliberately deferred, not overlooked. Each needs a seam before it
can be tested honestly, and a bad seam is worse than no test:

- **`SessionOrchestrator`** is now partially covered — see below. What remains
  uncovered is its I/O orchestration (`connectSsh`, `connectEt`,
  `reconnectEtCarrier`, `autoReconnect`'s loop body). Those need interfaces for
  `ConnectionRegistry` / `TerminalIOService` / `TranscriptService`, which the
  class constructs internally rather than receiving. That is a large change to
  the most-churned file in the repo, so the decision logic was extracted first
  instead — see "Extracting decisions out of SessionOrchestrator".

- **`TransportResolver`** — the Tailscale/Cloudflare/ET fallback and
  early-death backoff logic. Valuable to cover (it governs reconnect behavior
  on flaky links) but it reads `System.currentTimeMillis()` directly; it needs
  an injected clock (`now: () -> Long`) before its backoff windows can be
  tested without sleeping.

- **`TmuxProbes.singleClientPreamble`** is a pure shell-string builder with
  safety-critical quoting and per-device key truncation, but it is an instance
  method on a class requiring four live collaborators. Moving it to the
  companion (or a top-level `internal fun`) would make it directly testable.

- **`InputPromptDetector`'s timing logic** — the quiescence window, bounded
  re-check budget and notification latch have all had real bugs ("fires once,
  then never again"). The parsers are covered; the state machine is not,
  because it calls `System.currentTimeMillis()` internally. Same fix: inject a
  clock, then drive it with `runTest`'s virtual time (`kotlinx-coroutines-test`
  is already on the test classpath for this).

## Adding a test

Match the existing style, which is deliberately regression-oriented:

- `kotlin.test` only.
- One class per unit; descriptive `lowerCamelCase` names that state the
  expected behavior (`removingActiveTabReassignsActiveId`).
- **A comment naming the regression the test locks in.** The existing tests
  read as a changelog of real bugs; keep that. A test whose purpose is
  unclear gets deleted by the next person.
- Assert exact values. `assertNotNull` or `isNotEmpty()` alone is a test that
  cannot fail.
- When you fix a bug, add the test that would have caught it *first*.
