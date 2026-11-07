# claude-remote — what a session must know before touching the code

KMP app (Android phone + Wear through the phone + desktop) that drives ~50 Claude sessions living in tmux on a dev box over SSH.
Details live in `docs/`; this file only points at the places of truth and at the traps.

## Places of truth
- **Connection layer** — `docs/connection.md` (pool, exec transport, flags, seams, reconcile, bootstrap, hooks). Flags and their
  implications: `shared/.../session/service/CommFlags.kt` (`effectiveFor(serverId)` for any per-server decision; `deviceDefaults` never).
- **Pool / handshakes** — `connection/ServerTransportPool.kt` + `SingleFlight.kt` (≤ 3 handshakes per transport key, shell cap 8 only
  while the server's exec transport is up, else 3).
- **Exec transport and channel budget** — `session/service/ExecTransportManager.kt`, `RemoteExec.kt` (`GatedExecChannels`, typed
  `ExecUnavailable`); routing helpers in `ConnectionRegistry` (`execRun`, `execRead`, `useChannel`). Fallbacks only onto a transport
  with channel headroom (sshd default `MaxSessions 10`; the dev box has 30). Every tab-side channel is counted against its transport
  (`withTabChannel`/`onServerTabChannel`, `TabChannels`); no room = skip the turn, never let sshd refuse. One reserve slot for short
  channels only; transfers never take it. A new channel site without a token is a budget leak.
- **Fresh launch** — `SessionOrchestrator.launchSession` creates the session first over exec (`ClaudeConfig.buildFreshCreateCommand`:
  `new-session -d -e CR_LAUNCH=<nonce>`), only then adds the tab, which attaches. Never kill or adopt a session that holds the name;
  never let a tab exist before its name is ours. Socket heal: `tmux-sockheal.sh`/`sg_guard` generated in `SessionPersistenceService`.
- **Forget rules** — `session/service/ServerReconciler.kt` + `ManifestCommands.reconcileAll`: forget only if tombstoned ∧ absent from
  `list-sessions` ∧ another live `claude-server-*` exists; an unknown/truncated section never forgets.
- **Hooks** — `HookCommands.kt` (install/merge/`hook-ev.sh`), `HookEventReducer.kt`, `NotificationService` watcher. Claude 2.1.316
  hot-reloads `settings.json` within ~1 s, so installing hooks affects every live session at once: install only on the explicit
  per-server Settings action; `~/.claude-remote/hookev.off` is the instant off switch.
- **Wiring** — `session/SessionOrchestrator.kt` (`wireExecLayer`).
- **Metrics** — `tools/relay-metrics/` (extractor + hourly `snapshot.sh` from the user crontab).

## Traps (each one has happened)
- **Tests run inside one of the user's live tmux sessions.** Every Test task gets a private `TMUX_TMPDIR`/`HOME` and a tmux shim
  (root `build.gradle.kts`, `scripts/test-shims/tmux`, keep its exec bit). Never call tmux on the default socket from a test.
- **Never create a symlink in a test that points outside the test's own temp root, and never use `File.deleteRecursively()` in tests**
  — Kotlin follows directory symlinks. On 2026-11-02 a test with `dirlink -> /tmp` deleted the live tmux socket; drift then relaunched
  all 51 sessions as duplicates. Use `testsupport/safeDeleteTree`; a syntax test enforces it.
- `LiveFileCanary` guards live files and the live tmux socket identity; a canary failure is a real alarm unless its message names a file
  the live Claude processes rewrite (e.g. OAuth refresh of `.credentials.json`) — then rerun once and report it.
- Never `tmux kill-server`/`-g` options/`window-size manual` on the default socket, never `systemctl --user daemon-reload`, never edit
  `~/.claude-remote/{drift,restore}.sh` by hand, never read or copy `~/.claude/.credentials.json`.
  - The one exception (Lukáš, R3 = 3c): `~/.claude-remote/bin/cr-session` (`SessionOpsScript`) runs the launch builder's own
    `set-option -g` (`ClaudeConfig.optionsPreludeBody`/`createBody`: idempotent, the same every app launch sends, never
    `window-size`) on the live server. Keep it composed from those pieces; it never runs `kill-session` on a name it creates.
- Account dirs `~/.claude-remote/accounts/*/settings.json` are symlinks to `~/.claude/settings.json`: write through the realpath,
  never `mv` over the account path.
- Debug builds hide release-only compile errors: always also run `:shared:compileReleaseKotlinAndroid :androidApp:compileReleaseKotlin`.
- Other sessions work on `main` in this same checkout: unpushed work belongs on a branch, or it may be pushed by someone else.

## Build and test
See `docs/building.md` and `docs/TESTING.md` (gradle 8.11.1 in `~/.local/gradle/`, JDK `~/jdks/jdk-21.0.5+11`; integration tests
serially under `flock ~/.cache/crit-integration.lock`). Finished work: commit (no AI attribution lines) → push `main` → watch GHA
`Tests` and `Build and Release` to green.
