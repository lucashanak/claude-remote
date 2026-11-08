package com.clauderemote

import com.clauderemote.session.service.PromptFingerprint
import com.clauderemote.session.service.RefuseReason
import com.clauderemote.session.service.ReplyGuard
import com.clauderemote.util.FileLogger
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wire model for /reply — kept in sync with wearApp's copy by convention.
 * [prompt] is the WearSessionInfo.prompt the watch showed when the user
 * answered; null (an older watch build) fails closed.
 */
@Serializable
data class WearReplyRequest(val sessionId: String, val text: String, val prompt: String? = null)

/** Wire model for /approve (answer is "y"/"n") — same convention. */
@Serializable
data class WearApproveRequest(val sessionId: String, val answer: String, val prompt: String? = null)

/** Watch -> phone on /jarvis-undo: "Vrátit" of a close Jarvis announced (F4). */
@Serializable
data class WearJarvisUndoRequest(val serverId: String, val tmux: String)

/** Phone -> watch on /jarvis-undo-result: what the undo did, one Czech sentence. */
@Serializable
data class WearJarvisUndoResult(val tmux: String, val text: String)

/** Phone -> watch on /reply-refused: the reply was not sent, and why (shown on the wrist). */
@Serializable
data class WearReplyRefused(val sessionId: String, val message: String)

/**
 * Receives Wearable Data Layer messages from the watch companion app
 * (com.clauderemote.wear): `/reply` (free text from the watch's voice/
 * keyboard input) and `/approve` (y/n). Both route into [OrchestratorHolder]
 * exactly like [ReplyReceiver] does for the notification's RemoteInput
 * action — the same SessionOrchestrator.sendReply, which types nothing unless
 * the session still shows the prompt the watch answered; a refusal goes back
 * on /reply-refused with its reason.
 */
