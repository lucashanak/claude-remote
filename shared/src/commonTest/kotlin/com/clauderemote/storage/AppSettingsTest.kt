package com.clauderemote.storage

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SttEngine
import com.clauderemote.model.TtsEngine
import com.clauderemote.ui.theme.AppearanceState
import com.clauderemote.ui.theme.CRAccent
import com.clauderemote.ui.theme.CRDensity
import com.clauderemote.ui.theme.CRStatusViz
import com.clauderemote.ui.theme.CRTerminalScheme
import com.clauderemote.ui.theme.CRTerminalView
import com.clauderemote.ui.theme.CRVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [AppSettings] over a [FakeKeyValueStore]. Two things matter here
 * beyond plain round-tripping:
 *  - [AppSettings.installId] must be generated exactly once and stay stable, and
 *  - every enum-valued setting must survive an unparseable stored string, which
 *    is what happens when settings written by a newer app version are read by an
 *    older one (or an enum constant gets renamed).
 */
class AppSettingsTest {

    private fun settings(vararg stored: Pair<String, String>): Pair<AppSettings, FakeKeyValueStore> {
        val store = FakeKeyValueStore(stored.toMap())
        return AppSettings(store) to store
    }

    // --- defaults on an empty store ---

    @Test
    fun emptyStore_returnsDocumentedDefaults() {
        val (s, _) = settings()
        assertEquals(14, s.terminalFontSize)
        assertEquals("default", s.terminalColorScheme)
        assertEquals(10000, s.terminalScrollback)
        assertEquals(ClaudeMode.YOLO, s.defaultClaudeMode)
        assertEquals(ClaudeModel.DEFAULT, s.defaultClaudeModel)
        assertEquals(22, s.defaultSshPort)
        assertEquals(ConnectionType.SSH, s.defaultConnectionType)
        assertEquals(true, s.sshAutoReconnect)
        assertEquals(15, s.sshConnectTimeout)
        assertEquals(true, s.suppressSystemKeyboard)
        assertEquals(false, s.hapticFeedback)
        assertEquals(true, s.autoOpenTerminalOnPrompt)
        assertEquals(true, s.keepAliveEnabled)
        assertEquals("system", s.themeMode)
        assertEquals(true, s.notificationsEnabled)
        assertEquals("", s.customShortcuts)
        assertEquals(true, s.notifyOnTaskComplete)
        assertEquals(false, s.biometricLockEnabled)
        assertEquals(false, s.invertColors)
        assertEquals(220, s.sidePanelWidthDp)
        assertEquals(4000, s.dictationSilenceMs)
        assertEquals(false, s.wakeWordEnabled)
        assertEquals("claude", s.wakeWord)
        assertEquals("SERVER", s.wakeEngine)
        assertEquals(false, s.llmSummaryEnabled)
        assertEquals(false, s.llmSummaryPhone)
        assertEquals("SENTENCE", s.llmSummaryLength)
    }

    @Test
    fun emptyStore_appearanceDefaults() {
        val (s, _) = settings()
        assertEquals(
            AppearanceState(
                variant = CRVariant.Classic,
                density = CRDensity.Regular,
                accent = CRAccent.Sky,
                statusViz = CRStatusViz.Pill,
                terminalView = CRTerminalView.Raw,
                terminalScheme = CRTerminalScheme.Default,
            ),
            s.loadAppearance(),
        )
    }

    @Test
    fun emptyStore_voiceDefaults() {
        val (s, _) = settings()
        assertEquals(SttEngine.SERVER, s.sttEngine)
        assertEquals("", s.sttServerUrl)
        assertEquals("Systran/faster-whisper-large-v3", s.sttServerModel)
        assertEquals("", s.sttServerApiKey)
        assertEquals(TtsEngine.SERVER, s.ttsEngine)
        assertEquals("speaches-ai/piper-cs_CZ-jirka-medium", s.ttsServerModel)
        assertEquals("jirka", s.ttsServerVoice)
        assertEquals(100, s.ttsSpeechRatePct)
        assertEquals(100, s.ttsPitchPct)
        assertEquals("", s.ttsSystemVoice)
        assertEquals("cs-CZ-Wavenet-A", s.googleCloudVoice)
        assertEquals("Adrian", s.sonioxTtsVoice)
    }

