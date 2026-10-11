package com.clauderemote.ui

/**
 * Splits an assistant message into text and the images Claude asked to SHOW.
 *
 * The contract is markdown image syntax pointing at a file on the server:
 * `![a screenshot of the bug](/tmp/shot.png)`. That is the deliberate "look at
 * this" signal. A bare path in prose ("I edited res/icon.png") is NOT one — it
 * stays a tap-to-open link, because rendering every image Claude merely
 * mentions would bury the conversation under thumbnails of files it was only
 * talking about.
 *
 * Pure, so the edge cases that decide what renders are tested rather than
 * eyeballed: images inside code blocks are examples, not images to show;
 * http(s) URLs are not server files; a non-image extension stays text.
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
        var inFence = false
        var fenceMarker = ""
        val lines = markdown.split('\n')
        lines.forEachIndexed { index, line ->
            val suffix = if (index < lines.size - 1) "\n" else ""
            val trimmed = line.trimStart()
            val opensOrClosesFence = trimmed.startsWith("```") || trimmed.startsWith("~~~")
            if (opensOrClosesFence) {
                val marker = trimmed.take(3)
                if (!inFence) {
                    inFence = true
                    fenceMarker = marker
                } else if (marker == fenceMarker) {
                    inFence = false
                }
                pending.append(line).append(suffix)
                return@forEachIndexed
            }
            if (inFence) {
                // An image inside a code block is an EXAMPLE of the syntax.
                pending.append(line).append(suffix)
                return@forEachIndexed
            }
            splitLine(line, pending, out, ::flush)
            pending.append(suffix)
        }
        flush()
        return out
    }

    private fun splitLine(
        line: String,
        pending: StringBuilder,
        out: MutableList<Segment>,
        flush: () -> Unit,
    ) {
        val codeSpans = inlineCodeRanges(line)
        var cursor = 0
        for (match in IMAGE.findAll(line)) {
            if (codeSpans.any { match.range.first in it }) continue
            val target = match.groupValues[2].removeSurrounding("<", ">").trim()
            if (!isShowableServerImage(target)) continue
            pending.append(line, cursor, match.range.first)
            flush()
            out += Segment.Image(alt = match.groupValues[1].trim(), path = target)
            cursor = match.range.last + 1
        }
        pending.append(line, cursor, line.length)
    }

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
     * A path the app can fetch from the session's server and decode. Remote
     * URLs are deliberately excluded: the app has no business fetching
     * arbitrary internet content on Claude's say-so, and nothing here could
     * render them anyway.
     */
    fun isShowableServerImage(target: String): Boolean {
        if (target.isBlank()) return false
        val lower = target.lowercase()
        if ("://" in lower || lower.startsWith("data:") || lower.startsWith("mailto:")) return false
        return isImagePath(target.substringBefore('?').substringBefore('#'))
    }
}