class PhoneWearService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            "/ping" -> FileLogger.log(TAG, "Wear ping received")
            "/reply" -> handleReply(messageEvent.data, messageEvent.sourceNodeId)
            "/approve" -> handleApprove(messageEvent.data, messageEvent.sourceNodeId)
            // Lazy history: the watch asks for a session's recent messages when
            // it opens a detail screen. Answered on /history-reply back to the
            // requesting node — a separate channel from /sessions so the
            // routine snapshot push stays small.
            "/history-request" -> handleHistoryRequest(messageEvent.data, messageEvent.sourceNodeId)
            "/jarvis-undo" -> handleJarvisUndo(messageEvent.data, messageEvent.sourceNodeId)
            // Watch-side log line (see wearApp's WearLog) — piggybacks on the
            // phone's existing LogShipper so watch diagnostics show up in the
            // same server-side log file as the phone's, no new infra needed.
            "/log" -> FileLogger.log("Wear", String(messageEvent.data, Charsets.UTF_8))
            else -> FileLogger.log(TAG, "Unhandled wear message: ${messageEvent.path}")
        }
    }

    private fun handleReply(data: ByteArray, sourceNodeId: String) {
        val req = runCatching {
            JSON.decodeFromString<WearReplyRequest>(String(data, Charsets.UTF_8))
        }.getOrNull() ?: return
        val text = req.text.trim()
        if (text.isEmpty()) return
        answer(req.sessionId, req.prompt, ReplyGuard.Reply.TEXT, text, sourceNodeId)
    }

    private fun handleApprove(data: ByteArray, sourceNodeId: String) {
        val req = runCatching {
            JSON.decodeFromString<WearApproveRequest>(String(data, Charsets.UTF_8))
        }.getOrNull() ?: return
        val answer = if (req.answer == "y") "y" else "n"
        answer(req.sessionId, req.prompt, ReplyGuard.Reply.APPROVE, answer, sourceNodeId)
    }

    /**
     * Send what the watch answered through SessionOrchestrator.sendReply: only
     * while the session still shows the prompt the watch showed (checked
     * against the app's state, then against the server's hook event log in
     * the same exec that types the keys). On a refusal nothing is sent and the
     * watch is told why on /reply-refused.
     *
     * Blocks this listener thread for the exec round trip on purpose: the
     * service may be unbound as soon as onMessageReceived returns, and the
     * answer must not be lost half way.
     */
    private fun answer(sessionId: String, rawPrompt: String?, reply: ReplyGuard.Reply, text: String, sourceNodeId: String) {
        val orchestrator = OrchestratorHolder.orchestrator
        if (orchestrator == null) {
            FileLogger.log(TAG, "Wear $reply dropped — orchestrator not available")
            return
        }
        ReplyGuard.watchRefusal(rawPrompt)?.let { why ->
            if (why == RefuseReason.WATCH_OUTDATED) {
                // An old watch build would show "sent" for a reply nobody typed: say it on the phone.
                FileLogger.log(TAG, "Wear $reply for $sessionId refused: watch app outdated (no prompt identity)")
                AlertNotifier.postRefused(this, sessionId, orchestrator.tabTitle(sessionId) ?: "Claude", why.message)
            } else {
                FileLogger.log(TAG, "Wear $reply for $sessionId refused: unreadable prompt identity")
            }
            tellWatch(sessionId, why, sourceNodeId)
            return
        }
        // An answer from the wrist to a session whose close is still in its undo window (the watch may hold a
        // snapshot from before the close) keeps that session once it reached the pane: sendReply undoes the close
        // after its checks, so a refused answer does not.
        val why = try {
            kotlinx.coroutines.runBlocking {
                orchestrator.sendReply(sessionId, PromptFingerprint.decode(rawPrompt), reply, text)
            }
        } catch (e: Exception) {
            FileLogger.log(TAG, "Wear $reply for $sessionId failed: ${e.message}")
            RefuseReason.NOT_VERIFIABLE
        }
        if (why != null) {
            tellWatch(sessionId, why, sourceNodeId)
            return
        }
        // The wrist answered, so the phone's alert for that session is spent.
        // Without this the bridged copy also stays on the watch, still
        // offering Reply for a question that has already been answered.
        AlertNotifier.clear(this, sessionId)
    }

    /** The reply was not sent: the watch shows [why] (its message) instead of "sent". */
    private fun tellWatch(sessionId: String, why: RefuseReason, sourceNodeId: String) {
        val json = runCatching {
            JSON.encodeToString<WearReplyRefused>(WearReplyRefused(sessionId, why.message))
        }.getOrNull() ?: return
        runCatching {
            Wearable.getMessageClient(this)
                .sendMessage(sourceNodeId, "/reply-refused", json.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * "Vrátit" from the wrist (Jarvis F4): the phone checks closed.jsonl and restores the session under its own name
     * through the same restore as "Nedávno zavřené" ([com.clauderemote.session.SessionOrchestrator.undoJarvisClose]);
     * anything that needs a choice is left to the app and the sentence says so. The answer goes back on
     * /jarvis-undo-result. Blocks this listener thread for the restore on purpose, like [answer]: the service may be
     * unbound as soon as onMessageReceived returns.
     */
    private fun handleJarvisUndo(data: ByteArray, sourceNodeId: String) {
        val req = runCatching {
            JSON.decodeFromString<WearJarvisUndoRequest>(String(data, Charsets.UTF_8))
        }.getOrNull() ?: return
        if (req.serverId.isBlank() || req.tmux.isBlank()) return
        val orchestrator = OrchestratorHolder.orchestrator
        val text = if (orchestrator == null) {
            "Aplikace na telefonu neběží – vrať to z Nedávno zavřených."
        } else {
            try {
                // the undo has its own limit (JarvisUndo.HEADLESS_TIMEOUT_MS); this one only keeps the listener
                // thread from ever hanging on something outside it (rev-f4-app LOW)
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(com.clauderemote.session.JarvisUndo.HEADLESS_TIMEOUT_MS + 10_000) {
                        orchestrator.undoJarvisClose(req.serverId, req.tmux)
                    }
                } ?: "Vracení „${com.clauderemote.session.JarvisUndo.alias(req.tmux)}“ neodpovědělo včas. Session se možná spustila: podívej se do seznamu sessions."
            } catch (e: Exception) {
                FileLogger.log(TAG, "Wear Jarvis undo of ${req.tmux} failed: ${e.message}")
                "Vrácení se nepovedlo (${e.javaClass.simpleName}) – zkus to z Nedávno zavřených."
            }
        }
        FileLogger.log(TAG, "Wear Jarvis undo of ${req.tmux} on ${req.serverId}: $text")
        val json = runCatching {
            JSON.encodeToString<WearJarvisUndoResult>(WearJarvisUndoResult(req.tmux, text))
        }.getOrNull() ?: return
        runCatching {
            Wearable.getMessageClient(this)
                .sendMessage(sourceNodeId, "/jarvis-undo-result", json.toByteArray(Charsets.UTF_8))
                .addOnFailureListener { JarvisNotifier.postUndoResult(this, text) }
        }.onFailure { JarvisNotifier.postUndoResult(this, text) }
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
