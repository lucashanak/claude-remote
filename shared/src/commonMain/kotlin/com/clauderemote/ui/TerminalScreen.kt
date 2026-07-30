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

// ---------------------------------------------------------------------------
// Special key enum (spec §6.3)
// ---------------------------------------------------------------------------

enum class SpecialKey(val bytes: ByteArray) {
    Esc(   byteArrayOf(0x1B)),
    Tab(   byteArrayOf(0x09)),
    Up(    byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())),
    Down(  byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())),
    Right( byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte())),
    Left(  byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte())),
    CtrlC( byteArrayOf(0x03)),
    CtrlD( byteArrayOf(0x04)),
}

// ---------------------------------------------------------------------------
// Private session list item (unchanged from original)
// ---------------------------------------------------------------------------

internal data class SessionItem(
    val id: String, val label: String, val folder: String,
    val isConnected: Boolean,
    val status: SessionStatus?, val tab: ClaudeSession?,
    val remote: com.clauderemote.model.RemoteSession?
)

// ---------------------------------------------------------------------------
// TerminalScreen — primary entry point
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    tabs: List<ClaudeSession>,
    activeTabId: String?,
    onTabSwitch: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onSessionLongPress: ((String) -> Unit)? = null,
    onNewTab: () -> Unit,
    onMenuOpen: () -> Unit,
    onSendCommand: (String) -> Unit,
    onSwitchModel: (ClaudeModel) -> Unit,
    onSwitchEffort: ((com.clauderemote.model.ClaudeEffort) -> Unit)? = null,
    onSendEscape: () -> Unit,
    onPageUp: () -> Unit = {},
    onPageDown: () -> Unit = {},
    onReconnect: ((String) -> Unit)? = null,
    onRestartClaude: ((String) -> Unit)? = null,
    onRenameSession: ((sessionId: String, newAlias: String) -> Unit)? = null,
    onAttachFile: (suspend () -> String?)? = null,
    onDownloadFile: (suspend (path: String) -> ByteArray?)? = null,
    /** Narrows chat file-path candidates to the ones that are really files on the server. */
    onVerifyPaths: (suspend (List<String>) -> Set<String>)? = null,
    onSaveFile: ((bytes: ByteArray, suggestedName: String) -> Unit)? = null,
    onFetchClaudeMd: (suspend () -> String)? = null,
    onSaveClaudeMd: (suspend (String) -> Unit)? = null,
    onFetchCommands: (suspend () -> List<SlashCommand>)? = null,
    onFontSizeChange: ((Int) -> Unit)? = null,
    onAttachRemote: ((com.clauderemote.model.RemoteSession) -> Unit)? = null,
    remoteSessions: List<com.clauderemote.model.RemoteSession> = emptyList(),
    contextPercent: Int? = null,
    gitStatus: com.clauderemote.model.GitStatus? = null,
    sessionUsagePercent: Int? = null,
    weekUsagePercent: Int? = null,
    sessionResetMin: Int? = null,
    weekResetMin: Int? = null,
    sessionActivities: Map<String, com.clauderemote.model.SessionActivity> = emptyMap(),
    hookActiveSessions: Set<String> = emptySet(),
    reconnectStatus: Map<String, com.clauderemote.session.ReconnectInfo> = emptyMap(),
    loginFlow: com.clauderemote.model.LoginFlowState? = null,
    onOpenLoginUrl: (String) -> Unit = {},
    onSubmitLoginCode: (String) -> Unit = {},
    onCancelLogin: () -> Unit = {},
    onLogin: (() -> Unit)? = null,
    latencyMs: Long? = null,
    pendingInputCount: Int = 0,
    onClearPending: (() -> Unit)? = null,
    onNavigate: ((String) -> Unit)? = null,
    invertColors: Boolean = false,
    onToggleInvertColors: (() -> Unit)? = null,
    onTerminalViewChange: ((CRTerminalView) -> Unit)? = null,
    terminalContent: @Composable (Modifier) -> Unit,
    terminalScrolledUp: Boolean = false,
    terminalPendingOutput: Boolean = false,
    onJumpToLatest: (() -> Unit)? = null,
    transcriptEntries: List<TranscriptEntry> = emptyList(),
    remoteStatus: RemoteSessionStatus? = null,
    onTerminalContentVisible: (() -> Unit)? = null,
    activeClaudeSessionId: String? = null,
    transcriptStatus: String? = null,
    sidePanelWidthDp: Int = 220,
    onSidePanelWidthChange: ((Int) -> Unit)? = null,
    // Pane grid (Phase 1, low-risk). Defaulted so existing callers compile
    // unchanged and stay on the single-pane ONE path.
    gridLayout: com.clauderemote.model.GridLayout = com.clauderemote.model.GridLayout.ONE,
    paneSessions: List<String?> = listOf(null, null, null, null),
    paneTranscripts: List<List<TranscriptEntry>> = listOf(emptyList(), emptyList(), emptyList(), emptyList()),
    focusedPaneIndex: Int = 0,
    onSetLayout: ((com.clauderemote.model.GridLayout) -> Unit)? = null,
    // FIX 1: (paneIndex, sessionId?) — index-based focus guarantees exactly
    // one cell ever hosts the single shared raw terminal.
    onFocusPane: ((paneIndex: Int, sessionId: String?) -> Unit)? = null,
    onAssignPane: ((paneIndex: Int, sessionId: String) -> Unit)? = null,
    // #70: Claude is awaiting a choice (AskUserQuestion or permission prompt) on
    // the active session. When the user is in Chat and the feature is enabled, we
    // transiently render the raw terminal so they can answer the real TUI widget.
    awaitingChoice: Boolean = false,
    autoOpenTerminalOnPrompt: Boolean = true,
    // FIX B: per-pane pending-ask flags (size 4, mirrors paneTranscripts).
    // A non-focused pane with pendingAsk=true shows a small "Claude asks" badge;
    // the focused pane uses the existing awaitingChoice auto-switch instead.
    panePendingAsk: List<Boolean> = listOf(false, false, false, false),
    // #75: when true the terminal emulator is kept composed under the Chat overlay
    // so screenReader stays fed in Chat view (Android single-pane only). Desktop
    // keeps the old swap behaviour (false) because AWT SwingPanel bleeds through
    // a Compose overlay and cannot be reliably occluded by a lightweight layer.
    composeTerminalUnderTranscript: Boolean = false,
    connectionLabel: String? = null,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val terminalView = LocalCRTerminalView.current
    // Captured once for every FloatingDialog in this screen — a new
    // top-level window (the desktop actual) opens its own composition root
    // and needs these values re-provided explicitly; see CRThemeSnapshot.
    val crThemeSnapshot = com.clauderemote.ui.theme.CRThemeSnapshot.current()

    // #70: transient auto-switch to raw when Claude is awaiting a choice. The
    // user can dismiss (stay in Chat) per-prompt; a NEW prompt re-triggers the
    // switch because the dismiss flag is reset whenever awaitingChoice clears.
    // FIX C: key dismiss state on activeTabId so switching to a different tab
    // that is also awaiting re-arms the auto-switch (no stale dismiss leak).
    var userDismissedPrompt by remember(activeTabId) { mutableStateOf(false) }
    LaunchedEffect(awaitingChoice) { if (!awaitingChoice) userDismissedPrompt = false }
    val promptAutoSwitch = autoOpenTerminalOnPrompt && awaitingChoice &&
        terminalView == CRTerminalView.Transcript && !userDismissedPrompt
    // Render-only view: the persisted `terminalView` is never mutated here.
    val effectiveTerminalView = if (promptAutoSwitch) CRTerminalView.Raw else terminalView

    // State — preserved from original
    var showControlBar by remember { mutableStateOf(true) }
    var compactMode by remember { mutableStateOf(false) }
    var currentFontSize by remember { mutableStateOf(14) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var moreMenu by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    // File download / image preview state
    var showDownloadDialog by remember { mutableStateOf(false) }
    // Path tapped in a Claude answer, awaiting confirmation.
    var pendingFileLink by remember { mutableStateOf<String?>(null) }
    var downloadBusy by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var previewFileName by remember { mutableStateOf<String?>(null) }
    var previewBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var previewBytes by remember { mutableStateOf<ByteArray?>(null) }
    var downloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val inputFocusRequester = remember { FocusRequester() }
    LaunchedEffect(activeTabId) {
        if (activeTabId != null) {
            try { inputFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }
    var showClaudeMd by remember { mutableStateOf(false) }
    var claudeMdContent by remember { mutableStateOf("") }
    var commands by remember { mutableStateOf(CommandFetcher.getCachedOrFallback()) }
    val activeSession = tabs.find { it.id == activeTabId }
    val scope = rememberCoroutineScope()
    var showPalette by remember { mutableStateOf(false) }
    var showSessionDrawer by remember { mutableStateOf(false) }
    var showExpanded by remember { mutableStateOf(false) }

    // Tapping a path in a Claude answer opens the confirm dialog. Null when the
    // screen has no download capability, which also hides the links themselves.
    // Remembered so RichBody's UriHandler isn't rebuilt on every recomposition.
    val openFileLink: ((String) -> Unit)? = remember(onDownloadFile) {
        if (onDownloadFile == null) null else { path: String ->
            downloadError = null
            downloadBusy = false
            pendingFileLink = path
        }
    }

    // Shared by the manual "Download file…" dialog and by file links tapped in a
    // Claude answer. [stillOpen] reports whether the dialog that started the
    // download is still on screen — if the user dismissed it mid-flight we drop
    // the result rather than popping a save picker they no longer expect.
    val runDownload: (String, () -> Boolean, () -> Unit) -> Unit = { path, stillOpen, close ->
        downloadBusy = true
        downloadError = null
        downloadJob = scope.launch {
            val bytes = onDownloadFile?.invoke(path)
            if (!stillOpen()) return@launch
            downloadBusy = false
            when {
                bytes === com.clauderemote.session.SessionOrchestrator.DOWNLOAD_TOO_LARGE ->
                    downloadError = "File too large (>50 MB)."
                bytes == null ->
                    downloadError = "Download failed. Check the path and try again."
                else -> {
                    val name = fileNameFromPath(path)
                    if (isImagePath(path)) {
                        previewFileName = name
                        previewBytes = bytes
                        previewBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            com.clauderemote.util.decodeImageBitmap(bytes)
                        }
                    } else {
                        onSaveFile?.invoke(bytes, name)
                    }
                    close()
                }
            }
        }
    }

    // Replay terminal buffer when switching back from transcript. Keyed on the
    // EFFECTIVE view so the #70 auto-switch to Raw also triggers a replay.
    // #75: when composeTerminalUnderTranscript is true (Android single-pane) the
    // emulator was never torn down in Chat, so a Chat→Raw transition does NOT need
    // a buffer replay — the live screen is already correct. Replaying would wipe
    // the very prompt the user switched to answer (#70 auto-switch). We still
    // fire the replay on genuine tab-switches (activeTabId changes) because the
    // single shared emulator needs to be seeded with the new session's buffer.
    val prevActiveTabId = remember { mutableStateOf(activeTabId) }
    LaunchedEffect(effectiveTerminalView, activeTabId) {
        val tabSwitched = activeTabId != prevActiveTabId.value
        prevActiveTabId.value = activeTabId
        if (effectiveTerminalView == CRTerminalView.Raw) {
            // Skip view-only Chat→Raw replay when the terminal stayed composed
            // and no tab switch happened (the emulator already shows live state).
            val skipReplay = composeTerminalUnderTranscript && !tabSwitched
            if (!skipReplay) onTerminalContentVisible?.invoke()
        }
    }

    // Unified session list
    val allSessions = remember(tabs, remoteSessions) {
        val connectedTmux = tabs.map { it.tmuxSessionName }.toSet()
        fun parseFolder(raw: String): String {
            var f = raw.trimEnd('/').substringAfterLast('/').ifBlank { raw }
            f = f.replace(Regex("-yolo\\d*$"), "")
            return f.ifBlank { "~" }
        }
        val activeSessions = tabs.map { tab ->
            val parsed = com.clauderemote.model.TmuxNameParser.parse(tab.tmuxSessionName, tab.server.name)
            val alias = tab.alias.ifBlank { parsed.alias }
            val folder = parseFolder(tab.folder)
            val label = alias.ifBlank {
                val rawName = tab.folder.trimEnd('/').substringAfterLast('/').ifBlank { tab.folder }
                if (parsed.isYolo || tab.mode == ClaudeMode.YOLO) "$rawName ⚡" else rawName
            }
            SessionItem(tab.id, label, folder, true, tab.status, tab, null)
        }
        val remoteItems = remoteSessions.filter { remote ->
            remote.tmuxSession.name !in connectedTmux
        }.map { remote ->
            val parsed = com.clauderemote.model.TmuxNameParser.parse(remote.tmuxSession.name, remote.server.name)
            val folder = parseFolder(parsed.folder)
            val label = parsed.alias.ifBlank {
                if (parsed.isYolo) "${parsed.folder} ⚡" else parsed.folder
            }
            SessionItem(remote.tmuxSession.name, label, folder, false, null, null, remote)
        }
        (activeSessions + remoteItems).groupBy { it.folder }.toSortedMap()
    }

    // Keyboard shortcuts
    fun handleShortcut(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.isCtrlPressed || event.isMetaPressed) {
            when (event.key) {
                Key.K -> { showPalette = true; return true }
                Key.Tab -> {
                    if (tabs.size > 1) {
                        val currentIdx = tabs.indexOfFirst { it.id == activeTabId }
                        val nextIdx = if (event.isShiftPressed) {
                            if (currentIdx <= 0) tabs.size - 1 else currentIdx - 1
                        } else {
                            if (currentIdx >= tabs.size - 1) 0 else currentIdx + 1
                        }
                        onTabSwitch(tabs[nextIdx].id)
                    }
                    return true
                }
                Key.W -> { activeTabId?.let { onTabClose(it) }; return true }
                Key.N -> { onNewTab(); return true }
                else -> {}
            }
        }
        return false
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().onPreviewKeyEvent { handleShortcut(it) }) {
        val hasMultiple = tabs.size > 1 || remoteSessions.any { r -> tabs.none { it.tmuxSessionName == r.tmuxSession.name } }
        // Split view is a desktop-class feature: on desktop (incl. macOS, where the
        // reported window width can come back below the old hard 700.dp gate — which
        // is why the Layout 1/2/4 picker never appeared there) always offer it when
        // there is more than one session. Phones stay single-pane unless genuinely wide.
        val wideMode = (!isMobile || maxWidth > 700.dp) && hasMultiple
        // Captured once: the more-menu picker runs inside a FloatingDialog lambda
        // that no longer has the BoxWithConstraintsScope receiver in scope.
        val allowQuadLayout = !isMobile || maxWidth > 1000.dp

        var sidePanelWidth by remember { mutableStateOf(sidePanelWidthDp.dp) }
        val density = LocalDensity.current

        Row(modifier = Modifier.fillMaxSize()) {
            // Wide-screen side panel
            if (wideMode) {
                SessionSidePanel(
                    allSessions = allSessions,
                    activeTabId = activeTabId,
                    sessionActivities = sessionActivities,
                    onTabSwitch = onTabSwitch,
                    onTabClose = onTabClose,
                    onNewTab = onNewTab,
                    onMenuOpen = onMenuOpen,
                    onAttachRemote = onAttachRemote,
                    onRenameSession = onRenameSession,
                    onSessionLongPress = onSessionLongPress,
                    modifier = Modifier.width(sidePanelWidth).fillMaxHeight()
                )
                if (!isMobile) {
                    Box(
                        Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(c.border.copy(alpha = 0.6f))
                            .draggable(
                                orientation = Orientation.Horizontal,
                                state = rememberDraggableState { delta ->
                                    val newWidth = with(density) {
                                        (sidePanelWidth + delta.toDp()).coerceIn(160.dp, 480.dp)
                                    }
                                    sidePanelWidth = newWidth
                                    onSidePanelWidthChange?.invoke(newWidth.value.toInt())
                                },
                            )
                    )
                }
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight().background(c.bg)) {

                // ── Topbar (56 dp) ─────────────────────────────────────────
                CRTopBar(
                    activeSession = activeSession,
                    sessionActivities = sessionActivities,
                    hasMultiple = hasMultiple,
                    wideMode = wideMode,
                    tabs = tabs,
                    allSessions = allSessions,
                    activeTabId = activeTabId,
                    invertColors = invertColors,
                    terminalView = terminalView,
                    latencyMs = latencyMs,
                    contextPercent = contextPercent,
                    gitStatus = gitStatus,
                    sessionUsagePercent = sessionUsagePercent,
                    weekUsagePercent = weekUsagePercent,
                    compactMode = compactMode,
                    showControlBar = showControlBar,
                    onMenuOpen = onMenuOpen,
                    onTabSwitch = onTabSwitch,
                    onTabClose = onTabClose,
                    onNewTab = onNewTab,
                    onAttachRemote = onAttachRemote,
                    onToggleInvertColors = onToggleInvertColors,
                    onTerminalViewChange = onTerminalViewChange,
                    onToggleCompact = { compactMode = !compactMode },
                    onToggleControlBar = { showControlBar = !showControlBar },
                    // "..." always opens the same in-app menu, in every terminal
                    // view and on every platform: FloatingDialog (see its own doc
                    // comment) is what makes this safe on desktop's Raw view,
                    // where a plain in-window dialog would render behind the
                    // SwingPanel-embedded terminal.
                    onMoreMenu = { moreMenu = true },
                    onOpenDrawer = { showSessionDrawer = true },
                )

                // ── Crumb bar (36 dp) ──────────────────────────────────────
                if (activeSession != null) {
                    val allFlat = remember(allSessions) { allSessions.values.flatten() }
                    val idx = allFlat.indexOfFirst { it.tab?.id == activeTabId }.coerceAtLeast(0)
                    val total = allFlat.size.coerceAtLeast(1)
                    CrumbBar(
                        session = activeSession,
                        allSessions = allFlat,
                        index = idx,
                        total = total,
                        onOpenDrawer = { showSessionDrawer = true },
                        onPrev = {
                            if (idx > 0) {
                                val prev = allFlat[idx - 1]
                                if (prev.tab != null) onTabSwitch(prev.tab.id)
                                else if (prev.remote != null) onAttachRemote?.invoke(prev.remote)
                            }
                        },
                        onNext = {
                            if (idx < total - 1) {
                                val next = allFlat[idx + 1]
                                if (next.tab != null) onTabSwitch(next.tab.id)
                                else if (next.remote != null) onAttachRemote?.invoke(next.remote)
                            }
                        },
                    )
                }

                // ── More / rename / command dialogs ────────────────────────
                com.clauderemote.ui.components.FloatingDialog(
                    visible = moreMenu,
                    onDismiss = { moreMenu = false },
                    theme = crThemeSnapshot,
                    confirmButton = {},
                    text = {
                            Column {
                                // CommandPaletteDialog is a custom in-window Box overlay (not
                                // a FloatingDialog) — see its own doc comment for why: a past
                                // Compose Desktop / macOS bug where system Dialog content never
                                // rendered. That means it's still subject to the SAME
                                // SwingPanel-always-on-top problem this whole commit otherwise
                                // fixes: on desktop, in Raw view, it would open invisibly behind
                                // the terminal. Disable the entry there rather than expose a
                                // menu item that silently does nothing — composeTerminalUnderTranscript
                                // is true only on Android, which has no heavyweight surface to
                                // hide behind.
                                val paletteBlockedByRawView =
                                    !composeTerminalUnderTranscript && terminalView == CRTerminalView.Raw
                                TextButton(
                                    onClick = {
                                        moreMenu = false
                                        showPalette = true
                                        if (onFetchCommands != null) {
                                            scope.launch { commands = onFetchCommands.invoke() }
                                        }
                                    },
                                    enabled = !paletteBlockedByRawView,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        if (paletteBlockedByRawView) "Command Palette (switch to Chat first)" else "Command Palette",
                                        color = if (paletteBlockedByRawView) c.textDim else c.text,
                                    )
                                }
                                if (onFetchClaudeMd != null) {
                                    TextButton(onClick = {
                                        moreMenu = false
                                        scope.launch { claudeMdContent = onFetchClaudeMd.invoke(); showClaudeMd = true }
                                    }, modifier = Modifier.fillMaxWidth()) { Text("View CLAUDE.md", color = c.text) }
                                }
                                TextButton(onClick = { moreMenu = false; onSendCommand("c") },
                                    modifier = Modifier.fillMaxWidth()) { Text("Reset terminal", color = c.text) }
                                if (onDownloadFile != null) {
                                    TextButton(onClick = {
                                        moreMenu = false
                                        downloadError = null
                                        downloadBusy = false
                                        showDownloadDialog = true
                                    }, modifier = Modifier.fillMaxWidth()) { Text("Download file…", color = c.text) }
                                }
                                // Pane grid layout picker — wide screens only.
                                // Phones stay single-pane (the picker is hidden).
                                if (wideMode && onSetLayout != null) {
                                    HorizontalDivider(color = c.border, modifier = Modifier.padding(vertical = 4.dp))
                                    Text(
                                        "Layout",
                                        style = CRType.sectionH,
                                        color = c.textDim,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // "4" gated behind a wider threshold so
                                        // quarters aren't offered on tablet-width.
                                        val allowQuad = allowQuadLayout
                                        val opts = buildList {
                                            add(com.clauderemote.model.GridLayout.ONE to "1")
                                            add(com.clauderemote.model.GridLayout.TWO to "2")
                                            if (allowQuad) add(com.clauderemote.model.GridLayout.QUAD to "4")
                                        }
                                        opts.forEach { (layout, label) ->
                                            val selected = gridLayout == layout
                                            FilledTonalButton(
                                                onClick = {
                                                    moreMenu = false
                                                    onSetLayout.invoke(layout)
                                                },
                                                colors = ButtonDefaults.filledTonalButtonColors(
                                                    containerColor = if (selected) c.accent else c.surface2,
                                                    contentColor = if (selected) c.accentInk else c.text,
                                                ),
                                                modifier = Modifier.size(40.dp),
                                                contentPadding = PaddingValues(0.dp)
                                            ) { Text(label) }
                                        }
                                    }
                                }
                                HorizontalDivider(color = c.border, modifier = Modifier.padding(vertical = 4.dp))
                                // Font size
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text("Font: ", style = CRType.bodyDim, color = c.textDim)
                                    FilledTonalButton(
                                        onClick = { currentFontSize = (currentFontSize - 1).coerceIn(8, 32); onFontSizeChange?.invoke(currentFontSize) },
                                        modifier = Modifier.size(32.dp), contentPadding = PaddingValues(0.dp)
                                    ) { Text("A-") }
                                    Spacer(Modifier.width(12.dp))
                                    Text("$currentFontSize", style = CRType.cardTitle, color = c.text)
                                    Spacer(Modifier.width(12.dp))
                                    FilledTonalButton(
                                        onClick = { currentFontSize = (currentFontSize + 1).coerceIn(8, 32); onFontSizeChange?.invoke(currentFontSize) },
                                        modifier = Modifier.size(32.dp), contentPadding = PaddingValues(0.dp)
                                    ) { Text("A+") }
                                }
                                // Top-bar density toggles — moved here out of the
                                // always-visible bar to free horizontal space.
                                HorizontalDivider(color = c.border, modifier = Modifier.padding(vertical = 4.dp))
                                TextButton(onClick = { moreMenu = false; compactMode = !compactMode },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(if (compactMode) "Show full bar" else "Minimize bar", color = c.text)
                                }
                                if (!compactMode) {
                                    TextButton(onClick = { moreMenu = false; showControlBar = !showControlBar },
                                        modifier = Modifier.fillMaxWidth()) {
                                        Text(if (showControlBar) "Hide control bar" else "Show control bar", color = c.text)
                                    }
                                }
                                if (activeSession != null) {
                                    HorizontalDivider(color = c.border, modifier = Modifier.padding(vertical = 4.dp))
                                    TextButton(onClick = {
                                        moreMenu = false
                                        renameText = activeSession.alias.ifBlank { activeSession.displayLabel }
                                        showRenameDialog = true
                                    }, modifier = Modifier.fillMaxWidth()) { Text("Rename session", color = c.text) }
                                    if (activeSession.status == SessionStatus.DISCONNECTED || activeSession.status == SessionStatus.ERROR) {
                                        TextButton(onClick = { moreMenu = false; onReconnect?.invoke(activeSession.id) },
                                            modifier = Modifier.fillMaxWidth()) { Text("Reconnect", color = c.text) }
                                    }
                                    if (onRestartClaude != null && activeSession.status == SessionStatus.ACTIVE) {
                                        TextButton(onClick = { moreMenu = false; showRestartConfirm = true },
                                            modifier = Modifier.fillMaxWidth()) {
                                            Text("Restart Claude Code", color = c.text)
                                        }
                                    }
                                    TextButton(onClick = { moreMenu = false; onTabClose(activeSession.id) },
                                        modifier = Modifier.fillMaxWidth()) {
                                        Text("Close session", color = c.disconnected)
                                    }
                                }
                            }
                        }
                    )

                if (activeSession != null) {
                    com.clauderemote.ui.components.FloatingDialog(
                        visible = showRestartConfirm,
                        onDismiss = { showRestartConfirm = false },
                        theme = crThemeSnapshot,
                        title = { Text("Restart Claude Code", color = c.text) },
                        text = {
                            Text(
                                "Restarts the claude process in this session (e.g. to pick up an update) and resumes the SAME conversation. Any in-progress task is interrupted.",
                                color = c.textDim,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showRestartConfirm = false
                                onRestartClaude?.invoke(activeSession.id)
                            }) { Text("Restart", color = c.accent) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRestartConfirm = false }) { Text("Cancel", color = c.textDim) }
                        }
                    )
                }

                // File download dialog
                if (showDownloadDialog && onDownloadFile != null) {
                    DownloadPathDialog(
                        busy = downloadBusy,
                        errorMessage = downloadError,
                        onDismiss = {
                            downloadJob?.cancel()
                            downloadJob = null
                            downloadBusy = false
                            showDownloadDialog = false
                        },
                        onConfirm = { path ->
                            runDownload(path, { showDownloadDialog }, { showDownloadDialog = false })
                        },
                    )
                }

                // Confirmation for a file path tapped in a Claude answer.
                val linkPath = pendingFileLink
                if (linkPath != null && onDownloadFile != null) {
                    RemoteFileLinkDialog(
                        path = linkPath,
                        busy = downloadBusy,
                        errorMessage = downloadError,
                        onDismiss = {
                            downloadJob?.cancel()
                            downloadJob = null
                            downloadBusy = false
                            pendingFileLink = null
                        },
                        onConfirm = {
                            runDownload(linkPath, { pendingFileLink != null }, { pendingFileLink = null })
                        },
                    )
                }

                // Image preview dialog
                val pName = previewFileName
                val pBytes = previewBytes
                if (pName != null && pBytes != null) {
                    ImagePreviewDialog(
                        fileName = pName,
                        bitmap = previewBitmap,
                        onSave = {
                            onSaveFile?.invoke(pBytes, pName)
                            previewFileName = null
                            previewBytes = null
                            previewBitmap = null
                        },
                        onClose = {
                            previewFileName = null
                            previewBytes = null
                            previewBitmap = null
                        },
                    )
                }

                // Disconnected banner. When an auto-reconnect is actively in
                // progress we show its live status ("Reconnecting… (N/3)" /
                // "Retrying in Ns") so the user knows recovery is already
                // underway — the manual "Reconnect" button stays as an override.
                if (activeSession?.status == SessionStatus.DISCONNECTED || activeSession?.status == SessionStatus.ERROR) {
                    val reconnectLabel = rememberReconnectLabel(activeSession?.let { reconnectStatus[it.id] })
                    Surface(color = c.tintRed) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(reconnectLabel ?: "Disconnected", style = CRType.bodyDim, color = c.disconnected)
                            if (onReconnect != null && activeSession != null) {
                                TextButton(onClick = { onReconnect(activeSession.id) }) {
                                    Text("Reconnect", color = c.accent)
                                }
                            }
                        }
                    }
                }

                // Login / switch-account card. Claude `/login` prints an OAuth URL
                // hard-wrapped across terminal rows plus a "Paste code here" prompt;
                // copying the wrapped URL from the raw terminal breaks it. This card
                // surfaces the de-wrapped URL (open in browser / copy) and pastes the
                // resulting code back into the session.
                if (loginFlow != null && loginFlow.sessionId == activeSession?.id) {
                    val clipboard = LocalClipboardManager.current
                    var loginCode by remember(loginFlow.sessionId) { mutableStateOf("") }
                    Surface(color = c.surface) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Přihlásit / přepnout účet", style = CRType.cardTitle, color = c.text)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onOpenLoginUrl(loginFlow.url) }) {
                                    Text("Otevřít přihlášení", color = c.accent)
                                }
                                TextButton(onClick = { clipboard.setText(AnnotatedString(loginFlow.url)) }) {
                                    Text("Kopírovat URL", color = c.accent)
                                }
                            }
                            OutlinedTextField(
                                value = loginCode,
                                onValueChange = { loginCode = it },
                                label = { Text("Kód") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    clipboard.getText()?.text?.let { loginCode = it }
                                }) { Text("Vložit ze schránky", color = c.accent) }
                                TextButton(onClick = {
                                    onSubmitLoginCode(loginCode)
                                    loginCode = ""
                                }) { Text("Odeslat", color = c.accent) }
                                TextButton(onClick = { onCancelLogin() }) {
                                    Text("Zrušit", color = c.textDim)
                                }
                            }
                            Text(
                                "URL zkopíruj/otevři, přihlas se, kód vlož sem.",
                                style = CRType.bodyDim,
                                color = c.textDim,
                            )
                        }
                    }
                }

                // Rename dialog
                if (activeSession != null) {
                    com.clauderemote.ui.components.FloatingDialog(
                        visible = showRenameDialog,
                        onDismiss = { showRenameDialog = false },
                        theme = crThemeSnapshot,
                        title = { Text("Rename session", color = c.text) },
                        text = {
                            OutlinedTextField(
                                value = renameText,
                                onValueChange = { renameText = it },
                                label = { Text("Alias") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showRenameDialog = false
                                onRenameSession?.invoke(activeSession.id, renameText.trim())
                            }) { Text("Save", color = c.accent) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRenameDialog = false }) { Text("Cancel", color = c.textDim) }
                        }
                    )
                }

                // Command palette
                if (showPalette) {
                    val paletteActions = remember(tabs, activeTabId, commands) {
                        buildPaletteActions(
                            tabs = tabs,
                            activeTabId = activeTabId,
                            slashCommands = commands,
                            onSendCommand = onSendCommand,
                            onTabSwitch = onTabSwitch,
                            onTabClose = onTabClose,
                            onNewTab = onNewTab,
                            onReconnect = onReconnect,
                            onSwitchModel = onSwitchModel,
                            onSendEscape = onSendEscape,
                            onNavigate = { target -> onNavigate?.invoke(target) }
                        )
                    }
                    CommandPaletteDialog(
                        actions = paletteActions,
                        onDismiss = { showPalette = false }
                    )
                }

                // CLAUDE.md editor dialog
                if (showClaudeMd) {
                    var editMode by remember { mutableStateOf(false) }
                    var editText by remember(claudeMdContent) { mutableStateOf(claudeMdContent) }
                    var saving by remember { mutableStateOf(false) }
                    com.clauderemote.ui.components.FloatingDialog(
                        visible = true,
                        onDismiss = { showClaudeMd = false; editMode = false },
                        theme = crThemeSnapshot,
                        confirmButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (editMode) {
                                    TextButton(
                                        onClick = {
                                            saving = true
                                            scope.launch {
                                                onSaveClaudeMd?.invoke(editText)
                                                saving = false
                                                editMode = false
                                                claudeMdContent = editText
                                            }
                                        },
                                        enabled = !saving
                                    ) { Text(if (saving) "Saving..." else "Save", color = c.accent) }
                                    TextButton(onClick = { editMode = false; editText = claudeMdContent }) {
                                        Text("Cancel", color = c.textDim)
                                    }
                                } else {
                                    if (claudeMdContent.isNotBlank() && claudeMdContent != "(no CLAUDE.md found)" && claudeMdContent != "(no connection)") {
                                        TextButton(onClick = { editMode = true }) { Text("Edit", color = c.accent) }
                                    }
                                    TextButton(onClick = { showClaudeMd = false }) { Text("Close", color = c.textDim) }
                                }
                            }
                        },
                        title = { Text(if (editMode) "Edit CLAUDE.md" else "CLAUDE.md", color = c.text) },
                        text = {
                            if (editMode) {
                                OutlinedTextField(
                                    value = editText,
                                    onValueChange = { editText = it },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    maxLines = Int.MAX_VALUE
                                )
                            } else {
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(
                                        text = claudeMdContent.ifBlank { "(not found)" },
                                        modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = c.text
                                    )
                                }
                            }
                        }
                    )
                }

                // #70: thin banner shown only while the auto-switch is active.
                if (promptAutoSwitch) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(c.approval.copy(alpha = 0.18f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Claude is asking — answer here",
                            style = CRType.bodyDim,
                            color = c.approval,
                        )
                        TextButton(onClick = { userDismissedPrompt = true }) {
                            Text("Back to chat", style = CRType.bodyDim, color = c.accent)
                        }
                    }
                }

                // ── Terminal body ─────────────────────────────────────────
                // #70: render off the EFFECTIVE view so the prompt auto-switch
                // can transiently show raw without touching the persisted setting.
                val isTranscript = effectiveTerminalView == CRTerminalView.Transcript
                val gridActive = gridLayout != com.clauderemote.model.GridLayout.ONE && wideMode
                if (gridActive) {
                    // Additive grid path. Only the cell at focusedPaneIndex uses the
                    // single shared raw terminal when the global toggle is Raw; every
                    // other cell shows its own live transcript (Chat). Focus is tracked
                    // by PANE INDEX — not session id — so exactly one cell ever hosts
                    // terminalContent regardless of how sessions are assigned.
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).background(c.bg)) {
                        @Composable
                        fun PaneCell(i: Int, modifier: Modifier) {
                            val sid = paneSessions.getOrNull(i)
                            // FIX 1: focused by INDEX, not by sid==activeTabId.
                            val focused = (i == focusedPaneIndex)
                            val cellMod = modifier
                                .background(c.bg)
                                .border(
                                    width = if (focused) 2.dp else 1.dp,
                                    color = if (focused) c.accent else c.border,
                                )
                            if (sid == null) {
                                // Empty pane → session picker.
                                var pick by remember { mutableStateOf(false) }
                                Box(
                                    modifier = cellMod.clickable {
                                        onFocusPane?.invoke(i, null)
                                        pick = true
                                    },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Tap to choose session", style = CRType.bodyDim, color = c.textDim)
                                    DropdownMenu(expanded = pick, onDismissRequest = { pick = false }) {
                                        tabs.forEach { t ->
                                            DropdownMenuItem(
                                                text = { Text(t.tabTitle.ifBlank { t.displayLabel }, color = c.text) },
                                                onClick = {
                                                    pick = false
                                                    onAssignPane?.invoke(i, t.id)
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                val tab = tabs.firstOrNull { it.id == sid }
                                Column(modifier = cellMod.clickable { onFocusPane?.invoke(i, sid) }) {
                                    // Thin per-cell label: activity dot + title + reassign button.
                                    // FIX 5: "▾" opens the picker so a filled pane can be reassigned.
                                    var reassignPick by remember { mutableStateOf(false) }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().background(c.surface)
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        val dot = activityDotColor(
                                            sessionActivities[sid],
                                            tab?.status ?: SessionStatus.ACTIVE
                                        )
                                        Box(modifier = Modifier.size(8.dp).background(dot, CircleShape))
                                        Text(
                                            tab?.tabTitle?.ifBlank { tab.displayLabel } ?: sid,
                                            style = CRType.monoTiny,
                                            color = if (focused) c.accent else c.textDim,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        // Reassign affordance — tap "▾" to swap the session in this pane.
                                        Box {
                                            Text(
                                                "▾",
                                                style = CRType.monoTiny,
                                                color = c.textDim,
                                                modifier = Modifier.clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                ) { reassignPick = true }
                                            )
                                            DropdownMenu(
                                                expanded = reassignPick,
                                                onDismissRequest = { reassignPick = false }
                                            ) {
                                                tabs.forEach { t ->
                                                    DropdownMenuItem(
                                                        text = { Text(t.tabTitle.ifBlank { t.displayLabel }, color = c.text) },
                                                        onClick = {
                                                            reassignPick = false
                                                            onAssignPane?.invoke(i, t.id)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                        // FIX B: badge on non-focused pane that is awaiting a choice.
                                        // Focused pane uses the full auto-switch; badge is for background panes.
                                        if (!focused && panePendingAsk.getOrElse(i) { false }) {
                                            Pill(
                                                text = "asks",
                                                background = c.approval.copy(alpha = 0.25f),
                                                foreground = c.approval,
                                            )
                                        }
                                        // Reconnect indicator: a pane whose session is silently
                                        // reconnecting must not look inertly frozen.
                                        rememberReconnectLabel(reconnectStatus[sid], compact = true)?.let { rl ->
                                            Pill(
                                                text = rl,
                                                background = c.tintRed,
                                                foreground = c.disconnected,
                                            )
                                        }
                                    }
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        // FIX 1: only the focused cell (by index) renders terminalContent.
                                        if (focused && effectiveTerminalView == CRTerminalView.Raw) {
                                            terminalContent(Modifier.fillMaxSize())
                                            JumpToLatestPill(
                                                visible = terminalScrolledUp && terminalPendingOutput,
                                                onClick = { onJumpToLatest?.invoke() },
                                                modifier = Modifier.align(Alignment.BottomCenter),
                                            )
                                        } else {
                                            TranscriptView(
                                                entries = paneTranscripts.getOrNull(i) ?: emptyList(),
                                                modifier = Modifier.fillMaxSize(),
                                                activity = sessionActivities[sid],
                                                hookActive = sid in hookActiveSessions,
                                                claudeSessionId = tab?.claudeSessionId,
                                                onFileLink = openFileLink,
                                                onVerifyPaths = onVerifyPaths,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (gridLayout == com.clauderemote.model.GridLayout.TWO) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                PaneCell(0, Modifier.weight(1f).fillMaxHeight())
                                VerticalDivider(modifier = Modifier.fillMaxHeight().width(1.dp), color = c.border)
                                PaneCell(1, Modifier.weight(1f).fillMaxHeight())
                            }
                        } else { // QUAD
                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    PaneCell(0, Modifier.weight(1f).fillMaxHeight())
                                    VerticalDivider(modifier = Modifier.fillMaxHeight().width(1.dp), color = c.border)
                                    PaneCell(1, Modifier.weight(1f).fillMaxHeight())
                                }
                                HorizontalDivider(modifier = Modifier.fillMaxWidth().height(1.dp), color = c.border)
                                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    PaneCell(2, Modifier.weight(1f).fillMaxHeight())
                                    VerticalDivider(modifier = Modifier.fillMaxHeight().width(1.dp), color = c.border)
                                    PaneCell(3, Modifier.weight(1f).fillMaxHeight())
                                }
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).background(c.bg)) {
                        // Compose the terminal when showing Raw, OR on Android single-pane
                        // in Chat (#75): the emulator must stay alive so screenReader can
                        // detect a pending prompt that is visible on-screen but not yet in
                        // the transcript JSONL. Desktop keeps the old swap (no SwingPanel in
                        // tree during Chat) because a Compose overlay cannot reliably occlude
                        // a heavyweight AWT component.
                        if (!isTranscript || composeTerminalUnderTranscript) {
                            terminalContent(Modifier.fillMaxSize())
                        }
                        if (isTranscript) {
                            // Chat overlay — opaque so the hidden terminal doesn't bleed
                            // through. We only need to swallow TAPS so they don't reach
                            // the focusable TerminalView underneath (keyboard / keystroke
                            // leak). The previous version consumed EVERY pointer change in
                            // a raw awaitPointerEvent loop, which also ate vertical DRAGS —
                            // so the child LazyColumn often couldn't scroll. detectTapGestures
                            // claims taps/long-press only and leaves drag deltas for the
                            // transcript list, fixing scroll-by-drag while still blocking
                            // tap-through.
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(c.bg)
                                    .pointerInput(Unit) {
                                        detectTapGestures(onLongPress = {}, onTap = {})
                                    }
                            ) {
                                TranscriptView(
                                    entries = transcriptEntries,
                                    modifier = Modifier.fillMaxSize(),
                                    contextPercent = contextPercent,
                                    sessionUsagePercent = sessionUsagePercent,
                                    weekUsagePercent = weekUsagePercent,
                                    sessionResetMin = sessionResetMin,
                                    weekResetMin = weekResetMin,
                                    latencyMs = latencyMs,
                                    remoteStatus = remoteStatus,
                                    activity = activeTabId?.let { sessionActivities[it] },
                                    hookActive = activeTabId?.let { it in hookActiveSessions } ?: false,
                                    claudeSessionId = activeClaudeSessionId,
                                    streamStatus = transcriptStatus,
                                    connectionLabel = connectionLabel,
                                    onFileLink = openFileLink,
                                    onVerifyPaths = onVerifyPaths,
                                )
                            }
                        } else {
                            JumpToLatestPill(
                                visible = terminalScrolledUp && terminalPendingOutput,
                                onClick = { onJumpToLatest?.invoke() },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }

                if (!compactMode) {
                    // Snippet bar
                    val snippets = activeSession?.server?.snippets ?: emptyList()
                    if (snippets.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(c.surface)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            snippets.forEach { snip ->
                                AssistChip(
                                    onClick = { onSendCommand(snip + "\r") },
                                    label = {
                                        Text(
                                            if (snip.length > 20) snip.take(18) + ".." else snip,
                                            style = CRType.pill
                                        )
                                    },
                                    modifier = Modifier.height(28.dp)
                                )
                            }
                        }
                    }

                    // Pending input indicator
                    if (pendingInputCount > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(c.tintYellow)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "$pendingInputCount message(s) queued — will send on reconnect",
                                style = CRType.bodyDim,
                                color = c.working
                            )
                            if (onClearPending != null) {
                                TextButton(onClick = onClearPending) {
                                    Text("Clear", style = CRType.bodyDim, color = c.accent)
                                }
                            }
                        }
                    }

                    // ── Status row ─────────────────────────────────────────
                    if (activeSession != null) {
                        StatusRow(
                            session = activeSession,
                            activity = sessionActivities[activeSession.id],
                        )
                    }

                    // ── Prompt input ────────────────────────────────────────
                    if (activeSession != null) {
                        PromptInputBar(
                            commands = commands,
                            onSend = { text ->
                                // Two send strategies depending on whether
                                // the input is a slash command:
                                //
                                // 1) Slash commands ("/clear", "/resume",
                                //    etc.) need claude TUI's keystroke
                                //    handler to open the command palette
                                //    on the first '/', then filter on
                                //    subsequent characters, then ENTER to
                                //    select. If we send the whole string
                                //    as a burst, claude detects it as a
                                //    paste and lands "/clear" in the
                                //    prompt as plain text instead — the
                                //    user then sees "//clear" rendered by
                                //    claude (its own '/' indicator + the
                                //    pasted '/clear') and the command is
                                //    never executed. Send char-by-char
                                //    with a small gap so each character
                                //    arrives as its own keystroke.
                                //
                                // 2) Regular text: body + Enter as TWO
                                //    writes with a short gap. Single
                                //    text+\r is misdetected as paste,
                                //    forcing the user to press Send
                                //    twice. Bracketed-paste markers were
                                //    the wrong fix: claude in the tmux
                                //    PTY never negotiated \e[?2004h and
                                //    rendered '[200~' literally.
                                scope.launch {
                                    if (text.startsWith("/") && !text.contains('\n') && text.length < 64) {
                                        for (ch in text) {
                                            onSendCommand(ch.toString())
                                            kotlinx.coroutines.delay(15)
                                        }
                                        kotlinx.coroutines.delay(60)
                                        onSendCommand("\r")
                                    } else {
                                        onSendCommand(text)
                                        kotlinx.coroutines.delay(40)
                                        onSendCommand("\r")
                                    }
                                }
                            },
                            onSendCommand = onSendCommand,
                            onAttachFile = onAttachFile,
                            inputFocusRequester = inputFocusRequester,
                            onExpand = { showExpanded = true },
                        )
                    }

                    // ── Special keys row (spec §6.3 CRITICAL) ──────────────
                    if (isMobile && activeSession != null) {
                        SpecialKeysRow(
                            onKey = { key ->
                                onSendCommand(String(key.bytes.map { it.toInt().toChar() }.toCharArray()))
                            },
                            onMore = {
                                showPalette = true
                                if (onFetchCommands != null) {
                                    scope.launch { commands = onFetchCommands.invoke() }
                                }
                            },
                        )
                    }

                    // ── Control bar ─────────────────────────────────────────
                    if (showControlBar && activeSession != null) {
                        CRControlBar(
                            session = activeSession,
                            activity = sessionActivities[activeSession.id],
                            onSendCommand = onSendCommand,
                            onSendEscape = onSendEscape,
                            onPageUp = onPageUp,
                            onPageDown = onPageDown,
                            onSwitchModel = onSwitchModel,
                            onSwitchEffort = onSwitchEffort,
                            // "/cmd" used to open a narrower slash-command-only
                            // picker (CommandPicker) — a strict subset of the
                            // fuzzy CommandPaletteDialog the "/" special key
                            // already opens (slash commands PLUS tab/model/
                            // navigation actions). Route both to the one
                            // command surface instead of maintaining two.
                            onOpenCommands = {
                                showPalette = true
                                if (onFetchCommands != null) {
                                    scope.launch { commands = onFetchCommands.invoke() }
                                }
                            }
                        )
                    }
                }

            } // end Column
        } // end Row

        // ── SessionDrawer overlay ──────────────────────────────────────────
        SessionDrawer(
            open = showSessionDrawer,
            sessions = tabs,
            activities = sessionActivities,
            activeId = activeTabId ?: "",
            remoteSessions = remoteSessions,
            onPick = { id ->
                onTabSwitch(id)
                showSessionDrawer = false
            },
            onAttachRemote = onAttachRemote?.let { handler ->
                { remote ->
                    handler(remote)
                    showSessionDrawer = false
                }
            },
            onNew = {
                onNewTab()
                showSessionDrawer = false
            },
            onClose = { showSessionDrawer = false },
            // Keep the drawer open behind the menu — the context sheet floats
            // over the session list instead of dismissing it.
            onLongPressSession = onSessionLongPress,
            onLogin = onLogin,
        )

        // ── ExpandedInput overlay ──────────────────────────────────────────
        if (showExpanded) {
            ExpandedInput(
                onSend = { text ->
                    // Match the PromptInputBar send strategy: char-by-char
                    // for slash commands, body + delayed \r otherwise.
                    // Bracketed-paste markers ([200~ … [201~) used to
                    // wrap the payload here but claude in the tmux PTY
                    // never negotiates \e[?2004h, so the markers ended
                    // up in the prompt verbatim.
                    scope.launch {
                        if (text.startsWith("/") && !text.contains('\n') && text.length < 64) {
                            for (ch in text) {
                                onSendCommand(ch.toString())
                                kotlinx.coroutines.delay(15)
                            }
                            kotlinx.coroutines.delay(60)
                            onSendCommand("\r")
                        } else {
                            onSendCommand(text)
                            kotlinx.coroutines.delay(40)
                            onSendCommand("\r")
                        }
                    }
                    showExpanded = false
                },
                onDismiss = { showExpanded = false },
            )
        }
        // Foreground-only wake word lives with the prompt input now (it starts
        // dictation into that field), so it's wired up inside PromptInputBar.
    } // end BoxWithConstraints
}

// ---------------------------------------------------------------------------
// JumpToLatestPill — overlay shown when scrolled up with new output pending.
// ---------------------------------------------------------------------------

