package com.clauderemote.session.service

import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot exec with a hard wall-clock bound. JSch's blocking
 * `readText()` cannot be interrupted by coroutine cancellation, so a plain
 * withTimeout would still leave the IO thread parked on a dead channel —
 * on a flaky network those parked threads accumulate until Dispatchers.IO
 * is starved and even reconnects can't get a thread (the "only an app
 * restart helps" state). The watchdog instead force-disconnects the
 * channel at [totalMs], which closes the stream and releases the reader.
 */
internal suspend fun execReadWithWatchdog(
    sshSession: com.jcraft.jsch.Session,
    cmd: String,
    connectMs: Int = 5000,
    totalMs: Long = 15_000,
): String = kotlinx.coroutines.coroutineScope {
    val ch = sshSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
    ch.setCommand(cmd)
    ch.inputStream = null
    val input = ch.inputStream
    // C5 kolo 4 (adv N3): the CLOSE of the channel is a packet on a shared transport — never written on the caller's
    // thread (UI callers run this from Android's main thread)
    val watchdog = launch(Dispatchers.IO) {
        kotlinx.coroutines.delay(totalMs)
        com.clauderemote.connection.ChannelOpen.disconnect(ch)
    }
    try {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            com.clauderemote.connection.ChannelOpen.connect(ch, connectMs.toLong())
            input.bufferedReader().readText()
        }
    } finally {
        watchdog.cancel()
        kotlinx.coroutines.withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            com.clauderemote.connection.ChannelOpen.disconnect(ch)
        }
    }
}

// ---------------------------------------------------------------------------
// Seam: RemoteExec / ExecChannels contracts. Interfaces + production stubs so
// the pool, reconcile and bootstrap work can code and fake against them without
// editing each other's files. The pool work replaces the stubs (gate,
// generation, exec transport); the signatures and the error contract below are
// the contract.
// ---------------------------------------------------------------------------

/**
 * What an exec channel is for; permits are per kind (SFTP shares SHORT). [POLL] is a SHORT channel of a periodic poller
 * (transcript tick, status, tmux probe/refresh): it never waits for a permit and never takes the last
 * [GatedExecChannels.Limits.interactiveReserve] SHORT permits, which stay for what a user waits on (C1, priority class).
 */
internal enum class ExecKind { SHORT, LONG, SFTP, POLL }

/**
 * C1: the ticks the poll class skipped for want of a permit ([ExecKind.POLL]), counted per server and source and written
 * as ONE line per server at most every [WINDOW_MS] (`poll skipped (no poll permit) server=… transcript=N status=M
 * probe=K in Xs`), so the skips stay measurable without a line per tick. The line goes out with the first skip after
 * the window; a window without skips writes nothing.
 */
internal object PollSkipLog {
    const val WINDOW_MS = 60_000L
    const val TRANSCRIPT = "transcript"
    const val STATUS = "status"
    const val PROBE = "probe"

    private class Window(var since: Long) {
        val counts = linkedMapOf(TRANSCRIPT to 0, STATUS to 0, PROBE to 0)
    }

    private val windows = java.util.concurrent.ConcurrentHashMap<String, Window>()

    /** A periodic tick of [source] on [serverId] was skipped (no poll permit). */
    fun skipped(serverId: String, source: String, now: Long = System.currentTimeMillis()) {
        val w = windows.getOrPut(serverId) { Window(now) }
        val line = synchronized(w) {
            w.counts[source] = (w.counts[source] ?: 0) + 1
            if (now - w.since < WINDOW_MS) null
            else ("poll skipped (no poll permit) server=$serverId " + w.counts.entries.joinToString(" ") { "${it.key}=${it.value}" } +
                " in ${(now - w.since) / 1000}s").also { w.counts.keys.forEach { k -> w.counts[k] = 0 }; w.since = now }
        }
        line?.let { FileLogger.log("ExecChannel", it) }
    }

    /** The counts of the current window of [serverId] (tests, diagnostics). */
    fun counts(serverId: String): Map<String, Int> = windows[serverId]?.let { w -> synchronized(w) { w.counts.toMap() } }.orEmpty()

    /** Forget every window (tests). */
    fun resetForTest() = windows.clear()
}

