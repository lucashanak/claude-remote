@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.clauderemote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.RemoteSession
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.service.RefuseReason
import com.clauderemote.session.service.ReplyGuard
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.CRStatus
import com.clauderemote.ui.components.Segmented
import com.clauderemote.ui.components.color
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Přehled and Čeká (F0); Úkoly and Jarvis (F3). */
internal enum class VelinTab { PREHLED, CEKA, UKOLY, JARVIS }

internal fun VelinState.crStatus(): CRStatus = when (this) {
    VelinState.APPROVAL, VelinState.QUESTION -> CRStatus.Approval
    VelinState.CRASHED -> CRStatus.Crashed
    VelinState.WORKING -> CRStatus.Working
    VelinState.READY -> CRStatus.Ready
    VelinState.OFFLINE -> CRStatus.Disconnected
    VelinState.IDLE -> CRStatus.Idle
}

/** How long a "už vyřízeno jinde" note stays in the inbox. */
private const val GONE_NOTE_MS = 5_000L

/**
 * Velín (F0): every session at a glance, grouped by project, and an inbox of what waits for the
 * user — built only from what the app already tracks. A reply from the inbox goes through
 * [SessionOrchestrator.sendReplyAsync], the same checked path as Ano/Ne and "Odpovědět" of the
 * notification (ReplyGuard on the phone, the prompt's hook event re-checked on the server).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VelinScreen(
    tabs: List<ClaudeSession>,
    remoteSessions: List<RemoteSession>,
    remoteSessionsLoading: Boolean,
    sessionOrchestrator: SessionOrchestrator,
    accountTags: Map<String, AccountTag>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSession: (ClaudeSession) -> Unit,
    onAttachRemote: (RemoteSession) -> Unit,
    onAccountUsage: () -> Unit,
    /** Ask once whether the app should open here (R16); null hides the strip. */
    onStartOffer: ((Boolean) -> Unit)?,
    /** The top-bar Jarvis button; null hides it. */
    onJarvis: (() -> Unit)? = null,
    /** This device's alert routing (Jarvis tab's switch) and where a change goes. */
    alertRouting: com.clauderemote.session.service.AlertRouting = com.clauderemote.session.service.AlertRouting.PER_SESSION,
    onAlertRouting: (com.clauderemote.session.service.AlertRouting) -> Unit = {},
    /** The tab to open on (the merged Jarvis notification opens the Jarvis tab). */
    initialTab: VelinTab = VelinTab.PREHLED,
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val scope = rememberCoroutineScope()

    val activities by sessionOrchestrator.sessionActivities.collectAsState()
    val shown by sessionOrchestrator.shownPrompts.collectAsState()
    val endedAt by sessionOrchestrator.claudeEndedAt.collectAsState()
    val buckets by sessionOrchestrator.usageBuckets.collectAsState()
    val fiveHour by sessionOrchestrator.sessionUsagePercents.collectAsState()
    val week by sessionOrchestrator.weekUsagePercents.collectAsState()

    // Ages, the crash grace and the transcript's last message are read on this tick: none of them
    // is a flow, and a few seconds of lag in "12 min" costs nothing.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis()
        }
    }

    val rows = remember(tabs, remoteSessions, activities, shown, endedAt, now) {
        Velin.rowsOf(
            tabs, remoteSessions, now,
            activityOf = { activities[it] },
            promptKindOf = { shown[it]?.current?.prompt?.kind },
            endedAtOf = { endedAt[it] },
            sinceOf = { sessionOrchestrator.activitySinceMs(it) },
        )
    }
    val inbox = remember(rows, shown, now) {
        Velin.sortInbox(rows.mapNotNull { r ->
            val id = r.sessionId ?: return@mapNotNull null
            val p = shown[id]
            Velin.inboxItemFor(
                sessionId = id,
                title = r.title,
                state = r.state,
                prompt = p?.current?.prompt,
                promptText = p?.current?.text,
                promptAtMs = p?.atMs,
                hookNamed = p?.current?.hookEvent() != null,
                lastAssistantText = if (r.state == VelinState.READY) sessionOrchestrator.lastAssistantText(id) else null,
                readyPrompt = if (r.state == VelinState.READY) sessionOrchestrator.currentPrompt(id) else null,
                sinceMs = r.sinceMs,
            )
        })
    }

    // Replies sent from here, by prompt key: null = in flight; Sent / a refusal once answered.
    val outcomes = remember { mutableStateMapOf<String, ReplyOutcome>() }
    val answeredHere = remember { mutableStateListOf<String>() }
    val gone = remember { mutableStateListOf<Pair<InboxItem, Long>>() }
    var previousInbox by remember { mutableStateOf(inbox) }
    LaunchedEffect(inbox) {
        val live = rows.filter { it.sessionId != null && it.state != VelinState.OFFLINE && it.state != VelinState.CRASHED }
            .mapTo(HashSet()) { it.sessionId!! }
        val left = Velin.goneElsewhere(previousInbox, inbox, answeredHere.toSet(), live)
        val t = System.currentTimeMillis()
        left.forEach { gone.add(it to t + GONE_NOTE_MS) }
        // Items answered from here that left: their outcome is history now.
        val nowKeys = inbox.mapTo(HashSet()) { it.key }
        previousInbox.filter { it.key !in nowKeys }.forEach { outcomes.remove(it.key); answeredHere.remove(it.key) }
        previousInbox = inbox
    }
    LaunchedEffect(gone.size) {
        while (gone.isNotEmpty()) {
            delay(1_000)
            val t = System.currentTimeMillis()
            gone.removeAll { it.second <= t }
        }
    }

    fun answer(item: InboxItem, reply: ReplyGuard.Reply, text: String) {
        val key = item.key
        if (outcomes[key] == ReplyOutcome.Sending) return
        outcomes[key] = ReplyOutcome.Sending
        answeredHere.add(key)
        // The orchestrator's scope, not this screen's: leaving the Velín mid-exec must not cancel
        // a reply whose keys may already be on their way.
        sessionOrchestrator.sendReplyAsync(item.sessionId, item.prompt, reply, text) { why ->
            scope.launch {
                outcomes[key] = if (why == null) ReplyOutcome.Sent else ReplyOutcome.Refused(why)
                // Only a reply that surely went nowhere lets the item count as "answered elsewhere" later.
                if (why != null && why != RefuseReason.MAYBE_SENT && why != RefuseReason.TYPED_NOT_SENT &&
                    why != RefuseReason.ALREADY_ANSWERED) answeredHere.remove(key)
            }
        }
    }

    var tab by remember(initialTab) { mutableStateOf(initialTab) }
    val feed by sessionOrchestrator.jarvisAlerts.state.collectAsState()
    val jarvis = remember { JarvisPanelState() }
    val jarvisServer = remember(tabs, remoteSessions, feed.serverId) { sessionOrchestrator.jarvisServer(tabs, remoteSessions) }
    val expandedTasks = remember { mutableStateListOf<String>() }
    // Reads on entering a tab (and the refresh below): Jarvis' state changes on his schedule, not the app's.
    LaunchedEffect(tab, jarvisServer?.id) {
        when (tab) {
            VelinTab.UKOLY -> jarvis.loadTasks(sessionOrchestrator, jarvisServer)
            VelinTab.JARVIS -> jarvis.loadJarvis(sessionOrchestrator, jarvisServer)
            else -> {}
        }
    }
    // The Jarvis tab shows what was pending when it opened and what came since; being shown is being seen.
    LaunchedEffect(tab, feed.pending) {
        if (tab != VelinTab.JARVIS) return@LaunchedEffect
        jarvis.recentAlerts = (feed.pending + jarvis.recentAlerts).distinctBy { it.id }.take(20)
        if (feed.pending.isNotEmpty()) sessionOrchestrator.jarvisAlerts.acknowledge()
    }
    val refresh: () -> Unit = {
        onRefresh()
        when (tab) {
            VelinTab.UKOLY -> scope.launch { jarvis.taskMail.clear(); jarvis.loadTasks(sessionOrchestrator, jarvisServer) }
            VelinTab.JARVIS -> scope.launch { jarvis.loadJarvis(sessionOrchestrator, jarvisServer) }
            else -> {}
        }
    }
    val openTmux: (String?, String) -> Unit = { serverId, tmux ->
        openTmuxTarget(tabs, remoteSessions, serverId, tmux, onOpenSession, onAttachRemote)
    }
    val tabsById = remember(tabs) { tabs.associateBy { it.id } }
    val remotesByKey = remember(remoteSessions) {
        remoteSessions.associateBy { "remote:${it.server.id}:${it.tmuxSession.name}" }
    }
    val open: (VelinRow) -> Unit = { r ->
        val s = r.sessionId?.let { tabsById[it] }
        if (s != null) onOpenSession(s) else remotesByKey[r.key]?.let(onAttachRemote)
    }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = c.surface,
                    titleContentColor = c.text,
                    navigationIconContentColor = c.textDim,
                ),
                title = { Text("Velín", style = CRType.cardTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zpět", tint = c.textDim)
                    }
                },
                actions = {
                    if (onJarvis != null) {
                        JarvisButton(onJarvis = onJarvis, onVelin = null, tint = c.accent, size = 48.dp, iconSize = 22.dp)
                    }
                    IconButton(onClick = refresh) {
                        Icon(Icons.Default.Refresh, "Obnovit", tint = c.textDim)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        Column(
            Modifier.padding(start = m.sectionPad, end = m.sectionPad, top = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(m.cardGap),
        ) {
            if (onStartOffer != null) StartOfferStrip(onStartOffer)
            Segmented(
                options = VelinTab.entries.toList(),
                selected = tab,
                onSelect = { tab = it },
                label = {
                    when (it) {
                        VelinTab.PREHLED -> "Přehled"
                        VelinTab.CEKA -> "Čeká ${inbox.size}"
                        VelinTab.UKOLY -> "Úkoly"
                        VelinTab.JARVIS -> if (feed.pending.isEmpty()) "Jarvis" else "Jarvis ${feed.pending.size}"
                    }
                },
            )
        }
        if (tab == VelinTab.JARVIS) {
            JarvisPane(
                state = jarvis, feed = feed, routing = alertRouting, onRouting = onAlertRouting,
                server = jarvisServer, orch = sessionOrchestrator, scope = scope,
                jarvisTab = jarvisTabOf(tabs, feed.serverId), now = now,
                onOpenTmux = { sid, t -> openTmux(sid, t) },
                onOpenJarvis = onJarvis,
                modifier = Modifier.padding(start = m.sectionPad, end = m.sectionPad, bottom = 8.dp),
            )
        } else
        PullToRefreshBox(
            isRefreshing = remoteSessionsLoading || (tab == VelinTab.UKOLY && jarvis.tasksLoading),
            onRefresh = refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = m.sectionPad, end = m.sectionPad, top = 4.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(m.cardGap),
            ) {
                when (tab) {
                    VelinTab.PREHLED -> overview(
                        rows = rows, inbox = inbox, tabs = tabs, now = now,
                        accountTags = accountTags,
                        usageOf = { serverId, slug ->
                            val key = sessionOrchestrator.usageKeyFor(serverId, slug)
                            val b = buckets[key].orEmpty()
                            val five = b.firstOrNull { it.key == com.clauderemote.model.UsageBucket.KEY_FIVE_HOUR }?.percent ?: fiveHour[key]
                            val wk = b.firstOrNull { it.key == com.clauderemote.model.UsageBucket.KEY_SEVEN_DAY }?.percent ?: week[key]
                            five to wk
                        },
                        onOpen = open,
                        onShowInbox = { tab = VelinTab.CEKA },
                        onAccountUsage = onAccountUsage,
                    )
                    VelinTab.CEKA -> inboxList(
                        inbox = inbox, gone = gone.map { it.first }, now = now, outcomes = outcomes,
                        onAnswer = ::answer,
                        onOpen = { id -> tabsById[id]?.let(onOpenSession) },
                    )
                    VelinTab.UKOLY -> tasksList(
                        state = jarvis, now = now, expanded = expandedTasks.toSet(),
                        onToggle = { t ->
                            if (!expandedTasks.remove(t.id)) {
                                expandedTasks.add(t.id)
                                if (t.id !in jarvis.taskMail) scope.launch { jarvis.loadTaskMail(sessionOrchestrator, jarvisServer, t.id) }
                            }
                        },
                        onOpenTarget = { tmux -> openTmux(jarvisServer?.id, tmux) },
                    )
                    VelinTab.JARVIS -> {}
                }
            }
        }
        }
    }
}

