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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

/** Small per-message button that copies [text] to the clipboard, with a brief
 *  check-mark confirmation. */
@Composable
private fun CopyButton(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = CRTheme.colors.textDim,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
    IconButton(
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = "Copy message",
            tint = if (copied) CRTheme.colors.ready else tint,
            modifier = Modifier.size(15.dp),
        )
    }
}

/** Pulsing dot shown while a tool call has no result yet. */
@Composable
private fun PendingDot(modifier: Modifier = Modifier) {
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
        style = CRType.monoTiny,
        color = CRTheme.colors.working,
        modifier = modifier.alpha(pulseAlpha)
    )
}

@Composable
internal fun UserPromptCard(entry: TranscriptEntry.UserPrompt) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                // Bordered on purpose: prompts and final answers are the TURN
                // ANCHORS the eye scans for — they get frames, the working
                // middle stays flat (user feedback after trying fill-only).
                .background(c.tintAccent, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp))
                .border(1.dp, c.accent.copy(alpha = 0.35f), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp))
                .padding(horizontal = m.cardPadH, vertical = m.cardPadV)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Pill(text = "USER", background = c.tintAccent, foreground = c.accent)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (entry.timestamp != null) {
                        Text(
                            formatTimestamp(entry.timestamp),
                            style = CRType.monoTiny,
                            color = c.textDim
                        )
                    }
                    CopyButton(entry.text, modifier = Modifier.size(26.dp), tint = c.accent)
                }
            }
            Spacer(Modifier.height(4.dp))
            // Long pasted prompts (logs, stack traces) collapse to the first
            // 10 lines so they don't dominate the conversation.
            val lines = remember(entry.text) { entry.text.lines() }
            val collapsible = lines.size > 12
            var promptExpanded by rememberSaveable(entry.id) { mutableStateOf(false) }
            val shown = if (collapsible && !promptExpanded)
                lines.take(10).joinToString("\n")
            else entry.text
            RichBody(shown, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            if (collapsible) {
                Text(
                    if (promptExpanded) "▲ show less" else "▼ show all (${lines.size} lines)",
                    style = CRType.monoTiny,
                    color = c.accent,
                    modifier = Modifier
                        .align(Alignment.End)
                        .clickable { promptExpanded = !promptExpanded }
                        .padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
internal fun SlashCommandRow(entry: TranscriptEntry.SlashCommand) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .background(c.surface2, RoundedCornerShape(6.dp))
                .padding(horizontal = m.cardPadH, vertical = 4.dp)
        ) {
            Text(
                "/${entry.name}${if (entry.args.isNotBlank()) " ${entry.args}" else ""}",
                style = CRType.mono,
                color = c.accent
            )
        }
    }
}

/**
 * Assistant text. Intermediate messages render flat (no chrome) — they're
 * part of the working middle. The turn's FINAL answer ([framed] = true) gets
 * a card frame: together with the user bubble it forms the pair of turn
 * anchors the eye scans for. Metadata (model · time, copy, speaker) sits in
 * a quiet hairline row under the body either way.
 */
@Composable
internal fun AssistantTextCard(
    entry: TranscriptEntry.AssistantText,
    framed: Boolean = false,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val frameModifier = if (framed) {
        Modifier
            .background(c.surface2, RoundedCornerShape(m.cardRadius))
            .border(1.dp, c.border, RoundedCornerShape(m.cardRadius))
            .padding(horizontal = m.cardPadH, vertical = m.cardPadV)
    } else {
        Modifier.padding(vertical = 2.dp)
    }
    Column(modifier = Modifier.fillMaxWidth().then(frameModifier)) {
        RichBody(entry.text)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        ) {
            val meta = listOfNotNull(
                entry.model,
                entry.timestamp?.let { formatTimestamp(it) }
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = CRType.monoTiny, color = c.textDim.copy(alpha = 0.7f))
            }
            CopyButton(entry.text, modifier = Modifier.size(22.dp), tint = c.textDim.copy(alpha = 0.7f))
            SpeakerButton(
                text = entry.text,
                modifier = Modifier.size(24.dp),
                tint = c.textDim.copy(alpha = 0.7f),
            )
        }
    }
}

