package com.clauderemote

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clauderemote.util.FileLogger
import com.clauderemote.voice.conversation.TalkSendResult
import com.clauderemote.voice.conversation.TalkWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Ano" and "Odpovědět" of Jarvis' question (F5c, a jask/jblockask notification). Both go into Jarvis' pane only, over
 * the conversation mode's checked path (idle at his prompt, his input empty, checked again on the server in the exec
 * that types), one answer at a time and only while that very question is still on offer
 * ([com.clauderemote.session.SessionOrchestrator.jarvisYes] / [com.clauderemote.session.SessionOrchestrator.jarvisReply]).
 * Ano types the bare "ano"; which proposal it approves is his hook's decision. Nothing is ever queued: refused = said
 * in the notification.
 */
class JarvisAskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val alertId = intent.getStringExtra(EXTRA_ALERT_ID)
        val typed = if (intent.action == ACTION_REPLY) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
        } else null
        if (intent.action == ACTION_REPLY && typed.isNullOrEmpty()) return
        if (intent.action != ACTION_REPLY && intent.action != ACTION_YES) return
        val orchestrator = OrchestratorHolder.orchestrator
        if (orchestrator == null) {
            FileLogger.log(TAG, "${intent.action} dropped — orchestrator not available")
            JarvisNotifier.postAnswerResult(appContext, false, "Aplikace neběží – odpověz Jarvisovi v ní.")
            return
        }
        // goAsync keeps the process up while the checked exec runs. The answer is never cut by a timeout: the exec runs
        // to its end once started (NonCancellable), and a late "sent" said as "nevím" would invite a second Ano
        // (rev-f5c-app L1). The receiver lets go after LIMIT_MS; the result is posted whenever it comes.
        val pending = goAsync()
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) pending.finish() }
        val job = SCOPE.launch {
            val r = try {
                if (typed == null) orchestrator.jarvisYes(alertId) else orchestrator.jarvisReply(alertId, typed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.log(TAG, "${intent.action} failed: ${e::class.simpleName}: ${e.message}")
                TalkSendResult.Refused("Poslat to nešlo (${e::class.simpleName}), zkus to v aplikaci.")
            }
            val (ok, text) = TalkWords.answerOutcome(r, if (typed == null) "Poslal jsem Jarvisovi „ano“." else "Odpověď odešla Jarvisovi.")
            FileLogger.log(TAG, "${intent.action} of $alertId: ok=$ok $text")
            try {
                JarvisNotifier.postAnswerResult(appContext, ok, text)
                // answered here: the wrist drops its Ano with the next snapshot (H1)
                WearSync.pushNow(urgent = false)
            } finally {
                release()
            }
        }
        SCOPE.launch {
            if (withTimeoutOrNull(LIMIT_MS) { job.join() } == null) {
                FileLogger.log(TAG, "${intent.action} of $alertId still running after ${LIMIT_MS}ms: the result follows")
            }
            release()
        }
    }

    companion object {
        const val ACTION_YES = "com.clauderemote.jarvis.ASK_YES"
        const val ACTION_REPLY = "com.clauderemote.jarvis.ASK_REPLY"
        const val EXTRA_ALERT_ID = "jarvis_alert_id"
        const val KEY_REPLY = "jarvis_reply_text"
        /** Under the ~10 s a goAsync receiver has. */
        private const val LIMIT_MS = 9_000L
        private const val TAG = "JarvisAsk"
        private val SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
