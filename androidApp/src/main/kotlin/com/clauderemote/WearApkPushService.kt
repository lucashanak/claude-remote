package com.clauderemote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground-service wrapper for [WearApkPusher.checkAndPush]. A full watch
 * APK transfer over classic Bluetooth can legitimately take several
 * minutes (see WearApkPusher's size-scaled write timeout) — far longer
 * than a user will sit and watch Settings for. Without a foreground
 * service, Android's background execution limits silently suspended the
 * plain single-thread executor mid-transfer as soon as the phone screen
 * locked or the app backgrounded: no crash, no error, no completion —
 * exactly what was reported ("pořád nejde"), with the log showing
 * onChannelOpened fire on the watch and then nothing further at all.
 */
class WearApkPushService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification("Kontroluji verzi…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WearApkPusher.checkAndPush(
            applicationContext,
            onProgress = { msg -> updateNotification(msg) },
            onError = { msg -> finish("Chyba: $msg") },
        )
        return START_NOT_STICKY
    }

    private fun updateNotification(text: String) {
        if (text.startsWith("Odesláno")) {
            finish(text)
            return
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** Terminal state (success or error) — leave a dismissible notification behind, stop the service. */
    private fun finish(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text, ongoing = false))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun buildNotification(text: String, ongoing: Boolean = true): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Aktualizace hodinek")
            .setContentText(text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Aktualizace hodinek", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "wear_apk_push"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, WearApkPushService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
