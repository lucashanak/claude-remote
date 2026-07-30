package com.clauderemote.storage

import com.clauderemote.model.AuthMethod
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.PortForward
import com.clauderemote.model.ServerTransport
import com.clauderemote.model.SshServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ServerStorage] — the user's saved SSH servers and TOFU known-hosts
 * map. Losing this is the worst data loss in the app (it holds credentials the
 * user typed once), so the corrupt-blob and on-disk-shape tests below matter more
 * than the CRUD ones.
 */
class ServerStorageTest {

    private val serversKey = "ssh_servers"
    private val knownHostsKey = "known_hosts"

    private fun server(id: String, name: String = "srv-$id") = SshServer(
        id = id,
        name = name,
        host = "$id.example.org",
        username = "lucas",
    )

    // --- empty / absent store ---

    @Test
    fun absentStore_loadsEmptyServerList() {
        assertEquals(emptyList(), ServerStorage(FakeKeyValueStore()).loadServers())
    }

    @Test
    fun absentStore_loadsEmptyKnownHosts() {
        assertEquals(emptyMap(), ServerStorage(FakeKeyValueStore()).loadKnownHosts())
    }

    @Test
    fun emptyJsonContainers_loadEmpty() {
        val storage = ServerStorage(
            FakeKeyValueStore(mapOf(serversKey to "[]", knownHostsKey to "{}"))
        )
        assertEquals(emptyList(), storage.loadServers())
        assertEquals(emptyMap(), storage.loadKnownHosts())
    }

    // --- round-trip ---

    @Test
    fun saveThenLoad_roundTripsEveryField() {
        val store = FakeKeyValueStore()
        val storage = ServerStorage(store)
        val original = SshServer(
            id = "srv1",
            name = "dev box",
            host = "dev.example.org",
            port = 2222,
            username = "lucas",
            authMethod = AuthMethod.KEY,
            password = null,
            privateKey = "-----BEGIN KEY-----",
            preferMosh = true,
            preferEternal = true,
            defaultFolder = "/home/lucas/claude-remote",
            recentFolders = listOf("/home/lucas/a", "/home/lucas/b"),
            defaultClaudeMode = ClaudeMode.YOLO,
            defaultClaudeModel = ClaudeModel.OPUS,
            portForwards = listOf(PortForward(type = "L", localPort = 8080, remotePort = 80)),
            favorite = true,
            startupCommand = "cd /srv && ls",
            snippets = listOf("git status"),
            useCloudflareProxy = true,
            cloudflareToken = "tok",
            tailscaleHost = "dev.tail1234.ts.net",
            transport = ServerTransport.AUTO,
        )
        storage.saveServers(listOf(original))
        assertEquals(listOf(original), storage.loadServers())
        // And through a fresh storage instance over the same store.
        assertEquals(listOf(original), ServerStorage(store).loadServers())
    }

    // --- CRUD ---

    @Test
    fun addServer_appendsPreservingOrder() {
        val storage = ServerStorage(FakeKeyValueStore())
        storage.addServer(server("a"))
        storage.addServer(server("b"))
        assertEquals(listOf("a", "b"), storage.loadServers().map { it.id })
    }

    @Test
    fun updateServer_replacesTheMatchingId() {
        val storage = ServerStorage(FakeKeyValueStore())
        storage.addServer(server("a", name = "old"))
        storage.addServer(server("b"))
        storage.updateServer(server("a", name = "new"))
        assertEquals("new", storage.getServer("a")?.name)
        assertEquals(2, storage.loadServers().size)
    }

    @Test
    fun updateServer_unknownIdIsANoOp() {
        val storage = ServerStorage(FakeKeyValueStore())
        storage.addServer(server("a"))
        storage.updateServer(server("ghost"))
        assertEquals(listOf("a"), storage.loadServers().map { it.id })
    }

