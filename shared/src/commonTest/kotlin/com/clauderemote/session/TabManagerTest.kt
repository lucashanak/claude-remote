package com.clauderemote.session

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionStatus
import com.clauderemote.model.SshServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [TabManager], the in-memory session-tab state machine backing the
 * session drawer / tab strip. Focused on the reassignment edge cases around
 * removing the active tab and on StateFlow emission/identity stability, since
 * those are exactly the kind of bugs that render a blank terminal or reshuffle
 * the tab strip on an unrelated update.
 */
class TabManagerTest {

    private val testServer = SshServer(
        id = "server-1",
        name = "test-server",
        host = "example.com",
        username = "user",
    )

    /** Build a minimal valid [ClaudeSession] with sensible defaults for testing. */
    private fun session(
        id: String,
        alias: String = "",
        status: SessionStatus = SessionStatus.CONNECTING,
        claudeSessionId: String? = null,
    ): ClaudeSession = ClaudeSession(
        id = id,
        server = testServer,
        folder = "/tmp/$id",
        mode = ClaudeMode.NORMAL,
        model = ClaudeModel.DEFAULT,
        tmuxSessionName = "claude-test-$id",
        connectionType = ConnectionType.SSH,
        status = status,
        alias = alias,
        claudeSessionId = claudeSessionId,
    )

    // ---- addTab ----

    @Test
    fun addTab_appendsToList() {
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.addTab(session("b"))
        assertEquals(listOf("a", "b"), manager.tabs.value.map { it.id })
    }

    @Test
    fun addTab_activatesTheNewTab() {
        // Actual behavior: addTab always makes the new tab active, even if
        // another tab was already active.
        val manager = TabManager()
        manager.addTab(session("a"))
        assertEquals("a", manager.activeTabId.value)
        manager.addTab(session("b"))
        assertEquals("b", manager.activeTabId.value)
    }

    @Test
    fun addTab_duplicateId_replacesInPlaceRatherThanAppending() {
        // Regression guard: addTab used to have no dedup/replace check —
        // adding a session with an id that already exists produced TWO
        // entries sharing that id. Every id-keyed lookup (getTab,
        // updateTabStatus, updateAlias, updateClaudeSessionId) uses
        // find/first-match, so the duplicate was unreachable-but-rendered —
        // a phantom tab in the drawer that could never be updated again.
        val manager = TabManager()
        manager.addTab(session("a", alias = "first"))
        manager.addTab(session("a", alias = "second"))
        assertEquals(listOf("a"), manager.tabs.value.map { it.id })
        assertEquals("second", manager.tabs.value.single().alias)
    }

    @Test
    fun addTab_duplicateId_preservesListPosition() {
        // The tab strip must not reshuffle when re-adding an existing
        // session id — the replaced tab stays at its original index.
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.addTab(session("b"))
        manager.addTab(session("c"))
        manager.addTab(session("b", alias = "renamed"))
        assertEquals(listOf("a", "b", "c"), manager.tabs.value.map { it.id })
        assertEquals("renamed", manager.getTab("b")?.alias)
    }

    @Test
    fun addTab_duplicateId_leavesSiblingTabsUntouched() {
        val manager = TabManager()
        manager.addTab(session("a", alias = "alias-a"))
        manager.addTab(session("b", alias = "alias-b"))
        manager.addTab(session("a", alias = "alias-a-2"))
        assertEquals("alias-b", manager.getTab("b")?.alias)
    }

    @Test
    fun addTab_duplicateId_replacedTabRemainsReachableAndMutable() {
        // The replaced tab must not become a second unreachable phantom —
        // it has to stay findable via getTab and mutable via the id-keyed
        // update methods.
        val manager = TabManager()
        manager.addTab(session("a", alias = "first"))
        manager.addTab(session("a", alias = "second"))
        assertEquals("second", manager.getTab("a")?.alias)
        manager.updateTabStatus("a", SessionStatus.ACTIVE)
        assertEquals(SessionStatus.ACTIVE, manager.getTab("a")?.status)
        manager.updateAlias("a", "third")
        assertEquals("third", manager.getTab("a")?.alias)
    }

