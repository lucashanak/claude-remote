package com.clauderemote.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reset-countdown formatting for the per-account usage bars.
 *
 * Pinned because the input is minutes-from-now derived from the endpoint's
 * `resets_at`, and the weekly window is measured in DAYS — an hours-only
 * formatter rendered "168 h" for a fresh weekly limit.
 */
class AccountUsageFormatTest {

    @Test
    fun minutesUnderAnHourStayMinutes() {
        assertEquals("45 min", formatResetMinutes(45))
        assertEquals("1 min", formatResetMinutes(1))
    }

    @Test
    fun hoursCarryTheirRemainderButDropAZero() {
        assertEquals("2 h 15 min", formatResetMinutes(135))
        assertEquals("3 h", formatResetMinutes(180))
    }

    @Test
    fun daysReadAsDaysNotHours() {
        assertEquals("7 d", formatResetMinutes(7 * 24 * 60))
        assertEquals("1 d 4 h", formatResetMinutes(24 * 60 + 4 * 60))
        // Minutes are dropped once we're in days — nobody needs "6 d 23 h 59 min".
        assertEquals("6 d 23 h", formatResetMinutes(7 * 24 * 60 - 1))
    }

    @Test
    fun zeroAndNegativeReadAsImminentRatherThanEmpty() {
        assertEquals("chvilku", formatResetMinutes(0))
        assertEquals("chvilku", formatResetMinutes(-5))
    }
}
