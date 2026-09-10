package com.clauderemote.session.service

import com.clauderemote.session.PromptType
import com.clauderemote.session.transcript.TranscriptEntry
import com.clauderemote.util.isoToEpochMillis

/**
 * The decision logic behind the "Claude needs input" notification, extracted
 * from [NotificationService] so it can be unit-tested without SSH, a live
 * transcript stream or a coroutine scope. Everything here is pure (or a small
 * self-contained piece of state); the service keeps the I/O.
 */

/**
 * Chooses the assistant message that may be shown as a notification body.
 *
 * The bug this exists for: the old gate was "any assistant entry whose id
 * differs from the one we notified last time". After a cold start that id is
 * null and the transcript backlog already holds the PREVIOUS turn's answer, so
 * the very first completion of every session adopted stale text with zero
 * waiting. The rules below replace a timing heuristic with invariants that hold
 * even when nothing has been recorded yet.
 */
internal object StopBodySelector {

    /**
     * How far before its Stop marker an assistant message may be timestamped
     * when we have NO previous marker to bound it with. One turn — however many
     * tools it ran — is the unit here, so this is minutes, not hours: it is the
     * only thing standing between a cold start and the previous turn's answer,
     * and the six-hour value it replaced let a whole day of backlog through.
     * When a previous marker epoch IS known, that is a far sharper bound and
     * this one stops mattering.
     */
    const val MAX_BODY_AGE_BEFORE_MARKER_MS: Long = 15L * 60 * 1000

    /**
     * The last assistant message that belongs to the CURRENT turn: everything
     * after the last thing the human actually typed. Claude Code appends the
     * UserPrompt before the answer it produces, so an assistant entry ahead of
     * that boundary is by construction from an earlier turn.
     *
     * Tool round-trips do not move the boundary: a "user"-role message carrying
     * `tool_result` blocks is parsed as [TranscriptEntry.ToolResult], and
     * synthetic user text (system reminders, command echoes) is dropped
     * outright — see TranscriptParser.parseUser / isSyntheticUserText. The scan
     * therefore walks backwards PAST any trailing tool results to find the
     * answer text.
     *
     * With no UserPrompt in the parsed window at all we fall back to the last
     * assistant entry; the id and timestamp gates in [select] are what keep a
     * backlog from being adopted in that case.
     */
    fun lastAssistantAfterLastUserPrompt(entries: List<TranscriptEntry>): TranscriptEntry.AssistantText? {
        val boundary = entries.indexOfLast { it is TranscriptEntry.UserPrompt }
        for (i in entries.indices.reversed()) {
            if (i <= boundary) return null
            val e = entries[i]
            if (e is TranscriptEntry.AssistantText && e.text.isNotBlank()) return e
        }
        return null
    }

    /**
     * The body for a just-finished turn, or null to fall back to the generic
     * hint. Four gates, all of which must pass:
     *
     *  a) the entry is after the last UserPrompt (it answers THIS turn);
     *  b) it is not the message we already notified for (no repeat);
     *  c) if [previousMarkerEpochSec] is known, the entry is not older than that
     *     completion — an answer to THIS turn cannot predate the end of the
     *     previous one. This is the gate that works on a cold start, where
     *     nothing has been recorded and (b) is vacuous;
     *  d) otherwise it is not older than [MAX_BODY_AGE_BEFORE_MARKER_MS].
     *
     * An unparseable or missing timestamp passes (c) and (d) deliberately — the
     * structural gate is the real defence and we would rather send a body than
     * degrade to the generic hint on a transcript format change.
     *
     * Both epochs come from the SERVER (`date +%s` in the hook, and Claude
     * Code's own transcript clock), so no phone↔server skew enters here.
     */
    fun select(
        entries: List<TranscriptEntry>,
        lastNotifiedId: String?,
        markerEpochSec: Long?,
        previousMarkerEpochSec: Long? = null,
    ): TranscriptEntry.AssistantText? {
        val entry = lastAssistantAfterLastUserPrompt(entries) ?: return null
        if (lastNotifiedId != null && entry.id == lastNotifiedId) return null
        val ts = entry.timestamp?.let { isoToEpochMillis(it) } ?: return entry
        if (previousMarkerEpochSec != null) {
            // The sharp bound REPLACES the coarse one rather than stacking with
            // it: a turn can legitimately run for hours of tool work, and once
            // we know when the previous turn ended there is no reason to guess
            // at a duration. Second resolution is all the marker has, so an
            // answer written in the same second as the previous completion is
            // indistinguishable from one written just before it — that
            // one-second ambiguity is inherent (see NotifyEpochTracker).
            return if (ts < previousMarkerEpochSec * 1000L) null else entry
        }
        if (markerEpochSec != null && markerEpochSec * 1000L - ts > MAX_BODY_AGE_BEFORE_MARKER_MS) return null
        return entry
    }

