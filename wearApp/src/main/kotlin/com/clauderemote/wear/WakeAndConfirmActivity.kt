package com.clauderemote.wear

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.IntentCompat

/**
 * Tiny trampoline Activity — its only job is to wake the screen (if
 * asleep/ambient) and immediately hand off to the real PackageInstaller
 * confirmation Intent, then finish. Launched via a notification's
 * setFullScreenIntent, which the system fires immediately (waking the
 * screen) when it's off/locked, or degrades to a normal heads-up
 * notification when the screen's already on/in use — that's standard,
 * documented system behavior, not something this code manages itself.
 *
 * Wakes via Activity.setTurnScreenOn/setShowWhenLocked (API 27+, no
 * PowerManager wake lock needed — those screen-wake wake lock flags are
 * deprecated in favor of exactly this). Activity→Activity startActivity()
 * here is never a background-activity-start violation, unlike calling
 * startActivity() directly from a BroadcastReceiver (see
 * InstallResultReceiver's own history — that's exactly the bug this exists
 * to route around).
 */
class WakeAndConfirmActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val confirmIntent = IntentCompat.getParcelableExtra(intent, EXTRA_CONFIRM_INTENT, Intent::class.java)
        if (confirmIntent != null) {
            runCatching { startActivity(confirmIntent) }
        }
        finish()
    }

    companion object {
        const val EXTRA_CONFIRM_INTENT = "confirm_intent"
    }
}
