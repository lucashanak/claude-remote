package com.clauderemote.session.service

import com.clauderemote.session.PromptType
import com.clauderemote.session.transcript.TranscriptEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the rules that decide WHICH message a completion notification carries and
 * WHICH Stop markers are real completions. Both were the source of the two
 * reported bugs — the notification showing the previous turn's answer, and the
 * completion the user was waiting for being dropped as "stale" — so each rule
 * gets a case here rather than being re-derived from the timing code.
 */
class NotifyGatingTest {

    private fun user(id: String, ts: String? = null, text: String = "ask") =
        TranscriptEntry.UserPrompt(id, ts, text)

    private fun assistant(id: String, ts: String? = null, text: String = "answer") =
        TranscriptEntry.AssistantText(id, ts, text, model = null)

    private fun toolResult(id: String) =
        TranscriptEntry.ToolResult(id, null, toolUseId = "t1", text = "output", isError = false)

    // Marker epoch used throughout: 2026-09-10T12:00:00Z.
    private val markerEpoch = 1789041600L

    // ---- StopBodySelector: which message answers THIS turn ----

    @Test
    fun `takes the last assistant message after the last user prompt`() {
        val entries = listOf(
            user("u1"), assistant("a1", text = "old answer"),
            user("u2"), assistant("a2", text = "preamble"), assistant("a3", text = "final"),
        )
        assertEquals("a3", StopBodySelector.lastAssistantAfterLastUserPrompt(entries)?.id)
    }

    @Test
    fun `rejects a backlog answer that precedes the last user prompt`() {
        // The turn has started (the prompt is logged) but Claude has not
        // answered yet — the previous turn's text must NOT be adopted.
        val entries = listOf(user("u1"), assistant("a1"), user("u2"))
        assertNull(StopBodySelector.lastAssistantAfterLastUserPrompt(entries))
        assertNull(StopBodySelector.select(entries, lastNotifiedId = null, markerEpochSec = markerEpoch))
    }

    @Test
    fun `tool round-trips inside a turn do not move the boundary`() {
        // tool_result blocks parse as ToolResult, never UserPrompt, so a turn
        // full of tool calls still resolves to the assistant text after the
        // human's prompt.
        val entries = listOf(
            user("u1"), assistant("a1", text = "let me look"),
            toolResult("r1"), toolResult("r2"), assistant("a2", text = "final"),
        )
        assertEquals("a2", StopBodySelector.select(entries, null, markerEpoch)?.id)
    }

    @Test
    fun `skips a blank assistant entry`() {
        val entries = listOf(user("u1"), assistant("a1", text = "real"), assistant("a2", text = "   "))
        assertEquals("a1", StopBodySelector.lastAssistantAfterLastUserPrompt(entries)?.id)
    }

    @Test
    fun `falls back to the last assistant message when no user prompt was parsed`() {
        val entries = listOf(assistant("a1"), assistant("a2"))
        assertEquals("a2", StopBodySelector.lastAssistantAfterLastUserPrompt(entries)?.id)
    }

    @Test
    fun `rejects the message we already notified for`() {
        val entries = listOf(user("u1"), assistant("a1"))
        assertNull(StopBodySelector.select(entries, lastNotifiedId = "a1", markerEpochSec = markerEpoch))
        assertNotNull(StopBodySelector.select(entries, lastNotifiedId = "a0", markerEpochSec = markerEpoch))
    }

    @Test
    fun `rejects a message older than one plausible turn`() {
        // An hour before the marker. With no previous marker to bound it, this
        // is the gate that stands between a cold start and the backlog.
        val entries = listOf(user("u1"), assistant("a1", ts = "2026-09-10T11:00:00.000Z"))
        assertNull(StopBodySelector.select(entries, null, markerEpoch))
    }

    @Test
    fun `accepts a message inside one plausible turn`() {
        // Ten minutes before the marker: a long tool run, not a stale body.
        val entries = listOf(user("u1"), assistant("a1", ts = "2026-09-10T11:50:00.000Z"))
        assertEquals("a1", StopBodySelector.select(entries, null, markerEpoch)?.id)
    }

    @Test
    fun `the age bound is one turn, not hours`() {
        // Pinned explicitly: this was 6 h and let a whole day of backlog past
        // the cold-start path, which is the stale-body bug.
        assertTrue(
            StopBodySelector.MAX_BODY_AGE_BEFORE_MARKER_MS <= 30L * 60 * 1000,
            "a body older than half an hour cannot be this turn's answer",
        )
    }

