package com.clauderemote.ui

import com.clauderemote.session.transcript.TranscriptEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the pure transcript -> chat-view transform in
 * [TranscriptRenderModel.kt]: [computeTurnMeta], [buildRenderList], [itemKey]
 * and [mapEntryToTurn]. These back the LazyColumn the Chat view draws, so the
 * highest-value properties are: turn grouping matches the documented rules,
 * and [itemKey] is unique + stable (a duplicate key corrupts rendering, an
 * unstable one destroys scroll position).
 *
 * Inputs are constructed directly as [TranscriptEntry] values rather than by
 * round-tripping through `TranscriptParser.parseLines`: every entry type here
 * is a plain data class with the exact fields this transform reads (id,
 * timestamp, durationMs, tool name), so direct construction gives precise
 * control over those fields without JSON-escaping noise that would test the
 * parser, not this transform.
 */
class TranscriptRenderModelTest {

    private fun prompt(id: String, ts: String? = null) = TranscriptEntry.UserPrompt(id, ts, "prompt $id")
    private fun slash(id: String, ts: String? = null) = TranscriptEntry.SlashCommand(id, ts, "cmd", "")
    private fun atext(id: String, ts: String? = null) = TranscriptEntry.AssistantText(id, ts, "text $id", model = null)
    private fun athinking(id: String, ts: String? = null) = TranscriptEntry.AssistantThinking(id, ts, "thinking $id")
    private fun tool(id: String, name: String = "Bash", ts: String? = null) =
        TranscriptEntry.ToolCall(id, ts, toolUseId = id, name = name, inputSummary = "sum", fullInput = "{}")
    private fun sysNote(id: String, subtype: String = "info", durationMs: Long? = null, ts: String? = null) =
        TranscriptEntry.SystemNote(id, ts, subtype, "note $id", durationMs)

    // --- computeTurnMeta ---

    @Test
    fun computeTurnMeta_excludesOnlyTrailingFinalAnswerFromStepCount() {
        // 2 tools + 1 thinking block count as steps; the trailing AssistantText
        // (the turn's answer) is not a step.
        val entries = listOf(prompt("p1"), tool("t1"), tool("t2"), athinking("th1"), atext("final1"))
        val meta = computeTurnMeta(entries)
        assertEquals(TurnMeta(steps = 3, durationMs = null), meta["p1"])
    }

    @Test
    fun computeTurnMeta_onlyLastOfConsecutiveAssistantTextsExcluded() {
        // Two AssistantText entries in a row: only the LAST is excluded as "the
        // final answer" — an intermediate text still counts as a step.
        val entries = listOf(prompt("p1"), atext("mid1"), atext("final1"))
        val meta = computeTurnMeta(entries)
        assertEquals(TurnMeta(steps = 1, durationMs = null), meta["p1"])
    }

    @Test
    fun computeTurnMeta_sumsDurationAcrossMultipleSystemNotesInSameTurn() {
        // Hook-driven loops can stop a turn more than once, emitting more than
        // one turn_duration note per user prompt — durations are summed.
        val entries = listOf(
            prompt("p1"),
            tool("t1"),
            sysNote("d1", subtype = "turn_duration", durationMs = 1000L),
            sysNote("d2", subtype = "turn_duration", durationMs = 2000L),
        )
        val meta = computeTurnMeta(entries)
        assertEquals(TurnMeta(steps = 1, durationMs = 3000L), meta["p1"])
    }

    @Test
    fun computeTurnMeta_multiTurnEachKeyedByOwnPromptIdWithOwnDuration() {
        // A slash-command anchor resets the turn just like a user prompt, and
        // each turn accrues its own step count / duration independently.
        val entries = listOf(
            prompt("p1"),
            tool("t1"),
            sysNote("d1", subtype = "turn_duration", durationMs = 500L),
            slash("p2"),
            sysNote("d2", subtype = "turn_duration", durationMs = 700L),
        )
        val meta = computeTurnMeta(entries)
        assertEquals(2, meta.size)
        assertEquals(TurnMeta(steps = 1, durationMs = 500L), meta["p1"])
        assertEquals(TurnMeta(steps = 0, durationMs = 700L), meta["p2"])
    }

