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
        _tabs.update { it + session }
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

    fun switchTab(id: String) {
        if (_tabs.value.any { it.id == id }) {
            _activeTabId.value = id
        }
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
