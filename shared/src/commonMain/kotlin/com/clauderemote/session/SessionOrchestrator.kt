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
import com.clauderemote.storage.PersistedSession
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

    // sessions.json is one file per SERVER; with N sessions each running the
    // 15 s reconcile loop, N−1 of the flock+cat fetches were redundant. Cache
    // the parsed snapshot per server with a TTL just under the loop period so
    // only the first session to tick pays the exec.
    private class SessionsSnapshot(val at: Long, val list: List<PersistedSession>)
    private val sessionsJsonCache = java.util.concurrent.ConcurrentHashMap<String, SessionsSnapshot>()
    private val sessionsJsonMutex = Mutex()

    // Per-session pollers that read ~/.claude/sessions/<pid>.json on the
    // server to capture the *real* claude session_id — which can drift from
    // the UUID we passed via --session-id when the user invokes /resume,
    // /clear, /compact etc. Without this we'd push a stale UUID to
    // sessions.json and the next reboot's restore.sh would --resume the
    // wrong (or non-existent) conversation.
    private val sessionIdRefreshJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

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
            startSessionIdRefresh(sessionId, tmuxSessionName, conn)
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
     * Periodically read the server-side `~/.claude/sessions/<pid>.json` for
     * this session's tmux pane and update [tabManager] + [sessionStorage]
     * whenever claude's internal session_id differs from what we have.
     *
     * Triggers a server-side `sessions.json` push only when the UUID actually
     * changes, so the systemd restore service always has the latest real id.
     *
     * 3s warm-up gives claude time to write its first state file; then we
     * poll every 60s. Cancelled in [disconnectSession].
     */
    /**
     * Pull the authoritative `~/.claude-remote/sessions.json` from the
     * server and reconcile this tab's claudeSessionId with whatever the
     * server-side drift daemon has recorded. Replaces the older per-pid
     * probe — the server now owns the truth, the client just mirrors it.
     */
    private fun startSessionIdRefresh(sessionId: String, tmuxName: String, sshManager: SshManager) {
        if (sessionStorage == null) return
        sessionIdRefreshJobs[sessionId]?.cancel()
        sessionIdRefreshJobs[sessionId] = reconnectScope.launch {
            kotlinx.coroutines.delay(1000)
            while (isActive) {
                // Skip in background: this is 2 SSH execs every 15s per session
                // (~104 radio wakeups/min at 13 sessions) and only matters for
                // UUID/restore correctness, which onResume refreshes anyway.
                if (isInBackground) { kotlinx.coroutines.delay(15_000); continue }
                try {
                    val remote = fetchSessionsCached(connectionRegistry.serverIdOf(sessionId), sshManager)
                    val entry = remote?.firstOrNull { it.tmuxSessionName == tmuxName }
                    // Prefer the LIVE pane's running Claude pid over the server's
                    // sessions.json record. The server entry is seeded with the
                    // client-generated launch UUID and the drift daemon may not
                    // have caught a rotation (or recorded the wrong pid) — a
                    // stale-but-present server value would mask the real UUID and
                    // actively fight the transcript kick-probe, reverting the
                    // corrected UUID every 15 s. The pid-probe reads what Claude
                    // is writing right now, so it's ground truth for a connected
                    // tab; the server entry is only a fallback when the pane
                    // can't be resolved.
                    val realUuid = readRealSessionId(sshManager, tmuxName)
                        ?: entry?.claudeSessionId.takeUnless { it.isNullOrBlank() }
                    val tab = tabManager.getTab(sessionId)
                    if (tab != null && !realUuid.isNullOrBlank() && tab.claudeSessionId != realUuid) {
                        // Refuse to adopt a UUID already owned by another
                        // tab — two transcript streams pointing at the
                        // same jsonl would mirror identical content
                        // across both sessions, which is exactly the
                        // "občas se chat historie zobrazuje stejně" bug.
                        // Happens when the user picks the same /resume
                        // conversation in two tabs, or when a tmux name
                        // collision lets the drift daemon attribute one
                        // claude pid to two tab rows.
                        val claimedByOther = tabManager.tabs.value.any {
                            it.id != sessionId && it.claudeSessionId == realUuid
                        }
                        if (claimedByOther) {
                            FileLogger.log(TAG, "Skip UUID drift $realUuid for $sessionId — already owned by another tab")
                        } else {
                            val source = if (entry?.claudeSessionId == realUuid) "server" else "pid-probe"
                            FileLogger.log(TAG, "Session $sessionId UUID synced from $source: ${tab.claudeSessionId} -> $realUuid")
                            tabManager.updateClaudeSessionId(sessionId, realUuid)
                            sessionStorage.upsert(SessionStorage.fromClaudeSession(tab.copy(claudeSessionId = realUuid)))
                            transcriptService.notifyClaudeSessionIdChanged(sessionId, realUuid)
                            transcriptService.setConfirmedUuid(sessionId, realUuid)
                        }
                    }
                } catch (_: Exception) {}
                kotlinx.coroutines.delay(15_000)
            }
        }
    }

    /**
     * Read the server's authoritative sessions.json under a shared file
     * lock (so we never read mid-write from the drift daemon or restore.sh).
     * Returns null on transport failure, empty list on missing file or
     * parse error.
     */
    private suspend fun fetchSessionsFromServer(sshManager: SshManager): List<PersistedSession>? {
        return withContext(Dispatchers.IO) {
            try {
                val sshSession = sshManager.getSession() ?: return@withContext null
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(
                    "touch \"\$HOME/.claude-remote/sessions.lock\"; " +
                    "flock -s \"\$HOME/.claude-remote/sessions.lock\" " +
                    "cat \"\$HOME/.claude-remote/sessions.json\" 2>/dev/null"
                )
                ch.inputStream = null
                val input = ch.inputStream
                val out = try {
                    ch.connect(3000)
                    input.bufferedReader().readText().trim()
                } finally {
                    try { ch.disconnect() } catch (_: Exception) {}
                }
                if (out.isEmpty()) emptyList()
                else fetchJson.decodeFromString<List<PersistedSession>>(out)
            } catch (e: Exception) {
                FileLogger.error(TAG, "fetchSessionsFromServer failed: ${e.message}", e)
                null
            }
        }
    }

    private val fetchJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * True unless the server's authoritative sessions.json was fetched
     * successfully AND no longer lists this tmux name — i.e. some device's
     * forgetSession() removed it. Fail-open (true) on any fetch problem so a
     * network blip can't be mistaken for "closed elsewhere" and wrongly drop
     * a tab that's still legitimately tracked.
     */
    private suspend fun stillTrackedOnServer(sshManager: SshManager, tmuxSessionName: String): Boolean {
        val remote = fetchSessionsFromServer(sshManager) ?: return true
        return remote.any { it.tmuxSessionName == tmuxSessionName }
    }

    /**
     * Thrown by [sendTmuxCommand] when a tab's tmux is missing AND another
     * device already closed it server-side. [sendTmuxCommand] has already
     * torn the tab down locally by the time this is thrown — callers should
     * stop reconnecting/retrying, not treat it as a connection failure.
     */
    private class SessionClosedElsewhereException : Exception()

    /**
     * [fetchSessionsFromServer] with a per-server TTL cache. All sessions on a
     * server reconcile against the SAME sessions.json every 15 s — only the
     * first one inside the TTL pays the exec, the rest reuse the snapshot.
     * Transport failures (null) are never cached, so a fresh session retries.
     */
    private suspend fun fetchSessionsCached(
        serverId: String?,
        sshManager: SshManager,
    ): List<PersistedSession>? {
        if (serverId == null) return fetchSessionsFromServer(sshManager)
        val ttl = 12_000L // just under the 15 s loop period
        sessionsJsonCache[serverId]?.let {
            if (System.currentTimeMillis() - it.at < ttl) return it.list
        }
        return sessionsJsonMutex.withLock {
            // Re-check under the lock — another session's tick may have just
            // refreshed it while we waited.
            sessionsJsonCache[serverId]?.let {
                if (System.currentTimeMillis() - it.at < ttl) return@withLock it.list
            }
            val fresh = fetchSessionsFromServer(sshManager)
            if (fresh != null) {
                sessionsJsonCache[serverId] = SessionsSnapshot(System.currentTimeMillis(), fresh)
            }
            fresh
        }
    }

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
                    ensureRestoreService(conn)
                    pushSessionsToServer(conn, server.id)
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
            if (!tmuxExists && checkClosedElsewhere && !stillTrackedOnServer(sshManager, session.tmuxSessionName)) {
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
    suspend fun forgetSession(sessionId: String) {
        val session = tabManager.getTab(sessionId)
        // Capture identity up front so we can prune the UI's stale remote-tmux
        // snapshot even after the tab is torn down below.
        val forgottenServerId = session?.server?.id
        val forgottenTmuxName = session?.tmuxSessionName

        // 1. IMMEDIATE local teardown. The server-side cleanup below can take
        //    tens of seconds on a slow network (cleanup connect timeout + tmux
        //    kill + sessions.json push). The old order ran cleanup FIRST and
        //    removed the tab in a finally — so a "closed" tab stayed visible
        //    (and switchable!) until cleanup finished, then vanished from under
        //    the user mid-use.
        sessionStorage?.remove(sessionId)
        disconnectSession(sessionId)
        // Prune the UI's stale remote-tmux snapshot so the killed pane
        // doesn't reappear as a "detached remote" row.
        if (forgottenServerId != null && !forgottenTmuxName.isNullOrBlank()) {
            onSessionForgotten?.invoke(forgottenServerId, forgottenTmuxName)
        }

        // 2. Server-side cleanup in the BACKGROUND, best-effort: kill the tmux
        //    pane and re-sync sessions.json so the systemd restore service
        //    doesn't re-materialise the "closed" session after a reboot. Opens
        //    its own short-lived connection — the tab's live connection was
        //    already torn down above, and reusing it would keep its read loop
        //    (and onConnectionLost → autoReconnect) alive for a dead tab.
        if (session != null) {
            reconnectScope.launch {
                val cleanupConn: SshManager? = try {
                    val tmp = SshManager(serverStorage)
                    tmp.connectForCleanup(session.server)
                    tmp
                } catch (e: Exception) {
                    FileLogger.log(TAG, "Cleanup SSH connect failed for $sessionId (${e.message}) — server-side cleanup skipped")
                    null
                }
                try {
                    if (cleanupConn != null) {
                        try {
                            val killed = com.clauderemote.connection.TmuxManager.killSession(
                                cleanupConn.getSession() ?: error("no ssh"),
                                session.tmuxSessionName
                            )
                            if (!killed) {
                                FileLogger.error(TAG, "Tmux kill returned failure for $sessionId (${session.tmuxSessionName}) — pane may still be alive", null)
                            }
                        } catch (e: Exception) {
                            FileLogger.error(TAG, "Tmux kill failed for $sessionId: ${e.message}", e)
                        }
                        try {
                            pushSessionsToServer(cleanupConn, session.server.id)
                        } catch (e: Exception) {
                            FileLogger.error(TAG, "sessions.json push failed for ${session.server.id}: ${e.message}", e)
                        }
                    }
                } finally {
                    try { cleanupConn?.disconnect() } catch (_: Exception) {}
                }
            }
        }
    }

    suspend fun disconnectSession(sessionId: String) {
        reconnectRetryJobs.remove(sessionId)?.cancel()
        _reconnectStatus.update { it - sessionId }
        gitStatusService.stopPolling(sessionId)
        sessionIdRefreshJobs.remove(sessionId)?.cancel()
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

    suspend fun renameTmuxSession(sessionId: String, oldName: String, newName: String) {
        withContext(Dispatchers.IO) {
            try {
                val sshSession = connectionRegistry.ssh(sessionId)?.getSession() ?: return@withContext
                com.clauderemote.connection.TmuxManager.renameSession(sshSession, oldName, newName)
                FileLogger.log(TAG, "Tmux renamed: $oldName → $newName")
                // Persist the new tmux name + re-sync server snapshot so the
                // restore service uses it after a reboot.
                val tab = tabManager.getTab(sessionId)
                if (tab != null && sessionStorage != null) {
                    val updated = tab.copy(tmuxSessionName = newName)
                    sessionStorage.upsert(SessionStorage.fromClaudeSession(updated))
                    connectionRegistry.ssh(sessionId)?.let { pushSessionsToServer(it, tab.server.id) }
                }
            } catch (e: Exception) {
                FileLogger.error(TAG, "Tmux rename failed", e)
            }
        }
    }

    // ---------------- Server-side restore (systemd) ----------------

    /**
     * Idempotent installer for the user-level systemd service that restores
     * tmux + claude sessions after a server reboot. Writes:
     *   ~/.claude-remote/restore.sh
     *   ~/.config/systemd/user/claude-remote-restore.service
     * then enables linger + the unit. Safe to call on every connect — checks
     * for a marker line in the script before rewriting.
     *
     * Requires: bash, jq, tmux, claude on PATH at boot. The service uses an
     * explicit PATH so it works under empty systemd-user env.
     */
    private val INSTALL_RESTORE_COMMAND = """
        set -e
        mkdir -p "${'$'}HOME/.claude-remote" "${'$'}HOME/.config/systemd/user"
        SCRIPT="${'$'}HOME/.claude-remote/restore.sh"
        DRIFT="${'$'}HOME/.claude-remote/drift.sh"
        UNIT="${'$'}HOME/.config/systemd/user/claude-remote-restore.service"
        DUNIT="${'$'}HOME/.config/systemd/user/claude-remote-drift.service"
        DTIMER="${'$'}HOME/.config/systemd/user/claude-remote-drift.timer"
        LOCK="${'$'}HOME/.claude-remote/sessions.lock"
        MARKER="claude-remote-restore-v6"
        touch "${'$'}LOCK"
        echo "[${'$'}(date -u +%FT%TZ)] install: invoked by client" >> "${'$'}HOME/.claude-remote/install.log"
        if ! grep -q "${'$'}MARKER" "${'$'}SCRIPT" 2>/dev/null; then
            cat > "${'$'}SCRIPT" <<'RESTORE_EOF'
#!/usr/bin/env bash
# claude-remote-restore-v6 — recreates tmux+claude sessions from sessions.json (snapshot under flock)
set -u
LOG="${'$'}HOME/.claude-remote/restore.log"
exec >> "${'$'}LOG" 2>&1
echo "----- ${'$'}(date -u +%FT%TZ) restore.sh start (pid=${'$'}${'$'}) -----"
SESSIONS_FILE="${'$'}HOME/.claude-remote/sessions.json"
LOCK="${'$'}HOME/.claude-remote/sessions.lock"
if [ ! -f "${'$'}SESSIONS_FILE" ]; then
    echo "no sessions.json yet — client has not synced; nothing to restore"
    exit 0
fi
touch "${'$'}LOCK"
SNAP=${'$'}(flock -s "${'$'}LOCK" cat "${'$'}SESSIONS_FILE")
command -v tmux >/dev/null 2>&1 || { echo "tmux not in PATH"; exit 1; }
command -v claude >/dev/null 2>&1 || { echo "claude not in PATH"; exit 1; }
HAVE_JQ=0
command -v jq >/dev/null 2>&1 && HAVE_JQ=1
parse_field() {
    local key="${'$'}1" line="${'$'}2"
    echo "${'$'}line" | sed -n "s/.*\"${'$'}key\":[[:space:]]*\"\([^\"]*\)\".*/\1/p"
}
if [ "${'$'}HAVE_JQ" = "1" ]; then
    COUNT=${'$'}(echo "${'$'}SNAP" | jq 'length')
    for i in ${'$'}(seq 0 ${'$'}((COUNT-1))); do
        TMUX_NAME=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].tmuxSessionName")
        FOLDER=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].folder")
        MODE=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].mode")
        MODEL=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].model")
        UUID=${'$'}(echo "${'$'}SNAP" | jq -r ".[${'$'}i].claudeSessionId // empty")
        tmux has-session -t "${'$'}TMUX_NAME" 2>/dev/null && continue
        FOLDER_EXP="${'$'}{FOLDER/#\~/${'$'}HOME}"
        case "${'$'}FOLDER_EXP" in /*) ;; *) FOLDER_EXP="${'$'}HOME/${'$'}FOLDER_EXP";; esac
        [ -d "${'$'}FOLDER_EXP" ] || { echo "skip ${'$'}TMUX_NAME — folder ${'$'}FOLDER_EXP missing"; continue; }
        case "${'$'}MODEL" in
            LOCAL|LOCAL_ORNITH|LOCAL_QWEN) ARGS=("claude-local");;
            *) ARGS=("claude");;
        esac
        case "${'$'}MODEL" in
            OPUS) ARGS+=(--model opus);;
            FABLE) ARGS+=(--model fable);;
            SONNET) ARGS+=(--model sonnet);;
            HAIKU) ARGS+=(--model haiku);;
        esac
        case "${'$'}MODE" in
            YOLO) ARGS+=(--dangerously-skip-permissions);;
            AUTO) ARGS+=(--permission-mode auto --allow-dangerously-skip-permissions);;
            AUTO_ACCEPT) ARGS+=(--permission-mode acceptEdits --allow-dangerously-skip-permissions);;
            *) ARGS+=(--allow-dangerously-skip-permissions);;
        esac
        # Resume only if a transcript actually exists for this UUID — claude
        # creates the jsonl lazily (first user/assistant turn), so a session
        # that was launched but never used has nothing to resume. Falling back
        # to fresh `--session-id` keeps the UUID stable for next time.
        if [ -n "${'$'}UUID" ]; then
            ENC=${'$'}(echo "${'$'}FOLDER_EXP" | sed 's|/|-|g')
            JSONL="${'$'}HOME/.claude/projects/${'$'}ENC/${'$'}UUID.jsonl"
            if [ -f "${'$'}JSONL" ]; then
                ARGS+=(--resume "${'$'}UUID")
            else
                echo "no transcript at ${'$'}JSONL — launching fresh with --session-id ${'$'}UUID"
                ARGS+=(--session-id "${'$'}UUID")
            fi
        fi
        CMD="${'$'}{ARGS[*]}"
        # `exec bash -l` keepalive: when claude exits (crash, usage limit, OOM,
        # /exit, network blip) the pane drops to a login shell instead of the
        # whole tmux session vanishing — matches app-created sessions, which
        # run claude via `send-keys` into a shell. Without this, a restored
        # session is fragile: it disappears the moment claude stops.
        if tmux new-session -d -s "${'$'}TMUX_NAME" -c "${'$'}FOLDER_EXP" \
            "tmux set-option -g mouse on; tmux set-option -g history-limit 100000; ${'$'}CMD; exec bash -l"; then
            echo "Restored ${'$'}TMUX_NAME (${'$'}FOLDER_EXP) [uuid=${'$'}UUID]"
        else
            echo "FAILED to restore ${'$'}TMUX_NAME (${'$'}FOLDER_EXP) — tmux exit ${'$'}?"
        fi
    done
else
    echo "jq not installed — falling back to line parser"
    while IFS= read -r line; do
        case "${'$'}line" in
            *tmuxSessionName*) TMUX_NAME=${'$'}(parse_field tmuxSessionName "${'$'}line");;
            *\"folder\"*)      FOLDER=${'$'}(parse_field folder "${'$'}line");;
            *\"mode\"*)        MODE=${'$'}(parse_field mode "${'$'}line");;
            *\"model\"*)       MODEL=${'$'}(parse_field model "${'$'}line");;
            *claudeSessionId*) UUID=${'$'}(parse_field claudeSessionId "${'$'}line");;
            *\}*)
                if [ -n "${'$'}{TMUX_NAME:-}" ] && [ -n "${'$'}{FOLDER:-}" ]; then
                    if ! tmux has-session -t "${'$'}TMUX_NAME" 2>/dev/null; then
                        FOLDER_EXP="${'$'}{FOLDER/#\~/${'$'}HOME}"
                        [ -d "${'$'}FOLDER_EXP" ] && {
                            CMD="claude --allow-dangerously-skip-permissions"
                            [ -n "${'$'}{UUID:-}" ] && CMD="${'$'}CMD --resume ${'$'}UUID"
                            tmux new-session -d -s "${'$'}TMUX_NAME" -c "${'$'}FOLDER_EXP" \
                                "tmux set-option -g mouse on; tmux set-option -g history-limit 100000; ${'$'}CMD; exec bash -l"
                            echo "Restored ${'$'}TMUX_NAME"
                        }
                    fi
                fi
                TMUX_NAME=""; FOLDER=""; MODE=""; MODEL=""; UUID=""
                ;;
        esac
    done < "${'$'}SESSIONS_FILE"
fi
RESTORE_EOF
            chmod +x "${'$'}SCRIPT"
            echo "RESTORE_SCRIPT_INSTALLED"
        else
            echo "RESTORE_SCRIPT_PRESENT"
        fi
        if ! grep -q "${'$'}MARKER" "${'$'}DRIFT" 2>/dev/null; then
            cat > "${'$'}DRIFT" <<'DRIFT_EOF'
#!/usr/bin/env bash
# claude-remote-restore-v6 — drift daemon: reconciles sessions.json to mirror
# the LIVE claude-server-* tmux sessions every minute. Self-healing: re-adds
# live sessions a misbehaving/old client truncated away, refreshes
# claudeSessionId from claude's per-pid state files, preserves client-set
# metadata (serverId/alias) for known sessions, and drops entries whose tmux
# session is gone. A single buggy `cat > sessions.json` overwrite from any
# client is non-fatal — within 60s the snapshot is rebuilt from ground truth,
# so the next reboot's restore service still rebuilds every live session.
set -u
LOG="${'$'}HOME/.claude-remote/drift.log"
exec >> "${'$'}LOG" 2>&1
echo "----- ${'$'}(date -u +%FT%TZ) drift start -----"
SF="${'$'}HOME/.claude-remote/sessions.json"
LOCK="${'$'}HOME/.claude-remote/sessions.lock"
command -v tmux >/dev/null 2>&1 || { echo "no tmux"; exit 0; }
command -v jq >/dev/null 2>&1 || { echo "no jq"; exit 0; }
touch "${'$'}LOCK"

# Walk the tmux pane's process tree to find the claude process — pane_pid is
# often bash (claude launched via a shell command / keepalive), so claude is a
# descendant. Recursive descent finds the right pid.
find_claude_descendant() {
    local p=${'$'}1
    if [ "${'$'}(ps -o comm= -p "${'$'}p" 2>/dev/null)" = "claude" ]; then echo "${'$'}p"; return 0; fi
    local c r
    for c in ${'$'}(pgrep -P "${'$'}p" 2>/dev/null); do
        r=${'$'}(find_claude_descendant "${'$'}c"); [ -n "${'$'}r" ] && { echo "${'$'}r"; return 0; }
    done
}

# Ground-truth entry list from the live tmux sessions.
LIVE="[]"
for s in ${'$'}(tmux list-sessions -F '#{session_name}' 2>/dev/null); do
    case "${'$'}s" in claude-server-*) ;; *) continue;; esac
    pane_pid=${'$'}(tmux list-panes -t "${'$'}s" -F '#{pane_pid}' 2>/dev/null | head -1)
    [ -n "${'$'}pane_pid" ] || continue
    folder=${'$'}(tmux display-message -p -t "${'$'}s" '#{pane_current_path}' 2>/dev/null)
    pid=${'$'}(find_claude_descendant "${'$'}pane_pid")
    sid=""; model="DEFAULT"; mode="YOLO"
    if [ -n "${'$'}pid" ]; then
        args=${'$'}(tr '\0' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null)
        case "${'$'}args" in
            *"--model opus"*)   model=OPUS;;
            *"--model sonnet"*) model=SONNET;;
            *"--model haiku"*)  model=HAIKU;;
            *"--model fable"*)  model=FABLE;;
        esac
        case "${'$'}args" in
            *"--permission-mode auto"*) mode=AUTO;;
            *"--permission-mode acceptEdits"*) mode=AUTO_ACCEPT;;
            *"--allow-dangerously-skip-permissions"*) mode=DEFAULT;;
        esac
        sid=${'$'}(echo "${'$'}args" | sed -n 's/.*--\(resume\|session-id\) \([0-9a-f-]*\).*/\2/p' | head -1)
        psf="${'$'}HOME/.claude/sessions/${'$'}pid.json"
        if [ -f "${'$'}psf" ]; then
            v=${'$'}(jq -r .sessionId "${'$'}psf" 2>/dev/null)
            [ -n "${'$'}v" ] && [ "${'$'}v" != "null" ] && sid="${'$'}v"
        fi
    fi
    case "${'$'}s" in *--*) alias="${'$'}{s##*--}";; *) alias="";; esac
    LIVE=${'$'}(echo "${'$'}LIVE" | jq \
        --arg n "${'$'}s" --arg f "${'$'}folder" --arg m "${'$'}mode" --arg md "${'$'}model" --arg a "${'$'}alias" --arg sid "${'$'}sid" \
        '. + [{id:${'$'}n, serverId:"", folder:${'$'}f, mode:${'$'}m, model:${'$'}md, tmuxSessionName:${'$'}n, connectionType:"SSH", alias:${'$'}a, claudeSessionId:(if ${'$'}sid=="" then null else ${'$'}sid end), createdAt:0}]')
done
echo "LIVE=${'$'}(echo "${'$'}LIVE" | jq -c 'map(.tmuxSessionName)')"
(
    flock -x 9
    OLD="[]"; [ -f "${'$'}SF" ] && OLD=${'$'}(cat "${'$'}SF")
    # Keep client metadata for sessions already in OLD (refresh only the live
    # claudeSessionId); add live sessions missing from OLD; drop OLD entries
    # whose tmux session is no longer live.
    NEW=${'$'}(jq -n --argjson old "${'$'}OLD" --argjson live "${'$'}LIVE" '
        (${'$'}old | map({key:.tmuxSessionName, value:.}) | from_entries) as ${'$'}om
        | ${'$'}live | map(
            . as ${'$'}l
            | (${'$'}om[${'$'}l.tmuxSessionName]) as ${'$'}o
            | if ${'$'}o then ${'$'}o + (if ${'$'}l.claudeSessionId != null then {claudeSessionId:${'$'}l.claudeSessionId} else {} end)
              else ${'$'}l end)' 2>/dev/null)
    if [ -n "${'$'}NEW" ] && [ "${'$'}NEW" != "${'$'}OLD" ]; then
        echo "${'$'}NEW" > "${'$'}SF.tmp.${'$'}${'$'}" && mv "${'$'}SF.tmp.${'$'}${'$'}" "${'$'}SF"
        echo "[${'$'}(date -u +%FT%TZ)] drift: reconciled ${'$'}(echo "${'$'}NEW" | jq length) live (was ${'$'}(echo "${'$'}OLD" | jq 'length // 0'))"
    fi
) 9<>"${'$'}LOCK"
DRIFT_EOF
            chmod +x "${'$'}DRIFT"
            echo "DRIFT_SCRIPT_INSTALLED"
        fi
        if ! grep -q "claude-remote-restore" "${'$'}UNIT" 2>/dev/null; then
            cat > "${'$'}UNIT" <<UNIT_EOF
[Unit]
Description=Claude Remote — restore tmux+claude sessions on boot
After=default.target

[Service]
Type=oneshot
RemainAfterExit=yes
Environment=PATH=%h/.local/bin:%h/.npm-global/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=/usr/bin/env bash %h/.claude-remote/restore.sh

[Install]
WantedBy=default.target
UNIT_EOF
            systemctl --user daemon-reload 2>/dev/null || true
            systemctl --user enable claude-remote-restore.service 2>/dev/null || true
            loginctl enable-linger "${'$'}USER" 2>/dev/null || true
            echo "RESTORE_UNIT_INSTALLED"
        else
            systemctl --user enable claude-remote-restore.service 2>/dev/null || true
            echo "RESTORE_UNIT_PRESENT"
        fi
        if [ ! -f "${'$'}DUNIT" ] || [ ! -f "${'$'}DTIMER" ]; then
            cat > "${'$'}DUNIT" <<DUNIT_EOF
[Unit]
Description=Claude Remote — sync sessions.json with claude session_ids

[Service]
Type=oneshot
Environment=PATH=%h/.local/bin:%h/.npm-global/bin:/usr/local/bin:/usr/bin:/bin
ExecStart=/usr/bin/env bash %h/.claude-remote/drift.sh
DUNIT_EOF
            cat > "${'$'}DTIMER" <<DTIMER_EOF
[Unit]
Description=Run claude-remote-drift every minute

[Timer]
OnBootSec=2min
OnUnitActiveSec=1min

[Install]
WantedBy=timers.target
DTIMER_EOF
            systemctl --user daemon-reload 2>/dev/null || true
            systemctl --user enable --now claude-remote-drift.timer 2>/dev/null || true
            echo "DRIFT_TIMER_INSTALLED"
        fi
        # Probe whether linger actually stuck — without it the user systemd
        # instance dies on logout and the restore unit never fires after reboot.
        # Most distros require polkit/sudo for `loginctl enable-linger`, so
        # the call above often fails silently. Surface the verdict in the log
        # so the client can warn the user once.
        LINGER=${'$'}(loginctl show-user "${'$'}USER" --property=Linger --value 2>/dev/null || echo unknown)
        echo "LINGER=${'$'}LINGER"
    """.trimIndent()

    /**
     * Track which servers we've already attempted install on this app session
     * to avoid pinging on every persist. Best-effort — if the install fails
     * (no systemd, e.g. macOS, BSD), the in-app reconnect path still works.
     */
    private val installedRestoreServers = mutableSetOf<String>()

    private suspend fun ensureRestoreService(sshManager: SshManager) {
        val serverId = tabManager.tabs.value.firstOrNull { connectionRegistry.ssh(it.id) === sshManager }?.server?.id
        if (serverId != null) {
            synchronized(installedRestoreServers) {
                if (!installedRestoreServers.add(serverId)) return
            }
        }
        try {
            val out = withContext(Dispatchers.IO) {
                val sshSession = sshManager.getSession() ?: return@withContext "NO_SESSION"
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(INSTALL_RESTORE_COMMAND)
                ch.inputStream = null
                val input = ch.inputStream
                ch.connect(15_000)
                val text = input.bufferedReader().readText().trim()
                ch.disconnect()
                text
            }
            FileLogger.log(TAG, "Restore service install: $out")
            if (out.contains("LINGER=no") || out.contains("LINGER=unknown")) {
                FileLogger.log(TAG,
                    "WARNING: linger is not enabled on the server — the restore service " +
                    "will only fire after a manual login. Run `sudo loginctl enable-linger \$USER` " +
                    "on the server to make session persistence work after reboot."
                )
            }
        } catch (e: Exception) {
            FileLogger.error(TAG, "Restore service install failed: ${e.message}", e)
        }
    }

    /**
     * Push the per-server `sessions.json` snapshot to the remote server via
     * `cat > tmp && mv tmp final` (atomic rename). The systemd restore unit
     * reads this file at boot.
     */
    private suspend fun pushSessionsToServer(sshManager: SshManager, serverId: String) {
        val storage = sessionStorage ?: return
        try {
            val payload = storage.serializeForServer(serverId)
            withContext(Dispatchers.IO) {
                val sshSession = sshManager.getSession() ?: return@withContext
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                // MERGE, not overwrite. The previous `cat > tmp && mv` clobbered
                // the shared sessions.json with only THIS client's sessions, so
                // whichever client (Android vs desktop) synced last silently
                // dropped the others' sessions — and the next reboot's restore
                // service then only rebuilt that truncated subset.
                //
                // New semantics (under the same flock the drift daemon + restore
                // use): keep the incoming list (this client wins for its own
                // sessions) PLUS any existing entry whose tmux session is still
                // LIVE on the server and isn't already in the incoming list.
                // Killed/forgotten sessions (kill-session runs before this push)
                // are no longer live and aren't in incoming, so they correctly
                // drop out — no resurrection on reboot. Falls back to a plain
                // overwrite when jq is unavailable (matches old behaviour).
                //
                // The incoming + scratch temp files are suffixed with the remote
                // shell's PID ($$) so a burst of near-simultaneous pushes (the
                // app fires several on a multi-tab reconnect) can't race on a
                // shared path — without per-PID names an earlier push would
                // `rm` the incoming file out from under a later one, which then
                // merged against an empty incoming and collapsed sessions.json.
                val safeServerId = serverId.replace("\"", "")
                ch.setCommand(
                    "set -u; D=\"\$HOME/.claude-remote\"; mkdir -p \"\$D\"; " +
                    "LOCK=\"\$D/sessions.lock\"; touch \"\$LOCK\"; " +
                    "SF=\"\$D/sessions.json\"; INC=\"\$D/.sessions.incoming.\$\$\"; " +
                    "cat > \"\$INC\"; " +
                    "if command -v jq >/dev/null 2>&1; then " +
                      "LIVE=\$(tmux list-sessions -F '#{session_name}' 2>/dev/null | jq -R . | jq -s . 2>/dev/null); " +
                      "[ -n \"\$LIVE\" ] || LIVE='[]'; " +
                      "( flock -x 9; " +
                        "OLD='[]'; [ -f \"\$SF\" ] && OLD=\$(cat \"\$SF\"); " +
                        "MERGED=\$(jq -n --slurpfile inc \"\$INC\" --argjson old \"\$OLD\" --argjson live \"\$LIVE\" '" +
                          "(\$inc[0] // []) as \$incoming " +
                          "| (\$incoming | map(.tmuxSessionName)) as \$names " +
                          "| \$incoming + (\$old | map(. as \$e | select(((\$names | index(\$e.tmuxSessionName)) | not) and (\$live | index(\$e.tmuxSessionName)))))" +
                        "' 2>/dev/null); " +
                        "if [ -n \"\$MERGED\" ]; then printf '%s' \"\$MERGED\" > \"\$SF.tmp.\$\$\" && mv \"\$SF.tmp.\$\$\" \"\$SF\"; else cp \"\$INC\" \"\$SF\"; fi " +
                      ") 9<>\"\$LOCK\"; " +
                    "else cp \"\$INC\" \"\$SF\"; fi; " +
                    "rm -f \"\$INC\"; " +
                    "echo \"[\$(date -u +%FT%TZ)] push(merge): ${payload.length} bytes for $safeServerId\" >> \"\$D/push.log\""
                )
                ch.inputStream = null
                val os = ch.outputStream
                ch.connect(5000)
                os.write(payload.toByteArray(Charsets.UTF_8))
                os.flush()
                os.close()
                val deadline = System.currentTimeMillis() + 5000
                while (!ch.isClosed && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(50)
                }
                val exit = ch.exitStatus
                ch.disconnect()
                if (exit != 0) {
                    FileLogger.error(
                        TAG,
                        "sessions.json sync exec exited with $exit for $serverId — restore service may use stale data",
                        null
                    )
                    return@withContext
                }
            }
            FileLogger.log(TAG, "Synced sessions.json to server $serverId (${payload.length} bytes)")
        } catch (e: Exception) {
            FileLogger.error(TAG, "sessions.json sync failed for $serverId: ${e.message}", e)
        }
    }

    /**
     * Rehydrate persisted sessions on app start. Returns the list of
     * ClaudeSessions that were restored into [tabManager] (status =
     * CONNECTING). Caller is responsible for triggering reconnectSession()
     * for each, which will probe tmux and either attach or rebuild via
     * `claude --resume <uuid>`.
     */
    @Volatile private var restoreDone = false

    fun restorePersistedTabs(): List<ClaudeSession> {
        val storage = sessionStorage ?: return emptyList()
        // Idempotent — if the host (Activity, app window) calls this twice
        // (e.g. Android configuration change re-runs initApp), we mustn't
        // duplicate tabs or fan out parallel reconnects to the same tmux.
        if (restoreDone) return emptyList()
        restoreDone = true
        val persisted = storage.load()
        if (persisted.isEmpty()) return emptyList()
        val existingIds = tabManager.tabs.value.map { it.id }.toSet()
        val rehydrated = persisted.mapNotNull { p ->
            if (p.id in existingIds) return@mapNotNull null
            val cs = SessionStorage.toClaudeSession(p, serverStorage)
            if (cs == null) {
                FileLogger.log(TAG, "Dropping persisted session ${p.id}: server ${p.serverId} not found")
                storage.remove(p.id)
            }
            cs
        }
        rehydrated.forEach { session ->
            terminalIO.initBuffer(session.id)
            tabManager.addTab(session)
            tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
            statusService.updateActivity(session.id, SessionActivity.DISCONNECTED)
        }
        FileLogger.log(TAG, "Restored ${rehydrated.size} persisted sessions to tabs")
        return rehydrated
    }

    /**
     * One-shot restore + background reconnect, scoped to the orchestrator's
     * own [reconnectScope] (SupervisorJob-backed) so callers don't need to
     * wire a CoroutineScope. Idempotent — safe to call from both Activity
     * onCreate and Window init without producing duplicate connect attempts.
     */
    fun restoreAndReconnect() {
        val restored = restorePersistedTabs()
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
