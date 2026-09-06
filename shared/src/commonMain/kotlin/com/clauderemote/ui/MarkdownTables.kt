package com.clauderemote.ui

/**
 * Rewrites GFM tables as fixed-width text inside a fenced code block.
 *
 * Why: Slack (and Teams/Discord) cannot paste a table at all. Their paste
 * filters keep an allowlist of tags — paragraphs, bold, italic, code, lists —
 * and drop `<table>/<tr>/<td>` INCLUDING the structure, without inserting any
 * separator, so every cell runs into the next: "tvůj bododkud to pocházíco z
 * toho vyšlo1 · prodloužení nezabere…". Nothing we can do to the HTML fixes
 * that; a table simply has no representation there.
 *
 * A fenced code block does survive: those same filters render `<pre>` as a code
 * block, in a monospace font, where column alignment is preserved. So for that
 * target we spend the formatting we cannot use (a real table) on the one thing
 * that reads correctly (aligned columns).
 *
 * The transform runs on the MARKDOWN SOURCE, before rendering, so both
 * clipboard flavours benefit from one pass: the plain flavour becomes an
 * aligned table (good for a paste-as-plain-text anywhere), and running the
 * result through [MarkdownHtml.toHtml] turns the fence into `<pre><code>`,
 * which is exactly what a table-less rich target can display.
 */
internal object MarkdownTables {

    /**
     * Total line width to aim for. Slack's code blocks scroll horizontally
     * rather than wrap, and a wide table there is unreadable — so cells are
     * wrapped to keep rows within this budget.
     */
    const val DEFAULT_MAX_WIDTH = 100

    /** Never squeeze a column below this; past it, wrapping destroys more than it saves. */
    private const val MIN_COLUMN_WIDTH = 8

    fun alignTablesAsCodeBlocks(markdown: String, maxWidth: Int = DEFAULT_MAX_WIDTH): String {
        val lines = markdown.lines()
        val out = StringBuilder()
        var i = 0
        var insideFence = false
        while (i < lines.size) {
            val line = lines[i]
            // Never touch anything already inside a fenced block: a table drawn
            // in an example stays exactly as the author wrote it.
            if (line.trimStart().startsWith("```")) insideFence = !insideFence
            val table = if (insideFence) null else readTable(lines, i)
            if (table == null) {
                out.append(line)
                if (i < lines.size - 1) out.append('\n')
                i++
            } else {
                out.append("```\n")
                out.append(render(table.rows, maxWidth))
                out.append("```")
                if (table.endExclusive < lines.size) out.append('\n')
                i = table.endExclusive
            }
        }
        return out.toString()
    }

    private class Table(val rows: List<List<String>>, val endExclusive: Int)

    /**
     * A GFM table is a header row followed by a delimiter row (`|---|:--:|`).
     * Without that delimiter it is just text with pipes in it, which must be
     * left alone.
     */
    private fun readTable(lines: List<String>, start: Int): Table? {
        if (start + 1 >= lines.size) return null
        if (!looksLikeRow(lines[start])) return null
        if (!isDelimiterRow(lines[start + 1])) return null
        val header = splitCells(lines[start])
        val rows = mutableListOf(header)
        var i = start + 2
        while (i < lines.size && looksLikeRow(lines[i])) {
            // Pad/truncate to the header's shape so the grid stays rectangular.
            val cells = splitCells(lines[i])
            rows.add(List(header.size) { cells.getOrElse(it) { "" } })
            i++
        }
        return Table(rows, i)
    }

    private fun looksLikeRow(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("|") && t.length > 1 && t.drop(1).contains("|")
    }

    private fun isDelimiterRow(line: String): Boolean {
        val cells = splitCells(line)
        if (cells.isEmpty()) return false
        return cells.all { cell ->
            val c = cell.trim()
            c.isNotEmpty() && c.all { it == '-' || it == ':' } && c.contains('-')
        }
    }

