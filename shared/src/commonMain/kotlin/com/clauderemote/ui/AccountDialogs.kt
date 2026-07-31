package com.clauderemote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeAccount
import com.clauderemote.model.accountSlugFromEmail
import com.clauderemote.ui.components.FloatingDialog
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRThemeSnapshot
import com.clauderemote.ui.theme.CRType

/**
 * Prompt for a label identifying a new Claude login. The real email isn't
 * known until the OAuth flow completes, so this only picks the slug the
 * account will live under on the server (`~/.claude-remote/accounts/<slug>/`)
 * — see [ClaudeAccount].
 */
@Composable
fun AddAccountDialog(
    existingSlugs: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (label: String) -> Unit,
) {
    val c = CRTheme.colors
    var label by remember { mutableStateOf("") }
    val slug = remember(label) { accountSlugFromEmail(label) }
    val collision = label.isNotBlank() && slug in existingSlugs

    FloatingDialog(
        visible = true,
        onDismiss = onDismiss,
        theme = CRThemeSnapshot.current(),
        title = { Text("Add account", color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "A short name for this login — you'll sign in with the real Claude account next.",
                    style = CRType.bodyDim, color = c.textDim,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. work, personal") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (label.isNotBlank()) {
                    Text("Slug: $slug", style = CRType.monoTiny, color = c.textDim)
                }
                if (collision) {
                    Text("An account with that name already exists.", style = CRType.bodyDim, color = c.disconnected)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && !collision,
                onClick = { onConfirm(label.trim()) },
            ) { Text("Continue", color = c.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = c.textDim) }
        },
    )
}

/**
 * OAuth sign-in card for a freshly provisioned account — the same de-wrapped
 * URL + paste-code pattern as the in-session `/login` card in TerminalScreen
 * (Claude prints the URL hard-wrapped across terminal rows, which breaks a
 * naive copy from the raw terminal).
 */
@Composable
fun AccountLoginDialog(
    url: String,
    onOpenUrl: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val c = CRTheme.colors
    val clipboard = LocalClipboardManager.current
    var code by remember(url) { mutableStateOf("") }

    FloatingDialog(
        visible = true,
        onDismiss = onCancel,
        theme = CRThemeSnapshot.current(),
        title = { Text("Sign in", color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenUrl, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("Open sign-in page", color = c.accent)
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(url)) }) {
                        Text("Copy URL", color = c.accent)
                    }
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Sign in in the browser, then paste the code Claude gives you.",
                    style = CRType.bodyDim, color = c.textDim,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = code.isNotBlank(), onClick = { onSubmitCode(code.trim()) }) {
                Text("Submit", color = c.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel", color = c.textDim) }
        },
    )
}

/** Confirms deleting a non-default account's isolated server-side config dir + credentials. */
@Composable
fun RemoveAccountConfirmDialog(
    account: ClaudeAccount,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val c = CRTheme.colors
    FloatingDialog(
        visible = true,
        onDismiss = onDismiss,
        theme = CRThemeSnapshot.current(),
        title = { Text("Remove account", color = c.text) },
        text = {
            Text(
                "Remove \"${account.label}\"? Its saved credentials and isolated config on the " +
                    "server will be deleted. This does not affect the Claude account itself — only " +
                    "this app's ability to use it.",
                color = c.textDim,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Remove", color = c.disconnected) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = c.textDim) }
        },
    )
}

/**
 * Switch the account a LIVE session runs Claude under. Claude restarts to
 * pick up the new `CLAUDE_CONFIG_DIR`, but — unlike closing a session — the
 * SAME conversation resumes right after, so the copy says that explicitly
 * rather than let the user assume they'd lose it.
 */
@Composable
fun SwitchAccountDialog(
    accounts: List<ClaudeAccount>,
    currentSlug: String?,
    /**
     * This folder's policy. Nothing is hidden on its basis — a non-preferred pick
     * is offered and merely warned about, so the guard prevents a mis-tap without
     * locking the owner out of their own folder.
     */
    folderPolicy: com.clauderemote.model.FolderPolicy? = null,
    onDismiss: () -> Unit,
    onConfirm: (accountSlug: String?) -> Unit,
) {
    val c = CRTheme.colors
    var selected by remember { mutableStateOf(currentSlug) }
    val offPolicy = !com.clauderemote.model.isAccountPreferred(folderPolicy, selected)

    FloatingDialog(
        visible = true,
        onDismiss = onDismiss,
        theme = CRThemeSnapshot.current(),
        title = { Text("Switch account", color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Claude restarts in this session to switch accounts — the conversation " +
                        "itself is preserved and resumes right after.",
                    style = CRType.bodyDim, color = c.textDim,
                )
                if (offPolicy) {
                    Text(
                        "This folder isn't set up for that account. Switching anyway is fine — " +
                            "this is a reminder, not a restriction.",
                        style = CRType.bodyDim,
                        color = c.disconnected,
                    )
                }
                Spacer(Modifier.height(4.dp))
                accounts.forEach { acc ->
                    // Normalize the default account to the same null the rest of the
                    // account-slug plumbing uses for "no override" — see ClaudeAccount.kt.
                    val slug = if (acc.isDefault) null else acc.slug
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = slug },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(
                            selected = selected == slug,
                            onClick = { selected = slug },
                            colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.textDim),
                        )
                        Column {
                            Text(acc.label, style = CRType.cardTitle, color = c.text)
                            if (acc.subtitle.isNotBlank()) {
                                Text(acc.subtitle, style = CRType.bodyDim, color = c.textDim)
                            }
                            if (!com.clauderemote.model.isAccountPreferred(folderPolicy, slug)) {
                                Text(
                                    "not set up for this folder",
                                    style = CRType.monoTiny,
                                    color = c.disconnected,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != currentSlug,
                onClick = { onConfirm(selected) },
            ) {
                // The label itself carries the warning, so a mis-tap can't sail
                // through unnoticed without adding a second nested dialog.
                Text(
                    if (offPolicy) "Switch anyway" else "Switch",
                    color = if (offPolicy) c.disconnected else c.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = c.textDim) }
        },
    )
}
