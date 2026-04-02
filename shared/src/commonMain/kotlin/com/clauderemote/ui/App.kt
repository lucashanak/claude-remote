package com.clauderemote.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.clauderemote.connection.TmuxManager
import com.clauderemote.model.*
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.storage.AppSettings
import com.clauderemote.storage.ServerStorage
import com.clauderemote.ui.theme.ClaudeRemoteTheme
import com.clauderemote.util.FileLogger
import com.clauderemote.util.UpdateChecker
import com.clauderemote.util.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen {
    LAUNCHER, CONNECT, TERMINAL, SETTINGS, LOG_VIEWER
}

@Composable
fun App(
    serverStorage: ServerStorage,
    appSettings: AppSettings,
    tabManager: TabManager,
    sessionOrchestrator: SessionOrchestrator,
    appVersion: String = "1.0.0",
    onInstallUpdate: ((ByteArray, UpdateInfo) -> Unit)? = null,
    onShareLog: ((String) -> Unit)? = null,
    onTerminalScreenVisible: (() -> Unit)? = null,
    onPickKeyFile: ((callback: (String) -> Unit) -> Unit)? = null,
    onImportServers: (() -> Unit)? = null,
    terminalContent: @Composable (modifier: Modifier) -> Unit
) {
    val scope = rememberCoroutineScope()

    var currentScreen by remember { mutableStateOf(Screen.LAUNCHER) }
    var selectedServer by remember { mutableStateOf<SshServer?>(null) }
    var showServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<SshServer?>(null) }
    var tmuxSessions by remember { mutableStateOf<List<TmuxSession>>(emptyList()) }
    var tmuxLoading by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var tabCloseConfirmId by remember { mutableStateOf<String?>(null) }

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

    // Manual update check function
    fun checkForUpdate() {
        scope.launch {
            try {
                val info = UpdateChecker.checkUpdate(appVersion)
                if (info != null) {
                    updateState = UpdateState(info = info)
                }
            } catch (_: Exception) {}
        }
    }

    // Back handler (Android only, no-op on desktop)
    PlatformBackHandler(enabled = currentScreen != Screen.LAUNCHER) {
        currentScreen = Screen.LAUNCHER
    }

    fun refreshServers() {
        serverList = serverStorage.loadServers()
    }

    fun downloadUpdate(info: UpdateInfo) {
        scope.launch {
            try {
                updateState = updateState.copy(downloading = true, error = null, statusText = "Downloading...")

                // Always download full APK (delta patching needs platform APK access)
                val apkBytes = UpdateChecker.downloadFile(info.apkUrl) { progress, dl, total ->
                    updateState = updateState.copy(
                        progress = progress,
                        statusText = "Downloading ${UpdateChecker.formatBytes(dl)} / ${UpdateChecker.formatBytes(total)}"
                    )
                }

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

    val darkTheme = when (appSettings.themeMode) {
        "dark" -> true
        "light" -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    ClaudeRemoteTheme(darkTheme = darkTheme) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime))
        ) {
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
                        onQuickConnect = { server ->
                            // Long-press: connect directly with defaults
                            scope.launch {
                                try {
                                    connectionError = null
                                    sessionOrchestrator.launchSession(
                                        server = server,
                                        folder = server.defaultFolder,
                                        mode = server.defaultClaudeMode,
                                        model = server.defaultClaudeModel,
                                        connectionType = ConnectionType.SSH,
                                        tmuxSessionName = "claude-${server.name}",
                                        isNewTmuxSession = true
                                    )
                                    currentScreen = Screen.TERMINAL
                                } catch (e: Exception) {
                                    connectionError = e.message
                                }
                            }
                        },
                        onConnectServer = { server ->
                            selectedServer = server
                            tmuxSessions = emptyList()
                            currentScreen = Screen.CONNECT
                            scope.launch {
                                tmuxLoading = true
                                try {
                                    withContext(Dispatchers.IO) {
                                        val jsch = com.jcraft.jsch.JSch()
                                        if (server.authMethod == AuthMethod.KEY && server.privateKey != null) {
                                            jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
                                        }
                                        val sess = jsch.getSession(server.username, server.host, server.port)
                                        if (server.authMethod == AuthMethod.PASSWORD && server.password != null) {
                                            sess.setPassword(server.password)
                                        }
                                        sess.setConfig("StrictHostKeyChecking", "no")
                                        sess.timeout = 10000
                                        sess.connect(10000)
                                        tmuxSessions = TmuxManager.listSessions(sess)
                                        sess.disconnect()
                                    }
                                } catch (_: Exception) {
                                    tmuxSessions = emptyList()
                                }
                                tmuxLoading = false
                            }
                        },
                        onAddServer = {
                            editingServer = null
                            showServerDialog = true
                        },
                        onEditServer = { server ->
                            editingServer = server
                            showServerDialog = true
                        },
                        onDuplicateServer = { server ->
                            val copy = server.copy(
                                id = kotlin.random.Random.nextBytes(16).joinToString("") { "%02x".format(it) },
                                name = "${server.name} (copy)",
                                favorite = false
                            )
                            serverStorage.addServer(copy)
                            refreshServers()
                        },
                        onDeleteServer = { server ->
                            serverStorage.deleteServer(server.id)
                            refreshServers()
                        },
                        onToggleFavorite = { server ->
                            serverStorage.updateServer(server.copy(favorite = !server.favorite))
                            refreshServers()
                        },
                        onResumeSession = { session ->
                            sessionOrchestrator.switchTab(session.id)
                            currentScreen = Screen.TERMINAL
                        },
                        onSettings = { currentScreen = Screen.SETTINGS },
                        onViewLog = { currentScreen = Screen.LOG_VIEWER }
                    )
                }

                Screen.CONNECT -> {
                    selectedServer?.let { server ->
                        ConnectScreen(
                            server = server,
                            tmuxSessions = tmuxSessions,
                            onBack = { currentScreen = Screen.LAUNCHER },
                            onKillTmux = { sessionName ->
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val jsch = com.jcraft.jsch.JSch()
                                            if (server.authMethod == AuthMethod.KEY && server.privateKey != null) {
                                                jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
                                            }
                                            val sess = jsch.getSession(server.username, server.host, server.port)
                                            if (server.authMethod == AuthMethod.PASSWORD && server.password != null) {
                                                sess.setPassword(server.password)
                                            }
                                            sess.setConfig("StrictHostKeyChecking", "no")
                                            sess.timeout = 10000
                                            sess.connect(10000)
                                            TmuxManager.killSession(sess, sessionName)
                                            tmuxSessions = TmuxManager.listSessions(sess)
                                            sess.disconnect()
                                        }
                                    } catch (_: Exception) {}
                                }
                            },
                            onLaunch = { folder, mode, model, connType, tmuxName, isNewTmux ->
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
                                            isNewTmuxSession = isNewTmux
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
                    // Notify platform when terminal screen becomes visible
                    LaunchedEffect(Unit) {
                        onTerminalScreenVisible?.invoke()
                    }
                    TerminalScreen(
                        tabs = tabs,
                        activeTabId = activeTabId,
                        onTabSwitch = { sessionOrchestrator.switchTab(it) },
                        onReconnect = { id ->
                            scope.launch { sessionOrchestrator.reconnectSession(id) }
                        },
                        onTabClose = { id ->
                            val session = tabManager.getTab(id)
                            if (session?.status == SessionStatus.ACTIVE) {
                                tabCloseConfirmId = id
                            } else {
                                scope.launch {
                                    sessionOrchestrator.disconnectSession(id)
                                    if (tabs.isEmpty()) currentScreen = Screen.LAUNCHER
                                }
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
                        onFetchClaudeMd = {
                            val id = activeTabId
                            if (id != null) {
                                val conn = sessionOrchestrator.getConnection(id)
                                val sess = conn?.getSession()
                                if (sess != null) {
                                    try {
                                        val folder = tabManager.getTab(id)?.folder ?: "~"
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
                                            ch.setCommand("cat $folder/CLAUDE.md 2>/dev/null || cat ~/.claude/CLAUDE.md 2>/dev/null || echo '(no CLAUDE.md found)'")
                                            ch.inputStream = null
                                            val input = ch.inputStream
                                            ch.connect(5000)
                                            val content = input.bufferedReader().readText()
                                            ch.disconnect()
                                            content
                                        }
                                    } catch (_: Exception) { "(failed to read CLAUDE.md)" }
                                } else "(no connection)"
                            } else "(no active tab)"
                        },
                        onSendEscape = {
                            activeTabId?.let { sessionOrchestrator.sendEscape(it) }
                        },
                        onFetchCommands = {
                            val id = activeTabId
                            if (id != null) {
                                val conn = sessionOrchestrator.getConnection(id)
                                if (conn != null) {
                                    com.clauderemote.session.CommandFetcher.fetchCommands(conn)
                                } else {
                                    com.clauderemote.session.CommandFetcher.getCachedOrFallback()
                                }
                            } else {
                                com.clauderemote.session.CommandFetcher.getCachedOrFallback()
                            }
                        },
                        terminalContent = terminalContent
                    )
                }

                Screen.SETTINGS -> {
                    SettingsScreen(
                        settings = appSettings,
                        appVersion = appVersion,
                        onBack = { currentScreen = Screen.LAUNCHER },
                        onCheckUpdate = { checkForUpdate() },
                        onExportServers = {
                            val json = kotlinx.serialization.json.Json { prettyPrint = true }
                                .encodeToString(kotlinx.serialization.builtins.ListSerializer(
                                    com.clauderemote.model.SshServer.serializer()
                                ), serverStorage.loadServers())
                            onShareLog?.invoke(json)
                        },
                        onImportServers = onImportServers
                    )
                }

                Screen.LOG_VIEWER -> {
                    LogViewerScreen(
                        onBack = { currentScreen = Screen.LAUNCHER },
                        onShare = onShareLog
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
                },
                onPickKeyFile = onPickKeyFile
            )
        }

        // Close tab confirmation
        tabCloseConfirmId?.let { id ->
            val session = tabManager.getTab(id)
            AlertDialog(
                onDismissRequest = { tabCloseConfirmId = null },
                title = { Text("Close Session") },
                text = { Text("Disconnect from ${session?.server?.name ?: "server"}?") },
                confirmButton = {
                    TextButton(onClick = {
                        tabCloseConfirmId = null
                        scope.launch {
                            sessionOrchestrator.disconnectSession(id)
                            if (tabs.isEmpty()) currentScreen = Screen.LAUNCHER
                        }
                    }) { Text("Disconnect") }
                },
                dismissButton = {
                    TextButton(onClick = { tabCloseConfirmId = null }) { Text("Cancel") }
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