    /**
     * The assistant id to remember when the body poll timed out and the
     * notification went out with the generic hint.
     *
     * Recording SOMETHING here is what stops one timeout poisoning every turn
     * after it: leave the id a turn behind and the next marker sees the message
     * we failed to send, finds it different from the recorded id, and adopts it
     * as if it were the new turn's answer. Deliberately NOT boundary-filtered —
     * we want the transcript's actual high-water mark, whichever turn it
     * belongs to. Null when nothing has been parsed yet, in which case there is
     * nothing that could be mistaken for a fresh answer either.
     */
    fun idToRecordOnTimeout(entries: List<TranscriptEntry>): String? =
        (entries.lastOrNull { it is TranscriptEntry.AssistantText } as? TranscriptEntry.AssistantText)?.id
}

/**
 * Per-session Stop-marker epochs: the highest we have ever SEEN and the highest
 * we have NOTIFIED for.
 *
 * This replaces the old age-based stale filter, which dropped exactly the
 * completion the user was waiting for: a phone that sleeps (or has its socket
 * frozen by HyperOS) reads the buffered marker minutes later, and "older than
 * two minutes" cannot tell that apart from a `tail -n 25` replay. Identity can:
 *
 *  - epoch <= highest seen  → we have already processed this marker (a replay
 *    of the backlog, or a racing watcher restart) → drop it, silently;
 *  - epoch strictly greater → a completion we have never processed → notify,
 *    however old it is. A burst of them collapses in the notify debounce.
 *
 * Seen and notified are tracked separately so a dispatch that is suppressed
 * downstream still advances "seen" and cannot be re-processed, while a dispatch
 * that THREW can be rolled back and retried on the next replay.
 *
 * Two limits are inherent to identifying a completion by `date +%s`, and are
 * accepted rather than worked around: two completions inside the same second
 * collapse into one, and a backwards NTP step on the server makes the markers
 * it emits unclaimable until the clock passes the old high-water mark again.
 * Both need a marker format change to fix, not a change here.
 */
internal class NotifyEpochTracker {
    private val highestSeenEpochSec = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val highestNotifiedEpochSec = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // ConcurrentHashMap alone is not enough: the check-and-set spans two maps
    // and must be atomic against a racing watcher restart observing the same
    // replayed line.
    private val lock = Any()

    /** Enough state to undo a claim, so a failed dispatch doesn't eat a completion. */
    class Claim(
        val sessionId: String,
        val epochSec: Long,
        val prevSeen: Long?,
        val prevNotified: Long?,
    )

    /** What to do with one marker. */
    sealed class Outcome {
        /** Already processed — a tail replay or a racing watcher. Drop it. */
        object Replay : Outcome()
        /** Recorded as a baseline. Flip the activity dot, send no notification. */
        object Primed : Outcome()
        /** A completion to tell the user about. */
        class Claimed(val claim: Claim) : Outcome()
    }

    /**
     * The whole decision for one marker, taken atomically.
     *
     * [ageMs] is the marker's skew-corrected age and [firstSightingGraceMs] how
     * recent it must be to notify while this session has never notified.
     *
     * The priming test is "has this session ever NOTIFIED", not "have we ever
     * seen a marker for it": priming itself records a marker as seen, so a
     * seen-based test primes only the FIRST replayed line and lets every later
     * line in the same 25-line tail window through — one baseline and then a
     * burst, which is the bug it was added to prevent.
     */
    fun observe(
        sessionId: String,
        epochSec: Long,
        ageMs: Long,
        firstSightingGraceMs: Long,
    ): Outcome = synchronized(lock) {
        if (highestNotifiedEpochSec[sessionId] == null && ageMs > firstSightingGraceMs) {
            primeLocked(sessionId, epochSec)
            return@synchronized Outcome.Primed
        }
        val claim = claimLocked(sessionId, epochSec) ?: return@synchronized Outcome.Replay
        Outcome.Claimed(claim)
    }

    /**
     * Claim [epochSec] for [sessionId]. Returns null when this marker has been
     * seen before (drop it); otherwise records it as both seen and notified and
     * returns the previous values for [rollback].
     */
    fun claim(sessionId: String, epochSec: Long): Claim? = synchronized(lock) { claimLocked(sessionId, epochSec) }

