package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.session.SlashCommand

data class PaletteAction(
    val id: String,
    val label: String,
    val category: String,
    val shortcutHint: String = "",
    val onExecute: () -> Unit
)

/**
 * Build a unified list of palette actions from current state.
 */
fun buildPaletteActions(
    tabs: List<ClaudeSession>,
    activeTabId: String?,
    slashCommands: List<SlashCommand>,
    onSendCommand: (String) -> Unit,
    onTabSwitch: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onReconnect: ((String) -> Unit)?,
    onSwitchModel: (ClaudeModel) -> Unit,
    onSendEscape: () -> Unit,
    onNavigate: (String) -> Unit // "settings", "dashboard", "logs", "launcher"
): List<PaletteAction> {
    val actions = mutableListOf<PaletteAction>()

    // Claude slash commands
    slashCommands.forEach { cmd ->
        val label = if (cmd.description.isNotBlank()) "${cmd.command} — ${cmd.description}" else cmd.command
        actions.add(PaletteAction(
            id = "cmd_${cmd.command}",
            label = label,
            category = cmd.category,
            onExecute = { onSendCommand(cmd.command + "\r") }
        ))
    }

    // Mode switching
    actions.add(PaletteAction("mode_toggle", "Toggle mode (Shift+Tab)", "Mode", "Shift+Tab") {
        onSendCommand("\u001B[Z")
    })

    // Model switching
    ClaudeModel.entries.filter { it != ClaudeModel.DEFAULT }.forEach { model ->
        actions.add(PaletteAction("model_${model.name}", "Switch to ${model.displayName}", "Model") {
            onSwitchModel(model)
        })
    }

    // Session actions
    tabs.forEach { tab ->
        if (tab.id != activeTabId) {
            actions.add(PaletteAction("switch_${tab.id}", "Switch to: ${tab.tabTitle}", "Sessions") {
                onTabSwitch(tab.id)
            })
        }
    }
    activeTabId?.let { id ->
        actions.add(PaletteAction("close_active", "Close current session", "Sessions") {
            onTabClose(id)
        })
        if (onReconnect != null) {
            actions.add(PaletteAction("reconnect", "Reconnect current session", "Sessions") {
                onReconnect(id)
            })
        }
    }
    actions.add(PaletteAction("new_session", "New session", "Sessions", "Ctrl+N") {
        onNewTab()
    })

    // Terminal controls
    actions.add(PaletteAction("escape", "Send Escape", "Terminal", "Esc") { onSendEscape() })
    actions.add(PaletteAction("ctrl_c", "Send Ctrl+C", "Terminal") { onSendCommand("\u0003") })
    actions.add(PaletteAction("clear", "Clear terminal", "Terminal") { onSendCommand("\u001Bc") })
    actions.add(PaletteAction("approve_yes", "Approve (y)", "Terminal") { onSendCommand("y\r") })
    actions.add(PaletteAction("approve_no", "Reject (n)", "Terminal") { onSendCommand("n\r") })

    // Navigation
    actions.add(PaletteAction("nav_settings", "Open Settings", "Navigation") { onNavigate("settings") })
    actions.add(PaletteAction("nav_dashboard", "Open Usage Dashboard", "Navigation") { onNavigate("dashboard") })
    actions.add(PaletteAction("nav_logs", "Open Logs", "Navigation") { onNavigate("logs") })
    actions.add(PaletteAction("nav_launcher", "Back to Launcher", "Navigation") { onNavigate("launcher") })

    return actions
}

/**
 * Fuzzy-searchable command palette dialog.
 */
@Composable
fun CommandPaletteDialog(
    actions: List<PaletteAction>,
    onDismiss: () -> Unit
) {
    var filter by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }

    val filtered = if (filter.isBlank()) actions
    else {
        val lower = filter.lowercase()
        actions.filter {
            it.label.lowercase().contains(lower) ||
            it.category.lowercase().contains(lower)
        }
    }

    LaunchedEffect(filter) { selectedIndex = 0 }

    // Inline overlay rather than Dialog/AlertDialog: both of those spawn a
    // separate OS-level window under Compose Desktop, and on macOS inside this
    // app (JediTerm SwingPanel + skiko) the dialog window's content never
    // rendered — the app darkened (scrim) but the palette was invisible.
    // A Box rendered directly in the parent window avoids the issue entirely.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            modifier = Modifier
                .padding(top = 72.dp, start = 24.dp, end = 24.dp)
                .widthIn(min = 400.dp, max = 720.dp)
                .heightIn(max = 500.dp)
                .pointerInput(Unit) { detectTapGestures { /* swallow */ } },
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val filterFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { filterFocus.requestFocus() }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            placeholder = { Text("Search actions...") },
                            modifier = Modifier.weight(1f)
                                .focusRequester(filterFocus)
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown) {
                                        when (event.key) {
                                            Key.DirectionDown -> {
                                                selectedIndex = (selectedIndex + 1).coerceAtMost(filtered.size - 1)
                                                true
                                            }
                                            Key.DirectionUp -> {
                                                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                                true
                                            }
                                            Key.Enter -> {
                                                if (filtered.isNotEmpty() && selectedIndex in filtered.indices) {
                                                    filtered[selectedIndex].onExecute()
                                                    onDismiss()
                                                }
                                                true
                                            }
                                            Key.Escape -> { onDismiss(); true }
                                            else -> false
                                        }
                                    } else false
                                },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                    }

                    val listState = rememberLazyListState()
                    LaunchedEffect(selectedIndex) {
                        if (selectedIndex in filtered.indices) {
                            listState.animateScrollToItem(selectedIndex)
                        }
                    }

                    // Precompute which indices start a new category
                    val categoryStarts = remember(filtered) {
                        val starts = mutableSetOf<Int>()
                        var prev = ""
                        filtered.forEachIndexed { i, action ->
                            if (action.category != prev) { starts.add(i); prev = action.category }
                        }
                        starts
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth(), state = listState) {
                        itemsIndexed(filtered) { index, action ->
                            if (index in categoryStarts) {
                                Text(
                                    action.category,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                            }
                            val isSelected = index == selectedIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        action.onExecute()
                                        onDismiss()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    action.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                           else MaterialTheme.colorScheme.onSurface
                                )
                                if (action.shortcutHint.isNotBlank()) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            action.shortcutHint,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
        }
    }
}
