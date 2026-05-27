package com.clauderemote.voice

import android.content.Context
import com.clauderemote.model.SttEngine

/**
 * Reads the user's chosen STT engine directly from the same
 * SharedPreferences file [com.clauderemote.storage.PlatformPreferences]
 * uses, so MicButton / VoiceMode (which don't otherwise have an
 * AppSettings handle) can route to the right backend without extra
 * Compose plumbing.
 */
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
