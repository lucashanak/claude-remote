package com.clauderemote

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.storage.AppSettings
import com.clauderemote.storage.PlatformPreferences
import com.clauderemote.storage.ServerStorage
import com.clauderemote.ui.App
import com.clauderemote.util.FileLogger
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebEngine
import javafx.scene.web.WebView
import netscape.javascript.JSObject
import java.awt.BorderLayout
import java.io.File
import javax.swing.JPanel

/** Extract terminal assets from jar to app cache directory for reliable WebView loading */
private fun extractTerminalAssets(): File {
    val dir = File(System.getProperty("user.home"), ".claude-remote/terminal")
    dir.mkdirs()
    val assets = listOf("terminal.html", "xterm.js", "xterm.css", "xterm-addon-fit.js", "xterm-addon-search.js", "xterm-addon-web-links.js")
    for (name in assets) {
        val stream = object {}.javaClass.getResourceAsStream("/terminal/$name")
        if (stream == null) {
            FileLogger.error("Desktop", "Asset not found in JAR: /terminal/$name", null)
            continue
        }
        val target = File(dir, name)
        try {
            stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            FileLogger.log("Desktop", "Extracted: $name (${target.length()} bytes)")
        } catch (e: Exception) {
            FileLogger.error("Desktop", "Failed to extract $name", e)
        }
    }
    return dir
}

/** Reference to the JavaFX WebEngine for terminal I/O */
private var webEngine: WebEngine? = null

fun main() = application {
    val prefs = PlatformPreferences()
    val serverStorage = ServerStorage(prefs)
    val appSettings = AppSettings(prefs)
    val tabManager = TabManager()
    val sessionOrchestrator = SessionOrchestrator(serverStorage, tabManager)

    // Wire SSH output → terminal WebView
    sessionOrchestrator.onTerminalOutput = { _, data ->
        writeToTerminal(data)
    }
    sessionOrchestrator.onTabSwitched = { _, bufferedOutput ->
        replayBuffer(bufferedOutput)
    }

    // Desktop notifications via SystemTray
    sessionOrchestrator.onClaudeNeedsInput = { _, hint, _ ->
        if (appSettings.notificationsEnabled) {
            try {
                if (java.awt.SystemTray.isSupported()) {
                    val tray = java.awt.SystemTray.getSystemTray()
                    if (tray.trayIcons.isEmpty()) {
                        val icon = java.awt.Toolkit.getDefaultToolkit().createImage(
                            object {}.javaClass.getResource("/icon.png")
                        )
                        val trayIcon = java.awt.TrayIcon(icon, "Claude Remote")
                        trayIcon.isImageAutoSize = true
                        tray.add(trayIcon)
                    }
                    tray.trayIcons.firstOrNull()?.displayMessage(
                        "Claude Remote", hint, java.awt.TrayIcon.MessageType.INFO
                    )
                }
            } catch (e: Exception) {
                FileLogger.log("Desktop", "System tray notification failed: ${e.message}")
            }
        }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Claude Remote",
        state = rememberWindowState(width = 1000.dp, height = 700.dp)
    ) {
        App(
            serverStorage = serverStorage,
            appSettings = appSettings,
            tabManager = tabManager,
            sessionOrchestrator = sessionOrchestrator,
            appVersion = "1.0.0",
            onTerminalScreenVisible = {
                val activeId = tabManager.activeTabId.value ?: return@App
                val buffer = sessionOrchestrator.getBuffer(activeId)
                if (buffer.isNotEmpty()) {
                    replayBuffer(buffer)
                }
            },
            exitApp = ::exitApplication,
            terminalContent = { modifier ->
                DesktopTerminalWebView(
                    modifier = modifier,
                    tabManager = tabManager,
                    sessionOrchestrator = sessionOrchestrator,
                    appSettings = appSettings
                )
            }
        )
    }
}

