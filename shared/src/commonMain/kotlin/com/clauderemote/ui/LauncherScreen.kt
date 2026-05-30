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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.RemoteSession
import com.clauderemote.model.ServerHealth
import com.clauderemote.model.SessionActivity
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
    sessionActivities: Map<String, SessionActivity> = emptyMap(),
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
    onSessionLongPress: ((ClaudeSession) -> Unit)? = null,
    onSettings: () -> Unit,
    onViewLog: () -> Unit = {},
    onUsageDashboard: (() -> Unit)? = null,
    onCheckUpdate: (() -> Unit)? = null,
    serverHealth: Map<String, ServerHealth> = emptyMap(),
    onProbeServers: (Boolean) -> Unit = {},
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val sortedServers = servers.sortedByDescending { it.favorite }

    // Probe server reachability on first composition (debounced); pull-to-refresh forces.
    LaunchedEffect(Unit) { onProbeServers(false) }

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
            onRefresh = {
                onProbeServers(true)
                onRefreshRemote?.invoke()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = m.sectionPad, end = m.sectionPad, top = 0.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(m.cardGap),
            ) {
                // ── Needs attention (pinned) ──────────────────────────
                // Active sessions awaiting approval from the user only.
                val needsAttention: List<ClaudeSession> = activeSessions
                    .filter { sessionActivities[it.id] == SessionActivity.APPROVAL_NEEDED }
                    .sortedWith(compareBy({ it.server.name.lowercase() }, { it.alias.lowercase() }, { it.id }))
                val needsAttentionIds = needsAttention.map { it.id }.toSet()

                if (needsAttention.isNotEmpty()) {
                    item(key = "header_attention") {
                        LauncherSectionHeader(
                            title = "Needs attention · ${needsAttention.size}",
                            modifier = Modifier.padding(top = m.sectionTopGap, bottom = 4.dp),
                        )
                    }
                    items(needsAttention, key = { "attention_" + it.id }) { s ->
                        SessionLauncherCard(
                            session = s,
                            onClick = { onResumeSession(s) },
                            onLongPress = if (isMobile && onSessionLongPress != null) {
                                { onSessionLongPress(s) }
                            } else null,
                        )
                    }
                }

                // ── Sessions section (active + remote merged) ─────────
                val totalSessionCount = activeSessions.size + remoteSessions.count {
                    it.tmuxSession.name !in activeSessions.map { a -> a.tmuxSessionName }.toSet()
                }
                val sectionTitle = if (totalSessionCount == 0) "Sessions"
                    else "Sessions · $totalSessionCount"

                item(key = "header_active") {
                    LauncherSectionHeader(
                        title = sectionTitle,
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

                // Compact activity roll-up over all active sessions, omitting
                // zero buckets. Derived purely from sessionActivities (no polling).
                if (activeSessions.isNotEmpty()) {
                    val needYou = activeSessions.count { sessionActivities[it.id] == SessionActivity.APPROVAL_NEEDED }
                    val working = activeSessions.count { sessionActivities[it.id] == SessionActivity.WORKING }
                    val ready = activeSessions.count { sessionActivities[it.id] == SessionActivity.WAITING_FOR_INPUT }
                    val idle = activeSessions.count { val a = sessionActivities[it.id]; a == null || a == SessionActivity.IDLE }
                    if (needYou + working + ready + idle > 0) {
                        item(key = "activity_rollup") {
                            ActivityRollup(
                                needYou = needYou,
                                working = working,
                                ready = ready,
                                idle = idle,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                }

                // Unified list: actives + un-attached remotes merged
                // and sorted by (server, folder, alias) regardless of
                // connectedness. Section headers are kept separate
                // only for visual grouping by server.
                val connectedTmuxNames = activeSessions.map { it.tmuxSessionName }.toSet()
                val filteredRemote = remoteSessions
                    .filter { it.tmuxSession.name !in connectedTmuxNames }
                    .distinctBy { "${it.server.id}:${it.tmuxSession.name}" }

                data class LauncherEntry(
                    val serverKey: String,
                    val folderKey: String,
                    val aliasKey: String,
                    val active: ClaudeSession?,
                    val remote: RemoteSession?,
                )
                val merged: List<LauncherEntry> = buildList {
                    activeSessions.filter { it.id !in needsAttentionIds }.forEach { s ->
                        add(LauncherEntry(
                            serverKey = s.server.name.lowercase(),
                            folderKey = s.folder.trimEnd('/').substringAfterLast('/').lowercase(),
                            aliasKey = s.alias.lowercase(),
                            active = s,
                            remote = null,
                        ))
                    }
                    filteredRemote.forEach { r ->
                        val parsed = TmuxNameParser.parse(r.tmuxSession.name, r.server.name)
                        add(LauncherEntry(
                            serverKey = r.server.name.lowercase(),
                            folderKey = parsed.folder.trimEnd('/').substringAfterLast('/').lowercase(),
                            aliasKey = parsed.alias.lowercase(),
                            active = null,
                            remote = r,
                        ))
                    }
                }.sortedWith(
                    compareBy(
                        { it.serverKey },
                        { it.folderKey },
                        { it.aliasKey },
                    )
                )

                if (merged.isEmpty() && !remoteSessionsLoading) {
                    item(key = "empty_active") {
                        CRCard {
                            Text(
                                "No sessions. Connect to a server to start.",
                                style = CRType.bodyDim,
                                color = c.textDim,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                } else {
                    items(
                        items = merged,
                        key = { e ->
                            e.active?.id
                                ?: ("remote_" + (e.remote?.server?.id ?: "") + ":" + (e.remote?.tmuxSession?.name ?: ""))
                        },
                    ) { e ->
                        val s = e.active
                        val r = e.remote
                        if (s != null) {
                            SessionLauncherCard(
                                session = s,
                                onClick = { onResumeSession(s) },
                                onLongPress = if (isMobile && onSessionLongPress != null) {
                                    { onSessionLongPress(s) }
                                } else null,
                            )
                        } else if (r != null) {
                            RemoteSessionCard(remote = r, onClick = { onAttachRemote?.invoke(r) })
                        }
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
                            health = serverHealth[server.id] ?: ServerHealth.UNKNOWN,
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

// ── Activity roll-up strip ────────────────────────────────────────────────────

@Composable
private fun ActivityRollup(
    needYou: Int,
    working: Int,
    ready: Int,
    idle: Int,
    modifier: Modifier = Modifier,
) {
    val c = CRTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (needYou > 0) ActivityRollupItem("⚠ $needYou need you", c.approval)
        if (working > 0) ActivityRollupItem("▶ $working working", c.working)
        if (ready > 0) ActivityRollupItem("✓ $ready ready", c.ready)
        if (idle > 0) ActivityRollupItem("• $idle idle", c.idle)
    }
}

@Composable
private fun ActivityRollupItem(label: String, color: Color) {
    Text(
        text = label,
        style = CRType.monoTiny,
        color = color,
    )
}

// ── Session launcher card ─────────────────────────────────────────────────────

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SessionLauncherCard(
    session: ClaudeSession,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics

    val folderBasename = session.folder.trimEnd('/').substringAfterLast('/').ifBlank { session.folder }
    val sessionAlias = session.alias.ifBlank { null }

    CRCard(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
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
    health: ServerHealth = ServerHealth.UNKNOWN,
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
                ServerHealthDot(health)
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
                        ServerHealthDot(health)
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

/**
 * Small colored reachability dot for a server, mirroring the session activity
 * dot styling. ONLINE→green, OFFLINE→red, CHECKING→amber, UNKNOWN→dim grey.
 */
@Composable
private fun ServerHealthDot(health: ServerHealth) {
    val c = CRTheme.colors
    val color: Color = when (health) {
        ServerHealth.ONLINE -> c.ready
        ServerHealth.OFFLINE -> c.disconnected
        ServerHealth.CHECKING -> c.working
        ServerHealth.UNKNOWN -> c.idle
    }
    Box(
        Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(color),
    )
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
