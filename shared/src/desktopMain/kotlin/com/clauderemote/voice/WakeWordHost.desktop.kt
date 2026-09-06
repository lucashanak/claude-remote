package com.clauderemote.voice

import androidx.compose.runtime.Composable
import com.clauderemote.storage.AppSettings

@Composable
actual fun WakeWordSettingsCard(settings: AppSettings) {
    // No desktop voice settings yet. Nothing is rendered here rather than a
    // "not available" line because SettingsScreen only calls this on mobile —
    // anything put here would be invisible until that gate is lifted.
}

@Composable
actual fun WakeWordListener(paused: Boolean, onWake: () -> Unit) {
    // No wake word on desktop: it needs the sherpa-onnx KWS model, which is an
    // Android-only dependency. Dictation and read-aloud work without it.
}
