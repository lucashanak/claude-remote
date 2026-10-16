package com.clauderemote.ui

import com.clauderemote.session.transcript.TranscriptEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chat-render fixes: what the Chat view decides to draw, in the cases the
 * transcript corpus showed it drawing wrongly. Compose UI tests do not exist in
 * this project, so every test here targets the pure decision — the filter, the
 * render list, the strings a row falls back to — rather than the pixels.
 */
class TranscriptRenderFixesTest {

    private fun prompt(id: String, ts: String? = null) = TranscriptEntry.UserPrompt(id, ts, "prompt $id")
    private fun atext(id: String, ts: String? = null, isError: Boolean = false) =
        TranscriptEntry.AssistantText(id, ts, "text $id", model = null, isError = isError)
    private fun tool(id: String, name: String = "Bash") =
        TranscriptEntry.ToolCall(id, null, toolUseId = id, name = name, inputSummary = "sum", fullInput = "{}")
    private fun sysNote(id: String, subtype: String, text: String = "note $id") =
        TranscriptEntry.SystemNote(id, null, subtype, text)

    // --- R1: conversation summaries are content, not plumbing ------------

    @Test
    fun summaryAndCompactSummaryNotesShowEvenWithTheSystemToggleOff() {
        // The post-compaction summary IS the earlier half of the conversation;
        // hiding it behind a toggle that defaults to off hid that half.
        assertTrue(shouldShowEntry(sysNote("s1", "summary"), showSystem = false, showThinking = false, pairedResultIds = emptySet()))
        assertTrue(shouldShowEntry(sysNote("s2", "compact_summary"), showSystem = false, showThinking = false, pairedResultIds = emptySet()))
    }

    @Test
    fun plumbingSystemNotesStayHiddenUntilTheToggleIsOn() {
        val note = sysNote("s3", "turn_duration")
        assertFalse(shouldShowEntry(note, showSystem = false, showThinking = false, pairedResultIds = emptySet()))
        assertTrue(shouldShowEntry(note, showSystem = true, showThinking = false, pairedResultIds = emptySet()))
    }

    @Test
    fun theOtherFilterRulesAreUnchanged() {
        val thinking = TranscriptEntry.AssistantThinking("th1", null, "…")
        assertFalse(shouldShowEntry(thinking, showSystem = true, showThinking = false, pairedResultIds = emptySet()))
        assertTrue(shouldShowEntry(thinking, showSystem = false, showThinking = true, pairedResultIds = emptySet()))

        val paired = TranscriptEntry.ToolResult("r1", null, toolUseId = "t1", text = "ok", isError = false)
        assertFalse(shouldShowEntry(paired, showSystem = false, showThinking = false, pairedResultIds = setOf("r1")))
        assertTrue(shouldShowEntry(paired, showSystem = false, showThinking = false, pairedResultIds = emptySet()))
        assertTrue(shouldShowEntry(prompt("p1"), showSystem = false, showThinking = false, pairedResultIds = emptySet()))
    }

    @Test
    fun aLongSystemNoteIsPreviewedWithTheHiddenCharactersCounted() {
        // 500 chars shown, the rest counted so the row can offer the expand.
        val long = "x".repeat(1200)
        val (collapsed, hidden) = systemNoteBody(long, expanded = false)
        assertEquals(500, collapsed.length)
        assertEquals(700, hidden)

        val (full, stillHidden) = systemNoteBody(long, expanded = true)
        assertEquals(long, full)
        assertEquals(700, stillHidden)
    }

    @Test
    fun aShortSystemNoteIsNeverCutAndOffersNoExpand() {
        val short = "Session resumed."
        assertEquals(short to 0, systemNoteBody(short, expanded = false))
        assertEquals("" to 0, systemNoteBody("", expanded = false))
    }

    // --- R4: an API error is not an answer -------------------------------

    @Test
    fun anApiErrorTextIsNotTheTurnsFinalAnswer() {
        // Rate-limit / transport failures are logged in the assistant's voice;
        // framing one as the answer dresses a failure up as a conclusion.
        val body = listOf(tool("t1"), atext("err1", isError = true))
        assertEquals(-1, finalAnswerIndex(body))
    }

    @Test
    fun anApiErrorAfterARealAnswerStillSuppressesTheFrame() {
        // The turn ended in the error, so nothing in it is the final answer.
        val body = listOf(atext("a1"), atext("err1", isError = true))
        assertEquals(-1, finalAnswerIndex(body))
    }

    @Test
    fun anOrdinaryTrailingAnswerIsStillTheFinalAnswer() {
        assertEquals(1, finalAnswerIndex(listOf(tool("t1"), atext("a1"))))
        // Trailing system notes still do not hide it.
        assertEquals(1, finalAnswerIndex(listOf(tool("t1"), atext("a1"), sysNote("n1", "turn_duration"))))
    }

    @Test
    fun buildRenderListNeverMarksAnApiErrorAsTheFinalAnswer() {
        val p1 = prompt("p1")
        val err = atext("err1", isError = true)
        val p2 = prompt("p2")
        val result = buildRenderList(listOf(p1, err, p2), emptyMap(), liveTurnDone = true) { false }
        assertTrue(result.filterIsInstance<RenderItem.Single>().none { it.isFinalAnswer })
    }

    // --- R5: say so when the chat starts mid-conversation -----------------

