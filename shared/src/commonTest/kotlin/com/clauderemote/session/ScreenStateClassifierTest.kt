package com.clauderemote.session

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [ScreenStateClassifier], focused on the approval/permission
 * detection added for #71. False-positive-sensitive: assistant prose that
 * mentions approval-like words must NOT be classified as APPROVAL.
 */
class ScreenStateClassifierTest {

    private val prompt = '❯' // ❯

    /** Build a snapshot from plain row text; no red foreground, fixed width. */
    private fun snapshotOf(vararg rows: String): ScreenStateSnapshot {
        val cols = rows.maxOfOrNull { it.length } ?: 0
        return ScreenStateSnapshot(
            rows = rows.map { text ->
                val padded = text.padEnd(cols)
                RowSnapshot(text = padded, isRedFg = BooleanArray(padded.length))
            },
            cols = cols,
        )
    }

    /** Build a snapshot where exactly one row has all-red foreground. */
    private fun snapshotWithRedRow(redRowIndex: Int, vararg rows: String): ScreenStateSnapshot {
        val cols = rows.maxOfOrNull { it.length } ?: 0
        return ScreenStateSnapshot(
            rows = rows.mapIndexed { i, text ->
                val padded = text.padEnd(cols)
                val red = BooleanArray(padded.length) { i == redRowIndex }
                RowSnapshot(text = padded, isRedFg = red)
            },
            cols = cols,
        )
    }

    @Test
    fun permissionDialogNumberedSelector_isApproval() {
        val snapshot = snapshotOf(
            "Allow Bash to run this command?",
            "$prompt 1. Yes",
            "  2. Yes, and don't ask again",
            "  3. No",
        )
        assertEquals(ClaudeState.APPROVAL, ScreenStateClassifier.classify(snapshot))
    }

    @Test
    fun shellConfirmPrompt_isApproval() {
        // A real shell confirm renders [Y/n] on the bottom-most line (the prompt
        // line itself), which is also where the ❯ input cursor sits.
        val snapshot = snapshotOf(
            "Some preceding output",
            "$prompt Overwrite existing file? [Y/n]",
        )
        assertEquals(ClaudeState.APPROVAL, ScreenStateClassifier.classify(snapshot))
    }

    @Test
    fun idleInputBoxWithApprovalProse_isIdleNotApproval() {
        // Assistant prose mentioning "Do you want to proceed?" must NOT
        // false-positive — the numbered-selector pointer is absent.
        val snapshot = snapshotOf(
            "I've finished the change. Do you want to proceed?",
            "$prompt ",
        )
        assertEquals(ClaudeState.IDLE, ScreenStateClassifier.classify(snapshot))
    }

    @Test
    fun redWorkingIndicatorAboveBox_isWorking() {
        // A real working indicator must win even if an approval-ish row exists.
        val snapshot = snapshotWithRedRow(
            redRowIndex = 0,
            "* Flibbertigibbeting…",
            "$prompt 1. Yes",
        )
        assertEquals(ClaudeState.WORKING, ScreenStateClassifier.classify(snapshot))
    }

    @Test
    fun noPromptMarker_isUnknown() {
        val snapshot = snapshotOf(
            "some scrolled-back content",
            "with no input box visible",
        )
        assertEquals(ClaudeState.UNKNOWN, ScreenStateClassifier.classify(snapshot))
    }

    // --- FIX 1: single numbered line typed by user must NOT trigger APPROVAL ---

    @Test
    fun userTypesNumberedListInInputBox_isIdle() {
        // The user types "❯ 1. fix the parser bug" — one pointer line, no sibling.
        // Without the ≥2-option guard this would false-positive as APPROVAL.
        val snapshot = snapshotOf(
            "$prompt 1. fix the parser bug",
        )
        assertEquals(ClaudeState.IDLE, ScreenStateClassifier.classify(snapshot))
    }

    // --- FIX 1 + frame tolerance: box-frame-wrapped selector must still be APPROVAL ---

    @Test
    fun boxFrameWrappedSelector_isApproval() {
        // Claude renders the permission dialog inside a bordered TUI box.
        // The pointer and sibling lines both have leading box-frame characters.
        val snapshot = snapshotOf(
            "│ $prompt 1. Yes │",
            "│   2. No │",
        )
        assertEquals(ClaudeState.APPROVAL, ScreenStateClassifier.classify(snapshot))
    }

    // --- FIX 2: [Y/n] in assistant prose (not the bottom row) must NOT trigger APPROVAL ---

    @Test
    fun ynInAssistantProseAboveInputBox_isIdle() {
        // "[Y/n]" appears in a prose line above the input box; the bottom row is
        // just the empty input box. Only the bottom-most non-empty row is checked
        // for the shell-confirm pattern, so this must NOT classify as APPROVAL.
        val snapshot = snapshotOf(
            "Do you want to overwrite? [Y/n]",
            "$prompt ",
        )
        assertEquals(ClaudeState.IDLE, ScreenStateClassifier.classify(snapshot))
    }
}
