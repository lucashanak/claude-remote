package com.clauderemote.session.service

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.SshServer
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Preserve the exact log tag the moved bodies used while they lived in
// SessionOrchestrator, so device-log lines are byte-identical.
private const val TAG = "SessionOrchestrator"

/**
 * Remote operations that act on a live SSH connection: tmux copy-mode scrolling
 * and SFTP file upload/download. Extracted verbatim from SessionOrchestrator —
 * the per-session scroll mutex, the copy-mode scroll sequence, the upload retry
 * loop and the download size guard are unchanged, a pure move so the public API
 * and runtime behavior stay identical.
 */
internal class RemoteOpsService(
    private val scope: CoroutineScope,
    private val registry: ConnectionRegistry,
    private val tabManager: TabManager,
) {
    /**
     * Scroll the tmux copy-mode buffer up/down one page for the given session.
     * Page-down at the bottom of history auto-exits copy-mode (back to live).
     * Runs off the UI thread on the IO scope.
     */
    // Per-session mutex so rapid scroll taps queue rather than storm SSH MaxSessions.
    private val scrollMutexes = mutableMapOf<String, Mutex>()
    private fun scrollMutex(sessionId: String) =
        synchronized(scrollMutexes) { scrollMutexes.getOrPut(sessionId) { Mutex() } }

    fun tmuxScroll(sessionId: String, up: Boolean) {
        val tmuxName = tabManager.getTab(sessionId)?.tmuxSessionName ?: return
        scope.launch {
            scrollMutex(sessionId).withLock {
                try {
                    kotlinx.coroutines.withContext(Dispatchers.IO) {
                        val sshSession = registry.ssh(sessionId)?.getSession() ?: return@withContext
                        val escaped = tmuxName.replace("'", "'\\''")
                        val key = if (up) "page-up" else "page-down"
                        // `=name:` — exact-match PANE target. `=` alone is rejected for a
                        // pane target ("can't find pane"), and plain `-t name` prefix-matches,
                        // which would scroll a DIFFERENT session's pane. Measured on tmux 3.5a.
                        val cmd = "tmux copy-mode -e -t '=$escaped:'; tmux send-keys -t '=$escaped:' -X $key"
                        val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                        ch.setCommand(cmd)
                        ch.inputStream = null
                        val input = ch.inputStream
                        try {
                            ch.connect(1500)
                            input.bufferedReader().readText()
                        } finally {
                            ch.disconnect()
                        }
                    }
                } catch (e: Exception) {
                    FileLogger.error(TAG, "tmuxScroll failed for $sessionId: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Probe an EXISTING tmux session (by name) on [server] to determine
     * whether its first pane's working directory matches [cwd].
     *
     * Returns:
     *  - `null`  — no such tmux session (safe to create a new one)
     *  - `true`  — session exists AND its pane cwd matches [cwd] (same conversation, attach)
     *  - `false` — session exists with a DIFFERENT cwd (collision — do NOT kill it)
     *
     * Fail-open on SSH / exec errors: returns `null` so the caller falls through
     * to the normal resume path rather than blocking the user.
     */
    suspend fun tmuxPaneMatchesCwd(server: SshServer, tmuxName: String, cwd: String): Boolean? =
        withContext(Dispatchers.IO) {
            try {
                SshSessionHelper.withSession(server, timeout = 5000) { sess ->
                    val escaped = tmuxName.replace("'", "'\\''")
                    // `tmux has-session` first to avoid the display-message error noise
                    // when the session doesn't exist.
                    // Exact-match targets: `=name` for the session probe, `=name:` for the
                    // pane that display-message reads. Plain `-t` prefix-matches, so this
                    // used to report a DIFFERENT session's cwd. The has-session gate is
                    // what actually protects the pair — display-message alone exits 0 even
                    // on an unresolvable target (measured on tmux 3.5a).
                    val checkCmd = "tmux has-session -t '=$escaped' 2>/dev/null && " +
                        "tmux display-message -p -t '=$escaped:' '#{pane_current_path}' 2>/dev/null " +
                        "|| echo __NO_SESSION__"
                    val ch = sess.openChannel("exec") as ChannelExec
                    ch.setCommand(checkCmd)
                    ch.inputStream = null
                    val input = ch.inputStream
                    ch.connect(4000)
                    val out = try {
                        input.bufferedReader().readText().trim()
                    } finally {
                        try { ch.disconnect() } catch (_: Throwable) {}
                    }
                    when {
                        out == "__NO_SESSION__" || out.isEmpty() -> null
                        else -> {
                            // Normalise both paths: strip trailing slash, expand leading ~
                            val panePath = out.trimEnd('/')
                            val histPath = cwd.trimEnd('/')
                            panePath == histPath
                        }
                    }
                }
            } catch (e: Exception) {
                FileLogger.error(TAG, "tmuxPaneMatchesCwd probe failed for $tmuxName: ${e.message}", e)
                null // fail-open
            }
        }

    /**
     * Upload a file to the remote server for the given session.
     * Returns the remote path of the uploaded file.
     *
     * If autoReconnect is already running (SAF picker killed the socket),
     * waits for it to finish.  Never kills the connection itself — that
     * was causing a cascade of 3 failed reconnects.
     */
    suspend fun uploadFile(sessionId: String, bytes: ByteArray, fileName: String): String {
        val deadline = System.currentTimeMillis() + 20_000L
        var lastException: Exception? = null

        while (System.currentTimeMillis() < deadline) {
            val c = registry.ssh(sessionId)
            if (c != null && c.isConnected) {
                try {
                    val remoteDir = "/tmp/claude-uploads"
                    val remotePath = c.uploadFile(bytes, remoteDir, fileName)
                    FileLogger.log(TAG, "File uploaded: $remotePath (${bytes.size} bytes)")
                    return remotePath
                } catch (e: Exception) {
                    lastException = e
                    FileLogger.error(TAG, "Upload exec failed for $sessionId: ${e.message}", e)
                    // Don't kill the connection — if transport is truly dead,
                    // the read loop will detect it via ServerAliveInterval and
                    // autoReconnect will handle recovery.
                }
            }
            kotlinx.coroutines.delay(1000)
        }
        throw lastException ?: IllegalStateException("SSH not ready for $sessionId (upload timeout)")
    }

    /**
     * Download a file from the remote server via SFTP.
     * Returns the file bytes, or null on failure.
     */
    suspend fun downloadFile(sessionId: String, remotePath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val conn = registry.ssh(sessionId) ?: return@withContext null
            val sshSession = conn.getSession() ?: return@withContext null
            val sftp = sshSession.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
            sftp.connect(5000)
            try {
                val home = sftp.home ?: "~"
                val resolved = when {
                    remotePath == "~"              -> home
                    remotePath.startsWith("~/")    -> home + remotePath.substring(1)
                    remotePath.startsWith("/")     -> remotePath
                    else                           -> "$home/$remotePath"
                }
                val attrs = sftp.lstat(resolved)
                if (attrs.size > DOWNLOAD_SIZE_LIMIT) {
                    FileLogger.log(TAG, "Download refused: $resolved is ${attrs.size} bytes (limit $DOWNLOAD_SIZE_LIMIT)")
                    return@withContext SessionOrchestrator.DOWNLOAD_TOO_LARGE
                }
                val out = java.io.ByteArrayOutputStream()
                sftp.get(resolved, out)
                out.toByteArray()
            } finally {
                sftp.disconnect()
            }
        } catch (e: Exception) {
            FileLogger.error(TAG, "Download file failed: $remotePath", e)
            null
        }
    }

    companion object {
        private const val DOWNLOAD_SIZE_LIMIT = 50L * 1024 * 1024 // 50 MB
    }
}
