package com.clauderemote.connection

import com.clauderemote.model.AuthMethod
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.ExecChannels
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.ExecUnavailableReason
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

    /**
     * Gated exec channels of a server when exec routing is active for it, null =
     * legacy (→ `ConnectionRegistry.activeExecChannels`). Set once at startup. When it
     * returns channels, [withSession] runs the block on the exec transport's session
     * and holds one SHORT permit for the duration of the block, so every one-off user
     * of this helper is inside the per-server channel budget without being edited.
     */
    @Volatile internal var execChannelsProvider: ((serverId: String) -> ExecChannels?)? = null

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
     *
     * Order of transports: the server's exec transport (holding one SHORT permit while
     * [block] runs; skipped when it is not UP) → a pooled transport the server already
     * has → a fresh connection, unless [allowFresh] is false, in which case
     * [ExecUnavailable] ([ExecUnavailableReason.NOT_UP]) is thrown instead of paying a
     * handshake. Only NOT_UP falls through to those transports; a permit wait that timed out
     * ([ExecUnavailableReason.GATE_TIMEOUT]) is thrown to the caller. A caller that can skip a tick (a poller) passes `allowFresh = false`.
     */
    suspend fun <T> withSession(
        server: SshServer,
        timeout: Int = 10000,
        allowFresh: Boolean = true,
        block: suspend (Session) -> T
    ): T {
        val gated = execChannelsProvider?.invoke(server.id)
        if (gated != null) {
            var entered = false
            try {
                return gated.withPermit(server.id, ExecKind.SHORT) { sess -> entered = true; block(sess) }
            } catch (e: ExecUnavailable) {
                // An ExecUnavailable raised by the block itself is the block's business.
                if (entered) throw e
                // A saturated gate (permit wait timed out) must not be walked around: the caller waits or fails.
                if (e.reason != ExecUnavailableReason.NOT_UP) throw e
                // Exec transport not UP: fall through to the legacy transports.
            }
        }

        // Reuse an existing pooled transport when the server already has one —
        // no fresh handshake, and we must NOT disconnect it (it's shared).
        val reuse = liveSessionProvider?.invoke(server)?.takeIf { it.isConnected }
        if (reuse != null) return block(reuse)

        if (!allowFresh) throw ExecUnavailable(ExecUnavailableReason.NOT_UP, "no live transport and a fresh connection is not allowed")

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
