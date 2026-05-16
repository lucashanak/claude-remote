package com.clauderemote.ui

import com.clauderemote.model.TmuxNameParser
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.clauderemote.model.*
import com.clauderemote.storage.AppSettings
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.Segmented
import com.clauderemote.ui.components.ServerGlyph
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectScreen(
    server: SshServer,
    tmuxSessions: List<TmuxSession>,
    appSettings: AppSettings,
    onBack: () -> Unit,
    onKillTmux: ((String) -> Unit)? = null,
    onBrowseFolders: (suspend (String) -> List<String>)? = null,
    onLaunch: (folder: String, mode: ClaudeMode, model: ClaudeModel, connectionType: ConnectionType, tmuxSession: String, isNewTmuxSession: Boolean) -> Unit
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics

    var folder by remember { mutableStateOf(server.defaultFolder) }
    var selectedMode by remember { mutableStateOf(appSettings.defaultClaudeMode) }
    var selectedModel by remember { mutableStateOf(appSettings.defaultClaudeModel) }
    var connectionType by remember { mutableStateOf(ConnectionType.SSH) }
    var sessionAlias by remember { mutableStateOf("") }
    var tmuxSessionName by remember {
        mutableStateOf(TmuxNameParser.build(server.name, server.defaultFolder, appSettings.defaultClaudeMode == ClaudeMode.YOLO))
    }
    var useExistingTmux by remember { mutableStateOf(false) }
    var browseFolders by remember { mutableStateOf<List<String>>(emptyList()) }
    var browseLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(folder, selectedMode, sessionAlias) {
        if (!useExistingTmux) {
            tmuxSessionName = TmuxNameParser.build(
                server.name, folder, selectedMode == ClaudeMode.YOLO, sessionAlias
            )
        }
    }

    val launch: () -> Unit = {
        onLaunch(folder, selectedMode, selectedModel, connectionType, tmuxSessionName, !useExistingTmux)
    }
    val launchKeyboardOptions = KeyboardOptions(imeAction = ImeAction.Go)
    val launchKeyboardActions = KeyboardActions(onGo = { launch() })

    // Build will-run preview command
    val willRunPreview = buildString {
        val attachFlag = if (useExistingTmux) "-t" else "new -A -s"
        append("$ tmux $attachFlag '${tmuxSessionName}'\n")
        append("$ cd $folder && claude")
        if (selectedModel != ClaudeModel.DEFAULT) append(" --model ${selectedModel.cliValue}")
        selectedMode.cliFlag?.let { append(" $it") }
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
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ServerGlyph(server.name, modifier = Modifier.size(26.dp))
                        Text(server.name, style = CRType.cardTitle)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.textDim)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = m.sectionPad, vertical = m.sectionTopGap),
            verticalArrangement = Arrangement.spacedBy(m.cardGap)
        ) {
            // ── Folder ─────────────────────────────────────────────────────
            SectionLabel("Folder", c.textDim)
            CRCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    var browseOpen by remember { mutableStateOf(false) }
                    // Subdirs minus hidden entries (.git, .claude, .venv, …).
                    val visibleSubdirs = remember(browseFolders) {
                        browseFolders.filterNot { it.substringAfterLast('/').startsWith(".") }
                    }
                    Box {
                        OutlinedTextField(
                            value = folder,
                            onValueChange = { folder = it },
                            label = { Text("Remote path") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = launchKeyboardOptions,
                            keyboardActions = launchKeyboardActions,
                            colors = crTextFieldColors(),
                            trailingIcon = if (onBrowseFolders != null) {{
                                IconButton(
                                    onClick = {
                                        browseOpen = !browseOpen
                                        if (browseOpen && browseFolders.isEmpty() && !browseLoading) {
                                            browseLoading = true
                                            scope.launch {
                                                browseFolders = onBrowseFolders.invoke(folder)
                                                browseLoading = false
                                            }
                                        }
                                    }
                                ) {
                                    if (browseLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = c.accent
                                        )
                                    } else {
                                        Text(
                                            if (browseOpen) "▾" else "▸",
                                            color = c.textDim,
                                            style = CRType.cardTitle
                                        )
                                    }
                                }
                            }} else null
                        )
                        if (onBrowseFolders != null) {
                            DropdownMenu(
                                expanded = browseOpen,
                                onDismissRequest = { browseOpen = false },
                                modifier = Modifier
                                    .background(c.surface2)
                                    .heightIn(max = 340.dp)
                                    .widthIn(min = 220.dp)
                            ) {
                                DropdownMenuItem(
                                    enabled = false,
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                folder.ifBlank { "~" },
                                                style = CRType.sectionH,
                                                color = c.textDim,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TextButton(
                                                onClick = {
                                                    browseLoading = true
                                                    scope.launch {
                                                        browseFolders = onBrowseFolders.invoke(folder)
                                                        browseLoading = false
                                                    }
                                                }
                                            ) {
                                                Text("↻", color = c.accent, style = CRType.pill)
                                            }
                                        }
                                    },
                                    onClick = {}
                                )
                                HorizontalDivider(color = c.outline.copy(alpha = 0.4f))
                                if (browseLoading && visibleSubdirs.isEmpty()) {
                                    DropdownMenuItem(
                                        enabled = false,
                                        text = { Text("Loading…", style = CRType.pill, color = c.textDim) },
                                        onClick = {}
                                    )
                                } else if (visibleSubdirs.isEmpty()) {
                                    DropdownMenuItem(
                                        enabled = false,
                                        text = { Text("(no visible subdirs)", style = CRType.pill, color = c.textDim) },
                                        onClick = {}
                                    )
                                } else {
                                    visibleSubdirs.forEach { sub ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    sub.substringAfterLast('/').ifBlank { sub },
                                                    style = CRType.pill,
                                                    color = c.text
                                                )
                                            },
                                            onClick = {
                                                folder = sub
                                                browseOpen = false
                                                browseLoading = true
                                                scope.launch {
                                                    browseFolders = onBrowseFolders.invoke(sub)
                                                    browseLoading = false
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Recent folders as quick-jump chips under the field.
                    val recents = server.recentFolders.take(6)
                    if (recents.isNotEmpty()) {
                        Text("Recent", style = CRType.sectionH, color = c.textDim)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            maxItemsInEachRow = 3
                        ) {
                            recents.forEach { recent ->
                                FolderChip(
                                    label = recent.substringAfterLast('/').ifBlank { recent },
                                    onClick = { folder = recent }
                                )
                            }
                        }
                    }
                }
            }

            // ── Claude options ─────────────────────────────────────────────
            SectionLabel("Claude Options", c.textDim)
            CRCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabeledRow("Mode") {
                        Segmented(
                            options = ClaudeMode.entries,
                            selected = selectedMode,
                            onSelect = { selectedMode = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { it.displayName }
                        )
                    }
                    LabeledRow("Model") {
                        Segmented(
                            options = ClaudeModel.entries,
                            selected = selectedModel,
                            onSelect = { selectedModel = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { it.displayName }
                        )
                    }
                    LabeledRow("Connection") {
                        Segmented(
                            options = if (server.useCloudflareProxy)
                                listOf(ConnectionType.SSH)
                            else
                                listOf(ConnectionType.SSH, ConnectionType.MOSH),
                            selected = connectionType,
                            onSelect = { connectionType = it },
                            label = { it.displayName }
                        )
                    }
                    LabeledRow("Alias") {
                        OutlinedTextField(
                            value = sessionAlias,
                            onValueChange = { sessionAlias = it },
                            placeholder = { Text("e.g. bugfix, refactor…") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = launchKeyboardOptions,
                            keyboardActions = launchKeyboardActions,
                            colors = crTextFieldColors(),
                        )
                    }
                }
            }

            // ── Tmux session ────────────────────────────────────────────────
            SectionLabel("Tmux · ${tmuxSessions.size} on server", c.textDim)
            CRCard {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TmuxRadioRow(
                        selected = !useExistingTmux,
                        label = "New session",
                        onClick = { useExistingTmux = false }
                    )
                    if (!useExistingTmux) {
                        Text(
                            tmuxSessionName,
                            style = CRType.mono,
                            color = c.textDim,
                            modifier = Modifier.padding(start = 32.dp, bottom = 4.dp)
                        )
                    }
                    tmuxSessions.forEach { tmux ->
                        TmuxRadioRow(
                            selected = useExistingTmux && tmuxSessionName == tmux.name,
                            label = "${tmux.name} (${tmux.windows}w)",
                            attached = tmux.attached,
                            onClick = {
                                useExistingTmux = true
                                tmuxSessionName = tmux.name
                            },
                            onKill = if (onKillTmux != null) ({ onKillTmux(tmux.name) }) else null
                        )
                    }
                }
            }

            // ── Will-run preview ────────────────────────────────────────────
            SectionLabel("Will run", c.textDim)
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface2, RoundedCornerShape(m.cardRadius))
                    .border(1.dp, c.border, RoundedCornerShape(m.cardRadius))
                    .padding(12.dp)
            ) {
                Text(willRunPreview, style = CRType.mono, color = c.textDim)
            }

            // ── Launch ──────────────────────────────────────────────────────
            Button(
                onClick = launch,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(m.rowHeight),
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.accent,
                    contentColor = c.accentInk,
                ),
                shape = RoundedCornerShape(m.cardRadius),
            ) {
                Text("▶  Launch Claude", style = CRType.cardTitle)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Local helpers ──────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text.uppercase(),
        style = CRType.sectionH,
        color = color,
        modifier = Modifier.padding(horizontal = 2.dp)
    )
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    val c = CRTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = CRType.bodyDim, color = c.textDim)
        content()
    }
}

@Composable
private fun FolderChip(label: String, onClick: () -> Unit) {
    val c = CRTheme.colors
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .background(c.surface2, shape)
            .border(1.dp, c.border, shape)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .then(Modifier.then(
                androidx.compose.ui.Modifier
                    .then(Modifier)
            ))
    ) {
        TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
            Text(label, style = CRType.mono, color = c.text)
        }
    }
}

@Composable
private fun TmuxRadioRow(
    selected: Boolean,
    label: String,
    attached: Boolean = false,
    onClick: () -> Unit,
    onKill: (() -> Unit)? = null,
) {
    val c = CRTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = c.accent,
                unselectedColor = c.textDim,
            )
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = CRType.cardTitle, color = c.text, modifier = Modifier.weight(1f))
        if (attached) {
            Text("attached", style = CRType.pill, color = c.ready)
            Spacer(Modifier.width(6.dp))
        }
        if (onKill != null) {
            TextButton(
                onClick = onKill,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text("Kill", style = CRType.pill, color = c.disconnected)
            }
        }
    }
}

@Composable
private fun crTextFieldColors() = OutlinedTextFieldDefaults.colors(
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
