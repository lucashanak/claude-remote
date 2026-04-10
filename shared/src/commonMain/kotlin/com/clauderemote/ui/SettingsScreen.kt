package com.clauderemote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ConnectionType
import com.clauderemote.storage.AppSettings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    appVersion: String = "",
    onBack: () -> Unit,
    onCheckUpdate: (() -> Unit)? = null,
    onExportServers: (() -> Unit)? = null,
    onImportServers: (() -> Unit)? = null,
    sshKeyManager: com.clauderemote.connection.SshKeyManager? = null
) {
    var fontSize by remember { mutableStateOf(settings.terminalFontSize) }
    var scrollback by remember { mutableStateOf(settings.terminalScrollback) }
    var colorScheme by remember { mutableStateOf(settings.terminalColorScheme) }
    var defaultMode by remember { mutableStateOf(settings.defaultClaudeMode) }
    var defaultModel by remember { mutableStateOf(settings.defaultClaudeModel) }
    var defaultConnection by remember { mutableStateOf(settings.defaultConnectionType) }
    var autoReconnect by remember { mutableStateOf(settings.sshAutoReconnect) }
    var keepAlive by remember { mutableStateOf(settings.keepAliveEnabled) }
    var notifications by remember { mutableStateOf(settings.notificationsEnabled) }
    var connectTimeout by remember { mutableStateOf(settings.sshConnectTimeout) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Appearance
            Text("Appearance", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            var themeMode by remember { mutableStateOf(settings.themeMode) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("system" to "System", "dark" to "Dark", "light" to "Light").forEach { (value, label) ->
                    FilterChip(
                        selected = themeMode == value,
                        onClick = { themeMode = value; settings.themeMode = value },
                        label = { Text(label) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Terminal
            Text("Terminal", style = MaterialTheme.typography.titleMedium)

            SettingsSlider(
                label = "Font Size",
                value = fontSize,
                range = 8..32,
                onValueChange = { fontSize = it; settings.terminalFontSize = it }
            )

            SettingsSlider(
                label = "Scrollback (lines)",
                value = scrollback,
                range = 1000..50000,
                step = 1000,
                onValueChange = { scrollback = it; settings.terminalScrollback = it }
            )

            Text("Color Scheme", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            val schemes = listOf("default", "solarized-dark", "dracula", "monokai", "linux")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                schemes.forEach { scheme ->
                    FilterChip(
                        selected = colorScheme == scheme,
                        onClick = { colorScheme = scheme; settings.terminalColorScheme = scheme },
                        label = { Text(scheme) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Claude Defaults
            Text("Claude Defaults", style = MaterialTheme.typography.titleMedium)

            Text("Default Mode", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ClaudeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = defaultMode == mode,
                        onClick = { defaultMode = mode; settings.defaultClaudeMode = mode },
                        label = { Text(mode.displayName, style = MaterialTheme.typography.bodySmall) }
                    )
                }
            }

            Text("Default Model", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ClaudeModel.entries.forEach { model ->
                    FilterChip(
                        selected = defaultModel == model,
                        onClick = { defaultModel = model; settings.defaultClaudeModel = model },
                        label = { Text(model.displayName) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Connection
            Text("Connection", style = MaterialTheme.typography.titleMedium)

            Text("Default Connection Type", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionType.entries.forEach { type ->
                    FilterChip(
                        selected = defaultConnection == type,
                        onClick = { defaultConnection = type; settings.defaultConnectionType = type },
                        label = { Text(type.displayName) }
                    )
                }
            }

            SettingsSwitch(
                label = "Auto-reconnect",
                checked = autoReconnect,
                onCheckedChange = { autoReconnect = it; settings.sshAutoReconnect = it }
            )

            SettingsSwitch(
                label = "Keep alive (background)",
                checked = keepAlive,
                onCheckedChange = { keepAlive = it; settings.keepAliveEnabled = it }
            )

            SettingsSwitch(
                label = "Notify when Claude is ready",
                checked = notifications,
                onCheckedChange = { notifications = it; settings.notificationsEnabled = it }
            )

            var notifyTaskComplete by remember { mutableStateOf(settings.notifyOnTaskComplete) }
            SettingsSwitch(
                label = "Notify on task complete",
                checked = notifyTaskComplete,
                onCheckedChange = { notifyTaskComplete = it; settings.notifyOnTaskComplete = it }
            )

            SettingsSlider(
                label = "Connect timeout (seconds)",
                value = connectTimeout,
                range = 5..60,
                onValueChange = { connectTimeout = it; settings.sshConnectTimeout = it }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // SSH Keys
            if (sshKeyManager != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("SSH Keys", style = MaterialTheme.typography.titleMedium)

                var keys by remember { mutableStateOf(sshKeyManager.loadKeys()) }
                var showGenDialog by remember { mutableStateOf(false) }
                var genName by remember { mutableStateOf("") }
                var genType by remember { mutableStateOf("ed25519") }

                if (keys.isEmpty()) {
                    Text("No managed keys", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    keys.forEach { key ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(key.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${key.type.uppercase()} - ${key.fingerprint}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                sshKeyManager.deleteKey(key.id)
                                keys = sshKeyManager.loadKeys()
                            }) {
                                Icon(Icons.Default.Delete, "Delete")
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showGenDialog = true }) { Text("Generate Key") }
                }

                if (showGenDialog) {
                    AlertDialog(
                        onDismissRequest = { showGenDialog = false },
                        title = { Text("Generate SSH Key") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = genName,
                                    onValueChange = { genName = it },
                                    label = { Text("Key name") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("Key type", style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("ed25519", "rsa").forEach { type ->
                                        FilterChip(
                                            selected = genType == type,
                                            onClick = { genType = type },
                                            label = { Text(type.uppercase()) }
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (genName.isNotBlank()) {
                                        sshKeyManager.generateKey(genName, genType)
                                        keys = sshKeyManager.loadKeys()
                                        showGenDialog = false
                                        genName = ""
                                    }
                                }
                            ) { Text("Generate") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showGenDialog = false }) { Text("Cancel") }
                        }
                    )
                }
            }

            // Keyboard Shortcuts
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Keyboard Shortcuts", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ctrl+K: Command Palette  |  Ctrl+Tab: Next Tab\nCtrl+Shift+Tab: Prev Tab  |  Ctrl+W: Close Tab\nCtrl+N: New Session",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Security
            Text("Security", style = MaterialTheme.typography.titleMedium)

            var biometricLock by remember { mutableStateOf(settings.biometricLockEnabled) }
            SettingsSwitch(
                label = "Biometric lock",
                checked = biometricLock,
                onCheckedChange = { biometricLock = it; settings.biometricLockEnabled = it }
            )

            // Update check
            if (onCheckUpdate != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Updates", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onCheckUpdate) {
                    Text("Check for update")
                }
            }

            // Backup
            if (onExportServers != null || onImportServers != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Backup", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onExportServers != null) {
                        Button(onClick = onExportServers) { Text("Export Servers") }
                    }
                    if (onImportServers != null) {
                        OutlinedButton(onClick = onImportServers) { Text("Import Servers") }
                    }
                }
            }

            // Version
            if (appVersion.isNotBlank()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "Claude Remote v$appVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsSlider(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onValueChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (step > 1) ((range.last - range.first) / step - 1) else 0
        )
    }
}
