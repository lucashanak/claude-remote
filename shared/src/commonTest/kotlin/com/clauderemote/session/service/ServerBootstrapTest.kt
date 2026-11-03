package com.clauderemote.session.service

import com.clauderemote.util.FileLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DefaultServerBootstrap] against a fake exec on virtual time. Every test builds its
 * subject through [newBootstrap] only, typed as the [ServerBootstrap] interface, so the
 * same file can be run against [NoopServerBootstrap] (the A0 behaviour) to show it fails.
 */
class ServerBootstrapTest {

    private class FakeExec(val handler: suspend (serverId: String, cmd: String) -> String) : RemoteExec {
        val calls = mutableListOf<Pair<String, String>>()
        val callTimes = mutableListOf<Long>()
        var now: () -> Long = { 0 }
        override suspend fun run(
            serverId: String, cmd: String, stdin: ByteArray?, totalMs: Long, kind: ExecKind, paceStdin: Pace,
        ): ExecResult {
            calls += serverId to cmd
            callTimes += now()
            return ExecResult(handler(serverId, cmd), 0)
        }
    }

    /**
     * Run everything that is due, including background-scope work: `advanceUntilIdle` stops
     * as soon as only background tasks are left, which is exactly where the bootstrap lives.
     */
    private fun TestScope.settle() { runCurrent(); advanceTimeBy(20_000); runCurrent() }

    private val logs = mutableListOf<String>()
    private var savedLog: ((String, String, String, Throwable?) -> Unit)? = null

    @BeforeTest fun captureLog() {
        savedLog = FileLogger.platformLog
        FileLogger.platformLog = { _, _, msg, _ -> logs += msg }
    }

    @AfterTest fun restoreLog() { FileLogger.platformLog = savedLog }

    private fun ok(
        flags: String = "",
        legacy: String = "HOOK_EXISTS",
        v2: String = "HOOKS_V2_STATE=ABSENT\nHOOKEV_OFF=0",
        streamd: String = "STREAMD_OK",
        end: Boolean = true,
    ) = "===CRB flags===\n$flags\n===CRB hooks===\n$legacy\n===CRB hooksv2===\n$v2\n===CRB streamd===\n$streamd\n" + if (end) "===CRB end===\n" else ""

    private class Subject(
        val bootstrap: ServerBootstrap,
        val installs: MutableSharedFlow<String>,
        val removes: MutableSharedFlow<String>,
        val control: HookInstallControl,
        val applied: MutableList<Pair<String, Set<String>>>,
    )

    private fun TestScope.newBootstrap(
        exec: FakeExec,
        fallback: (suspend (String, String, Long) -> String)? = null,
    ): Subject {
        exec.now = { testScheduler.currentTime }
        val installs = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val removes = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val control = HookInstallControl()
        val applied = mutableListOf<Pair<String, Set<String>>>()
        val b: ServerBootstrap = DefaultServerBootstrap(
            scope = backgroundScope,
            exec = exec,
            fallback = fallback,
            serverName = { "srv-$it" },
            applyServerOff = { id, off -> applied += id to off },
            installRequests = installs,
            removeRequests = removes,
            control = control,
            clock = { testScheduler.currentTime },
        )
        return Subject(b, installs, removes, control, applied)
    }

    @Test fun nTabsOnOneServerCostOneExecAndTheResultIsMemoized() = runTest {
        val exec = FakeExec { _, _ -> delay(1_000); ok() }
        val s = newBootstrap(exec)
        (1..20).map { async { s.bootstrap.ensure("a") } }.awaitAll()
        assertEquals(1, exec.calls.size, "20 concurrent ensures share one exec")
        s.bootstrap.ensure("a")
        assertEquals(1, exec.calls.size, "memoized")
        s.bootstrap.ensure("b")
        assertEquals(2, exec.calls.size, "another server is its own bootstrap")
        assertEquals("b", exec.calls.last().first)
    }

    @Test fun aCancelledInitiatorDoesNotCancelTheBootstrap() = runTest {
        val exec = FakeExec { _, _ -> delay(1_000); ok() }
        val s = newBootstrap(exec)
        val first = launch { s.bootstrap.ensure("a") }
        runCurrent()
        first.cancel()
        settle()
        assertEquals(1, exec.calls.size)
        s.bootstrap.ensure("a")
        assertEquals(1, exec.calls.size, "the run that outlived its initiator still memoized the result")
    }

