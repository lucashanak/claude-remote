package com.clauderemote.session.service

import com.clauderemote.connection.EtManager
import com.clauderemote.connection.Fallback
import com.clauderemote.connection.FallbackRunner
import com.clauderemote.connection.MoshManager
import com.clauderemote.connection.ServerTransportPool
import com.clauderemote.connection.SshManager
import com.clauderemote.model.SshServer
import com.clauderemote.session.TabManager
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.Session
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * Owns the per-session SSH/Mosh transports and the per-server pooling/gating
 * state, plus the tab→server lookups the per-server loops rely on. Extracted
 * from SessionOrchestrator so the collaborator services can reach a session's
 * live transport without reaching into the orchestrator's internals.
 *
 * Map types are preserved verbatim from the original fields (connections is a
 * ConcurrentHashMap; moshConnections is a plain mutableMap) — this is a pure
 * move, it neither adds nor removes synchronization.
 */
internal class ConnectionRegistry(
    private val serverStorage: ServerStorage,
    private val tabManager: TabManager,
) : FallbackRunner {
    private val connections = java.util.concurrent.ConcurrentHashMap<String, SshManager>()
    private val moshConnections = mutableMapOf<String, MoshManager>()
    // ET runs alongside an SSH carrier (bootstrap + local forward), so a
    // preferEternal session has BOTH an entry here and in [connections].
    private val etConnections = mutableMapOf<String, EtManager>()

    // Handshake gate per server — see SessionOrchestrator.connectSsh: caps
    // concurrent KEX/auth handshakes to a server at 3 so a herd reconnect
    // (e.g. 21 tabs after a Starlink drop) doesn't hammer the link.
    private val connectGates = java.util.concurrent.ConcurrentHashMap<String, Semaphore>()
    private val pooledGates = java.util.concurrent.ConcurrentHashMap<String, Semaphore>()

    // Shared SSH transports per server: tabs lease shell channels on pooled
    // jsch Sessions instead of each owning a TCP/WebSocket + KEX + auth.
    private val transportPools = java.util.concurrent.ConcurrentHashMap<String, ServerTransportPool>()

    private companion object {
        /** More tabs than any user runs on one server: the gate of a pooled server never holds anyone back. */
        const val POOLED_GATE_PERMITS = 256
        /** How often [run] looks again when the slot it saw went to someone else before its claim. */
        const val FALLBACK_CLAIM_TRIES = 3
        /** At most one `poll skipped (no room)` line per source and server in this window (the count rides along). */
        const val SKIP_LOG_WINDOW_MS = 60_000L
    }

    // --- Seam slots: null = legacy. The wiring step fills them, so no service constructor has to change. ---
    /** Exec-session provider (ExecTransportManager). Null = legacy lookups. */
    @Volatile var execSessions: ExecSessionProvider? = null
    @Volatile var execChannels: ExecChannels? = null
    @Volatile var remoteExec: RemoteExec? = null
    @Volatile var reconciler: ServerReconciler? = null
    @Volatile var bootstrap: ServerBootstrap? = null

    // --- SSH transports ---
    fun ssh(sessionId: String): SshManager? = connections[sessionId]
    fun putSsh(sessionId: String, mgr: SshManager) { connections[sessionId] = mgr }
    fun removeSsh(sessionId: String): SshManager? = connections.remove(sessionId)
    fun containsSsh(sessionId: String): Boolean = connections.containsKey(sessionId)
    fun allSsh(): Collection<SshManager> = connections.values
    fun sshEntries(): Set<Map.Entry<String, SshManager>> = connections.entries
    fun clearSsh() { connections.clear() }

    /**
     * Any CONNECTED tab session, for account-wide use. It never returns the exec session: that one
     * is only handed out under a permit ([execRun], [useChannel]). Log shipping goes through
     * [execRun] with the short kind.
     */
    fun anyLiveSession(): Session? {
        return connections.values.firstOrNull { it.isConnected }?.getSession()
    }

    /** Keepalive (milliseconds) for every shell session, pooled transport and exec transport. */
    fun setKeepAliveIntervalAll(keepAlive: Int) {
        connections.values.forEach { it.setKeepAliveInterval(keepAlive) }
        transportPools.values.forEach { it.setKeepAlive(keepAlive) }
        (execSessions as? ExecTransportManager)?.setKeepAlive(keepAlive)
    }

    // --- Mosh transports ---
    fun mosh(sessionId: String): MoshManager? = moshConnections[sessionId]
    fun putMosh(sessionId: String, mgr: MoshManager) { moshConnections[sessionId] = mgr }
    fun removeMosh(sessionId: String): MoshManager? = moshConnections.remove(sessionId)

    // --- Eternal Terminal transports ---
    fun et(sessionId: String): EtManager? = etConnections[sessionId]
    fun putEt(sessionId: String, mgr: EtManager) { etConnections[sessionId] = mgr }
    fun removeEt(sessionId: String): EtManager? = etConnections.remove(sessionId)

    /** Takes every mosh and ET client out of the registry and returns how to stop each (`disconnect`), for `shutdown()`. */
    fun takeMoshAndEt(): List<suspend () -> Unit> {
        val mosh = moshConnections.values.toList().also { moshConnections.clear() }
        val et = etConnections.values.toList().also { etConnections.clear() }
        return mosh.map { m -> suspend { m.disconnect() } } + et.map { e -> suspend { e.disconnect() } }
    }

    // --- per-server pools / gates ---
    /**
     * The handshake gate of [serverId]. Pool v2 ON: practically open ([POOLED_GATE_PERMITS]); the pool itself
     * bounds the handshakes of a wave, at most [ServerTransportPool.MAX_PARALLEL_HANDSHAKES] of a key at once
     * and ceil(N / shell cap) in all, so a gate of 3 in FRONT of it would only serialize them: a tab holds
     * its permit while it waits for the handshake it joined, so at most 3 tabs are inside `connect` and the
     * next transport's handshake only starts once the earlier ones are through (measured: 1 handshake at a
     * time instead of up to 3, see `ExecWiringIntegrationTest`). Pool OFF: the legacy gate of 3, as before.
     */
    fun connectGate(serverId: String): Semaphore =
        if (CommFlags.effectiveFor(serverId).pool) pooledGates.getOrPut(serverId) { Semaphore(POOLED_GATE_PERMITS) }
        else connectGates.getOrPut(serverId) { Semaphore(3) }

    /**
     * The pool of [serverId]. Its shell cap is 8 only while the server's exec transport is UP ([execUp]):
     * with the transient exec channels still on the tab transports, 8 shells plus their execs would overflow
     * sshd's `MaxSessions` 10 — and while that transport is being replaced for channel health (C1, H7: hysteresis,
     * [ExecTransportManager.keepsShellCap]), so a wave in that window does not open a transport per 3 tabs.
     */
    fun transportPool(serverId: String): ServerTransportPool =
        transportPools.getOrPut(serverId) { ServerTransportPool(serverStorage, execWired = { execUp(it.id) || execKeepsShellCap(it.id) }) }

    /** C1 (H7): the exec transport of [serverId] is being replaced for channel health; the shells keep the cap of 8. */
    internal fun execKeepsShellCap(serverId: String): Boolean =
        (execSessions as? ExecTransportManager)?.keepsShellCap(serverId) == true

    /** The exec transport manager of [serverId] is wired and its transport is UP (the condition for shell cap 8). */
    internal fun execUp(serverId: String): Boolean =
        execSessions != null && (execSessions as? ExecTransportManager)?.state(serverId)?.value is LinkState.Up

    fun transportPoolCount(): Int = transportPools.size
    /** Total live TCP transports across all servers — the real connection count. */
    fun liveTransportCount(): Int = transportPools.values.sumOf { it.liveCount() }
    /** A usable pooled session for [serverId] to run one-off execs on (reuse
     *  instead of opening a fresh SSH-over-Cloudflare connection). Null if none. */
    fun pooledSession(serverId: String): Session? = transportPools[serverId]?.anyUsableSession()

    /**
     * The pooled session a one-off op may borrow when the exec transport did not take it. With exec routing
     * on for [serverId] only a shell transport that still has channel headroom
     * ([ServerTransportPool.fallbackHeadroom]) qualifies: a transport packed with 8 shells would refuse the
     * channel. Routing off: [pooledSession], as before.
     */
    fun fallbackPooledSession(serverId: String): Session? =
        if (needsRoom(serverId)) transportPools[serverId]?.fallbackSessionWithHeadroom() else pooledSession(serverId)
    fun teardownAllTransports() { transportPools.values.forEach { it.teardownAll() } }

    // --- tab → server lookups ---
    /** Server id of a session's tab, or null if the tab is gone. */
    fun serverIdOf(sessionId: String): String? =
        tabManager.getTab(sessionId)?.server?.id

    /** All session ids currently on [serverId]. */
    fun sessionIdsOnServer(serverId: String): List<String> =
        tabManager.tabs.value.filter { it.server.id == serverId }.map { it.id }

    /**
     * Any CONNECTED tab's live jsch Session on [serverId], for per-server loops that survive
     * any single tab's reconnect by picking another live connection. It never returns the exec
     * session: that one is only handed out under a permit. A long-lived stream (notify watcher,
     * stream daemon) opens its channel with [useChannel] and the long kind instead.
     */
    fun liveServerSession(serverId: String): Session? = tabServerSession(serverId, withHeadroom = needsRoom(serverId))

    /**
     * The legacy answer: a CONNECTED tab's session on [serverId], ignoring the exec transport. With
     * [withHeadroom] only a transport that can take one more transient channel counts
     * ([ServerTransportPool.fallbackHeadroom]); look-only, nothing is counted.
     */
    private fun tabServerSession(serverId: String, withHeadroom: Boolean = false): Session? {
        val pool = if (withHeadroom) transportPools[serverId] else null
        for ((sid, mgr) in connections) {
            if (tabManager.getTab(sid)?.server?.id == serverId && mgr.isConnected) {
                val s = mgr.getSession() ?: continue
                if (pool != null && !pool.fallbackHeadroom(s)) continue
                return s
            }
        }
        return null
    }

    /**
     * Runs [block] on a tab session of [serverId] that has channel headroom, holding one counted channel on
     * its transport for the whole block ([ServerTransportPool.reserveFallbackChannel]). Null = no tab
     * transport has room (a value of null from [block] is wrapped, so the two never mix up). This is what
     * every fallback of the exec layer uses while exec routing is on: when the exec transport is down and
     * the tabs sit 8 to a transport, a fallback must not run into sshd's `MaxSessions`. A [kind] other than
     * [ExecKind.LONG] is a short channel and may take the one slot of the reserve ([ServerTransportPool.SHORT_RESERVE_SLOTS]),
     * unless it is a [transfer] (V12: an upload or SFTP download would hold that one slot for the whole transfer and
     * starve the polls of every tab on the transport).
     */
    private suspend fun <T> onFallbackTab(serverId: String, kind: ExecKind, transfer: Boolean = false, block: suspend (Session) -> T): Fallback<T>? {
        val pool = transportPools[serverId]
        val short = kind != ExecKind.LONG && !transfer
        for ((sid, mgr) in connections) {
            if (tabManager.getTab(sid)?.server?.id != serverId || !mgr.isConnected) continue
            val s = mgr.getSession() ?: continue
            if (pool != null && !pool.reserveFallbackChannel(s, short = short)) continue
            // K4-3: a LONG channel (the notify watcher's tail) holds its slot for good; transferMayFindRoom counts it
            val held = if (pool != null && kind == ExecKind.LONG && !transfer) longChannels.computeIfAbsent(s) { java.util.concurrent.atomic.AtomicInteger() } else null
            held?.incrementAndGet()
            try {
                return Fallback(block(s))
            } catch (e: Exception) {
                if (isChannelOpenFailure(e)) logChannelOpenFailed("fallback", serverId, s, null, e)
                throw e
            } finally {
                held?.decrementAndGet()
                if (pool != null) withContext(NonCancellable) { pool.releaseChannel(s) }
            }
        }
        return null
    }

    /**
     * [FallbackRunner] of [com.clauderemote.connection.SshSessionHelper.withSession] (NN1): with a room check
     * ([needsRoom]) the block runs on a pooled shell transport with headroom and holds one counted (short) channel there
     * until it ends (given back in `finally` under `NonCancellable`, nothing suspends between the claim and the `try`); no room =
     * null, the caller skips or opens a fresh connection if it may. A legacy server: today's [pooledSession], nothing
     * counted.
     */
    override suspend fun <T> run(server: SshServer, block: suspend (Session) -> T): Fallback<T>? {
        val serverId = server.id
        if (!needsRoom(serverId)) {
            val s = pooledSession(serverId)?.takeIf { it.isConnected } ?: return null
            return Fallback(block(s))
        }
        val pool = transportPools[serverId] ?: return null
        // The look and the claim are a moment apart: a slot taken meanwhile is refused by the claim, look again.
        repeat(FALLBACK_CLAIM_TRIES) {
            val s = pool.fallbackSessionWithHeadroom(short = true) ?: return null
            claimSeam?.invoke(s)
            if (pool.reserveFallbackChannel(s, short = true)) {
                try {
                    return Fallback(block(s))
                } catch (e: Exception) {
                    if (isChannelOpenFailure(e)) logChannelOpenFailed("withSession", serverId, s, null, e)
                    throw e
                } finally {
                    withContext(NonCancellable) { pool.releaseChannel(s) }
                }
            }
        }
        return null
    }

    /** Test seam of [run]: between the look at a transport with room and the claim of its slot. */
    @Volatile internal var claimSeam: (suspend (Session) -> Unit)? = null

    /**
     * [block] on a CONNECTED tab session of [serverId], for a long or one-off channel a service opens itself (the notify
     * watcher's LONG tail, the accounts screen with [ExecKind.SHORT]). With a room check it is one counted channel on a
     * tab transport with headroom for the whole block; a legacy server: the first connected tab session, nothing
     * counted. Null = none.
     */
    suspend fun <T> onServerTabChannel(serverId: String, kind: ExecKind = ExecKind.LONG, block: suspend (Session) -> T): Fallback<T>? =
        if (needsRoom(serverId)) onFallbackTab(serverId, kind, block = block)
        else tabServerSession(serverId)?.let { Fallback(block(it)) }

    /** Exec routing is in effect for [serverId] (slots wired and the `exec` group on for it). */
    private fun routedFor(serverId: String): Boolean = remoteExec != null && execActive(serverId)

    /**
     * A transient channel of [serverId] must be counted and may only land on a tab transport with room:
     * exec routing is on, or the exec group was switched off while 8 shells still ride one transport (the
     * kill switch does not move live shells). With routing off and at most 3 shells per transport there is
     * always room, and nothing is counted (the legacy lease would otherwise lose a slot while a channel runs).
     */
    private fun needsRoom(serverId: String): Boolean =
        routedFor(serverId) || transportPools[serverId]?.hasPackedTransport() == true

    /**
     * Take one channel on [session] (a tab's own transport) of [serverId], for a service that holds the session itself
     * (the pollers of a tab, the git probe, the per-tab session-id loop, ...). With a room check ([needsRoom]) it is
     * counted against the transport and null when there is no room: skip the tick. Every such channel is short (a poll,
     * a one-off command), so it may take the one slot of the reserve: 8 packed shells still poll, one at a time. A
     * transfer (upload, SFTP download) passes `short = false` and keeps off the reserve (V12). A server that needs no
     * room check gets an uncounted token. Release the token (idempotent) in `finally` under `NonCancellable`.
     */
    suspend fun reserveTabChannel(serverId: String, session: Session, short: Boolean = true): TabChannel? {
        val pool = if (needsRoom(serverId)) transportPools[serverId] else null
        if (pool != null && !pool.reserveFallbackChannel(session, short = short)) return null
        val token = if (pool == null) TabChannel(session) else TabChannel(session) { pool.releaseChannel(it) }
        // only now that the token exists: a logger that throws must not leak the slot just claimed
        runCatching { flushSkipped(serverId) }
        return token
    }

    /**
     * [block] on [session] holding a [reserveTabChannel] token for its whole run. Null = no room ([source]'s tick is
     * reported skipped); a channel that failed to open is logged with the transport's state before it is rethrown.
     */
    suspend fun <T> withTabChannel(serverId: String, session: Session, source: String, block: suspend (Session) -> T): Fallback<T>? =
        onTabChannel(serverId, session, source, short = true, block)

    /**
     * [withTabChannel] for a transfer (upload, SFTP download): counted like it, but it never takes the reserve slot of a
     * packed transport (V12), so with 8 shells there is no room and the transfer fails instead of starving the polls.
     */
    suspend fun <T> withTransferChannel(serverId: String, session: Session, source: String, block: suspend (Session) -> T): Fallback<T>? =
        onTabChannel(serverId, session, source, short = false, block)

    /**
     * Whether a transfer that found no room may still get it before its deadline (R3-1, dávka B round 4). It holds no
     * reserve slot while it waits, so waiting blocks nobody. Yes when the exec transport can still come up (routing on
     * and the transport not [LinkState.Closed]), or when a tab transport of [serverId] (only [own], for a transfer on
     * the tab's own session) carries few enough shells that a transfer fits beside the reserve once the short channels
     * in flight end: 7 shells yes, between the polls; 8 never, until the tabs re-lease (shells + 1 + reserve > 10).
     * The shells of a transport are the tabs whose session it is; a LONG channel held there (the notify watcher's tail,
     * K4-3) counts like a shell, so 7 shells and a watcher tail never leave room either.
     */
    /** LONG channels held per tab session through [onFallbackTab] (they do not end between polls). */
    private val longChannels = java.util.WeakHashMap<Session, java.util.concurrent.atomic.AtomicInteger>().let { java.util.Collections.synchronizedMap(it) }

    internal fun transferMayFindRoom(serverId: String, own: Session? = null): Boolean {
        if (execActive(serverId) && (execSessions as? ExecTransportManager)?.state(serverId)?.value !is LinkState.Closed) return true
        val candidates = own?.let { listOf(it) } ?: connections.entries
            .filter { (sid, m) -> tabManager.getTab(sid)?.server?.id == serverId && m.isConnected }
            .mapNotNull { it.value.getSession() }
        return candidates.any { s ->
            connections.values.count { it.getSession() === s } + (longChannels[s]?.get() ?: 0) + 1 +
                ServerTransportPool.FALLBACK_RESERVE <= ServerTransportPool.SSHD_MAX_SESSIONS
        }
    }

    private suspend fun <T> onTabChannel(serverId: String, session: Session, source: String, short: Boolean, block: suspend (Session) -> T): Fallback<T>? {
        val ch = reserveTabChannel(serverId, session, short) ?: run { noteSkipped(source, serverId); return null }
        try {
            return Fallback(block(session))
        } catch (e: Exception) {
            if (isChannelOpenFailure(e)) logChannelOpenFailed(source, serverId, session, null, e)
            throw e
        } finally {
            withContext(NonCancellable) { ch.release() }
        }
    }

    /** The channel budget of [serverId]'s tab transports for the pollers of a tab. */
    fun tabChannels(serverId: String): TabChannels = object : TabChannels {
        override suspend fun reserve(session: Session): TabChannel? = reserveTabChannel(serverId, session)
        override fun skipped(source: String) = noteSkipped(source, serverId)
        override fun describe(session: Session): String = describeTransport(serverId, session)
    }

    /** Skipped ticks per source of a server since the last line, and when that line was written. */
    private class SkipCount { var since = 0L; var count = 0 }
    private val skipCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, SkipCount>>()

    /** Clock of the `poll skipped` rate limit (a seam for tests). */
    @Volatile internal var skipClock: () -> Long = { System.currentTimeMillis() }

    /**
     * `poll skipped (no room)`: the first skip of a source on a server at once, then at most one line a minute with the
     * count. Skips counted after the last line are written by the next skip, or at the next tab channel the server grants
     * ([reserveTabChannel] → [flushSkipped]) once the minute is over. A streak that neither follows (the last tab closed,
     * the app in the background) keeps its count unwritten.
     */
    fun noteSkipped(source: String, serverId: String) {
        val c = skipCounts.computeIfAbsent(serverId) { java.util.concurrent.ConcurrentHashMap() }.computeIfAbsent(source) { SkipCount() }
        val line = synchronized(c) {
            c.count++
            val now = skipClock()
            if (c.since != 0L && now - c.since < SKIP_LOG_WINDOW_MS) return
            val n = c.count
            c.count = 0
            c.since = now
            "poll skipped (no room) src=$source server=$serverId n=$n"
        }
        FileLogger.log(CHANNEL_TAG, line)
    }

    /** A channel of [serverId] was granted: write the skips still counted since a line older than the window (the end of a streak). */
    private fun flushSkipped(serverId: String) {
        val sources = skipCounts[serverId] ?: return
        for ((source, c) in sources) {
            val line = synchronized(c) {
                val now = skipClock()
                if (c.count == 0 || now - c.since < SKIP_LOG_WINDOW_MS) null
                else "poll skipped (no room) src=$source server=$serverId n=${c.count}".also { c.count = 0; c.since = now }
            } ?: continue
            FileLogger.log(CHANNEL_TAG, line)
        }
    }

    /** route, usability and occupancy of [session]'s transport (shells = this registry's tabs on it; headroom = room for one more counted channel). */
    fun describeTransport(serverId: String, session: Session): String {
        val pool = transportPools[serverId]
        val shells = connections.values.count { it.getSession() === session }
        return "route=${pool?.routeOf(session) ?: "unknown"} usable=${pool?.isUsable(session) ?: session.isConnected} " +
            "shells=$shells headroom=${pool?.fallbackHeadroom(session) ?: "n/a"}"
    }

    /** One `channel open failed` line (exit status, route, usability, occupancy): the acceptance metric of the channel budget. */
    fun logChannelOpenFailed(source: String, serverId: String, session: Session, exitStatus: Int?, e: Throwable) {
        FileLogger.log(CHANNEL_TAG, channelOpenFailedLine(source, exitStatus, describeTransport(serverId, session), e))
    }

    /** The server of the tab whose manager is [mgr], or null. */
    fun serverIdOfManager(mgr: SshManager): String? =
        connections.entries.firstOrNull { it.value === mgr }?.key?.let { serverIdOf(it) }

    /**
     * One command on a tab transport of [serverId] with channel headroom (no exec transport involved), for
     * callers that must not fail just because the exec transport is down (the bootstrap). Null = no tab
     * transport has room. With routing off it is the legacy tab session, as before.
     */
    internal suspend fun execOnTab(serverId: String, cmd: String, totalMs: Long): String? =
        if (needsRoom(serverId)) onFallbackTab(serverId, ExecKind.SHORT) { execReadWithWatchdog(it, cmd, totalMs = totalMs) }?.value
        else tabServerSession(serverId)?.let { execReadWithWatchdog(it, cmd, totalMs = totalMs) }

    /** Reconcile execs issued so far, over every server (the load figure of `data/60s exec=`). */
    internal fun reconcileExecCount(): Int = (reconciler as? ExecServerReconciler)?.execCount?.get() ?: 0

    // --- exec routing (the services call these instead of opening channels themselves) ---

    /**
     * True when exec routing is in effect for [serverId]: the seam slots are wired
     * (null = legacy) AND the `exec` group is on for this server (per-server force-off,
     * legacy set and implications included, see [CommFlags.effectiveFor]).
     */
    fun execActive(serverId: String): Boolean =
        execSessions != null && remoteExec != null && CommFlags.effectiveFor(serverId).exec

    /** The gated channel provider for [serverId] when exec routing is active, else null (legacy). */
    fun activeExecChannels(serverId: String): ExecChannels? =
        if (execActive(serverId)) execChannels else null

    /**
     * One command on [serverId], complete or an exception. Exec transport first; when it
     * is not UP ([ExecUnavailableReason.NOT_UP]) the command runs on a live tab session
     * of the server instead. Returns null only when there is no usable transport at all.
     * Any other [ExecUnavailable] (timeout, refused channel, io, gate timeout) is thrown:
     * retrying it on another transport would double the work.
     */
    suspend fun execRun(
        serverId: String,
        cmd: String,
        stdin: ByteArray? = null,
        totalMs: Long = 15_000,
        kind: ExecKind = ExecKind.SHORT,
        paceStdin: Pace = Pace(),
        /** An upload: its fallback keeps off the reserve slot of a packed tab transport (V12), null = no room. */
        transfer: Boolean = false,
    ): ExecResult? {
        val routed = remoteExec
        if (routed != null && execActive(serverId)) {
            try {
                return routed.run(serverId, cmd, stdin, totalMs, kind, paceStdin)
            } catch (e: ExecUnavailable) {
                if (e.reason != ExecUnavailableReason.NOT_UP) throw e
            }
            // exec transport not up: only a tab transport that has room for one more channel
            return onFallbackTab(serverId, kind, transfer) { tab ->
                PooledRemoteExec(UngatedExecChannels { tab }).run(serverId, cmd, stdin, totalMs, kind, paceStdin)
            }?.value
        }
        if (needsRoom(serverId)) {
            // routing switched off while shells are still packed 8 to a transport: the same counted fallback
            return onFallbackTab(serverId, kind, transfer) { tab ->
                PooledRemoteExec(UngatedExecChannels { tab }).run(serverId, cmd, stdin, totalMs, kind, paceStdin)
            }?.value
        }
        val tab = tabServerSession(serverId) ?: return null
        return PooledRemoteExec(UngatedExecChannels { tab }).run(serverId, cmd, stdin, totalMs, kind, paceStdin)
    }

    /**
     * Read-only form of [execRun] for the polling services. With exec routing off the
     * behaviour is today's: [execReadWithWatchdog] on a live tab session. Null = no
     * usable transport (callers skip the round).
     */
    suspend fun execRead(
        serverId: String,
        cmd: String,
        totalMs: Long = 15_000,
        kind: ExecKind = ExecKind.SHORT,
    ): String? {
        val routed = remoteExec
        if (routed != null && execActive(serverId)) {
            try {
                return routed.run(serverId, cmd, null, totalMs, kind).stdout
            } catch (e: ExecUnavailable) {
                if (e.reason != ExecUnavailableReason.NOT_UP) throw e
            }
            return onFallbackTab(serverId, kind) { tab -> execReadWithWatchdog(tab, cmd, totalMs = totalMs) }?.value
        }
        if (needsRoom(serverId)) return onFallbackTab(serverId, kind) { tab -> execReadWithWatchdog(tab, cmd, totalMs = totalMs) }?.value
        val tab = tabServerSession(serverId) ?: return null
        return execReadWithWatchdog(tab, cmd, totalMs = totalMs)
    }

    /**
     * Scoped channel on [serverId] through the gate (SFTP, long-lived streams). Falls
     * back to an ungated channel on a live tab session when the exec transport is not
     * UP. Throws [ExecUnavailable] (NOT_UP) when neither exists. An [ExecUnavailable]
     * thrown by [block] itself is never mistaken for "not up" (the block runs once). A [transfer] (SFTP download) keeps
     * its fallback off the reserve slot of a packed tab transport (V12): NOT_UP there, the user tries again.
     */
    suspend fun <T> useChannel(
        serverId: String,
        kind: ExecKind,
        acquireTimeoutMs: Long = 5_000,
        transfer: Boolean = false,
        block: suspend (com.jcraft.jsch.Channel) -> T,
    ): T {
        val gated = execChannels
        if (gated != null && execActive(serverId)) {
            var entered = false
            try {
                return gated.use(serverId, kind, acquireTimeoutMs) { ch -> entered = true; block(ch) }
            } catch (e: ExecUnavailable) {
                if (entered || e.reason != ExecUnavailableReason.NOT_UP) throw e
            }
            return onFallbackTab(serverId, kind, transfer) { tab -> UngatedExecChannels { tab }.use(serverId, kind, acquireTimeoutMs, block) }
                ?.value ?: throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
        }
        if (needsRoom(serverId)) {
            return onFallbackTab(serverId, kind, transfer) { tab -> UngatedExecChannels { tab }.use(serverId, kind, acquireTimeoutMs, block) }
                ?.value ?: throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
        }
        val tab = tabServerSession(serverId) ?: throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
        return UngatedExecChannels { tab }.use(serverId, kind, acquireTimeoutMs, block)
    }
}
