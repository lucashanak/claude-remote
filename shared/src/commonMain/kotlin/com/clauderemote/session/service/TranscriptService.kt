package com.clauderemote.session.service

import com.clauderemote.connection.SshManager
import com.clauderemote.session.TabManager
import com.clauderemote.session.transcript.TranscriptEntry
import com.clauderemote.session.transcript.TranscriptStream
import com.clauderemote.storage.SessionStorage
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// Preserve the exact log tag the moved bodies used while they lived in
// SessionOrchestrator, so device-log lines are byte-identical.
private const val TAG = "SessionOrchestrator"

/**
 * Per-session transcript streams (JSONL tail readers), the derived context-window
 * percentage, and the SHARED per-server transcript stream daemon (streamd).
 * Extracted verbatim from SessionOrchestrator: the state, timing, ordering,
 * LOCKING and atomic map operations are unchanged — a pure move so the public
 * API and runtime behavior stay identical.
 *
 * [transcriptLock] MUST stay private: callers never touch the raw map, only the
 * lock-holding accessors ([streamOrNull], [dispose], the transcript flows) that
 * reproduce the orchestrator's original synchronized blocks exactly.
 */
internal class TranscriptService(
    private val scope: CoroutineScope,
    private val registry: ConnectionRegistry,
    private val tabManager: TabManager,
    private val isBackground: () -> Boolean,
    private val onContextUpdate: (String, Int) -> Unit,
    // Needed by transcriptFlow's one-shot UUID kick-probe, moved here verbatim.
    private val sessionStorage: SessionStorage?,
    private val readRealSessionId: suspend (sshManager: SshManager, tmuxName: String) -> String?,
) {
    // Per-session transcript streams (JSONL tail readers).
    private val transcriptStreams = mutableMapOf<String, TranscriptStream>()
    private val transcriptLock = Any()

    // Sessions whose claudeSessionId has been confirmed by at least one server-side
    // probe (pid-probe or sessions.json reconcile). Once confirmed, transcriptFlow()
    // skips the one-shot kick-probe — firing it on every call caused repeated
    // stream restarts (cancel → _entries = emptyList() → blank transcript) whenever
    // the user toggled to the Transcript view while the server was slow to respond.
    private val confirmedUuids = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Per-session context window usage (0-100). Derived from the transcript's
    // latest assistant-message token usage (see startContextTokenCollector),
    // not scraped from the TUI.
    private val _contextPercents = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val contextPercents: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _contextPercents

    // Latest context-size (tokens) seen per session, mirrored from the
    // transcript stream so emit()'s statusline scrape can calibrate the window.
    private val latestContextTokens = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // Calibrated context-window size (tokens) per session: learned from one
    // statusline `ctx:NN%` sighting (window ≈ tokens / pct), snapped to the
    // 200k / 1M tier. Until known, a session whose tokens exceed 200k is
    // assumed 1M (unambiguous); otherwise ctx % is withheld.
    private val contextWindowTokens = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // Sessions that have been WORKING at least once since the app attached.
    // Status chips stay empty until then — so we never surface stale scrollback
    // values on a fresh attach (per product decision).
    private val sawWorkSinceAttach = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val contextTokenCollectors = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    private val fetchJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /**
     * Collect the transcript stream's context-token count and turn it into the
     * ctx-window %. Launched once per session (idempotent). The % is only
     * surfaced after the session has actually worked (so a fresh attach shows no
     * stale value) and once the window size is known — either calibrated from a
     * statusline `ctx:NN%` sighting in emit(), or inferred as 1M when tokens
     * already exceed the 200k tier.
     */
    // Caller holds transcriptLock so create-and-bind stays atomic with the
    // transcriptStreams map (and with disconnectSession's remove-and-cancel).
    // computeIfAbsent makes the single-launch race-free.
    private fun startContextTokenCollector(sessionId: String, stream: TranscriptStream) {
        contextTokenCollectors.computeIfAbsent(sessionId) {
            scope.launch {
                stream.contextTokens.collect { tokens ->
                    if (tokens == null) {
                        // /clear or /compact reset the conversation — drop the
                        // now-stale % instead of holding the pre-clear value.
                        _contextPercents.update { it - sessionId }
                        latestContextTokens.remove(sessionId)
                        return@collect
                    }
                    if (tokens <= 0L) return@collect
                    latestContextTokens[sessionId] = tokens
                    if (sessionId !in sawWorkSinceAttach) return@collect
                    val window = contextWindowTokens[sessionId]
                        ?: if (tokens > 200_000L) 1_000_000L else return@collect
                    val pct = ((tokens.toDouble() / window) * 100).toInt().coerceIn(0, 100)
                    updateContextPercent(sessionId, pct)
                    onContextUpdate(sessionId, pct)
                }
            }
        }
    }

    private fun updateContextPercent(sessionId: String, percent: Int) {
        _contextPercents.update { it + (sessionId to percent) }
    }

    /**
     * The most recent assistant message text for a session, for the
     * "Claude is ready" notification body. Reads whatever transcript stream
     * already exists (one is running once the session has been opened); null
     * if none yet, in which case the caller keeps the generic hint.
     */
    fun lastAssistantText(sessionId: String): String? =
        lastAssistantEntry(sessionId)?.text?.takeIf { it.isNotBlank() }

    /**
     * Last [limit] user/assistant messages (oldest→newest) for the watch's
     * lazy history fetch (see PhoneWearService's /history-request). Reads
     * whatever transcript stream already exists — one runs once a session has
     * been opened; empty if none yet. Role is "user"/"assistant"; tool calls,
     * results, thinking and system notes are dropped — the watch only shows
     * the conversational back-and-forth. Falls back to the single last
     * assistant message when the transcript has none of the above but does
     * have one assistant entry, so an offline/never-opened-in-Chat session
     * still shows something rather than nothing.
     */
    fun recentMessages(sessionId: String, limit: Int = 10): List<Pair<String, String>> {
        val stream = synchronized(transcriptLock) { transcriptStreams[sessionId] } ?: return emptyList()
        val messages = stream.entries.value.mapNotNull { entry ->
            when (entry) {
                is TranscriptEntry.UserPrompt -> "user" to entry.text
                is TranscriptEntry.AssistantText -> "assistant" to entry.text
                else -> null
            }
        }.filter { it.second.isNotBlank() }.takeLast(limit)
        if (messages.isNotEmpty()) return messages
        // Fallback: nothing conversational parsed yet — hand back the single
        // last assistant message as a 1-element list if we have one.
        val last = lastAssistantEntry(sessionId)?.text?.takeIf { it.isNotBlank() }
        return if (last != null) listOf("assistant" to last) else emptyList()
    }

    fun lastAssistantEntry(sessionId: String): TranscriptEntry.AssistantText? {
        val stream = synchronized(transcriptLock) { transcriptStreams[sessionId] } ?: return null
        return stream.entries.value.lastOrNull { it is TranscriptEntry.AssistantText }
            as? TranscriptEntry.AssistantText
    }

    /**
     * Ensure a transcript stream is running for a connected session,
     * regardless of UI view. Idempotent (getOrPut + start()'s own guard +
     * idempotent collector). Called from [attachSessionRuntime] so the last
     * assistant message is available for notifications even when the user
     * only ever uses the Raw terminal view (previously the stream was started
     * lazily by the Chat-view transcriptFlow subscription, so Raw-view
     * notifications had no body).
     */
    fun ensureTranscriptStream(sessionId: String) {
        val tab = tabManager.getTab(sessionId) ?: return
        val stream = synchronized(transcriptLock) {
            val s = transcriptStreams.getOrPut(sessionId) {
                TranscriptStream(tab.server, tab.folder, scope, liveSession = { registry.ssh(sessionId)?.getSession() }, isBackground = { isBackground() }, isActiveTab = { tabManager.activeTabId.value == sessionId }, daemonActive = { serverStreamDaemons[tab.server.id]?.live == true })
            }
            startContextTokenCollector(sessionId, s)
            s
        }
        tab.claudeSessionId?.let { stream.start(it) }
    }

    fun transcriptFlow(sessionId: String): kotlinx.coroutines.flow.StateFlow<List<TranscriptEntry>> {
        val tab = tabManager.getTab(sessionId)
            ?: return kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        val stream = synchronized(transcriptLock) {
            val s = transcriptStreams.getOrPut(sessionId) {
                TranscriptStream(tab.server, tab.folder, scope, liveSession = { registry.ssh(sessionId)?.getSession() }, isBackground = { isBackground() }, isActiveTab = { tabManager.activeTabId.value == sessionId }, daemonActive = { serverStreamDaemons[tab.server.id]?.live == true })
            }
            // Derive the ctx-window % from this stream's token usage. Inside the
            // lock so it binds to the exact stream instance and stays atomic with
            // disconnectSession's teardown.
            startContextTokenCollector(sessionId, s)
            s
        }
        val uuid = tab.claudeSessionId
        if (uuid != null) stream.start(uuid)
        // Freshness nudge: a tab that just became active may be up to
        // INACTIVE_POLL_MS (15 s) behind — pull the delta right away instead
        // of waiting out the current sleep. pollNow is mutex-serialized and
        // incremental, so repeated calls (recompositions) are cheap no-ops.
        scope.launch { stream.pollNow() }
        // One-shot pid-probe to correct the client-generated UUID before the
        // 15 s reconcile loop fires. Only runs until the UUID is confirmed by
        // at least one server-side probe — after that, repeated calls to
        // transcriptFlow() (e.g. every time the user toggles to the Transcript
        // tab) skip this block entirely. Without this guard the probe was fired
        // on every call, each one potentially restarting the stream and blanking
        // the transcript for several seconds while the new SSH tail reconnected.
        val alreadyConfirmed = uuid != null && confirmedUuids[sessionId] == uuid
        val sshMan = registry.ssh(sessionId)
        if (!alreadyConfirmed && sshMan != null && sshMan.isConnected) {
            scope.launch {
                try {
                    val real = readRealSessionId(sshMan, tab.tmuxSessionName)
                    val current = tabManager.getTab(sessionId)
                    if (real != null && current != null && current.claudeSessionId != real) {
                        val claimedByOther = tabManager.tabs.value.any {
                            it.id != sessionId && it.claudeSessionId == real
                        }
                        if (claimedByOther) {
                            FileLogger.log(TAG, "Skip kick-sync UUID $real for $sessionId — already owned by another tab")
                        } else {
                            FileLogger.log(TAG, "Session $sessionId UUID kick-sync from pid-probe: ${current.claudeSessionId} -> $real")
                            tabManager.updateClaudeSessionId(sessionId, real)
                            sessionStorage?.upsert(SessionStorage.fromClaudeSession(current.copy(claudeSessionId = real)))
                            notifyClaudeSessionIdChanged(sessionId, real)
                            confirmedUuids[sessionId] = real
                        }
                    } else if (real != null && current != null) {
                        // UUID already matches — mark confirmed so future calls skip the probe.
                        confirmedUuids[sessionId] = real
                    }
                } catch (_: Exception) {}
            }
        }
        return stream.entries
    }

    /**
     * Diagnostic status of the transcript tail for [sessionId] — what it's doing
     * / why no data yet (connecting, retry+error, "no transcript data yet").
     * Null once entries flow. Shown in the "Waiting for transcript…" state.
     */
    fun transcriptStatusFlow(sessionId: String): kotlinx.coroutines.flow.StateFlow<String?> {
        val tab = tabManager.getTab(sessionId)
            ?: return kotlinx.coroutines.flow.MutableStateFlow(null)
        val stream = synchronized(transcriptLock) {
            transcriptStreams.getOrPut(sessionId) {
                TranscriptStream(tab.server, tab.folder, scope, liveSession = { registry.ssh(sessionId)?.getSession() }, isBackground = { isBackground() }, isActiveTab = { tabManager.activeTabId.value == sessionId }, daemonActive = { serverStreamDaemons[tab.server.id]?.live == true })
            }
        }
        return stream.status
    }

    /**
     * Called when the Claude Code session UUID rotates (e.g. user invoked
     * `/resume` or `/clear`). Restarts the transcript stream against the new
     * file so the UI keeps showing the active conversation.
     */
    fun notifyClaudeSessionIdChanged(sessionId: String, newUuid: String?) {
        val stream = synchronized(transcriptLock) { transcriptStreams[sessionId] } ?: return
        if (newUuid != null) {
            stream.start(newUuid)
            // Re-point the daemon watch at the new JSONL (start() set
            // currentUuid synchronously; the wiped stream reports offset 0 →
            // watch from EOF while the client reloads its own backlog).
            registerStreamWatch(sessionId)
        }
    }

    // ---- Per-server transcript stream daemon (streamd) ----
    //
    // One long-lived exec channel per server replaces N sessions × 20 polls/min
    // of transcript execs: a tiny python script on the server watches the JSONL
    // files locally (cheap — no network) and pushes only NEW COMPLETE LINES as
    // NDJSON events. Idle traffic drops to a 20 s heartbeat; updates arrive in
    // ~1 s instead of 3–30 s, background included. Every TranscriptStream keeps
    // its own poll loop as a 60 s safety backstop and as the full fallback when
    // python3 is missing or the channel dies.

    /** Marker doubles as the version gate — bump vN to force reinstall. */
    private val STREAMD_MARKER = "claude-remote-streamd v2"

    private val STREAMD_SCRIPT = """
        #!/usr/bin/env python3
        # claude-remote-streamd v2 — single-channel transcript delta streamer.
        # stdin : {"op":"watch","id":..,"cwd":..,"uuid":..,"off":N}  (off<0 = from EOF)
        #         {"op":"unwatch","id":..}
        # stdout: {"t":"hello","v":1} | {"t":"hb"} | {"t":"d","id":..,"u":..,"o":N,"b":b64}
        import sys, os, json, time, base64, threading, glob, re

        watches = {}
        lock = threading.Lock()
        TAIL = 200000  # initial backlog bytes when off==0 (~2000 lines)

        def resolve(cwd, uuid):
            # UUID is globally unique — find the existing transcript directly, immune to
            # the lossy cwd->dir encoding (tmux mangles '.'/'_' in session names, so a
            # restored session's cwd can't be reconstructed exactly).
            hits = glob.glob(os.path.join(os.path.expanduser('~/.claude/projects'), '*', uuid + '.jsonl'))
            if hits:
                return max(hits, key=os.path.getmtime)
            # Not written yet — compute the expected path (Claude replaces every
            # non-alphanumeric char with '-') so we can watch for its creation.
            p = os.path.expanduser(cwd)
            if not os.path.isabs(p):
                p = os.path.join(os.path.expanduser('~'), p)
            enc = re.sub(r'[^a-zA-Z0-9]', '-', os.path.realpath(p))
            return os.path.join(os.path.expanduser('~/.claude/projects'), enc, uuid + '.jsonl')

        def emit(o):
            sys.stdout.write(json.dumps(o, separators=(',', ':')) + '\n')
            sys.stdout.flush()

        def reader():
            for line in sys.stdin:
                line = line.strip()
                if not line:
                    continue
                try:
                    c = json.loads(line)
                except Exception:
                    continue
                op = c.get('op')
                if op == 'watch' and c.get('id') and c.get('uuid'):
                    with lock:
                        watches[c['id']] = {
                            'path': resolve(c.get('cwd') or '~', c['uuid']),
                            'uuid': c['uuid'],
                            'off': int(c.get('off') or 0),
                        }
                elif op == 'unwatch':
                    with lock:
                        watches.pop(c.get('id'), None)
            os._exit(0)  # stdin closed -> client gone

        threading.Thread(target=reader, daemon=True).start()
        emit({'t': 'hello', 'v': 1})
        last_hb = time.time()
        while True:
            now = time.time()
            if now - last_hb >= 20:
                emit({'t': 'hb'})
                last_hb = now
            with lock:
                items = list(watches.items())
            for wid, w in items:
                try:
                    sz = os.path.getsize(w['path'])
                except OSError:
                    continue
                off = w['off']
                if off < 0:          # "from EOF": client loads its own backlog
                    w['off'] = sz
                    continue
                adjusted = False
                if sz < off or (off == 0 and sz > TAIL):
                    off = max(0, sz - TAIL)   # rotation / first sight of a big file
                    adjusted = True
                if sz <= off:
                    continue
                try:
                    with open(w['path'], 'rb') as f:
                        f.seek(off)
                        data = f.read(sz - off)
                except OSError:
                    continue
                nl = data.rfind(b'\n')
                if nl < 0:
                    continue          # no complete line yet
                chunk = data[:nl + 1]
                new_off = off + nl + 1
                if adjusted and off > 0:
                    first = chunk.find(b'\n')
                    chunk = chunk[first + 1:]   # drop the partial first line
                w['off'] = new_off
                if chunk:
                    emit({'t': 'd', 'id': wid, 'u': w['uuid'], 'o': new_off,
                          'b': base64.b64encode(chunk).decode()})
            time.sleep(1.0)
    """.trimIndent()

    private val ENSURE_STREAMD_COMMAND = buildString {
        append("F=\"${'$'}HOME/.claude-remote/streamd.py\"; ")
        append("if head -c 200 \"${'$'}F\" 2>/dev/null | grep -q '").append(STREAMD_MARKER).append("'; ")
        append("then echo STREAMD_OK; else mkdir -p \"${'$'}HOME/.claude-remote\" && ")
        append("cat > \"${'$'}F\" <<'CRSD_EOF'\n")
        append(STREAMD_SCRIPT)
        append("\nCRSD_EOF\n")
        append("echo STREAMD_INSTALLED; fi")
    }

    /** Install/refresh streamd.py on the server. Idempotent, non-fatal. */
    suspend fun ensureStreamd(sshManager: SshManager) {
        try {
            val sshSession = sshManager.getSession() ?: return
            val out = execReadWithWatchdog(sshSession, ENSURE_STREAMD_COMMAND, totalMs = 15_000)
            FileLogger.log(TAG, "streamd setup: ${out.trim().lineSequence().lastOrNull()}")
        } catch (e: Exception) {
            FileLogger.error(TAG, "streamd setup failed: ${e.message}", e)
        }
    }

    inner class ServerStreamDaemon(val serverId: String) {
        /** sessionId → cwd; the uuid/offset come from the live stream at send time. */
        val specs = java.util.concurrent.ConcurrentHashMap<String, String>()
        @Volatile var live = false
        @Volatile var stdin: java.io.OutputStream? = null
        @Volatile var lastEventAt = 0L
        /** Serializes control-line writes: jsch's channel OutputStream is not
         *  thread-safe, and the hello fan-out fires N watches at once —
         *  concurrent write() calls corrupt the SSH packet framing. */
        val writeMutex = Mutex()
        var job: kotlinx.coroutines.Job? = null
    }
    private val serverStreamDaemons = java.util.concurrent.ConcurrentHashMap<String, ServerStreamDaemon>()

    /** Register [sessionId]'s transcript with its server's stream daemon
     *  (starting the daemon if needed) — same TOCTOU discipline as the
     *  notify watcher. Re-invoked on attach and on UUID rotation. */
    fun registerStreamWatch(sessionId: String) {
        val tab = tabManager.getTab(sessionId) ?: return
        while (true) {
            val d = serverStreamDaemons.getOrPut(tab.server.id) { ServerStreamDaemon(tab.server.id) }
            val registered = synchronized(d) {
                if (serverStreamDaemons[tab.server.id] !== d) return@synchronized false
                d.specs[sessionId] = tab.folder
                if (d.job?.isActive != true) {
                    d.job = scope.launch { runServerStreamDaemon(d) }
                }
                if (d.live) sendWatch(d, sessionId)
                true
            }
            if (registered) return
        }
    }

    private fun sendWatch(d: ServerStreamDaemon, sessionId: String) {
        val cwd = d.specs[sessionId] ?: return
        val stream = synchronized(transcriptLock) { transcriptStreams[sessionId] } ?: return
        val uuid = stream.currentUuid() ?: return
        // offsetFor(uuid) is 0 when the stream hasn't loaded this uuid's
        // backlog yet (startup OR mid-rotation, where the raw offset still
        // belongs to the OLD file — sending that made the daemon skip the new
        // file's head). 0 → ask the daemon to stream from EOF (-1); the
        // client's own poll fetches the backlog and pushLines drops anything
        // that would race it.
        val off = stream.offsetFor(uuid).let { if (it == 0L) -1L else it }
        val cmd = kotlinx.serialization.json.JsonObject(mapOf(
            "op" to JsonPrimitive("watch"),
            "id" to JsonPrimitive(sessionId),
            "cwd" to JsonPrimitive(cwd),
            "uuid" to JsonPrimitive(uuid),
            "off" to JsonPrimitive(off),
        )).toString()
        sendStreamCmd(d, cmd)
    }

    private fun sendStreamCmd(d: ServerStreamDaemon, line: String) {
        val os = d.stdin ?: return
        scope.launch(Dispatchers.IO) {
            d.writeMutex.withLock {
                try {
                    os.write((line + "\n").toByteArray())
                    os.flush()
                } catch (e: Exception) {
                    FileLogger.log(TAG, "streamd write failed for server ${d.serverId}: ${e.message}")
                }
            }
        }
    }

    private suspend fun runServerStreamDaemon(d: ServerStreamDaemon) = kotlinx.coroutines.coroutineScope {
        var attempt = 0
        while (isActive && d.specs.isNotEmpty()) {
            var ch: com.jcraft.jsch.ChannelExec? = null
            var watchdog: kotlinx.coroutines.Job? = null
            try {
                val sshSession = registry.liveServerSession(d.serverId)
                if (sshSession == null) {
                    kotlinx.coroutines.delay(5_000)
                    continue
                }
                ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand("python3 \"${'$'}HOME/.claude-remote/streamd.py\" 2>/dev/null")
                val stdin = ch.outputStream
                val reader = ch.inputStream.bufferedReader()
                ch.connect(5000)
                d.stdin = stdin
                d.lastEventAt = System.currentTimeMillis()
                // Heartbeats come every 20 s; 60 s of silence = dead channel
                // even while isConnected still lies — force-close so readLine
                // unblocks and the retry loop reconnects.
                val chRef = ch
                watchdog = launch {
                    while (isActive) {
                        kotlinx.coroutines.delay(20_000)
                        if (System.currentTimeMillis() - d.lastEventAt > 60_000) {
                            FileLogger.log(TAG, "streamd watchdog fired for server ${d.serverId}")
                            try { chRef.disconnect() } catch (_: Exception) {}
                            break
                        }
                    }
                }
                while (isActive && ch.isConnected) {
                    val line = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        reader.readLine()
                    } ?: break
                    d.lastEventAt = System.currentTimeMillis()
                    val obj = try {
                        fetchJson.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject
                    } catch (_: Exception) { null } ?: continue
                    when (obj["t"]?.jsonPrimitive?.contentOrNull) {
                        "hello" -> {
                            d.live = true
                            attempt = 0
                            FileLogger.log(TAG, "streamd live for server ${d.serverId} (${d.specs.size} watches)")
                            d.specs.keys.forEach { sendWatch(d, it) }
                        }
                        "hb" -> {}
                        "d" -> {
                            val sid = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                            val uuid = obj["u"]?.jsonPrimitive?.contentOrNull ?: continue
                            val off = obj["o"]?.jsonPrimitive?.longOrNull ?: continue
                            val b64 = obj["b"]?.jsonPrimitive?.contentOrNull ?: continue
                            val stream = synchronized(transcriptLock) { transcriptStreams[sid] } ?: continue
                            val text = try {
                                String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
                            } catch (_: Exception) { continue }
                            // Sequential dispatch on this reader keeps per-
                            // session line order; dedup absorbs any overlap
                            // with the safety poll. BOUNDED: pushLines waits on
                            // the stream's pollMutex, which a slow safety poll
                            // can hold across a timeout-less SSH read — an
                            // unbounded wait would head-of-line-block deltas
                            // for EVERY session on this server and starve the
                            // heartbeat into a false watchdog kill. A dropped
                            // push is backfilled by that same safety poll.
                            kotlinx.coroutines.withTimeoutOrNull(5_000) {
                                stream.pushLines(uuid, text.lineSequence().filter { it.isNotBlank() }.toList(), off)
                            } ?: FileLogger.log(TAG, "streamd push timed out for $sid (safety poll will backfill)")
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                FileLogger.error(TAG, "streamd failed for server ${d.serverId}: ${e.message}", e)
            } finally {
                watchdog?.cancel()
                d.live = false
                d.stdin = null
                try { ch?.disconnect() } catch (_: Exception) {}
            }
            if (!isActive || d.specs.isEmpty()) break
            attempt++
            kotlinx.coroutines.delay((5_000L * attempt).coerceAtMost(60_000L))
        }
        FileLogger.log(TAG, "streamd stopped for server ${d.serverId}")
    }

    // ---- Lock-holding accessors + lifecycle helpers exposed to the facade ----

    /**
     * Reproduces `synchronized(transcriptLock) { transcriptStreams[sessionId] }`
     * for the orchestrator sites that still read a stream (the notify watcher).
     * The raw map + lock stay private — callers only ever get the stream out.
     */
    fun streamOrNull(sessionId: String): TranscriptStream? = synchronized(transcriptLock) { transcriptStreams[sessionId] }

    /** True once [sessionId] has been WORKING at least once since attach. */
    fun hasSeenWork(id: String): Boolean = id in sawWorkSinceAttach

    /** Mark that [id] has WORKED at least once since attach (from updateActivity). */
    fun markWorkSeen(id: String) { sawWorkSinceAttach.add(id) }

    /** emit()'s calibration guard: worked at least once AND window not yet known. */
    fun needsWindowCalibration(id: String): Boolean = id in sawWorkSinceAttach && !contextWindowTokens.containsKey(id)

    /** Calibrate the ctx window from one statusline `ctx:NN%` sighting paired
     *  with the live token count (200k vs 1M tier). [ctxPct] is emit()'s parse. */
    fun calibrateWindow(id: String, ctxPct: Int?) {
        val tokens = latestContextTokens[id]
        if (ctxPct != null && ctxPct in 1..100 && tokens != null && tokens > 0L) {
            val est = tokens.toDouble() / (ctxPct / 100.0)
            contextWindowTokens[id] = if (est > 450_000L) 1_000_000L else 200_000L
        }
    }

    /** Mark [id]'s claudeSessionId confirmed by a server-side probe. */
    fun setConfirmedUuid(id: String, uuid: String) { confirmedUuids[id] = uuid }

    /** Invalidate [id]'s confirmed UUID (tab switch, reconnect, disconnect). */
    fun clearConfirmedUuid(id: String) { confirmedUuids.remove(id) }

    /** True if [id]'s confirmed UUID matches [uuid]. */
    fun isConfirmed(id: String, uuid: String): Boolean = confirmedUuids[id] == uuid

    /** Drop [sessionId]'s derived ctx-% (disconnect). */
    fun clearContextPercent(sessionId: String) { _contextPercents.update { it - sessionId } }

    /**
     * Unregister [sessionId] from its server's stream daemon on disconnect —
     * reproduces the orchestrator's disconnectSession streamd-teardown block
     * verbatim (specs.remove → unwatch if live → cancel the daemon job when the
     * last session leaves), all under the daemon's monitor.
     */
    fun unregisterStreamWatch(serverId: String, sessionId: String) {
        serverStreamDaemons[serverId]?.let { d ->
            synchronized(d) {
                d.specs.remove(sessionId)
                if (d.live) {
                    sendStreamCmd(d, kotlinx.serialization.json.JsonObject(mapOf(
                        "op" to JsonPrimitive("unwatch"),
                        "id" to JsonPrimitive(sessionId),
                    )).toString())
                }
                if (d.specs.isEmpty() && serverStreamDaemons.remove(serverId, d)) {
                    d.job?.cancel()
                }
            }
        }
    }

    /**
     * Transcript teardown on disconnect — reproduces the orchestrator block
     * VERBATIM and in the same order: remove+stop the stream and cancel the
     * ctx collector inside transcriptLock (so a racing transcriptFlow can't
     * relaunch the collector against the now-stopped stream), then drop the
     * three context maps.
     */
    fun dispose(sessionId: String) {
        synchronized(transcriptLock) {
            transcriptStreams.remove(sessionId)?.let { stream ->
                scope.launch { stream.stop() }
            }
            // Cancel the ctx collector inside the same lock so it can't be
            // relaunched against this now-stopped stream by a racing
            // transcriptFlow.
            contextTokenCollectors.remove(sessionId)?.cancel()
        }
        latestContextTokens.remove(sessionId)
        contextWindowTokens.remove(sessionId)
        sawWorkSinceAttach.remove(sessionId)
    }
}
