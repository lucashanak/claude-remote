package com.clauderemote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.RemoteSession
import com.clauderemote.model.SessionActivity
import com.clauderemote.model.SshServer
import com.clauderemote.model.TmuxNameParser
import com.clauderemote.ui.components.CRStatus
import com.clauderemote.ui.components.Pill
import com.clauderemote.ui.components.ServerGlyph
import com.clauderemote.ui.components.StatusIndicator
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

/**
 * Vertical session drawer — groups sessions by server, slide-in from left.
 *
 * Not wired into navigation yet; callers control [open]/[onClose].
 */
/** Stable list key for a remote pane — its tmux name is unique per server. */
private fun rowId(serverId: String, r: RemoteSession): String =
    "remote_${serverId}_" + r.tmuxSession.name

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SessionDrawer(
    open: Boolean,
    sessions: List<ClaudeSession>,
    activities: Map<String, SessionActivity> = emptyMap(),
    activeId: String = "",
    remoteSessions: List<RemoteSession> = emptyList(),
    onPick: (id: String) -> Unit = {},
    onAttachRemote: ((RemoteSession) -> Unit)? = null,
    onNew: () -> Unit = {},
    onClose: () -> Unit = {},
    onLongPressSession: ((id: String) -> Unit)? = null,
    onLogin: (() -> Unit)? = null,
    // Owned by TerminalScreen so the drawer and the side panel cannot hold two
    // private copies that overwrite each other. Persisted there.
    collapsedGroups: Set<String> = emptySet(),
    onToggleGroup: ((String) -> Unit)? = null,
) {
    if (!open && sessions.isEmpty() && remoteSessions.isEmpty()) return  // skip composition when not needed

    val c = CRTheme.colors
    var query by remember { mutableStateOf("") }

    // Reset filter when drawer closes
    LaunchedEffect(open) { if (!open) query = "" }

    Box(Modifier.fillMaxSize()) {
        // Backdrop
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
            )
        }

        // Drawer panel
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(tween(250)) { -it } + fadeIn(tween(200)),
            exit  = slideOutHorizontally(tween(250)) { -it } + fadeOut(tween(200)),
        ) {
            BoxWithConstraints {
                val drawerWidth: Dp = (maxWidth * 0.86f).coerceAtMost(320.dp)

                val drawerBrush = if (CRTheme.variant == com.clauderemote.ui.theme.CRVariant.Glass) {
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
                    Modifier
                        .width(drawerWidth)
                        .fillMaxHeight()
                        .background(drawerBrush)
                        .border(1.dp, c.border, RoundedCornerShape(0.dp)),
                ) {
                    DrawerHeader(count = sessions.size, onClose = onClose)
                    HorizontalDivider(color = c.border, thickness = 1.dp)
                    DrawerSearch(query = query, onQuery = { query = it })
                    HorizontalDivider(color = c.border, thickness = 1.dp)

                    val filteredActive = filterSessions(sessions, query)
                    val attachedTmuxByServer: Map<String, Set<String>> =
                        sessions.groupBy { it.server.id }
                            .mapValues { (_, list) -> list.map { it.tmuxSessionName }.toSet() }
                    val filteredRemote = filterRemote(
                        remoteSessions.filter { rs ->
                            rs.tmuxSession.name !in (attachedTmuxByServer[rs.server.id] ?: emptySet())
                        },
                        query
                    )

                    val activeByServer = filteredActive.groupBy { it.server.id }
                    val remoteByServer = filteredRemote.groupBy { it.server.id }
                    val serverIds = (activeByServer.keys + remoteByServer.keys).toList()
                    val serverById: Map<String, SshServer> = (
                        filteredActive.map { it.server } + filteredRemote.map { it.server }
                    ).associateBy { it.id }
                    val sortedServerIds = serverIds.sortedBy {
                        serverById[it]?.name?.lowercase() ?: it
                    }
                    val totalItems = filteredActive.size + filteredRemote.size

                    if (totalItems == 0) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (query.isBlank()) "No sessions"
                                else "No sessions match \"$query\"",
                                style = CRType.bodyDim,
                                color = c.textDim,
                            )
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            sortedServerIds.forEach { sid ->
                                val server = serverById[sid] ?: return@forEach
                                // Grouping, ordering and the "needs attention"
                                // hoist all live in SessionGrouping — the same
                                // rules the side panel uses, so the two lists
                                // cannot drift apart again.
                                val activeList = activeByServer[sid] ?: emptyList()
                                val remoteList = remoteByServer[sid] ?: emptyList()
                                val activeById = activeList.associateBy { it.id }
                                val remoteById = remoteList.associateBy { rowId(server.id, it) }
                                val entries = buildList {
                                    activeList.forEach { s ->
                                        val leaf = s.folder.trimEnd('/').substringAfterLast('/').ifBlank { s.folder }
                                        add(SessionGrouping.Entry(
                                            id = s.id,
                                            serverId = server.id,
                                            folderKey = leaf.lowercase(),
                                            folderLabel = leaf,
                                            alias = s.alias,
                                            searchText = listOf(
                                                s.alias, s.folder, server.name,
                                                s.mode.name, s.tmuxSessionName,
                                            ).joinToString(" ").lowercase(),
                                            // Same definition the launcher uses:
                                            // only an approval prompt is really
                                            // waiting on the user.
                                            needsAttention = activities[s.id] == SessionActivity.APPROVAL_NEEDED,
                                            isActive = s.id == activeId,
                                        ))
                                    }
                                    remoteList.forEach { r ->
                                        val parsed = TmuxNameParser.parse(r.tmuxSession.name, server.name)
                                        val leaf = parsed.folder.trimEnd('/').substringAfterLast('/')
                                            .ifBlank { parsed.folder }
                                        add(SessionGrouping.Entry(
                                            id = rowId(server.id, r),
                                            serverId = server.id,
                                            folderKey = leaf.lowercase(),
                                            folderLabel = leaf,
                                            alias = parsed.alias,
                                            searchText = listOf(
                                                parsed.alias, parsed.folder,
                                                server.name, r.tmuxSession.name,
                                            ).joinToString(" ").lowercase(),
                                            needsAttention = false,
                                            isActive = false,
                                        ))
                                    }
                                }
                                // The entries are already filtered by
                                // filterSessions/filterRemote above, which match
                                // more fields than this model would — so it only
                                // gets told to go flat, never to filter again.
                                val rows = SessionGrouping.build(
                                    entries, collapsedGroups, flat = query.isNotBlank(),
                                )

                                if (query.isBlank()) {
                                    stickyHeader(key = "group_${server.id}") {
                                        DrawerGroupLabel(server = server, count = entries.size)
                                    }
                                }
                                rows.forEach { row ->
                                    when (row) {
                                        // Every section header sticks, so the
                                        // pinned one always names the section
                                        // you are actually inside.
                                        is SessionGrouping.Row.AttentionHeader ->
                                            stickyHeader(key = "attention_${server.id}") {
                                                DrawerSectionHeader("Needs attention · ${row.count}", accent = true)
                                            }
                                        is SessionGrouping.Row.OtherHeader ->
                                            stickyHeader(key = "other_${server.id}") {
                                                DrawerSectionHeader("Other · ${row.count}")
                                            }
                                        is SessionGrouping.Row.FolderHeader ->
                                            stickyHeader(key = "folder_${row.key}") {
                                                DrawerFolderHeader(
                                                    row = row,
                                                    onToggle = {
                                                        onToggleGroup?.invoke(row.key)
                                                    },
                                                )
                                            }
                                        is SessionGrouping.Row.Item -> item(key = row.entry.id) {
                                            val s = activeById[row.entry.id]
                                            val r = remoteById[row.entry.id]
                                            if (s != null) {
                                                DrawerItem(
                                                    session = s,
                                                    activity = activities[s.id] ?: SessionActivity.IDLE,
                                                    selected = s.id == activeId,
                                                    // Under a folder header the
                                                    // folder is already on
                                                    // screen; showing it again
                                                    // is what truncated the
                                                    // alias.
                                                    aliasOnly = row.inGroup,
                                                    onClick = {
                                                        onPick(s.id)
                                                        onClose()
                                                    },
                                                    // Passed through unconditionally: DrawerItem
                                                    // itself decides mobile long-press vs desktop
                                                    // right-click.
                                                    onLongPress = onLongPressSession?.let { lp -> { lp(s.id) } },
                                                )
                                            } else if (r != null) {
                                                DrawerRemoteItem(
                                                    remote = r,
                                                    onClick = {
                                                        onAttachRemote?.invoke(r)
                                                        onClose()
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = c.border, thickness = 1.dp)
                    DrawerFooter(
                        onNew = { onNew(); onClose() },
                        onReattachAll = { /* no-op until wired */ },
                        onLogin = onLogin?.let { cb -> { cb(); onClose() } },
                    )
                }
            }
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun DrawerHeader(count: Int, onClose: () -> Unit) {
    val c = CRTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.Refresh,
            contentDescription = null,
            tint = c.textDim,
            modifier = Modifier.size(16.dp),
        )
        Text(
            "Sessions",
            style = if (isMobile) CRType.cardTitle else CRType.cardTitle.copy(fontSize = 16.sp),
            color = c.text,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count",
            style = CRType.pill,
            color = c.textDim,
        )
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = c.textDim, modifier = Modifier.size(16.dp))
        }
    }
}

// ── Search field ──────────────────────────────────────────────────────────────

@Composable
private fun DrawerSearch(query: String, onQuery: (String) -> Unit) {
    val c = CRTheme.colors
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = CRType.bodyDim.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(shape)
            .background(c.surface2)
            .border(1.dp, c.border, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        decorationBox = { innerTextField ->
            if (query.isEmpty()) {
                Text("Filter…", style = CRType.bodyDim, color = c.textDim)
            }
            innerTextField()
        },
    )
}

// ── Group label ───────────────────────────────────────────────────────────────

@Composable
private fun DrawerSectionHeader(title: String, accent: Boolean = false) {
    val c = CRTheme.colors
    Text(
        title,
        style = CRType.sectionH,
        color = if (accent) c.accent else c.textDim,
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
    )
}

/**
 * Sticky so the folder you are scrolling through stays named. The opaque
 * background is required, not decorative: rows sliding under a transparent
 * sticky header are unreadable.
 */
@Composable
private fun DrawerFolderHeader(
    row: SessionGrouping.Row.FolderHeader,
    onToggle: () -> Unit,
) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bg)
            .clickable(onClick = onToggle)
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            if (row.collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
            if (row.collapsed) "Expand ${row.label}" else "Collapse ${row.label}",
            tint = c.textDim,
            modifier = Modifier.size(14.dp),
        )
        Text(
            row.label,
            style = CRType.sectionH,
            color = c.textDim,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        // Counts sessions hoisted into "Needs attention" too, so a collapsed
        // folder never under-reports.
        Text(
            if (row.hoisted > 0) "${row.total} · ${row.hoisted}!" else "${row.total}",
            style = CRType.monoTiny,
            color = c.textDim,
        )
    }
}