    @Test
    fun deleteServer_removesOnlyThatServer() {
        val storage = ServerStorage(FakeKeyValueStore())
        storage.addServer(server("a"))
        storage.addServer(server("b"))
        storage.deleteServer("a")
        assertEquals(listOf("b"), storage.loadServers().map { it.id })
        assertNull(storage.getServer("a"))
    }

    @Test
    fun deleteServer_unknownIdIsANoOp() {
        val storage = ServerStorage(FakeKeyValueStore())
        storage.addServer(server("a"))
        storage.deleteServer("ghost")
        assertEquals(listOf("a"), storage.loadServers().map { it.id })
    }

    @Test
    fun getServer_returnsNullWhenAbsent() {
        assertNull(ServerStorage(FakeKeyValueStore()).getServer("nope"))
    }

    // --- known hosts (TOFU) ---

    @Test
    fun saveKnownHost_roundTripsAndOverwritesOnKeyChange() {
        val storage = ServerStorage(FakeKeyValueStore())
        storage.saveKnownHost("dev.example.org:22", "fp-1")
        storage.saveKnownHost("other.example.org:22", "fp-2")
        assertEquals(mapOf("dev.example.org:22" to "fp-1", "other.example.org:22" to "fp-2"), storage.loadKnownHosts())
        // Re-pinning a host must replace, not duplicate.
        storage.saveKnownHost("dev.example.org:22", "fp-1-rotated")
        assertEquals("fp-1-rotated", storage.loadKnownHosts()["dev.example.org:22"])
        assertEquals(2, storage.loadKnownHosts().size)
    }

    @Test
    fun corruptKnownHosts_loadsEmptyMapInsteadOfThrowing() {
        val storage = ServerStorage(FakeKeyValueStore(mapOf(knownHostsKey to "]not json[")))
        assertEquals(emptyMap(), storage.loadKnownHosts())
    }

    @Test
    fun corruptKnownHosts_isRecoverableByPinningAgain() {
        val store = FakeKeyValueStore(mapOf(knownHostsKey to "garbage"))
        val storage = ServerStorage(store)
        storage.saveKnownHost("dev.example.org:22", "fp-1")
        assertEquals(mapOf("dev.example.org:22" to "fp-1"), storage.loadKnownHosts())
    }

    // --- corrupt persisted data must never crash launch ---

    @Test
    fun corruptServerJson_loadsEmptyListInsteadOfThrowing() {
        assertEquals(
            emptyList(),
            ServerStorage(FakeKeyValueStore(mapOf(serversKey to "{not json"))).loadServers()
        )
    }

    @Test
    fun wrongServerJsonShape_loadsEmptyListInsteadOfThrowing() {
        assertEquals(
            emptyList(),
            ServerStorage(FakeKeyValueStore(mapOf(serversKey to """{"id":"a"}"""))).loadServers()
        )
    }

    @Test
    fun blankStoredServerValue_loadsEmptyListInsteadOfThrowing() {
        assertEquals(emptyList(), ServerStorage(FakeKeyValueStore(mapOf(serversKey to ""))).loadServers())
    }

    @Test
    fun entryMissingRequiredField_loadsEmptyListInsteadOfThrowing() {
        // `username` has no default; the whole list is dropped rather than
        // partially recovered. Documents current behavior.
        val raw = """[{"id":"a","name":"dev","host":"dev.example.org"}]"""
        assertEquals(emptyList(), ServerStorage(FakeKeyValueStore(mapOf(serversKey to raw))).loadServers())
    }

    @Test
    fun corruptServerJson_isRecoverableByAddingAServer() {
        val store = FakeKeyValueStore(mapOf(serversKey to "garbage"))
        val storage = ServerStorage(store)
        assertEquals(emptyList(), storage.loadServers())
        storage.addServer(server("a"))
        assertEquals(listOf("a"), storage.loadServers().map { it.id })
    }

    // --- on-disk backward compatibility ---

