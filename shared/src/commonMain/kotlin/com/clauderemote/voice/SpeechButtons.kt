package com.clauderemote.voice

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Mic button: tap to start Czech speech-to-text dictation, tap again to stop.
 * Partial recognition results stream into [onTextChange] while listening;
 * the final recognised utterance is appended to [currentText] and committed
 * via the same callback.
 *
 * Renders nothing on platforms without STT support (currently desktop).
 */
@Composable
expect fun MicButton(
    currentText: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
)

/**
 * Speaker button: tap to read [text] aloud (Czech TTS), tap again to stop.
 * Only one TTS playback is active at a time across the app; tapping a
 * different speaker button cancels the previous one.
 *
 * Renders nothing on platforms without TTS support (currently desktop).
 */
@Composable
expect fun SpeakerButton(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
)
