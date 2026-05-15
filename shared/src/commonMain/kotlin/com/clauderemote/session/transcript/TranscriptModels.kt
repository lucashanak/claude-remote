package com.clauderemote.session.transcript

/**
 * One entry shown in the Transcript view. Parsed from Claude Code's
 * append-only JSONL transcripts under ~/.claude/projects/<encoded-cwd>/<uuid>.jsonl.
 *
 * Metadata-only records (attachments, file-history-snapshot, ai-title,
 * last-prompt, queue-operation) are dropped during parsing.
 */
sealed class TranscriptEntry {
    abstract val id: String
    abstract val timestamp: String?

    data class UserPrompt(
        override val id: String,
        override val timestamp: String?,
        val text: String
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
        val model: String?
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
        val text: String
    ) : TranscriptEntry()
}
