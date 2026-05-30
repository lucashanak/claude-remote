package com.clauderemote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauderemote.model.ClaudeHistorySession
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.Pill
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

/**
 * Browser of past Claude conversations discovered from server transcripts.
 * Entries are grouped by server+cwd and sorted newest-first. Each card shows a
 * LIVE badge when a currently-active tab or remote tmux maps to it, else Resume.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    sessions: List<ClaudeHistorySession>,
    loading: Boolean,
    /** UUIDs of currently-open tabs (claudeSessionId). Primary live signal. */
    liveUuids: Set<String>,
    /**
     * Total number of transcripts on server (before the ~50 cap). When
     * greater than sessions.size, a banner is shown. 0 = unknown/not yet scanned.
     */
    totalCount: Int = 0,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onResume: (ClaudeHistorySession) -> Unit,
    onAttachLive: (ClaudeHistorySession) -> Unit,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics

    val title = if (totalCount > sessions.size && sessions.isNotEmpty())
        "History (${sessions.size} of $totalCount)"
    else
        "History"

    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        style = CRType.cardTitle.copy(fontSize = 17.sp, color = c.accent),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.textDim)
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = c.textDim)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = c.surface,
                    scrolledContainerColor = c.surface,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = m.sectionPad, end = m.sectionPad, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(m.cardGap),
        ) {
            if (loading && sessions.isEmpty()) {
                item(key = "loading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(color = c.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(12.dp))
                        Text("Scanning transcripts…", style = CRType.bodyDim, color = c.textDim)
                    }
                }
            } else if (sessions.isEmpty()) {
                item(key = "empty") {
                    CRCard {
                        Text(
                            "No past conversations found.",
                            style = CRType.bodyDim,
                            color = c.textDim,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            } else {
                // Key includes cwd to guard against the same uuid appearing under
                // two encoded-cwd dirs (duplicate key would crash LazyColumn).
                items(sessions, key = { "${it.server.id}:${it.uuid}:${it.cwd}" }) { s ->
                    // Primary live signal: uuid match against open tabs only.
                    // Cwd-basename heuristic dropped — it produces false matches
                    // when unrelated projects share a folder name.
                    val isLive = s.uuid in liveUuids
                    HistoryCard(
                        session = s,
                        isLive = isLive,
                        onClick = { if (isLive) onAttachLive(s) else onResume(s) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    session: ClaudeHistorySession,
    isLive: Boolean,
    onClick: () -> Unit,
) {
    val c = CRTheme.colors
    CRCard(modifier = Modifier.clickable(onClick = onClick)) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    session.displayFolder,
                    style = CRType.cardTitle,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    relativeTime(session.lastModifiedEpoch),
                    style = CRType.monoTiny,
                    color = c.textDim,
                )
                if (isLive) {
                    Pill(text = "LIVE", background = c.tintGreen, foreground = c.ready)
                } else {
                    Pill(text = "Resume", background = c.tintAccent, foreground = c.accent)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${session.server.name} • ${session.cwd}",
                style = CRType.monoTiny,
                color = c.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session.preview.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    session.preview,
                    style = CRType.bodyDim,
                    color = c.textDim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Coarse relative time from an epoch-seconds timestamp ("3m", "5h", "2d"). */
private fun relativeTime(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    val deltaSec = (System.currentTimeMillis() / 1000L) - epochSeconds
    return when {
        deltaSec < 60 -> "just now"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86_400 -> "${deltaSec / 3600}h ago"
        else -> "${deltaSec / 86_400}d ago"
    }
}
