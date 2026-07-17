package com.clauderemote.session.service

import com.clauderemote.session.CostCalculator
import com.clauderemote.session.TabManager
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "UsageService"

/**
 * 5h/week usage percents + reset minutes + usage tokens + per-server ccusage
 * polling. Extracted verbatim from SessionOrchestrator: the state, timing,
 * ordering, locking and atomic map operations are unchanged — a pure move so
 * the public API and runtime behavior stay identical.
 */
internal class UsageService(
    private val scope: CoroutineScope,
    private val registry: ConnectionRegistry,
    private val tabManager: TabManager,
    private val isBackground: () -> Boolean,
    private val onUsageUpdate: (Int?, Int?) -> Unit,
) {
    // Last parsed usage tokens (for dashboard)
    private val _usageTokens = MutableStateFlow<CostCalculator.UsageTokens?>(null)
    val usageTokens: StateFlow<CostCalculator.UsageTokens?> = _usageTokens

    // Usage percentages parsed from the OMC statusline, keyed by SERVER id.
    // 5h and weekly limits are account-wide, so every session on the same
    // server shares them — switch tabs and the values stay put instead of
    // resetting to '—' until the new tab fetches them itself.
    private val _sessionUsagePercents = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val sessionUsagePercents: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _sessionUsagePercents
    private val _weekUsagePercents = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val weekUsagePercents: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _weekUsagePercents

    // Time-to-reset (minutes) parsed from the OMC `(XhYm)` suffix.
    private val _sessionResetMin = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val sessionResetMin: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _sessionResetMin
    private val _weekResetMin = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val weekResetMin: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _weekResetMin

    // Per-SERVER periodic polling jobs (keyed by server id). Usage (ccusage is
    // account-wide) and latency (all sessions share the physical link) are
    // identical for every session on a server — at 21 sessions on one box the
    // old per-session polls did 21× the SSH work to fetch one value.
    // ConcurrentHashMap + compute(): every session's attach/reconnect touches
    // its server's entry, overlapping with disconnects during a reconnect storm.
    private val usagePollingJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun startServerUsagePolling(serverId: String) {
        // Idempotent: ccusage data is account-wide, one loop per server serves
        // every session on it. Don't restart on reattach — the loop resolves a
        // live connection fresh each tick, so it self-heals across reconnects.
        // compute() makes check-and-launch atomic per key against a concurrent
        // last-session teardown's remove()+cancel().
        usagePollingJobs.compute(serverId) { _, existing ->
            if (existing?.isActive == true) return@compute existing
            scope.launch {
                kotlinx.coroutines.delay(5000) // initial delay
                while (isActive) {
                    // Skip poll when app is in background — user can't see usage bar anyway.
                    // A missing connection (mid-reconnect) just skips this round —
                    // the old `?: break` exited the loop PERMANENTLY the first time
                    // a reconnect briefly emptied the connections map, leaving the
                    // chips dead until app restart.
                    if (!isBackground()) {
                        try {
                            val sshSession = registry.liveServerSession(serverId)
                            if (sshSession != null) {
                                // 60s budget: the first run may npm-install ccusage.
                                val output = execReadWithWatchdog(
                                    sshSession,
                                    "which ccusage >/dev/null 2>&1 || npm install -g ccusage >/dev/null 2>&1; ccusage blocks --active --json --offline --no-color 2>/dev/null || echo '{}'",
                                    totalMs = 60_000,
                                )
                                parseUsageJson(output)
                            }
                        } catch (_: Exception) {}
                    }
                    kotlinx.coroutines.delay(120_000) // poll every 2 min (was 30s)
                }
            }
        }
    }

    /**
     * Apply the 5h/week usage + reset-minute values scraped from the OMC
     * statusline. Keyed by SERVER, not session — switching to another session
     * on the same server then keeps the values instead of resetting to "—"
     * until that session happens to fetch them itself.
     */
    fun applyStatusline(serverId: String, usage: Map<String, Int>) {
        usage["session"]?.let { s ->
            _sessionUsagePercents.update { it + (serverId to s) }
        }
        usage["week"]?.let { w ->
            _weekUsagePercents.update { it + (serverId to w) }
        }
        usage["session_reset_min"]?.let { m ->
            _sessionResetMin.update { it + (serverId to m) }
        }
        usage["week_reset_min"]?.let { m ->
            _weekResetMin.update { it + (serverId to m) }
        }
        onUsageUpdate(usage["session"], usage["week"])
    }

    /** Cancel + drop this server's usage polling job (last session left). */
    fun stopPolling(serverId: String) { usagePollingJobs.remove(serverId)?.cancel() }

    private fun parseUsageJson(json: String) {
        try {
            if (json.isBlank() || json.trim() == "{}") return
            // Parse token counts from ccusage blocks JSON
            // Sum all token types for real usage
            val inputTokens = Regex("\"inputTokens\"\\s*:\\s*(\\d+)").find(json)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val outputTokens = Regex("\"outputTokens\"\\s*:\\s*(\\d+)").find(json)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val cacheCreation = Regex("\"cacheCreationInputTokens\"\\s*:\\s*(\\d+)").find(json)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val cacheRead = Regex("\"cacheReadInputTokens\"\\s*:\\s*(\\d+)").find(json)
                ?.groupValues?.get(1)?.toLongOrNull() ?: 0L

            val totalUsed = inputTokens + outputTokens + cacheCreation + cacheRead
            if (totalUsed == 0L) return

            // Store for dashboard
            _usageTokens.value = CostCalculator.UsageTokens(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                cacheCreationTokens = cacheCreation,
                cacheReadTokens = cacheRead
            )

            // Parse remaining minutes from projection
            val remaining = Regex("\"remainingMinutes\"\\s*:\\s*(\\d+)").find(json)
                ?.groupValues?.get(1)?.toIntOrNull()

            // Estimate percentage: remaining/300min (5h) = remaining fraction
            val pct = if (remaining != null && remaining < 300) {
                ((1.0 - remaining.toDouble() / 300.0) * 100).toInt().coerceIn(0, 100)
            } else {
                // Fallback: burn rate based
                val burnRate = Regex("\"tokensPerMinute\"\\s*:\\s*([\\d.]+)").find(json)
                    ?.groupValues?.get(1)?.toDoubleOrNull() ?: return
                if (burnRate <= 0) return
                val estimatedTotal = burnRate * 300 // 5h in minutes
                ((totalUsed.toDouble() / estimatedTotal) * 100).toInt().coerceIn(0, 100)
            }
            FileLogger.log(TAG, "Usage: ${totalUsed} tokens, ${remaining}min remaining, ${pct}%")
            onUsageUpdate(pct, null)
        } catch (e: Exception) {
            FileLogger.error(TAG, "Usage parse failed: ${e.message}", e)
        }
    }
}
