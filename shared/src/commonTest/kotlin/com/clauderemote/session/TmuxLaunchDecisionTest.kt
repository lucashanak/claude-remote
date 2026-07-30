package com.clauderemote.session

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for [TmuxLaunchDecider] — the decision that determines whether a
 * reconnect lands the user back in their own conversation, in an empty one, or
 * has the session forgotten and deleted.
 *
 * Two properties are locked in here:
 *  - the exact [TmuxLaunchDecision] for every reachable input combination, and
 *  - probe LAZINESS: each probe is an SSH round-trip (sometimes over cellular),
 *    so a probe that today's code never makes must stay unmade. A probe declared
 *    unavailable in a row (`null`) fails the test if it is invoked at all.
 */
class TmuxLaunchDecisionTest {

    /**
     * Probe bench: each answer is `null` when the row asserts the probe must
     * NEVER be invoked, and counts invocations so single-call/no-call properties
     * are checkable.
     */
    private class Probes(
        val tmuxExists: Boolean? = null,
        val stillTracked: Boolean? = null,
        val hasLivePeers: Boolean? = null,
        val hasTranscript: Boolean? = null,
    ) {
        var tmuxExistsCalls = 0
        var stillTrackedCalls = 0
        var hasLivePeersCalls = 0
        var hasTranscriptCalls = 0

        suspend fun decide(
            isNew: Boolean,
            checkClosedElsewhere: Boolean,
            hasClaudeSessionId: Boolean,
        ): TmuxLaunchDecision = TmuxLaunchDecider.decide(
            isNew = isNew,
            checkClosedElsewhere = checkClosedElsewhere,
            hasClaudeSessionId = hasClaudeSessionId,
            tmuxExists = { tmuxExistsCalls++; answer("tmuxExists", tmuxExists) },
            stillTracked = { stillTrackedCalls++; answer("stillTracked", stillTracked) },
            hasLivePeers = { hasLivePeersCalls++; answer("hasLivePeers", hasLivePeers) },
            hasTranscript = { hasTranscriptCalls++; answer("hasTranscript", hasTranscript) },
        )

        private fun answer(name: String, value: Boolean?): Boolean =
            value ?: fail("probe '$name' was invoked but must never be — every probe is an SSH round-trip")
    }

    private data class Row(
        val name: String,
        val isNew: Boolean,
        val checkClosedElsewhere: Boolean,
        val hasClaudeSessionId: Boolean,
        val probes: Probes,
        val expected: TmuxLaunchDecision,
    )

    private fun row(
        name: String,
        isNew: Boolean = false,
        checkClosedElsewhere: Boolean = false,
        hasClaudeSessionId: Boolean = true,
        tmuxExists: Boolean? = null,
        stillTracked: Boolean? = null,
        hasLivePeers: Boolean? = null,
        hasTranscript: Boolean? = null,
        expected: TmuxLaunchDecision,
    ) = Row(
        name = name,
        isNew = isNew,
        checkClosedElsewhere = checkClosedElsewhere,
        hasClaudeSessionId = hasClaudeSessionId,
        probes = Probes(tmuxExists, stillTracked, hasLivePeers, hasTranscript),
        expected = expected,
    )

