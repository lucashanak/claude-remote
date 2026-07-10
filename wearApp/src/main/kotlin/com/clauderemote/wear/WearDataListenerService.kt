package com.clauderemote.wear

import android.app.NotificationManager
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Receives the phone's /sessions Data Layer pushes (WearSync.push()),
 * updates [SessionRepository], and — the actual point of the watch app —
 * speaks a session's message ALOUD on the watch's own speaker/BT the
 * instant it flips to WAITING_FOR_INPUT/APPROVAL_NEEDED. Not gated behind
 * opening the app or tapping a button: that would be strictly worse than
 * the phone notification tier this is meant to improve on.
 */
class WearDataListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // Logged unconditionally (not just on failure) — diagnosing the
        // watch side was blocked by having zero visibility into whether
        // this even gets invoked at all vs. invoked-but-silently-fine.
        Log.i(TAG, "onDataChanged: ${dataEvents.count} event(s)")
        for (event in dataEvents) {
            Log.i(TAG, "event type=${event.type} path=${event.dataItem.uri.path}")
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != PATH) continue
            runCatching {
                val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(KEY_JSON) ?: return@runCatching
                val payload = WEAR_JSON.decodeFromString<WearSessionsPayload>(json)
                val previousById = SessionRepository.sessions.value.associateBy { it.id }
                SessionRepository.update(payload.sessions)
                Log.i(TAG, "Updated repository with ${payload.sessions.size} sessions")
                maybeSpeakTransitions(payload.sessions, previousById)
            }.onFailure { e -> Log.w(TAG, "Failed to parse /sessions payload: ${e.message}") }
        }
        dataEvents.release()
    }

    private fun maybeSpeakTransitions(
        sessions: List<WearSessionInfo>,
        previousById: Map<String, WearSessionInfo>,
    ) {
        if (!AutoSpeakPrefs.isEnabled(this) || isDoNotDisturb()) return
        for (session in sessions) {
            if (!session.activity.isNotifyWorthy()) continue
            if (previousById[session.id]?.activity.isNotifyWorthy()) continue // already was — not a fresh transition
            val text = session.lastMessage?.takeIf { it.isNotBlank() } ?: continue
            WatchTts.speak(applicationContext, text)
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
