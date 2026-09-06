package com.clauderemote.voice

import com.clauderemote.storage.AppSettings
import com.clauderemote.storage.PlatformPreferences

/**
 * Voice settings for the desktop buttons, which have no [AppSettings] handle
 * of their own (the same reason VoiceConfig.android.kt reads prefs directly).
 * A fresh [PlatformPreferences] is built per read: it loads
 * `~/.claude-remote/settings.properties` in its constructor, so this always
 * sees what the settings screen last wrote instead of a snapshot from
 * whenever the composable first ran.
 *
 * These three keys are what SettingsScreen's desktop Voice card writes; the
 * rest of the voice settings are engine/server choices that only Android's
 * backends have.
 */
private fun settings() = AppSettings(PlatformPreferences())

/** Reading speed as a percentage (100 = normal), shared with Android. */
internal fun desktopTtsSpeechRatePct(): Int = settings().ttsSpeechRatePct.coerceIn(25, 400)

/** Soniox cloud key — the only STT backend reachable from desktop. */
internal fun desktopSonioxApiKey(): String = settings().sonioxApiKey

/** How long a pause ends a single-shot dictation. */
internal fun desktopDictationSilenceMs(): Int = settings().dictationSilenceMs