    /**
     * Every reachable combination of (isNew, checkClosedElsewhere,
     * claudeSessionId != null, tmuxExists, stillTracked, hasLivePeers,
     * hasTranscript). Probes left unset in a row must never be invoked, which is
     * itself asserted (see [Probes.answer]).
     */
    private fun table(): List<Row> = listOf(
        // isNew decides with NO probes at all, whatever the other inputs say.
        row("new_noId", isNew = true, hasClaudeSessionId = false, expected = TmuxLaunchDecision.FreshLaunch),
        row("new_withId", isNew = true, expected = TmuxLaunchDecision.FreshLaunch),
        row("new_checkClosedElsewhere_noId", isNew = true, checkClosedElsewhere = true, hasClaudeSessionId = false, expected = TmuxLaunchDecision.FreshLaunch),
        row("new_checkClosedElsewhere_withId", isNew = true, checkClosedElsewhere = true, expected = TmuxLaunchDecision.FreshLaunch),

        // tmux alive ⇒ plain attach; the closed-elsewhere and transcript probes
        // are gated on tmux being GONE, so none of them may run.
        row("alive_noId", tmuxExists = true, hasClaudeSessionId = false, expected = TmuxLaunchDecision.Attach),
        row("alive_withId", tmuxExists = true, expected = TmuxLaunchDecision.Attach),
        row("alive_checkClosedElsewhere_noId", tmuxExists = true, checkClosedElsewhere = true, hasClaudeSessionId = false, expected = TmuxLaunchDecision.Attach),
        row("alive_checkClosedElsewhere_withId", tmuxExists = true, checkClosedElsewhere = true, expected = TmuxLaunchDecision.Attach),

        // tmux gone, closed-elsewhere check disabled (launchSession's
        // attach/history-resume callers): straight to rebuild, manifest and
        // liveness never consulted.
        row("gone_noCheck_transcript", tmuxExists = false, hasTranscript = true, expected = TmuxLaunchDecision.Rebuild(resume = true, withSessionId = true)),
        row("gone_noCheck_noTranscript", tmuxExists = false, hasTranscript = false, expected = TmuxLaunchDecision.Rebuild(resume = false, withSessionId = true)),
        row("gone_noCheck_noId", tmuxExists = false, hasClaudeSessionId = false, expected = TmuxLaunchDecision.Rebuild(resume = false, withSessionId = false)),

        // tmux gone but STILL in the shared manifest ⇒ nobody closed it; rebuild.
        // Liveness is not probed because the manifest already answered.
        row("gone_stillTracked_transcript", tmuxExists = false, checkClosedElsewhere = true, stillTracked = true, hasTranscript = true, expected = TmuxLaunchDecision.Rebuild(resume = true, withSessionId = true)),
        row("gone_stillTracked_noTranscript", tmuxExists = false, checkClosedElsewhere = true, stillTracked = true, hasTranscript = false, expected = TmuxLaunchDecision.Rebuild(resume = false, withSessionId = true)),
        row("gone_stillTracked_noId", tmuxExists = false, checkClosedElsewhere = true, stillTracked = true, hasClaudeSessionId = false, expected = TmuxLaunchDecision.Rebuild(resume = false, withSessionId = false)),

        // tmux gone, untracked, and the tmux server is PROVABLY up with other
        // live sessions ⇒ another device closed this one. Forget it — and don't
        // pay for a transcript probe on a session we're about to delete.
        row("gone_untracked_livePeers_withId", tmuxExists = false, checkClosedElsewhere = true, stillTracked = false, hasLivePeers = true, expected = TmuxLaunchDecision.ForgetClosedElsewhere),
        row("gone_untracked_livePeers_noId", tmuxExists = false, checkClosedElsewhere = true, stillTracked = false, hasLivePeers = true, hasClaudeSessionId = false, expected = TmuxLaunchDecision.ForgetClosedElsewhere),

        // tmux gone, untracked, NO live peers ⇒ whole-server outage, NOT
        // closed-elsewhere. See wholeServerOutage* tests below.
        row("gone_untracked_noPeers_transcript", tmuxExists = false, checkClosedElsewhere = true, stillTracked = false, hasLivePeers = false, hasTranscript = true, expected = TmuxLaunchDecision.Rebuild(resume = true, withSessionId = true, afterSuspectedServerOutage = true)),
        row("gone_untracked_noPeers_noTranscript", tmuxExists = false, checkClosedElsewhere = true, stillTracked = false, hasLivePeers = false, hasTranscript = false, expected = TmuxLaunchDecision.Rebuild(resume = false, withSessionId = true, afterSuspectedServerOutage = true)),
        row("gone_untracked_noPeers_noId", tmuxExists = false, checkClosedElsewhere = true, stillTracked = false, hasLivePeers = false, hasClaudeSessionId = false, expected = TmuxLaunchDecision.Rebuild(resume = false, withSessionId = false, afterSuspectedServerOutage = true)),
    )

