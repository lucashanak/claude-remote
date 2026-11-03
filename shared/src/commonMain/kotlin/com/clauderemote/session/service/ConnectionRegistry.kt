package com.clauderemote.session.service

import com.clauderemote.connection.EtManager
import com.clauderemote.connection.MoshManager
import com.clauderemote.connection.ServerTransportPool
import com.clauderemote.connection.SshManager
import com.clauderemote.session.TabManager
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.Session
import kotlinx.coroutines.sync.Semaphore

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

    // Shared SSH transports per server: tabs lease shell channels on pooled
    // jsch Sessions instead of each owning a TCP/WebSocket + KEX + auth.
    private val transportPools = java.util.concurrent.ConcurrentHashMap<String, ServerTransportPool>()

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
    fun connectGate(serverId: String): Semaphore =
        connectGates.getOrPut(serverId) { Semaphore(3) }

    fun transportPool(serverId: String): ServerTransportPool =
        transportPools.getOrPut(serverId) { ServerTransportPool(serverStorage) }

    fun transportPoolCount(): Int = transportPools.size
    /** Total live TCP transports across all servers — the real connection count. */
    fun liveTransportCount(): Int = transportPools.values.sumOf { it.liveCount() }
    /** A usable pooled session for [serverId] to run one-off execs on (reuse
     *  instead of opening a fresh SSH-over-Cloudflare connection). Null if none. */
    fun pooledSession(serverId: String): Session? = transportPools[serverId]?.anyUsableSession()
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
    fun liveServerSession(serverId: String): Session? = tabServerSession(serverId)

    /** The legacy answer: a CONNECTED tab's session on [serverId], ignoring the exec transport. */
    private fun tabServerSession(serverId: String): Session? {
        for ((sid, mgr) in connections) {
            if (tabManager.getTab(sid)?.server?.id == serverId && mgr.isConnected) {
                mgr.getSession()?.let { return it }
            }
        }
        return null
    }

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
        }
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
        }
        val tab = tabServerSession(serverId) ?: throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
        return UngatedExecChannels { tab }.use(serverId, kind, acquireTimeoutMs, block)
    }
}
