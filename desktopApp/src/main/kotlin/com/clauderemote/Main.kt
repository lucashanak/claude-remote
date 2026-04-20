package com.clauderemote

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.clauderemote.session.RowSnapshot
import com.clauderemote.session.ScreenStateSnapshot
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.storage.AppSettings
import com.clauderemote.storage.PlatformPreferences
import com.clauderemote.storage.ServerStorage
import com.clauderemote.ui.App
import com.clauderemote.util.FileLogger
import com.jediterm.terminal.TerminalColor
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
    @Volatile var lastTermSize: com.jediterm.core.util.TermSize? = null
        private set

    // Holds an OSC 52 sequence that was split across chunk boundaries.
    private val oscCarry = StringBuilder()

    /** Called by SessionOrchestrator.onTerminalOutput — receives already-decoded SSH string chunks */
    fun feedOutput(data: String) {
        if (data.isEmpty()) return
        val filtered = interceptOsc52(data)
        if (filtered.isNotEmpty()) queue.offer(filtered.toCharArray())
    }

    /**
     * JediTerm 3.64 ignores OSC 52 (clipboard) escapes, so we parse them here
     * and write to the system clipboard ourselves. Called with the entire
     * chunk as received — the chunk is stripped of OSC 52 before being fed
     * to JediTerm. Partial sequences at a chunk boundary are buffered until
     * the next chunk arrives.
     *
     * Sequence: ESC ] 52 ; <selection-type> ; <base64-text> (BEL | ESC \)
     */
    private fun interceptOsc52(data: String): String {
        val input = if (oscCarry.isEmpty()) data else {
            val combined = oscCarry.toString() + data
            oscCarry.clear()
            combined
        }
        val out = StringBuilder(input.length)
        var i = 0
        while (true) {
            val start = input.indexOf("\u001B]52;", i)
            if (start < 0) {
                out.append(input, i, input.length)
                return out.toString()
            }
            out.append(input, i, start)
            // Locate terminator: BEL (\u0007) or ST (ESC \ = \u001B\u005C).
            var end = -1
            var termLen = 0
            var j = start + 5
            while (j < input.length) {
                val c = input[j]
                if (c == '\u0007') { end = j; termLen = 1; break }
                if (c == '\u001B' && j + 1 < input.length && input[j + 1] == '\\') {
                    end = j; termLen = 2; break
                }
                j++
            }
            if (end < 0) {
                // Terminator not yet in buffer — park the partial sequence for the next chunk.
                oscCarry.append(input, start, input.length)
                return out.toString()
            }
            val payload = input.substring(start + 5, end)
            val sep = payload.indexOf(';')
            if (sep >= 0) {
                val b64 = payload.substring(sep + 1)
                try {
                    val decoded = java.util.Base64.getDecoder().decode(b64)
                    val text = String(decoded, StandardCharsets.UTF_8)
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        java.awt.datatransfer.StringSelection(text), null
                    )
                    FileLogger.log("Desktop", "OSC 52 clipboard write: ${text.length} chars")
                } catch (t: Throwable) {
                    FileLogger.error("Desktop", "OSC 52 decode failed", t)
                }
            }
            i = end + termLen
        }
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
    sessionOrchestrator.onTabSwitched = tabSwitched@{ sessionId, bufferedOutput ->
        val widget = termWidget ?: return@tabSwitched
        javax.swing.SwingUtilities.invokeLater {
            widget.terminalPanel.clearBuffer()
            if (bufferedOutput.isNotEmpty()) connector.feedOutput(bufferedOutput)
            widget.terminalPanel.requestFocusInWindow()
            // Force a full tmux redraw after the switch, matching Android behavior.
            // Toggle PTY size to fire SIGWINCH twice — naive back-to-back resize can
            // be coalesced by the kernel, so use a delay between the two resizes.
            val termSize = connector.lastTermSize ?: return@invokeLater
            val cols = termSize.columns
            val rows = termSize.rows
            if (cols <= 0 || rows <= 1) return@invokeLater
            sessionOrchestrator.resize(sessionId, cols, rows - 1)
            javax.swing.Timer(80) {
                sessionOrchestrator.resize(sessionId, cols, rows)
                widget.terminalPanel.requestFocusInWindow()
            }.also { it.isRepeats = false }.start()
        }
    }

    // Screen-state reader for the color-aware prompt detector. JediTerm's
    // TerminalTextBuffer is internally locked, so we can call it from any
    // coroutine dispatcher without Swing-EDT marshaling. Only the active tab's
    // widget state is inspected — background sessions return null (regression
    // vs. the old regex detector: no background-tab notifications in this
    // iteration). Shadow emulators per session would lift this — left for later.
    sessionOrchestrator.screenReader = { sessionId ->
        if (tabManager.activeTabId.value != sessionId) null
        else readJediTermSnapshot(termWidget, rowCount = 8)
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

    val appIcon = remember {
        try {
            val stream = object {}.javaClass.getResourceAsStream("/icon.png")
            if (stream != null) {
                val image = javax.imageio.ImageIO.read(stream)
                stream.close()
                androidx.compose.ui.res.loadImageBitmap(object {}.javaClass.getResourceAsStream("/icon.png")!!)
            } else null
        } catch (_: Exception) { null }
    }

    // Set Dock icon on macOS
    LaunchedEffect(Unit) {
        try {
            val stream = object {}.javaClass.getResourceAsStream("/icon.png")
            if (stream != null) {
                val image = javax.imageio.ImageIO.read(stream)
                stream.close()
                if (java.awt.Taskbar.isTaskbarSupported()) {
                    java.awt.Taskbar.getTaskbar().iconImage = image
                }
            }
        } catch (_: Exception) {}
    }

    Window(
        onCloseRequest = {
            sshConnector?.close()
            exitApplication()
        },
        title = "Claude Remote",
        icon = appIcon?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it) },
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
                    val parent = javax.swing.SwingUtilities.getWindowAncestor(termWidget) as? java.awt.Frame
                    val dialog = java.awt.FileDialog(parent, "Attach File", java.awt.FileDialog.LOAD)
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
                    // Force repaint after native dialog — macOS can leave the
                    // SwingPanel in a stale state after FileDialog steals focus
                    termWidget?.let { w ->
                        w.revalidate()
                        w.repaint()
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
            onNativeRenameDialog = { sessionId, currentAlias ->
                javax.swing.SwingUtilities.invokeLater {
                    val parent = javax.swing.SwingUtilities.getWindowAncestor(termWidget)
                    val newAlias = javax.swing.JOptionPane.showInputDialog(parent, "Session alias:", currentAlias) ?: return@invokeLater
                    val trimmed = newAlias.trim()
                    tabManager.updateAlias(sessionId, trimmed)
                    val tab = tabManager.getTab(sessionId) ?: return@invokeLater
                    val newTmuxName = com.clauderemote.model.TmuxNameParser.build(
                        tab.server.name, tab.folder,
                        tab.mode == com.clauderemote.model.ClaudeMode.YOLO, trimmed
                    )
                    Thread {
                        kotlinx.coroutines.runBlocking {
                            sessionOrchestrator.renameTmuxSession(sessionId, tab.tmuxSessionName, newTmuxName)
                        }
                    }.start()
                }
            },
            onNativeCloseConfirm = { sessionId ->
                javax.swing.SwingUtilities.invokeLater {
                    val session = tabManager.getTab(sessionId)
                    val parent = javax.swing.SwingUtilities.getWindowAncestor(termWidget)
                    val result = javax.swing.JOptionPane.showConfirmDialog(
                        parent,
                        "Disconnect from ${session?.server?.name ?: "server"}?",
                        "Close Session",
                        javax.swing.JOptionPane.OK_CANCEL_OPTION
                    )
                    if (result == javax.swing.JOptionPane.OK_OPTION) {
                        Thread {
                            kotlinx.coroutines.runBlocking {
                                try { sessionOrchestrator.disconnectSession(sessionId) } catch (_: Exception) {}
                            }
                        }.start()
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

                        // Compose SwingPanel on macOS doesn't forward Cmd+C reliably to
                        // the embedded Swing component, so auto-copy during drag gives the
                        // user the clipboard contents without needing the keystroke.
                        override fun copyOnSelect(): Boolean = true
                    }

                    val widget = JediTermWidget(settings)
                    widget.setTtyConnector(connector)
                    widget.start()
                    termWidget = widget

                    installSelectionGuard(widget.terminalPanel)
                    installDragScroller(widget.terminalPanel)

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

/**
 * Terminal text selection on macOS Compose SwingPanel is broken because
 * mySelection is nulled between the last MOUSE_DRAGGED and MOUSE_RELEASED
 * (cause unclear — phantom press, scrollArea from incoming output, a Compose
 * synthetic, etc.). So we can't rely on reading the selection at release time.
 *
 * Workaround: snapshot the selected text on every drag step (while mySelection
 * is still live). On release we flush the last snapshot to the system clipboard,
 * independent of whatever state mySelection is in at that moment. Also keep a
 * visual restore via reflection so the yellow highlight stays visible.
 */
private fun installSelectionGuard(termPanel: com.jediterm.terminal.ui.TerminalPanel) {
    val selectionField = try {
        termPanel.javaClass.getDeclaredField("mySelection").apply { isAccessible = true }
    } catch (e: Throwable) {
        FileLogger.error("Desktop", "Selection guard: cannot access mySelection", e)
        return
    }

    var dragInProgress = false
    var lastSelectionText: String? = null
    var lastSelection: com.jediterm.terminal.model.TerminalSelection? = null

    fun snapshotSelection() {
        val sel = termPanel.selection ?: return
        lastSelection = sel
        try {
            val buffer = termPanel.terminalTextBuffer
            buffer.lock()
            try {
                lastSelectionText = com.jediterm.terminal.model.SelectionUtil
                    .getSelectionText(sel, buffer)
            } finally {
                buffer.unlock()
            }
        } catch (t: Throwable) {
            FileLogger.error("Desktop", "snapshotSelection failed", t)
        }
    }

    termPanel.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
        override fun mouseDragged(e: java.awt.event.MouseEvent) {
            dragInProgress = true
            snapshotSelection()
        }
    })

    termPanel.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) {
            lastSelection = null
            lastSelectionText = null
        }

        override fun mouseReleased(e: java.awt.event.MouseEvent) {
            if (!dragInProgress) return
            dragInProgress = false

            val text = lastSelectionText
            FileLogger.log("Desktop", "selection release: textLen=${text?.length ?: -1}")
            if (!text.isNullOrEmpty()) {
                try {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        java.awt.datatransfer.StringSelection(text), null
                    )
                } catch (t: Throwable) {
                    FileLogger.error("Desktop", "Clipboard write failed", t)
                }
            }

            val saved = lastSelection ?: return
            for (delay in listOf(10, 40, 120, 300)) {
                javax.swing.Timer(delay) {
                    if (selectionField.get(termPanel) == null && lastSelection != null) {
                        selectionField.set(termPanel, saved)
                        termPanel.repaint()
                    }
                }.also { it.isRepeats = false }.start()
            }
        }
    })
}

