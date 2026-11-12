package com.clauderemote.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * What the wrist does with the Jarvis part of one /sessions push. Pure, so it is unit-tested without a watch.
 *
 * The push arrives for EVERY session change, so reposting on each one brought a swiped-away notification straight
 * back (rev-f3-app MED5). Now: post only when the merged content changed ([key]); a swipe is remembered
 * ([dismissedKey]) until the content changes, i.e. a new alert; a vibrating alert ([WearJarvisInfo.buzzAt] newer than
 * the last one acted on) always shows.
 */
internal object JarvisWatchDecision {
    sealed interface Action {
        data object Cancel : Action
        data object Skip : Action
        data class Post(val buzz: Boolean) : Action
    }

    fun key(info: WearJarvisInfo): String = "${info.pending}\u0000${info.text}\u0000${info.askId.orEmpty()}\u0000${info.latestId.orEmpty()}"

    fun decide(info: WearJarvisInfo?, lastBuzzAt: Long, shownKey: String?, dismissedKey: String?): Action {
        if (info == null || !info.notify || info.pending <= 0 || info.text.isBlank()) return Action.Cancel
        if (info.buzzAt > lastBuzzAt) return Action.Post(buzz = true)
        val k = key(info)
        if (k == dismissedKey || k == shownKey) return Action.Skip
        return Action.Post(buzz = false)
    }
}

/**
 * Jarvis F3 on the wrist: one merged notification "Jarvis · N" instead of one per session, shown only when the phone
 * routes alerts through Jarvis and his lease is live, or (F5c) when his own sentences wait — those whatever the routing
 * ([WearJarvisInfo.notify]); his question (jask) gets "Ano" ([JarvisYesReceiver]). It vibrates only when the phone's
 * batcher let an alert vibrate (block, done or incident, navrh §3 bod 10: HNED); everything else updates it silently.
 *
 * The last [WearJarvisInfo.buzzAt] acted on, the content shown and the content swiped away are persisted: the
 * listener process is killed routinely, and a re-delivered payload must neither vibrate twice nor resurrect a swipe.
 */
object JarvisWatchNotifier {
    private const val CHANNEL_NOW = "jarvis_now"
    private const val CHANNEL_QUIET = "jarvis_quiet"
    private const val NOTIF_ID = 0x2A11_0001
    internal const val PREFS = "jarvis_watch"
    private const val KEY_BUZZ_AT = "buzz_at"
    private const val KEY_SHOWN = "shown_key"
    internal const val KEY_DISMISSED = "dismissed_key"
    internal const val EXTRA_KEY = "jarvis_key"
    internal const val EXTRA_UNDO_SERVER = "jarvis_undo_server"
    internal const val EXTRA_UNDO_TMUX = "jarvis_undo_tmux"
    internal const val EXTRA_ASK_ID = "jarvis_ask_id"
    private const val YES_REQ = 0x2A13_0001
    private const val UNDO_RESULT_ID = 0x2A11_0003
    private const val DECIDE_RESULT_ID = 0x2A11_0004
    private const val UNDO_REQ_BASE = 0x2A12_0000
    private const val MAX_UNDO = 2
    private const val TAG = "JarvisWatch"

