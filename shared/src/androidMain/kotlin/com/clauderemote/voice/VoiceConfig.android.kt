package com.clauderemote.voice

import android.content.Context
import com.clauderemote.model.SttEngine
import com.clauderemote.model.TtsEngine

/**
 * Reads the user's chosen STT engine directly from the same
 * SharedPreferences file [com.clauderemote.storage.PlatformPreferences]
 * uses, so MicButton / VoiceMode (which don't otherwise have an
 * AppSettings handle) can route to the right backend without extra
 * Compose plumbing.
 */
/**
 * Strip a trailing `/v1` (and any trailing slashes) from a server base URL.
 * The app always appends `/v1/audio/...` itself, so a user-pasted URL that
 * already includes `/v1` (a common OpenAI-style copy-paste) would otherwise
 * produce a doubled segment and 404.
 */
internal fun normalizeApiBase(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    return if (trimmed.endsWith("/v1")) trimmed.removeSuffix("/v1") else trimmed
}

internal fun selectedSttEngine(context: Context): SttEngine {
    val name = context
        .getSharedPreferences("claude_remote", Context.MODE_PRIVATE)
        .getString("stt_engine", SttEngine.SYSTEM.name)
        ?: SttEngine.SYSTEM.name
    return runCatching { SttEngine.valueOf(name) }.getOrDefault(SttEngine.SYSTEM)
}

internal data class SttServerConfig(val url: String, val model: String, val apiKey: String)

internal fun sttServerConfig(context: Context): SttServerConfig {
    val p = context.getSharedPreferences("claude_remote", Context.MODE_PRIVATE)
    return SttServerConfig(
        url = p.getString("stt_server_url", "").orEmpty(),
        model = p.getString("stt_server_model", "Systran/faster-whisper-large-v3")
            .orEmpty().ifBlank { "Systran/faster-whisper-large-v3" },
        apiKey = p.getString("stt_server_api_key", "").orEmpty(),
    )
}

internal fun selectedTtsEngine(context: Context): TtsEngine {
    val name = context
        .getSharedPreferences("claude_remote", Context.MODE_PRIVATE)
        .getString("tts_engine", TtsEngine.SYSTEM.name)
        ?: TtsEngine.SYSTEM.name
    return runCatching { TtsEngine.valueOf(name) }.getOrDefault(TtsEngine.SYSTEM)
}

internal data class TtsServerConfig(
    val url: String, val model: String, val voice: String, val apiKey: String,
)

internal fun ttsServerConfig(context: Context): TtsServerConfig {
    val p = context.getSharedPreferences("claude_remote", Context.MODE_PRIVATE)
    // Reuse the STT server URL + key — same Speaches instance serves both.
    return TtsServerConfig(
        url = p.getString("stt_server_url", "").orEmpty(),
        model = p.getString("tts_server_model", "speaches-ai/piper-cs_CZ-jirka-medium")
            .orEmpty().ifBlank { "speaches-ai/piper-cs_CZ-jirka-medium" },
        voice = p.getString("tts_server_voice", "jirka").orEmpty().ifBlank { "jirka" },
        apiKey = p.getString("stt_server_api_key", "").orEmpty(),
    )
}

internal data class GoogleCloudConfig(val apiKey: String, val voice: String)

internal fun googleCloudConfig(context: Context): GoogleCloudConfig {
    val p = context.getSharedPreferences("claude_remote", Context.MODE_PRIVATE)
    return GoogleCloudConfig(
        apiKey = p.getString("gcloud_tts_api_key", "").orEmpty(),
        voice = p.getString("gcloud_tts_voice", "cs-CZ-Wavenet-A")
            .orEmpty().ifBlank { "cs-CZ-Wavenet-A" },
    )
}
