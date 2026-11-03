package com.jcraft.jsch

/**
 * A jsch [Session] that never touches a socket, for unit tests of the exec layer. It lives in
 * jsch's own package only because `Session`'s constructor is package-private; nothing else of
 * jsch is touched.
 */
class FakeSession(
    @Volatile var connected: Boolean = true,
    private val channels: (String) -> Channel = { error("no channel scripted for $it") },
) : Session(JSch(), "fake", "fake-host", 22) {
    @Volatile var disconnects = 0
        private set
    @Volatile var keepAliveMs = -1
        private set

    override fun isConnected(): Boolean = connected
    override fun openChannel(type: String): Channel = channels(type)
    override fun disconnect() { disconnects++; connected = false }
    override fun setServerAliveInterval(interval: Int) { keepAliveMs = interval }
    override fun getServerAliveInterval(): Int = keepAliveMs
}