    // ---- The previous marker epoch, the gate that works on a cold start ----

    @Test
    fun `rejects an answer that predates the previous completion`() {
        // Cold start: nothing recorded, so the id gate is vacuous. The previous
        // turn ended at 11:59:00 and its answer was written at 11:58:30, so that
        // answer cannot be what THIS marker is about.
        val entries = listOf(user("u1"), assistant("a1", ts = "2026-09-10T11:58:30.000Z"))
        assertNull(
            StopBodySelector.select(entries, null, markerEpoch, previousMarkerEpochSec = markerEpoch - 60)
        )
    }

    @Test
    fun `accepts an answer written after the previous completion`() {
        val entries = listOf(user("u1"), assistant("a1", ts = "2026-09-10T11:59:30.000Z"))
        assertEquals(
            "a1",
            StopBodySelector.select(entries, null, markerEpoch, previousMarkerEpochSec = markerEpoch - 60)?.id,
        )
    }

    @Test
    fun `a known previous marker overrides the coarse age bound`() {
        // Two hours of tool work between the two completions: past the blanket
        // age bound, but provably part of THIS turn.
        val entries = listOf(user("u1"), assistant("a1", ts = "2026-09-10T11:00:00.000Z"))
        assertNull(StopBodySelector.select(entries, null, markerEpoch))
        assertEquals(
            "a1",
            StopBodySelector.select(entries, null, markerEpoch, previousMarkerEpochSec = markerEpoch - 7200)?.id,
        )
    }

    @Test
    fun `accepts a message timestamped after the marker`() {
        // Clock skew between phone and server routinely puts the entry ahead of
        // the marker; the bound is one-sided on purpose.
        val entries = listOf(user("u1"), assistant("a1", ts = "2026-09-10T12:00:30.000Z"))
        assertEquals("a1", StopBodySelector.select(entries, null, markerEpoch)?.id)
    }

    @Test
    fun `an unparseable or missing timestamp does not block the body`() {
        val noTs = listOf(user("u1"), assistant("a1", ts = null))
        val badTs = listOf(user("u1"), assistant("a2", ts = "not-a-date"))
        assertEquals("a1", StopBodySelector.select(noTs, null, markerEpoch)?.id)
        assertEquals("a2", StopBodySelector.select(badTs, null, markerEpoch)?.id)
    }

    @Test
    fun `a trailing tool result does not hide the answer`() {
        // The reverse scan has to walk back past tool results appended after the
        // final text, which is the normal shape when a turn ends on a tool.
        val entries = listOf(
            user("u1"), assistant("a1", text = "final"), toolResult("r1"), toolResult("r2"),
        )
        assertEquals("a1", StopBodySelector.lastAssistantAfterLastUserPrompt(entries)?.id)
        assertEquals("a1", StopBodySelector.select(entries, null, null)?.id)
    }

    // ---- What id to remember when the body poll timed out ----

    @Test
    fun `timeout records the transcript high-water mark`() {
        // Deliberately NOT boundary-filtered: after a timeout we want the newest
        // assistant id whatever turn it belongs to, so the next marker cannot
        // mistake the message we failed to send for a fresh answer.
        val entries = listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2"))
        assertEquals("a2", StopBodySelector.idToRecordOnTimeout(entries))
    }

    @Test
    fun `timeout records the backlog answer even with a turn in progress`() {
        val entries = listOf(user("u1"), assistant("a1"), user("u2"))
        assertNull(StopBodySelector.lastAssistantAfterLastUserPrompt(entries))
        assertEquals("a1", StopBodySelector.idToRecordOnTimeout(entries))
    }

    @Test
    fun `timeout records nothing when no assistant message was parsed`() {
        assertNull(StopBodySelector.idToRecordOnTimeout(listOf(user("u1"))))
        assertNull(StopBodySelector.idToRecordOnTimeout(emptyList()))
    }

    @Test
    fun `a null marker epoch skips the timestamp gate entirely`() {
        val entries = listOf(user("u1"), assistant("a1", ts = "2020-01-01T00:00:00.000Z"))
        assertEquals("a1", StopBodySelector.select(entries, null, markerEpochSec = null)?.id)
    }

    @Test
    fun `an empty transcript yields no body`() {
        assertNull(StopBodySelector.select(emptyList(), null, markerEpoch))
    }

