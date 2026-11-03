package com.clauderemote.connection

import com.clauderemote.model.AuthMethod
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a pooled transport is for. The two roles never share a transport. */
internal enum class TransportRole(val label: String) { SHELL("shell"), EXEC("exec") }

/**
 * Which kind of path a transport took, for the `route=` field of the connect log
 * lines (the metrics extractor reads it): `cf` (Cloudflare tunnel), `tunnel` (the
 * socket rides a Tailscale/VPN interface or address), `lan` (anything else, loopback
 * included), `unknown` (the local address could not be read).
 *
 * Needed because a Tailscale endpoint can be reached over the LAN with the VPN off,
 * and that path must not be counted as a tunnel death.
 */
internal object RouteKind {
    fun classify(local: java.net.InetAddress?, remote: java.net.InetAddress?, viaProxy: Boolean, ifaceName: String? = null): String {
        if (viaProxy) return "cf"
        if (local == null) return "unknown"
        if (isTailscaleAddress(local) || (remote != null && isTailscaleAddress(remote))) return "tunnel"
        if (ifaceName != null && TUNNEL_IFACE.containsMatchIn(ifaceName)) return "tunnel"
        return "lan"
    }

    /** 100.64.0.0/10 (CGNAT range Tailscale uses) and fd7a:115c:a1e0::/48. */
    fun isTailscaleAddress(a: java.net.InetAddress): Boolean {
        val b = a.address
        return when (b.size) {
            4 -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 0x40
            16 -> (b[0].toInt() and 0xFF) == 0xFD && (b[1].toInt() and 0xFF) == 0x7A &&
                (b[2].toInt() and 0xFF) == 0x11 && (b[3].toInt() and 0xFF) == 0x5C &&
                (b[4].toInt() and 0xFF) == 0xA1 && (b[5].toInt() and 0xFF) == 0xE0
            else -> false
        }
    }

    /** The route of a CONNECTED session, from its socket's local address (reflection; `unknown` if unreadable). */
    fun ofSession(session: Session, viaProxy: Boolean): String {
        if (viaProxy) return "cf"
        return try {
            val f = Session::class.java.getDeclaredField("socket")
            f.isAccessible = true
            val sock = f.get(session) as? java.net.Socket ?: return "unknown"
            val local = sock.localAddress
            val iface = try { java.net.NetworkInterface.getByInetAddress(local)?.name } catch (_: Exception) { null }
            classify(local, sock.inetAddress, false, iface)
        } catch (_: Throwable) {
            "unknown"
        }
    }

    /**
     * The route a NEW connection to [host]:[port] would take, without sending anything:
     * a connected UDP socket only asks the OS routing table for the local address.
     */
    fun probe(host: String, port: Int, viaProxy: Boolean): String {
        if (viaProxy) return "cf"
        return try {
            java.net.DatagramSocket().use { ds ->
                val remote = java.net.InetAddress.getByName(host)
                ds.connect(java.net.InetSocketAddress(remote, port))
                val local = ds.localAddress
                val iface = try { java.net.NetworkInterface.getByInetAddress(local)?.name } catch (_: Exception) { null }
                classify(local, remote, false, iface)
            }
        } catch (_: Throwable) {
            "unknown"
        }
    }

    private val TUNNEL_IFACE = Regex("^(tailscale|ts|utun|wg|tun)\\d*$")
}

