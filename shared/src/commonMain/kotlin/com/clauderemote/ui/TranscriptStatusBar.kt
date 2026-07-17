package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListScope
import com.clauderemote.model.SessionActivity
import com.clauderemote.session.status.RemoteSessionStatus
import com.clauderemote.session.transcript.TranscriptEntry
import com.clauderemote.voice.SpeakerButton
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.Pill
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import com.mikepenz.markdown.m3.Markdown
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.launch

@Composable
internal fun StatusBar(
    entryCount: Int,
    contextPercent: Int?,
    sessionUsagePercent: Int?,
    weekUsagePercent: Int?,
    sessionResetMin: Int?,
    weekResetMin: Int?,
    latencyMs: Long?,
    todoPending: Int,
    activeSkill: String?,
    activeSubagents: Int,
    activity: SessionActivity?,
    connectionLabel: String?,
    showThinking: Boolean,
    showSystem: Boolean,
    onToggleThinking: () -> Unit,
    onToggleSystem: () -> Unit,
    fontScale: Float,
    onFontScaleDelta: (Float) -> Unit,
    searchOpen: Boolean,
    onToggleSearch: () -> Unit
) {
    val c = CRTheme.colors
    var filterMenu by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface)
            .border(width = 1.dp, color = c.border, shape = RoundedCornerShape(0.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActivityIndicator(activity)
                if (!connectionLabel.isNullOrBlank()) StatusChip(connectionLabel)
                StatusChip("$entryCount entries")
                StatusChip("ctx ${contextPercent?.let { "$it%" } ?: "—"}")
                StatusChip(buildUsageLabel("5h", sessionUsagePercent, sessionResetMin))
                StatusChip(buildUsageLabel("wk", weekUsagePercent, weekResetMin))
                if (todoPending > 0) StatusChip("↘ $todoPending")
                if (activeSubagents > 0) StatusChip("⚡ $activeSubagents")
                if (!activeSkill.isNullOrBlank()) StatusChip("skill: $activeSkill")
                if (latencyMs != null) StatusChip("${latencyMs}ms")
            }
            IconButton(
                onClick = onToggleSearch,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search transcript",
                    tint = if (searchOpen) c.accent else c.textDim,
                    modifier = Modifier.size(16.dp)
                )
            }
            Box {
                IconButton(
                    onClick = { filterMenu = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Text(
                        "▾",
                        style = CRType.monoTiny,
                        color = c.textDim
                    )
                }
                DropdownMenu(
                    expanded = filterMenu,
                    onDismissRequest = { filterMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Show thinking") },
                        trailingIcon = { Text(if (showThinking) "✓" else "") },
                        onClick = { onToggleThinking(); filterMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Show system") },
                        trailingIcon = { Text(if (showSystem) "✓" else "") },
                        onClick = { onToggleSystem(); filterMenu = false }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Font ${(fontScale * 100).toInt()}%", modifier = Modifier.weight(1f))
                                TextButton(onClick = { onFontScaleDelta(-0.1f) }) { Text("A−") }
                                TextButton(onClick = { onFontScaleDelta(0.1f) }) { Text("A+") }
                            }
                        },
                        onClick = {}
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityIndicator(activity: SessionActivity?) {
    val c = CRTheme.colors
    val (color, label) = when (activity) {
        SessionActivity.WORKING -> c.working to "working"
        SessionActivity.WAITING_FOR_INPUT -> c.ready to "ready"
        SessionActivity.APPROVAL_NEEDED -> c.approval to "approval"
        SessionActivity.DISCONNECTED -> c.disconnected to "offline"
        SessionActivity.IDLE -> c.idle to "idle"
        null -> return
    }
    val alpha = if (activity == SessionActivity.WORKING || activity == SessionActivity.APPROVAL_NEEDED) {
        val t = rememberInfiniteTransition(label = "activity-pulse")
        t.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        ).value
    } else 1f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(alpha)
                .background(color, CircleShape)
        )
        Text(
            label,
            style = CRType.mono,
            color = c.textDim
        )
    }
}

@Composable
internal fun WorkingSkeletonCard() {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val t = rememberInfiniteTransition(label = "skeleton")
    val alpha by t.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton-alpha"
    )
    CRCard(
        background = c.surface,
        borderColor = c.border,
        padding = PaddingValues(horizontal = m.cardPadH, vertical = m.cardPadV)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .background(c.working, CircleShape)
            )
            Text(
                "Claude is working…",
                style = CRType.mono,
                color = c.textDim.copy(alpha = alpha)
            )
        }
    }
}

/**
 * Bottom-of-chat banner shown while a permission / AskUserQuestion selector is
 * live on screen. Claude Code does not flush the AskUserQuestion tool_use to the
 * .jsonl until the user answers, so the real question card can't render yet —
 * this tells the user Claude is blocked on them and where to answer, instead of
 * the chat looking idle or (before the effectiveActivity fix) stuck "working…".
 */
@Composable
internal fun AwaitingAnswerCard() {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val t = rememberInfiniteTransition(label = "awaiting")
    val alpha by t.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "awaiting-alpha"
    )
    CRCard(
        background = c.surface,
        borderColor = c.approval,
        padding = PaddingValues(horizontal = m.cardPadH, vertical = m.cardPadV)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .background(c.approval, CircleShape)
            )
            Column {
                Text(
                    "Claude is asking you a question",
                    style = CRType.mono,
                    color = c.approval
                )
                Text(
                    "Open the terminal view to answer",
                    style = CRType.bodyDim,
                    color = c.textDim
                )
            }
        }
    }
}

/** Collapsed-middle expander row for an old turn: `▶ 23 steps · 1m 42s`. */
@Composable
internal fun TurnStepsRow(item: RenderItem.TurnSteps, onToggle: () -> Unit) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (item.expanded) "▼" else "▶",
            style = CRType.monoTiny,
            color = c.textDim,
            modifier = Modifier.padding(end = 6.dp)
        )
        val label = buildString {
            append(item.steps)
            append(if (item.steps == 1) " step" else " steps")
            item.durationMs?.let { append(" · ${formatDuration(it)}") }
        }
        Text(label, style = CRType.monoTiny, color = c.textDim)
    }
}

@Composable
internal fun TimeGapRow(label: String) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = c.border.copy(alpha = 0.4f))
        Text(
            label,
            style = CRType.monoTiny,
            color = c.textDim,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = c.border.copy(alpha = 0.4f))
    }
}

@Composable
private fun StatusChip(text: String) {
    Text(
        text,
        style = CRType.mono,
        color = CRTheme.colors.textDim
    )
}

/**
 * Format a usage chip label combining percentage and time-to-reset.
 */
private fun buildUsageLabel(prefix: String, pct: Int?, resetMin: Int?): String {
    val pctPart = pct?.let { "$it%" } ?: "—"
    val resetPart = resetMin?.let { formatReset(it) }
    return if (resetPart != null) "$prefix $pctPart · $resetPart" else "$prefix $pctPart"
}

private fun formatReset(minutes: Int): String {
    val d = minutes / 1440
    val h = (minutes % 1440) / 60
    val m = minutes % 60
    return when {
        d > 0 -> "${d}d${h}h"
        h > 0 -> "${h}h${m}m"
        else -> "${m}m"
    }
}

