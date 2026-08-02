package com.clauderemote.storage

import com.clauderemote.ui.theme.CRAccent

/**
 * Per-account chip colour, keyed by account slug.
 *
 * An unset account is NOT left uncoloured: [colorFor] derives a stable colour
 * from the slug, so several accounts are distinguishable the moment they exist
 * rather than only after someone configures them. Choosing a colour just pins
 * one over that default.
 */
class AccountColorStorage(private val prefs: KeyValueStore) {

    /** The explicitly chosen colour for [slug], or null when it's still automatic. */
    fun chosen(slug: String): CRAccent? {
        val name = load()[slug] ?: return null
        return CRAccent.entries.firstOrNull { it.name == name }
    }

    /** Pin a colour, or pass null to fall back to the derived one. */
    fun set(slug: String, accent: CRAccent?) {
        val map = load().toMutableMap()
        if (accent == null) map.remove(slug) else map[slug] = accent.name
        save(map)
    }

    /** Colour to actually paint [slug] with: the pinned one, else derived. */
    fun colorFor(slug: String): CRAccent = chosen(slug) ?: derivedColorFor(slug)

    /**
     * Colours for a whole account list, guaranteeing they DIFFER while there are
     * fewer accounts than palette entries.
     *
     * Per-slug derivation alone isn't enough: hashing three real slugs into five
     * colours collided in practice, and two identically-coloured chips defeat the
     * point of colouring them. Pinned choices are honoured as-is (the user's
     * intent wins, even if they pin two accounts the same); the rest are filled
     * from the unused colours in list order.
     */
    fun assign(slugs: List<String>): Map<String, CRAccent> {
        val out = LinkedHashMap<String, CRAccent>()
        val taken = mutableSetOf<CRAccent>()
        for (s in slugs) chosen(s)?.let { out[s] = it; taken += it }
        var cursor = 0
        for (s in slugs) {
            if (s in out) continue
            val free = CRAccent.entries.firstOrNull { it !in taken }
            val pick = free ?: CRAccent.entries[cursor++ % CRAccent.entries.size]
            out[s] = pick
            taken += pick
        }
        return out
    }

    private fun load(): Map<String, String> {
        val raw = prefs.getString(KEY, "")
        if (raw.isBlank()) return emptyMap()
        // Stored as slug=ACCENT pairs joined by \n. The slug charset excludes
        // both separators, so this needs no escaping and no JSON dependency.
        return raw.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
    }

    private fun save(map: Map<String, String>) {
        prefs.putString(KEY, map.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    companion object {
        private const val KEY = "account_colors"

        /**
         * Stable colour for a slug, so two accounts don't both come up in the
         * theme accent and look identical. Deterministic (same slug, same colour
         * on every device) and never throws for an empty slug.
         */
        fun derivedColorFor(slug: String): CRAccent {
            if (slug.isEmpty()) return CRAccent.entries.first()
            var h = 0
            for (ch in slug) h = h * 31 + ch.code
            val idx = ((h % CRAccent.entries.size) + CRAccent.entries.size) % CRAccent.entries.size
            return CRAccent.entries[idx]
        }
    }
}
