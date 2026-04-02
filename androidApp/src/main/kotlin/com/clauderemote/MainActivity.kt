package com.clauderemote

import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
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
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var serverStorage: ServerStorage
    private lateinit var appSettings: AppSettings
    private lateinit var tabManager: TabManager
    private lateinit var sessionOrchestrator: SessionOrchestrator
    private var terminalWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val prefs = PlatformPreferences(this)
        serverStorage = ServerStorage(prefs)
        appSettings = AppSettings(prefs)
        tabManager = TabManager()
        sessionOrchestrator = SessionOrchestrator(serverStorage, tabManager)

        // Wire SSH output → terminal WebView (only for active tab)
        sessionOrchestrator.onTerminalOutput = { sessionId, data ->
            writeToTerminal(data)
        }

        // Tab switch: clear terminal, replay buffered output
        sessionOrchestrator.onTabSwitched = { sessionId, bufferedOutput ->
            FileLogger.log("MainActivity", "Tab switched to $sessionId, replaying ${bufferedOutput.length} chars")
            clearTerminal()
            if (bufferedOutput.isNotEmpty()) {
                writeToTerminal(bufferedOutput)
            }
        }

        sessionOrchestrator.onSessionDisconnect = { sessionId ->
            FileLogger.log("MainActivity", "Session disconnected: $sessionId")
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
                onInstallUpdate = { apkBytes, info ->
                    installUpdate(apkBytes, info)
                },
                onShareLog = { log ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, log)
                        putExtra(Intent.EXTRA_SUBJECT, "Claude Remote Debug Log")
                    }
                    startActivity(Intent.createChooser(intent, "Share Log"))
                },
                terminalContent = { modifier ->
                    TerminalWebView(modifier = modifier)
                }
            )
        }
    }

    @Composable
    private fun TerminalWebView(modifier: Modifier) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    // Force MATCH_PARENT — Compose AndroidView doesn't always
                    // propagate height constraints to WebView correctly
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    setBackgroundColor(0xFF1E1E1E.toInt())

                    addJavascriptInterface(TerminalBridge(), "TerminalBridge")

                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                            msg?.let {
                                FileLogger.log("WebView:JS", "${it.messageLevel()} ${it.message()} [${it.sourceId()}:${it.lineNumber()}]")
                            }
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            FileLogger.log("MainActivity", "WebView loaded, size: ${view?.width}x${view?.height}px")
                            view?.evaluateJavascript("typeof Terminal !== 'undefined' ? 'xterm OK' : 'xterm MISSING'") { result ->
                                FileLogger.log("MainActivity", "xterm.js status: $result")
                            }
                        }

                        override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                            FileLogger.error("WebView", "Resource error: ${request?.url} - ${error?.description}")
                        }
                    }

                    loadUrl("file:///android_asset/terminal/terminal.html")

                    terminalWebView = this
                    FileLogger.log("MainActivity", "Terminal WebView created")
                }
            },
            modifier = modifier
        )
    }

    private inner class TerminalBridge {
        @JavascriptInterface
        fun onTerminalInput(data: String) {
            // Capture JS log messages
            if (data.startsWith("\u0000LOG:")) {
                FileLogger.log("Terminal:JS", data.substring(5))
                return
            }
            tabManager.activeTabId.value?.let { id ->
                sessionOrchestrator.sendInput(id, data)
            }
        }

        @JavascriptInterface
        fun onTerminalReady(cols: Int, rows: Int) {
            val wv = terminalWebView
            val wvW = wv?.width ?: 0
            val wvH = wv?.height ?: 0
            FileLogger.log("MainActivity", "Terminal ready: ${cols}x${rows}, WebView: ${wvW}x${wvH}px")
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
    }

    private fun clearTerminal() {
        val wv = terminalWebView ?: return
        wv.post {
            wv.evaluateJavascript("if(typeof clearTerminal==='function')clearTerminal()", null)
        }
    }

    private fun writeToTerminal(data: String) {
        val wv = terminalWebView ?: return
        wv.post {
            // Use Base64 encoding to safely pass binary terminal data
            val b64 = android.util.Base64.encodeToString(
                data.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            wv.evaluateJavascript("writeOutputB64('$b64')", null)
        }
    }

    private fun installUpdate(apkBytes: ByteArray, info: UpdateInfo) {
        try {
            val updateDir = File(cacheDir, "updates")
            updateDir.mkdirs()
            val apkFile = File(updateDir, "update.apk")
            apkFile.writeBytes(apkBytes)

            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this,
                "Install failed: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