/** Stdin pacing for CF-tunnelled uploads (SshManager: 4 KB / 20 ms). */
internal data class Pace(val chunkBytes: Int = 4096, val delayMs: Long = 20)

internal data class ExecResult(val stdout: String, val exitStatus: Int)

/**
 * Why exec was not possible:
 *  - [NOT_UP]: no usable exec transport;
 *  - [GATE_TIMEOUT]: waited longer than `acquireTimeoutMs` for a permit;
 *  - [TIMEOUT]: the command did not finish within its wall-clock `totalMs`;
 *  - [REFUSED]: the server refused to open the channel (`MaxSessions`; jsch reports
 *    `JSchException("channel is not opened.")`, `exitStatus` 1 or 2);
 *  - [IO]: the channel broke while writing stdin or reading output.
 */
internal enum class ExecUnavailableReason { NOT_UP, GATE_TIMEOUT, TIMEOUT, REFUSED, IO }

/** "No usable exec transport / no permit / no result". Typed so callers choose a fallback (never null). */
internal class ExecUnavailable(
    val reason: ExecUnavailableReason,
    message: String = "exec unavailable: $reason",
    /**
     * Only with [ExecUnavailableReason.REFUSED]: jsch's `channel is not opened.` with an exit status of
     * 1 or 2 — the server refused the channel (`MaxSessions`), so ONE retry shortly after is worth it
     * (a sibling channel may have closed). Any other refusal is not retried.
     */
    val spillable: Boolean = false,
    /**
     * Only with [ExecUnavailableReason.TIMEOUT]: no channel was opened because less than the route's floor was left
     * ([com.clauderemote.connection.ChannelOpen]). For a periodic poll that is a skipped tick, not an error.
     */
    val belowFloor: Boolean = false,
) : Exception(message)

/**
 * Error contract of [run], binding on every implementation and fake: the result is
 * either COMPLETE or an [ExecUnavailable] is thrown. In particular a timeout is
 * [ExecUnavailableReason.TIMEOUT] and NEVER a partial [ExecResult]; a refused
 * channel is [ExecUnavailableReason.REFUSED] (an implementation with a spill rule
 * applies it before surfacing this); a failing stdin/stdout is
 * [ExecUnavailableReason.IO]. `ExecResult.exitStatus` is the remote status, or -1
 * if the server never reported one.
 */
internal interface RemoteExec {
    /**
     * Run [cmd] on [serverId], optionally feeding [stdin] paced by [paceStdin].
     * Wall-clock bounded by [totalMs] (connect included), enforced independently of
     * the caller: cancelling the caller does not lift the bound. [kind] must be
     * [ExecKind.SHORT], [ExecKind.POLL] or [ExecKind.LONG] (SFTP is not an exec; `IllegalArgumentException`).
     * Throws [ExecUnavailable] as described on this interface.
     */
    suspend fun run(
        serverId: String,
        cmd: String,
        stdin: ByteArray? = null,
        totalMs: Long = 15_000,
        kind: ExecKind = ExecKind.SHORT,
        paceStdin: Pace = Pace(),
    ): ExecResult
}

internal interface ExecChannels {
    /**
     * Scoped channel use. The block receives a freshly OPENED, not yet connected
     * channel (`exec` for SHORT/LONG, `sftp` for SFTP). Contract:
     *  - the channel is disconnected and the permit released when the block exits;
     *  - permits are reset with the transport GENERATION (transport death frees all);
     *  - waiting longer than [acquireTimeoutMs] for a permit throws
     *    [ExecUnavailable] with [ExecUnavailableReason.GATE_TIMEOUT];
     *  - no usable transport throws [ExecUnavailable] with [ExecUnavailableReason.NOT_UP].
     * The stub has no gate (no permits, no generations); it only honours the
     * scoped open/disconnect and NOT_UP parts.
     */
    suspend fun <T> use(
        serverId: String,
        kind: ExecKind,
        acquireTimeoutMs: Long = 5_000,
        block: suspend (com.jcraft.jsch.Channel) -> T,
    ): T