    // ---- switchTab ----

    @Test
    fun switchTab_toKnownId_setsActiveTabId() {
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.addTab(session("b"))
        manager.switchTab("a")
        assertEquals("a", manager.activeTabId.value)
    }

    @Test
    fun switchTab_toUnknownId_isNoOp() {
        // Guarded by an existence check: switching to an id not present in
        // tabs must not leave a dangling activeTabId.
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.switchTab("does-not-exist")
        assertEquals("a", manager.activeTabId.value)
    }

    // ---- removeTab: active-tab reassignment ----

    @Test
    fun removeTab_activeFirstTab_reassignsToLastRemaining() {
        // Actual behavior: on removing the active tab, activeTabId always
        // jumps to the LAST remaining tab (filtered.lastOrNull()), not to an
        // adjacent tab relative to the removed one's position.
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.addTab(session("b"))
        manager.addTab(session("c"))
        manager.switchTab("a")
        manager.removeTab("a")
        assertEquals(listOf("b", "c"), manager.tabs.value.map { it.id })
        assertEquals("c", manager.activeTabId.value)
    }

    @Test
    fun removeTab_activeMiddleTab_reassignsToLastRemaining() {
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.addTab(session("b"))
        manager.addTab(session("c"))
        manager.switchTab("b")
        manager.removeTab("b")
        assertEquals(listOf("a", "c"), manager.tabs.value.map { it.id })
        assertEquals("c", manager.activeTabId.value)
    }

    @Test
    fun removeTab_activeLastTab_reassignsToNewLastRemaining() {
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.addTab(session("b"))
        manager.addTab(session("c"))
        manager.switchTab("c")
        manager.removeTab("c")
        assertEquals(listOf("a", "b"), manager.tabs.value.map { it.id })
        assertEquals("b", manager.activeTabId.value)
    }

    @Test
    fun removeTab_onlyTab_leavesActiveTabIdNull() {
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.removeTab("a")
        assertEquals(emptyList<String>(), manager.tabs.value.map { it.id })
        assertNull(manager.activeTabId.value)
    }

    @Test
    fun removeTab_nonActiveTab_leavesActiveTabIdUnchanged() {
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.addTab(session("b"))
        manager.addTab(session("c"))
        manager.switchTab("a")
        manager.removeTab("c")
        assertEquals(listOf("a", "b"), manager.tabs.value.map { it.id })
        assertEquals("a", manager.activeTabId.value)
    }

    @Test
    fun removeTab_unknownId_isNoOpOnListAndActiveTabId() {
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.switchTab("a")
        manager.removeTab("does-not-exist")
        assertEquals(listOf("a"), manager.tabs.value.map { it.id })
        assertEquals("a", manager.activeTabId.value)
    }

    @Test
    fun removeTab_activeTabId_neverDanglesAfterRemoval() {
        // Regression guard: whatever activeTabId ends up as after a removal,
        // it must either be null or reference a tab that still exists.
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.addTab(session("b"))
        manager.switchTab("a")
        manager.removeTab("a")
        val remainingIds = manager.tabs.value.map { it.id }
        val active = manager.activeTabId.value
        assertTrue(active == null || active in remainingIds)
    }

    // ---- updateTabStatus / updateAlias / updateClaudeSessionId ----

    @Test
    fun updateTabStatus_mutatesOnlyTargetTab() {
        val manager = TabManager()
        manager.addTab(session("a", status = SessionStatus.CONNECTING))
        manager.addTab(session("b", status = SessionStatus.CONNECTING))
        manager.updateTabStatus("a", SessionStatus.ACTIVE)
        assertEquals(SessionStatus.ACTIVE, manager.getTab("a")?.status)
        assertEquals(SessionStatus.CONNECTING, manager.getTab("b")?.status)
    }

