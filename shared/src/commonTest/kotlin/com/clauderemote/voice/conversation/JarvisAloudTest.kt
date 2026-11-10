package com.clauderemote.voice.conversation

import com.clauderemote.session.service.JarvisAlert
import com.clauderemote.session.service.JarvisAlertKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** F5c: where Jarvis' own sentence is read aloud, and the headset queue outside a conversation. */
class JarvisAloudTest {
    private fun a(kind: JarvisAlertKind) = JarvisAlert("i", kind, 1, 1, "Citace pushnula.", null, "s")

    private val taken = mutableListOf<String>()

    private fun route(
        kind: JarvisAlertKind = JarvisAlertKind.JSAY,
        conversation: Boolean = false,
        notifications: Boolean = true,
        setting: Boolean = true,
        headset: Boolean = true,
    ) = JarvisAloud.route(
        a(kind), notifications,
        conversation = { if (conversation) { taken += "talk"; true } else false },
        headsetSetting = setting,
        headsetConnected = { headset },
    )

    @Test fun aRunningConversationTakesItFirst() {
        assertEquals(AloudRoute.CONVERSATION, route(conversation = true))
        assertEquals(listOf("talk"), taken, "handed to the conversation exactly once")
        // even with notifications off and the switch off: he is in a conversation with Jarvis
        assertEquals(AloudRoute.CONVERSATION, route(conversation = true, notifications = false, setting = false, headset = false))
    }

    @Test fun outsideAConversationOnlyInAConnectedHeadsetWithTheSwitchOn() {
        assertEquals(AloudRoute.HEADSET, route())
        assertEquals(AloudRoute.NONE, route(headset = false), "never the loudspeaker")
        assertEquals(AloudRoute.NONE, route(setting = false))
        assertEquals(AloudRoute.NONE, route(notifications = false))
    }

    @Test fun everyOwnKindIsReadTemplatesNever() {
        for (k in listOf(JarvisAlertKind.JSAY, JarvisAlertKind.JBLOCK, JarvisAlertKind.JASK)) assertEquals(AloudRoute.HEADSET, route(k), k.name)
        for (k in listOf(JarvisAlertKind.BLOCK, JarvisAlertKind.DONE, JarvisAlertKind.INFO, JarvisAlertKind.INCIDENT)) {
            assertEquals(AloudRoute.NONE, route(k, conversation = true), k.name)
        }
        assertEquals(emptyList(), taken, "a template line is never handed to the conversation")
    }

    @Test fun theQueueReadsOneAtATimeOldestFirstCappedAndFresh() {
        val q = JarvisAloudQueue()
        q.offer("a", false, 0)
        q.offer("  ", false, 0)
        q.offer("b", true, 1_000)
        assertEquals(2, q.size, "a blank sentence is nothing")
        assertEquals(JarvisAloudQueue.Item("a", false, 0), q.take(1_000))
        assertEquals(JarvisAloudQueue.Item("b", true, 1_000), q.take(1_000))
        assertNull(q.take(1_000))
        (1..5).forEach { q.offer("x$it", false, 2_000) }
        assertEquals(JarvisAloudQueue.MAX, q.size)
        assertEquals("x3", q.take(2_000)?.text, "the oldest beyond the cap went")
        q.clear()
        q.offer("stale", false, 0)
        q.offer("fresh", false, JarvisAloudQueue.MAX_AGE_MS)
        assertEquals("fresh", q.take(JarvisAloudQueue.MAX_AGE_MS + 1)?.text, "a sentence that waited too long is dropped")
    }

    @Test fun theReadingStopsTheMomentTheHeadsetLeavesAndOnlyAHeadsetReadingIsStopped() {
        // becoming noisy: stop the sentence at once, even before the device list shows the loss
        assertEquals(true, HeadsetLoss.stopReading(armed = true, becomingNoisy = true, headsetNow = true))
        assertEquals(false, HeadsetLoss.lost(armed = true, headsetNow = true), "a route switch is no loss")
        // the device is gone from the list
        assertEquals(true, HeadsetLoss.stopReading(armed = true, becomingNoisy = false, headsetNow = false))
        assertEquals(true, HeadsetLoss.lost(armed = true, headsetNow = false))
        // another device went (a headset is still there): nothing
        assertEquals(false, HeadsetLoss.stopReading(armed = true, becomingNoisy = false, headsetNow = true))
        // never armed (a conversation on the phone's own speaker): never stopped by this
        assertEquals(false, HeadsetLoss.stopReading(armed = false, becomingNoisy = true, headsetNow = false))
        assertEquals(false, HeadsetLoss.lost(armed = false, headsetNow = false))
    }

    @Test fun m1OnlyARealHeadsetRouteIsAHeadset() {
        val r = HeadsetRoute
        // Android 13+: where the stream would actually play decides
        assertEquals(true, r.headsetNow(listOf(r.BLUETOOTH_A2DP), listOf(r.BLUETOOTH_A2DP, r.BLUETOOTH_SCO)))
        assertEquals(false, r.headsetNow(listOf(2 /* built-in speaker */), listOf(r.BLUETOOTH_SCO)), "HFP-only: media plays on the speaker")
        assertEquals(false, r.headsetNow(emptyList(), listOf(r.BLUETOOTH_A2DP)))
        // older Android: a headset among the outputs, never SCO alone nor a BLE speaker
        assertEquals(false, r.headsetNow(null, listOf(r.BLUETOOTH_SCO)))
        assertEquals(false, r.headsetNow(null, listOf(r.BLE_SPEAKER)))
        for (t in listOf(r.WIRED_HEADSET, r.WIRED_HEADPHONES, r.BLUETOOTH_A2DP, r.USB_HEADSET, r.BLE_HEADSET)) {
            assertEquals(true, r.headsetNow(null, listOf(2, t)), "type $t")
        }
        // the loss watch: SCO still counts as the headset being there (a route switch is no loss)
        assertEquals(true, r.present(listOf(r.BLUETOOTH_SCO)))
        assertEquals(false, r.present(listOf(2, r.BLE_SPEAKER)))
    }

    @Test fun jblockaskIsReadAloudLikeEveryOwnKind() {
        assertEquals(AloudRoute.HEADSET, route(JarvisAlertKind.JBLOCKASK))
    }
}
