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
// Fallback only, for sessions without the Stop hook: how long a WORKING state
// may persist with no new transcript content before we assume Claude is idle.
// Generous so a slow tool (build, test run) doesn't flip the indicator off.
private const val STALE_WORKING_MS = 45_000L

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
    hookActive: Boolean = false,
    claudeSessionId: String? = null,
    streamStatus: String? = null,
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
    var searchOpen by remember(sessionKey) { mutableStateOf(false) }
    var searchQuery by remember(sessionKey) { mutableStateOf("") }
    var searchPos by remember(sessionKey) { mutableStateOf(0) }

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
    // Working/idle from the transcript itself — the only reliable source in
    // chat view (the screen classifier can't read the disposed terminal, and the
    // OMC statusline can scroll out of the parse buffer during heavy streaming).
    // Claude Code writes a `stop_hook_summary` system entry at the end of every
    // turn, AFTER the final assistant text. So: conversation content sitting
    // after the last stop marker ⇒ Claude is working; the marker being last ⇒
    // idle. No timer needed — the marker is the authoritative boundary.
    val transcriptWorking = remember(entries) {
        val lastStopIdx = entries.indexOfLast {
            it is TranscriptEntry.SystemNote && it.subtype == "stop_hook_summary"
        }
        val lastTurnIdx = entries.indexOfLast {
            it is TranscriptEntry.AssistantText || it is TranscriptEntry.AssistantThinking ||
                it is TranscriptEntry.ToolCall || it is TranscriptEntry.ToolResult ||
                it is TranscriptEntry.UserPrompt || it is TranscriptEntry.SlashCommand
        }
        // hasStops null ⇒ no marker yet (fresh session / hook off) → caller falls
        // back to statusline + pending-tool heuristics.
        if (lastStopIdx < 0) null else lastTurnIdx > lastStopIdx
    }
    // Fallback signal when there are no stop markers: a tool call whose result
    // hasn't arrived ⇒ Claude is working (the statusline shows no "thinking"
    // during tool execution).
    val pendingTool = remember(entries, resultsByToolId) {
        val lastUserIdx = entries.indexOfLast {
            it is TranscriptEntry.UserPrompt || it is TranscriptEntry.SlashCommand
        }
        entries.asSequence().drop(lastUserIdx + 1).any {
            it is TranscriptEntry.ToolCall && resultsByToolId[it.toolUseId] == null
        }
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
    // Tick a clock while the statusline/hook reports WORKING so a stale WORKING
    // (hook miss after Claude finished) can decay. A pending tool is handled
    // separately below and does NOT decay.
    LaunchedEffect(activity == SessionActivity.WORKING) {
        while (activity == SessionActivity.WORKING) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    // Whether Claude is working, deciding by the most reliable available signal.
    val working = when {
        // Transcript stop-marker present → authoritative (no timer).
        transcriptWorking != null -> transcriptWorking
        // No markers yet: a pending tool, or a fresh statusline WORKING that
        // hasn't gone stale, means working.
        pendingTool -> true
        else -> activity == SessionActivity.WORKING && now - lastChangeAt <= STALE_WORKING_MS
    }
    val effectiveActivity = when {
        activity == SessionActivity.DISCONNECTED -> SessionActivity.DISCONNECTED
        // A permission / AskUserQuestion selector detected on the LIVE screen is
        // the single most reliable "Claude is blocked waiting for YOU" signal, so
        // it must win over the transcript-derived `working` guess. During a
        // pending AskUserQuestion the tool_use line isn't flushed to the .jsonl
        // until it's answered (see TranscriptParser) — so the pre-question
        // assistant text is the last transcript entry and sits after the last
        // stop marker, making transcriptWorking read true. Without this branch
        // first, "Claude is working…" sticks while Claude is actually awaiting
        // the answer.
        activity == SessionActivity.APPROVAL_NEEDED -> SessionActivity.APPROVAL_NEEDED
        working -> SessionActivity.WORKING
        else -> SessionActivity.WAITING_FOR_INPUT
    }

    val skeletonShowing = effectiveActivity == SessionActivity.WORKING
    val awaitingShowing = effectiveActivity == SessionActivity.APPROVAL_NEEDED

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
            },
            searchOpen = searchOpen,
            onToggleSearch = {
                searchOpen = !searchOpen
                if (!searchOpen) searchQuery = ""
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
                    // Diagnostic: what the tail is actually doing / why no data.
                    if (!streamStatus.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            streamStatus,
                            style = CRType.monoTiny,
                            color = CRTheme.colors.textDim,
                        )
                    }
                }
            }
            return@Column
        }

        val baseDensity = LocalDensity.current
        val cardGap = CRTheme.metrics.cardGap
        // Turn-aware render list: every turn except the live last one collapses
        // its working middle (tools / thinking / intermediate text) behind a
        // "N steps · duration" expander; within expanded content consecutive
        // ToolCalls still fuse into a single group row.
        val turnMeta = remember(entries) { computeTurnMeta(entries) }
        val expandedTurns = remember(sessionKey) { mutableStateMapOf<String, Boolean>() }
        // Read the map in composition (snapshot-tracked) so toggling a turn
        // recomposes; the plain copy doubles as a stable remember key.
        val expandedSnapshot = expandedTurns.toMap()
        val rendered = remember(filtered, turnMeta, expandedSnapshot) {
            buildRenderList(filtered, turnMeta) { key -> expandedSnapshot[key] == true }
        }
        val itemsCount = rendered.size + if (skeletonShowing || awaitingShowing) 1 else 0

        // ── Search & navigation lookups ──────────────────────────────────
        // Matches are computed over filtered ENTRIES (not render items) so a
        // hit inside a collapsed turn can be navigated to by first expanding
        // that turn. keyByEntryId maps an entry to the LazyColumn item that
        // hosts it (a grouped tool call maps to its group row).
        val searchMatches = remember(filtered, searchQuery) {
            if (searchQuery.length < 2) emptyList()
            else filtered.filter { entryMatchesQuery(it, searchQuery) }.map { it.id }
        }
        val keyByEntryId = remember(rendered) {
            buildMap {
                for (item in rendered) when (item) {
                    is RenderItem.Single -> put(item.entry.id, itemKey(item))
                    is RenderItem.ToolGroup -> {
                        val k = itemKey(item)
                        for (call in item.calls) put(call.id, k)
                    }
                    is RenderItem.TurnSteps -> {}
                    is RenderItem.TimeGap -> {}
                }
            }
        }
        val turnKeyByEntryId = remember(filtered) { mapEntryToTurn(filtered) }
        val promptIndices = remember(rendered) {
            rendered.indices.filter {
                val item = rendered[it]
                item is RenderItem.Single &&
                    (item.entry is TranscriptEntry.UserPrompt || item.entry is TranscriptEntry.SlashCommand)
            }
        }
        var pendingScrollEntryId by remember(sessionKey) { mutableStateOf<String?>(null) }

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

        // ── Navigation actions ───────────────────────────────────────────
        fun scrollGuarded(block: suspend () -> Unit) {
            scope.launch {
                programmaticDepth++
                try {
                    block()
                    // Hold the guard one settle frame (same reason as the
                    // auto-scroll effect above).
                    kotlinx.coroutines.delay(120)
                } finally {
                    programmaticDepth--
                }
            }
        }
        fun jumpToPrompt(direction: Int) {
            val cur = listState.firstVisibleItemIndex
            val target = if (direction < 0) promptIndices.lastOrNull { it < cur }
            else promptIndices.firstOrNull { it > cur }
            if (target != null) {
                stickToBottom = false
                scrollGuarded { listState.animateScrollToItem(target) }
            }
        }
        fun jumpToBottom() {
            stickToBottom = true
            scrollGuarded {
                listState.scrollToItem((itemsCount - 1).coerceAtLeast(0))
                listState.scrollBy(Float.MAX_VALUE)
            }
        }
        fun gotoMatch(pos: Int) {
            if (searchMatches.isEmpty()) return
            val p = ((pos % searchMatches.size) + searchMatches.size) % searchMatches.size
            searchPos = p
            val id = searchMatches[p]
            // Hit inside a collapsed turn: expand it first; the pending-scroll
            // effect below resolves the item once the new render list exists.
            if (keyByEntryId[id] == null) turnKeyByEntryId[id]?.let { expandedTurns[it] = true }
            pendingScrollEntryId = id
        }
        // Resolve a pending search target once (re)composition produced an
        // item hosting it — needed because expanding a turn changes `rendered`
        // a frame after gotoMatch runs.
        LaunchedEffect(pendingScrollEntryId, rendered) {
            val id = pendingScrollEntryId ?: return@LaunchedEffect
            val key = keyByEntryId[id] ?: return@LaunchedEffect
            val idx = rendered.indexOfFirst { itemKey(it) == key }
            if (idx < 0) return@LaunchedEffect
            pendingScrollEntryId = null
            stickToBottom = false
            programmaticDepth++
            try {
                kotlinx.coroutines.yield()
                listState.scrollToItem(idx)
                kotlinx.coroutines.delay(120)
            } finally {
                programmaticDepth--
            }
        }
        // Keep the match cursor valid when streaming shrinks the match list —
        // otherwise the counter can read "6/3" and the highlight vanishes.
        LaunchedEffect(searchMatches.size) {
            if (searchPos >= searchMatches.size) {
                searchPos = (searchMatches.size - 1).coerceAtLeast(0)
            }
        }
        // New query → navigate to its most recent (bottom-most) hit. Keyed on
        // the query, not the match list, so streaming entries don't yank the
        // viewport while the search bar is open. lastNavQuery is only advanced
        // once a hit exists: a match that arrives a moment after typing still
        // gets focused.
        var lastNavQuery by remember(sessionKey) { mutableStateOf("") }
        LaunchedEffect(searchMatches, searchQuery) {
            if (searchQuery != lastNavQuery && searchMatches.isNotEmpty()) {
                lastNavQuery = searchQuery
                gotoMatch(searchMatches.size - 1)
            }
        }
        val currentMatchKey =
            if (searchOpen && searchMatches.isNotEmpty())
                searchMatches.getOrNull(searchPos)?.let { keyByEntryId[it] }
            else null

        if (searchOpen) {
            TranscriptSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                matchCount = searchMatches.size,
                matchPos = searchPos,
                onPrev = { gotoMatch(searchPos - 1) },
                onNext = { gotoMatch(searchPos + 1) },
                onClose = {
                    searchOpen = false
                    searchQuery = ""
                },
            )
        }
        CompositionLocalProvider(
            LocalDensity provides Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * fontScale
            )
        ) {
            Box(Modifier.fillMaxSize()) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(cardGap),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(
                            items = rendered,
                            // Keys are namespaced per render type (see itemKey) so a
                            // Single entry id can never collide with a ToolGroup key —
                            // a duplicate LazyColumn key is a hard crash, not a glitch.
                            key = { itemKey(it) }
                        ) { item ->
                            val highlighted = currentMatchKey != null && itemKey(item) == currentMatchKey
                            Box(
                                modifier = if (highlighted)
                                    Modifier.background(
                                        CRTheme.colors.tintYellow,
                                        RoundedCornerShape(6.dp)
                                    )
                                else Modifier
                            ) {
                                when (item) {
                                    is RenderItem.Single -> when (val e = item.entry) {
                                        is TranscriptEntry.UserPrompt -> UserPromptCard(e)
                                        is TranscriptEntry.SlashCommand -> SlashCommandRow(e)
                                        is TranscriptEntry.AssistantText -> AssistantTextCard(e)
                                        is TranscriptEntry.AssistantThinking -> ThinkingCard(e)
                                        is TranscriptEntry.ToolCall -> when (e.name) {
                                            "AskUserQuestion" ->
                                                AskUserQuestionCard(e, resultsByToolId[e.toolUseId])
                                            "TodoWrite" ->
                                                TodoChecklistCard(e, resultsByToolId[e.toolUseId])
                                            "Task", "Agent" ->
                                                AgentCard(e, resultsByToolId[e.toolUseId])
                                            else -> ToolRow(e, resultsByToolId[e.toolUseId])
                                        }
                                        is TranscriptEntry.ToolResult -> ToolResultCard(e)
                                        is TranscriptEntry.SystemNote -> SystemNoteRow(e)
                                    }
                                    is RenderItem.ToolGroup -> ToolGroupBlock(item.calls, resultsByToolId)
                                    is RenderItem.TurnSteps -> TurnStepsRow(item) {
                                        expandedTurns[item.turnKey] =
                                            !(expandedTurns[item.turnKey] ?: false)
                                    }
                                    is RenderItem.TimeGap -> TimeGapRow(item.label)
                                }
                            }
                        }
                        if (effectiveActivity == SessionActivity.WORKING) {
                            item(key = "__working_skeleton__") { WorkingSkeletonCard() }
                        } else if (effectiveActivity == SessionActivity.APPROVAL_NEEDED) {
                            // Claude is showing a permission / AskUserQuestion selector
                            // on the live screen. The tool_use isn't flushed to the
                            // transcript until it's answered, so we can't render the
                            // real question card yet — surface a banner instead of
                            // leaving the chat looking idle (or stuck "working…").
                            item(key = "__awaiting_answer__") { AwaitingAnswerCard() }
                        }
                    }
                }
                // Prev/next user-prompt jumps — only useful with 2+ prompts.
                if (promptIndices.size > 1) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OverlayCircleButton(
                            icon = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Previous prompt",
                            onClick = { jumpToPrompt(-1) }
                        )
                        OverlayCircleButton(
                            icon = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Next prompt",
                            onClick = { jumpToPrompt(1) }
                        )
                    }
                }
                if (!stickToBottom) {
                    OverlayCircleButton(
                        icon = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Jump to bottom",
                        accent = true,
                        onClick = { jumpToBottom() },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                    )
                }
            }
        }
    }
}

