package com.clauderemote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.Pill
import com.clauderemote.ui.components.Segmented
import com.clauderemote.ui.theme.AppearanceState
import com.clauderemote.ui.theme.CRAccent
import com.clauderemote.ui.theme.CRDensity
import com.clauderemote.ui.theme.CRStatusViz
import com.clauderemote.ui.theme.CRTerminalScheme
import com.clauderemote.ui.theme.CRTerminalView
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import com.clauderemote.ui.theme.CRVariant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    appVersion: String = "",
    onBack: () -> Unit,
    onCheckUpdate: (() -> Unit)? = null,
    onExportServers: (() -> Unit)? = null,
    onImportServers: (() -> Unit)? = null,
    onViewLog: (() -> Unit)? = null,
    sshKeyManager: com.clauderemote.connection.SshKeyManager? = null,
    appearance: AppearanceState = settings.loadAppearance(),
    onAppearanceChange: (AppearanceState) -> Unit = { settings.saveAppearance(it) }
) {
    val c = CRTheme.colors

    var fontSize by remember { mutableStateOf(settings.terminalFontSize) }
    var scrollback by remember { mutableStateOf(settings.terminalScrollback) }
    var defaultMode by remember { mutableStateOf(settings.defaultClaudeMode) }
    var defaultModel by remember { mutableStateOf(settings.defaultClaudeModel) }
    var defaultConnection by remember { mutableStateOf(settings.defaultConnectionType) }
    var autoReconnect by remember { mutableStateOf(settings.sshAutoReconnect) }
    var keepAlive by remember { mutableStateOf(settings.keepAliveEnabled) }
    var notifications by remember { mutableStateOf(settings.notificationsEnabled) }
    var connectTimeout by remember { mutableStateOf(settings.sshConnectTimeout) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = CRType.cardTitle, color = c.text) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.textDim)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = c.surface,
                    scrolledContainerColor = c.surface,
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── Appearance ─────────────────────────────────────────────────
            SectionHeader("Appearance")
            CRCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                    // Theme mode
                    SettingsRow(label = "Theme") {
                        Segmented(
                            options = listOf("system", "dark", "light"),
                            selected = themeMode,
                            onSelect = { themeMode = it; settings.themeMode = it },
                            label = { it.replaceFirstChar { c -> c.uppercase() } },
                        )
                    }

                    // Variant pills
                    SettingsRow(label = "Variant") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CRVariant.entries.forEach { v ->
                                val selected = appearance.variant == v
                                Pill(
                                    text = v.name,
                                    background = if (selected) c.tintAccent else c.surface2,
                                    foreground = if (selected) c.accent else c.textDim,
                                    modifier = Modifier.clickableNoRipple { onAppearanceChange(appearance.copy(variant = v)) },
                                )
                            }
                        }
                    }

                    // Density
                    SettingsRow(label = "Density") {
                        Segmented(
                            options = CRDensity.entries.toList(),
                            selected = appearance.density,
                            onSelect = { onAppearanceChange(appearance.copy(density = it)) },
                            label = { it.name },
                        )
                    }

                    // Accent
                    SettingsRow(label = "Accent") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CRAccent.entries.forEach { a ->
                                val selected = appearance.accent == a
                                Pill(
                                    text = a.label,
                                    background = if (selected) a.color.copy(alpha = 0.25f) else c.surface2,
                                    foreground = if (selected) a.color else c.textDim,
                                    modifier = Modifier.clickableNoRipple { onAppearanceChange(appearance.copy(accent = a)) },
                                )
                            }
                        }
                    }

                    // Status viz
                    SettingsRow(label = "Status") {
                        Segmented(
                            options = CRStatusViz.entries.toList(),
                            selected = appearance.statusViz,
                            onSelect = { onAppearanceChange(appearance.copy(statusViz = it)) },
                            label = { it.name },
                        )
                    }
                }
            }

            // ── Terminal ────────────────────────────────────────────────────
            SectionHeader("Terminal")
            CRCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                    // Terminal view
                    SettingsRow(label = "View") {
                        Segmented(
                            options = CRTerminalView.entries.toList(),
                            selected = appearance.terminalView,
                            onSelect = { onAppearanceChange(appearance.copy(terminalView = it)) },
                            label = { if (it == CRTerminalView.Raw) "Raw" else "Chat" },
                        )
                    }

                    // Terminal color scheme
                    SettingsRow(label = "Scheme") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CRTerminalScheme.entries.forEach { ts ->
                                val selected = appearance.terminalScheme == ts
                                Pill(
                                    text = ts.label,
                                    background = if (selected) c.tintAccent else c.surface2,
                                    foreground = if (selected) c.accent else c.textDim,
                                    modifier = Modifier.clickableNoRipple { onAppearanceChange(appearance.copy(terminalScheme = ts)) },
                                )
                            }
                        }
                    }

                    SettingsSlider(
                        label = "Font size",
                        value = fontSize,
                        range = 8..32,
                        onValueChange = { fontSize = it; settings.terminalFontSize = it }
                    )

                    SettingsSlider(
                        label = "Scrollback",
                        value = scrollback,
                        range = 1000..50000,
                        step = 1000,
                        onValueChange = { scrollback = it; settings.terminalScrollback = it }
                    )
                }
            }

            // ── Claude Defaults ─────────────────────────────────────────────
            SectionHeader("Claude Defaults")
            CRCard {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                    SettingsRow(label = "Mode") {
                        Segmented(
                            options = ClaudeMode.entries.toList(),
                            selected = defaultMode,
                            onSelect = { defaultMode = it; settings.defaultClaudeMode = it },
                            label = { it.displayName },
                        )
                    }

                    SettingsRow(label = "Model") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ClaudeModel.entries.forEach { model ->
                                val selected = defaultModel == model
                                Pill(
                                    text = model.displayName,
                                    background = if (selected) c.tintAccent else c.surface2,
                                    foreground = if (selected) c.accent else c.textDim,
                                    modifier = Modifier.clickableNoRipple { defaultModel = model; settings.defaultClaudeModel = model },
                                )
                            }
                        }
                    }
                }
            }

            // ── SSH / Connection ────────────────────────────────────────────
            SectionHeader("SSH / Connection")
            CRCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    SettingsRow(label = "Type") {
                        Segmented(
                            options = ConnectionType.entries.toList(),
                            selected = defaultConnection,
                            onSelect = { defaultConnection = it; settings.defaultConnectionType = it },
                            label = { it.displayName },
                        )
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

                    SettingsSlider(
                        label = "Connect timeout (s)",
                        value = connectTimeout,
                        range = 5..60,
                        onValueChange = { connectTimeout = it; settings.sshConnectTimeout = it }
                    )

                    // SSH Keys
                    if (sshKeyManager != null) {
                        HorizontalDivider(color = c.border, modifier = Modifier.padding(vertical = 4.dp))
                        Text("SSH Keys", style = CRType.sectionH, color = c.textDim)

                        var keys by remember { mutableStateOf(sshKeyManager.loadKeys()) }
                        var showGenDialog by remember { mutableStateOf(false) }
                        var genName by remember { mutableStateOf("") }
                        var genType by remember { mutableStateOf("ed25519") }

                        if (keys.isEmpty()) {
                            Text("No managed keys", style = CRType.bodyDim, color = c.textDim)
                        } else {
                            keys.forEach { key ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(key.name, style = CRType.cardTitle, color = c.text)
                                        Text(
                                            "${key.type.uppercase()} · ${key.fingerprint}",
                                            style = CRType.monoTiny,
                                            color = c.textDim,
                                        )
                                    }
                                    IconButton(onClick = {
                                        sshKeyManager.deleteKey(key.id)
                                        keys = sshKeyManager.loadKeys()
                                    }) {
                                        Icon(Icons.Default.Delete, "Delete", tint = c.textDim)
                                    }
                                }
                            }
                        }

                        TextButton(onClick = { showGenDialog = true }) {
                            Text("Generate key", color = c.accent, style = CRType.bodyDim)
                        }

                        if (showGenDialog) {
                            AlertDialog(
                                onDismissRequest = { showGenDialog = false },
                                containerColor = c.surface,
                                title = { Text("Generate SSH Key", color = c.text) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedTextField(
                                            value = genName,
                                            onValueChange = { genName = it },
                                            label = { Text("Key name") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Segmented(
                                            options = listOf("ed25519", "rsa"),
                                            selected = genType,
                                            onSelect = { genType = it },
                                            label = { it.uppercase() },
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        if (genName.isNotBlank()) {
                                            sshKeyManager.generateKey(genName, genType)
                                            keys = sshKeyManager.loadKeys()
                                            showGenDialog = false
                                            genName = ""
                                        }
                                    }) { Text("Generate", color = c.accent) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showGenDialog = false }) { Text("Cancel", color = c.textDim) }
                                }
                            )
                        }
                    }
                }
            }

            // ── Notifications ───────────────────────────────────────────────
            SectionHeader("Notifications")
            CRCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                }
            }

            // ── Security ────────────────────────────────────────────────────
            SectionHeader("Security")
            CRCard {
                var biometricLock by remember { mutableStateOf(settings.biometricLockEnabled) }
                SettingsSwitch(
                    label = "Biometric lock",
                    checked = biometricLock,
                    onCheckedChange = { biometricLock = it; settings.biometricLockEnabled = it }
                )
            }

            // ── About ───────────────────────────────────────────────────────
            SectionHeader("About")
            CRCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    // Keyboard shortcuts reference
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Keyboard shortcuts", style = CRType.sectionH, color = c.textDim)
                        Spacer(Modifier.height(4.dp))
                        listOf(
                            "Ctrl+K" to "Command Palette",
                            "Ctrl+Tab" to "Next Tab",
                            "Ctrl+Shift+Tab" to "Prev Tab",
                            "Ctrl+W" to "Close Tab",
                            "Ctrl+N" to "New Session",
                        ).forEach { (key, desc) ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(key, style = CRType.mono, color = c.accent, modifier = Modifier.width(120.dp))
                                Text(desc, style = CRType.bodyDim, color = c.textDim)
                            }
                        }
                    }

                    if (onCheckUpdate != null || onExportServers != null || onImportServers != null || onViewLog != null) {
                        HorizontalDivider(color = c.border)
                    }

                    if (onViewLog != null) {
                        OutlinedButton(
                            onClick = onViewLog,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("View log", color = c.accent) }
                    }

                    if (onCheckUpdate != null) {
                        OutlinedButton(
                            onClick = onCheckUpdate,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Check for update", color = c.accent) }
                    }

                    if (onExportServers != null || onImportServers != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (onExportServers != null) {
                                OutlinedButton(onClick = onExportServers, modifier = Modifier.weight(1f)) {
                                    Text("Export servers", color = c.accent)
                                }
                            }
                            if (onImportServers != null) {
                                OutlinedButton(onClick = onImportServers, modifier = Modifier.weight(1f)) {
                                    Text("Import servers", color = c.textDim)
                                }
                            }
                        }
                    }

                    if (appVersion.isNotBlank()) {
                        Text(
                            "Claude Remote v$appVersion",
                            style = CRType.monoTiny,
                            color = c.textDim,
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Section header ─────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = CRType.sectionH,
        color = CRTheme.colors.textDim,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp),
    )
}

// ── Settings row (label + trailing control) ────────────────────────────────────

@Composable
private fun SettingsRow(
    label: String,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = CRType.bodyDim, color = CRTheme.colors.textDim, modifier = Modifier.weight(1f))
        content()
    }
}

// ── Settings switch ────────────────────────────────────────────────────────────

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
        Text(label, style = CRType.bodyDim, color = CRTheme.colors.text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ── Settings slider ────────────────────────────────────────────────────────────

@Composable
private fun SettingsSlider(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onValueChange: (Int) -> Unit
) {
    val c = CRTheme.colors
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = CRType.bodyDim, color = c.text)
            Text(value.toString(), style = CRType.mono, color = c.accent)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (step > 1) ((range.last - range.first) / step - 1) else 0
        )
    }
}

// ── Click helpers ──────────────────────────────────────────────────────────────

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
