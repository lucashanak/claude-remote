package com.clauderemote.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat

/**
 * PackageInstaller session result callback (see WearUpdater.installApk).
 * STATUS_PENDING_USER_ACTION is the normal path — every sideloaded install/
 * update needs one confirmation tap, even for a self-update.
 *
 * Does NOT call context.startActivity() directly on the confirmation intent
 * — confirmed on a real device that if the watch screen is asleep/ambient
 * when this fires (e.g. an update pushed from the phone while nobody's
 * looking at the watch), nothing becomes visible and there's no way back to
 * it afterward. That's also a background-activity-start violation on
 * API 34+ regardless of screen state (this receiver isn't on the BAL
 * exemption list). A notification-tap PendingIntent IS a documented BAL
 * exemption and needs no screen-wake trickery — the user just confirms
 * whenever they next glance at the watch.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        WearLog.i(context, TAG, "onReceive status=$status")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmIntent != null) postConfirmNotification(context, confirmIntent)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                WearLog.i(context, TAG, "Wear update installed")
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                WearLog.w(context, TAG, "Wear update failed: status=$status message=$msg")
            }
        }
    }

    private fun postConfirmNotification(context: Context, confirmIntent: Intent) {
        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Aktualizace", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Aktualizace hodinek")
            .setContentText("Klepnutím potvrďte instalaci")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
        WearLog.i(context, TAG, "Posted install-confirm notification")
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
        private const val CHANNEL_ID = "wear_update"
        private const val NOTIFICATION_ID = 1001
    }
}