    @Test
    fun computeTurnMeta_entriesBeforeFirstPromptAreIgnored() {
        // A tool call preceding the first anchor (e.g. a pre-compaction prefix)
        // belongs to no turn and must not leak into meta.
        val entries = listOf(tool("t0"), prompt("p1"))
        val meta = computeTurnMeta(entries)
        assertEquals(mapOf("p1" to TurnMeta(steps = 0, durationMs = null)), meta)
    }

    @Test
    fun computeTurnMeta_emptyAndSingleEntryInputsAreSane() {
        assertEquals(emptyMap(), computeTurnMeta(emptyList()))
        assertEquals(
            mapOf("p1" to TurnMeta(steps = 0, durationMs = null)),
            computeTurnMeta(listOf(prompt("p1")))
        )
    }

    // --- mapEntryToTurn ---

    @Test
    fun mapEntryToTurn_mapsNonAnchorEntriesToOwningTurnAcrossMultipleTurns() {
        // user prompt -> assistant text -> tool call -> stop marker, then a
        // second turn anchored on a slash command. Anchors are never keys.
        val entries = listOf(
            prompt("p1"), atext("a1"), tool("t1"), sysNote("stop1", subtype = "stop_hook_summary"),
            slash("p2"), tool("t2"),
        )
        val turns = mapEntryToTurn(entries)
        assertEquals(
            mapOf("a1" to "p1", "t1" to "p1", "stop1" to "p1", "t2" to "p2"),
            turns
        )
    }

    @Test
    fun mapEntryToTurn_emptyInputReturnsEmptyMap() {
        assertEquals(emptyMap(), mapEntryToTurn(emptyList()))
    }

    // --- buildRenderList: tool grouping (no anchors) ---

    @Test
    fun buildRenderList_noAnchors_consecutiveToolsCollapseAndSingleNonGroupedToolsStayUngrouped() {
        // No user-prompt/slash-command anchors (pure tool activity, or a
        // pre-compaction prefix): a run of 2+ ToolCalls collapses into one
        // ToolGroup, a non-tool entry splits a run, and a lone trailing tool
        // (run length 1) stays a Single.
        val t1 = tool("t1"); val t2 = tool("t2"); val t3 = tool("t3")
        val a1 = atext("a1")
        val t4 = tool("t4")
        val entries = listOf(t1, t2, t3, a1, t4)
        val result = buildRenderList(entries, emptyMap(), liveTurnDone = true) { false }
        assertEquals(
            listOf(
                RenderItem.ToolGroup(listOf(t1, t2, t3)),
                RenderItem.Single(a1),
                RenderItem.Single(t4),
            ),
            result
        )
    }

    @Test
    fun buildRenderList_noAnchors_standaloneToolsNeverCollapseEvenConsecutively() {
        // AskUserQuestion/TodoWrite/Task/Agent always render as their own
        // prominent card — never folded into a ToolGroup even when adjacent.
        val q1 = tool("q1", name = "AskUserQuestion")
        val q2 = tool("q2", name = "AskUserQuestion")
        val result = buildRenderList(listOf(q1, q2), emptyMap(), liveTurnDone = true) { false }
        assertEquals(listOf(RenderItem.Single(q1), RenderItem.Single(q2)), result)
    }

    @Test
    fun buildRenderList_noAnchors_standaloneToolSplitsSurroundingRunsIntoSeparateGroups() {
        val t1 = tool("t1"); val t2 = tool("t2")
        val q1 = tool("q1", name = "AskUserQuestion")
        val t3 = tool("t3"); val t4 = tool("t4")
        val entries = listOf(t1, t2, q1, t3, t4)
        val result = buildRenderList(entries, emptyMap(), liveTurnDone = true) { false }
        assertEquals(
            listOf(
                RenderItem.ToolGroup(listOf(t1, t2)),
                RenderItem.Single(q1),
                RenderItem.ToolGroup(listOf(t3, t4)),
            ),
            result
        )
    }

