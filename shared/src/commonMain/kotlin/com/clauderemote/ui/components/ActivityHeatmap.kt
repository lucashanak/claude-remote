package com.clauderemote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.clauderemote.ui.theme.CRTheme

@Composable
fun ActivityHeatmap(
    values: List<Float>,
    modifier: Modifier = Modifier.fillMaxWidth().height(14.dp),
    color: Color = CRTheme.colors.accent,
) {
    Canvas(modifier) {
        if (values.isEmpty()) return@Canvas
        val cellW = size.width / values.size
        val maxV = (values.maxOrNull() ?: 1f).coerceAtLeast(0.001f)
        values.forEachIndexed { i, v ->
            val a = (v / maxV).coerceIn(0f, 1f) * 0.85f + 0.05f
            drawRect(
                color.copy(alpha = a),
                topLeft = Offset(i * cellW + 0.5f, 0f),
                size = Size(cellW - 1f, size.height),
            )
        }
    }
}

@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier.fillMaxWidth().height(20.dp),
    color: Color = CRTheme.colors.accent,
) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val maxV = values.max()
        val minV = values.min()
        val range = (maxV - minV).coerceAtLeast(0.001f)
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - minV) / range) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
    }
}
