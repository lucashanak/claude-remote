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
   └─ ServerAliveInterval = 10 s, 2 misses (keepalive; the pool-wide value from setKeepAlive)
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

## Transport pool and exec transport

**Files:** `connection/ServerTransportPool.kt`, `connection/SingleFlight.kt`,
`session/service/ExecTransportManager.kt`, `session/service/RemoteExec.kt`.

One `ServerTransportPool` per server holds the shared SSH transports (jsch `Session`s).
A transport has a **role** and the pool key is `(host, port, viaProxy, role)`:

| Role | Carries | Capacity |
|------|---------|----------|
| `SHELL` | the tabs' shell channels | 8 only when `comm_pool` and `comm_exec` are on AND the server's exec transport really runs (the pool's `execWired` predicate, default `false`); otherwise 3. With the transient exec channels still on the tab transports, 8 shells plus their execs would overflow sshd's default `MaxSessions` 10 |
| `EXEC` | the transient exec/SFTP channels of the services and the server's port forwards | one transport per key; its channels are budgeted by `GatedExecChannels` |

**Leasing a shell transport** (`lease`, called by `SshManager.connect`):
- `comm_pool` **off**: the legacy lease, unchanged: one handshake at a time under the pool
  mutex, 3 shells per transport.
- `comm_pool` **on**: under the mutex the pool takes a free slot of a live transport (slots
  promised to waiters of a pending handshake count as taken) or joins a pending handshake;
  the handshake itself runs outside the mutex in a pool-owned coroutine (`SingleFlight`). A
  reconnect wave of N tabs therefore starts at most `ceil(N / cap)` handshakes, in parallel.
  A cancelled initiator neither cancels the handshake for the others nor leaks its session:
  when the handshake finishes with nobody left it is disconnected. A failed handshake fails
  every waiter at once and is forgotten.
- **Spill**: when the server refuses a shell channel (`MaxSessions`: jsch reports
  `JSchException("channel is not opened.")` with `exitStatus` 1 or 2), `SshManager` marks the
  transport full (`reportFull`) and leases once more, which lands on another transport. Siblings
  on the full transport are untouched.

**Keepalive**: `setKeepAlive(ms)` on the pool (also reached through
`SshManager.setKeepAliveInterval` for a leased session and `ExecTransportManager.setKeepAlive`)
applies to every live transport and to transports connected later; `0` turns it off. This holds
also with `comm_pool` off: the lease is the old one, but a transport connected in the background
starts with the background interval.

**Log lines** (parsed by the metrics extractor, so keep the shape):
`New transport to <host>:<port> role=shell|exec route=lan|tunnel|cf|unknown (<n> in pool)`,
`Awaited in-flight transport (<ms>ms)`, `Transport for <server>: <choice> -> <host> (cf=<bool>) route=...`,
`Exec transport <server> up via TS|CF|DIRECT in <ms>ms route=...`,
`Exec transport <server> down after <ms>ms via TS|CF|DIRECT`. `route` says which path the
socket really took: `cf` (Cloudflare tunnel), `tunnel` (the socket's local or remote address is in
Tailscale's 100.64.0.0/10 or fd7a:115c:a1e0::/48, or its interface is a tun/tailscale one), `lan`
(anything else, loopback included), `unknown` (the local address could not be read). A Tailscale
endpoint reached over the LAN with the VPN off is `lan`, not `tunnel`.

### Exec transport

`PoolExecTransportManager` (the real `ExecTransportManager`) keeps one `EXEC` transport per
server for as long as the server has a tab (`ensure` / `close`). One supervisor coroutine per
server resolves the endpoint with the tabs' TS/CF/AUTO decision, leases the transport, registers
the server's L/R port forwards on it, publishes `LinkState.Up(via)`, polls it every 5 s (and at
once when a channel failure is reported), and on death logs, bumps `generation`, feeds the
Tailscale strike rule and reconnects after a backoff.

- States: `Up(via)`, `Connecting`, `Down` (dead, retried with backoff), `DownNetwork` (network
  gone), `Closed`.
- `DownNetwork` heals by itself: it retries every 10 s (30 s in the background), at once on
  `onNetworkAvailable`, and on demand when someone `await`s or asks for the `session`.
- `Closed` is never revived by `await`, `session` or the network callbacks; only an explicit
  `ensure` re-opens it.
- Backoff: 0, 1, 2, 4, 8, 16, 30 s in the foreground; twice that, at least 5 s and at most 60 s,
  in the background (`background = true`). A transport that lived 60 s resets it.
- Strikes: only a Tailscale transport that died within 30 s of coming up counts
  (`TransportResolver.noteServerEarlyDeath`, which skips deaths right after a network teardown
  we caused), and a failed connect attempt counts like a tab's failure, except while the network
  is gone (`networkLost`): then every attempt fails and none says anything about Tailscale.
  A Tailscale transport that stays up past the 30 s window clears the strike streak
  (`TransportResolver.noteServerTransportStable`). Cloudflare is the last resort and never strikes.
- A `DownNetwork` nudge (from `session()`/`await()`) is limited to once per 2 s in the foreground
  and once per half retry period in the background, so a poller does not turn the slow
  background pace into a connect every 2 s.
- `generation(serverId)` increases on every death, network loss and close.
- Port forwards: while the exec manager owns a server's forwards
  (`ServerTransportPool.setExecForwardsOwned`) a shell session does not register them; the
  manager registers them again after every reconnect. They therefore keep working with zero tabs.
  Known limit: ownership starts at `ensure`, not when the transport is `Up`, so while the exec
  transport is down in a backoff (up to 30 s in the foreground, 60 s in the background) the
  forwards are not available even though the tabs run. Shell sessions do not bind them in the
  meantime on purpose: a forward bound on a shell would collide with the exec transport's bind
  when it comes back, and would die with that shell's transport without anyone rebinding it.

### Channel budget

`GatedExecChannels` (the real `ExecChannels`) enforces the per-server budget so the exec
transport never reaches the sshd's `MaxSessions` (default 10): SHORT 6 permits (SFTP shares
them), LONG 2, 8 channels in total. Permits belong to a transport generation: when the transport
dies the old permits are void and waiters re-evaluate against the new transport. Waiting longer
than `acquireTimeoutMs` is `GATE_TIMEOUT`. With no exec transport, SHORT/SFTP fail with `NOT_UP`
after a short wait (the caller falls back to its tab session); LONG waits 3 s and then opens on a
live shell transport under its own limit of 2, so the notify watcher and the transcript daemon do
not go dark while the exec transport reconnects. A fallback stream is counted against the shell
transport it borrowed (`reserveChannel`, so a shell lease cannot fill the transport behind its
back) and is ended with EOF when the exec transport comes `Up` again, so its owner reopens it
there. With the exec transport in `Down` or `Closed` a SHORT call answers `NOT_UP` at once instead
of waiting; only `Connecting` is waited for.