    // --- buildRenderList: turn structure ---

    @Test
    fun buildRenderList_middleTurnCollapsesToTurnStepsWhenNotExpanded() {
        val p1 = prompt("p1")
        val t1 = tool("t1"); val t2 = tool("t2")
        val final1 = atext("final1")
        val p2 = prompt("p2") // live (last) turn anchor
        val t3 = tool("t3")
        val entries = listOf(p1, t1, t2, final1, p2, t3)
        val meta = computeTurnMeta(entries)
        val result = buildRenderList(entries, meta, liveTurnDone = false) { false }
        assertEquals(
            listOf(
                RenderItem.Single(p1),
                RenderItem.TurnSteps(turnKey = "p1", steps = 2, durationMs = null, expanded = false),
                RenderItem.Single(final1, isFinalAnswer = true),
                RenderItem.Single(p2),
                RenderItem.Single(t3),
            ),
            result
        )
    }

    @Test
    fun buildRenderList_middleTurnExpandedRevealsGroupedToolsBeforeFinalAnswer() {
        val p1 = prompt("p1")
        val t1 = tool("t1"); val t2 = tool("t2")
        val final1 = atext("final1")
        val p2 = prompt("p2")
        val entries = listOf(p1, t1, t2, final1, p2)
        val meta = computeTurnMeta(entries)
        val result = buildRenderList(entries, meta, liveTurnDone = false) { true }
        assertEquals(
            listOf(
                RenderItem.Single(p1),
                RenderItem.TurnSteps(turnKey = "p1", steps = 2, durationMs = null, expanded = true),
                RenderItem.ToolGroup(listOf(t1, t2)),
                RenderItem.Single(final1, isFinalAnswer = true),
                RenderItem.Single(p2),
            ),
            result
        )
    }

    @Test
    fun buildRenderList_turnEndingInToolCallHasNoFinalAnswerExtracted() {
        // finalAnswerIndex requires the turn's last non-system entry to be
        // AssistantText; ending in a tool call means there is no final answer.
        val p1 = prompt("p1")
        val mid1 = atext("mid1")
        val t1 = tool("t1")
        val p2 = prompt("p2")
        val entries = listOf(p1, mid1, t1, p2)
        val meta = computeTurnMeta(entries)
        val result = buildRenderList(entries, meta, liveTurnDone = false) { false }
        assertEquals(
            listOf(
                RenderItem.Single(p1),
                RenderItem.TurnSteps(turnKey = "p1", steps = 2, durationMs = null, expanded = false),
                RenderItem.Single(p2),
            ),
            result
        )
    }

    @Test
    fun buildRenderList_trailingSystemNotesAfterFinalAnswerAreAppendedAfterIt() {
        val p1 = prompt("p1")
        val t1 = tool("t1")
        val final1 = atext("final1")
        val note = sysNote("note1", subtype = "stop_hook_summary")
        val p2 = prompt("p2")
        val entries = listOf(p1, t1, final1, note, p2)
        val meta = computeTurnMeta(entries)
        val result = buildRenderList(entries, meta, liveTurnDone = false) { false }
        assertEquals(
            listOf(
                RenderItem.Single(p1),
                RenderItem.TurnSteps(turnKey = "p1", steps = 1, durationMs = null, expanded = false),
                RenderItem.Single(final1, isFinalAnswer = true),
                RenderItem.Single(note),
                RenderItem.Single(p2),
            ),
            result
        )
    }

    @Test
    fun buildRenderList_liveTurnNeverCollapsesRegardlessOfExpandedFlag() {
        // The live (last) turn always renders in full — isExpanded is irrelevant.
        val p1 = prompt("p1")
        val t1 = tool("t1"); val t2 = tool("t2")
        val result = buildRenderList(listOf(p1, t1, t2), emptyMap(), liveTurnDone = false) { false }
        assertEquals(listOf(RenderItem.Single(p1), RenderItem.ToolGroup(listOf(t1, t2))), result)
    }

