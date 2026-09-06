package com.clauderemote.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState

/**
 * True when a raw pointer press is the RIGHT mouse button — the predicate
 * [secondaryClick] fires on, pulled out standalone so it's testable without
 * constructing a real Compose `PointerEvent`.
 *
 * [secondaryAlreadyHeld] is the button state carried over from the previous
 * event, not menu state: a press while the right button is already down is a
 * SECOND button going down (right held, then left clicked), which must not
 * re-fire.
 */
internal fun isSecondaryPress(
    type: PointerEventType,
    secondaryButtonPressed: Boolean,
    secondaryAlreadyHeld: Boolean = false,
): Boolean = type == PointerEventType.Press && secondaryButtonPressed && !secondaryAlreadyHeld

/**
 * Fires [onClick] on a right mouse button press — the desktop equivalent of a
 * long-press for opening a context menu. Android never reports a secondary
 * pointer button, so this modifier is inert there and safe to chain onto any
 * composable that already handles long-press for mobile via
 * `combinedClickable`'s `onLongClick`.
 *
 * The same pattern is inlined in TranscriptCards' CopyButton (where it first
 * appeared, for the "Copy for Slack" menu); that call site predates this
 * helper and has not been migrated to it.
 */
@Composable
fun Modifier.secondaryClick(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    // The lambda is captured through a State holder and the loop is keyed on
    // `enabled` alone. Keying on the lambda itself (which is what happens when
    // it is captured directly) makes the modifier element unequal on every
    // recomposition, so the pointer loop is cancelled and relaunched for every
    // visible row each time a session's status ticks — on Android too, where
    // this modifier does nothing.
    val current = rememberUpdatedState(onClick)
    return pointerInput(enabled) {
        if (!enabled) return@pointerInput
        // A press while the right button is ALREADY down is a second button
        // going down (right held, then left clicked), not a new right-click —
        // without this the menu reopens under the user's left click.
        var secondaryHeld = false
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val down = event.buttons.isSecondaryPressed
                if (isSecondaryPress(event.type, down, secondaryAlreadyHeld = secondaryHeld)) {
                    current.value()
                    event.changes.forEach { it.consume() }
                }
                secondaryHeld = down
            }
        }
    }
}
