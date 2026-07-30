package com.clauderemote.ui

/**
 * Turns file paths that Claude mentions in its answers into tappable links, so
 * "I wrote the report to `out/report.pdf`" can be downloaded with one tap
 * instead of retyping the path into the manual download dialog.
 *
 * Detection is deliberately liberal: any inline-code span and any slash-bearing
 * run of path characters becomes a *candidate*. Candidates are then checked
 * against the server (see `RemoteOpsService.statFiles`) and only the ones that
 * are really files get linkified. Guessing from text alone can't support names
 * with spaces, diacritics or no extension without turning prose into links —
 * asking the server removes the guesswork entirely.
 *
 * Verified paths are rewritten into ordinary markdown links whose destination
 * carries the private [REMOTE_FILE_SCHEME]; `RichBody` installs a `UriHandler`
 * that intercepts that scheme and leaves every other link to the platform.
 */

/** Private URI scheme marking a link that points at a file on the session's server. */
const val REMOTE_FILE_SCHEME = "crfile://"

/** Longest candidate we will bother asking the server about. */
private const val MAX_CANDIDATE_LEN = 512

/**
 * A candidate file path found in assistant text. [start]/[end] is the half-open
 * source range that becomes the link *label* — for an inline-code candidate that
 * includes the surrounding backticks, so the monospace styling survives the
 * rewrite. [path] is the path itself, which is what gets verified and fetched.
 */
data class FilePathMatch(val path: String, val start: Int, val end: Int, val backticked: Boolean)

/**
 * Resolves a path the way the download dialog does: relative to the session's
 * working folder, absolute and `~`-rooted paths untouched. Shared so verification
 * and the actual fetch can never disagree about what a path means.
 */
fun resolveSessionPath(path: String, sessionFolder: String): String =
    if (path.startsWith("/") || path.startsWith("~")) path
    else "${sessionFolder.trimEnd('/')}/$path"

/**
 * Remembers which paths the server confirmed, so scrolling the transcript doesn't
 * re-ask about every message. Hits are kept for the whole session — a file that
 * existed rarely disappears, and a stale hit just fails the download with a clear
 * error. Misses expire, so a file Claude creates *after* mentioning it still turns
 * into a link on the next render.
 */
class RemotePathCache(private val missTtlMs: Long = 60_000) {
    private class Entry(val isFile: Boolean, val mark: kotlin.time.TimeMark)

    private val entries = mutableMapOf<String, Entry>()

    /** True/false if known and still fresh, null if we must ask the server. */
    operator fun get(key: String): Boolean? {
        val e = entries[key] ?: return null
        if (!e.isFile && e.mark.elapsedNow().inWholeMilliseconds >= missTtlMs) {
            entries.remove(key)
            return null
        }
        return e.isFile
    }

    operator fun set(key: String, isFile: Boolean) {
        entries[key] = Entry(isFile, kotlin.time.TimeSource.Monotonic.markNow())
    }
}

/** Existing markdown links/images — already clickable, never re-linkify them. */
private val MARKDOWN_LINK = Regex("""!?\[[^\]\n]*\]\([^)\s]*\)""")

/** `<https://…>` autolinks. */
private val AUTOLINK = Regex("""<[^>\s]+>""")

/** Inline code spans. Their content is taken verbatim — spaces and all. */
private val INLINE_CODE = Regex("""`([^`\n]+)`""")

/** Sentence punctuation that can trail a path in prose but is never part of it. */
private const val TRAILING_PUNCT = ".,;:!?"

/**
 * Characters allowed in a bare (un-backticked) path. Unicode-aware so `zpráva.pdf`
 * and `résumé.docx` work. ':' is excluded on purpose — it is what stops a run at
 * the `https:` of a URL and at the `:42` of a `file:line` reference.
 */
private fun isPathChar(ch: Char): Boolean =
    ch.isLetterOrDigit() || ch in "._-+~/@#%,'&=()"

/** Shell metacharacters that mark a backticked span as a command, not a path. */
private const val SHELL_META = "|;<>*?{}\"\\\n\t"

/**
 * Cheap sanity gate before we spend a round-trip asking the server. Only rejects
 * things that cannot be a path we could fetch; everything else is the server's
 * call, which is the whole point of verifying.
 */
private fun plausibleCandidate(s: String): Boolean {
    if (s.isEmpty() || s.length > MAX_CANDIDATE_LEN) return false
    if (s.contains("://")) return false
    if (s.any { it in SHELL_META }) return false
    if (s.contains("$(") || s.contains("&&")) return false
    if (s.startsWith("-")) return false        // a command-line flag
    if (s.endsWith("()")) return false         // a function call, e.g. `foo.bar()`
    if (s.endsWith("/")) return false          // a directory
    if (s == "." || s == "..") return false
    // Multi-word with no extension and no separator is prose or a command
    // (`npm run build`), never a filename worth a round-trip.
    if (s.contains(' ') && !s.contains('.') && !s.contains('/')) return false
    return true
}

