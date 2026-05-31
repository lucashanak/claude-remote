package com.clauderemote.storage

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ConnectionType
import com.clauderemote.ui.theme.AppearanceState
import com.clauderemote.ui.theme.CRAccent
import com.clauderemote.ui.theme.CRDensity
import com.clauderemote.ui.theme.CRStatusViz
import com.clauderemote.ui.theme.CRTerminalScheme
import com.clauderemote.ui.theme.CRTerminalView
import com.clauderemote.ui.theme.CRVariant

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
            ClaudeMode.valueOf(prefs.getString("default_claude_mode", "YOLO"))
        } catch (e: Exception) { ClaudeMode.YOLO }
        set(value) = prefs.putString("default_claude_mode", value.name)

    var defaultClaudeModel: ClaudeModel
        get() = try {
            ClaudeModel.valueOf(prefs.getString("default_claude_model", "DEFAULT"))
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

    // When Claude is awaiting a choice (AskUserQuestion / permission prompt),
    // transiently switch the active session to the raw terminal so the user can
    // answer the real TUI widget. Render-only override — never touches the
    // persisted Raw/Chat view setting.
    var autoOpenTerminalOnPrompt: Boolean
        get() = prefs.getBoolean("auto_open_terminal_on_prompt", true)
        set(value) = prefs.putBoolean("auto_open_terminal_on_prompt", value)

    // Keep alive (Android)
    var keepAliveEnabled: Boolean
        get() = prefs.getBoolean("keep_alive_enabled", true)
        set(value) = prefs.putBoolean("keep_alive_enabled", value)

    // Theme: "system", "dark", "light"
    var themeMode: String
        get() = prefs.getString("theme_mode", "system")
        set(value) = prefs.putString("theme_mode", value)

    // Notifications
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(value) = prefs.putBoolean("notifications_enabled", value)

    // Keyboard shortcuts (custom bindings, format: "actionId=shortcut;...")
    var customShortcuts: String
        get() = prefs.getString("custom_shortcuts", "")
        set(value) = prefs.putString("custom_shortcuts", value)

    // Notifications - task complete
    var notifyOnTaskComplete: Boolean
        get() = prefs.getBoolean("notify_on_task_complete", true)
        set(value) = prefs.putBoolean("notify_on_task_complete", value)

    // Security
    var biometricLockEnabled: Boolean
        get() = prefs.getBoolean("biometric_lock_enabled", false)
        set(value) = prefs.putBoolean("biometric_lock_enabled", value)

    // Sunlight-readable mode: invert all pixel colors on the Activity root
    // (Compose + AndroidView children) via a hardware color-matrix layer.
    var invertColors: Boolean
        get() = prefs.getBoolean("invert_colors", false)
        set(value) = prefs.putBoolean("invert_colors", value)

    // Appearance (CRTheme)
    var crVariant: CRVariant
        get() = runCatching { CRVariant.valueOf(prefs.getString("cr_variant", "Classic")) }.getOrDefault(CRVariant.Classic)
        set(value) = prefs.putString("cr_variant", value.name)

    var crDensity: CRDensity
        get() = runCatching { CRDensity.valueOf(prefs.getString("cr_density", "Regular")) }.getOrDefault(CRDensity.Regular)
        set(value) = prefs.putString("cr_density", value.name)

    var crAccent: CRAccent
        get() = runCatching { CRAccent.valueOf(prefs.getString("cr_accent", "Sky")) }.getOrDefault(CRAccent.Sky)
        set(value) = prefs.putString("cr_accent", value.name)

    var crStatusViz: CRStatusViz
        get() = runCatching { CRStatusViz.valueOf(prefs.getString("cr_status_viz", "Pill")) }.getOrDefault(CRStatusViz.Pill)
        set(value) = prefs.putString("cr_status_viz", value.name)

    var crTerminalView: CRTerminalView
        get() = runCatching { CRTerminalView.valueOf(prefs.getString("cr_terminal_view", "Raw")) }.getOrDefault(CRTerminalView.Raw)
        set(value) = prefs.putString("cr_terminal_view", value.name)

    var crTerminalScheme: CRTerminalScheme
        get() = runCatching { CRTerminalScheme.valueOf(prefs.getString("cr_terminal_scheme", "Default")) }.getOrDefault(CRTerminalScheme.Default)
        set(value) = prefs.putString("cr_terminal_scheme", value.name)

    // Side panel width (desktop only, dp)
    var sidePanelWidthDp: Int
        get() = prefs.getInt("side_panel_width_dp", 220).coerceIn(160, 480)
        set(value) = prefs.putInt("side_panel_width_dp", value.coerceIn(160, 480))

    // Speech-to-text backend for dictation + voice mode.
    var sttEngine: com.clauderemote.model.SttEngine
        get() = runCatching {
            com.clauderemote.model.SttEngine.valueOf(
                prefs.getString("stt_engine", com.clauderemote.model.SttEngine.SERVER.name)
            )
        }.getOrDefault(com.clauderemote.model.SttEngine.SERVER)
        set(value) = prefs.putString("stt_engine", value.name)

    // Self-hosted OpenAI-compatible STT server (faster-whisper / Speaches).
    var sttServerUrl: String
        get() = prefs.getString("stt_server_url", "")
        set(value) = prefs.putString("stt_server_url", value.trim())

    var sttServerModel: String
        get() = prefs.getString("stt_server_model", "Systran/faster-whisper-large-v3")
        set(value) = prefs.putString("stt_server_model", value.trim())

    var sttServerApiKey: String
        get() = prefs.getString("stt_server_api_key", "")
        set(value) = prefs.putString("stt_server_api_key", value.trim())

    // Text-to-speech backend + server voice config (reuses the STT server
    // URL + API key — same Speaches instance serves both).
    var ttsEngine: com.clauderemote.model.TtsEngine
        get() = runCatching {
            com.clauderemote.model.TtsEngine.valueOf(
                prefs.getString("tts_engine", com.clauderemote.model.TtsEngine.SERVER.name)
            )
        }.getOrDefault(com.clauderemote.model.TtsEngine.SERVER)
        set(value) = prefs.putString("tts_engine", value.name)

    var ttsServerModel: String
        get() = prefs.getString("tts_server_model", "speaches-ai/piper-cs_CZ-jirka-medium")
        set(value) = prefs.putString("tts_server_model", value.trim())

    var ttsServerVoice: String
        get() = prefs.getString("tts_server_voice", "jirka")
        set(value) = prefs.putString("tts_server_voice", value.trim())

    // Google Cloud Text-to-Speech (used when ttsEngine == GOOGLE_CLOUD).
    var googleCloudApiKey: String
        get() = prefs.getString("gcloud_tts_api_key", "")
        set(value) = prefs.putString("gcloud_tts_api_key", value.trim())

    var googleCloudVoice: String
        get() = prefs.getString("gcloud_tts_voice", "cs-CZ-Wavenet-A")
        set(value) = prefs.putString("gcloud_tts_voice", value.trim())

    fun loadAppearance(): AppearanceState = AppearanceState(
        variant = crVariant,
        density = crDensity,
        accent = crAccent,
        statusViz = crStatusViz,
        terminalView = crTerminalView,
        terminalScheme = crTerminalScheme,
    )

    fun saveAppearance(state: AppearanceState) {
        crVariant = state.variant
        crDensity = state.density
        crAccent = state.accent
        crStatusViz = state.statusViz
        crTerminalView = state.terminalView
        crTerminalScheme = state.terminalScheme
    }
}