`PooledRemoteExec.run` runs one command on a gated channel, bounded by a wall-clock watchdog that
is independent of the caller. The bound `totalMs` is an absolute deadline: waiting for a permit
and for the transport spends it (`acquireTimeoutMs = min(5 s, what is left)`). It never returns a partial result: if the transport died before the
command reported an exit status the call is `ExecUnavailable(IO)`; `channel is not opened.` with
exit status 1 or 2 is retried once inside the same bound before it surfaces as `REFUSED`; the
connect never waits longer than what is left of the bound; paced stdin stops once the remote side
has closed.

## Communication redesign: flags and seams

Experimental groups of the communication redesign. `comm_pool` acts on the
transport pool (previous section); the exec transport manager and the channel budget exist
but nothing constructs them in the app yet (the shell cap therefore stays 3 until the exec
transport is wired). **The other groups are still mostly stubs that delegate to the
behaviour described above**, so switching such a flag changes nothing yet; the per-server
bootstrap and the hook-event path (below) are implemented but not wired into the connect
path in this build, except that `comm_hooks` already decides whether the notify watcher
also follows the hook events. Settings -> *Connection (experimental)* edits the flags (each
label says what the switch does today, `comm_pool` included: it is read by the connection
layer per server; a switch that nothing reads says "no effect in this build"; every switch
shows when an implication turned its group off). `CommFlags.deviceDefaults`
(`StateFlow<Effective>`) is published by the Android `Application`, desktop `Main`
and Settings on every change; code that acts for one server uses
`CommFlags.effectiveFor(serverId)` / `effectiveFlow(serverId)`, never
`deviceDefaults`.