    // ---- NotifyEpochTracker: which markers are real completions ----

    @Test
    fun `first marker for a session is a completion`() {
        val t = NotifyEpochTracker()
        assertNotNull(t.claim("s", 100L))
        assertEquals(100L, t.highestSeen("s"))
        assertEquals(100L, t.highestNotified("s"))
    }

    @Test
    fun `a replayed or equal epoch is dropped`() {
        val t = NotifyEpochTracker()
        t.claim("s", 100L)
        assertNull(t.claim("s", 100L))
        assertNull(t.claim("s", 99L))
    }

    @Test
    fun `a strictly newer marker notifies however old it looks`() {
        // The whole point of dropping the age filter: a marker buffered for an
        // hour by a frozen socket is still the completion the user waits on.
        val t = NotifyEpochTracker()
        t.claim("s", 100L)
        assertNotNull(t.claim("s", 101L))
    }

    @Test
    fun `sessions do not share epochs`() {
        val t = NotifyEpochTracker()
        t.claim("a", 500L)
        assertNotNull(t.claim("b", 100L))
    }

    @Test
    fun `rollback leaves a newer successful claim alone`() {
        // The claim runs on the tail reader while the dispatch runs on a worker,
        // so 200 can be claimed and notified while 100 is still failing. An
        // unconditional restore would un-see 200 and notify for it twice.
        val t = NotifyEpochTracker()
        val first = t.claim("s", 100L)
        val second = t.claim("s", 200L)
        assertNotNull(first)
        assertNotNull(second)
        t.rollback(first)
        assertEquals(200L, t.highestSeen("s"))
        assertNull(t.claim("s", 200L))
    }

    // ---- Priming: the first sighting must not become a notification ----

    @Test
    fun `prime records a baseline without claiming it`() {
        val t = NotifyEpochTracker()
        t.prime("s", 100L)
        assertEquals(100L, t.highestSeen("s"))
        assertNull(t.highestNotified("s"))
        // The primed marker itself is now a replay and cannot notify.
        assertNull(t.claim("s", 100L))
    }

    @Test
    fun `a marker after the primed baseline still notifies`() {
        // This is the H3 case: a real completion arriving late through a frozen
        // socket must survive priming.
        val t = NotifyEpochTracker()
        t.prime("s", 100L)
        assertNotNull(t.claim("s", 101L))
    }

    // ---- observe(): the whole per-marker decision, which is where the
    // cold-start burst actually has to be stopped ----

    private val grace = 120_000L

    @Test
    fun `a whole replayed window primes once and dispatches nothing`() {
        // The tail replays 25 lines on connect. Priming that gated on "have we
        // ever SEEN a marker" recorded the first line and then let all 11 others
        // claim and notify — one baseline followed by a burst.
        val t = NotifyEpochTracker()
        val outcomes = (1L..12L).map { t.observe("s", 1000L + it, ageMs = 3_600_000L, firstSightingGraceMs = grace) }
        assertTrue(
            outcomes.all { it is NotifyEpochTracker.Outcome.Primed },
            "every marker in a stale replay window must prime, not dispatch",
        )
        assertNull(t.highestNotified("s"))
        assertEquals(1012L, t.highestSeen("s"))
    }

    @Test
    fun `the first fresh marker after a replayed window notifies`() {
        val t = NotifyEpochTracker()
        (1L..12L).forEach { t.observe("s", 1000L + it, ageMs = 3_600_000L, firstSightingGraceMs = grace) }
        val live = t.observe("s", 2000L, ageMs = 1_000L, firstSightingGraceMs = grace)
        assertTrue(live is NotifyEpochTracker.Outcome.Claimed)
        assertEquals(2000L, t.highestNotified("s"))
    }

    @Test
    fun `an old marker after a real notification still notifies`() {
        // H3's case: a real completion flushed minutes late by a frozen socket.
        // Once the session has notified once, age stops mattering entirely.
        val t = NotifyEpochTracker()
        t.observe("s", 1000L, ageMs = 1_000L, firstSightingGraceMs = grace)
        val late = t.observe("s", 1001L, ageMs = 600_000L, firstSightingGraceMs = grace)
        assertTrue(late is NotifyEpochTracker.Outcome.Claimed)
    }

    @Test
    fun `a fresh first marker notifies without priming`() {
        val t = NotifyEpochTracker()
        val first = t.observe("s", 1000L, ageMs = 5_000L, firstSightingGraceMs = grace)
        assertTrue(first is NotifyEpochTracker.Outcome.Claimed)
    }

