package com.clauderemote.session.service

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionActivity
import com.clauderemote.model.SshServer
import com.clauderemote.session.ClaudeState
import com.clauderemote.session.PromptDetection
import com.clauderemote.session.PromptType
import com.clauderemote.session.TabManager
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Batch B, unit B4: the state of a session with v2 hooks (CAP=1) and the one-alert rule
 * for a permission dialog, through the real [NotificationService] / [ClaudeControlService]
 * with only the transport and the clock faked.
 *
 * Phone log of 2026-11-04: hook `perm` 14:26:18, the screen detector's "Potřebuje schválení"
 * for the SAME dialog 14:26:52 (+35 s) raised a second alert, because the two sources held
 * each other back for 15 s only. And after Escape (no hook arrives) the dot stayed
 * "● Working": every keystroke set WORKING and the frozen OMC `thinking` segment kept it.
 */
class HookStateNotifyTest {
    private val server = SshServer(id = "srv", name = "srv", host = "127.0.0.1", username = "u")
    private val tmux = "claude-server-b4"
    private val sid = "sess-b4"
    private val t0 = 1_800_000_000_000L
    private val staleThinking = "5h:33%(2h59m) | wk:14%(10h52m) | thinking | session:6464m | ctx:42%"

    private class Rig(val server: SshServer, val sid: String, val tmux: String, foreground: Boolean) {
        val tabs = TabManager()
        val registry = ConnectionRegistry(ServerStorage(FakeKeyValueStore()), tabs)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        @Volatile var background = !foreground
        @Volatile var now = 0L
        @Volatile var offSwitch = false
        val needs = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val status = StatusService(scope, registry, tabs, { background }, {}, {})
        val service = NotificationService(
            scope = scope, registry = registry, tabManager = tabs, isBackground = { background },
            updateActivity = { id, a -> status.updateActivity(id, a) },
            lastAssistantEntry = { null }, entriesOf = { emptyList() }, streamOrNull = { null },
            onNeedsInput = { id, hint, _, _, _ -> needs += id to hint },
            activityOf = { id -> status.currentActivity(id) },
        )
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val control = ClaudeControlService(scope, registry, tabs, status, service, TerminalIOService(registry)) { _, _ -> }

        init {
            if (foreground) {
                tabs.addTab(
                    ClaudeSession(
                        id = sid, server = server, folder = "/tmp", mode = ClaudeMode.YOLO, model = ClaudeModel.DEFAULT,
                        tmuxSessionName = tmux, connectionType = ConnectionType.SSH,
                    ),
                )
            }
            service.startNotifyWatcher(sid, tmux, server.id)
            // The tail is connected (the rig has no channel; the flag is what "hooks live" reads) and
            // the bootstrap has not reported hookev.off.
            service.setWatcherLiveForTest(server.id, true)
            service.hookOffSwitch = { offSwitch }
            control.routeOverride = { ClaudeControlService.InputRoute({ sent += it }, { sent += String(it, Charsets.ISO_8859_1) }) }
        }

        fun fakeClock(start: Long) { now = start; service.clock = { now }; service.promptDetector.outputClock = { now } }

        fun hook(ev: String, atMs: Long, extra: String = "", name: String = tmux) {
            val b64 = java.util.Base64.getEncoder()
            val line = "claude-remote-ev $ev ${b64.encodeToString(name.toByteArray())} $atMs 4242" +
                if (extra.isEmpty()) "" else " " + b64.encodeToString(extra.toByteArray())
            service.dispatchHookLineForTest(server.id, line)
        }

        fun screenApproval(seq: Long) =
            service.promptDetector.onDetection!!.invoke(PromptDetection(sid, PromptType.APPROVAL_NEEDED, seq))

        fun close() = scope.cancel()
    }

    private fun <T> rig(foreground: Boolean = false, block: (Rig) -> T): T {
        val r = Rig(server, sid, tmux, foreground)
        try { return block(r) } finally { r.close() }
    }

