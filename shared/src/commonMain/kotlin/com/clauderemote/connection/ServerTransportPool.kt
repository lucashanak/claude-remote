package com.clauderemote.connection

import com.clauderemote.model.AuthMethod
import com.clauderemote.model.SshServer
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Pool of shared SSH transports (jsch [Session]s) for ONE server. Every tab
 * used to open its OWN transport — at 21 sessions on one box that meant 21
 * TCP/WebSocket connections, 21 KEX+auth handshakes after every network blip,
 * 21 keepalive timers and (over Cloudflare) 21 OkHttp WebSockets with their
 * own ping schedulers. SSH multiplexes channels over one transport natively,
 * so the pool packs up to [SHELLS_PER_TRANSPORT] tabs (shell channels) per
 * transport; the transient exec channels (transcript polls, git/usage probes,
 * notify watcher) ride the same shared sessions via [SshManager.getSession].
 *
 * Lifecycle:
 *  - [lease] hands out a connected transport with shell capacity for the
 *    server's EFFECTIVE endpoint (Tailscale vs Cloudflare resolve to different
 *    host/proxy configs and never share a transport), connecting a fresh one
 *    when none fits. Runs under the pool mutex — after a full-link blip the
 *    reconnecting tabs serialize here and reuse the first reconnector's new
 *    transport instead of each performing its own handshake.
 *  - [release] drops a tab's claim; the last one out disconnects the transport.
 *  - [reportDead] marks a transport unusable on a WRITE timeout — the one
 *    zombie signal where the TCP is dead but `isConnected` still reads true
 *    (keepalive needs ~20 s to notice). A channel-only EOF (user exits the
 *    remote shell) must NOT report the transport dead: the siblings riding it
 *    are healthy, and killing it would cascade a reconnect across all of them.
 */
class ServerTransportPool(private val serverStorage: ServerStorage) {

    private class Transport(val session: Session, val host: String, val port: Int, val viaProxy: Boolean) {
        /** SshManager instances currently holding a shell on this transport. */
        val lessees = mutableSetOf<Any>()
        /** Write-timeout zombie flag — see [reportDead]. */
        @Volatile var reportedDead = false
        val usable: Boolean get() = !reportedDead && session.isConnected
    }

    // CopyOnWriteArrayList: reportDead() iterates without the mutex (it runs
    // in a write-failure path that must not suspend); all structural changes
    // happen under [mutex].
    private val transports = java.util.concurrent.CopyOnWriteArrayList<Transport>()
    private val mutex = Mutex()

    /** Number of live (usable) TCP transports — the real connection count,
     *  unlike the per-session SshManager count. For data-usage metering. */
    fun liveCount(): Int = transports.count { it.usable }

    /** Any usable transport's session, for one-off execs (tmux listing, status
     *  polls) that would otherwise open a fresh SSH-over-Cloudflare connection
     *  every time — reuse the pooled transport instead. Null if none is live. */
    fun anyUsableSession(): Session? = transports.firstOrNull { it.usable }?.session

    /**
     * Lease a connected transport for [server], connecting one if needed.
     * [lessee] identifies the holder for [release].
     */
    suspend fun lease(server: SshServer, connectTimeout: Int, lessee: Any): Session = mutex.withLock {
        prune()
        val existing = transports.firstOrNull {
            it.usable && it.host == server.host && it.port == server.port &&
                it.viaProxy == server.useCloudflareProxy &&
                it.lessees.size < SHELLS_PER_TRANSPORT
        }
        if (existing != null) {
            existing.lessees.add(lessee)
            FileLogger.log(TAG, "Leased existing transport to ${server.host} (${existing.lessees.size} shells)")
            return@withLock existing.session
        }
        val session = connectTransport(server, connectTimeout)
        val t = Transport(session, server.host, server.port, server.useCloudflareProxy)
        t.lessees.add(lessee)
        transports.add(t)
        FileLogger.log(TAG, "New transport to ${server.host}:${server.port} (${transports.size} in pool)")
        session
    }