    @Test
    fun updateAlias_mutatesOnlyTargetTab() {
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.addTab(session("b"))
        manager.updateAlias("a", "renamed")
        assertEquals("renamed", manager.getTab("a")?.alias)
        assertEquals("", manager.getTab("b")?.alias)
    }

    @Test
    fun updateClaudeSessionId_mutatesOnlyTargetTab() {
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.addTab(session("b"))
        manager.updateClaudeSessionId("a", "uuid-123")
        assertEquals("uuid-123", manager.getTab("a")?.claudeSessionId)
        assertNull(manager.getTab("b")?.claudeSessionId)
    }

    @Test
    fun updateTabStatus_unknownId_doesNotThrowAndLeavesListUnchanged() {
        val manager = TabManager()
        manager.addTab(session("a", status = SessionStatus.CONNECTING))
        manager.updateTabStatus("does-not-exist", SessionStatus.ERROR)
        assertEquals(listOf(SessionStatus.CONNECTING), manager.tabs.value.map { it.status })
        assertEquals(listOf("a"), manager.tabs.value.map { it.id })
    }

    @Test
    fun updateAlias_siblingReferenceIsUntouchedByUnrelatedUpdate() {
        // The unmatched sibling should be the SAME object reference (map's
        // `else it` branch), not merely equal, confirming no accidental copy.
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.addTab(session("b"))
        val siblingBefore = manager.getTab("b")
        manager.updateAlias("a", "renamed")
        val siblingAfter = manager.getTab("b")
        assertSame(siblingBefore, siblingAfter)
    }

    // ---- StateFlow emission / ordering stability ----

    @Test
    fun tabsValue_reflectsEachMutationImmediately() {
        val manager = TabManager()
        assertEquals(emptyList<String>(), manager.tabs.value.map { it.id })
        manager.addTab(session("a"))
        assertEquals(listOf("a"), manager.tabs.value.map { it.id })
    }

    @Test
    fun statusUpdate_preservesListOrdering() {
        // The tab strip must not reshuffle when a status changes on a
        // non-terminal tab.
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.addTab(session("b"))
        manager.addTab(session("c"))
        manager.updateTabStatus("b", SessionStatus.ERROR)
        assertEquals(listOf("a", "b", "c"), manager.tabs.value.map { it.id })
    }

    @Test
    fun aliasUpdate_preservesOtherTabsFieldValues() {
        val manager = TabManager()
        manager.addTab(session("a", status = SessionStatus.ACTIVE))
        manager.addTab(session("b", status = SessionStatus.ERROR))
        manager.updateAlias("a", "renamed")
        assertEquals(SessionStatus.ERROR, manager.getTab("b")?.status)
    }

    // ---- activeTab ----

    @Test
    fun activeTab_matchesActiveTabId() {
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.addTab(session("b"))
        manager.switchTab("a")
        assertEquals("a", manager.activeTab?.id)
    }

    @Test
    fun activeTab_isNullWhenNoActiveTab() {
        val manager = TabManager()
        assertNull(manager.activeTab)
        assertNull(manager.activeTabId.value)
    }

    @Test
    fun activeTab_becomesNullAfterRemovingTheOnlyTab() {
        val manager = TabManager()
        manager.addTab(session("a"))
        manager.removeTab("a")
        assertNull(manager.activeTab)
    }

    // ---- getTab ----

    @Test
    fun getTab_returnsNullForUnknownId() {
        val manager = TabManager()
        manager.addTab(session("a"))
        assertNull(manager.getTab("does-not-exist"))
    }

    @Test
    fun getTab_returnsMatchingSessionForKnownId() {
        val manager = TabManager()
        manager.addTab(session("a", alias = "my-tab"))
        assertEquals("my-tab", manager.getTab("a")?.alias)
        assertFalse(manager.getTab("a") == null)
    }
}
