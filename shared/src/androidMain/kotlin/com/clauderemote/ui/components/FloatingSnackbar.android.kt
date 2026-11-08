package com.clauderemote.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clauderemote.ui.theme.CRThemeSnapshot

/** In place, above whatever the root Box drew before it; clear of the system bars and the keyboard. */
@Composable
actual fun FloatingSnackbar(
    visible: Boolean,
    theme: CRThemeSnapshot,
    content: @Composable () -> Unit,
) {
    if (!visible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
            .padding(12.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        content()
    }
}
