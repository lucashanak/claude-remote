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
 * Where the Stop hook appends its completion markers, and where the watcher
 * tails from. Moved off `/tmp/claude-notify`: that lives in a world-writable
 * directory, so on a shared host any local user could read the user's tmux
 * session names out of it — or append forged marker lines and raise
 * notifications on the user's phone. The per-user cache dir with mode 600
 * closes both. Servers still carrying the old `/tmp` hook migrate on the next
 * connect: the install script rewrites any marker entry that differs from the
 * one it wants.
 */
private const val MARKER_PATH = "~/.cache/claude-remote/notify"
private const val MARKER_DIR = "~/.cache/claude-remote"

/**
 * The pre-migration marker file. Still tailed because an already-running
 * `claude` keeps using the hook it snapshotted at startup; see the watcher.
 * Remove both this and that half of the tail once servers have cycled.
 */
private const val LEGACY_MARKER_PATH = "/tmp/claude-notify"

/** Rotate the marker file when it passes this many lines, down to [MARKER_KEEP_LINES]. */
private const val MARKER_MAX_LINES = 1000
private const val MARKER_KEEP_LINES = 200

/**
 * Single-quote a path for the remote shell. A leading `~` must stay OUTSIDE the
 * quotes or the shell hands the literal string to `mkdir`; everything after it
 * is quoted, so a home directory with a space or an apostrophe still works.
 */
private fun shellQuote(path: String): String {
    val (prefix, rest) = if (path.startsWith("~/")) "~/" to path.substring(2) else "" to path
    return prefix + "'" + rest.replace("'", "'\\''") + "'"
}

/**
 * Body of a Stop-hook notification when no assistant text could be resolved.
 * Taken from the detector's own INPUT_PROMPT hint so the two phrasings — and
 * the language they are in — cannot drift apart.
 */
