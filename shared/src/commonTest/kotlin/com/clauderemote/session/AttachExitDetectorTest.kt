package com.clauderemote.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AttachExitDetector] — the only witness that THIS device's `tmux attach`
 * ended when another device closed the session. Killing the pane leaves the SSH
 * shell alive, so nothing else in the app notices: the tab kept looking healthy
 * while showing a bare `lucas@Debian:~$`.
 */
class AttachExitDetectorTest {

    @Test
    fun detectsTheMarkerTmuxPrintsToAnEndingClient() {
        assertTrue(AttachExitDetector.sawAttachExit("[exited]\r\nlucas@Debian:~$ "))
        assertTrue(AttachExitDetector.sawAttachExit("[?25h[exited]"), "must survive ANSI framing")
        assertTrue(AttachExitDetector.sawAttachExit("[exited]"))
    }

    @Test
    fun ordinaryOutputDoesNotTrip() {
        assertTrue(!AttachExitDetector.sawAttachExit("process exited with code 0"))
        assertTrue(!AttachExitDetector.sawAttachExit("[exit]"))
        assertTrue(!AttachExitDetector.sawAttachExit(""))
    }

    /**
     * The marker is a HINT, never proof — a session's own output may contain the
     * literal text (this test is the reminder). The caller must confirm with a
     * `tmux has-session` probe before acting, because acting while still
     * attached types the recovery command into the user's live Claude prompt.
     */
    @Test
    fun theMarkerCanAppearInLegitimateOutputSoItIsOnlyAHint() {
        assertTrue(
            AttachExitDetector.sawAttachExit("""echo "[exited]" >> audit.log"""),
            "a false positive is expected and must be resolved by the caller's probe, not by this predicate",
        )
    }

    @Test
    fun probeIsRateLimitedButAlwaysRunsTheFirstTime() {
        assertTrue(AttachExitDetector.shouldProbe(nowMs = 1_000, lastProbeMs = null))
        assertTrue(!AttachExitDetector.shouldProbe(nowMs = 2_000, lastProbeMs = 1_000))
        assertTrue(!AttachExitDetector.shouldProbe(nowMs = 5_999, lastProbeMs = 1_000))
        assertTrue(AttachExitDetector.shouldProbe(nowMs = 6_000, lastProbeMs = 1_000))
    }

    /** Scrollback replay after an attach can re-deliver an old marker. */
    @Test
    fun repeatedMarkersCostAtMostOneProbePerWindow() {
        var last: Long? = null
        var probes = 0
        for (t in listOf(0L, 100L, 900L, 4_000L, 5_100L, 12_000L)) {
            if (AttachExitDetector.shouldProbe(t, last)) {
                probes++
                last = t
            }
        }
        assertEquals(3, probes, "expected one probe at 0ms, 5100ms and 12000ms")
    }
}
