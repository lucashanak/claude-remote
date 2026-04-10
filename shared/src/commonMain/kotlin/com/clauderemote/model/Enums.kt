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

enum class SessionStatus {
    CONNECTING,
    ACTIVE,
    DISCONNECTED,
    ERROR
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
