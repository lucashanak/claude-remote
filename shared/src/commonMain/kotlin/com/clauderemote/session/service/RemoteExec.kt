package com.clauderemote.session.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val watchdog = launch {
        kotlinx.coroutines.delay(totalMs)
        try { ch.disconnect() } catch (_: Exception) {}
    }
    try {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            ch.connect(connectMs)
            input.bufferedReader().readText()
        }
    } finally {
        watchdog.cancel()
        try { ch.disconnect() } catch (_: Exception) {}
    }
}

// ---------------------------------------------------------------------------
// Seam: RemoteExec / ExecChannels contracts. Interfaces + production stubs so
// the pool, reconcile and bootstrap work can code and fake against them without
// editing each other's files. The pool work replaces the stubs (gate,
// generation, exec transport); the signatures and the error contract below are
// the contract.
// ---------------------------------------------------------------------------

/** What an exec channel is for; permits are per kind (SFTP shares SHORT). */
internal enum class ExecKind { SHORT, LONG, SFTP }

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
     * [ExecKind.SHORT] or [ExecKind.LONG] (SFTP is not an exec; `IllegalArgumentException`).
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
    private fun liveSession(serverId: String): com.jcraft.jsch.Session =
        sessionOf(serverId)?.takeIf { it.isConnected } ?: throw ExecUnavailable(ExecUnavailableReason.NOT_UP)

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
            try { ch.disconnect() } catch (_: Exception) {}
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
 * Stub: today's behaviour — one exec on the pooled/live session bounded by a
 * watchdog (compare [execReadWithWatchdog]), additionally returning the exit
 * status and supporting paced stdin, with the typed error contract of [RemoteExec].
 */
internal class PooledRemoteExec(private val channels: ExecChannels) : RemoteExec {
    override suspend fun run(
        serverId: String,
        cmd: String,
        stdin: ByteArray?,
        totalMs: Long,
        kind: ExecKind,
        paceStdin: Pace,
    ): ExecResult {
        require(kind != ExecKind.SFTP) { "RemoteExec.run is exec-only; use ExecChannels.use(kind = SFTP)" }
        return channels.use(serverId, kind) { raw ->
            val ch = raw as com.jcraft.jsch.ChannelExec
            ch.setCommand(cmd)
            val out = if (stdin != null) ch.outputStream else null
            ch.inputStream = null
            val input = ch.inputStream
            val timedOut = AtomicBoolean(false)
            // Own scope, NOT a child of the caller: if the caller is cancelled while
            // parked in a blocking read, the wall-clock bound must still fire and
            // free the IO thread.
            val watchdog = CoroutineScope(Dispatchers.Default).launch {
                delay(totalMs)
                timedOut.set(true)
                try { ch.disconnect() } catch (_: Exception) {}
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    ch.connect(5000)
                    if (stdin != null && out != null) {
                        var off = 0
                        while (off < stdin.size) {
                            val n = minOf(paceStdin.chunkBytes, stdin.size - off)
                            out.write(stdin, off, n)
                            out.flush()
                            off += n
                            if (off < stdin.size) delay(paceStdin.delayMs)
                        }
                        out.close()
                    }
                    val text = input.bufferedReader().readText()
                    // exit-status arrives just after EOF; give it a short bounded wait
                    var waited = 0
                    while (ch.exitStatus < 0 && waited < 2000 && !timedOut.get()) { delay(20); waited += 20 }
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
                        ExecUnavailable(ExecUnavailableReason.REFUSED, "exec unavailable: ${e.message} (exitStatus=${ch.exitStatus})")
                    else -> ExecUnavailable(ExecUnavailableReason.IO, "exec unavailable: ${e.javaClass.simpleName}: ${e.message}")
                }
            } finally {
                watchdog.cancel()
                try { ch.disconnect() } catch (_: Exception) {}
            }
        }
    }
}
