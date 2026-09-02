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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.clauderemote.model.*
import com.clauderemote.storage.AppSettings
import com.clauderemote.storage.FolderPolicyStorage
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
    // ONE scan returns a whole subtree (dirs + mtimes + project markers) rather
    // than the per-click listing this replaced — see RemoteDirScan for why.
    // Returns null when the attempt FAILED, which is not the same answer as
    // an empty tree — the UI has to be able to say "couldn't list" rather
    // than claim the folder has no subfolders.
    onScanFolders: (suspend (String) -> RemoteDirTree?)? = null,
    // Multi-account support: accounts are loaded lazily (a suspend callback,
    // matching onBrowseFolders' idiom) rather than threading the whole
    // SessionOrchestrator through, and folder policy is read directly since it
    // depends on the in-screen `folder` field, not anything App.kt tracks.
    onLoadAccounts: (suspend () -> List<ClaudeAccount>)? = null,
    folderPolicyStorage: FolderPolicyStorage? = null,
    onLaunch: (folder: String, mode: ClaudeMode, model: ClaudeModel, connectionType: ConnectionType, tmuxSession: String, isNewTmuxSession: Boolean, accountSlug: String?) -> Unit
) {
    val c = CRTheme.colors
    val m = CRTheme.metrics

    var folder by remember { mutableStateOf(server.defaultFolder) }

    // ── Accounts (multi-account) ────────────────────────────────────────────
    var accounts by remember { mutableStateOf<List<ClaudeAccount>>(emptyList()) }
    LaunchedEffect(server.id) {
        accounts = try { onLoadAccounts?.invoke() ?: emptyList() } catch (_: Exception) { emptyList() }
    }
    val folderPolicy = remember(folder, server.id, accounts) { folderPolicyStorage?.get(server.id, folder) }
    // Every account is offered; the folder policy only drives which one is
    // PRESELECTED and whether a warning shows. Hiding accounts here while the
    // live-session switcher allowed them made the guard inconsistent, and it is
    // meant to prevent a mis-tap rather than lock the owner out of a folder.
    val offerableAccounts = accounts
    var selectedAccountSlug by remember(server.id) { mutableStateOf<String?>(null) }
    // Re-pick the default whenever the folder (and thus its policy) changes —
    // e.g. typing a different path should re-offer that folder's own default,
    // not silently keep whatever the previous folder had selected.
    LaunchedEffect(folderPolicy, accounts) {
        selectedAccountSlug = defaultAccountFor(folderPolicy, accounts)
            ?.let { if (it.isDefault) null else it.slug }
    }
    var selectedMode by remember { mutableStateOf(appSettings.defaultClaudeMode) }
    var selectedModel by remember { mutableStateOf(appSettings.defaultClaudeModel) }
    // Default to Mosh when the server prefers it AND a direct-UDP path exists
    // (plain SSH or Tailscale) — over Tailscale, mosh roams across Starlink's
    // IP changes without dropping the session.
    var connectionType by remember {
        mutableStateOf(
            if (server.preferMosh && (!server.useCloudflareProxy || server.hasTailscale))
                ConnectionType.MOSH else ConnectionType.SSH
        )
    }
    var sessionAlias by remember { mutableStateOf("") }
    var tmuxSessionName by remember {
        mutableStateOf(TmuxNameParser.build(server.name, server.defaultFolder, appSettings.defaultClaudeMode == ClaudeMode.YOLO))
    }
    var useExistingTmux by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // ── Folder browsing ────────────────────────────────────────────────────
    // The cached subtree. Navigation inside it is free; only a directory deeper
    // than the last scan reached costs another round trip, and its result is
    // merged in rather than replacing what we already know.
    var dirTree by remember(server.id) { mutableStateOf(RemoteDirTree.empty(server.defaultFolder)) }
    var scanning by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    // Directories whose scan FAILED. A failure leaves no listing behind, so
    // without this the picker's "listing missing → request it" effect would
    // re-fire on every update and hammer a dead connection. It is also what
    // lets the UI say "couldn't list this" instead of "no subfolders", and what
    // makes the browse button retry for real after a failure rather than being
    // a silent no-op.
    var failedScans by remember(server.id) { mutableStateOf<Set<String>>(emptySet()) }
    // Bumped whenever a scan finishes, win or lose. The effects that re-request
    // a missing listing key on THIS rather than on the tree: keying on the tree
    // only works because `RemoteDirTree` has no `equals` and so compares by
    // identity, which is far too subtle an invariant to rest recovery on —
    // someone giving the model a natural `equals` would deadlock the picker at
    // "No subfolders" with no clue why.
    var scanGeneration by remember(server.id) { mutableStateOf(0) }
    val scan: (String, Boolean) -> Unit = { target, force ->
        val key = RemotePath.normalize(target)
        val needed = force || (!dirTree.hasListing(key) && key !in failedScans)
        if (onScanFolders != null && !scanning && needed) {
            scanning = true
            scope.launch {
                try {
                    // `ch.connect` bounds only the channel OPEN; the read itself
                    // is unbounded, and the keepalive only kills a link that has
                    // gone silent — a genuinely slow `find` (an NFS mount, say)
                    // would just keep the picker spinning.
                    val fetched = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                        onScanFolders.invoke(key)
                    }
                    if (fetched != null) {
                        dirTree = if (dirTree.isEmpty) fetched else dirTree.merge(fetched)
                        failedScans = failedScans - key
                    } else {
                        failedScans = failedScans + key
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Rethrown rather than recorded as a scan failure: this is
                    // the composition going away, not the server saying no.
                    throw e
                } catch (_: Exception) {
                    failedScans = failedScans + key
                } finally {
                    // In a finally so a cancellation or an unexpected throw
                    // cannot latch the spinner on and block every later scan.
                    scanning = false
                    scanGeneration++
                }
            }
        }
    }

    LaunchedEffect(folder, selectedMode, sessionAlias) {
        if (!useExistingTmux) {
            tmuxSessionName = TmuxNameParser.build(
                server.name, folder, selectedMode == ClaudeMode.YOLO, sessionAlias
            )
        }
    }

    // Debounce the Launch button: launchSession takes several seconds (SSH
    // connect + tmux) and this screen stays interactive the whole time, so an
    // impatient second tap used to fire a SECOND launchSession — a duplicate
    // tab whose tmux relaunch killed the first one's pane. The orchestrator
    // now also dedups by tmux name; this is the UX half: show progress, block
    // re-taps, auto-re-arm after 8s in case the launch failed and we're still
    // on this screen.
    var launching by remember { mutableStateOf(false) }
    LaunchedEffect(launching) {
        if (launching) {
            kotlinx.coroutines.delay(8_000)
            launching = false
        }
    }
    val launch: () -> Unit = {
        if (!launching) {
            launching = true
            onLaunch(folder, selectedMode, selectedModel, connectionType, tmuxSessionName, !useExistingTmux, selectedAccountSlug)
        }
    }
    val launchKeyboardOptions = KeyboardOptions(imeAction = ImeAction.Go)
    val launchKeyboardActions = KeyboardActions(onGo = { launch() })

    // Build will-run preview command
    val willRunPreview = buildString {
        val attachFlag = if (useExistingTmux) "-t" else "new -A -s"
        append("$ tmux $attachFlag '${tmuxSessionName}'\n")
        append("$ cd $folder && ${if (selectedModel.isLocal) "claude-local" else "claude"}")
        if (selectedModel != ClaudeModel.DEFAULT) append(" --model ${selectedModel.cliValue}")
        selectedMode.cliFlag?.let { append(" $it") }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
      Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = m.sectionPad, vertical = m.sectionTopGap),
            verticalArrangement = Arrangement.spacedBy(m.cardGap)
        ) {
            // ── Folder ─────────────────────────────────────────────────────
            SectionLabel("Folder", c.textDim)
            CRCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // The path field is the fast path: shell-like Tab completion
                    // over the cached tree plus live validation. Browsing is one
                    // tap away but no longer the only way in — and editing the
                    // path by hand no longer leaves a stale listing behind it,
                    // because there is no listing bound to the field any more.
                    var fieldFocused by remember { mutableStateOf(false) }
                    // `onFocusChanged` alone was not enough: a scan already in
                    // flight when the field got focus discarded that request
                    // (one scan at a time), leaving typeahead empty until the
                    // user clicked away and back. Re-ask whenever a scan
                    // finishes while we still have no listing for this path.
                    LaunchedEffect(fieldFocused, scanGeneration, folder) {
                        if (fieldFocused) scan(folder, false)
                    }
                    val suggestions = remember(folder, dirTree, fieldFocused) {
                        if (!fieldFocused) emptyList()
                        else PathCompletion.suggest(folder, dirTree, server.recentFolders)
                            .filterNot { it.path == RemotePath.normalize(folder) }
                            .take(6)
                    }
                    // Tab advances to where the candidates diverge, exactly like
                    // shell completion; with one candidate that completes it.
                    val completeToCommonPrefix: () -> Unit = {
                        val prefix = PathCompletion.commonPrefix(suggestions.map { it.path })
                        // Length alone is not enough: the candidates may share a
                        // prefix that is longer than what was typed yet does not
                        // EXTEND it (a whole-tree fuzzy hit elsewhere), and
                        // swapping the text for that would move the user
                        // somewhere they never asked to go. Case is ignored so
                        // typing "~/cl" still corrects itself to "~/CLAUDE…".
                        if (prefix.length > folder.length &&
                            prefix.startsWith(folder, ignoreCase = true)
                        ) {
                            folder = prefix
                        } else {
                            suggestions.firstOrNull()?.let { folder = it.path }
                        }
                    }

                    OutlinedTextField(
                        value = folder,
                        onValueChange = { folder = it },
                        label = { Text("Remote path") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { state ->
                                fieldFocused = state.isFocused
                                // Scan on intent rather than on screen entry, so
                                // opening Connect and hitting Launch straight away
                                // still costs no extra handshake.
                                if (state.isFocused) scan(folder, false)
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Tab && suggestions.isNotEmpty()
                                ) {
                                    completeToCommonPrefix()
                                    true
                                } else false
                            },
                        singleLine = true,
                        keyboardOptions = launchKeyboardOptions,
                        // Enter accepts the top suggestion while the list is
                        // open, and only launches once there is nothing to
                        // accept. Launching straight from a field with an open
                        // typeahead meant Enter could start a session on a
                        // half-typed path — and it contradicted Enter's meaning
                        // everywhere else in this feature, where it descends.
                        keyboardActions = KeyboardActions(onGo = {
                            val top = suggestions.firstOrNull()
                            if (top != null) folder = top.path else launch()
                        }),
                        colors = crTextFieldColors(),
                        trailingIcon = if (onScanFolders != null) {{
                            IconButton(onClick = {
                                pickerOpen = true
                                // Force after a failure: otherwise the guard that
                                // stops the retry loop also makes this button do
                                // nothing at all, with no spinner and no message.
                                scan(folder, RemotePath.normalize(folder) in failedScans)
                            }) {
                                if (scanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = c.accent
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Folder,
                                        contentDescription = "Browse folders",
                                        tint = c.textDim
                                    )
                                }
                            }
                        }} else null
                    )

                    if (suggestions.isNotEmpty()) {
                        val suggestShape = RoundedCornerShape(m.cardRadius)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(suggestShape)
                                .background(c.surface2)
                                .border(1.dp, c.border, suggestShape)
                        ) {
                            suggestions.forEach { suggestion ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { folder = suggestion.path }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Folder,
                                        null,
                                        tint = if (suggestion.kind == RemoteDirKind.FOLDER) c.textDim else c.accent,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        suggestion.path,
                                        style = CRType.mono,
                                        color = c.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    val tag = when (suggestion.kind) {
                                        RemoteDirKind.RECENT -> "recent"
                                        RemoteDirKind.PROJECT -> "git"
                                        RemoteDirKind.FOLDER -> null
                                    }
                                    if (tag != null) {
                                        Text(tag, style = CRType.pill, color = c.textDim)
                                    }
                                }
                            }
                        }
                    }

                    // Only claim a folder is missing when its parent was actually
                    // listed. Anything deeper than the scan reached is unknown,
                    // not absent, and a false "no such folder" would be worse
                    // than no hint at all.
                    val pathMissing = remember(folder, dirTree) {
                        val parent = RemotePath.parent(folder)
                        parent != null && dirTree.hasListing(parent) && !dirTree.contains(folder)
                    }
                    if (pathMissing) {
                        Text(
                            "No such folder on ${server.name} — it will be created only if Claude does it.",
                            style = CRType.bodyDim,
                            color = c.approval
                        )
                    }

                    // Recent folders as quick-jump chips under the field.
                    val recents = server.recentFolders.take(6)
                    if (recents.isNotEmpty()) {
                        Text("RECENT", style = CRType.sectionH, color = c.textDim)
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
                            options = ClaudeModel.selectable,
                            selected = selectedModel,
                            onSelect = { selectedModel = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { it.displayName }
                        )
                    }
                    LabeledRow("Connection") {
                        Segmented(
                            // Mosh needs direct UDP: available on a plain-SSH
                            // server OR when a Tailscale path is configured (UDP
                            // rides the WireGuard tunnel). A CF-only server can't.
                            options = if (server.useCloudflareProxy && !server.hasTailscale)
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
                    // Compact account chip — only shown once there's an actual
                    // choice to make; a lone account needs no picker at all.
                    if (offerableAccounts.size > 1) {
                        LabeledRow("Account") {
                            AccountPickerChip(
                                accounts = offerableAccounts,
                                selectedSlug = selectedAccountSlug,
                                onSelect = { acc -> selectedAccountSlug = if (acc.isDefault) null else acc.slug },
                            )
                        }
                        // Same reminder wording as the live-session switcher, so the
                        // folder guard reads identically wherever an account is chosen.
                        if (!isAccountPreferred(folderPolicy, selectedAccountSlug)) {
                            Text(
                                "This folder isn't set up for that account — launching anyway is fine.",
                                style = CRType.bodyDim,
                                color = c.disconnected,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
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
                enabled = !launching,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(m.rowHeight),
                colors = ButtonDefaults.buttonColors(
                    containerColor = c.accent,
                    contentColor = c.accentInk,
                ),
                shape = RoundedCornerShape(m.cardRadius),
            ) {
                Text(if (launching) "Connecting…" else "▶  Launch Claude", style = CRType.cardTitle)
            }

            Spacer(Modifier.height(16.dp))
        }

      }
    }

    // Composed OUTSIDE the Scaffold, not inside its content: within the content
    // the scrim began below the TopAppBar, so Back stayed visible and clickable
    // behind a supposedly modal overlay — tapping it left the screen with the
    // picker still composed. It is a sibling of the whole screen rather than a
    // popup anchored to the field; an anchored menu is exactly what made the old
    // browser clip and draw over the cards beneath it.
    if (pickerOpen && onScanFolders != null) {
        RemotePathPicker(
            initialPath = folder,
            tree = dirTree,
            loading = scanning,
            recents = server.recentFolders.take(6),
            scanGeneration = scanGeneration,
            loadFailed = { path -> RemotePath.normalize(path) in failedScans },
            onRequestListing = { target -> scan(target, false) },
            onRefresh = { target -> scan(target, true) },
            onPick = { picked ->
                folder = picked
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
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
private fun AccountPickerChip(
    accounts: List<ClaudeAccount>,
    selectedSlug: String?,
    onSelect: (ClaudeAccount) -> Unit,
) {
    val c = CRTheme.colors
    var open by remember { mutableStateOf(false) }
    val current = accounts.firstOrNull { (if (it.isDefault) null else it.slug) == selectedSlug }
        ?: accounts.firstOrNull()
    val shape = RoundedCornerShape(999.dp)
    Box {
        Box(
            modifier = Modifier
                .background(c.surface2, shape)
                .border(1.dp, c.border, shape)
                .clip(shape),
        ) {
            TextButton(
                onClick = { open = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    current?.label ?: "Account",
                    style = CRType.mono,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            accounts.forEach { acc ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(acc.label, style = CRType.cardTitle, color = c.text)
                            if (acc.subtitle.isNotBlank()) {
                                Text(acc.subtitle, style = CRType.bodyDim, color = c.textDim)
                            }
                        }
                    },
                    onClick = { open = false; onSelect(acc) },
                )
            }
        }
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
internal fun crTextFieldColors() = OutlinedTextFieldDefaults.colors(
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