/** Small semi-transparent circular overlay button used by chat navigation. */
@Composable
private fun OverlayCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val c = CRTheme.colors
    Box(
        modifier = modifier
            .size(36.dp)
            .background(
                if (accent) c.accent.copy(alpha = 0.9f) else c.surface2.copy(alpha = 0.85f),
                CircleShape
            )
            .border(1.dp, if (accent) c.accent else c.border, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (accent) c.accentInk else c.textDim,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** Inline search bar: query field · match position · prev/next · close. */
@Composable
private fun TranscriptSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    matchPos: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = CRType.mono.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier
                .weight(1f)
                .background(c.surface2, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text("Search transcript…", style = CRType.mono, color = c.textDim)
                    }
                    inner()
                }
            }
        )
        Text(
            if (matchCount > 0) "${matchPos + 1}/$matchCount" else "0/0",
            style = CRType.monoTiny,
            color = if (matchCount > 0) c.text else c.textDim
        )
        IconButton(onClick = onPrev, modifier = Modifier.size(28.dp), enabled = matchCount > 0) {
            Icon(Icons.Default.KeyboardArrowUp, "Previous match", tint = c.textDim, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onNext, modifier = Modifier.size(28.dp), enabled = matchCount > 0) {
            Icon(Icons.Default.KeyboardArrowDown, "Next match", tint = c.textDim, modifier = Modifier.size(18.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
            Text("✕", style = CRType.mono, color = c.textDim)
        }
    }
}

private fun itemKey(item: RenderItem): String = when (item) {
    is RenderItem.Single -> "s:" + item.entry.id
    is RenderItem.ToolGroup -> "tg:" + item.calls.first().id
    is RenderItem.TurnSteps -> "ts:" + item.turnKey
    is RenderItem.TimeGap -> item.key
}

private fun entryMatchesQuery(e: TranscriptEntry, q: String): Boolean = when (e) {
    is TranscriptEntry.UserPrompt -> e.text.contains(q, ignoreCase = true)
    is TranscriptEntry.SlashCommand -> "/${e.name} ${e.args}".contains(q, ignoreCase = true)
    is TranscriptEntry.AssistantText -> e.text.contains(q, ignoreCase = true)
    is TranscriptEntry.AssistantThinking -> e.text.contains(q, ignoreCase = true)
    is TranscriptEntry.ToolCall ->
        e.name.contains(q, ignoreCase = true) ||
            e.inputSummary.contains(q, ignoreCase = true) ||
            e.fullInput.contains(q, ignoreCase = true)
    is TranscriptEntry.ToolResult -> e.text.contains(q, ignoreCase = true)
    is TranscriptEntry.SystemNote -> e.text.contains(q, ignoreCase = true)
}

/** Map every non-anchor entry id to the id of the user prompt anchoring its turn. */
private fun mapEntryToTurn(filtered: List<TranscriptEntry>): Map<String, String> {
    val out = HashMap<String, String>()
    var turn: String? = null
    for (e in filtered) {
        if (e is TranscriptEntry.UserPrompt || e is TranscriptEntry.SlashCommand) turn = e.id
        else turn?.let { out[e.id] = it }
    }
    return out
}

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

/**
 * Assistant text is the primary content of the chat — render it flat (no
 * card chrome, no role pill). Metadata (model · time, copy, speaker) sits in
 * a single quiet hairline row under the body so it's available but doesn't
 * compete with the content.
 */
@Composable
private fun AssistantTextCard(entry: TranscriptEntry.AssistantText) {
    val c = CRTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
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
                    color = c.textDim
                )
            }
        }
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