    @Test
    fun decisionTableCoversEveryReachableCombination() = runTest {
        val failures = mutableListOf<String>()
        for (r in table()) {
            val actual = r.probes.decide(r.isNew, r.checkClosedElsewhere, r.hasClaudeSessionId)
            if (actual != r.expected) failures += "${r.name}: expected ${r.expected}, got $actual"
        }
        assertTrue(failures.isEmpty(), "decision table mismatches:\n" + failures.joinToString("\n"))
    }

    @Test
    fun everyProbeIsInvokedAtMostOnce() = runTest {
        for (r in table()) {
            val p = r.probes
            p.decide(r.isNew, r.checkClosedElsewhere, r.hasClaudeSessionId)
            assertTrue(p.tmuxExistsCalls <= 1, "${r.name}: tmuxExists probed ${p.tmuxExistsCalls}x")
            assertTrue(p.stillTrackedCalls <= 1, "${r.name}: stillTracked probed ${p.stillTrackedCalls}x")
            assertTrue(p.hasLivePeersCalls <= 1, "${r.name}: hasLivePeers probed ${p.hasLivePeersCalls}x")
            assertTrue(p.hasTranscriptCalls <= 1, "${r.name}: hasTranscript probed ${p.hasTranscriptCalls}x")
        }
    }

    // ---- probe laziness: each of these is a saved SSH round-trip ------------

    @Test
    fun isNewShortCircuitsBeforeAnyProbe() = runTest {
        val p = Probes() // every probe forbidden
        assertEquals(TmuxLaunchDecision.FreshLaunch, p.decide(isNew = true, checkClosedElsewhere = true, hasClaudeSessionId = true))
        assertEquals(0, p.tmuxExistsCalls)
        assertEquals(0, p.stillTrackedCalls)
        assertEquals(0, p.hasLivePeersCalls)
        assertEquals(0, p.hasTranscriptCalls)
    }

    @Test
    fun liveTmuxProbesNothingElse() = runTest {
        val p = Probes(tmuxExists = true)
        assertEquals(TmuxLaunchDecision.Attach, p.decide(isNew = false, checkClosedElsewhere = true, hasClaudeSessionId = true))
        assertEquals(1, p.tmuxExistsCalls)
        assertEquals(0, p.stillTrackedCalls)
        assertEquals(0, p.hasLivePeersCalls)
        assertEquals(0, p.hasTranscriptCalls)
    }

    @Test
    fun stillTrackedSkipsTheLivenessProbe() = runTest {
        val p = Probes(tmuxExists = false, stillTracked = true, hasTranscript = true)
        p.decide(isNew = false, checkClosedElsewhere = true, hasClaudeSessionId = true)
        assertEquals(1, p.stillTrackedCalls)
        assertEquals(0, p.hasLivePeersCalls)
    }

    @Test
    fun checkClosedElsewhereFalseSkipsManifestAndLivenessProbes() = runTest {
        val p = Probes(tmuxExists = false, hasTranscript = true)
        p.decide(isNew = false, checkClosedElsewhere = false, hasClaudeSessionId = true)
        assertEquals(0, p.stillTrackedCalls)
        assertEquals(0, p.hasLivePeersCalls)
    }

    @Test
    fun forgetClosedElsewhereSkipsTheTranscriptProbe() = runTest {
        val p = Probes(tmuxExists = false, stillTracked = false, hasLivePeers = true)
        assertEquals(
            TmuxLaunchDecision.ForgetClosedElsewhere,
            p.decide(isNew = false, checkClosedElsewhere = true, hasClaudeSessionId = true),
        )
        assertEquals(0, p.hasTranscriptCalls)
    }

