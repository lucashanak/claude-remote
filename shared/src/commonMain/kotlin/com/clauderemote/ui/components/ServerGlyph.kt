package com.clauderemote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauderemote.ui.theme.CRTheme
import kotlin.math.abs

@Composable
fun ServerGlyph(
    name: String,
    modifier: Modifier = Modifier.size(36.dp),
) {
    val palette = listOf(
        CRTheme.colors.accent,
        CRTheme.colors.ready,
        CRTheme.colors.working,
        CRTheme.colors.approval,
        CRTheme.colors.modePlan,
    )
    val seed = abs(name.hashCode())
    val bg: Color = palette[seed % palette.size].copy(alpha = 0.25f)
    val fg: Color = palette[seed % palette.size]
    Box(
        modifier.background(bg, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(2).uppercase(),
            color = fg,
            fontWeight = FontWeight.W700,
            fontSize = 13.sp,
        )
    }
}
