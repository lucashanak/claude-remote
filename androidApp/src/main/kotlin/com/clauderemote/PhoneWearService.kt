package com.clauderemote

import com.clauderemote.util.FileLogger
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives Wearable Data Layer messages from the watch companion app
 * (com.clauderemote.wear). This PR only proves the round trip — logs the
 * `/ping` path. `/reply` and `/approve` (routing into [OrchestratorHolder],
 * mirroring [ReplyReceiver]) land in a follow-up PR once this is confirmed
 * working on a real device/paired emulators.
 */
class PhoneWearService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        FileLogger.log(
            TAG,
            "Wear message: ${messageEvent.path} (${messageEvent.data.size} bytes) from ${messageEvent.sourceNodeId}",
        )
    }

    companion object {
        private const val TAG = "PhoneWearService"
    }
}
