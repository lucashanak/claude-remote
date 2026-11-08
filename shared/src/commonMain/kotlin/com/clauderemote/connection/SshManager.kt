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
    private val connectTimeout: Int = 15000,
    /**
     * When set, [connect] LEASES a shared transport from this pool instead of
     * opening its own TCP/WebSocket + KEX + auth — this manager then owns only
     * its shell channel on the shared jsch Session. [disconnect] releases the
     * lease (never disconnects the shared session; sibling tabs ride it).
     * Null ⇒ legacy standalone transport (cleanup connections, tests).
     */
    private val transportPool: ServerTransportPool? = null,
) {
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var onConnectionLost: (() -> Unit)? = null
    private val writeMutex = Mutex()
    /** True while [session] is a pool lease rather than our own transport. */
    @Volatile private var leased = false

    @Volatile private var disconnected = false

    /** Keepalive (ms) for a transport this manager opens itself; [setKeepAliveInterval] updates it. */
    @Volatile private var keepAliveMs = 10_000

    val isConnected: Boolean get() = !disconnected && session?.isConnected == true && channel?.isConnected == true

    /**
     * Connect to server and open a shell channel.
     * onConnectionLost is called when connection drops (for reconnect by orchestrator).
     */
    suspend fun connect(
        server: SshServer,
        onOutput: (String) -> Unit,
        onConnectionLost: () -> Unit,
        initialCols: Int = 80,
        initialRows: Int = 24,
    ): Session = withContext(Dispatchers.IO) {
        disconnected = false
        this@SshManager.onConnectionLost = onConnectionLost
        ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        FileLogger.log(TAG, "Connecting to ${server.host}:${server.port} as ${server.username}")

        // Note: xterm reset sequences removed — they caused DA response
        // (0;276;0c) to leak into SSH shell as text input

        // Pool v2 (comm_pool ON for this server): the exec transport carries the L/R port
        // forwards and a refused shell channel (MaxSessions) spills to another transport.
        val poolV2 = transportPool != null && com.clauderemote.session.service.CommFlags.effectiveFor(server.id).pool
        var spillLeft = if (poolV2) 1 else 0
        var sess: Session
        lateinit var ch: ChannelShell
        lateinit var inputStream: java.io.InputStream
        while (true) {
            sess = if (transportPool != null) {
                // Shared transport: one handshake serves up to `shellCap` tabs.
                leased = true
                transportPool.lease(server, connectTimeout, this@SshManager)
            } else {
                openStandaloneTransport(server)
            }
            session = sess
            FileLogger.log(TAG, "SSH session ready (leased=$leased)")

            // Port forwarding. On a pool-v2 server the exec transport owns the forwards
            // (registered once there, re-registered after each reconnect), so a shell
            // session must not bind the same ports again.
            if (transportPool?.execForwardsOwned(server.id) != true) {
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
                        // On a shared (leased) transport the first tab already
                        // registered the forward — the rebind failure is expected
                        // noise for every later tab, not an error worth printing.
                        if (leased) FileLogger.log(TAG, "Port forward on shared transport: ${e.message}")
                        else onOutput("\u001B[31mPort forward failed: ${e.message}\u001B[0m\r\n")
                    }
                }
            }

            // Streams MUST be obtained before connect() (jsch wires the pipe into
            // the channel's IO at that point; data arriving pre-getInputStream is
            // lost). The whole open sequence releases the pool lease on failure —
            // otherwise a dead manager holds a shell slot until the next
            // reconnect attempt/teardown gets around to disconnect().
            var opened: ChannelShell? = null
            try {
                val c = sess.openChannel("shell") as ChannelShell
                opened = c
                c.setPtyType("xterm-256color")
                // Open the pty at the real terminal size. Without this jsch defaults
                // to 80x24, which leaves tmux panes not filling the window for any
                // session that (re)connected while backgrounded (its onResize never
                // fired, so no post-attach resize corrected it).
                c.setPtySize(initialCols, initialRows, initialCols * 8, initialRows * 16)
                inputStream = c.inputStream
                outputStream = c.outputStream
                c.connect(connectTimeout)
                ch = c
                break
            } catch (e: Exception) {
                val wasLeased = leased
                // Spill: the server refused the channel because this transport is at its
                // MaxSessions. Mark it full BEFORE giving the lease back (else another tab could be
                // handed the same full transport in between), then lease again once.
                val spill = wasLeased && spillLeft > 0 && isChannelRefused(e, opened)
                if (spill) transportPool?.reportFull(sess)
                if (leased) {
                    transportPool?.release(this@SshManager)
                    leased = false
                    session = null
                }
                if (spill) {
                    spillLeft--
                    FileLogger.log(TAG, "Shell open refused (${e.message}, exitStatus=${opened?.exitStatus}) — spilling to another transport")
                    continue
                }
                throw e
            }
        }
        channel = ch
        // Keep the resize no-op check consistent with the pty size we opened at.
        lastCols = initialCols; lastRows = initialRows
        FileLogger.log(TAG, "Shell channel opened")

        // Read loop — delivers SSH data immediately to onOutput
        readJob = ioScope.launch {
            // Why the loop ended (dávka B, F4 — log only): the channel's own end, refined below by the transport's.
            var ended = "channel closed"
            try {
                val buf = ByteArray(16384)
                val decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                val charBuf = java.nio.CharBuffer.allocate(16384 + 4)
                while (isActive && ch.isConnected) {
                    val n = inputStream.read(buf)
                    if (n < 0) { ended = "EOF"; break }
                    if (n == 0) { delay(10); if (ch.isClosed) break; continue }
                    val byteBuf = java.nio.ByteBuffer.wrap(buf, 0, n)
                    charBuf.clear()
                    decoder.decode(byteBuf, charBuf, false)
                    charBuf.flip()
                    if (charBuf.hasRemaining()) onOutput(charBuf.toString())
                }
            } catch (e: Exception) {
                ended = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                if (!disconnected) {
                    disconnected = true
                    FileLogger.log(TAG, "Connection lost " + lostCause(sess, ch, ended))
                    onConnectionLost()
                }
            }
        }

        sess
    }

    /** Our own TCP/WebSocket transport (no pool): KEX + auth for this manager alone. */
    private suspend fun openStandaloneTransport(server: SshServer): Session {
        val jsch = JSch()
        if (server.authMethod == AuthMethod.KEY && server.privateKey != null) {
            jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
        }
        val own = jsch.getSession(server.username, server.host, server.port)
        if (server.authMethod == AuthMethod.PASSWORD && server.password != null) {
            own.setPassword(server.password)
        }
        own.setConfig("StrictHostKeyChecking", "no")
        // Compression — same rationale as ServerTransportPool.connectTransport.
        own.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
        own.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
        // Keepalive via the EXPLICIT API, not setConfig: JSch's interval is in
        // MILLISECONDS and the setConfig string key isn't reliably applied —
        // the old setConfig("ServerAliveInterval","30") was either ignored or
        // meant a 30ms interval. 10s × 2 misses ⇒ a silently-dead link (NAT
        // timeout, radio handoff on a flaky roaming network) is detected in
        // ~20s instead of never/90s, which is what re-arms auto-reconnect.
        // [keepAliveMs] is 10 s until [setKeepAliveInterval] changes it.
        // C1: the read timeout before the interval (which sets it to itself): see ServerTransportPool.connectTransport
        own.timeout = connectTimeout
        own.setServerAliveInterval(keepAliveMs)
        own.setServerAliveCountMax(2)
        own.userInfo = TofuUserInfo(server.host, serverStorage)

        // Cloudflare Tunnel: route SSH over WebSocket instead of direct TCP
        val cf = if (server.useCloudflareProxy) {
            FileLogger.log(TAG, "Using Cloudflare tunnel proxy for ${server.host}")
            CloudflareProxy(server.host, server.cloudflareToken).also { own.setProxy(it) }
        } else null
        own.connect(connectTimeout)
        cf?.armKeepAlive(own)
        return own
    }

    /**
     * `cause=…` of the "Connection lost" line: `local: <why>` when WE closed the shared transport (network teardown, …),
     * `transport: <jsch's reason>` when the transport ended under us, else the channel's own end (`EOF`, an exception
     * `<Class>: <message>`, `channel closed`) with whether the transport is still up and the channel's exit status.
     */
    private fun lostCause(sess: Session, ch: ChannelShell, channelEnd: String): String {
        val transportEnd = TransportEnd.of(sess)?.cause
        return when {
            transportEnd == null -> "cause=$channelEnd transport=${if (sess.isConnected) "up" else "down"} exitStatus=${ch.exitStatus}"
            transportEnd.startsWith("local") -> "cause=$transportEnd"
            else -> "cause=transport: $transportEnd (channel: $channelEnd)"
        }
    }

    /** `channel is not opened.` with an exit status of 1 or 2 is jsch's report of a channel the server refused (MaxSessions). */
    private fun isChannelRefused(e: Exception, ch: com.jcraft.jsch.Channel?): Boolean =
        e is com.jcraft.jsch.JSchException && e.message?.contains("channel is not opened") == true &&
            (ch?.exitStatus ?: -1) in 1..2

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
                // A write timeout is the one ZOMBIE signal: the TCP is dead but
                // jsch still reports isConnected (keepalive needs ~20 s). Tell
                // the pool so reconnecting siblings don't get the corpse
                // re-leased. (A read-EOF must NOT report — a channel-only EOF
                // would falsely kill the healthy shared transport.)
                if (leased) session?.let { transportPool?.reportDead(it) }
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
        // The channel first: the read loop sits in a BLOCKING read of its stream (cancelling the coroutine does
        // not interrupt it), so joining it before the channel is closed waited for the shell to say something -
        // minutes for a quiet one. Closing the channel ends that read at once.
        try { channel?.disconnect() } catch (_: Exception) {}
        readJob?.cancelAndJoin()
        readJob = null
        ioScope.coroutineContext[Job]?.cancelAndJoin()
        if (leased) {
            // Shared transport: NEVER disconnect the session — sibling tabs
            // ride it. Release the lease; the pool disconnects it when the
            // last lessee is gone.
            transportPool?.release(this)
            leased = false
        } else {
            try { session?.disconnect() } catch (_: Exception) {}
        }
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
            // Throttle writes — sending large bursts through Cloudflare
            // WebSocket tunnel causes cloudflared to drop the connection
            // (EOFException). 4KB chunks with 20ms pauses ≈ 200KB/s.
            // Uses coroutine delay instead of Thread.sleep to avoid blocking
            // Dispatchers.IO threads (which caused freezes on macOS desktop).
            var off = 0
            while (off < bytes.size) {
                val len = minOf(4096, bytes.size - off)
                os.write(bytes, off, len)
                os.flush()
                off += len
                if (off < bytes.size) kotlinx.coroutines.delay(20)
            }
            os.close()
            FileLogger.log(TAG, "uploadFile: bytes written, waiting for cat to finish")
            // Wait for remote cat to finish
            val deadline = System.currentTimeMillis() + 10_000L
            while (!ch.isClosed && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(100)
            }
            val exit = ch.exitStatus
            FileLogger.log(TAG, "uploadFile: done, closed=${ch.isClosed}, exit=$exit")
            if (exit != 0) throw java.io.IOException("Upload failed (exit $exit)")
            remotePath
        } finally { ch.disconnect() }
    }

    fun getSession(): Session? = session

    /**
     * Adjust the SSH keepalive interval on the LIVE session (JSch reads it in
     * its keepalive loop, so it takes effect without reconnecting). Used to back
     * the radio off in the background (10s → 60s) and tighten it in the
     * foreground for fast dead-link detection. No-op if not connected.
     */
    fun setKeepAliveInterval(ms: Int) {
        keepAliveMs = ms
        // A leased session is a pool transport: apply the interval pool-wide so the
        // pool's other transports and the ones it connects later use it too.
        if (leased) transportPool?.setKeepAlive(ms)
        else try { session?.setServerAliveInterval(ms) } catch (_: Throwable) {}
    }

    /**
     * Open a minimal SSH session for exec-only use (no shell channel, no
     * read loop). Used by [SessionOrchestrator.forgetSession] to kill tmux
     * and push sessions.json even when the normal shell connection is absent.
     * Caller must call [disconnect] when done.
     */
    suspend fun connectForCleanup(server: SshServer) = withContext(Dispatchers.IO) {
        disconnected = false
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
        sess.timeout = 10_000
        // Same keepalive as the main connect (ms!): cleanup execs must not hang
        // forever on a silently-dead link.
        sess.setServerAliveInterval(keepAliveMs)
        sess.setServerAliveCountMax(2)
        val cf = if (server.useCloudflareProxy) CloudflareProxy(server.host, server.cloudflareToken).also { sess.setProxy(it) } else null
        sess.connect(10_000)
        cf?.armKeepAlive(sess)
        session = sess
    }

    companion object {
        private const val TAG = "SshManager"
        private const val WRITE_TIMEOUT = 5000L
    }
}

internal class TofuUserInfo(
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
