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
import androidx.compose.material.icons.Icons
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
                                // Merge active + remote into ONE list and
                                // sort the combined list by (folder, alias)
                                // — the previous code rendered actives
                                // first (sorted) then remotes (sorted),
                                // which split a tab and its server-side
                                // tmux peer across the two halves. User
                                // expectation: order is folder + alias,
                                // period; connectedness is incidental and
                                // shown by the per-row indicator only.
                                val activeList = activeByServer[sid] ?: emptyList()
                                val remoteList = remoteByServer[sid] ?: emptyList()
                                data class Entry(
                                    val folder: String,
                                    val alias: String,
                                    val active: ClaudeSession?,
                                    val remote: RemoteSession?,
                                )
                                val entries = buildList {
                                    activeList.forEach { s ->
                                        add(Entry(
                                            folder = s.folder.trimEnd('/').substringAfterLast('/').lowercase(),
                                            alias = s.alias.lowercase(),
                                            active = s,
                                            remote = null,
                                        ))
                                    }
                                    remoteList.forEach { r ->
                                        val parsed = TmuxNameParser.parse(r.tmuxSession.name, server.name)
                                        add(Entry(
                                            folder = parsed.folder.trimEnd('/').substringAfterLast('/').lowercase(),
                                            alias = parsed.alias.lowercase(),
                                            active = null,
                                            remote = r,
                                        ))
                                    }
                                }.sortedWith(compareBy({ it.folder }, { it.alias }))

                                item(key = "group_${server.id}") {
                                    DrawerGroupLabel(server = server, count = entries.size)
                                }
                                items(
                                    items = entries,
                                    key = { e ->
                                        e.active?.id
                                            ?: ("remote_${server.id}_" + (e.remote?.tmuxSession?.name ?: ""))
                                    },
                                ) { e ->
                                    val s = e.active
                                    val r = e.remote
                                    if (s != null) {
                                        DrawerItem(
                                            session = s,
                                            activity = activities[s.id] ?: SessionActivity.IDLE,
                                            selected = s.id == activeId,
                                            onClick = {
                                                onPick(s.id)
                                                onClose()
                                            },
                                            onLongPress = if (isMobile && onLongPressSession != null) {
                                                { onLongPressSession(s.id) }
                                            } else null,
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

                    HorizontalDivider(color = c.border, thickness = 1.dp)
                    DrawerFooter(
                        onNew = { onNew(); onClose() },
                        onReattachAll = { /* no-op until wired */ },
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
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val c = CRTheme.colors
    val bg = if (selected) c.tintAccent else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
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
                Text(
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
        ClaudeMode.PLAN        -> Triple(c.tintPurple, c.modePlan,   "PLAN")
        ClaudeMode.AUTO_ACCEPT -> Triple(c.tintGreen,  c.modeAuto,   "AUTO")
        ClaudeMode.NORMAL      -> Triple(c.surface2,   c.modeNormal, "NORM")
    }
    Pill(text = label, background = bg, foreground = fg)
}

// ── Footer ────────────────────────────────────────────────────────────────────

@Composable
private fun DrawerFooter(onNew: () -> Unit, onReattachAll: () -> Unit) {
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