| Flag | Covers | Default |
|------|--------|---------|
| `comm_pool` | pool shell/exec role split, single-flight leases, spill on a refused channel, exec transport manager | on |
| `comm_exec` | exec routing, per-server reconcile, bootstrap, exec channel gate | on |
| `comm_hooks` | background state from hooks | on |
| `comm_lazy` | lazy attach | **off** |
| `comm_fg_deadlink` | fast foreground dead-link detection | **off** |
| `comm_warm_tabs` | warm tab count, clamped to 5-8 (a clamp is logged) | 6 |
| `comm_bg_park_grace_s` | background park grace, clamped to 10-3600 s (a clamp is logged) | 120 |

**Implications** (an invalid combination is downgraded and logged as
`CommFlags: <x> forced off because <y>`): `exec => pool`; `hooks => pool and exec`;
`lazy => pool and exec and hooks`; `fg_deadlink => pool`. Shell channels per
transport: 8 only with `pool and exec`, otherwise today's 3.

**Precedence** (what wins when they disagree), strongest first:
1. the legacy set (`comm_legacy_servers`, server ids in the app preferences, no UI
   yet): every group is off for those servers, whatever anything else says;
2. server-side force-off for that server (below);
3. the Settings switches, then the implications above.

**Rollback ladder** (what to try, first resort first):
1. Turn the group off in Settings on the device.
2. On the server, `~/.claude-remote/client-flags.json`, e.g.
   `{"off":["lazy","hooks"]}` (group names `pool exec hooks lazy fg_deadlink`),
   turns groups off for every device talking to that server. The bootstrap applies
   it per server through `CommFlags.applyServerOff(serverId, set)`; it can only turn
   groups off. Whatever is forced off for a server is logged once per change as
   `[<serverId>] CommFlags: <x> forced off because ...`.
3. Put the server in the legacy set on a device (all groups off, no matter what).
4. Revert the commit.

**Hook installation is an explicit action**: nothing is written to a server's
Claude settings unless the user asks, per server. The request is a one-shot event
(`CommFlags.hookInstallRequests: SharedFlow<serverId>`, raised with
`CommFlags.requestHooksInstall(serverId)`, which returns `false` and drops the
request when nobody listens); nothing is stored, so a request made while nobody
listens is gone and cannot turn into a later install. Settings -> *Connection
(experimental)* has an *Install hooks on server* / *Remove* pair per server (see
"Per-server bootstrap and background hooks" below). Removal is an event too
(`HookInstallControl.requestRemove`). The emergency stop that needs no settings
rewrite is the file `~/.claude-remote/hookev.off` on the server.

**Log formats** (`CommLogFormat`): the `data/60s:` line gets
` | attached=<n> warm=<n> parked=<n> exec=<n>` appended at the END (prefix
unchanged; parse `key=value` tolerantly; `warm/parked/exec` are 0 until their
features land). The attach line is
`Attach <sid> kind=cold|warm ms=<n> phase=shell=<ms>,prompt=<ms>,decide=<ms>,attach=<ms>,firstByte=<ms>`
(the phase value is a list of `name=<ms>` items; absent phases are omitted, no
phases at all is `phase=-`).
The remote per-device log (`~/.claude-remote/logs/<install>.log`) is capped at 40 MB
and cut back to its last 36 MB on a line boundary (at the measured 3.75 MB/day that
is about 9.6 days of history after a cut).

**Contract details the implementations rely on** (`RemoteExec.kt`, `HookCapContract.kt`):
- `RemoteExec.run` returns a complete result or throws the typed
  `ExecUnavailable(reason)`, never null and never a partial result: `NOT_UP`,
  `GATE_TIMEOUT` (no permit in time), `TIMEOUT` (the wall-clock bound, enforced
  independently of the caller, so cancelling the caller does not lift it), `REFUSED`
  (the server refused the channel) and `IO` (stdin/stdout broke). `kind` must be
  SHORT or LONG.
- `ExecChannels.use` is scoped: the channel is disconnected and the permit released
  on exit, permits reset with the transport generation. `withPermit` holds one permit
  for a block that opens its own channels and gets the live session.
