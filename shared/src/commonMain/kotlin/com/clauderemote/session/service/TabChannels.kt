package com.clauderemote.session.service

import com.clauderemote.util.FileLogger
import com.jcraft.jsch.Session
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One channel a tab's poller holds on a TAB transport outside the exec transport ([TabChannels.reserve]). It is a
 * token: [release] gives back exactly what this reservation took, at most once, whatever other reservations of the
 * same session do meanwhile. (A per-session counter gave back a slot another reservation still held, and a release
 * racing a reserve could lose the other one's count for good: a slot leaked until the transport died.)
 *
 * [counted] is false when the server needed no room check at the moment of the reservation (a legacy server, at most
 * 3 shells per transport): nothing was taken, so nothing is given back, even if the check turns on meanwhile.
 */
class TabChannel internal constructor(
    val session: Session,
    private val giveBack: (suspend (Session) -> Unit)? = null,
) {
    private val released = AtomicBoolean(false)
    val counted: Boolean get() = giveBack != null

    /** Idempotent and atomic: only the first call gives the slot back. Call it from `finally` under `NonCancellable`. */
    suspend fun release() {
        if (released.compareAndSet(false, true)) giveBack?.invoke(session)
    }
}

/**
 * The channel budget of one server's tab transports, as the pollers of a tab see it (TranscriptStream,
 * SessionStatusPoller). The registry supplies it ([ConnectionRegistry.tabChannels]).
 */
interface TabChannels {
    /** One channel on [session]'s transport, counted when the server needs a room check; null = no room: skip the tick. */
    suspend fun reserve(session: Session): TabChannel?

    /** A tick of [source] was skipped for want of room (the `poll skipped (no room)` line, rate-limited). */
    fun skipped(source: String) {}

    /** Route, usability and occupancy of [session]'s transport, for the log line of a channel that failed to open. */
    fun describe(session: Session): String = "route=unknown"

    /** A channel of [source] on [session] failed to open: one line with the exit status and [describe] (acceptance metric). */
    fun channelOpenFailed(source: String, session: Session, exitStatus: Int?, e: Throwable) {
        FileLogger.log(CHANNEL_TAG, channelOpenFailedLine(source, exitStatus, describe(session), e))
    }

    companion object {
        /** No room check at all (tests, callers without a registry): every reservation succeeds and counts nothing. */
        val UNCOUNTED: TabChannels = object : TabChannels {
            override suspend fun reserve(session: Session): TabChannel = TabChannel(session)
        }
    }
}

internal const val CHANNEL_TAG = "ChannelBudget"

/**
 * `channel open failed src=<who> exitStatus=<n|n/a> route=… usable=… shells=… headroom=…: <exception>`. exitStatus -1 =
 * the server never answered the open (a dead transport); 1/2 = sshd refused it (`MaxSessions`). n/a = no channel object.
 */
internal fun channelOpenFailedLine(source: String, exitStatus: Int?, where: String, e: Throwable): String =
    "channel open failed src=$source exitStatus=${exitStatus ?: "n/a"} $where: ${e.javaClass.simpleName}: ${e.message}"

/** True for the failures of opening a channel (as opposed to a command that ran and failed). */
internal fun isChannelOpenFailure(e: Throwable): Boolean {
    val m = e.message ?: return false
    return e is com.jcraft.jsch.JSchException &&
        (m.contains("channel is not opened") || m.contains("session is down") || m.contains("channel request"))
}
