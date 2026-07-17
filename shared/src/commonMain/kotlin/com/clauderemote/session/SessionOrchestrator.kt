package com.clauderemote.session

import com.clauderemote.connection.SshManager
import com.clauderemote.connection.SshSessionHelper
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import com.clauderemote.model.*
import com.clauderemote.session.transcript.TranscriptEntry
import com.clauderemote.session.service.ConnectionRegistry
import com.clauderemote.session.service.execReadWithWatchdog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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
    // active transport + protocol is glanceable (the choice is otherwise only in
    // the device log). Set on each (re)connect, cleared on disconnect.
    private val _connectionLabels = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    val connectionLabels: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = _connectionLabels

    /** Compute + publish the connection label from the resolved endpoint. */
    private fun setConnectionLabel(
        sessionId: String,
        effective: com.clauderemote.model.SshServer,
        original: com.clauderemote.model.SshServer,
        proto: String,
    ) {
        val transport = when {
            effective.useCloudflareProxy -> "Cloudflare"
            original.hasTailscale && effective.host == original.tailscaleHost -> "Tailscale"
            else -> "Direct"
        }
        _connectionLabels.update { it + (sessionId to "$transport · $proto") }
    }

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
        lastNetworkTeardownAt = System.currentTimeMillis()
        connectionRegistry.teardownAllTransports()
        // The AUTO decision was made on the old network — a Wi-Fi→LTE switch
        // changes Tailscale reachability, so re-probe on the next resolve.
        resolvedTransportCache.clear()
    }

    private var logShipper: com.clauderemote.util.LogShipper? = null

    /**
     * Start shipping FileLogger output to the server (one remote file per
     * install id, `~/.claude-remote/logs/<appId>.log`). Rides whatever live
     * pooled connection exists; buffers while offline. Called once from
     * platform init after AppSettings is available.
     */
    fun startLogShipping(appId: String) {
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
                val cmd = "PID=\$(tmux list-panes -t '$escaped' -F '#{pane_pid}' 2>/dev/null | head -1); " +
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
    fun kickRedraw(sessionId: String, cols: Int, rows: Int) {
        val conn = connectionRegistry.ssh(sessionId) ?: return
        if (cols <= 1 || rows <= 0) return
        FileLogger.log("TermGeom", "kickRedraw $sessionId requested ${cols}x${rows}")
        // Sync pty geometry to the current view first (no-op if unchanged) —
        // each session's pty keeps the size from the last time *it* was
        // active, which may be stale after switching between sessions.
        conn.resize(cols, rows)
        val tmuxName = tabManager.getTab(sessionId)?.tmuxSessionName
        reconnectScope.launch {
            if (tmuxName != null) probeTmuxGeometry(conn, tmuxName, sessionId, cols, rows)
            val refreshed = tmuxName != null && refreshTmuxClient(conn, tmuxName)
            if (!refreshed) {
                // Fallback: SIGWINCH toggle. Shrink COLS, not ROWS — row
                // shrink pushes the tmux status line up a cell during the
                // kick and its bytes leak into scrollback as a stray
                // status-line artifact mid-history.
                conn.resize(cols - 1, rows)
                kotlinx.coroutines.delay(80)
                conn.resize(cols, rows)
            }
        }
    }

    /**
     * Run `tmux refresh-client` for every client attached to [tmuxName] via a
     * short-lived exec channel. Returns true only if at least one client was
     * actually refreshed (the command echoes OK per successful refresh).
     */
    private suspend fun refreshTmuxClient(conn: SshManager, tmuxName: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val sshSession = conn.getSession() ?: return@withContext false
                val escaped = tmuxName.replace("'", "'\\''")
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(
                    "tmux list-clients -t '$escaped' -F '#{client_name}' 2>/dev/null" +
                        " | while IFS= read -r c; do tmux refresh-client -t \"\$c\" 2>/dev/null && echo OK; done"
                )
                ch.inputStream = null
                val input = ch.inputStream
                ch.connect(1500) // keep snappy on cell links; fallback toggle covers failure
                val out = input.bufferedReader().readText()
                ch.disconnect()
                out.contains("OK")
            } catch (e: Exception) {
                FileLogger.log(TAG, "refresh-client failed for $tmuxName: ${e.message}")
                false
            }
        }

    /**
     * Read-only diagnostic probe (TermGeom): logs tmux's actual pane geometry
     * and the window-size option for [tmuxName], so it can be compared against
     * the [cols]x[rows] the client requested in kickRedraw. Does not change any
     * tmux state — additional telemetry alongside the existing refresh path.
     */
    private suspend fun probeTmuxGeometry(
        conn: SshManager,
        tmuxName: String,
        sessionId: String,
        cols: Int,
        rows: Int,
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val sshSession = conn.getSession() ?: return@withContext
            val escaped = tmuxName.replace("'", "'\\''")
            val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
            ch.setCommand(
                "tmux display-message -p -t '$escaped' " +
                    "'#{pane_width}x#{pane_height} win=#{window_width}x#{window_height} ws=#{?window-size,#{window-size},?}' 2>/dev/null"
            )
            ch.inputStream = null
            val input = ch.inputStream
            ch.connect(1500)
            val out = input.bufferedReader().readText().trim()
            ch.disconnect()
            FileLogger.log("TermGeom", "kickRedraw $sessionId tmux pane=$out (requested ${cols}x${rows})")
        } catch (e: Exception) {
            FileLogger.log("TermGeom", "kickRedraw $sessionId tmux probe failed: ${e.message}")
        }
    }

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

    /**
     * Resolve a server's configured [ServerTransport] into the EFFECTIVE server
     * actually handed to the connection layer. AUTO prefers Tailscale when its
     * host is set and reachable (a quick TCP probe over the system VPN), else
     * falls back to the Cloudflare path — so a Starlink user with the Tailscale
     * VPN up gets the roaming-resilient path automatically, and anyone without
     * it still connects over CF. Resolved fresh on every (re)connect so the best
     * path is re-picked after a drop.
     */
    // AUTO transport: after a failed connect over Tailscale we prefer Cloudflare
    // for this long, so a flaky / unreachable tailnet path can't trap sessions in
    // a reconnect storm. It self-heals back to Tailscale once the cooldown lapses
    // and a connect succeeds. Keyed by server id.
    private val tailscaleCooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // 20 s, armed only after TWO consecutive failures. The old one-strike 60 s
    // ban meant a single slow KEX during a network blip downgraded the whole
    // server to Cloudflare for a minute — every minute, on a flaky link. Two
    // strikes tolerate the transient; 20 s still breaks probe/fail loops.
    private val TS_COOLDOWN_MS = 20_000L
    private val tailscaleFailStreak = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // AUTO resolution cached per server for a short TTL: a reconnect wave used
    // to run the TS probe independently per session, straddling the cooldown
    // window — half the tabs landed on 100.x, half on CF (split-brain, two
    // transport pools to one box, extra KEX churn). One decision per wave.
    private class ResolvedTransport(val at: Long, val eff: com.clauderemote.model.SshServer)
    private val resolvedTransportCache = java.util.concurrent.ConcurrentHashMap<String, ResolvedTransport>()
    private val resolveMutex = Mutex()
    private val RESOLVE_TTL_MS = 10_000L

    private fun isTailscaleEffective(
        server: com.clauderemote.model.SshServer,
        eff: com.clauderemote.model.SshServer,
    ): Boolean = server.hasTailscale && !eff.useCloudflareProxy && eff.host == server.tailscaleHost

    /** Record a connect outcome so AUTO can avoid a dead Tailscale path. */
    private fun noteConnectResult(
        server: com.clauderemote.model.SshServer,
        eff: com.clauderemote.model.SshServer,
        ok: Boolean,
    ) {
        if (!isTailscaleEffective(server, eff)) return
        // ok=true is NOT "healthy" — clearing the streak here let every
        // connect-then-instant-death cycle wipe the previous strike before
        // the death could be recorded, so the 2-strike cooldown could only
        // ever arm by the accident of two sessions dying in the same instant
        // (observed in the field: "strike 1/2" logged seven times in a row).
        // The streak is only cleared once a connection PROVES it survives
        // past the early-death window — see recordConnectSuccess's delayed
        // confirm. A connect failure still counts immediately.
        if (!ok) recordTailscaleFailure(server)
    }

    /** One TS strike; two consecutive strikes arm the cooldown. Fed by both
     *  CONNECT failures and post-connect EARLY DEATHS (see below). */
    private fun recordTailscaleFailure(server: com.clauderemote.model.SshServer) {
        // A TS failure means the cached AUTO decision is wrong — drop it so
        // the next resolve re-decides instead of re-serving TS from cache.
        resolvedTransportCache.remove(server.id)
        val streak = (tailscaleFailStreak[server.id] ?: 0) + 1
        tailscaleFailStreak[server.id] = streak
        if (streak >= 2) {
            tailscaleCooldownUntil[server.id] = System.currentTimeMillis() + TS_COOLDOWN_MS
            FileLogger.log(TAG, "Tailscale failed ${streak}× for ${server.name} — using Cloudflare for ${TS_COOLDOWN_MS / 1000}s")
        } else {
            FileLogger.log(TAG, "Tailscale failed for ${server.name} (strike $streak/2)")
        }
    }

    // A Tailscale transport that CONNECTS fine but dies seconds later (bad
    // tunnel path: DERP relay choking on the tmux-redraw burst, MTU blackhole
    // through a subnet router, …) used to loop forever: the successful connect
    // cleared the strike streak, the instant death never counted as a failure,
    // and AUTO re-picked TS every ~2 s. Deaths within this window of a connect
    // now count as TS strikes so the existing 2-strike cooldown breaks the
    // loop and falls back to Cloudflare.
    private val TS_EARLY_DEATH_MS = 30_000L
    private val lastConnectAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastConnectTsEffective = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var lastNetworkTeardownAt = 0L

    /** Record a successful connect for early-death attribution. */
    private fun recordConnectSuccess(sessionId: String, server: com.clauderemote.model.SshServer, eff: com.clauderemote.model.SshServer) {
        val connectEpoch = System.currentTimeMillis()
        lastConnectAt[sessionId] = connectEpoch
        val tsEffective = isTailscaleEffective(server, eff)
        lastConnectTsEffective[sessionId] = tsEffective
        if (tsEffective) {
            // Clear the TS strike streak only once THIS connection proves it
            // survives past the early-death window — not at connect time (see
            // noteConnectResult). Superseded by a newer connect (lastConnectAt
            // moved on) or already dead (isConnected false) ⇒ no-op; the death
            // was already counted by maybeCountTsEarlyDeath.
            reconnectScope.launch {
                kotlinx.coroutines.delay(TS_EARLY_DEATH_MS)
                if (lastConnectAt[sessionId] == connectEpoch && connectionRegistry.ssh(sessionId)?.isConnected == true) {
                    tailscaleCooldownUntil.remove(server.id)
                    tailscaleFailStreak.remove(server.id)
                }
            }
        }
        // Fire-and-forget RTT sample the instant a TS connect succeeds, BEFORE
        // the tmux-attach burst that's been killing the transport — the
        // regular 15 s latency loop never gets a turn when a session is dying
        // every ~2 s. A round-trip of tens-hundreds of ms points at a DERP
        // relay hop (e.g. double-CGNAT hole-punch failure); low single-digit
        // ms points at a direct LAN path (rule out relay, look at MTU/offload
        // on the gateway instead). Bounded 3 s so a hung probe can't delay the
        // real tmux attach that follows.
        if (tsEffective) {
            reconnectScope.launch {
                try {
                    val sshSession = connectionRegistry.ssh(sessionId)?.getSession() ?: return@launch
                    val start = System.currentTimeMillis()
                    execReadWithWatchdog(sshSession, "echo pong", totalMs = 3_000)
                    FileLogger.log(TAG, "TS pre-attach RTT for $sessionId: ${System.currentTimeMillis() - start}ms")
                } catch (e: Exception) {
                    FileLogger.log(TAG, "TS pre-attach RTT probe failed for $sessionId: ${e.message}")
                }
            }
        }
    }

    /** From onConnectionLost: count a fresh-connection death on the TS path
     *  as a TS strike — unless WE tore the transport down (network change). */
    private fun maybeCountTsEarlyDeath(session: ClaudeSession) {
        val at = lastConnectAt[session.id] ?: return
        if (lastConnectTsEffective[session.id] != true) return
        val now = System.currentTimeMillis()
        if (now - at > TS_EARLY_DEATH_MS) return
        if (now - lastNetworkTeardownAt < 5_000) return
        FileLogger.log(TAG, "Tailscale transport died ${now - at}ms after connect for ${session.id}")
        recordTailscaleFailure(session.server)
    }

    private suspend fun resolveTransport(server: com.clauderemote.model.SshServer): com.clauderemote.model.SshServer {
        // Fixed CLOUDFLARE is deterministic — no probe, no cooldown to check.
        if (server.transport == com.clauderemote.model.ServerTransport.CLOUDFLARE) {
            return server.forTransport(server.transport)
        }
        // Fixed TAILSCALE still respects the cooldown. Pinning the transport
        // is "prefer Tailscale", not "Tailscale no matter what" — without this
        // a bad tunnel path (DERP relay dying on bursty traffic, e.g.) looped
        // the app on zero-backoff reconnects forever, because only AUTO's
        // branch ever consulted tailscaleCooldownUntil. Same safety net AUTO
        // gets: fall back to whatever the base server config represents
        // (normally Cloudflare) for the cooldown window, then retry TS.
        if (server.transport == com.clauderemote.model.ServerTransport.TAILSCALE) {
            val cooling = System.currentTimeMillis() < (tailscaleCooldownUntil[server.id] ?: 0L)
            if (cooling) {
                FileLogger.log(TAG, "Tailscale (pinned) cooling down for ${server.name} — using Cloudflare")
                return server.forTransport(com.clauderemote.model.ServerTransport.CLOUDFLARE)
            }
            return server.forTransport(com.clauderemote.model.ServerTransport.TAILSCALE)
        }
        // AUTO: one probe + one decision per server per RESOLVE_TTL_MS window,
        // shared by every session reconnecting in the same wave. The mutex
        // collapses concurrent resolvers onto a single probe.
        resolvedTransportCache[server.id]?.let {
            if (System.currentTimeMillis() - it.at < RESOLVE_TTL_MS) return it.eff
        }
        return resolveMutex.withLock {
            resolvedTransportCache[server.id]?.let {
                if (System.currentTimeMillis() - it.at < RESOLVE_TTL_MS) return@withLock it.eff
            }
            // Skip Tailscale entirely while cooling down from recent failures
            // — no probe, straight to Cloudflare.
            val cooling = System.currentTimeMillis() < (tailscaleCooldownUntil[server.id] ?: 0L)
            val chosen = if (!cooling && server.hasTailscale && tailscaleReachable(server))
                com.clauderemote.model.ServerTransport.TAILSCALE
            else com.clauderemote.model.ServerTransport.CLOUDFLARE
            val eff = server.forTransport(chosen)
            if (eff.host != server.host || eff.useCloudflareProxy != server.useCloudflareProxy) {
                FileLogger.log(TAG, "Transport for ${server.name}: $chosen -> ${eff.host} (cf=${eff.useCloudflareProxy})")
            }
            resolvedTransportCache[server.id] = ResolvedTransport(System.currentTimeMillis(), eff)
            eff
        }
    }

    /**
     * Reachability probe of the Tailscale endpoint (system VPN route),
     * validated by READING THE SSH BANNER — a bare TCP SYN/ACK used to pass
     * even when sshd/KEX would stall, flapping AUTO between TS and CF.
     * Two attempts with a settle pause: on HyperOS the WireGuard tunnel is
     * routinely still re-handshaking at onResume, and the old single 2 s
     * probe fired exactly then — AUTO practically never picked Tailscale on
     * a phone that had just woken up.
     */
    private suspend fun tailscaleReachable(server: com.clauderemote.model.SshServer): Boolean =
        withContext(Dispatchers.IO) {
            repeat(2) { attempt ->
                try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(server.tailscaleHost, server.port), 3_500)
                        s.soTimeout = 3_000
                        // sshd sends "SSH-2.0-…" immediately; any byte proves a
                        // live, responsive daemon behind the route.
                        if (s.getInputStream().read() > 0) return@withContext true
                    }
                } catch (_: Exception) {}
                if (attempt == 0) kotlinx.coroutines.delay(1_500) // let WG finish its handshake
            }
            false
        }

    private suspend fun connectSsh(
        session: ClaudeSession,
        isNewTmuxSession: Boolean,
        checkClosedElsewhere: Boolean = false,
    ) {
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

        val sshEffective = resolveTransport(session.server)
        setConnectionLabel(session.id, sshEffective, session.server, "SSH")
        try {
            // Gate the handshake per server — see connectGates.
            connectionRegistry.connectGate(session.server.id).withPermit {
                sshManager.connect(
                    sshEffective,
                    onOutput = { data -> emit(data) },
                    onConnectionLost = {
                        maybeCountTsEarlyDeath(session)
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
            noteConnectResult(session.server, sshEffective, ok = false)
            throw e
        }
        noteConnectResult(session.server, sshEffective, ok = true)
        recordConnectSuccess(session.id, session.server, sshEffective)

        // Wait for shell prompt (detect $ or # or >, max 3s)
        waitForShellPrompt(session.id, 3000)

        // Startup command
        if (session.server.startupCommand.isNotBlank()) {
            sshManager.sendInput(session.server.startupCommand + "\n")
            waitForShellPrompt(session.id, 3000)
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
            val tmuxExists = probeTmuxSession(sshManager, session.tmuxSessionName)
            if (!tmuxExists && checkClosedElsewhere && !persistence.stillTrackedOnServer(sshManager, session.tmuxSessionName)) {
                // Another device's forgetSession() already pushed this tmux name
                // out of the shared sessions.json — respect that instead of
                // resurrecting a session the user consciously closed elsewhere.
                // Only trusted for the reconnect-to-an-already-tracked-tab path
                // (checkClosedElsewhere=true); launchSession's attach/history-resume
                // callers pass false since their target may legitimately be new
                // to sessions.json.
                FileLogger.log(TAG, "Tmux '${session.tmuxSessionName}' missing and no longer tracked server-side — closed on another device, forgetting locally")
                sessionStorage?.remove(session.id)
                disconnectSession(session.id)
                throw SessionClosedElsewhereException()
            }
            val escaped = session.tmuxSessionName.replace("'", "'\\''")
            val command = if (tmuxExists) {
                "tmux set-option -g window-size latest 2>/dev/null; tmux set-option -g history-limit 100000 2>/dev/null; tmux attach-session -t '$escaped'"
            } else if (session.claudeSessionId != null) {
                // Resume only works if claude actually wrote a transcript file
                // for this UUID. The transcript appears lazily — first user/
                // assistant turn — so a session that was launched but never
                // interacted with has no jsonl, and `--resume` would print
                // "No conversation found". In that case we re-launch fresh
                // with the same `--session-id` so future restarts can resume.
                val hasTranscript = probeTranscriptExists(sshManager, session.folder, session.claudeSessionId)
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
     * Synchronous tmux session existence probe via SSH exec channel.
     * Returns true if `tmux has-session -t <name>` exits 0. Returns true on
     * exec failure too (fail-open: fall through to attach which will create
     * via -A if needed — old behavior).
     */
    private fun probeTmuxSession(sshManager: SshManager, sessionName: String): Boolean {
        return try {
            val sshSession = sshManager.getSession() ?: return true
            val escaped = sessionName.replace("'", "'\\''")
            val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
            ch.setCommand("tmux has-session -t '$escaped' 2>/dev/null && echo YES || echo NO")
            ch.inputStream = null
            val input = ch.inputStream
            ch.connect(1500) // fail-open probe — keep snappy on cell links
            val out = input.bufferedReader().readText().trim()
            ch.disconnect()
            out.endsWith("YES")
        } catch (e: Exception) {
            FileLogger.error(TAG, "Tmux probe failed for $sessionName: ${e.message}", e)
            true // fail-open
        }
    }

    /**
     * Probe whether a Claude Code transcript file exists for the given UUID
     * in the encoded form of [folder]. Used to decide whether `--resume <uuid>`
     * will succeed or whether we need to launch fresh with `--session-id <uuid>`.
     *
     * Encoding: `~` is expanded to `$HOME`, relative folders are anchored at
     * `$HOME`, then every `/` becomes `-` (matches Claude Code's on-disk layout
     * under `~/.claude/projects/`).
     *
     * Fail-closed (returns false on probe error) so we don't try a `--resume`
     * that we can't verify — it's safer to start fresh than to crash with
     * "No conversation found".
     */
    private fun probeTranscriptExists(sshManager: SshManager, folder: String, uuid: String): Boolean {
        return try {
            val sshSession = sshManager.getSession() ?: return false
            val escapedFolder = folder.replace("'", "'\\''")
            val cmd = """
                F='$escapedFolder'
                E="${'$'}{F/#~/${'$'}HOME}"
                case "${'$'}E" in /*) ;; *) E="${'$'}HOME/${'$'}E";; esac
                # UUID is globally unique — a transcript matching it anywhere means
                # it exists, immune to the lossy cwd->dir encoding. Fall back to the
                # corrected encoding (every non-alphanumeric -> '-') for a not-yet-
                # globbable path.
                ENC=${'$'}(echo "${'$'}E" | sed 's|[^a-zA-Z0-9]|-|g')
                if ls "${'$'}HOME/.claude/projects/"*/"$uuid.jsonl" >/dev/null 2>&1 || [ -f "${'$'}HOME/.claude/projects/${'$'}ENC/$uuid.jsonl" ]; then echo YES; else echo NO; fi
            """.trimIndent()
            val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
            ch.setCommand(cmd)
            ch.inputStream = null
            val input = ch.inputStream
            ch.connect(1500)
            val out = input.bufferedReader().readText().trim()
            ch.disconnect()
            out.endsWith("YES")
        } catch (e: Exception) {
            FileLogger.error(TAG, "Transcript probe failed for $uuid in $folder: ${e.message}", e)
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
                val jitter = kotlin.random.Random.nextLong(500)
                if (base + jitter > 0) kotlinx.coroutines.delay(base + jitter)
                // Tab closed during the backoff — stop reconnecting it.
                if (tabManager.getTab(session.id) == null) { _reconnectStatus.update { it - session.id }; return }

                try {
                    // Clean up old connection
                    connectionRegistry.ssh(session.id)?.disconnect()
                    connectionRegistry.removeSsh(session.id)

                    val sshManager = SshManager(serverStorage, transportPool = connectionRegistry.transportPool(session.server.id))
                    connectionRegistry.putSsh(session.id, sshManager)

                    val reEffective = resolveTransport(session.server)
                    setConnectionLabel(session.id, reEffective, session.server, "SSH")
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
                                    maybeCountTsEarlyDeath(session)
                                    tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
                                    reconnectScope.launch { autoReconnect(session, emit) }
                                },
                                initialCols = terminalIO.effectiveSize(session.id).first,
                                initialRows = terminalIO.effectiveSize(session.id).second,
                            )
                        }
                    } catch (e: Exception) {
                        noteConnectResult(session.server, reEffective, ok = false)
                        throw e
                    }
                    noteConnectResult(session.server, reEffective, ok = true)
                    recordConnectSuccess(session.id, session.server, reEffective)

                    // Wait for shell prompt, clear garbage, then attach tmux
                    waitForShellPrompt(session.id, 3000)
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
        val effectiveServer = resolveTransport(session.server)
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
            val escaped = session.tmuxSessionName.replace("'", "'\\''")
            "tmux set-option -g window-size latest 2>/dev/null; tmux set-option -g history-limit 100000 2>/dev/null; tmux attach-session -t '$escaped' 2>/dev/null || tmux new-session -A -s '$escaped' \\; set-option -g mouse on \\; set-option -g history-limit 100000"
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
        setConnectionLabel(session.id, effectiveServer, session.server, "Mosh")
        FileLogger.log(TAG, "Mosh connected for ${session.id}")
    }

    /**
     * Wait for shell prompt by watching output buffer for prompt chars ($ # > %).
     * Returns as soon as prompt detected or after maxWait ms.
     */
    private suspend fun waitForShellPrompt(sessionId: String, maxWait: Long) {
        val start = System.currentTimeMillis()
        val promptChars = setOf('$', '#', '>', '%')
        while (System.currentTimeMillis() - start < maxWait) {
            val lastLine = terminalIO.lastLine(sessionId)
            if (lastLine.isNotEmpty() && lastLine.any { it in promptChars }) return
            kotlinx.coroutines.delay(50)
        }
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
        synchronized(reconnectingSessionIds) {
            if (sessionId in reconnectingSessionIds) {
                FileLogger.log(TAG, "Skipping reconnectSession for $sessionId — autoReconnect already running")
                return
            }
        }
        val session = tabManager.getTab(sessionId) ?: return
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
        lastConnectAt.remove(sessionId)
        lastConnectTsEffective.remove(sessionId)
        transcriptService.clearConfirmedUuid(sessionId)
        statusService.clearActivity(sessionId)
        _connectionLabels.update { it - sessionId }
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
        val cmd = ClaudeConfig.buildRestartCommand(tab.tmuxSessionName, tab.mode, tab.model, uuid)
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
        reconnectScope.launch {
            for (s in restored) {
                try { reconnectSession(s.id) } catch (_: Exception) {}
            }
        }
    }

    /**
     * Download a file from the remote server via SFTP.
     * Returns the file bytes, or null on failure.
     */
    suspend fun downloadFile(sessionId: String, remotePath: String): ByteArray? =
        remoteOps.downloadFile(sessionId, remotePath)

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
        private const val DOWNLOAD_SIZE_LIMIT = 50L * 1024 * 1024 // 50 MB
    }
}
