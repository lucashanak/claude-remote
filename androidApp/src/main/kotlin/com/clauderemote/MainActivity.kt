package com.clauderemote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.core.content.FileProvider
import com.clauderemote.session.NetworkCallbackHub
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.storage.AppSettings
import com.clauderemote.storage.PlatformPreferences
import com.clauderemote.storage.ServerStorage
import com.clauderemote.terminal.SshTerminal
import com.clauderemote.terminal.SshTerminalHandle
import com.clauderemote.ui.App
import com.clauderemote.util.FileLogger
import com.clauderemote.util.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * How long a notification tap waits for restore to produce its tab before
 * giving up. Generous: a cold start reconnects over SSH (Cloudflare tunnel on a
 * cold radio is seconds), and the cost of waiting is only that the app opens on
 * the previously-active tab, which is where it would have landed anyway.
 */
private const val RESTORE_WAIT_MS = 30_000L

/** requestPermissions code for POST_NOTIFICATIONS. */
private const val NOTIFICATION_PERMISSION_REQUEST = 1001

/**
 * Process-scoped scope for the notification-summary work. Not GlobalScope
 * (an uncaught exception there takes the process down) and not lifecycleScope
 * (a backgrounded or finishing Activity must still get its alert out) — a
 * SupervisorJob on IO keeps one failed summarize from cancelling the rest.
 */
private val notifyScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
)

/**
 * Per-session alert counter. Every completion takes a number before its
 * summary is launched; the summary is only allowed to replace the visible
 * notification while that number is still the newest one. Without it a slow
 * summary for an OLD completion lands after — and overwrites — the alert for
 * a newer one, since both post to the same notification id.
 */
private val alertSeq = java.util.concurrent.ConcurrentHashMap<String, Long>()

private fun nextAlertSeq(sessionId: String): Long =
    alertSeq.compute(sessionId) { _, v -> (v ?: 0L) + 1L }!!

/**
 * Which alert each session shows, so a permission/question alert can be taken down once the
 * session left the dialog: with completion alerts off, nothing else replaces it after the turn.
 */
private val dialogAlerts = com.clauderemote.session.service.DialogAlertTracker()

/** The activity collector feeding [dialogAlerts]; replaced (not stacked) on Activity recreation. */
@Volatile private var dialogAlertJob: kotlinx.coroutines.Job? = null

class MainActivity : FragmentActivity() {

    private lateinit var serverStorage: ServerStorage
    private lateinit var appSettings: AppSettings
    private lateinit var folderPolicyStorage: com.clauderemote.storage.FolderPolicyStorage
    private lateinit var accountColorStorage: com.clauderemote.storage.AccountColorStorage
    private lateinit var tabManager: TabManager
    private lateinit var sessionOrchestrator: SessionOrchestrator
    @Volatile private var terminalHandle: SshTerminalHandle? = null

    // App shortcuts caught by the terminal view's own key hook, handed to the
    // Compose UI to dispatch. Activity-level so the lambda the AndroidView
    // factory captures once never goes stale.
    private val appShortcutEvents =
        kotlinx.coroutines.flow.MutableSharedFlow<com.clauderemote.ui.AppShortcut>(extraBufferCapacity = 8)
    private var keyFileCallback: ((String) -> Unit)? = null
    private var attachFileCallback: ((List<Pair<ByteArray, String>>) -> Unit)? = null
    // In-flight "enter this session" request from a notification tap, waiting
    // for restore to produce the tab. Replaced (not stacked) when a second
    // notification is tapped before the first one resolves.
    @Volatile private var pendingSwitchJob: kotlinx.coroutines.Job? = null
    // Quick access (launcher shortcuts, the quick-settings tile): App takes the request and clears it. A StateFlow, so
    // a request from onCreate waits for the first composition instead of being dropped.
    private val quickAccessRequests =
        kotlinx.coroutines.flow.MutableStateFlow<com.clauderemote.session.QuickTarget?>(null)
    @Volatile private var pendingSaveBytes: ByteArray? = null

