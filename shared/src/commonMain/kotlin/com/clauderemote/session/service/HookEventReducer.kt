package com.clauderemote.session.service

import com.clauderemote.model.SessionActivity
import com.clauderemote.util.isoToEpochMillis

/**
 * One line of `~/.cache/claude-remote/events`, written by `hook-ev.sh`:
 * `claude-remote-ev <EV> <name_b64> <epoch_ms> <claude_pid> <extra_b64>`.
 * [extra] is the decoded UTF-8 text (empty when absent).
 */
internal data class HookEvent(
    val ev: String,
    val tmux: String,
    val atMs: Long,
    val pid: Long?,
    val extra: String,
)

internal object HookEventLine {
    const val PREFIX = "claude-remote-ev "
    val KNOWN = setOf("work", "ask", "askdone", "perm", "stop", "start", "end")

    /** Null for anything that is not a well-formed v2 event line (unknown event names included). */
    fun parse(line: String): HookEvent? {
        if (!line.startsWith(PREFIX)) return null
        val f = line.trim().split(' ').filter { it.isNotEmpty() }
        if (f.size < 5) return null
        val ev = f[1]
        if (ev !in KNOWN) return null
        val name = decode(f[2]) ?: return null
        if (name.isEmpty()) return null
        val ms = f[3].toLongOrNull() ?: return null
        val extra = if (f.size >= 6) decode(f[5]) ?: "" else ""
        return HookEvent(ev, name, ms, f[4].toLongOrNull(), extra)
    }

