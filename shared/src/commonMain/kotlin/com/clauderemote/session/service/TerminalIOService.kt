package com.clauderemote.session.service

/**
 * Per-session terminal output ring buffer + pty size tracking.
 *
 * Extracted verbatim from SessionOrchestrator: the state, ordering, locking and
 * buffer-slicing arithmetic are unchanged — a pure move so the public API and
 * runtime behavior stay identical. [bufferLock] stays private to this service;
 * the orchestrator drives the lifecycle sites through the small init/remove/tail
 * methods below, each reproducing its original `synchronized(bufferLock)` block.
 */
internal class TerminalIOService(private val registry: ConnectionRegistry) {
    // Per-session terminal output buffer (ring buffer, capped at MAX_BUFFER)
    private val outputBuffers = mutableMapOf<String, StringBuilder>()
    private val bufferLock = Any()

    // Last known terminal dimensions per session — used to re-send SIGWINCH after reconnect
    private val terminalSizes = mutableMapOf<String, Pair<Int, Int>>()
    // The most recent ACTIVE-view terminal size. All sessions render in the same
    // on-screen emulator, so this is the right size for any session whose own
    // size isn't known yet (e.g. reconnected while backgrounded) — avoids the
    // jsch 80x24 default that leaves tmux panes not filling the window.
    @Volatile private var lastActiveViewSize: Pair<Int, Int>? = null

    fun append(sessionId: String, data: String) {
        synchronized(bufferLock) {
            val buf = outputBuffers[sessionId] ?: return
            buf.append(data)
            if (buf.length > MAX_BUFFER) {
                val tail = buf.substring(buf.length - MAX_BUFFER)
                buf.clear()
                buf.append(tail)
            }
        }
    }

    fun clearBuffer(sessionId: String) {
        synchronized(bufferLock) { outputBuffers[sessionId]?.clear() }
    }

    fun getBuffer(sessionId: String): String {
        synchronized(bufferLock) {
            val buf = outputBuffers[sessionId] ?: return ""
            val len = buf.length
            return if (len > 2048) buf.substring(len - 2048) else buf.toString()
        }
    }

    fun resize(sessionId: String, cols: Int, rows: Int) {
        terminalSizes[sessionId] = cols to rows
        // Remember the active-view size as a global fallback for sessions that
        // (re)connect while backgrounded. Guard against transient 0-size layout passes.
        if (cols > 1 && rows > 0) lastActiveViewSize = cols to rows
        registry.ssh(sessionId)?.resize(cols, rows)
    }

    /** Best-known pty size for [sessionId]: its own remembered size, else the last
     *  active-view size, else the classic 80x24 default. */
    fun effectiveSize(sessionId: String): Pair<Int, Int> =
        terminalSizes[sessionId] ?: lastActiveViewSize ?: (80 to 24)

    // ---- Lifecycle buffer sites (driven from the orchestrator) ----

    /** Launch + restore paths: (re)create an empty buffer for [sessionId]. */
    fun initBuffer(sessionId: String) {
        synchronized(bufferLock) { outputBuffers[sessionId] = StringBuilder() }
    }

    /** switchTab's tail read: last 2 KB of [id]'s buffer, or "". */
    fun bufferTail(id: String): String = synchronized(bufferLock) {
        val buf = outputBuffers[id] ?: return@synchronized ""
        val len = buf.length
        if (len > 2048) buf.substring(len - 2048) else buf.toString()
    }

    /** waitForShellPrompt's last-line peek: trailing 80 chars, trimmed, or "". */
    fun lastLine(sessionId: String): String = synchronized(bufferLock) {
        val buf = outputBuffers[sessionId]
        if (buf == null || buf.isEmpty()) ""
        else {
            val len = buf.length
            buf.substring(maxOf(0, len - 80)).trimEnd()
        }
    }

    /** Disconnect: drop this session's buffer. */
    fun removeBuffer(sessionId: String) {
        synchronized(bufferLock) { outputBuffers.remove(sessionId) }
    }

    /** disconnectAll: drop every buffer. */
    fun clearAllBuffers() {
        synchronized(bufferLock) { outputBuffers.clear() }
    }

    /** Disconnect: forget this session's remembered pty size. */
    fun clearSize(sessionId: String) {
        terminalSizes.remove(sessionId)
    }

    companion object {
        private const val MAX_BUFFER = 64 * 1024 // 64KB per session
    }
}
