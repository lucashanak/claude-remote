package com.clauderemote.wear

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Receives the phone's /sessions Data Layer pushes (WearSync.push()) and
 * updates [SessionRepository] so the SessionList screen stays current.
 */
class WearDataListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != PATH) continue
            runCatching {
                val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(KEY_JSON) ?: return@runCatching
                val payload = WEAR_JSON.decodeFromString<WearSessionsPayload>(json)
                SessionRepository.update(payload.sessions)
            }.onFailure { e -> Log.w(TAG, "Failed to parse /sessions payload: ${e.message}") }
        }
        dataEvents.release()
    }

    companion object {
        private const val TAG = "WearDataListener"
        const val PATH = "/sessions"
        const val KEY_JSON = "json"
        val WEAR_JSON = Json { ignoreUnknownKeys = true }
    }
}
