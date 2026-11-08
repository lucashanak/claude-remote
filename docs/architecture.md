# Architecture

Claude Remote is built with Kotlin Multiplatform (KMM) and Compose Multiplatform, sharing the vast majority of code between Android and Desktop targets.

## Layered Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                        UI Layer                               │
│  LauncherScreen  ConnectScreen  TerminalScreen  SettingsScreen│
├──────────────────────────────────────────────────────────────┤
│                     Session Layer                             │
│          SessionOrchestrator    TabManager                    │
│          ClaudeConfig           CommandFetcher                │
├──────────────────────────────────────────────────────────────┤
│                    Connection Layer                            │
│          SshManager    TmuxManager    MoshManager             │
├──────────────────────────────────────────────────────────────┤
│                     Storage Layer                             │
│          ServerStorage    AppSettings    PlatformPreferences   │
├──────────────────────────────────────────────────────────────┤
│                   WebView Terminal Layer                       │
│          xterm.js (shared HTML/JS) + platform JS bridge       │
└──────────────────────────────────────────────────────────────┘
         ↕ SSH/Mosh
┌──────────────────────────────────────────────────────────────┐
│   Remote Server:  sshd → shell → tmux → claude CLI            │
└──────────────────────────────────────────────────────────────┘
```

## Navigation

The app uses a state machine with 5 screens defined in `shared/src/commonMain/.../ui/App.kt`:

| Screen | Purpose |
|--------|---------|
| `LAUNCHER` | Server list, active sessions, add/edit/delete servers |
| `CONNECT` | Folder picker, Claude mode/model config, tmux session selector |
| `TERMINAL` | xterm.js terminal with session dropdown and controls |
| `SETTINGS` | Terminal, Claude defaults, connection, security preferences |
| `LOG_VIEWER` | Debug log viewer (FileLogger output) |

**Flow:** `LAUNCHER → CONNECT → TERMINAL` (main path), `LAUNCHER → SETTINGS`, `SETTINGS → LOG_VIEWER`

## Data Models

### SshServer (`model/SshServer.kt`)

Represents a configured remote server with all connection and Claude defaults:

```kotlin
@Serializable
data class SshServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMethod: AuthMethod,          // PASSWORD or KEY
    val password: String? = null,
    val privateKey: String? = null,
    val preferMosh: Boolean = false,
    val defaultFolder: String = "~",
    val recentFolders: List<String>,     // Last 10 used folders
    val defaultClaudeMode: ClaudeMode,
    val defaultClaudeModel: ClaudeModel,
    val portForwards: List<PortForward>,
    val favorite: Boolean = false,
    val startupCommand: String = "",     // Executed after SSH login
    val snippets: List<String> = emptyList()
)

data class PortForward(
    val type: String,       // "L" (local) or "R" (remote)
    val localPort: Int,
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int
)
```

### ClaudeSession (`model/ClaudeSession.kt`)

Represents an active terminal session:

```kotlin
data class ClaudeSession(
    val id: String,
    val server: SshServer,
    val folder: String,
    val mode: ClaudeMode,
    val model: ClaudeModel,
    val tmuxSessionName: String,
    val connectionType: ConnectionType,
    val status: SessionStatus = CONNECTING,
    val connectedAt: Long
)
```

Computed properties:
- `tabTitle` — `"serverName:folderName"` for display in session dropdown
- `durationText` — Human-readable elapsed time (`"5m"`, `"1h23m"`)

### Enums (`model/Enums.kt`)

```kotlin
enum class ClaudeMode(val displayName: String, val flag: String?) {
    NORMAL("Normal", null),
    PLAN("Plan", null),
    AUTO_ACCEPT("Auto-accept", "--auto-accept"),
    YOLO("YOLO", "--dangerously-skip-permissions")
}

enum class ClaudeModel(val displayName: String, val value: String?) {
    DEFAULT("Default", null),
    OPUS("Opus", "opus"),
    SONNET("Sonnet", "sonnet"),
    HAIKU("Haiku", "haiku")
}