    /**
     * Holds ONE permit of [kind] for the duration of [block] and hands it the live
     * jsch session, for callers that open their own channels (`SshSessionHelper.withSession`).
     * No channel is opened on the caller's behalf. Same permit, generation, timeout
     * and NOT_UP rules as [use]; the stub has no gate and just looks the session up.
     */
    suspend fun <T> withPermit(
        serverId: String,
        kind: ExecKind,
        acquireTimeoutMs: Long = 5_000,
        block: suspend (com.jcraft.jsch.Session) -> T,
    ): T
}

/** Stub: ungated channels on whatever session [sessionOf] returns (today: pooled/live). */
internal class UngatedExecChannels(
    private val sessionOf: (String) -> com.jcraft.jsch.Session?,
) : ExecChannels {
    private fun liveSession(serverId: String): com.jcraft.jsch.Session {
        // a throwing provider is "no usable transport" for the caller, never a stray exception
        val s = try { sessionOf(serverId) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            throw ExecUnavailable(ExecUnavailableReason.NOT_UP, "exec unavailable: session lookup failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        return s?.takeIf { it.isConnected } ?: throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
    }

    override suspend fun <T> use(
        serverId: String,
        kind: ExecKind,
        acquireTimeoutMs: Long,
        block: suspend (com.jcraft.jsch.Channel) -> T,
    ): T {
        val sess = liveSession(serverId)
        val ch = try {
            sess.openChannel(if (kind == ExecKind.SFTP) "sftp" else "exec")
        } catch (e: com.jcraft.jsch.JSchException) {
            throw ExecUnavailable(ExecUnavailableReason.NOT_UP, "exec unavailable: ${e.message}")
        }
        try {
            return block(ch)
        } finally {
            com.clauderemote.connection.ChannelOpen.disconnect(ch)
        }
    }

    override suspend fun <T> withPermit(
        serverId: String,
        kind: ExecKind,
        acquireTimeoutMs: Long,
        block: suspend (com.jcraft.jsch.Session) -> T,
    ): T = block(liveSession(serverId))
}

/**
 * One exec on a pooled session bounded by a watchdog (compare [execReadWithWatchdog]),
 * additionally returning the exit status and supporting paced stdin, with the typed
 * error contract of [RemoteExec]. Works with any [ExecChannels] (the ungated stub or
 * the gated one of the exec transport).
 */
internal class PooledRemoteExec(
    private val channels: ExecChannels,
    /** Pause before the single retry of a spillable refusal. */
    private val refusedRetryDelayMs: Long = 200,
) : RemoteExec {
    override suspend fun run(
        serverId: String,
        cmd: String,
        stdin: ByteArray?,
        totalMs: Long,
        kind: ExecKind,
        paceStdin: Pace,
    ): ExecResult {
        require(kind != ExecKind.SFTP) { "RemoteExec.run is exec-only; use ExecChannels.use(kind = SFTP)" }
        val deadline = System.nanoTime() + totalMs * 1_000_000
        var retried = false
        while (true) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) throw ExecUnavailable(ExecUnavailableReason.TIMEOUT, "exec timed out after $totalMs ms")
            try {
                return runOnce(serverId, cmd, stdin, deadline, totalMs, kind, paceStdin)
            } catch (e: ExecUnavailable) {
                // Spill: `channel is not opened.` + exit status 1/2 = MaxSessions. A sibling channel may
                // have closed a moment later, so retry exactly once inside the same wall-clock bound.
                if (e.reason == ExecUnavailableReason.REFUSED && e.spillable && !retried) {
                    retried = true
                    FileLogger.log("RemoteExec", "exec channel refused for $serverId (${e.message}) — retrying once")
                    delay(refusedRetryDelayMs)
                    continue
                }
                throw e
            }
        }
    }

    private suspend fun runOnce(
        serverId: String,
        cmd: String,
        stdin: ByteArray?,
        deadline: Long,
        totalMs: Long,
        kind: ExecKind,
        paceStdin: Pace,
    ): ExecResult = channels.use(
        serverId, kind,
        // waiting for a permit and for the transport counts against the same wall-clock bound
        acquireTimeoutMs = minOf(ACQUIRE_MS, ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)),
    ) { raw ->
        val ch = raw as? com.jcraft.jsch.ChannelExec
            ?: throw ExecUnavailable(ExecUnavailableReason.IO, "exec unavailable: channel is not an exec channel (${raw.javaClass.simpleName})")
        // what is left of the bound after the permit and the channel open
        val leftMs = (deadline - System.nanoTime()) / 1_000_000
        if (leftMs <= 0) throw ExecUnavailable(ExecUnavailableReason.TIMEOUT, "exec timed out after $totalMs ms")
        val timedOut = AtomicBoolean(false)
        // Own scope, NOT a child of the caller: if the caller is cancelled while
        // parked in a blocking read, the wall-clock bound must still fire and
        // free the IO thread. Started before the first channel call so it bounds those too.
        val watchdog = CoroutineScope(Dispatchers.Default).launch {
            delay(leftMs)
            timedOut.set(true)
            // a channel whose open still runs detached is the open's to close (ChannelOpen)
            com.clauderemote.connection.ChannelOpen.disconnect(ch)
        }
        try {
            val result = withContext(Dispatchers.IO) {
                ch.setCommand(cmd)
                val out = if (stdin != null) ch.outputStream else null
                ch.inputStream = null
                val input = ch.inputStream
                // never wait for the connect longer than what is left of the bound; below the route's floor no open at
                // all, and an open the bound gave up on goes on detached and is closed when it lands (ChannelOpen)
                com.clauderemote.connection.ChannelOpen.connect(ch, minOf(CONNECT_MS, leftMs))
                if (stdin != null && out != null) {
                    var off = 0
                    while (off < stdin.size) {
                        // the remote side is gone (the command exited without reading everything):
                        // stop feeding it; its exit status says what happened
                        if (ch.isClosed || ch.isEOF) break
                        val n = minOf(paceStdin.chunkBytes, stdin.size - off)
                        out.write(stdin, off, n)
                        out.flush()
                        off += n
                        if (off < stdin.size) delay(paceStdin.delayMs)
                    }
                    // a close on an already-closed channel is not an error (the status below tells the story)
                    try { out.close() } catch (_: java.io.IOException) {}
                }
                val text = input.bufferedReader().readText()
                // exit-status arrives just after EOF; give it a short bounded wait
                var waited = 0
                while (ch.exitStatus < 0 && waited < 2000 && !timedOut.get()) { delay(20); waited += 20 }
                // EOF without a status AND a dead transport = the output is a fragment, not a result
                if (ch.exitStatus < 0 && !timedOut.get() && !sessionConnected(ch)) {
                    throw ExecUnavailable(ExecUnavailableReason.IO, "exec unavailable: transport lost before the command finished (partial output discarded)")
                }
                ExecResult(text, ch.exitStatus)
            }
            if (timedOut.get()) throw ExecUnavailable(ExecUnavailableReason.TIMEOUT, "exec timed out after $totalMs ms")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw when {
                timedOut.get() -> ExecUnavailable(ExecUnavailableReason.TIMEOUT, "exec timed out after $totalMs ms")
                e is ExecUnavailable -> e
                e is com.jcraft.jsch.JSchException && e.message?.contains("channel is not opened") == true ->
                    ExecUnavailable(
                        ExecUnavailableReason.REFUSED,
                        "exec unavailable: ${e.message} (exitStatus=${ch.exitStatus})",
                        spillable = ch.exitStatus in 1..2,
                    )
                else -> ExecUnavailable(ExecUnavailableReason.IO, "exec unavailable: ${e.javaClass.simpleName}: ${e.message}")
            }
        } finally {
            watchdog.cancel()
            // C5 kolo 5 (adv N5): the CLOSE off the caller's thread — a UI caller (account usage) runs this from main
            withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { com.clauderemote.connection.ChannelOpen.disconnect(ch) }
        }
    }

    /** Whether the channel's transport is still up; an unknown session counts as up (nothing to conclude). */
    private fun sessionConnected(ch: com.jcraft.jsch.Channel): Boolean =
        try { ch.session.isConnected } catch (_: Exception) { true }

    internal companion object {
        /** The open's budget; at least [com.clauderemote.connection.ChannelOpen.Tuning.fullBudgetMs], or no open of it counts. */
        const val CONNECT_MS = 5_000L
        const val ACQUIRE_MS = 5_000L
    }
}

