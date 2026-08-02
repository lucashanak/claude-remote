package com.clauderemote.session.service

import com.clauderemote.connection.SshSessionHelper
import com.clauderemote.model.ClaudeAccount
import com.clauderemote.session.ClaudeConfig
import com.clauderemote.storage.ServerStorage
import com.clauderemote.util.FileLogger
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Same log tag as the rest of the session services so device-log lines for one
// user action stay on one grep.
private const val TAG = "SessionOrchestrator"

/**
 * Server-side Claude LOGIN management: enumerate the provisioned accounts,
 * provision a new config dir, drive `claude auth login` in a tmux pane, and drop
 * an account again.
 *
 * SECURITY: tokens never enter the app. `.credentials.json` is read only by
 * `claude` itself (and by [UsageService]'s server-side curl); everything this
 * service returns is LABELS — email / org / subscription — which is all the UI
 * needs to tell two seats apart.
 *
 * Layout per non-default account (`~/.claude-remote/accounts/<slug>/`):
 *   `.credentials.json`, `.claude.json`   — private to the account (identity)
 *   `projects/` → `~/.claude/projects/`   — SHARED, so transcripts, the Chat
 *   `plugins/`  → `~/.claude/plugins/`      view and streamd keep working
 *   `settings.json` → `~/.claude/settings.json`   unchanged no matter which
 *   `CLAUDE.md` → `~/.claude/CLAUDE.md`           account a session runs under
 *
 * The default account is deliberately NOT under `accounts/`: it keeps using
 * `~/.claude` with `CLAUDE_CONFIG_DIR` unset (see [com.clauderemote.model.claudeConfigDirFor]).
 */
