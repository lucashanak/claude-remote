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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ConnectionType
import com.clauderemote.session.service.CommFlags
import com.clauderemote.session.service.HookCommands
import com.clauderemote.session.service.HookInstallControl
import com.clauderemote.storage.AppSettings
import com.clauderemote.voice.WakeWordSettingsCard
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
    onTestNotification: (() -> Unit)? = null,
    sshKeyManager: com.clauderemote.connection.SshKeyManager? = null,
    appearance: AppearanceState = settings.loadAppearance(),
    onAppearanceChange: (AppearanceState) -> Unit = { settings.saveAppearance(it) },
    /**
     * Wire to apply a terminal font-size change to the live terminal (the
     * settings object is not Compose state, so writing the pref alone repaints
     * nothing). When null the slider only persists the value.
     */
    onFontSizeChange: ((Int) -> Unit)? = null,
    /** Same for the chat text scale, in percent. */
    onTranscriptFontChange: ((Int) -> Unit)? = null,
    /**
     * Whether the OS has notifications for this app switched off. On Android
     * 13+ a denied POST_NOTIFICATIONS makes `notify()` a silent no-op — no
     * exception, no alert — so the in-app toggle can read "on" while nothing
     * ever appears. Platforms that don't know (desktop) leave the default and
     * the row stays hidden.
     */
    notificationsBlocked: () -> Boolean = { false },
    /** Called after a notification switch was written (Android pushes the completion setting to the watch). */
    onNotificationSettingsChange: () -> Unit = {},
    /** (server id, display name) of the saved servers, for the per-server hook buttons. Empty hides them. */
    hookServers: List<Pair<String, String>> = emptyList(),
) {
    val c = CRTheme.colors

    var fontSize by remember { mutableStateOf(settings.terminalFontSize) }
    var chatFontPercent by remember { mutableStateOf(settings.transcriptFontPercent) }
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
                        onValueChange = {
                            fontSize = it
                            if (onFontSizeChange != null) onFontSizeChange(it)
                            else settings.terminalFontSize = it
                        }
                    )

                    // Chat text scale — separate knob because the raw terminal
                    // renders a fixed character grid while the chat reflows.
                    SettingsSlider(
                        label = "Chat font %",
                        value = chatFontPercent,
                        range = 70..160,
                        step = 10,
                        onValueChange = {
                            chatFontPercent = it
                            if (onTranscriptFontChange != null) onTranscriptFontChange(it)
                            else settings.transcriptFontPercent = it
                        }
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
                            ClaudeModel.selectable.forEach { model ->
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

                    var autoOpenTerminalOnPrompt by remember { mutableStateOf(settings.autoOpenTerminalOnPrompt) }
                    SettingsSwitch(
                        label = "Open terminal on prompts",
                        checked = autoOpenTerminalOnPrompt,
                        onCheckedChange = { autoOpenTerminalOnPrompt = it; settings.autoOpenTerminalOnPrompt = it }
                    )

                    var confirmTabClose by remember { mutableStateOf(settings.confirmTabClose) }
                    SettingsSwitch(
                        label = "Potvrzovat zavření session",
                        checked = confirmTabClose,
                        onCheckedChange = { confirmTabClose = it; settings.confirmTabClose = it }
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

            // ── Connection (experimental) ───────────────────────────────────
            // Communication-redesign switches. They feed CommFlags, which the
            // connection layer reads per server (see docs/connection.md), so
            // each one has the effect its label says; an implication that turns
            // one off is shown under it ("Effective: off (...)").
            SectionHeader("Connection (experimental)")
            CRCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    var commPool by remember { mutableStateOf(settings.commPool) }
                    var commExec by remember { mutableStateOf(settings.commExec) }
                    var commHooks by remember { mutableStateOf(settings.commHooks) }
                    var commLazy by remember { mutableStateOf(settings.commLazy) }
                    var commFg by remember { mutableStateOf(settings.commFgDeadlink) }
                    var warmTabs by remember {
                        mutableStateOf(settings.commWarmTabs.coerceIn(CommFlags.WARM_TABS_MIN, CommFlags.WARM_TABS_MAX))
                    }
                    val effective by CommFlags.deviceDefaults.collectAsState()

                    // Shows what the code would actually do when the switch is on
                    // but an implication turned the group off.
                    @Composable
                    fun EffectiveNote(group: String, on: Boolean) {
                        if (!on || effective.isOn(group)) return
                        val why = effective.notes.firstOrNull { it.startsWith("CommFlags: $group forced off because ") }
                            ?.substringAfter("because ") ?: "a dependency is off"
                        Text("Effective: off ($why)", style = CRType.monoTiny, color = CRTheme.colors.textDim)
                    }

                    SettingsSwitch(
                        label = "Pooled transports (comm_pool): tabs of a server share connections with single-flight reconnect; off = legacy lease, 3 tabs per connection",
                        checked = commPool,
                        onCheckedChange = { commPool = it; settings.commPool = it; settings.publishCommFlags() }
                    )
                    EffectiveNote(CommFlags.POOL, commPool)
                    SettingsSwitch(
                        label = "Exec channels (comm_exec): background reads run on a separate exec connection per server; off = on a tab's connection, as before",
                        checked = commExec,
                        onCheckedChange = { commExec = it; settings.commExec = it; settings.publishCommFlags() }
                    )
                    EffectiveNote(CommFlags.EXEC, commExec)
                    SettingsSwitch(
                        label = "Background hooks (comm_hooks): read hook events from servers where they are installed; off = Stop marker only",
                        checked = commHooks,
                        onCheckedChange = { commHooks = it; settings.commHooks = it; settings.publishCommFlags() }
                    )
                    EffectiveNote(CommFlags.HOOKS, commHooks)
                    SettingsSwitch(
                        label = "Lazy attach (comm_lazy) - no effect in this build",
                        checked = commLazy,
                        onCheckedChange = { commLazy = it; settings.commLazy = it; settings.publishCommFlags() }
                    )
                    EffectiveNote(CommFlags.LAZY, commLazy)
                    SettingsSwitch(
                        label = "Fast dead-link detection (comm_fg_deadlink) - no effect in this build",
                        checked = commFg,
                        onCheckedChange = { commFg = it; settings.commFgDeadlink = it; settings.publishCommFlags() }
                    )
                    EffectiveNote(CommFlags.FG_DEADLINK, commFg)
                    SettingsSlider(
                        label = "Warm tabs (comm_warm_tabs) - no effect in this build",
                        value = warmTabs,
                        range = CommFlags.WARM_TABS_MIN..CommFlags.WARM_TABS_MAX,
                        onValueChange = { warmTabs = it },
                        // Persist and publish once, when the drag ends.
                        onValueChangeFinished = { settings.commWarmTabs = warmTabs; settings.publishCommFlags() },
                    )
                    if (hookServers.isNotEmpty()) HookServersBlock(hookServers)
                }
            }

            // ── Notifications ───────────────────────────────────────────────
            SectionHeader("Notifications")
            CRCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsSwitch(
                        label = "Notifikace",
                        checked = notifications,
                        onCheckedChange = { notifications = it; settings.notificationsEnabled = it; onNotificationSettingsChange() }
                    )
                    var notifyTaskComplete by remember { mutableStateOf(settings.notifyOnTaskComplete) }
                    SettingsSwitch(
                        label = "Hlásit i dokončení práce (nejen schválení a otázky)",
                        checked = notifyTaskComplete,
                        onCheckedChange = { notifyTaskComplete = it; settings.notifyOnTaskComplete = it; onNotificationSettingsChange() }
                    )
                    if (notificationsBlocked()) {
                        Text(
                            "Notifikace jsou blokované v nastavení systému",
                            style = CRType.bodyDim,
                            // Opaque red. `tintRed` is a 15 %-alpha BACKGROUND
                            // tint elsewhere in the app and is unreadable as
                            // text on the dark surface.
                            color = c.disconnected,
                        )
                    }
                    if (onTestNotification != null) {
                        OutlinedButton(
                            onClick = onTestNotification,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Otestovat notifikaci", color = c.accent) }
                    }
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

            // ── Voice ───────────────────────────────────────────────────────
            // Mobile gets the full card: engine pickers, server config, the
            // on-device voices, the wake word. Desktop has none of those
            // choices to make — read-aloud is the OS synthesiser and dictation
            // is Soniox — so it gets its own short card with the three
            // settings its buttons actually read.
            SectionHeader("Voice")
            if (isMobile) {
                WakeWordSettingsCard(settings)
            } else {
                DesktopVoiceCard(settings)
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

// ── Desktop voice card ─────────────────────────────────────────────────────────

/**
 * The desktop half of Voice. Desktop preferences are a local file and are NOT
 * synced from the phone, so without this the Soniox key could only be set by
 * hand-editing ~/.claude-remote/settings.properties — and the mic button,
 * which hides itself while the key is blank, could never appear at all.
 *
 * Only three settings are here because only three are read on desktop:
 * read-aloud drives the OS synthesiser (`say`, `spd-say`, `espeak-ng`), which
 * needs no key, no server and no voice list — just a speed.
 */
@Composable
private fun DesktopVoiceCard(settings: AppSettings) {
    val c = CRTheme.colors
    var sonioxKey by remember { mutableStateOf(settings.sonioxApiKey) }
    var silenceSec by remember { mutableStateOf(settings.dictationSilenceMs / 1000) }
    var speechRatePct by remember { mutableStateOf(settings.ttsSpeechRatePct) }

    CRCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Read aloud uses the system speech synthesiser (say on macOS, " +
                    "spd-say or espeak-ng on Linux). Dictation streams to Soniox " +
                    "and needs an API key.",
                style = CRType.bodyDim,
                color = c.textDim,
            )
            OutlinedTextField(
                value = sonioxKey,
                onValueChange = { sonioxKey = it; settings.sonioxApiKey = it },
                label = { Text("Soniox API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "The mic button appears next to the prompt once a key is set — " +
                    "go back to a session to pick it up.",
                style = CRType.monoTiny,
                color = c.textDim.copy(alpha = 0.7f),
            )
            SettingsSlider(
                label = "Dictation silence (s)",
                value = silenceSec,
                range = 1..10,
                onValueChange = { silenceSec = it; settings.dictationSilenceMs = it * 1000 },
            )
            SettingsSlider(
                label = "Reading speed %",
                value = speechRatePct,
                range = 50..300,
                step = 10,
                onValueChange = { speechRatePct = it; settings.ttsSpeechRatePct = it },
            )
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
        val c = CRTheme.colors
        Text(label, style = CRType.bodyDim, color = c.text, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.accentInk,
                checkedTrackColor = c.accent,
                checkedBorderColor = c.accent,
                uncheckedThumbColor = c.textDim,
                uncheckedTrackColor = c.surface2,
                uncheckedBorderColor = c.border,
            ),
        )
    }
}

// ── Settings slider ────────────────────────────────────────────────────────────

@Composable
private fun SettingsSlider(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onValueChangeFinished: (() -> Unit)? = null,
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
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (step > 1) ((range.last - range.first) / step - 1) else 0,
            colors = SliderDefaults.colors(
                thumbColor = c.accent,
                activeTrackColor = c.accent,
                activeTickColor = c.accentInk,
                inactiveTrackColor = c.surface2,
                inactiveTickColor = c.border,
            ),
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


/**
 * Per-server install / remove of the background-state hooks. Nothing is written to a
 * server's Claude settings unless the user taps Install here; the dialog says what that
 * touches. Requests are one-shot events ([CommFlags.requestHooksInstall],
 * [HookInstallControl.requestRemove]): when the connection layer does not listen for
 * them, the row says so instead of pretending.
 */
@Composable
private fun HookServersBlock(servers: List<Pair<String, String>>) {
    val c = CRTheme.colors
    val statuses by HookInstallControl.shared.status.collectAsState()
    var confirmInstall by remember { mutableStateOf<Pair<String, String>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    Text("Background hooks on the server", style = CRType.monoTiny, color = c.textDim)
    Text(
        "Installing adds hook entries to the server's Claude settings. Running Claude sessions load " +
            "them within about a second (measured with Claude Code 2.1.316). Remove undoes it. To stop them instantly without touching settings, " +
            "create ~/.claude-remote/hookev.off on the server.",
        style = CRType.monoTiny, color = c.textDim,
    )
    servers.forEach { (id, name) ->
        val st = statuses[id] ?: HookInstallControl.Status()
        // Hooks that this device or the server switches off would never be read, so the install stays disabled
        val blocked = HookInstallControl.installBlockedReason(CommFlags.effectiveFlow(id).collectAsState().value)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = CRType.monoTiny, color = c.text)
            Text(hookStatusText(st), style = CRType.monoTiny, color = c.textDim)
            if (blocked != null) Text("Install disabled: $blocked", style = CRType.monoTiny, color = c.textDim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !st.busy && blocked == null, onClick = { confirmInstall = id to name }) { Text("Install hooks on server") }
                OutlinedButton(enabled = !st.busy, onClick = {
                    notice = if (HookInstallControl.shared.requestRemove(id)) null
                    else "Not available: the connection layer is not running for $name."
                }) { Text("Remove") }
            }
        }
    }
    notice?.let { Text(it, style = CRType.monoTiny, color = c.textDim) }

    confirmInstall?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { confirmInstall = null },
            title = { Text("Install hooks on $name?") },
            text = {
                Text(
                    "This edits the Claude settings on that server (a backup is kept). Every running Claude " +
                        "session of every account on it picks the hooks up within about a second (measured with Claude Code 2.1.316).",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    notice = if (CommFlags.requestHooksInstall(id)) null
                    else "Not available: the connection layer is not running for $name."
                    confirmInstall = null
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { confirmInstall = null }) { Text("Cancel", color = c.textDim) } },
        )
    }
}

private fun hookStatusText(st: HookInstallControl.Status): String {
    val base = when (st.state) {
        HookCommands.V2State.INSTALLED -> "Installed"
        HookCommands.V2State.PARTIAL -> "Partly installed (Install completes it)"
        HookCommands.V2State.ABSENT -> "Not installed"
        HookCommands.V2State.NEWER -> "A newer version is installed (left alone)"
        HookCommands.V2State.UNREADABLE -> "Server settings file is unreadable"
        HookCommands.V2State.UNKNOWN -> "Not checked yet"
    }
    val off = if (st.offSwitch) " - paused by hookev.off" else ""
    val last = when (st.lastResult) {
        HookCommands.V2Result.FAILED -> " - last attempt failed"
        HookCommands.V2Result.SKIPPED_OFF -> " - the server switches hooks off (client-flags.json)"
        else -> ""
    }
    return base + off + last + if (st.busy) " - working..." else ""
}
