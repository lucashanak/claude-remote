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

    fun key(info: WearJarvisInfo): String = "${info.pending}\u0000${info.text}"

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
 * routes alerts through Jarvis and his lease is live ([WearJarvisInfo.notify]). It vibrates only when the phone's
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
            context, NOTIF_ID, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), flags,
        )
        val dismissed = PendingIntent.getBroadcast(
            context, NOTIF_ID, Intent(context, JarvisDismissReceiver::class.java).putExtra(EXTRA_KEY, key), flags,
        )
        val n = NotificationCompat.Builder(context, if (buzz) CHANNEL_NOW else CHANNEL_QUIET)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Jarvis · ${info.pending}")
            .setContentText(info.text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.text))
            .setContentIntent(open)
            .setDeleteIntent(dismissed)
            .setOnlyAlertOnce(!buzz)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
        WearLog.i(context, TAG, "merged: ${info.pending} pending, buzz=$buzz")
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
