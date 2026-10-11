package com.clauderemote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Which way a turntable faces. Shared by the inline view and the full-screen
 * one, so opening it full screen starts at the angle you turned it to inline
 * and closing it leaves the inline view where you left it.
 */
@Stable
internal class TurntableState(val sheet: TurntableSheet, azimuth: Float, elevation: Int) {
    /** Fractional so a slow drag accumulates instead of rounding to nothing. */
    var azimuth by mutableFloatStateOf(azimuth)
        private set
    var elevation by mutableIntStateOf(elevation)
        private set

    val frame: Int get() = floor(azimuth).toInt().mod(sheet.frames)

    fun turn(frames: Float) { azimuth = (azimuth + frames).mod(sheet.frames.toFloat()) }

    fun tilt(by: Int) { elevation = (elevation + by).coerceIn(0, sheet.elevations - 1) }

    /** Rotate/tilt actions for a screen reader, which cannot drag. */
    fun accessibilityActions(): List<CustomAccessibilityAction> {
        val step = sheet.frames / 8f
        return buildList {
            add(CustomAccessibilityAction("Rotate left") { turn(step); true })
            add(CustomAccessibilityAction("Rotate right") { turn(-step); true })
            if (sheet.elevations > 1) {
                add(CustomAccessibilityAction("View from higher") { tilt(1); true })
                add(CustomAccessibilityAction("View from lower") { tilt(-1); true })
            }
        }
    }
}

/** Saveable so the angle survives the item scrolling out of the chat list. */
@Composable
internal fun rememberTurntableState(sheet: TurntableSheet): TurntableState =
    rememberSaveable(
        sheet,
        saver = listSaver(
            save = { listOf(it.azimuth, it.elevation.toFloat()) },
            restore = { TurntableState(sheet, it[0], it[1].toInt()) },
        ),
    ) {
        // Start at the middle elevation, the ordinary three-quarter view.
        TurntableState(sheet, 0f, sheet.elevations / 2)
    }

/** Draws one frame of [bitmap] (laid out as [layout]) into a square of [side] at [topLeft]. */
private fun DrawScope.drawFrame(
    bitmap: ImageBitmap,
    layout: TurntableSheet,
    azimuth: Int,
    elevation: Int,
    topLeft: Offset,
    side: Float,
) {
    val frameSide = layout.frameSize(bitmap.width)
    val (x, y) = layout.frameOrigin(azimuth, elevation, bitmap.width)
    drawImage(
        image = bitmap,
        srcOffset = IntOffset(x, y),
        srcSize = IntSize(frameSide, frameSide),
        dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = IntSize(side.toInt(), side.toInt()),
        filterQuality = FilterQuality.High,
    )
}

@Composable
private fun TiltButtons(state: TurntableState, modifier: Modifier = Modifier) {
    val c = CRTheme.colors
    Column(
        modifier = modifier.background(c.surface.copy(alpha = 0.7f), RoundedCornerShape(6.dp)),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        IconButton(
            onClick = { state.tilt(1) },
            enabled = state.elevation < state.sheet.elevations - 1,
            modifier = Modifier.size(40.dp),
        ) { Icon(Icons.Default.KeyboardArrowUp, "View from higher", tint = c.text) }
        IconButton(
            onClick = { state.tilt(-1) },
            enabled = state.elevation > 0,
            modifier = Modifier.size(40.dp),
        ) { Icon(Icons.Default.KeyboardArrowDown, "View from lower", tint = c.text) }
    }
}

/**
 * Drag-to-rotate view of a [TurntableSheet] inside an answer.
 *
 * HORIZONTAL drag spins the model; tilt is on two small buttons rather than a
 * vertical drag. This sits inside the chat's scrolling list, and a vertical
 * drag on the model would steal the scroll every time a thumb passed over it on
 * the way down the conversation. A horizontal drag does not collide with that.
 * A tap opens [TurntableFullScreen], where there is no list to fight and both
 * directions and zoom are free.
 */
