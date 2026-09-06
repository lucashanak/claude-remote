package com.clauderemote.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MarkdownTables] — the "Copy for Slack" transform. Slack drops `<table>` and
 * everything in it without inserting a single separator, so a copied table
 * arrives as one run-together string; a fenced code block is the one shape that
 * survives with its alignment intact.
 */
class MarkdownTablesTest {

    private fun transform(md: String, maxWidth: Int = 100) =
        MarkdownTables.alignTablesAsCodeBlocks(md, maxWidth)

    @Test
    fun tableBecomesAnAlignedCodeBlock() {
        val md = """
            | bod | odkud | vysledek |
            |---|---|---|
            | 1 | produkce | doloženo |
            | 22 | nová funkce | ne |
        """.trimIndent()
        assertEquals(
            """
            ```
            bod | odkud       | vysledek
            ----+-------------+---------
            1   | produkce    | doloženo
            22  | nová funkce | ne
            ```
            """.trimIndent(),
            transform(md),
        )
    }

    /** Column widths must come from the widest cell, header included. */
    @Test
    fun columnsAreWidenedToTheLongestCell() {
        val out = transform("| a | b |\n|---|---|\n| xxxxxxxx | y |")
        val lines = out.lines()
        assertEquals("a        | b", lines[1])
        assertEquals("---------+--", lines[2])
        assertEquals("xxxxxxxx | y", lines[3])
    }

    /**
     * The real message that started this: a long prose column. Rows must wrap
     * inside the budget instead of running off the side, because Slack's code
     * blocks scroll rather than wrap.
     */
    @Test
    fun longCellsWrapWithinTheWidthBudget() {
        val md = "| bod | co z toho vyšlo |\n|---|---|\n" +
            "| 1 · prodloužení nezabere | doloženo — 1 ze 6 žádostí model zkrátil, pravidlo jsem doplnil a měření ho zatím nepotvrdilo |"
        val out = transform(md, maxWidth = 60)
        val body = out.lines().filter { it.isNotBlank() && it != "```" }
        assertTrue(body.all { it.length <= 60 }, "a line exceeded the budget:\n$out")
        assertTrue(body.size > 3, "the long cell did not wrap onto extra lines:\n$out")
        // Wrapping must not lose words.
        val joined = body.drop(2).joinToString(" ") { it.substringAfter("|", it) }
        assertTrue(joined.contains("nepotvrdilo"), "wrapping dropped text:\n$out")
    }

    /** Alignment is worthless if the columns do not line up on every row. */
    @Test
    fun everyRowSharesTheSameColumnOffsets() {
        val out = transform("| a | b | c |\n|---|---|---|\n| 1 | 22 | 333 |\n| 4444 | 5 | 6 |")
        val rows = out.lines().filter { it.isNotBlank() && it != "```" && !it.startsWith("-") }
        val offsets = rows.map { row -> row.indices.filter { row[it] == '|' } }
        assertTrue(offsets.distinct().size == 1, "columns are ragged:\n$out")
    }

    @Test
    fun escapedPipesStayInsideTheCell() {
        val out = transform("| expr | note |\n|---|---|\n| a \\| b | ok |")
        assertTrue(out.contains("a | b"), out)
        assertTrue(out.lines().first { it.startsWith("a") }.count { it == '|' } == 2, out)
    }

    /** Inside a fence markdown is not rendered, so markers would be literal noise. */
    @Test
    fun inlineMarkdownIsStrippedFromCells() {
        val out = transform("| a | b |\n|---|---|\n| **bold** | `code` |\n| *it* | [text](http://x) |")
        // The fence itself is backticks, so only the table body may be checked.
        val body = out.lines().filter { it.isNotBlank() && it != "```" }.joinToString("\n")
        assertTrue(body.contains("bold"), out)
        assertTrue(!body.contains("**"), "bold markers survived: $out")
        assertTrue(!body.contains("`"), "code markers survived: $out")
        assertTrue(body.contains("text") && !body.contains("http://x"), "link not flattened: $out")
    }

    // ---- everything that must NOT be touched --------------------------------

    @Test
    fun textWithoutTablesIsUnchanged() {
        val md = "Ahoj **světe**\n\n- one\n- two\n\nkonec"
        assertEquals(md, transform(md))
    }

    /** Pipes without a delimiter row are prose, not a table. */
    @Test
    fun pipeTextWithoutADelimiterRowIsLeftAlone() {
        val md = "| tohle není tabulka |\ndruhý řádek"
        assertEquals(md, transform(md))
    }

    /** A table drawn inside an example block belongs to the author. */
    @Test
    fun tablesInsideAFenceAreLeftAlone() {
        val md = "```\n| a | b |\n|---|---|\n| 1 | 2 |\n```"
        assertEquals(md, transform(md))
    }

    @Test
    fun surroundingProseIsPreservedAroundTheTable() {
        val md = "před\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\npo"
        val out = transform(md)
        assertTrue(out.startsWith("před\n\n```"), out)
        assertTrue(out.endsWith("```\n\npo"), out)
    }

    @Test
    fun raggedRowsArePaddedToTheHeaderShape() {
        val out = transform("| a | b | c |\n|---|---|---|\n| 1 |\n| 1 | 2 | 3 | 4 |")
        val rows = out.lines().filter { it.isNotBlank() && it != "```" && !it.startsWith("-") }
        assertTrue(rows.all { it.count { ch -> ch == '|' } <= 2 }, "extra cells leaked through:\n$out")
    }

    /**
     * End to end: the point of the transform is that the rendered HTML carries
     * a `<pre><code>` block (which Slack shows as a code block) and no
     * `<table>` (which it silently flattens).
     */
    @Test
    fun renderedHtmlHasNoTableAndKeepsThePreBlock() {
        val md = "Text:\n\n| a | b |\n|---|---|\n| 1 | 2 |\n"
        val html = MarkdownHtml.toHtml(transform(md))
        assertTrue(!html.contains("<table"), "a table survived into the Slack payload: $html")
        assertTrue(html.contains("<pre><code>"), html)
        assertTrue(html.contains("a | b"), "alignment lost before it reached the clipboard: $html")
    }
}
