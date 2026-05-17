package com.clauderemote.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.RemoteSession
import com.clauderemote.model.SessionStatus
import com.clauderemote.model.SshServer
import com.clauderemote.model.TmuxNameParser
import com.clauderemote.ui.components.ActivityHeatmap
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.Pill
import com.clauderemote.ui.components.ServerGlyph
import com.clauderemote.ui.components.StatusIndicator
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    servers: List<SshServer>,
    activeSessions: List<ClaudeSession>,
    remoteSessions: List<RemoteSession> = emptyList(),
    remoteSessionsLoading: Boolean = false,
    onRefreshRemote: (() -> Unit)? = null,
    onAttachRemote: ((RemoteSession) -> Unit)? = null,
    onConnectServer: (SshServer) -> Unit,
    onQuickConnect: ((SshServer) -> Unit)? = null,
    onAddServer: () -> Unit,
    onEditServer: (SshServer) -> Unit,
    onDuplicateServer: ((SshServer) -> Unit)? = null,
    onDeleteServer: (SshServer) -> Unit,
    onToggleFavorite: ((SshServer) -> Unit)? = null,
    onResumeSession: (ClaudeSession) -> Unit,
    onSettings: () -> Unit,
    onViewLog: () -> Unit = {},
    onUsageDashboard: (() -> Unit)? = null,
    onCheckUpdate: (() -> Unit)? = null,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val sortedServers = servers.sortedByDescending { it.favorite }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Claude Remote",
                        style = CRType.cardTitle.copy(fontSize = 17.sp, color = c.accent),
                    )
                },
                actions = {
                    if (onUsageDashboard != null) {
                        IconButton(onClick = onUsageDashboard) {
                            Icon(Icons.Default.DateRange, contentDescription = "Usage", tint = c.textDim)
                        }
                    }
                    if (onCheckUpdate != null) {
                        IconButton(onClick = onCheckUpdate) {
                            Icon(Icons.Default.Refresh, contentDescription = "Check for update", tint = c.textDim)
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = c.textDim)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = c.surface,
                    scrolledContainerColor = c.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddServer,
                containerColor = c.accent,
                contentColor = c.accentInk,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Server")
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = remoteSessionsLoading,
            onRefresh = { onRefreshRemote?.invoke() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = m.sectionPad, end = m.sectionPad, top = 0.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(m.cardGap),
            ) {
                // ── Active sessions section ──────────────────────────
                val activeSectionTitle = if (activeSessions.isEmpty())
                    "Active sessions"
                else
                    "Active sessions · ${activeSessions.size}"

                item(key = "header_active") {
                    LauncherSectionHeader(
                        title = activeSectionTitle,
                        modifier = Modifier.padding(top = m.sectionTopGap, bottom = 4.dp),
                        trailing = if (onUsageDashboard != null) {
                            {
                                TextButton(onClick = onUsageDashboard) {
                                    Text("Usage ›", style = CRType.bodyDim, color = c.textDim)
                                }
                            }
                        } else null,
                    )
                }

                if (activeSessions.isEmpty()) {
                    item(key = "empty_active") {
                        CRCard {
                            Text(
                                "No active sessions. Connect to a server to start.",
                                style = CRType.bodyDim,
                                color = c.textDim,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                } else {
                    val sortedActive = activeSessions.sortedWith(
                        compareBy(
                            { it.server.name.lowercase() },
                            { it.folder.trimEnd('/').substringAfterLast('/').lowercase() },
                            { it.alias.lowercase() },
                        )
                    )
                    items(sortedActive, key = { it.id }) { session ->
                        SessionLauncherCard(
                            session = session,
                            onClick = { onResumeSession(session) },
                        )
                    }
                }

                // ── Remote sessions (undiscovered tmux) ───────────────
                val connectedTmuxNames = activeSessions.map { it.tmuxSessionName }.toSet()
                val filteredRemote = remoteSessions
                    .filter { it.tmuxSession.name !in connectedTmuxNames }
                    .distinctBy { "${it.server.id}:${it.tmuxSession.name}" }
                    .sortedWith(
                        compareBy(
                            { it.server.name.lowercase() },
                            { TmuxNameParser.parse(it.tmuxSession.name, it.server.name).folder.lowercase() },
                            { it.tmuxSession.name.lowercase() },
                        )
                    )

                if (filteredRemote.isNotEmpty() || remoteSessionsLoading) {
                    item(key = "header_remote") {
                        LauncherSectionHeader(
                            title = "Remote sessions",
                            modifier = Modifier.padding(top = m.sectionTopGap, bottom = 4.dp),
                            trailing = if (remoteSessionsLoading) {
                                {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = c.textDim,
                                    )
                                }
                            } else null,
                        )
                    }
                    items(filteredRemote, key = { "${it.server.id}:${it.tmuxSession.name}" }) { remote ->
                        RemoteSessionCard(remote = remote, onClick = { onAttachRemote?.invoke(remote) })
                    }
                }

                // ── Servers section ──────────────────────────────────
                item(key = "header_servers") {
                    LauncherSectionHeader(
                        title = if (servers.isEmpty()) "Servers" else "Servers · ${servers.size}",
                        modifier = Modifier.padding(top = m.sectionTopGap, bottom = 4.dp),
                    )
                }

                if (servers.isEmpty()) {
                    item(key = "empty_servers") {
                        CRCard {
                            Text(
                                "No servers yet. Tap + to add your first server.",
                                style = CRType.bodyDim,
                                color = c.textDim,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                } else {
                    items(sortedServers, key = { it.id }) { server ->
                        val activeCount = activeSessions.count {
                            it.server.id == server.id && it.status == SessionStatus.ACTIVE
                        }
                        ServerLauncherCard(
                            server = server,
                            activeSessionCount = activeCount,
                            onConnect = { onConnectServer(server) },
                            onQuickConnect = onQuickConnect?.let { qc -> { qc(server) } },
                            onEdit = { onEditServer(server) },
                            onDuplicate = onDuplicateServer?.let { dup -> { dup(server) } },
                            onDelete = { onDeleteServer(server) },
                            onToggleFavorite = onToggleFavorite?.let { toggle -> { toggle(server) } },
                        )
                    }
                }
            }
        }
    }
}

// ── Section header ────────────────────────────────────────────────────────────

@Composable
private fun LauncherSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = CRTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = CRType.sectionH,
            color = c.textDim,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

// ── Session launcher card ─────────────────────────────────────────────────────

@Composable
private fun SessionLauncherCard(
    session: ClaudeSession,
    onClick: () -> Unit,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics

    val folderBasename = session.folder.trimEnd('/').substringAfterLast('/').ifBlank { session.folder }
    val sessionAlias = session.alias.ifBlank { null }

    CRCard(
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        if (m.sessionCardOneLine) {
            // Dense: single row — folder · alias (folder first)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusIndicator(
                    status = session.status.toCRStatus(),
                    modifier = Modifier.size(8.dp),
                )
                Text(
                    if (sessionAlias != null) "$folderBasename · $sessionAlias" else folderBasename,
                    style = CRType.cardTitle,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    session.server.name,
                    style = CRType.monoTiny,
                    color = c.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ModePillSmall(mode = session.mode)
                Text(
                    session.durationText,
                    style = CRType.monoTiny,
                    color = c.textDim,
                )
            }
        } else {
            // Regular / Compact: folder as title, alias as subtitle secondary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ServerGlyph(name = session.server.name, modifier = Modifier.size(36.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            folderBasename,
                            style = CRType.cardTitle,
                            color = c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        ModePillSmall(mode = session.mode)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append(session.server.name)
                            if (sessionAlias != null) { append(" · "); append(sessionAlias) }
                        },
                        style = CRType.mono,
                        color = c.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    StatusIndicator(status = session.status.toCRStatus())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        session.durationText,
                        style = CRType.monoTiny,
                        color = c.textDim,
                    )
                }
            }

            if (m.showPreviewLine) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "› ${session.folder}",
                    style = CRType.mono,
                    color = c.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (m.showHeatmap) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActivityHeatmap(
                        values = session.history,
                        modifier = Modifier.weight(1f).height(14.dp),
                        color = c.accent,
                    )
                }
            }
        }
    }
}

