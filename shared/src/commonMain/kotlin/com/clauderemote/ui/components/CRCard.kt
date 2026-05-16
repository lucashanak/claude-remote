package com.clauderemote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    Box(
        modifier
            .background(
                if (CRTheme.variant == CRVariant.Glass) background.copy(alpha = 0.55f) else background,
                shape,
            )
            .border(1.dp, borderColor, shape)
            .padding(padding)
    ) { content() }
}
