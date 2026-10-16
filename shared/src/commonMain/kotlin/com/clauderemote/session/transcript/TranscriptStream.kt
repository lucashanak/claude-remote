package com.clauderemote.session.transcript

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.SshServer
import com.clauderemote.util.FileLogger
import com.clauderemote.util.isoToEpochMillis
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /**
     * True when the app is backgrounded. While backgrounded the poll cadence
     * drops from [POLL_MS] (3s) to [BG_POLL_MS] (30s): a backgrounded session
     * the user isn't looking at doesn't need a 3s SSH exec per session, and at
     * ~13 sessions that 3s poll was the single biggest battery/radio drain.
     */
    private val isBackground: () -> Boolean = { false },
    /**
     * True when this session is the ACTIVE tab. Only the visible transcript
     * needs the 3 s cadence; the other N−1 sessions drop to
     * [INACTIVE_POLL_MS] (15 s) even in the foreground — at 21 sessions that
     * cuts ~420 exec opens/min to ~100. Safe for notifications: the Stop-hook
     * path forces an immediate [pollNow] before firing, so the body never
     * depends on the periodic cadence.
     */
    private val isActiveTab: () -> Boolean = { true },
    /**
     * True while the per-server stream daemon is pushing this transcript's
     * deltas over its single channel (see SessionOrchestrator's streamd).
     * The poll loop then stretches to [DAEMON_SAFETY_POLL_MS] — a pure
     * offset-integrity backstop — instead of doing the transport work itself.
     */
    private val daemonActive: () -> Boolean = { false },
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
    // the tail is doing / why it hasn't produced data yet. Also carries a
    // TRANSIENT failure note once entries exist — a poll/stream that starts
    // failing mid-conversation used to be completely invisible (the flag was
    // only ever written while the entry list was empty), so a session could sit
    // minutes behind with the UI looking perfectly healthy. Cleared by the next
    // successful poll.
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    // True once we know the conversation shown is NOT the whole conversation:
    // either the first poll came back with a full `tail -n INITIAL_LINES`
    // backlog (so older lines exist in the file we never read), or
    // appendEntries trimmed the head at MAX_ENTRIES. The UI renders a marker
    // from this — silently starting mid-conversation is the thing to avoid.
    private val _historyTruncated = MutableStateFlow(false)
    val historyTruncated: StateFlow<Boolean> = _historyTruncated.asStateFlow()

    private val supervisor = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisor)
    private var streamJob: Job? = null
    // @Volatile: read by pushLines (daemon reader coroutine) and
    // currentOffset() outside the start()/stop() monitor.
    @Volatile private var currentUuid: String? = null
    // Persistent dedup set so appending a line is O(1) instead of rebuilding a
    // HashSet of every retained entry on each line. Single-writer: only the
    // active tail coroutine touches it, and start() cancel-and-joins the
    // previous tail before the next one runs, so no locking is needed.
    private val seenIds = HashSet<String>()
    // How many bytes of the .jsonl we've already consumed. Shared between the
    // steady tail loop, pollNow() and pushLines(); all advance it under
    // [pollMutex] so no path double-reads or races the offset. @Volatile only
    // for the approximate read in [currentOffset].
    @Volatile private var byteOffset = 0L
    // Which UUID [byteOffset] belongs to. During a rotation (/clear, /resume)
    // currentUuid is already the NEW uuid while byteOffset still counts the
    // OLD file until runTail's reset runs — handing that stale offset to the
    // stream daemon made it skip the new file's head (permanent gap on
    // /resume into a larger conversation). Seeded together with the reset.
    @Volatile private var offsetUuid: String? = null
    // When the stream daemon last delivered a batch for THIS session (epoch ms,
    // 0 = never). daemonActive() is per-SERVER but gates a per-SESSION poll
    // cadence, so a single watch that never took (lost control line, daemon
    // restarted mid-registration) degraded just that session to the 60 s
    // backstop with nothing to show for it. Paired with [daemonMisses] below.
    @Volatile private var lastPushAt = 0L
    // Consecutive safety polls that found new bytes the daemon should already
    // have pushed. Two in a row ⇒ stop trusting the daemon for this session and
    // go back to the normal cadence until a poll comes up clean again.
    @Volatile private var daemonMisses = 0
    // Serializes pollOnce() + byteOffset updates between the steady loop and
    // pollNow(). Without it a Stop-hook-triggered pollNow() could run a second
    // pollOnce concurrently with the loop, both advancing byteOffset and losing
    // appended bytes.
    private val pollMutex = Mutex()
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
            // Under pollMutex: pushLines (daemon reader) appends concurrently
            // with this coroutine — the wipe must not interleave with an
            // in-flight append or seenIds and _entries drift apart.
            if (uuidChanged) {
                pollMutex.withLock {
                    _entries.value = emptyList()
                    seenIds.clear()
                    _contextTokens.value = null
                    _status.value = null
                    _historyTruncated.value = false
                    lastPushAt = 0L
                    daemonMisses = 0
                }
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
        // Fresh tail (new start()) resumes from the top of the file; the poll
        // command echoes the file's current size as `__OFFSET__<n>` so we
        // resume from exactly there next time — only NEW bytes are sent over
        // the wire after the initial backlog (keeps idle traffic tiny).
        pollMutex.withLock {
            byteOffset = 0L
            offsetUuid = uuid
            daemonMisses = 0
            lastPushAt = 0L
        }
        var attempt = 0
        while (scope.isActive) {
            attempt++
            try {
                if (_entries.value.isEmpty()) _status.value = "connecting…"
                doPoll(safeFolder, safeUuid)
                attempt = 0
                // A poll that succeeded clears any transient failure note; with
                // no entries yet, keep explaining why the view is still empty.
                _status.value = if (_entries.value.isEmpty()) {
                    // Connected & polled fine, but nothing parseable yet (no
                    // transcript written, or wrong folder/uuid).
                    "connected, no transcript data yet"
                } else null
            } catch (t: Throwable) {
                val msg = t.message?.take(80) ?: t::class.simpleName ?: "unknown error"
                FileLogger.log(TAG, "transcript poll error (attempt $attempt): ${t.message}")
                // Surface the failure even mid-conversation: the user was
                // otherwise shown a healthy-looking, silently frozen chat.
                _status.value = if (_entries.value.isEmpty()) "retry $attempt — $msg"
                else "reconnecting — $msg"
            }
            if (!scope.isActive) break
            // Steady poll cadence; back off only after errors. With a live
            // stream daemon the poll is only an offset-integrity backstop —
            // deltas arrive pushed, in background too. But only while THIS
            // session's watch is demonstrably delivering (see daemonMisses).
            val wait = when {
                attempt > 1 -> (1_000L * attempt).coerceAtMost(10_000L)
                _entries.value.isEmpty() -> 1_500L
                daemonActive() && StreamOffsetPolicy.daemonTrusted(daemonMisses) -> DAEMON_SAFETY_POLL_MS
                isBackground() -> BG_POLL_MS
                !isActiveTab() -> INACTIVE_POLL_MS
                else -> POLL_MS
            }
            delay(wait)
        }
    }

    /**
     * Run one incremental poll under [pollMutex] (advancing the shared
     * [byteOffset]), parse the new lines and append them. Shared by the steady
     * [runTail] loop and [pollNow] so the two never issue overlapping reads.
     */
    private suspend fun doPoll(safeFolder: String, safeUuid: String) = pollMutex.withLock {
        // Prefer the main connection (no extra SSH connection, no handshake per
        // poll); fall back to a short-lived dedicated session only when the tab
        // has no live main connection.
        val shared = liveSession()?.takeIf { it.isConnected }
        // A poll from offset 0 asks for `tail -n INITIAL_LINES`, so a full
        // batch means the file had at least that many lines and we are NOT
        // looking at the start of the conversation.
        val initialRead = byteOffset <= 0L
        val (lines, newOffset) = if (shared != null) {
            pollOnce(shared, safeFolder, safeUuid, byteOffset)
        } else {
            SshSessionHelper.withSession(server, timeout = 15_000) { sess ->
                pollOnce(sess, safeFolder, safeUuid, byteOffset)
            }
        }
        // The poll asks for INITIAL_LINES + 1: getting more than we keep is
        // proof that older history exists, where `>= INITIAL_LINES` would also
        // have fired on a complete file of exactly that length and told the
        // user a whole conversation was cut.
        val truncated = initialRead && lines.size > INITIAL_LINES
        if (truncated) _historyTruncated.value = true
        // Drop the surplus oldest line so the view holds exactly what it claims.
        val kept = if (truncated) lines.subList(lines.size - INITIAL_LINES, lines.size) else lines
        // Daemon health for THIS session: finding bytes here that the daemon
        // should long since have pushed (and no recent push to explain the lag)
        // means its watch isn't delivering, whatever the per-server flag says.
        daemonMisses = StreamOffsetPolicy.nextDaemonMisses(
            prev = daemonMisses,
            daemonLive = daemonActive(),
            pollFoundNewLines = lines.isNotEmpty() && !initialRead,
            msSinceLastPush = System.currentTimeMillis() - lastPushAt,
        )
        if (daemonMisses == StreamOffsetPolicy.DAEMON_MISS_LIMIT) {
            FileLogger.log(TAG, "streamd watch looks dead for $safeUuid — back to the fast poll")
        }
        if (newOffset >= 0) byteOffset = newOffset
        if (kept.isNotEmpty()) {
            val newEntries = TranscriptParser.parseLines(kept.asSequence())
            if (newEntries.isNotEmpty()) {
                _status.value = null
                appendEntries(newEntries)
            }
            TranscriptParser.latestContextTokens(kept.asSequence())?.let {
                _contextTokens.value = it
            }
        }
    }

    /**
     * Force one immediate incremental poll right now, bypassing the steady
     * cadence, and suspend until it finishes. The Stop-hook notification path
     * calls this so the notification body + dedup key reflect the just-finished
     * turn instead of the last periodic poll — which can be up to [BG_POLL_MS]
     * (30 s) stale in background and would surface the previous turn's text or
     * a mid-turn preamble. No-op if stopped or the Claude UUID isn't known yet.
     */
    suspend fun pollNow() {
        if (closed) return
        val uuid = currentUuid ?: return
        val safeFolder = cwd.replace("'", "'\\''")
        val safeUuid = uuid.replace("'", "'\\''")
        try {
            doPoll(safeFolder, safeUuid)
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Cancellation is not a poll failure. Swallowing it left callers
            // that wrap this in withTimeoutOrNull looping past their own
            // deadline — the notification body poll being the one that matters.
            throw c
        } catch (t: Throwable) {
            FileLogger.log(TAG, "pollNow error: ${t.message}")
        }
    }

    /**
     * Consumed-bytes offset FOR [uuid] — the daemon registers its watch from
     * here so it only streams bytes the client doesn't have. Returns 0 when
     * the offset belongs to a different (pre-rotation) uuid, which the caller
     * translates to a from-EOF watch. Overlap is harmless (id dedup); a gap
     * is not, so both fields are volatile-fresh.
     */
    fun offsetFor(uuid: String): Long = if (uuid == offsetUuid) byteOffset else 0L

    /** The Claude session UUID this stream currently tails, or null. */
    fun currentUuid(): String? = currentUuid

    /**
     * Visible for tests ONLY. [pushLines]' offset arithmetic is the one place
     * in this class that can lose transcript bytes for good, and it is
     * unreachable without a live SSH tail to seed the (uuid, offset)
     * bookkeeping [runTail] normally establishes. This seeds exactly that.
     */
    internal fun primeForTest(uuid: String, offset: Long) {
        currentUuid = uuid
        offsetUuid = uuid
        byteOffset = offset
    }

    /**
     * Push a batch of transcript lines delivered by the per-server stream
     * daemon (single shared channel) instead of this stream's own poll.
     * Serialized with the poll path under [pollMutex]; a batch for a stale
     * UUID (the tab /clear'd or /resume'd since the daemon read it) is
     * dropped. [newOffset] moves the poll offset forward so the safety poll
     * doesn't re-fetch what the daemon already delivered. [fromOffset] is the
     * chunk's START offset (streamd's `f`); null means a pre-v4 daemon that
     * doesn't report one — see [StreamOffsetPolicy.dropsGapBatch].
     */
    suspend fun pushLines(uuid: String, lines: List<String>, newOffset: Long, fromOffset: Long? = null) {
        if (closed || uuid != currentUuid) return
        pollMutex.withLock {
            if (closed || uuid != currentUuid) {
                FileLogger.log(TAG, "push dropped: closed=$closed, uuid $uuid vs current $currentUuid")
                return
            }
            // Poll pipeline hasn't re-seeded for this uuid yet (rotation wipe
            // pending) — applying a push would append into the old convo's
            // entries and poison the old offset. The re-seeded tail + backlog
            // poll will cover these bytes.
            if (uuid != offsetUuid) {
                FileLogger.log(TAG, "push dropped: offset still on $offsetUuid, push for $uuid")
                return
            }
            // Backlog not loaded yet (offset 0): applying a push here would
            // jump byteOffset past the initial tail read and leave a GAP the
            // poll can never backfill. Drop the push — the ≤1.5 s startup poll
            // fetches those bytes itself because our offset stays behind them.
            if (byteOffset == 0L) {
                FileLogger.log(TAG, "push dropped: backlog not loaded yet for $uuid")
                return
            }
            // The batch starts beyond what we've consumed, so the bytes between
            // are only in the file. Leave byteOffset where it is and let the
            // safety poll re-read from there; fast-forwarding past the hole (the
            // old behaviour) lost those lines permanently.
            if (StreamOffsetPolicy.dropsGapBatch(byteOffset, fromOffset)) {
                FileLogger.log(TAG, "push dropped: gap for $uuid — batch starts at $fromOffset, have $byteOffset")
                return
            }
            lastPushAt = System.currentTimeMillis()
            if (newOffset > byteOffset) byteOffset = newOffset
            if (lines.isEmpty()) {
                FileLogger.log(TAG, "push dropped: empty batch for $uuid at $newOffset")
                return
            }
            val newEntries = TranscriptParser.parseLines(lines.asSequence())
            if (newEntries.isNotEmpty()) {
                _status.value = null
                appendEntries(newEntries)
            }
            TranscriptParser.latestContextTokens(lines.asSequence())?.let {
                _contextTokens.value = it
            }
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
            // UUID is globally unique — locate the existing transcript by glob
            // first (immune to the lossy cwd->dir encoding). Fall back to the
            // corrected cwd encoding (every non-alphanumeric -> '-') only if the
            // glob finds nothing (file not written yet). The `*` must stay
            // unquoted so the shell expands it.
            append("FILE=\$(ls \"\$HOME/.claude/projects/\"*/\"").append(safeUuid).append(".jsonl\" 2>/dev/null | head -1); ")
            append("if [ -z \"\$FILE\" ]; then ")
            append("F='").append(safeFolder).append("'; ")
            append("case \"\$F\" in \"~\"*) F=\"\$HOME\${F#\"~\"}\";; esac; ")
            append("ENC=\$(cd \"\$F\" 2>/dev/null && pwd | sed 's|[^a-zA-Z0-9]|-|g'); ")
            append("[ -n \"\$ENC\" ] && FILE=\"\$HOME/.claude/projects/\$ENC/").append(safeUuid).append(".jsonl\"; fi; ")
            append("[ -f \"\$FILE\" ] || { echo __OFFSET__-1; exit 0; }; ")
            append("SZ=\$(wc -c < \"\$FILE\" 2>/dev/null || echo 0); ")
            // Initial (offset<=0) or file shrank (rotation) → last N lines;
            // otherwise only the bytes appended since the last poll. Offsets sit
            // on line boundaries (jsonl ends every line with \n), so appended
            // bytes are always whole lines.
            append("if [ ").append(offset).append(" -le 0 ] || [ \"\$SZ\" -lt ").append(offset).append(" ]; then ")
            // One line MORE than we keep: seeing the extra line is the only way
            // to tell "the file is exactly INITIAL_LINES long" (complete) from
            // "there is older history we are not showing" (truncated). The
            // surplus line is dropped before parsing; offsets are byte-based,
            // so dropping it cannot disturb them.
            append("tail -n ").append(INITIAL_LINES + 1).append(" \"\$FILE\" 2>/dev/null; ")
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
    /**
     * When a queued prompt was last seen, by trimmed text. The parser dedups a
     * queued record against its `user` twin within one parse, but the two are
     * written a turn apart and streamd pushes every second, so in the live path
     * they arrive in different batches with different ids — nothing downstream
     * would catch them. Measured: all 7 queued/user pairs in the corpus split
     * that way. Only a prompt that did NOT come from the queue is ever dropped
     * against this, so two prompts the user really typed twice both survive.
     */
    private val queuedPromptTimes = HashMap<String, Long>()
    /** The same, for prompts that came from a `user` record. */
    private val userPromptTimes = HashMap<String, Long>()

    /**
     * Mirrors the parser's asymmetric rule, which the per-batch [ParseState]
     * cannot apply once the pair is split across batches: a queued record is
     * compared against both sides (it can arrive after its twin — measured once
     * in the corpus, 193 s later, and it did render twice), a plain `user`
     * record only against queued ones. Two plain user prompts with the same
     * text are never deduped: those are two prompts the user really typed.
     */
    private fun isSplitDuplicatePrompt(entry: TranscriptEntry.UserPrompt): Boolean {
        val at = entry.timestamp?.let { isoToEpochMillis(it) } ?: return false
        val key = entry.text.trim()
        val maps = if (entry.fromQueue) listOf(queuedPromptTimes, userPromptTimes)
            else listOf(queuedPromptTimes)
        return maps.any { seen ->
            val before = seen[key] ?: return@any false
            kotlin.math.abs(at - before) <= PROMPT_DEDUP_WINDOW_MS
        }
    }

    /**
     * Keep the newest half once the map outgrows its bound: an older prompt can
     * no longer fall inside any future record's dedup window anyway.
     */
    private fun prunePromptMemory(seen: HashMap<String, Long>) {
        if (seen.size <= QUEUED_PROMPT_MEMORY) return
        val keep = seen.entries.sortedByDescending { it.value }
            .take(QUEUED_PROMPT_MEMORY / 2)
            .associate { it.key to it.value }
        seen.clear()
        seen.putAll(keep)
    }

    private fun appendEntries(newEntries: List<TranscriptEntry>) {
        val unique = newEntries.filter { e ->
            if (e is TranscriptEntry.UserPrompt && isSplitDuplicatePrompt(e)) {
                FileLogger.log(TAG, "dropping a queued prompt's duplicate user record")
                return@filter false
            }
            seenIds.add(e.id)
        }
        for (e in unique) {
            if (e !is TranscriptEntry.UserPrompt) continue
            val at = e.timestamp?.let { isoToEpochMillis(it) } ?: continue
            (if (e.fromQueue) queuedPromptTimes else userPromptTimes)[e.text.trim()] = at
        }
        prunePromptMemory(queuedPromptTimes)
        prunePromptMemory(userPromptTimes)
        if (unique.isEmpty()) return
        val sizeBefore = _entries.value.size
        _entries.update { prev ->
            val combined = prev + unique
            if (combined.size > MAX_ENTRIES) {
                combined.subList(combined.size - MAX_ENTRIES, combined.size).toList()
            } else combined
        }
        // Fewer entries than we appended ⇒ the head was dropped, so the view no
        // longer starts where the conversation does and the UI has to say so.
        // Checked here, not inside the update lambda, which can be retried.
        if (_entries.value.size < sizeBefore + unique.size) _historyTruncated.value = true
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
        /** Same rule the parser uses for a queued prompt and its `user` twin. */
        private const val PROMPT_DEDUP_WINDOW_MS = 15L * 60L * 1000L
        /** Bound on [queuedPromptTimes]; a long session must not grow it forever. */
        private const val QUEUED_PROMPT_MEMORY = 256
        // Hard cap on entries held in memory; oldest get dropped when exceeded.
        private const val MAX_ENTRIES = 5000
        // Steady-state poll interval once the backlog has loaded. New content
        // only sends the bytes appended since the last poll, so this is cheap.
        private const val POLL_MS = 3_000L
        // Foreground cadence for NON-active tabs — nobody is looking at their
        // transcript, and pollNow() covers the notification path.
        private const val INACTIVE_POLL_MS = 15_000L
        // Backstop cadence while the stream daemon pushes deltas for us.
        private const val DAEMON_SAFETY_POLL_MS = 60_000L
        // Background poll cadence — much slower; the user isn't looking.
        private const val BG_POLL_MS = 30_000L
    }
}

/**
 * The two [TranscriptStream] decisions that can silently lose transcript data,
 * factored out as pure functions so they can be tested without an SSH session
 * behind them.
 */
internal object StreamOffsetPolicy {
    /** Consecutive daemon misses after which this session stops trusting its watch. */
    const val DAEMON_MISS_LIMIT = 2

    /**
     * A push older than this (ms) can't explain a safety poll finding new
     * bytes: streamd ticks once a second, so anything beyond a few ticks means
     * the watch, not the lag, is the reason nothing arrived.
     */
    const val PUSH_STALE_MS = 5_000L

    /**
     * Should a daemon-pushed batch be dropped because it begins beyond the
     * bytes the client has consumed? [from] is the chunk's START offset as
     * reported by streamd (`f`); null means a pre-v4 daemon that doesn't report
     * one, where the lenient pre-v4 behaviour is all that's available.
     *
     * Dropping rather than fast-forwarding the client's byte offset to the
     * batch's end is what lets the safety poll heal the hole: the client's
     * offset stays BEHIND the missing bytes, so the next `tail -c +offset`
     * re-reads them. Fast-forwarding stepped over them for good.
     */
    fun dropsGapBatch(byteOffset: Long, from: Long?): Boolean = from != null && from > byteOffset

    /** True while the per-server daemon flag may still be believed for this session. */
    fun daemonTrusted(misses: Int): Boolean = misses < DAEMON_MISS_LIMIT

    /**
     * Fold one safety-poll result into the consecutive-miss count. A miss is
     * "the daemon claims to be live, the poll found bytes it should already
     * have pushed, and no recent push explains the lag". Anything else resets
     * the count, so a session heals as soon as its watch starts delivering —
     * and an IDLE session (no new bytes at all) is never counted against.
     */
    fun nextDaemonMisses(
        prev: Int,
        daemonLive: Boolean,
        pollFoundNewLines: Boolean,
        msSinceLastPush: Long,
    ): Int = if (daemonLive && pollFoundNewLines && msSinceLastPush > PUSH_STALE_MS) prev + 1 else 0
}