    /** Split on unescaped `|`, dropping the outer pipes. */
    private fun splitCells(line: String): List<String> {
        val t = line.trim()
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            when {
                ch == '\\' && i + 1 < t.length && t[i + 1] == '|' -> { cur.append('|'); i += 2 }
                ch == '|' -> { cells.add(cur.toString()); cur.clear(); i++ }
                else -> { cur.append(ch); i++ }
            }
        }
        cells.add(cur.toString())
        // The outer pipes produce an empty cell at each end.
        if (cells.isNotEmpty() && cells.first().isBlank()) cells.removeAt(0)
        if (cells.isNotEmpty() && cells.last().isBlank()) cells.removeAt(cells.size - 1)
        return cells.map { stripInlineMarkdown(it.trim()) }
    }

    /**
     * Inside a code fence markdown is not rendered, so emphasis markers would
     * show up as literal `**` noise. Links keep their text, dropping the URL —
     * a URL inside an aligned column destroys the layout for no gain.
     */
    private fun stripInlineMarkdown(s: String): String {
        var r = s
        r = Regex("""\[([^\]]*)]\([^)]*\)""").replace(r) { it.groupValues[1] }
        r = Regex("""\*\*([^*]+)\*\*""").replace(r) { it.groupValues[1] }
        r = Regex("""__([^_]+)__""").replace(r) { it.groupValues[1] }
        r = Regex("""(?<![*\w])\*([^*]+)\*(?![*\w])""").replace(r) { it.groupValues[1] }
        r = Regex("""`([^`]+)`""").replace(r) { it.groupValues[1] }
        return r
    }

    /** Display width in characters; surrogate pairs count once. */
    private fun width(s: String): Int = s.count { !it.isLowSurrogate() }

    private fun render(rows: List<List<String>>, maxWidth: Int): String {
        val columns = rows.first().size
        if (columns == 0) return ""
        val widths = fitWidths(rows, columns, maxWidth)
        val sb = StringBuilder()
        rows.forEachIndexed { index, row ->
            sb.append(renderRow(row, widths))
            // The rule goes under the header, where it separates rather than
            // decorates — no bottom border, which only adds noise in a chat.
            if (index == 0) {
                sb.append(widths.joinToString("-+-") { "-".repeat(it) })
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * Natural widths when they fit, otherwise shave the widest column
     * repeatedly — that targets the one long prose column tables like this
     * usually have, instead of squeezing the short label columns too.
     */
    private fun fitWidths(rows: List<List<String>>, columns: Int, maxWidth: Int): List<Int> {
        val widths = IntArray(columns) { col -> rows.maxOf { width(it.getOrElse(col) { "" }) }.coerceAtLeast(1) }
        val separators = (columns - 1) * 3
        var total = widths.sum() + separators
        while (total > maxWidth) {
            val widest = widths.indices.maxByOrNull { widths[it] } ?: break
            if (widths[widest] <= MIN_COLUMN_WIDTH) break
            widths[widest]--
            total--
        }
        return widths.toList()
    }

    private fun renderRow(row: List<String>, widths: List<Int>): String {
        val wrapped = widths.mapIndexed { col, w -> wrap(row.getOrElse(col) { "" }, w) }
        val height = wrapped.maxOf { it.size }
        val sb = StringBuilder()
        for (line in 0 until height) {
            val cells = wrapped.mapIndexed { col, cellLines ->
                val text = cellLines.getOrElse(line) { "" }
                text + " ".repeat((widths[col] - width(text)).coerceAtLeast(0))
            }
            // Trailing padding on the last column is invisible and only makes
            // the copied text ragged, so drop it.
            sb.append(cells.joinToString(" | ").trimEnd())
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Greedy word wrap; a word longer than the column is hard-split. */
    private fun wrap(text: String, width: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(' ')) {
            var w = word
            while (width(w) > width) {
                val room = width - width(line.toString()) - if (line.isEmpty()) 0 else 1
                if (room > MIN_COLUMN_WIDTH / 2) {
                    if (line.isNotEmpty()) line.append(' ')
                    line.append(w.take(room))
                    w = w.drop(room)
                }
                out.add(line.toString())
                line = StringBuilder()
            }
            val extra = if (line.isEmpty()) width(w) else width(line.toString()) + 1 + width(w)
            if (extra > width && line.isNotEmpty()) {
                out.add(line.toString())
                line = StringBuilder(w)
            } else {
                if (line.isNotEmpty()) line.append(' ')
                line.append(w)
            }
        }
        if (line.isNotEmpty() || out.isEmpty()) out.add(line.toString())
        return out
    }
}
