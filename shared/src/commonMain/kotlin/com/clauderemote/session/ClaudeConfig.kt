package com.clauderemote.session

import com.clauderemote.model.ClaudeEffort
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.claudeConfigDirFor

/**
 * Claude Code CLI command builder and runtime control sequences.
 *
 * Runtime mode switching:
 * - Shift+Tab (\x1b[Z) toggles between normal/plan/auto-accept modes
 * - /model <alias> switches model immediately; /model alone opens interactive picker
 * - /plan [desc] enters plan mode
 * - /clear clears context
 * - /compact compacts context
 * - /rewind undoes changes
 * - /config opens settings
 *
 * Permission handling:
 * - Non-YOLO launches pass --allow-dangerously-skip-permissions, which keeps
 *   the permission gate at startup but lets the user opt into YOLO from the
 *   running session (so mode can be flipped without reconnecting)
 * - YOLO launches pass --dangerously-skip-permissions directly (starts in YOLO)
 */
object ClaudeConfig {

    fun buildLaunchCommand(
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel,
        claudeSessionId: String? = null,
        resume: Boolean = false,
        accountSlug: String? = null
    ): String = "cd ${shellEscape(folder)} && ${claudeInvocation(mode, model, claudeSessionId, resume, accountSlug)}"

    /**
     * `CLAUDE_CONFIG_DIR=<dir> ` prefix for a non-default account, or `""`.
     *
     * The empty string is load-bearing: `CLAUDE_CONFIG_DIR=$HOME/.claude` is NOT
     * the same as leaving the variable unset (unset ⇒ global config at
     * `~/.claude.json`; set ⇒ `~/.claude/.claude.json`, which doesn't exist, gets
     * created empty, and the session loses the project trust map + MCP config).
     * [claudeConfigDirFor] returns null for exactly that case, and then we must
     * emit NO prefix at all — see the doc on that function.
     *
     * The dir starts with `~`, which has to stay unquoted so the pane's shell
     * expands it, so it goes through the same [shellEscape] treatment as the
     * `cd <folder>` above: leading `~/` bare, the rest single-quoted. That keeps
     * the result safe under the double `sq()` wrapping in
     * [buildTmuxLaunchCommand] / [buildRestartCommand] (which already handle the
     * quotes `cd` contributes) even for a slug carrying shell metacharacters.
     */
    private fun configDirPrefix(accountSlug: String?): String {
        val dir = claudeConfigDirFor(accountSlug) ?: return ""
        return "CLAUDE_CONFIG_DIR=${shellEscape(dir)} "
    }

    /**
     * Just the `claude …` invocation (no `cd`), space-joined, optionally prefixed
     * with this session's account `CLAUDE_CONFIG_DIR=` (see [configDirPrefix]).
     * All argument tokens are plain (flags, model alias, uuid) — no shell
     * metacharacters — and the account prefix is [shellEscape]d, so this stays
     * safe to single-quote-wrap for `send-keys`/`bash -lc`.
     */
    private fun claudeInvocation(
        mode: ClaudeMode,
        model: ClaudeModel,
        claudeSessionId: String?,
        resume: Boolean,
        accountSlug: String? = null,
    ): String {
        // Local models launch the `claude-local` wrapper (which sets the
        // gateway env server-side); everything else launches plain `claude`.
        val claudeArgs = mutableListOf(if (model.isLocal) "claude-local" else "claude")

        if (model.cliValue != null) {
            claudeArgs.add("--model")
            claudeArgs.add(model.cliValue)
        }

        when (mode) {
            ClaudeMode.AUTO_ACCEPT -> { claudeArgs.add("--permission-mode"); claudeArgs.add("acceptEdits") }
            ClaudeMode.AUTO -> { claudeArgs.add("--permission-mode"); claudeArgs.add("auto") }
            ClaudeMode.PLAN, ClaudeMode.NORMAL -> {}
            ClaudeMode.YOLO -> {}
        }

        claudeArgs.add(
            if (mode == ClaudeMode.YOLO) "--dangerously-skip-permissions"
            else "--allow-dangerously-skip-permissions"
        )

        // Session-id flag: --resume reuses an existing conversation UUID,
        // --session-id forces a NEW session to use a deterministic UUID.
        // These are mutually exclusive — resume wins when both are set.
        if (claudeSessionId != null) {
            if (resume) {
                claudeArgs.add("--resume")
                claudeArgs.add(claudeSessionId)
            } else {
                claudeArgs.add("--session-id")
                claudeArgs.add(claudeSessionId)
            }
        }

        return configDirPrefix(accountSlug) + claudeArgs.joinToString(" ")
    }

