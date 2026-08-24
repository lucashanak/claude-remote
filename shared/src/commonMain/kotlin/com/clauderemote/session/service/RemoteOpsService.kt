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
                        // `-u`: a folder with diacritics comes back '_'-mangled on an
                        // exec channel (no locale) — see the note in
                        // SessionOrchestrator.serverHasOtherLiveSession.
                        "tmux -u display-message -p -t '=$escaped:' '#{pane_current_path}' 2>/dev/null " +
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
                // stat, NOT lstat: lstat reports the symlink's own size (a few
                // bytes) while get() follows it, which let a link to a huge file
                // slip past this guard and then blow up the heap.
                val attrs = sftp.stat(resolved)
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
        } catch (e: OutOfMemoryError) {
            // The whole file is buffered in memory, so a large download can
            // exhaust the app heap. Report it like any other failure instead of
            // letting it tear the process down.
            FileLogger.error(TAG, "Download ran out of memory: $remotePath", RuntimeException(e))
            SessionOrchestrator.DOWNLOAD_TOO_LARGE
        } catch (e: Exception) {
            FileLogger.error(TAG, "Download file failed: $remotePath", e)
            null
        }
    }

    /**
     * Which of [remotePaths] are regular files we could actually download.
     * Used to confirm the file paths Claude mentions in chat before offering
     * them as links, so prose never turns into a bogus download.
     *
     * One SFTP channel is reused for the whole batch, and paths go over the
     * wire as data — no shell quoting, so names with spaces or apostrophes
     * are handled like any other.
     */
    suspend fun statFiles(sessionId: String, remotePaths: List<String>): Set<String> =
        withContext(Dispatchers.IO) {
            if (remotePaths.isEmpty()) return@withContext emptySet()
            try {
                val conn = registry.ssh(sessionId) ?: return@withContext emptySet()
                val sshSession = conn.getSession() ?: return@withContext emptySet()
                val sftp = sshSession.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
                sftp.connect(5000)
                try {
                    val home = sftp.home ?: "~"
                    remotePaths.filterTo(mutableSetOf()) { path ->
                        val resolved = when {
                            path == "~" -> home
                            path.startsWith("~/") -> home + path.substring(1)
                            path.startsWith("/") -> path
                            else -> "$home/$path"
                        }
                        try {
                            // stat() follows symlinks, so a link to a real file counts.
                            !sftp.stat(resolved).isDir
                        } catch (_: Exception) {
                            false // no such file — the common case for prose
                        }
                    }
                } finally {
                    sftp.disconnect()
                }
            } catch (e: Exception) {
                FileLogger.error(TAG, "statFiles failed for $sessionId", e)
                emptySet()
            }
        }

    companion object {
        // Raised from 50 MB on request. The download is buffered whole in memory,
        // so this rides on androidApp's largeHeap; see the OutOfMemoryError catch.
        private const val DOWNLOAD_SIZE_LIMIT = 200L * 1024 * 1024 // 200 MB
    }
}
