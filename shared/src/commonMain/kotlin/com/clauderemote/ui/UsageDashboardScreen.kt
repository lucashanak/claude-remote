package com.clauderemote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.SessionActivity
import com.clauderemote.session.CostCalculator
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.CRStatus
import com.clauderemote.ui.components.Pill
import com.clauderemote.ui.components.StatusIndicator
import com.clauderemote.ui.components.color
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageDashboardScreen(
    sessions: List<ClaudeSession>,
    sessionActivities: Map<String, SessionActivity>,
    contextPercents: Map<String, Int>,
    sessionUsagePercent: Int?,
    weekUsagePercent: Int?,
    usageTokens: CostCalculator.UsageTokens?,
    daily: List<Float> = emptyList(),
    onBack: () -> Unit
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics

    // Range toggle for potential chart (7d / 30d)
    var rangeIndex by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = c.surface,
                    titleContentColor = c.text,
                    navigationIconContentColor = c.textDim,
                ),
                title = { Text("Usage Dashboard", style = CRType.cardTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.textDim)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = m.sectionPad, vertical = m.sectionTopGap),
            verticalArrangement = Arrangement.spacedBy(m.cardGap)
        ) {
            // ── 2-column summary stats ─────────────────────────────────────
            if (usageTokens != null) {
                val cost = CostCalculator.estimateCost(usageTokens)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(m.cardGap)
                ) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        label = "Session cost",
                        value = CostCalculator.formatCost(cost),
                        caption = "${sessions.size} session${if (sessions.size != 1) "s" else ""}",
                    )
                    val totalTokens = usageTokens.inputTokens + usageTokens.outputTokens +
                        usageTokens.cacheCreationTokens + usageTokens.cacheReadTokens
                    StatCard(
                        modifier = Modifier.weight(1f),
                        label = "Total tokens",
                        value = formatTokenCount(totalTokens),
                        caption = "this session",
                    )
                }
            }

            // ── Daily cost bars ─────────────────────────────────────────────
            if (daily.isNotEmpty()) {
                DashSection("Daily Usage") {
                    val accentColor = c.accent
                    val maxVal = daily.max().coerceAtLeast(0.001f)
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        val barCount = daily.size
                        val gap = 2.dp.toPx()
                        val barWidth = (size.width - gap * (barCount - 1)) / barCount
                        daily.forEachIndexed { i, value ->
                            val barHeight = (value / maxVal) * size.height
                            val x = i * (barWidth + gap)
                            drawRect(
                                color = accentColor,
                                topLeft = androidx.compose.ui.geometry.Offset(x, size.height - barHeight),
                                size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
                            )
                        }
                    }
                }
            }

            // ── Rate limits ─────────────────────────────────────────────────
            DashSection("Rate Limits") {
                UsageBar(
                    label = "5-hour window",
                    percent = sessionUsagePercent,
                    description = if (sessionUsagePercent != null) "${sessionUsagePercent}% used" else "No data"
                )
                UsageBar(
                    label = "Weekly limit",
                    percent = weekUsagePercent,
                    description = if (weekUsagePercent != null) "${weekUsagePercent}% used" else "No data"
                )
            }

            // ── Cost breakdown ──────────────────────────────────────────────
            if (usageTokens != null) {
                DashSection("Cost Estimate") {
                    val cost = CostCalculator.estimateCost(usageTokens)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Current session", style = CRType.cardTitle, color = c.text)
                        Text(
                            CostCalculator.formatCost(cost),
                            style = CRType.cardTitle,
                            color = c.accent
                        )
                    }
                    HorizontalDivider(color = c.border)
                    TokenRow("Input tokens", usageTokens.inputTokens)
                    TokenRow("Output tokens", usageTokens.outputTokens)
                    TokenRow("Cache write", usageTokens.cacheCreationTokens)
                    TokenRow("Cache read", usageTokens.cacheReadTokens)
                    HorizontalDivider(color = c.border)
                    val total = usageTokens.inputTokens + usageTokens.outputTokens +
                        usageTokens.cacheCreationTokens + usageTokens.cacheReadTokens
                    TokenRow("Total", total, highlight = true)
                }
            }

            // ── Active sessions ─────────────────────────────────────────────
            if (sessions.isNotEmpty()) {
                DashSection("Active Sessions · ${sessions.size}") {
                    sessions.forEachIndexed { index, session ->
                        if (index > 0) HorizontalDivider(color = c.border)
                        SessionUsageRow(
                            session = session,
                            activity = sessionActivities[session.id],
                            contextPercent = contextPercents[session.id],
                        )
                    }
                }
            }

            // ── Disclaimer ──────────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface2, RoundedCornerShape(m.cardRadius))
                    .padding(12.dp)
            ) {
                Text(
                    "Cost estimates are based on local token counting and may differ from Anthropic billing.",
                    style = CRType.bodyDim,
                    color = c.textDim
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Local composables ──────────────────────────────────────────────────────

@Composable
private fun DashSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = CRTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = CRType.sectionH, color = c.textDim)
        CRCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    caption: String,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    Box(
        modifier
            .background(c.surface, RoundedCornerShape(m.cardRadius))
            .border(1.dp, c.border, RoundedCornerShape(m.cardRadius))
            .padding(m.cardPad)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = CRType.bodyDim, color = c.textDim)
            Text(value, style = CRType.cardTitle.copy(fontSize = CRType.h2), color = c.accent)
            Text(caption, style = CRType.bodyDim, color = c.textDim)
        }
    }
}

