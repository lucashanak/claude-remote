package com.clauderemote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.SessionActivity
import com.clauderemote.model.SessionStatus
import com.clauderemote.session.CommandFetcher
import com.clauderemote.session.SlashCommand
import com.clauderemote.session.status.RemoteSessionStatus
import com.clauderemote.session.transcript.TranscriptEntry
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.CRStatus
import com.clauderemote.ui.components.Pill
import com.clauderemote.ui.components.ServerGlyph
import com.clauderemote.ui.components.StatusIndicator
import com.clauderemote.ui.components.color
import com.clauderemote.ui.theme.CRTerminalView
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import com.clauderemote.ui.theme.LocalCRTerminalView
import kotlinx.coroutines.launch




@Composable
internal fun SessionSidePanel(
    allSessions: Map<String, List<SessionItem>>,
    activeTabId: String?,
    sessionActivities: Map<String, com.clauderemote.model.SessionActivity> = emptyMap(),
    onTabSwitch: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onMenuOpen: () -> Unit,
    onAttachRemote: ((com.clauderemote.model.RemoteSession) -> Unit)?,
    onRenameSession: ((sessionId: String, newAlias: String) -> Unit)? = null,
    onSessionLongPress: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val dense = m.sessionCardOneLine
    var renamingItem by remember { mutableStateOf<SessionItem?>(null) }
    var renameText by remember { mutableStateOf("") }

    // Rename dialog. FloatingDialog (not a plain AlertDialog) so it renders
    // above the SwingPanel-embedded terminal on desktop when the drawer is
    // opened over Raw view — same reasoning as the terminal's "..." menu.
    com.clauderemote.ui.components.FloatingDialog(
        visible = renamingItem != null,
        onDismiss = { renamingItem = null },
        theme = com.clauderemote.ui.theme.CRThemeSnapshot.current(),
        title = { Text("Rename session", color = c.text) },
        text = {
            OutlinedTextField(
                value = renameText,
                onValueChange = { renameText = it },
                label = { Text("Alias") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = {
                renamingItem?.tab?.let { onRenameSession?.invoke(it.id, renameText.trim()) }
                renamingItem = null
            }) { Text("OK", color = c.accent) }
        },
        dismissButton = {
            TextButton(onClick = { renamingItem = null }) { Text("Cancel", color = c.textDim) }
        }
    )

    // Re-group flat items by server id/name
    val allFlat: List<SessionItem> = remember(allSessions) { allSessions.values.flatten() }
    val byServer: Map<String, List<SessionItem>> = remember(allFlat) {
        allFlat.groupBy { item ->
            item.tab?.server?.id ?: item.remote?.server?.id ?: "unknown"
        }
    }

    val panelBrush = if (CRTheme.variant == com.clauderemote.ui.theme.CRVariant.Glass) {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(
                c.surface.copy(alpha = 0.92f),
                c.bg.copy(alpha = 0.92f),
            ),
        )
    } else {
        androidx.compose.ui.graphics.SolidColor(c.bg)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(panelBrush)
    ) {
        // ── Panel header ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onMenuOpen, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Menu, "Menu", tint = c.textDim, modifier = Modifier.size(18.dp))
            }
            Text(
                "Sessions",
                style = if (isMobile) CRType.cardTitle else CRType.cardTitle.copy(fontSize = 16.sp),
                color = c.text,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNewTab, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, "New", tint = c.accent, modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = c.border, thickness = 1.dp)

        // ── Session list grouped by server ──────────────────────────────────
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            val sortedServers = byServer.entries.sortedBy { (_, items) ->
                (items.first().tab?.server?.name ?: items.first().remote?.server?.name ?: "").lowercase()
            }
            sortedServers.forEach { (_, items) ->
                val server = items.first().tab?.server ?: items.first().remote?.server
                // Sort strictly by (folder leaf, alias) — NOT by
                // item.label, because label collapses to the alias when
                // one exists, which clusters every session named e.g.
                // "second" together regardless of which folder they
                // belong to. The user wants folder first, alias as
                // tiebreaker, connectedness ignored.
                val sortedItems = items.sortedWith(
                    compareBy(
                        { item ->
                            val tab = item.tab
                            val folder = if (tab != null) {
                                tab.folder
                            } else {
                                val r = item.remote
                                if (r != null) com.clauderemote.model.TmuxNameParser
                                    .parse(r.tmuxSession.name, r.server.name).folder
                                else item.folder
                            }
                            folder.trimEnd('/').substringAfterLast('/').lowercase()
                        },
                        { item ->
                            val tab = item.tab
                            if (tab != null) {
                                tab.alias.lowercase()
                            } else {
                                val r = item.remote
                                if (r != null) com.clauderemote.model.TmuxNameParser
                                    .parse(r.tmuxSession.name, r.server.name).alias.lowercase()
                                else ""
                            }
                        },
                    )
                )
                if (server != null) {
                    item(key = "server_${server.id}") {
                        SidePanelGroupLabel(
                            serverName = server.name,
                            count = items.size,
                        )
                    }
                }
                items(sortedItems, key = { it.id }) { item ->
                    SidePanelSessionRow(
                        item = item,
                        isActive = item.tab?.id == activeTabId,
                        activity = sessionActivities[item.id],
                        dense = dense,
                        onTabSwitch = onTabSwitch,
                        onTabClose = onTabClose,
                        onAttachRemote = onAttachRemote,
                        onRename = if (onRenameSession != null) { label ->
                            renameText = label
                            renamingItem = item
                        } else null,
                        // Passed through unconditionally (still requires a tab):
                        // SidePanelSessionRow itself decides mobile long-press
                        // vs desktop right-click.
                        onLongPress = if (item.tab != null) {
                            onSessionLongPress?.let { lp -> { lp(item.tab.id) } }
                        } else null,
                    )
                }
            }

            // Footer: new session button
            item(key = "footer") {
                HorizontalDivider(color = c.border, thickness = 1.dp)
                val shape = RoundedCornerShape(8.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .clip(shape)
                        .border(1.dp, c.border, shape)
                        .clickable(onClick = onNewTab)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.Add, null, tint = c.accent, modifier = Modifier.size(14.dp))
                    Text("New session", style = CRType.bodyDim, color = c.accent)
                }
            }
        }
    }
}

