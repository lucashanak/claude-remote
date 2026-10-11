package com.clauderemote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.clauderemote.ui.theme.CRTheme

/**
 * Drag-to-rotate view of a [TurntableSheet].
 *
 * HORIZONTAL drag spins the model; tilt is on two small buttons rather than a
 * vertical drag. This sits inside the chat's scrolling list, and a vertical
 * drag on the model would steal the scroll every time a thumb passed over it on
 * the way down the conversation. A horizontal drag does not collide with that.
 */
@Composable
internal fun TurntableView(
    bitmap: ImageBitmap,
    sheet: TurntableSheet,
    description: String,
) {
    val c = CRTheme.colors
    // Start at the middle elevation, the ordinary three-quarter view.
    var elevation by remember(sheet) { mutableIntStateOf(sheet.elevations / 2) }
    // Fractional so a slow drag accumulates instead of rounding to nothing.
    var azimuth by remember(sheet) { mutableFloatStateOf(0f) }
    val frameSide = sheet.frameSize(bitmap.width)
    val shape = RoundedCornerShape(8.dp)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val side = min(maxWidth, 360.dp)
        Box(
            modifier = Modifier
                .size(side)
                .clip(shape)
                .border(1.dp, c.border, shape)
                .semantics { contentDescription = "$description, drag sideways to rotate" },
        ) {
            Canvas(
                modifier = Modifier
                    .size(side)
                    .pointerInput(sheet) {
                        // One revolution per ~1.5 widths of drag: fine enough to
                        // pick an exact angle, short enough to spin it right round.
                        val pxPerFrame = size.width * 1.5f / sheet.frames
                        detectHorizontalDragGestures { change, dx ->
                            change.consume()
                            azimuth -= dx / pxPerFrame
                        }
                    },
            ) {
                val (x, y) = sheet.frameOrigin(kotlin.math.floor(azimuth).toInt(), elevation, bitmap.width)
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset(x, y),
                    srcSize = IntSize(frameSide, frameSide),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                )
            }
            if (sheet.elevations > 1) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(c.surface.copy(alpha = 0.7f), RoundedCornerShape(6.dp)),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    IconButton(
                        onClick = { elevation = (elevation + 1).coerceAtMost(sheet.elevations - 1) },
                        enabled = elevation < sheet.elevations - 1,
                        modifier = Modifier.size(32.dp),
                    ) { Icon(Icons.Default.KeyboardArrowUp, "View from higher", tint = c.text) }
                    IconButton(
                        onClick = { elevation = (elevation - 1).coerceAtLeast(0) },
                        enabled = elevation > 0,
                        modifier = Modifier.size(32.dp),
                    ) { Icon(Icons.Default.KeyboardArrowDown, "View from lower", tint = c.text) }
                }
            }
        }
    }
}
