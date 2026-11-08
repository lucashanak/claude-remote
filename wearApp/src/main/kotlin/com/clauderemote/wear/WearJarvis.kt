package com.clauderemote.wear

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/*
 * The watch as a Jarvis remote (2026-11-08). Wire models mirror shared's `session/wear/WatchJarvis.kt` (the phone's
 * PhoneWearService encodes those); keep the two in sync when the shape changes. Every field the phone may add later
 * is ignored (ignoreUnknownKeys), every field it may leave out has a default.
 */

/** Mirror of WatchProposal: one card of the Čeká page. Approving needs [fullText] and the whole [text] scrolled. */
@Serializable
data class WearJarvisProposal(
    val id: String,
    val kind: String,
    val heading: String,
    val title: String? = null,
    val text: String,
    val fullText: Boolean = false,
    val approveLabel: String = "Provést",
    /** Relative to the phone's read, by the SERVER's clock; null = unknown. */
    val expiresInMs: Long? = null,
)

@Serializable
data class WearJarvisProposals(val ok: Boolean, val error: String? = null, val items: List<WearJarvisProposal> = emptyList())

@Serializable
data class WearJarvisDecideRequest(val id: String, val approve: Boolean, val textHash: Int = 0)

@Serializable
data class WearJarvisDecideResult(val id: String, val ok: Boolean, val text: String)

@Serializable
data class WearJarvisNews(val ok: Boolean, val text: String)

/**
 * What the phone answered to the open app's Jarvis requests (in-app MessageClient listener, see MainActivity): the
 * proposals with the elapsed-realtime moment they arrived (expiry is judged against it, never the wall clock), the
 * last decision's sentence, the last news.
 */
object JarvisWatchStore {
    data class Proposals(val items: List<WearJarvisProposal>, val atElapsed: Long, val error: String?)

    val proposals = MutableStateFlow<Proposals?>(null)
    val proposalsLoading = MutableStateFlow(false)
    /** Proposal ids whose decision is on its way. */
    val deciding = MutableStateFlow<Set<String>>(emptySet())
    val notice = MutableStateFlow<String?>(null)
    val news = MutableStateFlow<WearJarvisNews?>(null)
    val newsLoading = MutableStateFlow(false)

    private val JSON = Json { ignoreUnknownKeys = true }

    fun expired(p: WearJarvisProposal, atElapsed: Long, nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean =
        p.expiresInMs != null && nowElapsed - atElapsed >= p.expiresInMs

    /** One phone answer on [path]; true = it was one of ours. */
    fun onMessage(path: String, data: ByteArray): Boolean {
        val text = String(data, Charsets.UTF_8)
        when (path) {
            PATH_PROPOSALS -> {
                val r = runCatching { JSON.decodeFromString<WearJarvisProposals>(text) }.getOrNull() ?: return true
                proposalsLoading.value = false
                // A failed read keeps the cards already shown (with the reason): "nejde načíst" is not "nic nečeká".
                val prev = proposals.value
                proposals.value = if (r.ok) {
                    Proposals(r.items, SystemClock.elapsedRealtime(), null)
                } else {
                    Proposals(prev?.items.orEmpty(), prev?.atElapsed ?: 0L, r.error ?: "Návrhy nejdou načíst")
                }
            }
            PATH_DECIDE_RESULT -> {
                val r = runCatching { JSON.decodeFromString<WearJarvisDecideResult>(text) }.getOrNull() ?: return true
                deciding.value = deciding.value - r.id
                notice.value = r.text
            }
            PATH_NEWS -> {
                val r = runCatching { JSON.decodeFromString<WearJarvisNews>(text) }.getOrNull() ?: return true
                newsLoading.value = false
                news.value = r
            }
            else -> return false
        }
        return true
    }

    const val PATH_PROPOSALS = "/jarvis-proposals"
    const val PATH_DECIDE_RESULT = "/jarvis-decide-result"
    const val PATH_NEWS = "/jarvis-news"
}

/**
 * Jarvis' recent alerts as the wrist last saw them (≤ 5, newest first), persisted: the push carries only the pending
 * ones, so once Lukáš has seen them on the phone the Jarvis page and the tile would otherwise go blank. Merged by id.
 */
object JarvisRecentStore {
    const val MAX = 5
    private const val PREFS = "jarvis_watch"
    private const val KEY = "recent_json"
    private val JSON = Json { ignoreUnknownKeys = true }

    private val _recent = MutableStateFlow<List<WearJarvisRecent>?>(null)
    val recent: StateFlow<List<WearJarvisRecent>?> = _recent

    fun merge(old: List<WearJarvisRecent>, incoming: List<WearJarvisRecent>): List<WearJarvisRecent> =
        (incoming + old).distinctBy { it.id }.sortedByDescending { it.atMs }.take(MAX)

    fun load(context: Context): List<WearJarvisRecent> {
        _recent.value?.let { return it }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val list = raw?.let { runCatching { JSON.decodeFromString<List<WearJarvisRecent>>(it) }.getOrNull() }.orEmpty()
        _recent.value = list
        return list
    }

    fun update(context: Context, incoming: List<WearJarvisRecent>) {
        if (incoming.isEmpty()) {
            load(context)
            return
        }
        val next = merge(load(context), incoming)
        if (next == _recent.value) return
        _recent.value = next
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, JSON.encodeToString(next)).apply()
        }
    }

    /** "▲", "✓", "⚠", "•" — the glyphs of the phone's merged notification. */
    fun glyph(kind: String): String = when (kind) {
        "block" -> "▲"
        "incident" -> "⚠"
        "done" -> "✓"
        else -> "•"
    }
}
