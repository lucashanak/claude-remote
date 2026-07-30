package com.clauderemote.storage

import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ConnectionType
import com.clauderemote.model.SshServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SessionStorage] — the persisted tab list that drives session
 * restore on launch. The failure modes that matter are all data-loss shaped: a
 * corrupt blob must not crash launch, a "closed" tab must not come back, and a
 * future field rename must not silently wipe everyone's saved sessions (hence
 * the hand-written on-disk fixture below).
 */
class SessionStorageTest {

    private val key = "claude_sessions"

    private fun session(
        id: String,
        serverId: String = "srv1",
        tmux: String = "claude-$id",
        createdAt: Long = 0L,
        alias: String = "",
    ) = PersistedSession(
        id = id,
        serverId = serverId,
        folder = "/home/lucas/proj",
        mode = ClaudeMode.YOLO,
        model = ClaudeModel.OPUS,
        tmuxSessionName = tmux,
        connectionType = ConnectionType.SSH,
        alias = alias,
        claudeSessionId = "uuid-$id",
        createdAt = createdAt,
    )

    // --- empty / absent store ---

    @Test
    fun absentStore_loadsEmptyList() {
        assertEquals(emptyList(), SessionStorage(FakeKeyValueStore()).load())
    }

    @Test
    fun emptyJsonArray_loadsEmptyList() {
        assertEquals(emptyList(), SessionStorage(FakeKeyValueStore(mapOf(key to "[]"))).load())
    }

    // --- round-trip ---

    @Test
    fun saveThenLoad_roundTripsEveryField() {
        val store = FakeKeyValueStore()
        val storage = SessionStorage(store)
        val original = session("s1", createdAt = 1_700_000_000_000L, alias = "my tab")
        storage.save(listOf(original))
        assertEquals(listOf(original), storage.load())
    }

    @Test
    fun saveThenLoad_survivesAFreshStorageInstance() {
        val store = FakeKeyValueStore()
        SessionStorage(store).save(listOf(session("s1"), session("s2")))
        assertEquals(listOf("s1", "s2"), SessionStorage(store).load().map { it.id })
    }

    // --- upsert ---

    @Test
    fun upsert_addsWhenAbsent() {
        val storage = SessionStorage(FakeKeyValueStore())
        storage.upsert(session("s1"))
        assertEquals(listOf("s1"), storage.load().map { it.id })
    }

    @Test
    fun upsert_replacesMatchByAppInternalId() {
        val storage = SessionStorage(FakeKeyValueStore())
        storage.upsert(session("s1", alias = "old"))
        storage.upsert(session("s1", alias = "new"))
        val loaded = storage.load()
        assertEquals(1, loaded.size)
        assertEquals("new", loaded.single().alias)
    }

    @Test
    fun upsert_replacesMatchByServerAndTmuxNameWhenIdChanged() {
        // A fresh app launch regenerates the in-memory id for the same tmux
        // session; without the (serverId, tmuxSessionName) fallback we would
        // accumulate duplicates that the systemd restore service rebuilds as
        // separate panes.
        val storage = SessionStorage(FakeKeyValueStore())
        storage.upsert(session("old-id", tmux = "claude-proj"))
        storage.upsert(session("new-id", tmux = "claude-proj", alias = "same tmux"))
        val loaded = storage.load()
        assertEquals(1, loaded.size)
        assertEquals("new-id", loaded.single().id)
        assertEquals("same tmux", loaded.single().alias)
    }

    @Test
    fun upsert_keepsDistinctTmuxSessionsSeparate() {
        val storage = SessionStorage(FakeKeyValueStore())
        storage.upsert(session("s1", tmux = "claude-a"))
        storage.upsert(session("s2", tmux = "claude-b"))
        assertEquals(listOf("s1", "s2"), storage.load().map { it.id })
    }

    @Test
    fun upsert_sameTmuxNameOnDifferentServersStaysSeparate() {
        val storage = SessionStorage(FakeKeyValueStore())
        storage.upsert(session("s1", serverId = "srvA", tmux = "claude-proj"))
        storage.upsert(session("s2", serverId = "srvB", tmux = "claude-proj"))
        assertEquals(2, storage.load().size)
    }

    // --- remove: must use the DURABLE write path ---

    @Test
    fun remove_dropsTheSessionAndUsesTheDurableWritePath() {
        // If this write were the async apply(), a process kill right after the
        // user closed a tab would resurrect it on next launch and relaunch a
        // fresh claude under the same tmux name.
        val store = FakeKeyValueStore()
        val storage = SessionStorage(store)
        storage.save(listOf(session("s1"), session("s2")))
        val syncBefore = store.syncWriteCount

        storage.remove("s1")

        assertEquals(listOf("s2"), storage.load().map { it.id })
        assertEquals(syncBefore + 1, store.syncWriteCount, "remove must go through putStringSync")
        assertEquals(key, store.syncWrittenKeys.last())
    }