// ── Group label (mirrors DrawerGroupLabel) ────────────────────────────────────

@Composable
private fun SidePanelGroupLabel(serverName: String, count: Int) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ServerGlyph(name = serverName, modifier = Modifier.size(14.dp))
        Text(
            serverName,
            style = if (isMobile) CRType.sectionH else CRType.sectionH.copy(fontSize = 13.sp),
            color = c.textDim,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Pill(
            text = "$count",
            background = c.surface2,
            foreground = c.textDim,
        )
    }
}

// ── Per-session row ───────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SidePanelSessionRow(
    item: SessionItem,
    isActive: Boolean,
    activity: com.clauderemote.model.SessionActivity?,
    dense: Boolean,
    onTabSwitch: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onAttachRemote: ((com.clauderemote.model.RemoteSession) -> Unit)?,
    onRename: ((String) -> Unit)?,
    onLongPress: (() -> Unit)? = null,
) {
    val c = CRTheme.colors
    val crStatus = activity.sidePanelToCRStatus(item.isConnected)
    val mode = item.tab?.mode

    // Folder basename · alias label
    val folderBase = item.folder.trimEnd('/').substringAfterLast('/').ifBlank { item.folder }
    val rowLabel = buildString {
        append(folderBase)
        val alias = item.tab?.alias?.ifBlank { null }
        if (alias != null) append(" · $alias")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) c.tintAccent else Color.Transparent)
            .combinedClickable(
                onClick = {
                    if (item.isConnected && item.tab != null) onTabSwitch(item.tab.id)
                    else if (item.remote != null) onAttachRemote?.invoke(item.remote)
                },
                // Long-press-to-open-menu is a mobile gesture only; desktop
                // opens the same menu via right-click (secondaryClick below).
                onLongClick = if (isMobile) onLongPress else null,
            )
            .secondaryClick(enabled = onLongPress != null) { onLongPress?.invoke() }
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 3 dp accent bar for active row
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(if (isActive) c.accent else Color.Transparent),
        )

        if (dense) {
            // ── Dense: single line ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusIndicator(
                    status = crStatus,
                    modifier = Modifier.size(8.dp),
                    viz = com.clauderemote.ui.theme.CRStatusViz.Dot,
                )
                Text(
                    rowLabel,
                    style = if (isMobile) CRType.bodyDim else CRType.bodyDim.copy(fontSize = 14.sp),
                    color = if (isActive) c.text else c.textDim,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isActive && item.isConnected) {
                    IconButton(
                        onClick = { item.tab?.let { onTabClose(it.id) } },
                        modifier = Modifier.size(20.dp),
                    ) { Icon(Icons.Default.Close, "Close", tint = c.textDim, modifier = Modifier.size(12.dp)) }
                }
            }
        } else {
            // ── Regular / Compact: two-line with ServerGlyph ────────────────
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ServerGlyph block
                val serverName = item.tab?.server?.name ?: item.remote?.server?.name ?: "?"
                ServerGlyph(name = serverName, modifier = Modifier.size(28.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        rowLabel,
                        style = if (isMobile) CRType.cardTitle else CRType.cardTitle.copy(fontSize = 16.sp),
                        color = if (isActive) c.text else c.textDim,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        StatusIndicator(
                            status = crStatus,
                            modifier = Modifier.size(8.dp),
                            viz = com.clauderemote.ui.theme.CRStatusViz.Dot,
                        )
                        if (mode != null) {
                            SidePanelModePill(mode = mode)
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (isActive && onRename != null && item.isConnected) {
                        IconButton(
                            onClick = { onRename(item.label) },
                            modifier = Modifier.size(18.dp),
                        ) { Text("✎", style = CRType.monoTiny, color = c.textDim) }
                    }
                    if (isActive && item.isConnected) {
                        IconButton(
                            onClick = { item.tab?.let { onTabClose(it.id) } },
                            modifier = Modifier.size(18.dp),
                        ) { Icon(Icons.Default.Close, "Close", tint = c.textDim, modifier = Modifier.size(12.dp)) }
                    }
                }
            }
        }
    }
}

