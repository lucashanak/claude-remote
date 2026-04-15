package com.clauderemote.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.clauderemote.util.FileLogger
import com.termux.terminal.SshTerminalSession
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Handle exposed once the native [TerminalView] + [SshTerminalSession] are
 * instantiated, so the host Activity can feed SSH bytes and replay buffers.
 */
class SshTerminalHandle internal constructor(
    val view: TerminalView,
    val session: SshTerminalSession,
) {
    /** Whether the underlying [TerminalEmulator] has been initialized (first layout has happened). */
    val isReady: Boolean get() = session.emulator != null

    fun feedSshBytes(bytes: ByteArray) = session.receiveSshBytes(bytes)

    /** Clear the emulator and replay a buffer (used on tab switch). Safe before-ready: drops if not ready. */
    fun replay(bufferedOutput: ByteArray) {
        if (!isReady) return
        session.emulator?.reset()
        if (bufferedOutput.isNotEmpty()) session.appendDirect(bufferedOutput)
        view.onScreenUpdated()
    }

    fun applyFontSize(dp: Int) {
        val px = (dp.coerceIn(6, 48) * view.resources.displayMetrics.density).toInt()
        view.setTextSize(px)
    }

    fun applyColorScheme(scheme: String) {
        applyColorSchemeTo(session, scheme)
        view.invalidate()
    }
}

@Composable
fun SshTerminal(
    fontSizeDp: Int,
    colorScheme: String,
    scrollbackRows: Int,
    onUserInput: (ByteArray) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    onSingleTap: () -> Unit,
    onReady: (SshTerminalHandle) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val sessionClient = ClauDeTerminalSessionClient(ctx.applicationContext)
            val session = SshTerminalSession(
                transcriptRowsCount = scrollbackRows.coerceIn(100, 50_000),
                client = sessionClient,
                onUserInput = onUserInput,
                onResize = onResize,
            )
            val view = TerminalView(ctx, null).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(0xFF1E1E1E.toInt())
                isFocusable = true
                isFocusableInTouchMode = true
            }
            sessionClient.terminalView = view
            val viewClient = ClauDeTerminalViewClient(onSingleTap)
            view.setTerminalViewClient(viewClient)
            val density = ctx.resources.displayMetrics.density
            view.setTextSize((fontSizeDp.coerceIn(6, 48) * density).toInt())

            // attachSession reads getWidth/getHeight and is a no-op if the view
            // isn't yet laid out. Handle both the immediate-post case and later
            // layout changes (keyboard open/close, orientation). onReady is
            // deferred until the emulator actually exists so callers can't feed
            // bytes into a null emulator.
            val handle = SshTerminalHandle(view, session)
            var readyFired = false
            var lastW = 0
            var lastH = 0
            fun tryAttach() {
                val w = view.width; val h = view.height
                if (w > 0 && h > 0) {
                    val sizeChanged = w != lastW || h != lastH
                    if (!handle.isReady) {
                        view.attachSession(session)
                        applyColorSchemeTo(session, colorScheme)
                        FileLogger.log("SshTerminal", "attached: ${w}x${h}px")
                        lastW = w; lastH = h
                        view.onScreenUpdated()
                    } else if (sizeChanged) {
                        lastW = w; lastH = h
                        view.updateSize()
                        view.onScreenUpdated()
                    }
                    if (!readyFired && handle.isReady) {
                        readyFired = true
                        onReady(handle)
                        // Belt-and-suspenders re-fit: Compose sometimes settles the
                        // final layout after our first attach (insets, keyboard
                        // animation, split-screen). Without a re-poke the emulator
                        // stays on cols/rows measured from the initial pass and
                        // tmux renders at the wrong size until the next real
                        // resize. Re-run tryAttach after a beat to catch that.
                        view.postDelayed({ tryAttach() }, 300)
                        view.postDelayed({ tryAttach() }, 800)
                    }
                }
            }
            view.post { tryAttach() }
            view.viewTreeObserver.addOnGlobalLayoutListener { tryAttach() }

            view
        },
        update = { view ->
            val density = view.resources.displayMetrics.density
            view.setTextSize((fontSizeDp.coerceIn(6, 48) * density).toInt())
            (view.mTermSession as? SshTerminalSession)?.let { applyColorSchemeTo(it, colorScheme) }
            view.onScreenUpdated()
        }
    )
}

private fun applyColorSchemeTo(session: SshTerminalSession, scheme: String) {
    val emu: TerminalEmulator = session.emulator ?: return
    val colors = emu.mColors.mCurrentColors
    val (bg, fg, cursor) = when (scheme) {
        "solarized-dark" -> Triple(0xFF002B36.toInt(), 0xFF839496.toInt(), 0xFF93A1A1.toInt())
        "dracula"        -> Triple(0xFF282A36.toInt(), 0xFFF8F8F2.toInt(), 0xFFBD93F9.toInt())
        "monokai"        -> Triple(0xFF272822.toInt(), 0xFFF8F8F2.toInt(), 0xFFA6E22E.toInt())
        "linux"          -> Triple(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF00FF00.toInt())
        else             -> Triple(0xFF1E1E1E.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
    }
    colors[TextStyle.COLOR_INDEX_BACKGROUND] = bg
    colors[TextStyle.COLOR_INDEX_FOREGROUND] = fg
    colors[TextStyle.COLOR_INDEX_CURSOR] = cursor
}

/**
 * Minimal [TerminalViewClient] — delegates gestures to TerminalView's built-in
 * behavior, forwards single-tap up to the host (used to show IME).
 *
 * Pinch-zoom (onScale) is a no-op for the MVP; font size is controlled by the
 * setting. Re-add later via host-side state if needed.
 */
private class ClauDeTerminalViewClient(
    private val onSingleTap: () -> Unit,
) : TerminalViewClient {

    override fun onScale(scale: Float): Float = 1.0f

    override fun onSingleTapUp(e: MotionEvent) {
        onSingleTap()
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}

    override fun logError(tag: String, message: String) = FileLogger.error(tag, message)
    override fun logWarn(tag: String, message: String) = FileLogger.log(tag, message)
    override fun logInfo(tag: String, message: String) = FileLogger.log(tag, message)
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
        FileLogger.error(tag, message, e)
    override fun logStackTrace(tag: String, e: Exception) = FileLogger.error(tag, e.message ?: "", e)
}

private class ClauDeTerminalSessionClient(private val ctx: Context) : TerminalSessionClient {
    var terminalView: TerminalView? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val paste = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: return
        session.emulator?.paste(paste)
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) = FileLogger.error(tag, message)
    override fun logWarn(tag: String, message: String) = FileLogger.log(tag, message)
    override fun logInfo(tag: String, message: String) = FileLogger.log(tag, message)
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) =
        FileLogger.error(tag, message, e)
    override fun logStackTrace(tag: String, e: Exception) = FileLogger.error(tag, e.message ?: "", e)
}
