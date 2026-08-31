package com.clauderemote.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Convert a message's markdown source to an HTML fragment for rich clipboard
 * copy, using the SAME parser the transcript renderer uses (JetBrains
 * markdown, GFM flavour → headings, bold/italic, lists, task-lists, fenced
 * code, tables, links, blockquotes). Best-effort: any failure falls back to a
 * `<pre>`-escaped block so copy never throws.
 */
object MarkdownHtml {
    fun toHtml(markdown: String): String = try {
        val flavour = GFMFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        val html = HtmlGenerator(markdown, tree, flavour).generateHtml()
        // HtmlGenerator wraps output in <body>…</body>; strip it so the result
        // is a clean fragment (what clipboard HTML flavours expect).
        html.removePrefix("<body>").removeSuffix("</body>")
    } catch (_: Throwable) {
        "<pre>" + escapeHtml(markdown) + "</pre>"
    }

    private fun escapeHtml(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(ch)
        }
    }

    /**
     * Escape for HTML **without losing the layout**. HTML collapses every run
     * of whitespace and every newline, so escaping alone turns copied code,
     * diffs, JSON and terminal output into one run-together line — the paste
     * looks nothing like what was on screen. Leading spaces and runs of two or
     * more become `&nbsp;`, tabs become four, newlines become `<br>`.
     *
     * Only the SELECTION path needs this. Whole-message copy goes through the
     * markdown renderer, which already puts code inside `<pre><code>` (verified
     * in MarkdownHtmlTest) where whitespace is significant.
     */
    internal fun escapeHtmlPreservingLayout(s: String): String = buildString(s.length) {
        var atLineStart = true
        var i = 0
        while (i < s.length) {
            when (val ch = s[i]) {
                '\n' -> { append("<br>"); atLineStart = true; i++ }
                '\t' -> { append("&nbsp;&nbsp;&nbsp;&nbsp;"); atLineStart = false; i++ }
                ' ' -> {
                    var n = 0
                    while (i + n < s.length && s[i + n] == ' ') n++
                    // A space at the start of a line collapses on its own; inside
                    // a line only runs of 2+ do.
                    if (atLineStart || n > 1) repeat(n) { append("&nbsp;") } else append(' ')
                    i += n
                    atLineStart = false
                }
                '&' -> { append("&amp;"); atLineStart = false; i++ }
                '<' -> { append("&lt;"); atLineStart = false; i++ }
                '>' -> { append("&gt;"); atLineStart = false; i++ }
                else -> { append(ch); atLineStart = false; i++ }
            }
        }
    }

    /**
     * Is this span the app's monospace face?
     *
     * The old test was `fontFamily.toString().contains("Monospace")`, which is
     * true for Android's `FontFamily.Monospace` but FALSE on desktop, where the
     * app bundles its own faces: the family stringifies as
     * `FontListFontFamily(fonts=[ResourceFont(name='font/DejaVuSansMono.ttf'…)])`
     * — "Mono", never "Monospace". So every code span in a desktop selection
     * silently lost its `<code>` wrapper, on the platform the user copies from.
     */
    private fun androidx.compose.ui.text.SpanStyle.isMonospace(): Boolean {
        val family = fontFamily ?: return false
        if (family == com.clauderemote.ui.theme.CRFontMono) return true
        return family.toString().contains("mono", ignoreCase = true)
    }

    /**
     * Convert a selected [AnnotatedString] to an inline-formatted HTML fragment
     * for rich selection-copy. Only INLINE styles carried by the selection's
     * span styles survive: bold, italic, underline, strikethrough, monospace
     * (`<code>`). Colors are deliberately dropped (they're theme colors that
     * would look wrong pasted onto a light background); block structure (tables,
     * lists) isn't in the AnnotatedString so it can't be reconstructed from a
     * partial selection.
     *
     * Returns "" when the selection carries NO formatting this can express, so
     * the caller copies plain text only. That emptiness is a feature: attaching
     * an HTML flavour to an unformatted selection made pasting WORSE than a
     * plain copy, because a rich target then renders HTML and collapses all the
     * alignment out of terminal output, diffs and JSON.
     */
    fun annotatedToHtml(a: AnnotatedString): String {
        val text = a.text
        if (text.isEmpty()) return ""
        val spans = a.spanStyles
        if (spans.isEmpty()) return ""

        // Cut points at every span boundary; each segment has one constant style set.
        val bounds = sortedSetOf(0, text.length)
        for (s in spans) {
            bounds.add(s.start.coerceIn(0, text.length))
            bounds.add(s.end.coerceIn(0, text.length))
        }
        val b = bounds.toList()
        var formatted = false
        val html = buildString {
            for (i in 0 until b.size - 1) {
                val start = b[i]
                val end = b[i + 1]
                if (start >= end) continue
                val active = spans.filter { it.start <= start && it.end >= end }.map { it.item }
                val bold = active.any { (it.fontWeight?.weight ?: 0) >= FontWeight.SemiBold.weight }
                val italic = active.any { it.fontStyle == FontStyle.Italic }
                val underline = active.any { it.textDecoration?.contains(TextDecoration.Underline) == true }
                val strike = active.any { it.textDecoration?.contains(TextDecoration.LineThrough) == true }
                val mono = active.any { it.isMonospace() }
                val open = StringBuilder(); val close = StringBuilder()
                if (bold) { open.append("<b>"); close.insert(0, "</b>") }
                if (italic) { open.append("<i>"); close.insert(0, "</i>") }
                if (underline) { open.append("<u>"); close.insert(0, "</u>") }
                if (strike) { open.append("<s>"); close.insert(0, "</s>") }
                if (mono) { open.append("<code>"); close.insert(0, "</code>") }
                if (open.isNotEmpty()) formatted = true
                append(open)
                append(escapeHtmlPreservingLayout(text.substring(start, end)))
                append(close)
            }
        }
        // Nothing we could express ⇒ no HTML flavour at all (see the doc above).
        return if (formatted) html else ""
    }
}

/**
 * Returns a "copy rich" function: puts BOTH a plain-text and an HTML flavour on
 * the system clipboard, so rich targets (Mail, Docs, Notion, Slack, …) paste
 * formatted while plain targets still get the text. `plain` is the markdown
 * source (readable + re-usable as markdown); `html` is the rendered fragment.
 *
 * Platform-specific because the plain [androidx.compose.ui.platform.ClipboardManager]
 * only writes a single plain string — Android uses ClipData.newHtmlText (needs a
 * Context, hence @Composable to reach LocalContext), desktop uses an AWT
 * Transferable exposing text/plain + text/html.
 */
@Composable
expect fun rememberRichCopy(): (plain: String, html: String) -> Unit
