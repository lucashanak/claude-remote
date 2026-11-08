package com.clauderemote.ui.components

import androidx.compose.runtime.Composable
import com.clauderemote.ui.theme.CRThemeSnapshot

/**
 * A snackbar slot pinned to the bottom-left corner that stays visible above every surface, including the
 * desktop's SwingPanel-embedded terminal. Same reason and same cure as [FloatingDialog]: an in-window Compose
 * overlay draws below a heavyweight AWT child, so the desktop actual is its own small undecorated window that
 * follows the main window; Android has no heavyweight surface and draws it in place.
 *
 * Call it as the LAST child of the app's root Box (Android aligns within it). [content] sizes itself; the desktop
 * window takes that size. [theme] as for [FloatingDialog]: captured at the call site, because the desktop window
 * is a new composition root.
 *
 * Desktop: the window never takes focus, so a snackbar appearing mid-typing does not steal the terminal's keys.
 */
@Composable
expect fun FloatingSnackbar(
    visible: Boolean,
    theme: CRThemeSnapshot,
    content: @Composable () -> Unit,
)
