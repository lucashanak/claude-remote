package com.clauderemote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clauderemote.session.ClosedEntry
import com.clauderemote.session.RecentlyClosed
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

/**
 * What the "Nedávno zavřené" section shows. [failedServers] = servers whose `closed.jsonl` read did not come back
 * (unknown, not "nothing closed"); [loaded] = at least one read finished.
 */
internal data class RecentlyClosedUi(
    val entries: List<ClosedEntry> = emptyList(),
    val failedServers: List<String> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /** Some server was skipped (no transport, no handshake paid): the rows are not the whole story, no count. */
    val partial: Boolean = false,
)

/**
 * "Nedávno zavřené" (§3.9): one row per closed session — alias, account, when, folder, title, who closed it — and
 * one tap to restore. Collapsed by default in the Launcher and the side panel; [showEmpty] (History) says "nothing"
 * instead of disappearing. [compact] = the desktop side panel (no card, tighter rows).
 */
@Composable
internal fun RecentlyClosedSection(
    ui: RecentlyClosedUi,
    ownBy: String,
    nowEpochS: Long,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    restoring: Set<String>,
    onRestore: (ClosedEntry) -> Unit,
    onRetry: () -> Unit,
    onHistory: (() -> Unit)? = null,
    showEmpty: Boolean = false,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = CRTheme.colors
    val nothing = ui.loaded && ui.entries.isEmpty() && ui.failedServers.isEmpty() && !ui.partial
    // An empty section is not drawn at all where it is only a shortcut (§3.9 "Prázdný stav").
    if (nothing && !showEmpty) return
    if (!ui.loaded && !ui.loading && !showEmpty && onToggle != null && !expanded) {
        // not read yet (the read starts with the screen): a bare header would announce something that may be empty
        return
    }
    val count = if (ui.loaded && !ui.partial) " · ${ui.entries.size}" else ""
    val body: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth()
                    .let { if (onToggle != null) it.clickable(onClick = onToggle) else it }
                    .padding(horizontal = if (compact) 10.dp else 0.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "NEDÁVNO ZAVŘENÉ$count",
                    style = CRType.sectionH,
                    color = c.textDim,
                    modifier = Modifier.weight(1f),
                )
                if (onToggle != null) Text(if (expanded) "▾" else "▸", style = CRType.mono, color = c.textDim)
            }
            if (onToggle == null || expanded) {
                if (ui.loading && !ui.loaded) {
                    DimLine("Načítám…", compact)
                }
                if (nothing) {
                    DimLine("Nic jsi v posledních 14 dnech nezavřel.", compact)
                }
                ui.entries.forEachIndexed { i, e ->
                    if (i > 0 || !compact) HorizontalDivider(color = c.border, thickness = 1.dp)
                    ClosedRow(e, ownBy, nowEpochS, e.key in restoring, onRestore, compact)
                }
                if (ui.failedServers.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = if (compact) 10.dp else 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Nedávno zavřené nejde načíst (${ui.failedServers.joinToString(", ")})",
                            style = CRType.bodyDim,
                            color = c.disconnected,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRetry, enabled = !ui.loading) {
                            Text("Zkusit znovu", style = CRType.bodyDim, color = c.accent)
                        }
                    }
                }
                if (onHistory != null) {
                    TextButton(onClick = onHistory, modifier = Modifier.padding(start = if (compact) 4.dp else 0.dp)) {
                        Text("Starší v Historii ›", style = CRType.bodyDim, color = c.textDim)
                    }
                }
            }
        }
    }
    if (compact) {
        Column(modifier.fillMaxWidth()) { body() }
    } else {
        CRCard(modifier = modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { body() }
    }
}

@Composable
private fun DimLine(text: String, compact: Boolean) {
    Text(
        text,
        style = CRType.bodyDim,
        color = CRTheme.colors.textDim,
        modifier = Modifier.padding(horizontal = if (compact) 10.dp else 0.dp, vertical = 6.dp),
    )
}

