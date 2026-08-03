package com.clauderemote.storage

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SessionStatus
import com.clauderemote.model.TmuxNameParser
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
    // Defaulted (unlike serverId/folder/tmuxSessionName) so an unknown enum
    // value written by a skewed app build (newer phone, older watch, or vice
    // versa) coerces to a safe fallback instead of making decodeFromString
    // throw for the whole session list — see `json`'s coerceInputValues above.
    val mode: ClaudeMode = ClaudeMode.NORMAL,
    val model: ClaudeModel = ClaudeModel.DEFAULT,
    val tmuxSessionName: String,
    val connectionType: ConnectionType = ConnectionType.SSH,
    val alias: String = "",
    val claudeSessionId: String? = null,
    val createdAt: Long = 0L,
    /**
     * Server-side Claude login this session runs under. Absent/null = the
     * default `~/.claude` account, so every row written before multi-account
     * existed keeps restoring exactly as before — no migration needed. The
     * server-side restore/drift scripts read this same key out of
     * `sessions.json` (see SessionPersistenceService).
     */
    val accountSlug: String? = null
)

class SessionStorage(private val prefs: KeyValueStore) {

    // Compact JSON for the on-device prefs blob (size matters on Android
    // SharedPreferences). The server-side snapshot uses a pretty variant
    // for human inspection.
    //
    // coerceInputValues: ignoreUnknownKeys only covers unknown KEYS, not an
    // unknown ENUM VALUE — a `mode`/`model`/`connectionType` written by a
    // newer (or watch-skewed) app build would otherwise make decodeFromString
    // throw for the WHOLE list, wiping every restorable session. With this
    // on, an unrecognized enum constant (or `null` for a non-nullable field)
    // falls back to that property's declared default instead.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = false; encodeDefaults = true }
    private val prettyJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true; encodeDefaults = true }

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
        // Names written before build() applied tmux's own substitution still
        // carry '.'/':' and can never match the live session tmux actually
        // created, so they read as a separate session and show up as a
        // duplicate tab. Normalize first, then dedupe.
        val normalized = parsed.map { it to TmuxNameParser.sanitize(it.tmuxSessionName) }
        // Dedupe by (serverId, tmuxSessionName) keeping the entry with the
        // newest createdAt — heals stale duplicates that older app versions
        // (which keyed upsert only on the in-memory id) accumulated on disk.
        //
        // A row whose name we just rewrote wins its group outright: it is the
        // one the launcher created, so it holds the REAL folder
        // (/home/lucas/nekrachni.plus). The row it collapses with was minted by
        // the scan discovering the renamed session, whose folder could only be
        // parsed back out of the mangled name (nekrachni_plus) — keeping that
        // one would leave the tab pointing at a directory that does not exist.
        // claudeSessionId needs no such care: the drift/probe refresh re-syncs
        // it from the live pane within a tick.
        val deduped = normalized
            .groupBy { (entry, name) -> entry.serverId to name }
            .map { (_, group) ->
                val (entry, name) = group.firstOrNull { (e, name) -> e.tmuxSessionName != name }
                    ?: group.maxByOrNull { (e, _) -> e.createdAt }
                    ?: group.first()
                if (entry.tmuxSessionName == name) entry else entry.copy(tmuxSessionName = name)
            }
        if (deduped != parsed) {
            println("SessionStorage: normalized/deduped ${parsed.size - deduped.size} stale entries on load")
            save(deduped)
        }
        return deduped
    }

    fun save(sessions: List<PersistedSession>) {
        prefs.putString(KEY_SESSIONS, json.encodeToString(sessions))
    }

    /**
     * Synchronous variant for the close/forget path: on Android, [putString]
     * is a fire-and-forget `apply()` — if the process dies (backgrounded and
     * reaped) before that async write lands, the next launch reads the STALE
     * list, restores the "closed" tab, and reconnectSession's missing-tmux
     * fallback relaunches a fresh session under the same name. A blocking
     * write here closes that race.
     */
    private fun saveSync(sessions: List<PersistedSession>) {
        prefs.putStringSync(KEY_SESSIONS, json.encodeToString(sessions))
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
        saveSync(load().filter { it.id != sessionId })
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
                createdAt = session.connectedAt,
                accountSlug = session.accountSlug
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
                claudeSessionId = persisted.claudeSessionId,
                accountSlug = persisted.accountSlug
            )
        }
    }
}