private val READY_HINT = PromptType.INPUT_PROMPT.displayHint

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
    // Bridge to transcriptService.entries — the Stop path needs the whole entry
    // list, not just the last assistant message, to tell "answers THIS turn"
    // from "was already in the backlog" (see StopBodySelector).
    private val entriesOf: (String) -> List<TranscriptEntry>,
    // Bridge to transcriptService.streamOrNull — the notify watcher polls it.
    private val streamOrNull: (String) -> TranscriptStream?,
    // Bridge to the facade's public `var onClaudeNeedsInput`.
    private val onNeedsInput: (String, String, Boolean, String?) -> Unit,
) {
    // Prompt detection for notifications — quiescence-based, reads rendered screen state.
    internal val promptDetector = InputPromptDetector().apply {
        onDetection = { det ->
            val isActive = tabManager.activeTabId.value == det.sessionId
            // Key on the detection COUNTER, not on the last assistant message:
            // consecutive tool permissions inside one turn share the same last
            // message, so the old key made every prompt after the first look
            // like a duplicate and the user was never told.
            fireNeedsInput(
                det.sessionId, det.type.displayHint, isActive,
                eventKey = NotifyKeys.detection(det.type, det.seq),
            )
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
        onLoginExpiryWarning = { sid, warning ->
            _loginExpiryWarnings.update { cur ->
                if (warning == null) cur - sid else cur + (sid to warning)
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
                // The debounce collapses repeats of one event, and a screen
                // -detected PROMPT is never that: answering permission prompt #1
                // and being asked for #2 two seconds later is two things the
                // user must act on, and the window swallowed the second one.
                // Stop markers stay debounced — several fire inside one turn
                // (model handoff, tool retries) and the user experiences them as
                // a single completion. See NotifyKeys.isDebounceExempt.
                val last = lastNeedsInputAt[sessionId] ?: 0L
                if (!NotifyKeys.isDebounceExempt(eventKey) && now - last < notifyDebounceMs) {
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
     * SAME marker file, so one watcher per server replaces N identical
     * long-lived tail -f channels (20 fewer at 21 sessions) and dispatches each
     * marker to its owning session by the exact tmux-name token in the line.
     */
    private class ServerNotifyWatcher(val serverId: String) {
        /** tmux session name → app session id, updated on attach/detach. */
        val tmuxToSession = java.util.concurrent.ConcurrentHashMap<String, String>()
        /** True while the tail channel is connected (hook-based detection active). */
        @Volatile var live = false
        var job: kotlinx.coroutines.Job? = null
        /**
         * The live `tail -f` exec channel. Held here because coroutine
         * cancellation cannot interrupt a blocking stream read: after
         * `job.cancel()` the watcher stays parked inside `readLine()` until the
         * server happens to emit another line — which, for a server whose
         * sessions are all closed, may be never. Disconnecting the channel
         * closes the piped stream, so the read returns and the `finally` runs.
         */
        @Volatile var channel: com.jcraft.jsch.ChannelExec? = null
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
    // Highest Stop-hook marker epoch (seconds) SEEN and NOTIFIED per session.
    // A completion is identified by its epoch, so a marker we have already seen
    // is a replay (tail -n 25 on every reconnect, watcher restart every ~30-45
    // min) and one we have not is a real completion — however old it looks.
    private val notifyEpochs = NotifyEpochTracker()
    // Assistant-message id sent in the last notification per session, so we never
    // resend the previous round's message as a "new" completion body.
    private val lastNotifiedAssistantId = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Tail of each session's marker lane: the Job of the most recently dispatched
    // handler. Each new handler joins it before running, which is what actually
    // makes the lane ORDERED. A Mutex here would only give mutual exclusion:
    // `scope` is Dispatchers.IO, so two launched bodies can reach lock() in
    // either order, and the ordering the watch depends on (alert resolved before
    // the activity flip) would not hold. Chaining is done synchronously on the
    // tail reader, which reads markers in file order, so the chain is FIFO.
    private val sessionNotifyChain = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val sessionNotifyChainLock = Any()
    private val notifyDebounceMs = 5_000L
    /**
     * How long to wait for the transcript to produce this turn's answer before
     * sending the generic hint instead.
     */
    private val bodyPollMs = 3_000L
    /**
     * Absurdity bound on a Stop marker's skew-corrected age. This is NOT the old
     * two-minute stale filter: that dropped exactly the completion the user was
     * waiting for, because a phone whose socket froze reads a real marker
     * minutes late and "old" cannot be told from "replayed". Identity does that
     * now (see [NotifyEpochTracker]); this only rejects a marker file whose
     * epochs are nonsense — a day-old line still gets the activity flip, just
     * not a buzz.
     */
    private val notifyAbsurdAgeMs = 24L * 60 * 60 * 1000
    /**
     * How recent the FIRST marker seen for a session must be to notify rather
     * than merely establish a baseline. Roughly the old stale window, but it now
     * applies once per session per process instead of to every marker, so it
     * cannot swallow the late-arriving completions H3 was about.
     */
    private val firstSightingGraceMs = 120_000L

    // Active Claude `/login` OAuth flow detected on the current screen, or null.
    // Fed by InputPromptDetector.onLoginDetected. The URL is never logged.
    private val _loginFlow = kotlinx.coroutines.flow.MutableStateFlow<com.clauderemote.model.LoginFlowState?>(null)
    val loginFlow: kotlinx.coroutines.flow.StateFlow<com.clauderemote.model.LoginFlowState?> = _loginFlow

    /** Clear the login card for [sessionId] (user submitted the code or cancelled). */
    fun clearLoginFlow(sessionId: String) { _loginFlow.update { if (it?.sessionId == sessionId) null else it } }

    /**
     * Sessions currently showing Claude's renewal banner, keyed by session id.
     * A MAP rather than the single value [loginFlow] uses: every session on the
     * expiring login shows the banner, and the user may be looking at any of
     * them. Entries clear themselves on the next screen check once the banner
     * is gone (i.e. right after a successful `/login`).
     */
    private val _loginExpiryWarnings =
        kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.clauderemote.model.LoginExpiryWarning>>(emptyMap())
    val loginExpiryWarnings: kotlinx.coroutines.flow.StateFlow<Map<String, com.clauderemote.model.LoginExpiryWarning>> =
        _loginExpiryWarnings

    // ---- Claude Code Stop-hook integration ----

    /**
     * Shell command run via SSH exec to ensure `~/.claude/settings.json` on the
     * remote server contains a `Stop` hook that appends to [MARKER_PATH].
     * Uses `python3` for safe JSON merge (preserves all existing content).
     * Idempotent — checks for our marker string before adding.
     *
     * Three things this script must survive, because it runs on EVERY
     * connectSsh (so several tabs on one server race it) against a file Claude
     * Code itself writes:
     *  - a `Stop` value that is not a list (a dict, or a string): iterating it
     *    yielded its keys/characters and the rewrite replaced the user's config
     *    with that garbage. We now refuse to touch a shape we do not understand
     *    and report HOOK_FAILED — screen-scrape detection covers the session,
     *    and the user's configuration survives.
     *  - an unparseable settings.json: reported as HOOK_FAILED instead of a
     *    traceback, and never rewritten from a half-read state.
     *  - a torn write: the new content goes to a temp file in the same
     *    directory and is `os.replace`d in (atomic on POSIX), with an
     *    `flock` spanning the read AND the write so concurrent copies of this
     *    script serialize instead of losing each other's edits.
     */
    private val ENSURE_HOOK_COMMAND = """
        python3 -c "
import json, os, fcntl, shlex, tempfile
p = os.path.expanduser('~/.claude/settings.json')
os.makedirs(os.path.dirname(p), exist_ok=True)
lock = open(p + '.claude-remote.lock', 'a+')
fcntl.flock(lock, fcntl.LOCK_EX)
try:
    d = {}
    if os.path.exists(p):
        try:
            with open(p) as f: d = json.load(f)
        except Exception:
            print('HOOK_FAILED')
            raise SystemExit(0)
    if not isinstance(d, dict): d = {}
    hooks = d.get('hooks')
    if not isinstance(hooks, dict):
        hooks = {}
        d['hooks'] = hooks
    stop = hooks.get('Stop')
    if stop is None: stop = []
    if not isinstance(stop, list):
        # A shape this script does not understand. Rewriting it would silently
        # destroy whatever the user (or a future Claude Code format) put there,
        # so bail and let screen-scrape detection cover the session instead.
        print('HOOK_FAILED')
        raise SystemExit(0)
    marker = 'claude-remote-notify'
    out = os.path.expanduser('$MARKER_PATH')
    q = shlex.quote(out)
    qd = shlex.quote(os.path.dirname(out))
    cmd = \"mkdir -p %s && touch %s && chmod 600 %s && echo claude-remote-notify \$(tmux -u display-message -p '#S' 2>/dev/null || echo unknown) \$(date +%%s) >> %s\" % (qd, q, q, q)
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
        mode = (os.stat(p).st_mode & 0o777) if os.path.exists(p) else 0o600
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(p), prefix='.claude-remote-settings-')
        with os.fdopen(fd, 'w') as f: json.dump(d, f, indent=2)
        os.chmod(tmp, mode)
        os.replace(tmp, p)
        print('HOOK_FIXED')
finally:
    fcntl.flock(lock, fcntl.LOCK_UN)
    lock.close()
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
     * <marker file>` exec channel per server (all sessions share the
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
        // Seed "the message we last notified for" with whatever the transcript
        // already holds. Without this the id is null on the first Stop marker
        // after a cold start, the backlog's last assistant message trivially
        // differs from null, and the PREVIOUS turn's answer is adopted as the
        // body with zero waiting — the reported "notification shows the old
        // answer" bug. putIfAbsent so a reconnect never rewinds a live session.
        lastAssistantEntry(sessionId)?.id?.let { lastNotifiedAssistantId.putIfAbsent(sessionId, it) }
        while (true) {
            // computeIfAbsent, not getOrPut: the latter is get-then-put and two
            // attaching sessions could each install their own watcher for one
            // server. The re-check below would still catch it, but only after a
            // second tail had been minted on the same file.
            val w = serverNotifyWatchers.computeIfAbsent(serverId) { ServerNotifyWatcher(serverId) }
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
                // notified. The per-session epoch identity below makes replaying
                // the recent backlog safe. The marker file is created here too,
                // with mode 600, so the first tail on a fresh server works and
                // the file never appears world-readable even for an instant.
                //
                // BOTH paths are tailed. Claude Code snapshots its hooks at
                // process start, so every `claude` already running in tmux keeps
                // appending to the old world-writable /tmp file no matter what
                // we write to settings.json — reading only the new path would
                // kill completion detection for every pre-existing session until
                // the user restarted Claude in each pane. `-q` suppresses the
                // "==> file <==" headers so the parser below is unchanged, `-F`
                // retries a path that does not exist yet, and a marker that
                // somehow lands in both files is dropped by epoch identity.
                // DROP the /tmp path (and this comment) once every server has
                // been through a Claude restart. The old file is deliberately
                // NOT deleted or truncated here: other processes still append
                // to it, and removing a file out from under them is worse than
                // leaving it.
                //
                // Rotation runs BEFORE tail starts, never while it follows, so
                // tail cannot see a truncation and replay from the top.
                val marker = shellQuote(MARKER_PATH)
                ch.setCommand(
                    "echo claude-remote-clock \$(date +%s); " +
                        "mkdir -p ${shellQuote(MARKER_DIR)} && touch $marker && chmod 600 $marker; " +
                        "if [ \"\$(wc -l < $marker)\" -gt $MARKER_MAX_LINES ]; then " +
                        "tail -n $MARKER_KEEP_LINES $marker > $marker.rot && mv $marker.rot $marker && " +
                        "chmod 600 $marker; fi; " +
                        "tail -q -n 25 -F $marker ${shellQuote(LEGACY_MARKER_PATH)}"
                )
                ch.inputStream = null
                val reader = ch.inputStream.bufferedReader()
                ch.connect(5000)

                // Under the monitor so a session registering right now can't
                // slip between `live = true` and the fan-out and miss both —
                // and so `channel` is visible to unregisterNotifyWatcher before
                // anything can observe this watcher as live. Publishing it
                // outside left a window where a last-session disconnect
                // cancelled the job, saw a null channel, and left the coroutine
                // parked in readLine() forever: the exact leak this closes.
                synchronized(w) {
                    w.channel = ch
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
                    // "claude-remote-notify <#S> <epoch>" and ALL sessions on a
                    // server share one marker file, so a substring match cross-
                    // fired between sessions whose names are prefixes of each
                    // other (e.g. "cashy" matched "cashy-test"). Dispatch on the
                    // second whitespace token exactly.
                    val parts = line.trim().split(Regex("\\s+"))
                    val markerTmux = parts.getOrNull(1) ?: continue
                    val sessionId = w.tmuxToSession[markerTmux] ?: continue
                    val markerEpochSec = parts.getOrNull(2)?.toLongOrNull()
                    // Identity, not age. The marker file is append-only and we
                    // replay its last 25 lines on every (re)connect, so a marker
                    // we have already SEEN is a replay and is dropped; one we
                    // have not seen is a completion the user is waiting on and
                    // notifies however late it arrives — a frozen socket
                    // flushing minutes of buffered bytes is indistinguishable
                    // from a replay by age, which is how the old two-minute
                    // filter killed exactly the notification that mattered.
                    // Malformed lines (null epoch) fall through to
                    // fireNeedsInput's own dedup unchanged.
                    var claim: NotifyEpochTracker.Claim? = null
                    if (markerEpochSec != null) {
                        val ageMs = System.currentTimeMillis() - (markerEpochSec * 1000L + (clockSkewMs ?: 0L))
                        // FIRST sighting for this session in this process. With
                        // no baseline, "never seen" and "newer than anything
                        // seen" are the same thing, so the replayed backlog of a
                        // file shared by every session on the server would buzz
                        // the user once per session for completions they
                        // answered hours ago. Record it as seen, flip the dot,
                        // stay quiet. From the second marker on, identity alone
                        // decides — so a genuinely late marker still notifies,
                        // which is the case the age filter used to break.
                        when (val outcome = notifyEpochs.observe(
                            sessionId, markerEpochSec, ageMs, firstSightingGraceMs,
                        )) {
                            is NotifyEpochTracker.Outcome.Primed -> {
                                FileLogger.log(TAG, "Priming Stop-hook baseline for $sessionId (epoch $markerEpochSec, age ${ageMs}ms) — activity only")
                                // Seed the message id too, so the first REAL
                                // notification for this session has the id gate
                                // behind it and not just the timestamp bounds.
                                lastAssistantEntry(sessionId)?.id
                                    ?.let { lastNotifiedAssistantId.putIfAbsent(sessionId, it) }
                                flipToWaiting(sessionId)
                                continue
                            }
                            is NotifyEpochTracker.Outcome.Replay -> {
                                FileLogger.log(TAG, "Suppressed replayed Stop hook for $sessionId (epoch $markerEpochSec <= last seen)")
                                continue
                            }
                            is NotifyEpochTracker.Outcome.Claimed -> claim = outcome.claim
                        }
                        // Sanity bound only — see notifyAbsurdAgeMs. The session
                        // really is idle, so still flip the dot; just don't buzz.
                        if (ageMs > notifyAbsurdAgeMs) {
                            FileLogger.log(TAG, "Stop hook for $sessionId is ${ageMs}ms old — activity only, no notification")
                            flipToWaiting(sessionId)
                            continue
                        }
                    }
                    FileLogger.log(TAG, "Stop hook fired for $sessionId: $line")
                    // Hand the marker to its own coroutine: resolving the body
                    // can take up to bodyPollMs, and doing that here stalled the
                    // tail reader — markers for OTHER sessions on this server
                    // queued behind it and their activity dots lagged by the
                    // same amount.
                    val claimForSession = claim
                    onSessionLane(sessionId) {
                        handleStopMarker(sessionId, markerTmux, markerEpochSec, claimForSession)
                    }
                }
            } catch (e: Exception) {
                FileLogger.error(TAG, "Notify watcher failed for server ${w.serverId}: ${e.message}", e)
            } finally {
                w.channel = null
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

    /**
     * Run [block] on [sessionId]'s marker lane: off the tail reader, after
     * everything already dispatched for this session, and before anything
     * dispatched later. Call it from the tail reader only — the chaining is what
     * fixes the order, and it is only FIFO because that one thread does it.
     */
    private fun onSessionLane(sessionId: String, block: suspend () -> Unit) {
        synchronized(sessionNotifyChainLock) {
            val previous = sessionNotifyChain[sessionId]
            sessionNotifyChain[sessionId] = scope.launch {
                // join() returns for a failed or cancelled predecessor too, so
                // one bad marker cannot wedge the lane.
                previous?.join()
                block()
            }
        }
    }

    /** Activity-only outcome for a marker we deliberately do not notify for.
     *  Goes through the lane so it can't overtake an in-flight handler's flip
     *  for the same session. */
    private fun flipToWaiting(sessionId: String) =
        onSessionLane(sessionId) { updateActivity(sessionId, SessionActivity.WAITING_FOR_INPUT) }

    /**
     * One Stop marker, end to end: resolve the body for the turn that just
     * finished, notify, then flip the activity. Runs on its own coroutine,
     * serialized per session by the caller.
     *
     * ORDER MATTERS and must not be "optimized": the watch notifies on the
     * activity transition and reads the message text at that moment, so the
     * activity flip has to come AFTER the body has been resolved and the phone
     * alert dispatched. Flipping early is exactly how the wrist ended up
     * buzzing with the previous turn's answer.
     */
    private suspend fun handleStopMarker(
        sessionId: String,
        markerTmux: String,
        markerEpochSec: Long?,
        claim: NotifyEpochTracker.Claim?,
    ) {
        var dispatched = false
        try {
            val stream = streamOrNull(sessionId)
            var body: String? = null
            if (stream != null) {
                // Lazy seed. The eager seed in startNotifyWatcher runs before
                // the transcript stream exists (attachSessionRuntime registers
                // the watcher first, and the backlog then loads over SSH), so on
                // the FIRST marker of a process it stored nothing and the id
                // gate was vacuous — exactly the cold start the stale-body bug
                // was reported on. Read it here instead, before the first poll,
                // so whatever the backlog already holds counts as "already
                // seen" rather than as this turn's answer.
                val prevId = lastNotifiedAssistantId[sessionId]
                    ?: if (claim?.prevSeen == null) {
                        lastAssistantEntry(sessionId)?.id
                            ?.also { lastNotifiedAssistantId.putIfAbsent(sessionId, it) }
                    } else null
                // The previous completion for this session bounds the answer to
                // this one: a turn's answer cannot predate the end of the turn
                // before it. Unlike the id gate this works on a cold start.
                val prevMarkerEpochSec = claim?.prevSeen
                kotlinx.coroutines.withTimeoutOrNull(bodyPollMs) {
                    while (true) {
                        // Called directly, not via scope.launch{}.join(): inside
                        // withTimeoutOrNull the poll is then cancelled when the
                        // budget runs out instead of being left running with
                        // nobody waiting for it.
                        stream.pollNow()
                        val entry = StopBodySelector.select(
                            entriesOf(sessionId), prevId, markerEpochSec, prevMarkerEpochSec,
                        )
                        if (entry != null) {
                            // Record the id of the entry we are ACTUALLY sending,
                            // not a fresh re-read: the background poller can append
                            // a newer message in that window, and storing its id
                            // meant the next turn had to advance past a message the
                            // user was never shown before it could produce a body.
                            body = entry.text
                            lastNotifiedAssistantId[sessionId] = entry.id
                            break
                        }
                        kotlinx.coroutines.delay(400)
                    }
                }
                if (body == null) {
                    // The poll timed out, so this completion goes out with the
                    // generic hint. Still record where the transcript stands, or
                    // the id stays a turn behind and the NEXT marker adopts the
                    // message we failed to send as if it were the new turn's
                    // answer — one timeout poisoning every turn after it.
                    StopBodySelector.idToRecordOnTimeout(entriesOf(sessionId))
                        ?.let { lastNotifiedAssistantId[sessionId] = it }
                }
            }
            val isActiveTab = tabManager.activeTabId.value == sessionId
            fireNeedsInput(
                sessionId, READY_HINT, isActiveTab,
                eventKey = markerEpochSec?.let { NotifyKeys.stop(markerTmux, it) },
                body = body,
            )
            dispatched = true
            // Outside the dispatch guard on purpose: a completion the debounce
            // swallowed is still a completion, and the dot must say so.
            updateActivity(sessionId, SessionActivity.WAITING_FOR_INPUT)
        } catch (e: Exception) {
            // Give the epoch back if we never got as far as notifying, so the
            // next tail replay retries it. Without this the claim made on behalf
            // of a notification that never happened suppresses it forever.
            if (!dispatched && claim != null) notifyEpochs.rollback(claim)
            // Shutdown is not a failure, and swallowing it here would break
            // cancellation for everything waiting on this session's lock.
            if (e is kotlinx.coroutines.CancellationException) throw e
            FileLogger.error(TAG, "Stop hook handling failed for $sessionId: ${e.message}", e)
        }
    }

    // ---- Offline input queue ----

    /**
     * Enqueue [data] for [sessionId] while offline. Reproduces the orchestrator's
     * queueInput buffer-append verbatim and returns the new queue size so the
     * caller can render its "Queued (N pending)" message.
     */
    fun enqueue(sessionId: String, data: String): Int {
        // computeIfAbsent: two concurrent enqueues on a first touch would each
        // build their own list under getOrPut and one input would be dropped.
        val queue = pendingInputs.computeIfAbsent(sessionId) { mutableListOf() }
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

    /** Drop every per-session notify dedup slot. */
    fun clearNotifyDedup(sessionId: String) {
        // Under the SAME monitor fireNeedsInput uses. These two are plain
        // LinkedHashMaps and this runs on an arbitrary dispatcher (via
        // disconnectSession) while the shared per-server watcher may be inside
        // fireNeedsInput for a different session — an unsynchronized structural
        // modification during a resize can drop unrelated entries or corrupt a
        // bucket chain, leaving a session that never dedups or always does.
        synchronized(lastNotifiedKey) {
            lastNeedsInputAt.remove(sessionId)
            lastNotifiedKey.remove(sessionId)
        }
        // Only on permanent forget — NOT on transient disconnect/reconnect, or
        // a tail replay after reconnect would re-fire an already-notified epoch.
        notifyEpochs.clear(sessionId)
        lastNotifiedAssistantId.remove(sessionId)
        // Safe to drop even with a handler still in flight: that handler holds
        // its own Job, and this runs only on permanent forget — disconnectSession
        // has already unregistered the session from its watcher, so no further
        // marker can be dispatched for it and nothing can race the in-flight one.
        sessionNotifyChain.remove(sessionId)
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
                if (w.tmuxToSession.isEmpty()) {
                    // Conditional remove(key, value): a false return means
                    // another instance already replaced this one in the map, so
                    // THIS one is an orphan with no sessions and no owner —
                    // cancel it either way, or it stays parked forever.
                    serverNotifyWatchers.remove(serverId, w)
                    w.job?.cancel()
                    // Cancellation alone cannot interrupt the blocking readLine
                    // the watcher is parked in; closing the channel closes the
                    // piped stream so the read returns and the finally (which
                    // clears the hook-active flags) actually runs.
                    try { w.channel?.disconnect() } catch (_: Exception) {}
                }
            }
        }
    }
}