    private fun decode(b64: String): String? = try {
        java.util.Base64.getDecoder().decode(b64).toString(Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** `[2026-11-02T18:10:12Z] forget: <name> (durable=1…)` from `~/.claude-remote/forget.log`. */
internal data class ForgetEntry(val atMs: Long, val name: String)

internal object ForgetLine {
    private val SHAPE = Regex("""^\[([^\]]+)]\s+forget:\s+(\S+)(?:\s+\(durable=[01][^)]*\))?\s*$""")

    /**
     * Tolerant of the suffix: another writer adds free text after `durable=1`
     * (`(durable=1, <who>)`), and a line without any suffix is accepted too.
     */
    fun parse(line: String): ForgetEntry? {
        val m = SHAPE.matchEntire(line.trim()) ?: return null
        val ts = isoToEpochMillis(m.groupValues[1]) ?: return null
        val name = m.groupValues[2]
        if (name.startsWith("(")) return null
        return ForgetEntry(ts, name)
    }
}

/**
 * Per-server reducer for hook events: the pure decision logic behind
 * [NotificationService]'s handling of `claude-remote-ev` lines. No I/O and no
 * clock of its own (`nowMs` / `skewMs` are passed in), so replay, ordering and
 * the notification rules can be tested exhaustively.
 *
 * Rules:
 *  - **Dedup** on `(tmux, ev, atMs)` through an LRU of [capacity]: the watcher
 *    replays the last lines of the file on every (re)connect. An event that
 *    carries the same stamp and name as the newest one of its session is a
 *    duplicate even after its key fell out of the LRU.
 *  - **Order** comes from ONE file only (the events file, millisecond stamps):
 *    an event older than the newest one already seen for that session changes
 *    nothing. The legacy `Stop` marker (seconds, another file) is never compared
 *    with these stamps; the v2 `stop` entry exists for that reason.
 *  - `work` -> WORKING (and re-arms the latch via [Reduction.callWorking]); clears approval.
 *  - `perm` / `ask` -> APPROVAL_NEEDED, remembered with its start time
 *    ([approvalSince]). A notification is proposed only if the event is younger
 *    than [NOTIFY_MAX_AGE_MS] (10 min) - and only [FIRST_SIGHTING_MAX_AGE_MS]
 *    (60 s) for an event stamped BEFORE this reducer's first call: that is the
 *    replayed backlog of a cold start or of a rotated file, whichever session it
 *    belongs to. Events from the outage of a later reconnect are stamped after
 *    that moment and keep the 10 minutes.
 *    The caller must still check [isSuperseded] after a short settle time, so an
 *    approval that was answered one line later in the same replay does not buzz.
 *  - `askdone` -> WORKING; clears approval.
 *  - `stop` clears approval, for a live one too: with a pending approval the
 *    session goes to WORKING (live; the legacy Stop marker flips it to ready once
 *    the notification body is resolved, which an earlier flip would pre-empt and
 *    make the watch buzz with the previous answer) or to ready (an OLD event, a
 *    replay) - a Stop with background tasks (`extra == "bg"`) never flips to ready.
 *    Without a pending approval a live `stop` changes no state; an old one flips to
 *    ready. "Live" means younger than [STOP_LIVE_WINDOW_MS] plus the uncertainty
 *    of the measured clock skew.
 *  - `end` -> IDLE; clears approval. `start` asks for a UUID re-check
 *    ([Reduction.checkUuid]) and does NOT touch a pending approval; no session id
 *    is ever adopted from a hook. A `start` right after an `end` (`/clear`) leaves
 *    the session ready, not idle.
 *
 * Limits: deny / Escape on a permission dialog emit no event at all, so a hook
 * APPROVAL ends only with the next event of that session or with the screen
 * classifier after attach; [approvalSince] lets the UI show its age. The
 * `background_tasks` check inspects only the first 256 KB of a Stop payload.
 */
internal class HookEventReducer(private val capacity: Int = 2000) {
    data class Notify(val kind: String, val text: String?, val key: String)

    data class Reduction(
        /** Why nothing was done (`duplicate`, `older`, ...); null when the event was applied. */
        val ignored: String? = null,
        val activity: SessionActivity? = null,
        val clearedApproval: Boolean = false,
        val callWorking: Boolean = false,
        val notify: Notify? = null,
        val checkUuid: Boolean = false,
        val ageMs: Long = 0,
    )

    private val lock = Any()
    private val seen = object : LinkedHashMap<String, Unit>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean = size > capacity
    }
    private val lastAt = HashMap<String, Long>()
    private val lastEv = HashMap<String, String>()
    private val approval = HashMap<String, Long>()

    /** Server-clock ms of this reducer's first call; events stamped before it are the replayed backlog. */
    private var liveFromMs: Long? = null

    /**
     * [skewMs] = device clock minus server clock (same sign convention as the Stop watcher);
     * [skewErrMs] = how far off that measurement can be, which widens the window in which a
     * `stop` still counts as live.
     */
    fun reduce(e: HookEvent, nowMs: Long, skewMs: Long, skewErrMs: Long = 0): Reduction = synchronized(lock) {
        val ageMs = nowMs - (e.atMs + skewMs)
        val liveFrom = liveFromMs ?: (nowMs - skewMs - LIVE_FROM_MARGIN_MS).also { liveFromMs = it }
        if (seen.put("${e.tmux}|${e.ev}|${e.atMs}", Unit) != null) {
            return@synchronized Reduction(ignored = "duplicate", ageMs = ageMs)
        }
        val prev = lastAt[e.tmux]
        if (prev != null) {
            if (e.atMs < prev) return@synchronized Reduction(ignored = "older than the newest event", ageMs = ageMs)
            if (e.atMs == prev && e.ev == lastEv[e.tmux]) return@synchronized Reduction(ignored = "duplicate", ageMs = ageMs)
        }
        val previousEv = lastEv[e.tmux]
        lastAt[e.tmux] = e.atMs
        lastEv[e.tmux] = e.ev
        // `start` leaves a pending approval alone: a session start is no answer to a dialog.
        val hadApproval = if (e.ev == "start") false else approval.remove(e.tmux) != null
        val liveStop = ageMs <= STOP_LIVE_WINDOW_MS + skewErrMs
        when (e.ev) {
            "work" -> Reduction(activity = SessionActivity.WORKING, clearedApproval = hadApproval, callWorking = true, ageMs = ageMs)
            "askdone" -> Reduction(activity = SessionActivity.WORKING, clearedApproval = hadApproval, ageMs = ageMs)
            "perm", "ask" -> {
                approval[e.tmux] = e.atMs
                val limit = if (e.atMs < liveFrom) FIRST_SIGHTING_MAX_AGE_MS else NOTIFY_MAX_AGE_MS
                val notify = if (ageMs <= limit) {
                    Notify(e.ev, e.extra.ifBlank { null }, NotifyKeys.hook(e.ev, e.tmux, e.atMs))
                } else null
                Reduction(activity = SessionActivity.APPROVAL_NEEDED, notify = notify, ageMs = ageMs)
            }
            "stop" -> {
                val bg = e.extra == "bg"
                val activity = when {
                    // old (replayed) stop: the session is ready, unless background tasks still run
                    !liveStop -> when {
                        !bg -> SessionActivity.WAITING_FOR_INPUT
                        hadApproval -> SessionActivity.WORKING
                        else -> null
                    }
                    // live stop that ends a dialog: the dot must leave APPROVAL; the legacy marker does the rest
                    hadApproval -> SessionActivity.WORKING
                    else -> null
                }
                Reduction(activity = activity, clearedApproval = hadApproval, ageMs = ageMs)
            }
            "end" -> Reduction(activity = SessionActivity.IDLE, clearedApproval = hadApproval, ageMs = ageMs)
            "start" -> Reduction(
                activity = if (previousEv == "end") SessionActivity.WAITING_FOR_INPUT else null,
                checkUuid = true,
                ageMs = ageMs,
            )
            else -> Reduction(ignored = "unknown event", ageMs = ageMs)
        }
    }

    /** True when an event newer than [atMs] has already been reduced for [tmux]. */
    fun isSuperseded(tmux: String, atMs: Long): Boolean = synchronized(lock) { (lastAt[tmux] ?: Long.MIN_VALUE) > atMs }

    /** Server-clock start of the hook APPROVAL of [tmux], or null when none is pending. */
    fun approvalSince(tmux: String): Long? = synchronized(lock) { approval[tmux] }

    /** Permanent forget of a session. */
    fun forget(tmux: String) = synchronized(lock) {
        lastAt.remove(tmux); lastEv.remove(tmux); approval.remove(tmux)
        Unit
    }

    companion object {
        const val NOTIFY_MAX_AGE_MS = 10L * 60 * 1000
        const val FIRST_SIGHTING_MAX_AGE_MS = 60L * 1000
        const val STOP_LIVE_WINDOW_MS = 5_000L
        private const val LIVE_FROM_MARGIN_MS = 1_000L
    }
}

/**
 * Replay guard for `forget.log` lines: `tail -n 25` re-sends the same lines on
 * every watcher (re)connect (20-30 per day per device, hundreds in an outage).
 * A line is acted on once, and only when it is fresh; an old line is merely
 * remembered (same idea as the Stop watcher's "Primed").
 */
internal class ForgetTracker(private val capacity: Int = 500) {
    private val lock = Any()
    private val seen = object : LinkedHashMap<String, Unit>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean = size > capacity
    }

    /** True when [e] is new and younger than [LIVE_MAX_AGE_MS] (skew-corrected) -> run the closed-elsewhere check. */
    fun shouldAct(e: ForgetEntry, nowMs: Long, skewMs: Long): Boolean = synchronized(lock) {
        if (seen.put("${e.atMs}|${e.name}", Unit) != null) return@synchronized false
        nowMs - (e.atMs + skewMs) <= LIVE_MAX_AGE_MS
    }

    companion object {
        const val LIVE_MAX_AGE_MS = 60_000L
    }
}
