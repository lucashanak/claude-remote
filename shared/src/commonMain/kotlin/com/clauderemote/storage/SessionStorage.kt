package com.clauderemote.storage

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Disk-persistent record of an active Claude session. Survives app restart
 * and (combined with the server-side systemd restore service) survives
 * remote reboot. Kept separate from in-memory [ClaudeSession] so we don't
 * persist volatile fields (status, connectedAt) and don't have to make
 * SshServer transitively serializable through TabManager state.
 *
 * `serverId` is resolved against [ServerStorage] at restore time. If the
 * server has been deleted, the persisted session is dropped on next save.
 */
@Serializable
data class PersistedSession(
    val id: String,
    val serverId: String,
    val folder: String,
    val mode: ClaudeMode,
    val model: ClaudeModel,
    val tmuxSessionName: String,
    val connectionType: ConnectionType,
    val alias: String = "",
    val claudeSessionId: String? = null,
    val createdAt: Long = 0L
)

class SessionStorage(private val prefs: PlatformPreferences) {

    // Compact JSON for the on-device prefs blob (size matters on Android
    // SharedPreferences). The server-side snapshot uses a pretty variant
    // for human inspection.
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false; encodeDefaults = true }
    private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun load(): List<PersistedSession> {
        val raw = prefs.getString(KEY_SESSIONS, "[]")
        val parsed = try {
            json.decodeFromString<List<PersistedSession>>(raw)
        } catch (e: Exception) {
            // Corrupt blob — drop it so we don't loop on parse errors. Logging
            // is platform-agnostic (println shows up in logcat / stdout).
            println("SessionStorage: failed to decode persisted sessions, resetting (${e.message})")
            return emptyList()
        }
        // Dedupe by (serverId, tmuxSessionName) keeping the entry with the
        // newest createdAt — heals stale duplicates that older app versions
        // (which keyed upsert only on the in-memory id) accumulated on disk.
        val deduped = parsed
            .groupBy { it.serverId to it.tmuxSessionName }
            .map { (_, group) -> group.maxByOrNull { it.createdAt } ?: group.first() }
        if (deduped.size != parsed.size) {
            println("SessionStorage: deduped ${parsed.size - deduped.size} stale entries on load")
            save(deduped)
        }
        return deduped
    }

    fun save(sessions: List<PersistedSession>) {
        prefs.putString(KEY_SESSIONS, json.encodeToString(sessions))
    }

    fun upsert(session: PersistedSession) {
        val list = load().toMutableList()
        // Match first by app-internal id, then fall back to (server, tmux name)
        // — the app may regenerate `id` on a fresh launch even though the
        // user is reopening the same tmux session. Without this fallback we
        // accumulate duplicates that the systemd restore service then tries
        // to rebuild as separate panes.
        val byId = list.indexOfFirst { it.id == session.id }
        val byName = list.indexOfFirst {
            it.serverId == session.serverId && it.tmuxSessionName == session.tmuxSessionName
        }
        val idx = if (byId >= 0) byId else byName
        if (idx >= 0) list[idx] = session else list.add(session)
        save(list)
    }

    fun remove(sessionId: String) {
        save(load().filter { it.id != sessionId })
    }

    /**
     * Serialize the current session list as a JSON string suitable for upload
     * to the server (consumed by the systemd restore service). Pretty-printed
     * for human inspection at `~/.claude-remote/sessions.json`.
     */
    fun serializeForServer(serverId: String): String {
        val forServer = load().filter { it.serverId == serverId }
        return prettyJson.encodeToString(forServer)
    }

    companion object {
        private const val KEY_SESSIONS = "claude_sessions"

        fun fromClaudeSession(session: ClaudeSession): PersistedSession =
            PersistedSession(
                id = session.id,
                serverId = session.server.id,
                folder = session.folder,
                mode = session.mode,
                model = session.model,
                tmuxSessionName = session.tmuxSessionName,
                connectionType = session.connectionType,
                alias = session.alias,
                claudeSessionId = session.claudeSessionId,
                createdAt = session.connectedAt
            )

        /**
         * Resolve a [PersistedSession] back to an in-memory [ClaudeSession],
         * looking up the server by id. Returns null if the referenced server
         * has been deleted from storage.
         */
        fun toClaudeSession(persisted: PersistedSession, serverStorage: ServerStorage): ClaudeSession? {
            val server = serverStorage.getServer(persisted.serverId) ?: return null
            return ClaudeSession(
                id = persisted.id,
                server = server,
                folder = persisted.folder,
                mode = persisted.mode,
                model = persisted.model,
                tmuxSessionName = persisted.tmuxSessionName,
                connectionType = persisted.connectionType,
                status = SessionStatus.CONNECTING,
                connectedAt = if (persisted.createdAt > 0L) persisted.createdAt else System.currentTimeMillis(),
                alias = persisted.alias,
                claudeSessionId = persisted.claudeSessionId
            )
        }
    }
}