/** Flat collapsed thinking row — no card chrome, it's secondary detail. */
@Composable
internal fun ThinkingCard(entry: TranscriptEntry.AssistantThinking) {
    val c = CRTheme.colors
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (expanded) "▼ thinking" else "▶ thinking",
                style = CRType.monoTiny,
                color = c.textDim
            )
            if (expanded) {
                CopyButton(entry.text, modifier = Modifier.size(24.dp))
            }
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                entry.text,
                style = CRType.mono,
                color = c.textDim,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

/**
 * Compact one-line tool row: category glyph · name · summary · status.
 * Borderless — tool calls are secondary detail, only an error result gets
 * card emphasis. Expanded reveals input + result indented under the row.
 */
@Composable
internal fun ToolRow(
    entry: TranscriptEntry.ToolCall,
    result: TranscriptEntry.ToolResult?
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val errorTint = result?.isError == true
    val categoryTint = toolCategoryTint(entry.name, c)

    val rowContent: @Composable () -> Unit = {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "●",
                    style = CRType.monoTiny,
                    color = if (errorTint) c.disconnected else categoryTint,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    entry.name,
                    style = CRType.mono,
                    color = if (errorTint) c.disconnected else c.text,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    entry.inputSummary,
                    style = CRType.mono,
                    maxLines = 1,
                    color = c.textDim,
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())
                )
                if (result == null) {
                    PendingDot(modifier = Modifier.padding(start = 4.dp))
                } else if (errorTint) {
                    Text(
                        "!",
                        style = CRType.monoTiny,
                        color = c.disconnected,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            if (expanded) {
                ToolExpandedDetail(entry, result)
            }
        }
    }

    if (errorTint) {
        CRCard(
            background = c.disconnected.copy(alpha = 0.08f),
            borderColor = c.disconnected.copy(alpha = 0.4f),
            padding = PaddingValues(horizontal = m.cardPadH, vertical = 2.dp)
        ) { rowContent() }
    } else {
        Box(Modifier.padding(horizontal = 4.dp)) { rowContent() }
    }
}

@Composable
private fun ToolExpandedDetail(
    entry: TranscriptEntry.ToolCall,
    result: TranscriptEntry.ToolResult?
) {
    val c = CRTheme.colors
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
                style = CRType.monoTiny,
                color = if (errorTint) c.disconnected else c.textDim,
                modifier = Modifier.padding(bottom = 2.dp)
            )
            IndentedMono(result.text, error = errorTint)
        }
    }
}

/**
 * A run of consecutive tool calls collapsed to ONE summary row:
 * `⚙ 14 tools · 6×Read 4×Bash 3×Edit ✓`. Errors surface as a red count,
 * a still-running call as a pulsing dot. Tap toggles the full row list.
 * Collapsed by default — the group is working noise, not content.
 */
