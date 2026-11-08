package com.clauderemote.session

import com.clauderemote.model.ClaudeEffort
import com.clauderemote.model.ClaudeMode
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.TmuxNameParser
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
     * [buildLaunchCommand] where [resume] may be NULL: the app does not know
     * whether the conversation exists, so the SERVER decides at launch time by
     * looking for the transcript itself. That is the only safe answer to an
     * unknown: both guesses kill the pane when wrong — `--resume` on a missing
     * transcript exits "No conversation found", `--session-id` on an existing
     * one exits "Session ID … is already in use".
     */
    internal fun buildLaunchCommandOrServerDecided(
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel,
        claudeSessionId: String,
        resume: Boolean?,
        accountSlug: String?,
    ): String {
        if (resume != null) return buildLaunchCommand(folder, mode, model, claudeSessionId, resume, accountSlug)
        // The uuid is interpolated into a glob, so reduce it to the charset a
        // uuid actually has; anything else could not name a transcript anyway.
        val id = claudeSessionId.filter { it.isLetterOrDigit() || it == '-' }
        // Same check the drift daemon uses: the uuid is globally unique, so
        // globbing every project dir is enough and needs no cwd encoding.
        // An account whose `projects/` is a REAL directory rather than the
        // usual symlink (provisioned out of order) is looked at as well.
        val accountProjects = claudeConfigDirFor(accountSlug)
            ?.let { " || ls ${shellEscape(it)}/projects/*/$id.jsonl >/dev/null 2>&1" }
            .orEmpty()
        val resumeCmd = buildLaunchCommand(folder, mode, model, claudeSessionId, resume = true, accountSlug = accountSlug)
        val freshCmd = buildLaunchCommand(folder, mode, model, claudeSessionId, resume = false, accountSlug = accountSlug)
        return "if ls \"\$HOME\"/.claude/projects/*/$id.jsonl >/dev/null 2>&1$accountProjects; " +
            "then $resumeCmd; else $freshCmd; fi"
    }

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
        /** Null with a [claudeSessionId]: the server decides — see [buildLaunchCommandOrServerDecided]. */
        resume: Boolean? = false,
        accountSlug: String? = null,
        /**
         * What happens when a session of this name already exists (V14): a relaunch attaches it, a fresh launch refuses.
         * No default (L4): a caller that forgot it would silently adopt whatever holds the name.
         */
        onExisting: OnExisting,
    ): String = buildTmuxRunCommand(
        tmuxSessionName,
        if (claudeSessionId != null) {
            buildLaunchCommandOrServerDecided(folder, mode, model, claudeSessionId, resume, accountSlug)
        } else {
            buildLaunchCommand(folder, mode, model, null, false, accountSlug)
        },
        onExisting = onExisting,
    )

    /**
     * What the tmux anchor does when a session of the name it is told to create already exists (V14). The caller
     * knows which one it sends (`TmuxLaunchDecision`): a session that should exist, or a new one.
     */
    sealed interface OnExisting {
        /**
         * A relaunch of a session that should exist (Rebuild, `attach || relaunch`, resume): it may be alive in a
         * server whose socket was just healed (R5, N1), so it is attached, never killed and created again.
         * [command] is the caller's own attach (`SessionOrchestrator.buildAttachCommand`, with the single-client
         * preamble — S2); null = a plain `tmux attach-session`.
         */
        data class Attach(val command: String? = null) : OnExisting

        /**
         * A fresh launch: a name that is taken is refused (non-zero exit, a readable message on stderr) — the session
         * there is never killed and never adopted (the tab would show someone else's work under its own folder,
         * account and manifest entry). The app picks a free name first ([freshTmuxSessionName]) and creates the
         * session detached before its tab exists ([buildFreshCreateCommand], V16: no has-session, no kill-session —
         * `new-session` fails on a taken name); in a tab this mode is the backstop.
         */
        data object Refuse : OnExisting
    }

    /**
     * V14: the tmux name for a FRESH launch — the usual [TmuxNameParser.build] name when no session in [taken] holds
     * it, else the first free `-2`, `-3`, … The number goes before the `-yolo` / `--alias` tail, so
     * [TmuxNameParser.parse] still reads the mode and the alias back.
     */
    fun freshTmuxSessionName(
        serverName: String,
        folder: String,
        isYolo: Boolean,
        alias: String = "",
        taken: Collection<String>,
    ): String {
        val base = TmuxNameParser.build(serverName, folder, isYolo, alias)
        if (base !in taken) return base
        val tail = TmuxNameParser.build(serverName, "x", isYolo, alias).removePrefix(TmuxNameParser.build(serverName, "x", false))
        val stem = base.removeSuffix(tail)
        return generateSequence(2) { it + 1 }.map { "$stem-$it$tail" }.first { it !in taken }
    }

    /**
     * `claude auth login` under [accountSlug]'s own config dir — the ONLY
     * supported way to add a login (never `claude setup-token`: it hardcodes
     * scope=user:inference and ignores CLAUDE_CODE_OAUTH_SCOPES, which would
     * break MCP, uploads and the usage endpoint). Runs in a tmux pane so the
     * app's screen-scraper can read the OAuth URL and type the pasted code.
     */
    fun buildTmuxLoginCommand(tmuxSessionName: String, accountSlug: String): String =
        buildTmuxRunCommand(
            tmuxSessionName,
            configDirPrefix(accountSlug) + "claude auth login",
            // Detached: this runs over an exec channel with no TTY, and the pane is
            // never attached — the app reads it with capture-pane.
            detached = true,
            // ignored when detached (the login pane's path); spelled out because the parameter has no default (L4)
            onExisting = OnExisting.Attach(),
        )

    /**
     * Server path of the tmux socket guard that [buildTmuxRunCommand] runs before anything that may create a tmux
     * server. Written by `SessionPersistenceService.INSTALL_RESTORE_COMMAND` (marker v18).
     */
    internal const val SOCKHEAL_SCRIPT = "\$HOME/.claude-remote/tmux-sockheal.sh"

    /**
     * V16: a NEW session is created over an exec channel BEFORE any tab exists (`SessionOrchestrator.createFreshSession`):
     * `tmux new-session -d … -e CR_LAUNCH=<nonce>`, the same anchored command as a launch in a tab, only detached and
     * never over a session of that name (no has-session, no kill-session: `new-session` itself fails on a taken name,
     * atomically). Its stderr is folded into stdout and its exit status echoed ([FRESH_CREATE_RC]); then, if a session
     * of that name exists, its `CR_LAUNCH` ([FRESH_CREATE_OWNER], from `show-environment`), else [FRESH_CREATE_ABSENT].
     * The owner tells the app whether the session of that name is the one it just created (its nonce) — also when the
     * answer to an earlier try was lost and the retry ran into the session that try created.
     */
    const val FRESH_CREATE_RC = "__CR_CREATE_RC="
    const val FRESH_CREATE_OWNER = "__CR_CREATE_OWNER="
    const val FRESH_CREATE_ABSENT = "__CR_CREATE_ABSENT__"
    /** The session environment variable carrying the creating launch's nonce. */
    const val LAUNCH_ENV = "CR_LAUNCH"

    fun buildFreshCreateCommand(
        tmuxSessionName: String,
        folder: String,
        mode: ClaudeMode,
        model: ClaudeModel,
        claudeSessionId: String?,
        accountSlug: String? = null,
        nonce: String,
    ): String {
        require(nonce.isNotEmpty() && nonce.all { it.isLetterOrDigit() && it.code < 128 }) { "nonce must be ASCII alphanumeric" }
        // `--session-id <uuid>` (never --resume: the conversation is new) on every transport, mosh and ET included
        val claudeCmd = buildLaunchCommand(folder, mode, model, claudeSessionId, false, accountSlug)
        val run = buildTmuxRunCommand(tmuxSessionName, claudeCmd, detached = true, onExisting = OnExisting.Refuse, launchNonce = nonce)
        val target = "'" + ("=" + tmuxSessionName).replace("'", "'\\''") + "'"
        val runQ = "'" + run.replace("'", "'\\''") + "'"
        // K6-1: the server ends a hung creation itself (`timeout`, exit 124 = cut short = Lost) and each check after it,
        // so a stuck tmux never keeps sshd's session once the client gave up (vzor K5-2). A check cut short prints no
        // owner and no ABSENT line: Lost too, never "absent" or "taken".
        // N7-1: every tmux client writes into a temp file ([TMUX_OUT_FILE]), never into the channel's pipe or a `$(…)`: a
        // tmux client hands its stdout to the server, so a hung (stopped) server kept that pipe open after `timeout` killed
        // the client, and the channel — sshd's session — waited for an EOF until tmux recovered.
        return "$TMUX_OUT_FILE " +
            "{ timeout $FRESH_CREATE_SERVER_LIMIT_S sh -c $runQ; } >\"\$CR_F\" 2>&1 </dev/null; CR_RC=\$?; " +
            "cat \"\$CR_F\"; echo \"$FRESH_CREATE_RC\$CR_RC\"; " +
            "timeout $FRESH_CHECK_LIMIT_S tmux has-session -t $target </dev/null >/dev/null 2>&1; CR_HS=\$?; " +
            "if [ \$CR_HS -eq 0 ]; then timeout $FRESH_CHECK_LIMIT_S tmux show-environment -t $target $LAUNCH_ENV </dev/null >\"\$CR_F\" 2>/dev/null; " +
            "CR_X=\$?; CR_OW=\$(cat \"\$CR_F\"); [ \$CR_X -ne 124 ] && echo \"$FRESH_CREATE_OWNER\$CR_OW\"; " +
            "elif [ \$CR_HS -ne 124 ]; then echo $FRESH_CREATE_ABSENT; fi"
    }

    /**
     * N7-1: the temp file (`$CR_F`) the tmux clients of a creation / a cleanup / a probe write into. Removed on every way
     * out (review C1-1, R15b): the end of the command, and a client that gave up (the shell's SIGPIPE/HUP/TERM).
     */
    internal const val TMUX_OUT_FILE = "CR_F=\$(mktemp) || exit 1; trap 'rm -f \"\${CR_F:?}\"' EXIT; trap 'exit 1' HUP PIPE TERM;"

    /**
     * K6-1: the server's limit on the creation itself (socket guard, anchor, `new-session`), in seconds. With the two
     * checks after it ([FRESH_CHECK_LIMIT_S] each) it stays below the client's limit (`SessionOrchestrator.FRESH_CREATE_MS`,
     * 20 s): a creation the client gave up on is over on the server too. Needs coreutils `timeout` on the server (as the
     * listing does): without it every creation fails with the server's "timeout: not found".
     */
    const val FRESH_CREATE_SERVER_LIMIT_S = 10
    /** K6-1: the server's limit on each tmux check after the creation (has-session, show-environment) and on the cleanup's. */
    const val FRESH_CHECK_LIMIT_S = 3
    /** R6-2/R6-3: what [buildOwnSessionCleanupCommand] prints once it killed this launch's session. */
    const val FRESH_CLEANED = "__CR_CLEANED__"
    /** LOW4: the cleanup could not tell (a tmux check cut short by its limit): the session may still be there. */
    const val FRESH_CLEANUP_UNKNOWN = "__CR_CLEANUP_UNKNOWN__"
    /** LOW3: the session is this launch's, but a client is attached to it (another device took it over): it stays. */
    const val FRESH_CLEANUP_ATTACHED = "__CR_CLEANUP_ATTACHED__"

    /**
     * R6-2/R6-3: kill the session [tmuxSessionName] only when it is this launch's — its `CR_LAUNCH` is [nonce] — for a
     * launch that ends without a tab after its creation may have run (cancelled, every answer lost). A session of the
     * name without that nonce (another device's, a user's) is never touched; nor one of ours a client is attached to
     * (LOW3: the launch that gives up has no client, so any client is another device that took the session over:
     * [FRESH_CLEANUP_ATTACHED]). A check cut short by its limit prints [FRESH_CLEANUP_UNKNOWN] (LOW4). Like the creation,
     * the tmux clients write into a temp file, not a pipe (N7-1).
     */
    fun buildOwnSessionCleanupCommand(tmuxSessionName: String, nonce: String): String {
        require(nonce.isNotEmpty() && nonce.all { it.isLetterOrDigit() && it.code < 128 }) { "nonce must be ASCII alphanumeric" }
        val target = "'" + ("=" + tmuxSessionName).replace("'", "'\\''") + "'"
        val t = "timeout $FRESH_CHECK_LIMIT_S tmux"
        // review C1-1 (HIGH): display-message takes a PANE target — `=name` alone resolves to nothing (empty output,
        // rc 0) and the cleanup never killed anything; `=name:` is the session's current pane
        val paneTarget = "'" + ("=" + tmuxSessionName + ":").replace("'", "'\\''") + "'"
        return "$TMUX_OUT_FILE " +
            "$t show-environment -t $target $LAUNCH_ENV </dev/null >\"\$CR_F\" 2>/dev/null; CR_X=\$?; CR_V=\$(cat \"\$CR_F\"); " +
            "if [ \$CR_X -eq 124 ]; then echo $FRESH_CLEANUP_UNKNOWN; " +
            "elif [ \"\$CR_V\" = '$LAUNCH_ENV=$nonce' ]; then " +
            "$t display-message -p -t $paneTarget '#{session_attached}' </dev/null >\"\$CR_F\" 2>/dev/null; CR_X=\$?; CR_A=\$(cat \"\$CR_F\"); " +
            "if [ \$CR_X -eq 124 ]; then echo $FRESH_CLEANUP_UNKNOWN; " +
            "elif [ \"\$CR_A\" = 0 ]; then $t kill-session -t $target </dev/null >/dev/null 2>&1; CR_X=\$?; " +
            "if [ \$CR_X -eq 0 ]; then echo $FRESH_CLEANED; elif [ \$CR_X -eq 124 ]; then echo $FRESH_CLEANUP_UNKNOWN; fi; " +
            "elif [ -n \"\$CR_A\" ]; then echo $FRESH_CLEANUP_ATTACHED; fi; " +
            "fi"
    }

    /**
     * Create the anchored tmux session [tmuxSessionName] and `send-keys` the shell
     * command [claudeCmd] into its pane. Everything about the tmux SERVER (anchoring,
     * exit-empty, sizing, exact-match targets) lives here so every creator —
     * claude launches and the account-login pane alike — gets the same treatment.
     */
    private fun buildTmuxRunCommand(
        tmuxSessionName: String,
        claudeCmd: String,
        /**
         * Create the session DETACHED. Required whenever this command is sent over
         * an SSH *exec* channel, which has no TTY: an attaching `new-session` dies
         * with "open terminal failed: not a terminal" and the session is never
         * created at all. Terminal launches leave this false — they run on the
         * PTY channel and the app attaches that session immediately.
         */
        detached: Boolean = false,
        /** Ignored when [detached] (the login pane keeps its old path). No default (L4). */
        onExisting: OnExisting,
        /** V16: the creating launch's nonce, set as the session's [LAUNCH_ENV] (detached fresh creation only). */
        launchNonce: String? = null,
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
        //
        // SOCKET GUARD first (installed with restore.sh/drift.sh, marker v18): when a tmux server is alive on the
        // default socket path but its socket file is gone, `tmux list-sessions` below fails and this command would
        // start a SECOND server next to it — on 2026-11-02 that duplicated every session. The helper exits
        //   0  nothing to heal: everything below as before;
        //   3  the socket was unusable and is back (SIGUSR1 / chmod 700);
        //   anything else  refused (the helper printed why): NOTHING below runs and the command fails.
        // Absent helper (older server install): the previous behaviour.
        // A session of this name that EXISTS is never killed and created again (V14), whatever the guard's exit:
        //  - OnExisting.Attach (a relaunch: Rebuild, `attach || relaunch`, resume) attaches it (R5, N1): this tab may
        //    have healed the socket (3), or another tab or a drift tick healed it a moment earlier (0), and the
        //    session ran in that server all along. The caller's attach carries the single-client preamble (S2). The
        //    kill-session below stays for this mode: it acts only when a second creator of the same name wins the
        //    moment between has-session and it (two creators of one name, as before this guard).
        //  - OnExisting.Refuse (a fresh launch) refuses with a non-zero exit: no attach (that adopted someone else's
        //    session, a bare shell or a dead pane under this tab's folder, account and manifest entry), no kill (that
        //    killed live work). Nor in the race: no kill-session, so new-session fails on a name taken meanwhile.
        // The detached login pane is never attached and keeps the old path (its point is a fresh `claude auth login`).
        val heal = "\"$SOCKHEAL_SCRIPT\""
        val target = sq("=" + tmuxSessionName)
        val existing = when {
            detached -> ""
            onExisting is OnExisting.Attach ->
                "elif tmux has-session -t $target 2>/dev/null; then ${onExisting.command ?: "tmux attach-session -t $target"}; "
            else -> "elif tmux has-session -t $target 2>/dev/null; then " +
                "echo ${sq("claude-remote: tmux session $tmuxSessionName already exists — not killing it, not attaching it; start the new session under another name")} >&2; false; "
        }
        return "export XDG_RUNTIME_DIR=\${XDG_RUNTIME_DIR:-/run/user/\$(id -u)}; " +
                "CR_SG=0; [ -x $heal ] && { $heal; CR_SG=\$?; }; " +
                "if [ \$CR_SG -ne 0 ] && [ \$CR_SG -ne 3 ]; then " +
                "echo \"claude-remote: not starting a tmux server (socket guard exit \$CR_SG, see ~/.claude-remote/sockheal.log)\" >&2; false; " +
                existing +
                "else " +
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
                // Never for a fresh launch (Refuse): in a tab or detached (V16), a taken name fails new-session.
                (if (onExisting is OnExisting.Refuse) "" else "tmux kill-session -t $target 2>/dev/null; ") +
                "tmux set-option -g history-limit 100000 2>/dev/null; " +
                "tmux new-session ${if (detached) "-d " else ""}-x 200 -y 50 -s ${sq(tmuxSessionName)} " +
                (if (launchNonce != null) "-e $LAUNCH_ENV=$launchNonce " else "") +
                "\\; set-option -g mouse on " +
                "\\; set-option -g history-limit 100000 " +
                "\\; send-keys ${sq(claudeCmd)} Enter; fi"
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
        /**
         * False when the conversation doesn't exist yet, which is the case for a
         * session nobody has spoken to. `--resume` on a missing transcript makes
         * claude exit with "No conversation found with session ID", killing the
         * restart; `--session-id` starts that same uuid instead, so nothing is
         * lost and later restarts resume normally.
         *
         * NULL means the caller does not know, and the SERVER decides at launch
         * time by looking for the transcript itself. That is the only safe answer
         * to a failed probe: both guesses kill the pane when wrong — `--resume`
         * on a missing transcript exits "No conversation found", `--session-id`
         * on an existing one exits "Session ID … is already in use". The second
         * is what happened to a live session whose account switch hit a
         * momentary `channel is not opened`: the probe threw, the old code
         * assumed "no conversation", and a 310-entry conversation came back as a
         * dead bash prompt.
         */
        resume: Boolean? = true,
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
        val launch = buildLaunchCommandOrServerDecided(folder, mode, model, claudeSessionId, resume, accountSlug)
        val inner = "cd \"\$HOME\"; " + launch
        fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val loginRun = "bash -lc ${sq(inner)}"
        // `respawn-pane`/`send-keys` take a target-PANE, not target-session:
        // `-t '=name'` alone FAILS ("can't find pane") for a pane target, but
        // `-t '=name:'` (trailing colon) both works AND exact-matches, closing
        // the same prefix-match data-loss bug as kill-session above (measured
        // on tmux 3.5a: plain `-t` would respawn/send into a "name-2" sibling).
        val paneTarget = sq("=" + tmuxSessionName + ":")
        // The respawn names its command, a plain login shell. Bare, it reruns the
        // pane's START command — and a pane rebuilt by restore.sh was started as
        // `claude --resume <uuid>` under the account it had THEN. That claude
        // came back up on the old login, and the send-keys below then typed the
        // launch line into Claude's prompt and pressed Enter: the account never
        // changed and Claude received a shell command as a message.
        return "tmux respawn-pane -k -t $paneTarget 'bash -l' 2>/dev/null; " +
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