    private fun waitUntil(ms: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    // ---------------------------------------------------------------- B4.1 CAP=1

    @Test fun aV2HookLineMarksTheSessionCap1AndTheFrozenThinkingSegmentNoLongerMeansWorking() = rig { r ->
        r.service.promptDetector.feedRecentOutput(sid, staleThinking)
        assertEquals(true, r.service.promptDetector.parseClaudeWorking(sid), "without a hook event the statusline still decides")
        r.hook("start", System.currentTimeMillis())
        assertTrue(r.service.promptDetector.hasHookEvents(sid), "a v2 event of the session sets CAP=1")
        assertNull(r.service.promptDetector.parseClaudeWorking(sid), "CAP=1: the statusline does not drive the state")
    }

    // Round 2, R8: CAP=1 only from APPLIED and FRESH events. The `tail -n 25` replay of every
    // watcher (re)connect proves only that the hooks once worked; with hookev.off since then the
    // session would stay CAP=1 for good and work started from another client would never show.

    @Test fun cap1ComesOnlyFromAnAppliedFreshEvent() = rig { r ->
        r.hook("stop", System.currentTimeMillis(), name = "claude-server-other")
        assertFalse(r.service.promptDetector.hasHookEvents(sid), "an event of another pane says nothing about this one")
        r.hook("stop", System.currentTimeMillis() - 3_600_000)
        assertFalse(r.service.promptDetector.hasHookEvents(sid), "an hour-old line of the replay is no proof of live hooks")
        val at = System.currentTimeMillis()
        r.hook("work", at)
        assertTrue(r.service.promptDetector.hasHookEvents(sid), "a fresh event sets CAP=1")
        // closed and attached again in this process: CAP starts over, and the replay of the same
        // line is a duplicate the reducer ignores
        r.service.promptDetector.removeSession(sid)
        r.hook("work", at)
        assertFalse(r.service.promptDetector.hasHookEvents(sid), "a replayed duplicate is ignored, so it marks nothing")
        r.hook("stop", System.currentTimeMillis())
        assertTrue(r.service.promptDetector.hasHookEvents(sid))
    }

    @Test fun theBootstrapsHookevOffEndsCap1() = rig { r ->
        r.hook("work", System.currentTimeMillis())
        assertTrue(r.service.promptDetector.hasHookEvents(sid))
        r.offSwitch = true // the bootstrap read ~/.claude-remote/hookev.off
        assertFalse(r.service.promptDetector.hasHookEvents(sid), "hooks switched off: the session is CAP=0 again")
        r.status.updateActivity(sid, SessionActivity.WAITING_FOR_INPUT)
        r.control.sendEscape(sid)
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid), "CAP=0: every key sets WORKING as before")
    }

    @Test fun aTurnEndWithoutAnyV2EventForMinutesEndsCap1() = rig { r ->
        val p = r.service.promptDetector
        r.service.capStopGraceMs = 100
        val nowSec = System.currentTimeMillis() / 1000
        fun marker(epochSec: Long) = r.service.dispatchStopMarkerLineForTest(server.id, "claude-remote-notify $tmux $epochSec")
        val stale = System.currentTimeMillis() - NotificationService.CAP_STALE_MS - 1_000
        p.markHookEvents(sid, atMs = System.currentTimeMillis() - 30_000)
        marker(nowSec - 3)
        Thread.sleep(400)
        assertTrue(p.hasHookEvents(sid), "a turn end 30 s after the last v2 event (a short turn): the hooks run")
        p.removeSession(sid)
        p.markHookEvents(sid, atMs = stale)
        marker(nowSec - 2)
        r.hook("stop", System.currentTimeMillis()) // the v2 `stop` of the same turn, read just after the marker
        Thread.sleep(400)
        assertTrue(p.hasHookEvents(sid), "a long turn: the v2 `stop` arrived within the grace")
        p.removeSession(sid)
        p.markHookEvents(sid, atMs = stale)
        marker(nowSec - 1)
        waitUntil(2_000, "CAP=0 after a turn end with no v2 event for minutes") { !p.hasHookEvents(sid) }
        r.hook("work", System.currentTimeMillis())
        assertTrue(p.hasHookEvents(sid), "the next fresh v2 event sets CAP=1 again")
    }

    @Test fun escapeArrowsAndTypingDoNotSetWorkingForCap1() = rig { r ->
        r.service.promptDetector.markHookEvents(sid)
        r.status.updateActivity(sid, SessionActivity.WAITING_FOR_INPUT)
        r.control.sendEscape(sid)
        assertEquals(SessionActivity.WAITING_FOR_INPUT, r.status.currentActivity(sid), "Escape is no prompt")
        r.control.sendInput(sid, "h")
        r.control.sendBytes(sid, "\u001b[A".toByteArray())
        r.control.sendClaudeCommand(sid, "fix the build")
        assertEquals(SessionActivity.WAITING_FOR_INPUT, r.status.currentActivity(sid), "typing and arrows are no prompt")
        assertEquals(listOf("\u001b", "h", "\u001b[A", "fix the build"), r.sent.toList(), "every key still reaches the terminal")
        r.control.sendClaudeCommand(sid, "\r")
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid), "Enter submits the prompt: optimistic WORKING")
    }

    @Test fun withoutHookEventsEveryKeyStillSetsWorkingAsBefore() = rig { r ->
        r.status.updateActivity(sid, SessionActivity.WAITING_FOR_INPUT)
        r.control.sendEscape(sid)
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid), "sessions without hooks are unchanged")
    }

    @Test fun afterAnInterruptTheSilentTerminalDropsWorkingToReady() = rig { r ->
        r.service.silenceDropMs = 400
        r.service.silenceTickMs = 40
        r.service.outputLive = { true }
        r.service.promptDetector.markHookEvents(sid)
        r.control.sendInput(sid, "\r")
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid))
        // the spinner keeps printing while Claude works: no drop
        val until = System.currentTimeMillis() + 1_000
        while (System.currentTimeMillis() < until) {
            r.service.promptDetector.onOutput(sid, "\u001b[2K✻ Working… (3s · esc to interrupt)")
            Thread.sleep(60)
        }
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid), "output flows: still working")
        // Escape: Claude stops, no hook `stop` comes, the terminal goes quiet
        r.control.sendEscape(sid)
        waitUntil(3_000, "the drop to ready after silence") { r.status.currentActivity(sid) == SessionActivity.WAITING_FOR_INPUT }
    }

    @Test fun theSilenceIsCountedFromTheSubmitNotFromOlderOutput() = rig { r ->
        r.service.silenceDropMs = 1_500
        r.service.silenceTickMs = 20
        r.service.outputLive = { true }
        r.service.promptDetector.markHookEvents(sid)
        r.service.promptDetector.onOutput(sid, "an old screen")
        Thread.sleep(2_000) // the terminal was quiet before the prompt, longer than the drop
        r.control.sendInput(sid, "\r")
        Thread.sleep(300)
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid), "the quiet before Enter does not count")
        waitUntil(5_000, "the drop after 1.5 s of silence since the submit") {
            r.status.currentActivity(sid) == SessionActivity.WAITING_FOR_INPUT
        }
    }

    @Test fun aHookWorkEventAlsoArmsTheSilenceDrop() = rig { r ->
        r.service.silenceDropMs = 300
        r.service.silenceTickMs = 30
        r.service.outputLive = { true }
        r.hook("work", System.currentTimeMillis())
        waitUntil(2_000, "WORKING from the hook") { r.status.currentActivity(sid) == SessionActivity.WORKING }
        waitUntil(3_000, "the drop to ready after silence") { r.status.currentActivity(sid) == SessionActivity.WAITING_FOR_INPUT }
    }

    // Round 2: adopted from the review (testy-stav, N1). A permission dialog is a static, silent
    // screen: the silence watch must end with the WORKING it watches, never turn APPROVAL into ready.
    @Test fun silenceDoesNotEndAPendingApproval() = rig { r ->
        r.service.silenceDropMs = 300
        r.service.silenceTickMs = 30
        r.service.outputLive = { true }
        val t = System.currentTimeMillis()
        r.hook("work", t)
        waitUntil(2_000, "WORKING from the hook") { r.status.currentActivity(sid) == SessionActivity.WORKING }
        r.hook("perm", t + 50, "Bash: sleep 60")
        waitUntil(2_000, "APPROVAL from the hook") { r.status.currentActivity(sid) == SessionActivity.APPROVAL_NEEDED }
        Thread.sleep(1_200)
        assertEquals(SessionActivity.APPROVAL_NEEDED, r.status.currentActivity(sid), "a silent permission dialog is not ready")
    }

    // Round 2, R7: after a reconnect (or a frozen process) `lastOutputAt` is from before the
    // outage, and the repaint of the re-attach has not arrived yet: silence counts only from the
    // moment output could flow again.

    @Test fun afterAReconnectTheSilenceCountsFromWhenOutputCanFlowAgain() = rig { r ->
        r.service.silenceDropMs = 1_000
        r.service.silenceTickMs = 20
        r.service.silenceDeadTickMaxMs = 40
        val live = java.util.concurrent.atomic.AtomicBoolean(true)
        r.service.outputLive = { live.get() }
        r.service.promptDetector.markHookEvents(sid)
        r.control.sendInput(sid, "\r")
        live.set(false) // Starlink drop: nothing can reach the phone
        Thread.sleep(1_800)
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid))
        live.set(true)
        Thread.sleep(500)
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid), "back for 0.5 s: no silence measured yet")
        waitUntil(4_000, "the drop after 1 s of live silence") { r.status.currentActivity(sid) == SessionActivity.WAITING_FOR_INPUT }
    }

    @Test fun aProcessFrozenPastTheDropDoesNotDropOnWake() = rig { r ->
        r.service.silenceDropMs = 600
        r.service.silenceTickMs = 20
        val frozen = java.util.concurrent.atomic.AtomicBoolean(false)
        r.service.outputLive = {
            // Doze / HyperOS freeze: one tick comes 1.5 s late, the transport still says connected
            if (frozen.compareAndSet(true, false)) Thread.sleep(1_500)
            true
        }
        r.service.promptDetector.markHookEvents(sid)
        r.control.sendInput(sid, "\r")
        Thread.sleep(100)
        frozen.set(true)
        Thread.sleep(1_700) // the late tick has run by now
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid), "woken from a freeze: the output had no chance yet")
        waitUntil(3_000, "the drop after 600 ms of silence since the wake") {
            r.status.currentActivity(sid) == SessionActivity.WAITING_FOR_INPUT
        }
    }

    @Test fun aDeadTransportIsNotPolledEveryTick() = rig { r ->
        r.service.silenceDropMs = 200
        r.service.silenceTickMs = 20
        r.service.silenceDeadTickMaxMs = 30_000
        val polls = java.util.concurrent.atomic.AtomicInteger()
        r.service.outputLive = { polls.incrementAndGet(); false }
        r.service.promptDetector.markHookEvents(sid)
        r.control.sendInput(sid, "\r")
        Thread.sleep(1_500)
        assertTrue(polls.get() in 1..12, "a dead transport backs off instead of waking every ${r.service.silenceTickMs} ms: ${polls.get()} polls")
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid))
    }

    @Test fun silenceOnADeadTransportOrWithoutHooksDropsNothing() = rig { r ->
        r.service.silenceDropMs = 200
        r.service.silenceTickMs = 20
        r.service.outputLive = { false }
        r.service.promptDetector.markHookEvents(sid)
        r.control.sendInput(sid, "\r")
        Thread.sleep(800)
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid), "no output can reach us: silence says nothing")
        val other = "sess-nohooks"
        r.service.outputLive = { true }
        r.control.sendInput(other, "\r")
        Thread.sleep(800)
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(other), "without hooks the 45 s UI decay stays the only one")
    }

    // No hook fires when a permission dialog is answered, and Escape there (deny) ends the turn
    // without a `stop`: a key that answers the dialog from this app must leave APPROVAL, or the
    // dot says "approval" until the next prompt.

    @Test fun escapeAtAHookApprovalLeavesItAndTheSilentTerminalDropsToReady() = rig { r ->
        r.service.silenceDropMs = 300
        r.service.silenceTickMs = 30
        r.service.outputLive = { true }
        r.hook("perm", System.currentTimeMillis(), "Bash: sleep 60")
        waitUntil(2_000, "APPROVAL from the hook") { r.status.currentActivity(sid) == SessionActivity.APPROVAL_NEEDED }
        r.control.sendBytes(sid, "\u001b[B".toByteArray())
        Thread.sleep(500)
        assertEquals(SessionActivity.APPROVAL_NEEDED, r.status.currentActivity(sid), "an arrow only moves the selection")
        r.control.sendEscape(sid)
        waitUntil(3_000, "ready after the denied dialog went quiet") {
            r.status.currentActivity(sid) == SessionActivity.WAITING_FOR_INPUT
        }
    }

    @Test fun aDigitAnsweringAHookApprovalSetsWorking() = rig { r ->
        r.hook("perm", System.currentTimeMillis(), "Bash: sleep 60")
        waitUntil(2_000, "APPROVAL from the hook") { r.status.currentActivity(sid) == SessionActivity.APPROVAL_NEEDED }
        r.control.sendInput(sid, "1")
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid), "1 = Yes: the tool runs")
    }

    // Round 2 (low): a newline inside pasted text, or Shift+Enter, is no submit; at a dialog an
    // escape SEQUENCE the terminal generates (OSC reply, focus report) is no answer.

    @Test fun aNewlineInsidePastedTextOrShiftEnterIsNoSubmit() = rig { r ->
        r.service.promptDetector.markHookEvents(sid)
        r.status.updateActivity(sid, SessionActivity.WAITING_FOR_INPUT)
        r.control.sendBytes(sid, "\u001b[200~line one\r\nline two\u001b[201~".toByteArray())
        r.control.sendInput(sid, "line\n")
        r.control.sendBytes(sid, "\u001b\r".toByteArray())
        assertEquals(SessionActivity.WAITING_FOR_INPUT, r.status.currentActivity(sid), "the draft grows, nothing is submitted")
        r.control.sendInput(sid, "\r")
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid), "Enter submits")
    }

    @Test fun aTerminalEscapeSequenceAtADialogIsNoAnswer() = rig { r ->
        r.hook("perm", System.currentTimeMillis(), "Bash: sleep 60")
        waitUntil(2_000, "APPROVAL from the hook") { r.status.currentActivity(sid) == SessionActivity.APPROVAL_NEEDED }
        r.control.sendBytes(sid, "\u001b]11;rgb:0000/0000/0000\u0007".toByteArray())
        r.control.sendBytes(sid, "\u001b[I".toByteArray())
        r.control.sendBytes(sid, "\u001bP>|xterm\u001b\\".toByteArray())
        assertEquals(SessionActivity.APPROVAL_NEEDED, r.status.currentActivity(sid), "terminal replies answer nothing")
        r.control.sendInput(sid, "y")
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid), "a printable key does")
    }

    // ---------------------------------------------------------------- B4.2 one alert

    @Test fun theScreenDetectorDoesNotAlertAgain35sAfterTheHookAnnouncedTheSameDialog() = rig { r ->
        r.fakeClock(t0)
        r.hook("perm", t0, "Bash: mkdir test")
        waitUntil(3_000, "the hook alert") { r.needs.size == 1 }
        assertEquals(PromptType.PERMISSION_PROMPT.displayHint, r.needs[0].second)
        r.now = t0 + 35_000
        r.screenApproval(1)
        Thread.sleep(100)
        assertEquals(1, r.needs.size, "the screen detector's sighting of the pending hook dialog is the same dialog: ${r.needs}")
        assertEquals(SessionActivity.APPROVAL_NEEDED, r.status.currentActivity(sid), "the dot still says approval")
    }

    @Test fun theNextHookEventReleasesTheSuppression() = rig { r ->
        r.fakeClock(t0)
        r.hook("perm", t0, "Bash: mkdir test")
        waitUntil(3_000, "the hook alert") { r.needs.size == 1 }
        r.now = t0 + 40_000
        r.hook("work", t0 + 40_000)
        assertNull(r.service.hookApprovalSince(server.id, tmux))
        r.now = t0 + 60_000
        r.screenApproval(2) // a dialog without a hook (trust, /login) in the new turn
        assertEquals(2, r.needs.size, "after the answer the screen detector alerts again: ${r.needs}")
    }

    @Test fun aStopAlsoReleasesIt() = rig { r ->
        r.fakeClock(t0)
        r.hook("perm", t0, "Bash: mkdir test")
        waitUntil(3_000, "the hook alert") { r.needs.size == 1 }
        r.now = t0 + 20_000
        r.hook("stop", t0 + 20_000)
        r.now = t0 + 60_000
        r.screenApproval(2)
        assertEquals(2, r.needs.size, "${r.needs}")
    }

    @Test fun aNewRequestAfterTheAnswerAlertsAgainAndItsScreenSightingIsHeldBack() = rig { r ->
        r.fakeClock(t0)
        r.hook("perm", t0, "Bash: one")
        waitUntil(3_000, "first alert") { r.needs.size == 1 }
        r.now = t0 + 10_000
        r.hook("work", t0 + 10_000)
        r.now = t0 + 20_000
        r.hook("perm", t0 + 20_000, "Bash: two")
        waitUntil(3_000, "second alert") { r.needs.size == 2 }
        r.now = t0 + 55_000
        r.screenApproval(3)
        assertEquals(2, r.needs.size, "${r.needs}")
    }

    @Test fun theOppositeOrderKeepsThe15sWindow() = rig { r ->
        r.fakeClock(t0)
        r.screenApproval(1)
        assertEquals(1, r.needs.size)
        r.now = t0 + 2_000
        r.hook("perm", t0 + 2_000, "Bash: mkdir test")
        Thread.sleep(600)
        assertEquals(1, r.needs.size, "the hook after the screen detector within 15 s: the same dialog")
    }

    @Test fun aHookApprovalThatWasNeverAnnouncedDoesNotSilenceTheScreen() = rig(foreground = true) { r ->
        // The active tab in the foreground: the hook path leaves the alert to the screen detector.
        r.fakeClock(t0)
        r.hook("perm", t0, "Bash: mkdir test")
        Thread.sleep(600)
        assertEquals(0, r.needs.size)
        r.now = t0 + 35_000
        r.screenApproval(1)
        assertEquals(1, r.needs.size, "exactly one alert, from the screen")
    }

    // Round 2, R6: the hold behind an announced hook approval must never silence a real new
    // request. Allow / deny emit no hook, so the reducer keeps the old approval pending until the
    // next hook event, which never comes with hookev.off or without the watcher.

    @Test fun afterTheAnswerHookevOffLetsTheNextDialogAlert() = rig { r ->
        r.fakeClock(t0)
        r.hook("perm", t0, "Bash: one")
        waitUntil(3_000, "the hook alert") { r.needs.size == 1 }
        // answered in another client (no hook), then the owner touches ~/.claude-remote/hookev.off
        r.offSwitch = true
        r.now = t0 + 40_000
        r.screenApproval(1) // the next dialog: no hook reports it any more
        assertEquals(2, r.needs.size, "the new request alerts: ${r.needs}")
    }

    @Test fun losingTheWatcherLetsTheNextDialogAlert() = rig { r ->
        r.fakeClock(t0)
        r.hook("perm", t0, "Bash: one")
        waitUntil(3_000, "the hook alert") { r.needs.size == 1 }
        r.service.setWatcherLiveForTest(server.id, false)
        r.now = t0 + 40_000
        r.screenApproval(1)
        assertEquals(2, r.needs.size, "no live hook path, no hold: ${r.needs}")
    }

    @Test fun theHoldHasAnUpperBound() = rig { r ->
        r.fakeClock(t0)
        r.hook("perm", t0, "Bash: one")
        waitUntil(3_000, "the hook alert") { r.needs.size == 1 }
        r.now = t0 + 110_000
        r.screenApproval(1)
        assertEquals(1, r.needs.size, "within HOLD_MAX_MS the same dialog: ${r.needs}")
        r.now = t0 + PromptDialogDedup.HOLD_MAX_MS + 1_000
        r.screenApproval(2)
        assertEquals(2, r.needs.size, "past HOLD_MAX_MS: ${r.needs}")
    }

    @Test fun answeringFromThisAppEndsTheHold() = rig { r ->
        r.fakeClock(t0)
        r.hook("perm", t0, "Bash: one")
        waitUntil(3_000, "the hook alert") { r.needs.size == 1 }
        r.control.sendInput(sid, "1") // Yes: no hook reports the answer
        r.now = t0 + 40_000
        r.screenApproval(1) // a later dialog (e.g. trust) the hook does not report
        assertEquals(2, r.needs.size, "${r.needs}")
    }

    @Test fun aScreenWithoutTheDialogEndsTheHold() = rig { r ->
        r.fakeClock(t0)
        r.hook("perm", t0, "Bash: one")
        waitUntil(3_000, "the hook alert") { r.needs.size == 1 }
        // answered on the desktop: output arrives and the classifier sees the input box again
        r.now = t0 + 10_000
        r.service.promptDetector.onOutput(sid, "\u001b[2K> ")
        r.service.promptDetector.onScreenSeen!!.invoke(sid, ClaudeState.IDLE)
        r.now = t0 + 40_000
        r.screenApproval(1)
        assertEquals(2, r.needs.size, "${r.needs}")
    }

    @Test fun anUnknownScreenKeepsTheHold() = rig { r ->
        r.fakeClock(t0)
        r.hook("perm", t0, "Bash: one")
        waitUntil(3_000, "the hook alert") { r.needs.size == 1 }
        r.now = t0 + 10_000
        // Output after the announcement (round 4, testy-stav T1): an IDLE or WORKING screen would
        // end the hold now, so the guard sees the UNKNOWN branch and not the stale-screen rule.
        r.service.promptDetector.onOutput(sid, "\u001b[2K garbled")
        r.service.promptDetector.onScreenSeen?.invoke(sid, ClaudeState.UNKNOWN)
        r.now = t0 + 40_000
        r.screenApproval(1)
        assertEquals(1, r.needs.size, "a screen the classifier cannot read says nothing about the dialog: ${r.needs}")
    }

    // ---- Round 3

    // adv-stav N5: the tab's stream lags the hook by more than the 5 s grace, so the classifier,
    // read 10 s after the announcement, still sees the spinner from before the dialog. Taking that
    // for "the dialog is gone" ended the hold, and the late sighting of the SAME dialog alerted again.
    @Test fun aScreenOlderThanTheDialogDoesNotEndTheHold() = rig { r ->
        r.fakeClock(t0 - 2_000)
        r.service.promptDetector.onOutput(sid, "\u001b[2K✻ Working… (3s · esc to interrupt)")
        r.now = t0
        r.hook("perm", t0, "Bash: one")
        waitUntil(3_000, "the hook alert") { r.needs.size == 1 }
        r.now = t0 + 3_000
        r.service.promptDetector.onOutput(sid, "\u001b[2K✻ Working… (6s · esc to interrupt)") // still before the drawing
        r.now = t0 + 10_000
        r.service.promptDetector.onScreenSeen!!.invoke(sid, ClaudeState.WORKING)
        r.now = t0 + 35_000
        r.screenApproval(1) // the same dialog, drawn at last
        assertEquals(1, r.needs.size, "one dialog, one alert: ${r.needs}")
    }

    @Test fun onceTheScreenShowedTheDialogItsAbsenceEndsTheHold() = rig { r ->
        r.fakeClock(t0)
        r.hook("perm", t0, "Bash: one")
        waitUntil(3_000, "the hook alert") { r.needs.size == 1 }
        r.now = t0 + 2_000
        r.service.promptDetector.onScreenSeen!!.invoke(sid, ClaudeState.APPROVAL) // drawn
        r.now = t0 + 3_000
        r.service.promptDetector.onScreenSeen!!.invoke(sid, ClaudeState.IDLE) // answered on the desktop at once
        r.now = t0 + 40_000
        r.screenApproval(1) // a later dialog the hook does not report
        assertEquals(2, r.needs.size, "${r.needs}")
    }

    // kod-stav L1: `$` matched before the final CR, so a paste split across sends whose first
    // part ends in CR counted as a submit.
    @Test fun aSplitPasteEndingInACarriageReturnIsNoSubmit() = rig { r ->
        r.service.promptDetector.markHookEvents(sid)
        r.status.updateActivity(sid, SessionActivity.WAITING_FOR_INPUT)
        r.control.sendInput(sid, "\u001b[200~abc\r")
        r.control.sendInput(sid, "\u001b[200~a\rb\r\n")
        assertEquals(SessionActivity.WAITING_FOR_INPUT, r.status.currentActivity(sid), "pasted text, nothing submitted")
        r.control.sendInput(sid, "\u001b[200~abc\u001b[201~\r")
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid), "Enter after the paste submits")
    }

    // kod-stav L3: the hook announced the dialog, but the session is CAP=0 (the event was too old
    // for CAP, or CAP ended since). An answer from this app still ends the hold.
    @Test fun answeringFromThisAppEndsTheHoldAlsoWithoutCap1() = rig { r ->
        r.fakeClock(t0)
        r.hook("perm", t0, "Bash: one")
        waitUntil(3_000, "the hook alert") { r.needs.size == 1 }
        waitUntil(3_000, "the approval") { r.status.currentActivity(sid) == SessionActivity.APPROVAL_NEEDED }
        r.service.promptDetector.clearHookEvents(sid)
        r.control.sendInput(sid, "1")
        r.now = t0 + 40_000
        r.screenApproval(1)
        assertEquals(2, r.needs.size, "${r.needs}")
    }

    // testy-stav F1/F2: the review's probes, kept.
    @Test fun theTickReturnsToNormalAfterAReconnect() = rig { r ->
        r.service.silenceDropMs = 300
        r.service.silenceTickMs = 20
        r.service.silenceDeadTickMaxMs = 3_000
        val live = java.util.concurrent.atomic.AtomicBoolean(true)
        val firstLivePoll = java.util.concurrent.atomic.AtomicLong(0)
        r.service.outputLive = {
            val l = live.get()
            if (l && firstLivePoll.get() == 0L) firstLivePoll.compareAndSet(0, System.currentTimeMillis())
            l
        }
        r.service.promptDetector.markHookEvents(sid)
        r.control.sendInput(sid, "\r")
        live.set(false)
        Thread.sleep(4_000) // the dead-transport backoff has grown to seconds
        firstLivePoll.set(0)
        live.set(true)
        waitUntil(8_000, "the drop after the reconnect") { r.status.currentActivity(sid) == SessionActivity.WAITING_FOR_INPUT }
        val after = System.currentTimeMillis() - firstLivePoll.get()
        assertTrue(after < 1_200, "back on the normal tick after the first live poll, drop 300 ms later; took $after ms")
    }

    @Test fun cap1IsStampedWithTheEventTimeNotTheReadTime() = rig { r ->
        val at = System.currentTimeMillis() - 50_000
        r.hook("work", at)
        val stamp = r.service.promptDetector.hookEventAtMs(sid)
        assertNotNull(stamp, "a 50 s old applied event sets CAP=1")
        assertTrue(kotlin.math.abs(stamp - at) < 5_000, "stamped with the event time ($at), not the read time: $stamp")
    }
}
