package com.clauderemote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.clauderemote.util.FileLogger

class KeepAliveService : Service() {
    companion object {
        private const val CHANNEL_ID = "keepalive"
        private const val ALERT_CHANNEL_ID = "claude_alerts"
        /**
         * Single notification id used for BOTH the quiet foreground-service
         * notification and the "Claude needs input" alert. Switching channels
         * on the same id gives the user ONE notification that merely
         * upgrades to HIGH-importance alerting when needed — rather than two
         * concurrent status-bar entries (the old "dimmed gear" + "loud alert"
         * pair the user complained about).
         */
        private const val NOTIFICATION_ID = 1
        private const val TAG = "KeepAlive"

        private var instance: KeepAliveService? = null

        fun start(ctx: Context, description: String) {
            val intent = Intent(ctx, KeepAliveService::class.java).apply {
                putExtra("description", description)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }

        fun updateDescription(description: String) {
            instance?.updateNotification(description)
        }

        fun sendAlert(sessionId: String, sessionTitle: String, hint: String) {
            instance?.postAlert(sessionId, sessionTitle, hint)
        }

        fun clearAlert(sessionId: String) {
            instance?.dismissAlert(sessionId)
        }

        /** Call from onResume — screen is on, CPU is awake, no wake lock needed */
        fun onAppForeground() { instance?.setWakeLockEnabled(false) }

        /** Call from onPause — going to background, need wake lock to receive SSH data */
        fun onAppBackground() { instance?.setWakeLockEnabled(true) }

        val isRunning: Boolean get() = instance != null
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /** Last description passed via [updateDescription] — restored when an alert is dismissed. */
    @Volatile private var currentDescription: String = "Active session"
    /** Session id whose alert is currently rendered in our single notification, or null. */
    @Volatile private var currentAlertSessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        acquireWakeLock()
        acquireWifiLock()
        FileLogger.log(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val desc = intent?.getStringExtra("description") ?: "Active session"
        currentDescription = desc
        startForeground(NOTIFICATION_ID, buildNotification(desc))
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        releaseWifiLock()
        instance = null
        FileLogger.log(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val keepaliveChannel = NotificationChannel(
                CHANNEL_ID,
                "Active Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the app alive during active Claude sessions"
                setShowBadge(false)
            }
            nm.createNotificationChannel(keepaliveChannel)

            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Claude Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when Claude needs your attention"
                enableVibration(true)
                enableLights(true)
            }
            nm.createNotificationChannel(alertChannel)
        }
    }

    private fun buildNotification(description: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Claude Remote")
            .setContentText(description)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Quietly update the persistent notification's description. If an alert
     * is currently showing, we keep the alert content — the user hasn't
     * acknowledged it yet.
     */
    fun updateNotification(description: String) {
        currentDescription = description
        if (currentAlertSessionId != null) return
        startForeground(NOTIFICATION_ID, buildNotification(description))
    }

    /**
     * Raise an attention-grabbing alert by upgrading the SAME foreground-service
     * notification (same id) to the HIGH-importance `claude_alerts` channel.
     * The user sees one notification that changes appearance, not two
     * concurrent entries.
     *
     * Must stay `setOngoing(true)` and NOT `setAutoCancel(true)` — this
     * notification is tied to a foreground service; cancelling it would
     * violate the FGS contract.
     */
    fun postAlert(sessionId: String, sessionTitle: String, hint: String) {
        val openIntent = PendingIntent.getActivity(
            this, sessionId.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("switch_to_session", sessionId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        @Suppress("DEPRECATION")
        val notification = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(sessionTitle)
            .setContentText(hint)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setDefaults(Notification.DEFAULT_ALL)
            .setFullScreenIntent(openIntent, false)
            .build()

        currentAlertSessionId = sessionId
        startForeground(NOTIFICATION_ID, notification)
        FileLogger.log(TAG, "Alert sent: $sessionTitle — $hint")
    }

    /**
     * Revert the single notification back to the quiet keep-alive appearance
     * after the user has acknowledged the alert (tapped, switched tabs, or
     * typed into the session).
     */
    fun dismissAlert(sessionId: String) {
        if (currentAlertSessionId != sessionId) return
        currentAlertSessionId = null
        startForeground(NOTIFICATION_ID, buildNotification(currentDescription))
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clauderemote:keepalive")
        }
        wakeLock?.let { if (!it.isHeld) it.acquire(30 * 60 * 1000L) } // 30 min safety timeout
        FileLogger.log(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        FileLogger.log(TAG, "WakeLock released")
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (wifiLock == null) {
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "clauderemote:wifi")
        }
        wifiLock?.let { if (!it.isHeld) it.acquire() }
        FileLogger.log(TAG, "WifiLock acquired")
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        FileLogger.log(TAG, "WifiLock released")
    }

    /** Switch wake lock on/off based on app visibility.
     *  When app is in foreground the screen is already on — no wake lock needed.
     *  When app goes to background we need it to keep receiving SSH data. */
    fun setWakeLockEnabled(enabled: Boolean) {
        if (enabled) acquireWakeLock() else releaseWakeLock()
        FileLogger.log(TAG, "WakeLock enabled=$enabled")
    }
}
