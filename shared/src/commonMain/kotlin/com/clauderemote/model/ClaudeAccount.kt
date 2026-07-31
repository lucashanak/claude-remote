package com.clauderemote.model

/**
 * A Claude login provisioned on the server, identified for the UI by its labels
 * only — access/refresh tokens never leave the server, so nothing here is secret.
 *
 * Each non-default account owns a `CLAUDE_CONFIG_DIR` under
 * `~/.claude-remote/accounts/<slug>/`, which isolates its credentials and its
 * `.claude.json` identity while symlinking the shared parts (`projects/`,
 * `plugins/`, `settings.json`, `CLAUDE.md`) back to `~/.claude/`.
 */
data class ClaudeAccount(
    /** Stable directory name under `~/.claude-remote/accounts/`. */
    val slug: String,
    val email: String = "",
    val orgName: String = "",
    val subscriptionType: String = "",
    /**
     * True for the pre-existing `~/.claude` login. It is NOT stored under
     * `accounts/` and must run with `CLAUDE_CONFIG_DIR` unset — see
     * [claudeConfigDirFor].
     */
    val isDefault: Boolean = false,
) {
    /** Primary UI label. Falls back to the slug for an account we couldn't probe. */
    val label: String get() = email.ifBlank { slug }

    /**
     * Compact label for a status-bar chip. The org name is what distinguishes two
     * seats at a glance ("Nekrachni" vs "Kontexta"); a full email would crowd the
     * chip row off a phone screen.
     */
    val shortLabel: String
        get() = orgName.ifBlank { email.substringBefore('@').ifBlank { slug } }

    /** Secondary UI line, e.g. "Nekrachni · team". */
    val subtitle: String
        get() = listOf(orgName, subscriptionType).filter { it.isNotBlank() }.joinToString(" · ")

    companion object {
        /** Slug reserved for the default `~/.claude` login. */
        const val DEFAULT_SLUG = "default"

        /** Server-side root holding one config dir per non-default account. */
        const val ACCOUNTS_ROOT = "~/.claude-remote/accounts"

        /** Onboarding keys a fresh config dir needs so it doesn't run the wizard. */
        const val ONBOARDING_CLI_VERSION = "2.1.220"
    }
}

/**
 * The `CLAUDE_CONFIG_DIR` a session should run with, or **null when the env var
 * must be left unset entirely**.
 *
 * This null is load-bearing, not a convenience: `CLAUDE_CONFIG_DIR=$HOME/.claude`
 * is NOT equivalent to leaving it unset. Unset resolves the global config to
 * `~/.claude.json`; setting it to `$HOME/.claude` resolves it to
 * `~/.claude/.claude.json`, which does not exist and gets created empty — the
 * session then loses the project trust map, MCP config and account identity.
 * So the default account must never get the variable at all.
 */
fun claudeConfigDirFor(accountSlug: String?): String? =
    if (accountSlug.isNullOrBlank() || accountSlug == ClaudeAccount.DEFAULT_SLUG) null
    else "${ClaudeAccount.ACCOUNTS_ROOT}/$accountSlug"

/**
 * Derives a filesystem-safe slug from an account email. Collision-proof enough
 * that two seats in the same org don't clash (which keying on the org would).
 */
fun accountSlugFromEmail(email: String): String {
    val cleaned = email.lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
    return cleaned.take(48).ifBlank { "account" }
}

/**
 * Per-folder account policy. Enforcement is deliberately SOFT — the app narrows
 * what it offers, the server does not refuse anything. Every device is the
 * owner's, so this guards against mis-taps (personal repo under a work seat),
 * not against an adversary.
 */
data class FolderPolicy(
    /** Preferred account for new sessions here; null means "no preference". */
    val defaultAccountSlug: String? = null,
    /** Accounts offerable here. **Empty means unrestricted**, not "none". */
    val allowedAccountSlugs: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = defaultAccountSlug == null && allowedAccountSlugs.isEmpty()
}

/**
 * Accounts offerable for a folder. An empty allow-set means unrestricted; a
 * non-empty one that matches nothing (accounts were removed since it was set)
 * also falls back to the full list rather than locking the user out of their
 * own folder.
 */
fun allowedAccountsFor(policy: FolderPolicy?, all: List<ClaudeAccount>): List<ClaudeAccount> {
    val allow = policy?.allowedAccountSlugs ?: emptySet()
    if (allow.isEmpty()) return all
    val filtered = all.filter { it.slug in allow }
    return filtered.ifEmpty { all }
}

/**
 * Which account a new session in this folder should start on: the folder's
 * default when it is still offerable, otherwise the first offerable one.
 */
fun defaultAccountFor(policy: FolderPolicy?, all: List<ClaudeAccount>): ClaudeAccount? {
    val offerable = allowedAccountsFor(policy, all)
    val preferred = policy?.defaultAccountSlug
    return offerable.firstOrNull { it.slug == preferred } ?: offerable.firstOrNull()
}
