package com.clauderemote.connection

import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.RecordingShellChannel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C5 (trace-lan-umrti): `SshManager.resize` was called on Android's main thread (layout, redraw kick) and wrote the
 * window-change there — a socket write that throws after JSch encrypted the packet, swallowed by JSch: the transport's
 * cipher a packet ahead of sshd's. The send now runs on the manager's IO scope, serialized, a burst coalesced into its
 * last size; closing a tab (the UI's coroutine) also writes off the caller's thread.
 */
class SshManagerResizeTest {
    private fun manager(channel: RecordingShellChannel): SshManager {
        val m = SshManager(ServerStorage(FakeKeyValueStore()))
        SshManager::class.java.getDeclaredField("session").also { it.isAccessible = true }.set(m, FakeSession())
        SshManager::class.java.getDeclaredField("channel").also { it.isAccessible = true }.set(m, channel)
        return m
    }

    private fun eventually(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end && !cond()) Thread.sleep(10)
        assertTrue(cond(), what)
    }

    @Test fun aResizeNeverWritesOnTheCallersThread() {
        val ch = RecordingShellChannel()
        val m = manager(ch)
        val caller = Thread.currentThread().name
        m.resize(96, 50)
        assertEquals(96 to 50, m.lastCols to m.lastRows, "the size is remembered at once")
        eventually("the window-change went out") { ch.ptys.isNotEmpty() }
        val pty = ch.ptys.single()
        assertEquals(96 to 50, pty.cols to pty.rows)
        assertTrue(!pty.thread.startsWith(caller), "not on the caller's (main) thread: ${pty.thread}")
    }

    @Test fun aBurstOfResizesSendsTheOneInFlightAndThenOnlyTheLastSize() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ch = RecordingShellChannel { _, _ -> if (inside.count > 0) { inside.countDown(); release.await(5, TimeUnit.SECONDS) } }
        val m = manager(ch)
        m.resize(80, 24)
        assertTrue(inside.await(5, TimeUnit.SECONDS), "the first send runs")
        m.resize(90, 30); m.resize(95, 40); m.resize(96, 50)   // while it hangs: a layout burst
        release.countDown()
        eventually("the last size went out") { ch.ptys.size >= 2 }
        Thread.sleep(300)
        assertEquals(listOf(80 to 24, 96 to 50), ch.ptys.map { it.cols to it.rows }, "coalesced: ${ch.ptys}")
    }

    @Test fun closingFromTheMainThreadClosesTheChannelOffIt() {
        val ch = RecordingShellChannel()
        val m = manager(ch)
        val main = Executors.newSingleThreadExecutor { r -> Thread(r, "main-like") }
        try {
            runBlocking { withContext(main.asCoroutineDispatcher()) { m.disconnect() } }
        } finally { main.shutdown() }
        assertEquals(1, ch.disconnectThreads.size)
        // (in tests the coroutine debug mode appends " @coroutine#n" to a thread's name)
        val thread = ch.disconnectThreads.single()
        assertTrue(!thread.startsWith("main-like"), "the channel's close packet is written off the caller's thread: $thread")
    }
}