@Composable
internal fun ToolGroupBlock(
    calls: List<TranscriptEntry.ToolCall>,
    results: Map<String, TranscriptEntry.ToolResult>
) {
    val c = CRTheme.colors
    // Key on the first call id: stable while the group grows during streaming.
    var expanded by rememberSaveable(calls.first().id) { mutableStateOf(false) }
    val pending = calls.any { results[it.toolUseId] == null }
    val errorCount = calls.count { results[it.toolUseId]?.isError == true }
    val breakdown = remember(calls) {
        val counts = calls.groupingBy { it.name }.eachCount()
            .entries.sortedByDescending { it.value }
        val shown = counts.take(4).joinToString(" ") { "${it.value}×${it.key}" }
        if (counts.size > 4) "$shown …" else shown
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (expanded) "▼" else "▶",
                style = CRType.monoTiny,
                color = c.textDim,
                modifier = Modifier.padding(end = 6.dp)
            )
            Text(
                "⚙ ${calls.size} tools",
                style = CRType.mono,
                color = c.text,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                breakdown,
                style = CRType.monoTiny,
                maxLines = 1,
                color = c.textDim,
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())
            )
            when {
                pending -> PendingDot(modifier = Modifier.padding(start = 4.dp))
                errorCount > 0 -> Text(
                    "$errorCount!",
                    style = CRType.monoTiny,
                    color = c.disconnected,
                    modifier = Modifier.padding(start = 4.dp)
                )
                else -> Text(
                    "✓",
                    style = CRType.monoTiny,
                    color = c.ready,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(start = 12.dp)
            ) {
                for (call in calls) {
                    ToolRow(call, results[call.toolUseId])
                }
            }
        }
    }
}

/**
 * Subagent launch (Task/Agent tool) as a purple LEFT ACCENT BAR block — no
 * box chrome; borders are reserved for needs-attention states. Description is
 * the headline, with a running pulse until the result lands and a short tail
 * of the agent's final report once it does. Tap for full input/result detail.
 */
