package com.clauderemote.session.service

import com.clauderemote.storage.AppSettings
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommFlagsTest {
    private val logged = mutableListOf<String>()
    private var oldPlatformLog: ((String, String, String, Throwable?) -> Unit)? = null

    @BeforeTest fun reset() {
        CommFlags.resetForTest()
        logged.clear()
        oldPlatformLog = FileLogger.platformLog
        FileLogger.platformLog = { _, tag, msg, _ -> if (tag == "CommFlags") logged += msg }
    }

    @AfterTest fun restore() {
        FileLogger.platformLog = oldPlatformLog
        CommFlags.resetForTest()
    }

    private fun raw(
        pool: Boolean = false, exec: Boolean = false, hooks: Boolean = false,
        lazy: Boolean = false, fg: Boolean = false,
    ) = CommFlags.Raw(pool = pool, exec = exec, hooks = hooks, lazy = lazy, fgDeadlink = fg)

    @Test fun shippedDefaultsAreThePlanDefaults() {
        val e = CommFlags.resolve(CommFlags.Raw())
        assertTrue(e.pool && e.exec && e.hooks)
        assertFalse(e.lazy)
        assertFalse(e.fgDeadlink)
        assertEquals(6, e.warmTabs)
        assertEquals(120, e.bgParkGraceS)
        assertTrue(e.notes.isEmpty())
    }

    @Test fun allOffIsValidLegacy() {
        val e = CommFlags.resolve(raw())
        assertFalse(e.pool || e.exec || e.hooks || e.lazy || e.fgDeadlink)
        assertTrue(e.notes.isEmpty())
        assertEquals(3, e.shellCap)
    }

    @Test fun safeCombinationsAreKeptVerbatim() {
        val safe = listOf(
            raw(pool = true),
            raw(pool = true, exec = true),
            raw(pool = true, exec = true, hooks = true),
            raw(pool = true, exec = true, hooks = true, lazy = true),
            raw(pool = true, exec = true, hooks = true, lazy = true, fg = true),
            raw(pool = true, fg = true),
        )
        for (r in safe) {
            val e = CommFlags.resolve(r)
            assertEquals(r.pool, e.pool); assertEquals(r.exec, e.exec); assertEquals(r.hooks, e.hooks)
            assertEquals(r.lazy, e.lazy); assertEquals(r.fgDeadlink, e.fgDeadlink)
            assertTrue(e.notes.isEmpty(), "no downgrade for $r: ${e.notes}")
        }
    }

    @Test fun shellCapIs8OnlyWithPoolAndExecElse3() {
        assertEquals(3, CommFlags.resolve(raw(pool = true)).shellCap)
        assertEquals(8, CommFlags.resolve(raw(pool = true, exec = true)).shellCap)
        assertEquals(3, CommFlags.resolve(raw(exec = true)).shellCap) // exec alone is downgraded
    }

    @Test fun everyInvalidCombinationIsDowngradedWithNote() {
        // Exhaustive over all 32 raw combinations: post-conditions + a note per downgrade.
        for (bits in 0 until 32) {
            val r = raw(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0, bits and 8 != 0, bits and 16 != 0)
            val e = CommFlags.resolve(r)
            // implications hold on the result
            if (e.exec) assertTrue(e.pool, "exec ⇒ pool for $r")
            if (e.hooks) assertTrue(e.pool && e.exec, "hooks ⇒ pool ∧ exec for $r")
            if (e.lazy) assertTrue(e.pool && e.exec && e.hooks, "lazy ⇒ pool ∧ exec ∧ hooks for $r")
            if (e.fgDeadlink) assertTrue(e.pool, "fg ⇒ pool for $r")
            // a flag is only ever turned off, and every turn-off is logged
            for (g in CommFlags.ALL_GROUPS) {
                if (!r.let { raw -> when (g) {
                        CommFlags.POOL -> raw.pool; CommFlags.EXEC -> raw.exec; CommFlags.HOOKS -> raw.hooks
                        CommFlags.LAZY -> raw.lazy; else -> raw.fgDeadlink } }) {
                    assertFalse(e.isOn(g), "$g must not turn on by itself for $r")
                } else if (!e.isOn(g)) {
                    assertTrue(e.notes.any { it.startsWith("CommFlags: $g forced off because ") }, "note for $g in $r")
                }
            }
        }
    }

    @Test fun specificDowngradeReasons() {
        assertEquals(
            listOf("CommFlags: exec forced off because pool is off", "CommFlags: hooks forced off because pool is off",
                "CommFlags: lazy forced off because pool is off"),
            CommFlags.resolve(raw(exec = true, hooks = true, lazy = true)).notes,
        )
        val e = CommFlags.resolve(raw(pool = true, exec = true, lazy = true))
        assertFalse(e.lazy)
        assertEquals(listOf("CommFlags: lazy forced off because hooks is off"), e.notes)
        assertFalse(CommFlags.resolve(raw(fg = true)).fgDeadlink)
    }

    @Test fun hooksImplicationIsExplicitNotJustViaLazy() {
        val e = CommFlags.resolve(raw(pool = true, hooks = true)) // exec off
        assertFalse(e.hooks)
        assertEquals(listOf("CommFlags: hooks forced off because exec is off"), e.notes)
    }

    @Test fun serverOffTurnsGroupsOffAndCascades() {
        val all = CommFlags.Raw(pool = true, exec = true, hooks = true, lazy = true, fgDeadlink = true)
        val e = CommFlags.resolve(all, "s1", setOf("hooks"))
        assertFalse(e.hooks); assertFalse(e.lazy) // lazy needs hooks
        assertTrue(e.pool && e.exec && e.fgDeadlink)
        val e2 = CommFlags.resolve(all, "s1", setOf("exec"))
        assertFalse(e2.exec); assertFalse(e2.hooks); assertFalse(e2.lazy)
        assertEquals(3, e2.shellCap)
    }

    @Test fun serverOffPoolCascadesToEverythingThatNeedsIt() {
        val all = CommFlags.Raw(pool = true, exec = true, hooks = true, lazy = true, fgDeadlink = true)
        val e = CommFlags.resolve(all, "s1", setOf("pool"))
        assertFalse(e.pool || e.exec || e.hooks || e.lazy || e.fgDeadlink, "pool is the root of every implication")
        assertEquals(3, e.shellCap)
        assertTrue(e.notes.any { it == "CommFlags: pool forced off because server-side force-off" })
        assertTrue(e.notes.any { it == "CommFlags: exec forced off because pool is off" })
    }

    @Test fun serverOffFgDeadlinkTurnsOnlyThatGroupOff() {
        val all = CommFlags.Raw(pool = true, exec = true, hooks = true, lazy = true, fgDeadlink = true)
        val e = CommFlags.resolve(all, "s1", setOf("fg_deadlink"))
        assertFalse(e.fgDeadlink)
        assertTrue(e.pool && e.exec && e.hooks && e.lazy)
        assertEquals(listOf("CommFlags: fg_deadlink forced off because server-side force-off"), e.notes)
    }

    @Test fun bgParkGraceIsClampedWithNote() {
        assertEquals(CommFlags.BG_PARK_GRACE_MIN_S, CommFlags.resolve(CommFlags.Raw(bgParkGraceS = -5)).bgParkGraceS)
        assertEquals(CommFlags.BG_PARK_GRACE_MAX_S, CommFlags.resolve(CommFlags.Raw(bgParkGraceS = 1_000_000)).bgParkGraceS)
        assertTrue(CommFlags.resolve(CommFlags.Raw(bgParkGraceS = -5)).notes.single().contains("comm_bg_park_grace_s -5 clamped"))
        assertEquals(120, CommFlags.resolve(CommFlags.Raw()).bgParkGraceS)
        assertTrue(CommFlags.resolve(CommFlags.Raw(bgParkGraceS = 600)).notes.isEmpty())
    }

    @Test fun serverOffCanOnlyTurnOffNeverOn() {
        val e = CommFlags.resolve(raw(pool = true), "s1", setOf("lazy", "exec", "hooks"))
        assertTrue(e.pool); assertFalse(e.exec || e.hooks || e.lazy)
    }

    @Test fun legacySetWinsOverEverything() {
        val all = CommFlags.Raw(pool = true, exec = true, hooks = true, lazy = true, fgDeadlink = true, legacyServers = setOf("old"))
        val e = CommFlags.resolve(all, "old", emptySet())
        assertFalse(e.pool || e.exec || e.hooks || e.lazy || e.fgDeadlink)
        assertEquals(3, e.shellCap)
        val other = CommFlags.resolve(all, "new")
        assertTrue(other.pool && other.exec && other.hooks && other.lazy && other.fgDeadlink)
        // legacy also beats "server-off says nothing"
        assertFalse(CommFlags.resolve(all, "old", setOf("lazy")).pool)
    }

    @Test fun warmTabsClampedToFiveEightWithNote() {
        assertEquals(5, CommFlags.resolve(CommFlags.Raw(warmTabs = 3)).warmTabs)
        assertEquals(8, CommFlags.resolve(CommFlags.Raw(warmTabs = 99)).warmTabs)
        assertEquals(5, CommFlags.resolve(CommFlags.Raw(warmTabs = 5)).warmTabs)
        assertEquals(8, CommFlags.resolve(CommFlags.Raw(warmTabs = 8)).warmTabs)
        assertEquals(7, CommFlags.resolve(CommFlags.Raw(warmTabs = 7)).warmTabs)
        assertTrue(CommFlags.resolve(CommFlags.Raw(warmTabs = 3)).notes.single().contains("clamped to 5"))
        assertTrue(CommFlags.resolve(CommFlags.Raw(warmTabs = 6)).notes.isEmpty())
    }

    @Test fun holderCurrentAndPerServerView() {
        assertTrue(CommFlags.deviceDefaults.value.pool)
        CommFlags.set(CommFlags.Raw(pool = true, exec = true, hooks = true, lazy = true, legacyServers = setOf("old")))
        assertTrue(CommFlags.deviceDefaults.value.lazy)
        assertFalse(CommFlags.effectiveFor("old").pool)
        CommFlags.applyServerOff("srv", setOf("lazy", "bogus"))
        assertFalse(CommFlags.effectiveFor("srv").lazy)
        assertTrue(CommFlags.effectiveFor("srv").hooks)
        assertTrue(CommFlags.effectiveFor("other").lazy)
        CommFlags.applyServerOff("srv", emptySet())
        assertTrue(CommFlags.effectiveFor("srv").lazy)
    }

    @Test fun applyServerOffIgnoresUnknownNames() {
        CommFlags.applyServerOff("srv", setOf("bogus"))
        assertTrue(CommFlags.effectiveFor("srv").pool)
    }

    @Test fun deviceDefaultsIgnoreLegacyAndServerOffButPerServerViewsHonourThem() {
        CommFlags.set(CommFlags.Raw(pool = true, exec = true, hooks = true, legacyServers = setOf("old")))
        CommFlags.applyServerOff("srv", setOf("hooks"))
        assertTrue(CommFlags.deviceDefaults.value.pool && CommFlags.deviceDefaults.value.hooks)
        assertFalse(CommFlags.effectiveFor("old").pool)
        assertFalse(CommFlags.effectiveFor("srv").hooks)
    }

    @Test fun serverOffAndLegacyAreLoggedPerServerOncePerChange() {
        CommFlags.set(CommFlags.Raw(pool = true, exec = true, hooks = true, lazy = true))
        CommFlags.applyServerOff("srv", setOf("hooks"))
        assertTrue(logged.any { it == "[srv] CommFlags: hooks forced off because server-side force-off" }, logged.toString())
        assertTrue(logged.any { it == "[srv] CommFlags: lazy forced off because hooks is off" }, logged.toString())
        val n = logged.size
        CommFlags.applyServerOff("srv", setOf("hooks")) // same again: nothing new to say
        assertEquals(n, logged.size, logged.toString())
        CommFlags.applyServerOff("srv", emptySet())
        CommFlags.applyServerOff("srv", setOf("hooks")) // forced off anew after being cleared: logged again
        assertEquals(n + 2, logged.size, logged.toString())

        CommFlags.set(CommFlags.Raw(pool = true, exec = true, hooks = true, lazy = true, legacyServers = setOf("old")))
        assertTrue(logged.any { it == "[old] CommFlags: all groups forced off because server old is in the legacy set" }, logged.toString())
    }

    @Test fun effectiveFlowFollowsSettingsLegacyAndServerOff() = runTest {
        val flow = CommFlags.effectiveFlow("s1")
        assertTrue(flow.value.pool && flow.value.hooks && !flow.value.lazy)
        val seen = mutableListOf<CommFlags.Effective>()
        val job = launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) { flow.collect { seen += it } }
        CommFlags.set(CommFlags.Raw(pool = true, exec = true, hooks = true, lazy = true))
        assertTrue(flow.value.lazy)
        CommFlags.applyServerOff("s1", setOf("lazy"))
        assertFalse(flow.value.lazy)
        assertTrue(flow.value.hooks)
        CommFlags.applyServerOff("other", setOf("pool")) // another server: no effect on s1
        assertTrue(flow.value.pool)
        CommFlags.set(CommFlags.Raw(pool = true, exec = true, hooks = true, lazy = true, legacyServers = setOf("s1")))
        assertFalse(flow.value.pool)
        assertTrue(CommFlags.effectiveFlow("other").let { !it.value.pool }, "server-off for 'other' is visible through its own flow")
        assertEquals(seen.last(), flow.value)
        assertTrue(seen.size >= 4, "every change was emitted: ${seen.size}")
        job.cancel()
    }

    @Test fun hookInstallRequestIsAOneShotPerServerEventAndNothingIsStored() = runTest {
        // nobody listening: the request is simply gone, a later listener does not see it
        CommFlags.requestHooksInstall("early")
        val got = mutableListOf<String>()
        val job = launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            CommFlags.hookInstallRequests.collect { got += it }
        }
        assertEquals(emptyList(), got)
        CommFlags.requestHooksInstall("srv-1")
        CommFlags.requestHooksInstall("srv-2")
        assertEquals(listOf("srv-1", "srv-2"), got)
        job.cancel()
        // not persisted anywhere
        val store = FakeKeyValueStore()
        CommFlags.requestHooksInstall("srv-3")
        AppSettings(store).publishCommFlags()
        assertTrue(store.map.keys.none { it.contains("install") }, store.map.keys.toString())
    }

    @Test fun legacyServerIdsWithCommasAndBackslashesSurviveTheRoundTrip() {
        val store = FakeKeyValueStore()
        val ids = setOf("plain", "a,b", "c\\d", "e\\,f")
        AppSettings(store).commLegacyServers = ids
        assertEquals(ids, AppSettings(store).commLegacyServers)
        // lists written by older builds (plain comma-separated) still parse
        assertEquals(setOf("x", "y"), AppSettings(FakeKeyValueStore(mapOf("comm_legacy_servers" to "x, y,,"))).commLegacyServers)
    }

    @Test fun concurrentSettersDoNotLoseTheLastUpdate() {
        val threads = (1..8).map { i ->
            Thread { repeat(200) { CommFlags.applyServerOff("s$i", setOf("lazy")); CommFlags.applyServerOff("s$i", emptySet()) } }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        CommFlags.set(CommFlags.Raw(pool = true, exec = true, hooks = true, lazy = true))
        for (i in 1..8) assertTrue(CommFlags.effectiveFor("s$i").lazy)
        assertTrue(CommFlags.deviceDefaults.value.lazy)
    }

    @Test fun appSettingsRoundTripAndPublish() {
        val store = FakeKeyValueStore()
        val s = AppSettings(store)
        // defaults
        val d = s.commFlagsRaw()
        assertEquals(CommFlags.Raw(), d)
        s.commLazy = true; s.commWarmTabs = 7; s.commLegacyServers = setOf("a", "b")
        val r = AppSettings(store).commFlagsRaw()
        assertTrue(r.lazy); assertEquals(7, r.warmTabs); assertEquals(setOf("a", "b"), r.legacyServers)
        AppSettings(store).publishCommFlags()
        assertEquals(7, CommFlags.deviceDefaults.value.warmTabs)
        assertFalse(CommFlags.effectiveFor("a").pool) // legacy server
        assertTrue(CommFlags.effectiveFor("c").pool)
    }
}
