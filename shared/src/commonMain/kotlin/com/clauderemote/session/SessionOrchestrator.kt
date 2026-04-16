package com.clauderemote.session

import com.clauderemote.connection.SshManager
import com.clauderemote.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Orchestrates the full flow: server → SSH connect → tmux → cd folder → claude.
 * Manages one SshManager per active session/tab.
 * Buffers terminal output per session for tab switching.
 */
class SessionOrchestrator(
    private val serverStorage: ServerStorage,
    private val tabManager: TabManager
) {
    private val connections = mutableMapOf<String, SshManager>()
    private val moshConnections = mutableMapOf<String, com.clauderemote.connection.MoshManager>()

    // Per-session terminal output buffer (ring buffer, capped at MAX_BUFFER)
    private val outputBuffers = mutableMapOf<String, StringBuilder>()
    private val bufferLock = Any()

    // Prompt detection for notifications — quiescence-based, reads rendered screen state.
    private val promptDetector = InputPromptDetector().apply {
        onDetection = { det ->
            val isActive = tabManager.activeTabId.value == det.sessionId
            onClaudeNeedsInput?.invoke(det.sessionId, det.type.displayHint, isActive)
        }
        onStateChange = { sessionId, state ->
            when (state) {
                ClaudeState.WORKING -> updateActivity(sessionId, SessionActivity.WORKING)
                ClaudeState.IDLE -> updateActivity(sessionId, SessionActivity.WAITING_FOR_INPUT)
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

    // Per-session context window usage (0-100)
    private val _contextPercents = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val contextPercents: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _contextPercents

    // Per-session SSH latency (ms)
    private val _latencies = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
    val latencies: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> = _latencies

    // Pending input queue per session (for offline queue feature)
    private val pendingInputs = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    private val _pendingCounts = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val pendingCounts: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _pendingCounts

    private fun updateActivity(sessionId: String, activity: SessionActivity) {
        _sessionActivities.update { it + (sessionId to activity) }
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

    // Per-session periodic polling jobs
    private val usagePollingJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val latencyPollingJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    @Volatile private var isInBackground = false
    private val reconnectingSessionIds = mutableSetOf<String>()
    // Last known terminal dimensions per session — used to re-send SIGWINCH after reconnect
    private val terminalSizes = mutableMapOf<String, Pair<Int, Int>>()

    /** Call from onPause/onResume to pause heavy background work and save battery. */
    fun setBackgroundMode(background: Boolean) {
        isInBackground = background
    }

    private fun startUsagePolling(sessionId: String) {
        usagePollingJobs[sessionId]?.cancel()
        usagePollingJobs[sessionId] = reconnectScope.launch {
            kotlinx.coroutines.delay(5000) // initial delay
            while (isActive) {
                // Skip poll when app is in background — user can't see usage bar anyway
                if (!isInBackground) {
                    try {
                        val conn = connections[sessionId] ?: break
                        val sshSession = conn.getSession() ?: break
                        val output = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                            ch.setCommand("which ccusage >/dev/null 2>&1 || npm install -g ccusage >/dev/null 2>&1; ccusage blocks --active --json --offline --no-color 2>/dev/null || echo '{}'")
                            ch.inputStream = null
                            val input = ch.inputStream
                            ch.connect(5000)
                            val result = input.bufferedReader().readText()
                            ch.disconnect()
                            result
                        }
                        parseUsageJson(output)
                    } catch (_: Exception) {}
                }
                kotlinx.coroutines.delay(120_000) // poll every 2 min (was 30s)
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
                        val conn = connections[sessionId] ?: break
                        val sshSession = conn.getSession() ?: break
                        val latency = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            val start = System.currentTimeMillis()
                            val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                            ch.setCommand("echo pong")
                            ch.inputStream = null
                            val input = ch.inputStream
                            ch.connect(5000)
                            input.bufferedReader().readText()
                            ch.disconnect()
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

    suspend fun launchSession(
        server: SshServer,
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel,
        connectionType: ConnectionType,
        tmuxSessionName: String,
        isNewTmuxSession: Boolean = true
    ): ClaudeSession = withContext(Dispatchers.IO) {
        val sessionId = generateId()

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
            alias = parsedAlias
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
            startUsagePolling(sessionId)
            startLatencyPolling(sessionId)
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
        tabManager.switchTab(id)
        promptDetector.onUserInput(id)
        val tail = synchronized(bufferLock) {
            val buf = outputBuffers[id] ?: return@synchronized ""
            val len = buf.length
            if (len > 2048) buf.substring(len - 2048) else buf.toString()
        }
        promptDetector.suppressFor(2000)
        onTabSwitched?.invoke(id, tail)
    }

    /**
     * Kick tmux into a full-screen redraw by toggling the PTY size. The 2 KB tail
     * we replay on tab switch is usually a partial update (statusline, cursor
     * move) rather than a full screen dump, so without an explicit SIGWINCH tmux
     * won't repaint the rest of the viewport. Called by the platform after it
     * has laid out the terminal for the new session at the current dimensions.
     */
    fun kickRedraw(sessionId: String, cols: Int, rows: Int) {
        val conn = connections[sessionId] ?: return
        if (cols <= 0 || rows <= 1) return
        conn.resize(cols, rows - 1)
        conn.resize(cols, rows)
    }

    private val reconnectScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

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

            // Skip expensive processing during data bursts (tmux attach/scrollback)
            val now = System.currentTimeMillis()
            if (now - lastOutputTime < 50) {
                burstMode = true
                lastOutputTime = now
                return // Skip prompt detection, activity tracking during burst
            }
            if (burstMode && now - lastOutputTime >= 200) {
                burstMode = false // Burst ended, resume processing
            }
            lastOutputTime = now
            if (burstMode) return

            // Feed output — detector handles buffering + schedules quiescence check.
            // Detection fires asynchronously via promptDetector.onDetection callback
            // once the rendered screen state classifies as IDLE.
            promptDetector.onOutput(session.id, text)
            // Parse context window usage
            val ctx = promptDetector.parseContextPercent(session.id, text)
            if (ctx != null) {
                updateContextPercent(session.id, ctx)
                onContextUpdate?.invoke(session.id, ctx)
            }
        }

        sshManager.connect(
            session.server,
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
                model = session.model
            )
            sshManager.sendInput(command + "\n")
        } else {
            val escaped = session.tmuxSessionName.replace("'", "\\'")
            val command = "tmux set-option -g window-size latest 2>/dev/null; tmux attach-session -t '$escaped' 2>/dev/null || tmux new-session -A -s '$escaped' \\; set-option -g mouse on"
            FileLogger.log(TAG, "Attaching to tmux: $command")
            sshManager.sendInput(command + "\n")
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
            for (attempt in 1..maxAttempts) {
                emit("\r\n\u001B[33mConnection lost. Reconnecting ($attempt/$maxAttempts)...\u001B[0m\r\n")
                FileLogger.log(TAG, "Auto-reconnect attempt $attempt/$maxAttempts for ${session.id}")
                kotlinx.coroutines.delay(2000L * attempt) // increasing delay

                try {
                    // Clean up old connection
                    connections[session.id]?.disconnect()
                    connections.remove(session.id)

                    val sshManager = SshManager(serverStorage)
                    connections[session.id] = sshManager

                    sshManager.connect(
                        session.server,
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
        } finally {
            synchronized(reconnectingSessionIds) { reconnectingSessionIds.remove(session.id) }
        }
    }

    private suspend fun connectMosh(session: ClaudeSession, isNewTmuxSession: Boolean) {
        // Mosh requires direct UDP — can't work over Cloudflare tunnel
        if (session.server.useCloudflareProxy) {
            FileLogger.log(TAG, "Mosh not compatible with Cloudflare tunnel, using SSH")
            val warning = "\r\n\u001B[33mMosh requires direct UDP — falling back to SSH (Cloudflare tunnel)\u001B[0m\r\n"
            appendToBuffer(session.id, warning)
            onTerminalOutput?.invoke(session.id, warning)
            connectSsh(session, isNewTmuxSession)
            return
        }

        FileLogger.log(TAG, "Connecting via Mosh to ${session.server.name}")
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
            val escaped = session.tmuxSessionName.replace("'", "\\'")
            "tmux set-option -g window-size latest 2>/dev/null; tmux attach-session -t '$escaped' 2>/dev/null || tmux new-session -A -s '$escaped' \\; set-option -g mouse on"
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
            session.server,
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

        // Clean up old connection
        connections[sessionId]?.disconnect()
        connections.remove(sessionId)

        tabManager.updateTabStatus(sessionId, SessionStatus.CONNECTING)

        try {
            connectSsh(session, false) // attach to existing tmux
            tabManager.updateTabStatus(sessionId, SessionStatus.ACTIVE)
            onSessionActive?.invoke(session)
            FileLogger.log(TAG, "Reconnected: $sessionId")
        } catch (e: Exception) {
            FileLogger.error(TAG, "Reconnect failed", e)
            tabManager.updateTabStatus(sessionId, SessionStatus.ERROR)
        }
    }

    suspend fun disconnectSession(sessionId: String) {
        usagePollingJobs.remove(sessionId)?.cancel()
        latencyPollingJobs.remove(sessionId)?.cancel()
        connections[sessionId]?.disconnect()
        connections.remove(sessionId)
        moshConnections[sessionId]?.disconnect()
        moshConnections.remove(sessionId)
        synchronized(bufferLock) { outputBuffers.remove(sessionId) }
        promptDetector.removeSession(sessionId)
        pendingInputs.remove(sessionId)
        terminalSizes.remove(sessionId)
        _sessionActivities.update { it - sessionId }
        _contextPercents.update { it - sessionId }
        _latencies.update { it - sessionId }
        _pendingCounts.update { it - sessionId }
        tabManager.removeTab(sessionId)
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
            } catch (e: Exception) {
                FileLogger.error(TAG, "Tmux rename failed", e)
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
            val out = java.io.ByteArrayOutputStream()
            sftp.get(remotePath, out)
            sftp.disconnect()
            out.toByteArray()
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

    companion object {
        private const val TAG = "SessionOrchestrator"
        private const val MAX_BUFFER = 64 * 1024 // 64KB per session
    }
}