/**
 * Drag-time scrolling for terminal selection:
 *  B) While a drag is active, poll the global mouse position every 30 ms. If
 *     the cursor leaves the panel vertically, synthesize MouseWheelEvent and
 *     MOUSE_DRAGGED events at the edge. JediTerm's own wheel listener routes
 *     the wheel to tmux (remote reporting) or the local scrollbar depending
 *     on Shift, which matches the drag's shift state captured at press time.
 *  C) Forward wheel-during-drag: after a real wheel event fires while
 *     dragging, dispatch an extra MOUSE_DRAGGED at the current cursor so
 *     the selection edge tracks to the newly-scrolled content.
 */
private fun installDragScroller(termPanel: com.jediterm.terminal.ui.TerminalPanel) {
    var dragging = false
    var dragShift = false
    var pollTimer: javax.swing.Timer? = null

    fun dispatchWheel(x: Int, y: Int, rotation: Int, shift: Boolean) {
        val mods = if (shift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0
        termPanel.dispatchEvent(
            java.awt.event.MouseWheelEvent(
                termPanel, java.awt.event.MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), mods,
                x, y, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,
                kotlin.math.abs(rotation).coerceAtLeast(1), rotation
            )
        )
    }

    fun dispatchDrag(x: Int, y: Int, shift: Boolean) {
        val mods = (if (shift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0) or
            java.awt.event.InputEvent.BUTTON1_DOWN_MASK
        termPanel.dispatchEvent(
            java.awt.event.MouseEvent(
                termPanel, java.awt.event.MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(), mods,
                x, y, 0, false,
                java.awt.event.MouseEvent.BUTTON1
            )
        )
    }

    termPanel.addMouseListener(object : java.awt.event.MouseAdapter() {
        override fun mousePressed(e: java.awt.event.MouseEvent) {
            if (e.button != java.awt.event.MouseEvent.BUTTON1) return
            dragging = true
            dragShift = e.isShiftDown
            pollTimer?.stop()
            pollTimer = javax.swing.Timer(30) {
                if (!dragging || !termPanel.isShowing) return@Timer
                val ptr = java.awt.MouseInfo.getPointerInfo() ?: return@Timer
                val panelLoc = try { termPanel.locationOnScreen } catch (_: Throwable) { return@Timer }
                val pw = termPanel.width
                val ph = termPanel.height
                if (pw <= 0 || ph <= 0) return@Timer
                val localY = ptr.location.y - panelLoc.y
                val localX = (ptr.location.x - panelLoc.x).coerceIn(0, pw - 1)
                val direction = when {
                    localY < 0 -> -1
                    localY >= ph -> 1
                    else -> 0
                }
                if (direction != 0) {
                    val dist = if (direction < 0) -localY else localY - ph + 1
                    val speed = (1 + dist / 25).coerceAtMost(5)
                    val edgeY = if (direction < 0) 0 else ph - 1
                    dispatchWheel(localX, edgeY, direction * speed, dragShift)
                    dispatchDrag(localX, edgeY, dragShift)
                }
            }.apply { isRepeats = true; start() }
        }

        override fun mouseReleased(e: java.awt.event.MouseEvent) {
            dragging = false
            pollTimer?.stop()
            pollTimer = null
        }
    })

    termPanel.addMouseWheelListener { e ->
        if (!dragging) return@addMouseWheelListener
        // Let JediTerm's existing wheel listener scroll first, then synthesize
        // a MOUSE_DRAGGED at the cursor so the selection extends to the new
        // content revealed by the scroll.
        javax.swing.SwingUtilities.invokeLater {
            if (!dragging || !termPanel.isShowing) return@invokeLater
            val ptr = java.awt.MouseInfo.getPointerInfo() ?: return@invokeLater
            val panelLoc = try { termPanel.locationOnScreen } catch (_: Throwable) { return@invokeLater }
            val pw = termPanel.width
            val ph = termPanel.height
            if (pw <= 0 || ph <= 0) return@invokeLater
            val x = (ptr.location.x - panelLoc.x).coerceIn(0, pw - 1)
            val y = (ptr.location.y - panelLoc.y).coerceIn(0, ph - 1)
            dispatchDrag(x, y, dragShift || e.isShiftDown)
        }
    }
}

/**
 * Snapshot the bottom [rowCount] rows of the JediTerm widget with per-cell
 * foreground color info for [com.clauderemote.session.ScreenStateClassifier].
 *
 * [com.jediterm.terminal.model.TerminalTextBuffer] has an internal lock, so
 * this can be called from any thread — we acquire the lock ourselves.
 */
private fun readJediTermSnapshot(widget: JediTermWidget?, rowCount: Int): ScreenStateSnapshot? {
    val w = widget ?: return null
    val buffer = try { w.terminalTextBuffer } catch (_: Throwable) { return null } ?: return null
    buffer.lock()
    try {
        val cols = buffer.width
        val rows = buffer.height
        if (cols <= 0 || rows <= 0) return null
        val startRow = (rows - rowCount).coerceAtLeast(0)
        val result = ArrayList<RowSnapshot>(rows - startRow)
        for (r in startRow until rows) {
            val line = buffer.getLine(r) ?: continue
            val text = CharArray(cols) { ' ' }
            val reds = BooleanArray(cols)
            var col = 0
            line.forEachEntry { entry ->
                val isRed = isReddishFgJedi(entry.style?.foreground)
                val s = entry.text?.toString() ?: ""
                val len = entry.length
                var i = 0
                while (i < len && col < cols) {
                    if (i < s.length) text[col] = s[i]
                    reds[col] = isRed
                    i++
                    col++
                }
            }
            result.add(RowSnapshot(String(text), reds))
        }
        return ScreenStateSnapshot(result, cols)
    } finally {
        buffer.unlock()
    }
}

/**
 * Is the JediTerm foreground color "reddish"? Mirrors the Android variant in
 * [com.termux.terminal.SshTerminalSession.isReddishFg] — checks ANSI red
 * (indices 1 & 9), common 256-color reds, and 24-bit with dominant red.
 */
private fun isReddishFgJedi(fg: TerminalColor?): Boolean {
    if (fg == null) return false
    if (fg.isIndexed) {
        return when (fg.colorIndex) {
            1, 9, 88, 124, 160, 196, 197, 203, 204 -> true
            else -> false
        }
    }
    return try {
        val c = fg.toColor()
        val r = c.red
        val g = c.green
        val b = c.blue
        r >= 120 && r > g + 40 && r > b + 40
    } catch (_: Throwable) {
        false
    }
}
