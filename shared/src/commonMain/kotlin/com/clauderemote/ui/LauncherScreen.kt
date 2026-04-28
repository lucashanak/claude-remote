package com.clauderemote.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.RemoteSession
import com.clauderemote.model.SessionStatus
import com.clauderemote.model.SshServer

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
    onUsageDashboard: (() -> Unit)? = null
) {
    val sortedServers = servers.sortedByDescending { it.favorite }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Claude Remote") },
                actions = {
                    if (onUsageDashboard != null) {
                        TextButton(onClick = onUsageDashboard) {
                            Text("Usage")
                        }
                    }
                    TextButton(onClick = onViewLog) {
                        Text("Log")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddServer) {
                Icon(Icons.Default.Add, "Add Server")
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = remoteSessionsLoading,
            onRefresh = { onRefreshRemote?.invoke() },
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Active Sessions
            if (activeSessions.isNotEmpty()) {
                item {
                    Text(
                        "Active Sessions",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                items(activeSessions, key = { it.id }) { session ->
                    ActiveSessionCard(session, onClick = { onResumeSession(session) })
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            // Remote Sessions (tmux sessions discovered on servers)
            // Filter out sessions already connected in-app
            val connectedTmuxNames = activeSessions.map { it.tmuxSessionName }.toSet()
            val filteredRemote = remoteSessions
                .filter { it.tmuxSession.name !in connectedTmuxNames }
                .distinctBy { "${it.server.id}:${it.tmuxSession.name}" }
            if (filteredRemote.isNotEmpty() || remoteSessionsLoading) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Text(
                            "Remote Sessions",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (remoteSessionsLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
                items(filteredRemote, key = { "${it.server.id}:${it.tmuxSession.name}" }) { remote ->
                    RemoteSessionCard(remote, onClick = { onAttachRemote?.invoke(remote) })
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            // Servers
            item {
                Text(
                    "Servers",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            if (servers.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            "No servers configured. Tap + to add one.",
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(sortedServers, key = { it.id }) { server ->
                val activeCount = activeSessions.count { it.server.id == server.id && it.status == SessionStatus.ACTIVE }
                ServerCard(
                    server = server,
                    activeSessionCount = activeCount,
                    onConnect = { onConnectServer(server) },
                    onQuickConnect = onQuickConnect?.let { qc -> { qc(server) } },
                    onEdit = { onEditServer(server) },
                    onDuplicate = onDuplicateServer?.let { dup -> { dup(server) } },
                    onDelete = { onDeleteServer(server) },
                    onToggleFavorite = onToggleFavorite?.let { toggle -> { toggle(server) } }
                )
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
        } // end PullToRefreshBox
    }
}

@Composable
private fun ActiveSessionCard(session: ClaudeSession, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (session.status == SessionStatus.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
            } else {
                val statusColor = when (session.status) {
                    SessionStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                    SessionStatus.DISCONNECTED -> MaterialTheme.colorScheme.error
                    SessionStatus.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.tertiary
                }
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = MaterialTheme.shapes.small,
                    color = statusColor
                ) {}
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.displayLabel,
                        style = MaterialTheme.typography.titleSmall
                    )
                    val folderLabel = session.folder.trimEnd('/').substringAfterLast('/').ifBlank { session.folder }
                    if (session.alias.isNotBlank() && folderLabel != session.displayLabel) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            folderLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "${session.server.name} \u00B7 ${session.mode.displayName.lowercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                session.durationText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ServerCard(
    server: SshServer,
    activeSessionCount: Int = 0,
    onConnect: () -> Unit,
    onQuickConnect: (() -> Unit)? = null,
    onEdit: () -> Unit,
    onDuplicate: (() -> Unit)? = null,
    onDelete: () -> Unit,
    onToggleFavorite: (() -> Unit)? = null
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onQuickConnect != null) Modifier.combinedClickable(
                    onClick = { onConnect() },
                    onLongClick = { onQuickConnect() }
                ) else Modifier.clickable { onConnect() }
            )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Favorite star
                if (onToggleFavorite != null) {
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(28.dp)) {
                        Text(
                            if (server.favorite) "\u2605" else "\u2606",
                            color = if (server.favorite) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(server.name, style = MaterialTheme.typography.titleSmall)
                        if (activeSessionCount > 0) {
                            Spacer(Modifier.width(6.dp))
                            Badge { Text("$activeSessionCount") }
                        }
                    }
                    Text(
                        server.displayAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(20.dp))
                }
                if (onDuplicate != null) {
                    IconButton(onClick = onDuplicate) {
                        Icon(Icons.Default.Add, "Duplicate", modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(20.dp))
                }
            }

            // Recent folders as quick-connect chips
            if (server.recentFolders.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    server.recentFolders.take(3).forEach { folder ->
                        AssistChip(
                            onClick = { onConnect() },
                            label = {
                                Text(
                                    folder.substringAfterLast('/').ifBlank { folder },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Server") },
            text = { Text("Delete ${server.name}?") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun RemoteSessionCard(remote: RemoteSession, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(12.dp),
                shape = MaterialTheme.shapes.small,
                color = if (remote.tmuxSession.attached)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.outline
            ) {}

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    remote.tmuxSession.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "${remote.server.name} \u2022 ${remote.tmuxSession.windows}w" +
                        if (remote.tmuxSession.attached) " \u2022 attached" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
