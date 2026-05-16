package com.clauderemote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRVariant

@Composable
fun CRCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(CRTheme.metrics.cardPad),
    background: Color = CRTheme.colors.surface,
    borderColor: Color = CRTheme.colors.border,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(CRTheme.metrics.cardRadius)
    val isGlass = CRTheme.variant == CRVariant.Glass
    val effectiveBorder = if (isGlass) CRTheme.colors.accent.copy(alpha = 0.18f) else borderColor

    val bgModifier = if (isGlass) {
        val surf = background.copy(alpha = 0.85f)
        val surfBottom = background.copy(alpha = 0.45f)
        Modifier.background(
            Brush.verticalGradient(listOf(surf, surfBottom)),
            shape,
        )
    } else {
        Modifier.background(background, shape)
    }

    Box(
        modifier
            .then(bgModifier)
            .border(if (isGlass) 1.5.dp else 1.dp, effectiveBorder, shape)
            .padding(padding)
    ) { content() }
}
