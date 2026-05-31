package com.clauderemote.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeMode
import com.clauderemote.ui.theme.CRType
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.clauderemote.connection.TmuxManager
import com.clauderemote.model.*
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.storage.AppSettings
import com.clauderemote.storage.ServerStorage
import com.clauderemote.ui.theme.AppearanceState
import com.clauderemote.ui.theme.CRTheme
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
    LAUNCHER, CONNECT, TERMINAL, SETTINGS, LOG_VIEWER, USAGE_DASHBOARD, HISTORY
}

@Composable
fun App(
    serverStorage: ServerStorage,
    appSettings: AppSettings,
    tabManager: TabManager,
    sessionOrchestrator: SessionOrchestrator,
    appVersion: String = "1.0.0",
    onInstallUpdate: ((ByteArray, UpdateInfo) -> Unit)? = null,
    onGetCurrentApk: (() -> ByteArray)? = null,
    onShareLog: ((String) -> Unit)? = null,
    onTerminalScreenVisible: (() -> Unit)? = null,
    onPickKeyFile: ((callback: (String) -> Unit) -> Unit)? = null,
    onImportServers: (() -> Unit)? = null,
    onPickFile: ((callback: (List<Pair<ByteArray, String>>) -> Unit) -> Unit)? = null,
    onSaveFile: ((bytes: ByteArray, suggestedName: String) -> Unit)? = null,
    onApplyFontSize: ((Int) -> Unit)? = null,
    onShowNativeMenu: (() -> Unit)? = null,
    onNativeRenameDialog: ((sessionId: String, currentAlias: String) -> Unit)? = null,
    onNativeCloseConfirm: ((sessionId: String) -> Unit)? = null,
    sshKeyManager: com.clauderemote.connection.SshKeyManager? = null,
    exitApp: (() -> Unit)? = null,
    onInvertColorsChanged: ((Boolean) -> Unit)? = null,
    terminalScrolledUp: Boolean = false,
    terminalPendingOutput: Boolean = false,
    onJumpToLatest: (() -> Unit)? = null,
    terminalContent: @Composable (modifier: Modifier) -> Unit,
    // #75: keep emulator composed under the Chat overlay so screenReader works in
    // Chat. Android single-pane passes true; desktop stays false (SwingPanel bleeds
    // through a Compose overlay).
    composeTerminalUnderTranscript: Boolean = false
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
    // Long-press session context menu (mobile): which session it's open for.
    var sessionMenuId by remember { mutableStateOf<String?>(null) }
    // Rename dialog: which session is being renamed.
    var renameSessionId by remember { mutableStateOf<String?>(null) }
    var invertColors by remember { mutableStateOf(appSettings.invertColors) }

    // Collect new StateFlows from orchestrator
    val sessionActivities by sessionOrchestrator.sessionActivities.collectAsState()
    val hookActiveSessions by sessionOrchestrator.hookActiveSessions.collectAsState()
    val contextPercents by sessionOrchestrator.contextPercents.collectAsState()
    val sessionUsagePercents by sessionOrchestrator.sessionUsagePercents.collectAsState()
    val weekUsagePercents by sessionOrchestrator.weekUsagePercents.collectAsState()
    val sessionResetMin by sessionOrchestrator.sessionResetMin.collectAsState()
    val weekResetMin by sessionOrchestrator.weekResetMin.collectAsState()
    val latencies by sessionOrchestrator.latencies.collectAsState()
    val gitStatuses by sessionOrchestrator.gitStatuses.collectAsState()
    val pendingCounts by sessionOrchestrator.pendingCounts.collectAsState()
    val serverHealth by sessionOrchestrator.serverHealth.collectAsState()

    var serverList by remember { mutableStateOf(serverStorage.loadServers()) }
    val tabs by tabManager.tabs.collectAsState()
    val activeTabId by tabManager.activeTabId.collectAsState()
    // Server of the active tab — usage chips (5h/wk) are keyed by server.
    val activeServerId = tabs.firstOrNull { it.id == activeTabId }?.server?.id

    // ── Pane grid (Phase 1, low-risk) ──────────────────────────────────────
    var gridLayout by remember { mutableStateOf(GridLayout.ONE) }
    var paneSessions by remember { mutableStateOf(listOf<String?>(null, null, null, null)) }
    // FIX 1: Focus is tracked by PANE INDEX, not session id match, so the
    // single shared raw terminal is guaranteed to be hosted in exactly one cell.
    var focusedPaneIndex by remember { mutableStateOf(0) }

    // FIX 3: Purge dead session ids when tabs change.
    LaunchedEffect(tabs) {
        val validIds = tabs.map { it.id }.toSet()
        paneSessions = paneSessions.map { sid -> if (sid != null && sid !in validIds) null else sid }
        // focusedPaneIndex is left wherever it is; that cell will show the picker.
    }
    // FIX 4: Reset grid entirely when tab count drops to 1 (can't split a
    // single session) — prevents stale TWO/QUAD resurfacing on next open.
    LaunchedEffect(tabs.size) {
        if (tabs.size <= 1) {
            gridLayout = GridLayout.ONE
            paneSessions = listOf(null, null, null, null)
            focusedPaneIndex = 0
        }
    }

    // FIX 2: Per-pane transcript collection keyed on BOTH sid AND claudeSessionId
    // so UUID rotation (/clear, /compact, /resume, first null→real reconcile)
    // restarts the flow against the new .jsonl — matching the single-pane logic.
    // Exactly 4 unconditional call sites; the helper makes each one invariant
    // (empty slot → empty list) without varying the Compose hook count.
    @Composable
    fun rememberPaneTranscript(sid: String?): List<com.clauderemote.session.transcript.TranscriptEntry> {
        val claudeUuid = sid?.let { id -> tabs.firstOrNull { it.id == id }?.claudeSessionId }
        val flow = remember(sid, claudeUuid) {
            if (sid != null) sessionOrchestrator.transcriptFlow(sid)
            else kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        }
        return flow.collectAsState().value
    }
    val pane0Tx = rememberPaneTranscript(paneSessions[0])
    val pane1Tx = rememberPaneTranscript(paneSessions[1])
    val pane2Tx = rememberPaneTranscript(paneSessions[2])
    val pane3Tx = rememberPaneTranscript(paneSessions[3])
    val paneTranscripts = listOf(pane0Tx, pane1Tx, pane2Tx, pane3Tx)

    // Remote tmux sessions discovered on servers
    var remoteSessions by remember { mutableStateOf<List<RemoteSession>>(emptyList()) }
    var remoteSessionsLoading by remember { mutableStateOf(false) }

    // Past Claude conversations discovered from server transcripts (history browser)
    var historySessions by remember { mutableStateOf<List<ClaudeHistorySession>>(emptyList()) }
    var historyLoading by remember { mutableStateOf(false) }
    var historyTotalCount by remember { mutableStateOf(0) }

    fun scanHistory() {
        val servers = serverList
        if (servers.isEmpty()) {
            historySessions = emptyList()
            historyTotalCount = 0
            return
        }
        scope.launch {
            historyLoading = true
            try {
                val results = withContext(Dispatchers.IO) {
                    servers.map { server ->
                        async {
                            try {
                                com.clauderemote.session.ClaudeHistoryScanner.scan(server)
                            } catch (_: Exception) {
                                com.clauderemote.session.ClaudeHistoryScanner.ScanResult(emptyList(), 0)
                            }
                        }
                    }.awaitAll()
                }
                historySessions = results.flatMap { it.sessions }
                    .sortedByDescending { it.lastModifiedEpoch }
                historyTotalCount = results.sumOf { it.totalCount }
                if (historyTotalCount > historySessions.size) {
                    FileLogger.log("App", "History: showing ${historySessions.size} of $historyTotalCount transcripts (capped)")
                }
            } catch (_: Exception) {
                historySessions = emptyList()
                historyTotalCount = 0
            }
            historyLoading = false
        }
    }

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
                                com.clauderemote.connection.SshSessionHelper.withSession(server, 5000) { sess ->
                                    TmuxManager.listSessions(sess).map { RemoteSession(server, it) }
                                }
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }
                remoteSessions = results
                // Prune disconnected tabs whose tmux session no longer
                // exists on the server — prevents stale entries in side panel
                val remoteTmuxNames = results.map { it.tmuxSession.name }.toSet()
                val staleTabs = tabManager.tabs.value.filter { tab ->
                    tab.status != SessionStatus.ACTIVE &&
                        tab.tmuxSessionName.isNotBlank() &&
                        tab.tmuxSessionName !in remoteTmuxNames
                }
                staleTabs.forEach { tab ->
                    FileLogger.log("App", "Pruning stale tab ${tab.id} (tmux ${tab.tmuxSessionName})")
                    // forgetSession removes from SessionStorage and re-syncs the
                    // server-side sessions.json so the systemd restore service
                    // doesn't try to re-materialise this tab on next reboot.
                    scope.launch { sessionOrchestrator.forgetSession(tab.id) }
                }
            } catch (_: Exception) {
                remoteSessions = emptyList()
            }
            remoteSessionsLoading = false
        }
    }

    // When a tab is permanently closed, drop the matching entry from the
    // remote-tmux snapshot immediately — otherwise the killed pane (no longer
    // an attached tab) resurfaces as a "detached remote" row until the next
    // 30s scan, and tapping it would launch a brand-new empty session.
    LaunchedEffect(Unit) {
        sessionOrchestrator.onSessionForgotten = { serverId, tmuxName ->
            remoteSessions = remoteSessions.filterNot {
                it.server.id == serverId && it.tmuxSession.name == tmuxName
            }
        }
    }

    // Scan remote sessions on startup and whenever launcher is shown
    LaunchedEffect(Unit) { scanRemoteSessions() }
    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.LAUNCHER) scanRemoteSessions()
    }
    // Scan transcript history whenever the history browser is opened.
    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.HISTORY) scanHistory()
    }
    // Periodically refresh remote sessions while on terminal screen
    // so the side panel stays up-to-date
    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.TERMINAL) {
            while (true) {
                kotlinx.coroutines.delay(30_000) // every 30s
                scanRemoteSessions()
            }
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
                val onAndroid = try { Class.forName("android.os.Build"); true } catch (_: Exception) { false }
                val usePatch = onAndroid && info.hasPatch && onGetCurrentApk != null

                if (!usePatch) {
                    // Full download path (desktop or no patches available)
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
                } else {
                    // Patch update path
                    updateState = updateState.copy(downloading = true, error = null, statusText = "Reading current APK...")

                    val currentApk = withContext(Dispatchers.IO) { onGetCurrentApk!!() }
                    var apkBytes = currentApk
                    val totalSteps = info.patchChain.size
                    val totalPatchBytes = info.totalPatchSize
                    var downloadedSoFar = 0L

                    for ((idx, step) in info.patchChain.withIndex()) {
                        updateState = updateState.copy(
                            statusText = "Patch ${idx + 1}/$totalSteps: ${step.from} → ${step.to}",
                            progress = if (totalPatchBytes > 0) ((downloadedSoFar * 100) / totalPatchBytes).toInt() else 0
                        )

                        val patchBytes = UpdateChecker.downloadFile(step.url) { _, dl, _ ->
                            val totalDl = downloadedSoFar + dl
                            updateState = updateState.copy(
                                progress = if (totalPatchBytes > 0) ((totalDl * 100) / totalPatchBytes).toInt() else 0,
                                statusText = "Patch ${idx + 1}/$totalSteps: ${UpdateChecker.formatBytes(totalDl)} / ${UpdateChecker.formatBytes(totalPatchBytes)}"
                            )
                        }
                        downloadedSoFar += step.size

                        updateState = updateState.copy(statusText = "Applying patch ${idx + 1}/$totalSteps...")
                        apkBytes = withContext(Dispatchers.IO) {
                            UpdateChecker.applyPatch(apkBytes, patchBytes)
                        }
                    }

                    // Verify final APK hash
                    if (info.apkSha256 != null) {
                        val actualHash = UpdateChecker.sha256(apkBytes)
                        if (actualHash != info.apkSha256) {
                            updateState = updateState.copy(
                                downloading = false,
                                error = "Patch result hash mismatch - falling back to full download"
                            )
                            // Fallback: retry with full APK
                            val fallbackInfo = info.copy(patchChain = emptyList())
                            downloadUpdate(fallbackInfo)
                            return@launch
                        }
                    }

                    updateState = updateState.copy(statusText = "Installing v${info.version}...", progress = 100)
                    onInstallUpdate?.invoke(apkBytes, info)
                }
            } catch (e: Exception) {
                FileLogger.error("App", "Update failed", e)
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

    var appearance by remember { mutableStateOf(appSettings.loadAppearance()) }
    val updateAppearance: (AppearanceState) -> Unit = { next ->
        appearance = next
        appSettings.saveAppearance(next)
    }

    CRTheme(appearance = appearance) {
    ClaudeRemoteTheme(darkTheme = darkTheme) {
        val insets = if (currentScreen == Screen.TERMINAL) {
            WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                .union(WindowInsets.ime)
        } else {
            WindowInsets.systemBars.union(WindowInsets.ime)
        }
        val invertModifier = if (invertColors && !isMobile) {
            Modifier.drawWithContent {
                val paint = Paint().apply {
                    colorFilter = ColorFilter.colorMatrix(
                        ColorMatrix(floatArrayOf(
                            -1f, 0f, 0f, 0f, 255f,
                            0f, -1f, 0f, 0f, 255f,
                            0f, 0f, -1f, 0f, 255f,
                            0f, 0f, 0f, 1f, 0f,
                        ))
                    )
                }
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(Offset.Zero, size), paint)
                    drawContent()
                    canvas.restore()
                }
            }
        } else Modifier
        Box(modifier = Modifier.fillMaxSize().then(invertModifier)) {
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
                        serverHealth = serverHealth,
                        onProbeServers = { force -> sessionOrchestrator.probeServers(serverList, force) },
                        activeSessions = tabs,
                        sessionActivities = sessionActivities,
                        remoteSessions = remoteSessions,
                        remoteSessionsLoading = remoteSessionsLoading,
                        onRefreshRemote = { scanRemoteSessions() },
                        onConnectAll = {
                            // Connect everything in the list so all sessions' data
                            // (terminal, usage, activity, transcript) loads after an
                            // app restart. Sequential to avoid a connection storm.
                            scope.launch {
                                // 1) Reconnect restored-but-disconnected tabs.
                                tabs.filter { it.status != SessionStatus.ACTIVE }
                                    .forEach { tab ->
                                        try {
                                            sessionOrchestrator.reconnectSession(tab.id)
                                        } catch (_: Exception) {}
                                    }
                                // 2) Attach remote tmux sessions that aren't open as
                                //    a tab yet (the "1w" entries discovered on the
                                //    server) — reconnectSession only covers tabs.
                                val openTmux = tabManager.tabs.value
                                    .map { it.tmuxSessionName }.toSet()
                                remoteSessions
                                    .filter { it.tmuxSession.name !in openTmux }
                                    .forEach { remote ->
                                        try {
                                            val parsed = TmuxNameParser.parse(
                                                remote.tmuxSession.name, remote.server.name
                                            )
                                            sessionOrchestrator.launchSession(
                                                server = remote.server,
                                                folder = parsed.folder,
                                                mode = if (parsed.isYolo) ClaudeMode.YOLO
                                                    else remote.server.defaultClaudeMode,
                                                model = remote.server.defaultClaudeModel,
                                                connectionType = ConnectionType.SSH,
                                                tmuxSessionName = remote.tmuxSession.name,
                                                isNewTmuxSession = false,
                                            )
                                        } catch (_: Exception) {}
                                    }
                            }
                        },
                        onAttachRemote = { remote ->
                            scope.launch {
                                try {
                                    connectionError = null
                                    val parsed = TmuxNameParser.parse(remote.tmuxSession.name, remote.server.name)
                                    sessionOrchestrator.launchSession(
                                        server = remote.server,
                                        folder = parsed.folder,
                                        mode = if (parsed.isYolo) ClaudeMode.YOLO else remote.server.defaultClaudeMode,
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
                                    sessionOrchestrator.launchSession(
                                        server = server,
                                        folder = server.defaultFolder,
                                        mode = server.defaultClaudeMode,
                                        model = server.defaultClaudeModel,
                                        connectionType = ConnectionType.SSH,
                                        tmuxSessionName = TmuxNameParser.build(server.name, server.defaultFolder, server.defaultClaudeMode == ClaudeMode.YOLO),
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
                                    tmuxSessions = com.clauderemote.connection.SshSessionHelper.withSession(server) { sess ->
                                        TmuxManager.listSessions(sess)
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
                            sessionOrchestrator.probeServers(serverList, force = true)
                        },
                        onDeleteServer = { server ->
                            serverStorage.deleteServer(server.id)
                            refreshServers()
                            sessionOrchestrator.pruneServerHealth(server.id)
                        },
                        onToggleFavorite = { server ->
                            serverStorage.updateServer(server.copy(favorite = !server.favorite))
                            refreshServers()
                        },
                        onResumeSession = { session ->
                            sessionOrchestrator.switchTab(session.id)
                            currentScreen = Screen.TERMINAL
                        },
                        onSessionLongPress = { session -> sessionMenuId = session.id },
                        onSettings = { currentScreen = Screen.SETTINGS },
                        onViewLog = { currentScreen = Screen.LOG_VIEWER },
                        onHistory = { currentScreen = Screen.HISTORY },
                        onUsageDashboard = { currentScreen = Screen.USAGE_DASHBOARD },
                        onCheckUpdate = { checkForUpdate() },
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
                                try {
                                    com.clauderemote.connection.SshSessionHelper.withSession(server) { sess ->
                                        val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
                                        ch.setCommand("ls -1d ${path.trimEnd('/')}/*/ 2>/dev/null | head -50")
                                        ch.inputStream = null
                                        val input = ch.inputStream
                                        ch.connect(5000)
                                        val output = input.bufferedReader().readText()
                                        ch.disconnect()
                                        output.lines().filter { it.isNotBlank() }.map { it.trimEnd('/') }
                                    }
                                } catch (_: Exception) {
                                    emptyList()
                                }
                            },
                            onKillTmux = { sessionName ->
                                scope.launch {
                                    try {
                                        tmuxSessions = com.clauderemote.connection.SshSessionHelper.withSession(server) { sess ->
                                            TmuxManager.killSession(sess, sessionName)
                                            TmuxManager.listSessions(sess)
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
                    // Active session's live transcript (collected once; shared by
                    // the chat view and the #70 awaiting-choice detection below).
                    val activeTranscript: List<com.clauderemote.session.transcript.TranscriptEntry> = activeTabId?.let { id ->
                        // Key on the Claude session UUID too, not just the tab
                        // id. transcriptFlow() only (re)starts the tail and
                        // fires the UUID kick-probe WHEN IT IS CALLED, and the
                        // remember block is the only caller. Keying on id alone
                        // meant a UUID rotation (/clear, /compact, /resume, or
                        // the first null→real reconcile) never re-invoked it, so
                        // the stream stayed pinned to the old/dead .jsonl and the
                        // chat only refreshed after an app restart. Re-keying on
                        // the UUID re-subscribes against the live file.
                        val claudeUuid = tabs.firstOrNull { it.id == id }?.claudeSessionId
                        val flow = remember(id, claudeUuid) { sessionOrchestrator.transcriptFlow(id) }
                        flow.collectAsState().value
                    } ?: emptyList()
                    // #70: Claude awaiting a choice on the ACTIVE session.
                    //  • AskUserQuestion (transcript tool) is the reliable trigger for
                    //    assistant-initiated questions.
                    //  • APPROVAL_NEEDED is now also emitted by ScreenStateClassifier
                    //    (#71) when a permission/selector dialog is detected on screen —
                    //    permission prompts are covered.
                    // FIX D: only count an AskUserQuestion as pending if the conversation
                    // has NOT moved on (no UserPrompt or AssistantText after the ask), so
                    // dead/abandoned sessions don't keep awaitingChoice stuck true.
                    val awaitingChoice = remember(activeTranscript, sessionActivities, activeTabId) {
                        val resultIds = activeTranscript
                            .filterIsInstance<com.clauderemote.session.transcript.TranscriptEntry.ToolResult>()
                            .mapNotNull { it.toolUseId }
                            .toSet()
                        val pendingAsk = hasPendingAskUserQuestion(activeTranscript, resultIds)
                        pendingAsk ||
                            activeTabId?.let { sessionActivities[it] } == SessionActivity.APPROVAL_NEEDED
                    }
                    // FIX B: per-pane awaiting-choice flags for the #58 grid. Same
                    // "moved-on" guard as awaitingChoice (FIX D) so abandoned panes
                    // don't badge forever. Badge rendered on non-focused panes only;
                    // focused pane keeps the existing full auto-switch behavior.
                    val panePendingAsk = remember(paneTranscripts) {
                        paneTranscripts.map { entries ->
                            val rIds = entries
                                .filterIsInstance<com.clauderemote.session.transcript.TranscriptEntry.ToolResult>()
                                .mapNotNull { it.toolUseId }
                                .toSet()
                            hasPendingAskUserQuestion(entries, rIds)
                        }
                    }
                    TerminalScreen(
                        tabs = tabs,
                        activeTabId = activeTabId,
                        invertColors = invertColors,
                        onToggleInvertColors = {
                            val next = !invertColors
                            invertColors = next
                            appSettings.invertColors = next
                            onInvertColorsChanged?.invoke(next)
                        },
                        onTabSwitch = { sessionOrchestrator.switchTab(it) },
                        onRenameSession = { id, newAlias ->
                            tabManager.updateAlias(id, newAlias)
                            // Also rename tmux session on server for cross-device sync
                            val tab = tabManager.getTab(id)
                            if (tab != null) {
                                val newTmuxName = TmuxNameParser.build(
                                    tab.server.name,
                                    tab.folder,
                                    tab.mode == ClaudeMode.YOLO,
                                    newAlias
                                )
                                scope.launch {
                                    try {
                                        val conn = sessionOrchestrator.getConnection(id)
                                        val sess = conn?.getSession()
                                        if (sess != null) {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
                                                ch.setCommand("tmux rename-session -t '${tab.tmuxSessionName.replace("'", "'\\''")}' '${newTmuxName.replace("'", "'\\''")}'")
                                                ch.connect(5000)
                                                ch.inputStream.bufferedReader().readText()
                                                ch.disconnect()
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        },
                        onShowNativeMenu = onShowNativeMenu,
                        onNativeRenameDialog = onNativeRenameDialog,
                        onReconnect = { id ->
                            scope.launch { sessionOrchestrator.reconnectSession(id) }
                        },
                        onTabClose = { id ->
                            // Always confirm — closing forgets the persisted
                            // session and kills the remote tmux pane, which
                            // is destructive enough that we don't want a
                            // single misclick to lose the conversation.
                            if (onNativeCloseConfirm != null) {
                                onNativeCloseConfirm.invoke(id)
                            } else {
                                tabCloseConfirmId = id
                            }
                        },
                        onSessionLongPress = { id -> sessionMenuId = id },
                        onNewTab = { currentScreen = Screen.LAUNCHER },
                        onMenuOpen = { currentScreen = Screen.LAUNCHER },
                        onSendCommand = { cmd ->
                            activeTabId?.let { sessionOrchestrator.sendClaudeCommand(it, cmd) }
                        },
                        onAttachFile = if (onPickFile != null) {
                            suspend attachFile@{
                                val id = activeTabId ?: return@attachFile null
                                val deferred = CompletableDeferred<List<Pair<ByteArray, String>>>()
                                onPickFile { files -> deferred.complete(files) }
                                // Hard timeout: if the picker never fires
                                // its callback (native dialog wedged, JBR
                                // bug, etc.), unstick the spinner after
                                // five minutes instead of hanging the UI
                                // forever. Long enough for a real user to
                                // pick a file with thought; short enough
                                // that a stuck dialog doesn't lock the +
                                // button permanently.
                                val files = kotlinx.coroutines.withTimeoutOrNull(5 * 60 * 1000L) {
                                    deferred.await()
                                } ?: run {
                                    FileLogger.log("App", "onAttachFile timed out waiting for picker")
                                    emptyList()
                                }
                                if (files.isEmpty()) return@attachFile null
                                val paths = files.mapNotNull { (bytes, name) ->
                                    if (bytes.isEmpty() || name.isEmpty()) return@mapNotNull null
                                    try {
                                        withContext(Dispatchers.IO) {
                                            sessionOrchestrator.uploadFile(id, bytes, name)
                                        }
                                    } catch (e: Exception) {
                                        FileLogger.error("App", "File upload failed: $name", e)
                                        null
                                    }
                                }
                                if (paths.isEmpty()) null else paths.joinToString("\n")
                            }
                        } else null,
                        onDownloadFile = { path ->
                            val id = activeTabId
                            if (id == null) {
                                null
                            } else {
                                // Resolve a relative path against the session folder so
                                // the user can type "output.png" instead of an absolute
                                // path; absolute paths are passed through untouched.
                                val folder = tabManager.getTab(id)?.folder ?: "~"
                                val remotePath = if (path.startsWith("/") || path.startsWith("~")) {
                                    path
                                } else {
                                    "${folder.trimEnd('/')}/$path"
                                }
                                sessionOrchestrator.downloadFile(id, remotePath)
                            }
                        },
                        onSaveFile = onSaveFile,
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
                        onSaveClaudeMd = { content ->
                            val id = activeTabId
                            if (id != null) {
                                val conn = sessionOrchestrator.getConnection(id)
                                val sess = conn?.getSession()
                                if (sess != null) {
                                    val folder = tabManager.getTab(id)?.folder ?: "~"
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        val sftp = sess.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
                                        sftp.connect(5000)
                                        try {
                                            sftp.put(content.toByteArray(Charsets.UTF_8).inputStream(), "$folder/CLAUDE.md")
                                        } finally {
                                            sftp.disconnect()
                                        }
                                    }
                                }
                            }
                        },
                        onSendEscape = {
                            activeTabId?.let { sessionOrchestrator.sendEscape(it) }
                        },
                        onPageUp = {
                            activeTabId?.let { sessionOrchestrator.tmuxScroll(it, up = true) }
                        },
                        onPageDown = {
                            activeTabId?.let { sessionOrchestrator.tmuxScroll(it, up = false) }
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
                                    val parsed = TmuxNameParser.parse(remote.tmuxSession.name, remote.server.name)
                                    sessionOrchestrator.launchSession(
                                        server = remote.server, folder = parsed.folder,
                                        mode = if (parsed.isYolo) ClaudeMode.YOLO else remote.server.defaultClaudeMode,
                                        model = remote.server.defaultClaudeModel,
                                        connectionType = ConnectionType.SSH,
                                        tmuxSessionName = remote.tmuxSession.name,
                                        isNewTmuxSession = false
                                    )
                                } catch (_: Exception) {}
                            }
                        },
                        sessionUsagePercent = activeServerId?.let { sessionUsagePercents[it] },
                        weekUsagePercent = activeServerId?.let { weekUsagePercents[it] },
                        sessionResetMin = activeServerId?.let { sessionResetMin[it] },
                        weekResetMin = activeServerId?.let { weekResetMin[it] },
                        sessionActivities = sessionActivities,
                        hookActiveSessions = hookActiveSessions,
                        contextPercent = activeTabId?.let { contextPercents[it] },
                        gitStatus = activeTabId?.let { gitStatuses[it] },
                        latencyMs = activeTabId?.let { latencies[it] },
                        pendingInputCount = activeTabId?.let { pendingCounts[it] } ?: 0,
                        onClearPending = activeTabId?.let { id ->
                            { sessionOrchestrator.clearPendingInputs(id) }
                        },
                        onNavigate = { target ->
                            currentScreen = when (target) {
                                "settings" -> Screen.SETTINGS
                                "dashboard" -> Screen.USAGE_DASHBOARD
                                "logs" -> Screen.LOG_VIEWER
                                "launcher" -> Screen.LAUNCHER
                                else -> Screen.LAUNCHER
                            }
                        },
                        onTerminalViewChange = { tv -> updateAppearance(appearance.copy(terminalView = tv)) },
                        terminalContent = terminalContent,
                        terminalScrolledUp = terminalScrolledUp,
                        terminalPendingOutput = terminalPendingOutput,
                        onJumpToLatest = onJumpToLatest,
                        transcriptEntries = activeTranscript,
                        awaitingChoice = awaitingChoice,
                        autoOpenTerminalOnPrompt = appSettings.autoOpenTerminalOnPrompt,
                        remoteStatus = activeTabId?.let { id ->
                            val flow = remember(id) { sessionOrchestrator.remoteStatusFlow(id) }
                            flow.collectAsState().value
                        },
                        onTerminalContentVisible = onTerminalScreenVisible,
                        activeClaudeSessionId = activeTabId?.let { id ->
                            tabs.firstOrNull { it.id == id }?.claudeSessionId
                        },
                        transcriptStatus = activeTabId?.let { id ->
                            val flow = remember(id) { sessionOrchestrator.transcriptStatusFlow(id) }
                            flow.collectAsState().value
                        },
                        sidePanelWidthDp = appSettings.sidePanelWidthDp,
                        onSidePanelWidthChange = { appSettings.sidePanelWidthDp = it },
                        gridLayout = gridLayout,
                        paneSessions = paneSessions,
                        paneTranscripts = paneTranscripts,
                        panePendingAsk = panePendingAsk,
                        focusedPaneIndex = focusedPaneIndex,
                        onSetLayout = { layout ->
                            gridLayout = layout
                            focusedPaneIndex = 0
                            if (layout == GridLayout.ONE) {
                                // Back to single pane: raw terminal shows activeTabId.
                                paneSessions = listOf(null, null, null, null)
                            } else {
                                // Auto-fill: pane 0 = active tab, remaining from
                                // the first other tabs, extra slots stay empty.
                                val active = activeTabId
                                val others = tabs.map { it.id }.filter { it != active }
                                val filled = mutableListOf<String?>(active)
                                for (i in 1 until 4) filled.add(others.getOrNull(i - 1))
                                paneSessions = filled
                            }
                        },
                        // FIX 1: index-aware focus — switch raw terminal only when
                        // the chosen pane holds a different session.
                        onFocusPane = { index, sid ->
                            focusedPaneIndex = index
                            if (sid != null && sid != activeTabId) {
                                sessionOrchestrator.switchTab(sid)
                            }
                        },
                        // FIX 1 + dedup: if the chosen sid is already in another
                        // pane, clear that other pane to prevent two cells sharing
                        // the same session (and both rendering terminalContent).
                        onAssignPane = { index, sid ->
                            val current = paneSessions.toMutableList()
                            // Clear any existing pane that already holds this sid.
                            for (j in current.indices) {
                                if (j != index && current[j] == sid) current[j] = null
                            }
                            if (index in current.indices) current[index] = sid
                            paneSessions = current
                        },
                        composeTerminalUnderTranscript = composeTerminalUnderTranscript,
                    )
                }

                Screen.SETTINGS -> {
                    SettingsScreen(
                        settings = appSettings,
                        appVersion = appVersion,
                        sshKeyManager = sshKeyManager,
                        appearance = appearance,
                        onAppearanceChange = updateAppearance,
                        onBack = { currentScreen = Screen.LAUNCHER },
                        onCheckUpdate = { checkForUpdate() },
                        onExportServers = {
                            val json = kotlinx.serialization.json.Json { prettyPrint = true }
                                .encodeToString(kotlinx.serialization.builtins.ListSerializer(
                                    com.clauderemote.model.SshServer.serializer()
                                ), serverStorage.loadServers())
                            onShareLog?.invoke(json)
                        },
                        onImportServers = onImportServers,
                        onViewLog = { currentScreen = Screen.LOG_VIEWER }
                    )
                }

                Screen.LOG_VIEWER -> {
                    LogViewerScreen(
                        onBack = { currentScreen = Screen.LAUNCHER },
                        onShare = onShareLog
                    )
                }

                Screen.USAGE_DASHBOARD -> {
                    val usageTokensState by sessionOrchestrator.usageTokens.collectAsState()
                    UsageDashboardScreen(
                        sessions = tabs,
                        sessionActivities = sessionActivities,
                        contextPercents = contextPercents,
                        sessionUsagePercent = activeServerId?.let { sessionUsagePercents[it] },
                        weekUsagePercent = activeServerId?.let { weekUsagePercents[it] },
                        usageTokens = usageTokensState,
                        onBack = { currentScreen = Screen.LAUNCHER }
                    )
                }

                Screen.HISTORY -> {
                    // Live detection: uuid-only (primary). Cwd-basename heuristic
                    // removed — it caused false-LIVE when unrelated projects share a
                    // folder name, and false-Resume when the same uuid appeared under
                    // two encoded-cwd dirs.
                    val liveUuids = tabs.mapNotNull { it.claudeSessionId }.toSet()
                    HistoryScreen(
                        sessions = historySessions,
                        loading = historyLoading,
                        liveUuids = liveUuids,
                        totalCount = historyTotalCount,
                        onBack = { currentScreen = Screen.LAUNCHER },
                        onRefresh = { scanHistory() },
                        onResume = { hist ->
                            scope.launch {
                                try {
                                    connectionError = null
                                    val tmuxSessionName = TmuxNameParser.build(
                                        hist.server.name, hist.cwd, isYolo = false
                                    )
                                    // FIX 2: guard against killing an unrelated live session.
                                    // TmuxNameParser.build uses only the folder basename, so
                                    // two projects with the same basename produce the same
                                    // tmux name. Probe first: if a session exists with a
                                    // different cwd, abort rather than kill it.
                                    val paneMatch = sessionOrchestrator.tmuxPaneMatchesCwd(
                                        hist.server, tmuxSessionName, hist.cwd
                                    )
                                    when (paneMatch) {
                                        false -> {
                                            // Collision: a DIFFERENT live session owns that name.
                                            connectionError =
                                                "A different session is already running as '$tmuxSessionName'. " +
                                                "Rename or close it first."
                                        }
                                        true -> {
                                            // Same cwd — session is already there; just attach.
                                            sessionOrchestrator.launchSession(
                                                server = hist.server,
                                                folder = hist.cwd,
                                                mode = hist.server.defaultClaudeMode,
                                                model = hist.server.defaultClaudeModel,
                                                connectionType = ConnectionType.SSH,
                                                tmuxSessionName = tmuxSessionName,
                                                isNewTmuxSession = false,
                                                resumeClaudeSessionId = hist.uuid
                                            )
                                            currentScreen = Screen.TERMINAL
                                        }
                                        null -> {
                                            // No existing session — safe to create & resume.
                                            sessionOrchestrator.launchSession(
                                                server = hist.server,
                                                folder = hist.cwd,
                                                mode = hist.server.defaultClaudeMode,
                                                model = hist.server.defaultClaudeModel,
                                                connectionType = ConnectionType.SSH,
                                                tmuxSessionName = tmuxSessionName,
                                                isNewTmuxSession = false,
                                                resumeClaudeSessionId = hist.uuid
                                            )
                                            currentScreen = Screen.TERMINAL
                                        }
                                    }
                                } catch (e: Exception) {
                                    connectionError = e.message
                                }
                            }
                        },
                        onAttachLive = { hist ->
                            // Switch to the already-open tab (uuid matched).
                            val tab = tabs.firstOrNull { it.claudeSessionId == hist.uuid }
                            if (tab != null) {
                                sessionOrchestrator.switchTab(tab.id)
                                currentScreen = Screen.TERMINAL
                            }
                            // No remote-tmux fallback here: if uuid is in liveUuids
                            // there must be an open tab. If none, fall through silently
                            // (stale snapshot — next scan will correct it).
                        },
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
                    sessionOrchestrator.probeServers(serverList, force = true)
                    showServerDialog = false
                },
                onPickKeyFile = onPickKeyFile,
                onDelete = { server ->
                    serverStorage.deleteServer(server.id)
                    refreshServers()
                    sessionOrchestrator.pruneServerHealth(server.id)
                    showServerDialog = false
                }
            )
        }

        // Close tab confirmation
        tabCloseConfirmId?.let { id ->
            val session = tabManager.getTab(id)
            AlertDialog(
                onDismissRequest = { tabCloseConfirmId = null },
                title = { Text("Close Session") },
                text = { Text("Permanently close session on ${session?.server?.name ?: "server"}? The tmux pane will be killed and the conversation removed from your tab list.") },
                confirmButton = {
                    TextButton(onClick = {
                        tabCloseConfirmId = null
                        scope.launch {
                            sessionOrchestrator.forgetSession(id)
                            if (tabManager.tabs.value.isEmpty()) currentScreen = Screen.LAUNCHER
                        }
                    }) { Text("Close") }
                },
                dismissButton = {
                    TextButton(onClick = { tabCloseConfirmId = null }) { Text("Cancel") }
                }
            )
        }

        // Session long-press context menu (mobile). Desktop uses its native
        // right-click menu instead. Styled to match the CR design system.
        sessionMenuId?.let { id ->
            val session = tabManager.getTab(id)
            SessionContextSheet(
                title = session?.displayLabel ?: "Session",
                subtitle = "${session?.server?.name ?: ""} · ${session?.folder ?: ""}",
                status = (sessionActivities[id] ?: SessionActivity.IDLE).toMenuCRStatus(),
                // Rename needs a live connection to rename the server-side tmux;
                // offline it'd only change a local alias that's lost on restart
                // and would desync from the tmux name. So offer it only when
                // connected — offline sessions get Reconnect.
                canRename = session?.status == com.clauderemote.model.SessionStatus.ACTIVE,
                onRename = { sessionMenuId = null; renameSessionId = id },
                onReconnect = {
                    sessionMenuId = null
                    scope.launch { sessionOrchestrator.reconnectSession(id) }
                },
                onClose = { sessionMenuId = null; tabCloseConfirmId = id },
                onDismiss = { sessionMenuId = null },
            )
        }

        // Rename session dialog (mobile / shared). Renames the alias + the
        // server-side tmux session so it survives reconnect/reboot.
        renameSessionId?.let { id ->
            val session = tabManager.getTab(id)
            RenameSessionDialog(
                initialAlias = session?.alias ?: "",
                onConfirm = { trimmed ->
                    renameSessionId = null
                    val tab = tabManager.getTab(id) ?: return@RenameSessionDialog
                    tabManager.updateAlias(id, trimmed)
                    val newTmux = com.clauderemote.model.TmuxNameParser.build(
                        tab.server.name, tab.folder,
                        tab.mode == ClaudeMode.YOLO, trimmed
                    )
                    scope.launch {
                        sessionOrchestrator.renameTmuxSession(id, tab.tmuxSessionName, newTmux)
                    }
                },
                onDismiss = { renameSessionId = null },
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
        } // end Box
    }
    }
}

/**
 * True if [entries] contains an AskUserQuestion ToolCall whose tool_use_id is
 * not in [resultIds] AND no UserPrompt or AssistantText appears AFTER it (i.e.
 * the conversation has not moved on past the question). This prevents a dead /
 * abandoned session from keeping awaitingChoice stuck true forever (FIX D).
 */
private fun hasPendingAskUserQuestion(
    entries: List<com.clauderemote.session.transcript.TranscriptEntry>,
    resultIds: Set<String>,
): Boolean {
    // Walk backwards; the last unanswered AskUserQuestion is the relevant one.
    val lastAskIdx = entries.indexOfLast {
        it is com.clauderemote.session.transcript.TranscriptEntry.ToolCall &&
            it.name == "AskUserQuestion" &&
            it.toolUseId !in resultIds
    }
    if (lastAskIdx < 0) return false
    // If anything that signals "conversation moved on" appears AFTER the ask,
    // treat it as abandoned/answered out-of-band.
    return entries.drop(lastAskIdx + 1).none {
        it is com.clauderemote.session.transcript.TranscriptEntry.UserPrompt ||
            it is com.clauderemote.session.transcript.TranscriptEntry.AssistantText
    }
}

