@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.clauderemote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.RemoteSession
import com.clauderemote.model.SshServer
import com.clauderemote.session.JarvisUndo
import com.clauderemote.session.QuickAccess
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.service.AlertRouting
import com.clauderemote.session.service.JarvisAlert
import com.clauderemote.session.service.JarvisAlertKind
import com.clauderemote.session.service.JarvisData
import com.clauderemote.session.service.JarvisFeed
import com.clauderemote.session.service.JarvisMode
import com.clauderemote.session.service.JarvisProposal
import com.clauderemote.session.service.JarvisProposalKind
import com.clauderemote.session.service.JarvisTask
import com.clauderemote.session.service.JarvisWatch
import com.clauderemote.session.service.NotificationPolicy
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.Segmented
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import com.clauderemote.voice.SpeakerButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Velín's two F3 tabs: Úkoly (Jarvis' task ledger and watches) and Jarvis (mode, routing, news, proposals,
 * his conversation). Everything is read through Jarvis' own CLI on the server, one short exec per read; every
 * change is one CLI call as well — the app keeps no copy of his state beyond what the screen shows.
 */
internal class JarvisPanelState {
    var tasks by mutableStateOf<List<JarvisTask>?>(null)
    var watches by mutableStateOf<List<JarvisWatch>>(emptyList())
    var tasksError by mutableStateOf<String?>(null)
    var tasksLoading by mutableStateOf(false)
    var tasksAtMs by mutableStateOf<Long?>(null)
    /** Mail of an expanded task (`cr-task show --json`), by task id. */
    val taskMail = mutableStateMapOf<String, List<String>>()

    var proposals by mutableStateOf<List<JarvisProposal>>(emptyList())
    /** Device minus server clock at the last proposals read; null = unknown (expiry then not judged here). */
    var skewMs by mutableStateOf<Long?>(null)
    var mailOff by mutableStateOf(false)
    var mode by mutableStateOf<JarvisMode?>(null)
    var jarvisError by mutableStateOf<String?>(null)
    var jarvisLoading by mutableStateOf(false)
    /** Outcome of the last change (proposal, mode), shown under the header until the next one. */
    var notice by mutableStateOf<String?>(null)
    /** Proposal ids with a decision on its way. */
    val deciding = mutableStateListOf<String>()

    var briefing by mutableStateOf<String?>(null)
    var briefingLoading by mutableStateOf(false)

    /** Alerts shown in the Jarvis tab: those pending when it was opened plus what came since (newest first). */
    var recentAlerts by mutableStateOf<List<JarvisAlert>>(emptyList())

    suspend fun loadTasks(orch: SessionOrchestrator, server: SshServer?) {
        if (server == null) { tasksError = "Jarvis není na žádném známém serveru"; return }
        tasksLoading = true
        try {
            val list = JarvisData.parseTasks(orch.jarvisRun(server, JarvisData.TASK_LIST))
            if (list == null) {
                tasksError = "Úkoly nejdou načíst (server/Jarvis)"
            } else {
                tasks = list
                tasksError = null
                tasksAtMs = System.currentTimeMillis()
            }
            JarvisData.parseWatches(orch.jarvisRun(server, JarvisData.WATCHES))?.let { watches = it }
        } finally {
            tasksLoading = false
        }
    }

    suspend fun loadTaskMail(orch: SessionOrchestrator, server: SshServer?, id: String) {
        server ?: return
        taskMail[id] = JarvisData.parseTaskMail(orch.jarvisRun(server, JarvisData.taskShow(id)))
    }

    suspend fun loadJarvis(orch: SessionOrchestrator, server: SshServer?) {
        if (server == null) { jarvisError = "Jarvis není na žádném známém serveru"; return }
        jarvisLoading = true
        try {
            val all = orch.jarvisRun(server, JarvisData.PROPOSALS)
            val parsed = JarvisData.parseCombinedProposals(all)
            if (parsed == null) {
                jarvisError = "Návrhy nejdou načíst (server/Jarvis)"
            } else {
                proposals = parsed
                skewMs = JarvisData.skewMs(all, System.currentTimeMillis())
                mailOff = JarvisData.mailOff(JarvisData.mailHalf(all))
                jarvisError = null
            }
            orch.jarvisRun(server, JarvisData.MODE_READ)?.let { mode = JarvisData.parseMode(it) }
        } finally {
            jarvisLoading = false
        }
    }

