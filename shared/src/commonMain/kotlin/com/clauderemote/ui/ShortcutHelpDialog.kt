package com.clauderemote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clauderemote.ui.theme.CRThemeSnapshot
import com.clauderemote.ui.theme.CRType

/**
 * Ctrl+Shift+H. Rendered straight from [AppShortcut.entries], so it lists
 * exactly what the dispatcher handles — there is no second list to forget.
 *
 * The closing note matters as much as the table: the shortcuts moved from
 * plain Ctrl to Ctrl+Shift, and "where did Ctrl+K go" is answered by saying
 * that plain Ctrl now belongs to the terminal.
 */
@Composable
internal fun ShortcutHelpDialog(
    visible: Boolean,
    theme: CRThemeSnapshot,
    onDismiss: () -> Unit,
) {
    val c = theme.colors
    com.clauderemote.ui.components.FloatingDialog(
        visible = visible,
        onDismiss = onDismiss,
        theme = theme,
        title = { Text("Klávesové zkratky", style = CRType.cardTitle, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppShortcut.entries.forEach { s ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            s.keys,
                            style = CRType.mono,
                            color = c.accent,
                            modifier = Modifier.width(210.dp),
                        )
                        Text(s.label, style = CRType.bodyDim, color = c.text)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Holé Ctrl patří terminálu — Ctrl+W maže slovo, Ctrl+K do konce řádku, " +
                        "Ctrl+C přeruší. Na Macu dál fungují Cmd+K, Cmd+N a Cmd+W.",
                    style = CRType.bodyDim,
                    color = c.textDim,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Zavřít", color = c.accent) }
        },
    )
}
