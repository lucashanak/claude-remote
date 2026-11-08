package com.clauderemote.connection

import com.clauderemote.util.FileLogger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * C5 kolo 4: the socket factory of a direct transport. JSch applies no connect timeout to a socket from a factory, so
 * the guard must (G7: a dead LAN address would otherwise hang the connect for minutes); and a write to a socket that
 * was already closed (our own cleanup) is no broken cipher (adv N7).
 */
class TransportWriteGuardTest {
    private val savedLog = FileLogger.platformLog
    private val logLines = CopyOnWriteArrayList<String>()

    @AfterTest fun tearDown() {
        FileLogger.platformLog = savedLog
        TransportWriteGuard.resolverForTest = null
    }

    /** Kolo 5 (V15, adv N6): a name lookup that hangs ends within the connect timeout, like JSch's own socket. */
    @Test fun aSlowNameLookupEndsWithinTheConnectTimeout() {
        TransportWriteGuard.resolverForTest = { Thread.sleep(5_000); InetAddress.getLoopbackAddress() }
        val task = FutureTask { runCatching { TransportWriteGuard(500).createSocket("slow-dns.invalid", 22).close() } }
        val t0 = System.nanoTime()
        Thread(task).apply { isDaemon = true; start() }
        val r = try { task.get(4, TimeUnit.SECONDS) } catch (_: java.util.concurrent.TimeoutException) { null }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(r != null, "the lookup is bounded (still waiting after 4 s)")
        assertTrue(r.exceptionOrNull() is java.net.SocketTimeoutException, "a timeout: ${r.exceptionOrNull()}")
        assertTrue(ms < 2_000, "within its 500 ms timeout (plus slack): $ms ms")
    }

    @Test fun aConnectToAnAddressThatNeverAnswersEndsWithinItsTimeout() {
        val task = FutureTask { runCatching { TransportWriteGuard(500).createSocket("10.255.255.1", 22).close() } }
        val t0 = System.nanoTime()
        Thread(task).apply { isDaemon = true; start() }
        val r = try { task.get(5, TimeUnit.SECONDS) } catch (_: java.util.concurrent.TimeoutException) { null }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(r != null, "the connect is bounded (still hanging after 5 s)")
        assertTrue(r.exceptionOrNull() is java.io.IOException, "it fails: ${r.exceptionOrNull()}")
        assertTrue(ms < 2_000, "within its 500 ms timeout (plus slack): $ms ms")
    }

    @Test fun aWriteToASocketWeClosedOurselvesIsNotReportedAsABrokenCipher() {
        FileLogger.platformLog = { _, tag, message, _ -> logLines += "$tag: $message" }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val guard = TransportWriteGuard(2_000)
            val s = guard.createSocket(InetAddress.getLoopbackAddress().hostAddress, server.localPort)
            server.accept().use { _ ->
                val out = guard.getOutputStream(s)
                out.write(1)                                   // a healthy write: nothing said
                assertTrue(logLines.none { "Transport write" in it }, "$logLines")
                s.close()                                      // our own cleanup
                runCatching { out.write(2) }
                val line = logLines.single { "Transport write" in it }
                assertTrue("already closed" in line && "cipher" !in line && "on thread" in line, line)
            }
        }
    }
}
