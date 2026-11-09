package com.clauderemote.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mac v1.0.569 (2026-11-09): 62 tab read loops plus streamd and the notify watcher blocked all 64 Dispatchers.IO
 * threads and the remaining tabs' attach never ran. Long blocking reads live on [LongReads], which must not be
 * bounded by IO's 64 and must leave IO free for short work.
 */
class LongReadsTest {

    @Test fun moreBlockedReadsThanIoHasThreadsStillLeaveIoFree() = runBlocking {
        val readers = 100   // more than Dispatchers.IO's 64
        val release = CountDownLatch(1)
        val blocked = AtomicInteger()
        coroutineScope {
            val reads = (1..readers).map {
                async(LongReads) {
                    blocked.incrementAndGet()
                    release.await(30, TimeUnit.SECONDS)   // a read that does not return until the channel speaks
                }
            }
            try {
                // Every reader got its own thread: none waits for another to finish.
                withTimeout(10_000) { while (blocked.get() < readers) kotlinx.coroutines.delay(10) }
                // And the short work that would make those reads return (the attach step) still runs on IO.
                val short = withTimeout(5_000) { withContext(Dispatchers.IO) { 42 } }
                assertEquals(42, short)
            } finally {
                release.countDown()
            }
            assertTrue(reads.awaitAll().all { it }, "every reader was released")
        }
    }

    @Test fun theLongLivedReadersAreNotOnDispatchersIo() {
        val ssh = java.io.File("src/commonMain/kotlin/com/clauderemote/connection/SshManager.kt").readText()
        assertTrue("readJob = ioScope.launch(LongReads) {" in ssh, "the tab's shell read loop runs on LongReads")

        for (path in listOf(
            "src/commonMain/kotlin/com/clauderemote/session/service/TranscriptService.kt",
            "src/commonMain/kotlin/com/clauderemote/session/service/NotificationService.kt",
        )) {
            val src = java.io.File(path).readText()
            assertFalse(Regex("""withContext\(Dispatchers\.IO\)\s*\{\s*reader\.readLine\(\)""").containsMatchIn(src), "$path: readLine on IO")
            assertTrue(Regex("""withContext\(com\.clauderemote\.connection\.LongReads\)\s*\{\s*reader\.readLine\(\)""").containsMatchIn(src), "$path: readLine on LongReads")
        }

        for (path in listOf(
            "src/desktopMain/kotlin/com/clauderemote/connection/EtManager.kt",
            "src/desktopMain/kotlin/com/clauderemote/connection/MoshManager.kt",
            "src/androidMain/kotlin/com/clauderemote/connection/EtManager.kt",
            "src/androidMain/kotlin/com/clauderemote/connection/MoshManager.kt",
        )) {
            val src = java.io.File(path).readText()
            assertFalse("CoroutineScope(Dispatchers.IO + SupervisorJob())" in src, "$path: process read loop on IO")
            assertTrue("CoroutineScope(LongReads + SupervisorJob())" in src, "$path: process read loop on LongReads")
        }
    }
}
