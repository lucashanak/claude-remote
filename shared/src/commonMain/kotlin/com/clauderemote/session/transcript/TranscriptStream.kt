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
    private val parentScope: CoroutineScope
) {
    private val _entries = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val entries: StateFlow<List<TranscriptEntry>> = _entries.asStateFlow()

    private val supervisor = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + supervisor)
    private var streamJob: Job? = null
    private var currentUuid: String? = null

    @Synchronized
    fun start(claudeSessionUuid: String) {
        if (currentUuid == claudeSessionUuid && streamJob?.isActive == true) return
        currentUuid = claudeSessionUuid
        // Cancel-and-join the previous tail in a new coroutine so the new one
        // never appends concurrently with the old one. Capture the old job
        // locally before reassignment.
        val previous = streamJob
        streamJob = scope.launch {
            previous?.cancelAndJoin()
            _entries.value = emptyList()
            runTail(claudeSessionUuid)
        }
    }

    suspend fun stop() {
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
            // Outer loop: re-pick the newest *.jsonl every time the
            // inner tail dies. We start from the UUID-targeted file
            // when it exists (so we keep showing the conversation
            // matching tab.claudeSessionId) and on first iteration only;
            // afterwards we always follow whatever the newest jsonl is,
            // which catches `/clear` and `/resume` rotations that the
            // 60 s server-as-truth reconcile would otherwise miss.
            // A 5 s watcher kills the inner tail when a newer jsonl
            // appears in \$DIR so we don't keep tailing a dead file.
            append("UUID='").append(safeUuid).append("'; ")
            append("FIRST=1; ")
            append("while :; do ")
            append("  if [ \$FIRST -eq 1 ] && [ -f \"\$DIR/\$UUID.jsonl\" ]; then T=\"\$DIR/\$UUID.jsonl\"; ")
            append("  else T=\$(ls -t \"\$DIR\"/*.jsonl 2>/dev/null | head -1); fi; ")
            append("  FIRST=0; ")
            append("  if [ -z \"\$T\" ] || [ ! -f \"\$T\" ]; then sleep 5; continue; fi; ")
            append("  tail -n ").append(INITIAL_LINES).append(" -F \"\$T\" 2>/dev/null & ")
            append("  TPID=\$!; ")
            append("  while kill -0 \$TPID 2>/dev/null; do ")
            append("    sleep 5; ")
            append("    NEW=\$(ls -t \"\$DIR\"/*.jsonl 2>/dev/null | head -1); ")
            append("    if [ -n \"\$NEW\" ] && [ \"\$NEW\" != \"\$T\" ]; then kill \$TPID 2>/dev/null; break; fi; ")
            append("  done; ")
            append("  wait \$TPID 2>/dev/null; ")
            append("done")
        }
        var attempt = 0
        while (scope.isActive) {
            attempt++
            try {
                SshSessionHelper.withSession(server, timeout = 15_000) { sess ->
                    val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
                    ch.setCommand(cmd)
                    ch.inputStream = null
                    val inStream = ch.inputStream
                    withContext(Dispatchers.IO) { ch.connect(10_000) }
                    try {
                        val reader = BufferedReader(InputStreamReader(inStream, Charsets.UTF_8))
                        while (scope.isActive && !ch.isClosed) {
                            val line = withContext(Dispatchers.IO) {
                                try { reader.readLine() } catch (_: Throwable) { null }
                            } ?: break
                            // Parse opportunistically. Each JSONL line is independent;
                            // partial lines (rare, when tail catches mid-write) will fail
                            // parse cleanly and get dropped by the parser.
                            val newEntries = TranscriptParser.parseLines(sequenceOf(line))
                            if (newEntries.isNotEmpty()) {
                                _entries.update { prev ->
                                    // Dedup by id. On SSH reconnect the
                                    // outer retry loop reopens tail -F, which
                                    // re-emits the last 2 000 lines from the
                                    // start of the file. Without this guard
                                    // every reconnect would clone the whole
                                    // backlog into the entries flow.
                                    val seen = prev.mapTo(HashSet(prev.size)) { it.id }
                                    val unique = newEntries.filter { seen.add(it.id) }
                                    if (unique.isEmpty()) return@update prev
                                    val combined = prev + unique
                                    if (combined.size > MAX_ENTRIES) {
                                        combined.subList(combined.size - MAX_ENTRIES, combined.size).toList()
                                    } else combined
                                }
                            }
                        }
                    } finally {
                        try { ch.disconnect() } catch (_: Throwable) {}
                    }
                }
                attempt = 0
            } catch (t: Throwable) {
                FileLogger.log(TAG, "tail stream error (attempt $attempt): ${t.message}")
            }
            if (!scope.isActive) break
            val backoff = (1_000L * attempt).coerceAtMost(15_000L)
            delay(backoff)
        }
    }

    companion object {
        private const val TAG = "TranscriptStream"
        // Cap initial backlog from tail to bound startup time + RAM.
        private const val INITIAL_LINES = 2000
        // Hard cap on entries held in memory; oldest get dropped when exceeded.
        private const val MAX_ENTRIES = 5000
    }
}
