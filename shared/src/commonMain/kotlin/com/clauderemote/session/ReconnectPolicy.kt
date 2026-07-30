package com.clauderemote.session

/**
 * Pure backoff arithmetic for SessionOrchestrator's two reconnect curves,
 * extracted so the formulas can be unit-tested without spinning up an
 * orchestrator. This is a straight extraction — every value either curve
 * produces is unchanged from the inline arithmetic that used to live at the
 * call sites (armReconnectRetry / autoReconnect).
 *
 * The two curves are DIFFERENT ON PURPOSE and must stay that way:
 *  - background (armReconnectRetry): unbounded attempts, 2s→60s cap, attempt 1
 *    waits ~2s. This is the loop that re-arms forever after autoReconnect
 *    gives up — see the comment near SessionOrchestrator's reconnectRetryJobs
 *    field for the incident where a 3-attempt cap left a session DISCONNECTED
 *    forever.
 *  - foreground (autoReconnect): bounded attempts (maxAttempts, default 3),
 *    2s→30s cap, attempt 1 fires with (essentially) zero backoff because the
 *    user is watching and a Starlink-handover reconnect is usually instant.
 *    A separate CF-early-death floor can raise attempt 1 above zero; that
 *    floor is transport state, not a function of attempt, so it's an
 *    injected parameter here rather than baked into the curve.
 */
internal object ReconnectPolicy {

    /**
     * Background re-arm loop (armReconnectRetry) deterministic delay, ms.
     * attempt 1 → 2000, doubling each attempt, capped at 60_000. `attempt`
     * is 1-based and unbounded (the loop has no maxAttempts).
     */
    fun backgroundBaseDelayMs(attempt: Int): Long =
        (2000L shl (attempt - 1).coerceAtMost(5)).coerceAtMost(60_000L)

    /**
     * Background curve + jitter. [jitter] mirrors the original
     * `kotlin.random.Random.nextLong(500)` call (0..499ms) but is injectable
     * so tests can pin it to 0 or to its max.
     */
    fun backgroundDelayMs(attempt: Int, jitter: (Long) -> Long = { kotlin.random.Random.nextLong(it) }): Long =
        backgroundBaseDelayMs(attempt) + jitter(500)

    /**
     * Foreground auto-reconnect (autoReconnect) deterministic delay, ms,
     * WITHOUT the CF-early-death floor (see [foregroundDelayMs]). attempt 1
     * → 0 (fires immediately); from attempt 2, 2000 doubling, capped at
     * 30_000.
     */
    fun foregroundBaseDelayMs(attempt: Int): Long =
        if (attempt == 1) 0L
        else (2000L shl (attempt - 2).coerceAtMost(5)).coerceAtMost(30_000L)

    /**
     * Foreground curve + floor + jitter, matching autoReconnect's
     * `maxOf(base, floor) + jitter` exactly. [floor] is
     * TransportResolver.cfEarlyDeathBackoffMs(session.server) at the call
     * site — live transport state, not a pure function of [attempt], so it's
     * passed in rather than computed here. [jitter] mirrors the original
     * `kotlin.random.Random.nextLong(500)` call (0..499ms).
     */
    fun foregroundDelayMs(
        attempt: Int,
        floor: Long = 0L,
        jitter: (Long) -> Long = { kotlin.random.Random.nextLong(it) },
    ): Long = maxOf(foregroundBaseDelayMs(attempt), floor) + jitter(500)
}
