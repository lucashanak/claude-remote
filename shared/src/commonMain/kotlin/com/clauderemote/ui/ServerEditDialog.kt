package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.clauderemote.model.AuthMethod
import com.clauderemote.model.PortForward
import com.clauderemote.model.SshServer
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.Segmented
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditDialog(
    server: SshServer? = null,
    onDismiss: () -> Unit,
    onSave: (SshServer) -> Unit,
    onPickKeyFile: ((callback: (String) -> Unit) -> Unit)? = null
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics

    var name by remember { mutableStateOf(server?.name ?: "") }
    var host by remember { mutableStateOf(server?.host ?: "") }
    var port by remember { mutableStateOf(server?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(server?.username ?: "") }
    var authMethod by remember { mutableStateOf(server?.authMethod ?: AuthMethod.PASSWORD) }
    var password by remember { mutableStateOf(server?.password ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var privateKey by remember { mutableStateOf(server?.privateKey ?: "") }
    var preferMosh by remember { mutableStateOf(server?.preferMosh ?: false) }
    var defaultFolder by remember { mutableStateOf(server?.defaultFolder ?: "~") }
    var startupCommand by remember { mutableStateOf(server?.startupCommand ?: "") }
    var snippets by remember { mutableStateOf(server?.snippets ?: emptyList()) }
    var newSnippet by remember { mutableStateOf("") }
    var portForwards by remember { mutableStateOf(server?.portForwards ?: emptyList()) }
    var newPfLocal by remember { mutableStateOf("") }
    var newPfRemote by remember { mutableStateOf("") }
    var useCloudflareProxy by remember { mutableStateOf(server?.useCloudflareProxy ?: false) }
    var cloudflareToken by remember { mutableStateOf(server?.cloudflareToken ?: "") }
    var showAdvanced by remember { mutableStateOf(false) }

    val isNew = server == null
    val isValid = name.isNotBlank() && host.isNotBlank() && username.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.text,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (isNew) "Add Server" else "Edit Server", style = CRType.cardTitle)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close", tint = c.textDim)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(m.cardGap)
            ) {
                // ── Identity ────────────────────────────────────────────────
                DialogSection("Identity") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Display name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = crDialogTextFieldColors(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it },
                            label = { Text("Host") },
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                            colors = crDialogTextFieldColors(),
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Port") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = crDialogTextFieldColors(),
                        )
                    }
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = crDialogTextFieldColors(),
                    )
                }

                // ── Auth ────────────────────────────────────────────────────
                DialogSection("Authentication") {
                    Segmented(
                        options = listOf(AuthMethod.PASSWORD, AuthMethod.KEY),
                        selected = authMethod,
                        onSelect = { authMethod = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { if (it == AuthMethod.PASSWORD) "Password" else "SSH Key" }
                    )
                    when (authMethod) {
                        AuthMethod.PASSWORD -> {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (passwordVisible)
                                    VisualTransformation.None
                                else
                                    PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Text(
                                            if (passwordVisible) "Hide" else "Show",
                                            style = CRType.pill,
                                            color = c.textDim
                                        )
                                    }
                                },
                                colors = crDialogTextFieldColors(),
                            )
                        }
                        AuthMethod.KEY -> {
                            OutlinedTextField(
                                value = privateKey,
                                onValueChange = { privateKey = it },
                                label = { Text("Private key (paste or import)") },
                                modifier = Modifier.fillMaxWidth().heightIn(max = 100.dp),
                                minLines = 3,
                                maxLines = 6,
                                textStyle = CRType.mono,
                                colors = crDialogTextFieldColors(),
                            )
                            if (onPickKeyFile != null) {
                                TextButton(onClick = {
                                    onPickKeyFile { content -> privateKey = content }
                                }) {
                                    Text("Import from file", style = CRType.pill, color = c.accent)
                                }
                            }
                        }
                    }
                }

                // ── Claude defaults for server ──────────────────────────────
                DialogSection("Claude Defaults") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Default folder", style = CRType.bodyDim, color = c.textDim, modifier = Modifier.width(100.dp))
                        OutlinedTextField(
                            value = defaultFolder,
                            onValueChange = { defaultFolder = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = crDialogTextFieldColors(),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Startup cmd", style = CRType.bodyDim, color = c.textDim, modifier = Modifier.width(100.dp))
                        OutlinedTextField(
                            value = startupCommand,
                            onValueChange = { startupCommand = it },
                            placeholder = { Text("e.g. source ~/.profile") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = crDialogTextFieldColors(),
                        )
                    }
                }

                // ── Advanced (collapsible) ──────────────────────────────────
                TextButton(
                    onClick = { showAdvanced = !showAdvanced },
                    contentPadding = PaddingValues(horizontal = 0.dp)
                ) {
                    Text(
                        if (showAdvanced) "▾ Advanced" else "▸ Advanced",
                        style = CRType.sectionH,
                        color = c.textDim
                    )
                }
                if (showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Prefer Mosh
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = preferMosh,
                                onCheckedChange = { preferMosh = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = c.accent,
                                    uncheckedColor = c.border,
                                )
                            )
                            Column {
                                Text("Prefer Mosh connection", style = CRType.cardTitle, color = c.text)
                                Text("Uses Mosh instead of SSH when available", style = CRType.bodyDim, color = c.textDim)
                            }
                        }

                        // Cloudflare
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = useCloudflareProxy,
                                onCheckedChange = { useCloudflareProxy = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = c.accent,
                                    uncheckedColor = c.border,
                                )
                            )
                            Column {
                                Text("Cloudflare Tunnel", style = CRType.cardTitle, color = c.text)
                                Text("SSH over WebSocket via cloudflared", style = CRType.bodyDim, color = c.textDim)
                            }
                        }
                        if (useCloudflareProxy) {
                            OutlinedTextField(
                                value = cloudflareToken,
                                onValueChange = { cloudflareToken = it },
                                label = { Text("CF Access Token (optional)") },
                                placeholder = { Text("JWT for Zero Trust") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = crDialogTextFieldColors(),
                            )
                        }

                        // Snippets
                        Text("Snippets", style = CRType.sectionH, color = c.textDim)
                        snippets.forEachIndexed { idx, snip ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(snip, style = CRType.mono, color = c.text, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    snippets = snippets.toMutableList().also { it.removeAt(idx) }
                                }) {
                                    Text("Remove", style = CRType.pill, color = c.disconnected)
                                }
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newSnippet,
                                onValueChange = { newSnippet = it },
                                label = { Text("Command") },
                                placeholder = { Text("e.g. git pull") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                colors = crDialogTextFieldColors(),
                            )
                            TextButton(onClick = {
                                if (newSnippet.isNotBlank()) {
                                    snippets = snippets + newSnippet.trim()
                                    newSnippet = ""
                                }
                            }) {
                                Text("Add", style = CRType.pill, color = c.accent)
                            }
                        }

                        // Port forwards
                        Text("Port Forwards · ${portForwards.size}", style = CRType.sectionH, color = c.textDim)
                        portForwards.forEachIndexed { idx, pf ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "${pf.type} ${pf.localPort}:${pf.remoteHost}:${pf.remotePort}",
                                    style = CRType.mono,
                                    color = c.text,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    portForwards = portForwards.toMutableList().also { it.removeAt(idx) }
                                }) {
                                    Text("Remove", style = CRType.pill, color = c.disconnected)
                                }
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newPfLocal,
                                onValueChange = { newPfLocal = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Local port") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                colors = crDialogTextFieldColors(),
                            )
                            Text("→", style = CRType.cardTitle, color = c.textDim)
                            OutlinedTextField(
                                value = newPfRemote,
                                onValueChange = { newPfRemote = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Remote port") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                colors = crDialogTextFieldColors(),
                            )
                            TextButton(onClick = {
                                val lp = newPfLocal.toIntOrNull()
                                val rp = newPfRemote.toIntOrNull()
                                if (lp != null && rp != null) {
                                    portForwards = portForwards + PortForward(localPort = lp, remotePort = rp)
                                    newPfLocal = ""; newPfRemote = ""
                                }
                            }) {
                                Text("Add", style = CRType.pill, color = c.accent)
                            }
                        }
                    }
                }

                // ── Danger: delete ──────────────────────────────────────────
                if (!isNew) {
                    HorizontalDivider(color = c.border)
                    OutlinedButton(
                        onClick = { /* deletion handled outside via onDismiss+callback; no delete param here */ },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = c.disconnected),
                        border = androidx.compose.foundation.BorderStroke(1.dp, c.disconnected.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(m.cardRadius),
                    ) {
                        Text("Delete server", style = CRType.cardTitle, color = c.disconnected)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val saved = SshServer(
                        id = server?.id ?: Random.nextBytes(16).joinToString("") { "%02x".format(it) },
                        name = name.trim(),
                        host = host.trim(),
                        port = port.toIntOrNull() ?: 22,
                        username = username.trim(),
                        authMethod = authMethod,
                        password = if (authMethod == AuthMethod.PASSWORD) password else null,
                        privateKey = if (authMethod == AuthMethod.KEY) privateKey else null,
                        preferMosh = preferMosh,
                        defaultFolder = defaultFolder.ifBlank { "~" },
                        recentFolders = server?.recentFolders ?: emptyList(),
                        defaultClaudeMode = server?.defaultClaudeMode ?: com.clauderemote.model.ClaudeMode.NORMAL,
                        defaultClaudeModel = server?.defaultClaudeModel ?: com.clauderemote.model.ClaudeModel.DEFAULT,
                        portForwards = portForwards,
                        startupCommand = startupCommand.trim(),
                        snippets = snippets,
                        useCloudflareProxy = useCloudflareProxy,
                        cloudflareToken = cloudflareToken.trim()
                    )
                    onSave(saved)
                },
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.accent,
                    contentColor = c.accentInk,
                    disabledContainerColor = c.surface2,
                    disabledContentColor = c.textDim,
                ),
                shape = RoundedCornerShape(m.cardRadius),
            ) {
                Text("Save", style = CRType.cardTitle)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = CRType.cardTitle, color = c.textDim)
            }
        }
    )
}

// ── Local helpers ──────────────────────────────────────────────────────────

@Composable
private fun DialogSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = CRTheme.colors
    val m = CRTheme.metrics
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = CRType.sectionH, color = c.textDim)
        CRCard(content = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } })
    }
}

@Composable
private fun crDialogTextFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = CRTheme.colors.border,
    focusedBorderColor = CRTheme.colors.accent,
    cursorColor = CRTheme.colors.accent,
    unfocusedTextColor = CRTheme.colors.text,
    focusedTextColor = CRTheme.colors.text,
    unfocusedLabelColor = CRTheme.colors.textDim,
    focusedLabelColor = CRTheme.colors.accent,
    unfocusedPlaceholderColor = CRTheme.colors.textDim,
    focusedPlaceholderColor = CRTheme.colors.textDim,
    unfocusedContainerColor = CRTheme.colors.surface,
    focusedContainerColor = CRTheme.colors.surface,
)
