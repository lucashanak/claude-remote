package com.clauderemote.session.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [TranscriptParser] — the JSONL → entry pipeline the chat view
 * renders from. Focused on the contracts the turn-grouping UI relies on:
 * structured turn_duration, the always-emitted stop marker, and tool
 * call/result pairing ids.
 */
class TranscriptParserTest {

    private fun parse(vararg lines: String): List<TranscriptEntry> =
        TranscriptParser.parseLines(lines.asSequence())

    @Test
    fun userPromptParsed() {
        val entries = parse(
            """{"type":"user","uuid":"u1","timestamp":"2026-06-07T10:00:00.000Z","message":{"content":"hello"}}"""
        )
        val prompt = assertIs<TranscriptEntry.UserPrompt>(entries.single())
        assertEquals("hello", prompt.text)
        assertEquals("u1", prompt.id)
    }

    @Test
    fun syntheticUserInjectionIsDropped() {
        val entries = parse(
            """{"type":"user","uuid":"u1","message":{"content":"<system-reminder>noise</system-reminder>"}}"""
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun turnDurationIsStructured() {
        val entries = parse(
            """{"type":"system","uuid":"s1","subtype":"turn_duration","durationMs":61500}"""
        )
        val note = assertIs<TranscriptEntry.SystemNote>(entries.single())
        assertEquals("turn_duration", note.subtype)
        assertEquals(61500L, note.durationMs)
    }

    @Test
    fun nonDurationSystemNoteHasNullDuration() {
        val entries = parse(
            """{"type":"system","uuid":"s2","subtype":"info","content":"something happened"}"""
        )
        val note = assertIs<TranscriptEntry.SystemNote>(entries.single())
        assertNull(note.durationMs)
    }

    @Test
    fun stopHookSummaryAlwaysEmitted() {
        // Empty body must still produce the marker — the chat view uses it as
        // the authoritative turn boundary.
        val entries = parse(
            """{"type":"system","uuid":"s3","subtype":"stop_hook_summary"}"""
        )
        val note = assertIs<TranscriptEntry.SystemNote>(entries.single())
        assertEquals("stop_hook_summary", note.subtype)
        assertTrue(note.text.isNotBlank())
    }

    @Test
    fun toolCallAndResultPairOnToolUseId() {
        val entries = parse(
            """{"type":"assistant","uuid":"a1","message":{"model":"opus","content":[{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"ls"}}]}}""",
            """{"type":"user","uuid":"u2","message":{"content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"file.txt"}]}}"""
        )
        assertEquals(2, entries.size)
        val call = assertIs<TranscriptEntry.ToolCall>(entries[0])
        val result = assertIs<TranscriptEntry.ToolResult>(entries[1])
        assertEquals("toolu_1", call.toolUseId)
        assertEquals("toolu_1", result.toolUseId)
        assertEquals("ls", call.inputSummary)
        assertEquals("Bash", call.name)
    }

    @Test
    fun malformedLineDoesNotAbortBatch() {
        val entries = parse(
            """{"type":"user","uuid":"u1","message":{"content":"first"}}""",
            """{not json at all""",
            """{"type":"user","uuid":"u2","message":{"content":"second"}}"""
        )
        assertEquals(2, entries.size)
        assertEquals("first", (entries[0] as TranscriptEntry.UserPrompt).text)
        assertEquals("second", (entries[1] as TranscriptEntry.UserPrompt).text)
    }

    @Test
    fun slashCommandParsed() {
        val entries = parse(
            """{"type":"user","uuid":"u1","message":{"content":"<command-name>/review</command-name><command-message>review</command-message><command-args>pr 7</command-args>"}}"""
        )
        val cmd = assertIs<TranscriptEntry.SlashCommand>(entries.single())
        assertEquals("/review", cmd.name)
        assertEquals("pr 7", cmd.args)
    }

    @Test
    fun assistantTextAndThinkingSplitIntoEntries() {
        val entries = parse(
            """{"type":"assistant","uuid":"a1","message":{"model":"opus","content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"answer"}]}}"""
        )
        assertEquals(2, entries.size)
        assertIs<TranscriptEntry.AssistantThinking>(entries[0])
        val text = assertIs<TranscriptEntry.AssistantText>(entries[1])
        assertEquals("answer", text.text)
        assertEquals("opus", text.model)
    }
}
