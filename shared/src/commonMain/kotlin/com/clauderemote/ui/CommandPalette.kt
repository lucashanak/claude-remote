package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.session.SlashCommand
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

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
        onSendCommand("[Z")
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
    actions.add(PaletteAction("ctrl_c", "Send Ctrl+C", "Terminal") { onSendCommand("") })
    actions.add(PaletteAction("clear", "Clear terminal", "Terminal") { onSendCommand("c") })
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
 * Fuzzy-searchable command palette overlay, styled with CRTheme.
 *
 * Rendered as an in-window Box overlay (not a system Dialog) to avoid
 * Compose Desktop / skiko window-layering issues where Dialog content
 * never renders on macOS.
 */
@Composable
fun CommandPaletteDialog(
    actions: List<PaletteAction>,
    onDismiss: () -> Unit
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.TopCenter
    ) {
        val panelShape = RoundedCornerShape(m.cardRadius * 2)
        Box(
            modifier = Modifier
                .padding(top = 64.dp, start = 20.dp, end = 20.dp)
                .widthIn(min = 360.dp, max = 680.dp)
                .heightIn(max = 520.dp)
                .background(c.surface, panelShape)
                .border(1.dp, c.border, panelShape)
                .pointerInput(Unit) { detectTapGestures { /* swallow taps inside panel */ } }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ── Search row ────────────────────────────────────────────
                val filterFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { filterFocus.requestFocus() }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = { Text("Search actions…", style = CRType.cardTitle, color = c.textDim) },
                        modifier = Modifier
                            .weight(1f)
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
                        textStyle = CRType.cardTitle,
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = c.border,
                            focusedBorderColor = c.accent,
                            cursorColor = c.accent,
                            unfocusedTextColor = c.text,
                            focusedTextColor = c.text,
                            unfocusedContainerColor = c.surface,
                            focusedContainerColor = c.surface,
                        )
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = c.textDim)
                    }
                }

                HorizontalDivider(color = c.border)

                // ── Results ───────────────────────────────────────────────
                val listState = rememberLazyListState()
                LaunchedEffect(selectedIndex) {
                    if (selectedIndex in filtered.indices) {
                        listState.animateScrollToItem(selectedIndex)
                    }
                }

                val categoryStarts = remember(filtered) {
                    val starts = mutableSetOf<Int>()
                    var prev = ""
                    filtered.forEachIndexed { i, action ->
                        if (action.category != prev) { starts.add(i); prev = action.category }
                    }
                    starts
                }

                if (filtered.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No results for \"$filter\"", style = CRType.bodyDim, color = c.textDim)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        state = listState,
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                        itemsIndexed(filtered) { index, action ->
                            if (index in categoryStarts) {
                                Text(
                                    action.category.uppercase(),
                                    style = CRType.sectionH,
                                    color = c.textDim,
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .padding(top = 12.dp, bottom = 4.dp)
                                )
                            }
                            val isSelected = index == selectedIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) c.tintAccent else Color.Transparent
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        action.onExecute()
                                        onDismiss()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    action.label,
                                    style = CRType.cardTitle,
                                    color = if (isSelected) c.accent else c.text,
                                    modifier = Modifier.weight(1f),
                                )
                                if (action.shortcutHint.isNotBlank()) {
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(c.surface2)
                                            .border(1.dp, c.border, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            action.shortcutHint,
                                            style = CRType.keyboardKey,
                                            color = c.textDim,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
