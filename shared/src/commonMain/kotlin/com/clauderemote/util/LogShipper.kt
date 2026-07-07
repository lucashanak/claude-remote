package com.clauderemote.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Ships the app's [FileLogger] lines to the server, one file per install:
 * `~/.claude-remote/logs/<appId>.log`. Makes the phone's logs readable from
 * the dev box — no adb, no hunting for the device — which is where every
 * "why did notifications misbehave abroad" investigation actually happens.
 *
 * Design: [offer] only appends to a bounded in-memory ring (it is called from
 * inside FileLogger.log — no IO, no logging, or it would recurse). A single
 * loop flushes every [FLUSH_MS] through ONE short exec channel on whatever
 * live pooled SSH session the orchestrator can provide; no connection → the
 * batch is requeued and retried next tick (bounded, oldest lines dropped
 * with a marker). Flush failures are NEVER logged through FileLogger — that
 * would feed the buffer it failed to drain.
 */
class LogShipper(
    private val appId: String,
    private val scope: CoroutineScope,
    /** Any live jsch session to the target server, or null when offline. */
    private val liveSession: () -> com.jcraft.jsch.Session?,
) {
    private val buffer = ArrayDeque<String>()
    private var dropped = 0
    private val lock = Any()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        FileLogger.log(TAG, "Shipping logs to ~/.claude-remote/logs/$appId.log")
        FileLogger.remoteSink = ::offer
        job = scope.launch {
            while (isActive) {
                delay(FLUSH_MS)
                try {
                    flush()
                } catch (_: Exception) {
                    // Deliberately silent — see class kdoc.
                }
            }
        }
    }

    /** Buffer-only, called from within FileLogger.log — must not log or block. */
    fun offer(line: String) {
        synchronized(lock) {
            if (buffer.size >= MAX_LINES) {
                buffer.removeFirst()
                dropped++
            }
            buffer.addLast(line)
        }
    }

    private suspend fun flush() {
        val batch: List<String>
        val droppedNow: Int
        synchronized(lock) {
            if (buffer.isEmpty()) return
            batch = buffer.toList()
            buffer.clear()
            droppedNow = dropped
            dropped = 0
        }
        val sess = liveSession()
        if (sess == null || !sess.isConnected) {
            requeue(batch, droppedNow)
            return
        }
        try {
            withContext(Dispatchers.IO) {
                val ch = sess.openChannel("exec") as com.jcraft.jsch.ChannelExec
                // Size-cap the remote file on each flush (10 MB → keep 5 MB
                // tail) so a chatty install can't grow it unbounded.
                ch.setCommand(
                    "D=\"${'$'}HOME/.claude-remote/logs\"; mkdir -p \"${'$'}D\"; F=\"${'$'}D/$appId.log\"; " +
                        "if [ -f \"${'$'}F\" ] && [ \"${'$'}(wc -c < \"${'$'}F\")\" -gt 10485760 ]; then " +
                        "tail -c 5242880 \"${'$'}F\" > \"${'$'}F.tmp\" && mv \"${'$'}F.tmp\" \"${'$'}F\"; fi; " +
                        "cat >> \"${'$'}F\""
                )
                val os = ch.outputStream
                ch.connect(5_000)
                try {
                    // SimpleDateFormat (not java.time) — same minSdk-safe
                    // choice as FileLogger itself.
                    val stamp = java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US
                    ).format(java.util.Date())
                    val header = "--- flush $stamp ---\n"
                    os.write(header.toByteArray(Charsets.UTF_8))
                    if (droppedNow > 0) {
                        os.write("[shipper dropped $droppedNow lines]\n".toByteArray(Charsets.UTF_8))
                    }
                    for (line in batch) os.write(line.toByteArray(Charsets.UTF_8))
                    os.flush()
                    os.close() // EOF → remote `cat` exits
                    // Give cat a moment to drain; bounded so a frozen socket
                    // can't wedge the flush loop.
                    val deadline = System.currentTimeMillis() + 5_000
                    while (!ch.isClosed && System.currentTimeMillis() < deadline) {
                        delay(100)
                    }
                } finally {
                    try { ch.disconnect() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            requeue(batch, droppedNow)
        }
    }

    /** Put an unflushed batch back at the FRONT, respecting the cap. */
    private fun requeue(batch: List<String>, droppedNow: Int) {
        synchronized(lock) {
            dropped += droppedNow
            val reversed = batch.asReversed()
            for ((i, line) in reversed.withIndex()) {
                if (buffer.size >= MAX_LINES) {
                    // Count EVERY line we're abandoning, not just this one —
                    // the "[dropped N]" marker exists for exactly the offline
                    // investigations where under-counting would mislead.
                    dropped += reversed.size - i
                    break
                }
                buffer.addFirst(line)
            }
        }
    }

    companion object {
        private const val TAG = "LogShipper"
        private const val FLUSH_MS = 30_000L
        private const val MAX_LINES = 1000
    }
}
