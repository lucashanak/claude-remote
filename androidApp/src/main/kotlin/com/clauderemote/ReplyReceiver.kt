package com.clauderemote

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
    /** The same, as a flow: Jarvis' conversation mode follows the current orchestrator across a recreation (F5a). */
    val current = kotlinx.coroutines.flow.MutableStateFlow<SessionOrchestrator?>(null)

    var orchestrator: SessionOrchestrator?
        get() = current.value
        set(value) { current.value = value }
}

/**
 * Handles the RemoteInput "Odpovědět" action on the Claude-needs-input
 * notification. On the phone this is an inline text reply; on Wear OS the
 * system bridges the action automatically and offers the watch's built-in
 * voice input (which does Czech), so the user can answer Claude from the
 * wrist with zero watch-side code.
 *
 * Also the Ano/Ne of a permission alert ([EXTRA_ANSWER]): free text never
 * answers a permission dialog, Enter would pick its default "Yes".
 *
 * Sent through SessionOrchestrator.sendReply, only while the session still
 * shows the prompt the alert was raised for — checked against the app's
 * state and then against the server's hook event log in the same exec that
 * types the keys. Every session runs in yolo mode, so a text meant for one
 * permission dialog must never answer the next one. Otherwise nothing is
 * sent and the alert says why.
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        // Ano/Ne of a permission alert, or the inline reply's text.
        val answer = intent.getStringExtra(EXTRA_ANSWER)?.takeIf { it == "y" || it == "n" }
        val text = answer ?: RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val orchestrator = OrchestratorHolder.orchestrator
        if (orchestrator == null) {
            FileLogger.log(TAG, "Reply dropped — orchestrator not available")
            return
        }
        val prompt = PromptFingerprint.decode(intent.getStringExtra(EXTRA_PROMPT))
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Claude"
        val reply = if (answer != null) ReplyGuard.Reply.APPROVE else ReplyGuard.Reply.TEXT
        // The server check is an exec round trip: keep the receiver alive until it answers
        // (the exec is capped well inside the broadcast budget).
        val pending = goAsync()
        val appContext = context.applicationContext
        // An answer that reaches the pane also undoes a close still in its undo window (sendReply does it, after the
        // checks: a refused answer keeps the close).
        orchestrator.sendReplyAsync(sessionId, prompt, reply, text) { why ->
            try {
                // Cancelling also clears the RemoteInput progress indicator; a refusal replaces
                // the alert with its reason and no actions.
                if (why == null) AlertNotifier.clear(appContext, sessionId)
                else AlertNotifier.postRefused(appContext, sessionId, title, why.message)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val KEY_REPLY = "reply_text"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_TITLE = "session_title"
        // PromptFingerprint.encode() of the prompt the alert was raised for.
        const val EXTRA_PROMPT = "prompt"
        // "y" / "n" of a permission alert's Ano/Ne.
        const val EXTRA_ANSWER = "answer"
        private const val TAG = "ReplyReceiver"
    }
}
