package com.clauderemote.session

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import com.clauderemote.storage.FakeKeyValueStore
import com.clauderemote.storage.ServerStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opening a session from outside the tab bar while its close waits out the undo window (Jarvis F0, R6): a notification
 * tap, Velín, an answer from the watch all go through [SessionOrchestrator.reclaimPendingClose] = "Vrátit". And what
 * the tab bar and the watch show leaves the pending ones out ([PendingClosesState.visibleOf]).
 */
class PendingCloseReclaimTest {
    private val server = SshServer(id = "s1", name = "one", host = "unreachable.invalid", username = "u")

    private fun tab(id: String) = ClaudeSession(
        id = id, server = server, folder = "/work/$id", mode = ClaudeMode.NORMAL, model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-server-$id", connectionType = ConnectionType.SSH,
    )

    private inline fun withOrchestrator(block: (SessionOrchestrator, TabManager) -> Unit) {
        val tabs = TabManager()
        val o = SessionOrchestrator(ServerStorage(FakeKeyValueStore()), tabs)
        try {
            block(o, tabs)
        } finally {
            o.shutdownForTest()
        }
    }

    @Test fun openingAPendingTabUndoesItsClose() = withOrchestrator { o, tabs ->
        val a = tab("a")
        tabs.addTab(tab("b"))
        tabs.addTab(a)
        assertTrue(o.pendingCloses.close(a, wasActive = true))
        assertEquals(setOf("a"), o.pendingCloses.state.value.hiddenIds)

        assertTrue(o.reclaimPendingClose("a"), "the notification tap is the undo")
        assertTrue(o.pendingCloses.state.value.hiddenIds.isEmpty(), "the tab is back in the bar")
        assertNull(o.pendingCloses.remainingMs("a"), "no countdown left: the close will not happen")
        o.switchTab("a")
        assertEquals("a", tabs.activeTabId.value)

        assertFalse(o.reclaimPendingClose("a"), "nothing pending any more")
        assertFalse(o.reclaimPendingClose("b"), "a tab that was never closed")
    }

    /**
     * Not every switch is the user's: after a close goes through, the orchestrator switches to whatever tab
     * TabManager.removeTab picked, which may be another pending one. That must not undo it.
     */
    @Test fun aPlainSwitchToAPendingTabDoesNotUndoIt() = withOrchestrator { o, tabs ->
        val a = tab("a")
        tabs.addTab(a)
        tabs.addTab(tab("b"))
        o.pendingCloses.close(a)
        o.switchTab("a")
        assertEquals(setOf("a"), o.pendingCloses.state.value.hiddenIds)
    }

    /**
     * An answer undoes a pending close only once its keys reached the pane: a refused one (the session is not even
     * connected here) leaves the close running.
     */
    @Test fun aRefusedAnswerDoesNotUndoTheClose() = withOrchestrator { o, tabs ->
        val a = tab("a")
        tabs.addTab(a)
        o.pendingCloses.close(a)
        val why = kotlinx.coroutines.runBlocking {
            o.sendReply("a", null, com.clauderemote.session.service.ReplyGuard.Reply.TEXT, "ahoj")
        }
        assertTrue(why != null && !o.replyReachedThePane(why), "refused: $why")
        assertEquals(setOf("a"), o.pendingCloses.state.value.hiddenIds, "the close goes on")
    }

    /**
     * The other half: an answer whose keys reached the pane ("CR_REPLY SENT", or an exec that broke off after the keys
     * may have gone: MAYBE_SENT) undoes the close — through the real [SessionOrchestrator.sendReply], only the route
     * and the server exec faked.
     */
    @Test fun anAnswerThatReachedThePaneUndoesTheClose() {
        for ((serverSays, expected) in listOf<Pair<String?, com.clauderemote.session.service.RefuseReason?>>(
            "CR_REPLY SENT\n" to null,
            "CR_REPLY SENDING\n" to com.clauderemote.session.service.RefuseReason.MAYBE_SENT,
        )) withOrchestrator { o, tabs ->
            val a = tab("a")
            tabs.addTab(a)
            o.claudeControlForTest.routeOverride = { com.clauderemote.session.service.ClaudeControlService.InputRoute({}, {}) }
            o.claudeControlForTest.replyExecOverride = { _, _ -> serverSays }
            o.statusService.updateActivity("a", com.clauderemote.model.SessionActivity.WAITING_FOR_INPUT)
            assertTrue(o.pendingCloses.close(a))
            val why = kotlinx.coroutines.runBlocking {
                o.sendReply(
                    "a",
                    com.clauderemote.session.service.PromptFingerprint(
                        com.clauderemote.session.service.ReplyGuard.INSTRUCTION_KEY,
                        com.clauderemote.session.service.PromptKind.INSTRUCTION,
                    ),
                    com.clauderemote.session.service.ReplyGuard.Reply.TEXT,
                    "pokračuj",
                )
            }
            assertEquals(expected, why, "server said: $serverSays")
            assertTrue(o.pendingCloses.state.value.hiddenIds.isEmpty(), "the tab is back ($serverSays)")
            assertNull(o.pendingCloses.remainingMs("a"), "no countdown left: the close will not happen ($serverSays)")
        }
    }

    @Test fun onlyAnAnswerThatReachedThePaneKeepsTheSession() = withOrchestrator { o, _ ->
        val keeps = com.clauderemote.session.service.RefuseReason.entries.filter { o.replyReachedThePane(it) }.toSet()
        assertEquals(setOf(
            com.clauderemote.session.service.RefuseReason.MAYBE_SENT,
            com.clauderemote.session.service.RefuseReason.TYPED_NOT_SENT,
        ), keeps)
        assertTrue(o.replyReachedThePane(null), "sent")
    }

    @Test fun visibleTabsLeaveThePendingOnesOut() {
        val all = listOf(tab("a"), tab("b"), tab("c"))
        val none = PendingClosesState()
        assertTrue(none.visibleOf(all) === all, "nothing pending: the same list")
        val s = PendingClosesState(
            pending = listOf(PendingClose("b", "b", wasActive = false)),
            blocked = listOf(BlockedClose("c", "c", "běží tah")),
        )
        assertEquals(listOf("a", "c"), s.visibleOf(all).map { it.id }, "a blocked close is back in view, a pending one is not")
    }
}
