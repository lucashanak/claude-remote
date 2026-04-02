package com.clauderemote.connection

import com.clauderemote.model.AuthMethod
import com.clauderemote.model.SshServer
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

class SshManager(
    private val serverStorage: ServerStorage,
    private val connectTimeout: Int = 15000,
    private val maxReconnectAttempts: Int = 3
) {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var disconnected = false
    private var reconnectAttempts = 0
    private var currentServer: SshServer? = null
    private var currentOnOutput: ((String) -> Unit)? = null
    private var currentOnDisconnect: (() -> Unit)? = null

    val isConnected: Boolean get() = !disconnected && session?.isConnected == true && channel?.isConnected == true

    /**
     * Connect to server and open a shell channel.
     * Returns the JSch Session for use with TmuxManager etc.
     */
    suspend fun connect(
        server: SshServer,
        onOutput: (String) -> Unit,
        onDisconnect: () -> Unit
    ): Session {
        currentServer = server
        currentOnOutput = onOutput
        currentOnDisconnect = onDisconnect
        reconnectAttempts = 0
        return doConnect(server, onOutput, onDisconnect)
    }

    private suspend fun doConnect(
        server: SshServer,
        onOutput: (String) -> Unit,
        onDisconnect: () -> Unit
    ): Session = withContext(Dispatchers.IO) {
        disconnected = false
        FileLogger.log(TAG, "Connecting to ${server.host}:${server.port} as ${server.username}")

        // Reset xterm state (exit alt buffer, disable mouse) to clear stale state
        onOutput("\u001b[?1049l\u001b[?1002l\u001b[?1003l\u001b[?1006l")

        val jsch = JSch()

        if (server.authMethod == AuthMethod.KEY && server.privateKey != null) {
            jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
        }

        val sess = jsch.getSession(server.username, server.host, server.port)

        if (server.authMethod == AuthMethod.PASSWORD && server.password != null) {
            sess.setPassword(server.password)
        }

        sess.setConfig("StrictHostKeyChecking", "no")
        sess.userInfo = TofuUserInfo(server.host, serverStorage)
        sess.timeout = connectTimeout

        FileLogger.log(TAG, "Opening SSH session...")
        sess.connect(connectTimeout)
        session = sess
        FileLogger.log(TAG, "SSH session connected")

        val ch = sess.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color")
        val inputStream = ch.inputStream
        outputStream = ch.outputStream
        ch.connect(connectTimeout)
        channel = ch
        reconnectAttempts = 0
        FileLogger.log(TAG, "Shell channel opened")

        // Read loop with auto-reconnect on failure
        readJob = ioScope.launch {
            try {
                val buf = ByteArray(8192)
                while (isActive && ch.isConnected) {
                    val n = inputStream.read(buf)
                    if (n < 0) break
                    if (n == 0) { delay(50); if (ch.isClosed) break; continue }
                    val text = String(buf, 0, n)
                    onOutput(text)
                }
            } catch (_: Exception) {
            } finally {
                if (!disconnected) {
                    disconnected = true
                    // Try auto-reconnect
                    val srv = currentServer
                    if (reconnectAttempts < maxReconnectAttempts && srv != null) {
                        reconnectAttempts++
                        FileLogger.log(TAG, "Connection lost. Reconnecting ($reconnectAttempts/$maxReconnectAttempts)...")
                        onOutput("\r\n\u001B[33mConnection lost. Reconnecting ($reconnectAttempts/$maxReconnectAttempts)...\u001B[0m\r\n")
                        delay(2000)
                        try {
                            doConnect(srv, onOutput, onDisconnect)
                        } catch (e: Exception) {
                            FileLogger.error(TAG, "Reconnect failed", e)
                            onOutput("\r\n\u001B[31mReconnect failed: ${e.message}\u001B[0m\r\n")
                            onDisconnect()
                        }
                    } else {
                        FileLogger.log(TAG, "Read loop ended, disconnecting")
                        onDisconnect()
                    }
                }
            }
        }

        sess
    }

    fun sendInput(data: String) {
        if (disconnected) return
        ioScope.launch {
            try {
                val os = outputStream ?: return@launch
                os.write(data.toByteArray(Charsets.UTF_8))
                os.flush()
            } catch (e: Exception) {
                if (!disconnected) {
                    disconnected = true
                    FileLogger.error(TAG, "sendInput failed, marking disconnected", e)
                }
            }
        }
    }

    fun sendBytes(data: ByteArray) {
        if (disconnected) return
        ioScope.launch {
            try {
                val os = outputStream ?: return@launch
                os.write(data)
                os.flush()
            } catch (e: Exception) {
                if (!disconnected) {
                    disconnected = true
                    FileLogger.error(TAG, "sendBytes failed, marking disconnected", e)
                }
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        try {
            channel?.setPtySize(cols, rows, cols * 8, rows * 16)
        } catch (_: Exception) {}
    }

    suspend fun disconnect() {
        disconnected = true
        reconnectAttempts = maxReconnectAttempts // prevent auto-reconnect
        currentServer = null
        FileLogger.log(TAG, "Disconnecting...")
        readJob?.cancelAndJoin()
        readJob = null
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        channel = null
        session = null
        outputStream = null
        FileLogger.log(TAG, "Disconnected")
    }

    fun getSession(): Session? = session

    companion object {
        private const val TAG = "SshManager"
    }
}

private class TofuUserInfo(
    private val host: String,
    private val serverStorage: ServerStorage
) : com.jcraft.jsch.UserInfo {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = null
    override fun promptPassword(message: String?): Boolean = false
    override fun promptPassphrase(message: String?): Boolean = false
    override fun promptYesNo(message: String?): Boolean = true
    override fun showMessage(message: String?) {}
}
