package com.clauderemote.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * True when a raw pointer press is the RIGHT mouse button — the predicate
 * [secondaryClick] fires on, pulled out standalone so it's testable without
 * constructing a real Compose `PointerEvent`.
 */
internal fun isSecondaryPress(type: PointerEventType, secondaryButtonPressed: Boolean): Boolean =
    type == PointerEventType.Press && secondaryButtonPressed

/**
 * Fires [onClick] on a right mouse button press — the desktop equivalent of a
 * long-press for opening a context menu. Android never reports a secondary
 * pointer button, so this modifier is inert there and safe to chain onto any
 * composable that already handles long-press for mobile via
 * `combinedClickable`'s `onLongClick`.
 *
 * Factored out of TranscriptCards' CopyButton (the first place this pattern
 * was needed, for the "Copy for Slack" menu) so every desktop context-menu
 * trigger shares one implementation.
 */
fun Modifier.secondaryClick(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (isSecondaryPress(event.type, event.buttons.isSecondaryPressed)) {
                    onClick()
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
