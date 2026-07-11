package com.clauderemote.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat

/**
 * PackageInstaller session result callback (see WearUpdater.installApk).
 * STATUS_PENDING_USER_ACTION is the normal path — every sideloaded install/
 * update needs one confirmation tap, even for a self-update.
 *
 * Does NOT call context.startActivity() directly on the confirmation intent
 * — confirmed on a real device that if the watch screen is asleep/ambient
 * when this fires, nothing becomes visible. That's also a
 * background-activity-start violation on API 34+ regardless of screen state
 * (this receiver isn't on the BAL exemption list). Instead posts a
 * notification with a tap PendingIntent (a documented BAL exemption) AND —
 * researched specifically because USE_FULL_SCREEN_INTENT's Android 14
 * auto-revocation is enforced by Google Play's *installer* denying the
 * permission for apps that fail its calling/alarm review, not by the OS
 * itself; a sideloaded install never goes through that denial path, so the
 * permission should stay in its default-granted state — a
 * setFullScreenIntent pointed at a trampoline Activity
 * ([WakeAndConfirmActivity]) that actually wakes the screen. Gated behind
 * canUseFullScreenIntent() in case an OEM skin (this targets Xiaomi/HyperOS,
 * known for aggressive background/permission policy) diverges from AOSP
 * defaults; the plain tap notification is always posted regardless, so
 * there's no dead end either way.
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
        val tapPendingIntent = PendingIntent.getActivity(
            context, 0, confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Aktualizace hodinek")
            .setContentText("Klepnutím potvrďte instalaci")
            .setContentIntent(tapPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)

        // canUseFullScreenIntent() only exists from API 34 — below that the
        // Android-14-era auto-revocation this whole mechanism is about
        // doesn't apply in the first place, so treat as always allowed.
        val canUseFsi = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || nm.canUseFullScreenIntent()
        if (canUseFsi) {
            val wakeIntent = Intent(context, WakeAndConfirmActivity::class.java).apply {
                putExtra(WakeAndConfirmActivity.EXTRA_CONFIRM_INTENT, confirmIntent)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val fullScreenPendingIntent = PendingIntent.getActivity(
                context, 1, wakeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
        } else {
            WearLog.w(context, TAG, "canUseFullScreenIntent() false — falling back to tap-only notification")
        }
        nm.notify(NOTIFICATION_ID, builder.build())
        WearLog.i(context, TAG, "Posted install-confirm notification (fsi=$canUseFsi)")
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
        private const val CHANNEL_ID = "wear_update"
        private const val NOTIFICATION_ID = 1001
    }
}
