package com.clauderemote.session.service

import com.clauderemote.connection.EtManager
import com.clauderemote.connection.MoshManager
import com.clauderemote.connection.ServerTransportPool
import com.clauderemote.connection.SshManager
import com.clauderemote.session.TabManager
import com.clauderemote.storage.ServerStorage
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
) {
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
     * sshd's `MaxSessions` 10.
     */
    fun transportPool(serverId: String): ServerTransportPool =
        transportPools.getOrPut(serverId) { ServerTransportPool(serverStorage, execWired = { execUp(it.id) }) }

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

    private class Fallback<T>(val value: T)

    /**
     * Runs [block] on a tab session of [serverId] that has channel headroom, holding one counted channel on
     * its transport for the whole block ([ServerTransportPool.reserveFallbackChannel]). Null = no tab
     * transport has room (a value of null from [block] is wrapped, so the two never mix up). This is what
     * every fallback of the exec layer uses while exec routing is on: when the exec transport is down and
     * the tabs sit 8 to a transport, a fallback must not run into sshd's `MaxSessions`.
     */
    private suspend fun <T> onFallbackTab(serverId: String, block: suspend (Session) -> T): Fallback<T>? {
        val pool = transportPools[serverId]
        for ((sid, mgr) in connections) {
            if (tabManager.getTab(sid)?.server?.id != serverId || !mgr.isConnected) continue
            val s = mgr.getSession() ?: continue
            if (pool != null && !pool.reserveFallbackChannel(s)) continue
            try {
                return Fallback(block(s))
            } finally {
                if (pool != null) withContext(NonCancellable) { pool.releaseChannel(s) }
            }
        }
        return null
    }

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
     * Take one counted fallback channel on [session] of [serverId] (an exec routed to a tab transport by a
     * service that holds the session itself: the pollers of a tab). False = no room: skip the tick. Pair with
     * [releaseTabChannel]. A server that needs no room check always answers true and counts nothing.
     */
    suspend fun reserveTabChannel(serverId: String, session: Session): Boolean {
        if (!needsRoom(serverId)) return true
        val pool = transportPools[serverId] ?: return true
        if (!pool.reserveFallbackChannel(session)) return false
        tabChannelsCounted.computeIfAbsent(session) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
        return true
    }

    /** Give back the channel taken by [reserveTabChannel] (only if that call really counted one). */
    suspend fun releaseTabChannel(serverId: String, session: Session) {
        val n = tabChannelsCounted[session] ?: return
        if (n.get() <= 0) return
        n.decrementAndGet()
        if (n.get() == 0) tabChannelsCounted.remove(session, n)
        transportPools[serverId]?.releaseChannel(session)
    }

    /** Channels counted by [reserveTabChannel] per tab session, so a release gives back only what a reserve took. */
    private val tabChannelsCounted = java.util.concurrent.ConcurrentHashMap<Session, java.util.concurrent.atomic.AtomicInteger>()

    /**
     * One command on a tab transport of [serverId] with channel headroom (no exec transport involved), for
     * callers that must not fail just because the exec transport is down (the bootstrap). Null = no tab
     * transport has room. With routing off it is the legacy tab session, as before.
     */
    internal suspend fun execOnTab(serverId: String, cmd: String, totalMs: Long): String? =
        if (needsRoom(serverId)) onFallbackTab(serverId) { execReadWithWatchdog(it, cmd, totalMs = totalMs) }?.value
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
    ): ExecResult? {
        val routed = remoteExec
        if (routed != null && execActive(serverId)) {
            try {
                return routed.run(serverId, cmd, stdin, totalMs, kind, paceStdin)
            } catch (e: ExecUnavailable) {
                if (e.reason != ExecUnavailableReason.NOT_UP) throw e
            }
            // exec transport not up: only a tab transport that has room for one more channel
            return onFallbackTab(serverId) { tab ->
                PooledRemoteExec(UngatedExecChannels { tab }).run(serverId, cmd, stdin, totalMs, kind, paceStdin)
            }?.value
        }
        if (needsRoom(serverId)) {
            // routing switched off while shells are still packed 8 to a transport: the same counted fallback
            return onFallbackTab(serverId) { tab ->
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
            return onFallbackTab(serverId) { tab -> execReadWithWatchdog(tab, cmd, totalMs = totalMs) }?.value
        }
        if (needsRoom(serverId)) return onFallbackTab(serverId) { tab -> execReadWithWatchdog(tab, cmd, totalMs = totalMs) }?.value
        val tab = tabServerSession(serverId) ?: return null
        return execReadWithWatchdog(tab, cmd, totalMs = totalMs)
    }

    /**
     * Scoped channel on [serverId] through the gate (SFTP, long-lived streams). Falls
     * back to an ungated channel on a live tab session when the exec transport is not
     * UP. Throws [ExecUnavailable] (NOT_UP) when neither exists. An [ExecUnavailable]
     * thrown by [block] itself is never mistaken for "not up" (the block runs once).
     */
    suspend fun <T> useChannel(
        serverId: String,
        kind: ExecKind,
        acquireTimeoutMs: Long = 5_000,
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
            return onFallbackTab(serverId) { tab -> UngatedExecChannels { tab }.use(serverId, kind, acquireTimeoutMs, block) }
                ?.value ?: throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
        }
        if (needsRoom(serverId)) {
            return onFallbackTab(serverId) { tab -> UngatedExecChannels { tab }.use(serverId, kind, acquireTimeoutMs, block) }
                ?.value ?: throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
        }
        val tab = tabServerSession(serverId) ?: throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
        return UngatedExecChannels { tab }.use(serverId, kind, acquireTimeoutMs, block)
    }
}
