package com.clauderemote.voice

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue

@Composable
actual fun MicButton(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier,
    tint: Color,
    autoStartSignal: Int,
    onListeningChange: (Boolean) -> Unit,
) {
    // No desktop STT yet. Speech support is Android-only for now.
}

@Composable
actual fun SpeakerButton(
    text: String,
    modifier: Modifier,
    tint: Color,
) {
    // No desktop TTS yet. Speech support is Android-only for now.
}
