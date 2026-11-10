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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 *    (the keepalive needs its interval x 3, ~30 s in the foreground, to notice). A channel-only EOF (user exits the
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
    /** Runs at the start of every handshake (before the TCP connect). A seam for tests: a controlled handshake latency. */
    private val beforeHandshake: suspend () -> Unit = {},
) {

    private class Transport(
        val session: Session,
        val host: String,
        val port: Int,
        val viaProxy: Boolean,
        val role: TransportRole,
        val route: String,
        /** Why it ended (log only, see [TransportEnd]). */
        val end: TransportEnd,
    ) {
        /** Holders of a channel (shell tabs) or of the exec role (the manager) on this transport. */
        val lessees = mutableSetOf<Any>()
        /** Channels of a long stream parked here while the exec transport is down (see [reserveChannel]); they count against the cap. Under the mutex. */
        var extraChannels = 0
        /** Slots promised to waiters of the handshake that created it, not yet claimed. Under the mutex. */
        var reserved = 0
        /** Of [extraChannels], the ones already given back whose channel still lingers in sshd (dávka E, H1). Under the mutex. */
        var settling = 0
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

    /**
     * Give back a channel taken with [reserveChannel] or [reserveFallbackChannel] — once sshd has freed it too (dávka E,
     * H1): sshd frees a session only after it reaped the command, so the slot comes back when the channels lingering on
     * [session] now have settled ([ChannelOpen.afterSettled]); at once when none lingers. The caller never waits for it.
     */
    internal suspend fun releaseChannel(session: Session) {
        var deferred = false
        ChannelOpen.afterSettled(session, onDeferred = {
            deferred = true
            mutex.withLock { transports.firstOrNull { it.session === session }?.let { it.settling++ } }
        }) {
            mutex.withLock {
                transports.firstOrNull { it.session === session }?.let {
                    if (it.extraChannels > 0) it.extraChannels--
                    if (deferred && it.settling > 0) it.settling--
                }
            }
            // every slot given back wakes the one-offs waiting for room, deferred or not (N2-1)
            settled.update { it + 1 }
        }
    }

    /** Bumped whenever a counted slot came back, deferred or not ([awaitSettlingRoomAndClaim] wakes on it). */
    private val settled = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /**
     * N-EK1/N2-1 (dávka E, rounds 2-3): a one-off (not a poll) that found no room on [session]'s transport only because
     * slots given back there still settle ([releaseChannel]) waits for them — suspending, never blocking a thread — until
     * [deadlineNanos], and TAKES the slot (counted like [reserveFallbackChannel]) under the same lock that sees it free,
     * so a poll cannot take it in between. It starts only when the settling slots would make room; once waiting, a
     * wake-up that brought no room (a poll or another one-off took the slot) makes it wait again, for any slot given back,
     * until the deadline. True = the slot is taken (pair with [releaseChannel]); false = no room: at once when nothing
     * settles there (as before the deferral), else at the deadline, or when the transport is gone, dead or full.
     */
    internal suspend fun awaitSettlingRoomAndClaim(session: Session, short: Boolean, deadlineNanos: Long): Boolean {
        var first = true
        while (true) {
            val seen = settled.value
            val wait = mutex.withLock {
                val t = transports.firstOrNull { it.session === session && it.role == TransportRole.SHELL } ?: return false
                if (!t.usable || t.full) return false
                if (t.hasFallbackHeadroom(short)) {
                    t.extraChannels++
                    return true
                }
                !first || (t.settling > 0 && t.hasFallbackHeadroom(short, settledToo = true))
            }
            first = false
            if (!wait) return false
            val left = (deadlineNanos - System.nanoTime()) / 1_000_000
            if (left <= 0) return false
            withTimeoutOrNull(left) { settled.first { it != seen } }
        }
    }

    /** Slots of [session]'s transport given back whose release still waits for sshd (tests: never left behind). */
    internal suspend fun settlingOf(session: Session): Int = mutex.withLock { transports.firstOrNull { it.session === session }?.settling ?: 0 }

    /** Whether [session]'s transport has no room now but would once its settling slots came back (look-only). */
    internal fun roomOnceSettled(session: Session, short: Boolean): Boolean {
        val t = transports.firstOrNull { it.session === session && it.role == TransportRole.SHELL } ?: return false
        return t.usable && !t.full && t.settling > 0 && !t.hasFallbackHeadroom(short) && t.hasFallbackHeadroom(short, settledToo = true)
    }

    /** Some usable shell transport carries more shells than the legacy cap: only then does a transient channel risk `MaxSessions`. */
    internal fun hasPackedTransport(): Boolean =
        transports.any { it.usable && it.role == TransportRole.SHELL && it.lessees.size > SHELLS_PER_TRANSPORT }

    /**
     * Whether one more TRANSIENT channel (a fallback exec while the exec transport is down) fits on
     * [session]'s shell transport and still leaves [FALLBACK_RESERVE] channels of sshd's
     * [SSHD_MAX_SESSIONS] free for the tabs' own short channels: shells + reserved slots + channels already
     * borrowed + 1 + reserve <= 10. A transport packed with 8 shells (cap 8) has none for a LONG channel, so the
     * fallback is refused instead of running into sshd's refusal. A [short] channel (a poll, a one-off command) may
     * take [SHORT_RESERVE_SLOTS] slot of that reserve (V9): with 8 shells one short channel at a time still runs, so
     * the tabs' polls serialize instead of all being skipped, and 8 + 1 + 1 stays within 10. A session this pool
     * does not know says nothing: true.
     */
    internal fun fallbackHeadroom(session: Session, short: Boolean = false): Boolean {
        val t = transports.firstOrNull { it.session === session && it.role == TransportRole.SHELL } ?: return true
        return t.usable && !t.full && t.hasFallbackHeadroom(short)
    }

    /** A usable shell transport that has [fallbackHeadroom], or null. Look-only: nothing is counted (see [reserveFallbackChannel]). */
    internal fun fallbackSessionWithHeadroom(short: Boolean = false): Session? =
        transports.firstOrNull { it.usable && it.role == TransportRole.SHELL && !it.full && it.hasFallbackHeadroom(short) }?.session

    /**
     * Take one transient channel on [session]'s shell transport if it has [fallbackHeadroom] (counted against
     * the cap like [reserveChannel], decided under the mutex so two callers cannot both take the last slot).
     * [short]: a poll or one-off command, which may take one slot of the reserve; a long stream may not.
     * Pair with [releaseChannel]. True for a session this pool does not know (nothing to count).
     */
    internal suspend fun reserveFallbackChannel(session: Session, short: Boolean = false): Boolean = mutex.withLock {
        val t = transports.firstOrNull { it.session === session && it.role == TransportRole.SHELL } ?: return@withLock true
        if (!t.usable || t.full || !t.hasFallbackHeadroom(short)) return@withLock false
        t.extraChannels++
        true
    }

    /**
     * Counted by occupancy, not by kind: a short channel fits while at most [SHORT_RESERVE_SLOTS] of the reserve would
     * be in use afterwards, so two short channels can never both sit in the reserve (they serialize), and a long one
     * never fits once a short one sits there.
     */
    private fun Transport.hasFallbackHeadroom(short: Boolean, settledToo: Boolean = false): Boolean =
        lessees.size + reserved + extraChannels - (if (settledToo) settling else 0) + 1 + FALLBACK_RESERVE -
            (if (short) SHORT_RESERVE_SLOTS else 0) <= SSHD_MAX_SESSIONS

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
        return if (flagsOf(server).pool) leaseV2(server, connectTimeout, lessee, TransportRole.SHELL) { shellCapOf(server) }
        else leaseLegacy(server, connectTimeout, lessee)
    }

    /**
     * The shell cap of [server] NOW. Read on every pass of the lease loop, not once per lease: an exec transport that comes
     * up during a reconnect wave lets the tabs still waiting pack by 8 instead of 3 (49 tabs: 7-8 transports, not 17).
     */
    private fun shellCapOf(server: SshServer): Int = if (execWired(server)) flagsOf(server).shellCap else SHELLS_PER_TRANSPORT

    /** Lease the EXEC transport of [server] (one per key, no shell cap). Caller: the exec transport manager. */
    internal suspend fun leaseExec(server: SshServer, connectTimeout: Int, lessee: Any): Session =
        leaseV2(server, connectTimeout, lessee, TransportRole.EXEC) { Int.MAX_VALUE }

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
        /** [MAX_PARALLEL_HANDSHAKES] connects of the key already run: wait for ANY of them to end, then ask again. */
        class Throttled(val flights: List<SingleFlight.Flight<Key, Transport>>) : Step
    }

    /**
     * Single-flight lease. Under the mutex: a usable transport with a free slot
     * (counting the slots promised to waiters), else a slot of a pending handshake
     * (joining one, or starting one when all are full and fewer than
     * [MAX_PARALLEL_HANDSHAKES] of the key run). The wait for the handshake
     * is OUTSIDE the mutex. [capNow] is read on every pass (see [shellCapOf]).
     */
    private suspend fun leaseV2(
        server: SshServer,
        connectTimeout: Int,
        lessee: Any,
        role: TransportRole,
        capNow: () -> Int,
    ): Session {
        val key = server.key(role)
        var attempts = 0
        var throttleDeadlineNs = System.nanoTime() + connectTimeout * 1_000_000L
        while (true) {
            val cap = capNow()
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
                    val joined = flights.tryJoin(key, cap, MAX_PARALLEL_HANDSHAKES) { newTransport(server, connectTimeout, role) }
                    // the snapshot of the key's running connects is taken under the mutex, with the refusal
                    if (joined != null) Step.Wait(joined) else Step.Throttled(flights.pendingAll(key))
                }
            }
            when (step) {
                is Step.Ready -> return step.session
                is Step.Throttled -> {
                    // Not a claimant of any of those connects: wait until the FIRST of them has ended (a success
                    // publishes a transport with room for the next ones or leaves the slot to a new connect, a
                    // failure lets the next claimant start afresh) and look again. `onJoin`, not `onAwait`: a
                    // failed connect must wake us, not throw its failure at us. Waiting for the OLDEST one held a
                    // throttled tab behind a slow handshake although a fast one had long freed a slot.
                    // N-D: bounded by connectTimeout counted from the last handshake of the key that SUCCEEDED while we
                    // waited (from the lease's start before that). A blackhole fails the throttled tabs after one
                    // connectTimeout instead of round after round (49 tabs: ~12 s for 2 s handshakes); a slow wave that
                    // keeps connecting is never cut.
                    val left = throttleDeadlineNs - System.nanoTime()
                    val woke = if (left <= 0) null else withTimeoutOrNull((left + 999_999) / 1_000_000) {
                        select<SingleFlight.Flight<Key, Transport>> { step.flights.forEach { f -> f.result.onJoin { f } } }
                    }
                    if (woke == null) {
                        throw java.io.IOException("no handshake slot to ${server.host} within ${connectTimeout}ms " +
                            "($MAX_PARALLEL_HANDSHAKES handshakes of the key run, none succeeded)")
                    }
                    if (!woke.result.isCancelled) throttleDeadlineNs = System.nanoTime() + connectTimeout * 1_000_000L
                }
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
            t.end.closingLocally("drained")
            try { t.session.disconnect() } catch (_: Exception) {}
            FileLogger.log(TAG, "Disconnected drained transport to ${t.host} (${transports.size} left)")
        }
    }

    /** Disconnect and forget [t] if nobody holds or awaits it. Caller holds [mutex]. */
    private fun dropIfIdle(t: Transport) {
        if (t.lessees.isEmpty() && t.reserved <= 0) {
            transports.remove(t)
            t.end.closingLocally("idle")
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
     * ~30 s (fg, 10 s x 3) / 3 min (bg, 60 s x 3) — frozen tabs the whole time. Disconnecting
     * unblocks the parked channel reads immediately, so every manager's read
     * loop EOFs and per-session autoReconnect (gated + pooled) re-establishes
     * on the new network the moment it's up. Structural removal from the list
     * happens on the next lease()'s prune.
     */
    fun teardownAll() {
        transports.forEach {
            it.reportedDead = true
            // F4: the read loops that EOF now must say it was us (a network change), not the network
            if (it.end.closingLocally("network teardown")) FileLogger.log(TAG, it.end.endedLine())
            try { it.session.disconnect() } catch (_: Exception) {}
        }
    }

    /** Drop unusable transports that nobody awaits. Caller holds [mutex]. */
    private fun prune() {
        val dead = transports.filter { !it.usable }
        for (t in dead) {
            transports.remove(t)
            // a write-timeout zombie (reportDead) is still connected: closing it is our decision
            if (t.session.isConnected && t.end.closingLocally("reported dead")) FileLogger.log(TAG, t.end.endedLine())
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
        t.end.closingLocally("no claimant left")
        try { t.session.disconnect() } catch (_: Exception) {}
        FileLogger.log(TAG, "Disposed transport to ${t.host}:${t.port} role=${t.role.label} (no claimant left)")
    }

    private fun newTransportLine(t: Transport): String =
        "New transport to ${t.host}:${t.port} role=${t.role.label} route=${t.route} (${transports.size} in pool)"

    private suspend fun newTransport(server: SshServer, connectTimeout: Int, role: TransportRole): Transport {
        val end = TransportEnd("${server.host}:${server.port} role=${role.label}")
        val session = connectTransport(server, connectTimeout, end)
        val route = RouteKind.ofSession(session, server.useCloudflareProxy)
        end.connected(route)
        return Transport(session, server.host, server.port, server.useCloudflareProxy, role, route, end)
    }

    /** Full SSH handshake — mirrors what SshManager.connect used to do per tab. */
    private suspend fun connectTransport(server: SshServer, connectTimeout: Int, end: TransportEnd): Session =
        withContext(Dispatchers.IO) {
            beforeHandshake()
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
            // maps zlib@openssh.com / zlib to its bundled jzlib port
            // (com.jcraft.jsch.jzlib.Compression — no external jzlib jar needed;
            // the java.util.zip one is opt-in and not used here); "none" stays
            // in the list so a server with Compression=no still negotiates cleanly.
            sess.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
            sess.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
            // Keepalive via the EXPLICIT API (milliseconds!) — see SshManager. The pool-wide
            // value (setKeepAlive), so a transport connected in the background does not start
            // with the foreground interval.
            // C1: the read timeout first — `setServerAliveInterval` sets it to the interval, and JSch sends its keepalive
            // only when a read times out; set after it, the connect timeout (15 s) was the keepalive pace (~45 s to notice
            // a dead link instead of ~30 s). The handshake: on a socket connect(connectTimeout) below bounds it; over Cloudflare
            // (no socket) the proxy's own limit does (TunnelKeepAlive)
            sess.timeout = connectTimeout
            sess.setServerAliveInterval(keepAliveMs)
            sess.setServerAliveCountMax(2)
            sess.userInfo = TofuUserInfo(server.host, serverStorage)
            // F4: jsch reports the end of its main loop (and why) only to a logger; this one keeps that reason
            sess.setLogger(end)
            val cf = if (server.useCloudflareProxy) {
                FileLogger.log(TAG, "Using Cloudflare tunnel proxy for ${server.host}")
                CloudflareProxy(server.host, server.cloudflareToken).also { sess.setProxy(it) }
            } else null
            // C5: a direct socket's failed write closes the transport (never a cipher state a packet ahead of sshd's)
            TransportWriteGuard.forServer(server.useCloudflareProxy, connectTimeout)?.let { sess.setSocketFactory(it) }
            sess.connect(connectTimeout)
            // C1 (K-2): only now does the keepalive run over the tunnel (no socket to time out)
            cf?.armKeepAlive(sess)
            sess
        }

    companion object {
        private const val TAG = "ServerTransportPool"
        private const val DEFAULT_KEEPALIVE_MS = 10_000
        private const val MAX_LEASE_ATTEMPTS = 3
        /**
         * Handshakes of one transport key that may run at once. OpenSSH's default `MaxStartups` 10:30:100 starts
         * dropping new connections at 10 unauthenticated ones (and through Cloudflare they all come from one
         * address); 3 is also what the handshake gate of the single-connection design allowed.
         */
        internal const val MAX_PARALLEL_HANDSHAKES = 3
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
        /** OpenSSH's default `MaxSessions`: channels one connection may carry. */
        internal const val SSHD_MAX_SESSIONS = 10
        /**
         * Channels of a shell transport that a fallback exec leaves free: the channels that are still uncounted (the
         * close-state and peer probes of a tab's connect, the UI's one-offs on a tab session, the script install and
         * the sessions.json push) still need room when the exec transport is down. A short counted channel may take
         * one of them ([SHORT_RESERVE_SLOTS]).
         */
        internal const val FALLBACK_RESERVE = 2
        /**
         * Slots of [FALLBACK_RESERVE] a SHORT counted channel may take (V9, dávka B round 2): one, so the polls of 8
         * packed shells run one at a time instead of not at all, while a long stream (the watcher) still keeps off it.
         */
        internal const val SHORT_RESERVE_SLOTS = 1
    }
}

/**
 * Why a pooled transport ended, for the log only: it changes nothing (dávka B, F4). jsch tells why its main loop stopped
 * ("Caught an exception, leaving main loop due to <message>", the message only, no exception class) to nobody but a
 * logger; this one is installed on every pooled session and keeps that reason. When WE close the transport
 * ([closingLocally]: network teardown, drained, write-timeout zombie, …) the reason is ours, `local: <why>`.
 * The tab's read loop ([SshManager]) reads it through `Session.getLogger()` for its `Connection lost cause=…` line.
 */
internal class TransportEnd(private val label: String) : com.jcraft.jsch.Logger {
    @Volatile private var route = "unknown"
    @Volatile private var connectedAtNs = 0L

    /** The reason once the transport has ended (jsch's message, or `local: <why>`); null while it lives. */
    @Volatile var cause: String? = null
        private set

    /** The handshake is done: the transport lives from now on. */
    fun connected(route: String) {
        this.route = route
        connectedAtNs = System.nanoTime()
    }

    /** We are about to close the transport for [reason]. True when this is the first word on its end (then log it). */
    @Synchronized fun closingLocally(reason: String): Boolean {
        if (cause != null) return false
        cause = "local: $reason"
        return true
    }

    fun endedLine(): String = "Transport to $label route=$route ended after ${ageMs()}ms: cause=$cause"

    private fun ageMs(): Long = if (connectedAtNs == 0L) -1 else (System.nanoTime() - connectedAtNs) / 1_000_000

    override fun isEnabled(level: Int): Boolean = level == com.jcraft.jsch.Logger.INFO

    override fun log(level: Int, message: String?) {
        if (message == null || !message.startsWith(MAIN_LOOP_END)) return
        val first = synchronized(this) {
            if (cause != null) false else {
                cause = message.removePrefix(MAIN_LOOP_END).ifBlank { "unknown" }
                true
            }
        }
        if (first) FileLogger.log("ServerTransportPool", endedLine())
    }

    companion object {
        /** jsch's own words (com.jcraft.jsch.Session.run, mwiede 0.2.x), logged at INFO before it disconnects. */
        private const val MAIN_LOOP_END = "Caught an exception, leaving main loop due to "

        /** The end recorder of a pooled [session], or null (a standalone session). */
        fun of(session: Session?): TransportEnd? = try { session?.logger as? TransportEnd } catch (_: Throwable) { null }
    }
}
