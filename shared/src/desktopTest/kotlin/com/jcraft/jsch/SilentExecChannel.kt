package com.jcraft.jsch

import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * C5 kolo 5 (V12): an exec channel that opens and then never says a word — its output stream blocks until [disconnect]
 * closes it (what a watchdog does at its bound). Records the thread of each [disconnect]. In jsch's package only because
 * the constructor is package-private.
 */
class SilentExecChannel : ChannelExec() {
    val disconnectThreads = CopyOnWriteArrayList<String>()
    private val feed = PipedOutputStream()
    private val out = PipedInputStream(feed)
    @Volatile private var open = false

    override fun connect(connectTimeout: Int) { open = true }
    override fun isConnected(): Boolean = open
    override fun getInputStream(): InputStream = out
    override fun getExitStatus(): Int = -1
    override fun disconnect() {
        disconnectThreads += Thread.currentThread().name
        open = false
        try { feed.close() } catch (_: Exception) {}
    }
}