@Composable
private fun ClosedRow(
    e: ClosedEntry,
    ownBy: String,
    nowEpochS: Long,
    restoring: Boolean,
    onRestore: (ClosedEntry) -> Unit,
    compact: Boolean,
) {
    val c = CRTheme.colors
    val r = e.record
    val resumable = RecentlyClosed.canResume(r)
    val fresh = !resumable && RecentlyClosed.canStartFresh(r)
    Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 10.dp else 0.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("↺ ", style = CRType.mono, color = c.accent)
            Text(
                RecentlyClosed.label(r, e.serverName),
                style = CRType.cardTitle,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            val account = r.account ?: "default"
            Text(" · $account · ${e.serverName}", style = CRType.monoTiny, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            RecentlyClosed.whenLabel(r.atEpochSeconds, nowEpochS)?.let {
                Spacer(Modifier.width(6.dp))
                Text(it, style = CRType.monoTiny, color = c.textDim, maxLines = 1)
            }
        }
        val second = listOfNotNull(
            RecentlyClosed.folderLeaf(r.restoreFolder),
            r.title?.let { "„$it“" } ?: if (!resumable) "transcript chybí" else null,
        ).joinToString(" · ")
        if (second.isNotEmpty()) {
            Spacer(Modifier.height(1.dp))
            Text(second, style = CRType.bodyDim, color = c.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                RecentlyClosed.closedByLine(r, ownBy),
                style = CRType.monoTiny,
                color = c.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                restoring -> Text("Obnovuji…", style = CRType.bodyDim, color = c.textDim, modifier = Modifier.padding(8.dp))
                resumable -> TextButton(onClick = { onRestore(e) }) { Text("Obnovit", style = CRType.bodyDim, color = c.accent) }
                fresh -> TextButton(onClick = { onRestore(e) }) { Text("Nová ve stejné složce", style = CRType.bodyDim, color = c.accent) }
                else -> Text("nejde obnovit", style = CRType.bodyDim, color = c.textDim, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

/**
 * A restore that needs the user: [action] for [entry] (the restore's own outcome, see [RecentlyClosed.actionFor]).
 * [entry] is null only with a [com.clauderemote.session.RestoreAction.Message] about a record that was not found
 * ("Vrátit" of a Jarvis close that can no longer be undone).
 */
internal data class RestoreOutcome(val entry: ClosedEntry?, val action: com.clauderemote.session.RestoreAction)

/**
 * The answer to a restore that did not simply open its tab. Every choice that restores under another name or another
 * conversation is a button the user presses here (§15.15 "lidský souhlas"); nothing happens on dismiss.
 * FloatingDialog, so it shows above the desktop terminal too.
 */
@Composable
internal fun RestoreOutcomeDialog(
    outcome: RestoreOutcome?,
    onDismiss: () -> Unit,
    onRestoreAs: (ClosedEntry, String) -> Unit,
    onRestoreCandidate: (ClosedEntry, String) -> Unit,
    onStartFresh: (ClosedEntry) -> Unit,
    onGoTo: (ClosedEntry, String) -> Unit,
) {
    val c = CRTheme.colors
    val a = outcome?.action
    val e = outcome?.entry
    val name = e?.let { RecentlyClosed.label(it.record, it.serverName) }.orEmpty()
    val title: String
    val text: String
    var confirm: (@Composable () -> Unit) = { TextButton(onClick = onDismiss) { Text("OK", color = c.accent) } }
    var dismiss: (@Composable () -> Unit)? = null
    when (a) {
        is com.clauderemote.session.RestoreAction.OfferName -> {
            val alias = com.clauderemote.model.TmuxNameParser.parse(a.suggestedName, e!!.serverName).alias.ifBlank { a.suggestedName }
            title = "Jméno je obsazené"
            // EXISTS comes only after the server found this conversation running nowhere: the name holds another one
            text = "Jméno „$name“ je obsazené jinou session (tahle konverzace nikde neběží). Obnovit ji jako „$alias“?"
            confirm = { TextButton(onClick = { onRestoreAs(e, a.suggestedName) }) { Text("Obnovit jako „$alias“", color = c.accent) } }
            dismiss = { TextButton(onClick = onDismiss) { Text("Zrušit", color = c.textDim) } }
        }
        is com.clauderemote.session.RestoreAction.GoTo -> {
            val where = a.tmuxName?.let { com.clauderemote.model.TmuxNameParser.parse(it, e!!.serverName).alias.ifBlank { it } }
            title = "Konverzace už běží"
            text = if (where != null) "Konverzace „$name“ už běží v session „$where“. Dva procesy nad jedním transcriptem nespouštím."
                else "Konverzace „$name“ už na serveru běží, jen nevím ve které session."
            if (a.tmuxName != null) {
                confirm = { TextButton(onClick = { onGoTo(e!!, a.tmuxName) }) { Text("Přejít na ni", color = c.accent) } }
                dismiss = { TextButton(onClick = onDismiss) { Text("Zavřít", color = c.textDim) } }
            }
        }
        is com.clauderemote.session.RestoreAction.OfferCandidate -> {
            title = "Transcript chybí"
            val fresh = RecentlyClosed.canStartFresh(e!!.record)
            text = if (a.candidate != null) {
                "Transcript konverzace „$name“ na serveru není. Ve stejné složce je konverzace ${a.candidate.take(8)}… z doby " +
                    "zavření, nejspíš její pokračování. Obnovit ji? Jinak můžu založit novou session ve stejné složce a účtu."
            } else {
                "Transcript konverzace „$name“ na serveru není." + if (fresh) " Můžu založit novou session ve stejné složce a účtu." else ""
            }
            confirm = {
                Row {
                    if (a.candidate != null) {
                        TextButton(onClick = { onRestoreCandidate(e, a.candidate) }) { Text("Obnovit kandidáta", color = c.accent) }
                    }
                    if (fresh) TextButton(onClick = { onStartFresh(e) }) { Text("Nová ve stejné složce", color = c.accent) }
                }
            }
            dismiss = { TextButton(onClick = onDismiss) { Text("Zrušit", color = c.textDim) } }
        }
        is com.clauderemote.session.RestoreAction.Message -> {
            title = a.title
            text = a.text
        }
        is com.clauderemote.session.RestoreAction.OpenTab -> {
            title = if (a.loginRequired) "Session chce přihlášení" else "Session běží"
            text = if (a.loginRequired) "„$name“ běží, ale claude se nepřihlásil. V terminálu spusť /login, nebo přihlas účet v Účtech."
                else "„$name“ na serveru běží, ale tab se nepřipojil. Najdeš ji v seznamu sessions."
        }
        null -> { title = ""; text = "" }
    }
    com.clauderemote.ui.components.FloatingDialog(
        visible = outcome != null,
        onDismiss = onDismiss,
        theme = com.clauderemote.ui.theme.CRThemeSnapshot.current(),
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = confirm,
        dismissButton = dismiss,
    )
}