    @Test
    fun `observe reports a replay as a replay`() {
        val t = NotifyEpochTracker()
        t.observe("s", 1000L, ageMs = 1_000L, firstSightingGraceMs = grace)
        val again = t.observe("s", 1000L, ageMs = 1_000L, firstSightingGraceMs = grace)
        assertTrue(again is NotifyEpochTracker.Outcome.Replay)
    }

    @Test
    fun `priming is per session`() {
        val t = NotifyEpochTracker()
        t.observe("a", 1000L, ageMs = 3_600_000L, firstSightingGraceMs = grace)
        assertTrue(t.observe("b", 500L, ageMs = 1_000L, firstSightingGraceMs = grace) is NotifyEpochTracker.Outcome.Claimed)
        assertNull(t.highestNotified("a"))
    }

    @Test
    fun `prime never moves the baseline backwards`() {
        val t = NotifyEpochTracker()
        t.claim("s", 200L)
        t.prime("s", 100L)
        assertEquals(200L, t.highestSeen("s"))
    }

    @Test
    fun `an unprimed session treats any marker as new`() {
        // Pins the rule the caller relies on: the tracker itself has no notion
        // of "too old", so the first-sighting grace window in the watcher is
        // what stops a cold start buzzing for old completions.
        val t = NotifyEpochTracker()
        assertNotNull(t.claim("s", 1L))
    }

    @Test
    fun `rollback lets a failed dispatch be retried on the next replay`() {
        val t = NotifyEpochTracker()
        t.claim("s", 100L)
        val claim = t.claim("s", 200L)
        assertNotNull(claim)
        t.rollback(claim)
        assertEquals(100L, t.highestSeen("s"))
        assertNotNull(t.claim("s", 200L))
    }

    @Test
    fun `rollback of the very first claim clears the session`() {
        val t = NotifyEpochTracker()
        val claim = t.claim("s", 100L)
        assertNotNull(claim)
        t.rollback(claim)
        assertNull(t.highestSeen("s"))
        assertNotNull(t.claim("s", 100L))
    }

    @Test
    fun `clear forgets a session`() {
        val t = NotifyEpochTracker()
        t.claim("s", 100L)
        t.clear("s")
        assertNull(t.highestSeen("s"))
        assertNotNull(t.claim("s", 50L))
    }

    // ---- NotifyKeys: one key per event the user should be told about ----

    @Test
    fun `two prompts in the same turn get different dedup keys`() {
        val first = NotifyKeys.detection(PromptType.PERMISSION_PROMPT, 1L)
        val second = NotifyKeys.detection(PromptType.PERMISSION_PROMPT, 2L)
        assertTrue(first != second, "a second permission prompt must not look like a duplicate")
    }

    @Test
    fun `the same detection produces a stable key`() {
        assertEquals(
            NotifyKeys.detection(PromptType.APPROVAL_NEEDED, 7L),
            NotifyKeys.detection(PromptType.APPROVAL_NEEDED, 7L),
        )
    }

    @Test
    fun `prompt type is part of the key`() {
        assertTrue(
            NotifyKeys.detection(PromptType.APPROVAL_NEEDED, 1L) !=
                NotifyKeys.detection(PromptType.PERMISSION_PROMPT, 1L)
        )
    }

    @Test
    fun `only prompt keys escape the debounce`() {
        // A second permission prompt inside one turn must get through.
        assertTrue(NotifyKeys.isDebounceExempt(NotifyKeys.detection(PromptType.PERMISSION_PROMPT, 2L)))
        assertTrue(NotifyKeys.isDebounceExempt(NotifyKeys.detection(PromptType.APPROVAL_NEEDED, 1L)))
        // Several Stop markers in one turn are one completion to the user.
        assertTrue(!NotifyKeys.isDebounceExempt(NotifyKeys.stop("deploy", markerEpoch)))
        // A malformed marker line has no identity at all.
        assertTrue(!NotifyKeys.isDebounceExempt(null))
        assertTrue(!NotifyKeys.isDebounceExempt("something-else"))
    }

    @Test
    fun `a stop key names one completion`() {
        assertEquals("stop#deploy#1789041600", NotifyKeys.stop("deploy", markerEpoch))
        assertTrue(NotifyKeys.stop("deploy", 1L) != NotifyKeys.stop("deploy-test", 1L))
    }
}
