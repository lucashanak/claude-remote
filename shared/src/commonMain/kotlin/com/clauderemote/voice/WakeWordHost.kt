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
