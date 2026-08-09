package com.clauderemote.session

import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class TabManager {

    private val _tabs = MutableStateFlow<List<ClaudeSession>>(emptyList())
    val tabs: StateFlow<List<ClaudeSession>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    val activeTab: ClaudeSession?
        get() = _tabs.value.find { it.id == _activeTabId.value }

    fun addTab(session: ClaudeSession) {
        // Idempotent on id: replacing in place (rather than appending) avoids
        // a phantom duplicate tab that every id-keyed lookup (getTab,
        // updateTabStatus, updateAlias, updateClaudeSessionId — all
        // find/first-match) would never be able to reach or update again.
        _tabs.update { tabs ->
            if (tabs.any { it.id == session.id }) {
                tabs.map { if (it.id == session.id) session else it }
            } else {
                tabs + session
            }
        }
        _activeTabId.value = session.id
    }

    fun removeTab(id: String) {
        _tabs.update { tabs ->
            val filtered = tabs.filter { it.id != id }
            if (_activeTabId.value == id) {
                _activeTabId.value = filtered.lastOrNull()?.id
            }
            filtered
        }
    }

    /**
     * Make [id] the active tab. Returns false — leaving the active tab alone —
     * when no such tab exists, which is the normal state for a few seconds
     * after a cold start: the notification that asked for it is delivered
     * before restore has rebuilt the list. Callers must not act on a switch
     * that did not happen (see SessionOrchestrator.switchTab).
     */
    fun switchTab(id: String): Boolean {
        if (_tabs.value.none { it.id == id }) return false
        _activeTabId.value = id
        return true
    }

    fun updateTabStatus(id: String, status: SessionStatus) {
        _tabs.update { tabs ->
            tabs.map { if (it.id == id) it.copy(status = status) else it }
        }
    }

    fun updateAlias(id: String, alias: String) {
        _tabs.update { tabs ->
            tabs.map { if (it.id == id) it.copy(alias = alias) else it }
        }
    }

    /**
     * Update the persisted Claude Code session UUID. Called by the orchestrator
     * after polling `~/.claude/sessions/<pid>.json` on the server, to capture
     * cases where claude internally switched session_id (e.g. user invoked
     * `/resume` and picked a different conversation, or `/clear` started a
     * fresh one). Keeps SessionStorage in sync with reality.
     */
    fun updateClaudeSessionId(id: String, claudeSessionId: String) {
        _tabs.update { tabs ->
            tabs.map { if (it.id == id) it.copy(claudeSessionId = claudeSessionId) else it }
        }
    }

    fun getTab(id: String): ClaudeSession? = _tabs.value.find { it.id == id }
}