    suspend fun decide(orch: SessionOrchestrator, server: SshServer?, p: JarvisProposal, approve: Boolean) {
        server ?: return
        if (p.id in deciding) return
        deciding.add(p.id)
        try {
            val r = JarvisData.parseRun(
                orch.jarvisRun(server, JarvisData.decideProposal(p, approve, orch.installId), totalMs = JarvisData.decideTimeoutMs(p, approve)),
            )
            notice = if (r.ok) (if (approve) JarvisData.approvedNotice(p, r.message) else "Zahozeno") else r.message
            loadJarvis(orch, server)
        } finally {
            deciding.remove(p.id)
        }
    }

    suspend fun setMode(orch: SessionOrchestrator, server: SshServer?, kind: JarvisMode.Kind, forMinutes: Int?) {
        server ?: return
        val r = JarvisData.parseRun(orch.jarvisRun(server, JarvisData.setMode(kind, forMinutes)))
        notice = if (r.ok) null else r.message
        orch.jarvisRun(server, JarvisData.MODE_READ)?.let { mode = JarvisData.parseMode(it) }
    }

    suspend fun loadBriefing(orch: SessionOrchestrator, server: SshServer?) {
        server ?: return
        briefingLoading = true
        try {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            briefing = orch.jarvisRun(server, JarvisData.briefing(hour), totalMs = 20_000)?.trim()?.ifBlank { null }
                ?: "Briefing nejde načíst"
        } finally {
            briefingLoading = false
        }
    }
}

/** `claude-server-kontexta--soniox` → `soniox` (the alias part of the tmux name; the whole name when it has none). */
internal fun shortName(tmux: String): String = tmux.substringAfterLast("--").ifBlank { tmux }

private fun taskGlyph(status: String): String = when (status) {
    "čeká na tebe" -> "✎"
    "ke kontrole" -> "✓?"
    "běží" -> "▶"
    "zadáno" -> "→"
    "hotovo" -> "✓"
    "selhalo" -> "✕"
    else -> "•"
}

// ── Úkoly ──────────────────────────────────────────────────────────────────────────────────────────────────────

internal fun LazyListScope.tasksList(
    state: JarvisPanelState,
    now: Long,
    expanded: Set<String>,
    onToggle: (JarvisTask) -> Unit,
    onOpenTarget: (String) -> Unit,
) {
    val tasks = state.tasks
    state.tasksError?.let { err ->
        item(key = "tasks_err") {
            CRCard {
                Text(
                    err + (state.tasksAtMs?.let { " · data z ${Velin.formatAge(it, now)} zpět" } ?: ""),
                    style = CRType.bodyDim, color = CRTheme.colors.disconnected,
                )
            }
        }
    }
    if (tasks == null) {
        if (state.tasksError == null) item(key = "tasks_loading") {
            Text("načítám…", style = CRType.bodyDim, color = CRTheme.colors.textDim)
        }
        return
    }
    if (tasks.isEmpty()) {
        item(key = "tasks_empty") {
            CRCard {
                Text("Zatím žádné úkoly. Zadej první Jarvisovi: „Jarvisi, ať někdo…“", style = CRType.bodyDim, color = CRTheme.colors.textDim)
            }
        }
    }
    JarvisData.groupTasks(tasks).forEach { (status, group) ->
        item(key = "tg_$status") {
            Text("${status.uppercase()} · ${group.size}", style = CRType.sectionH, color = CRTheme.colors.textDim, modifier = Modifier.padding(top = 8.dp))
        }
        items(group, key = { "t_" + it.id }) { t ->
            TaskRow(t, now, t.id in expanded, state.taskMail[t.id], onToggle, onOpenTarget)
        }
    }
    val open = state.watches.filter { it.state == "open" }
    if (open.isNotEmpty()) {
        item(key = "watches_h") {
            Text("HLÍDKY · ${open.size}", style = CRType.sectionH, color = CRTheme.colors.textDim, modifier = Modifier.padding(top = 8.dp))
        }
        items(open, key = { "w_" + it.id }) { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("◷", style = CRType.mono, color = CRTheme.colors.working)
                Text(w.what, style = CRType.bodyDim, color = CRTheme.colors.text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(Velin.formatAge(w.createdMs, now), style = CRType.monoTiny, color = CRTheme.colors.textDim)
            }
        }
    }
}

