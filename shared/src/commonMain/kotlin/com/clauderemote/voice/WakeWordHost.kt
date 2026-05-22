package com.clauderemote.voice

import androidx.compose.runtime.Composable
import com.clauderemote.storage.AppSettings

/**
 * Side-effect-only composable that wires the wake-word foreground service
 * to the app's voice-mode lifecycle:
 *
 *  - When [enabled] is true and voice mode is not currently on screen,
 *    the service is started so the user can summon voice mode by saying
 *    "Hej Claude".
 *  - When voice mode is on screen the service is stopped, since both
 *    components compete for the microphone.
 *  - When the service emits a wake event, [onWake] is invoked so the
 *    caller (TerminalScreen) can flip its `voiceModeActive` state.
 *
 * Desktop noops. Android implementation also requests `RECORD_AUDIO` lazily
 * on first enable.
 */
@Composable
expect fun WakeWordHost(
    enabled: Boolean,
    voiceModeActive: Boolean,
    onWake: () -> Unit,
)

/**
 * Settings card with a toggle + download button for the Czech wake-word
 * model. Self-contained — reads/writes [AppSettings.wakeWordEnabled],
 * downloads the model on first enable, requests microphone permission.
 *
 * Desktop noops.
 */
@Composable
expect fun WakeWordSettingsCard(settings: AppSettings)
