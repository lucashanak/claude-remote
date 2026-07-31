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
internal fun JumpToLatestPill(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val c = CRTheme.colors
    Pill(
        text = "Jump to latest ↓",
        background = c.accent,
        foreground = c.accentInk,
        modifier = modifier
            .padding(bottom = 12.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
    )
}

// ---------------------------------------------------------------------------
// CRTopBar
// ---------------------------------------------------------------------------

@Composable
internal fun CRTopBar(
    activeSession: ClaudeSession?,
    sessionActivities: Map<String, com.clauderemote.model.SessionActivity>,
    hasMultiple: Boolean,
    wideMode: Boolean,
    tabs: List<ClaudeSession>,
    allSessions: Map<String, List<SessionItem>>,
    activeTabId: String?,
    invertColors: Boolean,
    terminalView: CRTerminalView,
    latencyMs: Long?,
    /**
     * Two-letter tag for the account this session runs under (see
     * ClaudeAccount.initials). Lives in the TOP bar, not the transcript status
     * bar, because that one only renders in Chat view — in Raw view the user
     * had no way to tell which login they were on.
     */
    accountLabel: String? = null,
    contextPercent: Int?,
    gitStatus: com.clauderemote.model.GitStatus?,
    sessionUsagePercent: Int?,
    weekUsagePercent: Int?,
    compactMode: Boolean,
    showControlBar: Boolean,
    onMenuOpen: () -> Unit,
    onTabSwitch: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onAttachRemote: ((com.clauderemote.model.RemoteSession) -> Unit)?,
    onToggleInvertColors: (() -> Unit)?,
    onTerminalViewChange: ((CRTerminalView) -> Unit)?,
    onToggleCompact: () -> Unit,
    onToggleControlBar: () -> Unit,
    onMoreMenu: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics

    Surface(
        color = c.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().height(m.rowHeight)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!wideMode) {
                IconButton(onClick = onMenuOpen, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Menu, "Sessions", tint = c.textDim, modifier = Modifier.size(20.dp))
                }
            }

            // Session title — tapping opens the slide-in SessionDrawer.
            // The inner Row MUST fillMaxWidth() so it's hard-capped to this
            // Box's weighted share: without it, a long title's natural Text
            // width can make the Row report a size larger than what it was
            // given, pushing the invert-colors/more-menu buttons off-screen
            // to the right instead of being absorbed by ellipsis here.
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { if (hasMultiple && !wideMode) onOpenDrawer() },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (activeSession != null) {
                        val activity = sessionActivities[activeSession.id]
                        val dotColor = activityDotColor(activity, activeSession.status)
                        Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
                    }
                    Text(
                        activeSession?.tabTitle ?: "",
                        style = CRType.cardTitle,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (hasMultiple && !wideMode) {
                        Text("(${tabs.size})", style = CRType.monoTiny, color = c.textDim)
                        Text("▾", style = CRType.bodyDim, color = c.textDim)
                    }
                }
            }

            // Informational chips (git branch, model, latency, usage) are
            // secondary — nice to see, safe to lose. A hard width CEILING +
            // horizontalScroll (rather than a second weight(1f)) means this
            // cluster still shrinks to its actual content in the common case
            // — leaving the title as the sole weighted child, getting the
            // full leftover, same as before — while never being able to grow
            // past the ceiling and threaten the functional controls that
            // follow (view toggle, compact/control-bar toggles, invert-
            // colors, more-menu); scroll is the escape hatch if it's ever
            // squeezed below its content's natural width.
            Row(
                modifier = Modifier
                    .widthIn(max = 210.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Usage mini bars FIRST — this is the metric the user most wants
                // always visible. The cluster is width-capped + horizontally
                // scrollable, so whatever comes last scrolls off when cramped;
                // a long git branch used to push the usage off-screen entirely
                // (visible on a `main` session, gone on `ci/supabase-auto-migrate`).
                // Order = priority: usage, then latency, model, and git branch
                // last (widest + most variable → it's the one that scrolls away).
                if (contextPercent != null || sessionUsagePercent != null || weekUsagePercent != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                        if (contextPercent != null) MiniBar("Ctx", contextPercent)
                        if (sessionUsagePercent != null) MiniBar("5h", sessionUsagePercent)
                        if (weekUsagePercent != null) MiniBar("Wk", weekUsagePercent)
                    }
                }

                // Latency
                if (latencyMs != null) {
                    val latColor = when {
                        latencyMs < 100 -> c.ready
                        latencyMs < 300 -> c.working
                        else -> c.disconnected
                    }
                    Text("${latencyMs}ms", style = CRType.monoTiny, color = latColor,
                        modifier = Modifier.padding(horizontal = 4.dp))
                }

                // Account tag — which login this session runs Claude under.
                if (!accountLabel.isNullOrBlank()) {
                    Text(
                        accountLabel,
                        style = CRType.monoTiny,
                        color = c.accent,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                // Active model chip
                if (activeSession != null) {
                    ModelChip(activeSession.model)
                }

                // Git status chip (branch + dirty/ahead/behind) — only for git
                // repos. LAST: the branch name is the widest, most variable chip,
                // so it's the one that scrolls off when the cluster is cramped.
                if (gitStatus != null) {
                    GitChip(gitStatus)
                }
            }

            // Terminal view toggle (Raw / Transcript) — a single button showing
            // the CURRENT view; tapping flips it. Replaces the wider two-segment
            // Segmented switch to free horizontal space. Accent color reads as
            // active/tappable. Compact/control-bar toggles now live in the ⋮ menu.
            if (onTerminalViewChange != null) {
                TextButton(onClick = {
                    onTerminalViewChange(if (terminalView == CRTerminalView.Raw) CRTerminalView.Transcript else CRTerminalView.Raw)
                }) {
                    Text(if (terminalView == CRTerminalView.Raw) "Raw" else "Chat", color = c.accent, style = CRType.bodyDim)
                }
            }

            // Invert colors (sunlight-readable)
            if (onToggleInvertColors != null) {
                IconButton(onClick = onToggleInvertColors, modifier = Modifier.size(36.dp)) {
                    Text(
                        if (invertColors) "☾" else "☀",
                        style = MaterialTheme.typography.titleMedium,
                        color = c.textDim
                    )
                }
            }

            // More menu
            IconButton(onClick = onMoreMenu, modifier = Modifier.size(36.dp)) {
                Text("⋮", style = MaterialTheme.typography.titleMedium, color = c.textDim)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// CrumbBar (spec §6.3)
// ---------------------------------------------------------------------------

@Composable
internal fun CrumbBar(
    session: ClaudeSession,
    allSessions: List<SessionItem>,
    index: Int,
    total: Int,
    onOpenDrawer: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(c.bg)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Sessions button
        Row(
            modifier = Modifier
                .background(c.surface, RoundedCornerShape(6.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onOpenDrawer() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Default.Menu, null, tint = c.textDim, modifier = Modifier.size(12.dp))
            Text("Sessions", style = CRType.monoTiny, color = c.textDim)
        }

        // Server : folder · alias
        val folderName = session.folder.trimEnd('/').substringAfterLast('/').ifBlank { session.folder }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(session.server.name, style = CRType.mono, color = c.textDim, maxLines = 1)
            Text(":", style = CRType.mono, color = c.border)
            Text(folderName, style = CRType.mono, color = c.text, maxLines = 1)
            if (session.alias.isNotBlank()) {
                Text("·", style = CRType.mono, color = c.border)
                Text(session.alias, style = CRType.mono, color = c.accent, maxLines = 1)
            }
        }

        // Prev / counter / next
        IconButton(
            onClick = onPrev,
            enabled = index > 0,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowLeft, null, tint = if (index > 0) c.textDim else c.border,
                modifier = Modifier.size(14.dp))
        }
        Text("${index + 1}/$total", style = CRType.monoTiny, color = c.textDim)
        IconButton(
            onClick = onNext,
            enabled = index < total - 1,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(Icons.Default.KeyboardArrowRight, null, tint = if (index < total - 1) c.textDim else c.border,
                modifier = Modifier.size(14.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// StatusRow — activity + last line + cost
// ---------------------------------------------------------------------------

@Composable
internal fun StatusRow(
    session: ClaudeSession,
    activity: SessionActivity?,
) {
    val c = CRTheme.colors
    val crStatus = activity.toCRStatus()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusIndicator(status = crStatus)
        Spacer(Modifier.weight(1f))
    }
}

private fun SessionActivity?.toCRStatus(): CRStatus = when (this) {
    SessionActivity.WORKING -> CRStatus.Working
    SessionActivity.WAITING_FOR_INPUT -> CRStatus.Ready
    SessionActivity.APPROVAL_NEEDED -> CRStatus.Approval
    SessionActivity.IDLE -> CRStatus.Idle
    SessionActivity.DISCONNECTED -> CRStatus.Disconnected
    null -> CRStatus.Idle
}

// ---------------------------------------------------------------------------
// SpecialKeysRow (spec §6.3 CRITICAL)
// ---------------------------------------------------------------------------

