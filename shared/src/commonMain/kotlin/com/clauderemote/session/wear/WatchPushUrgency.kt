package com.clauderemote.session.wear

import com.clauderemote.model.SessionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One row of a snapshot build: the session's activity in the previous build (null = first sighting), now, and its `lastMessageAt`. */
data class WatchUrgencyRow(val sessionId: String, val previous: String?, val current: String, val messageAt: Long)

/**
 * When a routine snapshot push to the watch must go out URGENT.
 *
 * Routine pushes are not urgent on purpose: an urgent DataItem wakes the Bluetooth radio, and the
 * activity churn of many streaming sessions made that the biggest battery cost of the feature.
 * But the push that carries "this session now waits for you" is the one the user is waiting for,
 * and as a routine item Play Services delivered it 91 s late (`stop` 14:41:07, watch flip
 * 14:42:38). So urgent exactly where the watch will vibrate NOW:
 * - a session ENTERS APPROVAL_NEEDED (perm or ask: the watch alerts every new dialog);
 * - pushNow() of a perm/ask alert ([needsInputUrgent]): the second dialog of one turn stays in
 *   APPROVAL_NEEDED and has no edge, its alert path is what carries it;
 * - a Jarvis alert the phone let vibrate ([jarvisAlertUrgent]: block, done, incident).
 * Since 2026-11-08 a finished turn (WAITING_FOR_INPUT) never alerts the wrist, so it is never
 * urgent: it rides the routine, batched delivery like any other row change.
 * At most one urgent push per [BUDGET_MS] in all, outside pushNow() (urgent anyway).
 */
object WatchPushUrgency {
    const val BUDGET_MS = 10_000L
    /** An edge the budget deferred rides a later push for at most this long. */
    const val PENDING_MAX_MS = 60_000L

    private val ATTENTION = setOf(SessionActivity.APPROVAL_NEEDED.name)

    fun isAttentionState(activity: String): Boolean = activity in ATTENTION

    /**
     * Only an edge INTO an open dialog. [messageAt] / [lastAttentionMessageAt] decided the WAITING
     * edge before 2026-11-08 and are kept for the callers' shape; a finished turn is never urgent now.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isUrgentEdge(previous: String?, current: String, messageAt: Long, lastAttentionMessageAt: Long?): Boolean =
        previous != null && previous != current && current == SessionActivity.APPROVAL_NEEDED.name

    /** pushNow() of a needs-input alert: urgent for a dialog (perm, ask), routine for a finished turn. */
    fun needsInputUrgent(kind: com.clauderemote.session.service.NotifyKind): Boolean =
        kind != com.clauderemote.session.service.NotifyKind.COMPLETION

    /**
     * The push of a Jarvis alert: urgent exactly when the phone's batcher let it vibrate ([buzz], which it does only
     * for block, done and incident, at most once per 5 min) — that is when the watch vibrates too (buzzAt grew).
     * Everything else Jarvis changes (an info, a cleared count, the lease, the routing) only updates the wrist.
     */
    fun jarvisAlertUrgent(kind: com.clauderemote.session.service.JarvisAlertKind, buzz: Boolean): Boolean = buzz && kind.urgent
}

/**
 * The phone side of [WatchPushUrgency]: the activity each session had in the previous build
 * ([observe]), the urgent edges a build found ([note]) and the write that delivers them ([claim]).
 * An edge stays pending until a written snapshot that still shows the session waiting claims it,
 * so a build that collectLatest cancels (or a write dropped as stale) loses nothing. Thread-safe.
 */
