package com.clauderemote.storage

import com.clauderemote.model.FolderPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [FolderPolicyStorage] over a [FakeKeyValueStore]. Beyond plain
 * round-tripping, the composite key (serverId + folder) and the "empty policy
 * removes the row" rule are the parts most likely to regress silently.
 */
class FolderPolicyStorageTest {

    private fun storage(): Pair<FolderPolicyStorage, FakeKeyValueStore> {
        val store = FakeKeyValueStore()
        return FolderPolicyStorage(store) to store
    }

    @Test
    fun get_unsetFolder_returnsEmptyPolicy() {
        val (storage, _) = storage()
        assertEquals(FolderPolicy(), storage.get("server1", "/repo"))
    }

    @Test
    fun setAndGet_roundTrips() {
        val (storage, _) = storage()
        val policy = FolderPolicy(defaultAccountSlug = "work", allowedAccountSlugs = setOf("work", "personal"))
        storage.set("server1", "/repo", policy)
        assertEquals(policy, storage.get("server1", "/repo"))
    }

    @Test
    fun setAndGet_survivesFreshInstanceOverSameStore() {
        // Must actually be persisted in the backing store, not just cached in memory.
        val store = FakeKeyValueStore()
        val policy = FolderPolicy(defaultAccountSlug = "work")
        FolderPolicyStorage(store).set("server1", "/repo", policy)
        assertEquals(policy, FolderPolicyStorage(store).get("server1", "/repo"))
    }

    @Test
    fun trailingSlash_isNormalized() {
        val (storage, _) = storage()
        val policy = FolderPolicy(defaultAccountSlug = "work")
        storage.set("server1", "/repo/", policy)
        assertEquals(policy, storage.get("server1", "/repo"))
        assertEquals(policy, storage.get("server1", "/repo/"))
    }

    @Test
    fun trailingSlash_setWithoutSlashIsReadableWithSlash() {
        val (storage, _) = storage()
        val policy = FolderPolicy(defaultAccountSlug = "work")
        storage.set("server1", "/repo", policy)
        assertEquals(policy, storage.get("server1", "/repo/"))
    }

    @Test
    fun rootFolder_singleSlash_isNotMangledToEmpty() {
        // normalize("/") must not collapse to "" (which would collide with an
        // empty-string folder key); it stays "/".
        val (storage, _) = storage()
        val policy = FolderPolicy(defaultAccountSlug = "work")
        storage.set("server1", "/", policy)
        assertEquals(policy, storage.get("server1", "/"))
    }

    @Test
    fun sameFolder_differentServers_doNotBleedIntoEachOther() {
        val (storage, _) = storage()
        val policyA = FolderPolicy(defaultAccountSlug = "work")
        val policyB = FolderPolicy(defaultAccountSlug = "personal")
        storage.set("serverA", "/repo", policyA)
        storage.set("serverB", "/repo", policyB)
        assertEquals(policyA, storage.get("serverA", "/repo"))
        assertEquals(policyB, storage.get("serverB", "/repo"))
    }

    @Test
    fun clear_removesEntry() {
        val (storage, _) = storage()
        storage.set("server1", "/repo", FolderPolicy(defaultAccountSlug = "work"))
        storage.clear("server1", "/repo")
        assertEquals(FolderPolicy(), storage.get("server1", "/repo"))
    }

    @Test
    fun clear_onUnsetFolder_isNoOp() {
        val (storage, _) = storage()
        // Must not throw, and must not fabricate an entry.
        storage.clear("server1", "/repo")
        assertEquals(FolderPolicy(), storage.get("server1", "/repo"))
        assertTrue(storage.forServer("server1").isEmpty())
    }

    @Test
    fun settingEmptyPolicy_removesRowRatherThanPersistingIt() {
        val (storage, _) = storage()
        storage.set("server1", "/repo", FolderPolicy(defaultAccountSlug = "work"))
        storage.set("server1", "/repo", FolderPolicy())
        assertEquals(emptyMap(), storage.forServer("server1"))
    }

    @Test
    fun corruptStoredBlob_degradesToEmptyInsteadOfThrowing() {
        val store = FakeKeyValueStore(mapOf("folder_policies" to "{not valid json"))
        val storage = FolderPolicyStorage(store)
        assertEquals(FolderPolicy(), storage.get("server1", "/repo"))
        assertEquals(emptyMap(), storage.forServer("server1"))
    }

