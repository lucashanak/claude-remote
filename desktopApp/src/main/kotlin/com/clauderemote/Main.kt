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
        FileLogger.log("TermGeom", "resize cb ${termSize.columns}x${termSize.rows}")
        lastTermSize = termSize
        tabManager.activeTabId.value?.let { id ->
            sessionOrchestrator.resize(id, termSize.columns, termSize.rows)
        }
    }

    /** Re-sends the last known terminal size to the active session.
     *  Call this after connecting to an existing tmux session to fix size mismatch
     *  (e.g. session was previously used from Android with different terminal size).
     *  Caller may pass [fallback] (current widget display dims) for the case where
     *  JediTerm hasn't fired its first resize callback yet and [lastTermSize] is null. */
    fun reapplySize(fallback: com.jediterm.core.util.TermSize? = null) {
        val size = lastTermSize ?: fallback ?: return
        val id = tabManager.activeTabId.value ?: return
        FileLogger.log("TermGeom", "reapplySize -> ${size.columns}x${size.rows}")
        sessionOrchestrator.resize(id, size.columns, size.rows)
    }
}

// Global terminal state
private var termWidget: JediTermWidget? = null
private var sshConnector: SshTtyConnector? = null
// macOS-only: last-applied "invert colors" state, used ONLY to detect a change in
// DesktopTerminalView's `update` lambda and trigger an immediate repaint. The actual
// color decision is read LIVE from appSettings in the settings provider, so this is
// not the source of truth (a stale value here can't strand the terminal inverted).
@Volatile private var terminalInvertColors: Boolean = false

