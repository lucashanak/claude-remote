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
/** The result of a block that ran on a fallback transport; null instead of it = the block did not run (no room, no transport). */
internal class Fallback<T>(val value: T)

/**
 * Runs a block on a transport the server already has, holding whatever that transport's channel budget demands for
 * the whole block (ConnectionRegistry implements it):
 *  - the server needs a room check (exec routing on, or shells packed beyond the legacy 3 per transport): one COUNTED
 *    channel on a shell transport with headroom, given back in `finally` under `NonCancellable`;
 *  - a legacy server: a pooled shell transport, nothing counted (the legacy lease would otherwise lose a slot).
 * Null = no transport, or none with room; the block did not run. A block's own null is wrapped ([Fallback]), so it is
 * never mistaken for "no room" and the block never runs twice.
 */
internal interface FallbackRunner {
    suspend fun <T> run(server: SshServer, block: suspend (Session) -> T): Fallback<T>?
}

object SshSessionHelper {

    /**
     * LOOK-ONLY view of the pooled session a fallback would get for a server (ConnectionRegistry.fallbackPooledSession):
     * nothing is counted, so [withSession] never runs a block on it — it uses [fallbackRunner]. Kept for diagnostics and
     * the wiring tests; set by the orchestrator.
     */
    @Volatile var liveSessionProvider: ((SshServer) -> Session?)? = null

    /**
     * The pooled step of [withSession]: periodic polls (scanRemoteSessions, SessionStatusPoller, transcript poll) used to
     * do a full handshake every time, which dominated idle data usage, so a one-off op reuses a transport the server
     * already has — through this runner, which counts its channel where the server needs it. Set by the orchestrator
     * (→ ConnectionRegistry). Null = no pooled step.
     */
    @Volatile internal var fallbackRunner: FallbackRunner? = null

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
     * has ([fallbackRunner]: a counted channel when the server needs a room check, skipped
     * without room) → a fresh connection, unless [allowFresh] is false, in which case
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

        // Reuse an existing pooled transport when the server already has one — no fresh handshake, and we must NOT
        // disconnect it (it's shared). The runner holds the transport's channel count for the whole block.
        fallbackRunner?.run(server, block)?.let { return it.value }

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
