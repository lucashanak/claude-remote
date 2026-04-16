package com.clauderemote.session

/**
 * Snapshot of the bottom rows of the rendered terminal screen, including
 * per-cell foreground color info so [ScreenStateClassifier] can distinguish
 * Claude's working indicator (dark red) from idle chat content.
 *
 * Produced on the platform thread that owns the emulator (main looper on
 * Android, EDT on Swing) and consumed by the shared classifier.
 */
class ScreenStateSnapshot(
    /** Rows ordered top-to-bottom of the VISIBLE viewport tail. */
    val rows: List<RowSnapshot>,
    /** Total column count of the underlying screen (for sanity / debugging). */
    val cols: Int,
)

class RowSnapshot(
    /** Plain text of the row, padded with spaces to column width. */
    val text: String,
    /**
     * For each cell in [text], whether the foreground color is "reddish" —
     * meaning ANSI red (index 1), bright red (index 9), a reddish 256-color
     * palette index, or a 24-bit color with a dominant red channel. Same
     * length as [text].
     */
    val isRedFg: BooleanArray,
)

/** Classifier output: what state Claude appears to be in on this snapshot. */
enum class ClaudeState {
    /** Working indicator visible → Claude is processing. */
    WORKING,
    /** Input box visible, no working indicator → Claude is waiting for input. */
    IDLE,
    /** Input box not visible / insufficient signal → don't trust this reading. */
    UNKNOWN,
}