    fun buildTmuxLaunchCommand(
        tmuxSessionName: String,
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel,
        claudeSessionId: String? = null,
        resume: Boolean = false,
        accountSlug: String? = null
    ): String = buildTmuxRunCommand(
        tmuxSessionName,
        buildLaunchCommand(folder, mode, model, claudeSessionId, resume, accountSlug),
    )

    /**
     * `claude auth login` under [accountSlug]'s own config dir — the ONLY
     * supported way to add a login (never `claude setup-token`: it hardcodes
     * scope=user:inference and ignores CLAUDE_CODE_OAUTH_SCOPES, which would
     * break MCP, uploads and the usage endpoint). Runs in a tmux pane so the
     * app's screen-scraper can read the OAuth URL and type the pasted code.
     */
    fun buildTmuxLoginCommand(tmuxSessionName: String, accountSlug: String): String =
        buildTmuxRunCommand(tmuxSessionName, configDirPrefix(accountSlug) + "claude auth login")

    /**
     * Create the anchored tmux session [tmuxSessionName] and `send-keys` the shell
     * command [claudeCmd] into its pane. Everything about the tmux SERVER (anchoring,
     * exit-empty, sizing, exact-match targets) lives here so every creator —
     * claude launches and the account-login pane alike — gets the same treatment.
     */
    private fun buildTmuxRunCommand(
        tmuxSessionName: String,
        claudeCmd: String,
    ): String {
        // POSIX single-quote wrap: a literal ' inside a '...' string must be
        // written as '\'' — NOT \' (you can't backslash-escape a quote inside
        // single quotes). The old `\'` produced an unterminated quote because
        // claudeCmd already contains the single-quoted `cd '<folder>'`, so the
        // launch hung at the bash continuation prompt and Claude never started.
        fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"
        // Anchor the tmux SERVER under the user systemd slice BEFORE creating a
        // session: a raw SSH-exec'd `tmux new-session` otherwise parents the
        // server under the ephemeral SSH login scope, so it dies when that scope
        // is torn down (the mid-life mass-death root cause). If NO server is
        // running yet, create it (with a keepalive __anchor__ session) via
        // `systemd-run --user --scope`, which parents the forked tmux server
        // under user-1000.slice so it survives this SSH session's teardown; the
        // real session below then attaches to that anchored server. If a server
        // already exists we attach as-is. Best-effort (`|| true`): if systemd-run
        // is unavailable we silently fall back to today's behavior.
        //   --unit=claude-tmux-server: a FIXED scope name, so a second
        //     concurrent create attempt fails cleanly at the systemd level
        //     ("unit already exists") instead of spawning a competing transient
        //     scope, and the unbounded run-*.scope leak stops (11 leaked scopes
        //     holding ~150 processes had to be reaped by hand).
        //   --property=LimitCORE=infinity: the server deaths are tmux SIGSEGV
        //     (status=11/SEGV), so let it dump a core for the backtrace.
        // Kill existing session with same name to avoid -A reattaching
        // and sending keystrokes into a running program
        return "export XDG_RUNTIME_DIR=\${XDG_RUNTIME_DIR:-/run/user/\$(id -u)}; " +
                // No --unit=<fixed name> and no --property=LimitCORE: a scope does
                // NOT accept LimitCORE ("Unknown assignment"), which made the whole
                // systemd-run fail, and a leftover unit of a fixed name makes it
                // fail silently too — either way `|| true` then let the CALLER
                // create the server in its own (possibly short-lived) cgroup.
                "tmux list-sessions >/dev/null 2>&1 || systemd-run --user --scope --quiet tmux new-session -d -x 200 -y 50 -s __anchor__ sleep infinity >/dev/null 2>&1 || true; " +
                // exit-empty off: keep the server alive when a kill-session/recreate
                // momentarily drops it to 0 sessions (else the whole server exits).
                "tmux set-option -g exit-empty off 2>/dev/null || true; " +
                // CRASH GUARD. Core dump (tmux 3.5a, ~/core):
                //   SIGSEGV clients_calculate_size() <- default_window_size()
                //           <- spawn_window() <- cmd_new_session_exec()
                // tmux died WHILE CREATING a session, sizing the new window from the
                // attached clients. `window-size manual` is NOT the cure — it is the
                // POISON: proven minimal repro on 3.5a, a config containing only
                // `set -g window-size manual` makes `tmux new-session` print "server
                // exited unexpectedly", while `latest` works. This app used to send
                // that option on every launch AND every attach, which is exactly why
                // "creating a session" reliably killed the whole server and all its
                // sessions. So: never set window-size at all (leave tmux's default),
                // and pass explicit -x/-y plus default-size so the new window's size
                // never has to be derived from a client.
                "tmux set-option -g default-size 200x50 2>/dev/null || true; " +
                // `-t '=name'`: plain `-t` prefix-matches, so killing "proj--cashy"
                // would also hit a live "proj--cashy-2" (measured on tmux 3.5a).
                // `=` forces an exact-name match for a target-session.
                "tmux kill-session -t ${sq("=" + tmuxSessionName)} 2>/dev/null; " +
                "tmux set-option -g history-limit 100000 2>/dev/null; " +
                "tmux new-session -x 200 -y 50 -s ${sq(tmuxSessionName)} " +
                "\\; set-option -g mouse on " +
                "\\; set-option -g history-limit 100000 " +
                "\\; send-keys ${sq(claudeCmd)} Enter"
    }

