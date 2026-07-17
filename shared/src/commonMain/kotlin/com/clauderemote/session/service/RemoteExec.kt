package com.clauderemote.session.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
