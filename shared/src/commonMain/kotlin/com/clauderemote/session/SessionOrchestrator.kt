package com.clauderemote.session

import com.clauderemote.connection.SshManager
import com.clauderemote.connection.SshSessionHelper
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import com.clauderemote.model.*
import com.clauderemote.session.status.RemoteSessionStatus
import com.clauderemote.session.status.SessionStatusPoller
import com.clauderemote.session.transcript.TranscriptEntry
import com.clauderemote.session.transcript.TranscriptStream
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

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
    private val connections = java.util.concurrent.ConcurrentHashMap<String, SshManager>()
    private val moshConnections = mutableMapOf<String, com.clauderemote.connection.MoshManager>()

    // Per-session transcript streams (JSONL tail readers).
    private val transcriptStreams = mutableMapOf<String, TranscriptStream>()
    private val transcriptLock = Any()

    // Per-session OMC state pollers (active skill + subagent count).
    private val statusPollers = mutableMapOf<String, SessionStatusPoller>()
    private val statusLock = Any()

    // Per-session terminal output buffer (ring buffer, capped at MAX_BUFFER)
    private val outputBuffers = mutableMapOf<String, StringBuilder>()
    private val bufferLock = Any()

    // Prompt detection for notifications — quiescence-based, reads rendered screen state.
    private val promptDetector = InputPromptDetector().apply {
        onDetection = { det ->
            val isActive = tabManager.activeTabId.value == det.sessionId
            fireNeedsInput(det.sessionId, det.type.displayHint, isActive)
            // Bridge prompt types that require explicit user approval to the
            // APPROVAL_NEEDED activity so the y/n buttons visually emphasize.
            // The onStateChange callback will overwrite this on the next state
            // change (e.g. WORKING), so it self-clears automatically.
            if (det.type == PromptType.APPROVAL_NEEDED || det.type == PromptType.PERMISSION_PROMPT) {
                updateActivity(det.sessionId, SessionActivity.APPROVAL_NEEDED)
            }
        }
        onStateChange = { sessionId, state ->
            when (state) {
                ClaudeState.WORKING -> updateActivity(sessionId, SessionActivity.WORKING)
                ClaudeState.IDLE -> updateActivity(sessionId, SessionActivity.WAITING_FOR_INPUT)
                ClaudeState.APPROVAL -> updateActivity(sessionId, SessionActivity.APPROVAL_NEEDED)
                ClaudeState.UNKNOWN -> {} // keep last known activity
            }
        }
    }

    /**
     * Platform-provided screen snapshot reader. Must marshal onto the thread that
     * owns the terminal emulator (main looper on Android, EDT on Swing). Pass-through
     * to [InputPromptDetector.screenReader].
     */
    var screenReader: (suspend (sessionId: String) -> ScreenStateSnapshot?)?
        get() = promptDetector.screenReader
        set(value) { promptDetector.screenReader = value }

    // Per-session activity state (for health indicator dots)
    private val _sessionActivities = kotlinx.coroutines.flow.MutableStateFlow<Map<String, SessionActivity>>(emptyMap())
    val sessionActivities: kotlinx.coroutines.flow.StateFlow<Map<String, SessionActivity>> = _sessionActivities

    // Sessions whose idle/working state is driven by the Claude Code Stop hook
    // (authoritative: flips to WAITING the instant Claude finishes, regardless
    // of which screen the user is on). The UI uses this to know it can trust
    // `activity` outright instead of falling back to a stale-WORKING timer.
    private val _hookActiveSessions = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val hookActiveSessions: kotlinx.coroutines.flow.StateFlow<Set<String>> = _hookActiveSessions

    private fun setHookActive(sessionId: String, active: Boolean) {
        if (active) promptDetector.markHookActive(sessionId)
        else promptDetector.markHookInactive(sessionId)
        _hookActiveSessions.update { if (active) it + sessionId else it - sessionId }
    }

    // Per-session context window usage (0-100). Derived from the transcript's
    // latest assistant-message token usage (see startContextTokenCollector),
    // not scraped from the TUI.
    private val _contextPercents = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val contextPercents: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _contextPercents

    // Latest context-size (tokens) seen per session, mirrored from the
    // transcript stream so emit()'s statusline scrape can calibrate the window.
    private val latestContextTokens = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // Calibrated context-window size (tokens) per session: learned from one
    // statusline `ctx:NN%` sighting (window ≈ tokens / pct), snapped to the
    // 200k / 1M tier. Until known, a session whose tokens exceed 200k is
    // assumed 1M (unambiguous); otherwise ctx % is withheld.
    private val contextWindowTokens = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // Sessions that have been WORKING at least once since the app attached.
    // Status chips stay empty until then — so we never surface stale scrollback
    // values on a fresh attach (per product decision).
    private val sawWorkSinceAttach = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val contextTokenCollectors = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    // Per-session SSH latency (ms)
    private val _latencies = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
    val latencies: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> = _latencies

    // Per-session git status of the working directory (branch + dirty/ahead/behind).
    // Absence of a sessionId key means "not a git repo" — UI shows no chip.
    private val _gitStatuses = kotlinx.coroutines.flow.MutableStateFlow<Map<String, GitStatus>>(emptyMap())
    val gitStatuses: kotlinx.coroutines.flow.StateFlow<Map<String, GitStatus>> = _gitStatuses

    // Pending input queue per session (for offline queue feature)
    private val pendingInputs = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    private val _pendingCounts = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val pendingCounts: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _pendingCounts

    // Per-server reachability for the launcher health dot. Keyed by server id.
    // Separate from the serialized SshServer model (mirrors gitStatuses etc.).
    private val _serverHealth = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ServerHealth>>(emptyMap())
    val serverHealth: kotlinx.coroutines.flow.StateFlow<Map<String, ServerHealth>> = _serverHealth
    // Debounce: last probe time per server id, so pull-to-refresh spam doesn't storm.
    private val lastServerProbeAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // When APPROVAL_NEEDED was last asserted per session — used to protect a
    // fresh screen-detected approval from being clobbered by a stale statusline
    // WORKING render (see the grace check in emit()). Refreshed every ~3s by
    // the detector's APPROVAL re-check while the dialog is on screen.
    private val lastApprovalAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun updateActivity(sessionId: String, activity: SessionActivity) {
        if (activity == SessionActivity.WORKING) sawWorkSinceAttach.add(sessionId)
        if (activity == SessionActivity.APPROVAL_NEEDED) {
            lastApprovalAt[sessionId] = System.currentTimeMillis()
        }
        val previous = _sessionActivities.value[sessionId]
        _sessionActivities.update { it + (sessionId to activity) }
        // Refresh git status when the session goes idle (e.g. a command just
        // finished and may have changed the branch/dirty state). Debounced
        // against the 90s loop via lastGitProbeAt. Off-thread; never blocks.
        if (activity == SessionActivity.WAITING_FOR_INPUT && previous != SessionActivity.WAITING_FOR_INPUT) {
            if (!isInBackground) {
                val last = lastGitProbeAt[sessionId] ?: 0L
                if (System.currentTimeMillis() - last >= 5_000L) {
                    reconnectScope.launch { probeGitStatusOnce(sessionId) }
                }
            }
        }
    }

    /**
     * Collect the transcript stream's context-token count and turn it into the
     * ctx-window %. Launched once per session (idempotent). The % is only
     * surfaced after the session has actually worked (so a fresh attach shows no
     * stale value) and once the window size is known — either calibrated from a
     * statusline `ctx:NN%` sighting in emit(), or inferred as 1M when tokens
     * already exceed the 200k tier.
     */
    // Caller holds transcriptLock so create-and-bind stays atomic with the
    // transcriptStreams map (and with disconnectSession's remove-and-cancel).
    // computeIfAbsent makes the single-launch race-free.
    private fun startContextTokenCollector(sessionId: String, stream: TranscriptStream) {
        contextTokenCollectors.computeIfAbsent(sessionId) {
            reconnectScope.launch {
                stream.contextTokens.collect { tokens ->
                    if (tokens == null) {
                        // /clear or /compact reset the conversation — drop the
                        // now-stale % instead of holding the pre-clear value.
                        _contextPercents.update { it - sessionId }
                        latestContextTokens.remove(sessionId)
                        return@collect
                    }
                    if (tokens <= 0L) return@collect
                    latestContextTokens[sessionId] = tokens
                    if (sessionId !in sawWorkSinceAttach) return@collect
                    val window = contextWindowTokens[sessionId]
                        ?: if (tokens > 200_000L) 1_000_000L else return@collect
                    val pct = ((tokens.toDouble() / window) * 100).toInt().coerceIn(0, 100)
                    updateContextPercent(sessionId, pct)
                    onContextUpdate?.invoke(sessionId, pct)
                }
            }
        }
    }

    /** Dispatch [onClaudeNeedsInput] no more than once per [notifyDebounceMs] per
     *  session — protects against rapid duplicate fires from the Stop-hook stream
     *  and from the screen-state fallback firing on transient quiescence. */
    private fun fireNeedsInput(sessionId: String, hint: String, isActive: Boolean) {
        val now = System.currentTimeMillis()
        val last = lastNeedsInputAt[sessionId] ?: 0L
        if (now - last < notifyDebounceMs) {
            FileLogger.log(TAG, "Suppressed needs-input for $sessionId (debounce)")
            return
        }
        lastNeedsInputAt[sessionId] = now
        onClaudeNeedsInput?.invoke(sessionId, hint, isActive)
    }

    private fun updateContextPercent(sessionId: String, percent: Int) {
        _contextPercents.update { it + (sessionId to percent) }
    }

    // Last parsed usage tokens (for dashboard)
    private val _usageTokens = MutableStateFlow<CostCalculator.UsageTokens?>(null)
    val usageTokens: StateFlow<CostCalculator.UsageTokens?> = _usageTokens

    // Terminal output callback — set by the platform (Android native terminal, Desktop JediTerm)
    var onTerminalOutput: ((sessionId: String, data: String) -> Unit)? = null

    // Tab switch callback — platform clears terminal and replays buffer
    var onTabSwitched: ((sessionId: String, bufferedOutput: String) -> Unit)? = null

    // Disconnect callback
    var onSessionDisconnect: ((sessionId: String) -> Unit)? = null

    // Session became active callback (for keep-alive etc.)
    var onSessionActive: ((ClaudeSession) -> Unit)? = null

    // Notification callback when Claude needs attention
    var onClaudeNeedsInput: ((sessionId: String, hint: String, isActiveTab: Boolean) -> Unit)? = null

    // Context window usage callback (0-100 percent)
    var onContextUpdate: ((sessionId: String, percent: Int) -> Unit)? = null

    // Usage stats callback (session%, week%)
    var onUsageUpdate: ((sessionPercent: Int?, weekPercent: Int?) -> Unit)? = null

    // Fired when a session is permanently forgotten (tab closed). Lets the UI
    // prune the matching entry from its (stale) remote-tmux snapshot so the
    // killed pane doesn't reappear as a "detached remote" row and resurrect
    // into a new empty session when tapped.
    var onSessionForgotten: ((serverId: String, tmuxSessionName: String) -> Unit)? = null

    // Usage percentages parsed from the OMC statusline, keyed by SERVER id.
    // 5h and weekly limits are account-wide, so every session on the same
    // server shares them — switch tabs and the values stay put instead of
    // resetting to '—' until the new tab fetches them itself.
    private val _sessionUsagePercents = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val sessionUsagePercents: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _sessionUsagePercents
    private val _weekUsagePercents = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val weekUsagePercents: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _weekUsagePercents

    // Time-to-reset (minutes) parsed from the OMC `(XhYm)` suffix.
    private val _sessionResetMin = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val sessionResetMin: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _sessionResetMin
    private val _weekResetMin = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val weekResetMin: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _weekResetMin

    // Per-session periodic polling jobs
    private val usagePollingJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val latencyPollingJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    // Per-session Claude Code Stop-hook watchers (tail -f on notify file)
    private val notifyWatchers = mutableMapOf<String, kotlinx.coroutines.Job>()
    /** Per-session timestamp of the last "needs input" dispatch, used to debounce
     *  the Stop-hook fire stream — claude can emit several markers in quick
     *  succession (model handoff, tool retries) and we don't want to vibrate
     *  the phone for each one. */
    private val lastNeedsInputAt = mutableMapOf<String, Long>()
    private val notifyDebounceMs = 5_000L
    // Per-session pollers that read ~/.claude/sessions/<pid>.json on the
    // server to capture the *real* claude session_id — which can drift from
    // the UUID we passed via --session-id when the user invokes /resume,
    // /clear, /compact etc. Without this we'd push a stale UUID to
    // sessions.json and the next reboot's restore.sh would --resume the
    // wrong (or non-existent) conversation.
    private val sessionIdRefreshJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    // Per-session git-status pollers (branch + dirty/ahead/behind of the working dir).
    private val gitStatusJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    // Persistent reconnect re-arm loops (one per session). Started whenever a
    // reconnect path gives up (autoReconnect exhausted, reconnectSession threw)
    // so that NO failure is terminal: the loop keeps calling reconnectSession
    // with capped backoff until the tab is ACTIVE again or removed. Without
    // this, a multi-minute outage (flaky roaming network) burned autoReconnect's
    // 3 attempts and the session sat DISCONNECTED forever — only an app restart
    // (which re-runs restoreAndReconnect) recovered it.
    private val reconnectRetryJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

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
            var attempt = 1
            while (isActive) {
                val base = (2000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(60_000L)
                kotlinx.coroutines.delay(base + kotlin.random.Random.nextLong(500))
                val tab = tabManager.getTab(sessionId) ?: break // closed/forgotten
                if (tab.status == SessionStatus.ACTIVE) break   // recovered elsewhere
                if (tab.status != SessionStatus.CONNECTING) {
                    FileLogger.log(TAG, "Re-arm reconnect attempt $attempt for $sessionId")
                    try {
                        reconnectSession(sessionId)
                    } catch (e: Exception) {
                        FileLogger.error(TAG, "Re-arm reconnect attempt $attempt failed for $sessionId", e)
                    }
                    if (tabManager.getTab(sessionId)?.status == SessionStatus.ACTIVE) break
                }
                attempt++
            }
            reconnectRetryJobs.remove(sessionId)
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
        startUsagePolling(sessionId)
        startGitStatusPolling(sessionId)
        startLatencyPolling(sessionId)
        connections[sessionId]?.let { conn ->
            startNotifyWatcher(sessionId, tmuxSessionName, conn)
            startSessionIdRefresh(sessionId, tmuxSessionName, conn)
        }
        // Keep a transcript stream running for every connected session so the
        // notification body (last assistant message) is available even in Raw
        // view, not only after the Chat view has subscribed.
        ensureTranscriptStream(sessionId)
    }

    /**
     * One-shot exec with a hard wall-clock bound. JSch's blocking
     * `readText()` cannot be interrupted by coroutine cancellation, so a plain
     * withTimeout would still leave the IO thread parked on a dead channel —
     * on a flaky network those parked threads accumulate until Dispatchers.IO
     * is starved and even reconnects can't get a thread (the "only an app
     * restart helps" state). The watchdog instead force-disconnects the
     * channel at [totalMs], which closes the stream and releases the reader.
     */
    private suspend fun execReadWithWatchdog(
        sshSession: com.jcraft.jsch.Session,
        cmd: String,
        connectMs: Int = 5000,
        totalMs: Long = 15_000,
    ): String = kotlinx.coroutines.coroutineScope {
        val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
        ch.setCommand(cmd)
        ch.inputStream = null
        val input = ch.inputStream
        val watchdog = launch {
            kotlinx.coroutines.delay(totalMs)
            try { ch.disconnect() } catch (_: Exception) {}
        }
        try {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                ch.connect(connectMs)
                input.bufferedReader().readText()
            }
        } finally {
            watchdog.cancel()
            try { ch.disconnect() } catch (_: Exception) {}
        }
    }
    // Per-session timestamp (ms) of the last git probe, used to debounce the
    // idle-transition trigger against the 90s polling loop so they don't
    // double-fire within a few seconds.
    private val lastGitProbeAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var isInBackground = false
    private val reconnectingSessionIds = mutableSetOf<String>()
    // Last known terminal dimensions per session — used to re-send SIGWINCH after reconnect
    private val terminalSizes = mutableMapOf<String, Pair<Int, Int>>()
    // Sessions whose claudeSessionId has been confirmed by at least one server-side
    // probe (pid-probe or sessions.json reconcile). Once confirmed, transcriptFlow()
    // skips the one-shot kick-probe — firing it on every call caused repeated
    // stream restarts (cancel → _entries = emptyList() → blank transcript) whenever
    // the user toggled to the Transcript view while the server was slow to respond.
    private val confirmedUuids = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Call from onPause/onResume to pause heavy background work and save battery. */
    fun setBackgroundMode(background: Boolean) {
        isInBackground = background
    }

    private fun startUsagePolling(sessionId: String) {
        usagePollingJobs[sessionId]?.cancel()
        usagePollingJobs[sessionId] = reconnectScope.launch {
            kotlinx.coroutines.delay(5000) // initial delay
            while (isActive) {
                // Skip poll when app is in background — user can't see usage bar anyway.
                // A missing connection (mid-reconnect) just skips this round —
                // the old `?: break` exited the loop PERMANENTLY the first time
                // a reconnect briefly emptied the connections map, leaving the
                // chips dead until app restart.
                if (!isInBackground) {
                    try {
                        val sshSession = connections[sessionId]?.getSession()
                        if (sshSession != null) {
                            // 60s budget: the first run may npm-install ccusage.
                            val output = execReadWithWatchdog(
                                sshSession,
                                "which ccusage >/dev/null 2>&1 || npm install -g ccusage >/dev/null 2>&1; ccusage blocks --active --json --offline --no-color 2>/dev/null || echo '{}'",
                                totalMs = 60_000,
                            )
                            parseUsageJson(output)
                        }
                    } catch (_: Exception) {}
                }
                kotlinx.coroutines.delay(120_000) // poll every 2 min (was 30s)
            }
        }
    }

    /**
     * Periodically probe the git status of the session's working directory
     * (branch + dirty/ahead/behind) and publish it to [gitStatuses]. Mirrors
     * [startUsagePolling]: runs the exec off the UI thread, respects
     * [isInBackground], and never blocks. A non-git directory (or any failure)
     * clears the entry so the UI shows no chip. Cancelled in [disconnectSession].
     */
    private fun startGitStatusPolling(sessionId: String) {
        gitStatusJobs[sessionId]?.cancel()
        gitStatusJobs[sessionId] = reconnectScope.launch {
            kotlinx.coroutines.delay(3000) // initial delay — let the session settle
            while (isActive) {
                if (!isInBackground) {
                    probeGitStatusOnce(sessionId)
                }
                kotlinx.coroutines.delay(90_000) // poll every 90s
            }
        }
    }

    /**
     * Run a single git-status probe (one exec + parse + StateFlow update) for
     * [sessionId]. Shared by the 90s polling loop and the activity→idle trigger.
     * Updates [lastGitProbeAt] on entry so the two callers debounce each other.
     * The folder may be the literal "~" or a relative path, so we mirror the
     * expansion idiom used by [probeTranscriptExists]: `${F/#~/$HOME}` plus a
     * relative-path anchor under $HOME — JSch's non-login exec shell does not
     * expand `~` on its own.
     */
    private suspend fun probeGitStatusOnce(sessionId: String) {
        lastGitProbeAt[sessionId] = System.currentTimeMillis()
        try {
            val folder = tabManager.getTab(sessionId)?.folder ?: "~"
            val conn = connections[sessionId] ?: return
            val sshSession = conn.getSession() ?: return
            val escapedFolder = folder.replace("'", "'\\''")
            val cmd = """
                F='$escapedFolder'
                E="${'$'}{F/#~/${'$'}HOME}"
                case "${'$'}E" in /*) ;; *) E="${'$'}HOME/${'$'}E";; esac
                cd "${'$'}E" 2>/dev/null || exit 0
                git rev-parse --abbrev-ref HEAD 2>/dev/null
                git status --porcelain 2>/dev/null | head -1
                git rev-list --left-right --count @{u}...HEAD 2>/dev/null
            """.trimIndent()
            val output = execReadWithWatchdog(sshSession, cmd, totalMs = 20_000)
            parseGitStatus(sessionId, output)
        } catch (_: Exception) {
            _gitStatuses.update { it - sessionId }
        }
    }

    /**
     * Probe reachability of each [servers] entry and publish to [serverHealth]
     * (keyed by server id). Off the UI thread, time-bounded, and debounced.
     * Branches per server:
     *  - A live, connected SSH session already exists for the server →
     *    [ServerHealth.ONLINE] immediately (no redundant socket probe).
     *  - [SshServer.useCloudflareProxy] → [ServerHealth.UNKNOWN] (a raw TCP
     *    probe to a Cloudflare-tunneled host is misleading; don't show false
     *    OFFLINE).
     *  - Otherwise → [ServerHealth.CHECKING], then a 2.5s TCP connect on
     *    Dispatchers.IO; success → ONLINE, any failure/timeout → OFFLINE.
     * Skipped entirely while [isInBackground]; per-server debounced to ~5s.
     */
    /** Remove a deleted server's health entry so no stale state leaks. */
    fun pruneServerHealth(serverId: String) {
        _serverHealth.update { it - serverId }
        lastServerProbeAt.remove(serverId)
    }

    fun probeServers(servers: List<SshServer>, force: Boolean = false) {
        if (isInBackground) return
        val now = System.currentTimeMillis()
        for (server in servers) {
            val last = lastServerProbeAt[server.id] ?: 0L
            if (!force && now - last < 5_000L) continue
            lastServerProbeAt[server.id] = now
            reconnectScope.launch {
                // 1) Reuse a live connection → ONLINE without a socket probe.
                val hasLiveConnection = connections.any { (sessionId, mgr) ->
                    mgr.isConnected && tabManager.getTab(sessionId)?.server?.id == server.id
                }
                if (hasLiveConnection) {
                    _serverHealth.update { it + (server.id to ServerHealth.ONLINE) }
                    return@launch
                }
                // 2) Cloudflare-tunneled host → a raw TCP probe is misleading.
                if (server.useCloudflareProxy) {
                    _serverHealth.update { it + (server.id to ServerHealth.UNKNOWN) }
                    return@launch
                }
                // 3) Raw TCP connect, time-bounded, off the UI thread.
                _serverHealth.update { if (it[server.id] == null) it + (server.id to ServerHealth.CHECKING) else it }
                val reachable = withContext(Dispatchers.IO) {
                    val socket = java.net.Socket()
                    try {
                        socket.connect(java.net.InetSocketAddress(server.host, server.port), 2500)
                        true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
                _serverHealth.update {
                    it + (server.id to if (reachable) ServerHealth.ONLINE else ServerHealth.OFFLINE)
                }
            }
        }
    }

    /**
     * Parse the multi-line output of the git probe. Line 1 is the branch
     * (empty/error → not a git repo → clear the entry). The presence of a
     * porcelain line means dirty. A trailing "behind<TAB>ahead" line gives the
     * ahead/behind counts. Defensive: any malformed output → null.
     */
    private fun parseGitStatus(sessionId: String, raw: String) {
        val lines = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val branch = lines.firstOrNull()
        if (branch.isNullOrBlank()) {
            // Not a git repo (or unparseable) — show no chip.
            _gitStatuses.update { it - sessionId }
            return
        }
        val resolvedBranch = branch
        // A left-right count line looks like "2\t3" (behind \t ahead).
        val countLine = lines.lastOrNull { it.matches(Regex("""\d+\s+\d+""")) }
        var behind = 0
        var ahead = 0
        if (countLine != null) {
            val parts = countLine.split(Regex("\\s+"))
            behind = parts.getOrNull(0)?.toIntOrNull() ?: 0
            ahead = parts.getOrNull(1)?.toIntOrNull() ?: 0
        }
        // Dirty if there's any porcelain output (a line that isn't the branch
        // and isn't the count line).
        val dirty = lines.any { it != resolvedBranch && it != countLine }
        _gitStatuses.update {
            it + (sessionId to GitStatus(branch = resolvedBranch, dirty = dirty, ahead = ahead, behind = behind))
        }
    }

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
                try {
                    val remote = fetchSessionsFromServer(sshManager)
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
                            notifyClaudeSessionIdChanged(sessionId, realUuid)
                            confirmedUuids[sessionId] = realUuid
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

    private fun startLatencyPolling(sessionId: String) {
        latencyPollingJobs[sessionId]?.cancel()
        latencyPollingJobs[sessionId] = reconnectScope.launch {
            kotlinx.coroutines.delay(3000)
            val recentLatencies = mutableListOf<Long>()
            while (isActive) {
                if (!isInBackground) {
                    try {
                        // Missing connection (mid-reconnect) skips the round;
                        // the old `?: break` killed the loop permanently.
                        val sshSession = connections[sessionId]?.getSession()
                        if (sshSession == null) {
                            kotlinx.coroutines.delay(15_000)
                            continue
                        }
                        val latency = kotlin.run {
                            val start = System.currentTimeMillis()
                            execReadWithWatchdog(sshSession, "echo pong", totalMs = 10_000)
                            System.currentTimeMillis() - start
                        }
                        recentLatencies.add(latency)
                        if (recentLatencies.size > 5) recentLatencies.removeAt(0)
                        val avg = recentLatencies.average().toLong()
                        _latencies.update { it + (sessionId to avg) }
                    } catch (_: Exception) {}
                }
                kotlinx.coroutines.delay(15_000) // every 15s
            }
        }
    }

    private fun parseUsageJson(json: String) {
        try {
            if (json.isBlank() || json.trim() == "{}") return
            // Parse token counts from ccusage blocks JSON
            // Sum all token types for real usage
            val inputTokens = Regex("\"inputTokens\"\\s*:\\s*(\\d+)").find(json)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val outputTokens = Regex("\"outputTokens\"\\s*:\\s*(\\d+)").find(json)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val cacheCreation = Regex("\"cacheCreationInputTokens\"\\s*:\\s*(\\d+)").find(json)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val cacheRead = Regex("\"cacheReadInputTokens\"\\s*:\\s*(\\d+)").find(json)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L

            val totalUsed = inputTokens + outputTokens + cacheCreation + cacheRead
            if (totalUsed == 0L) return

            // Store for dashboard
            _usageTokens.value = CostCalculator.UsageTokens(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cacheCreationTokens = cacheCreation,
                cacheReadTokens = cacheRead
            )

            // Parse remaining minutes from projection
            val remaining = Regex("\"remainingMinutes\"\\s*:\\s*(\\d+)").find(json)
                ?.groupValues?.get(1)?.toIntOrNull()

            // Estimate percentage: remaining/300min (5h) = remaining fraction
            val pct = if (remaining != null && remaining < 300) {
                ((1.0 - remaining.toDouble() / 300.0) * 100).toInt().coerceIn(0, 100)
            } else {
                // Fallback: burn rate based
                val burnRate = Regex("\"tokensPerMinute\"\\s*:\\s*([\\d.]+)").find(json)
                    ?.groupValues?.get(1)?.toDoubleOrNull() ?: return
                if (burnRate <= 0) return
                val estimatedTotal = burnRate * 300 // 5h in minutes
                ((totalUsed.toDouble() / estimatedTotal) * 100).toInt().coerceIn(0, 100)
            }
            FileLogger.log(TAG, "Usage: ${totalUsed} tokens, ${remaining}min remaining, ${pct}%")
            onUsageUpdate?.invoke(pct, null)
        } catch (e: Exception) {
            FileLogger.error(TAG, "Usage parse failed: ${e.message}", e)
        }
    }

    // ---- Claude Code Stop-hook integration ----

    /**
     * Shell command run via SSH exec to ensure `~/.claude/settings.json` on the
     * remote server contains a `Stop` hook that appends to `/tmp/claude-notify`.
     * Uses `python3` for safe JSON merge (preserves all existing content).
     * Idempotent — checks for our marker string before adding.
     */
    private val ENSURE_HOOK_COMMAND = """
        python3 -c "
import json, os
p = os.path.expanduser('~/.claude/settings.json')
d = {}
if os.path.exists(p):
    with open(p) as f: d = json.load(f)
hooks = d.setdefault('hooks', {})
stop = hooks.setdefault('Stop', [])
marker = 'claude-remote-notify'
cmd = \"echo claude-remote-notify \$(tmux display-message -p '#S' 2>/dev/null || echo unknown) \$(date +%s) >> /tmp/claude-notify\"
want = {'matcher': '', 'hooks': [{'type': 'command', 'command': cmd}]}
def has_marker(e):
    if not isinstance(e, dict): return False
    if marker in str(e.get('command', '')): return True
    for h in e.get('hooks') or []:
        if isinstance(h, dict) and marker in str(h.get('command', '')): return True
    return False
canonical_ok = any(e == want for e in stop if isinstance(e, dict))
stale = [e for e in stop if has_marker(e) and e != want]
if canonical_ok and not stale:
    print('HOOK_EXISTS')
else:
    hooks['Stop'] = [e for e in stop if not has_marker(e)] + [want]
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w') as f: json.dump(d, f, indent=2)
    print('HOOK_FIXED')
" 2>&1 || echo 'HOOK_FAILED'
    """.trimIndent()

    /**
     * Ensure the Claude Code `Stop` hook is present on the remote server. Runs
     * a one-shot SSH exec. Safe to call multiple times — the script is
     * idempotent. Failures are logged but non-fatal (screen-scraping fallback
     * still works).
     */
    private suspend fun ensureStopHook(sshManager: SshManager) {
        try {
            val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val sshSession = sshManager.getSession() ?: return@withContext "NO_SESSION"
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(ENSURE_HOOK_COMMAND)
                ch.inputStream = null
                val input = ch.inputStream
                ch.connect(10_000)
                val out = input.bufferedReader().readText().trim()
                ch.disconnect()
                out
            }
            FileLogger.log(TAG, "Stop hook setup: $result")
        } catch (e: Exception) {
            FileLogger.error(TAG, "Stop hook setup failed: ${e.message}", e)
        }
    }

    /**
     * Start a background SSH exec channel that `tail -f /tmp/claude-notify` and
     * fires [onClaudeNeedsInput] whenever our Stop-hook marker appears for the
     * given tmux session. Marks the session as hook-active in the detector so
     * screen-state polling is skipped.
     *
     * If the watcher channel drops (SSH reconnect), screen-state fallback
     * resumes automatically via [markHookInactive].
     */
    private fun startNotifyWatcher(sessionId: String, tmuxName: String, sshManager: SshManager) {
        notifyWatchers[sessionId]?.cancel()
        notifyWatchers[sessionId] = reconnectScope.launch {
            // Retry loop: the exec channel can die silently (mobile networks,
            // HyperOS battery management kill the socket without dropping the
            // main SSH channel). Previously the watcher exited permanently and
            // hook-based detection was gone until a FULL transport reconnect —
            // background sessions then had no detection path at all.
            var attempt = 0
            while (isActive) {
                try {
                    val sshSession = sshManager.getSession() ?: break
                    val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                    ch.setCommand("touch /tmp/claude-notify && tail -n 0 -f /tmp/claude-notify")
                    ch.inputStream = null
                    val reader = ch.inputStream.bufferedReader()
                    ch.connect(5000)

                    setHookActive(sessionId, true)
                    attempt = 0
                    FileLogger.log(TAG, "Notify watcher started for $sessionId (tmux=$tmuxName)")

                    while (isActive && ch.isConnected) {
                        val line = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            reader.readLine()
                        } ?: break
                        if (!line.contains("claude-remote-notify")) continue
                        if (!line.contains(tmuxName)) continue
                        FileLogger.log(TAG, "Stop hook fired for $sessionId: $line")
                        val isActiveTab = tabManager.activeTabId.value == sessionId
                        fireNeedsInput(sessionId, "Claude is ready for input", isActiveTab)
                        updateActivity(sessionId, SessionActivity.WAITING_FOR_INPUT)
                    }
                    ch.disconnect()
                } catch (e: Exception) {
                    FileLogger.error(TAG, "Notify watcher failed for $sessionId: ${e.message}", e)
                } finally {
                    setHookActive(sessionId, false)
                }
                if (!isActive) break
                attempt++
                val backoffMs = (5_000L * attempt).coerceAtMost(30_000L)
                FileLogger.log(TAG, "Notify watcher retrying for $sessionId in ${backoffMs}ms (attempt $attempt)")
                kotlinx.coroutines.delay(backoffMs)
            }
            FileLogger.log(TAG, "Notify watcher stopped for $sessionId")
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

        synchronized(bufferLock) { outputBuffers[sessionId] = StringBuilder() }
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
            updateActivity(sessionId, SessionActivity.WAITING_FOR_INPUT)
            onSessionActive?.invoke(session)
            attachSessionRuntime(sessionId, session.tmuxSessionName)
            // Persist session for app-restart and server-reboot recovery.
            sessionStorage?.upsert(SessionStorage.fromClaudeSession(session))
            connections[sessionId]?.let { conn ->
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
            _sessionActivities.value[previousId] == SessionActivity.APPROVAL_NEEDED) {
            updateActivity(previousId, SessionActivity.WAITING_FOR_INPUT)
        }
        tabManager.switchTab(id)
        promptDetector.onUserInput(id)
        // Re-verify the Claude session UUID whenever we (re)enter a session.
        // While this tab was in the background Claude may have rotated its
        // session id (/clear, /compact, /resume) — leaving confirmedUuids
        // pointing at a dead .jsonl. Clearing it here lets the next
        // transcriptFlow() call (UI re-subscribes on active-tab change) fire a
        // fresh kick-probe and re-point the transcript stream at the live file.
        // a08359c cleared this on reconnect but missed plain tab switches,
        // which is why the chat only refreshed after an app restart.
        confirmedUuids.remove(id)
        val tail = synchronized(bufferLock) {
            val buf = outputBuffers[id] ?: return@synchronized ""
            val len = buf.length
            if (len > 2048) buf.substring(len - 2048) else buf.toString()
        }
        promptDetector.suppressFor(2000)
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
        val conn = connections[sessionId] ?: return
        if (cols <= 1 || rows <= 0) return
        // Sync pty geometry to the current view first (no-op if unchanged) —
        // each session's pty keeps the size from the last time *it* was
        // active, which may be stale after switching between sessions.
        conn.resize(cols, rows)
        val tmuxName = tabManager.getTab(sessionId)?.tmuxSessionName
        reconnectScope.launch {
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

    private val reconnectScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

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
     * "Claude is ready" notification body. Reads whatever transcript stream
     * already exists (one is running once the session has been opened); null
     * if none yet, in which case the caller keeps the generic hint.
     */
    fun lastAssistantText(sessionId: String): String? {
        val stream = synchronized(transcriptLock) { transcriptStreams[sessionId] } ?: return null
        return stream.entries.value
            .lastOrNull { it is TranscriptEntry.AssistantText }
            ?.let { (it as TranscriptEntry.AssistantText).text }
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Ensure a transcript stream is running for a connected session,
     * regardless of UI view. Idempotent (getOrPut + start()'s own guard +
     * idempotent collector). Called from [attachSessionRuntime] so the last
     * assistant message is available for notifications even when the user
     * only ever uses the Raw terminal view (previously the stream was started
     * lazily by the Chat-view transcriptFlow subscription, so Raw-view
     * notifications had no body).
     */
    private fun ensureTranscriptStream(sessionId: String) {
        val tab = tabManager.getTab(sessionId) ?: return
        val stream = synchronized(transcriptLock) {
            val s = transcriptStreams.getOrPut(sessionId) {
                TranscriptStream(tab.server, tab.folder, reconnectScope) { connections[sessionId]?.getSession() }
            }
            startContextTokenCollector(sessionId, s)
            s
        }
        tab.claudeSessionId?.let { stream.start(it) }
    }

    fun transcriptFlow(sessionId: String): kotlinx.coroutines.flow.StateFlow<List<TranscriptEntry>> {
        val tab = tabManager.getTab(sessionId)
            ?: return kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        val stream = synchronized(transcriptLock) {
            val s = transcriptStreams.getOrPut(sessionId) {
                TranscriptStream(tab.server, tab.folder, reconnectScope) { connections[sessionId]?.getSession() }
            }
            // Derive the ctx-window % from this stream's token usage. Inside the
            // lock so it binds to the exact stream instance and stays atomic with
            // disconnectSession's teardown.
            startContextTokenCollector(sessionId, s)
            s
        }
        val uuid = tab.claudeSessionId
        if (uuid != null) stream.start(uuid)
        // One-shot pid-probe to correct the client-generated UUID before the
        // 15 s reconcile loop fires. Only runs until the UUID is confirmed by
        // at least one server-side probe — after that, repeated calls to
        // transcriptFlow() (e.g. every time the user toggles to the Transcript
        // tab) skip this block entirely. Without this guard the probe was fired
        // on every call, each one potentially restarting the stream and blanking
        // the transcript for several seconds while the new SSH tail reconnected.
        val alreadyConfirmed = uuid != null && confirmedUuids[sessionId] == uuid
        val sshMan = connections[sessionId]
        if (!alreadyConfirmed && sshMan != null && sshMan.isConnected) {
            reconnectScope.launch {
                try {
                    val real = readRealSessionId(sshMan, tab.tmuxSessionName)
                    val current = tabManager.getTab(sessionId)
                    if (real != null && current != null && current.claudeSessionId != real) {
                        val claimedByOther = tabManager.tabs.value.any {
                            it.id != sessionId && it.claudeSessionId == real
                        }
                        if (claimedByOther) {
                            FileLogger.log(TAG, "Skip kick-sync UUID $real for $sessionId — already owned by another tab")
                        } else {
                            FileLogger.log(TAG, "Session $sessionId UUID kick-sync from pid-probe: ${current.claudeSessionId} -> $real")
                            tabManager.updateClaudeSessionId(sessionId, real)
                            sessionStorage?.upsert(SessionStorage.fromClaudeSession(current.copy(claudeSessionId = real)))
                            notifyClaudeSessionIdChanged(sessionId, real)
                            confirmedUuids[sessionId] = real
                        }
                    } else if (real != null && current != null) {
                        // UUID already matches — mark confirmed so future calls skip the probe.
                        confirmedUuids[sessionId] = real
                    }
                } catch (_: Exception) {}
            }
        }
        return stream.entries
    }

    /**
     * Diagnostic status of the transcript tail for [sessionId] — what it's doing
     * / why no data yet (connecting, retry+error, "no transcript data yet").
     * Null once entries flow. Shown in the "Waiting for transcript…" state.
     */
    fun transcriptStatusFlow(sessionId: String): kotlinx.coroutines.flow.StateFlow<String?> {
        val tab = tabManager.getTab(sessionId)
            ?: return kotlinx.coroutines.flow.MutableStateFlow(null)
        val stream = synchronized(transcriptLock) {
            transcriptStreams.getOrPut(sessionId) {
                TranscriptStream(tab.server, tab.folder, reconnectScope) { connections[sessionId]?.getSession() }
            }
        }
        return stream.status
    }

    /**
     * Lazy poller for OMC remote state (active skill, in-flight subagents).
     * Polls two small state files via SSH stat+cat every ~5 s; idle traffic
     * stays under ~50 B/s. Cached per session and cleaned up on disconnect.
     */
    fun remoteStatusFlow(sessionId: String): kotlinx.coroutines.flow.StateFlow<RemoteSessionStatus> {
        val tab = tabManager.getTab(sessionId)
            ?: return kotlinx.coroutines.flow.MutableStateFlow(RemoteSessionStatus())
        val poller = synchronized(statusLock) {
            statusPollers.getOrPut(sessionId) {
                SessionStatusPoller(
                    server = tab.server,
                    cwd = tab.folder,
                    claudeSessionIdProvider = { tabManager.getTab(sessionId)?.claudeSessionId },
                    scope = reconnectScope
                )
            }
        }
        poller.start()
        return poller.status
    }

    /**
     * Called when the Claude Code session UUID rotates (e.g. user invoked
     * `/resume` or `/clear`). Restarts the transcript stream against the new
     * file so the UI keeps showing the active conversation.
     */
    private fun notifyClaudeSessionIdChanged(sessionId: String, newUuid: String?) {
        val stream = synchronized(transcriptLock) { transcriptStreams[sessionId] } ?: return
        if (newUuid != null) stream.start(newUuid)
    }

    /**
     * Resolve a server's configured [ServerTransport] into the EFFECTIVE server
     * actually handed to the connection layer. AUTO prefers Tailscale when its
     * host is set and reachable (a quick TCP probe over the system VPN), else
     * falls back to the Cloudflare path — so a Starlink user with the Tailscale
     * VPN up gets the roaming-resilient path automatically, and anyone without
     * it still connects over CF. Resolved fresh on every (re)connect so the best
     * path is re-picked after a drop.
     */
    private suspend fun resolveTransport(server: com.clauderemote.model.SshServer): com.clauderemote.model.SshServer {
        val chosen = when (server.transport) {
            com.clauderemote.model.ServerTransport.AUTO ->
                if (server.hasTailscale && tailscaleReachable(server))
                    com.clauderemote.model.ServerTransport.TAILSCALE
                else com.clauderemote.model.ServerTransport.CLOUDFLARE
            else -> server.transport
        }
        val eff = server.forTransport(chosen)
        if (eff.host != server.host || eff.useCloudflareProxy != server.useCloudflareProxy) {
            FileLogger.log(TAG, "Transport for ${server.name}: $chosen -> ${eff.host} (cf=${eff.useCloudflareProxy})")
        }
        return eff
    }

    /** Fast TCP reachability probe of the Tailscale endpoint (system VPN route). */
    private suspend fun tailscaleReachable(server: com.clauderemote.model.SshServer): Boolean =
        withContext(Dispatchers.IO) {
            try {
                java.net.Socket().use {
                    it.connect(java.net.InetSocketAddress(server.tailscaleHost, server.port), 2000)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

    private suspend fun connectSsh(session: ClaudeSession, isNewTmuxSession: Boolean) {
        val sshManager = SshManager(serverStorage)
        connections[session.id] = sshManager

        // Track last output time for burst detection
        var lastOutputTime = 0L
        var burstMode = true // Start in burst mode (tmux attach sends lots of data)

        fun emit(text: String) {
            appendToBuffer(session.id, text)
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
            promptDetector.onOutput(session.id, text)

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
            promptDetector.parseClaudeWorking(session.id)?.let { working ->
                val next = if (working) SessionActivity.WORKING else SessionActivity.WAITING_FOR_INPUT
                // Claude actively working → a new turn started; re-arm the
                // notify latch so the next idle/approval can notify again
                // even if the user never typed in this app (dismissed the
                // notification, answered from another client, …).
                if (working) promptDetector.onClaudeWorking(session.id)
                // FIX 3: when the statusline says "not working" (→ WAITING_FOR_INPUT),
                // do NOT overwrite APPROVAL_NEEDED — the permission dialog is still on
                // screen and the OMC statusline shows no elapsed time while it waits.
                // A genuine WORKING result from the statusline may still override APPROVAL.
                if (next == SessionActivity.WAITING_FOR_INPUT &&
                    _sessionActivities.value[session.id] == SessionActivity.APPROVAL_NEEDED) return@let
                // Grace: a WORKING statusline within 8s of APPROVAL being asserted
                // is more likely a STALE render flowing back through the buffer
                // (kickRedraw / replay re-printing an old "thinking" segment) than
                // a real resume. The detector re-confirms APPROVAL from the screen
                // every ~3s while the dialog is up (refreshing the timestamp), so
                // a genuine resume still takes over within a beat of the dialog
                // actually closing.
                if (next == SessionActivity.WORKING &&
                    _sessionActivities.value[session.id] == SessionActivity.APPROVAL_NEEDED &&
                    System.currentTimeMillis() - (lastApprovalAt[session.id] ?: 0L) < 8_000L) return@let
                updateActivity(session.id, next)
            }
            // ctx % is derived from the transcript (startContextTokenCollector),
            // not scraped. We still read the statusline's `ctx:NN%` here, but
            // only to CALIBRATE the window size for this session: with the live
            // token count we can back out whether it's a 200k or 1M window and
            // cache it. One sighting is enough; afterwards the transcript drives
            // the displayed %.
            if (session.id in sawWorkSinceAttach && !contextWindowTokens.containsKey(session.id)) {
                // Gate on sawWork so we only pair a FRESH statusline ctx:NN% with
                // the live token count. Calibrating off a stale scrollback pct
                // (e.g. 5% paired with 180k live tokens) would mis-snap the
                // window to 1M and stick the chip wrong for the whole session.
                val ctxPct = promptDetector.parseContextPercent(session.id, text)
                val tokens = latestContextTokens[session.id]
                if (ctxPct != null && ctxPct in 1..100 && tokens != null && tokens > 0L) {
                    val est = tokens.toDouble() / (ctxPct / 100.0)
                    // Snap to the nearest tier (the two windows are 5x apart, so
                    // the geometric midpoint ~447k cleanly separates them).
                    contextWindowTokens[session.id] = if (est > 450_000L) 1_000_000L else 200_000L
                }
            }
            // 5h / week usage are account-level (not in the transcript) so they
            // stay scraped from the OMC statusline — but only once the session
            // has actually worked, so we don't surface stale scrollback values.
            if (session.id in sawWorkSinceAttach) {
                val usage = promptDetector.parseUsage(session.id, text)
                if (usage != null) {
                    // 5h / week limits are account-wide, so key them by SERVER,
                    // not session — switching to another session on the same
                    // server then keeps the values instead of resetting to "—"
                    // until that session happens to fetch them itself.
                    val serverId = session.server.id
                    usage["session"]?.let { s ->
                        _sessionUsagePercents.update { it + (serverId to s) }
                    }
                    usage["week"]?.let { w ->
                        _weekUsagePercents.update { it + (serverId to w) }
                    }
                    usage["session_reset_min"]?.let { m ->
                        _sessionResetMin.update { it + (serverId to m) }
                    }
                    usage["week_reset_min"]?.let { m ->
                        _weekResetMin.update { it + (serverId to m) }
                    }
                    onUsageUpdate?.invoke(usage["session"], usage["week"])
                }
            }
        }

        sshManager.connect(
            resolveTransport(session.server),
            onOutput = { data -> emit(data) },
            onConnectionLost = {
                // Auto-reconnect with tmux reattach
                tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
                updateActivity(session.id, SessionActivity.DISCONNECTED)
                reconnectScope.launch {
                    autoReconnect(session, ::emit)
                }
            }
        )

        // Wait for shell prompt (detect $ or # or >, max 3s)
        waitForShellPrompt(session.id, 3000)

        // Startup command
        if (session.server.startupCommand.isNotBlank()) {
            sshManager.sendInput(session.server.startupCommand + "\n")
            waitForShellPrompt(session.id, 3000)
        }

        // Ensure Claude Code's Stop hook is configured → enables hook-based
        // idle detection (fast, reliable) instead of screen-state polling.
        ensureStopHook(sshManager)

        // Tmux
        sendTmuxCommand(sshManager, session, isNewTmuxSession)
        promptDetector.suppressFor(3000) // suppress during tmux screen redraw

        // Apply saved terminal dimensions — TerminalView won't fire onResize
        // because its size hasn't changed, but the new SSH channel defaults to 80x24.
        terminalSizes[session.id]?.let { (cols, rows) ->
            sshManager.resize(cols, rows)
        }
    }

    private fun sendTmuxCommand(sshManager: SshManager, session: ClaudeSession, isNew: Boolean) {
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
                ENC=${'$'}(echo "${'$'}E" | sed 's|/|-|g')
                [ -f "${'$'}HOME/.claude/projects/${'$'}ENC/$uuid.jsonl" ] && echo YES || echo NO
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
        withContext(Dispatchers.IO) {
            try {
                SshSessionHelper.withSession(server, timeout = 5000) { sess ->
                    val escaped = tmuxName.replace("'", "'\\''")
                    // `tmux has-session` first to avoid the display-message error noise
                    // when the session doesn't exist.
                    val checkCmd = "tmux has-session -t '$escaped' 2>/dev/null && " +
                        "tmux display-message -p -t '$escaped' '#{pane_current_path}' 2>/dev/null " +
                        "|| echo __NO_SESSION__"
                    val ch = sess.openChannel("exec") as ChannelExec
                    ch.setCommand(checkCmd)
                    ch.inputStream = null
                    val input = ch.inputStream
                    ch.connect(4000)
                    val out = try {
                        input.bufferedReader().readText().trim()
                    } finally {
                        try { ch.disconnect() } catch (_: Throwable) {}
                    }
                    when {
                        out == "__NO_SESSION__" || out.isEmpty() -> null
                        else -> {
                            // Normalise both paths: strip trailing slash, expand leading ~
                            val panePath = out.trimEnd('/')
                            val histPath = cwd.trimEnd('/')
                            panePath == histPath
                        }
                    }
                }
            } catch (e: Exception) {
                FileLogger.error(TAG, "tmuxPaneMatchesCwd probe failed for $tmuxName: ${e.message}", e)
                null // fail-open
            }
        }

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
            confirmedUuids.remove(session.id)
            for (attempt in 1..maxAttempts) {
                emit("\r\n\u001B[33mConnection lost. Reconnecting ($attempt/$maxAttempts)...\u001B[0m\r\n")
                FileLogger.log(TAG, "Auto-reconnect attempt $attempt/$maxAttempts for ${session.id}")
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
                if (tabManager.getTab(session.id) == null) return

                try {
                    // Clean up old connection
                    connections[session.id]?.disconnect()
                    connections.remove(session.id)

                    val sshManager = SshManager(serverStorage)
                    connections[session.id] = sshManager

                    sshManager.connect(
                        resolveTransport(session.server),
                        onOutput = { data -> emit(data) },
                        onConnectionLost = {
                            tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
                            reconnectScope.launch { autoReconnect(session, emit) }
                        }
                    )

                    // Wait for shell prompt, clear garbage, then attach tmux
                    waitForShellPrompt(session.id, 3000)
                    sshManager.sendInput("\u0003\n") // Ctrl-C + Enter to clear
                    kotlinx.coroutines.delay(100)
                    sendTmuxCommand(sshManager, session, false)
                    promptDetector.suppressFor(3000) // suppress during tmux screen redraw after reconnect

                    // Re-send terminal dimensions — the new SshManager defaults
                    // to 80x24 but the TerminalView hasn't changed size, so
                    // onResize won't fire.  Without this, tmux renders at 80x24
                    // leaving a gap below the content.
                    terminalSizes[session.id]?.let { (cols, rows) ->
                        kotlinx.coroutines.delay(200) // let tmux attach settle
                        sshManager.resize(cols, rows)
                    }

                    tabManager.updateTabStatus(session.id, SessionStatus.ACTIVE)
                    updateActivity(session.id, SessionActivity.WAITING_FOR_INPUT)
                    onSessionActive?.invoke(session)
                    // Restart ALL per-session loops, not just watcher+refresh —
                    // usage/git/latency pollers may have died during the outage.
                    attachSessionRuntime(session.id, session.tmuxSessionName)
                    emit("\r\n\u001B[32mReconnected!\u001B[0m\r\n")
                    FileLogger.log(TAG, "Auto-reconnect succeeded for ${session.id}")
                    flushPendingInputs(session.id)
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
            appendToBuffer(session.id, warning)
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
            appendToBuffer(session.id, text)
            val isActive = tabManager.activeTabId.value == session.id
            if (isActive) {
                onTerminalOutput?.invoke(session.id, text)
            }
            promptDetector.onOutput(session.id, text)
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
        moshConnections[session.id] = moshManager
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
            val lastLine = synchronized(bufferLock) {
                val buf = outputBuffers[sessionId]
                if (buf == null || buf.isEmpty()) ""
                else {
                    val len = buf.length
                    buf.substring(maxOf(0, len - 80)).trimEnd()
                }
            }
            if (lastLine.isNotEmpty() && lastLine.any { it in promptChars }) return
            kotlinx.coroutines.delay(50)
        }
    }

    private fun appendToBuffer(sessionId: String, data: String) {
        synchronized(bufferLock) {
            val buf = outputBuffers[sessionId] ?: return
            buf.append(data)
            if (buf.length > MAX_BUFFER) {
                val tail = buf.substring(buf.length - MAX_BUFFER)
                buf.clear()
                buf.append(tail)
            }
        }
    }

    fun clearBuffer(sessionId: String) {
        synchronized(bufferLock) { outputBuffers[sessionId]?.clear() }
    }

    private fun warnNoConnection(sessionId: String) {
        val msg = "\r\n\u001B[31mNo connection — input dropped. Try reconnecting.\u001B[0m\r\n"
        appendToBuffer(sessionId, msg)
        if (tabManager.activeTabId.value == sessionId) {
            onTerminalOutput?.invoke(sessionId, msg)
        }
    }

    fun sendInput(sessionId: String, data: String) {
        promptDetector.onUserInput(sessionId)
        // Try mosh first, then SSH
        val mosh = moshConnections[sessionId]
        if (mosh != null && mosh.isConnected) {
            updateActivity(sessionId, SessionActivity.WORKING)
            mosh.sendInput(data)
            return
        }
        val conn = connections[sessionId]
        if (conn == null || !conn.isConnected) {
            queueInput(sessionId, data)
            return
        }
        updateActivity(sessionId, SessionActivity.WORKING)
        conn.sendInput(data)
    }

    fun sendBytes(sessionId: String, data: ByteArray) {
        promptDetector.onUserInput(sessionId)
        val mosh = moshConnections[sessionId]
        if (mosh != null && mosh.isConnected) {
            updateActivity(sessionId, SessionActivity.WORKING)
            mosh.sendBytes(data)
            return
        }
        val conn = connections[sessionId]
        if (conn == null || !conn.isConnected) { warnNoConnection(sessionId); return }
        updateActivity(sessionId, SessionActivity.WORKING)
        conn.sendBytes(data)
    }

    // ---- Offline input queue ----

    private fun queueInput(sessionId: String, data: String) {
        val queue = pendingInputs.getOrPut(sessionId) { mutableListOf() }
        queue.add(data)
        _pendingCounts.update { it + (sessionId to queue.size) }
        val msg = "\r\n\u001B[33mQueued (${queue.size} pending) — will send on reconnect\u001B[0m\r\n"
        appendToBuffer(sessionId, msg)
        if (tabManager.activeTabId.value == sessionId) {
            onTerminalOutput?.invoke(sessionId, msg)
        }
    }

    fun clearPendingInputs(sessionId: String) {
        pendingInputs.remove(sessionId)
        _pendingCounts.update { it - sessionId }
    }

    private fun flushPendingInputs(sessionId: String) {
        val queue = pendingInputs.remove(sessionId) ?: return
        _pendingCounts.update { it - sessionId }
        if (queue.isEmpty()) return
        val conn = connections[sessionId] ?: return
        reconnectScope.launch {
            for (input in queue) {
                conn.sendInput(input)
                kotlinx.coroutines.delay(300) // small delay between queued messages
            }
            val msg = "\r\n\u001B[32mFlushed ${queue.size} queued message(s)\u001B[0m\r\n"
            appendToBuffer(sessionId, msg)
            if (tabManager.activeTabId.value == sessionId) {
                onTerminalOutput?.invoke(sessionId, msg)
            }
        }
    }

    fun resize(sessionId: String, cols: Int, rows: Int) {
        terminalSizes[sessionId] = cols to rows
        connections[sessionId]?.resize(cols, rows)
    }

    fun sendClaudeCommand(sessionId: String, command: String) {
        val conn = connections[sessionId]
        if (conn == null || !conn.isConnected) {
            queueInput(sessionId, command)
            return
        }
        FileLogger.log(TAG, "sendClaudeCommand: ${command.length} bytes to $sessionId")
        promptDetector.onUserInput(sessionId)
        updateActivity(sessionId, SessionActivity.WORKING)
        conn.sendInput(command)
    }

    fun switchModel(sessionId: String, model: ClaudeModel) {
        sendInput(sessionId, ClaudeConfig.modelSwitchCommand(model))
    }

    fun sendEscape(sessionId: String) {
        sendInput(sessionId, ClaudeConfig.escapeSequence())
    }

    /**
     * Scroll the tmux pane via copy-mode (NOT via stdin) so the agent's input
     * is never disturbed. Enters copy-mode then sends one page-up / page-down.
     * Page-down at the bottom of history auto-exits copy-mode (back to live).
     * Runs off the UI thread on the IO scope.
     */
    // Per-session mutex so rapid scroll taps queue rather than storm SSH MaxSessions.
    private val scrollMutexes = mutableMapOf<String, Mutex>()
    private fun scrollMutex(sessionId: String) =
        synchronized(scrollMutexes) { scrollMutexes.getOrPut(sessionId) { Mutex() } }

    fun tmuxScroll(sessionId: String, up: Boolean) {
        val tmuxName = tabManager.getTab(sessionId)?.tmuxSessionName ?: return
        reconnectScope.launch {
            scrollMutex(sessionId).withLock {
                try {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val sshSession = connections[sessionId]?.getSession() ?: return@withContext
                        val escaped = tmuxName.replace("'", "'\\''")
                        val key = if (up) "page-up" else "page-down"
                        val cmd = "tmux copy-mode -e -t '$escaped'; tmux send-keys -t '$escaped' -X $key"
                        val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                        ch.setCommand(cmd)
                        ch.inputStream = null
                        val input = ch.inputStream
                        try {
                            ch.connect(1500)
                            input.bufferedReader().readText()
                        } finally {
                            ch.disconnect()
                        }
                    }
                } catch (e: Exception) {
                    FileLogger.error(TAG, "tmuxScroll failed for $sessionId: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Upload a file to the remote server for the given session.
     * Returns the remote path of the uploaded file.
     *
     * If autoReconnect is already running (SAF picker killed the socket),
     * waits for it to finish.  Never kills the connection itself — that
     * was causing a cascade of 3 failed reconnects.
     */
    suspend fun uploadFile(sessionId: String, bytes: ByteArray, fileName: String): String {
        val deadline = System.currentTimeMillis() + 20_000L
        var lastException: Exception? = null

        while (System.currentTimeMillis() < deadline) {
            val c = connections[sessionId]
            if (c != null && c.isConnected) {
                try {
                    val remoteDir = "/tmp/claude-uploads"
                    val remotePath = c.uploadFile(bytes, remoteDir, fileName)
                    FileLogger.log(TAG, "File uploaded: $remotePath (${bytes.size} bytes)")
                    return remotePath
                } catch (e: Exception) {
                    lastException = e
                    FileLogger.error(TAG, "Upload exec failed for $sessionId: ${e.message}", e)
                    // Don't kill the connection — if transport is truly dead,
                    // the read loop will detect it via ServerAliveInterval and
                    // autoReconnect will handle recovery.
                }
            }
            kotlinx.coroutines.delay(1000)
        }
        throw lastException ?: IllegalStateException("SSH not ready for $sessionId (upload timeout)")
    }

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
        promptDetector.markInteracted(sessionId)

        // Invalidate the confirmed UUID so the next transcriptFlow() call fires
        // a fresh kick-probe. If claude restarted with a new session UUID during
        // the outage (e.g. user ran /clear or /resume), the probe will adopt the
        // new UUID. Without this, confirmedUuids retains the stale UUID forever
        // and the transcript stream keeps tailing a non-existent .jsonl file.
        confirmedUuids.remove(sessionId)

        // Clean up old connection
        connections[sessionId]?.disconnect()
        connections.remove(sessionId)

        tabManager.updateTabStatus(sessionId, SessionStatus.CONNECTING)

        try {
            connectSsh(session, false) // attach to existing tmux
            tabManager.updateTabStatus(sessionId, SessionStatus.ACTIVE)
            // Clear the DISCONNECTED activity left over from restore/disconnect —
            // otherwise the session shows "Offline" (badge + status + empty
            // chips) even though it's connected. autoReconnect already does
            // this; reconnectSession (restore + manual) was missing it. The real
            // working/idle state is then driven by the statusline parse in emit()
            // as soon as output flows.
            updateActivity(sessionId, SessionActivity.WAITING_FOR_INPUT)
            onSessionActive?.invoke(session)
            // Restart ALL per-session loops (usage/git/latency pollers included
            // — they may have died during the outage), not just watcher+refresh.
            attachSessionRuntime(sessionId, session.tmuxSessionName)
            FileLogger.log(TAG, "Reconnected: $sessionId")
        } catch (e: Exception) {
            FileLogger.error(TAG, "Reconnect failed", e)
            tabManager.updateTabStatus(sessionId, SessionStatus.ERROR)
            updateActivity(sessionId, SessionActivity.DISCONNECTED)
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
                            com.clauderemote.connection.TmuxManager.killSession(
                                cleanupConn.getSession() ?: error("no ssh"),
                                session.tmuxSessionName
                            )
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
        usagePollingJobs.remove(sessionId)?.cancel()
        gitStatusJobs.remove(sessionId)?.cancel()
        latencyPollingJobs.remove(sessionId)?.cancel()
        notifyWatchers.remove(sessionId)?.cancel()
        sessionIdRefreshJobs.remove(sessionId)?.cancel()
        setHookActive(sessionId, false)
        connections[sessionId]?.disconnect()
        connections.remove(sessionId)
        moshConnections[sessionId]?.disconnect()
        moshConnections.remove(sessionId)
        synchronized(bufferLock) { outputBuffers.remove(sessionId) }
        synchronized(transcriptLock) {
            transcriptStreams.remove(sessionId)?.let { stream ->
                reconnectScope.launch { stream.stop() }
            }
            // Cancel the ctx collector inside the same lock so it can't be
            // relaunched against this now-stopped stream by a racing
            // transcriptFlow.
            contextTokenCollectors.remove(sessionId)?.cancel()
        }
        latestContextTokens.remove(sessionId)
        contextWindowTokens.remove(sessionId)
        sawWorkSinceAttach.remove(sessionId)
        synchronized(statusLock) {
            statusPollers.remove(sessionId)?.stop()
        }
        promptDetector.removeSession(sessionId)
        pendingInputs.remove(sessionId)
        terminalSizes.remove(sessionId)
        confirmedUuids.remove(sessionId)
        _sessionActivities.update { it - sessionId }
        _contextPercents.update { it - sessionId }
        // Usage maps are keyed by server (account-wide), so don't clear them on
        // a single session's disconnect — other sessions on that server still
        // want the values.
        _latencies.update { it - sessionId }
        _gitStatuses.update { it - sessionId }
        _pendingCounts.update { it - sessionId }
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
        connections.values.forEach { it.disconnect() }
        connections.clear()
        synchronized(bufferLock) { outputBuffers.clear() }
    }

    fun getConnection(sessionId: String): SshManager? = connections[sessionId]

    suspend fun renameTmuxSession(sessionId: String, oldName: String, newName: String) {
        withContext(Dispatchers.IO) {
            try {
                val sshSession = connections[sessionId]?.getSession() ?: return@withContext
                com.clauderemote.connection.TmuxManager.renameSession(sshSession, oldName, newName)
                FileLogger.log(TAG, "Tmux renamed: $oldName → $newName")
                // Persist the new tmux name + re-sync server snapshot so the
                // restore service uses it after a reboot.
                val tab = tabManager.getTab(sessionId)
                if (tab != null && sessionStorage != null) {
                    val updated = tab.copy(tmuxSessionName = newName)
                    sessionStorage.upsert(SessionStorage.fromClaudeSession(updated))
                    connections[sessionId]?.let { pushSessionsToServer(it, tab.server.id) }
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
        MARKER="claude-remote-restore-v3"
        touch "${'$'}LOCK"
        echo "[${'$'}(date -u +%FT%TZ)] install: invoked by client" >> "${'$'}HOME/.claude-remote/install.log"
        if ! grep -q "${'$'}MARKER" "${'$'}SCRIPT" 2>/dev/null; then
            cat > "${'$'}SCRIPT" <<'RESTORE_EOF'
#!/usr/bin/env bash
# claude-remote-restore-v3 — recreates tmux+claude sessions from sessions.json (snapshot under flock)
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
        ARGS=("claude")
        case "${'$'}MODEL" in
            OPUS) ARGS+=(--model opus);;
            SONNET) ARGS+=(--model sonnet);;
            HAIKU) ARGS+=(--model haiku);;
        esac
        case "${'$'}MODE" in
            YOLO) ARGS+=(--dangerously-skip-permissions);;
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
        if tmux new-session -d -s "${'$'}TMUX_NAME" -c "${'$'}FOLDER_EXP" \
            "tmux set-option -g mouse on; tmux set-option -g history-limit 100000; ${'$'}CMD"; then
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
                                "tmux set-option -g mouse on; tmux set-option -g history-limit 100000; ${'$'}CMD"
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
# claude-remote-restore-v3 — drift daemon: pulls real claude session_ids
# from per-pid state files into sessions.json so the client doesn't have
# to. Runs every minute via systemd-user timer.
set -u
LOG="${'$'}HOME/.claude-remote/drift.log"
exec >> "${'$'}LOG" 2>&1
echo "----- ${'$'}(date -u +%FT%TZ) drift start -----"
SF="${'$'}HOME/.claude-remote/sessions.json"
LOCK="${'$'}HOME/.claude-remote/sessions.lock"
[ -f "${'$'}SF" ] || { echo "no sessions.json"; exit 0; }
command -v tmux >/dev/null 2>&1 || { echo "no tmux"; exit 0; }
command -v jq >/dev/null 2>&1 || { echo "no jq"; exit 0; }
touch "${'$'}LOCK"

# Walk the tmux pane's process tree to find the claude process — pane_pid
# is sometimes bash (when claude was launched by a shell command), and
# claude is a grandchild. Recursive descent finds the right pid.
find_claude_descendant() {
    local p=${'$'}1
    if [ "${'$'}(ps -o comm= -p "${'$'}p" 2>/dev/null)" = "claude" ]; then
        echo "${'$'}p"; return 0
    fi
    local c r
    for c in ${'$'}(pgrep -P "${'$'}p" 2>/dev/null); do
        r=${'$'}(find_claude_descendant "${'$'}c")
        if [ -n "${'$'}r" ]; then echo "${'$'}r"; return 0; fi
    done
}

# Build {tmuxName: realSessionId} from claude's per-pid state files.
MAP="{}"
for s in ${'$'}(tmux list-sessions -F '#{session_name}' 2>/dev/null); do
    pane_pid=${'$'}(tmux list-panes -t "${'$'}s" -F '#{pane_pid}' 2>/dev/null | head -1)
    [ -n "${'$'}pane_pid" ] || continue
    pid=${'$'}(find_claude_descendant "${'$'}pane_pid")
    [ -n "${'$'}pid" ] || { echo "skip ${'$'}s: no claude descendant of pid ${'$'}pane_pid"; continue; }
    sf="${'$'}HOME/.claude/sessions/${'$'}pid.json"
    [ -f "${'$'}sf" ] || { echo "skip ${'$'}s: no ${'$'}sf"; continue; }
    sid=${'$'}(jq -r .sessionId "${'$'}sf" 2>/dev/null)
    if [ -n "${'$'}sid" ] && [ "${'$'}sid" != "null" ]; then
        MAP=${'$'}(echo "${'$'}MAP" | jq --arg n "${'$'}s" --arg sid "${'$'}sid" '. + {(${'$'}n): ${'$'}sid}')
    fi
done
echo "MAP=${'$'}MAP"
(
    flock -x 9
    OLD=${'$'}(cat "${'$'}SF")
    NEW=${'$'}(echo "${'$'}OLD" | jq --argjson map "${'$'}MAP" '
        map(. as ${'$'}e |
            if (${'$'}map[${'$'}e.tmuxSessionName] // null) != null
               and ${'$'}map[${'$'}e.tmuxSessionName] != ${'$'}e.claudeSessionId
            then . + {claudeSessionId: ${'$'}map[${'$'}e.tmuxSessionName]}
            else .
            end)
    ')
    if [ "${'$'}NEW" != "${'$'}OLD" ]; then
        echo "${'$'}NEW" > "${'$'}SF.tmp" && mv "${'$'}SF.tmp" "${'$'}SF"
        echo "[${'$'}(date -u +%FT%TZ)] drift: updated UUIDs"
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
        val serverId = tabManager.tabs.value.firstOrNull { connections[it.id] === sshManager }?.server?.id
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
                // Atomic write + append-only push log so server-side debugging
                // can confirm whether/when the client actually synced.
                ch.setCommand(
                    "mkdir -p \"\$HOME/.claude-remote\" && " +
                    "cat > \"\$HOME/.claude-remote/sessions.json.tmp\" && " +
                    "mv \"\$HOME/.claude-remote/sessions.json.tmp\" \"\$HOME/.claude-remote/sessions.json\" && " +
                    "echo \"[\$(date -u +%FT%TZ)] push: ${payload.length} bytes for ${serverId.replace("\"", "")}\" >> \"\$HOME/.claude-remote/push.log\""
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
            synchronized(bufferLock) { outputBuffers[session.id] = StringBuilder() }
            tabManager.addTab(session)
            tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
            updateActivity(session.id, SessionActivity.DISCONNECTED)
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
    suspend fun downloadFile(sessionId: String, remotePath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val conn = connections[sessionId] ?: return@withContext null
            val sshSession = conn.getSession() ?: return@withContext null
            val sftp = sshSession.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
            sftp.connect(5000)
            try {
                val home = sftp.home ?: "~"
                val resolved = when {
                    remotePath == "~"              -> home
                    remotePath.startsWith("~/")    -> home + remotePath.substring(1)
                    remotePath.startsWith("/")     -> remotePath
                    else                           -> "$home/$remotePath"
                }
                val attrs = sftp.lstat(resolved)
                if (attrs.size > DOWNLOAD_SIZE_LIMIT) {
                    FileLogger.log(TAG, "Download refused: $resolved is ${attrs.size} bytes (limit $DOWNLOAD_SIZE_LIMIT)")
                    return@withContext DOWNLOAD_TOO_LARGE
                }
                val out = java.io.ByteArrayOutputStream()
                sftp.get(resolved, out)
                out.toByteArray()
            } finally {
                sftp.disconnect()
            }
        } catch (e: Exception) {
            FileLogger.error(TAG, "Download file failed: $remotePath", e)
            null
        }
    }

    fun getBuffer(sessionId: String): String {
        synchronized(bufferLock) {
            val buf = outputBuffers[sessionId] ?: return ""
            val len = buf.length
            return if (len > 2048) buf.substring(len - 2048) else buf.toString()
        }
    }

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
        private const val MAX_BUFFER = 64 * 1024 // 64KB per session
        /** Sentinel returned by [downloadFile] when the remote file exceeds [DOWNLOAD_SIZE_LIMIT]. */
        val DOWNLOAD_TOO_LARGE: ByteArray = ByteArray(0)
        private const val DOWNLOAD_SIZE_LIMIT = 50L * 1024 * 1024 // 50 MB
    }
}
