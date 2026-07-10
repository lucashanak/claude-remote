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
        WearLog.i(this, TAG, "onDataChanged: ${dataEvents.count} event(s)")
        for (event in dataEvents) {
            WearLog.i(this, TAG, "event type=${event.type} path=${event.dataItem.uri.path}")
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != PATH) continue
            runCatching {
                val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(KEY_JSON) ?: return@runCatching
                val payload = WEAR_JSON.decodeFromString<WearSessionsPayload>(json)
                val previousById = SessionRepository.sessions.value.associateBy { it.id }
                SessionRepository.update(payload.sessions)
                WearLog.i(this, TAG, "Updated repository with ${payload.sessions.size} sessions")
                maybeSpeakTransitions(payload.sessions, previousById)
            }.onFailure { e -> WearLog.w(this, TAG, "Failed to parse /sessions payload: ${e.message}") }
        }
        dataEvents.release()
    }

    private fun maybeSpeakTransitions(
        sessions: List<WearSessionInfo>,
        previousById: Map<String, WearSessionInfo>,
    ) {
        val autoSpeakOn = AutoSpeakPrefs.isEnabled(this)
        val dnd = isDoNotDisturb()
        WearLog.i(this, TAG, "maybeSpeakTransitions: autoSpeak=$autoSpeakOn dnd=$dnd sessions=${sessions.size}")
        if (!autoSpeakOn || dnd) return
        for (session in sessions) {
            val wasNotifyWorthy = previousById[session.id]?.activity.isNotifyWorthy()
            val nowNotifyWorthy = session.activity.isNotifyWorthy()
            if (!nowNotifyWorthy) continue
            if (wasNotifyWorthy) continue // already was — not a fresh transition
            val text = session.lastMessage?.takeIf { it.isNotBlank() }
            WearLog.i(
                this, TAG,
                "transition for ${session.id}: activity=${session.activity} lastMessage=${if (text != null) "${text.length} chars" else "null/blank"}",
            )
            if (text == null) continue
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
