package com.clauderemote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalDensity
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
import com.clauderemote.voice.MicButton
import com.clauderemote.voice.VoiceModeScreen
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
    Slash( byteArrayOf('/'.code.toByte())),
    CtrlC( byteArrayOf(0x03)),
    CtrlD( byteArrayOf(0x04)),
}

// ---------------------------------------------------------------------------
// Private session list item (unchanged from original)
// ---------------------------------------------------------------------------

private data class SessionItem(
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
    onNewTab: () -> Unit,
    onMenuOpen: () -> Unit,
    onSendCommand: (String) -> Unit,
    onSwitchModel: (ClaudeModel) -> Unit,
    onSendEscape: () -> Unit,
    onReconnect: ((String) -> Unit)? = null,
    onRenameSession: ((sessionId: String, newAlias: String) -> Unit)? = null,
    onAttachFile: (suspend () -> String?)? = null,
    onFetchClaudeMd: (suspend () -> String)? = null,
    onSaveClaudeMd: (suspend (String) -> Unit)? = null,
    onFetchCommands: (suspend () -> List<SlashCommand>)? = null,
    onFontSizeChange: ((Int) -> Unit)? = null,
    onShowNativeMenu: (() -> Unit)? = null,
    onNativeRenameDialog: ((sessionId: String, currentAlias: String) -> Unit)? = null,
    onAttachRemote: ((com.clauderemote.model.RemoteSession) -> Unit)? = null,
    remoteSessions: List<com.clauderemote.model.RemoteSession> = emptyList(),
    contextPercent: Int? = null,
    sessionUsagePercent: Int? = null,
    weekUsagePercent: Int? = null,
    sessionResetMin: Int? = null,
    weekResetMin: Int? = null,
    sessionActivities: Map<String, com.clauderemote.model.SessionActivity> = emptyMap(),
    hookActiveSessions: Set<String> = emptySet(),
    latencyMs: Long? = null,
    pendingInputCount: Int = 0,
    onClearPending: (() -> Unit)? = null,
    onNavigate: ((String) -> Unit)? = null,
    onSplitView: ((secondSessionId: String?) -> Unit)? = null,
    invertColors: Boolean = false,
    onToggleInvertColors: (() -> Unit)? = null,
    onTerminalViewChange: ((CRTerminalView) -> Unit)? = null,
    terminalContent: @Composable (Modifier) -> Unit,
    splitTerminalContent: (@Composable (Modifier) -> Unit)? = null,
    transcriptEntries: List<TranscriptEntry> = emptyList(),
    remoteStatus: RemoteSessionStatus? = null,
    onTerminalContentVisible: (() -> Unit)? = null,
    activeClaudeSessionId: String? = null,
    sidePanelWidthDp: Int = 220,
    onSidePanelWidthChange: ((Int) -> Unit)? = null,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val terminalView = LocalCRTerminalView.current

    // State — preserved from original
    var showControlBar by remember { mutableStateOf(true) }
    var compactMode by remember { mutableStateOf(false) }
    var showCommandPicker by remember { mutableStateOf(false) }
    var currentFontSize by remember { mutableStateOf(14) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var moreMenu by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    LaunchedEffect(activeTabId) {
        if (activeTabId != null) {
            try { inputFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }
    var showClaudeMd by remember { mutableStateOf(false) }
    var claudeMdContent by remember { mutableStateOf("") }
    var commandFilter by remember { mutableStateOf("") }
    var commands by remember { mutableStateOf(CommandFetcher.getCachedOrFallback()) }
    val activeSession = tabs.find { it.id == activeTabId }
    val scope = rememberCoroutineScope()
    var showPalette by remember { mutableStateOf(false) }
    var splitActive by remember { mutableStateOf(false) }
    var showSessionDrawer by remember { mutableStateOf(false) }
    var showExpanded by remember { mutableStateOf(false) }
    var voiceModeActive by remember { mutableStateOf(false) }
    val latestAssistant: TranscriptEntry.AssistantText? = remember(transcriptEntries) {
        for (i in transcriptEntries.indices.reversed()) {
            val e = transcriptEntries[i]
            if (e is TranscriptEntry.AssistantText) return@remember e
        }
        null
    }

    // Replay terminal buffer when switching back from transcript
    LaunchedEffect(terminalView, activeTabId) {
        if (terminalView == CRTerminalView.Raw) onTerminalContentVisible?.invoke()
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
        val wideMode = maxWidth > 700.dp && hasMultiple

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
                    onNativeRenameDialog = onNativeRenameDialog,
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
                    onMoreMenu = {
                        if (onShowNativeMenu != null) onShowNativeMenu.invoke()
                        else moreMenu = true
                    },
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
                if (moreMenu) {
                    AlertDialog(
                        onDismissRequest = { moreMenu = false },
                        confirmButton = {},
                        containerColor = c.surface,
                        text = {
                            Column {
                                TextButton(onClick = {
                                    moreMenu = false
                                    showPalette = true
                                    if (onFetchCommands != null) {
                                        scope.launch { commands = onFetchCommands.invoke() }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Command Palette", color = c.text) }
                                if (onFetchClaudeMd != null) {
                                    TextButton(onClick = {
                                        moreMenu = false
                                        scope.launch { claudeMdContent = onFetchClaudeMd.invoke(); showClaudeMd = true }
                                    }, modifier = Modifier.fillMaxWidth()) { Text("View CLAUDE.md", color = c.text) }
                                }
                                TextButton(onClick = { moreMenu = false; onSendCommand("c") },
                                    modifier = Modifier.fillMaxWidth()) { Text("Reset terminal", color = c.text) }
                                if (splitTerminalContent != null && tabs.size > 1) {
                                    TextButton(onClick = {
                                        moreMenu = false
                                        splitActive = !splitActive
                                        if (splitActive) {
                                            val otherId = tabs.firstOrNull { it.id != activeTabId }?.id
                                            onSplitView?.invoke(otherId)
                                        } else {
                                            onSplitView?.invoke(null)
                                        }
                                    }, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (splitActive) "Close Split View" else "Split View", color = c.text)
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
                                    TextButton(onClick = { moreMenu = false; onTabClose(activeSession.id) },
                                        modifier = Modifier.fillMaxWidth()) {
                                        Text("Close session", color = c.disconnected)
                                    }
                                }
                            }
                        }
                    )
                }

                // Disconnected banner
                if (activeSession?.status == SessionStatus.DISCONNECTED || activeSession?.status == SessionStatus.ERROR) {
                    Surface(color = c.tintRed) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Disconnected", style = CRType.bodyDim, color = c.disconnected)
                            if (onReconnect != null && activeSession != null) {
                                TextButton(onClick = { onReconnect(activeSession.id) }) {
                                    Text("Reconnect", color = c.accent)
                                }
                            }
                        }
                    }
                }

                // Rename dialog
                if (showRenameDialog && activeSession != null) {
                    AlertDialog(
                        onDismissRequest = { showRenameDialog = false },
                        containerColor = c.surface,
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

                if (showCommandPicker) {
                    AlertDialog(
                        onDismissRequest = {
                            showCommandPicker = false
                            commandFilter = ""
                            try { inputFocusRequester.requestFocus() } catch (_: Exception) {}
                        },
                        containerColor = c.surface,
                        confirmButton = {},
                        text = {
                            CommandPicker(
                                commands = commands,
                                filter = commandFilter,
                                onFilterChange = { commandFilter = it },
                                onSelect = { cmd ->
                                    showCommandPicker = false
                                    commandFilter = ""
                                    onSendCommand(cmd.command + "\r")
                                },
                                onDismiss = {
                                    showCommandPicker = false
                                    commandFilter = ""
                                    try { inputFocusRequester.requestFocus() } catch (_: Exception) {}
                                }
                            )
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
                    AlertDialog(
                        onDismissRequest = { showClaudeMd = false; editMode = false },
                        containerColor = c.surface,
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

                // ── Terminal body ─────────────────────────────────────────
                val isTranscript = terminalView == CRTerminalView.Transcript
                if (isTranscript) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).background(c.bg)) {
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
                            claudeSessionId = activeClaudeSessionId
                        )
                    }
                } else if (splitActive && splitTerminalContent != null && wideMode) {
                    Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(c.bg)) {
                            terminalContent(Modifier.fillMaxSize())
                        }
                        VerticalDivider(modifier = Modifier.fillMaxHeight().width(1.dp), color = c.border)
                        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(c.bg)) {
                            splitTerminalContent(Modifier.fillMaxSize())
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).background(c.bg)) {
                        terminalContent(Modifier.fillMaxSize())
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
                            onEnterVoiceMode = { voiceModeActive = true },
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
                            onSendCommand = onSendCommand,
                            onSendEscape = onSendEscape,
                            onSwitchModel = onSwitchModel,
                            onOpenCommands = {
                                showCommandPicker = true
                                commandFilter = ""
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
        // Voice-mode overlay sits on top of all the regular UI. Rendered as
        // the last BoxWithConstraints child so it stacks above everything;
        // VoiceModeScreen fills the screen with its own opaque surface.
        if (voiceModeActive) {
            VoiceModeScreen(
                onSend = { text ->
                    if (text.isBlank()) return@VoiceModeScreen
                    scope.launch {
                        onSendCommand(text)
                        kotlinx.coroutines.delay(40)
                        onSendCommand("\r")
                    }
                },
                latestAssistantId = latestAssistant?.id,
                latestAssistantText = latestAssistant?.text,
                onClose = { voiceModeActive = false },
            )
        }
    } // end BoxWithConstraints
}

// ---------------------------------------------------------------------------
// CRTopBar
// ---------------------------------------------------------------------------

@Composable
private fun CRTopBar(
    activeSession: ClaudeSession?,
    sessionActivities: Map<String, com.clauderemote.model.SessionActivity>,
    hasMultiple: Boolean,
    wideMode: Boolean,
    tabs: List<ClaudeSession>,
    allSessions: Map<String, List<SessionItem>>,
    activeTabId: String?,
    invertColors: Boolean,
    terminalView: CRTerminalView,
    latencyMs: Long?,
    contextPercent: Int?,
    sessionUsagePercent: Int?,
    weekUsagePercent: Int?,
    compactMode: Boolean,
    showControlBar: Boolean,
    onMenuOpen: () -> Unit,
    onTabSwitch: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onAttachRemote: ((com.clauderemote.model.RemoteSession) -> Unit)?,
    onToggleInvertColors: (() -> Unit)?,
    onTerminalViewChange: ((CRTerminalView) -> Unit)?,
    onToggleCompact: () -> Unit,
    onToggleControlBar: () -> Unit,
    onMoreMenu: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics

    Surface(
        color = c.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().height(m.rowHeight)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!wideMode) {
                IconButton(onClick = onMenuOpen, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Menu, "Sessions", tint = c.textDim, modifier = Modifier.size(20.dp))
                }
            }

            // Session title — tapping opens the slide-in SessionDrawer
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { if (hasMultiple && !wideMode) onOpenDrawer() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (activeSession != null) {
                        val activity = sessionActivities[activeSession.id]
                        val dotColor = activityDotColor(activity, activeSession.status)
                        Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
                    }
                    Text(
                        activeSession?.tabTitle ?: "",
                        style = CRType.cardTitle,
                        color = c.text,
                        maxLines = 1,
                    )
                    if (hasMultiple && !wideMode) {
                        Text("(${tabs.size})", style = CRType.monoTiny, color = c.textDim)
                        Text("▾", style = CRType.bodyDim, color = c.textDim)
                    }
                }
            }

            // Latency
            if (latencyMs != null) {
                val latColor = when {
                    latencyMs < 100 -> c.ready
                    latencyMs < 300 -> c.working
                    else -> c.disconnected
                }
                Text("${latencyMs}ms", style = CRType.monoTiny, color = latColor,
                    modifier = Modifier.padding(horizontal = 4.dp))
            }

            // Usage mini bars
            if (contextPercent != null || sessionUsagePercent != null || weekUsagePercent != null) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                    if (contextPercent != null) MiniBar("Ctx", contextPercent)
                    if (sessionUsagePercent != null) MiniBar("5h", sessionUsagePercent)
                    if (weekUsagePercent != null) MiniBar("Wk", weekUsagePercent)
                }
            }

            // Terminal view toggle (Raw / Transcript)
            if (onTerminalViewChange != null) {
                com.clauderemote.ui.components.Segmented(
                    options = CRTerminalView.entries.toList(),
                    selected = terminalView,
                    onSelect = onTerminalViewChange,
                    modifier = Modifier.padding(horizontal = 2.dp),
                    label = { if (it == CRTerminalView.Raw) "Raw" else "Chat" },
                )
            }

            // Compact toggle
            TextButton(onClick = onToggleCompact) {
                Text(if (compactMode) "Full" else "Min", style = CRType.bodyDim, color = c.textDim)
            }
            if (!compactMode) {
                TextButton(onClick = onToggleControlBar) {
                    Text(if (showControlBar) "Hide" else "Ctrl", style = CRType.bodyDim, color = c.textDim)
                }
            }

            // Invert colors (sunlight-readable)
            if (onToggleInvertColors != null) {
                IconButton(onClick = onToggleInvertColors, modifier = Modifier.size(36.dp)) {
                    Text(
                        if (invertColors) "☾" else "☀",
                        style = MaterialTheme.typography.titleMedium,
                        color = c.textDim
                    )
                }
            }

            // More menu
            IconButton(onClick = onMoreMenu, modifier = Modifier.size(36.dp)) {
                Text("⋮", style = MaterialTheme.typography.titleMedium, color = c.textDim)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// CrumbBar (spec §6.3)
// ---------------------------------------------------------------------------

@Composable
private fun CrumbBar(
    session: ClaudeSession,
    allSessions: List<SessionItem>,
    index: Int,
    total: Int,
    onOpenDrawer: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(c.bg)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Sessions button
        Row(
            modifier = Modifier
                .background(c.surface, RoundedCornerShape(6.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onOpenDrawer() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Default.Menu, null, tint = c.textDim, modifier = Modifier.size(12.dp))
            Text("Sessions", style = CRType.monoTiny, color = c.textDim)
        }

        // Server : folder · alias
        val folderName = session.folder.trimEnd('/').substringAfterLast('/').ifBlank { session.folder }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(session.server.name, style = CRType.mono, color = c.textDim, maxLines = 1)
            Text(":", style = CRType.mono, color = c.border)
            Text(folderName, style = CRType.mono, color = c.text, maxLines = 1)
            if (session.alias.isNotBlank()) {
                Text("·", style = CRType.mono, color = c.border)
                Text(session.alias, style = CRType.mono, color = c.accent, maxLines = 1)
            }
        }

        // Prev / counter / next
        IconButton(
            onClick = onPrev,
            enabled = index > 0,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowLeft, null, tint = if (index > 0) c.textDim else c.border,
                modifier = Modifier.size(14.dp))
        }
        Text("${index + 1}/$total", style = CRType.monoTiny, color = c.textDim)
        IconButton(
            onClick = onNext,
            enabled = index < total - 1,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowRight, null, tint = if (index < total - 1) c.textDim else c.border,
                modifier = Modifier.size(14.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// StatusRow — activity + last line + cost
// ---------------------------------------------------------------------------

@Composable
private fun StatusRow(
    session: ClaudeSession,
    activity: SessionActivity?,
) {
    val c = CRTheme.colors
    val crStatus = activity.toCRStatus()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusIndicator(status = crStatus)
        Spacer(Modifier.weight(1f))
    }
}

private fun SessionActivity?.toCRStatus(): CRStatus = when (this) {
    SessionActivity.WORKING -> CRStatus.Working
    SessionActivity.WAITING_FOR_INPUT -> CRStatus.Ready
    SessionActivity.APPROVAL_NEEDED -> CRStatus.Approval
    SessionActivity.IDLE -> CRStatus.Idle
    SessionActivity.DISCONNECTED -> CRStatus.Disconnected
    null -> CRStatus.Idle
}

// ---------------------------------------------------------------------------
// SpecialKeysRow (spec §6.3 CRITICAL)
// ---------------------------------------------------------------------------

@Composable
private fun SpecialKeysRow(
    onKey: (SpecialKey) -> Unit,
    onMore: () -> Unit,
) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SpecialKeyBtn("Esc",  Modifier.weight(1f)) { onKey(SpecialKey.Esc)   }
        SpecialKeyBtn("Tab",  Modifier.weight(1f)) { onKey(SpecialKey.Tab)   }
        SpecialKeyBtn("↑",    Modifier.weight(1f)) { onKey(SpecialKey.Up)    }
        SpecialKeyBtn("↓",    Modifier.weight(1f)) { onKey(SpecialKey.Down)  }
        SpecialKeyBtn("/",    Modifier.weight(1f)) { onKey(SpecialKey.Slash) }
        SpecialKeyBtn("⌃C",   Modifier.weight(1f)) { onKey(SpecialKey.CtrlC) }
        SpecialKeyBtn("⌃D",   Modifier.weight(1f)) { onKey(SpecialKey.CtrlD) }
        SpecialKeyBtn("···",  Modifier.weight(1f)) { onMore()                }
    }
}

@Composable
private fun SpecialKeyBtn(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = CRTheme.colors
    val haptic = androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current

    var pressed by remember { mutableStateOf(false) }
    val bgColor by animateColorAsState(
        targetValue = if (pressed) c.accent.copy(alpha = 0.25f) else c.surface2,
        animationSpec = tween(if (pressed) 0 else 100),
        label = "keyBg"
    )

    Surface(
        modifier = modifier
            .height(32.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                hapticFeedback.performHapticFeedback(haptic)
                pressed = true
                onClick()
            },
        color = bgColor,
        shape = RoundedCornerShape(6.dp),
        tonalElevation = 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = CRType.keyboardKey, color = c.text)
        }
    }

    // Reset pressed state after flash
    LaunchedEffect(pressed) {
        if (pressed) {
            kotlinx.coroutines.delay(100)
            pressed = false
        }
    }
}

// ---------------------------------------------------------------------------
// CRControlBar — mode/model chips + escape + slash commands
// ---------------------------------------------------------------------------

@Composable
private fun CRControlBar(
    session: ClaudeSession,
    onSendCommand: (String) -> Unit,
    onSendEscape: () -> Unit,
    onSwitchModel: (ClaudeModel) -> Unit,
    onOpenCommands: () -> Unit,
) {
    val c = CRTheme.colors

    var showModePop by remember { mutableStateOf(false) }
    var showModelPop by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Mode popup
        if (showModePop) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                color = c.surface,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("MODE", style = CRType.sectionH, color = c.textDim,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    ClaudeMode.entries.forEach { mode ->
                        val isActive = session.mode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isActive) c.tintAccent else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    showModePop = false
                                    // mode switching is handled by sending the shift-tab toggle command
                                    onSendCommand("[Z")
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(mode.displayName, style = CRType.bodyDim,
                                color = if (isActive) c.accent else c.text,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Model popup
        if (showModelPop) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                color = c.surface,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 8.dp,
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("MODEL", style = CRType.sectionH, color = c.textDim,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    ClaudeModel.entries.forEach { model ->
                        val isActive = session.model == model
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isActive) c.tintAccent else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    showModelPop = false
                                    onSwitchModel(model)
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(model.displayName, style = CRType.bodyDim,
                                color = if (isActive) c.accent else c.text,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Actual bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.surface)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Mode chip
            val modeColor = when (session.mode) {
                ClaudeMode.YOLO -> c.modeYolo
                ClaudeMode.PLAN -> c.modePlan
                ClaudeMode.AUTO_ACCEPT -> c.modeAuto
                ClaudeMode.NORMAL -> c.modeNormal
            }
            val modeShort = when (session.mode) {
                ClaudeMode.YOLO -> "YOLO"
                ClaudeMode.PLAN -> "PLAN"
                ClaudeMode.AUTO_ACCEPT -> "AUTO"
                ClaudeMode.NORMAL -> "NORM"
            }
            Surface(
                onClick = { showModePop = !showModePop; showModelPop = false },
                color = modeColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("mode", style = CRType.monoTiny, color = c.textDim)
                    Text(modeShort, style = CRType.pill, color = modeColor)
                }
            }

            // Model chip
            Surface(
                onClick = { showModelPop = !showModelPop; showModePop = false },
                color = c.tintAccent,
                shape = RoundedCornerShape(6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("model", style = CRType.monoTiny, color = c.textDim)
                    Text(session.model.displayName.uppercase(), style = CRType.pill, color = c.accent)
                }
            }

            Spacer(Modifier.weight(1f))

            // /cmd
            Surface(
                onClick = onOpenCommands,
                color = c.surface2,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text("/cmd", style = CRType.pill, color = c.textDim,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }

            // Esc
            CtrlButton("Esc") { onSendEscape() }
            // C-c
            CtrlButton("C-c") { onSendCommand("") }
            // y / n
            CtrlButton("y") { onSendCommand("y\r") }
            CtrlButton("n") { onSendCommand("n\r") }
        }
    }
}

// ---------------------------------------------------------------------------
// SESSION SIDE PANEL — redesigned (CRCard + group-by-server)
// ---------------------------------------------------------------------------

@Composable
private fun SessionSidePanel(
    allSessions: Map<String, List<SessionItem>>,
    activeTabId: String?,
    sessionActivities: Map<String, com.clauderemote.model.SessionActivity> = emptyMap(),
    onTabSwitch: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onMenuOpen: () -> Unit,
    onAttachRemote: ((com.clauderemote.model.RemoteSession) -> Unit)?,
    onRenameSession: ((sessionId: String, newAlias: String) -> Unit)? = null,
    onNativeRenameDialog: ((sessionId: String, currentAlias: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val dense = m.sessionCardOneLine
    var renamingItem by remember { mutableStateOf<SessionItem?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Rename dialog (compose-only path)
    if (onNativeRenameDialog == null) {
        renamingItem?.let { item ->
            AlertDialog(
                onDismissRequest = { renamingItem = null },
                containerColor = c.surface,
                title = { Text("Rename session", color = c.text) },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("Alias") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        item.tab?.let { onRenameSession?.invoke(it.id, renameText.trim()) }
                        renamingItem = null
                    }) { Text("OK", color = c.accent) }
                },
                dismissButton = {
                    TextButton(onClick = { renamingItem = null }) { Text("Cancel", color = c.textDim) }
                }
            )
        }
    }

    // Re-group flat items by server id/name
    val allFlat: List<SessionItem> = remember(allSessions) { allSessions.values.flatten() }
    val byServer: Map<String, List<SessionItem>> = remember(allFlat) {
        allFlat.groupBy { item ->
            item.tab?.server?.id ?: item.remote?.server?.id ?: "unknown"
        }
    }

    val panelBrush = if (CRTheme.variant == com.clauderemote.ui.theme.CRVariant.Glass) {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(
                c.surface.copy(alpha = 0.92f),
                c.bg.copy(alpha = 0.92f),
            ),
        )
    } else {
        androidx.compose.ui.graphics.SolidColor(c.bg)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(panelBrush)
    ) {
        // ── Panel header ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onMenuOpen, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Menu, "Menu", tint = c.textDim, modifier = Modifier.size(18.dp))
            }
            Text(
                "Sessions",
                style = if (isMobile) CRType.cardTitle else CRType.cardTitle.copy(fontSize = 16.sp),
                color = c.text,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNewTab, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, "New", tint = c.accent, modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = c.border, thickness = 1.dp)

        // ── Session list grouped by server ──────────────────────────────────
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            val sortedServers = byServer.entries.sortedBy { (_, items) ->
                (items.first().tab?.server?.name ?: items.first().remote?.server?.name ?: "").lowercase()
            }
            sortedServers.forEach { (_, items) ->
                val server = items.first().tab?.server ?: items.first().remote?.server
                // Sort strictly by (folder leaf, alias) — NOT by
                // item.label, because label collapses to the alias when
                // one exists, which clusters every session named e.g.
                // "second" together regardless of which folder they
                // belong to. The user wants folder first, alias as
                // tiebreaker, connectedness ignored.
                val sortedItems = items.sortedWith(
                    compareBy(
                        { item ->
                            val tab = item.tab
                            val folder = if (tab != null) {
                                tab.folder
                            } else {
                                val r = item.remote
                                if (r != null) com.clauderemote.model.TmuxNameParser
                                    .parse(r.tmuxSession.name, r.server.name).folder
                                else item.folder
                            }
                            folder.trimEnd('/').substringAfterLast('/').lowercase()
                        },
                        { item ->
                            val tab = item.tab
                            if (tab != null) {
                                tab.alias.lowercase()
                            } else {
                                val r = item.remote
                                if (r != null) com.clauderemote.model.TmuxNameParser
                                    .parse(r.tmuxSession.name, r.server.name).alias.lowercase()
                                else ""
                            }
                        },
                    )
                )
                if (server != null) {
                    item(key = "server_${server.id}") {
                        SidePanelGroupLabel(
                            serverName = server.name,
                            count = items.size,
                        )
                    }
                }
                items(sortedItems, key = { it.id }) { item ->
                    SidePanelSessionRow(
                        item = item,
                        isActive = item.tab?.id == activeTabId,
                        activity = sessionActivities[item.id],
                        dense = dense,
                        onTabSwitch = onTabSwitch,
                        onTabClose = onTabClose,
                        onAttachRemote = onAttachRemote,
                        onRename = if (onRenameSession != null) { label ->
                            if (onNativeRenameDialog != null && item.tab != null) {
                                onNativeRenameDialog.invoke(item.tab.id, label)
                            } else {
                                renameText = label
                                renamingItem = item
                            }
                        } else null,
                    )
                }
            }

            // Footer: new session button
            item(key = "footer") {
                HorizontalDivider(color = c.border, thickness = 1.dp)
                val shape = RoundedCornerShape(8.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .clip(shape)
                        .border(1.dp, c.border, shape)
                        .clickable(onClick = onNewTab)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.Add, null, tint = c.accent, modifier = Modifier.size(14.dp))
                    Text("New session", style = CRType.bodyDim, color = c.accent)
                }
            }
        }
    }
}

