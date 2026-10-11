package com.clauderemote.ui

/**
 * Splits an assistant message into text and the images Claude asked to SHOW.
 *
 * The contract is markdown image syntax pointing at a file on the server, on a
 * line of its own:
 *
 *     ![a screenshot of the bug](/tmp/shot.png)
 *
 * That is the deliberate "look at this" signal. A bare path in prose ("I
 * edited res/icon.png") is not one and stays a tap-to-open link — rendering
 * every image Claude merely mentions would bury the conversation in thumbnails.
 *
 * Only a line holding nothing but images is split out. Each text segment is
 * rendered by its own markdown pass, so cutting INSIDE a block broke it: a table
 * row with an image became two half-tables, a list item lost its tail to a stray
 * paragraph, a quote rendered empty above the image. An image that sits inside
 * such a structure is therefore rewritten in place as plain text — its alt and
 * its path, which the file-link pass then makes tappable — and the structure
 * stays whole.
 *
 * Pure, so the edge cases that decide what renders are tested rather than
 * eyeballed.
 */
internal object InlineImages {

    sealed interface Segment {
        data class Text(val markdown: String) : Segment
        data class Image(val alt: String, val path: String) : Segment
    }

    // `![alt](target)` — target may carry a "title" and may be <bracketed>.
    private val IMAGE = Regex("""!\[([^\]\n]*)]\(\s*(<[^>\n]+>|[^)\s]+)(?:\s+"[^"\n]*")?\s*\)""")

    fun split(markdown: String): List<Segment> {
        if (!markdown.contains("![")) return listOf(Segment.Text(markdown))
        val out = mutableListOf<Segment>()
        val pending = StringBuilder()
        fun flush() {
            if (pending.isNotEmpty()) {
                out += Segment.Text(pending.toString())
                pending.clear()
            }
        }
        var fence: Fence? = null
        val lines = markdown.split('\n')
        lines.forEachIndexed { index, line ->
            val suffix = if (index < lines.size - 1) "\n" else ""
            val open = fence
            if (open != null) {
                // Inside a code block the syntax is an EXAMPLE, never an image.
                if (open.closedBy(line)) fence = null
                pending.append(line).append(suffix)
                return@forEachIndexed
            }
            Fence.openedBy(line)?.let {
                fence = it
                pending.append(line).append(suffix)
                return@forEachIndexed
            }
            if (isIndentedCode(line)) {
                pending.append(line).append(suffix)
                return@forEachIndexed
            }
            val standalone = standaloneImages(line)
            if (standalone != null) {
                flush()
                standalone.forEach { out += it }
                // The newline after an image line belongs to the layout between
                // segments, which the renderer spaces itself.
                return@forEachIndexed
            }
            pending.append(rewriteEmbedded(line)).append(suffix)
        }
        flush()
        return out
    }

    /**
     * A fenced code block: CommonMark's rules, which matter here because Claude
     * shows markdown that itself contains a fence by wrapping it in a LONGER
     * fence. The closer must use the same character, be at least as long, and
     * carry nothing else — an inner three-backtick line, or a line with an info
     * string, does not close a four-backtick block.
     */
    private class Fence(val char: Char, val length: Int) {
        fun closedBy(line: String): Boolean {
            val t = line.trimStart()
            if (line.length - t.length > 3) return false
            val run = t.takeWhile { it == char }.length
            return run >= length && t.substring(run).isBlank()
        }

        companion object {
            fun openedBy(line: String): Fence? {
                val t = line.trimStart()
                if (line.length - t.length > 3) return null
                val c = t.firstOrNull() ?: return null
                if (c != '`' && c != '~') return null
                val run = t.takeWhile { it == c }.length
                if (run < 3) return null
                // A backtick fence's info string may not contain a backtick.
                if (c == '`' && t.substring(run).contains('`')) return null
                return Fence(c, run)
            }
        }
    }

    /** Four spaces or a tab of indentation is a code block in CommonMark. */
    private fun isIndentedCode(line: String): Boolean =
        line.isNotBlank() && (line.startsWith("    ") || line.startsWith("\t"))

    /** The images on a line that holds nothing else, or null. */
    private fun standaloneImages(line: String): List<Segment.Image>? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("![")) return null
        val found = mutableListOf<Segment.Image>()
        var rest = trimmed
        while (rest.isNotEmpty()) {
            val m = IMAGE.matchAt(rest, 0) ?: return null
            val target = targetOf(m)
            if (!isShowableServerImage(target)) return null
            found += Segment.Image(alt = m.groupValues[1].trim(), path = target)
            rest = rest.substring(m.range.last + 1).trimStart()
        }
        return found.takeIf { it.isNotEmpty() }
    }

    /**
     * An image embedded in a sentence, list, table, quote or link: rewritten as
     * text so the surrounding markdown stays one block. Untouched inside inline
     * code, when escaped, or when it is not a showable server image at all.
     */
    private fun rewriteEmbedded(line: String): String {
        val codeSpans = inlineCodeRanges(line)
        val sb = StringBuilder()
        var cursor = 0
        for (m in IMAGE.findAll(line)) {
            val start = m.range.first
            if (codeSpans.any { start in it }) continue
            if (start > 0 && line[start - 1] == '\\') continue
            val target = targetOf(m)
            if (!isShowableServerImage(target)) continue
            sb.append(line, cursor, start)
            val alt = m.groupValues[1].trim()
            sb.append(if (alt.isBlank()) target else "$alt ($target)")
            cursor = m.range.last + 1
        }
        sb.append(line, cursor, line.length)
        return sb.toString()
    }

    private fun targetOf(m: MatchResult): String = m.groupValues[2].removeSurrounding("<", ">").trim()

    /** Ranges covered by `inline code`, where image syntax is literal text. */
    private fun inlineCodeRanges(line: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i < line.length) {
            if (line[i] == '`') {
                var ticks = 0
                while (i + ticks < line.length && line[i + ticks] == '`') ticks++
                val fence = "`".repeat(ticks)
                val close = line.indexOf(fence, i + ticks)
                if (close < 0) break
                ranges += i..(close + ticks - 1)
                i = close + ticks
            } else {
                i++
            }
        }
        return ranges
    }

    /**
     * A path the app can fetch from the session's server and decode.
     *
     * Remote URLs and data: URIs are excluded — the app has no business fetching
     * internet content on Claude's say-so. So are `*`, `?` and `[`: SFTP treats
     * them as GLOB characters, so `/tmp/b.jpg?v=2` would fail and a `*.png`
     * wildcard would quietly fetch whichever file happened to match.
     */
    fun isShowableServerImage(target: String): Boolean {
        if (target.isBlank()) return false
        val lower = target.lowercase()
        if ("://" in lower || lower.startsWith("data:") || lower.startsWith("mailto:") || lower.startsWith("file:")) {
            return false
        }
        if (target.any { it == '*' || it == '?' || it == '[' }) return false
        return isImagePath(target)
    }
}
