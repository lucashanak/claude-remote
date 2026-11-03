package com.clauderemote.session.service

import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/**
 * Seam: per-server reconcile (to be implemented with: one 15 s loop per server,
 * `reconcileAll` exec, forget only with a live peer, CAP).
 */
internal interface ServerReconciler {
    fun register(sessionId: String)
    fun unregister(sessionId: String)
    /** Wait [delayMs], check whether the tmux session is closed on the server, forget locally if so. */
    suspend fun checkClosed(sessionId: String, delayMs: Long)
    /** Re-read the real Claude UUID of [sessionId] and adopt it. */
    suspend fun checkUuid(sessionId: String)
    /** CAP verdict (hooks active for this pane); null = unknown. Contract: [HookCapContract]. */
    suspend fun capability(serverId: String, tmux: String): Boolean?
}

/**
 * Server-side facts the legacy forget rule needs (null from the provider = unknown).
 * [tmuxAlive] must be gathered FAIL-OPEN: when the probe itself fails (flaky link,
 * timeout) the provider returns `tmuxAlive = true` or no facts at all, never
 * `false` — the rule forgets a session the moment it sees `tombstoned ∧ !tmuxAlive`.
 */
internal data class ClosedFacts(val tombstoned: Boolean, val tmuxAlive: Boolean)

/** TODAY's forget rule (SessionPersistenceService.startSessionIdRefresh, "CLOSED ON ANOTHER DEVICE"). */
internal object LegacyForgetRule {
    /** Forget iff the server tombstoned the name AND the pane is really gone. No peer-liveness fact (a later change tightens this). */
    fun shouldForget(facts: ClosedFacts): Boolean = facts.tombstoned && !facts.tmuxAlive
}

/**
 * Stub that EXECUTES the legacy rule (so a later tightening is observable as a
 * behaviour change). Facts and the forget action are injected; with neither wired
 * (nobody calls it yet, the per-tab loop in SessionPersistenceService keeps doing
 * the real work) it just logs `stub`. Like today's loop it does nothing while
 * [isBackground], forgets only ids that are still registered after the delay, and
 * treats a throwing [facts] as unknown. [checkUuid] stays a no-op because the legacy
 * loop already adopts UUIDs; [capability] is unknown.
 */
