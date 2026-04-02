package com.clauderemote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clauderemote.model.AuthMethod
import com.clauderemote.model.PortForward
import com.clauderemote.model.SshServer
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditDialog(
    server: SshServer? = null,
    onDismiss: () -> Unit,
    onSave: (SshServer) -> Unit,
    onPickKeyFile: ((callback: (String) -> Unit) -> Unit)? = null
) {
    var name by remember { mutableStateOf(server?.name ?: "") }
    var host by remember { mutableStateOf(server?.host ?: "") }
    var port by remember { mutableStateOf(server?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(server?.username ?: "") }
    var authMethod by remember { mutableStateOf(server?.authMethod ?: AuthMethod.PASSWORD) }
    var password by remember { mutableStateOf(server?.password ?: "") }
    var privateKey by remember { mutableStateOf(server?.privateKey ?: "") }
    var preferMosh by remember { mutableStateOf(server?.preferMosh ?: false) }
    var defaultFolder by remember { mutableStateOf(server?.defaultFolder ?: "~") }
    var startupCommand by remember { mutableStateOf(server?.startupCommand ?: "") }
    var snippets by remember { mutableStateOf(server?.snippets ?: emptyList()) }
    var newSnippet by remember { mutableStateOf("") }
    var portForwards by remember { mutableStateOf(server?.portForwards ?: emptyList()) }
    var newPfLocal by remember { mutableStateOf("") }
    var newPfRemote by remember { mutableStateOf("") }

    val isNew = server == null
    val isValid = name.isNotBlank() && host.isNotBlank() && username.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add Server" else "Edit Server") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() } },
                        label = { Text("Port") },
                        modifier = Modifier.width(80.dp),
                        singleLine = true
                    )
                }

                // Auth method
                Text("Authentication", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = authMethod == AuthMethod.PASSWORD,
                        onClick = { authMethod = AuthMethod.PASSWORD },
                        label = { Text("Password") }
                    )
                    FilterChip(
                        selected = authMethod == AuthMethod.KEY,
                        onClick = { authMethod = AuthMethod.KEY },
                        label = { Text("SSH Key") }
                    )
                }

                var passwordVisible by remember { mutableStateOf(false) }
                when (authMethod) {
                    AuthMethod.PASSWORD -> {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (passwordVisible)
                                androidx.compose.ui.text.input.VisualTransformation.None
                            else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Text(if (passwordVisible) "Hide" else "Show",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        )
                    }
                    AuthMethod.KEY -> {
                        OutlinedTextField(
                            value = privateKey,
                            onValueChange = { privateKey = it },
                            label = { Text("Private Key (paste or import)") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 5
                        )
                        if (onPickKeyFile != null) {
                            TextButton(onClick = {
                                onPickKeyFile { content -> privateKey = content }
                            }) {
                                Text("Import from file")
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = defaultFolder,
                    onValueChange = { defaultFolder = it },
                    label = { Text("Default Folder") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = startupCommand,
                    onValueChange = { startupCommand = it },
                    label = { Text("Startup Command (optional)") },
                    placeholder = { Text("e.g. source ~/.profile") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Snippets (quick commands shown in terminal)
                Text("Snippets", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp))
                snippets.forEachIndexed { idx, snip ->
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(snip, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            snippets = snippets.toMutableList().also { it.removeAt(idx) }
                        }) { Text("X") }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newSnippet,
                        onValueChange = { newSnippet = it },
                        label = { Text("Command") },
                        placeholder = { Text("e.g. git pull") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    TextButton(onClick = {
                        if (newSnippet.isNotBlank()) {
                            snippets = snippets + newSnippet.trim()
                            newSnippet = ""
                        }
                    }) { Text("Add") }
                }

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(
                        checked = preferMosh,
                        onCheckedChange = { preferMosh = it }
                    )
                    Text("Prefer Mosh connection")
                }

                // Port forwarding
                Text("Port Forwards", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp))
                portForwards.forEachIndexed { idx, pf ->
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "${pf.type} ${pf.localPort}:${pf.remoteHost}:${pf.remotePort}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            portForwards = portForwards.toMutableList().also { it.removeAt(idx) }
                        }) { Text("X") }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newPfLocal,
                        onValueChange = { newPfLocal = it.filter { c -> c.isDigit() } },
                        label = { Text("Local") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Text(":", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = newPfRemote,
                        onValueChange = { newPfRemote = it.filter { c -> c.isDigit() } },
                        label = { Text("Remote") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    TextButton(onClick = {
                        val lp = newPfLocal.toIntOrNull()
                        val rp = newPfRemote.toIntOrNull()
                        if (lp != null && rp != null) {
                            portForwards = portForwards + PortForward(localPort = lp, remotePort = rp)
                            newPfLocal = ""; newPfRemote = ""
                        }
                    }) { Text("Add") }
                }
            }
        },
        confirmButton = {
            TextButton(
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
                        snippets = snippets
                    )
                    onSave(saved)
                },
                enabled = isValid
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
