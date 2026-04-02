package com.clauderemote

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.storage.AppSettings
import com.clauderemote.storage.PlatformPreferences
import com.clauderemote.storage.ServerStorage
import com.clauderemote.ui.App
import com.clauderemote.util.FileLogger
import com.clauderemote.util.UpdateInfo
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var serverStorage: ServerStorage
    private lateinit var appSettings: AppSettings
    private lateinit var tabManager: TabManager
    private lateinit var sessionOrchestrator: SessionOrchestrator
    private var terminalWebView: WebView? = null
    private var keyFileCallback: ((String) -> Unit)? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val prefs = PlatformPreferences(this)
        serverStorage = ServerStorage(prefs)
        appSettings = AppSettings(prefs)
        tabManager = TabManager()
        sessionOrchestrator = SessionOrchestrator(serverStorage, tabManager)

        // Wire SSH output → terminal WebView
        sessionOrchestrator.onTerminalOutput = { sessionId, data ->
            writeToTerminal(data)
        }

        sessionOrchestrator.onTabSwitched = { sessionId, bufferedOutput ->
            FileLogger.log("MainActivity", "Tab switched to $sessionId, buffer: ${bufferedOutput.length} chars")
            replayBuffer(bufferedOutput)
        }

        sessionOrchestrator.onSessionDisconnect = { sessionId ->
            FileLogger.log("MainActivity", "Session disconnected: $sessionId")
            // Stop keep-alive when no active sessions remain
            if (tabManager.tabs.value.none { it.status == com.clauderemote.model.SessionStatus.ACTIVE }) {
                KeepAliveService.stop(this)
            }
        }

        // Start/update keep-alive when sessions change
        sessionOrchestrator.onSessionActive = { session ->
            if (appSettings.keepAliveEnabled) {
                KeepAliveService.start(this, "${session.server.name}: ${session.folder}")
            }
        }

        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }

        setContent {
            App(
                serverStorage = serverStorage,
                appSettings = appSettings,
                tabManager = tabManager,
                sessionOrchestrator = sessionOrchestrator,
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
                onTerminalScreenVisible = {
                    // When terminal screen becomes visible, replay active tab buffer
                    val activeId = tabManager.activeTabId.value ?: return@App
                    val buffer = sessionOrchestrator.getBuffer(activeId)
                    if (buffer.isNotEmpty()) {
                        replayBuffer(buffer)
                    }
                },
                terminalContent = { modifier ->
                    TerminalWebView(modifier = modifier)
                }
            )
        }
    }

    private fun replayBuffer(bufferedOutput: String) {
        // Delay to ensure WebView is laid out and visible
        terminalWebView?.postDelayed({
            clearTerminal()
            if (bufferedOutput.isNotEmpty()) {
                writeToTerminal(bufferedOutput)
            }
        }, 150)
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    @Composable
    private fun TerminalWebView(modifier: Modifier) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.setSupportZoom(false)
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    setBackgroundColor(0xFF1E1E1E.toInt())

                    addJavascriptInterface(TerminalBridge(), "Android")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            FileLogger.log("MainActivity", "WebView loaded, size: ${view?.width}x${view?.height}px")
                            // Apply saved font size
                            val savedSize = appSettings.terminalFontSize
                            if (savedSize != 14) {
                                view?.evaluateJavascript("setFontSize($savedSize)", null)
                            }
                        }
                    }

                    setOnLongClickListener { true }
                    isLongClickable = false
                    isHapticFeedbackEnabled = false

                    setupTouchHandlers(this)
                    loadUrl("file:///android_asset/terminal/terminal.html")
                    terminalWebView = this
                    FileLogger.log("MainActivity", "Terminal WebView created")
                }
            },
            modifier = modifier
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchHandlers(webView: WebView) {
        var twoFingerActive = false
        var twoFingerStartY = 0f
        var twoFingerStartDist = 0f
        var startFontSize = 14
        var mode: String? = null
        var scrollAccum2f = 0f

        var oneFingerFontSize = 14
        var oneFingerStartX = 0f
        var oneFingerStartY = 0f
        var oneFingerLastY = 0f
        var oneFingerScrolling = false
        var longPressHandled = false
        var scrollAccum1f = 0f
        var longPressRunnable: Runnable? = null
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val deadZoneDp = 5f

        webView.setOnTouchListener { v, event ->
            val density = v.resources.displayMetrics.density
            val deadZone = deadZoneDp * density

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    oneFingerStartX = event.x
                    oneFingerStartY = event.y
                    oneFingerLastY = event.y
                    oneFingerScrolling = false
                    longPressHandled = false
                    scrollAccum1f = 0f
                    webView.evaluateJavascript(
                        "typeof term!=='undefined'?term.options.fontSize:14"
                    ) { r -> oneFingerFontSize = r?.toIntOrNull() ?: 14 }

                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    val cssX = event.x / density
                    val cssY = event.y / density
                    longPressRunnable = Runnable {
                        if (!oneFingerScrolling && !twoFingerActive) {
                            longPressHandled = true
                            webView.evaluateJavascript("selectWordAt($cssX,$cssY);if(A)A.haptic()", null)
                        }
                    }
                    handler.postDelayed(longPressRunnable!!, 500)
                    false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    oneFingerScrolling = false
                    if (event.pointerCount == 2) {
                        twoFingerActive = true
                        val dx = event.getX(0) - event.getX(1)
                        val dy = event.getY(0) - event.getY(1)
                        twoFingerStartDist = kotlin.math.sqrt(dx * dx + dy * dy)
                        twoFingerStartY = (event.getY(0) + event.getY(1)) / 2f
                        mode = null
                        scrollAccum2f = 0f
                        webView.evaluateJavascript(
                            "typeof term!=='undefined'?term.options.fontSize:14"
                        ) { r -> startFontSize = r?.toIntOrNull() ?: 14 }
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (twoFingerActive && event.pointerCount >= 2) {
                        val dx = event.getX(0) - event.getX(1)
                        val dy = event.getY(0) - event.getY(1)
                        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                        val midY = (event.getY(0) + event.getY(1)) / 2f
                        val distChange = kotlin.math.abs(dist - twoFingerStartDist)
                        val yChange = kotlin.math.abs(midY - twoFingerStartY)

                        if (mode == null && (distChange > 20f || yChange > 15f)) {
                            mode = if (distChange > yChange * 2f) "pinch" else "scroll"
                        }

                        when (mode) {
                            "scroll" -> {
                                val lineHeight = startFontSize * 1.2f * density
                                scrollAccum2f += twoFingerStartY - midY
                                val lines = (scrollAccum2f / lineHeight).toInt()
                                if (lines != 0) {
                                    webView.evaluateJavascript("scrollTerminal($lines)", null)
                                    scrollAccum2f -= lines * lineHeight
                                }
                                twoFingerStartY = midY
                                return@setOnTouchListener true
                            }
                            "pinch" -> {
                                val scale = dist / twoFingerStartDist
                                val newSize = (startFontSize * scale).toInt().coerceIn(8, 32)
                                webView.evaluateJavascript(
                                    "if(term.options.fontSize!==$newSize){term.options.fontSize=$newSize;fitAddon.fit()}", null)
                                return@setOnTouchListener true
                            }
                        }
                        return@setOnTouchListener false
                    }

                    if (event.pointerCount == 1 && !twoFingerActive) {
                        val dx = event.x - oneFingerStartX
                        val dy = event.y - oneFingerStartY
                        if (!oneFingerScrolling && (dx * dx + dy * dy > deadZone * deadZone)) {
                            oneFingerScrolling = true
                            longPressRunnable?.let { handler.removeCallbacks(it) }
                            webView.evaluateJavascript("term.clearSelection()", null)
                        }
                        if (oneFingerScrolling) {
                            val deltaY = oneFingerLastY - event.y
                            oneFingerLastY = event.y
                            val lineHeight = oneFingerFontSize * 1.2f * density
                            scrollAccum1f += deltaY
                            val lines = (scrollAccum1f / lineHeight).toInt()
                            if (lines != 0) {
                                webView.evaluateJavascript("scrollTerminal($lines)", null)
                                scrollAccum1f -= lines * lineHeight
                            }
                        }
                        return@setOnTouchListener true
                    }
                    false
                }
                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    val was2f = twoFingerActive
                    val wasPinch = mode == "pinch"
                    val was1fScroll = oneFingerScrolling
                    val wasLongPress = longPressHandled
                    twoFingerActive = false
                    mode = null
                    oneFingerScrolling = false

                    if (wasPinch) {
                        webView.postDelayed({
                            webView.evaluateJavascript("fitAddon.fit();if(A)A.onTerminalResize(term.cols,term.rows)", null)
                            // Save font size
                            webView.evaluateJavascript("term.options.fontSize") { size ->
                                size?.toIntOrNull()?.let { appSettings.terminalFontSize = it }
                            }
                        }, 100)
                    }

                    if (was2f || was1fScroll || wasLongPress) return@setOnTouchListener true
                    false
                }
                else -> false
            }
        }
    }

    private inner class TerminalBridge {
        @JavascriptInterface
        fun onTerminalInput(data: String) {
            tabManager.activeTabId.value?.let { id ->
                sessionOrchestrator.sendInput(id, data)
            }
        }

        @JavascriptInterface
        fun onTerminalReady(cols: Int, rows: Int) {
            val wv = terminalWebView
            FileLogger.log("MainActivity", "Terminal ready: ${cols}x${rows}, WebView: ${wv?.width}x${wv?.height}px")
            tabManager.activeTabId.value?.let { id ->
                sessionOrchestrator.resize(id, cols, rows)
            }
        }

        @JavascriptInterface
        fun onTerminalResize(cols: Int, rows: Int) {
            tabManager.activeTabId.value?.let { id ->
                sessionOrchestrator.resize(id, cols, rows)
            }
        }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
            haptic()
        }

        @JavascriptInterface
        fun getClipboard(): String {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            return cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                FileLogger.error("MainActivity", "Failed to open URL: $url", e)
            }
        }

        @JavascriptInterface
        fun exportScrollback(content: String) {
            try {
                val dir = File(cacheDir, "exports")
                dir.mkdirs()
                val file = File(dir, "terminal_${System.currentTimeMillis()}.log")
                file.writeText(content)
                val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Export terminal log"))
            } catch (e: Exception) {
                FileLogger.error("MainActivity", "Export failed", e)
            }
        }

        @JavascriptInterface
        fun haptic() {
            val prefs = getSharedPreferences("claude_remote", MODE_PRIVATE)
            if (!prefs.getBoolean("haptic_feedback", false)) return
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(android.os.VibrationEffect.createOneShot(5, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(5)
            }
        }
    }

    private fun writeToTerminal(data: String) {
        val wv = terminalWebView ?: return
        val safe = JSONObject.quote(data)
        wv.post { wv.evaluateJavascript("writeOutput($safe)", null) }
    }

    private fun clearTerminal() {
        val wv = terminalWebView ?: return
        wv.post { wv.evaluateJavascript("clearTerminal()", null) }
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
