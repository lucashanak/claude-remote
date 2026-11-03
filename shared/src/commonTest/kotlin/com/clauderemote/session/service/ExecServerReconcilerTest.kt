package com.clauderemote.session.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real reconciler against canned `reconcileAll` output and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExecServerReconcilerTest {
    private val uuidA = "11111111-1111-1111-1111-111111111111"
    private val uuidB = "22222222-2222-2222-2222-222222222222"

    /** Output of [ManifestCommands.reconcileAll]; a null section is left out (cut off). */
    private fun out(
        tomb: List<String> = emptyList(),
        manifest: String = "[]",
        tmux: List<String>? = emptyList(),
        panes: List<String>? = emptyList(),
        ps: List<String>? = emptyList(),
        claude: List<String>? = emptyList(),
    ): String = buildString {
        append((tomb + "---" + manifest).joinToString("\n")).append("\n__END_snap__ rc=0\n")
        if (tmux != null) append(tmux.joinToString("\n")).append("\n__END_tmux__ rc=0\n")
        if (panes != null) append(panes.joinToString("\n")).append("\n__END_panes__ rc=0\n")
        if (ps != null) append(ps.joinToString("\n")).append("\n__END_ps__ rc=0\n")
        if (claude != null) append(claude.joinToString("\n")).append("\n__END_claude__ rc=0\n")
    }

    private class Host(val tabs: Map<String, String>, var background: Boolean = false) {
        val forgotten = mutableListOf<String>()
        val adopted = mutableListOf<Triple<String, String, String>>()
        val uuids = HashMap<String, String?>()
        val commands = mutableListOf<String>()
        var output: String? = ""
        /** What the teardown behind a forget does (runs before the id is recorded in [forgotten]). */
        var onForget: suspend (String) -> Unit = {}
        /** What the exec itself does before it answers (a slow link). */
        var onRun: suspend () -> Unit = {}
    }

    private fun TestScopeHelper.reconciler(host: Host, periodMs: Long = 15_000) = ExecServerReconciler(
        scope = scope,
        run = { _, cmd, _ -> host.commands += cmd; host.onRun(); host.output },
        serverIdOf = { if (it in host.tabs) "srv" else null },
        tmuxNameOf = { host.tabs[it] },
        tabUuidOf = { host.uuids[it] },
        adoptUuid = { sid, uuid, source -> host.adopted += Triple(sid, uuid, source) },
        forget = { host.onForget(it); host.forgotten += it },
        isBackground = { host.background },
        periodMs = periodMs,
        clock = clock,
    )

    private class TestScopeHelper(val scope: kotlinx.coroutines.CoroutineScope, val clock: () -> Long)

    private val closedOutput get() = out(
        tomb = listOf("claude-server-a"),
        tmux = listOf("claude-server-b", "__anchor__"),
    )

    @Test fun theLoopRunsOneExecPerServerPerRoundAndForgetsAClosedTab() = runTest {
        val host = Host(mapOf("a" to "claude-server-a", "b" to "claude-server-b"))
        host.output = closedOutput
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        rec.register("a"); rec.register("b")
        advanceTimeBy(1_001)
        assertEquals(listOf("a"), host.forgotten) // tombstoned, absent, and claude-server-b is a live peer
        assertEquals(1, host.commands.size)        // two tabs, ONE exec
        advanceTimeBy(15_000 * 4)
        assertEquals(5, host.commands.size, "one exec per 15 s round, not per tab")
        assertEquals(listOf("a"), host.forgotten, "a forgotten tab is not forgotten again by the following rounds")
    }

    @Test fun neverForgetsWithoutALivePeerOrWhenTheSessionIsStillThere() = runTest {
        val host = Host(mapOf("a" to "claude-server-a"))
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        rec.register("a")
        host.output = out(tomb = listOf("claude-server-a"), tmux = listOf("__anchor__", "scratch")) // no other claude-server-*
        advanceTimeBy(1_001)
        host.output = out(tomb = listOf("claude-server-a"), tmux = listOf("claude-server-a", "claude-server-b")) // still alive
        advanceTimeBy(15_000)
        assertTrue(host.forgotten.isEmpty(), "forgot ${host.forgotten}")
    }

    @Test fun unknownNeverForgets_truncatedOrFailedSectionsAndNoTransport() = runTest {
        val host = Host(mapOf("a" to "claude-server-a"))
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        rec.register("a")
        // output cut off inside the tmux listing: the tombstones are known, the live set is NOT
        host.output = closedOutput.substringBefore("__END_tmux__")
        advanceTimeBy(1_001)
        // the tmux section failed (no server): rc != 0
        host.output = closedOutput.replace("__END_tmux__ rc=0", "__END_tmux__ rc=1")
        advanceTimeBy(15_000)
        // no usable transport at all
        host.output = null
        advanceTimeBy(15_000)
        assertTrue(host.forgotten.isEmpty(), "forgot ${host.forgotten}")
        assertEquals(3, host.commands.size)
    }

    @Test fun backgroundSkipsTheRoundAndUnregisterStopsTheLoop() = runTest {
        val host = Host(mapOf("a" to "claude-server-a"), background = true)
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        rec.register("a")
        advanceTimeBy(60_000)
        assertEquals(0, host.commands.size)
        host.background = false
        advanceTimeBy(15_000)
        val n = host.commands.size
        assertTrue(n >= 1)
        rec.unregister("a")
        advanceTimeBy(60_000)
        assertEquals(n, host.commands.size, "no round after the last tab of the server left")
    }

    @Test fun adoptsTheLiveUuidOfTheShallowestProcessWithASessionFileElseTheManifest() = runTest {
        val host = Host(mapOf("a" to "claude-server-a", "b" to "claude-server-b"))
        host.uuids["a"] = "old"
        host.uuids["b"] = null
        // pane a: bash(100) -> launcher claude(101, no session file) -> claude(102, has one);  pane b: nothing running, manifest knows an id
        host.output = out(
            manifest = """[{"id":"b","serverId":"srv","folder":"/f","tmuxSessionName":"claude-server-b","claudeSessionId":"$uuidB"}]""",
            tmux = listOf("claude-server-a", "claude-server-b"),
            panes = listOf("claude-server-a 100", "claude-server-b 200"),
            ps = listOf("100 1 bash", "101 100 claude", "102 101 claude", "200 1 bash"),
            claude = listOf("EXE 101 -", "CAPF 101 1 0", "EXE 102 -", "CAPF 102 1 0", "SID 102 $uuidA"),
        )
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        rec.register("a"); rec.register("b")
        advanceTimeBy(1_001)
        assertEquals(setOf(Triple("a", uuidA, "pid-probe"), Triple("b", uuidB, "server")), host.adopted.toSet())
    }

    @Test fun theLiveUuidBeatsTheManifestWhenBothExistAndDiffer() = runTest {
        val host = Host(mapOf("a" to "claude-server-a"))
        host.uuids["a"] = "old"
        host.output = out(
            manifest = """[{"id":"a","serverId":"srv","folder":"/f","tmuxSessionName":"claude-server-a","claudeSessionId":"$uuidB"}]""",
            tmux = listOf("claude-server-a"), panes = listOf("claude-server-a 100"),
            ps = listOf("100 1 claude"), claude = listOf("EXE 100 -", "CAPF 100 1 0", "SID 100 $uuidA"),
        )
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        rec.register("a")
        advanceTimeBy(1_001)
        assertEquals(listOf(Triple("a", uuidA, "pid-probe")), host.adopted) // the seeded manifest id must not mask the real one
    }

    @Test fun aLiveUuidThatEqualsTheTabsIsStillPassedOnSoItConfirmsIt() = runTest {
        val host = Host(mapOf("a" to "claude-server-a"))
        host.uuids["a"] = uuidA
        host.output = out(
            tmux = listOf("claude-server-a"), panes = listOf("claude-server-a 100"),
            ps = listOf("100 1 claude"), claude = listOf("EXE 100 -", "CAPF 100 1 0", "SID 100 $uuidA"),
        )
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        rec.register("a")
        advanceTimeBy(1_001)
        assertEquals(listOf(Triple("a", uuidA, "pid-probe")), host.adopted)
        // the manifest alone (weaker source) equal to the tab's id is not worth a call
        host.adopted.clear()
        host.output = out(
            manifest = """[{"id":"a","serverId":"srv","folder":"/f","tmuxSessionName":"claude-server-a","claudeSessionId":"$uuidA"}]""",
            tmux = listOf("claude-server-a"), panes = emptyList(), ps = emptyList(), claude = emptyList(),
        )
        advanceTimeBy(15_000)
        assertTrue(host.adopted.isEmpty())
    }

    @Test fun aSessionFileWithoutATranscriptYieldsNoUuidNotTheManifestOne() = runTest {
        val host = Host(mapOf("a" to "claude-server-a"))
        host.uuids["a"] = uuidA
        host.output = out(
            manifest = """[{"id":"a","serverId":"srv","folder":"/f","tmuxSessionName":"claude-server-a","claudeSessionId":"$uuidB"}]""",
            tmux = listOf("claude-server-a"), panes = listOf("claude-server-a 100"),
            ps = listOf("100 1 claude"), claude = listOf("EXE 100 -", "CAPF 100 1 0", "SID 100 -"),
        )
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        rec.register("a")
        advanceTimeBy(1_001)
        // the claude forked on --resume: its new id has no transcript yet. The pid probe says "none" and the
        // manifest is the weaker source, which still names uuidB — exactly what the legacy loop did.
        assertEquals(listOf(Triple("a", uuidB, "server")), host.adopted)
    }

    @Test fun capabilityIsCachedForItsTtlAndAsksForUnknownNamesOnce() = runTest {
        val host = Host(mapOf("a" to "claude-server-a"))
        host.output = out(
            tmux = listOf("claude-server-a", "claude-server-x"),
            panes = listOf("claude-server-a 100", "claude-server-x 300"),
            ps = listOf("100 1 bash", "101 100 claude", "300 1 bash", "301 300 claude"),
            claude = listOf("EXE 101 -", "CAPF 101 1 0", "EXE 301 -", "CAPF 301 0 0"),
        )
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        assertEquals(true, rec.capability("srv", "claude-server-a")) // not registered: asked for, one exec
        assertEquals(1, host.commands.size)
        assertEquals(true, rec.capability("srv", "claude-server-a")) // inside the ttl: no exec
        assertEquals(1, host.commands.size)
        assertEquals(false, rec.capability("srv", "claude-server-x")) // a new name: one more exec, restricted to it
        assertEquals(2, host.commands.size)
        assertTrue("claude-server-x" in host.commands.last())
        host.output = null
        advanceTimeBy(31_000)
        assertNull(rec.capability("srv", "claude-server-a")) // no transport = unknown
    }

    @Test fun checkClosedLooksAgainAfterAShortWaitWhileTheSessionIsStillThere() = runTest {
        val host = Host(mapOf("a" to "claude-server-a"))
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host, periodMs = 3_600_000)
        host.output = out(tmux = listOf("claude-server-a"))
        rec.register("a")
        advanceTimeBy(1_001) // the loop's first round; the next one is an hour away
        host.commands.clear()
        host.output = out(tomb = listOf("claude-server-a"), tmux = listOf("claude-server-a", "claude-server-b"))
        val t0 = currentTime
        rec.checkClosed("a", delayMs = 1_000)
        assertEquals(6_000, currentTime - t0, "1 s delay + the 5 s look-again while the session is still present")
        assertEquals(2, host.commands.size)
        assertTrue(host.forgotten.isEmpty())
        // the pane dies before the look
        host.commands.clear()
        host.output = out(tomb = listOf("claude-server-a"), tmux = listOf("claude-server-b"))
        rec.checkClosed("a", delayMs = 0)
        assertEquals(listOf("a"), host.forgotten)
        assertEquals(1, host.commands.size)
    }

    @Test fun checkClosedOfAnUnregisteredOrBackgroundTabDoesNothing() = runTest {
        val host = Host(mapOf("a" to "claude-server-a"))
        host.output = closedOutput
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host, periodMs = 3_600_000)
        rec.checkClosed("a", 0) // never registered
        assertTrue(host.forgotten.isEmpty() && host.commands.isEmpty())
        rec.register("a")
        host.background = true
        advanceTimeBy(1_001)
        rec.checkClosed("a", 0)
        assertTrue(host.forgotten.isEmpty() && host.commands.isEmpty())
    }

    @Test fun cancellationFromTheFactsProviderOfTheLegacyReconcilerPropagates() = runTest {
        val legacy = LegacyServerReconciler(facts = { throw CancellationException("cancelled") }, forget = { error("must not forget") })
        legacy.register("a")
        assertFailsWith<CancellationException> { legacy.checkClosed("a", 0) }
    }

    @Test fun reconcileAllNamesAreTheTabsOfTheServerSorted() = runTest {
        val host = Host(mapOf("a" to "claude-server-b", "b" to "claude-server-a"))
        host.output = out()
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        rec.register("a"); rec.register("b")
        advanceTimeBy(1_001)
        assertTrue("'claude-server-a' 'claude-server-b'" in host.commands.single(), host.commands.single())
    }

    @Test fun forgettingTheLastTabOfAServerRunsToTheEndEvenThoughItStopsTheLoop() = runTest {
        val host = Host(mapOf("a" to "claude-server-a"))
        host.output = closedOutput // the peer claude-server-b lives only in tmux
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        // like the real teardown: it takes a while and itself unregisters the tab, which stops this very loop
        host.onForget = { delay(100); rec.unregister(it); delay(100) }
        rec.register("a")
        advanceTimeBy(1_001)
        assertTrue(host.forgotten.isEmpty(), "the teardown is still running")
        advanceTimeBy(300)
        assertEquals(listOf("a"), host.forgotten)
        val n = host.commands.size
        advanceTimeBy(60_000)
        assertEquals(n, host.commands.size, "the loop ended with the last tab")
    }

    @Test fun aForgetThatThrowsLeavesTheTabRegisteredAndIsTriedAgain() = runTest {
        val host = Host(mapOf("a" to "claude-server-a"))
        host.output = closedOutput
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        var attempts = 0
        host.onForget = { attempts++; rec.unregister(it); throw IllegalStateException("teardown failed") }
        rec.register("a")
        advanceTimeBy(1_001)
        assertEquals(1, attempts)
        advanceTimeBy(15_000)
        assertEquals(2, attempts, "the tab went back to the registration and the next round forgets it again")
        assertTrue(host.forgotten.isEmpty())
    }

    @Test fun aSecondRoundDuringTheTeardownDoesNotForgetTheSameTabAgain() = runTest {
        val host = Host(mapOf("a" to "claude-server-a"))
        host.output = closedOutput
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        var calls = 0
        host.onForget = { calls++; delay(20_000) }
        rec.register("a")
        advanceTimeBy(1_001)
        launch { rec.checkClosed("a", 0) }
        advanceTimeBy(500)
        assertEquals(1, calls)
        advanceTimeBy(30_000)
        assertEquals(listOf("a"), host.forgotten)
        assertEquals(1, calls)
    }

    @Test fun checkUuidAdoptsButNeverForgetsATabNorOneThatIsNotRegistered() = runTest {
        val host = Host(mapOf("a" to "claude-server-a", "u" to "claude-server-u"), background = true)
        host.output = out(
            tomb = listOf("claude-server-a", "claude-server-u"), tmux = listOf("claude-server-b"),
        )
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        rec.register("a") // the loop is idle (background); only the explicit calls run
        rec.checkUuid("a")
        rec.checkUuid("u") // not registered at all
        assertTrue(host.forgotten.isEmpty(), "forgot ${host.forgotten}")
        assertTrue(host.commands.isNotEmpty())
    }

    @Test fun namesTmuxNoLongerListsLeaveThePaneFilter() = runTest {
        val host = Host(mapOf("a" to "claude-server-a"))
        host.output = out(tmux = listOf("claude-server-a"))
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        rec.register("a")
        rec.capability("srv", "claude-server-zz") // asked for, but tmux has no such session
        assertTrue("claude-server-zz" in host.commands[0], host.commands[0])
        advanceTimeBy(1_001) // the loop's round
        assertTrue("claude-server-zz" !in host.commands.last(), host.commands.last())
    }

    @Test fun aTabClosedLocallyWhileTheRoundIsOnTheWireIsNotForgottenAfterwards() = runTest {
        val host = Host(mapOf("a" to "claude-server-a"))
        host.output = closedOutput
        host.onRun = { delay(5_000) }
        val rec = TestScopeHelper(backgroundScope) { currentTime }.reconciler(host)
        rec.register("a")
        launch { rec.checkClosed("a", 0) }
        advanceTimeBy(100)
        rec.unregister("a") // the user closed the tab while the exec was still running
        advanceTimeBy(10_000)
        assertTrue(host.commands.isNotEmpty())
        assertTrue(host.forgotten.isEmpty(), "forgot ${host.forgotten}: a tab that is no longer registered is nobody's to forget")
    }
}
