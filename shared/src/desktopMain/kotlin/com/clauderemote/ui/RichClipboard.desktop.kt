package com.clauderemote.ui

import androidx.compose.runtime.Composable
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/**
 * A clipboard payload that advertises both text/html and plain text, so a paste
 * target picks the richest form it understands (Mail/Pages/Word take the HTML;
 * a plain editor takes the text).
 */
private class HtmlAndPlainTransferable(
    private val plain: String,
    private val html: String,
) : Transferable {
    private val htmlFlavor = DataFlavor("text/html;class=java.lang.String;charset=unicode")

    override fun getTransferDataFlavors(): Array<DataFlavor> =
        arrayOf(htmlFlavor, DataFlavor.stringFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        flavor == htmlFlavor || flavor == DataFlavor.stringFlavor

    override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
        htmlFlavor -> html
        DataFlavor.stringFlavor -> plain
        else -> throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
    }
}

@Composable
actual fun rememberRichCopy(): (plain: String, html: String) -> Unit = { plain, html ->
    try {
        val payload = if (html.isBlank()) java.awt.datatransfer.StringSelection(plain)
                      else HtmlAndPlainTransferable(plain, html)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(payload, null)
    } catch (_: Throwable) {
        try {
            Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(plain), null)
        } catch (_: Throwable) {}
    }
}
