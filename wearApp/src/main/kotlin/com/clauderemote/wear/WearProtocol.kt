package com.clauderemote.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Wire model for the phone -> watch session snapshot (path "/sessions").
 * This module has no Gradle dependency on `androidApp` (kept dependency-free
 * — see build.gradle.kts), so it declares its own copy matching
 * androidApp's `WearSync.kt`. Keep the two in sync when the shape changes.
 */
@Serializable
data class WearSessionInfo(
    val id: String,
    val title: String,
    val status: String,
    val activity: String,
    val lastMessage: String?,
    val lastMessageAt: Long = 0,
)

@Serializable
data class WearSessionsPayload(
    val sessions: List<WearSessionInfo>,
    val sonioxApiKey: String = "",
    // TTS voice + reading speed (percent, 100 = 1.0x) mirrored from the
    // phone's Voice settings so the watch reads aloud with the same voice
    // and speed the user chose there.
    val sonioxVoice: String = "Adrian",
    val ttsSpeedPct: Int = 100,
)

/**
 * Process-wide holder for the Soniox config synced from the phone (rides the
 * /sessions payload). Written by [WearDataListenerService] on each push, read
 * by the on-watch Soniox STT/TTS. In-memory only — re-synced on every push, so
 * it repopulates within ~a second of the app connecting; no need to persist.
 */
object SonioxKeyStore {
    @Volatile var apiKey: String = ""
        private set
    @Volatile var ttsVoice: String = "Adrian"
        private set
    @Volatile var ttsSpeedPct: Int = 100
        private set

    fun update(key: String, voice: String = "Adrian", speedPct: Int = 100) {
        if (key.isNotBlank()) apiKey = key
        if (voice.isNotBlank()) ttsVoice = voice
        if (speedPct in 25..400) ttsSpeedPct = speedPct
    }
}

/**
 * Process-wide holder for the latest synced session list. Written by
 * [WearDataListenerService] (system-invoked per data change, not a
 * long-lived component you can hold a reference to) and by MainActivity's
 * one-shot initial fetch on launch; read by the SessionList UI via
 * `collectAsState()`.
 */
object SessionRepository {
    private val _sessions = MutableStateFlow<List<WearSessionInfo>>(emptyList())
    val sessions: StateFlow<List<WearSessionInfo>> = _sessions

    fun update(payload: List<WearSessionInfo>) {
        _sessions.value = payload
    }
}
