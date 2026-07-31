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
}