@Composable
internal fun TurntableView(
    bitmap: ImageBitmap,
    state: TurntableState,
    description: String,
    onExpand: () -> Unit,
) {
    val c = CRTheme.colors
    val sheet = state.sheet
    val shape = RoundedCornerShape(8.dp)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val side = min(maxWidth, 360.dp)
        Box(
            modifier = Modifier
                .size(side)
                .clip(shape)
                .border(1.dp, c.border, shape)
                .semantics {
                    contentDescription = "$description, 3D model you can rotate"
                    customActions = state.accessibilityActions()
                },
        ) {
            Canvas(
                modifier = Modifier
                    .size(side)
                    .pointerInput(sheet) {
                        detectHorizontalDragGestures { change, dx ->
                            change.consume()
                            // One revolution per ~1.5 widths of drag: fine enough to
                            // pick an exact angle, short enough to spin it right round.
                            // Read per event, so a resized pane keeps the same feel.
                            val pxPerFrame = size.width * 1.5f / sheet.frames
                            if (pxPerFrame > 0f) state.turn(-dx / pxPerFrame)
                        }
                    }
                    // A tap never moves past touch slop, so it does not race the drag.
                    .pointerInput(Unit) { detectTapGestures(onTap = { onExpand() }) },
            ) {
                drawFrame(bitmap, sheet, state.frame, state.elevation, Offset.Zero, size.width)
            }
            if (sheet.elevations > 1) {
                TiltButtons(state, Modifier.align(Alignment.TopEnd).padding(4.dp))
            }
            IconButton(
                onClick = onExpand,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(40.dp)
                    .background(c.surface.copy(alpha = 0.7f), RoundedCornerShape(6.dp)),
            ) { Icon(Icons.Default.Fullscreen, "Open full screen", tint = c.text) }
        }
    }
}

private const val MAX_ZOOM = 6f

/**
 * Full-screen turntable: one-finger (or mouse) drag rotates sideways and tilts
 * up/down, pinch or the mouse wheel zooms around the fingers / pointer, two
 * fingers pan, double tap toggles zoom.
 *
 * The main sheet's frames are small (256 px), so zooming them only magnifies
 * blur. [loadDetail] fetches the current elevation's DETAIL sheet (512 px
 * frames, see [TurntableSheet.detail]) and it replaces the small frames as soon
 * as it arrives. Only one is held at a time — each is ~37 MB decoded — and an
 * elevation without one (an older render) simply keeps the main sheet.
 */
