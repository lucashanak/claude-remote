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
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import java.awt.BorderLayout
import java.io.File
import javax.swing.JPanel

/** Extract terminal assets from jar to app cache directory */
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
        } catch (e: Exception) {
            FileLogger.error("Desktop", "Failed to extract $name", e)
        }
    }
    return dir
}

// Global CEF state
private var cefApp: CefApp? = null
private var cefBrowser: CefBrowser? = null

private fun initCef(): CefApp {
    cefApp?.let { return it }
    val installDir = File(System.getProperty("user.home"), ".claude-remote/jcef")
    installDir.mkdirs()
    val builder = CefAppBuilder()
    builder.setInstallDir(installDir)
    builder.setAppHandler(object : MavenCefAppHandlerAdapter() {})
    builder.cefSettings.windowless_rendering_enabled = false
    builder.cefSettings.log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_ERROR
    builder.addJcefArgs("--allow-file-access-from-files")
    builder.addJcefArgs("--disable-web-security")
    val app = builder.build()
    cefApp = app
    return app
}

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
        onCloseRequest = {
            cefBrowser?.close(true)
            cefApp?.dispose()
            exitApplication()
        },
        title = "Claude Remote",
        state = rememberWindowState(width = 1000.dp, height = 700.dp)
    ) {
        App(
            serverStorage = serverStorage,
            appSettings = appSettings,
            tabManager = tabManager,
            sessionOrchestrator = sessionOrchestrator,
            appVersion = System.getProperty("jpackage.app-version") ?: "dev",
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
    SwingPanel(
        modifier = modifier,
        factory = {
            JPanel(BorderLayout()).also { panel ->
                panel.background = java.awt.Color(0x1E, 0x1E, 0x1E)

                try {
                    val app = initCef()
                    val client = app.createClient()

                    // Message router for JS → Kotlin calls
                    val routerConfig = CefMessageRouter.CefMessageRouterConfig()
                    routerConfig.jsQueryFunction = "cefQuery"
                    routerConfig.jsCancelFunction = "cefQueryCancel"
                    val msgRouter = CefMessageRouter.create(routerConfig)

                    msgRouter.addHandler(object : CefMessageRouterHandlerAdapter() {
                        override fun onQuery(
                            browser: CefBrowser?, frame: org.cef.browser.CefFrame?, queryId: Long,
                            request: String?, persistent: Boolean, callback: CefQueryCallback?
                        ): Boolean {
                            if (request == null) return false
                            // Protocol: "method:data"
                            val colonIdx = request.indexOf(':')
                            if (colonIdx < 0) return false
                            val method = request.substring(0, colonIdx)
                            val data = request.substring(colonIdx + 1)

                            when (method) {
                                "onTerminalInput" -> {
                                    tabManager.activeTabId.value?.let { id ->
                                        sessionOrchestrator.sendInput(id, data)
                                    }
                                }
                                "onTerminalReady" -> {
                                    val parts = data.split(",")
                                    if (parts.size == 2) {
                                        val cols = parts[0].toIntOrNull() ?: return false
                                        val rows = parts[1].toIntOrNull() ?: return false
                                        tabManager.activeTabId.value?.let { id ->
                                            sessionOrchestrator.resize(id, cols, rows)
                                        }
                                    }
                                }
                                "onTerminalResize" -> {
                                    val parts = data.split(",")
                                    if (parts.size == 2) {
                                        val cols = parts[0].toIntOrNull() ?: return false
                                        val rows = parts[1].toIntOrNull() ?: return false
                                        tabManager.activeTabId.value?.let { id ->
                                            sessionOrchestrator.resize(id, cols, rows)
                                        }
                                    }
                                }
                                "copyToClipboard" -> {
                                    val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                    clipboard.setContents(java.awt.datatransfer.StringSelection(data), null)
                                }
                                "getClipboard" -> {
                                    val text = try {
                                        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                        clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String ?: ""
                                    } catch (_: Exception) { "" }
                                    callback?.success(text)
                                    return true
                                }
                                "openUrl" -> {
                                    try { java.awt.Desktop.getDesktop().browse(java.net.URI(data)) } catch (_: Exception) {}
                                }
                                "exportScrollback" -> {
                                    try {
                                        val tmpFile = File.createTempFile("terminal_", ".log")
                                        tmpFile.writeText(data)
                                        java.awt.Desktop.getDesktop().open(tmpFile)
                                    } catch (_: Exception) {}
                                }
                            }
                            callback?.success("")
                            return true
                        }
                    }, true)

                    client.addMessageRouter(msgRouter)

                    // Log JS console errors
                    client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                        override fun onConsoleMessage(
                            browser: CefBrowser?, level: org.cef.CefSettings.LogSeverity?,
                            message: String?, source: String?, line: Int
                        ): Boolean {
                            if (level == org.cef.CefSettings.LogSeverity.LOGSEVERITY_ERROR) {
                                FileLogger.error("CEF-JS", "$message ($source:$line)", null)
                            }
                            return false
                        }
                    })

                    // On load complete: inject bridge and init
                    client.addLoadHandler(object : CefLoadHandlerAdapter() {
                        override fun onLoadEnd(browser: CefBrowser?, frame: org.cef.browser.CefFrame?, httpStatusCode: Int) {
                            if (frame?.isMain != true || browser == null) return
                            val url = browser.url ?: ""
                            // Inject bridge shim that mimics window.Android interface
                            browser.executeJavaScript("""
                                window.Android = {
                                    onTerminalInput: function(data) { window.cefQuery({request: 'onTerminalInput:' + data}); },
                                    onTerminalReady: function(cols, rows) { window.cefQuery({request: 'onTerminalReady:' + cols + ',' + rows}); },
                                    onTerminalResize: function(cols, rows) { window.cefQuery({request: 'onTerminalResize:' + cols + ',' + rows}); },
                                    copyToClipboard: function(text) { window.cefQuery({request: 'copyToClipboard:' + text}); },
                                    getClipboard: function() { return ''; },
                                    openUrl: function(u) { window.cefQuery({request: 'openUrl:' + u}); },
                                    exportScrollback: function(c) { window.cefQuery({request: 'exportScrollback:' + c}); },
                                    haptic: function() {},
                                    setHandleDrag: function(a) {}
                                };
                                A = window.Android;
                                if (typeof fitAddon !== 'undefined') {
                                    setTimeout(function() { fitAddon.fit(); A.onTerminalReady(term.cols, term.rows); }, 100);
                                }
                            """.trimIndent(), url, 0)

                            // Apply saved settings
                            val fontSize = appSettings.terminalFontSize
                            if (fontSize != 14) {
                                browser.executeJavaScript("setFontSize($fontSize)", url, 0)
                            }
                            val scheme = appSettings.terminalColorScheme
                            if (scheme != "default") {
                                browser.executeJavaScript("applyColorScheme('$scheme')", url, 0)
                            }
                        }
                    })

                    val terminalDir = extractTerminalAssets()
                    val htmlFile = File(terminalDir, "terminal.html")
                    val url = htmlFile.toURI().toString()

                    val browser = client.createBrowser(url, false, false)
                    cefBrowser = browser
                    panel.add(browser.uiComponent, BorderLayout.CENTER)

                } catch (e: Exception) {
                    FileLogger.error("Desktop", "CEF init failed: ${e.message}", e)
                    val label = javax.swing.JLabel(
                        "<html><center style='color:white'>Terminal failed to initialize.<br>${e.message}</center></html>"
                    )
                    label.foreground = java.awt.Color.WHITE
                    label.horizontalAlignment = javax.swing.SwingConstants.CENTER
                    panel.add(label, BorderLayout.CENTER)
                }
            }
        }
    )
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
    val browser = cefBrowser ?: return
    val safe = jsonQuote(data)
    browser.executeJavaScript("if(typeof writeOutput==='function')writeOutput($safe)", browser.url, 0)
}

private fun clearTerminal() {
    val browser = cefBrowser ?: return
    browser.executeJavaScript("if(typeof clearTerminal==='function')clearTerminal()", browser.url, 0)
}

private fun replayBuffer(bufferedOutput: String) {
    val browser = cefBrowser ?: return
    javax.swing.Timer(150) {
        clearTerminal()
        if (bufferedOutput.isNotEmpty()) {
            writeToTerminal(bufferedOutput)
        }
    }.apply { isRepeats = false; start() }
}
