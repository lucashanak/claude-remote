package com.clauderemote.storage

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ConnectionType

class AppSettings(private val prefs: PlatformPreferences) {

    // Terminal
    var terminalFontSize: Int
        get() = prefs.getInt("terminal_font_size", 14)
        set(value) = prefs.putInt("terminal_font_size", value.coerceIn(8, 32))

    var terminalColorScheme: String
        get() = prefs.getString("terminal_color_scheme", "default")
        set(value) = prefs.putString("terminal_color_scheme", value)

    var terminalScrollback: Int
        get() = prefs.getInt("terminal_scrollback", 10000)
        set(value) = prefs.putInt("terminal_scrollback", value)

    // Claude defaults
    var defaultClaudeMode: ClaudeMode
        get() = try {
            ClaudeMode.valueOf(prefs.getString("default_claude_mode", "NORMAL"))
        } catch (e: Exception) { ClaudeMode.NORMAL }
        set(value) = prefs.putString("default_claude_mode", value.name)

    var defaultClaudeModel: ClaudeModel
        get() = try {
            ClaudeModel.valueOf(prefs.getString("default_claude_model", "SONNET"))
        } catch (e: Exception) { ClaudeModel.DEFAULT }
        set(value) = prefs.putString("default_claude_model", value.name)

    // SSH defaults
    var defaultSshPort: Int
        get() = prefs.getInt("default_ssh_port", 22)
        set(value) = prefs.putInt("default_ssh_port", value)

    var defaultConnectionType: ConnectionType
        get() = try {
            ConnectionType.valueOf(prefs.getString("default_connection_type", "SSH"))
        } catch (e: Exception) { ConnectionType.SSH }
        set(value) = prefs.putString("default_connection_type", value.name)

    var sshAutoReconnect: Boolean
        get() = prefs.getBoolean("ssh_auto_reconnect", true)
        set(value) = prefs.putBoolean("ssh_auto_reconnect", value)

    var sshConnectTimeout: Int
        get() = prefs.getInt("ssh_connect_timeout", 15)
        set(value) = prefs.putInt("ssh_connect_timeout", value)

    // UI
    var suppressSystemKeyboard: Boolean
        get() = prefs.getBoolean("suppress_system_keyboard", true)
        set(value) = prefs.putBoolean("suppress_system_keyboard", value)

    var hapticFeedback: Boolean
        get() = prefs.getBoolean("haptic_feedback", false)
        set(value) = prefs.putBoolean("haptic_feedback", value)

    // Keep alive (Android)
    var keepAliveEnabled: Boolean
        get() = prefs.getBoolean("keep_alive_enabled", true)
        set(value) = prefs.putBoolean("keep_alive_enabled", value)
}
