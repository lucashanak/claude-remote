package com.clauderemote.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRThemeSnapshot

/**
 * Android has no heavyweight AWT/Swing surface to fight — the terminal view
 * (even Raw) is composed normally, so a plain in-window AlertDialog already
 * renders above everything. [theme] is unused: we're still in the original
 * composition, so CRTheme's own composition locals are already in scope.
 */
@Composable
actual fun FloatingDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    theme: CRThemeSnapshot,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier,
    dismissButton: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
) {
    if (visible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            title = title,
            text = text,
            containerColor = CRTheme.colors.surface,
        )
    }
}