    @Test
    fun remove_unknownIdIsANoOpButStillPersistsDurably() {
        val store = FakeKeyValueStore()
        val storage = SessionStorage(store)
        storage.save(listOf(session("s1")))
        storage.remove("nope")
        assertEquals(listOf("s1"), storage.load().map { it.id })
        assertEquals(1, store.syncWriteCount)
    }

    @Test
    fun save_doesNotUseTheDurableWritePath() {
        val store = FakeKeyValueStore()
        SessionStorage(store).save(listOf(session("s1")))
        assertEquals(0, store.syncWriteCount)
    }

    // --- corrupt persisted data must never crash launch ---

    @Test
    fun corruptJson_loadsEmptyListInsteadOfThrowing() {
        val storage = SessionStorage(FakeKeyValueStore(mapOf(key to "{not json at all")))
        assertEquals(emptyList(), storage.load())
    }

    @Test
    fun wrongJsonShape_loadsEmptyListInsteadOfThrowing() {
        // An object where a list is expected — e.g. a half-migrated on-disk format.
        val storage = SessionStorage(FakeKeyValueStore(mapOf(key to """{"id":"s1"}""")))
        assertEquals(emptyList(), storage.load())
    }

    @Test
    fun entryMissingRequiredField_loadsEmptyListInsteadOfThrowing() {
        // `serverId` has no default, so the whole blob fails to decode. The list
        // is dropped rather than partially recovered — documents current behavior.
        val raw = """[{"id":"s1","folder":"/x","mode":"YOLO","model":"OPUS",""" +
            """"tmuxSessionName":"t","connectionType":"SSH"}]"""
        assertEquals(emptyList(), SessionStorage(FakeKeyValueStore(mapOf(key to raw))).load())
    }

    @Test
    fun blankStoredValue_loadsEmptyListInsteadOfThrowing() {
        assertEquals(emptyList(), SessionStorage(FakeKeyValueStore(mapOf(key to ""))).load())
    }

    @Test
    fun corruptJson_isRecoverableByASubsequentSave() {
        val store = FakeKeyValueStore(mapOf(key to "garbage"))
        val storage = SessionStorage(store)
        assertEquals(emptyList(), storage.load())
        storage.upsert(session("s1"))
        assertEquals(listOf("s1"), storage.load().map { it.id })
    }

    // --- on-disk backward compatibility ---

    @Test
    fun oldOnDiskShape_stillDeserializes() {
        // Hand-written fixture in the shape written by the build that first added
        // PersistedSession: no alias / claudeSessionId / createdAt. If a future
        // rename or removal breaks this, saved sessions would be silently wiped on
        // upgrade — so this test failing means "write a migration", not "update me".
        val raw = """
            [{"id":"sess-1","serverId":"srv-1","folder":"/home/lucas/claude-remote",
              "mode":"YOLO","model":"OPUS","tmuxSessionName":"claude-remote-1",
              "connectionType":"SSH"}]
        """.trimIndent()
        val loaded = SessionStorage(FakeKeyValueStore(mapOf(key to raw))).load()
        assertEquals(1, loaded.size)
        val s = loaded.single()
        assertEquals("sess-1", s.id)
        assertEquals("srv-1", s.serverId)
        assertEquals("/home/lucas/claude-remote", s.folder)
        assertEquals(ClaudeMode.YOLO, s.mode)
        assertEquals(ClaudeModel.OPUS, s.model)
        assertEquals("claude-remote-1", s.tmuxSessionName)
        assertEquals(ConnectionType.SSH, s.connectionType)
        // Fields added later must fall back to their declared defaults.
        assertEquals("", s.alias)
        assertNull(s.claudeSessionId)
        assertEquals(0L, s.createdAt)
    }

    @Test
    fun currentOnDiskShape_stillDeserializes() {
        // Full current shape, exactly as encodeDefaults=true writes it.
        val raw = """
            [{"id":"sess-1","serverId":"srv-1","folder":"/home/lucas/claude-remote",
              "mode":"AUTO_ACCEPT","model":"SONNET","tmuxSessionName":"claude-remote-1",
              "connectionType":"MOSH","alias":"remote","claudeSessionId":"11111111-2222-3333-4444-555555555555",
              "createdAt":1700000000000}]
        """.trimIndent()
        val s = SessionStorage(FakeKeyValueStore(mapOf(key to raw))).load().single()
        assertEquals(ClaudeMode.AUTO_ACCEPT, s.mode)
        assertEquals(ClaudeModel.SONNET, s.model)
        assertEquals(ConnectionType.MOSH, s.connectionType)
        assertEquals("remote", s.alias)
        assertEquals("11111111-2222-3333-4444-555555555555", s.claudeSessionId)
        assertEquals(1_700_000_000_000L, s.createdAt)
    }

