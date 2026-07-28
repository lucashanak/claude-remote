package com.clauderemote.ui

import androidx.compose.runtime.Composable
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