private sealed interface ReplyOutcome {
    data object Sending : ReplyOutcome
    data object Sent : ReplyOutcome
    data class Refused(val why: RefuseReason) : ReplyOutcome
}

@Composable
private fun StartOfferStrip(onAnswer: (Boolean) -> Unit) {
    val c = CRTheme.colors
    CRCard(background = c.tintAccent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Otevírat aplikaci rovnou tady?", style = CRType.bodyDim, color = c.text, modifier = Modifier.weight(1f))
            TextButton(onClick = { onAnswer(true) }) { Text("Ano", color = c.accent) }
            TextButton(onClick = { onAnswer(false) }) { Text("Ne", color = c.textDim) }
        }
    }
}

// ── Přehled ────────────────────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.overview(
    rows: List<VelinRow>,
    inbox: List<InboxItem>,
    tabs: List<ClaudeSession>,
    now: Long,
    accountTags: Map<String, AccountTag>,
    usageOf: (serverId: String, slug: String?) -> Pair<Int?, Int?>,
    onOpen: (VelinRow) -> Unit,
    onShowInbox: () -> Unit,
    onAccountUsage: () -> Unit,
) {
    // One line per login in play (server + account of the open tabs): what the pollers have
    // already published, no fetch of its own (the endpoint's quota is shared, see AccountUsageScreen).
    val logins = tabs.map { it.server.id to it.accountSlug?.ifBlank { null } }.distinct()
    if (logins.isNotEmpty()) {
        item(key = "usage") {
            CRCard(modifier = Modifier.clickable(onClick = onAccountUsage)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    logins.forEach { (serverId, slug) ->
                        val (five, wk) = usageOf(serverId, slug)
                        val tag = accountTags[slug ?: com.clauderemote.model.ClaudeAccount.DEFAULT_SLUG]
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (tag != null) AccountTagChip(tag)
                            else Text(slug ?: "default", style = CRType.monoTiny, color = CRTheme.colors.textDim)
                            if (five != null) MiniBar("5h", five)
                            if (wk != null) MiniBar("7d", wk)
                            if (five == null && wk == null) Text("bez dat", style = CRType.monoTiny, color = CRTheme.colors.textDim)
                        }
                    }
                }
            }
        }
    }

    item(key = "rollup") { RollupLine(Velin.rollup(rows), waiting = inbox.size) }

    if (inbox.isNotEmpty()) {
        item(key = "waiting_header") {
            SectionHeader("Čeká na tebe · ${inbox.size}", trailing = "vše ›", onTrailing = onShowInbox)
        }
        items(inbox.take(3), key = { "w_" + it.key }) { item ->
            val glyph = if (item.kind == InboxKind.PERMISSION) VelinState.APPROVAL.glyph else VelinState.QUESTION.glyph
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onShowInbox).padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(glyph, style = CRType.mono, color = CRTheme.colors.approval)
                Text(item.title, style = CRType.cardTitle, color = CRTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                Text(item.text.ifBlank { "—" }, style = CRType.bodyDim, color = CRTheme.colors.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(Velin.formatAge(item.sinceMs, now), style = CRType.monoTiny, color = CRTheme.colors.textDim)
            }
        }
    }

    if (rows.isEmpty()) {
        item(key = "empty") {
            CRCard {
                Text("Žádné sessions. Připoj server.", style = CRType.bodyDim, color = CRTheme.colors.textDim)
            }
        }
        return
    }

    Velin.projects(rows).forEach { project ->
        item(key = "p_" + project.key) { ProjectBlock(project, now, accountTags, onOpen) }
    }
}

