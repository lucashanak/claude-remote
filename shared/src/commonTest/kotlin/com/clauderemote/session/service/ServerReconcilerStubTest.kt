package com.clauderemote.session.service

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the stub to TODAY's forget rule (SessionPersistenceService "CLOSED ON
 * ANOTHER DEVICE": tombstoned ∧ pane gone). A tightening of the rule (additionally
 * requiring a live peer) turns THIS red on purpose — whoever tightens it replaces it.
 */
class ServerReconcilerStubTest {
    @Test fun legacyRuleForgetsOnTombstonedAndGoneWithoutAnyPeerFact() {
        assertTrue(LegacyForgetRule.shouldForget(ClosedFacts(tombstoned = true, tmuxAlive = false)))
        assertFalse(LegacyForgetRule.shouldForget(ClosedFacts(tombstoned = true, tmuxAlive = true)))
        assertFalse(LegacyForgetRule.shouldForget(ClosedFacts(tombstoned = false, tmuxAlive = false)))
        assertFalse(LegacyForgetRule.shouldForget(ClosedFacts(tombstoned = false, tmuxAlive = true)))
    }

    @Test fun checkClosedExecutesLegacyRuleAfterDelay() = runTest {
        val forgotten = mutableListOf<String>()
        val r = LegacyServerReconciler(
            facts = { sid -> if (sid == "gone") ClosedFacts(true, false) else ClosedFacts(true, true) },
            forget = { forgotten += it },
        )
        r.register("gone"); r.register("alive")
        r.checkClosed("gone", 5_000)
        r.checkClosed("alive", 0)
        assertEquals(listOf("gone"), forgotten)
        assertEquals(5_000L, testScheduler.currentTime) // honoured delayMs on virtual time
    }

    @Test fun unknownFactsNeverForget() = runTest {
        val forgotten = mutableListOf<String>()
        LegacyServerReconciler(facts = { null }, forget = { forgotten += it }).apply { register("x") }.checkClosed("x", 0)
        LegacyServerReconciler().apply { register("x") }.checkClosed("x", 0) // unwired: only logs
        assertTrue(forgotten.isEmpty())
    }

    @Test fun unregisteredIdIsNeverForgottenEvenWhenUnregisteredDuringTheDelay() = runTest {
        val forgotten = mutableListOf<String>()
        val r = LegacyServerReconciler(facts = { ClosedFacts(true, false) }, forget = { forgotten += it })
        r.checkClosed("never-registered", 0)
        r.register("s"); r.unregister("s")
        r.checkClosed("s", 0)
        assertTrue(forgotten.isEmpty())
        r.register("late")
        val job = launch { r.checkClosed("late", 1_000) }
        advanceTimeBy(500); r.unregister("late"); advanceUntilIdle(); job.join()
        assertTrue(forgotten.isEmpty(), "unregistered while waiting: must not forget")
    }

    @Test fun factsThatThrowAreUnknownNotAnError() = runTest {
        val forgotten = mutableListOf<String>()
        val r = LegacyServerReconciler(facts = { throw java.io.IOException("probe failed") }, forget = { forgotten += it })
        r.register("a")
        r.checkClosed("a", 0) // must not propagate
        assertTrue(forgotten.isEmpty())
    }

    @Test fun aProviderThatFailsClosedMustReportUnknownOrAliveSoNothingIsForgotten() = runTest {
        // The contract (see ClosedFacts): a failed tmux probe is reported as "alive" or as no facts.
        // A provider that honours it never makes the stub forget on a flaky link:
        val forgotten = mutableListOf<String>()
        var linkUp = false
        val failOpen: suspend (String) -> ClosedFacts? = { if (linkUp) ClosedFacts(true, false) else ClosedFacts(true, true) }
        val r = LegacyServerReconciler(facts = failOpen, forget = { forgotten += it })
        r.register("a")
        r.checkClosed("a", 0)
        assertTrue(forgotten.isEmpty())
        linkUp = true
        r.checkClosed("a", 0)
        assertEquals(listOf("a"), forgotten)
    }

    @Test fun nothingHappensWhileInBackground() = runTest {
        val forgotten = mutableListOf<String>()
        var bg = true
        val r = LegacyServerReconciler(facts = { ClosedFacts(true, false) }, forget = { forgotten += it }, isBackground = { bg })
        r.register("a")
        r.checkClosed("a", 0)
        assertTrue(forgotten.isEmpty())
        bg = false
        r.checkClosed("a", 0)
        assertEquals(listOf("a"), forgotten)
    }

    @Test fun registerUnregisterAndUnknownCapability() = runTest {
        val r = LegacyServerReconciler()
        r.register("a"); r.register("b"); r.unregister("a")
        assertEquals(setOf("b"), r.registeredIds)
        assertNull(r.capability("srv", "claude-server-x"))
    }

    @Test fun otherSeamStubsAreInert() {
        assertFalse(PersistenceClosures.None.recentlyClosedLocally("srv", "n", 60_000))
        HookEventSink.NoOp.onActivity("s", "n", 1) // compiles and does nothing
    }
}
