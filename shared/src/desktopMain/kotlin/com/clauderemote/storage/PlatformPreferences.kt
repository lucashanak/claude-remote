package com.clauderemote.storage

import java.io.File
import java.util.Properties

actual class PlatformPreferences : KeyValueStore {
    private val propsFile = File(System.getProperty("user.home"), ".claude-remote/settings.properties")
    private val props = Properties()

    init {
        propsFile.parentFile?.mkdirs()
        if (propsFile.exists()) {
            propsFile.inputStream().use { props.load(it) }
        }
    }

    private fun save() {
        propsFile.outputStream().use { props.store(it, "Claude Remote Settings") }
    }

    override fun getString(key: String, default: String): String = props.getProperty(key, default)
    override fun putString(key: String, value: String) { props.setProperty(key, value); save() }
    // Already a blocking file write above — same implementation serves both.
    override fun putStringSync(key: String, value: String) { putString(key, value) }
    override fun getInt(key: String, default: Int): Int = props.getProperty(key)?.toIntOrNull() ?: default
    override fun putInt(key: String, value: Int) { props.setProperty(key, value.toString()); save() }
    override fun getBoolean(key: String, default: Boolean): Boolean = props.getProperty(key)?.toBooleanStrictOrNull() ?: default
    override fun putBoolean(key: String, value: Boolean) { props.setProperty(key, value.toString()); save() }
}
