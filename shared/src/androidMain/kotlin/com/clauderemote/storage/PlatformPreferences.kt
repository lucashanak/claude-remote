package com.clauderemote.storage

import android.content.Context
import android.content.SharedPreferences

actual class PlatformPreferences(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("claude_remote", Context.MODE_PRIVATE)

    override fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun putStringSync(key: String, value: String) { prefs.edit().putString(key, value).commit() }
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
}
