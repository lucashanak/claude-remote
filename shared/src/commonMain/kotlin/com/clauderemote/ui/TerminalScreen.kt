package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.SessionStatus
import com.clauderemote.session.CommandFetcher
import com.clauderemote.session.SlashCommand
import kotlinx.coroutines.launch

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
    onAttachFile: (suspend () -> String?)? = null,
    onFetchClaudeMd: (suspend () -> String)? = null,
    onFetchCommands: (suspend () -> List<SlashCommand>)? = null,
    terminalContent: @Composable (Modifier) -> Unit
) {
    var showControlBar by remember { mutableStateOf(true) }
    var compactMode by remember { mutableStateOf(false) } // hides prompt+controls for max terminal
    var showCommandPicker by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    var showClaudeMd by remember { mutableStateOf(false) }
    var claudeMdContent by remember { mutableStateOf("") }
    var commandFilter by remember { mutableStateOf("") }
    var commands by remember { mutableStateOf(CommandFetcher.getCachedOrFallback()) }
    val activeSession = tabs.find { it.id == activeTabId }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onMenuOpen, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Menu, "Menu", modifier = Modifier.size(20.dp))
                }

                // Session dropdown (replaces tab bar)
                var sessionDropdown by remember { mutableStateOf(false) }
                Box(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.clickable { if (tabs.size > 1) sessionDropdown = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Status dot
                        if (activeSession != null) {
                            val dotColor = when (activeSession.status) {
                                SessionStatus.ACTIVE -> Color(0xFF4CAF50)
                                SessionStatus.CONNECTING -> Color(0xFFFF9800)
                                SessionStatus.DISCONNECTED, SessionStatus.ERROR -> Color(0xFFF44336)
                            }
                            Box(modifier = Modifier.size(8.dp).background(dotColor, shape = CircleShape))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            activeSession?.tabTitle ?: "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (tabs.size > 1) {
                            Text(
                                " (${tabs.size})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(" \u25BE", style = MaterialTheme.typography.bodySmall) // ▾
                        }
                    }
                    DropdownMenu(
                        expanded = sessionDropdown,
                        onDismissRequest = { sessionDropdown = false }
                    ) {
                        tabs.forEach { tab ->
                            val isActive = tab.id == activeTabId
                            val dotColor = when (tab.status) {
                                SessionStatus.ACTIVE -> Color(0xFF4CAF50)
                                SessionStatus.CONNECTING -> Color(0xFFFF9800)
                                SessionStatus.DISCONNECTED, SessionStatus.ERROR -> Color(0xFFF44336)
                            }
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.size(8.dp).background(dotColor, shape = CircleShape))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            tab.tabTitle,
                                            style = if (isActive) MaterialTheme.typography.bodyMedium
                                                   else MaterialTheme.typography.bodySmall
                                        )
                                    }
                                },
                                onClick = {
                                    sessionDropdown = false
                                    onTabSwitch(tab.id)
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            sessionDropdown = false
                                            onTabClose(tab.id)
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, "Close", modifier = Modifier.size(14.dp))
                                    }
                                }
                            )
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
                // More menu
                var moreMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { moreMenu = true }, modifier = Modifier.size(36.dp)) {
                        Text("\u22EE", style = MaterialTheme.typography.titleMedium) // vertical ellipsis
                    }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        if (onFetchClaudeMd != null) {
                            DropdownMenuItem(
                                text = { Text("View CLAUDE.md") },
                                onClick = {
                                    moreMenu = false
                                    scope.launch {
                                        claudeMdContent = onFetchClaudeMd.invoke()
                                        showClaudeMd = true
                                    }
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Reset terminal") },
                            onClick = {
                                moreMenu = false
                                onSendCommand("\u001Bc") // ESC c = full terminal reset
                            }
                        )
                    }
                }
            }
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

        // Terminal content
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            terminalContent(Modifier.fillMaxSize())

            if (showCommandPicker) {
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

            // CLAUDE.md viewer overlay
            if (showClaudeMd) {
                Surface(
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.7f).padding(8.dp),
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("CLAUDE.md", style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { showClaudeMd = false }) {
                                Icon(Icons.Default.Close, "Close")
                            }
                        }
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = claudeMdContent.ifBlank { "(not found)" },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
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
                        .padding(horizontal = 8.dp),
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
                                            if (path != null) attachedFilesRaw = (attachedFiles + path).joinToString("\n")
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
                        .then(if (inputFocusRequester != null) Modifier.focusRequester(inputFocusRequester) else Modifier),
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
                    modifier = Modifier.weight(1f).focusRequester(filterFocus),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered) { cmd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(cmd) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(cmd.command, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Text(cmd.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

// ======================== TAB BAR ========================
