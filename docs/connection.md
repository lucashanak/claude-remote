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
   ├─ timeout = connect timeout, THEN ServerAliveInterval = 10 s, 2 misses (keepalive; the pool-wide value from
   │  setKeepAlive). The order matters: the interval also sets the read timeout, and JSch sends a keepalive only
   │  when a read times out — set the other way round, the 15 s connect timeout was the keepalive pace (C1).
   └─ Cloudflare: the proxy has no socket, so JSch never sends a keepalive there; the proxy's own timer does
      (`TunnelKeepAlive`, armed right after connect by `CloudflareProxy.armKeepAlive`) with JSch's public
      `sendKeepAliveMsg`, on JSch's rules (below). Before that the handshake has the proxy's limit of the connect
      timeout (at least 10 s). Before C1 no keepalive ran over Cloudflare at all.
5. session.connect(timeout) — a direct (non-Cloudflare) transport rides the socket of `TransportWriteGuard` (C5, below)
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

// Resize PTY (terminal dimensions changed). C5: returns at once; the window-change goes out on the manager's IO
// scope, serialized with the input writes, a burst coalesced into its last size (see "Writes on a direct socket")
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

// What a NEW session must not be named: tmux sessions + names in sessions.json; THROWS on failure
suspend fun listForFreshName(session: Session): FreshNameListing
```

`listForFreshName` (quick connect, Connect screen) never gives up its channel in the middle of the OPEN (K5-1):
jsch abandons a channel interrupted before the server confirmed it without sending CLOSE (it has no recipient
yet), and the late confirmation left sshd a session nobody closes — one `MaxSessions` slot gone for the life of the
transport, while the client had returned its permit. So only the read is interruptible (the caller's 3 s / 6 s
timeout ends it, and the opened channel is closed); a stalled OPEN delays the timeout by up to its 5 s connect
limit. The server bounds the listing itself (K5-2): `timeout 2 sh -c "tmux list-sessions …; cat sessions.json"`
(the signal reaches the tmux CLIENT and `cat`, never the tmux server); status 124 or a missing status line reads as
a failure, so a hung tmux or file read never keeps sshd's session after the client gave its slot back. The creation
of a new session is bounded the same way (K6-1, `ClaudeConfig.buildFreshCreateCommand`: 10 s for the creation,
3 s for each check, below the client's 20 s). Both need coreutils `timeout` on the server: without it (N7-2, a limit
kept) no new session can be created — every launch fails with the server's "timeout: not found". Creation and the
cleanup let every tmux client write into a temp file (`mktemp`, removed by a `trap` on every way out), never into the
channel's pipe or a `$(…)` (N7-1); so do the tmux probes of `TmuxProbes` (has-session, the redraw kick's
list-clients/refresh-client, each bounded by `timeout 3` where coreutils has it; has-session prints NO only for tmux's
own answers with status 1, read under `LC_ALL=C` from its stderr in the temp file: "can't find session", "no server
running" or "error connecting … (No such file or directory)" — also for a LIVE server whose socket file was deleted
(the 2026-11-02 shape; kolo 6, adv H1: kolo 5's fail-open there led to a plain attach that failed and left a bare
login shell under an ACTIVE tab). NO leads to Rebuild, whose launch line goes through `tmux-sockheal.sh` (SIGUSR1
brings the socket back, then the existing session is attached; nothing is killed); ForgetClosedElsewhere on NO stays
as since 18faa58: only for a tombstoned name, or an untracked one while other live sessions answer (they cannot when
the socket is gone). Any other failure (tmux missing, a socket path too long, no permission) prints nothing:
fail-open, kolo 4 adv N8; and every line `sendTmuxCommand` types into the login shell ends that shell when it fails:
the SSH attach line (`buildAttachCommand … || exit 1`, kolo 6) and the launch line of each Rebuild branch and of
FreshLaunch (`{ <launch>; } || exit 1`, dávka D2) — so a failed attach, or a `tmux-sockheal.sh` that refuses (split
brain, its lock held: the line's `false`), goes the usual reconnect way instead of a bare bash under an ACTIVE tab. The
wrapper is added in the orchestrator, never in `ClaudeConfig.buildTmuxLaunchCommand` (which `cr-session` and the
detached create share; `TmuxRunGoldenTest` pins its bytes). ET types its line into a login shell too: `<attach>
2>/dev/null || { <relaunch> ; } || exit 1` (D2), and its fresh-launch line `{ <launch>; } || exit 1` (R6, as dead as
the SSH one); mosh runs its command as the session's own process, which ends with it. FreshLaunch in a tab is a dead
backstop: every entry calls it with `isNewTmuxSession = false`, `launchSession` creates new sessions over exec. A loss
soon after the line of a reconnect that ended ACTIVE counts as a failed attempt (kolo 7, adv N1: on a slow link the
failed attach's exit comes after ACTIVE, and every round began at attempt 1 with no wait, ~1.6 s apart for ever). The
brake counts from the line, stamped before `sendTmuxCommand` sends it, not from ACTIVE, and its window depends on the
line: 5 s after an attach (`EARLY_DEATH_MS`), 11 s after a Rebuild or FreshLaunch line (`LAUNCH_EARLY_DEATH_MS`, R1: the
guard's longest refusal — two heal tries of `flock -w 2` or the 2 s socket deadline 0.5 s apart, or one hung probe of
`timeout -k 1 5`: 6 s — plus the attach's 5 s; with 5 s from ACTIVE a 4.5–4.7 s refusal that came back over a link
slower than ~0.5 s read as a connection that held, and every tab of the server reconnected every 6–10 s). A reconnect
that typed no line (mosh, ET) counts 5 s from ACTIVE as before. Such losses in a row wait 4 s, 8 s, 16 s … (cap 60 s); a
connection that held longer starts the count over. A sockheal that keeps refusing thus reconnects every 4 … 60 s for as
long as it refuses (unless its `ss`/`find` look-ups of the listener themselves hang past the window); each refusal is on
the tab's terminal and in
`~/.claude-remote/sockheal.log` (one line per cause per 15 min). Not covered (older, in the hand-over): a tmux client
that ENDS with status 0 — another client detached ours, `prefix d` — still leaves the login shell under the ACTIVE tab
(an unconditional `; exit` would make two tabs of one device on one session detach each other for ever through the
single-client preamble: dávka E). The probes are
spelled out in both branches of
`if command -v timeout …; then timeout 3 tmux …; else tmux …; fi`, never a command kept in a shell variable: zsh does
not word-split an unquoted `$CR_T`, so round 2's `$CR_T tmux has-session` ran `timeout 3` as one command name and
read NO for a live session (kolo 3, adv N3; `TmuxProbeShellTest` runs the probes under sh and bash with zsh's
splitting rule, and under zsh where it exists)): a tmux client hands its stdout to the
server, and a STOPPED server kept that pipe — and sshd's session — open after `timeout` killed the client. Measured
with SIGSTOP of a private tmux server: the cleanup's channel ends after its 3 s limit, the creation's after 13 s; 70 s
of a stopped tmux behind 4 probe pollers cost no replacement of the exec transport and no failed interactive `echo`
(round 1: 3 replacements, 10 of 14 echoes refused). The cleanup asks `display-message -t '=name:'` (a pane target:
`'=name'` resolves to nothing) whether a client is attached, and leaves the session to it if one is (LOW3). Not
bounded yet: the pid probe of the manifest commands (`ManifestCommands`, not this batch's). Limits: the creation budget of 10 s is tight against the socket guard's own
waits on a slow but answering tmux (LOW5: `sg_probe` 5 s, `list-sessions` 5 s, `flock` 2 s; then Lost x 3 and an
error); and a creation cut short while tmux hangs may still be carried out once tmux recovers, after the launch has
ended — the session then stays (LOW4; the cleanup's check is cut short too and says so: "unknown whether the
session it may have created is there").

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
    // 0. V16: a NEW session is created first, over an exec channel (exec transport / counted channel / the pooled
    //    transport the tab leases next): `new-session -d … -e CR_LAUNCH=<nonce>`; a taken name moves on to the next
    //    free one, a lost answer is recognised by the nonce. No channel or a refusal: readable error, no tab.
    //    The server bounds the creation (`timeout 10 sh -c …`, 3 s per check; status 124 = lost answer, K6-1).
    //    Ended without its tab after a creation was sent (cancelled, every answer lost): the session it created is
    //    killed only when it carries the nonce and no client is attached to it (R6-2/R6-3, LOW3: an attached client
    //    is another device that took it over). A second launch of the same (server, requested name) while this one
    //    creates joins it: its tab, or its error (R6-1); so does an attach of that name (LOW1). The claim of the name
    //    ends when its tab exists (LOW2), so a launch after that tab was closed creates anew.
    //    The tab below then ATTACHES it (single-client attach), on SSH, mosh and ET alike.
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

`SessionOrchestrator.autoReconnect` runs when a shell reports its loss (`SshManager`'s `onConnectionLost`, the ET client's
`onDisconnect`, an ET carrier rebuild that gives up). One reconnect of a tab at a time: `autoReconnect`,
`reconnectSession` and `reconnectEtCarrier` share the per-tab lock `reconnectingSessionIds`.

- **Attempts.** Up to 3 (`maxAttempts`): attempt 1 at once (a Starlink handover is usually over by then; a Cloudflare
  route that died early twice in a row raises it, `cfEarlyDeathBackoffMs`), then 2 s, 4 s … (cap 30 s) + 0–500 ms
  jitter (`ReconnectPolicy.foregroundDelayMs`). Each attempt drops the old shell, waits for the exec transport inside
  a wave window, builds a new shell, waits for the prompt, sends Ctrl-C + Enter, attaches tmux
  (`sendTmuxCommand`, `checkClosedElsewhere = true`) and sends the pty size (`effectiveSize`, read after the 200 ms
  settle — kolo 4: a rotation during it wins).
- **Never ACTIVE over a dead shell (C4).** The shell can die during the attempt (measured 2026-11-07: 439 ms after
  its connect); the attach then went nowhere (`sendInput` on a disconnected manager does nothing). Before the tab is
  set ACTIVE and "Reconnected!" is printed, the attempt checks `sshManager.isConnected`; a dead one is a failed attempt
  (the next one runs). `connectSsh` checks the same at its end for the caller that holds the tab's lock
  (`requireAliveAtEnd`: `reconnectSession`, through the ET and mosh fallbacks too), so `reconnectSession` ends in its
  error path (ERROR + `armReconnectRetry`). A launch holds no lock (kolo 3, adv N2): the loss of its first shell is
  reported, `autoReconnect` heals the tab, and the launch ends as one that worked — persisted (`persistLaunched`:
  SessionStorage, tombstone, sessions.json), not as "launch failed" with nothing persisted. Kolo 4 (adv N9): the launch
  does not set ACTIVE over a tab its reconnect left DISCONNECTED (that reconnect sets ACTIVE, or the background retry
  has the tab), and its server bookkeeping is not skipped while the tab's shell is being rebuilt: over the exec
  transport it needs no shell, the legacy way waits for the tab's live shell up to 30 s.
- **A loss reported during a reconnect is kept (C4).** The shell a reconnect builds reports its own loss while that
  reconnect still holds the lock; the report's `autoReconnect` used to return silently. It now records the loss
  (`lostDuringReconnect`, under the lock) and `endReconnect` looks at it when the reconnect ends (the lock and the
  record go in one critical section, kolo 3, so a report after that point runs its own reconnect): a reconnect that
  ended ACTIVE over a connection that is no longer alive (`hasLiveTransport`: the ET client for an ET tab — its carrier
  shell carries no terminal, also once the dead client was taken out of the registry: the carrier stays marked,
  `ConnectionRegistry.isEtCarrier`, kolo 4 —, else mosh or the SSH shell) sets the tab and its activity DISCONNECTED
  and runs `autoReconnect` again,
  with its attempt count and backoff starting over like any loss. A reconnect that failed has handed the tab to the
  background retry already, so nothing loops. `reconnectEtCarrier` ends through `endReconnect` too (kolo 3, adv N5).
- **No input queue (kolo 5, decision of the lead).** Input for a tab without a deliverable route is never sent later:
  `sendInput`, `sendBytes` and `sendClaudeCommand` print "Not connected — not sent" and keep nothing. Three rounds
  showed a delayed send cannot be made safe — "1⏎" typed at a frozen dialog answered whatever dialog was open when the
  tab came back (the background retry reconnects without a bound); the prompt token is gone after a loss; the UI sends
  keys as "messages"; order; a killed process. Replies from the watch, the notifications and the quick reply are not
  input of the tab: they go over exec through `sendReply` + `ReplyGuard`, unchanged.
- **Input only into an ACTIVE tab (kolo 5, adv N3/N4).** `ClaudeControlService.route` gives a route only while the tab
  is ACTIVE: during a reconnect its new shell is a bare login shell until the attach (a message typed then ran as a
  command in `$HOME`), and the input never goes into an ET tab's carrier shell. `reconnectSession` and `autoReconnect`
  stop and remove the old ET and mosh clients first (kolo 6, adv M3/H2; a carrier rebuild that hands the tab to a full
  reconnect did so since kolo 5), and a mosh client that died leaves the registry (`isConnected` = the process lives),
  so the rebuilt SSH terminal gets the input, not a zombie `et` or a dead mosh-client. (`canDeliverNow`, the staleness
  input of `ReplyGuard`, still asks only whether the transport is up.)
- **The emulator's own answers are no user input (kolo 6).** tmux asks the terminal (DA, XTVERSION, CPR …) at every
  attach, and the answer comes one round trip later — inside a reconnect, before ACTIVE. Those answers go through
  `sendTerminalReply`: whenever the transport is up, never "not sent". The platforms hand them in apart from the keys:
  Termux (`SshTerminalSession`) — what the emulator writes while it processes received bytes (a paste or a mouse
  report, written outside that, stays user input); JediTerm — `TerminalStarter.sendBytes(…, userInput = false)`.
- **One message, one tab (kolo 6, adv M2).** The input bar's send (a slash command key by key, other text as body +
  Enter) runs in `ClaudeControlService.sendMessage` for the tab read once at the send; a part that is not sent prints
  one "not sent" and stops the rest — never an Enter alone (also for the app's own slash commands and the login code).
- **Limit (adv M1, open).** ET and mosh clients buffer what they are given and deliver it when their link comes back;
  such a tab stays ACTIVE until its keepalive notices the loss (ET: its carrier is a pool transport — keepalive
  10 s in the foreground, 60 s in the background (`setBackgroundMode`), `serverAliveCountMax` 2, dead after 3
  intervals: ~30 s / ~3 min; mosh: while the mosh-client process lives, no bound). Input typed into a frozen ET or mosh tab in that window can still arrive later. A staleness
  gate for ET/mosh is in the hand-over, not in this batch.
- **After 3 failed attempts** "Reconnect failed after 3 attempts", DISCONNECTED, and `armReconnectRetry` keeps calling
  `reconnectSession` (2 s → 60 s + jitter, unbounded) until the tab is ACTIVE or gone.
- **Safety net (C4).** The network sweep `reconnectDeadTabs` takes DISCONNECTED, ERROR **and ACTIVE tabs without a
  live transport**; `kickRedraw` (a tab shown: switch, back from Chat) of an ACTIVE tab without a live transport calls
  `reconnectSession` instead of kicking a dead shell, so showing a frozen tab heals it. Both go through
  `reconnectSession`'s single flight, so a reconnect already running is never doubled. The sweep's list can be 2 s
  old (the size wait below): each tab is checked again right before its reconnect, and one that is ACTIVE over a live
  transport by then is left alone (kolo 3). A DISCONNECTED tab is not reconnected by a kick: its own reconnect or retry
  has it.
- **The app's own lines are not tmux's output (C4).** "Connection lost. Reconnecting (n/3)…", "Reconnected!" and
  "Reconnect failed …" are announced to the attach trace (`AttachTrace.ownOutput`) before they are printed, so they do
  not close the `Attach … firstByte` measurement (a dead attach used to read as a 200 ms one).
- **Size at a cold start (C4).** The restore wave (`restoreAndReconnect`) and the sweep wait for the first size the
  terminal view reports (`TerminalIOService.awaitFirstViewSize`, at most `VIEW_SIZE_WAIT_MS` = 2 s counted from the
  first wait, once per orchestrator; then the default 80x24 as before): a tab connected before it starts its tmux client
  at 80x24, and a tab in the background gets no resize until it is shown (none is sent to background tabs on purpose,
  `window-size latest`). `kickRedraw` resizes through `TerminalIOService.resize`, so the tab remembers the size it was
  shown at for its next reconnect.
- **Limits.** The atomic `endReconnect` has no deterministic test (a race inside one critical section, E1/M5f: it
  would need a hook in production code); the ET carrier path is tested without the ET binary (kolo 4). On the phone
  measured 2026-11-07
  13:03 the first view size came 9.5 s after the cold start, past the 2 s limit: those tabs still start at 80x24
  until they are shown.

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
  reconnect wave of N tabs therefore starts at most `ceil(N / cap)` handshakes, at most
  `MAX_PARALLEL_HANDSHAKES` = 3 of them at once (the bound lives in the pool: `SingleFlight.tryJoin`
  refuses a fourth connect of the key and the claimant waits until ANY of the key's running connects ends
  (`select` over the snapshot `SingleFlight.pendingAll`, taken under the mutex with the refusal), then asks again; see
  "Handshake gate"). Waiting for the oldest one held a throttled tab behind a slow handshake although a fast one had
  long freed a slot. That wait is bounded: when no handshake of the key has succeeded for `connectTimeout` (counted
  from the lease's start, restarted by every success it sees), the lease fails with `IOException("no handshake slot
  ...")` like a claimant whose handshake timed out. A blackhole thus fails a wave of 49 tabs after one
  `connectTimeout` (+ at most one handshake) instead of round after round; a slow wave that keeps connecting is never
  cut. The shell cap is read again on every pass of the lease loop, so tabs still waiting when the exec transport
  comes up pack by 8, not 3.
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
starts with the background interval. A link that stops carrying data is down after interval x 3 (2 unanswered
keepalives, then the third timeout): ~30 s in the foreground, ~3 min in the background (60 s). The same holds over
Cloudflare since C1: `TunnelKeepAlive` sends a keepalive after each interval without an incoming byte, reading
`serverAliveInterval` on every 250 ms tick (the background switch applies at once), and closes the tunnel once
`serverAliveCountMax` (2) keepalives SENT since the last byte stayed unanswered for one more interval — JSch's own rule
on a socket, interval x 3 of silence at a steady interval. Kolo 3 (adv N1): death counts sent keepalives, never the
idle time against the interval of the moment; the old rule closed every idle healthy tunnel 31-59 s after its last byte
when `onResume` shortened the interval 60 s -> 10 s (about half the returns from the background), now the first tick
sends a keepalive instead. It never throws into JSch's read (round 1 did, and a pause inside an SSH packet broke the
stream); a pause shorter than the dead limit is harmless. Doze and a frozen process: the first tick after the wake sends
a keepalive (no death without unanswered keepalives); the one case left is a freeze right after the second unanswered
keepalive, whose answer may still sit unread when the first tick runs. On a direct socket JSch's read timeout fires
first after a wake and the count of 2 stays (two keepalives must go unanswered AFTER the wake).

