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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
    activity: SessionActivity? = null,
    claudeSessionId: String? = null
) {
    // Key list+scroll state on the session uuid so switching tabs resets
    // scroll position and stickiness — otherwise the new session inherits
    // the previous one's "user scrolled up" flag and never auto-scrolls
    // to the latest entry on open.
    val sessionKey = claudeSessionId ?: ""
    val listState = remember(sessionKey) { androidx.compose.foundation.lazy.LazyListState(0, 0) }
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
    val todoPending = remember(entries) { countOpenTodos(entries) }

    // Decay a stuck WORKING state. The prompt detector that drives `activity`
    // runs off the terminal's rendered screen — when the user is in the
    // transcript view the terminal is disposed, the detector can't read
    // anything, and a WORKING reading from before the toggle freezes here.
    // If no new entry has arrived for ~6 s, treat the state as ready so the
    // pulsing dot + skeleton card stop misleading the user.
    var lastChangeAt by remember { mutableStateOf(System.currentTimeMillis()) }
    // Track content freshness on more than list size: a long single streaming
    // assistant/thinking message grows in place without changing entries.size,
    // and keying decay on size alone made Claude look "stuck → waiting" while it
    // was actively streaming. Fold in the last entry's id + text length.
    val contentTick = remember(entries) {
        val last = entries.lastOrNull()
        val len = when (last) {
            is TranscriptEntry.AssistantText -> last.text.length
            is TranscriptEntry.AssistantThinking -> last.text.length
            is TranscriptEntry.ToolResult -> last.text.length
            else -> 0
        }
        Triple(entries.size, last?.id, len)
    }
    LaunchedEffect(contentTick) { lastChangeAt = System.currentTimeMillis() }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activity == SessionActivity.WORKING) {
        while (activity == SessionActivity.WORKING) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val effectiveActivity = when {
        activity != SessionActivity.WORKING -> activity
        now - lastChangeAt > 6_000 -> SessionActivity.WAITING_FOR_INPUT
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (claudeSessionId.isNullOrBlank())
                            "Waiting for Claude session id…"
                        else
                            "Waiting for transcript…",
                        style = CRType.bodyDim,
                        color = CRTheme.colors.textDim
                    )
                    if (!claudeSessionId.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "uuid: ${claudeSessionId.take(8)}",
                            style = CRType.bodyDim,
                            color = CRTheme.colors.textDim
                        )
                    }
                }
            }
            return@Column
        }

        val baseDensity = LocalDensity.current
        val cardGap = CRTheme.metrics.cardGap
        // Pre-group consecutive ToolCalls so a run of Read/Edit/Bash collapses
        // to one tight stack instead of N bordered cards.
        val rendered = remember(filtered) { groupConsecutiveTools(filtered) }
        val itemsCount = rendered.size + if (skeletonShowing) 1 else 0

        // Stickiness is decided by user scroll gestures, not by snapshotting
        // layoutInfo at the moment a content effect fires. Previous logic
        // sampled `lastVisible` right when a new item arrived — but at that
        // tick the new item hasn't been composed yet, so `lastVisible` could
        // legitimately be `lastIdx - 1` and still pass the threshold OR
        // could be stale and fail it depending on timing. Result: random
        // misses, exactly matching the user-reported "sometimes doesn't
        // follow" symptom. Driving stickiness only off user scroll end
        // makes it deterministic and immune to add-then-measure races.
        // ─────────────────────────────────────────────────────────────
        // Auto-scroll / stickiness — robust against composition races.
        //
        // Two effects:
        //  • A snapshotFlow watcher that decides whether the user wants
        //    to stay at the bottom, ONLY reading layoutInfo when we are
        //    NOT inside a programmatic scroll window.
        //  • A content-change effect that, whenever itemsCount grows,
        //    raises the programmatic guard, scrolls to the very end,
        //    waits one settle frame, then drops the guard.
        //
        // Why a guard *counter* rather than a boolean: if a new entry
        // arrives mid-scroll, this content-change effect is cancelled
        // and re-launched. With a boolean guard the cancelled coroutine
        // would lower the flag in its finally just as the new effect
        // raises it — leaving a one-tick window in which the watcher
        // reads the new (not-yet-scrolled-to) totalItemsCount, sees
        // lastVisible < lastIdx, and falsely flips stickToBottom off.
        // The counter increments on entry / decrements on exit; the
        // guard is "down" only when *every* outstanding scroll has
        // finished, so there is no false-positive window.
        var stickToBottom by remember(sessionKey) { mutableStateOf(true) }
        var userHasScrolled by remember(sessionKey) { mutableStateOf(false) }
        var programmaticDepth by remember(sessionKey) { mutableStateOf(0) }
        val programmaticGuard = programmaticDepth > 0

        LaunchedEffect(listState, sessionKey) {
            snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
                if (inProgress) {
                    // Any scroll motion — including our own — marks that
                    // the list state has moved at least once. We use this
                    // only to gate the "pre-layout" guard below; the
                    // programmatic guard handles distinguishing user
                    // vs. machine intent.
                    userHasScrolled = true
                    return@collect
                }
                if (!userHasScrolled) return@collect
                if (programmaticGuard) return@collect
                val info = listState.layoutInfo
                val lastIdx = info.totalItemsCount - 1
                if (lastIdx < 0) return@collect
                if (info.visibleItemsInfo.isEmpty()) return@collect
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                // lastIdx - 1 buffer: when the tail item is taller than
                // viewport-bottom-content-padding, its last pixel may sit
                // just below the visible region even at true end-of-list,
                // so lastVisible reads as lastIdx-1. canScrollForward is
                // the authoritative check for "scrolled to the actual
                // end", so OR it in.
                stickToBottom = lastVisible >= lastIdx - 1 || !listState.canScrollForward
            }
        }

        LaunchedEffect(itemsCount, sessionKey) {
            if (itemsCount <= 0) return@LaunchedEffect
            if (!stickToBottom) return@LaunchedEffect
            val lastIdx = itemsCount - 1
            programmaticDepth++
            try {
                // Two frame yields so the new item is composed AND
                // measured before scrollToItem anchors — without these
                // the anchor sometimes lands on the prior tail item
                // and the new one sits below the viewport.
                kotlinx.coroutines.yield()
                kotlinx.coroutines.yield()
                listState.scrollToItem(lastIdx)
                kotlinx.coroutines.yield()
                // scrollBy with a saturating delta drops us at the true
                // content end (LazyColumn clamps forward scroll at the
                // bottom), so the tail item bottom-aligns regardless of
                // its height. Without this, scrollToItem(lastIdx) alone
                // anchors the item at the TOP of the viewport.
                listState.scrollBy(Float.MAX_VALUE)
                // Hold the guard one settle frame longer so the watcher
                // doesn't read a still-stale layoutInfo on the very next
                // snapshot tick.
                kotlinx.coroutines.delay(120)
            } finally {
                programmaticDepth--
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
                    verticalArrangement = Arrangement.spacedBy(cardGap),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(
                        items = rendered,
                        key = { item ->
                            // Namespace per render type so a Single entry id can
                            // never collide with a ToolGroup key — a duplicate
                            // LazyColumn key is a hard crash, not a glitch.
                            when (item) {
                                is RenderItem.Single -> "s:" + item.entry.id
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
    val c = CRTheme.colors
    val m = CRTheme.metrics
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
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
                if (entry.timestamp != null) {
                    Text(
                        formatTimestamp(entry.timestamp),
                        style = CRType.monoTiny,
                        color = c.textDim
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            RichBody(entry.text, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun SlashCommandRow(entry: TranscriptEntry.SlashCommand) {
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
                .border(1.dp, c.border, RoundedCornerShape(6.dp))
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

@Composable
private fun AssistantTextCard(entry: TranscriptEntry.AssistantText) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface2, RoundedCornerShape(m.cardRadius))
            .border(1.dp, c.border, RoundedCornerShape(m.cardRadius))
            .padding(horizontal = m.cardPadH, vertical = m.cardPadV)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Pill(text = "ASSISTANT", background = c.tintAccent, foreground = c.accent)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (entry.model != null) {
                    Text(entry.model, style = CRType.monoTiny, color = c.textDim)
                }
                if (entry.timestamp != null) {
                    Text(formatTimestamp(entry.timestamp), style = CRType.monoTiny, color = c.textDim)
                }
                SpeakerButton(
                    text = entry.text,
                    modifier = Modifier.size(28.dp),
                    tint = c.textDim,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        RichBody(entry.text)
    }
}

@Composable
private fun ThinkingCard(entry: TranscriptEntry.AssistantThinking) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(6.dp))
            .border(1.dp, c.border.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = m.cardPadH, vertical = 6.dp)) {
            Text(
                if (expanded) "▼ thinking" else "▶ thinking",
                style = CRType.monoTiny,
                color = c.textDim
            )
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    entry.text,
                    style = CRType.mono,
                    color = c.textDim
                )
            }
        }
    }
}

/**
 * Compact one-line tool row: glyph · name · summary · status indicator.
 * Expanded reveals input + result indented under the row, wrapped in CRCard.
 */
@Composable
private fun ToolRow(
    entry: TranscriptEntry.ToolCall,
    result: TranscriptEntry.ToolResult?
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    var expanded by remember { mutableStateOf(false) }
    val errorTint = result?.isError == true
    val categoryTint = toolCategoryTint(entry.name, c)
    val accent = when {
        errorTint -> c.disconnected
        result == null -> c.working
        else -> categoryTint
    }

    CRCard(
        background = c.surface,
        borderColor = accent.copy(alpha = 0.35f),
        padding = PaddingValues(horizontal = m.cardPadH, vertical = 4.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Pill(
                    text = "TOOL",
                    background = categoryTint.copy(alpha = 0.18f),
                    foreground = categoryTint
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    entry.name,
                    style = CRType.cardTitle,
                    color = accent,
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
                        color = c.working,
                        modifier = Modifier.padding(start = 4.dp).alpha(pulseAlpha)
                    )
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
 * Render an Edit tool_use input as a unified diff: each old_string line
 * prefixed with red '−', each new_string line with green '+'. Falls back
 * to raw JSON if the input doesn't have the expected shape.
 */
@Composable
private fun EditDiffBlock(fullInput: String) {
    val c = CRTheme.colors
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
                style = CRType.monoTiny,
                color = c.textDim
            )
            Spacer(Modifier.height(2.dp))
        }
        DiffPane(removed = oldStr, added = newStr)
    }
}

@Composable
private fun WriteDiffBlock(fullInput: String) {
    val c = CRTheme.colors
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
                style = CRType.monoTiny,
                color = c.textDim
            )
            Spacer(Modifier.height(2.dp))
        }
        DiffPane(removed = "", added = content)
    }
}