    @Test
    fun buildRenderList_liveTurnFramesTrailingAnswerOnlyOnceLiveTurnDone() {
        // isFinalAnswer must not flicker on while streaming (Claude may still
        // add tools after a text block) — the frame appears only once the
        // stop marker has been seen (liveTurnDone).
        val p1 = prompt("p1")
        val t1 = tool("t1")
        val final1 = atext("final1")
        val entries = listOf(p1, t1, final1)

        val streaming = buildRenderList(entries, emptyMap(), liveTurnDone = false) { false }
        assertEquals(
            listOf(RenderItem.Single(p1), RenderItem.Single(t1), RenderItem.Single(final1)),
            streaming
        )

        val done = buildRenderList(entries, emptyMap(), liveTurnDone = true) { false }
        assertEquals(
            listOf(RenderItem.Single(p1), RenderItem.Single(t1), RenderItem.Single(final1, isFinalAnswer = true)),
            done
        )
    }

    @Test
    fun buildRenderList_preAnchorPrefixEntriesRenderAsIs() {
        // Content before the first anchor (e.g. a post-compaction summary) is
        // not part of any turn and passes through the pre-anchor prefix path.
        val summary = sysNote("sum1", subtype = "summary")
        val p1 = prompt("p1")
        val result = buildRenderList(listOf(summary, p1), emptyMap(), liveTurnDone = false) { false }
        assertEquals(listOf(RenderItem.Single(summary), RenderItem.Single(p1)), result)
    }

    @Test
    fun buildRenderList_turnStepsFallsBackToMiddleSizeWhenMetaEntryMissing() {
        // meta[key] can be absent (e.g. a stale/partial map); steps then falls
        // back to the visible middle size instead of zeroing out.
        val p1 = prompt("p1")
        val t1 = tool("t1"); val t2 = tool("t2")
        val final1 = atext("final1")
        val p2 = prompt("p2")
        val entries = listOf(p1, t1, t2, final1, p2)
        val result = buildRenderList(entries, emptyMap(), liveTurnDone = false) { false }
        val turnSteps = result.filterIsInstance<RenderItem.TurnSteps>().single()
        assertEquals(2, turnSteps.steps)
        assertNull(turnSteps.durationMs)
    }

    @Test
    fun buildRenderList_fullTranscriptOrderingPreservedWhenNothingCollapsed() {
        // With every middle turn expanded and the live turn done, the render
        // list must contain every entry id in exactly the original order.
        val p1 = prompt("p1")
        val t1 = tool("t1"); val t2 = tool("t2")
        val final1 = atext("final1")
        val p2 = prompt("p2")
        val t3 = tool("t3"); val t4 = tool("t4")
        val final2 = atext("final2")
        val entries = listOf(p1, t1, t2, final1, p2, t3, t4, final2)
        val meta = computeTurnMeta(entries)
        val result = buildRenderList(entries, meta, liveTurnDone = true) { true }

        val flattenedIds = result.flatMap {
            when (it) {
                is RenderItem.Single -> listOf(it.entry.id)
                is RenderItem.ToolGroup -> it.calls.map { c -> c.id }
                is RenderItem.TurnSteps -> emptyList()
                is RenderItem.TimeGap -> emptyList()
            }
        }
        assertEquals(entries.map { it.id }, flattenedIds)
    }

    @Test
    fun buildRenderList_emptyInputReturnsEmptyList() {
        assertEquals(emptyList(), buildRenderList(emptyList(), emptyMap(), liveTurnDone = true) { false })
    }

    // --- buildRenderList: time gaps ---