- CAP (hooks active for a pane) = the shallowest claude process in the pane's process
  subtree (the pane's own process included) runs with a config dir
  (`CLAUDE_CONFIG_DIR` from `/proc/<pid>/environ`, default `~/.claude`) whose
  `settings.json` carries the marker `claude-remote-ev v`, and neither that
  `settings.json` nor the `settings.local.json` next to it sets `disableAllHooks`.
  Claude Code reloads settings while it runs (an added or removed hook is live in every
  running session within about a second), so the start time of the process does not
  matter. A claude process has comm `claude`, or a bare version number (`2.1.316`, the
  name of the versioned binary) whose `/proc/<pid>/exe` lies under `.../claude/versions/`
  (the bundled `rg` has the same comm and another exe). The per-config-dir
  `claude-remote-hookev.installed` file is an audit record of the install time, not a
  condition. The file `~/.claude-remote/hookev.off` (the owner's switch that silences the
  hook script) makes CAP "no" on that host. CAP says what the host does; whoever trusts the
  hook stream must also multiply it by `CommFlags.effectiveFor(serverId).hooks`. Unknown
  inputs give "unknown", never "no". Limits: project-level and managed
  settings are not examined.

### Exec routing

When the exec seam is wired (the `execSessions`/`remoteExec` slots are set) and the `exec`
group is on for a server, everything that is not the attached terminal talks to that
server through the server's exec transport instead of borrowing a tab's shell:

- `execRun` / `execRead` / `useChannel` run a command, a read-only command or a scoped
  channel through `RemoteExec` / `ExecChannels`. When the exec transport is not UP
  (`NOT_UP`) they fall back to a live tab session of the server; any other
  `ExecUnavailable` (timeout, refused channel, io, permit wait) is thrown, never retried
  on another transport. `ConnectionRegistry.liveServerSession` / `anyLiveSession` never
  hand out the exec session, because it would be used without a permit: they return a
  connected tab session only. A permanent stream (notify watcher, stream daemon) takes
  its channel from `useChannel` with the long kind; a one-off operation (usage poll,
  upload, log shipping) uses the short kind with a total time limit, so the two long
  permits are never used up by work that ends.
- With routing off the behavior is the one from before, except for these exact items:
  keepalive also reaches the pooled and exec transports; the log shipper trims with
  `tail -n +2`; the stream daemon forgets its bootstrap memo when it ends without a
  `hello`; the new by-server-id overloads exist (nothing calls them).
- `SshSessionHelper.withSession` runs its block on the exec session under ONE short permit
  (so every one-off user is inside the per-server channel budget), then on a pooled
  transport, and only then on a fresh connection, unless `allowFresh = false`, where it
  throws `ExecUnavailable(NOT_UP)` instead. Only `NOT_UP` falls through to those
  transports; a permit wait that timed out is thrown to the caller. The transcript and status pollers use
  `allowFresh = false` under exec routing: a tick without a live transport is skipped.
- Moved over: usage and rate-limit polls, server latency, git status, upload / download /
  stat / scroll, tmux refresh-client, the stream daemon channel (long permit), log
  shipping (optional `execFlush`, short permit), rename and close of a tab (a parked tab can be renamed
  and closed), restore-service install and manifest push by server id.
  A server whose tabs are all parked reads as ONLINE while its exec transport is UP.
- Still on the tab's own shell: terminal input and resize, `kickRedraw`'s resize, attach
  and reconnect code.

### Reconcile

One `ServerReconciler` loop per server (every 15 s, foreground only; on-demand rounds
for `checkClosed` / `checkUuid` / `capability`) runs ONE exec for all tabs of the server
(`ManifestCommands.reconcileAll`). The output is a list of sections, each closed by
`__END_<section>__ rc=<status>`; a section that is missing (output cut off) or failed is
UNKNOWN and unknown never forgets anything. Sections: the tombstones and manifest under
the shared lock, `tmux list-sessions`, ONE `list-panes -a` (restricted to the tabs' names
by whole-name match), ONE `ps` table, and per claude-named process its exe, the CAP
facts and (when it has a `sessions/<pid>.json`) the session id if its transcript exists.
Per tab the result decides:

- **forget**: tombstoned AND absent from `list-sessions` AND at least one other live
  `claude-server-*` session exists (a dead tmux server never counts as proof);
- **adopt the real session id**: the shallowest process of the pane's subtree that has a
  session file wins (the manifest is the weaker fallback); an id already owned by another
  tab is refused;
- **CAP**: per the contract above.

`checkClosed` waits, looks, and when the session is still present looks once more 5 s
later. This replaces the per-tab 15 s loop (two execs per tab) when the reconciler is
wired. A tab is forgotten only while it is registered, the forget runs to the end even
when it unregisters the last tab of the server (which stops the loop), and a forget that
throws leaves the tab registered for the next round. `checkUuid` only adopts an id; it
never forgets. Names that tmux no longer lists are dropped from the pane filter.
`PersistenceClosures.recentlyClosedLocally(serverId, name, withinMs)` answers
whether THIS device closed the tmux session (recorded by `forgetSession`).

## Per-server bootstrap and background hooks

### Bootstrap (`ServerBootstrap.kt`)

`DefaultServerBootstrap` brings one server to the state the app expects with **one
exec** and remembers the result. In order, the script runs: (1) read
`~/.claude-remote/client-flags.json` (bounded, delimited); (2) the legacy Stop-hook
ensure, unchanged; (3) hooks v2: **read their state**, or install them when this run
carries an explicit request; (4) the streamd script. Output sections are delimited
(`===CRB ...===`), and the run counts as complete only with its end marker.

- `ensure(serverId)` is single-flight per server in the bootstrap's own scope (a
  cancelled caller does not cancel the work) and memoized for 6 h; `invalidate`
  forgets the memo (the notify watcher does it after a reconnect and then ensures
  again, which repairs a settings file rewritten meanwhile).