    @Test
    fun oldOnDiskShape_stillDeserializes() {
        // Hand-written fixture in the minimal shape an early build wrote: only the
        // fields without defaults. Every later field must fall back to its default.
        // If a rename or removal breaks this, users lose their saved servers
        // (including credentials) on upgrade — fix with a migration, not by
        // editing this fixture.
        val raw = """
            [{"id":"srv-1","name":"dev box","host":"dev.example.org","username":"lucas"}]
        """.trimIndent()
        val s = ServerStorage(FakeKeyValueStore(mapOf(serversKey to raw))).loadServers().single()
        assertEquals("srv-1", s.id)
        assertEquals("dev box", s.name)
        assertEquals("dev.example.org", s.host)
        assertEquals("lucas", s.username)
        assertEquals(22, s.port)
        assertEquals(AuthMethod.PASSWORD, s.authMethod)
        assertNull(s.password)
        assertNull(s.privateKey)
        assertEquals(false, s.preferMosh)
        assertEquals(false, s.preferEternal)
        assertEquals("~", s.defaultFolder)
        assertEquals(emptyList(), s.recentFolders)
        assertEquals(ClaudeMode.NORMAL, s.defaultClaudeMode)
        assertEquals(ClaudeModel.DEFAULT, s.defaultClaudeModel)
        assertEquals(emptyList(), s.portForwards)
        assertEquals(false, s.favorite)
        assertEquals("", s.startupCommand)
        assertEquals(emptyList(), s.snippets)
        assertEquals(false, s.useCloudflareProxy)
        assertEquals("", s.cloudflareToken)
        assertEquals("", s.tailscaleHost)
        assertEquals(ServerTransport.CLOUDFLARE, s.transport)
    }

    @Test
    fun currentOnDiskShape_stillDeserializes() {
        val raw = """
            [{"id":"srv-1","name":"dev box","host":"dev.example.org","port":2222,"username":"lucas",
              "authMethod":"KEY","privateKey":"-----BEGIN KEY-----","preferMosh":true,"preferEternal":true,
              "defaultFolder":"/home/lucas/claude-remote","recentFolders":["/home/lucas/a"],
              "defaultClaudeMode":"YOLO","defaultClaudeModel":"OPUS",
              "portForwards":[{"type":"L","localPort":8080,"remoteHost":"127.0.0.1","remotePort":80}],
              "favorite":true,"startupCommand":"cd /srv","snippets":["git status"],
              "useCloudflareProxy":true,"cloudflareToken":"tok","tailscaleHost":"dev.tail1234.ts.net",
              "transport":"TAILSCALE"}]
        """.trimIndent()
        val s = ServerStorage(FakeKeyValueStore(mapOf(serversKey to raw))).loadServers().single()
        assertEquals(2222, s.port)
        assertEquals(AuthMethod.KEY, s.authMethod)
        assertEquals(true, s.preferEternal)
        assertEquals(listOf("/home/lucas/a"), s.recentFolders)
        assertEquals(ClaudeMode.YOLO, s.defaultClaudeMode)
        assertEquals(ClaudeModel.OPUS, s.defaultClaudeModel)
        assertEquals(listOf(PortForward("L", 8080, "127.0.0.1", 80)), s.portForwards)
        assertEquals(listOf("git status"), s.snippets)
        assertEquals("tok", s.cloudflareToken)
        assertEquals(ServerTransport.TAILSCALE, s.transport)
    }

    @Test
    fun unknownFutureField_isIgnoredNotFatal() {
        val raw = """
            [{"id":"srv-1","name":"dev","host":"dev.example.org","username":"lucas",
              "someFieldFromTheFuture":"x"}]
        """.trimIndent()
        assertEquals(
            "srv-1",
            ServerStorage(FakeKeyValueStore(mapOf(serversKey to raw))).loadServers().single().id
        )
    }

