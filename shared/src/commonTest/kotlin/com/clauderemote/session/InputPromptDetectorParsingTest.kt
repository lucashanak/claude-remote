package com.clauderemote.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression tests for [InputPromptDetector]'s regex scrapers of the
 * oh-my-claudecode (OMC) statusline and Claude Code `/usage` output — the
 * single most fragile coupling in the project. When OMC v4.15 added a
 * `[###----]` progress bar between label and number, EVERY scrape silently
 * broke: usage chips froze on stale scrollback values and working/idle
 * detection went silent. These tests pin down both the OMC statusline
 * (with and without bars) and the Claude Code `/usage` prose form, plus the
 * LAST-match-wins semantics the rolling output buffer depends on.
 *
 * Each test uses a distinct sessionId so the rolling recentOutput buffers
 * (module state inside the shared detector) can't leak between tests.
 */
class InputPromptDetectorParsingTest {

    private val detector = InputPromptDetector()

    // ---- parseContextPercent ----

    @Test
    fun ctxWithProgressBar_matches() {
        // The regression: OMC ≥4.15 renders "ctx:[####----]42%" — the bar
        // must be tolerated between the label and the percentage.
        val pct = detector.parseContextPercent("ctx-bar", "ctx:[####----]42%")
        assertEquals(42, pct)
    }

    @Test
    fun ctxWithoutProgressBar_matches() {
        val pct = detector.parseContextPercent("ctx-nobar", "ctx:6%")
        assertEquals(6, pct)
    }

    @Test
    fun ctxProsePercentBeforeWord_matches() {
        // Claude Code /usage form: "33% context"
        val pct = detector.parseContextPercent("ctx-prose-1", "Model usage: 33% context remaining? no, 33% context used.")
        assertEquals(33, pct)
    }

    @Test
    fun ctxProseColonForm_matches() {
        // Claude Code /usage form: "context: 33%"
        val pct = detector.parseContextPercent("ctx-prose-2", "context: 33%")
        assertEquals(33, pct)
    }

    @Test
    fun ctxTokenRatioForm_computesPercent() {
        // "12.5k / 200k tokens" — k-suffixed ratio, division computed then
        // truncated to an int: 12500 / 200000 * 100 = 6.25 -> 6.
        val pct = detector.parseContextPercent("ctx-ratio", "12.5k / 200k tokens")
        assertEquals(6, pct)
    }

    @Test
    fun ctxTokenRatioWithCommaSeparators_computesPercent() {
        val pct = detector.parseContextPercent("ctx-ratio-comma", "12,500 / 200,000 tokens")
        assertEquals(6, pct)
    }

    @Test
    fun ctxTokensRemaining_inferSmallWindow() {
        // "150k tokens remaining" with an inferred 200k window -> 25% used.
        val pct = detector.parseContextPercent("ctx-remaining-small", "150k tokens remaining")
        assertEquals(25, pct)
    }

    @Test
    fun ctxTokensRemaining_inferLargeWindow() {
        // Remaining > 1,000,000 falls into the "unknown window" branch, which
        // estimates conservatively as remaining * 1.5:
        // (1 - 1_200_000 / 1_800_000) * 100 = 33.33 -> 33.
        val pct = detector.parseContextPercent("ctx-remaining-large", "1.2m tokens remaining")
        assertEquals(33, pct)
    }

    @Test
    fun ctxLastMatchWins_overOlderBufferedRender() {
        // The rolling buffer holds many concatenated statusline renders; the
        // bottom-most (newest) one is the current truth.
        detector.feedRecentOutput("ctx-lastmatch", "ctx:80%")
        detector.feedRecentOutput("ctx-lastmatch", "ctx:20%")
        val pct = detector.parseContextPercent("ctx-lastmatch", "")
        assertEquals(20, pct)
    }

    @Test
    fun ctxOutOfRangeValue_clampsTo100() {
        val pct = detector.parseContextPercent("ctx-clamp", "ctx:150%")
        assertEquals(100, pct)
    }

    @Test
    fun ctxNoStatuslineInPlainProse_returnsNull() {
        val pct = detector.parseContextPercent("ctx-null", "I've made the requested change, let me know if you'd like anything else.")
        assertNull(pct)
    }

    // ---- parseUsage ----

    @Test
    fun usageOmcWithBars_parsesSessionWeekAndResets() {
        val statusline = "5h:[###-----]33%(2h59m) | wk:[##------]14%(10h52m) | thinking | " +
            "session:6464m | ctx:[####----]42%"
        val usage = detector.parseUsage("usage-bar", statusline)
        assertEquals(
            mapOf("session" to 33, "week" to 14, "session_reset_min" to 179, "week_reset_min" to 652),
            usage,
        )
    }

