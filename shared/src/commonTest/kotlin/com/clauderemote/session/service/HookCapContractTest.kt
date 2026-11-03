package com.clauderemote.session.service

import com.clauderemote.session.service.HookCapContract.Installed
import com.clauderemote.session.service.HookCapContract.Proc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HookCapContractTest {
    @Test fun parseInstalled() {
        assertEquals(Installed.Epoch(1730000000), HookCapContract.parseInstalled("1730000000\n"))
        assertEquals(Installed.Epoch(1730000000), HookCapContract.parseInstalled("1730000000"))
        assertEquals(Installed.Absent, HookCapContract.parseInstalled(null))
        for (bad in listOf(
            "", "\n", "abc\n", "12 34\n", "-5\n", "1.5\n", "12\n\n", "1730000000\nextra\n", " 12\n", "9999999999999999\n",
            "0\n", "0", "0000000000\n", "0173000000\n", // zero / leading zero: not an epoch
            "1730000000000\n", "1730000000123", // milliseconds
            "173000000\n", "17300000000\n", // nine / eleven digits
            "1730000000\r\n", "+730000000\n",
        )) assertEquals(Installed.Corrupt, HookCapContract.parseInstalled(bad), "input=<$bad>")
        assertEquals(Installed.Epoch(1000000000), HookCapContract.parseInstalled("1000000000\n"))
        assertEquals(Installed.Epoch(9999999999), HookCapContract.parseInstalled("9999999999"))
    }

    @Test fun capIsMarkerInSettingsAndNoDisableAllHooks_startTimeAndInstalledEpochDoNotMatter() {
        assertEquals(true, HookCapContract.verdict(true, false))
        assertEquals(false, HookCapContract.verdict(false, false))
        assertEquals(false, HookCapContract.verdict(true, true))
    }

    @Test fun unknownInputMeansUnknownButADefiniteFailureWins() {
        assertNull(HookCapContract.verdict(null, false))
        assertNull(HookCapContract.verdict(true, null))
        assertNull(HookCapContract.verdict(null, null))
        assertEquals(false, HookCapContract.verdict(false, null))
        assertEquals(false, HookCapContract.verdict(null, true))
        // the owner's hook switch file
        assertEquals(false, HookCapContract.verdict(true, false, hooksOff = true))
        assertEquals(false, HookCapContract.verdict(false, null, hooksOff = true))
        assertNull(HookCapContract.verdict(true, false, hooksOff = null))
        assertEquals(true, HookCapContract.verdict(true, false, hooksOff = false))
    }

    @Test fun markerIsPerConfigDirFile() {
        assertEquals("claude-remote-hookev.installed", HookCapContract.MARKER_FILE_NAME)
    }

    @Test fun disableAllHooksFromSettingsAndSettingsLocal() {
        assertEquals(true, HookCapContract.combineDisableAllHooks(true, false))
        assertEquals(true, HookCapContract.combineDisableAllHooks(false, true))
        assertEquals(true, HookCapContract.combineDisableAllHooks(null, true)) // a definite yes wins over unknown
        assertEquals(false, HookCapContract.combineDisableAllHooks(false, false))
        assertNull(HookCapContract.combineDisableAllHooks(false, null))
        assertNull(HookCapContract.combineDisableAllHooks(null, false))
        assertEquals(false, HookCapContract.verdict(true, HookCapContract.combineDisableAllHooks(false, true)))
    }

    @Test fun versionCommCountsAsClaudeOnlyWhenTheExeIsUnderClaudeVersions() {
        val exe = "/home/u/.local/share/claude/versions/2.1.316"
        // bash(2) -> 2.1.316 (3, real claude) -> 2.1.316 (4, the bundled rg: same comm, other exe)
        val procs = listOf(
            Proc(2, 1, "bash"), Proc(3, 2, "2.1.316", exe), Proc(4, 3, "2.1.316", "/home/u/.local/share/claude/vendor/rg"),
        )
        assertEquals(3, HookCapContract.shallowestClaude(1, procs))
        // the rg alone is not a claude, and an unknown exe is not trusted
        assertNull(HookCapContract.shallowestClaude(1, listOf(Proc(4, 1, "2.1.316", "/x/rg"))))
        assertNull(HookCapContract.shallowestClaude(1, listOf(Proc(4, 1, "2.1.316", null))))
        // only dotted numbers are version comms
        assertNull(HookCapContract.shallowestClaude(1, listOf(Proc(4, 1, "2", exe), Proc(5, 1, "2.x", exe))))
        // the pane's own process may be the versioned binary
        assertEquals(1, HookCapContract.shallowestClaude(1, listOf(Proc(1, 0, "2.1.316", exe))))
    }

    @Test fun paneProcessThatIsAClaudeHelperIsNoClaude() {
        assertNull(HookCapContract.shallowestClaude(1, listOf(Proc(1, 0, "claude-helper"))))
        assertNull(HookCapContract.shallowestClaude(1, listOf(Proc(1, 0, "claude-helper"), Proc(2, 1, "node"))))
    }

    @Test fun shallowestClaudeHandlesRestartChains() {
        // pane bash(1) -> bash -lc(2) -> claude(3) -> claude helper(4)
        val chain = listOf(Proc(2, 1, "bash"), Proc(3, 2, "bash"), Proc(4, 3, "claude"), Proc(5, 4, "claude"), Proc(6, 4, "node"))
        assertEquals(4, HookCapContract.shallowestClaude(1, chain))
        // direct child
        assertEquals(9, HookCapContract.shallowestClaude(1, listOf(Proc(9, 1, "claude"), Proc(10, 9, "claude"))))
        // none
        assertNull(HookCapContract.shallowestClaude(1, listOf(Proc(2, 1, "bash"))))
        // another pane's claude is not in the subtree
        assertNull(HookCapContract.shallowestClaude(1, listOf(Proc(2, 7, "claude"))))
        // two at the same minimum depth: ambiguous
        assertNull(HookCapContract.shallowestClaude(1, listOf(Proc(2, 1, "claude"), Proc(3, 1, "claude"))))
        // a helper whose comm merely STARTS with "claude" is not a claude, even at the same depth as the real one
        assertEquals(4, HookCapContract.shallowestClaude(1, listOf(Proc(2, 1, "bash"), Proc(3, 2, "claude-helper"), Proc(4, 2, "claude"))))
        assertNull(HookCapContract.shallowestClaude(1, listOf(Proc(2, 1, "claudette"), Proc(3, 1, "claude-helper"))))
        // the pane's own process counts (depth 0): a pane that exec'd claude, with or without claude children
        assertEquals(1, HookCapContract.shallowestClaude(1, listOf(Proc(1, 0, "claude"), Proc(2, 1, "claude"), Proc(3, 1, "node"))))
        assertEquals(1, HookCapContract.shallowestClaude(1, listOf(Proc(1, 0, "claude"))))
        // pid cycle in a corrupt table does not hang
        assertNull(HookCapContract.shallowestClaude(1, listOf(Proc(2, 1, "bash"), Proc(1, 2, "bash"))))
    }
}
