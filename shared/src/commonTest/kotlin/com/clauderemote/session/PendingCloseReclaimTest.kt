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
