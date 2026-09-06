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
import kotlinx.coroutines.async
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

class MainActivity : FragmentActivity() {

    private lateinit var serverStorage: ServerStorage
    private lateinit var appSettings: AppSettings
    private lateinit var folderPolicyStorage: com.clauderemote.storage.FolderPolicyStorage
    private lateinit var accountColorStorage: com.clauderemote.storage.AccountColorStorage
    private lateinit var tabManager: TabManager
    private lateinit var sessionOrchestrator: SessionOrchestrator
    @Volatile private var terminalHandle: SshTerminalHandle? = null
    private var keyFileCallback: ((String) -> Unit)? = null
    private var attachFileCallback: ((List<Pair<ByteArray, String>>) -> Unit)? = null
    // In-flight "enter this session" request from a notification tap, waiting
    // for restore to produce the tab. Replaced (not stacked) when a second
    // notification is tapped before the first one resolves.
    @Volatile private var pendingSwitchJob: kotlinx.coroutines.Job? = null
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        initApp()

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
        sessionOrchestrator = SessionOrchestrator(serverStorage, tabManager, sessionStorage)
        // Mirror FileLogger to the server (per-install file) — makes phone
        // logs readable from the dev box without adb.
        sessionOrchestrator.startLogShipping(appSettings.installId)
        // Expose to non-Compose entry points (notification RemoteInput reply,
        // future Wear data layer).
        OrchestratorHolder.orchestrator = sessionOrchestrator
        // Keep the Wear companion app's session list in sync (Data Layer).
        WearSync.start(applicationContext, tabManager, sessionOrchestrator)
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

