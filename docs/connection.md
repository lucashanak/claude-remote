# Connection Layer

The connection layer manages SSH, Mosh, and tmux — the three protocols that make remote Claude Code control possible.

## Overview

```
App ──SSH──► Remote Server ──shell──► tmux session ──► claude CLI
        │
        └─(future)─ Mosh ──► same flow
```

All connections go through `SessionOrchestrator`, which coordinates `SshManager`, `TmuxManager`, and `MoshManager`.

## SSH Manager

**File:** `shared/src/commonMain/.../connection/SshManager.kt`
**Library:** JSch 0.2.21

### Connection Flow

```
1. Create JSch instance
2. Configure authentication:
   ├─ PASSWORD: session.setPassword(password)
   └─ KEY: jsch.addIdentity("key", privateKeyBytes, null, null)
3. Host key verification (TOFU):
   ├─ First connect: trust & save fingerprint to ServerStorage
   └─ Subsequent: compare against saved fingerprint, reject on mismatch
4. Set session config:
   ├─ StrictHostKeyChecking = "no" (handled by custom verifier)
   ├─ PreferredAuthentications = "publickey,password"
   └─ ServerAliveInterval = 15 (keepalive)
5. session.connect(timeout)
6. Set up port forwarding (if configured):
   ├─ Local ("L"): session.setPortForwardingL(localPort, remoteHost, remotePort)
   └─ Remote ("R"): session.setPortForwardingR(remotePort, remoteHost, localPort)
7. Open shell channel:
   ├─ channel.setPtyType("xterm-256color")
   ├─ channel.setPtySize(cols, rows, 0, 0)
   └─ channel.connect()
8. Start read loop (coroutine):
   └─ Read from channel.inputStream → decode UTF-8 → onOutput(data)
9. On EOF/error → onConnectionLost()
```

### Key Methods

```kotlin
// Establish connection
suspend fun connect(
    server: SshServer,
    onOutput: (String) -> Unit,       // Terminal output stream
    onConnectionLost: () -> Unit       // Disconnection callback
): Session

// Send text input (user typing)
fun sendInput(data: String)

// Send raw bytes (mouse events, special keys)
fun sendBytes(data: ByteArray)

// Resize PTY (terminal dimensions changed)
fun resize(cols: Int, rows: Int)

// Upload file via SFTP
suspend fun uploadFile(
    localPath: String,
    remotePath: String,
    onProgress: (Float) -> Unit
)

// Clean disconnect
suspend fun disconnect()
```

### Output Streaming

The read loop runs in a coroutine, reading chunks from the SSH channel's input stream:

```kotlin
val buffer = ByteArray(8192)
while (!channel.isClosed) {
    val bytesRead = inputStream.read(buffer)
    if (bytesRead > 0) {
        val text = String(buffer, 0, bytesRead, Charsets.UTF_8)
        onOutput(text)
    } else if (bytesRead == -1) {
        break  // EOF → connection lost
    }
}
onConnectionLost()
```

### Host Key Verification (TOFU)

Trust On First Use — the app stores host key fingerprints and warns on changes:

```kotlin
class TofuHostKeyRepository(private val storage: ServerStorage) : HostKeyRepository {
    override fun check(host: String, key: ByteArray): Int {
        val fingerprint = computeFingerprint(key)
        val saved = storage.loadKnownHosts()[host]
        return when {
            saved == null -> {
                storage.saveKnownHost(host, fingerprint)
                OK  // First time — trust
            }
            saved == fingerprint -> OK         // Match
            else -> CHANGED                    // Mismatch — reject
        }
    }
}
```

## Tmux Manager

**File:** `shared/src/commonMain/.../connection/TmuxManager.kt`

Manages tmux sessions and remote folder operations using SSH exec channels (separate from the shell channel).

### Session Management

```kotlin
// List all tmux sessions on the server
suspend fun listSessions(session: Session): List<TmuxSession>
// Parses: tmux list-sessions -F "#{session_name}|#{session_windows}|..."

// Kill a named session
suspend fun killSession(session: Session, sessionName: String): Boolean
// Runs: tmux kill-session -t 'sessionName'

// Build attach-or-create command
fun buildAttachCommand(sessionName: String): String
// Returns: tmux new-session -A -s 'name' \; set-option -g mouse on
```

### Remote Folder Operations

```kotlin
// List directories at path
suspend fun listFolders(session: Session, path: String = "~"): List<String>
// Runs: ls -1 -d path/*/ 2>/dev/null

// Check if folder exists, create if not
suspend fun ensureFolder(session: Session, path: String): Boolean
// Runs: mkdir -p path && echo OK
```

### TmuxSession Data

```kotlin
data class TmuxSession(
    val name: String,       // e.g., "claude-prod-myproject"
    val windows: Int,       // Number of windows
    val attached: Boolean,  // Currently attached by another client
    val created: String     // Creation timestamp
)
```

### Session Naming Convention

Sessions are named: `claude-{serverName}-{folderName}`

