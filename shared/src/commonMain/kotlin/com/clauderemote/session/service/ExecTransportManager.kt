package com.clauderemote.session.service

import com.clauderemote.util.FileLogger
import com.jcraft.jsch.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

/** Pure rules of the exec transport's life: backoff and which deaths count against Tailscale. */
internal object ExecTransportPolicy {
    const val VIA_TS = "TS"
    const val VIA_CF = "CF"
    const val VIA_DIRECT = "DIRECT"

    /** A Tailscale transport that dies sooner than this after it came up is a strike (the tab rule). */
    const val TS_EARLY_DEATH_MS = 30_000L

    /** A transport that lived this long was healthy: the backoff starts over. */
    const val STABLE_MS = 60_000L

    private val FOREGROUND_BACKOFF_S = longArrayOf(0, 1, 2, 4, 8, 16, 30)
    private const val BACKGROUND_FLOOR_MS = 5_000L
    private const val BACKGROUND_CAP_MS = 60_000L

    /** Retry pace of a transport that is down because the NETWORK is gone (it also heals on demand / on the network callback). */
    fun downNetworkRetryMs(background: Boolean): Long = if (background) 30_000L else 10_000L

    /**
     * Delay before reconnect attempt number [attempt] (0 = the first retry after a death).
     * Foreground: 0, 1, 2, 4, 8, 16, 30 s. Background: twice that, at least 5 s, at most 60 s
     * (nobody is waiting for it, the radio is).
     */
    fun backoffMs(attempt: Int, background: Boolean): Long {
        val fg = FOREGROUND_BACKOFF_S[attempt.coerceIn(0, FOREGROUND_BACKOFF_S.lastIndex)] * 1_000
        return if (background) (fg * 2).coerceIn(BACKGROUND_FLOOR_MS, BACKGROUND_CAP_MS) else fg
    }

    /** The attempt counter after a transport that lived [lifetimeMs]: a stable one resets it. */
    fun nextAttempt(previous: Int, lifetimeMs: Long): Int = if (lifetimeMs >= STABLE_MS) 0 else previous + 1

    /** Only a Tailscale transport that died young is a strike; Cloudflare is the last resort and never strikes. */
    fun strikesTailscale(via: String, lifetimeMs: Long): Boolean = via == VIA_TS && lifetimeMs < TS_EARLY_DEATH_MS

    /** `TS`, `CF` or `DIRECT` for the endpoint [eff] resolved from the configured [server]. */
    fun viaOf(server: com.clauderemote.model.SshServer, eff: com.clauderemote.model.SshServer): String = when {
        eff.useCloudflareProxy -> VIA_CF
        server.hasTailscale && eff.host == server.tailscaleHost -> VIA_TS
        else -> VIA_DIRECT
    }
}

/**
 * The real exec transport manager: one long-lived transport per server, role EXEC in
 * the server's [com.clauderemote.connection.ServerTransportPool], owned for as long as
 * the server has a tab (the orchestrator calls [ensure] / [close]).
 *
 * Per server a single supervisor coroutine runs: resolve the endpoint (the same
 * TS/CF/AUTO decision as the tabs), lease the EXEC transport, register the server's
 * L/R port forwards on it, publish [LinkState.Up], then watch it (every [pollMs], and at
 * once when a channel failure is reported) until it dies; then log, bump the
 * generation, feed the Tailscale strike rule and reconnect after the policy's backoff.
 *
 * States: [LinkState.Down] (dead, retried with backoff), [LinkState.DownNetwork]
 * (the network is gone; retried every [ExecTransportPolicy.downNetworkRetryMs], at once
 * on [onNetworkAvailable], and on demand when someone [await]s or asks for the
 * [session]), [LinkState.Closed] (closed on purpose: nothing but an explicit [ensure]
 * revives it — not [await], not the network callbacks).
 *
 * [generation] increments on every death, network loss and close, so permits and
 * in-flight work can tell that the transport they started on is gone.
 */
