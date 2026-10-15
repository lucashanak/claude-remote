package com.clauderemote.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * Every app-level keyboard shortcut, in the order the help dialog lists them.
 *
 * ONE table drives both the dispatcher and the Ctrl+Shift+H help: the
 * dispatcher's `when` over this enum is exhaustive, so a shortcut cannot exist
 * without an action, and the help renders `entries`, so it cannot exist without
 * being documented.
 *
 * Why Ctrl+SHIFT and not plain Ctrl: the shortcuts used to fire on plain Ctrl,
 * intercepted before the terminal saw the key. On an external keyboard that
 * stole keys the terminal and Claude's prompt actually use — Ctrl+W (delete
 * word) opened "close session", Ctrl+K (kill to end of line) opened the
 * palette, Ctrl+N (next history line) started a new session. A terminal sends
 * the same control byte for Ctrl+X and Ctrl+Shift+X, so claiming the Shift
 * variant costs the terminal nothing. On macOS Cmd+K / Cmd+N / Cmd+W keep
 * working as before — Cmd never collided with the terminal.
 */
enum class AppShortcut(val keys: String, val label: String) {
    PALETTE("Ctrl+Shift+K", "Command palette"),
    NEW_SESSION("Ctrl+Shift+N", "Nová session"),
    CLOSE_SESSION("Ctrl+Shift+W", "Zavřít session (s potvrzením)"),
    NEXT_SESSION("Ctrl+Tab", "Další session"),
    PREV_SESSION("Ctrl+Shift+Tab", "Předchozí session"),
    TOGGLE_SIDEBAR("Ctrl+Shift+B", "Skrýt / ukázat seznam sessions"),
    TOGGLE_VIEW("Ctrl+Shift+R", "Přepnout Raw / Chat"),
    TOGGLE_KEYBOARD_MODE("Ctrl+Shift+L", "Klávesnicový režim zap / vyp (i na výšku)"),
    MODEL("Ctrl+Shift+M", "Vybrat model"),
    EFFORT("Ctrl+Shift+E", "Vybrat effort"),
    HELP("Ctrl+Shift+H", "Tahle nápověda"),
}

object AppShortcuts {

    /** [match] on a Compose key event — the form both platforms hand us. */
    fun match(event: KeyEvent): AppShortcut? = match(
        key = event.key,
        keyDown = event.type == KeyEventType.KeyDown,
        ctrl = event.isCtrlPressed,
        shift = event.isShiftPressed,
        meta = event.isMetaPressed,
    )

    /**
     * Which shortcut a key press is, or null when it belongs to the terminal.
     *
     * Primitive inputs so the rule can be unit tested without synthesising
     * platform key events. Null is the important answer here: anything this
     * does not claim must reach the terminal untouched.
     */
    fun match(key: Key, keyDown: Boolean, ctrl: Boolean, shift: Boolean, meta: Boolean): AppShortcut? {
        if (!keyDown) return null
        // Ctrl+Tab has no meaning to a terminal, so it stays a plain-Ctrl
        // shortcut — it is the one session switcher people already reach for.
        if (key == Key.Tab && (ctrl || meta)) {
            return if (shift) AppShortcut.PREV_SESSION else AppShortcut.NEXT_SESSION
        }
        // Cmd on its own keeps exactly the set it always had. Widening it would
        // take Cmd+←/→ (line start/end in a text field) for session switching,
        // and macOS swallows Cmd+H / Cmd+M before the app ever sees them.
        if (meta && !ctrl) {
            return when (key) {
                Key.K -> AppShortcut.PALETTE
                Key.N -> AppShortcut.NEW_SESSION
                Key.W -> AppShortcut.CLOSE_SESSION
                else -> null
            }
        }
        if (!(ctrl && shift)) return null
        // No arrow keys here: Ctrl+Shift+←/→ is "select word" in every text
        // field — the chat input and the palette search included — and taking
        // it would switch the session behind the field instead.
        return when (key) {
            Key.K -> AppShortcut.PALETTE
            Key.N -> AppShortcut.NEW_SESSION
            Key.W -> AppShortcut.CLOSE_SESSION
            Key.B -> AppShortcut.TOGGLE_SIDEBAR
            Key.R -> AppShortcut.TOGGLE_VIEW
            Key.L -> AppShortcut.TOGGLE_KEYBOARD_MODE
            Key.M -> AppShortcut.MODEL
            Key.E -> AppShortcut.EFFORT
            Key.H -> AppShortcut.HELP
            else -> null
        }
    }
}