@Composable
private fun UsageBar(label: String, percent: Int?, description: String) {
    val c = CRTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = CRType.cardTitle, color = c.text)
            Text(description, style = CRType.bodyDim, color = c.textDim)
        }
        if (percent != null) {
            val pct = percent.coerceIn(0, 100)
            val barColor = when {
                pct < 50 -> c.ready
                pct < 80 -> c.working
                else -> c.disconnected
            }
            val trackShape = RoundedCornerShape(999.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(trackShape)
                    .background(c.surface2)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(pct / 100f)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(listOf(barColor, barColor.copy(alpha = 0.7f))),
                            trackShape
                        )
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.surface2)
            )
        }
    }
}

@Composable
private fun TokenRow(label: String, tokens: Long, highlight: Boolean = false) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = CRType.bodyDim,
            color = if (highlight) c.text else c.textDim
        )
        Text(
            formatTokenCount(tokens),
            style = CRType.mono,
            color = if (highlight) c.accent else c.text
        )
    }
}

@Composable
private fun SessionUsageRow(
    session: ClaudeSession,
    activity: SessionActivity?,
    contextPercent: Int?,
) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val crStatus = when (activity) {
            SessionActivity.WORKING -> CRStatus.Working
            SessionActivity.WAITING_FOR_INPUT -> CRStatus.Ready
            SessionActivity.APPROVAL_NEEDED -> CRStatus.Approval
            SessionActivity.DISCONNECTED -> CRStatus.Disconnected
            else -> CRStatus.Idle
        }
        StatusIndicator(crStatus)
        Column(modifier = Modifier.weight(1f)) {
            Text(session.tabTitle, style = CRType.cardTitle, color = c.text)
            Text(
                "${session.mode.displayName} · ${session.model.displayName} · ${session.durationText}",
                style = CRType.bodyDim,
                color = c.textDim
            )
        }
        if (contextPercent != null) {
            ContextChip(contextPercent)
        }
    }
}

@Composable
private fun ContextChip(percent: Int) {
    val c = CRTheme.colors
    val color = when {
        percent < 50 -> c.ready
        percent < 80 -> c.working
        else -> c.disconnected
    }
    Pill(
        text = "Ctx ${percent}%",
        background = color.copy(alpha = 0.15f),
        foreground = color,
    )
}

private fun formatTokenCount(tokens: Long): String = when {
    tokens < 1000 -> "$tokens"
    tokens < 1_000_000 -> "${"%.1f".format(tokens / 1000.0)}k"
    else -> "${"%.2f".format(tokens / 1_000_000.0)}M"
}
