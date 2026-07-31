package com.clauderemote.storage

import com.clauderemote.model.FolderPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Serializable mirror of [FolderPolicy]. [FolderPolicy] itself lives in `model/`
 * and is intentionally not `@Serializable` (that module has no kotlinx.serialization
 * dependency to pull in), so this DTO is the only thing that touches JSON.
 */
@Serializable
private data class FolderPolicyDto(
    val defaultAccountSlug: String? = null,
    val allowedAccountSlugs: Set<String> = emptySet(),
) {
    fun toDomain(): FolderPolicy = FolderPolicy(defaultAccountSlug, allowedAccountSlugs)

    companion object {
        fun fromDomain(policy: FolderPolicy): FolderPolicyDto =
            FolderPolicyDto(policy.defaultAccountSlug, policy.allowedAccountSlugs)
    }
}

/**
 * Per-(server, folder) [FolderPolicy] storage. Persisted as ONE serialized JSON
 * blob under a single prefs key (matching [SessionStorage]'s approach) rather
 * than one key per folder, so [forServer] can list every configured folder
 * without having to enumerate prefs keys (which [KeyValueStore] can't do).
 *
 * Keyed on `"$serverId::$folder"` rather than a NUL-joined string — a NUL byte
 * survives round-tripping through neither Android SharedPreferences XML nor the
 * desktop java.util.Properties file, so it corrupts the blob instead of acting
 * as a safe separator.
 */
class FolderPolicyStorage(private val prefs: KeyValueStore) {

    // Same tolerance stance as SessionStorage: ignoreUnknownKeys + coerceInputValues
    // so a field added by a newer build doesn't brick every stored policy on an
    // older one, and vice versa.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

    fun get(serverId: String, folder: String): FolderPolicy =
        load()[compositeKey(serverId, folder)]?.toDomain() ?: FolderPolicy()

    fun set(serverId: String, folder: String, policy: FolderPolicy) {
        val key = compositeKey(serverId, folder)
        val map = load().toMutableMap()
        if (policy.isEmpty) {
            map.remove(key)
        } else {
            map[key] = FolderPolicyDto.fromDomain(policy)
        }
        save(map)
    }

    fun clear(serverId: String, folder: String) {
        val map = load().toMutableMap()
        if (map.remove(compositeKey(serverId, folder)) != null) save(map)
    }

    /** Every folder configured for [serverId], keyed by the (normalised) folder path. */
    fun forServer(serverId: String): Map<String, FolderPolicy> {
        val prefix = "$serverId$SEPARATOR"
        return load()
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { (key, _) -> key.removePrefix(prefix) }
            .mapValues { (_, dto) -> dto.toDomain() }
    }

    private fun load(): Map<String, FolderPolicyDto> {
        val raw = prefs.getString(KEY_POLICIES, "{}")
        return try {
            json.decodeFromString<Map<String, FolderPolicyDto>>(raw)
        } catch (e: Exception) {
            // Corrupt blob — degrade to empty rather than throwing, matching
            // SessionStorage's fail-soft stance on unparseable stored JSON.
            println("FolderPolicyStorage: failed to decode stored policies, resetting (${e.message})")
            emptyMap()
        }
    }

    private fun save(map: Map<String, FolderPolicyDto>) {
        prefs.putString(KEY_POLICIES, json.encodeToString(map))
    }

    companion object {
        private const val KEY_POLICIES = "folder_policies"
        private const val SEPARATOR = "::"

        /**
         * Trims a trailing `/` so `/repo` and `/repo/` key the same policy. Deliberately
         * does NOT expand `~` — the app stores folders exactly as the user typed them,
         * and expanding here would desync from how they're stored/compared elsewhere.
         */
        private fun normalize(folder: String): String =
            folder.removeSuffix("/").ifEmpty { folder }

        private fun compositeKey(serverId: String, folder: String): String =
            "$serverId$SEPARATOR${normalize(folder)}"
    }
}
