package com.clauderemote.voice

import androidx.compose.runtime.Composable

@Composable
actual fun VoiceModeScreen(
    onSend: (String) -> Unit,
    latestAssistantId: String?,
    latestAssistantText: String?,
    onClose: () -> Unit,
) {
    // No desktop voice mode yet. Speech support is Android-only for now.
}
