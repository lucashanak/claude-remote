package com.clauderemote.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    onPickFile: ((callback: (ByteArray, String) -> Unit) -> Unit)? = null,
    onApplyFontSize: ((Int) -> Unit)? = null,
    exitApp: (() -> Unit)? = null,
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
    var contextPercent by remember { mutableStateOf<Int?>(null) }
    var sessionUsagePercent by remember { mutableStateOf<Int?>(null) }
    var weekUsagePercent by remember { mutableStateOf<Int?>(null) }

    // Wire context and usage updates
    LaunchedEffect(Unit) {
        sessionOrchestrator.onContextUpdate = { _, pct -> contextPercent = pct }
        sessionOrchestrator.onUsageUpdate = { session, week ->
            if (session != null) sessionUsagePercent = session
            if (week != null) weekUsagePercent = week
        }
    }

    var serverList by remember { mutableStateOf(serverStorage.loadServers()) }
    val tabs by tabManager.tabs.collectAsState()
    val activeTabId by tabManager.activeTabId.collectAsState()

    // Remote tmux sessions discovered on servers
    var remoteSessions by remember { mutableStateOf<List<RemoteSession>>(emptyList()) }
    var remoteSessionsLoading by remember { mutableStateOf(false) }

    fun scanRemoteSessions() {
        val servers = serverList
        if (servers.isEmpty()) return
        scope.launch {
            remoteSessionsLoading = true
            try {
                val results = withContext(Dispatchers.IO) {
                    servers.map { server ->
                        async {
                            try {
                                val jsch = com.jcraft.jsch.JSch()
                                if (server.authMethod == AuthMethod.KEY && server.privateKey != null) {
                                    jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
                                }
                                val sess = jsch.getSession(server.username, server.host, server.port)
                                if (server.authMethod == AuthMethod.PASSWORD && server.password != null) {
                                    sess.setPassword(server.password)
                                }
                                sess.setConfig("StrictHostKeyChecking", "no")
                                sess.timeout = 5000
                                sess.connect(5000)
                                val sessions = TmuxManager.listSessions(sess)
                                sess.disconnect()
                                sessions.map { RemoteSession(server, it) }
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }
                remoteSessions = results
            } catch (_: Exception) {
                remoteSessions = emptyList()
            }
            remoteSessionsLoading = false
        }
    }

    // Scan remote sessions when launcher is shown
    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.LAUNCHER) {
            scanRemoteSessions()
        }
    }

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
    var showExitConfirm by remember { mutableStateOf(false) }
    PlatformBackHandler(enabled = true) {
        if (currentScreen != Screen.LAUNCHER) {
            currentScreen = Screen.LAUNCHER
        } else {
            showExitConfirm = true
        }
    }
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("Exit app?") },
            text = { Text("Active sessions will continue running in tmux.") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    // Let the system handle the back press (exit)
                    exitApp?.invoke()
                }) { Text("Exit") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("Cancel") }
            }
        )
    }

    fun refreshServers() {
        serverList = serverStorage.loadServers()
    }

    fun downloadUpdate(info: UpdateInfo) {
        scope.launch {
            try {
                // Choose platform-appropriate asset URL
                // Android: always APK. Desktop: prefer DMG, fallback to APK.
                val onAndroid = try { Class.forName("android.os.Build"); true } catch (_: Exception) { false }
                val downloadUrl = if (onAndroid) info.apkUrl else info.dmgUrl.ifBlank { info.apkUrl }
                if (downloadUrl.isBlank()) {
                    updateState = updateState.copy(error = "No update available for this platform")
                    return@launch
                }

                updateState = updateState.copy(downloading = true, error = null, statusText = "Downloading...")

                val bytes = UpdateChecker.downloadFile(downloadUrl) { progress, dl, total ->
                    updateState = updateState.copy(
                        progress = progress,
                        statusText = "Downloading ${UpdateChecker.formatBytes(dl)} / ${UpdateChecker.formatBytes(total)}"
                    )
                }

                if (info.apkSha256 != null && downloadUrl == info.apkUrl) {
                    val actualHash = UpdateChecker.sha256(bytes)
                    if (actualHash != info.apkSha256) {
                        updateState = updateState.copy(
                            downloading = false,
                            error = "Hash mismatch - download corrupted"
                        )
                        return@launch
                    }
                }

                updateState = updateState.copy(statusText = "Installing v${info.version}...", progress = 100)
                onInstallUpdate?.invoke(bytes, info)
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
        val insets = if (currentScreen == Screen.TERMINAL) {
            WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                .union(WindowInsets.ime)
        } else {
            WindowInsets.systemBars.union(WindowInsets.ime)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(insets)
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
                        remoteSessions = remoteSessions,
                        remoteSessionsLoading = remoteSessionsLoading,
                        onRefreshRemote = { scanRemoteSessions() },
                        onAttachRemote = { remote ->
                            scope.launch {
                                try {
                                    connectionError = null
                                    // Parse folder and mode from tmux session name
                                    // Format: claude-{server}-{folder}[-yolo]
                                    val prefix = "claude-${remote.server.name}-"
                                    var remainder = if (remote.tmuxSession.name.startsWith(prefix)) {
                                        remote.tmuxSession.name.removePrefix(prefix)
                                    } else remote.tmuxSession.name
                                    val isYolo = remainder.endsWith("-yolo")
                                    if (isYolo) remainder = remainder.removeSuffix("-yolo")
                                    val folderFromTmux = remainder.ifBlank { remote.server.defaultFolder }
                                    val modeFromTmux = if (isYolo) ClaudeMode.YOLO else remote.server.defaultClaudeMode
                                    sessionOrchestrator.launchSession(
                                        server = remote.server,
                                        folder = folderFromTmux,
                                        mode = modeFromTmux,
                                        model = remote.server.defaultClaudeModel,
                                        connectionType = ConnectionType.SSH,
                                        tmuxSessionName = remote.tmuxSession.name,
                                        isNewTmuxSession = false
                                    )
                                    currentScreen = Screen.TERMINAL
                                } catch (e: Exception) {
                                    connectionError = e.message
                                }
                            }
                        },
                        onQuickConnect = { server ->
                            // Long-press: connect directly with defaults
                            scope.launch {
                                try {
                                    connectionError = null
                                    val yoloSuffix = if (server.defaultClaudeMode == ClaudeMode.YOLO) "-yolo" else ""
                                    sessionOrchestrator.launchSession(
                                        server = server,
                                        folder = server.defaultFolder,
                                        mode = server.defaultClaudeMode,
                                        model = server.defaultClaudeModel,
                                        connectionType = ConnectionType.SSH,
                                        tmuxSessionName = "claude-${server.name}${yoloSuffix}",
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
                            appSettings = appSettings,
                            onBack = { currentScreen = Screen.LAUNCHER },
                            onBrowseFolders = { path ->
                                withContext(Dispatchers.IO) {
                                    try {
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
                                        val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
                                        ch.setCommand("ls -1d ${path.trimEnd('/')}/*/ 2>/dev/null | head -50")
                                        ch.inputStream = null
                                        val input = ch.inputStream
                                        ch.connect(5000)
                                        val output = input.bufferedReader().readText()
                                        ch.disconnect()
                                        sess.disconnect()
                                        output.lines().filter { it.isNotBlank() }.map { it.trimEnd('/') }
                                    } catch (_: Exception) {
                                        emptyList()
                                    }
                                }
                            },
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
                                    if (tabManager.tabs.value.isEmpty()) currentScreen = Screen.LAUNCHER
                                }
                            }
                        },
                        onNewTab = { currentScreen = Screen.LAUNCHER },
                        onMenuOpen = { currentScreen = Screen.LAUNCHER },
                        onSendCommand = { cmd ->
                            activeTabId?.let { sessionOrchestrator.sendClaudeCommand(it, cmd) }
                        },
                        onAttachFile = if (onPickFile != null) {
                            suspend attachFile@{
                                val id = activeTabId ?: return@attachFile null
                                val deferred = CompletableDeferred<Pair<ByteArray, String>?>()
                                onPickFile { bytes, name -> deferred.complete(bytes to name) }
                                val result = deferred.await() ?: return@attachFile null
                                val (bytes, name) = result
                                if (bytes.isEmpty() || name.isEmpty()) return@attachFile null
                                try {
                                    withContext(Dispatchers.IO) {
                                        sessionOrchestrator.uploadFile(id, bytes, name)
                                    }
                                } catch (e: Exception) {
                                    FileLogger.error("App", "File upload failed", e)
                                    null
                                }
                            }
                        } else null,
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
                        onFontSizeChange = { size ->
                            appSettings.terminalFontSize = size
                            onApplyFontSize?.invoke(size)
                        },
                        remoteSessions = remoteSessions,
                        onAttachRemote = { remote ->
                            scope.launch {
                                try {
                                    val prefix = "claude-${remote.server.name}-"
                                    var remainder = if (remote.tmuxSession.name.startsWith(prefix))
                                        remote.tmuxSession.name.removePrefix(prefix) else remote.tmuxSession.name
                                    val isYolo = remainder.endsWith("-yolo")
                                    if (isYolo) remainder = remainder.removeSuffix("-yolo")
                                    val folder = remainder.ifBlank { remote.server.defaultFolder }
                                    val mode = if (isYolo) ClaudeMode.YOLO else remote.server.defaultClaudeMode
                                    sessionOrchestrator.launchSession(
                                        server = remote.server, folder = folder, mode = mode,
                                        model = remote.server.defaultClaudeModel,
                                        connectionType = ConnectionType.SSH,
                                        tmuxSessionName = remote.tmuxSession.name,
                                        isNewTmuxSession = false
                                    )
                                } catch (_: Exception) {}
                            }
                        },
                        contextPercent = contextPercent,
                        sessionUsagePercent = sessionUsagePercent,
                        weekUsagePercent = weekUsagePercent,
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
                            if (tabManager.tabs.value.isEmpty()) currentScreen = Screen.LAUNCHER
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