@Composable
internal fun TurntableFullScreen(
    bitmap: ImageBitmap,
    state: TurntableState,
    title: String,
    loadDetail: suspend (elevation: Int) -> ImageBitmap?,
    onClose: () -> Unit,
) {
    val c = CRTheme.colors
    val sheet = state.sheet
    val detailLayout = remember(sheet) { sheet.detail() }
    var detail by remember { mutableStateOf<Pair<Int, ImageBitmap>?>(null) }
    val missing = remember { mutableSetOf<Int>() }
    LaunchedEffect(state.elevation) {
        val e = state.elevation
        if (detail?.first == e || e in missing) return@LaunchedEffect
        // Let a quick run of tilts settle before fetching ~2-3 MB per level.
        kotlinx.coroutines.delay(200)
        val loaded = loadDetail(e)
        if (loaded != null && detailLayout.fits(loaded.width, loaded.height)) detail = e to loaded
        else missing += e
    }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = c.bg) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = CRType.cardTitle, color = c.text, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = c.text) }
                }
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val sidePx = with(androidx.compose.ui.platform.LocalDensity.current) { min(maxWidth, maxHeight).toPx() }
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics {
                                contentDescription = "$title, 3D model you can rotate and zoom"
                                customActions = state.accessibilityActions() + listOf(
                                    CustomAccessibilityAction("Zoom in") { zoom = (zoom * 1.5f).coerceAtMost(MAX_ZOOM); true },
                                    CustomAccessibilityAction("Zoom out") { zoom = 1f; pan = Offset.Zero; true },
                                )
                            }
                            .pointerInput(sheet) {
                                fun side() = minOf(size.width, size.height).toFloat()
                                fun center() = Offset(size.width / 2f, size.height / 2f)
                                // Keep the point under [at] where it is while the zoom changes.
                                fun zoomAt(at: Offset, factor: Float) {
                                    val next = (zoom * factor).coerceIn(1f, MAX_ZOOM)
                                    val rel = at - center()
                                    pan = clampPan(rel - (rel - pan) * (next / zoom), next, side())
                                    zoom = next
                                }
                                val tiltStep = 48.dp.toPx()
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var tiltAcc = 0f
                                    // Once two fingers zoomed, the finger left behind must
                                    // not spin the model as the other one lifts.
                                    var pinched = false
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.count { it.pressed }
                                        if (pressed >= 2) {
                                            pinched = true
                                            val z = event.calculateZoom()
                                            val p = event.calculatePan()
                                            val centroid = event.calculateCentroid(useCurrent = true)
                                            if (centroid != Offset.Unspecified) zoomAt(centroid, z)
                                            pan = clampPan(pan + p, zoom, side())
                                            event.changes.forEach { it.consume() }
                                        } else if (pressed == 1 && !pinched) {
                                            val change = event.changes.first { it.pressed }
                                            val d = change.positionChange()
                                            if (d != Offset.Zero) {
                                                // Same feel as inline: a revolution per ~1.5 widths.
                                                val pxPerFrame = side() * 1.5f / sheet.frames
                                                if (pxPerFrame > 0f) state.turn(-d.x / pxPerFrame)
                                                // Dragging down looks from higher, like orbiting a camera.
                                                tiltAcc += d.y
                                                while (abs(tiltAcc) >= tiltStep) {
                                                    state.tilt(if (tiltAcc > 0) 1 else -1)
                                                    tiltAcc -= if (tiltAcc > 0) tiltStep else -tiltStep
                                                }
                                                change.consume()
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .pointerInput(sheet) {
                                detectTapGestures(onDoubleTap = { at ->
                                    val side = minOf(size.width, size.height).toFloat()
                                    if (zoom > 1.01f) { zoom = 1f; pan = Offset.Zero }
                                    else {
                                        val rel = at - Offset(size.width / 2f, size.height / 2f)
                                        val next = 2.5f
                                        pan = clampPan(rel - rel * next, next, side)
                                        zoom = next
                                    }
                                })
                            }
                            // Mouse wheel (desktop): zoom around the pointer.
                            .pointerInput(sheet) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type != PointerEventType.Scroll) continue
                                        val change = event.changes.firstOrNull() ?: continue
                                        val dy = change.scrollDelta.y
                                        if (dy == 0f) continue
                                        val side = minOf(size.width, size.height).toFloat()
                                        val next = (zoom * 1.15f.pow(-dy)).coerceIn(1f, MAX_ZOOM)
                                        val rel = change.position - Offset(size.width / 2f, size.height / 2f)
                                        pan = clampPan(rel - (rel - pan) * (next / zoom), next, side)
                                        zoom = next
                                        change.consume()
                                    }
                                }
                            },
                    ) {
                        val side = minOf(size.width, size.height)
                        val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
                        val hd = detail?.takeIf { it.first == state.elevation }?.second
                        withTransform({
                            translate(pan.x, pan.y)
                            scale(zoom, zoom, pivot = center)
                        }) {
                            if (hd != null) drawFrame(hd, detailLayout, state.frame, 0, topLeft, side)
                            else drawFrame(bitmap, sheet, state.frame, state.elevation, topLeft, side)
                        }
                    }
                    Column(
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (sheet.elevations > 1) TiltButtons(state)
                        Column(
                            modifier = Modifier.background(c.surface.copy(alpha = 0.7f), RoundedCornerShape(6.dp)),
                        ) {
                            IconButton(
                                onClick = { zoom = (zoom * 1.5f).coerceAtMost(MAX_ZOOM); pan = clampPan(pan, zoom, sidePx) },
                                enabled = zoom < MAX_ZOOM,
                                modifier = Modifier.size(40.dp),
                            ) { Icon(Icons.Default.ZoomIn, "Zoom in", tint = c.text) }
                            IconButton(
                                onClick = {
                                    zoom = (zoom / 1.5f).coerceAtLeast(1f)
                                    pan = if (zoom <= 1.01f) Offset.Zero else clampPan(pan, zoom, sidePx)
                                },
                                enabled = zoom > 1f,
                                modifier = Modifier.size(40.dp),
                            ) { Icon(Icons.Default.ZoomOut, "Zoom out", tint = c.text) }
                        }
                    }
                    Text(
                        "drag to turn · pinch or wheel to zoom · double tap",
                        style = CRType.monoTiny,
                        color = c.textDim,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                    )
                }
            }
        }
    }
}

/** Keeps a zoomed model from being panned off screen: at most half its overhang each way. */
private fun clampPan(pan: Offset, zoom: Float, side: Float): Offset {
    val limit = side * (zoom - 1f) / 2f
    return Offset(pan.x.coerceIn(-limit, limit), pan.y.coerceIn(-limit, limit))
}
