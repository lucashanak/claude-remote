package com.clauderemote.voice

/**
 * Splits text to be read aloud into segments that each fit one Soniox TTS
 * stream, and that double as the player's "paragraph" seek steps.
 *
 * Soniox caps a stream twice, both confirmed in the phone log (2026-11-05):
 *  - input text: `400 input text too long (limit: 5000 bytes)` — UTF-8 bytes,
 *    so Czech diacritics count double;
 *  - output audio: `413 maximum audio duration per request` — 2 minutes of
 *    generated speech per stream (docs: tts/rt/limits-and-quotas).
 * Czech speech runs ~14–16 chars/s, so 2 min ≈ 1,700 chars. [maxChars] 900
 * keeps every segment near one minute with a wide margin.
 *
 * Splitting prefers paragraph breaks, then sentence ends, then clause marks,
 * then spaces, and hard-cuts only a run with no space at all. Short adjacent
 * paragraphs (list items, one-liners) are merged up to [mergeTarget] so a
 * "next paragraph" skip moves a meaningful step instead of one bullet.
 *
 * The watch keeps a copy of this function (wearApp does not depend on
 * `shared`): `wearApp/.../wear/TtsSegmenter.kt` — change both together.
 */
fun ttsSegments(
    text: String,
    maxChars: Int = 900,
    maxBytes: Int = 4000,
    mergeTarget: Int = 350,
): List<String> {
    require(maxChars > 0 && maxBytes >= 4) { "limits must be positive" }
    val paragraphs = text
        .split(Regex("\\n\\s*\\n"))
        // Single line breaks stay: list items and lines read as separate
        // phrases (Soniox pauses on them), and they are preferred cut points.
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val pieces = paragraphs.flatMap { splitLong(it, maxChars, maxBytes) }

    val out = ArrayList<String>()
    for (p in pieces) {
        val last = out.lastOrNull()
        if (last != null) {
            val merged = "$last\n\n$p"
            if (merged.length <= mergeTarget && utf8Len(merged) <= maxBytes) {
                out[out.size - 1] = merged
                continue
            }
        }
        out += p
    }
    return out
}

private fun fits(s: String, maxChars: Int, maxBytes: Int) =
    s.length <= maxChars && utf8Len(s) <= maxBytes

/** Cut one paragraph into pieces that each fit, at the best boundary available. */
private fun splitLong(p: String, maxChars: Int, maxBytes: Int): List<String> {
    if (fits(p, maxChars, maxBytes)) return listOf(p)
    val out = ArrayList<String>()
    var rest = p
    while (!fits(rest, maxChars, maxBytes)) {
        val limit = maxPrefix(rest, maxChars, maxBytes)
        val cut = bestCut(rest, limit)
        val head = rest.substring(0, cut).trim()
        if (head.isNotEmpty()) out += head
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) out += rest
    return out
}

/** Longest prefix length within both limits (never splits a surrogate pair). */
private fun maxPrefix(s: String, maxChars: Int, maxBytes: Int): Int {
    var bytes = 0
    var i = 0
    val end = minOf(s.length, maxChars)
    while (i < end) {
        val cp = s[i]
        val step = if (cp.isHighSurrogate() && i + 1 < s.length) 2 else 1
        if (i + step > end) break
        val b = utf8Len(s.substring(i, i + step))
        if (bytes + b > maxBytes) break
        bytes += b
        i += step
    }
    return i.coerceAtLeast(1)
}

private val SENTENCE_END = Regex("[.!?…]+[\"'”»)]*\\s|\\n")
private val CLAUSE_END = Regex("[,;:–—]\\s")

/**
 * Cut position within `s[0, limit)`: the last sentence end, else the last
 * clause mark, else the last space, else `limit` itself. A boundary in the
 * first third is ignored so a long sentence is not cut into slivers.
 */
private fun bestCut(s: String, limit: Int): Int {
    if (limit >= s.length) return s.length
    val window = s.substring(0, limit)
    val floor = limit / 3
    fun lastEnd(re: Regex) = re.findAll(window).map { it.range.last + 1 }.lastOrNull { it > floor }
    lastEnd(SENTENCE_END)?.let { return it }
    lastEnd(CLAUSE_END)?.let { return it }
    val space = window.lastIndexOf(' ')
    if (space > floor) return space + 1
    // No space at all (a URL, a hash): hard cut, but not inside a surrogate pair.
    return if (limit > 1 && s[limit - 1].isHighSurrogate()) limit - 1 else limit
}

internal fun utf8Len(s: String): Int {
    var n = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        n += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { i++; 4 }
            else -> 3
        }
        i++
    }
    return n
}
