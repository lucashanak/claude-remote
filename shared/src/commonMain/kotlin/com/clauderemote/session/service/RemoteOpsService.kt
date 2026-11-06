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
                        val serverId = registry.serverIdOf(sessionId)
                        val escaped = tmuxName.replace("'", "'\\''")
                        val key = if (up) "page-up" else "page-down"
                        // `=name:` — exact-match PANE target. `=` alone is rejected for a
                        // pane target ("can't find pane"), and plain `-t name` prefix-matches,
                        // which would scroll a DIFFERENT session's pane. Measured on tmux 3.5a.
                        val cmd = "tmux copy-mode -e -t '=$escaped:'; tmux send-keys -t '=$escaped:' -X $key"
                        if (serverId != null && registry.execActive(serverId)) {
                            registry.execRun(serverId, cmd, totalMs = 3_000)
                            return@withContext
                        }
                        val sshSession = registry.ssh(sessionId)?.getSession() ?: return@withContext
                        // The tab's own session: one channel counted where the shells are packed; no room = no scroll.
                        registry.withTabChannel(serverId ?: return@withContext, sshSession, "scroll") { sess ->
                            val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
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
     *
     * A transfer never takes the one reserve slot of a packed tab transport (V12). Without room it waits (holding
     * nothing, R3-1) and looks again every [ROOM_RETRY_MIN_MS]-[ROOM_RETRY_MAX_MS] ms until its deadline: a poll in
     * flight on 7 shells, another transfer, or an exec transport that is briefly down. It fails at once with
     * "No free SSH channel" only when no room can come ([ConnectionRegistry.transferMayFindRoom]: the exec transport
     * switched off or closed and 8 shells on the tab transports).
     */
    suspend fun uploadFile(sessionId: String, bytes: ByteArray, fileName: String): String {
        val deadline = System.currentTimeMillis() + TRANSFER_DEADLINE_MS
        var lastException: Exception? = null
        // The last try found the tab's transport without room for a channel (not a broken SSH): said so at the end.
        var lastNoRoom = false
        // ... and no room can come before the deadline: given up at once
        var noRoomCanCome = false

        val serverId = registry.serverIdOf(sessionId)
        val viaExec = serverId != null && registry.execActive(serverId)
        while (System.currentTimeMillis() < deadline) {
            val c = registry.ssh(sessionId)
            if (viaExec) {
                // Exec transport: the upload needs no shell of this tab (a parked tab can upload).
                try {
                    val remoteDir = "/tmp/claude-uploads"
                    val remotePath = "$remoteDir/${fileName.replace("'", "'\\''")}"
                    val res = registry.execRun(
                        serverId!!,
                        "mkdir -p '$remoteDir' && cat > '$remotePath'",
                        stdin = bytes,
                        totalMs = 20_000L + bytes.size / 150,
                        kind = ExecKind.SHORT,
                        transfer = true,
                    )
                    if (res != null) {
                        if (res.exitStatus != 0) throw java.io.IOException("Upload failed (exit ${res.exitStatus})")
                        FileLogger.log(TAG, "File uploaded: $remotePath (${bytes.size} bytes)")
                        return remotePath
                    }
                    // the tab is connected, so null = its server's tab transports have no room for a transfer
                    lastNoRoom = c != null && c.isConnected
                    if (lastNoRoom) {
                        if (!registry.transferMayFindRoom(serverId)) { noRoomCanCome = true; break }
                        kotlinx.coroutines.delay(roomRetryMs())
                        continue
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastException = e
                    FileLogger.error(TAG, "Upload exec failed for $sessionId: ${e.message}", e)
                }
            } else if (c != null && c.isConnected) {
                try {
                    val remoteDir = "/tmp/claude-uploads"
                    // The tab's own session: its channel counted where the shells are packed, off the reserve (a transfer);
                    // no room = look again, or fail at once with a readable error when none can come.
                    val sess = c.getSession()
                    val remotePath = if (serverId != null && sess != null) {
                        registry.withTransferChannel(serverId, sess, "upload") { c.uploadFile(bytes, remoteDir, fileName) }?.value
                            .also { lastNoRoom = it == null }
                    } else {
                        c.uploadFile(bytes, remoteDir, fileName)
                    }
                    if (remotePath != null) {
                        FileLogger.log(TAG, "File uploaded: $remotePath (${bytes.size} bytes)")
                        return remotePath
                    }
                    if (lastNoRoom) {
                        if (!registry.transferMayFindRoom(serverId!!, sess)) { noRoomCanCome = true; break }
                        kotlinx.coroutines.delay(roomRetryMs())
                        continue
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastException = e
                    lastNoRoom = false
                    FileLogger.error(TAG, "Upload exec failed for $sessionId: ${e.message}", e)
                    // Don't kill the connection — if transport is truly dead,
                    // the read loop will detect it via ServerAliveInterval and
                    // autoReconnect will handle recovery.
                }
            }
            kotlinx.coroutines.delay(1000)
        }
        if (noRoomCanCome) throw IllegalStateException(
            "No free SSH channel for $sessionId: the connection is full and the exec transport is off, reconnect the tab to upload")
        if (lastNoRoom) throw IllegalStateException(
            "No free SSH channel for $sessionId: the connection stayed full for ${TRANSFER_DEADLINE_MS / 1000} s, try again")
        throw lastException ?: IllegalStateException("SSH not ready for $sessionId (upload timeout)")
    }

    /**
     * Download a file from the remote server via SFTP.
     * Returns the file bytes, or null on failure.
     */
    suspend fun downloadFile(
        sessionId: String,
        remotePath: String,
        /**
         * Refuse anything bigger BEFORE transferring it. Inline images pass a
         * small cap: checking the size after the download meant a 150 MB file
         * crossed the link in full only to be rejected — and again on every
         * scroll back to it.
         */
        maxBytes: Long = DOWNLOAD_SIZE_LIMIT,
        /** Abort a transfer that runs longer than this (a FIFO, a dead link). */
        timeoutMs: Long = 0,
    ): ByteArray? = withContext(Dispatchers.IO) {
        // Without room the download looks again until this deadline, holding nothing meanwhile (R3-1), unless no room can
        // come ([ConnectionRegistry.transferMayFindRoom]); the transfer itself gets what is left of [timeoutMs].
        val deadline = System.currentTimeMillis() + if (timeoutMs > 0) timeoutMs else TRANSFER_DEADLINE_MS
        fun timeLeft() = if (timeoutMs > 0) (deadline - System.currentTimeMillis()).coerceAtLeast(1) else 0
        try {
            val serverId = registry.serverIdOf(sessionId)
            while (true) {
                if (serverId != null && registry.execActive(serverId)) {
                    // Exec transport: SFTP through the gate, no tab shell needed. A transfer: its fallback keeps off the
                    // reserve slot of a packed tab transport (V12), NOT_UP there = no room now.
                    try {
                        return@withContext registry.useChannel(serverId, ExecKind.SFTP, transfer = true) { ch ->
                            val sftp = ch as com.jcraft.jsch.ChannelSftp
                            sftp.connect(5000)
                            try { sftpDownload(sftp, remotePath, maxBytes, timeLeft()) } finally { sftp.disconnect() }
                        }
                    } catch (e: ExecUnavailable) {
                        if (e.reason != ExecUnavailableReason.NOT_UP || !registry.transferMayFindRoom(serverId) ||
                            System.currentTimeMillis() >= deadline) throw e
                    }
                } else {
                    val conn = registry.ssh(sessionId) ?: return@withContext null
                    val sshSession = conn.getSession() ?: return@withContext null
                    val download: suspend (com.jcraft.jsch.Session) -> ByteArray? = { s ->
                        val sftp = s.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
                        sftp.connect(5000)
                        try {
                            sftpDownload(sftp, remotePath, maxBytes, timeLeft())
                        } finally {
                            sftp.disconnect()
                        }
                    }
                    if (serverId == null) return@withContext download(sshSession)
                    // The tab's own session: the SFTP channel counted where the shells are packed, off the reserve (a
                    // transfer, V12); no room = look again, or no download when none can come.
                    registry.withTransferChannel(serverId, sshSession, "sftp-download", download)?.let { return@withContext it.value }
                    if (!registry.transferMayFindRoom(serverId, sshSession) || System.currentTimeMillis() >= deadline) return@withContext null
                }
                kotlinx.coroutines.delay(roomRetryMs())
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (e: OutOfMemoryError) {
            // The whole file is buffered in memory, so a large download can
            // exhaust the app heap. Report it like any other failure instead of
            // letting it tear the process down.
            FileLogger.error(TAG, "Download ran out of memory: $remotePath", RuntimeException(e))
            SessionOrchestrator.DOWNLOAD_TOO_LARGE
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.error(TAG, "Download file failed: $remotePath", e)
            null
        }
    }

    /** One step of the wait for room of a transfer: jittered, so transfers waiting together do not retry in lockstep. */
    private fun roomRetryMs(): Long = kotlin.random.Random.nextLong(ROOM_RETRY_MIN_MS, ROOM_RETRY_MAX_MS + 1)

    /** The download body on an already connected SFTP channel (the caller disconnects it). Null = failure/aborted. */
    private fun sftpDownload(sftp: com.jcraft.jsch.ChannelSftp, remotePath: String, maxBytes: Long, timeoutMs: Long): ByteArray? {
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
        // Regular files only. A FIFO or a link into /dev or /proc stats
        // at size 0, passes any size guard, and then get() blocks
        // forever or streams without end — and with inline images a
        // path in Claude's text starts a fetch without anyone tapping.
        if (!attrs.isReg) {
            FileLogger.log(TAG, "Download refused: $resolved is not a regular file")
            return null
        }
        if (attrs.size > maxBytes) {
            FileLogger.log(TAG, "Download refused: $resolved is ${attrs.size} bytes (limit $maxBytes)")
            return SessionOrchestrator.DOWNLOAD_TOO_LARGE
        }
        val out = java.io.ByteArrayOutputStream(attrs.size.coerceIn(0, maxBytes).toInt())
        // The stat is a promise, not a guarantee: the file can grow
        // while it is read. The monitor enforces the cap (and the time
        // limit) on the bytes actually arriving, by aborting the get.
        val started = System.currentTimeMillis()
        var received = 0L
        var aborted: Any? = null
        val monitor = object : com.jcraft.jsch.SftpProgressMonitor {
            override fun init(op: Int, src: String?, dest: String?, max: Long) {}
            override fun count(bytes: Long): Boolean {
                received += bytes
                if (received > maxBytes) { aborted = SessionOrchestrator.DOWNLOAD_TOO_LARGE; return false }
                if (timeoutMs > 0 && System.currentTimeMillis() - started > timeoutMs) {
                    aborted = "timeout"; return false
                }
                return true
            }
            override fun end() {}
        }
        sftp.get(resolved, out, monitor)
        return when (aborted) {
            null -> out.toByteArray()
            SessionOrchestrator.DOWNLOAD_TOO_LARGE -> SessionOrchestrator.DOWNLOAD_TOO_LARGE
            else -> {
                FileLogger.log(TAG, "Download aborted after ${timeoutMs}ms: $resolved")
                null
            }
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
                val serverId = registry.serverIdOf(sessionId)
                if (serverId != null && registry.execActive(serverId)) {
                    return@withContext registry.useChannel(serverId, ExecKind.SFTP) { ch ->
                        val sftp = ch as com.jcraft.jsch.ChannelSftp
                        sftp.connect(5000)
                        try { sftpRegularFiles(sftp, remotePaths) } finally { sftp.disconnect() }
                    }
                }
                val conn = registry.ssh(sessionId) ?: return@withContext emptySet()
                val sshSession = conn.getSession() ?: return@withContext emptySet()
                val stat: suspend (com.jcraft.jsch.Session) -> Set<String> = { s ->
                    val sftp = s.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
                    sftp.connect(5000)
                    try {
                        sftpRegularFiles(sftp, remotePaths)
                    } finally {
                        sftp.disconnect()
                    }
                }
                // The tab's own session: counted where the shells are packed; no room = nothing confirmed this time.
                if (serverId != null) registry.withTabChannel(serverId, sshSession, "sftp-stat", stat)?.value ?: emptySet()
                else stat(sshSession)
            } catch (e: Exception) {
                FileLogger.error(TAG, "statFiles failed for $sessionId", e)
                emptySet()
            }
        }

    /** [remotePaths] that stat as non-directories on an already connected SFTP channel. */
    private fun sftpRegularFiles(sftp: com.jcraft.jsch.ChannelSftp, remotePaths: List<String>): Set<String> {
        val home = sftp.home ?: "~"
        return remotePaths.filterTo(mutableSetOf()) { path ->
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
    }

    companion object {
        // Raised from 50 MB on request. The download is buffered whole in memory,
        // so this rides on androidApp's largeHeap; see the OutOfMemoryError catch.
        private const val DOWNLOAD_SIZE_LIMIT = 200L * 1024 * 1024 // 200 MB
        /** How long an upload (and a download without its own time limit) tries, waiting for room included. */
        private const val TRANSFER_DEADLINE_MS = 20_000L
        /** The step of a transfer's wait for room (R3-1), jittered between these. */
        private const val ROOM_RETRY_MIN_MS = 300L
        private const val ROOM_RETRY_MAX_MS = 1_000L
    }
}
