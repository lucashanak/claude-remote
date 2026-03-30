package com.clauderemote.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.clauderemote.model.*
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.storage.AppSettings
import com.clauderemote.storage.ServerStorage
import com.clauderemote.ui.theme.ClaudeRemoteTheme
import com.clauderemote.util.UpdateChecker
import com.clauderemote.util.UpdateInfo
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
    appVersion: String = "1.0.0",
    onInstallUpdate: ((ByteArray, UpdateInfo) -> Unit)? = null,
    terminalContent: @Composable (modifier: Modifier) -> Unit
) {
    val scope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf(Screen.LAUNCHER) }
    var selectedServer by remember { mutableStateOf<SshServer?>(null) }
    var showServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<SshServer?>(null) }
    var tmuxSessions by remember { mutableStateOf<List<TmuxSession>>(emptyList()) }
    var connectionError by remember { mutableStateOf<String?>(null) }

    var serverList by remember { mutableStateOf(serverStorage.loadServers()) }
    val tabs by tabManager.tabs.collectAsState()
    val activeTabId by tabManager.activeTabId.collectAsState()

    // Update state
    var updateState by remember { mutableStateOf(UpdateState()) }

    // Check for updates on launch
    LaunchedEffect(Unit) {
        try {
            val info = UpdateChecker.checkUpdate(appVersion)
            if (info != null) {
                updateState = UpdateState(info = info)
            }
        } catch (_: Exception) {}
    }

    fun refreshServers() {
        serverList = serverStorage.loadServers()
    }

    fun downloadUpdate(info: UpdateInfo) {
        scope.launch {
            try {
                updateState = updateState.copy(downloading = true, error = null, statusText = "Downloading...")

                val apkBytes = if (info.hasPatch) {
                    // Delta patch chain
                    try {
                        applyPatchChain(info, appVersion) { status, progress ->
                            updateState = updateState.copy(statusText = status, progress = progress)
                        }
                    } catch (e: Exception) {
                        // Fallback to full APK
                        updateState = updateState.copy(statusText = "Patch failed, downloading full APK...")
                        UpdateChecker.downloadFile(info.apkUrl) { progress, dl, total ->
                            updateState = updateState.copy(
                                progress = progress,
                                statusText = "Downloading ${UpdateChecker.formatBytes(dl)} / ${UpdateChecker.formatBytes(total)}"
                            )
                        }
                    }
                } else {
                    UpdateChecker.downloadFile(info.apkUrl) { progress, dl, total ->
                        updateState = updateState.copy(
                            progress = progress,
                            statusText = "Downloading ${UpdateChecker.formatBytes(dl)} / ${UpdateChecker.formatBytes(total)}"
                        )
                    }
                }

                // Verify SHA-256
                if (info.apkSha256 != null) {
                    val actualHash = UpdateChecker.sha256(apkBytes)
                    if (actualHash != info.apkSha256) {
                        updateState = updateState.copy(
                            downloading = false,
                            error = "Hash mismatch - download corrupted"
                        )
                        return@launch
                    }
                }

                updateState = updateState.copy(statusText = "Installing v${info.version}...", progress = 100)
                onInstallUpdate?.invoke(apkBytes, info)
            } catch (e: Exception) {
                updateState = updateState.copy(
                    downloading = false,
                    error = "Download failed: ${e.message}"
                )
            }
        }
    }

    ClaudeRemoteTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            // Update banner at top
            UpdateBanner(
                state = updateState,
                onDownload = { updateState.info?.let { downloadUpdate(it) } },
                onDismiss = { updateState = UpdateState() }
            )

            // Main content
            when (currentScreen) {
                Screen.LAUNCHER -> {
                    LauncherScreen(
                        servers = serverList,
                        activeSessions = tabs,
                        onConnectServer = { server ->
                            selectedServer = server
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
                                            onOutput = {},
                                            onDisconnect = {}
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

        // Connection error dialog
        connectionError?.let { error ->
            AlertDialog(
                onDismissRequest = { connectionError = null },
                title = { Text("Connection Error") },
                text = { Text(error) },
                confirmButton = {
                    TextButton(onClick = { connectionError = null }) { Text("OK") }
                }
            )
        }
    }
}

/**
 * Apply a chain of binary delta patches to produce the final APK bytes.
 */
private suspend fun applyPatchChain(
    update: UpdateInfo,
    currentVersion: String,
    onStatus: (String, Int) -> Unit
): ByteArray {
    val chain = update.patchChain
    // Read current installed APK - this will be provided by platform
    // For now, download full APK as fallback
    throw UnsupportedOperationException("Patch chain requires platform-specific APK access")
}