// ── Group label (mirrors DrawerGroupLabel) ────────────────────────────────────

@Composable
private fun SidePanelGroupLabel(serverName: String, count: Int) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ServerGlyph(name = serverName, modifier = Modifier.size(14.dp))
        Text(
            serverName,
            style = if (isMobile) CRType.sectionH else CRType.sectionH.copy(fontSize = 13.sp),
            color = c.textDim,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Pill(
            text = "$count",
            background = c.surface2,
            foreground = c.textDim,
        )
    }
}

// ── Per-session row ───────────────────────────────────────────────────────────

@Composable
private fun SidePanelSessionRow(
    item: SessionItem,
    isActive: Boolean,
    activity: com.clauderemote.model.SessionActivity?,
    dense: Boolean,
    onTabSwitch: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onAttachRemote: ((com.clauderemote.model.RemoteSession) -> Unit)?,
    onRename: ((String) -> Unit)?,
) {
    val c = CRTheme.colors
    val crStatus = activity.sidePanelToCRStatus(item.isConnected)
    val mode = item.tab?.mode

    // Folder basename · alias label
    val folderBase = item.folder.trimEnd('/').substringAfterLast('/').ifBlank { item.folder }
    val rowLabel = buildString {
        append(folderBase)
        val alias = item.tab?.alias?.ifBlank { null }
        if (alias != null) append(" · $alias")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) c.tintAccent else Color.Transparent)
            .clickable {
                if (item.isConnected && item.tab != null) onTabSwitch(item.tab.id)
                else if (item.remote != null) onAttachRemote?.invoke(item.remote)
            }
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 3 dp accent bar for active row
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (isActive) c.accent else Color.Transparent),
        )

        if (dense) {
            // ── Dense: single line ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusIndicator(
                    status = crStatus,
                    modifier = Modifier.size(8.dp),
                    viz = com.clauderemote.ui.theme.CRStatusViz.Dot,
                )
                Text(
                    rowLabel,
                    style = if (isMobile) CRType.bodyDim else CRType.bodyDim.copy(fontSize = 14.sp),
                    color = if (isActive) c.text else c.textDim,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isActive && item.isConnected) {
                    IconButton(
                        onClick = { item.tab?.let { onTabClose(it.id) } },
                        modifier = Modifier.size(20.dp),
                    ) { Icon(Icons.Default.Close, "Close", tint = c.textDim, modifier = Modifier.size(12.dp)) }
                }
            }
        } else {
            // ── Regular / Compact: two-line with ServerGlyph ────────────────
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ServerGlyph block
                val serverName = item.tab?.server?.name ?: item.remote?.server?.name ?: "?"
                ServerGlyph(name = serverName, modifier = Modifier.size(28.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        rowLabel,
                        style = if (isMobile) CRType.cardTitle else CRType.cardTitle.copy(fontSize = 16.sp),
                        color = if (isActive) c.text else c.textDim,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        StatusIndicator(
                            status = crStatus,
                            modifier = Modifier.size(8.dp),
                            viz = com.clauderemote.ui.theme.CRStatusViz.Dot,
                        )
                        if (mode != null) {
                            SidePanelModePill(mode = mode)
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (isActive && onRename != null && item.isConnected) {
                        IconButton(
                            onClick = { onRename(item.label) },
                            modifier = Modifier.size(18.dp),
                        ) { Text("✎", style = CRType.monoTiny, color = c.textDim) }
                    }
                    if (isActive && item.isConnected) {
                        IconButton(
                            onClick = { item.tab?.let { onTabClose(it.id) } },
                            modifier = Modifier.size(18.dp),
                        ) { Icon(Icons.Default.Close, "Close", tint = c.textDim, modifier = Modifier.size(12.dp)) }
                    }
                }
            }
        }
    }
}