fun main() = application {
    // Init logging
    val logDir = File(System.getProperty("user.home"), ".claude-remote")
    logDir.mkdirs()
    FileLogger.init(logDir, System.getProperty("jpackage.app-version") ?: "dev")

    val prefs = PlatformPreferences()
    val serverStorage = ServerStorage(prefs)
    val appSettings = AppSettings(prefs)
    val tabManager = TabManager()
    val sessionStorage = com.clauderemote.storage.SessionStorage(prefs)
    val sessionOrchestrator = SessionOrchestrator(serverStorage, tabManager, sessionStorage)
    // Mirror FileLogger to the server (per-install file) — same remote
    // diagnostics as the Android app.
    sessionOrchestrator.startLogShipping(appSettings.installId)
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
            // clearBuffer() only empties the scrollback history on
            // JediTerm — it does NOT clear the visible screen cells.
            // Without an ANSI clear-screen prefix, the previous
            // session's last frame stays on the visible viewport and
            // tmux's diff-based SIGWINCH redraw only overwrites cells
            // that *changed* between its model and ours. The leftover
            // unchanged cells from session A then sit beneath the
            // partial redraw of session B, producing the duplicated
            // status lines visible in the user's screenshot. Prepend
            // ESC[H (cursor home) + ESC[2J (clear visible screen) +
            // ESC[3J (clear scrollback) so the JediTerm canvas is
            // genuinely empty before the tail replay.
            widget.terminalPanel.clearBuffer()
            val clearSeq = "[H[2J[3J"
            connector.feedOutput(clearSeq + bufferedOutput)
            // Explicit repaint: JediTerm schedules its own repaint when
            // data arrives but the AWT component is not always invalidated
            // on macOS Compose SwingPanel while it lacks focus. Calling
            // repaint() here ensures the clear+buffer replay is always
            // rendered without waiting for the next user-input event.
            widget.terminalPanel.repaint()
            widget.terminalPanel.requestFocusInWindow()
            val buffer = widget.terminalTextBuffer
            val cols = buffer?.width?.takeIf { it > 0 }
                ?: connector.lastTermSize?.columns
                ?: return@invokeLater
            val rows = buffer?.height?.takeIf { it > 0 }
                ?: connector.lastTermSize?.rows
                ?: return@invokeLater
            if (cols <= 1 || rows <= 0) return@invokeLater
            // Force a full tmux redraw — the replayed tail is a partial
            // frame. kickRedraw issues `tmux refresh-client` (deterministic
            // full repaint regardless of geometry; falls back to a SIGWINCH
            // col-shrink toggle), see SessionOrchestrator.kickRedraw.
            sessionOrchestrator.kickRedraw(sessionId, cols, rows)
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
        else readJediTermSnapshot(termWidget, rowCount = 16)
    }
    sessionOrchestrator.fullScreenReader = { sessionId ->
        if (tabManager.activeTabId.value != sessionId) null
        else readJediTermFullText(termWidget)
    }

    // Desktop notifications. On Linux the AWT SystemTray balloon renders as an
    // ugly, text-less mini window (the toolkit has no real freedesktop backend),
    // so we use `notify-send` — the universal D-Bus (org.freedesktop.Notifications)
    // path that KDE Plasma, GNOME, XFCE et al. implement natively. macOS/Windows
    // keep the AWT tray balloon, which works acceptably there.
    sessionOrchestrator.onClaudeNeedsInput = { _, hint, _, _ ->
        if (appSettings.notificationsEnabled) {
            if (IS_LINUX) sendLinuxNotification(hint)
            else sendTrayNotification(hint)
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

    // Resurrect persisted tabs from previous app run before showing the window
    // so the user sees their sessions immediately (status DISCONNECTED while
    // reconnect spins up in the background, scoped to the orchestrator).
    sessionOrchestrator.restoreAndReconnect()

    Window(
        onCloseRequest = {
            sshConnector?.close()
            exitApplication()
        },
        title = "Claude Remote",
        icon = appIcon?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it) },
        state = rememberWindowState(width = 1000.dp, height = 700.dp)
    ) {
        // Force dark titlebar on macOS so it matches the app chrome.
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            androidx.compose.runtime.SideEffect {
                window.rootPane.putClientProperty("apple.awt.windowAppearance", "NSAppearanceNameDarkAqua")
            }
        }
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
                    if (IS_LINUX) {
                        // Hand the downloaded distro package to the system's GUI
                        // installer (polkit prompt) — we never had a Linux branch
                        // before, so it fell through to the macOS `hdiutil` path
                        // and crashed with "Cannot run program hdiutil".
                        installLinuxUpdate(bytes, info, tmpDir)
                    } else if (IS_MAC && info.dmgUrl.isNotBlank()) {
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
                    // from a different device (Android) that had a smaller terminal.
                    // Pass widget dims as a fallback in case JediTerm hasn't yet fired
                    // its first resize into the connector.
                    val fallback = widget?.terminalTextBuffer?.let { b ->
                        val c = b.width; val r = b.height
                        if (c > 0 && r > 0) com.jediterm.core.util.TermSize(c, r) else null
                    }
                    connector.reapplySize(fallback)
                    if (widget != null && buffer.isNotEmpty()) {
                        widget.terminalPanel.clearBuffer()
                        val clearSeq = "[H[2J[3J"
                        connector.feedOutput(clearSeq + buffer)
                        // Same rationale as onTabSwitched: explicit repaint
                        // ensures the replayed content is rendered promptly
                        // even when the panel regains focus asynchronously.
                        widget.terminalPanel.repaint()
                    }
                    // The replayed buffer is a partial frame and reapplySize
                    // is a no-op when the size didn't change — without an
                    // explicit kick tmux never resends the full screen here
                    // (same partial-paint bug as on tab switch).
                    val kc = widget?.terminalTextBuffer?.width?.takeIf { it > 1 }
                        ?: connector.lastTermSize?.columns
                    val kr = widget?.terminalTextBuffer?.height?.takeIf { it > 0 }
                        ?: connector.lastTermSize?.rows
                    if (kc != null && kr != null) {
                        sessionOrchestrator.kickRedraw(activeId, kc, kr)
                    }
                }
            },
            onPickFile = { callback ->
                // Native picker per platform: zenity/kdialog portal on Linux
                // (AWT FileDialog is unreliable inside a Compose window there),
                // NSOpenPanel via java.awt.FileDialog on macOS. See
                // pickFilesForAttach for the full rationale and safety nets.
                pickFilesForAttach(callback)
            },
            onSaveFile = { bytes, suggestedName ->
                // Show the native save dialog on the EDT, then write bytes on a
                // background thread so the EDT is never blocked by I/O.
                javax.swing.SwingUtilities.invokeLater {
                    try {
                        val parent = javax.swing.SwingUtilities.getWindowAncestor(termWidget) as? java.awt.Frame
                        val dialog = java.awt.FileDialog(parent, "Save File", java.awt.FileDialog.SAVE)
                        dialog.file = suggestedName
                        dialog.isVisible = true
                        val dir = dialog.directory
                        val name = dialog.file
                        if (dir != null && name != null) {
                            val target = java.io.File(dir, name)
                            Thread {
                                try {
                                    target.writeBytes(bytes)
                                    com.clauderemote.util.FileLogger.log("Main", "Saved file: ${target.absolutePath}")
                                } catch (e: Exception) {
                                    com.clauderemote.util.FileLogger.error(
                                        "Main", "Failed to write saved file: ${e.message}", e
                                    )
                                }
                            }.start()
                        }
                    } catch (e: Throwable) {
                        com.clauderemote.util.FileLogger.error(
                            "Main", "Save FileDialog failed: ${e.message}", e
                        )
                    }
                }
            },
            onOpenUrl = { url ->
                // Do NOT log `url` — it carries the login/OAuth seam.
                try {
                    if (IS_LINUX) Runtime.getRuntime().exec(arrayOf("xdg-open", url))
                    else java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                } catch (e: Exception) {
                    com.clauderemote.util.FileLogger.error("Login", "openUrl failed: ${e.message}", e)
                }
            },
            exitApp = ::exitApplication,
            terminalContent = { modifier ->
                DesktopTerminalView(
                    modifier = modifier,
                    connector = connector,
                    appSettings = appSettings,
                    // Read here so the SwingPanel recomposes when the toggle flips.
                    invertColors = appSettings.invertColors
                )
            }
        )
    }
}

