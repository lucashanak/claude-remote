package com.clauderemote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.clauderemote.ui.theme.CRThemeSnapshot
import com.clauderemote.ui.theme.reprovide

/**
 * A real separate top-level OS window (DialogWindow), not an in-window
 * Compose overlay — it stacks above the main window's SwingPanel-embedded
 * terminal (JediTerm) the same way any other window would, regardless of
 * whether Raw or Chat is the active terminal view. The title/text/button
 * slots are laid out by hand here to match Material3 AlertDialog's shape,
 * since AlertDialog itself is the primitive we're replacing (a DialogWindow
 * hosts a full independent composition, not another AlertDialog). [theme]
 * re-establishes CRTheme's composition locals inside this window's own
 * composition root, which does not inherit them from the caller.
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
    if (!visible) return
    val c = theme.colors
    DialogWindow(
        onCloseRequest = onDismiss,
        state = rememberDialogState(
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(400.dp, 560.dp),
        ),
        title = "Claude Remote",
        resizable = true,
    ) {
        theme.reprovide {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(0.dp)),
            ) {
                Column(
                    modifier = modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                ) {
                    if (title != null) {
                        title()
                        Spacer(Modifier.height(16.dp))
                    }
                    if (text != null) {
                        text()
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}
