package com.termux.terminal

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
     * Feed SSH bytes into the emulator. Safe to call from any thread — the
     * bytes are queued and drained on the main thread by the upstream
     * [android.os.Handler] machinery. Drops bytes if the emulator has not yet
     * been initialized (happens between session creation and first layout).
     */
    fun receiveSshBytes(data: ByteArray) {
        if (data.isEmpty()) return
        if (mEmulator == null) return // view not laid out yet; drop (tab-switch replay will re-feed)
        if (mProcessToTerminalIOQueue.write(data, 0, data.size)) {
            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT)
        }
    }

    companion object {
        // Mirrors the private constant TerminalSession.MSG_NEW_INPUT.
        private const val MSG_NEW_INPUT = 1
    }
}