    @Test
    fun forServer_returnsOnlyThatServersFolders() {
        val (storage, _) = storage()
        val policyA1 = FolderPolicy(defaultAccountSlug = "work")
        val policyA2 = FolderPolicy(allowedAccountSlugs = setOf("personal"))
        val policyB1 = FolderPolicy(defaultAccountSlug = "other")
        storage.set("serverA", "/repo1", policyA1)
        storage.set("serverA", "/repo2", policyA2)
        storage.set("serverB", "/repo1", policyB1)

        val forA = storage.forServer("serverA")
        assertEquals(2, forA.size)
        assertEquals(policyA1, forA["/repo1"])
        assertEquals(policyA2, forA["/repo2"])
    }

    @Test
    fun forServer_unknownServer_returnsEmptyMap() {
        val (storage, _) = storage()
        storage.set("serverA", "/repo", FolderPolicy(defaultAccountSlug = "work"))
        assertTrue(storage.forServer("serverB").isEmpty())
    }

    // --- folder spellings (the `kontexta` key that never matched) ---

    private val home = "/home/lucas"
    private val kontexta = FolderPolicy(
        defaultAccountSlug = "lukas-kontexta-cz",
        allowedAccountSlugs = setOf("lukas-kontexta-cz"),
    )

    /** The dev box's real folder-policies.json, imported the way App.kt does. */
    private fun withServerFile(): FolderPolicyStorage {
        val (storage, _) = storage()
        storage.importFromServer(
            "dev",
            """{"kontexta":{"defaultAccountSlug":"lukas-kontexta-cz","allowedAccountSlugs":["lukas-kontexta-cz"]}}""",
        )
        return storage
    }

    @Test
    fun relativeServerKey_matchesEveryCallersSpelling() {
        val storage = withServerFile()
        // ConnectScreen / recentFolders, TmuxNameParser (attach), server manifest
        // restore and history resume, and a hand-typed trailing slash.
        for (folder in listOf("~/kontexta", "kontexta", "/home/lucas/kontexta", "/home/lucas/kontexta/", "~/kontexta/")) {
            assertEquals(kontexta, storage.get("dev", folder, home), folder)
        }
    }

    @Test
    fun withoutHome_relativeKeyStillMatchesTildeAndRelativeSpellings() {
        val storage = withServerFile()
        assertEquals(kontexta, storage.get("dev", "~/kontexta"))
        assertEquals(kontexta, storage.get("dev", "kontexta"))
        // Unknowable without home: absolute spelling falls back to no policy.
        assertEquals(FolderPolicy(), storage.get("dev", "/home/lucas/kontexta"))
    }

    @Test
    fun absoluteKey_matchesTildeAndRelativeSpellings() {
        val (storage, _) = storage()
        storage.importFromServer("dev", """{"/home/lucas/kontexta":{"defaultAccountSlug":"lukas-kontexta-cz"}}""")
        for (folder in listOf("~/kontexta", "kontexta", "/home/lucas/kontexta")) {
            assertEquals("lukas-kontexta-cz", storage.get("dev", folder, home).defaultAccountSlug, folder)
        }
    }

    @Test
    fun subfoldersInheritButSiblingCheckoutsDoNot() {
        val storage = withServerFile()
        assertEquals(kontexta, storage.get("dev", "/home/lucas/kontexta/web", home))
        assertEquals(kontexta, storage.get("dev", "~/kontexta/.claude/worktrees/fix", home))
        assertEquals(FolderPolicy(), storage.get("dev", "/home/lucas/kontexta-deploy", home))
        assertEquals(FolderPolicy(), storage.get("dev", "kontexta-h42", home))
        assertEquals(FolderPolicy(), storage.get("dev", "~", home))
    }

    @Test
    fun mostSpecificPolicyWins() {
        val storage = withServerFile()
        storage.set("dev", "~/kontexta/web", FolderPolicy(defaultAccountSlug = "other"), home)
        assertEquals("other", storage.get("dev", "/home/lucas/kontexta/web/src", home).defaultAccountSlug)
        assertEquals(kontexta, storage.get("dev", "/home/lucas/kontexta/api", home))
    }

    @Test
    fun homePolicy_doesNotGovernEveryProject() {
        val (storage, _) = storage()
        storage.set("dev", "~", FolderPolicy(defaultAccountSlug = "personal"), home)
        assertEquals("personal", storage.get("dev", "/home/lucas", home).defaultAccountSlug)
        assertEquals(FolderPolicy(), storage.get("dev", "~/kontexta", home))
    }

