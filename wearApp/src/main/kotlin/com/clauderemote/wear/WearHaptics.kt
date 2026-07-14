package com.clauderemote.wear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Wrist feedback for notification actions handled headlessly in
 * [WearActionReceiver] — with no UI open, the buzz is the only signal the
 * user gets that their tap on Ano/Ne/Odpovědět actually reached the phone
 * (tick) or didn't (error pattern).
 */
object WearHaptics {
    /** Short confirmation buzz — the reply/approval was sent. */
    fun success(context: Context) {
        val v = vibrator(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(40)
        }
    }

    /** Double-buzz error pattern — the send failed / no phone connected. */
    fun error(context: Context) {
        val v = vibrator(context) ?: return
        val pattern = longArrayOf(0, 100, 60, 100)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, -1)
        }
    }

    // VibratorManager is the non-deprecated path from API 31; the plain
    // VIBRATOR_SERVICE lookup is deprecated there but still the only option below.
    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