@Composable
private fun DiffPane(removed: String, added: String) {
    val c = CRTheme.colors
    // Error (red) bg for removed lines, green tint for added lines
    val errorBg = c.disconnected.copy(alpha = 0.15f)
    val addBg = c.ready.copy(alpha = 0.12f)
    val errorFg = c.disconnected
    val addFg = c.ready
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
    prefixColor: Color,
    background: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            prefix,
            style = CRType.mono,
            color = prefixColor,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text,
            style = CRType.mono,
            color = CRTheme.colors.text
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

@Composable
private fun ToolGroupBlock(
    calls: List<TranscriptEntry.ToolCall>,
    results: Map<String, TranscriptEntry.ToolResult>
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (call in calls) {
            ToolRow(call, results[call.toolUseId])
        }
    }
}

private sealed class RenderItem {
    data class Single(val entry: TranscriptEntry) : RenderItem()
    data class ToolGroup(val calls: List<TranscriptEntry.ToolCall>) : RenderItem()
}

/**
 * Walk the entries and fuse any run of two-or-more consecutive ToolCall
 * entries into a single ToolGroup item.
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
    val c = CRTheme.colors
    val m = CRTheme.metrics
    var expanded by remember { mutableStateOf(false) }
    val lines = entry.text.lines()
    val preview = lines.take(3).joinToString("\n")
    val hasMore = lines.size > 3 || entry.text.length > preview.length
    val bg = if (entry.isError) c.disconnected.copy(alpha = 0.12f) else c.surface2
    val borderCol = if (entry.isError) c.disconnected.copy(alpha = 0.4f) else c.border

    CRCard(
        background = bg,
        borderColor = borderCol,
        padding = PaddingValues(horizontal = m.cardPadH, vertical = 6.dp),
        modifier = Modifier.padding(start = 16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(enabled = hasMore) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Pill(
                    text = if (entry.isError) "ERROR" else "RESULT",
                    background = if (entry.isError) c.disconnected.copy(alpha = 0.18f) else c.tintAccent,
                    foreground = if (entry.isError) c.disconnected else c.accent
                )
                Spacer(Modifier.width(6.dp))
                if (hasMore) {
                    Text(
                        if (expanded) "▼" else "▶",
                        style = CRType.monoTiny,
                        color = c.textDim
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            MonospaceBlock(if (expanded) entry.text else preview)
            if (!expanded && hasMore) {
                Text(
                    "(${lines.size - 3} more lines — tap to expand)",
                    style = CRType.monoTiny,
                    color = c.textDim
                )
            }
        }
    }
}

@Composable
private fun SystemNoteRow(entry: TranscriptEntry.SystemNote) {
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
        padding = padding
    )
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
private fun WorkingSkeletonCard() {
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

@Composable
private fun StatusChip(text: String) {
    Text(
        text,
        style = CRType.mono,
        color = CRTheme.colors.textDim
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

/**
 * Format a usage chip label combining percentage and time-to-reset.
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
    val pending = Regex("\"status\"\\s*:\\s*\"(pending|in_progress)\"")
        .findAll(json).count()
    return pending
}
