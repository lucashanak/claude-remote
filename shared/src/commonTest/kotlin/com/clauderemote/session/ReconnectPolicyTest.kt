package com.clauderemote.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ReconnectPolicy] — the two reconnect-backoff curves extracted
 * from SessionOrchestrator's armReconnectRetry (background re-arm loop) and
 * autoReconnect (foreground auto-reconnect). This is a pure refactor: every
 * value asserted here is the same one the old inline arithmetic produced —
 * see the call sites in SessionOrchestrator.kt for the byte-identical
 * `ReconnectPolicy.xxxDelayMs(...)` replacements.
 */
class ReconnectPolicyTest {

    // --- background curve: exact values, hand-computed from
    // min(2000 << min(attempt-1, 5), 60_000) --- not re-deriving the formula,
    // these are the concrete numbers it must produce.

    @Test
    fun backgroundBaseDelay_exactValuesForAttempts1to10() {
        val expected = mapOf(
            1 to 2_000L,
            2 to 4_000L,
            3 to 8_000L,
            4 to 16_000L,
            5 to 32_000L,
            6 to 60_000L,  // 64_000 would be next but the 60s cap kicks in
            7 to 60_000L,
            8 to 60_000L,
            9 to 60_000L,
            10 to 60_000L,
        )
        expected.forEach { (attempt, ms) ->
            assertEquals(ms, ReconnectPolicy.backgroundBaseDelayMs(attempt), "attempt $attempt")
        }
    }

    // --- foreground curve: exact values, hand-computed from
    // attempt==1 -> 0, else min(2000 << min(attempt-2, 5), 30_000) ---

    @Test
    fun foregroundBaseDelay_exactValuesForAttempts1to10() {
        val expected = mapOf(
            1 to 0L,       // fires immediately — see attempt-1-semantics test below
            2 to 2_000L,
            3 to 4_000L,
            4 to 8_000L,
            5 to 16_000L,
            6 to 30_000L,  // 32_000 would be next but the 30s cap kicks in
            7 to 30_000L,
            8 to 30_000L,
            9 to 30_000L,
            10 to 30_000L,
        )
        expected.forEach { (attempt, ms) ->
            assertEquals(ms, ReconnectPolicy.foregroundBaseDelayMs(attempt), "attempt $attempt")
        }
    }

    // --- caps hold for large attempt numbers, not just attempt 10 ---

    @Test
    fun backgroundBaseDelay_staysAt60sCapForLargeAttempts() {
        for (attempt in listOf(11, 32, 64, 1000)) {
            assertEquals(60_000L, ReconnectPolicy.backgroundBaseDelayMs(attempt), "attempt $attempt")
        }
    }

    @Test
    fun foregroundBaseDelay_staysAt30sCapForLargeAttempts() {
        for (attempt in listOf(11, 32, 64, 1000)) {
            assertEquals(30_000L, ReconnectPolicy.foregroundBaseDelayMs(attempt), "attempt $attempt")
        }
    }

    // --- monotonic non-decreasing: a curve that dips would retry FASTER under
    // sustained failure, which is exactly the loop-hammering failure mode
    // (the incident where a tight reconnect loop's RSTs killed the Tailscale
    // gateway) that these curves exist to prevent. ---

    @Test
    fun backgroundBaseDelay_isMonotonicNonDecreasing() {
        for (attempt in 1..999) {
            assertTrue(
                ReconnectPolicy.backgroundBaseDelayMs(attempt + 1) >= ReconnectPolicy.backgroundBaseDelayMs(attempt),
                "attempt ${attempt + 1} must not be faster than attempt $attempt",
            )
        }
    }

    @Test
    fun foregroundBaseDelay_isMonotonicNonDecreasing() {
        for (attempt in 1..999) {
            assertTrue(
                ReconnectPolicy.foregroundBaseDelayMs(attempt + 1) >= ReconnectPolicy.foregroundBaseDelayMs(attempt),
                "attempt ${attempt + 1} must not be faster than attempt $attempt",
            )
        }
    }

    // --- no overflow / no negative at absurd attempt numbers. `2000L shl n`
    // overflows Long and can go NEGATIVE for large n (a negative delay would
    // `delay()` instantly, i.e. exactly the tight-loop failure mode) — the
    // `coerceAtMost(5)` on the SHIFT AMOUNT (not the result) is what prevents
    // this, by never shifting further than 2000 << 5 = 64_000 regardless of
    // how large `attempt` is. ---

