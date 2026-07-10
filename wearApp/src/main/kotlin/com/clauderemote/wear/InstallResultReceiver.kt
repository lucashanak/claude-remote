package com.clauderemote.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat

/**
 * PackageInstaller session result callback (see WearUpdater.installApk).
 * STATUS_PENDING_USER_ACTION is the normal path — every sideloaded install/
 * update needs one confirmation tap, even for a self-update; this just
 * surfaces that system confirmation UI.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirmIntent?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Wear update installed")
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Wear update failed: status=$status message=$msg")
            }
        }
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
    }
}