    /**
     * Restart the Claude Code process IN PLACE while keeping the conversation.
     *
     * `tmux respawn-pane -k` kills whatever is running in the pane (the current
     * `claude`, whether idle or mid-task) and restarts the pane's shell — WITHOUT
     * killing the tmux session, so any attached client (the app's terminal) stays
     * attached and simply sees the pane redraw. We then `send-keys` a fresh
     * `claude --resume <uuid>` into that shell, which reloads the SAME conversation
     * from its transcript (picking up e.g. a newly-installed Claude Code version).
     *
     * This is deliberately NOT `kill-session; new-session` (buildTmuxLaunchCommand):
     * that would detach the client and break the live terminal.
     */
    fun buildRestartCommand(
        tmuxSessionName: String,
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel,
        claudeSessionId: String,
        accountSlug: String? = null,
    ): String {
        // Restart claude in place, KEEPING both the conversation AND the folder.
        //
        // `respawn-pane` restarts the pane's command in the pane's START
        // directory (where new-session created it = $HOME for our launches), NOT
        // the cwd claude was running in. So an earlier no-`cd` version restarted
        // claude in $HOME — the wrong project — and `--resume <uuid>` (whose
        // transcript is keyed by cwd, ~/.claude/projects/<encoded-cwd>/) then
        // couldn't find the conversation. So we must re-`cd` to the folder.
        //
        // Reuse the PROVEN launch string (buildLaunchCommand → shellEscape,
        // which correctly handles ~, ~/x, absolute and relative-to-$HOME and is
        // injection-safe), prefixed with `cd "$HOME"` so the relative form
        // resolves the same as at launch regardless of the respawn start dir.
        // Run it via a LOGIN shell so ~/.profile puts ~/.local/bin (claude) on
        // PATH — the respawn shell alone lacked it ("command not found").
        //
        // Quoting: `sq` is applied TWICE (once wrapping the inner for `bash -lc`,
        // once wrapping that for `send-keys`) — verified across ~, ~/x, absolute,
        // relative and embedded-quote folders.
        //
        // [accountSlug] is what makes an in-place ACCOUNT switch possible: the
        // pane restarts under the new account's CLAUDE_CONFIG_DIR while
        // `--resume <uuid>` reloads the same conversation, which survives the
        // switch because `projects/` is symlinked to the shared `~/.claude/`.
        val inner = "cd \"\$HOME\"; " +
            buildLaunchCommand(folder, mode, model, claudeSessionId, resume = true, accountSlug = accountSlug)
        fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val loginRun = "bash -lc ${sq(inner)}"
        // `respawn-pane`/`send-keys` take a target-PANE, not target-session:
        // `-t '=name'` alone FAILS ("can't find pane") for a pane target, but
        // `-t '=name:'` (trailing colon) both works AND exact-matches, closing
        // the same prefix-match data-loss bug as kill-session above (measured
        // on tmux 3.5a: plain `-t` would respawn/send into a "name-2" sibling).
        val paneTarget = sq("=" + tmuxSessionName + ":")
        return "tmux respawn-pane -k -t $paneTarget 2>/dev/null; " +
                "sleep 0.4; " +
                "tmux send-keys -t $paneTarget ${sq(loginRun)} Enter"
    }