@Composable
internal fun AgentCard(
    entry: TranscriptEntry.ToolCall,
    result: TranscriptEntry.ToolResult?,
) {
    val c = CRTheme.colors
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val running = result == null
    val isError = result?.isError == true
    val accent = if (isError) c.disconnected else c.modePlan
    // The bar is drawn (drawBehind), not laid out: an IntrinsicSize.Min row
    // would query intrinsics of horizontalScroll descendants in the expanded
    // detail — drawing sidesteps intrinsic measurement entirely and always
    // spans the full block height.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp)
            .leftAccentBar(accent, width = 3.dp)
            .padding(start = 11.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⚡",
                    style = CRType.mono,
                    color = accent,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Text(
                    entry.inputSummary.ifBlank { entry.name },
                    style = CRType.cardTitle,
                    color = c.text,
                    maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
                when {
                    running -> PendingDot(modifier = Modifier.padding(start = 4.dp))
                    isError -> Text("!", style = CRType.monoTiny, color = c.disconnected, modifier = Modifier.padding(start = 4.dp))
                    else -> Text("✓", style = CRType.monoTiny, color = c.ready, modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (running) {
                Text("running…", style = CRType.monoTiny, color = c.textDim, modifier = Modifier.padding(top = 2.dp))
            } else if (!expanded) {
                val tail = remember(result.text) { agentResultTail(result.text) }
                if (tail.isNotBlank()) {
                    Text(
                        tail,
                        style = CRType.monoTiny,
                        color = c.textDim,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (expanded) {
                ToolExpandedDetail(entry, result)
            }
        }
    }
}

/**
 * Last meaningful lines of a subagent result for the collapsed preview.
 * Background-agent spawn results lead with plumbing (output_file path,
 * "Do NOT read the transcript…", agent id) — skip those, the user wants
 * the agent's actual conclusion.
 */
private fun agentResultTail(text: String): String =
    text.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filterNot {
            // Best-effort, case-insensitive: if the spawn-result wording ever
            // drifts, the worst case is boilerplate reappearing in a preview.
            it.startsWith("output_file:", ignoreCase = true) ||
                it.startsWith("agentId:", ignoreCase = true) ||
                it.startsWith("Do not ", ignoreCase = true) ||
                it.startsWith("The agent is working", ignoreCase = true)
        }
        .takeLast(2)
        .joinToString("\n")

/**
 * TodoWrite rendered as an actual checklist (☐ / ◐ / ☑) instead of a generic
 * tool row — the plan is content, not plumbing. Falls back to the generic row
 * when the input doesn't parse.
 */
@Composable
internal fun TodoChecklistCard(
    entry: TranscriptEntry.ToolCall,
    result: TranscriptEntry.ToolResult?,
) {
    val c = CRTheme.colors
    val todos = remember(entry.fullInput) { parseTodoInput(entry.fullInput) }
    if (todos.isEmpty()) {
        ToolRow(entry, result)
        return
    }
    val done = todos.count { it.status == "completed" }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 2.dp)) {
        Text(
            "todos · $done/${todos.size} done",
            style = CRType.monoTiny,
            color = c.textDim
        )
        Spacer(Modifier.height(2.dp))
        todos.forEach { t ->
            val (glyph, glyphColor) = when (t.status) {
                "completed" -> "☑" to c.ready
                "in_progress" -> "◐" to c.working
                else -> "☐" to c.textDim
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(glyph, style = CRType.mono, color = glyphColor, modifier = Modifier.padding(end = 6.dp))
                Text(
                    t.content,
                    style = if (t.status == "completed")
                        CRType.mono.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                    else CRType.mono,
                    color = when (t.status) {
                        "completed" -> c.textDim
                        "in_progress" -> c.text
                        else -> c.textDim
                    }
                )
            }
        }
    }
}

/**
 * Prominent card for Claude's AskUserQuestion tool call: shows each question's
 * header/question with a numbered list of options. When the paired tool_result
 * is present, shows the chosen answer(s) (the result content as-is — Claude
 * writes the selected label(s) there). When still unanswered, hints the user
 * to answer in the terminal where the real TUI widget lives.
 */
@Composable
internal fun AskUserQuestionCard(
    entry: TranscriptEntry.ToolCall,
    result: TranscriptEntry.ToolResult?,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val questions = remember(entry.fullInput) { parseAskUserQuestions(entry.fullInput) }
    val answered = result != null
    val accent = if (answered) c.ready else c.approval
    CRCard(
        background = c.surface,
        borderColor = accent.copy(alpha = 0.6f),
        padding = PaddingValues(horizontal = m.cardPadH, vertical = m.cardPadV),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Pill(
                    text = if (answered) "ANSWERED" else "CLAUDE ASKS",
                    background = accent.copy(alpha = 0.18f),
                    foreground = accent,
                )
                if (entry.timestamp != null) {
                    Text(formatTimestamp(entry.timestamp), style = CRType.monoTiny, color = c.textDim)
                }
            }
            if (questions.isEmpty()) {
                // Fallback: couldn't parse the expected shape — show the summary.
                Text(entry.inputSummary, style = CRType.bodyDim, color = c.text)
            } else {
                questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (q.header.isNotBlank()) {
                            Text(q.header, style = CRType.cardTitle, color = accent)
                        }
                        if (q.question.isNotBlank()) {
                            Text(q.question, style = CRType.bodyDim, color = c.text)
                        }
                        q.options.forEachIndexed { idx, opt ->
                            val line = buildString {
                                append("${idx + 1}. ")
                                append(opt.label)
                                if (opt.description.isNotBlank()) append(" — ${opt.description}")
                            }
                            Text(line, style = CRType.mono, color = c.textDim)
                        }
                        // FIX E: surface multiSelect hint when true.
                        if (q.multiSelect) {
                            Text("(select multiple)", style = CRType.monoTiny, color = c.textDim)
                        }
                    }
                }
            }
            if (answered) {
                // FIX E: extract just the answer value(s) from the verbose tool_result
                // text. Claude writes e.g. `User has answered your questions: "q"="a". …`
                // Pull out all `="<value>"` portions; fall back to the trimmed raw text.
                val rawAnswer = result!!.text.trim()
                val answer = extractAskAnswers(rawAnswer).ifBlank { rawAnswer }
                if (answer.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text("Answer", style = CRType.monoTiny, color = c.textDim)
                    Text(answer, style = CRType.mono, color = c.ready)
                }
            } else {
                Text(
                    "Answer in the terminal view.",
                    style = CRType.monoTiny,
                    color = c.textDim,
                )
            }
        }
    }
}