    // --- round-trips ---

    @Test
    fun everyIntSetting_roundTrips() {
        val (s, _) = settings()
        s.terminalFontSize = 20; assertEquals(20, s.terminalFontSize)
        s.terminalScrollback = 5000; assertEquals(5000, s.terminalScrollback)
        s.defaultSshPort = 2222; assertEquals(2222, s.defaultSshPort)
        s.sshConnectTimeout = 30; assertEquals(30, s.sshConnectTimeout)
        s.sidePanelWidthDp = 300; assertEquals(300, s.sidePanelWidthDp)
        s.ttsSpeechRatePct = 125; assertEquals(125, s.ttsSpeechRatePct)
        s.ttsPitchPct = 90; assertEquals(90, s.ttsPitchPct)
        s.dictationSilenceMs = 2500; assertEquals(2500, s.dictationSilenceMs)
    }

    @Test
    fun everyBooleanSetting_roundTrips() {
        val (s, _) = settings()
        s.sshAutoReconnect = false; assertEquals(false, s.sshAutoReconnect)
        s.suppressSystemKeyboard = false; assertEquals(false, s.suppressSystemKeyboard)
        s.hapticFeedback = true; assertEquals(true, s.hapticFeedback)
        s.autoOpenTerminalOnPrompt = false; assertEquals(false, s.autoOpenTerminalOnPrompt)
        s.keepAliveEnabled = false; assertEquals(false, s.keepAliveEnabled)
        s.notificationsEnabled = false; assertEquals(false, s.notificationsEnabled)
        s.notifyOnTaskComplete = false; assertEquals(false, s.notifyOnTaskComplete)
        s.biometricLockEnabled = true; assertEquals(true, s.biometricLockEnabled)
        s.invertColors = true; assertEquals(true, s.invertColors)
        s.wakeWordEnabled = true; assertEquals(true, s.wakeWordEnabled)
        s.llmSummaryEnabled = true; assertEquals(true, s.llmSummaryEnabled)
        s.llmSummaryPhone = true; assertEquals(true, s.llmSummaryPhone)
    }

    @Test
    fun everyStringSetting_roundTrips() {
        val (s, _) = settings()
        s.terminalColorScheme = "solarized"; assertEquals("solarized", s.terminalColorScheme)
        s.themeMode = "dark"; assertEquals("dark", s.themeMode)
        s.customShortcuts = "copy=ctrl+c;paste=ctrl+v"; assertEquals("copy=ctrl+c;paste=ctrl+v", s.customShortcuts)
        s.wakeWord = "jarvis"; assertEquals("jarvis", s.wakeWord)
        s.wakeEngine = "SHERPA"; assertEquals("SHERPA", s.wakeEngine)
        s.porcupineAccessKey = "abc123"; assertEquals("abc123", s.porcupineAccessKey)
        s.porcupineKeyword = "COMPUTER"; assertEquals("COMPUTER", s.porcupineKeyword)
        s.sherpaKeyword = "HEY JARVIS"; assertEquals("HEY JARVIS", s.sherpaKeyword)
        s.sttServerUrl = "https://stt.example.org"; assertEquals("https://stt.example.org", s.sttServerUrl)
        s.sttServerModel = "tiny"; assertEquals("tiny", s.sttServerModel)
        s.sttServerApiKey = "k1"; assertEquals("k1", s.sttServerApiKey)
        s.ttsServerModel = "piper"; assertEquals("piper", s.ttsServerModel)
        s.ttsServerVoice = "anna"; assertEquals("anna", s.ttsServerVoice)
        s.ttsSystemVoice = "cs-CZ-x"; assertEquals("cs-CZ-x", s.ttsSystemVoice)
        s.googleCloudApiKey = "g1"; assertEquals("g1", s.googleCloudApiKey)
        s.googleCloudVoice = "cs-CZ-Wavenet-B"; assertEquals("cs-CZ-Wavenet-B", s.googleCloudVoice)
        s.sonioxApiKey = "s1"; assertEquals("s1", s.sonioxApiKey)
        s.sonioxTtsVoice = "Nova"; assertEquals("Nova", s.sonioxTtsVoice)
        s.llmSummaryUrl = "https://ai.example.org"; assertEquals("https://ai.example.org", s.llmSummaryUrl)
        s.llmSummaryApiKey = "l1"; assertEquals("l1", s.llmSummaryApiKey)
        s.llmSummaryModel = "chadrock"; assertEquals("chadrock", s.llmSummaryModel)
        s.llmSummaryLength = "PARAGRAPH"; assertEquals("PARAGRAPH", s.llmSummaryLength)
    }

