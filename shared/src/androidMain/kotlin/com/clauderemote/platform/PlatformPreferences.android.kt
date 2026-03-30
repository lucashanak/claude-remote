package com.clauderemote.storage

import android.content.Context
import android.content.SharedPreferences

actual class PlatformPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("claude_remote", Context.MODE_PRIVATE)

    actual fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default
    actual fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    actual fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    actual fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    actual fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    actual fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
}
