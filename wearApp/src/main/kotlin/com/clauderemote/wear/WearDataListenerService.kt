package com.clauderemote.wear

import android.app.NotificationManager
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Receives the phone's /sessions Data Layer pushes (WearSync.push()),
 * updates [SessionRepository] and Jarvis' recent alerts, and — the actual
 * point of the watch — reacts the instant a session opens a dialog Lukáš can
 * answer from the wrist (perm/ask, [WaitingRules.isActionable]):
 *   - posts an actionable [WearNotifier] notification (Ano/Ne, an answer to
 *     a question) without opening the app;
 *   - additionally speaks it ALOUD when read-aloud is enabled.
 * A finished turn never reaches the wrist (2026-11-08): what else the watch
 * shows comes from Jarvis ([JarvisWatchNotifier]).
 */
class WearDataListenerService : WearableListenerService() {
    /**
     * Telefon odpověď z hodinek NEodeslal (session mezitím ukazuje jiný dotaz,
     * není připojená, stav nejde ověřit…) a v [WearReplyRefused.message] říká
     * proč. Akce už notifikaci možná zrušila jako odeslanou, takže to řekni
     * znovu — bez akcí, nový dotaz je třeba nejdřív vidět.
     */
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == JarvisWatchStore.PATH_DECIDE_RESULT) {
            // Review M5: a decision that comes back after the app was closed must not be lost. The open app's own
            // listener gets it too and shows it on the Čeká page; with the app not on screen it is a notification.
            val r = JarvisWatchStore.decideResult(messageEvent.data) ?: return
            JarvisWatchStore.onMessage(messageEvent.path, messageEvent.data)
            WearLog.i(this, TAG, "Jarvis decision ${r.id}: ok=${r.ok} ${r.text}")
            if (WatchMessageRouting.notifyDecision(AppVisibility.visible)) {
                JarvisWatchNotifier.postResult(this, "Jarvis · návrh", r.text)
                if (r.ok) WearHaptics.success(this) else WearHaptics.error(this)
            }
            return
        }
        if (messageEvent.path == PATH_JARVIS_YES_RESULT) {
            // F5c: what "Ano" of Jarvis' question did (same shape as a decision's result)
            val r = JarvisWatchStore.decideResult(messageEvent.data) ?: return
            WearLog.i(this, TAG, "Jarvis yes ${r.id}: ok=${r.ok} ${r.text}")
            JarvisWatchNotifier.postResult(this, "Jarvis · ano", r.text)
            if (r.ok) WearHaptics.success(this) else WearHaptics.error(this)
            return
        }
        if (messageEvent.path == PATH_JARVIS_UNDO_RESULT) {
            val r = runCatching {
                WEAR_JSON.decodeFromString<WearJarvisUndoResult>(String(messageEvent.data, Charsets.UTF_8))
            }.getOrNull() ?: return
            WearLog.i(this, TAG, "Jarvis undo of ${r.tmux}: ${r.text}")
            JarvisWatchNotifier.postUndoResult(this, r.text)
            return
        }
        if (messageEvent.path != PATH_REPLY_REFUSED) return
        val refused = runCatching {
            WEAR_JSON.decodeFromString<WearReplyRefused>(String(messageEvent.data, Charsets.UTF_8))
        }.getOrNull() ?: return
        WearLog.i(this, TAG, "reply for ${refused.sessionId} refused by the phone: ${refused.message}")
        WearNotifier.notifySendFailure(this, refused.sessionId, null, refused.message, offerActions = false)
        WearNotifier.markRefusalShown(refused.sessionId)
        WearHaptics.error(this)
    }

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
                SessionRepository.update(payload.sessions)
                SessionRepository.updateJarvis(payload.jarvis)
                JarvisRecentStore.update(this, payload.jarvis?.recent.orEmpty())
                WearLog.i(this, TAG, "Updated repository with ${payload.sessions.size} sessions")
                requestTileUpdate()
                handleTransitions(payload.sessions)
                JarvisWatchNotifier.update(this, payload.jarvis)
            }.onFailure { e -> WearLog.w(this, TAG, "Failed to parse /sessions payload: ${e.message}") }
        }
        dataEvents.release()
    }

    /**
     * Notifications of a push, per session through [WatchNotifyDecision]: a new
     * dialog vibrates, the same one never again, a gone one is taken down. The
     * baseline is persisted ([NotifyStateStore]): Wear OS kills this process
     * routinely, and an in-memory one would forget what was already announced.
     * The phone's "hlásit i dokončení" setting no longer matters here: a
     * finished turn is never actionable, so it never posts.
     */
    private fun handleTransitions(sessions: List<WearSessionInfo>) {
        WearNotifier.ensureChannels(this)
        WearNotifier.clearStaleCompletionsOnce(this)
        // TTS stays gated behind the read-aloud toggle + Do Not Disturb.
        // Notifications do NOT — the OS filters those itself by channel
        // importance under DND.
        val autoSpeakOn = AutoSpeakPrefs.isEnabled(this)
        val dnd = isDoNotDisturb()
        val seeding = NotifyStateStore.isFresh(this)
        val stored = NotifyStateStore.load(this)
        val byId = sessions.associateBy { it.id }
        val next = HashMap<String, NotifyBaseline>(sessions.size)
        for (id in byId.keys + stored.keys) {
            val session = byId[id]
            val prev = stored[id]
            val body = session?.let { WearNotifier.bodyFor(it) }.orEmpty()
            val r = WatchNotifyDecision.decide(prev, session, body.hashCode(), seeding)
            var baseline = r.baseline
            when (val a = r.action) {
                WatchNotifyDecision.Action.Skip -> Unit
                WatchNotifyDecision.Action.Cancel -> {
                    WearLog.i(this, TAG, "dialog gone for $id (${prev?.activity} -> ${session?.activity ?: "absent"})")
                    WearNotifier.cancelSession(this, id)
                }
                WatchNotifyDecision.Action.Repair -> {
                    if (WearNotifier.isShowing(this, id)) WearNotifier.notifySession(this, session!!, silentUpdate = true)
                }
                is WatchNotifyDecision.Action.Post -> {
                    session!!
                    WearLog.i(this, TAG, "dialog for $id (${prev?.activity} -> ${session.activity}) alert=${a.alert} body=${body.length} chars")
                    WearNotifier.notifySession(this, session, silentUpdate = !a.alert, silent = !a.alert)
                    // Předčítá se plný text, ne shrnutí. spokenHash se posouvá i
                    // když se nečte (vypnuté předčítání / DND), jinak by pozdější
                    // zapnutí přečetlo dialog, který už uživatel dávno viděl.
                    val spoken = session.notifyBody?.takeIf { it.isNotBlank() } ?: session.lastMessage?.takeIf { it.isNotBlank() }
                    if (a.alert && spoken != null && spoken.hashCode() != prev?.spokenHash) {
                        baseline = baseline?.copy(spokenHash = spoken.hashCode())
                        if (autoSpeakOn && !dnd) SonioxWatchTts.speak(applicationContext, spoken)
                    }
                }
            }
            if (baseline != null) next[id] = baseline
        }
        // Jen při skutečné změně: drtivá většina pushů baseline nemění a
        // re-encode + apply() na každý data event je na hodinkách zbytečný.
        // Seeding writes even an empty map: from then on the store is no longer fresh (M4).
        if (next != stored || seeding) NotifyStateStore.save(this, next)
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
        const val PATH_REPLY_REFUSED = "/reply-refused"
        const val PATH_JARVIS_UNDO_RESULT = "/jarvis-undo-result"
        const val PATH_JARVIS_YES_RESULT = "/jarvis-yes-result"
        const val PATH = "/sessions"
        const val KEY_JSON = "json"
        val WEAR_JSON = Json { ignoreUnknownKeys = true }
    }
}
