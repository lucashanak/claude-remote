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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
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
    LAUNCHER, CONNECT, TERMINAL, SETTINGS, LOG_VIEWER, USAGE_DASHBOARD, HISTORY, ACCOUNTS,
    ACCOUNT_USAGE, VELIN,
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
    // Whether the OS has notifications for this app switched off — surfaced as
    // a warning row in Settings. Android wires this; desktop keeps the default.
    notificationsBlocked: () -> Boolean = { false },
    // A notification setting changed. Android pushes it to the watch at once,
    // which otherwise learns it only from the next routine session push.
    onNotificationSettingsChange: () -> Unit = {},
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
    composeTerminalUnderTranscript: Boolean = false,
    /** Physical keyboard attached — enables keyboard mode in landscape. Android only. */
    hardwareKeyboard: Boolean = false,
    /** Shortcuts the platform caught before Compose did (Android terminal view hook). */
    externalShortcuts: kotlinx.coroutines.flow.Flow<AppShortcut>? = null,
    /** Keyboard mode started/stopped on the terminal screen (Android: system bars). */
    onKeyboardModeChange: ((Boolean) -> Unit)? = null,
    /** Move keyboard focus into the terminal view; false while it doesn't exist yet. */
    onFocusTerminal: (() -> Boolean)? = null,
    /**
     * Quick-access requests from outside the composition (Android launcher shortcuts and the quick-settings tile):
     * App takes each non-null value, sets it back to null and opens Jarvis or the Velín. A StateFlow, so a request
     * made before the first composition (a cold start) waits here instead of being dropped.
     */
    quickAccessRequests: kotlinx.coroutines.flow.MutableStateFlow<com.clauderemote.session.QuickTarget?>? = null,
) {
    val scope = rememberCoroutineScope()
    // Which chat-mentioned paths the server confirmed are real files.
    val pathVerifyCache = remember { RemotePathCache() }

    // The start screen is a setting (Launcher unless the user chose the Velín), read once here.
    var currentScreen by remember {
        mutableStateOf(if (appSettings.startScreen == AppSettings.START_VELIN) Screen.VELIN else Screen.LAUNCHER)
    }
    // The Velín asks once whether the app should open there; answered, it never asks again.
    var velinStartOfferPending by remember { mutableStateOf(!appSettings.velinStartOffered) }
    // The Velín's tab to open on, and this device's alert routing (Jarvis F3).
    var velinTab by remember { mutableStateOf(VelinTab.PREHLED) }
    var alertRouting by remember { mutableStateOf(appSettings.alertRouting) }
    // Which server's accounts the accounts screen is showing; set when opened
    // from that server's settings, which is the only way in.
    var accountsServerId by remember { mutableStateOf<String?>(null) }
    var selectedServer by remember { mutableStateOf<SshServer?>(null) }
    var showServerDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<SshServer?>(null) }
    // The Connect screen's listing of the selected server: its sessions, whether it still runs, and the names a new
    // session must not take beyond the listed ones (see onConnectServer, ConnectListing)
    val connectListing = remember { ConnectListing() }
    var connectionError by remember { mutableStateOf<String?>(null) }
    // Long-press session context menu (mobile): which session it's open for.
    var sessionMenuId by remember { mutableStateOf<String?>(null) }
    // Usage is reachable from the launcher AND from the chat's "..." menu, so
    // Back has to return where it was opened from rather than to a fixed
    // screen — landing in the launcher after opening it mid-session loses the
    // terminal you were reading.
    var usageReturnScreen by remember { mutableStateOf(Screen.LAUNCHER) }
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
    val activeModels by sessionOrchestrator.activeModels.collectAsState()
    val sessionUsagePercents by sessionOrchestrator.sessionUsagePercents.collectAsState()
    val weekUsagePercents by sessionOrchestrator.weekUsagePercents.collectAsState()
    val sessionResetMin by sessionOrchestrator.sessionResetMin.collectAsState()
    val weekResetMin by sessionOrchestrator.weekResetMin.collectAsState()
    val latencies by sessionOrchestrator.latencies.collectAsState()
    val gitStatuses by sessionOrchestrator.gitStatuses.collectAsState()
    val serverHealth by sessionOrchestrator.serverHealth.collectAsState()
    val reconnectStatus by sessionOrchestrator.reconnectStatus.collectAsState()
    val loginFlow by sessionOrchestrator.loginFlow.collectAsState()
    val loginExpiryWarnings by sessionOrchestrator.loginExpiryWarnings.collectAsState()

    var serverList by remember { mutableStateOf(serverStorage.loadServers()) }
    val tabs by tabManager.tabs.collectAsState()
    val activeTabId by tabManager.activeTabId.collectAsState()
    // A closed tab waits out its undo window still in tabManager (connected, persisted, in the manifest), only
    // hidden; everything the user sees and picks from uses visibleTabs (PendingCloses).
    val pendingCloses = sessionOrchestrator.pendingCloses
    val pendingCloseState by pendingCloses.state.collectAsState()
    val visibleTabs = remember(tabs, pendingCloseState.pending) { pendingCloseState.visibleOf(tabs) }
    // A tab gone from tabManager behind the undo window's back (another device closed it, the dead-pane prune
    // forgot it): nothing left to close or bring back.
    LaunchedEffect(tabs) { pendingCloses.retain(tabs.mapTo(HashSet()) { it.id }) }
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
    LaunchedEffect(visibleTabs) {
        val validIds = visibleTabs.map { it.id }.toSet()
        paneSessions = paneSessions.map { sid -> if (sid != null && sid !in validIds) null else sid }
        // focusedPaneIndex is left wherever it is; that cell will show the picker.
    }
    // FIX 4: Reset grid entirely when tab count drops to 1 (can't split a
    // single session) — prevents stale TWO/QUAD resurfacing on next open.
    LaunchedEffect(visibleTabs.size) {
        if (visibleTabs.size <= 1) {
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

    // Closing a tab (Jarvis F0a, R6 = 6d): no question asked; it leaves the bar at once, the real close (durable
    // tombstone, kill) waits out the undo window in PendingCloses while the server's close facts decide whether it
    // may happen at all. Selection moves on as TabManager.removeTab moves it: to the last tab left.
    // Reads the flows' current values, not the composed ones, because shortcuts and menus may hold an older lambda.
    fun beginTabClose(id: String) {
        val tab = tabManager.getTab(id) ?: return
        val wasActive = id == tabManager.activeTabId.value
        if (!pendingCloses.close(tab, wasActive)) return
        val hidden = pendingCloses.state.value.hiddenIds
        val rest = tabManager.tabs.value.filter { it.id !in hidden }
        if (wasActive) rest.lastOrNull()?.let { switchActiveSession(it.id) }
        if (rest.isEmpty()) currentScreen = Screen.LAUNCHER
    }

    // "Vrátit": the tab is back where it was (it never left tabManager); if it was the one on screen, so is it again.
    fun afterUndo(undone: List<com.clauderemote.session.PendingClose>) {
        val reselect = undone.lastOrNull { it.wasActive } ?: return
        if (tabManager.getTab(reselect.sessionId) == null) return
        switchActiveSession(reselect.sessionId)
        currentScreen = Screen.TERMINAL
    }

    // The selection can land on a hidden tab behind beginTabClose's back: TabManager.removeTab (a close that went
    // through, a forget from elsewhere) picks the last tab in the list, hidden or not. Never leave a terminal on
    // screen that has no tab in the bar.
    LaunchedEffect(activeTabId, pendingCloseState.pending, currentScreen) {
        val id = activeTabId ?: return@LaunchedEffect
        if (id !in pendingCloseState.hiddenIds || currentScreen != Screen.TERMINAL) return@LaunchedEffect
        val next = visibleTabs.lastOrNull()
        if (next != null) switchActiveSession(next.id) else currentScreen = Screen.LAUNCHER
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

    // Same four unconditional call sites for "this pane's history is cut", keyed
    // the same way, so a rotation re-subscribes the flag with the transcript.
    @Composable
    fun rememberPaneTruncated(sid: String?): Boolean {
        val claudeUuid = sid?.let { id -> tabs.firstOrNull { it.id == id }?.claudeSessionId }
        val flow = remember(sid, claudeUuid) {
            if (sid != null) sessionOrchestrator.transcriptTruncatedFlow(sid)
            else kotlinx.coroutines.flow.MutableStateFlow(false)
        }
        return flow.collectAsState().value
    }
    val paneHistoryTruncated = listOf(
        rememberPaneTruncated(paneSessions[0]),
        rememberPaneTruncated(paneSessions[1]),
        rememberPaneTruncated(paneSessions[2]),
        rememberPaneTruncated(paneSessions[3]),
    )

    // Remote tmux sessions discovered on servers
    var remoteSessions by remember { mutableStateOf<List<RemoteSession>>(emptyList()) }
    var remoteSessionsLoading by remember { mutableStateOf(false) }
    // The servers whose LAST scan answered (quick access tells "not running" from "no answer" by it).
    var remoteScanOk by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Stale-tab prune strike counter: tab.id → consecutive successful scans of
    // its server that did NOT contain its tmux session. A tab is forgotten only
    // at 2 strikes (see scanRemoteSessions) so one flaky scan can't delete it.
    val stalePruneStrikes = remember { mutableMapOf<String, Int>() }

    // V14/V16: quick connect starts a FRESH claude in the default folder under a name no session holds — see
    // pickQuickConnectName. The listing never pays a handshake of its own (`allowFresh = false`): the exec transport
    // or a counted channel of a transport the server already has; without one, or when it fails or hangs, the
    // launcher's last scan of the server stands in.
    suspend fun quickConnectName(server: SshServer): String = pickQuickConnectName(
        server,
        // R6-1: the names launches are creating right now count as own: a second quick connect joins that launch.
        // N7-3: read once the listing answered, not before it — a launch begun meanwhile is joined, not named around.
        ownTabs = { sessionOrchestrator.ownTmuxNames(server.id) },
        lastScan = remoteSessions.filter { it.server.id == server.id }.map { it.tmuxSession.name },
    ) { com.clauderemote.connection.SshSessionHelper.withSession(server, allowFresh = false) { sess -> TmuxManager.listForFreshName(sess) } }

    // The Connect screen's listing of [server] (C2). V15: a NEW session's name must skip every name in use, not just
    // the listed ones: the launcher's last scan and this device's tabs always, the manifest when the listing ran. A
    // failed listing leaves those, never "nothing is taken" (M2). A retry ([allowFresh] false, H18) never pays a
    // handshake of its own: the exec transport or a transport the server already has, else it fails again at once.
    fun loadConnectListing(server: SshServer, allowFresh: Boolean) {
        val fallbackTaken = remoteSessions.filter { it.server.id == server.id }.map { it.tmuxSession.name }.toSet() +
            tabManager.tabs.value.filter { it.server.id == server.id }.map { it.tmuxSessionName }
        val opening = connectListing.open(fallbackTaken)
        scope.launch {
            connectListing.load(opening, fallbackTaken) {
                com.clauderemote.connection.SshSessionHelper.withSession(server, allowFresh = allowFresh) { sess -> TmuxManager.listForFreshName(sess) }
            }
        }
    }

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

    // The scan itself, awaited by quick access (Jarvis is attached only where a fresh scan saw him).
    suspend fun scanRemoteSessionsNow() {
        val servers = serverList
        if (servers.isEmpty()) return
        run {
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
                remoteScanOk = perServer.filterValues { it != null }.keys
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

    fun scanRemoteSessions() {
        if (serverList.isEmpty()) return
        scope.launch { scanRemoteSessionsNow() }
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
        if (currentScreen == Screen.LAUNCHER || currentScreen == Screen.VELIN) scanRemoteSessions()
    }
    // Scan transcript history whenever the history browser is opened.
    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.HISTORY) scanHistory()
    }
    // Periodically refresh remote sessions while on terminal screen
    // so the side panel stays up-to-date (and the Velín's list of sessions not open here)
    LaunchedEffect(currentScreen) {
        if (currentScreen == Screen.TERMINAL || currentScreen == Screen.VELIN) {
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

    // Open a tmux session the server runs but no tab shows (Launcher and Velín alike).
    fun attachRemote(remote: RemoteSession) {
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
    }

    // ── Rychlý přístup: Jarvis a Velín odkudkoli (tlačítko v liště, Ctrl+Shift+J/D, paleta, zkratky, dlaždice) ──
    // "Jarvis neběží" (or no server answered): a short notice in the snackbar slot, then the Velín.
    var quickNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(quickNotice) {
        if (quickNotice != null) {
            kotlinx.coroutines.delay(QUICK_NOTICE_MS)
            quickNotice = null
        }
    }
    // One Jarvis lookup at a time: a second tap while the scan runs would only attach twice (launchSession joins it,
    // but there is nothing to gain).
    var jarvisLookup by remember { mutableStateOf(false) }

    fun openVelin(tab: VelinTab = VelinTab.PREHLED) {
        velinTab = tab
        currentScreen = Screen.VELIN
    }

    // Jarvis' tab, else attach to where a fresh scan sees him live (the remote-row path, never a new session), else
    // the notice and the Velín. Reads the flows' current values: a shortcut or a menu may hold an older lambda.
    fun openJarvis() {
        if (jarvisLookup) return
        jarvisLookup = true
        scope.launch {
            try {
                fun routeNow() = com.clauderemote.session.QuickAccess.route(
                    tabs = tabManager.tabs.value,
                    hiddenIds = pendingCloses.state.value.hiddenIds,
                    remote = remoteSessions,
                    scannedServerIds = remoteScanOk,
                )
                var route = routeNow()
                if (com.clauderemote.session.QuickAccess.needsFreshScan(route, tabManager.tabs.value)) {
                    scanRemoteSessionsNow()
                    route = com.clauderemote.session.QuickAccess.settle(routeNow())
                }
                FileLogger.log("App", "quick access: Jarvis -> $route")
                when (route) {
                    is com.clauderemote.session.JarvisRoute.SwitchTab -> {
                        // a tab whose close waits out its undo window: opening it is "Vrátit" (as in the Velín)
                        if (route.reclaim) sessionOrchestrator.reclaimPendingClose(route.tabId)
                        if (tabManager.getTab(route.tabId) != null) {
                            switchActiveSession(route.tabId)
                            currentScreen = Screen.TERMINAL
                        }
                    }
                    is com.clauderemote.session.JarvisRoute.Attach -> attachRemote(route.remote)
                    else -> {
                        quickNotice = com.clauderemote.session.QuickAccess.notRunningNotice(remoteScanOk.isNotEmpty())
                        openVelin()
                    }
                }
            } finally {
                jarvisLookup = false
            }
        }
    }

    fun openQuick(target: com.clauderemote.session.QuickTarget) = when (target) {
        com.clauderemote.session.QuickTarget.JARVIS -> openJarvis()
        com.clauderemote.session.QuickTarget.VELIN -> openVelin()
        com.clauderemote.session.QuickTarget.JARVIS_NEWS -> openVelin(VelinTab.JARVIS)
    }

    // Requests from outside (launcher shortcuts, the tile): taken once, then cleared.
    LaunchedEffect(quickAccessRequests) {
        val requests = quickAccessRequests ?: return@LaunchedEffect
        requests.collect { t ->
            if (t != null && requests.compareAndSet(t, null)) openQuick(t)
        }
    }

    // ── Nedávno zavřené, obnova a zaparkované taby (Jarvis F0b/F0c, §3.9, §15.4) ───────────────────────────
    // The raw reads per server; the rows are derived at composition, so a later remote scan or tab change filters
    // out what runs again.
    var closedReads by remember {
        mutableStateOf<List<Pair<Pair<String, String>, List<com.clauderemote.session.service.ClosedRecord>>>>(emptyList())
    }
    var closedFailed by remember { mutableStateOf<List<String>>(emptyList()) }
    var closedPartial by remember { mutableStateOf(false) }
    var closedLoading by remember { mutableStateOf(false) }
    var closedLoaded by remember { mutableStateOf(false) }
    var closedNowS by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    var closedExpanded by remember { mutableStateOf(false) }
    var closedListDialog by remember { mutableStateOf(false) }
    var restoringClosed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var restoreOutcome by remember { mutableStateOf<RestoreOutcome?>(null) }
    // the palette rows are built before startRestore exists below; they call it through this
    val startRestoreRef = remember { java.util.concurrent.atomic.AtomicReference<(com.clauderemote.session.ClosedEntry) -> Unit>({}) }
    val parkedTabs by sessionOrchestrator.parkedTabs.collectAsState()

    fun currentClosedEntries(): List<com.clauderemote.session.ClosedEntry> {
        val live = tabManager.tabs.value.mapTo(HashSet()) { it.server.id to it.tmuxSessionName } +
            remoteSessions.map { it.server.id to it.tmuxSession.name }
        return com.clauderemote.session.RecentlyClosed.merge(closedReads, live, closedNowS)
    }
    val closedEntries = remember(closedReads, tabs, remoteSessions, closedNowS) { currentClosedEntries() }
    val closedUi = RecentlyClosedUi(closedEntries, closedFailed, closedLoading, closedLoaded, closedPartial)
    // remembered: the palette rebuilds its actions whenever this list is a new one
    val closedPaletteEntries = remember(closedEntries, closedNowS) {
        closedEntries.take(10).map { e ->
            val label = listOfNotNull(
                com.clauderemote.session.RecentlyClosed.label(e.record, e.serverName),
                com.clauderemote.session.RecentlyClosed.folderLeaf(e.record.restoreFolder),
                com.clauderemote.session.RecentlyClosed.whenLabel(e.record.atEpochSeconds, closedNowS),
            ).joinToString(" · ")
            label to { startRestoreRef.get()(e) }
        }
    }

    // The hide list lives in this device's settings; adoption adopts nothing until it is wired.
    LaunchedEffect(Unit) {
        sessionOrchestrator.hiddenAdoptionsStore = object : com.clauderemote.session.HiddenAdoptionsStore {
            override fun load(): String = appSettings.hiddenAdoptions
            override fun save(value: String) { appSettings.hiddenAdoptions = value }
        }
    }

    // [full] = pay a handshake for a server without a transport (the user asked: expanded the section, History,
    // Ctrl+Shift+T); otherwise such a server is skipped and the section says no count.
    suspend fun readRecentlyClosed(full: Boolean) {
        val servers = serverList
        closedLoading = true
        try {
            val reads = withContext(Dispatchers.IO) {
                servers.map { s -> async { s to sessionOrchestrator.readClosedRecords(s, allowFresh = full) } }.awaitAll()
            }
            closedReads = reads.mapNotNull { (s, r) ->
                (r as? com.clauderemote.session.ClosedRead.Ok)?.let { (s.id to s.name) to it.records }
            }
            closedFailed = reads.filter { it.second == com.clauderemote.session.ClosedRead.Unknown }.map { it.first.name }
            closedPartial = reads.any { it.second == com.clauderemote.session.ClosedRead.Skipped }
            closedNowS = System.currentTimeMillis() / 1000
            closedLoaded = true
        } finally {
            closedLoading = false
        }
    }

    fun loadRecentlyClosed(full: Boolean = false) {
        if (!closedLoading) scope.launch { readRecentlyClosed(full) }
    }

    fun toggleRecentlyClosed() {
        closedExpanded = !closedExpanded
        if (closedExpanded && (closedPartial || closedFailed.isNotEmpty() || !closedLoaded)) loadRecentlyClosed(full = true)
    }

    LaunchedEffect(currentScreen) {
        when (currentScreen) {
            Screen.LAUNCHER -> loadRecentlyClosed(full = closedExpanded)
            Screen.HISTORY -> loadRecentlyClosed(full = true)
            else -> {}
        }
    }
    // A close that went through writes its record a moment later (tombstone → record → kill in the background).
    var lastPendingCount by remember { mutableStateOf(0) }
    LaunchedEffect(pendingCloseState.pending.size) {
        val n = pendingCloseState.pending.size
        val fewer = n < lastPendingCount
        lastPendingCount = n
        if (fewer && closedLoaded) {
            kotlinx.coroutines.delay(4_000)
            loadRecentlyClosed()
        }
    }

    // The restore itself, awaited: the row's tap launches it, "Vrátit" of a Jarvis close runs it inside the undo's
    // in-flight hold, so a second tap anywhere finds it running (rev-f4-app MED-1).
    suspend fun runClosedRestore(
        server: SshServer,
        entry: com.clauderemote.session.ClosedEntry,
        tmuxName: String = entry.record.tmuxName,
        sessionId: String? = null,
        freshSessionId: String? = null,
    ) {
        restoringClosed = restoringClosed + entry.key
        try {
            val (result, tab) = sessionOrchestrator.restoreClosed(server, entry.record, tmuxName, sessionId, freshSessionId)
            val action = com.clauderemote.session.RecentlyClosed.actionFor(result, entry.record, server.name)
            if (action is com.clauderemote.session.RestoreAction.OpenTab && tab != null) {
                closedListDialog = false
                currentScreen = Screen.TERMINAL
                if (action.loginRequired) restoreOutcome = RestoreOutcome(entry, action)
            } else {
                restoreOutcome = RestoreOutcome(entry, action)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            restoreOutcome = RestoreOutcome(entry, com.clauderemote.session.RestoreAction.Message(
                "Obnova selhala", e.message ?: e.javaClass.simpleName))
        } finally {
            restoringClosed = restoringClosed - entry.key
            scanRemoteSessions()
            loadRecentlyClosed()
        }
    }

    // A restore of [entry] under [tmuxName] (a confirmed new name, a confirmed candidate, a new conversation). The
    // server never kills anything and lifts the tombstone first; the result decides what the user sees next.
    fun restoreClosedEntry(
        entry: com.clauderemote.session.ClosedEntry,
        tmuxName: String = entry.record.tmuxName,
        sessionId: String? = null,
        freshSessionId: String? = null,
    ) {
        if (entry.key in restoringClosed) return
        restoreOutcome = null
        val server = serverList.firstOrNull { it.id == entry.serverId }
        if (server == null) {
            restoreOutcome = RestoreOutcome(entry, com.clauderemote.session.RestoreAction.Message(
                "Server chybí", "Server ${entry.serverName} už v aplikaci není."))
            return
        }
        restoringClosed = restoringClosed + entry.key   // at once: a second tap before the launch runs sees it
        scope.launch { runClosedRestore(server, entry, tmuxName, sessionId, freshSessionId) }
    }

    // A row's tap: resume its conversation; a record without one starts a new conversation in the same folder and account.
    fun startRestore(entry: com.clauderemote.session.ClosedEntry) {
        when {
            com.clauderemote.session.RecentlyClosed.canResume(entry.record) -> restoreClosedEntry(entry)
            com.clauderemote.session.RecentlyClosed.canStartFresh(entry.record) ->
                restoreClosedEntry(entry, freshSessionId = java.util.UUID.randomUUID().toString())
        }
    }

    startRestoreRef.set { startRestore(it) }

    // "Vrátit" of a close Jarvis announced (the phone notification's action, the Velín's news line): closed.jsonl
    // decides whether it can still be undone (JarvisUndo.pick), then it is the row's own restore with its dialogs.
    fun undoJarvisClose(req: com.clauderemote.session.JarvisUndoRequest) {
        val server = serverList.firstOrNull { it.id == req.serverId }
        if (server == null) {
            restoreOutcome = RestoreOutcome(null, com.clauderemote.session.RestoreAction.Message(
                "Server chybí", "Server, na kterém „${com.clauderemote.session.JarvisUndo.alias(req.tmux)}“ běžela, už v aplikaci není."))
            return
        }
        scope.launch {
            // the same in-flight set as the watch's undo, held over the read AND the restore (MED-1)
            val ran = sessionOrchestrator.jarvisUndoInFlight.run(server.id, req.tmux) {
                when (val p = sessionOrchestrator.findJarvisUndo(server, req.tmux)) {
                    is com.clauderemote.session.JarvisUndo.Pick.Found -> {
                        val entry = com.clauderemote.session.ClosedEntry(server.id, server.name, p.record)
                        // a row tap on the same record already restoring it: that one decides
                        if (entry.key !in restoringClosed) {
                            restoreOutcome = null
                            runClosedRestore(
                                server, entry,
                                freshSessionId = if (p.fresh) java.util.UUID.randomUUID().toString() else null,
                            )
                        }
                    }
                    is com.clauderemote.session.JarvisUndo.Pick.No -> restoreOutcome =
                        RestoreOutcome(null, com.clauderemote.session.RestoreAction.Message("Nejde vrátit", p.why))
                }
            }
            if (ran == null) restoreOutcome = RestoreOutcome(null, com.clauderemote.session.RestoreAction.Message(
                "Už se vrací", com.clauderemote.session.JarvisUndo.busyText(req.tmux)))
        }
    }

    // Taken once, then cleared (like the quick-access requests).
    LaunchedEffect(Unit) {
        val requests = sessionOrchestrator.jarvisUndoRequests
        requests.collect { r ->
            if (r != null && requests.compareAndSet(r, null)) undoJarvisClose(r)
        }
    }

    // "Ta konverzace běží v X": its tab here, else attach to it the way a remote row is opened.
    fun goToRunning(entry: com.clauderemote.session.ClosedEntry, tmuxName: String) {
        restoreOutcome = null
        if (!com.clauderemote.session.RecentlyClosed.canGoTo(tmuxName)) return
        closedListDialog = false
        tabManager.tabs.value.firstOrNull { it.server.id == entry.serverId && it.tmuxSessionName == tmuxName }?.let {
            switchActiveSession(it.id)
            currentScreen = Screen.TERMINAL
            return
        }
        val server = serverList.firstOrNull { it.id == entry.serverId } ?: return
        scope.launch {
            try {
                connectionError = null
                sessionOrchestrator.launchSession(
                    server = server,
                    folder = entry.record.restoreFolder ?: server.defaultFolder,
                    mode = entry.record.claudeMode,
                    model = entry.record.claudeModel,
                    connectionType = ConnectionType.SSH,
                    tmuxSessionName = tmuxName,
                    isNewTmuxSession = false,
                    resumeClaudeSessionId = entry.record.sessionId ?: entry.record.launchSessionId,
                    accountSlug = entry.record.account,
                )
                currentScreen = Screen.TERMINAL
            } catch (e: Exception) {
                connectionError = e.message
            }
        }
    }

    // Ctrl/Cmd+Shift+T: a close still in its undo window is undone (free: the pane never died); else the newest
    // session THIS device closed is restored, read fresh. Nothing of ours to restore → the list (with the others').
    fun reopenLastClosed() {
        val ownBy = sessionOrchestrator.ownCloseBy
        val undo = com.clauderemote.session.RecentlyClosed.reopenTarget(pendingCloses.state.value.pending.map { it.sessionId }, emptyList(), ownBy)
        if (undo is com.clauderemote.session.ReopenTarget.Undo) {
            afterUndo(listOfNotNull(pendingCloses.undo(undo.sessionId)))
            return
        }
        scope.launch {
            if (!closedLoading) readRecentlyClosed(full = true)
            when (val t = com.clauderemote.session.RecentlyClosed.reopenTarget(emptyList(), currentClosedEntries(), ownBy)) {
                is com.clauderemote.session.ReopenTarget.Restore -> startRestore(t.entry)
                else -> closedListDialog = true
            }
        }
    }

    // "Skrýt z tohoto zařízení": the tab goes from this device only; the session runs on (a remote row here).
    fun hideTab(id: String) {
        sessionOrchestrator.hideFromThisDevice(id)
        if (visibleTabs.none { it.id != id } && currentScreen == Screen.TERMINAL) currentScreen = Screen.LAUNCHER
    }

    // A parked tab (adopted from the server) gets its shell when the user opens it, not before.
    LaunchedEffect(activeTabId, currentScreen, parkedTabs) {
        val id = activeTabId ?: return@LaunchedEffect
        // its own scope: reconnectSession unparks at once, which restarts this effect
        if (currentScreen == Screen.TERMINAL && id in parkedTabs) scope.launch { sessionOrchestrator.reconnectSession(id) }
    }

    @Composable
    fun recentlyClosedSection(compact: Boolean, showEmpty: Boolean, collapsible: Boolean, onHistory: (() -> Unit)?) {
        RecentlyClosedSection(
            ui = closedUi,
            ownBy = sessionOrchestrator.ownCloseBy,
            nowEpochS = closedNowS,
            expanded = closedExpanded || !collapsible,
            onToggle = if (collapsible) ({ toggleRecentlyClosed() }) else null,
            restoring = restoringClosed,
            onRestore = { startRestore(it) },
            onRetry = { loadRecentlyClosed(full = true) },
            onHistory = onHistory,
            showEmpty = showEmpty,
            compact = compact,
        )
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
        // Ctrl+Shift+J / Ctrl+Shift+D on every screen; first, so the terminal's own handler never sees them.
        Box(modifier = Modifier.fillMaxSize().then(invertModifier).onPreviewKeyEvent { e ->
            when (AppShortcuts.match(e)) {
                AppShortcut.JARVIS -> { openJarvis(); true }
                AppShortcut.VELIN -> { openVelin(); true }
                else -> false
            }
        }) {
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

            // Read-aloud transport (only visible while something is read).
            TtsPlayerBar()

            // Main content
            when (currentScreen) {
                Screen.LAUNCHER -> {
                    LauncherScreen(
                        servers = serverList,
                        serverHealth = serverHealth,
                        onProbeServers = { force -> sessionOrchestrator.probeServers(serverList, force) },
                        activeSessions = visibleTabs,
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
                                visibleTabs.filter { it.status != SessionStatus.ACTIVE }
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
                        onRestartAllSessions = {
                            scope.launch { sessionOrchestrator.restartClaudeForAllSessions() }
                        },
                        onAttachRemote = { remote -> attachRemote(remote) },
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
                                        tmuxSessionName = quickConnectName(server),
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
                            currentScreen = Screen.CONNECT
                            loadConnectListing(server, allowFresh = true)
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
                        onVelin = { openVelin() },
                        onJarvis = { openJarvis() },
                        // Per-account usage IS the usage screen now: with
                        // several seats provisioned, "which account still has
                        // room" is the question being asked, and the
                        // per-session dashboard can only answer it for the
                        // active login.
                        onUsageDashboard = {
                            usageReturnScreen = Screen.LAUNCHER
                            currentScreen = Screen.ACCOUNT_USAGE
                        },
                        onCheckUpdate = { checkForUpdate() },
                        sessionBadges = parkedTabs.mapValues { com.clauderemote.session.OpenedAdoption.badge(it.value) },
                        recentlyClosed = {
                            recentlyClosedSection(compact = false, showEmpty = false, collapsible = true,
                                onHistory = { currentScreen = Screen.HISTORY })
                        },
                    )
                }

                Screen.CONNECT -> {
                    selectedServer?.let { server ->
                        // C2: Retry needs no connection of its own while the exec transport is up; without it, it may open one
                        val execState by remember(server.id) { sessionOrchestrator.execManager.state(server.id) }.collectAsState()
                        val execUp = connectExecUp(execState is com.clauderemote.session.service.LinkState.Up) {
                            sessionOrchestrator.connectionRegistry.execActive(server.id)
                        }
                        ConnectScreen(
                            server = server,
                            tmuxSessions = connectListing.sessions,
                            tmuxListing = connectListing.loading,
                            takenNames = connectListing.taken,
                            listingFailed = (connectListing.state as? ListingState.Failed)?.reason,
                            onRetryListing = { loadConnectListing(server, allowFresh = connectRetryAllowsFresh(execUp)) },
                            // H16: the names this device's launches are creating right now (not its tabs) are joined, not named around
                            inFlightNames = { connectInFlightNames(sessionOrchestrator.ownTmuxNames(server.id), tabManager.tabs.value, server.id) },
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
                                        // runInterruptible, not a plain blocking
                                        // call. kotlinx cancellation is
                                        // cooperative and this chain has no
                                        // suspension point, so the caller's 15s
                                        // withTimeoutOrNull would mark the job
                                        // cancelled and then sit waiting for the
                                        // read regardless — a timeout in name
                                        // only. This maps cancellation onto
                                        // Thread.interrupt(), which the channel's
                                        // stream honours: it is a
                                        // PipedInputStream (jsch 0.2.21:
                                        // PassiveInputStream -> MyPipedInputStream
                                        // -> java.io.PipedInputStream), whose
                                        // read() waits in wait(1000) loops and
                                        // turns InterruptedException into
                                        // InterruptedIOException — an IOException
                                        // the catch below already handles.
                                        suspend fun exec(cmd: String): String =
                                            kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                                            val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
                                            try {
                                                ch.setCommand(cmd)
                                                // Two unrelated JSch calls hide
                                                // behind one Kotlin property name:
                                                // setInputStream is the remote
                                                // STDIN (we send nothing) and
                                                // getInputStream is its STDOUT.
                                                // Spelled out so the asymmetry
                                                // does not read as a typo. The
                                                // getter must precede connect().
                                                ch.setInputStream(null)
                                                val input = ch.getInputStream()
                                                ch.connect(8000)
                                                // Bounded read: MAX_DIRS is
                                                // enforced only by `head` INSIDE
                                                // the remote command, i.e. only by
                                                // a cooperative server. A wedged or
                                                // hostile one that streams forever
                                                // would OOM the client through
                                                // readText().
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
                                                sb.toString()
                                            } finally {
                                                // The CHANNEL only, never the
                                                // session: withSession may have
                                                // handed us a pooled transport
                                                // other features are using, and it
                                                // owns that lifetime. In a finally
                                                // because a throwing or interrupted
                                                // read used to leak the channel —
                                                // disconnect sat on the success
                                                // path alone.
                                                try { ch.disconnect() } catch (_: Exception) {}
                                            }
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
                                        com.clauderemote.connection.SshSessionHelper.withSession(server) { sess ->
                                            TmuxManager.killSession(sess, sessionName)
                                        }
                                    } catch (_: Exception) {}
                                    // listed again like a retry (a failed listing is "unavailable", never "0 on server"), and only
                                    // while this server's screen is still shown: never another server's screen (kolo 2)
                                    if (reloadAfterKill(currentScreen == Screen.CONNECT, selectedServer?.id, server.id)) {
                                        // kolo 3: the exec state NOW, after the kill — not the one captured when the screen was drawn
                                        val execUpNow = connectExecUp(sessionOrchestrator.execManager.state(server.id).value is com.clauderemote.session.service.LinkState.Up) {
                                            sessionOrchestrator.connectionRegistry.execActive(server.id)
                                        }
                                        loadConnectListing(server, allowFresh = connectRetryAllowsFresh(execUpNow))
                                    }
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
                    val activeHistoryTruncated: Boolean = activeTabId?.let { id ->
                        val claudeUuid = tabs.firstOrNull { it.id == id }?.claudeSessionId
                        val flow = remember(id, claudeUuid) {
                            sessionOrchestrator.transcriptTruncatedFlow(id)
                        }
                        flow.collectAsState().value
                    } ?: false
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
                        tabs = visibleTabs,
                        activeTabId = activeTabId,
                        appSettings = appSettings,
                        onUsage = {
                            usageReturnScreen = Screen.TERMINAL
                            currentScreen = Screen.ACCOUNT_USAGE
                        },
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
                        onTabClose = { id -> beginTabClose(id) },
                        onSessionLongPress = { id -> sessionMenuId = id },
                        onNewTab = { currentScreen = Screen.LAUNCHER },
                        onMenuOpen = { currentScreen = Screen.LAUNCHER },
                        onSendCommand = { cmd ->
                            activeTabId?.let { sessionOrchestrator.sendClaudeCommand(it, cmd) }
                        },
                        // kolo 6 (adv M2): the tab is read once, at the send
                        onSendMessage = { text -> activeTabId?.let { sessionOrchestrator.sendMessage(it, text) } },
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
                        onLoadSessionFile = { id, path ->
                            val folder = tabManager.getTab(id)?.folder ?: "~"
                            // Capped on the SERVER side, before any transfer:
                            // inline images start fetching on render, with no
                            // tap, so an oversized file must never cross the link.
                            sessionOrchestrator.downloadFileCapped(
                                id, resolveSessionPath(path, folder), INLINE_MAX_BYTES, INLINE_TIMEOUT_MS,
                            )
                        },
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
                        loginExpiry = activeTabId?.let { loginExpiryWarnings[it] },
                        hardwareKeyboard = hardwareKeyboard,
                        externalShortcuts = externalShortcuts,
                        onFocusTerminal = onFocusTerminal,
                        onKeyboardModeChange = onKeyboardModeChange,
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
                        onNavigate = { target ->
                            // This navigation starts FROM the terminal, so Usage
                            // opened through it must come back here — otherwise
                            // Back drops you in the launcher and you lose the
                            // session you were reading. (The usage screens are
                            // the only ones that track a return point; the rest
                            // go home by design.)
                            if (target == "dashboard" || target == "account-usage") {
                                usageReturnScreen = Screen.TERMINAL
                            }
                            currentScreen = when (target) {
                                "settings" -> Screen.SETTINGS
                                "dashboard" -> Screen.USAGE_DASHBOARD
                                "account-usage" -> Screen.ACCOUNT_USAGE
                                "logs" -> Screen.LOG_VIEWER
                                "velin" -> Screen.VELIN
                                // Jarvis decides the screen itself (his tab, or the Velín when he is not running)
                                "jarvis" -> {
                                    openJarvis()
                                    currentScreen
                                }
                                "launcher" -> Screen.LAUNCHER
                                // the palette's "Obnovit zavřenou…": a list over the terminal, the screen stays
                                "recently-closed" -> {
                                    closedListDialog = true
                                    loadRecentlyClosed(full = true)
                                    currentScreen
                                }
                                else -> Screen.LAUNCHER
                            }
                        },
                        onReopenClosed = { reopenLastClosed() },
                        onOpenJarvis = { openJarvis() },
                        onOpenVelin = { openVelin() },
                        closedPaletteEntries = closedPaletteEntries,
                        onPaletteOpened = { loadRecentlyClosed() },
                        sidePanelFooter = {
                            recentlyClosedSection(compact = true, showEmpty = false, collapsible = true, onHistory = null)
                        },
                        onTerminalViewChange = { tv -> updateAppearance(appearance.copy(terminalView = tv)) },
                        terminalContent = terminalContent,
                        terminalScrolledUp = terminalScrolledUp,
                        terminalPendingOutput = terminalPendingOutput,
                        onJumpToLatest = onJumpToLatest,
                        transcriptEntries = activeTranscript,
                        transcriptHistoryTruncated = activeHistoryTruncated,
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
                        paneHistoryTruncated = paneHistoryTruncated,
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
                                val others = visibleTabs.map { it.id }.filter { it != active }
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
                                folderPolicyStorage.get(
                                    t.server.id, t.folder,
                                    com.clauderemote.model.policyHomeFor(t.server.username),
                                )
                            }
                        },
                        accountTags = buildAccountTags(terminalAccounts, tabs) { slugs ->
                            accountColorStorage.assign(slugs).mapValues { it.value.color }
                        },
                        fableSessionIds = tabs.filter { runsFable(it, activeModels[it.id]) }.mapTo(HashSet()) { it.id },
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
                        notificationsBlocked = notificationsBlocked,
                        onNotificationSettingsChange = onNotificationSettingsChange,
                        onFontSizeChange = applyTerminalFontSize,
                        onTranscriptFontChange = applyTranscriptFontPercent,
                        // the install / remove buttons of hooks v2 are per server
                        hookServers = serverList.map { it.id to it.name },
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
                        sessions = visibleTabs,
                        sessionActivities = sessionActivities,
                        contextPercents = contextPercents,
                        sessionUsagePercent = activeServerId?.let { sessionUsagePercents[it] },
                        weekUsagePercent = activeServerId?.let { weekUsagePercents[it] },
                        usageTokens = usageTokensState,
                        // Second page now — Back goes to the accounts page, and
                        // the link that used to lead there would point at its
                        // own caller.
                        onBack = { currentScreen = Screen.ACCOUNT_USAGE },
                        onPerAccountUsage = null,
                    )
                }

                Screen.VELIN -> {
                    VelinScreen(
                        tabs = tabs,
                        remoteSessions = remoteSessions,
                        remoteSessionsLoading = remoteSessionsLoading,
                        sessionOrchestrator = sessionOrchestrator,
                        accountTags = buildAccountTags(emptyList(), tabs) { slugs ->
                            accountColorStorage.assign(slugs).mapValues { it.value.color }
                        },
                        onBack = { currentScreen = Screen.LAUNCHER },
                        onRefresh = {
                            sessionOrchestrator.probeServers(serverList, true)
                            scanRemoteSessions()
                        },
                        // The same way the Launcher opens a session: it becomes the active tab. Opening one whose close is
                        // still in its undo window is "Vrátit".
                        onOpenSession = { session ->
                            sessionOrchestrator.reclaimPendingClose(session.id)
                            switchActiveSession(session.id)
                            currentScreen = Screen.TERMINAL
                        },
                        onAttachRemote = { remote -> attachRemote(remote) },
                        onAccountUsage = {
                            usageReturnScreen = Screen.VELIN
                            currentScreen = Screen.ACCOUNT_USAGE
                        },
                        // Settings may have answered it meanwhile (choosing a start screen there counts).
                        onStartOffer = if (velinStartOfferPending && !appSettings.velinStartOffered) { yes ->
                            if (yes) appSettings.startScreen = AppSettings.START_VELIN
                            appSettings.velinStartOffered = true
                            velinStartOfferPending = false
                        } else null,
                        onJarvis = { openJarvis() },
                        alertRouting = alertRouting,
                        onAlertRouting = { r ->
                            appSettings.alertRouting = r
                            alertRouting = r
                            // the watch copies the routing (its completion alerts, the merged notification)
                            sessionOrchestrator.jarvisAlerts.noteRoutingChanged()
                        },
                        initialTab = velinTab,
                    )
                }

                Screen.ACCOUNT_USAGE -> {
                    AccountUsageScreen(
                        servers = serverList,
                        sessionOrchestrator = sessionOrchestrator,
                        accountColorStorage = accountColorStorage,
                        onBack = { currentScreen = usageReturnScreen },
                        onManageAccounts = { currentScreen = Screen.ACCOUNTS },
                        onSessionUsage = { currentScreen = Screen.USAGE_DASHBOARD },
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
                            // Switch to the already-open tab (uuid matched). Opening one whose close is still in its
                            // undo window is "Vrátit", as from Velín and a notification.
                            val tab = tabs.firstOrNull { it.claudeSessionId == hist.uuid }
                            if (tab != null) {
                                sessionOrchestrator.reclaimPendingClose(tab.id)
                                switchActiveSession(tab.id)
                                currentScreen = Screen.TERMINAL
                            }
                            // No remote-tmux fallback here: if uuid is in liveUuids
                            // there must be an open tab. If none, fall through silently
                            // (stale snapshot — next scan will correct it).
                        },
                        recentlyClosed = {
                            recentlyClosedSection(compact = false, showEmpty = true, collapsible = false, onHistory = null)
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

        RestoreOutcomeDialog(
            outcome = restoreOutcome,
            onDismiss = { restoreOutcome = null },
            onRestoreAs = { e, name -> restoreClosedEntry(e, tmuxName = name) },
            onRestoreCandidate = { e, id -> restoreClosedEntry(e, sessionId = id) },
            onStartFresh = { e -> restoreClosedEntry(e, freshSessionId = java.util.UUID.randomUUID().toString()) },
            onGoTo = { e, name -> goToRunning(e, name) },
        )

        // The palette's "Obnovit zavřenou…" and an empty Ctrl+Shift+T: the whole list, above the terminal.
        com.clauderemote.ui.components.FloatingDialog(
            visible = closedListDialog,
            onDismiss = { closedListDialog = false },
            theme = com.clauderemote.ui.theme.CRThemeSnapshot.current(),
            title = { Text("Obnovit zavřenou session") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    recentlyClosedSection(compact = true, showEmpty = true, collapsible = false, onHistory = null)
                }
            },
            confirmButton = {
                TextButton(onClick = { closedListDialog = false }) { Text("Zavřít") }
            },
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
                onClose = { sessionMenuId = null; beginTabClose(id) },
                onDismiss = { sessionMenuId = null },
                onHide = { sessionMenuId = null; hideTab(id) },
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
                                tmuxSessionName = quickConnectName(srv),
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

        // Undo snackbar for closed tabs, bottom left, on every screen. FloatingSnackbar for the same reason the
        // dialogs use FloatingDialog: on desktop an in-window overlay would draw under the Raw-view terminal.
        val snackbarTheme = com.clauderemote.ui.theme.CRThemeSnapshot.current()
        // The quick-access notice ("Jarvis neběží") shares the slot: two floating windows would stack on desktop.
        com.clauderemote.ui.components.FloatingSnackbar(
            visible = !pendingCloseState.isEmpty || quickNotice != null,
            theme = snackbarTheme,
        ) {
          Column {
            quickNotice?.let { QuickNoticeCard(it) }
            if (!pendingCloseState.isEmpty) PendingCloseSnackbar(
                state = pendingCloseState,
                remainingMs = pendingCloses::remainingMs,
                windowMs = pendingCloses.windowMillis,
                onUndo = { id -> afterUndo(listOfNotNull(pendingCloses.undo(id))) },
                onUndoAll = { afterUndo(pendingCloses.undoAll()) },
                onForceClose = { id ->
                    pendingCloses.forceClose(id)
                    val left = tabManager.tabs.value.filter { it.id != id && it.id !in pendingCloses.state.value.hiddenIds }
                    if (left.isEmpty() && currentScreen == Screen.TERMINAL) currentScreen = Screen.LAUNCHER
                },
                onDismissBlocked = pendingCloses::dismissBlocked,
                onHover = pendingCloses::setPaused,
            )
          }
        }
        } // end Box
    }
    }
}

/** How long the quick-access notice ("Jarvis neběží") stays before it goes by itself. */
private const val QUICK_NOTICE_MS = 3_500L

/** The quick-access notice in the snackbar slot: one line, the look of the undo snackbar's card. */
@Composable
private fun QuickNoticeCard(text: String) {
    val c = CRTheme.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    Text(
        text,
        color = c.text,
        style = CRType.cardTitle,
        modifier = Modifier
            .padding(bottom = 6.dp)
            .widthIn(min = 200.dp, max = 440.dp)
            .clip(shape)
            .background(c.surface2)
            .border(1.dp, c.border, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Retries for the account probe — a restored session has no transport yet. */
private const val ACCOUNTS_LOAD_TRIES = 6
private const val ACCOUNTS_LOAD_RETRY_MS = 2000L

/** How long quick connect waits for its listing before the launcher's last scan stands in (K4-2). */
internal const val QUICK_CONNECT_LIST_MS = 3_000L

/** How long the Connect screen waits for its listing before Launch of a new session opens on the other taken names (K5-4). */
internal const val CONNECT_LIST_MS = 6_000L

/**
 * K5-4 / V15-7: the Connect screen's listing of a server, for the sessions it shows and the name of a NEW session.
 * [open] starts an opening of the screen (its taken names = the [open]'s fallback until the listing answers); [load]
 * runs that opening's listing for at most [timeoutMs] — a hung transport or tmux leaves the fallback and opens Launch
 * again instead of holding it on "Listing sessions…" until a keepalive drops the link. Only the LAST opening writes:
 * the late answer of an earlier one (another server, or the same one opened again) is dropped.
 */
internal class ConnectListing(private val timeoutMs: Long = CONNECT_LIST_MS) {
    /** C2: "N on server" only for [ListingState.Loaded]; a listing that failed or did not answer is [ListingState.Failed], never an empty server. */
    var state by mutableStateOf<ListingState>(ListingState.Loading)
        private set
    val sessions: List<TmuxSession> get() = (state as? ListingState.Loaded)?.sessions ?: emptyList()
    /** The listing of the current opening still runs: a NEW session waits for it (its name is picked from it). */
    val loading: Boolean get() = state is ListingState.Loading
    var taken by mutableStateOf<Set<String>>(emptySet())
        private set
    private val openings = java.util.concurrent.atomic.AtomicInteger()

    /** A new opening of the Connect screen (or a retry of its listing); returns the token its [load] carries. */
    fun open(fallbackTaken: Set<String>): Int {
        val opening = openings.incrementAndGet()
        state = ListingState.Loading
        taken = fallbackTaken
        return opening
    }

    suspend fun load(opening: Int, fallbackTaken: Set<String>, list: suspend () -> TmuxManager.FreshNameListing) {
        try {
            val listing = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { list() }
            if (openings.get() != opening) return
            if (listing == null) {
                FileLogger.log("App", "Connect screen: no listing within $timeoutMs ms — new session named from the other taken names")
                state = ListingState.Failed("no answer within ${timeoutMs / 1000} s")
                return
            }
            taken = fallbackTaken + listing.manifestNames
            state = ListingState.Loaded(listing.sessions)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.log("App", "Connect screen: listing failed (${e::class.simpleName}: ${e.message}) — no sessions shown, new session named from the other taken names")
            if (openings.get() == opening) state = ListingState.Failed("${e::class.simpleName}: ${e.message}")
        } finally {
            if (openings.get() == opening && state is ListingState.Loading) state = ListingState.Failed("listing cancelled")
        }
    }
}

/** C2 kolo 2: Retry opens a fresh connection only when the server has no exec transport up (else there is no way out). */
internal fun connectRetryAllowsFresh(execUp: Boolean): Boolean = !execUp

/** C2: the server's exec transport is up (its link is Up AND its channels are active); [execActive] is read only for an Up link. */
internal fun connectExecUp(linkUp: Boolean, execActive: () -> Boolean): Boolean = linkUp && execActive()

/** H16: [serverId]'s in-flight launch names: [own] without the names of this device's [tabs] on that server. */
internal fun connectInFlightNames(own: Set<String>, tabs: List<com.clauderemote.model.ClaudeSession>, serverId: String): Set<String> =
    inFlightTmuxNames(own, tabs.filter { it.server.id == serverId }.map { it.tmuxSessionName }.toSet())

/** H16: of this device's own names ([own]: its tabs and the launches creating a name now), the launches' — not the tabs. */
internal fun inFlightTmuxNames(own: Set<String>, tabNames: Set<String>): Set<String> = own - tabNames

/** C2 kolo 2: a kill re-lists only the screen of the server it killed on, and only while it is shown. */
internal fun reloadAfterKill(connectShown: Boolean, shownServerId: String?, killedServerId: String): Boolean =
    connectShown && shownServerId == killedServerId

/** C2: the Connect screen's listing of a server. */
internal sealed interface ListingState {
    data object Loading : ListingState
    data class Loaded(val sessions: List<TmuxSession>) : ListingState
    data class Failed(val reason: String) : ListingState
}

/**
 * V14/V15: the tmux name of a quick connect — a FRESH claude in [server]'s default folder. A name another session holds
 * gets `-2`, `-3`, … (never an attach to a session this tab did not start); a tab this device already has open under
 * the name, or a launch creating it right now ([ownTabs], `SessionOrchestrator.ownTmuxNames`), is switched to or joined
 * by `launchSession` (R6-1). Taken = the live listing [list] (tmux sessions and
 * the restore manifest's names) ∪ the launcher's [lastScan] of the server. A listing that fails or does not answer
 * within [listTimeoutMs] leaves the last scan, never "nothing is taken"; a name taken since is found taken when the
 * launch creates its session and the next free one is used (V16).
 * Cancellation is passed on, never taken for a failed listing (L5).
 */
internal suspend fun pickQuickConnectName(
    server: SshServer,
    /** Read AFTER the listing (N7-3): a launch that began while the listing ran counts as own, so this one joins it. */
    ownTabs: () -> Set<String>,
    lastScan: Collection<String>,
    listTimeoutMs: Long = QUICK_CONNECT_LIST_MS,
    list: suspend () -> TmuxManager.FreshNameListing,
): String {
    val listed = try {
        kotlinx.coroutines.withTimeoutOrNull(listTimeoutMs) { list() }?.names
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
    if (listed == null) FileLogger.log("App", "quick connect: no listing of ${server.name} — naming from the last scan")
    return com.clauderemote.session.ClaudeConfig.freshTmuxSessionName(
        server.name, server.defaultFolder, server.defaultClaudeMode == ClaudeMode.YOLO,
        taken = (listed.orEmpty() + lastScan) - ownTabs(),
    )
}
