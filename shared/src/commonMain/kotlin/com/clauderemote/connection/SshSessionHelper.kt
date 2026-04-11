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