**Writes on a direct socket (C5, trace-lan-umrti)**: JSch encrypts and compresses a packet (AES-CTR counter, deflate
state) before it writes it. A write that throws after that — on Android a socket write from the main thread throws
`NetworkOnMainThreadException` before a byte leaves — leaves the client's cipher a packet ahead of sshd's, and sshd
reads the next packet as garbage (`Corrupted padlen`, `Invalid ssh2 packet type: 0`, or a silent RST/FIN). Measured
2026-11-07 on the phone over Wi-Fi: LAN attach + refit 56 deaths / 17 survivals, Cloudflare 0/18 (the WebSocket only
queues the bytes; a send that fails there makes OkHttp close the tunnel). `SshManager.resize` was the writer: the
layout and the redraw kick call it on the main thread, and JSch's `setPtySize` swallows the exception. Now:
- `resize` sends on the manager's IO scope, serialized with the input writes (`writeMutex`), a burst coalesced into its
  last size; `disconnect` closes the channel (and a standalone session) off the caller's thread — the UI closes a tab
  from the main thread, and the channel's close packet rides the transport its sibling tabs share;
- every direct transport of the app's connection layer (pool, `SshManager`'s standalone and cleanup connections,
  `SshSessionHelper`'s fresh one) rides the socket of `TransportWriteGuard`: a failed write closes the socket at once —
  the transport is broken, every channel on it reports its loss and reconnects onto a new one — and logs
  `Transport write to <ip>:<port> failed on thread <name>: <exception>` once; a write to a socket that was already
  closed (our own cleanup) says so instead and claims nothing about the cipher (kolo 4, adv N7). Never a transport kept
  with a broken cipher. JSch applies no connect timeout to a socket from a factory: the guard does, the name lookup
  included (kolo 4, adv N6: a lookup on a thread of its own, waited for within the connect timeout; kolo 5 tests a
  lookup that hangs through the seam `TransportWriteGuard.resolverForTest`).