@Composable
internal fun ToolResultCard(entry: TranscriptEntry.ToolResult) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val lines = entry.text.lines()
    // Tail, not head: the interesting part of command output (exit status,
    // error message, summary line) is at the END.
    val preview = lines.takeLast(3).joinToString("\n")
    val hasMore = lines.size > 3

    val body: @Composable () -> Unit = {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = hasMore) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (entry.isError) {
                    Pill(
                        text = "ERROR",
                        background = c.disconnected.copy(alpha = 0.18f),
                        foreground = c.disconnected
                    )
                } else {
                    Text("result", style = CRType.monoTiny, color = c.textDim)
                }
                Spacer(Modifier.width(6.dp))
                if (hasMore) {
                    Text(
                        if (expanded) "▼" else "▶",
                        style = CRType.monoTiny,
                        color = c.textDim
                    )
                }
                Spacer(Modifier.weight(1f))
                CopyButton(entry.text, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.height(4.dp))
            if (!expanded && hasMore) {
                Text(
                    "(${lines.size - 3} earlier lines — tap to expand)",
                    style = CRType.monoTiny,
                    color = c.textDim
                )
            }
            MonospaceBlock(if (expanded) entry.text else preview)
        }
    }

    if (entry.isError) {
        // Errors keep the bordered card — border = needs attention.
        CRCard(
            background = c.disconnected.copy(alpha = 0.12f),
            borderColor = c.disconnected.copy(alpha = 0.4f),
            padding = PaddingValues(horizontal = m.cardPadH, vertical = 6.dp),
            modifier = Modifier.padding(start = 16.dp)
        ) { body() }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp)
                .leftAccentBar(c.border, width = 2.dp)
                .padding(start = 10.dp)
        ) { body() }
    }
}

@Composable
internal fun SystemNoteRow(entry: TranscriptEntry.SystemNote) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .border(1.dp, c.border.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(horizontal = m.cardPadH, vertical = 4.dp)
    ) {
        Column {
            Text(
                "system · ${entry.subtype}",
                style = CRType.monoTiny,
                color = c.textDim
            )
            if (entry.text.isNotBlank()) {
                Text(
                    entry.text.take(500),
                    style = CRType.mono,
                    color = c.textDim
                )
            }
        }
    }
}

/**
 * Render a chunk of markdown using multiplatform-markdown-renderer.
 * Typography and colors use CRTheme tokens.
 */
@Composable
private fun RichBody(
    text: String,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    val c = CRTheme.colors
    val mono = FontFamily.Monospace
    val body = CRType.bodyDim.copy(
        lineHeight = CRType.bodyDim.fontSize * 1.15f,
        textAlign = textAlign ?: CRType.bodyDim.textAlign,
    )
    val codeStyle = CRType.monoTiny
    val typography = com.mikepenz.markdown.m3.markdownTypography(
        h1 = CRType.sectionH.copy(fontWeight = FontWeight.Bold, fontSize = CRType.xl),
        h2 = CRType.sectionH.copy(fontWeight = FontWeight.Bold, fontSize = CRType.lg),
        h3 = CRType.sectionH.copy(fontWeight = FontWeight.Bold),
        h4 = CRType.sectionH.copy(fontWeight = FontWeight.Bold),
        h5 = CRType.sectionH.copy(fontWeight = FontWeight.Bold),
        h6 = CRType.sectionH.copy(fontWeight = FontWeight.Bold),
        text = body,
        paragraph = body,
        quote = body.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
        code = codeStyle,
        inlineCode = codeStyle,
        list = body,
        link = body.copy(
            color = c.accent,
            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
        )
    )
    val padding = com.mikepenz.markdown.model.markdownPadding(
        block = 2.dp,
        list = 2.dp,
        indentList = 12.dp
    )
    val colors = com.mikepenz.markdown.m3.markdownColor(
        text = c.text,
        codeText = c.working,
        inlineCodeText = c.working,
        linkText = c.accent,
        codeBackground = c.surface2,
        inlineCodeBackground = c.surface2,
        dividerColor = c.border.copy(alpha = 0.4f)
    )
    Markdown(
        content = text,
        colors = colors,
        typography = typography,
        padding = padding,
        components = com.mikepenz.markdown.compose.components.markdownComponents(
            table = { model -> CRMarkdownTable(model) }
        )
    )
}

