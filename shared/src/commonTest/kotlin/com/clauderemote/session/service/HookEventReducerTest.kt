package com.clauderemote.session.service

import com.clauderemote.model.SessionActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HookEventReducerTest {
    private val b64 = java.util.Base64.getEncoder()
    private fun enc(s: String) = b64.encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun line(ev: String, tmux: String, ms: Long, extra: String = "", pid: Long = 4242) =
        "claude-remote-ev $ev ${enc(tmux)} $ms $pid ${if (extra.isEmpty()) "" else enc(extra)}"

    private fun ev(ev: String, ms: Long, tmux: String = "t1", extra: String = "") = HookEvent(ev, tmux, ms, 1, extra)

    // ---- line format ------------------------------------------------------

    @Test fun parsesTheLineWrittenByHookEv() {
        val e = HookEventLine.parse(line("ask", "claude-server-x", 1_700_000_000_123, "Která barva? [Červená, Modrá]"))
        assertEquals(HookEvent("ask", "claude-server-x", 1_700_000_000_123, 4242, "Která barva? [Červená, Modrá]"), e)
    }

    @Test fun anAbsentExtraAndASessionNameWithASpaceSurvive() {
        val e = assertNotNull(HookEventLine.parse(line("work", "name with space", 5)))
        assertEquals("name with space", e.tmux)
        assertEquals("", e.extra)
        // trailing newline / CR from the tail reader
        assertNotNull(HookEventLine.parse(line("work", "n", 5) + "\r"))
    }

    @Test fun malformedLinesAreRejectedNotGuessed() {
        assertNull(HookEventLine.parse("claude-remote-notify t 1"))
        assertNull(HookEventLine.parse("claude-remote-ev work"))
        assertNull(HookEventLine.parse("claude-remote-ev bogus ${enc("t")} 1 2 "))
        assertNull(HookEventLine.parse("claude-remote-ev work !!notbase64!! 1 2 "))
        assertNull(HookEventLine.parse("claude-remote-ev work ${enc("t")} notanumber 2 "))
        assertNull(HookEventLine.parse("claude-remote-ev work ${enc("")} 5 2 "))
    }

    // ---- reducer ----------------------------------------------------------

    private val now = 1_000_000L

    @Test fun workIsWorkingReArmsTheLatchAndClearsApproval() {
        val r = HookEventReducer()
        r.reduce(ev("perm", now - 10, extra = "Bash: ls"), now, 0)
        val w = r.reduce(ev("work", now), now, 0)
        assertEquals(SessionActivity.WORKING, w.activity)
        assertTrue(w.callWorking && w.clearedApproval)
        assertNull(r.approvalSince("t1"))
    }

    @Test fun permAndAskAreApprovalWithAnAgeAndANotification() {
        val r = HookEventReducer()
        val p = r.reduce(ev("perm", now - 1_000, extra = "Bash: rm -rf x"), now, 0)
        assertEquals(SessionActivity.APPROVAL_NEEDED, p.activity)
        val n = assertNotNull(p.notify)
        assertEquals("perm", n.kind)
        assertEquals("Bash: rm -rf x", n.text)
        assertEquals(now - 1_000, r.approvalSince("t1"))
        val a = r.reduce(ev("ask", now - 500, tmux = "t2", extra = "Which? [A, B]"), now, 0)
        assertEquals("ask", a.notify!!.kind)
    }

    @Test fun askdoneIsWorkingAndClearsApproval() {
        val r = HookEventReducer()
        r.reduce(ev("ask", now - 20), now, 0)
        val d = r.reduce(ev("askdone", now - 10), now, 0)
        assertEquals(SessionActivity.WORKING, d.activity)
        assertTrue(d.clearedApproval)
        assertFalse(d.callWorking, "only a prompt submit re-arms the latch")
    }

    @Test fun endIsIdleAndStartAsksForAUuidCheckAndAdoptsNothing() {
        val r = HookEventReducer()
        r.reduce(ev("perm", now - 20), now, 0)
        val s = r.reduce(ev("start", now - 10, extra = "source=clear exe=2.1.316"), now, 0)
        assertTrue(s.checkUuid)
        assertNull(s.activity, "start only triggers the check; no state is taken from it")
        assertFalse(s.clearedApproval, "a session start is no answer to a dialog")
        assertNotNull(r.approvalSince("t1"), "the pending approval survives a start")
        val e = r.reduce(ev("end", now), now, 0)
        assertEquals(SessionActivity.IDLE, e.activity)
    }

    @Test fun aClearEndThenStartLeavesTheSessionReadyNotIdle() {
        val r = HookEventReducer()
        assertEquals(SessionActivity.IDLE, r.reduce(ev("end", now - 20), now, 0).activity)
        val s = r.reduce(ev("start", now - 10, extra = "source=clear exe=2.1.316"), now, 0)
        assertEquals(SessionActivity.WAITING_FOR_INPUT, s.activity, "/clear is end then start: the session is ready")
        assertTrue(s.checkUuid)
        // a start that does not follow an end keeps the state alone
        assertNull(HookEventReducer().reduce(ev("start", now - 10), now, 0).activity)
    }

    @Test fun notificationsOnlyWithin10MinutesOnceTheSessionWasSeen() {
        val r = HookEventReducer()
        val first = now - 700_000
        r.reduce(ev("work", first), first, 0) // the reducer has been running since then
        assertNull(r.reduce(ev("perm", now - 601_000), now, 0).notify, "older than 10 min: no buzz")
        assertNotNull(r.reduce(ev("ask", now - 599_000), now, 0).notify, "within 10 min: buzz")
    }

    @Test fun anEventFromAnOutageAfterTheStartKeepsTheTenMinutesForEverySession() {
        // the reducer saw session A at t0; ~7 min later session B's first event arrives, stamped 5 min ago
        // (a reconnect brought it): it is after the start, so it is not the cold-start backlog and notifies
        val t0 = now
        val r = HookEventReducer()
        r.reduce(ev("work", t0, tmux = "a"), t0, 0)
        val later = t0 + 420_000
        assertNotNull(r.reduce(ev("perm", later - 300_000, tmux = "b"), later, 0).notify)
        // while a line stamped BEFORE the start is the replayed backlog whichever session it belongs to
        assertNull(r.reduce(ev("perm", t0 - 300_000, tmux = "c"), later, 0).notify)
    }

    @Test fun theFirstEventOfAColdStartNotifiesOnlyWithin60Seconds() {
        val old = HookEventReducer().reduce(ev("perm", now - 61_000), now, 0)
        assertNull(old.notify, "a replayed backlog line must not buzz after a cold start")
        assertEquals(SessionActivity.APPROVAL_NEEDED, old.activity, "but the activity is still right")
        assertNotNull(HookEventReducer().reduce(ev("perm", now - 59_000), now, 0).notify)
    }

    @Test fun serverClockSkewIsAppliedToTheAge() {
        // the phone is 2 min ahead of the server: an event stamped "2 min ago" by the server is fresh
        val r = HookEventReducer()
        assertNotNull(r.reduce(ev("perm", now - 120_000 - 5_000), now, skewMs = 120_000).notify)
        assertNull(HookEventReducer().reduce(ev("perm", now - 120_000 - 65_000), now, skewMs = 120_000).notify)
    }

    @Test fun replayedLinesAreDroppedOnce() {
        val r = HookEventReducer()
        assertNull(r.reduce(ev("perm", now - 10), now, 0).ignored)
        val again = r.reduce(ev("perm", now - 10), now, 0)
        assertEquals("duplicate", again.ignored)
        assertNull(again.notify)
        assertNull(r.reduce(ev("perm", now - 9), now, 0).ignored, "same event name, new millisecond: a new event")
    }

    @Test fun dedupIsAnLruOfTheLastNLines() {
        val r = HookEventReducer(capacity = 3)
        r.reduce(ev("work", 5, tmux = "s"), now, 0)
        r.reduce(ev("perm", 5, tmux = "s"), now, 0) // the same millisecond, another event
        assertEquals("duplicate", r.reduce(ev("work", 5, tmux = "s"), now, 0).ignored, "still in the LRU")
        r.reduce(ev("work", 1, tmux = "x"), now, 0)
        r.reduce(ev("work", 1, tmux = "y"), now, 0)
        assertNull(r.reduce(ev("work", 5, tmux = "s"), now, 0).ignored, "the oldest key fell out of the LRU")
    }

    @Test fun theNewestEventOfASessionIsADuplicateEvenAfterItsKeyLeftTheLru() {
        val r = HookEventReducer(capacity = 2)
        r.reduce(ev("perm", now - 100, tmux = "s"), now, 0)
        r.reduce(ev("work", 1, tmux = "x"), now, 0)
        r.reduce(ev("work", 2, tmux = "y"), now, 0)
        val again = r.reduce(ev("perm", now - 100, tmux = "s"), now, 0)
        assertEquals("duplicate", again.ignored, "the same stamp and name as the newest one of the session")
        assertNull(again.notify)
    }

    @Test fun anOlderEventNeverOverridesANewerOne() {
        val r = HookEventReducer()
        r.reduce(ev("stop", now - 100), now, 0)
        r.reduce(ev("work", now - 50), now, 0)
        val late = r.reduce(ev("perm", now - 80), now, 0)
        assertNotNull(late.ignored)
        assertNull(late.activity)
        assertNull(late.notify)
        assertNull(r.approvalSince("t1"))
    }

    @Test fun replayOfWorkThenStopInTheEventsFileEndsIdle() {
        // work at 12.3 s, the Stop at 12.9 s (the legacy marker only says "12"): events are one
        // file with ms stamps, so the order is exact and the session ends up ready.
        val r = HookEventReducer()
        val old = now - 60_000
        r.reduce(ev("work", old + 300), now, 0)
        val stop = r.reduce(ev("stop", old + 900), now, 0)
        assertEquals(SessionActivity.WAITING_FOR_INPUT, stop.activity)
    }

    @Test fun aLiveStopLeavesTheReadyFlipToTheLegacyMarker() {
        val r = HookEventReducer()
        val stop = r.reduce(ev("stop", now - 1_000), now, 0)
        assertNull(stop.activity, "flipping early would make the watch buzz with the previous answer")
    }

    @Test fun aLiveStopEndsAPendingApprovalAndTheDotLeavesApproval() {
        val r = HookEventReducer()
        r.reduce(ev("perm", now - 2_000), now, 0)
        val stop = r.reduce(ev("stop", now - 1_000), now, 0)
        assertEquals(SessionActivity.WORKING, stop.activity, "the legacy marker flips it to ready afterwards")
        assertTrue(stop.clearedApproval)
        assertNull(r.approvalSince("t1"))
    }

    @Test fun aStopWithBackgroundTasksAfterAnApprovalIsWorkingNotApproval() {
        val r = HookEventReducer()
        r.reduce(ev("ask", now - 60_000), now, 0)
        val stop = r.reduce(ev("stop", now - 59_000, extra = "bg"), now, 0)
        assertEquals(SessionActivity.WORKING, stop.activity)
        assertTrue(stop.clearedApproval)
    }

    @Test fun theLiveWindowOfAStopWidensWithTheMeasuredClockUncertainty() {
        // age 7 s: old for a perfect clock (5 s window), live when the skew is only known to +-5 s
        assertEquals(SessionActivity.WAITING_FOR_INPUT, HookEventReducer().reduce(ev("stop", now - 7_000), now, 0).activity)
        assertNull(HookEventReducer().reduce(ev("stop", now - 7_000), now, 0, skewErrMs = 5_000).activity)
    }

    @Test fun aStopWithBackgroundTasksNeverFlipsToReady() {
        val r = HookEventReducer()
        r.reduce(ev("work", now - 60_000), now, 0)
        val stop = r.reduce(ev("stop", now - 59_000, extra = "bg"), now, 0)
        assertNull(stop.activity)
    }

    @Test fun aNotificationIsSupersededByALaterEvent() {
        val r = HookEventReducer()
        val p = r.reduce(ev("perm", now - 100), now, 0)
        assertFalse(r.isSuperseded("t1", now - 100))
        r.reduce(ev("work", now - 50), now, 0)
        assertTrue(r.isSuperseded("t1", now - 100), "answered one line later in the same replay: no buzz")
        assertNotNull(p.notify)
    }

    @Test fun forgetDropsTheSessionsState() {
        val r = HookEventReducer()
        r.reduce(ev("perm", now - 100), now, 0)
        r.forget("t1")
        assertNull(r.approvalSince("t1"))
        assertFalse(r.isSuperseded("t1", 0))
    }

    @Test fun sessionsAreIndependent() {
        val r = HookEventReducer()
        r.reduce(ev("perm", now - 100, tmux = "a"), now, 0)
        r.reduce(ev("work", now - 50, tmux = "b"), now, 0)
        assertNotNull(r.approvalSince("a"))
        assertNull(r.approvalSince("b"))
    }

    // ---- notification keys ------------------------------------------------

    @Test fun hookKeysAreDebounceExemptAndStopKeysAreNot() {
        val n = HookEventReducer().reduce(ev("perm", now - 10), now, 0).notify!!
        assertTrue(NotifyKeys.isDebounceExempt(n.key), n.key)
        assertFalse(NotifyKeys.isDebounceExempt(NotifyKeys.stop("t", 12)))
        assertFalse(NotifyKeys.isDebounceExempt(null))
        assertNotNull(n.key.takeIf { it.contains("t1") && it.contains("${now - 10}") })
    }

    // ---- forget.log -------------------------------------------------------

    @Test fun forgetLinesAreParsedTolerantly() {
        val a = ForgetLine.parse("[2026-11-02T18:10:12Z] forget: claude-server-x (durable=1)")
        assertEquals("claude-server-x", a!!.name)
        assertEquals("claude-server-y", ForgetLine.parse("[2026-11-02T18:10:12Z] forget: claude-server-y (durable=0)")!!.name)
        // another writer adds free text after the flag
        assertEquals("claude-server-z", ForgetLine.parse("[2026-11-02T18:10:12Z] forget: claude-server-z (durable=1, vedení)")!!.name)
        // no suffix at all
        assertEquals("claude-server-w", ForgetLine.parse("[2026-11-02T18:10:12Z] forget: claude-server-w")!!.name)
        assertNull(ForgetLine.parse("claude-remote-ev work x 1 2"))
        assertNull(ForgetLine.parse("[not a time] forget: x (durable=1)"))
        assertNull(ForgetLine.parse("[2026-11-02T18:10:12Z] forget:  (durable=1)"))
    }

    @Test fun aForgetLineIsActedOnOnceAndOnlyWhenFresh() {
        val t = ForgetTracker()
        val e = ForgetEntry(now - 5_000, "n")
        assertTrue(t.shouldAct(e, now, 0))
        assertFalse(t.shouldAct(e, now, 0), "the 25-line replay on a reconnect must not trigger it again")
        val old = ForgetEntry(now - 3_600_000, "m")
        assertFalse(t.shouldAct(old, now, 0), "an old line is only remembered (first sighting after a restart)")
        assertFalse(t.shouldAct(old, now, 0))
        assertTrue(t.shouldAct(ForgetEntry(now - 5_000, "other"), now, 0))
        assertTrue(ForgetTracker().shouldAct(ForgetEntry(now - 10_000 - 120_000, "s"), now, skewMs = 120_000), "skew corrected")
    }
}