/**
 * macOS only. The JediTerm terminal is a heavyweight Swing/AWT component embedded
 * via SwingPanel; on macOS it renders over the Compose layer, so the Compose
 * color-matrix "invert colors" filter in App.kt never touches it. We therefore
 * invert at the source here. Strictly macOS-gated: on Linux/Windows the Compose
 * filter already inverts the terminal, and source-inverting there too would
 * double-invert (cancel out).
 */
private val IS_MAC: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")

private val IS_LINUX: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().let {
        it.contains("nux") || it.contains("nix") || it.contains("aix")
    }

/**
 * Invert the terminal at the JediTerm source on ALL desktop platforms.
 *
 * The terminal is a heavyweight Swing/AWT component (SwingPanel), whose native
 * pixels are painted over the Compose Skia layer on every OS — so the Compose
 * color-matrix "invert" filter in App.kt only ever flips the Compose chrome,
 * never the terminal, regardless of platform. Originally this was macOS-gated
 * on the assumption that the Compose filter inverted the terminal on
 * Linux/Windows; it does not, which is why invert appeared dead on Linux. There
 * is no double-invert risk precisely because the Compose filter can't reach the
 * heavyweight surface.
 */
private val INVERT_TERMINAL_AT_SOURCE: Boolean = IS_MAC || IS_LINUX

/**
 * Install a downloaded Linux update by handing the package to the distro's GUI
 * installer. The app lives in /opt (root-owned), so we can't do a silent
 * in-place swap the way the macOS path replaces the bundle in /Applications —
 * instead the user confirms the install via a polkit prompt, then restarts.
 *
 * [UpdateChecker.linuxPkgKind] is the same call the download step used to pick
 * the asset, so the file extension here always matches what was fetched.
 */
