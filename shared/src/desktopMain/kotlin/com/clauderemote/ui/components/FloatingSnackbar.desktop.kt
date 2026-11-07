package com.clauderemote.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import com.clauderemote.ui.theme.CRThemeSnapshot
import com.clauderemote.ui.theme.reprovide
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

private const val MARGIN = 12

/**
 * An undecorated, non-focusable DialogWindow owned by the main window (so it stays above it and leaves with it),
 * kept at the main window's bottom-left corner as that window moves or resizes, and sized to what [content]
 * measures. The owner is read from the dialog itself (`window.owner`, which DialogWindow sets to the calling
 * window) because Compose keeps its own LocalWindow internal. AWT window coordinates and Compose window dp are
 * the same unit on desktop, which is why the owner's bounds go straight into [WindowPosition]. The dialog stays
 * invisible until it has been placed once, so it never flashes at the platform's default spot.
 */
@Composable
actual fun FloatingSnackbar(
    visible: Boolean,
    theme: CRThemeSnapshot,
    content: @Composable () -> Unit,
) {
    if (!visible) return
    var size by remember { mutableStateOf(DpSize(420.dp, 72.dp)) }
    var anchor by remember { mutableStateOf<Pair<java.awt.Rectangle, java.awt.Insets>?>(null) }
    val state = rememberDialogState(size = size)
    val a = anchor
    LaunchedEffect(a, size) {
        if (a == null) return@LaunchedEffect
        val (b, ins) = a
        state.position = WindowPosition(
            (b.x + ins.left + MARGIN).dp,
            (b.y + b.height - ins.bottom - MARGIN).dp - size.height,
        )
        state.size = size
    }
    DialogWindow(
        onCloseRequest = {},
        state = state,
        visible = a != null,
        title = "Claude Remote",
        undecorated = true,
        resizable = false,
        focusable = false,
    ) {
        val owner = window.owner
        DisposableEffect(owner) {
            if (owner == null) return@DisposableEffect onDispose {}
            anchor = owner.bounds to owner.insets
            val follow = object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) { anchor = owner.bounds to owner.insets }
                override fun componentResized(e: ComponentEvent) { anchor = owner.bounds to owner.insets }
            }
            owner.addComponentListener(follow)
            onDispose { owner.removeComponentListener(follow) }
        }
        val density = LocalDensity.current
        theme.reprovide {
            // Unbounded so the content reports its own size, not the window's, and the window follows it.
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .onSizeChanged { px -> with(density) { size = DpSize(px.width.toDp(), px.height.toDp()) } },
            ) {
                content()
            }
        }
    }
}
