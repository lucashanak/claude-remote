package com.clauderemote.voice

import androidx.compose.runtime.Composable
import com.clauderemote.storage.AppSettings

@Composable
actual fun WakeWordSettingsCard(settings: AppSettings) {
    // No desktop voice settings yet.
}

@Composable
actual fun WakeWordListener(paused: Boolean, onWake: () -> Unit) {
    // No desktop speech support.
}