internal class LegacyServerReconciler(
    private val facts: (suspend (String) -> ClosedFacts?)? = null,
    private val forget: (suspend (String) -> Unit)? = null,
    private val isBackground: () -> Boolean = { false },
) : ServerReconciler {
    private val registered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val registeredIds: Set<String> get() = registered.toSet()

    override fun register(sessionId: String) { registered.add(sessionId) }
    override fun unregister(sessionId: String) { registered.remove(sessionId) }

    override suspend fun checkClosed(sessionId: String, delayMs: Long) {
        if (delayMs > 0) delay(delayMs)
        if (sessionId !in registered) {
            FileLogger.log(TAG, "checkClosed($sessionId) skipped: not registered (any more)")
            return
        }
        if (isBackground()) return
        val f = try {
            facts?.invoke(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.log(TAG, "checkClosed($sessionId): facts failed (${e.javaClass.simpleName}), treated as unknown")
            null
        }
        if (f == null || forget == null) {
            FileLogger.log(TAG, "checkClosed($sessionId) stub: no facts/forget wired")
            return
        }
        if (LegacyForgetRule.shouldForget(f)) forget.invoke(sessionId)
    }

    override suspend fun checkUuid(sessionId: String) {
        FileLogger.log(TAG, "checkUuid($sessionId) stub: legacy per-tab loop adopts UUIDs")
    }

    override suspend fun capability(serverId: String, tmux: String): Boolean? = null

    private companion object { const val TAG = "ServerReconciler" }
}

/**
 * The real reconciler: one loop per server (every [periodMs], foreground only) that runs
 * ONE [ManifestCommands.reconcileAll] exec for all registered tabs of that server and
 * applies the result to each of them:
 *  - **forget** a tab whose tmux name is tombstoned ∧ absent from `list-sessions` ∧ has at
 *    least one other live peer ([ReconcileSnapshot.closeVerdict]); unknown never forgets;
 *  - **adopt** the real claude session id (pane subtree probe, manifest as the weaker
 *    fallback) through [adoptUuid], which owns the claimed-by-another-tab guard.
 * The same result answers [capability]; [checkClosed] and [checkUuid] run a round on demand.
 * It works with zero attached shells because [run] goes through the exec transport.
 *
 * Collaborators are injected as functions so the class holds no reference to the tab or
 * persistence services (and can be driven with fakes and virtual time).
 */
internal class ExecServerReconciler(
    private val scope: CoroutineScope,
    /** Run [cmd] on the server; null = no usable transport. Throws on any other failure. */
    private val run: suspend (serverId: String, cmd: String, totalMs: Long) -> String?,
    private val serverIdOf: (sessionId: String) -> String?,
    private val tmuxNameOf: (sessionId: String) -> String?,
    private val tabUuidOf: (sessionId: String) -> String?,
    private val adoptUuid: suspend (sessionId: String, uuid: String, source: String) -> Unit,
    private val forget: suspend (sessionId: String) -> Unit,
    private val isBackground: () -> Boolean,
    private val periodMs: Long = 15_000,
    private val recheckMs: Long = 5_000,
    /** A round younger than this answers [capability] and [checkUuid] without a new exec. */
    private val capTtlMs: Long = 30_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ServerReconciler {
    private class Round(val at: Long, val names: Set<String>, val snapshot: ReconcileSnapshot)

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val registered = java.util.concurrent.ConcurrentHashMap<String, String>() // sessionId -> serverId
    private val extraNames = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>() // asked by capability()
    private val forgetting = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val loops = HashMap<String, kotlinx.coroutines.Job>()
    private val last = java.util.concurrent.ConcurrentHashMap<String, Round>()
    private val mutexes = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** Number of reconcile execs issued so far (the load figure `data/60s exec=` reports per server is derived from it). */
    val execCount = java.util.concurrent.atomic.AtomicInteger()

    override fun register(sessionId: String) = register(sessionId, firstRoundDelayMs = 1_000)

    private fun register(sessionId: String, firstRoundDelayMs: Long) {
        val serverId = serverIdOf(sessionId) ?: return
        registered[sessionId] = serverId
        synchronized(loops) {
            if (loops[serverId]?.isActive == true) return
            loops[serverId] = scope.launch { loop(serverId, firstRoundDelayMs) }
        }
    }

    override fun unregister(sessionId: String) {
        val serverId = registered.remove(sessionId) ?: return
        tmuxNameOf(sessionId)?.let { name -> extraNames[serverId]?.remove(name) }
        synchronized(loops) {
            if (registered.values.none { it == serverId }) loops.remove(serverId)?.cancel()
        }
    }

    private suspend fun loop(serverId: String, firstRoundDelayMs: Long) {
        delay(firstRoundDelayMs)
        while (true) {
            try {
                if (!isBackground()) {
                    val round = round(serverId, maxAgeMs = 0)
                    if (round != null) apply(round.snapshot, registered.filterValues { it == serverId }.keys.toList())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.log(TAG, "reconcile round for $serverId failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            delay(periodMs)
        }
    }

    /** One round (single-flight per server); a round younger than [maxAgeMs] is reused (0 = always run one). Null = no transport. */
    private suspend fun round(serverId: String, maxAgeMs: Long): Round? {
        last[serverId]?.let { if (clock() - it.at < maxAgeMs) return it }
        val mutex = mutexes.getOrPut(serverId) { kotlinx.coroutines.sync.Mutex() }
        return mutex.withLock {
            last[serverId]?.let { if (clock() - it.at < maxAgeMs) return@withLock it }
            val names = (registered.filterValues { it == serverId }.keys.mapNotNull(tmuxNameOf) +
                extraNames[serverId].orEmpty()).toSet()
            execCount.incrementAndGet()
            val out = run(serverId, ManifestCommands.reconcileAll(names.sorted()), 20_000) ?: return@withLock null
            val snapshot = ReconcileParse.parse(out, json)
            // Names that tmux no longer has (renamed or closed tabs) would otherwise stay in the pane filter forever.
            snapshot.tmuxNames?.let { present -> extraNames[serverId]?.retainAll { it in present } }
            Round(clock(), names, snapshot).also { last[serverId] = it }
        }
    }

    /**
     * Apply [snap] to the tabs [sessionIds]: forget the closed ones (only when [allowForget] and the tab is
     * registered), adopt the real uuid of the rest.
     */
    private suspend fun apply(snap: ReconcileSnapshot, sessionIds: List<String>, allowForget: Boolean = true) {
        for (sid in sessionIds) {
            val name = tmuxNameOf(sid) ?: continue
            if (snap.closeVerdict(name) == CloseVerdict.FORGET) {
                if (!allowForget || !registered.containsKey(sid)) continue
                // A second round (or checkClosed) while the teardown runs must not forget the same tab again.
                if (!forgetting.add(sid)) continue
                FileLogger.log(TAG, "Tmux '$name' tombstoned, gone and another live session exists — closed on another device, forgetting locally")
                // The teardown behind [forget] ends by unregistering the last tab of a server, which cancels
                // this very loop; it must run to the end regardless, and unregister only after it.
                withContext(NonCancellable) {
                    try {
                        forget(sid)
                        unregister(sid)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The tab was not torn down: it stays (or goes back to being) registered and is tried again.
                        FileLogger.log(TAG, "forget of $sid failed: ${e.javaClass.simpleName}: ${e.message}")
                        register(sid, firstRoundDelayMs = periodMs) // a whole period before the retry, not a retry storm
                    } finally {
                        forgetting.remove(sid)
                    }
                }
                continue
            }
            val live = snap.liveUuid(name)
            val uuid = live ?: snap.manifestUuid(name)
            // A live (pid-probe) answer is passed on even when it equals the tab's id: it confirms it.
            if (uuid != null && (live != null || uuid != tabUuidOf(sid))) adoptUuid(sid, uuid, if (live != null) "pid-probe" else "server")
        }
    }

    override suspend fun checkClosed(sessionId: String, delayMs: Long) {
        if (delayMs > 0) delay(delayMs)
        if (!registered.containsKey(sessionId)) {
            FileLogger.log(TAG, "checkClosed($sessionId) skipped: not registered (any more)")
            return
        }
        if (isBackground()) return
        val serverId = registered[sessionId] ?: return
        val name = tmuxNameOf(sessionId) ?: return
        var r = round(serverId, maxAgeMs = 0) ?: return
        if (r.snapshot.closeVerdict(name) == CloseVerdict.FORGET) { apply(r.snapshot, listOf(sessionId)); return }
        // Still present in tmux: the pane may be on its way out. One more look.
        if (r.snapshot.tmuxNames?.contains(name) == true) {
            delay(recheckMs)
            if (!registered.containsKey(sessionId)) return
            r = round(serverId, maxAgeMs = 0) ?: return
            if (r.snapshot.closeVerdict(name) == CloseVerdict.FORGET) apply(r.snapshot, listOf(sessionId))
        }
    }

    override suspend fun checkUuid(sessionId: String) {
        val serverId = registered[sessionId] ?: serverIdOf(sessionId) ?: return
        val name = tmuxNameOf(sessionId) ?: return
        var r = round(serverId, maxAgeMs = 2_000) ?: return
        if (name !in r.names) {
            // A tab that is not registered (yet): its pane was not part of the last round.
            extraNames.getOrPut(serverId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(name)
            r = round(serverId, maxAgeMs = 0) ?: return
        }
        apply(r.snapshot, listOf(sessionId), allowForget = false)
    }

    override suspend fun capability(serverId: String, tmux: String): Boolean? {
        // A name no tab asked for is added to the next rounds' pane filter; a round that predates it is not good enough.
        extraNames.getOrPut(serverId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(tmux)
        var r = round(serverId, maxAgeMs = capTtlMs) ?: return null
        if (tmux !in r.names) r = round(serverId, maxAgeMs = 0) ?: return null
        return r.snapshot.capability(tmux)
    }

    private companion object { const val TAG = "ServerReconciler" }
}
