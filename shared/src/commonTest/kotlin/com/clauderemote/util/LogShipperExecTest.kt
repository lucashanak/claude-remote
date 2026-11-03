package com.clauderemote.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.coroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The log shipper's exec path (`execFlush`): the orchestrator hands it a function that runs the append command
 * through the exec layer, so a flush never opens a channel of its own. Virtual time: the loop flushes every 30 s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogShipperExecTest {
    private val savedSink = FileLogger.remoteSink

    @AfterTest fun restoreSink() { FileLogger.remoteSink = savedSink }

    private class Sent(val cmd: String, val payload: String)

    @Test fun aFlushRunsTheAppendCommandThroughExecFlushWithTheBufferedLinesAndNeverTouchesALiveSession() = runTest {
        val sent = mutableListOf<Sent>()
        val shipper = LogShipper(
            "app-1", this,
            liveSession = { error("the exec path must not look for a live session") },
            execFlush = { cmd, bytes -> sent += Sent(cmd, bytes.decodeToString()); true },
        )
        shipper.start()
        shipper.offer("first line\n")
        shipper.offer("zweite Zeile\n")
        advanceTimeBy(30_001); runCurrent()
        assertEquals(1, sent.size)
        assertEquals(LogShipper.remoteAppendCommand("app-1"), sent[0].cmd)
        assertTrue(sent[0].payload.startsWith("--- flush "), sent[0].payload)
        assertTrue(sent[0].payload.endsWith("first line\nzweite Zeile\n"), sent[0].payload)
        // delivered: the next tick has nothing to send
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, sent.size)
        coroutineContext.cancelChildren()
    }

    @Test fun aFlushThatReportsFailureIsRequeuedAndSentAgainWithTheNextOne() = runTest {
        val sent = mutableListOf<Sent>()
        var ok = false
        val shipper = LogShipper("app-2", this, liveSession = { null }, execFlush = { cmd, bytes ->
            sent += Sent(cmd, bytes.decodeToString()); ok
        })
        shipper.start()
        shipper.offer("kept\n")
        advanceTimeBy(30_001); runCurrent()
        assertEquals(1, sent.size)
        ok = true
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, sent.size, "the failed batch goes again")
        assertTrue(sent[1].payload.contains("kept\n"), sent[1].payload)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, sent.size, "and then it is gone")
        coroutineContext.cancelChildren()
    }

    @Test fun aThrowingExecFlushRequeuesTheBatchToo() = runTest {
        val sent = mutableListOf<String>()
        var boom = true
        val shipper = LogShipper("app-3", this, liveSession = { null }, execFlush = { _, bytes ->
            sent += bytes.decodeToString()
            if (boom) throw java.io.IOException("exec unavailable")
            true
        })
        shipper.start()
        shipper.offer("survives a failed flush\n")
        advanceTimeBy(30_001); runCurrent()
        boom = false
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, sent.size)
        assertTrue(sent[1].contains("survives a failed flush\n"))
        coroutineContext.cancelChildren()
    }

    @Test fun anEmptyBufferCostsNoExec() = runTest {
        var calls = 0
        val shipper = LogShipper("app-4", this, liveSession = { null }, execFlush = { _, _ -> calls++; true })
        shipper.start()
        // start() logs one line through FileLogger, but the sink is attached after it: nothing buffered
        advanceTimeBy(95_000); runCurrent()
        assertEquals(0, calls)
        assertFalse(FileLogger.remoteSink == null, "the shipper is attached to the logger")
        coroutineContext.cancelChildren()
    }
}
