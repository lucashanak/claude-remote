package com.clauderemote.session.service

import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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
