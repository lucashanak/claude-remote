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

### Whole-build gates

`:desktopApp:test` and `:androidApp:testDebugUnitTest` have no test sources today
but run under the same isolation if one is added. `:desktopApp:compileKotlin`,
`:wearApp:compileReleaseKotlin` and the Android compiles above complete the
set run before a push.

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
faked in tests. The desktop `PlatformPreferences` writes to
`~/.claude-remote/settings.properties` (relative to `user.home`), which is why
Gradle redirects `user.home` for every test JVM (next section).

## Live-box safety (read this before touching test infrastructure)

The box that runs the tests also hosts the developer's live Claude sessions
(tmux on the DEFAULT socket, `~/.claude`, `~/.claude-remote`), and the tests are
usually started from inside one of those sessions. Four layers keep a test
from touching any of it.

**1. Environment of every Test task (root `build.gradle.kts`).** One
`subprojects { tasks.withType<Test>() }` block covers every module (shared
`desktopTest` and `integrationTest`, `androidApp`, `desktopApp`, `wearApp`,
`terminal-*`), so a new module cannot be forgotten:

| Setting | Why |
|---|---|
| `TMUX`, `TMUX_PANE` removed | a set `TMUX` overrides `TMUX_TMPDIR` and points tmux at the live server |
| `TMUX_TMPDIR=<root>/build/t/tmux` | without it the default socket is `/tmp/tmux-<uid>/default`, the LIVE server. The socket path `<dir>/tmux-<uid>/default` must fit `sun_path` (107 bytes); in a checkout too deep for that the directory falls back to `$java.io.tmpdir/crit-tmux-<hash>` |
| `PATH` starts with `scripts/test-shims` | its `tmux` refuses the live socket (below) |
| `HOME` and the system property `user.home` = `<root>/build/t/home/<project>-<task>` | Java ignores `$HOME`; code that falls back to the home directory (`PlatformPreferences`, `EtManager`) writes into the sandbox. Every Test task has its own directory, emptied (links removed as links) before the task runs, so state of an earlier run or of a parallel task cannot leak in |
| `LC_ALL=C.UTF-8` | under `LANG=C` the JVM's `sun.jnu.encoding` is ASCII and `ProcessBuilder` turns every non-ASCII byte of an argument into `?` (the `❯` of `cr-session`: two `SessionOpsScriptTest` cases failed only there, dávka D R7). `C.UTF-8` is built into glibc |
| `clauderemote.live.home` = the REAL home | `LiveFileCanary` keeps watching the real one |
| `TRANSCRIPT_HARNESS_ROOT` = real `~/.claude/projects` (unless set) | `RealTranscriptHarnessTest` only READS it and would otherwise skip silently under the redirected home |

Environment is not a Gradle task input, so the block also declares the
isolation values (as paths relative to the checkout, so two checkouts share cache entries) and the shim as inputs: changing them re-runs the tests instead
of restoring an old result. `DesktopTestEnvTest` (desktopTest) and
`ProbeEnvIntegrationTest` (the integration JVM) assert the result; the latter
exists because the integration task is registered by hand and a `withType` that
is narrowed by mistake would otherwise go unnoticed.

**2. The `tmux` shim (`scripts/test-shims/tmux`).** It works out which socket
tmux would use (same precedence as tmux: `-S`, `-L`, `$TMUX`, then
`$TMUX_TMPDIR/tmux-<uid>/default`), resolves symlinks and `..`, and exits 97
when that socket lives in `/tmp/tmux-<uid>/` or when `-L default` is used;
otherwise it execs the next real `tmux`. It stops a child that clears or
rewrites its environment (`ProcessBuilder.environment().clear()`,
`unset TMUX_TMPDIR`, `TMUX_TMPDIR=/tmp`, `-S /tmp/tmux-<uid>/default`).
`TmuxShimTest` runs it against a fake tmux that prints `REACHED`, so the test
cannot touch the live server even when the shim is broken. The shim knows the
global flags that take a value (`-S`, `-L`, `-f`, `-T`, `-c`) so a `-S` after them is still seen. The
environment variable `TMUX_SHIM_LIVE_DIR` (default `/tmp/tmux-<uid>`) is a seam for that test only: it lets the
test model "a link to the live directory" with a fake directory inside its own temp root.