internal class PoolExecTransportManager(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val poolOf: (String) -> com.clauderemote.connection.ServerTransportPool,
    private val serverOf: (String) -> com.clauderemote.model.SshServer?,
    /** The TS/CF/AUTO decision (TransportResolver.resolveTransport). */
    private val resolve: suspend (com.clauderemote.model.SshServer) -> com.clauderemote.model.SshServer,
    /** Strike feed (configured server, effective endpoint, lifetime ms) — TransportResolver.noteServerEarlyDeath. */
    private val onEarlyDeath: (com.clauderemote.model.SshServer, com.clauderemote.model.SshServer, Long) -> Unit = { _, _, _ -> },
    /** The transport stayed up past the early-death window (configured server, effective endpoint): the Tailscale strike streak starts over. */
    private val onStable: (com.clauderemote.model.SshServer, com.clauderemote.model.SshServer) -> Unit = { _, _ -> },
    private val execEnabled: (String) -> Boolean = { CommFlags.effectiveFor(it).exec },
    private val connectTimeoutMs: Int = 15_000,
    private val pollMs: Long = 5_000,
    /** How long a transport must stay up before [onStable] is told (the Tailscale early-death window). */
    private val stableAfterMs: Long = ExecTransportPolicy.TS_EARLY_DEATH_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : ExecTransportManager {

    private inner class Entry(val serverId: String) {
        val state = MutableStateFlow<LinkState>(LinkState.Down)
        val generation = java.util.concurrent.atomic.AtomicLong()
        @Volatile var session: Session? = null
        @Volatile var job: kotlinx.coroutines.Job? = null
        /** Identity of the running supervisor: its pool lessee, and the owner of [session]. A stale (cancelled) run never touches a newer one's state. */
        @Volatile var run: Any = Any()
        @Volatile var closed = false
        @Volatile var wanted = false
        @Volatile var networkLost = false
        @Volatile var lastAttemptAt = 0L
        /** Wakes the supervisor: network back, channel trouble, someone is waiting. */
        val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    }

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private fun entry(serverId: String): Entry = entries.getOrPut(serverId) { Entry(serverId) }

    /** Background mode: slower reconnect pace. The orchestrator sets it with the foreground/background switch. */
    @Volatile var background: Boolean = false

    @Volatile var keepAliveMs: Int = 0
        private set

    override fun ensure(serverId: String) {
        if (!execEnabled(serverId)) return
        val e = entry(serverId)
        synchronized(e) {
            if (e.closed) {
                e.closed = false
                e.networkLost = false
                e.state.value = LinkState.Down
            }
            e.wanted = true
            poolOf(serverId).setExecForwardsOwned(serverId, true)
            if (e.job?.isActive != true) {
                val token = Any()
                e.run = token
                e.job = scope.launch { supervise(e, token) }
            }
        }
    }

    override fun session(serverId: String): Session? {
        val e = entries[serverId] ?: return null
        val s = e.session
        if (s != null && s.isConnected && e.state.value is LinkState.Up) return s
        nudge(e)
        return null
    }

    override suspend fun await(serverId: String, ms: Long): Session? {
        val e = entries[serverId] ?: return null
        session(serverId)?.let { return it }
        if (e.closed || !e.wanted) return null
        kotlinx.coroutines.withTimeoutOrNull(ms) { e.state.first { it is LinkState.Up || it is LinkState.Closed } }
        return if (e.closed) null else session(serverId)
    }

    override fun state(serverId: String): StateFlow<LinkState> = entry(serverId).state

    override fun generation(serverId: String): Long = entry(serverId).generation.get()

    override fun close(serverId: String) {
        val e = entries[serverId] ?: return
        // All of it under the entry lock: a concurrent ensure() either ran before (and its run is the one
        // released here) or runs after (and owns a fresh run this close never touches).
        val lessee: Any
        synchronized(e) {
            lessee = e.run
            e.closed = true
            e.wanted = false
            e.generation.incrementAndGet()
            e.state.value = LinkState.Closed
            e.job?.cancel()
            e.job = null
            e.session = null
            poolOf(serverId).setExecForwardsOwned(serverId, false)
            e.wake.trySend(Unit)
        }
        scope.launch(kotlinx.coroutines.NonCancellable) { poolOf(serverId).release(lessee) }
    }

    override fun onNetworkLost() {
        for (e in entries.values) {
            if (e.closed || !e.wanted) continue
            e.networkLost = true
            e.generation.incrementAndGet()
            e.state.value = LinkState.DownNetwork
            e.wake.trySend(Unit)   // the supervisor drops its dead transport and parks in DownNetwork
        }
    }

    override fun onNetworkAvailable() {
        for (e in entries.values) {
            if (e.closed || !e.wanted) continue
            e.wake.trySend(Unit)
        }
    }

    override fun setKeepAlive(ms: Int) {
        keepAliveMs = ms
        for (id in entries.keys) poolOf(id).setKeepAlive(ms)
    }

    /** A channel failed in a way that may mean the transport is dead: check it now, not at the next poll. */
    fun noteChannelTrouble(serverId: String) {
        entries[serverId]?.wake?.trySend(Unit)
    }

    /**
     * Someone wants the transport while it is down for the network: try now, at most every 2 s in
     * the foreground and at most every half retry period in the background (a poller must not turn
     * the slow background pace into a connect every 2 s).
     */
    private fun nudge(e: Entry) {
        if (e.closed || !e.wanted) return
        val minGapMs = if (background) ExecTransportPolicy.downNetworkRetryMs(true) / 2 else 2_000L
        if (e.state.value is LinkState.DownNetwork && clock() - e.lastAttemptAt > minGapMs) e.wake.trySend(Unit)
    }

    private suspend fun supervise(e: Entry, token: Any) {
        var attempt = 0
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive && !e.closed) {
                val server = serverOf(e.serverId)
                if (server == null) {
                    e.state.value = if (e.networkLost) LinkState.DownNetwork else LinkState.Down
                    sleepOrWake(e, 30_000)
                    continue
                }
                e.lastAttemptAt = clock()
                if (!e.networkLost) e.state.value = LinkState.Connecting
                val t0 = clock()
                var eff: com.clauderemote.model.SshServer? = null
                var session: Session? = null
                try {
                    eff = resolve(server)
                    session = poolOf(e.serverId).leaseExec(eff, connectTimeoutMs, token)
                    registerForwards(session, eff)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (ex: Exception) {
                    session?.let { poolOf(e.serverId).release(token) }
                    FileLogger.log(TAG, "Exec transport ${server.name} connect failed after ${clock() - t0}ms: ${ex.javaClass.simpleName}: ${ex.message}")
                    if (eff != null && !e.networkLost) {
                        // a connect failure on TS counts like it does for a tab — but not while the
                        // network is gone: then every attempt fails and none says anything about Tailscale
                        onConnectFailed(server, eff)
                    }
                    val wait = if (e.networkLost) {
                        e.state.value = LinkState.DownNetwork
                        ExecTransportPolicy.downNetworkRetryMs(background)
                    } else {
                        e.state.value = LinkState.Down
                        ExecTransportPolicy.backoffMs(attempt++, background)
                    }
                    sleepOrWake(e, wait)
                    continue
                }
                // closed (or superseded) while connecting: publish nothing; the finally releases the lease
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val pool = poolOf(e.serverId)
                val via = ExecTransportPolicy.viaOf(server, eff!!)
                val upAt = clock()
                val published = synchronized(e) {
                    if (e.run !== token || e.closed) false else {
                        e.session = session
                        e.networkLost = false
                        e.state.value = LinkState.Up(via)
                        true
                    }
                }
                if (!published) break
                FileLogger.log(TAG, "Exec transport ${server.name} up via $via in ${upAt - t0}ms route=${pool.routeOf(session!!)}")

                // watch it until it dies, the network goes, or we are closed
                var confirmed = false
                while (kotlinx.coroutines.currentCoroutineContext().isActive && !e.closed && !e.networkLost && pool.isUsable(session)) {
                    sleepOrWake(e, pollMs)
                    if (!confirmed && !e.closed && !e.networkLost && clock() - upAt >= stableAfterMs) {
                        confirmed = true
                        if (ExecTransportPolicy.viaOf(server, eff) == ExecTransportPolicy.VIA_TS) onStable(server, eff)
                    }
                }
                if (e.closed) break
                val lifetime = clock() - upAt
                val byNetwork = e.networkLost
                FileLogger.log(TAG, "Exec transport ${server.name} down after ${lifetime}ms via $via" + if (byNetwork) " (network lost)" else "")
                synchronized(e) { if (e.run === token) e.session = null }
                if (!byNetwork) e.generation.incrementAndGet()   // onNetworkLost already bumped it
                pool.release(token)
                if (byNetwork) {
                    e.state.value = LinkState.DownNetwork
                    attempt = 0
                    sleepOrWake(e, ExecTransportPolicy.downNetworkRetryMs(background))
                } else {
                    e.state.value = LinkState.Down
                    if (ExecTransportPolicy.strikesTailscale(via, lifetime)) onEarlyDeath(server, eff, lifetime)
                    attempt = ExecTransportPolicy.nextAttempt(attempt, lifetime)
                    sleepOrWake(e, ExecTransportPolicy.backoffMs((attempt - 1).coerceAtLeast(0), background))
                }
            }
        } finally {
            synchronized(e) { if (e.run === token) e.session = null }
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { poolOf(e.serverId).release(token) }
        }
    }

    private fun onConnectFailed(server: com.clauderemote.model.SshServer, eff: com.clauderemote.model.SshServer) {
        // Connect failures are fed by TransportResolver.noteConnectResult in the tab path; the exec
        // transport reports them the same way through the early-death hook with lifetime 0.
        if (ExecTransportPolicy.strikesTailscale(ExecTransportPolicy.viaOf(server, eff), 0)) onEarlyDeath(server, eff, 0)
    }

    private fun registerForwards(session: Session, eff: com.clauderemote.model.SshServer) {
        for (pf in eff.portForwards) {
            try {
                when (pf.type) {
                    "L" -> session.setPortForwardingL(pf.localPort, pf.remoteHost, pf.remotePort)
                    "R" -> session.setPortForwardingR(pf.remotePort, pf.remoteHost, pf.localPort)
                }
                FileLogger.log(TAG, "Exec transport ${eff.name}: port forward ${pf.type} ${pf.localPort} <-> ${pf.remoteHost}:${pf.remotePort}")
            } catch (ex: Exception) {
                FileLogger.log(TAG, "Exec transport ${eff.name}: port forward ${pf.type} ${pf.localPort} failed: ${ex.message}")
            }
        }
    }

    /** Sleep [ms] or until woken, whichever first. */
    private suspend fun sleepOrWake(e: Entry, ms: Long) {
        if (ms <= 0) return
        kotlinx.coroutines.withTimeoutOrNull(ms) { e.wake.receive() }
    }

    private companion object {
        const val TAG = "ExecTransport"
    }
}