    @Test
    fun usageOmcWithoutBars_parsesSessionWeekAndResets() {
        val statusline = "5h:33%(2h59m) | wk:14%(6d10h) | session:6m | ctx:6%"
        val usage = detector.parseUsage("usage-nobar", statusline)
        assertEquals(
            mapOf("session" to 33, "week" to 14, "session_reset_min" to 179, "week_reset_min" to 9240),
            usage,
        )
    }

    @Test
    fun usageClaudeCodeProseForm_parsesSessionAndWeekOnly() {
        // /usage prose doesn't carry the OMC short-form reset syntax, so no
        // reset_min keys should be produced for it.
        val prose = "Current session (5h): 33% used, resets soon\n" +
            "Current week (7d): 14% used, resets later"
        val usage = detector.parseUsage("usage-prose", prose)
        assertEquals(mapOf("session" to 33, "week" to 14), usage)
    }

    @Test
    fun usageResetTime_dOnlyFieldsOptional_minutesOnly() {
        val usage = detector.parseUsage("usage-reset-m", "5h:10%(45m)")
        assertEquals(45, usage?.get("session_reset_min"))
    }

    @Test
    fun usageResetTime_hoursAndMinutes() {
        val usage = detector.parseUsage("usage-reset-hm", "5h:20%(2h27m)")
        assertEquals(147, usage?.get("session_reset_min"))
    }

    @Test
    fun usageResetTime_daysAndHours() {
        // Documented bug fix: the old pattern required a trailing "m", so
        // "5d10h" never matched and the week-reset chip stayed blank.
        val usage = detector.parseUsage("usage-reset-dh", "wk:30%(5d10h)")
        assertEquals(5 * 1440 + 10 * 60, usage?.get("week_reset_min"))
    }

    @Test
    fun usageResetTime_hoursAndMinutesForWeek() {
        val usage = detector.parseUsage("usage-reset-wk-hm", "wk:40%(10h52m)")
        assertEquals(652, usage?.get("week_reset_min"))
    }

    @Test
    fun usageSessionAndWeekResetsAreNotConfused() {
        // Each reset capture is anchored to its own "5h"/"wk" prefix so the
        // 5h window can't be mistaken for the wk window or vice versa.
        val usage = detector.parseUsage("usage-anchor", "5h:33%(2h27m) | wk:14%(10h52m)")
        assertEquals(147, usage?.get("session_reset_min"))
        assertEquals(652, usage?.get("week_reset_min"))
    }

    @Test
    fun usageLastMatchWins_overOlderBufferedRender() {
        detector.feedRecentOutput("usage-lastmatch", "5h:80%(1h0m) | wk:50%(2h0m)")
        detector.feedRecentOutput("usage-lastmatch", "5h:20%(4h0m) | wk:10%(6d5h)")
        val usage = detector.parseUsage("usage-lastmatch", "")
        assertEquals(
            mapOf("session" to 20, "week" to 10, "session_reset_min" to 240, "week_reset_min" to 6 * 1440 + 5 * 60),
            usage,
        )
    }

    @Test
    fun usageNothingMatches_returnsNull() {
        val usage = detector.parseUsage("usage-null", "just a plain log line, nothing to see here")
        assertNull(usage)
    }

    @Test
    fun usageSessionPercentAbove100_isClamped() {
        // Regression guard: parseContextPercent already .coerceIn(0, 100)s
        // every branch, but parseUsage's SESSION_USAGE_REGEX branch used to
        // store the parsed int unclamped, letting a garbled statusline render
        // with a 3-digit percentage flow straight into the UI usage chips.
        val usage = detector.parseUsage("usage-clamped-session", "5h:150%(1h0m)")
        assertEquals(100, usage?.get("session"))
    }

    @Test
    fun usageWeekPercentAbove100_isClamped() {
        // Same consistency fix as above, for the week branch.
        val usage = detector.parseUsage("usage-clamped-week", "wk:250%(1h0m)")
        assertEquals(100, usage?.get("week"))
    }

    @Test
    fun usageResetMinutesAbove100_isNotClamped() {
        // Guard against an over-eager future clamp landing on the wrong
        // field: session_reset_min/week_reset_min are durations in minutes,
        // not percentages, and must NOT be coerced to 100.
        val usage = detector.parseUsage("usage-reset-not-clamped", "5h:10%(5h0m)")
        assertEquals(300, usage?.get("session_reset_min"))
    }

    // ---- parseClaudeWorking ----

    @Test
    fun workingSegmentWithBars_nonEmptyMeansWorking() {
        val statusline = "5h:[###-----]33%(2h59m) | wk:[##------]14%(10h52m) | thinking | " +
            "session:6464m | ctx:[####----]42%"
        detector.feedRecentOutput("working-bar", statusline)
        assertEquals(true, detector.parseClaudeWorking("working-bar"))
    }

