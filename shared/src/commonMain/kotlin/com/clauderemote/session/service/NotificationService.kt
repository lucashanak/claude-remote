package com.clauderemote.session.service

import com.clauderemote.connection.SshManager
import com.clauderemote.model.SessionActivity
import com.clauderemote.session.ClaudeState
import com.clauderemote.session.InputPromptDetector
import com.clauderemote.session.PromptType
import com.clauderemote.session.TabManager
import com.clauderemote.session.transcript.TranscriptEntry
import com.clauderemote.session.transcript.TranscriptStream
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update

// Preserve the exact log tag the moved bodies used while they lived in
// SessionOrchestrator, so device-log lines are byte-identical.
private const val TAG = "SessionOrchestrator"

/**
 * Input-prompt detection, "Claude needs input" notifications, the SHARED
 * per-server Stop-hook watcher, the `/login` OAuth flow surface, and the
 * offline pending-input queue. Extracted verbatim from SessionOrchestrator:
 * the state, timing, ordering, LOCKING and atomic map operations are unchanged
 * — a pure move so the public API and runtime behavior stay identical.
 *
 * [promptDetector] is exposed as-is so the orchestrator keeps calling
 * `notificationService.promptDetector.X` for its many passthrough sites
 * (onOutput, onUserInput, suppressFor, markInteracted, parseClaudeWorking,
 * onClaudeWorking, parseContextPercent, parseUsage) — exposing the same
 * instance keeps those calls byte-identical.
 */
