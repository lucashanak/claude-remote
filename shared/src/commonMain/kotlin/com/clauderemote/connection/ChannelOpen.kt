package com.clauderemote.connection

import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.ExecUnavailableReason
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The one way the services open a transient channel (C1; kritika K-4, K-6, H7-H10): `RemoteExec`, the transcript and
 * status polls and the tmux probes call [connect] instead of `ch.connect(ms)`, and [disconnect] instead of
 * `ch.disconnect()`.
 *
 * Why: JSch 0.2.21 gives up an open whose confirmation is late WITHOUT sending CLOSE (`Channel.connect` sets
 * `connected=false` before `disconnect()`, which then only forgets the channel), and it ignores the late
 * OPEN_CONFIRMATION. sshd keeps that session open for the rest of the transport's life: an orphan in `MaxSessions`
 * (probe P1: 4 opens given up after 300 ms, then `exitStatus=2` refusals).
 *
 * What [connect] does instead:
 *  - an open with less than the route's floor left ([Tuning.minOpenCfMs] on Cloudflare, [Tuning.minOpenLanMs] else) is
 *    not started: [ExecUnavailable] TIMEOUT, no channel on the wire;
 *  - the open runs DETACHED: the caller waits only its own budget; the open itself goes on until it resolves (at most
 *    [Tuning.openDeadCfMs] / [Tuning.openDeadLanMs], or the caller's budget if that is longer). An open the caller gave up
 *    on runs a no-op instead of its command (the swap can lose a race with an answer arriving in the same instant) and
 *    is closed properly once confirmed — a CLOSE goes out, no orphan (probe P2). Meanwhile its slot stays taken
 *    ([Slots.hold], the exec gate) and [disconnect] leaves the channel to the open;
 *  - the health of the transport: [Tuning.deadAfter] opens IN A ROW that the server did not answer — each waited a full
 *    budget ([Tuning.fullBudgetMs], measured, not asked for), started after the last confirmed open and after the
 *    previous counted one was given up (so one pause that several opens in flight wait out together counts once; exit
 *    status -1 — not a probe with a small budget, not `channel request: timeout`, not a refusal) — or one open
 *    unanswered until its dead limit, make [Watch.dead]; a confirmed open resets the count. A refusal (exit status 1/2)
 *    while the session channels JSch has on the transport stay below sshd's `MaxSessions` ([Tuning.maxSessions],
 *    [Slots.headroom]) is evidence of orphans: [Tuning.refusedAfter] of them (over the transport's life) make
 *    [Watch.dead] too.
 */
internal object ChannelOpen {
    data class Tuning(
        val minOpenCfMs: Long = 1_500,
        val minOpenLanMs: Long = 300,
        val openDeadCfMs: Long = 20_000,
        val openDeadLanMs: Long = 10_000,
        /** An unanswered open counts toward [deadAfter] only when its caller waited at least this long. */
        val fullBudgetMs: Long = 5_000,
        val deadAfter: Int = 3,
        val refusedAfter: Int = 2,
        /** sshd's `MaxSessions` as the refusal rule assumes it (OpenSSH's default). */
        val maxSessions: Int = 10,
    )

    /** The limits; a seam for tests (reset with [resetForTest]). */
    @Volatile var tuning = Tuning()

    /** The owner of a transport (the exec transport manager): told when the transport looks dead. */
    fun interface Watch {
        /** Replace the transport of [session] ([why] for the log). True = done; false = not now (the owner's spacing). */
        fun dead(session: Session, why: String): Boolean
    }

    /** The channel budget of a transport (the exec gate). */
    interface Slots {
        /** Keep one slot taken for an open the caller gave up on; the function returned gives it back (call once). */
        fun hold(): () -> Unit
        /** Our own channels on the transport leave sshd room for one more: a refusal then means channels we do not know of. */
        fun headroom(): Boolean
    }

    /** What an abandoned exec runs instead of its command once its open is confirmed after all. */
    const val NOOP_COMMAND = "true"

    private class Health {
        var noAnswer = 0
        var refused = 0
        /** When an open on the transport was last confirmed, and when the last counted unanswered one was given up. */
        var lastConfirmedNanos = Long.MIN_VALUE
        var lastCountedNanos = Long.MIN_VALUE
        /** The aggregated refusal line (review C1-1 adv 3: one line per refusal flooded the log). */
        var refusedLogNanos = Long.MIN_VALUE
    }

    private val cloudflare = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Session, Boolean>())
    private val watches = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Session, Watch>())
    private val slots = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Session, Slots>())
    private val health = java.util.WeakHashMap<Session, Health>()

    /** Channels whose open runs detached right now: the open owns them, not the caller (identity). */
    private val pending = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Channel, Boolean>())

    private val opener = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "channel-open").apply { isDaemon = true }
    }

    /** [session] rides the Cloudflare tunnel (its floor and dead limit are the longer ones). */
    fun markCloudflare(session: Session) { cloudflare[session] = true }

    fun isCloudflare(session: Session): Boolean = cloudflare[session] == true

    /** [w] watches [session] from now on; null ends it. */
    fun watch(session: Session, w: Watch?) {
        if (w == null) watches.remove(session) else watches[session] = w
        synchronized(health) { health.remove(session) }
    }

    /** [s] is the channel budget of [session] (idempotent). */
    fun slots(session: Session, s: Slots) { slots[session] = s }

    /** Opens that still run detached (diagnostics). */
    fun pendingCount(): Int = synchronized(pending) { pending.size }

    /** When an open on [session] was last confirmed (nanoTime), or null: never (the exec transport's swap spacing). */
    fun lastConfirmedNanos(session: Session): Long? =
        synchronized(health) { health[session]?.lastConfirmedNanos?.takeIf { it != Long.MIN_VALUE } }

    /**
     * Session channels (exec, shell, sftp, subsystem — what sshd's `MaxSessions` counts; not port forwards) JSch has on
     * [session] right now, opens still pending included; null when JSch's channel table cannot be read.
     */
    fun sessionChannels(session: Session): Int? = try {
        val f = Channel::class.java.getDeclaredField("pool").apply { isAccessible = true }
        val sf = Channel::class.java.getDeclaredField("session").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pool = f.get(null) as java.util.Vector<Channel>
        synchronized(pool) {
            pool.count { c ->
                sf.get(c) === session && (c is ChannelExec || c is com.jcraft.jsch.ChannelShell ||
                    c is com.jcraft.jsch.ChannelSftp || c is com.jcraft.jsch.ChannelSubsystem)
            }
        }
    } catch (_: Throwable) {
        null
    }

    /** Whether the open of [ch] still runs detached (tests). */
    fun isPending(ch: Channel): Boolean = synchronized(pending) { ch in pending }

    /** Disconnect [ch] unless its open still runs detached — that open closes it once it resolves. */
    fun disconnect(ch: Channel) {
        if (synchronized(pending) { ch in pending }) return
        try { ch.disconnect() } catch (_: Exception) {}
    }

    /**
     * Open [ch] (an exec's command already set) within [budgetMs], from a blocking context. Throws what `ch.connect` threw
     * (`channel is not opened.` also when the budget ran out first, exit status -1), or [ExecUnavailable] TIMEOUT when
     * the budget is below the route's floor.
     */
    fun connect(ch: Channel, budgetMs: Long) {
        val session = try { ch.session } catch (_: Exception) { null }
        val t = tuning
        val onCf = session != null && isCloudflare(session)
        val floor = if (onCf) t.minOpenCfMs else t.minOpenLanMs
        if (budgetMs < floor) {
            throw ExecUnavailable(ExecUnavailableReason.TIMEOUT,
                "exec unavailable: channel not opened, $budgetMs ms left is below the ${route(onCf)} floor of $floor ms",
                belowFloor = true)
        }
        val openMs = maxOf(if (onCf) t.openDeadCfMs else t.openDeadLanMs, budgetMs)
        val open = Open(ch, session, onCf, openMs)
        val startedNanos = System.nanoTime()
        synchronized(pending) { pending.add(ch) }
        try {
            opener.execute { open.run() }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            synchronized(pending) { pending.remove(ch) }
            throw JSchException("channel is not opened.", e)
        }
        var interrupted = false
        val answered = try {
            open.done.await(budgetMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            interrupted = true
            false
        }
        try {
            if (answered || !open.abandon()) {
                // resolved within the budget (or in the instant the caller gave up): the caller owns the channel again
                synchronized(pending) { pending.remove(ch) }
                open.failure?.let { throw it }
                return
            }
            // the open itself confirmed, only the exec request still runs (sshd slow to start the command): not unanswered (H9)
            val opened = try { ch.isConnected } catch (_: Exception) { false }
            // a full budget is what the caller really waited, not what it asked for (an interrupted wait is short)
            val waitedMs = (System.nanoTime() - startedNanos) / 1_000_000
            if (session != null && !opened) noteUnanswered(session, onCf, waitedMs >= t.fullBudgetMs, waitedMs, startedNanos)
            throw JSchException(if (opened) "channel request: timeout" else "channel is not opened.")
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun route(onCf: Boolean) = if (onCf) "cf" else "lan"

    private class Open(val ch: Channel, val session: Session?, val onCf: Boolean, val openMs: Long) {
        val done = CountDownLatch(1)
        @Volatile var failure: Exception? = null
        private var resolved = false
        private var abandoned = false
        /** The abandoned open is over (closed, out of [pending]): a slot held after this must be given back at once. */
        private var finished = false
        private var giveBack: (() -> Unit)? = null

        fun run() {
            val t0 = System.nanoTime()
            var err: Throwable? = null
            try {
                ch.connect(openMs.toInt())
            } catch (e: Throwable) {
                err = e
            }
            val tookMs = (System.nanoTime() - t0) / 1_000_000
            val gaveUp = synchronized(this) {
                resolved = true
                failure = err?.let { it as? Exception ?: JSchException("channel open failed: $it", it) }
                abandoned
            }
            try {
                // the health first: a caller that returns on the countdown sees its effect (tests, and the next open)
                if (session != null) noteResolved(session, onCf, ch, err, tookMs >= openMs, tookMs)
            } catch (e: Throwable) {
                FileLogger.log(TAG, "channel health bookkeeping failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                // whatever happened above, neither the slot nor the channel may stay behind (review C1-1)
                done.countDown()
                if (gaveUp) {
                    // confirmed late: connected, so the disconnect sends CLOSE and sshd frees the session (no orphan);
                    // never confirmed: there is nothing on the wire to close
                    try { ch.disconnect() } catch (_: Throwable) {}
                    synchronized(pending) { pending.remove(ch) }
                    synchronized(this) { finished = true; giveBack }?.invoke()
                    FileLogger.log(TAG, "detached channel open on ${route(onCf)} resolved after ${tookMs}ms " +
                        (if (err == null) "(answered late: closed)" else "(${err.message})"))
                }
            }
        }

        /** The caller gives up: false when the open resolved first (the caller then takes its result). */
        fun abandon(): Boolean {
            val keep = synchronized(this) {
                if (resolved) return false
                abandoned = true
                // under the channel's monitor: JSch's open waits on it, so the opener reads the no-op after this (JMM)
                (ch as? ChannelExec)?.let { synchronized(it) { it.setCommand(NOOP_COMMAND) } }
                session?.let { slots[it] }
            }
            // outside the lock: the gate takes its own. The open may end meanwhile: then the slot goes back right here
            val back = keep?.hold() ?: return true
            val now = synchronized(this) { if (finished) true else { giveBack = back; false } }
            if (now) back()
            return true
        }
    }

    /** The caller's budget ran out before the server answered the open. */
    private fun noteUnanswered(session: Session, onCf: Boolean, fullBudget: Boolean, waitedMs: Long, startedNanos: Long) {
        val (counted, n) = synchronized(health) {
            val h = health.getOrPut(session) { Health() }
            // in a row: started after the transport last answered, and after the previous counted open was given up
            val counts = fullBudget && startedNanos > h.lastConfirmedNanos && startedNanos >= h.lastCountedNanos
            if (counts) {
                h.noAnswer++
                h.lastCountedNanos = System.nanoTime()
            }
            counts to h.noAnswer
        }
        FileLogger.log(TAG, "channel open on ${route(onCf)} unanswered after ${waitedMs}ms" + when {
            counted -> " ($n/${tuning.deadAfter} in a row)"
            fullBudget -> " (overlaps an open already counted, not counted)"
            else -> " (small budget, not counted)"
        } + " — the open goes on detached")
        if (counted && n >= tuning.deadAfter) reportDead(session, "$n channel opens in a row unanswered within a full budget")
    }

    private fun noteResolved(session: Session, onCf: Boolean, ch: Channel, err: Throwable?, ranOut: Boolean, tookMs: Long) {
        val notOpened = err is JSchException && err.message?.contains("channel is not opened") == true
        val status = try { ch.exitStatus } catch (_: Exception) { -1 }
        when {
            // confirmed (a later `channel request: timeout` included: the open itself was answered)
            err == null || (err is JSchException && err.message?.contains("channel request") == true) ->
                synchronized(health) {
                    val h = health.getOrPut(session) { Health() }
                    h.noAnswer = 0
                    h.lastConfirmedNanos = System.nanoTime()
                }
            notOpened && status == -1 && ranOut ->
                reportDead(session, "no answer to a channel open on ${route(onCf)} within ${tookMs}ms")
            notOpened && status in 1..2 && slots[session]?.headroom() == true -> {
                val now = System.nanoTime()
                val (n, log) = synchronized(health) {
                    val h = health.getOrPut(session) { Health() }
                    h.refused++
                    // the first ones until the rule fires, then one line per minute (a stuck tmux refuses dozens a second)
                    val log = h.refused <= tuning.refusedAfter || now - h.refusedLogNanos >= REFUSED_LOG_EVERY_NS
                    if (log) h.refusedLogNanos = now
                    h.refused to log
                }
                if (log) FileLogger.log(TAG, "channel refused on ${route(onCf)} (exitStatus=$status) although our channels leave room " +
                    "($n so far, rule ${tuning.refusedAfter}): orphan channels on the server")
                if (n >= tuning.refusedAfter) reportDead(session, "$n channels refused with room left (orphans)")
            }
        }
    }

    private fun reportDead(session: Session, why: String) {
        val w = watches[session] ?: return
        if (w.dead(session, why)) synchronized(health) { health.remove(session) }
    }

    /** Back to the defaults and no state (tests). */
    fun resetForTest() {
        tuning = Tuning()
        watches.clear()
        slots.clear()
        cloudflare.clear()
        synchronized(health) { health.clear() }
    }

    private const val TAG = "ChannelOpen"
    private const val REFUSED_LOG_EVERY_NS = 60_000_000_000L
}
