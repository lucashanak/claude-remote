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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import com.clauderemote.model.SessionActivity
import com.clauderemote.session.status.RemoteSessionStatus
import com.clauderemote.session.transcript.TranscriptEntry
import com.mikepenz.markdown.m3.Markdown
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
    sessionResetMin: Int? = null,
    weekResetMin: Int? = null,
    latencyMs: Long? = null,
    remoteStatus: RemoteSessionStatus? = null,
    activity: SessionActivity? = null
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showSystem by remember { mutableStateOf(false) }
    var showThinking by remember { mutableStateOf(false) }
    var fontScale by rememberSaveable { mutableStateOf(1f) }

    // Pair each ToolCall with its matching ToolResult so we can render them
    // in a single collapsible row. Orphan ToolResults (no preceding call,
    // streamed before its call appears, etc.) still render standalone.
    val resultsByToolId = remember(entries) {
        entries.filterIsInstance<TranscriptEntry.ToolResult>()
            .mapNotNull { r -> r.toolUseId?.let { it to r } }
            .toMap()
    }
    val pairedResultIds = remember(entries, resultsByToolId) {
        entries.filterIsInstance<TranscriptEntry.ToolCall>()
            .mapNotNull { resultsByToolId[it.toolUseId]?.id }
            .toSet()
    }

    val filtered = remember(entries, showSystem, showThinking, pairedResultIds) {
        entries.filter { entry ->
            when (entry) {
                is TranscriptEntry.SystemNote -> showSystem
                is TranscriptEntry.AssistantThinking -> showThinking
                // Paired results are rendered inside their tool-call card.
                is TranscriptEntry.ToolResult -> entry.id !in pairedResultIds
                else -> true
            }
        }
    }
    // Find the most recent TodoWrite tool_use to derive an open-todo count.
    // Cheap: scans backwards from the end and stops at the first match.
    val todoPending = remember(entries) { countOpenTodos(entries) }

    // Decay a stuck WORKING state. The prompt detector that drives `activity`
    // runs off the terminal's rendered screen — when the user is in the
    // transcript view the terminal is disposed, the detector can't read
    // anything, and a WORKING reading from before the toggle freezes here.
    // If no new entry has arrived for ~6 s, treat the state as ready so the
    // pulsing dot + skeleton card stop misleading the user.
    var lastChangeAt by remember { mutableStateOf(0L) }
    LaunchedEffect(entries.size) { lastChangeAt = System.currentTimeMillis() }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activity == SessionActivity.WORKING) {
        while (activity == SessionActivity.WORKING) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val effectiveActivity = when {
        activity != SessionActivity.WORKING -> activity
        lastChangeAt > 0 && now - lastChangeAt > 6_000 -> SessionActivity.WAITING_FOR_INPUT
        else -> activity
    }

    val skeletonShowing = effectiveActivity == SessionActivity.WORKING

    Column(modifier = modifier) {
        StatusBar(
            entryCount = entries.size,
            contextPercent = contextPercent,
            sessionUsagePercent = sessionUsagePercent,
            weekUsagePercent = weekUsagePercent,
            sessionResetMin = sessionResetMin,
            weekResetMin = weekResetMin,
            latencyMs = latencyMs,
            todoPending = todoPending,
            activeSkill = remoteStatus?.activeSkill,
            activeSubagents = remoteStatus?.activeSubagents ?: 0,
            activity = effectiveActivity,
            showThinking = showThinking,
            showSystem = showSystem,
            onToggleThinking = { showThinking = !showThinking },
            onToggleSystem = { showSystem = !showSystem },
            fontScale = fontScale,
            onFontScaleDelta = { delta ->
                fontScale = (fontScale + delta).coerceIn(0.7f, 1.6f)
            }
        )

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

        val baseDensity = LocalDensity.current
        // Pre-group consecutive ToolCalls so a run of Read/Edit/Bash collapses
        // to one tight stack instead of N bordered cards. Other entries pass
        // through unchanged as singletons.
        val rendered = remember(filtered) { groupConsecutiveTools(filtered) }
        // Auto-scroll based on the *rendered* list size (post-grouping +
        // skeleton), not the filtered entry count — grouping fuses runs of
        // ToolCalls into one LazyColumn item, so filtered.size and the
        // actual item count diverge.
        var didInitialJump by remember { mutableStateOf(false) }
        val itemsCount = rendered.size + if (skeletonShowing) 1 else 0
        LaunchedEffect(itemsCount) {
            if (itemsCount <= 0) return@LaunchedEffect
            val lastIdx = itemsCount - 1
            if (!didInitialJump) {
                didInitialJump = true
                listState.scrollToItem(lastIdx)
                return@LaunchedEffect
            }
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val nearBottom = lastVisible >= lastIdx - 3
            if (nearBottom) {
                scope.launch { listState.animateScrollToItem(lastIdx) }
            }
        }
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * fontScale
            )
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(
                        items = rendered,
                        key = { item ->
                            when (item) {
                                is RenderItem.Single -> item.entry.id
                                is RenderItem.ToolGroup -> "tg:" + item.calls.first().id
                            }
                        }
                    ) { item ->
                        when (item) {
                            is RenderItem.Single -> when (val e = item.entry) {
                                is TranscriptEntry.UserPrompt -> UserPromptCard(e)
                                is TranscriptEntry.SlashCommand -> SlashCommandRow(e)
                                is TranscriptEntry.AssistantText -> AssistantTextCard(e)
                                is TranscriptEntry.AssistantThinking -> ThinkingCard(e)
                                is TranscriptEntry.ToolCall -> ToolRow(e, resultsByToolId[e.toolUseId])
                                is TranscriptEntry.ToolResult -> ToolResultCard(e)
                                is TranscriptEntry.SystemNote -> SystemNoteRow(e)
                            }
                            is RenderItem.ToolGroup -> ToolGroupBlock(item.calls, resultsByToolId)
                        }
                    }
                    if (effectiveActivity == SessionActivity.WORKING) {
                        item(key = "__working_skeleton__") { WorkingSkeletonCard() }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserPromptCard(entry: TranscriptEntry.UserPrompt) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 600.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (entry.timestamp != null) {
                    Text(
                        formatTimestamp(entry.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.height(2.dp))
                }
                RichBody(entry.text)
            }
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        RoleHeader(entry.model ?: "Claude", entry.timestamp)
        Spacer(Modifier.height(2.dp))
        RichBody(entry.text)
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

/**
 * Compact one-line tool row: glyph · name · summary · status indicator.
 * Expanded reveals input + result indented under the row, no card framing.
 */
@Composable
private fun ToolRow(
    entry: TranscriptEntry.ToolCall,
    result: TranscriptEntry.ToolResult?
) {
    var expanded by remember { mutableStateOf(false) }
    val errorTint = result?.isError == true
    val accent = when {
        errorTint -> MaterialTheme.colorScheme.error
        result == null -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                glyphFor(entry.name),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = accent,
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(
                entry.name,
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                entry.inputSummary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())
            )
            if (result == null) {
                val t = rememberInfiniteTransition(label = "tool-pending")
                val pulseAlpha by t.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "tool-pending-alpha"
                )
                Text(
                    "●",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp).alpha(pulseAlpha)
                )
            } else if (errorTint) {
                Text(
                    "!",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
        if (expanded) {
            ToolExpandedDetail(entry, result)
        }
    }
}

@Composable
private fun ToolExpandedDetail(
    entry: TranscriptEntry.ToolCall,
    result: TranscriptEntry.ToolResult?
) {
    val errorTint = result?.isError == true
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 2.dp, bottom = 4.dp)
    ) {
        when (entry.name) {
            "Edit" -> EditDiffBlock(entry.fullInput)
            "Write" -> WriteDiffBlock(entry.fullInput)
            else -> if (entry.fullInput.isNotBlank()) IndentedMono(entry.fullInput)
        }
        if (result != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (errorTint) "error" else "result",
                style = MaterialTheme.typography.labelSmall,
                color = if (errorTint) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            IndentedMono(result.text, error = errorTint)
        }
    }
}

