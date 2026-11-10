package com.clauderemote.wear

import com.clauderemote.wear.JarvisWatchDecision.Action
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals

/** rev-f3-app MED5: the merged Jarvis notification is posted on change only, and a swipe stays swiped. */
class JarvisWatchDecisionTest {
    private fun info(pending: Int = 2, text: String = "▲ a\n✓ b", notify: Boolean = true, buzzAt: Long = 0) =
        WearJarvisInfo(pending, text, notify, buzzAt)

    @Test fun nothingToShowCancels() {
        assertEquals(Action.Cancel, JarvisWatchDecision.decide(null, 0, null, null))
        assertEquals(Action.Cancel, JarvisWatchDecision.decide(info(notify = false), 0, null, null))
        assertEquals(Action.Cancel, JarvisWatchDecision.decide(info(pending = 0), 0, null, null))
        assertEquals(Action.Cancel, JarvisWatchDecision.decide(info(text = " "), 0, null, null))
    }

    @Test fun theSameContentIsNotRepostedOnEveryPush() {
        val i = info()
        assertEquals(Action.Post(buzz = false), JarvisWatchDecision.decide(i, 0, null, null))
        assertEquals(Action.Skip, JarvisWatchDecision.decide(i, 0, JarvisWatchDecision.key(i), null))
    }

    @Test fun aSwipeIsRememberedUntilANewAlert() {
        val i = info()
        val k = JarvisWatchDecision.key(i)
        // swiped: the shown key was cleared by nothing, the dismissed key holds it
        assertEquals(Action.Skip, JarvisWatchDecision.decide(i, 0, null, k))
        // a new alert changes the content: shown again
        val newer = info(pending = 3, text = "• c\n▲ a\n✓ b")
        assertEquals(Action.Post(buzz = false), JarvisWatchDecision.decide(newer, 0, null, k))
    }

    @Test fun aVibratingAlertAlwaysShowsAndVibratesOnce() {
        val i = info(buzzAt = 500)
        val k = JarvisWatchDecision.key(i)
        assertEquals(Action.Post(buzz = true), JarvisWatchDecision.decide(i, 100, k, k))
        // re-delivered after it was acted on: no second vibration, no repost
        assertEquals(Action.Skip, JarvisWatchDecision.decide(i, 500, k, null))
    }

    // F4: "Vrátit" offers ride the same push; a phone without them (older build) still decodes, with none
    @Test fun undoOffersAreWireCompatible() {
        val old = """{"sessions": [], "jarvis": {"pending": 1, "text": "✓ Zavřel jsem a.", "notify": true}}"""
        assertEquals(emptyList(), WearDataListenerService.WEAR_JSON.decodeFromString<WearSessionsPayload>(old).jarvis!!.undo)
        val new = """{"sessions": [], "jarvis": {"pending": 1, "text": "✓ Zavřel jsem a.", "notify": true,
            "undo": [{"serverId": "s1", "tmux": "claude-server-x--a", "label": "a", "future": 1}]}}"""
        assertEquals(
            listOf(WearJarvisUndo("s1", "claude-server-x--a", "a")),
            WearDataListenerService.WEAR_JSON.decodeFromString<WearSessionsPayload>(new).jarvis!!.undo,
        )
    }

    // F5c: Jarvis' question gets "Ano" on the wrist; a new question (another id) is new content even with the same text
    @Test fun anotherQuestionIsNewContent() {
        val q1 = info().copy(askId = "j-1")
        val q2 = info().copy(askId = "j-2")
        assertEquals(Action.Skip, JarvisWatchDecision.decide(q1, 0, JarvisWatchDecision.key(q1), null))
        assertEquals(Action.Post(buzz = false), JarvisWatchDecision.decide(q2, 0, JarvisWatchDecision.key(q1), null))
        // the Ano going away (he said something after) also reposts, so the action disappears
        assertEquals(Action.Post(buzz = false), JarvisWatchDecision.decide(info(), 0, JarvisWatchDecision.key(q1), null))
    }

    @Test fun anOlderPhonesPayloadHasNoQuestion() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val i = json.decodeFromString<WearJarvisInfo>("""{"pending":1,"text":"» x","notify":true}""")
        assertEquals(null, i.askId)
        assertEquals("j-7", json.decodeFromString<WearJarvisInfo>("""{"pending":1,"text":"? x","notify":true,"askId":"j-7"}""").askId)
    }

    @Test fun theNewKindsHaveThePhonesGlyphs() {
        assertEquals(
            listOf("▲", "»", "?", "▲?", "▲", "•"),
            listOf("jblock", "jsay", "jask", "jblockask", "block", "future").map { JarvisRecentStore.glyph(it) },
        )
    }
}