@Composable
private fun DesktopTerminalWebView(
    modifier: Modifier,
    tabManager: TabManager,
    sessionOrchestrator: SessionOrchestrator,
    appSettings: AppSettings
) {
    // Keep strong reference so GC doesn't collect the bridge (JSObject uses weak refs)
    val bridge = remember {
        DesktopTerminalBridge(tabManager, sessionOrchestrator, appSettings)
    }

    SwingPanel(
        modifier = modifier,
        factory = {
            JPanel(BorderLayout()).also { panel ->
                val jfxPanel = JFXPanel() // This initializes the JavaFX toolkit
                panel.add(jfxPanel, BorderLayout.CENTER)

                Platform.setImplicitExit(false)
                Platform.runLater {
                    val webView = WebView()
                    val engine = webView.engine
                    webEngine = engine

                    // Capture JavaScript console messages
                    engine.setOnAlert { event ->
                        FileLogger.log("WebView-JS", event.data)
                    }
                    engine.loadWorker.exceptionProperty().addListener { _, _, ex ->
                        if (ex != null) FileLogger.error("Desktop", "WebView exception: ${ex.message}", ex)
                    }

                    engine.loadWorker.stateProperty().addListener { _, _, newState ->
                        if (newState == Worker.State.FAILED) {
                            FileLogger.error("Desktop", "WebView load failed: ${engine.loadWorker.exception?.message}", engine.loadWorker.exception)
                        }
                        if (newState == Worker.State.SUCCEEDED) {
                            // Inject the bridge as window.Android (same name as Android app)
                            val window = engine.executeScript("window") as JSObject
                            window.setMember("Android", bridge)
                            // Re-assign the cached `A` variable and trigger init
                            engine.executeScript(
                                "A = window.Android; if(A) { setTimeout(function() { fitAddon.fit(); A.onTerminalReady(term.cols, term.rows); }, 50); }"
                            )
                            // Apply saved settings
                            val fontSize = appSettings.terminalFontSize
                            if (fontSize != 14) {
                                engine.executeScript("setFontSize($fontSize)")
                            }
                            val scheme = appSettings.terminalColorScheme
                            if (scheme != "default") {
                                engine.executeScript("applyColorScheme('$scheme')")
                            }
                        }
                    }

                    val terminalDir = extractTerminalAssets()
                    val htmlFile = File(terminalDir, "terminal.html")
                    val loadUrl = htmlFile.toURI().toString()
                    FileLogger.log("Desktop", "Loading WebView: $loadUrl (exists=${htmlFile.exists()}, size=${htmlFile.length()})")
                    engine.load(loadUrl)

                    jfxPanel.scene = Scene(webView)
                }
            }
        }
    )
}

/**
 * JavaScript bridge exposed to terminal.html as `window.Android`.
 * Method signatures must match what the HTML expects.
 */
class DesktopTerminalBridge(
    private val tabManager: TabManager,
    private val sessionOrchestrator: SessionOrchestrator,
    private val appSettings: AppSettings
) {
    fun onTerminalInput(data: String) {
        tabManager.activeTabId.value?.let { id ->
            sessionOrchestrator.sendInput(id, data)
        }
    }

    fun onTerminalReady(cols: Int, rows: Int) {
        tabManager.activeTabId.value?.let { id ->
            sessionOrchestrator.resize(id, cols, rows)
        }
    }

    fun onTerminalResize(cols: Int, rows: Int) {
        tabManager.activeTabId.value?.let { id ->
            sessionOrchestrator.resize(id, cols, rows)
        }
    }

    fun copyToClipboard(text: String) {
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
    }

    fun getClipboard(): String {
        return try {
            val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
            clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String ?: ""
        } catch (_: Exception) { "" }
    }

    fun openUrl(url: String) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        } catch (_: Exception) {}
    }

    fun exportScrollback(content: String) {
        try {
            val tmpFile = java.io.File.createTempFile("terminal_", ".log")
            tmpFile.writeText(content)
            java.awt.Desktop.getDesktop().open(tmpFile)
        } catch (_: Exception) {}
    }

    fun haptic() { /* no-op on desktop */ }

    fun setHandleDrag(active: Boolean) { /* no-op on desktop */ }
}

// ── Terminal I/O helpers ──

private fun jsonQuote(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append("\"")
    return sb.toString()
}

private fun writeToTerminal(data: String) {
    val engine = webEngine ?: return
    val safe = jsonQuote(data)
    Platform.runLater {
        try {
            engine.executeScript("writeOutput($safe)")
        } catch (_: Exception) {}
    }
}

private fun clearTerminal() {
    val engine = webEngine ?: return
    Platform.runLater {
        try {
            engine.executeScript("clearTerminal()")
        } catch (_: Exception) {}
    }
}

private fun replayBuffer(bufferedOutput: String) {
    val engine = webEngine ?: return
    // Delay to ensure WebView is visible (mirrors Android's 150ms delay)
    Platform.runLater {
        val timer = javax.swing.Timer(150) {
            Platform.runLater {
                try {
                    engine.executeScript("clearTerminal()")
                    if (bufferedOutput.isNotEmpty()) {
                        val safe = jsonQuote(bufferedOutput)
                        engine.executeScript("writeOutput($safe)")
                    }
                } catch (_: Exception) {}
            }
        }
        timer.isRepeats = false
        timer.start()
    }
}
