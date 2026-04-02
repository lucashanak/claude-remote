package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    tabs: List<ClaudeSession>,
    activeTabId: String?,
    onTabSwitch: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onMenuOpen: () -> Unit,
    // Claude control
    onSendCommand: (String) -> Unit,
    onSwitchModel: (ClaudeModel) -> Unit,
    onSendEscape: () -> Unit,
    // Terminal content composable (platform-specific WebView)
    terminalContent: @Composable (Modifier) -> Unit
) {
    var showControlBar by remember { mutableStateOf(true) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val activeSession = tabs.find { it.id == activeTabId }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar - minimal
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
                    Text(if (showControlBar) "Hide" else "Controls", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Terminal content (WebView) - takes all remaining space
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            terminalContent(Modifier.fillMaxSize())
        }

        // Claude Control Bar
        if (showControlBar && activeSession != null) {
            ClaudeControlBar(
                session = activeSession,
                modelMenuExpanded = modelMenuExpanded,
                onModelMenuToggle = { modelMenuExpanded = it },
                onSendCommand = onSendCommand,
                onSwitchModel = onSwitchModel,
                onSendEscape = onSendEscape
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
private fun ClaudeControlBar(
    session: ClaudeSession,
    modelMenuExpanded: Boolean,
    onModelMenuToggle: (Boolean) -> Unit,
    onSendCommand: (String) -> Unit,
    onSwitchModel: (ClaudeModel) -> Unit,
    onSendEscape: () -> Unit
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
            // Row 1: Mode toggle + Claude slash commands
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Shift+Tab cycles modes: normal → plan → auto-accept
                CtrlButton("Mode") { onSendCommand("\u001B[Z") }
                CtrlButton("/plan") { onSendCommand("/plan\n") }
                CtrlButton("/model") { onSendCommand("/model\n") }
                CtrlButton("/compact") { onSendCommand("/compact\n") }

                Spacer(Modifier.weight(1f))

                CtrlButton("Esc") { onSendEscape() }
                CtrlButton("/clear") { onSendCommand("/clear\n") }
            }

            // Row 2: Navigation + editing
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                CtrlButton("Tab") { onSendCommand("\t") }
                CtrlButton("Enter") { onSendCommand("\r") }
                Spacer(Modifier.weight(1f))
                CtrlButton("\u2190") { onSendCommand("\u001B[D") }
                CtrlButton("\u2193") { onSendCommand("\u001B[B") }
                CtrlButton("\u2191") { onSendCommand("\u001B[A") }
                CtrlButton("\u2192") { onSendCommand("\u001B[C") }
                Spacer(Modifier.weight(1f))
                CtrlButton("C-c") { onSendCommand("\u0003") }
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
                        Text(
                            tab.tabTitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = { onTabClose(tab.id) },
                            modifier = Modifier.size(16.dp)
                        ) {
                            Icon(
                                Icons.Default.Close, "Close",
                                modifier = Modifier.size(12.dp)
                            )
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