/**
 * Pool of shared SSH transports (jsch [Session]s) for ONE server. Every tab
 * used to open its OWN transport — at 21 sessions on one box that meant 21
 * TCP/WebSocket connections, 21 KEX+auth handshakes after every network blip,
 * 21 keepalive timers and (over Cloudflare) 21 OkHttp WebSockets with their
 * own ping schedulers. SSH multiplexes channels over one transport natively,
 * so the pool packs up to a per-transport shell cap (3, or 8 with `comm_pool` and
 * `comm_exec` once the server's exec transport really runs, see [CommFlags.Effective.shellCap]
 * and the `execWired` constructor parameter) tabs per transport.
 *
 * Two ROLES never share a transport: SHELL (tab shells) and EXEC (the transient
 * exec/SFTP channels of the services, owned by the exec transport manager). The
 * transport key is (host, port, viaProxy, role).
 *
 * Lifecycle:
 *  - [lease] hands out a connected SHELL transport with capacity for the
 *    server's EFFECTIVE endpoint (Tailscale vs Cloudflare resolve to different
 *    host/proxy configs and never share a transport), connecting a fresh one
 *    when none fits.
 *    - `comm_pool` ON: the handshake runs OUTSIDE the pool mutex in a pool-owned
 *      coroutine ([SingleFlight]); a reconnect wave of N tabs starts at most
 *      `ceil(N / cap)` handshakes, in parallel, and a cancelled initiator neither
 *      cancels the handshake for the others nor leaks its session.
 *    - `comm_pool` OFF: the legacy lease, literally: one handshake at a time
 *      under the mutex, cap 3.
 *  - The keepalive interval ([setKeepAlive]) applies to every transport the pool opens,
 *    also with `comm_pool` OFF (a transport connected in the background starts with the
 *    background interval); the lease itself is the old one.
 *  - [release] drops a claim; the last one out disconnects the transport.
 *  - [reportDead] marks a transport unusable on a WRITE timeout — the one
 *    zombie signal where the TCP is dead but `isConnected` still reads true
 *    (keepalive needs ~20 s to notice). A channel-only EOF (user exits the
 *    remote shell) must NOT report the transport dead: the siblings riding it
 *    are healthy, and killing it would cascade a reconnect across all of them.
 *  - [reportFull] marks a transport that REFUSED a channel (`MaxSessions`) as
 *    full: no new shell is leased on it, the ones already there are untouched.
 */