*Limit, stated plainly:* the shim is a PATH entry. A child that calls the real
binary by absolute path (`/usr/local/bin/tmux`) or rebuilds `PATH` without it
(`env -i PATH=/usr/bin tmux ...`) AND has no private `TMUX_TMPDIR` still reaches
the live server. Closing that needs another UID or a mount namespace; nothing
short of that can. Test code must start `tmux` by name and keep the environment
it was given (or the one `LiveFileCanary.GuardEnv` provides).

**3. Deleting test trees: `safeDeleteTree`.** On 2026-11-02 a test left a link `dirlink -> /tmp` in its scratch
directory and its tear-down called Kotlin's recursive delete, which FOLLOWS links to directories: part of `/tmp` was deleted,
including the socket directory of the live tmux server (every session restarted). Rules that follow from it:

- a test never creates a link that points outside its own temp root (model "a link to a live path" with a fake path inside the
  root);
- tests and fixtures never call the recursive delete of `java.io.File`; they call `safeDeleteTree(root)` from `testsupport`, which
  removes links as links, and refuses (throws before touching anything) unless the root is strictly inside `java.io.tmpdir` (and not
  in the live `tmux-<uid>` directory) or strictly below `build/t` or `build/tmp`. `NoFollowingDeleteInTestsSyntaxTest` fails the build
  when any test source outside the helper uses the forbidden call;
