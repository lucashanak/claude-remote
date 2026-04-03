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

## Kotlin Multiplatform Structure

```
shared/src/
├── commonMain/    # ~32 files — all shared logic
├── androidMain/   # 3 actual implementations
│   ├── MoshManager.kt          # ProcessBuilder with bundled binary
│   ├── PlatformPreferences.kt  # SharedPreferences wrapper
│   └── PlatformBackHandler.kt  # OnBackPressed callback
└── desktopMain/   # 3 actual implementations
    ├── MoshManager.kt          # System mosh command
    ├── PlatformPreferences.kt  # Properties file wrapper
    └── PlatformBackHandler.kt  # No-op
```
