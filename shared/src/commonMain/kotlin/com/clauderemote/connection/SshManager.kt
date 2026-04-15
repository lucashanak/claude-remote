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

    /**
     * Upload file via exec channel + cat (works over Cloudflare WebSocket
     * tunnel where SFTP subsystem may not be available).
     */
    suspend fun uploadFile(bytes: ByteArray, remoteDir: String, fileName: String): String = withContext(Dispatchers.IO) {
        val sess = session ?: throw IllegalStateException("Not connected")
        FileLogger.log(TAG, "uploadFile: session.isConnected=${sess.isConnected}, bytes=${bytes.size}, file=$fileName")
        val safeName = fileName.replace("'", "'\\''")
        val remotePath = "$remoteDir/$safeName"
        val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
        ch.setCommand("mkdir -p '$remoteDir' && cat > '$remotePath'")
        ch.inputStream = null
        val os = ch.outputStream
        FileLogger.log(TAG, "uploadFile: connecting exec channel...")
        ch.connect(5000)
        FileLogger.log(TAG, "uploadFile: exec channel connected, writing ${bytes.size} bytes")
        try {
            os.write(bytes)
            os.flush()
            os.close()
            FileLogger.log(TAG, "uploadFile: bytes written, waiting for cat to finish")
            // Wait for remote cat to finish
            val deadline = System.currentTimeMillis() + 10_000L
            while (!ch.isClosed && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            val exit = ch.exitStatus
            FileLogger.log(TAG, "uploadFile: done, closed=${ch.isClosed}, exit=$exit")
            if (exit != 0) throw java.io.IOException("Upload failed (exit $exit)")
            remotePath
        } finally { ch.disconnect() }
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