    fun update(context: Context, info: WearJarvisInfo?) {
        val nm = NotificationManagerCompat.from(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val action = JarvisWatchDecision.decide(
            info, prefs.getLong(KEY_BUZZ_AT, 0L), prefs.getString(KEY_SHOWN, null), prefs.getString(KEY_DISMISSED, null),
        )
        when (action) {
            JarvisWatchDecision.Action.Skip -> return
            JarvisWatchDecision.Action.Cancel -> {
                nm.cancel(NOTIF_ID)
                prefs.edit().remove(KEY_SHOWN).apply()
                return
            }
            is JarvisWatchDecision.Action.Post -> post(context, nm, prefs, info!!, action.buzz)
        }
    }

    private fun post(
        context: Context,
        nm: NotificationManagerCompat,
        prefs: android.content.SharedPreferences,
        info: WearJarvisInfo,
        buzz: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(context)
        val key = JarvisWatchDecision.key(info)
        prefs.edit().apply {
            if (buzz) putLong(KEY_BUZZ_AT, info.buzzAt)
            putString(KEY_SHOWN, key)
            remove(KEY_DISMISSED)
        }.apply()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = PendingIntent.getActivity(
            context, NOTIF_ID,
            Intent(context, MainActivity::class.java)
                .putExtra(OpenPage.EXTRA_PAGE, OpenPage.PAGE_EXTRA_JARVIS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags,
        )
        val dismissed = PendingIntent.getBroadcast(
            context, NOTIF_ID, Intent(context, JarvisDismissReceiver::class.java).putExtra(EXTRA_KEY, key), flags,
        )
        val b = NotificationCompat.Builder(context, if (buzz) CHANNEL_NOW else CHANNEL_QUIET)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Jarvis · ${info.pending}")
            .setContentText(info.text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.text))
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setOnlyAlertOnce(!buzz)
            .setAutoCancel(true)
        // F5c: "Ano" only for Jarvis' question (jask) that is still the newest thing he said; the phone checks it again.
        info.askId?.takeIf { it.isNotBlank() }?.let { id ->
            val pi = PendingIntent.getBroadcast(
                context, YES_REQ, Intent(context, JarvisYesReceiver::class.java).putExtra(EXTRA_ASK_ID, id), flags,
            )
            b.addAction(NotificationCompat.Action.Builder(0, "Ano", pi).build())
        }
        // F4: "Vrátit" for each close Jarvis announced; the phone decides from closed.jsonl whether it still can.
        info.undo.take(MAX_UNDO).forEach { u ->
            val pi = PendingIntent.getBroadcast(
                context, UNDO_REQ_BASE + ((u.serverId + "\u0000" + u.tmux).hashCode() and 0xFFFF),
                Intent(context, JarvisUndoReceiver::class.java)
                    .putExtra(EXTRA_UNDO_SERVER, u.serverId)
                    .putExtra(EXTRA_UNDO_TMUX, u.tmux),
                flags,
            )
            b.addAction(NotificationCompat.Action.Builder(0, "Vrátit ${u.label}", pi).build())
        }
        nm.notify(NOTIF_ID, b.build())
        WearLog.i(context, TAG, "merged: ${info.pending} pending, buzz=$buzz")
    }

    /** What "Vrátit" did (the phone's sentence, or why it was not sent): one quiet notification, no actions. */
    fun postUndoResult(context: Context, text: String) = postResult(context, "Jarvis · vrácení", text, UNDO_RESULT_ID)

    /**
     * One quiet result notification, no actions: what "Vrátit" did, or what became of a proposal decided on the wrist
     * while the app was closed (review M5, its own id so the two do not replace each other).
     */
    fun postResult(context: Context, title: String, text: String, id: Int = DECIDE_RESULT_ID) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CHANNEL_QUIET)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    private fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_NOW) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_NOW, "Jarvis – hned", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 60, 80, 120)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_QUIET) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_QUIET, "Jarvis – tiše", NotificationManager.IMPORTANCE_LOW).apply {
                    enableVibration(false)
                },
            )
        }
    }
}

/** The merged Jarvis notification was swiped away: remember which content, so the next push does not bring it back. */
class JarvisDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(JarvisWatchNotifier.EXTRA_KEY) ?: return
        context.getSharedPreferences(JarvisWatchNotifier.PREFS, Context.MODE_PRIVATE).edit()
            .putString(JarvisWatchNotifier.KEY_DISMISSED, key).apply()
    }
}

/**
 * "Vrátit" on the merged Jarvis notification (F4): hands the close to the phone on /jarvis-undo, with no Activity
 * (a background activity start is a violation on API 34+, see [WearActionReceiver]). The phone answers on
 * /jarvis-undo-result; only a send that did not happen is said here. goAsync keeps the process up for the two Play
 * Services round trips, with a timeout under the receiver's ~10 s budget.
 */
class JarvisUndoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val serverId = intent.getStringExtra(JarvisWatchNotifier.EXTRA_UNDO_SERVER)
        val tmux = intent.getStringExtra(JarvisWatchNotifier.EXTRA_UNDO_TMUX)
        if (serverId.isNullOrBlank() || tmux.isNullOrBlank()) return
        val pending = goAsync()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        lateinit var timeout: Runnable
        fun settle(result: String) {
            if (!done.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            WearLog.i(appContext, "JarvisUndo", "undo $tmux sent: $result")
            if (result == "Sent") {
                WearHaptics.success(appContext)
            } else if (result != "Timeout") {
                // a timeout may still arrive: the phone's answer says so, a second tap would only be refused as taken
                JarvisWatchNotifier.postUndoResult(appContext, "Vrácení se neodeslalo: $result")
                WearHaptics.error(appContext)
            }
            pending.finish()
        }
        timeout = Runnable { settle("Timeout") }
        handler.postDelayed(timeout, 7_500L)
        sendJarvisUndo(appContext, serverId, tmux) { r -> settle(r) }
    }
}

/**
 * "Ano" of Jarvis' question on the wrist (F5c): hands it to the phone on /jarvis-yes, no Activity (like
 * [JarvisUndoReceiver]). The phone answers on /jarvis-yes-result; only a send that did not happen is said here.
 */
class JarvisYesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val id = intent.getStringExtra(JarvisWatchNotifier.EXTRA_ASK_ID)
        if (id.isNullOrBlank()) return
        val pending = goAsync()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        lateinit var timeout: Runnable
        fun settle(result: String) {
            if (!done.compareAndSet(false, true)) return
            handler.removeCallbacks(timeout)
            WearLog.i(appContext, "JarvisYes", "yes $id sent: $result")
            if (result != "Sent" && result != "Timeout") {
                JarvisWatchNotifier.postResult(appContext, "Jarvis · ano", "Neodesláno: $result")
                WearHaptics.error(appContext)
            }
            pending.finish()
        }
        timeout = Runnable { settle("Timeout") }
        handler.postDelayed(timeout, 7_500L)
        sendJarvisYes(appContext, id) { r -> settle(r) }
    }
}