    @Test
    fun buildRenderList_timeGapInsertedAtExactlyThresholdNotJustBelow() {
        // TIME_GAP_MINUTES = 30: a gap fires at exactly 30 minutes apart and
        // does NOT fire at 29 — the boundary is inclusive on the ">= 30" side.
        val a1 = atext("a1", ts = "2026-06-07T10:00:00.000Z")
        val a2At30 = atext("a2", ts = "2026-06-07T10:30:00.000Z")
        val atThreshold = buildRenderList(listOf(a1, a2At30), emptyMap(), liveTurnDone = true) { false }
        assertEquals(
            listOf(
                RenderItem.Single(a1),
                RenderItem.TimeGap(key = "gap:s:a2", label = formatTimestamp(a2At30.timestamp!!).take(5)),
                RenderItem.Single(a2At30),
            ),
            atThreshold
        )

        val a2At29 = atext("a2", ts = "2026-06-07T10:29:00.000Z")
        val belowThreshold = buildRenderList(listOf(a1, a2At29), emptyMap(), liveTurnDone = true) { false }
        assertEquals(listOf(RenderItem.Single(a1), RenderItem.Single(a2At29)), belowThreshold)
    }

    @Test
    fun buildRenderList_timeGapTrackingSkipsNullTimestampItems() {
        // An untimed item (e.g. AssistantThinking with no timestamp) must not
        // reset the "previous timestamp" the gap detector tracks — the gap
        // still fires relative to the last item that DID have one.
        val a1 = atext("a1", ts = "2026-06-07T10:00:00.000Z")
        val untimed = athinking("th1") // no timestamp
        val a3 = atext("a3", ts = "2026-06-07T10:30:00.000Z")
        val entries = listOf(a1, untimed, a3)
        val result = buildRenderList(entries, emptyMap(), liveTurnDone = true) { false }
        assertEquals(
            listOf(
                RenderItem.Single(a1),
                RenderItem.Single(untimed),
                RenderItem.TimeGap(key = "gap:s:a3", label = formatTimestamp(a3.timestamp!!).take(5)),
                RenderItem.Single(a3),
            ),
            result
        )
    }

    // --- itemKey ---

    @Test
    fun itemKey_derivesExpectedPrefixPerRenderItemVariant() {
        val single = RenderItem.Single(prompt("p1"))
        val group = RenderItem.ToolGroup(listOf(tool("t1"), tool("t2")))
        val steps = RenderItem.TurnSteps(turnKey = "p1", steps = 2, durationMs = null, expanded = false)
        val gap = RenderItem.TimeGap(key = "gap:custom", label = "10:00")

        assertEquals("s:p1", itemKey(single))
        assertEquals("tg:t1", itemKey(group))
        assertEquals("ts:p1", itemKey(steps))
        assertEquals("gap:custom", itemKey(gap))
    }

    @Test
    fun itemKey_uniqueAcrossRealisticMixedRenderList() {
        // The highest-value property: a duplicate key corrupts LazyColumn
        // rendering. Build a list that exercises every RenderItem variant.
        val p1 = prompt("p1")
        val t1 = tool("t1"); val t2 = tool("t2")
        val final1 = atext("final1")
        val p2 = prompt("p2")
        val t3 = tool("t3"); val t4 = tool("t4")
        val final2 = atext("final2")
        val entries = listOf(p1, t1, t2, final1, p2, t3, t4, final2)
        val meta = computeTurnMeta(entries)
        val result = buildRenderList(entries, meta, liveTurnDone = true) { true }

        val keys = result.map { itemKey(it) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun itemKey_stableAcrossRepeatedComputationFromSameInput() {
        // Keys back a LazyColumn; recomputing from identical input across
        // recompositions must yield identical keys or scroll position breaks.
        val entries = listOf(
            prompt("p1"), tool("t1"), tool("t2"), atext("final1"),
            prompt("p2"), tool("t3"),
        )
        val meta = computeTurnMeta(entries)
        val keys1 = buildRenderList(entries, meta, liveTurnDone = false) { false }.map { itemKey(it) }
        val keys2 = buildRenderList(entries, meta, liveTurnDone = false) { false }.map { itemKey(it) }
        assertEquals(keys1, keys2)
    }
}