    /** Release [lessee]'s claim; the last one out disconnects the transport. */
    suspend fun release(lessee: Any): Unit = mutex.withLock {
        val t = transports.firstOrNull { lessee in it.lessees } ?: return@withLock
        t.lessees.remove(lessee)
        if (t.lessees.isEmpty()) {
            transports.remove(t)
            try { t.session.disconnect() } catch (_: Exception) {}
            FileLogger.log(TAG, "Disconnected drained transport to ${t.host} (${transports.size} left)")
        }
    }

    /**
     * Mark [session]'s transport dead. Call ONLY on transport-level evidence
     * (write timeout); read-loop EOF alone is ambiguous — a genuinely dead
     * link flips `isConnected` false by itself (RST or keepalive), which
     * [lease]'s `usable` check already handles.
     */
    fun reportDead(session: Session) {
        transports.forEach { if (it.session === session) it.reportedDead = true }
    }

    /**
     * Kill every transport NOW. For the Android network-lost event: the
     * interface our TCP rode is gone, but keepalive wouldn't notice for up to
     * 20 s (fg) / 2 min (bg) — frozen tabs the whole time. Disconnecting
     * unblocks the parked channel reads immediately, so every manager's read
     * loop EOFs and per-session autoReconnect (gated + pooled) re-establishes
     * on the new network the moment it's up. Structural removal from the list
     * happens on the next lease()'s prune.
     */
    fun teardownAll() {
        transports.forEach {
            it.reportedDead = true
            try { it.session.disconnect() } catch (_: Exception) {}
        }
    }

    /** Drop unusable transports. Caller holds [mutex]. */
    private fun prune() {
        val dead = transports.filter { !it.usable }
        for (t in dead) {
            transports.remove(t)
            try { t.session.disconnect() } catch (_: Exception) {}
            if (t.lessees.isNotEmpty()) {
                FileLogger.log(TAG, "Pruned dead transport to ${t.host} (${t.lessees.size} lessees will re-lease)")
            }
        }
    }

    /** Full SSH handshake — mirrors what SshManager.connect used to do per tab. */
    private suspend fun connectTransport(server: SshServer, connectTimeout: Int): Session =
        withContext(Dispatchers.IO) {
            FileLogger.log(TAG, "Connecting transport to ${server.host}:${server.port} as ${server.username}")
            val jsch = JSch()
            if (server.authMethod == AuthMethod.KEY && server.privateKey != null) {
                jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
            }
            val sess = jsch.getSession(server.username, server.host, server.port)
            if (server.authMethod == AuthMethod.PASSWORD && server.password != null) {
                sess.setPassword(server.password)
            }
            sess.setConfig("StrictHostKeyChecking", "no")
            // Compression: transcript JSONL + terminal output compress 5-10×,
            // and on weak/metered links the payload IS the cost. mwiede jsch
            // ships a java.util.zip implementation (no jzlib needed); "none"
            // stays in the list so a server with Compression=no still
            // negotiates cleanly.
            sess.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
            sess.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
            // Keepalive via the EXPLICIT API (milliseconds!) — see SshManager.
            sess.setServerAliveInterval(10_000)
            sess.setServerAliveCountMax(2)
            sess.userInfo = TofuUserInfo(server.host, serverStorage)
            sess.timeout = connectTimeout
            if (server.useCloudflareProxy) {
                FileLogger.log(TAG, "Using Cloudflare tunnel proxy for ${server.host}")
                sess.setProxy(CloudflareProxy(server.host, server.cloudflareToken))
            }
            sess.connect(connectTimeout)
            sess
        }

    companion object {
        private const val TAG = "ServerTransportPool"
        // OpenSSH's default MaxSessions is 10 channels per connection, and the
        // transient exec load scales with the number of SESSIONS on the
        // transport (each runs its own transcript/git/sessionId probes, which
        // all burst at once right after a reconnect). 3 shells + ~2 concurrent
        // execs each stays inside the budget; 5 shells overflowed it in the
        // post-reconnect burst ("administratively prohibited"). Bonus: a
        // write-timeout transport teardown now cascades ≤3 tabs, not 5.
        private const val SHELLS_PER_TRANSPORT = 3
    }
}
