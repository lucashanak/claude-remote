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
 * Uses PipedStream at byte level with proper UTF-8 decoding via CharsetDecoder
 * which correctly handles incomplete multi-byte sequences at chunk boundaries.
 */
class SshTtyConnector(
    private val sessionOrchestrator: SessionOrchestrator,
    private val tabManager: TabManager
) : TtyConnector {
    private val byteOut = java.io.PipedOutputStream()
    private val byteIn = java.io.PipedInputStream(byteOut, 512 * 1024)
    // CharsetDecoder handles incomplete UTF-8 sequences across chunks
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
    private val readBuf = ByteArray(8192)
    private val decodeBuf = java.nio.CharBuffer.allocate(8192)
    @Volatile private var connected = true

    /** Called by SessionOrchestrator.onTerminalOutput — receives raw SSH string chunks */
    fun feedOutput(data: String) {
        try {
            val bytes = data.toByteArray(StandardCharsets.UTF_8)
            synchronized(byteOut) {
                byteOut.write(bytes)
                byteOut.flush()
            }
        } catch (_: Exception) {}
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        // Read raw bytes then decode to chars with stateful decoder
        val n = byteIn.read(readBuf, 0, minOf(readBuf.size, length))
        if (n <= 0) return n
        val byteBuf = java.nio.ByteBuffer.wrap(readBuf, 0, n)
        decodeBuf.clear()
        decoder.decode(byteBuf, decodeBuf, false)
        decodeBuf.flip()
        val count = minOf(decodeBuf.remaining(), length)
        decodeBuf.get(buf, offset, count)
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

    override fun ready(): Boolean {
        return try { byteIn.available() > 0 } catch (_: Exception) { false }
    }

    override fun getName(): String = "SSH"

    override fun close() {
        connected = false
        try { byteOut.close() } catch (_: Exception) {}
        try { byteIn.close() } catch (_: Exception) {}
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