// ── Mode pill for side panel ──────────────────────────────────────────────────

@Composable
private fun SidePanelModePill(mode: ClaudeMode) {
    val c = CRTheme.colors
    val (bg, fg, label) = when (mode) {
        ClaudeMode.YOLO        -> Triple(c.tintRed,    c.modeYolo,   "YOLO")
        ClaudeMode.PLAN        -> Triple(c.tintPurple, c.modePlan,   "PLAN")
        ClaudeMode.AUTO_ACCEPT -> Triple(c.tintGreen,  c.modeAuto,   "AUTO")
        ClaudeMode.NORMAL      -> Triple(c.surface2,   c.modeNormal, "NORM")
    }
    Pill(text = label, background = bg, foreground = fg)
}

// ── SessionActivity → CRStatus for side panel ────────────────────────────────

private fun com.clauderemote.model.SessionActivity?.sidePanelToCRStatus(isConnected: Boolean): CRStatus {
    if (!isConnected) return CRStatus.Disconnected
    return when (this) {
        com.clauderemote.model.SessionActivity.WORKING           -> CRStatus.Working
        com.clauderemote.model.SessionActivity.WAITING_FOR_INPUT -> CRStatus.Ready
        com.clauderemote.model.SessionActivity.APPROVAL_NEEDED   -> CRStatus.Approval
        com.clauderemote.model.SessionActivity.IDLE              -> CRStatus.Idle
        com.clauderemote.model.SessionActivity.DISCONNECTED      -> CRStatus.Disconnected
        null                                                     -> CRStatus.Idle
    }
}

