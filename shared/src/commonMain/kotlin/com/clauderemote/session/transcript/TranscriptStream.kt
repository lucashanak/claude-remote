package com.clauderemote.session.transcript

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.SshServer
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Streams a Claude Code transcript file (`~/.claude/projects/<enc>/<uuid>.jsonl`)
 * by POLLING it over SSH with a one-shot incremental `tail` every few seconds,
 * parsing new lines and exposing the accumulated entries via a [StateFlow].
 *
 * Polling (a short exec that completes, read with readText) rather than a
 * long-lived `tail -F` channel: the latter did not deliver stdout on the
 * desktop JVM (hung at "reading…"), while the one-shot exec+readText pattern is
 * the same one readRealSessionId uses and works on every platform.
 *
 * One instance per active session; [start] restarts it against a new UUID when
 * the Claude Code session id rotates (/clear, /resume).
 */
class TranscriptStream(
    private val server: SshServer,
    private val cwd: String,
    private val parentScope: CoroutineScope,
    /**
     * The session's live MAIN terminal SSH session, if connected. The poll runs
     * a one-shot exec on it (no extra connection) — the SAME proven pattern as
     * readRealSessionId/fetchSessionsFromServer, which work on both Android and
     * the desktop JVM. (A long-lived `tail -F` + readLine loop did NOT deliver
     * stdout on the desktop JVM — it hung at "reading…".) Null ⇒ no live main
     * connection, so we fall back to a short-lived dedicated session per poll.
     */
    private val liveSession: () -> com.jcraft.jsch.Session? = { null },
) {
    private val _entries = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val entries: StateFlow<List<TranscriptEntry>> = _entries.asStateFlow()

    // Context size (tokens) of the latest assistant message. The orchestrator
    // turns this into the ctx-window % — derived from the transcript we already
    // stream rather than scraping the TUI statusline. Null until a message with
    // usage arrives.
    private val _contextTokens = MutableStateFlow<Long?>(null)
    val contextTokens: StateFlow<Long?> = _contextTokens.asStateFlow()

    // Human-readable diagnostic for the "Waiting for transcript…" state: what
    // the tail is doing / why it hasn't produced data yet. Null once data flows.
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val supervisor = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisor)
    private var streamJob: Job? = null
    private var currentUuid: String? = null
    // Persistent dedup set so appending a line is O(1) instead of rebuilding a
    // HashSet of every retained entry on each line. Single-writer: only the
    // active tail coroutine touches it, and start() cancel-and-joins the
    // previous tail before the next one runs, so no locking is needed.
    private val seenIds = HashSet<String>()
    // Set once stop() has been called. start() is invoked outside transcriptLock
    // by the orchestrator, so it can race a concurrent disconnectSession() that
    // stops + removes this stream. Without this flag a late start() would
    // resurrect a tail -F on a torn-down session and leak the SSH channel.
    private var closed = false

    @Synchronized
    fun start(claudeSessionUuid: String) {
        if (closed) return
        if (currentUuid == claudeSessionUuid && streamJob?.isActive == true) return
        val uuidChanged = currentUuid != claudeSessionUuid
        currentUuid = claudeSessionUuid
        // Cancel-and-join the previous tail in a new coroutine so the new one
        // never appends concurrently with the old one. Capture the old job
        // locally before reassignment.
        val previous = streamJob
        streamJob = scope.launch {
            previous?.cancelAndJoin()
            // Only wipe accumulated entries when the UUID actually changed
            // (i.e. the user /resume'd or /clear'd into a different JSONL).
            // Restarting the same UUID (e.g. SSH reconnect after a network
            // blip) must NOT reset entries: the dedup guard in the update
            // lambda already prevents duplicates, and blanking the list here
            // would cause a visible "flash to empty" every time the SSH
            // channel drops and reconnects.
            if (uuidChanged) {
                _entries.value = emptyList()
                seenIds.clear()
                _contextTokens.value = null
                _status.value = null
            }
            runTail(claudeSessionUuid)
        }
    }

    suspend fun stop() {
        synchronized(this) { closed = true }
        streamJob?.cancelAndJoin()
        streamJob = null
        currentUuid = null
    }

    private suspend fun runTail(uuid: String) {
        val safeFolder = cwd.replace("'", "'\\''")
        val safeUuid = uuid.replace("'", "'\\''")
        // byteOffset = how many bytes of the .jsonl we've already consumed.
        // The poll command echoes the file's current size as `__OFFSET__<n>`
        // so we resume from exactly there next time — only NEW bytes are sent
        // over the wire after the initial backlog (keeps idle traffic tiny).
        var byteOffset = 0L
        var attempt = 0
        while (scope.isActive) {
            attempt++
            try {
                _status.value = if (_entries.value.isEmpty()) "connecting…" else null
                // Prefer the main connection (no extra SSH connection, no
                // handshake per poll); fall back to a short-lived dedicated
                // session only when the tab has no live main connection.
                val shared = liveSession()?.takeIf { it.isConnected }
                val (lines, newOffset) = if (shared != null) {
                    pollOnce(shared, safeFolder, safeUuid, byteOffset)
                } else {
                    SshSessionHelper.withSession(server, timeout = 15_000) { sess ->
                        pollOnce(sess, safeFolder, safeUuid, byteOffset)
                    }
                }
                if (newOffset >= 0) byteOffset = newOffset
                if (lines.isNotEmpty()) {
                    val newEntries = TranscriptParser.parseLines(lines.asSequence())
                    if (newEntries.isNotEmpty()) {
                        _status.value = null
                        appendEntries(newEntries)
                    }
                    TranscriptParser.latestContextTokens(lines.asSequence())?.let {
                        _contextTokens.value = it
                    }
                }
                attempt = 0
                if (_entries.value.isEmpty()) {
                    // Connected & polled fine, but nothing parseable yet (no
                    // transcript written, or wrong folder/uuid).
                    _status.value = "connected, no transcript data yet"
                }
            } catch (t: Throwable) {
                val msg = t.message?.take(80) ?: t::class.simpleName ?: "unknown error"
                FileLogger.log(TAG, "transcript poll error (attempt $attempt): ${t.message}")
                if (_entries.value.isEmpty()) _status.value = "retry $attempt — $msg"
            }
            if (!scope.isActive) break
            // Steady poll cadence; back off only after errors.
            val wait = if (attempt > 1) (1_000L * attempt).coerceAtMost(10_000L)
                       else if (_entries.value.isEmpty()) 1_500L else POLL_MS
            delay(wait)
        }
    }

    /**
     * One-shot incremental read of the transcript file. Returns the new JSONL
     * lines (since [offset]) and the file's current byte size (the next
     * offset), or (empty, -1) when the file/folder can't be resolved yet.
     *
     * Uses a short exec that COMPLETES (channel closes → readText hits EOF) —
     * the same pattern as readRealSessionId, which works on the desktop JVM
     * where a long-lived `tail -F` channel did not deliver stdout.
     */
    private suspend fun pollOnce(
        sess: com.jcraft.jsch.Session,
        safeFolder: String,
        safeUuid: String,
        offset: Long,
    ): Pair<List<String>, Long> {
        if (_entries.value.isEmpty()) _status.value = "reading…"
        val cmd = buildString {
            append("F='").append(safeFolder).append("'; ")
            append("case \"\$F\" in \"~\"*) F=\"\$HOME\${F#\"~\"}\";; esac; ")
            append("ENC=\$(cd \"\$F\" 2>/dev/null && pwd | sed 's|/|-|g'); ")
            append("[ -z \"\$ENC\" ] && { echo __OFFSET__-1; exit 0; }; ")
            append("FILE=\"\$HOME/.claude/projects/\$ENC/").append(safeUuid).append(".jsonl\"; ")
            append("[ -f \"\$FILE\" ] || { echo __OFFSET__-1; exit 0; }; ")
            append("SZ=\$(wc -c < \"\$FILE\" 2>/dev/null || echo 0); ")
            // Initial (offset<=0) or file shrank (rotation) → last N lines;
            // otherwise only the bytes appended since the last poll. Offsets sit
            // on line boundaries (jsonl ends every line with \n), so appended
            // bytes are always whole lines.
            append("if [ ").append(offset).append(" -le 0 ] || [ \"\$SZ\" -lt ").append(offset).append(" ]; then ")
            append("tail -n ").append(INITIAL_LINES).append(" \"\$FILE\" 2>/dev/null; ")
            append("elif [ \"\$SZ\" -gt ").append(offset).append(" ]; then ")
            append("tail -c +\$(("); append(offset.toString()); append("+1)) \"\$FILE\" 2>/dev/null; fi; ")
            append("printf '\\n__OFFSET__%s\\n' \"\$SZ\"")
        }
        val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
        ch.setCommand(cmd)
        ch.inputStream = null
        val inStream = ch.inputStream
        val out = withContext(Dispatchers.IO) {
            ch.connect(10_000)
            inStream.bufferedReader(Charsets.UTF_8).readText()
        }
        try { ch.disconnect() } catch (_: Throwable) {}
        var newOffset = -1L
        val lines = ArrayList<String>()
        for (line in out.lineSequence()) {
            if (line.startsWith("__OFFSET__")) {
                line.removePrefix("__OFFSET__").trim().toLongOrNull()?.let { newOffset = it }
            } else if (line.isNotBlank()) {
                lines.add(line)
            }
        }
        return lines to newOffset
    }

    /**
     * Append a parsed batch, deduped by id. Dedup matters because a poll whose
     * byte offset got reset (first poll, or file rotation/shrink) re-reads the
     * last [INITIAL_LINES] lines — without this that re-read would clone the
     * backlog. The `seenIds.add` side effect runs exactly once here (outside
     * the StateFlow.update lambda, which can be retried), and seenIds is
     * bounded so a long-lived stream doesn't grow it without limit.
     */
    private fun appendEntries(newEntries: List<TranscriptEntry>) {
        val unique = newEntries.filter { seenIds.add(it.id) }
        if (unique.isEmpty()) return
        _entries.update { prev ->
            val combined = prev + unique
            if (combined.size > MAX_ENTRIES) {
                combined.subList(combined.size - MAX_ENTRIES, combined.size).toList()
            } else combined
        }
        // Keep seenIds from growing unbounded across a very long session by
        // pruning back to the currently-visible ids. This is safe against the
        // offset-reset re-read: a reset poll re-reads only the NEWEST
        // <= INITIAL_LINES (2000) lines, and INITIAL_LINES < MAX_ENTRIES (5000),
        // so every re-readable entry is always within the retained visible
        // window and stays deduped. Prune lazily (at 2x) to avoid rebuilding
        // the set on every batch.
        if (seenIds.size > MAX_ENTRIES * 2) {
            val retained = _entries.value.mapTo(HashSet(_entries.value.size)) { it.id }
            seenIds.retainAll(retained)
        }
    }

    companion object {
        private const val TAG = "TranscriptStream"
        // Cap initial backlog from tail to bound startup time + RAM.
        private const val INITIAL_LINES = 2000
        // Hard cap on entries held in memory; oldest get dropped when exceeded.
        private const val MAX_ENTRIES = 5000
        // Steady-state poll interval once the backlog has loaded. New content
        // only sends the bytes appended since the last poll, so this is cheap.
        private const val POLL_MS = 3_000L
    }
}
