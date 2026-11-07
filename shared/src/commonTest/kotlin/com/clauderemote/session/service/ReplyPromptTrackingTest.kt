package com.clauderemote.session.service

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionActivity
import com.clauderemote.model.SshServer
import com.clauderemote.session.PromptDetection
import com.clauderemote.session.PromptType
import com.clauderemote.session.TabManager
import com.clauderemote.session.service.ReplyGuard.Reply
import com.clauderemote.session.service.ReplyGuard.Verdict
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The prompt identity a notification carries and the session's current prompt, through the real
 * [NotificationService] / [StatusService] / [ClaudeControlService] with only the transport and
 * the server exec faked: notification for permission A, A answered elsewhere, the session now
 * asks for B — a reply written for A must not be sent.
 */
class ReplyPromptTrackingTest {
    private val server = SshServer(id = "srv", name = "srv", host = "127.0.0.1", username = "u")
    private val tmux = "claude-server-reply"
    private val sid = "sess-reply"

    private class Rig(val server: SshServer, val sid: String, val tmux: String) {
        val tabs = TabManager()
        val registry = ConnectionRegistry(ServerStorage(FakeKeyValueStore()), tabs)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val alerts = Collections.synchronizedList(mutableListOf<PromptFingerprint?>())
        val status = StatusService(scope, registry, tabs, { true }, {}, {})
        val service = NotificationService(
            scope = scope, registry = registry, tabManager = tabs, isBackground = { true },
            updateActivity = { id, a -> status.updateActivity(id, a) },
            lastAssistantEntry = { null }, entriesOf = { emptyList() }, streamOrNull = { null },
            onNeedsInput = { _, _, _, _, _, prompt -> alerts += prompt },
            activityOf = { id -> status.currentActivity(id) },
            promptTokens = status.promptTokens,
        )
        val control = ClaudeControlService(scope, registry, tabs, status, service, TerminalIOService(registry)) { _, _ -> }
        @Volatile var connected = true
        @Volatile var now = 0L
        val execs = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var serverSays: String? = "CR_REPLY SENT"

        init {
            tabs.addTab(
                ClaudeSession(
                    id = sid, server = server, folder = "/tmp", mode = ClaudeMode.YOLO, model = ClaudeModel.DEFAULT,
                    tmuxSessionName = tmux, connectionType = ConnectionType.SSH,
                ),
            )
            service.startNotifyWatcher(sid, tmux, server.id)
            service.setWatcherLiveForTest(server.id, true)
            control.routeOverride = { if (connected) ClaudeControlService.InputRoute({}, {}) else null }
            control.replyExecOverride = { _, cmd -> execs += cmd; serverSays }
        }

        fun fakeClock(start: Long) { now = start; service.clock = { now }; service.promptDetector.outputClock = { now } }

        fun hook(ev: String, atMs: Long, extra: String = "") {
            val b64 = java.util.Base64.getEncoder()
            val line = "claude-remote-ev $ev ${b64.encodeToString(tmux.toByteArray())} $atMs 4242" +
                if (extra.isEmpty()) "" else " " + b64.encodeToString(extra.toByteArray())
            service.dispatchHookLineForTest(server.id, line)
        }

        fun screen(seq: Long) = service.promptDetector.onDetection!!.invoke(PromptDetection(sid, PromptType.APPROVAL_NEEDED, seq))

        fun check(notified: PromptFingerprint?, reply: Reply) = ReplyGuard.check(
            notified, status.promptTokens.current(sid), status.currentActivity(sid), control.canDeliverNow(sid), reply,
        )

        fun close() = scope.cancel()
    }

    private fun rig(block: (Rig) -> Unit) {
        val r = Rig(server, sid, tmux)
        try { block(r) } finally { r.close() }
    }

