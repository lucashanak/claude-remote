package com.clauderemote.session

import com.clauderemote.model.ClaudeSession
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * What the server says about closing a session right now (Jarvis R6 = 6d). Filled in by the server-side
 * `facts` check of the second wave; until then nothing produces it and every close goes through.
 */
sealed interface CloseCheck {
    /** Nothing in the way. */
    data object Clear : CloseCheck

    /** Close anyway, but say so in the snackbar (uncommitted or unpushed git work). */
    data class Warn(val text: String) : CloseCheck

    /** Do not close: a turn is running, a prompt is open, someone else is watching, ... */
    data class Block(val text: String) : CloseCheck
}

/** A tab whose close is waiting out its undo window. The tab stays in [TabManager] meanwhile, only hidden. */
data class PendingClose(
    val sessionId: String,
    val label: String,
    /** It was the selected tab when closed: undo selects it again. */
    val wasActive: Boolean,
    /** [CloseCheck.Warn] text, once the check said so. */
    val warning: String? = null,
)

/** A close the check refused: the tab is back, the notice offers "close anyway". */
data class BlockedClose(val sessionId: String, val label: String, val reason: String)

data class PendingClosesState(
    val pending: List<PendingClose> = emptyList(),
    val blocked: List<BlockedClose> = emptyList(),
    val paused: Boolean = false,
) {
    /** Tabs the UI must not show: their close has not happened yet, but the user already asked for it. */
    val hiddenIds: Set<String> get() = pending.mapTo(HashSet()) { it.sessionId }
    val isEmpty: Boolean get() = pending.isEmpty() && blocked.isEmpty()
}

/**
 * Delayed close with undo (Jarvis F0a, R6 = 6d, R7 = 7b).
 *
 * Closing a tab used to forget it at once: durable tombstone, `kill-session`, gone. Now [close] only hides the tab
 * and starts a [windowMs] countdown; [forget] — the real, irreversible close — runs when it ends. Until then
 * nothing has happened anywhere: the tab is still in [TabManager], in the local session store and in the manifest
 * this device pushes, and its PTY stays attached. That is what makes the two failure directions safe:
 * - undo costs nothing (no reconnect, no new channel, the pane never noticed);
 * - if the process dies inside the window the close simply never happened, and the tab comes back on the next
 *   start from the same store it never left.
 *
 * Every close has its own deadline. [setPaused] stops all of them while the pointer rests on the snackbar (the
 * remaining time is kept, not restarted); [flush] runs every pending close now, for when the user leaves the app.
 *
 * [closeCheck] runs alongside the countdown: [CloseCheck.Block] cancels the close and turns it into a
 * [BlockedClose] notice, [CloseCheck.Warn] adds its text to the snackbar, and `null`, an exception or no answer
 * before the deadline all mean "close" — the check may only stop a close, never hold one up.
 *
 * All state lives under [lock]; [forget] and [closeCheck] are called outside it. A close fires at most once: whoever
 * removes the entry from [entries] (the timer, [flush], [forceClose]) is the only caller of [forget] for it.
 */
