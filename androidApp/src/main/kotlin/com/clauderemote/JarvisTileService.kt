package com.clauderemote

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.clauderemote.session.QuickTarget

/**
 * Quick-settings tile "Jarvis": a tap collapses the shade and opens the app on Jarvis (his tab, or the attach to the
 * running session; when he does not run, the notice and the Velín — all decided in App).
 *
 * Android 14+ (API 34) accepts only the PendingIntent form of startActivityAndCollapse for an app targeting 34+
 * (targetSdk is 35); older releases have only the Intent form. On a locked secure device the launch waits for the
 * unlock (unlockAndRun), otherwise a tap on the lock screen would do nothing.
 */
class JarvisTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        // Shown as ACTIVE: the default INACTIVE greys the tile out on some OEM themes, as if it did not work.
        qsTile?.apply { state = Tile.STATE_ACTIVE; updateTile() }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openJarvis() } else openJarvis()
    }

    private fun openJarvis() {
        val intent = MainActivity.quickAccessIntent(this, QuickTarget.JARVIS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val REQUEST_CODE = 0x4A41 // "JA"
    }
}