    @Test
    fun aTruncatedBacklogAddsOneMarkerAtTheVeryTop() {
        val p1 = prompt("p1")
        val a1 = atext("a1")
        val result = buildRenderList(listOf(p1, a1), emptyMap(), liveTurnDone = true, historyTruncated = true) { false }
        val head = result.first()
        assertTrue(head is RenderItem.TimeGap && head.key == HISTORY_TRUNCATED_KEY)
        assertEquals(1, result.count { it is RenderItem.TimeGap && it.key == HISTORY_TRUNCATED_KEY })
        // and the conversation itself is untouched below it
        assertEquals(listOf(RenderItem.Single(p1), RenderItem.Single(a1, isFinalAnswer = true)), result.drop(1))
    }

    @Test
    fun aTruncatedBacklogWithNoTurnAnchorsStillGetsTheMarker() {
        // The anchor-less path (pure tool activity / a summary-only prefix) is
        // a separate early return and had to learn about the marker too.
        val t1 = tool("t1")
        val result = buildRenderList(listOf(t1), emptyMap(), liveTurnDone = true, historyTruncated = true) { false }
        assertEquals(listOf(RenderItem.TimeGap(HISTORY_TRUNCATED_KEY, "earlier messages not loaded"), RenderItem.Single(t1)), result)
    }

    @Test
    fun anUntruncatedBacklogDrawsNoMarker() {
        val result = buildRenderList(listOf(prompt("p1")), emptyMap(), liveTurnDone = true, historyTruncated = false) { false }
        assertTrue(result.none { it is RenderItem.TimeGap })
    }

    @Test
    fun theTruncationMarkerKeyIsUniqueInTheRenderList() {
        // A duplicate LazyColumn key is a hard crash, not a glitch.
        val entries = listOf(prompt("p1"), tool("t1"), tool("t2"), atext("a1"), prompt("p2"))
        val result = buildRenderList(entries, computeTurnMeta(entries), liveTurnDone = true, historyTruncated = true) { true }
        val keys = result.map { itemKey(it) }
        assertEquals(keys.size, keys.toSet().size)
    }

    // --- R6: a resumed turn jumps backwards in time -----------------------

    @Test
    fun aBackwardsJumpOfDaysDrawsAResumedDivider() {
        // /resume re-emits an old turn after a new one; without a divider the
        // month-old message just sits at the bottom as if it were the latest.
        val recent = atext("a1", ts = "2026-06-07T10:00:00.000Z")
        val old = atext("a2", ts = "2026-05-09T09:15:00.000Z")
        val result = buildRenderList(listOf(recent, old), emptyMap(), liveTurnDone = true) { false }
        assertEquals(
            listOf(
                RenderItem.Single(recent),
                RenderItem.TimeGap(key = "gap:s:a2", label = "resumed from 2026-05-09"),
                RenderItem.Single(old),
            ),
            result
        )
    }

    @Test
    fun subSecondClockJitterDrawsNothingEvenAcrossAMinuteBoundary() {
        // 296 of the 304 observed inversions are under a second — two records
        // written in the same moment. A minute-resolution comparison called the
        // boundary-straddling ones a full minute backwards; seconds do not.
        val a1 = atext("a1", ts = "2026-06-07T10:01:00.050Z")
        val a2 = atext("a2", ts = "2026-06-07T10:00:59.950Z")
        val result = buildRenderList(listOf(a1, a2), emptyMap(), liveTurnDone = true) { false }
        assertEquals(listOf(RenderItem.Single(a1), RenderItem.Single(a2)), result)
    }

    @Test
    fun aBackwardsJumpJustUnderTheThresholdDrawsNothing() {
        val a1 = atext("a1", ts = "2026-06-07T10:01:00.000Z")
        val a2 = atext("a2", ts = "2026-06-07T10:00:01.000Z") // 59 s back
        assertTrue(buildRenderList(listOf(a1, a2), emptyMap(), liveTurnDone = true) { false }
            .none { it is RenderItem.TimeGap })
    }

    @Test
    fun aBackwardsJumpAtExactlyTheThresholdDrawsTheDivider() {
        val a1 = atext("a1", ts = "2026-06-07T10:01:00.000Z")
        val a2 = atext("a2", ts = "2026-06-07T10:00:00.000Z") // exactly 60 s back
        val gaps = buildRenderList(listOf(a1, a2), emptyMap(), liveTurnDone = true) { false }
            .filterIsInstance<RenderItem.TimeGap>()
        assertEquals(listOf("resumed from 2026-06-07"), gaps.map { it.label })
    }

    @Test
    fun forwardGapsAreUnaffectedByTheBackwardsRule() {
        val a1 = atext("a1", ts = "2026-06-07T10:00:00.000Z")
        val a2 = atext("a2", ts = "2026-06-07T10:30:00.000Z")
        val gaps = buildRenderList(listOf(a1, a2), emptyMap(), liveTurnDone = true) { false }
            .filterIsInstance<RenderItem.TimeGap>()
        assertEquals(listOf(formatTimestamp(a2.timestamp!!).take(5)), gaps.map { it.label })
    }

    // --- R7: a tool row with nothing to summarise -------------------------

    @Test
    fun aToolRowWithABlankSummaryFallsBackToNoSummaryRatherThanAnEmptyColumn() {
        assertEquals(null, toolSummaryOrNull(""))
        assertEquals(null, toolSummaryOrNull("   "))
        assertEquals("rg foo", toolSummaryOrNull("  rg foo  "))
    }

    @Test
    fun aToolNameIsNeverDrawnBlank() {
        assertEquals("Bash", toolDisplayName("Bash"))
        assertEquals("Bash", toolDisplayName("  Bash "))
        assertEquals("tool", toolDisplayName(""))
        assertEquals("tool", toolDisplayName("   "))
    }
}