// ---------------------------------------------------------------------------
// PROMPT INPUT BAR (logic preserved, chrome restyled)
// ---------------------------------------------------------------------------

private val PROMPT_TEMPLATES = listOf(
    "Fix bug in ",
    "Explain ",
    "Write tests for ",
    "Refactor ",
    "Add feature: ",
    "Review and improve ",
    "Create a ",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptInputBar(
    commands: List<SlashCommand>,
    onSend: (String) -> Unit,
    onSendCommand: (String) -> Unit,
    onAttachFile: (suspend () -> String?)? = null,
    inputFocusRequester: FocusRequester? = null,
    onExpand: (() -> Unit)? = null,
    onEnterVoiceMode: (() -> Unit)? = null,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics

    LaunchedEffect(Unit) {
        try { inputFocusRequester?.requestFocus() } catch (_: Exception) {}
    }

    var text by rememberSaveable { mutableStateOf("") }
    var attachedFilesRaw by rememberSaveable { mutableStateOf("") }
    val attachedFiles: List<String> = if (attachedFilesRaw.isBlank()) emptyList() else attachedFilesRaw.split('\n')
    var uploading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val promptScope = rememberCoroutineScope()

    var historyRaw by rememberSaveable { mutableStateOf("") }
    val history: List<String> = if (historyRaw.isBlank()) emptyList()
    else historyRaw.split(" ").filter { it.isNotBlank() }

    val suggestions = if (text.startsWith("/") && text.length > 1 && !text.contains("\n")) {
        commands.filter { it.command.contains(text.trim(), ignoreCase = true) }.take(5)
    } else emptyList()

    fun addToHistory(msg: String) {
        if (msg.isBlank()) return
        val updated = (listOf(msg) + history.filter { it != msg }).take(50)
        historyRaw = updated.joinToString(" ")
    }

    fun buildAndSend() {
        val userText = text.trim()
        if (attachedFiles.isEmpty() && userText.isNotBlank()) {
            addToHistory(userText)
            onSend(userText)
        } else if (attachedFiles.isNotEmpty()) {
            val fileRefs = attachedFiles.joinToString(" ") { "\"$it\"" }
            val prompt = if (userText.isNotBlank()) "Read $fileRefs — $userText" else "Read and analyze $fileRefs"
            addToHistory(prompt)
            onSend(prompt)
        } else {
            onSendCommand("\r")
            return
        }
        text = ""
        attachedFilesRaw = ""
        expanded = false
    }

    // Full-screen expanded editor
    if (expanded) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = c.surface,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { expanded = false }) { Text("Collapse", color = c.textDim) }
                        TextButton(onClick = { showTemplates = !showTemplates }) { Text("Templates", color = c.textDim) }
                        TextButton(onClick = { showHistory = !showHistory }) { Text("History", color = c.textDim) }
                    }
                    Text("${text.length} chars", style = CRType.monoTiny, color = c.textDim)
                }

                if (showTemplates) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        PROMPT_TEMPLATES.forEach { tmpl ->
                            AssistChip(
                                onClick = { text = tmpl; showTemplates = false },
                                label = { Text(tmpl.trimEnd(), style = CRType.pill) }
                            )
                        }
                    }
                }

                if (showHistory && history.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState())
                    ) {
                        history.take(10).forEach { item ->
                            Text(
                                text = if (item.length > 60) item.take(57) + "..." else item,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { text = item; showHistory = false }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                style = CRType.bodyDim, color = c.text
                            )
                            HorizontalDivider(color = c.border)
                        }
                    }
                }

                if (attachedFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        attachedFiles.forEachIndexed { idx, path ->
                            InputChip(
                                selected = true, onClick = {},
                                label = { Text(path.substringAfterLast('/'), style = CRType.pill) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, "Remove",
                                        modifier = Modifier.size(14.dp).clickable {
                                            attachedFilesRaw = attachedFiles.filterIndexed { i, _ -> i != idx }.joinToString("\n")
                                        })
                                },
                                modifier = Modifier.height(26.dp)
                            )
                        }
                    }
                }

                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 280.dp)
                        .padding(horizontal = 8.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                event.key == Key.Enter &&
                                (event.isCtrlPressed || event.isMetaPressed)) {
                                buildAndSend(); true
                            } else false
                        },
                    textStyle = CRType.bodyDim.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    decorationBox = { innerTextField ->
                        OutlinedTextFieldDefaults.DecorationBox(
                            value = text,
                            innerTextField = innerTextField,
                            enabled = true,
                            singleLine = false,
                            visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                            interactionSource = remember { MutableInteractionSource() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            placeholder = { Text("Type your message...\n\nEnter = new line\nSend button = submit", style = CRType.bodyDim, color = c.textDim) },
                            container = {
                                OutlinedTextFieldDefaults.ContainerBox(
                                    enabled = true, isError = false,
                                    interactionSource = remember { MutableInteractionSource() },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = c.surface,
                                        unfocusedContainerColor = c.surface
                                    )
                                )
                            }
                        )
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (onAttachFile != null) {
                            IconButton(
                                onClick = {
                                    if (!uploading) {
                                        uploading = true
                                        promptScope.launch {
                                            try {
                                                val path = onAttachFile.invoke()
                                                if (path != null) attachedFilesRaw = (attachedFiles + path.split('\n').filter { it.isNotEmpty() }).joinToString("\n")
                                            } finally { uploading = false }
                                        }
                                    }
                                },
                                modifier = Modifier.size(36.dp), enabled = !uploading
                            ) {
                                if (uploading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                                else Icon(Icons.Default.Add, "Attach", tint = c.textDim, modifier = Modifier.size(20.dp))
                            }
                        }
                        if (text.isNotBlank()) {
                            IconButton(onClick = { text = "" }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, "Clear", tint = c.textDim, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Button(
                        onClick = { buildAndSend() },
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentInk)
                    ) { Text("Send") }
                }
            }
        }
        return
    }

    // ── Compact mode (default) ───────────────────────────────────────────────
    Surface(color = c.surface, tonalElevation = 0.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Slash suggestions
            if (suggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    suggestions.forEach { cmd ->
                        AssistChip(
                            onClick = { onSendCommand(cmd.command + "\r"); text = "" },
                            label = { Text(cmd.command, style = CRType.mono) }
                        )
                    }
                }
            }

            // Compact input row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onAttachFile != null) {
                    IconButton(
                        onClick = {
                            if (!uploading) {
                                uploading = true
                                promptScope.launch {
                                    try {
                                        val path = onAttachFile.invoke()
                                        if (path != null) attachedFilesRaw = (attachedFiles + path).joinToString("\n")
                                    } finally { uploading = false }
                                }
                            }
                        },
                        modifier = Modifier.size(32.dp), enabled = !uploading
                    ) {
                        if (uploading) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
                        else Icon(Icons.Default.Add, "Attach", tint = c.textDim, modifier = Modifier.size(18.dp))
                    }
                }

                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        .then(if (inputFocusRequester != null) Modifier.focusRequester(inputFocusRequester) else Modifier)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                event.key == Key.Enter &&
                                (event.isCtrlPressed || event.isMetaPressed)) {
                                buildAndSend(); true
                            } else false
                        },
                    textStyle = CRType.bodyDim.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                    decorationBox = { innerTextField ->
                        OutlinedTextFieldDefaults.DecorationBox(
                            value = text,
                            innerTextField = innerTextField,
                            enabled = true,
                            singleLine = false,
                            visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                            interactionSource = remember { MutableInteractionSource() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            placeholder = { Text("Message or /command...", style = CRType.bodyDim, color = c.textDim) },
                            container = {
                                OutlinedTextFieldDefaults.ContainerBox(
                                    enabled = true, isError = false,
                                    interactionSource = remember { MutableInteractionSource() },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = c.surface2,
                                        unfocusedContainerColor = c.surface2
                                    )
                                )
                            }
                        )
                    }
                )

                // Dictation (cs-CZ STT) — no-op on platforms without speech support.
                MicButton(
                    currentText = text,
                    onTextChange = { text = it },
                    modifier = Modifier.size(32.dp),
                    tint = c.textDim,
                )

                // Voice mode (hands-free dialog) — no-op when not provided.
                if (onEnterVoiceMode != null) {
                    IconButton(
                        onClick = onEnterVoiceMode,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RecordVoiceOver,
                            contentDescription = "Voice mode",
                            tint = c.textDim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // Expand
                IconButton(onClick = { if (onExpand != null) onExpand() else expanded = true }, modifier = Modifier.size(32.dp)) {
                    Text("⤢", style = MaterialTheme.typography.titleMedium, color = c.textDim)
                }

                // History
                if (history.isNotEmpty()) {
                    IconButton(onClick = { showHistory = !showHistory }, modifier = Modifier.size(32.dp)) {
                        Text("↑", style = MaterialTheme.typography.titleMedium, color = c.textDim)
                    }
                }

                Button(
                    onClick = { buildAndSend() },
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentInk)
                ) { Text("Send") }
            }

            // History popup
            if (showHistory && history.isNotEmpty()) {
                Surface(
                    tonalElevation = 8.dp,
                    color = c.surface2,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                ) {
                    Column(modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                        history.take(8).forEach { item ->
                            Text(
                                text = if (item.length > 50) item.take(47) + "..." else item,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { text = item; showHistory = false }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                style = CRType.bodyDim, color = c.text
                            )
                            HorizontalDivider(color = c.border)
                        }
                    }
                }
            }

            // Attached files
            if (attachedFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    attachedFiles.forEachIndexed { idx, path ->
                        InputChip(
                            selected = true, onClick = {},
                            label = { Text(path.substringAfterLast('/'), style = CRType.pill) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, "Remove",
                                    modifier = Modifier.size(14.dp).clickable {
                                        attachedFilesRaw = attachedFiles.filterIndexed { i, _ -> i != idx }.joinToString("\n")
                                    })
                            },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// COMMAND PICKER (logic preserved)
// ---------------------------------------------------------------------------

@Composable
private fun CommandPicker(
    commands: List<SlashCommand>,
    filter: String,
    onFilterChange: (String) -> Unit,
    onSelect: (SlashCommand) -> Unit,
    onDismiss: () -> Unit
) {
    val c = CRTheme.colors
    val filtered = if (filter.isBlank()) commands
    else commands.filter {
        it.command.contains(filter, ignoreCase = true) ||
        it.description.contains(filter, ignoreCase = true)
    }
    var selectedIndex by remember { mutableStateOf(0) }
    LaunchedEffect(filter) { selectedIndex = 0 }

    Surface(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f).padding(8.dp),
        shape = MaterialTheme.shapes.medium,
        color = c.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            val filterFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { filterFocus.requestFocus() }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = onFilterChange,
                    placeholder = { Text("Filter commands...", color = c.textDim) },
                    modifier = Modifier.weight(1f)
                        .focusRequester(filterFocus)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionDown -> { selectedIndex = (selectedIndex + 1).coerceAtMost(filtered.size - 1); true }
                                    Key.DirectionUp   -> { selectedIndex = (selectedIndex - 1).coerceAtLeast(0); true }
                                    Key.Enter -> {
                                        if (filtered.isNotEmpty() && selectedIndex in filtered.indices) onSelect(filtered[selectedIndex])
                                        true
                                    }
                                    Key.Escape -> { onDismiss(); true }
                                    else -> false
                                }
                            } else false
                        },
                    singleLine = true,
                    textStyle = CRType.bodyDim.copy(color = c.text)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = c.textDim) }
            }

            val listState = rememberLazyListState()
            LaunchedEffect(selectedIndex) {
                if (selectedIndex in filtered.indices) listState.animateScrollToItem(selectedIndex)
            }

            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(filtered) { index, cmd ->
                    val isSelected = index == selectedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) c.tintAccent else Color.Transparent)
                            .clickable { onSelect(cmd) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(cmd.command, style = CRType.mono,
                            color = if (isSelected) c.accent else c.accent.copy(alpha = 0.85f))
                        Text(cmd.description, style = CRType.bodyDim, color = c.textDim)
                    }
                    HorizontalDivider(color = c.border)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Mini helpers