/**
 * Custom renderer for GitHub-style pipe tables: wraps cell text (no ellipsis) and
 * is horizontally scrollable with fixed, aligned per-column widths so full text and
 * all columns stay reachable on a narrow phone screen.
 */
@Composable
private fun CRMarkdownTable(model: com.mikepenz.markdown.compose.components.MarkdownComponentModel) {
    val c = CRTheme.colors
    val raw = model.content.substring(model.node.startOffset, model.node.endOffset)

    fun cleanCell(s: String): String {
        var t = s.trim()
        // unwrap `code`
        t = t.replace(Regex("`([^`]+)`"), "$1")
        // strip **bold**
        t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        // strip *italic* (single star pairs)
        t = t.replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "$1")
        return t.trim()
    }

    fun splitRow(line: String): List<String> {
        val parts = line.split("|").toMutableList()
        if (parts.isNotEmpty() && parts.first().isBlank()) parts.removeAt(0)
        if (parts.isNotEmpty() && parts.last().isBlank()) parts.removeAt(parts.size - 1)
        return parts.map { it.trim() }
    }

    val sepRegex = Regex("^:?-{2,}:?$")
    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
    val allRows = lines.map { splitRow(it) }
    val sepIndex = allRows.indexOfFirst { row -> row.isNotEmpty() && row.all { sepRegex.matches(it) } }

    val headerCells: List<String>
    val bodyRows: List<List<String>>
    if (sepIndex > 0) {
        headerCells = allRows[sepIndex - 1]
        bodyRows = allRows.drop(sepIndex + 1)
    } else if (allRows.isNotEmpty()) {
        headerCells = allRows[0]
        bodyRows = allRows.drop(1)
    } else {
        headerCells = emptyList()
        bodyRows = emptyList()
    }

    // Defensive fallback: nothing parseable -> plain monospace text.
    if (headerCells.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface2, RoundedCornerShape(4.dp))
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                raw,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                style = CRType.mono,
                color = c.text
            )
        }
        return
    }

    val cleanedHeader = headerCells.map { cleanCell(it) }
    val cleanedBody = bodyRows.map { row -> row.map { cleanCell(it) } }
    val numCols = (listOf(cleanedHeader) + cleanedBody).maxOf { it.size }

    var expanded by remember { mutableStateOf(false) }

    // Inline: tap to expand, drag to horizontally scroll. Compose disambiguates tap vs drag.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            TableGrid(
                header = cleanedHeader,
                body = cleanedBody,
                numCols = numCols,
                maxColWidth = 240.dp,
            )
        }
        // Subtle hint that the table opens fullscreen when tapped.
        Text(
            "⤢",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
            style = CRType.bodyDim,
            color = c.textDim
        )
    }

    if (expanded) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = c.bg) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Tabulka", style = CRType.cardTitle, color = c.text)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "✕",
                            modifier = Modifier
                                .clickable { expanded = false }
                                .padding(8.dp),
                            style = CRType.cardTitle,
                            color = c.text
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        TableGrid(
                            header = cleanedHeader,
                            body = cleanedBody,
                            numCols = numCols,
                            maxColWidth = 520.dp,
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableGrid(
    header: List<String>,
    body: List<List<String>>,
    numCols: Int,
    maxColWidth: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val c = CRTheme.colors

    fun cellAt(row: List<String>, i: Int): String = row.getOrElse(i) { "" }

    val colWidths: List<androidx.compose.ui.unit.Dp> = (0 until numCols).map { i ->
        val maxChars = (listOf(header) + body).maxOf { cellAt(it, i).length }
        ((maxChars.coerceIn(3, 30) * 7.5f).dp).coerceIn(48.dp, maxColWidth)
    }
    val totalWidth = colWidths.fold(0.dp) { acc, w -> acc + w }
    val cellStyle = CRType.bodyDim
    val dividerColor = c.border.copy(alpha = 0.4f)

    @Composable
    fun TableRow(cells: List<String>, bold: Boolean, background: Color) {
        Row(modifier = Modifier.width(totalWidth).background(background)) {
            for (i in 0 until numCols) {
                Text(
                    cellAt(cells, i),
                    modifier = Modifier
                        .width(colWidths[i])
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = cellStyle,
                    color = c.text,
                    fontWeight = if (bold) FontWeight.Bold else null,
                    softWrap = true
                )
            }
        }
    }

    Box(modifier = modifier) {
        Column(modifier = Modifier.width(totalWidth)) {
            TableRow(header, bold = true, background = c.surface2)
            Box(Modifier.width(totalWidth).height(1.dp).background(dividerColor))
            for (row in body) {
                TableRow(row, bold = false, background = Color.Transparent)
                Box(Modifier.width(totalWidth).height(1.dp).background(dividerColor))
            }
        }
    }
}

@Composable
private fun MonospaceBlock(text: String) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface2, RoundedCornerShape(4.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            style = CRType.mono,
            color = c.text
        )
    }
}