@Composable
private fun DrawerGroupLabel(server: SshServer, count: Int) {
    val c = CRTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ServerGlyph(name = server.name, modifier = Modifier.size(14.dp))
        Text(
            server.name,
            style = if (isMobile) CRType.sectionH else CRType.sectionH.copy(fontSize = 13.sp),
            color = c.textDim,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count",
            style = CRType.bodyDim,
            color = c.textDim,
        )
    }
}

// ── Session item ──────────────────────────────────────────────────────────────

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun DrawerItem(
    session: ClaudeSession,
    activity: SessionActivity,
    selected: Boolean,
    /**
     * Under a folder header the folder path is already on screen, so the row
     * drops its second line and the title (which is the alias) gets the room.
     */
    aliasOnly: Boolean = false,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val c = CRTheme.colors
    val bg = if (selected) c.tintAccent else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            // Long-press-to-open-menu is a mobile gesture only; desktop opens
            // the same menu via right-click (secondaryClick below) instead —
            // Android never reports a secondary button, so that's inert there.
            .combinedClickable(onClick = onClick, onLongClick = if (isMobile) onLongPress else null)
            .secondaryClick(enabled = onLongPress != null) { onLongPress?.invoke() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left accent bar for active item
        Box(
            Modifier
                .width(3.dp)
                .height(48.dp)
                .background(if (selected) c.accent else Color.Transparent),
        )

        Row(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusIndicator(
                status = activity.toCRStatus(),
                modifier = Modifier.size(8.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.displayLabel,
                    style = if (isMobile) CRType.cardTitle else CRType.cardTitle.copy(fontSize = 16.sp),
                    color = if (selected) c.accent else c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Hidden only when the alias carries the row. Without an alias
                // the title falls back to the folder leaf, so dropping the path
                // too would render two identical rows in the same group.
                if (!aliasOnly || session.alias.isBlank()) Text(
                    session.folder,
                    style = CRType.monoTiny,
                    color = c.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                DrawerModePill(mode = session.mode)
                Spacer(Modifier.height(2.dp))
                Text(
                    session.durationText,
                    style = CRType.monoTiny,
                    color = c.textDim,
                )
            }
        }
    }
}

// ── Remote (un-attached) session row ──────────────────────────────────────────

@Composable
private fun DrawerRemoteItem(
    remote: RemoteSession,
    onClick: () -> Unit,
) {
    val c = CRTheme.colors
    val parsed = TmuxNameParser.parse(remote.tmuxSession.name, remote.server.name)
    val label = parsed.alias.ifBlank { parsed.folder.trimEnd('/').substringAfterLast('/').ifBlank { parsed.folder } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(48.dp))

        Row(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusIndicator(status = CRStatus.Idle, modifier = Modifier.size(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = CRType.cardTitle,
                    color = c.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    parsed.folder,
                    style = CRType.monoTiny,
                    color = c.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                DrawerModePill(mode = if (parsed.isYolo) ClaudeMode.YOLO else ClaudeMode.NORMAL)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (remote.tmuxSession.attached) "attached" else "detached",
                    style = CRType.monoTiny,
                    color = c.textDim,
                )
            }
        }
    }
}

// ── Mode mini-pill in drawer ──────────────────────────────────────────────────

@Composable
private fun DrawerModePill(mode: ClaudeMode) {
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

// ── Footer ────────────────────────────────────────────────────────────────────

@Composable
private fun DrawerFooter(onNew: () -> Unit, onReattachAll: () -> Unit, onLogin: (() -> Unit)? = null) {
    val c = CRTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(shape)
                .border(1.dp, c.border, shape)
                .clickable(onClick = onNew)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = c.accent, modifier = Modifier.size(14.dp))
            Text("New session", style = CRType.bodyDim, color = c.accent)
        }

        if (onLogin != null) {
            Row(
                Modifier
                    .clip(shape)
                    .border(1.dp, c.border, shape)
                    .clickable(onClick = onLogin)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.AccountCircle, contentDescription = "Přihlásit", tint = c.textDim, modifier = Modifier.size(14.dp))
                Text("Přihlásit", style = CRType.bodyDim, color = c.textDim)
            }
        }

        IconButton(
            onClick = onReattachAll,
            modifier = Modifier
                .size(36.dp)
                .clip(shape)
                .border(1.dp, c.border, shape),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Reattach all", tint = c.textDim, modifier = Modifier.size(16.dp))
        }
    }
}

