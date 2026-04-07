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
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import java.nio.charset.StandardCharsets
import javax.swing.JPanel

/**
 * TtyConnector that bridges JediTerm to our SessionOrchestrator.
 * Reads SSH output from a pipe, writes user input via orchestrator.
 */
class SshTtyConnector(
    private val sessionOrchestrator: SessionOrchestrator,
    private val tabManager: TabManager
) : TtyConnector {
    // Thread-safe queue for SSH output chunks → JediTerm reader
    private val queue = java.util.concurrent.LinkedBlockingQueue<CharArray>()
    private var pending: CharArray? = null
    private var pendingOffset = 0
    @Volatile private var connected = true

    /** Called by SessionOrchestrator.onTerminalOutput to feed data to JediTerm */
    fun feedOutput(data: String) {
        if (data.isNotEmpty()) {
            queue.offer(data.toCharArray())
        }
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        // Serve from pending leftover first
        var src = pending
        var srcOff = pendingOffset
        if (src == null || srcOff >= src.size) {
            // Block until data available
            src = queue.take()
            srcOff = 0
        }
        val available = src.size - srcOff
        val count = minOf(available, length)
        System.arraycopy(src, srcOff, buf, offset, count)
        if (srcOff + count < src.size) {
            pending = src
            pendingOffset = srcOff + count
        } else {
            pending = null
            pendingOffset = 0
        }
        return count
    }

    override fun write(bytes: ByteArray) {
        tabManager.activeTabId.value?.let { id ->
            sessionOrchestrator.sendInput(id, String(bytes, StandardCharsets.UTF_8))
        }
    }

    override fun write(string: String) {
        tabManager.activeTabId.value?.let { id ->
            sessionOrchestrator.sendInput(id, string)
        }
    }

    override fun isConnected(): Boolean = connected

    override fun waitFor(): Int {
        while (connected) { Thread.sleep(500) }
        return 0
    }

    override fun ready(): Boolean = queue.isNotEmpty() || (pending != null && pendingOffset < (pending?.size ?: 0))

    override fun getName(): String = "SSH"

    override fun close() {
        connected = false
        queue.offer(charArrayOf()) // unblock reader
    }

    override fun resize(termSize: com.jediterm.core.util.TermSize) {
        tabManager.activeTabId.value?.let { id ->
            sessionOrchestrator.resize(id, termSize.columns, termSize.rows)
        }
    }
}

// Global terminal state
private var termWidget: JediTermWidget? = null
private var sshConnector: SshTtyConnector? = null

fun main() = application {
    // Init logging
    val logDir = File(System.getProperty("user.home"), ".claude-remote")
    logDir.mkdirs()
    FileLogger.init(logDir, System.getProperty("jpackage.app-version") ?: "dev")

    val prefs = PlatformPreferences()
    val serverStorage = ServerStorage(prefs)
    val appSettings = AppSettings(prefs)
    val tabManager = TabManager()
    val sessionOrchestrator = SessionOrchestrator(serverStorage, tabManager)

    // Create connector and wire SSH output → JediTerm
    val connector = SshTtyConnector(sessionOrchestrator, tabManager)
    sshConnector = connector

    sessionOrchestrator.onTerminalOutput = { _, data ->
        connector.feedOutput(data)
    }
    sessionOrchestrator.onTabSwitched = tabSwitched@{ _, bufferedOutput ->
        val widget = termWidget ?: return@tabSwitched
        javax.swing.SwingUtilities.invokeLater {
            widget.terminalPanel.clearBuffer()
            if (bufferedOutput.isNotEmpty()) connector.feedOutput(bufferedOutput)
        }
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
            sshConnector?.close()
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
            onInstallUpdate = { bytes, info ->
                try {
                    val tmpDir = File(System.getProperty("java.io.tmpdir"), "claude-remote-update")
                    tmpDir.mkdirs()
                    val ext = if (info.dmgUrl.isNotBlank()) ".dmg" else ".apk"
                    val updateFile = File(tmpDir, "ClaudeRemote-${info.version}$ext")
                    updateFile.writeBytes(bytes)
                    java.awt.Desktop.getDesktop().open(updateFile)
                    FileLogger.log("Desktop", "Update saved and opened: ${updateFile.absolutePath}")
                } catch (e: Exception) {
                    FileLogger.error("Desktop", "Failed to install update: ${e.message}", e)
                }
            },
            onTerminalScreenVisible = {
                val activeId = tabManager.activeTabId.value ?: return@App
                val buffer = sessionOrchestrator.getBuffer(activeId)
                if (buffer.isNotEmpty()) {
                    val widget = termWidget ?: return@App
                    javax.swing.SwingUtilities.invokeLater {
                        widget.terminalPanel.clearBuffer()
                        connector.feedOutput(buffer)
                    }
                }
            },
            onPickFile = { callback ->
                javax.swing.SwingUtilities.invokeLater {
                    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Attach File", java.awt.FileDialog.LOAD)
                    dialog.isVisible = true
                    val dir = dialog.directory
                    val name = dialog.file
                    if (dir != null && name != null) {
                        val file = File(dir, name)
                        callback(file.readBytes(), file.name)
                    } else {
                        callback(ByteArray(0), "")
                    }
                }
            },
            exitApp = ::exitApplication,
            terminalContent = { modifier ->
                DesktopTerminalView(
                    modifier = modifier,
                    connector = connector,
                    appSettings = appSettings
                )
            }
        )
    }
}

@Composable
private fun DesktopTerminalView(
    modifier: Modifier,
    connector: SshTtyConnector,
    appSettings: AppSettings
) {
    SwingPanel(
        modifier = modifier,
        factory = {
            JPanel(BorderLayout()).also { panel ->
                panel.background = java.awt.Color(0x1E, 0x1E, 0x1E)

                try {
                    val darkFg = com.jediterm.terminal.TerminalColor(com.jediterm.core.Color(0xCC, 0xCC, 0xCC))
                    val darkBg = com.jediterm.terminal.TerminalColor(com.jediterm.core.Color(0x1E, 0x1E, 0x1E))
                    val settings = object : DefaultSettingsProvider() {
                        override fun getTerminalFontSize(): Float =
                            appSettings.terminalFontSize.toFloat()

                        override fun getBufferMaxLinesCount(): Int =
                            appSettings.terminalScrollback

                        override fun useAntialiasing(): Boolean = true

                        override fun getDefaultForeground(): com.jediterm.terminal.TerminalColor = darkFg
                        override fun getDefaultBackground(): com.jediterm.terminal.TerminalColor = darkBg
                    }

                    val widget = JediTermWidget(settings)
                    widget.setTtyConnector(connector)
                    widget.start()
                    termWidget = widget

                    panel.add(widget, BorderLayout.CENTER)
                    FileLogger.log("Desktop", "JediTerm widget created")
                } catch (e: Exception) {
                    FileLogger.error("Desktop", "JediTerm init failed: ${e.message}", e)
                    val label = javax.swing.JLabel(
                        "<html><center>Terminal failed: ${e.message}</center></html>"
                    )
                    label.foreground = java.awt.Color.WHITE
                    label.horizontalAlignment = javax.swing.SwingConstants.CENTER
                    panel.add(label, BorderLayout.CENTER)
                }
            }
        }
    )
}
