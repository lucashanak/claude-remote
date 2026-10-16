package com.clauderemote.session.transcript

import com.clauderemote.model.SshServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression cover for the two [TranscriptStream] paths that can silently lose
 * transcript bytes:
 *
 *  - a stream-daemon batch that begins PAST what the client has read. Before
 *    the `from` offset existed, [TranscriptStream.pushLines] fast-forwarded its
 *    byte offset to the batch's END, so the bytes in between were never read by
 *    anybody — the safety poll resumed above them. Measured as a real delta
 *    loss at watch registration, where streamd snapshots EOF on its own tick,
 *    later than the `wc -c` the client's backlog poll sampled.
 *  - the backlog cap: only the last 2000 lines ever load, and nothing told the
 *    user the conversation they are reading starts in the middle.
 *
 * The cadence/offset decisions are also covered directly as pure functions on
 * [StreamOffsetPolicy], because the surrounding loop needs a live SSH session.
 */
class TranscriptStreamOffsetTest {

    private val server = SshServer(id = "srv", name = "srv", host = "h", username = "u")

    /**
     * The stream gets a scope of its OWN, not the test's: its internal
     * SupervisorJob never completes, so parenting it under runTest's scope
     * makes every test hang out to UncompletedCoroutinesError.
     */
    private fun withStream(body: suspend (TranscriptStream) -> Unit) = runTest {
        val job = Job()
        try {
            body(TranscriptStream(server, "/tmp/x", CoroutineScope(job)))
        } finally {
            job.cancel()
        }
    }

    private fun userLine(id: String, text: String) =
        """{"type":"user","uuid":"$id","timestamp":"2026-06-07T10:00:00.000Z","message":{"content":"$text"}}"""

    // ---- pushLines offset arithmetic ----

    /**
     * THE bug: a batch starting beyond our offset means bytes [byteOffset,
     * from) exist only in the file. The batch must be dropped AND the offset
     * left alone, so the next `tail -c +offset` re-reads the hole. Asserting
     * the offset is what makes this a regression test — appending the lines
     * while skipping ahead would look fine here and lose the gap forever.
     */
    @Test
    fun pushLines_dropsBatchThatStartsPastOurOffset_andKeepsOffset() = withStream { s ->
        s.primeForTest("u-1", offset = 100L)
        s.pushLines("u-1", listOf(userLine("a", "hi")), newOffset = 400L, fromOffset = 250L)
        assertTrue(s.entries.value.isEmpty(), "gap batch must not be appended")
        assertEquals(100L, s.offsetFor("u-1"), "offset must stay behind the gap so the poll backfills it")
    }

    /** A batch that starts exactly where we are is contiguous — apply it. */
    @Test
    fun pushLines_appliesContiguousBatch_andAdvancesOffset() = withStream { s ->
        s.primeForTest("u-1", offset = 100L)
        s.pushLines("u-1", listOf(userLine("a", "hi")), newOffset = 160L, fromOffset = 100L)
        assertEquals(1, s.entries.value.size)
        assertEquals(160L, s.offsetFor("u-1"))
    }

    /**
     * An OVERLAPPING batch (starts before our offset) is applied too: the id
     * dedup absorbs the repeat, and refusing it would strand the new tail of
     * the same chunk.
     */
    @Test
    fun pushLines_appliesOverlappingBatch() = withStream { s ->
        s.primeForTest("u-1", offset = 100L)
        s.pushLines("u-1", listOf(userLine("a", "hi")), newOffset = 200L, fromOffset = 40L)
        assertEquals(1, s.entries.value.size)
        assertEquals(200L, s.offsetFor("u-1"))
    }

    /**
     * Version skew: a streamd still running from a pre-v4 install reports no
     * `from` at all. Null must keep the old lenient behaviour rather than
     * dropping every push — a degraded guarantee, not a dead stream.
     */
    @Test
    fun pushLines_withoutFromOffset_keepsLegacyFastForward() = withStream { s ->
        s.primeForTest("u-1", offset = 100L)
        s.pushLines("u-1", listOf(userLine("a", "hi")), newOffset = 400L, fromOffset = null)
        assertEquals(1, s.entries.value.size)
        assertEquals(400L, s.offsetFor("u-1"))
    }

