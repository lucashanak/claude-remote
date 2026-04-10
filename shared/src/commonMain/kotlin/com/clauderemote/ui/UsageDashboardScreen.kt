package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.SessionActivity
import com.clauderemote.session.CostCalculator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageDashboardScreen(
    sessions: List<ClaudeSession>,
    sessionActivities: Map<String, SessionActivity>,
    contextPercents: Map<String, Int>,
    sessionUsagePercent: Int?,
    weekUsagePercent: Int?,
    usageTokens: CostCalculator.UsageTokens?,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage Dashboard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // Rate limit usage
            Text("Rate Limits", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }

            // Cost estimation
            if (usageTokens != null) {
                Text("Cost Estimate", style = MaterialTheme.typography.titleMedium)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val cost = CostCalculator.estimateCost(usageTokens)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Current session", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                CostCalculator.formatCost(cost),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        HorizontalDivider()
                        TokenRow("Input tokens", usageTokens.inputTokens)
                        TokenRow("Output tokens", usageTokens.outputTokens)
                        TokenRow("Cache write", usageTokens.cacheCreationTokens)
                        TokenRow("Cache read", usageTokens.cacheReadTokens)
                        TokenRow("Total", usageTokens.inputTokens + usageTokens.outputTokens +
                                usageTokens.cacheCreationTokens + usageTokens.cacheReadTokens)
                    }
                }
            }

            // Active sessions overview
            if (sessions.isNotEmpty()) {
                Text("Active Sessions", style = MaterialTheme.typography.titleMedium)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sessions.forEach { session ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val activity = sessionActivities[session.id]
                                val dotColor = when (activity) {
                                    SessionActivity.WORKING -> Color(0xFFFF9800)
                                    SessionActivity.WAITING_FOR_INPUT, SessionActivity.IDLE -> Color(0xFF4CAF50)
                                    SessionActivity.APPROVAL_NEEDED -> Color(0xFF2196F3)
                                    SessionActivity.DISCONNECTED -> Color(0xFFF44336)
                                    null -> Color(0xFF4CAF50)
                                }
                                Box(modifier = Modifier.size(8.dp).background(dotColor, shape = CircleShape))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(session.tabTitle, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${session.mode.displayName} / ${session.model.displayName} / ${session.durationText}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                val ctx = contextPercents[session.id]
                                if (ctx != null) {
                                    ContextChip(ctx)
                                }
                            }
                            if (session != sessions.last()) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun UsageBar(label: String, percent: Int?, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (percent != null) {
            val pct = percent.coerceIn(0, 100)
            val color = when {
                pct < 50 -> Color(0xFF4CAF50)
                pct < 80 -> Color(0xFFFF9800)
                else -> Color(0xFFF44336)
            }
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}

@Composable
private fun TokenRow(label: String, tokens: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatTokenCount(tokens), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ContextChip(percent: Int) {
    val color = when {
        percent < 50 -> Color(0xFF4CAF50)
        percent < 80 -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            "Ctx ${percent}%",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private fun formatTokenCount(tokens: Long): String = when {
    tokens < 1000 -> "$tokens"
    tokens < 1_000_000 -> "${String.format("%.1f", tokens / 1000.0)}k"
    else -> "${String.format("%.2f", tokens / 1_000_000.0)}M"
}
