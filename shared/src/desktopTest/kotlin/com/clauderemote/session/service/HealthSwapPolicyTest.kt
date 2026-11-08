package com.clauderemote.session.service

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * C1 kolo 2 (R9, adv 3): the spacing between two replacements of the exec transport for channel health — 30 s, growing
 * 30 -> 60 -> 120 -> 240 -> 300 s while the replacements bring no confirmed open, back to 30 s once one does.
 */
class HealthSwapPolicyTest {
    @Test fun theSpacingStartsAtThirtySecondsAndIsCappedAtFiveMinutes() {
        assertEquals(30_000L, ExecTransportPolicy.HEALTH_SWAP_SPACING_MS)
        assertEquals(300_000L, ExecTransportPolicy.HEALTH_SWAP_SPACING_MAX_MS)
        assertEquals(60_000L, ExecTransportPolicy.SHELL_CAP_HOLD_MS)
    }

    @Test fun theSpacingGrowsWhileReplacementsDoNotHelpAndStartsOverWhenOneDid() {
        var spacing = ExecTransportPolicy.HEALTH_SWAP_SPACING_MS
        val seen = mutableListOf<Long>()
        repeat(5) {
            spacing = ExecTransportPolicy.nextHealthSwapSpacingMs(spacing, confirmedSinceLastSwap = false)
            seen += spacing
        }
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 300_000L, 300_000L), seen)
        assertEquals(30_000L, ExecTransportPolicy.nextHealthSwapSpacingMs(spacing, confirmedSinceLastSwap = true))
    }
}
