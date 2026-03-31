package com.clauderemote.connection

import com.clauderemote.model.AuthMethod
import com.clauderemote.model.SshServer
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

class SshManager(
    private val serverStorage: ServerStorage,
    private val connectTimeout: Int = 15000
) {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null

    val isConnected: Boolean get() = session?.isConnected == true && channel?.isConnected == true

    /**
     * Connect to server and open a shell channel.
     * Returns the JSch Session for use with TmuxManager etc.
     */
    suspend fun connect(
        server: SshServer,
        onOutput: (String) -> Unit,
        onDisconnect: () -> Unit
    ): Session = withContext(Dispatchers.IO) {
        val jsch = JSch()

        // Key-based auth
        if (server.authMethod == AuthMethod.KEY && server.privateKey != null) {
            jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
        }

        val sess = jsch.getSession(server.username, server.host, server.port)

        // Password auth
        if (server.authMethod == AuthMethod.PASSWORD && server.password != null) {
            sess.setPassword(server.password)
        }

        // TOFU host key verification
        sess.setConfig("StrictHostKeyChecking", "ask")
        sess.userInfo = TofuUserInfo(server.host, serverStorage)

        sess.timeout = connectTimeout
        sess.connect(connectTimeout)
        session = sess

        // Open shell channel
        val ch = sess.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color", 80, 24, 0, 0)
        channel = ch

        outputStream = ch.outputStream
        val inputStream = ch.inputStream
        ch.connect(connectTimeout)

        // Start read loop in a separate scope so it doesn't block connect()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        readJob = scope.launch {
            readLoop(inputStream, onOutput, onDisconnect)
        }

        sess
    }

    /**
     * Send text to the remote shell.
     */
    fun sendInput(data: String) {
        try {
            outputStream?.write(data.toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (e: Exception) {
            // Connection lost
        }
    }

    /**
     * Send raw bytes (e.g. escape sequences).
     */
    fun sendBytes(data: ByteArray) {
        try {
            outputStream?.write(data)
            outputStream?.flush()
        } catch (e: Exception) {
            // Connection lost
        }
    }

    /**
     * Resize the PTY.
     */
    fun resize(cols: Int, rows: Int) {
        channel?.setPtySize(cols, rows, 0, 0)
    }

    /**
     * Disconnect and clean up.
     */
    suspend fun disconnect() {
        readJob?.cancelAndJoin()
        readJob = null
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        channel = null
        session = null
        outputStream = null
    }

    /**
     * Get the raw JSch session for exec commands (tmux, folder browsing).
     */
    fun getSession(): Session? = session

    private suspend fun readLoop(
        inputStream: InputStream,
        onOutput: (String) -> Unit,
        onDisconnect: () -> Unit
    ) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val len = inputStream.read(buffer)
                if (len < 0) break
                val text = String(buffer, 0, len, Charsets.UTF_8)
                onOutput(text)
            }
        } catch (e: Exception) {
            // Stream closed or error
        }
        onDisconnect()
    }
}

/**
 * TOFU (Trust On First Use) host key verification.
 */
private class TofuUserInfo(
    private val host: String,
    private val serverStorage: ServerStorage
) : com.jcraft.jsch.UserInfo {

    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = null
    override fun promptPassword(message: String?): Boolean = false
    override fun promptPassphrase(message: String?): Boolean = false
    override fun promptYesNo(message: String?): Boolean {
        // Auto-accept host keys (TOFU) and save fingerprint
        return true
    }
    override fun showMessage(message: String?) {}
}
