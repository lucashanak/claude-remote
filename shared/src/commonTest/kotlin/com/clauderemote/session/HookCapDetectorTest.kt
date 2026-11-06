package com.clauderemote.session

import com.clauderemote.util.FileLogger
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Batch B, unit B4.1/B4.2 at the detector: CAP=1 (a v2 hook event seen for the session)
 * takes the OMC statusline out of the working/idle decision, and a screen APPROVAL
 * detection leaves one log line naming its source and sequence number.
 *
 * Why the statusline cannot be trusted with hooks on: OMC's `thinking` segment means
 * "a thinking block in the transcript within 30 s of the render", and the HUD cache
 * shows the PREVIOUS render, so the segment freezes at the end of a turn (26 of 27
 * cached statuslines with `thinking` were older than 5 min, kritika-K2 B4 bod 1).
 */
class HookCapDetectorTest {
    private val thinking = "5h:33%(2h59m) | wk:14%(10h52m) | thinking | session:6464m | ctx:42%"
    private val idle = "5h:33%(2h59m) | wk:14%(6d10h) | session:6m | ctx:6%"

    @Test fun cap1ReturnsNullForTheFrozenThinkingSegment() {
        val d = InputPromptDetector()
        d.markHookEvents("cap1")
        d.feedRecentOutput("cap1", thinking)
        assertNull(d.parseClaudeWorking("cap1"), "CAP=1: the hooks own the state, `thinking` is not work")
        d.feedRecentOutput("cap1", idle)
        assertNull(d.parseClaudeWorking("cap1"), "nor does an idle render force ready")
    }

    @Test fun withoutHookEventsTheStatuslineDecidesAsBefore() {
        val d = InputPromptDetector()
        d.feedRecentOutput("cap0", thinking)
        assertEquals(true, d.parseClaudeWorking("cap0"))
        d.markHookEvents("someone-else")
        assertEquals(true, d.parseClaudeWorking("cap0"), "CAP is per session")
    }

    @Test fun removingTheSessionForgetsCap1() {
        val d = InputPromptDetector()
        d.markHookEvents("gone")
        d.removeSession("gone")
        d.feedRecentOutput("gone", thinking)
        assertEquals(true, d.parseClaudeWorking("gone"), "a session re-attached later starts as CAP=0")
    }

    @Test fun aScreenApprovalDetectionLogsItsSourceAndSequence() {
        val d = InputPromptDetector()
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val prev = FileLogger.platformLog
        FileLogger.platformLog = { _, _, msg, _ -> if ("log-sess" in msg) lines += msg }
        try {
            d.screenReader = {
                ScreenStateSnapshot(
                    rows = listOf("Allow Bash to run this command?", "❯ 1. Yes", "  2. Yes, and don't ask again", "  3. No")
                        .map { RowSnapshot(it.padEnd(40), BooleanArray(40)) },
                    cols = 40,
                )
            }
            val detections = Collections.synchronizedList(mutableListOf<PromptDetection>())
            d.onDetection = { detections += it }
            d.markInteracted("log-sess")
            d.onOutput("log-sess", "x")
            val end = System.currentTimeMillis() + 5_000
            while (detections.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
            assertEquals(1L, detections.single().seq)
            assertTrue(
                lines.any { it.startsWith("detection APPROVAL source=SCREEN seq=1 ") },
                "the detector names its detection in the log: $lines",
            )
        } finally {
            FileLogger.platformLog = prev
        }
    }

    // Batch B round 2, R6: a hook-active session reports the screen state only when it is a
    // dialog (approval-only mode). That a dialog is GONE is what ends the hold of a screen
    // alert behind an announced hook approval, so the classifier's verdict must still get out.

    @Test fun aHookActiveScreenWithoutADialogIsStillReportedToOnScreenSeen() {
        val d = InputPromptDetector()
        d.screenReader = {
            ScreenStateSnapshot(
                rows = listOf("I've finished the change.", "\u276F ").map { RowSnapshot(it.padEnd(40), BooleanArray(40)) },
                cols = 40,
            )
        }
        val seen = Collections.synchronizedList(mutableListOf<ClaudeState>())
        val changed = Collections.synchronizedList(mutableListOf<ClaudeState>())
        d.onScreenSeen = { id, st -> if (id == "ha") seen += st }
        d.onStateChange = { id, st -> if (id == "ha") changed += st }
        d.markHookActive("ha")
        d.onOutput("ha", "x")
        val end = System.currentTimeMillis() + 5_000
        while (seen.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(listOf(ClaudeState.IDLE), seen.toList(), "the classifier saw no dialog")
        assertTrue(changed.isEmpty(), "approval-only mode: the activity is still not driven from the screen")
    }

    // Batch B round 2, R8: `hookev.off` (reported by the bootstrap) ends CAP=1 for the session.

    @Test fun aHookCapVetoEndsCap1() {
        val d = InputPromptDetector()
        d.markHookEvents("v", atMs = 1_234L)
        assertEquals(1_234L, d.hookEventAtMs("v"))
        d.feedRecentOutput("v", thinking)
        d.hookCapVeto = { it == "v" }
        assertFalse(d.hasHookEvents("v"), "the hooks are switched off: no CAP=1")
        assertEquals(true, d.parseClaudeWorking("v"), "the statusline decides again")
        d.hookCapVeto = null
        assertFalse(d.hasHookEvents("v"), "a veto clears the mark: a fresh event must set it again")
    }
}
