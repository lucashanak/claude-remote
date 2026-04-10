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

    // Per-session terminal output buffer (ring buffer, capped at MAX_BUFFER)
    private val outputBuffers = mutableMapOf<String, StringBuilder>()

    // Prompt detection for notifications
    private val promptDetector = InputPromptDetector()

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

    // Terminal output callback — set by the platform (Android WebView, Desktop terminal)
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

        outputBuffers[sessionId] = StringBuilder()
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
     */
    fun switchTab(id: String) {
        tabManager.switchTab(id)
        promptDetector.onUserInput(id) // Clear waiting state on tab focus
        val buffer = outputBuffers[id]?.toString() ?: ""
        FileLogger.log(TAG, "Switching to tab $id (buffer: ${buffer.length} chars)")
        promptDetector.suppressFor(3000)
        onTabSwitched?.invoke(id, buffer)
    }

    private val reconnectScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    private suspend fun connectSsh(session: ClaudeSession, isNewTmuxSession: Boolean) {
        val sshManager = SshManager(serverStorage)
        connections[session.id] = sshManager

        fun emit(text: String) {
            appendToBuffer(session.id, text)
            val isActive = tabManager.activeTabId.value == session.id
            if (isActive) {
                onTerminalOutput?.invoke(session.id, text)
            }
            // Detect prompts for all sessions (active and background)
            val detection = promptDetector.onOutput(session.id, text)
            if (detection != null) {
                onClaudeNeedsInput?.invoke(session.id, detection.type.displayHint, isActive)
                // Update activity based on prompt type
                val activity = when (detection.type) {
                    PromptType.INPUT_PROMPT -> SessionActivity.WAITING_FOR_INPUT
                    PromptType.APPROVAL_NEEDED, PromptType.PERMISSION_PROMPT -> SessionActivity.APPROVAL_NEEDED
                }
                updateActivity(session.id, activity)
            }
            // Feed buffer for multi-chunk parsing
            promptDetector.feedRecentOutput(session.id, text)
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

        // Wait for shell prompt
        kotlinx.coroutines.delay(800)

        // Startup command
        if (session.server.startupCommand.isNotBlank()) {
            sshManager.sendInput(session.server.startupCommand + "\n")
            kotlinx.coroutines.delay(500)
        }

        // Tmux
        sendTmuxCommand(sshManager, session, isNewTmuxSession)
        promptDetector.suppressFor(3000) // suppress during tmux screen redraw
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

                // Wait for shell prompt to be ready
                kotlinx.coroutines.delay(800)
                // Clear any garbage in the shell input (DA responses etc.)
                sshManager.sendInput("\u0003") // Ctrl-C
                kotlinx.coroutines.delay(200)
                sshManager.sendInput("\n")     // blank Enter to get clean prompt
                kotlinx.coroutines.delay(300)
                sendTmuxCommand(sshManager, session, false) // always attach existing
                promptDetector.suppressFor(3000) // suppress during tmux screen redraw after reconnect

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
    }

    private suspend fun connectMosh(session: ClaudeSession, isNewTmuxSession: Boolean) {
        FileLogger.log(TAG, "Mosh not yet implemented, falling back to SSH for ${session.server.name}")
        val warning = "\r\n[Warning: Mosh not yet implemented, using SSH]\r\n"
        appendToBuffer("", warning)
        onTerminalOutput?.invoke("", warning)
        connectSsh(session, isNewTmuxSession)
    }

    private fun appendToBuffer(sessionId: String, data: String) {
        val buf = outputBuffers[sessionId] ?: return
        buf.append(data)
        // Cap buffer at MAX_BUFFER — keep tail
        if (buf.length > MAX_BUFFER) {
            val excess = buf.length - MAX_BUFFER
            buf.delete(0, excess)
        }
    }

    fun clearBuffer(sessionId: String) {
        outputBuffers[sessionId]?.clear()
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
     */
    suspend fun uploadFile(sessionId: String, bytes: ByteArray, fileName: String): String {
        val conn = connections[sessionId]
            ?: throw IllegalStateException("No connection for session $sessionId")
        val remoteDir = "/tmp/claude-uploads"
        val remotePath = conn.uploadFile(bytes, remoteDir, fileName)
        FileLogger.log(TAG, "File uploaded: $remotePath (${bytes.size} bytes)")
        return remotePath
    }

    /**
     * Reconnect a disconnected session. Reuses the same session config.
     */
    suspend fun reconnectSession(sessionId: String) {
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
        outputBuffers.remove(sessionId)
        promptDetector.removeSession(sessionId)
        pendingInputs.remove(sessionId)
        _sessionActivities.update { it - sessionId }
        _contextPercents.update { it - sessionId }
        _latencies.update { it - sessionId }
        _pendingCounts.update { it - sessionId }
        tabManager.removeTab(sessionId)
    }

    suspend fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
        connections.clear()
        outputBuffers.clear()
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

    fun getBuffer(sessionId: String): String = outputBuffers[sessionId]?.toString() ?: ""

    private fun generateId(): String {
        val bytes = Random.nextBytes(16)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "SessionOrchestrator"
        private const val MAX_BUFFER = 256 * 1024 // 256KB per session
    }
}
