package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clauderemote.model.SessionActivity
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.StatusIndicator
import com.clauderemote.ui.components.CRStatus
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

/** Map a session activity to the status-dot style used in the menu header. */
fun SessionActivity.toMenuCRStatus(): CRStatus = when (this) {
    SessionActivity.WORKING -> CRStatus.Working
    SessionActivity.WAITING_FOR_INPUT -> CRStatus.Ready
    SessionActivity.APPROVAL_NEEDED -> CRStatus.Approval
    SessionActivity.IDLE -> CRStatus.Idle
    SessionActivity.DISCONNECTED -> CRStatus.Disconnected
}

/**
 * App-styled long-press action sheet for a session — matches the CR design
 * (surface card, border, accent rows) instead of the stock Material dialog.
 */
@Composable
fun SessionContextSheet(
    title: String,
    subtitle: String,
    status: CRStatus,
    canRename: Boolean,
    onRename: () -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CRTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CRCard(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .widthIn(max = 360.dp),
            padding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                // Header
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusIndicator(status = status, modifier = Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = CRType.cardTitle, color = c.text)
                        if (subtitle.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(subtitle, style = CRType.monoTiny, color = c.textDim)
                        }
                    }
                }
                HorizontalDivider(color = c.border, thickness = 1.dp)

                if (canRename) {
                    MenuRow(Icons.Default.Edit, "Rename", c.text, onRename)
                }
                MenuRow(Icons.Default.Refresh, "Reconnect", c.text, onReconnect)
                MenuRow(Icons.Default.Close, "Close session", c.disconnected, onClose)

                HorizontalDivider(color = c.border, thickness = 1.dp)
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text("Cancel", style = CRType.bodyDim, color = c.textDim)
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(label, style = CRType.cardTitle, color = tint)
    }
}

/** App-styled rename dialog (alias input). */
@Composable
fun RenameSessionDialog(
    initialAlias: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CRTheme.colors
    var text by remember { mutableStateOf(initialAlias) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CRCard(
            modifier = Modifier.fillMaxWidth(0.86f).widthIn(max = 360.dp),
            padding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text("Rename session", style = CRType.cardTitle, color = c.text)
                Spacer(Modifier.height(14.dp))
                val shape = RoundedCornerShape(8.dp)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = CRType.cardTitle.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(c.surface2)
                        .border(1.dp, c.border, shape)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    decorationBox = { inner ->
                        if (text.isEmpty()) Text("Alias…", style = CRType.bodyDim, color = c.textDim)
                        inner()
                    },
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    Text(
                        "Cancel",
                        style = CRType.cardTitle,
                        color = c.textDim,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    Text(
                        "Rename",
                        style = CRType.cardTitle,
                        color = c.accent,
                        modifier = Modifier.clickable { onConfirm(text.trim()) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
