package com.clauderemote.session

import com.clauderemote.connection.ChannelOpen
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.ExecKind
import com.clauderemote.session.service.ExecUnavailable
import com.clauderemote.session.service.ExecUnavailableReason
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.FakeSession
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * N-EK1 (dávka E, round 2): a counted slot of a tab transport comes back only once sshd freed its channel (H1). A
 * one-off a user waits for (`execRun`, `execRead`, `useChannel` falling back to a tab transport) that finds no room ONLY
 * because such a slot still settles waits for it; a poll (`ExecKind.POLL`) skips its turn; and with nothing settling
 * "no room" comes at once, as on 1bdcbd5. The situation: the kill switch with 8 shells packed on one transport (one
 * short slot, the one of the reserve).
 */
class FallbackSettleWaitTest {
    private val tabs = TabManager()
    private val storage = ServerStorage(FakeKeyValueStore())
    private val server = SshServer(id = "srv-1", name = "box", host = "127.0.0.1", port = 1, username = "u", password = "p")
    private var orchestrator: SessionOrchestrator? = null
    private lateinit var fake: FakeSession

    @BeforeTest fun setUp() {
        CommFlags.resetForTest()
        ChannelOpen.resetForTest()
        storage.addServer(server)
    }

    @AfterTest fun tearDown() {
        orchestrator?.shutdownForTest()
        CommFlags.resetForTest()
        ChannelOpen.resetForTest()
    }

    /** An exec whose output ended; sshd closes it on [serverClose]. */
    private class Ended(private val sess: Session) : ChannelExec() {
        @Volatile private var open = true
        override fun getSession(): Session = sess
        override fun isConnected(): Boolean = open
        override fun isClosed(): Boolean = !open
        override fun isEOF(): Boolean = true
        fun serverClose() { open = false }
        override fun disconnect() { open = false }
    }

    /**
     * A one-off's own exec: it answers "ok" at once and sshd closes it with the answer (exit 0, never connected as far as
     * the linger rule sees: it lingers not at all).
     */
    private class Answers : ChannelExec() {
        override fun connect(connectTimeout: Int) {}
        override fun setCommand(command: String) {}
        override fun setInputStream(`in`: java.io.InputStream?) {}
        override fun getInputStream(): java.io.InputStream = "ok\n".byteInputStream()
        override fun getExitStatus(): Int = 0
        override fun isConnected(): Boolean = false
        override fun disconnect() {}
    }

    /** [exec] = false: the kill switch; true: exec routing on but no exec transport (NOT_UP), the gate path's fallback. */
    private fun setup(exec: Boolean = false): SessionOrchestrator {
        CommFlags.set(CommFlags.Raw(exec = exec))
        val o = SessionOrchestrator(storage, tabs, null).also { orchestrator = it }
        if (exec) assertTrue(o.connectionRegistry.execActive(server.id), "fixture: exec routing is on (the gate path)")
        val pool = o.connectionRegistry.transportPool(server.id)
        fake = FakeSession { _ -> Answers() }
        BudgetFixture.injectTransport(pool, fake, 8)
        tabs.addTab(ClaudeSession(id = "t1", server = server, folder = "/work", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
            tmuxSessionName = "claude-server-a", connectionType = ConnectionType.SSH))
        o.connectionRegistry.putSsh("t1", BudgetFixture.connectedManager(storage, fake, pool))
        repeat(7) { o.connectionRegistry.putSsh("peer-$it", BudgetFixture.connectedManager(storage, fake, pool)) }
        return o
    }