    @Test
    fun everyEnumSetting_roundTrips() {
        val (s, _) = settings()
        s.defaultClaudeMode = ClaudeMode.PLAN; assertEquals(ClaudeMode.PLAN, s.defaultClaudeMode)
        s.defaultClaudeModel = ClaudeModel.OPUS; assertEquals(ClaudeModel.OPUS, s.defaultClaudeModel)
        s.defaultConnectionType = ConnectionType.MOSH; assertEquals(ConnectionType.MOSH, s.defaultConnectionType)
        s.sttEngine = SttEngine.SYSTEM; assertEquals(SttEngine.SYSTEM, s.sttEngine)
        s.ttsEngine = TtsEngine.SYSTEM; assertEquals(TtsEngine.SYSTEM, s.ttsEngine)
        s.crVariant = CRVariant.Classic; assertEquals(CRVariant.Classic, s.crVariant)
        s.crDensity = CRDensity.Regular; assertEquals(CRDensity.Regular, s.crDensity)
        s.crAccent = CRAccent.Sky; assertEquals(CRAccent.Sky, s.crAccent)
        s.crStatusViz = CRStatusViz.Pill; assertEquals(CRStatusViz.Pill, s.crStatusViz)
        s.crTerminalView = CRTerminalView.Raw; assertEquals(CRTerminalView.Raw, s.crTerminalView)
        s.crTerminalScheme = CRTerminalScheme.Default; assertEquals(CRTerminalScheme.Default, s.crTerminalScheme)
    }

    @Test
    fun appearance_roundTripsThroughSaveAndLoad() {
        val store = FakeKeyValueStore()
        val state = AppearanceState(
            variant = CRVariant.entries.last(),
            density = CRDensity.entries.last(),
            accent = CRAccent.entries.last(),
            statusViz = CRStatusViz.entries.last(),
            terminalView = CRTerminalView.entries.last(),
            terminalScheme = CRTerminalScheme.entries.last(),
        )
        AppSettings(store).saveAppearance(state)
        // A *fresh* instance over the same store must see the same appearance.
        assertEquals(state, AppSettings(store).loadAppearance())
    }

    // --- installId: generated once, then stable forever ---

    @Test
    fun installId_isGeneratedOnceAndPersisted() {
        val (s, store) = settings()
        val id = s.installId
        assertTrue(id.isNotBlank(), "installId must not be blank")
        assertEquals(id, store.map["install_id"], "installId must be persisted under install_id")
        // Repeated reads on the same instance must not regenerate.
        assertEquals(id, s.installId)
    }

