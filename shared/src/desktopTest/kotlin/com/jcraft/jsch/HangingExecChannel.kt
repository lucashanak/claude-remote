package com.jcraft.jsch

/**
 * An exec channel whose output never comes (a stuck link): its read blocks until the reading thread is interrupted,
 * like jsch's own piped stream. In jsch's package only because the constructor is package-private.
 */
class HangingExecChannel : ChannelExec() {
    @Volatile var disconnected = false
    private val pipe = java.io.PipedInputStream()
    @Suppress("unused") private val writer = java.io.PipedOutputStream(pipe)
    override fun getInputStream(): java.io.InputStream = pipe
    override fun connect(connectTimeout: Int) {}
    override fun disconnect() { disconnected = true }
    override fun isClosed(): Boolean = false
}