// ── Filter helper ─────────────────────────────────────────────────────────────

private fun filterSessions(sessions: List<ClaudeSession>, query: String): List<ClaudeSession> {
    if (query.isBlank()) return sessions
    val q = query.lowercase()
    return sessions.filter { s ->
        s.folder.lowercase().contains(q) ||
            s.alias.lowercase().contains(q) ||
            s.server.name.lowercase().contains(q) ||
            s.mode.name.lowercase().contains(q)
    }
}

private fun filterRemote(remote: List<RemoteSession>, query: String): List<RemoteSession> {
    if (query.isBlank()) return remote
    val q = query.lowercase()
    return remote.filter { r ->
        val parsed = TmuxNameParser.parse(r.tmuxSession.name, r.server.name)
        parsed.folder.lowercase().contains(q) ||
            parsed.alias.lowercase().contains(q) ||
            r.server.name.lowercase().contains(q) ||
            r.tmuxSession.name.lowercase().contains(q)
    }
}

// ── SessionActivity → CRStatus mapping ───────────────────────────────────────

private fun SessionActivity.toCRStatus(): CRStatus = when (this) {
    SessionActivity.WORKING            -> CRStatus.Working
    SessionActivity.WAITING_FOR_INPUT  -> CRStatus.Ready
    SessionActivity.APPROVAL_NEEDED    -> CRStatus.Approval
    SessionActivity.IDLE               -> CRStatus.Idle
    SessionActivity.DISCONNECTED       -> CRStatus.Disconnected
}
