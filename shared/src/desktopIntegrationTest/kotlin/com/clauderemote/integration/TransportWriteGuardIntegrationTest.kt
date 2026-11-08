package com.clauderemote.integration

import com.clauderemote.connection.ServerTransportPool
import com.clauderemote.connection.SshManager
import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.connection.TransportWriteGuard
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.execReadWithWatchdog
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.ChannelShell
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * C5 (trace-lan-umrti): on a direct socket a JSch write that throws after the packet was encrypted (on Android: the
 * window-change written from the main thread, `NetworkOnMainThreadException`) leaves the client's cipher a packet ahead
 * of sshd's; JSch's `setPtySize` swallows the exception. Against a real sshd:
 *  - the mechanism: one failed write inside `setPtySize`, then one keystroke — sshd ends the connection;
 *  - with [TransportWriteGuard] (the socket of every direct transport): the failed write closes the transport at once,
 *    the tab's shell reports its loss, the log names the exception and the thread, and the next lease builds a NEW
 *    working transport. Never a transport kept with a broken cipher.
 * The failure is injected under the socket's stream ([TransportWriteGuard.outputForTest] / the test's own factory).
 */
class TransportWriteGuardIntegrationTest {
    private var fixture: SshdFixture? = null
    private val storage = ServerStorage(InMemoryKeyValueStore())
    private val pools = CopyOnWriteArrayList<ServerTransportPool>()
    private val managers = CopyOnWriteArrayList<SshManager>()
    private val sessions = CopyOnWriteArrayList<com.jcraft.jsch.Session>()
    private val logLines = CopyOnWriteArrayList<String>()
    private val savedPlatformLog = FileLogger.platformLog

    @get:org.junit.Rule(order = 0) val perTestTimeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(120)

    @get:org.junit.Rule(order = 1) val dumpOnFailure = object : org.junit.rules.TestWatcher() {
        override fun failed(e: Throwable?, description: org.junit.runner.Description?) {
            println("---- log lines of ${description?.methodName} ----\n" + logLines.takeLast(80).joinToString("\n"))
        }
    }

    @Before fun setUp() {
        CommFlags.resetForTest()
        FileLogger.platformLog = { _, tag, message, t -> logLines += "$tag: $message" + (t?.let { " — ${it.message}" } ?: "") }
    }

    @After fun tearDown() {
        TransportWriteGuard.outputForTest = null
        FileLogger.platformLog = savedPlatformLog
        managers.forEach { it.shutdownForTest() }
        pools.forEach { it.teardownAll() }
        sessions.forEach { runCatching { it.disconnect() } }
        CommFlags.resetForTest()
        fixture?.close()
    }

    private fun fx(): SshdFixture {
        SshdFixture.unsupportedReason?.let { error("integration environment incomplete — $it") }
        return SshdFixture.start().also { fixture = it }
    }

