package com.clauderemote.session.service

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

    // Handshake gate per server — see SessionOrchestrator.connectSsh: caps
    // concurrent KEX/auth handshakes to a server at 3 so a herd reconnect
    // (e.g. 21 tabs after a Starlink drop) doesn't hammer the link.
    private val connectGates = java.util.concurrent.ConcurrentHashMap<String, Semaphore>()

    // Shared SSH transports per server: tabs lease shell channels on pooled
    // jsch Sessions instead of each owning a TCP/WebSocket + KEX + auth.
    private val transportPools = java.util.concurrent.ConcurrentHashMap<String, ServerTransportPool>()

    // --- SSH transports ---
    fun ssh(sessionId: String): SshManager? = connections[sessionId]
    fun putSsh(sessionId: String, mgr: SshManager) { connections[sessionId] = mgr }
    fun removeSsh(sessionId: String): SshManager? = connections.remove(sessionId)
    fun containsSsh(sessionId: String): Boolean = connections.containsKey(sessionId)
    fun allSsh(): Collection<SshManager> = connections.values
    fun sshEntries(): Set<Map.Entry<String, SshManager>> = connections.entries
    fun clearSsh() { connections.clear() }

    /** Any CONNECTED session's live jsch Session (used for account-wide probes). */
    fun anyLiveSession(): Session? = connections.values.firstOrNull { it.isConnected }?.getSession()

    fun setKeepAliveIntervalAll(keepAlive: Int) {
        connections.values.forEach { it.setKeepAliveInterval(keepAlive) }
    }

    // --- Mosh transports ---
    fun mosh(sessionId: String): MoshManager? = moshConnections[sessionId]
    fun putMosh(sessionId: String, mgr: MoshManager) { moshConnections[sessionId] = mgr }
    fun removeMosh(sessionId: String): MoshManager? = moshConnections.remove(sessionId)

    // --- per-server pools / gates ---
    fun connectGate(serverId: String): Semaphore =
        connectGates.getOrPut(serverId) { Semaphore(3) }

    fun transportPool(serverId: String): ServerTransportPool =
        transportPools.getOrPut(serverId) { ServerTransportPool(serverStorage) }

    fun transportPoolCount(): Int = transportPools.size
    fun teardownAllTransports() { transportPools.values.forEach { it.teardownAll() } }

    // --- tab → server lookups ---
    /** Server id of a session's tab, or null if the tab is gone. */
    fun serverIdOf(sessionId: String): String? =
        tabManager.getTab(sessionId)?.server?.id

    /** All session ids currently on [serverId]. */
    fun sessionIdsOnServer(serverId: String): List<String> =
        tabManager.tabs.value.filter { it.server.id == serverId }.map { it.id }

    /**
     * Any CONNECTED session's live jsch Session on [serverId] — used by the
     * per-server loops (usage, latency, notify watcher) so they survive any
     * single tab's reconnect by simply picking another live connection.
     */
    fun liveServerSession(serverId: String): Session? {
        for ((sid, mgr) in connections) {
            if (tabManager.getTab(sid)?.server?.id == serverId && mgr.isConnected) {
                mgr.getSession()?.let { return it }
            }
        }
        return null
    }
}
