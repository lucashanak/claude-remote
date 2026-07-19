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
 *  - [platformNetBytes] is the app-wide real RX/TX where the platform exposes
 *    it (Android TrafficStats); null on desktop.
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

/** App-wide (rx, tx) byte counters, or null if the platform doesn't expose them. */
expect fun platformNetBytes(): Pair<Long, Long>?
