package com.clauderemote.session

/**
 * Classifies a [ScreenStateSnapshot] as Claude working / idle / unknown.
 *
 * Heuristic (validated against real Claude Code screenshots):
 *  - The input box (`╭──╮` / `│ ❯ ... │` / `╰──╯`) is always at the bottom
 *    in both states — its presence alone is not a signal.
 *  - Directly above the box is a slot that holds the "working indicator":
 *    e.g. `* Flibbertigibbeting…`, `* Cadoleting…` — different verb each
 *    time, but always rendered in dark red.
 *  - When idle, that slot is either empty or holds dim/gray completion text
 *    like `Churned for 1m 27s` — not red.
 *
 * So: find the input box row, scan the few rows above it, and if ANY
 * non-whitespace / non-box-drawing cell has a red foreground → WORKING.
 * Otherwise → IDLE.
 *
 * Intentionally ignores text content / verbs / spinner chars — color alone is
 * the robust signal. Claude Code picks a fresh silly verb every turn.
 */
object ScreenStateClassifier {

    /** Claude's prompt marker. */
    private const val PROMPT_MARKER = '\u276F' // ❯

    /** How many rows above the input-box row to scan for a red indicator. */
    private const val INDICATOR_LOOKBACK = 4

    fun classify(snapshot: ScreenStateSnapshot): ClaudeState {
        val boxRow = findInputBoxRow(snapshot) ?: return ClaudeState.UNKNOWN

        val scanStart = (boxRow - INDICATOR_LOOKBACK).coerceAtLeast(0)
        for (r in scanStart until boxRow) {
            val row = snapshot.rows[r]
            val text = row.text
            val reds = row.isRedFg
            for (i in text.indices) {
                if (i >= reds.size) break
                val ch = text[i]
                if (!isContentCell(ch)) continue
                if (reds[i]) return ClaudeState.WORKING
            }
        }

        return ClaudeState.IDLE
    }

    /**
     * Find the row (index into [ScreenStateSnapshot.rows]) that contains the
     * `❯` prompt marker. Search from the bottom up — the input box is always
     * at the very bottom of Claude's TUI.
     */
    private fun findInputBoxRow(snapshot: ScreenStateSnapshot): Int? {
        for (r in snapshot.rows.indices.reversed()) {
            if (snapshot.rows[r].text.contains(PROMPT_MARKER)) return r
        }
        return null
    }

    /**
     * Skip whitespace and box-drawing characters when scanning for red cells —
     * the box frame itself may or may not be styled, and we care only about
     * actual text content in the indicator slot.
     */
    private fun isContentCell(ch: Char): Boolean {
        if (ch.isWhitespace()) return false
        // Unicode Box Drawing block (U+2500..U+257F)
        if (ch in '\u2500'..'\u257F') return false
        return true
    }
}
