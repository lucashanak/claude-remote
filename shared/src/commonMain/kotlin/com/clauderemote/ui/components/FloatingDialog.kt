package com.clauderemote.ui.components

import androidx.compose.runtime.Composable
import com.clauderemote.ui.theme.CRThemeSnapshot

/**
 * Drop-in replacement for Material3's `AlertDialog` that's guaranteed to
 * render above ANY other surface in the window — including a heavyweight
 * native component embedded elsewhere (the desktop terminal's
 * SwingPanel/JediTerm). An in-window Compose overlay (a plain
 * AlertDialog/Popup) draws BELOW a heavyweight AWT child regardless of
 * composition order — a fixed Compose Desktop / Swing interop rule, not a
 * bug to route around per call site. The desktop actual instead opens a
 * genuine separate top-level OS window (DialogWindow), which stacks above
 * the SwingPanel like any other window would, independent of which terminal
 * view (Raw/Chat) is active. Android has no heavyweight surface, so its
 * actual delegates straight to the real `AlertDialog` — Raw and Chat already
 * behave identically there.
 *
 * Same slot shape as `AlertDialog` (title/text/confirmButton/dismissButton)
 * so existing call sites port over with matching visuals, minus the ones
 * this project doesn't use (icon, shape, custom colors beyond the theme).
 *
 * [theme] must be captured via `CRThemeSnapshot.current()` at the call site,
 * while still inside the original composition — a new top-level window opens
 * its own composition root, which does not inherit CompositionLocals from
 * its caller.
 */
@Composable
expect fun FloatingDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    theme: CRThemeSnapshot,
    confirmButton: @Composable () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
)
