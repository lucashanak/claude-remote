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
 */
class SessionOrchestrator(
    private val serverStorage: ServerStorage,
    private val tabManager: TabManager
) {
    // One SshManager per session ID
    private val connections = mutableMapOf<String, SshManager>()

    /**
     * Launch a new Claude session:
     * 1. SSH connect to server
     * 2. Open tmux session
     * 3. Run claude command in folder
     */
    suspend fun launchSession(
        server: SshServer,
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel,
        connectionType: ConnectionType,
        tmuxSessionName: String,
        onOutput: (String) -> Unit,
        onDisconnect: () -> Unit
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

        tabManager.addTab(session)
        FileLogger.log(TAG, "Launching session: ${server.name} → $folder (${connectionType.name}, ${mode.name}, ${model.name})")

        try {
            when (connectionType) {
                ConnectionType.SSH -> connectSsh(session, onOutput, onDisconnect)
                ConnectionType.MOSH -> connectMosh(session, onOutput, onDisconnect)
            }

            // Update recent folders on server
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

    private suspend fun connectSsh(
        session: ClaudeSession,
        onOutput: (String) -> Unit,
        onDisconnect: () -> Unit
    ) {
        val sshManager = SshManager(serverStorage)
        connections[session.id] = sshManager

        sshManager.connect(session.server, onOutput) {
            tabManager.updateTabStatus(session.id, SessionStatus.DISCONNECTED)
            onDisconnect()
        }

        // Send tmux + claude command
        val command = ClaudeConfig.buildTmuxLaunchCommand(
            tmuxSessionName = session.tmuxSessionName,
            folder = session.folder,
            mode = session.mode,
            model = session.model
        )
        sshManager.sendInput(command + "\n")
    }

    private suspend fun connectMosh(
        session: ClaudeSession,
        onOutput: (String) -> Unit,
        onDisconnect: () -> Unit
    ) {
        // Mosh not yet fully integrated — fall back to SSH with warning
        FileLogger.log("SessionOrchestrator", "Mosh not yet implemented, falling back to SSH for ${session.server.name}")
        onOutput("\r\n[Warning: Mosh not yet implemented, using SSH]\r\n")
        connectSsh(session, onOutput, onDisconnect)
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

    /**
     * Send a claude slash command (e.g. /model, /plan, /clear).
     */
    fun sendClaudeCommand(sessionId: String, command: String) {
        sendInput(sessionId, command)
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
        tabManager.removeTab(sessionId)
    }

    suspend fun disconnectAll() {
        connections.values.forEach { it.disconnect() }
        connections.clear()
    }

    fun getConnection(sessionId: String): SshManager? = connections[sessionId]

    private fun generateId(): String {
        val bytes = Random.nextBytes(16)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "SessionOrchestrator"
    }
}
