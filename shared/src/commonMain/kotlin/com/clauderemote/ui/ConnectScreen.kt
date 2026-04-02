package com.clauderemote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.clauderemote.model.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectScreen(
    server: SshServer,
    tmuxSessions: List<TmuxSession>,
    onBack: () -> Unit,
    onKillTmux: ((String) -> Unit)? = null,
    onLaunch: (folder: String, mode: ClaudeMode, model: ClaudeModel, connectionType: ConnectionType, tmuxSession: String, isNewTmuxSession: Boolean) -> Unit
) {
    var folder by remember { mutableStateOf(server.defaultFolder) }
    var selectedMode by remember { mutableStateOf(server.defaultClaudeMode) }
    var selectedModel by remember { mutableStateOf(server.defaultClaudeModel) }
    var connectionType by remember { mutableStateOf(ConnectionType.SSH) }
    var tmuxSessionName by remember { mutableStateOf("claude-${server.name}-${server.defaultFolder.substringAfterLast('/')}") }
    // Auto-update tmux name when folder changes
    LaunchedEffect(folder) {
        if (!useExistingTmux) {
            val folderName = folder.substringAfterLast('/').ifBlank { folder.trimEnd('/').substringAfterLast('/') }
            tmuxSessionName = "claude-${server.name}-${folderName}".take(32)
        }
    }
    var useExistingTmux by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(server.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Folder
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Folder", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = folder,
                        onValueChange = { folder = it },
                        label = { Text("Remote path") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (server.recentFolders.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Recent:", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            server.recentFolders.take(5).forEach { recent ->
                                AssistChip(
                                    onClick = { folder = recent },
                                    label = { Text(recent, style = MaterialTheme.typography.bodySmall) }
                                )
                            }
                        }
                    }
                }
            }

            // Claude Options
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Claude Options", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))

                    // Mode
                    Text("Mode", style = MaterialTheme.typography.bodyMedium)
                    Column(modifier = Modifier.selectableGroup()) {
                        ClaudeMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp)
                                    .selectable(
                                        selected = selectedMode == mode,
                                        onClick = { selectedMode = mode },
                                        role = Role.RadioButton
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedMode == mode,
                                    onClick = null
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(mode.displayName)
                                if (mode == ClaudeMode.YOLO) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "(--dangerously-skip-permissions, cannot toggle at runtime)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Model
                    Text("Model", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    ExposedDropdownMenuBox(
                        expanded = modelExpanded,
                        onExpandedChange = { modelExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedModel.displayName,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = modelExpanded,
                            onDismissRequest = { modelExpanded = false }
                        ) {
                            ClaudeModel.entries.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.displayName) },
                                    onClick = {
                                        selectedModel = model
                                        modelExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Connection type
                    Text("Connection", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = connectionType == ConnectionType.SSH,
                            onClick = { connectionType = ConnectionType.SSH },
                            label = { Text("SSH") }
                        )
                        FilterChip(
                            selected = connectionType == ConnectionType.MOSH,
                            onClick = { connectionType = ConnectionType.MOSH },
                            label = { Text("Mosh (not yet)") },
                            enabled = false
                        )
                    }
                }
            }

            // Tmux Session
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tmux Session", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))

                    // New session
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .selectable(
                                selected = !useExistingTmux,
                                onClick = { useExistingTmux = false },
                                role = Role.RadioButton
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = !useExistingTmux, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text("New session")
                    }

                    if (!useExistingTmux) {
                        OutlinedTextField(
                            value = tmuxSessionName,
                            onValueChange = { tmuxSessionName = it },
                            label = { Text("Session name") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 40.dp),
                            singleLine = true
                        )
                    }

                    // Existing sessions
                    tmuxSessions.forEach { tmux ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .selectable(
                                    selected = useExistingTmux && tmuxSessionName == tmux.name,
                                    onClick = {
                                        useExistingTmux = true
                                        tmuxSessionName = tmux.name
                                    },
                                    role = Role.RadioButton
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = useExistingTmux && tmuxSessionName == tmux.name,
                                onClick = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${tmux.name} (${tmux.windows}w)",
                                modifier = Modifier.weight(1f)
                            )
                            if (tmux.attached) {
                                Text(
                                    "attached",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            if (onKillTmux != null) {
                                TextButton(
                                    onClick = { onKillTmux(tmux.name) },
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("Kill", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            // Launch button
            Button(
                onClick = {
                    onLaunch(folder, selectedMode, selectedModel, connectionType, tmuxSessionName, !useExistingTmux)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("Launch Claude", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