// ── Mode pill (compact, uppercase label) ─────────────────────────────────────

@Composable
private fun ModePillSmall(mode: ClaudeMode) {
    val c = CRTheme.colors
    val (bg, fg, label) = when (mode) {
        ClaudeMode.YOLO        -> Triple(c.tintRed,    c.modeYolo,   "YOLO")
        ClaudeMode.PLAN        -> Triple(c.tintPurple, c.modePlan,   "PLAN")
        ClaudeMode.AUTO_ACCEPT -> Triple(c.tintGreen,  c.modeAuto,   "AUTO")
        ClaudeMode.NORMAL      -> Triple(c.surface2,   c.modeNormal, "NORM")
    }
    Pill(text = label, background = bg, foreground = fg)
}

// ── Server launcher card ──────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerLauncherCard(
    server: SshServer,
    activeSessionCount: Int = 0,
    onConnect: () -> Unit,
    onQuickConnect: (() -> Unit)? = null,
    onEdit: () -> Unit,
    onDuplicate: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val clickModifier = if (onQuickConnect != null) {
        Modifier.combinedClickable(onClick = onConnect, onLongClick = onQuickConnect)
    } else {
        Modifier.clickable(onClick = onConnect)
    }

    CRCard(modifier = clickModifier) {
        if (m.sessionCardOneLine) {
            // Dense layout
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ServerGlyph(name = server.name, modifier = Modifier.size(18.dp))
                Text(
                    server.name,
                    style = CRType.cardTitle,
                    color = c.text,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (server.favorite) {
                    Text("★", color = c.working, style = CRType.bodyDim)
                }
                Text(
                    server.displayAddress,
                    style = CRType.monoTiny,
                    color = c.textDim,
                )
                if (activeSessionCount > 0) {
                    Pill(
                        text = "$activeSessionCount",
                        background = c.tintGreen,
                        foreground = c.ready,
                    )
                }
                OutlinedButton(
                    onClick = onConnect,
                    modifier = Modifier.height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("▶", style = CRType.monoTiny, color = c.accent)
                }
            }
        } else {
            // Regular / Compact layout
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ServerGlyph(name = server.name, modifier = Modifier.size(36.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            server.name,
                            style = CRType.cardTitle,
                            color = c.text,
                        )
                        if (server.favorite) {
                            Text("★", color = c.working, style = CRType.bodyDim)
                        }
                        if (activeSessionCount > 0) {
                            Pill(
                                text = "$activeSessionCount live",
                                background = c.tintGreen,
                                foreground = c.ready,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        server.displayAddress,
                        style = CRType.mono,
                        color = c.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Action buttons
                if (onToggleFavorite != null) {
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                        Text(
                            if (server.favorite) "★" else "☆",
                            color = if (server.favorite) c.working else c.textDim,
                        )
                    }
                }
                OutlinedButton(
                    onClick = { onConnect() },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text("Connect", style = CRType.bodyDim, color = c.accent)
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = c.textDim, modifier = Modifier.size(16.dp))
                }
                if (onDuplicate != null) {
                    IconButton(onClick = onDuplicate, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "Duplicate", tint = c.textDim, modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = c.textDim, modifier = Modifier.size(16.dp))
                }
            }

            // Recent folders chips
            if (server.recentFolders.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    server.recentFolders.take(3).forEach { folder ->
                        val label = folder.trimEnd('/').substringAfterLast('/').let {
                            if (it.length > 22) "…${folder.takeLast(21)}" else folder
                        }
                        val shape = RoundedCornerShape(6.dp)
                        Text(
                            label,
                            style = CRType.mono,
                            color = c.textDim,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(shape)
                                .background(c.surface2)
                                .border(1.dp, c.border, shape)
                                .clickable(onClick = onConnect)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete server", style = CRType.cardTitle) },
            text = { Text("Delete ${server.name}?", style = CRType.bodyDim, color = CRTheme.colors.textDim) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("Delete", color = CRTheme.colors.disconnected)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ── Remote session card ───────────────────────────────────────────────────────

@Composable
private fun RemoteSessionCard(remote: RemoteSession, onClick: () -> Unit) {
    val c = CRTheme.colors
    CRCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val attachedColor = if (remote.tmuxSession.attached) c.ready else c.textDim
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(attachedColor),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(remote.tmuxSession.name, style = CRType.cardTitle, color = c.text)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${remote.server.name} • ${remote.tmuxSession.windows}w" +
                        if (remote.tmuxSession.attached) " • attached" else "",
                    style = CRType.bodyDim,
                    color = c.textDim,
                )
            }
            if (remote.tmuxSession.attached) {
                Pill(text = "attached", background = c.tintGreen, foreground = c.ready)
            }
        }
    }
}

// ── SessionStatus → CRStatus mapping ─────────────────────────────────────────

private fun SessionStatus.toCRStatus(): com.clauderemote.ui.components.CRStatus = when (this) {
    SessionStatus.ACTIVE       -> com.clauderemote.ui.components.CRStatus.Working
    SessionStatus.CONNECTING   -> com.clauderemote.ui.components.CRStatus.Idle
    SessionStatus.DISCONNECTED -> com.clauderemote.ui.components.CRStatus.Disconnected
    SessionStatus.ERROR        -> com.clauderemote.ui.components.CRStatus.Disconnected
}
