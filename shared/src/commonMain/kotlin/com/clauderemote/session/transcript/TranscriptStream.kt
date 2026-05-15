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

    fun start(claudeSessionUuid: String) {
        if (currentUuid == claudeSessionUuid && streamJob?.isActive == true) return
        currentUuid = claudeSessionUuid
        streamJob?.cancel()
        _entries.value = emptyList()
        streamJob = scope.launch { runTail(claudeSessionUuid) }
    }

    suspend fun stop() {
        streamJob?.cancelAndJoin()
        streamJob = null
        currentUuid = null
    }

    private suspend fun runTail(uuid: String) {
        val enc = encodeCwd(cwd)
        val remotePath = "~/.claude/projects/$enc/$uuid.jsonl"
        // tail -n +1 -F: emit from line 1, follow rotations/recreations.
        // 2>/dev/null suppresses transient "file truncated" messages.
        val cmd = "tail -n +1 -F $remotePath 2>/dev/null"
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
                        val pending = StringBuilder()
                        while (scope.isActive && !ch.isClosed) {
                            val line = withContext(Dispatchers.IO) {
                                try { reader.readLine() } catch (_: Throwable) { null }
                            } ?: break
                            pending.append(line).append('\n')
                            // Parse opportunistically. Each JSONL line is independent;
                            // partial lines (rare, when tail catches mid-write) will fail
                            // parse cleanly and get dropped by the parser.
                            val newEntries = TranscriptParser.parseLines(sequenceOf(line))
                            if (newEntries.isNotEmpty()) {
                                _entries.value = _entries.value + newEntries
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

        fun encodeCwd(cwd: String): String = cwd.replace('/', '-')
    }
}
