package com.clauderemote.voice

import androidx.compose.runtime.Composable

/**
 * Full-screen hands-free voice mode.
 *
 * While active the app continuously listens for the user, sends the
 * recognised utterance via [onSend], and reads incoming assistant
 * messages aloud — pausing the microphone while TTS is speaking. The
 * caller wires [latestAssistantId] / [latestAssistantText] to the
 * newest assistant entry in the transcript; the screen tracks id
 * transitions and speaks only fresh messages.
 *
 * Renders nothing on platforms without speech support (currently desktop).
 *
 * @param onSend                  Called with the user's recognised utterance.
 * @param latestAssistantId       Id of the most recent assistant message in the transcript.
 * @param latestAssistantText     Body of that message (used for TTS).
 * @param onClose                 Invoked when the user dismisses voice mode.
 */
@Composable
expect fun VoiceModeScreen(
    onSend: (String) -> Unit,
    latestAssistantId: String?,
    latestAssistantText: String?,
    onClose: () -> Unit,
)