- the first run of changed test infrastructure goes into a private `/tmp`:
  `unshare --user --map-root-user --mount sh -c 'mount -t tmpfs tmpfs /tmp && exec <gradle command>'` (uid 0 inside: the live-socket
  path there is `/tmp/tmux-0`, so the canary's tmux part is idle in that run).

**4. `LiveFileCanary` for tests that run server-side scripts** (hook merge,
`hook-ev.sh`, `ManifestCommands`). `canary.guard(token, testHome, tempRoot) { env -> ... }`:
the block uses `env.home` as HOME, starts children with `env.start(pb)` and puts
the unique `token` in its input. Afterwards, **also when the block threw**, the
canary compares a fingerprint of the live home taken before and after:

- every entry under `~/.claude-remote`, `~/.cache/claude-remote` and `~/.claude`
  by TYPE (directory, symlink and its TARGET, file) and SHA-256 of the CONTENT,
  so a truncation, an atomic rename, a delete-and-recreate, a symlink replaced
  by a regular file with the same bytes and a marker created in a config dir
  (`claude-remote-hookev.installed`) all fail;
- `*.credentials.json` is never opened: size + mtime + inode only. A live OAuth refresh rewrites a config dir's
  `.credentials.json` (`~/.claude` or `~/.claude-remote/accounts/<a>`) under the lock `.oauth_refresh.lock` (a directory) and
  `.oauth_refresh.lock.owner` (names measured from `ls` and the claude 2.1.318 string table, no credentials opened). That change is
  tolerated only when such a lock appeared or vanished in the SAME config dir during the guard (snapshot difference or an inotify
  event, so a refresh that starts and ends between the snapshots counts). A bare credentials change, a lock in another dir, a lock
  that stood unchanged throughout, or credentials created or deleted still fail;
- append-only live files (`logs/`, `events`, top-level `*.log`) must still
  exist and not shrink; while they keep their inode they keep their first 64 KiB (a file
  replaced by another inode that did not shrink is tolerated: the log shipper trims through a rename);
  over 64 MiB only size and inode are compared;
- live-rewritten entries are only checked for existence and type (list below);
  `~/.claude.json` likewise;
- the token must appear in no walked file content (UTF-8, UTF-16LE/BE, and base64 in each of its three alignments, which is how
  `hook-ev.sh` writes names to `events`) and no path name;
- the live tmux server, read-only, before and after: the socket FILE must still be the same file (inode and birth time: a recursive
  delete through a link into `/tmp` once removed it, and the watchdog that puts it back creates a new file; ext4 reuses inode numbers,
  hence the birth time), the same holds for the directories `/tmp/tmux-<uid>` and `/tmp/claude-<uid>`, no session may have vanished,
  no new session may have appeared (a restart of everything after a lost socket looks like that) and not all clients may be gone. Each
  tmux query has a time limit (its output goes to a file, so a hung server cannot block the canary); a server that does not answer
  counts as "not up" on that side;
- every process started through `env.start(...)` and every process that carries the
  guard's `CRIT_CANARY_TAG` environment variable (a detached grandchild inherits it) is
  killed BEFORE the comparison, so nothing writes after the check.

If the block and the check both fail the check's error is thrown and the block's
exception is attached as suppressed; a test that expects the block to throw
cannot hide damage. A failure that a live process could have caused says
`FALSE ALARM` in its message: rerun to confirm. The token checks never are.

*What is not watched (measured, not guessed).* Over 10 minutes of idle on the
live box (21 samples 30 s apart, 69 885 entries), plus a scan of what changed over the last 10 hours of use, these changed
on their own and are therefore reduced to existence/type: in `~/.claude` and in every
`~/.claude-remote/accounts/<a>` — `projects`, `hud`, `backups`, `skills/synced`, `plugins/oh-my-claudecode` and `plugins/synced`,
`.session-stats.json`, `.claude.json`, `policy-limits.json*`, `remote-settings.json*`, `sessions`, `state`, `cache`, `file-history`,
`session-env`, `teams`, `mcp-needs-auth-cache.json`, plus the names the brief gave (`history.jsonl`, `statsig`, `todos`,
`shell-snapshots`); in `~/.claude-remote` — `clients`, `usage`, `sessions.lock`, and of `jarvis/` exactly `status.json` and
`d-state.json` (the live `jarvis-d` daemon rewrites them every ~5 s, measured with `stat` 2026-11-07; everything else in
`jarvis/` stays strict); in `~/.cache/claude-remote` — `notify`.
Trees that were only guessed to be live-written (`paste-cache`, `debug`, `tasks`, `.omc`, `stats-cache.json`,
`gh-pr-status-cache.json`) are compared strictly; a false alarm there is rerun and, if real, measured and added.
Also existence/type only: `~/.claude-remote/sessions.json*`, `sessions.restore.json*`, `forgotten*` (the app and the drift script
rewrite them and create new entries beside them), `state/` and `mcp-needs-auth-cache.json` (also in the accounts), and below `skills`
only `synced`, below `plugins` only `synced` and `oh-my-claudecode`; every other `plugins` file contributes its path name to the token
search, except `hooks*.json` and `SKILL.md` outside `marketplaces` and the files directly in a `scripts` directory of `plugins/cache`,
which are compared by content (they inject hooks and skills into every live session; a script in a deeper subdirectory of `scripts`
is not). User `skills`,
`settings*.json` and the rest of a config dir are compared by content.
Temp and lock files (`*.tmp.*`, `*.lock`, `.drift.*`, `.oauth_refresh.lock*`) are not recorded because they come and go, and one that vanishes
while the tree is walked is skipped; they are still searched for the token. The same holds, in either kind of config dir, for what every live
Claude writes behind a test's back: the entries of `telemetry`, the `session-*` entries of `tasks` and `.omc/update-check.json` (false
alarms in three review runs). Their neighbours stay compared, and `.credentials.json` keeps its own rule. Files over 64 MiB are compared by size/mtime/inode.
A live user who opens or closes a tmux session during a run raises a false alarm (the message says so; rerun).
Known blind spots: an in-place change of a credentials file that keeps size, mtime and inode; a credentials rewrite made
while a real refresh lock of that dir came or went;
anything written below the live-rewritten trees; a test that swallows the canary's
`AssertionError` itself (`assertFailsWith<AssertionError>` around `guard`).

