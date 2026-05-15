package com.clauderemote.session.status

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.SshServer
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Active skill name + count of in-flight subagents for the session.
 * Both come from OMC state files inside the remote project's `.omc/state/`
 * — same source the OMC statusline reads from in the terminal.
 */
data class RemoteSessionStatus(
    val activeSkill: String? = null,
    val activeSubagents: Int = 0
)

/**
 * Polls two small OMC state files over SSH with stat-based change detection:
 * we check mtimes every [POLL_INTERVAL_MS]; the file payload is only read
 * when mtime changed. Idle traffic is ~50 bytes per tick.
 *
 * One instance per active session; canceled by the orchestrator on disconnect.
 */
class SessionStatusPoller(
    private val server: SshServer,
    private val cwd: String,
    private val claudeSessionIdProvider: () -> String?,
    private val scope: CoroutineScope
) {
    private val _status = MutableStateFlow(RemoteSessionStatus())
    val status: StateFlow<RemoteSessionStatus> = _status.asStateFlow()

    private var pollJob: Job? = null

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch { runPoll() }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun runPoll() {
        val safeCwd = cwd.replace("'", "'\\''")
        // OMC's skill-active state lives at
        // <project>/.omc/state/sessions/<claudeSessionId>/skill-active-state.json
        // (session-scoped), while subagent-tracking.json is project-wide at
        // <project>/.omc/state/subagent-tracking.json. We resolve the id per
        // tick because it can rotate when the user invokes /resume or /clear.
        fun buildCmd(claudeUuid: String?): String = buildString {
            val safeUuid = claudeUuid?.replace("'", "'\\''") ?: ""
            append("F='").append(safeCwd).append("'; ")
            append("case \"\$F\" in \"~\"*) F=\"\$HOME\${F#\"~\"}\";; esac; ")
            append("D=\$(cd \"\$F\" 2>/dev/null && pwd); ")
            append("[ -z \"\$D\" ] && exit 0; ")
            append("U='").append(safeUuid).append("'; ")
            append("SKILL=\"\$D/.omc/state/sessions/\$U/skill-active-state.json\"; ")
            append("SUB=\"\$D/.omc/state/subagent-tracking.json\"; ")
            append("for f in \"\$SKILL\" \"\$SUB\"; do ")
            append("if [ -n \"\$U\" -o \"\$f\" = \"\$SUB\" ] && [ -f \"\$f\" ]; then ")
            append("echo \"===FILE \$f\"; ")
            append("stat -c '%Y' \"\$f\" 2>/dev/null || stat -f '%m' \"\$f\" 2>/dev/null || echo 0; ")
            append("cat \"\$f\" 2>/dev/null; ")
            append("else echo \"===MISSING \$f\"; fi; ")
            append("done; echo ===END")
        }

        var lastSkillMtime = -1L
        var lastSubagentMtime = -1L
        var attempt = 0
        while (scope.isActive) {
            attempt++
            try {
                val statCmd = buildCmd(claudeSessionIdProvider())
                val raw = SshSessionHelper.withSession(server, timeout = 10_000) { sess ->
                    val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
                    ch.setCommand(statCmd)
                    ch.inputStream = null
                    val input = ch.inputStream
                    withContext(Dispatchers.IO) { ch.connect(8_000) }
                    try {
                        withContext(Dispatchers.IO) { input.bufferedReader().readText() }
                    } finally {
                        try { ch.disconnect() } catch (_: Throwable) {}
                    }
                }
                attempt = 0
                val (skillMtime, skillBody, subMtime, subBody) = parseDualFile(raw)
                var current = _status.value
                if (skillMtime != lastSkillMtime) {
                    lastSkillMtime = skillMtime
                    current = current.copy(activeSkill = extractSkillName(skillBody))
                }
                if (subMtime != lastSubagentMtime) {
                    lastSubagentMtime = subMtime
                    current = current.copy(activeSubagents = extractSubagentCount(subBody))
                }
                _status.value = current
            } catch (t: Throwable) {
                FileLogger.log(TAG, "status poll error (attempt $attempt): ${t.message}")
            }
            delay(if (attempt == 0) POLL_INTERVAL_MS else (POLL_INTERVAL_MS * attempt).coerceAtMost(30_000L))
        }
    }

    private data class DualFile(
        val skillMtime: Long,
        val skillBody: String?,
        val subMtime: Long,
        val subBody: String?
    )

    private fun parseDualFile(raw: String): DualFile {
        var skillMtime = -1L
        var subMtime = -1L
        var skillBody: String? = null
        var subBody: String? = null
        var current: String? = null
        var mtime = -1L
        val body = StringBuilder()
        fun flush() {
            val c = current ?: return
            val text = body.toString().trim()
            when {
                c.endsWith("skill-active-state.json") -> { skillMtime = mtime; skillBody = text }
                c.endsWith("subagent-tracking.json") -> { subMtime = mtime; subBody = text }
            }
        }
        raw.lineSequence().forEach { line ->
            when {
                line.startsWith("===FILE ") -> {
                    flush(); body.clear()
                    current = line.removePrefix("===FILE ")
                    mtime = -1L
                }
                line.startsWith("===MISSING ") -> {
                    flush(); body.clear()
                    val path = line.removePrefix("===MISSING ")
                    when {
                        path.endsWith("skill-active-state.json") -> { skillMtime = 0L; skillBody = null }
                        path.endsWith("subagent-tracking.json") -> { subMtime = 0L; subBody = null }
                    }
                    current = null
                }
                line == "===END" -> {
                    flush()
                    current = null
                }
                current != null && mtime < 0 && line.all { it.isDigit() } -> {
                    mtime = line.toLongOrNull() ?: -1L
                }
                current != null && mtime >= 0 -> {
                    body.append(line).append('\n')
                }
            }
        }
        return DualFile(skillMtime, skillBody, subMtime, subBody)
    }

    private fun extractSkillName(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val obj = json.parseToJsonElement(body) as? JsonObject ?: return null
            val active = obj["active"]?.jsonPrimitive?.contentOrNull
            if (active == "false") return null
            obj["skill"]?.jsonPrimitive?.contentOrNull
                ?: obj["name"]?.jsonPrimitive?.contentOrNull
                ?: obj["mode"]?.jsonPrimitive?.contentOrNull
        } catch (_: Throwable) { null }
    }

    private fun extractSubagentCount(body: String?): Int {
        if (body.isNullOrBlank()) return 0
        return try {
            val obj = json.parseToJsonElement(body) as? JsonObject ?: return 0
            // OMC writes {"agents": [{agent_id, status, ...}, ...], ...}.
            // Count entries whose status isn't "completed"/"failed" —
            // those are the in-flight ones the statusline shows.
            val agents = obj["agents"] as? kotlinx.serialization.json.JsonArray ?: return 0
            agents.count { entry ->
                val o = entry as? JsonObject ?: return@count false
                val s = o["status"]?.jsonPrimitive?.contentOrNull
                s != null && s != "completed" && s != "failed"
            }
        } catch (_: Throwable) { 0 }
    }

    companion object {
        private const val TAG = "SessionStatusPoller"
        private const val POLL_INTERVAL_MS = 5_000L
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