/**
 * Finds everything that could plausibly be a file path. The result is a
 * candidate list, NOT a list of known files — feed the paths through the
 * server check and pass the surviving set to [linkifyRemoteFilePaths].
 */
fun detectFilePathCandidates(
    text: String,
    includeCodeBlocks: Boolean = true,
): List<FilePathMatch> {
    if (text.isEmpty()) return emptyList()
    val mask = buildMask(text, includeCodeBlocks)
    val out = mutableListOf<FilePathMatch>()

    // Inline code first — its content is taken whole, so `My Report.pdf` and
    // `Dockerfile` survive, and its span is masked off from the bare scan below.
    for (m in INLINE_CODE.findAll(text)) {
        if (m.range.any { mask[it] }) continue
        val inner = m.groups[1]!!.range
        for (k in m.range) mask[k] = true
        val trimmed = text.substring(inner.first, inner.last + 1).trim()
        if (!plausibleCandidate(trimmed)) continue
        // The label spans the backticks themselves so the link renders monospaced.
        out.add(
            FilePathMatch(
                path = trimmed,
                start = m.range.first,
                end = m.range.last + 1,
                backticked = true,
            )
        )
    }

    // Bare runs. A '/' is required here: without the backtick delimiters there is
    // no reliable way to tell where a spaceless bare word ends and prose begins.
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
        if (end - runStart < 2) continue
        val candidate = text.substring(runStart, end)
        if (!candidate.contains('/')) continue
        if (candidate.contains("//")) continue      // protocol-relative, not a path
        if (candidate.indexOf('~') > 0) continue    // '~' only means home at the front
        if (candidate.startsWith("~") && !candidate.startsWith("~/")) continue
        // Without backticks, require an extension. Otherwise "and/or", "N/A" and
        // "24/7" would each cost a server round-trip on every rendered message.
        if (!candidate.substringAfterLast('/').contains('.')) continue
        if (!plausibleCandidate(candidate)) continue
        out.add(FilePathMatch(candidate, runStart, end, backticked = false))
    }

    return out.sortedBy { it.start }
}

/**
 * Marks regions that must be left alone: fenced and indented code blocks (markdown
 * isn't parsed there, so an injected link would render as literal `[x](crfile://x)`
 * garbage) plus existing links and autolinks.
 */
private fun buildMask(text: String, includeCodeBlocks: Boolean): BooleanArray {
    val mask = BooleanArray(text.length)

    // Code blocks are masked for the markdown REWRITE (an injected link would
    // render as literal `[x](crfile://x)` there) but not when merely collecting
    // candidates — a standalone path in a fence is exactly how Claude hands over
    // a file, and those get their own clickable renderer instead.
    if (!includeCodeBlocks) {
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
    }

    for (re in listOf(MARKDOWN_LINK, AUTOLINK)) {
        for (m in re.findAll(text)) {
            for (k in m.range) mask[k] = true
        }
    }
    return mask
}

/**
 * A markdown link destination can't hold spaces or parentheses bare — those need
 * the angle-bracket form. Plain destinations are kept everywhere else so the
 * common case stays byte-identical to what the renderer already handles.
 */
private fun destination(path: String): String {
    val uri = REMOTE_FILE_SCHEME + path
    val needsAngles = path.any { it == ' ' || it == '(' || it == ')' }
    return if (needsAngles) "<$uri>" else uri
}

/**
 * Rewrites the candidates present in [verifiedPaths] into markdown links pointing
 * at [REMOTE_FILE_SCHEME]. Candidates the server didn't confirm are left as plain
 * text. Paths already written as inline code keep their backticks —
 * ``[`x.kt`](crfile://x.kt)`` renders as a monospaced link.
 */
fun linkifyRemoteFilePaths(markdown: String, verifiedPaths: Set<String>): String {
    if (verifiedPaths.isEmpty()) return markdown
    val matches = detectFilePathCandidates(markdown, includeCodeBlocks = false)
        .filter { it.path in verifiedPaths }
    if (matches.isEmpty()) return markdown
    val sb = StringBuilder(markdown.length + matches.size * 24)
    var cursor = 0
    for (m in matches) {
        if (m.start < cursor) continue
        sb.append(markdown, cursor, m.start)
        sb.append('[').append(markdown, m.start, m.end).append(']')
        sb.append('(').append(destination(m.path)).append(')')
        cursor = m.end
    }
    sb.append(markdown, cursor, markdown.length)
    return sb.toString()
}
