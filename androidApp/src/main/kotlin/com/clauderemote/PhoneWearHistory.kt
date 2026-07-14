package com.clauderemote

import kotlinx.serialization.Serializable

/**
 * Wire models for the lazy history channel — the phone's own copy, kept in
 * sync with wearApp's WearHistory.kt by convention (same as WearReplyRequest/
 * WearApproveRequest). Lives in its own file, not WearSync.kt, so it stays
 * fully independent of the /sessions snapshot: history is a separate
 * request→reply channel precisely so it never bloats the routine push.
 */
@Serializable
data class WearChatMessage(val role: String, val text: String)

@Serializable
data class WearHistoryRequest(val sessionId: String)

@Serializable
data class WearHistoryReply(val sessionId: String, val messages: List<WearChatMessage>)
