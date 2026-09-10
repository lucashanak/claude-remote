package com.clauderemote.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.app.RemoteInput
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Handles the notification actions posted by [WearNotifier] — the whole
 * point of the "notifikace-first" flow: Ano/Ne on an approval and the inline
 * reply are dispatched to the phone from here, with no Activity ever opened.
 *
 * [sendApprove]/[sendReply] jsou dva zřetězené Play Services IPC round-tripy
 * (NodeClient.connectedNodes → MessageClient.sendMessage), jejichž callbacky
 * doběhnou dávno po návratu z onReceive. Bez [goAsync] proces v ten moment
 * spadne na cached prioritu a systém ho může zabít dřív, než se cokoliv
 * odešle — nadiktovaná odpověď zmizí bez chybové vibrace i bez zrušení
 * notifikace. goAsync() drží proces na foreground prioritě až do
 * PendingResult.finish(), který voláme z callbacku i z timeoutu (broadcast
 * receiver má tvrdý ~10s rozpočet, takže se do něj s rezervou vejdeme a
 * radši ohlásíme "nepotvrzeno", než abychom nechali PendingResult viset a
 * dostali ANR).
 *
 * Deliberately no startActivity() from here: that's a background-activity-
 * start violation on API 34+ regardless of screen state (see
 * InstallResultReceiver's kdoc); the display-wake path goes through the
 * notification's full-screen intent instead.
 */
class WearActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // BroadcastReceiver instance + its Context are torn down as soon as
        // onReceive returns; the async callback fires later, so hold the
        // application context for the notification cancel + haptics.
        val appContext = context.applicationContext
        val pending = goAsync()

        val sessionId = intent.getStringExtra(WearNotifier.EXTRA_SESSION_ID)
        if (sessionId.isNullOrBlank()) {
            WearLog.w(appContext, TAG, "onReceive with no session_id, action=${intent.action}")
            pending.finish()
            return
        }

        val replyText = if (intent.action == ACTION_REPLY) {
            RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(WearNotifier.KEY_REPLY_TEXT)?.toString()?.trim()
        } else {
            null
        }
        val answer = if (intent.action == ACTION_APPROVE) intent.getStringExtra(WearNotifier.EXTRA_ANSWER) else null

        // Nic k odeslání — uzavři PendingResult hned, ať proces nedrží
        // foreground prioritu zbytečně.
        when (intent.action) {
            ACTION_APPROVE -> if (answer.isNullOrBlank()) {
                WearLog.w(appContext, TAG, "approve $sessionId with no answer — ignored")
                pending.finish()
                return
            }
            ACTION_REPLY -> if (replyText.isNullOrBlank()) {
                WearLog.w(appContext, TAG, "reply $sessionId with blank text — ignored")
                pending.finish()
                return
            }
            else -> {
                WearLog.w(appContext, TAG, "unknown action=${intent.action}")
                pending.finish()
                return
            }
        }

        // Jediné místo, kde se PendingResult uzavírá — z callbacku NEBO z
        // timeoutu, podle toho, co přijde dřív. AtomicBoolean, protože na
        // vlákno, kde Play Services callback doběhne, se nespoléháme.
        val done = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        lateinit var timeout: Runnable
        fun settle(result: String) {
            if (!done.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            runCatching { finish(appContext, sessionId, result, replyText) }
                .onFailure { e -> WearLog.w(appContext, TAG, "finish failed for $sessionId: ${e.message}") }
            pending.finish()
        }
        timeout = Runnable { settle(RESULT_TIMEOUT) }
        handler.postDelayed(timeout, TIMEOUT_MS)

        if (intent.action == ACTION_APPROVE) {
            WearLog.i(appContext, TAG, "approve $sessionId answer=$answer")
            sendApprove(appContext, sessionId, answer!!) { result -> settle(result) }
        } else {
            WearLog.i(appContext, TAG, "reply $sessionId (${replyText!!.length} chars)")
            sendReply(appContext, sessionId, replyText) { result -> settle(result) }
        }
    }

    /**
     * Notifikaci ruš JEN když se odeslání povedlo. Dřív se rušila
     * bezpodmínečně, takže po "No phone connected" / "Send failed" dostal
     * uživatel chybovou vibraci a prázdné zápěstí — a nadiktovaný text byl
     * pryč bez možnosti odeslat ho znovu. Při chybě ji proto vrátíme zpět
     * i s tím, co uživatel napsal.
     */
    private fun finish(context: Context, sessionId: String, result: String, pendingText: String?) {
        WearLog.i(context, TAG, "send result for $sessionId: $result")
        if (result == RESULT_SENT) {
            WearNotifier.cancelSession(context, sessionId)
            WearHaptics.success(context)
            return
        }
        // Timeout není totéž co selhání: round-trip přes Play Services může
        // doběhnout až po něm a odpověď DORAZÍ. Tvrdit "neodesláno" by
        // uživatele posílalo odeslat to znovu — a Claude by dostal totéž
        // dvakrát. Text se do těla dává v obou případech, ať je co zopakovat
        // (nebo aspoň přečíst) na skutečném selhání.
        val unconfirmed = result == RESULT_TIMEOUT
        val message = if (unconfirmed) "může už být na cestě, neposílejte hned znovu" else result
        WearNotifier.notifySendFailure(context, sessionId, pendingText, message, unconfirmed)
        WearHaptics.error(context)
    }

    companion object {
        private const val TAG = "WearActionReceiver"
        const val ACTION_APPROVE = "com.clauderemote.wear.APPROVE"
        const val ACTION_REPLY = "com.clauderemote.wear.REPLY"
        // Broadcast receivers get roughly 10 s before the system considers
        // them stuck. Střílet fallback přesně na deadline znamená závod s
        // ANR, takže se vejdeme pod něj s rezervou.
        private const val TIMEOUT_MS = 7_500L
        private const val RESULT_SENT = "Sent"
        private const val RESULT_TIMEOUT = "Timeout"
    }
}
