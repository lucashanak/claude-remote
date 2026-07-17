package com.clauderemote.session.service

import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.SshServer
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "SessionOrchestrator"

/**
 * Tailscale / Cloudflare / direct transport selection + connection labels.
 * Extracted verbatim from SessionOrchestrator: the state, timing, ordering,
 * locking and atomic map operations are unchanged — a pure move so the public
 * API and runtime behavior stay identical.
 */
internal class TransportResolver(
    private val scope: CoroutineScope,
    private val registry: ConnectionRegistry,
) {
    // Human-readable "how am I connected" label per session, e.g. "Tailscale · Mosh"
    // or "Cloudflare · SSH". Surfaced as a chip in the chat status bar so the
    // active transport + protocol is glanceable (the choice is otherwise only in
    // the device log). Set on each (re)connect, cleared on disconnect.
    private val _connectionLabels = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    val connectionLabels: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = _connectionLabels

    /** Compute + publish the connection label from the resolved endpoint. */
    fun setConnectionLabel(
        sessionId: String,
        effective: com.clauderemote.model.SshServer,
        original: com.clauderemote.model.SshServer,
        proto: String,
    ) {
        val transport = when {
            effective.useCloudflareProxy -> "Cloudflare"
            original.hasTailscale && effective.host == original.tailscaleHost -> "Tailscale"
            else -> "Direct"
        }
        _connectionLabels.update { it + (sessionId to "$transport · $proto") }
    }

    /**
     * Resolve a server's configured [ServerTransport] into the EFFECTIVE server
     * actually handed to the connection layer. AUTO prefers Tailscale when its
     * host is set and reachable (a quick TCP probe over the system VPN), else
     * falls back to the Cloudflare path — so a Starlink user with the Tailscale
     * VPN up gets the roaming-resilient path automatically, and anyone without
     * it still connects over CF. Resolved fresh on every (re)connect so the best
     * path is re-picked after a drop.
     */
    // AUTO transport: after a failed connect over Tailscale we prefer Cloudflare
    // for this long, so a flaky / unreachable tailnet path can't trap sessions in
    // a reconnect storm. It self-heals back to Tailscale once the cooldown lapses
    // and a connect succeeds. Keyed by server id.
    private val tailscaleCooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // 20 s, armed only after TWO consecutive failures. The old one-strike 60 s
    // ban meant a single slow KEX during a network blip downgraded the whole
    // server to Cloudflare for a minute — every minute, on a flaky link. Two
    // strikes tolerate the transient; 20 s still breaks probe/fail loops.
    private val TS_COOLDOWN_MS = 20_000L
    private val tailscaleFailStreak = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // AUTO resolution cached per server for a short TTL: a reconnect wave used
    // to run the TS probe independently per session, straddling the cooldown
    // window — half the tabs landed on 100.x, half on CF (split-brain, two
    // transport pools to one box, extra KEX churn). One decision per wave.
    private class ResolvedTransport(val at: Long, val eff: com.clauderemote.model.SshServer)
    private val resolvedTransportCache = java.util.concurrent.ConcurrentHashMap<String, ResolvedTransport>()
    private val resolveMutex = Mutex()
    private val RESOLVE_TTL_MS = 10_000L

    private fun isTailscaleEffective(
        server: com.clauderemote.model.SshServer,
        eff: com.clauderemote.model.SshServer,
    ): Boolean = server.hasTailscale && !eff.useCloudflareProxy && eff.host == server.tailscaleHost

    /** Record a connect outcome so AUTO can avoid a dead Tailscale path. */
    fun noteConnectResult(
        server: com.clauderemote.model.SshServer,
        eff: com.clauderemote.model.SshServer,
        ok: Boolean,
    ) {
        if (!isTailscaleEffective(server, eff)) return
        // ok=true is NOT "healthy" — clearing the streak here let every
        // connect-then-instant-death cycle wipe the previous strike before
        // the death could be recorded, so the 2-strike cooldown could only
        // ever arm by the accident of two sessions dying in the same instant
        // (observed in the field: "strike 1/2" logged seven times in a row).
        // The streak is only cleared once a connection PROVES it survives
        // past the early-death window — see recordConnectSuccess's delayed
        // confirm. A connect failure still counts immediately.
        if (!ok) recordTailscaleFailure(server)
    }

    /** One TS strike; two consecutive strikes arm the cooldown. Fed by both
     *  CONNECT failures and post-connect EARLY DEATHS (see below). */
    private fun recordTailscaleFailure(server: com.clauderemote.model.SshServer) {
        // A TS failure means the cached AUTO decision is wrong — drop it so
        // the next resolve re-decides instead of re-serving TS from cache.
        resolvedTransportCache.remove(server.id)
        val streak = (tailscaleFailStreak[server.id] ?: 0) + 1
        tailscaleFailStreak[server.id] = streak
        if (streak >= 2) {
            tailscaleCooldownUntil[server.id] = System.currentTimeMillis() + TS_COOLDOWN_MS
            FileLogger.log(TAG, "Tailscale failed ${streak}× for ${server.name} — using Cloudflare for ${TS_COOLDOWN_MS / 1000}s")
        } else {
            FileLogger.log(TAG, "Tailscale failed for ${server.name} (strike $streak/2)")
        }
    }

    // A Tailscale transport that CONNECTS fine but dies seconds later (bad
    // tunnel path: DERP relay choking on the tmux-redraw burst, MTU blackhole
    // through a subnet router, …) used to loop forever: the successful connect
    // cleared the strike streak, the instant death never counted as a failure,
    // and AUTO re-picked TS every ~2 s. Deaths within this window of a connect
    // now count as TS strikes so the existing 2-strike cooldown breaks the
    // loop and falls back to Cloudflare.
    private val TS_EARLY_DEATH_MS = 30_000L
    private val lastConnectAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastConnectTsEffective = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var lastNetworkTeardownAt = 0L

    /** Record a successful connect for early-death attribution. */
    fun recordConnectSuccess(sessionId: String, server: com.clauderemote.model.SshServer, eff: com.clauderemote.model.SshServer) {
        val connectEpoch = System.currentTimeMillis()
        lastConnectAt[sessionId] = connectEpoch
        val tsEffective = isTailscaleEffective(server, eff)
        lastConnectTsEffective[sessionId] = tsEffective
        if (tsEffective) {
            // Clear the TS strike streak only once THIS connection proves it
            // survives past the early-death window — not at connect time (see
            // noteConnectResult). Superseded by a newer connect (lastConnectAt
            // moved on) or already dead (isConnected false) ⇒ no-op; the death
            // was already counted by maybeCountTsEarlyDeath.
            scope.launch {
                kotlinx.coroutines.delay(TS_EARLY_DEATH_MS)
                if (lastConnectAt[sessionId] == connectEpoch && registry.ssh(sessionId)?.isConnected == true) {
                    tailscaleCooldownUntil.remove(server.id)
                    tailscaleFailStreak.remove(server.id)
                }
            }
        }
        // Fire-and-forget RTT sample the instant a TS connect succeeds, BEFORE
        // the tmux-attach burst that's been killing the transport — the
        // regular 15 s latency loop never gets a turn when a session is dying
        // every ~2 s. A round-trip of tens-hundreds of ms points at a DERP
        // relay hop (e.g. double-CGNAT hole-punch failure); low single-digit
        // ms points at a direct LAN path (rule out relay, look at MTU/offload
        // on the gateway instead). Bounded 3 s so a hung probe can't delay the
        // real tmux attach that follows.
        if (tsEffective) {
            scope.launch {
                try {
                    val sshSession = registry.ssh(sessionId)?.getSession() ?: return@launch
                    val start = System.currentTimeMillis()
                    execReadWithWatchdog(sshSession, "echo pong", totalMs = 3_000)
                    FileLogger.log(TAG, "TS pre-attach RTT for $sessionId: ${System.currentTimeMillis() - start}ms")
                } catch (e: Exception) {
                    FileLogger.log(TAG, "TS pre-attach RTT probe failed for $sessionId: ${e.message}")
                }
            }
        }
    }

    /** From onConnectionLost: count a fresh-connection death on the TS path
     *  as a TS strike — unless WE tore the transport down (network change). */
    fun maybeCountTsEarlyDeath(session: ClaudeSession) {
        val at = lastConnectAt[session.id] ?: return
        if (lastConnectTsEffective[session.id] != true) return
        val now = System.currentTimeMillis()
        if (now - at > TS_EARLY_DEATH_MS) return
        if (now - lastNetworkTeardownAt < 5_000) return
        FileLogger.log(TAG, "Tailscale transport died ${now - at}ms after connect for ${session.id}")
        recordTailscaleFailure(session.server)
    }

    suspend fun resolveTransport(server: com.clauderemote.model.SshServer): com.clauderemote.model.SshServer {
        // Fixed CLOUDFLARE is deterministic — no probe, no cooldown to check.
        if (server.transport == com.clauderemote.model.ServerTransport.CLOUDFLARE) {
            return server.forTransport(server.transport)
        }
        // Fixed TAILSCALE still respects the cooldown. Pinning the transport
        // is "prefer Tailscale", not "Tailscale no matter what" — without this
        // a bad tunnel path (DERP relay dying on bursty traffic, e.g.) looped
        // the app on zero-backoff reconnects forever, because only AUTO's
        // branch ever consulted tailscaleCooldownUntil. Same safety net AUTO
        // gets: fall back to whatever the base server config represents
        // (normally Cloudflare) for the cooldown window, then retry TS.
        if (server.transport == com.clauderemote.model.ServerTransport.TAILSCALE) {
            val cooling = System.currentTimeMillis() < (tailscaleCooldownUntil[server.id] ?: 0L)
            if (cooling) {
                FileLogger.log(TAG, "Tailscale (pinned) cooling down for ${server.name} — using Cloudflare")
                return server.forTransport(com.clauderemote.model.ServerTransport.CLOUDFLARE)
            }
            return server.forTransport(com.clauderemote.model.ServerTransport.TAILSCALE)
        }
        // AUTO: one probe + one decision per server per RESOLVE_TTL_MS window,
        // shared by every session reconnecting in the same wave. The mutex
        // collapses concurrent resolvers onto a single probe.
        resolvedTransportCache[server.id]?.let {
            if (System.currentTimeMillis() - it.at < RESOLVE_TTL_MS) return it.eff
        }
        return resolveMutex.withLock {
            resolvedTransportCache[server.id]?.let {
                if (System.currentTimeMillis() - it.at < RESOLVE_TTL_MS) return@withLock it.eff
            }
            // Skip Tailscale entirely while cooling down from recent failures
            // — no probe, straight to Cloudflare.
            val cooling = System.currentTimeMillis() < (tailscaleCooldownUntil[server.id] ?: 0L)
            val chosen = if (!cooling && server.hasTailscale && tailscaleReachable(server))
                com.clauderemote.model.ServerTransport.TAILSCALE
            else com.clauderemote.model.ServerTransport.CLOUDFLARE
            val eff = server.forTransport(chosen)
            if (eff.host != server.host || eff.useCloudflareProxy != server.useCloudflareProxy) {
                FileLogger.log(TAG, "Transport for ${server.name}: $chosen -> ${eff.host} (cf=${eff.useCloudflareProxy})")
            }
            resolvedTransportCache[server.id] = ResolvedTransport(System.currentTimeMillis(), eff)
            eff
        }
    }

    /**
     * Reachability probe of the Tailscale endpoint (system VPN route),
     * validated by READING THE SSH BANNER — a bare TCP SYN/ACK used to pass
     * even when sshd/KEX would stall, flapping AUTO between TS and CF.
     * Two attempts with a settle pause: on HyperOS the WireGuard tunnel is
     * routinely still re-handshaking at onResume, and the old single 2 s
     * probe fired exactly then — AUTO practically never picked Tailscale on
     * a phone that had just woken up.
     */
    private suspend fun tailscaleReachable(server: com.clauderemote.model.SshServer): Boolean =
        withContext(Dispatchers.IO) {
            repeat(2) { attempt ->
                try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(server.tailscaleHost, server.port), 3_500)
                        s.soTimeout = 3_000
                        // sshd sends "SSH-2.0-…" immediately; any byte proves a
                        // live, responsive daemon behind the route.
                        if (s.getInputStream().read() > 0) return@withContext true
                    }
                } catch (_: Exception) {}
                if (attempt == 0) kotlinx.coroutines.delay(1_500) // let WG finish its handshake
            }
            false
        }

    /** Mark a self-initiated network teardown so post-teardown deaths don't
     *  count as Tailscale early-death strikes. */
    fun markNetworkTeardown() { lastNetworkTeardownAt = System.currentTimeMillis() }

    /** Drop the cached AUTO transport decision for all servers. */
    fun clearResolvedCache() { resolvedTransportCache.clear() }

    /** Drop a disconnected session's early-death connect attribution. */
    fun clearConnectData(sessionId: String) {
        lastConnectAt.remove(sessionId)
        lastConnectTsEffective.remove(sessionId)
    }

    /** Drop a disconnected session's connection label. */
    fun clearConnectionLabel(sessionId: String) { _connectionLabels.update { it - sessionId } }
}
