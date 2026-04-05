package com.clauderemote.session

import com.clauderemote.connection.SshManager
import com.clauderemote.model.*
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.Dispatchers
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

        val session = ClaudeSession(
            id = sessionId,
            server = server,
            folder = folder,
            mode = mode,
            model = model,
            tmuxSessionName = tmuxSessionName,
            connectionType = connectionType,
            status = SessionStatus.CONNECTING
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
            onSessionActive?.invoke(session)
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
        promptDetector.suppressDetection = true
        onTabSwitched?.invoke(id, buffer)
        promptDetector.suppressDetection = false
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
            }
        }

        sshManager.connect(
            session.server,
            onOutput = { data -> emit(data) },
            onConnectionLost = {
                // Auto-reconnect with tmux reattach
                tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
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

                tabManager.updateTabStatus(session.id, SessionStatus.ACTIVE)
                onSessionActive?.invoke(session)
                emit("\r\n\u001B[32mReconnected!\u001B[0m\r\n")
                FileLogger.log(TAG, "Auto-reconnect succeeded for ${session.id}")
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
        if (conn == null) { warnNoConnection(sessionId); return }
        conn.sendInput(data)
    }

    fun sendBytes(sessionId: String, data: ByteArray) {
        promptDetector.onUserInput(sessionId)
        val conn = connections[sessionId]
        if (conn == null) { warnNoConnection(sessionId); return }
        conn.sendBytes(data)
    }

    fun resize(sessionId: String, cols: Int, rows: Int) {
        connections[sessionId]?.resize(cols, rows)
    }

    fun sendClaudeCommand(sessionId: String, command: String) {
        val conn = connections[sessionId]
        if (conn == null) { warnNoConnection(sessionId); return }
        FileLogger.log(TAG, "sendClaudeCommand: ${command.length} bytes to $sessionId")
        promptDetector.onUserInput(sessionId)
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
        connections[sessionId]?.disconnect()
        connections.remove(sessionId)
        outputBuffers.remove(sessionId)
        promptDetector.removeSession(sessionId)
        tabManager.removeTab(sessionId)
    }

    suspend fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
        connections.clear()
        outputBuffers.clear()
    }

    fun getConnection(sessionId: String): SshManager? = connections[sessionId]

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
