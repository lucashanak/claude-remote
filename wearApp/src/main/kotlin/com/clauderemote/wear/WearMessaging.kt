package com.clauderemote.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Wire model for /reply — kept in sync with androidApp's copy by convention. */
@Serializable
data class WearReplyRequest(val sessionId: String, val text: String)

/** Wire model for /approve (answer is "y"/"n") — same convention. */
@Serializable
data class WearApproveRequest(val sessionId: String, val answer: String)

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

fun sendReply(context: Context, sessionId: String, text: String, onResult: (String) -> Unit = {}) {
    val json = MESSAGING_JSON.encodeToString<WearReplyRequest>(WearReplyRequest(sessionId, text))
    sendToPhone(context, "/reply", json.toByteArray(Charsets.UTF_8), onResult)
}

fun sendApprove(context: Context, sessionId: String, answer: String, onResult: (String) -> Unit = {}) {
    val json = MESSAGING_JSON.encodeToString<WearApproveRequest>(WearApproveRequest(sessionId, answer))
    sendToPhone(context, "/approve", json.toByteArray(Charsets.UTF_8), onResult)
}