internal class NotificationService(
    private val scope: CoroutineScope,
    private val registry: ConnectionRegistry,
    private val tabManager: TabManager,
    private val isBackground: () -> Boolean,
    // Bridge to statusService.updateActivity — the promptDetector callbacks call it.
    private val updateActivity: (String, SessionActivity) -> Unit,
    // Bridge to transcriptService.lastAssistantEntry — the notify watcher reads it.
    private val lastAssistantEntry: (String) -> TranscriptEntry.AssistantText?,
    // Bridge to transcriptService.streamOrNull — the notify watcher polls it.
    private val streamOrNull: (String) -> TranscriptStream?,
    // Bridge to the facade's public `var onClaudeNeedsInput`.
    private val onNeedsInput: (String, String, Boolean, String?) -> Unit,
) {
    // Prompt detection for notifications — quiescence-based, reads rendered screen state.
    internal val promptDetector = InputPromptDetector().apply {
        onDetection = { det ->
            val isActive = tabManager.activeTabId.value == det.sessionId
            fireNeedsInput(det.sessionId, det.type.displayHint, isActive)
            // Bridge prompt types that require explicit user approval to the
            // APPROVAL_NEEDED activity so the y/n buttons visually emphasize.
            // The onStateChange callback will overwrite this on the next state
            // change (e.g. WORKING), so it self-clears automatically.
            if (det.type == PromptType.APPROVAL_NEEDED || det.type == PromptType.PERMISSION_PROMPT) {
                updateActivity(det.sessionId, SessionActivity.APPROVAL_NEEDED)
            }
        }
        onStateChange = { sessionId, state ->
            when (state) {
                ClaudeState.WORKING -> updateActivity(sessionId, SessionActivity.WORKING)
                ClaudeState.IDLE -> updateActivity(sessionId, SessionActivity.WAITING_FOR_INPUT)
                ClaudeState.APPROVAL -> updateActivity(sessionId, SessionActivity.APPROVAL_NEEDED)
                ClaudeState.UNKNOWN -> {} // keep last known activity
            }
        }
        onLoginDetected = { sid, url ->
            // SECURITY: never log the URL — it seams into the pasted auth code.
            _loginFlow.update { cur ->
                val next = if (url != null) com.clauderemote.model.LoginFlowState(sid, url)
                else if (cur?.sessionId == sid) null else cur
                if ((cur == null) != (next == null)) {
                    FileLogger.log(TAG, "login flow ${if (next != null) "detected" else "cleared"} for $sid")
                }
                next
            }
        }
    }

    // Sessions whose idle/working state is driven by the Claude Code Stop hook
    // (authoritative: flips to WAITING the instant Claude finishes, regardless
    // of which screen the user is on). The UI uses this to know it can trust
    // `activity` outright instead of falling back to a stale-WORKING timer.
    private val _hookActiveSessions = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val hookActiveSessions: kotlinx.coroutines.flow.StateFlow<Set<String>> = _hookActiveSessions

    fun setHookActive(sessionId: String, active: Boolean) {
        if (active) promptDetector.markHookActive(sessionId)
        else promptDetector.markHookInactive(sessionId)
        _hookActiveSessions.update { if (active) it + sessionId else it - sessionId }
    }

    // Pending input queue per session (for offline queue feature)
    private val pendingInputs = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    private val _pendingCounts = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
    val pendingCounts: kotlinx.coroutines.flow.StateFlow<Map<String, Int>> = _pendingCounts

    /** Dispatch [onNeedsInput] no more than once per [notifyDebounceMs] per
     *  session — protects against rapid duplicate fires from the Stop-hook stream
     *  and from the screen-state fallback firing on transient quiescence. */
    private fun fireNeedsInput(
        sessionId: String,
        hint: String,
        isActive: Boolean,
        // Precise identity of the triggering event when the caller has one —
        // the Stop-hook path passes "stop#<tmux>#<epoch>" so one completion
        // maps to exactly one key regardless of transcript freshness.
        eventKey: String? = null,
        // Raw assistant text (markdown) for the just-finished turn, resolved by
        // the Stop path after its atomic epoch claim wins; null for the other
        // callers, in which case the platform falls back to the generic hint.
        body: String? = null,
    ) {
        val now = System.currentTimeMillis()
        // Dedup by the explicit event key when given, else by (hint + last
        // assistant message). On reconnect the tmux buffer is replayed and the
        // screen looks idle/approval again, and a flapping
        // OMC statusline (WORKING→idle→WORKING around a prompt) re-raises the
        // SAME alert — both re-fired a notification for an event the user already
        // saw. Keying on the hint TOO lets a genuine APPROVAL after an
        // INPUT_PROMPT on the same message still get through, while the same
        // (hint, message) repeat is suppressed. Previously only INPUT_PROMPT was
        // deduped, so APPROVAL/PERMISSION spammed on every flap.
        // The check-and-update runs under a lock so non-epoch callers (APPROVAL
        // etc.) can't race two coroutines through the same dedup slot; the
        // platform callback is invoked OUTSIDE the lock (never hold a lock
        // across a platform callback).
        val key = eventKey ?: lastAssistantEntry(sessionId)?.id?.let { "$hint#$it" }
        val proceed = synchronized(lastNotifiedKey) {
            if (key != null && lastNotifiedKey[sessionId] == key) {
                FileLogger.log(TAG, "Suppressed needs-input for $sessionId (same event)")
                false
            } else {
                val last = lastNeedsInputAt[sessionId] ?: 0L
                if (now - last < notifyDebounceMs) {
                    FileLogger.log(TAG, "Suppressed needs-input for $sessionId (debounce)")
                    false
                } else {
                    lastNeedsInputAt[sessionId] = now
                    if (key != null) lastNotifiedKey[sessionId] = key
                    true
                }
            }
        }
        if (!proceed) return
        onNeedsInput(sessionId, hint, isActive, body)
    }

    /**
     * SHARED per-server Stop-hook watcher. All sessions on a server tail the
     * SAME /tmp/claude-notify, so one watcher per server replaces N identical
     * long-lived tail -f channels (20 fewer at 21 sessions) and dispatches each
     * marker to its owning session by the exact tmux-name token in the line.
     */
    private class ServerNotifyWatcher(val serverId: String) {
        /** tmux session name → app session id, updated on attach/detach. */
        val tmuxToSession = java.util.concurrent.ConcurrentHashMap<String, String>()
        /** True while the tail channel is connected (hook-based detection active). */
        @Volatile var live = false
        var job: kotlinx.coroutines.Job? = null
    }
    private val serverNotifyWatchers = java.util.concurrent.ConcurrentHashMap<String, ServerNotifyWatcher>()

    /** Per-session timestamp of the last "needs input" dispatch, used to debounce
     *  the Stop-hook fire stream — claude can emit several markers in quick
     *  succession (model handoff, tool retries) and we don't want to vibrate
     *  the phone for each one. */
    private val lastNeedsInputAt = mutableMapOf<String, Long>()
    /** Last "(hint)#(assistant-message-id)" we fired a notification for, per
     *  session — dedups reconnect replays / statusline flaps re-notifying an
     *  already-seen event (per prompt type). */
    private val lastNotifiedKey = mutableMapOf<String, String>()
    // Highest Stop-hook marker epoch (seconds) we've already NOTIFIED for a
    // session. The watcher replays tail -n 25 on every reconnect and restarts
    // every ~30-45 min; a completion is identified by its epoch, so we notify
    // only for a STRICTLY NEWER epoch per session — replays (same/older epoch),
    // the single-slot lastNotifiedKey overwrite, and the check-and-set race can
    // no longer re-fire an already-notified completion. ConcurrentHashMap +
    // the synchronized claim below make the check-and-set atomic.
    private val lastNotifiedEpochSec = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // Assistant-message id sent in the last notification per session, so we never
    // resend the previous round's message as a "new" completion body.
    private val lastNotifiedAssistantId = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val notifyDebounceMs = 5_000L
    /** A Stop-hook marker older than this (skew-corrected) is dropped as stale —
     *  it's a buffered replay from a network/HyperOS freeze, not a fresh
     *  completion the user is waiting on. */
    private val notifyStaleMs = 120_000L

    // Active Claude `/login` OAuth flow detected on the current screen, or null.
    // Fed by InputPromptDetector.onLoginDetected. The URL is never logged.
    private val _loginFlow = kotlinx.coroutines.flow.MutableStateFlow<com.clauderemote.model.LoginFlowState?>(null)
    val loginFlow: kotlinx.coroutines.flow.StateFlow<com.clauderemote.model.LoginFlowState?> = _loginFlow

    /** Clear the login card for [sessionId] (user submitted the code or cancelled). */
    fun clearLoginFlow(sessionId: String) { _loginFlow.update { if (it?.sessionId == sessionId) null else it } }

    // ---- Claude Code Stop-hook integration ----

    /**
     * Shell command run via SSH exec to ensure `~/.claude/settings.json` on the
     * remote server contains a `Stop` hook that appends to `/tmp/claude-notify`.
     * Uses `python3` for safe JSON merge (preserves all existing content).
     * Idempotent — checks for our marker string before adding.
     */
    private val ENSURE_HOOK_COMMAND = """
        python3 -c "
import json, os
p = os.path.expanduser('~/.claude/settings.json')
d = {}
if os.path.exists(p):
    with open(p) as f: d = json.load(f)
hooks = d.setdefault('hooks', {})
stop = hooks.setdefault('Stop', [])
marker = 'claude-remote-notify'
cmd = \"echo claude-remote-notify \$(tmux -u display-message -p '#S' 2>/dev/null || echo unknown) \$(date +%s) >> /tmp/claude-notify\"
want = {'matcher': '', 'hooks': [{'type': 'command', 'command': cmd}]}
def has_marker(e):
    if not isinstance(e, dict): return False
    if marker in str(e.get('command', '')): return True
    for h in e.get('hooks') or []:
        if isinstance(h, dict) and marker in str(h.get('command', '')): return True
    return False
canonical_ok = any(e == want for e in stop if isinstance(e, dict))
stale = [e for e in stop if has_marker(e) and e != want]
if canonical_ok and not stale:
    print('HOOK_EXISTS')
else:
    hooks['Stop'] = [e for e in stop if not has_marker(e)] + [want]
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w') as f: json.dump(d, f, indent=2)
    print('HOOK_FIXED')
" 2>&1 || echo 'HOOK_FAILED'
    """.trimIndent()

    /**
     * Ensure the Claude Code `Stop` hook is present on the remote server. Runs
     * a one-shot SSH exec. Safe to call multiple times — the script is
     * idempotent. Failures are logged but non-fatal (screen-scraping fallback
     * still works).
     */
    suspend fun ensureStopHook(sshManager: SshManager) {
        try {
            val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                val sshSession = sshManager.getSession() ?: return@withContext "NO_SESSION"
                val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                ch.setCommand(ENSURE_HOOK_COMMAND)
                ch.inputStream = null
                val input = ch.inputStream
                ch.connect(10_000)
                val out = input.bufferedReader().readText().trim()
                ch.disconnect()
                out
            }
            FileLogger.log(TAG, "Stop hook setup: $result")
        } catch (e: Exception) {
            FileLogger.error(TAG, "Stop hook setup failed: ${e.message}", e)
        }
    }

    /**
     * Register [sessionId] with the SHARED per-server Stop-hook watcher and
     * make sure that watcher is running. The watcher holds ONE `tail -f
     * /tmp/claude-notify` exec channel per server (all sessions share the
     * file) and dispatches each marker to the owning session by tmux name,
     * firing [onNeedsInput]. Registered sessions are marked hook-active
     * in the detector so screen-state polling is skipped.
     *
     * If the watcher channel drops (SSH reconnect), screen-state fallback
     * resumes automatically via [markHookInactive]; on reconnect the last 25
     * marker lines are replayed (stale-filtered + deduped) so completions
     * that happened during the gap still notify.
     */
    fun startNotifyWatcher(sessionId: String, tmuxName: String, serverId: String) {
        while (true) {
            val w = serverNotifyWatchers.getOrPut(serverId) { ServerNotifyWatcher(serverId) }
            val registered = synchronized(w) {
                // Whole register sequence under the watcher's monitor, and
                // re-checked against the map: a concurrent last-session
                // disconnect may have just emptied + removed this instance —
                // registering into the removed orphan would leave the session
                // served by a watcher nobody can ever cancel (and a later
                // attach would mint a SECOND tail on the same file).
                if (serverNotifyWatchers[serverId] !== w) return@synchronized false
                // Re-point this session's registration (a relaunched tab may
                // have a new tmux name — drop the stale mapping first).
                w.tmuxToSession.entries.removeAll { it.value == sessionId && it.key != tmuxName }
                w.tmuxToSession[tmuxName] = sessionId
                // Watcher already tailing → hook detection is live for this
                // session now. Under the monitor so it can't interleave with
                // the connect fan-out and miss the one-shot activation.
                if (w.live) setHookActive(sessionId, true)
                if (w.job?.isActive != true) {
                    w.job = scope.launch { runServerNotifyWatcher(w) }
                }
                true
            }
            if (registered) return
        }
    }

    private suspend fun runServerNotifyWatcher(w: ServerNotifyWatcher) = kotlinx.coroutines.coroutineScope {
        // Retry loop: the exec channel can die silently (mobile networks,
        // HyperOS battery management kill the socket without dropping the
        // main SSH channel). Previously the watcher exited permanently and
        // hook-based detection was gone until a FULL transport reconnect —
        // background sessions then had no detection path at all.
        var attempt = 0
        while (isActive && w.tmuxToSession.isNotEmpty()) {
            // Hoisted so the finally can ALWAYS disconnect it — if connect() or
            // readLine() throws, a channel declared inside the try would leak
            // on the shared long-lived session, one per retry.
            var ch: com.jcraft.jsch.ChannelExec? = null
            try {
                val sshSession = registry.liveServerSession(w.serverId)
                if (sshSession == null) {
                    // No live connection right now (mid-reconnect). Keep probing
                    // — the shared watcher rides ANY session's connection, so it
                    // resumes as soon as the first tab on this server is back.
                    kotlinx.coroutines.delay(5_000)
                    continue
                }
                ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                // Emit the server clock first (learns phone↔server skew), then
                // tail INCLUDING the last 25 lines: markers appended while the
                // watcher was down (channel drop + retry backoff) used to be
                // lost forever — a completion during a reconnect window never
                // notified. The stale filter + marker-epoch dedup below make
                // replaying the recent backlog safe.
                ch.setCommand("echo claude-remote-clock \$(date +%s); touch /tmp/claude-notify && tail -n 25 -f /tmp/claude-notify")
                ch.inputStream = null
                val reader = ch.inputStream.bufferedReader()
                ch.connect(5000)

                // Under the monitor so a session registering right now can't
                // slip between `live = true` and the fan-out and miss both.
                synchronized(w) {
                    w.live = true
                    w.tmuxToSession.values.forEach { setHookActive(it, true) }
                }
                attempt = 0
                // Offset (ms) between this device's clock and the server's,
                // learned from the "claude-remote-clock <epoch>" line the
                // channel emits first (echo runs before tail, so it always
                // precedes any marker). Lets us reject stale markers by AGE
                // despite clock skew.
                var clockSkewMs: Long? = null
                FileLogger.log(TAG, "Notify watcher started for server ${w.serverId} (${w.tmuxToSession.size} sessions)")

                while (isActive && ch.isConnected) {
                    val line = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        reader.readLine()
                    } ?: break
                    // Server clock probe (emitted once, first): learn skew.
                    if (line.startsWith("claude-remote-clock")) {
                        line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()?.let { serverEpochSec ->
                            clockSkewMs = System.currentTimeMillis() - serverEpochSec * 1000L
                        }
                        continue
                    }
                    if (!line.startsWith("claude-remote-notify")) continue
                    // EXACT tmux-session match. The marker line is
                    // "claude-remote-notify <#S> <epoch>" and ALL sessions
                    // share /tmp/claude-notify, so a substring match cross-
                    // fired between sessions whose names are prefixes of each
                    // other (e.g. "cashy" matched "cashy-test"). Dispatch on the
                    // second whitespace token exactly.
                    val parts = line.trim().split(Regex("\\s+"))
                    val markerTmux = parts.getOrNull(1) ?: continue
                    val sessionId = w.tmuxToSession[markerTmux] ?: continue
                    // Reject STALE completions. /tmp/claude-notify is append-only
                    // and we now REPLAY the last 25 lines on every (re)connect;
                    // plus on a mobile / HyperOS socket freeze the tail's
                    // buffered bytes flush all at once when the phone wakes.
                    // The marker carries the Stop hook's epoch — drop anything
                    // older than notifyStaleMs (skew-corrected). Skew defaults to
                    // 0 (NTP assumption) if the clock line was somehow lost:
                    // fail-open would re-fire the whole replayed backlog.
                    val markerEpochSec = parts.getOrNull(2)?.toLongOrNull()
                    if (markerEpochSec != null) {
                        val skew = clockSkewMs ?: 0L
                        val ageMs = System.currentTimeMillis() - (markerEpochSec * 1000L + skew)
                        if (ageMs > notifyStaleMs) {
                            FileLogger.log(TAG, "Skipping stale Stop hook for $sessionId (age ${ageMs}ms): $line")
                            continue
                        }
                    }
                    FileLogger.log(TAG, "Stop hook fired for $sessionId: $line")
                    // Atomic monotonic claim: only the FIRST observer of a
                    // strictly-newer epoch proceeds; racing coroutines (watcher
                    // restart) and tail-replays of same/older epochs bail here,
                    // BEFORE the expensive body poll. This is now the primary
                    // dedup for Stop events — fireNeedsInput's eventKey/debounce
                    // stays as a secondary guard. Malformed lines (null epoch)
                    // fall through to that guard unchanged.
                    if (markerEpochSec != null) {
                        val claimed = synchronized(lastNotifiedEpochSec) {
                            val prev = lastNotifiedEpochSec[sessionId]
                            if (prev != null && markerEpochSec <= prev) false
                            else { lastNotifiedEpochSec[sessionId] = markerEpochSec; true }
                        }
                        if (!claimed) {
                            FileLogger.log(TAG, "Suppressed duplicate/stale Stop hook for $sessionId (epoch $markerEpochSec <= last notified)")
                            continue
                        }
                    }
                    // Resolve the notification body from the just-finished turn.
                    // Poll the transcript until it advances PAST the message we
                    // sent last time (lastNotifiedAssistantId) — on a slow/
                    // background socket the new turn may not be parsed for a
                    // moment, and sending the prior turn's text is exactly the
                    // "previous round's message" bug. If it doesn't advance in
                    // time, send NO body (null) and let the platform fall back
                    // to the generic hint. Cap total latency ~3 s so a genuinely
                    // fresh alert isn't delayed waiting on a frozen socket.
                    val stream = streamOrNull(sessionId)
                    var body: String? = null
                    if (stream != null) {
                        val prevId = lastNotifiedAssistantId[sessionId]
                        kotlinx.coroutines.withTimeoutOrNull(3_000) {
                            while (true) {
                                scope.launch { stream.pollNow() }.join()
                                val entry = lastAssistantEntry(sessionId)
                                if (entry != null && entry.id != prevId) { body = entry.text; break }
                                kotlinx.coroutines.delay(400)
                            }
                        }
                        body?.let { lastAssistantEntry(sessionId)?.let { e -> lastNotifiedAssistantId[sessionId] = e.id } }
                    }
                    val isActiveTab = tabManager.activeTabId.value == sessionId
                    // Dedup on the MARKER itself — (tmux, epoch) identifies one
                    // completion. Replays of an already-notified marker are
                    // suppressed exactly, and a NEW completion can never be
                    // swallowed because the transcript hadn't caught up (the
                    // old lastAssistantId-based key was stale precisely when
                    // the network was slow).
                    fireNeedsInput(
                        sessionId, "Claude is ready for input", isActiveTab,
                        eventKey = markerEpochSec?.let { "stop#$markerTmux#$it" },
                        body = body,
                    )
                    updateActivity(sessionId, SessionActivity.WAITING_FOR_INPUT)
                }
            } catch (e: Exception) {
                FileLogger.error(TAG, "Notify watcher failed for server ${w.serverId}: ${e.message}", e)
            } finally {
                try { ch?.disconnect() } catch (_: Exception) {}
                synchronized(w) {
                    w.live = false
                    w.tmuxToSession.values.forEach { setHookActive(it, false) }
                }
            }
            if (!isActive || w.tmuxToSession.isEmpty()) break
            attempt++
            val backoffMs = (5_000L * attempt).coerceAtMost(30_000L)
            FileLogger.log(TAG, "Notify watcher retrying for server ${w.serverId} in ${backoffMs}ms (attempt $attempt)")
            kotlinx.coroutines.delay(backoffMs)
        }
        FileLogger.log(TAG, "Notify watcher stopped for server ${w.serverId}")
    }

    // ---- Offline input queue ----

    /**
     * Enqueue [data] for [sessionId] while offline. Reproduces the orchestrator's
     * queueInput buffer-append verbatim and returns the new queue size so the
     * caller can render its "Queued (N pending)" message.
     */
    fun enqueue(sessionId: String, data: String): Int {
        val queue = pendingInputs.getOrPut(sessionId) { mutableListOf() }
        queue.add(data)
        _pendingCounts.update { it + (sessionId to queue.size) }
        return queue.size
    }

    /**
     * Remove and return [sessionId]'s pending queue (flush on reconnect).
     * Reproduces the orchestrator's `pendingInputs.remove(...)` +
     * `_pendingCounts.update { it - sessionId }` pair verbatim.
     */
    fun drain(sessionId: String): List<String>? =
        pendingInputs.remove(sessionId)?.also { _pendingCounts.update { it - sessionId } }

    /** Public clear (user cancelled the queue). Reproduces the orchestrator's
     *  clearPendingInputs body verbatim. */
    fun clearPendingInputs(sessionId: String) {
        pendingInputs.remove(sessionId)
        _pendingCounts.update { it - sessionId }
    }

    // ---- Disconnect teardown helpers (each reproduces one orchestrator line
    // at its exact position; do NOT reorder relative to the facade's teardown). ----

    /** Reproduces the four per-session notify dedup removes, in order. */
    fun clearNotifyDedup(sessionId: String) {
        lastNeedsInputAt.remove(sessionId)
        lastNotifiedKey.remove(sessionId)
        // Only on permanent forget — NOT on transient disconnect/reconnect, or
        // a tail replay after reconnect would re-fire an already-notified epoch.
        lastNotifiedEpochSec.remove(sessionId)
        lastNotifiedAssistantId.remove(sessionId)
    }

    /** Reproduces the disconnect-time `pendingInputs.remove(sessionId)` line. */
    fun removePendingInputs(sessionId: String) { pendingInputs.remove(sessionId) }

    /** Reproduces the disconnect-time `_pendingCounts.update { it - sessionId }` line. */
    fun clearPendingCount(sessionId: String) { _pendingCounts.update { it - sessionId } }

    /**
     * Unregister [sessionId] from its server's Stop-hook watcher on disconnect —
     * reproduces the orchestrator's per-server teardown block VERBATIM under the
     * watcher's monitor (removeAll → conditional remove(key,value) → cancel).
     */
    fun unregisterNotifyWatcher(serverId: String, sessionId: String) {
        serverNotifyWatchers[serverId]?.let { w ->
            synchronized(w) {
                w.tmuxToSession.entries.removeAll { it.value == sessionId }
                // Conditional remove(key, value): only cancel if this exact
                // instance is still the registered watcher AND nobody
                // re-registered between the removeAll and here (both are
                // under w's monitor, matching startNotifyWatcher).
                if (w.tmuxToSession.isEmpty() && serverNotifyWatchers.remove(serverId, w)) {
                    w.job?.cancel()
                }
            }
        }
    }
}
