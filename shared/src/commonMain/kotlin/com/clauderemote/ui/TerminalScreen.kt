package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.SessionStatus
import com.clauderemote.session.CommandFetcher
import com.clauderemote.session.SlashCommand
import kotlinx.coroutines.launch

private data class SessionItem(
    val id: String, val label: String, val folder: String,
    val isConnected: Boolean,
    val status: SessionStatus?, val tab: ClaudeSession?,
    val remote: com.clauderemote.model.RemoteSession?
)

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
    onShowNativeMenu: (() -> Unit)? = null, // Desktop: show menu via Swing (bypasses SwingPanel z-order)
    onAttachRemote: ((com.clauderemote.model.RemoteSession) -> Unit)? = null,
    remoteSessions: List<com.clauderemote.model.RemoteSession> = emptyList(),
    contextPercent: Int? = null,
    sessionUsagePercent: Int? = null,
    weekUsagePercent: Int? = null,
    sessionActivities: Map<String, com.clauderemote.model.SessionActivity> = emptyMap(),
    latencyMs: Long? = null,
    pendingInputCount: Int = 0,
    onClearPending: (() -> Unit)? = null,
    onNavigate: ((String) -> Unit)? = null,
    onSplitView: ((secondSessionId: String?) -> Unit)? = null,
    invertColors: Boolean = false,
    onToggleInvertColors: (() -> Unit)? = null,
    terminalContent: @Composable (Modifier) -> Unit,
    splitTerminalContent: (@Composable (Modifier) -> Unit)? = null
) {
    var showControlBar by remember { mutableStateOf(true) }
    var compactMode by remember { mutableStateOf(false) }
    var showCommandPicker by remember { mutableStateOf(false) }
    var currentFontSize by remember { mutableStateOf(14) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var moreMenu by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    var showClaudeMd by remember { mutableStateOf(false) }
    var claudeMdContent by remember { mutableStateOf("") }
    var commandFilter by remember { mutableStateOf("") }
    var commands by remember { mutableStateOf(CommandFetcher.getCachedOrFallback()) }
    val activeSession = tabs.find { it.id == activeTabId }
    val scope = rememberCoroutineScope()
    var showPalette by remember { mutableStateOf(false) }
    var splitActive by remember { mutableStateOf(false) }

    // Unified session list: active tabs + remote (unconnected) sessions, grouped by folder
    val allSessions = remember(tabs, remoteSessions) {
        val connectedTmux = tabs.map { it.tmuxSessionName }.toSet()
        fun parseFolder(raw: String): String {
            var f = raw.trimEnd('/').substringAfterLast('/').ifBlank { raw }
            // Strip yolo/yolo2/etc. suffix for grouping
            f = f.replace(Regex("-yolo\\d*$"), "")
            return f.ifBlank { "~" }
        }
        val activeSessions = tabs.map { tab ->
            val parsed = com.clauderemote.model.TmuxNameParser.parse(tab.tmuxSessionName, tab.server.name)
            val alias = tab.alias.ifBlank { parsed.alias }
            val folder = parseFolder(tab.folder)
            val label = alias.ifBlank {
                val rawName = tab.folder.trimEnd('/').substringAfterLast('/').ifBlank { tab.folder }
                if (parsed.isYolo || tab.mode == com.clauderemote.model.ClaudeMode.YOLO) "$rawName \u26A1" else rawName
            }
            SessionItem(tab.id, label, folder, true, tab.status, tab, null)
        }
        val remoteItems = remoteSessions.filter { remote ->
            // Only filter by exact tmux name match
            remote.tmuxSession.name !in connectedTmux
        }.map { remote ->
            val parsed = com.clauderemote.model.TmuxNameParser.parse(remote.tmuxSession.name, remote.server.name)
            val folder = parseFolder(parsed.folder)
            val label = parsed.alias.ifBlank {
                if (parsed.isYolo) "${parsed.folder} \u26A1" else parsed.folder
            }
            SessionItem(remote.tmuxSession.name, label, folder, false, null, null, remote)
        }
        (activeSessions + remoteItems).groupBy { it.folder }.toSortedMap()
    }

    // Keyboard shortcut handler
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

        Row(modifier = Modifier.fillMaxSize()) {
            // Side panel on wide displays
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
                    modifier = Modifier.width(200.dp).fillMaxHeight()
                )
            }

    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
        // Top bar
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!wideMode) {
                    IconButton(onClick = onMenuOpen, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Menu, "Menu", modifier = Modifier.size(20.dp))
                    }
                }

                // Session dropdown (narrow mode only)
                var sessionDropdown by remember { mutableStateOf(false) }
                Box(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.clickable { if (hasMultiple && !wideMode) sessionDropdown = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (activeSession != null) {
                            val activity = sessionActivities[activeSession.id]
                            val dotColor = activityDotColor(activity, activeSession.status)
                            Box(modifier = Modifier.size(8.dp).background(dotColor, shape = CircleShape))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            activeSession?.tabTitle ?: "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (hasMultiple && !wideMode) {
                            Text(
                                " (${tabs.size})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(" \u25BE", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!wideMode) {
                    DropdownMenu(
                        expanded = sessionDropdown,
                        onDismissRequest = { sessionDropdown = false }
                    ) {
                        allSessions.forEach { (folder, items) ->
                            if (allSessions.size > 1) {
                                Text(
                                    folder,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                            items.forEach { item ->
                                val dotColor = when {
                                    !item.isConnected -> Color(0xFF666666)
                                    item.status == SessionStatus.ACTIVE -> Color(0xFF4CAF50)
                                    item.status == SessionStatus.CONNECTING -> Color(0xFFFF9800)
                                    else -> Color(0xFFF44336)
                                }
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(modifier = Modifier.size(8.dp).background(dotColor, shape = CircleShape))
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                item.label,
                                                style = if (item.tab?.id == activeTabId) MaterialTheme.typography.bodyMedium
                                                       else MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    },
                                    onClick = {
                                        sessionDropdown = false
                                        if (item.isConnected && item.tab != null) {
                                            onTabSwitch(item.tab.id)
                                        } else if (item.remote != null) {
                                            onAttachRemote?.invoke(item.remote)
                                        }
                                    },
                                    trailingIcon = if (item.isConnected) { {
                                        IconButton(
                                            onClick = { sessionDropdown = false; item.tab?.let { onTabClose(it.id) } },
                                            modifier = Modifier.size(24.dp)
                                        ) { Icon(Icons.Default.Close, "Close", modifier = Modifier.size(14.dp)) }
                                    } } else null
                                )
                            }
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Add, "New", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("New session")
                                }
                            },
                            onClick = { sessionDropdown = false; onNewTab() }
                        )
                    }
                    } // end !wideMode dropdown
                }
                // Latency indicator
                if (latencyMs != null) {
                    Spacer(Modifier.width(4.dp))
                    val latColor = when {
                        latencyMs < 100 -> Color(0xFF4CAF50)
                        latencyMs < 300 -> Color(0xFFFF9800)
                        else -> Color(0xFFF44336)
                    }
                    Text(
                        "${latencyMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = latColor
                    )
                }
                // Context + usage bars
                if (contextPercent != null || sessionUsagePercent != null || weekUsagePercent != null) {
                    Spacer(Modifier.width(4.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        if (contextPercent != null) {
                            MiniBar("Ctx", contextPercent)
                        }
                        if (sessionUsagePercent != null) {
                            MiniBar("5h", sessionUsagePercent)
                        }
                        if (weekUsagePercent != null) {
                            MiniBar("Wk", weekUsagePercent)
                        }
                    }
                }

                // Compact/Full toggle
                TextButton(onClick = { compactMode = !compactMode }) {
                    Text(if (compactMode) "Full" else "Min", style = MaterialTheme.typography.bodySmall)
                }
                if (!compactMode) {
                    TextButton(onClick = { showControlBar = !showControlBar }) {
                        Text(if (showControlBar) "Hide" else "Ctrl", style = MaterialTheme.typography.bodySmall)
                    }
                }
                // Sunlight-readable toggle (global color inversion)
                if (onToggleInvertColors != null) {
                    IconButton(
                        onClick = onToggleInvertColors,
                        modifier = Modifier.size(36.dp),
                    ) {
                        // ☀ U+2600 when off, ☾ U+263E when on — Unicode glyphs
                        // keep us icon-library-agnostic and render in a TextButton.
                        Text(
                            if (invertColors) "\u263E" else "\u2600",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                // More menu button
                IconButton(onClick = {
                    if (onShowNativeMenu != null) onShowNativeMenu.invoke()
                    else moreMenu = true
                }, modifier = Modifier.size(36.dp)) {
                    Text("\u22EE", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        if (moreMenu) {
            AlertDialog(
                onDismissRequest = { moreMenu = false },
                confirmButton = {},
                text = {
                    Column {
                        TextButton(onClick = {
                            moreMenu = false
                            showPalette = true
                            if (onFetchCommands != null) {
                                scope.launch { commands = onFetchCommands.invoke() }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Command Palette") }
                        if (onFetchClaudeMd != null) {
                            TextButton(onClick = {
                                moreMenu = false
                                scope.launch { claudeMdContent = onFetchClaudeMd.invoke(); showClaudeMd = true }
                            }, modifier = Modifier.fillMaxWidth()) { Text("View CLAUDE.md") }
                        }
                        TextButton(onClick = { moreMenu = false; onSendCommand("\u001Bc") },
                            modifier = Modifier.fillMaxWidth()) { Text("Reset terminal") }
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
                                Text(if (splitActive) "Close Split View" else "Split View")
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        // Font size
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text("Font: ", style = MaterialTheme.typography.bodyMedium)
                            FilledTonalButton(
                                onClick = { currentFontSize = (currentFontSize - 1).coerceIn(8, 32); onFontSizeChange?.invoke(currentFontSize) },
                                modifier = Modifier.size(32.dp), contentPadding = PaddingValues(0.dp)
                            ) { Text("A-") }
                            Spacer(Modifier.width(12.dp))
                            Text("$currentFontSize", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(12.dp))
                            FilledTonalButton(
                                onClick = { currentFontSize = (currentFontSize + 1).coerceIn(8, 32); onFontSizeChange?.invoke(currentFontSize) },
                                modifier = Modifier.size(32.dp), contentPadding = PaddingValues(0.dp)
                            ) { Text("A+") }
                        }
                        if (activeSession != null) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            TextButton(onClick = {
                                moreMenu = false
                                renameText = activeSession.alias.ifBlank { activeSession.displayLabel }
                                showRenameDialog = true
                            }, modifier = Modifier.fillMaxWidth()) { Text("Rename session") }
                            if (activeSession.status == SessionStatus.DISCONNECTED || activeSession.status == SessionStatus.ERROR) {
                                TextButton(onClick = { moreMenu = false; onReconnect?.invoke(activeSession.id) },
                                    modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
                            }
                            TextButton(onClick = { moreMenu = false; onTabClose(activeSession.id) },
                                modifier = Modifier.fillMaxWidth()) {
                                Text("Close session", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            )
        }

        // Disconnected banner
        if (activeSession?.status == SessionStatus.DISCONNECTED || activeSession?.status == SessionStatus.ERROR) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Disconnected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    if (onReconnect != null && activeSession != null) {
                        TextButton(onClick = { onReconnect(activeSession.id) }) {
                            Text("Reconnect")
                        }
                    }
                }
            }
        }

        // Command picker as dialog (works over SwingPanel on desktop)
        // Rename session dialog
        if (showRenameDialog && activeSession != null) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename session") },
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
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
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
                            ) { Text(if (saving) "Saving..." else "Save") }
                            TextButton(onClick = { editMode = false; editText = claudeMdContent }) { Text("Cancel") }
                        } else {
                            if (claudeMdContent.isNotBlank() && claudeMdContent != "(no CLAUDE.md found)" && claudeMdContent != "(no connection)") {
                                TextButton(onClick = { editMode = true }) { Text("Edit") }
                            }
                            TextButton(onClick = { showClaudeMd = false }) { Text("Close") }
                        }
                    }
                },
                title = { Text(if (editMode) "Edit CLAUDE.md" else "CLAUDE.md") },
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
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            )
        }

        // Terminal content (with optional split view)
        if (splitActive && splitTerminalContent != null && wideMode) {
            Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    terminalContent(Modifier.fillMaxSize())
                }
                VerticalDivider(modifier = Modifier.fillMaxHeight().width(2.dp))
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    splitTerminalContent(Modifier.fillMaxSize())
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                terminalContent(Modifier.fillMaxSize())
            }
        }

        if (!compactMode) {
            // Snippet bar (per-server quick commands)
            val snippets = activeSession?.server?.snippets ?: emptyList()
            if (snippets.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 1.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        snippets.forEach { snip ->
                            AssistChip(
                                onClick = { onSendCommand(snip + "\r") },
                                label = {
                                    Text(
                                        if (snip.length > 20) snip.take(18) + ".." else snip,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                }
            }

            // Pending input queue indicator
            if (pendingInputCount > 0) {
                Surface(color = Color(0xFFFFF3E0)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$pendingInputCount message(s) queued — will send on reconnect",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE65100)
                        )
                        if (onClearPending != null) {
                            TextButton(onClick = onClearPending) {
                                Text("Clear", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // Prompt input with inline slash autocomplete
            if (activeSession != null) {
                PromptInputBar(
                    commands = commands,
                    onSend = { text -> onSendCommand(text + "\r") },
                    onSendCommand = onSendCommand,
                    onAttachFile = onAttachFile,
                    inputFocusRequester = inputFocusRequester
                )
            }
        }

        // Control bar
        if (!compactMode && showControlBar && activeSession != null) {
            ClaudeControlBar(
                onSendCommand = onSendCommand,
                onSendEscape = onSendEscape,
                onOpenCommands = {
                    showCommandPicker = true
                    commandFilter = ""
                    if (onFetchCommands != null) {
                        scope.launch { commands = onFetchCommands.invoke() }
                    }
                }
            )
        }

    } // end Column
    } // end Row
    } // end BoxWithConstraints
}

// ======================== SESSION SIDE PANEL ========================

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
    modifier: Modifier = Modifier
) {
    var renamingItem by remember { mutableStateOf<SessionItem?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Rename dialog — uses AlertDialog which renders as a separate OS window,
    // always visible above the JediTerm SwingPanel heavyweight component
    renamingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { renamingItem = null },
            title = { Text("Rename session") },
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
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { renamingItem = null }) { Text("Cancel") }
            }
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onMenuOpen, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Menu, "Menu", modifier = Modifier.size(18.dp))
                }
                Text("Sessions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onNewTab, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Add, "New", modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider()

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                allSessions.forEach { (folder, items) ->
                    Text(
                        folder,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    items.forEach { item ->
                        val dotColor = if (!item.isConnected) Color(0xFF666666)
                            else activityDotColor(
                                sessionActivities[item.id],
                                item.status ?: SessionStatus.ACTIVE
                            )
                        Surface(
                            color = if (item.tab?.id == activeTabId) MaterialTheme.colorScheme.primaryContainer
                                   else Color.Transparent,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (item.isConnected && item.tab != null) onTabSwitch(item.tab.id)
                                else if (item.remote != null) onAttachRemote?.invoke(item.remote)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(8.dp).background(dotColor, shape = CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    item.label + if (!item.isConnected) " (remote)" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                if (item.isConnected && onRenameSession != null) {
                                    IconButton(
                                        onClick = {
                                            renameText = item.label
                                            renamingItem = item
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) { Text("\u270E", style = MaterialTheme.typography.labelSmall) } // pencil
                                }
                                if (item.isConnected) {
                                    IconButton(
                                        onClick = { item.tab?.let { onTabClose(it.id) } },
                                        modifier = Modifier.size(20.dp)
                                    ) { Icon(Icons.Default.Close, "Close", modifier = Modifier.size(12.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ======================== PROMPT INPUT ========================

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
    inputFocusRequester: FocusRequester? = null
) {
    // Auto-focus input when first shown
    LaunchedEffect(Unit) {
        try { inputFocusRequester?.requestFocus() } catch (_: Exception) {}
    }

    var text by rememberSaveable { mutableStateOf("") }
    var attachedFilesRaw by rememberSaveable { mutableStateOf("") }
    val attachedFiles: List<String> = if (attachedFilesRaw.isBlank()) emptyList() else attachedFilesRaw.split('\n')
    var uploading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) } // full-screen editor
    var showTemplates by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val promptScope = rememberCoroutineScope()

    // History (in-memory, persists across recompositions via saveable)
    var historyRaw by rememberSaveable { mutableStateOf("") }
    val history: List<String> = if (historyRaw.isBlank()) emptyList()
        else historyRaw.split("\u0000").filter { it.isNotBlank() }

    // Slash suggestions
    val suggestions = if (text.startsWith("/") && text.length > 1 && !text.contains("\n")) {
        commands.filter { it.command.contains(text.trim(), ignoreCase = true) }.take(5)
    } else emptyList()

    fun addToHistory(msg: String) {
        if (msg.isBlank()) return
        val updated = (listOf(msg) + history.filter { it != msg }).take(50)
        historyRaw = updated.joinToString("\u0000")
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

    // Full-screen editor mode
    if (expanded) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Toolbar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { expanded = false }) { Text("Collapse") }
                        TextButton(onClick = { showTemplates = !showTemplates }) { Text("Templates") }
                        TextButton(onClick = { showHistory = !showHistory }) { Text("History") }
                    }
                    // Char count
                    Text(
                        "${text.length} chars",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Templates dropdown
                if (showTemplates) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        PROMPT_TEMPLATES.forEach { tmpl ->
                            AssistChip(
                                onClick = { text = tmpl; showTemplates = false },
                                label = { Text(tmpl.trimEnd(), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                // History dropdown
                if (showHistory && history.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        history.take(10).forEach { item ->
                            Text(
                                text = if (item.length > 60) item.take(57) + "..." else item,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { text = item; showHistory = false }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            HorizontalDivider()
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
                                label = { Text(path.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall) },
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

                // Big text editor
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
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(Color(0xFFAAAAAA)),
                    decorationBox = { innerTextField ->
                        OutlinedTextFieldDefaults.DecorationBox(
                            value = text,
                            innerTextField = innerTextField,
                            enabled = true,
                            singleLine = false,
                            visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            placeholder = { Text("Type your message...\n\nEnter = new line\nSend button = submit") },
                            container = {
                                OutlinedTextFieldDefaults.ContainerBox(
                                    enabled = true,
                                    isError = false,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                    )
                                )
                            }
                        )
                    }
                )

                // Bottom action row
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
                                            val path = onAttachFile.invoke()
                                            if (path != null) attachedFilesRaw = (attachedFiles + path.split('\n').filter { it.isNotEmpty() }).joinToString("\n")
                                            uploading = false
                                        }
                                    }
                                },
                                modifier = Modifier.size(36.dp), enabled = !uploading
                            ) {
                                if (uploading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Add, "Attach", modifier = Modifier.size(20.dp))
                            }
                        }
                        if (text.isNotBlank()) {
                            IconButton(onClick = { text = "" }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, "Clear", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Button(
                        onClick = { buildAndSend() },
                        contentPadding = PaddingValues(horizontal = 24.dp)
                    ) { Text("Send") }
                }
            }
        }
        return
    }

    // ======================== COMPACT MODE (default) ========================

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
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
                            label = { Text(cmd.command, style = MaterialTheme.typography.bodySmall) }
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
                                    val path = onAttachFile.invoke()
                                    if (path != null) attachedFilesRaw = (attachedFiles + path).joinToString("\n")
                                    uploading = false
                                }
                            }
                        },
                        modifier = Modifier.size(32.dp), enabled = !uploading
                    ) {
                        if (uploading) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Add, "Attach", modifier = Modifier.size(18.dp))
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
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(Color(0xFFAAAAAA)),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                    decorationBox = { innerTextField ->
                        OutlinedTextFieldDefaults.DecorationBox(
                            value = text,
                            innerTextField = innerTextField,
                            enabled = true,
                            singleLine = false,
                            visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            placeholder = { Text("Message or /command...", style = MaterialTheme.typography.bodySmall) },
                            container = {
                                OutlinedTextFieldDefaults.ContainerBox(
                                    enabled = true,
                                    isError = false,
                                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                    )
                                )
                            }
                        )
                    }
                )

                // Expand button
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Text("\u2922", style = MaterialTheme.typography.titleMedium) // expand arrows
                }

                // History button
                if (history.isNotEmpty()) {
                    IconButton(
                        onClick = { showHistory = !showHistory },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("\u2191", style = MaterialTheme.typography.titleMedium) // up arrow
                    }
                }

                Button(
                    onClick = { buildAndSend() },
                    modifier = Modifier.height(40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) { Text("Send") }
            }

            // History popup in compact mode
            if (showHistory && history.isNotEmpty()) {
                Surface(
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                ) {
                    Column(modifier = Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                        history.take(8).forEach { item ->
                            Text(
                                text = if (item.length > 50) item.take(47) + "..." else item,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { text = item; showHistory = false }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

            // Attached files in compact
            if (attachedFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    attachedFiles.forEachIndexed { idx, path ->
                        InputChip(
                            selected = true, onClick = {},
                            label = { Text(path.substringAfterLast('/'), style = MaterialTheme.typography.labelSmall) },
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

// ======================== COMMAND PICKER ========================

@Composable
private fun CommandPicker(
    commands: List<SlashCommand>,
    filter: String,
    onFilterChange: (String) -> Unit,
    onSelect: (SlashCommand) -> Unit,
    onDismiss: () -> Unit
) {
    val filtered = if (filter.isBlank()) commands
    else commands.filter {
        it.command.contains(filter, ignoreCase = true) ||
        it.description.contains(filter, ignoreCase = true)
    }
    var selectedIndex by remember { mutableStateOf(0) }
    // Reset selection when filter changes
    LaunchedEffect(filter) { selectedIndex = 0 }

    Surface(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f).padding(8.dp),
        shape = MaterialTheme.shapes.medium,
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
                    placeholder = { Text("Filter commands...") },
                    modifier = Modifier.weight(1f)
                        .focusRequester(filterFocus)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        selectedIndex = (selectedIndex + 1).coerceAtMost(filtered.size - 1)
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                        true
                                    }
                                    Key.Enter -> {
                                        if (filtered.isNotEmpty() && selectedIndex in filtered.indices) {
                                            onSelect(filtered[selectedIndex])
                                        }
                                        true
                                    }
                                    Key.Escape -> { onDismiss(); true }
                                    else -> false
                                }
                            } else false
                        },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }

            val listState = rememberLazyListState()
            LaunchedEffect(selectedIndex) {
                if (selectedIndex in filtered.indices) {
                    listState.animateScrollToItem(selectedIndex)
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(filtered) { index, cmd ->
                    val isSelected = index == selectedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .clickable { onSelect(cmd) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(cmd.command, style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                   else MaterialTheme.colorScheme.primary)
                        Text(cmd.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ======================== CONTROL BAR ========================

@Composable
private fun ClaudeControlBar(
    onSendCommand: (String) -> Unit,
    onSendEscape: () -> Unit,
    onOpenCommands: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 4.dp) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            CtrlButton("Mode") { onSendCommand("\u001B[Z") }
            CtrlButton("/") { onOpenCommands() }
            CtrlButton("Esc") { onSendEscape() }
            CtrlButton("C-c") { onSendCommand("\u0003") }
            CtrlButton("\u2190") { onSendCommand("\u001B[D") }
            CtrlButton("\u2193") { onSendCommand("\u001B[B") }
            CtrlButton("\u2191") { onSendCommand("\u001B[A") }
            CtrlButton("\u2192") { onSendCommand("\u001B[C") }
            Spacer(Modifier.weight(1f))
            CtrlButton("y") { onSendCommand("y\r") }
            CtrlButton("n") { onSendCommand("n\r") }
        }
    }
}

@Composable
private fun MiniBar(label: String, percent: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp))
        Box(
            modifier = Modifier.width(40.dp).height(4.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f), CircleShape)
        ) {
            val pct = percent.coerceIn(0, 100)
            val color = when {
                pct < 50 -> Color(0xFF4CAF50)
                pct < 80 -> Color(0xFFFF9800)
                else -> Color(0xFFF44336)
            }
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(pct / 100f).background(color, CircleShape))
        }
        Text("${percent}%", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
private fun CtrlButton(label: String, onClick: () -> Unit) {
    val haptic = androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    FilledTonalButton(
        onClick = {
            hapticFeedback.performHapticFeedback(haptic)
            onClick()
        },
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Map SessionActivity (or fallback to SessionStatus) to a dot color.
 * Green=waiting/idle, Yellow=working, Blue=approval, Red=disconnected.
 */
private fun activityDotColor(
    activity: com.clauderemote.model.SessionActivity?,
    status: SessionStatus
): Color = when (activity) {
    com.clauderemote.model.SessionActivity.WAITING_FOR_INPUT -> Color(0xFF4CAF50) // green
    com.clauderemote.model.SessionActivity.WORKING -> Color(0xFFFF9800)           // yellow/amber
    com.clauderemote.model.SessionActivity.APPROVAL_NEEDED -> Color(0xFF2196F3)   // blue
    com.clauderemote.model.SessionActivity.IDLE -> Color(0xFF4CAF50)              // green
    com.clauderemote.model.SessionActivity.DISCONNECTED -> Color(0xFFF44336)      // red
    null -> when (status) {
        SessionStatus.ACTIVE -> Color(0xFF4CAF50)
        SessionStatus.CONNECTING -> Color(0xFFFF9800)
        SessionStatus.DISCONNECTED, SessionStatus.ERROR -> Color(0xFFF44336)
    }
}

// ======================== TAB BAR ========================
