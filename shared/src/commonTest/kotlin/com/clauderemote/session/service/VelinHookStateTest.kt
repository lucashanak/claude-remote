package com.clauderemote.session.service

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionActivity
import com.clauderemote.model.SshServer
import com.clauderemote.session.TabManager
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the Velín reads from the hook path, through the real [NotificationService] and
 * [StatusService]: the text of a `perm` / `ask` next to its prompt identity, the `end` of a
 * Claude that exited, and when a session's activity last changed.
 */
class VelinHookStateTest {
    private val server = SshServer(id = "srv", name = "srv", host = "127.0.0.1", username = "u")
    private val tmux = "claude-server-velin"
    private val sid = "sess-velin"

    private inner class Rig {
        val tabs = TabManager()
        val registry = ConnectionRegistry(ServerStorage(FakeKeyValueStore()), tabs)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val status = StatusService(scope, registry, tabs, { true }, {}, {})
        val service = NotificationService(
            scope = scope, registry = registry, tabManager = tabs, isBackground = { true },
            updateActivity = { id, a -> status.updateActivity(id, a) },
            lastAssistantEntry = { null }, entriesOf = { emptyList() }, streamOrNull = { null },
            onNeedsInput = { _, _, _, _, _, _ -> },
            activityOf = { id -> status.currentActivity(id) },
            promptTokens = status.promptTokens,
            onClaudeEnded = { id, at -> status.markClaudeEnded(id, at) },
        )

        init {
            tabs.addTab(
                ClaudeSession(
                    id = sid, server = server, folder = "/tmp", mode = ClaudeMode.YOLO, model = ClaudeModel.DEFAULT,
                    tmuxSessionName = tmux, connectionType = ConnectionType.SSH,
                ),
            )
            service.startNotifyWatcher(sid, tmux, server.id)
            service.setWatcherLiveForTest(server.id, true)
        }

        fun hook(ev: String, atMs: Long, extra: String = "") {
            val b64 = java.util.Base64.getEncoder()
            val line = "claude-remote-ev $ev ${b64.encodeToString(tmux.toByteArray())} $atMs 4242" +
                if (extra.isEmpty()) "" else " " + b64.encodeToString(extra.toByteArray())
            service.dispatchHookLineForTest(server.id, line)
        }
    }

    private fun rig(block: (Rig) -> Unit) {
        val r = Rig()
        try { block(r) } finally { r.scope.cancel() }
    }

    private fun waitUntil(ms: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun aPermissionIsPublishedWithItsTextAndGoesAwayWhenAnswered() = rig { r ->
        val t = System.currentTimeMillis()
        r.hook("perm", t, "Bash: git push --force origin main")
        waitUntil(3_000, "the published prompt") { r.status.promptTokens.shown.value[sid] != null }
        val shown = r.status.promptTokens.shown.value[sid]!!
        assertEquals(PromptKind.PERMISSION, shown.current.prompt.kind)
        assertEquals("Bash: git push --force origin main", shown.current.text)
        assertEquals("perm" to t, shown.current.hookEvent(), "the server can verify a reply to it")
        assertTrue(kotlin.math.abs(shown.atMs - t) < 2_000, "stamped with when the hook fired")

        r.hook("work", t + 1_000)
        waitUntil(3_000, "the prompt gone") { r.status.promptTokens.shown.value[sid] == null }
        assertNull(r.status.promptTokens.current(sid))
    }

    @Test fun aQuestionCarriesItsQuestionAndOptionLabels() = rig { r ->
        val t = System.currentTimeMillis()
        r.hook("ask", t, "Dělit segmenty i podle čárek? [Ano, Ne, jen věty]")
        waitUntil(3_000, "the published question") { r.status.promptTokens.shown.value[sid] != null }
        val c = r.status.promptTokens.shown.value[sid]!!.current
        assertEquals(PromptKind.QUESTION, c.prompt.kind)
        assertEquals("Dělit segmenty i podle čárek? [Ano, Ne, jen věty]", c.text)
    }

    @Test fun anEndMarksClaudeExitedAndAnyLaterEventClearsIt() = rig { r ->
        val t = System.currentTimeMillis()
        r.hook("work", t)
        r.hook("end", t + 1_000)
        waitUntil(3_000, "the end noted") { r.status.claudeEndedAt.value[sid] != null }
        assertEquals(SessionActivity.IDLE, r.status.currentActivity(sid))
        r.hook("start", t + 2_000)
        waitUntil(3_000, "the start clearing it") { r.status.claudeEndedAt.value[sid] == null }

        r.hook("end", t + 3_000)
        waitUntil(3_000, "the second end") { r.status.claudeEndedAt.value[sid] != null }
        r.hook("work", t + 4_000)
        waitUntil(3_000, "work clearing it") { r.status.claudeEndedAt.value[sid] == null }
    }

    @Test fun anIgnoredReplayOfAnEndChangesNothing() = rig { r ->
        val t = System.currentTimeMillis()
        r.hook("end", t)
        r.hook("start", t + 1_000)
        waitUntil(3_000, "the start") { r.status.currentActivity(sid) == SessionActivity.WAITING_FOR_INPUT }
        // the watcher replays the tail on reconnect: the old end is older than the newest event
        r.hook("end", t)
        Thread.sleep(200)
        assertNull(r.status.claudeEndedAt.value[sid])
    }

    @Test fun activitySinceMovesOnlyWhenTheActivityChanges() = rig { r ->
        r.status.updateActivity(sid, SessionActivity.WORKING)
        val first = assertNotNull(r.status.activitySinceMs(sid))
        Thread.sleep(15)
        r.status.updateActivity(sid, SessionActivity.WORKING)
        assertEquals(first, r.status.activitySinceMs(sid), "the same state again keeps its start")
        Thread.sleep(15)
        r.status.updateActivity(sid, SessionActivity.WAITING_FOR_INPUT)
        assertTrue(r.status.activitySinceMs(sid)!! > first)
        r.status.clearActivity(sid)
        assertNull(r.status.activitySinceMs(sid))
    }
}
