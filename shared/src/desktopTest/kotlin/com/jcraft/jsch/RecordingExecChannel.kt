package com.jcraft.jsch

import java.util.concurrent.CopyOnWriteArrayList

/**
 * C5 kolo 4: an exec channel that never opens (its OPEN is refused at once, as a full server answers) and records the
 * thread of each [disconnect] — the CLOSE packet a real channel writes. Lives in jsch's package only because the
 * constructor is package-private.
 */
class RecordingExecChannel : ChannelExec() {
    val disconnectThreads = CopyOnWriteArrayList<String>()

    override fun connect(connectTimeout: Int) { throw JSchException("channel is not opened.") }
    override fun isConnected(): Boolean = false
    override fun disconnect() { disconnectThreads += Thread.currentThread().name }
}
