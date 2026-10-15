package com.clauderemote.ui

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule that decides whether a key press belongs to the app or the terminal.
 *
 * The null answers are the point: every key this does NOT claim reaches the
 * terminal, and the bug being fixed was the app claiming Ctrl+W (delete word),
 * Ctrl+K (kill line) and Ctrl+N (next history line) out from under Claude.
 */
class AppShortcutsTest {

    private fun hit(key: Key, ctrl: Boolean = false, shift: Boolean = false, meta: Boolean = false, down: Boolean = true) =
        AppShortcuts.match(key, keyDown = down, ctrl = ctrl, shift = shift, meta = meta)

    @Test
    fun plainCtrlBelongsToTheTerminal() {
        // The three keys the old plain-Ctrl shortcuts stole.
        assertNull(hit(Key.W, ctrl = true))
        assertNull(hit(Key.K, ctrl = true))
        assertNull(hit(Key.N, ctrl = true))
        // And the ones Claude's TUI relies on.
        assertNull(hit(Key.C, ctrl = true))
        assertNull(hit(Key.D, ctrl = true))
        assertNull(hit(Key.R, ctrl = true))
    }

    @Test
    fun ctrlShiftIsTheAppsNamespace() {
        assertEquals(AppShortcut.PALETTE, hit(Key.K, ctrl = true, shift = true))
        assertEquals(AppShortcut.NEW_SESSION, hit(Key.N, ctrl = true, shift = true))
        assertEquals(AppShortcut.CLOSE_SESSION, hit(Key.W, ctrl = true, shift = true))
        assertEquals(AppShortcut.TOGGLE_SIDEBAR, hit(Key.B, ctrl = true, shift = true))
        assertEquals(AppShortcut.TOGGLE_VIEW, hit(Key.R, ctrl = true, shift = true))
        assertEquals(AppShortcut.MODEL, hit(Key.M, ctrl = true, shift = true))
        assertEquals(AppShortcut.EFFORT, hit(Key.E, ctrl = true, shift = true))
        assertEquals(AppShortcut.HELP, hit(Key.H, ctrl = true, shift = true))
    }

    @Test
    fun ctrlShiftArrowsStayWordSelection() {
        // "Select word" in the chat input and the palette search — taking it
        // switched the session behind the field.
        assertNull(hit(Key.DirectionLeft, ctrl = true, shift = true))
        assertNull(hit(Key.DirectionRight, ctrl = true, shift = true))
    }

    @Test
    fun unboundCtrlShiftKeysStillReachTheTerminal() {
        // Ctrl+Shift+V is paste in most Linux terminals — deliberately not taken.
        assertNull(hit(Key.V, ctrl = true, shift = true))
        assertNull(hit(Key.C, ctrl = true, shift = true))
        assertNull(hit(Key.Z, ctrl = true, shift = true))
    }

    @Test
    fun ctrlTabSwitchesSessionsBothWays() {
        assertEquals(AppShortcut.NEXT_SESSION, hit(Key.Tab, ctrl = true))
        assertEquals(AppShortcut.PREV_SESSION, hit(Key.Tab, ctrl = true, shift = true))
        assertEquals(AppShortcut.NEXT_SESSION, hit(Key.Tab, meta = true))
    }

    @Test
    fun cmdKeepsExactlyItsOldSetOnMac() {
        assertEquals(AppShortcut.PALETTE, hit(Key.K, meta = true))
        assertEquals(AppShortcut.NEW_SESSION, hit(Key.N, meta = true))
        assertEquals(AppShortcut.CLOSE_SESSION, hit(Key.W, meta = true))
        // Cmd+←/→ are line start/end in a text field; Cmd+H/M belong to macOS.
        assertNull(hit(Key.DirectionLeft, meta = true))
        assertNull(hit(Key.DirectionRight, meta = true))
        assertNull(hit(Key.H, meta = true))
        assertNull(hit(Key.M, meta = true))
    }

    @Test
    fun shiftAloneAndKeyUpAreNeverShortcuts() {
        assertNull(hit(Key.K, shift = true))
        assertNull(hit(Key.K))
        // Key-up of a shortcut must not fire it a second time.
        assertNull(hit(Key.K, ctrl = true, shift = true, down = false))
        assertNull(hit(Key.Tab, ctrl = true, down = false))
    }

    @Test
    fun everyShortcutIsDocumentedWithKeysAndALabel() {
        AppShortcut.entries.forEach {
            assertTrue(it.keys.isNotBlank() && it.label.isNotBlank(), "${it.name} has no help text")
        }
    }
}
