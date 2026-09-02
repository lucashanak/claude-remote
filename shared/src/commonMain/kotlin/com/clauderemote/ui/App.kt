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
    LAUNCHER, CONNECT, TERMINAL, SETTINGS, LOG_VIEWER, USAGE_DASHBOARD, HISTORY, ACCOUNTS
}

@Composable
fun App(
    serverStorage: ServerStorage,
    appSettings: AppSettings,
    tabManager: TabManager,
    sessionOrchestrator: SessionOrchestrator,
    // Multi-account: per-(server, folder) account policy. Constructed the
    // same way as serverStorage/appSettings (wrapping the platform `prefs`) —
    // see MainActivity.kt / Main.kt.
    folderPolicyStorage: com.clauderemote.storage.FolderPolicyStorage,
    accountColorStorage: com.clauderemote.storage.AccountColorStorage,
    appVersion: String = "1.0.0",
    onInstallUpdate: ((ByteArray, UpdateInfo) -> Unit)? = null,
    onGetCurrentApk: (() -> ByteArray)? = null,
    onShareLog: ((String) -> Unit)? = null,
    onTestNotification: (() -> Unit)? = null,
    onTerminalScreenVisible: (() -> Unit)? = null,
    onPickKeyFile: ((callback: (String) -> Unit) -> Unit)? = null,
    onImportServers: (() -> Unit)? = null,
    onPickFile: ((callback: (List<Pair<ByteArray, String>>) -> Unit) -> Unit)? = null,
    onSaveFile: ((bytes: ByteArray, suggestedName: String) -> Unit)? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    onApplyFontSize: ((Int) -> Unit)? = null,
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
    // Which chat-mentioned paths the server confirmed are real files.
    val pathVerifyCache = remember { RemotePathCache() }

    var currentScreen by remember { mutableStateOf(Screen.LAUNCHER) }
    // Which server's accounts the accounts screen is showing; set when opened
    // from that server's settings, which is the only way in.
    var accountsServerId by remember { mutableStateOf<String?>(null) }
    var selectedServer by remember { mutableStateOf<SshServer?>(null) }
    var showServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<SshServer?>(null) }
    var tmuxSessions by remember { mutableStateOf<List<TmuxSession>>(emptyList()) }
    var tmuxLoading by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var tabCloseConfirmId by remember { mutableStateOf<String?>(null) }
    // Long-press session context menu (mobile): which session it's open for.
    var sessionMenuId by remember { mutableStateOf<String?>(null) }
    // Long-press server context menu (mobile): which server it's open for.
    var serverMenuServer by remember { mutableStateOf<SshServer?>(null) }
    // Delete-server confirmation: which server is pending deletion. Both the
    // edit-dialog "Delete server" button and the long-press context menu's
    // "Delete" route here instead of calling deleteServer directly — losing a
    // saved server (host, key, tailscale config) with one accidental tap had
    // no undo.
    var serverDeleteConfirm by remember { mutableStateOf<SshServer?>(null) }
    // Rename dialog: which session is being renamed.
    var renameSessionId by remember { mutableStateOf<String?>(null) }
    var invertColors by remember { mutableStateOf(appSettings.invertColors) }
    // Font sizes live here as Compose state because AppSettings is a plain prefs
    // wrapper: writing the pref alone never recomposes anything, so a change made
    // in Settings used to reach the terminal/chat only by accident, on the next
    // unrelated recomposition. Both entry points (Settings sliders and the
    // in-terminal A−/A+ controls) funnel through the two lambdas below.
    var terminalFontSize by remember { mutableStateOf(appSettings.terminalFontSize) }
    var transcriptFontPercent by remember { mutableStateOf(appSettings.transcriptFontPercent) }
    val applyTerminalFontSize: (Int) -> Unit = { size ->
        appSettings.terminalFontSize = size
        // Read back so the state matches what was actually stored (clamped).
        terminalFontSize = appSettings.terminalFontSize
        onApplyFontSize?.invoke(terminalFontSize)
    }
    val applyTranscriptFontPercent: (Int) -> Unit = { percent ->
        appSettings.transcriptFontPercent = percent
        transcriptFontPercent = appSettings.transcriptFontPercent
    }

    // Sink for "hand this text to the user": Android puts it in the share sheet
    // (onShareLog), desktop has no share sheet so it falls back to the native
    // save dialog (onSaveFile). Desktop passes only the latter, which is why
    // Export servers used to be a dead button there and the log viewer's share
    // icon never showed up at all.
    val shareText: ((text: String, suggestedName: String) -> Unit)? =
        onShareLog?.let { share -> { text: String, _: String -> share(text) } }
            ?: onSaveFile?.let { save ->
                { text: String, name: String -> save(text.encodeToByteArray(), name) }
            }

    // Collect new StateFlows from orchestrator
    val sessionActivities by sessionOrchestrator.sessionActivities.collectAsState()
    val connectionLabels by sessionOrchestrator.connectionLabels.collectAsState()
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
    val reconnectStatus by sessionOrchestrator.reconnectStatus.collectAsState()
    val loginFlow by sessionOrchestrator.loginFlow.collectAsState()

    var serverList by remember { mutableStateOf(serverStorage.loadServers()) }
    val tabs by tabManager.tabs.collectAsState()
    val activeTabId by tabManager.activeTabId.collectAsState()
    // Server of the active tab — usage chips (5h/wk) are keyed by server.
    val activeServerId = tabs.firstOrNull { it.id == activeTabId }?.server?.id

    // Multi-account: accounts available on the active session's server, for
    // the "Switch account" entry in TerminalScreen's overflow menu.
    var terminalAccounts by remember { mutableStateOf<List<com.clauderemote.model.ClaudeAccount>>(emptyList()) }
    LaunchedEffect(activeServerId) {
        val id = activeServerId
        if (id == null) {
            terminalAccounts = emptyList()
            return@LaunchedEffect
        }
        // Retry while empty. The probe needs a live SSH transport, and on a
        // session RESTORED at app start there often isn't one yet — a single
        // attempt then failed silently and left the list empty for good, which
        // hides the account chip and the switcher on exactly those sessions.
        // (A session created through ConnectScreen re-triggered this effect by
        // changing the server, which is why new sessions looked fine.)
        repeat(ACCOUNTS_LOAD_TRIES) { attempt ->
            val loaded = try {
                sessionOrchestrator.listClaudeAccounts(id)
            } catch (_: Exception) {
                emptyList()
            }
            if (loaded.isNotEmpty()) {
                terminalAccounts = loaded
                // Pull the SHARED folder policies at the same time. They live on
                // the server so every client sees the same rules; the local store
                // is just a cache, and without this a rule set on another device
                // simply wouldn't exist here.
                try {
                    sessionOrchestrator.readFolderPolicies(id)?.let {
                        folderPolicyStorage.importFromServer(id, it)
                    }
                } catch (_: Exception) {}
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(ACCOUNTS_LOAD_RETRY_MS * (attempt + 1))
        }
    }

    // Correct the active session's account from the running process. The stored
    // slug can be stale (adopted from the server manifest, written by another
    // device, or persisted before the field existed) and the chip must not claim
    // an account the session isn't on.
    LaunchedEffect(activeTabId, terminalAccounts) {
        val id = activeTabId ?: return@LaunchedEffect
        try { sessionOrchestrator.resolveSessionAccount(id) } catch (_: Exception) {}
    }

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
    // FIX 6: every path that switches the active session (CrumbBar prev/next,
    // the session drawer/list, resuming from the launcher/history) must keep
    // the grid's own bookkeeping (paneSessions/focusedPaneIndex) in step with
    // activeTabId — mirrors onFocusPane/onAssignPane below. In single-pane
    // mode the whole screen already renders straight off activeTabId, so this
    // is a no-op there; in TWO/QUAD the focused *cell* (and its label) is
    // driven by paneSessions[focusedPaneIndex], which sessionOrchestrator
    // .switchTab() alone never touches — without this, switching sessions via
    // anything other than a direct pane tap silently desyncs which cell is
    // highlighted/labeled from what's actually shown underneath, or switches
    // to a session that isn't placed in any visible pane at all.
    fun switchActiveSession(id: String) {
        FileLogger.log("App", "switchActiveSession target=$id layout=$gridLayout focusedPane=$focusedPaneIndex targetStatus=${tabs.firstOrNull { it.id == id }?.status}")
        if (gridLayout != GridLayout.ONE) {
            val idx = paneSessions.indexOf(id)
            if (idx >= 0) {
                focusedPaneIndex = idx
            } else {
                val current = paneSessions.toMutableList()
                if (focusedPaneIndex in current.indices) current[focusedPaneIndex] = id
                paneSessions = current
            }
        }
        // Re-selecting the already-active tab is a no-op for pane bookkeeping,
        // but switchTab() itself is not free — it forces a transcript
        // re-subscribe and a 2 KB tail replay. onFocusPane already guards this;
        // match that here so switching to what's already active doesn't churn.
        if (id != activeTabId) sessionOrchestrator.switchTab(id)
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
    // Stale-tab prune strike counter: tab.id → consecutive successful scans of
    // its server that did NOT contain its tmux session. A tab is forgotten only
    // at 2 strikes (see scanRemoteSessions) so one flaky scan can't delete it.
    val stalePruneStrikes = remember { mutableMapOf<String, Int>() }

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
                // Per-server results; null = the scan FAILED for that server
                // (timeout / unreachable). The old code flattened failures into
                // an empty list, which made "scan failed" indistinguishable from
                // "no tmux sessions exist" — and on a flaky network the prune
                // below then forgetSession'd every DISCONNECTED tab (removeTab
                // + storage.remove + tmux kill-session). That is exactly the
                // "sessions vanish until app restart" bug.
                val perServer: Map<String, List<RemoteSession>?> = withContext(Dispatchers.IO) {
                    servers.map { server ->
                        async {
                            server.id to try {
                                com.clauderemote.connection.SshSessionHelper.withSession(server, 5000) { sess ->
                                    TmuxManager.listSessions(sess).map { RemoteSession(server, it) }
                                }
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }.awaitAll().toMap()
                }
                // Only overwrite the visible list when at least one server
                // answered; blanking it on an all-failed scan flashed
                // "No sessions" on every connectivity dip.
                if (perServer.values.any { it != null }) {
                    remoteSessions = perServer.values.filterNotNull().flatten()
                }
                // Prune disconnected tabs whose tmux session no longer exists.
                // Flaky-network guards:
                //  • a tab is only "confirmed absent" when ITS OWN server's
                //    scan succeeded and the tmux name is missing from it — a
                //    failed scan proves nothing about that server's sessions;
                //  • two CONSECUTIVE confirmed absences (strikes) are required
                //    before forgetting, so a single half-broken scan (server
                //    answered but tmux hiccupped) can't delete a tab.
                //  • DEATH-SIGNATURE guard: on a WHOLE-server tmux death (the
                //    tmux server process itself died) SSH still connects, so the
                //    scan "succeeds" but returns ZERO sessions — momentarily ALL
                //    of that server's tabs look absent at once. Without this
                //    guard the prune would forget EVERY tab on that server (the
                //    recurring mass session-loss bug). So a tab is only prunable
                //    when its server's successful scan shows it is genuinely UP:
                //    ≥1 OTHER live claude-server-* session still exists. If a
                //    server's scan succeeded but its live claude-server-* set is
                //    empty while local tabs remain, treat it as a whole-server
                //    outage and prune nothing on that server this cycle.
                val serversWithLiveClaudeSession: Set<String> =
                    perServer.mapNotNull { (serverId, scanned) ->
                        if (scanned != null &&
                            scanned.any { it.tmuxSession.name.startsWith("claude-server-") }
                        ) serverId else null
                    }.toSet()
                val confirmedAbsent = tabManager.tabs.value.filter { tab ->
                    val scanned = perServer[tab.server.id]
                    tab.status != SessionStatus.ACTIVE &&
                        tab.tmuxSessionName.isNotBlank() &&
                        scanned != null &&
                        // Positive proof the server is up — skips the whole-server
                        // outage case where scanned succeeded but is empty.
                        serversWithLiveClaudeSession.contains(tab.server.id) &&
                        scanned.none { it.tmuxSession.name == tab.tmuxSessionName }
                }
                // Any tab NOT confirmed absent this round (present again, or
                // its server failed to scan) loses its accumulated strikes.
                stalePruneStrikes.keys.retainAll(confirmedAbsent.map { it.id }.toSet())
                confirmedAbsent.forEach { tab ->
                    val strikes = (stalePruneStrikes[tab.id] ?: 0) + 1
                    stalePruneStrikes[tab.id] = strikes
                    if (strikes >= 2) {
                        FileLogger.log("App", "Pruning stale tab ${tab.id} (tmux ${tab.tmuxSessionName}, $strikes strikes)")
                        stalePruneStrikes.remove(tab.id)
                        // forgetSession removes from SessionStorage and re-syncs the
                        // server-side sessions.json so the systemd restore service
                        // doesn't try to re-materialise this tab on next reboot.
                        scope.launch { sessionOrchestrator.forgetSession(tab.id) }
                    }
                }
            } catch (_: Exception) {
                // Keep the last-known remoteSessions — never blank on failure.
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

    // The server list can also change from outside this composition — the
    // platform "Import servers" picker writes straight to storage — so re-read
    // it whenever the launcher comes back into view. Without this, imported
    // servers stayed invisible until some unrelated edit refreshed the list.
    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.LAUNCHER) refreshServers()
    }

    fun downloadUpdate(info: UpdateInfo) {
        scope.launch {
            try {
                val onAndroid = try { Class.forName("android.os.Build"); true } catch (_: Exception) { false }
                val usePatch = onAndroid && info.hasPatch && onGetCurrentApk != null

                if (!usePatch) {
                    // Full download path (desktop or no patches available).
                    // Pick the asset that matches the running platform — on Linux
                    // that's the distro's package (.pkg.tar.zst / .deb / tarball),
                    // NOT the .dmg (which used to be grabbed here and then fed to
                    // macOS-only hdiutil, crashing the Linux updater).
                    val downloadUrl = when {
                        onAndroid -> info.apkUrl
                        UpdateChecker.desktopPlatform() == UpdateChecker.DesktopPlatform.LINUX ->
                            when (UpdateChecker.linuxPkgKind(info)) {
                                UpdateChecker.LinuxPkg.PKG -> info.pkgUrl
                                UpdateChecker.LinuxPkg.DEB -> info.debUrl
                                UpdateChecker.LinuxPkg.TARGZ -> info.tarGzUrl
                                UpdateChecker.LinuxPkg.NONE -> ""
                            }
                        else -> info.dmgUrl.ifBlank { info.apkUrl }
                    }
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
                    // On Linux the system package installer opens in its own
                    // window and we hand control to it (no in-place swap /
                    // relaunch like macOS), so clear the spinner instead of
                    // leaving the banner stuck on "Installing...".
                    if (!onAndroid &&
                        UpdateChecker.desktopPlatform() == UpdateChecker.DesktopPlatform.LINUX
                    ) {
                        updateState = updateState.copy(
                            downloading = false,
                            statusText = "Installer opened — finish it, then restart the app."
                        )
                    }
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
                        onSwitchModelAll = { model ->
                            sessionOrchestrator.switchModelForAllSessions(model)
                        },
                        onSwitchEffortAll = { effort ->
                            sessionOrchestrator.switchEffortForAllSessions(effort)
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
                                        // Honor the server's Mosh preference when a direct-UDP
                                        // path exists (plain SSH or Tailscale); the orchestrator
                                        // still falls back to SSH if mosh can't run.
                                        connectionType = if (server.preferMosh && (!server.useCloudflareProxy || server.hasTailscale))
                                            ConnectionType.MOSH else ConnectionType.SSH,
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
                        onServerLongPress = { server -> serverMenuServer = server },
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
                            switchActiveSession(session.id)
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
                            // ONE exec brings back the whole subtree with the
                            // mtimes and project markers the picker ranks by.
                            // This used to be an `ls` per click, and because no
                            // pooled transport exists yet on the Connect screen
                            // each of those paid a full SSH handshake — seconds
                            // per click over the Cloudflare tunnel.
                            onScanFolders = { path ->
                                try {
                                    // Dispatchers.IO is not optional here. The
                                    // caller is a rememberCoroutineScope(), i.e.
                                    // Main, and withSession only wraps connect()
                                    // in IO — block(sess) runs on the CALLER's
                                    // context, and on the pooled-reuse path it
                                    // returns before any dispatch at all. Without
                                    // this the channel connect and the read would
                                    // block the UI thread for the whole
                                    // handshake: the spinner could not even
                                    // animate, and on Android that is an ANR.
                                    withContext(Dispatchers.IO) {
                                    com.clauderemote.connection.SshSessionHelper.withSession(server) { sess ->
                                        fun exec(cmd: String): String {
                                            val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
                                            ch.setCommand(cmd)
                                            ch.inputStream = null
                                            val input = ch.inputStream
                                            ch.connect(8000)
                                            // Bounded read: MAX_DIRS is enforced
                                            // only by `head` INSIDE the remote
                                            // command, i.e. only by a cooperative
                                            // server. A wedged or hostile one
                                            // that streams forever would OOM the
                                            // client through readText().
                                            val cap = RemoteDirScan.MAX_OUTPUT_CHARS
                                            val reader = input.bufferedReader()
                                            val sb = StringBuilder()
                                            val buf = CharArray(8192)
                                            while (sb.length < cap) {
                                                val n = reader.read(
                                                    buf, 0, minOf(buf.size, cap - sb.length)
                                                )
                                                if (n < 0) break
                                                sb.appendRange(buf, 0, n)
                                            }
                                            val output = sb.toString()
                                            ch.disconnect()
                                            return output
                                        }
                                        val scanned = RemoteDirScan.parse(
                                            path, exec(RemoteDirScan.command(path))
                                        )
                                        // `find -printf` is GNU-only, so a BSD or
                                        // macOS server returns NO dirs section at
                                        // all — retry one level with `ls` on the
                                        // same (already open) transport. Gated on
                                        // `parsed`, not on emptiness: a GNU server
                                        // reporting a genuinely empty directory is
                                        // a real answer, and treating it as a
                                        // failure spent a second exec every time.
                                        if (!scanned.parsed) {
                                            RemoteDirScan.parseFallback(
                                                path, exec(RemoteDirScan.fallbackCommand(path))
                                            )
                                        } else scanned
                                    }
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    // Never swallowed: it is how the caller's
                                    // scope tears this down, not a scan failure.
                                    throw e
                                } catch (_: Exception) {
                                    // null, NOT an empty tree: the Connect screen
                                    // has to tell "the listing failed" apart from
                                    // "this folder has no subfolders", or one dead
                                    // tunnel reads as an empty home directory.
                                    null
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
                            onLoadAccounts = { sessionOrchestrator.listClaudeAccounts(server.id) },
                            folderPolicyStorage = folderPolicyStorage,
                            onLaunch = { folder, mode, model, connType, tmuxName, isNewTmux, accountSlug ->
                                scope.launch {
                                    try {
                                        connectionError = null
                                        // The account is bound at LAUNCH, not after: it is
                                        // carried by CLAUDE_CONFIG_DIR in the process env, so
                                        // it only takes effect for a process that hasn't
                                        // started yet. Launching first and switching after
                                        // would run the session under the wrong account,
                                        // write transcript entries as that account, and only
                                        // then restart Claude.
                                        sessionOrchestrator.launchSession(
                                            server = server,
                                            folder = folder,
                                            mode = mode,
                                            model = model,
                                            connectionType = connType,
                                            tmuxSessionName = tmuxName,
                                            isNewTmuxSession = isNewTmux,
                                            accountSlug = accountSlug
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
                        // Every session-switch entry point OUTSIDE the pane grid
                        // (CrumbBar prev/next, SessionDrawer pick, Command Palette
                        // "Switch to") funnels through this one lambda — routed
                        // through switchActiveSession so it stays in step with the
                        // grid the same way tapping a pane does.
                        onTabSwitch = { id -> switchActiveSession(id) },
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
                                                // `=` forces an exact target match — plain `-t` prefix-matches
                                                // and would rename a DIFFERENT session whose name merely
                                                // starts with this one's.
                                                ch.setCommand("tmux rename-session -t '=${tab.tmuxSessionName.replace("'", "'\\''")}' '${newTmuxName.replace("'", "'\\''")}'")
                                                ch.connect(5000)
                                                ch.inputStream.bufferedReader().readText()
                                                ch.disconnect()
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        },
                        onReconnect = { id ->
                            scope.launch { sessionOrchestrator.reconnectSession(id) }
                        },
                        onRestartClaude = { id ->
                            scope.launch { sessionOrchestrator.restartClaude(id) }
                        },
                        onTabClose = { id ->
                            // Always confirm — closing forgets the persisted
                            // session and kills the remote tmux pane, which
                            // is destructive enough that we don't want a
                            // single misclick to lose the conversation.
                            tabCloseConfirmId = id
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
                                val folder = tabManager.getTab(id)?.folder ?: "~"
                                sessionOrchestrator.downloadFile(id, resolveSessionPath(path, folder))
                            }
                        },
                        onVerifyPaths = { paths ->
                            val id = activeTabId
                            if (id == null) {
                                emptySet()
                            } else {
                                val folder = tabManager.getTab(id)?.folder ?: "~"
                                // Key on the RESOLVED path, scoped by session, so two
                                // sessions in different folders can't answer for each other.
                                val resolved = paths.associateWith { resolveSessionPath(it, folder) }
                                val key = { p: String -> id + " " + resolved[p] }
                                val ask = paths.filter { pathVerifyCache[key(it)] == null }
                                if (ask.isNotEmpty()) {
                                    val found = sessionOrchestrator.statFiles(id, ask.map { resolved[it]!! })
                                    ask.forEach { pathVerifyCache[key(it)] = resolved[it] in found }
                                }
                                paths.filterTo(mutableSetOf()) { pathVerifyCache[key(it)] == true }
                            }
                        },
                        onSaveFile = onSaveFile,
                        onSwitchModel = { model ->
                            activeTabId?.let { sessionOrchestrator.switchModel(it, model) }
                        },
                        onSwitchEffort = { effort ->
                            activeTabId?.let { sessionOrchestrator.switchEffort(it, effort) }
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
                        terminalFontSize = terminalFontSize,
                        onFontSizeChange = applyTerminalFontSize,
                        transcriptFontScale = transcriptFontPercent / 100f,
                        onTranscriptFontScaleChange = { scale ->
                            // roundToInt, not toInt: 1.3f * 100 is 129.99999 and
                            // truncating would drift the percent down step by step.
                            applyTranscriptFontPercent(kotlin.math.round(scale * 100.0).toInt())
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
                        reconnectStatus = reconnectStatus,
                        loginFlow = loginFlow,
                        onOpenLoginUrl = { url -> onOpenUrl?.invoke(url) },
                        onSubmitLoginCode = { code ->
                            val clean = code.filterNot { it.isWhitespace() }
                            activeTabId?.let {
                                sessionOrchestrator.submitLoginCode(it, clean)
                                sessionOrchestrator.clearLoginFlow(it)
                            }
                        },
                        onCancelLogin = {
                            activeTabId?.let {
                                sessionOrchestrator.sendEscape(it)
                                sessionOrchestrator.clearLoginFlow(it)
                            }
                        },
                        onLogin = { activeTabId?.let { sessionOrchestrator.sendLoginCommand(it) } },
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
                            FileLogger.log("App", "onFocusPane index=$index sid=$sid status=${tabs.firstOrNull { it.id == sid }?.status}")
                            focusedPaneIndex = index
                            if (sid != null && sid != activeTabId) {
                                sessionOrchestrator.switchTab(sid)
                            }
                        },
                        // FIX 1 + dedup: if the chosen sid is already in another
                        // pane, clear that other pane to prevent two cells sharing
                        // the same session (and both rendering terminalContent).
                        onAssignPane = { index, sid ->
                            FileLogger.log("App", "onAssignPane index=$index sid=$sid status=${tabs.firstOrNull { it.id == sid }?.status}")
                            val current = paneSessions.toMutableList()
                            // Clear any existing pane that already holds this sid.
                            for (j in current.indices) {
                                if (j != index && current[j] == sid) current[j] = null
                            }
                            if (index in current.indices) current[index] = sid
                            paneSessions = current
                            // The raw terminal is bound to activeTabId, not paneSessions.
                            // When the focused pane is reassigned we must switch the active
                            // tab so terminalContent follows — mirroring what onFocusPane
                            // already does.
                            if (index == focusedPaneIndex && sid != activeTabId) {
                                sessionOrchestrator.switchTab(sid)
                            }
                        },
                        composeTerminalUnderTranscript = composeTerminalUnderTranscript,
                        connectionLabel = activeTabId?.let { connectionLabels[it] },
                        accounts = terminalAccounts,
                        activeAccountColor = activeTabId?.let { id ->
                            val slug = tabManager.getTab(id)?.accountSlug
                                ?: com.clauderemote.model.ClaudeAccount.DEFAULT_SLUG
                            // Resolve through the whole list so the chip matches the
                            // swatch shown on the accounts screen — assign() only
                            // guarantees distinctness when it sees every account.
                            val slugs = terminalAccounts.map { it.slug }.ifEmpty { listOf(slug) }
                            accountColorStorage.assign(slugs)[slug]?.color
                        },
                        activeFolderPolicy = activeTabId?.let { id ->
                            tabManager.getTab(id)?.let { t ->
                                folderPolicyStorage.get(t.server.id, t.folder)
                            }
                        },
                        onSwitchAccount = { id, slug -> sessionOrchestrator.switchSessionAccount(id, slug) },
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
                        onExportServers = shareText?.let { emit ->
                            {
                                val json = kotlinx.serialization.json.Json { prettyPrint = true }
                                    .encodeToString(kotlinx.serialization.builtins.ListSerializer(
                                        com.clauderemote.model.SshServer.serializer()
                                    ), serverStorage.loadServers())
                                emit(json, "claude-remote-servers.json")
                            }
                        },
                        onImportServers = onImportServers,
                        onViewLog = { currentScreen = Screen.LOG_VIEWER },
                        onTestNotification = onTestNotification,
                        onFontSizeChange = applyTerminalFontSize,
                        onTranscriptFontChange = applyTranscriptFontPercent,
                    )
                }

                Screen.LOG_VIEWER -> {
                    LogViewerScreen(
                        onBack = { currentScreen = Screen.LAUNCHER },
                        onShare = shareText?.let { emit ->
                            { text: String -> emit(text, "claude-remote-log.txt") }
                        }
                    )
                }

                Screen.ACCOUNTS -> {
                    AccountsScreen(
                        servers = serverList,
                        sessionOrchestrator = sessionOrchestrator,
                        folderPolicyStorage = folderPolicyStorage,
                        accountColorStorage = accountColorStorage,
                        appSettings = appSettings,
                        initialServerId = accountsServerId,
                        onBack = { currentScreen = Screen.LAUNCHER },
                        onOpenUrl = onOpenUrl,
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
                                switchActiveSession(tab.id)
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
                onManageAccounts = editingServer?.let { srv ->
                    {
                        showServerDialog = false
                        accountsServerId = srv.id
                        currentScreen = Screen.ACCOUNTS
                    }
                },
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
                    serverDeleteConfirm = server
                }
            )
        }

        // Close tab confirmation. FloatingDialog (not a plain AlertDialog) so
        // it renders above the SwingPanel-embedded terminal on desktop when
        // triggered while Raw view is active.
        com.clauderemote.ui.components.FloatingDialog(
            visible = tabCloseConfirmId != null,
            onDismiss = { tabCloseConfirmId = null },
            theme = com.clauderemote.ui.theme.CRThemeSnapshot.current(),
            title = { Text("Close Session") },
            text = {
                val session = tabCloseConfirmId?.let { tabManager.getTab(it) }
                Text("Permanently close session on ${session?.server?.name ?: "server"}? The tmux pane will be killed and the conversation removed from your tab list.")
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = tabCloseConfirmId ?: return@TextButton
                    tabCloseConfirmId = null
                    scope.launch {
                        // userInitiated: the user pressed Close in this dialog, so
                        // the close is recorded durably and propagates to every
                        // other device even if one of them is asleep right now.
                        sessionOrchestrator.forgetSession(id, userInitiated = true)
                        if (tabManager.tabs.value.isEmpty()) currentScreen = Screen.LAUNCHER
                    }
                }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { tabCloseConfirmId = null }) { Text("Cancel") }
            }
        )

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

        // Server long-press context menu (mobile): the discoverable path to Edit
        // (server settings, e.g. Tailscale host), Quick connect, and Delete.
        serverMenuServer?.let { srv ->
            ServerContextSheet(
                name = srv.name,
                address = srv.displayAddress,
                onEdit = {
                    serverMenuServer = null
                    editingServer = srv
                    showServerDialog = true
                },
                onQuickConnect = {
                    serverMenuServer = null
                    scope.launch {
                        try {
                            connectionError = null
                            sessionOrchestrator.launchSession(
                                server = srv,
                                folder = srv.defaultFolder,
                                mode = srv.defaultClaudeMode,
                                model = srv.defaultClaudeModel,
                                connectionType = if (srv.preferMosh && (!srv.useCloudflareProxy || srv.hasTailscale))
                                    ConnectionType.MOSH else ConnectionType.SSH,
                                tmuxSessionName = TmuxNameParser.build(srv.name, srv.defaultFolder, srv.defaultClaudeMode == ClaudeMode.YOLO),
                                isNewTmuxSession = true
                            )
                            currentScreen = Screen.TERMINAL
                        } catch (e: Exception) {
                            connectionError = e.message
                        }
                    }
                },
                onDelete = {
                    serverMenuServer = null
                    serverDeleteConfirm = srv
                },
                onDismiss = { serverMenuServer = null },
            )
        }

        // Delete-server confirmation — shared by the edit dialog's "Delete
        // server" button and the long-press context menu's "Delete".
        serverDeleteConfirm?.let { srv ->
            AlertDialog(
                onDismissRequest = { serverDeleteConfirm = null },
                title = { Text("Delete Server") },
                text = { Text("Permanently delete \"${srv.name}\"? Saved connection details (host, key, Tailscale config) will be lost. This does not affect any tmux sessions already running on the server.") },
                confirmButton = {
                    TextButton(onClick = {
                        serverDeleteConfirm = null
                        showServerDialog = false
                        serverStorage.deleteServer(srv.id)
                        refreshServers()
                        sessionOrchestrator.pruneServerHealth(srv.id)
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { serverDeleteConfirm = null }) { Text("Cancel") }
                }
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

/** Retries for the account probe — a restored session has no transport yet. */
private const val ACCOUNTS_LOAD_TRIES = 6
private const val ACCOUNTS_LOAD_RETRY_MS = 2000L