    private fun claimLocked(sessionId: String, epochSec: Long): Claim? {
        val prevSeen = highestSeenEpochSec[sessionId]
        if (prevSeen != null && epochSec <= prevSeen) return null
        val prevNotified = highestNotifiedEpochSec[sessionId]
        highestSeenEpochSec[sessionId] = epochSec
        highestNotifiedEpochSec[sessionId] = epochSec
        return Claim(sessionId, epochSec, prevSeen, prevNotified)
    }

    /**
     * Record [epochSec] as seen WITHOUT notifying, establishing a baseline for a
     * session we have never seen a marker for.
     *
     * Needed because "no recorded epoch" and "a marker newer than anything
     * recorded" are the same thing on the first connect, when the watcher
     * replays the last 25 lines of a file shared by every session on the
     * server. Without a baseline the user opens the app and is buzzed once per
     * session for completions they answered hours ago. Priming costs nothing
     * afterwards: from the second marker on, identity alone decides, so a real
     * completion arriving minutes late through a frozen socket still notifies.
     */
    fun prime(sessionId: String, epochSec: Long) = synchronized(lock) { primeLocked(sessionId, epochSec) }

    private fun primeLocked(sessionId: String, epochSec: Long) {
        val prevSeen = highestSeenEpochSec[sessionId]
        if (prevSeen == null || epochSec > prevSeen) highestSeenEpochSec[sessionId] = epochSec
    }

    /**
     * Undo [claim], but ONLY if it is still the newest thing recorded.
     *
     * The claim happens on the tail reader while the dispatch runs on a worker,
     * so a newer marker for the same session can be claimed and notified while
     * an older one is still failing. An unconditional restore would then un-see
     * the newer marker and the next tail replay would notify for it a second
     * time. Compare-and-restore keeps the rollback to the case it is for: the
     * claim is the high-water mark, and nothing has advanced past it.
     */
    fun rollback(claim: Claim) = synchronized(lock) {
        if (highestSeenEpochSec[claim.sessionId] != claim.epochSec) return@synchronized
        restore(highestSeenEpochSec, claim.sessionId, claim.prevSeen)
        restore(highestNotifiedEpochSec, claim.sessionId, claim.prevNotified)
    }

    fun highestSeen(sessionId: String): Long? = highestSeenEpochSec[sessionId]

    fun highestNotified(sessionId: String): Long? = highestNotifiedEpochSec[sessionId]

    /** Permanent forget (session removed), NOT a transient disconnect. */
    fun clear(sessionId: String) = synchronized(lock) {
        highestSeenEpochSec.remove(sessionId)
        highestNotifiedEpochSec.remove(sessionId)
        Unit
    }

    private fun restore(map: java.util.concurrent.ConcurrentHashMap<String, Long>, key: String, value: Long?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

/**
 * Dedup keys for [NotificationService.fireNeedsInput]. One key identifies one
 * event the user should be told about exactly once.
 */
internal object NotifyKeys {
    private const val STOP_PREFIX = "stop#"
    private const val DETECTION_PREFIX = "prompt#"

    /** A Stop-hook completion: (tmux session, marker epoch) names it exactly. */
    fun stop(tmuxName: String, epochSec: Long): String = "$STOP_PREFIX$tmuxName#$epochSec"

    /**
     * A screen-detected prompt. Keyed on a per-session detection COUNTER rather
     * than on the last assistant message id: a run of tool permissions inside
     * one turn produces no new assistant text between them, so the old key was
     * byte-identical and every prompt after the first was silently swallowed.
     * The counter only advances on a genuine new detection (the detector's own
     * latch + COOLDOWN gate that), and replay/flap protection is the notify
     * debounce's job.
     */
    fun detection(type: PromptType, seq: Long): String = "$DETECTION_PREFIX${type.name}#$seq"

    /**
     * Whether [key] names an event that must be told to the user even if
     * another notification for the session just went out.
     *
     * Only screen-detected PROMPTS qualify. Answering one permission prompt and
     * being asked for the next one two seconds later is two things the user has
     * to act on, and the debounce window swallowed the second.
     *
     * Stop markers deliberately do NOT qualify, even though each carries a
     * distinct epoch: a model handoff or a tool retry emits several markers
     * inside what the user experiences as one completion, and collapsing those
     * is exactly what the window is for. A key-less caller (a malformed marker
     * line) cannot be identified at all, so it stays debounced too.
     */
    fun isDebounceExempt(key: String?): Boolean = key != null && key.startsWith(DETECTION_PREFIX)
}
