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

## Source-set layout

| Source set | Runs on | Use for |
|---|---|---|
| `shared/src/commonTest` | both targets | Pure logic — parsers, classifiers, command builders, state machines |
| `shared/src/desktopTest` | desktop JVM only | Tests needing JVM/OS facilities (e.g. shelling out to `bash`) |

`commonTest` has friend-module access to `commonMain`, so `internal`
declarations are visible to tests without widening their visibility.

Tests must never read or write the developer's real `~/.claude-remote/` or
`~/.claude/`. Storage is reached through the `KeyValueStore` interface and
faked in tests — the desktop `PlatformPreferences` writes to
`~/.claude-remote/settings.properties`, i.e. the developer's live settings.

## CI

The `test` job in `.github/workflows/build-and-release.yml` runs the unit tests
and the release-variant compiles, and **gates** `build-android`, `build-macos`
and `build-linux`. A failing test blocks the release rather than shipping.
Test reports upload as the `test-results` artifact even on failure.

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
  `null`), never a removed id; a dangling id renders a blank terminal.

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

## Deliberately not covered

- **`@Composable` rendering.** Screenshot/UI tests here cost far more to
  maintain than they protect. The pure model behind the UI is tested instead
  (transcript render models, diffing, parsing).
- **Real network / SSH / tmux against a live server.** Tests assert the
  *commands and parsers* — the byte-exact strings sent and the output parsed —
  not the transport. Transport behavior (Starlink IP rotation, Eternal
  Terminal, Cloudflare fallback) is only reproducible against real links.
- **Voice/STT and Wear.** Needs device hardware and a paired watch.

## Known gaps

These are deliberately deferred, not overlooked. Each needs a seam before it
can be tested honestly, and a bad seam is worse than no test:

- **`SessionOrchestrator`** (~1950 lines, by far the most-changed file in the
  repo) is the biggest uncovered risk. It already takes its I/O as constructor
  lambdas (`readRealSessionId`, `disconnectSession`, `onActivityUpdate`,
  `onForgotten`, `isBackground`), but its collaborators — `ConnectionRegistry`,
  `TerminalIOService`, `TranscriptService` — are concrete classes with no
  interfaces, so it cannot be constructed in a test yet. Extracting interfaces
  for those three would open up the session lifecycle (create → attach →
  restore → forget) to real feature-level tests.

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