enum class ConnectionType { SSH, MOSH }
enum class AuthMethod { PASSWORD, KEY }
enum class SessionStatus { CONNECTING, ACTIVE, DISCONNECTED, ERROR }
```

## Session Lifecycle

### Launch Sequence

```
User taps "Launch Claude"
  │
  ├─ SessionOrchestrator.launchSession()
  │    ├─ Create ClaudeSession object
  │    ├─ TabManager.addTab(session)
  │    │
  │    ├─ SshManager.connect(server, onOutput, onConnectionLost)
  │    │    ├─ JSch auth (password/key)
  │    │    ├─ Host key verification (TOFU)
  │    │    ├─ Port forwarding setup
  │    │    ├─ Open shell channel (xterm-256color PTY)
  │    │    └─ Start streaming read loop
  │    │
  │    ├─ Wait 500ms for shell prompt
  │    ├─ Execute startupCommand (if set)
  │    ├─ Send tmux new-session command
  │    ├─ Send claude launch command (folder + mode + model flags)
  │    │
  │    └─ Session is ACTIVE
  │         ├─ Output → onTerminalOutput → WebView → xterm.js
  │         └─ Input → JS bridge → sendInput → SSH channel
  │
  └─ Navigate to TERMINAL screen
```

### Auto-Reconnect

When the SSH read loop detects connection loss:

1. Emit yellow warning message to terminal
2. Attempt reconnect with exponential backoff (2s, 4s, 6s — max 3 attempts)
3. On success: reattach to existing tmux session, replay 256KB output buffer
4. On failure: emit red error, set session status to `DISCONNECTED`

### Server-side scripts and the tmux socket guard

`SessionPersistenceService.INSTALL_RESTORE_COMMAND` installs three scripts in `~/.claude-remote` (on the first
launch per server and app run, `persistLaunched`) whose marker (`claude-remote-restore-v18`) is missing, each
written to a temp file and renamed into place (never `cat >` over a script a running bash may be reading):

- `restore.sh` (boot unit, and drift's self-heal) recreates sessions from the manifest;
- `drift.sh` (user timer, every minute) reconciles the manifest with live tmux and relaunches what died;
- `tmux-sockheal.sh`, which the app's tmux anchor (`ClaudeConfig.buildTmuxRunCommand`) runs before any
  command that may create a tmux server.

The installer (~80 KB) is never the exec request itself: it goes on the stdin of the short `INSTALL_RESTORE_EXEC`,
paced 4 KB / 20 ms like every upload, and runs only once its end line arrived, so a transport lost midway runs
none of it; it counts as installed only when it exits 0. The restore unit counts as present when it runs our
`restore.sh` (an earlier check looked for a text the unit never had, so every install rewrote it and ran
`daemon-reload`). As one ~80 KB request (23 KB compressed) through the Cloudflare tunnel it was followed by an exec
transport that opened no more channels until it died (dávka C, K-1). `ExecArgumentSizeTest` holds every exec
request at 4 KB or under, with named exceptions (bootstrap, hooks v2, streamd, the reconcile of many sessions)
that may not grow.

All three carry the same **socket guard**. A tmux server whose socket file (or whole `/tmp/tmux-<uid>`
directory) was deleted keeps running but answers nobody; on 2026-11-02 drift then saw no sessions,
`restore.sh` started a second server and every session was relaunched into it as a duplicate. The guard
counts the servers *listening* on the exact path `realpath(${TMUX_TMPDIR:-/tmp})/tmux-<uid>/default` (`ss -xlpH`,
fallback `/proc/net/unix` listening rows; never by cmdline): none → the old behaviour; one that answers →
fine (it must answer through `-S` *and* to a plain `tmux`, which also checks the directory); one without its
socket file, or with a stale socket file nobody accepts on (a failed `tmux attach` starts a short-lived server
of its own when the path is empty and leaves exactly that, measured on tmux 3.5a) → `mkdir -m 700` of the
directory (an existing one is made 700 again), check `/proc/<pid>/comm` and uid, re-check under the lock that
it is still the only listener, `SIGUSR1` (tmux unlinks and recreates the socket), wait at most 2 s, and the
drift tick ends without relaunching or writing the manifest; one that answers only through `-S` because the
directory is not private → `chmod 700`; one that does not answer (a single 5 s wait), or two and more (split
brain) → no signal, no relaunch, no new server, a log line rate-limited per caller and kind (drift.log, restore.log
and sockheal.log each get it once per 15 minutes). Heals and `restore.sh`'s server creation share
`~/.claude-remote/tmux-server.lock`. Every tmux call of a drift tick has a time limit.
`~/.claude-remote/drift-heal.off` switches the guard off in all three scripts — the only rollback, because an
older client never rewrites a v18 script. An install cut short removes its temp files (`trap … EXIT`).

`tmux-sockheal.sh` exits 0 (nothing to do), 3 (it was unusable and is usable again) or 1 (refused). A heal that
failed (lock not taken within 2 s, listeners changed meanwhile, socket not back) mostly lost a race with another
tab, so the app's guard looks once more after 0.5 s before it refuses. A socket file that vanishes between the
guard's `[ -S ]` and its probe ("No such file or directory") counts as stale, not hung. On a refusal the tab gets
every cause line on stderr each time, while sockheal.log gets a line once per 15 minutes per kind (the rate-limit
stamp holds back only the log copy). The anchor fails with a non-zero exit on a refusal and creates nothing.

A session of that name that exists is never killed and created again. What happens instead is chosen by the
caller (`ClaudeConfig.OnExisting`, V14), who knows what it sends (`TmuxLaunchDecision`):
- a **relaunch** of a session that should exist (Rebuild, `attach || relaunch`, resume) attaches it, whatever
  the guard's exit: after a lost socket several tabs (and a drift tick) reconnect at once, only the first one
  heals (exit 3), every later one finds a socket that answers (exit 0) while the session ran in that server all
  along — `kill-session` + `new-session` restarted it (3 of 4 tabs, measured). The attach is the caller's
  single-client attach (`buildAttachCommand`: this device's old client is detached and the device marker names
  the new one); a bare attach left the old client attached and the anchor's client unknown to the marker;
- a **fresh launch** (V16) creates its session on the server **before its tab exists**, over an exec channel:
  `tmux new-session -d … -e CR_LAUNCH=<nonce>` (`ClaudeConfig.buildFreshCreateCommand`: the same anchored command,
  detached, with no `has-session` and no `kill-session` — `new-session` itself fails on a taken name, atomically), its
  exit status read, then the owner of the name (`show-environment … CR_LAUNCH`). The session is the tab's only when
  it carries the launch's nonce; a name another session holds is left exactly as it is and the next free name is
  tried (`-2`, `-3`, … after the requested one, at most 8 names). A lost answer is retried with the same nonce, and a
  retry that runs into the session the lost try created recognises it as its own (no second name). The server
  bounds the creation itself (K6-1: `timeout 10 sh -c …`, then 3 s for each check, together below the client's 20 s),
  so a hung tmux never keeps sshd's session after the client gave up; a creation or a check the server cut short
  (status 124, no owner line) reads as a lost answer. A launch that ends without its tab once a creation was sent —
  cancelled, or every answer of a name lost (3 tries) — removes the session that creation made, and only when it
  carries the launch's nonce (R6-2/R6-3, `ClaudeConfig.buildOwnSessionCleanupCommand`: compare and kill in one
  server command); a session of the name without that nonce is never touched. A second launch of the same requested
  name on the same server while the first one is creating (a second tap, quick connect twice, Launch re-armed on
  the Connect screen) joins it (R6-1): it waits for the first launch's tab and switches to it, or ends with the first
  one's error — never a second session; quick connect counts the names being created as this device's own
  (`SessionOrchestrator.ownTmuxNames`, read once its listing answered, N7-3), so it asks for that same name, and the
  Connect screen keeps the name its own launch is creating when a listing shows it (H16). Only
  then is the tab added; it attaches the session like any session of this device (`buildAttachCommand`,
  single-client), and a session gone again before that attach is rebuilt by the usual recovery, never launched fresh
  in the tab. Until the tab exists nothing can persist, push, probe, restart, rename or close it. The creation's
  channel costs no handshake of its own: the exec transport or a counted channel of a tab transport with room
  (`ConnectionRegistry.execRun`); a mosh launch with exec routing waits up to 15 s for the exec transport it starts
  anyway; otherwise it leases the pooled shell transport the tab's shell (SSH, ET carrier) leases next and holds
  that slot until the shell has the transport (given back at once when another holder keeps the transport). No
  channel at all, or a server that refuses the creation (socket guard), ends the launch with a readable error and
  no tab. Kolo 2 killed a session that held the name, kolo 3 adopted it, V15 read a verdict off the tab's output
  and needed guards wherever a not-yet-confirmed tab could be reached; V16 has no such tab. The in-tab launch
  (`OnExisting.Refuse`: non-zero exit, "already exists" on stderr, no `kill-session`) stays as the backstop.
  The app still picks a free name first (`ClaudeConfig.freshTmuxSessionName`), so a name only the restore manifest
  knows is avoided too. Taken names are the live listing (`TmuxManager.listForFreshName`: tmux sessions plus the
  names in the server's `sessions.json`, so a session of another device that is not back yet after a reboot keeps
  its name) ∪ the launcher's last scan of the server; the Connect screen adds this device's tabs. A listing that
  fails, is cut short or does not answer (quick connect waits 3 s, the Connect screen 6 s; the server ends the
  listing itself after 2 s) throws, and the other names stand in — never "nothing is taken". On the Connect screen
  such a listing is "list unavailable" with Retry, never "0 on server" (C2: `ConnectListing.state` is
  `Loading | Loaded | Failed`; "N on server" only for `Loaded`). Retry opens a connection of its own only when the
  server has no exec transport up (`allowFresh = !execUp`), and is off while a listing or a launch runs (the launch's
  8 s debounce; past it the launch's own name is kept and a second Launch joins it, R6-1). A new session waits for a
  running listing; after a failed one it can be launched, with a warning, since the server checks its name when it
  creates it (V16); an off Launch stays visible and says why. A kill re-lists only the screen of its own server, and
  an existing session that is no longer listed is no longer picked. Only the last opening writes the result, picking
  "New session" again after an existing one gives a free name again, and "Will run" shows the launch's own builder
  (`tmux new-session -d -s <name>` + `ClaudeConfig.buildLaunchCommand`; an existing session: `tmux attach-session`).
  A tab attached to an existing session from the Connect screen still carries the screen's folder field, not the
  session's folder (limit; the manifest's folder is not in the listing). Quick connect stays a fresh claude in
  the default folder: a session of another device or folder under that name gets a number, and a tab this device
  already has open under the name is switched to.
  Limits: a mosh launch on a server without exec routing and without a transport pays one handshake for the
  creation (mosh brings its own SSH, which cannot carry it); three or more launches at once with the cap of 3 (a cold
  server, the kill switch) can need one transport more while a creation still holds its slot (K6-3, measured with the
  kill switch in 4 of 8 runs; two creations through at the same moment no longer both give their slot back — that is
  decided under one lock). The cleanup of an own session needs a channel without a handshake (exec transport, a
  counted channel, the held transport) and a creation that is over on the server: with no such channel, or when a
  cancelled creation is still running there when the cleanup asks, the session stays — the launcher lists it like
  any session of the server. The first attach after the creation goes by the name, not the nonce: a created session
  that is gone in the second before the attach while another one of that name appears is attached (narrow window,
  not fixed). `new-session -e` needs tmux 3.2 or later (older tmux: every new session fails with tmux's usage text).
  The listing and the creation need coreutils `timeout` on the server (without it the listing always fails and the
  other names stand in; every creation fails with "timeout: not found"). A name with a backslash (a folder `a\b`):
  tmux stores it altered, the owner check finds no session of the requested name and reads status 0 as ours, so the
  created session stays under tmux's name and the tab's attach rebuilds another (R3, `sanitize` keeps `\`; not fixed).
The detached login pane keeps the usual path (`kill-session` + `new-session`: its point is a fresh login).

Limit: a drift tick captures tmux output (`$(tmux …)`); a server that freezes after the tick's guard holds the
captured pipe open (the client hands its stdout to the server), so the per-call `timeout` does not end the capture
and the oneshot drift unit waits until the server runs again. Not fixed (every capture would need a temp file).

### Tab Switching

`TabManager` maintains a `StateFlow<List<ClaudeSession>>` and `StateFlow<String?>` for the active tab. When switching tabs:

1. Current tab output continues buffering in the background
2. New tab's buffered output is replayed to the terminal via `onTabSwitched` callback
3. Terminal WebView is cleared and repopulated

## Session Orchestrator

`SessionOrchestrator` (`session/SessionOrchestrator.kt`) is the central coordinator:

- Maintains one `SshManager` instance per active session
- Per-session output ring buffer (256KB) for tab switching and reconnect replay
- Exposes callbacks for terminal output, tab switches, disconnections, and input prompts
- Handles file upload via SFTP through `SshManager.uploadFile()`

## Claude CLI Integration

`ClaudeConfig` (`session/ClaudeConfig.kt`) builds the launch command:

```bash
# Example output:
cd ~/myproject && claude --model opus --auto-accept
```

Runtime control is done by sending keystrokes/commands to stdin:

| Action | Sent to terminal |
|--------|------------------|
| Toggle mode | `Shift+Tab` (`\x1b[Z`) |
| Change model | `/model\n` |
| Enter plan mode | `/plan\n` |
| Clear context | `/clear\n` |
| Interrupt | `Escape` (`\x1b`) |

`CommandFetcher` (`session/CommandFetcher.kt`) provides slash command suggestions — fetched from the remote Claude installation or falling back to a hardcoded list of 70+ commands.

## Multiple Claude accounts

A session can run under any of several Claude logins, bound **per session** — two
sessions on one server may use different accounts at the same time. Tokens live
only on the server; the client is given labels (`ClaudeAccount`) and never a
credential.

Isolation comes from `CLAUDE_CONFIG_DIR`. Each non-default account owns a config
dir under `~/.claude-remote/accounts/<slug>/`, holding its own
`.credentials.json` and `.claude.json` (which is where account identity lives —
credentials files contain none) while symlinking the shared parts back:

```
~/.claude-remote/accounts/<slug>/
├── .credentials.json     per account; Claude rotates it in place
├── .claude.json          per account — oauthAccount identity + project trust map
├── projects/     → ~/.claude/projects/      shared: transcripts
├── plugins/      → ~/.claude/plugins/       shared: OMC statusline the app scrapes
├── settings.json → ~/.claude/settings.json  shared: hooks
└── CLAUDE.md     → ~/.claude/CLAUDE.md
```

Sharing `projects/` is what keeps the rest of the app unchanged: transcripts stay
in one place, so `TranscriptService`, `streamd` and the `TmuxProbes` transcript
probe need no account awareness, and `claude --resume <uuid>` still finds a
conversation after its session switches accounts.

### Non-obvious constraints

These were established experimentally against Claude Code 2.1.220. Each one
fails silently if ignored.

- **`CLAUDE_CONFIG_DIR=$HOME/.claude` is not the same as leaving it unset.**
  Unset resolves the global config to `~/.claude.json`; setting it to
  `$HOME/.claude` resolves it to `~/.claude/.claude.json`, which does not exist,
  gets created empty, and takes the project trust map and MCP config with it. The
  default account must therefore receive *no* variable at all —
  `claudeConfigDirFor()` returns `null` for exactly that case, and that `null`
  must survive all the way to the launch command.
- **Provisioning order is mandatory**: `mkdir -m 700` → seed `.claude.json` →
  create symlinks → *then* first launch. Launch first and Claude creates real
  `settings.json`/`plugins/`/`cache/` entries that collide with the symlinks,
  costing the account its hooks and the OMC statusline.
- **A fresh config dir must have onboarding pre-seeded** (`hasCompletedOnboarding`,
  `lastOnboardingVersion`, `theme`). Otherwise the first interactive launch runs
  the onboarding wizard, which demands a *second* login even though the dir
  already holds valid credentials.
- **Never copy a credentials file between dirs.** Refresh tokens rotate; the
  losing copy gets `invalid_grant` and Claude Code then blanks it on disk — a
  silent logout. Each account is logged in separately; whole dirs may be moved.
- **`claude setup-token` is unusable here.** Its long-lived token is minted
  `user:inference`-only and it ignores `CLAUDE_CODE_OAUTH_SCOPES`, which would
  break MCP servers, file upload and the usage endpoint. A normal
  `claude auth login` grants all five scopes.
- **The restore script builds the launch command a second time.** `restore.sh`
  (generated by `SessionPersistenceService`) reconstructs `claude …` independently
  of `ClaudeConfig`, in both its `jq` and no-`jq` branches, and runs server-side
  with no app attached. An account not propagated there means drift self-heal
  quietly rebuilds sessions under the default account.

Per-folder policy (`FolderPolicyStorage`, `model/FolderPolicy`) records a default
account and an allowed set for a folder. Enforcement is deliberately **soft** —
the app narrows what it offers, the server refuses nothing — because it guards
against mis-taps, not against an adversary. An empty allowed set means
*unrestricted*, not *nothing*.

## Storage

### ServerStorage (`storage/ServerStorage.kt`)

Persists the server list as JSON via `PlatformPreferences`. Also manages known hosts for TOFU SSH verification.

### AppSettings (`storage/AppSettings.kt`)

User preferences grouped by category:

| Category | Settings |
|----------|----------|
| Terminal | `fontSize` (8-32), `colorScheme`, `scrollback` (default 10000) |
| Claude | `defaultMode`, `defaultModel` |
| Connection | `defaultPort`, `connectionType`, `autoReconnect`, `connectTimeout` |
| Connection (experimental) | `comm_pool`, `comm_exec`, `comm_hooks`, `comm_lazy`, `comm_fg_deadlink`, `comm_warm_tabs` (5-8), `comm_bg_park_grace_s` (10-3600), `comm_legacy_servers` — see [connection.md](connection.md#communication-redesign-flags-and-seams) |
| UI (Android) | `suppressSystemKeyboard`, `hapticFeedback`, `themeMode` |
| Security | `biometricLockEnabled` |

### PlatformPreferences (`expect/actual`)

| Platform | Implementation |
|----------|---------------|
| Android | `SharedPreferences` (`"claude_remote"` namespace) |
| Desktop | `~/.claude-remote/settings.properties` (Java Properties) |

## Session Services

`SessionOrchestrator` used to be a ~4000-line god-class. It is now a thin
**session-lifecycle coordinator** (connect → tmux → claude, reconnect,
disconnect, the `emit()` output fan-out, transport resolution) that owns and
wires a set of single-responsibility collaborator services under
`session/service/` (plus the existing `session/status`, `session/transcript`,
and `session/notify` packages). The public API surface of `SessionOrchestrator`
(constructor, callback `var`s, `StateFlow` vals, methods) is unchanged — the
composition roots (`androidApp`, `desktopApp`, `ui/App.kt`) construct it exactly
as before and it delegates to the services.

| Service | Responsibility |
|---------|----------------|
| `ConnectionRegistry` | Per-session SSH/Mosh transports, per-server transport pools + connect gates, tab→server lookups. The seam every service uses to reach a live transport. |
| `TransportResolver` | Tailscale/Cloudflare/direct transport selection (TTL cache, cooldown/fail-streak, early-death accounting) + connection-label state. |
| `TmuxProbes` | Low-level tmux/shell primitives: `kickRedraw` + client refresh, pane-geometry probe, session/transcript existence probes, shell-prompt wait. |
| `RemoteExec` (fn) | `execReadWithWatchdog` — one-shot exec with a hard wall-clock bound. The same file also holds the new `RemoteExec` / `ExecChannels` interfaces (see *Communication seams* below). |
| `ServerHealthService` | Launcher reachability health + per-server latency polling. |
| `GitStatusService` | Per-session git working-dir status (branch + dirty/ahead/behind). |
| `UsageService` | 5h/week usage %, reset mins, usage tokens, ccusage polling. |
| `TranscriptService` | Transcript streams (+ `transcriptLock`), context-token tracking, the shared streamd daemon. |
| `StatusService` | Per-session activity state (`updateActivity`) + OMC remote-status pollers. |
| `NotificationService` | Input-prompt detection, needs-input notifications, the Stop-hook watcher (and, with `comm_hooks`, the hook-event and forget.log lines), login flow. (No offline input queue since kolo 5 of batch C: input without a route is never sent later.) |
| `HookCommands` / `HookEventReducer` | Server-side text of the background hooks (`hook-ev.sh`, settings merge, remove, status) and the pure reducer of their events; see [connection.md](connection.md#per-server-bootstrap-and-background-hooks). |
| `TerminalIOService` | Per-session output ring buffer + pty size tracking. |
| `ClaudeControlService` | User input send path (only into an ACTIVE tab; without a route: "Not connected — not sent", no queue) + Claude slash-control (model/effort/login/escape). |
| `RemoteOpsService` | tmux copy-mode scroll + SFTP upload/download. |
| `SessionPersistenceService` | Shared `sessions.json`, restore-script install, real-session-id refresh, forget/rename. |

### Communication seams and their wiring

New interfaces under `session/service/`, each with a production stub that keeps
today's behaviour so the pieces behind them (transport pool, exec routing,
reconcile, hooks) could be built in parallel without touching each other's files. They are reached through nullable
`@Volatile` slots on `ConnectionRegistry` (`execSessions`, `execChannels`,
`remoteExec`, `reconciler`, `bootstrap`; null = legacy) and the process-wide
`CommFlags`. The transport pool (`ServerTransportPool`, `SingleFlight`) is live behind
`comm_pool`. `SessionOrchestrator.wireExecLayer()` fills ALL the slots in one step in its
constructor (the real classes: `PoolExecTransportManager`, `GatedExecChannels`,
`PooledRemoteExec`, `DefaultServerBootstrap`, `ExecServerReconciler`) and connects the
notification service's `HookEventSink` and `PersistenceClosures`; the flags decide per server
whether anything uses them (connection.md "Wiring in the orchestrator"); the orchestrator also watches
each server's `exec` flag, so the kill switch closes (or restarts) the exec transport at run time. The stubs stay for
tests and as the documented legacy behaviour. The services reach
exec through the routing helpers of `ConnectionRegistry` (`execRun`, `execRead`,
`useChannel`) and `SshSessionHelper.withSession` (see connection.md "Exec routing"), which
only deviate from legacy when the slots are wired and the `exec` group is on for the server, or when shells are
still packed beyond the legacy 3 per transport (the kill switch): then every channel on a tab transport is counted
(connection.md "Fallback with headroom").

| Interface (file) | Stub | To be implemented in |
|------------------|------|----------------|
| `RemoteExec.run(serverId, cmd, stdin, totalMs, kind, paceStdin): ExecResult` (`RemoteExec.kt`) | `PooledRemoteExec` (typed errors, spill retry, never a partial result; works over either channel implementation) | transport pool |
| `ExecChannels.use(serverId, kind, acquireTimeoutMs) { ch -> }` and `withPermit(...) { session -> }` (`RemoteExec.kt`) | `UngatedExecChannels` (no permits); real: `GatedExecChannels` (permits per kind, void with the transport generation, LONG fallback) | transport pool |
| `ExecTransportManager` / `ExecSessionProvider`, `LinkState` (`ExecTransportManager.kt`) | `PooledExecTransportManager`; real: `PoolExecTransportManager` (one EXEC transport per server, supervisor, backoff, forwards) | transport pool |
| `ServerTransportPool.setKeepAlive(ms)`, `ExecTransportManager.setKeepAlive(ms)` | pool: applies to every live transport and to later ones; manager stub: records it | transport pool |
| `ServerBootstrap` (`ServerBootstrap.kt`) | `NoopServerBootstrap`; implemented by `DefaultServerBootstrap`, wired (built on first use) by the orchestrator | wiring |
| `ServerReconciler` (`ServerReconciler.kt`) | `LegacyServerReconciler` (executes today's forget rule); real one: `ExecServerReconciler` (one `reconcileAll` exec per server, see connection.md "Reconcile") | reconcile |
| `PersistenceClosures.recentlyClosedLocally(serverId, name, withinMs)` | `None`; `SessionPersistenceService` implements it | reconcile |
| `HookEventSink` | `NoOp`; `NotificationService.hookSink` calls it; the orchestrator's sink re-arms the notify latch and asks the reconciler for `checkUuid` / `checkClosed` | hook-event reducer, wired in the orchestrator |
| `HookCapContract` (CAP: marker in the config dir's settings.json, no `disableAllHooks`; `claude-remote-hookev.installed` is audit only) | executable reference | reconcile reads, hooks install writes |
| `CommLogFormat` (`data/60s` tail, `Attach` line) | final | emitted by the orchestrator (`data/60s exec=` = reconcile execs of the minute; `Attach` per connect and reconnect) |

Services take their dependencies by constructor injection (the shared
`CoroutineScope`, `ConnectionRegistry`, `TabManager`, an `isBackground` getter,
and lambdas bridging to sibling services or facade callbacks). Each service
keeps its own lock private and exposes lock-holding methods — the raw
`ConcurrentHashMap`s and monitors are never shared across responsibilities.

## Module & file conventions

To keep the modular structure from decaying back into god-files, new code
should follow these rules:

- **Soft limit ~500 lines per file.** Past that, split — no god-classes, no
  2000-line Compose files. UI: one file = one screen or one contiguous area
  (top bar, control bar, side panel…); non-`@Composable` logic (parsing,
  render-model building) goes in its own `*Model.kt` / `*Parsing.kt`.
- **One class = one responsibility.** Stateful logic that coordinates the
  session lives behind a dedicated service in `session/service` (or
  `session/{status,transcript,notify}`), not inline in the orchestrator.
- **Shared mutable state stays behind its owning service, guarded by that
  service's private lock.** Never expose a raw shared `ConcurrentHashMap` /
  monitor across responsibilities; expose intent-revealing methods instead.
- **Package layout:** `model` / `connection` / `storage` / `util` (core),
  `session` + `session/{service,status,transcript,notify}` (domain),
  `ui` + `ui/{components,theme}` (UI). New code belongs in the existing package
  that matches its responsibility.
- **Wiring is manual, in the composition root** (`Main.kt` / `MainActivity` /
  `App.kt`); services receive their dependencies via the constructor. No DI
  framework.
- **Everything shared stays in `commonMain`;** platform specifics go behind
  `expect`/`actual`.

## Kotlin Multiplatform Structure

```
shared/src/
├── commonMain/    # all shared logic (ui/, session/ + session/service, …)
├── androidMain/   # 3 actual implementations
│   ├── MoshManager.kt          # ProcessBuilder with bundled binary
│   ├── PlatformPreferences.kt  # SharedPreferences wrapper
│   └── PlatformBackHandler.kt  # OnBackPressed callback
└── desktopMain/   # 3 actual implementations
    ├── MoshManager.kt          # System mosh command
    ├── PlatformPreferences.kt  # Properties file wrapper
    └── PlatformBackHandler.kt  # No-op
```
