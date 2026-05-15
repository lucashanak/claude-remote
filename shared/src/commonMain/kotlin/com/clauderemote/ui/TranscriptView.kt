package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clauderemote.session.status.RemoteSessionStatus
import com.clauderemote.session.transcript.TranscriptEntry
import kotlinx.coroutines.launch

/**
 * Read-only structured view of a Claude Code transcript. Renders user
 * prompts, assistant text (with code-fence aware monospace), tool calls
 * with collapsible results, and slash commands. Entire view is wrapped
 * in a SelectionContainer so any text can be copied.
 */
@Composable
fun TranscriptView(
    entries: List<TranscriptEntry>,
    modifier: Modifier = Modifier,
    contextPercent: Int? = null,
    sessionUsagePercent: Int? = null,
    weekUsagePercent: Int? = null,
    latencyMs: Long? = null,
    remoteStatus: RemoteSessionStatus? = null
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showSystem by remember { mutableStateOf(false) }
    var showThinking by remember { mutableStateOf(false) }

    val filtered = remember(entries, showSystem, showThinking) {
        entries.filter { entry ->
            when (entry) {
                is TranscriptEntry.SystemNote -> showSystem
                is TranscriptEntry.AssistantThinking -> showThinking
                else -> true
            }
        }
    }
    // Find the most recent TodoWrite tool_use to derive an open-todo count.
    // Cheap: scans backwards from the end and stops at the first match.
    val todoPending = remember(entries) { countOpenTodos(entries) }

    // Auto-scroll to bottom when new entries arrive and the user is already near the bottom.
    LaunchedEffect(filtered.size) {
        if (filtered.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearBottom = lastVisible >= filtered.size - 3
        if (nearBottom) {
            scope.launch { listState.animateScrollToItem(filtered.lastIndex) }
        }
    }

    Column(modifier = modifier) {
        StatusBar(
            entryCount = entries.size,
            contextPercent = contextPercent,
            sessionUsagePercent = sessionUsagePercent,
            weekUsagePercent = weekUsagePercent,
            latencyMs = latencyMs,
            todoPending = todoPending,
            activeSkill = remoteStatus?.activeSkill,
            activeSubagents = remoteStatus?.activeSubagents ?: 0
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = showThinking,
                onClick = { showThinking = !showThinking },
                label = { Text("Show thinking", style = MaterialTheme.typography.labelSmall) }
            )
            FilterChip(
                selected = showSystem,
                onClick = { showSystem = !showSystem },
                label = { Text("Show system", style = MaterialTheme.typography.labelSmall) }
            )
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Waiting for transcript…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(filtered, key = { it.id }) { entry ->
                    when (entry) {
                        is TranscriptEntry.UserPrompt -> UserPromptCard(entry)
                        is TranscriptEntry.SlashCommand -> SlashCommandRow(entry)
                        is TranscriptEntry.AssistantText -> AssistantTextCard(entry)
                        is TranscriptEntry.AssistantThinking -> ThinkingCard(entry)
                        is TranscriptEntry.ToolCall -> ToolCallCard(entry)
                        is TranscriptEntry.ToolResult -> ToolResultCard(entry)
                        is TranscriptEntry.SystemNote -> SystemNoteRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun UserPromptCard(entry: TranscriptEntry.UserPrompt) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            RoleHeader("You", entry.timestamp)
            Spacer(Modifier.height(4.dp))
            RichBody(entry.text)
        }
    }
}

@Composable
private fun SlashCommandRow(entry: TranscriptEntry.SlashCommand) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            "/${entry.name}${if (entry.args.isNotBlank()) " ${entry.args}" else ""}",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun AssistantTextCard(entry: TranscriptEntry.AssistantText) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            RoleHeader(entry.model ?: "Claude", entry.timestamp)
            Spacer(Modifier.height(4.dp))
            RichBody(entry.text)
        }
    }
}

@Composable
private fun ThinkingCard(entry: TranscriptEntry.AssistantThinking) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                if (expanded) "▼ thinking" else "▶ thinking",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ToolCallCard(entry: TranscriptEntry.ToolCall) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (expanded) "▼" else "▶",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    entry.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.inputSummary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())
                )
            }
            if (expanded && entry.fullInput.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                MonospaceBlock(entry.fullInput)
            }
        }
    }
}