        sessionOrchestrator.onClaudeNeedsInput = { sessionId, hint, isActiveTab, body ->
            val tab = tabManager.getTab(sessionId)
            val title = tab?.tabTitle ?: "Session"
            val fg = isAppInForeground
            FileLogger.log("Notify", "Claude needs input: '$hint' fg=$fg activeTab=$isActiveTab keepAlive=${KeepAliveService.isRunning} notif=${appSettings.notificationsEnabled}")
            KeepAliveService.updateDescription(title)
            if (com.clauderemote.session.service.NotificationPolicy.shouldNotify(
                    appForeground = fg,
                    isActiveTab = isActiveTab,
                    notificationsEnabled = appSettings.notificationsEnabled,
                )
            ) {
                FileLogger.log("Notify", "Sending alert for '$title'")
                // Use the body the orchestrator resolved for THIS completion
                // (cleaned of markdown) so the notification — and the watch —
                // show what Claude actually said, not just the generic hint. It
                // is null when the transcript couldn't be confirmed fresh, in
                // which case we fall back to the hint (never the stale prior
                // turn, which the orchestrator now gates out).
                val notifBody = body
                    ?.let { com.clauderemote.voice.speakableFromMarkdown(it) }
                    ?.takeIf { it.isNotBlank() }
                    ?: hint
                if (appSettings.llmSummaryEnabled && appSettings.llmSummaryPhone) {
                    // Summarize the phone notification too. Off-main + best-effort
                    // (12 s timeout inside summaryFor); post once the LLM answers,
                    // falling back to the raw body on null/failure. summaryFor
                    // shares WearSync's per-(session,text) cache with the watch
                    // push, so the same message is summarized at most once.
                    // GlobalScope (not lifecycleScope) so a backgrounded/finishing
                    // Activity still gets the notification out.
                    GlobalScope.launch(Dispatchers.IO) {
                        val summary = runCatching { WearSync.summaryFor(sessionId, notifBody) }.getOrNull()
                        AlertNotifier.post(applicationContext, sessionId, title, summary ?: notifBody)
                    }
                } else {
                    AlertNotifier.post(applicationContext, sessionId, title, notifBody)
                }
                // Push the freshest message to the watch now — don't wait for
                // WearSync's periodic debounced collector.
                WearSync.pushNow()
            }
        }

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
            App(
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
                    )
                },
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
                        onResize = { cols, rows ->
                            tabManager.activeTabId.value?.let { id ->
                                sessionOrchestrator.resize(id, cols, rows)
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
        // user-visible "I'm back" moment, so sweep here too.
        reconnectDeadTabs("onResume")
    }

    override fun onPause() {
        super.onPause()
        isAppInForeground = false
        KeepAliveService.onAppBackground()
        sessionOrchestrator.setBackgroundMode(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSessionIntent(intent)
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
                sessionOrchestrator.switchTab(sessionId)
            } else {
                FileLogger.log("MainActivity", "Notification switch to $sessionId gave up — tab never restored")
            }
        }
    }

    // Single in-flight reconnect sweep. Flapping networks fire onAvailable in
    // bursts; without this each edge spawned a detached GlobalScope coroutine
    // that piled stale connect attempts (5-10s timeouts each) onto the
    // half-dead link. One sweep at a time; a newer trigger replaces the wait.
    @Volatile private var reconnectSweepJob: kotlinx.coroutines.Job? = null
    // Guards the check-and-launch below: onAvailable can fire from a binder
    // thread while onResume fires on the main thread, so a plain @Volatile
    // read-then-write race could let two bursty callbacks both see
    // isActive==false and launch overlapping sweeps.
    private val reconnectSweepLock = Any()

    private fun reconnectDeadTabs(reason: String) {
        // DISCONNECTED **and ERROR**: a failed reconnectSession leaves the tab
        // in ERROR, and the old DISCONNECTED-only filter skipped those forever.
        val dead = tabManager.tabs.value.filter {
            it.status == com.clauderemote.model.SessionStatus.DISCONNECTED ||
                it.status == com.clauderemote.model.SessionStatus.ERROR
        }
        if (dead.isEmpty()) return
        synchronized(reconnectSweepLock) {
            if (reconnectSweepJob?.isActive == true) {
                FileLogger.log("Network", "Reconnect sweep already running, skipping ($reason)")
                return
            }
            FileLogger.log("Network", "Reconnect sweep ($reason): ${dead.size} dead tab(s)")
            reconnectSweepJob = GlobalScope.launch(Dispatchers.IO) {
                // Concurrent, not sequential — see restoreAndReconnect's comment.
                // reconnectSession's own single-flight guard makes this safe even
                // if another sweep/trigger is mid-flight for the same tab.
                dead.map { session ->
                    async {
                        try {
                            sessionOrchestrator.reconnectSession(session.id)
                        } catch (_: Exception) {
                            // reconnectSession re-arms its own persistent retry loop.
                        }
                    }
                }.forEach { it.join() }
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            // registerDefaultNetworkCallback — NOT the generic NetworkRequest
            // form — tracks only the network actually carrying our traffic.
            // The old broad "any network with NET_CAPABILITY_INTERNET" request
            // fired onAvailable/onLost for EVERY matching network, including a
            // known Wi-Fi the OS silently validates in the background without
            // ever making it the default route. That both spammed pointless
            // reconnect sweeps AND — worse — could point "lastAvailable" at
            // that non-default network, so when the REAL default actually
            // dropped, onLost's "network != lastAvailable" check discarded it:
            // no immediate teardown, just a silent wait for the ~20s keepalive
            // to notice. With 2-3 known Wi-Fis plus cellular in range that was
            // a frequent multi-second hiccup on every handover. The default
            // callback only ever reports transitions of the actual default
            // network, so there's no second network to get confused with.
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    FileLogger.log("Network", "Default network available, checking for dead sessions")
                    reconnectDeadTabs("onAvailable")
                }

                override fun onLost(network: android.net.Network) {
                    // Always about the default (that's the contract of this
                    // callback variant) — proactively kill the pooled
                    // transports instead of waiting ~20s for keepalive to
                    // notice, turning a handover freeze into a ~1s blip
                    // (reconnect fires on the next onAvailable).
                    FileLogger.log("Network", "Default network lost — tearing down transports")
                    sessionOrchestrator.onNetworkLost()
                }
            })
        } catch (e: Exception) {
            FileLogger.error("Network", "Failed to register network callback", e)
        }
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