## CI

Tests live in their own workflow, `.github/workflows/tests.yml`, as a single
`test` job: unit tests, the release-variant compiles, then the integration lane.

It is deliberately a **separate workflow with its own concurrency group** so it
runs alongside `build-and-release` (whose builds are done in ~6 min; this one took 14:51 at
18faa58, 4:23 of it unit tests and 8:37 integration, and the release gate waits for it). The separate group also matters because the release
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

**The release gate.** `build-and-release.yml` job `tests-passed` runs `scripts/ci/tests-gate.sh SHA REPO 1800`: it polls the
push-triggered Tests run of that exact commit and succeeds only on `success` (`cancelled`/`skipped`/`failure` fail, no run or
no result within 30 min of wall-clock fails; every `gh` call is capped at 60 s and API errors are printed, then retried until the
deadline). On `main` it also fails if the SHA is no longer the head of `main` (a re-run of an old run must not release old code).
The job has `timeout-minutes: 35`. `release` needs it, and only runs on `main`. `scripts/ci/test-tests-gate.sh`
tests the script against a `gh` shim (`LIVE=1` adds one read-only API call against a real run); run it after touching the gate.
Because Tests is now the longest leg, a release lands ~Tests-duration after the push, not ~7 min.

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
  yields `null` rather than `false`. For a session with hook events (CAP=1) the
  working/idle parse returns `null` (`HookCapDetectorTest`): there the hooks own
  the state (`HookStateNotifyTest`).

- **Embedded restore/drift bash** — `SessionPersistenceService` carries a
  ~700-line bash installer as a Kotlin raw string, which writes `restore.sh`,
  `drift.sh` and `tmux-sockheal.sh` to the user's server via heredocs. It has been through 14
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
  that every id-keyed lookup could never reach again. And that `updateTab`
  writes only its target row (from the current one), leaves the active tab and
  the order alone, and never brings back a removed tab — it is what a write
  after a suspend point uses instead of `addTab(snapshot.copy(…))`
  (`SessionAccountCorrectionTest`: the account correction that put a live tab
  back to CONNECTING, 2026-11-11).

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
- **session prefixes** (D6-S, marker v19, `DriftPrefixesIntegrationTest`): the drift of v19 against the drift of
  v18 (`golden/restore-installer-v18.sh`, the installer cedfd70 generated, checked against `git show cedfd70`
  when the clone has it, else by its SHA-256) over the same world, one sshd fixture per world: on the box `server`
  (no `prefixes` file, or one of junk plus the default) the same manifest, mirror, log (times normalized) and
  tombstones; on `box` the only difference is the adoption of `claude-box-b` (claude runs in it); a file of broken
  lines adopts nothing and leaves `__anchor__` alive; overlapping prefixes give one row; a second tick adds 0 B to
  drift.log; kolo 3 (B1): a session of the box's prefix with neither claude nor an entry of the app (a `sleep`) is not
  adopted, leaves `__anchor__` alive and is not relaunched after its `kill-session` (= v18), and the app's entry of
  a session whose pane runs no claude stays as the app wrote it (MOSH, DEFAULT, OPUS, serverId, createdAt) in the
  manifest and the mirror, and self-heal relaunches it in DEFAULT; kolo 4 (B4): a claude without the app's flag
  (a bare `claude`, a `claude -p` whose folder is then deleted) and no entry is not adopted, leaves `__anchor__` alive
  and is neither relaunched nor self-healed (= v18), while with an entry of the app it keeps that entry; on `box`
  an adopted session drops `__anchor__` and a stray does not, even with an entry of another name;
  the step v18 → v19 rewrites the three scripts by rename and a v18 client after it rewrites nothing back; the
  replaced v18 `restore.sh` and `drift.sh` are kept as `.v18` byte for byte, only from v18 and only once.
  `DriftPrefixesTest` (desktopTest): hradlo A (what the generator sends for `server` differs from cedfd70's
  only in the marker (MARKER, three compat lines), the installer's trap, the header comment of drift, the prefix
  reader, the two comparisons, the three B1 blocks of the adoption with proof, the two rollback copies and the
  prefix write), the reader in bash and dash, each of the two rollback blocks alone (a v19 script is never kept as
  `.v18`), drift's overlay of the app's entries alone (an app file over 128 KB; a failing jq drops only its own names),
  and the installer's write (once, normalizing, never for `server`, never failing the install, a prefix already there
  found without the lock).
