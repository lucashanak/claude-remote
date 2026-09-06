package com.clauderemote.ui

import androidx.compose.ui.input.pointer.PointerEventType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [isSecondaryPress] is the predicate [secondaryClick] fires on — pure and
 * platform-independent, so it's exercised directly rather than through a
 * Compose UI test.
 */
class SecondaryClickTest {

    @Test
    fun opensOnRightButtonPress() {
        assertTrue(isSecondaryPress(PointerEventType.Press, secondaryButtonPressed = true))
    }

    @Test
    fun ignoresLeftButtonPress() {
        assertFalse(isSecondaryPress(PointerEventType.Press, secondaryButtonPressed = false))
    }

    /**
     * Right button held, then LEFT clicked: that second press still reports
     * the secondary button as down, so without the held-state check the menu
     * would reopen under the user's left click.
     */
    @Test
    fun doesNotReopenWhileTheRightButtonIsAlreadyHeld() {
        assertFalse(
            isSecondaryPress(PointerEventType.Press, secondaryButtonPressed = true, alreadyOpen = true),
        )
        // ...but a fresh right-click after releasing still opens it.
        assertTrue(
            isSecondaryPress(PointerEventType.Press, secondaryButtonPressed = true, alreadyOpen = false),
        )
    }

    @Test
    fun ignoresRightButtonReleaseAndMove() {
        // Only the initial PRESS should open the menu — a release or a drag
        // that happens to still be holding the right button must not re-fire.
        assertFalse(isSecondaryPress(PointerEventType.Release, secondaryButtonPressed = true))
        assertFalse(isSecondaryPress(PointerEventType.Move, secondaryButtonPressed = true))
    }
}
