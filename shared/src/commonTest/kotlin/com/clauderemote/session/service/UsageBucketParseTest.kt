package com.clauderemote.session.service

import com.clauderemote.model.UsageBucket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parsing of Anthropic's OAuth usage payload into display buckets.
 *
 * The parser is deliberately key-agnostic, so these tests pin the BEHAVIOUR
 * (any `{utilization, resets_at}` window is picked up, ordered 5h → week →
 * model caps) rather than a fixed set of key names: the premium-model cap has
 * already been renamed once with the model lineup, and hardcoding it is how the
 * Fable numbers would silently disappear again.
 */
class UsageBucketParseTest {

    private val now = 1_760_000_000_000L

    private fun parse(json: String) = UsageService.parseUsageBuckets(json, now)

    private fun iso(minutesFromNow: Long): String {
        // Fixed offset format the endpoint uses: 2026-09-06T12:00:00+00:00.
        val ms = now + minutesFromNow * 60_000
        val secs = ms / 1000
        val days = secs / 86_400
        val rem = secs % 86_400
        // Only the round-trip through isoToEpochMillis matters here, so build a
        // date arithmetically from the epoch day rather than pulling in a
        // date library the shared module doesn't have.
        var y = 1970
        var d = days
        while (true) {
            val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
            val len = if (leap) 366 else 365
            if (d < len) break
            d -= len; y++
        }
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val lens = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var mo = 0
        while (d >= lens[mo]) { d -= lens[mo]; mo++ }
        fun p(n: Long) = n.toString().padStart(2, '0')
        return "$y-${p((mo + 1).toLong())}-${p(d + 1)}T${p(rem / 3600)}:${p((rem % 3600) / 60)}:${p(rem % 60)}+00:00"
    }

    @Test
    fun readsEveryWindowIncludingAModelCapItHasNeverSeen() {
        val json = """
            {"five_hour":{"utilization":37.4,"resets_at":"${iso(90)}"},
             "seven_day":{"utilization":61.6,"resets_at":"${iso(3000)}"},
             "seven_day_fable":{"utilization":12.0,"resets_at":"${iso(3000)}"}}
        """.trimIndent()
        val buckets = parse(json)
        assertEquals(listOf("five_hour", "seven_day", "seven_day_fable"), buckets.map { it.key })
        // Rounded, not truncated.
        assertEquals(37, buckets[0].percent)
        assertEquals(62, buckets[1].percent)
        assertEquals(90, buckets[0].resetMin)
        // Labels: the well-known pair is short, and a cap we have a name for
        // reads as that name.
        assertEquals(listOf("5h", "Week", "Fable (7d)"), buckets.map { it.label })
        assertTrue(buckets[2].isModelCap)
        assertTrue(!buckets[0].isModelCap && !buckets[1].isModelCap)
    }

    @Test
    fun modelCapsSortAfterTheKnownPairWhateverTheirOrderInJson() {
        val json = """
            {"seven_day_opus":{"utilization":5},"five_hour":{"utilization":1},"seven_day":{"utilization":2}}
        """.trimIndent()
        assertEquals(listOf("five_hour", "seven_day", "seven_day_opus"), parse(json).map { it.key })
    }

    @Test
    fun objectsWithoutUtilizationAreNotBuckets() {
        // A 429/error body must yield NOTHING — reporting it as 0% would paint
        // an empty bar and read as "you've used nothing", the opposite of the truth.
        assertEquals(emptyList(), parse("""{"error":{"type":"rate_limit_error","message":"Rate limited."}}"""))
        assertEquals(emptyList(), parse("{}"))
        assertEquals(emptyList(), parse(""))
    }

    @Test
    fun aWrapperObjectDoesNotSwallowItsChildren() {
        val json = """
            {"limits":{"five_hour":{"utilization":10,"resets_at":"${iso(60)}"},"seven_day":{"utilization":20}}}
        """.trimIndent()
        // The innermost-object bound means the two real windows are found and the
        // "limits" wrapper is not itself reported as a bucket.
        assertEquals(listOf("five_hour", "seven_day"), parse(json).map { it.key })
    }

    @Test
    fun aPastResetClampsToZeroRatherThanGoingNegative() {
        val json = """{"five_hour":{"utilization":99,"resets_at":"${iso(-120)}"}}"""
        assertEquals(0, parse(json).single().resetMin)
    }

    @Test
    fun missingResetIsNullNotZero() {
        // null = "the payload didn't say"; 0 = "resets right now". The UI shows
        // them differently, so they must not collapse.
        assertEquals(null, parse("""{"five_hour":{"utilization":50}}""").single().resetMin)
    }

    @Test
    fun utilizationIsClampedToThePercentRange() {
        val json = """{"five_hour":{"utilization":120},"seven_day":{"utilization":-3}}"""
        val b = parse(json)
        assertEquals(100, b.first { it.key == "five_hour" }.percent)
        assertEquals(0, b.first { it.key == "seven_day" }.percent)
    }

    @Test
    fun aCapWeHaveNoNameForIsStillReadable() {
        // The live payload is full of codenames (nimbus_quill, copper_kite, …),
        // so the fallback has to produce something identifiable rather than a
        // raw snake_case key or a blank row.
        assertEquals("Nimbus Quill", UsageBucket(key = "nimbus_quill", percent = 0).label)
        assertEquals("Fable", UsageBucket(key = "fable", percent = 0).label)
        assertEquals("Opus Weekly", UsageBucket(key = "opus_weekly", percent = 0).label)
    }
}
