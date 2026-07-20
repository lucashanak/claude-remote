package com.clauderemote.voice

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Mic button: tap to start Czech speech-to-text dictation, tap again to stop.
 * Takes the full [TextFieldValue] (text + caret/selection) so dictated words
 * are inserted AT THE CURSOR (replacing any selection), not blindly appended
 * to the end. Partial results stream in while listening and the caret is left
 * just after the inserted text.
 *
 * Renders nothing on platforms without STT support (currently desktop).
 *
 * [autoStartSignal] lets the wake word start dictation hands-free: whenever it
 * changes to a fresh value > 0 the button begins listening exactly as if it had
 * been tapped (unless a dictation is already running). [onListeningChange]
 * reports the mic state so the caller can release competing listeners (the wake
 * word) while dictating.
 */
@Composable
expect fun MicButton(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    autoStartSignal: Int = 0,
    onListeningChange: (Boolean) -> Unit = {},
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
