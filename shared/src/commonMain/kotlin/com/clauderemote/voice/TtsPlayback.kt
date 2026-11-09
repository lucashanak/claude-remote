package com.clauderemote.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the read-aloud player is doing right now. */
enum class TtsPhase {
    /** Waiting for the first audio of the current segment. */
    BUFFERING,
    PLAYING,
    PAUSED,
    /** Out of audio and the connection is down; retrying on its own. */
    WAITING_NETWORK,
    /** Gave up retrying (or a fatal error); play resumes from the current segment. */
    INTERRUPTED,
}

/**
 * Snapshot of the read-aloud player for the in-app bar. [segment] is 0-based;
 * [segmentCount] is the number of paragraph-sized segments the text was cut
 * into ([ttsSegments]). [preview] is the start of the current segment.
 */
data class TtsPlayerState(
    val phase: TtsPhase,
    val segment: Int,
    val segmentCount: Int,
    val preview: String,
    val note: String? = null,
)

/** Transport controls a platform player exposes to the shared UI. */
interface TtsPlayerController {
    fun play()
    fun pause()
    /** Restart the current segment, or go to the previous one if near its start. */
    fun previous()
    fun next()
    fun rewind(seconds: Int)
    fun stop()
}

/**
 * Process-wide bridge between the platform TTS player and the shared UI.
 * `state` is null whenever nothing is being read; only the Soniox player
 * ([SonioxTtsSession], Android and desktop) publishes here — the other engines
 * have no transport.
 */
object TtsPlayback {
    private val _state = MutableStateFlow<TtsPlayerState?>(null)
    val state: StateFlow<TtsPlayerState?> = _state.asStateFlow()

    @kotlin.concurrent.Volatile
    var controller: TtsPlayerController? = null
        private set

    fun attach(controller: TtsPlayerController, initial: TtsPlayerState) {
        this.controller = controller
        _state.value = initial
    }

    /** Publishes [s] only while [owner] is still the attached controller. */
    fun publish(owner: TtsPlayerController, s: TtsPlayerState) {
        if (controller === owner) _state.value = s
    }

    fun detach(owner: TtsPlayerController) {
        if (controller === owner) {
            controller = null
            _state.value = null
        }
    }
}
