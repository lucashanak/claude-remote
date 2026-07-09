package com.clauderemote.wear

import android.content.Context

/** Default-on toggle for auto-speaking a session's message on the watch
 *  when it flips to WAITING_FOR_INPUT/APPROVAL_NEEDED (see
 *  WearDataListenerService). Plain SharedPreferences — no settings screen
 *  yet, toggled from a Switch on SessionListScreen. */
object AutoSpeakPrefs {
    private const val PREFS = "wear_prefs"
    private const val KEY_ENABLED = "auto_speak_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
