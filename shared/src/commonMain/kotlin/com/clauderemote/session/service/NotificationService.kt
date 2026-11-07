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
internal const val MARKER_PATH = "~/.cache/claude-remote/notify"
private const val MARKER_DIR = "~/.cache/claude-remote"

/**
 * The pre-migration marker file. Still tailed because claude-local sessions keep
 * the legacy hook that appends here, and sessions started before the move may not
 * have reloaded their settings; see the watcher. Remove both this and that half of
 * the tail only when no config on the box carries the /tmp hook any more.
 */
internal const val LEGACY_MARKER_PATH = "/tmp/claude-notify"

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

/** `~/.claude-remote/forget.log`: one line per tombstone any device wrote (see ManifestCommands.tombstone). */
private const val FORGET_LOG_PATH = "~/.claude-remote/forget.log"

/** CAP=1: terminal silence after which a WORKING session without a hook `stop` counts as ready. */
private const val SILENCE_DROP_MS = 10_000L

/** Rotate the events file at most once per 24 h, so concurrent readers are not disturbed every few minutes. */
private const val EVENTS_ROTATE_MIN_INTERVAL_MIN = 1440

/**
 * The shell command of the per-server watcher channel.
 *
 * Without [withHooks] it is the legacy command, character for character. With it,
 * the same channel also follows the hook events file and the forget log, and
 * rotates the events file (> [MARKER_MAX_LINES] lines, keep [MARKER_KEEP_LINES],
 * at most once per 24 h, BEFORE `tail` starts so it never sees a truncation).
 * `tail -n 25` is per file, so the Stop replay is never starved by the others.
 *
 * BOTH marker paths are tailed: the old /tmp file is still appended to by
 * claude-local sessions (their own settings carry the legacy hook) and by sessions
 * that have not reloaded settings since the move. `-q` suppresses the
 * "==> file <==" headers, `-F` retries a path that does not exist yet, and a
 * marker that lands in both files is dropped by epoch identity. The old file is
 * deliberately NOT deleted or truncated: other processes still append to it.
 */
internal fun buildNotifyWatcherCommand(withHooks: Boolean): String {
    val marker = shellQuote(MARKER_PATH)
    val head = "echo claude-remote-clock \$(date +%s); " +
        "mkdir -p ${shellQuote(MARKER_DIR)} && touch $marker && chmod 600 $marker; " +
        "if [ \"\$(wc -l < $marker)\" -gt $MARKER_MAX_LINES ]; then " +
        "tail -n $MARKER_KEEP_LINES $marker > $marker.rot && mv $marker.rot $marker && " +
        "chmod 600 $marker; fi; "
    if (!withHooks) return head + "tail -q -n 25 -F $marker ${shellQuote(LEGACY_MARKER_PATH)}"
    val ev = shellQuote(HookCommands.EVENTS_PATH)
    return head +
        "touch $ev && chmod 600 $ev; " +
        "if [ -z \"\$(find $ev.rotated -mmin -$EVENTS_ROTATE_MIN_INTERVAL_MIN 2>/dev/null)\" ] && " +
        "[ \"\$(wc -l < $ev)\" -gt $MARKER_MAX_LINES ]; then " +
        "tail -n $MARKER_KEEP_LINES $ev > $ev.rot && mv $ev.rot $ev && chmod 600 $ev && touch $ev.rotated; fi; " +
        "tail -q -n 25 -F $marker ${shellQuote(LEGACY_MARKER_PATH)} $ev ${shellQuote(FORGET_LOG_PATH)}"
}

/**
 * Body of a Stop-hook notification when no assistant text could be resolved.
 * Taken from the detector's own INPUT_PROMPT hint so the two phrasings — and
 * the language they are in — cannot drift apart.
 */