@Composable
private fun TaskRow(
    t: JarvisTask,
    now: Long,
    expanded: Boolean,
    mail: List<String>?,
    onToggle: (JarvisTask) -> Unit,
    onOpenTarget: (String) -> Unit,
) {
    val c = CRTheme.colors
    CRCard(modifier = Modifier.clickable { onToggle(t) }) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(taskGlyph(t.status), style = CRType.mono, color = if (t.status == "čeká na tebe") c.approval else c.textDim)
                Text(t.title, style = CRType.cardTitle, color = c.text, maxLines = if (expanded) 6 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(Velin.formatAge(t.createdMs, now), style = CRType.monoTiny, color = c.textDim)
            }
            val line = listOfNotNull(t.to?.let { "→ ${shortName(it)}" }, t.statusDetail).joinToString(" · ")
            if (line.isNotBlank()) Text(line, style = CRType.bodyDim, color = c.textDim, maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis)
            if (expanded) {
                Text("${t.id} · ${t.status}" + (t.by?.let { " · zadal $it" } ?: ""), style = CRType.monoTiny, color = c.textDim)
                t.ac?.let { Text("HOTOVO KDYŽ  $it", style = CRType.bodyDim, color = c.text) }
                t.note?.let { Text("POZNÁMKA  $it", style = CRType.bodyDim, color = c.text) }
                t.closedMs?.let { Text("uzavřeno před ${Velin.formatAge(it, now)}", style = CRType.monoTiny, color = c.textDim) }
                when {
                    mail == null -> Text("pošta: načítám…", style = CRType.monoTiny, color = c.textDim)
                    mail.isNotEmpty() -> Text("pošta: " + mail.joinToString(", "), style = CRType.monoTiny, color = c.textDim)
                }
                t.to?.let { to ->
                    TextButton(onClick = { onOpenTarget(to) }) { Text("Otevřít ${shortName(to)} ›", color = c.accent) }
                }
            }
        }
    }
}

// ── Jarvis ─────────────────────────────────────────────────────────────────────────────────────────────────────

/** "do kdy" of Nerušit: an hour from now, until the morning, until cancelled. */
private enum class DndSpan(val label: String) { HOUR("1 h"), MORNING("do 7:00"), FOREVER("dokud nezruším") }

/** As a duration from now, computed on THIS device's clock and zone (the server reads clock times in UTC). */
private fun dndForMinutes(span: DndSpan): Int? = when (span) {
    DndSpan.HOUR -> 60
    DndSpan.MORNING -> {
        val cal = java.util.Calendar.getInstance()
        JarvisData.minutesUntil(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), 7)
    }
    DndSpan.FOREVER -> null
}

