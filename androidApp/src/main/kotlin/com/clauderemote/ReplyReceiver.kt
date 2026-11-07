package com.clauderemote

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.service.PromptFingerprint
import com.clauderemote.session.service.ReplyGuard
import com.clauderemote.util.FileLogger

/**
 * Process-wide handle to the live [SessionOrchestrator] so non-Compose entry
 * points (notification reply, future Wear data layer) can reach the running
 * sessions. Set by [MainActivity] on create, cleared on destroy.
 */
object OrchestratorHolder {
    @Volatile var orchestrator: SessionOrchestrator? = null
}

/**
 * Handles the RemoteInput "Odpovědět" action on the Claude-needs-input
 * notification. On the phone this is an inline text reply; on Wear OS the
 * system bridges the action automatically and offers the watch's built-in
 * voice input (which does Czech), so the user can answer Claude from the
 * wrist with zero watch-side code.
 *
 * Mirrors the chat input path: sendClaudeCommand(text) followed by a CR a
 * beat later (same as voice mode), then reverts the alert notification —
 * which also clears the watch-side "sending…" spinner.
 *
 * Only while the session still shows the prompt the alert was raised for
 * (SessionOrchestrator.checkReply): every session runs in yolo mode, so a
 * text meant for one permission dialog must never answer the next one.
 * Otherwise nothing is sent and the alert says the question has changed.
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val orchestrator = OrchestratorHolder.orchestrator
        if (orchestrator == null) {
            FileLogger.log(TAG, "Reply dropped — orchestrator not available")
            return
        }
        val prompt = PromptFingerprint.decode(intent.getStringExtra(EXTRA_PROMPT))
        val verdict = orchestrator.checkReply(sessionId, prompt, ReplyGuard.Reply.TEXT)
        if (verdict is ReplyGuard.Verdict.Refuse) {
            FileLogger.log(TAG, "Reply to $sessionId refused (${prompt?.key}): ${verdict.reason}")
            AlertNotifier.postPromptChanged(context, sessionId, intent.getStringExtra(EXTRA_TITLE) ?: "Claude")
            return
        }
        FileLogger.log(TAG, "Reply to $sessionId: ${text.length} chars")
        orchestrator.sendClaudeCommand(sessionId, text)
        Handler(Looper.getMainLooper()).postDelayed({
            OrchestratorHolder.orchestrator?.sendClaudeCommand(sessionId, "\r")
        }, 60)
        // Cancel the alert — acknowledges it and clears the RemoteInput
        // progress indicator on the watch.
        AlertNotifier.clear(context, sessionId)
    }

    companion object {
        const val KEY_REPLY = "reply_text"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_TITLE = "session_title"
        // PromptFingerprint.encode() of the prompt the alert was raised for.
        const val EXTRA_PROMPT = "prompt"
        private const val TAG = "ReplyReceiver"
    }
}