private fun installLinuxUpdate(
    bytes: ByteArray,
    info: com.clauderemote.util.UpdateInfo,
    tmpDir: File
) {
    val kind = com.clauderemote.util.UpdateChecker.linuxPkgKind(info)
    val file = when (kind) {
        com.clauderemote.util.UpdateChecker.LinuxPkg.PKG ->
            File(tmpDir, "claude-remote-${info.version}.pkg.tar.zst")
        com.clauderemote.util.UpdateChecker.LinuxPkg.DEB ->
            File(tmpDir, "claude-remote-${info.version}.deb")
        com.clauderemote.util.UpdateChecker.LinuxPkg.TARGZ ->
            File(tmpDir, "claude-remote-${info.version}.tar.gz")
        com.clauderemote.util.UpdateChecker.LinuxPkg.NONE -> {
            FileLogger.log("Desktop", "No Linux package in release — opening releases page")
            openReleasesPage()
            return
        }
    }
    file.writeBytes(bytes)
    FileLogger.log("Desktop", "Linux update downloaded: ${file.absolutePath} (kind=$kind)")

    // Prefer a graphical package installer (shows a polkit auth prompt, no root
    // handled by us). Fall back through progressively more generic handlers,
    // and finally the release page so the user is never left stuck.
    val launched = when (kind) {
        com.clauderemote.util.UpdateChecker.LinuxPkg.PKG ->
            tryLaunch("pamac-installer", file.absolutePath) ||
                tryLaunch("gnome-software", "--local-filename=${file.absolutePath}") ||
                tryLaunch("xdg-open", file.absolutePath)
        com.clauderemote.util.UpdateChecker.LinuxPkg.DEB ->
            tryLaunch("xdg-open", file.absolutePath) ||
                tryLaunch("gdebi-gtk", file.absolutePath)
        // Portable tarball: no installer — reveal it in the file manager so the
        // user can extract over their install dir.
        else -> tryLaunch("xdg-open", file.parentFile.absolutePath)
    }
    if (!launched) {
        FileLogger.log("Desktop", "No installer launched — opening releases page")
        openReleasesPage()
    }
}

/** Start a process, returning true only if the command actually exists/launched. */
private fun tryLaunch(vararg cmd: String): Boolean = try {
    ProcessBuilder(*cmd).start()
    FileLogger.log("Desktop", "Launched: ${cmd.joinToString(" ")}")
    true
} catch (e: Exception) {
    FileLogger.log("Desktop", "Not available: ${cmd.firstOrNull()} (${e.message})")
    false
}

private fun openReleasesPage() {
    try {
        java.awt.Desktop.getDesktop()
            .browse(java.net.URI(com.clauderemote.util.UpdateChecker.RELEASES_PAGE))
    } catch (e: Exception) {
        FileLogger.error("Desktop", "Failed to open releases page: ${e.message}", e)
    }
}

/**
 * Fire a native Linux notification via `notify-send` (freedesktop D-Bus). The
 * `-i claude-remote` icon and desktop-entry hint make KDE/GNOME attribute it to
 * the app. If notify-send is missing we skip rather than fall back to the AWT
 * balloon — the user would rather have no notification than the ugly one.
 */
private fun sendLinuxNotification(hint: String) {
    try {
        ProcessBuilder(
            "notify-send",
            "-a", "Claude Remote",
            "-i", "claude-remote",
            "-h", "string:desktop-entry:claude-remote",
            "Claude Remote", hint
        ).start()
    } catch (e: Exception) {
        FileLogger.log("Desktop", "notify-send unavailable, notification skipped: ${e.message}")
    }
}