    @Test fun reEnsuresAfterSixHoursNotBefore() = runTest {
        val exec = FakeExec { _, _ -> ok() }
        val s = newBootstrap(exec)
        s.bootstrap.ensure("a")
        advanceTimeBy(6L * 3600 * 1000 - 1)
        s.bootstrap.ensure("a")
        assertEquals(1, exec.calls.size)
        advanceTimeBy(1)
        s.bootstrap.ensure("a")
        assertEquals(2, exec.calls.size)
    }

    @Test fun invalidateForcesTheNextEnsureToRun() = runTest {
        val exec = FakeExec { _, _ -> ok() }
        val s = newBootstrap(exec)
        s.bootstrap.ensure("a")
        s.bootstrap.invalidate("a")
        s.bootstrap.ensure("a")
        assertEquals(2, exec.calls.size)
    }

    @Test fun failureBacksOffFrom10sDoublingTo5minAndSuccessResets() = runTest {
        var failing = true
        val exec = FakeExec { _, _ -> if (failing) throw ExecUnavailable(ExecUnavailableReason.NOT_UP) else ok() }
        val s = newBootstrap(exec)
        s.bootstrap.ensure("a")
        s.bootstrap.ensure("a")
        assertEquals(1, exec.calls.size, "inside the 10 s backoff nothing is tried")
        val expectedGaps = listOf(10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L)
        var at = 0L
        for ((i, gap) in expectedGaps.withIndex()) {
            advanceTimeBy(gap - 1)
            s.bootstrap.ensure("a")
            assertEquals(i + 1, exec.calls.size, "no try 1 ms before the backoff ends (gap $gap)")
            advanceTimeBy(1)
            s.bootstrap.ensure("a")
            at += gap
            assertEquals(i + 2, exec.calls.size, "a try at +$gap")
            assertEquals(at, exec.callTimes.last())
        }
        failing = false
        advanceTimeBy(300_000)
        s.bootstrap.ensure("a")
        val n = exec.calls.size
        s.bootstrap.ensure("a")
        assertEquals(n, exec.calls.size, "success is remembered")
        s.bootstrap.invalidate("a")
        failing = true
        s.bootstrap.ensure("a")
        advanceTimeBy(9_999)
        s.bootstrap.ensure("a")
        assertEquals(n + 1, exec.calls.size, "after a success the backoff starts again at 10 s")
    }

    @Test fun anIncompleteOutputIsAFailureAndLeavesFlagsAlone() = runTest {
        val exec = FakeExec { _, _ -> ok(flags = "{\"off\":[\"hooks\"]", end = false) }
        val s = newBootstrap(exec)
        s.bootstrap.ensure("a")
        assertTrue(s.applied.isEmpty(), "a truncated output must not read as a corrupt flags file: ${s.applied}")
        s.bootstrap.ensure("a")
        assertEquals(1, exec.calls.size, "and it is backed off like any failure")
    }

    @Test fun fallbackServesWhenExecIsNotUpButNotOnTimeout() = runTest {
        var reason = ExecUnavailableReason.NOT_UP
        val exec = FakeExec { _, _ -> throw ExecUnavailable(reason) }
        val viaFallback = mutableListOf<String>()
        val s = newBootstrap(exec) { id, _, _ -> viaFallback += id; ok() }
        s.bootstrap.ensure("a")
        assertEquals(listOf("a"), viaFallback)
        for (r in listOf(ExecUnavailableReason.GATE_TIMEOUT, ExecUnavailableReason.REFUSED, ExecUnavailableReason.IO)) {
            reason = r
            s.bootstrap.invalidate("b$r")
            s.bootstrap.ensure("b$r")
        }
        assertEquals(4, viaFallback.size)
        reason = ExecUnavailableReason.TIMEOUT
        s.bootstrap.ensure("c")
        assertEquals(4, viaFallback.size, "a command that timed out is not repeated on the other path")
    }