Example: server "prod", folder "~/myproject" → `claude-prod-myproject`

## Mosh Manager

**File:** `shared/src/commonMain/.../connection/MoshManager.kt` (expect class)

Mosh (Mobile Shell) provides UDP-based connections that survive network changes and high latency. Currently implemented but not actively used (SSH is the default).

### Android Implementation

**File:** `shared/src/androidMain/.../connection/MoshManager.kt`

Uses a cross-compiled `mosh-client` binary (built by `build-mosh.sh`):

```kotlin
// 1. SSH exec to start mosh-server
val output = sshExec(session, "mosh-server new")
// Parse: MOSH CONNECT <port> <key>
val (port, key) = parseMoshConnect(output)

// 2. Launch mosh-client process
val process = ProcessBuilder(
    moshBinaryPath,    // jniLibs/arm64-v8a/libmosh.so
    server.host,
    port.toString()
).apply {
    environment()["MOSH_KEY"] = key
}.start()

// 3. Read output in coroutine
launch {
    process.inputStream.bufferedReader().forEachLine { line ->
        onOutput(line)
    }
}
```

### Desktop Implementation

**File:** `shared/src/desktopMain/.../connection/MoshManager.kt`

Uses system-installed `mosh` command:

```kotlin
val process = ProcessBuilder(
    "mosh",
    "--ssh=ssh -p ${server.port}",
    "${server.username}@${server.host}",
    "--", startupCommand
).start()
```

## SessionOrchestrator Integration

**File:** `shared/src/commonMain/.../session/SessionOrchestrator.kt`

The orchestrator ties all connection components together:

```kotlin
suspend fun launchSession(
    server: SshServer,
    folder: String,
    mode: ClaudeMode,
    model: ClaudeModel,
    connectionType: ConnectionType,
    tmuxSessionName: String
): ClaudeSession {
    // 1. Create session object
    val session = ClaudeSession(...)
    tabManager.addTab(session)

    // 2. Connect SSH
    val sshSession = sshManager.connect(server, onOutput = { data ->
        outputBuffers[session.id]?.append(data)
        onTerminalOutput?.invoke(session.id, data)
    }, onConnectionLost = {
        handleDisconnect(session.id)
    })

    // 3. Wait for shell prompt
    delay(500)

    // 4. Run startup command
    if (server.startupCommand.isNotEmpty()) {
        sshManager.sendInput(server.startupCommand + "\n")
        delay(300)
    }

    // 5. Launch tmux + claude
    val launchCmd = ClaudeConfig.buildTmuxLaunchCommand(
        tmuxSessionName, folder, mode, model
    )
    sshManager.sendInput(launchCmd + "\n")

    // 6. Mark active
    tabManager.updateTabStatus(session.id, SessionStatus.ACTIVE)
    return session
}
```

### Auto-Reconnect

```kotlin
private suspend fun handleDisconnect(sessionId: String) {
    val session = tabManager.getTab(sessionId) ?: return
    tabManager.updateTabStatus(sessionId, SessionStatus.DISCONNECTED)
    emitWarning(sessionId, "Connection lost. Reconnecting...")

    for (attempt in 1..3) {
        delay(attempt * 2000L)  // 2s, 4s, 6s
        try {
            sshManager.connect(session.server, ...)
            sshManager.sendInput(
                TmuxManager.buildAttachCommand(session.tmuxSessionName) + "\n"
            )
            // Replay buffer
            onTabSwitched?.invoke(sessionId, outputBuffers[sessionId]?.toString())
            tabManager.updateTabStatus(sessionId, SessionStatus.ACTIVE)
            emitSuccess(sessionId, "Reconnected!")
            return
        } catch (e: Exception) {
            emitWarning(sessionId, "Attempt $attempt failed: ${e.message}")
        }
    }
    emitError(sessionId, "Could not reconnect after 3 attempts")
}
```

### Output Buffering

Each session has a 256KB ring buffer for:
- **Tab switching** — Replay buffered output when switching to a background tab
- **Reconnection** — Restore terminal state after SSH drops

## Port Forwarding

Configured per server in `SshServer.portForwards`:

```kotlin
// Local forwarding (access remote service locally)
PortForward(type = "L", localPort = 8080, remoteHost = "127.0.0.1", remotePort = 3000)
// Result: localhost:8080 → remote:3000

// Remote forwarding (expose local service to remote)
PortForward(type = "R", localPort = 5000, remoteHost = "127.0.0.1", remotePort = 5000)
// Result: remote:5000 → localhost:5000
```

Set up during SSH connection, before the shell channel opens.

## Security

- **Credentials** are stored in platform-specific encrypted storage (SharedPreferences / Properties file)
- **SSH keys** support PEM format private keys
- **Host keys** use TOFU (Trust On First Use) — stored per host in `ServerStorage`
- **No external servers** — all traffic goes directly to user's SSH server
- **Keepalive** — `ServerAliveInterval=15` prevents idle disconnections
