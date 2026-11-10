package com.clauderemote.voice.conversation

import com.clauderemote.session.service.JarvisAlert

/**
 * Jarvis' own sentences read aloud (F5c, 2026-11-10). Where one goes:
 * - a conversation runs ([JarvisTalk]): it is read there, queued behind Lukáš's words ([TalkEvent.Report]);
 * - otherwise, on a platform with a headset reader (Android), the setting "Číst Jarvise ve sluchátkách" on and a
 *   headset connected (Bluetooth or wired): read in the headset, without the microphone, ducking the music;
 * - otherwise nowhere — never on the phone's loudspeaker (decided, not a gap), and on the desktop only in a running
 *   conversation (the notification carries the text).
 * Only Jarvis' own kinds (jsay/jblock/jask) are read; the template lines of jarvis-d never are.
 */
enum class AloudRoute { CONVERSATION, HEADSET, NONE }

/** The headset setting of this platform; [JarvisAloud.headset] null = there is no headset reader (desktop). */
interface JarvisHeadsetSetting {
    var enabled: Boolean
}

object JarvisAloud {
    /** Set by Android at start; the Velín draws the switch only when it is there. */
    @kotlin.concurrent.Volatile var headset: JarvisHeadsetSetting? = null

    /** Where [alert] is read; [conversation] = a conversation took it (JarvisTalk.report returned true). */
    fun route(
        alert: JarvisAlert,
        notificationsEnabled: Boolean,
        conversation: () -> Boolean,
        headsetSetting: Boolean,
        headsetConnected: () -> Boolean,
    ): AloudRoute {
        if (!alert.kind.own) return AloudRoute.NONE
        // the conversation is asked first, and asking it IS handing the sentence over (it queues it)
        if (conversation()) return AloudRoute.CONVERSATION
        if (!notificationsEnabled || !headsetSetting) return AloudRoute.NONE
        return if (headsetConnected()) AloudRoute.HEADSET else AloudRoute.NONE
    }
}

/**
 * What waits to be read in the headset outside a conversation: one at a time, never over another reading (a long
 * answer Lukáš is listening to is not cut), at most [MAX] (the oldest go), none older than [MAX_AGE_MS] (a sentence
 * that waited that long is stale — the notification still has it). Not thread-safe: the platform owns one thread.
 */
/**
 * Which outputs are a headset Jarvis may be read into (rev-f5c-app M1). The values are Android's
 * `AudioDeviceInfo.TYPE_*` (stable API constants; `JarvisHeadsetVoice` checks them at start). Not Bluetooth SCO: an
 * HFP-only (car, mono) headset does not play a media stream, which would then come out of the loudspeaker; not a BLE
 * speaker: it is a loudspeaker.
 */
object HeadsetRoute {
    const val WIRED_HEADSET = 3
    const val WIRED_HEADPHONES = 4
    const val BLUETOOTH_SCO = 7
    const val BLUETOOTH_A2DP = 8
    const val USB_HEADSET = 22
    const val BLE_HEADSET = 26
    const val BLE_SPEAKER = 27

    private val HEADSET = setOf(WIRED_HEADSET, WIRED_HEADPHONES, BLUETOOTH_A2DP, USB_HEADSET, BLE_HEADSET)

    fun isHeadset(type: Int): Boolean = type in HEADSET

    /**
     * A headset now. [routed] = where a speech/media stream would actually go (Android 13+:
     * `getAudioDevicesForAttributes`), which decides when known; else any headset among the [outputs].
     */
    fun headsetNow(routed: List<Int>?, outputs: List<Int>): Boolean =
        if (routed != null) routed.isNotEmpty() && routed.all { isHeadset(it) } else outputs.any { isHeadset(it) }

    /**
     * A headset is still there at all — what the loss watch asks (SCO counts: the conversation's microphone switches
     * a Bluetooth headset to SCO and back, and a route switch must never read as the headset leaving).
     */
    fun present(outputs: List<Int>): Boolean = outputs.any { isHeadset(it) || it == BLUETOOTH_SCO }
}

/**
 * F5c: when a reading into the headset must stop. [armed] = a headset was an output when the watch started or joined
 * since (a conversation begun on the phone's own speaker is never stopped by this).
 * - BECOMING_NOISY (the system: audio is about to leave the headset) stops the READING at once, before the device
 *   list catches up — stopping a sentence is harmless even if it was a false alarm (a Bluetooth SCO switch).
 * - The headset is LOST only when no headset is an output any more: that ends a conversation (silently).
 */
object HeadsetLoss {
    fun stopReading(armed: Boolean, becomingNoisy: Boolean, headsetNow: Boolean): Boolean = armed && (becomingNoisy || !headsetNow)
    fun lost(armed: Boolean, headsetNow: Boolean): Boolean = armed && !headsetNow
    /** After BECOMING_NOISY the device list is read again this much later to tell a loss from a route switch. */
    const val RECHECK_MS = 400L
}

class JarvisAloudQueue(private val maxAgeMs: Long = MAX_AGE_MS) {
    data class Item(val text: String, val ask: Boolean, val atMs: Long)

    private val items = ArrayDeque<Item>()

    val size: Int get() = items.size

    fun offer(text: String, ask: Boolean, now: Long) {
        val t = text.trim()
        if (t.isEmpty()) return
        items.addLast(Item(t, ask, now))
        while (items.size > MAX) items.removeFirst()
    }

    /** The next item to read now, or null; stale ones are dropped on the way. */
    fun take(now: Long): Item? {
        while (items.isNotEmpty()) {
            val i = items.removeFirst()
            if (now - i.atMs <= maxAgeMs) return i
        }
        return null
    }

    fun clear() = items.clear()

    companion object {
        const val MAX = 3
        const val MAX_AGE_MS = 2 * 60_000L
        /** After a jask read in the headset, the headset's button starts the conversation for this long. */
        const val ASK_WINDOW_MS = 10_000L
    }
}
