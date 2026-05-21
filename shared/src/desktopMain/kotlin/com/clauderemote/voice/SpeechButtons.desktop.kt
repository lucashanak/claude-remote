package com.clauderemote.voice

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
actual fun MicButton(
    currentText: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier,
    tint: Color,
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