@Composable
internal fun JarvisPane(
    state: JarvisPanelState,
    feed: JarvisFeed,
    routing: AlertRouting,
    onRouting: (AlertRouting) -> Unit,
    server: SshServer?,
    orch: SessionOrchestrator,
    scope: CoroutineScope,
    jarvisTab: ClaudeSession?,
    now: Long,
    onOpenTmux: (serverId: String, tmux: String) -> Unit,
    onOpenJarvis: (() -> Unit)?,
    onUndoClose: (JarvisAlert) -> Unit,
    /** Undos under way ([com.clauderemote.session.JarvisUndoInFlight.state]): their button is off. */
    undoing: Set<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val c = CRTheme.colors
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = if (jarvisTab != null) 380.dp else 2000.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CRCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("JARVIS", style = CRType.cardTitle, color = c.text)
                        Text(
                            if (feed.alive) "● žije" else "○ neodpovídá",
                            style = CRType.monoTiny, color = if (feed.alive) c.working else c.disconnected,
                        )
                        if (state.mailOff) Text("pošta vypnutá", style = CRType.monoTiny, color = c.approval)
                        Spacer(Modifier.weight(1f))
                        if (onOpenJarvis != null) TextButton(onClick = onOpenJarvis) { Text("Terminál ›", color = c.accent) }
                    }
                    if (NotificationPolicy.jarvisFailedOpen(routing, feed.alive)) {
                        Text("Jarvis neodpovídá – upozornění jdou zase přímo ze sessions", style = CRType.bodyDim, color = c.approval)
                    }
                    Text("Rušit", style = CRType.monoTiny, color = c.textDim)
                    ModeSelector(state.mode?.let { JarvisData.effectiveMode(it, now) }, now) { kind, until ->
                        scope.launch { state.setMode(orch, server, kind, until) }
                    }
                    Text("Upozornění na tomto zařízení", style = CRType.monoTiny, color = c.textDim)
                    Segmented(
                        options = AlertRouting.entries.toList(),
                        selected = routing,
                        onSelect = onRouting,
                        label = { if (it == AlertRouting.PER_SESSION) "Každá session" else "Přes Jarvise" },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !state.briefingLoading && server != null, onClick = { scope.launch { state.loadBriefing(orch, server) } }) {
                            Text(if (state.briefingLoading) "Briefing…" else "Briefing")
                        }
                        state.briefing?.let { SpeakerButton(it, tint = c.accent) }
                    }
                    state.briefing?.let { Text(it, style = CRType.bodyDim, color = c.text) }
                    state.notice?.let { Text(it, style = CRType.bodyDim, color = c.textDim) }
                    state.jarvisError?.let { Text(it, style = CRType.bodyDim, color = c.disconnected) }
                }
            }
            state.proposals.forEach { p ->
                ProposalCard(p, now, state.skewMs, busy = p.id in state.deciding) { approve -> scope.launch { state.decide(orch, server, p, approve) } }
            }
            if (state.recentAlerts.isNotEmpty()) {
                Text("NOVINKY · ${state.recentAlerts.size}", style = CRType.sectionH, color = c.textDim)
                state.recentAlerts.take(12).forEach { a -> AlertLine(a, now, onOpenTmux, onUndoClose, undoing) }
            }
        }
        if (jarvisTab != null) {
            // Once per tab, not per recomposition: transcriptFlow nudges a poll each time it is called.
            val flow = remember(jarvisTab.id) { orch.transcriptFlow(jarvisTab.id) }
            val entries by flow.collectAsState()
            TranscriptView(entries = entries, modifier = Modifier.fillMaxWidth().weight(1f), claudeSessionId = jarvisTab.claudeSessionId)
        } else {
            CRCard {
                Text(
                    "Jarvisova konverzace se ukáže tady, až bude jeho session otevřená v aplikaci.",
                    style = CRType.bodyDim, color = c.textDim,
                )
            }
        }
    }
}

@Composable
private fun ModeSelector(mode: JarvisMode?, now: Long, onSet: (JarvisMode.Kind, Int?) -> Unit) {
    val c = CRTheme.colors
    var menu by remember { mutableStateOf(false) }
    Column {
        Segmented(
            options = JarvisMode.Kind.entries.toList(),
            selected = mode?.mode ?: JarvisMode.Kind.NORMAL,
            onSelect = { k -> if (k == JarvisMode.Kind.DND) menu = true else onSet(k, null) },
            label = { it.label },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DndSpan.entries.forEach { span ->
                DropdownMenuItem(text = { Text("Nerušit ${span.label}") }, onClick = { menu = false; onSet(JarvisMode.Kind.DND, dndForMinutes(span)) })
            }
        }
        if (mode?.mode == JarvisMode.Kind.DND && mode.untilMs != null) {
            Text("nerušit ještě ${Velin.formatAge(now, mode.untilMs).ifBlank { "chvíli" }}", style = CRType.monoTiny, color = c.textDim)
        }
        if (mode == null) Text("režim neznámý", style = CRType.monoTiny, color = c.textDim)
    }
}

