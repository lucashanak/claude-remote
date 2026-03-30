package com.clauderemote.ui

import androidx.compose.runtime.*
import com.clauderemote.model.*
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.storage.AppSettings
import com.clauderemote.storage.ServerStorage
import com.clauderemote.ui.theme.ClaudeRemoteTheme
import kotlinx.coroutines.launch

enum class Screen {
    LAUNCHER, CONNECT, TERMINAL, SETTINGS
}

@Composable
fun App(
    serverStorage: ServerStorage,
    appSettings: AppSettings,
    tabManager: TabManager,
    sessionOrchestrator: SessionOrchestrator,
    terminalContent: @Composable (modifier: androidx.compose.ui.Modifier) -> Unit
) {
    val scope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf(Screen.LAUNCHER) }
    var selectedServer by remember { mutableStateOf<SshServer?>(null) }
    var showServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<SshServer?>(null) }
    var tmuxSessions by remember { mutableStateOf<List<TmuxSession>>(emptyList()) }
    var connectionError by remember { mutableStateOf<String?>(null) }

    val servers by remember { derivedStateOf { serverStorage.loadServers() } }
    var serverList by remember { mutableStateOf(serverStorage.loadServers()) }
    val tabs by tabManager.tabs.collectAsState()
    val activeTabId by tabManager.activeTabId.collectAsState()

    // Refresh server list helper
    fun refreshServers() {
        serverList = serverStorage.loadServers()
    }

    ClaudeRemoteTheme {
        when (currentScreen) {
            Screen.LAUNCHER -> {
                LauncherScreen(
                    servers = serverList,
                    activeSessions = tabs,
                    onConnectServer = { server ->
                        selectedServer = server
                        // TODO: fetch tmux sessions in background
                        tmuxSessions = emptyList()
                        currentScreen = Screen.CONNECT
                    },
                    onAddServer = {
                        editingServer = null
                        showServerDialog = true
                    },
                    onEditServer = { server ->
                        editingServer = server
                        showServerDialog = true
                    },
                    onDeleteServer = { server ->
                        serverStorage.deleteServer(server.id)
                        refreshServers()
                    },
                    onResumeSession = { session ->
                        tabManager.switchTab(session.id)
                        currentScreen = Screen.TERMINAL
                    },
                    onSettings = { currentScreen = Screen.SETTINGS }
                )
            }

            Screen.CONNECT -> {
                selectedServer?.let { server ->
                    ConnectScreen(
                        server = server,
                        tmuxSessions = tmuxSessions,
                        onBack = { currentScreen = Screen.LAUNCHER },
                        onLaunch = { folder, mode, model, connType, tmuxName ->
                            scope.launch {
                                try {
                                    connectionError = null
                                    sessionOrchestrator.launchSession(
                                        server = server,
                                        folder = folder,
                                        mode = mode,
                                        model = model,
                                        connectionType = connType,
                                        tmuxSessionName = tmuxName,
                                        onOutput = { /* handled by terminal WebView */ },
                                        onDisconnect = { /* handled by tab status update */ }
                                    )
                                    currentScreen = Screen.TERMINAL
                                } catch (e: Exception) {
                                    connectionError = e.message
                                }
                            }
                        }
                    )
                }
            }

            Screen.TERMINAL -> {
                TerminalScreen(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    onTabSwitch = { tabManager.switchTab(it) },
                    onTabClose = { id ->
                        scope.launch {
                            sessionOrchestrator.disconnectSession(id)
                            if (tabs.isEmpty()) currentScreen = Screen.LAUNCHER
                        }
                    },
                    onNewTab = { currentScreen = Screen.LAUNCHER },
                    onMenuOpen = { currentScreen = Screen.LAUNCHER },
                    onSendCommand = { cmd ->
                        activeTabId?.let { sessionOrchestrator.sendClaudeCommand(it, cmd) }
                    },
                    onSwitchModel = { model ->
                        activeTabId?.let { sessionOrchestrator.switchModel(it, model) }
                    },
                    onSendEscape = {
                        activeTabId?.let { sessionOrchestrator.sendEscape(it) }
                    },
                    terminalContent = terminalContent
                )
            }

            Screen.SETTINGS -> {
                SettingsScreen(
                    settings = appSettings,
                    onBack = { currentScreen = Screen.LAUNCHER }
                )
            }
        }

        // Server add/edit dialog
        if (showServerDialog) {
            ServerEditDialog(
                server = editingServer,
                onDismiss = { showServerDialog = false },
                onSave = { server ->
                    if (editingServer != null) {
                        serverStorage.updateServer(server)
                    } else {
                        serverStorage.addServer(server)
                    }
                    refreshServers()
                    showServerDialog = false
                }
            )
        }

        // Connection error snackbar
        connectionError?.let { error ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { connectionError = null },
                title = { androidx.compose.material3.Text("Connection Error") },
                text = { androidx.compose.material3.Text(error) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { connectionError = null }) {
                        androidx.compose.material3.Text("OK")
                    }
                }
            )
        }
    }
}
