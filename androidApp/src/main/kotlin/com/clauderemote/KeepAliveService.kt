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
        /**
         * Id of the quiet foreground-service notification ONLY. "Claude
         * needs input" alerts are posted as SEPARATE notifications by
         * [AlertNotifier] — re-posting this id on a different channel never
         * worked (a posted notification's channel can't change), kept the
         * alert silent, and ongoing/FGS notifications don't bridge to Wear.
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

        /** Call from onResume — screen is on, CPU is awake, no wake lock needed */
        fun onAppForeground() { instance?.setWakeLockEnabled(false) }

        /** Call from onPause — going to background, need wake lock to receive SSH data */
        fun onAppBackground() { instance?.setWakeLockEnabled(true) }

        val isRunning: Boolean get() = instance != null
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /** Last description passed via [updateDescription]. */
    @Volatile private var currentDescription: String = "Active session"

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
            // The HIGH-importance alert channel is owned by AlertNotifier.
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

    /** Quietly update the persistent notification's description. */
    fun updateNotification(description: String) {
        currentDescription = description
        startForeground(NOTIFICATION_ID, buildNotification(description))
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
        // WIFI_MODE_FULL_HIGH_PERF pins the WiFi radio out of power-save for the
        // app's entire background lifetime — by this service's own measurement
        // the single biggest battery cost here, for little benefit: the SSH
        // keepalive + partial wakelock already keep the link warm, and on
        // cellular it does nothing at all. Dropped. A normal WIFI_MODE_FULL_LOW_LATENCY
        // is still overkill; we simply rely on the keepalive to wake the radio
        // when there's actually traffic.
        FileLogger.log(TAG, "WifiLock skipped (high-perf lock removed to save battery)")
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        FileLogger.log(TAG, "WifiLock released")
    }

    /** Switch wake lock + WiFi lock on/off based on app visibility.
     *  When app is in foreground the screen is already on — no locks needed.
     *  When app goes to background we re-acquire so SSH keeps flowing.
     *
     *  WIFI_MODE_FULL_HIGH_PERF is the single biggest battery cost in this
     *  service; releasing it while the user is actively looking at the screen
     *  (WiFi radio stays awake anyway) recovers the largest share. */
    fun setWakeLockEnabled(enabled: Boolean) {
        if (enabled) {
            acquireWakeLock()
            acquireWifiLock()
        } else {
            releaseWakeLock()
            releaseWifiLock()
        }
        FileLogger.log(TAG, "Locks enabled=$enabled")
    }
}
