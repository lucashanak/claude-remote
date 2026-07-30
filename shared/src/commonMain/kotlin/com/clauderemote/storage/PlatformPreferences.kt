package com.clauderemote.storage

/**
 * Platform-agnostic key/value persistence contract. Exists so the storage layer
 * ([AppSettings], [ServerStorage], [SessionStorage]) can be unit-tested against
 * an in-memory implementation — the [PlatformPreferences] actuals have different
 * constructors per platform and the desktop one writes to the real user home.
 */
interface KeyValueStore {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    /** Blocking write that's durable on disk before returning — use only where losing the
     *  write to a killed process would resurrect state the user just deleted (see
     *  SessionStorage.remove). Everything else should use [putString]'s fire-and-forget write. */
    fun putStringSync(key: String, value: String)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

expect class PlatformPreferences : KeyValueStore