- A failed run (no exec, timeout, truncated output) backs off 10 s, 20 s, ... up to
  5 min; `ensure` inside the backoff returns at once and never throws. A step that
  reports its own failure (`HOOK_FAILED`, `HOOKS_V2_FAILED`) is logged, not retried
  on a timer.
- Transport: the exec channel first; when it is not usable (any reason but a
  timeout) a fallback over a tab's session.
- `refreshFlags(serverId)` re-reads only the flags (meant for every 10 min in the
  foreground).
- **Force-off is decided on the server**: the same script reads `client-flags.json`
  with python and skips the hooks v2 install when `hooks`, `pool` or `exec` is in
  `off` (`HOOKS_V2_SKIPPED_OFF`); the client applies the same list through
  `CommFlags.applyServerOff`. A file that is not valid JSON, or has the wrong shape,
  switches **nothing** off and is logged as `CommFlags: client-flags.json
  unparseable`. An absent file lifts an earlier force-off. A truncated output never
  changes the flags.
- Log: `Server bootstrap <name>: flags=<off|none|unparseable> hooks=<HOOK_*>
  hooksV2=<state>[/<result>] streamd=<STREAMD_*> (<ms>ms)`.

### Hooks v2

Installed only on request. One settings.json entry per event, each command carrying
the marker `claude-remote-ev v<N>` (`N` = `HookCommands.ENTRY_VERSION`):

| Hook | Matcher | `hook-ev.sh` argument |
|------|---------|-----------------------|
| UserPromptSubmit | - | `work` |
| PreToolUse | AskUserQuestion | `ask` |
| PostToolUse | AskUserQuestion | `askdone` |
| PermissionRequest | - | `perm` |
| Stop | - | `stop` |
| SessionStart | - | `start` |
| SessionEnd | - | `end` |

The legacy `Stop` entry (`claude-remote-notify`) is never modified: old app builds
rewrite it on any difference. The v2 `Stop` entry is a separate item with another
marker, so the legacy ensure leaves it alone (mixed fleets stay byte-stable).