// ── Mode pill for side panel ──────────────────────────────────────────────────

@Composable
private fun SidePanelModePill(mode: ClaudeMode) {
    val c = CRTheme.colors
    val (bg, fg, label) = when (mode) {
        ClaudeMode.YOLO        -> Triple(c.tintRed,    c.modeYolo,   "YOLO")
        ClaudeMode.AUTO        -> Triple(c.tintAccent, c.modeAuto,   "AUTO")
        ClaudeMode.PLAN        -> Triple(c.tintPurple, c.modePlan,   "PLAN")
        ClaudeMode.AUTO_ACCEPT -> Triple(c.tintGreen,  c.modeAuto,   "EDIT")
        ClaudeMode.NORMAL      -> Triple(c.surface2,   c.modeNormal, "NORM")
    }
    Pill(text = label, background = bg, foreground = fg)
}

// ── SessionActivity → CRStatus for side panel ────────────────────────────────

private fun com.clauderemote.model.SessionActivity?.sidePanelToCRStatus(isConnected: Boolean): CRStatus {
    if (!isConnected) return CRStatus.Disconnected
    return when (this) {
        com.clauderemote.model.SessionActivity.WORKING           -> CRStatus.Working
        com.clauderemote.model.SessionActivity.WAITING_FOR_INPUT -> CRStatus.Ready
        com.clauderemote.model.SessionActivity.APPROVAL_NEEDED   -> CRStatus.Approval
        com.clauderemote.model.SessionActivity.IDLE              -> CRStatus.Idle
        com.clauderemote.model.SessionActivity.DISCONNECTED      -> CRStatus.Disconnected
        null                                                     -> CRStatus.Idle
    }
}

// ---------------------------------------------------------------------------
// PROMPT INPUT BAR (logic preserved, chrome restyled)
// ---------------------------------------------------------------------------