@Composable
private fun RollupLine(rollup: List<Pair<VelinState, Int>>, waiting: Int) {
    val c = CRTheme.colors
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (waiting == 0) Text("✓ Nic nečeká", style = CRType.monoTiny, color = c.ready)
        rollup.forEach { (state, n) ->
            Text("${state.glyph} $n ${state.label}", style = CRType.monoTiny, color = state.crStatus().color())
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null, onTrailing: (() -> Unit)? = null, onClick: (() -> Unit)? = null, extra: @Composable RowScope.() -> Unit = {}) {
    val c = CRTheme.colors
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title.uppercase(), style = CRType.sectionH, color = c.textDim)
        extra()
        Spacer(Modifier.weight(1f))
        if (trailing != null && onTrailing != null) {
            TextButton(onClick = onTrailing) { Text(trailing, style = CRType.bodyDim, color = c.textDim) }
        }
    }
}

@Composable
private fun ProjectBlock(project: VelinProject, now: Long, accountTags: Map<String, AccountTag>, onOpen: (VelinRow) -> Unit) {
    val c = CRTheme.colors
    // Collapsing is explicit only (SessionGrouping's rule): nothing folds by itself, except that a
    // project's idle rows sit behind one "• N nečinných" line until opened.
    var collapsed by remember(project.key) { mutableStateOf(false) }
    var idleOpen by remember(project.key) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionHeader(
            title = "${project.label} · ${project.rows.size}",
            onClick = { collapsed = !collapsed },
            extra = {
                project.rollup.filter { it.first != VelinState.IDLE }.forEach { (s, n) ->
                    Text("${s.glyph}$n", style = CRType.monoTiny, color = s.crStatus().color())
                }
                Spacer(Modifier.weight(1f))
                Text(if (collapsed) "▸" else "▾", style = CRType.monoTiny, color = c.textDim)
            },
        )
        if (collapsed) return@Column
        val (idle, active) = project.rows.partition { it.state == VelinState.IDLE }
        active.forEach { SessionLine(it, now, accountTags, onOpen) }
        if (idle.isNotEmpty()) {
            if (idleOpen || active.isEmpty() && idle.size == 1) {
                idle.forEach { SessionLine(it, now, accountTags, onOpen) }
            } else {
                Text(
                    "${VelinState.IDLE.glyph} … ${idle.size} nečinných ›",
                    style = CRType.monoTiny, color = c.idle,
                    modifier = Modifier.fillMaxWidth().clickable { idleOpen = true }.padding(vertical = 6.dp, horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SessionLine(row: VelinRow, now: Long, accountTags: Map<String, AccountTag>, onOpen: (VelinRow) -> Unit) {
    val c = CRTheme.colors
    val color = row.state.crStatus().color()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onOpen(row) }.padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(row.state.glyph, style = CRType.mono, color = color)
        Text(row.title, style = CRType.cardTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        row.accountKey?.let { accountTags[it] }?.let { AccountTagChip(it) }
        Text(if (row.sessionId == null) "není otevřená" else row.state.label, style = CRType.bodyDim, color = if (row.sessionId == null) c.textDim else color)
        Text(Velin.formatAge(row.sinceMs, now), style = CRType.monoTiny, color = c.textDim, modifier = Modifier.widthIn(min = 44.dp))
    }
}

// ── Čeká ───────────────────────────────────────────────────────────────────

private fun androidx.compose.foundation.lazy.LazyListScope.inboxList(
    inbox: List<InboxItem>,
    gone: List<InboxItem>,
    now: Long,
    outcomes: Map<String, ReplyOutcome>,
    onAnswer: (InboxItem, ReplyGuard.Reply, String) -> Unit,
    onOpen: (String) -> Unit,
) {
    if (inbox.isEmpty() && gone.isEmpty()) {
        item(key = "inbox_empty") {
            CRCard {
                Text("✓ Nic na tebe nečeká", style = CRType.cardTitle, color = CRTheme.colors.ready)
            }
        }
    }
    items(inbox, key = { "i_" + it.key }) { item ->
        InboxCard(item, now, outcomes[item.key], onAnswer, onOpen)
    }
    items(gone, key = { "g_" + it.key }) { item ->
        CRCard {
            Text("${item.title}: už vyřízeno jinde", style = CRType.bodyDim, color = CRTheme.colors.textDim)
        }
    }
}

@Composable
private fun InboxCard(
    item: InboxItem,
    now: Long,
    outcome: ReplyOutcome?,
    onAnswer: (InboxItem, ReplyGuard.Reply, String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val c = CRTheme.colors
    val busy = outcome == ReplyOutcome.Sending || outcome == ReplyOutcome.Sent
    CRCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = CRType.cardTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(Velin.formatAge(item.sinceMs, now), style = CRType.monoTiny, color = c.textDim)
            }
            val glyph = when (item.kind) {
                InboxKind.PERMISSION -> VelinState.APPROVAL.glyph
                InboxKind.QUESTION -> VelinState.QUESTION.glyph
                InboxKind.STOP_QUESTION -> VelinState.READY.glyph
            }
            when (item.kind) {
                InboxKind.QUESTION -> {
                    val questions = Velin.parseAsk(item.text)
                    if (questions.isEmpty()) PromptText(glyph, "Otázka (text neznámý)")
                    questions.forEach { q ->
                        PromptText(glyph, q.question)
                        if (q.options.isNotEmpty()) {
                            // The labels only, for reading: picking one means moving the selector,
                            // which no checked reply path does.
                            Text("volby: " + q.options.joinToString(" · "), style = CRType.bodyDim, color = c.textDim)
                        }
                    }
                }
                InboxKind.PERMISSION -> PromptText(glyph, item.text.ifBlank { "Povolení (text neznámý)" })
                InboxKind.STOP_QUESTION -> PromptText(glyph, item.text)
            }
            val approval = Velin.approvalMode(item)
            val risky = item.kind == InboxKind.PERMISSION && item.risk?.risky != false
            if (risky) {
                Text("⚠ RIZIKOVÉ — ${item.risk?.reason ?: "neznámý příkaz"}", style = CRType.monoTiny, color = c.approval)
            }
            when (outcome) {
                ReplyOutcome.Sending -> Text("odesílám…", style = CRType.bodyDim, color = c.textDim)
                ReplyOutcome.Sent -> Text("odesláno · čekám na potvrzení", style = CRType.bodyDim, color = c.ready)
                is ReplyOutcome.Refused -> Text(outcome.why.message, style = CRType.bodyDim, color = c.disconnected)
                null -> {}
            }
            if (item.prompt == null) {
                Text("Odpověď jen v aplikaci — dialog nepotvrdil žádný hook.", style = CRType.bodyDim, color = c.textDim)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (approval != ApprovalMode.NONE) {
                    OutlinedButton(enabled = !busy, onClick = { onAnswer(item, ReplyGuard.Reply.APPROVE, "n") }) {
                        Text(if (approval == ApprovalMode.HOLD) "Zamítnout" else "Ne")
                    }
                    when (approval) {
                        ApprovalMode.HOLD ->
                            HoldToConfirmButton("Podržet pro schválení", enabled = !busy) { onAnswer(item, ReplyGuard.Reply.APPROVE, "y") }
                        ApprovalMode.TAP ->
                            Button(enabled = !busy, onClick = { onAnswer(item, ReplyGuard.Reply.APPROVE, "y") }) { Text("Ano") }
                        ApprovalMode.NONE -> {}
                    }
                }
                TextButton(onClick = { onOpen(item.sessionId) }) { Text("Otevřít ›", color = c.accent) }
            }
            if (item.prompt != null && item.kind != InboxKind.PERMISSION) {
                TextReply(enabled = !busy) { text -> onAnswer(item, ReplyGuard.Reply.TEXT, text) }
            }
        }
    }
}

@Composable
private fun PromptText(glyph: String, text: String) {
    val c = CRTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(glyph, style = CRType.mono, color = c.approval)
        Text(text, style = CRType.mono, color = c.text, maxLines = 6, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TextReply(enabled: Boolean, onSend: (String) -> Unit) {
    val c = CRTheme.colors
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            placeholder = { Text("Odpověď pro Claude…", style = CRType.bodyDim, color = c.textDim) },
            textStyle = CRType.bodyDim.copy(color = c.text),
            singleLine = false,
            maxLines = 4,
            modifier = Modifier.weight(1f),
        )
        TextButton(enabled = enabled && text.isNotBlank(), onClick = { onSend(text.trim()); text = "" }) {
            Text("Odeslat", color = if (enabled && text.isNotBlank()) c.accent else c.textDim)
        }
    }
}

/**
 * Approve a risky permission only by holding for [holdMs] (D2): a tap, a brush or a pocket press
 * does nothing. The fill shows how far the hold got; letting go early resets it.
 */
@Composable
internal fun HoldToConfirmButton(label: String, enabled: Boolean, holdMs: Int = 1_500, onConfirmed: () -> Unit) {
    val c = CRTheme.colors
    val haptic = LocalHapticFeedback.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val confirm by rememberUpdatedState(onConfirmed)
    val fill = c.approval.copy(alpha = 0.35f)
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (enabled) c.tintOrange else c.surface2)
            .drawBehind { drawRect(fill, size = Size(size.width * progress.value, size.height)) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    val hold = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        confirm()
                        progress.snapTo(0f)
                    }
                    tryAwaitRelease()
                    if (hold.isActive) {
                        hold.cancel()
                        scope.launch { progress.animateTo(0f, tween(150)) }
                    }
                })
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = CRType.cardTitle, color = if (enabled) c.approval else c.textDim)
    }
}
