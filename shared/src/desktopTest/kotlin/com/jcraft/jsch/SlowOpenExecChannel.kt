package com.jcraft.jsch

/**
 * An exec channel whose OPEN the server confirms only after [openMs] (a link that stalls for a moment), and whose output
 * then never comes. Like jsch's own `connect`, an interrupt while the confirmation is pending gives up the open
 * ("channel is not opened") — jsch then has no recipient and never sends CLOSE, which on a real sshd leaves the session
 * the late confirmation opened (K5-1). Records whether that happened and whether the channel was closed once open.
 * In jsch's package only because the constructor is package-private.
 */
class SlowOpenExecChannel(private val openMs: Long) : ChannelExec() {
    @Volatile var openInterrupted = false
    @Volatile var opened = false
    @Volatile var closedAfterOpen = false
    private val pipe = java.io.PipedInputStream()
    @Suppress("unused") private val writer = java.io.PipedOutputStream(pipe)
    override fun getInputStream(): java.io.InputStream = pipe
    override fun connect(connectTimeout: Int) {
        try {
            Thread.sleep(openMs)
        } catch (e: InterruptedException) {
            openInterrupted = true
            throw JSchException("channel is not opened.")
        }
        opened = true
    }
    override fun disconnect() { if (opened) closedAfterOpen = true }
    override fun isClosed(): Boolean = false
}
