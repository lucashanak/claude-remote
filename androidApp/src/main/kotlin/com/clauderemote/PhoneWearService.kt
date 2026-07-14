package com.clauderemote

import android.os.Handler
import android.os.Looper
import com.clauderemote.util.FileLogger
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Wire model for /reply — kept in sync with wearApp's copy by convention. */
@Serializable
data class WearReplyRequest(val sessionId: String, val text: String)

/** Wire model for /approve (answer is "y"/"n") — same convention. */
@Serializable
data class WearApproveRequest(val sessionId: String, val answer: String)

/**
 * Receives Wearable Data Layer messages from the watch companion app
 * (com.clauderemote.wear): `/reply` (free text from the watch's voice/
 * keyboard input) and `/approve` (y/n). Both route into [OrchestratorHolder]
 * exactly like [ReplyReceiver] does for the notification's RemoteInput
 * action — same sendClaudeCommand(text) + delayed "\r" convention.
 */
class PhoneWearService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            "/ping" -> FileLogger.log(TAG, "Wear ping received")
            "/reply" -> handleReply(messageEvent.data)
            "/approve" -> handleApprove(messageEvent.data)
            // Lazy history: the watch asks for a session's recent messages when
            // it opens a detail screen. Answered on /history-reply back to the
            // requesting node — a separate channel from /sessions so the
            // routine snapshot push stays small.
            "/history-request" -> handleHistoryRequest(messageEvent.data, messageEvent.sourceNodeId)
            // Watch-side log line (see wearApp's WearLog) — piggybacks on the
            // phone's existing LogShipper so watch diagnostics show up in the
            // same server-side log file as the phone's, no new infra needed.
            "/log" -> FileLogger.log("Wear", String(messageEvent.data, Charsets.UTF_8))
            else -> FileLogger.log(TAG, "Unhandled wear message: ${messageEvent.path}")
        }
    }

    private fun handleReply(data: ByteArray) {
        val req = runCatching {
            JSON.decodeFromString<WearReplyRequest>(String(data, Charsets.UTF_8))
        }.getOrNull() ?: return
        val text = req.text.trim()
        if (text.isEmpty()) return
        val orchestrator = OrchestratorHolder.orchestrator
        if (orchestrator == null) {
            FileLogger.log(TAG, "Wear reply dropped — orchestrator not available")
            return
        }
        FileLogger.log(TAG, "Wear reply to ${req.sessionId}: ${text.length} chars")
        orchestrator.sendClaudeCommand(req.sessionId, text)
        Handler(Looper.getMainLooper()).postDelayed({
            OrchestratorHolder.orchestrator?.sendClaudeCommand(req.sessionId, "\r")
        }, 60)
    }

    private fun handleApprove(data: ByteArray) {
        val req = runCatching {
            JSON.decodeFromString<WearApproveRequest>(String(data, Charsets.UTF_8))
        }.getOrNull() ?: return
        val answer = if (req.answer == "y") "y" else "n"
        val orchestrator = OrchestratorHolder.orchestrator
        if (orchestrator == null) {
            FileLogger.log(TAG, "Wear approve dropped — orchestrator not available")
            return
        }
        FileLogger.log(TAG, "Wear approve for ${req.sessionId}: $answer")
        // Single call, unlike /reply — matches the phone's own y/n buttons
        // (TerminalScreen.kt), which send "y\r"/"n\r" in one shot since it's
        // a single keystroke, not dictated text needing a settle delay.
        orchestrator.sendClaudeCommand(req.sessionId, "$answer\r")
    }

    private fun handleHistoryRequest(data: ByteArray, sourceNodeId: String) {
        val req = runCatching {
            JSON.decodeFromString<WearHistoryRequest>(String(data, Charsets.UTF_8))
        }.getOrNull() ?: return
        val orchestrator = OrchestratorHolder.orchestrator
        if (orchestrator == null) {
            FileLogger.log(TAG, "Wear history request dropped — orchestrator not available")
            return
        }
        // Last ~10 user/assistant messages, each clamped to ~500 chars so the
        // reply stays small over the BT tunnel. Role is "user"/"assistant".
        val messages = orchestrator.recentMessages(req.sessionId, 10).map { (role, text) ->
            WearChatMessage(role, text.take(HISTORY_TEXT_MAX))
        }
        FileLogger.log(TAG, "Wear history for ${req.sessionId}: ${messages.size} msg(s)")
        val json = runCatching {
            JSON.encodeToString<WearHistoryReply>(WearHistoryReply(req.sessionId, messages))
        }.getOrNull() ?: return
        // Fire-and-forget back to the requesting node — never block the service.
        runCatching {
            Wearable.getMessageClient(this)
                .sendMessage(sourceNodeId, "/history-reply", json.toByteArray(Charsets.UTF_8))
        }
    }

    companion object {
        private const val TAG = "PhoneWearService"
        private val JSON = Json { ignoreUnknownKeys = true }
        // Per-message clamp — keeps a 10-message reply well within a sane
        // MessageClient payload without truncating mid-conversation context.
        private const val HISTORY_TEXT_MAX = 500
    }
}
