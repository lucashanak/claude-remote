package com.clauderemote

import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        super.onCreate(savedInstanceState)

        val prefs = PlatformPreferences(this)
        serverStorage = ServerStorage(prefs)
        appSettings = AppSettings(prefs)
        tabManager = TabManager()
        sessionOrchestrator = SessionOrchestrator(serverStorage, tabManager)

        // Wire SSH output → terminal WebView
        sessionOrchestrator.onTerminalOutput = { sessionId, data ->
            // Only write to terminal if this session's tab is active
            val activeId = tabManager.activeTabId.value
            if (activeId == null || activeId == sessionId) {
                writeToTerminal(data)
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
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    setBackgroundColor(0xFF1E1E1E.toInt())

                    addJavascriptInterface(TerminalBridge(), "TerminalBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            FileLogger.log("MainActivity", "Terminal WebView loaded: $url")
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
            tabManager.activeTabId.value?.let { id ->
                sessionOrchestrator.sendInput(id, data)
            }
        }

        @JavascriptInterface
        fun onTerminalReady(cols: Int, rows: Int) {
            FileLogger.log("MainActivity", "Terminal ready: ${cols}x${rows}")
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
