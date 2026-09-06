package com.clauderemote.util

import java.util.concurrent.atomic.AtomicLong

/**
 * Coarse per-stream data-usage meter, logged periodically so we can see where
 * the bytes actually go before optimizing.
 *
 *  - [terminalBytes] are DECODED terminal output bytes — the SSH/ET transport
 *    zlib-compresses these on the wire, so real data is a fraction of this.
 *  - [transcriptBytes] are the on-wire streamd payload (base64, and gzipped
 *    once the compressed protocol is negotiated) — close to real data.
 *  - [platformNetBytes] is the real RX/TX where the platform exposes it —
 *    per-app on Android (TrafficStats, per-UID), machine-wide on desktop (no
 *    per-process counter without native APIs). See [NetBytes.appScoped]:
 *    callers MUST check it before treating the numbers as this app's own
 *    traffic, since a machine-wide count also includes every other process.
 */
object DataMeter {
    private val terminal = AtomicLong()
    private val transcript = AtomicLong()
    private val poll = AtomicLong()

    fun addTerminal(n: Int) { if (n > 0) terminal.addAndGet(n.toLong()) }
    fun addTranscript(n: Int) { if (n > 0) transcript.addAndGet(n.toLong()) }
    /** Periodic poller payloads (latency echo, usage scrape, streamd heartbeat)
     *  — payload proxy only; the real cost includes SSH channel/keepalive
     *  framing which isn't visible here, so a large residual = that framing. */
    fun addPoll(n: Int) { if (n > 0) poll.addAndGet(n.toLong()) }

    fun terminalBytes(): Long = terminal.get()
    fun transcriptBytes(): Long = transcript.get()
    fun pollBytes(): Long = poll.get()
}

/**
 * Cumulative-since-boot (rx, tx) byte counters.
 *
 * [appScoped] tells callers whether [rx]/[tx] are scoped to this app's own
 * traffic (true — Android, per-UID via TrafficStats) or to the whole machine
 * (false — desktop, summed across all non-loopback interfaces since the JVM
 * has no cheap per-process counter). A residual/"overhead" calculation that
 * subtracts known app traffic from these counters is only meaningful when
 * [appScoped] is true; on a machine-wide count it would attribute every other
 * process's traffic to this app and must not be computed.
 */
data class NetBytes(val rx: Long, val tx: Long, val appScoped: Boolean)

/** Real RX/TX byte counters, or null if the platform doesn't expose them. */
expect fun platformNetBytes(): NetBytes?
