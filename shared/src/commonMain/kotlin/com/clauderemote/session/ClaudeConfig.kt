package com.clauderemote.session

import com.clauderemote.model.ClaudeEffort
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel

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
        resume: Boolean = false
    ): String = "cd ${shellEscape(folder)} && ${claudeInvocation(mode, model, claudeSessionId, resume)}"

    /**
     * Just the `claude …` invocation (no `cd`), space-joined. All tokens are
     * plain (flags, model alias, uuid) — no shell metacharacters — so this is
     * safe to single-quote-wrap for `send-keys`/`bash -lc` without escaping.
     */
    private fun claudeInvocation(
        mode: ClaudeMode,
        model: ClaudeModel,
        claudeSessionId: String?,
        resume: Boolean,
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

        return claudeArgs.joinToString(" ")
    }

    fun buildTmuxLaunchCommand(
        tmuxSessionName: String,
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel,
        claudeSessionId: String? = null,
        resume: Boolean = false
    ): String {
        val claudeCmd = buildLaunchCommand(folder, mode, model, claudeSessionId, resume)
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
                // CRASH GUARD — must run BEFORE new-session. Core dump (tmux 3.5a):
                //   SIGSEGV clients_calculate_size() <- default_window_size()
                //           <- spawn_window() <- cmd_new_session_exec()
                // i.e. tmux crashed WHILE CREATING a session because it sized the new
                // window from the ATTACHED CLIENTS and walked a stale one. A server
                // that respawned after a crash starts with the default
                // window-size=latest, so creating a session on it took exactly that
                // path — which is why "creating a session" reliably killed the whole
                // server and every session in it. `manual` is the ONE value whose
                // default_window_size() branch never consults clients; pair it with an
                // explicit default-size and pass -x/-y so the size never comes from a
                // client. (Do NOT put window-size manual in ~/.tmux.conf — applied at
                // server STARTUP it crashes tmux outright; it is only safe at runtime.)
                "tmux set-option -g window-size manual 2>/dev/null || true; " +
                "tmux set-option -g default-size 200x50 2>/dev/null || true; " +
                "tmux kill-session -t ${sq(tmuxSessionName)} 2>/dev/null; " +
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
        val inner = "cd \"\$HOME\"; " + buildLaunchCommand(folder, mode, model, claudeSessionId, resume = true)
        fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val loginRun = "bash -lc ${sq(inner)}"
        return "tmux respawn-pane -k -t ${sq(tmuxSessionName)} 2>/dev/null; " +
                "sleep 0.4; " +
                "tmux send-keys -t ${sq(tmuxSessionName)} ${sq(loginRun)} Enter"
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
