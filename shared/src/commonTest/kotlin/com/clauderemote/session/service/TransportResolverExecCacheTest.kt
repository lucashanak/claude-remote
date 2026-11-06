package com.clauderemote.session.service

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.ServerTransport
import com.clauderemote.model.SshServer
import com.clauderemote.session.TabManager
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dávka B, B2: a FAILED probe is not served from the 10 s cache to the exec transport (it re-probes; the tabs keep the
 * one-decision-per-wave cache), and the V8 debounce is per TRANSPORT: two transports that die together are still
 * two strikes (the "strike also for LAN" semantics stays).
 */
class TransportResolverExecCacheTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closeables = CopyOnWriteArrayList<java.io.Closeable>()

    @AfterTest fun tearDown() {
        closeables.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    private fun resolver() = TransportResolver(scope, ConnectionRegistry(ServerStorage(FakeKeyValueStore()), TabManager()))

    /** A loopback "sshd" that accepts and hangs up at once (a failed probe attempt); counts the attempts. */
    private fun refusingEndpoint(): Pair<Int, AtomicInteger> {
        val ss = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).also { closeables += it }
        val n = AtomicInteger()
        Thread {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                n.incrementAndGet()
                runCatching { c.close() }
            }
        }.apply { isDaemon = true; start() }
        return ss.localPort to n
    }

    private fun autoServer(port: Int) = SshServer(
        id = "s", name = "box", host = "ssh.example.org", port = port, username = "u",
        useCloudflareProxy = true, tailscaleHost = "127.0.0.1", transport = ServerTransport.AUTO,
    )

    @Test fun aFailedProbeIsCachedForTheTabsButNotServedToTheExecTransport() {
        val (port, attempts) = refusingEndpoint()
        val r = resolver()
        val s = autoServer(port)
        runBlocking {
            assertTrue(r.resolveTransport(s).useCloudflareProxy)
            assertEquals(2, attempts.get(), "one probe = two attempts")
            assertTrue(r.resolveTransport(s).useCloudflareProxy)
            assertEquals(2, attempts.get(), "a tab within 10 s gets the cached decision")
            assertTrue(withContext(ResolveForExec) { r.resolveTransport(s) }.useCloudflareProxy)
            assertEquals(4, attempts.get(), "the exec transport does not take a cached FAILED probe: it probes itself")
        }
    }

    @Test fun aSuccessfulDecisionIsStillSharedWithTheExecTransport() {
        val ss = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).also { closeables += it }
        val n = AtomicInteger()
        Thread {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                n.incrementAndGet()
                runCatching { c.getOutputStream().write("SSH-2.0-x\r\n".toByteArray()); c.close() }
            }
        }.apply { isDaemon = true; start() }
        val r = resolver()
        val s = autoServer(ss.localPort)
        runBlocking {
            assertEquals("127.0.0.1", r.resolveTransport(s).host)
            assertEquals("127.0.0.1", withContext(ResolveForExec) { r.resolveTransport(s) }.host)
        }
        assertEquals(1, n.get(), "a good decision of the wave is reused, also by the exec transport")
    }

    private fun tab(id: String, server: SshServer) = ClaudeSession(
        id = id, server = server, folder = "~/x", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-x--$id", connectionType = ConnectionType.SSH,
    )

    @Test fun twoTransportsDyingTogetherAreStillTwoStrikes() {
        val r = resolver()
        val base = SshServer(id = "s", name = "box", host = "ssh.example.org", username = "u",
            useCloudflareProxy = true, tailscaleHost = "192.168.1.7", transport = ServerTransport.TAILSCALE)
        val ts = base.forTransport(ServerTransport.TAILSCALE)
        val a = Any(); val b = Any()
        val tabs = (1..6).map { tab("t$it", base) }
        tabs.forEach { r.recordConnectSuccess(it.id, base, ts) }
        // three tabs on transport A, three on transport B, all dying in the same millisecond
        tabs.forEachIndexed { i, t -> r.maybeCountTsEarlyDeath(t, if (i < 3) a else b) }
        assertTrue(runBlocking { r.resolveTransport(base) }.useCloudflareProxy, "two transports = two strikes = the cooldown")
    }

    @Test fun theSameTransportReportedAgainAfterTheWindowIsANewStrike() {
        val r = resolver()
        val base = SshServer(id = "s", name = "box", host = "ssh.example.org", username = "u",
            useCloudflareProxy = true, tailscaleHost = "192.168.1.7", transport = ServerTransport.TAILSCALE)
        val ts = base.forTransport(ServerTransport.TAILSCALE)
        val a = Any()
        val t1 = tab("t1", base); val t2 = tab("t2", base)
        r.recordConnectSuccess(t1.id, base, ts); r.recordConnectSuccess(t2.id, base, ts)
        r.maybeCountTsEarlyDeath(t1, a)
        r.maybeCountTsEarlyDeath(t2, a)
        assertEquals(false, runBlocking { r.resolveTransport(base) }.useCloudflareProxy, "one transport, one wave: one strike")
        Thread.sleep(1_100)
        r.recordConnectSuccess(t1.id, base, ts)
        r.maybeCountTsEarlyDeath(t1, a)
        assertTrue(runBlocking { r.resolveTransport(base) }.useCloudflareProxy, "a later death (> 1 s) is a new strike")
    }
}
