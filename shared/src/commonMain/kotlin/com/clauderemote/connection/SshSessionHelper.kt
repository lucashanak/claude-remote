package com.clauderemote.connection

import com.clauderemote.model.AuthMethod
import com.clauderemote.model.SshServer
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session

/**
 * Utility to create a quick SSH session for one-off operations
 * (tmux listing, folder browsing, etc.). Handles Cloudflare proxy.
 */
object SshSessionHelper {

    /**
     * Optional hook to a live pooled session for a server. Set once at startup
     * (→ ConnectionRegistry.pooledSession). When it returns a connected session,
     * [withSession] runs the one-off op on THAT transport instead of opening a
     * fresh SSH-over-Cloudflare connection — periodic polls (scanRemoteSessions,
     * SessionStatusPoller, transcript poll) were each doing a full handshake
     * every time, which dominated idle data usage.
     */
    @Volatile var liveSessionProvider: ((SshServer) -> Session?)? = null

    fun createSession(server: SshServer, timeout: Int = 10000): Session {
        val jsch = JSch()
        if (server.authMethod == AuthMethod.KEY && server.privateKey != null) {
            jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
        }
        val sess = jsch.getSession(server.username, server.host, server.port)
        if (server.authMethod == AuthMethod.PASSWORD && server.password != null) {
            sess.setPassword(server.password)
        }
        sess.setConfig("StrictHostKeyChecking", "no")
        sess.timeout = timeout
        // Keepalive (interval is MILLISECONDS in JSch): a quiet-but-alive
        // operation longer than the socket timeout survives (the keepalive
        // resets it), while a silently-dead link is torn down in ~20s instead
        // of hanging the helper's exec read on a flaky network.
        sess.setServerAliveInterval(10_000)
        sess.setServerAliveCountMax(2)

        if (server.useCloudflareProxy) {
            sess.setProxy(CloudflareProxy(server.host, server.cloudflareToken))
        }

        return sess
    }

    /**
     * Create, connect, execute block, disconnect. Returns block result.
     */
    suspend fun <T> withSession(
        server: SshServer,
        timeout: Int = 10000,
        block: suspend (Session) -> T
    ): T {
        // Reuse an existing pooled transport when the server already has one —
        // no fresh handshake, and we must NOT disconnect it (it's shared).
        val reuse = liveSessionProvider?.invoke(server)?.takeIf { it.isConnected }
        if (reuse != null) return block(reuse)

        val sess = createSession(server, timeout)
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                sess.connect(timeout)
            }
            block(sess)
        } finally {
            try { sess.disconnect() } catch (_: Exception) {}
        }
    }
}