- **the tmux socket guard** (B3, marker v18), in the fixture's private
  `TMUX_TMPDIR` only: the socket file deleted, the whole `tmux-<uid>` directory
  deleted, and a split brain (a second server on the same path holding part of
  the sessions). drift must heal the first two with SIGUSR1 in the SAME server
  (pid, `session_created` and manifest unchanged, nothing relaunched) and must
  neither signal nor relaunch in the third; `restore.sh` and the app's tmux
  anchor (`ClaudeConfig`) must refuse there too. A `-L other` server in the same
  directory and one in another `TMUX_TMPDIR` keep their socket inodes. Also: the
  `/proc/net/unix` fallback (an `ss` shim that fails), the `drift-heal.off`
  rollback, a no-op tick writing nothing, and an upgrade over v17 scripts
  (rewritten by rename: new inode). Kolo 2: a tab reconnecting on a PTY after
  the socket was deleted (both the `attach || relaunch` form and the bare
  relaunch) keeps its pane pid; the stale socket file a failed `tmux attach`
  leaves is healed; a hung server (SIGSTOP of the private server, SIGCONT in
  `finally`) is never relaunched next to and the anchor refuses non-zero with
  the cause; a socket directory re-created 755 is made 700 again; a
  non-canonical `TMUX_TMPDIR`; an install interrupted by a failing `chmod`
  leaves no temp file; nothing the fixture starts sees `CLAUDE_CONFIG_DIR`.
  Kolo 3: tabs that relaunch one after another after the first one healed the
  socket, and a tab after a drift tick healed it, keep their pane pids; the
  heal re-checks the listeners under the lock (`sg_heal` called while two
  servers listen: rc 1, nobody signalled); a hung server is refused within one
  5 s wait; a split brain lands in drift.log, restore.log and sockheal.log.
  Kolo 4: a fresh launch of a taken name is refused non-zero and leaves that
  session's pane pid and `session_created` alone, and the free name
  (`freshTmuxSessionName`) runs a fresh `claude` stand-in in the chosen folder
  (the pane's login shell gets the stand-in's `PATH` from a test
  `.bash_profile`); a relaunch after a heal attaches with the real
  `TmuxProbes.singleClientPreamble`, so this device's old client is detached and
  the marker names the new one; `sg_probe` reads "No such file or directory" as
  stale; the app's guard looks once more after a heal that lost the lock (the
  lock held by a local `flock` until the first attempt's log stamp appears); a
  refused tab gets the cause on stderr every time while sockheal.log gets it
  once; an unknown listener pid stays `?` in the split-brain line with a file
  `t` in the cwd.
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
- `CLAUDE_CONFIG_DIR` (the live session's account dir) is stripped by `SshdFixture` from `run()`, the
  sshd daemon and `wrap.sh`: a claude stand-in in the private server would otherwise read the LIVE
  `settings.json` (`ExecRoutingIntegrationTest.capVerdict…` was red locally, green on CI). The Gradle
  Test task itself does not strip it yet (root `build.gradle.kts` is outside B3).
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