class ServerTransportPool(
    private val serverStorage: ServerStorage,
    /** Flags of one server; the default reads [CommFlags]. A seam for tests. */
    private val flagsOf: (SshServer) -> CommFlags.Effective = { CommFlags.effectiveFor(it.id) },
    /**
     * Whether the exec transport of a server is really running (its manager is wired and Up). The
     * shell cap is 8 only then: with the transient exec channels still on the tab transports, 8 shells
     * plus their execs overflow sshd's default `MaxSessions` 10. Default `false`, so cap 3.
     */
    private val execWired: (SshServer) -> Boolean = { false },
) {

    private class Transport(
        val session: Session,
        val host: String,
        val port: Int,
        val viaProxy: Boolean,
        val role: TransportRole,
        val route: String,
    ) {
        /** Holders of a channel (shell tabs) or of the exec role (the manager) on this transport. */
        val lessees = mutableSetOf<Any>()
        /** Channels of a long stream parked here while the exec transport is down (see [reserveChannel]); they count against the cap. Under the mutex. */
        var extraChannels = 0
        /** Slots promised to waiters of the handshake that created it, not yet claimed. Under the mutex. */
        var reserved = 0
        /** The server refused a channel here (MaxSessions) — see [reportFull]. */
        @Volatile var full = false
        /** Write-timeout zombie flag — see [reportDead]. */
        @Volatile var reportedDead = false
        val usable: Boolean get() = !reportedDead && session.isConnected
    }

    private data class Key(val host: String, val port: Int, val viaProxy: Boolean, val role: TransportRole)

    private fun SshServer.key(role: TransportRole) = Key(host, port, useCloudflareProxy, role)
    private fun Transport.key() = Key(host, port, viaProxy, role)

    // CopyOnWriteArrayList: reportDead() iterates without the mutex (it runs
    // in a write-failure path that must not suspend); all structural changes
    // happen under [mutex].
    private val transports = java.util.concurrent.CopyOnWriteArrayList<Transport>()
    private val mutex = Mutex()

    // Handshakes of the v2 lease run in this scope, not in the leasing coroutine.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flights = SingleFlight<Key, Transport>(
        scope = scope,
        mutex = mutex,
        publish = { f, t -> publishTransport(t, f.claims) },
        dispose = { t -> disposeTransport(t) },
    )

    /** Keepalive interval (ms) for every transport, current and future. */
    @Volatile private var keepAliveMs = DEFAULT_KEEPALIVE_MS

    /** Servers whose port forwards the exec transport carries (shell sessions then skip them). */
    private val execForwardOwners = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Number of live (usable) TCP transports — the real connection count,
     *  unlike the per-session SshManager count. For data-usage metering. */
    fun liveCount(): Int = transports.count { it.usable }

    internal fun liveCount(role: TransportRole): Int = transports.count { it.usable && it.role == role }

    /** Connects running right now / the most ever running at once / started in total (tests, diagnostics). */
    internal suspend fun inflightConnects(): Int = mutex.withLock { flights.inflight }
    internal suspend fun peakInflightConnects(): Int = mutex.withLock { flights.peakInflight }
    internal suspend fun startedConnects(): Int = mutex.withLock { flights.started }

    /** Any usable transport's session, for one-off execs (tmux listing, status
     *  polls) that would otherwise open a fresh SSH-over-Cloudflare connection
     *  every time — reuse the pooled transport instead. Null if none is live.
     *  Shell transports only: the exec transport's channels are metered by its own permits. */
    fun anyUsableSession(): Session? =
        transports.firstOrNull { it.usable && it.role == TransportRole.SHELL }?.session

    /**
     * A long stream (watcher, streamd) borrows [session]'s shell transport while the exec transport is
     * down: count its channel against the transport's capacity, so a shell lease cannot fill the
     * transport up to `MaxSessions` behind its back. False when [session] is not one of our shell transports.
     */
    internal suspend fun reserveChannel(session: Session): Boolean = mutex.withLock {
        val t = transports.firstOrNull { it.session === session && it.role == TransportRole.SHELL } ?: return@withLock false
        t.extraChannels++
        true
    }

    /** Give back a channel taken with [reserveChannel]. */
    internal suspend fun releaseChannel(session: Session) = mutex.withLock {
        transports.firstOrNull { it.session === session }?.let { if (it.extraChannels > 0) it.extraChannels-- }
    }

    /** The route (`lan|tunnel|cf|unknown`) the pooled [session] took; `unknown` if it is not ours. */
    internal fun routeOf(session: Session): String = transports.firstOrNull { it.session === session }?.route ?: "unknown"

    /** Whether [session] is a pooled transport that is still usable (connected and not reported dead). */
    internal fun isUsable(session: Session): Boolean = transports.any { it.session === session && it.usable }

    /**
     * Set the keepalive interval (MILLISECONDS, 0 = off) on every usable transport,
     * and remember it: transports connected LATER start with it too.
     */
    fun setKeepAlive(ms: Int) {
        keepAliveMs = ms
        transports.forEach { if (it.usable) try { it.session.setServerAliveInterval(ms) } catch (_: Exception) {} }
    }

    /** The exec transport manager of [serverId] carries the server's L/R port forwards (true) or not (false). */
    internal fun setExecForwardsOwned(serverId: String, owned: Boolean) {
        if (owned) execForwardOwners.add(serverId) else execForwardOwners.remove(serverId)
    }

    /** True while the exec transport owns [serverId]'s port forwards — a shell session must not register them. */
    internal fun execForwardsOwned(serverId: String): Boolean = serverId in execForwardOwners

    /**
     * Lease a connected SHELL transport for [server], connecting one if needed.
     * [lessee] identifies the holder for [release].
     */
    suspend fun lease(server: SshServer, connectTimeout: Int, lessee: Any): Session {
        val eff = flagsOf(server)
        val cap = if (execWired(server)) eff.shellCap else SHELLS_PER_TRANSPORT
        return if (eff.pool) leaseV2(server, connectTimeout, lessee, TransportRole.SHELL, cap)
        else leaseLegacy(server, connectTimeout, lessee)
    }

    /** Lease the EXEC transport of [server] (one per key, no shell cap). Caller: the exec transport manager. */
    internal suspend fun leaseExec(server: SshServer, connectTimeout: Int, lessee: Any): Session =
        leaseV2(server, connectTimeout, lessee, TransportRole.EXEC, Int.MAX_VALUE)

    /** The pre-pool-v2 lease, unchanged: handshake under the mutex, 3 shells per transport. */
    private suspend fun leaseLegacy(server: SshServer, connectTimeout: Int, lessee: Any): Session = mutex.withLock {
        prune()
        val existing = transports.firstOrNull {
            it.usable && it.host == server.host && it.port == server.port &&
                it.viaProxy == server.useCloudflareProxy && it.role == TransportRole.SHELL && !it.full &&
                it.lessees.size + it.reserved + it.extraChannels < SHELLS_PER_TRANSPORT
        }
        if (existing != null) {
            existing.lessees.add(lessee)
            FileLogger.log(TAG, "Leased existing transport to ${server.host} (${existing.lessees.size} shells)")
            return@withLock existing.session
        }
        val t = newTransport(server, connectTimeout, TransportRole.SHELL)
        t.lessees.add(lessee)
        transports.add(t)
        FileLogger.log(TAG, newTransportLine(t))
        t.session
    }

    private sealed interface Step {
        class Ready(val session: Session) : Step
        class Wait(val flight: SingleFlight.Flight<Key, Transport>) : Step
    }

    /**
     * Single-flight lease. Under the mutex: a usable transport with a free slot
     * (counting the slots promised to waiters), else a slot of a pending handshake
     * (joining one, or starting one when all are full). The wait for the handshake
     * is OUTSIDE the mutex.
     */
    private suspend fun leaseV2(
        server: SshServer,
        connectTimeout: Int,
        lessee: Any,
        role: TransportRole,
        cap: Int,
    ): Session {
        val key = server.key(role)
        var attempts = 0
        while (true) {
            val step = mutex.withLock {
                prune()
                val t = transports.firstOrNull {
                    it.usable && !it.full && it.key() == key && it.lessees.size + it.reserved + it.extraChannels < cap
                }
                if (t != null) {
                    t.lessees.add(lessee)
                    FileLogger.log(TAG, "Leased existing transport to ${server.host} (${t.lessees.size} ${role.label}s)")
                    Step.Ready(t.session)
                } else {
                    Step.Wait(flights.join(key, cap) { newTransport(server, connectTimeout, role) })
                }
            }
            when (step) {
                is Step.Ready -> return step.session
                is Step.Wait -> {
                    val t0 = System.currentTimeMillis()
                    val t = try {
                        step.flight.result.await()
                    } catch (e: CancellationException) {
                        // This claimant gave up. The handshake goes on for the others, or its
                        // value is disposed when nobody is left.
                        withContext(NonCancellable) { mutex.withLock { abandonClaim(step.flight) } }
                        throw e
                    }
                    val claimed = withContext(NonCancellable) {
                        mutex.withLock {
                            t.reserved--
                            if (t.usable && transports.contains(t)) {
                                t.lessees.add(lessee)
                                true
                            } else {
                                dropIfIdle(t)
                                false
                            }
                        }
                    }
                    if (!currentCoroutineContext().isActive) {
                        // cancelled between the wake-up and now: give the slot back
                        withContext(NonCancellable) { mutex.withLock { releaseLocked(lessee) } }
                        throw CancellationException("lease cancelled")
                    }
                    if (claimed) {
                        FileLogger.log(TAG, "Awaited in-flight transport (${System.currentTimeMillis() - t0}ms)")
                        return t.session
                    }
                    if (++attempts >= MAX_LEASE_ATTEMPTS) {
                        throw java.io.IOException("transport to ${server.host} died while it was being leased")
                    }
                }
            }
        }
    }

    /** Give back a slot a waiter held on [flight]. Caller holds [mutex]. */
    private fun abandonClaim(flight: SingleFlight.Flight<Key, Transport>) {
        if (!flight.finished) {
            flights.abandon(flight)
        } else if (flight.claims > 0) {
            flight.value?.let { t ->
                t.reserved--
                dropIfIdle(t)
            }
        }
    }

    /** Release [lessee]'s claim; the last one out disconnects the transport. */
    suspend fun release(lessee: Any): Unit = mutex.withLock { releaseLocked(lessee) }

    private fun releaseLocked(lessee: Any) {
        val t = transports.firstOrNull { lessee in it.lessees } ?: return
        t.lessees.remove(lessee)
        if (t.lessees.isEmpty() && t.reserved == 0) {
            transports.remove(t)
            try { t.session.disconnect() } catch (_: Exception) {}
            FileLogger.log(TAG, "Disconnected drained transport to ${t.host} (${transports.size} left)")
        }
    }

    /** Disconnect and forget [t] if nobody holds or awaits it. Caller holds [mutex]. */
    private fun dropIfIdle(t: Transport) {
        if (t.lessees.isEmpty() && t.reserved <= 0) {
            transports.remove(t)
            try { t.session.disconnect() } catch (_: Exception) {}
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
     * The server refused to open a shell channel on [session]'s transport
     * (`MaxSessions`): stop leasing it. Siblings already on it are untouched; the
     * caller retries [lease] and lands on another transport.
     */
    internal fun reportFull(session: Session) {
        transports.forEach { if (it.session === session) it.full = true }
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

    /** Drop unusable transports that nobody awaits. Caller holds [mutex]. */
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

    /** A transport the flight just connected becomes visible, with [claims] slots promised to its waiters. Under the mutex. */
    private fun publishTransport(t: Transport, claims: Int) {
        t.reserved = claims
        transports.add(t)
        FileLogger.log(TAG, newTransportLine(t))
    }

    /** A connected transport nobody wants any more (every claimant gave up while it connected). Under the mutex. */
    private fun disposeTransport(t: Transport) {
        try { t.session.disconnect() } catch (_: Exception) {}
        FileLogger.log(TAG, "Disposed transport to ${t.host}:${t.port} role=${t.role.label} (no claimant left)")
    }

    private fun newTransportLine(t: Transport): String =
        "New transport to ${t.host}:${t.port} role=${t.role.label} route=${t.route} (${transports.size} in pool)"

    private suspend fun newTransport(server: SshServer, connectTimeout: Int, role: TransportRole): Transport {
        val session = connectTransport(server, connectTimeout)
        return Transport(session, server.host, server.port, server.useCloudflareProxy, role,
            RouteKind.ofSession(session, server.useCloudflareProxy))
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
            // Keepalive via the EXPLICIT API (milliseconds!) — see SshManager. The pool-wide
            // value (setKeepAlive), so a transport connected in the background does not start
            // with the foreground interval.
            sess.setServerAliveInterval(keepAliveMs)
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
        private const val DEFAULT_KEEPALIVE_MS = 10_000
        private const val MAX_LEASE_ATTEMPTS = 3
        // OpenSSH's default MaxSessions is 10 channels per connection, and the
        // transient exec load scales with the number of SESSIONS on the
        // transport (each runs its own transcript/git/sessionId probes, which
        // all burst at once right after a reconnect). 3 shells + ~2 concurrent
        // execs each stays inside the budget; 5 shells overflowed it in the
        // post-reconnect burst ("administratively prohibited"). Bonus: a
        // write-timeout transport teardown now cascades ≤3 tabs, not 5.
        // This is the cap whenever the exec role does not run on its own transport
        // (`comm_pool` off, or the exec transport of the server not wired yet); with it the cap is 8
        // (CommFlags).
        private const val SHELLS_PER_TRANSPORT = 3
    }
}
