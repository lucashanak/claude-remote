package com.clauderemote.voice

import androidx.compose.runtime.Composable
import com.clauderemote.storage.AppSettings

@Composable
actual fun WakeWordHost(
    enabled: Boolean,
    voiceModeActive: Boolean,
    onWake: () -> Unit,
) {
    // No desktop wake-word.
}

@Composable
actual fun WakeWordSettingsCard(settings: AppSettings) {
    // No desktop wake-word.
}
