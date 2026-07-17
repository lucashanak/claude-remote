package com.clauderemote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.SessionActivity
import com.clauderemote.model.SessionStatus
import com.clauderemote.session.CommandFetcher
import com.clauderemote.session.SlashCommand
import com.clauderemote.session.status.RemoteSessionStatus
import com.clauderemote.session.transcript.TranscriptEntry
import com.clauderemote.voice.MicButton
import com.clauderemote.voice.VoiceModeScreen
import com.clauderemote.voice.WakeWordListener
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.CRStatus
import com.clauderemote.ui.components.Pill
import com.clauderemote.ui.components.ServerGlyph
import com.clauderemote.ui.components.StatusIndicator
import com.clauderemote.ui.components.color
import com.clauderemote.ui.theme.CRTerminalView
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import com.clauderemote.ui.theme.LocalCRTerminalView
import kotlinx.coroutines.launch




@Composable
internal fun MiniBar(label: String, percent: Int) {
    val c = CRTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = CRType.monoTiny, color = c.textDim, modifier = Modifier.width(20.dp))
        Box(
            modifier = Modifier.width(40.dp).height(4.dp)
                .background(c.surface2, CircleShape)
        ) {
            val pct = percent.coerceIn(0, 100)
            val color = when {
                pct < 50 -> c.ready
                pct < 80 -> c.working
                else -> c.disconnected
            }
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(pct / 100f).background(color, CircleShape))
        }
        Text("${percent}%", style = CRType.monoTiny, color = c.textDim, modifier = Modifier.padding(start = 2.dp))
    }
}

/**
 * Compact chip showing the working-dir git branch, a dirty marker, and
 * optional ahead/behind counts. Rendered only when git status is non-null.
 */
@Composable
internal fun GitChip(status: com.clauderemote.model.GitStatus) {
    val c = CRTheme.colors
    val branchColor = if (status.dirty) c.working else c.ready
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .background(c.surface2, CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            status.branch + if (status.dirty) " ●" else "",
            style = CRType.monoTiny,
            color = branchColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 96.dp),
        )
        if (status.ahead > 0) {
            Text("↑${status.ahead}", style = CRType.monoTiny, color = c.textDim)
        }
        if (status.behind > 0) {
            Text("↓${status.behind}", style = CRType.monoTiny, color = c.textDim)
        }
    }
}

/** Compact chip showing the session's active Claude model. */
@Composable
internal fun ModelChip(model: com.clauderemote.model.ClaudeModel) {
    val c = CRTheme.colors
    Text(
        model.displayName,
        style = CRType.pill,
        color = c.accent,
        maxLines = 1,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .background(c.surface2, CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
internal fun CtrlButton(label: String, emphasized: Boolean = false, onClick: () -> Unit) {
    val c = CRTheme.colors
    val haptic = androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    FilledTonalButton(
        onClick = { hapticFeedback.performHapticFeedback(haptic); onClick() },
        modifier = Modifier.height(28.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (emphasized) c.accent else c.surface2
        )
    ) {
        Text(label, style = CRType.keyboardKey, color = if (emphasized) c.accentInk else c.text)
    }
}

/**
 * Map SessionActivity (or fallback to SessionStatus) to a dot color.
 */
internal fun activityDotColor(
    activity: com.clauderemote.model.SessionActivity?,
    status: SessionStatus
): Color = when (activity) {
    SessionActivity.WAITING_FOR_INPUT -> Color(0xFF4ADE80)
    SessionActivity.WORKING           -> Color(0xFFFBBF24)
    SessionActivity.APPROVAL_NEEDED   -> Color(0xFFFB923C)
    SessionActivity.IDLE              -> Color(0xFF94A3B8)
    SessionActivity.DISCONNECTED      -> Color(0xFFF87171)
    null -> when (status) {
        SessionStatus.ACTIVE       -> Color(0xFF4ADE80)
        SessionStatus.CONNECTING   -> Color(0xFFFBBF24)
        SessionStatus.DISCONNECTED, SessionStatus.ERROR -> Color(0xFFF87171)
    }
}

/**
 * Live, self-ticking label for an in-progress reconnect (or null when idle).
 * Re-derives once a second so the "Retrying in Ns" countdown advances without
 * a manual timer at each call site. [compact] yields a short "↻ Ns"/"↻" form
 * for tight grid-pane pills; the full form is used on the disconnected banner.
 */
@Composable
internal fun rememberReconnectLabel(
    info: com.clauderemote.session.ReconnectInfo?,
    compact: Boolean = false,
): String? {
    if (info == null) return null
    return androidx.compose.runtime.produceState(
        initialValue = reconnectLabelOf(info, compact),
        info, compact,
    ) {
        while (true) {
            value = reconnectLabelOf(info, compact)
            kotlinx.coroutines.delay(1000)
        }
    }.value
}

private fun reconnectLabelOf(info: com.clauderemote.session.ReconnectInfo, compact: Boolean): String {
    val next = info.nextRetryAtMillis
    return when {
        next != null -> {
            val secs = ((next - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
            when {
                secs <= 0L -> if (compact) "↻" else "Retrying…"
                compact -> "↻ ${secs}s"
                else -> "Retrying in ${secs}s"
            }
        }
        compact -> "↻"
        info.maxAttempts > 0 -> "Reconnecting… (${info.attempt}/${info.maxAttempts})"
        else -> "Reconnecting…"
    }
}
