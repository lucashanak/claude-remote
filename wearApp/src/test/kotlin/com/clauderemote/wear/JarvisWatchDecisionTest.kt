package com.clauderemote.wear

import com.clauderemote.wear.JarvisWatchDecision.Action
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
}
