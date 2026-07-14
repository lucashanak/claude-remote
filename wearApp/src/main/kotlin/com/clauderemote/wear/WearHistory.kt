package com.clauderemote.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

/**
 * Wire models for the lazy history channel. The watch asks the phone for a
 * session's recent messages (/history-request) when a detail screen opens and
 * the phone answers on /history-reply. Deliberately a SEPARATE request→reply
 * channel, NOT part of the /sessions snapshot — that's the whole point of
 * "lazy": history would otherwise bloat every routine push payload.
 *
 * Kept in sync with androidApp's own copy (PhoneWearHistory.kt) by convention,
 * mirroring how WearReplyRequest/WearApproveRequest are duplicated across the
 * two dependency-free modules.
 */
@Serializable
data class WearChatMessage(val role: String, val text: String)

@Serializable
data class WearHistoryReply(val sessionId: String, val messages: List<WearChatMessage>)

@Serializable
data class WearHistoryRequest(val sessionId: String)

/**
 * Process-wide holder for lazily-fetched history. The /history-reply listener
 * (registered while a detail screen is open, see MainActivity) drops parsed
 * messages here; Compose collectAsState()s the flow and repaints. Keyed by
 * sessionId so each detail screen reads only its own entry, and so a stale
 * entry from a previously-open session is trivially clear()-able on open.
 */
object HistoryStore {
    val history = MutableStateFlow<Map<String, List<WearChatMessage>>>(emptyMap())

    fun put(sessionId: String, messages: List<WearChatMessage>) {
        history.value = history.value + (sessionId to messages)
    }

    fun clear(sessionId: String) {
        history.value = history.value - sessionId
    }
}
