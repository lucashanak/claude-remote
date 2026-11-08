package com.clauderemote.wear

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Co jsme o každé session naposledy VIDĚLI a na co jsme REAGOVALI.
 *
 * [WearDataListenerService] rozhoduje o notifikaci porovnáním nového pushe
 * s předchozím stavem. Ten dosud žil jen v paměti ([SessionRepository]), a
 * protože Wear OS proces listeneru rutinně zabíjí, po každém restartu byl
 * baseline prázdný — a prázdný baseline se (správně, kvůli anti-burst
 * pravidlu) přeskakuje. Výsledek: session dokončí ve chvíli, kdy proces
 * neběžel, a uživateli nepřijde NIC (DataItem dedup zajistí, že další
 * rutinní push nese stejné bajty, takže se to už nikdy nespraví).
 *
 * Persistujeme proto baseline do SharedPreferences po každém průchodu.
 * Anti-burst vlastnost zůstává: session, kterou jsme nikdy neviděli, se
 * bere jako už ohlášená — nově ale "nikdy neviděli" znamená opravdu nikdy,
 * ne "od posledního startu procesu". Rozhoduje [WatchNotifyDecision].
 */
@Serializable
data class NotifyBaseline(
    // --- CO JSME VIDĚLI -------------------------------------------------
    // Poslední aktivita, kterou pro tuhle session přinesl push: odchod ze
    // schvalování ruší notifikaci, návrat téhož dialogu ji ukáže potichu.
    val activity: String = "",

    // --- NA CO JSME REAGOVALI -------------------------------------------
    // Identita dialogu (PromptFingerprint z telefonu), na který jsme naposledy
    // upozornili. Nový dialog = jiná identita = zabzučet; tentýž dialog
    // znovu (reconnect, znovu doručený DataItem, restart procesu) nikdy.
    // Starší záznamy ho nemají (null), takže dialog, který po aktualizaci
    // hodinek pořád čeká, se ohlásí jednou znovu – vědomě, je akční.
    val notifiedPrompt: String? = null,
    val notifiedBodyHash: Int = 0,
    // Hash textu, který pro tuhle session naposledy "platil za vyřízený" z
    // pohledu předčítání. Posouvá se s KAŽDOU vyvěšenou notifikací, ne jen
    // když se opravdu četlo nahlas — jinak by zapnutí předčítání přečetlo
    // zprávu, kterou už uživatel dávno viděl.
    val spokenHash: Int = 0,
)

object NotifyStateStore {
    private const val PREFS = "wear_notify_state"
    private const val KEY_BASELINE = "baseline_json"
    private const val TAG = "NotifyStateStore"

    private val JSON = Json { ignoreUnknownKeys = true }

    fun load(context: Context): Map<String, NotifyBaseline> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASELINE, null) ?: return emptyMap()
        return runCatching { JSON.decodeFromString<Map<String, NotifyBaseline>>(raw) }
            .onFailure { e -> WearLog.w(context, TAG, "baseline decode failed: ${e.message}") }
            .getOrDefault(emptyMap())
    }

    /**
     * Zapisuje se celá mapa (ne inkrementálně) a jen pro session, které v
     * aktuálním pushi existují — tím se sama ořezává a neroste donekonečna.
     * Session, která z pushe zmizí, si ještě nechá zrušit notifikaci a pak se
     * zapomene; když se vrátí, bere se jako nikdy neviděná.
     *
     * Volající zapisuje jen při skutečné změně (viz [WearDataListenerService]):
     * naprostá většina pushů baseline nemění a re-encode celé mapy plus
     * `apply()` na každý data event je na hodinkách zbytečná zátěž.
     */
    fun save(context: Context, baseline: Map<String, NotifyBaseline>) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_BASELINE, JSON.encodeToString(baseline))
                .apply()
        }.onFailure { e -> WearLog.w(context, TAG, "baseline save failed: ${e.message}") }
    }
}
