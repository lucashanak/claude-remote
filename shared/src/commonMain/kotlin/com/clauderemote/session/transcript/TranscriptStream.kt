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
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Streams a Claude Code transcript file (`~/.claude/projects/<enc>/<uuid>.jsonl`)
 * over SSH using `tail -n +1 -F`, parses each line incrementally, and exposes
 * the accumulated entries via a [StateFlow].
 *
 * One instance per active session; reconnects/restarts on transcript file
 * changes via [restart] when the Claude Code session UUID rotates.
 */
class TranscriptStream(
    private val server: SshServer,
    private val cwd: String,
    private val parentScope: CoroutineScope,
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
        // Resolve the encoded cwd server-side: Claude Code keys
        // ~/.claude/projects/<enc>/ by `pwd | sed 's|/|-|g'` of the absolute
        // path, so we must shell-expand (handles `~`, relative paths) and
        // then dash-replace there — doing it client-side breaks for any
        // folder that needs shell expansion.
        val safeFolder = cwd.replace("'", "'\\''")
        val safeUuid = uuid.replace("'", "'\\''")
        val cmd = buildString {
            // Set FOLDER from a single-quoted literal (safe against shell
            // metacharacters), then do a portable tilde expansion before cd.
            append("F='").append(safeFolder).append("'; ")
            append("case \"\$F\" in \"~\"*) F=\"\$HOME\${F#\"~\"}\";; esac; ")
            append("ENC=\$(cd \"\$F\" 2>/dev/null && pwd | sed 's|/|-|g'); ")
            append("[ -z \"\$ENC\" ] && exit 0; ")
            append("DIR=\"\$HOME/.claude/projects/\$ENC\"; ")
            // Tail strictly the UUID-targeted jsonl. NEVER fall back to
            // "newest *.jsonl in this folder" — two tabs that share a
            // cwd (e.g. "second" and "third" aliases of the same
            // server/folder) would both pick the same newest file and
            // cross-pollute. Rotation across /clear and /resume is
            // handled at the SessionOrchestrator layer: the 60 s
            // reconcile loop polls the server's sessions.json AND, when
            // that's empty, the per-pid ~/.claude/sessions/<pid>.json
            // probe directly. Either path updates tab.claudeSessionId
            // and triggers notifyClaudeSessionIdChanged, which restarts
            // this stream against the correct uuid.
            //
            // tail -F waits patiently for the file to appear, so a
            // freshly-launched session with no transcript yet hangs on
            // "Waiting for transcript…" until claude writes the first
            // line — that's the correct behaviour, not a bug.
            append("tail -n ").append(INITIAL_LINES)
            append(" -F \"\$DIR/").append(safeUuid).append(".jsonl\" 2>/dev/null")
        }
        var attempt = 0
        while (scope.isActive) {
            attempt++
            var sawData = false
            try {
                // Use a dedicated short-lived SSH session for the tail. NOTE: an
                // earlier optimization reused the main terminal session (one exec
                // channel) to save connections, but an exec channel opened on the
                // shell-bearing main session never delivered output — the tail hung
                // forever at "connecting…" (confirmed via the on-screen status).
                // A dedicated session reliably delivers.
                _status.value = "connecting…"
                sawData = SshSessionHelper.withSession(server, timeout = 15_000) { sess ->
                    streamFromSession(sess, cmd)
                }
                // Only treat the connection as healthy (reset backoff) if it
                // actually delivered transcript data. A folder that doesn't
                // resolve (ENC empty → `exit 0`) or a missing file returns
                // cleanly with zero lines — without this guard that would
                // reset attempt=0 and busy-reconnect with no backoff.
                if (sawData) {
                    attempt = 0
                } else if (_entries.value.isEmpty()) {
                    // Connected fine but the file produced nothing (no transcript
                    // yet, or wrong folder/uuid) — say so instead of a blank wait.
                    _status.value = "connected, no transcript data yet"
                }
            } catch (t: Throwable) {
                val msg = t.message?.take(80) ?: t::class.simpleName ?: "unknown error"
                FileLogger.log(TAG, "tail stream error (attempt $attempt): ${t.message}")
                if (_entries.value.isEmpty()) _status.value = "retry $attempt — $msg"
            }
            if (!scope.isActive) break
            val backoff = (1_000L * attempt).coerceAtMost(15_000L)
            delay(backoff)
        }
    }

    /**
     * Run the `tail -F` command on [sess] (the main terminal session when
     * available, otherwise a dedicated one) and pump parsed entries into the
     * flow. Closes ONLY the exec channel, never [sess] — the caller / SshManager
     * owns the session lifecycle. Returns true if any transcript data was read.
     */
    private suspend fun streamFromSession(sess: com.jcraft.jsch.Session, cmd: String): Boolean {
        var sawData = false
        val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
        ch.setCommand(cmd)
        ch.inputStream = null
        val inStream = ch.inputStream
        withContext(Dispatchers.IO) { ch.connect(10_000) }
        try {
            val reader = BufferedReader(InputStreamReader(inStream, Charsets.UTF_8))
            while (scope.isActive && !ch.isClosed) {
                // Let read errors (broken pipe, channel reset) propagate to the
                // outer catch so they go through the backoff + logging path. The
                // old `catch { null }` collapsed errors into clean-EOF, which
                // reset attempt=0 → delay(0) → tight reconnect loop.
                val first = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                // Coalesce a burst — the 2 000-line replay on (re)connect and
                // rapid streaming — into ONE flow emission instead of one per
                // line. Each emission is a full list copy plus ~4 O(n) recomputes
                // in the Compose view, so per-line emission janks on long
                // sessions. reader.ready() drains only what's already buffered,
                // so a genuinely idle stream still emits promptly per line.
                val batch = ArrayList<String>()
                batch.add(first)
                while (batch.size < MAX_BATCH &&
                    withContext(Dispatchers.IO) { reader.ready() }
                ) {
                    val more = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                    batch.add(more)
                }
                // Partial lines (tail caught mid-write) fail parse cleanly and
                // are dropped by the parser.
                val newEntries = TranscriptParser.parseLines(batch.asSequence())
                if (newEntries.isNotEmpty()) {
                    sawData = true
                    _status.value = null
                    appendEntries(newEntries)
                }
                // Track context size from this batch's newest assistant usage.
                TranscriptParser.latestContextTokens(batch.asSequence())?.let {
                    _contextTokens.value = it
                }
            }
        } finally {
            try { ch.disconnect() } catch (_: Throwable) {}
        }
        return sawData
    }

    /**
     * Append a parsed batch, deduped by id. Dedup matters because the outer
     * retry loop reopens `tail -F` on reconnect, which re-emits the last
     * [INITIAL_LINES] lines — without this every reconnect would clone the
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
        // reconnect replay: `tail -n INITIAL_LINES -F` re-emits only the NEWEST
        // <= INITIAL_LINES (2000) lines, and INITIAL_LINES < MAX_ENTRIES (5000),
        // so every replayable entry is always within the retained visible
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
        // Max lines coalesced into a single flow emission during a burst.
        private const val MAX_BATCH = 500
    }
}