The command in settings.json is
`: claude-remote-ev v1; H="$HOME/.claude-remote/hook-ev.sh"; [ -f "$HOME/.claude-remote/hookev.off" ] || { [ -x "$H" ] && "$H" <EV> >/dev/null 2>&1; }; exit 0`:
nothing can reach stdout (stdout of UserPromptSubmit and SessionStart is added to the
model's context), the status is always 0 (2 would block a prompt or a tool), and
`hookev.off` stops everything immediately without touching settings. Claude reloads
settings while running (measured with Claude Code 2.1.316: a hook added to the file
fired in an already running session within about a second; another version may behave
differently), so an install or removal reaches every live session of every account on
the server within about a second.

**Install respects the effective flags.** Settings keeps the install button disabled
(with the reason shown) while hooks are off for the server - by this device's
`comm_hooks`, by the legacy set, by a server-side force-off, or because `comm_pool` or
`comm_exec` is off (hooks need both). The Kotlin side drops a request made anyway and
logs it; the shell side skips the install (`HOOKS_V2_SKIPPED_OFF`) when `hooks`, `pool`
or `exec` is in `off` of `client-flags.json`. Both sides read only the first 4096 bytes
of that file, so a longer file is judged by the same prefix.

**Merge rules** (python on the server): the target is the real path of each
settings.json (`~/.claude` and each `~/.claude-remote/accounts/<a>/`; account files
are symlinks to the main one today and are never replaced by a regular file); one
flock, the same lock file as the legacy ensure; unparseable JSON or an unexpected
shape is `FAILED` and nothing is written; an entry of a newer version is `NEWER` and
nothing is written (builds never flap); per event list the non-canonical marked
items are taken out of their entries and the canonical entry appended; a foreign hook
that shares an entry with ours stays (only our items are removed, in install and in
remove), other entries are never touched; before a write a backup `settings.json.claude-remote.bak.<epoch>` is made
(newest 5 kept); compare-and-swap on inode and mtime right before the replace (Claude
and OMC do not take our lock), up to 3 rounds; after the write the file is re-read
and every other top-level key and every hook that is not ours (also inside an entry that
carries our marker) must be unchanged, otherwise the backup is put back and the result
is `FAILED`. The backup is put back only while the file is still exactly what the merge
wrote; if Claude or OMC rewrote it meanwhile, the result is `FAILED` and their newer
write is left alone. The CAP marker
`<config dir>/claude-remote-hookev.installed` is written after the replace (and on
`EXISTS` when missing). Output tokens: `HOOKS_V2_FIXED|EXISTS|NEWER|FAILED`,
`ACCOUNTS_MERGED=<n>`; removal prints `HOOKS_V2_REMOVE_FIXED|EXISTS|FAILED`; the
read-only probe prints `HOOKS_V2_STATE=INSTALLED|PARTIAL|ABSENT|NEWER|UNREADABLE`
and `HOOKEV_OFF=0|1`.

### `hook-ev.sh` contract

`~/.claude-remote/hook-ev.sh` (POSIX sh, rewritten only when it carries no version
>= its own: `# claude-remote-hookev-v1` plus a `marker-compat` line within the first
300 bytes; a file of this version is kept only when its size and, where `sha256sum`
exists, its SHA-256 match, so a damaged file with a valid header is rewritten):

1. Always exits 0; stdout and stderr are closed first thing. Linux only. Off switch
   `~/.claude-remote/hookev.off`.
2. Reads the first N bytes of stdin (4 KB; 16 KB for `ask`, 8 KB for `perm`, 256 KB
   for `stop`) and drains the rest, so Claude's writer never sees a broken pipe.
3. **Attribution.** `C` = `$CLAUDE_PID` (fallback: the nearest claude ancestor of
   `$PPID`; a process counts as claude when its comm is `claude`, or a version
   number and `/proc/<pid>/exe` points into `.../claude/versions/`). `P` = `pane_pid`
   of `$TMUX_PANE` (one `timeout -k 1 1 tmux -u list-panes -s`, answered into a file, not a
   pipe, so a hung tmux ends the hook within about two seconds). Emit only when `C == P`
   (`exec claude`) or `C` is a descendant of `P` with no other claude in between and
   `P` is not itself a claude (that `C` would be nested). Intermediate shells are
   fine: `bash -l -> bash -lc -> claude`, `sh -c "...; exec bash -l"`,
   `start-claude-*.sh`. The `session_id` on stdin must equal
   `$CLAUDE_CODE_SESSION_ID` when both are known (whitespace around `:` in the JSON is
   fine). **Main pane only:** the hook's pane must be the first pane of the session's
   first window (the one the app attaches to). With agent teams in tmux mode a
   teammate is a split pane of the same session; its hooks are dropped.
4. No python on the path of `work`, `askdone`, `stop`, `start`, `end`. `ask` and
   `perm` use python only to shorten the questions or the tool, and still emit
   without it (empty detail).
5. Writes one line `claude-remote-ev <EV> <name_b64> <epoch_ms> <C> <extra_b64>`
   (under 1 KB, one append, file mode 600) to `~/.cache/claude-remote/events`; above
   20 MB nothing is appended. `extra` is UTF-8 capped at 600 bytes: `ask` = the
   questions with option labels, `perm` = `<tool>: <description|command|path>`,
   `stop` = `bg` when background tasks are pending, `start` = `source=<s>
   exe=<claude version>`.
6. Subagents and in-process teammates share the pid, pane and session id of the lead,
   so their `ask`/`perm` are emitted for the lead's tab; a teammate in its own tmux
   pane is dropped (see 3). A `PermissionRequest` for
   `AskUserQuestion` is dropped (the `ask` event covers it).

Limits: deny and Escape on a permission dialog emit no event at all, so a hook
approval ends only with the next event of that session or with the screen
classifier after attach (`NotificationService.hookApprovalSince` gives its age); an
account whose own settings file is a regular file (not today's symlink to the main
one) has hooks but no legacy Stop marker, so a live v2 `stop` leaves it in WORKING
after an approval was answered (known limit, accounts are symlinks today);
`background_tasks` is looked for in the first 256 KB of a Stop payload; managed and
project-level settings are not examined.

### Watcher and reducer

With `comm_hooks` on for the server, the per-server watcher channel also follows the
events file and `~/.claude-remote/forget.log`:
`tail -q -n 25 -F <notify> /tmp/claude-notify <events> <forget.log>` (`-n` is per
file; the `/tmp` path stays because claude-local sessions still write it). The
events file is rotated (over 1000 lines, keep 200) at most once per 24 h and before
`tail` starts; an append that lands between the rotation's `tail` and its `mv` is
lost, at most once per 24 h (events are hints, the screen classifier is the
fallback). The watcher holds its channel as a LONG permit of the server's exec
transport when exec routing is on for the server, otherwise a channel on a live tab
session, as before. With the flag off the command is the legacy one, character for
character.

`HookEventReducer` (pure, per server): dedup on `(tmux, event, ms)` (LRU of 2000);
order comes from the events file only, so an event older than the newest one seen
for that session changes nothing; `work` -> WORKING (and re-arms the prompt latch),
`perm`/`ask` -> approval needed with a start time, `askdone` -> WORKING, `end` ->
IDLE, `start` -> UUID re-check (nothing is adopted from a hook; it leaves a pending
approval alone, and a `start` right after an `end`, i.e. `/clear`, leaves the session
ready, not idle). `stop` always clears an approval (the session goes to WORKING, the
legacy Stop marker then makes it ready); without an approval it flips to ready only
when the event is old (a replay), because live the legacy marker does it after the
notification body is resolved. "Old" is 5 s plus the uncertainty of the measured
clock skew. A notification
for `perm`/`ask` is raised only for a session not in the foreground, only when the
event is younger than 10 min (60 s for an event stamped before the watcher's first
event, i.e. the replayed backlog of a cold start, whichever session it belongs to) and
only if no later event superseded it within a short settle time. One dialog is one
notification: the hook event and the screen detector hold each other back for 15 s
(the first one to notify wins; repeats from the same source pass).
`forget.log` lines (`[<utc>] forget: <name> (durable=N...)`, free text after
`durable=` tolerated) for one of our tabs that this device did not just close ask the
reconciler to check, after 3 s and once more 5 s later; a line is acted on once and
only while fresh, so the 25-line replay of every reconnect does nothing.

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

Set up during SSH connection, before the shell channel opens. With `comm_pool` on and an exec transport manager owning the server, the forwards live on the exec transport instead (registered once, again after every reconnect), so they survive with no tab open.

## Security

- **Credentials** are stored in platform-specific encrypted storage (SharedPreferences / Properties file)
- **SSH keys** support PEM format private keys
- **Host keys** use TOFU (Trust On First Use) — stored per host in `ServerStorage`
- **No external servers** — all traffic goes directly to user's SSH server
- **Keepalive** — jsch `serverAliveInterval` (10 s, 2 misses by default; the app lengthens it in the background via `setKeepAlive`) prevents idle disconnections and detects dead links
