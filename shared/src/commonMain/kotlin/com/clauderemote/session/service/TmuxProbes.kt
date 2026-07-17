package com.clauderemote.session.service

import com.clauderemote.connection.SshManager
import com.clauderemote.session.TabManager
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "SessionOrchestrator"

/**
 * Low-level tmux/shell probe + terminal-redraw primitives. Extracted verbatim
 * from SessionOrchestrator: the timing (kickRedraw's shrink-then-restore resize
 * with delay(80), the 1500ms exec timeouts, waitForShellPrompt's poll loop),
 * ordering and fail-open/fail-closed semantics are unchanged — a pure move so
 * the public API and runtime behavior stay identical.
 */
internal class TmuxProbes(
    private val scope: CoroutineScope,
    private val registry: ConnectionRegistry,
    private val tabManager: TabManager,
    private val terminalIO: TerminalIOService,
) {
    /**
     * Nudge tmux to redraw a session's pane at [cols]x[rows] without disturbing
     * the input line. Preferred path: `tmux refresh-client` over an exec channel,
     * which repaints every attached client to the pane's current server-side
     * geometry. The SIGWINCH toggle is kept only as a fallback for when the
     * exec channel fails (connection mid-reconnect, tmux probe error).
     *
     * Called by the platform after it has laid out the terminal for the new
     * session at the current dimensions. Safe to call from the UI thread —
     * the SSH round-trip runs on [scope].
     */
    fun kickRedraw(sessionId: String, cols: Int, rows: Int) {
        val conn = registry.ssh(sessionId) ?: return
        if (cols <= 1 || rows <= 0) return
        FileLogger.log("TermGeom", "kickRedraw $sessionId requested ${cols}x${rows}")
        // Sync pty geometry to the current view first (no-op if unchanged) —
        // each session's pty keeps the size from the last time *it* was
        // active, which may be stale after switching between sessions.
        conn.resize(cols, rows)
        val tmuxName = tabManager.getTab(sessionId)?.tmuxSessionName
        scope.launch {
            if (tmuxName != null) probeTmuxGeometry(conn, tmuxName, sessionId, cols, rows)
            val refreshed = tmuxName != null && refreshTmuxClient(conn, tmuxName)
            if (!refreshed) {
                // Fallback: SIGWINCH toggle. Shrink COLS, not ROWS — row
                // shrink pushes the tmux status line up a cell during the
                // kick and its bytes leak into scrollback as a stray
                // status-line artifact mid-history.
                conn.resize(cols - 1, rows)
                kotlinx.coroutines.delay(80)
                conn.resize(cols, rows)
            }
        }
    }

    /**
     * Run `tmux refresh-client` for every client attached to [tmuxName] via a
     * short-lived exec channel. Returns true only if at least one client was
     * actually refreshed (the command echoes OK per successful refresh).
     */
    private suspend fun refreshTmuxClient(conn: SshManager, tmuxName: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val sshSession = conn.getSession() ?: return@withContext false
                val escaped = tmuxName.replace("'", "'\\''")
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(
                    "tmux list-clients -t '$escaped' -F '#{client_name}' 2>/dev/null" +
                        " | while IFS= read -r c; do tmux refresh-client -t \"\$c\" 2>/dev/null && echo OK; done"
                )
                ch.inputStream = null
                val input = ch.inputStream
                ch.connect(1500) // keep snappy on cell links; fallback toggle covers failure
                val out = input.bufferedReader().readText()
                ch.disconnect()
                out.contains("OK")
            } catch (e: Exception) {
                FileLogger.log(TAG, "refresh-client failed for $tmuxName: ${e.message}")
                false
            }
        }

    /**
     * Read-only diagnostic probe (TermGeom): logs tmux's actual pane geometry
     * and the window-size option for [tmuxName], so it can be compared against
     * the [cols]x[rows] the client requested in kickRedraw. Does not change any
     * tmux state — additional telemetry alongside the existing refresh path.
     */
    private suspend fun probeTmuxGeometry(
        conn: SshManager,
        tmuxName: String,
        sessionId: String,
        cols: Int,
        rows: Int,
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val sshSession = conn.getSession() ?: return@withContext
            val escaped = tmuxName.replace("'", "'\\''")
            val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
            ch.setCommand(
                "tmux display-message -p -t '$escaped' " +
                    "'#{pane_width}x#{pane_height} win=#{window_width}x#{window_height} ws=#{?window-size,#{window-size},?}' 2>/dev/null"
            )
            ch.inputStream = null
            val input = ch.inputStream
            ch.connect(1500)
            val out = input.bufferedReader().readText().trim()
            ch.disconnect()
            FileLogger.log("TermGeom", "kickRedraw $sessionId tmux pane=$out (requested ${cols}x${rows})")
        } catch (e: Exception) {
            FileLogger.log("TermGeom", "kickRedraw $sessionId tmux probe failed: ${e.message}")
        }
    }

    /**
     * Synchronous tmux session existence probe via SSH exec channel.
     * Returns true if `tmux has-session -t <name>` exits 0. Returns true on
     * exec failure too (fail-open: fall through to attach which will create
     * via -A if needed — old behavior).
     */
    fun probeTmuxSession(sshManager: SshManager, sessionName: String): Boolean {
        return try {
            val sshSession = sshManager.getSession() ?: return true
            val escaped = sessionName.replace("'", "'\\''")
            val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
            ch.setCommand("tmux has-session -t '$escaped' 2>/dev/null && echo YES || echo NO")
            ch.inputStream = null
            val input = ch.inputStream
            ch.connect(1500) // fail-open probe — keep snappy on cell links
            val out = input.bufferedReader().readText().trim()
            ch.disconnect()
            out.endsWith("YES")
        } catch (e: Exception) {
            FileLogger.error(TAG, "Tmux probe failed for $sessionName: ${e.message}", e)
            true // fail-open
        }
    }

    /**
     * Probe whether a Claude Code transcript file exists for the given UUID
     * in the encoded form of [folder]. Used to decide whether `--resume <uuid>`
     * will succeed or whether we need to launch fresh with `--session-id <uuid>`.
     *
     * Encoding: `~` is expanded to `$HOME`, relative folders are anchored at
     * `$HOME`, then every `/` becomes `-` (matches Claude Code's on-disk layout
     * under `~/.claude/projects/`).
     *
     * Fail-closed (returns false on probe error) so we don't try a `--resume`
     * that we can't verify — it's safer to start fresh than to crash with
     * "No conversation found".
     */
    fun probeTranscriptExists(sshManager: SshManager, folder: String, uuid: String): Boolean {
        return try {
            val sshSession = sshManager.getSession() ?: return false
            val escapedFolder = folder.replace("'", "'\\''")
            val cmd = """
                F='$escapedFolder'
                E="${'$'}{F/#~/${'$'}HOME}"
                case "${'$'}E" in /*) ;; *) E="${'$'}HOME/${'$'}E";; esac
                # UUID is globally unique — a transcript matching it anywhere means
                # it exists, immune to the lossy cwd->dir encoding. Fall back to the
                # corrected encoding (every non-alphanumeric -> '-') for a not-yet-
                # globbable path.
                ENC=${'$'}(echo "${'$'}E" | sed 's|[^a-zA-Z0-9]|-|g')
                if ls "${'$'}HOME/.claude/projects/"*/"$uuid.jsonl" >/dev/null 2>&1 || [ -f "${'$'}HOME/.claude/projects/${'$'}ENC/$uuid.jsonl" ]; then echo YES; else echo NO; fi
            """.trimIndent()
            val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
            ch.setCommand(cmd)
            ch.inputStream = null
            val input = ch.inputStream
            ch.connect(1500)
            val out = input.bufferedReader().readText().trim()
            ch.disconnect()
            out.endsWith("YES")
        } catch (e: Exception) {
            FileLogger.error(TAG, "Transcript probe failed for $uuid in $folder: ${e.message}", e)
            false
        }
    }

    /**
     * Wait for shell prompt by watching output buffer for prompt chars ($ # > %).
     * Returns as soon as prompt detected or after maxWait ms.
     */
    suspend fun waitForShellPrompt(sessionId: String, maxWait: Long) {
        val start = System.currentTimeMillis()
        val promptChars = setOf('$', '#', '>', '%')
        while (System.currentTimeMillis() - start < maxWait) {
            val lastLine = terminalIO.lastLine(sessionId)
            if (lastLine.isNotEmpty() && lastLine.any { it in promptChars }) return
            kotlinx.coroutines.delay(50)
        }
    }
}
