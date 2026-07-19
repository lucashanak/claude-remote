package com.clauderemote.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * Wire model for the phone -> watch session snapshot (path "/sessions").
 * This module has no Gradle dependency on `androidApp` (kept dependency-free
 * — see build.gradle.kts), so it declares its own copy matching
 * androidApp's `WearSync.kt`. Keep the two in sync when the shape changes —
 * the orchestrator there adds the same `summary` field.
 */
@Serializable
data class WearSessionInfo(
    val id: String,
    val title: String,
    val status: String,
    val activity: String,
    val lastMessage: String?,
    val lastMessageAt: Long = 0,
    // One-line LLM shrnutí `lastMessage` (může být null, když se nepodařilo
    // vygenerovat nebo je vypnuté) — default null drží zpětnou kompatibilitu
    // se staršími phone builds díky `ignoreUnknownKeys` na WEAR_JSON.
    val summary: String? = null,
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
    // Silence tolerance (ms) for on-watch dictation, mirrored from the phone.
    // Trailing default keeps compat with older phone builds (ignoreUnknownKeys).
    val dictationSilenceMs: Int = 4000,
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
    @Volatile var dictationSilenceMs: Int = 4000
        private set

    fun update(key: String, voice: String = "Adrian", speedPct: Int = 100, silenceMs: Int = 4000) {
        if (key.isNotBlank()) apiKey = key
        if (voice.isNotBlank()) ttsVoice = voice
        if (speedPct in 25..400) ttsSpeedPct = speedPct
        if (silenceMs in 1000..10000) dictationSilenceMs = silenceMs
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

    // Elapsed realtime (not wall-clock — immune to the user/NTP changing the
    // system time) of the last successful update; 0L = never synced yet.
    // Lets the UI show data age and warn when the phone's gone quiet.
    private val _lastSyncElapsed = MutableStateFlow(0L)
    val lastSyncElapsed: StateFlow<Long> = _lastSyncElapsed

    // Distinguishes "just launched, nothing has arrived yet" from "arrived,
    // and it's genuinely empty" — both render as an empty list otherwise.
    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded

    fun update(payload: List<WearSessionInfo>) {
        _sessions.value = payload
        _lastSyncElapsed.value = android.os.SystemClock.elapsedRealtime()
        _hasLoaded.value = true
    }
}

/**
 * One-shot bridge from a notification/deep-link tap to the Compose UI's
 * session selection. MainActivity (which owns the Intent) writes the tapped
 * session id here; `WearApp()` collects it and opens that session, then
 * clears it — clearing matters so a later swipe-back to the list doesn't get
 * yanked straight back into the same session by a stale value.
 */
object NavRequest {
    val requestedSessionId = MutableStateFlow<String?>(null)

    // Session id for which the "🎤 Diktovat" notification action asked us to
    // auto-start Soniox dictation on open. Keyed by id (not a bare bool) so a
    // flag left over from one session can't fire on another. Consumed by
    // SessionDetailScreen once it fires the dictation — NOT by consume() below,
    // which only clears the nav target (WearApp calls it right after selecting
    // the session, long before the detail screen gets a chance to read this).
    val startDictation = MutableStateFlow<String?>(null)

    fun request(id: String?, startDictation: Boolean = false) {
        if (!id.isNullOrBlank()) {
            requestedSessionId.value = id
            if (startDictation) this.startDictation.value = id
        }
    }

    fun consume() {
        requestedSessionId.value = null
    }

    fun consumeStartDictation() {
        startDictation.value = null
    }
}
