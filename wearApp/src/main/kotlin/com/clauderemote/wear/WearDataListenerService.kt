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
                SonioxKeyStore.update(payload.sonioxApiKey, payload.sonioxVoice, payload.ttsSpeedPct)
                val previousById = SessionRepository.sessions.value.associateBy { it.id }
                SessionRepository.update(payload.sessions)
                WearLog.i(this, TAG, "Updated repository with ${payload.sessions.size} sessions")
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
        WearLog.i(this, TAG, "handleTransitions: autoSpeak=$autoSpeakOn dnd=$dnd sessions=${sessions.size}")
        for (session in sessions) {
            // No prior record at all (first onDataChanged after this
            // process started, e.g. app restart/update/reboot) — we don't
            // know whether this session has been sitting there waiting for
            // hours or just flipped. Treating "unknown" as "wasn't
            // notify-worthy" made EVERY already-waiting session look like a
            // fresh transition on every process restart, reading out a pile
            // of stale messages (and now posting a pile of stale
            // notifications) that hadn't actually changed. Skip instead;
            // only a genuinely observed transition (previous state known
            // and different) should act.
            val previous = previousById[session.id] ?: continue
            val wasNotifyWorthy = previous.activity.isNotifyWorthy()
            val nowNotifyWorthy = session.activity.isNotifyWorthy()

            // Session finished waiting (answered from the phone, or moved on)
            // — clear its notification so no "ghost" prompt lingers on the wrist.
            if (wasNotifyWorthy && !nowNotifyWorthy) {
                WearNotifier.cancelSession(this, session.id)
                continue
            }
            if (!nowNotifyWorthy) continue
            if (wasNotifyWorthy) continue // already was — not a fresh transition

            val text = session.lastMessage?.takeIf { it.isNotBlank() }
            // Logs the PREVIOUS activity too — a session reported as acting
            // on a message that "hadn't moved in days" needs this to tell
            // apart a genuine (if surprising) phone-side activity flip from
            // this process having just restarted and previousById being a
            // stale/incomplete snapshot.
            WearLog.i(
                this, TAG,
                "transition for ${session.id}: ${previous.activity} -> ${session.activity} lastMessage=${if (text != null) "${text.length} chars" else "null/blank"}",
            )

            // Actionable notification — the notifikace-first path, always.
            WearNotifier.notifySession(this, session)

            if (!autoSpeakOn || dnd) continue
            if (text == null) continue
            // Soniox voice when a key is synced from the phone; falls back to
            // on-device WatchTts internally when there's no key.
            SonioxWatchTts.speak(applicationContext, text)
        }
    }

    private fun String?.isNotifyWorthy() = this == "WAITING_FOR_INPUT" || this == "APPROVAL_NEEDED"

    private fun isDoNotDisturb(): Boolean {
        val nm = getSystemService(NotificationManager::class.java) ?: return false
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    companion object {
        private const val TAG = "WearDataListener"
        const val PATH = "/sessions"
        const val KEY_JSON = "json"
        val WEAR_JSON = Json { ignoreUnknownKeys = true }
    }
}
