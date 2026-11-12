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
- **Channel opening** — every SSH channel open goes through `connection/ChannelOpen.kt`: floor `MIN_OPEN_MS` (cf 1.5 s / lan 0.3 s),
  the open keeps running detached until confirmed or `OPEN_DEAD_MS`, a late confirmation is closed. JSch 0.2.21 never sends
  CHANNEL_CLOSE for an open that timed out, so a bare `ch.connect(t)` leaks an sshd slot. A transport is replaced after 3
  serially-started unanswered full-budget opens or 2 refusals with headroom; health swaps are spaced 30 s → 300 s. Pollers take
  `ExecKind.POLL` permits (no wait, silent skip, counted in the `poll skipped` line); 2 of 6 SHORT permits stay for interactive work.
- **Keepalive over Cloudflare** — JSch's keepalive never fires through `CloudflareProxy` (no socket); `TunnelKeepAlive` sends
  `sendKeepAliveMsg` itself and counts unanswered keepalives, not idle time (the interval switches 60 s ↔ 10 s with foreground state).
- **Writes to JSch** — never from the Android main thread: a socket write that throws leaves the cipher one packet ahead and sshd
  drops the link (`Corrupted padlen`, `Invalid ssh2 packet type`). Resize, close and reconnect run on IO; `TransportWriteGuard` closes
  a direct transport whose write failed.
- **Input delivery** — user input goes only to an ACTIVE tab; without a route it is dropped with "Not connected — not sent" and never
  sent later (the offline queue is gone: after a reconnect it could confirm a different dialog). Terminal-emulator replies go through
  `sendTerminalReply`; watch/notification replies through `ReplyGuard` over exec. A reconnect never ends ACTIVE over a dead shell, the
  SSH attach line ends with `|| exit 1`, and an early death after "success" counts as a failed attempt (backoff 4 s → 60 s).
- **Big exec requests** — nothing over 4 KB as an exec argument (`ExecArgumentSizeTest`); larger payloads go over stdin, paced (the
  80 KB restore installer through cloudflared left a transport that opened no channels). Close, close facts, restore and the
  `cr-session` install go through `StdinScript` (short launcher, body on stdin, runs only when the end marker arrived; no
  `__CR_END__` = never success: close → fallback, facts → null, restore → Unknown, install → SESSIONOPS_FAILED). Bootstrap,
  hooks v2, streamd, reconcile and the ET server install are still capped exceptions.
- **Fresh launch** — `SessionOrchestrator.launchSession` creates the session first over exec (`ClaudeConfig.buildFreshCreateCommand`:
  `new-session -d -e CR_LAUNCH=<nonce>`), only then adds the tab, which attaches. Never kill or adopt a session that holds the name;
  never let a tab exist before its name is ours. Socket heal: `tmux-sockheal.sh`/`sg_guard` generated in `SessionPersistenceService`.
- **Forget rules** — `session/service/ServerReconciler.kt` + `ManifestCommands.reconcileAll`: forget only if tombstoned ∧ absent from
  `list-sessions` ∧ another live session of the box's own prefix or of `claude-server-` exists; an unknown/truncated section never
  forgets.
- **Session-name prefix** — one place: `TmuxNameParser.prefixFor(serverName)` / `DEFAULT_PREFIX` (`claude-server-`); a literal
  elsewhere fails `TmuxPrefixGateTest` (named exceptions: Jarvis, `cr-session` bodies). Liveness for forget, prune and the push guard =
  own prefix ∪ `claude-server-`, compared as fixed strings. A box with another name gets its prefix in `~/.claude-remote/prefixes`
  (written by the installer; drift reads only lines that pass `cr_prefix_ok`). Drift adopts a session of a non-default prefix only
  when claude runs there with the app's `--dangerously-skip-permissions`/`--allow-dangerously-skip-permissions`, or when the app's
  `sessions.json` has the entry (then its metadata is kept, never a default YOLO).
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
- The restore installer is marker-driven (`MARKER` in `SessionPersistenceService`): a generator change without a marker bump never
  reaches a box that has the marker, and a bump rewrites the live scripts on the next launch of any client. The live box runs v19
  since 2026-11-10 (installed under supervision by the lead). The v19 installer kept `drift.sh.v18`/`restore.sh.v18` next to them;
  moving them back is the second exception to "never edit drift/restore" and only with Lukáš's explicit consent that day (a v19
  client launching later rewrites them again — then the way back is a v20 hotfix).
- Drift's `for s in $(tmux list-sessions …)` splits names with a space: `claude-server-My Project` is adopted as `claude-server-My`
  and self-heal starts a duplicate claude. Open (HIGH, predates v19; see `davka-D/predavka-D.md`).
- Claude 2.1.32x dialogs have NO `y`/`n` keys: Enter takes the focused option, Escape says no. `n` + Enter was ignored and
  then picked the focused "1. Yes" — every "Ne" button approved (fixed 2026-11-11, 1445d7a/4b0f2c4). Answer a dialog only
  with Enter while the screen shows the focus on the one-time "1. Yes" (never "always allow"/"auto mode"), or Escape while
  the dialog's numbered options are still on the screen (`ReplyGuard.serverCommand`, `tools/jarvis/jarvisapprove.py`).
- Generated shell: never a variable as the command (`$T tmux …`) — zsh does not word-split it; spell `timeout 3 tmux` in both branches.
- `~/.cache/davka-a/sockguard.py` calls `tmux display` on the default socket: do not hand it to agents; take the server pid from `ss`.
- Mutation runs: never a glob that matches your own runner (a recursion took the box to load 1400 on 2026-11-07); run mutants
  serially with `timeout`, and build with `--max-workers=2 -Pkotlin.daemon.jvmargs=-Xmx3g` while other sessions build.
- `LiveFileCanary` tolerates exactly `~/.claude-remote/jarvis/status.json` and `d-state.json` (rewritten every ~5 s by `jarvis-d`);
  `~/.cache/claude-remote/jarvis-alerts` and `events` are append-tier. The live app rotates `events` after 1000 lines
  (`events.rotated` appears): a "truncated" alarm on it during a run is that rotation — rerun the class once.

## Build and test
See `docs/building.md` and `docs/TESTING.md` (gradle 8.11.1 in `~/.local/gradle/`, JDK `~/jdks/jdk-21.0.5+11`; integration tests
serially under `flock ~/.cache/crit-integration.lock`). Finished work: commit (no AI attribution lines) → push `main` → watch GHA
`Tests` and `Build and Release` to green.
