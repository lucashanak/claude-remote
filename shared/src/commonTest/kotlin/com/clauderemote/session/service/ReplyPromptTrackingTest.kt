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
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The prompt identity a notification carries and the session's current prompt, through the real
 * [NotificationService] / [StatusService] / [ClaudeControlService] with only the transport faked:
 * notification for permission A, A answered elsewhere, the session now asks for B — a reply
 * written for A must not be sent.
 */
class ReplyPromptTrackingTest {
    private val server = SshServer(id = "srv", name = "srv", host = "127.0.0.1", username = "u")
    private val tmux = "claude-server-reply"
    private val sid = "sess-reply"

    private class Rig(server: SshServer, val sid: String, val tmux: String) {
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
            notePrompt = { id, p -> status.promptTokens.note(id, p) },
        )
        val control = ClaudeControlService(scope, registry, tabs, status, service, TerminalIOService(registry)) { _, _ -> }
        @Volatile var connected = true
        val server = server

        init {
            service.startNotifyWatcher(sid, tmux, server.id)
            service.setWatcherLiveForTest(server.id, true)
            control.routeOverride = { if (connected) ClaudeControlService.InputRoute({}, {}) else null }
        }

        fun hook(ev: String, atMs: Long, extra: String = "") {
            val b64 = java.util.Base64.getEncoder()
            val line = "claude-remote-ev $ev ${b64.encodeToString(tmux.toByteArray())} $atMs 4242" +
                if (extra.isEmpty()) "" else " " + b64.encodeToString(extra.toByteArray())
            service.dispatchHookLineForTest(server.id, line)
        }

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
        assertEquals(PromptKind.DIALOG, a?.kind, "the alert names its dialog")
        assertEquals(Verdict.Send, r.check(a, Reply.APPROVE), "A is still open")

        // A answered on another device, the tool ran, and the next dialog opened
        r.hook("work", t + 1_000)
        waitUntil(3_000, "the session at work") { r.status.currentActivity(sid) == SessionActivity.WORKING }
        assertNull(r.status.promptTokens.current(sid), "back at work: the answered dialog is forgotten")
        assertIs<Verdict.Refuse>(r.check(a, Reply.APPROVE))

        r.hook("perm", t + 2_000, "git push --force")
        waitUntil(3_000, "the alert for B") { r.alerts.size == 2 }
        val b = r.alerts[1]
        assertIs<Verdict.Refuse>(r.check(a, Reply.APPROVE), "Ano for A must not approve B")
        assertIs<Verdict.Refuse>(r.check(a, Reply.TEXT), "nor a typed 1")
        assertEquals(Verdict.Send, r.check(b, Reply.APPROVE))
    }

    @Test fun aNewDialogReplacesThePromptEvenWithoutAWorkEventBetween() = rig { r ->
        val t = System.currentTimeMillis()
        r.hook("perm", t, "cmd A")
        waitUntil(3_000, "the alert for A") { r.alerts.size == 1 }
        r.hook("perm", t + 500, "cmd B")
        waitUntil(3_000, "B noted") { r.status.promptTokens.current(sid)?.key?.endsWith("#${t + 500}") == true }
        assertIs<Verdict.Refuse>(r.check(r.alerts[0], Reply.APPROVE))
    }

    @Test fun theScreenSightingOfTheAnnouncedDialogKeepsItsIdentity() = rig { r ->
        val t = System.currentTimeMillis()
        r.hook("perm", t, "cmd A")
        waitUntil(3_000, "the alert for A") { r.alerts.size == 1 }
        // the screen detector reads the same dialog: held back as the same dialog, so the hook's
        // alert stays answerable
        r.service.promptDetector.onDetection!!.invoke(PromptDetection(sid, PromptType.APPROVAL_NEEDED, 7))
        Thread.sleep(100)
        assertEquals(1, r.alerts.size, "one dialog, one alert")
        assertEquals(Verdict.Send, r.check(r.alerts[0], Reply.APPROVE))
    }

    @Test fun aReplyToACompletionGoesToTheIdleSessionButNotToADialogOrWhileOffline() = rig { r ->
        val t = System.currentTimeMillis()
        val ready = NotifyKeys.prompt(NotifyKeys.stop(tmux, t / 1000))!!
        r.status.promptTokens.note(sid, ready)
        r.status.updateActivity(sid, SessionActivity.WAITING_FOR_INPUT)
        assertEquals(Verdict.Send, r.check(ready, Reply.TEXT))
        r.connected = false
        assertIs<Verdict.Refuse>(r.check(ready, Reply.TEXT), "offline: it would be queued for a later state")
        r.connected = true
        r.hook("perm", t, "cmd C")
        waitUntil(3_000, "the dialog") { r.status.currentActivity(sid) == SessionActivity.APPROVAL_NEEDED }
        assertIs<Verdict.Refuse>(r.check(ready, Reply.TEXT), "the text must not land in the selector")
    }
}