    /**
     * Rotation guard: during /clear or /resume currentUuid is already the NEW
     * file while the offset still counts the OLD one. A push for either must be
     * refused — applying it would append the old conversation's lines or poison
     * the new file's offset.
     */
    @Test
    fun pushLines_dropsBatchForStaleUuid() = withStream { s ->
        s.primeForTest("u-1", offset = 100L)
        s.pushLines("u-2", listOf(userLine("a", "hi")), newOffset = 160L, fromOffset = 100L)
        assertTrue(s.entries.value.isEmpty())
        assertEquals(100L, s.offsetFor("u-1"))
    }

    /**
     * Offset-0 guard: the backlog poll hasn't run yet, so applying a push would
     * jump the offset past the initial `tail -n 2000` read and leave a gap no
     * poll can reach.
     */
    @Test
    fun pushLines_dropsBatchBeforeBacklogLoaded() = withStream { s ->
        s.primeForTest("u-1", offset = 0L)
        s.pushLines("u-1", listOf(userLine("a", "hi")), newOffset = 160L, fromOffset = 0L)
        assertTrue(s.entries.value.isEmpty())
        assertEquals(0L, s.offsetFor("u-1"))
    }

    // ---- StreamOffsetPolicy (pure) ----

    @Test
    fun dropsGapBatch_onlyWhenStartIsAheadOfConsumedBytes() {
        assertTrue(StreamOffsetPolicy.dropsGapBatch(byteOffset = 100L, from = 101L))
        assertFalse(StreamOffsetPolicy.dropsGapBatch(byteOffset = 100L, from = 100L))
        assertFalse(StreamOffsetPolicy.dropsGapBatch(byteOffset = 100L, from = 1L))
        // Unknown start (pre-v4 daemon) is never treated as a gap.
        assertFalse(StreamOffsetPolicy.dropsGapBatch(byteOffset = 100L, from = null))
    }

    /**
     * An IDLE session gets no pushes because nothing is being written, so
     * "no push recently" alone must never cost it the daemon cadence. Only a
     * poll that FOUND bytes the daemon should have delivered counts.
     */
    @Test
    fun daemonMisses_ignoreIdleSessions() {
        assertEquals(
            0,
            StreamOffsetPolicy.nextDaemonMisses(
                prev = 1, daemonLive = true, pollFoundNewLines = false, msSinceLastPush = 600_000L,
            ),
        )
    }

    @Test
    fun daemonMisses_accumulateWhenPollFindsBytesTheDaemonNeverPushed() {
        val first = StreamOffsetPolicy.nextDaemonMisses(
            prev = 0, daemonLive = true, pollFoundNewLines = true, msSinceLastPush = 60_000L,
        )
        assertEquals(1, first)
        assertTrue(StreamOffsetPolicy.daemonTrusted(first), "one miss is not yet proof")
        val second = StreamOffsetPolicy.nextDaemonMisses(
            prev = first, daemonLive = true, pollFoundNewLines = true, msSinceLastPush = 60_000L,
        )
        assertEquals(StreamOffsetPolicy.DAEMON_MISS_LIMIT, second)
        assertFalse(StreamOffsetPolicy.daemonTrusted(second), "two in a row ⇒ back to the fast poll")
    }

    /** A push a moment ago explains the lag — streamd ticks once a second. */
    @Test
    fun daemonMisses_resetWhenAPushJustArrived() {
        assertEquals(
            0,
            StreamOffsetPolicy.nextDaemonMisses(
                prev = 1, daemonLive = true, pollFoundNewLines = true, msSinceLastPush = 500L,
            ),
        )
    }

    /** With no daemon at all the counter is meaningless and must stay clear. */
    @Test
    fun daemonMisses_resetWhenDaemonIsNotLive() {
        assertEquals(
            0,
            StreamOffsetPolicy.nextDaemonMisses(
                prev = 1, daemonLive = false, pollFoundNewLines = true, msSinceLastPush = 600_000L,
            ),
        )
    }

    // ---- history truncation ----

    /** A conversation that fits is NOT truncated — the marker must not lie. */
    @Test
    fun historyTruncated_staysFalseForAShortTranscript() = withStream { s ->
        s.primeForTest("u-1", offset = 10L)
        s.pushLines("u-1", listOf(userLine("a", "hi"), userLine("b", "ho")), newOffset = 20L, fromOffset = 10L)
        assertFalse(s.historyTruncated.value)
    }

