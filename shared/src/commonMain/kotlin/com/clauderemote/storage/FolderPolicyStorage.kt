package com.clauderemote.storage

import com.clauderemote.model.FolderPolicy
import com.clauderemote.model.TmuxNameParser
import com.clauderemote.model.canonicalPolicyFolder
import com.clauderemote.model.policyCoversFolder
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

    /**
     * The policy for a session in [folder]: the one keyed on that folder, or on
     * the nearest folder above it (see [policyCoversFolder]). Keys and [folder]
     * are both compared in [canonicalPolicyFolder] form, so `kontexta`,
     * `~/kontexta` and `/home/lucas/kontexta` find the same policy whichever
     * spelling stored it — the server file is matched as written, not rewritten.
     */
    fun get(serverId: String, folder: String, home: String? = null): FolderPolicy {
        val target = canonicalPolicyFolder(folder, home)
        val prefix = "$serverId$SEPARATOR"
        val rules = load().filterKeys { it.startsWith(prefix) }
            .map { (key, dto) -> PolicyRow(key.removePrefix(prefix), canonicalPolicyFolder(key.removePrefix(prefix), home), dto) }
        tmuxSegment(folder)?.let { return lossyMatch(rules, it) }
        return rules
            .filter { policyCoversFolder(it.canonical, target) }
            // Most specific wins: a rule on `~/repo/sub` overrides one on `~/repo`. Two
            // spellings of one folder tie on length: the canonical spelling first, then the
            // stored text, so the answer never depends on the map's order.
            .sortedWith(ROW_ORDER)
            .firstOrNull()?.dto?.toDomain() ?: FolderPolicy()
    }

    private class PolicyRow(val stored: String, val canonical: String, val dto: FolderPolicyDto)

    /**
     * The folder of a tab attached from a tmux name is a bare last segment (TmuxNameParser:
     * `.` rewritten to `_`, possibly cut short), not a path: `~/work/kontexta` arrives as
     * `kontexta`, and reading it as home-relative applied `~/kontexta`'s rule. null for a path.
     * (A STORED bare key is the user's own spelling of `~/<key>` and stays home-relative.)
     */
    private fun tmuxSegment(folder: String): String? {
        val p = folder.trim().trimEnd('/')
        return p.takeIf { it.isNotEmpty() && '/' !in it && !it.startsWith("~") && it != "." }
    }

    /**
     * A tmux-derived [segment]: the rule whose folder ends in that segment (as tmux spells it),
     * but only when exactly one folder does. Two candidates (`~/kontexta` and
     * `~/work/kontexta`) cannot be told apart from the segment, so neither applies rather than
     * the wrong one.
     */
    private fun lossyMatch(rules: List<PolicyRow>, segment: String): FolderPolicy {
        val fits = rules.filter {
            TmuxNameParser.sanitize(it.canonical.trimEnd('/').substringAfterLast('/')) == segment
        }
        val folders = fits.map { it.canonical }.distinct()
        if (folders.size != 1) return FolderPolicy()
        return fits.sortedWith(ROW_ORDER).first().dto.toDomain()
    }

    /**
     * Stores [policy] for [folder] under its canonical spelling. Keys that name
     * the same folder in another spelling are removed, so the file never holds
     * two rules for one folder.
     */
    fun set(serverId: String, folder: String, policy: FolderPolicy, home: String? = null) {
        val map = load().toMutableMap()
        val same = sameFolderKeys(map.keys, serverId, folder, home)
        // Every spelling of the folder collapses into the canonical one: the rule just set
        // replaces them all, and which spelling survived used to depend on the map's order.
        val key = "$serverId$SEPARATOR${canonicalPolicyFolder(folder, home)}"
        same.forEach { map.remove(it) }
        if (!policy.isEmpty) map[key] = FolderPolicyDto.fromDomain(policy)
        save(map)
    }

    fun clear(serverId: String, folder: String, home: String? = null) {
        val map = load().toMutableMap()
        val same = sameFolderKeys(map.keys, serverId, folder, home)
        if (same.isNotEmpty()) {
            same.forEach { map.remove(it) }
            save(map)
        }
    }

    /** Stored keys of [serverId] that name exactly [folder] (not its parents). */
    private fun sameFolderKeys(keys: Set<String>, serverId: String, folder: String, home: String?): List<String> {
        val target = canonicalPolicyFolder(folder, home)
        val prefix = "$serverId$SEPARATOR"
        return keys.filter { it.startsWith(prefix) && canonicalPolicyFolder(it.removePrefix(prefix), home) == target }
    }

    /**
     * Every folder configured for [serverId], one row per folder in canonical form: the rule
     * [get] would use for it (the canonical spelling first when two spellings are stored).
     */
    fun forServerCanonical(serverId: String, home: String?): Map<String, FolderPolicy> {
        val prefix = "$serverId$SEPARATOR"
        return load().filterKeys { it.startsWith(prefix) }
            .map { (key, dto) -> PolicyRow(key.removePrefix(prefix), canonicalPolicyFolder(key.removePrefix(prefix), home), dto) }
            .groupBy { it.canonical }
            .mapValues { (_, rows) -> rows.sortedWith(ROW_ORDER).first().dto.toDomain() }
    }

    /** Every folder configured for [serverId], keyed as stored (any spelling, see [get]). */
    fun forServer(serverId: String): Map<String, FolderPolicy> {
        val prefix = "$serverId$SEPARATOR"
        return load()
            .filterKeys { it.startsWith(prefix) }
            .mapKeys { (key, _) -> key.removePrefix(prefix) }
            .mapValues { (_, dto) -> dto.toDomain() }
    }

    /**
     * This server's policies as the JSON that lives on the server. The store
     * itself is only a local CACHE — the shared copy is server-side so every
     * client agrees, which per-device preferences could never do.
     */
    fun exportForServer(serverId: String): String {
        val prefix = "$serverId$SEPARATOR"
        val mine = load().filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
        return json.encodeToString(mine)
    }

    /** Replace this server's cached policies with the server's copy. */
    fun importFromServer(serverId: String, raw: String) {
        val incoming = try {
            json.decodeFromString<Map<String, FolderPolicyDto>>(raw)
        } catch (e: Exception) {
            println("FolderPolicyStorage: server blob unparseable, keeping local (${e.message})")
            return
        }
        val prefix = "$serverId$SEPARATOR"
        val others = load().filterKeys { !it.startsWith(prefix) }
        save(others + incoming.mapKeys { prefix + it.key })
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
        /** Longest folder first; then the canonical spelling, then the stored text. */
        private val ROW_ORDER: Comparator<PolicyRow> = compareByDescending<PolicyRow> { it.canonical.length }
            .thenByDescending { it.stored == it.canonical }
            .thenBy { it.stored }

        private const val KEY_POLICIES = "folder_policies"
        private const val SEPARATOR = "::"
    }
}