- the writes the UI could start on the main thread, audited (kolo 3 and 4) and moved off it: `SshManager.resize`
  and `disconnect` (the channel's close, a standalone session's disconnect, the pool's release of the last lessee),
  the notify watcher's CLOSE when the last tab of a server is closed (`NotificationService.unregisterNotifyWatcher`,
  review MEDIUM-1), the whole `reconnectSession` (the Reconnect button runs it from the UI scope, adv N3), the
  watchdog's and the `finally`'s CLOSE of `execReadWithWatchdog`, the `finally` CLOSE of `PooledRemoteExec.runOnce` and
  of `GatedExecChannels.use` (kolo 5, adv N5: the account usage screen runs an exec from the UI scope), and the legacy
  has-session probe on a tab's own shell (`TmuxProbes.probeTmuxSession(SshManager)`, now suspend on IO). Already off
  it: port forwards (inside `connect` on IO, `reconnectScope`, the exec manager's scope), the watchdog of
  `PooledRemoteExec.runOnce` (`Dispatchers.Default`), the opens of `ChannelOpen` (a thread of their own), the upload and
  the SFTP save (IO), the network-callback teardown (ConnectivityThread). Left: `MoshManager` (Android) and
  `SshKeyManager` open JSch sessions of their own without the guard (on IO, short-lived) — out of this batch.
Tested against a real sshd (`TransportWriteGuardIntegrationTest`): one failed write inside `setPtySize` and one
keystroke — sshd ends the connection (the mechanism, without the guard); with the guard the shell reports its loss at
once and the next lease builds a new working transport (pool, standalone and the helper's fresh connection), and the
last shell closed from a "main" thread leaves the pool's disconnect to another one; `MainThreadWritesTest` calls the
other paths from a "main" thread. Limit: the window-change toggle of the redraw fallback (`cols-1`, then `cols` 80 ms
later) can coalesce into one send under load (no SIGWINCH then; the refresh-client kick is the main path).

**Log lines** (parsed by the metrics extractor, so keep the shape):
`New transport to <host>:<port> role=shell|exec route=lan|tunnel|cf|unknown (<n> in pool)`,
`Awaited in-flight transport (<ms>ms)`, `Transport for <server>: <choice> -> <host> (cf=<bool>) route=...`,
`Exec transport <server> up via TS|CF|DIRECT in <ms>ms route=... (resolve=<ms>ms lease=<ms>ms forwards=<ms>ms total=<ms>ms wall=<ms>ms)`
(the phases and `in` are monotonic (`nanoTime`), so a process frozen by Doze no longer shows up as a 400 s connect;
`wall` is the wall-clock span for diagnosis),
`Exec transport <server> down after <ms>ms via TS|CF|DIRECT[ (network lost)]`,
`Tailscale transport died <ms>ms after connect for <tab> on <server>[ transport=<id>][ transport_age=<ms>ms]` (one line
per tab; `<ms>` is the TAB's age, which the strike rule uses; `<id>` is the hex identity hash of the shared jsch session,
the key of the strike debounce, left out when the tab's transport is unknown; `transport_age` is the transport's own age,
counted from the first tab that connected on it, left out when unknown). Metric M7 (decision V13) counts TRANSPORTS on
both sides: the numerator is the Tailscale transports that died young (by `transport_age` where the line has it, else by
the tab's age: a tab that joined a transport up for an hour and died with it is no young death; the tab lines of one
server and transport id within 30 s are one death, an exec `down ... via TS` line is one, and one lost with the network
is none), the denominator the `New transport ... role=` lines (shell and exec) to a host some
`Transport for <server>: TAILSCALE -> <host>` decision chose, so a server without Tailscale does not dilute it. A link
outage that takes a server's 3 transports is 3 of 3. Lines without an id (older logs) fall back to one wave per server
within 2 s. The numerator counts only deaths of the same log generation as the denominator, i.e. after a
`New transport ... role=` line: a build before that logged `New transport ... (<n> in pool)` without `role=`, so on the
day of an update its deaths had no transports to divide by (2026-11-03 on the phone: 218/18 = 12.1, now 1/18); a day
with no denominator is `n/a`. Known limits: a server pinned to Tailscale logs no decision line, so its transports are not
in the denominator; the transport's age starts at its first tab's connect, not its handshake (the attach is not in it). `tools/relay-metrics` and `TransportResolverDeathLineTest` keep the two sides in step,
`Exec transport <server> reroute probe: direct endpoint offered|still the fallback (<n>/2)`,
`Exec transport <server> rerouted <via> -> <via> in <ms>ms route=...`,
`Exec transport <server> reroute <via> -> <via> failed after <ms>ms, staying on <via>: ...`,
`Transport for <server>: CLOUDFLARE (cooling down|no tailscale host|probe failed)[ [exec]]`,
`Transport probe <server> <host>:<port> attempt <n>/2 ok after <ms>ms|failed after <ms>ms: <reason>`,
`Transport to <host>:<port> role=shell|exec route=... ended after <ms>ms: cause=<reason>` and the tab's
`Connection lost cause=<reason> ...` (see "Why a transport ended"). `route` says which path the
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
  `ensure` re-opens it. The supervisor writes its states under the entry lock and only while it is the entry's run
  and the entry is not closed (review B-3 F1): a `close()` that lands after its last `closed` check (just before
  `Connecting`, during a connect that then fails, while it lets a dead transport go) stays `Closed`, and a connect that
  fails after the close is no Tailscale strike, not even when an `ensure` has opened a new run in between (only the
  entry's current run strikes, review B-4 K4-5). The same holds at the end of the watch (review B-5 K5-3): a run that
  `close()` and a new `ensure()` ended while it was in non-suspending code (e.g. a forward re-bind) leaves the watch
  without a "down after" line, a generation bump on the new run's transport, a strike, or a stable report
  (`onStable`), and the strike is checked again after the lifetime is read. `onNetworkLost` / `onNetworkAvailable` check `closed` and write under
  the same lock (review B-4 L2), so a `close()` racing the network callback also stays `Closed`.
- Backoff: 0, 1, 2, 4, 8, 16, 30 s in the foreground; twice that, at least 5 s and at most 60 s,
  in the background (`background = true`). A transport that lived 60 s resets it.
- Strikes: only a Tailscale transport that died within 30 s of coming up counts
  (`TransportResolver.noteServerEarlyDeath`, which skips deaths right after a network teardown
  we caused), and a failed connect attempt counts like a tab's failure, except while the network
  is gone (`networkLost`): then every attempt fails and none says anything about Tailscale.
  A Tailscale transport that stays up past the 30 s window clears the strike streak
  (`TransportResolver.noteServerTransportStable`). Cloudflare is the last resort and never strikes.
  The tabs of ONE shared transport report its death one by one within milliseconds; they are one strike, not one
  per tab (`TransportResolver.tsStrikeDebounced`: one strike per transport identity per 1 s; two different
  transports dying together are still two). A strike for a LAN death stays: the strike rule itself is unchanged.
- The resolver's probe is serialised per SERVER (a probe can take 14.5 s and used to hold up every other server's
  resolve). The exec transport resolves inside `withContext(ResolveForExec)` and does not take a cached decision that
  came from a FAILED probe (the tabs keep the one-decision-per-wave rule for 10 s): it probes again.
- **Back from the fallback (F1).** An exec transport that came up on the fallback endpoint (Cloudflare on the phone)
  of a server with a Tailscale/LAN host, not pinned to Cloudflare, looks for the way back from its watch loop: after
  it has lived 60 s, at most one probe (a fresh `resolve` as the exec transport) per 60 s, only in the foreground,
  never within 5 min of the last move or tried move (`ExecTransportPolicy.REROUTE_*`). Two probes IN A ROW that
  offer the direct endpoint move it, make-before-break: lease the new transport, publish it (`generation++`, state
  `Up(newVia)`), move the port forwards, and let the old transport go 5 s later. A failed move keeps the old transport
  and is no strike. The probe runs detached, so a death is still noticed while it runs; a gap of more than 30 s
  between two watch passes (a wake from Doze) restarts the schedule, so the wake adds no probe to its reconnect wave.
- A `DownNetwork` nudge (from `session()`/`await()`) is limited to once per 2 s in the foreground
  and once per half retry period in the background, so a poller does not turn the slow
  background pace into a connect every 2 s.
- `generation(serverId)` increases on every death, network loss, close and move back from the fallback.
- Port forwards: while the exec manager owns a server's forwards
  (`ServerTransportPool.setExecForwardsOwned`) a shell session does not register them; the
  manager registers them again after every reconnect. They therefore keep working with zero tabs.
  Known limit: ownership starts at `ensure`, not when the transport is `Up`, so while the exec
  transport is down in a backoff (up to 30 s in the foreground, 60 s in the background) the
  forwards are not available even though the tabs run. Shell sessions do not bind them in the
  meantime on purpose: a forward bound on a shell would collide with the exec transport's bind
  when it comes back, and would die with that shell's transport without anyone rebinding it.
  A move back from the fallback (F1 above) unbinds them on the old transport and binds them on the new one
  right after it is published. They cannot be bound on the new one first: an L port is a listener of this process,
  an R port one of the old sshd process, and a second bind of either fails. The port may also stay taken for a moment
  after the unbind (the old listener under load; for R, jsch sends `cancel-tcpip-forward` with want-reply=0, so the
  old sshd may not have let the port go yet). So each bind of a move is tried up to 5 times, 200 ms apart
  (`ExecTransportPolicy.FORWARD_BIND_TRIES` / `FORWARD_BIND_RETRY_MS`), and a forward still unbound after that (or
  after a reconnect's single try) is bound again on every watch pass (5 s) for as long as the transport lives, so at
  the latest one pass after the old transport is gone. Only a quick refusal is retried: a try that failed after more
  than 1 s (`FORWARD_BIND_QUICK_MS`), or on a transport that is no longer connected, ends the move's tries and leaves
  the forward to the watch. A transport that stalls without a RST makes jsch wait 10 x 1 s for the reply of each R
  request, so 5 tries held the move, and the watch it runs in, for 51 s; a taken port is refused at once. Such a slow
  (or dead-transport) failure also ends the whole pass, in a move, a connect and every watch pass alike (review B-3
  R3-2, OQ1): the forwards not tried yet go to the watch untried, so a stalled transport costs one 10 s wait per pass,
  not one per R forward (a move with 2 R forwards took 20 s). The untried forwards go first on the next pass, so one
  forward that always fails slowly does not keep the watch from the others. Lines:
  `Exec transport <server>: port forward <L|R> <localPort> <-> <host>:<remotePort>[ (try <n>/5)| bound again]`,
  `... port forward <L|R> <localPort> failed|not bound after 5 tries|not bound (try <n>/5 took <ms>ms|try <n>/5, the
  transport is down): <reason>; the watch binds it again` (logged once; the watch's failed re-binds are silent),
  `... <n> more forward(s) left to the watch untried (<why>)` and `... not freed on the old transport: <reason>`.
  Known limit (review B-2, OQ1): an R forward is requested for `localhost`, which sshd (with `GatewayPorts no`) binds on
  127.0.0.1 and ::1 and reports bound when either family succeeds. If a foreign process held one family's port (the old
  sshd frees both in one cancel), the forward would be bound on the other family only: jsch counts it bound, nothing
  retries it, and clients of the held family are refused until the transport ends. It is not bound on `127.0.0.1`
  explicitly, because that would drop the ::1 listener that `localhost` clients may try first.

### Why a transport ended

Log only; nothing behaves differently. Every pooled jsch session gets a `TransportEnd` as its jsch `Logger`: jsch
reports why its main loop stopped (`Caught an exception, leaving main loop due to <message>`, the message only) to
nobody but a logger, and `TransportEnd` keeps that message. When WE close a transport (`teardownAll` on a network
change, a drained or idle transport, a write-timeout zombie pruned after `reportDead`, a handshake nobody waits for any
more) the cause is ours, `local: <why>`, and the first word wins. Lines:
`Transport to <host>:<port> role=... route=... ended after <ms>ms: cause=<reason>` (pool), and the tab's
`Connection lost cause=<reason>` (`SshManager` read loop): `cause=local: <why>` for our own close,
`cause=transport: <jsch's reason> (channel: <end>)` when the transport ended under the tab, else the channel's own end
(`EOF`, `<Exception>: <message>`, `channel closed`) with `transport=up|down exitStatus=<n>`. This is what tells the
`channel is not opened` failures with `exitStatus=-1` (a dead transport) apart by cause.

### Channel budget

`GatedExecChannels` (the real `ExecChannels`) enforces the per-server budget so the exec
transport never reaches the sshd's `MaxSessions` (default 10): SHORT 6 permits (SFTP shares
them), LONG 2, 8 channels in total. Permits belong to a transport generation: when the transport
dies the old permits are void and waiters re-evaluate against the new transport. Waiting longer
than `acquireTimeoutMs` is `GATE_TIMEOUT`. **Priority class (C1):** a periodic poll (transcript tick, status poll,
and the redraw kick's refresh-client) asks for `ExecKind.POLL`: a SHORT permit taken only while more than 2 are free
(pending opens count, `Limits.interactiveReserve`), never waited for; without one — or while the exec transport is
`Connecting` (the tick does not go onto the shell transports then), or when what is left is below the route's floor
of an open — the tick is skipped quietly, no error line, no backoff, and counted into one line per server and minute
(`ExecChannel: poll skipped (no poll permit) server=… transcript=N status=M probe=K in 60s`, `PollSkipLog`); a
skipped transcript tick leaves the view's status as it was. The refresh-client kick has 2.5 s, so after the permit more
than Cloudflare's floor of 1.5 s is left. Everything else stays SHORT and may use the reserve: attach, the tmux probe
that decides launch/attach and what an ended attach means (`probeTmuxSession`), the Connect screen's listing, launch
and cleanup, a reply (`ClaudeControlService`), `pollNow` of the Stop path, the restore-service install, the manifest
push and the `sessions.json` sync. LONG (watcher, streamd) is unchanged.
With no exec transport, SHORT/SFTP fail with `NOT_UP`
after a short wait (the caller falls back to its tab session); LONG waits 3 s and then opens on a
live shell transport under its own limit of 2, so the notify watcher and the transcript daemon do
not go dark while the exec transport reconnects. A fallback stream is counted against the shell
transport it borrowed (`reserveChannel`, so a shell lease cannot fill the transport behind its
back) and is ended with EOF when the exec transport comes `Up` again, so its owner reopens it
there. With the exec transport in `Down` or `Closed` a SHORT call answers `NOT_UP` at once instead
of waiting; only `Connecting` is waited for (by a SHORT call; a POLL skips its tick).

**Channel opens and the health of the exec transport (dávka C, C1).** JSch 0.2.21 gives up an OPEN whose
confirmation is late without sending CLOSE (`Channel.connect` sets `connected=false` before `disconnect()`) and drops
the late confirmation, so sshd kept that session for the life of the transport (P-1: 11 stalls of 6 s, then 3 of 3
channels refused). `ChannelOpen.connect(ch, budgetMs)` is now the one way the exec layer (`PooledRemoteExec`,
`execReadWithWatchdog`), the transcript and status polls and the tmux probes open a channel:
- below the route's floor no OPEN is sent at all: `TIMEOUT` (`cf` 1 500 ms, `lan` 300 ms). The floor is checked
  against what is LEFT of the caller's bound after its wait for a permit, so a caller's bound must cover the floor plus
  that wait plus the read: the probe that decides launch/attach (`probeTmuxSession` by server) has Cloudflare's floor
  + 2 s (`probeBudgetMs`, dávka D5; its fixed 2 s left 1.4 s after a 0.6 s permit wait in the phone's first sweep after
  a start — 28 fail-open probes in one second, v1.0.563);
- the open runs detached: the caller waits its budget, the open goes on up to 20 s (`cf`) / 10 s (`lan`), or the
  caller's budget if longer. An open the caller gave up on runs `true` instead of its command (the swap can lose a
  race with an answer arriving in that very instant) and is closed with CLOSE once confirmed — no orphan. Its slot
  stays taken in the gate (`GatedExecChannels.pendingOpens`) until it resolves; `ChannelOpen.disconnect` leaves such a
  channel to its open;
- the transport is reported dead (`ChannelOpen.Watch`, registered by the exec transport manager only) after 3 opens
  IN A ROW unanswered within a full budget (waited >= 5 s, `exitStatus = -1`; each started after the transport last
  answered and after the previous counted open was given up — opens in flight together during one pause count once),
  or one open unanswered until its dead limit, or 2 refusals (exit status 1/2) while the session channels JSch has on
  the transport (pending opens included, port forwards not) stay below sshd's `MaxSessions` (assumed 10; unreadable =
  no claim): orphans we do not know of. Not counted: probes with a small budget, an interrupted wait, `channel request:
  timeout` (the OPEN was answered), refusals at the limit. A confirmed open (also a late one) resets the count. The
  refusal line is aggregated (the first ones, then one per minute). Limit (kolo 3, review LOW): on the exec transport
  the gate lets at most 8 channels open, against the assumed `MaxSessions` 10, so the refusal rule practically always
  has "room left": a server with `MaxSessions` below 9 refuses legitimately and two such refusals replace the transport
  (at most once per spacing, 30 s; a confirmed open between replacements keeps the spacing at 30 s). The dev box has 30;
- the manager then marks the transport unusable in the pool (`reportDead`); its watch logs `down`, releases it (the
  last lessee disconnects it) and leases a new one — never the same (`!==`). Between two such replacements at least
  30 s (`HEALTH_SWAP_SPACING_MS`), whatever the backoff says, doubling up to 300 s while a replacement brought no
  confirmed open before the next (`nextHealthSwapSpacingMs`); a refused one is logged once per spacing (`not replaced,
  the last replacement was …`). For 60 s after it the shells keep the cap of 8 (`keepsShellCap`, H7), so a reconnect
  wave in that window does not open a transport per 3 tabs.
Measured (`ChannelHealthIntegrationTest`, loopback sshd, LAN route, 4 pollers with 5 s open budgets): a black hole on
the exec transport (data stop, socket open) is `down` after ~10.1 s — the open's dead limit on a LAN route — and one
pause of 6 s that the pollers wait out together replaces nothing. Expected in production, not measured: LAN ~10 s;
Cloudflare ~15 s (3 counted opens in a row of the 5 s exec budget; the transcript's 10 s and the status' 8 s opens
count too) or at the latest the open's 20 s dead limit; with no channel traffic at all only the keepalive notices it,
interval x 3 (~30 s at 10 s). Limits: a pending open on a SHELL transport (a fallback channel) holds no slot in the
pool's count (bounded by its 10 s / 20 s); and the half-dead detection exists for the exec transport only (a tab's
shell transport relies on the keepalive).