    @Test
    fun idleSegmentWithBars_emptyMeansIdle() {
        val statusline = "5h:[###-----]10%(1h0m) | wk:[##------]5%(6d0h) | session:6m | ctx:[####----]6%"
        detector.feedRecentOutput("idle-bar", statusline)
        assertEquals(false, detector.parseClaudeWorking("idle-bar"))
    }

    @Test
    fun workingSegmentWithoutBars_nonEmptyMeansWorking() {
        val statusline = "5h:33%(2h59m) | wk:14%(10h52m) | thinking | session:6464m | ctx:42%"
        detector.feedRecentOutput("working-nobar", statusline)
        assertEquals(true, detector.parseClaudeWorking("working-nobar"))
    }

    @Test
    fun idleSegmentWithoutBars_straightToSessionMeansIdle() {
        val statusline = "5h:33%(2h59m) | wk:14%(6d10h) | session:6m | ctx:6%"
        detector.feedRecentOutput("idle-nobar", statusline)
        assertEquals(false, detector.parseClaudeWorking("idle-nobar"))
    }

    @Test
    fun workingSegmentSkillOrCompactingName_isWorking() {
        // Any non-empty segment counts, not just the literal word "thinking".
        detector.feedRecentOutput("working-skill", "5h:33%(2h59m) | wk:14%(10h52m) | compacting | session:12m | ctx:5%")
        assertEquals(true, detector.parseClaudeWorking("working-skill"))
    }

    @Test
    fun workingThenIdleTransition_lastRenderWins_isIdle() {
        // Documented bug: on a working->idle transition, an older "thinking"
        // render still inside the rolling window used to re-assert WORKING.
        // The LAST (newest) render in the buffer must win.
        val working = "5h:33%(2h59m) | wk:14%(10h52m) | thinking | session:6464m | ctx:42%"
        val idle = "5h:10%(4h0m) | wk:5%(6d0h) | session:6m | ctx:6%"
        detector.feedRecentOutput("transition-to-idle", working)
        detector.feedRecentOutput("transition-to-idle", idle)
        assertEquals(false, detector.parseClaudeWorking("transition-to-idle"))
    }

    @Test
    fun idleThenWorkingTransition_lastRenderWins_isWorking() {
        val idle = "5h:10%(4h0m) | wk:5%(6d0h) | session:6m | ctx:6%"
        val working = "5h:33%(2h59m) | wk:14%(10h52m) | thinking | session:6464m | ctx:42%"
        detector.feedRecentOutput("transition-to-working", idle)
        detector.feedRecentOutput("transition-to-working", working)
        assertEquals(true, detector.parseClaudeWorking("transition-to-working"))
    }

    @Test
    fun neverFedAnyOutput_returnsNull() {
        // Can't tell (no statusline ever seen) -> null, must NOT be false.
        assertNull(detector.parseClaudeWorking("working-never-fed"))
    }

    @Test
    fun bufferedButNoStatuslinePresent_returnsNull() {
        // The buffer is non-empty, but has no OMC statusline in it at all —
        // still can't tell, so this must be null, not false.
        detector.feedRecentOutput("working-no-statusline", "Sure, here's the summary of the changes I made.")
        assertNull(detector.parseClaudeWorking("working-no-statusline"))
    }

    // ---- stripAnsi ----

    @Test
    fun stripAnsi_removesCsiColorAndCursorSequences() {
        val input = "[31mHello[0m World[?25l!"
        assertEquals("Hello World!", InputPromptDetector.stripAnsi(input))
    }

    @Test
    fun stripAnsi_removesOscTitleSequence() {
        val input = "]0;My TitleHello"
        assertEquals("Hello", InputPromptDetector.stripAnsi(input))
    }

    @Test
    fun stripAnsi_endToEnd_coloredStatuslineStillParsesAfterFeeding() {
        // feedRecentOutput strips ANSI internally before buffering; a
        // realistically-colored statusline render must still parse cleanly.
        val colored = "[2mstatus: [32m5h:[###-----]33%(2h59m) | wk:[##------]14%(10h52m) | " +
            "thinking | session:6464m | ctx:[####----]42%[0m"
        detector.feedRecentOutput("ansi-e2e", colored)
        assertEquals(42, detector.parseContextPercent("ansi-e2e", ""))
        assertEquals(mapOf("session" to 33, "week" to 14, "session_reset_min" to 179, "week_reset_min" to 652), detector.parseUsage("ansi-e2e", ""))
        assertEquals(true, detector.parseClaudeWorking("ansi-e2e"))
    }
}