    @Test fun serverForcedOffFlagsAreAppliedAndAnAbsentFileLiftsThem() = runTest {
        var flags = "{\"off\":[\"lazy\",\"hooks\",\"bogus\"]}"
        val exec = FakeExec { _, _ -> ok(flags = flags) }
        val s = newBootstrap(exec)
        s.bootstrap.ensure("a")
        assertEquals(listOf("a" to setOf("lazy", "hooks", "bogus")), s.applied)
        flags = ""
        s.bootstrap.invalidate("a")
        s.bootstrap.ensure("a")
        assertEquals("a" to emptySet<String>(), s.applied.last())
    }

    @Test fun corruptFlagsFileSwitchesNothingOffAndIsLogged() = runTest {
        for (bad in listOf("{not json", "[1,2]", "{\"off\":\"hooks\"}", "{\"off\":{\"a\":1}}")) {
            logs.clear()
            val exec = FakeExec { _, _ -> ok(flags = bad) }
            val s = newBootstrap(exec)
            s.bootstrap.ensure("a")
            assertEquals(listOf("a" to emptySet<String>()), s.applied, "nothing off for $bad")
            assertTrue(logs.any { it.contains("CommFlags: client-flags.json unparseable") }, "logged for $bad: $logs")
        }
    }

    @Test fun refreshFlagsReadsOnlyTheFlagsAndNeverThrows() = runTest {
        var out: String = "===CRB flags===\n{\"off\":[\"pool\"]}\n===CRB end===\n"
        val exec = FakeExec { _, _ -> out }
        val s = newBootstrap(exec)
        s.bootstrap.refreshFlags("a")
        assertEquals(listOf("a" to setOf("pool")), s.applied)
        assertFalse(exec.calls.single().second.contains("CRHV2"), "refresh runs no install step")
        assertFalse(exec.calls.single().second.contains("claude-remote-streamd"), "and no streamd step")
        out = "garbage"
        s.bootstrap.refreshFlags("a")
        assertEquals(1, s.applied.size, "output that is not ours changes nothing")
        val failing = FakeExec { _, _ -> throw ExecUnavailable(ExecUnavailableReason.NOT_UP) }
        newBootstrap(failing).bootstrap.refreshFlags("a")
    }

    @Test fun hooksAreInstalledOnlyForAnExplicitRequestAndOnlyOnThatRun() = runTest {
        val exec = FakeExec { _, _ -> ok() }
        val s = newBootstrap(exec)
        runCurrent()
        s.bootstrap.ensure("a")
        assertFalse(exec.calls.last().second.contains("CRHV2_SH"), "no request: the bootstrap only READS the hooks state")
        assertTrue(exec.calls.last().second.contains(HookCommands.STATUS_HOOKS_V2_COMMAND))

        s.installs.emit("a")
        settle()
        assertEquals(2, exec.calls.size, "the request runs a bootstrap now, memo or not")
        assertTrue(exec.calls.last().second.contains(HookCommands.ENSURE_HOOKS_V2_COMMAND), "and it carries the install")

        s.bootstrap.invalidate("a")
        s.bootstrap.ensure("a")
        assertFalse(exec.calls.last().second.contains("CRHV2_SH"), "a request is one-shot")
    }

    @Test fun anInstallRequestIsDroppedAndLoggedWhenTheEffectiveFlagsHaveHooksOff() = runTest {
        for (off in listOf("hooks", "pool", "exec")) {
            CommFlags.resetForTest()
            CommFlags.applyServerOff("a", setOf(off))
            logs.clear()
            val exec = FakeExec { _, _ -> ok() }
            val s = newBootstrap(exec)
            runCurrent()
            s.installs.emit("a")
            settle()
            assertTrue(exec.calls.isEmpty(), "$off off on the server: no exec at all, the install never starts")
            assertTrue(logs.any { it.contains("Hooks v2 install request for srv-a dropped") }, "$off: ${logs}")
            val st = s.control.status.value.getValue("a")
            assertEquals(HookCommands.V2Result.SKIPPED_OFF, st.lastResult)
            assertFalse(st.busy)
            // the same server with nothing off installs
            CommFlags.resetForTest()
            s.installs.emit("a")
            settle()
            assertTrue(exec.calls.any { it.second.contains(HookCommands.ENSURE_HOOKS_V2_COMMAND) }, "$off lifted: it installs")
        }
        CommFlags.resetForTest()
    }

