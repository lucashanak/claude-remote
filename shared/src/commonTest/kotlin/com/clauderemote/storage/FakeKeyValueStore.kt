package com.clauderemote.storage

/**
 * In-memory [KeyValueStore] for tests. Deliberately mirrors the DESKTOP actual's
 * semantics (everything is stringly-typed in a java Properties file, ints/booleans
 * are re-parsed on read and fall back to the default when unparseable) because
 * that is the weaker of the two platforms — a test that passes here also passes
 * against Android's typed SharedPreferences.
 *
 * NEVER touches disk: the real desktop [PlatformPreferences] writes to
 * `~/.claude-remote/settings.properties`, so constructing it in a test would read
 * and overwrite the developer's actual app settings.
 */
class FakeKeyValueStore(
    initial: Map<String, String> = emptyMap(),
) : KeyValueStore {

    val map: MutableMap<String, String> = initial.toMutableMap()

    /** How many times the durable/blocking write path was used (see [putStringSync]). */
    var syncWriteCount: Int = 0
        private set

    /** Keys written through the durable path, in call order. */
    val syncWrittenKeys: MutableList<String> = mutableListOf()

    override fun getString(key: String, default: String): String = map[key] ?: default

    override fun putString(key: String, value: String) { map[key] = value }

    override fun putStringSync(key: String, value: String) {
        syncWriteCount++
        syncWrittenKeys.add(key)
        map[key] = value
    }

    override fun getInt(key: String, default: Int): Int = map[key]?.toIntOrNull() ?: default

    override fun putInt(key: String, value: Int) { map[key] = value.toString() }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        map[key]?.toBooleanStrictOrNull() ?: default

    override fun putBoolean(key: String, value: Boolean) { map[key] = value.toString() }
}
