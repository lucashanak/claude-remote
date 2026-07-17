package com.clauderemote.ui

/**
 * True if [entries] contains an AskUserQuestion ToolCall whose tool_use_id is
 * not in [resultIds] AND no UserPrompt or AssistantText appears AFTER it (i.e.
 * the conversation has not moved on past the question). This prevents a dead /
 * abandoned session from keeping awaitingChoice stuck true forever (FIX D).
 */
internal fun hasPendingAskUserQuestion(
    entries: List<com.clauderemote.session.transcript.TranscriptEntry>,
    resultIds: Set<String>,
): Boolean {
    // Walk backwards; the last unanswered AskUserQuestion is the relevant one.
    val lastAskIdx = entries.indexOfLast {
        it is com.clauderemote.session.transcript.TranscriptEntry.ToolCall &&
            it.name == "AskUserQuestion" &&
            it.toolUseId !in resultIds
    }
    if (lastAskIdx < 0) return false
    // If anything that signals "conversation moved on" appears AFTER the ask,
    // treat it as abandoned/answered out-of-band.
    return entries.drop(lastAskIdx + 1).none {
        it is com.clauderemote.session.transcript.TranscriptEntry.UserPrompt ||
            it is com.clauderemote.session.transcript.TranscriptEntry.AssistantText
    }
}