    /** A stream that throws ONCE, on the first write after [armed] is set, without passing a byte on. */
    private class ThrowOnce(private val out: OutputStream, private val armed: AtomicBoolean) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (armed.compareAndSet(true, false)) throw IOException("injected write failure")
            out.write(b, off, len)
        }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    private fun downWithin(s: com.jcraft.jsch.Session, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (s.isConnected && System.currentTimeMillis() < end) Thread.sleep(20)
        return !s.isConnected
    }

    /** The mechanism (no guard): the encrypted window-change never left, the next packet is garbage to sshd. */
    @Test fun aWindowChangeWhoseWriteFailedBreaksTheTransportAtTheNextPacket() {
        val f = fx()
        val armed = AtomicBoolean(false)
        val sess = SshSessionHelper.createSession(f.server, 10_000).also { sessions += it }
        sess.setSocketFactory(object : com.jcraft.jsch.SocketFactory {
            override fun createSocket(host: String, port: Int) = Socket(host, port)
            override fun getInputStream(socket: Socket) = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = ThrowOnce(socket.getOutputStream(), armed)
        })
        sess.connect(10_000)
        val ch = sess.openChannel("shell") as ChannelShell
        ch.setPtyType("xterm-256color")
        val keys = ch.outputStream
        ch.connect(10_000)
        Thread.sleep(300)
        armed.set(true)
        ch.setPtySize(100, 40, 800, 640)                  // JSch swallows the failure: nothing is said
        assertTrue(!armed.get(), "the window-change hit the failing write")
        assertTrue(sess.isConnected, "nothing noticed it yet")
        keys.write("echo hi\n".toByteArray()); keys.flush()  // the next packet, encrypted a packet ahead of sshd
        assertTrue(downWithin(sess, 10_000), "sshd ends the connection on the garbage")
    }

    /** C5: the guard closes the transport on the failed write; the shell says so; a new transport works. */
    @Test fun aFailedWriteClosesTheTransportAndTheNextLeaseBuildsANewOne() {
        val f = fx()
        storage.addServer(f.server)
        val pool = ServerTransportPool(storage).also { pools += it }
        val armed = AtomicBoolean(false)
        TransportWriteGuard.outputForTest = { raw -> ThrowOnce(raw, armed) }
        val m = SshManager(storage, transportPool = pool).also { managers += it }
        val lost = CountDownLatch(1)
        runBlocking { m.connect(f.server, onOutput = {}, onConnectionLost = { lost.countDown() }) }
        val first = m.getSession()!!
        Thread.sleep(300)
        armed.set(true)
        m.resize(100, 40)
        assertTrue(lost.await(5, TimeUnit.SECONDS), "the shell reports its loss at once (not at the next keepalive, 10 s on)")
        assertTrue(!armed.get(), "the window-change hit the failing write")
        assertTrue(downWithin(first, 5_000), "the broken transport is closed")
        val line = logLines.firstOrNull { "Transport write to" in it }
        assertTrue(line != null && "failed on thread" in line && "java.io.IOException: injected write failure" in line, "said why and where: $line")
        assertEquals(1, logLines.count { "Transport write to" in it }, "once")

        val m2 = SshManager(storage, transportPool = pool).also { managers += it }
        runBlocking { m2.connect(f.server, onOutput = {}, onConnectionLost = {}) }
        val second = m2.getSession()!!
        assertNotSame(first, second, "a new transport")
        assertEquals("ok", runBlocking { execReadWithWatchdog(second, "echo ok", totalMs = 10_000) }.trim())
    }

    /** Kolo 4 (G4): the standalone transport of an [SshManager] (no pool) rides the guard too. */
    @Test fun aStandaloneShellTransportIsGuardedToo() {
        val f = fx()
        storage.addServer(f.server)
        val armed = AtomicBoolean(false)
        TransportWriteGuard.outputForTest = { raw -> ThrowOnce(raw, armed) }
        val m = SshManager(storage).also { managers += it }
        val lost = CountDownLatch(1)
        runBlocking { m.connect(f.server, onOutput = {}, onConnectionLost = { lost.countDown() }) }
        Thread.sleep(300)
        armed.set(true)
        m.resize(100, 40)
        assertTrue(lost.await(5, TimeUnit.SECONDS), "the shell reports its loss at once")
        assertTrue(logLines.any { "Transport write to" in it && "injected write failure" in it }, "${logLines.takeLast(10)}")
    }

    /** Kolo 4 (G5): a fresh connection of `SshSessionHelper.withSession` rides the guard too. */
    @Test fun aFreshHelperConnectionIsGuardedToo() {
        val f = fx()
        val savedExec = SshSessionHelper.execChannelsProvider
        val savedFallback = SshSessionHelper.fallbackRunner
        SshSessionHelper.execChannelsProvider = null
        SshSessionHelper.fallbackRunner = null
        try {
            val armed = AtomicBoolean(false)
            TransportWriteGuard.outputForTest = { raw -> ThrowOnce(raw, armed) }
            val down = runBlocking {
                SshSessionHelper.withSession(f.server) { sess ->
                    val ch = sess.openChannel("shell") as ChannelShell
                    ch.connect(10_000)
                    armed.set(true)
                    ch.setPtySize(100, 40, 800, 640)         // JSch swallows the failure; the guard does not
                    downWithin(sess, 3_000)
                }
            }
            assertTrue(down, "the guard closed the broken transport")
        } finally {
            SshSessionHelper.execChannelsProvider = savedExec
            SshSessionHelper.fallbackRunner = savedFallback
        }
    }

    /** Kolo 4 (C5f): the last shell of a transport closed from the main thread: the pool disconnects it off that thread. */
    @Test fun theLastShellClosedFromTheMainThreadReleasesItsTransportOffIt() {
        val f = fx()
        storage.addServer(f.server)
        val threads = CopyOnWriteArrayList<String>()
        FileLogger.platformLog = { _, tag, message, _ ->
            logLines += "$tag: $message"
            if ("Disconnected drained transport" in message) threads += Thread.currentThread().name
        }
        val pool = ServerTransportPool(storage).also { pools += it }
        val m = SshManager(storage, transportPool = pool).also { managers += it }
        runBlocking { m.connect(f.server, onOutput = {}, onConnectionLost = {}) }
        val main = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "main-like") }
        try {
            runBlocking { kotlinx.coroutines.withContext(main.asCoroutineDispatcher()) { m.disconnect() } }
        } finally { main.shutdown() }
        assertEquals(1, threads.size, "the pool disconnected the drained transport: ${logLines.takeLast(10)}")
        assertTrue(!threads.single().startsWith("main-like"), "off the caller's thread: ${threads.single()}")
    }
}
