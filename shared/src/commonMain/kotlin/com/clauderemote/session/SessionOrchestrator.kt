package com.clauderemote.session

import com.clauderemote.connection.SshManager
import com.clauderemote.connection.SshSessionHelper
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import com.clauderemote.model.*
import com.clauderemote.session.transcript.TranscriptEntry
import com.clauderemote.session.service.ConnectionRegistry
import com.clauderemote.session.service.execReadWithWatchdog
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Live reconnect progress for a session, surfaced to the UI so a reconnecting
 * pane shows "Reconnecting (N/3)…" / "Retrying in Ns" instead of looking frozen.
 * [maxAttempts] <= 0 means the unbounded background re-arm loop is running (no
 * fixed cap); [nextRetryAtMillis] is a wall-clock (System.currentTimeMillis)
 * target the UI can count down to, or null when an attempt is in flight.
 */
data class ReconnectInfo(val attempt: Int, val maxAttempts: Int, val nextRetryAtMillis: Long?)

/**
 * Orchestrates the full flow: server → SSH connect → tmux → cd folder → claude.
 * Manages one SshManager per active session/tab.
 * Buffers terminal output per session for tab switching.
 */
class SessionOrchestrator(
    private val serverStorage: ServerStorage,
    private val tabManager: TabManager,
    private val sessionStorage: SessionStorage? = null
) {
    private val connectionRegistry = ConnectionRegistry(serverStorage, tabManager)

    // Shared background scope for all per-session/per-server loops. Declared
    // early so collaborator services can take it by constructor injection.
    private val reconnectScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    init {
        // Route one-off SSH ops (scanRemoteSessions, status/transcript polls)
        // onto an existing pooled transport instead of a fresh SSH-over-CF
        // handshake each time — the dominant idle data drain.
        com.clauderemote.connection.SshSessionHelper.liveSessionProvider = { server ->
            connectionRegistry.pooledSession(server.id)
        }

        // Data-usage snapshot every 60s so we can see where the bytes actually
        // go (terminal is DECODED — compressed on the wire; transcript is the
        // on-wire base64/gzip payload; appRx/Tx is the real app-wide traffic).
        reconnectScope.launch {
            var pTerm = 0L; var pTr = 0L; var pPoll = 0L; var pRx = 0L; var pTx = 0L
            while (true) {
                kotlinx.coroutines.delay(60_000) // throws on scope cancel → loop ends
                val term = com.clauderemote.util.DataMeter.terminalBytes()
                val tr = com.clauderemote.util.DataMeter.transcriptBytes()
                val pl = com.clauderemote.util.DataMeter.pollBytes()
                val net = com.clauderemote.util.platformNetBytes()
                val dTerm = (term - pTerm) / 1024; val dTr = (tr - pTr) / 1024; val dPoll = (pl - pPoll) / 1024
                // Session/transport counts so we can see if the residual scales
                // per-tab (→ per-connection keepalive/heartbeat) and whether it's ET.
                val tabs = tabManager.tabs.value
                val sessions = tabs.size
                val etCount = tabs.count { connectionRegistry.et(it.id) != null }
                // REAL TCP connection count (pooled), not the per-session
                // SshManager count — tells us if pooling is collapsing sessions.
                val conn = connectionRegistry.liveTransportCount()
                val mode = if (isInBackground) "bg" else "fg"
                val netStr = if (net != null) {
                    val dRx = (net.first - pRx) / 1024; val dTx = (net.second - pTx) / 1024
                    pRx = net.first; pTx = net.second
                    // Residual = real traffic not attributed to counted content —
                    // i.e. SSH/ET/CF keepalives + channel framing.
                    val residual = (dRx + dTx) - dTerm - dTr - dPoll
                    " | appRx=${dRx}KB appTx=${dTx}KB overhead≈${residual}KB"
                } else ""
                FileLogger.log(TAG, "data/60s: $mode sessions=$sessions(et=$etCount) conn=$conn | content term=${dTerm}KB tr=${dTr}KB poll=${dPoll}KB$netStr")
                pTerm = term; pTr = tr; pPoll = pl
            }
        }
    }

    // Server reachability health + per-server latency polling.
    private val serverHealthService = com.clauderemote.session.service.ServerHealthService(
        reconnectScope, connectionRegistry, tabManager, { isInBackground }
    )

    // Per-session git working-dir status polling.
    private val gitStatusService = com.clauderemote.session.service.GitStatusService(
        reconnectScope, connectionRegistry, tabManager, { isInBackground }
    )

    // 5h/week usage percents + reset minutes + usage tokens + ccusage polling.
    private val usageService = com.clauderemote.session.service.UsageService(
        reconnectScope, connectionRegistry, tabManager, { isInBackground },
        { s, w -> onUsageUpdate?.invoke(s, w) }
    )

    // Per-session transcript streams + derived ctx-% + the shared streamd daemon.
    private val transcriptService = com.clauderemote.session.service.TranscriptService(
        reconnectScope, connectionRegistry, tabManager, { isInBackground },
        { sid, pct -> onContextUpdate?.invoke(sid, pct) },
        sessionStorage, ::readRealSessionId,
    )

    // Per-session activity state + per-session OMC remote-status pollers.
    private val statusService = com.clauderemote.session.service.StatusService(
        reconnectScope, connectionRegistry, tabManager, { isInBackground },
        { id -> transcriptService.markWorkSeen(id) },
        { id -> gitStatusService.probeOnIdle(id) },
    )

    // Input-prompt detection, needs-input notifications, Stop-hook watcher,
    // login flow, and the offline pending-input queue. Declared after the
    // services it bridges to (statusService/transcriptService) exist.
    private val notificationService = com.clauderemote.session.service.NotificationService(
        reconnectScope, connectionRegistry, tabManager, { isInBackground },
        { id, act -> statusService.updateActivity(id, act) },
        { id -> transcriptService.lastAssistantEntry(id) },
        { id -> transcriptService.streamOrNull(id) },
        { sid, hint, active, body -> onClaudeNeedsInput?.invoke(sid, hint, active, body) },
    )

    // Per-session terminal output buffer (ring buffer) + pty size tracking.
    private val terminalIO = com.clauderemote.session.service.TerminalIOService(connectionRegistry)

    // User input delivery (mosh-first, ssh-fallback), the offline pending-input
    // queue, and the Claude slash-command control surface. Coordinator over the
    // services above; declared after them since it composes them.
    private val claudeControl = com.clauderemote.session.service.ClaudeControlService(
        reconnectScope, connectionRegistry, tabManager, statusService, notificationService, terminalIO,
    ) { sid, data -> onTerminalOutput?.invoke(sid, data) }

    private val remoteOps = com.clauderemote.session.service.RemoteOpsService(
        reconnectScope, connectionRegistry, tabManager,
    )

    // Shared sessions.json (fetch/cache/push), the systemd restore-service
    // installer + restore/drift scripts, per-session real-UUID refresh
    // pollers, and the forget/rename lifecycle edits.
    private val persistence = com.clauderemote.session.service.SessionPersistenceService(
        reconnectScope, connectionRegistry, tabManager, serverStorage, sessionStorage,
        transcriptService, terminalIO, { isInBackground }, ::readRealSessionId,
        { id, act -> statusService.updateActivity(id, act) },
        ::disconnectSession,
        { sid, tmux -> onSessionForgotten?.invoke(sid, tmux) },
    )

    // Tailscale/Cloudflare/direct transport selection + per-session connection
    // labels. Owns the AUTO probe, cooldown/failstreak arithmetic, and the
    // short-TTL resolved-transport cache. Declared after the deps it uses.
    private val transportResolver = com.clauderemote.session.service.TransportResolver(reconnectScope, connectionRegistry)
    private val tmuxProbes = com.clauderemote.session.service.TmuxProbes(reconnectScope, connectionRegistry, tabManager, terminalIO)

    /**
     * Platform-provided screen snapshot reader. Must marshal onto the thread that
     * owns the terminal emulator (main looper on Android, EDT on Swing). Pass-through
     * to [InputPromptDetector.screenReader].
     */
    var screenReader: (suspend (sessionId: String) -> ScreenStateSnapshot?)?
        get() = notificationService.promptDetector.screenReader
        set(value) { notificationService.promptDetector.screenReader = value }

    /**
     * Full de-wrapped visible screen text of the ACTIVE session, or null. Used only
     * for login-URL extraction; the platform returns null for background tabs.
     * Pass-through to [InputPromptDetector.fullScreenReader].
     */
    var fullScreenReader: (suspend (sessionId: String) -> String?)?
        get() = notificationService.promptDetector.fullScreenReader
        set(value) { notificationService.promptDetector.fullScreenReader = value }

    // Per-session activity state (for health indicator dots) — owned by statusService.
    val sessionActivities: kotlinx.coroutines.flow.StateFlow<Map<String, SessionActivity>> get() = statusService.sessionActivities

    // Human-readable "how am I connected" label per session, e.g. "Tailscale · Mosh"
    // or "Cloudflare · SSH". Surfaced as a chip in the chat status bar so the
    // active transport + protocol is glanceable — owned by transportResolver.
    val connectionLabels: kotlinx.coroutines.flow.StateFlow<Map<String, String>> get() = transportResolver.connectionLabels

    // Sessions whose idle/working state is driven by the Claude Code Stop hook
    // (authoritative: flips to WAITING the instant Claude finishes, regardless
    // of which screen the user is on). The UI uses this to know it can trust
    // `activity` outright instead of falling back to a stale-WORKING timer.
    val hookActiveSessions: kotlinx.coroutines.flow.StateFlow<Set<String>> get() = notificationService.hookActiveSessions

    // Per-session context window usage (0-100) — owned by transcriptService.
    val contextPercents: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> get() = transcriptService.contextPercents

    // Per-session SSH latency (ms) — owned by serverHealthService.
    val latencies: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> get() = serverHealthService.latencies

    // Per-session git status of the working directory — owned by gitStatusService.
    val gitStatuses: kotlinx.coroutines.flow.StateFlow<Map<String, GitStatus>> get() = gitStatusService.gitStatuses

    // Pending input queue per session (for offline queue feature) — owned by notificationService.
    val pendingCounts: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> get() = notificationService.pendingCounts

    // Per-server reachability for the launcher health dot — owned by serverHealthService.
    val serverHealth: kotlinx.coroutines.flow.StateFlow<Map<String, ServerHealth>> get() = serverHealthService.serverHealth

    // Last parsed usage tokens (for dashboard) — owned by usageService.
    val usageTokens: StateFlow<CostCalculator.UsageTokens?> get() = usageService.usageTokens

    // Terminal output callback — set by the platform (Android native terminal, Desktop JediTerm)
    var onTerminalOutput: ((sessionId: String, data: String) -> Unit)? = null

    // Tab switch callback — platform clears terminal and replays buffer
    var onTabSwitched: ((sessionId: String, bufferedOutput: String) -> Unit)? = null

    // Disconnect callback
    var onSessionDisconnect: ((sessionId: String) -> Unit)? = null

    // Session became active callback (for keep-alive etc.)
    var onSessionActive: ((ClaudeSession) -> Unit)? = null

    // Notification callback when Claude needs attention
    var onClaudeNeedsInput: ((sessionId: String, hint: String, isActiveTab: Boolean, body: String?) -> Unit)? = null

    // Context window usage callback (0-100 percent)
    var onContextUpdate: ((sessionId: String, percent: Int) -> Unit)? = null

    // Usage stats callback (session%, week%)
    var onUsageUpdate: ((sessionPercent: Int?, weekPercent: Int?) -> Unit)? = null

    // Fired when a session is permanently forgotten (tab closed). Lets the UI
    // prune the matching entry from its (stale) remote-tmux snapshot so the
    // killed pane doesn't reappear as a "detached remote" row and resurrect
    // into a new empty session when tapped.
    var onSessionForgotten: ((serverId: String, tmuxSessionName: String) -> Unit)? = null

    // Usage percentages parsed from the OMC statusline, keyed by SERVER id —
    // owned by usageService.
    val sessionUsagePercents: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> get() = usageService.sessionUsagePercents
    val weekUsagePercents: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> get() = usageService.weekUsagePercents

    // Time-to-reset (minutes) parsed from the OMC `(XhYm)` suffix — owned by usageService.
    val sessionResetMin: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> get() = usageService.sessionResetMin
    val weekResetMin: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> get() = usageService.weekResetMin



    // Persistent reconnect re-arm loops (one per session). Started whenever a
    // reconnect path gives up (autoReconnect exhausted, reconnectSession threw)
    // so that NO failure is terminal: the loop keeps calling reconnectSession
    // with capped backoff until the tab is ACTIVE again or removed. Without
    // this, a multi-minute outage (flaky roaming network) burned autoReconnect's
    // 3 attempts and the session sat DISCONNECTED forever — only an app restart
    // (which re-runs restoreAndReconnect) recovered it.
    private val reconnectRetryJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    // Live reconnect progress per session (attempt count / next-retry time), so
    // the UI can show a "Reconnecting…"/"Retrying in Ns" indicator on the
    // focused banner AND non-focused grid panes instead of a blank/frozen look.
    // Entry present ⇒ a reconnect is actively in progress; absence ⇒ idle.
    private val _reconnectStatus = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ReconnectInfo>>(emptyMap())
    val reconnectStatus: kotlinx.coroutines.flow.StateFlow<Map<String, ReconnectInfo>> = _reconnectStatus

    // Active Claude `/login` OAuth flow detected on the current screen, or null —
    // owned by notificationService (fed by InputPromptDetector.onLoginDetected).
    val loginFlow: kotlinx.coroutines.flow.StateFlow<com.clauderemote.model.LoginFlowState?> get() = notificationService.loginFlow

    /** Clear the login card for [sessionId] (user submitted the code or cancelled). */
    fun clearLoginFlow(sessionId: String) = notificationService.clearLoginFlow(sessionId)

    /**
     * Arm (idempotently) the persistent reconnect loop for [sessionId].
     * Backoff 2s → 60s (+jitter). Exits when the tab is ACTIVE, the tab is
     * gone (closed/forgotten), or the job is cancelled by [disconnectSession].
     * CONNECTING rounds are skipped, not aborted — if that in-flight attempt
     * fails it re-arms via reconnectSession's catch anyway.
     */
    private fun armReconnectRetry(sessionId: String) {
        val existing = reconnectRetryJobs[sessionId]
        if (existing?.isActive == true) return
        reconnectRetryJobs[sessionId] = reconnectScope.launch {
            try {
                var attempt = 1
                while (isActive) {
                    val base = (2000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(60_000L)
                    val wait = base + kotlin.random.Random.nextLong(500)
                    // Unbounded background phase: publish a countdown target so the
                    // UI shows "Retrying in Ns" (maxAttempts <= 0 ⇒ no fixed cap).
                    _reconnectStatus.update { it + (sessionId to ReconnectInfo(attempt, maxAttempts = -1, nextRetryAtMillis = System.currentTimeMillis() + wait)) }
                    kotlinx.coroutines.delay(wait)
                    val tab = tabManager.getTab(sessionId) ?: break // closed/forgotten
                    if (tab.status == SessionStatus.ACTIVE) break   // recovered elsewhere
                    if (tab.status != SessionStatus.CONNECTING) {
                        FileLogger.log(TAG, "Re-arm reconnect attempt $attempt for $sessionId")
                        // Attempt in flight — no countdown target.
                        _reconnectStatus.update { it + (sessionId to ReconnectInfo(attempt, maxAttempts = -1, nextRetryAtMillis = null)) }
                        try {
                            reconnectSession(sessionId)
                        } catch (e: Exception) {
                            FileLogger.error(TAG, "Re-arm reconnect attempt $attempt failed for $sessionId", e)
                        }
                        if (tabManager.getTab(sessionId)?.status == SessionStatus.ACTIVE) break
                    }
                    attempt++
                }
            } finally {
                // Clears on recovery, tab-close, or cancellation (disconnectSession).
                _reconnectStatus.update { it - sessionId }
                reconnectRetryJobs.remove(sessionId)
            }
        }
    }

    /**
     * (Re)start ALL per-session background loops, idempotently (each start*
     * cancels its predecessor). Single entry point shared by launchSession and
     * both reconnect paths — previously the reconnect paths restarted only the
     * notify watcher + sessionId refresh, so usage/git/latency pollers that had
     * exited during the outage stayed dead until app restart.
     */
    private fun attachSessionRuntime(sessionId: String, tmuxSessionName: String) {
        connectionRegistry.serverIdOf(sessionId)?.let { serverId ->
            // Per-server loops: idempotent, first session on the server starts
            // them, later sessions just register/reuse.
            usageService.startServerUsagePolling(serverId)
            usageService.startServerRateLimitPolling(serverId)
            serverHealthService.startServerLatencyPolling(serverId)
            notificationService.startNotifyWatcher(sessionId, tmuxSessionName, serverId)
        }
        gitStatusService.startGitStatusPolling(sessionId)
        connectionRegistry.ssh(sessionId)?.let { conn ->
            persistence.startSessionIdRefresh(sessionId, tmuxSessionName, conn)
        }
        // Keep a transcript stream running for every connected session so the
        // notification body (last assistant message) is available even in Raw
        // view, not only after the Chat view has subscribed.
        transcriptService.ensureTranscriptStream(sessionId)
        // …and feed it from the shared per-server stream daemon.
        transcriptService.registerStreamWatch(sessionId)
    }

    @Volatile private var isInBackground = false
    private val reconnectingSessionIds = mutableSetOf<String>()
    /**
     * Proactive teardown on a platform network-lost event (Android
     * ConnectivityManager). The interface our TCP connections rode is gone —
     * they cannot survive — but keepalive wouldn't flag them for up to 20 s
     * (fg) / 2 min (bg), leaving every tab frozen through a Wi-Fi→LTE
     * handover. Kill the pooled transports now; the read loops EOF, each
     * session's autoReconnect arms, and the sweep on onAvailable/onResume
     * reconnects on the new network within ~1 s instead of ~20.
     */
    fun onNetworkLost() {
        FileLogger.log(TAG, "Network lost — tearing down ${connectionRegistry.transportPoolCount()} transport pool(s)")
        // Our own teardown must not count as Tailscale early-death strikes.
        transportResolver.markNetworkTeardown()
        connectionRegistry.teardownAllTransports()
        // The AUTO decision was made on the old network — a Wi-Fi→LTE switch
        // changes Tailscale reachability, so re-probe on the next resolve.
        transportResolver.clearResolvedCache()
    }

    private var logShipper: com.clauderemote.util.LogShipper? = null

    /**
     * Stable per-installation id (AppSettings.installId), handed to us by the
     * platform via [startLogShipping]. Names this device's tmux-client marker
     * files so a reattach can drop OUR OWN stale client for a session without
     * touching another device's — see TmuxProbes.singleClientPreamble. Falls
     * back to a per-PROCESS id if the platform never called startLogShipping
     * (then the marker only survives within this app run).
     */
    @Volatile private var deviceKey: String = "proc-" + Random.nextInt(1 shl 24).toString(16)

    /**
     * Start shipping FileLogger output to the server (one remote file per
     * install id, `~/.claude-remote/logs/<appId>.log`). Rides whatever live
     * pooled connection exists; buffers while offline. Called once from
     * platform init after AppSettings is available.
     */
    fun startLogShipping(appId: String) {
        // Same id doubles as this device's tmux-client marker key — see deviceKey.
        deviceKey = appId
        if (logShipper != null) return
        logShipper = com.clauderemote.util.LogShipper(
            appId = appId,
            scope = reconnectScope,
            liveSession = { connectionRegistry.anyLiveSession() },
        ).also { it.start() }
    }

    /** Call from onPause/onResume to pause heavy background work and save battery. */
    fun setBackgroundMode(background: Boolean) {
        isInBackground = background
        // Back the SSH keepalive off in the background (10s → 60s). At ~13
        // sessions the 10s keepalive was ~78 radio wakeups/min just to detect
        // dead links; 60s cuts that to ~13/min. Foreground restores 10s for fast
        // Starlink-handover detection. JSch applies the new interval live.
        val keepAlive = if (background) 60_000 else 10_000
        connectionRegistry.setKeepAliveIntervalAll(keepAlive)
    }

    /** Remove a deleted server's health entry so no stale state leaks. */
    fun pruneServerHealth(serverId: String) = serverHealthService.pruneServerHealth(serverId)

    fun probeServers(servers: List<SshServer>, force: Boolean = false) =
        serverHealthService.probeServers(servers, force)





    /**
     * Thrown by [sendTmuxCommand] when a tab's tmux is missing AND another
     * device already closed it server-side. [sendTmuxCommand] has already
     * torn the tab down locally by the time this is thrown — callers should
     * stop reconnecting/retrying, not treat it as a connection failure.
     */
    private class SessionClosedElsewhereException : Exception()


    /**
     * Resolve the real claude session_id for a tmux session by reading
     * `~/.claude/sessions/<pane_pid>.json`. Claude *does* keep this file
     * in sync with its current session_id even after /resume — the prior
     * theory that it stayed stale was based on a pid that was simply
     * launched fresh and never resumed. Verified across multiple pids:
     * the file's `sessionId` field matches whatever conversation claude
     * is currently appending to.
     *
     * An earlier "newest jsonl by mtime in cwd" approach was rejected
     * because it returned the same id for every claude pid in the same
     * folder — the most recently touched jsonl might belong to a
     * different process, causing the wrong tab to adopt it.
     */
    private suspend fun readRealSessionId(sshManager: SshManager, tmuxName: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val sshSession = sshManager.getSession() ?: return@withContext null
                val escaped = tmuxName.replace("'", "'\\''")
                // The tmux pane_pid is the `claude` LAUNCHER process; the actual
                // Claude session that writes ~/.claude/sessions/<pid>.json is a
                // CHILD of it (verified: pane_pid 2890285 has no json, its child
                // 2890289 does). Reading sessions/<pane_pid>.json therefore
                // returned nothing, the UUID was never corrected, and the tab
                // stayed pinned to the dead client-generated launch UUID with
                // "Waiting for transcript… 0 entries" forever.
                //
                // Walk the pane's whole process subtree (BFS via a ps ppid
                // table) and take the FIRST (shallowest) pid that has a sessions
                // json. Shallowest = the top-level Claude, not a Task-tool
                // subagent (those are deeper children with their own session
                // files and would point the transcript at the wrong jsonl).
                // EXACT target ('=name'): plain -t prefix-matches, so this used to
                // read the pane pid of a DIFFERENT session whose name starts with
                // ours and pin the tab's transcript to the wrong conversation.
                val cmd = "PID=\$(tmux list-panes -t '=$escaped' -F '#{pane_pid}' 2>/dev/null | head -1); " +
                    "[ -n \"\$PID\" ] || exit 1; " +
                    "PIDS=\$(ps -eo pid=,ppid= 2>/dev/null | awk -v root=\"\$PID\" '" +
                    "{ kids[\$2]=kids[\$2]\" \"\$1 } " +
                    "END { head=0;tail=0;q[tail++]=root; " +
                    "while(head<tail){p=q[head++];print p;m=split(kids[p],a,\" \");" +
                    "for(i=1;i<=m;i++)if(a[i]!=\"\")q[tail++]=a[i]} }'); " +
                    "for p in \$PIDS; do f=\"\$HOME/.claude/sessions/\$p.json\"; " +
                    "[ -f \"\$f\" ] && { jq -r '.sessionId // empty' \"\$f\" 2>/dev/null; break; }; done"
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(cmd)
                ch.inputStream = null
                val input = ch.inputStream
                val out = try {
                    ch.connect(3000)
                    input.bufferedReader().readText().trim()
                } finally {
                    // Disconnect even if connect()/readText() throws — otherwise
                    // the channel leaks on the shared long-lived SSH session
                    // every time this 15 s probe hits an error.
                    try { ch.disconnect() } catch (_: Exception) {}
                }
                if (out.isEmpty() || out == "null" || !out.matches(Regex("^[0-9a-f-]{36}$"))) null else out
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun launchSession(
        server: SshServer,
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel,
        connectionType: ConnectionType,
        tmuxSessionName: String,
        isNewTmuxSession: Boolean = true,
        // When non-null, reuse this EXISTING Claude conversation UUID instead of
        // minting a fresh one — used to resume an orphaned session discovered by
        // ClaudeHistoryScanner. connectSsh already does `claude --resume <uuid>`
        // when the tmux pane is missing but a transcript exists for this UUID.
        resumeClaudeSessionId: String? = null
    ): ClaudeSession = withContext(Dispatchers.IO) {
        // Idempotency guard: a tab for this exact tmux session already exists
        // (typically a second tap on Create while the first launch is still
        // connecting — the dialog stays interactive for the several seconds the
        // SSH connect takes). Launching again would add a duplicate tab AND
        // `kill-session; new-session` the tmux out from under the first one,
        // leaving the older tab as a dead bare terminal. Switch to the existing
        // tab instead.
        tabManager.tabs.value.firstOrNull {
            it.server.id == server.id && it.tmuxSessionName == tmuxSessionName
        }?.let { existing ->
            FileLogger.log(TAG, "launchSession: '$tmuxSessionName' already open as tab ${existing.id} — switching instead of relaunching")
            switchTab(existing.id)
            return@withContext existing
        }
        val sessionId = generateId()
        // Pre-generate a UUID for `claude --session-id <uuid>` so we can later
        // restore the conversation deterministically via `claude --resume <uuid>`.
        // Avoids a polling race against `~/.claude/projects/<encoded-cwd>/*.jsonl`.
        val claudeSessionId = resumeClaudeSessionId ?: generateUuidV4()

        val parsedAlias = com.clauderemote.model.TmuxNameParser.parse(tmuxSessionName, server.name).alias
        val session = ClaudeSession(
            id = sessionId,
            server = server,
            folder = folder,
            mode = mode,
            model = model,
            tmuxSessionName = tmuxSessionName,
            connectionType = connectionType,
            status = SessionStatus.CONNECTING,
            alias = parsedAlias,
            claudeSessionId = claudeSessionId
        )

        terminalIO.initBuffer(sessionId)
        tabManager.addTab(session)
        FileLogger.log(TAG, "Launching session: ${server.name} → $folder (${connectionType.name}, ${mode.name}, ${model.name})")

        try {
            when (connectionType) {
                ConnectionType.SSH -> connectSsh(session, isNewTmuxSession)
                ConnectionType.MOSH -> connectMosh(session, isNewTmuxSession)
            }

            serverStorage.updateServer(server.withRecentFolder(folder))
            tabManager.updateTabStatus(sessionId, SessionStatus.ACTIVE)
            FileLogger.log(TAG, "Session active: $sessionId")
            statusService.updateActivity(sessionId, SessionActivity.WAITING_FOR_INPUT)
            onSessionActive?.invoke(session)
            attachSessionRuntime(sessionId, session.tmuxSessionName)
            // Persist session for app-restart and server-reboot recovery.
            sessionStorage?.upsert(SessionStorage.fromClaudeSession(session))
            connectionRegistry.ssh(sessionId)?.let { conn ->
                reconnectScope.launch {
                    persistence.ensureRestoreService(conn)
                    persistence.pushSessionsToServer(conn, server.id)
                }
            }
            session.copy(status = SessionStatus.ACTIVE)
        } catch (e: Exception) {
            FileLogger.error(TAG, "Session launch failed", e)
            tabManager.updateTabStatus(sessionId, SessionStatus.ERROR)
            throw e
        }
    }

    /**
     * Switch the active tab. Notifies platform to clear terminal and replay buffer.
     * The platform is expected to follow up with a [resize] call using the *current*
     * TerminalView dimensions — each session's [SshManager.lastCols]/[lastRows]
     * reflect the last time *that* session was active and may be stale when we
     * switch from a session that was resized differently. See MainActivity for the
     * SIGWINCH kick after tab switch.
     */
    fun switchTab(id: String) {
        // FIX 4: when switching away from a session whose activity is APPROVAL_NEEDED,
        // reset it to WAITING_FOR_INPUT — we can no longer read its screen to confirm
        // the dialog is still showing, so leaving APPROVAL_NEEDED would produce a stale
        // "needs attention" badge (#55) that never clears.
        val previousId = tabManager.activeTabId.value
        if (previousId != null && previousId != id &&
            statusService.currentActivity(previousId) == SessionActivity.APPROVAL_NEEDED) {
            statusService.updateActivity(previousId, SessionActivity.WAITING_FOR_INPUT)
        }
        tabManager.switchTab(id)
        notificationService.promptDetector.onUserInput(id)
        // Re-verify the Claude session UUID whenever we (re)enter a session.
        // While this tab was in the background Claude may have rotated its
        // session id (/clear, /compact, /resume) — leaving confirmedUuids
        // pointing at a dead .jsonl. Clearing it here lets the next
        // transcriptFlow() call (UI re-subscribes on active-tab change) fire a
        // fresh kick-probe and re-point the transcript stream at the live file.
        // a08359c cleared this on reconnect but missed plain tab switches,
        // which is why the chat only refreshed after an app restart.
        transcriptService.clearConfirmedUuid(id)
        val tail = terminalIO.bufferTail(id)
        notificationService.promptDetector.suppressFor(2000)
        onTabSwitched?.invoke(id, tail)
    }

    /**
     * Force tmux to resend the FULL screen for [sessionId]. The 2 KB tail we
     * replay on tab switch is usually a partial update (statusline, cursor
     * move) rather than a full screen dump, and tmux's damage tracking has no
     * idea the client just cleared its local screen — a geometry-toggle
     * SIGWINCH often produces only a minimal diff (or nothing at all when the
     * two resizes coalesce), which is why the first switch sometimes painted
     * partially until a second click on the session. `tmux refresh-client`
     * is the deterministic fix: it explicitly invalidates tmux's notion of
     * what the client has and resends the whole frame, independent of
     * geometry. The SIGWINCH toggle is kept only as a fallback for when the
     * exec channel fails (connection mid-reconnect, tmux probe error).
     *
     * Called by the platform after it has laid out the terminal for the new
     * session at the current dimensions. Safe to call from the UI thread —
     * the SSH round-trip runs on [reconnectScope].
     */
    fun kickRedraw(sessionId: String, cols: Int, rows: Int) = tmuxProbes.kickRedraw(sessionId, cols, rows)

    /**
     * Lazy transcript stream for a session. First access opens an SSH `tail -F`
     * against `~/.claude/projects/<encoded-cwd>/<uuid>.jsonl` and starts
     * incremental parsing. Subsequent calls return the same flow.
     *
     * If the tab does not yet have a `claudeSessionId` (fresh launch, server
     * UUID poll hasn't completed), the stream is created but idle. It will
     * auto-start when [notifyClaudeSessionIdChanged] fires, so callers don't
     * need to re-call this method.
     */
    /**
     * The most recent assistant message text for a session, for the
     * "Claude is ready" notification body — owned by transcriptService.
     */
    fun lastAssistantText(sessionId: String): String? = transcriptService.lastAssistantText(sessionId)

    /**
     * Last [limit] user/assistant messages (oldest→newest) for the watch's
     * lazy history fetch (see PhoneWearService's /history-request) — owned by
     * transcriptService.
     */
    fun recentMessages(sessionId: String, limit: Int = 10): List<Pair<String, String>> =
        transcriptService.recentMessages(sessionId, limit)

    fun transcriptFlow(sessionId: String): kotlinx.coroutines.flow.StateFlow<List<TranscriptEntry>> =
        transcriptService.transcriptFlow(sessionId)

    /**
     * Diagnostic status of the transcript tail for [sessionId] — what it's doing
     * / why no data yet (connecting, retry+error, "no transcript data yet").
     * Null once entries flow. Shown in the "Waiting for transcript…" state.
     */
    fun transcriptStatusFlow(sessionId: String): kotlinx.coroutines.flow.StateFlow<String?> =
        transcriptService.transcriptStatusFlow(sessionId)

    /**
     * Lazy poller for OMC remote state (active skill, in-flight subagents).
     * Polls two small state files via SSH stat+cat every ~5 s; idle traffic
     * stays under ~50 B/s. Cached per session and cleaned up on disconnect.
     */
    fun remoteStatusFlow(sessionId: String) = statusService.remoteStatusFlow(sessionId)

    private suspend fun connectSsh(
        session: ClaudeSession,
        isNewTmuxSession: Boolean,
        checkClosedElsewhere: Boolean = false,
        forceSsh: Boolean = false,
    ) {
        // Opt-in Eternal Terminal path: a session that prefers ET runs its
        // terminal through the ET client (resumes over TCP) rather than the raw
        // SSH shell. connectEt reuses this same SSH connection as its carrier,
        // and calls back with forceSsh=true to fall back to plain SSH if the ET
        // path can't be established (avoids recursing back into connectEt).
        if (session.server.preferEternal && !forceSsh) { connectEt(session, isNewTmuxSession); return }
        val sshManager = SshManager(serverStorage, transportPool = connectionRegistry.transportPool(session.server.id))
        connectionRegistry.putSsh(session.id, sshManager)

        // Track last output time for burst detection
        var lastOutputTime = 0L
        var burstMode = true // Start in burst mode (tmux attach sends lots of data)

        fun emit(text: String) {
            terminalIO.append(session.id, text)
            val isActive = tabManager.activeTabId.value == session.id
            if (isActive) {
                onTerminalOutput?.invoke(session.id, text)
            }

            // Feed output FIRST — detector handles buffering + schedules the
            // quiescence check. This must run for EVERY chunk, including
            // burst chunks: Claude's end-of-turn repaint often lands as
            // sub-50ms chunks, and skipping them meant the quiescence timer
            // never armed and the idle check silently never ran (missed
            // notification). The call is cheap (buffer append + timer reset);
            // reconnect false-positives are covered by suppressFor().
            notificationService.promptDetector.onOutput(session.id, text)

            // Skip the remaining expensive processing during data bursts
            // (tmux attach/scrollback).
            val now = System.currentTimeMillis()
            if (now - lastOutputTime < 50) {
                burstMode = true
                lastOutputTime = now
                return // Skip activity tracking during burst
            }
            if (burstMode && now - lastOutputTime >= 200) {
                burstMode = false // Burst ended, resume processing
            }
            lastOutputTime = now
            if (burstMode) return

            // Drive working/idle from the OMC statusline (flows in every view,
            // unlike the Stop hook which can silently fail). The screen classifier
            // now also works in Chat for the active single-pane session on Android
            // (#75: emulator is kept composed under the Chat overlay), but the
            // statusline remains the ground truth for all other cases. This is the
            // continuous source that keeps the status dot/badge honest and was
            // missing for hook-active sessions in chat view.
            notificationService.promptDetector.parseClaudeWorking(session.id)?.let { working ->
                val next = if (working) SessionActivity.WORKING else SessionActivity.WAITING_FOR_INPUT
                // Claude actively working → a new turn started; re-arm the
                // notify latch so the next idle/approval can notify again
                // even if the user never typed in this app (dismissed the
                // notification, answered from another client, …).
                if (working) notificationService.promptDetector.onClaudeWorking(session.id)
                // FIX 3: when the statusline says "not working" (→ WAITING_FOR_INPUT),
                // do NOT overwrite APPROVAL_NEEDED — the permission dialog is still on
                // screen and the OMC statusline shows no elapsed time while it waits.
                // A genuine WORKING result from the statusline may still override APPROVAL.
                if (next == SessionActivity.WAITING_FOR_INPUT &&
                    statusService.currentActivity(session.id) == SessionActivity.APPROVAL_NEEDED) return@let
                // Grace: a WORKING statusline within 8s of APPROVAL being asserted
                // is more likely a STALE render flowing back through the buffer
                // (kickRedraw / replay re-printing an old "thinking" segment) than
                // a real resume. The detector re-confirms APPROVAL from the screen
                // every ~3s while the dialog is up (refreshing the timestamp), so
                // a genuine resume still takes over within a beat of the dialog
                // actually closing.
                if (next == SessionActivity.WORKING &&
                    statusService.currentActivity(session.id) == SessionActivity.APPROVAL_NEEDED &&
                    System.currentTimeMillis() - statusService.lastApprovalAtMs(session.id) < 8_000L) return@let
                statusService.updateActivity(session.id, next)
            }
            // ctx % is derived from the transcript (startContextTokenCollector),
            // not scraped. We still read the statusline's `ctx:NN%` here, but
            // only to CALIBRATE the window size for this session: with the live
            // token count we can back out whether it's a 200k or 1M window and
            // cache it. One sighting is enough; afterwards the transcript drives
            // the displayed %.
            if (transcriptService.needsWindowCalibration(session.id)) {
                // Gate on sawWork so we only pair a FRESH statusline ctx:NN% with
                // the live token count. Calibrating off a stale scrollback pct
                // (e.g. 5% paired with 180k live tokens) would mis-snap the
                // window to 1M and stick the chip wrong for the whole session.
                transcriptService.calibrateWindow(session.id, notificationService.promptDetector.parseContextPercent(session.id, text))
            }
            // 5h / week usage are account-level (not in the transcript) so they
            // stay scraped from the OMC statusline — but only once the session
            // has actually worked, so we don't surface stale scrollback values.
            if (transcriptService.hasSeenWork(session.id)) {
                val usage = notificationService.promptDetector.parseUsage(session.id, text)
                if (usage != null) {
                    usageService.applyStatusline(session.server.id, usage)
                }
            }
        }

        val sshEffective = transportResolver.resolveTransport(session.server)
        transportResolver.setConnectionLabel(session.id, sshEffective, session.server, "SSH")
        try {
            // Gate the handshake per server — see connectGates.
            connectionRegistry.connectGate(session.server.id).withPermit {
                sshManager.connect(
                    sshEffective,
                    onOutput = { data -> emit(data) },
                    onConnectionLost = {
                        transportResolver.maybeCountTsEarlyDeath(session)
                        transportResolver.maybeCountCfEarlyDeath(session)
                        // Auto-reconnect with tmux reattach
                        tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
                        statusService.updateActivity(session.id, SessionActivity.DISCONNECTED)
                        reconnectScope.launch {
                            autoReconnect(session, ::emit)
                        }
                    },
                    initialCols = terminalIO.effectiveSize(session.id).first,
                    initialRows = terminalIO.effectiveSize(session.id).second,
                )
            }
        } catch (e: Exception) {
            transportResolver.noteConnectResult(session.server, sshEffective, ok = false)
            throw e
        }
        transportResolver.noteConnectResult(session.server, sshEffective, ok = true)
        transportResolver.recordConnectSuccess(session.id, session.server, sshEffective)

        // Wait for shell prompt (detect $ or # or >, max 3s)
        tmuxProbes.waitForShellPrompt(session.id, 3000)

        // Startup command
        if (session.server.startupCommand.isNotBlank()) {
            sshManager.sendInput(session.server.startupCommand + "\n")
            tmuxProbes.waitForShellPrompt(session.id, 3000)
        }

        // Ensure Claude Code's Stop hook is configured → enables hook-based
        // idle detection (fast, reliable) instead of screen-state polling.
        notificationService.ensureStopHook(sshManager)
        // Install/refresh the transcript stream daemon script (one shared
        // delta channel per server instead of per-session polling).
        transcriptService.ensureStreamd(sshManager)

        // Tmux
        sendTmuxCommand(sshManager, session, isNewTmuxSession, checkClosedElsewhere)
        notificationService.promptDetector.suppressFor(3000) // suppress during tmux screen redraw

        // Apply the effective terminal dimensions — TerminalView won't fire
        // onResize when its size hasn't changed, and a session that (re)connected
        // while backgrounded has no remembered size of its own. effectiveSize()
        // falls back to the last active-view size so the pane always fills the window.
        val (cols, rows) = terminalIO.effectiveSize(session.id)
        sshManager.resize(cols, rows)
    }

    /**
     * The `tmux attach-session` line every transport (SSH shell, mosh startup
     * command, ET startup command) uses to reattach an EXISTING session.
     *
     * Everything in it is load-bearing:
     *  - [TmuxProbes.singleClientPreamble] first: drop THIS device's previous
     *    client for the session if it's still attached. A half-open SSH socket
     *    (CF/Starlink) or an ET-preserved shell keeps the old `tmux attach`
     *    alive, so without this a reconnect left TWO focused clients of
     *    different sizes on one session, fighting over its layout — the resize
     *    churn we believe SIGSEGVs tmux 3.3a and kills every session at once.
     *    It is device-scoped (never `detach-client -a`), so two DIFFERENT
     *    devices can still hold one client each.
     *  - no `-d`: intentional multi-device attach keeps working.
     *  - kickRedraw's `resize-window` for sizing (NEVER `window-size manual`,
     *    which SIGSEGVs the tmux server — see ClaudeConfig's crash guard) (last-device
     *    priority) instead of `latest`, so a stale 80x24 client can't shrink
     *    the pane. See TmuxProbes.forceWindowSize.
     *  - EXACT target `-t '=name'`: plain `-t` prefix-matches, which could
     *    attach to a DIFFERENT session whose name merely starts with ours.
     */
    private fun buildAttachCommand(tmuxSessionName: String): String {
        val escaped = tmuxSessionName.replace("'", "'\\''")
        return tmuxProbes.singleClientPreamble(tmuxSessionName, deviceKey) +
            // Un-pin: release any window a historical `resize-window` left in
            // `window-size manual` so it tracks the most-recently-active client
            // again (current-device-wins). We never set `manual` anymore; this
            // only heals windows pinned by older builds.
            "tmux set-option -w -t '=$escaped' window-size latest 2>/dev/null; " +
            "tmux set-option -g history-limit 100000 2>/dev/null; " +
            "tmux attach-session -t '=$escaped'"
    }

    private suspend fun sendTmuxCommand(
        sshManager: SshManager,
        session: ClaudeSession,
        isNew: Boolean,
        checkClosedElsewhere: Boolean = false,
    ) {
        if (isNew) {
            val command = ClaudeConfig.buildTmuxLaunchCommand(
                tmuxSessionName = session.tmuxSessionName,
                folder = session.folder,
                mode = session.mode,
                model = session.model,
                claudeSessionId = session.claudeSessionId,
                resume = false
            )
            sshManager.sendInput(command + "\n")
        } else {
            // Probe tmux first. If the named session is gone (server reboot,
            // someone killed it), recreate it and re-launch claude with --resume
            // so the conversation continues. Otherwise plain attach.
            val tmuxExists = tmuxProbes.probeTmuxSession(sshManager, session.tmuxSessionName)
            if (!tmuxExists && checkClosedElsewhere && !persistence.stillTrackedOnServer(sshManager, session.tmuxSessionName)) {
                // Another device's forgetSession() already pushed this tmux name
                // out of the shared sessions.json — respect that instead of
                // resurrecting a session the user consciously closed elsewhere.
                // Only trusted for the reconnect-to-an-already-tracked-tab path
                // (checkClosedElsewhere=true); launchSession's attach/history-resume
                // callers pass false since their target may legitimately be new
                // to sessions.json.
                //
                // BUT "!tmuxExists && !stillTracked" is AMBIGUOUS: a WHOLE-server
                // tmux death makes probeTmuxSession=false for EVERY session AND
                // (with a wiped/empty manifest) stillTrackedOnServer=false too, so
                // this branch used to forget every session on a transient
                // server-wide outage (the repeated whole-server session loss).
                // Gate on positive liveness: only treat it as closed-elsewhere
                // when the tmux server is PROVABLY up with ≥1 OTHER live
                // claude-server-* session. No live peers ⇒ whole-server outage ⇒
                // fall through to the rebuild/--resume path instead of forgetting.
                if (serverHasOtherLiveSession(sshManager, session.tmuxSessionName)) {
                    FileLogger.log(TAG, "Tmux '${session.tmuxSessionName}' missing, untracked, and live peers exist — closed on another device, forgetting locally")
                    sessionStorage?.remove(session.id)
                    disconnectSession(session.id)
                    throw SessionClosedElsewhereException()
                }
                FileLogger.log(TAG, "Tmux '${session.tmuxSessionName}' missing + untracked but NO live peer sessions — treating as whole-server outage, rebuilding/--resume instead of forgetting")
            }
            // See buildAttachCommand for the attach semantics (single client per
            // device, no -d, manual window sizing, exact target).
            val command = if (tmuxExists) {
                buildAttachCommand(session.tmuxSessionName)
            } else if (session.claudeSessionId != null) {
                // Resume only works if claude actually wrote a transcript file
                // for this UUID. The transcript appears lazily — first user/
                // assistant turn — so a session that was launched but never
                // interacted with has no jsonl, and `--resume` would print
                // "No conversation found". In that case we re-launch fresh
                // with the same `--session-id` so future restarts can resume.
                val hasTranscript = tmuxProbes.probeTranscriptExists(sshManager, session.folder, session.claudeSessionId)
                if (hasTranscript) {
                    FileLogger.log(TAG, "Tmux '${session.tmuxSessionName}' missing — rebuilding with claude --resume ${session.claudeSessionId}")
                    ClaudeConfig.buildTmuxLaunchCommand(
                        tmuxSessionName = session.tmuxSessionName,
                        folder = session.folder,
                        mode = session.mode,
                        model = session.model,
                        claudeSessionId = session.claudeSessionId,
                        resume = true
                    )
                } else {
                    FileLogger.log(TAG, "Tmux '${session.tmuxSessionName}' missing and no transcript for ${session.claudeSessionId} — fresh launch with same --session-id")
                    ClaudeConfig.buildTmuxLaunchCommand(
                        tmuxSessionName = session.tmuxSessionName,
                        folder = session.folder,
                        mode = session.mode,
                        model = session.model,
                        claudeSessionId = session.claudeSessionId,
                        resume = false
                    )
                }
            } else {
                FileLogger.log(TAG, "Tmux '${session.tmuxSessionName}' missing and no claudeSessionId — fresh launch")
                ClaudeConfig.buildTmuxLaunchCommand(
                    tmuxSessionName = session.tmuxSessionName,
                    folder = session.folder,
                    mode = session.mode,
                    model = session.model
                )
            }
            FileLogger.log(TAG, "Attaching to tmux: $command")
            sshManager.sendInput(command + "\n")
        }
    }

    /**
     * True iff the tmux server is PROVABLY up with ≥1 OTHER live `claude-server-*`
     * session besides [excludeTmuxName] (and the `__anchor__` keepalive). Used to
     * disambiguate "one session was closed on another device" (peers still alive)
     * from a WHOLE-server tmux death (list empty / server down / exec error) — the
     * latter must NOT be treated as closed-elsewhere or a transient server-wide
     * outage forgets every session. On ANY SSH/exec error returns false (unknown ⇒
     * do NOT assume peers are alive). Exposed `internal` so App.kt can reuse the
     * same liveness gate via the orchestrator.
     */
    internal suspend fun serverHasOtherLiveSession(sshManager: SshManager, excludeTmuxName: String): Boolean {
        return try {
            val jsch = sshManager.getSession() ?: return false
            val out = execCapture(jsch, "tmux list-sessions -F '#{session_name}' 2>/dev/null")
            out.lineSequence()
                .map { it.trim() }
                .any { it.startsWith("claude-server-") && it != excludeTmuxName && it != "__anchor__" }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Probe whether a tmux session [tmuxName] exists on [server], and if so
     * whether its first pane's working directory matches [cwd].
     *
     * Returns:
     *  - `null`  — no such tmux session (safe to create a new one)
     *  - `true`  — session exists AND its pane cwd matches [cwd] (same conversation, attach)
     *  - `false` — session exists with a DIFFERENT cwd (collision — do NOT kill it)
     *
     * Fail-open on SSH / exec errors: returns `null` so the caller falls through
     * to the normal resume path rather than blocking the user.
     */
    suspend fun tmuxPaneMatchesCwd(server: SshServer, tmuxName: String, cwd: String): Boolean? =
        remoteOps.tmuxPaneMatchesCwd(server, tmuxName, cwd)

    private suspend fun autoReconnect(
        session: ClaudeSession,
        emit: (String) -> Unit,
        maxAttempts: Int = 3
    ) {
        synchronized(reconnectingSessionIds) {
            if (!reconnectingSessionIds.add(session.id)) return // already reconnecting
        }
        try {
            // Closed/forgotten while the loss event was in flight — don't
            // resurrect a connection for a tab that no longer exists.
            if (tabManager.getTab(session.id) == null) return
            // Invalidate the confirmed UUID before the first reconnect attempt so
            // the transcript kick-probe fires after we come back up. If claude
            // restarted (new pid, new session UUID) during the outage, the probe
            // will adopt the new UUID; without this the stale UUID stays "confirmed"
            // and the transcript stream tails a file that no longer exists.
            transcriptService.clearConfirmedUuid(session.id)
            for (attempt in 1..maxAttempts) {
                emit("\r\n\u001B[33mConnection lost. Reconnecting ($attempt/$maxAttempts)...\u001B[0m\r\n")
                FileLogger.log(TAG, "Auto-reconnect attempt $attempt/$maxAttempts for ${session.id}")
                // Publish live progress so the focused banner + non-focused grid
                // panes show "Reconnecting (N/3)…" instead of a frozen blank.
                _reconnectStatus.update { it + (session.id to ReconnectInfo(attempt, maxAttempts, nextRetryAtMillis = null)) }
                // Attempt 1 fires IMMEDIATELY (no backoff): the dominant drop
                // cause on Starlink is a satellite-handover public-IP change that
                // kills the old TCP while the new path is already up — so an
                // instant reconnect almost always succeeds and turns a freeze
                // into a sub-second blip (tmux preserves the session). Backoff
                // (2s, 4s, 8s …, capped 30s) only kicks in from attempt 2 for a
                // genuine outage, plus 0–500ms jitter against retry storms.
                val base = if (attempt == 1) 0L
                           else (2000L shl (attempt - 2).coerceAtMost(5)).coerceAtMost(30_000L)
                // Cloudflare has no transport to fall back to, so a CF
                // connect-then-die storm can't be broken by switching paths the
                // way the Tailscale brake does. Once CF is provably flapping,
                // floor even attempt 1's zero backoff so the tight loop becomes
                // a gentle escalating retry instead of a battery-draining
                // connect→die spin. Zero until CF dies early twice in a row, so
                // a genuine single handover still reconnects instantly.
                val floor = transportResolver.cfEarlyDeathBackoffMs(session.server)
                val jitter = kotlin.random.Random.nextLong(500)
                val wait = maxOf(base, floor) + jitter
                if (wait > 0) kotlinx.coroutines.delay(wait)
                // Tab closed during the backoff — stop reconnecting it.
                if (tabManager.getTab(session.id) == null) { _reconnectStatus.update { it - session.id }; return }

                try {
                    // Clean up old connection
                    connectionRegistry.ssh(session.id)?.disconnect()
                    connectionRegistry.removeSsh(session.id)

                    val sshManager = SshManager(serverStorage, transportPool = connectionRegistry.transportPool(session.server.id))
                    connectionRegistry.putSsh(session.id, sshManager)

                    val reEffective = transportResolver.resolveTransport(session.server)
                    transportResolver.setConnectionLabel(session.id, reEffective, session.server, "SSH")
                    try {
                        // Gate the handshake per server: a blip drops all
                        // sessions at once and each fires attempt-1 with zero
                        // backoff — without the gate that's a 21-way concurrent
                        // KEX storm on a just-recovered weak link.
                        connectionRegistry.connectGate(session.server.id).withPermit {
                            sshManager.connect(
                                reEffective,
                                onOutput = { data -> emit(data) },
                                onConnectionLost = {
                                    transportResolver.maybeCountTsEarlyDeath(session)
                                    transportResolver.maybeCountCfEarlyDeath(session)
                                    tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
                                    reconnectScope.launch { autoReconnect(session, emit) }
                                },
                                initialCols = terminalIO.effectiveSize(session.id).first,
                                initialRows = terminalIO.effectiveSize(session.id).second,
                            )
                        }
                    } catch (e: Exception) {
                        transportResolver.noteConnectResult(session.server, reEffective, ok = false)
                        throw e
                    }
                    transportResolver.noteConnectResult(session.server, reEffective, ok = true)
                    transportResolver.recordConnectSuccess(session.id, session.server, reEffective)

                    // Wait for shell prompt, clear garbage, then attach tmux
                    tmuxProbes.waitForShellPrompt(session.id, 3000)
                    sshManager.sendInput("\u0003\n") // Ctrl-C + Enter to clear
                    kotlinx.coroutines.delay(100)
                    sendTmuxCommand(sshManager, session, false, checkClosedElsewhere = true)
                    notificationService.promptDetector.suppressFor(3000) // suppress during tmux screen redraw after reconnect

                    // Re-send terminal dimensions — the TerminalView hasn't
                    // changed size so onResize won't fire. A session reconnecting
                    // while backgrounded has no remembered size of its own, so use
                    // effectiveSize() (last active-view size fallback) to always
                    // apply a correct size and avoid tmux rendering at 80x24.
                    run {
                        val (cols, rows) = terminalIO.effectiveSize(session.id)
                        kotlinx.coroutines.delay(200) // let tmux attach settle
                        sshManager.resize(cols, rows)
                    }

                    tabManager.updateTabStatus(session.id, SessionStatus.ACTIVE)
                    statusService.updateActivity(session.id, SessionActivity.WAITING_FOR_INPUT)
                    onSessionActive?.invoke(session)
                    // Restart ALL per-session loops, not just watcher+refresh —
                    // usage/git/latency pollers may have died during the outage.
                    attachSessionRuntime(session.id, session.tmuxSessionName)
                    emit("\r\n\u001B[32mReconnected!\u001B[0m\r\n")
                    FileLogger.log(TAG, "Auto-reconnect succeeded for ${session.id}")
                    _reconnectStatus.update { it - session.id }
                    claudeControl.flushPendingInputs(session.id)
                    return
                } catch (e: SessionClosedElsewhereException) {
                    FileLogger.log(TAG, "Auto-reconnect for ${session.id} aborted — closed on another device")
                    _reconnectStatus.update { it - session.id }
                    return
                } catch (e: Exception) {
                    FileLogger.error(TAG, "Auto-reconnect attempt $attempt failed", e)
                }
            }

            emit("\r\n\u001B[31mReconnect failed after $maxAttempts attempts.\u001B[0m\r\n")
            onSessionDisconnect?.invoke(session.id)
            // NOT terminal: hand off to the persistent re-arm loop, which keeps
            // calling reconnectSession with capped backoff (up to 60s) until the
            // tab is ACTIVE or removed. Previously the session was stranded
            // DISCONNECTED forever once the 3 quick attempts burned out — on a
            // multi-minute outage (roaming, tunnel) that was every time, and
            // only an app restart recovered the session.
            tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
            armReconnectRetry(session.id)
        } finally {
            synchronized(reconnectingSessionIds) { reconnectingSessionIds.remove(session.id) }
        }
    }

    private suspend fun connectMosh(session: ClaudeSession, isNewTmuxSession: Boolean) {
        // Mosh needs DIRECT UDP. Over the Tailscale path (effective host=100.x,
        // no CF proxy) the UDP rides the WireGuard tunnel and mosh's roaming
        // survives Starlink egress-IP changes outright — the ideal path. Over
        // the CF tunnel UDP is impossible → fall back to SSH (connectSsh picks
        // the best available path itself).
        val effectiveServer = transportResolver.resolveTransport(session.server)
        if (effectiveServer.useCloudflareProxy) {
            FileLogger.log(TAG, "Mosh needs direct UDP — no reachable Tailscale path, falling back to SSH")
            val warning = "\r\n\u001B[33mMosh requires direct UDP — falling back to SSH (Cloudflare tunnel)\u001B[0m\r\n"
            terminalIO.append(session.id, warning)
            onTerminalOutput?.invoke(session.id, warning)
            connectSsh(session, isNewTmuxSession)
            return
        }

        FileLogger.log(TAG, "Connecting via Mosh to ${session.server.name} (${effectiveServer.host})")
        val moshManager = com.clauderemote.connection.MoshManager()

        // Build tmux command as mosh startup command
        val tmuxCmd = if (isNewTmuxSession) {
            ClaudeConfig.buildTmuxLaunchCommand(
                tmuxSessionName = session.tmuxSessionName,
                folder = session.folder,
                mode = session.mode,
                model = session.model
            )
        } else {
            // On a MISS (server reboot / tmux death) do NOT `tmux new-session -A`:
            // that (a) starts a BARE shell with no claude/--resume, and (b) parents
            // the tmux SERVER under this ephemeral mosh login scope so it dies on
            // teardown (the churn/mass-death root cause). Route the (re)create
            // through buildTmuxLaunchCommand, which anchors the server via
            // `systemd-run --user --scope` AND relaunches claude with --resume —
            // mirroring the SSH reconnect path. Grouped with `{ …; }` so the whole
            // relaunch (a `;`-separated sequence) is conditional on the attach miss.
            val relaunch = ClaudeConfig.buildTmuxLaunchCommand(
                tmuxSessionName = session.tmuxSessionName,
                folder = session.folder,
                mode = session.mode,
                model = session.model,
                claudeSessionId = session.claudeSessionId,
                resume = true
            )
            "${buildAttachCommand(session.tmuxSessionName)} 2>/dev/null || { $relaunch ; }"
        }

        fun emit(text: String) {
            terminalIO.append(session.id, text)
            val isActive = tabManager.activeTabId.value == session.id
            if (isActive) {
                onTerminalOutput?.invoke(session.id, text)
            }
            notificationService.promptDetector.onOutput(session.id, text)
        }

        val success = moshManager.connect(
            effectiveServer,
            startupCommand = tmuxCmd,
            onOutput = { data -> emit(data) },
            onDisconnect = {
                tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
                onSessionDisconnect?.invoke(session.id)
            }
        )

        if (!success) {
            emit("\r\n\u001B[33mMosh connection failed. Falling back to SSH...\u001B[0m\r\n")
            FileLogger.log(TAG, "Mosh failed, falling back to SSH")
            connectSsh(session, isNewTmuxSession)
            return
        }

        // Store mosh as connection (wrap in a pseudo SshManager interface won't work,
        // so store separately and handle sendInput/sendBytes via mosh)
        connectionRegistry.putMosh(session.id, moshManager)
        transportResolver.setConnectionLabel(session.id, effectiveServer, session.server, "Mosh")
        FileLogger.log(TAG, "Mosh connected for ${session.id}")
    }

    /**
     * Opt-in Eternal Terminal path. ET resumes the session over TCP, so a
     * Starlink egress-IP change (CF WebSocket drop + rebuild) becomes a
     * seamless replay instead of a full tmux redraw.
     *
     * Flow (validated end-to-end on a desktop PoC): open a normal SSH
     * connection over the resolved transport as the CARRIER; over it (a) run
     * `etterminal` to register a session with etserver and parse its IDPASSKEY,
     * and (b) forward a local port to etserver:2022. Then run the patched `et`
     * client (--idpasskey skips ssh bootstrap, --pty gives it a controlling TTY
     * under the pipe-based Process) against that local forward. ET's own
     * resumable TCP rides the forward; on a carrier drop we rebuild it and ET
     * replays.
     *
     * FIRST INTEGRATION (needs on-device validation): reconnect currently
     * reuses the standard SSH autoReconnect, which rebuilds the carrier but
     * still does a tmux attach. Making reconnect skip the redraw entirely and
     * let ET replay is the follow-up; requires etserver on the server.
     */
    private suspend fun connectEt(session: ClaudeSession, isNewTmuxSession: Boolean) {
        val effective = transportResolver.resolveTransport(session.server)
        FileLogger.log(TAG, "Connecting via Eternal Terminal to ${session.server.name} (${effective.host})")

        fun emit(text: String) {
            terminalIO.append(session.id, text)
            if (tabManager.activeTabId.value == session.id) onTerminalOutput?.invoke(session.id, text)
            notificationService.promptDetector.onOutput(session.id, text)
        }

        // Carrier SSH connection — used only for bootstrap + the local forward.
        // Its shell channel is unused; ET provides the terminal stream.
        // Only arm the carrier→reconnectEtCarrier callback AFTER bootstrap has
        // produced a live et client + forwarded port (carrierReady flips true
        // after putEt below). A drop DURING ensureEtServer/bootstrap must NOT
        // trigger the ET-native resume — there is no et client to resume yet, and
        // disconnecting the in-use carrier mid-setup would just race the in-flight
        // bootstrap. Such a drop instead surfaces as a thrown exception → the
        // catch below degrades to plain SSH.
        val carrierReady = java.util.concurrent.atomic.AtomicBoolean(false)
        val sshManager = SshManager(serverStorage, transportPool = connectionRegistry.transportPool(session.server.id))
        connectionRegistry.putSsh(session.id, sshManager)
        transportResolver.setConnectionLabel(session.id, effective, session.server, "ET")
        connectionRegistry.connectGate(session.server.id).withPermit {
            sshManager.connect(
                effective,
                onOutput = { },
                onConnectionLost = {
                    if (carrierReady.get()) {
                        // Carrier transport died (e.g. a Starlink IP change killed
                        // the CF WebSocket). The et CLIENT process is still alive and
                        // retrying its local port, so DON'T kill it — just rebuild the
                        // carrier + the forward on the SAME local port and et resumes
                        // its session on its own: no re-bootstrap, no tmux redraw.
                        tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
                        reconnectScope.launch { reconnectEtCarrier(session, ::emit) }
                    }
                },
                initialCols = terminalIO.effectiveSize(session.id).first,
                initialRows = terminalIO.effectiveSize(session.id).second,
            )
        }
        transportResolver.noteConnectResult(session.server, effective, ok = true)

        // ET-specific setup can fail on a server without etserver (or a binary
        // that won't exec). Fall back to a plain SSH session rather than
        // stranding the tab in ERROR — the same graceful degradation connectMosh
        // uses. forceSsh=true stops the dispatch recursing back into connectEt.
        try {
        val jsch = sshManager.getSession()
            ?: throw IllegalStateException("ET carrier SSH not connected")

        // 0) Ensure etserver is installed + running on the server (user-space,
        //    downloaded from the release on first use — no root, no manual
        //    setup). Returns the TCP port its client connections listen on.
        val etPort = ensureEtServer(jsch)
            ?: throw IllegalStateException("etserver unavailable on ${session.server.name}")
        // Cache for carrier rebuilds (reconnectEtCarrier re-forwards this port
        // without re-running the install script).
        etServerPorts[session.server.id] = etPort

        // 1) Bootstrap: the client generates id+passkey, pipes them to
        //    ~/.claude-remote/etterminal, which registers the session with the
        //    user etserver and echoes IDPASSKEY.
        val id = "XX" + randomAlphaNum(14)
        val passkey = randomAlphaNum(32)
        // `timeout 8`: etterminal is a short-lived IPC helper (exits <1s) but
        // BLOCKS forever on the fifo when the user etserver is wedged/absent, so
        // cap it — a killed bootstrap surfaces as a failed parse below (→ SSH
        // fallback), not an accumulating stuck process. id/passkey are
        // alphanumeric (randomAlphaNum) so they need no quoting inside sh -c.
        val bootstrap = execCapture(jsch, "timeout 8 sh -c 'echo $id/${passkey}_xterm-256color | ~/.claude-remote/etterminal --serverfifo ~/.claude-remote/et.sock --verbose=0 2>&1'")
        val marker = bootstrap.indexOf("IDPASSKEY:")
        if (marker < 0 || marker + 10 + 49 > bootstrap.length) {
            emit("\r\n[31mEternal Terminal bootstrap failed (is etserver installed on the server?).[0m\r\n")
            throw IllegalStateException("etserver bootstrap failed: ${bootstrap.take(200)}")
        }
        val idpasskey = bootstrap.substring(marker + 10, marker + 10 + 16 + 1 + 32)

        // 2) Forward a local port to etserver's port over the carrier tunnel.
        //    Remember the local port so a carrier rebuild can re-forward the
        //    SAME one and the still-running et client resumes without a bootstrap.
        val localPort = jsch.setPortForwardingL(0, "127.0.0.1", etPort)
        etLocalPorts[session.id] = localPort

        // 3) tmux attach as the ET shell's startup command (mirrors connectMosh).
        //    Prefix with `clear`: ET types this line into a login shell, which
        //    echoes it — over a slow link that echo lingers visibly until tmux
        //    attaches and repaints. `clear` runs the instant Enter is read
        //    (before tmux's network round-trip), wiping the echoed command so
        //    it isn't left on screen.
        val attachCmd = if (isNewTmuxSession) {
            ClaudeConfig.buildTmuxLaunchCommand(
                tmuxSessionName = session.tmuxSessionName,
                folder = session.folder,
                mode = session.mode,
                model = session.model
            )
        } else {
            // On a MISS do NOT `tmux new-session -A` (bare shell + tmux server
            // parented under this ephemeral ET-carrier login scope → dies on
            // teardown). Route the (re)create through buildTmuxLaunchCommand, which
            // anchors the server via `systemd-run --user --scope` and relaunches
            // claude with --resume, mirroring the SSH reconnect path. `{ …; }`
            // keeps the whole `;`-separated relaunch conditional on the attach miss.
            val relaunch = ClaudeConfig.buildTmuxLaunchCommand(
                tmuxSessionName = session.tmuxSessionName,
                folder = session.folder,
                mode = session.mode,
                model = session.model,
                claudeSessionId = session.claudeSessionId,
                resume = true
            )
            "${buildAttachCommand(session.tmuxSessionName)} 2>/dev/null || { $relaunch ; }"
        }
        val tmuxCmd = "clear 2>/dev/null; $attachCmd"

        val (cols, rows) = terminalIO.effectiveSize(session.id)
        val etManager = com.clauderemote.connection.EtManager()
        val ok = etManager.connect(
            idpasskey = idpasskey,
            host = "127.0.0.1",
            port = localPort,
            cols = cols,
            rows = rows,
            startupCommand = tmuxCmd,
            onOutput = { data -> emit(data) },
            onDisconnect = {
                // The et CLIENT PROCESS exited (not just a transport blip that
                // et would retry through) — a real failure. Tear it down and do
                // a full reconnect (fresh bootstrap + new et), but only if this
                // is still the live et for the session.
                if (connectionRegistry.et(session.id) === etManager) {
                    connectionRegistry.removeEt(session.id)
                    etLocalPorts.remove(session.id)
                    tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
                    reconnectScope.launch { autoReconnect(session, ::emit) }
                }
            }
        )
        if (!ok) {
            emit("\r\n[31mEternal Terminal client failed to start.[0m\r\n")
            throw IllegalStateException("EtManager.connect returned false")
        }
        connectionRegistry.putEt(session.id, etManager)
        // Bootstrap done: the et client + forwarded port now exist, so a carrier
        // drop from here on is safe to resume via reconnectEtCarrier.
        carrierReady.set(true)
        FileLogger.log(TAG, "Eternal Terminal connected for ${session.id} (localPort=$localPort)")
        } catch (e: Exception) {
            FileLogger.error(TAG, "Eternal Terminal path failed — falling back to SSH", e)
            connectionRegistry.removeEt(session.id)?.disconnect()
            connectionRegistry.ssh(session.id)?.disconnect()
            connectionRegistry.removeSsh(session.id)
            connectSsh(session, isNewTmuxSession, forceSsh = true)
        }
    }

    // Per-ET-session local forward port, so a carrier rebuild can re-forward the
    // SAME port (letting the live et client resume). Re-entrancy + mutual
    // exclusion with the SSH reconnect paths is provided by the shared
    // [reconnectingSessionIds] lock (see reconnectEtCarrier), not a separate set.
    private val etLocalPorts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Resolved etserver TCP port per SERVER id. etserver is one-per-server
    // (shared across that server's ET sessions) and is anchored, so it almost
    // always survives a carrier drop. Caching lets an ET carrier rebuild just
    // re-forward the port instead of re-running the full install/readiness
    // script on every attempt — the durable cure for the carrier reconnect
    // stall. A stale entry (server reboot) self-heals: the forward then points
    // at a dead port, et can't resume, its own onDisconnect fires a full
    // connectEt which re-runs ensureEtServer and refreshes this cache.
    private val etServerPorts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * ET-native reconnect: the carrier transport dropped but the et client
     * process is still alive and retrying its local port. Rebuild ONLY the
     * carrier SSH + the local forward on the SAME port — et then resumes its
     * session against etserver on its own (seamless replay, no bootstrap, no
     * tmux redraw). Falls back to a full [autoReconnect] if there's no port to
     * resume or the carrier can't be re-established.
     */
    private suspend fun reconnectEtCarrier(session: ClaudeSession, emit: (String) -> Unit) {
        // Share the SAME per-session reconnect lock as autoReconnect and
        // reconnectSession. Previously this path used its own `etReconnecting`
        // set, so a concurrent autoReconnect/reconnectSession could disconnect and
        // null the SshManager this path had just built — getSession() then
        // returned null mid-loop and we spun 6× re-racing. The shared lock makes
        // carrier-reconnect and the SSH reconnect paths mutually exclusive, and
        // also serves as this method's own re-entrancy guard (a carrier drop while
        // it runs is skipped, exactly like the old set did).
        synchronized(reconnectingSessionIds) {
            if (!reconnectingSessionIds.add(session.id)) return
        }
        // When we degrade to a full autoReconnect we must RELEASE the shared lock
        // first — autoReconnect re-acquires it, and would no-op if we still held it.
        var handedOff = false
        fun handOffToAutoReconnect() {
            _reconnectStatus.update { it - session.id }
            handedOff = true
            synchronized(reconnectingSessionIds) { reconnectingSessionIds.remove(session.id) }
            reconnectScope.launch { autoReconnect(session, emit) }
        }
        try {
            val localPort = etLocalPorts[session.id]
            if (localPort == null || connectionRegistry.et(session.id) == null) {
                handOffToAutoReconnect()
                return
            }
            val maxAttempts = 6
            for (attempt in 1..maxAttempts) {
                if (tabManager.getTab(session.id) == null) { _reconnectStatus.update { it - session.id }; return }
                val base = if (attempt == 1) 0L
                           else (2000L shl (attempt - 2).coerceAtMost(5)).coerceAtMost(30_000L)
                if (base > 0) kotlinx.coroutines.delay(base + kotlin.random.Random.nextLong(500))
                if (tabManager.getTab(session.id) == null) { _reconnectStatus.update { it - session.id }; return }
                _reconnectStatus.update { it + (session.id to ReconnectInfo(attempt, maxAttempts, nextRetryAtMillis = null)) }
                try {
                    connectionRegistry.ssh(session.id)?.disconnect()
                    connectionRegistry.removeSsh(session.id)
                    val sshManager = SshManager(serverStorage, transportPool = connectionRegistry.transportPool(session.server.id))
                    connectionRegistry.putSsh(session.id, sshManager)
                    val effective = transportResolver.resolveTransport(session.server)
                    transportResolver.setConnectionLabel(session.id, effective, session.server, "ET")
                    connectionRegistry.connectGate(session.server.id).withPermit {
                        sshManager.connect(
                            effective,
                            onOutput = { },
                            onConnectionLost = {
                                tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
                                reconnectScope.launch { reconnectEtCarrier(session, emit) }
                            },
                            initialCols = terminalIO.effectiveSize(session.id).first,
                            initialRows = terminalIO.effectiveSize(session.id).second,
                        )
                    }
                    transportResolver.noteConnectResult(session.server, effective, ok = true)
                    val jsch = sshManager.getSession()
                    if (jsch == null) {
                        // STRUCTURAL failure, not a transient one: the carrier SSH is
                        // already gone (e.g. torn down by another path). Retrying
                        // wouldn't re-race any more since we now hold the shared lock,
                        // but a null carrier means there's nothing to resume onto —
                        // degrade to a full reconnect immediately instead of spinning.
                        FileLogger.log(TAG, "ET carrier getSession null for ${session.id} — degrading to full reconnect")
                        handOffToAutoReconnect()
                        return
                    }
                    // Re-forward the SAME local port to etserver — the running et
                    // client is retrying that port and resumes when it's back.
                    // Prefer the cached etserver port: on a carrier drop the
                    // anchored etserver is almost always still up, so a bare
                    // re-forward (no install round-trip) is enough — re-running
                    // ensureEtServer every attempt was the ~90s stall under the old
                    // flock deadlock. Only fall back to the full install when there
                    // is no cached port (server reboot) or the re-forward fails.
                    val cachedPort = etServerPorts[session.server.id]
                    var forwarded = false
                    if (cachedPort != null) {
                        try {
                            jsch.setPortForwardingL(localPort, "127.0.0.1", cachedPort)
                            forwarded = true
                        } catch (e: Exception) {
                            FileLogger.log(TAG, "ET re-forward on cached port $cachedPort failed for ${session.id} — re-running install")
                        }
                    }
                    if (!forwarded) {
                        val etPort = ensureEtServer(jsch) ?: throw IllegalStateException("etserver unavailable on reconnect")
                        etServerPorts[session.server.id] = etPort
                        jsch.setPortForwardingL(localPort, "127.0.0.1", etPort)
                    }
                    tabManager.updateTabStatus(session.id, SessionStatus.ACTIVE)
                    statusService.updateActivity(session.id, SessionActivity.WAITING_FOR_INPUT)
                    onSessionActive?.invoke(session)
                    attachSessionRuntime(session.id, session.tmuxSessionName)
                    _reconnectStatus.update { it - session.id }
                    FileLogger.log(TAG, "ET carrier rebuilt for ${session.id} (localPort=$localPort) — et resuming, no redraw")
                    return
                } catch (e: Exception) {
                    FileLogger.error(TAG, "ET carrier reconnect attempt $attempt failed", e)
                }
            }
            // Carrier couldn't be re-established — fall back to a full reconnect.
            handOffToAutoReconnect()
        } finally {
            if (!handedOff) synchronized(reconnectingSessionIds) { reconnectingSessionIds.remove(session.id) }
        }
    }

    private fun randomAlphaNum(n: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return buildString { repeat(n) { append(chars[kotlin.random.Random.nextInt(chars.length)]) } }
    }

    /**
     * Run a command over a one-shot exec channel and return its stdout.
     * Bounded end-to-end (15s): only the channel CONNECT had a deadline before,
     * so a remote command that produced no EOF (a wedged etserver leaving
     * etterminal blocked on the fifo) parked the readText() forever and hung
     * connectEt. withTimeout caps the whole op; runInterruptible converts that
     * cancellation into a thread interrupt so the blocking read actually unblocks.
     */
    private suspend fun execCapture(jsch: com.jcraft.jsch.Session, command: String): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.withTimeout(15_000L) {
                val ch = jsch.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(command)
                ch.inputStream = null
                val input = ch.inputStream
                ch.connect(10000)
                try {
                    kotlinx.coroutines.runInterruptible { input.bufferedReader().readText() }
                } finally {
                    try { ch.disconnect() } catch (_: Exception) {}
                }
            }
        }

    // Server-side etserver auto-install. Downloads the static etserver +
    // etterminal (built from the same ET pin as the client, so protocol-
    // compatible) from the GitHub release into ~/.claude-remote and runs
    // etserver as a plain user process — no root, no package manager, no server
    // config. Idempotent via a version marker; reuses a running instance.
    // Prints "ET_READY port=<P>" on success, or an ET_* marker to fall back.
    private val ETSERVER_INSTALL = """
        CR="${'$'}HOME/.claude-remote"; mkdir -p "${'$'}CR"
        VER="claude-remote-etserver v7.0.0-1"
        case "${'$'}(uname -m)" in x86_64) A=x64 ;; *) echo "ET_UNSUPPORTED_ARCH:${'$'}(uname -m)"; exit 0 ;; esac
        if [ "${'$'}(cat "${'$'}CR/etserver.ver" 2>/dev/null)" != "${'$'}VER" ] || [ ! -x "${'$'}CR/etserver" ] || [ ! -x "${'$'}CR/etterminal" ]; then
          for b in etserver etterminal; do
            curl -fsSL --connect-timeout 10 --max-time 120 "https://github.com/lucashanak/claude-remote/releases/latest/download/${'$'}b-linux-${'$'}A" -o "${'$'}CR/${'$'}b.tmp" || { echo ET_DOWNLOAD_FAILED; exit 0; }
            chmod +x "${'$'}CR/${'$'}b.tmp" && mv "${'$'}CR/${'$'}b.tmp" "${'$'}CR/${'$'}b"
          done
          echo "${'$'}VER" > "${'$'}CR/etserver.ver"
        fi
        # Reap stray etterminals: a healthy bootstrap helper lives <1s, so any
        # older than 30s is one that wedged on the fifo (bad/absent etserver) —
        # kill it so they don't accumulate.
        for p in ${'$'}(pgrep -f "${'$'}CR/etterminal" 2>/dev/null); do
          AGE=${'$'}(ps -o etimes= -p "${'$'}p" 2>/dev/null | tr -d ' ')
          [ "${'$'}{AGE:-0}" -gt 30 ] 2>/dev/null && kill "${'$'}p" 2>/dev/null
        done
        # Reap flock waiters stuck on the etserver lock from the pre-fix deadlock:
        # the daemon used to INHERIT lock fd 9 and hold it for its whole life, so
        # every later run's `flock 9` blocked forever (15+ leaked bash/flock). A
        # healthy `flock -w 5` now exits in <5s, so any flock older than 30s is a
        # legacy deadlocked waiter — kill it (own-process only; kill fails silently
        # otherwise). Killing it unblocks its parent bash too.
        for p in ${'$'}(pgrep -x flock 2>/dev/null); do
          AGE=${'$'}(ps -o etimes= -p "${'$'}p" 2>/dev/null | tr -d ' ')
          [ "${'$'}{AGE:-0}" -gt 30 ] 2>/dev/null && kill "${'$'}p" 2>/dev/null
        done
        # Single-flight across concurrent SSH execs. Two sessions' first ET
        # connect used to each run this and each start an etserver on a bumped
        # port, both sharing one serverfifo → et.sock races away and every
        # bootstrap fails ("No such file or directory"). flock serialises the
        # check-and-start so exactly one instance is ever brought up. `-w 5`:
        # bounded wait — NEVER block forever (the acute deadlock symptom). On
        # timeout emit a marker and bail cleanly (→ SSH fallback), and because the
        # flock process exits at 5s it can't accumulate as a stuck waiter.
        exec 9>"${'$'}CR/etserver.lock"
        flock -w 5 9 || { echo ET_LOCK_TIMEOUT; exit 0; }
        NEED_START=1
        if [ -f "${'$'}CR/etserver.port" ] && pgrep -f "${'$'}CR/etserver" >/dev/null 2>&1; then
          PORT=${'$'}(cat "${'$'}CR/etserver.port")
          # Reuse ONLY if the port LISTENS *and* the serverfifo socket exists. A
          # process alive-but-not-accepting, OR one whose et.sock went missing
          # (the field failure), would otherwise be trusted forever and block
          # every bootstrap on the fifo — restart it instead.
          if ss -tln 2>/dev/null | grep -q ":${'$'}PORT " && [ -S "${'$'}CR/et.sock" ]; then
            NEED_START=0
          else
            pkill -f "${'$'}CR/etserver" 2>/dev/null; rm -f "${'$'}CR/et.sock"
          fi
        fi
        if [ "${'$'}NEED_START" = 1 ]; then
          # Kill any straggler etserver first so we can't end up with two.
          pkill -f "${'$'}CR/etserver" 2>/dev/null; rm -f "${'$'}CR/et.sock"
          PORT=2299
          while ss -tln 2>/dev/null | grep -q ":${'$'}PORT "; do PORT=${'$'}((PORT+1)); done
          # Start the daemon ANCHORED under the user systemd slice (like the tmux
          # server) so it survives THIS ephemeral SSH-exec scope's teardown, with
          # the lock fd CLOSED (9>&-) and stdin detached (</dev/null). The `9>&-`
          # is the core deadlock fix: the daemon must NOT inherit fd 9, or it holds
          # the flock for its whole life and every later ensureEtServer blocks on
          # `flock` → 15s timeout → ET always fell back to SSH and leaked stuck
          # bash/flock. Fall back to `setsid nohup` when systemd-run is absent.
          # Backgrounded (trailing &) so this script never blocks on the long-lived
          # daemon; systemd-run --scope stays alive alongside etserver, so the `||`
          # fallback only fires when systemd-run itself is unavailable.
          systemd-run --user --scope --quiet "${'$'}CR/etserver" --port "${'$'}PORT" --serverfifo "${'$'}CR/et.sock" </dev/null >"${'$'}CR/etserver.log" 2>&1 9>&- ||
            setsid nohup "${'$'}CR/etserver" --port "${'$'}PORT" --serverfifo "${'$'}CR/et.sock" </dev/null >"${'$'}CR/etserver.log" 2>&1 9>&- &
          echo "${'$'}PORT" > "${'$'}CR/etserver.port"
          # Wait for the serverfifo socket (up to ~6s) instead of a blind `sleep 1`
          # — the et.sock race caused "No such file or directory" bootstraps.
          for _ in ${'$'}(seq 1 30); do [ -S "${'$'}CR/et.sock" ] && break; sleep 0.2; done
        fi
        echo "ET_READY port=${'$'}PORT"
    """.trimIndent()

    /** Ensure etserver is installed + running on the server; return its port
     *  (null → unsupported arch / no internet / start failed → SSH fallback). */
    private suspend fun ensureEtServer(jsch: com.jcraft.jsch.Session): Int? {
        // Wrap the whole install in a server-side `timeout 12`: execCapture's
        // 15s client-side withTimeout + runInterruptible unblocks OUR read, but a
        // wedged remote run (e.g. a still-blocked flock) could otherwise leave a
        // forever-bash on the server. timeout 12 < the 15s client cap guarantees
        // the server-side process is reaped BEFORE we give up, so nothing leaks.
        // Single-quote-wrap for the outer login shell so the script reaches
        // `sh -c` verbatim (its own `$…` expand in the inner sh, not out here).
        val wrapped = "timeout 12 sh -c '" + ETSERVER_INSTALL.replace("'", "'\\''") + "'"
        val out = execCapture(jsch, wrapped)
        val port = Regex("ET_READY port=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        if (port == null) FileLogger.log(TAG, "etserver not ready: ${out.trim().takeLast(200)}")
        return port
    }

    fun clearBuffer(sessionId: String) = terminalIO.clearBuffer(sessionId)

    fun sendInput(sessionId: String, data: String) = claudeControl.sendInput(sessionId, data)

    fun sendBytes(sessionId: String, data: ByteArray) = claudeControl.sendBytes(sessionId, data)

    fun clearPendingInputs(sessionId: String) = claudeControl.clearPendingInputs(sessionId)

    fun resize(sessionId: String, cols: Int, rows: Int) = terminalIO.resize(sessionId, cols, rows)

    fun sendClaudeCommand(sessionId: String, command: String) = claudeControl.sendClaudeCommand(sessionId, command)

    fun switchModel(sessionId: String, model: ClaudeModel) = claudeControl.switchModel(sessionId, model)

    fun switchModelForAllSessions(model: ClaudeModel) = claudeControl.switchModelForAllSessions(model)

    fun switchEffort(sessionId: String, effort: ClaudeEffort) = claudeControl.switchEffort(sessionId, effort)

    fun switchEffortForAllSessions(effort: ClaudeEffort) = claudeControl.switchEffortForAllSessions(effort)

    fun submitLoginCode(sessionId: String, code: String) = claudeControl.submitLoginCode(sessionId, code)

    fun sendLoginCommand(sessionId: String) = claudeControl.sendLoginCommand(sessionId)

    fun sendEscape(sessionId: String) = claudeControl.sendEscape(sessionId)

    /**
     * Scroll the tmux pane via copy-mode (NOT via stdin) so the agent's input
     * is never disturbed. Enters copy-mode then sends one page-up / page-down.
     * Page-down at the bottom of history auto-exits copy-mode (back to live).
     * Runs off the UI thread on the IO scope.
     */
    fun tmuxScroll(sessionId: String, up: Boolean) = remoteOps.tmuxScroll(sessionId, up)

    /**
     * Upload a file to the remote server for the given session.
     * Returns the remote path of the uploaded file.
     *
     * If autoReconnect is already running (SAF picker killed the socket),
     * waits for it to finish.  Never kills the connection itself — that
     * was causing a cascade of 3 failed reconnects.
     */
    suspend fun uploadFile(sessionId: String, bytes: ByteArray, fileName: String): String =
        remoteOps.uploadFile(sessionId, bytes, fileName)

    /**
     * Reconnect a disconnected session. Reuses the same session config.
     */
    suspend fun reconnectSession(sessionId: String) {
        // Single-flight per session across EVERY entry point (manual button,
        // restoreAndReconnect's cold-start loop, the network-callback sweep,
        // AND autoReconnect) — shares the same guard autoReconnect uses. Cold
        // start fires restoreAndReconnect (onCreate) and reconnectDeadTabs
        // (onResume, moments later) concurrently; without this, two calls for
        // the SAME id raced on the same SshManager/registry entry — one call's
        // disconnect()/putSsh() stepping on the other's in-flight connect —
        // producing spurious "ET carrier SSH not connected" failures that only
        // a manual retry (landing after the race window) would clear.
        synchronized(reconnectingSessionIds) {
            if (!reconnectingSessionIds.add(sessionId)) {
                FileLogger.log(TAG, "Skipping reconnectSession for $sessionId — already reconnecting")
                return
            }
        }
        try {
            val tab = tabManager.getTab(sessionId) ?: return
            // Re-read the server config fresh from storage so a setting changed
            // in Settings (e.g. "Prefer Eternal Terminal") takes effect on a
            // normal reconnect instead of only after an app restart — a tab
            // otherwise holds the snapshot of the server captured when it was
            // created, so connectSsh's `session.server.preferEternal` dispatch
            // (and MOSH/ET choice) would use the stale value.
            val session = serverStorage.getServer(tab.server.id)?.let { tab.copy(server = it) } ?: tab
            FileLogger.log(TAG, "Reconnecting session $sessionId to ${session.server.name}")
            // A reconnected session has a running Claude that may already be idle
            // waiting for input — bypass the brand-new-session startup guard so
            // the first idle after restore can notify.
            notificationService.promptDetector.markInteracted(sessionId)

            // Invalidate the confirmed UUID so the next transcriptFlow() call fires
            // a fresh kick-probe. If claude restarted with a new session UUID during
            // the outage (e.g. user ran /clear or /resume), the probe will adopt the
            // new UUID. Without this, confirmedUuids retains the stale UUID forever
            // and the transcript stream keeps tailing a non-existent .jsonl file.
            transcriptService.clearConfirmedUuid(sessionId)

            // Clean up old connection
            connectionRegistry.ssh(sessionId)?.disconnect()
            connectionRegistry.removeSsh(sessionId)

            tabManager.updateTabStatus(sessionId, SessionStatus.CONNECTING)

            try {
                // Keep the session's chosen connection type. reconnectSession used
                // to hardcode SSH, so a MOSH session silently degraded to plain
                // SSH after its first drop and never got its roaming resilience
                // back — the exact scenario mosh exists for. connectMosh still
                // falls back to SSH internally when no direct-UDP path exists.
                if (session.connectionType == ConnectionType.MOSH) {
                    connectMosh(session, false)
                } else {
                    connectSsh(session, false, checkClosedElsewhere = true) // attach to existing tmux
                }
                tabManager.updateTabStatus(sessionId, SessionStatus.ACTIVE)
                // Clear the DISCONNECTED activity left over from restore/disconnect —
                // otherwise the session shows "Offline" (badge + status + empty
                // chips) even though it's connected. autoReconnect already does
                // this; reconnectSession (restore + manual) was missing it. The real
                // working/idle state is then driven by the statusline parse in emit()
                // as soon as output flows.
                statusService.updateActivity(sessionId, SessionActivity.WAITING_FOR_INPUT)
                onSessionActive?.invoke(session)
                // Restart ALL per-session loops (usage/git/latency pollers included
                // — they may have died during the outage), not just watcher+refresh.
                attachSessionRuntime(sessionId, session.tmuxSessionName)
                FileLogger.log(TAG, "Reconnected: $sessionId")
            } catch (e: SessionClosedElsewhereException) {
                FileLogger.log(TAG, "reconnectSession($sessionId) aborted — closed on another device")
            } catch (e: Exception) {
                FileLogger.error(TAG, "Reconnect failed", e)
                tabManager.updateTabStatus(sessionId, SessionStatus.ERROR)
                statusService.updateActivity(sessionId, SessionActivity.DISCONNECTED)
                // Re-arm: a failed manual/restore/network-callback reconnect must
                // not strand the tab in ERROR — keep retrying with capped backoff
                // until it connects or the user closes the tab.
                armReconnectRetry(sessionId)
            }
        } finally {
            synchronized(reconnectingSessionIds) { reconnectingSessionIds.remove(sessionId) }
        }
    }

    /**
     * Forget a session permanently (used when user explicitly closes a tab,
     * not just disconnects). Removes the persisted record so it won't be
     * resurrected on next app start, and re-syncs the server-side
     * sessions.json so systemd doesn't try to restore it after reboot.
     */
    suspend fun forgetSession(sessionId: String) = persistence.forgetSession(sessionId)

    suspend fun renameTmuxSession(sessionId: String, oldName: String, newName: String) =
        persistence.renameTmuxSession(sessionId, oldName, newName)

    /**
     * Rehydrate persisted sessions on app start. Returns the list of
     * ClaudeSessions that were restored into [tabManager] (status =
     * CONNECTING). Caller triggers reconnectSession() for each.
     */
    fun restorePersistedTabs(): List<ClaudeSession> = persistence.restorePersistedTabs()


    suspend fun disconnectSession(sessionId: String) {
        reconnectRetryJobs.remove(sessionId)?.cancel()
        _reconnectStatus.update { it - sessionId }
        gitStatusService.stopPolling(sessionId)
        persistence.stopIdRefresh(sessionId)
        // Cancel any debounced redraw + forget the pinned window size while the
        // tab (and with it the tmux name) is still around.
        tmuxProbes.dispose(sessionId, tabManager.getTab(sessionId)?.tmuxSessionName)
        // Per-SERVER loops (usage, latency, notify watcher) serve every session
        // on the server — tear them down only with the LAST one. The notify
        // registry entry for this session goes away either way.
        val serverId = tabManager.getTab(sessionId)?.server?.id
        if (serverId != null) {
            transcriptService.unregisterStreamWatch(serverId, sessionId)
            notificationService.unregisterNotifyWatcher(serverId, sessionId)
            val lastOnServer = tabManager.tabs.value.none {
                it.id != sessionId && it.server.id == serverId
            }
            if (lastOnServer) {
                usageService.stopPolling(serverId)
                serverHealthService.stopLatencyPolling(serverId)
            }
        }
        notificationService.clearNotifyDedup(sessionId)
        notificationService.setHookActive(sessionId, false)
        connectionRegistry.et(sessionId)?.disconnect()
        connectionRegistry.removeEt(sessionId)
        etLocalPorts.remove(sessionId)
        connectionRegistry.ssh(sessionId)?.disconnect()
        connectionRegistry.removeSsh(sessionId)
        connectionRegistry.mosh(sessionId)?.disconnect()
        connectionRegistry.removeMosh(sessionId)
        terminalIO.removeBuffer(sessionId)
        transcriptService.dispose(sessionId)
        statusService.stopPoller(sessionId)
        notificationService.promptDetector.removeSession(sessionId)
        notificationService.removePendingInputs(sessionId)
        terminalIO.clearSize(sessionId)
        transportResolver.clearConnectData(sessionId)
        transcriptService.clearConfirmedUuid(sessionId)
        statusService.clearActivity(sessionId)
        transportResolver.clearConnectionLabel(sessionId)
        transcriptService.clearContextPercent(sessionId)
        // Usage maps are keyed by server (account-wide), so don't clear them on
        // a single session's disconnect — other sessions on that server still
        // want the values.
        serverHealthService.clearSession(sessionId)
        gitStatusService.clearSession(sessionId)
        notificationService.clearPendingCount(sessionId)
        tabManager.removeTab(sessionId)
        // After removal, the active tab may have shifted to another session.
        // Fire onTabSwitched so the platform terminal clears the now-stale
        // SSH/bash content and replays the new active session's buffer —
        // otherwise the user keeps staring at the closed session's last frame.
        val newActive = tabManager.activeTabId.value
        if (newActive != null) {
            switchTab(newActive)
        }
    }

    suspend fun disconnectAll() {
        connectionRegistry.allSsh().forEach { it.disconnect() }
        connectionRegistry.clearSsh()
        terminalIO.clearAllBuffers()
    }

    fun getConnection(sessionId: String): SshManager? = connectionRegistry.ssh(sessionId)

    /**
     * Restart the Claude Code process for [sessionId] while KEEPING the
     * conversation. Runs `tmux respawn-pane -k` + `send-keys 'claude --resume
     * <uuid>'` over a one-shot exec on the live SSH session, so the tmux session
     * (and the app's attach to it) survives — only the claude process is
     * replaced, resuming the same transcript. Used to pick up a Claude Code
     * update without losing the session.
     */
    suspend fun restartClaude(sessionId: String) {
        val tab = tabManager.getTab(sessionId) ?: return
        val uuid = tab.claudeSessionId
        if (uuid.isNullOrBlank()) {
            FileLogger.log(TAG, "restartClaude: no claudeSessionId for $sessionId — cannot resume")
            return
        }
        val sshSession = connectionRegistry.ssh(sessionId)?.getSession()
        if (sshSession == null) {
            FileLogger.log(TAG, "restartClaude: no live connection for $sessionId")
            return
        }
        val cmd = ClaudeConfig.buildRestartCommand(tab.tmuxSessionName, tab.folder, tab.mode, tab.model, uuid)
        FileLogger.log(TAG, "Restarting Claude Code for $sessionId (resume $uuid) in tmux ${tab.tmuxSessionName}")
        // The respawn kills+redraws the pane; suppress the prompt detector so it
        // doesn't misfire on the transient screen. UUID is unchanged, so the
        // transcript stream keeps tailing the same file across the restart.
        notificationService.promptDetector.suppressFor(5000)
        try {
            execReadWithWatchdog(sshSession, cmd, totalMs = 15_000)
        } catch (e: Exception) {
            FileLogger.error(TAG, "restartClaude failed for $sessionId", e)
        }
    }

    /**
     * One-shot restore + background reconnect, scoped to the orchestrator's
     * own [reconnectScope] (SupervisorJob-backed) so callers don't need to
     * wire a CoroutineScope. Idempotent — safe to call from both Activity
     * onCreate and Window init without producing duplicate connect attempts.
     */
    fun restoreAndReconnect() {
        val restored = persistence.restorePersistedTabs()
        if (restored.isEmpty()) return
        // Concurrent, not sequential: a strictly sequential loop over N restored
        // tabs made the LAST tab wait N × (connect+attach) after a cold start —
        // with 20+ tabs that's tens of seconds, long enough that an impatient
        // user hit "Reconnect" on a tab whose turn just hadn't come up yet.
        // Safe to parallelize: each server's connectGate (Semaphore(3)) already
        // caps concurrent handshakes to that server, so this doesn't storm a
        // just-recovered link — it only removes the ARTIFICIAL serialization
        // across tabs/servers on top of that existing cap.
        reconnectScope.launch {
            restored.map { s -> async { try { reconnectSession(s.id) } catch (_: Exception) {} } }
                .forEach { it.join() }
        }
    }

    /**
     * Download a file from the remote server via SFTP.
     * Returns the file bytes, or null on failure.
     */
    suspend fun downloadFile(sessionId: String, remotePath: String): ByteArray? =
        remoteOps.downloadFile(sessionId, remotePath)

    /**
     * Which of [remotePaths] are regular files. Used to confirm the paths Claude
     * mentions in chat before rendering them as download links.
     */
    suspend fun statFiles(sessionId: String, remotePaths: List<String>): Set<String> =
        remoteOps.statFiles(sessionId, remotePaths)

    fun getBuffer(sessionId: String): String = terminalIO.getBuffer(sessionId)

    private fun generateId(): String {
        val bytes = Random.nextBytes(16)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generate a cryptographically random RFC 4122 v4 UUID. Used for
     * `claude --session-id` so the same value can later resume the
     * conversation via `--resume <uuid>`. Backed by SecureRandom (via
     * java.util.UUID) — a non-cryptographic PRNG would let a co-tenant
     * on the server guess upcoming session ids and tamper with their
     * transcripts under `~/.claude/projects/`.
     */
    private fun generateUuidV4(): String = java.util.UUID.randomUUID().toString()

    companion object {
        private const val TAG = "SessionOrchestrator"
        /** Sentinel returned by [downloadFile] when the remote file exceeds [DOWNLOAD_SIZE_LIMIT]. */
        val DOWNLOAD_TOO_LARGE: ByteArray = ByteArray(0)
        private const val DOWNLOAD_SIZE_LIMIT = 200L * 1024 * 1024 // 200 MB
    }
}