class PendingCloses(
    private val scope: CoroutineScope,
    private val forget: suspend (sessionId: String) -> Unit,
    closeCheck: suspend (ClaudeSession) -> CloseCheck? = { null },
    private val windowMs: Long = WINDOW_MS,
    private val now: () -> Long = monotonicMs(),
) {
    /** Second wave sets this to the server `facts` check. A `null` answer means "unknown", which closes. */
    @Volatile
    var closeCheck: suspend (ClaudeSession) -> CloseCheck? = closeCheck

    private class Entry(
        val gen: Long,
        val session: ClaudeSession,
        val wasActive: Boolean,
        val blockedReason: String?,
        var warning: String? = null,
        // While running: when it fires. While paused: how much was left.
        var deadline: Long = 0,
        var remaining: Long = 0,
        var timer: Job? = null,
        var check: Job? = null,
    ) {
        val isBlocked get() = blockedReason != null
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private var paused = false
    private var nextGen = 0L
    private val inFlight = HashSet<Job>()

    private val _state = MutableStateFlow(PendingClosesState())
    val state: StateFlow<PendingClosesState> = _state.asStateFlow()

    /**
     * Start the undo window for [session]. False when it is already pending (a second tap on a tab that is
     * already gone from the bar must not restart or duplicate its countdown). A blocked notice for the same
     * session is replaced: closing it again is a fresh request.
     */
    fun close(session: ClaudeSession, wasActive: Boolean = false): Boolean {
        val entry = synchronized(lock) {
            val existing = entries[session.id]
            if (existing != null && !existing.isBlocked) return false
            existing?.let { drop(it) }
            Entry(nextGen++, session, wasActive, blockedReason = null).also {
                entries[session.id] = it
                arm(it, windowMs)
                publish()
            }
        }
        val check = closeCheck
        // Started outside the lock: a check that answers synchronously calls back into onChecked.
        val job = scope.launch {
            val verdict = try {
                check(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.log(TAG, "close check for ${session.id} failed (${e.message}) — closing as if unknown")
                null
            }
            onChecked(session.id, entry.gen, verdict)
        }
        synchronized(lock) {
            if (entries[session.id] === entry) entry.check = job else job.cancel()
        }
        return true
    }

    /** "Vrátit": the tab comes back where it was; [forget] never runs for this close. */
    fun undo(sessionId: String): PendingClose? = synchronized(lock) {
        val e = entries[sessionId]?.takeIf { !it.isBlocked } ?: return null
        drop(e)
        publish()
        e.toPending()
    }

    /** "Vrátit vše": every pending close comes back. Blocked notices stay — those tabs are back already. */
    fun undoAll(): List<PendingClose> = synchronized(lock) {
        val undone = entries.values.filter { !it.isBlocked }
        undone.forEach { drop(it) }
        publish()
        undone.map { it.toPending() }
    }

    /** "Zavřít i tak" on a blocked notice (or on a pending close): close now, no second window. */
    fun forceClose(sessionId: String) {
        synchronized(lock) {
            val e = entries[sessionId] ?: return
            drop(e)
            publish()
        }
        runForget(listOf(sessionId))
    }

    /** Hide a blocked notice without closing. */
    fun dismissBlocked(sessionId: String) = synchronized(lock) {
        val e = entries[sessionId]?.takeIf { it.isBlocked } ?: return
        drop(e)
        publish()
    }

    /**
     * Pointer over the snackbar (desktop): hold every countdown, keeping what was left of each. A check that
     * answers meanwhile still applies; only the deadlines wait.
     */
    fun setPaused(value: Boolean) = synchronized(lock) {
        if (paused == value) return
        paused = value
        val t = now()
        for (e in entries.values) {
            if (value) {
                e.remaining = (e.deadline - t).coerceAtLeast(0)
                e.timer?.cancel()
                e.timer = null
            } else {
                arm(e, e.remaining)
            }
        }
        publish()
    }

    /**
     * The user is leaving the app (Android `onStop`, desktop window close): run every pending close now instead of
     * leaving it to a timer that may never fire. Blocked notices are dropped (their tabs are back, nothing to do).
     * The returned job completes when every close started here AND every close already running has finished, so
     * the desktop exit can wait for the server side (tombstone, kill) before the JVM goes.
     */
    fun flush(): Job {
        val (ids, running) = synchronized(lock) {
            val due = entries.values.toList()
            due.forEach { drop(it) }
            publish()
            due.filter { !it.isBlocked }.map { it.session.id } to inFlight.toList()
        }
        val started = runForget(ids)
        return scope.launch { (running + listOfNotNull(started)).joinAll() }
    }

    /**
     * Drop entries whose tab is gone from [TabManager] without this class closing it (another device closed it,
     * the prune of dead panes forgot it). Nothing to forget any more, and an undo could not bring it back.
     */
    fun retain(liveIds: Set<String>) = synchronized(lock) {
        val gone = entries.values.filter { it.session.id !in liveIds }
        if (gone.isEmpty()) return
        gone.forEach { drop(it) }
        publish()
    }

    /** Milliseconds left for [sessionId]'s close or notice; null when there is none. Frozen while paused. */
    fun remainingMs(sessionId: String): Long? = synchronized(lock) {
        val e = entries[sessionId] ?: return null
        if (paused) e.remaining else (e.deadline - now()).coerceAtLeast(0)
    }

    val windowMillis: Long get() = windowMs

    private fun onChecked(sessionId: String, gen: Long, verdict: CloseCheck?) = synchronized(lock) {
        val e = entries[sessionId]?.takeIf { it.gen == gen && !it.isBlocked } ?: return
        when (verdict) {
            is CloseCheck.Block -> {
                drop(e)
                val notice = Entry(nextGen++, e.session, e.wasActive, blockedReason = verdict.text)
                entries[sessionId] = notice
                arm(notice, windowMs)
                FileLogger.log(TAG, "close of $sessionId blocked: ${verdict.text}")
            }
            is CloseCheck.Warn -> e.warning = verdict.text
            CloseCheck.Clear, null -> return
        }
        publish()
    }

    /** Must hold [lock]. Starts [e]'s countdown with [ms] left, or only records [ms] while paused. */
    private fun arm(e: Entry, ms: Long) {
        e.remaining = ms
        e.deadline = now() + ms
        if (paused) return
        e.timer = scope.launch {
            delay(ms)
            expire(e.session.id, e.gen)
        }
    }

    private fun expire(sessionId: String, gen: Long) {
        val fire = synchronized(lock) {
            val e = entries[sessionId]?.takeIf { it.gen == gen } ?: return
            // Not drop(): this coroutine IS e.timer, there is nothing to cancel but the check.
            entries.remove(sessionId)
            e.check?.cancel()
            publish()
            !e.isBlocked
        }
        if (fire) runForget(listOf(sessionId))
    }

    /** Must hold [lock]. */
    private fun drop(e: Entry) {
        entries.remove(e.session.id)
        e.timer?.cancel()
        e.check?.cancel()
    }

    /** Runs [forget] for [ids] in one tracked job ([flush] waits for it); null when there is nothing to forget. */
    private fun runForget(ids: List<String>): Job? {
        if (ids.isEmpty()) return null
        val job = scope.launch {
            // Side by side: a flush of several closes must not wait out one server's cleanup before the next.
            for (id in ids) launch {
                try {
                    FileLogger.log(TAG, "closing $id (undo window over)")
                    forget(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FileLogger.error(TAG, "close of $id failed", e)
                }
            }
        }
        synchronized(lock) { inFlight.add(job) }
        job.invokeOnCompletion { synchronized(lock) { inFlight.remove(job) } }
        return job
    }

    /** Must hold [lock]. */
    private fun publish() {
        _state.value = PendingClosesState(
            pending = entries.values.filter { !it.isBlocked }.map { it.toPending() },
            blocked = entries.values.filter { it.isBlocked }.map {
                BlockedClose(it.session.id, it.session.displayLabel, it.blockedReason!!)
            },
            paused = paused,
        )
    }

    private fun Entry.toPending() = PendingClose(session.id, session.displayLabel, wasActive, warning)

    companion object {
        private const val TAG = "PendingCloses"

        /** R7 = 7b. */
        const val WINDOW_MS = 10_000L

        private fun monotonicMs(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}
