package com.clauderemote.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/**
 * Handles the notification actions posted by [WearNotifier] — the whole
 * point of the "notifikace-first" flow: Ano/Ne on an approval and the inline
 * reply are dispatched to the phone from here, with no Activity ever opened.
 *
 * onReceive runs on the main thread with a short deadline, but [sendApprove]/
 * [sendReply] are fire-and-forget with a callback (Play Services async), so
 * we don't block — the callback just cancels the notification and buzzes the
 * result. Deliberately no startActivity() from here: that's a background-
 * activity-start violation on API 34+ regardless of screen state (see
 * InstallResultReceiver's kdoc); the display-wake path goes through the
 * notification's full-screen intent instead.
 */
class WearActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(WearNotifier.EXTRA_SESSION_ID)
        if (sessionId.isNullOrBlank()) {
            WearLog.w(context, TAG, "onReceive with no session_id, action=${intent.action}")
            return
        }
        // BroadcastReceiver instance + its Context are torn down as soon as
        // onReceive returns; the async callback fires later, so hold the
        // application context for the notification cancel + haptics.
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_APPROVE -> {
                val answer = intent.getStringExtra(WearNotifier.EXTRA_ANSWER) ?: return
                WearLog.i(context, TAG, "approve $sessionId answer=$answer")
                sendApprove(appContext, sessionId, answer) { result -> finish(appContext, sessionId, result) }
            }
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(WearNotifier.KEY_REPLY_TEXT)?.toString()?.trim()
                if (text.isNullOrBlank()) {
                    WearLog.w(context, TAG, "reply $sessionId with blank text — ignored")
                    return
                }
                WearLog.i(context, TAG, "reply $sessionId (${text.length} chars)")
                sendReply(appContext, sessionId, text) { result -> finish(appContext, sessionId, result) }
            }
            else -> WearLog.w(context, TAG, "unknown action=${intent.action}")
        }
    }

    private fun finish(context: Context, sessionId: String, result: String) {
        WearLog.i(context, TAG, "send result for $sessionId: $result")
        WearNotifier.cancelSession(context, sessionId)
        if (result == "Sent") WearHaptics.success(context) else WearHaptics.error(context)
    }

    companion object {
        private const val TAG = "WearActionReceiver"
        const val ACTION_APPROVE = "com.clauderemote.wear.APPROVE"
        const val ACTION_REPLY = "com.clauderemote.wear.REPLY"
    }
}
