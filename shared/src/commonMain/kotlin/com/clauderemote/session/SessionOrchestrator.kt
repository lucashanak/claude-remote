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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
    private fun fireNeedsInput(
        sessionId: String,
        hint: String,
        isActive: Boolean,
        // Precise identity of the triggering event when the caller has one —
        // the Stop-hook path passes "stop#<tmux>#<epoch>" so one completion
        // maps to exactly one key regardless of transcript freshness.
        eventKey: String? = null,
    ) {
        val now = System.currentTimeMillis()
        // Dedup by the explicit event key when given, else by (hint + last
        // assistant message). On reconnect the tmux buffer is replayed and the
        // screen looks idle/approval again, and a flapping
        // OMC statusline (WORKING→idle→WORKING around a prompt) re-raises the
        // SAME alert — both re-fired a notification for an event the user already
        // saw. Keying on the hint TOO lets a genuine APPROVAL after an
        // INPUT_PROMPT on the same message still get through, while the same
        // (hint, message) repeat is suppressed. Previously only INPUT_PROMPT was
        // deduped, so APPROVAL/PERMISSION spammed on every flap.
        val key = eventKey ?: lastAssistantId(sessionId)?.let { "$hint#$it" }
        if (key != null && lastNotifiedKey[sessionId] == key) {
            FileLogger.log(TAG, "Suppressed needs-input for $sessionId (same event)")
            return
        }
        val last = lastNeedsInputAt[sessionId] ?: 0L
        if (now - last < notifyDebounceMs) {
            FileLogger.log(TAG, "Suppressed needs-input for $sessionId (debounce)")
            return
        }
        lastNeedsInputAt[sessionId] = now
        if (key != null) lastNotifiedKey[sessionId] = key
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

    // Per-SERVER periodic polling jobs (keyed by server id). Usage (ccusage is
    // account-wide) and latency (all sessions share the physical link) are
    // identical for every session on a server — at 21 sessions on one box the
    // old per-session polls did 21× the SSH work to fetch one value.
    // ConcurrentHashMap + compute(): every session's attach/reconnect touches
    // its server's entry, overlapping with disconnects during a reconnect storm.
    private val usagePollingJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val latencyPollingJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * SHARED per-server Stop-hook watcher. All sessions on a server tail the
     * SAME /tmp/claude-notify, so one watcher per server replaces N identical
     * long-lived tail -f channels (20 fewer at 21 sessions) and dispatches each
     * marker to its owning session by the exact tmux-name token in the line.
     */
    private inner class ServerNotifyWatcher(val serverId: String) {
        /** tmux session name → app session id, updated on attach/detach. */
        val tmuxToSession = java.util.concurrent.ConcurrentHashMap<String, String>()
        /** True while the tail channel is connected (hook-based detection active). */
        @Volatile var live = false
        var job: kotlinx.coroutines.Job? = null
    }
    private val serverNotifyWatchers = java.util.concurrent.ConcurrentHashMap<String, ServerNotifyWatcher>()

    // sessions.json is one file per SERVER; with N sessions each running the
    // 15 s reconcile loop, N−1 of the flock+cat fetches were redundant. Cache
    // the parsed snapshot per server with a TTL just under the loop period so
    // only the first session to tick pays the exec.
    private class SessionsSnapshot(val at: Long, val list: List<PersistedSession>)
    private val sessionsJsonCache = java.util.concurrent.ConcurrentHashMap<String, SessionsSnapshot>()
    private val sessionsJsonMutex = Mutex()

    // Per-server gate on SIMULTANEOUS connect handshakes. A network blip drops
    // every session at once and each SshManager independently fires an
    // attempt-1 reconnect with zero backoff — at 21 sessions that was 21
    // concurrent KEX/auth handshakes (21 WebSocket upgrades over CF) hammering
    // a link that just came back. 3 at a time keeps the instant-reconnect feel
    // for the single-drop Starlink case while serializing the herd.
    private val connectGates = java.util.concurrent.ConcurrentHashMap<String, Semaphore>()
    private fun connectGate(serverId: String): Semaphore =
        connectGates.getOrPut(serverId) { Semaphore(3) }

    // Shared SSH transports per server: tabs lease shell channels on pooled
    // jsch Sessions (≤5 shells each) instead of each owning a TCP/WebSocket +
    // KEX + auth + keepalive. 21 sessions on one box: 21 transports → ~5.
    private val transportPools = java.util.concurrent.ConcurrentHashMap<String, com.clauderemote.connection.ServerTransportPool>()
    private fun transportPool(serverId: String): com.clauderemote.connection.ServerTransportPool =
        transportPools.getOrPut(serverId) { com.clauderemote.connection.ServerTransportPool(serverStorage) }

    /** Server id of a session's tab, or null if the tab is gone. */
    private fun serverIdOf(sessionId: String): String? =
        tabManager.getTab(sessionId)?.server?.id

    /** All session ids currently on [serverId]. */
    private fun sessionIdsOnServer(serverId: String): List<String> =
        tabManager.tabs.value.filter { it.server.id == serverId }.map { it.id }

    /**
     * Any CONNECTED session's live jsch Session on [serverId] — used by the
     * per-server loops (usage, latency, notify watcher) so they survive any
     * single tab's reconnect by simply picking another live connection.
     */
    private fun liveServerSession(serverId: String): Session? {
        for ((sid, mgr) in connections) {
            if (tabManager.getTab(sid)?.server?.id == serverId && mgr.isConnected) {
                mgr.getSession()?.let { return it }
            }
        }
        return null
    }
    /** Per-session timestamp of the last "needs input" dispatch, used to debounce
     *  the Stop-hook fire stream — claude can emit several markers in quick
     *  succession (model handoff, tool retries) and we don't want to vibrate
     *  the phone for each one. */
    private val lastNeedsInputAt = mutableMapOf<String, Long>()
    /** Last "(hint)#(assistant-message-id)" we fired a notification for, per
     *  session — dedups reconnect replays / statusline flaps re-notifying an
     *  already-seen event (per prompt type). */
    private val lastNotifiedKey = mutableMapOf<String, String>()
    private val notifyDebounceMs = 5_000L
    /** A Stop-hook marker older than this (skew-corrected) is dropped as stale —
     *  it's a buffered replay from a network/HyperOS freeze, not a fresh
     *  completion the user is waiting on. */
    private val notifyStaleMs = 120_000L
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
        serverIdOf(sessionId)?.let { serverId ->
            // Per-server loops: idempotent, first session on the server starts
            // them, later sessions just register/reuse.
            startServerUsagePolling(serverId)
            startServerLatencyPolling(serverId)
            startNotifyWatcher(sessionId, tmuxSessionName, serverId)
        }
        startGitStatusPolling(sessionId)
        connections[sessionId]?.let { conn ->
            startSessionIdRefresh(sessionId, tmuxSessionName, conn)
        }
        // Keep a transcript stream running for every connected session so the
        // notification body (last assistant message) is available even in Raw
        // view, not only after the Chat view has subscribed.
        ensureTranscriptStream(sessionId)
        // …and feed it from the shared per-server stream daemon.
        registerStreamWatch(sessionId)
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
        FileLogger.log(TAG, "Network lost — tearing down ${transportPools.size} transport pool(s)")
        // Our own teardown must not count as Tailscale early-death strikes.
        lastNetworkTeardownAt = System.currentTimeMillis()
        transportPools.values.forEach { it.teardownAll() }
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
            liveSession = { connections.values.firstOrNull { it.isConnected }?.getSession() },
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
        connections.values.forEach { it.setKeepAliveInterval(keepAlive) }
    }

    private fun startServerUsagePolling(serverId: String) {
        // Idempotent: ccusage data is account-wide, one loop per server serves
        // every session on it. Don't restart on reattach — the loop resolves a
        // live connection fresh each tick, so it self-heals across reconnects.
        // compute() makes check-and-launch atomic per key against a concurrent
        // last-session teardown's remove()+cancel().
        usagePollingJobs.compute(serverId) { _, existing ->
            if (existing?.isActive == true) return@compute existing
            reconnectScope.launch {
                kotlinx.coroutines.delay(5000) // initial delay
                while (isActive) {
                    // Skip poll when app is in background — user can't see usage bar anyway.
                    // A missing connection (mid-reconnect) just skips this round —
                    // the old `?: break` exited the loop PERMANENTLY the first time
                    // a reconnect briefly emptied the connections map, leaving the
                    // chips dead until app restart.
                    if (!isInBackground) {
                        try {
                            val sshSession = liveServerSession(serverId)
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
    }

    /**
     * Periodically probe the git status of the session's working directory
     * (branch + dirty/ahead/behind) and publish it to [gitStatuses]. Mirrors
     * [startServerUsagePolling]: runs the exec off the UI thread, respects
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
                // Skip in background: this is 2 SSH execs every 15s per session
                // (~104 radio wakeups/min at 13 sessions) and only matters for
                // UUID/restore correctness, which onResume refreshes anyway.
                if (isInBackground) { kotlinx.coroutines.delay(15_000); continue }
                try {
                    val remote = fetchSessionsCached(serverIdOf(sessionId), sshManager)
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

    private fun startServerLatencyPolling(serverId: String) {
        // Idempotent per server: every session on a server shares the physical
        // link, so 21 per-session `echo pong`s measured the same RTT 21×. One
        // probe per server, fanned out to all its sessions for display.
        // compute() = atomic check-and-launch (see startServerUsagePolling).
        latencyPollingJobs.compute(serverId) { _, existing ->
            if (existing?.isActive == true) return@compute existing
            reconnectScope.launch {
                kotlinx.coroutines.delay(3000)
                val recentLatencies = mutableListOf<Long>()
                while (isActive) {
                    if (!isInBackground) {
                        try {
                            // Missing connection (mid-reconnect) skips the round;
                            // the old `?: break` killed the loop permanently.
                            val sshSession = liveServerSession(serverId)
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
                            // Logged (not just published to the UI StateFlow) so
                            // remote-shipped logs carry an RTT trail — the
                            // signal that tells direct-LAN (~ms) apart from a
                            // DERP relay hop (tens-hundreds of ms) without
                            // needing tailscale CLI access on either end.
                            FileLogger.log(TAG, "Latency for server $serverId: ${latency}ms (avg ${avg}ms)")
                            // Fan out only to sessions with a live connection —
                            // a tab mid-teardown (entry already cleared by
                            // disconnectSession, tab not yet removed) must not
                            // be re-added as a permanent stale map entry.
                            val ids = sessionIdsOnServer(serverId).filter { connections.containsKey(it) }
                            _latencies.update { it + ids.associateWith { avg } }
                        } catch (_: Exception) {}
                    }
                    kotlinx.coroutines.delay(15_000) // every 15s
                }
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
     * Register [sessionId] with the SHARED per-server Stop-hook watcher and
     * make sure that watcher is running. The watcher holds ONE `tail -f
     * /tmp/claude-notify` exec channel per server (all sessions share the
     * file) and dispatches each marker to the owning session by tmux name,
     * firing [onClaudeNeedsInput]. Registered sessions are marked hook-active
     * in the detector so screen-state polling is skipped.
     *
     * If the watcher channel drops (SSH reconnect), screen-state fallback
     * resumes automatically via [markHookInactive]; on reconnect the last 25
     * marker lines are replayed (stale-filtered + deduped) so completions
     * that happened during the gap still notify.
     */
    private fun startNotifyWatcher(sessionId: String, tmuxName: String, serverId: String) {
        while (true) {
            val w = serverNotifyWatchers.getOrPut(serverId) { ServerNotifyWatcher(serverId) }
            val registered = synchronized(w) {
                // Whole register sequence under the watcher's monitor, and
                // re-checked against the map: a concurrent last-session
                // disconnect may have just emptied + removed this instance —
                // registering into the removed orphan would leave the session
                // served by a watcher nobody can ever cancel (and a later
                // attach would mint a SECOND tail on the same file).
                if (serverNotifyWatchers[serverId] !== w) return@synchronized false
                // Re-point this session's registration (a relaunched tab may
                // have a new tmux name — drop the stale mapping first).
                w.tmuxToSession.entries.removeAll { it.value == sessionId && it.key != tmuxName }
                w.tmuxToSession[tmuxName] = sessionId
                // Watcher already tailing → hook detection is live for this
                // session now. Under the monitor so it can't interleave with
                // the connect fan-out and miss the one-shot activation.
                if (w.live) setHookActive(sessionId, true)
                if (w.job?.isActive != true) {
                    w.job = reconnectScope.launch { runServerNotifyWatcher(w) }
                }
                true
            }
            if (registered) return
        }
    }

    private suspend fun runServerNotifyWatcher(w: ServerNotifyWatcher) = kotlinx.coroutines.coroutineScope {
        // Retry loop: the exec channel can die silently (mobile networks,
        // HyperOS battery management kill the socket without dropping the
        // main SSH channel). Previously the watcher exited permanently and
        // hook-based detection was gone until a FULL transport reconnect —
        // background sessions then had no detection path at all.
        var attempt = 0
        while (isActive && w.tmuxToSession.isNotEmpty()) {
            // Hoisted so the finally can ALWAYS disconnect it — if connect() or
            // readLine() throws, a channel declared inside the try would leak
            // on the shared long-lived session, one per retry.
            var ch: com.jcraft.jsch.ChannelExec? = null
            try {
                val sshSession = liveServerSession(w.serverId)
                if (sshSession == null) {
                    // No live connection right now (mid-reconnect). Keep probing
                    // — the shared watcher rides ANY session's connection, so it
                    // resumes as soon as the first tab on this server is back.
                    kotlinx.coroutines.delay(5_000)
                    continue
                }
                ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                // Emit the server clock first (learns phone↔server skew), then
                // tail INCLUDING the last 25 lines: markers appended while the
                // watcher was down (channel drop + retry backoff) used to be
                // lost forever — a completion during a reconnect window never
                // notified. The stale filter + marker-epoch dedup below make
                // replaying the recent backlog safe.
                ch.setCommand("echo claude-remote-clock \$(date +%s); touch /tmp/claude-notify && tail -n 25 -f /tmp/claude-notify")
                ch.inputStream = null
                val reader = ch.inputStream.bufferedReader()
                ch.connect(5000)

                // Under the monitor so a session registering right now can't
                // slip between `live = true` and the fan-out and miss both.
                synchronized(w) {
                    w.live = true
                    w.tmuxToSession.values.forEach { setHookActive(it, true) }
                }
                attempt = 0
                // Offset (ms) between this device's clock and the server's,
                // learned from the "claude-remote-clock <epoch>" line the
                // channel emits first (echo runs before tail, so it always
                // precedes any marker). Lets us reject stale markers by AGE
                // despite clock skew.
                var clockSkewMs: Long? = null
                FileLogger.log(TAG, "Notify watcher started for server ${w.serverId} (${w.tmuxToSession.size} sessions)")

                while (isActive && ch.isConnected) {
                    val line = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        reader.readLine()
                    } ?: break
                    // Server clock probe (emitted once, first): learn skew.
                    if (line.startsWith("claude-remote-clock")) {
                        line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()?.let { serverEpochSec ->
                            clockSkewMs = System.currentTimeMillis() - serverEpochSec * 1000L
                        }
                        continue
                    }
                    if (!line.startsWith("claude-remote-notify")) continue
                    // EXACT tmux-session match. The marker line is
                    // "claude-remote-notify <#S> <epoch>" and ALL sessions
                    // share /tmp/claude-notify, so a substring match cross-
                    // fired between sessions whose names are prefixes of each
                    // other (e.g. "cashy" matched "cashy-test"). Dispatch on the
                    // second whitespace token exactly.
                    val parts = line.trim().split(Regex("\\s+"))
                    val markerTmux = parts.getOrNull(1) ?: continue
                    val sessionId = w.tmuxToSession[markerTmux] ?: continue
                    // Reject STALE completions. /tmp/claude-notify is append-only
                    // and we now REPLAY the last 25 lines on every (re)connect;
                    // plus on a mobile / HyperOS socket freeze the tail's
                    // buffered bytes flush all at once when the phone wakes.
                    // The marker carries the Stop hook's epoch — drop anything
                    // older than notifyStaleMs (skew-corrected). Skew defaults to
                    // 0 (NTP assumption) if the clock line was somehow lost:
                    // fail-open would re-fire the whole replayed backlog.
                    val markerEpochSec = parts.getOrNull(2)?.toLongOrNull()
                    if (markerEpochSec != null) {
                        val skew = clockSkewMs ?: 0L
                        val ageMs = System.currentTimeMillis() - (markerEpochSec * 1000L + skew)
                        if (ageMs > notifyStaleMs) {
                            FileLogger.log(TAG, "Skipping stale Stop hook for $sessionId (age ${ageMs}ms): $line")
                            continue
                        }
                    }
                    FileLogger.log(TAG, "Stop hook fired for $sessionId: $line")
                    // Pull the transcript up to the just-finished turn BEFORE
                    // firing, so the notification body comes from the final
                    // assistant message rather than the last periodic poll (up
                    // to 30 s stale in background — it would show the previous
                    // turn or a mid-turn preamble). BOUND the wait: pollOnce's
                    // blocking read has no timeout, so on a frozen socket an
                    // unbounded refresh could stall or suppress the notification
                    // itself. Run it detached and wait at most 2 s, then fire
                    // regardless — a slightly stale body beats a missed alert.
                    val stream = synchronized(transcriptLock) { transcriptStreams[sessionId] }
                    if (stream != null) {
                        val refresh = reconnectScope.launch { stream.pollNow() }
                        kotlinx.coroutines.withTimeoutOrNull(2_000) { refresh.join() }
                    }
                    val isActiveTab = tabManager.activeTabId.value == sessionId
                    // Dedup on the MARKER itself — (tmux, epoch) identifies one
                    // completion. Replays of an already-notified marker are
                    // suppressed exactly, and a NEW completion can never be
                    // swallowed because the transcript hadn't caught up (the
                    // old lastAssistantId-based key was stale precisely when
                    // the network was slow).
                    fireNeedsInput(
                        sessionId, "Claude is ready for input", isActiveTab,
                        eventKey = markerEpochSec?.let { "stop#$markerTmux#$it" },
                    )
                    updateActivity(sessionId, SessionActivity.WAITING_FOR_INPUT)
                }
            } catch (e: Exception) {
                FileLogger.error(TAG, "Notify watcher failed for server ${w.serverId}: ${e.message}", e)
            } finally {
                try { ch?.disconnect() } catch (_: Exception) {}
                synchronized(w) {
                    w.live = false
                    w.tmuxToSession.values.forEach { setHookActive(it, false) }
                }
            }
            if (!isActive || w.tmuxToSession.isEmpty()) break
            attempt++
            val backoffMs = (5_000L * attempt).coerceAtMost(30_000L)
            FileLogger.log(TAG, "Notify watcher retrying for server ${w.serverId} in ${backoffMs}ms (attempt $attempt)")
            kotlinx.coroutines.delay(backoffMs)
        }
        FileLogger.log(TAG, "Notify watcher stopped for server ${w.serverId}")
    }

    // ---- Per-server transcript stream daemon (streamd) ----
    //
    // One long-lived exec channel per server replaces N sessions × 20 polls/min
    // of transcript execs: a tiny python script on the server watches the JSONL
    // files locally (cheap — no network) and pushes only NEW COMPLETE LINES as
    // NDJSON events. Idle traffic drops to a 20 s heartbeat; updates arrive in
    // ~1 s instead of 3–30 s, background included. Every TranscriptStream keeps
    // its own poll loop as a 60 s safety backstop and as the full fallback when
    // python3 is missing or the channel dies.

    /** Marker doubles as the version gate — bump vN to force reinstall. */
    private val STREAMD_MARKER = "claude-remote-streamd v1"

    private val STREAMD_SCRIPT = """
        #!/usr/bin/env python3
        # claude-remote-streamd v1 — single-channel transcript delta streamer.
        # stdin : {"op":"watch","id":..,"cwd":..,"uuid":..,"off":N}  (off<0 = from EOF)
        #         {"op":"unwatch","id":..}
        # stdout: {"t":"hello","v":1} | {"t":"hb"} | {"t":"d","id":..,"u":..,"o":N,"b":b64}
        import sys, os, json, time, base64, threading

        watches = {}
        lock = threading.Lock()
        TAIL = 200000  # initial backlog bytes when off==0 (~2000 lines)

        def resolve(cwd, uuid):
            p = os.path.expanduser(cwd)
            if not os.path.isabs(p):
                p = os.path.join(os.path.expanduser('~'), p)
            enc = os.path.realpath(p).replace('/', '-')
            return os.path.join(os.path.expanduser('~/.claude/projects'), enc, uuid + '.jsonl')

        def emit(o):
            sys.stdout.write(json.dumps(o, separators=(',', ':')) + '\n')
            sys.stdout.flush()

        def reader():
            for line in sys.stdin:
                line = line.strip()
                if not line:
                    continue
                try:
                    c = json.loads(line)
                except Exception:
                    continue
                op = c.get('op')
                if op == 'watch' and c.get('id') and c.get('uuid'):
                    with lock:
                        watches[c['id']] = {
                            'path': resolve(c.get('cwd') or '~', c['uuid']),
                            'uuid': c['uuid'],
                            'off': int(c.get('off') or 0),
                        }
                elif op == 'unwatch':
                    with lock:
                        watches.pop(c.get('id'), None)
            os._exit(0)  # stdin closed -> client gone

        threading.Thread(target=reader, daemon=True).start()
        emit({'t': 'hello', 'v': 1})
        last_hb = time.time()
        while True:
            now = time.time()
            if now - last_hb >= 20:
                emit({'t': 'hb'})
                last_hb = now
            with lock:
                items = list(watches.items())
            for wid, w in items:
                try:
                    sz = os.path.getsize(w['path'])
                except OSError:
                    continue
                off = w['off']
                if off < 0:          # "from EOF": client loads its own backlog
                    w['off'] = sz
                    continue
                adjusted = False
                if sz < off or (off == 0 and sz > TAIL):
                    off = max(0, sz - TAIL)   # rotation / first sight of a big file
                    adjusted = True
                if sz <= off:
                    continue
                try:
                    with open(w['path'], 'rb') as f:
                        f.seek(off)
                        data = f.read(sz - off)
                except OSError:
                    continue
                nl = data.rfind(b'\n')
                if nl < 0:
                    continue          # no complete line yet
                chunk = data[:nl + 1]
                new_off = off + nl + 1
                if adjusted and off > 0:
                    first = chunk.find(b'\n')
                    chunk = chunk[first + 1:]   # drop the partial first line
                w['off'] = new_off
                if chunk:
                    emit({'t': 'd', 'id': wid, 'u': w['uuid'], 'o': new_off,
                          'b': base64.b64encode(chunk).decode()})
            time.sleep(1.0)
    """.trimIndent()

    private val ENSURE_STREAMD_COMMAND = buildString {
        append("F=\"${'$'}HOME/.claude-remote/streamd.py\"; ")
        append("if head -c 200 \"${'$'}F\" 2>/dev/null | grep -q '").append(STREAMD_MARKER).append("'; ")
        append("then echo STREAMD_OK; else mkdir -p \"${'$'}HOME/.claude-remote\" && ")
        append("cat > \"${'$'}F\" <<'CRSD_EOF'\n")
        append(STREAMD_SCRIPT)
        append("\nCRSD_EOF\n")
        append("echo STREAMD_INSTALLED; fi")
    }

    /** Install/refresh streamd.py on the server. Idempotent, non-fatal. */
    private suspend fun ensureStreamd(sshManager: SshManager) {
        try {
            val sshSession = sshManager.getSession() ?: return
            val out = execReadWithWatchdog(sshSession, ENSURE_STREAMD_COMMAND, totalMs = 15_000)
            FileLogger.log(TAG, "streamd setup: ${out.trim().lineSequence().lastOrNull()}")
        } catch (e: Exception) {
            FileLogger.error(TAG, "streamd setup failed: ${e.message}", e)
        }
    }

    private inner class ServerStreamDaemon(val serverId: String) {
        /** sessionId → cwd; the uuid/offset come from the live stream at send time. */
        val specs = java.util.concurrent.ConcurrentHashMap<String, String>()
        @Volatile var live = false
        @Volatile var stdin: java.io.OutputStream? = null
        @Volatile var lastEventAt = 0L
        /** Serializes control-line writes: jsch's channel OutputStream is not
         *  thread-safe, and the hello fan-out fires N watches at once —
         *  concurrent write() calls corrupt the SSH packet framing. */
        val writeMutex = Mutex()
        var job: kotlinx.coroutines.Job? = null
    }
    private val serverStreamDaemons = java.util.concurrent.ConcurrentHashMap<String, ServerStreamDaemon>()

    /** Register [sessionId]'s transcript with its server's stream daemon
     *  (starting the daemon if needed) — same TOCTOU discipline as the
     *  notify watcher. Re-invoked on attach and on UUID rotation. */
    private fun registerStreamWatch(sessionId: String) {
        val tab = tabManager.getTab(sessionId) ?: return
        while (true) {
            val d = serverStreamDaemons.getOrPut(tab.server.id) { ServerStreamDaemon(tab.server.id) }
            val registered = synchronized(d) {
                if (serverStreamDaemons[tab.server.id] !== d) return@synchronized false
                d.specs[sessionId] = tab.folder
                if (d.job?.isActive != true) {
                    d.job = reconnectScope.launch { runServerStreamDaemon(d) }
                }
                if (d.live) sendWatch(d, sessionId)
                true
            }
            if (registered) return
        }
    }

    private fun sendWatch(d: ServerStreamDaemon, sessionId: String) {
        val cwd = d.specs[sessionId] ?: return
        val stream = synchronized(transcriptLock) { transcriptStreams[sessionId] } ?: return
        val uuid = stream.currentUuid() ?: return
        // offsetFor(uuid) is 0 when the stream hasn't loaded this uuid's
        // backlog yet (startup OR mid-rotation, where the raw offset still
        // belongs to the OLD file — sending that made the daemon skip the new
        // file's head). 0 → ask the daemon to stream from EOF (-1); the
        // client's own poll fetches the backlog and pushLines drops anything
        // that would race it.
        val off = stream.offsetFor(uuid).let { if (it == 0L) -1L else it }
        val cmd = kotlinx.serialization.json.JsonObject(mapOf(
            "op" to JsonPrimitive("watch"),
            "id" to JsonPrimitive(sessionId),
            "cwd" to JsonPrimitive(cwd),
            "uuid" to JsonPrimitive(uuid),
            "off" to JsonPrimitive(off),
        )).toString()
        sendStreamCmd(d, cmd)
    }

    private fun sendStreamCmd(d: ServerStreamDaemon, line: String) {
        val os = d.stdin ?: return
        reconnectScope.launch(Dispatchers.IO) {
            d.writeMutex.withLock {
                try {
                    os.write((line + "\n").toByteArray())
                    os.flush()
                } catch (e: Exception) {
                    FileLogger.log(TAG, "streamd write failed for server ${d.serverId}: ${e.message}")
                }
            }
        }
    }

    private suspend fun runServerStreamDaemon(d: ServerStreamDaemon) = kotlinx.coroutines.coroutineScope {
        var attempt = 0
        while (isActive && d.specs.isNotEmpty()) {
            var ch: com.jcraft.jsch.ChannelExec? = null
            var watchdog: kotlinx.coroutines.Job? = null
            try {
                val sshSession = liveServerSession(d.serverId)
                if (sshSession == null) {
                    kotlinx.coroutines.delay(5_000)
                    continue
                }
                ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand("python3 \"${'$'}HOME/.claude-remote/streamd.py\" 2>/dev/null")
                val stdin = ch.outputStream
                val reader = ch.inputStream.bufferedReader()
                ch.connect(5000)
                d.stdin = stdin
                d.lastEventAt = System.currentTimeMillis()
                // Heartbeats come every 20 s; 60 s of silence = dead channel
                // even while isConnected still lies — force-close so readLine
                // unblocks and the retry loop reconnects.
                val chRef = ch
                watchdog = launch {
                    while (isActive) {
                        kotlinx.coroutines.delay(20_000)
                        if (System.currentTimeMillis() - d.lastEventAt > 60_000) {
                            FileLogger.log(TAG, "streamd watchdog fired for server ${d.serverId}")
                            try { chRef.disconnect() } catch (_: Exception) {}
                            break
                        }
                    }
                }
                while (isActive && ch.isConnected) {
                    val line = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        reader.readLine()
                    } ?: break
                    d.lastEventAt = System.currentTimeMillis()
                    val obj = try {
                        fetchJson.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject
                    } catch (_: Exception) { null } ?: continue
                    when (obj["t"]?.jsonPrimitive?.contentOrNull) {
                        "hello" -> {
                            d.live = true
                            attempt = 0
                            FileLogger.log(TAG, "streamd live for server ${d.serverId} (${d.specs.size} watches)")
                            d.specs.keys.forEach { sendWatch(d, it) }
                        }
                        "hb" -> {}
                        "d" -> {
                            val sid = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                            val uuid = obj["u"]?.jsonPrimitive?.contentOrNull ?: continue
                            val off = obj["o"]?.jsonPrimitive?.longOrNull ?: continue
                            val b64 = obj["b"]?.jsonPrimitive?.contentOrNull ?: continue
                            val stream = synchronized(transcriptLock) { transcriptStreams[sid] } ?: continue
                            val text = try {
                                String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
                            } catch (_: Exception) { continue }
                            // Sequential dispatch on this reader keeps per-
                            // session line order; dedup absorbs any overlap
                            // with the safety poll. BOUNDED: pushLines waits on
                            // the stream's pollMutex, which a slow safety poll
                            // can hold across a timeout-less SSH read — an
                            // unbounded wait would head-of-line-block deltas
                            // for EVERY session on this server and starve the
                            // heartbeat into a false watchdog kill. A dropped
                            // push is backfilled by that same safety poll.
                            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                                stream.pushLines(uuid, text.lineSequence().filter { it.isNotBlank() }.toList(), off)
                            } ?: FileLogger.log(TAG, "streamd push timed out for $sid (safety poll will backfill)")
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                FileLogger.error(TAG, "streamd failed for server ${d.serverId}: ${e.message}", e)
            } finally {
                watchdog?.cancel()
                d.live = false
                d.stdin = null
                try { ch?.disconnect() } catch (_: Exception) {}
            }
            if (!isActive || d.specs.isEmpty()) break
            attempt++
            kotlinx.coroutines.delay((5_000L * attempt).coerceAtMost(60_000L))
        }
        FileLogger.log(TAG, "streamd stopped for server ${d.serverId}")
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
    fun lastAssistantText(sessionId: String): String? =
        lastAssistantEntry(sessionId)?.text?.takeIf { it.isNotBlank() }

    /** Id of the most recent assistant message — used to dedup notifications. */
    private fun lastAssistantId(sessionId: String): String? =
        lastAssistantEntry(sessionId)?.id

    private fun lastAssistantEntry(sessionId: String): TranscriptEntry.AssistantText? {
        val stream = synchronized(transcriptLock) { transcriptStreams[sessionId] } ?: return null
        return stream.entries.value.lastOrNull { it is TranscriptEntry.AssistantText }
            as? TranscriptEntry.AssistantText
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
                TranscriptStream(tab.server, tab.folder, reconnectScope, liveSession = { connections[sessionId]?.getSession() }, isBackground = { isInBackground }, isActiveTab = { tabManager.activeTabId.value == sessionId }, daemonActive = { serverStreamDaemons[tab.server.id]?.live == true })
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
                TranscriptStream(tab.server, tab.folder, reconnectScope, liveSession = { connections[sessionId]?.getSession() }, isBackground = { isInBackground }, isActiveTab = { tabManager.activeTabId.value == sessionId }, daemonActive = { serverStreamDaemons[tab.server.id]?.live == true })
            }
            // Derive the ctx-window % from this stream's token usage. Inside the
            // lock so it binds to the exact stream instance and stays atomic with
            // disconnectSession's teardown.
            startContextTokenCollector(sessionId, s)
            s
        }
        val uuid = tab.claudeSessionId
        if (uuid != null) stream.start(uuid)
        // Freshness nudge: a tab that just became active may be up to
        // INACTIVE_POLL_MS (15 s) behind — pull the delta right away instead
        // of waiting out the current sleep. pollNow is mutex-serialized and
        // incremental, so repeated calls (recompositions) are cheap no-ops.
        reconnectScope.launch { stream.pollNow() }
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
                TranscriptStream(tab.server, tab.folder, reconnectScope, liveSession = { connections[sessionId]?.getSession() }, isBackground = { isInBackground }, isActiveTab = { tabManager.activeTabId.value == sessionId }, daemonActive = { serverStreamDaemons[tab.server.id]?.live == true })
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
            // Only ONE tab's status is rendered at a time (the active Chat
            // view). Stop the others here instead of leaking them: visiting all
            // 21 tabs used to leave 21 pollers alive — each opening SSH execs
            // every 5 s, in the background too. A stopped poller restarts
            // lazily the next time its tab becomes active.
            statusPollers.forEach { (id, p) -> if (id != sessionId) p.stop() }
            statusPollers.getOrPut(sessionId) {
                SessionStatusPoller(
                    server = tab.server,
                    cwd = tab.folder,
                    claudeSessionIdProvider = { tabManager.getTab(sessionId)?.claudeSessionId },
                    scope = reconnectScope,
                    liveSession = { connections[sessionId]?.getSession() },
                    isBackground = { isInBackground },
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
        if (newUuid != null) {
            stream.start(newUuid)
            // Re-point the daemon watch at the new JSONL (start() set
            // currentUuid synchronously; the wiped stream reports offset 0 →
            // watch from EOF while the client reloads its own backlog).
            registerStreamWatch(sessionId)
        }
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
                if (lastConnectAt[sessionId] == connectEpoch && connections[sessionId]?.isConnected == true) {
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
                    val sshSession = connections[sessionId]?.getSession() ?: return@launch
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

    private suspend fun connectSsh(session: ClaudeSession, isNewTmuxSession: Boolean) {
        val sshManager = SshManager(serverStorage, transportPool = transportPool(session.server.id))
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

        val sshEffective = resolveTransport(session.server)
        setConnectionLabel(session.id, sshEffective, session.server, "SSH")
        try {
            // Gate the handshake per server — see connectGates.
            connectGate(session.server.id).withPermit {
                sshManager.connect(
                    sshEffective,
                    onOutput = { data -> emit(data) },
                    onConnectionLost = {
                        maybeCountTsEarlyDeath(session)
                        // Auto-reconnect with tmux reattach
                        tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
                        updateActivity(session.id, SessionActivity.DISCONNECTED)
                        reconnectScope.launch {
                            autoReconnect(session, ::emit)
                        }
                    }
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
        ensureStopHook(sshManager)
        // Install/refresh the transcript stream daemon script (one shared
        // delta channel per server instead of per-session polling).
        ensureStreamd(sshManager)

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

                    val sshManager = SshManager(serverStorage, transportPool = transportPool(session.server.id))
                    connections[session.id] = sshManager

                    val reEffective = resolveTransport(session.server)
                    setConnectionLabel(session.id, reEffective, session.server, "SSH")
                    try {
                        // Gate the handshake per server: a blip drops all
                        // sessions at once and each fires attempt-1 with zero
                        // backoff — without the gate that's a 21-way concurrent
                        // KEX storm on a just-recovered weak link.
                        connectGate(session.server.id).withPermit {
                            sshManager.connect(
                                reEffective,
                                onOutput = { data -> emit(data) },
                                onConnectionLost = {
                                    maybeCountTsEarlyDeath(session)
                                    tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
                                    reconnectScope.launch { autoReconnect(session, emit) }
                                }
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
        reconnectScope.launch { sendSlashCommand(sessionId, ClaudeConfig.modelSwitchCommand(model)) }
    }

    fun switchModelForAllSessions(model: ClaudeModel) {
        tabManager.tabs.value.forEach { switchModel(it.id, model) }
    }

    fun switchEffort(sessionId: String, effort: ClaudeEffort) {
        reconnectScope.launch { sendSlashCommand(sessionId, ClaudeConfig.effortSwitchCommand(effort)) }
    }

    fun switchEffortForAllSessions(effort: ClaudeEffort) {
        tabManager.tabs.value.forEach { switchEffort(it.id, effort) }
    }

    /**
     * Type [command] as discrete keystrokes with small gaps, then Enter
     * after a longer pause — mirrors the chat input's slash-command send
     * path (TerminalScreen's PromptInputBar/ExpandedInput). Sending a slash
     * command as one burst (whole string + \n in a single write) is detected
     * by Claude's TUI as a paste: it lands as literal text in the prompt
     * ("//model opus") instead of driving the interactive picker, so nothing
     * actually switches. switchModel/switchEffort used to do exactly that.
     */
    private suspend fun sendSlashCommand(sessionId: String, command: String) {
        for (ch in command) {
            sendInput(sessionId, ch.toString())
            kotlinx.coroutines.delay(15)
        }
        kotlinx.coroutines.delay(60)
        sendInput(sessionId, "\r")
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
            // Keep the session's chosen connection type. reconnectSession used
            // to hardcode SSH, so a MOSH session silently degraded to plain
            // SSH after its first drop and never got its roaming resilience
            // back — the exact scenario mosh exists for. connectMosh still
            // falls back to SSH internally when no direct-UDP path exists.
            if (session.connectionType == ConnectionType.MOSH) {
                connectMosh(session, false)
            } else {
                connectSsh(session, false) // attach to existing tmux
            }
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
        gitStatusJobs.remove(sessionId)?.cancel()
        sessionIdRefreshJobs.remove(sessionId)?.cancel()
        // Per-SERVER loops (usage, latency, notify watcher) serve every session
        // on the server — tear them down only with the LAST one. The notify
        // registry entry for this session goes away either way.
        val serverId = tabManager.getTab(sessionId)?.server?.id
        if (serverId != null) {
            serverStreamDaemons[serverId]?.let { d ->
                synchronized(d) {
                    d.specs.remove(sessionId)
                    if (d.live) {
                        sendStreamCmd(d, kotlinx.serialization.json.JsonObject(mapOf(
                            "op" to JsonPrimitive("unwatch"),
                            "id" to JsonPrimitive(sessionId),
                        )).toString())
                    }
                    if (d.specs.isEmpty() && serverStreamDaemons.remove(serverId, d)) {
                        d.job?.cancel()
                    }
                }
            }
            serverNotifyWatchers[serverId]?.let { w ->
                synchronized(w) {
                    w.tmuxToSession.entries.removeAll { it.value == sessionId }
                    // Conditional remove(key, value): only cancel if this exact
                    // instance is still the registered watcher AND nobody
                    // re-registered between the removeAll and here (both are
                    // under w's monitor, matching startNotifyWatcher).
                    if (w.tmuxToSession.isEmpty() && serverNotifyWatchers.remove(serverId, w)) {
                        w.job?.cancel()
                    }
                }
            }
            val lastOnServer = tabManager.tabs.value.none {
                it.id != sessionId && it.server.id == serverId
            }
            if (lastOnServer) {
                usagePollingJobs.remove(serverId)?.cancel()
                latencyPollingJobs.remove(serverId)?.cancel()
            }
        }
        lastNeedsInputAt.remove(sessionId)
        lastNotifiedKey.remove(sessionId)
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
        lastConnectAt.remove(sessionId)
        lastConnectTsEffective.remove(sessionId)
        confirmedUuids.remove(sessionId)
        _sessionActivities.update { it - sessionId }
        _connectionLabels.update { it - sessionId }
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
        val sshSession = connections[sessionId]?.getSession()
        if (sshSession == null) {
            FileLogger.log(TAG, "restartClaude: no live connection for $sessionId")
            return
        }
        val cmd = ClaudeConfig.buildRestartCommand(tab.tmuxSessionName, tab.mode, tab.model, uuid)
        FileLogger.log(TAG, "Restarting Claude Code for $sessionId (resume $uuid) in tmux ${tab.tmuxSessionName}")
        // The respawn kills+redraws the pane; suppress the prompt detector so it
        // doesn't misfire on the transient screen. UUID is unchanged, so the
        // transcript stream keeps tailing the same file across the restart.
        promptDetector.suppressFor(5000)
        try {
            execReadWithWatchdog(sshSession, cmd, totalMs = 15_000)
        } catch (e: Exception) {
            FileLogger.error(TAG, "restartClaude failed for $sessionId", e)
        }
    }

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
