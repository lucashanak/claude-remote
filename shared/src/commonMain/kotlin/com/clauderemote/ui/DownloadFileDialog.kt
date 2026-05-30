package com.clauderemote.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

/** Image file extensions we will attempt to preview inline. */
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

fun isImagePath(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

fun fileNameFromPath(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifBlank { "download" }

/**
 * Prompt the user for a remote path to download (relative to session cwd or
 * absolute). [busy] disables the controls while the download runs.
 */
@Composable
fun DownloadPathDialog(
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val c = CRTheme.colors
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = c.surface,
        title = { Text("Download file", color = c.text) },
        text = {
            Column {
                Text(
                    "Path relative to the session folder, or an absolute path.",
                    style = CRType.bodyDim,
                    color = c.textDim,
                )
                Spacer(Modifier.width(0.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    enabled = !busy,
                    placeholder = { Text("e.g. output.png", color = c.textDim) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        style = CRType.bodyDim,
                        color = c.disconnected,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.width(24.dp), color = c.text)
            } else {
                TextButton(
                    enabled = path.isNotBlank(),
                    onClick = { onConfirm(path.trim()) },
                ) { Text("Download", color = c.text) }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel", color = c.textDim) }
        },
    )
}

/**
 * Inline image preview with a Save action. If [bitmap] is null the file could
 * not be decoded, so only the Save / Close actions are shown.
 */
@Composable
fun ImagePreviewDialog(
    fileName: String,
    bitmap: ImageBitmap?,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val c = CRTheme.colors
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = c.surface,
        title = { Text(fileName, color = c.text) },
        text = {
            Column {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    )
                } else {
                    Text("Can't preview this file.", style = CRType.bodyDim, color = c.textDim)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSave) { Text("Save", color = c.text) }
                TextButton(onClick = onClose) { Text("Close", color = c.textDim) }
            }
        },
    )
}
