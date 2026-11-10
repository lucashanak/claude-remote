package com.clauderemote.session

import com.clauderemote.connection.SshManager
import com.clauderemote.connection.SshSessionHelper
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import com.clauderemote.model.*
import com.clauderemote.session.transcript.TranscriptEntry
import com.clauderemote.session.service.ConnectionRegistry
import com.clauderemote.session.service.execReadWithWatchdog
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.CommLogFormat
import com.clauderemote.session.service.DefaultServerBootstrap
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.ExecUnavailableReason
import com.clauderemote.session.service.GatedExecChannels
import com.clauderemote.session.service.HookEventSink
import com.clauderemote.session.service.LinkState
import com.clauderemote.session.service.ManifestCommands
import com.clauderemote.session.service.PoolExecTransportManager
import com.clauderemote.session.service.PooledRemoteExec
import com.clauderemote.session.service.ServerBootstrap
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import com.clauderemote.storage.ServerStorage
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
) : NetworkCallbackHub.Target {
    internal val connectionRegistry = ConnectionRegistry(serverStorage, tabManager)

    // Shared background scope for all per-session/per-server loops. Declared
    // early so collaborator services can take it by constructor injection.
    // The handler is a safety net: on Android an exception nobody caught in a launched coroutine ends the
    // process, and almost everything the exec layer starts here ends in a network call that can throw. The
    // launch sites of the exec paths catch and name their own failure ([launchGuarded]); this one only logs
    // what slipped through.
    private val reconnectScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob() +
            CoroutineExceptionHandler { _, e -> FileLogger.error(TAG, "Uncaught exception in a background coroutine", e) }
    )

    /** [reconnectScope].launch that catches (not [CancellationException]) and logs [what] instead of leaving the exception to the app. */
    private fun launchGuarded(what: String, block: suspend () -> Unit): kotlinx.coroutines.Job = reconnectScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.error(TAG, "$what failed", e)
        }
    }

    init {
        // Route one-off SSH ops (scanRemoteSessions, status/transcript polls)
        // onto an existing pooled transport instead of a fresh SSH-over-CF
        // handshake each time — the dominant idle data drain.
        // With exec routing on, only a pooled transport that still has channel headroom (the exec
        // transport is down and the tabs sit 8 to a transport: a one-off op there would be refused).
        // The runner holds one COUNTED channel on that transport while the op runs (NN1); the provider below is the
        // look-only view of the same choice, for diagnostics.
        com.clauderemote.connection.SshSessionHelper.fallbackRunner = connectionRegistry
        com.clauderemote.connection.SshSessionHelper.liveSessionProvider = { server ->
            connectionRegistry.fallbackPooledSession(server.id)
        }

        // Data-usage snapshot every 60s so we can see where the bytes actually
        // go (terminal is DECODED — compressed on the wire; transcript is the
        // on-wire base64/gzip payload; appRx/Tx is the real app-wide traffic).
        reconnectScope.launch {
            var pTerm = 0L; var pTr = 0L; var pPoll = 0L
            // NULL, not 0: these counters are cumulative since BOOT, so a first
            // delta measured against zero reports the machine's whole uptime as
            // one minute's traffic — on desktop, where the counter is
            // machine-wide, that is tens of gigabytes in the first line. The
            // first sample only seeds the baseline.
            var pRx: Long? = null; var pTx: Long? = null
            var pExec = 0
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
                    val baseRx = pRx; val baseTx = pTx
                    pRx = net.rx; pTx = net.tx
                    // A machine-wide sum is NOT monotonic: bring a VPN down,
                    // unplug a USB NIC or toggle Wi-Fi and the interface (with
                    // its counters) disappears from the total, so the next
                    // delta goes hugely negative. Per-UID counters can't do
                    // that, so nothing downstream guards against it. Treat a
                    // decrease as a re-seed rather than logging nonsense.
                    val dRx = if (baseRx == null || net.rx < baseRx) null else (net.rx - baseRx) / 1024
                    val dTx = if (baseTx == null || net.tx < baseTx) null else (net.tx - baseTx) / 1024
                    // Say WHICH of the two silent cases this is: a first sample
                    // has no baseline yet, a later one means the counter went
                    // backwards. Logging "" for both left it reading like the
                    // platform has no counter at all.
                    if (dRx == null || dTx == null) {
                        if (baseRx == null) " | net=baseline" else " | net=reseed"
                    } else if (net.appScoped) {
                        // Residual = real traffic not attributed to counted content —
                        // i.e. SSH/ET/CF keepalives + channel framing. Only meaningful
                        // when the counter is scoped to this app (Android); a
                        // machine-wide count (desktop) would attribute every other
                        // process's traffic to us, so no residual is printed there.
                        val residual = (dRx + dTx) - dTerm - dTr - dPoll
                        " | appRx=${dRx}KB appTx=${dTx}KB overhead≈${residual}KB"
                    } else {
                        " | sysRx=${dRx}KB sysTx=${dTx}KB"
                    }
                } else {
                    // A missed sample must clear the baseline, not keep it: the
                    // next successful poll would otherwise report TWO minutes of
                    // traffic under a line that says data/60s. Dropping it makes
                    // the next line an honest baseline instead.
                    pRx = null; pTx = null
                    ""
                }
                // exec= is the reconcile load of the minute (execs issued), not a running total.
                val execNow = connectionRegistry.reconcileExecCount()
                val dExec = execNow - pExec
                pExec = execNow
                FileLogger.log(TAG, CommLogFormat.data60sLine(
                    "data/60s: $mode sessions=$sessions(et=$etCount) conn=$conn | content term=${dTerm}KB tr=${dTr}KB poll=${dPoll}KB$netStr",
                    connectionRegistry,
                    exec = dExec,
                ))
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
    internal val statusService = com.clauderemote.session.service.StatusService(
        reconnectScope, connectionRegistry, tabManager, { isInBackground },
        { id -> transcriptService.markWorkSeen(id) },
        { id -> gitStatusService.probeOnIdle(id) },
    )

    // Input-prompt detection, needs-input notifications, Stop-hook watcher and
    // login flow (no input queue since kolo 5 of batch C). Declared after the
    // services it bridges to (statusService/transcriptService) exist.
    internal val notificationService = com.clauderemote.session.service.NotificationService(
        reconnectScope, connectionRegistry, tabManager, { isInBackground },
        { id, act -> statusService.updateActivity(id, act) },
        { id -> transcriptService.lastAssistantEntry(id) },
        { id -> transcriptService.entries(id) },
        { id -> transcriptService.streamOrNull(id) },
        { sid, kind, hint, active, body, prompt -> onClaudeNeedsInput?.invoke(sid, kind, hint, active, body, prompt) },
        { id -> statusService.sessionActivities.value[id] },
        statusService.promptTokens,
        { sid -> onDialogAnswered?.invoke(sid) },
        { sid, endedAt -> statusService.markClaudeEnded(sid, endedAt) },
    )

    // Per-session terminal output buffer (ring buffer) + pty size tracking.
    private val terminalIO = com.clauderemote.session.service.TerminalIOService(connectionRegistry)

    // User input delivery (mosh-first, ssh-fallback; only into an ACTIVE tab, never
    // queued) and the Claude slash-command control surface. Coordinator over the
    // services above; declared after them since it composes them.
    private val claudeControl = com.clauderemote.session.service.ClaudeControlService(
        reconnectScope, connectionRegistry, tabManager, statusService, notificationService, terminalIO,
    ) { sid, data -> onTerminalOutput?.invoke(sid, data) }

    /** Test seam: the reply path's own route and exec fakes (`routeOverride`, `replyExecOverride`). */
    internal val claudeControlForTest get() = claudeControl

    private val remoteOps = com.clauderemote.session.service.RemoteOpsService(
        reconnectScope, connectionRegistry, tabManager,
    )

    // Server-side Claude logins: enumerate/provision/remove account config dirs
    // and drive `claude auth login`. Takes serverStorage as well as the registry
    // because the accounts screen can be opened before any session on that
    // server is connected (then it borrows a one-off/pooled transport).
    private val accountService = com.clauderemote.session.service.AccountService(
        connectionRegistry, serverStorage,
    )

    // Shared sessions.json (fetch/cache/push), the systemd restore-service
    // installer + restore/drift scripts, per-session real-UUID refresh
    // pollers, and the forget/rename lifecycle edits.
    internal val persistence = com.clauderemote.session.service.SessionPersistenceService(
        reconnectScope, connectionRegistry, tabManager, serverStorage, sessionStorage,
        transcriptService, terminalIO, { isInBackground }, ::readRealSessionId,
        // Lambda, not a method reference: tmuxProbes is declared BELOW this
        // property, so a reference would capture an uninitialised field.
        { ssh, tmux -> tmuxProbes.probeTmuxSession(ssh, tmux) },
        { id, act -> statusService.updateActivity(id, act) },
        ::disconnectSession,
        { sid, tmux -> onSessionForgotten?.invoke(sid, tmux) },
        // read at close time: deviceKey is set by startLogShipping after construction
        closedBy = { "app:$deviceKey" },
    )

    // Tailscale/Cloudflare/direct transport selection + per-session connection
    // labels. Owns the AUTO probe, cooldown/failstreak arithmetic, and the
    // short-TTL resolved-transport cache. Declared after the deps it uses.
    private val transportResolver = com.clauderemote.session.service.TransportResolver(reconnectScope, connectionRegistry)
    private val tmuxProbes = com.clauderemote.session.service.TmuxProbes(reconnectScope, connectionRegistry, tabManager, terminalIO)

    /**
     * One exec transport per server: its own SSH connection for everything that is not a tab's terminal
     * (polls, reconcile, probes, uploads, the notify watcher, the stream daemon, log shipping). Installed
     * into the registry's slots by [wireExecLayer]; [CommFlags] (`comm_exec`, per server) decides whether
     * anything uses it. Lambdas, not bound references: [transportResolver] is a property declared above,
     * but the manager must not capture a half-built object if the order of these declarations changes.
     */
    internal val execManager = PoolExecTransportManager(
        scope = reconnectScope,
        poolOf = { connectionRegistry.transportPool(it) },
        serverOf = { serverStorage.getServer(it) },
        resolve = { transportResolver.resolveTransport(it) },
        onEarlyDeath = { server, eff, lifetimeMs -> transportResolver.noteServerEarlyDeath(server, eff, lifetimeMs) },
        onStable = { server, eff -> transportResolver.noteServerTransportStable(server, eff) },
    )

    /** Servers whose exec transport this orchestrator started, so [disconnectAll] can close them. */
    private val execEnsuredServers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Attach phase timing per session, from the connect until the first terminal byte after `tmux attach` (the `Attach` log line). */
    private val attachTraces = java.util.concurrent.ConcurrentHashMap<String, AttachTrace>()

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
    /** Model id each session's latest answer came from — see TranscriptService.activeModels. */
    val activeModels: kotlinx.coroutines.flow.StateFlow<Map<String, String>> get() = transcriptService.activeModels

    // Per-session SSH latency (ms) — owned by serverHealthService.
    val latencies: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> get() = serverHealthService.latencies

    // Per-session git status of the working directory — owned by gitStatusService.
    val gitStatuses: kotlinx.coroutines.flow.StateFlow<Map<String, GitStatus>> get() = gitStatusService.gitStatuses

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

    // Notification callback when Claude needs attention. [prompt] names the prompt the alert is
    // for (null when no event named one): an actionable notification carries it so its reply can
    // be checked with [sendReply].
    var onClaudeNeedsInput: ((
        sessionId: String,
        kind: com.clauderemote.session.service.NotifyKind,
        hint: String,
        isActiveTab: Boolean,
        body: String?,
        prompt: com.clauderemote.session.service.PromptFingerprint?,
    ) -> Unit)? = null

    // The hooks report a pending permission/question of the session answered (from anywhere):
    // the platform takes its alert down (DialogAlertTracker).
    var onDialogAnswered: ((sessionId: String) -> Unit)? = null

    /** Whether v2 hook events drive this session (CAP=1): its activity edges are then the hooks' word. */
    fun hasHookEvents(sessionId: String): Boolean = notificationService.promptDetector.hasHookEvents(sessionId)

    // Context window usage callback (0-100 percent)
    var onContextUpdate: ((sessionId: String, percent: Int) -> Unit)? = null

    // Usage stats callback (session%, week%)
    var onUsageUpdate: ((sessionPercent: Int?, weekPercent: Int?) -> Unit)? = null

    // Fired when a session is permanently forgotten (tab closed). Lets the UI
    // prune the matching entry from its (stale) remote-tmux snapshot so the
    // killed pane doesn't reappear as a "detached remote" row and resurrect
    // into a new empty session when tapped.
    var onSessionForgotten: ((serverId: String, tmuxSessionName: String) -> Unit)? = null

    // 5h/week usage percentages, keyed by [usageKeyFor] — owned by usageService.
    val sessionUsagePercents: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> get() = usageService.sessionUsagePercents
    val weekUsagePercents: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> get() = usageService.weekUsagePercents

    // Time-to-reset (minutes) parsed from the OMC `(XhYm)` suffix — owned by usageService.
    val sessionResetMin: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> get() = usageService.sessionResetMin
    val weekResetMin: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> get() = usageService.weekResetMin

    /** Every rate-limit window per login (5h, weekly, model caps) — owned by usageService. */
    val usageBuckets: kotlinx.coroutines.flow.StateFlow<Map<String, List<com.clauderemote.model.UsageBucket>>>
        get() = usageService.usageBuckets

    /**
     * One-shot usage fetch for a login nothing is polling — used by the
     * all-accounts usage page on open and on refresh. Returns false when the
     * numbers couldn't be refreshed (no connection, timeout, or a 429 from a
     * quota shared with the statusline), leaving the last known values on screen.
     */
    suspend fun refreshAccountUsage(serverId: String, accountSlug: String?): Boolean =
        usageService.fetchRateLimitsOnce(serverId, accountSlug)

    /**
     * Key into the four usage maps above for one LOGIN on one server. The 5h and
     * weekly limits belong to the account, not the machine, so a session running
     * under a second seat must not be shown the default seat's numbers.
     *
     * The DEFAULT account's key is the bare server id, so a lookup by
     * `activeServerId` keeps returning the default seat's values exactly as it did
     * before multi-account existed.
     */
    fun usageKeyFor(serverId: String, accountSlug: String?): String =
        com.clauderemote.session.service.UsageService.usageKey(serverId, accountSlug)



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

    /** Sessions showing Claude's "login expires in N days" banner, by session id. */
    val loginExpiryWarnings: kotlinx.coroutines.flow.StateFlow<Map<String, com.clauderemote.model.LoginExpiryWarning>>
        get() = notificationService.loginExpiryWarnings

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
                    val wait = ReconnectPolicy.backgroundDelayMs(attempt)
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
            // Rate-limit chips are per LOGIN: idempotent per (server, account), so
            // three sessions on two accounts end up with two pollers, each reading
            // its own credentials file.
            usageService.startServerRateLimitPolling(serverId, tabManager.getTab(sessionId)?.accountSlug)
            serverHealthService.startServerLatencyPolling(serverId)
            notificationService.startNotifyWatcher(sessionId, tmuxSessionName, serverId)
        }
        gitStatusService.startGitStatusPolling(sessionId)
        startReconcileLoop(sessionId, tmuxSessionName)
        // Keep a transcript stream running for every connected session so the
        // notification body (last assistant message) is available even in Raw
        // view, not only after the Chat view has subscribed.
        transcriptService.ensureTranscriptStream(sessionId)
        // …and feed it from the shared per-server stream daemon.
        transcriptService.registerStreamWatch(sessionId)
    }

    /**
     * One reconcile per server replaces the per-tab session-id loop when exec routing is on for the server;
     * the per-tab loop (which also guards itself) stays for the legacy path. Exactly one of the two runs for a
     * tab: switching routing at run time ([onExecFlagChanged]) calls this again and the other one is stopped.
     */
    private fun startReconcileLoop(sessionId: String, tmuxSessionName: String) {
        val reconciler = connectionRegistry.reconciler
        val serverId = connectionRegistry.serverIdOf(sessionId)
        if (reconciler != null && serverId != null && connectionRegistry.execActive(serverId)) {
            persistence.stopIdRefresh(sessionId)
            reconciler.register(sessionId)
        } else {
            reconciler?.unregister(sessionId)
            connectionRegistry.ssh(sessionId)?.let { conn ->
                persistence.startSessionIdRefresh(sessionId, tmuxSessionName, conn)
            }
        }
    }

    @Volatile private var isInBackground = false
    private val reconnectingSessionIds = mutableSetOf<String>()

    /**
     * C4: a loss reported while a reconnect of the session held [reconnectingSessionIds] (the shell that reconnect built
     * died under it): what to run [autoReconnect] with again. Put under that lock by the skipped [autoReconnect], taken
     * by [endReconnect], so a loss is never dropped between the two.
     */
    private val lostDuringReconnect = java.util.concurrent.ConcurrentHashMap<String, Pair<ClaudeSession, (String) -> Unit>>()

    /**
     * C4: a transport of [sessionId] that carries its terminal now (ET, mosh or the SSH shell — what input is routed to).
     * Kolo 3 (adv N5): an ET tab's terminal is its ET client; the SSH shell beside it is only the carrier, whose output is
     * dropped — a live carrier under a dead ET client is no live terminal. Kolo 4 (adv N2): also once the dead client was
     * taken out of the registry (its `onDisconnect`): the carrier stays marked as one ([ConnectionRegistry.isEtCarrier]).
     */
    private fun hasLiveTransport(sessionId: String): Boolean {
        connectionRegistry.et(sessionId)?.let { return it.isConnected }
        if (connectionRegistry.isEtCarrier(sessionId)) return false
        return connectionRegistry.mosh(sessionId)?.isConnected == true ||
            connectionRegistry.ssh(sessionId)?.isConnected == true
    }

    /**
     * C4: the end of a reconnect that held [reconnectingSessionIds]. The lock and the loss recorded meanwhile go together
     * (kolo 3: one critical section — a record is only ever written under that lock, so it is this run's, and a report
     * after this point takes the lock and runs its own reconnect). A reconnect that ended ACTIVE ([succeeded]) over a
     * connection that is no longer alive runs [autoReconnect] again, from DISCONNECTED like any loss (its attempt count
     * and backoff start over); a failed one has handed the tab to [armReconnectRetry] already.
     */
    private fun endReconnect(sessionId: String, succeeded: Boolean) {
        val (session, emit) = synchronized(reconnectingSessionIds) {
            reconnectingSessionIds.remove(sessionId)
            lostDuringReconnect.remove(sessionId)
        } ?: return
        if (!succeeded || released || tabManager.getTab(sessionId) == null || hasLiveTransport(sessionId)) return
        FileLogger.log(TAG, "Connection of $sessionId was lost during its reconnect — reconnecting again")
        tabManager.updateTabStatus(sessionId, SessionStatus.DISCONNECTED)
        statusService.updateActivity(sessionId, SessionActivity.DISCONNECTED)
        reconnectScope.launch { autoReconnect(session, emit) }
    }

    /**
     * Proactive teardown on a platform network-lost event (Android
     * ConnectivityManager). The interface our TCP connections rode is gone —
     * they cannot survive — but keepalive wouldn't flag them for up to ~30 s
     * (fg) / 3 min (bg), leaving every tab frozen through a Wi-Fi→LTE
     * handover. Kill the pooled transports now; the read loops EOF, each
     * session's autoReconnect arms, and the sweep on onAvailable/onResume
     * reconnects on the new network within ~1 s instead of ~20.
     */
    override fun onNetworkLost() {
        if (released) return
        FileLogger.log(TAG, "Network lost — tearing down ${connectionRegistry.transportPoolCount()} transport pool(s)")
        // Our own teardown must not count as Tailscale early-death strikes.
        transportResolver.markNetworkTeardown()
        // The exec transports first: they park in DownNetwork (no strike, no backoff climb) before the
        // teardown kills their sessions, which would otherwise read as a death of the exec transport.
        execManager.onNetworkLost()
        connectionRegistry.teardownAllTransports()
        // The AUTO decision was made on the old network — a Wi-Fi→LTE switch
        // changes Tailscale reachability, so re-probe on the next resolve.
        transportResolver.clearResolvedCache()
        // The auto-reconnects of the dead tabs start now, before the network is back.
        openReconnectWave()
    }

    /**
     * The default network is back (Android `onAvailable`). Wakes the exec transports and makes sure every
     * server that has a tab has one, BEFORE the dead tabs reconnect: the first reconnects of the wave wait
     * for it a moment ([awaitExecForWave]), so their leases find it up and get the shell cap of 8, and the
     * tab-side execs run on it.
     */
    override fun onNetworkAvailable() {
        if (released) return
        openReconnectWave()
        execManager.onNetworkAvailable()
        ensureExecTransports()
    }

    // Single in-flight reconnect sweep. Flapping networks fire onAvailable in bursts; without this each edge spawned
    // a detached coroutine that piled stale connect attempts (5-10 s timeouts each) onto the half-dead link. One sweep
    // at a time. The lock guards the check-and-launch: onAvailable fires on a binder thread, onResume on the main one.
    @Volatile private var reconnectSweepJob: kotlinx.coroutines.Job? = null
    private val reconnectSweepLock = Any()

    /**
     * Reconnect every tab that is DISCONNECTED **or ERROR** (a failed reconnectSession leaves the tab in ERROR, and a
     * DISCONNECTED-only filter skipped those forever), concurrently. Called on a network edge ([NetworkCallbackHub])
     * and on resume. Runs in this orchestrator's scope, so a replaced orchestrator ([shutdown]) sweeps nothing.
     */
    override fun reconnectDeadTabs(reason: String) {
        if (released) return
        // A parked tab (adopted from opened.d) waits for the user's first tap: no shell until then.
        // C4: and ACTIVE without a live transport — a reconnect that ended over a shell which died during it left one
        val parked = _parkedTabs.value
        val dead = tabManager.tabs.value.filter {
            it.id !in parked && (
                it.status == com.clauderemote.model.SessionStatus.DISCONNECTED ||
                    it.status == com.clauderemote.model.SessionStatus.ERROR ||
                    (it.status == com.clauderemote.model.SessionStatus.ACTIVE && !hasLiveTransport(it.id)))
        }
        if (dead.isEmpty()) return
        synchronized(reconnectSweepLock) {
            if (reconnectSweepJob?.isActive == true) {
                FileLogger.log("Network", "Reconnect sweep already running, skipping ($reason)")
                return
            }
            FileLogger.log("Network", "Reconnect sweep ($reason): ${dead.size} dead tab(s)")
            reconnectSweepJob = reconnectScope.launch {
                terminalIO.awaitFirstViewSize(VIEW_SIZE_WAIT_MS) // C4: the cold start's onResume sweep, see restoreAndReconnect
                // Concurrent, not sequential — see restoreAndReconnect's comment. reconnectSession's own single-flight
                // guard makes this safe even if another sweep/trigger is mid-flight for the same tab.
                dead.map { session ->
                    async {
                        try {
                            // kolo 3: the list is up to 2 s old (the size wait): a tab healed meanwhile keeps its live shell
                            val now = tabManager.getTab(session.id) ?: return@async
                            if (now.status == com.clauderemote.model.SessionStatus.ACTIVE && hasLiveTransport(session.id)) return@async
                            reconnectSession(session.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // reconnectSession re-arms its own persistent retry loop.
                        }
                    }
                }.forEach { it.join() }
            }
        }
    }

    /** End of the window in which a reconnect waits for its server's exec transport, see [awaitExecForWave]. */
    @Volatile private var reconnectWaveUntil = 0L

    private fun openReconnectWave() {
        reconnectWaveUntil = System.currentTimeMillis() + EXEC_WAVE_WAIT_MS
    }

    /**
     * The first reconnects after a restore or a network change wait up to [EXEC_WAVE_WAIT_MS] (together, not
     * each: the window opens once per event) for the exec transport of their server that is just coming up.
     * The shell cap is 8 only while it is Up, and leases made before that get 3: a wave of 49 tabs would
     * open 17 transports where 7 do. A transport that is Down (backing off), closed or switched off is not
     * waited for.
     */
    internal suspend fun awaitExecForWave(serverId: String) {
        val left = reconnectWaveUntil - System.currentTimeMillis()
        if (left <= 0 || !connectionRegistry.execActive(serverId) || connectionRegistry.execUp(serverId)) return
        val st = execManager.state(serverId).value
        if (st !is LinkState.Connecting && st !is LinkState.DownNetwork) return
        kotlinx.coroutines.withTimeoutOrNull(left) { execManager.await(serverId, left) }
    }

    /**
     * Make sure the exec transport and the per-server bootstrap exist for every server that has a tab
     * (idempotent; foreground, network back, first connect of a tab). Fire and forget.
     */
    fun ensureExecTransports() {
        if (released) return
        for (serverId in tabManager.tabs.value.map { it.server.id }.distinct()) {
            ensureExecTransport(serverId)
            kickBootstrap(serverId)
        }
    }

    /**
     * The exec transport of [serverId] lives while the server has a tab (one that is not being closed);
     * calling this again is free. It also starts the watch on the server's `exec` flag, which closes the
     * transport when the flag goes off at run time and starts it again when it comes back.
     */
    internal fun ensureExecTransport(serverId: String) = synchronized(execLock(serverId)) {
        if (released) return@synchronized
        if (!hasLiveTab(serverId)) return@synchronized
        watchExecFlag(serverId)
        if (!CommFlags.effectiveFor(serverId).exec) return@synchronized
        execEnsureHook?.invoke(serverId)
        execEnsuredServers.add(serverId)
        execManager.ensure(serverId)
    }

    /**
     * Per-server lock of the exec transport's lifecycle (NN4): [ensureExecTransport] and every close (the last tab gone,
     * the exec flag switched off, [releaseExecLayer]) decide and act under it, so a tab that opens while the last one
     * closes either keeps the transport or starts it again after the close, never finds it closed behind its back.
     * Nothing under it suspends (`ensure`/`close` only start or cancel work).
     */
    private fun execLock(serverId: String): Any = execLocks.computeIfAbsent(serverId) { Any() }
    private val execLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** Test hooks inside [execLock]: after the checks of [ensureExecTransport] / before an exec transport is closed. */
    @Volatile internal var execEnsureHook: ((serverId: String) -> Unit)? = null
    @Volatile internal var execCloseHook: ((serverId: String) -> Unit)? = null

    /** A tab of [serverId] that is not on its way out ([closingIds]), or a launch creating one (V16). */
    private fun hasLiveTab(serverId: String): Boolean =
        tabManager.tabs.value.any { it.server.id == serverId && it.id !in closingIds } || launchPending(serverId)

    /**
     * V16: launches of [serverId] creating their session before their tab exists, over the exec transport (mosh): they
     * keep the transport wanted like a tab does, so neither [ensureExecTransport] refuses it nor a close drops it.
     */
    private val pendingLaunches = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    private fun launchPending(serverId: String): Boolean = (pendingLaunches[serverId]?.get() ?: 0) > 0

    private fun beginLaunchPending(serverId: String) {
        pendingLaunches.computeIfAbsent(serverId) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
    }

    /**
     * [hold]'s launch is through (its tab exists, or it failed): with no tab and no launch left, the transport goes as with
     * the last tab. Once per launch.
     */
    private fun endLaunchPending(hold: LaunchHold) {
        val serverId = hold.pendingOn ?: return
        hold.pendingOn = null
        pendingLaunches[serverId]?.decrementAndGet()
        synchronized(execLock(serverId)) {
            if (tabManager.tabs.value.none { it.server.id == serverId } && !launchPending(serverId) && serverId in execEnsuredServers) {
                closeExecTransportLocked(serverId)
            }
        }
    }

    /** Close [serverId]'s exec transport and its flag watcher. Caller holds [execLock]. */
    private fun closeExecTransportLocked(serverId: String) {
        execCloseHook?.invoke(serverId)
        execFlagWatchers.remove(serverId)?.cancel()
        execEnsuredServers.remove(serverId)
        execManager.close(serverId)
    }

    /** Tabs whose [disconnectSession] is running: they no longer count as a reason to keep a server's exec transport. */
    private val closingIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** One watcher per server with tabs on the server's `exec` flag ([CommFlags.effectiveFlow]); ends with the last tab. */
    private val execFlagWatchers = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    private fun watchExecFlag(serverId: String) {
        execFlagWatchers.computeIfAbsent(serverId) {
            // Read here, before the launch (K4-4): read inside the coroutine it could already be the flip of a kill
            // switch that came right after this ensure, and the watcher would then take that flip for the old value.
            val initial = CommFlags.effectiveFor(serverId).exec
            reconnectScope.launch {
                var seen = initial
                CommFlags.effectiveFlow(serverId).map { it.exec }.distinctUntilChanged().collect { on ->
                    if (on == seen) return@collect
                    seen = on
                    try {
                        onExecFlagChanged(serverId, on)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FileLogger.error(TAG, "Switching exec routing ${if (on) "on" else "off"} for $serverId failed", e)
                    }
                }
            }
        }
    }

    /**
     * The `exec` group of [serverId] changed while tabs are open (Settings, the legacy set, a server-side
     * force-off). Off: the exec transport is closed (its supervisor would otherwise keep reviving it and
     * feeding the Tailscale strike rule), its port forwards go back to a shell transport, and every tab
     * returns to the per-tab loop. On: the other way round, and the bootstrap runs again.
     */
    private suspend fun onExecFlagChanged(serverId: String, on: Boolean) {
        val tabsOfServer = tabManager.tabs.value.filter { it.server.id == serverId && it.id !in closingIds }
        if (tabsOfServer.isEmpty()) return
        if (on) {
            FileLogger.log(TAG, "exec routing is on again for $serverId — starting its exec transport")
            movePortForwards(serverId, toExec = true)
            ensureExecTransport(serverId)
            kickBootstrap(serverId)
        } else {
            FileLogger.log(TAG, "exec routing was switched off for $serverId — closing its exec transport")
            synchronized(execLock(serverId)) {
                execCloseHook?.invoke(serverId)
                execEnsuredServers.remove(serverId)
                execManager.close(serverId)
            }
            movePortForwards(serverId, toExec = false)
        }
        for (t in tabsOfServer) startReconcileLoop(t.id, t.tmuxSessionName)
    }

    /**
     * The port forwards of [serverId] ride the exec transport while it owns them (the shells skipped them at
     * connect). When it is closed they go to a live shell transport; when it comes back they leave it.
     */
    private suspend fun movePortForwards(serverId: String, toExec: Boolean) {
        val forwards = serverStorage.getServer(serverId)?.portForwards.orEmpty()
        if (forwards.isEmpty()) return
        val sessions = connectionRegistry.sshEntries()
            .filter { connectionRegistry.serverIdOf(it.key) == serverId && it.value.isConnected }
            .mapNotNull { it.value.getSession() }
            .distinct()
        for (pf in forwards) {
            if (toExec) {
                for (sess in sessions) try {
                    when (pf.type) { "L" -> sess.delPortForwardingL(pf.localPort); "R" -> sess.delPortForwardingR(pf.remotePort) }
                } catch (_: Exception) { }
            } else {
                // The exec transport is released in the background after close(): its listener keeps the port
                // until its connection is gone, so the first tries may find it bound.
                var ok = false
                for (attempt in 1..FORWARD_MOVE_TRIES) {
                    ok = sessions.any { sess ->
                        try {
                            when (pf.type) {
                                "L" -> sess.setPortForwardingL(pf.localPort, pf.remoteHost, pf.remotePort)
                                "R" -> sess.setPortForwardingR(pf.remotePort, pf.remoteHost, pf.localPort)
                            }
                            true
                        } catch (_: Exception) { false }
                    }
                    if (ok) break
                    kotlinx.coroutines.delay(FORWARD_MOVE_RETRY_MS)
                }
                FileLogger.log(TAG, "Port forward ${pf.type} ${pf.localPort} " + if (ok) "moved to a shell transport" else "could not be moved to a shell transport")
            }
        }
    }

    /** The exec group is off for [serverId] because the SERVER's `client-flags.json` says so (not this device's switches or the legacy set). */
    private fun execForcedOffByServer(serverId: String): Boolean =
        !CommFlags.effectiveFor(serverId).exec && CommFlags.resolve(CommFlags.raw(), serverId).exec

    /**
     * The bootstrap exec of [serverId] (memoized, single flight) without waiting for it. Only when something can
     * answer it: the exec transport is up or a tab of the server is connected (a transport that does not exist
     * yet would make it fail and back off for 10 s, and the connects of the wave would then find it suppressed).
     * A server whose exec group the server itself forced off is included: the bootstrap reads its flags over a
     * tab transport, so deleting the force-off file lifts it.
     */
    private fun kickBootstrap(serverId: String) {
        if (released) return
        val b = connectionRegistry.bootstrap ?: return
        if (!connectionRegistry.execActive(serverId) && !execForcedOffByServer(serverId)) return
        if (!connectionRegistry.execUp(serverId) &&
            connectionRegistry.sessionIdsOnServer(serverId).none { connectionRegistry.ssh(it)?.isConnected == true }) return
        launchGuarded("Bootstrap of $serverId") { b.ensure(serverId) }
    }

    /** One round of the 10-minute flags refresh: foreground only (nobody sees a flag change while the app sleeps; the resume re-reads). */
    internal suspend fun flagsRefreshTick() {
        if (!isInBackground) refreshServerFlags()
    }

    /**
     * Re-read `client-flags.json` of every server that has a tab and runs exec routing or has it forced off by the
     * server (a force-off is lifted by deleting the file, which only this read can notice).
     */
    internal suspend fun refreshServerFlags() {
        val b = connectionRegistry.bootstrap ?: return
        for (serverId in tabManager.tabs.value.map { it.server.id }.distinct()) {
            if (!connectionRegistry.execActive(serverId) && !execForcedOffByServer(serverId)) continue
            try { b.refreshFlags(serverId) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                FileLogger.log(TAG, "refreshFlags $serverId failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
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
     * This device's client marker for [tmuxName], the file [buildAttachCommand]'s preamble writes: the close facts
     * read it to leave our own attached client out of "someone else is watching". Same [deviceKey] as the attach, so
     * the two cannot disagree.
     */
    internal fun ownClientMarker(tmuxName: String): String =
        com.clauderemote.session.service.CloseFactsCommands.selfMarkerFor(tmuxName, deviceKey)

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
            // Through the exec layer, SHORT: one flush is a one-off command, and the shipper must not open
            // a channel outside the per-server channel budget. The server with the most tabs carries it.
            execFlush = ::shipLogChunk,
        ).also { it.start() }
    }

    /** One flush of the log shipper: a SHORT exec on the server that carries the most tabs. False = it did not arrive (the shipper keeps its buffer). */
    internal suspend fun shipLogChunk(cmd: String, bytes: ByteArray): Boolean {
        val serverId = tabManager.tabs.value.groupingBy { it.server.id }.eachCount().maxByOrNull { it.value }?.key
        return serverId != null &&
            connectionRegistry.execRun(serverId, cmd, bytes, totalMs = 10_000, kind = ExecKind.SHORT)?.exitStatus == 0
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
        // The exec transports reconnect at a slower pace in the background (nobody waits, the radio does).
        execManager.background = background
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
                //
                // The state file lives under the pane's OWN config dir, which for a
                // non-default login is ~/.claude-remote/accounts/<slug>/sessions/ —
                // `sessions` is deliberately NOT shared (see AccountService
                // .SHARED_ENTRIES), so reading ~/.claude/sessions/ found nothing for
                // those panes and the probe returned null on every call. Take
                // CLAUDE_CONFIG_DIR from the candidate pid's own environ (same user,
                // always readable; the launcher sets it as an env assignment, so it
                // never shows up in cmdline) and fall back to ~/.claude.
                //
                // Only report a UUID whose transcript already exists: claude >=
                // 2.1.220 FORKS on --resume and reports the new id before writing
                // its jsonl, so adopting eagerly would repoint the chat view at an
                // empty conversation. Same guard the drift daemon uses.
                val cmd = "PID=\$(tmux list-panes -t '=$escaped' -F '#{pane_pid}' 2>/dev/null | head -1); " +
                    "[ -n \"\$PID\" ] || exit 1; " +
                    "PIDS=\$(ps -eo pid=,ppid= 2>/dev/null | awk -v root=\"\$PID\" '" +
                    "{ kids[\$2]=kids[\$2]\" \"\$1 } " +
                    "END { head=0;tail=0;q[tail++]=root; " +
                    "while(head<tail){p=q[head++];print p;m=split(kids[p],a,\" \");" +
                    "for(i=1;i<=m;i++)if(a[i]!=\"\")q[tail++]=a[i]} }'); " +
                    "for p in \$PIDS; do " +
                    "CFG=\$(tr '\\0' '\\n' < /proc/\$p/environ 2>/dev/null | " +
                    "sed -n 's|^CLAUDE_CONFIG_DIR=||p' | head -1); " +
                    "f=\"\${CFG:-\$HOME/.claude}/sessions/\$p.json\"; " +
                    "[ -f \"\$f\" ] || continue; " +
                    "v=\$(jq -r '.sessionId // empty' \"\$f\" 2>/dev/null); " +
                    "[ -n \"\$v\" ] && { ls \"\$HOME\"/.claude/projects/*/\"\$v\".jsonl >/dev/null 2>&1 || " +
                    "ls \"\${CFG:-\$HOME/.claude}\"/projects/*/\"\$v\".jsonl >/dev/null 2>&1; } && " +
                    "echo \"\$v\"; break; done"
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
        resumeClaudeSessionId: String? = null,
        // Which server-side Claude login to launch under. null = the default
        // `~/.claude` account (no CLAUDE_CONFIG_DIR at all — see claudeConfigDirFor).
        accountSlug: String? = null
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
        val key = server.id to tmuxSessionName
        if (!isNewTmuxSession) {
            // LOW1 (review B-7): an attach of a name a launch is creating right now joins that launch (its tab), instead
            // of a second tab of the name — whose detach preamble would fight the first, and which that launch's cleanup
            // would pull the session from under if the creation then failed
            launchesInFlight[key]?.let { first ->
                FileLogger.log(TAG, "launchSession: attach of '$tmuxSessionName' on ${server.name} while it is being created — joining that launch")
                // review C1-1: when that launch fails (the name is another device's, …) the attach goes its own way below
                val tab = try {
                    first.await()
                } catch (e: Throwable) {
                    // our own cancellation goes on; that launch's failure or cancellation does not end this attach
                    if (e is kotlinx.coroutines.CancellationException && !kotlinx.coroutines.currentCoroutineContext().isActive) throw e
                    null
                }
                if (tab != null) {
                    switchTab(tab.id)
                    return@withContext tab
                }
            }
            return@withContext openTab(server, folder, mode, model, connectionType, tmuxSessionName, false, resumeClaudeSessionId, accountSlug) {}
        }
        // R6-1: a launch creating its session has no tab yet, so the guard above cannot see it — a second tap, a second
        // quick connect or Launch re-armed on the Connect screen would create a second session and a second tab. A
        // launch of the same requested name on the same server joins the one under way: it waits for that launch's tab
        // and switches to it; when that launch ends without a tab, so does this one (its error, no second session).
        val mine = kotlinx.coroutines.CompletableDeferred<ClaudeSession>()
        launchesInFlight.putIfAbsent(key, mine)?.let { first ->
            FileLogger.log(TAG, "launchSession: '$tmuxSessionName' on ${server.name} is being launched already — joining that launch")
            val tab = first.await()
            switchTab(tab.id)
            return@withContext tab
        }
        // LOW2: the claim ends with the tab (below), so a launch whose tab came between the guard above and this claim is
        // seen here and not created a second time
        tabManager.tabs.value.firstOrNull { it.server.id == server.id && it.tmuxSessionName == tmuxSessionName }?.let { existing ->
            launchesInFlight.remove(key, mine)
            mine.complete(existing)
            switchTab(existing.id)
            return@withContext existing
        }
        try {
            // LOW2 (review B-7): the claim ends when the tab exists, not after the whole attach: a launch of the name after
            // that tab was closed during its attach creates afresh instead of joining a tab that is gone
            openTab(server, folder, mode, model, connectionType, tmuxSessionName, true, resumeClaudeSessionId, accountSlug) {
                mine.complete(it)
                launchesInFlight.remove(key, mine)
            }
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            launchesInFlight.remove(key, mine)
        }
    }

    /** R6-1: launches of a NEW session under way, by (server id, requested name); see [launchSession]. */
    private val launchesInFlight =
        java.util.concurrent.ConcurrentHashMap<Pair<String, String>, kotlinx.coroutines.CompletableDeferred<ClaudeSession>>()

    /**
     * The tmux names of [serverId] that are this device's own: its open tabs and the names launches are creating right
     * now (R6-1). Quick connect names its session apart from every OTHER name, so a second quick connect during a
     * creation picks the same name and joins that launch instead of creating a second session.
     */
    fun ownTmuxNames(serverId: String): Set<String> =
        tabManager.tabs.value.filter { it.server.id == serverId }.map { it.tmuxSessionName }.toSet() +
            launchesInFlight.keys.filter { it.first == serverId }.map { it.second }

    /**
     * The tab of [launchSession]: a NEW session ([isNewTmuxSession]) is created on the server first and the tab exists
     * only once it is ours (V16); [onTab] gets the tab the moment it exists.
     */
    private suspend fun openTab(
        server: SshServer,
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel,
        connectionType: ConnectionType,
        tmuxSessionName: String,
        isNewTmuxSession: Boolean,
        resumeClaudeSessionId: String?,
        accountSlug: String?,
        onTab: (ClaudeSession) -> Unit,
    ): ClaudeSession {
        val sessionId = generateId()
        // Pre-generate a UUID for `claude --session-id <uuid>` so we can later
        // restore the conversation deterministically via `claude --resume <uuid>`.
        // Avoids a polling race against `~/.claude/projects/<encoded-cwd>/*.jsonl`.
        val claudeSessionId = resumeClaudeSessionId ?: generateUuidV4()

        // V16: a NEW session is created on the server first, over an exec channel, and the tab exists only once it is
        // ours: until then nothing can persist, push, probe, restart, rename or close a tab of a name someone else may
        // hold. A taken name moves on to the next free one; no channel at all = a readable error and no tab.
        val hold = LaunchHold()
        var tabAdded = false
        val session = try {
            val tmuxName = if (isNewTmuxSession) {
                createFreshSession(server, tmuxSessionName, folder, mode, model, claudeSessionId, accountSlug, connectionType, hold)
            } else tmuxSessionName
            // a slot the creation holds is the tab's own only while nothing else keeps that transport: with another holder
            // (a tab, another launch's creation) it goes back now, so the tab's shell takes it instead of needing one more
            yieldSharedHold(hold)
            // LOW1: never two tabs of one name — also when the name was not changed (an attach that came in while the
            // session was being created)
            run {
                tabManager.tabs.value.firstOrNull { it.server.id == server.id && it.tmuxSessionName == tmuxName }?.let { existing ->
                    // the session is that tab's now: the cleanup of this launch must not take it
                    hold.mayOwn = null
                    hold.release()
                    endLaunchPending(hold)
                    switchTab(existing.id)
                    onTab(existing)
                    return existing
                }
            }

            val parsedAlias = com.clauderemote.model.TmuxNameParser.parse(tmuxName, server.name).alias
            val created = ClaudeSession(
                id = sessionId,
                server = server,
                folder = folder,
                mode = mode,
                model = model,
                tmuxSessionName = tmuxName,
                connectionType = connectionType,
                status = SessionStatus.CONNECTING,
                alias = parsedAlias,
                claudeSessionId = claudeSessionId,
                accountSlug = accountSlug
            )

            terminalIO.initBuffer(sessionId)
            tabManager.addTab(created)
            tabAdded = true
            created
        } catch (e: Throwable) {
            if (!tabAdded) {
                // R6-2/R6-3: ended without its tab (cancelled, every answer lost) after the creation may have run: the
                // session this launch created goes again — only it, by its nonce
                withContext(kotlinx.coroutines.NonCancellable) { abandonOwnSession(server, hold) }
                hold.release()
                endLaunchPending(hold)
            }
            throw e
        }
        onTab(session)
        // the tab keeps its server's exec transport wanted from now on
        endLaunchPending(hold)
        FileLogger.log(TAG, "Launching session: ${server.name} → $folder (${connectionType.name}, ${mode.name}, ${model.name}, account=${accountSlug ?: "default"})")
        // The exec transport comes first: it owns the port forwards and the shell cap of the server.
        ensureExecTransport(server.id)

        return try {
            // V16: a created session is attached like any session of this device (single-client attach); one that is
            // gone again before the attach is rebuilt by the usual recovery, never launched fresh in the tab.
            when (connectionType) {
                ConnectionType.SSH -> connectSsh(session, isNewTmuxSession = false)
                ConnectionType.MOSH -> connectMosh(session, isNewTmuxSession = false)
            }

            serverStorage.updateServer(server.withRecentFolder(folder))
            // kolo 4 (adv N9): a shell lost during the launch is its autoReconnect's (the launch holds no lock): never
            // ACTIVE over it — that reconnect sets ACTIVE itself, or hands the tab to the background retry
            if (tabManager.getTab(sessionId)?.status == SessionStatus.DISCONNECTED) {
                FileLogger.log(TAG, "Session $sessionId lost its shell during the launch — its reconnect has it")
            } else {
                tabManager.updateTabStatus(sessionId, SessionStatus.ACTIVE)
                FileLogger.log(TAG, "Session active: $sessionId")
                statusService.updateActivity(sessionId, SessionActivity.WAITING_FOR_INPUT)
            }
            onSessionActive?.invoke(session)
            attachSessionRuntime(sessionId, session.tmuxSessionName)
            persistLaunched(session)
            session.copy(status = SessionStatus.ACTIVE)
        } catch (e: Exception) {
            FileLogger.error(TAG, "Session launch failed", e)
            tabManager.updateTabStatus(sessionId, SessionStatus.ERROR)
            throw e
        } finally {
            // the tab's own shell holds the transport now (or the connect failed): the creation's slot goes back
            hold.release()
        }
    }

    /**
     * V16: a pooled shell transport slot the creation of a new session leased because no channel without a handshake
     * existed — the transport the tab's shell (SSH, ET carrier) leases next — held until that shell has it, so the
     * transport is not drained in between and the tab pays no handshake of its own.
     */
    private inner class LaunchHold {
        /** The server whose exec transport this launch keeps wanted while it creates its session (mosh), see [launchPending]. */
        @Volatile var pendingOn: String? = null
        /**
         * R6-2/R6-3: (name, nonce) of a creation that has been sent and may have made this launch's session — cleared
         * when the server said the name is another session's or nothing was created; see [abandonOwnSession].
         */
        @Volatile var mayOwn: Pair<String, String>? = null
        @Volatile var pool: com.clauderemote.connection.ServerTransportPool? = null
        @Volatile var session: com.jcraft.jsch.Session? = null
        fun held(p: com.clauderemote.connection.ServerTransportPool, s: com.jcraft.jsch.Session) {
            pool = p
            session = s
            launchHolds.add(this)
        }
        suspend fun release() {
            val p = pool ?: return
            pool = null
            session = null
            launchHolds.remove(this)
            withContext(kotlinx.coroutines.NonCancellable) { p.release(this@LaunchHold) }
        }
    }

    /** V16: creations that hold a pooled shell transport slot right now. */
    private val launchHolds = java.util.concurrent.ConcurrentHashMap.newKeySet<LaunchHold>()

    /**
     * V16: give [hold]'s slot back once its creation is through when something else keeps the transport (another
     * launch's hold, a tab's shell): the transport is not drained and the tab's shell takes the slot instead of needing
     * a transport more (two launches at once on a cold server: 2 holds + 2 shells would pass the cap of 3). With no
     * other holder the slot stays held until the tab's shell has the transport.
     */
    private suspend fun yieldSharedHold(hold: LaunchHold) {
        val s = hold.session ?: return
        // decided and taken out under one lock: two creations through at the same moment never both see the other's
        // hold and both let go (the transport drained, both tabs then needing a handshake more)
        val shared = synchronized(launchHolds) {
            (launchHolds.any { it !== hold && it.session === s } || connectionRegistry.allSsh().any { it.getSession() === s })
                .also { if (it) launchHolds.remove(hold) }
        }
        if (shared) hold.release()
    }

    /** V16: why a new session could not be created (no channel to the server at all, the server refused) — no tab. */
    internal class FreshCreateException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

    /**
     * R6-2/R6-3: a launch that ends without its tab once a creation was sent (cancelled, every answer lost) kills the
     * session that creation made — only when the session of the name carries this launch's nonce, so another device's
     * or a user's session of that name is never touched ([ClaudeConfig.buildOwnSessionCleanupCommand]). It goes over a
     * channel without a handshake of its own: the exec transport or a counted channel ([ConnectionRegistry.execRun]),
     * else the transport the creation holds. With neither, or no answer within [FRESH_CLEANUP_MS], the session stays
     * (said in the log): the launcher lists it like any session of the server (a limit, see docs/architecture.md).
     */
    private suspend fun abandonOwnSession(server: SshServer, hold: LaunchHold) {
        val (name, nonce) = hold.mayOwn ?: return
        hold.mayOwn = null
        val cmd = ClaudeConfig.buildOwnSessionCleanupCommand(name, nonce)
        val out = kotlinx.coroutines.withTimeoutOrNull(FRESH_CLEANUP_MS) {
            try {
                connectionRegistry.execRun(server.id, cmd, totalMs = FRESH_CLEANUP_MS)?.stdout
                    ?: hold.session?.takeIf { it.isConnected }?.let { execReadWithWatchdog(it, cmd, totalMs = FRESH_CLEANUP_MS) }
            } catch (e: Exception) {
                FileLogger.log(TAG, "Launch of '$name' on ${server.name} ended without its tab: cleanup failed (${e.message})")
                null
            }
        }
        when {
            out == null -> FileLogger.log(TAG, "Launch of '$name' on ${server.name} ended without its tab: no channel to remove the session it may have created — it stays")
            ClaudeConfig.FRESH_CLEANED in out -> FileLogger.log(TAG, "Launch of '$name' on ${server.name} ended without its tab: removed the session it created")
            // LOW3: another device attached to it meanwhile: it is that device's session now
            ClaudeConfig.FRESH_CLEANUP_ATTACHED in out -> FileLogger.log(TAG, "Launch of '$name' on ${server.name} ended without its tab: the session it created has a client attached — it stays")
            // LOW4: a check cut short (tmux hung): whether the session is there is unknown — and a creation tmux carries out once it recovers
            ClaudeConfig.FRESH_CLEANUP_UNKNOWN in out -> FileLogger.log(TAG, "Launch of '$name' on ${server.name} ended without its tab: unknown whether the session it may have created is there (tmux did not answer) — it may stay")
            else -> FileLogger.log(TAG, "Launch of '$name' on ${server.name} ended without its tab: no session of this launch to remove")
        }
    }

    /**
     * V16: create the new session [requested] (or, when another session holds that name, the next free one) on
     * [server] over an exec channel and return the name it got. The creation is one atomic `new-session -d` carrying
     * this launch's nonce ([ClaudeConfig.buildFreshCreateCommand]); the session of the name is ours only when its
     * `CR_LAUNCH` is that nonce — also on a retry after a lost answer, which finds the session the lost try created
     * instead of taking a second name and leaving that one orphaned. A taken name is never touched (no kill, no attach).
     */
    private suspend fun createFreshSession(
        server: SshServer,
        requested: String,
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel,
        claudeSessionId: String,
        accountSlug: String?,
        connectionType: ConnectionType,
        hold: LaunchHold,
    ): String {
        val nonce = randomAlphaNum(24)
        val taken = mutableSetOf<String>()
        var name = requested
        repeat(FRESH_CREATE_NAMES) {
            var lost = 0
            while (true) {
                val cmd = ClaudeConfig.buildFreshCreateCommand(name, folder, mode, model, claudeSessionId, accountSlug, nonce)
                hold.mayOwn = name to nonce
                val out = try {
                    runFreshCreate(server, cmd, connectionType, hold)
                } catch (e: FreshCreateException) {
                    throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FileLogger.log(TAG, "Creating '$name' on ${server.name}: no answer (${e.message})")
                    null
                }
                when (val r = FreshCreate.parse(out, nonce)) {
                    FreshCreate.Result.Ours -> {
                        FileLogger.log(TAG, "Created tmux session '$name' on ${server.name} for a new tab")
                        return name
                    }
                    FreshCreate.Result.Taken -> {
                        hold.mayOwn = null
                        FileLogger.log(TAG, "tmux session name '$name' on ${server.name} is held by another session — left alone, taking the next free name")
                        break
                    }
                    is FreshCreate.Result.Failed -> {
                        hold.mayOwn = null   // no session of the name exists
                        throw FreshCreateException(
                            "Could not start a new session on ${server.name}: the server refused to create tmux session $name (${r.why})")
                    }
                    FreshCreate.Result.Lost -> if (++lost >= FRESH_CREATE_LOST_TRIES) throw FreshCreateException(
                        "Could not start a new session on ${server.name}: no answer from the server while creating tmux session $name")
                }
            }
            taken += name
            name = nextFreshName(server.name, folder, requested, taken)
        }
        throw FreshCreateException("Could not start a new session on ${server.name}: $FRESH_CREATE_NAMES names in a row are held by other sessions")
    }

    /**
     * One try of a creation [cmd] on a channel that costs no handshake of its own: the exec transport (one SHORT permit)
     * or a counted channel of a tab transport with room ([ConnectionRegistry.execRun]); for mosh with exec routing, the
     * exec transport the tab starts anyway ([hold] keeps it wanted until the tab exists); otherwise the pooled
     * shell transport the tab's shell leases next, held in [hold]. Null output = cut short; [FreshCreateException] = no
     * channel to the server at all.
     */
    private suspend fun runFreshCreate(
        server: SshServer,
        cmd: String,
        connectionType: ConnectionType,
        hold: LaunchHold,
    ): String? {
        connectionRegistry.execRun(server.id, cmd, totalMs = FRESH_CREATE_MS)?.let { return it.stdout }
        hold.session?.takeIf { it.isConnected }?.let { return execReadWithWatchdog(it, cmd, totalMs = FRESH_CREATE_MS) }
        if (connectionType == ConnectionType.MOSH && connectionRegistry.execActive(server.id)) {
            // mosh brings its own SSH (not the pool's): the exec transport is the one connection its tab opens anyway
            if (hold.pendingOn == null) {
                hold.pendingOn = server.id
                beginLaunchPending(server.id)
            }
            ensureExecTransport(server.id)
            val viaExec = kotlinx.coroutines.withTimeoutOrNull(FRESH_CREATE_EXEC_WAIT_MS) {
                var out: String? = null
                while (out == null) {
                    out = connectionRegistry.execRun(server.id, cmd, totalMs = FRESH_CREATE_MS)?.stdout
                    if (out == null) kotlinx.coroutines.delay(FRESH_CREATE_EXEC_POLL_MS)
                }
                out
            }
            if (viaExec != null) return viaExec
            FileLogger.log(TAG, "Creating a mosh session on ${server.name}: the exec transport did not come up — over a pooled transport")
        }
        val effective = transportResolver.resolveTransport(server)
        val pool = connectionRegistry.transportPool(server.id)
        val sess = try {
            connectionRegistry.connectGate(server.id).withPermit { pool.lease(effective, FRESH_CREATE_CONNECT_MS, hold) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw FreshCreateException("Could not start a new session on ${server.name}: cannot reach the server (${e.message})", e)
        }
        hold.held(pool, sess)
        return execReadWithWatchdog(sess, cmd, totalMs = FRESH_CREATE_MS)
    }

    /**
     * V16: the next name to try once the names in [taken] proved held — `-2`, `-3`, … of [requested] before its `-yolo` /
     * `--alias` tail, counting on from [requested]'s own number: the names before it were skipped by whoever picked it
     * (the listing, the manifest or the last scan had them), so the creation never goes back to them.
     */
    internal fun nextFreshName(serverName: String, folder: String, requested: String, taken: Set<String>): String {
        val p = com.clauderemote.model.TmuxNameParser.parse(requested, serverName)
        val tail = com.clauderemote.model.TmuxNameParser.build(serverName, "x", p.isYolo, p.alias)
            .removePrefix(com.clauderemote.model.TmuxNameParser.build(serverName, "x", false))
        val hasTail = tail.isNotEmpty() && requested.endsWith(tail)
        val stem = if (hasTail) requested.removeSuffix(tail) else requested
        val suffix = if (hasTail) tail else ""
        val baseStem = com.clauderemote.model.TmuxNameParser.build(serverName, folder, p.isYolo, p.alias).removeSuffix(tail)
        val numbered = Regex(Regex.escape(baseStem) + "-(\\d+)").matchEntire(stem)?.groupValues?.get(1)?.toIntOrNull()
        val (from, root) = if (numbered != null) numbered + 1 to baseStem else 2 to stem
        return generateSequence(from) { it + 1 }.map { "$root-$it$suffix" }.first { it !in taken }
    }

    /**
     * One-off server-side step [step] of [serverId]: when it reports "no usable transport" (false) it is tried again
     * once the exec transport is up, for at most [ONE_OFF_WAIT_MS]; with no exec transport to wait for it is dropped
     * and said so (the next launch or the reconcile repeats what matters).
     */
    private suspend fun whenExecUp(serverId: String, what: String, step: suspend () -> Boolean) {
        if (step()) return
        if (execManager.await(serverId, ONE_OFF_WAIT_MS) == null) {
            FileLogger.log(TAG, "$what for $serverId dropped: no usable transport and the exec transport did not come up")
            return
        }
        step()
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
        // "needs attention" badge (#55) that never clears. Exception: an approval a HOOK
        // reported (the pane's own claude said it, and the next hook event of the pane ends
        // it) younger than [HookApprovalPolicy.MAX_AGE_MS] stays — see [hookApprovalSurvivesSwitch].
        val previousId = tabManager.activeTabId.value
        // Everything below repaints the ONE shared emulator for [id] and points
        // the prompt detector at it, so it may only run once [id] really is the
        // active tab. A notification tap for a session the tab list doesn't hold
        // yet used to fall through here: the switch itself was dropped, but the
        // buffer replay and kickRedraw still fired, leaving the screen showing
        // one session while every keystroke went to the one that stayed active.
        // The user saw that as a broken session only an app restart could fix.
        if (!tabManager.switchTab(id)) {
            FileLogger.log(TAG, "switchTab($id) ignored — no such tab (not restored yet?)")
            return
        }
        if (previousId != null && previousId != id &&
            statusService.currentActivity(previousId) == SessionActivity.APPROVAL_NEEDED &&
            !hookApprovalSurvivesSwitch(previousId)) {
            statusService.updateActivity(previousId, SessionActivity.WAITING_FOR_INPUT)
        }
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
        notificationService.promptDetector.suppressFor(id, 2000)
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
        // C4: a tab left ACTIVE over a dead connection reconnects when it is shown (reconnectSession is single flight
        // with every other reconnect of the tab); a kick over the dead shell would do nothing
        if (!released && tabManager.getTab(sessionId)?.status == SessionStatus.ACTIVE && !hasLiveTransport(sessionId)) {
            FileLogger.log(TAG, "kickRedraw: $sessionId is ACTIVE without a live connection — reconnecting it")
            if (cols > 1 && rows > 0) terminalIO.resize(sessionId, cols, rows)
            reconnectScope.launch {
                try { reconnectSession(sessionId) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            return
        }
        tmuxProbes.kickRedraw(sessionId, cols, rows)
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
     * True once the chat for [sessionId] is known to be missing older history —
     * the first tail hit the line cap, or the in-memory cap trimmed the head.
     * The view draws a marker so a conversation that starts mid-stream doesn't
     * silently look complete.
     */
    fun transcriptTruncatedFlow(sessionId: String): kotlinx.coroutines.flow.StateFlow<Boolean> =
        transcriptService.transcriptTruncatedFlow(sessionId)

    /**
     * Lazy poller for OMC remote state (active skill, in-flight subagents).
     * Polls two small state files via SSH stat+cat every ~5 s; idle traffic
     * stays under ~50 B/s. Cached per session and cleaned up on disconnect.
     */
    fun remoteStatusFlow(sessionId: String) = statusService.remoteStatusFlow(sessionId)

    /**
     * Test seam: every connect path (`ssh`, `mosh`, `et`) calls it first with what it was asked to do — a new session
     * (`isNew`, the anchor of a launch in the tab) or an existing one, and whether a missing one may read as closed
     * elsewhere. True = the test stands in for the connect (nothing is opened). Null in production.
     */
    @Volatile internal var connectSeam: ((path: String, session: ClaudeSession, isNew: Boolean, checkClosedElsewhere: Boolean) -> Boolean)? = null

    /** Test seam (T6-1): the shell [autoReconnect] re-opens, given instead of connected. Null in production. */
    @Volatile internal var reconnectShellSeam: ((ClaudeSession) -> SshManager)? = null

    /**
     * Test seam (T6-1): [sendTmuxCommand] calls it first with what it was asked — a new session (`isNew`, the anchor of a
     * launch in the tab) or the tab's own one — and whether a missing one may read as closed elsewhere. True = the test
     * stands in for the tmux command. Null in production.
     */
    @Volatile internal var tmuxCommandSeam: ((session: ClaudeSession, isNew: Boolean, checkClosedElsewhere: Boolean) -> Boolean)? = null

    private suspend fun connectSsh(
        session: ClaudeSession,
        isNewTmuxSession: Boolean,
        checkClosedElsewhere: Boolean = false,
        forceSsh: Boolean = false,
        requireAliveAtEnd: Boolean = false,
    ) {
        // Opt-in Eternal Terminal path: a session that prefers ET runs its
        // terminal through the ET client (resumes over TCP) rather than the raw
        // SSH shell. connectEt reuses this same SSH connection as its carrier,
        // and calls back with forceSsh=true to fall back to plain SSH if the ET
        // path can't be established (avoids recursing back into connectEt).
        if (session.server.preferEternal && !forceSsh) { connectEt(session, isNewTmuxSession, requireAliveAtEnd); return }
        if (connectSeam?.invoke("ssh", session, isNewTmuxSession, checkClosedElsewhere) == true) return
        // Before the first shell of the server connects: the exec transport carries the port forwards and
        // decides the shell cap of every lease below.
        ensureExecTransport(session.server.id)
        val trace = AttachTrace().also { attachTraces[session.id] = it }
        val sshManager = SshManager(serverStorage, transportPool = connectionRegistry.transportPool(session.server.id))
        connectionRegistry.putSsh(session.id, sshManager)

        // Track last output time for burst detection
        var lastOutputTime = 0L
        var burstMode = true // Start in burst mode (tmux attach sends lots of data)

        fun emit(text: String) {
            terminalIO.append(session.id, text)
            // The first byte after the attach command closes the `Attach` line of this connect.
            attachTraces[session.id]?.firstByte(text)?.let { line ->
                attachTraces.remove(session.id)
                FileLogger.log(TAG, CommLogFormat.attach(session.id, cold = true, line.totalMs, line.phasesMs))
            }
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

            // This device's tmux attach just ended — see [onAttachExited]. Must
            // run before the burst skip below: the marker usually arrives in one
            // small chunk right before the replacement shell prompt, and a
            // burst-skipped chunk would drop the only notice we get.
            if (AttachExitDetector.sawAttachExit(text)) onAttachExited(session)

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
                    // Keyed by (server, account): this statusline belongs to THIS
                    // session, so its numbers are its login's, not the server's.
                    usageService.applyStatusline(
                        usageKeyFor(session.server.id, session.accountSlug),
                        usage,
                    )
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
        trace.phase("shell")

        // Wait for shell prompt (detect $ or # or >, max 3s)
        tmuxProbes.waitForShellPrompt(session.id, 3000)

        // Startup command
        if (session.server.startupCommand.isNotBlank()) {
            sshManager.sendInput(session.server.startupCommand + "\n")
            tmuxProbes.waitForShellPrompt(session.id, 3000)
        }
        trace.phase("prompt")

        ensureServerSetup(session.server.id, sshManager)

        // Tmux
        sendTmuxCommand(sshManager, session, isNewTmuxSession, checkClosedElsewhere, trace)
        notificationService.promptDetector.suppressFor(session.id, 3000) // suppress during tmux screen redraw

        // Apply the effective terminal dimensions — TerminalView won't fire
        // onResize when its size hasn't changed, and a session that (re)connected
        // while backgrounded has no remembered size of its own. effectiveSize()
        // falls back to the last active-view size so the pane always fills the window.
        val (cols, rows) = terminalIO.effectiveSize(session.id)
        sshManager.resize(cols, rows)
        // C4: a shell that died during the connect (its loss report is skipped while a reconnect holds the tab) must
        // not end ACTIVE: the caller's error path retries (reconnectSession: ERROR + armReconnectRetry). Kolo 3 (adv N2):
        // only for the caller holding that lock — a launch holds none, so its loss report was NOT skipped: autoReconnect
        // heals the tab, and the launch must still end persisted, not as a failed launch
        if (requireAliveAtEnd && !sshManager.isConnected) throw java.io.IOException("the shell of ${session.id} died during the connect")
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
     *    Note the un-pin line below is a WINDOW target (`-w`) and needs the
     *    trailing colon (`'=name:'`), unlike this session target — see the
     *    comment on that line.
     */
    // internal (not private) so ReconnectPolicyTest / commonTest can exercise
    // it directly via friend-module access — emits the same string either way.
    internal fun buildAttachCommand(tmuxSessionName: String): String {
        val escaped = tmuxSessionName.replace("'", "'\\''")
        return tmuxProbes.singleClientPreamble(tmuxSessionName, deviceKey) +
            // Un-pin: release any window a historical `resize-window` left in
            // `window-size manual` so it tracks the most-recently-active client
            // again (current-device-wins). We never set `manual` anymore; this
            // only heals windows pinned by older builds. `-w` makes this a
            // WINDOW target, which (unlike a session target) requires the
            // trailing colon — `-t '=name'` without it fails with "no such
            // window" and the `2>/dev/null` swallowed that silently, so this
            // heal never actually ran until the colon was added. Verified on
            // live tmux 3.5a: `set-option -w -t '=name'` (no colon) exits 1
            // and leaves `window-size manual` in place; `-t '=name:'` exits 0
            // and flips it to `latest`.
            "tmux set-option -w -t '=$escaped:' window-size latest 2>/dev/null; " +
            "tmux set-option -g history-limit 100000 2>/dev/null; " +
            "tmux attach-session -t '=$escaped'"
    }

    // Last confirmation-probe per session, for AttachExitDetector.shouldProbe.
    private val attachExitProbes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The persistence of a launched tab: app-restart record, then the server-side restore install, tombstone clear and manifest push. */
    private fun persistLaunched(session: ClaudeSession) {
        val server = session.server
        val tmuxSessionName = session.tmuxSessionName
        // Persist session for app-restart and server-reboot recovery.
        sessionStorage?.upsert(SessionStorage.fromClaudeSession(session))
        // kolo 4 (adv N9): not skipped while the tab's shell is being rebuilt (its autoReconnect): the exec path needs no
        // shell, the legacy one waits for the tab's shell a bounded time
        launchGuarded("Server-side bookkeeping of $tmuxSessionName") {
                if (connectionRegistry.execActive(server.id)) {
                    // Over the exec transport: no attached shell needed. When none is usable right now the
                    // step waits for it once instead of being dropped; the push goes out even if the
                    // tombstone could not be cleared (the failure is logged by the call).
                    whenExecUp(server.id, "restore service install") { persistence.ensureRestoreService(server.id) }
                    whenExecUp(server.id, "tombstone clear of $tmuxSessionName") { persistence.clearTombstone(server.id, tmuxSessionName) }
                    whenExecUp(server.id, "sessions.json push") { persistence.pushSessionsToServer(server.id) }
                } else {
                    val conn = awaitLiveShell(session.id, PERSIST_SHELL_WAIT_MS) ?: run {
                        FileLogger.log(TAG, "Server-side bookkeeping of $tmuxSessionName skipped: no live shell within ${PERSIST_SHELL_WAIT_MS}ms")
                        return@launchGuarded
                    }
                    persistence.ensureRestoreService(conn)
                    // Launching (or deliberately attaching to) this tmux name is
                    // positive intent that it is alive, so drop any tombstone
                    // left by an earlier close of the SAME name. Must precede
                    // the push: the push filters tombstoned names out of the
                    // manifest, so a stale tombstone would otherwise keep this
                    // brand-new session out of the restore manifest entirely.
                    persistence.clearTombstone(conn, tmuxSessionName)
                    persistence.pushSessionsToServer(conn, server.id)
                }
        }
    }

    /**
     * Kolo 6 (adv M3, H2): the ET and mosh clients left from before a reconnect stop and leave the registry — they came
     * first in the input route, so every key went into an old `et` or a dead mosh-client. (A carrier rebuild keeps its ET
     * client: it does not come here.)
     */
    /**
     * The mosh client [m] of [sessionId] ended. Kolo 6 (adv H2): the dead client leaves the registry (it was the first
     * route of every key); kolo 7 (adv N6): only the tab's current client speaks for it — one a reconnect already stopped
     * and replaced writes nothing.
     */
    internal fun onMoshClientEnded(sessionId: String, m: com.clauderemote.connection.MoshManager) {
        if (connectionRegistry.mosh(sessionId) !== m) return
        connectionRegistry.removeMosh(sessionId)
        tabManager.updateTabStatus(sessionId, SessionStatus.DISCONNECTED)
        onSessionDisconnect?.invoke(sessionId)
    }

    /** When a line went into a tab's shell (or a reconnect ended ACTIVE without one), and how long a loss after it is early. */
    private class EarlyDeathStamp(val at: Long, val windowMs: Long)

    /** Kolo 7 (adv N1): the stamp of the tab's last reconnect that ended ACTIVE, and how many losses in a row came soon after one. */
    private val reconnectedAt = java.util.concurrent.ConcurrentHashMap<String, EarlyDeathStamp>()
    private val earlyDeaths = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** R1: the stamp of the last launch/attach line [sendTmuxCommand] typed into the tab's shell — taken BEFORE it is sent. */
    private val lineSentAt = java.util.concurrent.ConcurrentHashMap<String, EarlyDeathStamp>()

    /** Test seam (R1): the clock of the early-death brake only. Null in production. */
    @Volatile internal var earlyDeathClockSeam: (() -> Long)? = null
    private fun earlyDeathNow(): Long = earlyDeathClockSeam?.invoke() ?: System.currentTimeMillis()

    /**
     * R1: a reconnect of [sessionId] that began at [attemptStart] ended ACTIVE. The brake counts from the line that
     * reconnect typed (stamped before it was sent: a refusing sockheal's 4.5–6 s run from there, not from ACTIVE some
     * 0.3 s + a round trip later), with that line's window; a reconnect that typed none (mosh, ET, a test seam) counts
     * from now with [EARLY_DEATH_MS], as before.
     */
    private fun stampReconnected(sessionId: String, attemptStart: Long) {
        val line = lineSentAt.remove(sessionId)?.takeIf { it.at >= attemptStart }
        reconnectedAt[sessionId] = line ?: EarlyDeathStamp(earlyDeathNow(), EARLY_DEATH_MS)
    }

    /**
     * Kolo 7 (adv N1): the floor of the attempts of an autoReconnect that a loss started. A loss within the window of
     * the line a reconnect that ended ACTIVE typed ([EARLY_DEATH_MS] for an attach, [LAUNCH_EARLY_DEATH_MS] for a launch
     * line, R1) is a failed attempt, not a fresh start — e.g. an attach that fails for good behind `|| exit 1` on a slow
     * link (the loss comes after ACTIVE), or a Rebuild whose `tmux-sockheal.sh` keeps refusing: without this every round
     * began at attempt 1 with no wait, for ever. Such losses in a row wait 4 s, 8 s, 16 s … (cap 60 s); a connection that
     * held longer starts the count over.
     */
    private fun earlyDeathFloorMs(sessionId: String): Long {
        val stamp = reconnectedAt.remove(sessionId)
        val since = stamp?.let { earlyDeathNow() - it.at }
        if (stamp == null || since!! >= stamp.windowMs) {
            earlyDeaths.remove(sessionId)
            return 0
        }
        val n = earlyDeaths.merge(sessionId, 1, Int::plus) ?: 1
        val floor = ReconnectPolicy.backgroundBaseDelayMs(n + 1)
        FileLogger.log(TAG, "Connection of $sessionId lost ${since}ms after its reconnect's line (early death $n in a row, window ${stamp.windowMs}ms) — waiting ${floor}ms")
        return floor
    }

    private suspend fun dropOldTerminalClients(sessionId: String) {
        connectionRegistry.removeEt(sessionId)?.let { e ->
            try { e.disconnect() } catch (ex: Exception) { FileLogger.log(TAG, "Stopping the old ET client of $sessionId failed: ${ex.message}") }
        }
        etLocalPorts.remove(sessionId)
        connectionRegistry.removeMosh(sessionId)?.let { m ->
            try { m.disconnect() } catch (ex: Exception) { FileLogger.log(TAG, "Stopping the old mosh client of $sessionId failed: ${ex.message}") }
        }
    }

    /** Kolo 4: the tab's connected SSH shell, waited for up to [ms] (null = none, or the tab is gone). */
    private suspend fun awaitLiveShell(sessionId: String, ms: Long): SshManager? {
        val end = System.currentTimeMillis() + ms
        while (true) {
            connectionRegistry.ssh(sessionId)?.takeIf { it.isConnected }?.let { return it }
            if (released || tabManager.getTab(sessionId) == null || System.currentTimeMillis() >= end) return null
            kotlinx.coroutines.delay(250)
        }
    }

    /**
     * React to this device's `tmux attach` ending.
     *
     * The case that matters: another device closed the session. Killing the pane
     * ends our attach but leaves the SSH shell alive, so no reconnect ever
     * fires and the tab lingers showing a bare shell prompt (reported from a
     * phone that still had a session the desktop had closed). The 15 s reconcile
     * loop also catches this, but only in the FOREGROUND — on a phone the app is
     * usually backgrounded at the moment of the close, so without this the user
     * still opens the app to a dead tab.
     *
     * Confirm before acting, and act only when the session is really gone:
     * [AttachExitDetector.MARKER] can occur in ordinary output, and issuing the
     * recovery command while still attached would type it into the user's live
     * Claude prompt. probeTmuxSession fails OPEN (alive), so a probe that cannot
     * answer changes nothing.
     *
     * Recovery itself is delegated to [reconnectSession], which already probes
     * the tombstone + manifest and picks the one right outcome: forget the tab
     * when the user closed it elsewhere, or rebuild with `--resume` when the
     * pane died for any other reason.
     */
    private fun onAttachExited(session: ClaudeSession) {
        val now = System.currentTimeMillis()
        if (!AttachExitDetector.shouldProbe(now, attachExitProbes[session.id])) return
        attachExitProbes[session.id] = now
        launchGuarded("Attach-exit check of ${session.tmuxSessionName}") {
            if (tabManager.getTab(session.id) == null) return@launchGuarded
            val ssh = connectionRegistry.ssh(session.id) ?: return@launchGuarded
            if (connectionRegistry.execActive(session.server.id)) {
                // The per-server reconcile decides under the tightened forget rule (tombstoned, gone from
                // `list-sessions`, another live session exists); a tab it forgets is done here.
                connectionRegistry.reconciler?.checkClosed(session.id, 0)
                if (tabManager.getTab(session.id) == null) return@launchGuarded
                if (tmuxProbes.probeTmuxSession(session.server.id, session.tmuxSessionName)) return@launchGuarded
            } else if (tmuxProbes.probeTmuxSession(ssh, session.tmuxSessionName)) return@launchGuarded
            FileLogger.log(TAG, "Attach for '${session.tmuxSessionName}' exited and the session is gone — resolving")
            reconnectSession(session.id)
        }
    }

    /**
     * Server-side setup a tab's tmux command relies on: Claude Code's Stop hook configured (hook-based idle
     * detection instead of screen-state polling) and the transcript stream daemon script in place (one shared
     * delta channel per server). With exec routing on for the server that is the per-server bootstrap: one
     * exec, memoized, so 20 tabs cost one run; the connect waits for it at most [BOOTSTRAP_WAIT_MS], which
     * keeps the order hooks -> tmux when the server answers quickly and never holds the attach hostage when
     * it does not (the bootstrap goes on in its own scope). Routing off: the per-tab calls, as before.
     */
    internal suspend fun ensureServerSetup(serverId: String, sshManager: SshManager) {
        val bootstrap = connectionRegistry.bootstrap
        if (bootstrap != null && connectionRegistry.execActive(serverId)) {
            kotlinx.coroutines.withTimeoutOrNull(BOOTSTRAP_WAIT_MS) { bootstrap.ensure(serverId) }
        } else {
            notificationService.ensureStopHook(sshManager)
            transcriptService.ensureStreamd(sshManager)
        }
    }

    private suspend fun sendTmuxCommand(
        sshManager: SshManager,
        session: ClaudeSession,
        isNew: Boolean,
        checkClosedElsewhere: Boolean = false,
        trace: AttachTrace? = null,
    ) {
        if (tmuxCommandSeam?.invoke(session, isNew, checkClosedElsewhere) == true) return
        // The decision itself lives in TmuxLaunchDecider — pure, exhaustively
        // tested (TmuxLaunchDecisionTest), and lazy: each probe below is an SSH
        // round-trip, sometimes over cellular, so it only runs on the paths that
        // need it. Everything I/O — probing, logging, sending — stays here.
        // `checkClosedElsewhere` is only trusted for the
        // reconnect-to-an-already-tracked-tab path; launchSession's
        // attach/history-resume callers pass false since their target may
        // legitimately be new to sessions.json.
        //
        // The tombstone and manifest questions are answered by ONE exec, fetched
        // at most once and only if the decider actually asks (it doesn't when
        // tmux is alive). Two separate probes would double an SSH round-trip on
        // the reconnect path — often the worst possible moment for it.
        var closeStateProbed = false
        var closeState: com.clauderemote.session.service.RemoteCloseState? = null
        val fetchCloseState: suspend () -> com.clauderemote.session.service.RemoteCloseState? = {
            if (!closeStateProbed) {
                closeStateProbed = true
                closeState = persistence.fetchCloseState(sshManager, session.tmuxSessionName)
            }
            closeState
        }
        val decision = TmuxLaunchDecider.decide(
            isNew = isNew,
            checkClosedElsewhere = checkClosedElsewhere,
            hasClaudeSessionId = session.claudeSessionId != null,
            // Routed: over the exec transport (no round trip on the tab's own shell transport, which is
            // busy with the attach); legacy: on the tab's own session, as before.
            tmuxExists = {
                if (connectionRegistry.execActive(session.server.id)) tmuxProbes.probeTmuxSession(session.server.id, session.tmuxSessionName)
                else tmuxProbes.probeTmuxSession(sshManager, session.tmuxSessionName)
            },
            // Both fail OPEN when the probe couldn't answer: not tombstoned,
            // still tracked — a network blip must never read as "closed".
            tombstoned = { fetchCloseState()?.tombstoned == true },
            stillTracked = { fetchCloseState()?.tracked ?: true },
            hasLivePeers = { serverHasOtherLiveSession(sshManager, session.tmuxSessionName) },
            hasTranscript = {
                // Only a confident YES is taken. A NO can be wrong too — the probe
                // does not see an account's own projects/ dir — and an unknown is
                // unknown, so both let the server look for the transcript itself.
                val exists = if (connectionRegistry.execActive(session.server.id)) {
                    tmuxProbes.probeTranscriptExists(session.server.id, session.folder, session.claudeSessionId!!)
                } else {
                    tmuxProbes.probeTranscriptExists(sshManager, session.folder, session.claudeSessionId!!)
                }
                if (exists == true) true else null
            },
        )

        if (decision is TmuxLaunchDecision.ForgetClosedElsewhere) {
            val why = if (closeState?.tombstoned == true) "tombstoned" else "untracked, and live peers exist"
            FileLogger.log(TAG, "Tmux '${session.tmuxSessionName}' missing, $why — closed on another device, forgetting locally")
            sessionStorage?.remove(session.id)
            disconnectSession(session.id)
            throw SessionClosedElsewhereException()
        }

        if (decision is TmuxLaunchDecision.Rebuild && decision.afterSuspectedServerOutage) {
            FileLogger.log(TAG, "Tmux '${session.tmuxSessionName}' missing + untracked but NO live peer sessions — treating as whole-server outage, rebuilding/--resume instead of forgetting")
        }

        // D2: every line this decision sends ends the login shell when it fails — a sockheal that refuses (`false`), a
        // refused or failed attach — so the loss goes the usual reconnect way and no bare bash is left under an ACTIVE
        // tab, where typed input would run as commands. Only here: buildTmuxLaunchCommand also feeds `cr-session` and the
        // detached create, which must not exit their shell. A tmux client that ends with status 0 (a detach) is not covered.
        fun orExit(cmd: String) = "{ $cmd; } || exit 1"
        val command = when (decision) {
            is TmuxLaunchDecision.ForgetClosedElsewhere -> return // unreachable: threw above
            // V14: a fresh launch never kills and never adopts a session that holds its name — the anchor refuses.
            // V16: launchSession creates new sessions over exec before the tab exists and attaches them, so this is only
            // the backstop of a launch in the tab.
            is TmuxLaunchDecision.FreshLaunch -> orExit(ClaudeConfig.buildTmuxLaunchCommand(
                tmuxSessionName = session.tmuxSessionName,
                folder = session.folder,
                mode = session.mode,
                model = session.model,
                claudeSessionId = session.claudeSessionId,
                resume = false,
                accountSlug = session.accountSlug,
                onExisting = ClaudeConfig.OnExisting.Refuse,
            ))
            // See buildAttachCommand for the attach semantics (single client per
            // device, no -d, manual window sizing, exact target).
            // kolo 6 (adv H1): a failed attach ends the login shell (the transport's loss then goes the usual reconnect
            // way) — never a bare bash left under an ACTIVE tab, where typed input would run as commands
            is TmuxLaunchDecision.Attach -> buildAttachCommand(session.tmuxSessionName) + " || exit 1"
            // A relaunch of a session that should exist: if it is there after all (a socket heal), attach it with this
            // device's single-client attach (N1, S2) — never kill it and start it again.
            is TmuxLaunchDecision.Rebuild -> when {
                !decision.withSessionId -> {
                    FileLogger.log(TAG, "Tmux '${session.tmuxSessionName}' missing and no claudeSessionId — fresh launch")
                    orExit(ClaudeConfig.buildTmuxLaunchCommand(
                        tmuxSessionName = session.tmuxSessionName,
                        folder = session.folder,
                        mode = session.mode,
                        model = session.model,
                        accountSlug = session.accountSlug,
                        onExisting = ClaudeConfig.OnExisting.Attach(buildAttachCommand(session.tmuxSessionName)),
                    ))
                }
                decision.resume != false -> {
                    val how = if (decision.resume == true) "claude --resume" else "a server-side transcript check for"
                    FileLogger.log(TAG, "Tmux '${session.tmuxSessionName}' missing — rebuilding with $how ${session.claudeSessionId}")
                    orExit(ClaudeConfig.buildTmuxLaunchCommand(
                        tmuxSessionName = session.tmuxSessionName,
                        folder = session.folder,
                        mode = session.mode,
                        model = session.model,
                        claudeSessionId = session.claudeSessionId,
                        resume = decision.resume,
                        accountSlug = session.accountSlug,
                        onExisting = ClaudeConfig.OnExisting.Attach(buildAttachCommand(session.tmuxSessionName)),
                    ))
                }
                else -> {
                    // A session launched but never interacted with has no jsonl,
                    // so `--resume` would print "No conversation found" — relaunch
                    // fresh with the SAME --session-id so later restarts resume.
                    FileLogger.log(TAG, "Tmux '${session.tmuxSessionName}' missing and no transcript for ${session.claudeSessionId} — fresh launch with same --session-id")
                    orExit(ClaudeConfig.buildTmuxLaunchCommand(
                        tmuxSessionName = session.tmuxSessionName,
                        folder = session.folder,
                        mode = session.mode,
                        model = session.model,
                        claudeSessionId = session.claudeSessionId,
                        resume = false,
                        accountSlug = session.accountSlug,
                        onExisting = ClaudeConfig.OnExisting.Attach(buildAttachCommand(session.tmuxSessionName)),
                    ))
                }
            }
        }

        // isNew launches deliberately skip the "Attaching to tmux" line, as they
        // always have.
        if (decision !is TmuxLaunchDecision.FreshLaunch) {
            FileLogger.log(TAG, "Attaching to tmux: $command")
        }
        trace?.phase("decide")
        // R1: stamped BEFORE the line goes out — a launch line's sockheal may refuse for up to SOCKHEAL_LONGEST_REFUSAL_MS
        lineSentAt[session.id] = EarlyDeathStamp(
            earlyDeathNow(),
            if (decision is TmuxLaunchDecision.Attach) EARLY_DEATH_MS else LAUNCH_EARLY_DEATH_MS,
        )
        sshManager.sendInput(command + "\n")
        trace?.attachSent(command)
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
            // `tmux -u`: without it tmux decides UTF-8 support from the CLIENT's
            // locale, and an SSH *exec* channel gets no locale of its own (Debian's
            // /etc/default/locale is LANG=C here). A non-UTF-8 client makes tmux run
            // every name it prints through utf8_sanitize(), which replaces each
            // non-ASCII character with '_' — so a session aliased "doporučené kurzy"
            // reads back as "doporu_en_-kurzy". The mangled string matches no tab and
            // no manifest entry: the scan reports it as an unknown remote session, the
            // manifest merge treats the real one as dead, and a "missing" session gets
            // relaunched under the mangled name — one live session becomes two.
            // `-u` forces UTF-8 regardless of locale, so it also works on servers with
            // no UTF-8 locale installed. Writing/addressing is NOT affected (verified
            // on tmux 3.5a: new-session -s and -t '=name' round-trip UTF-8 fine under
            // `env -i`), so only the READ paths need this.
            val out = execCapture(jsch, "tmux -u list-sessions -F '#{session_name}' 2>/dev/null")
            // Parsing lives in TmuxPeerLiveness so it is testable without SSH;
            // the exec round-trip and the fail-closed catch below stay here.
            TmuxPeerLiveness.hasOtherLivePeer(out, excludeTmuxName)
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

    internal suspend fun autoReconnect(
        session: ClaudeSession,
        emit: (String) -> Unit,
        maxAttempts: Int = 3
    ) {
        if (released) return
        synchronized(reconnectingSessionIds) {
            if (!reconnectingSessionIds.add(session.id)) {
                // C4: already reconnecting — but this loss may be of the shell that reconnect is building: endReconnect
                // looks at it when that reconnect ends (it used to be dropped here, leaving the tab ACTIVE over a dead shell)
                lostDuringReconnect[session.id] = session to emit
                FileLogger.log(TAG, "Connection of ${session.id} lost while it reconnects — checked when that ends")
                return
            }
        }
        var succeeded = false
        // C4: the app's own lines are not tmux's output: they must not close the Attach line's firstByte
        fun say(text: String) {
            attachTraces[session.id]?.ownOutput(text)
            emit(text)
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
            dropOldTerminalClients(session.id)   // kolo 6 (adv M3, H2): this reconnect builds a plain SSH terminal
            val earlyFloor = earlyDeathFloorMs(session.id)   // kolo 7 (adv N1)
            for (attempt in 1..maxAttempts) {
                say("\r\n\u001B[33mConnection lost. Reconnecting ($attempt/$maxAttempts)...\u001B[0m\r\n")
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
                // Cloudflare has no transport to fall back to, so a CF
                // connect-then-die storm can't be broken by switching paths the
                // way the Tailscale brake does. Once CF is provably flapping,
                // floor even attempt 1's zero backoff so the tight loop becomes
                // a gentle escalating retry instead of a battery-draining
                // connect→die spin. Zero until CF dies early twice in a row, so
                // a genuine single handover still reconnects instantly.
                val floor = maxOf(transportResolver.cfEarlyDeathBackoffMs(session.server), earlyFloor)
                val wait = ReconnectPolicy.foregroundDelayMs(attempt, floor)
                if (wait > 0) kotlinx.coroutines.delay(wait)
                // Tab closed during the backoff — stop reconnecting it.
                if (tabManager.getTab(session.id) == null) { _reconnectStatus.update { it - session.id }; return }

                val attemptStart = earlyDeathNow()   // R1
                try {
                    // Clean up old connection
                    connectionRegistry.ssh(session.id)?.disconnect()
                    connectionRegistry.removeSsh(session.id)

                    // exec transport first (idempotent), then the shell
                    ensureExecTransport(session.server.id)
                    awaitExecForWave(session.server.id)
                    val trace = AttachTrace().also { attachTraces[session.id] = it }
                    val shellSeam = reconnectShellSeam
                    val sshManager = shellSeam?.invoke(session)
                        ?: SshManager(serverStorage, transportPool = connectionRegistry.transportPool(session.server.id))
                    connectionRegistry.putSsh(session.id, sshManager)

                    val reEffective = transportResolver.resolveTransport(session.server)
                    transportResolver.setConnectionLabel(session.id, reEffective, session.server, "SSH")
                    try {
                        // Gate the handshake per server: a blip drops all
                        // sessions at once and each fires attempt-1 with zero
                        // backoff — without the gate that's a 21-way concurrent
                        // KEX storm on a just-recovered weak link.
                        if (shellSeam == null) connectionRegistry.connectGate(session.server.id).withPermit {
                            sshManager.connect(
                                reEffective,
                                onOutput = { data -> emit(data) },
                                onConnectionLost = {
                                    transportResolver.maybeCountTsEarlyDeath(session)
                                    transportResolver.maybeCountCfEarlyDeath(session)
                                    tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
                                    // Activity too, matching the first-connect
                                    // handler. The watch skips any transition
                                    // touching DISCONNECTED; without this the
                                    // session stayed WORKING across the drop and
                                    // the reconnect read as WORKING -> ready,
                                    // buzzing the wrist with the pre-drop answer.
                                    statusService.updateActivity(session.id, SessionActivity.DISCONNECTED)
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
                    trace.phase("shell")

                    // Wait for shell prompt, clear garbage, then attach tmux
                    tmuxProbes.waitForShellPrompt(session.id, 3000)
                    sshManager.sendInput("\u0003\n") // Ctrl-C + Enter to clear
                    kotlinx.coroutines.delay(100)
                    trace.phase("prompt")
                    sendTmuxCommand(sshManager, session, false, checkClosedElsewhere = true, trace = trace)
                    notificationService.promptDetector.suppressFor(session.id, 3000) // suppress during tmux screen redraw after reconnect

                    // Re-send terminal dimensions — the TerminalView hasn't
                    // changed size so onResize won't fire. A session reconnecting
                    // while backgrounded has no remembered size of its own, so use
                    // effectiveSize() (last active-view size fallback) to always
                    // apply a correct size and avoid tmux rendering at 80x24.
                    run {
                        kotlinx.coroutines.delay(200) // let tmux attach settle
                        // kolo 4 (adv N4): the size after the pause — a rotation during it must win
                        val (cols, rows) = terminalIO.effectiveSize(session.id)
                        sshManager.resize(cols, rows)
                    }
                    // C4: the shell can die during the attempt (its own loss report is skipped above while this runs,
                    // and the attach then went nowhere): never ACTIVE over it — the next attempt instead
                    if (!sshManager.isConnected) throw java.io.IOException("the shell died during the reconnect")

                    tabManager.updateTabStatus(session.id, SessionStatus.ACTIVE)
                    // IDLE, not WAITING_FOR_INPUT: reconnecting clears "Offline"
                    // but proves nothing about whether Claude finished a turn.
                    // Asserting a completion here is what made the watch notify
                    // on every reconnect with a cached answer up to 30 h old.
                    // The statusline parse in emit() and the Stop hook set the
                    // real state as soon as there is evidence for one.
                    statusService.updateActivity(session.id, SessionActivity.IDLE)
                    onSessionActive?.invoke(session)
                    // Restart ALL per-session loops, not just watcher+refresh —
                    // usage/git/latency pollers may have died during the outage.
                    attachSessionRuntime(session.id, session.tmuxSessionName)
                    stampReconnected(session.id, attemptStart)   // kolo 7 (adv N1), R1
                    say("\r\n\u001B[32mReconnected!\u001B[0m\r\n")
                    FileLogger.log(TAG, "Auto-reconnect succeeded for ${session.id}")
                    _reconnectStatus.update { it - session.id }
                    succeeded = true
                    return
                } catch (e: SessionClosedElsewhereException) {
                    FileLogger.log(TAG, "Auto-reconnect for ${session.id} aborted — closed on another device")
                    _reconnectStatus.update { it - session.id }
                    return
                } catch (e: Exception) {
                    FileLogger.error(TAG, "Auto-reconnect attempt $attempt failed", e)
                }
            }

            say("\r\n\u001B[31mReconnect failed after $maxAttempts attempts.\u001B[0m\r\n")
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
            endReconnect(session.id, succeeded)
        }
    }

    private suspend fun connectMosh(session: ClaudeSession, isNewTmuxSession: Boolean, requireAliveAtEnd: Boolean = false) {
        if (connectSeam?.invoke("mosh", session, isNewTmuxSession, false) == true) return
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
            connectSsh(session, isNewTmuxSession, requireAliveAtEnd = requireAliveAtEnd)
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
                model = session.model,
                accountSlug = session.accountSlug,
                onExisting = ClaudeConfig.OnExisting.Refuse,
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
                resume = true,
                accountSlug = session.accountSlug,
                onExisting = ClaudeConfig.OnExisting.Attach(buildAttachCommand(session.tmuxSessionName)),
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

            // This device's tmux attach just ended — see [onAttachExited]. Must
            // run before the burst skip below: the marker usually arrives in one
            // small chunk right before the replacement shell prompt, and a
            // burst-skipped chunk would drop the only notice we get.
            if (AttachExitDetector.sawAttachExit(text)) onAttachExited(session)
        }

        val success = moshManager.connect(
            effectiveServer,
            startupCommand = tmuxCmd,
            onOutput = { data -> emit(data) },
            onDisconnect = { onMoshClientEnded(session.id, moshManager) }
        )

        if (!success) {
            emit("\r\n\u001B[33mMosh connection failed. Falling back to SSH...\u001B[0m\r\n")
            FileLogger.log(TAG, "Mosh failed, falling back to SSH")
            connectSsh(session, isNewTmuxSession, requireAliveAtEnd = requireAliveAtEnd)
            return
        }

        // Store mosh as connection (wrap in a pseudo SshManager interface won't work,
        // so store separately and handle sendInput/sendBytes via mosh)
        if (!adoptMosh(session.id, moshManager)) return
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
    private suspend fun connectEt(session: ClaudeSession, isNewTmuxSession: Boolean, requireAliveAtEnd: Boolean = false) {
        if (connectSeam?.invoke("et", session, isNewTmuxSession, false) == true) return
        val effective = transportResolver.resolveTransport(session.server)
        FileLogger.log(TAG, "Connecting via Eternal Terminal to ${session.server.name} (${effective.host})")

        fun emit(text: String) {
            terminalIO.append(session.id, text)
            if (tabManager.activeTabId.value == session.id) onTerminalOutput?.invoke(session.id, text)
            notificationService.promptDetector.onOutput(session.id, text)

            // This device's tmux attach just ended — see [onAttachExited]. Must
            // run before the burst skip below: the marker usually arrives in one
            // small chunk right before the replacement shell prompt, and a
            // burst-skipped chunk would drop the only notice we get.
            if (AttachExitDetector.sawAttachExit(text)) onAttachExited(session)
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
        connectionRegistry.putSsh(session.id, sshManager, etCarrier = true)   // kolo 4: never the terminal
        transportResolver.setConnectionLabel(session.id, effective, session.server, "ET")
        connectionRegistry.connectGate(session.server.id).withPermit {
            sshManager.connect(
                effective,
                onOutput = { },
                onConnectionLost = {
                    // Outside the carrierReady gate: the link is down either
                    // way, and the watch skips any transition touching
                    // DISCONNECTED. Leaving the old activity in place for a drop
                    // during bootstrap is what let the reconnect read as
                    // WORKING -> ready and buzz the wrist with a cached answer.
                    statusService.updateActivity(session.id, SessionActivity.DISCONNECTED)
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
            // R6: wrapped like the SSH FreshLaunch (D2), though no caller reaches it today (launchSession creates new
            // sessions over exec and connects with isNewTmuxSession = false): a refused launch never leaves a bare shell
            "{ " + ClaudeConfig.buildTmuxLaunchCommand(
                tmuxSessionName = session.tmuxSessionName,
                folder = session.folder,
                mode = session.mode,
                model = session.model,
                accountSlug = session.accountSlug,
                onExisting = ClaudeConfig.OnExisting.Refuse,
            ) + "; } || exit 1"
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
                resume = true,
                accountSlug = session.accountSlug,
                onExisting = ClaudeConfig.OnExisting.Attach(buildAttachCommand(session.tmuxSessionName)),
            )
            // D2: a relaunch that fails too (its sockheal refused: `false`) ends the login shell ET typed this into —
            // never a bare shell under the ET tab; the et client's exit then takes the onDisconnect reconnect below
            "${buildAttachCommand(session.tmuxSessionName)} 2>/dev/null || { $relaunch ; } || exit 1"
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
        if (!adoptEt(session.id, etManager)) return
        // Bootstrap done: the et client + forwarded port now exist, so a carrier
        // drop from here on is safe to resume via reconnectEtCarrier.
        carrierReady.set(true)
        FileLogger.log(TAG, "Eternal Terminal connected for ${session.id} (localPort=$localPort)")
        } catch (e: Exception) {
            FileLogger.error(TAG, "Eternal Terminal path failed — falling back to SSH", e)
            connectionRegistry.removeEt(session.id)?.disconnect()
            connectionRegistry.ssh(session.id)?.disconnect()
            connectionRegistry.removeSsh(session.id)
            connectSsh(session, isNewTmuxSession, forceSsh = true, requireAliveAtEnd = requireAliveAtEnd)
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
    internal suspend fun reconnectEtCarrier(session: ClaudeSession, emit: (String) -> Unit) {
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
        var succeeded = false
        fun handOffToAutoReconnect() {
            _reconnectStatus.update { it - session.id }
            handedOff = true
            // kolo 3 (adv N5): through endReconnect, so a loss recorded meanwhile is taken too (this full reconnect covers it)
            endReconnect(session.id, succeeded = false)
            // kolo 5 (adv N4): the full reconnect builds a plain SSH terminal — the old ET client must go with it, or every
            // key would still go into that zombie process
            val oldEt = connectionRegistry.removeEt(session.id)
            etLocalPorts.remove(session.id)
            reconnectScope.launch {
                try { oldEt?.disconnect() } catch (e: Exception) { FileLogger.log(TAG, "Stopping the old ET client of ${session.id} failed: ${e.message}") }
                autoReconnect(session, emit)
            }
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
                    connectionRegistry.putSsh(session.id, sshManager, etCarrier = true)   // kolo 4: never the terminal
                    val effective = transportResolver.resolveTransport(session.server)
                    transportResolver.setConnectionLabel(session.id, effective, session.server, "ET")
                    connectionRegistry.connectGate(session.server.id).withPermit {
                        sshManager.connect(
                            effective,
                            onOutput = { },
                            onConnectionLost = {
                                tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
                                statusService.updateActivity(session.id, SessionActivity.DISCONNECTED)
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
                    // Neutral, not a claimed completion — see autoReconnect.
                    statusService.updateActivity(session.id, SessionActivity.IDLE)
                    onSessionActive?.invoke(session)
                    attachSessionRuntime(session.id, session.tmuxSessionName)
                    _reconnectStatus.update { it - session.id }
                    FileLogger.log(TAG, "ET carrier rebuilt for ${session.id} (localPort=$localPort) — et resuming, no redraw")
                    succeeded = true
                    return
                } catch (e: Exception) {
                    FileLogger.error(TAG, "ET carrier reconnect attempt $attempt failed", e)
                }
            }
            // Carrier couldn't be re-established — fall back to a full reconnect.
            handOffToAutoReconnect()
        } finally {
            // kolo 3 (adv N5): a loss of the ET client recorded during the rebuild is looked at, never left lying
            if (!handedOff) endReconnect(session.id, succeeded)
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
                try {
                    // D1: ChannelOpen (floor, detached open, CLOSE of a late confirmation) — a bare ch.connect(t) that
                    // times out leaks an sshd slot (JSch 0.2.21 sends no CLOSE for it)
                    com.clauderemote.connection.ChannelOpen.connect(ch, 10_000)
                    kotlinx.coroutines.runInterruptible { input.bufferedReader().readText() }
                } finally {
                    com.clauderemote.connection.ChannelOpen.disconnect(ch)
                }
            }
        }

    // Server-side etserver auto-install. Downloads the static etserver +
    // etterminal (built from the same ET pin as the client, so protocol-
    // compatible) from the GitHub release into ~/.claude-remote and runs
    // etserver as a plain user process — no root, no package manager, no server
    // config. Idempotent via a version marker; reuses a running instance.
    // Prints "ET_READY port=<P>" on success, or an ET_* marker to fall back.
    internal val ETSERVER_INSTALL = """
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
        # No etterminal reaper here (R2). One used to kill every etterminal older
        # than 30 s as a bootstrap helper wedged on the fifo — but after a
        # bootstrap etterminal daemonizes with the SAME cmdline and lives on as
        # the host of that ET session's shell, so each new ET connect killed the
        # other tabs' ET sessions. A wedged helper cannot outlive its bootstrap:
        # that runs under `timeout 8` with a read deadline (connectEt), the fix
        # the reaper was added next to (c063024).
        # No flock reaper here (D1). One used to kill EVERY flock process of the
        # user older than 30 s, meant for the waiters of the old etserver.lock
        # deadlock — and with them other sessions' `( flock -x 9; … ) 9<>LOCK`
        # waiters (their body then ran WITHOUT the lock) and the integration lock.
        # The lock below is etserver.start.lock with a bounded `-w 5`: no waiter
        # of it can get stuck, and old waiters on etserver.lock block nothing here.
        # Single-flight across concurrent SSH execs. Two sessions' first ET
        # connect used to each run this and each start an etserver on a bumped
        # port, both sharing one serverfifo → et.sock races away and every
        # bootstrap fails ("No such file or directory"). flock serialises the
        # check-and-start so exactly one instance is ever brought up. `-w 5`:
        # bounded wait — NEVER block forever (the acute deadlock symptom). On
        # timeout emit a marker and bail cleanly (→ SSH fallback), and because the
        # flock process exits at 5s it can't accumulate as a stuck waiter.
        # etserver.start.lock, not etserver.lock: builds up to 1.0.553 left a
        # background subshell holding etserver.lock for the daemon's whole life
        # (see the start below), so a server that ran them keeps that lock taken
        # until the next reboot. A fresh name makes this build work there at once.
        exec 9>"${'$'}CR/etserver.start.lock"
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
          # stdin detached and the lock fd 9 CLOSED. Nothing that outlives this
          # script may keep fd 9, or it holds the flock and every later
          # ensureEtServer times out (ET_LOCK_TIMEOUT → SSH fallback).
          # The redirections sit on the backgrounded { } group, not on the two
          # commands: `A 9>&- || B 9>&- &` forks a subshell for the whole list,
          # and that subshell kept fd 9 while it waited on etserver — for the
          # daemon's entire life. On a backgrounded group the redirections run
          # inside the forked subshell itself, so it closes fd 9 too.
          # systemd-run --scope execs etserver in place, so the `||` fallback to
          # `setsid nohup` only fires when systemd-run itself is unavailable.
          {
            systemd-run --user --scope --quiet "${'$'}CR/etserver" --port "${'$'}PORT" --serverfifo "${'$'}CR/et.sock" ||
              setsid nohup "${'$'}CR/etserver" --port "${'$'}PORT" --serverfifo "${'$'}CR/et.sock"
          } </dev/null >"${'$'}CR/etserver.log" 2>&1 9>&- &
          echo "${'$'}PORT" > "${'$'}CR/etserver.port"
          # Wait for the serverfifo socket (up to ~6s) instead of a blind `sleep 1`
          # — the et.sock race caused "No such file or directory" bootstraps.
          for _ in ${'$'}(seq 1 30); do [ -S "${'$'}CR/et.sock" ] && break; sleep 0.2; done
        fi
        echo "ET_READY port=${'$'}PORT"
    """.trimIndent()

    /** Ensure etserver is installed + running on the server; return its port
     *  (null → unsupported arch / no internet / start failed → SSH fallback). */
    /** Test seam (kolo 4): stands in for [ensureEtServer] (its install script must never run in a test). Null in production. */
    @Volatile internal var etServerSeam: ((com.jcraft.jsch.Session) -> Int?)? = null

    /** The exec command [ensureEtServer] sends: [ETSERVER_INSTALL] under a server-side `timeout 12` (see there). */
    internal val etServerInstallCommand: String
        get() = "timeout 12 sh -c '" + ETSERVER_INSTALL.replace("'", "'\\''") + "'"

    private suspend fun ensureEtServer(jsch: com.jcraft.jsch.Session): Int? {
        etServerSeam?.let { return it(jsch) }
        // Wrap the whole install in a server-side `timeout 12`: execCapture's
        // 15s client-side withTimeout + runInterruptible unblocks OUR read, but a
        // wedged remote run (e.g. a still-blocked flock) could otherwise leave a
        // forever-bash on the server. timeout 12 < the 15s client cap guarantees
        // the server-side process is reaped BEFORE we give up, so nothing leaks.
        // Single-quote-wrap for the outer login shell so the script reaches
        // `sh -c` verbatim (its own `$…` expand in the inner sh, not out here).
        val out = execCapture(jsch, etServerInstallCommand)
        val port = Regex("ET_READY port=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        if (port == null) FileLogger.log(TAG, "etserver not ready: ${out.trim().takeLast(200)}")
        return port
    }

    fun clearBuffer(sessionId: String) = terminalIO.clearBuffer(sessionId)

    fun sendInput(sessionId: String, data: String) = claudeControl.sendInput(sessionId, data)

    fun sendBytes(sessionId: String, data: ByteArray) = claudeControl.sendBytes(sessionId, data)


    fun resize(sessionId: String, cols: Int, rows: Int) = terminalIO.resize(sessionId, cols, rows)

    fun sendClaudeCommand(sessionId: String, command: String) { claudeControl.sendClaudeCommand(sessionId, command) }

    /** Kolo 6: one message of the input bar for [sessionId] (fixed by the caller at the send), see ClaudeControlService. */
    fun sendMessage(sessionId: String, text: String) = claudeControl.sendMessage(sessionId, text)

    /** Kolo 6: the terminal emulator's own answers (DA, CPR …) — not user input, see ClaudeControlService. */
    fun sendTerminalReply(sessionId: String, data: ByteArray) = claudeControl.sendTerminalReply(sessionId, data)

    /** Every session's newest prompt with its text and age (Velín inbox); the reply check reads the same state. */
    internal val shownPrompts: kotlinx.coroutines.flow.StateFlow<Map<String, com.clauderemote.session.service.PromptTokens.Shown>>
        get() = statusService.promptTokens.shown

    /** Sessions whose Claude exited (hook `end`, no event since), with when (device ms). */
    val claudeEndedAt: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> get() = statusService.claudeEndedAt

    /** When [sessionId]'s activity last changed (device ms), or null. */
    fun activitySinceMs(sessionId: String): Long? = statusService.activitySinceMs(sessionId)

    /** The prompt the session shows right now, for a view that offers a reply to it (the test notification). */
    fun currentPrompt(sessionId: String): com.clauderemote.session.service.PromptFingerprint? =
        com.clauderemote.session.service.ReplyGuard.promptShown(
            statusService.promptTokens.current(sessionId)?.prompt, statusService.currentActivity(sessionId),
        )

    /**
     * Send a reply given outside the app (notification, watch) for [notified] — [text] and Enter
     * — but only while the session still shows that prompt: first against the app's state, then
     * against the server's hook event log in the same shell that types the keys (ReplyGuard).
     * Returns null when sent, else why nothing was sent. Never queues for a reconnect.
     */
    suspend fun sendReply(
        sessionId: String,
        notified: com.clauderemote.session.service.PromptFingerprint?,
        reply: com.clauderemote.session.service.ReplyGuard.Reply,
        text: String,
    ): com.clauderemote.session.service.RefuseReason? {
        val verdict = com.clauderemote.session.service.ReplyGuard.check(
            notified,
            statusService.promptTokens.current(sessionId),
            statusService.currentActivity(sessionId),
            claudeControl.canDeliverNow(sessionId),
            reply,
        )
        val why = when (verdict) {
            is com.clauderemote.session.service.ReplyGuard.Verdict.Refuse -> {
                FileLogger.log(TAG, "Reply $reply to $sessionId refused (${notified?.key}): ${verdict.detail}")
                verdict.reason
            }
            is com.clauderemote.session.service.ReplyGuard.Verdict.Send -> {
                // An instruction names no prompt, so there is nothing to answer twice.
                val key = notified?.takeIf { it.kind != com.clauderemote.session.service.PromptKind.INSTRUCTION }?.key
                if (key != null && !replyLedger.begin(key)) {
                    FileLogger.log(TAG, "Reply $reply to $sessionId refused: $key already answered or in flight")
                    return com.clauderemote.session.service.RefuseReason.ALREADY_ANSWERED
                }
                var outcome: com.clauderemote.session.service.RefuseReason? = com.clauderemote.session.service.RefuseReason.NOT_VERIFIABLE
                try {
                    outcome = claudeControl.sendCheckedReply(
                        sessionId, verdict.expect, text, approve = reply == com.clauderemote.session.service.ReplyGuard.Reply.APPROVE,
                    )
                } finally {
                    if (key != null) replyLedger.finish(key, outcome)
                }
                outcome?.also {
                    FileLogger.log(TAG, "Reply $reply to $sessionId refused by the server check (${notified?.key}, ${verdict.expect}): $it")
                }
            }
        }
        if (why == null) FileLogger.log(TAG, "Reply $reply to $sessionId sent (${notified?.key}): ${text.length} chars")
        // Answering a session whose close is still in its undo window means keeping it ("Vrátit"), not answering it
        // and having it killed seconds later. Only once the keys reached the pane: a refused answer (another
        // prompt by now, nothing to verify against) must not bring back a session the user closed.
        if (replyReachedThePane(why)) reclaimPendingClose(sessionId)
        return why
    }

    // One answer per prompt from outside the app (ReplyLedger).
    private val replyLedger = com.clauderemote.session.service.ReplyLedger()

    /** The tab title of [sessionId], for a notification about it. */
    fun tabTitle(sessionId: String): String? = tabManager.getTab(sessionId)?.tabTitle

    /** [sendReply] for a caller without a coroutine (a BroadcastReceiver, a listener service). */
    fun sendReplyAsync(
        sessionId: String,
        notified: com.clauderemote.session.service.PromptFingerprint?,
        reply: com.clauderemote.session.service.ReplyGuard.Reply,
        text: String,
        onResult: (com.clauderemote.session.service.RefuseReason?) -> Unit,
    ) {
        reconnectScope.launch {
            val why = try {
                sendReply(sessionId, notified, reply, text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.error(TAG, "Reply to $sessionId failed: ${e.message}", e)
                com.clauderemote.session.service.RefuseReason.NOT_VERIFIABLE
            }
            onResult(why)
        }
    }

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
    suspend fun reconnectSession(sessionId: String): Unit = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // C5 kolo 4 (adv N3): off the caller's thread — the Reconnect button calls it from the UI's (main) scope, and the
        // connect, the probes and the disconnects write to JSch
        reconnectSessionOnIo(sessionId)
    }

    private suspend fun reconnectSessionOnIo(sessionId: String) {
        // Single-flight per session across EVERY entry point (manual button,
        // restoreAndReconnect's cold-start loop, the network-callback sweep,
        // AND autoReconnect) — shares the same guard autoReconnect uses. Cold
        // start fires restoreAndReconnect (onCreate) and reconnectDeadTabs
        // (onResume, moments later) concurrently; without this, two calls for
        // the SAME id raced on the same SshManager/registry entry — one call's
        // disconnect()/putSsh() stepping on the other's in-flight connect —
        // producing spurious "ET carrier SSH not connected" failures that only
        // a manual retry (landing after the race window) would clear.
        if (released) return
        // the first connect of a parked tab ends its parking: from now on it is a tab like any other
        if (sessionId in _parkedTabs.value) _parkedTabs.update { it - sessionId }
        synchronized(reconnectingSessionIds) {
            if (!reconnectingSessionIds.add(sessionId)) {
                FileLogger.log(TAG, "Skipping reconnectSession for $sessionId — already reconnecting")
                return
            }
        }
        var succeeded = false
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
            ensureExecTransport(session.server.id)
            awaitExecForWave(session.server.id)
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
            dropOldTerminalClients(sessionId)   // kolo 6 (adv M3, H2)

            tabManager.updateTabStatus(sessionId, SessionStatus.CONNECTING)

            val attemptStart = earlyDeathNow()   // R1
            try {
                // Keep the session's chosen connection type. reconnectSession used
                // to hardcode SSH, so a MOSH session silently degraded to plain
                // SSH after its first drop and never got its roaming resilience
                // back — the exact scenario mosh exists for. connectMosh still
                // falls back to SSH internally when no direct-UDP path exists.
                if (session.connectionType == ConnectionType.MOSH) {
                    connectMosh(session, false, requireAliveAtEnd = true)
                } else {
                    // kolo 3: this reconnect holds the tab's lock (its shell's loss report is skipped): check the end
                    connectSsh(session, false, checkClosedElsewhere = true, requireAliveAtEnd = true) // attach to existing tmux
                }
                tabManager.updateTabStatus(sessionId, SessionStatus.ACTIVE)
                // Clear the DISCONNECTED activity left over from restore/disconnect —
                // otherwise the session shows "Offline" (badge + status + empty
                // chips) even though it's connected. IDLE says exactly that much:
                // connected, nothing heard yet. The real working/idle state is
                // driven by the statusline parse in emit() as soon as output
                // flows, and by the Stop hook at end of turn.
                statusService.updateActivity(sessionId, SessionActivity.IDLE)
                onSessionActive?.invoke(session)
                // Restart ALL per-session loops (usage/git/latency pollers included
                // — they may have died during the outage), not just watcher+refresh.
                attachSessionRuntime(sessionId, session.tmuxSessionName)
                FileLogger.log(TAG, "Reconnected: $sessionId")
                stampReconnected(sessionId, attemptStart)   // kolo 7 (adv N1), R1
                succeeded = true
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
            endReconnect(sessionId, succeeded)
        }
    }

    /**
     * Forget a session permanently (used when user explicitly closes a tab,
     * not just disconnects). Removes the persisted record so it won't be
     * resurrected on next app start, and re-syncs the server-side
     * sessions.json so systemd doesn't try to restore it after reboot.
     *
     * [userInitiated] separates a real user close from the remote-scan prune
     * guessing a pane is gone: only the former earns a DURABLE tombstone, which
     * tells other devices about the close even days later. A wrong guess must
     * not be able to durably kill a session on every device.
     */
    suspend fun forgetSession(sessionId: String, userInitiated: Boolean = false) =
        persistence.forgetSession(sessionId, userInitiated)

    /**
     * The user's tab closes, held for the undo window before [forgetSession] runs (Jarvis F0a). Lives here, not in
     * the UI, because the platform has to reach it when the user leaves the app (Android `onStop`, desktop window
     * close) to run the pending closes at once. Its timers run in [reconnectScope]: when [shutdown] cancels that
     * scope the pending closes simply never happen, the same outcome as the process dying in the window.
     */
    val pendingCloses = PendingCloses(
        reconnectScope,
        // join(): the job behind it is the server side (tombstone, kill); flush() waits on it before an exit.
        forget = { id ->
            // closed here = never adopt the marker this name had (a later open or restore of it writes a new one)
            tabManager.getTab(id)?.let { suppressAdoption(it.server.id, it.tmuxSessionName) }
            persistence.forgetSession(id, userInitiated = true)?.join()
        },
        // The server's close facts (R6 = 6d), read the way every read-only command is: exec transport first, a
        // live tab of the server otherwise, null (= close) when neither is there.
        closeCheck = com.clauderemote.session.service.CloseFactsCheck(
            read = { serverId, cmd, totalMs -> connectionRegistry.execRead(serverId, cmd, totalMs) },
            selfMarker = ::ownClientMarker,
        )::check,
    )

    /**
     * The user asked for [sessionId] from outside the tab bar (a notification tap, Velín, History, an answer that
     * reached the pane — [sendReply] calls it):
     * if its close is still waiting out the undo window, that is "Vrátit". They want this session, and a tab hidden by
     * a pending close would otherwise be switched straight away from by App's hidden-tab guard, then killed. Not done
     * in [switchTab]: that also runs after a close went through, onto whatever tab [TabManager.removeTab] picked,
     * which may be another pending one. True when a close was undone.
     */
    internal fun replyReachedThePane(why: com.clauderemote.session.service.RefuseReason?): Boolean =
        why == null || why == com.clauderemote.session.service.RefuseReason.MAYBE_SENT ||
            why == com.clauderemote.session.service.RefuseReason.TYPED_NOT_SENT

    fun reclaimPendingClose(sessionId: String): Boolean {
        val undone = pendingCloses.undo(sessionId) ?: return false
        FileLogger.log(TAG, "Close of ${undone.sessionId} undone: the session was opened again within its undo window")
        return true
    }

    suspend fun renameTmuxSession(sessionId: String, oldName: String, newName: String) =
        persistence.renameTmuxSession(sessionId, oldName, newName)

    /**
     * Rehydrate persisted sessions on app start. Returns the list of
     * ClaudeSessions that were restored into [tabManager] (status =
     * CONNECTING). Caller triggers reconnectSession() for each.
     */
    fun restorePersistedTabs(): List<ClaudeSession> = persistence.restorePersistedTabs()

    // ── Nedávno zavřené, obnova a adopce opened.d (Jarvis F0b/F0c, §3.9, §15.4, §15.13) ─────────────────────

    /** This device's `by` in close records (`app:<installId>`): the rows it closed itself read "zavřeno tady". */
    internal val ownCloseBy: String get() = "app:$deviceKey"

    // ── Jarvis F3: alerts, lease, CLI ─────────────────────────────────────────────────────────────────────────

    /** Jarvis' alerts and heartbeat lease, read off the notify watchers' tails. */
    val jarvisAlerts: com.clauderemote.session.service.JarvisAlertHub get() = notificationService.jarvis

    /** This installation's id, as `cr-mail approve --by app:<id>` records who confirmed a proposal. */
    internal val installId: String get() = deviceKey

    /**
     * The server Jarvis' CLI runs on: the one whose heartbeat is newest, else the server of a Jarvis tab, else the one
     * a scan saw him on. Null = no server known to have Jarvis.
     */
    internal fun jarvisServer(tabs: List<ClaudeSession>, remotes: List<RemoteSession>): SshServer? {
        val name = QuickAccess.JARVIS_TMUX_NAME
        val servers = (tabs.map { it.server } + remotes.map { it.server }).associateBy { it.id }
        jarvisAlerts.state.value.serverId?.let { id -> servers[id]?.let { return it } }
        return tabs.firstOrNull { it.tmuxSessionName == name }?.server
            ?: remotes.firstOrNull { it.tmuxSession.name == name }?.server
    }

    /**
     * One short exec of a Jarvis CLI command ([com.clauderemote.session.service.JarvisData]) on [server]: the same
     * path as the closed-records read (whatever transport the server has, a fresh one only when none). Null = no
     * answer; the caller says "nejde načíst", never "nic".
     */
    internal suspend fun jarvisRun(server: SshServer, cmd: String, totalMs: Long = 10_000, periodic: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            try {
                // [periodic]: a recurring read (the watch's Čeká tick) takes a POLL permit, never one of the interactive
                // SHORT ones, and is skipped when none is free; nor does it pay a fresh handshake (review L8).
                SshSessionHelper.withSession(server, 5_000, allowFresh = !periodic, periodic = periodic) { sess -> execReadWithWatchdog(sess, cmd, totalMs = totalMs) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.log(TAG, "Jarvis CLI on ${server.name} failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

    // ── Jarvis on the watch (2026-11-08): the wrist's Čeká and Jarvis pages, through the Velín's CLI paths ────────

    /** (server id, the name in its `jarvis/self`), read once per server; null = not read yet. */
    @Volatile private var jarvisSelf: Pair<String, String>? = null
    @Volatile private var jarvisSelfTriedAtMs = 0L

    /**
     * Jarvis' tab on this device, for the watch's dictated instruction (sent on /reply like any answer from the wrist,
     * with an INSTRUCTION identity, so ReplyGuard decides exactly as for a working session). The name is
     * `~/.claude-remote/jarvis/self` of Jarvis' server, the fixed one until that was read; the read runs in the
     * background at most every 10 min until it answers, so a /sessions push never waits on it. Null = no tab.
     */
    fun jarvisTabIdForWatch(): String? {
        val tabs = pendingCloses.state.value.visibleOf(tabManager.tabs.value)
        val server = jarvisServer(tabs, emptyList())
        val known = jarvisSelf?.takeIf { it.first == server?.id }
        val now = System.currentTimeMillis()
        if (server != null && known == null && now - jarvisSelfTriedAtMs > 10 * 60_000L) {
            jarvisSelfTriedAtMs = now
            reconnectScope.launch {
                val out = jarvisRun(server, com.clauderemote.session.wear.WatchJarvis.SELF_READ)
                if (out != null) jarvisSelf = server.id to com.clauderemote.session.wear.WatchJarvis.selfName(out)
            }
        }
        val name = known?.second ?: QuickAccess.JARVIS_TMUX_NAME
        return tabs.firstOrNull { it.tmuxSessionName == name && (server == null || it.server.id == server.id) }?.id
    }

    // ── Jarvis' conversation mode (F5a, 2026-11-09) ─────────────────────────────────────────────────────────────

    /**
     * Type a spoken [prompt] (`[hlas] …`) into Jarvis' pane — his tab only ([jarvisTabIdForWatch], the tab the watch's
     * dictation answers), over the same checked reply exec as an answer from the wrist, never the PTY and never a new
     * channel. Stricter than the watch's instruction (decision 14d): only while Jarvis is idle at his prompt
     * ([com.clauderemote.session.service.ReplyGuard.voiceCheck]) and his input box is empty (`emptyInput`, checked on
     * the server right before the keys). No tab, no route or any doubt = not sent, said as one sentence; nothing waits
     * to be sent later (CLAUDE.md "Input delivery").
     */
    suspend fun sendJarvisVoice(
        prompt: String,
        stillWanted: () -> Boolean = { true },
    ): com.clauderemote.voice.conversation.TalkSendResult {
        val id = jarvisTabIdForWatch()
            ?: return com.clauderemote.voice.conversation.TalkSendResult.Refused(com.clauderemote.voice.conversation.TalkWords.NO_TAB)
        val verdict = com.clauderemote.session.service.ReplyGuard.voiceCheck(
            statusService.promptTokens.current(id), statusService.currentActivity(id), claudeControl.canDeliverNow(id),
        )
        var serverAtMs: Long? = null
        val why = when (verdict) {
            is com.clauderemote.session.service.ReplyGuard.Verdict.Refuse -> {
                FileLogger.log(TAG, "Jarvis voice prompt refused: ${verdict.detail}")
                verdict.reason
            }
            is com.clauderemote.session.service.ReplyGuard.Verdict.Send -> {
                // the mode ended before the keys: nothing goes in
                if (!stillWanted()) return com.clauderemote.voice.conversation.TalkSendResult.Refused("Konverzace skončila, neposláno.")
                // started = finished: a cut between the typing and the Enter would leave the text as his draft
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    claudeControl.sendCheckedReplyTimed(id, verdict.expect, prompt, approve = false, emptyInput = true)
                }
                serverAtMs = r.serverAtMs
                r.why
            }
        }
        FileLogger.log(TAG, "Jarvis voice prompt (${prompt.length} chars) to $id: ${why ?: "sent at server ms $serverAtMs"}")
        return if (why == null) com.clauderemote.voice.conversation.TalkSendResult.Sent(serverAtMs)
        else com.clauderemote.voice.conversation.TalkWords.refusal(why)
    }

    /** Jarvis' tab can be reached now (the conversation mode's route check, every tick). */
    fun jarvisVoiceReachable(): Boolean = jarvisTabIdForWatch()?.let { claudeControl.canDeliverNow(it) } == true

    /** Jarvis' finished turns: his tab at the moment the turn ended (a tab reopened under another id still counts). */
    fun jarvisTurns(): kotlinx.coroutines.flow.Flow<com.clauderemote.voice.conversation.JarvisTurn> = kotlinx.coroutines.flow.flow {
        notificationService.stopAnswers.collect { a ->
            if (a.sessionId == jarvisTabIdForWatch()) {
                emit(com.clauderemote.voice.conversation.JarvisTurn(a.markerAtMs, a.body, a.markerEpochSec))
            }
        }
    }

    /** The proposals for the watch's Čeká page: the Velín's read ([JarvisData.PROPOSALS]) on Jarvis' server. */
    suspend fun jarvisWatchProposals(periodic: Boolean = false): com.clauderemote.session.wear.WatchProposals {
        val server = jarvisServer(tabManager.tabs.value, emptyList())
            ?: return com.clauderemote.session.wear.WatchProposals(false, "Jarvis není na žádném známém serveru")
        val all = jarvisRun(server, com.clauderemote.session.service.JarvisData.PROPOSALS, periodic = periodic)
        return com.clauderemote.session.wear.WatchJarvis.proposals(
            com.clauderemote.session.service.JarvisData.parseCombinedProposals(all),
            com.clauderemote.session.service.JarvisData.serverNowMs(all),
        )
    }

    /**
     * "Poslat"/"Provést"/"Zahodit" from the wrist: read the proposals again, check that this one still waits with the
     * very text the wrist showed ([com.clauderemote.session.wear.WatchJarvis.refusal]), then the Velín's own decision
     * (`cr-jarvis|cr-mail approve|reject <id> --by app:<installId>`).
     */
    suspend fun jarvisWatchDecide(id: String, approve: Boolean, textHash: Int): com.clauderemote.session.wear.WatchDecideResult {
        fun answer(ok: Boolean, text: String) = com.clauderemote.session.wear.WatchDecideResult(id, ok, text)
        val server = jarvisServer(tabManager.tabs.value, emptyList()) ?: return answer(false, "Jarvis není na žádném známém serveru")
        val all = jarvisRun(server, com.clauderemote.session.service.JarvisData.PROPOSALS)
        val list = com.clauderemote.session.service.JarvisData.parseCombinedProposals(all)
            ?: return answer(false, "Návrhy nejdou načíst (server/Jarvis)")
        val p = list.firstOrNull { it.id == id }
        com.clauderemote.session.wear.WatchJarvis.refusal(p, approve, textHash, com.clauderemote.session.service.JarvisData.serverNowMs(all))
            ?.let { return answer(false, com.clauderemote.session.wear.WatchJarvis.refusalText(p, it)) }
        p!!
        val r = com.clauderemote.session.service.JarvisData.parseRun(
            jarvisRun(
                server,
                com.clauderemote.session.service.JarvisData.decideProposal(p, approve, installId),
                totalMs = com.clauderemote.session.service.JarvisData.decideTimeoutMs(p, approve),
            ),
        )
        FileLogger.log(TAG, "Watch ${if (approve) "approved" else "rejected"} Jarvis proposal $id: ok=${r.ok}")
        return answer(r.ok, com.clauderemote.session.wear.WatchJarvis.decideText(p, approve, r.ok, r.message))
    }

    /** "Co je nového?" from the wrist: `cr-digest delta --peek --say` on Jarvis' server (the cursor is not moved). */
    suspend fun jarvisWatchNews(): com.clauderemote.session.wear.WatchNews {
        val server = jarvisServer(tabManager.tabs.value, emptyList())
            ?: return com.clauderemote.session.wear.WatchNews(false, "Jarvis není na žádném známém serveru")
        val r = com.clauderemote.session.service.JarvisData.parseRun(
            jarvisRun(server, com.clauderemote.session.wear.WatchJarvis.NEWS, totalMs = 15_000),
        )
        return com.clauderemote.session.wear.WatchNews(r.ok, r.message.take(com.clauderemote.session.wear.WatchJarvis.NEWS_MAX))
    }

    /**
     * Where the "do not adopt" list lives (AppSettings, set by the UI). Null = not wired yet: adoption then adopts
     * nothing, because a hide it cannot read must not be undone by adopting the tab again.
     */
    @Volatile internal var hiddenAdoptionsStore: HiddenAdoptionsStore? = null

    private val _parkedTabs = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * Tabs adopted from `opened.d` that have not been opened yet (session id → the marker's `by`). They have no shell:
     * the reconnect sweeps skip them, the first [reconnectSession] (the user's tap) ends the parking.
     */
    val parkedTabs: StateFlow<Map<String, String>> = _parkedTabs

    /** (server id, tmux name) of restores under way: their tab comes from the restore, never from adoption. */
    private val restoringNames = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, String>>()

    /** The newest marker mtime seen per (server id, tmux name): a hide stamps at least that (server clock). */
    private val markerMtimes = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Long>()

    private val adoptLock = Any()

    /**
     * The tail of [server]'s `closed.jsonl`, NEWEST FIRST ([ClosedRead.Ok]); [ClosedRead.Unknown] = no answer or a
     * read cut off — the UI says "nejde načíst", never "nic". One short exec on whatever transport the server has;
     * without one, a fresh connection only when [allowFresh] (else [ClosedRead.Skipped]).
     */
    internal suspend fun readClosedRecords(server: SshServer, allowFresh: Boolean = true): ClosedRead =
        withContext(Dispatchers.IO) {
            try {
                val out = SshSessionHelper.withSession(server, 5_000, allowFresh = allowFresh) { sess ->
                    execReadWithWatchdog(sess, ManifestCommands.readClosed(RecentlyClosed.READ_LIMIT), totalMs = 10_000)
                }
                com.clauderemote.session.service.ClosedRecords.parseRead(out)?.let { ClosedRead.Ok(it) } ?: ClosedRead.Unknown
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExecUnavailable) {
                // withSession refused before running anything: no transport and no fresh handshake wanted
                if (!allowFresh && e.reason == ExecUnavailableReason.NOT_UP) return@withContext ClosedRead.Skipped
                FileLogger.log(TAG, "closed.jsonl read of ${server.name} failed: ${e.reason}: ${e.message}")
                ClosedRead.Unknown
            } catch (e: Exception) {
                FileLogger.log(TAG, "closed.jsonl read of ${server.name} failed: ${e.javaClass.simpleName}: ${e.message}")
                ClosedRead.Unknown
            }
        }

    /**
     * Restore [record] on [server] under [tmuxName] ([SessionRestore.restoreCommand]; [sessionId] = a confirmed
     * candidate, [freshSessionId] = a new conversation in the same folder and account). The server side never kills
     * anything (a taken name is [RestoreResult.Exists]) and lifts the tombstones before the start. A running result
     * ([RestoreResult.Restored], [RestoreResult.LoginRequired]) gets its tab the way any session is opened here:
     * [launchSession] of the existing name, which persists it, clears the tombstone and pushes the manifest.
     *
     * Never retried here: an answer that did not arrive is [RestoreResult.Unknown] (the session may run), and a
     * second try could only end in "taken" anyway.
     */
    internal suspend fun restoreClosed(
        server: SshServer,
        record: com.clauderemote.session.service.ClosedRecord,
        tmuxName: String = record.tmuxName,
        sessionId: String? = null,
        freshSessionId: String? = null,
    ): Pair<com.clauderemote.session.service.RestoreResult, ClaudeSession?> = withContext(Dispatchers.IO) {
        val key = server.id to tmuxName
        restoringNames.add(key)
        try {
            val cmd = try {
                com.clauderemote.session.service.SessionRestore.restoreCommand(record, tmuxName, sessionId, freshSessionId)
            } catch (e: IllegalArgumentException) {
                return@withContext com.clauderemote.session.service.RestoreResult.Refused(e.message ?: "invalid record") to null
            }
            var sent = false
            val out = try {
                SshSessionHelper.withSession(server, 10_000) { sess ->
                    sent = true
                    execReadWithWatchdog(sess, cmd, totalMs = RecentlyClosed.RESTORE_EXEC_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.log(TAG, "Restore of $tmuxName on ${server.name} failed (sent=$sent): ${e.javaClass.simpleName}: ${e.message}")
                // before the command went out nothing ran; after it, nobody knows
                return@withContext (if (sent) com.clauderemote.session.service.RestoreResult.Unknown
                    else com.clauderemote.session.service.RestoreResult.Failed("server nedostupný")) to null
            }
            val result = com.clauderemote.session.service.SessionRestore.parse(out)
            FileLogger.log(TAG, "Restore of ${record.tmuxName} as $tmuxName on ${server.name}: $result")
            val running = result is com.clauderemote.session.service.RestoreResult.Restored ||
                result == com.clauderemote.session.service.RestoreResult.LoginRequired
            if (!running) return@withContext result to null
            val folder = record.restoreFolder ?: return@withContext result to null
            // The conversation the pane resumed, not the registry's id from the answer: the tab's Restart and rebuild
            // resume this id, so it must be one with a transcript (the script says which one it took when it fell
            // back to the launch id).
            val resumeId = freshSessionId
                ?: (result as? com.clauderemote.session.service.RestoreResult.Restored)?.resumedId
                ?: sessionId ?: record.sessionId ?: record.launchSessionId
            val tab = try {
                launchSession(
                    server = server,
                    folder = folder,
                    mode = record.claudeMode,
                    model = record.claudeModel,
                    connectionType = ConnectionType.SSH,
                    tmuxSessionName = tmuxName,
                    isNewTmuxSession = false,
                    resumeClaudeSessionId = resumeId,
                    accountSlug = record.account,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the session runs on the server; the tab's attach failed: it stays in the list (and can be adopted)
                FileLogger.error(TAG, "Restored $tmuxName but its tab did not attach", e)
                null
            }
            result to tab
        } finally {
            restoringNames.remove(key)
        }
    }

    /**
     * "Vrátit" of a Jarvis close asked from outside the composition (the phone notification's action, the Velín's
     * news line): App takes each non-null value, sets it back to null and runs the "Nedávno zavřené" restore with its
     * dialogs. A StateFlow, so a request from a cold start waits for the first composition.
     */
    val jarvisUndoRequests = kotlinx.coroutines.flow.MutableStateFlow<JarvisUndoRequest?>(null)

    /**
     * The undos under way — ONE set for all three ways in: [undoJarvisClose] (the watch) and the App's restore of
     * [jarvisUndoRequests] (the phone notification, the Velín) both hold it for the whole read + restore (MED-1).
     */
    val jarvisUndoInFlight = JarvisUndoInFlight()

    /**
     * What "Vrátit" of [tmux] on [server] would restore ([JarvisUndo.pick] over the server's `closed.jsonl`). One
     * short exec on the same path as the "Nedávno zavřené" read; an unreadable list is [JarvisUndo.Pick.No], never a
     * guess.
     */
    internal suspend fun findJarvisUndo(server: SshServer, tmux: String): JarvisUndo.Pick =
        when (val read = readClosedRecords(server)) {
            is ClosedRead.Ok -> JarvisUndo.pick(read.records, tmux, System.currentTimeMillis() / 1000)
            else -> JarvisUndo.Pick.No("Nedávno zavřené na ${server.name} nejde načíst – zkus to za chvíli.")
        }

    /**
     * "Vrátit" from the watch, where no screen can ask anything: [findJarvisUndo], then [restoreClosed] of the record
     * under its own name (a new conversation in the same folder and account when it has none to resume, the same
     * choice a tap on the row makes). Never under another name or another conversation: those need the app's
     * dialog, and the sentence says so. Returns the Czech sentence for the user.
     */
    suspend fun undoJarvisClose(serverId: String, tmux: String): String {
        val name = JarvisUndo.alias(tmux)
        val server = serverStorage.getServer(serverId) ?: return "Server, na kterém „$name“ běžela, už v aplikaci není."
        return jarvisUndoInFlight.run(server.id, tmux) {
            JarvisUndo.undo(
                tmux, server.name, System.currentTimeMillis() / 1000,
                read = { readClosedRecords(server) },
                restore = { record, fresh -> restoreClosed(server, record, freshSessionId = fresh).first },
                newId = { java.util.UUID.randomUUID().toString() },
            )
        } ?: JarvisUndo.busyText(tmux)
    }

    /**
     * "Skrýt z tohoto zařízení": the tab leaves THIS device only — no tombstone, no kill, no manifest push (the
     * session stays live, so pushMerge keeps its manifest entry for the other devices) — and its marker is not
     * adopted again. The session shows up here as a detached remote row, as any session without a tab does.
     */
    fun hideFromThisDevice(sessionId: String) {
        val tab = tabManager.getTab(sessionId) ?: return
        FileLogger.log(TAG, "Hiding ${tab.tmuxSessionName} (tab $sessionId) from this device: no tombstone, no kill")
        suppressAdoption(tab.server.id, tab.tmuxSessionName)
        _parkedTabs.update { it - sessionId }
        sessionStorage?.remove(sessionId)
        launchGuarded("Hide of ${tab.tmuxSessionName}") { disconnectSession(sessionId) }
    }

    /** Never adopt the marker [tmuxName] has now (hidden or closed here). */
    private fun suppressAdoption(serverId: String, tmuxName: String) {
        val store = hiddenAdoptionsStore ?: return
        val now = System.currentTimeMillis() / 1000
        try {
            val stamp = HiddenAdoptions.stamp(now, markerMtimes[serverId to tmuxName])
            store.save(HiddenAdoptions.parse(store.load()).pruned(now).with(serverId, tmuxName, stamp).serialize())
        } catch (e: Exception) {
            FileLogger.error(TAG, "Saving the hide of $tmuxName failed", e)
        }
    }

    /**
     * One reconcile round of [serverId] ([ExecServerReconciler] `onRound`): adopt its `opened.d` markers as parked
     * tabs ([OpenedAdoption.select]) and keep the parked tabs' conversation id in step with the manifest (they are
     * not registered with the reconciler until they connect).
     */
    private fun adoptOpened(serverId: String, snap: com.clauderemote.session.service.ReconcileSnapshot) {
        snap.opened?.forEach { m -> markerMtimes.merge(serverId to m.tmuxName, m.mtimeEpochS) { a, b -> maxOf(a, b) } }
        val store = hiddenAdoptionsStore ?: return
        val server = serverStorage.getServer(serverId) ?: return
        synchronized(adoptLock) {
            val live = tabManager.tabs.value
            _parkedTabs.update { p -> p.filterKeys { id -> live.any { it.id == id } } }
            val own = ownTmuxNames(serverId) + restoringNames.filter { it.first == serverId }.map { it.second }
            val picks = OpenedAdoption.select(
                serverId, snap.opened, snap.tmuxNames, snap.manifest, snap.tombstoned, own,
                HiddenAdoptions.parse(store.load()), System.currentTimeMillis() / 1000,
            )
            for (a in picks) adoptParked(server, a)
            for ((id, _) in _parkedTabs.value) {
                val tab = tabManager.getTab(id) ?: continue
                if (tab.server.id != serverId) continue
                val uuid = snap.manifestUuid(tab.tmuxSessionName) ?: continue
                if (uuid != tab.claudeSessionId) {
                    tabManager.updateClaudeSessionId(id, uuid)
                    tabManager.getTab(id)?.let { sessionStorage?.upsert(SessionStorage.fromClaudeSession(it)) }
                }
            }
        }
    }

    /** A parked tab of [a]: the manifest entry as it is, no shell, not selected; persisted like any tab (never pushed here). */
    private fun adoptParked(server: SshServer, a: Adoption) {
        val e = a.entry
        val id = generateId()
        val tab = ClaudeSession(
            id = id,
            server = server,
            folder = e.folder,
            mode = e.mode,
            model = e.model,
            tmuxSessionName = e.tmuxSessionName,
            connectionType = e.connectionType,
            status = SessionStatus.DISCONNECTED,
            alias = e.alias.ifBlank { TmuxNameParser.parse(e.tmuxSessionName, server.name).alias },
            claudeSessionId = e.claudeSessionId,
            accountSlug = e.accountSlug,
        )
        FileLogger.log(TAG, "Adopting ${e.tmuxSessionName} on ${server.name} as a parked tab $id (marker by=${a.marker.by})")
        terminalIO.initBuffer(id)
        tabManager.addTab(tab, activate = false)
        statusService.updateActivity(id, SessionActivity.DISCONNECTED)
        sessionStorage?.upsert(SessionStorage.fromClaudeSession(tab))
        _parkedTabs.update { it + (id to a.marker.by.orEmpty()) }
    }


    suspend fun disconnectSession(sessionId: String) {
        // From now on this tab is no reason to keep (or start again) its server's exec transport: a reconnect
        // that is still in flight must not revive it behind the close below.
        closingIds.add(sessionId)
        try {
            disconnectSessionNow(sessionId)
        } finally {
            closingIds.remove(sessionId)
        }
    }

    private suspend fun disconnectSessionNow(sessionId: String) {
        reconnectRetryJobs.remove(sessionId)?.cancel()
        _reconnectStatus.update { it - sessionId }
        gitStatusService.stopPolling(sessionId)
        persistence.stopIdRefresh(sessionId)
        connectionRegistry.reconciler?.unregister(sessionId)
        attachTraces.remove(sessionId)
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
        reconnectedAt.remove(sessionId); earlyDeaths.remove(sessionId); lineSentAt.remove(sessionId)   // kolo 7, R1
        connectionRegistry.ssh(sessionId)?.disconnect()
        connectionRegistry.removeSsh(sessionId)
        connectionRegistry.mosh(sessionId)?.disconnect()
        connectionRegistry.removeMosh(sessionId)
        terminalIO.removeBuffer(sessionId)
        transcriptService.dispose(sessionId)
        statusService.stopPoller(sessionId)
        notificationService.promptDetector.removeSession(sessionId)
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
        tabManager.removeTab(sessionId)
        // The exec transport lives while the server has a tab: closed only now that the tab is gone, so a
        // reconnect of this very tab (which checked that it exists) cannot start it again afterwards.
        if (serverId != null) synchronized(execLock(serverId)) {
            if (tabManager.tabs.value.none { it.server.id == serverId } && !launchPending(serverId)) closeExecTransportLocked(serverId)
        }
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
        releaseExecLayer()
        terminalIO.clearAllBuffers()
    }

    /**
     * Close every exec transport this orchestrator started and stop watching the exec flags. Nothing is
     * revived: [ensureExecTransport] starts it again for a server that still has a tab. The Android activity
     * calls it on the orchestrator it replaces (a recreated activity builds a new orchestrator with its own
     * tabs; the old one's supervisors would otherwise reconnect for ever).
     */
    fun releaseExecLayer() {
        execFlagWatchers.values.forEach { it.cancel() }
        execFlagWatchers.clear()
        // Every server that has a lock, not only the ones ensured so far: the FIRST ensure of a server that is past its
        // checks holds the lock without being in the set yet; it is found here once it is through, and closed.
        for (serverId in (execEnsuredServers + execLocks.keys).toSet()) synchronized(execLock(serverId)) {
            if (serverId in execEnsuredServers) {
                execCloseHook?.invoke(serverId)
                execEnsuredServers.remove(serverId)
                execManager.close(serverId)
            }
        }
    }

    /** Set by [shutdown]: this orchestrator was replaced and must not start, revive or reconnect anything any more. */
    @Volatile private var released = false

    /** Whether [shutdown] ran (tests, diagnostics). */
    internal val isReleased: Boolean get() = released

    /**
     * Registers the mosh client of [sessionId] whose connect just succeeded. A connect still running when [shutdown] took
     * its snapshot ([ConnectionRegistry.takeMoshAndEt]) ends here: the client is stopped instead of staying attached to
     * tmux as a stale client of a replaced orchestrator. Put first, then the check: [shutdown] sets [released] before its
     * snapshot, so a client put after the snapshot always sees it. False = stopped, the caller returns.
     */
    internal suspend fun adoptMosh(sessionId: String, m: com.clauderemote.connection.MoshManager): Boolean {
        connectionRegistry.putMosh(sessionId, m)
        if (!released) return true
        if (connectionRegistry.mosh(sessionId) === m) connectionRegistry.removeMosh(sessionId)
        FileLogger.log(TAG, "Mosh client of $sessionId connected after shutdown — stopping it")
        try { m.disconnect() } catch (e: Exception) { FileLogger.log(TAG, "Stopping a replaced mosh client failed: ${e.message}") }
        return false
    }

    /** [adoptMosh] for an Eternal Terminal client. */
    internal suspend fun adoptEt(sessionId: String, e: com.clauderemote.connection.EtManager): Boolean {
        connectionRegistry.putEt(sessionId, e)
        if (!released) return true
        if (connectionRegistry.et(sessionId) === e) connectionRegistry.removeEt(sessionId)
        FileLogger.log(TAG, "ET client of $sessionId connected after shutdown — stopping it")
        try { e.disconnect() } catch (ex: Exception) { FileLogger.log(TAG, "Stopping a replaced ET client failed: ${ex.message}") }
        return false
    }

    /**
     * The production end of an orchestrator that a new one replaced (a recreated Android activity builds its own with
     * its own tabs): from now on nothing of this one starts, revives or reconnects anything (the [released] guard of
     * [ensureExecTransports], [ensureExecTransport], `kickBootstrap`, [reconnectSession], [autoReconnect], the network
     * events), its exec transports close, its background loops end (transcript, status and git pollers, the notify
     * watcher, reconcile, bootstrap retries) and its tabs' shells are detached in the background. The tmux sessions stay;
     * the new orchestrator has its own attaches. Idempotent.
     *
     * Its pollers would otherwise keep running through the PROCESS-WIDE [com.clauderemote.connection.SshSessionHelper],
     * whose providers now belong to the new orchestrator (its exec transport, its pool).
     */
    fun shutdown() {
        if (released) return
        released = true
        val shells = connectionRegistry.allSsh().toList()
        // the native mosh / ET clients of its tabs too: each would stay attached to tmux as a stale client
        val natives = connectionRegistry.takeMoshAndEt()
        FileLogger.log(TAG, "Orchestrator replaced — stopping its loops and detaching ${shells.size} shell(s) and ${natives.size} mosh/ET client(s); tmux sessions stay")
        releaseExecLayer()
        reconnectScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            for (n in natives) try { n() } catch (e: Exception) { FileLogger.log(TAG, "Stopping a replaced mosh/ET client failed: ${e.message}") }
            for (m in shells) try { m.disconnect() } catch (e: Exception) { FileLogger.log(TAG, "Detaching a replaced shell failed: ${e.message}") }
            connectionRegistry.clearSsh()
            connectionRegistry.teardownAllTransports()
        }
    }

    /**
     * For tests that build an orchestrator per test inside one JVM: [releaseExecLayer] plus ending the background
     * scope. Without it the notify watchers, reconcile loops, bootstrap retries and the 60 s stats loop of a finished
     * test keep running and log into (and reconnect against) the next test.
     */
    internal fun shutdownForTest() {
        releaseExecLayer()
        reconnectScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
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
        // A session nobody has spoken to yet has NO transcript, and `--resume` on
        // one exits with "No conversation found with session ID" — which is how
        // switching the account of a freshly created session used to kill it.
        //
        // So the SERVER decides, at launch time, by looking for the transcript
        // itself — in the shared projects/ and in the account's own. An app-side
        // probe first only added a round trip and a way to be wrong: when it
        // failed it was taken as "no conversation", and `--session-id` against
        // an existing one is refused ("already in use"), leaving a bare prompt.
        val cmd = ClaudeConfig.buildRestartCommand(
            tab.tmuxSessionName, tab.folder, tab.mode, tab.model, uuid, tab.accountSlug,
            resume = null,
        )
        FileLogger.log(TAG, "Restarting Claude Code for $sessionId (server-decided $uuid, account=${tab.accountSlug ?: "default"}) in tmux ${tab.tmuxSessionName}")
        // The respawn kills+redraws the pane; suppress the prompt detector so it
        // doesn't misfire on the transient screen. UUID is unchanged, so the
        // transcript stream keeps tailing the same file across the restart.
        notificationService.promptDetector.suppressFor(sessionId, 5000)
        try {
            execReadWithWatchdog(sshSession, cmd, totalMs = 15_000)
        } catch (e: Exception) {
            FileLogger.error(TAG, "restartClaude failed for $sessionId", e)
        }
    }

    /**
     * Restart Claude in EVERY eligible session, one at a time.
     *
     * The case this exists for is a Claude Code upgrade: the new binary only
     * takes effect on a fresh process, and respawning forty panes by hand is
     * how it doesn't get done.
     *
     * SEQUENTIAL on purpose. Each restart is an SSH exec with a transcript
     * probe and a pane respawn; firing forty at once means forty concurrent
     * execs against one sshd and one tmux server, which is the shape of the
     * redraw bursts that have killed transports here before. A restart also
     * takes a moment to settle, and staggering keeps the terminal readable
     * instead of every pane blanking at once.
     *
     * Returns how many sessions were actually restarted, so the caller can say
     * so rather than claim all of them.
     */
    suspend fun restartClaudeForAllSessions(): Int {
        val targets = RestartTargets.eligible(tabManager.tabs.value)
        FileLogger.log(TAG, "restartAll: ${targets.size} of ${tabManager.tabs.value.size} sessions eligible")
        var done = 0
        for (id in targets) {
            restartClaude(id)
            done++
            kotlinx.coroutines.delay(RESTART_ALL_GAP_MS)
        }
        FileLogger.log(TAG, "restartAll: restarted $done session(s)")
        return done
    }

    // ======================== CLAUDE ACCOUNTS ========================

    /**
     * Every Claude login provisioned on [serverId], DEFAULT FIRST. Labels only —
     * tokens never leave the server (see AccountService).
     */
    suspend fun listClaudeAccounts(serverId: String): List<ClaudeAccount> =
        accountService.listAccounts(serverId)

    /**
     * Create the server-side config dir for [slug] (idempotent). Must complete
     * before any session launches under that slug — a session that starts first
     * makes claude create real `settings.json`/`plugins/` where the shared
     * symlinks belong, silently losing the hooks and the OMC statusline.
     */
    suspend fun provisionClaudeAccount(serverId: String, slug: String): Boolean =
        accountService.provisionAccount(serverId, slug)

    /** Delete [slug]'s config dir (its credentials with it). Never touches `~/.claude`. */
    suspend fun removeClaudeAccount(serverId: String, slug: String): Boolean =
        accountService.removeAccount(serverId, slug)

    /**
     * Provision [slug] if needed and start `claude auth login` for it in a
     * detached tmux session (`claude-login-<slug>`) the UI can screen-scrape for
     * the OAuth URL / code prompt.
     */
    suspend fun startClaudeAccountLogin(serverId: String, slug: String): Boolean =
        accountService.startLogin(serverId, slug)

    /**
     * Visible text of [slug]'s login pane, or null when no pane is running.
     *
     * The in-session login card ([loginFlow]) is driven by the terminal emulator
     * of an ATTACHED tab, and the account-login pane deliberately has no tab — so
     * poll this to find the OAuth URL (`https://…oauth/authorize…`) and to see
     * `Paste code here if prompted >` appear, then answer with
     * [submitClaudeAccountLoginCode].
     */
    suspend fun readClaudeAccountLoginScreen(serverId: String, slug: String): String? =
        accountService.readLoginScreen(serverId, slug)

    /** Type the pasted OAuth code into [slug]'s login pane and press Enter. */
    /**
     * Which account [sessionId] is REALLY running under, probed from the process.
     * Also reconciles: a stored slug that disagrees is corrected, so the switcher
     * and the restore script stop working from a stale belief.
     */
    suspend fun resolveSessionAccount(sessionId: String): String? {
        val tab = tabManager.getTab(sessionId) ?: return null
        // Unknown — the probe timed out, or no claude is running (a dead pane
        // has no CLAUDE_CONFIG_DIR either) — keeps the stored account. Reading
        // either as "default" used to undo a fresh account switch.
        val reading = accountService.readSessionAccountSlug(tab.server.id, tab.tmuxSessionName)
        if (reading !is com.clauderemote.session.service.SessionAccountReading.Running) return tab.accountSlug
        val actual = reading.slug
        if (actual != tab.accountSlug) {
            FileLogger.log(
                TAG,
                "Session $sessionId is on account ${actual ?: "default"}, " +
                    "stored was ${tab.accountSlug ?: "default"} — correcting",
            )
            val updated = tab.copy(accountSlug = actual)
            val previouslyActive = tabManager.activeTabId.value
            tabManager.addTab(updated)
            if (previouslyActive != null && previouslyActive != sessionId) {
                tabManager.switchTab(previouslyActive)
            }
            sessionStorage?.upsert(SessionStorage.fromClaudeSession(updated))
        }
        return actual
    }

    /** This server's shared folder-policy blob, or null when it has none yet. */
    suspend fun readFolderPolicies(serverId: String): String? =
        accountService.readFolderPolicies(serverId)

    /** Publish folder policies so the other clients see the same rules. */
    suspend fun writeFolderPolicies(serverId: String, json: String): Boolean =
        accountService.writeFolderPolicies(serverId, json)

    /** OAuth URL from the account-login pane, or null until it renders. */
    suspend fun readClaudeAccountLoginUrl(serverId: String, slug: String): String? =
        accountService.readLoginUrl(serverId, slug)

    suspend fun submitClaudeAccountLoginCode(serverId: String, slug: String, code: String): Boolean =
        accountService.submitLoginCode(serverId, slug, code)

    /** Kill [slug]'s login pane (cancelled, or finished). Absent pane = success. */
    suspend fun cancelClaudeAccountLogin(serverId: String, slug: String): Boolean =
        accountService.cancelLogin(serverId, slug)

    /**
     * Move a LIVE session to another Claude login, KEEPING the conversation.
     *
     * Reuses the "Restart Claude Code" path — `tmux respawn-pane -k` + `claude
     * --resume <uuid>` — but relaunches under the new account's
     * `CLAUDE_CONFIG_DIR`. The history survives because every account symlinks
     * `projects/` to the shared `~/.claude/projects/`, so the transcript the
     * resume reads is the very same file (and the app's transcript stream keeps
     * tailing it — the UUID doesn't change).
     *
     * [accountSlug] null (or "default") moves it back to the default `~/.claude`
     * login, which runs with NO `CLAUDE_CONFIG_DIR` at all.
     *
     * Fire-and-forget on the orchestrator's own scope: the tab + persisted record
     * are updated immediately (so a reconnect/restore can't revert the account),
     * the respawn and the sessions.json push happen in the background. A session
     * with no `claudeSessionId` can't be respawned mid-conversation — restartClaude
     * logs that and the new account takes effect at its next (re)launch instead.
     */
    fun switchSessionAccount(sessionId: String, accountSlug: String?) {
        val tab = tabManager.getTab(sessionId) ?: return
        // Normalise "default" to null: the model treats them as the same account,
        // and null is what the launch builders check for the no-env-var case.
        val target = accountSlug?.takeUnless { it.isBlank() || it == ClaudeAccount.DEFAULT_SLUG }
        if (tab.accountSlug == target) return
        val updated = tab.copy(accountSlug = target)

        // In-memory first, so ANY relaunch from here on (reconnect, drift restore,
        // respawn below) already carries the new account. addTab replaces in place
        // (idempotent on id) but also makes the tab active — restore the previously
        // active tab, since switching a BACKGROUND session's account must not yank
        // the user's view (the terminal only repaints via switchTab/onTabSwitched,
        // so a silent active-tab change would desync it).
        val previouslyActive = tabManager.activeTabId.value
        tabManager.addTab(updated)
        if (previouslyActive != null && previouslyActive != sessionId) {
            tabManager.switchTab(previouslyActive)
        }
        sessionStorage?.upsert(SessionStorage.fromClaudeSession(updated))
        FileLogger.log(TAG, "Switching $sessionId to account ${target ?: "default"} (resume ${tab.claudeSessionId})")

        launchGuarded("Account switch of $sessionId") {
            // Provision on demand: the user may be moving a session onto an
            // account whose dir was removed, or one provisioned by another device.
            // Idempotent, and skipped entirely for the default account.
            if (target != null) accountService.provisionAccount(tab.server.id, target)
            restartClaude(sessionId)
            // Push the manifest so the server-side restore/drift scripts relaunch
            // this session under the NEW account after a reboot or a self-heal.
            if (connectionRegistry.execActive(tab.server.id)) {
                persistence.pushSessionsToServer(tab.server.id)
            } else connectionRegistry.ssh(sessionId)?.let { conn ->
                persistence.pushSessionsToServer(conn, tab.server.id)
            }
            // The chips belong to the login — start (idempotently) the poller for
            // the account we just moved to.
            usageService.startServerRateLimitPolling(tab.server.id, target)
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
        // One exec transport per server, started before the first tab of it connects (the tabs'
        // own connects then find it coming up and the port forwards go to it, not to a shell).
        restored.map { it.server.id }.distinct().forEach { ensureExecTransport(it) }
        // Concurrent, not sequential: a strictly sequential loop over N restored
        // tabs made the LAST tab wait N × (connect+attach) after a cold start —
        // with 20+ tabs that's tens of seconds, long enough that an impatient
        // user hit "Reconnect" on a tab whose turn just hadn't come up yet.
        // Safe to parallelize: with the pool on, the pool bounds the handshakes to a server (at most
        // [ServerTransportPool.MAX_PARALLEL_HANDSHAKES] at once, ceil(N / shell cap) in all), with it off the
        // legacy gate of 3 does, so this doesn't storm a just-recovered link — it only removes the ARTIFICIAL
        // serialization across tabs/servers. The first reconnects wait a moment for the exec transport just
        // started above, so that their leases get the shell cap of 8 ([awaitExecForWave]).
        openReconnectWave()
        reconnectScope.launch {
            // C4: the screen's size first (at most VIEW_SIZE_WAIT_MS): a tab connected before it is known starts its tmux
            // client at 80x24, and a tab in the background gets no resize until it is shown. The wave's window opens again.
            if (terminalIO.awaitFirstViewSize(VIEW_SIZE_WAIT_MS)) openReconnectWave()
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

    /** [downloadFile] with a size cap applied BEFORE the transfer, and a time limit. */
    suspend fun downloadFileCapped(sessionId: String, remotePath: String, maxBytes: Long, timeoutMs: Long): ByteArray? =
        remoteOps.downloadFile(sessionId, remotePath, maxBytes, timeoutMs)

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

    /** Where the start (server clock) of a hook-reported approval comes from; a seam so the switch rule can be driven without a watcher. */
    internal var approvalSinceOf: (serverId: String, tmuxName: String) -> Long? =
        { serverId, tmux -> notificationService.hookApprovalSince(serverId, tmux) }

    /** FIX 4 exception: the hook-reported approval of [sessionId] is recent enough to survive leaving the tab. */
    private fun hookApprovalSurvivesSwitch(sessionId: String): Boolean {
        val tab = tabManager.getTab(sessionId) ?: return false
        return HookApprovalPolicy.survivesSwitch(
            sinceMs = approvalSinceOf(tab.server.id, tab.tmuxSessionName),
            nowMs = System.currentTimeMillis(),
            hooksOn = CommFlags.effectiveFor(tab.server.id).hooks,
        )
    }

    /** The tab of [serverId] whose tmux session is [tmuxName] (names are only unique per server), or null. */
    private fun sessionIdOf(serverId: String, tmuxName: String): String? =
        tabManager.tabs.value.firstOrNull { it.server.id == serverId && it.tmuxSessionName == tmuxName }?.id

    /**
     * Fills the registry's seam slots with the real exec layer, in ONE step: a half-wired layer (a manager
     * without the gate, a gate without the bootstrap) would route some callers to an exec transport that
     * nobody keeps alive. Everything stays behind the flags: with `comm_exec` off for a server every
     * lookup behaves as before.
     */
    private fun wireExecLayer() {
        val reg = connectionRegistry
        val channels = GatedExecChannels(
            execManager,
            // a long stream may borrow a tab transport while the exec transport is down, but only one with room
            fallbackSession = { reg.fallbackPooledSession(it) },
            onTransportTrouble = { execManager.noteChannelTrouble(it) },
            // decided under the pool's lock: the look above and this claim may be a moment apart (false = the room is gone)
            reserveFallback = { id, session -> reg.transportPool(id).reserveFallbackChannel(session) },
            releaseFallback = { id, session -> reg.transportPool(id).releaseChannel(session) },
        )
        val remote = PooledRemoteExec(channels)
        reg.execSessions = execManager
        reg.execChannels = channels
        reg.remoteExec = remote
        // Built on first use: the bootstrap listens to the process-wide install / remove requests of Settings
        // from the moment it exists, and an orchestrator that never connects anything must not hold that
        // subscription (a request nobody can serve is dropped, not queued).
        reg.bootstrap = LazyServerBootstrap {
            DefaultServerBootstrap(
                scope = reconnectScope,
                exec = remote,
                // exec transport down: the bootstrap still runs, on a tab transport that has room
                fallback = { serverId, cmd, totalMs ->
                    reg.execOnTab(serverId, cmd, totalMs) ?: throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
                },
                serverName = { serverStorage.getServer(it)?.name ?: it },
            )
        }
        reg.reconciler = persistence.newExecReconciler(onRound = { serverId, snap -> adoptOpened(serverId, snap) })
        // withSession (history scan, folder browser, command fetcher, ...) holds a SHORT permit on the exec transport
        com.clauderemote.connection.SshSessionHelper.execChannelsProvider = { serverId -> reg.activeExecChannels(serverId) }
        notificationService.closures = persistence
        notificationService.hookSink = object : HookEventSink {
            override fun onClaudeWorking(serverId: String, tmuxName: String, working: Boolean) {
                // a hook `work` event re-arms the notify latch exactly like the statusline does
                if (working) sessionIdOf(serverId, tmuxName)?.let { notificationService.promptDetector.onClaudeWorking(it) }
            }
            override fun onCheckUuid(serverId: String, tmuxName: String) {
                val sid = sessionIdOf(serverId, tmuxName) ?: return
                launchGuarded("checkUuid of $tmuxName") { reg.reconciler?.checkUuid(sid) }
            }
            override fun onCheckClosed(serverId: String, tmuxName: String, delayMs: Long) {
                val sid = sessionIdOf(serverId, tmuxName) ?: return
                launchGuarded("checkClosed of $tmuxName") { reg.reconciler?.checkClosed(sid, delayMs) }
            }
        }
        // The flags a server forces off are re-read while the app is in the foreground.
        launchGuarded("The flags refresh loop") {
            while (true) {
                kotlinx.coroutines.delay(FLAGS_REFRESH_MS)
                flagsRefreshTick()
            }
        }
    }

    init {
        // Last in the class body: every property the wiring touches exists by now.
        wireExecLayer()
    }

    companion object {
        private const val TAG = "SessionOrchestrator"
        /** Foreground re-read of `client-flags.json` per server. */
        internal const val FLAGS_REFRESH_MS = 10L * 60 * 1000
        /** How long a connect waits for the first bootstrap of its server before it goes on (the order hooks -> tmux is kept when it is quick). */
        internal const val BOOTSTRAP_WAIT_MS = 3_000L
        /** How long the first reconnects of a wave wait, together, for their server's exec transport (shell cap 8 needs it up). */
        internal const val EXEC_WAVE_WAIT_MS = 3_000L
        /** C4: how long a cold start's reconnects wait for the first size of the terminal view (shared, once per orchestrator). */
        internal const val VIEW_SIZE_WAIT_MS = 2_000L
        /** Kolo 7 (adv N1): a loss this soon after a reconnect's attach line (R1: stamped before it was sent) counts as an early death. */
        internal const val EARLY_DEATH_MS = 5_000L
        /** R1: one heal try of `tmux-sockheal.sh` (`sg_heal`): its `flock -w 2`, or once locked the 2 s deadline for the recreated socket. */
        internal const val SOCKHEAL_HEAL_TRY_MS = 2_000L
        /** R1: the pause before its second heal try (`sleep 0.5`). */
        internal const val SOCKHEAL_RETRY_PAUSE_MS = 500L
        /** R1: its one probe of a socket that does not answer (`timeout -k 1 5`): refused as hung after it, no second try. */
        internal const val SOCKHEAL_HUNG_PROBE_MS = 6_000L
        /**
         * R1: the longest a refusing `tmux-sockheal.sh` takes to its `exit 1` (measured 4.54–4.70 s for the lock case,
         * review D1 adv P5): two heal tries 0.5 s apart, or one hung probe. Not counted: its `timeout 5 ss`/`timeout 10
         * find` look-ups of the listener, instant when the box is not wedged. `SockhealRefusalBrakeTest` pins these
         * numbers to the generated script.
         */
        internal val SOCKHEAL_LONGEST_REFUSAL_MS: Long = maxOf(2 * SOCKHEAL_HEAL_TRY_MS + SOCKHEAL_RETRY_PAUSE_MS, SOCKHEAL_HUNG_PROBE_MS)
        /**
         * R1: a loss this soon after a Rebuild or FreshLaunch line went out counts as an early death: the guard's longest
         * refusal plus the attach line's own [EARLY_DEATH_MS] for the round trip and a loaded box (11 s). With 5 s a
         * refusal that came back over a link slower than ~0.5 s looked like a connection that held, and every tab of the
         * server reconnected every 6–10 s for as long as the guard refused (review D1 adv N1).
         */
        internal val LAUNCH_EARLY_DEATH_MS: Long = SOCKHEAL_LONGEST_REFUSAL_MS + EARLY_DEATH_MS
        /** Kolo 4: how long a launch's legacy server bookkeeping waits for the tab's shell (an autoReconnect after a loss). */
        internal const val PERSIST_SHELL_WAIT_MS = 30_000L
        /** A one-off operation (restore install, tombstone, manifest push) that found no transport waits this long for the exec transport. */
        internal const val ONE_OFF_WAIT_MS = 60_000L
        /** V16: names a new session's creation tries before it gives up (each taken one moves on to the next free one). */
        internal const val FRESH_CREATE_NAMES = 8
        /** V16: tries of one name whose answer was lost (cut short, timed out) before the launch fails. */
        internal const val FRESH_CREATE_LOST_TRIES = 3
        /** V16: the creation command's wall-clock limit (socket guard + anchor + new-session + owner check). */
        internal const val FRESH_CREATE_MS = 20_000L
        /** V16: handshake limit when the creation leases the pooled transport the tab uses next. */
        internal const val FRESH_CREATE_CONNECT_MS = 15_000
        /** V16, mosh with exec routing: how long the creation waits for the exec transport it started, and how often it asks. */
        internal const val FRESH_CREATE_EXEC_WAIT_MS = 15_000L
        internal const val FRESH_CREATE_EXEC_POLL_MS = 250L
        /** R6-2/R6-3: client limit of the cleanup of a launch that ended without its tab (two server checks of 3 s). */
        internal const val FRESH_CLEANUP_MS = 8_000L
        private const val FORWARD_MOVE_TRIES = 20
        private const val FORWARD_MOVE_RETRY_MS = 250L
        /** Sentinel returned by [downloadFile] when the remote file exceeds [DOWNLOAD_SIZE_LIMIT]. */
        val DOWNLOAD_TOO_LARGE: ByteArray = ByteArray(0)
        private const val DOWNLOAD_SIZE_LIMIT = 200L * 1024 * 1024 // 200 MB

        /**
         * Pause between panes in [restartClaudeForAllSessions]. Long enough that
         * a fleet-wide restart reads as a rolling wave rather than every pane
         * going blank at once, short enough that forty sessions finish in about
         * a minute.
         */
        private const val RESTART_ALL_GAP_MS = 1_500L
    }
}


/**
 * Phase timing of one attach, for the `Attach <sid> kind=cold ms=<n> phase=...` line: `shell` (transport lease
 * and shell channel), `prompt` (shell prompt and startup command), `decide` (server setup, tmux probes and the
 * decision, up to sending the command), `attach` (sending it), `firstByte` (until the first terminal output
 * of tmux after it). Each phase is the time since the previous mark.
 *
 * The pty echoes the typed command back about one round trip after it was sent, which says nothing about tmux:
 * [firstByte] skips that echo (the command text itself, and the escape sequences and line breaks around it) and
 * closes on the first thing that is not part of it, or on tmux switching to its alternate screen.
 */
internal class AttachTrace(private val clock: () -> Long = System::currentTimeMillis) {
    class Done(val totalMs: Long, val phasesMs: Map<String, Long>)

    private val startedAt = clock()
    private var mark = startedAt
    private val phases = LinkedHashMap<String, Long>()
    @Volatile private var sent = false
    /** What is still to come of the echo of the sent command (printable characters only, no blanks). */
    private var echoLeft = ""
    /** C4: a line the app itself is about to print into the terminal ("Reconnected!"): not tmux's output. */
    private var own: String? = null

    @Synchronized fun phase(name: String) {
        val now = clock()
        phases[name] = now - mark
        mark = now
    }

    /** [command] was just sent: its echo is not tmux's output. */
    @Synchronized fun attachSent(command: String = "") {
        phase("attach")
        echoLeft = visible(command)
        sent = true
    }

    /** C4: [text] is printed by the app next: [firstByte] passes over it. */
    @Synchronized fun ownOutput(text: String) {
        own = text
    }

    /** The finished trace on the first output [chunk] after [attachSent] that is not the echo of the command, else null (cheap: it runs for every output chunk). */
    fun firstByte(chunk: String): Done? {
        if (!sent) return null
        return synchronized(this) {
            if (!sent) return null
            if (chunk == own) { own = null; return null }
            var rest = visible(chunk)
            val n = minOf(rest.length, echoLeft.length)
            if (n > 0 && rest.regionMatches(0, echoLeft, 0, n)) {
                // all of it is (a part of) the echo
                rest = rest.substring(n)
                echoLeft = echoLeft.substring(n)
            }
            if (rest.isEmpty() && !chunk.contains(ALT_SCREEN)) return null
            sent = false
            phase("firstByte")
            Done(clock() - startedAt, phases.toMap())
        }
    }

    private companion object {
        /** tmux switching the client to its alternate screen: the first thing a successful attach prints. */
        const val ALT_SCREEN = "\u001B[?1049h"
        val ESCAPES = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B[()][A-Za-z0-9]|\u001B[=>]")
        fun visible(text: String): String = ESCAPES.replace(text, "").filterNot { it.isWhitespace() || it == '\b' }
    }
}

/**
 * FIX 4 exception: leaving a tab resets its APPROVAL to ready because the screen can no longer confirm the
 * dialog, except when a hook reported it recently. The pane's own claude said so, and the next hook event of
 * the pane (any of them) ends it, so it cannot go stale the way a screen guess does. After [MAX_AGE_MS] the
 * old rule applies again. [sinceMs] is the SERVER clock of the event; the device clock is within seconds of
 * it, so the age is judged on the absolute difference (a clock hours off proves nothing: the rule falls back).
 */
internal object HookApprovalPolicy {
    const val MAX_AGE_MS = 10L * 60 * 1000

    fun survivesSwitch(sinceMs: Long?, nowMs: Long, hooksOn: Boolean): Boolean =
        hooksOn && sinceMs != null && kotlin.math.abs(nowMs - sinceMs) < MAX_AGE_MS
}

/**
 * V16: what the answer of [ClaudeConfig.buildFreshCreateCommand] says. [Result.Ours]: the session of the name carries this
 * launch's nonce (created now, or by an earlier try whose answer was lost), or `new-session` succeeded; [Result.Taken]:
 * another session holds the name (another nonce, or none — a session this launch did not start); [Result.Failed]: no
 * session of the name and the creation failed (the socket guard refused, tmux failed: the server says why);
 * [Result.Lost]: the answer is missing or cut short — try the same name again.
 */
internal object FreshCreate {
    sealed interface Result {
        data object Ours : Result
        data object Taken : Result
        data class Failed(val why: String) : Result
        data object Lost : Result
    }

    fun parse(output: String?, nonce: String): Result {
        if (output == null) return Result.Lost
        val lines = output.lines()
        val rcAt = lines.indexOfLast { it.startsWith(ClaudeConfig.FRESH_CREATE_RC) }
        if (rcAt < 0) return Result.Lost
        val rc = lines[rcAt].removePrefix(ClaudeConfig.FRESH_CREATE_RC).trim().toIntOrNull()
        val after = lines.drop(rcAt + 1).map { it.trim() }
        val owner = after.firstOrNull { it.startsWith(ClaudeConfig.FRESH_CREATE_OWNER) }?.removePrefix(ClaudeConfig.FRESH_CREATE_OWNER)
        return when {
            owner == "${ClaudeConfig.LAUNCH_ENV}=$nonce" -> Result.Ours
            owner != null -> Result.Taken
            ClaudeConfig.FRESH_CREATE_ABSENT !in after -> Result.Lost
            // K6-1: the server's limit cut the creation short — whether it got to create is unknown: the same name again
            rc == 124 -> Result.Lost
            // created, and gone again before the check (claude's shell exited at once): ours; the tab's attach rebuilds it
            rc == 0 -> Result.Ours
            else -> Result.Failed(lines.take(rcAt).joinToString(" ").trim().takeLast(300).ifEmpty { "exit ${rc ?: "unknown"}" })
        }
    }
}

/** A [ServerBootstrap] created by [create] the first time anything calls it. */
internal class LazyServerBootstrap(create: () -> ServerBootstrap) : ServerBootstrap {
    private val delegate by lazy(create)
    override suspend fun ensure(serverId: String) = delegate.ensure(serverId)
    override fun invalidate(serverId: String) = delegate.invalidate(serverId)
    override suspend fun refreshFlags(serverId: String) = delegate.refreshFlags(serverId)
}
