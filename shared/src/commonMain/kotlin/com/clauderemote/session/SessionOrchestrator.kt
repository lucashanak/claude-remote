package com.clauderemote.session

import com.clauderemote.connection.SshManager
import com.clauderemote.model.*
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.Dispatchers
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

    // Terminal output callback — set by the platform (Android WebView, Desktop terminal)
    var onTerminalOutput: ((sessionId: String, data: String) -> Unit)? = null

    // Tab switch callback — platform clears terminal and replays buffer
    var onTabSwitched: ((sessionId: String, bufferedOutput: String) -> Unit)? = null

    // Disconnect callback
    var onSessionDisconnect: ((sessionId: String) -> Unit)? = null

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
        val buffer = outputBuffers[id]?.toString() ?: ""
        FileLogger.log(TAG, "Switching to tab $id (buffer: ${buffer.length} chars)")
        onTabSwitched?.invoke(id, buffer)
    }

    private suspend fun connectSsh(session: ClaudeSession, isNewTmuxSession: Boolean) {
        val sshManager = SshManager(serverStorage)
        connections[session.id] = sshManager

        sshManager.connect(
            session.server,
            onOutput = { data ->
                appendToBuffer(session.id, data)
                if (tabManager.activeTabId.value == session.id) {
                    onTerminalOutput?.invoke(session.id, data)
                }
            },
            onDisconnect = {
                tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
                onSessionDisconnect?.invoke(session.id)
            }
        )

        if (isNewTmuxSession) {
            // New session: create tmux + launch claude
            val command = ClaudeConfig.buildTmuxLaunchCommand(
                tmuxSessionName = session.tmuxSessionName,
                folder = session.folder,
                mode = session.mode,
                model = session.model
            )
            sshManager.sendInput(command + "\n")
        } else {
            // Attach to existing tmux session — don't send claude command
            val command = "tmux attach-session -t '${session.tmuxSessionName.replace("'", "\\'")}'"
            sshManager.sendInput(command + "\n")
        }
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

    fun sendInput(sessionId: String, data: String) {
        connections[sessionId]?.sendInput(data)
    }

    fun sendBytes(sessionId: String, data: ByteArray) {
        connections[sessionId]?.sendBytes(data)
    }

    fun resize(sessionId: String, cols: Int, rows: Int) {
        connections[sessionId]?.resize(cols, rows)
    }

    fun sendClaudeCommand(sessionId: String, command: String) {
        val conn = connections[sessionId]
        if (conn == null) {
            FileLogger.error(TAG, "sendClaudeCommand: no connection for session $sessionId")
            return
        }
        FileLogger.log(TAG, "sendClaudeCommand: ${command.length} bytes to $sessionId")
        conn.sendInput(command)
    }

    fun switchModel(sessionId: String, model: ClaudeModel) {
        sendInput(sessionId, ClaudeConfig.modelSwitchCommand(model))
    }

    fun sendEscape(sessionId: String) {
        sendInput(sessionId, ClaudeConfig.escapeSequence())
    }

    suspend fun disconnectSession(sessionId: String) {
        connections[sessionId]?.disconnect()
        connections.remove(sessionId)
        outputBuffers.remove(sessionId)
        tabManager.removeTab(sessionId)
    }

    suspend fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
        connections.clear()
        outputBuffers.clear()
    }

    fun getConnection(sessionId: String): SshManager? = connections[sessionId]

    private fun generateId(): String {
        val bytes = Random.nextBytes(16)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "SessionOrchestrator"
        private const val MAX_BUFFER = 256 * 1024 // 256KB per session
    }
}