    private val keyFilePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val content = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                keyFileCallback?.invoke(content)
            } catch (e: Exception) {
                FileLogger.error("MainActivity", "Failed to read key file", e)
            }
        }
        keyFileCallback = null
    }

    private val importFilePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                // Decoding lives in ServerStorage so both platforms import the
                // same way (lenient about enum values a different app build
                // wrote — see ServerStorage.importServers).
                val count = serverStorage.importServers(json)
                android.widget.Toast.makeText(this, "Imported $count servers", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                FileLogger.error("MainActivity", "Import failed", e)
                android.widget.Toast.makeText(this, "Import failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private val attachFilePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val files = uris.mapNotNull { uri ->
            try {
                val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return@mapNotNull null
                bytes to attachmentName(uri)
            } catch (e: Exception) {
                FileLogger.error("MainActivity", "Failed to read attached file", e)
                null
            }
        }
        attachFileCallback?.invoke(files)
        attachFileCallback = null
    }

    /**
     * Human-readable filename for a picked document.
     *
     * The URI's last path segment is a provider-internal row id
     * ("content://…/document/286496"), so using it produced uploads named
     * "286496.vnd.openxmlformats-officedocument.wordprocessingml.document" —
     * an id with the raw MIME subtype glued on. SAF exposes the real name in
     * OpenableColumns.DISPLAY_NAME; the segment is only a last resort, and
     * then the extension comes from MimeTypeMap ("docx", not the subtype).
     */
    private fun attachmentName(uri: android.net.Uri): String {
        val displayName = try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                }
        } catch (e: Exception) {
            FileLogger.error("MainActivity", "DISPLAY_NAME query failed for $uri", e)
            null
        }
        val raw = displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
            ?: "file_${System.currentTimeMillis()}"
        // Slashes (and a bare "..") would let a document name escape the
        // remote upload dir.
        val cleaned = raw.replace('/', '_').replace('\\', '_').trim()
        val name = if (cleaned.isEmpty() || cleaned.all { it == '.' }) {
            "file_${System.currentTimeMillis()}"
        } else cleaned
        if (name.substringAfterLast('.', "").isNotEmpty()) return name
        val ext = contentResolver.getType(uri)
            ?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: return name
        return "$name.$ext"
    }

    private val saveFilePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val bytes = pendingSaveBytes
        pendingSaveBytes = null
        if (uri != null && bytes != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                android.widget.Toast.makeText(this, "File saved", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                FileLogger.error("MainActivity", "Failed to save file", e)
                android.widget.Toast.makeText(this, "Save failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    @Volatile private var isAppInForeground = false

    /** Completions muted because Jarvis carried them, re-posted if his lease lapses first (Jarvis F3, MED1). */
    private val jarvisMuted = com.clauderemote.session.service.JarvisMutedCompletions<() -> Unit>()

    /**
     * Whether the OS has notifications for this app switched off. Compose
     * state, not a plain read: the user leaves for system settings, flips it,
     * and comes back, so the Settings warning row has to re-evaluate on resume
     * rather than at whatever moment it first composed.
     */
    private val notificationsBlockedState = androidx.compose.runtime.mutableStateOf(false)

    private fun refreshNotificationsBlocked() {
        notificationsBlockedState.value = AlertNotifier.notificationsBlocked(applicationContext)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST
                )
            }
        }
    }

    /**
     * Log what the user actually chose for POST_NOTIFICATIONS. A denial is
     * otherwise invisible: on 33+ `notify()` does not throw when the
     * permission is missing, it is a silent no-op, so the logs used to claim
     * the alert was posted while nothing appeared. SettingsScreen surfaces the
     * blocked state; this makes it diagnosable from the shipped log too.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            FileLogger.log(
                "Notify",
                "POST_NOTIFICATIONS ${if (granted) "granted" else "DENIED"} — " +
                    "system notifications enabled=${!AlertNotifier.notificationsBlocked(this)}",
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        initApp()
        // A recreated activity (savedInstanceState) still carries the intent it was first started with: replaying it
        // would pull the user back to Jarvis.
        if (savedInstanceState == null) handleQuickAccessIntent(intent)

        val prefs = getSharedPreferences("claude_remote", MODE_PRIVATE)
        if (prefs.getBoolean("biometric_lock_enabled", false)) {
            val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
            val biometricPrompt = androidx.biometric.BiometricPrompt(this, executor,
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {}
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode == androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            finishAffinity()
                        }
                    }
                })

            val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Claude Remote")
                .setSubtitle("Authenticate to access")
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            biometricPrompt.authenticate(promptInfo)
        }
    }

    private fun initApp() {
        requestNotificationPermission()

        val prefs = PlatformPreferences(this)
        serverStorage = ServerStorage(prefs)
        appSettings = AppSettings(prefs)
        folderPolicyStorage = com.clauderemote.storage.FolderPolicyStorage(prefs)
        accountColorStorage = com.clauderemote.storage.AccountColorStorage(prefs)
        tabManager = TabManager()
        val sessionStorage = com.clauderemote.storage.SessionStorage(prefs)
        // A recreated activity (a configuration change the manifest does not absorb, the system destroying it) builds a
        // new orchestrator with its own tab list; the one it replaces is unreachable from the UI from here on, but its
        // exec transports would go on reconnecting for ever and its pollers would keep polling through the process-wide
        // SshSessionHelper (now wired to the NEW orchestrator). shutdown() ends all of it and detaches its shells; the
        // tmux sessions stay, and the user only ever sees the new orchestrator's tabs.
        val replacedOrchestrator = OrchestratorHolder.orchestrator
        sessionOrchestrator = SessionOrchestrator(serverStorage, tabManager, sessionStorage)
        // Mirror FileLogger to the server (per-install file) — makes phone
        // logs readable from the dev box without adb.
        sessionOrchestrator.startLogShipping(appSettings.installId)
        // Expose to non-Compose entry points (notification RemoteInput reply,
        // future Wear data layer).
        OrchestratorHolder.orchestrator = sessionOrchestrator
        replacedOrchestrator?.shutdown()
        // Keep the Wear companion app's session list in sync (Data Layer).
        WearSync.start(applicationContext, tabManager, sessionOrchestrator)
        // WearSync already observes the tab list to prune its own per-session
        // maps; ride that signal so the alert counters don't outlive their
        // tabs either.
        WearSync.setPruneHook { liveIds -> alertSeq.keys.retainAll(liveIds); dialogAlerts.retain(liveIds) }
        sessionOrchestrator.onDialogAnswered = { id ->
            if (dialogAlerts.answered(id)) {
                FileLogger.log("Notify", "Dialog alert for $id cleared: the hooks report it answered")
                AlertNotifier.clear(applicationContext, id)
            }
        }
        dialogAlertJob?.cancel()
        dialogAlertJob = notifyScope.launch {
            sessionOrchestrator.sessionActivities.collect { activities ->
                dialogAlerts.staleAfter(activities) { sessionOrchestrator.hasHookEvents(it) }.forEach { id ->
                    FileLogger.log("Notify", "Dialog alert for $id cleared: the session left the dialog")
                    AlertNotifier.clear(applicationContext, id)
                }
            }
        }
        refreshNotificationsBlocked()
        com.clauderemote.connection.MoshManager.init(this)
        com.clauderemote.connection.EtManager.init(this)
        val sshKeyManager = com.clauderemote.connection.SshKeyManager(prefs)

        // Wire SSH output → native TerminalView.
        // String is converted to UTF-8 bytes at this boundary; SshManager's
        // stateful UTF-8 decoder handles partial chars, so valid UTF-8 round-trips
        // byte-accurately. Legacy X10/X11 mouse modes with coordinates > 0x7F are
        // the one known edge case where the REPLACE decoder corrupts bytes.
        sessionOrchestrator.onTerminalOutput = { _, data ->
            terminalHandle?.feedSshBytes(data.toByteArray(Charsets.UTF_8))
        }

        sessionOrchestrator.onTabSwitched = tabSwitch@ { sessionId, bufferedOutput ->
            FileLogger.log("MainActivity", "Tab switched to $sessionId, buffer: ${bufferedOutput.length} chars")
            val handle = terminalHandle ?: return@tabSwitch
            handle.replay(bufferedOutput.toByteArray(Charsets.UTF_8))
            // Force a full tmux redraw after the switch — the replayed tail
            // is a partial frame. kickRedraw issues `tmux refresh-client`
            // (deterministic full repaint; falls back to a SIGWINCH toggle),
            // see SessionOrchestrator.kickRedraw for the rationale.
            handle.view.post {
                val (cols, rows) = handle.currentSize() ?: return@post
                sessionOrchestrator.kickRedraw(sessionId, cols, rows)
            }
        }

        sessionOrchestrator.onSessionDisconnect = { sessionId ->
            FileLogger.log("MainActivity", "Session disconnected: $sessionId")
            if (tabManager.tabs.value.none { it.status == com.clauderemote.model.SessionStatus.ACTIVE }) {
                KeepAliveService.stop(this)
            }
        }

        sessionOrchestrator.onSessionActive = { session ->
            if (appSettings.keepAliveEnabled) {
                KeepAliveService.start(this, "${session.server.name}: ${session.folder}")
            }
        }

        sessionOrchestrator.onClaudeNeedsInput = { sessionId, kind, hint, isActiveTab, body, prompt ->
            val tab = tabManager.getTab(sessionId)
            val title = tab?.tabTitle ?: "Session"
            val fg = isAppInForeground
            FileLogger.log("Notify", "Claude needs input: $kind '$hint' fg=$fg activeTab=$isActiveTab keepAlive=${KeepAliveService.isRunning} notif=${appSettings.notificationsEnabled} taskComplete=${appSettings.notifyOnTaskComplete}")
            // Use the body the orchestrator resolved for THIS completion
            // (cleaned of markdown) so the notification — and the watch —
            // show what Claude actually said, not just the generic hint. It
            // is null when the transcript couldn't be confirmed fresh, in
            // which case we fall back to the hint (never the stale prior
            // turn, which the orchestrator now gates out).
            val notifBody = body
                ?.let { com.clauderemote.voice.speakableFromMarkdown(it) }
                ?.takeIf { it.isNotBlank() }
            val alertText = notifBody ?: hint

            // The watch is a DIFFERENT surface: hand it this turn's resolved
            // body and push immediately, outside the phone's own policy gate.
            // A suppressed phone alert (app foreground on the finishing tab)
            // must still refresh the wrist, which otherwise keeps whatever
            // text the debounced activity-change push happened to capture —
            // typically the PREVIOUS turn's answer, and permanently so.
            // Whether the wrist BUZZES for it is the watch's call, made from
            // the completion setting the payload carries (see WearSync).
            WearSync.setResolvedBody(sessionId, notifBody, prompt)
            // No body means this came from the screen detector, not the Stop
            // hook, so nothing in the payload changes and the watch would read
            // the push as "nothing happened" — deterministically so for a
            // second approval prompt inside one debounce window. Mark the
            // detection itself as the event; the Stop path needs no mark
            // because its resolved body already moves the stamp.
            if (notifBody == null) WearSync.markPromptEvent(sessionId)
            WearSync.pushNow()

            // Jarvis carries a session's completions only with the lease of the session's OWN server (he watches his box).
            val serverId = tab?.server?.id
            val viaJarvis = serverId != null && com.clauderemote.session.service.NotificationPolicy.jarvisCarries(
                appSettings.alertRouting, sessionOrchestrator.jarvisAlerts.alive(serverId),
            )
            if (serverId != null && com.clauderemote.session.service.NotificationPolicy.mutedByJarvis(
                    kind, fg, isActiveTab, appSettings.notificationsEnabled, appSettings.notifyOnTaskComplete, viaJarvis,
                )
            ) {
                // Said after all if Jarvis' lease lapses before he says it (rev-f3-app MED1).
                jarvisMuted.note(serverId, sessionId) {
                    AlertNotifier.post(applicationContext, sessionId, title, alertText, alertText, prompt = prompt)
                    dialogAlerts.posted(sessionId, kind)
                }
            }
            if (com.clauderemote.session.service.NotificationPolicy.shouldNotify(
                    kind = kind,
                    appForeground = fg,
                    isActiveTab = isActiveTab,
                    notificationsEnabled = appSettings.notificationsEnabled,
                    notifyOnTaskComplete = appSettings.notifyOnTaskComplete,
                    viaJarvis = viaJarvis,
                )
            ) {
                FileLogger.log("Notify", "Sending alert for '$title'")
                // Retitle the persistent keep-alive notification only when we
                // are actually notifying about this session — otherwise it
                // announces completions the user deliberately isn't being
                // told about.
                KeepAliveService.updateDescription(title)
                // Post the raw body NOW. The summariser allows 6 s connect +
                // 12 s read, and waiting for it used to mean no alert at all
                // for that whole window.
                val seq = nextAlertSeq(sessionId)
                AlertNotifier.post(applicationContext, sessionId, title, alertText, alertText, prompt = prompt)
                dialogAlerts.posted(sessionId, kind)
                // Only when a real body was resolved. With notifBody null the
                // alert text is the generic hint ("Claude čeká na vstup"),
                // which has nothing to summarize — and summarizing it would
                // spend an LLM call and a cache slot on it.
                if (appSettings.llmSummaryEnabled && appSettings.llmSummaryPhone && notifBody != null) {
                    // Then replace it with the one-sentence summary, but only
                    // while this is still the newest completion for the
                    // session. summaryFor shares WearSync's per-(session,text)
                    // cache with the watch push, so the same message is
                    // summarized at most once. The full body stays the
                    // read-aloud text — "Přehrát" should give the real answer,
                    // not a summary of it.
                    notifyScope.launch {
                        runCatching {
                            val summary = WearSync.summaryFor(sessionId, alertText)
                            if (summary != null && alertSeq[sessionId] == seq) {
                                AlertNotifier.post(applicationContext, sessionId, title, summary, alertText, prompt = prompt)
                            }
                        }.onFailure {
                            FileLogger.log("Notify", "Summary post failed for $sessionId: ${it.message}")
                        }
                    }
                }
            }
        }

        // Jarvis F3: his alerts arrive on the notify tail; one merged notification, the watch gets the count.
        val jarvisBatcher = com.clauderemote.session.service.JarvisAlertBatcher()
        sessionOrchestrator.jarvisAlerts.sink = object : com.clauderemote.session.service.JarvisAlertSink {
            override fun onAlert(
                alert: com.clauderemote.session.service.JarvisAlert,
                feed: com.clauderemote.session.service.JarvisFeed,
            ) {
                val post = jarvisBatcher.decide(
                    alert, appSettings.alertRouting, sessionOrchestrator.jarvisAlerts.alive(alert.serverId), appSettings.notificationsEnabled,
                )
                if (post != null) {
                    JarvisNotifier.post(applicationContext, feed, post.buzz)
                    if (post.buzz) WearSync.noteJarvisBuzz()
                }
                WearSync.pushNow()
            }

            override fun onCleared() {
                JarvisNotifier.cancel(applicationContext)
                WearSync.pushNow()
            }

            override fun onLeaseChanged(edge: com.clauderemote.session.service.JarvisLeaseEdge) {
                if (edge.alive) {
                    JarvisNotifier.cancelFailOpen(applicationContext)
                } else {
                    // What Jarvis was trusted with after his last heartbeat went unsaid: alert it now (MED1).
                    val muted = jarvisMuted.takeAfter(edge.serverId, edge.heartbeatAtMs)
                    if (appSettings.notificationsEnabled) muted.forEach { runCatching { it() } }
                    // Only a connected tail without heartbeats is Jarvis' silence; a dropped network is not (LOW).
                    if (edge.watcherConnected && appSettings.notificationsEnabled &&
                        com.clauderemote.session.service.NotificationPolicy.jarvisFailedOpen(appSettings.alertRouting, false)
                    ) {
                        JarvisNotifier.postFailOpen(applicationContext)
                    }
                }
                WearSync.pushNow()
            }

            override fun onRoutingChanged() {
                WearSync.pushNow()
            }
        }
        sessionOrchestrator.jarvisAlerts.presence = { isAppInForeground }

        // Screen-state reader for the new color-aware prompt detector. The primary
        // terminal emulator reflects ONLY the currently-active tab, so we return
        // null for background sessions (regression vs. the old regex detector: no
        // background-tab notifications in this iteration). Shadow emulators per
        // session would lift this restriction — left for a follow-up.
        sessionOrchestrator.screenReader = { sessionId ->
            withContext(Dispatchers.Main) {
                if (tabManager.activeTabId.value != sessionId) null
                else terminalHandle?.readScreenStateSnapshot()
            }
        }
        sessionOrchestrator.fullScreenReader = { sessionId ->
            withContext(Dispatchers.Main) {
                if (tabManager.activeTabId.value != sessionId) null
                else terminalHandle?.readFullScreenText()
            }
        }

        handleSessionIntent(intent)
        registerNetworkCallback()

        // Resurrect persisted tabs from previous app run. Restore is
        // idempotent inside the orchestrator (configuration changes that
        // recreate the Activity won't double-connect).
        sessionOrchestrator.restoreAndReconnect()

        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }

        setContent {
            // Compose-observable handle ref so the "Jump to latest" pill (rendered
            // in commonMain TerminalScreen) can react to the handle's scroll state.
            // The @Volatile terminalHandle field above is for non-Compose callers.
            val liveHandle = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf<SshTerminalHandle?>(null)
            }
            // Recomposes on attach/detach: the manifest handles keyboard and
            // keyboardHidden config changes, so the activity is NOT recreated
            // and LocalConfiguration simply updates.
            val cfg = androidx.compose.ui.platform.LocalConfiguration.current
            val hardwareKeyboard =
                cfg.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO &&
                    cfg.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
            App(
                hardwareKeyboard = hardwareKeyboard,
                externalShortcuts = appShortcutEvents,
                quickAccessRequests = quickAccessRequests,
                onFocusTerminal = { terminalHandle?.view?.requestFocus() ?: false },
                onKeyboardModeChange = { on ->
                    // Status bar and gesture strip only while keyboard mode is on;
                    // a swipe from the edge brings them back temporarily.
                    val ctl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    if (on) {
                        ctl.systemBarsBehavior =
                            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        ctl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    } else {
                        ctl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    }
                },
                serverStorage = serverStorage,
                appSettings = appSettings,
                folderPolicyStorage = folderPolicyStorage,
                accountColorStorage = accountColorStorage,
                tabManager = tabManager,
                sessionOrchestrator = sessionOrchestrator,
                sshKeyManager = sshKeyManager,
                appVersion = appVersion,
                onInstallUpdate = { apkBytes, info -> installUpdate(apkBytes, info) },
                onGetCurrentApk = {
                    File(applicationInfo.sourceDir).readBytes()
                },
                onShareLog = { log ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, log)
                        putExtra(Intent.EXTRA_SUBJECT, "Claude Remote Debug Log")
                    }
                    startActivity(Intent.createChooser(intent, "Share Log"))
                },
                onOpenUrl = { url ->
                    // Do NOT log `url` — it carries the login/OAuth seam.
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        )
                    } catch (e: Exception) {
                        FileLogger.error("Login", "openUrl failed: ${e.message}", e)
                    }
                },
                onTestNotification = {
                    // Exercises the real alert path (channel, sound, Wear
                    // bridge, Reply + Play actions) on demand. Uses the active
                    // session id if any so Reply targets a real session.
                    val sid = tabManager.activeTabId.value ?: "test-session"
                    val title = tabManager.getTab(sid)?.tabTitle ?: "Test"
                    AlertNotifier.post(
                        applicationContext, sid, title,
                        "Toto je testovací notifikace. Zkus „Odpovědět“ a „Přehrát“.",
                        // What the session shows now, so Reply goes through the same check as
                        // a real alert: text into an idle session, nothing into a dialog it
                        // did not name.
                        prompt = sessionOrchestrator.currentPrompt(sid),
                    )
                },
                notificationsBlocked = { notificationsBlockedState.value },
                // The watch decides itself whether a completion buzzes, from the setting the
                // /sessions payload carries: push it now rather than with the next routine push.
                onNotificationSettingsChange = { WearSync.pushNow() },
                onPickKeyFile = { callback ->
                    keyFileCallback = callback
                    keyFilePicker.launch("*/*")
                },
                onImportServers = {
                    importFilePicker.launch("application/json")
                },
                onPickFile = { callback ->
                    attachFileCallback = callback
                    attachFilePicker.launch(arrayOf("*/*"))
                },
                onSaveFile = { bytes, suggestedName ->
                    pendingSaveBytes = bytes
                    saveFilePicker.launch(suggestedName)
                },
                onTerminalScreenVisible = {
                    val activeId = tabManager.activeTabId.value ?: return@App
                    val buffer = sessionOrchestrator.getBuffer(activeId)
                    if (buffer.isNotEmpty()) {
                        terminalHandle?.replay(buffer.toByteArray(Charsets.UTF_8))
                    }
                    // After the buffer is replayed, force tmux to fully repaint
                    // the pane. Coming back from the transcript view, the
                    // local emulator has the byte history but tmux hasn't
                    // refreshed its render — without a kick the user sees a
                    // near-empty terminal with only the latest line until
                    // they type or click the tab.
                    val handle = terminalHandle ?: return@App
                    handle.view.post {
                        val (cols, rows) = handle.currentSize() ?: return@post
                        sessionOrchestrator.kickRedraw(activeId, cols, rows)
                    }
                },
                onApplyFontSize = { size ->
                    terminalHandle?.applyFontSize(size)
                },
                exitApp = { finishAffinity() },
                onInvertColorsChanged = { invert -> applyInvertLayer(invert) },
                terminalContent = { modifier ->
                    SshTerminal(
                        fontSizeDp = appSettings.terminalFontSize,
                        colorScheme = appSettings.terminalColorScheme,
                        scrollbackRows = appSettings.terminalScrollback.coerceIn(100, 50_000),
                        onUserInput = { bytes ->
                            tabManager.activeTabId.value?.let { id ->
                                sessionOrchestrator.sendBytes(id, bytes)
                                AlertNotifier.clear(applicationContext, id)
                            }
                        },
                        // kolo 6: the emulator's answers to tmux (DA, CPR …) are no user input: they go while the
                        // transport is up, also during a reconnect's attach
                        onEmulatorReply = { bytes ->
                            tabManager.activeTabId.value?.let { id -> sessionOrchestrator.sendTerminalReply(id, bytes) }
                        },
                        onResize = { cols, rows ->
                            tabManager.activeTabId.value?.let { id ->
                                sessionOrchestrator.resize(id, cols, rows)
                            }
                        },
                        onAppShortcutKey = { e ->
                            // The same matcher Compose uses, so a key is an app
                            // shortcut on exactly one rule; null = the terminal's.
                            val sc = com.clauderemote.ui.AppShortcuts.match(androidx.compose.ui.input.key.KeyEvent(e))
                            when {
                                sc == null -> false
                                // A held chord auto-repeats; consume the repeats so
                                // they neither re-fire (sidebar flicker, repeated
                                // close dialogs) nor leak into the terminal.
                                e.repeatCount > 0 -> true
                                else -> { appShortcutEvents.tryEmit(sc); true }
                            }
                        },
                        onSingleTap = {
                            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            val handle = terminalHandle ?: return@SshTerminal
                            imm.showSoftInput(handle.view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                        },
                        onReady = { handle ->
                            terminalHandle = handle
                            liveHandle.value = handle
                            FileLogger.log("MainActivity", "SshTerminal ready")
                            // Replay buffer for active tab if any
                            tabManager.activeTabId.value?.let { id ->
                                val buf = sessionOrchestrator.getBuffer(id)
                                if (buf.isNotEmpty()) handle.replay(buf.toByteArray(Charsets.UTF_8))
                            }
                        },
                        modifier = modifier,
                    )
                },
                // "Jump to latest" pill state — surfaced from the TerminalView
                // scroll listener via the handle's Compose state. Reading .value
                // here ties recomposition to scroll/output changes.
                terminalScrolledUp = liveHandle.value?.scrolledUp?.value == true,
                terminalPendingOutput = liveHandle.value?.pendingOutput?.value == true,
                onJumpToLatest = { liveHandle.value?.scrollToBottom() },
                // #75: keep emulator composed under the Chat overlay so screenReader
                // stays fed and can detect a pending prompt in Chat view (Android only;
                // desktop stays false — SwingPanel bleeds through a Compose overlay).
                composeTerminalUnderTranscript = true,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
        refreshNotificationsBlocked()
        // Re-apply invert layer on resume (layer can be lost across config changes).
        window.decorView.post { applyInvertLayer(appSettings.invertColors) }
        KeepAliveService.onAppForeground()
        sessionOrchestrator.setBackgroundMode(false)
        tabManager.activeTabId.value?.let { AlertNotifier.clear(applicationContext, it) }
        // Reconnect dead tabs on foreground. The network callback alone is not
        // enough: it only fires on an onAvailable EDGE, and after a long
        // background stint (HyperOS battery management kills sockets silently)
        // connectivity is often already up when the user returns — no edge, no
        // reconnect, sessions look dead until an app restart. Resume is the
        // user-visible "I'm back" moment, so sweep here too. The exec transports first (idempotent):
        // a connection that died in the background is back before the tabs ask for it.
        sessionOrchestrator.ensureExecTransports()
        sessionOrchestrator.reconnectDeadTabs("onResume")
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
        KeepAliveService.onAppBackground()
        sessionOrchestrator.setBackgroundMode(true)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app runs the pending tab closes now (Jarvis R7 = 7b): the user who left can no longer press
        // "Vrátit", and a timer in a backgrounded process may never fire. Not on a configuration change — the
        // user is still here, and the recreated activity's new orchestrator restores the tab from storage anyway.
        if (!isChangingConfigurations && ::sessionOrchestrator.isInitialized) {
            sessionOrchestrator.pendingCloses.flush()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSessionIntent(intent)
        handleQuickAccessIntent(intent)
    }

    /**
     * "Jarvis" / "Velín" from a launcher shortcut (through [QuickAccessActivity]) or the quick-settings tile. Consumed
     * like the notification extra: the same Intent comes back on recreation and from Recents, and replaying it would
     * yank the user out of wherever they went since.
     */
    private fun handleQuickAccessIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val target = com.clauderemote.session.QuickAccess.targetOf(
            intent.action, intent.getStringExtra(com.clauderemote.session.QuickAccess.EXTRA_TARGET),
        ) ?: return
        intent.removeExtra(com.clauderemote.session.QuickAccess.EXTRA_TARGET)
        intent.action = Intent.ACTION_MAIN
        setIntent(intent)
        FileLogger.log("MainActivity", "Quick access -> $target")
        quickAccessRequests.value = target
    }

    companion object {
        /** The intent that opens the app on [target]: the running instance gets it in onNewIntent, a cold start in onCreate. */
        fun quickAccessIntent(context: android.content.Context, target: com.clauderemote.session.QuickTarget): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra(com.clauderemote.session.QuickAccess.EXTRA_TARGET, com.clauderemote.session.QuickAccess.extraValue(target))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
    }

    /**
     * Enter the session a notification points at.
     *
     * onCreate runs this BEFORE restoreAndReconnect(), so on a cold start (the
     * usual case — the notification fired because the app was backgrounded, and
     * HyperOS had since reaped it) the tab does not exist yet and an immediate
     * switch is a no-op. Wait for it to appear instead of dropping the tap on
     * the floor, which is what left the user in a different session than the
     * one they answered.
     */
    private fun handleSessionIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra("switch_to_session") ?: return
        // Consume it: the same Intent is redelivered when the Activity is
        // recreated, and replaying the switch would yank the user out of
        // whatever tab they had moved to since.
        intent.removeExtra("switch_to_session")
        setIntent(intent)
        AlertNotifier.clear(applicationContext, sessionId)
        FileLogger.log(
            "MainActivity",
            "Notification tap -> $sessionId (tabs=${tabManager.tabs.value.size}, " +
                "known=${tabManager.getTab(sessionId) != null}, terminal=${terminalHandle != null})"
        )
        pendingSwitchJob?.cancel()
        pendingSwitchJob = GlobalScope.launch(Dispatchers.Main) {
            // Emits the current list first, so an already-restored tab switches
            // on the next main-thread pass rather than waiting for anything.
            val appeared = kotlinx.coroutines.withTimeoutOrNull(RESTORE_WAIT_MS) {
                tabManager.tabs.first { tabs -> tabs.any { it.id == sessionId } }
            } != null
            if (appeared) {
                // A tap on the notification of a tab whose close is still in its undo window (app in front, the
                // shade pulled down) is "Vrátit": the user wants that session, not a terminal the hidden-tab guard
                // switches straight away from before the close kills it.
                sessionOrchestrator.reclaimPendingClose(sessionId)
                sessionOrchestrator.switchTab(sessionId)
            } else {
                FileLogger.log("MainActivity", "Notification switch to $sessionId gave up — tab never restored")
            }
        }
    }

    /**
     * The default-network callback, registered ONCE per process ([NetworkCallbackHub]) on the application's
     * ConnectivityManager and always delivered to the CURRENT orchestrator ([OrchestratorHolder]): a callback per
     * activity instance kept driving the orchestrator of a destroyed activity (a second teardown and a sweep over its
     * old tabs on every network change), and unregistering in onDestroy would stop the reaction to the network while
     * the process lives on in the background without an activity.
     *
     * registerDefaultNetworkCallback — NOT the generic NetworkRequest form — tracks only the network actually carrying
     * our traffic. The old broad "any network with NET_CAPABILITY_INTERNET" request fired onAvailable/onLost for EVERY
     * matching network, including a known Wi-Fi the OS silently validates in the background without ever making it the
     * default route: pointless reconnect sweeps, and a real drop of the default discarded as "not the last available".
     */
    private fun registerNetworkCallback() {
        val cm = applicationContext.getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        NetworkCallbackHub.register(current = { OrchestratorHolder.orchestrator }) { listener ->
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                // C1 (V1 = b): the reaction stays as it is; the log only says which network it was (netId, type) and when
                // Android announced the old one's end (onLosing: the linger after a switch, kritika K-7 / H15)
                override fun onAvailable(network: android.net.Network) {
                    FileLogger.log("Network", "default network available: ${describeNetwork(cm, network)}")
                    listener.onAvailable()
                }
                override fun onLost(network: android.net.Network) {
                    FileLogger.log("Network", "default network lost: netId=$network")
                    listener.onLost()
                }
                override fun onLosing(network: android.net.Network, maxMsToLive: Int) {
                    FileLogger.log("Network", "default network losing: ${describeNetwork(cm, network)} in ${maxMsToLive}ms")
                }
            })
        }
    }

    /** `netId=<id> type=<wifi|cellular|vpn|ethernet|…>` of [network] for the network log lines (diagnostics only). */
    private fun describeNetwork(cm: android.net.ConnectivityManager, network: android.net.Network): String {
        val caps = try { cm.getNetworkCapabilities(network) } catch (_: Exception) { null }
        val type = when {
            caps == null -> "unknown"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        return "netId=$network type=$type"
    }

    /**
     * Toggle a GPU color-matrix inversion on the entire window. Covers Compose
     * rendering plus the native [com.termux.view.TerminalView] embedded via
     * [androidx.compose.ui.viewinterop.AndroidView] — both are children of the
     * same Android view hierarchy, so a hardware layer on the Compose root
     * captures every pixel.
     */
    private fun applyInvertLayer(invert: Boolean) {
        val root = (findViewById<android.view.ViewGroup>(android.R.id.content))
            ?.getChildAt(0) ?: return
        if (invert) {
            val matrix = android.graphics.ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                 0f,-1f, 0f, 0f, 255f,
                 0f, 0f,-1f, 0f, 255f,
                 0f, 0f, 0f, 1f,   0f,
            ))
            val paint = android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
            }
            root.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
        } else {
            root.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
        }
    }

    private fun installUpdate(apkBytes: ByteArray, info: UpdateInfo) {
        try {
            val updateDir = File(cacheDir, "updates")
            updateDir.mkdirs()
            val apkFile = File(updateDir, "update.apk")
            apkFile.writeBytes(apkBytes)
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Install failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