    /** The one short slot was taken by a poll that just read its output to EOF and gave the slot back: it settles. */
    private fun settlingSlot(o: SessionOrchestrator): Ended = runBlocking {
        val pool = o.connectionRegistry.transportPool(server.id)
        assertTrue(pool.reserveFallbackChannel(fake, short = true), "fixture: the poll takes the one slot")
        val ch = Ended(fake)
        ChannelOpen.disconnect(ch)
        pool.releaseChannel(fake)
        assertTrue(!pool.fallbackHeadroom(fake, short = true), "fixture: the slot still settles")
        ch
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    @Test fun withNothingSettlingNoRoomComesAtOnce() = runBlocking {
        val o = setup()
        val pool = o.connectionRegistry.transportPool(server.id)
        assertTrue(pool.reserveFallbackChannel(fake, short = true), "another one-off holds the slot (it does not settle)")
        var t0 = System.nanoTime()
        val e = assertFailsWith<ExecUnavailable> { o.connectionRegistry.useChannel(server.id, ExecKind.SHORT) { "ran" } }
        assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
        assertTrue(ms(t0) < 100, "no room at once: ${ms(t0)}ms")
        t0 = System.nanoTime()
        assertNull(o.connectionRegistry.execRead(server.id, "true"))
        assertTrue(ms(t0) < 100, "no room at once: ${ms(t0)}ms")
    }

    @Test fun aOneOffWaitsForASlotThatStillSettlesAndRunsInIt() = runBlocking {
        val o = setup()
        val ch = settlingSlot(o)
        val run = async(Dispatchers.IO) { o.connectionRegistry.useChannel(server.id, ExecKind.SHORT) { "ran" } }
        delay(400)
        assertTrue(!run.isCompleted, "it waits while sshd still holds the session")
        ch.serverClose()
        assertEquals("ran", withTimeout(3_000) { run.await() }, "and runs once the slot came back")
    }

    @Test fun aPollSkipsItsTurnInsteadOfWaitingForASettlingSlot() = runBlocking {
        val o = setup()
        val ch = settlingSlot(o)
        val t0 = System.nanoTime()
        assertNull(o.connectionRegistry.execRead(server.id, "true", kind = ExecKind.POLL), "no room: the poll skips")
        assertTrue(ms(t0) < 100, "at once: ${ms(t0)}ms")
        ch.serverClose()
    }
    // ---- kolo 3 (ověření E-K-2) ----------------------------------------------------------------------------------------

    /** N2-2: a periodic caller (health ping, usage, git status) skips its turn instead of waiting for a settling slot. */
    @Test fun aPeriodicReadSkipsItsTurnInsteadOfWaiting() = runBlocking {
        val o = setup()
        val ch = settlingSlot(o)
        val t0 = System.nanoTime()
        assertNull(o.connectionRegistry.execRead(server.id, "true", totalMs = 10_000, periodic = true))
        assertTrue(ms(t0) < 100, "at once: ${ms(t0)}ms")
        assertNull(o.connectionRegistry.execRun(server.id, "true", totalMs = 10_000, periodic = true))
        assertTrue(ms(t0) < 200, "at once: ${ms(t0)}ms")
        ch.serverClose()
    }

    /**
     * R3 + N2-3: a slot that never settles (sshd never closes, the wait for its CLOSE is longer than the call) ends the
     * wait within the caller's own budget: the acquire timeout of a channel, what is left of a read's `totalMs` after the
     * floor of the session's route (LAN here).
     */
    @Test fun aSlotThatNeverSettlesEndsTheWaitWithinTheCallersBudget() = runBlocking {
        ChannelOpen.tuning = ChannelOpen.Tuning(closeWaitMs = 60_000)
        val o = setup()
        settlingSlot(o)
        withTimeout(10_000) {
            var t0 = System.nanoTime()
            val e = assertFailsWith<ExecUnavailable> { o.connectionRegistry.useChannel(server.id, ExecKind.SHORT, acquireTimeoutMs = 400) { "ran" } }
            assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
            assertTrue(ms(t0) in 350..1_500, "the acquire timeout of 400 ms, not the 61 s bound: ${ms(t0)}ms")
            t0 = System.nanoTime()
            assertNull(o.connectionRegistry.execRead(server.id, "true", totalMs = 2_000))
            // N3-1 (round 4): this transport is LAN, so the read keeps LAN's 0.3 s floor for itself (round 3 took off CF's 1.5 s)
            assertTrue(ms(t0) in 1_600..2_500, "2 s minus the LAN floor of 0.3 s: ${ms(t0)}ms")
        }
    }

    /** R7: once the slot settled nothing is counted as settling any more, and a fallback without room fails at once. */
    @Test fun afterTheSlotSettledNothingSettlesAndNoRoomIsImmediateAgain() = runBlocking {
        val o = setup()
        val pool = o.connectionRegistry.transportPool(server.id)
        val ch = settlingSlot(o)
        assertEquals(1, pool.settlingOf(fake))
        ch.serverClose()
        withTimeout(3_000) { while (!pool.fallbackHeadroom(fake, short = true)) delay(10) }
        assertEquals(0, pool.settlingOf(fake), "the settling count came back to 0")
        assertTrue(pool.reserveFallbackChannel(fake, short = true), "a one-off holds the slot now (not settling)")
        val t0 = System.nanoTime()
        assertFailsWith<ExecUnavailable> { o.connectionRegistry.useChannel(server.id, ExecKind.SHORT) { "ran" } }
        assertTrue(ms(t0) < 100, "no room at once: ${ms(t0)}ms")
    }

    /**
     * N2-1: one-offs that wait for a settling slot take it under the lock that saw it free, and wait again after a wake-up
     * that brought nothing — a poll hammering the same slot cannot starve them: every one runs within its budget.
     */
    @Test fun waitingOneOffsTakeTheSlotAheadOfAPollAndAllRunWithinTheirBudget() = runBlocking {
        val o = setup()
        val pool = o.connectionRegistry.transportPool(server.id)
        val ch = settlingSlot(o)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val polls = java.util.concurrent.atomic.AtomicInteger()
        val poller = async(Dispatchers.IO) {
            // a poll that grabs the slot whenever it sees it free and gives it back at once (an uncounted-reap poll)
            while (!stop.get()) {
                if (pool.reserveFallbackChannel(fake, short = true)) { polls.incrementAndGet(); delay(5); pool.releaseChannel(fake) }
                delay(1)
            }
        }
        val runs = (1..4).map { i ->
            async(Dispatchers.IO) {
                runCatching { o.connectionRegistry.useChannel(server.id, ExecKind.SHORT, acquireTimeoutMs = 5_000) { delay(30); "ran$i" } }
            }
        }
        delay(200)
        ch.serverClose()
        val results = withTimeout(10_000) { runs.map { it.await() } }
        stop.set(true); poller.await()
        assertEquals((1..4).map { "ran$it" }, results.map { it.getOrNull() }, "every one-off ran: ${results.map { it.exceptionOrNull()?.message }} (polls ${polls.get()})")
    }
    /**
     * N2-2: the callers that run in an interval (their turn may be skipped) say so, so their fallback never waits for a
     * settling slot; the one-offs keep the default. A wiring gate over the sources (the pollers run only with a live app).
     */
    @Test fun thePeriodicCallersSayThatTheyArePeriodic() {
        fun src(name: String) = java.io.File("src/commonMain/kotlin/com/clauderemote/session/service/$name").readText()
        assertTrue("execRead(serverId, \"echo pong\", totalMs = 10_000, periodic = true)" in src("ServerHealthService.kt"), "the health ping")
        assertTrue("execRead(serverId, cmd, totalMs = 20_000, periodic = true)" in src("GitStatusService.kt"), "the git status probe")
        val usage = src("UsageService.kt")
        assertTrue(Regex("""totalMs = 60_000,\s*kind = ExecKind.SHORT,\s*periodic = true,""").containsMatchIn(usage), "the ccusage poll")
        assertTrue("execRead(serverId, cmd, totalMs = 10_000, periodic = true)" in usage, "the rate-limit poll")
        assertTrue("execRead(serverId, rateLimitCmd(serverConfigDir(accountSlug)), totalMs = 10_000)\n" in usage,
            "the usage page's one-shot refresh (a user waits for it) keeps the default")
    }
    // ---- kolo 4 ---------------------------------------------------------------------------------------------------------

    /** N3-1 (D18): on a LAN transport a one-off with 1.5 s waits for the settling slot (the LAN floor is 0.3 s) and runs. */
    @Test fun aLanOneOffWithATightBudgetWaitsForTheSettlingSlotAndRuns() = runBlocking {
        val o = setup()
        val ch = settlingSlot(o)
        val read = async(Dispatchers.IO) { o.connectionRegistry.execRead(server.id, "echo ok", totalMs = 1_500) }
        delay(300)
        assertTrue(!read.isCompleted, "it waits (on 1bdcbd5+E-K-3 the CF floor left 0 ms of the 1.5 s to wait)")
        ch.serverClose()
        assertEquals("ok", withTimeout(3_000) { read.await() }?.trim(), "and runs in the slot once it came back")
    }

    /** N3-1: on a Cloudflare transport the same one-off keeps the 1.5 s floor for the command, so it does not wait. */
    @Test fun aCloudflareOneOffWithTheSameBudgetDoesNotWait() = runBlocking {
        val o = setup()
        ChannelOpen.markCloudflare(fake)
        val ch = settlingSlot(o)
        val t0 = System.nanoTime()
        assertNull(o.connectionRegistry.execRead(server.id, "echo ok", totalMs = 1_500), "no room: nothing left to wait after the CF floor")
        assertTrue(ms(t0) < 100, "at once: ${ms(t0)}ms")
        ch.serverClose()
    }

    /**
     * N-EK11: exec routing on, the exec transport not up (NOT_UP), the fallback to a tab transport whose slot settles: a
     * periodic `execRun`/`execRead` skips its turn, a one-off waits and runs.
     */
    @Test fun onTheExecNotUpPathAPeriodicCallerSkipsAndAOneOffWaits() = runBlocking {
        val o = setup(exec = true)
        val ch = settlingSlot(o)
        val oneOff = async(Dispatchers.IO) { o.connectionRegistry.execRead(server.id, "echo ok", totalMs = 10_000) }
        var t0 = System.nanoTime()
        assertNull(o.connectionRegistry.execRead(server.id, "echo ok", totalMs = 10_000, periodic = true), "a periodic read skips")
        val readMs = ms(t0)
        t0 = System.nanoTime()
        assertNull(o.connectionRegistry.execRun(server.id, "echo ok", totalMs = 10_000, periodic = true), "a periodic run skips")
        val runMs = ms(t0)
        assertTrue(readMs < 1_000 && runMs < 1_000, "neither waited for the slot: read ${readMs}ms, run ${runMs}ms")
        assertTrue(!oneOff.isCompleted, "while the one-off still waits for it")
        ch.serverClose()
        assertEquals("ok", withTimeout(3_000) { oneOff.await() }?.trim())
    }

    /** N-EK12: on the same path `useChannel` waits for the settling slot at most its acquire timeout. */
    @Test fun onTheExecNotUpPathUseChannelWaitsAtMostItsAcquireTimeout() = runBlocking {
        ChannelOpen.tuning = ChannelOpen.Tuning(closeWaitMs = 60_000)
        val o = setup(exec = true)
        settlingSlot(o)
        withTimeout(10_000) {
            val t0 = System.nanoTime()
            val e = assertFailsWith<ExecUnavailable> { o.connectionRegistry.useChannel(server.id, ExecKind.SHORT, acquireTimeoutMs = 400) { "ran" } }
            assertEquals(ExecUnavailableReason.NOT_UP, e.reason)
            assertTrue(ms(t0) in 350..1_500, "its 400 ms, not the 61 s bound: ${ms(t0)}ms")
        }
    }
}