    @Test fun theInstallButtonExplainsWhyItIsBlocked() {
        CommFlags.resetForTest()
        assertEquals(null, HookInstallControl.installBlockedReason(CommFlags.effectiveFor("a")))
        CommFlags.applyServerOff("a", setOf("exec"))
        val why = HookInstallControl.installBlockedReason(CommFlags.effectiveFor("a"))
        assertTrue(why != null && why.isNotBlank(), "a reason the UI can show: $why")
        assertEquals(null, HookInstallControl.installBlockedReason(CommFlags.effectiveFor("other")), "per server")
        CommFlags.resetForTest()
        CommFlags.set(CommFlags.Raw(pool = true, exec = true, hooks = false))
        assertTrue(HookInstallControl.installBlockedReason(CommFlags.effectiveFor("a"))!!.contains("comm_hooks"), "the device's own switch")
        CommFlags.resetForTest()
    }

    @Test fun aRequestForOneServerDoesNotInstallOnAnother() = runTest {
        val exec = FakeExec { _, _ -> ok() }
        val s = newBootstrap(exec)
        runCurrent()
        s.installs.emit("a")
        settle()
        s.bootstrap.ensure("b")
        val forB = exec.calls.last { it.first == "b" }.second
        assertFalse(forB.contains("CRHV2_SH"))
    }

    @Test fun aRequestDuringARunningBootstrapStillGetsItsInstall() = runTest {
        val exec = FakeExec { _, _ -> delay(5_000); ok() }
        val s = newBootstrap(exec)
        runCurrent()
        val running = launch { s.bootstrap.ensure("a") }
        runCurrent()
        s.installs.emit("a")
        settle()
        running.join()
        assertTrue(exec.calls.any { it.second.contains(HookCommands.ENSURE_HOOKS_V2_COMMAND) }, "the install ran in a second pass")
    }

    @Test fun theStatusIsPublishedForTheSettingsScreen() = runTest {
        val exec = FakeExec { _, _ -> ok(v2 = "HOOKS_V2_STATE=INSTALLED\nHOOKEV_OFF=1") }
        val s = newBootstrap(exec)
        s.bootstrap.ensure("a")
        val st = s.control.status.value.getValue("a")
        assertEquals(HookCommands.V2State.INSTALLED, st.state)
        assertTrue(st.offSwitch)
    }

    @Test fun removalIsAnExplicitRequestAndRunsOnlyTheRemoveScript() = runTest {
        val exec = FakeExec { _, _ -> "HOOKS_V2_REMOVE_FIXED\nACCOUNTS_MERGED=0\n" }
        val s = newBootstrap(exec)
        runCurrent()
        s.bootstrap.ensure("a") // normal bootstrap output is not needed here
        exec.calls.clear()
        s.removes.emit("a")
        settle()
        assertEquals(1, exec.calls.size)
        assertEquals(HookCommands.REMOVE_HOOKS_V2_COMMAND, exec.calls.single().second)
        assertEquals(HookCommands.V2State.ABSENT, s.control.status.value.getValue("a").state)
    }

    @Test fun theLogLineCarriesTheFlagsHooksAndStreamdResult() = runTest {
        val exec = FakeExec { _, _ -> ok(flags = "{\"off\":[\"lazy\"]}", legacy = "HOOK_FIXED", streamd = "STREAMD_INSTALLED") }
        val s = newBootstrap(exec)
        s.bootstrap.ensure("a")
        val line = logs.single { it.startsWith("Server bootstrap srv-a:") }
        assertTrue(line.contains("flags=lazy") && line.contains("hooks=HOOK_FIXED") && line.contains("streamd=STREAMD_INSTALLED"), line)
        assertTrue(Regex("""\(\d+ms\)$""").containsMatchIn(line), line)
    }

    @Test fun requestsWithoutAListenerAreDroppedAndReportFalse() {
        val c = HookInstallControl()
        assertFalse(c.requestRemove("a"), "nobody listens: the request is gone, not queued")
        CommFlags.resetForTest()
        assertFalse(CommFlags.requestHooksInstall("a"), "install requests too")
    }
}