/**
 * [ExecChannels] over the exec transport: the channel budget of one server is
 * enforced HERE, so the sum of all exec/SFTP channels stays below the sshd's
 * `MaxSessions` (default 10) whatever the callers do.
 *
 * Permits per server: SHORT 6 (SFTP shares them), LONG 2 (notify watcher, streamd),
 * 8 channels in total. A periodic poller ([ExecKind.POLL]) takes a SHORT permit only while more than
 * [Limits.interactiveReserve] are free and never waits for one (no permit = the tick is skipped by its caller);
 * an open the caller gave up on keeps its slot until it resolves ([com.clauderemote.connection.ChannelOpen]). Permits belong to a transport GENERATION
 * ([ExecTransportManager.generation]): when the transport dies every permit of the
 * old generation is void and waiters re-evaluate against the new one — a leaked
 * permit cannot outlive its transport.
 *
 * When the exec transport is not UP: a SHORT/SFTP call fails with
 * [ExecUnavailableReason.NOT_UP] after [upWaitMs] (the caller falls back to its tab
 * session); a LONG call waits [longFallbackAfterMs] and then opens on the live shell
 * transport instead ([fallbackSession]) under its own small limit
 * ([Limits.fallbackLong]), because the watcher/streamd must not go dark just because
 * the exec transport is reconnecting.
 *
 * A fallback stream is counted against the shell transport it borrowed ([reserveFallback]) and is
 * ended when the exec transport comes Up again, so its owner reopens it on the exec transport.
 *
 * [onTransportTrouble] is told when opening or using a channel failed in a way that
 * may mean the transport is dead (the manager then checks it at once instead of at
 * its next 5 s poll).
 */
