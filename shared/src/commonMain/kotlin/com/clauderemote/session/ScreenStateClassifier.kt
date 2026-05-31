package com.clauderemote.session

/**
 * Classifies a [ScreenStateSnapshot] as Claude working / idle / unknown.
 *
 * Three independent "still working" signals — any one of them → WORKING:
 *
 * 1. **Red working indicator above the input box.** The silly-verb line
 *    (`* Flibbertigibbeting…`, `* Cadoleting…`) is always dark red. Text
 *    content varies every turn, so we match by color, not text.
 *
 * 2. **Spinner characters above the input box.** Claude Code uses spinner
 *    glyphs (`○ ◉ ⠋ ⠙ …`) for status indicators that aren't necessarily red —
 *    e.g. the periodic "Rating…" survey popup that can appear while Claude
 *    is still thinking. Presence of any spinner glyph in the tail above the
 *    box → Claude is active.
 *
 * 3. **Elapsed time in the Claude status line below the input box.** Claude
 *    Code renders `5m 20s` (or `45s`) in the status line only while a turn
 *    is in progress; when idle, the elapsed-time field is absent. Matching
 *    `Nm Ns` / `Ns` in rows below the box is a robust "turn active" signal.
 *
 * Only if ALL three signals are absent do we return IDLE. The input box
 * itself must be visible (`❯` marker) — otherwise UNKNOWN (viewport
 * scrolled, startup, etc).
 */
object ScreenStateClassifier {

    /** Claude's prompt marker. */
    private const val PROMPT_MARKER = '\u276F' // ❯

    /** How many rows above the input-box row to scan for working indicators. */
    private const val INDICATOR_LOOKBACK = 5

    /** Glyphs Claude Code uses for live status / rating / progress. */
    private val SPINNER_CHARS = setOf(
        // Circles used in "Rating…" survey and other live popups
        '\u25CB', '\u25C9', '\u25CE', '\u25EF', // ○ ◉ ◎ ◯
        // Braille progress spinners
        '\u280B', '\u2819', '\u2839', '\u2838', '\u283C', '\u2834', '\u2826', '\u2827', '\u2807', '\u280F',
        // Asterisk variants
        '\u2722', '\u2733', '\u273D', '\u273B', '\u273A',
        // Clock progress
        '\u23F3', '\u23F1', '\u23F2',
    )

    /**
     * Matches elapsed time as rendered in Claude Code's status line while a
     * turn is in progress — e.g. `5m 20s`, `45s`, `1h 3m 0s`. Idle state
     * omits this field entirely.
     *
     * Anchored with `\b` so it doesn't match the trailing `s` of `169ms`.
     */
    private val ELAPSED_TIME_REGEX = Regex(
        pattern = """\b\d+h\s*\d+m\s*\d+s\b|\b\d+m\s*\d+s\b|\b[1-9]\d*s\b"""
    )

    /**
     * Matches the `❯ N.` selected-option pointer of Claude Code's numbered
     * selector (permission dialog AND the AskUserQuestion TUI).
     */
    private val APPROVAL_POINTER_REGEX = Regex("""❯\s*\d+\.\s""")

    /**
     * Matches the `N. ` option-number pattern on a row that has NO pointer glyph.
     * Used together with [APPROVAL_POINTER_REGEX]: we require BOTH a pointer row
     * AND at least one sibling row to avoid false-positives when the user types a
     * numbered list into the input box (only one `❯ N.` line, no sibling). Real
     * selectors always render ≥2 options. Not anchored at start so box-frame
     * characters (`│   2. No │`) are tolerated.
     */
    private val APPROVAL_SIBLING_REGEX = Regex("""\d+\.\s""")

    fun classify(snapshot: ScreenStateSnapshot): ClaudeState {
        val boxRow = findInputBoxRow(snapshot) ?: return ClaudeState.UNKNOWN

        // Signal 1 + 2: scan the tail ABOVE the input box for red cells or spinner glyphs.
        val scanStart = (boxRow - INDICATOR_LOOKBACK).coerceAtLeast(0)
        for (r in scanStart until boxRow) {
            val row = snapshot.rows[r]
            val text = row.text
            val reds = row.isRedFg
            for (i in text.indices) {
                val ch = text[i]
                if (ch in SPINNER_CHARS) return ClaudeState.WORKING
                if (i >= reds.size) continue
                if (!isContentCell(ch)) continue
                if (reds[i]) return ClaudeState.WORKING
            }
        }

        // Signal 3: Claude status line below the input box shows elapsed time
        // while a turn is running.
        for (r in (boxRow + 1) until snapshot.rows.size) {
            if (ELAPSED_TIME_REGEX.containsMatchIn(snapshot.rows[r].text)) {
                return ClaudeState.WORKING
            }
        }

        // Approval check — runs AFTER the WORKING scans (a real working
        // indicator always wins) and BEFORE returning IDLE. Conservative,
        // structural markers only. Note: the 8-row snapshot window may miss the
        // first option of a very tall AskUserQuestion (acceptable — #70 covers
        // that via the transcript; this targets permission prompts).
        if (isApprovalSelector(snapshot)) return ClaudeState.APPROVAL

        return ClaudeState.IDLE
    }

    /**
     * Detect Claude's approval/permission selector by structure, not prose.
     *
     * Numbered-option check (FIX 1): require BOTH a pointer row (`❯ N.`) AND a
     * separate sibling row (`  N.` without `❯`). A user typing "❯ 1. fix the
     * parser bug" into the input box produces only one `❯ N.` line with no
     * sibling → no false positive. Real selectors always render ≥2 options.
     *
     * Shell-confirm check (FIX 2): only test the bottom-most non-empty row.
     * `[Y/n]` in assistant prose appears higher in the viewport, not on the
     * last line — restricting to the last non-blank row eliminates those hits.
     */
    private fun isApprovalSelector(snapshot: ScreenStateSnapshot): Boolean {
        // Numbered-option selector: need pointer row + at least one sibling row.
        var hasPointer = false
        var hasSibling = false
        for (row in snapshot.rows) {
            val text = row.text
            if (APPROVAL_POINTER_REGEX.containsMatchIn(text)) hasPointer = true
            // A sibling must NOT also contain the pointer glyph (rules out the
            // pointer row itself matching the sibling regex).
            else if (APPROVAL_SIBLING_REGEX.containsMatchIn(text)) hasSibling = true
        }
        if (hasPointer && hasSibling) return true

        // Shell confirm prompt: only the bottom-most non-empty row counts.
        val lastNonEmpty = snapshot.rows.lastOrNull { it.text.isNotBlank() }
        if (lastNonEmpty != null && Regex("""\[[Yy]/[Nn]\]""").containsMatchIn(lastNonEmpty.text)) return true

        return false
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
