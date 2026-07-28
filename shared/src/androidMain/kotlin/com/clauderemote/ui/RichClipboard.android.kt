package com.clauderemote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberRichCopy(): (plain: String, html: String) -> Unit {
    val context = LocalContext.current
    return { plain, html ->
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // Blank html → plain-only. Otherwise newHtmlText carries both a
            // plain-text and an HTML representation; rich targets (Gmail, Docs,
            // …) take the HTML, plain targets the text.
            val clip = if (html.isBlank()) ClipData.newPlainText("Claude message", plain)
                       else ClipData.newHtmlText("Claude message", plain, html)
            cm.setPrimaryClip(clip)
        } catch (_: Throwable) {
            // Fall back to plain-only so copy never fails.
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Claude message", plain))
            } catch (_: Throwable) {}
        }
    }
}