/**
 * Compact one-line tool row: category glyph · name · summary · status.
 * Borderless — tool calls are secondary detail, only an error result gets
 * card emphasis. Expanded reveals input + result indented under the row.
 */
@Composable
private fun ToolRow(
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

private data class AskOption(val label: String, val description: String)
private data class AskQuestion(
    val header: String,
    val question: String,
    val multiSelect: Boolean,
    val options: List<AskOption>,
)

/**
 * Parse the AskUserQuestion tool_use input (the pretty JSON in fullInput) into
 * a list of questions + options. Returns empty on any shape mismatch.
 */
private fun parseAskUserQuestions(json: String): List<AskQuestion> {
    if (json.isBlank()) return emptyList()
    return try {
        val obj = kotlinx.serialization.json.Json
            .parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val arr = obj["questions"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        arr.mapNotNull { q ->
            val qo = q as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(key: String): String =
                (qo[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            // FIX E: parse boolean properly — toBooleanStrictOrNull handles both
            // JSON true (unquoted) and string "true" defensively.
            val multi = (qo["multiSelect"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.toBooleanStrictOrNull() ?: false
            val opts = (qo["options"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { o ->
                    val oo = o as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    AskOption(
                        label = (oo["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                        description = (oo["description"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                    )
                } ?: emptyList()
            AskQuestion(
                header = str("header"),
                question = str("question"),
                multiSelect = multi,
                options = opts,
            )
        }
    } catch (_: Throwable) { emptyList() }
}

/**
 * Prominent card for Claude's AskUserQuestion tool call: shows each question's
 * header/question with a numbered list of options. When the paired tool_result
 * is present, shows the chosen answer(s) (the result content as-is — Claude
 * writes the selected label(s) there). When still unanswered, hints the user
 * to answer in the terminal where the real TUI widget lives.
 */
@Composable
private fun AskUserQuestionCard(
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

/**
 * Extract just the answer value(s) from Claude's verbose tool_result text.
 * Format: `User has answered your questions: "question"="answer". You can now…`
 * We pull all `="<value>"` matches and join them. Returns blank if no match so
 * the caller can fall back to the full trimmed text. Defensive — no crash.
 */
private fun extractAskAnswers(text: String): String {
    return try {
        val regex = Regex("=\"([^\"]*)\"")
        val matches = regex.findAll(text).map { it.groupValues[1] }.toList()
        matches.joinToString(", ")
    } catch (_: Throwable) { "" }
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

/**
 * A run of consecutive tool calls collapsed to ONE summary row:
 * `⚙ 14 tools · 6×Read 4×Bash 3×Edit ✓`. Errors surface as a red count,
 * a still-running call as a pulsing dot. Tap toggles the full row list.
 * Collapsed by default — the group is working noise, not content.
 */
private data class TodoLine(val content: String, val status: String)

/** Parse a TodoWrite input `{"todos":[{"content","status",…}]}`; empty on mismatch. */
private fun parseTodoInput(json: String): List<TodoLine> {
    if (json.isBlank()) return emptyList()
    return try {
        val obj = kotlinx.serialization.json.Json
            .parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject ?: return emptyList()
        val arr = obj["todos"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        arr.mapNotNull { t ->
            val to = t as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val content = (to["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: (to["subject"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: return@mapNotNull null
            val status = (to["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "pending"
            TodoLine(content, status)
        }
    } catch (_: Throwable) {
        emptyList()
    }
}

/**
 * TodoWrite rendered as an actual checklist (☐ / ◐ / ☑) instead of a generic
 * tool row — the plan is content, not plumbing. Falls back to the generic row
 * when the input doesn't parse.
 */
@Composable
private fun TodoChecklistCard(
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
 * Prominent card for a subagent launch (Task/Agent tool): the description is
 * the headline, with a running pulse until the result lands and a short tail
 * of the agent's final report once it does. Tap for full input/result detail.
 */
@Composable
private fun AgentCard(
    entry: TranscriptEntry.ToolCall,
    result: TranscriptEntry.ToolResult?,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val running = result == null
    val accent = if (result?.isError == true) c.disconnected else c.modePlan
    CRCard(
        background = c.surface,
        borderColor = accent.copy(alpha = 0.4f),
        padding = PaddingValues(horizontal = m.cardPadH, vertical = 6.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Pill(
                    text = "AGENT",
                    background = accent.copy(alpha = 0.18f),
                    foreground = accent
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    entry.inputSummary.ifBlank { entry.name },
                    style = CRType.cardTitle,
                    color = c.text,
                    maxLines = 2,
                    modifier = Modifier.weight(1f)
                )
                if (running) {
                    PendingDot(modifier = Modifier.padding(start = 4.dp))
                } else if (result.isError) {
                    Text("!", style = CRType.monoTiny, color = c.disconnected, modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (running) {
                Text("running…", style = CRType.monoTiny, color = c.textDim, modifier = Modifier.padding(top = 2.dp))
            } else if (!expanded) {
                val tail = result.text.lines().takeLast(2).joinToString("\n").trim()
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

@Composable
private fun ToolGroupBlock(
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

private sealed class RenderItem {
    data class Single(val entry: TranscriptEntry) : RenderItem()
    data class ToolGroup(val calls: List<TranscriptEntry.ToolCall>) : RenderItem()
    data class TurnSteps(
        val turnKey: String,
        val steps: Int,
        val durationMs: Long?,
        val expanded: Boolean,
    ) : RenderItem()
    data class TimeGap(val key: String, val label: String) : RenderItem()
}

private data class TurnMeta(val steps: Int, val durationMs: Long?)

/**
 * Work-step count + summed turn duration per user-prompt id, computed from the
 * RAW entry list so entries hidden by display filters (thinking, system) still
 * count. A "step" is a tool call, a thinking block, or an intermediate
 * assistant text; the turn's final assistant answer is not a step. Durations
 * are summed because hook-driven loops can stop a turn more than once.
 */
private fun computeTurnMeta(entries: List<TranscriptEntry>): Map<String, TurnMeta> {
    val out = HashMap<String, TurnMeta>()
    var promptId: String? = null
    var steps = 0
    var lastContentWasText = false
    var duration = 0L
    var hasDuration = false
    fun flush() {
        promptId?.let {
            out[it] = TurnMeta(
                steps = (steps - if (lastContentWasText) 1 else 0).coerceAtLeast(0),
                durationMs = if (hasDuration) duration else null,
            )
        }
    }
    for (e in entries) {
        when (e) {
            is TranscriptEntry.UserPrompt, is TranscriptEntry.SlashCommand -> {
                flush()
                promptId = e.id
                steps = 0
                lastContentWasText = false
                duration = 0L
                hasDuration = false
            }
            is TranscriptEntry.ToolCall -> { steps++; lastContentWasText = false }
            is TranscriptEntry.AssistantThinking -> { steps++; lastContentWasText = false }
            is TranscriptEntry.AssistantText -> { steps++; lastContentWasText = true }
            is TranscriptEntry.SystemNote -> if (e.durationMs != null) {
                duration += e.durationMs
                hasDuration = true
            }
            else -> {}
        }
    }
    flush()
    return out
}

/**
 * Build the LazyColumn item list from display-filtered entries.
 *
 * Entries are segmented into turns anchored on user prompts / slash commands.
 * Every turn but the last renders as: prompt · [▶ N steps · duration] · final
 * answer, with the middle expanded on demand. The last (live) turn renders in
 * full. The final answer is extracted only when it is literally the turn's
 * last filtered entry, so display order is always preserved.
 */
private fun buildRenderList(
    filtered: List<TranscriptEntry>,
    meta: Map<String, TurnMeta>,
    isExpanded: (String) -> Boolean,
): List<RenderItem> {
    val anchors = filtered.indices.filter {
        filtered[it] is TranscriptEntry.UserPrompt || filtered[it] is TranscriptEntry.SlashCommand
    }
    if (anchors.isEmpty()) return insertTimeGaps(groupConsecutiveTools(filtered))
    val out = ArrayList<RenderItem>(filtered.size)
    // Pre-anchor prefix (post-compaction summary etc.) renders as-is.
    out += groupConsecutiveTools(filtered.subList(0, anchors.first()))
    for ((ai, start) in anchors.withIndex()) {
        val end = if (ai + 1 < anchors.size) anchors[ai + 1] else filtered.size
        val prompt = filtered[start]
        val body = filtered.subList(start + 1, end)
        out += RenderItem.Single(prompt)
        if (ai == anchors.size - 1) {
            // Live turn — never collapsed.
            out += groupConsecutiveTools(body)
            continue
        }
        // Final answer = the last AssistantText followed only by system notes.
        // With showSystem on, turn_duration/stop_hook_summary notes trail the
        // answer in the filtered body — skipping them keeps the answer visible
        // outside the collapse. Any other entry kind after the text means the
        // turn has no final answer (e.g. it ended in a tool call).
        var finalIdx = -1
        for (i in body.indices.reversed()) {
            val e = body[i]
            if (e is TranscriptEntry.SystemNote) continue
            if (e is TranscriptEntry.AssistantText) finalIdx = i
            break
        }
        val final = if (finalIdx >= 0) body[finalIdx] else null
        val middle = if (finalIdx >= 0) body.subList(0, finalIdx) else body
        val trailing = if (finalIdx >= 0) body.subList(finalIdx + 1, body.size) else emptyList()
        if (middle.isNotEmpty()) {
            val key = prompt.id
            val m = meta[key]
            val expanded = isExpanded(key)
            out += RenderItem.TurnSteps(
                turnKey = key,
                // Raw-entry step count when available (counts hidden thinking);
                // fall back to the visible middle size.
                steps = m?.steps?.takeIf { it > 0 } ?: middle.size,
                durationMs = m?.durationMs,
                expanded = expanded,
            )
            if (expanded) out += groupConsecutiveTools(middle)
        }
        if (final != null) out += RenderItem.Single(final)
        for (t in trailing) out += RenderItem.Single(t)
    }
    return insertTimeGaps(out)
}

/** Minimum quiet period between two entries that earns a visual separator. */
private const val TIME_GAP_MINUTES = 30L

/**
 * Insert a thin `── 14:32 ──` separator wherever two adjacent items are more
 * than [TIME_GAP_MINUTES] apart — an anchor for "I came back hours later".
 */
private fun insertTimeGaps(items: List<RenderItem>): List<RenderItem> {
    if (items.size < 2) return items
    val out = ArrayList<RenderItem>(items.size + 4)
    var prev: Long? = null
    for (item in items) {
        val ts = itemTimestamp(item)
        val cur = isoToEpochMinutes(ts)
        if (prev != null && cur != null && cur - prev >= TIME_GAP_MINUTES) {
            // Key derived from the item the gap precedes: at most one gap per
            // item, and stable across streaming updates (a positional index
            // would re-key every gap whenever an earlier item is inserted).
            out += RenderItem.TimeGap(
                key = "gap:" + itemKey(item),
                label = formatTimestamp(ts!!).take(5)
            )
        }
        if (cur != null) prev = cur
        out += item
    }
    return out
}

private fun itemTimestamp(item: RenderItem): String? = when (item) {
    is RenderItem.Single -> item.entry.timestamp
    is RenderItem.ToolGroup -> item.calls.first().timestamp
    is RenderItem.TurnSteps -> null
    is RenderItem.TimeGap -> null
}

/**
 * ISO-8601 UTC timestamp → minutes since epoch, or null on malformed input.
 * Uses the exact civil-date day count (Hinnant's days_from_civil) — no
 * platform date APIs exist in commonMain and second precision isn't needed
 * for gap detection.
 */
private fun isoToEpochMinutes(iso: String?): Long? {
    if (iso == null || iso.length < 16) return null
    return try {
        val y = iso.substring(0, 4).toInt()
        val mo = iso.substring(5, 7).toInt()
        val d = iso.substring(8, 10).toInt()
        val h = iso.substring(11, 13).toInt()
        val mi = iso.substring(14, 16).toInt()
        val yy = if (mo <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (mo > 2) mo - 3 else mo + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468L
        days * 1440 + h * 60 + mi
    } catch (_: Throwable) {
        null
    }
}

@Composable
private fun TimeGapRow(label: String) {
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

/** Collapsed-middle expander row for an old turn: `▶ 23 steps · 1m 42s`. */
@Composable
private fun TurnStepsRow(item: RenderItem.TurnSteps, onToggle: () -> Unit) {
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

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

/**
 * Tool calls that render as standalone prominent cards — never folded into a
 * collapsed tool group: questions, todo checklists, subagent launches.
 */
private val standaloneTools = setOf("AskUserQuestion", "TodoWrite", "Task", "Agent")

/**
 * Walk the entries and fuse any run of two-or-more consecutive ToolCall
 * entries into a single ToolGroup item.
 */
private fun groupConsecutiveTools(entries: List<TranscriptEntry>): List<RenderItem> {
    val out = ArrayList<RenderItem>(entries.size)
    var i = 0
    while (i < entries.size) {
        val e = entries[i]
        if (e is TranscriptEntry.ToolCall && e.name !in standaloneTools) {
            var j = i + 1
            while (j < entries.size && entries[j] is TranscriptEntry.ToolCall &&
                (entries[j] as TranscriptEntry.ToolCall).name !in standaloneTools
            ) j++
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
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    val lines = entry.text.lines()
    // Tail, not head: the interesting part of command output (exit status,
    // error message, summary line) is at the END.
    val preview = lines.takeLast(3).joinToString("\n")
    val hasMore = lines.size > 3
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
    // Local wall-clock time; raw UTC clock substring only if parsing fails
    // (2026-05-15T15:24:02.384Z → 15:24:02).
    return com.clauderemote.util.isoToLocalTime(iso)
        ?: iso.substringAfter('T').substringBefore('.').substringBefore('Z').take(8)
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

/**
 * Bottom-of-chat banner shown while a permission / AskUserQuestion selector is
 * live on screen. Claude Code does not flush the AskUserQuestion tool_use to the
 * .jsonl until the user answers, so the real question card can't render yet —
 * this tells the user Claude is blocked on them and where to answer, instead of
 * the chat looking idle or (before the effectiveActivity fix) stuck "working…".
 */
@Composable
private fun AwaitingAnswerCard() {
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
    val d = minutes / 1440
    val h = (minutes % 1440) / 60
    val m = minutes % 60
    return when {
        d > 0 -> "${d}d${h}h"
        h > 0 -> "${h}h${m}m"
        else -> "${m}m"
    }
}

private val openTodoRegex = Regex("\"status\"\\s*:\\s*\"(pending|in_progress)\"")

private fun countOpenTodos(entries: List<TranscriptEntry>): Int {
    val last = entries.asReversed().firstOrNull {
        it is TranscriptEntry.ToolCall && it.name == "TodoWrite"
    } as? TranscriptEntry.ToolCall ?: return 0
    val json = last.fullInput
    if (json.isBlank()) return 0
    return openTodoRegex.findAll(json).count()
}