    @Test
    fun set_inAnotherSpelling_collapsesIntoTheCanonicalKey() {
        val storage = withServerFile()
        val updated = FolderPolicy(defaultAccountSlug = "lukas-kontexta-cz")
        storage.set("dev", "/home/lucas/kontexta", updated, home)
        assertEquals(mapOf("~/kontexta" to updated), storage.forServer("dev"))
        assertEquals("""{"~/kontexta":{"defaultAccountSlug":"lukas-kontexta-cz","allowedAccountSlugs":[]}}""",
            storage.exportForServer("dev"))
    }

    // --- review 2026-11-07: tmux-derived folders and tied spellings ---

    @Test
    fun aTmuxDerivedSegmentIsNotReadAsHomeRelative() {
        val (storage, _) = storage()
        storage.importFromServer("dev", """{"~/work/kontexta":{"defaultAccountSlug":"work"}}""")
        // a tab attached from `claude-dev-kontexta` carries only `kontexta`
        assertEquals("work", storage.get("dev", "kontexta", home).defaultAccountSlug, "the one folder ending in it")
        storage.importFromServer(
            "dev",
            """{"~/work/kontexta":{"defaultAccountSlug":"work"},"~/kontexta":{"defaultAccountSlug":"home"}}""",
        )
        assertEquals(FolderPolicy(), storage.get("dev", "kontexta", home), "two folders end in it: neither rule, rather than the wrong one")
        assertEquals("home", storage.get("dev", "~/kontexta", home).defaultAccountSlug, "a real path still resolves")
    }

    @Test
    fun aTmuxDerivedSegmentMatchesTheTmuxSpellingOfTheFolder() {
        val (storage, _) = storage()
        storage.importFromServer("dev", """{"~/nekrachni.plus":{"defaultAccountSlug":"np"}}""")
        assertEquals("np", storage.get("dev", "nekrachni_plus", home).defaultAccountSlug)
        assertEquals("np", storage.get("dev", "nekrachni.plus", home).defaultAccountSlug, "a segment not yet in tmux's spelling")
    }

    @Test
    fun twoStoredSpellingsOfOneFolderResolveDeterministically() {
        val a = """"/home/lucas/kontexta":{"defaultAccountSlug":"abs"}"""
        val t = """"~/kontexta":{"defaultAccountSlug":"tilde"}"""
        for (blob in listOf("{$a,$t}", "{$t,$a}")) {
            val (storage, _) = storage()
            storage.importFromServer("dev", blob)
            assertEquals("tilde", storage.get("dev", "~/kontexta/web", home).defaultAccountSlug, blob)
            assertEquals(mapOf("~/kontexta" to FolderPolicy(defaultAccountSlug = "tilde")), storage.forServerCanonical("dev", home), blob)
        }
    }

    @Test
    fun set_newFolder_isStoredCanonically() {
        val (storage, _) = storage()
        storage.set("dev", "/home/lucas/music-seeker/", FolderPolicy(defaultAccountSlug = "work"), home)
        assertEquals(setOf("~/music-seeker"), storage.forServer("dev").keys)
    }

    @Test
    fun set_onlyTouchesTheSameFolder_notItsParentOrSiblings() {
        val storage = withServerFile()
        storage.set("dev", "~/kontexta-deploy", FolderPolicy(defaultAccountSlug = "work"), home)
        storage.set("dev", "~/kontexta/web", FolderPolicy(defaultAccountSlug = "web"), home)
        assertEquals(setOf("kontexta", "~/kontexta-deploy", "~/kontexta/web"), storage.forServer("dev").keys)
    }

    @Test
    fun clear_inAnotherSpelling_removesTheStoredKey() {
        val storage = withServerFile()
        storage.clear("dev", "~/kontexta/", home)
        assertTrue(storage.forServer("dev").isEmpty())
    }

    @Test
    fun clear_ofASubfolder_leavesTheParentPolicy() {
        val storage = withServerFile()
        storage.clear("dev", "~/kontexta/web", home)
        assertEquals(setOf("kontexta"), storage.forServer("dev").keys)
    }

    @Test
    fun set_emptyPolicy_inAnotherSpelling_removesTheRow() {
        val storage = withServerFile()
        storage.set("dev", "/home/lucas/kontexta", FolderPolicy(), home)
        assertTrue(storage.forServer("dev").isEmpty())
    }
}