/**
 * Render an Edit tool_use input as a unified diff: each old_string line
 * prefixed with red '−', each new_string line with green '+'. Falls back
 * to raw JSON if the input doesn't have the expected shape.
 */
@Composable
private fun EditDiffBlock(fullInput: String) {
    val parsed = remember(fullInput) { parseEditInput(fullInput) }
    if (parsed == null) {
        IndentedMono(fullInput)
        return
    }
    val (path, oldStr, newStr) = parsed
    Column(modifier = Modifier.fillMaxWidth()) {
        if (path.isNotBlank()) {
            Text(
                path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
        }
        DiffPane(removed = oldStr, added = newStr)
    }
}

@Composable
private fun WriteDiffBlock(fullInput: String) {
    val parsed = remember(fullInput) { parseWriteInput(fullInput) }
    if (parsed == null) {
        IndentedMono(fullInput)
        return
    }
    val (path, content) = parsed
    Column(modifier = Modifier.fillMaxWidth()) {
        if (path.isNotBlank()) {
            Text(
                path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
        }
        DiffPane(removed = "", added = content)
    }
}

@Composable
private fun DiffPane(removed: String, added: String) {
    val errorBg = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    val addBg = androidx.compose.ui.graphics.Color(0xFF1E4620).copy(alpha = 0.45f)
    val errorFg = MaterialTheme.colorScheme.error
    val addFg = androidx.compose.ui.graphics.Color(0xFF7EE787)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        if (removed.isNotBlank()) {
            removed.lines().forEach { line ->
                DiffLine(prefix = "−", text = line, prefixColor = errorFg, background = errorBg)
            }
        }
        if (added.isNotBlank()) {
            added.lines().forEach { line ->
                DiffLine(prefix = "+", text = line, prefixColor = addFg, background = addBg)
            }
        }
    }
}

@Composable
private fun DiffLine(
    prefix: String,
    text: String,
    prefixColor: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            prefix,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = prefixColor,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun parseEditInput(json: String): Triple<String, String, String>? {
    if (json.isBlank()) return null
    return try {
        val obj = kotlinx.serialization.json.Json
            .parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject ?: return null
        val path = obj["file_path"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: ""
        val oldS = obj["old_string"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: ""
        val newS = obj["new_string"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: ""
        if (oldS.isEmpty() && newS.isEmpty()) return null
        Triple(path, oldS, newS)
    } catch (_: Throwable) { null }
}

private fun parseWriteInput(json: String): Pair<String, String>? {
    if (json.isBlank()) return null
    return try {
        val obj = kotlinx.serialization.json.Json
            .parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject ?: return null
        val path = obj["file_path"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: ""
        val content = obj["content"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        } ?: return null
        path to content
    } catch (_: Throwable) { null }
}

@Composable
private fun IndentedMono(text: String, error: Boolean = false) {
    val border = if (error) MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                 else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .heightIn(min = 16.dp)
                .background(border)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ToolGroupBlock(
    calls: List<TranscriptEntry.ToolCall>,
    results: Map<String, TranscriptEntry.ToolResult>
) {
    Column {
        for (call in calls) {
            ToolRow(call, results[call.toolUseId])
        }
    }
}

// Single neutral glyph for every tool. Status (pending/error/done) is
// communicated via color and the trailing indicator, not via the prefix
// shape — matches the codicon-style restraint of the Claude Code chat
// panel where every tool call leads with the same arrow.
private fun glyphFor(name: String): String = "▸"

private sealed class RenderItem {
    data class Single(val entry: TranscriptEntry) : RenderItem()
    data class ToolGroup(val calls: List<TranscriptEntry.ToolCall>) : RenderItem()
}

/**
 * Walk the entries and fuse any run of two-or-more consecutive ToolCall
 * entries into a single ToolGroup item. Single tool calls render as
 * standalone rows (avoids creating a singleton group around an isolated
 * tool call between two assistant text blocks).
 */
private fun groupConsecutiveTools(entries: List<TranscriptEntry>): List<RenderItem> {
    val out = ArrayList<RenderItem>(entries.size)
    var i = 0
    while (i < entries.size) {
        val e = entries[i]
        if (e is TranscriptEntry.ToolCall) {
            var j = i + 1
            while (j < entries.size && entries[j] is TranscriptEntry.ToolCall) j++
            if (j - i >= 2) {
                @Suppress("UNCHECKED_CAST")
                val run = entries.subList(i, j).toList() as List<TranscriptEntry.ToolCall>
                out += RenderItem.ToolGroup(run)
                i = j
                continue
            }
        }
        out += RenderItem.Single(e)
        i++
    }
    return out
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
 * Render a chunk of markdown using multiplatform-markdown-renderer.
 * Handles headers, lists, tables, bold/italic, inline code, code fences,
 * blockquotes, and links. Typography and colors are tuned for a denser,
 * terminal-tinted look: tighter line-height on body, monospace inline
 * code blocks, and accent colors borrowed from a typical terminal
 * palette (green for emphasis, cyan for links).
 */
@Composable
private fun RichBody(text: String) {
    val base = MaterialTheme.typography
    val mono = androidx.compose.ui.text.font.FontFamily.Monospace
    val body = base.bodySmall.copy(
        lineHeight = base.bodySmall.fontSize * 1.15f
    )
    val codeStyle = base.labelSmall.copy(fontFamily = mono)
    val typography = com.mikepenz.markdown.m3.markdownTypography(
        h1 = body.copy(fontWeight = FontWeight.Bold, fontSize = base.titleSmall.fontSize),
        h2 = body.copy(fontWeight = FontWeight.Bold, fontSize = base.titleSmall.fontSize),
        h3 = body.copy(fontWeight = FontWeight.Bold),
        h4 = body.copy(fontWeight = FontWeight.Bold),
        h5 = body.copy(fontWeight = FontWeight.Bold),
        h6 = body.copy(fontWeight = FontWeight.Bold),
        text = body,
        paragraph = body,
        quote = body.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
        code = codeStyle,
        inlineCode = codeStyle,
        list = body,
        link = body.copy(
            color = androidx.compose.ui.graphics.Color(0xFF6FB8FF),
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
        )
    )
    val padding = com.mikepenz.markdown.model.markdownPadding(
        block = 2.dp,
        list = 2.dp,
        listItemTop = 0.dp,
        listItemBottom = 0.dp,
        listIndent = 12.dp,
        indentList = 12.dp
    )
    val colors = com.mikepenz.markdown.m3.markdownColor(
        text = MaterialTheme.colorScheme.onSurface,
        codeText = androidx.compose.ui.graphics.Color(0xFFE5C07B),     // terminal yellow
        inlineCodeText = androidx.compose.ui.graphics.Color(0xFFE5C07B),
        linkText = androidx.compose.ui.graphics.Color(0xFF6FB8FF),
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
        dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    )
    Markdown(
        content = text,
        colors = colors,
        typography = typography,
        padding = padding
    )
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
    sessionResetMin: Int?,
    weekResetMin: Int?,
    latencyMs: Long?,
    todoPending: Int,
    activeSkill: String?,
    activeSubagents: Int,
    activity: SessionActivity?,
    showThinking: Boolean,
    showSystem: Boolean,
    onToggleThinking: () -> Unit,
    onToggleSystem: () -> Unit,
    fontScale: Float,
    onFontScaleDelta: (Float) -> Unit
) {
    var filterMenu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer
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
                StatusChip("$entryCount entries")
                StatusChip("ctx ${contextPercent?.let { "$it%" } ?: "—"}")
                StatusChip(buildUsageLabel("5h", sessionUsagePercent, sessionResetMin))
                StatusChip(buildUsageLabel("wk", weekUsagePercent, weekResetMin))
                if (todoPending > 0) StatusChip("↘ $todoPending")
                if (activeSubagents > 0) StatusChip("⚡ $activeSubagents")
                if (!activeSkill.isNullOrBlank()) StatusChip("skill: $activeSkill")
                if (latencyMs != null) StatusChip("${latencyMs}ms")
            }
            Box {
                IconButton(
                    onClick = { filterMenu = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Text(
                        "▾",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
    val (color, label) = when (activity) {
        SessionActivity.WORKING -> Color(0xFFFFC107) to "working"
        SessionActivity.WAITING_FOR_INPUT -> Color(0xFF4CAF50) to "ready"
        SessionActivity.APPROVAL_NEEDED -> Color(0xFFFF5722) to "approval"
        SessionActivity.DISCONNECTED -> Color(0xFFB0BEC5) to "offline"
        SessionActivity.IDLE -> Color(0xFF78909C) to "idle"
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
        Surface(
            modifier = Modifier.size(8.dp).alpha(alpha),
            color = color,
            shape = CircleShape
        ) {}
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WorkingSkeletonCard() {
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.size(8.dp).alpha(alpha),
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            ) {}
            Text(
                "Claude is working…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                fontFamily = FontFamily.Monospace
            )
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
/**
 * Format a usage chip label combining percentage and time-to-reset.
 * Either piece is optional; '—' is rendered when nothing is known so the
 * chip's intent stays visible.
 */
private fun buildUsageLabel(prefix: String, pct: Int?, resetMin: Int?): String {
    val pctPart = pct?.let { "$it%" } ?: "—"
    val resetPart = resetMin?.let { formatReset(it) }
    return if (resetPart != null) "$prefix $pctPart · $resetPart" else "$prefix $pctPart"
}

private fun formatReset(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h${m}m" else "${m}m"
}

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
