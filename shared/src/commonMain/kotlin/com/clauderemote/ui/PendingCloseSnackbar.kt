package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clauderemote.session.PendingClosesState
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import kotlinx.coroutines.delay

/**
 * The undo snackbar for closed tabs (Jarvis F0a). Pure view over [PendingClosesState]: one line per refused close
 * ("Neuzavřeno … · Zavřít i tak"), then one line for everything still pending ("Zavřeno … · Vrátit" or, with more
 * than one, "Zavřeno N · Vrátit vše"), its warnings, and the countdown of the close that comes next. The countdown
 * is visible on purpose: a hidden timeout is what makes an undo feel like a trap.
 *
 * Every text has an explicit CRType style: on desktop this runs in its own window, whose MaterialTheme has no
 * pinned typography (see CRType's note on the system-font crash).
 */
@Composable
fun PendingCloseSnackbar(
    state: PendingClosesState,
    remainingMs: (String) -> Long?,
    windowMs: Long,
    onUndo: (String) -> Unit,
    onUndoAll: () -> Unit,
    onForceClose: (String) -> Unit,
    onDismissBlocked: (String) -> Unit,
    onHover: (Boolean) -> Unit,
) {
    val c = CRTheme.colors
    // Redraw the countdown a few times a second; the deadlines themselves live in PendingCloses. The snackbar is
    // composed only while it has something to show, so the loop ends with it.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            tick++
        }
    }
    // A pause must not outlive the snackbar: it leaves while the pointer is still on it (last undo, window gone).
    DisposableEffect(Unit) { onDispose { onHover(false) } }

    Column(
        modifier = Modifier
            .widthIn(min = 280.dp, max = 440.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface2)
            .border(1.dp, c.border, RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        when (awaitPointerEvent().type) {
                            PointerEventType.Enter -> onHover(true)
                            PointerEventType.Exit -> onHover(false)
                        }
                    }
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (b in state.blocked.takeLast(2)) {
            SnackLine(
                text = "Neuzavřeno: ${b.label} · ${b.reason}",
                color = c.text,
                action = "Zavřít i tak",
                actionColor = c.tintRed,
                onAction = { onForceClose(b.sessionId) },
                onDismiss = { onDismissBlocked(b.sessionId) },
            )
        }
        val pending = state.pending
        if (pending.isNotEmpty()) {
            if (pending.size == 1) {
                val p = pending.single()
                SnackLine("Zavřeno: ${p.label}", c.text, "Vrátit", c.accent, onAction = { onUndo(p.sessionId) })
            } else {
                SnackLine("Zavřeno ${pending.size}", c.text, "Vrátit vše", c.accent, onAction = onUndoAll)
            }
            val warned = pending.filter { it.warning != null }
            for (p in warned.take(3)) {
                val who = if (pending.size > 1) "${p.label}: " else ""
                Text(
                    "⚠ $who${p.warning}",
                    style = CRType.bodyDim,
                    color = c.tintYellow,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val next = remember(tick, pending, state.paused) {
                pending.mapNotNull { remainingMs(it.sessionId) }.minOrNull() ?: 0L
            }
            Countdown(next, windowMs, paused = state.paused)
        }
    }
}

@Composable
private fun SnackLine(
    text: String,
    color: Color,
    action: String,
    actionColor: Color,
    onAction: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    val c = CRTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = CRType.cardTitle,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            action.uppercase(),
            style = CRType.pill,
            color = actionColor,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onAction)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        if (onDismiss != null) {
            Text(
                "✕",
                style = CRType.pill,
                color = c.textDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Countdown(remainingMs: Long, windowMs: Long, paused: Boolean) {
    val c = CRTheme.colors
    val fraction = if (windowMs <= 0) 0f else (remainingMs.toFloat() / windowMs).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(c.border),
        ) {
            Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(if (paused) c.textDim else c.accent))
        }
        Spacer(Modifier.width(8.dp))
        val secs = (remainingMs + 999) / 1000
        Text(if (paused) "$secs s ‖" else "$secs s", style = CRType.mono, color = c.textDim)
    }
}