    @Test
    fun unknownFutureField_isIgnoredNotFatal() {
        // Forward compatibility: a newer app version adds a field, the older
        // version must still read its own sessions (ignoreUnknownKeys = true).
        val raw = """
            [{"id":"sess-1","serverId":"srv-1","folder":"/x","mode":"YOLO","model":"OPUS",
              "tmuxSessionName":"t","connectionType":"SSH","someFieldFromTheFuture":42}]
        """.trimIndent()
        assertEquals("sess-1", SessionStorage(FakeKeyValueStore(mapOf(key to raw))).load().single().id)
    }

    // --- dedupe heal on load ---

    @Test
    fun load_dedupesByServerAndTmuxNameKeepingNewestCreatedAt() {
        val store = FakeKeyValueStore()
        val storage = SessionStorage(store)
        // Written directly with save() to simulate the duplicates older versions
        // accumulated (they keyed upsert only on the in-memory id).
        storage.save(
            listOf(
                session("old", tmux = "claude-proj", createdAt = 100L),
                session("new", tmux = "claude-proj", createdAt = 200L),
            )
        )
        val loaded = storage.load()
        assertEquals(1, loaded.size)
        assertEquals("new", loaded.single().id)
    }

    @Test
    fun load_dedupeIsPersistedBackToTheStore() {
        val store = FakeKeyValueStore()
        val storage = SessionStorage(store)
        storage.save(
            listOf(
                session("old", tmux = "claude-proj", createdAt = 100L),
                session("new", tmux = "claude-proj", createdAt = 200L),
            )
        )
        storage.load()
        // The healed list must be on disk, not just in the returned value.
        assertTrue(store.map.getValue(key).contains("\"new\""))
        assertTrue(!store.map.getValue(key).contains("\"old\""))
    }

    @Test
    fun load_doesNotRewriteWhenThereIsNothingToDedupe() {
        val store = FakeKeyValueStore()
        val storage = SessionStorage(store)
        storage.save(listOf(session("s1", tmux = "a"), session("s2", tmux = "b")))
        val before = store.map.getValue(key)
        storage.load()
        assertEquals(before, store.map.getValue(key))
    }

    // --- server snapshot ---

    @Test
    fun serializeForServer_onlyIncludesThatServersSessions() {
        val storage = SessionStorage(FakeKeyValueStore())
        storage.save(
            listOf(
                session("s1", serverId = "srvA", tmux = "a"),
                session("s2", serverId = "srvB", tmux = "b"),
            )
        )
        val json = storage.serializeForServer("srvA")
        assertTrue(json.contains("\"s1\""))
        assertTrue(!json.contains("\"s2\""))
    }

    @Test
    fun serializeForServer_unknownServerYieldsEmptyJsonArray() {
        val storage = SessionStorage(FakeKeyValueStore())
        storage.save(listOf(session("s1", serverId = "srvA")))
        assertEquals("[]", storage.serializeForServer("nope").trim())
    }

    // --- ClaudeSession <-> PersistedSession ---

    @Test
    fun toClaudeSession_returnsNullWhenTheServerWasDeleted() {
        val servers = ServerStorage(FakeKeyValueStore())
        assertNull(SessionStorage.toClaudeSession(session("s1", serverId = "gone"), servers))
    }

    @Test
    fun toClaudeSession_resolvesTheServerAndPreservesFields() {
        val serverStore = FakeKeyValueStore()
        val servers = ServerStorage(serverStore)
        servers.addServer(
            SshServer(id = "srv1", name = "dev", host = "dev.example.org", username = "lucas")
        )
        val persisted = session("s1", createdAt = 1_700_000_000_000L, alias = "tab")
        val restored = assertNotNull(SessionStorage.toClaudeSession(persisted, servers))
        assertEquals("s1", restored.id)
        assertEquals("srv1", restored.server.id)
        assertEquals(persisted.folder, restored.folder)
        assertEquals(persisted.mode, restored.mode)
        assertEquals(persisted.model, restored.model)
        assertEquals(persisted.tmuxSessionName, restored.tmuxSessionName)
        assertEquals(persisted.connectionType, restored.connectionType)
        assertEquals("tab", restored.alias)
        assertEquals("uuid-s1", restored.claudeSessionId)
        assertEquals(1_700_000_000_000L, restored.connectedAt)
    }

    @Test
    fun toClaudeSession_zeroCreatedAtFallsBackToNow() {
        val servers = ServerStorage(FakeKeyValueStore())
        servers.addServer(
            SshServer(id = "srv1", name = "dev", host = "dev.example.org", username = "lucas")
        )
        val restored = assertNotNull(SessionStorage.toClaudeSession(session("s1", createdAt = 0L), servers))
        assertTrue(restored.connectedAt > 0L, "createdAt = 0 must be replaced with a real timestamp")
    }
}