`PooledRemoteExec.run` runs one command on a gated channel, bounded by a wall-clock watchdog that
is independent of the caller. The bound `totalMs` is an absolute deadline: waiting for a permit
and for the transport spends it (`acquireTimeoutMs = min(5 s, what is left)`). It never returns a partial result: if the transport died before the
command reported an exit status the call is `ExecUnavailable(IO)`; `channel is not opened.` with
exit status 1 or 2 is retried once inside the same bound before it surfaces as `REFUSED`; the
connect never waits longer than what is left of the bound; paced stdin stops once the remote side
has closed.

## Communication redesign: flags and seams

Experimental groups of the communication redesign. `comm_pool` acts on the
transport pool (previous section). The exec layer (exec transport manager, channel gate,
bootstrap, reconcile, hook sink) is wired by the orchestrator in one step, see "Wiring in
the orchestrator" below: with `comm_exec` on for a server everything that is not a tab's
terminal runs on that server's exec transport, with it off every lookup behaves as before.
Settings -> *Connection (experimental)* edits the flags (each label says what the switch
does; every switch shows when an implication turned its group off). `CommFlags.deviceDefaults`
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
unchanged; parse `key=value` tolerantly; `warm` and `parked` are 0 until lazy attach
exists; `exec` is the number of reconcile execs issued in the minute just ended, the
load of the per-server reconcile, summed over ALL servers: with several servers expect about 6 per server,
not 6 in all). The attach line is
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

