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

    // ---- F1 (dávka B): back from the fallback endpoint to the direct one ----

    /** A transport must have lived this long on the fallback before its route is looked at again. */
    const val REROUTE_DWELL_MS = 60_000L

    /** At most one probe per server per this period (and only in the foreground). */
    const val REROUTE_PROBE_EVERY_MS = 60_000L

    /** Probes in a row that must offer the direct endpoint before the transport moves (hysteresis). */
    const val REROUTE_GOOD_PROBES = 2

    /** After a move (or a failed try) the route of that server is not touched for this long. */
    const val REROUTE_COOLDOWN_MS = 300_000L

    /**
     * Two watch passes this far apart (the poll is 5 s) mean the process was frozen (Doze: 281-1 320 s gaps in the phone
     * log): the probe schedule starts over, so the wake does not add a probe to the reconnect wave it starts.
     */
    const val WAKE_GAP_MS = 30_000L

    /**
     * A move binds each port forward on the new transport up to this many times, [FORWARD_BIND_RETRY_MS] apart (R4, review
     * B-1): the port may still be taken for a moment, by the old transport's listener (L) or by the old sshd process, which
     * jsch tells to cancel without waiting for the reply (R). A forward still unbound after that is bound again by the watch.
     */
    const val FORWARD_BIND_TRIES = 5
    const val FORWARD_BIND_RETRY_MS = 200L

    /**
     * Only a QUICK refusal is retried (review B-2, adv-spojeni N1): a bind that failed after longer than this, or on a
     * transport that is no longer connected, is left to the watch. A stalled transport (no RST) makes jsch wait 10 x 1 s
     * for the reply of each R request, so 5 tries held the move, and the watch it runs in, for 51 s. A taken port is
     * refused at once (jsch is woken by the reply).
     */
    const val FORWARD_BIND_QUICK_MS = 1_000L

    /**
     * Whether an exec transport on [via] is on the FALLBACK of a server that has a preferred direct endpoint: the server has
     * a Tailscale/LAN host, is not pinned to Cloudflare, and the transport is not on that host. On the phone that is Cloudflare
     * (base host behind the CF proxy); for a server whose base host is reached directly it is DIRECT.
     */
    fun onFallback(server: com.clauderemote.model.SshServer, via: String): Boolean =
        server.hasTailscale && server.transport != com.clauderemote.model.ServerTransport.CLOUDFLARE && via != VIA_TS

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
    /** Wall clock: lifetimes, the stable window and the F1 schedule (a seam for tests). */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Monotonic clock for the connect breakdown of the `up` line (F2): a frozen process does not inflate it. */
    private val nanoTime: () -> Long = System::nanoTime,
    /** After a move the old transport is let go this much later, so channels in flight on it can finish (make-before-break). */
    private val rerouteDrainMs: Long = 5_000,
    /** Binds one port forward on a transport (a seam for tests: they hold a port taken while a move binds it). */
    private val bindForward: (Session, com.clauderemote.model.PortForward) -> Unit = ::bindForwardOnJsch,
) : ExecTransportManager {

    private inner class Entry(val serverId: String) {
        val state = MutableStateFlow<LinkState>(LinkState.Down)
        val generation = java.util.concurrent.atomic.AtomicLong()
        @Volatile var session: Session? = null
        @Volatile var job: kotlinx.coroutines.Job? = null
        /** Identity of the running supervisor: its pool lessee, and the owner of [session]. A stale (cancelled) run never touches a newer one's state. */
        @Volatile var run: Any = Any()
        /** The pool lessee of the published transport ([run] at first; a new one after each move, see [reroute]). */
        @Volatile var lessee: Any = run
        /** The lessee of a move's new transport between its lease and its publication (released if the run ends there). */
        @Volatile var pendingLessee: Any? = null
        /** When the route of this server last moved, or a move was tried (clock); the F1 cooldown counts from it. */
        @Volatile var lastRerouteAt = Long.MIN_VALUE / 2
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
                e.lessee = token
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
            lessee = e.lessee
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
            // under the entry lock (review B-4 kod L2): a close() between the check and the write left a closed entry DownNetwork
            synchronized(e) {
                if (e.closed || !e.wanted) return@synchronized
                e.networkLost = true
                e.generation.incrementAndGet()
                e.state.value = LinkState.DownNetwork
                e.wake.trySend(Unit)   // the supervisor drops its dead transport and parks in DownNetwork
            }
        }
    }

    override fun onNetworkAvailable() {
        for (e in entries.values) {
            synchronized(e) { if (!e.closed && e.wanted) e.wake.trySend(Unit) }
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

    /**
     * The supervisor's state writes (review B-3 testy-spojeni F1): under the entry lock and only while [token] is still
     * the entry's run and the entry is not closed. A `close()` that landed after the supervisor's last `closed` check
     * (between the check and the write, or during a connect that then failed) left `Connecting` / `Down` /
     * `DownNetwork` instead of `Closed`. [next] null = leave the state as it is.
     */
    private fun setState(e: Entry, token: Any, next: () -> LinkState?) {
        synchronized(e) {
            if (e.run !== token || e.closed) return
            next()?.let { e.state.value = it }
        }
    }

    /**
     * Whether [token] is still the entry's run and the entry is not closed. After `close()` and a new `ensure()` `closed` is
     * false again, but the old run is over: what it sees (a failed connect, a death, a stable window) says nothing about the
     * new run's transport (review B-4 adv-spojeni K4-5, B-5 K5-3).
     */
    private fun stillOurs(e: Entry, token: Any): Boolean = synchronized(e) { e.run === token && !e.closed }

    private suspend fun supervise(e: Entry, token: Any) {
        var attempt = 0
        var lessee: Any = token
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive && !e.closed) {
                val server = serverOf(e.serverId)
                if (server == null) {
                    setState(e, token) { if (e.networkLost) LinkState.DownNetwork else LinkState.Down }
                    sleepOrWake(e, 30_000)
                    continue
                }
                e.lastAttemptAt = clock()
                setState(e, token) { if (e.networkLost) null else LinkState.Connecting }
                // F2: monotonic phases; the wall clock only for diagnosis (a frozen process inflated "in Xms" to 1 330 s)
                val w0 = clock()
                val m0 = nanoTime()
                var mResolved = m0
                var mLeased = m0
                var eff: com.clauderemote.model.SshServer? = null
                var session: Session? = null
                /** The forwards of [eff] the transport could not bind (yet): the watch tries them again on every pass. */
                var unbound: List<com.clauderemote.model.PortForward> = emptyList()
                try {
                    eff = kotlinx.coroutines.withContext(ResolveForExec) { resolve(server) }
                    mResolved = nanoTime()
                    session = poolOf(e.serverId).leaseExec(eff, connectTimeoutMs, lessee)
                    mLeased = nanoTime()
                    unbound = registerForwards(session, eff)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (ex: Exception) {
                    session?.let { poolOf(e.serverId).release(lessee) }
                    FileLogger.log(TAG, "Exec transport ${server.name} connect failed after ${msSince(m0)}ms (wall=${clock() - w0}ms): ${ex.javaClass.simpleName}: ${ex.message}")
                    // only the entry's current run (review B-4 adv-spojeni K4-5): after close() and a new ensure() `closed` is false
                    // again, but this run is over and its failed connect says nothing about Tailscale
                    if (eff != null && !e.networkLost && stillOurs(e, token)) {
                        // a connect failure on TS counts like it does for a tab — but not while the
                        // network is gone: then every attempt fails and none says anything about Tailscale;
                        // nor once closed: a connect cut short by the close says nothing about Tailscale either
                        onConnectFailed(server, eff)
                    }
                    val wait = if (e.networkLost) {
                        setState(e, token) { LinkState.DownNetwork }
                        ExecTransportPolicy.downNetworkRetryMs(background)
                    } else {
                        setState(e, token) { LinkState.Down }
                        ExecTransportPolicy.backoffMs(attempt++, background)
                    }
                    sleepOrWake(e, wait)
                    continue
                }
                val mUp = nanoTime()
                // closed (or superseded) while connecting: publish nothing; the finally releases the lease
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val pool = poolOf(e.serverId)
                var via = ExecTransportPolicy.viaOf(server, eff!!)
                var upAt = clock()
                val published = synchronized(e) {
                    if (e.run !== token || e.closed) false else {
                        e.session = session
                        e.lessee = lessee
                        e.networkLost = false
                        e.state.value = LinkState.Up(via)
                        true
                    }
                }
                if (!published) break
                FileLogger.log(TAG, "Exec transport ${server.name} up via $via in ${(mUp - m0) / 1_000_000}ms route=${pool.routeOf(session!!)}" +
                    " (resolve=${(mResolved - m0) / 1_000_000}ms lease=${(mLeased - mResolved) / 1_000_000}ms" +
                    " forwards=${(mUp - mLeased) / 1_000_000}ms total=${(mUp - m0) / 1_000_000}ms wall=${upAt - w0}ms)")

                // watch it until it dies, the network goes, or we are closed; on the fallback, look for the way back (F1)
                var confirmed = false
                val reroute = Reroute(upAt)
                // the probe runs detached: it must not outlive this watch (a cancelled supervisor included)
                try { while (kotlinx.coroutines.currentCoroutineContext().isActive && !e.closed && !e.networkLost && pool.isUsable(session!!)) {
                    sleepOrWake(e, pollMs)
                    if (!confirmed && !e.closed && !e.networkLost && clock() - upAt >= stableAfterMs) {
                        confirmed = true
                        // K5-3: not for a run that close() and a new ensure() ended (it would reset the new run's streak)
                        if (via == ExecTransportPolicy.VIA_TS && stillOurs(e, token)) onStable(server, eff!!)
                    }
                    if (unbound.isNotEmpty() && !e.closed && !e.networkLost && pool.isUsable(session!!)) {
                        unbound = registerForwards(session!!, eff!!, unbound, again = true)
                    }
                    if (e.closed || e.networkLost || !ExecTransportPolicy.onFallback(server, via)) continue
                    val target = reroute.tick(e, server, upAt) ?: continue
                    val moved = moveTo(e, token, server, session, lessee, eff!!, via, target, unbound) ?: continue
                    session = moved.session
                    lessee = moved.lessee
                    eff = target
                    via = moved.via
                    unbound = moved.unbound
                    upAt = clock()
                    confirmed = false
                    reroute.restart(upAt)
                } } finally { reroute.cancel() }
                // closed, or superseded by close() and a new ensure() (review B-5 adv-spojeni K5-3): the old run left the watch because
                // it was cancelled, not because its transport died; no "down after" line, no generation bump, no strike
                if (!stillOurs(e, token)) break
                val lifetime = clock() - upAt
                val byNetwork = e.networkLost
                FileLogger.log(TAG, "Exec transport ${server.name} down after ${lifetime}ms via $via" + if (byNetwork) " (network lost)" else "")
                synchronized(e) { if (e.run === token) e.session = null }
                if (!byNetwork) e.generation.incrementAndGet()   // onNetworkLost already bumped it
                pool.release(lessee)
                if (byNetwork) {
                    setState(e, token) { LinkState.DownNetwork }
                    attempt = 0
                    sleepOrWake(e, ExecTransportPolicy.downNetworkRetryMs(background))
                } else {
                    setState(e, token) { LinkState.Down }
                    // K5-3: checked again here, a close() and ensure() may land after the check above
                    if (ExecTransportPolicy.strikesTailscale(via, lifetime) && stillOurs(e, token)) onEarlyDeath(server, eff!!, lifetime)
                    attempt = ExecTransportPolicy.nextAttempt(attempt, lifetime)
                    sleepOrWake(e, ExecTransportPolicy.backoffMs((attempt - 1).coerceAtLeast(0), background))
                }
            }
        } finally {
            synchronized(e) { if (e.run === token) e.session = null }
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                poolOf(e.serverId).release(lessee)
                e.pendingLessee?.let { poolOf(e.serverId).release(it) }
            }
        }
    }

    private fun msSince(startNs: Long): Long = (nanoTime() - startNs) / 1_000_000

    /**
     * F1 schedule of one transport on the fallback: a probe (a fresh resolve, as the exec transport) at most every
     * [ExecTransportPolicy.REROUTE_PROBE_EVERY_MS], the first after [ExecTransportPolicy.REROUTE_DWELL_MS], only in the
     * foreground and outside the cooldown after a move; [ExecTransportPolicy.REROUTE_GOOD_PROBES] good ones in a row make a
     * move. The probe runs detached (it may take 14.5 s) so the watch keeps noticing a death meanwhile; nothing is struck.
     */
    private inner class Reroute(upAt: Long) {
        private var lastProbeAt = upAt
        private var lastTick = upAt
        private var good = 0
        private var probe: kotlinx.coroutines.Job? = null
        /** What the last probe's resolve offered (null: it failed or was cancelled). Written by the probe, read after it ended. */
        @Volatile private var offered: com.clauderemote.model.SshServer? = null

        fun restart(at: Long) { cancel(); lastProbeAt = at; lastTick = at; good = 0 }

        fun cancel() { probe?.cancel(); probe = null; offered = null }

        /** The endpoint to move to now, or null. */
        fun tick(e: Entry, server: com.clauderemote.model.SshServer, upAt: Long): com.clauderemote.model.SshServer? {
            val now = clock()
            if (now - lastTick > ExecTransportPolicy.WAKE_GAP_MS || now < lastTick) {
                // the process was frozen (or the wall clock went back): start the schedule over from the wake
                restart(now)
                return null
            }
            lastTick = now
            if (background) { cancel(); good = 0; return null }
            val running = probe
            if (running != null) {
                if (running.isActive) return null
                probe = null
                val offered = this.offered.also { this.offered = null }
                val direct = offered != null && ExecTransportPolicy.viaOf(server, offered) == ExecTransportPolicy.VIA_TS
                good = if (direct) good + 1 else 0
                FileLogger.log(TAG, "Exec transport ${server.name} reroute probe: " +
                    (if (direct) "direct endpoint offered" else "still the fallback") + " ($good/${ExecTransportPolicy.REROUTE_GOOD_PROBES})")
                if (good < ExecTransportPolicy.REROUTE_GOOD_PROBES) return null
                good = 0
                return offered
            }
            if (now - upAt < ExecTransportPolicy.REROUTE_DWELL_MS) return null
            if (now - lastProbeAt < ExecTransportPolicy.REROUTE_PROBE_EVERY_MS) return null
            if (now - e.lastRerouteAt < ExecTransportPolicy.REROUTE_COOLDOWN_MS) return null
            lastProbeAt = now
            offered = null
            probe = scope.launch {
                offered = try {
                    kotlinx.coroutines.withContext(ResolveForExec) { resolve(server) }
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (_: Exception) {
                    null
                }
            }
            return null
        }
    }

    private class Moved(val session: Session, val lessee: Any, val via: String, val unbound: List<com.clauderemote.model.PortForward>)

    /**
     * Make-before-break move of the exec transport to [target]: lease the new transport, publish it (`generation++`, so the
     * permits of the old one are void), move the port forwards, and only then, [rerouteDrainMs] later, let the old one go.
     * A failure leaves the old transport in place and is no strike. Either way the cooldown starts now.
     *
     * The forwards cannot be bound on the new transport before they are freed on the old one: an L forward's port is a
     * listener of this process, an R forward's port one of the old sshd process, and a second bind of either fails. So:
     * free them on the old one, bind each on the new one with [ExecTransportPolicy.FORWARD_BIND_TRIES] tries, and let the
     * watch bind what is still unbound on each pass (at the latest one pass after the old transport is gone, which frees
     * an R port for sure).
     */
    private suspend fun moveTo(
        e: Entry,
        token: Any,
        server: com.clauderemote.model.SshServer,
        oldSession: Session,
        oldLessee: Any,
        oldEff: com.clauderemote.model.SshServer,
        oldVia: String,
        target: com.clauderemote.model.SshServer,
        /** The forwards the old transport never bound: nothing to free there. */
        oldUnbound: List<com.clauderemote.model.PortForward>,
    ): Moved? {
        if (background) return null
        e.lastRerouteAt = clock()
        val pool = poolOf(e.serverId)
        val newVia = ExecTransportPolicy.viaOf(server, target)
        val newLessee = Any()
        e.pendingLessee = newLessee
        val m0 = nanoTime()
        val newSession = try {
            pool.leaseExec(target, connectTimeoutMs, newLessee)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c   // the supervisor's finally releases the pending lessee
        } catch (ex: Exception) {
            e.pendingLessee = null
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { pool.release(newLessee) }
            FileLogger.log(TAG, "Exec transport ${server.name} reroute $oldVia -> $newVia failed after ${msSince(m0)}ms, staying on $oldVia: ${ex.javaClass.simpleName}: ${ex.message}")
            return null
        }
        val published = synchronized(e) {
            if (e.run !== token || e.closed || e.networkLost) false else {
                e.session = newSession
                e.lessee = newLessee
                e.pendingLessee = null
                e.generation.incrementAndGet()
                e.state.value = LinkState.Up(newVia)
                true
            }
        }
        if (!published) {
            e.pendingLessee = null
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { pool.release(newLessee) }
            return null
        }
        // the old transport still binds the forwards' ports: free them there, then bind them on the new one (with retries)
        unregisterForwards(oldSession, oldEff, oldEff.portForwards - oldUnbound.toSet())
        val unbound = registerForwards(newSession, target, tries = ExecTransportPolicy.FORWARD_BIND_TRIES)
        FileLogger.log(TAG, "Exec transport ${server.name} rerouted $oldVia -> $newVia in ${msSince(m0)}ms route=${pool.routeOf(newSession)}")
        scope.launch(kotlinx.coroutines.NonCancellable) {
            delay(rerouteDrainMs)
            pool.release(oldLessee)
        }
        return Moved(newSession, newLessee, newVia, unbound)
    }

    private fun onConnectFailed(server: com.clauderemote.model.SshServer, eff: com.clauderemote.model.SshServer) {
        // Connect failures are fed by TransportResolver.noteConnectResult in the tab path; the exec
        // transport reports them the same way through the early-death hook with lifetime 0.
        if (ExecTransportPolicy.strikesTailscale(ExecTransportPolicy.viaOf(server, eff), 0)) onEarlyDeath(server, eff, 0)
    }

    /**
     * Binds [forwards] on [session], each up to [tries] times [ExecTransportPolicy.FORWARD_BIND_RETRY_MS] apart, and returns
     * those still unbound (the watch tries them again). [again]: the watch's re-bind, which logs only a success.
     *
     * A bind that failed slowly or on a transport that is down (see [ExecTransportPolicy.FORWARD_BIND_QUICK_MS]) ends the
     * whole pass (review B-3 adv-spojeni R3-2, kod OQ1): on a stalled transport every further R bind would wait jsch's
     * 10 x 1 s again (a move with 2 R forwards took 20 s). The forwards not tried go first in the result, so the watch's
     * next pass tries them before the one that failed slowly, and one forward that always fails slowly cannot keep the
     * others from ever being bound.
     */
    private suspend fun registerForwards(
        session: Session,
        eff: com.clauderemote.model.SshServer,
        forwards: List<com.clauderemote.model.PortForward> = eff.portForwards,
        tries: Int = 1,
        again: Boolean = false,
    ): List<com.clauderemote.model.PortForward> {
        val unbound = ArrayList<com.clauderemote.model.PortForward>()
        for ((i, pf) in forwards.withIndex()) {
            val what = "port forward ${pf.type} ${pf.localPort}"
            var failure: Exception? = null
            /** Why the retries stopped early (a slow failure or a dead transport), null when they did not. */
            var stopped: String? = null
            for (n in 1..tries) {
                val t0 = nanoTime()
                failure = try {
                    bindForward(session, pf)
                    val how = if (again) " bound again" else if (n > 1) " (try $n/$tries)" else ""
                    FileLogger.log(TAG, "Exec transport ${eff.name}: $what <-> ${pf.remoteHost}:${pf.remotePort}$how")
                    null
                } catch (ex: Exception) {
                    ex
                }
                if (failure == null) break
                val tookMs = (nanoTime() - t0) / 1_000_000
                stopped = when {
                    !session.isConnected -> "try $n/$tries, the transport is down"
                    tookMs > ExecTransportPolicy.FORWARD_BIND_QUICK_MS -> "try $n/$tries took ${tookMs}ms"
                    else -> null
                }
                if (stopped != null || n == tries) break
                delay(ExecTransportPolicy.FORWARD_BIND_RETRY_MS)
            }
            if (failure == null) continue
            unbound += pf
            if (!again) FileLogger.log(TAG, "Exec transport ${eff.name}: $what " +
                (if (stopped != null) "not bound ($stopped)" else if (tries > 1) "not bound after $tries tries" else "failed") +
                ": ${failure.message}; the watch binds it again")
            if (stopped != null && i < forwards.lastIndex) {
                val untried = forwards.subList(i + 1, forwards.size)
                FileLogger.log(TAG, "Exec transport ${eff.name}: ${untried.size} more forward(s) left to the watch untried ($stopped)")
                return untried + unbound
            }
        }
        return unbound
    }

    private fun unregisterForwards(session: Session, eff: com.clauderemote.model.SshServer, forwards: List<com.clauderemote.model.PortForward>) {
        for (pf in forwards) {
            try {
                when (pf.type) {
                    "L" -> session.delPortForwardingL(pf.localPort)
                    "R" -> session.delPortForwardingR(pf.remotePort)
                }
            } catch (ex: Exception) {
                FileLogger.log(TAG, "Exec transport ${eff.name}: port forward ${pf.type} ${pf.localPort} not freed on the old transport: ${ex.message}")
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

/** The real bind of [pf] on [session] (jsch). */
internal fun bindForwardOnJsch(session: Session, pf: com.clauderemote.model.PortForward) {
    when (pf.type) {
        "L" -> session.setPortForwardingL(pf.localPort, pf.remoteHost, pf.remotePort)
        "R" -> session.setPortForwardingR(pf.remotePort, pf.remoteHost, pf.localPort)
    }
}