internal class AccountService(
    private val registry: ConnectionRegistry,
    private val serverStorage: ServerStorage,
) {
    /**
     * Every login provisioned on [serverId], default account FIRST.
     *
     * ONE ssh exec loops over the account dirs server-side — a round-trip per
     * account would make the accounts screen scale badly on a cellular link.
     * Robust by design: a dir whose `auth status` probe fails still comes back
     * as an account (slug only, blank labels) rather than vanishing, so a broken
     * login stays visible and fixable instead of silently disappearing.
     */
    suspend fun listAccounts(serverId: String): List<ClaudeAccount> = withContext(Dispatchers.IO) {
        val out = try {
            withServerSession(serverId) { sess ->
                // 45 s: `claude auth status` is a process start (~1 s) per account,
                // and the first one after boot is the slowest.
                execReadWithWatchdog(sess, LIST_ACCOUNTS_CMD, totalMs = 45_000)
            }
        } catch (e: Exception) {
            FileLogger.error(TAG, "listAccounts failed for $serverId: ${e.message}", e)
            null
        }
        if (out.isNullOrBlank()) return@withContext emptyList()
        parseAccounts(out)
    }

    /**
     * Create (idempotently) the config dir for [slug] in the ONE order that
     * works — `mkdir -m 700` → onboarding seed → symlinks → *then* a session may
     * launch; see the doc on the provisioning script for why the order matters.
     * Returns false if the slug is unusable or the server refused.
     */
    suspend fun provisionAccount(serverId: String, slug: String): Boolean = withContext(Dispatchers.IO) {
        if (!isUsableSlug(slug)) {
            FileLogger.error(TAG, "provisionAccount refused: bad slug '$slug'", null)
            return@withContext false
        }
        val out = try {
            withServerSession(serverId) { sess ->
                execReadWithWatchdog(sess, provisionCmd(slug), totalMs = 20_000)
            }
        } catch (e: Exception) {
            FileLogger.error(TAG, "provisionAccount($slug) failed on $serverId: ${e.message}", e)
            null
        }
        val ok = out?.contains("PROVISIONED") == true
        FileLogger.log(TAG, "provisionAccount($slug) on $serverId: ${if (ok) "ok" else "FAILED — ${out?.trim()?.takeLast(200)}"}")
        ok
    }

    /**
     * `rm -rf` this account's config dir — and NOTHING else. The shared parts
     * live under `~/.claude/` and are only reachable through the symlinks inside
     * the dir; `rm -rf` unlinks those without following them, so the user's real
     * projects/settings/CLAUDE.md are untouched.
     *
     * Refused for a blank slug, for `default` (that's `~/.claude`, not ours to
     * delete) and for anything with a path separator or `..` in it.
     */
    suspend fun removeAccount(serverId: String, slug: String): Boolean = withContext(Dispatchers.IO) {
        if (!isUsableSlug(slug)) {
            FileLogger.error(TAG, "removeAccount refused: bad slug '$slug'", null)
            return@withContext false
        }
        val out = try {
            withServerSession(serverId) { sess ->
                execReadWithWatchdog(sess, removeCmd(slug), totalMs = 15_000)
            }
        } catch (e: Exception) {
            FileLogger.error(TAG, "removeAccount($slug) failed on $serverId: ${e.message}", e)
            null
        }
        val ok = out?.contains("REMOVED") == true
        FileLogger.log(TAG, "removeAccount($slug) on $serverId: ${if (ok) "ok" else "FAILED — ${out?.trim()?.takeLast(200)}"}")
        ok
    }

    /**
     * Start an interactive OAuth login for [slug]: provision the dir if needed,
     * then run `claude auth login` under it in a DETACHED tmux session named
     * `claude-login-<slug>`. Detached is what makes it drivable — the app's
     * existing screen-scraper reads the pane to surface the OAuth URL and types
     * the pasted code back, exactly as it already does for in-session `/login`.
     */
    suspend fun startLogin(serverId: String, slug: String): Boolean = withContext(Dispatchers.IO) {
        if (!isUsableSlug(slug)) {
            FileLogger.error(TAG, "startLogin refused: bad slug '$slug'", null)
            return@withContext false
        }
        // Provision FIRST and unconditionally: launching `claude` against a
        // missing/unseeded dir makes it run the onboarding wizard (which then
        // demands a second login) and lets it create REAL settings.json/plugins/
        // where the symlinks belong. provisionAccount is idempotent.
        if (!provisionAccount(serverId, slug)) return@withContext false
        try {
            withServerSession(serverId) { sess ->
                execReadWithWatchdog(
                    sess,
                    ClaudeConfig.buildTmuxLoginCommand(loginTmuxName(slug), slug),
                    totalMs = 20_000,
                )
            }
            FileLogger.log(TAG, "startLogin($slug) on $serverId: tmux ${loginTmuxName(slug)} launched")
            true
        } catch (e: Exception) {
            FileLogger.error(TAG, "startLogin($slug) failed on $serverId: ${e.message}", e)
            false
        }
    }

    /** tmux session the login pane for [slug] runs in — the UI attaches/scrapes this. */
    fun loginTmuxName(slug: String): String = Companion.loginTmuxName(slug)

    /**
     * The login pane's visible text, or null when there is no such pane (login
     * finished, cancelled, never started).
     *
     * The app's in-session login card is fed by the terminal emulator of an
     * ATTACHED tab; the login pane deliberately has no tab, so this
     * `capture-pane` is how the UI reads it — poll it to pick the OAuth URL out
     * and to see the `Paste code here if prompted >` prompt appear.
     */
    suspend fun readLoginScreen(serverId: String, slug: String): String? = withContext(Dispatchers.IO) {
        if (!isUsableSlug(slug)) return@withContext null
        val name = loginTmuxName(slug)
        try {
            val out = withServerSession(serverId) { sess ->
                // `=name` for has-session, `=name:` for the PANE target: a plain
                // `-t name` prefix-matches (so it could read a different pane), and
                // `=name` alone is rejected for a pane target ("can't find pane").
                // has-session is what actually gates it — capture-pane exits 0 even
                // on an unresolvable target on tmux 3.5a.
                execReadWithWatchdog(
                    sess,
                    "tmux has-session -t '=$name' 2>/dev/null && " +
                        "tmux capture-pane -p -t '=$name:' 2>/dev/null || echo __NO_LOGIN_PANE__",
                    totalMs = 10_000,
                )
            }
            if (out == null || out.contains("__NO_LOGIN_PANE__")) null else out
        } catch (e: Exception) {
            FileLogger.error(TAG, "readLoginScreen($slug) failed on $serverId: ${e.message}", e)
            null
        }
    }

    /**
     * Which account a RUNNING session is actually on, read from the `claude`
     * process's own `CLAUDE_CONFIG_DIR`. Null means the default login.
     *
     * The app's stored slug is a BELIEF and can be stale or absent — the session
     * may have been started by another device, adopted from the server manifest,
     * or persisted before the field existed. The chip must not claim an account
     * the process isn't on, so it is resolved from the process, not from storage.
     */
    suspend fun readSessionAccountSlug(serverId: String, tmuxSessionName: String): String? =
        withContext(Dispatchers.IO) {
            if (tmuxSessionName.isBlank()) return@withContext null
            val out = try {
                withServerSession(serverId) { sess ->
                    execReadWithWatchdog(sess, sessionAccountCmd(tmuxSessionName), totalMs = 10_000)
                }
            } catch (e: Exception) {
                FileLogger.error(TAG, "readSessionAccountSlug($tmuxSessionName) failed: ${e.message}", e)
                null
            } ?: return@withContext null
            parseSessionAccountSlug(out)
        }

    /**
     * The OAuth URL from the login pane, or null while it hasn't appeared yet.
     * Poll this after [startLogin] — the pane takes a second or two to render it.
     */
    suspend fun readLoginUrl(serverId: String, slug: String): String? {
        val pane = readLoginScreen(serverId, slug) ?: return null
        return extractLoginUrl(pane)
    }

    /**
     * Type [code] into the login pane and press Enter. `send-keys -l` sends it
     * LITERALLY (no key-name interpretation, so a code containing e.g. "Space"
     * isn't turned into a keypress), and the code is single-quote-escaped because
     * it is untrusted clipboard text.
     */
    suspend fun submitLoginCode(serverId: String, slug: String, code: String): Boolean =
        withContext(Dispatchers.IO) {
            if (!isUsableSlug(slug) || code.isBlank()) return@withContext false
            val target = "'=" + loginTmuxName(slug) + ":'"
            val sq = "'" + code.trim().replace("'", "'\\''") + "'"
            try {
                withServerSession(serverId) { sess ->
                    execReadWithWatchdog(
                        sess,
                        "tmux send-keys -t $target -l -- $sq 2>/dev/null; " +
                            "tmux send-keys -t $target Enter 2>/dev/null; echo SENT",
                        totalMs = 10_000,
                    )
                }?.contains("SENT") == true
            } catch (e: Exception) {
                FileLogger.error(TAG, "submitLoginCode($slug) failed on $serverId: ${e.message}", e)
                false
            }
        }

    /**
     * Kill the login pane (user cancelled, or the login finished). Exact-match
     * target so a session whose name merely STARTS with this one is never killed.
     * Absent pane counts as success.
     */
    suspend fun cancelLogin(serverId: String, slug: String): Boolean = withContext(Dispatchers.IO) {
        if (!isUsableSlug(slug)) return@withContext false
        try {
            withServerSession(serverId) { sess ->
                execReadWithWatchdog(
                    sess,
                    "tmux kill-session -t '=${loginTmuxName(slug)}' 2>/dev/null; echo KILLED",
                    totalMs = 10_000,
                )
            }?.contains("KILLED") == true
        } catch (e: Exception) {
            FileLogger.error(TAG, "cancelLogin($slug) failed on $serverId: ${e.message}", e)
            false
        }
    }

    // ======================== internals ========================

    /**
     * Run [block] on a usable transport to [serverId]: a live tab's connection
     * when there is one, else a one-off helper session (which itself reuses the
     * server's pooled transport when available). Null when the server is gone
     * from storage — the accounts screen can be opened before anything on that
     * server is connected, so "no live tab" must not mean "no accounts".
     */
    private suspend fun <T> withServerSession(serverId: String, block: suspend (Session) -> T): T? {
        registry.liveServerSession(serverId)?.let { return block(it) }
        val server = serverStorage.getServer(serverId) ?: run {
            FileLogger.error(TAG, "AccountService: no server $serverId in storage", null)
            return null
        }
        return SshSessionHelper.withSession(server, timeout = 10_000) { block(it) }
    }

    /**
     * A slug we're willing to touch on the filesystem. `default` is excluded on
     * purpose: it names the pre-existing `~/.claude` login, which has no dir
     * under `accounts/` — provisioning or deleting it is always a bug.
     */
    private fun isUsableSlug(slug: String): Boolean =
        slug.isNotBlank() &&
            slug != ClaudeAccount.DEFAULT_SLUG &&
            slug != "." && slug != ".." &&
            !slug.contains('/') &&
            !slug.contains("..") &&
            SLUG_CHARS.matches(slug)

    internal companion object {
        private const val BLOCK_MARKER = "==="

        /**
         * Pull the OAuth URL out of a captured login pane.
         *
         * The terminal HARD-WRAPS it across lines with no continuation marker, so
         * the tail has to be stitched back on or the link is truncated and the
         * sign-in page 404s. Continuation lines are the ones with no whitespace;
         * the wrap stops at the first blank line, at prose (which contains
         * spaces), or at the `Paste code here if prompted >` prompt.
         */
        internal fun extractLoginUrl(pane: String): String? {
            val lines = pane.lines()
            val start = lines.indexOfFirst { it.contains("https://") && it.contains("oauth") }
            if (start < 0) return null
            val head = lines[start].substringAfter("https://")
            val sb = StringBuilder("https://").append(head.trim())
            for (i in start + 1 until lines.size) {
                val s = lines[i].trim()
                if (s.isEmpty() || s.contains(' ') || s.startsWith("Paste")) break
                sb.append(s)
            }
            return sb.toString().takeIf { it.length > "https://".length }
        }

        /**
         * tmux session name for a slug's login pane.
         *
         * tmux SILENTLY REWRITES `.` in a session name (to `_`), because it uses
         * the dot as a window.pane separator in targets. Creating
         * `claude-login-a.b` therefore yields a session actually called
         * `claude-login-a_b`, and every later `-t '=claude-login-a.b'` fails with
         * "can't find session" — the pane exists but is unreachable, so the login
         * appears to never start. Since slugs gained `.` and `@`, sanitise here
         * rather than trusting tmux's exact rewrite rules.
         *
         * Two slugs differing only in a separator collapse to one pane name; that
         * is acceptable for a short-lived login pane and cannot affect the account
         * directories themselves, which keep the real slug.
         */
        internal fun loginTmuxName(slug: String): String =
            "claude-login-" + slug.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("")

        /**
         * Walk the tmux session's pane process and its children/grandchildren for a
         * `CLAUDE_CONFIG_DIR`. `claude` is usually the pane's direct child but sits a
         * level deeper when the pane runs a login shell first, so two levels are
         * checked rather than assuming a shape.
         */
        internal fun sessionAccountCmd(tmuxSessionName: String): String {
            val n = "'" + tmuxSessionName.replace("'", "'\\''") + "'"
            return PATH_PREAMBLE +
                "cfg() { tr '\\0' '\\n' < /proc/\$1/environ 2>/dev/null | " +
                "sed -n 's/^CLAUDE_CONFIG_DIR=//p' | head -1; }; " +
                "for p in \$(tmux list-panes -t \"=$n\" -F '#{pane_pid}' 2>/dev/null); do " +
                "for pid in \$p \$(pgrep -P \$p 2>/dev/null); do " +
                "d=\$(cfg \$pid); [ -n \"\$d\" ] && { echo \"\$d\"; exit 0; }; " +
                "for g in \$(pgrep -P \$pid 2>/dev/null); do " +
                "d=\$(cfg \$g); [ -n \"\$d\" ] && { echo \"\$d\"; exit 0; }; " +
                "done; done; done; echo __DEFAULT__"
        }

        /**
         * Slug out of a probed config dir. Anything that isn't a path under the
         * accounts root — including the `__DEFAULT__` marker and an empty read —
         * means the default login, which runs with no variable set at all.
         */
        internal fun parseSessionAccountSlug(out: String): String? {
            val line = out.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("+") }
                ?: return null
            if (line == "__DEFAULT__") return null
            val marker = "/.claude-remote/accounts/"
            val i = line.indexOf(marker)
            if (i < 0) return null
            return line.substring(i + marker.length).trim('/').substringBefore('/').ifBlank { null }
        }

        /** Filesystem-name charset — also keeps the slug shell-metachar-free. */
        private val SLUG_CHARS = Regex("^[A-Za-z0-9._@-]+$")

    /**
     * Split the probe output into `===<slug>` blocks and turn each into a
     * [ClaudeAccount]. Regex field extraction (no serialization dependency),
     * matching how [UsageService] parses the usage endpoint.
     */
    internal fun parseAccounts(out: String): List<ClaudeAccount> {
        val accounts = mutableListOf<ClaudeAccount>()
        var slug: String? = null
        val body = StringBuilder()
        fun flush() {
            val s = slug ?: return
            accounts.add(toAccount(s, body.toString()))
            body.setLength(0)
        }
        out.lineSequence().forEach { line ->
            if (line.startsWith(BLOCK_MARKER)) {
                flush()
                slug = line.removePrefix(BLOCK_MARKER).trim()
            } else if (slug != null) {
                body.append(line).append('\n')
            }
        }
        flush()
        // Default first, and never listed twice even if a stray `accounts/default`
        // dir exists on disk (it would be dead weight — the default account is
        // ~/.claude and gets no CLAUDE_CONFIG_DIR at all).
        return accounts
            .distinctBy { it.slug }
            .sortedByDescending { it.isDefault }
    }

    internal fun toAccount(slug: String, json: String): ClaudeAccount = ClaudeAccount(
        slug = slug,
        email = jsonString(json, "email"),
        orgName = jsonString(json, "orgName"),
        subscriptionType = jsonString(json, "subscriptionType"),
        isDefault = slug == ClaudeAccount.DEFAULT_SLUG,
    )

    private fun jsonString(json: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: ""

        /**
         * An SSH exec channel gets a NON-login shell, so `~/.local/bin` (where
         * claude lives) may be off PATH — the same reason the systemd restore
         * unit hardcodes a PATH. Prepend the usual install dirs before invoking
         * claude anywhere in these scripts.
         */
        private const val PATH_PREAMBLE =
            "export PATH=\"\$HOME/.local/bin:\$HOME/.npm-global/bin:\$PATH\"; "

        /**
         * Probe every login in ONE exec. Emits `===<slug>` then that account's
         * `claude auth status --json`.
         *
         * The default account is probed with `CLAUDE_CONFIG_DIR` **UNSET**, not
         * set to `$HOME/.claude`: the two are not equivalent (unset reads the
         * global `~/.claude.json`; setting it reads `~/.claude/.claude.json`,
         * which doesn't exist and would report a logged-out account while also
         * creating an empty file). Hence the two-branch `probe`.
         *
         * Credentials files carry NO identity, so `auth status --json` is the
         * only source for the labels. `|| true` + `timeout` keep one wedged or
         * logged-out account from failing the whole listing.
         */
        private val LIST_ACCOUNTS_CMD = PATH_PREAMBLE +
            "ROOT=\"\$HOME/.claude-remote/accounts\"; " +
            "TO=\"\"; command -v timeout >/dev/null 2>&1 && TO=\"timeout 15\"; " +
            "probe() { printf '===%s\\n' \"\$1\"; " +
            "if [ -z \"\$2\" ]; then \$TO claude auth status --json 2>/dev/null || true; " +
            "else CLAUDE_CONFIG_DIR=\"\$2\" \$TO claude auth status --json 2>/dev/null || true; fi; " +
            "printf '\\n'; }; " +
            "probe ${ClaudeAccount.DEFAULT_SLUG} \"\"; " +
            "if [ -d \"\$ROOT\" ]; then for d in \"\$ROOT\"/*; do " +
            "[ -d \"\$d\" ] || continue; probe \"\$(basename \"\$d\")\" \"\$d\"; " +
            "done; fi"

        /**
         * Entries under `~/.claude` that every account SHARES by symlink. Anything
         * not listed stays private to the account — credentials, `.claude.json`
         * (identity + trust map), and all the per-run state (`sessions/`,
         * `history.jsonl`, `statsig/`, `cache/`, `todos/`, …).
         *
         * `hud/` is not optional decoration: the `statusLine` command in
         * settings.json is
         * `sh ${'$'}{CLAUDE_CONFIG_DIR:-${'$'}HOME/.claude}/hud/omc-hud-cache.sh …`,
         * i.e. it resolves relative to the CONFIG DIR. Without the link an account
         * has no statusline at all — which also blanks the usage/working chips the
         * app scrapes out of it (see InputPromptDetector).
         *
         * `settings.local.json` carries the permission allowlist; omitting it
         * leaves the account prompting for every tool it should already trust.
         * The `.omc*` entries are oh-my-claudecode's own config/version/state, and
         * `hooks/` + `skills/` are user-authored extensions that belong to the
         * person, not to one login.
         *
         * A missing target is skipped rather than linked into a dangling symlink —
         * not every install has every one of these.
         */
        private val SHARED_ENTRIES = listOf(
            "projects", "plugins", "settings.json", "settings.local.json",
            "CLAUDE.md", "hud", "hooks", "skills",
            ".omc", ".omc-config.json", ".omc-version.json",
        )

        /**
         * PROVISIONING ORDER IS MANDATORY: `mkdir -m 700` → write the onboarding
         * seed → create the symlinks → only THEN may a session launch. Launch
         * first and `claude` creates REAL `settings.json`, `plugins/`, `cache/`
         * and `sessions/` inside the dir, which then collide with the symlinks —
         * the account silently loses the hooks and the OMC statusline the app
         * regex-scrapes, and nothing surfaces an error.
         *
         * `link()` removes a REAL file/dir sitting where a symlink belongs (a
         * session that launched before provisioning finished) — but only ever
         * inside this account's own dir. Nothing under `~/.claude` is ever
         * removed; those paths are only ever symlink TARGETS, and the two shared
         * directories are `mkdir -p`'d so the links can't dangle.
         *
         * The `.claude.json` seed is what actually skips the wizard: without
         * `hasCompletedOnboarding` + `lastOnboardingVersion` + `theme`, claude
         * runs onboarding and demands a SECOND login even though the credentials
         * it just wrote are valid. Never overwritten once present — it also
         * holds the account's project trust map after first use.
         */
        fun provisionCmd(slug: String): String = PATH_PREAMBLE +
            "set -e; SLUG='$slug'; " +
            "case \"\$SLUG\" in ''|.|..|*/*|${ClaudeAccount.DEFAULT_SLUG}) echo REFUSED; exit 1;; esac; " +
            "ROOT=\"\$HOME/.claude-remote/accounts\"; D=\"\$ROOT/\$SLUG\"; " +
            // -m only applies to dirs mkdir actually CREATES, so an already-present
            // dir (e.g. one a half-finished provision left behind) would keep its
            // umask mode — credentials live in here, so chmod it unconditionally.
            "mkdir -p -m 700 \"\$ROOT\" \"\$D\"; chmod 700 \"\$ROOT\" \"\$D\"; " +
            "if [ ! -f \"\$D/.claude.json\" ]; then " +
            "printf '%s\\n' '{\"hasCompletedOnboarding\":true," +
            "\"lastOnboardingVersion\":\"${ClaudeAccount.ONBOARDING_CLI_VERSION}\",\"theme\":\"dark\"}' " +
            "> \"\$D/.claude.json\"; chmod 600 \"\$D/.claude.json\"; fi; " +
            "mkdir -p \"\$HOME/.claude/projects\" \"\$HOME/.claude/plugins\"; " +
            "link() { tgt=\"\$1\"; lnk=\"\$D/\$2\"; " +
            "[ -e \"\$tgt\" ] || return 0; " +
            "if [ ! -L \"\$lnk\" ] && [ -e \"\$lnk\" ]; then rm -rf \"\$lnk\"; fi; " +
            // -n so an existing symlink-to-directory is REPLACED, not written into.
            "ln -sfn \"\$tgt\" \"\$lnk\"; }; " +
            "for n in ${SHARED_ENTRIES.joinToString(" ")}; do " +
            "link \"\$HOME/.claude/\$n\" \"\$n\"; done; " +
            "echo PROVISIONED"

        /**
         * Delete ONE account dir. Two independent guards (the slug `case` and the
         * `$D` prefix `case`) because the blast radius of getting this wrong is
         * the user's whole `~/.claude`. Already-absent counts as success so the
         * UI's remove is idempotent.
         */
        fun removeCmd(slug: String): String =
            "SLUG='$slug'; " +
            "case \"\$SLUG\" in ''|.|..|*/*|${ClaudeAccount.DEFAULT_SLUG}) echo REFUSED; exit 1;; esac; " +
            "D=\"\$HOME/.claude-remote/accounts/\$SLUG\"; " +
            "case \"\$D\" in \"\$HOME/.claude-remote/accounts/\"?*) ;; *) echo REFUSED; exit 1;; esac; " +
            "[ -e \"\$D\" ] || { echo REMOVED; exit 0; }; " +
            // rm -rf unlinks the symlinks inside $D; it does not follow them, so
            // ~/.claude/{projects,plugins,settings.json,CLAUDE.md} are safe.
            "rm -rf \"\$D\" && echo REMOVED"
    }
}
