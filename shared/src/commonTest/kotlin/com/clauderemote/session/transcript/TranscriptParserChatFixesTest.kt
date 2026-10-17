package com.clauderemote.session.transcript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Regression tests for the chat-view parser fixes, each built from the real
 * record shape it was derived from (text shortened — the corpus is the user's
 * private conversations).
 *
 * Covers: queued prompts the transcript only ever records as an attachment,
 * the injected post-compaction summary, API-error messages, unparsed tool
 * input, unknown-tool summaries, duplicate ids on /resume, and per-line
 * isolation of the `type` read itself.
 */
class TranscriptParserChatFixesTest {

    private fun parse(vararg lines: String): List<TranscriptEntry> =
        TranscriptParser.parseLines(lines.asSequence())

    /** Embeds [inner] as a JSON string value. */
    private fun jsonEscape(inner: String): String = inner.replace("\"", "\\\"")

    /** Real shape, shortened: a prompt typed while Claude was still working. */
    private fun queued(
        uuid: String,
        prompt: String,
        recordTs: String? = "2026-07-03T06:38:07.485Z",
        attachmentTs: String = "2026-07-03T06:38:07.485Z",
        origin: String? = "human"
    ): String {
        val originJson = origin?.let { ""","origin":{"kind":"$it"}""" } ?: ""
        val tsJson = recordTs?.let { ""","timestamp":"$it"""" } ?: ""
        return """{"type":"attachment","uuid":"$uuid"$tsJson,"attachment":{"type":"queued_command",""" +
            """"commandMode":"prompt","prompt":"$prompt","timestamp":"$attachmentTs"$originJson}}"""
    }

    // ---- P1: queued human prompts ----

    @Test
    fun queuedHumanCommandBecomesUserPrompt() {
        val entries = parse(queued("q1", "also check the logs"))
        val prompt = assertIs<TranscriptEntry.UserPrompt>(entries.single())
        assertEquals("also check the logs", prompt.text)
        // The RECORD's uuid — attachment.source_uuid never resolves to a record
        // in the same file, so it cannot key an entry.
        assertEquals("q1", prompt.id)
        assertEquals("2026-07-03T06:38:07.485Z", prompt.timestamp)
    }

    @Test
    fun queuedCommandFallsBackToAttachmentTimestamp() {
        val entries = parse(queued("q1", "ping", recordTs = null, attachmentTs = "2026-07-03T07:00:00.000Z"))
        val prompt = assertIs<TranscriptEntry.UserPrompt>(entries.single())
        assertEquals("2026-07-03T07:00:00.000Z", prompt.timestamp)
    }

    @Test
    fun nonHumanQueuedOriginsStayDropped() {
        val entries = parse(
            queued("q1", "peer chatter", origin = "peer"),
            queued("q2", "agent done", origin = "task-notification"),
            queued("q3", "no origin at all", origin = null)
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun blankAndSyntheticQueuedPromptsDropped() {
        val entries = parse(
            queued("q1", "   "),
            queued("q2", "<system-reminder>noise</system-reminder>")
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun otherAttachmentTypesStayDropped() {
        val entries = parse(
            """{"type":"attachment","uuid":"a1","timestamp":"2026-07-03T06:38:07.485Z",""" +
                """"attachment":{"type":"file-history-snapshot","prompt":"nope"}}"""
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun laterUserRecordDuplicatingAQueuedPromptIsSkipped() {
        // The 3% case: the same prompt arrives again as a `user` record. Keep
        // the queued one — it is the earlier, more accurate moment.
        val entries = parse(
            queued("q1", "run the tests"),
            """{"type":"user","uuid":"u1","timestamp":"2026-07-03T06:40:00.000Z","message":{"content":"run the tests\n"}}"""
        )
        val prompt = assertIs<TranscriptEntry.UserPrompt>(entries.single())
        assertEquals("q1", prompt.id)
    }

    @Test
    fun queuedPromptDuplicatingAnEarlierUserRecordIsSkipped() {
        val entries = parse(
            """{"type":"user","uuid":"u1","timestamp":"2026-07-03T06:30:00.000Z","message":{"content":"run the tests"}}""",
            queued("q1", "run the tests")
        )
        val prompt = assertIs<TranscriptEntry.UserPrompt>(entries.single())
        assertEquals("u1", prompt.id)
    }

    @Test
    fun distinctQueuedAndUserPromptsAreBothKept() {
        val entries = parse(
            queued("q1", "first thing"),
            """{"type":"user","uuid":"u1","timestamp":"2026-07-03T06:40:00.000Z","message":{"content":"second thing"}}"""
        )
        assertEquals(2, entries.size)
        assertEquals("first thing", (entries[0] as TranscriptEntry.UserPrompt).text)
        assertEquals("second thing", (entries[1] as TranscriptEntry.UserPrompt).text)
    }

    @Test
    fun repeatedHumanPromptsAreNotDedupedAgainstEachOther() {
        // Typing "continue" twice is legitimate; only a queued/user PAIR is a
        // duplicate of one prompt.
        val entries = parse(
            """{"type":"user","uuid":"u1","message":{"content":"continue"}}""",
            """{"type":"user","uuid":"u2","message":{"content":"continue"}}"""
        )
        assertEquals(2, entries.size)
    }

    // ---- P2: post-compaction summary ----

    @Test
    fun compactSummaryBecomesSystemNoteNotUserPrompt() {
        val entries = parse(
            """{"type":"user","uuid":"u1","timestamp":"2026-08-20T20:50:47.687Z","isCompactSummary":true,""" +
                """"message":{"role":"user","content":"This session is being continued from a previous conversation."}}"""
        )
        val note = assertIs<TranscriptEntry.SystemNote>(entries.single())
        assertEquals("compact_summary", note.subtype)
        assertEquals("u1", note.id)
        assertTrue(note.text.startsWith("This session is being continued"))
    }

    @Test
    fun compactSummaryInArrayFormIsAlsoASystemNote() {
        val entries = parse(
            """{"type":"user","uuid":"u1","isCompactSummary":true,""" +
                """"message":{"role":"user","content":[{"type":"text","text":"Summary of earlier work."}]}}"""
        )
        val note = assertIs<TranscriptEntry.SystemNote>(entries.single())
        assertEquals("compact_summary", note.subtype)
        assertEquals("Summary of earlier work.", note.text)
    }

    // ---- P3: API-error assistant messages ----

    @Test
    fun apiErrorMessageIsFlagged() {
        val entries = parse(
            """{"type":"assistant","uuid":"a1","isApiErrorMessage":true,"apiErrorStatus":429,""" +
                """"message":{"model":"<synthetic>","content":[{"type":"text","text":"You've hit your session limit"}]}}"""
        )
        val text = assertIs<TranscriptEntry.AssistantText>(entries.single())
        assertTrue(text.isError)
    }

    @Test
    fun normalAssistantMessageIsNotAnError() {
        val entries = parse(
            """{"type":"assistant","uuid":"a1","message":{"model":"opus","content":[{"type":"text","text":"done"}]}}"""
        )
        val text = assertIs<TranscriptEntry.AssistantText>(entries.single())
        assertFalse(text.isError)
    }

    // ---- P4: __unparsedToolInput ----

    @Test
    fun unparsedToolInputIsUnwrapped() {
        val inner = """{"questions":[{"question":"Which approach?","header":"Approach"}]}"""
        val entries = parse(
            """{"type":"assistant","uuid":"a1","message":{"content":[{"type":"tool_use","id":"t1",""" +
                """"name":"AskUserQuestion","input":{"__unparsedToolInput":{"raw":"${jsonEscape(inner)}",""" +
                """"len":${inner.length}}}}]}}"""
        )
        val call = assertIs<TranscriptEntry.ToolCall>(entries.single())
        assertEquals("Which approach?", call.inputSummary)
        // fullInput is what the prominent card re-parses, so it must be the
        // unwrapped arguments, not the wrapper.
        assertFalse(call.fullInput.contains("__unparsedToolInput"))
        assertTrue(call.fullInput.contains("Which approach?"))
    }

    @Test
    fun unparsableToolInputFallsBackToTheRawText() {
        val inner = """{"questions": [{"question": "Which appro"""
        val entries = parse(
            """{"type":"assistant","uuid":"a1","message":{"content":[{"type":"tool_use","id":"t1",""" +
                """"name":"AskUserQuestion","input":{"__unparsedToolInput":{"raw":"${jsonEscape(inner)}",""" +
                """"len":${inner.length}}}}]}}"""
        )
        val call = assertIs<TranscriptEntry.ToolCall>(entries.single())
        assertEquals(inner, call.fullInput)
        assertTrue(call.inputSummary.startsWith("{\"questions\""))
    }

    // ---- P5: unknown-tool summaries ----

    @Test
    fun unknownToolSummarisesItsMostMeaningfulArgument() {
        val entries = parse(
            """{"type":"assistant","uuid":"a1","message":{"content":[{"type":"tool_use","id":"t1",""" +
                """"name":"mcp__wiki__search","input":{"limit":5,"query":"transcript parser"}}]}}"""
        )
        val call = assertIs<TranscriptEntry.ToolCall>(entries.single())
        assertEquals("transcript parser", call.inputSummary)
    }

    @Test
    fun unknownToolFallsBackToAnyShortStringValue() {
        val entries = parse(
            """{"type":"assistant","uuid":"a1","message":{"content":[{"type":"tool_use","id":"t1",""" +
                """"name":"mcp__thing__do","input":{"target":"session-42"}}]}}"""
        )
        val call = assertIs<TranscriptEntry.ToolCall>(entries.single())
        assertEquals("session-42", call.inputSummary)
    }

    @Test
    fun unknownToolWithNoStringValuesStillListsItsKeys() {
        val entries = parse(
            """{"type":"assistant","uuid":"a1","message":{"content":[{"type":"tool_use","id":"t1",""" +
                """"name":"mcp__thing__do","input":{"count":3,"enabled":true}}]}}"""
        )
        val call = assertIs<TranscriptEntry.ToolCall>(entries.single())
        assertEquals("count, enabled", call.inputSummary)
    }

    @Test
    fun emptyToolInputSaysSo() {
        val entries = parse(
            """{"type":"assistant","uuid":"a1","message":{"content":[{"type":"tool_use","id":"t1",""" +
                """"name":"mcp__thing__list","input":{}}]}}"""
        )
        val call = assertIs<TranscriptEntry.ToolCall>(entries.single())
        assertEquals("(no arguments)", call.inputSummary)
    }

    // ---- P6: duplicate ids ----

    @Test
    fun aRecordReEmittedOnResumeIsNotDuplicated() {
        val line = """{"type":"user","uuid":"u1","timestamp":"2026-07-03T06:38:07.485Z","message":{"content":"hello"}}"""
        val entries = parse(line, """{"type":"assistant","uuid":"a1","message":{"content":[{"type":"text","text":"hi"}]}}""", line)
        assertEquals(2, entries.size)
        assertEquals(listOf("u1", "a1#0"), entries.map { it.id })
    }

    @Test
    fun aToolCallReEmittedOnResumeIsNotDuplicated() {
        val line = """{"type":"assistant","uuid":"a1","message":{"content":[{"type":"tool_use","id":"toolu_1",""" +
            """"name":"Bash","input":{"command":"ls"}}]}}"""
        val entries = parse(line, line)
        assertEquals(1, entries.size)
    }

    // ---- P7: per-line isolation of the type read ----

    @Test
    fun nonPrimitiveTypeDoesNotAbortTheBatch() {
        val entries = parse(
            """{"type":"user","uuid":"u1","message":{"content":"first"}}""",
            """{"type":{"nested":true},"uuid":"x1"}""",
            """{"type":"user","uuid":"u2","message":{"content":"second"}}"""
        )
        assertEquals(2, entries.size)
        assertEquals("second", (entries[1] as TranscriptEntry.UserPrompt).text)
    }

    @Test
    fun malformedUsageRecordDoesNotAbortTheContextScan() {
        val tokens = TranscriptParser.latestContextTokens(
            sequenceOf(
                """{"type":"assistant","uuid":"a1","message":{"usage":"not-an-object"}}""",
                """{"type":[1],"message":{"usage":{"input_tokens":1}}}""",
                """{"type":"assistant","uuid":"a2","message":{"usage":{"input_tokens":10,""" +
                    """"cache_creation_input_tokens":5,"cache_read_input_tokens":2}}}"""
            )
        )
        assertEquals(17L, tokens)
    }

    // ---- regression: the ordinary sequence is untouched ----

    @Test
    fun normalTurnIsUnchanged() {
        val entries = parse(
            """{"type":"user","uuid":"u1","timestamp":"2026-07-03T06:38:00.000Z","message":{"content":"list the files"}}""",
            """{"type":"assistant","uuid":"a1","timestamp":"2026-07-03T06:38:01.000Z","message":{"model":"opus",""" +
                """"content":[{"type":"thinking","thinking":"hmm"},{"type":"tool_use","id":"toolu_1","name":"Bash",""" +
                """"input":{"command":"ls -la"}}]}}""",
            """{"type":"user","uuid":"u2","timestamp":"2026-07-03T06:38:02.000Z","message":{"content":""" +
                """[{"type":"tool_result","tool_use_id":"toolu_1","content":"file.txt"}]}}""",
            """{"type":"assistant","uuid":"a2","timestamp":"2026-07-03T06:38:03.000Z","message":{"model":"opus",""" +
                """"content":[{"type":"text","text":"one file"}]}}""",
            """{"type":"system","uuid":"s1","timestamp":"2026-07-03T06:38:04.000Z","subtype":"stop_hook_summary"}"""
        )
        assertEquals(
            listOf("u1", "a1#0", "toolu_1", "u2#0", "a2#0", "s1"),
            entries.map { it.id }
        )
        assertEquals("ls -la", (entries[2] as TranscriptEntry.ToolCall).inputSummary)
        assertEquals("file.txt", (entries[3] as TranscriptEntry.ToolResult).text)
        assertFalse((entries[4] as TranscriptEntry.AssistantText).isError)
    }

    // ---- blank cards: a row with nothing in it reads as a broken app ----

    @Test
    fun aToolUseWithNoInputKeyIsSummarisedRatherThanLeftBlank() {
        // The empty-object case was already handled; a block with no `input`
        // key at all fell through to an empty summary AND an empty argument
        // dump, i.e. a card showing nothing.
        val entries = parse(
            """{"type":"assistant","uuid":"a1","timestamp":"2026-07-03T06:38:00.000Z","message":{"model":"opus",""" +
                """"content":[{"type":"tool_use","id":"toolu_1","name":"SomeMcpTool"}]}}"""
        )
        val call = assertIs<TranscriptEntry.ToolCall>(entries.single())
        assertEquals("(no arguments)", call.inputSummary)
        assertEquals("{}", call.fullInput)
    }

    @Test
    fun aUserRecordWithEmptyTextDoesNotBecomeAnEmptyBubble() {
        // The array-content branch already refused blank parts; the plain-string
        // branch did not, so an empty message rendered as an empty user bubble.
        val entries = parse(
            """{"type":"user","uuid":"u1","timestamp":"2026-07-03T06:38:00.000Z","message":{"content":"   "}}""",
            """{"type":"user","uuid":"u2","timestamp":"2026-07-03T06:38:01.000Z","message":{"content":"real prompt"}}"""
        )
        assertEquals(listOf("u2"), entries.map { it.id })
    }
}