/** AWT SystemTray balloon — used on macOS/Windows where it renders acceptably. */
private fun sendTrayNotification(hint: String) {
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

/**
 * Pick files for attachment. On Linux the AWT [java.awt.FileDialog] embedded in
 * a Compose/Skiko window is unreliable (it falls back to the ancient Motif peer
 * under many WMs and multi-select comes back empty), so we drive the native
 * desktop-portal picker via `zenity`/`kdialog` and only fall back to AWT when
 * neither is present. Elsewhere (macOS) FileDialog is the native NSOpenPanel and
 * works well, so it stays the primary path.
 *
 * [callback] is always invoked exactly once with the selected (bytes, name)
 * pairs, or an empty list on cancel/failure.
 */
private fun pickFilesForAttach(callback: (List<Pair<ByteArray, String>>) -> Unit) {
    if (IS_LINUX) {
        // zenity/kdialog block until the user is done, so run off the EDT.
        Thread {
            val portalFiles = pickFilesViaPortal()
            if (portalFiles != null) {
                callback(portalFiles.mapNotNull { f ->
                    try { f.readBytes() to f.name } catch (_: Exception) { null }
                })
            } else {
                // No portal tool installed — fall back to the AWT dialog.
                FileLogger.log("Main", "No zenity/kdialog found; using AWT FileDialog")
                pickFilesViaAwt(callback)
            }
        }.apply { isDaemon = true }.start()
        return
    }
    pickFilesViaAwt(callback)
}

/**
 * Returns the selected files via a native portal dialog, an empty list if the
 * user cancelled, or null if neither `zenity` nor `kdialog` is available (so the
 * caller can fall back to AWT).
 */
private fun pickFilesViaPortal(): List<File>? {
    // zenity: newline-separated absolute paths on stdout (robust with spaces).
    runPortal(
        listOf("zenity", "--file-selection", "--multiple", "--separator=\n", "--title=Attach File")
    )?.let { out ->
        return out.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.map { File(it) }
    }
    // kdialog (KDE): single file to avoid space-separated multi-path parsing.
    runPortal(listOf("kdialog", "--getopenfilename", System.getProperty("user.home") ?: "."))
        ?.let { out ->
            val path = out.trim()
            return if (path.isEmpty()) emptyList() else listOf(File(path))
        }
    return null
}

/**
 * Run a portal picker command. Returns its stdout on a clean pick, "" on cancel,
 * or null when the program isn't installed (so the next option can be tried).
 */
private fun runPortal(cmd: List<String>): String? = try {
    val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
    val out = proc.inputStream.bufferedReader().readText()
    val code = proc.waitFor()
    // Exit 0 = picked, 1 = user cancelled (both mean the tool exists → not null).
    if (code == 0) out else ""
} catch (e: Exception) {
    // IOException here means the binary wasn't found — signal "try the next one".
    FileLogger.log("Main", "Portal picker unavailable: ${cmd.firstOrNull()} (${e.message})")
    null
}

/** The original AWT FileDialog picker (native NSOpenPanel on macOS). */
private fun pickFilesViaAwt(callback: (List<Pair<ByteArray, String>>) -> Unit) {
    javax.swing.SwingUtilities.invokeLater {
        var fired = false
        fun fire(pairs: List<Pair<ByteArray, String>>) {
            if (fired) return
            fired = true
            callback(pairs)
        }
        try {
            val parent = javax.swing.SwingUtilities.getWindowAncestor(termWidget) as? java.awt.Frame
            val dialog = java.awt.FileDialog(parent, "Attach File", java.awt.FileDialog.LOAD)
            dialog.isMultipleMode = true
            dialog.isVisible = true
            val files = dialog.files
            if (files != null && files.isNotEmpty()) {
                fire(files.mapNotNull { f ->
                    try { f.readBytes() to f.name } catch (_: Exception) { null }
                })
            } else {
                fire(emptyList())
            }
        } catch (e: Throwable) {
            FileLogger.error("Main", "FileDialog failed: ${e.message}", e)
            fire(emptyList())
        } finally {
            if (!fired) fire(emptyList())
            termWidget?.let { w -> w.revalidate(); w.repaint() }
        }
    }
}

/** 255-complement of a JediTerm core color (alpha preserved). */
private fun invertCoreColor(c: com.jediterm.core.Color): com.jediterm.core.Color =
    com.jediterm.core.Color(255 - c.red, 255 - c.green, 255 - c.blue, c.alpha)

@Composable
private fun DesktopTerminalView(
    modifier: Modifier,
    connector: SshTtyConnector,
    appSettings: AppSettings,
    invertColors: Boolean
) {
    // Force an immediate repaint when the invert toggle flips. The settings
    // provider reads the flag live on the next paint, but when the terminal is
    // idle the next natural paint (cursor blink / output) can be seconds away —
    // this makes the toggle apply at once. Keyed on invertColors so it re-fires
    // on every change.
    androidx.compose.runtime.LaunchedEffect(invertColors) {
        termWidget?.let { it.terminalPanel.repaint(); it.repaint() }
    }
    SwingPanel(
        modifier = modifier,
        factory = {
            JPanel(BorderLayout()).also { panel ->
                panel.background = java.awt.Color(0x1E, 0x1E, 0x1E)
                // Live invert flag read by the settings provider on every paint.
                // Updated from the SwingPanel `update` lambda on recomposition.
                terminalInvertColors = INVERT_TERMINAL_AT_SOURCE && invertColors

                // Reuse existing widget if already created
                val existing = termWidget
                if (existing != null) {
                    existing.parent?.remove(existing)
                    panel.add(existing, BorderLayout.CENTER)
                    // Re-apply invert state to the reused widget.
                    existing.terminalPanel.repaint()
                    // Delayed resize — panel size not available yet
                    panel.addComponentListener(object : java.awt.event.ComponentAdapter() {
                        override fun componentResized(e: java.awt.event.ComponentEvent?) {
                            existing.size = panel.size
                            existing.revalidate()
                            val b = existing.terminalTextBuffer
                            FileLogger.log("TermGeom", "reused resize panel=${panel.size.width}x${panel.size.height}px -> grid=${b?.width}x${b?.height}")
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
                    val darkFgColor = com.jediterm.core.Color(0xCC, 0xCC, 0xCC)
                    val darkBgColor = com.jediterm.core.Color(0x1E, 0x1E, 0x1E)
                    val darkFg = com.jediterm.terminal.TerminalColor(darkFgColor)
                    val darkBg = com.jediterm.terminal.TerminalColor(darkBgColor)
                    // Inverted defaults (precomputed, since fg/bg are constant).
                    val invFg = com.jediterm.terminal.TerminalColor(invertCoreColor(darkFgColor))
                    val invBg = com.jediterm.terminal.TerminalColor(invertCoreColor(darkBgColor))
                    val settings = object : DefaultSettingsProvider() {
                        // Palette that 255-inverts every color the default XTerm palette
                        // produces (16 ANSI colors + 256-index lookups), so reverse-video
                        // and colored text invert correctly. macOS-only via terminalInvertColors.
                        private val basePalette = super.getTerminalColorPalette()
                        private val invertedPalette =
                            object : com.jediterm.terminal.emulator.ColorPalette() {
                                override fun getForegroundByColorIndex(colorIndex: Int): com.jediterm.core.Color =
                                    invertCoreColor(
                                        basePalette.getForeground(
                                            com.jediterm.terminal.emulator.ColorPalette.getIndexedTerminalColor(colorIndex)!!
                                        )
                                    )

                                override fun getBackgroundByColorIndex(colorIndex: Int): com.jediterm.core.Color =
                                    invertCoreColor(
                                        basePalette.getBackground(
                                            com.jediterm.terminal.emulator.ColorPalette.getIndexedTerminalColor(colorIndex)!!
                                        )
                                    )
                            }

                        override fun getTerminalFontSize(): Float =
                            appSettings.terminalFontSize.toFloat()

                        override fun getBufferMaxLinesCount(): Int =
                            appSettings.terminalScrollback

                        override fun useAntialiasing(): Boolean = true

                        // Read the setting LIVE on every paint (props.getProperty is an
                        // in-memory lookup) instead of trusting a cached flag — so the
                        // terminal can never get stuck inverted: even if the
                        // recomposition-driven repaint in `update` is missed when the
                        // toggle flips, the next natural paint self-corrects. All
                        // desktop platforms (see INVERT_TERMINAL_AT_SOURCE).
                        private val invertActive: Boolean
                            get() = INVERT_TERMINAL_AT_SOURCE && appSettings.invertColors

                        override fun getDefaultForeground(): com.jediterm.terminal.TerminalColor =
                            if (invertActive) invFg else darkFg
                        override fun getDefaultBackground(): com.jediterm.terminal.TerminalColor =
                            if (invertActive) invBg else darkBg

                        override fun getTerminalColorPalette(): com.jediterm.terminal.emulator.ColorPalette =
                            if (invertActive) invertedPalette else basePalette

                        // Compose SwingPanel on macOS doesn't forward Cmd+C reliably to
                        // the embedded Swing component, so auto-copy during drag gives the
                        // user the clipboard contents without needing the keystroke.
                        override fun copyOnSelect(): Boolean = true
                    }

                    val widget = JediTermWidget(settings)
                    widget.setTtyConnector(connector)
                    widget.start()
                    termWidget = widget

                    installNativeSelectionCopy(widget.terminalPanel)
                    installSelectionGuard(widget.terminalPanel)
                    installDragScroller(widget.terminalPanel)

                    panel.add(widget, BorderLayout.CENTER)

                    // Force layout after panel is shown (macOS needs explicit sizing)
                    fun forceSize() {
                        if (panel.size.width > 0 && panel.size.height > 0) {
                            widget.size = panel.size
                            widget.revalidate()
                            widget.repaint()
                            // Pass widget display dims as fallback — JediTerm fires its
                            // own resize() callback asynchronously after revalidate(),
                            // so lastTermSize may not be populated yet on first paint.
                            val b = widget.terminalTextBuffer
                            val fallback = if (b != null && b.width > 0 && b.height > 0)
                                com.jediterm.core.util.TermSize(b.width, b.height)
                            else null
                            FileLogger.log("TermGeom", "forceSize panel=${panel.size.width}x${panel.size.height}px -> grid=${b?.width}x${b?.height}")
                            connector.reapplySize(fallback)
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
            // Live "invert colors" toggle (all desktop platforms). The settings
            // provider reads terminalInvertColors on every paint, so flipping it +
            // repainting re-renders inverted/normal without recreating the widget.
            val nextInvert = INVERT_TERMINAL_AT_SOURCE && invertColors
            if (nextInvert != terminalInvertColors) {
                terminalInvertColors = nextInvert
                widget.terminalPanel.repaint()
            }
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
 * Primary clipboard path for terminal text selection on macOS. JediTerm fires
 * selectionChanged() from its OWN internal selection updates (the same ones that
 * draw the highlight), so it works even when raw AWT mouse events don't reach our
 * listeners through the Compose SwingPanel interop — which is exactly what breaks
 * copyOnSelect and the mouse-based [installSelectionGuard]. Copy the current
 * selection to the system clipboard whenever it changes to a non-empty value.
 */
private fun installNativeSelectionCopy(termPanel: com.jediterm.terminal.ui.TerminalPanel) {
    termPanel.addSelectionListener(object : com.jediterm.terminal.model.TerminalSelectionChangesListener {
        override fun selectionChanged(selection: com.jediterm.terminal.model.TerminalSelection?) {
            if (selection == null) return
            try {
                val buffer = termPanel.terminalTextBuffer
                val text: String
                buffer.lock()
                try {
                    text = com.jediterm.terminal.model.SelectionUtil.getSelectionText(selection, buffer)
                } finally {
                    buffer.unlock()
                }
                if (text.isNotEmpty()) {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        java.awt.datatransfer.StringSelection(text), null
                    )
                }
            } catch (t: Throwable) {
                FileLogger.error("Desktop", "native selection copy failed", t)
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
 * Whole visible screen text, de-wrapped: consecutive JediTerm lines are joined
 * without a '\n' at wrap points (a line whose [TerminalLine.isWrapped] is true is
 * one logical line with the next), so a hard-wrapped login URL is recovered
 * intact. Used only for login-URL extraction.
 */
private fun readJediTermFullText(widget: JediTermWidget?): String? {
    val w = widget ?: return null
    val buffer = try { w.terminalTextBuffer } catch (_: Throwable) { return null } ?: return null
    buffer.lock()
    try {
        val rows = buffer.height
        if (rows <= 0) return null
        val sb = StringBuilder()
        for (r in 0 until rows) {
            val line = buffer.getLine(r) ?: continue
            sb.append(line.text)
            // A wrapped line continues into the next physical row — no break.
            if (!line.isWrapped) sb.append('\n')
        }
        return sb.toString()
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
