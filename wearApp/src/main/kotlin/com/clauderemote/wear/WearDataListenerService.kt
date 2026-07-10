package com.clauderemote.wear

import android.app.NotificationManager
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

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

    /**
     * The phone (WearApkPusher) streams the watch's own update APK over this
     * channel instead of the watch downloading it via HTTP — that path was
     * unreliable (timeouts, screen sleeping mid-download). Requires the
     * CHANNEL_EVENT intent-filter action alongside DATA_CHANGED in the
     * manifest, or this never fires.
     *
     * Runs synchronously via Tasks.await rather than addOnSuccessListener:
     * WearableListenerService callbacks already run off the main thread, and
     * the default (unspecified) Task executor for gms Tasks IS the main
     * thread — reading a multi-MB APK there would block the UI/watchdog for
     * the whole transfer.
     */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        WearLog.i(this, TAG, "onChannelOpened path=${channel.path}")
        val channelClient = Wearable.getChannelClient(this)
        if (channel.path != APK_PUSH_PATH) {
            channelClient.close(channel)
            return
        }
        runCatching {
            val input = Tasks.await(channelClient.getInputStream(channel), 10, TimeUnit.SECONDS)
            val bytes = input.use { readFramed(it) }
            WearLog.i(this, TAG, "Received pushed APK: ${bytes.size} bytes")
            WearUpdater.installApk(applicationContext, bytes)
        }.onFailure { e -> WearLog.w(this, TAG, "Failed to read/install pushed APK: ${e.message}") }
        channelClient.close(channel)
    }

    /**
     * WearApkPusher writes an 8-byte big-endian length prefix before the APK
     * bytes so a Bluetooth stall/process-death mid-transfer surfaces as an
     * explicit "truncated transfer" failure here instead of a silent partial
     * APK reaching PackageInstaller (which just reports a generic install
     * failure with no clue it was a transfer problem, not a bad build).
     */
    private fun readFramed(input: java.io.InputStream): ByteArray {
        val header = ByteArray(8)
        var read = 0
        while (read < 8) {
            val n = input.read(header, read, 8 - read)
            if (n < 0) throw IllegalStateException("stream closed before length header ($read/8 bytes)")
            read += n
        }
        val expectedSize = ByteBuffer.wrap(header).long
        val buffer = ByteArrayOutputStream(expectedSize.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        val chunk = ByteArray(8192)
        var total = 0L
        while (total < expectedSize) {
            val n = input.read(chunk)
            if (n < 0) break
            buffer.write(chunk, 0, n)
            total += n
        }
        if (total != expectedSize) throw IllegalStateException("truncated transfer: expected $expectedSize got $total bytes")
        return buffer.toByteArray()
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
        private const val APK_PUSH_PATH = "/apk_push"
        val WEAR_JSON = Json { ignoreUnknownKeys = true }
    }
}
