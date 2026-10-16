package com.clauderemote.session.transcript

/**
 * One entry shown in the Transcript view. Parsed from Claude Code's
 * append-only JSONL transcripts under ~/.claude/projects/<encoded-cwd>/<uuid>.jsonl.
 *
 * Metadata-only records (file-history-snapshot, ai-title, last-prompt,
 * queue-operation, and every attachment except a human `queued_command`) are
 * dropped during parsing.
 */
sealed class TranscriptEntry {
    abstract val id: String
    abstract val timestamp: String?

    data class UserPrompt(
        override val id: String,
        override val timestamp: String?,
        val text: String,
        /**
         * Came from a `queued_command` attachment — the prompt the user typed
         * while Claude was still busy. Claude Code sometimes ALSO writes the
         * same prompt as a `user` record once it picks it up, a turn later and
         * therefore in a different stream batch, so the stream needs to know
         * which of the two it is looking at to drop the second one.
         */
        val fromQueue: Boolean = false
    ) : TranscriptEntry()

    data class SlashCommand(
        override val id: String,
        override val timestamp: String?,
        val name: String,
        val args: String
    ) : TranscriptEntry()

    data class AssistantText(
        override val id: String,
        override val timestamp: String?,
        val text: String,
        val model: String?,
        /**
         * Set for a record flagged `isApiErrorMessage` — a rate-limit or
         * transport failure Claude Code logs in the assistant's own voice
         * (model `<synthetic>`). It is not an answer, so the UI must not give
         * it the authoritative-answer frame.
         */
        val isError: Boolean = false
    ) : TranscriptEntry()

    data class AssistantThinking(
        override val id: String,
        override val timestamp: String?,
        val text: String
    ) : TranscriptEntry()

    data class ToolCall(
        override val id: String,
        override val timestamp: String?,
        val toolUseId: String,
        val name: String,
        val inputSummary: String,
        val fullInput: String
    ) : TranscriptEntry()

    data class ToolResult(
        override val id: String,
        override val timestamp: String?,
        val toolUseId: String?,
        val text: String,
        val isError: Boolean
    ) : TranscriptEntry()

    data class SystemNote(
        override val id: String,
        override val timestamp: String?,
        val subtype: String,
        val text: String,
        /** Structured turn duration for `turn_duration` notes (ms), else null. */
        val durationMs: Long? = null
    ) : TranscriptEntry()
}
