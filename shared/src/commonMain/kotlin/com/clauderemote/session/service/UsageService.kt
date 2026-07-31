package com.clauderemote.session.service

import com.clauderemote.model.ClaudeAccount
import com.clauderemote.model.claudeConfigDirFor
import com.clauderemote.session.CostCalculator
import com.clauderemote.session.TabManager
import com.clauderemote.util.FileLogger
import com.clauderemote.util.isoToEpochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val TAG = "SessionOrchestrator"

// Reads the OAuth access token from the credentials file ON THE SERVER and
// queries Anthropic's usage endpoint directly (free — usage metadata only, no
// model tokens, no subscription credit pool). OMC-independent: only needs the
// credentials file Claude Code keeps refreshed. SECURITY: the token is piped to
// curl via a stdin config file (`-K -`) so it never appears in argv/`ps`, and
// it never leaves the server — the app only ever receives the usage JSON.
//
// PER ACCOUNT: the credentials path is passed in, NOT taken from the server
// shell's environment. This exec runs in a plain SSH shell where
// CLAUDE_CONFIG_DIR is never set, so the old `${CLAUDE_CONFIG_DIR:-$HOME/.claude}`
// always resolved to the DEFAULT account — every session's chips showed the
// default seat's numbers no matter which login it was actually running under.
// [accountConfigDir] is `~/.claude` for the default account and
// `~/.claude-remote/accounts/<slug>` otherwise; it is shell-quoted by the caller.
private fun rateLimitCmd(accountConfigDir: String): String =
    "CRED=\"$accountConfigDir/.credentials.json\"; " +
        "T=\$(grep -oE '\"accessToken\":\"[^\"]+\"' \"\$CRED\" 2>/dev/null | head -1 | sed 's/.*:\"//; s/\"\$//'); " +
        "[ -z \"\$T\" ] && { echo '{}'; exit 0; }; " +
        "printf 'header = \"Authorization: Bearer %s\"\\nheader = \"anthropic-beta: oauth-2025-04-20\"\\nurl = \"https://api.anthropic.com/api/oauth/usage\"\\n' \"\$T\" | " +
        // -w exposes the status: without it a 429 body is indistinguishable from
        // an empty reply, so the poller kept hammering a tripped rate limit.
        "curl -sK - --max-time 8 -w '\\nHTTP:%{http_code}' 2>/dev/null || echo '{}'"

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

    // Usage percentages keyed by USAGE KEY = [usageKey] (server id for the
    // default account, "serverId#slug" for any other login). 5h and weekly
    // limits are per LOGIN and shared by every session on it, so switching tabs
    // keeps the values put instead of resetting to '—' until the new tab fetches
    // them itself — while two sessions on two different seats no longer show
    // each other's numbers.
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

    // SEPARATE jobs map for the rate-limit chip poller. Kept apart from
    // usagePollingJobs so the two loops (ccusage token totals vs. the 5h/week
    // chips) are fully independent — different cadence, different source, and one
    // failing/cancelling never touches the other.
    //
    // Keyed per (server, ACCOUNT): the 5h/week limits belong to the login, so N
    // accounts on one server need N independent pollers. A data-class key (not a
    // concatenated string) so [stopPolling] can match a server exactly instead of
    // prefix-matching its id — the same prefix-match trap the tmux targets hit.
    private data class RateKey(val serverId: String, val accountSlug: String?)
    private val rateLimitPollingJobs = java.util.concurrent.ConcurrentHashMap<RateKey, kotlinx.coroutines.Job>()

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
     * Poll Anthropic's usage endpoint DIRECTLY so the 5h/week chips update
     * INDEPENDENTLY of interactive session activity and of OMC. Runs a small
     * shell command on the server (see [rateLimitCmd]) over the EXISTING shared
     * connection — free (usage metadata only, no `claude`/SDK/model tokens).
     *
     * One loop per (server, [accountSlug]) pair — the limits belong to the LOGIN,
     * so a second account on the same server polls its own credentials and
     * publishes under its own [usageKey]. null slug = the default `~/.claude`
     * login, whose key is the bare server id (so existing UI lookups by server id
     * keep working unchanged).
     *
     * Idempotent + self-healing exactly like startServerUsagePolling: compute()
     * makes check-and-launch atomic against a concurrent teardown's
     * remove()+cancel(), and a missing live session (mid-reconnect) skips the tick
     * rather than breaking the loop.
     */
    fun startServerRateLimitPolling(serverId: String, accountSlug: String? = null) {
        val cmd = rateLimitCmd(serverConfigDir(accountSlug))
        val key = usageKey(serverId, accountSlug)
        rateLimitPollingJobs.compute(RateKey(serverId, accountSlug)) { _, existing ->
            if (existing?.isActive == true) return@compute existing
            scope.launch {
                kotlinx.coroutines.delay(2000) // small initial delay
                var backoffMs = 0L
                while (isActive) {
                    // Skip when backgrounded — user can't see the chips anyway.
                    if (!isBackground()) {
                        try {
                            // Any live connection to the server will do — the
                            // command names the account's credentials path
                            // explicitly, so it does not matter which session's
                            // transport carries it.
                            val sshSession = registry.liveServerSession(serverId)
                            if (sshSession != null) {
                                val output = execReadWithWatchdog(
                                    sshSession,
                                    cmd,
                                    totalMs = 10_000,
                                )
                                if (isRateLimited(output)) {
                                    // Back off instead of retrying into the wall.
                                    // This endpoint's quota is per ACCOUNT and is
                                    // shared with whatever else uses that login —
                                    // notably the OMC statusline, which renders
                                    // "[API 429]" and loses its usage bars when we
                                    // monopolise it. Retrying on the fixed interval
                                    // kept the limit permanently tripped.
                                    backoffMs = if (backoffMs == 0L) RATE_LIMIT_BACKOFF_MS
                                    else (backoffMs * 2).coerceAtMost(RATE_LIMIT_BACKOFF_MAX_MS)
                                    FileLogger.log(
                                        TAG,
                                        "usage endpoint 429 for $key — backing off ${backoffMs / 1000}s",
                                    )
                                } else {
                                    backoffMs = 0L
                                    parseRateLimitJson(key, output)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    kotlinx.coroutines.delay(if (backoffMs > 0L) backoffMs else RATE_LIMIT_POLL_MS)
                }
            }
        }
    }

    /**
     * Parse Anthropic's usage-endpoint JSON via regex (no serialization dep,
     * same approach as parseUsageJson) and feed the chips through applyStatusline.
     * The `five_hour`/`seven_day` objects each carry a `utilization` percent and
     * an ISO `resets_at` (with a `+00:00` offset). Reset timestamps become
     * minutes-from-now. Only keys that parse are included; a blank/`{}`/unparseable
     * response is skipped (keeps the last value) — no OMC-cache fallback.
     */
    /**
     * True when the probe came back rate-limited. Checked before parsing so a 429
     * body (which carries no `five_hour`) isn't mistaken for "no data yet".
     */
    private fun isRateLimited(output: String): Boolean =
        Regex("HTTP:(\\d{3})").find(output)?.groupValues?.get(1) == "429"

    private fun parseRateLimitJson(key: String, json: String) {
        try {
            if (json.isBlank() || json.trim() == "{}") return

            val fiveHourObj = Regex("\"five_hour\"\\s*:\\s*\\{([^}]*)\\}").find(json)?.groupValues?.get(1)
            val sevenDayObj = Regex("\"seven_day\"\\s*:\\s*\\{([^}]*)\\}").find(json)?.groupValues?.get(1)

            val utilRegex = Regex("\"utilization\"\\s*:\\s*([\\d.]+)")
            val resetRegex = Regex("\"resets_at\"\\s*:\\s*\"([^\"]+)\"")

            val fiveHour = fiveHourObj?.let { utilRegex.find(it)?.groupValues?.get(1) }
                ?.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100)
            val weekly = sevenDayObj?.let { utilRegex.find(it)?.groupValues?.get(1) }
                ?.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 100)
            val fiveHourResetsAt = fiveHourObj?.let { resetRegex.find(it)?.groupValues?.get(1) }
            val weeklyResetsAt = sevenDayObj?.let { resetRegex.find(it)?.groupValues?.get(1) }

            val update = mutableMapOf<String, Int>()
            fiveHour?.let { update["session"] = it }
            weekly?.let { update["week"] = it }
            fiveHourResetsAt?.let { iso ->
                isoToEpochMillis(iso)?.let { ms ->
                    update["session_reset_min"] =
                        ((ms - System.currentTimeMillis()) / 60000).toInt().coerceAtLeast(0)
                }
            }
            weeklyResetsAt?.let { iso ->
                isoToEpochMillis(iso)?.let { ms ->
                    update["week_reset_min"] =
                        ((ms - System.currentTimeMillis()) / 60000).toInt().coerceAtLeast(0)
                }
            }

            if (update.isEmpty()) return
            applyStatusline(key, update)
            FileLogger.log(TAG, "RateLimit[$key]: 5h=${fiveHour}% wk=${weekly}%")
        } catch (e: Exception) {
            FileLogger.error(TAG, "RateLimit parse failed: ${e.message}", e)
        }
    }

    /**
     * Apply the 5h/week usage + reset-minute values scraped from the OMC
     * statusline. Keyed by [usageKey] (server + account), not by session —
     * switching to another session on the same server AND account then keeps the
     * values instead of resetting to "—" until that session happens to fetch them
     * itself, while a session on a different login gets its own numbers.
     */
    fun applyStatusline(usageKey: String, usage: Map<String, Int>) {
        usage["session"]?.let { s ->
            _sessionUsagePercents.update { it + (usageKey to s) }
        }
        usage["week"]?.let { w ->
            _weekUsagePercents.update { it + (usageKey to w) }
        }
        usage["session_reset_min"]?.let { m ->
            _sessionResetMin.update { it + (usageKey to m) }
        }
        usage["week_reset_min"]?.let { m ->
            _weekResetMin.update { it + (usageKey to m) }
        }
        onUsageUpdate(usage["session"], usage["week"])
    }

    /**
     * Cancel + drop this server's usage + rate-limit polling jobs (last session
     * left). ALL of the server's per-account rate-limit loops go, matched on the
     * key's serverId field — no string prefix matching, so a server whose id is a
     * prefix of another's is never caught in the sweep.
     */
    fun stopPolling(serverId: String) {
        usagePollingJobs.remove(serverId)?.cancel()
        rateLimitPollingJobs.keys.filter { it.serverId == serverId }.forEach { key ->
            rateLimitPollingJobs.remove(key)?.cancel()
        }
    }

    companion object {
        /**
         * State-map key for one LOGIN on one server. The default account keeps the
         * bare server id, so every existing lookup (`map[activeServerId]`) behaves
         * exactly as before multi-account; other logins get `serverId#slug`.
         */
        /**
         * Base cadence for the usage-endpoint poll, per ACCOUNT. Deliberately
         * slower than the old 30s: that was tuned when a single account polled,
         * and N accounts multiply the load on a quota that is also feeding the
         * OMC statusline for the same login.
         */
        private const val RATE_LIMIT_POLL_MS = 60_000L
        private const val RATE_LIMIT_BACKOFF_MS = 120_000L
        private const val RATE_LIMIT_BACKOFF_MAX_MS = 600_000L

        fun usageKey(serverId: String, accountSlug: String?): String =
            if (accountSlug.isNullOrBlank() || accountSlug == ClaudeAccount.DEFAULT_SLUG) serverId
            else "$serverId#$accountSlug"

        /**
         * The account's config dir as a path the SERVER shell can expand inside a
         * double-quoted string — so `~` becomes `$HOME` (a literal `~` there does
         * NOT expand). Default account ⇒ `$HOME/.claude`, which is what this probe
         * has always read.
         *
         * The slug is reduced to the filesystem-name charset first: it is
         * interpolated into a double-quoted shell string, and a malformed slug must
         * not be able to break out of the quotes. A slug that doesn't survive that
         * can't name a real account dir anyway, so it falls back to the default
         * (the probe then simply finds no credentials and the tick no-ops).
         */
        internal fun serverConfigDir(accountSlug: String?): String {
            val home = "\$HOME"
            if (claudeConfigDirFor(accountSlug) == null) return "$home/.claude"
            val safe = accountSlug!!.filter { it.isLetterOrDigit() || it in "._-@" }
            if (safe.isEmpty() || safe == "." || safe == "..") return "$home/.claude"
            return "$home/${ClaudeAccount.ACCOUNTS_ROOT.removePrefix("~/")}/$safe"
        }
    }

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
