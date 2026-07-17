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
    connectionLabel: String? = null,
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
            connectionLabel = connectionLabel,
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
        // Frame the live turn's answer only once the stop marker confirms the
        // turn is over (transcriptWorking == false); while working — or with
        // no marker info at all — leave it flat to avoid frame flicker.
        val liveTurnDone = transcriptWorking == false
        val rendered = remember(filtered, turnMeta, expandedSnapshot, liveTurnDone) {
            buildRenderList(filtered, turnMeta, liveTurnDone) { key -> expandedSnapshot[key] == true }
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
                                        is TranscriptEntry.AssistantText ->
                                            AssistantTextCard(e, framed = item.isFinalAnswer)
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

private val openTodoRegex = Regex("\"status\"\\s*:\\s*\"(pending|in_progress)\"")

private fun countOpenTodos(entries: List<TranscriptEntry>): Int {
    val last = entries.asReversed().firstOrNull {
        it is TranscriptEntry.ToolCall && it.name == "TodoWrite"
    } as? TranscriptEntry.ToolCall ?: return 0
    val json = last.fullInput
    if (json.isBlank()) return 0
    return openTodoRegex.findAll(json).count()
}
