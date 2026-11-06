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
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The AUTO decision of [TransportResolver] as the exec transport and the tabs see it (dávka B, B2):
 * a death WAVE of one shared transport is one Tailscale strike, the probe of one server does not hold up
 * another server's resolve, and every probe attempt and every Cloudflare decision is logged with its reason.
 *
 * The "Tailscale endpoint" is a local [ServerSocket] that either answers with a banner, closes at once, or
 * accepts and stays silent; nothing leaves the loopback interface.
 */
class TransportResolverProbeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedPlatformLog = FileLogger.platformLog
    private val sockets = CopyOnWriteArrayList<java.io.Closeable>()

    @BeforeTest fun setUp() {
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
    }

    @AfterTest fun tearDown() {
        FileLogger.platformLog = savedPlatformLog
        sockets.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    private fun resolver() = TransportResolver(scope, ConnectionRegistry(ServerStorage(FakeKeyValueStore()), TabManager()))

    /** A loopback "sshd" whose every accepted connection gets [onAccept]; returns the port and the accept count. */
    private fun endpoint(onAccept: (Socket) -> Unit): Pair<Int, AtomicInteger> {
        val ss = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).also { sockets += it }
        val accepted = AtomicInteger()
        Thread {
            while (!ss.isClosed) {
                val c = runCatching { ss.accept() }.getOrNull() ?: break
                sockets += c
                accepted.incrementAndGet()
                runCatching { onAccept(c) }
            }
        }.apply { isDaemon = true; start() }
        return ss.localPort to accepted
    }

    private fun autoServer(id: String, port: Int, tailscaleHost: String = "127.0.0.1") = SshServer(
        id = id, name = "box-$id", host = "ssh.example.org", port = port, username = "u",
        useCloudflareProxy = true, tailscaleHost = tailscaleHost, transport = ServerTransport.AUTO,
    )

    private fun tab(id: String, server: SshServer) = ClaudeSession(
        id = id, server = server, folder = "~/x", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-x--$id", connectionType = ConnectionType.SSH,
    )

    // ------------------------------------------------------------------ V8: one wave, one strike

    /**
     * 11:00:28 in the phone log: one shared LAN transport died and its 5 tabs reported it within 10 ms; each was a
     * strike, so the second tab already armed the 20 s Cloudflare cooldown. One death of one transport is one strike.
     */
    @Test fun theTabsOfOneDyingTransportAreOneStrikeNotOnePerTab() {
        val r = resolver()
        val base = autoServer("s", 22).copy(transport = ServerTransport.TAILSCALE)
        val ts = base.forTransport(ServerTransport.TAILSCALE)
        val tabs = (1..5).map { tab("t$it", base) }
        tabs.forEach { r.recordConnectSuccess(it.id, base, ts) }
        tabs.forEach { r.maybeCountTsEarlyDeath(it) }
        val cooling = runBlocking { r.resolveTransport(base) }.useCloudflareProxy
        assertEquals(false, cooling, "one wave = one strike; the cooldown needs two: " + logLines.filter { it.contains("Tailscale") })
        assertEquals(1, logLines.count { it.contains("strike 1/2") }, logLines.toString())
    }

    // ------------------------------------------------------------------ the probe mutex is per server

    /** A probe of server A that hangs in its banner read (3 s timeout, two attempts) must not hold up server B's resolve. */
    @Test fun aSlowProbeOfOneServerDoesNotDelayTheResolveOfAnother() {
        val (silentPort, accepted) = endpoint { /* accept and say nothing: the banner read waits for its timeout */ }
        val r = resolver()
        val slow = autoServer("a", silentPort)
        val other = autoServer("b", 22, tailscaleHost = "")   // no Tailscale host: decided without a probe
        val probing = scope.async { r.resolveTransport(slow) }
        val deadline = System.currentTimeMillis() + 5_000
        while (accepted.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, accepted.get(), "server A's probe is running")
        val t0 = System.nanoTime()
        val eff = runBlocking { r.resolveTransport(other) }
        val tookMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(eff.useCloudflareProxy)
        assertTrue(tookMs < 1_000, "server B waited ${tookMs}ms for server A's probe")
        sockets.forEach { runCatching { it.close() } }   // ends A's read at once
        runBlocking { probing.await() }
    }

    // ------------------------------------------------------------------ F2: every attempt and every CF decision logged

    @Test fun everyProbeAttemptIsLoggedWithItsDurationAndOutcome() {
        val (port, accepted) = endpoint { it.close() }   // connect ok, no banner: a failed attempt
        val r = resolver()
        val s = autoServer("s", port)
        val eff = runBlocking { r.resolveTransport(s) }
        assertTrue(eff.useCloudflareProxy, "both attempts failed: Cloudflare")
        assertEquals(2, accepted.get())
        val attempts = logLines.filter { Regex("""probe box-s 127\.0\.0\.1:$port attempt [12]/2 failed after \d+ms""").containsMatchIn(it) }
        assertEquals(2, attempts.size, logLines.toString())
        assertTrue(logLines.any { it.contains("Transport for box-s: CLOUDFLARE (probe failed)") }, "the CF decision and its reason: $logLines")
    }

    @Test fun aSuccessfulProbeAttemptIsLoggedToo() {
        val (port, _) = endpoint { c -> c.getOutputStream().write("SSH-2.0-test\r\n".toByteArray()); c.getOutputStream().flush() }
        val r = resolver()
        val s = autoServer("s", port)
        val eff = runBlocking { r.resolveTransport(s) }
        assertEquals("127.0.0.1", eff.host)
        assertTrue(logLines.any { Regex("""probe box-s 127\.0\.0\.1:$port attempt 1/2 ok after \d+ms""").containsMatchIn(it) }, logLines.toString())
    }

    @Test fun aCloudflareDecisionDuringTheCooldownSaysSo() {
        val r = resolver()
        val s = autoServer("s", 22)
        val ts = s.forTransport(ServerTransport.TAILSCALE)
        r.noteServerEarlyDeath(s, ts, 100)
        r.noteServerEarlyDeath(s, ts, 100)   // two strikes: cooldown
        val eff = runBlocking { r.resolveTransport(s) }
        assertTrue(eff.useCloudflareProxy)
        assertTrue(logLines.any { it.contains("Transport for box-s: CLOUDFLARE (cooling down)") }, logLines.toString())
    }
}