internal class GatedExecChannels(
    private val manager: ExecTransportManager,
    private val fallbackSession: (String) -> com.jcraft.jsch.Session? = { null },
    private val upWaitMs: Long = 2_000,
    private val longFallbackAfterMs: Long = 3_000,
    private val limits: Limits = Limits(),
    private val onTransportTrouble: (String) -> Unit = {},
    /**
     * A long stream starts on the shell transport [fallbackSession] returned: the owner of that
     * transport must count the channel against its capacity, deciding under its lock whether it still fits
     * (`ServerTransportPool.reserveFallbackChannel`); false = the room went to someone else between the
     * look and the claim, and the stream does not start ([ExecUnavailableReason.NOT_UP]).
     */
    private val reserveFallback: suspend (String, com.jcraft.jsch.Session) -> Boolean = { _, _ -> true },
    /** The fallback stream ended: give the capacity back (`ServerTransportPool.releaseChannel`). */
    private val releaseFallback: suspend (String, com.jcraft.jsch.Session) -> Unit = { _, _ -> },
) : ExecChannels {
    data class Limits(
        val short: Int = 6,
        val long: Int = 2,
        val fallbackLong: Int = 2,
        /** SHORT permits a [ExecKind.POLL] never takes: attach, listing, launch, a reply, `pollNow` of the Stop path. */
        val interactiveReserve: Int = 2,
    ) {
        val total: Int get() = short + long
    }

    private enum class Where { EXEC, FALLBACK }

    private class Gate {
        var gen: Long = -1
        var short = 0
        var long = 0
        var fallback = 0
        /** Slots of opens their callers gave up on, until those opens resolve (ChannelOpen.Slots.hold). */
        var pending = 0
        /** Bumped on every release / reset so waiters wake. */
        val version = kotlinx.coroutines.flow.MutableStateFlow(0L)
    }

    private class Permit(
        val serverId: String,
        val gate: Gate,
        val where: Where,
        val isLong: Boolean,
        val gen: Long,
        val session: com.jcraft.jsch.Session,
    )

    private val gates = java.util.concurrent.ConcurrentHashMap<String, Gate>()

    /** Permits currently held on [serverId]'s exec transport: (short, long, fallback). For tests and diagnostics. */
    fun held(serverId: String): Triple<Int, Int, Int> =
        gates[serverId]?.let { g -> synchronized(g) { Triple(g.short, g.long, g.fallback) } } ?: Triple(0, 0, 0)

    /** Slots still held for opens their callers gave up on (tests and diagnostics). */
    fun pendingOpens(serverId: String): Int = gates[serverId]?.let { g -> synchronized(g) { g.pending } } ?: 0

    /** The budget of the exec transport [gen] of [gate], as [com.clauderemote.connection.ChannelOpen] sees it. */
    private fun slotsOf(gate: Gate, gen: Long, session: com.jcraft.jsch.Session) = object : com.clauderemote.connection.ChannelOpen.Slots {
        override fun hold(): () -> Unit {
            val took = synchronized(gate) { if (gate.gen == gen) { gate.pending++; true } else false }
            if (!took) return {}
            val once = AtomicBoolean(false)
            return {
                if (once.compareAndSet(false, true)) {
                    synchronized(gate) { if (gate.gen == gen && gate.pending > 0) gate.pending-- }
                    gate.version.update { it + 1 }
                }
            }
        }
        // review C1-1: the gate's own count (<= 8) is always below 10; what counts is what JSch really has open on the
        // transport, pending opens included, against sshd's MaxSessions. Unreadable = no claim of room (the rule stays off).
        override fun headroom(): Boolean = synchronized(gate) { gate.gen == gen } &&
            (com.clauderemote.connection.ChannelOpen.sessionChannels(session)
                ?.let { it < com.clauderemote.connection.ChannelOpen.tuning.maxSessions } ?: false)
    }

    override suspend fun <T> use(
        serverId: String,
        kind: ExecKind,
        acquireTimeoutMs: Long,
        block: suspend (com.jcraft.jsch.Channel) -> T,
    ): T {
        val permit = acquire(serverId, kind, acquireTimeoutMs)
        try {
            val ch = try {
                permit.session.openChannel(if (kind == ExecKind.SFTP) "sftp" else "exec")
            } catch (e: com.jcraft.jsch.JSchException) {
                onTransportTrouble(serverId)
                throw ExecUnavailable(ExecUnavailableReason.NOT_UP, "exec unavailable: ${e.message}")
            }
            try {
                return if (permit.where == Where.FALLBACK) endWhenExecIsUp(serverId, ch, block) else block(ch)
            } catch (e: ExecUnavailable) {
                if (e.reason == ExecUnavailableReason.IO) onTransportTrouble(serverId)
                throw e
            } finally {
                // C5 kolo 5 (adv N5): off the caller's thread, see PooledRemoteExec.runOnce
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { com.clauderemote.connection.ChannelOpen.disconnect(ch) }
            }
        } finally {
            release(permit)
        }
    }

    /** Runs [block] on a fallback channel and disconnects [ch] (the stream sees EOF) as soon as the exec transport is Up. */
    private suspend fun <T> endWhenExecIsUp(
        serverId: String,
        ch: com.jcraft.jsch.Channel,
        block: suspend (com.jcraft.jsch.Channel) -> T,
    ): T = kotlinx.coroutines.coroutineScope {
        val monitor = launch(Dispatchers.Default) {
            manager.state(serverId).first { it is LinkState.Up }
            FileLogger.log("RemoteExec", "exec transport of $serverId is up again — ending the fallback stream")
            try { ch.disconnect() } catch (_: Exception) {}
        }
        try { block(ch) } finally { monitor.cancel() }
    }

    override suspend fun <T> withPermit(
        serverId: String,
        kind: ExecKind,
        acquireTimeoutMs: Long,
        block: suspend (com.jcraft.jsch.Session) -> T,
    ): T {
        val permit = acquire(serverId, kind, acquireTimeoutMs)
        try {
            return block(permit.session)
        } finally {
            release(permit)
        }
    }

    private suspend fun acquire(serverId: String, kind: ExecKind, acquireTimeoutMs: Long): Permit {
        val poll = kind == ExecKind.POLL
        // a poller never waits: no permit now = its tick is skipped (C1, priority class)
        val deadline = System.nanoTime() + (if (poll) 0 else acquireTimeoutMs) * 1_000_000
        val gate = gates.getOrPut(serverId) { Gate() }
        val isLong = kind == ExecKind.LONG
        var stale = 0
        while (true) {
            val gen = manager.generation(serverId)
            // a transport that is down in a backoff will not come up within the wait: do not make every poll pay it
            val state = manager.state(serverId).value
            val waitUp = if (state is LinkState.Down || state is LinkState.Closed) 0
            else minOf(if (isLong) longFallbackAfterMs else upWaitMs, remainingMs(deadline).coerceAtLeast(0))
            var session = manager.session(serverId) ?: if (waitUp > 0) manager.await(serverId, waitUp) else null
            var where = Where.EXEC
            if (session == null && poll && state is LinkState.Connecting) {
                // review C1-1: a poll does not wait for the transport coming up, nor does it go onto the shell transports
                // meanwhile — the tick is skipped (its caller counts it)
                throw ExecUnavailable(ExecUnavailableReason.GATE_TIMEOUT, "exec unavailable: the exec transport is connecting, tick skipped")
            }
            if (session == null) {
                val fb = if (isLong) {
                    try { fallbackSession(serverId)?.takeIf { it.isConnected } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                } else null
                session = fb ?: throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
                where = Where.FALLBACK
            }
            val permit = waitForPermit(serverId, gate, where, isLong, poll, gen, session, deadline)
            // the transport may have died or been replaced while we waited
            if (permit != null && where == Where.FALLBACK) {
                val counted = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { reserveFallback(serverId, session) }
                if (counted) return permit
                // refused: give the local permit back WITHOUT telling the pool (it counted nothing)
                synchronized(permit.gate) { if (permit.gate.fallback > 0) permit.gate.fallback-- }
                permit.gate.version.update { it + 1 }
                throw ExecUnavailable(ExecUnavailableReason.NOT_UP)
            }
            if (permit != null && manager.generation(serverId) == gen && session.isConnected) {
                com.clauderemote.connection.ChannelOpen.slots(session, slotsOf(gate, gen, session))
                return permit
            }
            if (permit != null) release(permit)
            if (++stale >= 3) throw ExecUnavailable(ExecUnavailableReason.NOT_UP, "exec unavailable: the exec transport kept changing")
        }
    }

    /** The permit, or null when the transport generation moved on while waiting (caller re-evaluates). */
    private suspend fun waitForPermit(
        serverId: String,
        gate: Gate,
        where: Where,
        isLong: Boolean,
        poll: Boolean,
        gen: Long,
        session: com.jcraft.jsch.Session,
        deadline: Long,
    ): Permit? {
        while (true) {
            val seen = gate.version.value
            val got = synchronized(gate) {
                if (where == Where.EXEC) {
                    if (gate.gen != gen) {
                        if (gate.gen > gen) return null // a newer generation already runs
                        gate.gen = gen
                        gate.short = 0; gate.long = 0; gate.pending = 0   // the old transport's permits are void
                    }
                    val inUse = gate.short + gate.long + gate.pending
                    if (isLong) {
                        if (gate.long < limits.long && inUse < limits.total) { gate.long++; true } else false
                    } else {
                        val cap = if (poll) limits.short - limits.interactiveReserve else limits.short
                        if (gate.short + gate.pending < cap && inUse < limits.total) { gate.short++; true } else false
                    }
                } else {
                    if (gate.fallback < limits.fallbackLong) { gate.fallback++; true } else false
                }
            }
            if (got) return Permit(serverId, gate, where, isLong, gen, session)
            if (where == Where.EXEC && manager.generation(serverId) != gen) return null
            val left = remainingMs(deadline)
            if (left <= 0) throw ExecUnavailable(ExecUnavailableReason.GATE_TIMEOUT,
                if (poll) "exec unavailable: no poll permit (the interactive reserve stays free), tick skipped"
                else "exec unavailable: no channel permit within the acquire timeout")
            // wake on a release, and re-check the generation at least every 250 ms
            kotlinx.coroutines.withTimeoutOrNull(minOf(left, 250)) { gate.version.first { it != seen } }
        }
    }

    private suspend fun release(p: Permit) {
        if (p.where == Where.FALLBACK) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { releaseFallback(p.serverId, p.session) }
        }
        synchronized(p.gate) {
            when (p.where) {
                Where.FALLBACK -> if (p.gate.fallback > 0) p.gate.fallback--
                Where.EXEC -> if (p.gate.gen == p.gen) {
                    if (p.isLong) { if (p.gate.long > 0) p.gate.long-- } else { if (p.gate.short > 0) p.gate.short-- }
                }
            }
        }
        p.gate.version.update { it + 1 }
    }

    private fun remainingMs(deadline: Long): Long = (deadline - System.nanoTime()) / 1_000_000
}
