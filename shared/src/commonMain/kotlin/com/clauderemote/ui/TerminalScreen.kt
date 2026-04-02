package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
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
    onFetchCommands: (suspend () -> List<SlashCommand>)? = null,
    terminalContent: @Composable (Modifier) -> Unit
) {
    var showControlBar by remember { mutableStateOf(true) }
    var showCommandPicker by remember { mutableStateOf(false) }
    var commandFilter by remember { mutableStateOf("") }
    var commands by remember { mutableStateOf(CommandFetcher.getCachedOrFallback()) }
    val activeSession = tabs.find { it.id == activeTabId }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onMenuOpen, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Menu, "Menu", modifier = Modifier.size(20.dp))
                }
                Text(
                    activeSession?.tabTitle ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showControlBar = !showControlBar }) {
                    Text(if (showControlBar) "Hide" else "Ctrl", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Terminal content
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            terminalContent(Modifier.fillMaxSize())

            // Command picker overlay
            if (showCommandPicker) {
                CommandPicker(
                    commands = commands,
                    filter = commandFilter,
                    onFilterChange = { commandFilter = it },
                    onSelect = { cmd ->
                        showCommandPicker = false
                        commandFilter = ""
                        onSendCommand(cmd.command + "\n")
                    },
                    onDismiss = {
                        showCommandPicker = false
                        commandFilter = ""
                    }
                )
            }
        }

        // Input field + Send (multiline prompt entry)
        if (activeSession != null) {
            PromptInputBar(
                onSend = { text ->
                    onSendCommand(text + "\r")
                },
                onSendCommand = onSendCommand
            )
        }

        // Control bar
        if (showControlBar && activeSession != null) {
            ClaudeControlBar(
                onSendCommand = onSendCommand,
                onSendEscape = onSendEscape,
                onOpenCommands = {
                    showCommandPicker = true
                    commandFilter = ""
                    // Fetch commands from remote in background
                    if (onFetchCommands != null) {
                        scope.launch {
                            val fetched = onFetchCommands.invoke()
                            commands = fetched
                        }
                    }
                }
            )
        }

        // Tab bar
        TabBar(
            tabs = tabs,
            activeTabId = activeTabId,
            onTabSwitch = onTabSwitch,
            onTabClose = onTabClose,
            onNewTab = onNewTab
        )
    }
}

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
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.6f)
            .padding(8.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Search/filter bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = onFilterChange,
                    placeholder = { Text("Filter commands...") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close")
                }
            }

            // Command list
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered) { cmd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(cmd) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            cmd.command,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            cmd.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PromptInputBar(
    onSend: (String) -> Unit,
    onSendCommand: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type message...", style = MaterialTheme.typography.bodySmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 1,
                maxLines = 5,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    } else {
                        // Empty send = just Enter (confirm prompt, accept, etc.)
                        onSendCommand("\r")
                    }
                },
                modifier = Modifier.height(48.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun ClaudeControlBar(
    onSendCommand: (String) -> Unit,
    onSendEscape: () -> Unit,
    onOpenCommands: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Row 1: Commands
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                CtrlButton("Mode") { onSendCommand("\u001B[Z") }
                CtrlButton("/") { onOpenCommands() }

                Spacer(Modifier.weight(1f))

                CtrlButton("Esc") { onSendEscape() }
                CtrlButton("C-c") { onSendCommand("\u0003") }
            }

            // Row 2: Navigation + quick responses
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                CtrlButton("Tab") { onSendCommand("\t") }
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
}

@Composable
private fun CtrlButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TabBar(
    tabs: List<ClaudeSession>,
    activeTabId: String?,
    onTabSwitch: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onNewTab: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val isActive = tab.id == activeTabId
                Surface(
                    onClick = { onTabSwitch(tab.id) },
                    color = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(horizontal = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(tab.tabTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { onTabClose(tab.id) }, modifier = Modifier.size(16.dp)) {
                            Icon(Icons.Default.Close, "Close", modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
            IconButton(onClick = onNewTab, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, "New Tab", modifier = Modifier.size(16.dp))
            }
        }
    }
}
