package com.clauderemote.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Jarvis F3 on the wrist: one merged notification "Jarvis · N" instead of one per session, shown only when the phone
 * routes alerts through Jarvis and his lease is live ([WearJarvisInfo.notify]). It vibrates only when the phone's
 * batcher let an alert vibrate (block, done or incident, navrh §3 bod 10: HNED); everything else updates it silently.
 *
 * The last [WearJarvisInfo.buzzAt] acted on is persisted: the listener process is killed routinely, and a re-delivered
 * payload must not vibrate twice for the same alert.
 */
object JarvisWatchNotifier {
    private const val CHANNEL_NOW = "jarvis_now"
    private const val CHANNEL_QUIET = "jarvis_quiet"
    private const val NOTIF_ID = 0x2A11_0001
    private const val PREFS = "jarvis_watch"
    private const val KEY_BUZZ_AT = "buzz_at"
    private const val TAG = "JarvisWatch"

    fun update(context: Context, info: WearJarvisInfo?) {
        val nm = NotificationManagerCompat.from(context)
        if (info == null || !info.notify || info.pending <= 0 || info.text.isBlank()) {
            nm.cancel(NOTIF_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val buzz = info.buzzAt > prefs.getLong(KEY_BUZZ_AT, 0L)
        if (buzz) prefs.edit().putLong(KEY_BUZZ_AT, info.buzzAt).apply()
        val open = PendingIntent.getActivity(
            context, NOTIF_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0),
        )
        val n = NotificationCompat.Builder(context, if (buzz) CHANNEL_NOW else CHANNEL_QUIET)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Jarvis · ${info.pending}")
            .setContentText(info.text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.text))
            .setContentIntent(open)
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
