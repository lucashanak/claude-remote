package com.clauderemote.connection

import com.clauderemote.model.AuthMethod
import com.clauderemote.model.SshServer
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.ChannelSftp
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.OutputStream

class SshManager(
    private val serverStorage: ServerStorage,
    private val connectTimeout: Int = 15000
) {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var onConnectionLost: (() -> Unit)? = null
    private val writeMutex = Mutex()

    @Volatile private var disconnected = false

    val isConnected: Boolean get() = !disconnected && session?.isConnected == true && channel?.isConnected == true

    /**
     * Connect to server and open a shell channel.
     * onConnectionLost is called when connection drops (for reconnect by orchestrator).
     */
    suspend fun connect(
        server: SshServer,
        onOutput: (String) -> Unit,
        onConnectionLost: () -> Unit
    ): Session = withContext(Dispatchers.IO) {
        disconnected = false
        this@SshManager.onConnectionLost = onConnectionLost
        ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        FileLogger.log(TAG, "Connecting to ${server.host}:${server.port} as ${server.username}")

        // Note: xterm reset sequences removed — they caused DA response
        // (0;276;0c) to leak into SSH shell as text input

        val jsch = JSch()
        if (server.authMethod == AuthMethod.KEY && server.privateKey != null) {
            jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
        }

        val sess = jsch.getSession(server.username, server.host, server.port)
        if (server.authMethod == AuthMethod.PASSWORD && server.password != null) {
            sess.setPassword(server.password)
        }
        sess.setConfig("StrictHostKeyChecking", "no")
        sess.setConfig("ServerAliveInterval", "30")
        sess.setConfig("ServerAliveCountMax", "3")
        sess.userInfo = TofuUserInfo(server.host, serverStorage)
        sess.timeout = connectTimeout

        // Cloudflare Tunnel: route SSH over WebSocket instead of direct TCP
        if (server.useCloudflareProxy) {
            FileLogger.log(TAG, "Using Cloudflare tunnel proxy for ${server.host}")
            sess.setProxy(CloudflareProxy(server.host, server.cloudflareToken))
        }

        sess.connect(connectTimeout)
        session = sess
        FileLogger.log(TAG, "SSH session connected")

        // Port forwarding
        for (pf in server.portForwards) {
            try {
                when (pf.type) {
                    "L" -> {
                        sess.setPortForwardingL(pf.localPort, pf.remoteHost, pf.remotePort)
                        onOutput("\u001B[33mPort forward: L${pf.localPort} -> ${pf.remoteHost}:${pf.remotePort}\u001B[0m\r\n")
                    }
                    "R" -> {
                        sess.setPortForwardingR(pf.remotePort, pf.remoteHost, pf.localPort)
                        onOutput("\u001B[33mPort forward: R${pf.remotePort} -> ${pf.remoteHost}:${pf.localPort}\u001B[0m\r\n")
                    }
                }
            } catch (e: Exception) {
                onOutput("\u001B[31mPort forward failed: ${e.message}\u001B[0m\r\n")
            }
        }

        val ch = sess.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color")
        val inputStream = ch.inputStream
        outputStream = ch.outputStream
        ch.connect(connectTimeout)
        channel = ch
        FileLogger.log(TAG, "Shell channel opened")

        // Read loop — delivers SSH data immediately to onOutput
        readJob = ioScope.launch {
            try {
                val buf = ByteArray(16384)
                val decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                val charBuf = java.nio.CharBuffer.allocate(16384 + 4)
                while (isActive && ch.isConnected) {
                    val n = inputStream.read(buf)
                    if (n < 0) break
                    if (n == 0) { delay(10); if (ch.isClosed) break; continue }
                    val byteBuf = java.nio.ByteBuffer.wrap(buf, 0, n)
                    charBuf.clear()
                    decoder.decode(byteBuf, charBuf, false)
                    charBuf.flip()
                    if (charBuf.hasRemaining()) onOutput(charBuf.toString())
                }
            } catch (_: Exception) {
            } finally {
                if (!disconnected) {
                    disconnected = true
                    FileLogger.log(TAG, "Connection lost")
                    onConnectionLost()
                }
            }
        }

        sess
    }

    fun sendInput(data: String) {
        if (disconnected) return
        ioScope.launch { writeToSsh(data.toByteArray(Charsets.UTF_8)) }
    }

    fun sendBytes(data: ByteArray) {
        if (disconnected) return
        ioScope.launch { writeToSsh(data) }
    }

    private suspend fun writeToSsh(data: ByteArray) {
        try {
            withTimeout(WRITE_TIMEOUT) {
                writeMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val os = outputStream ?: return@withContext
                        os.write(data)
                        os.flush()
                    }
                }
            }
        } catch (e: Exception) {
            if (!disconnected) {
                disconnected = true
                FileLogger.error(TAG, "SSH write failed/timeout", e)
                onConnectionLost?.invoke()
            }
        }
    }

    var lastCols = 80; private set
    var lastRows = 24; private set

    fun resize(cols: Int, rows: Int) {
        lastCols = cols; lastRows = rows
        try { channel?.setPtySize(cols, rows, cols * 8, rows * 16) } catch (_: Exception) {}
    }

    suspend fun disconnect() {
        disconnected = true
        onConnectionLost = null
        FileLogger.log(TAG, "Disconnecting...")
        readJob?.cancelAndJoin()
        readJob = null
        ioScope.coroutineContext[Job]?.cancelAndJoin()
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        channel = null
        session = null
        outputStream = null
        FileLogger.log(TAG, "Disconnected")
    }

    suspend fun uploadFile(bytes: ByteArray, remoteDir: String, fileName: String): String = withContext(Dispatchers.IO) {
        val sess = session ?: throw IllegalStateException("Not connected")
        val sftp = sess.openChannel("sftp") as ChannelSftp
        sftp.connect(5000) // 5s — fail fast on dead WebSocket so caller can retry after reconnect
        try {
            try { sftp.mkdir(remoteDir) } catch (_: Exception) {}
            val remotePath = "$remoteDir/$fileName"
            sftp.put(bytes.inputStream(), remotePath)
            remotePath
        } finally { sftp.disconnect() }
    }

    /**
     * Quick probe: open+close an exec channel to verify the SSH transport
     * is truly alive (catches dead Cloudflare WebSocket where isConnected
     * still returns true because JSch hasn't detected the failure yet).
     *
     * If probe fails, kills the JSch session so the read loop exits and
     * fires onConnectionLost → autoReconnect.
     */
    suspend fun probeConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val sess = session ?: return@withContext false
            val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
            ch.setCommand("true")
            ch.connect(3000)
            ch.disconnect()
            true
        } catch (_: Exception) {
            // Probe failed — transport is dead. Kill JSch session to unblock
            // the read loop (which fires onConnectionLost → autoReconnect).
            FileLogger.log(TAG, "Probe failed, forcing session disconnect to trigger reconnect")
            try { session?.disconnect() } catch (_: Exception) {}
            false
        }
    }

    fun getSession(): Session? = session

    companion object {
        private const val TAG = "SshManager"
        private const val WRITE_TIMEOUT = 5000L
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
