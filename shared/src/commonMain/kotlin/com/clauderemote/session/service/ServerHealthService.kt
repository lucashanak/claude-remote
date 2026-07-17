package com.clauderemote.session.service

import com.clauderemote.model.ServerHealth
import com.clauderemote.model.SshServer
import com.clauderemote.session.TabManager
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SessionOrchestrator"

/**
 * Server reachability health (launcher dot) + per-server SSH latency polling.
 * Extracted verbatim from SessionOrchestrator: the state, timing, ordering,
 * locking and atomic map operations are unchanged — a pure move so the public
 * API and runtime behavior stay identical.
 */
internal class ServerHealthService(
    private val scope: CoroutineScope,
    private val registry: ConnectionRegistry,
    private val tabManager: TabManager,
    private val isBackground: () -> Boolean,
) {
    // Per-session SSH latency (ms)
    private val _latencies = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
    val latencies: kotlinx.coroutines.flow.StateFlow<Map<String, Long>> = _latencies

    // Per-server reachability for the launcher health dot. Keyed by server id.
    // Separate from the serialized SshServer model (mirrors gitStatuses etc.).
    private val _serverHealth = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ServerHealth>>(emptyMap())
    val serverHealth: kotlinx.coroutines.flow.StateFlow<Map<String, ServerHealth>> = _serverHealth
    // Debounce: last probe time per server id, so pull-to-refresh spam doesn't storm.
    private val lastServerProbeAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Per-SERVER periodic latency polling jobs (keyed by server id). Latency (all
    // sessions share the physical link) is identical for every session on a
    // server. ConcurrentHashMap + compute(): every session's attach/reconnect
    // touches its server's entry, overlapping with disconnects during a
    // reconnect storm.
    private val latencyPollingJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * Probe reachability of each [servers] entry and publish to [serverHealth]
     * (keyed by server id). Off the UI thread, time-bounded, and debounced.
     * Branches per server:
     *  - A live, connected SSH session already exists for the server →
     *    [ServerHealth.ONLINE] immediately (no redundant socket probe).
     *  - [SshServer.useCloudflareProxy] → [ServerHealth.UNKNOWN] (a raw TCP
     *    probe to a Cloudflare-tunneled host is misleading; don't show false
     *    OFFLINE).
     *  - Otherwise → [ServerHealth.CHECKING], then a 2.5s TCP connect on
     *    Dispatchers.IO; success → ONLINE, any failure/timeout → OFFLINE.
     * Skipped entirely while [isInBackground]; per-server debounced to ~5s.
     */
    /** Remove a deleted server's health entry so no stale state leaks. */
    fun pruneServerHealth(serverId: String) {
        _serverHealth.update { it - serverId }
        lastServerProbeAt.remove(serverId)
    }

    fun probeServers(servers: List<SshServer>, force: Boolean = false) {
        if (isBackground()) return
        val now = System.currentTimeMillis()
        for (server in servers) {
            val last = lastServerProbeAt[server.id] ?: 0L
            if (!force && now - last < 5_000L) continue
            lastServerProbeAt[server.id] = now
            scope.launch {
                // 1) Reuse a live connection → ONLINE without a socket probe.
                val hasLiveConnection = registry.sshEntries().any { (sessionId, mgr) ->
                    mgr.isConnected && tabManager.getTab(sessionId)?.server?.id == server.id
                }
                if (hasLiveConnection) {
                    _serverHealth.update { it + (server.id to ServerHealth.ONLINE) }
                    return@launch
                }
                // 2) Cloudflare-tunneled host → a raw TCP probe is misleading.
                if (server.useCloudflareProxy) {
                    _serverHealth.update { it + (server.id to ServerHealth.UNKNOWN) }
                    return@launch
                }
                // 3) Raw TCP connect, time-bounded, off the UI thread.
                _serverHealth.update { if (it[server.id] == null) it + (server.id to ServerHealth.CHECKING) else it }
                val reachable = withContext(Dispatchers.IO) {
                    val socket = java.net.Socket()
                    try {
                        socket.connect(java.net.InetSocketAddress(server.host, server.port), 2500)
                        true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
                _serverHealth.update {
                    it + (server.id to if (reachable) ServerHealth.ONLINE else ServerHealth.OFFLINE)
                }
            }
        }
    }

    fun startServerLatencyPolling(serverId: String) {
        // Idempotent per server: every session on a server shares the physical
        // link, so 21 per-session `echo pong`s measured the same RTT 21×. One
        // probe per server, fanned out to all its sessions for display.
        // compute() = atomic check-and-launch (see startServerUsagePolling).
        latencyPollingJobs.compute(serverId) { _, existing ->
            if (existing?.isActive == true) return@compute existing
            scope.launch {
                kotlinx.coroutines.delay(3000)
                val recentLatencies = mutableListOf<Long>()
                while (isActive) {
                    if (!isBackground()) {
                        try {
                            // Missing connection (mid-reconnect) skips the round;
                            // the old `?: break` killed the loop permanently.
                            val sshSession = registry.liveServerSession(serverId)
                            if (sshSession == null) {
                                kotlinx.coroutines.delay(15_000)
                                continue
                            }
                            val latency = kotlin.run {
                                val start = System.currentTimeMillis()
                                execReadWithWatchdog(sshSession, "echo pong", totalMs = 10_000)
                                System.currentTimeMillis() - start
                            }
                            recentLatencies.add(latency)
                            if (recentLatencies.size > 5) recentLatencies.removeAt(0)
                            val avg = recentLatencies.average().toLong()
                            // Logged (not just published to the UI StateFlow) so
                            // remote-shipped logs carry an RTT trail — the
                            // signal that tells direct-LAN (~ms) apart from a
                            // DERP relay hop (tens-hundreds of ms) without
                            // needing tailscale CLI access on either end.
                            FileLogger.log(TAG, "Latency for server $serverId: ${latency}ms (avg ${avg}ms)")
                            // Fan out only to sessions with a live connection —
                            // a tab mid-teardown (entry already cleared by
                            // disconnectSession, tab not yet removed) must not
                            // be re-added as a permanent stale map entry.
                            val ids = registry.sessionIdsOnServer(serverId).filter { registry.containsSsh(it) }
                            _latencies.update { it + ids.associateWith { avg } }
                        } catch (_: Exception) {}
                    }
                    kotlinx.coroutines.delay(15_000) // every 15s
                }
            }
        }
    }

    /** Cancel + drop this server's latency polling job (last session left). */
    fun stopLatencyPolling(serverId: String) { latencyPollingJobs.remove(serverId)?.cancel() }

    /** Drop a disconnected session's latency entry. */
    fun clearSession(sessionId: String) { _latencies.update { it - sessionId } }
}
