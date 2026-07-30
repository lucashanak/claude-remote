package com.clauderemote.ui

/**
 * Turns file paths that Claude mentions in its answers into tappable links, so
 * "I wrote the report to `out/report.pdf`" can be downloaded with one tap
 * instead of retyping the path into the manual download dialog.
 *
 * Detected paths are rewritten into ordinary markdown links whose destination
 * carries the private [REMOTE_FILE_SCHEME]; `RichBody` installs a `UriHandler`
 * that intercepts that scheme and leaves every other link to the platform.
 */

/** Private URI scheme marking a link that points at a file on the session's server. */
const val REMOTE_FILE_SCHEME = "crfile://"

/** A file path found in assistant text, as a half-open `[start, end)` range over the source. */
data class FilePathMatch(val path: String, val start: Int, val end: Int)

/**
 * The last path segment must look like `name.ext` — an extension starting with a
 * letter. This is what separates `src/main.kt` from `and/or`, `24/7` or `v1.2.3`.
 */
private val FILE_SEGMENT = Regex("""^[^/]*\.[A-Za-z][A-Za-z0-9_+-]{0,9}$""")

/** Existing markdown links/images — already clickable, never re-linkify them. */
private val MARKDOWN_LINK = Regex("""!?\[[^\]\n]*\]\([^)\s]*\)""")

/** `<https://…>` autolinks. */
private val AUTOLINK = Regex("""<[^>\s]+>""")

/** Sentence punctuation that can trail a path in prose but is never part of it. */
private const val TRAILING_PUNCT = ".,;:!?"

private fun isPathChar(ch: Char): Boolean =
    ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
        ch == '.' || ch == '_' || ch == '-' || ch == '+' ||
        ch == '~' || ch == '/' || ch == '@'

/**
 * Marks regions that must be left alone: fenced and indented code blocks (markdown
 * isn't parsed there, so an injected link would render as literal `[x](crfile://x)`
 * garbage) plus existing links and autolinks.
 */
private fun buildMask(text: String): BooleanArray {
    val mask = BooleanArray(text.length)

    var inFence = false
    var fenceMarker = ""
    var lineStart = 0
    while (lineStart <= text.length) {
        val nl = text.indexOf('\n', lineStart)
        val lineEnd = if (nl < 0) text.length else nl
        val line = text.substring(lineStart, lineEnd)
        val trimmed = line.trimStart()
        val fence = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        val indentedCode = !inFence && line.isNotBlank() &&
            (line.startsWith("\t") || line.startsWith("    "))
        when {
            inFence -> {
                for (k in lineStart until lineEnd) mask[k] = true
                if (fence == fenceMarker) inFence = false
            }
            fence != null -> {
                for (k in lineStart until lineEnd) mask[k] = true
                inFence = true
                fenceMarker = fence
            }
            indentedCode -> for (k in lineStart until lineEnd) mask[k] = true
        }
        if (nl < 0) break
        lineStart = nl + 1
    }

    for (re in listOf(MARKDOWN_LINK, AUTOLINK)) {
        for (m in re.findAll(text)) {
            for (k in m.range) mask[k] = true
        }
    }
    return mask
}

/**
 * Finds file paths in [text]. A candidate is accepted only when it either contains
 * a `/` or is wrapped in inline-code backticks — that single rule is what keeps
 * prose like "e.g." or "N/A" from turning into links.
 */
fun detectRemoteFilePaths(text: String): List<FilePathMatch> {
    if (text.isEmpty()) return emptyList()
    val mask = buildMask(text)
    val out = mutableListOf<FilePathMatch>()
    var i = 0
    while (i < text.length) {
        if (mask[i] || !isPathChar(text[i])) {
            i++
            continue
        }
        var runEnd = i
        while (runEnd < text.length && !mask[runEnd] && isPathChar(text[runEnd])) runEnd++
        val runStart = i
        i = runEnd

        // A run directly after ':' is the tail of a URL (`https://host/x.png`),
        // not a path — the ':' itself isn't a path char so the run starts at '//'.
        if (runStart > 0 && text[runStart - 1] == ':') continue

        var end = runEnd
        while (end > runStart && text[end - 1] in TRAILING_PUNCT) end--
        acceptPath(text, runStart, end)?.let { out.add(it) }
    }
    return out
}

private fun acceptPath(text: String, start: Int, end: Int): FilePathMatch? {
    if (end - start < 3) return null
    val candidate = text.substring(start, end)
    // Protocol-relative or malformed — `//host/x` is not a local path.
    if (candidate.contains("//")) return null
    if (candidate.endsWith("/")) return null
    // '~' is only meaningful as the leading home marker.
    if (candidate.indexOf('~') > 0) return null
    if (candidate.startsWith("~") && !candidate.startsWith("~/")) return null

    val backticked = start > 0 && text[start - 1] == '`' &&
        end < text.length && text[end] == '`'
    if (!candidate.contains('/') && !backticked) return null
    if (!FILE_SEGMENT.matches(candidate.substringAfterLast('/'))) return null

    return FilePathMatch(candidate, start, end)
}

/**
 * Rewrites every path found by [detectRemoteFilePaths] into a markdown link
 * pointing at [REMOTE_FILE_SCHEME]. Paths already written as inline code keep
 * their backticks — `[`x.kt`](crfile://x.kt)` renders as a monospaced link.
 */
fun linkifyRemoteFilePaths(markdown: String): String {
    val matches = detectRemoteFilePaths(markdown)
    if (matches.isEmpty()) return markdown
    val sb = StringBuilder(markdown.length + matches.size * 24)
    var cursor = 0
    for (m in matches) {
        val wrapped = m.start > 0 && markdown[m.start - 1] == '`' &&
            m.end < markdown.length && markdown[m.end] == '`'
        val from = if (wrapped) m.start - 1 else m.start
        val to = if (wrapped) m.end + 1 else m.end
        if (from < cursor) continue
        sb.append(markdown, cursor, from)
        sb.append('[').append(markdown, from, to).append(']')
        sb.append('(').append(REMOTE_FILE_SCHEME).append(m.path).append(')')
        cursor = to
    }
    sb.append(markdown, cursor, markdown.length)
    return sb.toString()
}
