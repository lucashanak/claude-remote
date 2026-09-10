package com.clauderemote.wear

import android.app.NotificationManager
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Receives the phone's /sessions Data Layer pushes (WearSync.push()),
 * updates [SessionRepository], and — the actual point of the watch app —
 * reacts the instant a session flips to WAITING_FOR_INPUT/APPROVAL_NEEDED:
 *   - posts an actionable [WearNotifier] notification (Y/N or inline reply)
 *     so the user can answer straight from the wrist without opening the app;
 *   - additionally speaks the message ALOUD when read-aloud is enabled.
 * Neither is gated behind opening the app — that would be strictly worse
 * than the phone notification tier this is meant to improve on.
 */
class WearDataListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // Logged unconditionally (not just on failure) — diagnosing the
        // watch side was blocked by having zero visibility into whether
        // this even gets invoked at all vs. invoked-but-silently-fine.
        WearLog.i(this, TAG, "onDataChanged: ${dataEvents.count} event(s)")
        for (event in dataEvents) {
            WearLog.i(this, TAG, "event type=${event.type} path=${event.dataItem.uri.path}")
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != PATH) continue
            runCatching {
                val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(KEY_JSON) ?: return@runCatching
                val payload = WEAR_JSON.decodeFromString<WearSessionsPayload>(json)
                SonioxKeyStore.update(payload.sonioxApiKey, payload.sonioxVoice, payload.ttsSpeedPct, payload.dictationSilenceMs)
                val previousById = SessionRepository.sessions.value.associateBy { it.id }
                SessionRepository.update(payload.sessions)
                WearLog.i(this, TAG, "Updated repository with ${payload.sessions.size} sessions")
                requestTileUpdate()
                handleTransitions(payload.sessions, previousById)
            }.onFailure { e -> WearLog.w(this, TAG, "Failed to parse /sessions payload: ${e.message}") }
        }
        dataEvents.release()
    }

    private fun handleTransitions(
        sessions: List<WearSessionInfo>,
        previousById: Map<String, WearSessionInfo>,
    ) {
        WearNotifier.ensureChannels(this)
        // TTS stays gated behind the read-aloud toggle + Do Not Disturb.
        // Notifications do NOT — the OS filters those itself by channel
        // importance under DND, and read-aloud is a separate opt-in from
        // "let me act on this from a notification".
        val autoSpeakOn = AutoSpeakPrefs.isEnabled(this)
        val dnd = isDoNotDisturb()
        // Persistovaný baseline. Wear OS proces listeneru rutinně zabíjí, a
        // dokud baseline žil jen v paměti, znamenal každý restart "žádnou
        // session neznám" → všechny se přeskočily → dokončení, které padlo do
        // okna bez procesu, se neohlásilo VŮBEC (a DataItem dedup zajistil,
        // že další rutinní push nese stejné bajty, takže se to nespravilo).
        //
        // Aktivitu ("co jsme viděli") bereme z in-memory snapshotu, když je —
        // je čerstvější. Reagovali-jsme půlku záznamu (notified*, spokenHash)
        // ale VŽDYCKY z disku: to je stav session, ne stav procesu.
        val stored = NotifyStateStore.load(this)
        val seedFromDisk = previousById.isEmpty()
        val nextBaseline = HashMap<String, NotifyBaseline>(sessions.size)
        WearLog.i(
            this, TAG,
            "handleTransitions: autoSpeak=$autoSpeakOn dnd=$dnd sessions=${sessions.size} " +
                "seedFromDisk=$seedFromDisk stored=${stored.size}",
        )
        for (session in sessions) {
            val storedRec = stored[session.id]
            val bodyNow = WearNotifier.bodyFor(session)
            // Předčítá se plný text, ne shrnutí; `notifyBody` má přednost,
            // protože je to tělo pro PRÁVĚ dokončený tah (`lastMessage` je
            // jen snapshot v okamžiku pushe).
            val spokenText = session.notifyBody?.takeIf { it.isNotBlank() }
                ?: session.lastMessage?.takeIf { it.isNotBlank() }

            // No prior record at all (first onDataChanged after this
            // process started, e.g. app restart/update/reboot) — we don't
            // know whether this session has been sitting there waiting for
            // hours or just flipped. Treating "unknown" as "wasn't
            // notify-worthy" made EVERY already-waiting session look like a
            // fresh transition on every process restart, reading out a pile
            // of stale messages (and now posting a pile of stale
            // notifications) that hadn't actually changed. Skip instead;
            // only a genuinely observed transition (previous state known
            // and different) should act. "Neznámý" teď ale znamená opravdu
            // nikdy neviděný — po restartu procesu se baseline seeduje z
            // [NotifyStateStore], takže tohle přeskočení už nespolkne
            // dokončení, které padlo do okna, kdy proces neběžel.
            val previous: NotifyBaseline? = if (seedFromDisk) storedRec else {
                previousById[session.id]?.let { prev ->
                    (storedRec ?: NotifyBaseline()).copy(activity = prev.activity)
                }
            }

            // Výchozí zápis pro tenhle průchod: posuň JEN "co jsme viděli",
            // reagovali-jsme půlku nech beze změny. Přepíše se níž jedině
            // tehdy, když se opravdu vyvěsí notifikace.
            nextBaseline[session.id] =
                (previous ?: storedRec ?: NotifyBaseline()).copy(activity = session.activity)

            if (previous == null) continue

            // DISCONNECTED (either end of the transition) is a visibility
            // change, not a real state change: when the phone loses its link
            // it marks every session DISCONNECTED, and on reconnect they ALL
            // re-report their true state at once. Acting on "DISCONNECTED ->
            // WAITING_FOR_INPUT" posted a burst of notifications on every
            // reconnect for sessions that were already waiting, not freshly
            // waiting (observed: 8 in ~7s). Treat DISCONNECTED like an unknown
            // baseline — never notify OUT of it (we didn't observe the real
            // moment it started waiting) and never cancel INTO it (a transient
            // phone-side drop doesn't mean the session stopped waiting).
            if (previous.activity == "DISCONNECTED" || session.activity == "DISCONNECTED") continue

            val wasNotifyWorthy = previous.activity.isNotifyWorthy()
            val nowNotifyWorthy = session.activity.isNotifyWorthy()

            // Session finished waiting (answered from the phone, or moved on)
            // — clear its notification so no "ghost" prompt lingers on the wrist.
            if (wasNotifyWorthy && !nowNotifyWorthy) {
                WearNotifier.cancelSession(this, session.id)
                continue
            }
            if (!nowNotifyWorthy) continue

            // Posunula se ZPRÁVA proti té, na kterou jsme NAPOSLEDY UPOZORNILI?
            // `lastMessageAt` telefon razítkuje jen když se text proti minulému
            // pushi liší, takže je to přesně ten signál, který tu chyběl: bez
            // něj stačilo k notifikaci samotné překlopení aktivity, a to se
            // děje i na reconnectu a z parsování OMC statusline, kde za ním
            // žádný nový tah není (zápěstí pak bzučelo s odpovědí starou
            // desítky minut až hodin — naměřeno 34 ze 127 notifikací).
            //
            // Porovnává se PROTI POSLEDNÍ NOTIFIKACI, ne proti poslednímu
            // vidění. Telefon totiž posílá urgentní push ještě před
            // překlopením aktivity, takže hranu `lastMessageAt` viděl často
            // už průchod, který nic nevyvěsil — a ten druhý, s už překlopenou
            // aktivitou, pak vypadal jako "nic nového" a dokončení se ztratilo.
            //
            // Session, na kterou jsme ještě nikdy neupozornili, má
            // `notifiedMessageAt == 0`, což se od jakéhokoli skutečného
            // razítka liší — hranu tedy nese samo porovnání a žádná výjimka
            // pro "ještě nenotifikováno" tu být NESMÍ: ta by po restartu
            // telefonu pustila první překlopení každé session s textem
            // starým klidně hodiny, tedy přesně ten symptom, kvůli kterému
            // gate vznikl.
            //
            // Zbytkové riziko: nová odpověď znak po znaku shodná s předchozí
            // dostane od telefonu stejné `lastMessageAt`, takže se pro ni
            // znovu nenotifikuje. Vědomý kompromis — falešný alert se starým
            // textem je horší než chybějící alert u odpovědi, kterou uživatel
            // právě viděl.
            val messageAdvanced = session.lastMessageAt != previous.notifiedMessageAt
            val bodyChanged = bodyNow.hashCode() != previous.notifiedBodyHash
            val activityChanged = session.activity != previous.activity
            // Vstup do schvalování je ŽÁDOST O AKCI, ne zpráva: detektor ji
            // hlásí z obrazovky, telefon k ní žádný nový text asistenta
            // nevyrábí, takže `lastMessageAt` se nepohne a gate výše by ji
            // zahodil — uživatel by na zápěstí nedostal Ano/Ne vůbec. Druhý a
            // další prompt v jednom tahu je takhle deterministicky ztracený.
            // Anti-burst se tím neruší: reconnect je pokrytý DISCONNECTED
            // guardem výš a tím, že telefon po reconnectu už netvrdí
            // WAITING_FOR_INPUT.
            val enteringApproval = session.activity == "APPROVAL_NEEDED" && previous.activity != "APPROVAL_NEEDED"
            // Druhý a další prompt v jednom tahu: aktivita zůstává
            // APPROVAL_NEEDED, mění se jen zpráva. Je to plnohodnotná nová
            // žádost o akci, ne oprava textu předchozí — musí vyvěsit a
            // zabzučet i tehdy, když na zápěstí nic nevisí.
            val isNewRequest = session.activity == "APPROVAL_NEEDED" && messageAdvanced

            if (!wasNotifyWorthy) {
                if (!enteringApproval && !messageAdvanced) {
                    WearLog.i(
                        this, TAG,
                        "skip stale flip for ${session.id}: ${previous.activity} -> ${session.activity} " +
                            "but lastMessageAt unchanged since last notify (${session.lastMessageAt})",
                    )
                    continue
                }
            } else {
                // Session už čekala. Dřív se tady bezpodmínečně pokračovalo
                // dál (`if (wasNotifyWorthy) continue`), takže pozdější push
                // s OPRAVENÝM textem nebo s LLM shrnutím notifikaci nikdy
                // nepřepsal a zápěstí zůstalo na starém těle až do konce
                // tahu. Přepiš ji, když se změnilo něco, co uživatel vidí —
                // ale jen dokud na zápěstí opravdu VISÍ. Tichá oprava
                // (setOnlyAlertOnce) je tichá jen vůči už zobrazené
                // notifikaci; kdyby ji uživatel mezitím smetl nebo z ní
                // odpověděl, znovuvyvěšení by zabzučelo znovu za něco, co má
                // dávno vyřízené. Změna aktivity je výjimka — to je nový
                // požadavek, ne oprava textu.
                // Test na "visí to ještě?" platí JEN pro opravu. Nová
                // žádost (změna aktivity nebo další schvalovací prompt) se
                // musí vyvěsit i na prázdné zápěstí — jinak by odpovězený
                // prompt #1 zrušil notifikaci a promlčel prompt #2.
                if (!activityChanged && !isNewRequest) {
                    if (!messageAdvanced && !bodyChanged) continue
                    if (!WearNotifier.isShowing(this, session.id)) {
                        WearLog.i(
                            this, TAG,
                            "skip repair for ${session.id}: notification no longer active",
                        )
                        continue
                    }
                }
            }

            // Každá NOVÁ žádost o schválení musí zabzučet — nejen příchod do
            // APPROVAL_NEEDED z čekání, ale i druhý a další prompt v jednom
            // tahu, kdy aktivita zůstává APPROVAL_NEEDED a pohne se jen
            // zpráva. Tichá aktualizace by z něj udělala jen přepsaný text
            // pod už odbytou notifikací. Opačný směr (APPROVAL -> WAITING,
            // telefon degraduje aktivitu při přepnutí tabu) i čistá oprava
            // těla se propíšou tiše přes setOnlyAlertOnce.
            val alerts = !wasNotifyWorthy ||
                (session.activity == "APPROVAL_NEEDED" && (activityChanged || messageAdvanced))

            // Logs the PREVIOUS activity too — a session reported as acting
            // on a message that "hadn't moved in days" needs this to tell
            // apart a genuine (if surprising) phone-side activity flip from
            // this process having just restarted and previousById being a
            // stale/incomplete snapshot.
            WearLog.i(
                this, TAG,
                "transition for ${session.id}: ${previous.activity} -> ${session.activity} " +
                    "body=${bodyNow.length} chars advanced=$messageAdvanced bodyChanged=$bodyChanged " +
                    "approval=$enteringApproval alerts=$alerts",
            )

            // Actionable notification — the notifikace-first path, always.
            WearNotifier.notifySession(
                this,
                session,
                silentUpdate = !alerts,
                replacesActivity = previous.activity.takeIf { wasNotifyWorthy },
            )

            // Teprve TEĎ se posouvá "na co jsme reagovali". spokenHash s ním,
            // i když se nakonec nečte nahlas (vypnuté předčítání / DND) —
            // jinak by pozdější zapnutí předčítání přečetlo zprávu, kterou
            // uživatel dávno viděl.
            nextBaseline[session.id] = NotifyBaseline(
                activity = session.activity,
                notifiedMessageAt = session.lastMessageAt,
                notifiedBodyHash = bodyNow.hashCode(),
                spokenHash = spokenText?.hashCode() ?: previous.spokenHash,
            )

            if (!autoSpeakOn || dnd) continue
            if (spokenText == null) continue
            // TTS visí na "tenhle text jsme pro tuhle session ještě nečetli",
            // ne na hraně přechodu: opravené tělo se tak přečte (i když
            // dorazilo až druhým pushem), a to zastaralé se nezopakuje.
            // Rub téže mince: nová odpověď, která je znak po znaku shodná s
            // předchozí, se nepřečte — a protože se hash persistuje, platí to
            // i přes restart procesu. Vědomé: opakované čtení téhož textu
            // uživatele štve víc než jedno chybějící přečtení.
            if (spokenText.hashCode() == previous.spokenHash) continue
            // Soniox voice when a key is synced from the phone; falls back to
            // on-device WatchTts internally when there's no key.
            SonioxWatchTts.speak(applicationContext, spokenText)
        }
        // Zapisuje se jen to, co v tomhle pushi bylo — mapa se tím sama
        // ořezává a session, která zmizí a vrátí se, má zase neznámý
        // baseline (tedy se pro ni nenotifikuje zpětně). A jen když se
        // opravdu něco změnilo: drtivá většina pushů baseline nemění a
        // re-encode + apply() na každý data event je na hodinkách zbytečný.
        if (nextBaseline != stored) NotifyStateStore.save(this, nextBaseline)
    }

    // Nudge the tile to re-render off the just-updated SessionRepository so
    // its "N čeká" count is fresh without waiting for the freshness interval.
    // runCatching: a tile refresh failure must never take down the listener
    // (its notification/TTS work is the more important path).
    private fun requestTileUpdate() {
        runCatching {
            androidx.wear.tiles.TileService.getUpdater(this)
                .requestUpdate(WearTileService::class.java)
        }.onFailure { e -> WearLog.w(this, TAG, "Tile update request failed: ${e.message}") }
    }

    private fun String?.isNotifyWorthy() = this == "WAITING_FOR_INPUT" || this == "APPROVAL_NEEDED"

    /**
     * Tichý režim pro předčítání. Záměrně JEN "nic"/"jen budíky" — ne každý
     * filtr != ALL: priority-only a hlavně divadelní/bedtime režim, který
     * spousta lidí na hodinkách nechává zapnutý natrvalo, předtím
     * předčítání trvale vypínaly a nikde v UI se to neprojevilo.
     * INTERRUPTION_FILTER_UNKNOWN (0) vrací platforma, když filtr přečíst
     * nejde — bereme ho jako "ne-DND", ať feature selže směrem k funkční,
     * ne k tiché.
     */
    private fun isDoNotDisturb(): Boolean {
        val nm = getSystemService(NotificationManager::class.java) ?: return false
        return when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
            else -> false
        }
    }

    companion object {
        private const val TAG = "WearDataListener"
        const val PATH = "/sessions"
        const val KEY_JSON = "json"
        val WEAR_JSON = Json { ignoreUnknownKeys = true }
    }
}
