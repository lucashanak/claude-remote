package com.clauderemote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeAccount
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.service.JarvisAccountsFile
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import kotlinx.coroutines.launch

/**
 * Settings → Jarvis (F5d): "Účty pro Jarvise", a multi-choice of the account slots on Jarvis' server (the default
 * `~/.claude` login with its e-mail included). Every change writes `~/.claude-remote/jarvis/accounts` at once
 * ([JarvisAccountsFile]); fewer than two chosen = the file is written empty and autopick is off, and the card says so.
 * Nothing is written before both the accounts and the file were read: a write from an unknown state could switch
 * autopick off by accident.
 */
@Composable
internal fun JarvisAccountsCard(orch: SessionOrchestrator, tabs: List<com.clauderemote.model.ClaudeSession>) {
    val c = CRTheme.colors
    val scope = rememberCoroutineScope()
    val server = remember { orch.jarvisServer(tabs, emptyList()) }
    var accounts by remember { mutableStateOf<List<ClaudeAccount>?>(null) }
    var chosen by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(server?.id, reload) {
        if (server == null) return@LaunchedEffect
        error = null
        val file = JarvisAccountsFile.parseRead(orch.jarvisRun(server, JarvisAccountsFile.READ))
        val list = orch.listClaudeAccountsQuick(server.id)
        when {
            file == null -> error = "Výběr účtů nejde načíst (server)"
            list.isEmpty() -> error = "Účty na serveru nejdou načíst"
            else -> { accounts = list; chosen = file }
        }
    }

    CRCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Účty pro Jarvise", style = CRType.bodyDim, color = c.text)
            Text(
                "Jarvis poběží na tom z vybraných účtů, který má nejvíc rezervy, a sám přejde na jiný, když mu účet dochází.",
                style = CRType.monoTiny, color = c.textDim,
            )
            when {
                server == null -> Text("Jarvis není na žádném známém serveru", style = CRType.bodyDim, color = c.textDim)
                error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(error!!, style = CRType.bodyDim, color = c.disconnected, modifier = Modifier.weight(1f))
                    TextButton(onClick = { reload++ }) { Text("Znovu", color = c.accent) }
                }
                accounts == null || chosen == null -> Text("Načítám účty…", style = CRType.bodyDim, color = c.textDim)
                else -> {
                    val sel = chosen!!
                    accounts!!.forEach { a ->
                        val slot = JarvisAccountsFile.slotOf(a)
                        val on = slot in sel
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !saving) {
                                val next = if (on) sel - slot else sel + slot
                                // in the order of the account list, so the file is stable whatever was ticked first
                                val ordered = accounts!!.map { JarvisAccountsFile.slotOf(it) }.filter { it in next }
                                chosen = ordered
                                saving = true
                                scope.launch {
                                    val ok = JarvisAccountsFile.writeOk(orch.jarvisRun(server!!, JarvisAccountsFile.writeCommand(ordered)))
                                    notice = if (ok) (if (JarvisAccountsFile.enabled(ordered)) "Uloženo" else "Uloženo – automatický výběr je vypnutý")
                                    else "Uložit nešlo – zkus to znovu"
                                    if (!ok) reload++
                                    saving = false
                                }
                            }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = null, enabled = !saving)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (a.isDefault) "${a.label} · výchozí" else a.label,
                                    style = CRType.bodyDim, color = c.text,
                                )
                                if (a.subtitle.isNotBlank()) Text(a.subtitle, style = CRType.monoTiny, color = c.textDim)
                            }
                        }
                    }
                    if (!JarvisAccountsFile.enabled(sel)) {
                        Text(JarvisAccountsFile.TOO_FEW, style = CRType.bodyDim, color = c.approval)
                    }
                    notice?.let { Text(it, style = CRType.monoTiny, color = c.textDim) }
                }
            }
        }
    }
}
