package com.clauderemote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = CRTheme.colors.tintAccent,
    foreground: Color = CRTheme.colors.accent,
    borderColor: Color? = null,
) {
    val shape = RoundedCornerShape(999.dp)
    var m = modifier.background(background, shape)
    if (borderColor != null) m = m.border(1.dp, borderColor, shape)
    Text(
        text,
        style = CRType.pill,
        color = foreground,
        modifier = m.padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