class WatchUrgencyTracker(
    private val budgetMs: Long = WatchPushUrgency.BUDGET_MS,
    private val pendingMaxMs: Long = WatchPushUrgency.PENDING_MAX_MS,
) {
    private val lock = Any()
    private val observed = HashMap<String, String>()
    private val attentionMessageAt = HashMap<String, Long>()
    private val pending = HashMap<String, Long>()
    private var lastUrgentAt: Long? = null
    /** When a write may deliver the edges the budget held back in the last [claim] (null = none held back). */
    private var deferredUntil: Long? = null

    /** Records [activity] as the session's latest and returns the one the previous build saw (null = first). */
    fun observe(sessionId: String, activity: String): String? = synchronized(lock) { observed.put(sessionId, activity) }

    /** One build's rows: urgent edges become pending, a session no longer waiting drops its edge. */
    fun note(rows: List<WatchUrgencyRow>, nowMs: Long) = synchronized(lock) {
        for (r in rows) {
            if (WatchPushUrgency.isUrgentEdge(r.previous, r.current, r.messageAt, attentionMessageAt[r.sessionId])) {
                pending[r.sessionId] = nowMs
            }
            if (WatchPushUrgency.isAttentionState(r.current)) attentionMessageAt[r.sessionId] = r.messageAt
            else pending.remove(r.sessionId)
        }
    }

    /**
     * Called by the write itself, with no suspension before the put: the pending edges of sessions
     * this snapshot shows waiting ([attentionIds]). [urgentAnyway] (pushNow) delivers them without
     * spending the budget; otherwise they make the write urgent once per [budgetMs]. Empty = routine.
     */
    fun claim(attentionIds: Set<String>, nowMs: Long, urgentAnyway: Boolean): Set<String> = synchronized(lock) {
        pending.entries.removeAll { nowMs - it.value >= pendingMaxMs }
        deferredUntil = null
        val ids = pending.keys.filterTo(HashSet()) { it in attentionIds }
        if (ids.isEmpty()) return@synchronized emptySet()
        if (!urgentAnyway) {
            val last = lastUrgentAt
            if (last != null && nowMs - last < budgetMs) {
                deferredUntil = last + budgetMs
                return@synchronized emptySet()
            }
            lastUrgentAt = nowMs
        }
        pending.keys.removeAll(ids)
        ids
    }

    /**
     * When the last [claim] held edges back for the budget: the time a write may deliver them
     * (`lastUrgentAt + budgetMs`), else null. The caller schedules one write then, or a deferred
     * edge would wait for the next change of tabs or activities and otherwise go out routine.
     */
    fun deferredUntilMs(): Long? = synchronized(lock) { deferredUntil }

    /**
     * One snapshot write: [claim]s the edges and hands the DataItem to [put] (urgent or not, and the
     * edges it delivers), unless the budget held an edge back. Then nothing is put and the time the
     * deferred write is due comes back (null = written). A routine put here would already carry the
     * edge's row, the deferred write would then carry the same bytes, and Data Layer drops a DataItem
     * identical to the last one with its urgent flag; skipped, the deferred write carries the change
     * and goes out urgent. Called inside the write lock, like [claim].
     */
    fun write(attentionIds: Set<String>, nowMs: Long, urgentAnyway: Boolean, put: (urgent: Boolean, edges: Set<String>) -> Unit): Long? {
        val edges = claim(attentionIds, nowMs, urgentAnyway)
        deferredUntilMs()?.let { return it }
        put(urgentAnyway || edges.isNotEmpty(), edges)
        return null
    }

    /** Drop everything of closed tabs. */
    fun retain(liveIds: Set<String>) = synchronized(lock) {
        observed.keys.retainAll(liveIds)
        attentionMessageAt.keys.retainAll(liveIds)
        pending.keys.retainAll(liveIds)
        Unit
    }
}

/**
 * The one write scheduled for when the budget lets a held-back edge out ([WatchUrgencyTracker.write]
 * returned a time). A write held back while it waits or runs, its own or another push's (say while
 * it waits on a summary), makes it write once more when it is done, so it never ends with a held-back
 * change unscheduled: that change would wait for the next change of tabs or activities (review-B-4
 * kod L1). Held-back writes while it waits fold into the write it is about to make. [heldBack] is
 * called inside the write lock and does not suspend.
 */
class WatchDeferredWrite(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val write: suspend () -> Unit,
) {
    private val lock = Any()
    private var job: Job? = null
    /** A write held back after [job] last began its write: [job] writes once more, at this time. */
    private var again: Long? = null

    /** A write was held back until [atMs]. Returns true when this scheduled a new write, false when it folded into the one there is. */
    fun heldBack(atMs: Long): Boolean {
        val started = synchronized(lock) {
            if (job != null) {
                again = minOf(again ?: atMs, atMs)
                return false
            }
            scope.launch(start = CoroutineStart.LAZY) { writeWhenDue(atMs) }.also { j ->
                job = j
                // A write that threw, or a cancelled scope, must not leave a dead job blocking the next schedule.
                // (A normal end clears it itself, under the lock, in writeWhenDue.)
                j.invokeOnCompletion { cause -> if (cause != null) synchronized(lock) { if (job === j) { job = null; again = null } } }
            }
        }
        started.start()
        return true
    }

    private suspend fun writeWhenDue(firstAtMs: Long) {
        var at = firstAtMs
        while (true) {
            delay(maxOf(0L, at - nowMs()))
            // This write carries every change held back so far.
            synchronized(lock) { again = null }
            write()
            // Done only when nothing was held back meanwhile, decided under the lock heldBack takes.
            at = synchronized(lock) {
                val next = again
                again = null
                if (next == null) job = null
                next
            } ?: return
        }
    }
}