    @Test
    fun missingSessionIdSkipsTheTranscriptProbe() = runTest {
        val p = Probes(tmuxExists = false)
        p.decide(isNew = false, checkClosedElsewhere = false, hasClaudeSessionId = false)
        assertEquals(0, p.hasTranscriptCalls)
    }

    // ---- the whole-server-outage regression --------------------------------

    /**
     * THE regression. "tmux missing + not in the manifest" is AMBIGUOUS: a
     * WHOLE-server tmux death makes tmuxExists=false for EVERY session, and a
     * wiped/empty manifest makes stillTracked=false for every session too — so
     * this combination used to forget EVERY session on a transient server-wide
     * outage (the repeated whole-server session loss). It may only be read as
     * closed-elsewhere when the tmux server is PROVABLY up with ≥1 OTHER live
     * session. No live peers ⇒ outage ⇒ rebuild, never forget.
     */
    @Test
    fun wholeServerOutageRebuildsAndMustNeverForgetTheSession() = runTest {
        val p = Probes(tmuxExists = false, stillTracked = false, hasLivePeers = false, hasTranscript = true)
        val decision = p.decide(isNew = false, checkClosedElsewhere = true, hasClaudeSessionId = true)

        assertTrue(
            decision !is TmuxLaunchDecision.ForgetClosedElsewhere,
            "a server-wide tmux outage must NOT be read as closed-on-another-device — that forgot every session",
        )
        assertEquals(
            TmuxLaunchDecision.Rebuild(resume = true, withSessionId = true, afterSuspectedServerOutage = true),
            decision,
        )
    }

    /** Same outage, session with no transcript yet: still a rebuild, still not forgotten. */
    @Test
    fun wholeServerOutageWithoutTranscriptStillRebuilds() = runTest {
        val p = Probes(tmuxExists = false, stillTracked = false, hasLivePeers = false, hasTranscript = false)
        assertEquals(
            TmuxLaunchDecision.Rebuild(resume = false, withSessionId = true, afterSuspectedServerOutage = true),
            p.decide(isNew = false, checkClosedElsewhere = true, hasClaudeSessionId = true),
        )
    }

    /** Same outage, session with no claudeSessionId: rebuild with no id argument. */
    @Test
    fun wholeServerOutageWithoutSessionIdStillRebuilds() = runTest {
        val p = Probes(tmuxExists = false, stillTracked = false, hasLivePeers = false)
        assertEquals(
            TmuxLaunchDecision.Rebuild(resume = false, withSessionId = false, afterSuspectedServerOutage = true),
            p.decide(isNew = false, checkClosedElsewhere = true, hasClaudeSessionId = false),
        )
    }

    // ---- resume vs bare --session-id ---------------------------------------

    /**
     * A session that was launched but never interacted with has no jsonl (the
     * transcript is written lazily, on the first turn), so `claude --resume`
     * prints "No conversation found" and the tab comes back empty. It must be
     * relaunched fresh with the SAME --session-id so later restarts can resume.
     */
    @Test
    fun noTranscriptRebuildsWithSessionIdButWithoutResume() = runTest {
        val p = Probes(tmuxExists = false, hasTranscript = false)
        assertEquals(
            TmuxLaunchDecision.Rebuild(resume = false, withSessionId = true),
            p.decide(isNew = false, checkClosedElsewhere = false, hasClaudeSessionId = true),
        )
    }

    /** Transcript on disk ⇒ `--resume`, which is what continues the conversation. */
    @Test
    fun transcriptPresentRebuildsWithResume() = runTest {
        val p = Probes(tmuxExists = false, hasTranscript = true)
        assertEquals(
            TmuxLaunchDecision.Rebuild(resume = true, withSessionId = true),
            p.decide(isNew = false, checkClosedElsewhere = false, hasClaudeSessionId = true),
        )
    }
}
