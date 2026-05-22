package com.clauderemote.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide bus for wake-word detections.
 *
 * Why a [MutableStateFlow] rather than a [kotlinx.coroutines.flow.SharedFlow]:
 * when the wake-word fires we need to (a) bring MainActivity to the
 * foreground via Intent and (b) cause TerminalScreen to enter voice mode.
 * Those two steps are asynchronous and TerminalScreen may not be
 * collecting yet when the service emits. A StateFlow gives the consumer
 * a chance to read the pending state on its next composition. The
 * consumer acknowledges by calling [acknowledge] to clear the flag,
 * preventing a stale wake from re-triggering on configuration changes.
 */
internal object WakeEvents {
    private val _pendingOpen = MutableStateFlow(false)
    val pendingOpen: StateFlow<Boolean> = _pendingOpen.asStateFlow()

    fun fire() {
        _pendingOpen.value = true
    }

    fun acknowledge() {
        _pendingOpen.value = false
    }
}
