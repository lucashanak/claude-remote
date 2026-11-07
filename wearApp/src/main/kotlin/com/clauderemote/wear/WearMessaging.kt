package com.clauderemote.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wire model for /reply — kept in sync with androidApp's copy by convention.
 * [prompt] is the [WearSessionInfo.prompt] the user answered; the phone
 * sends nothing when the session shows a different prompt by then. Sent as
 * "" when the row offered none: null is what an OLD watch build sends, and
 * the phone answers that with "update the watch app".
 */
@Serializable
data class WearReplyRequest(val sessionId: String, val text: String, val prompt: String? = null)

/** Wire model for /approve (answer is "y"/"n") — same convention. */
@Serializable
data class WearApproveRequest(val sessionId: String, val answer: String, val prompt: String? = null)

/** Phone -> watch on /reply-refused: the reply was not sent, and why. */
@Serializable
data class WearReplyRefused(val sessionId: String, val message: String)

private val MESSAGING_JSON = Json { ignoreUnknownKeys = true }

/**
 * Fire-and-forget MessageClient send to the (single, personal-device-pair)
 * connected phone. Callback-based — no coroutines-play-services dependency.
 */
private fun sendToPhone(context: Context, path: String, payload: ByteArray, onResult: (String) -> Unit = {}) {
    Wearable.getNodeClient(context).connectedNodes
        .addOnSuccessListener { nodes ->
            val node = nodes.firstOrNull()
            if (node == null) {
                onResult("No phone connected")
                return@addOnSuccessListener
            }
            Wearable.getMessageClient(context).sendMessage(node.id, path, payload)
                .addOnSuccessListener { onResult("Sent") }
                .addOnFailureListener { e -> onResult("Send failed: ${e.message}") }
        }
        .addOnFailureListener { e -> onResult("Node lookup failed: ${e.message}") }
}

fun sendReply(context: Context, sessionId: String, text: String, prompt: String?, onResult: (String) -> Unit = {}) {
    val json = MESSAGING_JSON.encodeToString<WearReplyRequest>(WearReplyRequest(sessionId, text, prompt ?: ""))
    sendToPhone(context, "/reply", json.toByteArray(Charsets.UTF_8), onResult)
}

fun sendApprove(context: Context, sessionId: String, answer: String, prompt: String?, onResult: (String) -> Unit = {}) {
    val json = MESSAGING_JSON.encodeToString<WearApproveRequest>(WearApproveRequest(sessionId, answer, prompt ?: ""))
    sendToPhone(context, "/approve", json.toByteArray(Charsets.UTF_8), onResult)
}

/**
 * Best-effort ask for a session's recent history (see WearHistory.kt). The
 * phone answers on /history-reply, which the open detail screen listens for.
 * Fire-and-forget like the rest — never blocks the UI or the actions.
 */
fun sendHistoryRequest(context: Context, sessionId: String, onResult: (String) -> Unit = {}) {
    val json = MESSAGING_JSON.encodeToString<WearHistoryRequest>(WearHistoryRequest(sessionId))
    sendToPhone(context, "/history-request", json.toByteArray(Charsets.UTF_8), onResult)
}
