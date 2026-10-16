package com.clauderemote.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [parseMarkdownTable] decides what each cell of a chat table says. It exists
 * because the card used to split rows itself, on every `|` and with its own
 * emphasis stripper — so a cell containing an escaped `\|` shifted the rest of
 * the row one column right, and a link rendered as raw `[text](url)` in the
 * grid. The clipboard path ([MarkdownTables.splitCells]) already got both
 * right; these tests pin the card to that one implementation.
 */
class MarkdownTableCellsTest {

    @Test
    fun headerAndBodyAreTakenFromAroundTheDelimiterRow() {
        val raw = "| name | size |\n|---|---:|\n| a.txt | 12 |\n| b.txt | 7 |"
        val t = parseMarkdownTable(raw)
        assertEquals(listOf("name", "size"), t.header)
        assertEquals(listOf(listOf("a.txt", "12"), listOf("b.txt", "7")), t.body)
    }

    @Test
    fun anEscapedPipeStaysInsideItsCellInsteadOfShiftingTheRow() {
        // `a \| b` is ONE cell holding "a | b" — splitting on it moved every
        // later cell one column to the right.
        val raw = "| cmd | note |\n|---|---|\n| rg a \\| wc | counts |"
        val t = parseMarkdownTable(raw)
        assertEquals(listOf(listOf("rg a | wc", "counts")), t.body)
    }

    @Test
    fun aLinkKeepsItsTextAndDropsItsUrl() {
        // A URL in a narrow phone column is noise; the raw markdown was worse.
        val raw = "| file | where |\n|---|---|\n| [App.kt](https://example.test/App.kt) | shared |"
        val t = parseMarkdownTable(raw)
        assertEquals(listOf(listOf("App.kt", "shared")), t.body)
    }

    @Test
    fun emphasisAndCodeMarkersAreStripped() {
        val raw = "| **what** | `how` |\n|---|---|\n| *fast* | __done__ |"
        val t = parseMarkdownTable(raw)
        assertEquals(listOf("what", "how"), t.header)
        assertEquals(listOf(listOf("fast", "done")), t.body)
    }

    @Test
    fun aTableWithoutADelimiterRowFallsBackToFirstRowAsHeader() {
        val raw = "| a | b |\n| c | d |"
        val t = parseMarkdownTable(raw)
        assertEquals(listOf("a", "b"), t.header)
        assertEquals(listOf(listOf("c", "d")), t.body)
    }

    @Test
    fun blankLinesAreIgnoredAndShortRowsAreKeptAsTheyAre() {
        // Padding short rows is the grid's job (numCols), not the splitter's.
        val raw = "| a | b | c |\n|---|---|---|\n\n| x |\n"
        val t = parseMarkdownTable(raw)
        assertEquals(listOf("a", "b", "c"), t.header)
        assertEquals(listOf(listOf("x")), t.body)
    }

    @Test
    fun emptyInputYieldsAnEmptyTableRatherThanThrowing() {
        val t = parseMarkdownTable("")
        assertEquals(emptyList(), t.header)
        assertEquals(emptyList(), t.body)
    }
}