### Wiring in the orchestrator

`SessionOrchestrator` fills the registry's slots in ONE step (`wireExecLayer`, last in the class
body): `PoolExecTransportManager` (`execSessions`), `GatedExecChannels` (`execChannels`),
`PooledRemoteExec` over the gate (`remoteExec`), `DefaultServerBootstrap` (`bootstrap`, built on
first use: it listens to the process-wide install / remove requests of Settings from the moment it
exists, and a request nobody can serve is dropped), `SessionPersistenceService.newExecReconciler()`
(`reconciler`), `SshSessionHelper.execChannelsProvider`, the `HookEventSink` and
`PersistenceClosures` of the notification service. A half-wired layer would route callers to an
exec transport nobody keeps alive, so there is no partial state. The shell cap of a server's pool is
8 only while its exec transport is UP (`ConnectionRegistry.execUp` is the pool's `execWired`).

Lifecycle:
- `ensureExecTransport(serverId)` (idempotent, free to repeat; `ensure` is single flight, so 20 tabs
  cost one handshake) runs before the first connect of a tab of the server: `launchSession`,
  `reconnectSession`, every `autoReconnect` attempt, `restoreAndReconnect` (once per server, before the
  tabs start), and `ensureExecTransports()` for every server that has a tab (Android `onResume`,
  desktop window focus, `onNetworkAvailable`).
- `onNetworkLost()` tells the exec manager BEFORE it tears the pooled transports down: the exec
  transports park in `DownNetwork` (no strike, no backoff climb) instead of reading the teardown as a
  death. `onNetworkAvailable()` (Android `onAvailable`) wakes them and ensures every server's, before the
  dead tabs reconnect.
- **The first reconnects of a wave wait for the exec transport.** The shell cap is 8 only while the exec
  transport is UP, so a lease made while it is still `Connecting` / `DownNetwork` gets 3: 49 tabs would open
  17 transports where 7 do. `restoreAndReconnect`, `onNetworkLost` and `onNetworkAvailable` open a window of
  3 s (`EXEC_WAVE_WAIT_MS`, together, not per tab); `reconnectSession` and `autoReconnect` wait inside it for
  `execManager.await(serverId)` when the exec transport is `Connecting` or `DownNetwork` (not when it is
  Down in a backoff, closed or switched off). Whatever is not up by then starts at cap 3; a lease still waiting in
  the pool when the exec transport comes up reads the cap of 8 on its next pass. Measured with 2 s handshakes and 49
  tabs (`ExecRerouteIntegrationTest`): exec transport up after 3 s = at most 8 transports (17 on c2915d1, which read
  the cap once per lease); up after 9 s, when the wave is over, = 17, as expected.
- `setBackgroundMode` sets `PoolExecTransportManager.background` (slower reconnect pace) next to the
  keepalive. The desktop never reports a background mode.
- `close(serverId)` runs in `disconnectSession` once the LAST tab of the server is gone (after `removeTab`;
  a tab that is being closed is in `closingIds` and no longer counts as a reason to start the transport, so a
  reconnect of it that is still in flight cannot revive it behind the close), and in `releaseExecLayer()`.
  `disconnectAll()` calls the latter; no platform calls `disconnectAll` today. `ensureExecTransport` and every
  close (the last tab gone, the `exec` flag switched off, `releaseExecLayer`) decide and act under one
  per-server lock (`execLock`), so a tab opened while the last one closes keeps (or restarts) the transport and
  a kill switch or a release is never undone by an ensure that was already past its checks (`releaseExecLayer` visits
  every server that has a lock, not only the ones ensured so far, so the very first ensure of a server that is past
  its checks is waited for and closed too). The Android
  `MainActivity` calls `shutdown()` on the orchestrator it replaces: a recreated activity builds a new
  orchestrator with its own `TabManager`, so the old one is unreachable from the UI. `shutdown()` sets
  `released` (from then on `ensureExecTransports`, `ensureExecTransport`, the bootstrap kick, `reconnectSession`,
  `autoReconnect`, the reconnect sweep and the network events do nothing), closes its exec transports, cancels its
  background scope (transcript, status and git pollers, notify watcher, reconcile and retry loops, which would
  otherwise go on polling through the process-wide `SshSessionHelper`, now wired to the new orchestrator), stops the
  native mosh / ET clients of its tabs (each would stay attached to tmux as a stale client) and detaches its shells.
  A mosh / ET connect still running at that moment registers its client after the snapshot; it sees `released` there
  (`adoptMosh`/`adoptEt`: put first, then the check) and stops the client itself.
  The tmux sessions stay; the new orchestrator attaches its own.
- **Network callback.** One default-network callback per PROCESS (`NetworkCallbackHub`, registered by the first
  activity on the application's `ConnectivityManager`), delivered to the current `OrchestratorHolder.orchestrator`:
  `onLost` → `onNetworkLost`, `onAvailable` → `onNetworkAvailable` and the orchestrator's `reconnectDeadTabs`
  sweep (DISCONNECTED and ERROR tabs and ACTIVE ones without a live transport, one sweep at a time, in the orchestrator's scope). It is never unregistered:
  the process (KeepAliveService) must keep reacting to the network while no activity exists.
- **Kill switch at run time.** The orchestrator watches `CommFlags.effectiveFlow(serverId).exec` of every
  server that has a tab. `exec` going off (Settings, the legacy set, a server-side force-off) closes the
  server's exec transport (its supervisor would otherwise revive it and feed the Tailscale strike rule),
  hands its port forwards to a live shell transport and puts every tab back on its per-tab session-id loop;
  `exec` coming back starts the transport, takes the forwards back from the shells, runs the bootstrap and
  puts the tabs back on the reconcile. Exactly one of the per-tab loop and the reconcile runs for a tab
  (`register` stops the loop, the legacy start unregisters). While shells are still packed 8 to a transport,
  the paths that went back to the legacy lookups are counted and need room too (`ConnectionRegistry.needsRoom`:
  routing on, or some shell transport carries more than 3 shells).
- Per tab: with routing on, `attachSessionRuntime` registers the tab with the reconciler (one reconcile
  per server) instead of starting the per-tab session-id loop; `disconnectSession` unregisters it. The
  attach-exit path asks `reconciler.checkClosed` first (it forgets a tab under the tightened rule).
- Connect: with routing on, `ensureServerSetup` runs the per-server bootstrap (one exec, memoized) and
  waits for it at most 3 s before the tmux command, so the order hooks -> tmux holds when the server
  answers quickly and the attach is never held hostage when it does not; the per-tab Stop-hook and
  streamd ensures stay for the legacy path. `tmuxExists` / `hasTranscript` of the launch decision and the
  restore-service install, tombstone clear and manifest push of `launchSession` use the by-server-id
  overloads (exec transport); with routing off they run on the tab's own session as before.
- `refreshFlags` of every server that has a tab and runs exec routing, or has it forced off by the server's
  `client-flags.json` (not by this device), every 10 minutes while the app is in the foreground: a server-side
  force-off takes effect without a reconnect, and deleting the file lifts it the same way (the read goes over
  a tab transport with room while the exec transport is closed). The bootstrap itself is kicked (resume, focus,
  network back, exec transport up) only when something can answer it: the exec transport is up or a tab of the
  server is connected; a bootstrap that fails with "no transport" backs off for 10 s and would suppress the
  ones of the connects.
- One-off server-side steps of `launchSession` (restore-service install, tombstone clear, manifest push) that
  find no usable transport wait for the exec transport once (60 s) instead of being dropped; the install is
  remembered only after it succeeded, and a tombstone that could not be cleared is logged and does not stop the
  push. Every coroutine the exec paths launch catches and logs its failure (`launchGuarded`), and the
  orchestrator's scope has a `CoroutineExceptionHandler` as a net: on Android an exception nobody caught ends
  the process. `ExecServerReconciler.round` / `checkClosed` / `checkUuid` never throw (a failed exec is "no answer").
- Hook sink: `work` re-arms the notify latch of the tab (the statusline does it only for a tab without hook events), `start` asks the
  reconciler for the tab's uuid, a forget.log line asks it to check whether the tab is closed
  (`checkClosed` after the delay). Tabs are found by `(serverId, tmux name)`.
- FIX 4 (leaving a tab resets its APPROVAL to ready): a hook-reported approval (the pane's own claude said
  so; `NotificationService.hookApprovalSince`) younger than 10 minutes, on a server with hooks on, stays;
  the next hook event of the pane ends it. Anything else is reset as before.
- The log shipper flushes through `execRun` (SHORT, 10 s) on the server with the most tabs.
- `data/60s exec=` is the reconcile execs of the minute, summed over all servers. Each attach logs
  `Attach <sid> kind=cold ms=<n> phase=shell=..,prompt=..,decide=..,attach=..,firstByte=..` when the first
  tmux output after the attach command arrives: `shell` (lease and shell channel), `prompt` (shell prompt, startup
  command), `decide` (server setup, tmux probes and the decision up to sending the command), `attach`
  (sending it), `firstByte` (until the first output that is not the pty's echo of the command: the echo comes
  about one round trip after the send and is skipped; tmux's switch to the alternate screen also counts).
  `warm` attaches do not exist yet.

Limits of the wiring that are known and accepted (they are not bugs to fix next):
- FIX 4: a hook-reported approval lasts until the next hook event of the pane. PostToolUse is not one of them, so
  after the user answered on the screen and a long tool runs, a later screen guess of APPROVAL can survive a tab
  switch for up to the 10 minutes of the rule. `approvalSince` is not cleared by the answer on screen.
- The desktop reports no background mode and has no network callbacks (`onNetworkLost` / `onNetworkAvailable`
  are Android's): it relies on the keepalive (10 s x 2: down after ~30 s, over Cloudflare too since C1), the channel
  health of the exec transport (C1) and its 5 s supervision poll.
- A network change (Android) still tears every transport down at once (V1 = b, dávka C): every `Default network
  lost` in the phone's log was followed by `available` within 0-2 s, and the storm 30 s after a switch was Android's
  linger killing the old network's sockets, which no debounce prevents. The log now names the network
  (`default network available: netId=… type=wifi|cellular|vpn|…`, `losing … in Xms`) for the next measurement.
- The close-state and peer probes of a tab's connect run on the tab's own shell session (see "Fallback with headroom").
- Tests: `aDamagedHookScriptWithAValidHeaderIsRewrittenAndAnIntactOneIsKept` (lane H) failed once in a run and
  passed on repeat (watch it); the hang test of the capped hook script checks only the time bound, not that the
  process is gone.

### Fallback with headroom

The shell cap of 8 is decided when a tab leases (read again on every pass while it waits), but the fallbacks happen later: when the exec
transport is down, short execs, the bootstrap, the watcher and the stream daemon fall back to a tab
transport, and tabs sit 8 to a transport (sshd's default `MaxSessions` is 10). A fallback must not land on
a transport without room, so while exec routing is on a fallback only uses a shell transport where
`shells + reserved slots + borrowed channels + 1 + 2 <= 10` (`ServerTransportPool.fallbackHeadroom`, 2 =
channels left for the channels that are still uncounted, `FALLBACK_RESERVE`: the close-state and peer probes of a
tab's connect, the UI's one-offs that open a channel on a tab's session themselves (`App.kt`: rename, ...), the script
install and the `sessions.json` push of `SessionPersistenceService`). A SHORT channel (a poll, a one-off command, the
redraw kick, an SFTP stat, `withSession`) may take ONE slot of that reserve (`SHORT_RESERVE_SLOTS`, V9 of dávka B):
the rule is counted by occupancy (`... + 1 + 2 - 1 <= 10`), so two short channels never sit in the reserve at once
and a LONG one (the watcher, the stream daemon's fallback) never fits once a short one sits there. A transport packed
with 8 shells therefore has room for one short channel at a time (8 + 1, which leaves one slot of the 10 to the
uncounted channels above) and none for a long one: the long fallback gets `NOT_UP` (the watcher retries) until the exec
transport is back, which the manager retries with its backoff (at most 30 s in the foreground). A transfer (the upload
and the SFTP download: `withTransferChannel` on the tab's own session, `transfer = true` in the fallback of
`execRun`/`useChannel`) keeps off the reserve like a LONG channel (V12): one 3 MB upload held the one slot for 15.7 s and not one poll of the 8
tabs ran meanwhile (adversarial review, round 2). A transfer without room waits for it, holding nothing meanwhile (no
reserve slot, no channel; R3-1, round 4): it looks again every 300-1000 ms (jittered) until its deadline (upload 20 s,
download its own time limit, else 20 s), so a poll in flight on 7 shells, another transfer, or an exec transport that is
briefly down (a backoff, an early death) no longer fail it. It gives up at once only when no room can come
(`ConnectionRegistry.transferMayFindRoom`): the exec transport is switched off (kill switch) or `Closed`, and no tab
transport of the server (for the tab's own session: that one) can ever fit a transfer, i.e. 8 shells
(8 + 1 + 2 > 10), or 7 shells and a LONG channel held there (the notify watcher's tail through
`onServerTabChannel`, counted per tab session by the registry; K4-3, round 5). Then the upload fails at once with `No free SSH channel ... the exec transport is off, reconnect the
tab` and the download returns null; after the deadline the upload says `... stayed full for 20 s, try again`. The
shells of a transport are counted as the tabs whose session it is. A 7-shell transport has room for ONE transfer at a
time: a 1 MB upload on a tab's session took ~5.2 s in the integration fixture, so twelve of them at once are ~62 s of
that slot and some end at their deadline (a limit of the budget; `ChannelBudgetIntegrationTest.r31a` moves 64 KB up and
1 MB down). An inline image whose download ends null is still cached as "could not be loaded" until a tap
(`InlineImageView`, not changed here); with the wait this needs the room to stay away for its whole 30 s limit.
A failed transfer is visible and can be repeated, a frozen chat is not. The SFTP stat (link check of the paths in a
chat) and the short execs still take the slot. Before V9 the whole
reserve stayed free, so with the kill switch and 8 packed shells not one poll of any tab ran (0 polls in 20 s, review
round 1; `ChannelBudgetIntegrationTest.n4`). The price: the polls of the 8 tabs serialize on that one channel
(measured with 8 tabs, their session-id loops and a status poller: 2-3 transcript polls per tab in 30 s, 18 for one
tab, instead of one every 1.5-15 s), until the tabs re-lease at cap 3. The slot is not handed out in turn: whoever
asks while it is free gets it (measured 2-19 polls per tab in 30 s), so a tab may wait well beyond one poll period; the
soak measures the largest gap between two polls of one tab for this. Two uncounted channels at once on top of a short
channel in the reserve exceed `MaxSessions` 10 (8 + 1 + 2: 35-46 refused polls in 20 s, adversarial review round 2,
N3); only on an sshd with the default 10 (the dev box has 30) and two of the uncounted one-offs above at the same
moment. The polls of a
tab (`TranscriptStream`, `SessionStatusPoller`) and the redraw kick, which used to fall back to the tab's own
session without any check, take a counted channel through `ConnectionRegistry.reserveTabChannel` and skip the
tick (the kick: use the geometry fallback) when there is no room. The same holds whenever the server needs a room
check (`needsRoom`: exec routing on, or a shell transport packed beyond the legacy 3, i.e. the kill switch with 8
shells still on a transport) for every channel a service opens on a tab's own session: both legacy branches of the
two pollers, the git probe, scroll, upload, the SFTP download and stat of a tab, `refresh-client`, the Stop-hook
setup, the notify watcher's LONG channel and the accounts screen (`onServerTabChannel`, SHORT), the per-tab session-id
loop (one token per tick) and the uuid pid probe when a transcript opens. An upload without room says so
(`No free SSH channel ...`, see the transfer wait above), not `SSH not ready`. `reserveTabChannel` returns a token (`TabChannel`): its `release()` is
atomic and idempotent and gives back exactly what that reservation took (nothing when the server needed no room
check at the moment of the reservation), so a double release or a race with another reservation of the same
session can no longer leak or steal a slot. A skipped tick logs `poll skipped (no room) src=<who> server=<id> n=<k>`
(the first at once, then at most one line a minute per source and server, with the count; the skips counted after the
last line are written by the next skip or at the next tab channel the server grants (`reserveTabChannel`) once the
minute is over; a streak after which neither comes, e.g. the last tab closed, keeps its count unwritten); a channel
that fails to
open logs `channel open failed src=<who> exitStatus=<n|n/a> route=… usable=… shells=… headroom=…` (tag
`ChannelBudget`; -1 = the server never answered, a dying transport; 1/2 = refused by sshd). Short
fallbacks, `useChannel` fallbacks and the bootstrap's hold ONE counted channel for the whole call
(`reserveFallbackChannel`, decided under the pool mutex so two callers cannot take the last slot) and give
it back in `finally`; the LONG fallback of `GatedExecChannels` counts its channel the same way through the
gate's `reserveFallback` hooks. Chosen over "back to cap 3 after exec has been down for X s" because it
needs no migration of live shells and costs nothing while the exec transport is up. `SshSessionHelper.withSession`'s
pooled step runs through `ConnectionRegistry` as its `FallbackRunner`: with a room check the block holds one counted
channel for its whole run (claimed and released in `finally` under `NonCancellable`), without room it does not run
there; a legacy server keeps the uncounted pooled session (a legacy lease would otherwise lose a slot). The block's
own `null` is wrapped (`Fallback<T>`) and never mistaken for "no room". `fallbackPooledSession` (the
`liveSessionProvider`) is kept as a look-only view for diagnostics; nothing runs on it. The LONG fallback of `GatedExecChannels` claims
its channel with a check-and-reserve under the pool mutex: when the room went to someone else between the look
and the claim the stream does not start (`NOT_UP`). The close-state and peer probes of a tab's connect
(`fetchCloseState`, `serverHasOtherLiveSession`) still run on the tab's own shell session (no by-server-id
overload): in a wave of reconnects on a transport packed with 8 shells they can be refused by `MaxSessions`
(they fail open); a limit of this wiring, accepted.

### Handshake gate

`connectGate(serverId)` was a `Semaphore(3)` in front of `SshManager.connect`. With the pool's single flight
(`comm_pool` on) it is harmful: a tab holds its permit while it waits for the handshake it joined, so at most
three tabs are inside `connect` and the handshakes of a wave run one at a time. Measured against a real sshd
with 20 tabs and the exec transport up (`ExecWiringIntegrationTest`): behind the gate of 3 the wave took
382 ms with at most 1 handshake in flight; with the pooled gate 85 ms with 3 in flight (both 3 handshakes in
total). So with `comm_pool` on the gate is practically open (256 permits); with it off the gate of 3 stays.

That gate was also what kept a wave at cap 3 (exec transport not up yet: 49 tabs = 17 handshakes) from
opening them all at once. The bound now lives in the pool, where it holds whatever stands in front of it: at
most 3 handshakes of a transport key at once. Measured against a real sshd with `MaxStartups 10:30:100`, 49
leases and no exec transport: peak 3 in flight, 17 handshakes, nothing dropped (without the bound: peak 17 and
`drop connection ... past MaxStartups`).

### Exec routing

When the exec seam is wired (the orchestrator sets the `execSessions`/`remoteExec` slots
in its constructor) and the `exec` group is on for a server, everything that is not the attached terminal talks to that
server through the server's exec transport instead of borrowing a tab's shell:

- `execRun` / `execRead` / `useChannel` run a command, a read-only command or a scoped
  channel through `RemoteExec` / `ExecChannels`. When the exec transport is not UP
  (`NOT_UP`) they fall back to a live tab session of the server that still has channel
  headroom (see "Fallback with headroom"); any other
  `ExecUnavailable` (timeout, refused channel, io, permit wait) is thrown, never retried
  on another transport. `ConnectionRegistry.liveServerSession` / `anyLiveSession` never
  hand out the exec session, because it would be used without a permit: they return a
  connected tab session only (with routing on, `liveServerSession` only one with channel headroom). A permanent stream (notify watcher, stream daemon) takes
  its channel from `useChannel` with the long kind; a one-off operation (usage poll,
  upload, log shipping) uses the short kind with a total time limit, so the two long
  permits are never used up by work that ends. A script over 4 KB goes on stdin behind the short
  `StdinScript.EXEC` (dávka D3: the close and its fallback, the close facts, the restore, the `cr-session`
  install), paced like every upload; `execRead` takes the `stdin` too, and its fallbacks then run on
  `PooledRemoteExec` (`execReadWithWatchdog` has no stdin). These sends are never `transfer = true`, and never wrap a
  command that reads its own stdin (`pushMerge`). Bootstrap, hooks v2, streamd and reconcile stay arguments
  (`ExecArgumentSizeTest` caps them, 12 KB after deflate for every request).
- With routing off the behavior is the one from before, except for these exact items:
  keepalive also reaches the pooled and exec transports; the log shipper trims with
  `tail -n +2`; the stream daemon forgets its bootstrap memo when it ends without a
  `hello`; the new by-server-id overloads exist (nothing calls them).
- `SshSessionHelper.withSession` runs its block on the exec session under ONE short permit
  (so every one-off user is inside the per-server channel budget), then on a pooled
  transport (a counted channel where the server needs a room check, see "Fallback with headroom"), and only
  then on a fresh connection, unless `allowFresh = false`, where it
  throws `ExecUnavailable(NOT_UP)` instead. Only `NOT_UP` falls through to those
  transports; a permit wait that timed out is thrown to the caller. The transcript and status pollers use
  `allowFresh = false` under exec routing, and also in their legacy branch for a tab without a live session
  whenever `comm_pool` is on (a fresh handshake per poll per tab would bypass the pool's limit of 3 handshakes):
  a tick without a usable transport is skipped.
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
remove), other entries are never touched. An item is ours only when its command
STARTS with `: claude-remote-ev v<N>;` (the shape the installer writes); a foreign hook
that merely mentions the name, say in a path, is left alone and does not count as a
version, and a `null` or non-object item in a hook list stays; before a write a backup `settings.json.claude-remote.bak.<epoch>` is made
(newest 5 kept); compare-and-swap on inode and mtime right before the replace (Claude
and OMC do not take our lock), up to 3 rounds; after the write the file is re-read
and every other top-level key and every hook that is not ours (also inside an entry that
carries our marker) must be unchanged, otherwise the backup is put back and the result
is `FAILED`. The backup is put back only while the file is still exactly what the merge
wrote; if Claude or OMC rewrote it meanwhile, the result is `FAILED` and their newer
write is left alone, unless what is in the file then already is our canonical state
(install) or holds none of ours (remove): that is reported as `EXISTS` and the marker
is written, so hooks that run are not reported as not installed. The CAP marker
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
   teammate is a split pane of the same session; its hooks are dropped. Known limit:
   the hook compares with the first pane of the session's first window, while the app
   attaches to `=name:` (the active pane of the current window); today the two coincide
   (one window, one pane; teammates are created with `split-window -d` behind the lead),
   so a layout with a second window or a moved lead pane would need the comparison revisited.
4. No python on the path of `work`, `askdone`, `stop`, `start`, `end`. `ask` and
   `perm` use python only to shorten the questions or the tool, and still emit
   without it (empty detail).
5. Writes one line `claude-remote-ev <EV> <name_b64> <epoch_ms> <C> <extra_b64>`
   (under 1 KB, one append, file mode 600) to `~/.cache/claude-remote/events`; above
   20 MB nothing is appended. `extra` is UTF-8 capped at 600 bytes: `ask` = the
   questions with option labels, `perm` = `<tool>: <description|command|path>`,
   `stop` = `bg` when background tasks are pending, `start` = `source=<s>
   exe=<claude version>`. The script needs `mktemp` (its tmux answer goes to a temp
   file): with `/tmp` full or not writable every event is silently dropped, and a hook
   killed by SIGKILL leaves its temp file behind (the exit trap does not run).
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
ready, not idle). A `stop` that finds the session still in APPROVAL leaves it for
WORKING (the legacy Stop marker then makes it ready); a live `stop` never overwrites
a session that is no longer in APPROVAL, because the legacy marker of the same turn
normally lands first and has already made it ready (`NotificationService.dispatchHookEvent`
asks the status service for the current activity). Without an approval a `stop`
flips to ready only when the event is old (a replay), because live the legacy marker
does it after the notification body is resolved. "Old" is 5 s plus the uncertainty of the measured
clock skew. A notification
for `perm`/`ask` is raised only for a session not in the foreground, only when the
event is younger than 10 min (60 s for an event stamped before the watcher's first
event, i.e. the replayed backlog of a cold start, whichever session it belongs to) and
only if no later event superseded it within a short settle time. One dialog is one
notification (`PromptDialogDedup`): while the announced hook approval is still pending in
the reducer (`approvalSince`), the screen detector's APPROVAL for that session is held back
even when it comes late (the phone log had it 35 s after the hook); the next hook event of
the pane (`work`, `stop`, `askdone`, `end`, or a new `perm`/`ask`) ends the pending approval
and the hold. A pending approval nobody announced (the active tab in the foreground, an old
replayed event) holds nothing back. In the opposite order (screen first) the hook event is
held back for 15 s, and the dialog counts as announced all the same, so a later screen
re-sighting of it is held as above; repeats from the same source pass. Allow and deny emit no
hook, so the reducer keeps an answered approval pending until the next hook event, and the hold
must never silence a real new request: it applies only while the hooks are live (the watcher's
tail is connected and the bootstrap has not reported `hookev.off`), it lasts at most 120 s after
the announcement (`HOLD_MAX_MS`: a dialog still up then alerts once more), and it ends when a
key sent from this app answers the dialog (also for a session that is CAP=0 by then) or when the
screen classifier reads the input box or the spinner on a screen that can show the dialog's fate:
the classifier saw the dialog (APPROVAL) since the announcement, or terminal output arrived 5 s or
more after it. A screen whose newest output is older is ignored: the hook may beat the drawing of
the dialog, and a tab's stream can lag the hook by seconds, so its spinner predates the dialog and
taking it for an answer let the late sighting of the same dialog alert twice. An unreadable screen
ends nothing. Known limit: the time is the time output reached this device, not the time it was
drawn, so a stream that lags the hook by 5 s or more and delivers the spinner from before the
dialog then still ends the hold, and the late sighting of the same dialog alerts a second time
(a duplicate, never a lost alert). Each screen detection logs
`detection APPROVAL source=SCREEN seq=<n> for <session>`.

**Working / ready of a session with hooks (CAP=1).** A session counts as CAP=1 once a
v2 event of its pane has been applied by the reducer since it was attached in this process
and was at most 60 s old (`InputPromptDetector.markHookEvents` from
`NotificationService.dispatchHookEvent`; a replayed duplicate or the hour-old backlog of a
cold start proves nothing about the hooks now). It ends when the session is removed, when the
bootstrap of its server reports `hookev.off`, and when the legacy Stop marker of a live turn
end arrives and no v2 event of the session (its own `stop` included, given 5 s to arrive from
the other file) came for 2 min: the Stop hook runs but the v2 hooks are silent. The check runs
only at a turn end because an idle session emits nothing for hours. For it:
the OMC statusline no longer drives working/ready (`parseClaudeWorking` returns null:
its `thinking` segment only means a thinking block within 30 s of a render the HUD
cache shows one frame late, so it froze "● Working" after a turn); the optimistic
WORKING of a send is set only for a submitted prompt (a CR outside a bracketed paste; a paste split
across sends counts to the end of its part, so a CR that ends such a part is no submit; a LF and
Shift+Enter's `ESC CR` are newlines in the draft), not for Escape, arrows, digits or
typing, except at APPROVAL: there a printable key or a lone Escape answers the dialog (a digit,
`y`, Escape = deny; an escape sequence such as an arrow or a terminal reply does not) and no
hook reports the answer, so it sets WORKING too and ends the hold of the screen alert; and
because an interrupt emits no hook, a WORKING session whose terminal stays silent for 10 s (the
spinner and the elapsed-time counter repaint continuously while Claude works) drops to ready,
logged as `No terminal output from <session> for <n>ms while WORKING and no hook stop: ready`.
The drop is armed whenever the session is set WORKING by an Enter from this app, by an answer
to an APPROVAL from this app, or by a hook event (`work`, `askdone`, and a `stop` that leaves an
approval). The silence counts only from the moment output can reach this device again: no
transport of the session connected (the watch then backs off up to 30 s between checks instead
of waking every second), or a check that comes far later than asked (the process was frozen),
restarts it, so a reconnect or a wake does not read the outage as silence. It never ends an
APPROVAL (a dialog is a silent screen). Sessions without hook events are unchanged
(statusline, every send sets WORKING, the UI's 45 s stale-WORKING decay). Known limits: after
an answer given in another client's terminal or on another device APPROVAL lasts until the
next hook event (no hook fires on an answer); a session whose last fresh event predates this
process (idle since before a cold start, or only in the replay) stays CAP=0 until its next
event; a pause of the spinner output of 10 s or more while the transport stays connected shows
ready early, and nothing but the next hook event or Enter brings back WORKING.

**Watch.** The routine snapshot push to the watch (`WearSync`, 400 ms debounce) is not
urgent (battery), except where the watch will announce something: a session ENTERS
APPROVAL_NEEDED from another known state, or ENTERS WAITING_FOR_INPUT with a `lastMessageAt`
other than the one it had when it was last seen waiting (a flip without a new message, e.g. the
silence drop, is dropped by the watch as a "stale flip"). Then the DataItem goes out urgent, at
most one urgent push per 10 s in all outside `pushNow()` (`WatchPushUrgency`,
`WatchUrgencyTracker`; logged as `Urgent push: <ids> entered WAITING/APPROVAL`). An edge stays
pending until a written snapshot that still shows the session waiting claims it (inside the
write lock, after the stale-sequence check), so a push that `collectLatest` cancels loses
nothing. A write the budget holds an edge back from puts nothing and schedules one write for when
the budget allows the next urgent push (`WatchUrgencyTracker.write`, logged as `Urgent push
deferred by the budget: write held back, another in <n> ms`); that write carries the change,
urgent. A write held back while that scheduled write waits or runs (its own write, or another
push's while it waits on a summary) makes it write once more when it is done
(`WatchDeferredWrite`), so a held back change never waits for the next unrelated push.
Putting the held back write routine would make the scheduled one carry the same bytes,
and Data Layer drops a DataItem identical to the last one, urgent flag and all. The tracker runs
on the monotonic clock (`SystemClock.elapsedRealtime`), so a wall clock step cannot hold writes
back. As a routine item the
"done" after a Stop hook reached the wrist 91 s late. The reducer and the "body before the
flip" order of the Stop path are unchanged.
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

Set up during SSH connection, before the shell channel opens. With `comm_pool` on and an exec transport manager owning the server, the forwards live on the exec transport instead (registered once, again after every reconnect, and a forward whose bind failed is tried again on every 5 s watch pass), so they survive with no tab open.

## Security

- **Credentials** are stored in platform-specific encrypted storage (SharedPreferences / Properties file)
- **SSH keys** support PEM format private keys
- **Host keys** use TOFU (Trust On First Use) — stored per host in `ServerStorage`
- **No external servers** — all traffic goes directly to user's SSH server
- **Keepalive** — jsch `serverAliveInterval` (10 s, 2 misses by default; the app lengthens it in the background via `setKeepAlive`) prevents idle disconnections and detects dead links within interval x 3, over Cloudflare too (`TunnelKeepAlive`, C1)