    @Test
    fun installId_isStableAcrossNewAppSettingsInstances() {
        // A changing installId would rename the server-side log file
        // (~/.claude-remote/logs/<installId>.log) and invalidate the tmux
        // single-client marker keyed on it, so this must survive re-construction.
        val store = FakeKeyValueStore()
        val first = AppSettings(store).installId
        val second = AppSettings(store).installId
        val third = AppSettings(store).installId
        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun installId_isPlatformPrefixed() {
        val (s, _) = settings()
        // The desktop test JVM is not Dalvik, so the id must carry the desktop tag.
        assertTrue(s.installId.startsWith("desktop-"), "unexpected installId: ${s.installId}")
    }

    @Test
    fun installId_blankStoredValueIsRegenerated() {
        // An empty string on disk (an older build that wrote "" once) must not be
        // handed out as the id.
        val (s, store) = settings("install_id" to "   ")
        val id = s.installId
        assertTrue(id.startsWith("desktop-"))
        assertEquals(id, store.map["install_id"])
    }

    @Test
    fun installId_existingValueIsReturnedVerbatim() {
        val (s, _) = settings("install_id" to "android-deadbeef")
        assertEquals("android-deadbeef", s.installId)
    }

    // --- forward/backward compatibility: unknown enum constants on disk ---

    @Test
    fun garbageStoredEnum_fallsBackToDefault() {
        val (s, _) = settings(
            "default_claude_mode" to "!!not-a-mode!!",
            "default_claude_model" to "GPT9",
            "default_connection_type" to "TELNET",
            "stt_engine" to "MAGIC",
            "tts_engine" to "MAGIC",
            "cr_variant" to "Neon",
            "cr_density" to "Huge",
            "cr_accent" to "Chartreuse",
            "cr_status_viz" to "Blob",
            "cr_terminal_view" to "Hologram",
            "cr_terminal_scheme" to "Vaporwave",
        )
        assertEquals(ClaudeMode.YOLO, s.defaultClaudeMode)
        assertEquals(ClaudeModel.DEFAULT, s.defaultClaudeModel)
        assertEquals(ConnectionType.SSH, s.defaultConnectionType)
        assertEquals(SttEngine.SERVER, s.sttEngine)
        assertEquals(TtsEngine.SERVER, s.ttsEngine)
        assertEquals(CRVariant.Classic, s.crVariant)
        assertEquals(CRDensity.Regular, s.crDensity)
        assertEquals(CRAccent.Sky, s.crAccent)
        assertEquals(CRStatusViz.Pill, s.crStatusViz)
        assertEquals(CRTerminalView.Raw, s.crTerminalView)
        assertEquals(CRTerminalScheme.Default, s.crTerminalScheme)
    }

    @Test
    fun emptyStoredEnum_fallsBackToDefault() {
        val (s, _) = settings(
            "default_claude_mode" to "",
            "default_claude_model" to "",
            "default_connection_type" to "",
            "cr_variant" to "",
        )
        assertEquals(ClaudeMode.YOLO, s.defaultClaudeMode)
        assertEquals(ClaudeModel.DEFAULT, s.defaultClaudeModel)
        assertEquals(ConnectionType.SSH, s.defaultConnectionType)
        assertEquals(CRVariant.Classic, s.crVariant)
    }

    @Test
    fun storedEnumIsCaseSensitive_lowercaseFallsBackToDefault() {
        // valueOf is exact-match; documents that we must never lowercase names
        // when writing settings out.
        val (s, _) = settings("default_claude_mode" to "plan")
        assertEquals(ClaudeMode.YOLO, s.defaultClaudeMode)
    }

    @Test
    fun garbageStoredInt_fallsBackToDefault() {
        // Desktop stores everything stringly; a non-numeric value must not throw.
        val (s, _) = settings(
            "terminal_font_size" to "big",
            "default_ssh_port" to "",
            "side_panel_width_dp" to "wide",
        )
        assertEquals(14, s.terminalFontSize)
        assertEquals(22, s.defaultSshPort)
        assertEquals(220, s.sidePanelWidthDp)
    }

    @Test
    fun garbageStoredBoolean_fallsBackToDefault() {
        val (s, _) = settings(
            "ssh_auto_reconnect" to "yes",
            "haptic_feedback" to "TRUE", // toBooleanStrictOrNull is case-sensitive
        )
        assertEquals(true, s.sshAutoReconnect)
        assertEquals(false, s.hapticFeedback)
    }

    // --- clamping ---

    @Test
    fun terminalFontSize_isClampedOnWrite() {
        val (s, _) = settings()
        s.terminalFontSize = 99
        assertEquals(32, s.terminalFontSize)
        s.terminalFontSize = 1
        assertEquals(8, s.terminalFontSize)
    }

    @Test
    fun transcriptFontPercent_defaultsTo100AndClampsOnWrite() {
        val (s, _) = settings()
        assertEquals(100, s.transcriptFontPercent)
        s.transcriptFontPercent = 500
        assertEquals(160, s.transcriptFontPercent)
        s.transcriptFontPercent = 10
        assertEquals(70, s.transcriptFontPercent)
    }

    @Test
    fun terminalFontSize_storedOutOfRangeValueIsNotClampedOnRead() {
        // SUSPECTED BUG: the setter clamps to 8..32 but the getter does not, so a
        // value written by another build (or hand-edited into
        // ~/.claude-remote/settings.properties) is returned as-is and reaches the
        // terminal renderer unclamped. Asserted against CURRENT behavior.
        val (s, _) = settings("terminal_font_size" to "400")
        assertEquals(400, s.terminalFontSize)
    }

    @Test
    fun sidePanelWidthDp_isClampedOnBothReadAndWrite() {
        val (s, store) = settings()
        s.sidePanelWidthDp = 9999
        assertEquals(480, s.sidePanelWidthDp)
        store.map["side_panel_width_dp"] = "1"
        assertEquals(160, s.sidePanelWidthDp)
    }

    @Test
    fun dictationSilenceMs_isClampedOnBothReadAndWrite() {
        val (s, store) = settings()
        s.dictationSilenceMs = 100
        assertEquals(1000, s.dictationSilenceMs)
        s.dictationSilenceMs = 60_000
        assertEquals(10_000, s.dictationSilenceMs)
        store.map["dictation_silence_ms"] = "999999"
        assertEquals(10_000, s.dictationSilenceMs)
    }

    // --- trimming ---

    @Test
    fun urlAndKeySetters_trimWhitespace() {
        // Pasting a key on a phone keyboard commonly drags a trailing newline in;
        // an untrimmed URL/key breaks the request with a confusing error.
        val (s, _) = settings()
        s.sttServerUrl = "  https://stt.example.org \n"
        s.sttServerApiKey = " k1 "
        s.sonioxApiKey = "\ts1\t"
        s.llmSummaryUrl = " https://ai.example.org "
        assertEquals("https://stt.example.org", s.sttServerUrl)
        assertEquals("k1", s.sttServerApiKey)
        assertEquals("s1", s.sonioxApiKey)
        assertEquals("https://ai.example.org", s.llmSummaryUrl)
    }

    @Test
    fun themeMode_unknownValueIsPassedThroughVerbatim() {
        // themeMode is a plain String, not an enum; the consumer's `when` has an
        // else -> system branch, so an unknown value degrades to system theme
        // rather than needing a fallback here. Documents the contract.
        val (s, _) = settings("theme_mode" to "midnight")
        assertEquals("midnight", s.themeMode)
    }

    // --- the seam itself ---

    @Test
    fun settingsAreWrittenThroughTheAsyncPath() {
        // AppSettings must never use the blocking durable write — only
        // SessionStorage.remove needs that, and it is expensive on Android.
        val (s, store) = settings()
        s.themeMode = "dark"
        s.terminalFontSize = 16
        s.hapticFeedback = true
        val id = s.installId
        assertTrue(id.isNotBlank())
        assertEquals(0, store.syncWriteCount)
    }
}