    @Test
    fun unknownTransportValue_fallsBackToDefaultInsteadOfWipingTheList() {
        // Regression guard (forward-compat): an unknown ENUM VALUE is not the same
        // as an unknown key — ignoreUnknownKeys alone does not cover it, so a
        // server saved by a NEWER app version (or a downgrade / phone-watch version
        // skew) with a transport this build doesn't know would otherwise make the
        // ENTIRE server list undecodable and the user sees zero saved servers.
        // coerceInputValues=true (ServerStorage.kt) fixes this: an unrecognized
        // enum constant falls back to the property's declared default and the rest
        // of the list (and object) survives intact.
        val raw = """
            [{"id":"srv-1","name":"dev","host":"dev.example.org","username":"lucas",
              "transport":"QUANTUM_ENTANGLEMENT"}]
        """.trimIndent()
        val s = ServerStorage(FakeKeyValueStore(mapOf(serversKey to raw))).loadServers().single()
        assertEquals("srv-1", s.id)
        assertEquals("dev", s.name)
        assertEquals(ServerTransport.CLOUDFLARE, s.transport)
    }

    @Test
    fun unknownAuthMethodValue_fallsBackToDefault() {
        val raw = """
            [{"id":"srv-1","name":"dev","host":"dev.example.org","username":"lucas",
              "authMethod":"QUANTUM_KEY"}]
        """.trimIndent()
        val s = ServerStorage(FakeKeyValueStore(mapOf(serversKey to raw))).loadServers().single()
        assertEquals("srv-1", s.id)
        assertEquals("dev.example.org", s.host)
        assertEquals(AuthMethod.PASSWORD, s.authMethod)
    }

    @Test
    fun unknownClaudeModeValue_fallsBackToDefault() {
        val raw = """
            [{"id":"srv-1","name":"dev","host":"dev.example.org","username":"lucas",
              "defaultClaudeMode":"HYPERSPEED"}]
        """.trimIndent()
        val s = ServerStorage(FakeKeyValueStore(mapOf(serversKey to raw))).loadServers().single()
        assertEquals("srv-1", s.id)
        assertEquals("lucas", s.username)
        assertEquals(ClaudeMode.NORMAL, s.defaultClaudeMode)
    }

    @Test
    fun unknownClaudeModelValue_fallsBackToDefault() {
        val raw = """
            [{"id":"srv-1","name":"dev","host":"dev.example.org","username":"lucas",
              "defaultClaudeModel":"GPT7"}]
        """.trimIndent()
        val s = ServerStorage(FakeKeyValueStore(mapOf(serversKey to raw))).loadServers().single()
        assertEquals("srv-1", s.id)
        assertEquals("dev.example.org", s.host)
        assertEquals(ClaudeModel.DEFAULT, s.defaultClaudeModel)
    }

    @Test
    fun explicitNullForNonNullableDefaultedField_fallsBackToDefault() {
        // coerceInputValues also covers a JSON `null` against a non-nullable
        // property that has a default — e.g. a field a future version made
        // nullable-with-fallback and this build still declares non-nullable.
        val raw = """
            [{"id":"srv-1","name":"dev","host":"dev.example.org","username":"lucas",
              "favorite":null}]
        """.trimIndent()
        val s = ServerStorage(FakeKeyValueStore(mapOf(serversKey to raw))).loadServers().single()
        assertEquals("srv-1", s.id)
        assertEquals("dev.example.org", s.host)
        assertEquals(false, s.favorite)
    }

    // --- the seam itself ---

    @Test
    fun serverWritesUseTheAsyncPathNotTheDurableOne() {
        val store = FakeKeyValueStore()
        val storage = ServerStorage(store)
        storage.addServer(server("a"))
        storage.updateServer(server("a", name = "renamed"))
        storage.deleteServer("a")
        storage.saveKnownHost("dev.example.org:22", "fp")
        assertEquals(0, store.syncWriteCount)
        assertTrue(store.map.containsKey(serversKey))
    }
}