    /**
     * Past MAX_ENTRIES the head is dropped, so the view no longer starts where
     * the conversation does and the flag has to say so.
     */
    @Test
    fun historyTruncated_setWhenTheEntryListIsTrimmed() = withStream { s ->
        s.primeForTest("u-1", offset = 10L)
        // MAX_ENTRIES is 5000; push past it in two batches so the trim happens
        // on an append rather than on the very first batch.
        val batch = { base: Int -> (0 until 3000).map { userLine("x${base + it}", "m") } }
        s.pushLines("u-1", batch(0), newOffset = 1_000L, fromOffset = 10L)
        assertFalse(s.historyTruncated.value, "3000 entries fit under the cap")
        s.pushLines("u-1", batch(3000), newOffset = 2_000L, fromOffset = 1_000L)
        assertTrue(s.historyTruncated.value, "6000 entries must have dropped the head")
        assertEquals(5000, s.entries.value.size)
    }

    // ---- a queued prompt and its `user` twin, split across batches ----

    private fun queuedLine(id: String, text: String, ts: String) =
        """{"type":"attachment","uuid":"$id","timestamp":"$ts","attachment":""" +
            """{"type":"queued_command","origin":{"kind":"human"},"prompt":"$text"}}"""

    private fun userLineAt(id: String, text: String, ts: String) =
        """{"type":"user","uuid":"$id","timestamp":"$ts","message":{"content":"$text"}}"""

    /**
     * The live path's half of the queued-prompt fix: the attachment arrives in
     * one push and the `user` record Claude Code writes when it picks the
     * prompt up arrives in another, with a different id. Deduping inside one
     * parse cannot see across that boundary, so the stream has to.
     */
    @Test
    fun aQueuedPromptsLaterUserRecordIsNotAppendedTwice() = withStream { s ->
        s.primeForTest("u-1", offset = 10L)
        s.pushLines("u-1", listOf(queuedLine("a-1", "status?", "2026-06-07T10:00:00.000Z")),
            newOffset = 100L, fromOffset = 10L)
        s.pushLines("u-1", listOf(userLineAt("u-2", "status?", "2026-06-07T10:00:35.000Z")),
            newOffset = 200L, fromOffset = 100L)
        assertEquals(1, s.entries.value.count { it is TranscriptEntry.UserPrompt })
    }

    /** The same text typed again much later is a second prompt, not a duplicate. */
    @Test
    fun theSamePromptTypedHoursLaterIsKept() = withStream { s ->
        s.primeForTest("u-1", offset = 10L)
        s.pushLines("u-1", listOf(queuedLine("a-1", "autopilot", "2026-06-07T10:00:00.000Z")),
            newOffset = 100L, fromOffset = 10L)
        s.pushLines("u-1", listOf(userLineAt("u-2", "autopilot", "2026-06-08T00:36:00.000Z")),
            newOffset = 200L, fromOffset = 100L)
        assertEquals(2, s.entries.value.count { it is TranscriptEntry.UserPrompt })
    }

    /**
     * The one ordering the first version of this guard missed, found on real
     * data: the `user` record arrives in the EARLIER batch and the queued twin
     * 193 s later, so there was nothing recorded to match it against and both
     * rendered. Mirrors the parser, which drops the queued one in this order.
     */
    @Test
    fun aQueuedPromptArrivingAfterItsUserTwinIsNotAppendedTwice() = withStream { s ->
        s.primeForTest("u-1", offset = 10L)
        s.pushLines("u-1", listOf(userLineAt("u-2", "nasadit sentry", "2026-06-07T10:00:00.000Z")),
            newOffset = 100L, fromOffset = 10L)
        s.pushLines("u-1", listOf(queuedLine("a-1", "nasadit sentry", "2026-06-07T10:03:13.000Z")),
            newOffset = 200L, fromOffset = 100L)
        assertEquals(1, s.entries.value.count { it is TranscriptEntry.UserPrompt })
    }

    /** Far enough apart, the queued record is a second prompt and stays. */
    @Test
    fun aQueuedPromptLongAfterItsUserTwinIsKept() = withStream { s ->
        s.primeForTest("u-1", offset = 10L)
        s.pushLines("u-1", listOf(userLineAt("u-2", "pokracuj", "2026-06-07T10:00:00.000Z")),
            newOffset = 100L, fromOffset = 10L)
        s.pushLines("u-1", listOf(queuedLine("a-1", "pokracuj", "2026-06-07T12:00:00.000Z")),
            newOffset = 200L, fromOffset = 100L)
        assertEquals(2, s.entries.value.count { it is TranscriptEntry.UserPrompt })
    }
}