    private fun waitUntil(ms: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun aReplyForPermissionAIsRefusedOnceTheSessionAsksForB() = rig { r ->
        val t = System.currentTimeMillis()
        r.hook("perm", t, "rm -rf build")
        waitUntil(3_000, "the alert for A") { r.alerts.size == 1 }
        val a = r.alerts[0]
        assertEquals(PromptKind.PERMISSION, a?.kind, "the alert names its dialog")
        assertEquals(Verdict.Send(ServerExpectation.Dialog("perm", t)), r.check(a, Reply.APPROVE), "A is still open")

        // A answered on another device, the tool ran, and the next dialog opened
        r.hook("work", t + 1_000)
        waitUntil(3_000, "the session at work") { r.status.currentActivity(sid) == SessionActivity.WORKING }
        assertNull(r.status.promptTokens.current(sid), "back at work: the answered dialog is forgotten")
        assertIs<Verdict.Refuse>(r.check(a, Reply.APPROVE))

        r.hook("perm", t + 2_000, "git push --force")
        waitUntil(3_000, "the alert for B") { r.alerts.size == 2 }
        val b = r.alerts[1]
        assertIs<Verdict.Refuse>(r.check(a, Reply.APPROVE), "Ano for A must not approve B")
        assertEquals(Verdict.Send(ServerExpectation.Dialog("perm", t + 2_000)), r.check(b, Reply.APPROVE))
    }

    @Test fun aNewDialogReplacesThePromptEvenWithoutAWorkEventBetween() = rig { r ->
        val t = System.currentTimeMillis()
        r.hook("perm", t, "cmd A")
        waitUntil(3_000, "the alert for A") { r.alerts.size == 1 }
        r.hook("perm", t + 500, "cmd B")
        waitUntil(3_000, "B noted") { r.status.promptTokens.current(sid)?.prompt?.key?.endsWith("#${t + 500}") == true }
        assertIs<Verdict.Refuse>(r.check(r.alerts[0], Reply.APPROVE))
    }

    @Test fun hookFirstTheScreenSightingOfTheAnnouncedDialogKeepsItsIdentity() = rig { r ->
        val t = System.currentTimeMillis()
        r.hook("perm", t, "cmd A")
        waitUntil(3_000, "the alert for A") { r.alerts.size == 1 }
        r.screen(7)
        Thread.sleep(100)
        assertEquals(1, r.alerts.size, "one dialog, one alert")
        assertIs<Verdict.Send>(r.check(r.alerts[0], Reply.APPROVE))
    }

    @Test fun screenFirstTheLateHookOfTheSameDialogKeepsTheScreenAlertAnswerable() = rig { r ->
        r.fakeClock(1_800_000_000_000L)
        val seen = r.now
        r.screen(7)
        waitUntil(3_000, "the screen alert") { r.alerts.size == 1 }
        val d = r.alerts[0]
        assertEquals(PromptKind.PERMISSION, d?.kind)
        // its hook fired before the screen saw the dialog, but its line reaches the phone later
        r.now = seen + 2_000
        r.hook("perm", seen - 300, "cmd A")
        waitUntil(3_000, "the hook noted") { r.status.promptTokens.current(sid)?.prompt?.key?.startsWith("hook#") == true }
        Thread.sleep(600) // the settle delay and the dedup decision
        assertEquals(1, r.alerts.size, "one dialog, one alert")
        assertEquals(Verdict.Send(ServerExpectation.Dialog("perm", seen - 300)), r.check(d, Reply.APPROVE))
    }

    @Test fun screenFirstAHookThatFiredLaterIsANewDialogEvenInsideTheDedupWindow() = rig { r ->
        r.fakeClock(1_800_000_000_000L)
        val seen = r.now
        r.screen(7)
        waitUntil(3_000, "the screen alert") { r.alerts.size == 1 }
        // A answered at the desktop (no event); B's hook fires 4 s later, inside the 15 s window
        r.now = seen + 4_000
        r.hook("perm", seen + 4_000, "cmd B")
        waitUntil(3_000, "B noted") { r.status.promptTokens.current(sid)?.prompt?.key?.startsWith("hook#") == true }
        Thread.sleep(600)
        assertEquals(RefuseReason.PROMPT_CHANGED, (r.check(r.alerts[0], Reply.APPROVE) as Verdict.Refuse).reason)
    }

    @Test fun freeTextForAPermissionAlertIsRefusedWithTheYesNoMessage() = rig { r ->
        r.hook("perm", System.currentTimeMillis(), "cmd A")
        waitUntil(3_000, "the alert") { r.alerts.size == 1 }
        assertEquals(RefuseReason.YES_NO_ONLY, (r.check(r.alerts[0], Reply.TEXT) as Verdict.Refuse).reason)
    }

    @Test fun aReplyToACompletionGoesToTheIdleSessionButNotToADialogOrWhileOffline() = rig { r ->
        val t = System.currentTimeMillis()
        val ready = NotifyKeys.prompt(NotifyKeys.stop(tmux, t / 1000))!!
        r.status.promptTokens.note(sid, ready)
        r.status.updateActivity(sid, SessionActivity.WAITING_FOR_INPUT)
        assertEquals(Verdict.Send(ServerExpectation.Ready(t / 1000)), r.check(ready, Reply.TEXT))
        r.connected = false
        assertEquals(RefuseReason.NOT_CONNECTED, (r.check(ready, Reply.TEXT) as Verdict.Refuse).reason)
        r.connected = true
        r.hook("perm", t, "cmd C")
        waitUntil(3_000, "the dialog") { r.status.currentActivity(sid) == SessionActivity.APPROVAL_NEEDED }
        assertIs<Verdict.Refuse>(r.check(ready, Reply.TEXT), "the text must not land in the selector")
    }

    @Test fun theCheckedSendRunsOneExecAndActsOnlyOnSent() = rig { r ->
        r.status.updateActivity(sid, SessionActivity.WAITING_FOR_INPUT)
        r.serverSays = "CR_REPLY STALE"
        val stale = runBlocking { r.control.sendCheckedReply(sid, ServerExpectation.Idle, "ahoj") }
        assertEquals(RefuseReason.PROMPT_CHANGED, stale)
        assertEquals(SessionActivity.WAITING_FOR_INPUT, r.status.currentActivity(sid), "nothing sent, nothing changes")
        r.serverSays = null
        assertEquals(RefuseReason.NOT_VERIFIABLE, runBlocking { r.control.sendCheckedReply(sid, ServerExpectation.Idle, "ahoj") }, "a failed exec")
        r.serverSays = "CR_REPLY SENT"
        assertNull(runBlocking { r.control.sendCheckedReply(sid, ServerExpectation.Idle, "ahoj") })
        assertEquals(SessionActivity.WORKING, r.status.currentActivity(sid), "a submitted reply starts work")
        assertEquals(3, r.execs.size)
        assertTrue(r.execs.all { "'=$tmux:'" in it && "'ahoj'" in it }, "one exec carries both the check and the keys")
    }
}