@Composable
private fun ToolResultCard(entry: TranscriptEntry.ToolResult) {
    var expanded by remember { mutableStateOf(false) }
    val lines = entry.text.lines()
    val preview = lines.take(3).joinToString("\n")
    val hasMore = lines.size > 3 || entry.text.length > preview.length
    val bg =
        if (entry.isError) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
        color = bg,
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = hasMore) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (entry.isError) "✗ result" else "↳ result",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.isError) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp)
                )
                if (hasMore) {
                    Text(
                        if (expanded) "▼" else "▶",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            MonospaceBlock(if (expanded) entry.text else preview)
            if (!expanded && hasMore) {
                Text(
                    "(${lines.size - 3} more lines — tap to expand)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SystemNoteRow(entry: TranscriptEntry.SystemNote) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(
                "system · ${entry.subtype}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (entry.text.isNotBlank()) {
                Text(
                    entry.text.take(500),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RoleHeader(role: String, timestamp: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            role,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
        if (timestamp != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                formatTimestamp(timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Render a chunk that may contain ``` code fences. Splits into alternating
 * prose / code segments; code segments get monospace + scrollable horiz panel.
 */
@Composable
private fun RichBody(text: String) {
    val segments = remember(text) { splitCodeFences(text) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (seg in segments) {
            if (seg.isCode) {
                MonospaceBlock(seg.text)
            } else {
                Text(
                    seg.text,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun MonospaceBlock(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(4.dp)
    ) {
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private data class Segment(val text: String, val isCode: Boolean)

private fun splitCodeFences(text: String): List<Segment> {
    val out = mutableListOf<Segment>()
    val fence = "```"
    var i = 0
    while (i < text.length) {
        val start = text.indexOf(fence, i)
        if (start < 0) {
            val tail = text.substring(i)
            if (tail.isNotEmpty()) out += Segment(tail, false)
            break
        }
        if (start > i) {
            out += Segment(text.substring(i, start).trimEnd('\n'), false)
        }
        // Skip optional language tag on the same line.
        val langEnd = text.indexOf('\n', start + fence.length).let { if (it < 0) text.length else it }
        val codeStart = (langEnd + 1).coerceAtMost(text.length)
        val end = text.indexOf(fence, codeStart)
        if (end < 0) {
            out += Segment(text.substring(codeStart), true)
            break
        }
        out += Segment(text.substring(codeStart, end).trimEnd('\n'), true)
        i = end + fence.length
        if (i < text.length && text[i] == '\n') i++
    }
    return out
}

private fun formatTimestamp(iso: String): String {
    // 2026-05-15T15:24:02.384Z → 15:24:02
    val t = iso.substringAfter('T').substringBefore('.').substringBefore('Z')
    return t.take(8)
}

@Composable
private fun StatusBar(
    entryCount: Int,
    contextPercent: Int?,
    sessionUsagePercent: Int?,
    weekUsagePercent: Int?,
    latencyMs: Long?,
    todoPending: Int,
    activeSkill: String?,
    activeSubagents: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusChip("$entryCount entries")
            if (contextPercent != null) StatusChip("ctx ${contextPercent}%")
            if (sessionUsagePercent != null) StatusChip("5h ${sessionUsagePercent}%")
            if (weekUsagePercent != null) StatusChip("wk ${weekUsagePercent}%")
            if (todoPending > 0) StatusChip("↘ $todoPending")
            if (activeSubagents > 0) StatusChip("⚡ $activeSubagents")
            if (!activeSkill.isNullOrBlank()) StatusChip("skill: $activeSkill")
            if (latencyMs != null) StatusChip("${latencyMs}ms")
        }
    }
}

@Composable
private fun StatusChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Walk the entries backwards to find the most recent TodoWrite tool_use,
 * parse its `todos` array, and return the count of items whose status is
 * not "completed". Returns 0 if no TodoWrite has been issued yet.
 */
private fun countOpenTodos(entries: List<TranscriptEntry>): Int {
    val last = entries.asReversed().firstOrNull {
        it is TranscriptEntry.ToolCall && it.name == "TodoWrite"
    } as? TranscriptEntry.ToolCall ?: return 0
    val json = last.fullInput
    if (json.isBlank()) return 0
    // Cheap: count `"status": "pending"` and `"status": "in_progress"`
    // occurrences. Avoids dragging the json parser into the UI layer.
    val pending = Regex("\"status\"\\s*:\\s*\"(pending|in_progress)\"")
        .findAll(json).count()
    return pending
}
