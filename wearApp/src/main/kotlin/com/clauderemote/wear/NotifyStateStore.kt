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
 * Anti-burst vlastnost zůstává: neznámý baseline (session, kterou jsme
 * nikdy neviděli) se pořád přeskakuje — nově ale "nikdy neviděli" znamená
 * opravdu nikdy, ne "od posledního startu procesu".
 */
@Serializable
data class NotifyBaseline(
    // --- CO JSME VIDĚLI -------------------------------------------------
    // Poslední aktivita, kterou pro tuhle session přinesl push. Slouží k
    // rozpoznání hrany (WORKING -> WAITING, WAITING -> APPROVAL, odchod z
    // čekání) a k DISCONNECTED guardu.
    val activity: String = "",

    // --- NA CO JSME REAGOVALI -------------------------------------------
    // Tyhle se posouvají VÝHRADNĚ ve chvíli, kdy se opravdu vyvěsí
    // notifikace. Držet je odděleně od "viděli jsme" je celý point: dokud
    // se baseline posouval i na průchodech, které nic nevyvěsily, spolkl
    // urgentní push, který přišel ještě před překlopením aktivity, hranu
    // `lastMessageAt` — a následující push s už překlopenou aktivitou pak
    // vypadal jako "zpráva se nepohnula" a notifikace se zahodila.
    // `notifiedMessageAt == 0` znamená "na tuhle session jsme ještě nikdy
    // neupozornili" — od jakéhokoli skutečného razítka se to liší, takže
    // žádný zvláštní příznak "už notifikováno" není potřeba (a nesmí být:
    // výjimka pro nenotifikované session by pustila zastaralý text).
    val notifiedMessageAt: Long = 0,
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
     * Vedlejší efekt: session, která zmizí a vrátí se, má zase neznámý
     * baseline, takže se pro ni nenotifikuje "zpětně" — což je záměr.
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