// ---------------------------------------------------------------------------

@Composable
private fun MiniBar(label: String, percent: Int) {
    val c = CRTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = CRType.monoTiny, color = c.textDim, modifier = Modifier.width(20.dp))
        Box(
            modifier = Modifier.width(40.dp).height(4.dp)
                .background(c.surface2, CircleShape)
        ) {
            val pct = percent.coerceIn(0, 100)
            val color = when {
                pct < 50 -> c.ready
                pct < 80 -> c.working
                else -> c.disconnected
            }
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(pct / 100f).background(color, CircleShape))
        }
        Text("${percent}%", style = CRType.monoTiny, color = c.textDim, modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
private fun CtrlButton(label: String, onClick: () -> Unit) {
    val c = CRTheme.colors
    val haptic = androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    FilledTonalButton(
        onClick = { hapticFeedback.performHapticFeedback(haptic); onClick() },
        modifier = Modifier.height(28.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = c.surface2)
    ) {
        Text(label, style = CRType.keyboardKey, color = c.text)
    }
}

/**
 * Map SessionActivity (or fallback to SessionStatus) to a dot color.
 */
private fun activityDotColor(
    activity: com.clauderemote.model.SessionActivity?,
    status: SessionStatus
): Color = when (activity) {
    SessionActivity.WAITING_FOR_INPUT -> Color(0xFF4ADE80)
    SessionActivity.WORKING           -> Color(0xFFFBBF24)
    SessionActivity.APPROVAL_NEEDED   -> Color(0xFFFB923C)
    SessionActivity.IDLE              -> Color(0xFF94A3B8)
    SessionActivity.DISCONNECTED      -> Color(0xFFF87171)
    null -> when (status) {
        SessionStatus.ACTIVE       -> Color(0xFF4ADE80)
        SessionStatus.CONNECTING   -> Color(0xFFFBBF24)
        SessionStatus.DISCONNECTED, SessionStatus.ERROR -> Color(0xFFF87171)
    }
}