    // ======================== RUNTIME CONTROLS ========================

    /** Shift+Tab — toggles between modes (normal → plan → auto-accept) */
    const val SHIFT_TAB = "\u001B[Z"

    /** Alt+M — toggle mode (Linux) */
    const val ALT_M = "\u001Bm"

    /** Escape key */
    const val ESCAPE = "\u001B"

    /** /model — opens interactive model picker */
    const val CMD_MODEL = "/model\n"

    /** /plan — enter plan mode */
    const val CMD_PLAN = "/plan\n"

    /** /clear — clear context */
    const val CMD_CLEAR = "/clear\n"

    /** /compact — compact context */
    const val CMD_COMPACT = "/compact\n"

    /** /rewind — undo changes */
    const val CMD_REWIND = "/rewind\n"

    /** /config — open settings */
    const val CMD_CONFIG = "/config\n"

    /** /help — show help */
    const val CMD_HELP = "/help\n"

    /** Ctrl+C */
    const val CTRL_C = "\u0003"

    /** Tab */
    const val TAB = "\t"

    /** Arrow keys */
    const val ARROW_UP = "\u001B[A"
    const val ARROW_DOWN = "\u001B[B"
    const val ARROW_RIGHT = "\u001B[C"
    const val ARROW_LEFT = "\u001B[D"

    /** Enter */
    const val ENTER = "\r"

    // Model switch: `/model <alias>` selects immediately, no interactive picker.
    // No trailing newline — the caller (sendSlashCommand) types this char-by-
    // char and sends \r itself; a whole "text+\n" burst gets pasted as
    // literal text by Claude's TUI instead of executed.
    fun modelSwitchCommand(model: ClaudeModel): String = "/model ${model.cliValue ?: "default"}"

    // Effort switch: `/effort <level>` selects immediately, no interactive picker.
    fun effortSwitchCommand(effort: ClaudeEffort): String = "/effort ${effort.cliValue}"

    fun escapeSequence(): String = ESCAPE

    private fun shellEscape(path: String): String {
        // POSIX-safe single-quoting that also closes the injection vector when
        // `path` is untrusted (e.g. a cwd read from a .jsonl transcript on the
        // server). The old conditional quote left embedded `'` unescaped, so a
        // crafted cwd could break out of the `cd ...` command. Single-quote and
        // escape embedded quotes via the standard `'\''` idiom, but keep a
        // leading `~` / `~/` UNquoted so tilde expansion still works in the
        // pane's shell. Avoids bash-only `${x/#~/$HOME}` so POSIX sh works too.
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        return when {
            path == "~" -> "~"
            path.startsWith("~/") -> "~/" + q(path.substring(2))
            else -> q(path)
        }
    }
}
