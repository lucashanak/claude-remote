package com.termux.terminal

import com.clauderemote.session.RowSnapshot
import com.clauderemote.session.ScreenStateSnapshot

/**
 * A [TerminalSession] that is *not* backed by a local subprocess. Instead, SSH bytes
 * received from the network are fed in via [receiveSshBytes], and user keystrokes
 * produced by the view are forwarded back out via [onUserInput] so the containing
 * application can write them to the SSH channel.
 *
 * Placed in `com.termux.terminal` to access the package-private
 * [mProcessToTerminalIOQueue] and [mMainThreadHandler] used for the main-thread
 * repaint handoff — the same machinery the upstream implementation uses for its
 * PTY reader thread, just driven from our SSH reader instead.
 *
 * Must be constructed on the Android main thread (the superclass instantiates an
 * [android.os.Handler] with no explicit Looper).
 */
class SshTerminalSession(
    private val transcriptRowsCount: Int,
    client: TerminalSessionClient,
    private val onUserInput: (ByteArray) -> Unit,
    private val onResize: (cols: Int, rows: Int) -> Unit,
) : TerminalSession(
    /* shellPath    = */ "/system/bin/sh",
    /* cwd          = */ "/",
    /* args         = */ emptyArray(),
    /* env          = */ emptyArray(),
    /* transcriptRows = */ transcriptRowsCount,
    /* client       = */ client,
) {
    init {
        // isRunning() returns `mShellPid != -1`. Set a positive sentinel so the
        // session looks "alive" to TerminalView — we never actually fork a subprocess.
        mShellPid = Int.MAX_VALUE
    }

    /** Create the emulator without forking a subprocess (no JNI). */
    override fun initializeEmulator(columns: Int, rows: Int) {
        mEmulator = TerminalEmulator(this, columns, rows, transcriptRowsCount, mClient)
    }

    /**
     * Resize the emulator directly and notify the caller so it can forward
     * SIGWINCH over SSH. We never call the upstream [JNI.setPtyWindowSize].
     */
    override fun updateSize(columns: Int, rows: Int) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows)
        } else {
            mEmulator.resize(columns, rows)
        }
        onResize(columns, rows)
    }

    /**
     * Called by [TerminalView] for every keystroke / IME commit / paste.
     * Also called transitively by `writeCodePoint` and the String-form `write`.
     */
    override fun write(data: ByteArray?, offset: Int, count: Int) {
        if (data == null || count <= 0) return
        val snapshot = ByteArray(count).also { System.arraycopy(data, offset, it, 0, count) }
        onUserInput(snapshot)
    }

    /**
     * Upstream kills the shell subprocess via `Os.kill(mShellPid, SIGKILL)`. We have
     * no subprocess (mShellPid is a bogus sentinel) so override as no-op to avoid a
     * spurious kill syscall.
     */
    override fun finishIfRunning() {
        // no-op — there is no subprocess.
    }

    /**
     * Feed bytes directly into the emulator, bypassing the handler queue.
     * Must be called on the main thread.
     */
    fun appendDirect(data: ByteArray) {
        if (data.isEmpty() || mEmulator == null) return
        mEmulator.append(data, data.size)
        notifyScreenUpdate()
    }

    /**
     * Feed SSH bytes into the emulator. Safe to call from any thread.
     * Posts to the main thread via Handler — the IO thread never blocks.
     */
    fun receiveSshBytes(data: ByteArray) {
        if (data.isEmpty()) return
        if (mEmulator == null) return
        mMainThreadHandler.post {
            if (mEmulator != null) {
                mEmulator.append(data, data.size)
                notifyScreenUpdate()
            }
        }
    }

    /**
     * Read the bottom [rowCount] rows of the rendered screen as a snapshot with
     * per-cell foreground colors. MUST be called on the Android main thread —
     * the emulator is not thread-safe and all writes are funneled through
     * [mMainThreadHandler].
     *
     * Used by [com.clauderemote.session.ScreenStateClassifier] to distinguish
     * Claude's dark-red "working indicator" from idle chat content.
     */
    fun readBottomRowsSnapshot(rowCount: Int = 8): ScreenStateSnapshot? {
        val emu = mEmulator ?: return null
        val buffer = emu.screen ?: return null
        val cols = emu.mColumns
        val rows = emu.mRows
        val startRow = (rows - rowCount).coerceAtLeast(0)
        val result = ArrayList<RowSnapshot>(rows - startRow)
        for (r in startRow until rows) {
            val internal = buffer.externalToInternalRow(r)
            val termRow = buffer.mLines[internal]
            val text = CharArray(cols) { ' ' }
            val reds = BooleanArray(cols)
            if (termRow != null) {
                for (c in 0 until cols) {
                    val idx = termRow.findStartOfColumn(c)
                    if (idx < termRow.mText.size) text[c] = termRow.mText[idx]
                    val fg = TextStyle.decodeForeColor(termRow.getStyle(c))
                    reds[c] = isReddishFg(fg)
                }
            }
            // null row (unallocated) → keep the all-space / all-false defaults, preserve alignment
            result.add(RowSnapshot(String(text), reds))
        }
        return ScreenStateSnapshot(result, cols)
    }

    /**
     * Is [fg] a "reddish" foreground? Covers:
     *  - ANSI palette indices 1 (red) and 9 (bright red)
     *  - Common 256-color red indices Claude Code may use
     *  - 24-bit truecolor with dominant red channel
     *
     * For 24-bit, `fg` is encoded as `0xff000000 | RGB` per [TextStyle.decodeForeColor].
     */
    private fun isReddishFg(fg: Int): Boolean {
        // Truecolor (high byte 0xff) → decode RGB
        if ((fg.toLong() and 0xff000000L) == 0xff000000L) {
            val r = (fg ushr 16) and 0xff
            val g = (fg ushr 8) and 0xff
            val b = fg and 0xff
            return r >= 120 && r > g + 40 && r > b + 40
        }
        // Indexed palette
        return when (fg) {
            1, 9 -> true                           // ANSI red + bright red
            88, 124, 160, 196, 197, 203, 204 -> true // common 256-color reds
            else -> false
        }
    }
}
