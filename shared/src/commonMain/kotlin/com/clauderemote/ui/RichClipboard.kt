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
     * Convert a selected [AnnotatedString] to an inline-formatted HTML fragment
     * for rich selection-copy. Only INLINE styles carried by the selection's
     * span styles survive: bold, italic, underline, strikethrough, monospace
     * (`<code>`). Colors are deliberately dropped (they're theme colors that
     * would look wrong pasted onto a light background); block structure (tables,
     * lists) isn't in the AnnotatedString so it can't be reconstructed from a
     * partial selection. Newlines become `<br>`.
     */
    fun annotatedToHtml(a: AnnotatedString): String {
        val text = a.text
        if (text.isEmpty()) return ""
        val spans = a.spanStyles
        if (spans.isEmpty()) return escapeHtml(text).replace("\n", "<br>")

        // Cut points at every span boundary; each segment has one constant style set.
        val bounds = sortedSetOf(0, text.length)
        for (s in spans) {
            bounds.add(s.start.coerceIn(0, text.length))
            bounds.add(s.end.coerceIn(0, text.length))
        }
        val b = bounds.toList()
        return buildString {
            for (i in 0 until b.size - 1) {
                val start = b[i]
                val end = b[i + 1]
                if (start >= end) continue
                val active = spans.filter { it.start <= start && it.end >= end }.map { it.item }
                val bold = active.any { (it.fontWeight?.weight ?: 0) >= FontWeight.SemiBold.weight }
                val italic = active.any { it.fontStyle == FontStyle.Italic }
                val underline = active.any { it.textDecoration?.contains(TextDecoration.Underline) == true }
                val strike = active.any { it.textDecoration?.contains(TextDecoration.LineThrough) == true }
                val mono = active.any { it.fontFamily.toString().contains("Monospace", ignoreCase = true) }
                val open = StringBuilder(); val close = StringBuilder()
                if (bold) { open.append("<b>"); close.insert(0, "</b>") }
                if (italic) { open.append("<i>"); close.insert(0, "</i>") }
                if (underline) { open.append("<u>"); close.insert(0, "</u>") }
                if (strike) { open.append("<s>"); close.insert(0, "</s>") }
                if (mono) { open.append("<code>"); close.insert(0, "</code>") }
                append(open)
                append(escapeHtml(text.substring(start, end)).replace("\n", "<br>"))
                append(close)
            }
        }
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