@Composable
private fun ProposalCard(p: JarvisProposal, now: Long, skewMs: Long?, busy: Boolean, onDecide: (Boolean) -> Unit) {
    val c = CRTheme.colors
    CRCard(background = c.tintAccent) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(JarvisData.heading(p), style = CRType.cardTitle, color = c.text, modifier = Modifier.weight(1f))
                Text(Velin.formatAge(p.createdMs, now), style = CRType.monoTiny, color = c.textDim)
            }
            // the CLI's own sentence ("Zavřít soniox: hotovo, čistý strom.") above the facts it rests on
            p.title?.takeIf { p.kind != JarvisProposalKind.MAIL }?.let { Text(it, style = CRType.bodyDim, color = c.text) }
            // "Poslat" sends the WHOLE message: it unlocks only once the whole text was on screen (rev-f3-app MED6).
            var expanded by remember(p.id) { mutableStateOf(false) }
            var overflows by remember(p.id) { mutableStateOf(true) }
            Text(
                p.text.ifBlank { "(text neznámý)" }, style = CRType.mono, color = c.text,
                maxLines = if (expanded) Int.MAX_VALUE else 8, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            )
            if (overflows && !expanded) {
                TextButton(onClick = { expanded = true }) { Text("Zobrazit celé", color = c.accent) }
            }
            p.task?.let { Text("úkol $it", style = CRType.monoTiny, color = c.textDim) }
            if (!p.fullText) Text("Celé znění se nenačetlo – schválit jde jen u Jarvise.", style = CRType.bodyDim, color = c.textDim)
            // the server's clock decides (LOW-2): without its time the expiry is not judged here, the server refuses
            val judgeAt = if (skewMs != null) now else null
            val expired = JarvisData.expired(p, judgeAt, skewMs ?: 0)
            if (skewMs != null) p.expiresMs?.let { exp ->
                Text(
                    if (expired) "návrh propadl – Jarvis se musí zeptat znovu" else "propadne za ${Velin.formatAge(now - skewMs, exp).ifBlank { "chvíli" }}",
                    style = CRType.monoTiny, color = if (expired) c.approval else c.textDim,
                )
            }
            val canSend = JarvisData.canApprove(p, seenWhole = expanded || !overflows, nowMs = judgeAt, skewMs = skewMs ?: 0)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { onDecide(false) }) { Text("Zahodit") }
                Button(enabled = !busy && canSend, onClick = { onDecide(true) }) { Text(if (busy) "…" else JarvisData.approveLabel(p)) }
            }
        }
    }
}

@Composable
private fun AlertLine(
    a: JarvisAlert,
    now: Long,
    onOpenTmux: (String, String) -> Unit,
    onUndoClose: (JarvisAlert) -> Unit,
    undoing: Set<Pair<String, String>>,
) {
    val c = CRTheme.colors
    val (glyph, color) = when (a.kind) {
        JarvisAlertKind.BLOCK -> "▲" to c.approval
        JarvisAlertKind.INCIDENT -> "⚠" to c.disconnected
        JarvisAlertKind.DONE -> "✓" to c.ready
        JarvisAlertKind.INFO -> "•" to c.textDim
    }
    Row(
        Modifier.fillMaxWidth().let { m -> a.tmux?.let { t -> m.clickable { onOpenTmux(a.serverId, t) } } ?: m }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(glyph, style = CRType.mono, color = color)
        Text(a.text, style = CRType.bodyDim, color = c.text, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(Velin.formatAge(a.atMs, now), style = CRType.monoTiny, color = c.textDim)
        // a closed session has nothing to open: its line offers the undo instead (closed.jsonl decides if it still can)
        val undo = JarvisUndo.undoButtonEnabled(a, undoing)
        if (undo != null) {
            TextButton(enabled = undo, onClick = { onUndoClose(a) }) {
                Text(if (undo) "Vrátit" else "Vracím…", color = if (undo) c.accent else c.textDim)
            }
        } else if (a.tmux != null) {
            Text("›", style = CRType.mono, color = c.accent)
        }
    }
}

/** A tab of [tmux] on [serverId], else the scanned remote session of it: what "Otevřít" of an alert or a task opens. */
internal fun openTmuxTarget(
    tabs: List<ClaudeSession>,
    remotes: List<RemoteSession>,
    serverId: String?,
    tmux: String,
    onOpenSession: (ClaudeSession) -> Unit,
    onAttachRemote: (RemoteSession) -> Unit,
): Boolean {
    tabs.firstOrNull { it.tmuxSessionName == tmux && (serverId == null || it.server.id == serverId) }?.let { onOpenSession(it); return true }
    remotes.firstOrNull { it.tmuxSession.name == tmux && (serverId == null || it.server.id == serverId) }?.let { onAttachRemote(it); return true }
    return false
}

/** The tab showing Jarvis on this device, if any (his conversation in the Jarvis tab needs one). */
internal fun jarvisTabOf(tabs: List<ClaudeSession>, serverId: String?): ClaudeSession? =
    tabs.filter { it.tmuxSessionName == QuickAccess.JARVIS_TMUX_NAME }
        .sortedByDescending { it.server.id == serverId }
        .firstOrNull()
