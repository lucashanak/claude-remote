package com.clauderemote.model

import kotlinx.serialization.Serializable

@Serializable
enum class ClaudeMode(val displayName: String, val cliFlag: String?) {
    NORMAL("Normal", null),
    PLAN("Plan", null),
    AUTO_ACCEPT("Auto-accept", "--auto-accept"),
    YOLO("YOLO", "--dangerously-skip-permissions");
}

@Serializable
enum class ClaudeModel(val displayName: String, val cliValue: String?) {
    DEFAULT("Default", null),
    OPUS("Opus", "opus"),
    SONNET("Sonnet", "sonnet"),
    HAIKU("Haiku", "haiku");
}

@Serializable
enum class ConnectionType(val displayName: String) {
    SSH("SSH"),
    MOSH("Mosh");
}

@Serializable
enum class AuthMethod {
    PASSWORD,
    KEY
}

/**
 * Speech-to-text backend for Czech dictation + voice mode.
 *
 * - [SYSTEM]: the device's built-in recognizer (Google on most phones).
 *   Best quality where the device supports Czech; needs network on many
 *   devices. Auto-falls back to [VOSK] if the system can't do Czech.
 * - [VOSK]: offline, small (~44 MB), fast, lower accuracy (~21% WER).
 * - [WHISPER]: offline, larger download, slower, higher accuracy.
 */
@Serializable
enum class SttEngine(val displayName: String) {
    SYSTEM("Systémový (Google)"),
    WHISPER("Whisper (offline)"),
    SERVER("Server (faster-whisper)");
}

/**
 * Text-to-speech backend for reading assistant replies aloud.
 *
 * - [SYSTEM]: on-device Android TTS, forced to the Google engine
 *   (`com.google.android.tts`) with a device-default fallback. Instant,
 *   free, offline; needs the cs-CZ voice installed.
 * - [SERVER]: self-hosted OpenAI-compatible `/v1/audio/speech` (Piper fast
 *   CZ-only, or XTTS slower bilingual).
 * - [GOOGLE_CLOUD]: Google Cloud Text-to-Speech REST API. Best quality,
 *   fast, but needs an API key + billing and sends text to Google.
 */
@Serializable
enum class TtsEngine(val displayName: String) {
    SYSTEM("Google (v zařízení)"),
    SERVER("Server (Piper/XTTS)"),
    GOOGLE_CLOUD("Google Cloud");
}

enum class SessionStatus {
    CONNECTING,
    ACTIVE,
    DISCONNECTED,
    ERROR
}

/**
 * Reachability of a configured server, shown as a colored dot in the launcher
 * so the user can see which servers are online before opening a session.
 * Kept as a separate StateFlow map in the orchestrator (not persisted on
 * [com.clauderemote.model.SshServer]).
 */
enum class ServerHealth {
    UNKNOWN,   // not probed yet, or probe skipped (e.g. Cloudflare proxy)
    CHECKING,  // probe in flight
    ONLINE,    // reachable (live SSH connection or TCP connect succeeded)
    OFFLINE    // TCP connect failed / timed out
}

/**
 * Fine-grained activity state derived from prompt detection + connection status.
 * Used for colored health dots in the UI.
 */
enum class SessionActivity(val displayName: String) {
    WORKING("Working"),           // Claude is processing (no prompt detected)
    WAITING_FOR_INPUT("Ready"),   // Claude prompt detected (❯)
    APPROVAL_NEEDED("Approval"),  // [Y/n] or permission prompt
    IDLE("Idle"),                 // Connected but no recent output
    DISCONNECTED("Disconnected")  // Connection lost
}
