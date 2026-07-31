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
| `RemoteExec` (fn) | `execReadWithWatchdog` — one-shot exec with a hard wall-clock bound. |
| `ServerHealthService` | Launcher reachability health + per-server latency polling. |
| `GitStatusService` | Per-session git working-dir status (branch + dirty/ahead/behind). |
| `UsageService` | 5h/week usage %, reset mins, usage tokens, ccusage polling. |
| `TranscriptService` | Transcript streams (+ `transcriptLock`), context-token tracking, the shared streamd daemon. |
| `StatusService` | Per-session activity state (`updateActivity`) + OMC remote-status pollers. |
| `NotificationService` | Input-prompt detection, needs-input notifications, the Stop-hook watcher, login flow, offline input queue. |
| `TerminalIOService` | Per-session output ring buffer + pty size tracking. |
| `ClaudeControlService` | User input send path + offline queue + Claude slash-control (model/effort/login/escape). |
| `RemoteOpsService` | tmux copy-mode scroll + SFTP upload/download. |
| `SessionPersistenceService` | Shared `sessions.json`, restore-script install, real-session-id refresh, forget/rename. |

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
