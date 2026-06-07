package com.clauderemote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.clauderemote.util.FileLogger

/**
 * Posts the "Claude needs input" alert as its OWN notification — separate
 * from the KeepAliveService foreground notification. This fixes three
 * platform-level bugs the previous same-id channel-switching design hit:
 *
 *  1. A posted notification's channel CANNOT be changed by re-posting the
 *     same id — the alert silently stayed on the LOW `keepalive` channel,
 *     so it never made a sound or heads-up.
 *  2. A foreground-service notification id is always already visible, so
 *     re-posts don't re-alert.
 *  3. Ongoing/FGS notifications are NOT bridged to Wear OS — the watch
 *     never saw the alert (or its RemoteInput reply action).
 *
 * This notification is non-ongoing + autoCancel on the HIGH channel, posted
 * via NotificationManager directly — so it sounds, heads-ups, bridges to the
 * watch, and works even when KeepAliveService isn't running.
 */
object AlertNotifier {
    private const val ALERT_CHANNEL_ID = "claude_alerts"
    // Keep alert ids far away from KeepAliveService.NOTIFICATION_ID (1).
    private const val ALERT_ID_BASE = 0x10000
    private const val TAG = "AlertNotifier"

    private fun alertId(sessionId: String) = ALERT_ID_BASE + (sessionId.hashCode() and 0x7FFF)

    /** Idempotent; safe to call on every post (and when the service never ran). */
    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Claude Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications when Claude needs your attention"
            enableVibration(true)
            enableLights(true)
        }
        nm.createNotificationChannel(channel)
    }

    fun post(context: Context, sessionId: String, sessionTitle: String, hint: String) {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context, sessionId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("switch_to_session", sessionId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Inline reply (RemoteInput). Wear OS bridges this action to the
        // watch automatically and offers its built-in voice input, so the
        // user can answer Claude from the wrist without any watch app.
        // FLAG_MUTABLE is required for RemoteInput on Android 12+.
        val replyIntent = PendingIntent.getBroadcast(
            context, sessionId.hashCode(),
            Intent(context, ReplyReceiver::class.java).apply {
                putExtra(ReplyReceiver.EXTRA_SESSION_ID, sessionId)
            },
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val remoteInput = android.app.RemoteInput.Builder(ReplyReceiver.KEY_REPLY)
            .setLabel("Odpověď pro Claude…")
            .build()
        val replyAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(
                context, android.R.drawable.ic_menu_send
            ),
            "Odpovědět",
            replyIntent,
        ).addRemoteInput(remoteInput).build()

        val notification = Notification.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle(sessionTitle)
            .setContentText(hint)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(replyAction)
            .build()

        // notify() throws SecurityException when POST_NOTIFICATIONS was
        // denied (33+) — log instead of crashing the detection path.
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(alertId(sessionId), notification)
            FileLogger.log(TAG, "Alert posted for $sessionId: $sessionTitle — $hint")
        }.onFailure {
            FileLogger.log(TAG, "Alert post FAILED for $sessionId: ${it.message}")
        }
    }

    /** Cancel the session's alert (also clears the watch-side reply spinner). */
    fun clear(context: Context, sessionId: String) {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(alertId(sessionId))
        }
    }
}