@Composable
internal fun IndentedMono(text: String, error: Boolean = false) {
    val c = CRTheme.colors
    val border = if (error) c.disconnected.copy(alpha = 0.6f) else c.border.copy(alpha = 0.5f)
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
                style = CRType.mono,
                color = if (error) c.disconnected else c.textDim
            )
        }
    }
}

/**
 * Rounded vertical accent bar along the start edge, drawn rather than laid
 * out — drawBehind needs no intrinsic measurement, so it composes safely with
 * horizontalScroll descendants and always spans the block's full height.
 */
private fun Modifier.leftAccentBar(color: Color, width: androidx.compose.ui.unit.Dp): Modifier =
    drawBehind {
        drawRoundRect(
            color = color,
            size = androidx.compose.ui.geometry.Size(width.toPx(), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(width.toPx() / 2)
        )
    }

/**
 * Map tool name to a category tint color from CRTheme.
 * Read tools → tintGreen, Write/Edit → tintOrange, Bash/exec → tintPurple,
 * others → tintAccent.
 */
private fun toolCategoryTint(name: String, c: com.clauderemote.ui.theme.CRColorScheme): Color =
    when (name.lowercase()) {
        "read", "ls", "glob", "grep", "find", "search" -> c.tintGreen
        "write", "create", "multiedit" -> c.tintOrange
        "edit", "str_replace_editor", "str_replace_based_edit_tool" -> c.tintOrange
        "bash", "execute", "run", "shell", "cmd" -> c.tintPurple
        "todowrite", "todoread" -> c.tintYellow
        else -> c.tintAccent
    }.let {
        // Return the base signal color (not the 15% tint) for text/pill foreground.
        // The caller wraps it in .copy(alpha=0.18f) for the bg tint as needed.
        // But here we return the full-alpha signal color for legibility.
        when (name.lowercase()) {
            "read", "ls", "glob", "grep", "find", "search" -> c.ready
            "write", "create", "multiedit" -> c.approval
            "edit", "str_replace_editor", "str_replace_based_edit_tool" -> c.approval
            "bash", "execute", "run", "shell", "cmd" -> c.modePlan
            "todowrite", "todoread" -> c.working
            else -> c.accent
        }
    }

