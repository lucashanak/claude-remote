package com.clauderemote.session.service

import com.jcraft.jsch.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** State of a server's exec transport. The pool work drives it; the stub only reports UP/DOWN. */
internal sealed interface LinkState {
    data class Up(val via: String) : LinkState
    data object Connecting : LinkState
    /** Dead; will be retried. */
    data object Down : LinkState
    /** Network lost; heals itself on [ExecTransportManager.onNetworkAvailable] / await. */
    data object DownNetwork : LinkState
    /** Closed on purpose; never revived. */
    data object Closed : LinkState
}

/** What the services need from the exec transport: the live session, or a bounded wait for it. */
internal interface ExecSessionProvider {
    fun session(serverId: String): Session?
    suspend fun await(serverId: String, ms: Long): Session?
}

/**
 * Seam: one exec transport per server. Contract: [LinkState.DownNetwork] heals by
 * itself, [LinkState.Closed] does not, [generation] increments on every transport
 * death/close so permits and in-flight work can detect a race.
 */
internal interface ExecTransportManager : ExecSessionProvider {
    fun ensure(serverId: String)
    fun state(serverId: String): StateFlow<LinkState>
    fun close(serverId: String)
    fun onNetworkLost()
    fun onNetworkAvailable()
    fun generation(serverId: String): Long
    /** Keepalive interval in MILLISECONDS for every transport, current and future (0 = off). */
    fun setKeepAlive(ms: Int)
}

/**
 * Stub: delegates to the pooled/live session of today (`sessionOf`). No
 * transport of its own, so [ensure]/[close]/network callbacks are no-ops except
 * for bumping [generation]. [state] is a SNAPSHOT flow taken at call time.
 */
internal class PooledExecTransportManager(
    private val sessionOf: (String) -> Session?,
) : ExecTransportManager {
    private val generations = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private fun gen(serverId: String) = generations.getOrPut(serverId) { java.util.concurrent.atomic.AtomicLong() }

    override fun ensure(serverId: String) {}
    override fun session(serverId: String): Session? = sessionOf(serverId)?.takeIf { it.isConnected }
    override suspend fun await(serverId: String, ms: Long): Session? {
        var waited = 0L
        while (true) {
            session(serverId)?.let { return it }
            if (waited >= ms) return null
            delay(100); waited += 100
        }
    }
    override fun state(serverId: String): StateFlow<LinkState> =
        MutableStateFlow(if (session(serverId) != null) LinkState.Up("pool") else LinkState.Down)
    override fun close(serverId: String) { gen(serverId).incrementAndGet() }
    override fun onNetworkLost() { generations.values.forEach { it.incrementAndGet() } }
    override fun onNetworkAvailable() {}
    override fun generation(serverId: String): Long = gen(serverId).get()

    /** Last requested keepalive. The stub owns no transport; the real one applies it to each. */
    @Volatile var keepAliveMs: Int = 0
        private set
    override fun setKeepAlive(ms: Int) { keepAliveMs = ms }
}
