package com.clauderemote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeAccount
import com.clauderemote.model.FolderPolicy
import com.clauderemote.model.SshServer
import com.clauderemote.model.accountSlugFromEmail
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.storage.FolderPolicyStorage
import com.clauderemote.ui.components.CRCard
import com.clauderemote.ui.components.Pill
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import kotlinx.coroutines.launch

/**
 * Account management: lists the Claude logins provisioned on a server, lets
 * the user add/remove them, and configures which folders on that server may
 * use which accounts ([FolderPolicy]). Scoped to one server at a time (a
 * server picker appears once there's more than one) because accounts and
 * folder policies are both server-local concepts — `listClaudeAccounts` and
 * `FolderPolicyStorage` are both keyed by server id.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AccountsScreen(
    accountColorStorage: com.clauderemote.storage.AccountColorStorage,
    appSettings: com.clauderemote.storage.AppSettings,
    servers: List<SshServer>,
    /** Preselected server; when set the in-screen picker is hidden, because the
     *  caller (the server's own settings) has already answered "which server". */
    initialServerId: String? = null,
    sessionOrchestrator: SessionOrchestrator,
    folderPolicyStorage: FolderPolicyStorage,
    onBack: () -> Unit,
    onOpenUrl: ((String) -> Unit)? = null,
) {
    val c = CRTheme.colors
    val scope = rememberCoroutineScope()

    var selectedServerId by remember { mutableStateOf(initialServerId ?: servers.firstOrNull()?.id) }
    val selectedServer = servers.firstOrNull { it.id == selectedServerId }

    var accounts by remember { mutableStateOf<List<ClaudeAccount>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    fun refreshAccounts() {
        val server = selectedServer ?: return
        scope.launch {
            loading = true
            loadError = null
            try {
                accounts = sessionOrchestrator.listClaudeAccounts(server.id)
            } catch (e: Exception) {
                loadError = e.message?.ifBlank { null } ?: "Failed to load accounts"
            }
            loading = false
        }
    }
    LaunchedEffect(selectedServerId) { refreshAccounts() }

    // FolderPolicyStorage is a plain synchronous KeyValueStore wrapper, not a
    // reactive store — we re-snapshot it ourselves after every edit rather
    // than collecting a flow.
    var policies by remember(selectedServerId) {
        mutableStateOf(selectedServer?.let { folderPolicyStorage.forServer(it.id) } ?: emptyMap())
    }
    fun refreshPolicies() {
        policies = selectedServer?.let { folderPolicyStorage.forServer(it.id) } ?: emptyMap()
    }
    // Folders picked for editing that don't have a saved policy yet — an
    // empty policy is never persisted (see FolderPolicyStorage.set), so a
    // freshly added row lives only here until the user actually sets something.
    var draftFolders by remember(selectedServerId) { mutableStateOf<List<String>>(emptyList()) }

    var addAccountOpen by remember { mutableStateOf(false) }
    // Chip colours held in state so a pick repaints immediately — the storage
    // itself re-reads prefs on every call and is not observable.
    var accountColors by remember {
        mutableStateOf<Map<String, com.clauderemote.ui.theme.CRAccent>>(emptyMap())
    }
    LaunchedEffect(accounts) {
        // assign() over the whole list, not colorFor() per account: it is what
        // guarantees two accounts don't come up the same colour.
        accountColors = accountColorStorage.assign(accounts.map { it.slug })
    }
    // The default account is a slot, not an identity — warn when the person
    // behind it changes, since folder policies that name it silently follow.
    var defaultAccountChangedFrom by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(accounts, selectedServerId) {
        val srv = selectedServerId ?: return@LaunchedEffect
        val current = accounts.firstOrNull { it.isDefault }?.email.orEmpty()
        if (current.isBlank()) return@LaunchedEffect
        val remembered = appSettings.rememberedDefaultAccount(srv)
        if (remembered.isBlank()) {
            appSettings.setRememberedDefaultAccount(srv, current)
        } else if (remembered != current) {
            defaultAccountChangedFrom = remembered
        }
    }
    var pendingLoginSlug by remember { mutableStateOf<String?>(null) }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var loginTimedOut by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<ClaudeAccount?>(null) }
    val loginFlow by sessionOrchestrator.loginFlow.collectAsState()

    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                title = { Text("Accounts", style = CRType.cardTitle, color = c.text) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.textDim)
                    }
                },
                actions = {
                    IconButton(onClick = { refreshAccounts(); refreshPolicies() }) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = c.textDim)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = c.surface,
                    scrolledContainerColor = c.surface,
                ),
            )
        }
    ) { padding ->
        if (servers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Add a server first.", style = CRType.bodyDim, color = c.textDim)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(4.dp))

                if (servers.size > 1 && initialServerId == null) {
                    SectionHeader("Server")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        servers.forEach { srv ->
                            val selected = srv.id == selectedServerId
                            Pill(
                                text = srv.name,
                                background = if (selected) c.tintAccent else c.surface2,
                                foreground = if (selected) c.accent else c.textDim,
                                modifier = Modifier.clickableNoRipple { selectedServerId = srv.id },
                            )
                        }
                    }
                }

                SectionHeader("Accounts")
                // Shown until acknowledged: a changed default means every folder
                // policy naming it now applies to a different person.
                defaultAccountChangedFrom?.let { was ->
                    val nowEmail = accounts.firstOrNull { it.isDefault }?.email.orEmpty()
                    CRCard {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Default account changed", style = CRType.cardTitle, color = c.disconnected)
                            Text(
                                "This server's default login was $was and is now $nowEmail. " +
                                    "Folder rules that allow \"default\" now allow this account instead.",
                                style = CRType.bodyDim,
                                color = c.textDim,
                            )
                            TextButton(
                                onClick = {
                                    selectedServerId?.let { appSettings.setRememberedDefaultAccount(it, nowEmail) }
                                    defaultAccountChangedFrom = null
                                },
                                contentPadding = PaddingValues(horizontal = 0.dp),
                            ) { Text("Got it", color = c.accent) }
                        }
                    }
                }
                CRCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when {
                            loading && accounts.isEmpty() -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                                    Text("Loading accounts…", style = CRType.bodyDim, color = c.textDim)
                                }
                            }
                            loadError != null -> {
                                Text(loadError ?: "", style = CRType.bodyDim, color = c.disconnected)
                                TextButton(onClick = { refreshAccounts() }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                                    Text("Retry", color = c.accent)
                                }
                            }
                            accounts.isEmpty() -> {
                                Text("No accounts probed yet.", style = CRType.bodyDim, color = c.textDim)
                            }
                            else -> {
                                accounts.forEachIndexed { idx, account ->
                                    if (idx > 0) HorizontalDivider(color = c.border.copy(alpha = 0.4f))
                                    AccountRow(
                                        account = account,
                                        onRemove = if (!account.isDefault) ({ removeTarget = account }) else null,
                                        selectedColor = accountColors[account.slug],
                                        onPickColor = { accent ->
                                            accountColorStorage.set(account.slug, accent)
                                            accountColors = accountColorStorage.assign(accounts.map { it.slug })
                                        },
                                        onRelogin = selectedServer?.let { srv ->
                                            {
                                                // Same path as the add flow, minus provisioning —
                                                // the dir already exists (and the default account
                                                // must never get one).
                                                scope.launch {
                                                    pendingLoginSlug = account.slug
                                                    sessionOrchestrator.startClaudeAccountLogin(srv.id, account.slug)
                                                }
                                                Unit
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = c.border)
                        TextButton(
                            onClick = { addAccountOpen = true },
                            enabled = selectedServer != null,
                            contentPadding = PaddingValues(horizontal = 0.dp),
                        ) { Text("+ Add account", color = c.accent) }
                    }
                }

                SectionHeader("Folder Policies")
                Text(
                    "Restrict which account a folder can use, or set its default. Leaving " +
                        "the allowed list empty means every account is offered here — not none.",
                    style = CRType.bodyDim, color = c.textDim,
                )
                CRCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        val configuredFolders = (policies.keys + draftFolders).distinct().sorted()
                        if (configuredFolders.isEmpty()) {
                            Text(
                                "No folder-specific policy — every folder offers every account.",
                                style = CRType.bodyDim, color = c.textDim,
                            )
                        } else {
                            configuredFolders.forEachIndexed { idx, folder ->
                                if (idx > 0) HorizontalDivider(color = c.border.copy(alpha = 0.4f))
                                FolderPolicyRow(
                                    folder = folder,
                                    policy = policies[folder] ?: FolderPolicy(),
                                    accounts = accounts,
                                    onChange = { updated ->
                                        selectedServer?.let { srv ->
                                            folderPolicyStorage.set(srv.id, folder, updated)
                                            // Publish immediately: a rule only kept
                                            // on this device is exactly the bug this
                                            // replaced.
                                            scope.launch {
                                                sessionOrchestrator.writeFolderPolicies(
                                                    srv.id,
                                                    folderPolicyStorage.exportForServer(srv.id),
                                                )
                                            }
                                            // set() deletes the stored row outright when the
                                            // resulting policy is empty (see FolderPolicyStorage.set)
                                            // — keep the row itself on screen via draftFolders so
                                            // clearing every field doesn't make the row vanish
                                            // out from under the user mid-edit.
                                            if (folder !in draftFolders) draftFolders = draftFolders + folder
                                            refreshPolicies()
                                        }
                                    },
                                    onRemove = {
                                        selectedServer?.let { srv -> folderPolicyStorage.clear(srv.id, folder) }
                                        draftFolders = draftFolders - folder
                                        refreshPolicies()
                                    },
                                )
                            }
                        }
                        HorizontalDivider(color = c.border)
                        AddFolderPolicyRow(
                            suggestions = (selectedServer?.recentFolders ?: emptyList())
                                .filterNot { it in configuredFolders },
                            onAdd = { folder ->
                                if (folder.isNotBlank() && folder !in configuredFolders) {
                                    draftFolders = draftFolders + folder
                                }
                            },
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }

    // ── Add account ──────────────────────────────────────────────────────────
    if (addAccountOpen) {
        AddAccountDialog(
            existingSlugs = accounts.map { it.slug }.toSet(),
            onDismiss = { addAccountOpen = false },
            onConfirm = { label ->
                addAccountOpen = false
                val server = selectedServer
                if (server != null) {
                    scope.launch {
                        val slug = accountSlugFromEmail(label)
                        val provisioned = sessionOrchestrator.provisionClaudeAccount(server.id, slug)
                        if (provisioned) {
                            pendingLoginSlug = slug
                            sessionOrchestrator.startClaudeAccountLogin(server.id, slug)
                        } else {
                            loadError = "Couldn't create account \"$slug\" on ${server.name}."
                        }
                    }
                }
            },
        )
    }

    // ── OAuth sign-in for the account just provisioned ─────────────────────
    // Read from the login PANE, not from sessionOrchestrator.loginFlow: that
    // StateFlow is fed by InputPromptDetector, which only scans sessions the app
    // has ATTACHED as tabs. The account-login pane deliberately has no tab, so
    // nothing ever fed that flow — the dialog never appeared and the user was
    // left with a provisioned dir and no login. Poll the pane instead.
    val loginSlug = pendingLoginSlug
    if (loginSlug != null) {
        val server = selectedServer
        LaunchedEffect(loginSlug, server?.id) {
            if (server == null) return@LaunchedEffect
            loginUrl = null
            loginTimedOut = false
            // The pane needs a moment to render the URL; give it a bounded number
            // of tries so a login that never starts surfaces as an error instead
            // of a dialog that spins forever.
            repeat(LOGIN_URL_POLL_TRIES) {
                val url = sessionOrchestrator.readClaudeAccountLoginUrl(server.id, loginSlug)
                if (url != null) {
                    loginUrl = url
                    return@LaunchedEffect
                }
                kotlinx.coroutines.delay(LOGIN_URL_POLL_MS)
            }
            loginTimedOut = true
        }

        val url = loginUrl
        if (url != null) {
            AccountLoginDialog(
                url = url,
                onOpenUrl = { onOpenUrl?.invoke(url) },
                onSubmitCode = { code ->
                    scope.launch {
                        server?.let { sessionOrchestrator.submitClaudeAccountLoginCode(it.id, loginSlug, code) }
                        pendingLoginSlug = null
                        loginUrl = null
                        // The pane writes the credentials a beat after the code is
                        // accepted, so give it a moment before re-probing or the
                        // row comes back label-less.
                        kotlinx.coroutines.delay(2500)
                        refreshAccounts()
                    }
                },
                onCancel = {
                    scope.launch {
                        server?.let { sessionOrchestrator.cancelClaudeAccountLogin(it.id, loginSlug) }
                        pendingLoginSlug = null
                        loginUrl = null
                    }
                },
            )
        } else {
            AccountLoginPendingDialog(
                timedOut = loginTimedOut,
                onCancel = {
                    scope.launch {
                        server?.let { sessionOrchestrator.cancelClaudeAccountLogin(it.id, loginSlug) }
                        pendingLoginSlug = null
                        loginTimedOut = false
                        // Refresh even on failure: provisioning already created the
                        // directory, so the half-made account must appear in the
                        // list or the user has no way to remove it.
                        refreshAccounts()
                    }
                },
            )
        }
    }

    // ── Remove account ───────────────────────────────────────────────────────
    removeTarget?.let { account ->
        RemoveAccountConfirmDialog(
            account = account,
            onDismiss = { removeTarget = null },
            onConfirm = {
                removeTarget = null
                val server = selectedServer
                if (server != null) {
                    scope.launch {
                        val ok = sessionOrchestrator.removeClaudeAccount(server.id, account.slug)
                        if (ok) refreshAccounts() else loadError = "Couldn't remove ${account.label}."
                    }
                }
            },
        )
    }
}

// ── Rows ─────────────────────────────────────────────────────────────────────

@Composable
private fun AccountRow(
    account: ClaudeAccount,
    onRemove: (() -> Unit)?,
    selectedColor: com.clauderemote.ui.theme.CRAccent? = null,
    onPickColor: ((com.clauderemote.ui.theme.CRAccent) -> Unit)? = null,
    /** Re-run the OAuth login for THIS account (renewal, or repairing a logged-out dir). */
    onRelogin: (() -> Unit)? = null,
    nowMs: Long = System.currentTimeMillis(),
) {
    val c = CRTheme.colors
    // Renewal state from the account's own refresh-token deadline (read by the
    // probe from .credentials.json). Unknown ⇒ no badge at all: an account we
    // couldn't read must not be nagged about, only one we know is running out.
    val expiryDays = account.loginExpiresInDays(nowMs)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    account.label, style = CRType.cardTitle, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (account.isDefault) {
                    Pill(text = "DEFAULT", background = c.tintAccent, foreground = c.accent)
                }
                // Only inside the warning window — a login with three weeks left
                // is not news, and a badge on every row would train the user to
                // ignore the one that matters.
                if (expiryDays != null && expiryDays <= ClaudeAccount.LOGIN_EXPIRY_WARN_DAYS) {
                    val expired = expiryDays < 0
                    Pill(
                        text = if (expired) "VYPRŠELO" else "${expiryDays}D",
                        background = if (expired) c.tintRed else c.tintYellow,
                        foreground = if (expired) c.disconnected else c.working,
                    )
                }
            }
            val subtitleParts = listOfNotNull(
                account.subtitle.takeIf { it.isNotBlank() },
                expiryDays?.let { d ->
                    when {
                        d < 0 -> "přihlášení vypršelo"
                        d == 0 -> "přihlášení vyprší dnes"
                        d == 1 -> "přihlášení vyprší za 1 den"
                        d in 2..4 -> "přihlášení vyprší za $d dny"
                        else -> "přihlášení vyprší za $d dní"
                    }
                },
            )
            if (subtitleParts.isNotEmpty()) {
                Text(
                    subtitleParts.joinToString(" · "), style = CRType.bodyDim,
                    color = if (expiryDays != null && expiryDays <= ClaudeAccount.LOGIN_EXPIRY_WARN_DAYS) c.working else c.textDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onPickColor != null) {
            // Swatches inline on the row: the chip colour only makes sense next to
            // the account it marks, and this is the only place both are visible.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(end = 6.dp),
            ) {
                com.clauderemote.ui.theme.CRAccent.entries.forEach { accent ->
                    val picked = accent == selectedColor
                    Box(
                        modifier = Modifier
                            .size(if (picked) 20.dp else 16.dp)
                            .clip(CircleShape)
                            .background(accent.color)
                            .then(
                                if (picked) Modifier.border(2.dp, c.text, CircleShape) else Modifier
                            )
                            .clickable { onPickColor(accent) },
                    )
                }
            }
        }
        if (onRelogin != null) {
            // The only way to re-authenticate an EXISTING account. Until this
            // existed, `startLogin` was reachable from "+ Add account" alone, so
            // renewing a seat meant either doing it inside a session or removing
            // and re-adding the account (which throws away its config dir).
            TextButton(onClick = onRelogin) {
                Text(
                    if (expiryDays != null && expiryDays <= ClaudeAccount.LOGIN_EXPIRY_WARN_DAYS) "Obnovit" else "Přihlásit",
                    style = CRType.pill,
                    color = c.accent,
                )
            }
        }
        if (onRemove != null) {
            TextButton(onClick = onRemove) { Text("Remove", style = CRType.pill, color = c.disconnected) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderPolicyRow(
    folder: String,
    policy: FolderPolicy,
    accounts: List<ClaudeAccount>,
    onChange: (FolderPolicy) -> Unit,
    onRemove: () -> Unit,
) {
    val c = CRTheme.colors
    var defaultMenuOpen by remember { mutableStateOf(false) }
    val defaultAccount = accounts.firstOrNull { it.slug == policy.defaultAccountSlug }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                folder, style = CRType.mono, color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRemove, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Remove", style = CRType.pill, color = c.disconnected)
            }
        }

        if (accounts.isEmpty()) {
            Text("Load accounts above to configure this folder.", style = CRType.bodyDim, color = c.textDim)
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Default:", style = CRType.bodyDim, color = c.textDim)
            Box {
                TextButton(onClick = { defaultMenuOpen = true }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(defaultAccount?.label ?: "No preference", color = c.accent, style = CRType.bodyDim)
                }
                DropdownMenu(expanded = defaultMenuOpen, onDismissRequest = { defaultMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("No preference") },
                        onClick = { defaultMenuOpen = false; onChange(policy.copy(defaultAccountSlug = null)) },
                    )
                    accounts.forEach { acc ->
                        DropdownMenuItem(
                            text = { Text(acc.label) },
                            onClick = { defaultMenuOpen = false; onChange(policy.copy(defaultAccountSlug = acc.slug)) },
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Allowed here:", style = CRType.bodyDim, color = c.textDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                accounts.forEach { acc ->
                    val selected = acc.slug in policy.allowedAccountSlugs
                    Pill(
                        text = acc.label,
                        background = if (selected) c.tintAccent else c.surface2,
                        foreground = if (selected) c.accent else c.textDim,
                        modifier = Modifier.clickableNoRipple {
                            val next = if (selected) policy.allowedAccountSlugs - acc.slug
                                else policy.allowedAccountSlugs + acc.slug
                            onChange(policy.copy(allowedAccountSlugs = next))
                        },
                    )
                }
            }
            Text(
                if (policy.allowedAccountSlugs.isEmpty()) "All accounts allowed"
                else "${policy.allowedAccountSlugs.size} of ${accounts.size} allowed",
                style = CRType.monoTiny, color = c.textDim,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddFolderPolicyRow(suggestions: List<String>, onAdd: (String) -> Unit) {
    val c = CRTheme.colors
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (suggestions.isNotEmpty()) {
            Text("RECENT", style = CRType.sectionH, color = c.textDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.take(6).forEach { folder ->
                    Pill(
                        text = folder.substringAfterLast('/').ifBlank { folder },
                        background = c.surface2, foreground = c.textDim,
                        modifier = Modifier.clickableNoRipple { onAdd(folder) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Folder path…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onAdd(text.trim()); text = "" }, enabled = text.isNotBlank()) {
                Text("Add", color = c.accent)
            }
        }
    }
}

// ── Local helpers (file-scoped; mirrors SettingsScreen's private idiom) ────

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = CRType.sectionH,
        color = CRTheme.colors.textDim,
        modifier = Modifier.padding(start = 2.dp, top = 4.dp),
    )
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )

/** How long to wait for the login pane to render its OAuth URL. */
private const val LOGIN_URL_POLL_TRIES = 20
private const val LOGIN_URL_POLL_MS = 1000L
