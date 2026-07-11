package com.clauderemote.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground-service wrapper for [WearUpdater.download]/[WearUpdater.install],
 * run as two separate user-triggered steps (see MainActivity's UpdateSection —
 * "Zkontrolovat" → "Stáhnout" → "Nainstalovat", three taps). The download
 * rides whatever connection Wear OS gives the watch (own Wi-Fi/LTE, or
 * relayed through the paired phone's Bluetooth) and can take a while for a
 * ~22 MB APK. As a plain background executor task this was vulnerable to
 * Wear OS's background-execution limits — even more aggressive than phone
 * Android — suspending it once the watch went to ambient/screen-off,
 * silently stalling the download with no error and no completion. Uses a
 * separate, LOW-importance notification channel from
 * [InstallResultReceiver]'s — that one is HIGH importance because it needs
 * to actually grab attention for the "tap to confirm install" step; sharing
 * a channel would have pinned whichever importance got created first onto
 * both.
 */
class WearUpdateService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Zpracovávám…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val version = intent?.getStringExtra(EXTRA_VERSION)
        if (version == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                WearUpdater.download(
                    applicationContext, version, url,
                    onProgress = { msg -> updateNotification(msg, terminalPrefix = "Staženo") },
                    onError = { msg -> finish("Chyba: $msg") },
                )
            }
            ACTION_INSTALL -> {
                WearUpdater.install(
                    applicationContext, version,
                    onProgress = { msg -> updateNotification(msg, terminalPrefix = "Potvrďte") },
                    onError = { msg -> finish("Chyba: $msg") },
                )
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun updateNotification(text: String, terminalPrefix: String) {
        if (text.startsWith(terminalPrefix)) {
            // Terminal state for this step — for install, InstallResultReceiver
            // takes over from here with its own (HIGH-importance) confirm
            // notification.
            finish(text)
            return
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun finish(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text, ongoing = false))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun buildNotification(text: String, ongoing: Boolean = true): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Aktualizace")
            .setContentText(text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Aktualizace — průběh", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "wear_update_progress"
        private const val NOTIFICATION_ID = 3001
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_URL = "url"
        private const val ACTION_DOWNLOAD = "download"
        private const val ACTION_INSTALL = "install"

        fun startDownload(context: Context, version: String, downloadUrl: String) {
            val intent = Intent(context, WearUpdateService::class.java).apply {
                putExtra(EXTRA_ACTION, ACTION_DOWNLOAD)
                putExtra(EXTRA_VERSION, version)
                putExtra(EXTRA_URL, downloadUrl)
            }
            context.startForegroundService(intent)
        }

        fun startInstall(context: Context, version: String) {
            val intent = Intent(context, WearUpdateService::class.java).apply {
                putExtra(EXTRA_ACTION, ACTION_INSTALL)
                putExtra(EXTRA_VERSION, version)
            }
            context.startForegroundService(intent)
        }
    }
}
