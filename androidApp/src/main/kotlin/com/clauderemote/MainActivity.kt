package com.clauderemote

import android.content.Intent
import android.net.Uri
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
                    webViewClient = WebViewClient()
                    loadUrl("file:///android_asset/terminal/terminal.html")

                    terminalWebView = this
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

    fun writeToTerminal(data: String) {
        terminalWebView?.post {
            val escaped = data
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            terminalWebView?.evaluateJavascript("writeOutput('$escaped')", null)
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
            // Fallback: show error
            android.widget.Toast.makeText(
                this,
                "Install failed: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
