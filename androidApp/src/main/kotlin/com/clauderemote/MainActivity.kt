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
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : FragmentActivity() {

    private lateinit var serverStorage: ServerStorage
    private lateinit var appSettings: AppSettings
    private lateinit var tabManager: TabManager
    private lateinit var sessionOrchestrator: SessionOrchestrator
    @Volatile private var terminalHandle: SshTerminalHandle? = null
    private var keyFileCallback: ((String) -> Unit)? = null
    private var attachFileCallback: ((List<Pair<ByteArray, String>>) -> Unit)? = null

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
                val imported = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<List<com.clauderemote.model.SshServer>>(json)
                imported.forEach { serverStorage.addServer(it) }
                android.widget.Toast.makeText(this, "Imported ${imported.size} servers", android.widget.Toast.LENGTH_SHORT).show()
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
                val name = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringAfterLast(':')
                    ?: "file_${System.currentTimeMillis()}"
                val ext = contentResolver.getType(uri)?.substringAfter('/')?.let { ".$it" } ?: ""
                val fileName = if (name.contains('.')) name else "$name$ext"
                bytes to fileName
            } catch (e: Exception) {
                FileLogger.error("MainActivity", "Failed to read attached file", e)
                null
            }
        }
        attachFileCallback?.invoke(files)
        attachFileCallback = null
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
        tabManager = TabManager()
        sessionOrchestrator = SessionOrchestrator(serverStorage, tabManager)
        com.clauderemote.connection.MoshManager.init(this)
        val sshKeyManager = com.clauderemote.connection.SshKeyManager(prefs)

        // Wire SSH output → native TerminalView.
        // String is converted to UTF-8 bytes at this boundary; SshManager's
        // stateful UTF-8 decoder handles partial chars, so valid UTF-8 round-trips
        // byte-accurately. Legacy X10/X11 mouse modes with coordinates > 0x7F are
        // the one known edge case where the REPLACE decoder corrupts bytes.
        sessionOrchestrator.onTerminalOutput = { _, data ->
            terminalHandle?.feedSshBytes(data.toByteArray(Charsets.UTF_8))
        }

        sessionOrchestrator.onTabSwitched = { sessionId, bufferedOutput ->
            FileLogger.log("MainActivity", "Tab switched to $sessionId, buffer: ${bufferedOutput.length} chars")
            val handle = terminalHandle ?: return@onTabSwitched
            handle.replay(bufferedOutput.toByteArray(Charsets.UTF_8))
            // Force a full tmux redraw after the switch. Naive toggle
            // resize(c, r-1) + resize(c, r) delivered back-to-back can
            // be coalesced by the kernel — tmux reads *current* pty size
            // == the value it already had, sees no change, skips redraw.
            // Use a delay between the two resizes and make the intermediate
            // size strictly different so SIGWINCH always fires twice.
            handle.view.post {
                val (cols, rows) = handle.currentSize() ?: return@post
                if (cols <= 0 || rows <= 1) return@post
                val conn = sessionOrchestrator.getConnection(sessionId) ?: return@post
                conn.resize(cols, rows - 1)
                handle.view.postDelayed({ conn.resize(cols, rows) }, 80)
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

        sessionOrchestrator.onClaudeNeedsInput = { sessionId, hint, isActiveTab ->
            val tab = tabManager.getTab(sessionId)
            val title = tab?.tabTitle ?: "Session"
            val fg = isAppInForeground
            FileLogger.log("Notify", "Claude needs input: '$hint' fg=$fg activeTab=$isActiveTab keepAlive=${KeepAliveService.isRunning} notif=${appSettings.notificationsEnabled}")
            KeepAliveService.updateDescription(title)
            if ((!fg || !isActiveTab) && appSettings.notificationsEnabled) {
                FileLogger.log("Notify", "Sending alert for '$title'")
                KeepAliveService.sendAlert(sessionId, title, hint)
            }
        }

        handleSessionIntent(intent)
        registerNetworkCallback()

        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }

        setContent {
            App(
                serverStorage = serverStorage,
                appSettings = appSettings,
                tabManager = tabManager,
                sessionOrchestrator = sessionOrchestrator,
                sshKeyManager = sshKeyManager,
                appVersion = appVersion,
                onInstallUpdate = { apkBytes, info -> installUpdate(apkBytes, info) },
                onShareLog = { log ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, log)
                        putExtra(Intent.EXTRA_SUBJECT, "Claude Remote Debug Log")
                    }
                    startActivity(Intent.createChooser(intent, "Share Log"))
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
                onTerminalScreenVisible = {
                    val activeId = tabManager.activeTabId.value ?: return@App
                    val buffer = sessionOrchestrator.getBuffer(activeId)
                    if (buffer.isNotEmpty()) {
                        terminalHandle?.replay(buffer.toByteArray(Charsets.UTF_8))
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
                                KeepAliveService.clearAlert(id)
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
                            FileLogger.log("MainActivity", "SshTerminal ready")
                            // Replay buffer for active tab if any
                            tabManager.activeTabId.value?.let { id ->
                                val buf = sessionOrchestrator.getBuffer(id)
                                if (buf.isNotEmpty()) handle.replay(buf.toByteArray(Charsets.UTF_8))
                            }
                        },
                        modifier = modifier,
                    )
                }
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
        tabManager.activeTabId.value?.let { KeepAliveService.clearAlert(it) }
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

    private fun handleSessionIntent(intent: Intent?) {
        intent?.getStringExtra("switch_to_session")?.let { sessionId ->
            sessionOrchestrator.switchTab(sessionId)
            KeepAliveService.clearAlert(sessionId)
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    FileLogger.log("Network", "Network available, checking for disconnected sessions")
                    val disconnected = tabManager.tabs.value.filter {
                        it.status == com.clauderemote.model.SessionStatus.DISCONNECTED
                    }
                    if (disconnected.isNotEmpty()) {
                            GlobalScope.launch(Dispatchers.IO) {
                            disconnected.forEach { session ->
                                try {
                                    sessionOrchestrator.reconnectSession(session.id)
                                } catch (_: Exception) {}
                            }
                        }
                    }
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
