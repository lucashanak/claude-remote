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
    // Thread-safe queue: SSH output chunks → JediTerm reader (no PipedStream thread-death issues)
    private val queue = java.util.concurrent.LinkedBlockingQueue<CharArray>()
    private var pending: CharArray? = null
    private var pendingOffset = 0
    @Volatile private var connected = true
    // Last known terminal size — reapplied after SSH reconnect (fixes wrong size from previous device)
    @Volatile private var lastTermSize: com.jediterm.core.util.TermSize? = null

    /** Called by SessionOrchestrator.onTerminalOutput — receives already-decoded SSH string chunks */
    fun feedOutput(data: String) {
        if (data.isNotEmpty()) queue.offer(data.toCharArray())
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        var src = pending
        var srcOff = pendingOffset
        if (src == null || srcOff >= src.size) {
            // Poll with timeout so close() can interrupt via connected flag
            src = null
            while (connected) {
                src = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (src != null) break
            }
            if (src == null) return -1 // disconnected with no pending data
            srcOff = 0
        }
        if (src!!.isEmpty()) return -1 // poison pill from close()
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

    override fun ready(): Boolean = pending != null || queue.isNotEmpty()

    override fun getName(): String = "SSH"

    override fun close() {
        connected = false
        queue.offer(CharArray(0)) // unblock any queue.poll() waiting in read()
    }

    override fun resize(termSize: com.jediterm.core.util.TermSize) {
        lastTermSize = termSize
        tabManager.activeTabId.value?.let { id ->
            sessionOrchestrator.resize(id, termSize.columns, termSize.rows)
        }
    }

    /** Re-sends the last known terminal size to the active session.
     *  Call this after connecting to an existing tmux session to fix size mismatch
     *  (e.g. session was previously used from Android with different terminal size). */
    fun reapplySize() {
        lastTermSize?.let { size ->
            tabManager.activeTabId.value?.let { id ->
                sessionOrchestrator.resize(id, size.columns, size.rows)
            }
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
    val sshKeyManager = com.clauderemote.connection.SshKeyManager(prefs)

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
            sshKeyManager = sshKeyManager,
            appVersion = System.getProperty("jpackage.app-version") ?: "dev",
            onInstallUpdate = { bytes, info ->
                try {
                    val tmpDir = File(System.getProperty("java.io.tmpdir"), "claude-remote-update")
                    tmpDir.mkdirs()
                    if (info.dmgUrl.isNotBlank()) {
                        val dmgFile = File(tmpDir, "ClaudeRemote-${info.version}.dmg")
                        dmgFile.writeBytes(bytes)

                        // Mount DMG silently
                        val attachProc = ProcessBuilder("hdiutil", "attach", "-nobrowse", dmgFile.absolutePath)
                            .redirectErrorStream(true)
                            .start()
                        val attachOutput = attachProc.inputStream.bufferedReader().readText()
                        attachProc.waitFor()

                        val mountPoint = attachOutput.lines()
                            .lastOrNull { it.contains("/Volumes/") }
                            ?.let { line -> line.substring(line.indexOf("/Volumes/")).trim() }
                            ?: throw Exception("Could not determine DMG mount point.\nhdiutil output:\n$attachOutput")

                        try {
                            val appBundle = File(mountPoint).listFiles()
                                ?.firstOrNull { it.name.endsWith(".app") }
                                ?: throw Exception("No .app found in DMG at $mountPoint")
                            val appDest = File("/Applications/${appBundle.name}")

                            // Copy to /Applications (overwrites existing)
                            ProcessBuilder("ditto", appBundle.absolutePath, appDest.absolutePath)
                                .start().waitFor()

                            // Remove quarantine
                            ProcessBuilder("xattr", "-cr", appDest.absolutePath)
                                .start().waitFor()

                            // Launch relaunch script that waits for this process to exit, then opens the new app
                            val pid = ProcessHandle.current().pid()
                            val script = File(tmpDir, "relaunch.sh")
                            script.writeText("#!/bin/bash\nwhile kill -0 $pid 2>/dev/null; do sleep 0.5; done\nopen '${appDest.absolutePath}'\n")
                            script.setExecutable(true)
                            ProcessBuilder("/bin/bash", script.absolutePath).start()

                            FileLogger.log("Desktop", "macOS update installed: ${appDest.absolutePath}")
                        } finally {
                            ProcessBuilder("hdiutil", "detach", mountPoint, "-quiet").start()
                        }

                        kotlin.system.exitProcess(0)
                    } else {
                        val apkFile = File(tmpDir, "ClaudeRemote-${info.version}.apk")
                        apkFile.writeBytes(bytes)
                        java.awt.Desktop.getDesktop().open(apkFile)
                        FileLogger.log("Desktop", "Update saved and opened: ${apkFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    FileLogger.error("Desktop", "Failed to install update: ${e.message}", e)
                    throw e
                }
            },
            onTerminalScreenVisible = {
                val activeId = tabManager.activeTabId.value ?: return@App
                val buffer = sessionOrchestrator.getBuffer(activeId)
                val widget = termWidget
                javax.swing.SwingUtilities.invokeLater {
                    // Reapply terminal size — fixes green rectangle when reconnecting
                    // from a different device (Android) that had a smaller terminal
                    connector.reapplySize()
                    if (widget != null && buffer.isNotEmpty()) {
                        widget.terminalPanel.clearBuffer()
                        connector.feedOutput(buffer)
                    }
                }
            },
            onPickFile = { callback ->
                javax.swing.SwingUtilities.invokeLater {
                    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Attach File", java.awt.FileDialog.LOAD)
                    dialog.isMultipleMode = true
                    dialog.isVisible = true
                    val files = dialog.files
                    if (files != null && files.isNotEmpty()) {
                        val pairs = files.mapNotNull { f ->
                            try { f.readBytes() to f.name } catch (_: Exception) { null }
                        }
                        callback(pairs)
                    } else {
                        callback(emptyList())
                    }
                }
            },
            onShowNativeMenu = {
                javax.swing.SwingUtilities.invokeLater {
                    val popup = javax.swing.JPopupMenu()
                    popup.add(javax.swing.JMenuItem("Reset terminal").apply {
                        addActionListener {
                            tabManager.activeTabId.value?.let { sessionOrchestrator.sendClaudeCommand(it, "\u001Bc") }
                        }
                    })
                    popup.addSeparator()
                    // Font size
                    val fontMenu = javax.swing.JMenu("Font size: ${appSettings.terminalFontSize}")
                    fontMenu.add(javax.swing.JMenuItem("Increase").apply {
                        addActionListener {
                            appSettings.terminalFontSize = (appSettings.terminalFontSize + 1).coerceAtMost(32)
                        }
                    })
                    fontMenu.add(javax.swing.JMenuItem("Decrease").apply {
                        addActionListener {
                            appSettings.terminalFontSize = (appSettings.terminalFontSize - 1).coerceAtLeast(8)
                        }
                    })
                    popup.add(fontMenu)
                    popup.addSeparator()
                    // Rename
                    popup.add(javax.swing.JMenuItem("Rename session").apply {
                        addActionListener {
                            val activeId = tabManager.activeTabId.value ?: return@addActionListener
                            val tab = tabManager.getTab(activeId) ?: return@addActionListener
                            val parent = javax.swing.SwingUtilities.getWindowAncestor(termWidget)
                            val newAlias = javax.swing.JOptionPane.showInputDialog(parent, "Session alias:", tab.alias) ?: return@addActionListener
                            val trimmed = newAlias.trim()
                            tabManager.updateAlias(activeId, trimmed)
                            // Rename tmux session on server
                            val newTmuxName = com.clauderemote.model.TmuxNameParser.build(
                                tab.server.name, tab.folder,
                                tab.mode == com.clauderemote.model.ClaudeMode.YOLO, trimmed
                            )
                            Thread {
                                kotlinx.coroutines.runBlocking {
                                    sessionOrchestrator.renameTmuxSession(activeId, tab.tmuxSessionName, newTmuxName)
                                }
                            }.start()
                        }
                    })
                    popup.add(javax.swing.JMenuItem("Close session").apply {
                        addActionListener {
                            tabManager.activeTabId.value?.let { id ->
                                Thread {
                                    kotlinx.coroutines.runBlocking {
                                        try { sessionOrchestrator.disconnectSession(id) } catch (_: Exception) {}
                                    }
                                }.start()
                            }
                        }
                    })
                    // Show popup at mouse position
                    val mousePos = java.awt.MouseInfo.getPointerInfo().location
                    val frame = javax.swing.SwingUtilities.getWindowAncestor(termWidget) ?: return@invokeLater
                    val framePos = frame.locationOnScreen
                    popup.show(frame, mousePos.x - framePos.x, mousePos.y - framePos.y)
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

                // Reuse existing widget if already created
                val existing = termWidget
                if (existing != null) {
                    existing.parent?.remove(existing)
                    panel.add(existing, BorderLayout.CENTER)
                    // Delayed resize — panel size not available yet
                    panel.addComponentListener(object : java.awt.event.ComponentAdapter() {
                        override fun componentResized(e: java.awt.event.ComponentEvent?) {
                            existing.size = panel.size
                            existing.revalidate()
                        }
                    })
                    for (delay in listOf(100, 300, 800)) {
                        javax.swing.Timer(delay) {
                            if (panel.width > 0) { existing.size = panel.size; existing.revalidate() }
                        }.also { it.isRepeats = false }.start()
                    }
                    FileLogger.log("Desktop", "JediTerm widget reused")
                    return@also
                }

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

                    // Force layout after panel is shown (macOS needs explicit sizing)
                    fun forceSize() {
                        if (panel.size.width > 0 && panel.size.height > 0) {
                            widget.size = panel.size
                            widget.revalidate()
                            widget.repaint()
                            connector.reapplySize() // also push PTY size to SSH
                        }
                    }
                    panel.addComponentListener(object : java.awt.event.ComponentAdapter() {
                        override fun componentResized(e: java.awt.event.ComponentEvent?) = forceSize()
                        override fun componentShown(e: java.awt.event.ComponentEvent?) = forceSize()
                    })
                    // Immediate attempt (panel may still be 0-sized here)
                    javax.swing.SwingUtilities.invokeLater { forceSize() }
                    // Multiple delayed attempts — macOS layout settles late
                    for (delay in listOf(200, 500, 1000, 2000)) {
                        javax.swing.Timer(delay) { forceSize() }.also { it.isRepeats = false }.start()
                    }

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
        },
        update = { panel ->
            // Called on recomposition — panel now has correct size from Compose layout
            val widget = termWidget ?: return@SwingPanel
            if (panel.width > 0 && panel.height > 0) {
                widget.size = panel.size
                widget.revalidate()
            }
        }
    )
}
