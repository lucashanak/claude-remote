package com.clauderemote.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The top-bar Jarvis button (Launcher, terminal/chat, Velín): a tap opens Jarvis' tab (attaches to the running
 * session when there is no tab); a long press — or a right click on desktop ([secondaryClick]) — opens the Velín.
 * Null [onVelin] leaves both inert (the Velín's own bar).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun JarvisButton(
    onJarvis: () -> Unit,
    onVelin: (() -> Unit)?,
    tint: Color,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .secondaryClick(enabled = onVelin != null) { onVelin?.invoke() }
            .combinedClickable(
                onClickLabel = "Jarvis",
                onLongClickLabel = if (onVelin != null) "Velín" else null,
                onLongClick = onVelin,
                onClick = onJarvis,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.SmartToy,
            contentDescription = if (onVelin != null) "Jarvis (podržet nebo pravý klik: Velín)" else "Jarvis",
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