    @Test
    fun bothCurves_neverOverflowOrGoNegative_atAbsurdAttemptNumbers() {
        for (attempt in listOf(1, 6, 7, 32, 64, 1000)) {
            assertTrue(ReconnectPolicy.backgroundBaseDelayMs(attempt) > 0, "background attempt $attempt")
            assertTrue(ReconnectPolicy.foregroundBaseDelayMs(attempt) >= 0, "foreground attempt $attempt") // attempt 1 floor is 0, not >0
        }
    }

    // --- attempt-1 semantics deliberately differ between the curves ---

    @Test
    fun attempt1_backgroundWaitsTwoSeconds_foregroundWaitsZero() {
        // Background (armReconnectRetry) has no "user is watching" signal — it's
        // the persistent loop that re-arms after autoReconnect gives up, so its
        // very first wait is already a real 2s backoff, not instant.
        assertEquals(2_000L, ReconnectPolicy.backgroundBaseDelayMs(1))
        // Foreground (autoReconnect) attempt 1 fires with (almost) no backoff:
        // the user is actively looking at a frozen pane, and the dominant cause
        // (Starlink handover IP change) reconnects instantly on the new path —
        // waiting here would turn a sub-second blip into a visible stall for no
        // reason. (A separate CF-early-death floor can raise this above zero;
        // that's exercised in the floor/jitter tests below, not here.)
        assertEquals(0L, ReconnectPolicy.foregroundBaseDelayMs(1))
    }

    // --- jitter bounds ---

    @Test
    fun jitterPinnedToZero_resultEqualsBase() {
        for (attempt in 1..8) {
            assertEquals(
                ReconnectPolicy.backgroundBaseDelayMs(attempt),
                ReconnectPolicy.backgroundDelayMs(attempt, jitter = { 0L }),
            )
            assertEquals(
                ReconnectPolicy.foregroundBaseDelayMs(attempt),
                ReconnectPolicy.foregroundDelayMs(attempt, floor = 0L, jitter = { 0L }),
            )
        }
    }

    @Test
    fun jitterAtMaximum_resultEqualsBasePlus499() {
        // The real jitter source is `Random.nextLong(500)`, whose upper bound
        // (500) is EXCLUSIVE — the largest value it can ever produce is 499,
        // not 500. Model "maximum jitter" as `n - 1` for the injected `n`.
        for (attempt in 1..8) {
            assertEquals(
                ReconnectPolicy.backgroundBaseDelayMs(attempt) + 499L,
                ReconnectPolicy.backgroundDelayMs(attempt, jitter = { it - 1 }),
            )
            assertEquals(
                ReconnectPolicy.foregroundBaseDelayMs(attempt) + 499L,
                ReconnectPolicy.foregroundDelayMs(attempt, floor = 0L, jitter = { it - 1 }),
            )
        }
    }

    @Test
    fun jitter_neverMakesALaterAttemptWaitLessThanAnEarlierAttemptsBase() {
        // Even with the WORST-CASE jitter draw (0) on the later attempt and the
        // BEST-CASE draw (max, 499) on the earlier one, the later attempt must
        // still wait at least as long as the earlier attempt's plain base —
        // guaranteed here by the bases being non-decreasing (see the monotonic
        // tests above) plus jitter never being negative.
        for (attempt in 1..20) {
            val laterWithNoJitter = ReconnectPolicy.backgroundDelayMs(attempt + 1, jitter = { 0L })
            val earlierBase = ReconnectPolicy.backgroundBaseDelayMs(attempt)
            assertTrue(laterWithNoJitter >= earlierBase, "attempt ${attempt + 1} vs attempt $attempt base")

            val laterFgWithNoJitter = ReconnectPolicy.foregroundDelayMs(attempt + 1, floor = 0L, jitter = { 0L })
            val earlierFgBase = ReconnectPolicy.foregroundBaseDelayMs(attempt)
            assertTrue(laterFgWithNoJitter >= earlierFgBase, "fg attempt ${attempt + 1} vs attempt $attempt base")
        }
    }

    // --- the CF-early-death floor (foreground curve only) ---

    @Test
    fun foregroundFloor_raisesAttempt1AboveZeroWhenCfIsFlapping() {
        // Once CF is provably flapping, transportResolver.cfEarlyDeathBackoffMs
        // returns a positive floor that even attempt 1's zero base must respect —
        // that's the whole point of `maxOf(base, floor)` at the call site.
        assertEquals(5_000L, ReconnectPolicy.foregroundDelayMs(1, floor = 5_000L, jitter = { 0L }))
        // But it never LOWERS a later attempt's already-larger base.
        assertEquals(8_000L, ReconnectPolicy.foregroundDelayMs(4, floor = 5_000L, jitter = { 0L }))
    }
}
