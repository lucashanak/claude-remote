package com.clauderemote.voice

import androidx.compose.runtime.Composable
import com.clauderemote.storage.AppSettings

/**
 * Voice settings card — STT/TTS server config, model picker, voice picker.
 * Renders nothing on platforms without speech support (desktop).
 *
 * File name and composable name are kept (`WakeWordSettingsCard`) for
 * source-stability with the SettingsScreen call site; the wake-word
 * feature itself was removed because the only Czech keyword-spotting
 * model that fit (Vosk small) had unusable WER.
 */
@Composable
expect fun WakeWordSettingsCard(settings: AppSettings)

/**
 * Foreground-only voice activation. When enabled in settings, listens for a
 * wake word (via the STT server) while the app is in the foreground and the
 * dialog isn't already open, and calls [onWake] to launch it. No-op on
 * platforms without speech support (desktop) and when disabled.
 *
 * @param paused When true (e.g. the dialog is already open) the listener
 *               releases the mic and does nothing.
 * @param onWake Invoked on the main thread when the wake word is detected.
 */
@Composable
expect fun WakeWordListener(paused: Boolean, onWake: () -> Unit)