private val READY_HINT = PromptType.INPUT_PROMPT.displayHint

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
internal val LEGACY_STOP_HOOK_COMMAND: String = """
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
    // Bridge to the facade's public `var onClaudeNeedsInput`. The last argument names the prompt
    // the alert is for, so a reply from the notification or the watch can be checked against it.
    private val onNeedsInput: (String, NotifyKind, String, Boolean, String?, PromptFingerprint?) -> Unit,
    // The session's current activity: a live v2 `stop` may only LEAVE an approval, never overwrite
    // the ready state the legacy Stop marker (which lands first) has already set.
    private val activityOf: (String) -> SessionActivity? = { null },
    // statusService.promptTokens: the newest prompt of each session (ReplyGuard).
    private val promptTokens: PromptTokens = PromptTokens(),
    // The hook reducer saw a pending perm/ask answered (the pane's next work/askdone/stop/end).
    private val onApprovalAnswered: (String) -> Unit = {},
    // Every applied hook event of a session: `end` (Claude exited, the pane is back at its shell)
    // with when it fired (device clock), any other event with null (Claude runs again).
    private val onClaudeEnded: (sessionId: String, endedAtMs: Long?) -> Unit = { _, _ -> },
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
                det.sessionId, NotifyKind.ofPrompt(det.type), det.type.displayHint, isActive,
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
        // A screen that shows no dialog (input box or spinner) ends the hold behind an announced
        // hook approval (PromptDialogDedup): the dialog was answered somewhere. Only a screen that
        // showed the dialog before, or got output after it, can tell (screenShowsNoDialog).
        onScreenSeen = { sid, st ->
            when (st) {
                ClaudeState.APPROVAL -> dialogDedup.dialogSeen(sid)
                ClaudeState.IDLE, ClaudeState.WORKING -> dialogDedup.screenShowsNoDialog(sid, lastOutputAtMs(sid))
                ClaudeState.UNKNOWN -> {}
            }
        }
        // `hookev.off` reported by the bootstrap of the session's server: CAP=0.
        hookCapVeto = { sid -> hooksSwitchedOff(sid) }
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

    private val dialogDedup = PromptDialogDedup()

    /** Device clock of the notify gates and the hook reducer; a test moves it. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * CAP=1 (see [InputPromptDetector.markHookEvents]): how long the terminal of a WORKING session
     * may stay silent before the dot drops to ready. An interrupt (Escape) emits no hook event at all,
     * and with the statusline out nothing else would end WORKING until the next `stop`; while Claude
     * works its spinner and elapsed-time counter repaint continuously, so a quiet terminal means it
     * stopped. Checked every [silenceTickMs].
     */
    internal var silenceDropMs = SILENCE_DROP_MS
    internal var silenceTickMs = 1_000L

    /** Whether output of the session can reach this device at all: silence on a dead transport says nothing. */
    internal var outputLive: (String) -> Boolean = { sid ->
        registry.et(sid)?.isConnected == true || registry.mosh(sid)?.isConnected == true || registry.ssh(sid)?.isConnected == true
    }

    internal companion object {
        /** CAP=1 ends when the legacy Stop hook fires and no v2 event of the session arrived for this long. */
        const val CAP_STALE_MS = 2L * 60 * 1000
        /** Only an applied event at most this old sets CAP=1: not the hour-old backlog of a watcher's replay. */
        const val CAP_FRESH_MAX_AGE_MS = HookEventReducer.FIRST_SIGHTING_MAX_AGE_MS
    }

    /** Longest wait between two silence ticks while no transport of the session is connected (backoff). */
    internal var silenceDeadTickMaxMs = 30_000L

    /** How long a legacy Stop marker waits for the v2 `stop` (another file of the same tail) before CAP=1 is judged. */
    internal var capStopGraceMs = 5_000L

    /** The bootstrap of [serverId] found `~/.claude-remote/hookev.off`: the hooks emit nothing. */
    internal var hookOffSwitch: (String) -> Boolean = { serverId -> HookInstallControl.shared.status.value[serverId]?.offSwitch == true }

    /** Test seam: the tail of [serverId] connected or lost (the rig has no exec channel). */
    internal fun setWatcherLiveForTest(serverId: String, live: Boolean) {
        serverNotifyWatchers[serverId]?.live = live
    }

    private fun hooksSwitchedOff(sessionId: String): Boolean {
        for (w in serverNotifyWatchers.values) {
            if (w.tmuxToSession.containsValue(sessionId)) return hookOffSwitch(w.serverId)
        }
        return false
    }

    /** A key sent from this app answered the dialog on screen (a digit, `y`, Escape): it is gone. */
    fun noteDialogAnswered(sessionId: String) = dialogDedup.dialogGone(sessionId)

    /**
     * A live legacy Stop marker of [sessionId]: the Stop hook runs. When no v2 event of the session
     * arrived for [CAP_STALE_MS] (the v2 `stop` of this same turn included, after [capStopGraceMs]),
     * the v2 hooks are silent (hookev.off the bootstrap has not seen yet, settings rewritten): CAP=0.
     * Checked only at a turn end, where an event is due: an idle session sends none for hours.
     */
    internal fun noteLegacyStop(sessionId: String) {
        if (!promptDetector.hasHookEvents(sessionId)) return
        scope.launch {
            kotlinx.coroutines.delay(capStopGraceMs)
            val at = promptDetector.hookEventAtMs(sessionId) ?: return@launch
            val silentMs = System.currentTimeMillis() - at
            if (silentMs > CAP_STALE_MS) {
                FileLogger.log(TAG, "Stop hook of $sessionId without any v2 hook event for ${silentMs}ms: CAP=0")
                promptDetector.clearHookEvents(sessionId)
            }
        }
    }

    private val silenceWatches = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val silenceArmedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * A CAP=1 session was just set WORKING (a prompt submitted from this app, a hook `work` /
     * `askdone`): (re)start its silence watch. No-op for a session without hook events, whose
     * statusline still drives the state (and the UI's 45 s stale-WORKING decay stays its only one).
     */
    fun armSilenceWatch(sessionId: String) {
        if (!promptDetector.hasHookEvents(sessionId)) return
        silenceArmedAt[sessionId] = System.currentTimeMillis()
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { runSilenceWatch(sessionId) }
        silenceWatches.put(sessionId, job)?.cancel()
        job.start()
    }

    private suspend fun runSilenceWatch(sessionId: String) {
        val self = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
        var tick = silenceTickMs
        var lastTickAt = System.currentTimeMillis()
        // Since when output can reach this device without a break: silence before it says nothing.
        var liveFrom: Long? = lastTickAt
        try {
            while (true) {
                kotlinx.coroutines.delay(tick)
                if (!promptDetector.hasHookEvents(sessionId)) return
                if (activityOf(sessionId) != SessionActivity.WORKING) return
                val live = outputLive(sessionId)
                val now = System.currentTimeMillis()
                // A tick far later than asked: the process was frozen (Doze) and the transport may only say connected.
                val woke = now - lastTickAt > tick + maxOf(silenceTickMs, silenceDropMs / 2)
                lastTickAt = now
                if (!live) {
                    // Nothing can arrive: no silence is measured, and the watch backs off instead of waking every tick.
                    liveFrom = null
                    tick = minOf(tick * 2, maxOf(silenceTickMs, silenceDeadTickMaxMs))
                    continue
                }
                tick = silenceTickMs
                // Back after a reconnect or a freeze: the re-attach repaint has not arrived yet.
                val from = if (liveFrom == null || woke) now.also { liveFrom = it } else liveFrom!!
                val quietFrom = maxOf(promptDetector.lastOutputAtMs(sessionId) ?: 0L, silenceArmedAt[sessionId] ?: 0L, from)
                val quietMs = now - quietFrom
                if (quietMs >= silenceDropMs) {
                    FileLogger.log(TAG, "No terminal output from $sessionId for ${quietMs}ms while WORKING and no hook stop: ready")
                    updateActivity(sessionId, SessionActivity.WAITING_FOR_INPUT)
                    return
                }
            }
        } finally {
            if (self != null) silenceWatches.remove(sessionId, self)
        }
    }

    /**
     * The reducer's pending hook approval of [sessionId] (server-clock start), whichever server watches
     * it, and whether that server's hooks are live: its tail connected and no `hookev.off` reported.
     */
    private fun hookApprovalOf(sessionId: String): Pair<Long?, Boolean> {
        for (w in serverNotifyWatchers.values) {
            for ((tmux, sid) in w.tmuxToSession) {
                if (sid == sessionId) {
                    w.reducer.approvalSince(tmux)?.let { return it to (w.live && !hookOffSwitch(w.serverId)) }
                }
            }
        }
        return null to false
    }

    private fun dialogSource(eventKey: String?): PromptDialogDedup.Source? = when {
        NotifyKeys.isHook(eventKey) -> PromptDialogDedup.Source.HOOK
        NotifyKeys.isDetection(eventKey) -> PromptDialogDedup.Source.SCREEN
        else -> null
    }

    /** Dispatch [onNeedsInput] no more than once per [notifyDebounceMs] per
     *  session — protects against rapid duplicate fires from the Stop-hook stream
     *  and from the screen-state fallback firing on transient quiescence. */
    private fun fireNeedsInput(
        sessionId: String,
        // What the event asks of the user, from the source that knows it — the
        // platform policy alerts on a completion only when the user asked for it.
        kind: NotifyKind,
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
        val now = clock()
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
        val prompt = NotifyKeys.prompt(eventKey)
        val source = dialogSource(eventKey)
        // Outside lastNotifiedKey's monitor: the reducer has its own lock and never takes this one.
        val (pendingHookApproval, hooksLive) = if (source != null) hookApprovalOf(sessionId) else (null to false)
        val proceed = synchronized(lastNotifiedKey) {
            if (key != null && lastNotifiedKey[sessionId] == key) {
                FileLogger.log(TAG, "Suppressed needs-input for $sessionId (same event)")
                false
            } else if (source != null && !dialogDedup.allow(sessionId, source, now, pendingHookApproval, hooksLive)) {
                // The same dialog was already announced by the other source (hook event vs screen).
                // Its key names that dialog too, so a reply to either alert stays answerable.
                prompt?.let { promptTokens.sameDialog(sessionId, it.key) }
                if (source == PromptDialogDedup.Source.SCREEN && dialogDedup.isHeldByHook(sessionId, pendingHookApproval, now, hooksLive)) {
                    FileLogger.log(TAG, "Suppressed needs-input for $sessionId (the hook approval since $pendingHookApproval it announced is still pending)")
                } else {
                    FileLogger.log(TAG, "Suppressed needs-input for $sessionId (the same dialog was just announced by the other source)")
                }
                false
            } else {
                // The debounce collapses repeats of one event, and a screen
                // -detected PROMPT is never that: answering permission prompt #1
                // and being asked for #2 two seconds later is two things the
                // user must act on, and the window swallowed the second one.
                // Stop markers stay debounced — several fire inside one turn
                // (model handoff, tool retries) and the user experiences them as
                // a single completion. See NotifyKeys.isDebounceExempt.
                // Past both "same prompt" gates: this is the session's newest prompt even when the
                // debounce swallows its alert, so a reply to the previous one no longer matches it.
                prompt?.let { promptTokens.note(sessionId, it, now) }
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
        onNeedsInput(sessionId, kind, hint, isActive, body, prompt)
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
        /** Hook-event state of this server (dedup LRU, per-session order, pending approvals). */
        val reducer = HookEventReducer()
        /** Replay guard for forget.log lines. */
        val forgets = ForgetTracker()
    }
    private val serverNotifyWatchers = java.util.concurrent.ConcurrentHashMap<String, ServerNotifyWatcher>()

    /**
     * Where hook events that reach beyond this service go (latch re-arm on `work`,
     * UUID re-check on `start`, closed-elsewhere check from forget.log). Defaults to
     * a no-op; the orchestrator wiring replaces it.
     */
    @Volatile internal var hookSink: HookEventSink = HookEventSink.NoOp

    /** "This device just closed that session" (forget.log lines we wrote ourselves must not trigger a check). */
    @Volatile internal var closures: PersistenceClosures = PersistenceClosures.None

    /**
     * Server-clock start (epoch ms) of the hook-reported approval pending for
     * [tmuxName] on [serverId], or null. For "asking since 12 min" in the UI and
     * for the policy that a hook approval younger than 10 min survives a tab switch.
     */
    internal fun hookApprovalSince(serverId: String, tmuxName: String): Long? =
        serverNotifyWatchers[serverId]?.reducer?.approvalSince(tmuxName)

    /** Pause between a `perm` / `ask` event and its notification, so an answer that follows in the same replay cancels it. */
    private val hookSettleMs = 250L

    /** A forget.log line of a session this device did not just close; one retry follows the first check. */
    private val forgetRetryMs = 5_000L
    private val forgetCheckDelayMs = 3_000L
    private val closedLocallyWindowMs = 60_000L

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
     * The legacy `Stop` hook ensure ([LEGACY_STOP_HOOK_COMMAND], kept verbatim at file
     * level so the bootstrap can run it inside its single exec). See there.
     */
    private val ENSURE_HOOK_COMMAND = LEGACY_STOP_HOOK_COMMAND

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
                val run: suspend (com.jcraft.jsch.Session) -> String = { sess ->
                    val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
                    ch.setCommand(ENSURE_HOOK_COMMAND)
                    ch.inputStream = null
                    val input = ch.inputStream
                    ch.connect(10_000)
                    val out = input.bufferedReader().readText().trim()
                    ch.disconnect()
                    out
                }
                // The tab's own session: one channel counted where the shells are packed (no room: try with the next connect).
                val serverId = registry.serverIdOfManager(sshManager) ?: return@withContext run(sshSession)
                registry.withTabChannel(serverId, sshSession, "stop-hook", run)?.value ?: "NO_ROOM"
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

    /**
     * Runs [block] on the watcher's long-lived exec channel and disconnects it afterwards. The
     * server's exec transport carries it as a LONG permit when exec routing is on for the server
     * (no permit-less channel on a session that already has all of its permits spoken for);
     * otherwise, or when that transport is not up, it is a channel on a live tab session, as
     * before. False when there is no usable connection at all. [block] runs at most once.
     */
    private suspend fun useWatcherChannel(serverId: String, block: suspend (com.jcraft.jsch.Channel) -> Unit): Boolean {
        val gated = registry.execChannels
        if (gated != null && CommFlags.effectiveFor(serverId).exec) {
            var entered = false
            try {
                gated.use(serverId, ExecKind.LONG) { ch -> entered = true; block(ch) }
                return true
            } catch (e: ExecUnavailable) {
                if (entered || e.reason != ExecUnavailableReason.NOT_UP) throw e
            }
        }
        // A tab session: the LONG channel is counted against its transport for as long as the tail runs where the shells
        // are packed (exec routing off with 8 shells per transport, or the exec transport down); no room = no watcher now.
        return registry.onServerTabChannel(serverId) { session ->
            val ch = session.openChannel("exec")
            try {
                block(ch)
            } finally {
                try { ch.disconnect() } catch (_: Exception) {}
            }
        } != null
    }

    private suspend fun runServerNotifyWatcher(w: ServerNotifyWatcher) = kotlinx.coroutines.coroutineScope {
        // Retry loop: the exec channel can die silently (mobile networks,
        // HyperOS battery management kill the socket without dropping the
        // main SSH channel). Previously the watcher exited permanently and
        // hook-based detection was gone until a FULL transport reconnect —
        // background sessions then had no detection path at all.
        var attempt = 0
        while (isActive && w.tmuxToSession.isNotEmpty()) {
            try {
                // The long-lived tail is ONE stream: it goes through useWatcherChannel (a LONG permit
                // of the server's exec transport when that runs, else a tab's session), never around it.
                val ran = useWatcherChannel(w.serverId) { opened ->
                    val ch = opened as com.jcraft.jsch.ChannelExec
                    try {
                        // Emit the server clock first (learns phone↔server skew), then
                        // tail INCLUDING the last 25 lines: markers appended while the
                        // watcher was down (channel drop + retry backoff) used to be
                        // lost forever — a completion during a reconnect window never
                        // notified. The per-session epoch identity below makes replaying
                        // the recent backlog safe. The marker file is created here too,
                        // with mode 600, so the first tail on a fresh server works and
                        // the file never appears world-readable even for an instant.
                        //
                        // BOTH marker paths are tailed (see buildNotifyWatcherCommand): the
                        // old /tmp one is still written by claude-local sessions, whose
                        // own settings carry the legacy hook, and by sessions that were
                        // started before the move and have not reloaded settings.
                        // DROP it only when no claude-local config carries the /tmp hook
                        // any more. The old file is deliberately NOT deleted or
                        // truncated here: other processes still append to it.
                        //
                        // Rotation runs BEFORE tail starts, never while it follows, so
                        // tail cannot see a truncation and replay from the top.
                        // One decision per (re)connect: comm_hooks OFF (or a legacy / force-off
                        // server) keeps today's command and ignores the new prefixes.
                        val hooksOn = CommFlags.effectiveFor(w.serverId).hooks
                        ch.setCommand(buildNotifyWatcherCommand(hooksOn))
                        ch.inputStream = null
                        val reader = ch.inputStream.bufferedReader()
                        val connectStartedAt = System.currentTimeMillis()
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
                        if (attempt > 0) {
                            // First success after a watcher reconnect: a settings.json that was
                            // rewritten meanwhile (Claude, OMC) is repaired by a fresh ensure.
                            registry.bootstrap?.let { b ->
                                b.invalidate(w.serverId)
                                scope.launch { b.ensure(w.serverId) }
                            }
                        }
                        attempt = 0
                        // Offset (ms) between this device's clock and the server's,
                        // learned from the "claude-remote-clock <epoch>" line the
                        // channel emits first (echo runs before tail, so it always
                        // precedes any marker). Lets us reject stale markers by AGE
                        // despite clock skew.
                        var clockSkewMs: Long? = null
                        // How far that skew can be off: the server's `date +%s` is truncated to the second and
                        // the line only arrives after the exec started (bounded by the time since connect).
                        var clockErrMs = 1_000L
                        FileLogger.log(TAG, "Notify watcher started for server ${w.serverId} (${w.tmuxToSession.size} sessions)")

                        while (isActive && ch.isConnected) {
                            val line = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                reader.readLine()
                            } ?: break
                            // Server clock probe (emitted once, first): learn skew.
                            if (line.startsWith("claude-remote-clock")) {
                                line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()?.let { serverEpochSec ->
                                    clockSkewMs = System.currentTimeMillis() - serverEpochSec * 1000L
                                    clockErrMs = 1_000L + (System.currentTimeMillis() - connectStartedAt)
                                }
                                continue
                            }
                            if (hooksOn) {
                                if (line.startsWith(HookEventLine.PREFIX)) {
                                    dispatchHookEvent(w, line, clockSkewMs ?: 0L, clockErrMs)
                                    continue
                                }
                                if (line.startsWith("[") && line.contains("] forget:")) {
                                    dispatchForgetLine(w, line, clockSkewMs ?: 0L)
                                    continue
                                }
                            }
                            if (!line.startsWith("claude-remote-notify")) continue
                            dispatchStopMarker(w, line, clockSkewMs)
                        }
                    } finally {
                        w.channel = null
                        try { ch.disconnect() } catch (_: Exception) {}
                        synchronized(w) {
                            w.live = false
                            w.tmuxToSession.values.forEach { setHookActive(it, false) }
                        }
                    }
                }
                if (!ran) {
                    // No live connection right now (mid-reconnect). Keep probing
                    // — the shared watcher rides ANY session's connection, so it
                    // resumes as soon as the first tab on this server is back.
                    kotlinx.coroutines.delay(5_000)
                    continue
                }
            } catch (e: Exception) {
                FileLogger.error(TAG, "Notify watcher failed for server ${w.serverId}: ${e.message}", e)
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
     * One legacy Stop marker line (`claude-remote-notify <#S> <epoch>`) of the shared tail: dispatched
     * to its session, deduplicated by epoch identity, then handled on the session's lane.
     */
    private fun dispatchStopMarker(w: ServerNotifyWatcher, line: String, clockSkewMs: Long?) {
        // EXACT tmux-session match. The marker line is
        // "claude-remote-notify <#S> <epoch>" and ALL sessions on a
        // server share one marker file, so a substring match cross-
        // fired between sessions whose names are prefixes of each
        // other (e.g. "cashy" matched "cashy-test"). Dispatch on the
        // second whitespace token exactly.
        val parts = line.trim().split(Regex("\\s+"))
        val markerTmux = parts.getOrNull(1) ?: return
        val sessionId = w.tmuxToSession[markerTmux] ?: return
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
                    return
                }
                is NotifyEpochTracker.Outcome.Replay -> {
                    FileLogger.log(TAG, "Suppressed replayed Stop hook for $sessionId (epoch $markerEpochSec <= last seen)")
                    return
                }
                is NotifyEpochTracker.Outcome.Claimed -> claim = outcome.claim
            }
            // Sanity bound only — see notifyAbsurdAgeMs. The session
            // really is idle, so still flip the dot; just don't buzz.
            if (ageMs > notifyAbsurdAgeMs) {
                FileLogger.log(TAG, "Stop hook for $sessionId is ${ageMs}ms old — activity only, no notification")
                flipToWaiting(sessionId)
                return
            }
        }
        FileLogger.log(TAG, "Stop hook fired for $sessionId: $line")
        noteLegacyStop(sessionId)
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

    /** Test seam: one legacy Stop marker line of [serverId] as if the tail had read it (the watcher must exist). */
    internal fun dispatchStopMarkerLineForTest(serverId: String, line: String) {
        val w = serverNotifyWatchers[serverId] ?: error("no watcher for $serverId")
        dispatchStopMarker(w, line, 0L)
    }

    /**
     * One `claude-remote-ev` line: reduce it ([HookEventReducer]) and apply the result
     * on the session's lane (after everything already dispatched for it).
     * Background sessions only get a notification; the active tab in the foreground is
     * covered by the screen detector, so a second alert is not raised for it.
     */
    private fun dispatchHookEvent(w: ServerNotifyWatcher, line: String, skewMs: Long, skewErrMs: Long) {
        val e = HookEventLine.parse(line)
        if (e == null) {
            FileLogger.log(TAG, "Hook event line not understood: ${line.take(120)}")
            return
        }
        val sessionId = w.tmuxToSession[e.tmux] ?: return
        val r = w.reducer.reduce(e, clock(), skewMs, skewErrMs)
        if (r.ignored != null) return
        // CAP=1 from now on: an applied, fresh v2 event of this pane proves its hooks run now. A
        // replayed duplicate (ignored above) or the hour-old backlog of a cold start proves nothing.
        if (r.ageMs <= CAP_FRESH_MAX_AGE_MS) promptDetector.markHookEvents(sessionId, System.currentTimeMillis() - maxOf(0L, r.ageMs))
        val isActive = tabManager.activeTabId.value == sessionId
        FileLogger.log(TAG, "Hook event ${e.ev} for $sessionId (${if (isActive) "fg" else "bg"}) age=${r.ageMs}")
        onSessionLane(sessionId) {
            hookSink.onActivity(w.serverId, e.tmux, e.atMs)
            if (r.callWorking) hookSink.onClaudeWorking(w.serverId, e.tmux, true)
            if (r.checkUuid) hookSink.onCheckUuid(w.serverId, e.tmux)
            r.activity?.let { act ->
                val onlyLeavesApproval = e.ev == "stop" && act == SessionActivity.WORKING
                if (!onlyLeavesApproval || activityOf(sessionId) == SessionActivity.APPROVAL_NEEDED) {
                    updateActivity(sessionId, act)
                    if (act == SessionActivity.WORKING) armSilenceWatch(sessionId)
                }
            }
            // A new dialog replaces the prompt a pending reply was written for NOW, not after the
            // settle delay, and also when no alert follows (the foreground tab, an old event).
            if (r.clearedApproval) onApprovalAnswered(sessionId)
            onClaudeEnded(sessionId, if (e.ev == "end") clock() - r.ageMs else null)
            // Stamped with when the hook FIRED (device clock, skew-corrected): that is what tells a
            // late hook of the dialog the screen already saw from the next dialog (PromptTokens.sameDialog).
            if (e.ev == "perm" || e.ev == "ask") {
                NotifyKeys.prompt(NotifyKeys.hook(e.ev, e.tmux, e.atMs))?.let {
                    promptTokens.note(sessionId, it, clock() - r.ageMs, text = e.extra.ifBlank { null })
                }
            }
            val n = r.notify ?: return@onSessionLane
            kotlinx.coroutines.delay(hookSettleMs)
            if (w.reducer.isSuperseded(e.tmux, e.atMs)) return@onSessionLane
            if (isActive && !isBackground()) return@onSessionLane
            val hint = if (n.kind == "ask") PromptType.INPUT_PROMPT.displayHint else PromptType.PERMISSION_PROMPT.displayHint
            fireNeedsInput(sessionId, NotifyKind.ofHook(n.kind), hint, isActive, eventKey = n.key, body = n.text)
            hookSink.onNotify(w.serverId, e.tmux, n.kind, n.text)
        }
    }

    /** Test seam: one watcher line of [serverId] as if the tail had read it (the watcher must exist). */
    internal fun dispatchHookLineForTest(serverId: String, line: String) {
        val w = serverNotifyWatchers[serverId] ?: error("no watcher for $serverId")
        dispatchHookEvent(w, line, 0L, 0L)
    }

    /**
     * One `forget.log` line: a tombstone some device wrote. For a tab of ours that this
     * device did not just close, ask the reconciler whether the session is really gone
     * (after a delay, because the tombstone is written BEFORE the kill), once more later.
     */
    private fun dispatchForgetLine(w: ServerNotifyWatcher, line: String, skewMs: Long) {
        val f = ForgetLine.parse(line) ?: return
        val sessionId = w.tmuxToSession[f.name] ?: return
        if (!w.forgets.shouldAct(f, System.currentTimeMillis(), skewMs)) return
        if (closures.recentlyClosedLocally(w.serverId, f.name, closedLocallyWindowMs)) {
            FileLogger.log(TAG, "forget.log: ${f.name} was closed by this device, no check")
            return
        }
        FileLogger.log(TAG, "forget.log: ${f.name} tombstoned elsewhere, checking $sessionId")
        scope.launch {
            hookSink.onCheckClosed(w.serverId, f.name, forgetCheckDelayMs)
            kotlinx.coroutines.delay(forgetRetryMs)
            hookSink.onCheckClosed(w.serverId, f.name, forgetCheckDelayMs)
        }
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
                sessionId, NotifyKind.COMPLETION, READY_HINT, isActiveTab,
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
        dialogDedup.forget(sessionId)
        silenceWatches.remove(sessionId)?.cancel()
        silenceArmedAt.remove(sessionId)
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
                w.tmuxToSession.entries.filter { it.value == sessionId }.forEach { w.reducer.forget(it.key) }
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
